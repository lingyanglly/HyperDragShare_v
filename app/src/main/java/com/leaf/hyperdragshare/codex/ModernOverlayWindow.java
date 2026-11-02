package com.leaf.hyperdragshare.codex;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

import java.util.function.Consumer;

/**
 * A bounded, non-interactive native window for the modern preview and menu.
 * Window background blur is compositor-backed and respects the transparent
 * rounded window background, unlike blur-behind which affects all content
 * below an overlay window.
 */
final class ModernOverlayWindow {
    private static final String TAG = "DragShare/Overlay";
    private static final float CARD_CORNER_RADIUS_DP = 16f;
    private static final long ENTER_DURATION_MS = 220L;
    /** Rough budget for the window add, Compose composition and first layout. */
    private static final long FIRST_FRAME_WAIT_ALLOWANCE_MS = 140L;
    private static final float ENTER_INITIAL_SCALE = 0.9f;
    private static final float EXIT_TARGET_SCALE = 0.96f;
    private static final int SURFACE_VOTE_MAX_ATTEMPTS = 8;
    private static final PathInterpolator ENTER_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final PathInterpolator EXIT_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 1f, 1f);

    private final Dialog dialog;
    private final WindowManager windowManager;
    private final ModernOverlayComposeView overlayView;
    private final WindowManager.LayoutParams layoutParams;
    private final int blurRadiusPx;
    private final Consumer<Boolean> blurEnabledListener;
    private boolean blurListenerRegistered;
    private boolean crossWindowBlurEnabled;
    private boolean nativeBackdropBlurEnabled;
    private boolean disposed;
    private int animationGeneration;
    private long enterAnimationUntilUptime;
    private boolean enterAnimationStarted;

    ModernOverlayWindow(
            Context context,
            WindowManager windowManager,
            ModernOverlayComposeView overlayView,
            WindowManager.LayoutParams layoutParams,
            int blurRadiusPx) {
        if (context == null || windowManager == null || overlayView == null
                || layoutParams == null) {
            throw new IllegalArgumentException("Modern overlay window requires its view and layout");
        }
        this.windowManager = windowManager;
        this.overlayView = overlayView;
        this.layoutParams = layoutParams;
        this.blurRadiusPx = Math.max(0, blurRadiusPx);

        ContextThemeWrapper themedContext = new ContextThemeWrapper(
                context,
                android.R.style.Theme_Translucent_NoTitleBar);
        dialog = new Dialog(themedContext);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(overlayView);

        Window window = dialog.getWindow();
        if (window == null) {
            throw new IllegalStateException("Modern overlay dialog has no Window");
        }
        window.setBackgroundDrawable(transparentRoundedBackground(context));
        window.setDimAmount(0f);
        window.setWindowAnimations(0);
        window.setAttributes(layoutParams);
        DisplayRefreshRate.apply(window, context);
        window.setBackgroundBlurRadius(this.blurRadiusPx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.getDecorView().setForceDarkAllowed(false);
        }

        blurEnabledListener = enabled -> overlayView.post(() -> {
            if (!disposed) {
                updateCrossWindowBlurEnabled(Boolean.TRUE.equals(enabled));
            }
        });
        refreshNativeBackdropBlurEnabled();
        registerBlurEnabledListener();
    }

    void show() {
        if (!disposed && !dialog.isShowing()) {
            prepareDecorForEnter();
            dialog.show();
            applySurfaceFrameRateVote(0);
        }
    }

    /**
     * The window surface control only exists after the first relayout, so the vote is
     * retried on animation frames until it lands. A failed vote leaves the window-attribute
     * request in place; the drag still works, only the panel rate follows the app.
     */
    private void applySurfaceFrameRateVote(int attempt) {
        if (disposed) {
            return;
        }
        if (attempt >= SURFACE_VOTE_MAX_ATTEMPTS) {
            DragShareLog.w(TAG, "surface frame-rate vote unavailable after "
                    + attempt + " attempts; panel rate follows the app");
            return;
        }
        View decorView = decorView();
        if (decorView == null) {
            return;
        }
        if (DisplayRefreshRate.applySurfaceVote(decorView, overlayView.getContext())) {
            return;
        }
        decorView.postOnAnimation(() -> applySurfaceFrameRateVote(attempt + 1));
    }

    void showAnimated() {
        showAnimated(ENTER_INITIAL_SCALE, 0L);
    }

    /**
     * Relay entry: the island shrinks away while this window grows in from {@code
     * initialScale}. A non-zero start delay lets the preview lead and the menu trail it, so the
     * two windows read as one sequence instead of popping in together.
     */
    void showAnimated(float initialScale, long startDelayMs) {
        if (disposed) {
            return;
        }
        animationGeneration++;
        // Flip the Compose state before the window is added: doing it after show() scheduled a
        // recomposition on the very frame that was supposed to be the first smooth one.
        overlayView.setContentVisibleForWindowAnimation(true);
        show();
        View decorView = decorView();
        if (decorView == null) {
            return;
        }
        final float fromScale = initialScale;
        final long delay = Math.max(0L, startDelayMs);
        final int generation = animationGeneration;
        final long requestedUptime = SystemClock.uptimeMillis();
        enterAnimationStarted = false;
        decorView.setAlpha(0f);
        decorView.setScaleX(fromScale);
        decorView.setScaleY(fromScale);
        // Keep the pulse boost quiet for the pending wait as well as for the animation itself.
        enterAnimationUntilUptime = requestedUptime + delay + ENTER_DURATION_MS
                + FIRST_FRAME_WAIT_ALLOWANCE_MS;
        decorView.animate().cancel();
        // show() adds the window here, so its first layout, the Compose composition and the
        // compositor blur pass all land on the very same frame. Starting the animation right away
        // would spend the first 60-120ms before a single pixel is on screen and then pop in
        // half-way through, which reads as a stutter. Hold the start scale and start the animation
        // from the frame that is actually drawn.
        final View firstFrameView = decorView;
        firstFrameView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        ViewTreeObserver observer = firstFrameView.getViewTreeObserver();
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(this);
                        }
                        startEnterAnimation(
                                firstFrameView, fromScale, delay, generation, requestedUptime);
                        return true;
                    }
                });
        // Never leave the window invisible if the traversal produces no frame at all.
        firstFrameView.postDelayed(
                () -> startEnterAnimation(
                        firstFrameView, fromScale, delay, generation, requestedUptime),
                FIRST_FRAME_WAIT_ALLOWANCE_MS + 80L);
    }

    private void startEnterAnimation(
            View decorView,
            float fromScale,
            long delay,
            int generation,
            long requestedUptime) {
        if (disposed || enterAnimationStarted || generation != animationGeneration) {
            return;
        }
        enterAnimationStarted = true;
        long waited = SystemClock.uptimeMillis() - requestedUptime;
        if (waited > 24L) {
            DragShareLog.i(TAG, "enter animation waited " + waited + "ms for the first frame");
        }
        decorView.setAlpha(0f);
        decorView.setScaleX(fromScale);
        decorView.setScaleY(fromScale);
        enterAnimationUntilUptime = SystemClock.uptimeMillis() + delay + ENTER_DURATION_MS;
        decorView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delay)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(ENTER_INTERPOLATOR)
                .start();
    }

    void hideAnimated(long durationMillis, Runnable afterAnimation) {
        if (disposed) {
            return;
        }
        final int generation = ++animationGeneration;
        View decorView = decorView();
        if (!dialog.isShowing() || decorView == null) {
            finishHideAnimation(generation, afterAnimation);
            return;
        }
        decorView.animate().cancel();
        decorView.animate()
                .alpha(0f)
                .scaleX(EXIT_TARGET_SCALE)
                .scaleY(EXIT_TARGET_SCALE)
                .setDuration(Math.max(0L, durationMillis))
                .setInterpolator(EXIT_INTERPOLATOR)
                .withEndAction(() -> finishHideAnimation(generation, afterAnimation))
                .start();
    }

    boolean isShowing() {
        return !disposed && dialog.isShowing();
    }

    boolean isNativeBackdropBlurEnabled() {
        return nativeBackdropBlurEnabled;
    }

    void updateLayout() {
        if (disposed || !dialog.isShowing()) {
            return;
        }
        Window window = dialog.getWindow();
        View decorView = window == null ? null : window.getDecorView();
        if (decorView != null) {
            windowManager.updateViewLayout(decorView, layoutParams);
        }
    }

    /**
     * A barely visible scale pulse (1.000 → 1.004 → 1.000). HyperOS' refresh policy keeps
     * its high-frame-rate interaction window alive while window animations run, so pulsing
     * during a drag prevents the panel from dropping to the app's 60 Hz mid-gesture.
     */
    void pulseBoost() {
        if (disposed || !dialog.isShowing()) {
            return;
        }
        View decorView = decorView();
        if (decorView == null) {
            return;
        }
        // Re-assert the frame-rate vote: the display mode director aggregates votes, so a
        // foreground app voting 60 Hz between pulses would otherwise drop the drag to 60.
        DisplayRefreshRate.applySurfaceVote(decorView, overlayView.getContext());
        if (SystemClock.uptimeMillis() < enterAnimationUntilUptime) {
            // Cancelling the enter animation mid-flight would snap the window to full size and
            // undo the relay; the vote above keeps the frame rate high meanwhile.
            return;
        }
        final int generation = animationGeneration;
        decorView.animate().cancel();
        decorView.animate()
                .scaleX(1.004f)
                .scaleY(1.004f)
                .setDuration(80L)
                .withEndAction(() -> {
                    if (disposed || animationGeneration != generation) {
                        return;
                    }
                    View current = decorView();
                    if (current == null) {
                        return;
                    }
                    current.animate().cancel();
                    current.animate().scaleX(1f).scaleY(1f).setDuration(80L).start();
                })
                .start();
    }

    void dispose() {
        if (disposed) {
            return;
        }
        animationGeneration++;
        disposed = true;
        View decorView = decorView();
        if (decorView != null) {
            decorView.animate().cancel();
        }
        if (blurListenerRegistered) {
            try {
                windowManager.removeCrossWindowBlurEnabledListener(blurEnabledListener);
            } catch (Throwable ignored) {
                // The host window manager can already be shutting down.
            }
            blurListenerRegistered = false;
        }
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
            // The dialog may never have reached WindowManager.addView().
        }
        overlayView.disposeOverlay();
    }

    static GradientDrawable transparentRoundedBackground(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(localBackgroundCornerRadiusPx(context));
        return drawable;
    }

    static float localBackgroundCornerRadiusPx(Context context) {
        return CARD_CORNER_RADIUS_DP * context.getResources().getDisplayMetrics().density;
    }

    static boolean shouldUseNativeBackdropBlur(int radiusPx, boolean crossWindowBlurEnabled) {
        return radiusPx > 0 && crossWindowBlurEnabled;
    }

    private void registerBlurEnabledListener() {
        if (blurRadiusPx <= 0) {
            return;
        }
        try {
            windowManager.addCrossWindowBlurEnabledListener(blurEnabledListener);
            blurListenerRegistered = true;
        } catch (Throwable ignored) {
            updateCrossWindowBlurEnabled(false);
        }
    }

    private void refreshNativeBackdropBlurEnabled() {
        boolean enabled = false;
        if (blurRadiusPx > 0) {
            try {
                enabled = windowManager.isCrossWindowBlurEnabled();
            } catch (Throwable ignored) {
                // The transparent glass fallback below remains readable.
            }
        }
        updateCrossWindowBlurEnabled(enabled);
    }

    private void updateCrossWindowBlurEnabled(boolean enabled) {
        crossWindowBlurEnabled = enabled;
        updateOverlayBlurAvailability();
    }

    private void updateOverlayBlurAvailability() {
        nativeBackdropBlurEnabled = shouldUseNativeBackdropBlur(
                blurRadiusPx,
                crossWindowBlurEnabled);
        overlayView.updateNativeBackdropBlurAvailability(nativeBackdropBlurEnabled);
    }

    private void prepareDecorForEnter() {
        View decorView = decorView();
        if (decorView == null) {
            return;
        }
        decorView.animate().cancel();
        decorView.setAlpha(0f);
        decorView.setScaleX(ENTER_INITIAL_SCALE);
        decorView.setScaleY(ENTER_INITIAL_SCALE);
    }

    private View decorView() {
        Window window = dialog.getWindow();
        return window == null ? null : window.getDecorView();
    }

    private void finishHideAnimation(int generation, Runnable afterAnimation) {
        if (disposed || animationGeneration != generation) {
            return;
        }
        overlayView.setContentVisibleForWindowAnimation(false);
        if (afterAnimation != null) {
            afterAnimation.run();
        }
    }
}
