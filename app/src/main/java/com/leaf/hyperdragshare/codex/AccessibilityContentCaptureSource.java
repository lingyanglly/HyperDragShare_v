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
        float tolerantTouchSlop = Math.max(
                configuration.getScaledTouchSlop() * 2f,
                service.getResources().getDisplayMetrics().density * 16f)
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
        }
    }

    void cancel() {
        gestureDetector.cancel();
        screenshotter.cancelAll();
        controller.cancelActiveSession();
    }

    private static final long COMBINED_PORTAL_WINDOW_MS = 1_500L;

    private void captureAt(long gestureId, float stableX, float stableY, boolean retried) {
        try {
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
                        mainHandler.post(() -> gestureDetector.captureFailed(gestureId));
                    }
                    return;
                }
                DragShareSettings settings = DragShareSettings.readLocal(service);
                if (!settings.isAccessibilityCaptureMode()
                        || !settings.isSharingEnabled(selection.isImage())) {
                    service.trace("gesture=" + gestureId + " sharing disabled");
                    mainHandler.post(() -> gestureDetector.captureFailed(gestureId));
                    return;
                }
                AccessibilityCandidate candidate = selection.candidate;
                if (ViewDumpClient.supports(candidate.sourcePackage)) {
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
                        postShowIfPortalSilent(gestureId, content);
                        return;
                    }
                    if (!selection.isImage()) {
                        // Text containers (comment list, "关闭工作表", "up主头像") are not
                        // draggable content in this app; never crop or copy them.
                        service.trace("gesture=" + gestureId
                                + " no ImageView and no image region; skipping");
                        mainHandler.post(() -> gestureDetector.captureFailed(gestureId));
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
                    postShowIfPortalSilent(gestureId, content);
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
                        postShowIfPortalSilent(completedGestureId, content);
                    }

                    @Override
                    public void onFailure(long completedGestureId, Throwable error) {
                        mainHandler.post(() -> {
                            DragShareLog.w(TAG, "gesture=" + completedGestureId
                                    + " image capture failed", error);
                            service.trace("gesture=" + completedGestureId
                                    + " screenshot failed=" + error.getClass().getSimpleName());
                            gestureDetector.captureFailed(completedGestureId);
                        });
                    }
                });
            });
        } catch (RuntimeException error) {
            DragShareLog.w(TAG, "gesture=" + gestureId + " classifier unavailable", error);
            mainHandler.post(() -> gestureDetector.captureFailed(gestureId));
        }
    }

    /**
     * Combined mode: the content is captured immediately (so an app's own long-press sheet
     * cannot end up in the crop) and handed off at once. A duplicate portal pick is rejected
     * by the portal controller's own "already active" guard, so no extra wait is needed.
     */
    private void postShowIfPortalSilent(long gestureId, CapturedContent content) {
        if (DragShareSettings.readLocal(service).isCombinedCaptureMode()
                && service.isPortalPickRecent(COMBINED_PORTAL_WINDOW_MS)) {
            recycleIfOwned(content);
            DragShareLog.i(TAG, "gesture=" + gestureId
                    + " portal already active; accessibility content dropped");
            mainHandler.post(() -> gestureDetector.captureFailed(gestureId));
            return;
        }
        mainHandler.post(() -> showCapturedContent(gestureId, content));
    }

    private void showCapturedContent(long gestureId, CapturedContent content) {
        if (content == null || !service.isAccessibilityCaptureEnabled()) {
            recycleIfOwned(content);
            return;
        }
        DragShareSettings settings = DragShareSettings.readLocal(service);
        if (settings.isCombinedCaptureMode()) {
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
