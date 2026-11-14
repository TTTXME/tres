package com.scamera.app.processing.opengl.postpipeline;

import com.scamera.app.processing.LiveRawFrame;
import com.scamera.app.processing.LiveWhyMeter;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P711 (owner, 2026-11-14: the RAW viewfinder «максимально близок к итоговому результату по тонмапингу»): the viewfinder's AE (its
 * CPU meter, LiveWhyMeter, on ONE noisy RAW frame) against the photo's (WhyAe on the photo's statistics grid - one value per colour
 * quad, WhyStats' whyLow of a 1x merge - of the same scene without the frame's noise: a merge's limit), as the TONE it gives: the
 * fused display luma of the reference layout's fusion (FusedCurve.fusedAt with WhyFusion.exposures207 of each result, at each
 * one's exposure) over the photo grid's own scene light, in 8-bit display codes. tools/check_vf_tone_match.py measures the tone
 * path at an equal AE; this is the AE's share of the difference. Scenes: daylight, a backlit room with a window, a night street
 * with lamps (the frame's noise at its strongest).
 */
public class VfAeToneMatchTest {
    private static final float[] TRANSFORM = {1.62f, -0.21f, 0.02f, -0.48f, 1.45f, -0.55f, -0.14f, -0.24f, 1.53f};
    private static final float[] LW = {0.7f, 2f, 1f, 1f};
    static double LAMP = 0.0004;
    private static final float CENTER = 1f, SMOOTH = 0.45f, GAMMA_INV = 1f / 2.2f, TOE = 0.05f;

    /** Scene light (linear sRGB-ish, 1 = the sensor's white) at frame position u, v of a scene. */
    private static double[] light(String scene, double u, double v, Random tex) {
        double t = Math.exp(0.35 * tex.nextGaussian() * 0.5);
        switch (scene) {
            case "daylight":
                if (v < 0.4) return new double[]{0.35 + 0.2 * v, 0.48 + 0.2 * v, 0.78};
                if (u < 0.4 && v > 0.5) return new double[]{0.012 * t, 0.025 * t, 0.008 * t};
                if (u > 0.55 && u < 0.8 && v < 0.75) return new double[]{0.72, 0.68, 0.62};
                return new double[]{0.07 * t, 0.14 * t, 0.04 * t};
            case "backlit room":
                if (u > 0.55 && u < 0.9 && v > 0.12 && v < 0.62) return new double[]{0.45 + 0.3 * v, 0.58 + 0.3 * v, 0.82};
                if ((u - 0.2) * (u - 0.2) + (v - 0.25) * (v - 0.25) * 0.56 < 0.0009) return new double[]{5, 4.2, 3};
                return new double[]{0.055 * t, 0.045 * t, 0.035 * t};
            default: { // night street
                double l = 0.0045 * t;
                double[] c = {l, l * 1.05, l * 1.3};
                double[][] lamps = {{0.2, 0.3}, {0.5, 0.25}, {0.8, 0.32}};
                for (double[] p : lamps) {
                    double r2 = (u - p[0]) * (u - p[0]) + (v - p[1]) * (v - p[1]) * 0.56;
                    if (r2 < LAMP) return new double[]{25, 20, 14};
                    double g = 0.00015 / (r2 + 0.004);
                    c[0] += g;
                    c[1] += g * 0.75;
                    c[2] += g * 0.45;
                }
                if (v > 0.6) for (int i = 0; i < 3; i++) c[i] += 0.04 * (1.2 - v);
                return c;
            }
        }
    }

    /** An RGGB frame of the scene: camera RGB = M^-1 light / WB, black 64, white 1023, shot and read noise of {@code noise}. */
    private static LiveRawFrame.Frame frame(String scene, int w, int h, double noise, long seed) {
        final double[][] m = inverse();
        final Random tex = new Random(17), rnd = new Random(seed);
        final double[] wb = {2.0, 1.0, 1.6};
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        final double[][] light = new double[w * h][];
        for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) {
            // one light per quad (the texture is the scene's, the same with and without noise)
            final double[] c = light(scene, (x + 1.0) / w, (y + 1.0) / h, tex);
            light[y * w + x] = c;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final double[] c = light[(y & ~1) * w + (x & ~1)];
                final int site = (y & 1) * 2 + (x & 1), ch = site == 0 ? 0 : site == 3 ? 2 : 1;
                final double cam = Math.max(0, m[ch][0] * c[0] + m[ch][1] * c[1] + m[ch][2] * c[2]) / wb[ch];
                double dn = 64 + 959 * cam;
                if (noise > 0) dn += rnd.nextGaussian() * noise * Math.sqrt(Math.max(dn - 64, 0) + 4);
                b.putShort((y * w + x) * 2, (short) Math.max(0, Math.min(1023, Math.round(dn))));
            }
        }
        final LiveRawFrame.Frame f = new LiveRawFrame.Frame();
        f.buffer = b;
        f.width = w;
        f.height = h;
        f.rowStride = w * 2;
        f.whiteLevel = 1023;
        f.blackLevel = new float[]{64, 64, 64, 64};
        f.wbGains = new float[]{2f, 1f, 1.6f};
        f.crop = new float[]{0, 0, 1, 1};
        f.shadingWidth = f.shadingHeight = 1;
        f.shading = new float[]{1, 1, 1};
        f.iso = scene.equals("night street") ? 3200 : 200;
        f.maxAnalogIso = 6400;
        return f;
    }

    /** The camera matrix's inverse (TRANSFORM is column-major). */
    private static double[][] inverse() {
        final double[][] a = new double[3][3];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) a[r][c] = TRANSFORM[c * 3 + r];
        final double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        final double[][] inv = new double[3][3];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            final int r1 = (c + 1) % 3, r2 = (c + 2) % 3, c1 = (r + 1) % 3, c2 = (r + 2) % 3;
            inv[r][c] = (a[r1][c1] * a[r2][c2] - a[r1][c2] * a[r2][c1]) / det;
        }
        return inv;
    }

    /** The photo's statistics grid: one linear value per colour quad of the clean frame (WhyStats' whyLow of a 1x merge). */
    private static float[] photoGrid(LiveRawFrame.Frame f, int[] size) {
        final int qw = f.width / 2, qh = f.height / 2;
        final float[] out = new float[qw * qh * 3];
        for (int qy = 0; qy < qh; qy++) for (int qx = 0; qx < qw; qx++) {
            final float[] s = new float[4];
            for (int p = 0; p < 4; p++) {
                final int x = 2 * qx + (p & 1), y = 2 * qy + (p >> 1);
                s[p] = Math.max(0f, ((f.buffer.getShort((y * f.width + x) * 2) & 0xFFFF) - 64f) / 959f);
            }
            float cr = s[0] * 2f, cg = (s[1] + s[2]) * 0.5f, cb = s[3] * 1.6f;
            final float rawMax = Math.max(s[0], Math.max((s[1] + s[2]) * 0.5f, s[3]));
            if (rawMax > 0.85f) {
                final float t = Math.max(0f, Math.min(1f, (rawMax - 0.85f) / 0.13f)), fade = t * t * (3 - 2 * t);
                final float mx = Math.max(cr, Math.max(cg, cb));
                cr += (mx - cr) * fade;
                cg += (mx - cg) * fade;
                cb += (mx - cb) * fade;
            }
            final int at = (qy * qw + qx) * 3;
            out[at] = Math.max(0f, TRANSFORM[0] * cr + TRANSFORM[3] * cg + TRANSFORM[6] * cb);
            out[at + 1] = Math.max(0f, TRANSFORM[1] * cr + TRANSFORM[4] * cg + TRANSFORM[7] * cb);
            out[at + 2] = Math.max(0f, TRANSFORM[2] * cr + TRANSFORM[5] * cg + TRANSFORM[8] * cb);
        }
        size[0] = qw;
        size[1] = qh;
        return out;
    }

    private static WhyAe.Settings settings(LiveRawFrame.Frame f) {
        final WhyAe.Settings s = new WhyAe.Settings();
        // the frame's noise model (variance s x + o of the normalised raw) over the merge's frames, as LiveWhyTone.mergedNoise
        s.noiseS = f.noiseS / 11.0;
        s.noiseO = f.noiseO / 11.0;
        return s;
    }

    /** The fused display luma of scene light Y (I = Y x ae) by an AE result's exposures (the reference layout). */
    private static double fused(WhyAe.Result r, double y) {
        final float[] em = WhyFusion.exposures207(r);
        return FusedCurve.fusedAt(y * r.ae, em, LW, CENTER, SMOOTH, GAMMA_INV, TOE);
    }

    /** {mean |d|, the 95th percentile of |d|, mean d} of the fused display luma in 8-bit codes over the photo grid's lights. */
    static double[] toneError(WhyAe.Result photo, WhyAe.Result vf, float[] grid) {
        final int n = grid.length / 3;
        final double[] d = new double[n];
        double sum = 0, signed = 0;
        for (int i = 0; i < n; i++) {
            final double y = 0.2126 * grid[3 * i] + 0.7152 * grid[3 * i + 1] + 0.0722 * grid[3 * i + 2];
            final double e = 255.0 * (fused(vf, y) - fused(photo, y));
            d[i] = Math.abs(e);
            sum += d[i];
            signed += e;
        }
        java.util.Arrays.sort(d);
        return new double[]{sum / n, d[(int) (0.95 * (n - 1))], signed / n};
    }

    private static String line(String what, WhyAe.Result r) {
        return String.format(Locale.ROOT, "%s ae %.3f effS %.3f effH %.3f p50 %.5f p98 %.4f p995 %.4f", what, r.ae, r.effS, r.effH,
                r.p50, r.p98, r.p995);
    }

    /**
     * The scene's photo AE (clean grid) against the viewfinder's on a noisy frame with its noise model: P711's samples (the quads,
     * LiveWhyMeter.measure) and, for the record, the cell means it metered before. Returns {after mean, after p95, before mean}.
     */
    private static double[] compare(String scene, double noise, double maxMean, double maxP95) {
        final LiveRawFrame.Frame clean = frame(scene, 1024, 768, 0, 1), noisy = frame(scene, 1024, 768, noise, 2);
        for (LiveRawFrame.Frame f : new LiveRawFrame.Frame[]{clean, noisy}) {
            // the noise model of the frames (variance s x + o of the normalised raw): noise^2 DN per DN of 959
            f.noiseS = noise * noise / 959.0;
            f.noiseO = noise * noise * 4 / (959.0 * 959.0);
        }
        final int[] size = new int[2];
        final float[] grid = photoGrid(clean, size);
        final WhyAe.Result photo = WhyAe.smartHdr(FloatBuffer.wrap(grid), 3, size[0], size[1], settings(clean), clean.iso, clean.maxAnalogIso);
        final LiveWhyMeter.View view = new LiveWhyMeter.View(noisy, 0, 0, 1024, 768, TRANSFORM);
        final WhyAe.Result vf = new LiveWhyMeter().measure(view, 1f, settings(noisy), noisy.iso, noisy.maxAnalogIso);
        assertNotNull(vf);
        // before P711: WhyAe on the cell means
        final int[] n = new int[2];
        final float[] cells = LiveWhyMeter.cells(view, 1f, n);
        final WhyAe.Result before = WhyAe.smartHdr(FloatBuffer.wrap(cells), 3, n[0], n[1], settings(noisy), noisy.iso, noisy.maxAnalogIso);
        final double[] e = toneError(photo, vf, grid), e0 = toneError(photo, before, grid);
        System.out.println(String.format(Locale.ROOT, "%-13s fused display luma vs the photo's: mean |d| %.2f (cell means %.2f), p95 %.2f (%.2f),"
                        + " mean d %+.2f (%+.2f) codes", scene, e[0], e0[0], e[1], e0[1], e[2], e0[2])
                + "\n  " + line("photo (clean grid):  ", photo) + "\n  " + line("viewfinder (quads):  ", vf)
                + "\n  " + line("viewfinder (cells):  ", before));
        assertTrue(scene + ": mean " + e[0], e[0] <= maxMean);
        assertTrue(scene + ": p95 " + e[1], e[1] <= maxP95);
        assertTrue(scene + ": no farther than the cell means", e[0] <= e0[0] + 0.3);
        return new double[]{e[0], e[1], e0[0]};
    }

    @Test
    public void theViewfindersAeGivesThePhotosToneInDaylight() {
        compare("daylight", 0.8, 1.0, 1.5);
        compare("backlit room", 1.0, 1.0, 1.5);
    }

    /**
     * The night street's lamps near half a percent of the frame: the cell means put the 99.5th percentile on the lamps' glow (the
     * viewfinder +0.35 EV, ~12 codes brighter than its photo); the quads keep it on the lamps.
     */
    @Test
    public void theViewfindersAeGivesThePhotosToneAtNight() {
        final double[] e = compare("night street", 1.5, 1.0, 1.5);
        assertTrue("the cell means were the night's error", e[2] > 5.0);
        // smaller and larger lamps: as close (the percentile is on the same side for both)
        for (double lamp : new double[]{0.0001, 0.0008}) {
            LAMP = lamp;
            try {
                compare("night street", 1.5, 1.0, 1.5);
            } finally {
                LAMP = 0.0004;
            }
        }
    }

    /** A frame without a noise model: the cell means, as before (the quads' noise is not known). */
    @Test
    public void withoutANoiseModelTheCellMeansAsBefore() {
        final LiveRawFrame.Frame f = frame("night street", 1024, 768, 1.5, 3);
        final LiveWhyMeter.View view = new LiveWhyMeter.View(f, 0, 0, 1024, 768, TRANSFORM);
        final WhyAe.Result vf = new LiveWhyMeter().measure(view, 1f, settings(f), f.iso, f.maxAnalogIso);
        final int[] n = new int[2];
        final float[] cells = LiveWhyMeter.cells(view, 1f, n);
        final WhyAe.Result before = WhyAe.smartHdr(FloatBuffer.wrap(cells), 3, n[0], n[1], settings(f), f.iso, f.maxAnalogIso);
        org.junit.Assert.assertEquals(before.ae, vf.ae, 0f);
        org.junit.Assert.assertEquals(before.p995, vf.p995, 0f);
        org.junit.Assert.assertEquals(before.effH, vf.effH, 0f);
    }
}
