package app.moa.client;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Local bounded image operations. Original document URIs are never written. */
final class MediaImageOps {
    static final int MAX_SELECTION = 20;
    static final long MAX_INPUT_BYTES = 20L * 1024 * 1024;
    static final int MAX_DECODE_EDGE = 2048;
    static final long MAX_DECODE_PIXELS = 4L * 1024 * 1024;
    static final int ORIGINAL = 0, SQUARE = 1, WIDE = 2;

    static final class Edit {
        final int turns, crop, edge;
        Edit(int turns, int crop, int edge) {
            this.turns = Math.floorMod(turns, 4);
            this.crop = crop >= ORIGINAL && crop <= WIDE ? crop : ORIGINAL;
            this.edge = edge == 1280 ? 1280 : 1920;
        }
    }

    static boolean validInput(Uri uri) {
        return uri != null && "content".equals(uri.getScheme()) && uri.getAuthority() != null &&
                !uri.getAuthority().isEmpty() && uri.getUserInfo() == null && uri.getFragment() == null &&
                uri.toString().length() <= 8192;
    }

    static int sampleSize(int width, int height, int edge, long pixels) throws IOException {
        if (width < 1 || height < 1 || width > 100000 || height > 100000 || edge < 1 || pixels < 1) throw new IOException("Invalid image dimensions");
        long sample = 1;
        while ((width + sample - 1L) / sample > edge || (height + sample - 1L) / sample > edge ||
                ((width + sample - 1L) / sample) * ((height + sample - 1L) / sample) > pixels) sample *= 2;
        return (int) sample;
    }

    static Bitmap decode(ContentResolver resolver, Uri uri) throws IOException {
        if (!validInput(uri)) throw new IOException("Choose a local image document");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (InputStream in = boundedInput(resolver, uri)) {
            BitmapFactory.decodeStream(in, null, options);
            // A small image with a large trailing payload is still over the input
            // limit. Decoding just the header must not bypass the byte bound.
            byte[] bytes = new byte[8192];
            while (in.read(bytes) != -1) if (Thread.currentThread().isInterrupted()) throw new IOException("Canceled");
        }
        options.inSampleSize = sampleSize(options.outWidth, options.outHeight, MAX_DECODE_EDGE, MAX_DECODE_PIXELS);
        options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = boundedInput(resolver, uri)) { decoded = BitmapFactory.decodeStream(in, null, options); }
        if (decoded == null || decoded.getWidth() > MAX_DECODE_EDGE || decoded.getHeight() > MAX_DECODE_EDGE ||
                (long) decoded.getWidth() * decoded.getHeight() > MAX_DECODE_PIXELS) {
            if (decoded != null) decoded.recycle(); throw new IOException("Unsupported image");
        }
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = boundedInput(resolver, uri)) {
            orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) { /* Non-EXIF images still decode normally. */ }
        if (orientation >= 2 && orientation <= 8) {
            Bitmap corrected = orient(decoded, orientation); decoded.recycle(); return corrected;
        }
        return decoded;
    }

    @SuppressLint("Recycle") // The returned owning wrapper closes source; callers use try-with-resources.
    static InputStream boundedInput(ContentResolver resolver, Uri uri) throws IOException {
        InputStream source = resolver.openInputStream(uri);
        if (source == null) throw new IOException("Cannot open selected image");
        return new FilterInputStream(source) {
            private long count;
            private void accepted(long amount) throws IOException { if (amount > 0) count += amount; if (count > MAX_INPUT_BYTES) throw new IOException("Image exceeds 20 MB"); }
            @Override public int read() throws IOException { int next = in.read(); accepted(next == -1 ? 0 : 1); return next; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int result = in.read(bytes, offset, (int) Math.min(length, MAX_INPUT_BYTES - count + 1)); accepted(result); return result;
            }
            @Override public long skip(long length) throws IOException {
                long skipped = in.skip(Math.min(length, MAX_INPUT_BYTES - count + 1)); accepted(skipped); return skipped;
            }
        };
    }

    static Bitmap orient(Bitmap source, int orientation) {
        float[][] values = {
                {1,0,0,0,1,0,0,0,1}, {1,0,0,0,1,0,0,0,1},
                {-1,0,0,0,1,0,0,0,1}, {-1,0,0,0,-1,0,0,0,1},
                {1,0,0,0,-1,0,0,0,1}, {0,1,0,1,0,0,0,0,1},
                {0,-1,0,1,0,0,0,0,1}, {0,-1,0,-1,0,0,0,0,1},
                {0,1,0,-1,0,0,0,0,1}
        };
        Matrix matrix = new Matrix(); matrix.setValues(values[Math.max(1, Math.min(8, orientation))]);
        RectF bounds = new RectF(0, 0, source.getWidth(), source.getHeight()); matrix.mapRect(bounds);
        matrix.postTranslate(-bounds.left, -bounds.top);
        Bitmap result = Bitmap.createBitmap(Math.round(bounds.width()), Math.round(bounds.height()), Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(source, matrix, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    static Rect cropBounds(int width, int height, int crop) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid dimensions");
        int targetWidth = width, targetHeight = height;
        if (crop == SQUARE) targetWidth = targetHeight = Math.min(width, height);
        else if (crop == WIDE) {
            if ((long) width * 9 > (long) height * 16) targetWidth = Math.max(1, height * 16 / 9);
            else targetHeight = Math.max(1, width * 9 / 16);
        }
        return new Rect((width - targetWidth) / 2, (height - targetHeight) / 2,
                (width - targetWidth) / 2 + targetWidth, (height - targetHeight) / 2 + targetHeight);
    }

    static Bitmap transform(Bitmap source, Edit edit, int maximumEdge, boolean opaque) {
        if (source == null || source.isRecycled() || maximumEdge < 1 || maximumEdge > 1920) throw new IllegalArgumentException("Invalid image");
        Matrix matrix = new Matrix(); matrix.setRotate(edit.turns * 90);
        RectF rotated = new RectF(0, 0, source.getWidth(), source.getHeight()); matrix.mapRect(rotated);
        matrix.postTranslate(-rotated.left, -rotated.top);
        Rect crop = cropBounds(Math.round(rotated.width()), Math.round(rotated.height()), edit.crop);
        int edge = Math.min(edit.edge, maximumEdge);
        double scale = Math.min(1d, (double) edge / Math.max(crop.width(), crop.height()));
        int width = Math.max(1, (int) Math.round(crop.width() * scale));
        int height = Math.max(1, (int) Math.round(crop.height() * scale));
        matrix.postTranslate(-crop.left, -crop.top); matrix.postScale((float) width / crop.width(), (float) height / crop.height());
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result); if (opaque) canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, matrix, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    static Bitmap frame(Bitmap source, int width, int height) {
        if (width < 1 || height < 1 || width > 640 || height > 640) throw new IllegalArgumentException("Invalid frame dimensions");
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result); canvas.drawColor(Color.WHITE);
        float scale = Math.min((float) width / source.getWidth(), (float) height / source.getHeight());
        float drawnWidth = source.getWidth() * scale, drawnHeight = source.getHeight() * scale;
        canvas.drawBitmap(source, null, new RectF((width - drawnWidth) / 2, (height - drawnHeight) / 2,
                (width + drawnWidth) / 2, (height + drawnHeight) / 2), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    static File directory(Context context) throws IOException {
        File cache = context.getCacheDir().getCanonicalFile();
        File folder = new File(cache, "edited_media");
        if (!folder.getCanonicalFile().getParentFile().equals(cache) || !folder.getCanonicalFile().equals(folder.getAbsoluteFile())) throw new IOException("Invalid cache folder");
        if (!folder.exists() && !folder.mkdir()) throw new IOException("Cannot create image folder");
        if (!folder.isDirectory()) throw new IOException("Invalid image folder");
        return folder;
    }

    static File outputFile(Context context, String extension) throws IOException {
        if (!extension.matches("jpg|png|gif")) throw new IOException("Invalid image format");
        File result = new File(directory(context), "moa-" + UUID.randomUUID() + "." + extension);
        if (!result.createNewFile()) throw new IOException("Cannot create edited image");
        return result;
    }

    static boolean ownedFile(Context context, File file) {
        if (file == null || !file.getName().matches("moa-[a-f0-9-]{36}\\.(jpg|png|gif)")) return false;
        try { return file.getCanonicalFile().getParentFile().equals(directory(context)) && file.getCanonicalFile().equals(file.getAbsoluteFile()); }
        catch (IOException | SecurityException exception) { return false; }
    }

    static void cleanup(Context context) {
        try {
            File[] files = directory(context).listFiles(); if (files == null) return;
            long cutoff = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000;
            int checked = 0;
            for (File file : files) {
                if (checked++ >= 200) break;
                if (file.isFile() && ownedFile(context, file) && file.lastModified() < cutoff) file.delete();
            }
        } catch (IOException | SecurityException ignored) { }
    }

    static OutputStream boundedOutput(OutputStream source, long limit) {
        return new FilterOutputStream(source) {
            private long count;
            @Override public void write(int value) throws IOException { if (++count > limit) throw new IOException("Image exceeds size limit"); out.write(value); }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                if (length < 0 || count + length > limit) throw new IOException("Image exceeds size limit"); out.write(value, offset, length); count += length;
            }
        };
    }
}
