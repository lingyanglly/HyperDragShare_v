package com.leaf.hyperdragshare.codex;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Accessibility-mode runtime owner. Events only update window state; capture starts after a long press. */
public final class DragShareAccessibilityService extends AccessibilityService {
    private static final String TAG = "DragShare/Accessibility";
    /** Broadcast action that dumps the current window tree to logcat for diagnostics. */
    public static final String ACTION_DIAGNOSTIC_DUMP =
            "com.leaf.hyperdragshare.codex.DIAGNOSTIC_DUMP";
    /** Sent by the portal hook when it captured pick content for the current gesture. */
    public static final String ACTION_PORTAL_PICK =
            "com.leaf.hyperdragshare.codex.PORTAL_PICK";
    public static final String EXTRA_PORTAL_PICK_IMAGE = "image";
    /** Diagnostic broadcasts that post / cancel the armed island without a gesture. */
    public static final String ACTION_ISLAND_PROBE =
            "com.leaf.hyperdragshare.codex.ISLAND_PROBE";
    public static final String ACTION_ISLAND_PROBE_OFF =
            "com.leaf.hyperdragshare.codex.ISLAND_PROBE_OFF";
    /** Diagnostic broadcast that publishes the root-only log file where adb can read it. */
    public static final String ACTION_LOG_COPY =
            "com.leaf.hyperdragshare.codex.LOG_COPY";    /** Accessibility content handed to the portal process for its single overlay. */
    public static final String ACTION_ACCESSIBILITY_CONTENT =
            "com.leaf.hyperdragshare.codex.ACCESSIBILITY_CONTENT";
    private static final String DIAG_TAG = "DragShare/Diag";
    private static final int DIAG_MAX_NODES = 800;
    private static final int MAX_NODE_COUNT = 4_000;
    private static final int MAX_NODE_DEPTH = 64;
    private static final long NODE_BUDGET_MILLIS = 120L;
    private static final long ROOT_RETRY_DELAY_MILLIS = 5_000L;
    private static final int MAX_ROOT_RESTARTS = 3;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable rootReadyReporter = new Runnable() {
        @Override
        public void run() {
            if (runtimeStarted && !rootInputReused && RootInputService.isRunning()
                    && (rootTouchSource == null || !rootTouchSource.isReady())) {
                // The helper came up after the accessibility runtime: move to its stream so
                // the two readers never compete for the same evdev device.
                switchToReusedStream();
            }
            RootTouchSource source = rootTouchSource;
            boolean ready = runtimeStarted && (rootInputReused
                    ? RootInputService.isRunning()
                    : source != null && source.isReady());
            AccessibilityRuntimeStatus.setRootInputReady(ready);
            if (ready != lastReportedRootReady) {
                lastReportedRootReady = ready;
                trace("root ready=" + ready);
            }
            if (!runtimeStarted) {
                return;
            }
            if (ready) {
                rootNotReadySince = 0L;
                rootRestartCount = 0;
            } else {
                long now = SystemClock.uptimeMillis();
                if (rootNotReadySince == 0L) {
                    rootNotReadySince = now;
                } else if (now - rootNotReadySince >= ROOT_RETRY_DELAY_MILLIS
                        && rootRestartCount < MAX_ROOT_RESTARTS
                        && source != null) {
                    rootRestartCount++;
                    rootNotReadySince = now;
                    DragShareLog.w(TAG, "restarting unavailable Root input attempt="
                            + rootRestartCount);
                    source.stop();
                    mainHandler.postDelayed(() -> {
                        if (runtimeStarted && rootTouchSource == source && !source.isReady()) {
                            source.start();
                        }
                    }, 250L);
                }
            }
            mainHandler.postDelayed(this, ready ? 1_000L : 400L);
        }
    };

    private ContentObserver settingsObserver;
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver diagnosticReceiver;
    private BroadcastReceiver portalPickReceiver;
    private volatile long lastPortalPickUptime;
    private volatile boolean screenInteractive = true;
    private long rootNotReadySince;
    private int rootRestartCount;
    private boolean lastReportedRootReady;
    private boolean runtimeStarted;
    private int runtimeLongPressTimeoutMillis = Integer.MIN_VALUE;
    private int runtimeRecognitionSensitivityPercent = Integer.MIN_VALUE;
    private RootTouchSource rootTouchSource;
    private RootInputService.PointerListener reusedPointerListener;
    private volatile boolean rootInputReused;
    private DragShareController controller;
    private AccessibilityContentCaptureSource captureSource;
    private ExecutorService classifierExecutor;
    private AccessibilityScreenshotter screenshotter;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityTrace.reset(this);
        AccessibilityRuntimeStatus.setConnected(true);
        screenInteractive = isScreenInteractive();
        trace("service connected mode="
                + DragShareSettings.readLocal(this).contentCaptureMode
                + " interactive=" + screenInteractive
                + " locked=" + isDeviceLocked()
                + " sdk=" + Build.VERSION.SDK_INT);
        registerSettingsObserver();
        registerScreenReceiver();
        registerDiagnosticReceiver();
        registerPortalPickReceiver();
        applyMode();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately passive: the node tree is only read after a long-press timeout via
        // AccessibilityContentCaptureSource. No state is derived from individual events.
    }

    @Override
    public void onInterrupt() {
        AccessibilityContentCaptureSource source = captureSource;
        if (source != null) {
            source.cancel();
        }
        AccessibilityScreenshotter activeScreenshotter = screenshotter;
        if (activeScreenshotter != null) {
            activeScreenshotter.cancelAll();
        }
    }

    @Override
    public void onDestroy() {
        unregisterSettingsObserver();
        unregisterScreenReceiver();
        unregisterDiagnosticReceiver();
        unregisterPortalPickReceiver();
        stopRuntime();
        AccessibilityRuntimeStatus.setConnected(false);
        super.onDestroy();
    }

    boolean isAccessibilityCaptureEnabled() {
        DragShareSettings settings = DragShareSettings.readLocal(this);
        return isAccessibilityRuntimeEnabled(settings)
                && settings.isAccessibilityRecognitionEnabledForOrientation(
                getResources().getConfiguration().orientation);
    }

    private boolean isAccessibilityRuntimeEnabled() {
        return isAccessibilityRuntimeEnabled(DragShareSettings.readLocal(this));
    }

    private boolean isAccessibilityRuntimeEnabled(DragShareSettings settings) {
        return screenInteractive
                && !isDeviceLocked()
                && settings.isAccessibilityCaptureMode();
    }

    AccessibilityCandidateSelector.Selection selectCandidateAt(float x, float y, long gestureId) {
        if (!isAccessibilityCaptureEnabled()) {
            trace("gesture=" + gestureId + " selection skipped disabled");
            return null;
        }
        DragShareSettings settings = DragShareSettings.readLocal(this);
        List<WindowRoot> roots = rootsAtPoint(x, y, settings);
        trace("gesture=" + gestureId + " roots=" + roots.size()
                + " point=" + Math.round(x) + "," + Math.round(y));
        for (WindowRoot windowRoot : roots) {
            try {
                List<AccessibilityNodeSnapshot> snapshots = snapshotTree(windowRoot.root);
                int[] screen = screenSize();
                AccessibilityNodeClassifier classifier = new AccessibilityNodeClassifier(
                        getResources().getDisplayMetrics().density,
                        screen[0],
                        screen[1]);
                AccessibilityNodeClassifier.Buckets buckets = classifier.classify(snapshots);
                AccessibilityCandidateSelector.Selection selection = AccessibilityCandidateSelector.select(
                        buckets,
                        x,
                        y);
                DragShareLog.i(TAG, "gesture=" + gestureId
                        + " nodes=" + snapshots.size()
                        + " candidates=" + buckets.candidateCount());
                if (selection != null) {
                    if (AccessibilityBlacklist.isBlocked(
                            this,
                            settings,
                            selection.candidate.sourcePackage)) {
                        trace("gesture=" + gestureId + " candidate skipped blacklist");
                        continue;
                    }
                    trace("gesture=" + gestureId + " selected="
                            + selection.candidate.kind
                            + " bounds=" + selection.candidate.bounds.flattenToString());
                    return selection;
                }
            } catch (Throwable error) {
                DragShareLog.w(TAG, "gesture=" + gestureId + " window snapshot failed", error);
            } finally {
                recycleNode(windowRoot.root);
            }
        }
        trace("gesture=" + gestureId + " no candidate");
        return null;
    }

    private void applyMode() {
        if (isAccessibilityRuntimeEnabled()) {
            startRuntime();
        } else {
            stopRuntime();
        }
    }

    private void startRuntime() {
        if (runtimeStarted) {
            return;
        }
        DragShareSettings settings = DragShareSettings.readLocal(this);
        runtimeStarted = true;
        runtimeLongPressTimeoutMillis = settings.accessibilityLongPressTimeoutMillis;
        runtimeRecognitionSensitivityPercent = settings.accessibilityRecognitionSensitivityPercent;
        rootNotReadySince = 0L;
        rootRestartCount = 0;
        lastReportedRootReady = false;
        classifierExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "drag-share-accessibility-classifier");
            thread.setDaemon(true);
            return thread;
        });
        try {
            controller = new DragShareController(
                    this,
                    OverlayWindowPolicy.accessibilityHosted());
            screenshotter = new AccessibilityScreenshotter(this, classifierExecutor);
            // Prefer the helper's already-warm evdev stream: a second RootTouchSource in this
            // same process competes for the device and leaves the accessibility runtime with
            // no frames. Fall back to a local reader only when the helper is not running.
            switchToReusedStream();
            if (!rootInputReused) {
                rootTouchSource = new RootTouchSource(
                        getApplicationContext(),
                        (action, x, y, eventTime) -> {
                            traceRootAction(action, x, y);
                            AccessibilityContentCaptureSource active = captureSource;
                            if (active != null) {
                                active.onPointerEvent(action, x, y, eventTime);
                            }
                        });
            }
            captureSource = new AccessibilityContentCaptureSource(
                    this,
                    controller,
                    () -> rootInputReused
                            ? RootInputService.isRunning()
                            : (rootTouchSource != null && rootTouchSource.isReady()),
                    classifierExecutor,
                    screenshotter);
            if (rootTouchSource != null) {
                rootTouchSource.start();
            }
            mainHandler.removeCallbacks(rootReadyReporter);
            mainHandler.post(rootReadyReporter);
            DragShareLog.i(TAG, "accessibility runtime started");
            trace("runtime started");
        } catch (Throwable error) {
            // A failure here would otherwise bubble out of onServiceConnected, the settings
            // observer or the screen receiver and crash the service process.
            DragShareLog.w(TAG, "unable to start accessibility runtime", error);
            stopRuntime();
        }
    }

    private void traceRootAction(int action, float x, float y) {
        if (action == android.view.MotionEvent.ACTION_DOWN
                || action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            trace("root action=" + android.view.MotionEvent.actionToString(action)
                    + " point=" + Math.round(x) + "," + Math.round(y));
        }
    }

    private void switchToReusedStream() {
        if (rootInputReused) {
            return;
        }
        RootInputService.PointerListener listener = (action, x, y, eventTime) -> {
            traceRootAction(action, x, y);
            AccessibilityContentCaptureSource active = captureSource;
            if (active != null) {
                active.onPointerEvent(action, x, y, eventTime);
            }
        };
        if (!RootInputService.addPointerListener(listener)) {
            return;
        }
        reusedPointerListener = listener;
        rootInputReused = true;
        RootTouchSource local = rootTouchSource;
        rootTouchSource = null;
        if (local != null) {
            local.stop();
        }
        DragShareLog.i(TAG, "switched to helper pointer stream");
        trace("root stream switched to helper");
    }

    private void stopRuntime() {
        if (!runtimeStarted && controller == null && rootTouchSource == null) {
            AccessibilityRuntimeStatus.setRootInputReady(false);
            return;
        }
        runtimeStarted = false;
        runtimeLongPressTimeoutMillis = Integer.MIN_VALUE;
        runtimeRecognitionSensitivityPercent = Integer.MIN_VALUE;
        rootNotReadySince = 0L;
        rootRestartCount = 0;
        lastReportedRootReady = false;
        mainHandler.removeCallbacks(rootReadyReporter);
        AccessibilityRuntimeStatus.setRootInputReady(false);
        AccessibilityContentCaptureSource source = captureSource;
        captureSource = null;
        if (source != null) {
            source.cancel();
        }
        AccessibilityScreenshotter activeScreenshotter = screenshotter;
        screenshotter = null;
        if (activeScreenshotter != null) {
            activeScreenshotter.close();
        }
        RootTouchSource input = rootTouchSource;
        rootTouchSource = null;
        if (input != null) {
            input.stop();
        }
        RootInputService.PointerListener reused = reusedPointerListener;
        reusedPointerListener = null;
        rootInputReused = false;
        if (reused != null) {
            RootInputService.removePointerListener(reused);
        }
        ExecutorService executor = classifierExecutor;
        classifierExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        DragShareController activeController = controller;
        controller = null;
        if (activeController != null) {
            activeController.destroy();
        }
        DragShareLog.i(TAG, "accessibility runtime stopped");
        trace("runtime stopped");
    }

    private List<WindowRoot> rootsAtPoint(
            float x,
            float y,
            DragShareSettings settings) {
        List<WindowRoot> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        AccessibilityNodeInfo activeRoot = null;
        try {
            activeRoot = getRootInActiveWindow();
            addRootIfUsable(result, seen, activeRoot, Integer.MAX_VALUE, settings);
        } catch (Throwable ignored) {
            recycleNode(activeRoot);
        }
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Throwable ignored) {
            windows = null;
        }
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = null;
                try {
                    if (window == null || window.getType()
                            == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        continue;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && window.getDisplayId() != Display.DEFAULT_DISPLAY) {
                        continue;
                    }
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    if (!contains(bounds, x, y)) {
                        continue;
                    }
                    root = window.getRoot();
                    int layer = window.getLayer();
                    if (window.isActive()) layer += 1_000_000;
                    if (window.isFocused()) layer += 100_000;
                    addRootIfUsable(result, seen, root, layer, settings);
                    root = null;
                } catch (Throwable ignored) {
                    // One broken window must not cancel the full capture.
                } finally {
                    recycleNode(root);
                    recycleWindow(window);
                }
            }
        }
        sortWindowRoots(result);
        return result;
    }

    private void addRootIfUsable(
            List<WindowRoot> roots,
            Set<String> seen,
            AccessibilityNodeInfo root,
            int layer,
            DragShareSettings settings) {
        if (root == null) {
            return;
        }
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        String packageName = asString(root.getPackageName());
        // Window bounds were already checked when available. Some ROMs expose an
        // empty or inset root bounds while its descendants still have valid bounds.
        if (getPackageName().equals(packageName)) {
            recycleNode(root);
            return;
        }
        if (AccessibilityBlacklist.isBlocked(this, settings, packageName)) {
            recycleNode(root);
            return;
        }
        String key = packageName + ":" + bounds.flattenToString();
        if (!seen.add(key)) {
            recycleNode(root);
            return;
        }
        roots.add(new WindowRoot(root, layer));
    }

    private static void sortWindowRoots(List<WindowRoot> roots) {
        roots.sort(new Comparator<WindowRoot>() {
            @Override
            public int compare(WindowRoot first, WindowRoot second) {
                return Integer.compare(second.layer, first.layer);
            }
        });
    }

    private List<AccessibilityNodeSnapshot> snapshotTree(AccessibilityNodeInfo root) {
        List<AccessibilityNodeSnapshot> snapshots = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        traverse(root, 0, false, snapshots, budget);
        if (budget.exhausted) {
            DragShareLog.w(TAG, "node traversal budget exhausted nodes=" + budget.nodeCount);
        }
        return snapshots;
    }

    private void traverse(
            AccessibilityNodeInfo node,
            int depth,
            boolean inheritedWebView,
            List<AccessibilityNodeSnapshot> snapshots,
            TraversalBudget budget) {
        if (node == null || budget.exhausted || depth > MAX_NODE_DEPTH) {
            return;
        }
        if (++budget.nodeCount > MAX_NODE_COUNT
                || SystemClock.uptimeMillis() - budget.startedAt > NODE_BUDGET_MILLIS) {
            budget.exhausted = true;
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String className = asString(node.getClassName());
        boolean insideWebView = inheritedWebView || "android.webkit.WebView".equals(className);
        int childCount = node.getChildCount();
        snapshots.add(new AccessibilityNodeSnapshot.Builder()
                .bounds(bounds)
                .packageName(asString(node.getPackageName()))
                .className(className)
                .text(asString(node.getText()))
                .contentDescription(asString(node.getContentDescription()))
                .visible(node.isVisibleToUser())
                .editable(node.isEditable())
                .password(node.isPassword())
                .leaf(childCount == 0)
                .insideWebView(insideWebView)
                .depth(depth)
                .traversalOrder(budget.nodeCount)
                .build());
        for (int index = 0; index < childCount && !budget.exhausted; index++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(index);
                traverse(child, depth + 1, insideWebView, snapshots, budget);
            } finally {
                recycleNode(child);
            }
        }
    }

    private int[] screenSize() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    private void registerSettingsObserver() {
        unregisterSettingsObserver();
        settingsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                DragShareSettings settings = DragShareSettings.readLocal(
                        DragShareAccessibilityService.this);
                if (runtimeStarted && (settings.accessibilityLongPressTimeoutMillis
                        != runtimeLongPressTimeoutMillis
                        || settings.accessibilityRecognitionSensitivityPercent
                        != runtimeRecognitionSensitivityPercent)) {
                    stopRuntime();
                }
                applyMode();
            }
        };
        getContentResolver().registerContentObserver(
                DragShareSettings.settingsUri(),
                false,
                settingsObserver);
    }

    private void unregisterSettingsObserver() {
        if (settingsObserver == null) {
            return;
        }
        try {
            getContentResolver().unregisterContentObserver(settingsObserver);
        } catch (Throwable ignored) {
            // Service teardown can race the resolver.
        }
        settingsObserver = null;
    }

    private void registerScreenReceiver() {
        if (screenReceiver != null) {
            return;
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenInteractive = false;
                    stopRuntime();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)
                        || Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenInteractive = isScreenInteractive();
                    applyMode();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
            // Already unregistered during platform shutdown.
        }
        screenReceiver = null;
    }

    private void registerDiagnosticReceiver() {
        if (diagnosticReceiver != null) {
            return;
        }
        diagnosticReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                if (ACTION_DIAGNOSTIC_DUMP.equals(intent.getAction())) {
                    DragShareLog.enableTemporaryDiagnostics();
                    DragShareLog.i(DIAG_TAG, "diagnostic dump requested");
                    dumpWindowTreeForDiagnostics();
                } else if (ACTION_ISLAND_PROBE.equals(intent.getAction())) {
                    DragShareLog.enableTemporaryDiagnostics();
                    String override = intent.getStringExtra("param");
                    DragShareLog.i(DIAG_TAG, "island probe: show override=" + (override != null));
                    if (override != null && !override.isEmpty()) {
                        DragReadyIsland.show(DragShareAccessibilityService.this, override);
                    } else {
                        DragReadyIsland.show(DragShareAccessibilityService.this);
                    }
                } else if (ACTION_ISLAND_PROBE_OFF.equals(intent.getAction())) {
                    DragShareLog.i(DIAG_TAG, "island probe: dismiss");
                    DragReadyIsland.dismiss(DragShareAccessibilityService.this);
                } else if (ACTION_LOG_COPY.equals(intent.getAction())) {
                    DragShareLog.enableTemporaryDiagnostics();
                    copyDiagnosticLogForAdb();
                } else if (DragReadyIsland.ACTION_ISLAND_BRIDGE.equals(intent.getAction())) {
                    boolean visible = intent.getBooleanExtra(
                            DragReadyIsland.EXTRA_ISLAND_VISIBLE, false);
                    if (visible) {
                        DragReadyIsland.show(DragShareAccessibilityService.this);
                    } else {
                        DragReadyIsland.dismiss(DragShareAccessibilityService.this);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_DIAGNOSTIC_DUMP);
        filter.addAction(ACTION_ISLAND_PROBE);
        filter.addAction(ACTION_ISLAND_PROBE_OFF);
        filter.addAction(ACTION_LOG_COPY);
        filter.addAction(DragReadyIsland.ACTION_ISLAND_BRIDGE);
        try {
            ContextCompat.registerReceiver(
                    this,
                    diagnosticReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED);
        } catch (Throwable error) {
            DragShareLog.w(DIAG_TAG, "unable to register diagnostic receiver", error);
        }
    }

    /** Temporary diagnostic helper: publish the root-only log where adb shell can read it. */
    private void copyDiagnosticLogForAdb() {
        final String target = "/storage/emulated/0/Download/hds.log";
        Thread worker = new Thread(() -> {
            boolean copied = RootShell.run(
                    "cat " + DragShareLog.LOG_FILE_PATH + " > " + target
                            + "; chmod 644 " + target);
            DragShareLog.i(DIAG_TAG, "diagnostic log copy to " + target + " ok=" + copied);
        }, "hds-log-copy");
        worker.setDaemon(true);
        worker.start();
    }

    private void unregisterDiagnosticReceiver() {        if (diagnosticReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(diagnosticReceiver);
        } catch (Throwable ignored) {
            // Already unregistered during platform shutdown.
        }
        diagnosticReceiver = null;
    }

    private void registerPortalPickReceiver() {
        if (portalPickReceiver != null) {
            return;
        }
        portalPickReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_PORTAL_PICK.equals(intent.getAction())) {
                    lastPortalPickUptime = SystemClock.uptimeMillis();
                }
            }
        };
        try {
            ContextCompat.registerReceiver(
                    this,
                    portalPickReceiver,
                    new IntentFilter(ACTION_PORTAL_PICK),
                    ContextCompat.RECEIVER_EXPORTED);
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to register portal pick receiver", error);
        }
    }

    private void unregisterPortalPickReceiver() {
        if (portalPickReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(portalPickReceiver);
        } catch (Throwable ignored) {
            // Already unregistered during platform shutdown.
        }
        portalPickReceiver = null;
    }

    /** {@code true} when the portal captured pick content inside the window. */
    boolean isPortalPickRecent(long windowMillis) {
        long last = lastPortalPickUptime;
        return last > 0L && SystemClock.uptimeMillis() - last <= windowMillis;
    }

    /**
     * Writes every window and node of the current screen to logcat so a particular control
     * (custom-canvas stickers, button drawables) can be identified as a node with bounds.
     * Runs regardless of the capture mode; only reads the tree.
     */
    void dumpWindowTreeForDiagnostics() {
        mainHandler.post(() -> {
            int[] screen = screenSize();
            DragShareLog.i(DIAG_TAG, "===== dump start screen="
                    + screen[0] + "x" + screen[1] + " =====");
            int[] counter = new int[]{0};
            List<AccessibilityWindowInfo> windows = null;
            try {
                windows = getWindows();
            } catch (Throwable error) {
                DragShareLog.w(DIAG_TAG, "getWindows failed", error);
            }
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = null;
                    try {
                        if (window == null || window.getType()
                                == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                            continue;
                        }
                        Rect bounds = new Rect();
                        window.getBoundsInScreen(bounds);
                        DragShareLog.i(DIAG_TAG, "window type=" + window.getType()
                                + " layer=" + window.getLayer()
                                + " active=" + window.isActive()
                                + " focused=" + window.isFocused()
                                + " bounds=" + bounds.flattenToString());
                        root = window.getRoot();
                        if (root != null) {
                            dumpDiagnosticNode(root, 0, counter);
                        }
                    } catch (Throwable error) {
                        DragShareLog.w(DIAG_TAG, "window dump failed", error);
                    } finally {
                        recycleNode(root);
                        recycleWindow(window);
                    }
                }
            }
            AccessibilityNodeInfo activeRoot = null;
            try {
                activeRoot = getRootInActiveWindow();
                if (activeRoot != null) {
                    DragShareLog.i(DIAG_TAG, "active window pkg="
                            + asString(activeRoot.getPackageName()));
                    dumpDiagnosticNode(activeRoot, 0, counter);
                }
            } catch (Throwable error) {
                DragShareLog.w(DIAG_TAG, "active root dump failed", error);
            } finally {
                recycleNode(activeRoot);
            }
            DragShareLog.i(DIAG_TAG, "===== dump end nodes=" + counter[0] + " =====");
        });
    }

    private void dumpDiagnosticNode(AccessibilityNodeInfo node, int depth, int[] counter) {
        if (node == null || counter[0] >= DIAG_MAX_NODES) {
            return;
        }
        counter[0]++;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        DragShareLog.i(DIAG_TAG, "node d=" + depth
                + " cls=" + asString(node.getClassName())
                + " b=" + bounds.flattenToString()
                + " vis=" + node.isVisibleToUser()
                + " imp=" + node.isImportantForAccessibility()
                + " clk=" + node.isClickable()
                + " ed=" + node.isEditable()
                + " txt=" + truncateText(asString(node.getText()), 40)
                + " desc=" + truncateText(asString(node.getContentDescription()), 40));
        int childCount = node.getChildCount();
        for (int index = 0; index < childCount && counter[0] < DIAG_MAX_NODES; index++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(index);
                dumpDiagnosticNode(child, depth + 1, counter);
            } catch (Throwable ignored) {
                // Skip a broken child and keep walking the rest of the tree.
            } finally {
                recycleNode(child);
            }
        }
    }

    private static String truncateText(String value, int limit) {
        if (value == null) {
            return "null";
        }
        String oneLine = value.replace('\n', ' ');
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit) + "…";
    }

    private boolean isScreenInteractive() {
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return manager == null || manager.isInteractive();
    }

    private boolean isDeviceLocked() {
        KeyguardManager manager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return manager != null && manager.isKeyguardLocked();
    }

    void trace(String message) {
        AccessibilityTrace.record(this, message);
    }

    private static boolean contains(Rect bounds, float x, float y) {
        return bounds != null && x >= bounds.left && x <= bounds.right
                && y >= bounds.top && y <= bounds.bottom;
    }

    private static String asString(CharSequence value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("deprecation")
    private static void recycleNode(AccessibilityNodeInfo node) {
        if (node != null) {
            node.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private static void recycleWindow(AccessibilityWindowInfo window) {
        if (window != null) {
            window.recycle();
        }
    }

    private static final class WindowRoot {
        final AccessibilityNodeInfo root;
        final int layer;

        WindowRoot(AccessibilityNodeInfo root, int layer) {
            this.root = root;
            this.layer = layer;
        }
    }

    private static final class TraversalBudget {
        final long startedAt = SystemClock.uptimeMillis();
        int nodeCount;
        boolean exhausted;
    }
}
