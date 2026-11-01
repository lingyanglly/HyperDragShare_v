package com.leaf.hyperdragshare.codex;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Keeps the root input pipe warm across reboots so the first drag after boot is instant. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !RootHelperPrefs.isEnabled(context)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, RootInputService.class));
            DragShareLog.i("DragShare/RootService", "boot start requested");
        } catch (Throwable error) {
            DragShareLog.w("DragShare/RootService", "boot start failed", error);
        }
    }
}
