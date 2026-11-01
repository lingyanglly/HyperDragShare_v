package com.leaf.hyperdragshare.codex;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** In-memory root screenshot used as the internal fallback when direct capture fails. */
final class RootScreenshotter {
    private static final long COMMAND_TIMEOUT_SECONDS = 4L;

    Bitmap capture() throws IOException {
        java.lang.Process process = null;
        Thread reader = null;
        try {
            process = new ProcessBuilder("su", "-c", "/system/bin/screencap -p").start();
            final java.lang.Process currentProcess = process;
            // The PNG payload is far larger than the pipe buffer, so the stream must be
            // drained while the command runs. Reading on a dedicated thread keeps a hung
            // su (authorization dialog etc.) from blocking the caller forever: the timeout
            // below kills the process and the stream close unblocks the reader.
            final byte[][] result = new byte[1][];
            final Throwable[] readError = new Throwable[1];
            reader = new Thread(() -> {
                try (InputStream input = currentProcess.getInputStream()) {
                    result[0] = readAll(input);
                } catch (Throwable error) {
                    readError[0] = error;
                }
            }, "drag-share-screencap-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished;
            try {
                finished = currentProcess.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                currentProcess.destroyForcibly();
                throw new IOException("Root screencap interrupted", interrupted);
            }
            if (!finished) {
                currentProcess.destroyForcibly();
                throw new IOException("Root screencap timed out");
            }
            try {
                reader.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Root screencap interrupted", interrupted);
            }
            if (readError[0] != null) {
                throw new IOException("Root screencap read failed", readError[0]);
            }
            byte[] bytes = result[0];
            if (currentProcess.exitValue() != 0 || bytes == null || bytes.length == 0) {
                throw new IOException("Root screencap failed");
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                throw new IOException("Root screencap returned invalid PNG");
            }
            return bitmap;
        } finally {
            if (process != null) {
                try {
                    process.getErrorStream().close();
                } catch (IOException ignored) {
                    // Process teardown only.
                }
                process.destroy();
            }
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }
}
