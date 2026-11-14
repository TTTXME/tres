package com.scamera.app.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.Random;

/**
 * P679: a LOFIC pair merged into one frame before our burst merge (LoficMerge): a synthetic scene whose highlights run far over
 * the Long clip, Long (14 bit) and Short (10 bit, k times less) reads with the release's noise figures (Long: 14.5 DN of read
 * noise and 195 DN at the clip at ISO 200; Short: 0.75 DN of read noise, a read 12.55 times shorter). Checked: the highlights the
 * merge holds over the Long clip, no tonal seam (the bias stays within the noise everywhere), and no noise step at the merge
 * point (the noise grows smoothly through the Long weight's taper); the encoding for the worker and the result's scaling back.
 * <p>
 * With LOFIC_NCH_DIR set, the test also writes a synthetic burst (four pairs, merged) and the same scene's Long frames alone as
 * NCH v11 files for the merge worker (the host check of the report: tools/ci's worker build, SCAM_HYBRID=1 ... --scam-capture).
 */
public class LoficMergeTest {
    static final double A_LONG = 2.46, B_LONG = 14.5 * 14.5; // Long noise: var = a L + b (DN^2), ISO 200 of the release
    static final int RANGE = 16383 - 1024;
    static final float[] BLACK = {1024, 1024, 1024, 1024};

    /** Long and Short codes of a true level {@code t} (Long DN over black). */
    static int[] read(double t, double k, Random r) {
        final double l = t + r.nextGaussian() * Math.sqrt(A_LONG * Math.max(0, t) + B_LONG);
        final double s = t / k + r.nextGaussian() * Math.sqrt(12.55 * A_LONG * Math.max(0, t / k) / k + 0.5625);
        return new int[]{(int) Math.max(0, Math.min(16383, Math.round(1024 + l))), (int) Math.max(0, Math.min(1023, Math.round(64 + s)))};
    }

    static double merged(double t, double k, Random r, int blend) {
        final int[] c = read(t, k, r);
        return LoficMerge.mergePixel(c[0] - 1024, c[1] - 64, c[0] >= 16381, c[1] >= 1021, k, A_LONG, B_LONG, RANGE, blend);
    }

    @Test
    public void noiseFreeIsExactOnBothSidesOfTheClip() {
        final double k = 100;
        for (double t = 0; t < 90 * RANGE; t += 997) {
            final double l = Math.min(t, RANGE), s = Math.min(t / k, 959);
            final double m = LoficMerge.mergePixel(l, s, t >= RANGE, t / k >= 959, k, A_LONG, B_LONG, RANGE, LoficMerge.BLEND_NOISE);
            assertEquals("t " + t, Math.min(t, k * 959), m, 1e-6 * Math.max(1, t));
        }
    }

    /** Per 1 %-wide bin of the true level around the Long clip: the bias and the noise of M against t, and of the Long read. */
    static double[][] bins(double k, int blend, int lo, int hi) {
        final Random r = new Random(7);
        final int n = hi - lo;
        final double[][] out = new double[n][];
        for (int b = 0; b < n; b++) {
            final double t = RANGE * (lo + b + 0.5) / 100.0;
            double sum = 0, sum2 = 0;
            final int samples = 20000;
            for (int i = 0; i < samples; i++) {
                final double d = merged(t, k, r, blend) - t;
                sum += d;
                sum2 += d * d;
            }
            final double mean = sum / samples, std = Math.sqrt(sum2 / samples - mean * mean);
            out[b] = new double[]{t, mean, std};
        }
        return out;
    }

    @Test
    public void noSeamAndNoNoiseStepAtTheMergePoint() {
        for (double k : new double[]{60, 833}) {
            final double[][] b = bins(k, LoficMerge.BLEND_NOISE, 40, 160);
            double worstStep = 1, worstBias = 0;
            for (int i = 0; i < b.length; i++) {
                final double t = b[i][0], bias = b[i][1], std = b[i][2];
                // no tonal seam: the bias within a tenth of the noise (and of 0.3 % of the level)
                worstBias = Math.max(worstBias, Math.abs(bias) / Math.max(std, 1e-9));
                assertTrue(String.format(Locale.ROOT, "k %.0f t %.0f: bias %.1f DN, noise %.1f", k, t, bias, std),
                        Math.abs(bias) <= Math.max(0.1 * std, 0.003 * t));
                if (i > 0) worstStep = Math.max(worstStep, std / b[i - 1][2]);
            }
            // the noise grows through the taper: no 1 %-step of the level raises it by more than 20 %
            assertTrue(String.format(Locale.ROOT, "k %.0f: the noise rises x%.2f within 1 %% of the level", k, worstStep), worstStep < 1.2);
            System.out.printf(Locale.ROOT, "LOFIC k %.0f noise-weighted: largest noise rise between 1 %% bins x%.3f, largest |bias| / noise %.3f%n",
                    k, worstStep, worstBias);
        }
        // the release's fixed ramp, for the report: its noise at the Long clip side of the ramp
        final double[][] ramp = bins(833, LoficMerge.BLEND_RAMP, 40, 160), noise = bins(833, LoficMerge.BLEND_NOISE, 40, 160);
        double rampStep = 1;
        for (int i = 1; i < ramp.length; i++) rampStep = Math.max(rampStep, ramp[i][2] / ramp[i - 1][2]);
        // at 80 % of the Long range (inside the release's ramp) the noise-weighted merge keeps the Long read's noise
        final int at80 = 40;
        System.out.printf(Locale.ROOT, "LOFIC k 833: noise at 80 %% of the Long clip %.0f DN noise-weighted, %.0f DN fixed ramp; fixed ramp's"
                + " largest rise between 1 %% bins x%.3f%n", noise[at80][2], ramp[at80][2], rampStep);
        assertTrue(noise[at80][2] < ramp[at80][2]);
    }

    @Test
    public void highlightsOverTheLongClip() {
        final Random r = new Random(11);
        for (double k : new double[]{60, 212, 833}) {
            final double reach = k * 959;
            for (double f : new double[]{2, 8, 0.9 * reach / RANGE}) {
                final double t = f * RANGE;
                if (t >= reach) continue;
                double sum = 0;
                for (int i = 0; i < 4000; i++) sum += merged(t, k, r, LoficMerge.BLEND_NOISE);
                assertEquals("k " + k + " at x" + f + " of the clip", t, sum / 4000, 0.01 * t);
            }
            System.out.printf(Locale.ROOT, "LOFIC k %.0f: highlights held up to x%.1f of the Long clip (%.2f stops; the Long frame alone: x1)%n",
                    k, reach / RANGE, Math.log(reach / RANGE) / Math.log(2));
        }
    }

    @Test
    public void encodingFitsSixteenBits() {
        assertNull(LoficMerge.encoding(10, BLACK, 16383)); // k x 959 under the Long clip: nothing to add
        final LoficMerge.Encoding e = LoficMerge.encoding(833, BLACK, 16383);
        assertNotNull(e);
        assertEquals(6, e.headroomLog2); // 833 x 959 / 15359 = 52 -> 64
        // the brightest merged level codes within 16 bits, the Long clip at 1 / H of the range
        final double top = 833 * 959;
        assertTrue(1024 + top / e.g[0] <= 65535);
        assertEquals(1.0 / 64, (RANGE / e.g[0]) / (65535 - 1024), 1e-9);
        final float[] n = LoficMerge.noise(2e-4f, 1e-6f, e);
        assertEquals(2e-4f / 64, n[0], 1e-12);
        assertEquals(1e-6f / 4096, n[1], 1e-15);
    }

    @Test
    public void mergesAPackedPairInPlace() {
        final int w = 64, rows = 4;
        final ByteBuffer l = ByteBuffer.allocateDirect(w * rows * 2).order(ByteOrder.nativeOrder());
        final ByteBuffer s = ByteBuffer.allocateDirect(w * rows * 10 / 8);
        final double k = 100;
        final double[] t = new double[w * rows];
        for (int y = 0; y < rows; y++)
            for (int g = 0; g < w / 4; g++) {
                final int[] sv = new int[4];
                for (int i = 0; i < 4; i++) {
                    final int x = 4 * g + i;
                    t[y * w + x] = (x + 1) * 1400.0; // up to 5.8 x the Long clip
                    l.putShort(2 * (y * w + x), (short) Math.min(16383, Math.round(1024 + t[y * w + x])));
                    sv[i] = (int) Math.min(1023, Math.round(64 + t[y * w + x] / k));
                }
                com.scamera.app.capture.XiaomiLoficTest.pack10(s, y * w * 10 / 8 + g * 5, sv);
            }
        final LoficMerge.Encoding e = LoficMerge.encoding(k, BLACK, 16383);
        final LoficMerge.Stats st = LoficMerge.merge(l, s, w, rows, k, 1e-4f, 1e-7f, e, LoficMerge.BLEND_NOISE);
        assertTrue(st.toString(), st.longClipped > 0 && st.recovered == st.longClipped);
        for (int i = 0; i < w * rows; i++) {
            final double m = ((l.getShort(2 * i) & 0xffff) - 1024) * e.g[0];
            // within a code step and the Short read's own 1 DN quantisation (k DN)
            assertEquals("pixel " + i, t[i], m, e.g[0] + (t[i] > 0.7 * RANGE ? k : 1));
        }
        assertEquals(64 * 1400.0 / RANGE, st.maxLevel, 0.05 * st.maxLevel);
        // the per-pair ratio on the plain Long frame (here too few pixels for a measure)
        assertEquals(0, LoficMerge.ratioPlain(l, s, w, rows, BLACK, 1), 0);
    }

    @Test
    public void halfScalingIsExact() {
        for (int v = 0; v < 65536; v++) {
            final short h = (short) v;
            final float f = LoficMerge.toFloat(h);
            if (Float.isNaN(f)) continue;
            assertEquals("half " + v, h, LoficMerge.toHalf(f)); // round trip
            for (int e : new int[]{1, 3, 6, 10}) {
                final short scaled = LoficMerge.scaleHalf(h, e);
                final float want = Math.abs(f) * (1 << e) >= 65520f ? Math.copySign(65504f, f) : f * (1 << e);
                if (Float.isInfinite(f)) continue;
                assertEquals("half " + v + " x2^" + e, want, LoficMerge.toFloat(scaled), 0f);
            }
        }
        final ByteBuffer rgb = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        rgb.putFloat(0, 0.5f).putFloat(4, 1f).putFloat(8, 0.001f);
        LoficMerge.scale(rgb, false, 6);
        assertEquals(32f, rgb.getFloat(0), 0f);
        assertEquals(64f, rgb.getFloat(4), 0f);
    }

    // ---------------------------------------------------------------- the worker's host check (only with LOFIC_NCH_DIR)

    /** A burst of four pairs of a static scene as NCH v11 (merged: {@code lofic}; else the Long frames alone). */
    static void writeNch(File file, boolean lofic, double k, int w, int h) throws Exception {
        final int n = 4;
        final LoficMerge.Encoding e = LoficMerge.encoding(k, BLACK, 16383);
        final float slope = (float) (A_LONG / RANGE), offset = (float) (B_LONG / ((double) RANGE * RANGE));
        final ByteBuffer head = ByteBuffer.allocate(128 + 32 * n).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(0x3143484e).putInt(11).putInt(w).putInt(h).putInt(3 /* BGGR */).putInt(n).putFloat(lofic ? 65535f : 16383f);
        for (float b : BLACK) head.putFloat(b);
        head.putInt(lofic ? 32 : 0).putInt(0).putInt(1).putInt(1).putInt(0).putInt(0);
        head.position(128);
        final float[] nz = lofic ? LoficMerge.noise(slope, offset, e) : new float[]{slope, offset};
        for (int i = 0; i < n; i++)
            head.putInt(1).putFloat(1f).putInt(200).putFloat(nz[0]).putFloat(nz[1]).putFloat(-16.6f * i).putInt(0).putInt(0);
        head.flip();
        try (FileChannel ch = new FileOutputStream(file).getChannel()) {
            ch.write(head);
            final Random r = new Random(5);
            for (int f = 0; f < n; f++) {
                final ByteBuffer l = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
                final ByteBuffer s = ByteBuffer.allocateDirect(w * h * 10 / 8);
                for (int y = 0; y < h; y++)
                    for (int g = 0; g < w / 4; g++) {
                        final int[] sv = new int[4];
                        for (int i = 0; i < 4; i++) {
                            final int x = 4 * g + i;
                            final int[] c = read(scene(x, y, w, h), k, r);
                            l.putShort(2 * (y * w + x), (short) c[0]);
                            sv[i] = c[1];
                        }
                        com.scamera.app.capture.XiaomiLoficTest.pack10(s, y * w * 10 / 8 + g * 5, sv);
                    }
                if (lofic) LoficMerge.merge(l, s, w, h, k, slope, offset, e, LoficMerge.BLEND_NOISE);
                final ByteBuffer le = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < w * h; i++) le.putShort(l.getShort(2 * i));
                le.flip();
                ch.write(le);
            }
        }
    }

    /**
     * The scene (Long DN over black, grey): a horizontal ramp from 5 % to 400 % of the Long clip over the upper half (the merge
     * point crossed by a smooth gradient), a lamp of up to 40 x the clip with a soft edge in the lower half, a texture of 3 %.
     */
    static double scene(int x, int y, int w, int h) {
        final double tex = 1 + 0.03 * Math.sin(x * 0.7) * Math.cos(y * 0.9);
        if (y < h / 2) return RANGE * (0.05 * Math.pow(80, x / (double) (w - 1))) * tex;
        final double dx = x - 0.5 * w, dy = y - 0.75 * h, d = Math.sqrt(dx * dx + dy * dy) / (0.18 * h);
        return RANGE * (0.3 + 40 * Math.exp(-d * d * d)) * tex;
    }

    @Test
    public void writesWorkerBurstsWhenAsked() throws Exception {
        final String dir = System.getenv("LOFIC_NCH_DIR");
        if (dir == null || dir.isEmpty()) return;
        final double k = 833;
        final LoficMerge.Encoding e = LoficMerge.encoding(k, BLACK, 16383);
        writeNch(new File(dir, "lofic.nch"), true, k, 1024, 640);
        writeNch(new File(dir, "long.nch"), false, k, 1024, 640);
        try (FileOutputStream o = new FileOutputStream(new File(dir, "lofic.txt"))) {
            o.write(String.format(Locale.ROOT, "k %.1f\nheadroom %d\nrange %d\n", k, Math.round(e.headroom), RANGE).getBytes());
        }
    }
}
