package com.leaf.hyperdragshare.codex;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Copies a captured image into the user's Pictures collection. */
final class LocalImageSaver {
    private static final String DIRECTORY_NAME = "HyperDragShare";
    private static final String MIME_PNG = "image/png";

    private LocalImageSaver() {}

    static Uri save(Context context, Bitmap bitmap) throws IOException {
        if (context == null || bitmap == null || bitmap.isRecycled()) {
            throw new IOException("Image is unavailable");
        }
        return save(context, output -> writePng(bitmap, output));
    }

    static String timestampName(long timestampMillis) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date(timestampMillis)) + ".png";
    }

    static void writePng(Bitmap bitmap, OutputStream output) throws IOException {
        BitmapEncoder.writePng(bitmap, output);
    }

    private static Uri save(Context context, OutputWriter writer) throws IOException {
        return saveWithMediaStore(context, timestampName(System.currentTimeMillis()), writer);
    }

    private static Uri saveWithMediaStore(
            Context context,
            String displayName,
            OutputWriter writer) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG);
        values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + DIRECTORY_NAME);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore insert returned null");
        }
        try {
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) {
                    throw new IOException("Unable to open destination image");
                }
                writer.write(output);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            return uri;
        } catch (Throwable error) {
            try {
                resolver.delete(uri, null, null);
            } catch (Throwable ignored) {
                // Preserve the original encoding or storage error.
            }
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Unable to save image", error);
        }
    }

    private interface OutputWriter {
        void write(OutputStream output) throws IOException;
    }
}
