package com.leaf.hyperdragshare.codex;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DragShareController {
    private static final String TAG = "DragShare/UI";
    private static final int PREVIEW_TEXT_WIDTH_DP = 184;
    private static final int PREVIEW_TEXT_HEIGHT_DP = 112;
    private static final int PREVIEW_IMAGE_SIZE_DP = 148;
    private static final int PORTAL_PREVIEW_TEXT_SIZE_DP = 112;
    private static final int PORTAL_PREVIEW_IMAGE_SIZE_DP = 116;
    private static final int MENU_HEIGHT_DP = 96;
    private static final int SIMPLE_SIDE_MENU_WIDTH_DP = 72;
    private static final int VERTICAL_MENU_ITEM_HEIGHT_DP = 72;
    private static final int VERTICAL_MENU_MIN_HEIGHT_DP = 380;
    private static final int VERTICAL_MENU_MAX_HEIGHT_PERCENT = 82;
    private static final int SIDE_MENU_SCROLL_LEAVE_SLOP_DP = 8;
    private static final int SIDE_MENU_INNER_SCROLL_BAND_DP = 56;
    private static final int CAPSULE_WIDTH_DP = 320;
    private static final int CAPSULE_HEIGHT_DP = 96;
    private static final int PORTAL_MENU_HEIGHT_DP = 152;
    private static final int MENU_TRIGGER_DP = 72;
    private static final int SIMPLE_MENU_ACTIVATION_SLOP_DP = 16;
    private static final int EDGE_SCROLL_FULL_SPEED_INSET_DP = 8;
    private static final int PORTAL_TRIGGER_FROM_BOTTOM_DP = 96;
    private static final int PORTAL_ITEM_ENTER_OFFSET_DP = 168;
    private static final long MODERN_OVERLAY_EXIT_DURATION_MS = 220L;
    private static final long LINEAR_MENU_EXIT_DURATION_MS = 160L;
    private static final long PREVIEW_EXIT_DURATION_MS = 160L;
    private static final int MODERN_BOTTOM_MENU_SIDE_MARGIN_DP = 12;
    private static final int CIRCLE_EDGE_TRIGGER_DP = 76;
    private static final int CIRCLE_EDGE_SOFT_DISTANCE_DP = 180;
    private static final long CIRCLE_EDGE_OPEN_DELAY_MS = 200L;
    private static final long DUPLICATE_EVENT_WINDOW_MS = 2;
    private static final float NEAR_HAND_TILT_THRESHOLD = 0.14f;

    private final Context context;
    private final WindowManager windowManager;
    private final BackgroundTouchBlocker backgroundTouchBlocker;
    private final OverlayWindowPolicy windowPolicy;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DragShareToast dragShareToast;
    private final Runnable edgeScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || !menuShown) {
                return;
            }
            if (isCircleStyle()) {
                if (circleMenuView == null || circleScrollDirection == 0) {
                    return;
                }
                circleMenuView.scrollWindow(circleScrollDirection);
                updateSelectedTarget(lastX, lastY);
                mainHandler.postDelayed(this, circleScrollIntervalMs());
                return;
            }
            if (edgeDirection == 0 || (menuScroll == null
                    && menuVerticalScroll == null
                    && modernMenuView == null)) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            long elapsed = lastEdgeScrollUptime == 0
                    ? 16L
                    : Math.min(48L, Math.max(1L, now - lastEdgeScrollUptime));
            lastEdgeScrollUptime = now;
            float distanceWithRemainder = dpFloat(settings.scrollSpeedDpPerSecond)
                    * edgeScrollMultiplierForCurrentGesture() * elapsed / 1000f
                    + edgeScrollRemainderPx;
            int distance = (int) distanceWithRemainder;
            edgeScrollRemainderPx = distanceWithRemainder - distance;
            if (distance > 0) {
                if (modernMenuView != null) {
                    modernMenuView.scrollByPixels(edgeDirection * distance);
                } else if (menuScroll != null) {
                    menuScroll.scrollBy(edgeDirection * distance, 0);
                } else {
                    menuVerticalScroll.scrollBy(0, edgeDirection * distance);
                }
                updateSelectedTarget(lastX, lastY);
            }
            mainHandler.postDelayed(this, 16L);
        }
    };
    private final Runnable circleExpandRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || menuShown || circleMenuView == null
                    || circlePendingEdge == CircleMenuGeometry.EDGE_NONE) {
                return;
            }
            int edge = CircleMenuGeometry.nearestEdge(
                    lastX,
                    lastY,
                    screenWidth,
                    screenHeight,
                    circleTriggerPx());
            if (edge == circlePendingEdge) {
                circleEdge = edge;
                showMenuOnMain();
            }
        }
    };

    private volatile boolean active;
    private volatile boolean destroyed;
    // startPick*Task can create Taplus' full-screen float view before this controller reaches
    // the main thread. Keep its window suppressed during that small handoff.
    private volatile boolean pendingPortalHostFloatWindowSuppression;
    private volatile float lastObservedX = -1;
    private volatile float lastObservedY = -1;
    private volatile long lastObservedEventTime;

    private float lastX;
    private float lastY;
    private float simpleMenuStartX;
    private float simpleMenuStartY;
    private boolean simpleMenuStartPointCaptured;
    private boolean simpleMenuActivationQualified;
    private long lastHandledEventTime = Long.MIN_VALUE;
    private int lastHandledAction = -1;
    private int edgeDirection;
    private boolean edgeScrollOutsideMenu;
    private float edgeScrollDepth;
    private volatile long lastPointerUptime;
    private boolean sessionGotPointerDown;
    /** Long-press must be followed by a move before the drag UI takes over. */
    private volatile boolean gestureMovedSinceDown;
    private float gestureDownX;
    private float gestureDownY;
    private final float gestureMoveSlopPx;
    private CapturedContent pendingMoveShowPayload;
    private DragShareSettings pendingMoveShowSettings;
    private long pendingMoveShowRequestId;
    private static final long SESSION_STALL_TIMEOUT_MS = 1500L;
    private final Runnable stuckSessionCheck = new Runnable() {
        @Override
        public void run() {
            if (!active || !sessionGotPointerDown
                    && SystemClock.uptimeMillis() - lastPointerUptime
                    >= SESSION_STALL_TIMEOUT_MS) {
                if (active) {
                    log("session stalled without a pointer DOWN; cancelling");
                    cancelGestureOnMain();
                }
            }
        }
    };
    /** Defense against a race leaving an overlay up after the finger is gone. */
    private static final long SESSION_HARD_LIMIT_MS = 10_000L;
    private final Runnable sessionHardLimit = new Runnable() {
        @Override
        public void run() {
            if (active) {
                log("session exceeded hard limit; force dismissing");
                cancelGestureOnMain();
            }
        }
    };
    /** The capsule must not start scrolling options just because the finger happened to be
     *  outside its edge when it appeared; require a deliberate outward move and a short
     *  settle window so the first option stays reachable. */
    private static final long MENU_SCROLL_SETTLE_MS = 800L;
    private static final int MENU_SCROLL_ARM_SLOP_DP = 24;
    private long menuShownUptime;
    private float menuShownX;
    private boolean inputSourceLogged;
    private boolean duplicateStartLogged;
    private boolean backgroundBlockAttempted;
    private boolean portalGlowStartedLogged;
    private boolean portalGlowExpandedLogged;
    private long lastEdgeScrollUptime;
    private float edgeScrollRemainderPx;
    private int circleEdge = CircleMenuGeometry.EDGE_NONE;
    private int circlePendingEdge = CircleMenuGeometry.EDGE_NONE;
    private int circleScrollDirection;

    private int screenWidth;
    private int screenHeight;
    private int topInset;
    private int bottomInset;
    private int menuTop;
    private int menuHeight;
    private int menuTriggerTop;
    private int menuLeft;
    private int menuWidth;
    private int simpleMenuPosition = DragShareSettings.SIMPLE_MENU_POSITION_BOTTOM;
    private int configuredSimpleMenuPosition = DragShareSettings.SIMPLE_MENU_POSITION_BOTTOM;
    private int nearHandSide = DragShareSettings.SIMPLE_MENU_POSITION_RIGHT;
    private boolean nearHandSideLocked;

    private View previewView;
    private ModernPreviewOverlayView modernPreviewView;
    private ModernOverlayWindow modernPreviewWindow;
    private WindowManager.LayoutParams previewParams;
    private int previewWidth;
    private int previewHeight;
    private boolean modernPreviewEnterStarted;
    // Keep an exiting preview addressable until its animation completes so a new drag can
    // tear it down immediately instead of briefly stacking two overlay windows.
    private int previewExitGeneration;
    private View exitingPreviewView;
    private ModernPreviewOverlayView exitingModernPreviewView;
    private ModernOverlayWindow exitingModernPreviewWindow;

    private PortalGlowView glowView;
    private WindowManager.LayoutParams glowParams;

    private View menuView;
    private ModernMenuOverlayView modernMenuView;
    private ModernOverlayWindow modernMenuWindow;
    private Runnable modernMenuDisposeRunnable;
    private int linearMenuFadeGeneration;
    private View fadingLinearMenuView;
    private WindowManager.LayoutParams menuParams;
    private HorizontalScrollView menuScroll;
    private ScrollView menuVerticalScroll;
    private LinearLayout menuRow;
    private final List<View> menuItems = new ArrayList<>();
    private CircleMenuOverlayView circleMenuView;
    private WindowManager.LayoutParams circleMenuParams;
    private List<ShareTarget> shareTargets = new ArrayList<>();
    private static final ExecutorService TARGET_QUERY_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "drag-share-targets");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile List<ShareTarget> cachedTextTargets;
    private static volatile List<ShareTarget> cachedImageTargets;
    private ShareTarget selectedTarget;
    private boolean menuShown;

    private SensorManager sensorManager;
    private Sensor nearHandSensor;
    private boolean nearHandSensorRegistered;
    private final SensorEventListener nearHandSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (nearHandSideLocked || event == null
                    || event.values == null || event.values.length == 0) {
                return;
            }
            float tilt = event.sensor != null
                    && event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR
                    ? rotationVectorRoll(event.values)
                    : event.values[0] / SensorManager.GRAVITY_EARTH;
            if (!Float.isFinite(tilt) || Math.abs(tilt) < NEAR_HAND_TILT_THRESHOLD) {
                return;
            }
            int side = GestureMath.nearHandMenuOnRight(tilt)
                    ? DragShareSettings.SIMPLE_MENU_POSITION_RIGHT
                    : DragShareSettings.SIMPLE_MENU_POSITION_LEFT;
            if (side != nearHandSide) {
                nearHandSide = side;
                mainHandler.post(() -> updateNearHandMenuPosition());
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // No calibration state is needed for the coarse left/right decision.
        }
    };

    private Session session;
    private DragShareSettings settings = DragShareSettings.defaults();
    private OverlayColors palette = OverlayColors.light();
    private SegmentationOverlay segmentationOverlay;
    private Runnable segmentationClosedListener;

    DragShareController(Context context) {
        this(context, OverlayWindowPolicy.systemHosted());
    }

    DragShareController(Context context, OverlayWindowPolicy windowPolicy) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.backgroundTouchBlocker = new BackgroundTouchBlocker(context);
        this.windowPolicy = windowPolicy == null ? OverlayWindowPolicy.systemHosted() : windowPolicy;
        this.dragShareToast = new DragShareToast(context, this.windowPolicy);
        this.gestureMoveSlopPx = context.getResources().getDisplayMetrics().density * 24f;
    }

    boolean isActive() {
        return active;
    }

    void reservePortalHostFloatWindowSuppression() {
        pendingPortalHostFloatWindowSuppression = true;
    }

    private static final long FIRST_SHOW_WAIT_MS = 3_000L;

    private volatile boolean rootInputProven;
    private volatile boolean miuiMotionSeen;
    private volatile boolean rootHelperClientPresent;
    private volatile long showRequestId;
    private volatile int lastObservedAction = -1;
    private volatile boolean deferredShowPending;
    private long deferredShowRequestId;
    private CapturedContent deferredShowPayload;
    private DragShareSettings deferredShowSettings;
    private float deferredShowInitialX;
    private float deferredShowInitialY;
    private final Runnable deferredShowRunnable = this::runDeferredShow;
    private final Runnable deferredShowAbortRunnable = this::abortDeferredShow;
    private volatile Runnable hostSplashHider;
    /**
     * HyperOS' refresh policy keeps its high-frame-rate interaction window alive while
     * window animations run and drops the panel to the app's rate a few seconds into an
     * otherwise static hold. A subtle periodic pulse on the preview window keeps the drag
     * at the panel's high rate.
     */
    private static final long BOOST_PULSE_INTERVAL_MS = 300L;
    private final Runnable boostPulseRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || destroyed) {
                return;
            }
            pulseBoost();
            mainHandler.postDelayed(this, BOOST_PULSE_INTERVAL_MS);
        }
    };

    void noteRootPointer() {
        rootInputProven = true;
    }

    void noteMiuiMotion() {
        miuiMotionSeen = true;
    }

    void noteRootHelperClientPresent(boolean present) {
        rootHelperClientPresent = present;
    }

    /** Hides the portal's own splash preview once the deferred takeover has really started. */
    void setHostSplashHider(Runnable hider) {
        hostSplashHider = hider;
    }

    boolean shouldSuppressPortalHostFloatWindow() {
        // Only hide the portal's own float window once one input source has actually
        // delivered a frame. Suppressing it earlier stops Taplus' internal drag feed and
        // leaves the first gesture with no pointer at all.
        boolean inputProven = rootInputProven || miuiMotionSeen;
        return inputProven && (active || pendingPortalHostFloatWindowSuppression);
    }

    void show(
            CapturedContent content,
            float initialX,
            float initialY,
            DragShareSettings loadedSettings) {
        if (content == null) {
            return;
        }
        final long requestId = ++showRequestId;
        runOnMain(() -> {
            // Portal long presses often collide with an app's own long-press menu. Only
            // take over once the finger actually moves; a stationary hold is left alone.
            if (windowPolicy.deferUntilMove && !gestureMovedSinceDown) {
                pendingMoveShowPayload = content;
                pendingMoveShowSettings = loadedSettings;
                pendingMoveShowRequestId = requestId;
                log("overlay deferred until the finger moves");
                return;
            }
            showOnMain(content, initialX, initialY, loadedSettings, requestId);
        });
    }

    /**
     * Entry point for content captured by the accessibility runtime: that runtime only hands
     * off mid-gesture after the finger moved, so the portal-side "wait for input / wait for
     * move" gates must not swallow it.
     */
    void showFromHandoff(
            CapturedContent content,
            float initialX,
            float initialY,
            DragShareSettings loadedSettings) {
        if (content == null) {
            return;
        }
        final long requestId = ++showRequestId;
        runOnMain(() -> {
            rootInputProven = true;
            gestureMovedSinceDown = true;
            showOnMain(content, initialX, initialY, loadedSettings, requestId);
        });
    }

    void cancelActiveSession() {
        runOnMain(this::cancelGestureOnMain);
    }

    float latestPointerX() {
        return lastObservedX;
    }

    float latestPointerY() {
        return lastObservedY;
    }

    void acceptMotionEvent(MotionEvent event) {
        acceptMotionEvent(event, null);
    }

    void acceptMotionEvent(MotionEvent event, Runnable beforeFinish) {
        if (event == null) {
            return;
        }
        final int action = event.getActionMasked();
        final float x = event.getRawX();
        final float y = event.getRawY();
        final long eventTime = event.getEventTime();
        event.recycle();
        acceptPointerEvent(action, x, y, eventTime, "miui", beforeFinish);
    }

    void acceptPointerEvent(
            int action,
            float x,
            float y,
            long eventTime,
            String source) {
        acceptPointerEvent(action, x, y, eventTime, source, null);
    }

    void acceptPointerEvent(
            int action,
            float x,
            float y,
            long eventTime,
            String source,
            Runnable beforeFinish) {
        if (DragShareLog.isDebugEnabled()) {
            DragShareLog.d(TAG, "pointer received source=" + source
                    + " action=" + MotionEvent.actionToString(action)
                    + " point=" + Math.round(x) + "," + Math.round(y)
                    + " active=" + active
                    + " time=" + eventTime);
        }
        lastObservedX = x;
        lastObservedY = y;
        lastObservedEventTime = eventTime;
        lastObservedAction = action;
        lastPointerUptime = SystemClock.uptimeMillis();
        if (deferredShowPending) {
            // The first real frame is the only reliable "input is available now" signal.
            // A fixed delay can expire while the finger is still held stationary.
            mainHandler.removeCallbacks(deferredShowRunnable);
            mainHandler.post(deferredShowRunnable);
        }
        if (action == MotionEvent.ACTION_DOWN) {
            sessionGotPointerDown = true;
        }
        mainHandler.post(() -> {
            trackGestureOnMain(action, x, y);
            if (pendingMoveShowPayload != null) {
                if (action == MotionEvent.ACTION_MOVE && gestureMovedSinceDown) {
                    CapturedContent payload = pendingMoveShowPayload;
                    DragShareSettings ps = pendingMoveShowSettings;
                    long pr = pendingMoveShowRequestId;
                    clearPendingMoveShow();
                    log("overlay shown after the finger moved");
                    // Appear directly under the current finger so the triggering move does
                    // not also yank the freshly created window away from the long-press point.
                    showOnMain(payload, x, y, ps, pr);
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    clearPendingMoveShow();
                    log("overlay dismissed before the finger moved");
                }
            }
            if (active && !inputSourceLogged) {
                inputSourceLogged = true;
                log("input source=" + source
                        + " action=" + MotionEvent.actionToString(action)
                        + " point=" + Math.round(x) + "," + Math.round(y));
            }
            handleMotionOnMain(action, x, y, eventTime, source, beforeFinish);
        });
    }

    private void trackGestureOnMain(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            gestureDownX = x;
            gestureDownY = y;
            gestureMovedSinceDown = false;
        } else if (action == MotionEvent.ACTION_MOVE && !gestureMovedSinceDown) {
            float dx = x - gestureDownX;
            float dy = y - gestureDownY;
            if (dx * dx + dy * dy > gestureMoveSlopPx * gestureMoveSlopPx) {
                gestureMovedSinceDown = true;
            }
        }
    }

    private void clearPendingMoveShow() {
        pendingMoveShowPayload = null;
        pendingMoveShowSettings = null;
        pendingMoveShowRequestId = 0L;
        pendingPortalHostFloatWindowSuppression = false;
    }

    /** Called by a root-backed source after it has created an active drag session. */
    void onRootDragSessionStarted() {
        runOnMain(() -> {
            if (active) {
                startBackgroundBlockerIfEnabled();
            }
        });
    }

    void finishFromControlEvent() {
        mainHandler.post(() -> {
            if (!active) {
                return;
            }
            handlePointerOnMain(lastObservedX, lastObservedY);
            // The fallback path does not have a physical root ACTION_UP callback.
            finishGestureOnMain(true);
        });
    }

    void onHostTaskCancelled() {
        // Give the queued 257/control callback a chance to finish the drag first.
        mainHandler.postDelayed(() -> {
            if (active) {
                cancelGestureOnMain();
            } else {
                backgroundTouchBlocker.stop();
                if (!isPreviewExitPending()) {
                    removeGestureViews();
                }
            }
        }, 32L);
    }

    void destroy() {
        destroyed = true;
        pendingPortalHostFloatWindowSuppression = false;
        clearDeferredShow();
        closeSegmentation();
        runOnMain(() -> {
            active = false;
            if (session != null) {
                session.cancelled = true;
            }
            stopEdgeScroll();
            backgroundTouchBlocker.stop();
            removeGestureViews();
            dragShareToast.close();
        });
    }

    private void showOnMain(
            CapturedContent payload,
            float requestedInitialX,
            float requestedInitialY,
            DragShareSettings loadedSettings,
            long requestId) {
        if (destroyed) {
            pendingPortalHostFloatWindowSuppression = false;
            return;
        }
        if (requestId != showRequestId) {
            log("overlay start superseded by a newer request");
            return;
        }
        if (deferredShowPending && deferredShowRequestId != requestId) {
            clearDeferredShow();
        }
        if (!rootInputProven && !miuiMotionSeen) {
            // The portal process was just (re)started and neither evdev nor MIUI motion
            // has produced a single frame yet (su grant / pipe warm-up). Taking over here
            // would only paint a preview that cannot follow the finger. With the persistent
            // root helper attached, hold the takeover until the first real frame arrives
            // (or the cap expires): a fixed delay can expire while the finger is still
            // held stationary before the drag starts.
            if (rootHelperClientPresent && !deferredShowPending) {
                deferredShowPending = true;
                deferredShowRequestId = requestId;
                deferredShowPayload = payload;
                deferredShowSettings = loadedSettings;
                deferredShowInitialX = requestedInitialX;
                deferredShowInitialY = requestedInitialY;
                log("input not ready; taking over on the first input frame (waiting up to "
                        + FIRST_SHOW_WAIT_MS + "ms)");
                mainHandler.removeCallbacks(deferredShowAbortRunnable);
                mainHandler.postDelayed(deferredShowAbortRunnable, FIRST_SHOW_WAIT_MS);
                return;
            }
            pendingPortalHostFloatWindowSuppression = false;
            log("overlay skipped: no input frame yet in this portal process");
            return;
        }
        if (active) {
            pendingPortalHostFloatWindowSuppression = false;
            if (!duplicateStartLogged) {
                duplicateStartLogged = true;
                log("duplicate Taplus start ignored during active drag");
            }
            return;
        }
        active = false;
        inputSourceLogged = false;
        duplicateStartLogged = false;
        backgroundBlockAttempted = false;
        portalGlowStartedLogged = false;
        portalGlowExpandedLogged = false;
        simpleMenuStartPointCaptured = false;
        simpleMenuActivationQualified = false;
        lastHandledEventTime = Long.MIN_VALUE;
        lastHandledAction = -1;
        settings = loadedSettings == null ? DragShareSettings.defaults() : loadedSettings;
        configuredSimpleMenuPosition = settings.simpleMenuPosition;
        nearHandSide = DragShareSettings.SIMPLE_MENU_POSITION_RIGHT;
        nearHandSideLocked = false;
        simpleMenuPosition = effectiveSimpleMenuPosition();
        palette = OverlayColors.from(settings);
        stopEdgeScroll();
        backgroundTouchBlocker.stop();
        removeGestureViews();

        if (!settings.isSharingEnabled(payload.isImage())) {
            pendingPortalHostFloatWindowSuppression = false;
            log("sharing disabled kind=" + payload.kind);
            return;
        }

        try {
            session = new Session(payload);
            refreshDisplayGeometry();
            createPreview(payload);
            createGlow();
            if (glowView != null && glowParams != null) {
                windowManager.addView(glowView, glowParams);
                glowView.start();
            }
            if (modernPreviewWindow != null) {
                modernPreviewWindow.show();
            } else {
                windowManager.addView(previewView, previewParams);
            }
            active = true;
            pendingPortalHostFloatWindowSuppression = false;
            startBoostPulse();
            registerNearHandSensorIfNeeded();
            traceAccessibility("overlay added kind=" + payload.kind);
        } catch (Throwable error) {
            pendingPortalHostFloatWindowSuppression = false;
            log("unable to add preview overlay", error);
            traceAccessibility("overlay add failed=" + error.getClass().getSimpleName()
                    + ":" + String.valueOf(error.getMessage()));
            removeGestureViews();
            return;
        }

        boolean hasRequestedInitialPoint = Float.isFinite(requestedInitialX)
                && requestedInitialX >= 0f
                && Float.isFinite(requestedInitialY)
                && requestedInitialY >= 0f;
        float initialX = Float.isFinite(requestedInitialX) && requestedInitialX >= 0f
                ? requestedInitialX
                : screenWidth / 2f;
        float initialY = Float.isFinite(requestedInitialY) && requestedInitialY >= 0f
                ? requestedInitialY
                : Math.max(topInset + dp(80), screenHeight * 0.32f);
        lastX = initialX;
        lastY = initialY;
        simpleMenuStartX = initialX;
        simpleMenuStartY = initialY;
        simpleMenuStartPointCaptured = hasRequestedInitialPoint;
        updatePreviewPosition(initialX, initialY);
        updatePortalPullEffect(initialX, initialY);
        startPreviewEnterAnimation();

        // Querying package icons can be comparatively slow: 100+ targets mean 100+
        // PackageManager lookups. Never block the gesture on it. Cached results are used
        // immediately; the first gesture fills the cache in the background and assembles
        // the menu as soon as it is ready.
        List<ShareTarget> rawTargets = cachedRawTargets(payload.isImage());
        if (rawTargets != null) {
            shareTargets = prepareTargets(safeApplyTargets(rawTargets, payload.isImage()), payload);
            createMenu();
            if (isCapsuleMenu()) {
                showMenuOnMain();
            }
        } else {
            final Session querySession = session;
            final boolean imagePayload = payload.isImage();
            final CapturedContent queryPayload = payload;
            TARGET_QUERY_EXECUTOR.execute(() -> {
                List<ShareTarget> queried = safeQueryTargets(queryPayload);
                mainHandler.post(() -> {
                    if (destroyed || !active || querySession != session) {
                        return;
                    }
                    shareTargets = prepareTargets(queried, queryPayload);
                    createMenu();
                    if (shouldShowSimpleMenu(lastX, lastY)) {
                        showMenuOnMain();
                    }
                });
            });
        }
        if (isCircleStyle() && circleMenuView != null && circleMenuParams != null) {
            try {
                circleMenuView.setVisibility(View.VISIBLE);
                windowManager.addView(circleMenuView, circleMenuParams);
                updateCirclePointer(initialX, initialY);
            } catch (Throwable error) {
                log("unable to add circle menu overlay", error);
                circleMenuView.collapse();
                circleMenuView = null;
                circleMenuParams = null;
            }
        }
        log("preview shown kind=" + payload.kind
                + " targets=" + shareTargets.size()
                + " style=" + settings.uiStyle
                + " blockBackground=" + settings.blockBackgroundScroll
                + " at=" + Math.round(initialX) + "," + Math.round(initialY));
        sessionGotPointerDown = false;
        lastPointerUptime = SystemClock.uptimeMillis();
        mainHandler.removeCallbacks(stuckSessionCheck);
        mainHandler.postDelayed(stuckSessionCheck, SESSION_STALL_TIMEOUT_MS + 200L);
        mainHandler.removeCallbacks(sessionHardLimit);
        mainHandler.postDelayed(sessionHardLimit, SESSION_HARD_LIMIT_MS);

        if (payload.isImage()) {
            final Session stagedSession = session;
            ImageStagingClient.stage(context, payload.bitmap, new ImageStagingClient.Callback() {
                @Override
                public void onStaged(Uri uri) {
                    mainHandler.post(() -> {
                        if (destroyed || stagedSession.cancelled) {
                            return;
                        }
                        stagedSession.stagedUri = uri;
                        if (stagedSession.pendingTarget != null) {
                            launchPendingShare(stagedSession);
                        }
                    });
                }

                @Override
                public void onFailure(Throwable error) {
                    mainHandler.post(() -> {
                        if (!destroyed && !stagedSession.cancelled) {
                            log("image staging failed", error);
                            if (stagedSession.pendingTarget != null) {
                                showToast("图片准备失败");
                            }
                        }
                    });
                }
            });
        }
    }

    private void runDeferredShow() {
        if (!deferredShowPending) {
            return;
        }
        deferredShowPending = false;
        mainHandler.removeCallbacks(deferredShowAbortRunnable);
        CapturedContent payload = deferredShowPayload;
        DragShareSettings loadedSettings = deferredShowSettings;
        float requestedInitialX = deferredShowInitialX;
        float requestedInitialY = deferredShowInitialY;
        long requestId = deferredShowRequestId;
        deferredShowPayload = null;
        deferredShowSettings = null;
        if (destroyed) {
            pendingPortalHostFloatWindowSuppression = false;
            return;
        }
        if (requestId != showRequestId) {
            log("overlay grace skipped: superseded by a newer start");
            return;
        }
        if (!rootInputProven && !miuiMotionSeen) {
            pendingPortalHostFloatWindowSuppression = false;
            log("overlay skipped: no input frame yet in this portal process");
            return;
        }
        boolean gestureEnded = lastObservedAction == MotionEvent.ACTION_UP
                || lastObservedAction == MotionEvent.ACTION_CANCEL;
        if (gestureEnded) {
            pendingPortalHostFloatWindowSuppression = false;
            log("overlay grace skipped: gesture ended before the input frame arrived");
            return;
        }
        // The finger may have moved since startPick; start the overlay under the latest
        // observed point instead of the stale long-press origin.
        float initialX = lastObservedX >= 0f ? lastObservedX : requestedInitialX;
        float initialY = lastObservedY >= 0f ? lastObservedY : requestedInitialY;
        log("first input frame arrived; taking over the drag");
        showOnMain(payload, initialX, initialY, loadedSettings, requestId);
        // The DOWN that started this gesture happened before the session existed, so the
        // stall guard must not treat the session as "never received a pointer down".
        sessionGotPointerDown = true;
        if (active) {
            Runnable hider = hostSplashHider;
            if (hider != null) {
                log("invoking host splash hider");
                hider.run();
            } else {
                log("host splash hider missing");
            }
        }
    }

    private void abortDeferredShow() {
        if (!deferredShowPending) {
            return;
        }
        clearDeferredShow();
        pendingPortalHostFloatWindowSuppression = false;
        log("overlay skipped: no input frame within " + FIRST_SHOW_WAIT_MS + "ms");
    }

    private void clearDeferredShow() {
        deferredShowPending = false;
        deferredShowPayload = null;
        deferredShowSettings = null;
        mainHandler.removeCallbacks(deferredShowAbortRunnable);
    }

    private List<ShareTarget> safeQueryTargets(CapturedContent payload) {
        try {
            List<ShareTarget> queried = ShareTargetRepository.query(context, payload);
            boolean image = payload != null && payload.isImage();
            storeRawTargets(image, queried);
            return ShareTargetRepository.applySettings(context, queried, settings, image);
        } catch (Throwable error) {
            log("share target query failed", error);
            return new ArrayList<>();
        }
    }

    private List<ShareTarget> prepareTargets(List<ShareTarget> base, CapturedContent payload) {
        List<ShareTarget> result = new ArrayList<>(buildActionTargets(payload));
        List<ShareTarget> ordered = TargetOrdering.order(base, payload == null ? null : payload.text);
        if (ordered != null) {
            result.addAll(ordered);
        }
        if (settings.isTargetVisible(DragShareSettings.TARGET_SYSTEM_SHARE)) {
            Drawable moreIcon = null;
            try {
                moreIcon = context.getDrawable(R.drawable.ic_share);
            } catch (Throwable ignored) {
                // The tile still works without an icon.
            }
            result.add(ShareTarget.systemShare(moreIcon));
        }
        return ShareTargetRepository.dedupeByPackage(result);
    }

    /**
     * OriginOS-like suggestions that do not depend on the app declaring ACTION_SEND:
     * a map app can still receive geo:, the dialer tel:, mail clients mailto:.
     */
    private List<ShareTarget> buildActionTargets(CapturedContent payload) {
        List<ShareTarget> actions = new ArrayList<>();
        if (payload == null || payload.isImage() || payload.text == null) {
            return actions;
        }
        String text = payload.text.trim();
        if (text.isEmpty()) {
            return actions;
        }
        PackageManager packageManager = context.getPackageManager();
        int kind = ContentKind.classify(text);
        if (kind == ContentKind.ADDRESS) {
            addGeoAction(actions, packageManager, text, "com.autonavi.minimap");
            addGeoAction(actions, packageManager, text, "com.baidu.BaiduMap");
            addGeoAction(actions, packageManager, text, "com.tencent.map");
        } else if (kind == ContentKind.PHONE) {
            addUriAction(actions, packageManager, text, Intent.ACTION_DIAL, "tel:",
                    new String[]{"com.android.dialer", "com.google.android.dialer"},
                    "action:dial");
        } else if (kind == ContentKind.EMAIL) {
            addUriAction(actions, packageManager, text, Intent.ACTION_SENDTO, "mailto:",
                    new String[]{"com.tencent.androidqqmail", "com.netease.mail",
                            "com.android.email", "com.google.android.gm"},
                    "action:mail");
        }
        return actions;
    }

    private void addGeoAction(
            List<ShareTarget> out,
            PackageManager packageManager,
            String address,
            String packageName) {
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=" + Uri.encode(address)));
            intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(ShareTarget.action(
                    info.loadLabel(packageManager),
                    info.loadIcon(packageManager),
                    "action:geo:" + packageName,
                    packageName,
                    intent));
        } catch (Throwable ignored) {
            // App not installed or not resolvable.
        }
    }

    private void addUriAction(
            List<ShareTarget> out,
            PackageManager packageManager,
            String value,
            String action,
            String scheme,
            String[] packages,
            String keyPrefix) {
        String digits = value.replaceAll("[^0-9+]", "");
        for (String packageName : packages) {
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
                Uri uri = Uri.parse("tel:".equals(scheme) ? scheme + digits : scheme);
                Intent intent = new Intent(action, uri);
                if (!"tel:".equals(scheme)) {
                    intent.putExtra(Intent.EXTRA_TEXT, value);
                    intent.putExtra(Intent.EXTRA_SUBJECT, "");
                }
                intent.setPackage(packageName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                out.add(ShareTarget.action(
                        info.loadLabel(packageManager),
                        info.loadIcon(packageManager),
                        keyPrefix + ":" + packageName,
                        packageName,
                        intent));
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }
    }

    private Drawable colorizedIcon(int resourceId) {
        try {
            return context.getDrawable(resourceId);
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * Runs the package query before the first drag so the first gesture after a fresh
     * portal start is not paying for 100+ PackageManager icon loads.
     */
    void prewarmTargets() {
        TARGET_QUERY_EXECUTOR.execute(() -> {
            try {
                if (cachedRawTargets(false) == null) {
                    storeRawTargets(false, ShareTargetRepository.query(context, "text/plain"));
                }
                if (cachedRawTargets(true) == null) {
                    storeRawTargets(true, ShareTargetRepository.query(context, "image/*"));
                }
                log("target prewarm finished");
            } catch (Throwable error) {
                log("target prewarm failed", error);
            }
        });
    }

    private List<ShareTarget> safeApplyTargets(List<ShareTarget> raw, boolean image) {
        try {
            return ShareTargetRepository.applySettings(context, raw, settings, image);
        } catch (Throwable error) {
            log("share target filter failed", error);
            return new ArrayList<>();
        }
    }

    private static List<ShareTarget> cachedRawTargets(boolean image) {
        return image ? cachedImageTargets : cachedTextTargets;
    }

    private static void storeRawTargets(boolean image, List<ShareTarget> targets) {
        if (targets == null) {
            return;
        }
        if (image) {
            cachedImageTargets = targets;
        } else {
            cachedTextTargets = targets;
        }
    }

    private void createPreview(CapturedContent payload) {
        if (isModernStyle()) {
            int side = ModernPreviewSizer.squareSidePx(
                    payload,
                    screenWidth,
                    context.getResources().getDisplayMetrics().density);
            previewWidth = side;
            previewHeight = side;
            modernPreviewView = new ModernPreviewOverlayView(context, payload, settings);
            previewView = modernPreviewView;
            previewParams = overlayParams(previewWidth, previewHeight, "DragShare modern preview");
            try {
                modernPreviewWindow = new ModernOverlayWindow(
                        context,
                        windowManager,
                        modernPreviewView,
                        previewParams,
                        dp(settings.modernBlurRadiusDp));
            } catch (Throwable error) {
                modernPreviewWindow = null;
                log("unable to create native modern preview window", error);
            }
            return;
        }
        boolean portalStyle = isPortalStyle();
        boolean circleStyle = isCircleStyle();
        boolean compactStyle = portalStyle || circleStyle;
        FrameLayout standardPreviewView = new FrameLayout(context);
        previewView = standardPreviewView;
        previewView.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            previewView.setForceDarkAllowed(false);
        }
        previewView.setElevation(dp(compactStyle ? 6 : 8));
        previewView.setBackground(roundDrawable(
                palette.previewBackground,
                compactStyle ? 12 : 8));

        if (compactStyle) {
            int sizeDp = !payload.isImage()
                    ? PORTAL_PREVIEW_TEXT_SIZE_DP
                    : PORTAL_PREVIEW_IMAGE_SIZE_DP;
            previewWidth = dp(sizeDp);
            previewHeight = dp(sizeDp);
            if (!payload.isImage()) {
                TextView text = new TextView(context);
                CharSequence previewText = payload.text;
                if (previewText != null && previewText.length() > 40) {
                    previewText = previewText.subSequence(0, 40);
                }
                text.setText(previewText);
                text.setTextColor(palette.primaryText);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                text.setGravity(Gravity.CENTER);
                text.setPadding(dp(7), dp(7), dp(7), dp(7));
                text.setMaxLines(4);
                text.setEllipsize(android.text.TextUtils.TruncateAt.END);
                standardPreviewView.addView(text, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                ImageView image = new ImageView(context);
                image.setImageBitmap(payload.bitmap);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setPadding(dp(3), dp(3), dp(3), dp(3));
                standardPreviewView.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else if (!payload.isImage()) {
            previewWidth = dp(PREVIEW_TEXT_WIDTH_DP);
            previewHeight = dp(PREVIEW_TEXT_HEIGHT_DP);
            TextView text = new TextView(context);
            text.setText(payload.text);
            text.setTextColor(palette.primaryText);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setPadding(dp(12), dp(10), dp(12), dp(10));
            text.setMaxLines(4);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            standardPreviewView.addView(text, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            previewWidth = dp(PREVIEW_IMAGE_SIZE_DP);
            previewHeight = dp(PREVIEW_IMAGE_SIZE_DP);
            ImageView image = new ImageView(context);
            image.setImageBitmap(payload.bitmap);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(dp(6), dp(6), dp(6), dp(6));
            standardPreviewView.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        previewParams = overlayParams(previewWidth, previewHeight, "DragShare preview");
    }

    private void createGlow() {
        if (!isPortalStyle()) {
            glowView = null;
            glowParams = null;
            return;
        }
        glowView = new PortalGlowView(
                context,
                settings.colorMode == DragShareSettings.COLOR_DARK,
                bottomInset);
        glowParams = overlayParams(screenWidth, screenHeight, "DragShare portal glow");
    }

    private void startPreviewEnterAnimation() {
        if (isModernStyle()) {
            enableModernLocalBlurAndReveal();
            return;
        }
        if (previewView == null || !isPortalStyle()) {
            return;
        }
        previewView.animate().cancel();
        previewView.setAlpha(0f);
        previewView.setScaleX(0.88f);
        previewView.setScaleY(0.88f);
        previewView.animate()
                .alpha(0.94f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200L)
                .start();
    }

    private void enableModernLocalBlurAndReveal() {
        ModernPreviewOverlayView preview = modernPreviewView;
        if (preview == null) {
            return;
        }
        modernPreviewEnterStarted = false;
        int blurRadiusPx = dp(settings.modernBlurRadiusDp);
        boolean enabled = modernPreviewWindow != null
                && modernPreviewWindow.isNativeBackdropBlurEnabled();
        log("modern Android background blur " + (enabled ? "enabled" : "unavailable")
                + " radiusPx=" + blurRadiusPx);
        revealModernPreview(preview);
    }

    private void revealModernPreview(ModernPreviewOverlayView preview) {
        if (preview == null || modernPreviewEnterStarted) {
            return;
        }
        modernPreviewEnterStarted = true;
        if (modernPreviewWindow != null) {
            modernPreviewWindow.showAnimated();
        } else {
            preview.showAnimated();
        }
    }
    private void createMenu() {
        if (shareTargets.isEmpty()) {
            // The async target query may still be running. Never build an empty menu:
            // an empty window would flash its placeholder text over the host app.
            menuView = null;
            menuParams = null;
            menuScroll = null;
            menuVerticalScroll = null;
            menuRow = null;
            modernMenuView = null;
            return;
        }
        if (isModernStyle()) {
            Map<ShareTarget, Drawable> icons = new LinkedHashMap<>();
            for (ShareTarget target : shareTargets) {
                icons.put(target, iconForTarget(target));
            }
            modernMenuView = new ModernMenuOverlayView(
                    context,
                    new ArrayList<>(shareTargets),
                    icons,
                    settings,
                    isVerticalSimpleMenu());
            menuView = modernMenuView;
            menuParams = overlayParams(menuWidth, menuHeight, "DragShare modern targets");
            menuParams.x = menuLeft;
            menuParams.y = menuTop;
            try {
                modernMenuWindow = new ModernOverlayWindow(
                        context,
                        windowManager,
                        modernMenuView,
                        menuParams,
                        dp(settings.modernBlurRadiusDp));
            } catch (Throwable error) {
                modernMenuWindow = null;
                log("unable to create native modern menu window", error);
            }
            return;
        }
        if (isCircleStyle()) {
            circleMenuView = new CircleMenuOverlayView(
                    context,
                    screenWidth,
                    screenHeight,
                    topInset,
                    bottomInset,
                    settings.colorMode == DragShareSettings.COLOR_DARK,
                    palette.accent,
                    palette.primaryText,
                    palette.selectedItemBackground,
                    palette.selectedItemBorder,
                    settings.iconOpacityPercent);
            circleMenuView.setTargets(shareTargets);
            circleMenuParams = overlayParams(
                    screenWidth,
                    screenHeight,
                    "DragShare circle menu");
            return;
        }
        boolean portalStyle = isPortalStyle();
        boolean vertical = !portalStyle && isVerticalSimpleMenu();
        FrameLayout linearMenuView = new FrameLayout(context);
        menuView = linearMenuView;
        linearMenuView.setClipChildren(false);
        linearMenuView.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            linearMenuView.setForceDarkAllowed(false);
        }
        int horizontalPadding = dp(portalStyle ? 10 : 4);
        int verticalPadding = dp(portalStyle ? 4 : 4);
        int bottomPadding = dp(portalStyle ? 2 : 4);
        if (portalStyle) {
            linearMenuView.setBackground(roundDrawable(Color.TRANSPARENT, 0));
        } else {
            addSimpleMenuBackground();
        }
        linearMenuView.setElevation(dp(portalStyle ? 0 : 10));

        FrameLayout menuContent = new FrameLayout(context);
        menuContent.setClipChildren(false);
        menuContent.setClipToPadding(false);
        menuContent.setPadding(
                horizontalPadding,
                verticalPadding,
                horizontalPadding,
                bottomPadding);
        linearMenuView.addView(menuContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        menuRow = new LinearLayout(context);
        menuRow.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        menuRow.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        menuRow.setClipChildren(false);
        menuRow.setClipToPadding(false);
        if (vertical) {
            menuVerticalScroll = new ScrollView(context);
            menuVerticalScroll.setVerticalScrollBarEnabled(false);
            menuVerticalScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            menuVerticalScroll.setClipToPadding(false);
            menuVerticalScroll.setClipChildren(false);
            menuVerticalScroll.addView(menuRow, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            menuContent.addView(menuVerticalScroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            menuScroll = new HorizontalScrollView(context);
            menuScroll.setHorizontalScrollBarEnabled(false);
            menuScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            menuScroll.setClipToPadding(false);
            menuScroll.setClipChildren(false);
            menuScroll.setFillViewport(false);
            menuScroll.addView(menuRow, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            menuContent.addView(menuScroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        menuItems.clear();
        if (shareTargets.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("没有可用的分享应用");
            empty.setTextColor(palette.secondaryText);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setGravity(Gravity.CENTER);
            if (vertical) {
                menuRow.addView(empty, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (portalStyle) {
                menuRow.addView(empty, new LinearLayout.LayoutParams(
                        screenWidth,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                menuContent.addView(empty, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else {
            int itemWidth = dp(portalStyle ? 78 : 76);
            int itemHeight = dp(portalStyle
                    ? 124
                    : (vertical ? VERTICAL_MENU_ITEM_HEIGHT_DP : 84));
            int iconSize = dp(portalStyle ? 50 : 44);
            for (ShareTarget target : shareTargets) {
                LinearLayout item = new LinearLayout(context);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setPadding(dp(portalStyle ? 5 : 4), dp(portalStyle ? 8 : 3),
                        dp(portalStyle ? 5 : 4), dp(portalStyle ? 2 : 3));
                item.setTag(target);
                item.setBackground(itemBackground(target, false));

                ImageView icon = new ImageView(context);
                icon.setImageDrawable(iconForTarget(target));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setAlpha(settings.iconOpacityPercent / 100f);
                item.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

                TextView label = new TextView(context);
                label.setText(target.label);
                label.setTextColor(palette.primaryText);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setAlpha(settings.iconOpacityPercent / 100f);
                if (portalStyle) {
                    label.setShadowLayer(dpFloat(2), 0f, dpFloat(1),
                            settings.colorMode == DragShareSettings.COLOR_DARK
                                    ? 0xCC000000
                                    : 0x66000000);
                }
                if (!vertical && !portalStyle) {
                    item.addView(label, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
                } else if (portalStyle) {
                    item.addView(label, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
                }

                if (vertical) {
                    // Dock layout: icon only, no caption, so the strip stays narrow.
                    int width = Math.max(1, menuWidth - dp(8));
                    menuRow.addView(item, new LinearLayout.LayoutParams(width, itemHeight));
                } else {
                    menuRow.addView(item, new LinearLayout.LayoutParams(itemWidth, itemHeight));
                }
                if (isCapsuleMenu()) {
                    // OriginOS-style tile: caption above the icon.
                    item.removeAllViews();
                    item.addView(label);
                    item.addView(icon);
                }
                menuItems.add(item);
            }
        }

        menuParams = overlayParams(menuWidth, menuHeight, "DragShare targets");
        menuParams.x = menuLeft;
        menuParams.y = menuTop;
    }

    private void addSimpleMenuBackground() {
        if (!(menuView instanceof FrameLayout)) {
            return;
        }
        FrameLayout linearMenuView = (FrameLayout) menuView;
        int cornerRadiusDp = isCapsuleMenu()
                ? Math.max(settings.simpleMenuCornerRadiusDp, 24)
                : settings.simpleMenuCornerRadiusDp;
        View background = new View(context);
        background.setBackground(roundDrawable(
                applyAbsoluteOpacity(palette.menuBackground, 100),
                cornerRadiusDp));
        background.setAlpha(simpleMenuBackgroundOpacityFraction(
                settings.simpleMenuOpacityPercent));
        linearMenuView.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        int cornerRadiusPx = dp(cornerRadiusDp);
        linearMenuView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
            }
        });
        linearMenuView.setClipToOutline(true);
    }

    private void handleMotionOnMain(
            int action,
            float x,
            float y,
            long eventTime,
            String source,
            Runnable beforeFinish) {
        if (!active) {
            return;
        }
        // Only pilfer once the authoritative root stream has produced an
        // event. The MIUI fallback cannot safely consume its synthetic cancel.
        if ("root".equals(source)
                && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
            startBackgroundBlockerIfEnabled();
        }
        if (eventTime == lastHandledEventTime && action == lastHandledAction
                && eventTime != 0 && eventTime > lastObservedEventTime - DUPLICATE_EVENT_WINDOW_MS) {
            return;
        }
        lastHandledEventTime = eventTime;
        lastHandledAction = action;
        lastX = x;
        lastY = y;

        if (action == MotionEvent.ACTION_CANCEL) {
            log("gesture cancelled source=" + source);
            cancelGestureOnMain();
            return;
        }
        handlePointerOnMain(x, y);
        if (action == MotionEvent.ACTION_UP) {
            log("gesture finished source=" + source
                    + " point=" + Math.round(x) + "," + Math.round(y)
                    + " menu=" + menuShown);
            finishGestureOnMain(true, beforeFinish);
        }
    }

    private void handlePointerOnMain(float x, float y) {
        if (!active || !Float.isFinite(x) || !Float.isFinite(y)) {
            return;
        }
        lastX = x;
        lastY = y;
        updatePreviewPosition(x, y);
        updatePortalPullEffect(x, y);
        if (isCircleStyle()) {
            updateCirclePointer(x, y);
            if (menuShown) {
                updateSelectedTarget(x, y);
            }
            return;
        }
        boolean menuOpenedNow = false;
        updateBothSidesMenuSide(x);
        if (!menuShown && shouldShowSimpleMenu(x, y)
                && hasQualifiedSimpleMenuActivation(x, y)) {
            showMenuOnMain();
            menuOpenedNow = menuShown;
        }
        if (menuShown) {
            if (settings.closeMenuWhenPointerLeaves
                    && !menuOpenedNow
                    && !isPointerWithinSimpleMenuZone(x, y)) {
                hideLinearMenu();
                return;
            }
            updateSelectedTarget(x, y);
            edgeScrollOutsideMenu = false;
            edgeScrollDepth = 0f;
            int nextDirection = 0;
            if (isCapsuleMenu()) {
                // Horizontal capsule: scroll from the inner bands of the capsule itself,
                // not from the screen edges (the capsule sits centred, far from them).
                // Only once the user deliberately moved away from the point where the menu
                // appeared; otherwise the first option would scroll away instantly.
                if (capsuleScrollArmed(x)) {
                    int slop = dp(SIDE_MENU_SCROLL_LEAVE_SLOP_DP);
                    int innerBand = Math.max(dp(SIDE_MENU_INNER_SCROLL_BAND_DP), 1);
                    float rightInner = menuLeft + menuWidth - innerBand;
                    float leftInner = menuLeft + innerBand;
                    if (x > menuLeft + menuWidth + slop) {
                        nextDirection = 1;
                        edgeScrollOutsideMenu = true;
                        edgeScrollDepth = 1f;
                    } else if (x > rightInner) {
                        nextDirection = 1;
                        edgeScrollDepth = Math.min(1f, (x - rightInner) / innerBand);
                    } else if (x < menuLeft - slop) {
                        nextDirection = -1;
                        edgeScrollOutsideMenu = true;
                        edgeScrollDepth = 1f;
                    } else if (x < leftInner) {
                        nextDirection = -1;
                        edgeScrollDepth = Math.min(1f, (leftInner - x) / innerBand);
                    }
                }
            } else if (isVerticalSimpleMenu()) {
                // Dock scrolling starts as soon as the pointer reaches the last visible
                // tile, not after leaving the card entirely.
                int slop = dp(SIDE_MENU_SCROLL_LEAVE_SLOP_DP);
                int innerBand = Math.max(dp(SIDE_MENU_INNER_SCROLL_BAND_DP), 1);
                float bottomInner = menuTop + menuHeight - innerBand;
                float topInner = menuTop + innerBand;
                if (y > menuTop + menuHeight + slop) {
                    nextDirection = 1;
                    edgeScrollOutsideMenu = true;
                    edgeScrollDepth = 1f;
                } else if (y > bottomInner) {
                    nextDirection = 1;
                    edgeScrollDepth = Math.min(1f, (y - bottomInner) / innerBand);
                } else if (y < menuTop - slop) {
                    nextDirection = -1;
                    edgeScrollOutsideMenu = true;
                    edgeScrollDepth = 1f;
                } else if (y < topInner) {
                    nextDirection = -1;
                    edgeScrollDepth = Math.min(1f, (topInner - y) / innerBand);
                }
            } else if (isPortalStyle() || isPointerInsideSimpleMenu(x, y)) {
                nextDirection = GestureMath.edgeScrollDirection(
                        x,
                        edgeScrollAxisSize(),
                        edgeScrollWidthPx());
            }
            if (nextDirection != edgeDirection) {
                edgeDirection = nextDirection;
                edgeScrollRemainderPx = 0f;
                log("edge scroll direction=" + nextDirection);
                if (nextDirection == 0) {
                    stopEdgeScroll();
                } else {
                    lastEdgeScrollUptime = 0L;
                    mainHandler.removeCallbacks(edgeScrollRunnable);
                    mainHandler.post(edgeScrollRunnable);
                }
            }
        }
    }

    private void updateCirclePointer(float x, float y) {
        if (circleMenuView == null) {
            return;
        }
        int trigger = circleTriggerPx();
        int nextEdge = CircleMenuGeometry.nearestEdge(
                x, y, screenWidth, screenHeight, trigger);
        float progress = CircleMenuGeometry.edgeProgress(
                x, y, screenWidth, screenHeight,
                Math.max(trigger, dp(CIRCLE_EDGE_SOFT_DISTANCE_DP)));
        if (menuShown) {
            circleMenuView.updatePointer(x, y);
            if (settings.closeMenuWhenPointerLeaves
                    && !circleMenuView.containsExpandedRegion(x, y)) {
                hideCircleMenu();
                circleMenuView.setEdgeProgress(nextEdge, progress, x, y);
                updateCirclePendingEdge(nextEdge);
                return;
            }
        } else {
            circleMenuView.setEdgeProgress(nextEdge, progress, x, y);
            updateCirclePendingEdge(nextEdge);
            return;
        }

        int nextScrollDirection = circleMenuView.scrollDirectionForPointer(x, y);
        if (nextScrollDirection != circleScrollDirection) {
            circleScrollDirection = nextScrollDirection;
            mainHandler.removeCallbacks(edgeScrollRunnable);
            if (nextScrollDirection != 0) {
                mainHandler.post(edgeScrollRunnable);
            }
        }
        // The reference side menu keeps the edge and vertical anchor captured
        // at expansion time. Pointer movement only changes hover/drop state.
    }

    private void updateCirclePendingEdge(int nextEdge) {
        if (nextEdge == circlePendingEdge) {
            return;
        }
        mainHandler.removeCallbacks(circleExpandRunnable);
        circlePendingEdge = nextEdge;
        if (nextEdge != CircleMenuGeometry.EDGE_NONE) {
            mainHandler.postDelayed(circleExpandRunnable, CIRCLE_EDGE_OPEN_DELAY_MS);
        }
    }

    private int circleTriggerPx() {
        int maxTrigger = Math.max(1, Math.min(screenWidth, screenHeight) / 2);
        return Math.min(
                maxTrigger,
                Math.max(dp(CIRCLE_EDGE_TRIGGER_DP), dp(settings.edgeTriggerDp)));
    }

    private int effectiveSimpleMenuPosition() {
        if (configuredSimpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_BOTH_SIDES) {
            // The side is chosen per pointer position in updateBothSidesMenuSide(); start left
            // so the first layout pass still builds a vertical list.
            return DragShareSettings.SIMPLE_MENU_POSITION_LEFT;
        }
        if (configuredSimpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_CAPSULE) {
            // The capsule hangs under the status bar; reuse the top-edge trigger semantics.
            return DragShareSettings.SIMPLE_MENU_POSITION_TOP;
        }
        if (configuredSimpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_NEAR_HAND) {
            return nearHandSide == DragShareSettings.SIMPLE_MENU_POSITION_LEFT
                    ? DragShareSettings.SIMPLE_MENU_POSITION_LEFT
                    : DragShareSettings.SIMPLE_MENU_POSITION_RIGHT;
        }
        return configuredSimpleMenuPosition >= DragShareSettings.SIMPLE_MENU_POSITION_TOP
                && configuredSimpleMenuPosition <= DragShareSettings.SIMPLE_MENU_POSITION_RIGHT
                ? configuredSimpleMenuPosition
                : DragShareSettings.DEFAULT_SIMPLE_MENU_POSITION;
    }

    private boolean isVerticalSimpleMenu() {
        return !isPortalStyle()
                && (simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_LEFT
                || simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_RIGHT);
    }

    private boolean isCapsuleMenu() {
        return configuredSimpleMenuPosition
                == DragShareSettings.SIMPLE_MENU_POSITION_CAPSULE;
    }

    private int edgeScrollAxisSize() {
        return isVerticalSimpleMenu() ? screenHeight : screenWidth;
    }

    private int edgeScrollWidthPx() {
        int edgeAxisSize = edgeScrollAxisSize();
        int maxEdgeWidth = Math.max(1, (edgeAxisSize - 1) / 2);
        return Math.min(dp(settings.edgeTriggerDp), maxEdgeWidth);
    }

    private float edgeScrollSpeedMultiplier() {
        boolean vertical = isVerticalSimpleMenu();
        return GestureMath.edgeScrollSpeedMultiplier(
                vertical ? lastY : lastX,
                edgeScrollAxisSize(),
                edgeScrollWidthPx(),
                dp(EDGE_SCROLL_FULL_SPEED_INSET_DP));
    }

    private float edgeScrollMultiplierForCurrentGesture() {
        float multiplier = edgeScrollSpeedMultiplier();
        if (edgeScrollOutsideMenu) {
            return Math.max(1.2f, multiplier) * 0.8f;
        }
        if (edgeScrollDepth > 0f) {
            // Ramp in quickly from the inner band so a small overshoot already moves
            // the list at a usable speed.
            return Math.max(0.9f, multiplier) * (0.9f + 0.9f * edgeScrollDepth) * 0.8f;
        }
        return multiplier * 0.8f;
    }

    private void fadeInCapsuleIfNeeded() {
        if (!isCapsuleMenu() || menuView == null) {
            return;
        }
        menuView.animate().cancel();
        menuView.setAlpha(0f);
        menuView.animate().alpha(1f).setDuration(150L).start();
    }

    private boolean shouldShowSimpleMenu(float x, float y) {
        if (isCapsuleMenu()) {
            // The capsule is the drag surface itself: it is visible for the whole
            // gesture instead of waiting for the finger to reach the top edge.
            return true;
        }
        if (isPortalStyle()) {
            return GestureMath.shouldShowMenu(y, menuTriggerTop);
        }
        int trigger = Math.max(dp(settings.edgeTriggerDp), dp(MENU_TRIGGER_DP));
        switch (simpleMenuPosition) {
            case DragShareSettings.SIMPLE_MENU_POSITION_TOP:
                return y <= menuTop + trigger;
            case DragShareSettings.SIMPLE_MENU_POSITION_LEFT:
                return x <= trigger;
            case DragShareSettings.SIMPLE_MENU_POSITION_RIGHT:
                return x >= screenWidth - trigger;
            case DragShareSettings.SIMPLE_MENU_POSITION_BOTTOM:
            default:
                return y >= menuTriggerTop;
        }
    }

    private boolean hasQualifiedSimpleMenuActivation(float x, float y) {
        if (isPortalStyle() || isCircleStyle() || simpleMenuActivationQualified) {
            return true;
        }
        if (isBothSidesSimpleMenu()) {
            // The side band itself is the gate: entering it is already an explicit move
            // toward the edge, and switching sides must not require a fresh slop again.
            return true;
        }
        if (!simpleMenuStartPointCaptured) {
            simpleMenuStartX = x;
            simpleMenuStartY = y;
            simpleMenuStartPointCaptured = true;
            return false;
        }
        simpleMenuActivationQualified = GestureMath.hasMovedTowardMenu(
                simpleMenuPosition,
                simpleMenuStartX,
                simpleMenuStartY,
                x,
                y,
                dp(SIMPLE_MENU_ACTIVATION_SLOP_DP));
        return simpleMenuActivationQualified;
    }

    private boolean isPointerInsideSimpleMenu(float x, float y) {
        return menuShown
                && x >= menuLeft
                && x < menuLeft + menuWidth
                && y >= menuTop
                && y < menuTop + menuHeight;
    }

    private boolean isPointerWithinSimpleMenuZone(float x, float y) {
        return isPointerInsideSimpleMenu(x, y) || shouldShowSimpleMenu(x, y);
    }

    private void hideLinearMenu() {
        if (!menuShown || isCircleStyle()) {
            return;
        }
        stopEdgeScroll();
        menuShown = false;
        if (isModernStyle() && modernMenuView != null) {
            ModernMenuOverlayView menuToDispose = modernMenuView;
            scheduleModernMenuDispose(menuToDispose, modernMenuWindow);
        } else if (menuView != null) {
            fadeOutLinearMenu(menuView);
        }
        selectedTarget = null;
        if (modernMenuView != null) {
            modernMenuView.setSelectedTarget(null);
        }
        if (glowView != null && isPortalStyle()) {
            glowView.collapseMenu();
            updatePortalPullEffect(lastX, lastY);
        }
        updatePreviewPosition(lastX, lastY);
    }

    private void hideCircleMenu() {
        if (!menuShown || !isCircleStyle() || circleMenuView == null) {
            return;
        }
        stopEdgeScroll();
        mainHandler.removeCallbacks(circleExpandRunnable);
        circleMenuView.collapse();
        menuShown = false;
        selectedTarget = null;
        circleEdge = CircleMenuGeometry.EDGE_NONE;
        circlePendingEdge = CircleMenuGeometry.EDGE_NONE;
        updatePreviewPosition(lastX, lastY);
    }

    private void updateNearHandMenuPosition() {
        if (!active || isPortalStyle() || isCircleStyle()
                || nearHandSideLocked
                || configuredSimpleMenuPosition != DragShareSettings.SIMPLE_MENU_POSITION_NEAR_HAND) {
            return;
        }
        int nextPosition = effectiveSimpleMenuPosition();
        if (nextPosition == simpleMenuPosition) {
            return;
        }
        switchSimpleMenuSide(nextPosition);
    }

    private boolean isBothSidesSimpleMenu() {
        return !isPortalStyle() && !isCircleStyle()
                && configuredSimpleMenuPosition
                == DragShareSettings.SIMPLE_MENU_POSITION_BOTH_SIDES;
    }

    private int simpleMenuTriggerPx() {
        return Math.max(dp(settings.edgeTriggerDp), dp(MENU_TRIGGER_DP));
    }

    /**
     * Keeps the "two sides" position in sync with the pointer: the vertical menu follows
     * whichever edge band the pointer enters, and a centred pointer hides it again.
     */
    private void updateBothSidesMenuSide(float x) {
        if (!isBothSidesSimpleMenu()) {
            return;
        }
        int side = GestureMath.nearestSideEdge(x, screenWidth, simpleMenuTriggerPx());
        if (side == -1) {
            if (menuShown) {
                hideLinearMenu();
            }
            return;
        }
        if (side != simpleMenuPosition) {
            switchSimpleMenuSide(side);
        }
    }

    private void switchSimpleMenuSide(int nextPosition) {
        if (!active || nextPosition == simpleMenuPosition) {
            return;
        }
        boolean wasShown = menuShown;
        View previousMenuView = menuView;
        if (wasShown) {
            hideLinearMenu();
            if (!isModernStyle()) {
                cancelLinearMenuFadeOut();
                removeLinearMenuImmediately(previousMenuView);
            }
        }
        if (!wasShown && modernMenuWindow != null) {
            modernMenuWindow.dispose();
        }
        menuView = null;
        modernMenuView = null;
        modernMenuWindow = null;
        menuParams = null;
        menuScroll = null;
        menuVerticalScroll = null;
        menuRow = null;
        simpleMenuPosition = nextPosition;
        refreshDisplayGeometry();
        createMenu();
        if (wasShown) {
            showMenuOnMain();
        }
        updatePreviewPosition(lastX, lastY);
    }

    private void registerNearHandSensorIfNeeded() {
        if (!active || isPortalStyle() || isCircleStyle()
                || configuredSimpleMenuPosition != DragShareSettings.SIMPLE_MENU_POSITION_NEAR_HAND
                || nearHandSensorRegistered) {
            return;
        }
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                return;
            }
            nearHandSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (nearHandSensor == null) {
                nearHandSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (nearHandSensor != null
                    && sensorManager.registerListener(
                            nearHandSensorListener,
                    nearHandSensor,
                    SensorManager.SENSOR_DELAY_GAME)) {
                nearHandSensorRegistered = true;
                log("near-hand sensor registered type=" + nearHandSensor.getType());
            }
        } catch (Throwable error) {
            log("near-hand sensor unavailable", error);
            nearHandSensorRegistered = false;
        }
    }

    private void unregisterNearHandSensor() {
        if (sensorManager != null && nearHandSensorRegistered) {
            try {
                sensorManager.unregisterListener(nearHandSensorListener);
            } catch (Throwable ignored) {
                // Sensor service may already be shutting down.
            }
        }
        nearHandSensorRegistered = false;
        nearHandSensor = null;
        sensorManager = null;
    }

    private static float rotationVectorRoll(float[] values) {
        try {
            float[] rotationMatrix = new float[9];
            float[] orientation = new float[3];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            return orientation[2];
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private long circleScrollIntervalMs() {
        float speed = Math.max(1f, settings.scrollSpeedDpPerSecond);
        return Math.max(120L, Math.min(420L, Math.round(300f * 560f / speed)));
    }

    private void showMenuOnMain() {
        if (menuShown) {
            return;
        }
        // Anchor the option scrolling to the position the menu appeared at: the finger may
        // already sit outside the capsule edge, which must not scroll the list by itself.
        menuShownUptime = SystemClock.uptimeMillis();
        menuShownX = lastX;
        if (isCircleStyle()) {
            if (circleMenuView == null || circlePendingEdge == CircleMenuGeometry.EDGE_NONE) {
                return;
            }
            menuShown = true;
            fadeInCapsuleIfNeeded();
            circleEdge = circlePendingEdge;
            circleScrollDirection = 0;
            circleMenuView.expand(circleEdge, lastX, lastY);
            updatePreviewPosition(lastX, lastY);
            startCirclePreviewAnimation();
            log("circle menu shown edge=" + circleEdge
                    + " pointer=" + Math.round(lastX) + "," + Math.round(lastY));
            mainHandler.post(() -> updateSelectedTarget(lastX, lastY));
            return;
        }
        if (isModernStyle()) {
            cancelModernMenuDispose();
            // A menu that completed its exit animation is removed to guarantee that the
            // blurred overlay cannot remain on screen. Recreate it when the same drag re-enters
            // the trigger zone, preserving the public "leave and return" behavior.
            if (menuView == null || modernMenuView == null) {
                createMenu();
            }
        }
        if (menuView == null) {
            return;
        }
        lockNearHandMenuSide();
        if (!isModernStyle() && menuView.isAttachedToWindow()) {
            cancelLinearMenuFadeOut(menuView);
            menuView.animate().cancel();
            menuShown = true;
            fadeInCapsuleIfNeeded();
            menuView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(LINEAR_MENU_EXIT_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
            updatePreviewPosition(lastX, lastY);
            log("linear menu restored pointerY=" + Math.round(lastY)
                    + " menuTop=" + menuTop);
            menuView.post(() -> updateSelectedTarget(lastX, lastY));
            return;
        }
        if (isModernStyle() && modernMenuView != null
                && ((modernMenuWindow != null && modernMenuWindow.isShowing())
                || menuView.isAttachedToWindow())) {
            menuShown = true;
            fadeInCapsuleIfNeeded();
            if (modernMenuWindow != null) {
                modernMenuWindow.showAnimated();
            } else {
                modernMenuView.showAnimated();
            }
            updatePreviewPosition(lastX, lastY);
            log("modern menu shown pointerY=" + Math.round(lastY) + " menuTop=" + menuTop);
            menuView.post(() -> updateSelectedTarget(lastX, lastY));
            return;
        }
        try {
            if (isModernStyle() && modernMenuWindow != null) {
                modernMenuWindow.showAnimated();
            } else {
                windowManager.addView(menuView, menuParams);
            }
            menuShown = true;
            fadeInCapsuleIfNeeded();
            updatePreviewPosition(lastX, lastY);
            log("menu shown pointerY=" + Math.round(lastY) + " menuTop=" + menuTop);
            if (isPortalStyle()) {
                if (glowView != null) {
                    glowView.setPullProgress(1f, lastX / Math.max(1f, screenWidth));
                    glowView.expandMenu();
                }
                if (!portalGlowExpandedLogged) {
                    portalGlowExpandedLogged = true;
                    log("portal glow expanded with share tray");
                }
                startPortalMenuEnterAnimation();
            } else if (isModernStyle() && modernMenuView != null) {
                if (modernMenuWindow == null) {
                    modernMenuView.showAnimated();
                }
            } else {
                menuView.setAlpha(0f);
                menuView.setTranslationX(isVerticalSimpleMenu()
                        ? (simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_LEFT
                        ? -dp(12) : dp(12))
                        : 0f);
                menuView.setTranslationY(!isVerticalSimpleMenu()
                        ? (simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_TOP
                        ? -dp(12) : dp(12))
                        : 0f);
                menuView.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(120L)
                        .start();
            }
            menuView.post(() -> updateSelectedTarget(lastX, lastY));
        } catch (Throwable error) {
            log("unable to add share menu", error);
        }
    }

    private void lockNearHandMenuSide() {
        if (configuredSimpleMenuPosition != DragShareSettings.SIMPLE_MENU_POSITION_NEAR_HAND
                || nearHandSideLocked) {
            return;
        }
        nearHandSideLocked = true;
        unregisterNearHandSensor();
        log("near-hand menu side locked=" + simpleMenuPosition);
    }

    private void startPortalMenuEnterAnimation() {
        menuView.setAlpha(1f);
        menuView.setTranslationY(0f);
        OvershootInterpolator springLike = new OvershootInterpolator(0.78f);
        for (int index = 0; index < menuItems.size(); index++) {
            View item = menuItems.get(index);
            item.animate().cancel();
            item.setAlpha(0f);
            item.setTranslationY(dp(PORTAL_ITEM_ENTER_OFFSET_DP));
            item.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(Math.min(180L, index * 22L))
                    .setDuration(430L)
                    .setInterpolator(springLike)
                    .start();
        }
    }

    private void startCirclePreviewAnimation() {
        if (previewView == null) {
            return;
        }
        previewView.animate().cancel();
        previewView.animate()
                .alpha(0.92f)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(500L)
                .start();
    }

    private void updateSelectedTarget(float x, float y) {
        if (isCircleStyle()) {
            ShareTarget hit = circleMenuView == null ? null : circleMenuView.hitTest(x, y);
            selectedTarget = hit;
            if (circleMenuView != null) {
                circleMenuView.setSelectedTarget(hit);
            }
            return;
        }
        if (isModernStyle()) {
            ShareTarget hit = menuShown && isPointerInsideSimpleMenu(x, y)
                    && modernMenuView != null
                    ? modernMenuView.hitTest(x, y)
                    : null;
            selectedTarget = hit;
            if (modernMenuView != null) {
                modernMenuView.setSelectedTarget(hit);
            }
            return;
        }
        ShareTarget hit = null;
        if (menuShown && isPointerInsideSimpleMenu(x, y)) {
            for (int i = 0; i < menuItems.size(); i++) {
                View item = menuItems.get(i);
                int[] location = new int[2];
                item.getLocationOnScreen(location);
                boolean inside;
                if (isPortalStyle() && item.getWidth() > 0 && item.getHeight() > 0) {
                    float centerX = location[0] + item.getWidth() / 2f;
                    float centerY = location[1] + item.getHeight() / 2f;
                    float scale = GestureMath.portalItemScale(
                            x, centerX, item.getWidth());
                    inside = Math.abs(x - centerX) <= item.getWidth() * scale / 2f
                            && Math.abs(y - centerY) <= item.getHeight() * scale / 2f;
                } else {
                    inside = x >= location[0] && x < location[0] + item.getWidth()
                            && y >= location[1] && y < location[1] + item.getHeight();
                }
                if (inside) {
                    Object tag = item.getTag();
                    if (tag instanceof ShareTarget) {
                        hit = (ShareTarget) tag;
                    }
                    break;
                }
            }
        }
        boolean selectionChanged = hit != selectedTarget;
        selectedTarget = hit;
        if (isPortalStyle()) {
            boolean pointerInMenu = menuShown && isPointerInsideSimpleMenu(x, y);
            for (View item : menuItems) {
                int[] location = new int[2];
                item.getLocationOnScreen(location);
                float scale = pointerInMenu && item.getWidth() > 0
                        ? GestureMath.portalItemScale(
                                x,
                                location[0] + item.getWidth() / 2f,
                                item.getWidth())
                        : 1f;
                item.setScaleX(scale);
                item.setScaleY(scale);
                if (selectionChanged) {
                    item.setBackground(itemBackground(
                            (ShareTarget) item.getTag(),
                            item.getTag() == selectedTarget));
                }
            }
            return;
        }
        if (!selectionChanged) {
            return;
        }
        for (View item : menuItems) {
            boolean selected = item.getTag() == selectedTarget;
            item.setBackground(itemBackground((ShareTarget) item.getTag(), selected));
            item.animate().scaleX(selected ? 1.04f : 1f)
                    .scaleY(selected ? 1.04f : 1f).setDuration(80L).start();
        }
    }

    private void finishGestureOnMain(boolean allowShare) {
        finishGestureOnMain(allowShare, null);
    }

    private void finishGestureOnMain(boolean allowShare, Runnable afterDeactivate) {
        mainHandler.removeCallbacks(stuckSessionCheck);
        mainHandler.removeCallbacks(sessionHardLimit);
        if (!active) {
            return;
        }
        Session finished = session;
        ShareTarget target = selectedTarget;
        if (allowShare && menuShown) {
            updateSelectedTarget(lastX, lastY);
            target = selectedTarget;
        }

        if (settings.blockBackgroundScroll && !backgroundBlockAttempted) {
            log("background scroll lock skipped because root input was not active");
        }
        active = false;
        stopBoostPulse();
        stopEdgeScroll();
        backgroundTouchBlocker.stop();
        if (afterDeactivate != null) {
            afterDeactivate.run();
        }

        // HyperOS can silently reject an AccessibilityService activity start after its last
        // accessibility overlay has been removed. Keep the passive overlay attached through
        // the user-selected launch, then clean it up in the same main-thread turn.
        if (allowShare && target != null && finished != null) {
            if (!finished.payload.isImage()
                    || target.isSaveToLocal()
                    || finished.stagedUri != null) {
                launchShare(finished, target);
            } else {
                finished.pendingTarget = target;
                showToast("正在准备图片");
            }
        } else if (finished != null && finished.pendingTarget == null) {
            finished.cancelled = true;
        }
        removeGestureViews(true);
    }

    private void cancelGestureOnMain() {
        mainHandler.removeCallbacks(stuckSessionCheck);
        mainHandler.removeCallbacks(sessionHardLimit);
        if (!active) {
            return;
        }
        active = false;
        stopBoostPulse();
        stopEdgeScroll();
        backgroundTouchBlocker.stop();
        removeGestureViews(true);
        if (session != null && session.pendingTarget == null) {
            session.cancelled = true;
        }
    }

    private void launchActionTarget(Session shareSession, ShareTarget target) {
        shareSession.cancelled = true;
        try {
            Intent intent = target.actionIntent();
            if (intent == null) {
                return;
            }
            context.startActivity(intent);
            log("action target=" + target.key());
        } catch (Throwable error) {
            log("action target failed key=" + target.key(), error);
            showToast("无法打开 " + target.label);
        }
    }

    private void openSystemShare(Session shareSession) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(shareSession.payload.mimeType());
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (shareSession.payload.isImage()) {
                Uri uri = shareSession.stagedUri;
                if (uri == null) {
                    showToast("图片还没有准备好");
                    return;
                }
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setData(uri);
                send.setClipData(ClipData.newUri(
                        context.getContentResolver(), "drag-share-image", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                send.putExtra(Intent.EXTRA_TEXT, shareSession.payload.text);
            }
            Intent chooser = Intent.createChooser(send, "分享到");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            log("system share sheet opened");
        } catch (Throwable error) {
            log("unable to open system share sheet", error);
            showToast("无法打开系统分享");
        }
    }

    private void launchPendingShare(Session pendingSession) {
        ShareTarget target = pendingSession.pendingTarget;
        if (target == null
                || (pendingSession.stagedUri == null && !target.isSaveToLocal())) {
            return;
        }
        pendingSession.pendingTarget = null;
        launchShare(pendingSession, target);
    }

    private void launchShare(Session shareSession, ShareTarget target) {
        if (target.isActionTarget()) {
            launchActionTarget(shareSession, target);
            return;
        }
        if (target.isSystemShare()) {
            openSystemShare(shareSession);
            shareSession.cancelled = true;
            return;
        }
        if (target.isSaveToLocal()) {
            saveImageLocally(shareSession.payload.bitmap);
            shareSession.cancelled = true;
            return;
        }
        if (target.isCopyToClipboard()) {
            copyToClipboard(shareSession.payload, shareSession.stagedUri);
            shareSession.cancelled = true;
            return;
        }
        if (target.isTextSegmentation()) {
            openTextSegmentation(shareSession.payload.text);
            shareSession.cancelled = true;
            return;
        }
        try {
            log("drop target=" + target.component.flattenToShortString());
            ShareLauncher.launch(
                    context,
                    shareSession.payload,
                    target,
                    shareSession.stagedUri,
                    dragShareToast);
        } catch (Throwable error) {
            log("share launch failed", error);
        }
        shareSession.cancelled = true;
    }

    private void copyToClipboard(CapturedContent payload, Uri stagedImage) {
        boolean image = payload != null && payload.isImage();
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                throw new IllegalStateException("Clipboard service is unavailable");
            }
            ClipData clip;
            if (image) {
                if (stagedImage == null) {
                    throw new IllegalArgumentException("Image URI is not ready");
                }
                clip = ClipData.newUri(
                        context.getContentResolver(),
                        "drag-share-image",
                        stagedImage);
            } else {
                String text = payload == null ? null : payload.text;
                if (text == null || text.trim().isEmpty()) {
                    throw new IllegalArgumentException("Text is empty");
                }
                clip = ClipData.newPlainText("drag-share-text", text);
            }
            clipboard.setPrimaryClip(clip);
            log("copied " + (image ? "image" : "text") + " to clipboard");
            showToast(image ? "图片已复制到剪贴板" : "文字已复制到剪贴板");
        } catch (Throwable error) {
            log("clipboard copy failed", error);
            showToast(image ? "复制图片失败" : "复制文字失败");
        }
    }

    private void openTextSegmentation(String text) {
        if (text == null || text.trim().isEmpty()) {
            showToast("没有可分词的文字");
            return;
        }
        closeSegmentation();
        try {
            final SegmentationOverlay[] holder = new SegmentationOverlay[1];
            holder[0] = new SegmentationOverlay(
                    context,
                    windowPolicy,
                    settings != null && settings.colorMode == DragShareSettings.COLOR_DARK,
                    text,
                    Math.round(lastX),
                    Math.round(lastY),
                    () -> {
                        if (segmentationOverlay == holder[0]) {
                            segmentationOverlay = null;
                            Runnable listener = segmentationClosedListener;
                            if (listener != null) {
                                listener.run();
                            }
                        }
                    });
            segmentationOverlay = holder[0];
            holder[0].show();
            log("opened text segmentation overlay");
        } catch (Throwable error) {
            log("open text segmentation overlay failed; falling back to activity", error);
            openTextSegmentationActivity(text);
        }
    }

    private void openTextSegmentationActivity(String text) {
        try {
            Intent intent = TextSegmentationActivity.createIntent(
                    text,
                    Math.round(lastX),
                    Math.round(lastY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            log("opened text segmentation activity");
        } catch (Throwable error) {
            log("open text segmentation failed", error);
            showToast("无法打开文本分词");
        }
    }

    /** Keeps the portal service alive while the segmentation window is open. */
    boolean hasOpenSegmentation() {
        return segmentationOverlay != null;
    }

    /** The portal hooks flush a deferred stopSelf through this callback. */
    void setSegmentationClosedListener(Runnable listener) {
        segmentationClosedListener = listener;
    }

    void closeSegmentation() {
        SegmentationOverlay overlay = segmentationOverlay;
        if (overlay != null) {
            // close() invokes onClosed, which clears the field and flushes a pending
            // portal stopSelf; do not clear the field before that identity check.
            overlay.close();
        }
    }

    private void saveImageLocally(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            showToast("保存图片失败");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                LocalImageSaver.save(context, bitmap);
                log("saved image as PNG");
                traceAccessibility("local save succeeded");
                mainHandler.post(() -> showToast("已保存到本地", android.widget.Toast.LENGTH_LONG));
            } catch (Throwable error) {
                log("local image save failed", error);
                traceAccessibility("local save failed=" + error.getClass().getSimpleName());
                mainHandler.post(() -> showToast("保存图片失败", android.widget.Toast.LENGTH_LONG));
            }
        }, "drag-share-local-save");
        worker.start();
    }

    /** {@code true} once the capsule may scroll its options: the settle window has passed and
     *  the finger moved away from where it was when the menu appeared. Keeping the first
     *  option reachable matters more than reacting instantly to the initial position. */
    private boolean capsuleScrollArmed(float x) {
        if (SystemClock.uptimeMillis() - menuShownUptime < MENU_SCROLL_SETTLE_MS) {
            return false;
        }
        return Math.abs(x - menuShownX) > dp(MENU_SCROLL_ARM_SLOP_DP);
    }

    private void updatePreviewPosition(float x, float y) {
        if (previewParams == null || previewView == null) {
            return;
        }
        if (isCircleStyle() && menuShown && circleMenuView != null) {
            Rect avoid = circleMenuView.getAvoidRect();
            int margin = dp(12);
            int left = GestureMath.previewLeft(x, previewWidth, screenWidth, dp(8));
            int top = GestureMath.previewTop(
                    y,
                    previewHeight,
                    dp(20),
                    topInset + dp(8),
                    screenHeight - bottomInset - previewHeight - dp(8));
            switch (circleEdge) {
                case CircleMenuGeometry.EDGE_LEFT:
                    left = avoid.right + margin;
                    break;
                case CircleMenuGeometry.EDGE_RIGHT:
                    left = avoid.left - previewWidth - margin;
                    break;
                default:
                    break;
            }
            previewParams.x = GestureMath.clamp(
                    left,
                    dp(8),
                    Math.max(dp(8), screenWidth - previewWidth - dp(8)));
            previewParams.y = GestureMath.clamp(
                    top,
                    topInset + dp(8),
                    Math.max(topInset + dp(8),
                            screenHeight - bottomInset - previewHeight - dp(8)));
            updateOverlayLayout(previewView, modernPreviewWindow, previewParams);
            return;
        }
        int margin = dp(settings.simpleMenuEdgeDistanceDp);
        int minLeft = dp(8);
        int maxLeft = Math.max(minLeft, screenWidth - previewWidth - dp(8));
        int minTop = topInset + dp(8);
        int maxTop = Math.max(minTop, screenHeight - bottomInset - previewHeight - dp(8));
        if (menuShown) {
            if (isVerticalSimpleMenu()) {
                if (simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_LEFT) {
                    minLeft = Math.min(maxLeft, menuLeft + menuWidth + margin);
                } else if (simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_RIGHT) {
                    maxLeft = Math.max(minLeft, menuLeft - previewWidth - margin);
                }
            } else if (simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_TOP) {
                minTop = Math.min(maxTop, menuTop + menuHeight + margin);
            } else {
                maxTop = Math.max(minTop, menuTop - previewHeight - margin);
            }
        }
        previewParams.x = GestureMath.clamp(
                Math.round(x - previewWidth / 2f), minLeft, maxLeft);
        previewParams.y = GestureMath.clamp(
                Math.round(y - previewHeight - dp(20)), minTop, maxTop);
        updateOverlayLayout(previewView, modernPreviewWindow, previewParams);
    }

    private void updateOverlayLayout(
            View view,
            ModernOverlayWindow modernWindow,
            WindowManager.LayoutParams params) {
        if (view == null || params == null) {
            return;
        }
        try {
            if (modernWindow != null) {
                modernWindow.updateLayout();
            } else {
                windowManager.updateViewLayout(view, params);
            }
        } catch (Throwable ignored) {
            // The host may be tearing down its service at the same time.
        }
    }

    private void startBoostPulse() {
        mainHandler.removeCallbacks(boostPulseRunnable);
        mainHandler.postDelayed(boostPulseRunnable, BOOST_PULSE_INTERVAL_MS);
    }

    private void stopBoostPulse() {
        mainHandler.removeCallbacks(boostPulseRunnable);
    }

    private void pulseBoost() {
        ModernOverlayWindow modernWindow = modernPreviewWindow;
        if (modernWindow != null) {
            modernWindow.pulseBoost();
            return;
        }
        View view = previewView;
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.animate()
                .scaleX(1.004f)
                .scaleY(1.004f)
                .setDuration(80L)
                .withEndAction(() -> {
                    View current = previewView;
                    if (current == null) {
                        return;
                    }
                    current.animate().cancel();
                    current.animate().scaleX(1f).scaleY(1f).setDuration(80L).start();
                })
                .start();
    }

    private void updatePortalPullEffect(float x, float y) {
        if (glowView == null || !isPortalStyle()) {
            return;
        }
        float progressStart = Math.max(topInset + dp(120), screenHeight * 0.45f);
        float progress = menuShown
                ? 1f
                : GestureMath.dragPullProgress(y, progressStart, menuTriggerTop);
        if (progress > 0.02f && !portalGlowStartedLogged) {
            portalGlowStartedLogged = true;
            log("portal glow progress started");
        }
        glowView.setPullProgress(progress, x / Math.max(1f, screenWidth));
    }

    private void refreshDisplayGeometry() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
                screenWidth = metrics.getBounds().width();
                screenHeight = metrics.getBounds().height();
                Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                topInset = insets.top;
                bottomInset = insets.bottom;
            } else {
                throw new UnsupportedOperationException("legacy display metrics");
            }
        } catch (Throwable ignored) {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            topInset = 0;
            bottomInset = 0;
        }
        if (isCircleStyle()) {
            menuWidth = screenWidth;
            menuHeight = dp(CircleMenuOverlayView.CONTAINER_SIZE_DP);
            menuLeft = 0;
            menuTop = Math.max(topInset, screenHeight - bottomInset - menuHeight);
            menuTriggerTop = menuTop;
            return;
        }
        if (isPortalStyle()) {
            menuWidth = screenWidth;
            menuHeight = dp(PORTAL_MENU_HEIGHT_DP);
            menuLeft = 0;
            menuTop = Math.max(topInset, screenHeight - bottomInset - menuHeight);
            menuTriggerTop = Math.max(
                    menuTop,
                    screenHeight - bottomInset - dp(PORTAL_TRIGGER_FROM_BOTTOM_DP));
            return;
        }

        if (isCapsuleMenu()) {
            // OriginOS-like capsule: compact, centred, pinned under the status bar.
            int capsuleWidth = Math.min(
                    Math.max(1, screenWidth - dp(24)),
                    dp(CAPSULE_WIDTH_DP));
            menuWidth = Math.max(1, capsuleWidth);
            menuHeight = dp(CAPSULE_HEIGHT_DP);
            menuLeft = Math.max(0, (screenWidth - menuWidth) / 2);
            // Sit on the status-bar / dynamic-island line instead of below it.
            menuTop = Math.max(0, topInset - dp(24));
            menuTriggerTop = menuTop + menuHeight;
            return;
        }

        if (configuredSimpleMenuPosition
                == DragShareSettings.SIMPLE_MENU_POSITION_BOTH_SIDES) {
            // The pointer decides the side for each layout pass; never reset it to left
            // here or a side switch would immediately undo itself.
            if (simpleMenuPosition != DragShareSettings.SIMPLE_MENU_POSITION_LEFT
                    && simpleMenuPosition != DragShareSettings.SIMPLE_MENU_POSITION_RIGHT) {
                simpleMenuPosition = DragShareSettings.SIMPLE_MENU_POSITION_LEFT;
            }
        } else {
            simpleMenuPosition = effectiveSimpleMenuPosition();
        }
        int menuMargin = dp(settings.simpleMenuEdgeDistanceDp);
        if (isVerticalSimpleMenu()) {
            menuWidth = Math.min(dp(SIMPLE_SIDE_MENU_WIDTH_DP), Math.max(1, screenWidth / 2));
            int availableHeight = Math.max(1, screenHeight - topInset - bottomInset);
            int contentHeight = shareTargets.size() * dp(VERTICAL_MENU_ITEM_HEIGHT_DP) + dp(16);
            int minHeight = dp(VERTICAL_MENU_MIN_HEIGHT_DP);
            int maxHeight = Math.max(minHeight,
                    availableHeight * VERTICAL_MENU_MAX_HEIGHT_PERCENT / 100);
            menuHeight = Math.min(Math.max(contentHeight, minHeight), maxHeight);
            menuTop = topInset + Math.max(0, (availableHeight - menuHeight) / 2);
            menuLeft = simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_LEFT
                    ? menuMargin
                    : Math.max(0, screenWidth - menuWidth - menuMargin);
            menuTriggerTop = menuTop;
        } else {
            int horizontalInset = isModernStyle()
                    && simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_BOTTOM
                    ? Math.min(
                            dp(MODERN_BOTTOM_MENU_SIDE_MARGIN_DP),
                            Math.max(0, (screenWidth - 1) / 2))
                    : 0;
            menuWidth = Math.max(1, screenWidth - horizontalInset * 2);
            menuHeight = dp(MENU_HEIGHT_DP);
            menuLeft = horizontalInset;
            if (simpleMenuPosition == DragShareSettings.SIMPLE_MENU_POSITION_TOP) {
                menuTop = topInset + menuMargin;
                menuTriggerTop = menuTop + dp(MENU_TRIGGER_DP);
            } else {
                menuTop = Math.max(
                        topInset,
                        screenHeight - bottomInset - menuHeight - menuMargin);
                menuTriggerTop = menuTop - dp(MENU_TRIGGER_DP);
            }
        }
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, String title) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = windowPolicy.windowType;
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.gravity = Gravity.TOP | Gravity.START;
        params.width = width;
        params.height = height;
        params.x = 0;
        params.y = 0;
        params.setTitle(title);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        DisplayRefreshRate.applyParams(params, context);
        return params;
    }

    private void removeGestureViews() {
        removeGestureViews(false);
    }

    private void removeGestureViews(boolean animatePreviewExit) {
        stopBoostPulse();
        unregisterNearHandSensor();
        cancelModernMenuDispose();
        cancelLinearMenuFadeOut();
        if (!animatePreviewExit) {
            removePendingPreviewExitImmediately();
        }
        if (previewView != null && !animatePreviewExit) {
            previewView.animate().cancel();
        }
        if (menuView != null) {
            menuView.animate().cancel();
        }
        for (View item : menuItems) {
            item.animate().cancel();
        }
        if (animatePreviewExit && previewView != null) {
            startPreviewExit(previewView, modernPreviewView, modernPreviewWindow);
        } else {
            removeOverlayView(previewView, modernPreviewView, modernPreviewWindow);
        }
        removeOverlayView(menuView, modernMenuView, modernMenuWindow);
        if (glowView != null) {
            glowView.stop();
            try {
                windowManager.removeViewImmediate(glowView);
            } catch (Throwable ignored) {
                // Already removed or never attached.
            }
        }
        mainHandler.removeCallbacks(circleExpandRunnable);
        if (circleMenuView != null) {
            circleMenuView.collapse();
            try {
                windowManager.removeViewImmediate(circleMenuView);
            } catch (Throwable ignored) {
                // Already removed or never attached.
            }
        }
        previewView = null;
        modernPreviewView = null;
        modernPreviewWindow = null;
        previewParams = null;
        glowView = null;
        glowParams = null;
        menuView = null;
        modernMenuView = null;
        modernMenuWindow = null;
        modernMenuDisposeRunnable = null;
        fadingLinearMenuView = null;
        modernPreviewEnterStarted = false;
        menuParams = null;
        menuScroll = null;
        menuVerticalScroll = null;
        menuRow = null;
        circleMenuView = null;
        circleMenuParams = null;
        menuItems.clear();
        selectedTarget = null;
        menuShown = false;
        edgeDirection = 0;
        edgeScrollRemainderPx = 0f;
        circleEdge = CircleMenuGeometry.EDGE_NONE;
        circlePendingEdge = CircleMenuGeometry.EDGE_NONE;
        circleScrollDirection = 0;
        menuLeft = 0;
        menuWidth = 0;
    }

    private void removeOverlayView(
            View view,
            ModernOverlayComposeView modernOverlay,
            ModernOverlayWindow modernWindow) {
        if (modernWindow != null) {
            modernWindow.dispose();
            return;
        }
        if (view != null) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (Throwable ignored) {
                // Already removed or never attached.
            }
        }
        if (modernOverlay != null) {
            modernOverlay.disposeOverlay();
        }
    }

    private void startPreviewExit(
            View previewToFade,
            ModernPreviewOverlayView modernPreviewToFade,
            ModernOverlayWindow modernWindowToFade) {
        removePendingPreviewExitImmediately();
        final int generation = ++previewExitGeneration;
        exitingPreviewView = previewToFade;
        exitingModernPreviewView = modernPreviewToFade;
        exitingModernPreviewWindow = modernWindowToFade;

        if (modernWindowToFade != null) {
            modernWindowToFade.hideAnimated(
                    MODERN_OVERLAY_EXIT_DURATION_MS,
                    () -> finishPreviewExit(
                            generation,
                            previewToFade,
                            modernPreviewToFade,
                            modernWindowToFade));
            return;
        }
        if (modernPreviewToFade != null) {
            modernPreviewToFade.hideAnimated();
            mainHandler.postDelayed(
                    () -> finishPreviewExit(
                            generation,
                            previewToFade,
                            modernPreviewToFade,
                            null),
                    MODERN_OVERLAY_EXIT_DURATION_MS);
            return;
        }

        previewToFade.animate().cancel();
        previewToFade.animate()
                .alpha(0f)
                .scaleX(Math.max(0f, previewToFade.getScaleX() * 0.96f))
                .scaleY(Math.max(0f, previewToFade.getScaleY() * 0.96f))
                .setDuration(PREVIEW_EXIT_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> finishPreviewExit(
                        generation,
                        previewToFade,
                        null,
                        null))
                .start();
    }

    private void finishPreviewExit(
            int generation,
            View previewToRemove,
            ModernPreviewOverlayView modernPreviewToRemove,
            ModernOverlayWindow modernWindowToRemove) {
        if (generation != previewExitGeneration
                || exitingPreviewView != previewToRemove
                || exitingModernPreviewView != modernPreviewToRemove
                || exitingModernPreviewWindow != modernWindowToRemove) {
            return;
        }
        exitingPreviewView = null;
        exitingModernPreviewView = null;
        exitingModernPreviewWindow = null;
        removeOverlayView(previewToRemove, modernPreviewToRemove, modernWindowToRemove);
    }

    private boolean isPreviewExitPending() {
        return exitingPreviewView != null;
    }

    private void removePendingPreviewExitImmediately() {
        previewExitGeneration++;
        View previewToRemove = exitingPreviewView;
        ModernPreviewOverlayView modernPreviewToRemove = exitingModernPreviewView;
        ModernOverlayWindow modernWindowToRemove = exitingModernPreviewWindow;
        exitingPreviewView = null;
        exitingModernPreviewView = null;
        exitingModernPreviewWindow = null;
        if (previewToRemove != null) {
            previewToRemove.animate().cancel();
        }
        removeOverlayView(previewToRemove, modernPreviewToRemove, modernWindowToRemove);
    }

    private void scheduleModernMenuDispose(
            ModernMenuOverlayView menuToDispose,
            ModernOverlayWindow windowToDispose) {
        cancelModernMenuDispose();
        Runnable disposeRunnable = () -> {
            if (menuShown || modernMenuView != menuToDispose) {
                return;
            }
            if (windowToDispose != null) {
                windowToDispose.dispose();
            } else {
                try {
                    windowManager.removeViewImmediate(menuToDispose);
                } catch (Throwable ignored) {
                    // The overlay can already be gone while the host tears down its window.
                }
                menuToDispose.disposeOverlay();
            }
            if (menuView == menuToDispose) {
                menuView = null;
                modernMenuView = null;
                modernMenuWindow = null;
                menuParams = null;
            }
        };
        modernMenuDisposeRunnable = disposeRunnable;
        if (windowToDispose != null) {
            windowToDispose.hideAnimated(
                    MODERN_OVERLAY_EXIT_DURATION_MS,
                    () -> {
                        if (modernMenuDisposeRunnable == disposeRunnable) {
                            disposeRunnable.run();
                        }
                    });
        } else {
            menuToDispose.hideAnimated();
            mainHandler.postDelayed(disposeRunnable, MODERN_OVERLAY_EXIT_DURATION_MS);
        }
    }

    private void cancelModernMenuDispose() {
        if (modernMenuDisposeRunnable != null) {
            mainHandler.removeCallbacks(modernMenuDisposeRunnable);
            modernMenuDisposeRunnable = null;
        }
    }

    private void fadeOutLinearMenu(View menuToFade) {
        if (menuToFade == null) {
            return;
        }
        cancelLinearMenuFadeOut();
        final int generation = ++linearMenuFadeGeneration;
        fadingLinearMenuView = menuToFade;
        menuToFade.animate().cancel();
        menuToFade.animate()
                .alpha(0f)
                .setDuration(LINEAR_MENU_EXIT_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    if (generation != linearMenuFadeGeneration
                            || fadingLinearMenuView != menuToFade) {
                        return;
                    }
                    fadingLinearMenuView = null;
                    if (menuView == menuToFade && menuShown) {
                        return;
                    }
                    removeLinearMenuImmediately(menuToFade);
                })
                .start();
    }

    private void cancelLinearMenuFadeOut(View menuToRestore) {
        if (fadingLinearMenuView == menuToRestore) {
            cancelLinearMenuFadeOut();
        }
    }

    private void cancelLinearMenuFadeOut() {
        linearMenuFadeGeneration++;
        View fading = fadingLinearMenuView;
        fadingLinearMenuView = null;
        if (fading != null) {
            fading.animate().cancel();
        }
    }

    private void removeLinearMenuImmediately(View menuToRemove) {
        if (menuToRemove == null) {
            return;
        }
        menuToRemove.animate().cancel();
        try {
            windowManager.removeViewImmediate(menuToRemove);
        } catch (Throwable ignored) {
            // The overlay may already have been removed by the host.
        }
    }

    private void stopEdgeScroll() {
        edgeDirection = 0;
        edgeScrollOutsideMenu = false;
        circleScrollDirection = 0;
        lastEdgeScrollUptime = 0L;
        edgeScrollRemainderPx = 0f;
        mainHandler.removeCallbacks(edgeScrollRunnable);
    }

    private boolean isPortalStyle() {
        return settings != null && settings.uiStyle == DragShareSettings.STYLE_PORTAL;
    }

    private boolean isCircleStyle() {
        return settings != null && settings.uiStyle == DragShareSettings.STYLE_CIRCLE;
    }

    private boolean isModernStyle() {
        return settings != null && settings.uiStyle == DragShareSettings.STYLE_MODERN;
    }

    private void startBackgroundBlockerIfEnabled() {
        if (!settings.blockBackgroundScroll || backgroundBlockAttempted) {
            return;
        }
        backgroundBlockAttempted = true;
        if (!backgroundTouchBlocker.start()) {
            log("background scroll lock unavailable; continuing without it");
        }
    }

    private GradientDrawable itemBackground(ShareTarget target, boolean selected) {
        if (!selected) {
            return roundDrawable(Color.TRANSPARENT, isPortalStyle() ? 28 : 8);
        }
        GradientDrawable drawable = roundDrawable(
                palette.selectedItemBackground,
                isPortalStyle() ? 28 : 8);
        if (isPortalStyle()) {
            drawable.setStroke(dp(1), palette.selectedItemBorder);
        }
        return drawable;
    }

    private Drawable iconForTarget(ShareTarget target) {
        return ShareTargetRepository.iconForDisplay(target);
    }

    private static int applyAbsoluteOpacity(int color, int percent) {
        int alpha = Math.round(255f * (Math.max(0, Math.min(100, percent)) / 100f));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static float simpleMenuBackgroundOpacityFraction(int percent) {
        return Math.max(0, Math.min(100, percent)) / 100f;
    }

    private GradientDrawable roundDrawable(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()));
    }

    private float dpFloat(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics());
    }

    private void showToast(String message) {
        dragShareToast.show(message);
    }

    private void showToast(String message, int duration) {
        dragShareToast.show(message, duration);
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    private static void log(String message) {
        DragShareLog.i(TAG, message);
    }

    private static void log(String message, Throwable error) {
        DragShareLog.w(TAG, message, error);
    }

    private void traceAccessibility(String message) {
        if (windowPolicy.traceToAccessibility) {
            AccessibilityTrace.record(context, message);
        }
    }

    private static final class Session {
        final CapturedContent payload;
        Uri stagedUri;
        ShareTarget pendingTarget;
        boolean cancelled;

        Session(CapturedContent payload) {
            this.payload = payload;
        }
    }

    private static final class OverlayColors {
        final int previewBackground;
        final int menuBackground;
        final int primaryText;
        final int secondaryText;
        final int selectedItemBackground;
        final int selectedItemBorder;
        final int accent;

        private OverlayColors(
                int previewBackground,
                int menuBackground,
                int primaryText,
                int secondaryText,
                int selectedItemBackground,
                int selectedItemBorder,
                int accent) {
            this.previewBackground = previewBackground;
            this.menuBackground = menuBackground;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.selectedItemBackground = selectedItemBackground;
            this.selectedItemBorder = selectedItemBorder;
            this.accent = accent;
        }

        static OverlayColors light() {
            return new OverlayColors(
                    0xF7FFFFFF,
                    0xF4F8FAF9,
                    0xFF17201D,
                    0xFF55615C,
                    0x26137A5A,
                    0x00000000,
                    0xFF137A5A);
        }

        static OverlayColors from(DragShareSettings settings) {
            if (settings != null && settings.uiStyle == DragShareSettings.STYLE_CIRCLE) {
                if (settings.colorMode == DragShareSettings.COLOR_DARK) {
                    return new OverlayColors(
                            0xF72A2D32,
                            0xE91E242B,
                            0xFFF3F5F4,
                            0xFFB7BFBB,
                            0x4C4C9DFF,
                            0xAA7AC9FF,
                            0xFF65C6E8);
                }
                return new OverlayColors(
                        0xFCFFFFFF,
                        0xE9F7FAFC,
                        0xFF17201D,
                        0xFF65716B,
                        0x3A5DABE8,
                        0xAA438CB6,
                        0xFF2E9EB7);
            }
            if (settings != null && settings.uiStyle == DragShareSettings.STYLE_PORTAL) {
                if (settings.colorMode == DragShareSettings.COLOR_DARK) {
                    return new OverlayColors(
                            0xF72A2D32,
                            0x003A3E43,
                            0xFFF3F5F4,
                            0xFFB7BFBB,
                            0x384F8BFF,
                            0x887AC9FF,
                            0xFF71DDEB);
                }
                return new OverlayColors(
                        0xFCFFFFFF,
                        0x00FFFFFF,
                        0xFF17201D,
                        0xFF65716B,
                        0x306F9DFF,
                        0x8074B8FF,
                        0xFF2CA9BD);
            }
            if (settings != null && settings.colorMode == DragShareSettings.COLOR_DARK) {
                return new OverlayColors(
                        0xF725272A,
                        0xF42F3135,
                        0xFFF3F5F4,
                        0xFFB7BFBB,
                        0x3358B995,
                        0x6658B995,
                        0xFF58B995);
            }
            return light();
        }
    }
}
