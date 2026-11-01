package com.leaf.hyperdragshare.codex;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Keeps the root input pipe warm across reboots so the first drag after boot is instant. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if ((!boot && !replaced) || !RootHelperPrefs.isEnabled(context)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, RootInputService.class));
            DragShareLog.i("DragShare/RootService",
                    (replaced ? "package replaced" : "boot") + " start requested");
        } catch (Throwable error) {
            DragShareLog.w("DragShare/RootService", "boot start failed", error);
        }
        if (replaced) {
            // The hook must re-inject into Bilibili after a module update; its process has to
            // restart. Force-stop it through root because HyperOS can ignore am force-stop.
            restartInjectedTarget();
        }
    }

    private void restartInjectedTarget() {
        // Both the portal and the injected app must restart after a module update so the new
        // code (hooks, receivers) is loaded; HyperOS can ignore a plain am force-stop.
        for (String packageName : new String[]{
                "com.miui.contentextension",
                "tv.danmaku.bili"}) {
            boolean restarted = RootShell.run("am force-stop " + packageName);
            DragShareLog.i("DragShare/RootService",
                    "re-injection restart " + packageName + " ok=" + restarted);
        }
    }
}
