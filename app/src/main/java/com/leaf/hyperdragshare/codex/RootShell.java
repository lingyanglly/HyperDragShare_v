package com.leaf.hyperdragshare.codex;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Single place that talks to {@code su}.
 *
 * <p>Root commands used to be rebuilt in every caller (portal hook, image hand-off, module
 * service, boot receiver), which made the process handling and the timeouts drift apart.
 */
final class RootShell {
    private static final long DEFAULT_TIMEOUT_MS = 1_500L;
    private static final long READ_TIMEOUT_MS = 3_000L;

    private RootShell() {
    }

    /** Runs a command and returns its trimmed stdout (stderr is merged in). */
    static String exec(String command) {
        return exec(command, DEFAULT_TIMEOUT_MS);
    }

    static String exec(String command, long timeoutMs) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = drain(process, timeoutMs);
            return output.toString().trim();
        } catch (Throwable error) {
            DragShareLog.w("DragShare/RootShell", "root command failed", error);
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** Fires a command and reports whether {@code su} exited cleanly. */
    static boolean run(String command) {
        return run(command, DEFAULT_TIMEOUT_MS);
    }

    static boolean run(String command, long timeoutMs) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            return finished && process.exitValue() == 0;
        } catch (Throwable error) {
            DragShareLog.w("DragShare/RootShell", "root command failed", error);
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** Reads a file through root; {@code null} when it is missing or unreadable. */
    static byte[] readFile(String path) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "cat " + path + " 2>/dev/null").start();
            ByteArrayOutputStream output = drain(process, READ_TIMEOUT_MS);
            return output.size() == 0 ? null : output.toByteArray();
        } catch (Throwable error) {
            DragShareLog.w("DragShare/RootShell", "unable to read " + path, error);
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static ByteArrayOutputStream drain(java.lang.Process process, long timeoutMs)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
        }
        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        return output;
    }
}
