package com.esjzone.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites an EPUB with smaller illustrations.
 *
 * <p>Each picture is decoded with a sampling factor so a huge JPEG never lands in memory at full
 * size, scaled down to {@code maxEdge}, then re-encoded in its own format. PNG stays PNG and JPEG
 * stays JPEG, so file names, the manifest and every {@code <img src>} keep working.</p>
 */
public final class ImageCompressor {

    public static final class Result {
        public long before;
        public long after;
        public int images;
        public int shrunk;
        public int failed;
        public int skipped;

        public String summary() {
            long saved = before - after;
            double percent = before > 0 ? saved * 100.0 / before : 0;
            return String.format(Locale.US,
                    "图片 %d 张（缩小 %d，跳过 %d，失败 %d）\n体积 %s → %s，省 %s（%.0f%%）",
                    images, shrunk, skipped, failed,
                    Downloader.humanBytes(before), Downloader.humanBytes(after),
                    Downloader.humanBytes(saved), percent);
        }
    }

    public static Result compress(File source, OutputStream out, int maxEdge, int quality)
            throws IOException {
        Result result = new Result();
        result.before = source.length();

        ZipFile zip = new ZipFile(source);
        try {
            ZipOutputStream target = new ZipOutputStream(out);
            target.setLevel(Deflater.DEFAULT_COMPRESSION);
            try {
                // mimetype must stay first and stored
                writeMimetype(zip, target);

                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if ("mimetype".equals(entry.getName()) || entry.isDirectory()) {
                        continue;
                    }
                    byte[] data = readEntry(zip, entry);
                    if (isImage(entry.getName())) {
                        result.images++;
                        byte[] shrunk = shrink(data, entry.getName(), maxEdge, quality);
                        if (shrunk == null) {
                            result.failed++;
                        } else if (shrunk.length < data.length) {
                            result.shrunk++;
                            data = shrunk;
                        } else {
                            result.skipped++;
                        }
                    }
                    ZipEntry copy = new ZipEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    target.putNextEntry(copy);
                    target.write(data);
                    target.closeEntry();
                }
                target.finish();
            } finally {
                target.close();
            }
        } finally {
            zip.close();
        }
        result.after = -1;
        return result;
    }

    private static void writeMimetype(ZipFile zip, ZipOutputStream target) throws IOException {
        byte[] mime;
        ZipEntry original = zip.getEntry("mimetype");
        if (original != null) {
            mime = readEntry(zip, original);
        } else {
            mime = "application/epub+zip".getBytes("US-ASCII");
        }
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(mime.length);
        entry.setCompressedSize(mime.length);
        CRC32 crc = new CRC32();
        crc.update(mime);
        entry.setCrc(crc.getValue());
        target.putNextEntry(entry);
        target.write(mime);
        target.closeEntry();
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private static byte[] shrink(byte[] data, String name, int maxEdge, int quality) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            // sample down while decoding so memory stays bounded
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxEdge
                    || bounds.outHeight / (sample * 2) >= maxEdge) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) {
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int longest = Math.max(width, height);
            if (longest > maxEdge) {
                float scale = (float) maxEdge / longest;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)), true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                }
                bitmap = scaled;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(data.length / 2 + 1024);
            boolean png = name.toLowerCase(Locale.US).endsWith(".png");
            bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                    png ? 100 : quality, buffer);
            bitmap.recycle();
            return buffer.toByteArray();
        } catch (OutOfMemoryError oom) {
            return null;
        } catch (Exception error) {
            return null;
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream in = zip.getInputStream(entry);
        try {
            long size = entry.getSize();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                    (int) (size > 0 ? Math.min(size, 1 << 22) : 1 << 16));
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private ImageCompressor() {
    }
}
