package com.esjzone.reader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes finished books into the public Downloads folder.
 *
 * <p>On API 29+ a pending MediaStore row is used, which needs no permission at all. Older
 * releases fall back to a plain file plus a media scan, which does need the storage permission
 * that MainActivity requests.</p>
 */
public final class Storage {

    public static final String FOLDER = "ESJZone小说";
    public static final String MIME_EPUB = "application/epub+zip";
    public static final String MIME_TXT = "text/plain";

    public abstract static class Sink {
        /** content:// URI once the destination is addressable, otherwise null. */
        public volatile String uri;
        /** Human readable location shown in the UI. */
        public volatile String location = "";
        public String displayName = "";

        public abstract OutputStream stream() throws IOException;

        public abstract void finish() throws IOException;

        public abstract void abort();
    }

    public static Sink open(Context context, String displayName, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStoreSink.create(context, displayName, mime);
        }
        return LegacySink.create(context, displayName, mime);
    }

    /** MediaStore-backed destination for API 29+. */
    private static final class MediaStoreSink extends Sink {

        private final Context context;
        private final Uri target;

        private MediaStoreSink(Context context, Uri target) {
            this.context = context;
            this.target = target;
        }

        static Sink create(Context context, String displayName, String mime) throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("系统拒绝创建下载文件（MediaStore 返回空）");
            }
            MediaStoreSink sink = new MediaStoreSink(context, uri);
            sink.uri = uri.toString();
            sink.displayName = displayName;
            sink.location = "下载/" + FOLDER + "/" + displayName;
            return sink;
        }

        @Override
        public OutputStream stream() throws IOException {
            OutputStream out = context.getContentResolver().openOutputStream(target, "w");
            if (out == null) {
                throw new IOException("无法写入下载目录");
            }
            return out;
        }

        @Override
        public void finish() throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(target, values, null, null);
        }

        @Override
        public void abort() {
            try {
                context.getContentResolver().delete(target, null, null);
            } catch (Exception ignored) {
                // best effort cleanup
            }
        }
    }

    /** Plain-file destination used below API 29. */
    private static final class LegacySink extends Sink {

        private final Context context;
        private final File file;

        private LegacySink(Context context, File file) {
            this.context = context;
            this.file = file;
        }

        static Sink create(Context context, String displayName, String mime) throws IOException {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                throw new IOException("外部存储不可用");
            }
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建目录 " + dir.getAbsolutePath());
            }
            LegacySink sink = new LegacySink(context, new File(dir, displayName));
            sink.displayName = displayName;
            sink.location = sink.file.getAbsolutePath();
            return sink;
        }

        @Override
        public OutputStream stream() throws IOException {
            return new FileOutputStream(file);
        }

        @Override
        public void finish() {
            final Sink self = this;
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()},
                    null, new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            if (uri != null) {
                                self.uri = uri.toString();
                            }
                        }
                    });
        }

        @Override
        public void abort() {
            if (file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    private Storage() {
    }
}
