package com.particlesdevs.photoncamera.processing.watermark;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.nio.ByteBuffer;

/**
 * P642: a photo as the streamed JPEG has it - RGBA_8888 rows in a buffer (the final readback, {@code strideBytes} apart from
 * the buffer's position), no bitmap of the whole photo. A mark is drawn by the bitmap route's own code on a {@link #window}: a
 * bitmap of the photo's size that holds the photo's bytes only in the rectangle the mark reads and changes. The drawing calls,
 * their coordinates and the bitmap's size are the bitmap route's, so what Skia writes there is what it writes into the whole
 * photo (a canvas moved to a small bitmap is NOT that: a rounded corner's edge came out 3 % of a level different on two pixels -
 * its curve is cut in single precision at other magnitudes). The window's other pixels are never touched, and a bitmap's memory
 * is zero pages until it is (as JpegliEncoder.touchPixels knows), so it costs the rectangle, not the photo. {@link #load} and
 * {@link #store} move the rectangle's words between the rows and the window as they are (a sprite copy, as WatermarkPatch.crop).
 */
public final class RgbaRows {
    public final int width, height, strideBytes;
    private final ByteBuffer pixels;
    private final int base;

    public RgbaRows(ByteBuffer pixels, int width, int height, int strideBytes) {
        if (pixels == null || width <= 0 || height <= 0 || strideBytes < 4 * width)
            throw new IllegalArgumentException("rows " + width + "x" + height + " stride " + strideBytes);
        this.pixels = pixels.duplicate();
        this.base = pixels.position();
        this.pixels.clear();
        if ((long) base + (long) strideBytes * (height - 1) + 4L * width > this.pixels.capacity())
            throw new IllegalArgumentException("rows " + width + "x" + height + " past the buffer");
        this.width = width;
        this.height = height;
        this.strideBytes = strideBytes;
    }

    private void inside(Rect r) {
        if (r.left < 0 || r.top < 0 || r.right > width || r.bottom > height || r.isEmpty())
            throw new IllegalArgumentException(r + " outside " + width + "x" + height);
    }

    /** The bytes of {@code r}, row after row. */
    public byte[] bytes(Rect r) {
        inside(r);
        final byte[] out = new byte[4 * r.width() * r.height()];
        final int row = 4 * r.width();
        for (int y = 0; y < r.height(); y++) {
            pixels.position(base + (r.top + y) * strideBytes + 4 * r.left);
            pixels.get(out, y * row, row);
        }
        return out;
    }

    /** {@code bytes} (as {@link #bytes} gave them) back into {@code r}. */
    public void write(Rect r, byte[] bytes) {
        inside(r);
        final int row = 4 * r.width();
        if (bytes.length != row * r.height()) throw new IllegalArgumentException("bytes of another rectangle");
        for (int y = 0; y < r.height(); y++) {
            pixels.position(base + (r.top + y) * strideBytes + 4 * r.left);
            pixels.put(bytes, y * row, row);
        }
    }

    /** {@code r} as a mutable ARGB_8888 bitmap of the same bytes. */
    public Bitmap cut(Rect r) {
        final byte[] b = bytes(r);
        final Bitmap out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888);
        out.copyPixelsFromBuffer(ByteBuffer.wrap(b));
        return out;
    }

    /** A mutable ARGB_8888 bitmap of the photo's size, nothing in it yet (its pages untouched). */
    public Bitmap window() {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private static android.graphics.Paint copyPaint() {
        android.graphics.Paint p = new android.graphics.Paint();
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC));
        p.setFilterBitmap(false);
        p.setDither(false);
        return p;
    }

    /** The rows' bytes of {@code r} into {@code window} at the same place. */
    public void load(Bitmap window, Rect r) {
        final Bitmap part = cut(r);
        try {
            new android.graphics.Canvas(window).drawBitmap(part, null, r, copyPaint());
        } finally {
            part.recycle();
        }
    }

    /** {@code window}'s bytes of {@code r} into the rows. */
    public void store(Bitmap window, Rect r) {
        inside(r);
        final Bitmap part = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888);
        try {
            new android.graphics.Canvas(part).drawBitmap(window, r, new Rect(0, 0, r.width(), r.height()), copyPaint());
            final byte[] b = new byte[4 * r.width() * r.height()];
            part.copyPixelsToBuffer(ByteBuffer.wrap(b));
            write(r, b);
        } finally {
            part.recycle();
        }
    }

    /** A drawing into the rows that can be taken back: the rectangle it changed and the bytes it held before. */
    public static final class Undo {
        private final RgbaRows rows;
        private final Rect rect;
        private final byte[] before;

        Undo(RgbaRows rows, Rect rect, byte[] before) {
            this.rows = rows;
            this.rect = rect;
            this.before = before;
        }

        /** The rows as they were before the drawing. */
        public void run() {
            rows.write(rect, before);
        }
    }

    public Undo undo(Rect r, byte[] before) {
        return new Undo(this, new Rect(r), before);
    }
}
