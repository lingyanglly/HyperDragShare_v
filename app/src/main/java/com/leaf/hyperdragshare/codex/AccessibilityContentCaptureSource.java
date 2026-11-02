package com.leaf.hyperdragshare.codex;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;

/** Coordinates Root pointer events, one node lookup, and optional one-shot screenshot capture. */
final class AccessibilityContentCaptureSource {
    private static final String TAG = "DragShare/Accessibility";

    private final DragShareAccessibilityService service;
    private final DragShareController controller;
    private final java.util.function.BooleanSupplier rootInputReady;
    private final ExecutorService classifierExecutor;
    private final AccessibilityScreenshotter screenshotter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LongPressGestureDetector gestureDetector;

    AccessibilityContentCaptureSource(
            DragShareAccessibilityService service,
            DragShareController controller,
            java.util.function.BooleanSupplier rootInputReady,
            ExecutorService classifierExecutor,
            AccessibilityScreenshotter screenshotter) {
        this.service = service;
        this.controller = controller;
        this.rootInputReady = rootInputReady;
        this.classifierExecutor = classifierExecutor;
        this.screenshotter = screenshotter;
        ViewConfiguration configuration = ViewConfiguration.get(service);
        DragShareSettings settings = DragShareSettings.readLocal(service);
        // A scrolling finger must cancel the hold, and a small move must start the drag. The
        // old 2x+16dp tolerance made slow scrolls pass as long presses and delayed the drag
        // start until the finger had travelled a long way.
        float tolerantTouchSlop = Math.max(
                configuration.getScaledTouchSlop(),
                service.getResources().getDisplayMetrics().density * 8f)
                * settings.accessibilityTouchSlopMultiplier();
        this.gestureDetector = new LongPressGestureDetector(
                settings.resolveAccessibilityLongPressTimeoutMillis(
                        ViewConfiguration.getLongPressTimeout()),
                tolerantTouchSlop,
                new LongPressGestureDetector.Callback() {
                    @Override
                    public boolean isLongPressAllowed() {
                        return service.isAccessibilityCaptureEnabled()
                                && rootInputReady != null
                                && rootInputReady.getAsBoolean();
                    }

                    @Override
                    public void onLongPress(long gestureId, float stableX, float stableY) {
                        service.trace("gesture=" + gestureId + " long press point="
                                + Math.round(stableX) + "," + Math.round(stableY));
                        captureAt(gestureId, stableX, stableY, false);
                    }

                    @Override
                    public void onDrag(int action, float x, float y, long eventTime) {
                        controller.acceptPointerEvent(action, x, y, eventTime, "root");
                    }

                    @Override
                    public void onGestureCancelled(long gestureId) {
                        service.trace("gesture=" + gestureId + " cancelled");
                        screenshotter.cancelAll();
                        controller.cancelActiveSession();
                    }
                });
    }

    void onPointerEvent(int action, float x, float y, long eventTime) {
        // The accessibility runtime owns this root stream instead of the portal hooks, so the
        // controller must be told input is alive or it refuses to paint the overlay.
        controller.noteRootPointer();
        LongPressGestureDetector.State before = gestureDetector.state();
        gestureDetector.onPointerEvent(action, x, y, eventTime);
        LongPressGestureDetector.State after = gestureDetector.state();
        if (action == MotionEvent.ACTION_DOWN) {
            service.trace("gesture=" + gestureDetector.currentGestureId()
                    + " pending=" + (after == LongPressGestureDetector.State.PENDING_LONG_PRESS));
        } else if (before == LongPressGestureDetector.State.PENDING_LONG_PRESS
                && after == LongPressGestureDetector.State.IGNORED_UNTIL_UP) {
            service.trace("gesture=" + gestureDetector.currentGestureId()
                    + " rejected movement point=" + Math.round(x) + "," + Math.round(y));
        } else if (action == MotionEvent.ACTION_MOVE) {
            flushDeferredShowIfMoved(x, y);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            discardDeferredShow();
        }
    }

    void cancel() {
        DragReadyIsland.dismiss(service);
        gestureDetector.cancel();
        screenshotter.cancelAll();
        controller.cancelActiveSession();
    }

    /** The content is captured with the hold; it becomes visible only after this much move. */
    private static final float SHOW_MOVE_SLOP_DP = GestureMath.DRAG_SHOW_SLOP_DP;
    private final Object deferredShowLock = new Object();
    private CapturedContent deferredShowContent;
    private long deferredShowGestureId;
    private float deferredShowAnchorX;
    private float deferredShowAnchorY;
    /**
     * One hand-off per press. The deferred reveal and a late flush can both reach the reveal
     * path for the same gesture; the second copy made the portal drop the island the first one
     * had already taken down and post a fresh one, which is what the user saw as the island
     * blinking in the middle of a drag.
     */
    private long handedOffGestureId;

    /**
     * The reminder belongs to the gesture, not to one capture path. When this path gives up but
     * the finger is still down, the portal may still be holding the same island for the same
     * press, so taking it down here made the island "flash and vanish" and then left the share
     * window blocked by the "no island, no window" rule.
     */
    private void dismissReminderUnlessLive(long gestureId) {
        if (!gestureDetector.isCapturing(gestureId)) {
            DragReadyIsland.dismiss(service);
        }
    }

    private void captureAt(long gestureId, float stableX, float stableY, boolean retried) {        try {
            classifierExecutor.execute(() -> {
                if (!gestureDetector.isCapturing(gestureId)) {
                    return;
                }
                AccessibilityCandidateSelector.Selection selection = service.selectCandidateAt(
                        stableX,
                        stableY,
                        gestureId);
                if (selection == null || selection.candidate == null) {
                    service.trace("gesture=" + gestureId + " candidate missing retry=" + !retried);
                    if (!retried) {
                        mainHandler.postDelayed(
                                () -> captureAt(gestureId, stableX, stableY, true),
                                50L);
                    } else {
                        mainHandler.post(() -> {
                            dismissReminderUnlessLive(gestureId);
                            gestureDetector.captureFailed(gestureId);
                        });
                    }
                    return;
                }
                DragShareSettings settings = DragShareSettings.readLocal(service);
                if (!settings.isAccessibilityCaptureMode()
                        || !settings.isSharingEnabled(selection.isImage())) {
                    service.trace("gesture=" + gestureId + " sharing disabled");
                    mainHandler.post(() -> {
                        dismissReminderUnlessLive(gestureId);
                        gestureDetector.captureFailed(gestureId);
                    });
                    return;
                }
                AccessibilityCandidate candidate = selection.candidate;
                boolean hookedApp = ViewDumpClient.supports(candidate.sourcePackage);
                if (!hookedApp || selection.isImage()) {
                    // Arm the reminder as soon as the press is known to be shareable. Waiting for
                    // the image extraction (a root round trip that costs ~0.5s in the hooked app)
                    // made the island arrive after the finger had already moved, so it looked
                    // like the reminder only worked sometimes.
                    DragReadyIsland.setExpiryListener(this::cancel);
                    DragReadyIsland.show(service);
                    controller.noteReminderArmed();
                }
                if (hookedApp) {
                    // Ask the injected app first: a real ImageView renders to a pure image.
                    Bitmap pure = ViewDumpClient.request(stableX, stableY);
                    if (pure != null) {
                        service.trace("gesture=" + gestureId + " pure image captured size="
                                + pure.getWidth() + "x" + pure.getHeight());
                        CapturedContent content = CapturedContent.image(
                                pure,
                                candidate.sourcePackage,
                                candidate.bounds,
                                true);
                        handOffFoundContent(gestureId, content);
                        return;
                    }
                    if (!selection.isImage()) {
                        // Text containers (comment list, "关闭工作表", "up主头像") are not
                        // draggable content in this app; never crop or copy them.
                        service.trace("gesture=" + gestureId
                                + " no ImageView and no image region; skipping");
                        mainHandler.post(() -> {
                            dismissReminderUnlessLive(gestureId);
                            gestureDetector.captureFailed(gestureId);
                        });
                        return;
                    }
                    // Image region without an ImageView (e.g. the video surface): fall through
                    // to the accessibility screenshot of that region.
                }
                if (!selection.isImage()) {
                    service.trace("gesture=" + gestureId + " text captured");
                    CapturedContent content = CapturedContent.text(
                            candidate.text,
                            candidate.sourcePackage,
                            candidate.bounds);
                    handOffFoundContent(gestureId, content);
                    return;
                }
                screenshotter.capture(gestureId, candidate.bounds, new AccessibilityScreenshotter.Callback() {
                    @Override
                    public void onSuccess(long completedGestureId, Bitmap bitmap) {
                        service.trace("gesture=" + completedGestureId + " screenshot captured size="
                                + bitmap.getWidth() + "x" + bitmap.getHeight());
                        CapturedContent content = CapturedContent.image(
                                bitmap,
                                candidate.sourcePackage,
                                candidate.bounds,
                                true);
                        handOffFoundContent(completedGestureId, content);
                    }

                    @Override
                    public void onFailure(long completedGestureId, Throwable error) {
                        mainHandler.post(() -> {
                            DragShareLog.w(TAG, "gesture=" + completedGestureId
                                    + " image capture failed", error);
                            service.trace("gesture=" + completedGestureId
                                    + " screenshot failed=" + error.getClass().getSimpleName());
                            dismissReminderUnlessLive(completedGestureId);
                            gestureDetector.captureFailed(completedGestureId);
                        });
                    }
                });
            });
        } catch (RuntimeException error) {
            DragShareLog.w(TAG, "gesture=" + gestureId + " classifier unavailable", error);
            mainHandler.post(() -> {
                dismissReminderUnlessLive(gestureId);
                gestureDetector.captureFailed(gestureId);
            });
        }
    }

    /**
     * Hands the freshly captured content to the shared overlay. It used to be dropped whenever a
     * portal pick had been seen in the last 1.5s, on the assumption that the portal was already
     * going to paint this gesture. When that assumption was wrong (the portal picked but then
     * produced no window, or the pick belonged to the previous press) the reminder stayed on
     * screen with nothing behind it, which is the "island appeared but no menu" report. The
     * portal controller keeps the newest request, so handing the content over is always safe.
     */
    private void handOffFoundContent(long gestureId, CapturedContent content) {
        mainHandler.post(() -> showCapturedContent(gestureId, content));
    }

    private void showCapturedContent(long gestureId, CapturedContent content) {
        if (content == null || !service.isAccessibilityCaptureEnabled()) {
            DragReadyIsland.dismiss(service);
            recycleIfOwned(content);
            return;
        }
        // Capture already ran with the hold, so nothing delays the reveal except the rule that
        // a stationary hold must never open the share menu. Wait for a small finger move.
        float anchorX = gestureDetector.longPressX();
        float anchorY = gestureDetector.longPressY();
        if (!GestureMath.movedBeyond(
                anchorX,
                anchorY,
                gestureDetector.latestX(),
                gestureDetector.latestY(),
                dp(SHOW_MOVE_SLOP_DP))) {
            if (!gestureDetector.isCapturing(gestureId)) {
                // The capture finished after the finger was already up, so no ACTION_UP will
                // ever arrive to arm-then-dismiss the reminder; this content must be dropped
                // instead of deferred (that is what left the island on screen indefinitely).
                recycleIfOwned(content);
                DragShareLog.i(TAG, "gesture=" + gestureId
                        + " released before arming; content dropped");
                service.trace("gesture=" + gestureId + " released before arming; content dropped");
                return;
            }
            synchronized (deferredShowLock) {
                recycleIfOwned(deferredShowContent);
                deferredShowContent = content;
                deferredShowGestureId = gestureId;
                deferredShowAnchorX = anchorX;
                deferredShowAnchorY = anchorY;
            }
            // The press is armed but nothing will be shown until the finger moves; say so on
            // the system island instead of leaving the user without any feedback.
            DragReadyIsland.setExpiryListener(this::cancel);
            DragReadyIsland.show(service);
            controller.noteReminderArmed();
            if (!gestureDetector.isCapturing(gestureId)) {
                // The release raced the post; do not leave a reminder for a dead gesture.
                DragReadyIsland.dismiss(service);
            }
            DragShareLog.i(TAG, "gesture=" + gestureId
                    + " content captured; waiting for the finger to move");
            service.trace("gesture=" + gestureId + " show deferred until movement");
            return;
        }
        doShowCapturedContent(gestureId, content);
    }

    private void flushDeferredShowIfMoved(float x, float y) {
        CapturedContent pending;
        long gestureId;
        synchronized (deferredShowLock) {
            if (deferredShowContent == null
                    || !GestureMath.movedBeyond(
                            deferredShowAnchorX,
                            deferredShowAnchorY,
                            x,
                            y,
                            dp(SHOW_MOVE_SLOP_DP))) {
                return;
            }
            pending = deferredShowContent;
            gestureId = deferredShowGestureId;
            deferredShowContent = null;
            deferredShowGestureId = 0L;
        }
        CapturedContent content = pending;
        long id = gestureId;
        if (android.os.SystemClock.uptimeMillis() < DragReadyIsland.onScreenUptime()) {
            // The finger moved while the reminder was still on its way up, so the user never
            // saw an invitation: treat the whole drag as abandoned.
            recycleIfOwned(content);
            DragReadyIsland.dismiss(service);
            gestureDetector.captureFailed(id);
            DragShareLog.i(TAG, "gesture=" + id
                    + " moved before the reminder appeared; content dropped");
            return;
        }
        // The island stays up on purpose: the reveal is a relay, and the overlay dismisses the
        // reminder the moment it takes the gesture over.
        DragShareLog.i(TAG, "gesture=" + id + " finger moved; showing captured content");
        mainHandler.post(() -> doShowCapturedContent(id, content));
    }

    private void discardDeferredShow() {
        CapturedContent pending;
        synchronized (deferredShowLock) {
            pending = deferredShowContent;
            deferredShowContent = null;
            deferredShowGestureId = 0L;
        }
        if (pending != null) {
            recycleIfOwned(pending);
            DragShareLog.i(TAG, "gesture released without movement; captured content dropped");
        }
        // Any gesture end takes the reminder down, whether or not content was still pending:
        // the island must never outlive the finger.
        DragReadyIsland.dismiss(service);
    }

    private float dp(float value) {
        return service.getResources().getDisplayMetrics().density * value;
    }

    private void doShowCapturedContent(long gestureId, CapturedContent content) {
        // A fast slide can finish the capture after the finger is already up. Painting the
        // overlay then leaves a window nobody can drag or commit, which lingers until a
        // watchdog fires; drop that late content instead of showing it at all.
        if (!gestureDetector.isCapturing(gestureId)) {
            DragReadyIsland.dismiss(service);
            recycleIfOwned(content);
            DragShareLog.i(TAG, "gesture=" + gestureId + " released before reveal; content dropped");
            service.trace("gesture=" + gestureId + " released before reveal; content dropped");
            return;
        }
        DragShareSettings settings = DragShareSettings.readLocal(service);
        if (settings.isCombinedCaptureMode()) {
            if (handedOffGestureId == gestureId) {
                recycleIfOwned(content);
                DragShareLog.i(TAG, "gesture=" + gestureId
                        + " already handed off; duplicate content dropped");
                return;
            }
            handedOffGestureId = gestureId;
            // One overlay only: hand the captured content to the portal process, which owns
            // the single share window and its own drag tracking. This must not depend on the
            // local gesture state, because the capture can finish after the finger is lifted.
            sendToPortal(content, gestureDetector.latestX(), gestureDetector.latestY());
            recycleIfOwned(content);
            DragShareLog.i(TAG, "gesture=" + gestureId + " content handed to portal kind="
                    + content.kind);
            service.trace("gesture=" + gestureId + " content handed to portal kind=" + content.kind);
            return;
        }
        if (!gestureDetector.beginDragging(gestureId)) {
            DragReadyIsland.dismiss(service);
            recycleIfOwned(content);
            return;
        }
        controller.show(
                content,
                gestureDetector.latestX(),
                gestureDetector.latestY(),
                settings);
        // beginDragging() only succeeds while the physical root gesture is
        // still down. Confirm that active root session synchronously so the
        // lock cannot be queued behind its eventual ACTION_UP.
        controller.onRootDragSessionStarted();
        service.trace("gesture=" + gestureId + " controller show requested kind=" + content.kind);
        DragShareLog.i(TAG, "gesture=" + gestureId + " controller shown kind=" + content.kind);
    }

    private void sendToPortal(CapturedContent content, float x, float y) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    DragShareAccessibilityService.ACTION_ACCESSIBILITY_CONTENT);
            intent.setPackage("com.miui.contentextension");
            intent.putExtra("x", x);
            intent.putExtra("y", y);
            if (content.isImage()) {
                File file = new File(service.getExternalCacheDir(), "acc_content.png");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    content.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    output.getFD().sync();
                }
                intent.putExtra("image_path", file.getAbsolutePath());
            } else {
                intent.putExtra("text", content.text);
            }
            service.sendBroadcast(intent);
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to hand content to portal", error);
        }
    }

    private static void recycleIfOwned(CapturedContent content) {
        if (content != null && content.bitmapOwnedByDragShare && content.bitmap != null
                && !content.bitmap.isRecycled()) {
            content.bitmap.recycle();
        }
    }
}
