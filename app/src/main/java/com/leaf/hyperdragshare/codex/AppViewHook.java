package com.leaf.hyperdragshare.codex;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Injected into a target app (Bilibili) so the pure image drawable can be extracted instead
 * of a composited screenshot. The module process writes a request file with the touch point
 * into the app's cache; this hook renders the ImageView under that point to a PNG and leaves
 * it next to the request.
 */
final class AppViewHook {
    private static final String TAG = "DragShare/ViewHook";
    static final String REQ_NAME = "hds_view_req";
    static final String RESP_NAME = "hds_view_resp.png";
    static final String NONE_NAME = "hds_view_resp.none";

    private static final long POLL_INTERVAL_MS = 120L;
    private static volatile File cacheDir;
    private static volatile boolean started;
    private static volatile WeakReference<View> lastRoot;

    private AppViewHook() {}

    static void install(ClassLoader classLoader, String packageName) {
        if (started) {
            return;
        }
        started = true;
        DragShareLog.i(TAG, "installing view hook pkg=" + packageName);
        // The injected process runs under the app's own uid, so its external cache directory
        // is reachable both here and from the module through root. Resolved lazily below
        // because Application/Activity boot-class hooks never fire inside Bilibili.
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Activity",
                    classLoader,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (cacheDir == null || !cacheDir.isDirectory()) {
                                cacheDir = activity.getCacheDir();
                            }
                            trackRoot(activity);
                        }
                    });
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to hook Activity.onResume", error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.app.Application app = (android.app.Application) param.thisObject;
                            if (cacheDir == null) {
                                cacheDir = app.getCacheDir();
                            }
                        }
                    });
        } catch (Throwable ignored) {
            // Application.onCreate can be missing on some ROMs; the Activity hook covers it.
        }
        startWatcher();
    }

    /** Resolves the app's own cache dir in-process; boot-class hooks never fired on Bilibili. */
    private static File resolveCacheDir() {
        File current = cacheDir;
        if (current != null && current.isDirectory()) {
            return current;
        }
        try {
            Object app = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"),
                    "currentApplication");
            if (app instanceof android.app.Application) {
                android.app.Application application = (android.app.Application) app;
                // External cache is a stable path the module process can reach through root.
                File external = application.getExternalCacheDir();
                File internal = application.getCacheDir();
                File chosen = external != null && external.isDirectory() ? external : internal;
                if (chosen != null) {
                    cacheDir = chosen;
                    DragShareLog.i(TAG, "cacheDir resolved=" + chosen);
                    return chosen;
                }
            }
        } catch (Throwable ignored) {
            // Not attached yet; the next poll retries.
        }
        return null;
    }

    private static void trackRoot(Activity activity) {
        try {
            WindowManager manager = activity.getWindowManager();
            android.view.View decor = activity.getWindow() == null
                    ? null
                    : activity.getWindow().peekDecorView();
            if (decor != null) {
                lastRoot = new WeakReference<>(decor);
            }
        } catch (Throwable ignored) {
            // A missing decor view only costs the fallback path.
        }
    }

    private static void startWatcher() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                    File dir = resolveCacheDir();
                    if (dir == null) {
                        continue;
                    }
                    File request = new File(dir, REQ_NAME);
                    if (!request.isFile()) {
                        continue;
                    }
                    String text;
                    try {
                        text = readText(request);
                    } catch (Throwable error) {
                        DragShareLog.w(TAG, "unable to read request", error);
                        request.delete();
                        continue;
                    }
                    String[] parts = text.split(",");
                    if (parts.length < 2) {
                        request.delete();
                        continue;
                    }
                    float x;
                    float y;
                    try {
                        x = Float.parseFloat(parts[0].trim());
                        y = Float.parseFloat(parts[1].trim());
                    } catch (NumberFormatException error) {
                        request.delete();
                        continue;
                    }
                    List<View> roots = collectRootViews();
                    if (roots.isEmpty()) {
                        // A subprocess (download/web) has no windows; leave the request for the
                        // UI process instead of answering "no image" on its behalf.
                        continue;
                    }
                    if (!request.delete()) {
                        continue;
                    }
                    DragShareLog.i(TAG, "request x=" + Math.round(x) + " y=" + Math.round(y));
                    Bitmap bitmap = dumpImageAt(roots, x, y);
                    if (bitmap == null) {
                        DragShareLog.w(TAG, "no ImageView at " + Math.round(x) + "," + Math.round(y));
                        // A marker lets the module answer "no image" immediately instead of
                        // waiting out its full timeout.
                        writeMarker(new File(dir, NONE_NAME));
                        continue;
                    }
                    writePng(new File(dir, RESP_NAME), bitmap);
                    bitmap.recycle();
                    DragShareLog.i(TAG, "pure image dumped at " + Math.round(x) + "," + Math.round(y));
                } catch (InterruptedException interrupted) {
                    return;
                } catch (Throwable error) {
                    DragShareLog.w(TAG, "view dump failed", error);
                }
            }
        }, "hds-view-watcher");
        thread.setDaemon(true);
        thread.start();
        DragShareLog.i(TAG, "watcher started dir=" + cacheDir);
    }

    private static Bitmap dumpImageAt(List<View> roots, float x, float y) {
        ImageView bestImage = null;
        for (View root : roots) {
            ImageView found = findImageViewAt(root, x, y);
            if (found == null) {
                continue;
            }
            if (bestImage == null || area(found) < area(bestImage)) {
                bestImage = found;
            }
        }
        if (bestImage == null) {
            DragShareLog.w(TAG, "no ImageView at " + Math.round(x) + "," + Math.round(y)
                    + " roots=" + roots.size()
                    + " nearest=" + describeNearest(roots, x, y));
            return null;
        }
        return renderImageView(bestImage);
    }

    /** Diagnostic: the closest ImageView to the point, with its class and bounds. */
    private static String describeNearest(List<View> roots, float x, float y) {
        ImageView[] best = new ImageView[1];
        long[] bestDistance = new long[]{Long.MAX_VALUE};
        for (View root : roots) {
            collectClosest(root, x, y, best, bestDistance);
        }
        if (best[0] == null) {
            return "none";
        }
        Rect bounds = new Rect();
        best[0].getGlobalVisibleRect(bounds);
        return best[0].getClass().getSimpleName() + " b=" + bounds.flattenToString()
                + " d=" + bestDistance[0];
    }

    private static void collectClosest(
            View view,
            float x,
            float y,
            ImageView[] best,
            long[] bestDistance) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof ImageView) {
            Rect bounds = new Rect();
            if (view.getGlobalVisibleRect(bounds)) {
                float cx = Math.max(bounds.left, Math.min(x, bounds.right));
                float cy = Math.max(bounds.top, Math.min(y, bounds.bottom));
                long distance = (long) ((cx - x) * (cx - x) + (cy - y) * (cy - y));
                if (distance < bestDistance[0]) {
                    bestDistance[0] = distance;
                    best[0] = (ImageView) view;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectClosest(group.getChildAt(index), x, y, best, bestDistance);
            }
        }
    }

    private static List<View> collectRootViews() {
        List<View> roots = new ArrayList<>();
        try {
            Object instance = XposedHelpers.callStaticMethod(
                    Class.forName("android.view.WindowManagerGlobal"),
                    "getInstance");
            Object views = XposedHelpers.getObjectField(instance, "mViews");
            if (views instanceof List) {
                for (Object value : (List<?>) views) {
                    if (value instanceof View) {
                        roots.add((View) value);
                    }
                }
            }
        } catch (Throwable error) {
            DragShareLog.w(TAG, "unable to read WindowManagerGlobal.mViews", error);
        }
        WeakReference<View> tracked = lastRoot;
        View decor = tracked == null ? null : tracked.get();
        if (decor != null && !roots.contains(decor)) {
            roots.add(decor);
        }
        return roots;
    }

    private static ImageView findImageViewAt(View view, float x, float y) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof ImageView) {
            Rect bounds = new Rect();
            if (view.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0
                    && x >= bounds.left - 2f && x <= bounds.right + 2f
                    && y >= bounds.top - 2f && y <= bounds.bottom + 2f) {
                return (ImageView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                ImageView found = findImageViewAt(group.getChildAt(index), x, y);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static long area(View view) {
        return (long) view.getWidth() * (long) view.getHeight();
    }

    private static Bitmap renderImageView(ImageView imageView) {
        Drawable drawable = imageView.getDrawable();
        if (drawable == null) {
            return null;
        }
        Rect bounds = new Rect();
        if (!imageView.getGlobalVisibleRect(bounds) || bounds.width() <= 0 || bounds.height() <= 0) {
            return null;
        }
        int width = Math.max(1, Math.min(bounds.width(), imageView.getWidth() > 0
                ? imageView.getWidth()
                : bounds.width()));
        int height = Math.max(1, Math.min(bounds.height(), imageView.getHeight() > 0
                ? imageView.getHeight()
                : bounds.height()));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    private static String readText(File file) throws Exception {
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[256];
            int count = input.read(buffer);
            if (count <= 0) {
                return "";
            }
            return new String(buffer, 0, count, "UTF-8");
        }
    }

    private static void writePng(File file, Bitmap bitmap) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            output.getFD().sync();
        }
    }

    private static void writeMarker(File file) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(1);
            output.getFD().sync();
        } catch (Throwable ignored) {
            // The module then falls back to its request timeout.
        }
    }
}
