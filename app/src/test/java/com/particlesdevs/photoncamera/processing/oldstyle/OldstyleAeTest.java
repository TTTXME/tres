package com.particlesdevs.photoncamera.processing.oldstyle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * P630e «Oldstyle»: the exposures by the owner's supplement - its plans and gains ported to Java held to the document's 45 table
 * rows (each value within 2e-7 of it), the cap's split by F, fusion_gains' clamp, our metering on the document's control scenes (the
 * tables' HDR decision on 14 of 15, F of the HDR scenes), the plan of a merge from the shot's capture plan over the frames' actual
 * TET (no gain twice), the style's exposure in both TETs, the EV compensation where the supplement puts it, the RAW quads of the
 * viewfinder's metering and the frame's scale into the module.
 */
public class OldstyleAeTest {
    /** n, S*, L* [, F, S, L, gS, gL] (the HDR rows); 2.7 YUV, 3.1 YUV, 3.1 RAW. */
    static final double[][] T27 = {{1, 989.201721, 315.177246}, {2, 270.237793, 100.612755}, {3, 35.0170898, 15.1982012},
            {4, 35.0170898, 15.1982012}, {5, 322.131866, 223.905884}, {6, 41.5780716, 36.3195}, {7, 42.9457512, 18.7574635},
            {8, 53.0715027, 135.983078, 0.783658624, 53.0715027, 135.983078, 6.22721815, 16}, {9, 38.8047676, 470.713745},
            {10, 41.8184509, 333.559784}, {11, 40.6862411, 44.8460083}, {12, 33.9252625, 35.5207481},
            {13, 45.378891, 174.472137, 0.752773225, 48.7554092, 170.643936, 5.58983946, 16},
            {14, 46.6515732, 108.750824, 0.752773225, 46.6515732, 108.750824, 5.50525713, 13.593853}, {15, 49.9008942, 58.9369354}};
    static final double[][] T31 = {{1, 984.76886, 378.037628}, {2, 275.014069, 130.732986}, {3, 35.3905334, 15.8302412},
            {4, 35.3905334, 15.8302412}, {5, 359.182831, 221.97908}, {6, 44.7052231, 36.4365959}, {7, 43.1003838, 20.3144894},
            {8, 54.9586372, 118.221741, 0.782477736, 54.9586372, 118.221741, 6.5144515, 14.7777176}, {9, 38.5246315, 431.15033},
            {10, 42.1738472, 326.639313}, {11, 42.4299278, 49.17836}, {12, 35.8402328, 36.7030449},
            {13, 47.7459755, 156.621124, 0.750348628, 47.7459755, 156.621124, 5.50388479, 16},
            {14, 48.8378639, 100.784462, 0.750348628, 48.8378639, 100.784462, 5.80144739, 12.5980577}, {15, 53.0210648, 56.1362877}};
    static final double[][] R31 = raw31();

    static double[][] raw31() {
        final double[][] r = T31.clone();
        r[8] = new double[]{9, 38.5246315, 431.15033, 0.750348628, 49.5841408, 396.673157, 5.08700275, 16};
        r[9] = new double[]{10, 42.1738472, 326.639313, 0.823265851, 42.1738472, 326.639313, 4.35407829, 16};
        return r;
    }

    static void within(String what, double want, float got) {
        assertTrue(what + ": " + got + " vs " + want, Math.abs(got - want) <= 2e-7 * Math.abs(want));
    }

    static void rows(String name, double[][] table, int profile, boolean raw) {
        for (double[] r : table) {
            final float s = (float) r[1], l = (float) r[2], f = r.length > 3 ? (float) r[3] : 0f;
            final OldstyleAe.ExposurePlan p = profile == 27 ? OldstyleAe.plan27(s, l, f)
                    : OldstyleAe.plan31(s, l, f, raw, false, 0f, 0.1f, 1000f);
            final String tag = name + " scene " + (int) r[0];
            assertEquals(tag, r.length > 3, p.useHdr);
            if (r.length > 3) {
                final float[] g = OldstyleAe.fusionGains(8f, p.shortPlan, p.longPlan, 1f, 16f);
                within(tag + " S", r[4], p.shortPlan);
                within(tag + " L", r[5], p.longPlan);
                within(tag + " gS", r[6], g[0]);
                within(tag + " gL", r[7], g[1]);
            }
        }
    }

    @Test public void theSupplementsTables() {
        rows("2.7 YUV", T27, 27, false);
        rows("3.1 YUV", T31, 31, false);
        rows("3.1 RAW", R31, 31, true);
    }

    @Test public void theRulesBounds() {
        // 2.7: HDR from 1.45 to 6.804, both included; the ratio capped at 3.5
        assertFalse(OldstyleAe.plan27(10f, 14.4f, 0.8f).useHdr);
        assertTrue(OldstyleAe.plan27(10f, 14.5f, 0.8f).useHdr);
        assertTrue(OldstyleAe.plan27(10f, 68.03f, 0.8f).useHdr);
        assertFalse(OldstyleAe.plan27(10f, 68.1f, 0.8f).useHdr);
        assertTrue(OldstyleAe.plan27(10f, 68.1f, 0.8f).hdrScene);
        // F at 0.6 or below: the long TET comes down by the whole excess; at 0.8 or above the short goes up by it
        OldstyleAe.ExposurePlan p = OldstyleAe.plan27(10f, 50f, 0.5f);
        assertEquals(10f, p.shortPlan, 0f);
        assertEquals(35f, p.longPlan, 1e-4f);
        p = OldstyleAe.plan27(10f, 50f, 0.9f);
        assertEquals(50f / 3.5f, p.shortPlan, 1e-4f);
        assertEquals(50f, p.longPlan, 0f);
        // 3.1: the scene flag at 1.33; RAW up to 8 x 1.4, YUV up to 3.5 x 1.944; disabled: the flag without HDR
        assertTrue(OldstyleAe.plan31(10f, 13.4f, 0.7f, true, false, 0f, 0.1f, 1000f).useHdr);
        assertFalse(OldstyleAe.plan31(10f, 13.2f, 0.7f, true, false, 0f, 0.1f, 1000f).hdrScene);
        assertTrue(OldstyleAe.plan31(10f, 111f, 0.7f, true, false, 0f, 0.1f, 1000f).useHdr);
        assertFalse(OldstyleAe.plan31(10f, 113f, 0.7f, true, false, 0f, 0.1f, 1000f).useHdr);
        assertFalse(OldstyleAe.plan31(10f, 70f, 0.7f, false, false, 0f, 0.1f, 1000f).useHdr);
        final OldstyleAe.ExposurePlan off = OldstyleAe.plan31(10f, 30f, 0.7f, true, true, 0f, 0.1f, 1000f);
        assertTrue(off.hdrScene);
        assertFalse(off.useHdr);
        // EV and the TET range after the cap
        p = OldstyleAe.plan31(10f, 30f, 0.7f, true, false, 1f, 0.1f, 50f);
        assertEquals(20f, p.shortPlan, 1e-5f);
        assertEquals(50f, p.longPlan, 0f);
    }

    @Test public void gainsAndTheirClamp() {
        // 0.97561^(L / S) x S over the actual TET, L over it; above M x 1.001 -> M, within the tolerance as they are
        float[] g = OldstyleAe.fusionGains(1f, 0.5f, 2f, 1f, 16f);
        assertEquals((float) Math.pow(0.9756097793579102f, 4f) * 0.5f, g[0], 1e-7f);
        assertEquals(2f, g[1], 0f);
        assertEquals(8f, OldstyleAe.fusionGains(1f, 1f, 9f, 1f, 8f)[1], 0f);
        assertEquals(8.005f, OldstyleAe.fusionGains(1f, 1f, 8.005f, 1f, 8f)[1], 0f);
        assertEquals(4f, OldstyleAe.fusionGains(2f, 1f, 8f, 1f, 16f)[1], 0f);   // the actual TET in the denominator: no gain twice
        g = OldstyleAe.fusionGainsYuv(2f, 0.75f, 6f, 1f, 16f);
        assertArrayEquals(OldstyleAe.fusionGains(2f, 1.5f, 6f, 1f, 16f), g, 0f);
    }

    /** The document's control scene n (32 x 24), the meter's 12-bit short input. */
    static float[] scene(int n) {
        final float[] out = new float[32 * 24 * 3];
        double[] m = n == 5 || n == 10 ? new double[]{1.15, 1, 0.78} : n == 12 ? new double[]{0.76, 1, 1.12} : new double[]{1, 1, 1};
        for (int y = 0; y < 24; y++) for (int x = 0; x < 32; x++) {
            final double X = x / 31.0, Y = y / 23.0, v;
            switch (n) {
                case 1: v = 0.035; break;
                case 2: v = 0.12; break;
                case 3: v = 0.42; break;
                case 4: v = 0.76; break;
                case 5: v = 0.015 + 0.085 * X; break;
                case 6: v = 0.06 + 0.34 * X; break;
                case 7: v = 0.11 + 0.77 * X; break;
                case 8: v = 0.02 + 0.55 * X * X * X; break;
                case 9: v = X < 0.72 ? 0.016 : 0.82; break;
                case 10: v = X > 0.35 && X < 0.62 && Y > 0.25 && Y < 0.73 ? 0.85 : 0.018; break;
                case 11: v = X < 0.46 ? 0.075 : 0.51; break;
                case 12: v = ((x / 4 + y / 3) % 2) == 1 ? 0.72 : 0.07; break;
                case 13: v = X < 0.72 ? 0.04 : 0.70; break;
                case 14: v = X < 0.72 ? 0.065 : 0.70; break;
                default: v = X < 0.72 ? 0.11 : 0.70;
            }
            for (int c = 0; c < 3; c++)
                out[(y * 32 + x) * 3 + c] = (float) (Math.min(4095, Math.max(0, Math.rint(4095 * v * m[c]))) / 4095.0);
        }
        return out;
    }

    @Test public void meteringOnTheControlScenes() {
        for (boolean v31 : new boolean[]{false, true}) {
            final double[][] table = v31 ? R31 : T27;
            int agree = 0;
            for (int n = 1; n <= 15; n++) {
                final OldstyleAe.Meter m = OldstyleAe.meter(scene(n), 32, 24, v31, 1f);
                final boolean hdr = OldstyleAe.rule(v31, m.shortRel, m.longRel, m.fractionLong).useHdr;
                if (hdr == table[n - 1].length > 3) agree++;
                else assertEquals("the one miss is the checkerboard", 12, n);
                // the scenes the tables plan by F (not 08: its ramp has no edge for the long exposure)
                if (table[n - 1].length > 3 && n != 8)
                    assertEquals("F of scene " + n, table[n - 1][3], m.fractionLong, 0.07);
                // S* keeps the light at 0.95 of white (a merge: the light as it is, clipped or not)
                assertEquals(OldstyleAe.SHORT_WHITE / m.high, m.shortRel, 1e-6f);
            }
            assertEquals(14, agree);
        }
        // before a shot a clipped light takes S* one step lower (the burst is still to be exposed): scene 04's 0.76 x 1.25 clips
        final float[] hot = scene(4);
        for (int i = 0; i < hot.length; i++) hot[i] = Math.min(1f, hot[i] * 1.4f);
        final OldstyleAe.Meter merge = OldstyleAe.meter(hot, 32, 24, false, 1f), vf = OldstyleAe.meter(hot, 32, 24, false, 1f, true);
        assertTrue(vf.clipped && merge.clipped);
        assertEquals(merge.shortRel * OldstyleAe.CLIPPED_STEP, vf.shortRel, 1e-6f);
        assertEquals(merge.ratio(), vf.ratio(), 1e-5f);
        // flat scenes: the SDR TET (L*) puts them at one level whatever their own (the AE normalises the exposure)
        final float a = OldstyleAe.meter(scene(1), 32, 24, false, 1f).longRel * 0.035f;
        final float b = OldstyleAe.meter(scene(3), 32, 24, false, 1f).longRel * 0.42f;
        assertEquals(a, b, 0.06f);
    }

    @Test public void thePlanOfAMergeFromTheCapture() {
        final float[] rgb = scene(13);
        final OldstyleAe.Meter m = OldstyleAe.meter(rgb, 32, 24, false, 1f);
        final OldstyleAe.Capture c = OldstyleAe.capture(m, false, 2_000_000.0);   // the viewfinder's frame: 10 ms x ISO 200
        assertTrue(c.hdr);
        assertEquals(c.shortTet, c.frameTet, 0.0);                               // the burst at S
        final OldstyleAe.Params p = new OldstyleAe.Params();
        // the frames came at S: the plan over their actual TET - S is 1 there, the long gain L / S, the short a little under 1
        final OldstyleAe.Plan plan = OldstyleAe.plan(rgb, 32, 24, p, 1f, c, c.frameTet);
        assertEquals("capture", plan.source);
        assertTrue(plan.hdr);
        assertEquals(1f, plan.shortTet, 1e-5f);
        assertEquals((float) (c.longTet / c.shortTet), plan.gainLong, 1e-4f);
        assertArrayEquals(OldstyleAe.fusionGains(1f, plan.shortTet, plan.longTet, 1f, p.maxGain), new float[]{plan.gainShort, plan.gainLong}, 0f);
        // frames that came 1 EV darker than planned: both gains twice (the actual TET in the denominator)
        final OldstyleAe.Plan dark = OldstyleAe.plan(rgb, 32, 24, p, 1f, c, c.frameTet / 2);
        assertEquals(plan.gainLong * 2f, dark.gainLong, 1e-3f);
        // without a capture plan: metered on the merge itself, the same rule
        final OldstyleAe.Plan own = OldstyleAe.plan(rgb, 32, 24, p, 1f, null, 0);
        assertEquals("merge", own.source);
        assertEquals(m.shortRel, own.shortCandidate, 0f);
        assertEquals(plan.ratio(), own.ratio(), 1e-4f);
        // the style's exposure: both TETs (the gains) x 2^EV, the decision as it was
        p.exposureEv = 1f;
        final OldstyleAe.Plan up = OldstyleAe.plan(rgb, 32, 24, p, 1f, c, c.frameTet);
        assertTrue(up.hdr);
        assertEquals(Math.min(p.maxGain, plan.gainLong * 2f), up.gainLong, 1e-3f);
        // an SDR scene: one gain, the SDR TET (L*) over the actual one
        final float[] flat = scene(3);
        final OldstyleAe.Meter fm = OldstyleAe.meter(flat, 32, 24, false, 1f);
        final OldstyleAe.Capture fc = OldstyleAe.capture(fm, false, 1000.0);
        assertFalse(fc.hdr);
        assertEquals(fc.longCandidate, fc.frameTet, 1e-9);
        p.exposureEv = 0f;
        final OldstyleAe.Plan sdr = OldstyleAe.plan(flat, 32, 24, p, 1f, fc, fc.frameTet);
        assertFalse(sdr.hdr);
        assertEquals(1f, sdr.gainLong, 1e-5f);
        assertEquals(sdr.gainShort, sdr.gainLong, 0f);
    }

    @Test public void evCompensationWhereTheSupplementPutsIt() {
        // 2.7: the candidates after the compensation; 3.1: compensate_tet after the cap (the decision on the ratio either way)
        final OldstyleAe.ExposurePlan a = OldstyleAe.rule(false, 10f, 30f, 0.7f, 1f);
        assertEquals(20f, a.shortPlan, 1e-5f);
        assertEquals(60f, a.longPlan, 1e-4f);
        final OldstyleAe.ExposurePlan b = OldstyleAe.rule(true, 10f, 30f, 0.7f, -1f);
        assertEquals(5f, b.shortPlan, 1e-5f);
        assertEquals(15f, b.longPlan, 1e-5f);
        // the SDR frame of an EV-compensated capture at L* x 2^EV
        final OldstyleAe.Meter m = OldstyleAe.meter(scene(3), 32, 24, true, 1f);
        final OldstyleAe.Capture c = OldstyleAe.capture(m, true, 100.0, 1f);
        assertEquals(m.longRel * 100.0 * 2, c.frameTet, 1e-3);
        assertArrayEquals(new float[]{(float) c.frameTet, (float) c.frameTet}, OldstyleAe.viewfinderGains(c, 1000f), 1e-3f);
    }

    @Test public void viewfinderGainsAreTheShots() {
        final OldstyleAe.Meter m = OldstyleAe.meter(scene(14), 32, 24, true, 1f);
        final OldstyleAe.Capture c = OldstyleAe.capture(m, true, 1.0);   // over the screen frame's own TET
        assertTrue(c.hdr);
        final OldstyleAe.Params p = new OldstyleAe.Params();
        p.v31 = true;
        final OldstyleAe.Plan photo = OldstyleAe.plan(scene(14), 32, 24, p, 1f, null, 0);
        assertArrayEquals(new float[]{photo.gainShort, photo.gainLong}, OldstyleAe.viewfinderGains(c, 16f), 1e-5f);
    }

    @Test public void bayerQuads() {
        // a 4 x 4 RGGB frame (black 64, white 1023): R 0.5, G 0.25 / 0.75 (mean 0.5), B 1 (clipped stays at white)
        final int w = 8, h = 4;
        final ShortBuffer d = ByteBuffer.allocate(w * h * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final boolean ev = (y & 1) == 0, ex = (x & 1) == 0;
            final float lin = ev && ex ? 0.5f : ev || ex ? (ev ? 0.25f : 0.75f) : 1.2f;
            d.put(y * w + x, (short) Math.round(64 + Math.min(1f, lin) * (1023 - 64)));
        }
        final float[] q = OldstyleAe.bayerQuads(d, w, w, h, 0, new float[]{64, 64, 64, 64}, 1023, new float[]{2f, 1f, 1.5f}, 2, 1);
        assertEquals(6, q.length);
        assertEquals(1f, q[0], 2e-3f);
        assertEquals(0.5f, q[1], 2e-3f);
        assertEquals(1.5f, q[2], 2e-3f);
        // BGGR: the same samples read as blue at (0, 0)
        final float[] b = OldstyleAe.bayerQuads(d, w, w, h, 3, new float[]{64}, 1023, null, 1, 1);
        assertEquals(1f, b[0], 2e-3f);
        assertEquals(0.5f, b[2], 2e-3f);
    }

    @Test public void moduleScale() {
        assertEquals(4095, OldstyleAe.sample(1f, 1f));
        assertEquals(-409, OldstyleAe.sample(-0.1f, 1f));       // floor(-409.5 + 0.5): negatives kept for the module
        assertEquals(32767, OldstyleAe.sample(9f, 1f));         // V1's highlights above white kept up to int16
        assertEquals(1024, OldstyleAe.sample(1f, 0.25f));       // floor(1023.75 + 0.5)
        assertEquals(0, OldstyleAe.sample(Float.NaN, 1f));
        assertEquals(1f, OldstyleAe.inputScale(0.7f), 0f);
        assertEquals(0.5f, OldstyleAe.inputScale(2f), 0f);
        // V2's HDR frame with light past white (a merge with highlight headroom): scaled under white, S0 its scale
        final float[] rgb = scene(13);
        for (int i = 0; i < rgb.length; i += 3) if (rgb[i] > 0.5f) { rgb[i] *= 1.5f; rgb[i + 1] *= 1.5f; rgb[i + 2] *= 1.5f; }
        final OldstyleAe.Params p = new OldstyleAe.Params();
        p.v31 = true;
        final OldstyleAe.Plan plan = OldstyleAe.plan(rgb, 32, 24, p, 8f, null, 0);
        assertTrue(plan.describe(), plan.hdr);
        assertEquals(OldstyleAe.inputScale(1.05f), plan.inputScale, 1e-3f);
        assertEquals(plan.inputScale, plan.actualTet, 0f);
        assertArrayEquals(OldstyleAe.fusionGains(plan.inputScale, plan.shortTet, plan.longTet, 1f, p.maxGain),
                new float[]{plan.gainShort, plan.gainLong}, 0f);
    }

    @Test public void stagesOfThePostArray() {
        final OldstyleAe.Params p = new OldstyleAe.Params();
        float[] post = p.post();
        assertEquals(12, post.length);
        assertEquals(1f, post[0], 0f);                                    // dehaze / tint on by default
        assertEquals(OldstyleAe.DEHAZE_MAX_DARK, post[1], 0f);
        assertEquals(0f, post[4], 0f);                                    // the output saturation neutral: skipped
        assertEquals(1f, post[9], 0f);                                    // the low-frequency colour (V1 HDR)
        p.vibrance = 1.2f;
        assertEquals(1f, p.post()[4], 0f);
        p.dehaze = false;
        p.lowFrequency = false;
        post = p.post();
        assertEquals(0f, post[0], 0f);
        assertEquals(0f, post[9], 0f);
    }
}
