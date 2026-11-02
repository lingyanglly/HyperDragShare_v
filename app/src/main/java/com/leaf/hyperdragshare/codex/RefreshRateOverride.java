package com.leaf.hyperdragshare.codex;

import android.content.Context;

/**
 * Raises the panel's refresh-rate cap while a drag is on screen.
 *
 * <p>HyperOS keeps the panel at the rate chosen in the display settings and caps the range it
 * lets individual windows request ({@code appRequestRanges render=[0, 60]} in SurfaceFlinger's
 * policy while the setting says 60 Hz). Neither {@code preferredDisplayModeId} nor a surface
 * frame-rate vote can lift that cap, so a drag over a 60 Hz app can never follow the finger at
 * 120 Hz. The only lever that works is the system setting itself: raise it when the gesture
 * starts and put the previous value back the moment the gesture ends, which is exactly the
 * "high frame rate while dragging, back to normal when idle" behaviour the panel already has
 * for its own animations.
 *
 * <p>The original value is also written to a marker file, so a crash mid-drag is repaired on
 * the next process start instead of leaving the whole device pinned to the peak rate.
 */
final class RefreshRateOverride {
    private static final String TAG = "DragShare/Refresh";
    private static final String SETTING_KEY = "miui_refresh_rate";
    private static final String MARKER_DIRECTORY = "/data/local/tmp/HyperDragShare";
    private static final String MARKER_PATH = MARKER_DIRECTORY + "/refresh_original";

    private static String savedValue;
    private static boolean active;
    private static long lastAssertUptime;
    private static long boostedSinceUptime;
    /** Hard ceiling on how long the panel may stay pinned to the peak rate. */
    private static final long MAX_HOLD_MS = 15_000L;
    /**
     * Every shell round-trip here is a {@code su} exec that can take hundreds of milliseconds
     * (and up to its timeout when the helper is restarting). Doing that on the caller's thread
     * stalled the share window's creation, so all root work runs on this daemon thread instead.
     */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "hds-refresh");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile boolean acquireQueued;
    private static long lastKeepAliveSubmitUptime;

    private RefreshRateOverride() {
    }

    /** Raises the cap to the panel peak; a no-op when the cap is already high enough. */
    static void acquire(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        synchronized (WORKER) {
            if (acquireQueued) {
                return;
            }
            acquireQueued = true;
        }
        WORKER.execute(() -> {
            try {
                acquireNow(app);
            } catch (Throwable error) {
                DragShareLog.w(TAG, "unable to raise the refresh cap", error);
            } finally {
                synchronized (WORKER) {
                    acquireQueued = false;
                }
            }
        });
    }

    private static synchronized void acquireNow(Context context) {
        if (active) {
            return;
        }
        int peak = Math.round(DisplayRefreshRate.max(context));
        if (peak <= 0) {
            return;
        }
        String current = RootShell.exec("settings get secure " + SETTING_KEY);
        int currentHz = parseHz(current);
        if (currentHz <= 0) {
            DragShareLog.w(TAG, "unable to read " + SETTING_KEY + ": " + current);
            return;
        }
        active = true;
        boostedSinceUptime = android.os.SystemClock.uptimeMillis();
        // The marker file is the only trustworthy record of the original cap: it survives a
        // force-stop or a crash mid-drag. When it exists the setting has already been raised by
        // an earlier acquire, so the marker (not the current value) decides what to restore.
        int markedHz = parseHz(readMarker());
        if (markedHz > 0) {
            savedValue = Integer.toString(markedHz);
            lastAssertUptime = android.os.SystemClock.uptimeMillis();
            if (currentHz < peak) {
                RootShell.run("settings put secure " + SETTING_KEY + " " + peak);
            }
            DragShareLog.i(TAG, "refresh cap re-raised to " + peak + "Hz (was " + markedHz + "Hz)");
            return;
        }
        if (currentHz >= peak) {
            // Nothing to raise. Deliberately NOT treated as "ours": a 120 Hz app (launcher,
            // games) legitimately starts here, and remembering a lower baseline from a previous
            // app would down-clock it on release.
            savedValue = null;
            DragShareLog.i(TAG, "refresh cap already " + currentHz + "Hz");
            return;
        }
        savedValue = Integer.toString(currentHz);
        RootShell.exec("mkdir -p " + MARKER_DIRECTORY
                + " && echo " + currentHz + " > " + MARKER_PATH);
        lastAssertUptime = android.os.SystemClock.uptimeMillis();
        boolean ok = RootShell.run("settings put secure " + SETTING_KEY + " " + peak);
        DragShareLog.i(TAG, "refresh cap " + currentHz + "Hz -> " + peak + "Hz ok=" + ok);
    }

    /**
     * Re-asserts the raised cap while the drag is still running: HyperOS' own refresh policy
     * rewrites this setting when the foreground app changes, which would otherwise drop the
     * panel back to 60 Hz in the middle of a drag. Runs at most once a second.
     */
    static void keepAlive(Context context) {
        if (context == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (WORKER) {
            if (now - lastKeepAliveSubmitUptime < 900L) {
                return;
            }
            lastKeepAliveSubmitUptime = now;
        }
        Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        WORKER.execute(() -> keepAliveNow(app));
    }

    private static synchronized void keepAliveNow(Context context) {
        if (!active) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - boostedSinceUptime > MAX_HOLD_MS) {
            // A drag that never reports its end would otherwise pin the whole panel to the
            // peak rate long after the finger is gone.
            DragShareLog.w(TAG, "refresh cap held too long; releasing");
            releaseNow();
            return;
        }
        if (now - lastAssertUptime < 1000L) {
            return;
        }
        lastAssertUptime = now;
        int peak = Math.round(DisplayRefreshRate.max(context));
        if (peak > 0) {
            // Touch the marker as a heartbeat: the long-lived helper restores the cap when the
            // heartbeat stops, which covers a portal process that died mid-drag.
            RootShell.run("settings put secure " + SETTING_KEY + " " + peak
                    + " && touch " + MARKER_PATH);
        }
    }

    /**
     * Puts the cap back when a drag stopped heart-beating a while ago (portal killed mid-drag).
     * Runs from the always-on helper process, so it also repairs a cap raised by a previous
     * process that never got to release it.
     */
    static void recoverIfStale(long staleAfterMs) {
        String stat = RootShell.exec("stat -c %Y " + MARKER_PATH);
        long modifiedSeconds = parseLong(stat);
        if (modifiedSeconds <= 0L) {
            return;
        }
        long ageMs = System.currentTimeMillis() - modifiedSeconds * 1000L;
        if (ageMs < staleAfterMs) {
            return;
        }
        String marked = readMarker();
        if (marked == null) {
            return;
        }
        boolean ok = RootShell.run("settings put secure " + SETTING_KEY + " " + marked);
        RootShell.run("rm -f " + MARKER_PATH);
        DragShareLog.i(TAG, "stale refresh cap " + marked + "Hz restored after " + ageMs
                + "ms ok=" + ok);
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    /** Puts the previous cap back; safe to call when nothing was raised. */
    static void release() {
        WORKER.execute(RefreshRateOverride::releaseNow);
    }

    private static synchronized void releaseNow() {
        if (!active) {
            // A release can arrive after the process already lost its in-memory state (the
            // controller and the override live in different processes). The marker still
            // knows the original cap, so never leave the panel pinned to the peak.
            restoreFromMarker("late release");
            return;
        }
        active = false;
        String restore = savedValue;
        savedValue = null;
        if (restore == null) {
            restore = readMarker();
        }
        if (restore == null) {
            RootShell.run("rm -f " + MARKER_PATH);
            return;
        }
        boolean ok = RootShell.run("settings put secure " + SETTING_KEY + " " + restore);
        RootShell.run("rm -f " + MARKER_PATH);
        DragShareLog.i(TAG, "refresh cap restored to " + restore + "Hz ok=" + ok);
    }

    private static void restoreFromMarker(String reason) {
        String marked = readMarker();
        if (marked == null) {
            return;
        }
        boolean ok = RootShell.run("settings put secure " + SETTING_KEY + " " + marked);
        RootShell.run("rm -f " + MARKER_PATH);
        DragShareLog.i(TAG, "refresh cap restored to " + marked + "Hz from marker ("
                + reason + ") ok=" + ok);
    }

    private static String readMarker() {
        byte[] marker = RootShell.readFile(MARKER_PATH);
        if (marker == null || marker.length == 0) {
            return null;
        }
        int hz = parseHz(new String(marker).trim());
        return hz > 0 ? Integer.toString(hz) : null;
    }

    /**
     * Repairs a cap that a previous process left raised (crash, force-stop, or an update in the
     * middle of a drag). Called once per process start.
     */
    static void restoreStale() {
        byte[] marker = RootShell.readFile(MARKER_PATH);
        if (marker == null || marker.length == 0) {
            return;
        }
        int staleHz = parseHz(new String(marker).trim());
        RootShell.run("rm -f " + MARKER_PATH);
        if (staleHz <= 0) {
            return;
        }
        boolean ok = RootShell.run("settings put secure " + SETTING_KEY + " " + staleHz);
        DragShareLog.i(TAG, "stale refresh cap restored to " + staleHz + "Hz ok=" + ok);
    }

    private static int parseHz(String value) {
        if (value == null) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException error) {
            DragShareLog.w(TAG, "unexpected " + SETTING_KEY + " value: " + trimmed);
            return 0;
        }
    }
}
