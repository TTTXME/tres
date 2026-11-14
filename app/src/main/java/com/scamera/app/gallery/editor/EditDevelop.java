package com.scamera.app.gallery.editor;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.scamera.app.gallery.dng.DngRaw;
import com.scamera.app.processing.DevelopRecord;
import com.scamera.app.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.TreeMap;

/**
 * P665 «Проявка» (owner, 2026-11-13: «доделай симуляцию плёнки в нашем проявщике в галерее, перенеси все настройки из нашей
 * камеры в проявщик» - «Все настройки обработки»): a DNG of ours developed by the camera's own post pipeline (DevelopRun) with the
 * shot's settings (its DevelopRecord) and the ones changed in the editor. The DNG holds the merge's RGB as a Bayer mosaic over its
 * headroom (P165): the RGB the pipeline takes is the DNG's level times the headroom, demosaiced here -
 * <ul>
 * <li>the preview: the DNG binned so that a 2x2 cell of it is one pixel of at most {@link #PREVIEW_SIDE} (a cell's R, mean G, B:
 * no interpolation), developed in about a second; the settings' changes re-develop it (the editor debounces them);</li>
 * <li>the copy and the zoom: the whole DNG demosaiced (Malvar-He-Cutler, {@link #mhc}) and developed once per settings (the last
 * one kept), the editor's own sliders then work on that picture as on a JPEG.</li>
 * </ul>
 * Each develop holds ScamLab.LOCK: a shot being processed finishes first.
 */
final class EditDevelop {
    private static final String TAG = "EditDevelop";
    /** The preview develop's longer side at most (px). */
    static final int PREVIEW_SIDE = 2048;
    /** Above this many pixels the RGB goes to the pipeline as halves (6 B a pixel instead of 12), as the merge's large shots do. */
    static final long HALF_ABOVE = 16_000_000L;

    final DevelopRecord record;
    private final File dng;
    /** The preview's RGB (float, x headroom), its size; made once. */
    @Nullable private float[] previewRgb;
    private int previewW, previewH;
    /** The DNG's mosaic size (the full develop's RGB). */
    private int dngW, dngH;
    /** The last full-size develop and its settings' key. */
    @Nullable private Bitmap full;
    @Nullable private String fullKey;
    private final Object fullLock = new Object();

    private EditDevelop(DevelopRecord record, File dng) {
        this.record = record;
        this.dng = dng;
    }

    /** A developer of {@code dng} with {@code record} (the tests' panels; {@link #open} for a photo). */
    static EditDevelop of(DevelopRecord record, File dng) {
        return new EditDevelop(record, dng);
    }

    /** The developer of {@code source} (a DNG of ours with its record), or null: the editor's own RAW route only. */
    @Nullable
    static EditDevelop open(EditSource source) {
        if (source.route != EditSource.Route.DNG || source.copyFile() == null) return null;
        final DevelopRecord r = DevelopRecord.read(source.copyFile());
        if (r == null) return null;
        Log.i(TAG, "develop record of " + source.name + ": build " + r.meta.optInt("build") + ", headroom " + r.headroom()
                + ", maps: clip " + (r.clipFlags != null) + ", frames " + (r.frames != null) + ", highlight mask " + (r.hlMask != null));
        return new EditDevelop(r, source.copyFile());
    }

    /** A key of {@code changes} (the full develop is kept per key). */
    static String key(Map<String, Object> changes) {
        return new TreeMap<>(changes).toString();
    }

    // ---------------------------------------------------------------- the runs

    /** The preview develop with {@code changes} (a worker thread). */
    Bitmap preview(Map<String, Object> changes) throws Exception {
        synchronized (this) {
            if (previewRgb == null) {
                final int[] size = DngRaw.probe(EditSource.map(dng)).size(1);
                dngW = size[0];
                dngH = size[1];
                final DngRaw.Image img = decode(binFor(size));
                previewW = img.width / 2;
                previewH = img.height / 2;
                previewRgb = superpixel(img.linear, img.width, img.height, img.phase, record.headroom());
            }
        }
        final ByteBuffer rgb = allocate((long) previewW * previewH, false);
        rgb.asFloatBuffer().put(previewRgb);
        return com.scamera.app.processing.opengl.postpipeline.DevelopRun.develop(record, rgb, previewW, previewH, false, changes);
    }

    /** Full-size pixels per preview pixel (after the first preview; 1 before). */
    synchronized double scale() {
        return previewW > 0 && dngW > 0 ? dngW / (double) previewW : 1.0;
    }

    /** The full-size develop with {@code changes}, kept for the next caller with the same changes (a worker thread). */
    Bitmap full(Map<String, Object> changes) throws Exception {
        final String k = key(changes);
        synchronized (fullLock) {
            if (full != null && !full.isRecycled() && k.equals(fullKey)) return full;
            // not recycled: a copy or the zoom may still read it (their readers do not own it); the GC takes it after them
            full = null;
            fullKey = null;
            final DngRaw.Image img = decode(1);
            final long pixels = (long) img.width * img.height;
            final boolean half = pixels > HALF_ABOVE;
            final ByteBuffer rgb = allocate(pixels, half);
            final long t0 = System.nanoTime();
            if (half) mhc(img.linear, img.width, img.height, img.phase, record.headroom(), null, rgb.asShortBuffer());
            else mhc(img.linear, img.width, img.height, img.phase, record.headroom(), rgb.asFloatBuffer(), null);
            Log.i(TAG, "demosaiced " + img.width + "x" + img.height + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            final Bitmap b = com.scamera.app.processing.opengl.postpipeline.DevelopRun.develop(record, rgb, img.width, img.height, half,
                    changes);
            full = b;
            fullKey = k;
            return b;
        }
    }

    /** Drops the kept full-size develop (the editor is left). */
    void release() {
        synchronized (fullLock) {
            full = null; // an export still running keeps its reference
            fullKey = null;
        }
        synchronized (this) {
            previewRgb = null;
        }
    }

    private DngRaw.Image decode(int bin) throws IOException {
        final DngRaw.Image img = DngRaw.decodeLinear(EditSource.map(dng), bin);
        if (img.info.kind != DngRaw.KIND_BAYER || img.info.cell != 1 || img.linear == null)
            throw new IOException("develop: not a Bayer DNG");
        return img;
    }

    /** The bin whose 2x2 cells give at most {@link #PREVIEW_SIDE} on the longer side. */
    static int binFor(int[] size) {
        int bin = 1;
        while (Math.max(size[0], size[1]) / (2 * bin) > PREVIEW_SIDE && bin < 16) bin *= 2;
        return bin;
    }

    /** A direct buffer of {@code pixels} RGB (float32, or halves) from the Allocator (the pipeline frees it as the merge's). */
    private static ByteBuffer allocate(long pixels, boolean half) throws IOException {
        final long bytes = pixels * (half ? 6L : 12L);
        if (bytes > Integer.MAX_VALUE - 64) throw new IOException("develop: picture too large");
        final ByteBuffer b = com.scamera.app.util.Allocator.allocate((int) bytes);
        if (b == null) throw new IOException("develop: no memory for " + bytes / (1 << 20) + " MB");
        b.order(ByteOrder.nativeOrder());
        b.clear();
        return b;
    }

    // ---------------------------------------------------------------- demosaic

    /** The colour (0 R, 1 G, 2 B) of the mosaic at (x, y) for DngRaw's phase codes (RGGB, GRBG, GBRG, BGGR). */
    static int colour(int phase, int x, int y) {
        final int i = ((y & 1) << 1) | (x & 1);
        switch (phase) {
            case DngRaw.GRBG: return i == 1 ? 0 : i == 2 ? 2 : 1;
            case DngRaw.GBRG: return i == 1 ? 2 : i == 2 ? 0 : 1;
            case DngRaw.BGGR: return i == 0 ? 2 : i == 3 ? 0 : 1;
            default: return i == 0 ? 0 : i == 3 ? 2 : 1;
        }
    }

    /** RGB of every 2x2 cell (its R, the mean of its G, its B) times {@code gain}: {@code (w / 2) x (h / 2)} pixels. */
    static float[] superpixel(float[] mosaic, int w, int h, int phase, float gain) {
        final int ow = w / 2, oh = h / 2;
        final float[] out = new float[ow * oh * 3];
        com.scamera.app.util.ParallelWork.forEach(oh, y -> {
            for (int x = 0; x < ow; x++) {
                float r = 0, g = 0, b = 0;
                for (int k = 0; k < 4; k++) {
                    final int sx = 2 * x + (k & 1), sy = 2 * y + (k >> 1);
                    final float v = mosaic[sy * w + sx];
                    final int c = colour(phase, sx, sy);
                    if (c == 0) r = v;
                    else if (c == 2) b = v;
                    else g += v;
                }
                final int o = (y * ow + x) * 3;
                out[o] = r * gain;
                out[o + 1] = g * .5f * gain;
                out[o + 2] = b * gain;
            }
        });
        return out;
    }

    /** The mirror of {@code i} into 0..n-1 about the edge sample (keeps the CFA parity). */
    private static int mirror(int i, int n) {
        if (i < 0) i = -i;
        if (i >= n) i = 2 * (n - 1) - i;
        return Math.max(0, Math.min(n - 1, i));
    }

    /**
     * Malvar-He-Cutler (2004) demosaic of {@code mosaic} (w x h, DngRaw's {@code phase}) times {@code gain}, never below 0, into
     * {@code f} (float RGB) or {@code half} (IEEE halves RGB); the borders mirror about the edge sample.
     */
    static void mhc(float[] m, int w, int h, int phase, float gain, @Nullable FloatBuffer f, @Nullable ShortBuffer half) {
        com.scamera.app.util.ParallelWork.forEach(h, y -> {
            final float[] row = new float[w * 3];
            final float[] rgb = new float[3];
            for (int x = 0; x < w; x++) {
                mhcAt(m, w, h, phase, x, y, rgb);
                row[3 * x] = Math.max(0f, rgb[0] * gain);
                row[3 * x + 1] = Math.max(0f, rgb[1] * gain);
                row[3 * x + 2] = Math.max(0f, rgb[2] * gain);
            }
            if (f != null) {
                final FloatBuffer d = f.duplicate();
                d.position(y * w * 3);
                d.put(row);
            } else if (half != null) {
                final ShortBuffer d = half.duplicate();
                d.position(y * w * 3);
                for (float v : row) d.put(toHalf(v));
            }
        });
    }

    /** One pixel of {@link #mhc} (without the gain) into {@code out}. */
    static void mhcAt(float[] m, int w, int h, int phase, int x, int y, float[] out) {
        final int c = colour(phase, x, y);
        final float v = at(m, w, h, x, y);
        if (c == 1) {
            out[1] = v;
            // the colour to the left / right of this green
            final int horizontal = colour(phase, x + 1, y);
            final float n2 = at(m, w, h, x, y - 2) + at(m, w, h, x, y + 2), e2 = at(m, w, h, x - 2, y) + at(m, w, h, x + 2, y);
            final float d1 = at(m, w, h, x - 1, y - 1) + at(m, w, h, x + 1, y - 1) + at(m, w, h, x - 1, y + 1) + at(m, w, h, x + 1, y + 1);
            final float hor = at(m, w, h, x - 1, y) + at(m, w, h, x + 1, y), ver = at(m, w, h, x, y - 1) + at(m, w, h, x, y + 1);
            // the colour in this row: 5 C + 4 (W + E) - (W2 + E2) - diagonals + (N2 + S2) / 2, / 8; the other one transposed
            final float inRow = (5 * v + 4 * hor - e2 - d1 + .5f * n2) / 8f;
            final float inCol = (5 * v + 4 * ver - n2 - d1 + .5f * e2) / 8f;
            out[horizontal] = inRow;
            out[2 - horizontal] = inCol;
        } else {
            out[c] = v;
            final float g4 = at(m, w, h, x - 1, y) + at(m, w, h, x + 1, y) + at(m, w, h, x, y - 1) + at(m, w, h, x, y + 1);
            final float c4 = at(m, w, h, x - 2, y) + at(m, w, h, x + 2, y) + at(m, w, h, x, y - 2) + at(m, w, h, x, y + 2);
            final float d4 = at(m, w, h, x - 1, y - 1) + at(m, w, h, x + 1, y - 1) + at(m, w, h, x - 1, y + 1) + at(m, w, h, x + 1, y + 1);
            out[1] = (4 * v + 2 * g4 - c4) / 8f;
            out[2 - c] = (6 * v + 2 * d4 - 1.5f * c4) / 8f;
        }
    }

    private static float at(float[] m, int w, int h, int x, int y) {
        return m[mirror(y, h) * w + mirror(x, w)];
    }

    /** IEEE half of {@code v} (round to nearest even; above the largest half: infinity; NaN kept). */
    static short toHalf(float v) {
        final int bits = Float.floatToIntBits(v);
        final int sign = (bits >>> 16) & 0x8000;
        int exp = (bits >>> 23) & 0xFF;
        int mant = bits & 0x7FFFFF;
        if (exp == 0xFF) return (short) (sign | 0x7C00 | (mant != 0 ? 0x200 : 0));
        exp = exp - 127 + 15;
        if (exp >= 0x1F) return (short) (sign | 0x7C00);
        if (exp <= 0) {
            if (exp < -10) return (short) sign;
            mant |= 0x800000;
            final int shift = 14 - exp;
            int h = mant >> shift;
            final int rem = mant & ((1 << shift) - 1), halfway = 1 << (shift - 1);
            if (rem > halfway || rem == halfway && (h & 1) != 0) h++;
            return (short) (sign | h);
        }
        int h = (exp << 10) | (mant >> 13);
        final int rem = mant & 0x1FFF;
        if (rem > 0x1000 || rem == 0x1000 && (h & 1) != 0) h++;
        return (short) (sign | h);
    }
}
