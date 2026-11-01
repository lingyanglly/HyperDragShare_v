package com.leaf.hyperdragshare.codex;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Portal-side subscriber for {@link RootInputService}. Runs inside the injected portal
 * process, subscribes to the persistent evdev stream and forwards pointer events. Falls
 * back silently (isConnected() stays false) when the helper is unavailable, in which case
 * the caller keeps using its own local evdev pipe.
 */
final class RootEventClient {
    interface Listener {
        void onPointerEvent(int action, float x, float y, long eventTime);

        default void onHelperConnected() {
        }

        default void onHelperDisconnected() {
        }
    }

    private static final String TAG = "DragShare/RootClient";
    private static final String MODULE_PACKAGE = "com.leaf.hyperdragshare.codex";
    private static final String SERVICE_CLASS =
            "com.leaf.hyperdragshare.codex.RootInputService";
    private static final String DESCRIPTOR = "com.leaf.hyperdragshare.codex.RootInputService";
    private static final int TRANSACTION_SUBSCRIBE = 100;
    private static final int MSG_POINTER = 10;
    private static final String EXTRA_ACTION = "action";
    private static final String EXTRA_X = "x";
    private static final String EXTRA_Y = "y";
    private static final String EXTRA_TIME = "time";
    private static final long RECONNECT_DELAY_MS = 800L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile IBinder remote;
    private volatile boolean bound;

    private final Messenger clientMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_POINTER || msg.getData() == null) {
                return;
            }
            Bundle data = msg.getData();
            listener.onPointerEvent(
                    data.getInt(EXTRA_ACTION),
                    data.getFloat(EXTRA_X),
                    data.getFloat(EXTRA_Y),
                    data.getLong(EXTRA_TIME));
        }
    });

    private final ContentObserver settingsObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (!started.get()) {
                return;
            }
            if (RootHelperPrefs.isEnabledForCaller(context)) {
                if (remote == null) {
                    bind();
                }
            } else {
                unbindForDisabledSetting();
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!subscribe(binder)) {
                remote = null;
                scheduleReconnect();
                return;
            }
            remote = binder;
            DragShareLog.i(TAG, "subscribed to root helper");
            listener.onHelperConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            DragShareLog.i(TAG, "helper disconnected");
            listener.onHelperDisconnected();
            scheduleReconnect();
        }
    };

    RootEventClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isConnected() {
        return remote != null;
    }

    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            context.getContentResolver().registerContentObserver(
                    DragShareSettings.settingsUri(), false, settingsObserver);
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to observe the root helper setting", error);
        }
        bind();
    }

    void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        mainHandler.removeCallbacksAndMessages(null);
        try {
            context.getContentResolver().unregisterContentObserver(settingsObserver);
        } catch (Throwable ignored) {
            // Process teardown may already have detached the resolver.
        }
        remote = null;
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (Throwable ignored) {
                // Service may already be gone.
            }
            bound = false;
        }
    }

    /** Disabled from the settings page: drop the connection and let the local pipe take over. */
    private void unbindForDisabledSetting() {
        DragShareLog.i(TAG, "root helper disabled; unbinding");
        mainHandler.removeCallbacks(reconnectRunnable);
        remote = null;
        if (!bound) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (Throwable ignored) {
            // Service may already be gone.
        }
        bound = false;
        listener.onHelperDisconnected();
    }

    private boolean subscribe(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(clientMessenger.getBinder());
            return binder.transact(TRANSACTION_SUBSCRIBE, data, reply, 0);
        } catch (RemoteException | RuntimeException error) {
            DragShareLog.w(TAG, "subscribe failed", error);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void bind() {
        if (!RootHelperPrefs.isEnabledForCaller(context)) {
            DragShareLog.i(TAG, "root helper disabled in settings; not binding");
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MODULE_PACKAGE, SERVICE_CLASS));
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                scheduleReconnect();
            }
        } catch (Throwable error) {
            DragShareLog.w(TAG, "bind failed", error);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!started.get()) {
            return;
        }
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (started.get() && remote == null) {
                bind();
            }
        }
    };
}
