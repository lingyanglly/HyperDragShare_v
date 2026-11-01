package com.leaf.hyperdragshare.codex;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Module-side client for {@link AppViewHook}: asks the injected app process to render the
 * ImageView under a point and reads the resulting PNG back through root.
 */
final class ViewDumpClient {
    private static final String TAG = "DragShare/ViewHook";
    private static final String BILIBILI_PACKAGE = "tv.danmaku.bili";
    /** External app cache: a stable path both the app process and root can reach. */
    private static final String CACHE_DIR =
            "/storage/emulated/0/Android/data/" + BILIBILI_PACKAGE + "/cache/";
    private static final String REQUEST = CACHE_DIR + AppViewHook.REQ_NAME;
    private static final String RESPONSE = CACHE_DIR + AppViewHook.RESP_NAME;
    private static final String NONE_MARKER = CACHE_DIR + AppViewHook.NONE_NAME;
    private static final long TIMEOUT_MS = 700L;
    private static final long POLL_INTERVAL_MS = 60L;

    private ViewDumpClient() {}

    static boolean supports(String sourcePackage) {
        return BILIBILI_PACKAGE.equals(sourcePackage);
    }

    /**
     * Requests the pure drawable under the point. Returns {@code null} when the app process is
     * not running or the view is not a plain image, so the caller can fall back to a screenshot.
     */
    static Bitmap request(float x, float y) {
        if (x < 0f || y < 0f) {
            return null;
        }
        try {
            String write = runCommand("rm -f " + RESPONSE + " " + NONE_MARKER + "; echo '"
                    + Math.round(x) + "," + Math.round(y) + "' > " + REQUEST
                    + "; chmod 666 " + REQUEST + "; ls -l " + REQUEST + " 2>&1");
            DragShareLog.i(TAG, "request queued at " + Math.round(x) + "," + Math.round(y)
                    + " write=" + write);
            long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
            while (SystemClock.uptimeMillis() < deadline) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                byte[] png = readBytes(RESPONSE);
                if (png != null && png.length > 0) {
                    runCommand("rm -f " + REQUEST + " " + RESPONSE + " " + NONE_MARKER);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bitmap != null) {
                        DragShareLog.i(TAG, "pure image received bytes=" + png.length
                                + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                    }
                    return bitmap;
                }
                if (readBytes(NONE_MARKER) != null) {
                    runCommand("rm -f " + REQUEST + " " + NONE_MARKER);
                    DragShareLog.i(TAG, "app reported no ImageView at the point");
                    return null;
                }
            }
            DragShareLog.i(TAG, "no pure image response");
            runCommand("rm -f " + REQUEST);
        } catch (Throwable error) {
            DragShareLog.w(TAG, "pure image request failed", error);
        }
        return null;
    }

    private static String runCommand(String command) {
        return RootShell.exec(command);
    }

    private static byte[] readBytes(String path) {
        return RootShell.readFile(path);
    }
}
