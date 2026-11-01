package com.leaf.hyperdragshare.codex;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class PortalHooks {
    private static final String TAG = "DragShare/Taplus";
    private static final String SERVICE_CLASS =
            "com.miui.contentextension.services.TextContentExtensionService";
    private static final String CALLBACK_CLASS = SERVICE_CLASS + "$1";
    private static final String BASE_FLOAT_CLASS =
            "com.miui.contentextension.text.floatview.BaseFloatView";
    private static final int SPLASH_HIDE_MAX_ATTEMPTS = 4;
    private static final long SPLASH_HIDE_RETRY_MS = 150L;
    private static final String MOTION_KEY = "MotionEvent";
    private static final String CONTROL_KEY = "observe_control_event";
    private static final Object DEFERRED_HOST_LOCK = new Object();
    private static final ArrayDeque<DeferredHostCall> DEFERRED_HOST_CALLS = new ArrayDeque<>();
    private static final ThreadLocal<Boolean> REPLAYING_HOST_CALL = new ThreadLocal<>();

    private static final Set<Class<?>> HOOKED_CALLBACK_CLASSES = Collections.newSetFromMap(
            new ConcurrentHashMap<>());

    @SuppressLint("StaticFieldLeak")
    private static volatile DragShareController controller;
    private static volatile Object portalService;
    private static volatile MiuiMotionSource motionSource;
    private static volatile RootTouchSource rootTouchSource;
    private static volatile RootEventClient rootEventClient;
    private static volatile boolean portalRuntimeActive;
    private static volatile boolean pendingServiceStop;
    private static volatile boolean rootAuthorityLogged;
    private static volatile long lastRootEventUptime;
    private static volatile boolean rootSilenceFallbackLogged;
    /**
     * A ready root source can still be silent: the {@code su} grant or the evdev pipe may
     * only start delivering after the finger is already down. When MIUI motion events keep
     * arriving and root has produced nothing for this long, trust MIUI for that gesture.
     */
    private static final long ROOT_SILENCE_FALLBACK_MS = 400L;
    /** Wait this long for a helper frame before spawning the local pipe. */
    private static final long ROOT_SILENCE_LOCAL_START_MS = 300L;
    private static volatile boolean silenceFallbackStartScheduled;
    private static volatile boolean hostCancelIgnoredLogged;
    private static volatile boolean controlIgnoredLogged;
    private static volatile boolean activationReported;
    private static volatile Context portalContext;
    private static volatile ClassLoader portalClassLoader;
    private static volatile ContentObserver settingsObserver;
    private static volatile String xposedBridgeVersion = "unavailable";

    private PortalHooks() {}

    static void install(ClassLoader classLoader) {
        try {
            portalClassLoader = classLoader;
            xposedBridgeVersion = readXposedBridgeVersion();
            Class<?> serviceClass = XposedHelpers.findClass(SERVICE_CLASS, classLoader);
            suppressOriginalFloatWindows(classLoader);
            hookServiceLifecycle(serviceClass, classLoader);
            hookShareStart(serviceClass);
            hookKnownCallbackClass(classLoader);
            log("Taplus hooks installed");
        } catch (Throwable error) {
            log("unable to install Taplus hooks", error);
        }
    }

    private static void suppressOriginalFloatWindows(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                BASE_FLOAT_CLASS,
                classLoader,
                "addToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        DragShareController current = controller;
                        if (current != null && current.shouldSuppressPortalHostFloatWindow()) {
                            log("suppressed Taplus float window");
                            param.setResult(null);
                        }
                    }
                });
    }

    private static void hookServiceLifecycle(Class<?> serviceClass, ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(serviceClass, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = ((Context) param.thisObject).getApplicationContext();
                portalContext = context;
                portalService = param.thisObject;
                portalClassLoader = classLoader;
                logPortalVersion(context);
                DragShareLog.configure(DragShareSettings.readFromProvider(context));
                DragShareDiagnostics.captureRuntimeOnce(
                        context,
                        "portal service created",
                        xposedBridgeVersion);
                activationReported = reportPortalActivation(context);
                registerSettingsObserver(context);
                applyPortalRuntime();
                hookCallbackFromService(param.thisObject);
            }
        });

        XposedHelpers.findAndHookMethod(serviceClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                unregisterSettingsObserver();
                stopPortalRuntime(false);
                portalContext = null;
            }
        });

        XposedHelpers.findAndHookMethod(
                serviceClass,
                "onStartCommand",
                Intent.class,
                int.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context context = ((Context) param.thisObject).getApplicationContext();
                        activationReported = reportPortalActivation(context)
                                || activationReported;
                    }
                });

        XposedHelpers.findAndHookMethod(serviceClass, "cancelTask", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isReplayingHostCall()) {
                    return;
                }
                DragShareController current = controller;
                if (current != null) {
                    if (deferHostCallIfRootDragActive(param, "cancelTask")) {
                        // Let the root ACTION_UP replay the original method after the
                        // physical gesture has ended. Running it now poisons Taplus'
                        // task state and prevents the next long press in the same app.
                        param.setResult(null);
                        if (!hostCancelIgnoredLogged) {
                            hostCancelIgnoredLogged = true;
                            log("ignoring host cancel while root drag is active");
                        }
                    } else {
                        current.onHostTaskCancelled();
                    }
                }
            }
        });
        try {
            XC_MethodHook stopSelfHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    DragShareController current = controller;
                    if (current != null && current.hasOpenSegmentation()) {
                        pendingServiceStop = true;
                        portalService = param.thisObject;
                        log("deferred portal stopSelf while segmentation is open");
                        param.setResult(null);
                    }
                }
            };
            // stopSelf is declared on android.app.Service; findAndHookMethod only looks at
            // the concrete class, so resolve the inherited methods explicitly.
            Method stopSelf = android.app.Service.class.getMethod("stopSelf");
            Method stopSelfWithId = android.app.Service.class.getMethod("stopSelf", int.class);
            XposedBridge.hookMethod(stopSelf, stopSelfHook);
            XposedBridge.hookMethod(stopSelfWithId, stopSelfHook);
        } catch (Throwable error) {
            log("unable to hook stopSelf", error);
        }
    }

    private static void hookShareStart(Class<?> serviceClass) {
        XC_MethodHook showHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                portalService = param.thisObject;
                if (!activationReported) {
                    Context context = ((Context) param.thisObject).getApplicationContext();
                    activationReported = reportPortalActivation(context);
                }
                Context context = ((Context) param.thisObject).getApplicationContext();
                DragShareSettings settings = DragShareSettings.readFromProvider(context);
                if (!settings.isPortalCaptureMode()) {
                    return;
                }
                applyPortalRuntime();
                DragShareController current = controller;
                if (current != null) {
                    CapturedContent content = PortalContentCaptureSource.capture(param.thisObject);
                    if (content == null) {
                        return;
                    }
                    PointF point = PortalContentCaptureSource.initialPoint(
                            param.thisObject,
                            current.latestPointerX(),
                            current.latestPointerY());
                    current.reservePortalHostFloatWindowSuppression();
                    current.show(
                            content,
                            point == null ? -1f : point.x,
                            point == null ? -1f : point.y,
                            settings);
                }
            }
        };
        XposedHelpers.findAndHookMethod(serviceClass, "startPickTextTask", showHook);
        XposedHelpers.findAndHookMethod(serviceClass, "startPickImageTask", showHook);
    }

    private static void hookKnownCallbackClass(ClassLoader classLoader) {
        Class<?> callbackClass = XposedHelpers.findClassIfExists(CALLBACK_CLASS, classLoader);
        if (callbackClass != null) {
            hookCallbackClass(callbackClass);
        }
    }

    private static void hookCallbackFromService(Object service) {
        try {
            Object callback = XposedHelpers.getObjectField(service, "mCallback");
            if (callback != null) {
                hookCallbackClass(callback.getClass());
            }
        } catch (Throwable error) {
            log("unable to discover Taplus callback", error);
        }
    }

    private static void hookCallbackClass(Class<?> callbackClass) {
        if (!HOOKED_CALLBACK_CLASSES.add(callbackClass)) {
            return;
        }

        try {
            Method target = null;
            for (Method method : callbackClass.getDeclaredMethods()) {
                if ("onContentReceived".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException(callbackClass.getName() + ".onContentReceived");
            }
            target.setAccessible(true);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    handleCallback(param);
                }
            });
            log("hooked callback " + callbackClass.getName());
        } catch (Throwable error) {
            HOOKED_CALLBACK_CLASSES.remove(callbackClass);
            log("unable to hook Taplus callback", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleCallback(XC_MethodHook.MethodHookParam param) {
        try {
            if (isReplayingHostCall()) {
                return;
            }
            Map<String, Object> properties = (Map<String, Object>)
                    XposedHelpers.callMethod(param.args[0], "getPropertyMap");
            if (properties == null) {
                return;
            }

            Object motion = properties.get(MOTION_KEY);
            DragShareLog.d(TAG, "portal callback keys=" + properties.keySet()
                    + " motion=" + (motion instanceof MotionEvent)
                    + " control=" + String.valueOf(properties.get(CONTROL_KEY)));
            if (motion instanceof MotionEvent) {
                dispatchMotion(MotionEvent.obtain((MotionEvent) motion));
                // A motion-only result is not valid Taplus pick content.
                param.setResult(null);
                return;
            }

            Object control = properties.get(CONTROL_KEY);
            if ("258".equals(control)) {
                // Taplus normally interprets a move as cancellation.
                param.setResult(null);
            } else if ("257".equals(control)) {
                DragShareController current = controller;
                if (deferHostCallIfRootDragActive(param, "control-257")) {
                    if (!controlIgnoredLogged) {
                        controlIgnoredLogged = true;
                        log("ignoring host finish; waiting for root ACTION_UP");
                    }
                    param.setResult(null);
                } else {
                    if (current != null) {
                        current.finishFromControlEvent();
                    }
                }
            } else {
                // A result without motion and without a control event is pick content.
                // The host only starts the pick while sIsTaskFinished == true (the flag
                // means "ready for the next pick"); a drag taken over by the module can
                // leave it false, in which case the host would swallow this content.
                markHostTaskPickReady();
            }
        } catch (Throwable error) {
            log("callback handling failed", error);
        }
    }

    private static void dispatchMotion(MotionEvent event) {
        if (DragShareLog.isDebugEnabled()) {
            DragShareLog.d(TAG, "MIUI motion action="
                    + MotionEvent.actionToString(event.getActionMasked())
                    + " point=" + Math.round(event.getRawX()) + "," + Math.round(event.getRawY()));
        }
        boolean rootSilent = SystemClock.uptimeMillis() - lastRootEventUptime
                > ROOT_SILENCE_FALLBACK_MS;
        if (hasReadyRootSource() && !rootSilent) {
            event.recycle();
            if (!rootAuthorityLogged) {
                rootAuthorityLogged = true;
                log("root input is authoritative; ignoring MIUI motion events");
            }
            return;
        }
        if (hasReadyRootSource() && rootSilent) {
            if (!rootSilenceFallbackLogged) {
                rootSilenceFallbackLogged = true;
                log("root stream silent for " + ROOT_SILENCE_FALLBACK_MS
                        + "ms; following MIUI motion until root resumes");
            }
            // The helper connection can outlive its evdev pipe (the module's su process is
            // torn down by the ROM while idle). Every gesture after an idle starts with a
            // "silent" root stream even when the helper is healthy, so the local pipe is
            // only spawned after a short grace period in which helper frames would have
            // arrived; a real dead pipe still gets root frames well within the long press.
            scheduleSilenceFallbackStart();
        }
        DragShareController current = controller;
        if (current == null) {
            event.recycle();
            return;
        }
        int action = event.getActionMasked();
        current.noteMiuiMotion();
        current.acceptMotionEvent(
                event,
                action == MotionEvent.ACTION_UP ? PortalHooks::replayDeferredHostCalls : null);
    }

    private static boolean hasReadyRootSource() {
        RootEventClient client = rootEventClient;
        if (client != null && client.isConnected()) {
            return true;
        }
        RootTouchSource source = rootTouchSource;
        return source != null && source.isReady();
    }

    /** Prefer the persistent helper; stop the local pipe once it is proven. */
    private static void settleInputSource() {
        if (!portalRuntimeActive) {
            return;
        }
        RootEventClient client = rootEventClient;
        if (client == null) {
            return;
        }
        if (client.isConnected()) {
            stopLocalRootSource("root helper active; local evdev stopped");
        } else {
            startLocalRootSource("root helper unavailable; staying on local evdev");
        }
    }

    private static synchronized void stopLocalRootSource(String reason) {
        RootTouchSource local = rootTouchSource;
        if (local == null) {
            return;
        }
        rootTouchSource = null;
        local.stop();
        log(reason);
    }

    private static synchronized void startLocalRootSource(String reason) {
        if (rootTouchSource != null || !portalRuntimeActive || portalContext == null) {
            return;
        }
        rootTouchSource = new RootTouchSource(portalContext, PortalHooks::dispatchRootPointer);
        rootTouchSource.start();
        log(reason);
    }

    /**
     * Starts the local evdev pipe only when the helper really stays silent past the grace
     * period. The check re-reads the last root frame time, so a helper frame that arrived
     * in between cancels the start.
     */
    private static void scheduleSilenceFallbackStart() {
        if (silenceFallbackStartScheduled || rootTouchSource != null) {
            return;
        }
        silenceFallbackStartScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            silenceFallbackStartScheduled = false;
            if (!portalRuntimeActive || rootTouchSource != null) {
                return;
            }
            boolean stillSilent = SystemClock.uptimeMillis() - lastRootEventUptime
                    > ROOT_SILENCE_FALLBACK_MS;
            if (stillSilent) {
                startLocalRootSource("root stream silent; starting local evdev as fallback");
            }
        }, ROOT_SILENCE_LOCAL_START_MS);
    }

    /** Helper connected: the local pipe is no longer needed and would duplicate frames. */
    private static void onRootHelperConnected() {
        if (!portalRuntimeActive) {
            return;
        }
        DragShareController current = controller;
        if (current != null) {
            current.noteRootHelperClientPresent(true);
        }
        stopLocalRootSource("root helper connected; local evdev stopped");
    }

    /** Helper died: fall back to the portal-side pipe instead of losing the gesture. */
    private static void onRootHelperDisconnected() {
        if (!portalRuntimeActive) {
            return;
        }
        startLocalRootSource("root helper disconnected; restarting local evdev");
    }

    /** The segmentation overlay closed: let a deferred stopSelf finish the portal task. */
    private static void onSegmentationClosed() {
        if (!pendingServiceStop || !portalRuntimeActive) {
            return;
        }
        pendingServiceStop = false;
        Object service = portalService;
        if (service == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(service, "stopSelf");
            log("portal stopSelf resumed after segmentation closed");
        } catch (Throwable error) {
            log("unable to resume portal stopSelf", error);
        }
    }

    /**
     * Cold start: the portal adds its native SplashFloatView before the first root frame
     * arrives, so adding the overlay alone leaves two previews on screen. Hide the host
     * splash as soon as the overlay takes over; the hide animation schedules cancelTask,
     * which is deferred until the real root UP exactly like the host's own 257.
     */
    private static void hidePortalSplashAfterTakeover() {
        scheduleSplashHide(0);
    }

    /**
     * The "T 文本识别" chip the user sees next to our preview is the splash float window
     * (its drawn rect matches the chip). Hide it with {@code isCancelTask=false}: passing
     * true is the portal's own quit path and cancels the host task, which would tear down
     * the session we are taking over. The big recognition panel is {@code mMainFloatView};
     * hiding that one always schedules cancelTask, so it is intentionally left alone.
     */
    private static void scheduleSplashHide(int attempt) {
        Object service = portalService;
        if (service == null || !portalRuntimeActive) {
            log("splash hide skipped: service=" + (service != null)
                    + " active=" + portalRuntimeActive);
            return;
        }
        Object floatView;
        try {
            floatView = XposedHelpers.getObjectField(service, "mSplashFloatView");
        } catch (Throwable error) {
            log("splash hide failed to read field", error);
            return;
        }
        if (floatView == null) {
            retrySplashHide(attempt, "mSplashFloatView=null");
            return;
        }
        boolean showing;
        try {
            // BaseFloatView.addToWindow() adds mMainView, so the wrapper itself never gets
            // a window token; mIsShowing is the state the portal itself uses.
            showing = XposedHelpers.getBooleanField(floatView, "mIsShowing");
        } catch (Throwable error) {
            showing = true;
        }
        if (!showing) {
            retrySplashHide(attempt, "mIsShowing=false");
            return;
        }
        try {
            // TaplusSplashFloatView.hideSplashFloatView(boolean) is overloaded with a
            // String variant; app-class reflection is allowed and keeps the exact
            // primitive signature instead of relying on Boolean autoboxing.
            Method hide = floatView.getClass().getDeclaredMethod(
                    "hideSplashFloatView", boolean.class);
            hide.setAccessible(true);
            hide.invoke(floatView, false);
            log("hid Taplus splash float after takeover attempt=" + attempt);
        } catch (Throwable error) {
            log("unable to hide Taplus splash float", error);
        }
    }

    private static void retrySplashHide(int attempt, String reason) {
        if (attempt >= SPLASH_HIDE_MAX_ATTEMPTS) {
            log("splash hide skipped: " + reason + " attempts=" + attempt);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> scheduleSplashHide(attempt + 1), SPLASH_HIDE_RETRY_MS);
    }

    private static void logPortalVersion(Context context) {
        try {
            String versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            log("portal package version=" + versionName);
        } catch (Throwable error) {
            log("unable to read portal version", error);
        }
    }

    private static boolean reportPortalActivation(Context context) {
        boolean reported = ModuleActivation.reportInjected(context);
        if (reported) {
            ModuleActivation.probePortalRootAccessAsync(context);
        }
        return reported;
    }

    private static synchronized void applyPortalRuntime() {
        Context context = portalContext;
        ClassLoader classLoader = portalClassLoader;
        if (context == null || classLoader == null) {
            return;
        }
        DragShareSettings settings = DragShareSettings.readFromProvider(context);
        DragShareLog.configure(settings);
        DragShareLog.d(TAG, "portal runtime settings captureMode=" + settings.contentCaptureMode
                + " logLevel=" + settings.logLevel
                + " logDestination=" + settings.logDestination);
        if (!settings.isPortalCaptureMode()) {
            stopPortalRuntime(true);
            return;
        }
        if (controller != null) {
            return;
        }
        rootAuthorityLogged = false;
        rootSilenceFallbackLogged = false;
        silenceFallbackStartScheduled = false;
        lastRootEventUptime = SystemClock.uptimeMillis();
        hostCancelIgnoredLogged = false;
        controlIgnoredLogged = false;
        clearDeferredHostCalls();
        portalRuntimeActive = true;
        controller = new DragShareController(context, OverlayWindowPolicy.portal());
        controller.prewarmTargets();
        motionSource = new MiuiMotionSource(classLoader, PortalHooks::dispatchMotion);
        motionSource.start();
        rootTouchSource = new RootTouchSource(context, PortalHooks::dispatchRootPointer);
        rootTouchSource.start();
        rootEventClient = new RootEventClient(context, new RootEventClient.Listener() {
            @Override
            public void onPointerEvent(int action, float x, float y, long eventTime) {
                dispatchHelperPointer(action, x, y, eventTime);
            }

            @Override
            public void onHelperConnected() {
                PortalHooks.onRootHelperConnected();
            }

            @Override
            public void onHelperDisconnected() {
                PortalHooks.onRootHelperDisconnected();
            }
        });
        rootEventClient.start();
        controller.noteRootHelperClientPresent(true);
        controller.setHostSplashHider(PortalHooks::hidePortalSplashAfterTakeover);
        controller.setSegmentationClosedListener(PortalHooks::onSegmentationClosed);
        new Handler(Looper.getMainLooper()).postDelayed(PortalHooks::settleInputSource, 1200L);
        log("portal runtime started");
    }

    private static synchronized void stopPortalRuntime(boolean replayDeferredCalls) {
        portalRuntimeActive = false;
        pendingServiceStop = false;
        portalService = null;
        if (replayDeferredCalls) {
            replayDeferredHostCalls();
        }
        MiuiMotionSource source = motionSource;
        motionSource = null;
        if (source != null) {
            source.stop();
        }
        RootEventClient client = rootEventClient;
        rootEventClient = null;
        if (client != null) {
            client.stop();
        }
        RootTouchSource rootSource = rootTouchSource;
        rootTouchSource = null;
        if (rootSource != null) {
            rootSource.stop();
        }
        DragShareController current = controller;
        controller = null;
        if (current != null) {
            current.destroy();
        }
        clearDeferredHostCalls();
    }

    private static void registerSettingsObserver(Context context) {
        unregisterSettingsObserver();
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                applyPortalRuntime();
            }
        };
        try {
            context.getContentResolver().registerContentObserver(
                    DragShareSettings.settingsUri(),
                    false,
                    observer);
            settingsObserver = observer;
        } catch (Throwable error) {
            log("unable to observe settings changes", error);
        }
    }

    private static void unregisterSettingsObserver() {
        ContentObserver observer = settingsObserver;
        settingsObserver = null;
        Context context = portalContext;
        if (observer == null || context == null) {
            return;
        }
        try {
            context.getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {
            // The process may be leaving while the resolver is already gone.
        }
    }

    private static void dispatchRootPointer(int action, float x, float y, long eventTime) {
        lastRootEventUptime = SystemClock.uptimeMillis();
        if (DragShareLog.isDebugEnabled()) {
            DragShareLog.d(TAG, "root pointer action=" + MotionEvent.actionToString(action)
                    + " point=" + Math.round(x) + "," + Math.round(y)
                    + " time=" + eventTime);
        }
        DragShareController current = controller;
        if (current != null) {
            current.noteRootPointer();
            current.acceptPointerEvent(
                    action,
                    x,
                    y,
                    eventTime,
                    "root",
                    action == MotionEvent.ACTION_UP
                            ? PortalHooks::replayDeferredHostCalls
                            : null);
        }
    }

    /**
     * A frame from the persistent helper proves its evdev pipe is alive again, so the
     * local silence-fallback pipe can stop before it duplicates frames.
     */
    private static void dispatchHelperPointer(int action, float x, float y, long eventTime) {
        if (rootTouchSource != null) {
            stopLocalRootSource("helper frames resumed; local evdev stopped");
        }
        dispatchRootPointer(action, x, y, eventTime);
    }

    private static boolean deferHostCallIfRootDragActive(
            XC_MethodHook.MethodHookParam param,
            String kind) {
        synchronized (DEFERRED_HOST_LOCK) {
            DragShareController current = controller;
            if (current == null || !current.isActive() || !hasReadyRootSource()) {
                return false;
            }
            for (DeferredHostCall existing : DEFERRED_HOST_CALLS) {
                if (kind.equals(existing.kind)) {
                    return true;
                }
            }
            Object[] args = param.args == null ? new Object[0] : param.args.clone();
            DEFERRED_HOST_CALLS.addLast(
                    new DeferredHostCall(kind, param.method, param.thisObject, args));
        }
        log("deferred host call=" + kind + " until root ACTION_UP");
        return true;
    }

    private static void replayDeferredHostCalls() {
        ArrayList<DeferredHostCall> calls;
        synchronized (DEFERRED_HOST_LOCK) {
            if (DEFERRED_HOST_CALLS.isEmpty()) {
                return;
            }
            calls = new ArrayList<>(DEFERRED_HOST_CALLS);
            DEFERRED_HOST_CALLS.clear();
        }

        REPLAYING_HOST_CALL.set(Boolean.TRUE);
        try {
            for (DeferredHostCall call : calls) {
                try {
                    XposedBridge.invokeOriginalMethod(call.method, call.receiver, call.args);
                    log("replayed host call=" + call.kind);
                } catch (Throwable error) {
                    log("unable to replay host call=" + call.kind, error);
                }
            }
        } finally {
            REPLAYING_HOST_CALL.remove();
        }
    }

    /**
     * Marks the host task finished so its callback starts the next pick. DEX semantics:
     * {@code sIsTaskFinished == true} is what routes an incoming result into the host's
     * own {@code onStartPick} pipeline ({@code false} routes it into 257/258 quit
     * handling). A drag taken over by the module can leave the flag false, in which case
     * the host would swallow the next pick content.
     */
    private static void markHostTaskPickReady() {
        ClassLoader classLoader = portalClassLoader;
        if (classLoader == null) {
            return;
        }
        try {
            Class<?> serviceClass = XposedHelpers.findClass(SERVICE_CLASS, classLoader);
            XposedHelpers.callStaticMethod(serviceClass, "setIsTaskFinished", true);
        } catch (Throwable error) {
            log("unable to mark host task pick-ready", error);
        }
    }

    private static void clearDeferredHostCalls() {
        synchronized (DEFERRED_HOST_LOCK) {
            DEFERRED_HOST_CALLS.clear();
        }
    }

    private static boolean isReplayingHostCall() {
        return Boolean.TRUE.equals(REPLAYING_HOST_CALL.get());
    }

    private static String readXposedBridgeVersion() {
        try {
            Method method = XposedBridge.class.getMethod("getXposedVersion");
            Object value = method.invoke(null);
            return String.valueOf(value);
        } catch (Throwable error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    private static final class DeferredHostCall {
        final String kind;
        final Member method;
        final Object receiver;
        final Object[] args;

        DeferredHostCall(String kind, Member method, Object receiver, Object[] args) {
            this.kind = kind;
            this.method = method;
            this.receiver = receiver;
            this.args = args;
        }
    }

    private static void log(String message) {
        DragShareLog.i(TAG, message);
    }

    private static void log(String message, Throwable error) {
        DragShareLog.w(TAG, message, error);
    }
}
