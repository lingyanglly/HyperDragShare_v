package com.leaf.hyperdragshare.codex;

import android.app.Application;
import android.content.Intent;

/** Starts the optional tokenizer warm-up for every module-app process. */
public final class DragShareApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DragShareLog.configure(DragShareSettings.readLocal(this));
        DragShareDiagnostics.captureRuntimeOnce(this, "module application created", null);
        TextSegmenter.preloadIfEnabled(this);
        startRootHelperIfEnabled();
    }

    /**
     * Fallback for ROMs that block BOOT_COMPLETED: opening the module once starts the
     * persistent helper; START_STICKY keeps it alive afterwards.
     */
    private void startRootHelperIfEnabled() {
        if (!RootHelperPrefs.isEnabled(this)) {
            return;
        }
        try {
            startForegroundService(new Intent(this, RootInputService.class));
            DragShareLog.i("DragShare/RootService", "app launch start requested");
        } catch (Throwable error) {
            DragShareLog.w("DragShare/RootService", "app launch start failed", error);
        }
    }
}
