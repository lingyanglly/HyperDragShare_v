package com.leaf.hyperdragshare.codex;

import android.content.Context;
import android.os.Bundle;

/** Persistent toggle for the experimental root input helper. Default: on. */
final class RootHelperPrefs {
    /** Bundle key used by {@code get_settings}; the portal cannot read this file directly. */
    static final String BUNDLE_KEY = "root_helper_enabled";

    private static final String PREFS = "root_helper";
    private static final String KEY_ENABLED = "enabled";

    private RootHelperPrefs() {}

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
        // The injected portal process observes the settings URI; notify it so it can
        // unbind/rebind the helper immediately instead of waiting for a restart.
        context.getContentResolver().notifyChange(DragShareSettings.settingsUri(), null);
    }

    /**
     * Portal-side check. The injected process runs under the portal UID and cannot read the
     * module's private prefs, so the flag round-trips through the module provider. Defaults
     * to on when the provider is unreachable: losing the helper silently is worse than a
     * failed bind that the reconnect loop already tolerates.
     */
    static boolean isEnabledForCaller(Context context) {
        try {
            Bundle result = context.getContentResolver().call(
                    ImageStagingClient.BASE_URI,
                    DragShareSettings.METHOD_GET_SETTINGS,
                    null,
                    null);
            return result == null || result.getBoolean(BUNDLE_KEY, true);
        } catch (Throwable ignored) {
            return true;
        }
    }
}
