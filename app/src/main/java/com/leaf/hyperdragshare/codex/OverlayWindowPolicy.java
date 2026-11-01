package com.leaf.hyperdragshare.codex;

import android.view.WindowManager;

/**
 * One overlay policy for every share window.
 *
 * <p>The window type is a runtime capability, not a property of the payload: the portal runs
 * with {@code INTERNAL_SYSTEM_WINDOW} so it can host {@link WindowManager.LayoutParams#TYPE_SYSTEM_DIALOG},
 * while the module process is an ordinary app and can only host
 * {@link WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY} (it is an accessibility service).
 * Combined mode always routes content to the portal, so a gesture never produces two windows.
 */
final class OverlayWindowPolicy {
    final int windowType;
    /** Portal-hosted windows wait for finger movement before taking over the gesture. */
    final boolean deferUntilMove;
    /** Module-hosted windows keep the in-process accessibility trace. */
    final boolean traceToAccessibility;

    private OverlayWindowPolicy(int windowType, boolean deferUntilMove, boolean traceToAccessibility) {
        this.windowType = windowType;
        this.deferUntilMove = deferUntilMove;
        this.traceToAccessibility = traceToAccessibility;
    }

    @SuppressWarnings("deprecation")
    static OverlayWindowPolicy systemHosted() {
        // Taplus is granted INTERNAL_SYSTEM_WINDOW. On the verified HyperOS device, the
        // same OPAQUE alpha=1 probe remains solid only on this window type, not TYPE_PHONE.
        return new OverlayWindowPolicy(
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                true,
                false);
    }

    static OverlayWindowPolicy accessibilityHosted() {
        return new OverlayWindowPolicy(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                false,
                true);
    }
}
