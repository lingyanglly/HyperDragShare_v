package com.leaf.hyperdragshare.codex;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Manifest-declared receiver for the portal process.
 *
 * <p>The island lives in the module process (its notification is the module's, which is the
 * package HyperOS authorised for focus notifications). The portal cannot post it, so it asks
 * for it with a broadcast. This receiver has to be in the manifest instead of being registered
 * at runtime by the accessibility service: in portal-only mode that service is not running, and
 * a runtime-only receiver silently swallowed every request, which made the island appear "some
 * of the time" while the share window still worked.
 */
public final class IslandBridgeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!DragReadyIsland.ACTION_ISLAND_BRIDGE.equals(intent.getAction())) {
            return;
        }
        boolean visible = intent.getBooleanExtra(DragReadyIsland.EXTRA_ISLAND_VISIBLE, false);
        DragShareLog.i("DragShare/Island", "bridge request visible=" + visible);
        if (visible) {
            DragReadyIsland.show(context);
        } else {
            DragReadyIsland.dismiss(context);
        }
    }
}
