package app.moa.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** A small first-party GIF89a encoder with bounded memory and a fixed 256-color palette.
 * Format reference: https://www.w3.org/Graphics/GIF/spec-gif89a.txt
 * Clear codes after 200 literal pixels keep LZW at nine bits; no growing dictionary
 * or third-party native encoder is needed. Photo quality is intentionally limited
 * to GIF's palette. JPEG/PNG export should be used to retain photo color detail.
 */
final class LocalGifEncoder {
    static final long MAX_BYTES = 25L * 1024 * 1024;
    private final OutputStream out;
    private final int width, height, delay;
    private final long limit;
    private long written;
    private int frames;
    private boolean finished;

    LocalGifEncoder(OutputStream out, int width, int height, int delayMs, boolean loop) throws IOException {
        this(out, width, height, delayMs, loop, MAX_BYTES);
    }

    LocalGifEncoder(OutputStream out, int width, int height, int delayMs, boolean loop, long limit) throws IOException {
        if (out == null || width < 1 || height < 1 || width > 640 || height > 640 || limit < 1) {
            throw new IllegalArgumentException("Invalid GIF dimensions");
        }
        this.out = out; this.width = width; this.height = height;
        this.delay = Math.max(10, Math.min(200, (delayMs + 5) / 10)); this.limit = limit;
        ascii("GIF89a"); word(width); word(height); one(0xf7); one(255); one(0);
        for (int i = 0; i < 256; i++) {
            one(((i >>> 5) & 7) * 255 / 7);
            one(((i >>> 2) & 7) * 255 / 7);
            one((i & 3) * 255 / 3);
        }
        if (loop) {
            one(0x21); one(0xff); one(11); ascii("NETSCAPE2.0");
            one(3); one(1); word(0); one(0);
        }
    }

    void addFrame(int[] argb) throws IOException {
        if (finished || frames >= 20 || argb == null || argb.length != width * height) {
            throw new IllegalArgumentException("Invalid GIF frame");
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("Canceled");
        one(0x21); one(0xf9); one(4); one(8); word(delay); one(0); one(0);
        one(0x2c); word(0); word(0); word(width); word(height); one(0); one(8);
        Blocks blocks = new Blocks(); blocks.code(256);
        int literals = 0;
        for (int i = 0; i < argb.length; i++) {
            if ((i & 4095) == 0 && Thread.currentThread().isInterrupted()) throw new IOException("Canceled");
            if (literals == 200) { blocks.code(256); literals = 0; }
            int color = argb[i], alpha = color >>> 24;
            int red = blend((color >>> 16) & 255, alpha);
            int green = blend((color >>> 8) & 255, alpha);
            int blue = blend(color & 255, alpha);
            blocks.code((red & 0xe0) | ((green & 0xe0) >>> 3) | ((blue & 0xc0) >>> 6));
            literals++;
        }
        blocks.code(257); blocks.finish(); frames++;
    }

    void finish() throws IOException {
        if (finished) return;
        if (frames < 2) throw new IllegalStateException("GIF needs at least two frames");
        one(0x3b); out.flush(); finished = true;
    }

    long bytesWritten() { return written; }
    private static int blend(int value, int alpha) { return (value * alpha + 255 * (255 - alpha) + 127) / 255; }
    private void ascii(String value) throws IOException { for (byte part : value.getBytes(StandardCharsets.US_ASCII)) one(part & 255); }
    private void word(int value) throws IOException { one(value & 255); one((value >>> 8) & 255); }
    private void one(int value) throws IOException {
        if (written >= limit) throw new IOException("GIF exceeds size limit");
        out.write(value); written++;
    }

    private final class Blocks {
        private final byte[] data = new byte[255];
        private int size, pending, bits;
        void code(int value) throws IOException {
            pending |= value << bits; bits += 9;
            while (bits >= 8) { append(pending & 255); pending >>>= 8; bits -= 8; }
        }
        void append(int value) throws IOException {
            data[size++] = (byte) value;
            if (size == data.length) flush();
        }
        void flush() throws IOException {
            if (size == 0) return;
            one(size); for (int i = 0; i < size; i++) one(data[i] & 255); size = 0;
        }
        void finish() throws IOException { if (bits > 0) append(pending & 255); flush(); one(0); }
    }
}
