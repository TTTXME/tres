package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P620 (with the gallery's thumbnails, t-thumb): our files carried no embedded preview, so the gallery decoded the whole photo
 * (a 50 MP JPEG ~200 ms) for its first show. Now:
 * <ul>
 * <li>the photo's EXIF (JPEG, Ultra HDR, HEIC, AVIF: ExifBlock.app1Segment) carries a baseline JPEG thumbnail in IFD1 - 512 px
 * on the long side, the photo's aspect and pixel orientation (the photo's pixels are stored upright), quality 85, the whole
 * APP1 at most 64 KB (a lower quality, else none) - downscaled from the rendered photo (its bitmap, or the final readback of
 * the streamed JPEG), never by decoding the saved file;</li>
 * <li>the DNG a reduced-resolution preview (DngPreview): the developed photo, 1024 px, in a SubIFD.</li>
 * </ul>
 * The photo's own pixels are not touched.
 */
public final class PhotoThumbnail {
    private static final String TAG = "PhotoThumbnail";
    /** The EXIF thumbnail's long side (ExifInterface: a thumbnail is at most 512 x 512). */
    public static final int EXIF_LONG_SIDE = 512;
    /** The DNG preview's long side. */
    public static final int DNG_LONG_SIDE = 1024;
    static final int QUALITY = 85;
    /** An APP1 segment's length field counts at most 65535 bytes (2 of them its own). */
    static final int APP1_MAX = 65535 + 2;

    private PhotoThumbnail() {}

    /** The thumbnail size of a {@code width} x {@code height} image: {@code longSide} on its long side, its aspect (at least 1 px). */
    public static int[] size(int width, int height, int longSide) {
        if (width <= 0 || height <= 0) return new int[]{0, 0};
        if (Math.max(width, height) <= longSide) return new int[]{width, height};
        final double s = longSide / (double) Math.max(width, height);
        return new int[]{Math.max(1, (int) Math.round(width * s)), Math.max(1, (int) Math.round(height * s))};
    }

    /** Reads rows of an image as ARGB ints (no alpha used). */
    private interface Rows {
        void row(int y, int[] into);
    }

    /**
     * A {@code longSide} ARGB_8888 copy of {@code img} (any config: a 10-bit image reads as 8 bits); null when it cannot be made.
     * The same samples as {@link #scaledRgba} of the same pixels, so the streamed JPEG's thumbnail is the bitmap path's (P483:
     * the two files are the same bytes). The source is kept.
     */
    @Nullable
    public static Bitmap scaled(@Nullable Bitmap img, int longSide) {
        if (img == null || img.isRecycled()) return null;
        try {
            final int w = img.getWidth();
            return sample(w, img.getHeight(), longSide, (y, into) -> img.getPixels(into, 0, w, 0, y, w, 1));
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "thumbnail of " + img.getWidth() + "x" + img.getHeight() + " failed: " + e);
            return null;
        }
    }

    /**
     * A {@code longSide} ARGB_8888 bitmap of an RGBA_8888 buffer ({@code width} x {@code height}, rows {@code strideBytes} apart,
     * from the buffer's position): the streamed JPEG's final readback (P483: no bitmap of the photo exists). Null for a buffer
     * too small.
     */
    @Nullable
    public static Bitmap scaledRgba(@Nullable ByteBuffer rgba, int width, int height, int strideBytes, int longSide) {
        if (rgba == null || width <= 0 || height <= 0 || strideBytes < 4 * width) return null;
        final ByteBuffer b = rgba.duplicate();
        final int base = rgba.position();
        if ((long) base + (long) strideBytes * (height - 1) + 4L * width > b.limit()) return null;
        try {
            final byte[] line = new byte[4 * width];
            return sample(width, height, longSide, (y, into) -> {
                b.position(base + y * strideBytes);
                b.get(line, 0, line.length);
                for (int x = 0, at = 0; x < width; x++, at += 4)
                    into[x] = 0xFF000000 | (line[at] & 0xFF) << 16 | (line[at + 1] & 0xFF) << 8 | (line[at + 2] & 0xFF);
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "thumbnail of the readback failed: " + e);
            return null;
        }
    }

    /** Each thumbnail pixel the mean of up to 4 x 4 samples spread over its box of the {@code width} x {@code height} image. */
    private static Bitmap sample(int width, int height, int longSide, Rows rows) {
        final int[] s = size(width, height, longSide);
        final int tw = s[0], th = s[1];
        final int[] out = new int[tw * th];
        final int[][] lines = new int[4][width];
        final int[] xs = new int[4 * tw], nxs = new int[tw];
        for (int tx = 0; tx < tw; tx++) {
            final int x0 = (int) ((long) tx * width / tw), x1 = Math.max(x0 + 1, (int) ((long) (tx + 1) * width / tw));
            final int nx = Math.min(4, x1 - x0);
            nxs[tx] = nx;
            for (int i = 0; i < nx; i++) xs[4 * tx + i] = x0 + (int) ((2L * i + 1) * (x1 - x0) / (2L * nx));
        }
        for (int ty = 0; ty < th; ty++) {
            final int y0 = (int) ((long) ty * height / th), y1 = Math.max(y0 + 1, (int) ((long) (ty + 1) * height / th));
            final int ny = Math.min(4, y1 - y0);
            for (int j = 0; j < ny; j++) rows.row(y0 + (int) ((2L * j + 1) * (y1 - y0) / (2L * ny)), lines[j]);
            for (int tx = 0; tx < tw; tx++) {
                final int nx = nxs[tx];
                int r = 0, g = 0, bl = 0;
                for (int j = 0; j < ny; j++)
                    for (int i = 0; i < nx; i++) {
                        final int c = lines[j][xs[4 * tx + i]];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        bl += c & 0xFF;
                    }
                final int n = nx * ny;
                out[ty * tw + tx] = 0xFF000000 | ((r + n / 2) / n) << 16 | ((g + n / 2) / n) << 8 | ((bl + n / 2) / n);
            }
        }
        return Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888);
    }

    /** A baseline JPEG of {@code small} at {@code quality}; null on failure. */
    @Nullable
    public static byte[] jpeg(@Nullable Bitmap small, int quality) {
        if (small == null || small.isRecycled()) return null;
        final ByteArrayOutputStream o = new ByteArrayOutputStream(64 << 10);
        try {
            return small.compress(Bitmap.CompressFormat.JPEG, quality, o) ? o.toByteArray() : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "thumbnail JPEG failed: " + e);
            return null;
        }
    }

    /** The EXIF thumbnail of the photo {@code img} (512 px, quality 85), null when it cannot be made; {@code img} is kept. */
    @Nullable
    public static Bitmap exifSource(@Nullable Bitmap img) {
        return scaled(img, EXIF_LONG_SIDE);
    }

    /**
     * {@code app1} (a JPEG APP1 EXIF segment: FF E1, length, "Exif\0\0", TIFF) with {@code small} as its IFD1 thumbnail; the
     * segment unchanged when there is no thumbnail, IFD0 already has a next IFD, the TIFF cannot be read or no quality from 85
     * down keeps the segment within 64 KB. {@code small} is kept.
     */
    public static byte[] withThumbnail(@Nullable byte[] app1, @Nullable Bitmap small) {
        if (app1 == null || small == null) return app1;
        final long started = System.nanoTime();
        for (int q : new int[]{QUALITY, 70, 55}) {
            final byte[] thumb = jpeg(small, q);
            if (thumb == null) return app1;
            final byte[] out = withThumbnail(app1, thumb);
            if (out == null) return app1;
            if (out.length <= APP1_MAX) {
                Log.i(TAG, "EXIF thumbnail " + small.getWidth() + "x" + small.getHeight() + " q" + q + ": " + thumb.length + " B, APP1 "
                        + out.length + " B in " + (System.nanoTime() - started) / 1000000 + " ms");
                return out;
            }
        }
        Log.w(TAG, "EXIF thumbnail does not fit the 64 KB APP1: none");
        return app1;
    }

    /**
     * {@code app1} with the JPEG {@code thumb} in a new IFD1 (Compression 6, JPEGInterchangeFormat / Length, X / YResolution 72,
     * ResolutionUnit inch) at the end of its TIFF, IFD0's next-IFD offset pointing at it, the segment length updated; null when
     * {@code app1} is no EXIF APP1 this can extend (no IFD0, an IFD1 already).
     */
    @Nullable
    static byte[] withThumbnail(byte[] app1, byte[] thumb) {
        if (app1.length < 10 + 8 || (app1[0] & 0xFF) != 0xFF || (app1[1] & 0xFF) != 0xE1 || app1[4] != 'E' || app1[5] != 'x'
                || app1[6] != 'i' || app1[7] != 'f' || app1[8] != 0 || app1[9] != 0) return null;
        final int tiff = 10;
        final ByteOrder order;
        if (app1[tiff] == 'I' && app1[tiff + 1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
        else if (app1[tiff] == 'M' && app1[tiff + 1] == 'M') order = ByteOrder.BIG_ENDIAN;
        else return null;
        final ByteBuffer in = ByteBuffer.wrap(app1).order(order);
        final int tiffLen = app1.length - tiff;
        final long ifd0 = in.getInt(tiff + 4) & 0xFFFFFFFFL;
        if (ifd0 < 8 || ifd0 + 2 > tiffLen) return null;
        final int count = in.getShort(tiff + (int) ifd0) & 0xFFFF;
        final int next = tiff + (int) ifd0 + 2 + 12 * count;
        if (next + 4 > app1.length || in.getInt(next) != 0) return null;
        // the TIFF's end, even; then IFD1 (6 entries), its two resolutions and the thumbnail
        final int ifd1 = (tiffLen + 1) & ~1;
        final int entries = 6, ifdBytes = 2 + 12 * entries + 4;
        final int xRes = ifd1 + ifdBytes, yRes = xRes + 8, data = yRes + 8;
        final int total = tiff + data + thumb.length;
        final ByteBuffer out = ByteBuffer.allocate(total).order(order);
        out.put(app1);
        out.putInt(next, ifd1);
        out.position(tiff + ifd1);
        out.putShort((short) entries);
        entry(out, 0x0103, 3, 1, 6, true);            // Compression: JPEG (thumbnail)
        entry(out, 0x011A, 5, 1, xRes, false);        // XResolution
        entry(out, 0x011B, 5, 1, yRes, false);        // YResolution
        entry(out, 0x0128, 3, 1, 2, true);            // ResolutionUnit: inch
        entry(out, 0x0201, 4, 1, data, false);        // JPEGInterchangeFormat
        entry(out, 0x0202, 4, 1, thumb.length, false); // JPEGInterchangeFormatLength
        out.putInt(0);
        out.putInt(72).putInt(1).putInt(72).putInt(1);
        out.put(thumb);
        final byte[] b = out.array();
        final int len = total - 2;
        if (len > 0xFFFF) return b; // the caller sees it is too long
        b[2] = (byte) (len >> 8);
        b[3] = (byte) len;
        return b;
    }

    private static void entry(ByteBuffer out, int tag, int type, int count, int value, boolean shortValue) {
        out.putShort((short) tag).putShort((short) type).putInt(count);
        if (shortValue) out.putShort((short) value).putShort((short) 0);
        else out.putInt(value);
    }

    /**
     * The DNG preview of the photo: {@code photoSmall} (the photo upright, as saved, at {@link #DNG_LONG_SIDE}) turned back to the sensor's orientation (the DNG's
     * Orientation tag turns its previews as its raw: {@code cameraRotation} degrees clockwise). Null on failure;
     * {@code photoSmall} is kept.
     */
    @Nullable
    public static Bitmap dngSource(@Nullable Bitmap photoSmall, int cameraRotation) {
        if (photoSmall == null) return null;
        final int r = ((cameraRotation % 360) + 360) % 360;
        if (r == 0) return photoSmall.copy(Bitmap.Config.ARGB_8888, false);
        final Matrix m = new Matrix();
        m.postRotate(-r);
        try {
            return Bitmap.createBitmap(photoSmall, 0, 0, photoSmall.getWidth(), photoSmall.getHeight(), m, true);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "DNG preview rotation failed: " + e);
            return null;
        }
    }
}
