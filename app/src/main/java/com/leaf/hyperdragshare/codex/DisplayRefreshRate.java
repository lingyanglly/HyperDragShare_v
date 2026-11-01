package com.leaf.hyperdragshare.codex;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Overlay windows normally follow the foreground app's display mode, so a 60 Hz app makes
 * the drag preview render at 60 Hz even on a 120 Hz panel. The drag overlay votes for the
 * panel's highest supported rate through three channels:
 *
 * <ol>
 *   <li>Window attributes ({@code preferredDisplayModeId} / {@code preferredRefreshRate}),
 *       applied before the window is added.</li>
 *   <li>The overlay surface's own frame-rate vote via
 *       {@link SurfaceControl.Transaction#setFrameRate}, which the display mode director
 *       aggregates together with the foreground app's request.</li>
 *   <li>On API 34+, {@link SurfaceControl.Transaction#setDesiredRefreshRate}, the strongest
 *       app-side vote a visible surface can make.</li>
 * </ol>
 *
 * <p>The old {@code ViewRootImpl.mSurface} reflection path is gone: on BLAST devices that
 * field is released right after the first relayout, so it always threw
 * "Surface has already been released" and the vote never landed. The vote now targets the
 * live {@code ViewRootImpl.getSurfaceControl()} and is retried on animation frames until
 * the surface control exists.
 */
final class DisplayRefreshRate {
    private static final String TAG = "DragShare/Overlay";

    private DisplayRefreshRate() {}

    static float max(Context context) {
        if (context == null) {
            return 0f;
        }
        DisplayManager manager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return 0f;
        }
        Display.Mode current = display.getMode();
        float best = 0f;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (current != null
                    && (mode.getPhysicalWidth() != current.getPhysicalWidth()
                    || mode.getPhysicalHeight() != current.getPhysicalHeight())) {
                // Keep the current resolution so the switch never resizes the UI.
                continue;
            }
            best = Math.max(best, mode.getRefreshRate());
        }
        return best;
    }

    static int maxModeId(Context context) {
        if (context == null) {
            return 0;
        }
        DisplayManager manager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return 0;
        }
        Display.Mode current = display.getMode();
        int bestId = 0;
        float bestRate = 0f;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (current != null
                    && (mode.getPhysicalWidth() != current.getPhysicalWidth()
                    || mode.getPhysicalHeight() != current.getPhysicalHeight())) {
                continue;
            }
            if (mode.getRefreshRate() > bestRate) {
                bestRate = mode.getRefreshRate();
                bestId = mode.getModeId();
            }
        }
        return bestId;
    }

    @SuppressWarnings("deprecation")
    static void apply(Window window, Context context) {
        if (window == null) {
            return;
        }
        float rate = max(context);
        if (rate <= 0f) {
            return;
        }
        try {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.preferredRefreshRate = rate;
            int modeId = maxModeId(context);
            if (modeId > 0) {
                attributes.preferredDisplayModeId = modeId;
            }
            window.setAttributes(attributes);
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    // HyperOS boost: raise the panel to the touch-boost rate while the
                    // finger is down instead of following the foreground app's timeline.
                    window.setFrameRateBoostOnTouchEnabled(true);
                } catch (Throwable ignored) {
                    // Older/other ROMs do not expose the MIUI window extension.
                }
            }
            DragShareLog.i(TAG, "requested overlay frame rate=" + rate
                    + " mode=" + modeId);
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to request overlay frame rate=" + rate, error);
        }
    }

    /**
     * Window-attribute vote for overlays that are added without a {@link Window} object
     * (legacy preview, glow, circle menu, linear menu).
     */
    @SuppressWarnings("deprecation")
    static void applyParams(WindowManager.LayoutParams params, Context context) {
        if (params == null || context == null) {
            return;
        }
        float rate = max(context);
        if (rate <= 0f) {
            return;
        }
        try {
            params.preferredRefreshRate = rate;
            int modeId = maxModeId(context);
            if (modeId > 0) {
                params.preferredDisplayModeId = modeId;
            }
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to request overlay params frame rate=" + rate, error);
        }
    }

    /**
     * Applies the overlay surface's own frame-rate vote. Returns {@code true} once the
     * vote has been handed to the display mode director; callers retry on animation
     * frames while it returns {@code false} because the surface control only exists
     * after the window's first relayout.
     */
    static boolean applySurfaceVote(View decorView, Context context) {
        if (decorView == null || !decorView.isAttachedToWindow()) {
            return false;
        }
        float rate = max(context);
        if (rate <= 0f) {
            return true;
        }
        SurfaceControl surfaceControl = surfaceControlOf(decorView);
        if (surfaceControl == null || !surfaceControl.isValid()) {
            return false;
        }
        try {
            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            transaction.setFrameRate(
                    surfaceControl,
                    rate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
            applyDesiredRefreshRate(transaction, surfaceControl, rate);
            transaction.apply();
            DragShareLog.i(TAG, "surface frame-rate vote applied rate=" + rate);
            return true;
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to apply surface frame-rate vote rate=" + rate, error);
            return false;
        }
    }

    /**
     * {@code Transaction.setDesiredRefreshRate} is the strongest app-side vote a visible
     * surface can make (API 34+, milliHz). It is hidden in this SDK's stubs, so it is
     * invoked reflectively; devices without it keep the {@code setFrameRate} vote.
     */
    private static void applyDesiredRefreshRate(
            SurfaceControl.Transaction transaction,
            SurfaceControl surfaceControl,
            float rate) {
        if (Build.VERSION.SDK_INT < 34) {
            return;
        }
        try {
            java.lang.reflect.Method method = SurfaceControl.Transaction.class.getDeclaredMethod(
                    "setDesiredRefreshRate", SurfaceControl.class, int.class, boolean.class);
            method.setAccessible(true);
            method.invoke(transaction, surfaceControl, Math.round(rate * 1000f), true);
        } catch (Throwable error) {
            DragShareLog.d(TAG, "setDesiredRefreshRate unavailable: "
                    + error.getClass().getSimpleName());
        }
    }

    private static SurfaceControl surfaceControlOf(View decorView) {
        try {
            Object root = callHidden(decorView, "getViewRootImpl");
            if (root == null) {
                return null;
            }
            Object surfaceControl = callHidden(root, "getSurfaceControl");
            return surfaceControl instanceof SurfaceControl
                    ? (SurfaceControl) surfaceControl
                    : null;
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to resolve the overlay surface control", error);
            return null;
        }
    }

    private static Object callHidden(Object receiver, String methodName) {
        Class<?> type = receiver.getClass();
        while (type != null && type != Object.class) {
            try {
                java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(receiver);
            } catch (NoSuchMethodException missing) {
                type = type.getSuperclass();
            } catch (Throwable error) {
                DragShareLog.w(TAG, "hidden call failed " + type.getSimpleName()
                        + "." + methodName, error);
                return null;
            }
        }
        return null;
    }
}
