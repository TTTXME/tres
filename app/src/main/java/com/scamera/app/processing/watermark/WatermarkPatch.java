package com.scamera.app.processing.watermark;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * P633 (owner, 2026-11-11: «дай возможность их редактировать у нас в галерее, добавлять, убирать на наших фото»): the original pixels
 * under a photo's watermark and the watermark's spec, kept in the photo's file (WatermarkStore), so the gallery's editor can take
 * the watermark off exactly - the pixels the camera had before it drew it - or redraw it with other settings.
 * <p>
 * The pixels are those of {@link WatermarkArea#of}: the touched {@link WatermarkArea#BLOCK} px blocks and their neighbours, as
 * rectangles, cut into pieces of at most {@link #PIECE_PIXELS} (the 200 MP frame is never one 85 MB bitmap). Lossless in the
 * photo's own pixel format - ARGB_8888 (8 bits a channel) or RGBA_1010102 (the 10-bit HEIC's) - and colour space: a piece is
 * the platform's lossless WebP when its decode gives back every word (checked on the spot, else) or the predictive code below
 * ({@link #CODEC_MED}: green and the red / blue differences to green, the median edge predictor of LOCO-I, the residuals deflated;
 * any bit depth, pure Java). A CRC-32 of each piece's words is checked when it is read back.
 * <p>
 * A 10-bit photo also keeps the 8-bit version of its pixels ({@link #eightBit}, Bitmap.copy as the camera's 8-bit files are made),
 * for the JPEG / WebP / 8-bit HEIC written beside or instead of the 10-bit one.
 */
public final class WatermarkPatch {
    public static final int FORMAT_8888 = 0, FORMAT_1010102 = 1;
    public static final int SPACE_SRGB = 0, SPACE_P3 = 1;
    public static final int CODEC_MED = 0, CODEC_WEBP = 1;
    /** Pixels a piece holds at most (16 MB of words). */
    public static final int PIECE_PIXELS = 4 << 20;
    static final byte[] MAGIC = {'S', 'C', 'W', 'M'};
    static final int VERSION = 1;
    /** Lossless WebP's effort (Bitmap.compress quality on Android 11+): the fast end; the result is checked either way. */
    static final int WEBP_EFFORT = 25;
    /** Off: every piece takes the predictive code (tests: the pure-Java path at 8 bits). */
    static volatile boolean webpAllowed = true;

    public final int width, height, format, colourSpace;
    /** The watermark's settings (WatermarkSpec.toJson). */
    public final String spec;
    final List<Piece> pieces;
    /** The 8-bit version of a 10-bit patch (null: this is 8-bit, or none was made). */
    @Nullable private WatermarkPatch eight;

    static final class Piece {
        final Rect rect;
        final int codec, crc;
        final byte[] data;

        Piece(Rect rect, int codec, int crc, byte[] data) {
            this.rect = rect;
            this.codec = codec;
            this.crc = crc;
            this.data = data;
        }
    }

    WatermarkPatch(int width, int height, int format, int colourSpace, String spec, List<Piece> pieces) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.colourSpace = colourSpace;
        this.spec = spec == null ? "" : spec;
        this.pieces = pieces;
    }

    // ───── capture ─────

    /** Whether {@code photo}'s pixels can be kept: a 32-bit software bitmap of the two formats. */
    public static boolean supports(Bitmap photo) {
        return photo != null && !photo.isRecycled() && formatOf(photo) >= 0;
    }

    static int formatOf(Bitmap b) {
        Bitmap.Config c = b.getConfig();
        if (c == Bitmap.Config.ARGB_8888) return FORMAT_8888;
        if (Build.VERSION.SDK_INT >= 33 && c == Bitmap.Config.RGBA_1010102) return FORMAT_1010102;
        return -1;
    }

    static int spaceOf(Bitmap b) {
        if (Build.VERSION.SDK_INT < 26) return SPACE_SRGB;
        ColorSpace cs = b.getColorSpace();
        return cs != null && cs.equals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3)) ? SPACE_P3 : SPACE_SRGB;
    }

    @Nullable
    static ColorSpace space(int code) {
        if (Build.VERSION.SDK_INT < 26) return null;
        return ColorSpace.get(code == SPACE_P3 ? ColorSpace.Named.DISPLAY_P3 : ColorSpace.Named.SRGB);
    }

    /**
     * The pixels of {@code photo} in {@code rects} (before its watermark is drawn) with the watermark's {@code spec}; a 10-bit
     * photo's 8-bit version beside. Throws for a bitmap {@link #supports} refuses.
     */
    public static WatermarkPatch capture(Bitmap photo, List<Rect> rects, String spec) {
        return captureLater(photo, rects, spec, false).get();
    }

    /** Raw pixels of the pieces the coding runs beside the photo's own encoding at most (bytes); more is coded on the spot. */
    static final long LATER_MAX_BYTES = 64L << 20;
    private static final java.util.concurrent.ExecutorService CODER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WatermarkRecord");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /**
     * A record being coded: the pieces are cut from the photo at once (before its watermark is drawn), coded on a worker while the
     * photo is encoded ({@code later}, at most {@link #LATER_MAX_BYTES} of pieces held: a 200 MP frame is coded on the spot, piece by
     * piece), {@link #get} waits for it.
     */
    public static final class Pending {
        @Nullable private final java.util.concurrent.Future<WatermarkPatch> future;
        @Nullable private final WatermarkPatch done;
        @Nullable private final RuntimeException failed;

        Pending(@Nullable java.util.concurrent.Future<WatermarkPatch> future, @Nullable WatermarkPatch done, @Nullable RuntimeException failed) {
            this.future = future;
            this.done = done;
            this.failed = failed;
        }

        /** The record; throws what the coding threw. */
        public WatermarkPatch get() {
            if (failed != null) throw failed;
            if (done != null) return done;
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("watermark record interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException) throw (RuntimeException) c;
                if (c instanceof Error) throw (Error) c;
                throw new IllegalStateException(c);
            }
        }
    }

    public static Pending captureLater(Bitmap photo, List<Rect> rects, String spec, boolean later) {
        final int format = formatOf(photo);
        if (format < 0) throw new IllegalArgumentException("no watermark patch for " + photo.getConfig());
        final int space = spaceOf(photo);
        final int width = photo.getWidth(), height = photo.getHeight();
        final List<Rect> parts = split(rects);
        long bytes = 0;
        for (Rect r : parts) bytes += 4L * r.width() * r.height();
        if (!later || bytes > LATER_MAX_BYTES) {
            List<Piece> pieces = new ArrayList<>(), eightPieces = format == FORMAT_1010102 ? new ArrayList<>() : null;
            for (Rect r : parts) {
                Bitmap part = crop(photo, r);
                try {
                    code(part, r, format, pieces, eightPieces);
                } finally {
                    part.recycle();
                }
            }
            return new Pending(null, made(width, height, format, space, spec, pieces, eightPieces), null);
        }
        final List<Bitmap> cut = new ArrayList<>(parts.size());
        try {
            for (Rect r : parts) cut.add(crop(photo, r));
        } catch (RuntimeException | OutOfMemoryError e) {
            for (Bitmap b : cut) b.recycle();
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
        }
        return new Pending(CODER.submit(() -> {
            List<Piece> pieces = new ArrayList<>(), eightPieces = format == FORMAT_1010102 ? new ArrayList<>() : null;
            try {
                for (int i = 0; i < parts.size(); i++) {
                    code(cut.get(i), parts.get(i), format, pieces, eightPieces);
                    cut.get(i).recycle();
                }
            } finally {
                for (Bitmap b : cut) if (!b.isRecycled()) b.recycle();
            }
            return made(width, height, format, space, spec, pieces, eightPieces);
        }), null, null);
    }

    private static void code(Bitmap part, Rect r, int format, List<Piece> pieces, @Nullable List<Piece> eightPieces) {
        pieces.add(encode(part, r, format));
        if (eightPieces == null) return;
        Bitmap e = part.copy(Bitmap.Config.ARGB_8888, false);
        try {
            eightPieces.add(encode(e, r, FORMAT_8888));
        } finally {
            e.recycle();
        }
    }

    private static WatermarkPatch made(int width, int height, int format, int space, String spec, List<Piece> pieces,
                                       @Nullable List<Piece> eightPieces) {
        WatermarkPatch p = new WatermarkPatch(width, height, format, space, spec, pieces);
        if (eightPieces != null) p.eight = new WatermarkPatch(width, height, FORMAT_8888, space, spec, eightPieces);
        return p;
    }

    /** The 8-bit patch of the photo's 8-bit files (this one when it is 8-bit). */
    public WatermarkPatch eightBit() {
        return eight != null ? eight : this;
    }

    /** {@code rects} cut in row strips of at most {@link #PIECE_PIXELS}. */
    static List<Rect> split(List<Rect> rects) {
        List<Rect> out = new ArrayList<>();
        for (Rect r : rects) {
            if (r.isEmpty()) continue;
            int rows = Math.max(1, PIECE_PIXELS / Math.max(1, r.width()));
            for (int y = r.top; y < r.bottom; y += rows) out.add(new Rect(r.left, y, r.right, Math.min(r.bottom, y + rows)));
        }
        return out;
    }

    private static Paint copyPaint() {
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        p.setFilterBitmap(false);
        p.setDither(false);
        return p;
    }

    /** A bitmap of {@code src}'s config and colour space. */
    static Bitmap like(Bitmap src, int w, int h) {
        if (Build.VERSION.SDK_INT >= 26 && src.getColorSpace() != null)
            return Bitmap.createBitmap(w, h, src.getConfig(), src.hasAlpha(), src.getColorSpace());
        return Bitmap.createBitmap(w, h, src.getConfig());
    }

    /** {@code r} of {@code photo} as its own bitmap, the same words (a sprite copy: equal config and colour space). */
    static Bitmap crop(Bitmap photo, Rect r) {
        Bitmap out = like(photo, r.width(), r.height());
        new Canvas(out).drawBitmap(photo, r, new Rect(0, 0, r.width(), r.height()), copyPaint());
        return out;
    }

    /** The words of a 32-bit bitmap, little-endian (ARGB_8888: R in the low byte; RGBA_1010102: R in the low 10 bits). */
    static int[] words(Bitmap b) {
        ByteBuffer buf = ByteBuffer.allocateDirect(b.getWidth() * b.getHeight() * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.copyPixelsToBuffer(buf);
        buf.rewind();
        int[] out = new int[b.getWidth() * b.getHeight()];
        buf.asIntBuffer().get(out);
        return out;
    }

    static Bitmap bitmap(int[] words, int w, int h, Bitmap.Config config, @Nullable ColorSpace cs) {
        Bitmap b = Build.VERSION.SDK_INT >= 26 && cs != null ? Bitmap.createBitmap(w, h, config, true, cs) : Bitmap.createBitmap(w, h, config);
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.asIntBuffer().put(words, 0, w * h);
        buf.rewind();
        b.copyPixelsFromBuffer(buf);
        return b;
    }

    static int crc(int[] words) {
        CRC32 c = new CRC32();
        byte[] chunk = new byte[4096 * 4];
        for (int i = 0; i < words.length; ) {
            int n = Math.min(4096, words.length - i);
            for (int k = 0; k < n; k++) {
                int v = words[i + k];
                chunk[4 * k] = (byte) v;
                chunk[4 * k + 1] = (byte) (v >>> 8);
                chunk[4 * k + 2] = (byte) (v >>> 16);
                chunk[4 * k + 3] = (byte) (v >>> 24);
            }
            c.update(chunk, 0, 4 * n);
            i += n;
        }
        return (int) c.getValue();
    }

    private static Piece encode(Bitmap part, Rect r, int format) {
        int[] w = words(part);
        int crc = crc(w);
        if (format == FORMAT_8888 && webpAllowed) {
            byte[] webp = webp(part, w);
            if (webp != null) return new Piece(new Rect(r), CODEC_WEBP, crc, webp);
        }
        return new Piece(new Rect(r), CODEC_MED, crc, med(w, part.getWidth(), part.getHeight(), format));
    }

    /** {@code part} as lossless WebP when its decode gives every word back; null otherwise (an encoder that is not lossless here). */
    @Nullable
    @SuppressWarnings("deprecation")
    static byte[] webp(Bitmap part, int[] words) {
        // the words tagged sRGB: a P3 bitmap would carry its profile into the WebP and come back converted
        Bitmap tagged = bitmap(words, part.getWidth(), part.getHeight(), Bitmap.Config.ARGB_8888, space(SPACE_SRGB));
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            boolean ok = Build.VERSION.SDK_INT >= 30 ? tagged.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, WEBP_EFFORT, o)
                    : tagged.compress(Bitmap.CompressFormat.WEBP, 100, o);
            if (!ok) return null;
            byte[] bytes = o.toByteArray();
            Bitmap back = decodeWebp(bytes, part.getWidth(), part.getHeight());
            if (back == null) return null;
            try {
                return java.util.Arrays.equals(words(back), words) ? bytes : null;
            } finally {
                back.recycle();
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            tagged.recycle();
        }
    }

    @Nullable
    static Bitmap decodeWebp(byte[] bytes, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inPremultiplied = false;
        if (Build.VERSION.SDK_INT >= 26) o.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (b == null) return null;
        if (b.getWidth() != w || b.getHeight() != h || b.getConfig() != Bitmap.Config.ARGB_8888) {
            b.recycle();
            return null;
        }
        return b;
    }

    // ───── the predictive code ─────

    /** Bits of the R, G, B, A channels of a word, and where they start. */
    static int[] bits(int format) {
        return format == FORMAT_1010102 ? new int[]{10, 10, 10, 2} : new int[]{8, 8, 8, 8};
    }

    static int[] shifts(int format) {
        return format == FORMAT_1010102 ? new int[]{0, 10, 20, 30} : new int[]{0, 8, 16, 24};
    }

    /** The planes coded: G, R - G, B - G (mod 2^bits), A. */
    static int[][] planes(int[] words, int format) {
        final int[] b = bits(format), s = shifts(format);
        final int n = words.length, mask = (1 << b[0]) - 1, am = (1 << b[3]) - 1;
        int[] g = new int[n], rg = new int[n], bg = new int[n], a = new int[n];
        for (int i = 0; i < n; i++) {
            int w = words[i];
            int R = (w >>> s[0]) & mask, G = (w >>> s[1]) & mask, B = (w >>> s[2]) & mask;
            g[i] = G;
            rg[i] = (R - G) & mask;
            bg[i] = (B - G) & mask;
            a[i] = (w >>> s[3]) & am;
        }
        return new int[][]{g, rg, bg, a};
    }

    static int[] unplanes(int[][] p, int format) {
        final int[] b = bits(format), s = shifts(format);
        final int n = p[0].length, mask = (1 << b[0]) - 1;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int G = p[0][i], R = (p[1][i] + G) & mask, B = (p[2][i] + G) & mask;
            out[i] = (R << s[0]) | (G << s[1]) | (B << s[2]) | (p[3][i] << s[3]);
        }
        return out;
    }

    /** LOCO-I's median edge detector from left a, above b, above-left c; the first row takes the left, the first column above. */
    static int predict(int[] v, int i, int x, int y, int w) {
        if (y == 0) return x == 0 ? 0 : v[i - 1];
        if (x == 0) return v[i - w];
        int a = v[i - 1], b = v[i - w], c = v[i - w - 1];
        int mx = Math.max(a, b), mn = Math.min(a, b);
        return c >= mx ? mn : c <= mn ? mx : a + b - c;
    }

    static byte[] med(int[] words, int w, int h, int format) {
        final int[] bt = bits(format);
        int[][] pl = planes(words, format);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(words.length);
        Deflater d = new Deflater(6);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, d, 1 << 16)) {
            byte[] row = new byte[w * 2];
            for (int c = 0; c < 4; c++) {
                final int bits = bt[c], m = (1 << bits) - 1, half = 1 << (bits - 1);
                final int[] v = pl[c];
                // the low bytes of every sample, then (10 bits) the high bits: two planes deflate better than interleaved words
                for (int pass = 0; pass < (bits > 8 ? 2 : 1); pass++) {
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int i = y * w + x;
                            int r = (v[i] - predict(v, i, x, y, w)) & m;
                            int sgn = r >= half ? r - (m + 1) : r;
                            int z = sgn >= 0 ? 2 * sgn : -2 * sgn - 1;
                            row[x] = (byte) (pass == 0 ? z : z >>> 8);
                        }
                        out.write(row, 0, w);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            d.end();
        }
        return bytes.toByteArray();
    }

    static int[] unmed(byte[] data, int w, int h, int format) throws IOException {
        final int[] bt = bits(format);
        final int n = w * h;
        int[][] pl = new int[4][];
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data), new java.util.zip.Inflater(), 1 << 16))) {
            byte[] lo = new byte[n], hi = new byte[n];
            for (int c = 0; c < 4; c++) {
                final int bits = bt[c], m = (1 << bits) - 1;
                in.readFully(lo);
                if (bits > 8) in.readFully(hi);
                int[] v = new int[n];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = y * w + x;
                        int z = (lo[i] & 255) | (bits > 8 ? (hi[i] & 255) << 8 : 0);
                        int sgn = (z & 1) == 0 ? z >>> 1 : -((z + 1) >>> 1);
                        v[i] = (predict(v, i, x, y, w) + sgn) & m;
                    }
                }
                pl[c] = v;
            }
        }
        return unplanes(pl, format);
    }

    // ───── bytes ─────

    /** The record as stored: «SCWM», version, size, format, colour space, spec, then each piece (rect, codec, CRC, data). */
    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 64, encodedBytes() + 1024));
            DataOutputStream o = new DataOutputStream(bytes);
            o.write(MAGIC);
            o.writeByte(VERSION);
            o.writeInt(width);
            o.writeInt(height);
            o.writeByte(format);
            o.writeByte(colourSpace);
            byte[] s = spec.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            o.writeInt(s.length);
            o.write(s);
            o.writeInt(pieces.size());
            for (Piece p : pieces) {
                o.writeInt(p.rect.left);
                o.writeInt(p.rect.top);
                o.writeInt(p.rect.width());
                o.writeInt(p.rect.height());
                o.writeByte(p.codec);
                o.writeInt(p.crc);
                o.writeInt(p.data.length);
                o.write(p.data);
            }
            o.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The record of {@link #toBytes}; throws for bytes that are not one (or not whole). */
    public static WatermarkPatch fromBytes(byte[] b) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("not a watermark record");
        int version = in.readUnsignedByte();
        if (version != VERSION) throw new IOException("watermark record version " + version);
        int width = in.readInt(), height = in.readInt();
        int format = in.readUnsignedByte(), space = in.readUnsignedByte();
        if (width <= 0 || height <= 0 || format > FORMAT_1010102 || space > SPACE_P3) throw new IOException("bad watermark record");
        int sl = in.readInt();
        if (sl < 0 || sl > 1 << 16) throw new IOException("bad watermark spec");
        byte[] s = new byte[sl];
        in.readFully(s);
        int n = in.readInt();
        if (n < 0 || n > 1 << 20) throw new IOException("bad watermark pieces");
        List<Piece> pieces = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int x = in.readInt(), y = in.readInt(), w = in.readInt(), h = in.readInt();
            int codec = in.readUnsignedByte(), crc = in.readInt(), len = in.readInt();
            if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > width || y + h > height || (long) w * h > PIECE_PIXELS
                    || codec > CODEC_WEBP || len < 0 || len > b.length)
                throw new IOException("bad watermark piece " + i);
            byte[] data = new byte[len];
            in.readFully(data);
            pieces.add(new Piece(new Rect(x, y, x + w, y + h), codec, crc, data));
        }
        return new WatermarkPatch(width, height, format, space, new String(s, java.nio.charset.StandardCharsets.UTF_8), pieces);
    }

    public List<Rect> rects() {
        List<Rect> out = new ArrayList<>(pieces.size());
        for (Piece p : pieces) out.add(new Rect(p.rect));
        return Collections.unmodifiableList(out);
    }

    public long pixels() {
        long n = 0;
        for (Piece p : pieces) n += (long) p.rect.width() * p.rect.height();
        return n;
    }

    public long encodedBytes() {
        long n = 0;
        for (Piece p : pieces) n += p.data.length;
        return n;
    }

    // ───── restoring ─────

    /** The words of piece {@code i}, checked against its CRC. */
    int[] wordsOf(int i) throws IOException {
        Piece p = pieces.get(i);
        int w = p.rect.width(), h = p.rect.height();
        int[] words;
        if (p.codec == CODEC_WEBP) {
            Bitmap b = decodeWebp(p.data, w, h);
            if (b == null) throw new IOException("watermark piece " + i + " not decoded");
            try {
                words = words(b);
            } finally {
                b.recycle();
            }
        } else {
            words = unmed(p.data, w, h, format);
        }
        if (crc(words) != p.crc) throw new IOException("watermark piece " + i + ": CRC mismatch");
        return words;
    }

    /** Piece {@code i} as a bitmap of the patch's format and colour space. */
    Bitmap pieceBitmap(int i) throws IOException {
        Piece p = pieces.get(i);
        int[] words = wordsOf(i);
        if (format == FORMAT_1010102 && Build.VERSION.SDK_INT < 33) {
            // no 10-bit bitmaps before Android 13: the 8-bit value of each channel
            for (int k = 0; k < words.length; k++) {
                int v = words[k];
                int r = ((v & 1023) * 255 + 511) / 1023, g = (((v >>> 10) & 1023) * 255 + 511) / 1023, bl = (((v >>> 20) & 1023) * 255 + 511) / 1023;
                words[k] = r | g << 8 | bl << 16 | 0xFF000000;
            }
            return bitmap(words, p.rect.width(), p.rect.height(), Bitmap.Config.ARGB_8888, space(colourSpace));
        }
        Bitmap.Config config = format == FORMAT_1010102 ? Bitmap.Config.RGBA_1010102 : Bitmap.Config.ARGB_8888;
        return bitmap(words, p.rect.width(), p.rect.height(), config, space(colourSpace));
    }

    /** Whether every piece decodes and matches its CRC (the editor checks before it offers «Убрать»). */
    public boolean verify() {
        try {
            for (int i = 0; i < pieces.size(); i++) wordsOf(i);
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return false;
        }
    }

    /**
     * The kept pixels back into {@code photo} - the whole photo at its own size ({@link #width} x {@link #height}): the same words
     * where the config and colour space are the patch's, else Skia's conversion of them (a 10-bit patch in an 8-bit decode).
     */
    public void restore(Bitmap photo) throws IOException {
        paint(photo, new Rect(0, 0, width, height));
    }

    /**
     * The kept pixels into {@code target}, a picture of the photo's region {@code src} (stored px of the {@link #width} x
     * {@link #height} photo) at the target's size: pixel for pixel when the target is that region at full size, else scaled
     * (bilinear, halved first while more than 2x smaller) - a preview or a reduced export.
     */
    public void paint(Bitmap target, Rect src) throws IOException {
        if (src.isEmpty() || !target.isMutable()) throw new IllegalArgumentException("not a mutable picture of a region");
        final double sx = target.getWidth() / (double) src.width(), sy = target.getHeight() / (double) src.height();
        final boolean exact = target.getWidth() == src.width() && target.getHeight() == src.height();
        Canvas canvas = new Canvas(target);
        Paint copy = copyPaint();
        Paint scaled = copyPaint();
        scaled.setFilterBitmap(true);
        for (int i = 0; i < pieces.size(); i++) {
            Rect r = pieces.get(i).rect;
            if (!Rect.intersects(r, src)) continue;
            Bitmap b = pieceBitmap(i);
            try {
                if (exact) {
                    canvas.drawBitmap(b, r.left - src.left, r.top - src.top, copy);
                    continue;
                }
                RectF dst = new RectF((float) ((r.left - src.left) * sx), (float) ((r.top - src.top) * sy),
                        (float) ((r.right - src.left) * sx), (float) ((r.bottom - src.top) * sy));
                Bitmap s = b;
                while (s.getWidth() > 2 * Math.max(1, dst.width()) && s.getHeight() > 2 * Math.max(1, dst.height())) {
                    Bitmap half = Bitmap.createScaledBitmap(s, Math.max(1, s.getWidth() / 2), Math.max(1, s.getHeight() / 2), true);
                    if (s != b) s.recycle();
                    s = half;
                }
                canvas.drawBitmap(s, null, dst, scaled);
                if (s != b) s.recycle();
            } finally {
                b.recycle();
            }
        }
    }

    @Override
    public String toString() {
        return "WatermarkPatch{" + width + "x" + height + (format == FORMAT_1010102 ? " 10-bit" : " 8-bit")
                + (colourSpace == SPACE_P3 ? " P3" : "") + ", " + pieces.size() + " pieces, " + pixels() + " px, " + encodedBytes() + " bytes}";
    }
}
