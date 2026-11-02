package com.leaf.hyperdragshare.codex;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Super island ("超级岛") reminder shown while a long press is armed but the finger has not
 * moved yet.
 *
 * <p>HyperOS renders an island for any ordinary notification that carries a JSON string in the
 * {@code miui.focus.param} extra; the island layout comes from {@code param_v2} inside that
 * JSON. Because a plain notification is used, this works from both the portal process and our
 * own accessibility process, and cancelling the notification takes the island down with it.
 */
final class DragReadyIsland {

    private static final String TAG = "DragShare/Island";

    private static final String CHANNEL_ID = "drag_ready";
    private static final String CHANNEL_NAME = "drag ready";
    private static final int NOTIFICATION_ID = 1001;
    /**
     * Two alternating ids: cancelling and re-posting the same notification key while SystemUI is
     * still playing the previous island's exit animation makes the new island shrink the moment
     * it appears (the "second long press" flicker). A fresh key starts a clean island.
     */
    private static final int NOTIFICATION_ID_ALT = NOTIFICATION_ID + 1;
    private static int currentId = NOTIFICATION_ID;

    /** Framework extra that marks a notification as a focus notification. */
    private static final String KEY_FOCUS_PARAM = "miui.focus.param";

    private static final String TITLE = "已就绪";
    private static final String CONTENT = "拖动即可分享";

    /** Broadcast the portal process uses to drive the island owned by the module process. */
    static final String ACTION_ISLAND_BRIDGE = "com.leaf.hyperdragshare.codex.ISLAND";
    /** Sent by the module process when the reminder timed out, so the portal drops the gesture. */
    static final String ACTION_ISLAND_EXPIRED = "com.leaf.hyperdragshare.codex.ISLAND_EXPIRED";
    static final String EXTRA_ISLAND_VISIBLE = "visible";
    /** Provider RPC the portal uses to learn whether the island is really on screen. */
    static final String METHOD_IS_POSTED = "island_posted";
    /** Provider RPC the portal uses to show/withdraw the island in the module process. */
    static final String METHOD_SET_VISIBLE = "island_visible";
    static final String EXTRA_POSTED = "posted";
    /** Uptime at which the island became visible; both processes share this time base. */
    static final String EXTRA_POSTED_UPTIME = "posted_uptime";
    static final String MODULE_PACKAGE = "com.leaf.hyperdragshare.codex";
    static final String PORTAL_PACKAGE = "com.miui.contentextension";

    private static final Object LOCK = new Object();
    private static boolean channelCreated;
    private static boolean posted;
    private static long postedUptime;
    private static String lastParamJson;
    private static boolean failureLogged;

    /**
     * Safety net: a missed ACTION_UP (capture finishing late, input stream restart) must not
     * leave the island on screen forever. The reminder is only useful while the finger is down.
     */
    private static final long MAX_LIFETIME_MS = 6_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context lastContext;
    /**
     * Runs when the reminder expires. The armed gesture is dead at that point: the press must not
     * turn into a share menu if the finger moves afterwards.
     */
    private static volatile Runnable expiryListener;
    private static final Runnable EXPIRE = () -> {
        Context context = lastContext;
        if (context != null) {
            dismiss(context);
        }
        Runnable listener = expiryListener;
        expiryListener = null;
        if (listener != null) {
            try {
                listener.run();
            } catch (Throwable error) {
                DragShareLog.i(TAG, "expiry listener failed: " + error);
            }
        }
        notifyPortalExpired(context);
        DragShareLog.i(TAG, "island expired; armed gesture cancelled");
    };

    /** The portal owns the window for the same gesture, so it has to drop it as well. */
    private static void notifyPortalExpired(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.sendBroadcast(new Intent(ACTION_ISLAND_EXPIRED).setPackage(PORTAL_PACKAGE));
        } catch (Throwable error) {
            DragShareLog.i(TAG, "unable to notify portal expiry: " + error);
        }
    }

    static void setExpiryListener(Runnable listener) {
        expiryListener = listener;
    }

    /**
     * Whether the reminder is actually on screen. {@link #request} only *asks* for the island
     * (the portal process can only broadcast), so this is the only trustworthy answer for the
     * rule "no island, no share window".
     */
    static boolean isPosted() {
        synchronized (LOCK) {
            return posted;
        }
    }

    /** SystemClock.uptimeMillis() when the island went up, or 0 when it is not showing. */
    static long postedUptime() {
        synchronized (LOCK) {
            return posted ? postedUptime : 0L;
        }
    }

    /**
     * Measured latency from notify() to SystemUI adding the island view is 340-470ms. Anything
     * the user does before this moment happens with no reminder on screen at all.
     */
    static final long RENDER_LATENCY_MS = 420L;

    /** Uptime from which the island is actually visible, or 0 when it is not up. */
    static long onScreenUptime() {
        long postedAt = postedUptime();
        return postedAt == 0L ? 0L : postedAt + RENDER_LATENCY_MS;
    }

    private DragReadyIsland() {
    }

    /**
     * Single owner for the island: it always lives in the module process, so whichever process
     * runs the gesture asks for it here. The portal process broadcasts; the module posts.
     */
    static void request(Context context, boolean visible) {
        Context app = appContext(context);
        if (app == null) {
            return;
        }
        if (MODULE_PACKAGE.equals(app.getPackageName())) {
            if (visible) {
                show(app);
            } else {
                dismiss(app);
            }
            return;
        }
        // Direct provider call beats a broadcast: it is synchronous, wakes the module process on
        // demand, and cannot be dropped by background broadcast throttling.
        try {
            Bundle request = new Bundle();
            request.putBoolean(EXTRA_ISLAND_VISIBLE, visible);
            app.getContentResolver().call(ImageStagingClient.BASE_URI, METHOD_SET_VISIBLE, null,
                    request);
            DragShareLog.i(TAG, "island request via provider visible=" + visible);
            return;
        } catch (Throwable error) {
            DragShareLog.i(TAG, "island provider request failed: " + error);
        }
        try {
            Intent intent = new Intent(ACTION_ISLAND_BRIDGE);
            intent.setPackage(MODULE_PACKAGE);
            intent.putExtra(EXTRA_ISLAND_VISIBLE, visible);
            app.sendBroadcast(intent);
            DragShareLog.i(TAG, "island request forwarded visible=" + visible
                    + " from=" + app.getPackageName());
        } catch (Throwable error) {
            DragShareLog.i(TAG, "island request failed: " + error);
        }
    }

    static void show(Context context) {
        show(context, paramJson());
    }

    /** Diagnostic entry point: post the island with an explicit focus param JSON payload. */
    static void show(Context context, String paramJson) {
        Context app = appContext(context);
        if (app == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                NotificationManager manager = app.getSystemService(NotificationManager.class);
                if (manager == null) {
                    return;
                }
                ensureChannel(manager);
                if (posted && paramJson.equals(lastParamJson)) {
                    // Both capture paths (portal pick and accessibility) ask for the reminder for
                    // the same press. Re-notifying would make SystemUI play its update/shrink
                    // animation and reset the visible-since timestamp, which is what made the
                    // island "shrink the moment it appeared" and then block the window.
                    MAIN.removeCallbacks(EXPIRE);
                    MAIN.postDelayed(EXPIRE, MAX_LIFETIME_MS);
                    DragShareLog.i(TAG, "island already visible; lifetime refreshed");
                    return;
                }
                Notification.Builder builder = new Notification.Builder(app, CHANNEL_ID)
                        .setSmallIcon(iconOf(app))
                        .setContentTitle(TITLE)
                        .setContentText(CONTENT)
                        .setOngoing(true)
                        .setShowWhen(false)
                        .setVisibility(Notification.VISIBILITY_PUBLIC);
                Bundle extras = new Bundle();
                extras.putString(KEY_FOCUS_PARAM, paramJson);
                builder.addExtras(extras);
                // Alternate the key so a new press never reuses the key whose exit animation may
                // still be running; the other id is cancelled as a safety net.
                currentId = currentId == NOTIFICATION_ID ? NOTIFICATION_ID_ALT : NOTIFICATION_ID;
                manager.cancel(currentId == NOTIFICATION_ID ? NOTIFICATION_ID_ALT : NOTIFICATION_ID);
                manager.notify(currentId, builder.build());
                posted = true;
                postedUptime = android.os.SystemClock.uptimeMillis();
                lastParamJson = paramJson;
                lastContext = app;
                MAIN.removeCallbacks(EXPIRE);
                MAIN.postDelayed(EXPIRE, MAX_LIFETIME_MS);
                DragShareLog.i(TAG, "island posted pkg=" + app.getPackageName());
            } catch (Throwable error) {
                if (!failureLogged) {
                    failureLogged = true;
                    DragShareLog.i(TAG, "island post failed: " + error);
                }
            }
        }
    }

    static void dismiss(Context context) {
        Context app = appContext(context);
        if (app == null) {
            return;
        }
        synchronized (LOCK) {
            if (!posted) {
                return;
            }
            posted = false;
            postedUptime = 0L;
            lastParamJson = null;
            MAIN.removeCallbacks(EXPIRE);
            lastContext = null;
            try {
                NotificationManager manager = app.getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.cancel(NOTIFICATION_ID);
                    manager.cancel(NOTIFICATION_ID_ALT);
                }
                DragShareLog.i(TAG, "island dismissed");
            } catch (Throwable error) {
                DragShareLog.i(TAG, "island dismiss failed: " + error);
            }
        }
    }

    private static Context appContext(Context context) {
        if (context == null) {
            return null;
        }
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    private static void ensureChannel(NotificationManager manager) {
        if (channelCreated) {
            return;
        }
        channelCreated = true;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static int iconOf(Context context) {
        int icon = context.getApplicationInfo().icon;
        return icon != 0 ? icon : android.R.drawable.ic_menu_share;
    }

    /**
     * Focus-notification payload. The island content is left to the system ("default island
     * data"), which renders the notification icon plus the standard title/text; only the
     * lifetime is controlled here. {@code reopen} has to be explicit, otherwise the island
     * refuses to appear again after a cancel and only the first long press would show it.
     */
    private static String paramJson() {
        String title = escape(TITLE);
        String content = escape(CONTENT);
        return "{"
                + "\"param_v2\":{"
                + "\"protocol\":1,"
                + "\"business\":\"drag_ready\","
                + "\"updatable\":true,"
                + "\"islandFirstFloat\":false,"
                + "\"enableFloat\":false,"
                + "\"timeout\":2,"
                + "\"reopen\":\"reopen\","
                + "\"filterWhenNoPermission\":false,"
                + "\"ticker\":\"" + content + "\","
                + "\"baseInfo\":{\"title\":\"" + title + "\",\"content\":\"" + content
                + "\",\"type\":2}"
                + "}"
                + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
