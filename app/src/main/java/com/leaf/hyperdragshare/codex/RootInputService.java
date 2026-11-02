package com.leaf.hyperdragshare.codex;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent root input helper. Owns the evdev pipe in the module process so the portal
 * can subscribe to an already-warm touch stream instead of spawning {@code su} itself.
 *
 * <p>Security: binding is open to any caller (the portal cannot request a custom
 * signature permission because it is a system-signed app), so the UID gate lives in
 * {@link #binder}'s {@code onTransact()}. That is the only place where
 * {@link Binder#getCallingUid()} still reports the remote caller; {@code onBind()}
 * runs on a plain main-thread message and would only see this process. Only the
 * subscribe transaction is exposed, never a generic shell.
 */
public final class RootInputService extends Service {
    static final int MSG_POINTER = 10;
    static final String EXTRA_ACTION = "action";
    static final String EXTRA_X = "x";
    static final String EXTRA_Y = "y";
    static final String EXTRA_TIME = "time";

    /** In-process consumer of the same evdev stream (e.g. the accessibility runtime). */
    interface PointerListener {
        void onPointer(int action, float x, float y, long eventTime);
    }

    private static final java.util.Set<PointerListener> PROCESS_LISTENERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean running;

    private static final String TAG = "DragShare/RootService";
    private static final String CHANNEL_ID = "root_input";
    private static final int NOTIFICATION_ID = 0x5244;
    private static final String PORTAL_PACKAGE = "com.miui.contentextension";
    private static final String DESCRIPTOR = "com.leaf.hyperdragshare.codex.RootInputService";
    private static final int TRANSACTION_SUBSCRIBE = 100;
    /**
     * Must stay in sync with {@code PortalHooks.SERVICE_CLASS}; used only to restart the
     * portal from the wake kick when the process is gone, and failures are tolerated.
     */
    private static final String PORTAL_SERVICE_CLASS =
            "com.miui.contentextension.services.TextContentExtensionService";
    private static final String WAKE_KICK_THREAD = "drag-share-wake-kick";
    private static final long WAKE_KICK_TIMEOUT_MS = 3_000L;
    /** Only dedupes double-DOWNs inside a single gesture. */
    private static final long WAKE_KICK_GATE_MS = 2_000L;
    /**
     * HyperOS applies the app's frame-rate vote on every touch down, so a drag inside a
     * 60 Hz app always renders at 60 regardless of the overlay's own vote. While a long
     * press is held, the module temporarily raises {@code miui_refresh_rate} (the knob
     * MIUI's Mirim policy reads for the base mode) to the user's preferred rate and
     * restores it shortly after the finger lifts. Taps never reach the long-press
     * threshold, so normal browsing keeps the user's original rate.
     */
    private static final long REFRESH_LONG_PRESS_MS = 450L;
    private static final long REFRESH_OVERRIDE_RESTORE_MS = 2_000L;
    private static final int HEARTBEAT_REQUEST_CODE = 1;
    private static final long HEARTBEAT_INTERVAL_MS = 15 * 60_000L;
    private static final long REFRESH_WATCHDOG_INTERVAL_MS = 2_000L;
    private static final long REFRESH_WATCHDOG_STALE_MS = 4_000L;
    /** The module's own accessibility service, granted through root so the user never has to. */
    private static final String ACCESSIBILITY_COMPONENT =
            "com.leaf.hyperdragshare.codex/"
                    + "com.leaf.hyperdragshare.codex.DragShareAccessibilityService";

    private final List<Messenger> subscribers = new ArrayList<>();
    private final AtomicInteger frames = new AtomicInteger();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RootTouchSource source;
    private volatile int lastAction = -1;
    private volatile float lastX;
    private volatile float lastY;
    private volatile long lastEventTime;
    private volatile int lastDeliveredAction = -1;
    private volatile long lastWakeKickUptime;
    private volatile String refreshOriginal;
    private volatile String refreshPreferred;
    private volatile String refreshApplied;
    private volatile boolean refreshProbeScheduled;
    private volatile boolean refreshOverrideEnabled;
    private volatile long downStartUptime;
    private volatile boolean refreshRaisedThisGesture;

    private final Runnable restoreRefreshRunnable = () -> {
        if (!refreshOverrideEnabled) {
            return;
        }
        refreshOverrideEnabled = false;
        String original = refreshOriginal;
        String applied = refreshApplied;
        if (original == null || original.equals(applied)) {
            return;
        }
        Thread thread = new Thread(() -> {
            runRootCommand("settings put secure miui_refresh_rate " + original,
                    "restore miui_refresh_rate");
            refreshApplied = original;
        }, "drag-share-refresh-restore");
        thread.setDaemon(true);
        thread.start();
    };

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != TRANSACTION_SUBSCRIBE) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(DESCRIPTOR);
            int uid = Binder.getCallingUid();
            if (!isAllowedCaller(uid)) {
                DragShareLog.w(TAG, "subscribe rejected uid=" + uid);
                return false;
            }
            IBinder target = data.readStrongBinder();
            if (target == null) {
                return true;
            }
            Messenger client = new Messenger(target);
            synchronized (subscribers) {
                subscribers.add(client);
            }
            DragShareLog.i(TAG, "subscriber added total=" + subscribers.size());
            replayCurrentPointer(client);
            return true;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to promote to foreground", error);
        }
        if (source == null) {
            source = new RootTouchSource(this, this::onPointer);
            source.start();
            DragShareLog.i(TAG, "helper started");
        }
        scheduleHeartbeat();
        startRefreshWatchdog();
        probeRefreshSettings();
        warmPortalIfCold();
        ensureAccessibilityServiceEnabled();
        ensureNotificationPermission();
    }

    /**
     * The armed-drag reminder is a normal notification, so Android 13+ needs the runtime
     * permission before it can reach the island. Granting it through root keeps the hint
     * working even when the user never opens the settings screen.
     */
    private void ensureNotificationPermission() {
        Thread thread = new Thread(() -> runRootCommand(
                "pm grant " + getPackageName() + " android.permission.POST_NOTIFICATIONS",
                "grant notification permission"), "drag-share-grant-notifications");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Adds this module's accessibility service to the secure setting through root, keeping
     * the user's other accessibility services intact. Re-running is a no-op, and an update
     * install (which makes Android drop the service) is repaired on the next app start.
     */
    private void ensureAccessibilityServiceEnabled() {
        Thread thread = new Thread(() -> {
            // The system stores this service either as "pkg/.Service" or with the fully
            // qualified class, so dedupe by package instead of comparing one exact string;
            // otherwise every start appends another entry to the setting.
            String script =
                    "cur=$(settings get secure enabled_accessibility_services); "
                            + "[ \"$cur\" = \"null\" ] && cur=\"\"; "
                            + "out=\"\"; "
                            + "oldifs=$IFS; IFS=:; "
                            + "for e in $cur; do "
                            + "case \"$e\" in "
                            + "com.leaf.hyperdragshare.codex/*) continue ;; "
                            + "esac; "
                            + "if [ -z \"$out\" ]; then out=\"$e\"; else out=\"$out:$e\"; fi; "
                            + "done; "
                            + "IFS=$oldifs; "
                            + "if [ -z \"$out\" ]; then out=\"" + ACCESSIBILITY_COMPONENT + "\"; "
                            + "else out=\"$out:" + ACCESSIBILITY_COMPONENT + "\"; fi; "
                            + "settings put secure enabled_accessibility_services \"$out\"; "
                            + "settings put secure accessibility_enabled 1; echo \"$out\"";
            runRootCommand(script, "enable accessibility service");
        }, "drag-share-enable-accessibility");
        thread.setDaemon(true);
        thread.start();
    }

    /** {@code true} while the helper owns the evdev pipe in this process. */
    static boolean isRunning() {
        return running;
    }

    /** Subscribes an in-process consumer; returns false when the helper is not running. */
    static boolean addPointerListener(PointerListener listener) {
        if (!running || listener == null) {
            return false;
        }
        PROCESS_LISTENERS.add(listener);
        return true;
    }

    static void removePointerListener(PointerListener listener) {
        if (listener != null) {
            PROCESS_LISTENERS.remove(listener);
        }
    }

    /**
     * The slowest first drag is the double-cold one: both this helper and the portal are
     * dead, so nothing can kick the portal at touch down and the system only starts it
     * half a second into the long press. Whenever this service comes up — boot, heartbeat
     * alarm, bind from the portal, app open — also bring the portal up, so after any
     * interaction cycle both processes are warm again.
     */
    private void warmPortalIfCold() {
        Thread thread = new Thread(this::runPortalWakeKick, WAKE_KICK_THREAD);
        thread.setDaemon(true);
        thread.start();
    }

    private void onPointer(int action, float x, float y, long eventTime) {
        lastAction = action;
        lastX = x;
        lastY = y;
        lastEventTime = eventTime;
        long nowUptime = SystemClock.uptimeMillis();
        int count = frames.incrementAndGet();
        if (count % 100 == 1) {
            DragShareLog.i(TAG, "frames=" + count
                    + " subscribers=" + subscribers.size());
        }
        boolean firstFrameOfGesture = action == MotionEvent.ACTION_DOWN
                && lastDeliveredAction != MotionEvent.ACTION_DOWN;
        if (firstFrameOfGesture) {
            // The portal may be frozen or dead after idle; one-way frames alone can take
            // seconds to wake it. Kick every gesture's first DOWN asynchronously so the
            // evdev pump never blocks and frames keep flowing in order.
            maybeKickPortalWake(nowUptime);
            // A new gesture cancels a pending restore and arms the long-press detector.
            mainHandler.removeCallbacks(restoreRefreshRunnable);
            downStartUptime = nowUptime;
            refreshRaisedThisGesture = false;
        }
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
                && !refreshRaisedThisGesture
                && nowUptime - downStartUptime >= REFRESH_LONG_PRESS_MS) {
            refreshRaisedThisGesture = true;
            maybeRaiseRefreshRate();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mainHandler.removeCallbacks(restoreRefreshRunnable);
            mainHandler.postDelayed(restoreRefreshRunnable, REFRESH_OVERRIDE_RESTORE_MS);
        }
        broadcast(pointerMessage(action, x, y, eventTime));
        dispatchToProcessListeners(action, x, y, eventTime);
        lastDeliveredAction = action;
    }

    private static void dispatchToProcessListeners(int action, float x, float y, long eventTime) {
        if (PROCESS_LISTENERS.isEmpty()) {
            return;
        }
        for (PointerListener listener : PROCESS_LISTENERS) {
            try {
                listener.onPointer(action, x, y, eventTime);
            } catch (Throwable error) {
                DragShareLog.w(TAG, "in-process pointer listener failed", error);
            }
        }
    }

    /**
     * Raises {@code miui_refresh_rate} to the user's preferred rate until the finger has
     * been up for a while. The values are probed once per process; the write is skipped
     * when the user's global selection already matches the preferred rate.
     */
    private void maybeRaiseRefreshRate() {
        String preferred = refreshPreferred;
        String original = refreshOriginal;
        if (preferred == null || original == null || preferred.equals(original)) {
            return;
        }
        if (preferred.equals(refreshApplied)) {
            refreshOverrideEnabled = true;
            return;
        }
        String target = preferred;
        Thread thread = new Thread(() -> {
            runRootCommand("settings put secure miui_refresh_rate " + target,
                    "raise miui_refresh_rate");
            refreshApplied = target;
            refreshOverrideEnabled = true;
        }, "drag-share-refresh-override");
        thread.setDaemon(true);
        thread.start();
    }

    private void probeRefreshSettings() {
        if (refreshProbeScheduled) {
            return;
        }
        refreshProbeScheduled = true;
        Thread thread = new Thread(() -> {
            try {
                java.lang.Process process = new ProcessBuilder(
                        "su", "-c",
                        "settings get system user_refresh_rate;"
                                + " settings get secure miui_refresh_rate")
                        .redirectErrorStream(true)
                        .start();
                // Wait first so a stuck su cannot hold the probe thread; the output is two
                // short lines, so the pipe buffer cannot fill in that time.
                boolean finished = process.waitFor(WAKE_KICK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroy();
                    DragShareLog.w(TAG, "refresh probe timed out");
                    return;
                }
                String output = readBounded(process);
                String[] lines = output.split("\\r?\\n");
                String preferred = firstNonEmpty(lines, 0);
                String original = firstNonEmpty(lines, 1);
                if (preferred == null || preferred.equals("null")) {
                    preferred = "120";
                }
                refreshPreferred = preferred;
                refreshOriginal = original;
                DragShareLog.i(TAG, "refresh probe preferred=" + preferred
                        + " original=" + original);
            } catch (Throwable error) {
                DragShareLog.w(TAG, "refresh probe failed", error);
            }
        }, "drag-share-refresh-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private String firstNonEmpty(String[] lines, int startIndex) {
        for (int i = startIndex; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }

    /**
     * HyperOS may kill this helper while it is idle, which would cost the first drag
     * seconds. A self-rescheduling wakeup alarm resurrects the service shortly after
     * every death; each start reschedules the next one.
     */
    /**
     * A drag raises the panel's refresh cap and lowers it again when it ends. If the portal
     * process dies in the middle of a drag that release never happens, so this always-on helper
     * watches the cap marker's heartbeat and puts the original value back.
     */
    private void startRefreshWatchdog() {
        Thread watchdog = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(REFRESH_WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                try {
                    RefreshRateOverride.recoverIfStale(REFRESH_WATCHDOG_STALE_MS);
                } catch (Throwable error) {
                    DragShareLog.w(TAG, "refresh watchdog failed", error);
                }
            }
        }, "hds-refresh-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void scheduleHeartbeat() {
        if (!RootHelperPrefs.isEnabled(this)) {
            return;
        }
        try {
            AlarmManager alarmManager = getSystemService(AlarmManager.class);
            PendingIntent pending = PendingIntent.getService(
                    this,
                    HEARTBEAT_REQUEST_CODE,
                    new Intent(this, RootInputService.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS,
                    pending);
            DragShareLog.d(TAG, "heartbeat scheduled");
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to schedule heartbeat", error);
        }
    }

    private void runRootCommand(String command, String reason) {
        try {
            java.lang.Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(WAKE_KICK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                DragShareLog.w(TAG, reason + " timed out");
                return;
            }
            String output = readBounded(process);
            DragShareLog.i(TAG, reason + " exit=" + process.exitValue()
                    + " output=" + output.trim());
        } catch (Throwable error) {
            DragShareLog.w(TAG, reason + " failed", error);
        }
    }

    /**
     * The portal process can be killed by the system after idle, and one-way frames alone
     * do not wake it up quickly enough (the first drag then takes seconds). Every gesture's
     * first DOWN kicks a root command in the background that starts the portal service when
     * {@code pidof} reports the process as gone, so it is warm by the end of the long press.
     * A two-second gate only dedupes double-DOWNs inside one gesture; the kick is cheap
     * enough to run on every gesture.
     */
    private void maybeKickPortalWake(long nowUptime) {
        if (nowUptime - lastWakeKickUptime < WAKE_KICK_GATE_MS) {
            return;
        }
        lastWakeKickUptime = nowUptime;
        DragShareLog.i(TAG, "scheduling portal wake kick");
        Thread thread = new Thread(this::runPortalWakeKick, WAKE_KICK_THREAD);
        thread.setDaemon(true);
        thread.start();
    }

    private void runPortalWakeKick() {
        // am rejects background-app start-service with "app is in background uid null";
        // start-foreground-service and the explicit --user both bypass that gate.
        String service = PORTAL_PACKAGE + "/" + PORTAL_SERVICE_CLASS;
        String command = "p=$(pidof " + PORTAL_PACKAGE + "); "
                + "if [ -z \"$p\" ]; then am start-service -n " + service
                + " --user 0 || am start-foreground-service -n " + service
                + " --user 0; fi";
        runRootCommand(command, "portal wake kick");
    }

    private String readBounded(java.lang.Process process) {
        StringBuilder output = new StringBuilder();
        try (InputStream stream = process.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 512) {
                    output.append(line).append('\n');
                }
            }
        } catch (Throwable ignored) {
            // Output is only diagnostics.
        }
        return output.toString();
    }

    private void broadcast(Message message) {
        List<Messenger> dead = null;
        synchronized (subscribers) {
            for (Messenger client : subscribers) {
                try {
                    client.send(message);
                } catch (RemoteException error) {
                    if (dead == null) {
                        dead = new ArrayList<>();
                    }
                    dead.add(client);
                }
            }
            if (dead != null) {
                subscribers.removeAll(dead);
            }
        }
    }

    /**
     * A subscriber that joins mid-gesture cannot wait for the next evdev frame: the finger
     * may stay stationary for the whole long press. Replaying the last DOWN/MOVE gives the
     * portal an input frame immediately, which is what the cold-start takeover depends on.
     */
    private void replayCurrentPointer(Messenger client) {
        int action = lastAction;
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
            return;
        }
        try {
            client.send(pointerMessage(action, lastX, lastY, lastEventTime));
            DragShareLog.i(TAG, "replayed active pointer to new subscriber action="
                    + MotionEvent.actionToString(action));
        } catch (RemoteException error) {
            synchronized (subscribers) {
                subscribers.remove(client);
            }
            DragShareLog.w(TAG, "unable to replay active pointer", error);
        }
    }

    private Message pointerMessage(int action, float x, float y, long eventTime) {
        Message message = Message.obtain(null, MSG_POINTER);
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, action);
        bundle.putFloat(EXTRA_X, x);
        bundle.putFloat(EXTRA_Y, y);
        bundle.putLong(EXTRA_TIME, eventTime);
        message.setData(bundle);
        return message;
    }

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "常驻输入",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("保持触摸设备读取，启动拖拽无需等待 root");
            manager.createNotificationChannel(channel);
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HyperDragShare 常驻输入")
                .setContentText("触摸事件已常驻读取")
                .setSmallIcon(R.drawable.ic_share)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RootHelperPrefs.isEnabled(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        scheduleHeartbeat();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        PROCESS_LISTENERS.clear();
        mainHandler.removeCallbacksAndMessages(null);
        if (source != null) {
            source.stop();
            source = null;
            DragShareLog.i(TAG, "helper stopped after frames=" + frames.get());
        }
        String original = refreshOriginal;
        String applied = refreshApplied;
        if (original != null && applied != null && !original.equals(applied)) {
            Thread thread = new Thread(() -> runRootCommand(
                    "settings put secure miui_refresh_rate " + original,
                    "restore miui_refresh_rate on destroy"), "drag-share-refresh-restore");
            thread.setDaemon(true);
            thread.start();
        }
        synchronized (subscribers) {
            subscribers.clear();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // No UID check here: onBind() is dispatched from a main-thread message, not from
        // the caller's binder transaction. The subscribe gate is enforced in onTransact().
        return binder;
    }

    private boolean isAllowedCaller(int uid) {
        if (uid == android.os.Process.myUid()) {
            return true;
        }
        try {
            int portalUid = getPackageManager().getPackageUid(PORTAL_PACKAGE, 0);
            return uid == portalUid;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }
}
