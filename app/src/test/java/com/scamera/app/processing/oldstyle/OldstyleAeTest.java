package com.scamera.app.processing.oldstyle;

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
                // S* keeps the light at 0.95 of white (no control scene clips: the brightest light is 0.9775, data)
                assertEquals(OldstyleAe.SHORT_WHITE / m.high, m.shortRel, 1e-6f);
            }
            assertEquals(14, agree);
        }
        // P643: the capture is the photo mode's - no metering "before a shot" that asked for a shorter exposure (the test of the
        // step S* took down for a clipped light went with it). A light clipped in the frame is kept where the frame has it:
        // scene 04's 0.76 x 1.4 clips - S* is the frame's own TET, not 0.95 of it (P643d: our estimator's input; the rules take it
        // as any S*), L* the fit's as for any frame
        final float[] hot = scene(4);
        for (int i = 0; i < hot.length; i++) hot[i] = Math.min(1f, hot[i] * 1.4f);
        final OldstyleAe.Meter clipped = OldstyleAe.meter(hot, 32, 24, false, 1f);
        assertTrue(clipped.clipped);
        assertEquals(1f, clipped.shortRel, 1e-6f);
        assertTrue("the fit asks for less light than the frame has", OldstyleAe.ratioFit(false, clipped.high, clipped.geoMean, clipped.mean) < 1f);
        assertEquals(clipped.shortRel * OldstyleAe.ratioFit(false, clipped.high, clipped.geoMean, clipped.mean), clipped.longRel, 1e-6f);
        final OldstyleAe.Plan white = OldstyleAe.plan(hot, 32, 24, new OldstyleAe.Params(), 1f);
        assertFalse(white.hdr);
        // P643d: without HDR the frame is the AE's one exposure (the supplement: S* / L* are the decision's diagnostics then):
        // gain 1, so a clipped white stays white
        assertEquals("a clipped white is not turned grey: the one gain is 1", 1f, white.gainLong, 0f);
        // ... with HDR the short gain is fusion_gains' base ^ (L / S) of a frame exposed at S (what a burst at S got), not 0.95 of it
        final float[] window = scene(13);
        for (int i = 0; i < window.length; i++) window[i] = Math.min(1f, window[i] * 1.6f);
        final OldstyleAe.Plan hdr = OldstyleAe.plan(window, 32, 24, new OldstyleAe.Params(), 1f);
        assertTrue(hdr.hdr && hdr.meter.clipped);
        assertEquals(1f, hdr.shortCandidate, 1e-6f);
        assertEquals((float) Math.pow(OldstyleAe.FUSION_BASE, hdr.longTet / hdr.shortTet) * hdr.shortTet, hdr.gainShort, 1e-5f);
        assertTrue(hdr.gainShort > 0.9f && hdr.gainShort <= 1f);
        // the rule comes in softly (no jump of S* or L* as the light crosses the clip level), and an unclipped frame is metered
        // as before it: S* at 0.95 of white, L* by the fit alone
        assertEquals(0f, OldstyleAe.clipShare(0.5f, OldstyleAe.CLIP_NEAR), 0f);
        assertEquals(0f, OldstyleAe.clipShare(0.9775f, OldstyleAe.CLIP_NEAR), 0f);
        assertEquals(1f, OldstyleAe.clipShare(OldstyleAe.CLIP_LEVEL, OldstyleAe.CLIP_NEAR), 1e-6f);
        assertEquals(1f, OldstyleAe.clipShare(1.2f, OldstyleAe.CLIP_NEAR), 0f);
        float lastShort = 0f, lastLong = 0f;
        for (float k = 1.05f; k <= 1.45f; k += 0.004f) {
            final float[] f = scene(4);
            for (int i = 0; i < f.length; i++) f[i] = Math.min(1f, f[i] * k);
            final OldstyleAe.Meter m = OldstyleAe.meter(f, 32, 24, false, 1f);
            // (the light itself rises by 0.4 % a step: S* x the light and L* x the light move by under 2 % a step)
            final float s = m.shortRel * m.high, l = m.longRel * m.high;
            if (lastShort > 0f) assertTrue(k + ": " + lastShort + " -> " + s + ", " + lastLong + " -> " + l,
                    Math.abs(s / lastShort - 1f) < 0.02f && Math.abs(l / lastLong - 1f) < 0.02f);
            lastShort = s;
            lastLong = l;
            if (m.high <= OldstyleAe.CLIP_NEAR) {
                assertEquals(OldstyleAe.SHORT_WHITE / m.high, m.shortRel, 1e-6f);
                assertEquals(m.shortRel * OldstyleAe.ratioFit(false, m.high, m.geoMean, m.mean), m.longRel, 1e-6f);
            }
        }
        // flat scenes: the SDR TET (L*) puts them at one level whatever their own (the AE normalises the exposure)
        final float a = OldstyleAe.meter(scene(1), 32, 24, false, 1f).longRel * 0.035f;
        final float b = OldstyleAe.meter(scene(3), 32, 24, false, 1f).longRel * 0.42f;
        assertEquals(a, b, 0.06f);
    }

    /**
     * P643: the tone's plan is metered on the frame it is given, at the exposure it came with (the photo mode's) - the frame's TET
     * is fusion_gains' actual short TET. The tests of a capture plan (the burst exposed at S or at the SDR TET, the viewfinder's EV
     * compensation carried into the rule, the plan's gains for the viewfinder) went with the mode's own capture.
     */
    @Test public void thePlanOfAFrameAtItsOwnExposure() {
        final float[] rgb = scene(13);
        final OldstyleAe.Meter m = OldstyleAe.meter(rgb, 32, 24, false, 1f);
        final OldstyleAe.Params p = new OldstyleAe.Params();
        final OldstyleAe.Plan plan = OldstyleAe.plan(rgb, 32, 24, p, 1f);
        assertTrue(plan.hdr);
        assertEquals(m.shortRel, plan.shortCandidate, 0f);
        assertEquals(m.longRel, plan.longCandidate, 0f);
        assertEquals(1f, plan.actualTet, 0f);
        // the supplement's rule and gains, the frame's TET (1) in the denominator: nothing applied twice
        final OldstyleAe.ExposurePlan e = OldstyleAe.rule(false, m.shortRel, m.longRel, m.fractionLong);
        assertArrayEquals(new float[]{e.shortPlan, e.longPlan}, new float[]{plan.shortTet, plan.longTet}, 0f);
        assertArrayEquals(OldstyleAe.fusionGains(1f, plan.shortTet, plan.longTet, 1f, p.maxGain), new float[]{plan.gainShort, plan.gainLong}, 0f);
        // the same scene exposed a stop darker by the camera: the tone asks for the same light - both gains twice
        final float[] darker = rgb.clone();
        for (int i = 0; i < darker.length; i++) darker[i] *= 0.5f;
        final OldstyleAe.Plan dark = OldstyleAe.plan(darker, 32, 24, p, 1f);
        assertTrue(dark.hdr);
        // (L* by the fit, which is not exactly scale-free: 0.5 ^ 0.027 of it)
        assertEquals(plan.gainLong * 2f, dark.gainLong, plan.gainLong * 2f * 0.03f);
        assertEquals(plan.gainShort * 2f, dark.gainShort, plan.gainShort * 0.02f);
        // the style's exposure: both TETs (the gains) x 2^EV, the decision as it was
        p.exposureEv = 1f;
        final OldstyleAe.Plan up = OldstyleAe.plan(rgb, 32, 24, p, 1f);
        assertTrue(up.hdr);
        assertEquals(Math.min(p.maxGain, plan.gainLong * 2f), up.gainLong, 1e-3f);
        // an SDR scene: one gain - P643d: the frame's own (the AE's) exposure, 1 (the supplement: S* / L* are the decision's
        // diagnostics without HDR; P630e took L* when the burst was exposed by the mode's own plan); the shifts on top
        p.exposureEv = 0f;
        final float[] flat = scene(3);
        final OldstyleAe.Plan sdr = OldstyleAe.plan(flat, 32, 24, p, 1f);
        assertFalse(sdr.hdr);
        assertEquals(1f, sdr.gainLong, 0f);
        assertEquals(sdr.gainShort, sdr.gainLong, 0f);
        assertTrue("L* diagnostic only", Math.abs(OldstyleAe.meter(flat, 32, 24, false, 1f).longRel - 1f) > 0.05f);
        p.exposureEv = -1f;
        p.baseEv = 0.5f;
        assertEquals((float) Math.pow(2, -0.5), OldstyleAe.plan(flat, 32, 24, p, 1f).gainLong, 1e-6f);
        p.exposureEv = 0f;
        p.baseEv = 0f;
        // V2's HDR on a frame holding light past white (a merge with short frames): scaled into white, the scale the actual TET
        final OldstyleAe.Params v2 = new OldstyleAe.Params();
        v2.v31 = true;
        final float[] bright = scene(14);
        for (int i = 0; i < bright.length; i++) bright[i] *= 3f;
        final OldstyleAe.Plan wide = OldstyleAe.plan(bright, 32, 24, v2, 16f);
        assertTrue(wide.hdr);
        assertEquals(1f / (0.70f * 3f), wide.inputScale, 1e-3f);
        assertEquals(wide.inputScale, wide.actualTet, 0f);
    }

    @Test public void evCompensationWhereTheSupplementPutsIt() {
        // 2.7: the candidates after the compensation; 3.1: compensate_tet after the cap (the decision on the ratio either way).
        // (The rule's EV is the supplement's; P643: the tone's plan passes 0 - the camera's compensation is in the frame it gets.)
        final OldstyleAe.ExposurePlan a = OldstyleAe.rule(false, 10f, 30f, 0.7f, 1f);
        assertEquals(20f, a.shortPlan, 1e-5f);
        assertEquals(60f, a.longPlan, 1e-4f);
        final OldstyleAe.ExposurePlan b = OldstyleAe.rule(true, 10f, 30f, 0.7f, -1f);
        assertEquals(5f, b.shortPlan, 1e-5f);
        assertEquals(15f, b.longPlan, 1e-5f);
    }

    /** P643b: the part of the sensor the viewfinder shows (the crop region, the zoom ratio), for the tone preview's frame. */
    @Test public void thePartOfTheSensorOnTheScreen() {
        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, OldstyleAe.shownPart(4000, 3000, null, 1f), 0f);
        assertArrayEquals(new float[]{0.25f, 0.25f, 0.5f, 0.5f}, OldstyleAe.shownPart(4000, 3000, null, 2f), 1e-6f);
        assertArrayEquals(new float[]{0.25f, 0.25f, 0.5f, 0.5f}, OldstyleAe.shownPart(4000, 3000, new int[]{1000, 750, 2000, 1500}, 1f), 1e-6f);
        assertArrayEquals(new float[]{0.375f, 0.375f, 0.25f, 0.25f}, OldstyleAe.shownPart(4000, 3000, new int[]{1000, 750, 2000, 1500}, 2f), 1e-6f);
        // never outside the frame, never less than a sixteenth of a side, a broken region is the whole frame
        final float[] far = OldstyleAe.shownPart(4000, 3000, new int[]{3900, 2900, 2000, 1500}, 1f);
        assertTrue(far[0] + far[2] <= 1f + 1e-6f && far[1] + far[3] <= 1f + 1e-6f && far[0] >= 0f);
        assertArrayEquals(new float[]{1f / 16f, 1f / 16f}, java.util.Arrays.copyOfRange(OldstyleAe.shownPart(4000, 3000, null, 100f), 2, 4), 0f);
        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, OldstyleAe.shownPart(0, 0, new int[]{1, 1, 5, 5}, Float.NaN), 0f);
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
        final OldstyleAe.Plan plan = OldstyleAe.plan(rgb, 32, 24, p, 8f);
        assertTrue(plan.describe(), plan.hdr);
        assertEquals(OldstyleAe.inputScale(1.05f), plan.inputScale, 1e-3f);
        assertEquals(plan.inputScale, plan.actualTet, 0f);
        assertArrayEquals(OldstyleAe.fusionGains(plan.inputScale, plan.shortTet, plan.longTet, 1f, p.maxGain),
                new float[]{plan.gainShort, plan.gainLong}, 0f);
    }

    @Test public void stagesOfThePostArray() {
        final OldstyleAe.Params p = new OldstyleAe.Params();
        float[] post = p.post();
        // P630h: three more entries (the low-frequency colour's share, the tone curve's midtones and contrast), neutral by default
        assertEquals(15, post.length);
        assertEquals(1f, post[11], 0f);                                   // colour only
        assertArrayEquals(new float[]{1f, 0f, 0f}, new float[]{post[12], post[13], post[14]}, 0f);
        p.lfChroma = false;
        p.lfStrength = 0.4f;
        p.curveMid = -0.5f;
        p.curveContrast = 0.25f;
        assertArrayEquals(new float[]{0f, 0.4f, -0.5f, 0.25f}, java.util.Arrays.copyOfRange(p.post(), 11, 15), 0f);
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

    private static OldstyleAe.Params params(boolean v31) {
        final OldstyleAe.Params p = new OldstyleAe.Params();
        p.v31 = v31;
        return p;
    }

    /**
     * P630h: with the rows untouched the meter, the rule and the plan are the ones before the rows - every control scene, both
     * profiles, bit for bit (the Params overloads against the signatures the 45 table rows are held to).
     */
    @Test public void theRowsUntouchedAreTheRulesBefore() {
        for (boolean v31 : new boolean[]{false, true}) {
            for (int n = 1; n <= 15; n++) {
                final float[] rgb = scene(n);
                {
                    final OldstyleAe.Meter a = OldstyleAe.meter(rgb, 32, 24, v31, 1f), b = OldstyleAe.meter(rgb, 32, 24, params(v31), 1f);
                    assertArrayEquals(new float[]{a.high, a.geoMean, a.mean, a.shortRel, a.longRel, a.fractionLong},
                            new float[]{b.high, b.geoMean, b.mean, b.shortRel, b.longRel, b.fractionLong}, 0f);
                }
                final OldstyleAe.Meter m = OldstyleAe.meter(rgb, 32, 24, v31, 1f);
                for (float ev : new float[]{0f, -0.7f, 1f}) {
                    final OldstyleAe.ExposurePlan a = OldstyleAe.rule(v31, m.shortRel, m.longRel, m.fractionLong, ev),
                            b = OldstyleAe.rule(params(v31), m.shortRel, m.longRel, m.fractionLong, ev);
                    assertEquals(a.useHdr, b.useHdr);
                    assertEquals(a.hdrScene, b.hdrScene);
                    assertArrayEquals(new float[]{a.shortPlan, a.longPlan}, new float[]{b.shortPlan, b.longPlan}, 0f);
                }
            }
        }
        assertArrayEquals(OldstyleAe.fusionGains(8f, 53.07f, 135.98f, 1f, 16f), OldstyleAe.fusionGains(8f, 53.07f, 135.98f, 1f, 16f, OldstyleAe.FUSION_BASE), 0f);
    }

    /** P630h «HDR» and its thresholds: each row moves the decision or the two exposures its way. */
    @Test public void theHdrRowsMoveTheDecisionAndTheRatio() {
        for (boolean v31 : new boolean[]{false, true}) {
            final String v = v31 ? "3.1" : "2.7";
            // a calm scene (L* / S* 1.2: under both profiles' start), a contrasty one (5), a huge one (40: past both ends)
            final OldstyleAe.Params auto = params(v31), on = params(v31), off = params(v31);
            on.hdrMode = OldstyleAe.HDR_ON;
            off.hdrMode = OldstyleAe.HDR_OFF;
            assertFalse(v, OldstyleAe.rule(auto, 1f, 1.2f, 0.9f, 0f).useHdr);
            assertTrue(v, OldstyleAe.rule(auto, 1f, 5f, 0.9f, 0f).useHdr);
            assertFalse(v, OldstyleAe.rule(auto, 1f, 40f, 0.9f, 0f).useHdr);
            for (float ratio : new float[]{1.2f, 5f, 40f}) {
                final OldstyleAe.ExposurePlan forced = OldstyleAe.rule(on, 1f, ratio, 0.9f, 0f), none = OldstyleAe.rule(off, 1f, ratio, 0.9f, 0f);
                assertTrue(v + " on " + ratio, forced.useHdr);
                // the ratio still capped (3.5 / 8), the excess to the short exposure with F 0.9
                assertEquals(v + " on " + ratio, Math.min(ratio, v31 ? 8f : 3.5f), forced.longPlan / forced.shortPlan, 1e-4f);
                assertFalse(v + " off " + ratio, none.useHdr);
                assertArrayEquals(new float[]{1f, ratio}, new float[]{none.shortPlan, none.longPlan}, 0f);
            }
            assertFalse(v + ": no second exposure below the first", OldstyleAe.rule(on, 1f, 1f, 0.9f, 0f).useHdr);
            assertFalse(OldstyleAe.rule(on, 1f, 0.8f, 0.9f, 0f).useHdr);
            // «HDR от перепада» lower: the calm scene takes HDR; higher: the contrasty one does not
            final OldstyleAe.Params low = params(v31), high = params(v31);
            low.v1HdrMin = low.v2HdrMin = 1f;
            high.v1HdrMin = high.v2HdrMin = 3f;
            assertTrue(v, OldstyleAe.rule(low, 1f, 1.2f, 0.9f, 0f).useHdr);
            assertFalse(v, OldstyleAe.rule(high, 1f, 2.5f, 0.9f, 0f).useHdr);
            assertTrue(v, OldstyleAe.rule(auto, 1f, 2.5f, 0.9f, 0f).useHdr);
            // «сила HDR»: the cap of the two exposures' ratio
            final OldstyleAe.Params weak = params(v31), strong = params(v31);
            weak.v1RatioCap = 1.5f;
            weak.v2RatioMax = 2f;
            weak.v2Forgiveness = 2f;
            strong.v1RatioCap = 8f;
            strong.v2RatioMax = 16f;
            final float r = v31 ? 3.9f : 6f;
            final OldstyleAe.ExposurePlan a = OldstyleAe.rule(weak, 1f, r, 0.9f, 0f), b = OldstyleAe.rule(auto, 1f, r, 0.9f, 0f),
                    c = OldstyleAe.rule(strong, 1f, r, 0.9f, 0f);
            assertTrue(v, a.useHdr && b.useHdr && c.useHdr);
            assertEquals(v, v31 ? 2f : 1.5f, a.longPlan / a.shortPlan, 1e-4f);
            assertEquals(v, v31 ? 3.9f : 3.5f, b.longPlan / b.shortPlan, 1e-4f);
            assertEquals(v, r, c.longPlan / c.shortPlan, 1e-4f);
        }
        // V1 «HDR до перепада»: 6.804 by default - 10 is one exposure, with the row at 16 two
        final OldstyleAe.Params wide = params(false);
        wide.v1HdrMax = 16f;
        assertFalse(OldstyleAe.rule(params(false), 1f, 10f, 0.9f, 0f).useHdr);
        assertTrue(OldstyleAe.rule(wide, 1f, 10f, 0.9f, 0f).useHdr);
        // V2 «запас сверх силы HDR»: 8 x 1.4 = 11.2 by default - 14 is one exposure, with the row at 2 (16) two, capped to 8
        final OldstyleAe.Params forgiving = params(true);
        forgiving.v2Forgiveness = 2f;
        assertFalse(OldstyleAe.rule(params(true), 1f, 14f, 0.9f, 0f).useHdr);
        final OldstyleAe.ExposurePlan e = OldstyleAe.rule(forgiving, 1f, 14f, 0.9f, 0f);
        assertTrue(e.useHdr);
        assertEquals(8f, e.longPlan / e.shortPlan, 1e-4f);
    }

    /** P630h: the rows of the meter and of the gains, each its way on a backlit scene (a dark room, a bright window at the edge). */
    @Test public void theMeterAndGainRowsMoveThePlanTheirWay() {
        final float[] rgb = scene(13);
        for (boolean v31 : new boolean[]{false, true}) {
            final String v = v31 ? "3.1" : "2.7";
            final OldstyleAe.Plan base = OldstyleAe.plan(rgb, 32, 24, params(v31), 1f);
            assertTrue(v, base.hdr);
            // «Экспозиция Oldstyle»: a stop is both exposures doubled (up to the limit), the decision and the ratio kept
            final OldstyleAe.Params up = params(v31), down = params(v31);
            up.baseEv = 1f;
            down.baseEv = -1f;
            final OldstyleAe.Plan brighter = OldstyleAe.plan(rgb, 32, 24, up, 1f), darker = OldstyleAe.plan(rgb, 32, 24, down, 1f);
            assertEquals(v, base.gainShort * 2f, brighter.gainShort, base.gainShort * 1e-4f);
            assertEquals(v, base.gainShort / 2f, darker.gainShort, base.gainShort * 1e-4f);
            assertEquals(v, base.gainLong / 2f, darker.gainLong, base.gainLong * 1e-4f);
            assertTrue(v, brighter.hdr && darker.hdr);
            // ... and adds to the style's exposure
            up.exposureEv = 1f;
            assertEquals(v, base.gainShort * 4f, OldstyleAe.plan(rgb, 32, 24, up, 1f).gainShort, base.gainShort * 4e-4f);
            // «Запас по светам»: lower keeps the light further under white - the short exposure darker
            final OldstyleAe.Params room = params(v31);
            room.shortWhite = 0.5f;
            final OldstyleAe.Meter plain = OldstyleAe.meter(rgb, 32, 24, params(v31), 1f), roomy = OldstyleAe.meter(rgb, 32, 24, room, 1f);
            assertEquals(v, plain.shortRel * 0.5f / 0.95f, roomy.shortRel, plain.shortRel * 1e-5f);
            assertEquals(v, plain.ratio(), roomy.ratio(), 1e-5f);
            // «Яркость теней»: a stop up is L* doubled, S* as it was
            final OldstyleAe.Params lifted = params(v31);
            lifted.shadowEv = 1f;
            final OldstyleAe.Meter l = OldstyleAe.meter(rgb, 32, 24, lifted, 1f);
            assertEquals(v, plain.shortRel, l.shortRel, 0f);
            assertEquals(v, plain.longRel * 2f, l.longRel, plain.longRel * 1e-5f);
            // «Приглушение светов в HDR»: 1 is no trim (gS = S / actual), lower trims more
            final OldstyleAe.Params none = params(v31), more = params(v31);
            none.fusionBase = 1f;
            more.fusionBase = 0.95f;
            final OldstyleAe.Plan untrimmed = OldstyleAe.plan(rgb, 32, 24, none, 1f), trimmed = OldstyleAe.plan(rgb, 32, 24, more, 1f);
            assertEquals(v, untrimmed.shortTet / untrimmed.actualTet, untrimmed.gainShort, 1e-5f);
            assertTrue(v, trimmed.gainShort < base.gainShort && base.gainShort < untrimmed.gainShort);
            assertEquals(v, base.gainLong, trimmed.gainLong, 0f);
            // «Предел усиления»
            final OldstyleAe.Params limited = params(v31);
            limited.maxGain = 2f;
            final OldstyleAe.Plan lim = OldstyleAe.plan(rgb, 32, 24, limited, 1f);
            assertTrue(v + " " + base.gainLong, base.gainLong > 2f);
            assertEquals(v, 2f, lim.gainLong, 0f);
        }
        // «Что считать светом»: a small bright patch (1 % of the frame) - at 99.7 % it is the light, at 95 % the wall is
        final float[] patch = new float[32 * 24 * 3];
        java.util.Arrays.fill(patch, 0.1f);
        for (int i = 0; i < 8; i++) for (int c = 0; c < 3; c++) patch[i * 3 + c] = 0.9f;
        final OldstyleAe.Params all = params(false), most = params(false);
        most.highPercentile = 0.95f;
        all.highPercentile = 1f;
        assertEquals(0.9f, OldstyleAe.meter(patch, 32, 24, params(false), 1f).high, 1e-6f);
        assertEquals(0.9f, OldstyleAe.meter(patch, 32, 24, all, 1f).high, 1e-6f);
        assertEquals(0.1f, OldstyleAe.meter(patch, 32, 24, most, 1f).high, 1e-6f);
        assertTrue(OldstyleAe.meter(patch, 32, 24, most, 1f).shortRel > OldstyleAe.meter(patch, 32, 24, all, 1f).shortRel * 8f);
        // «Вес центра кадра»: a window at the frame's edge clipped at L* - a narrow centre does not see it (F near 1), the whole
        // frame alike counts its true share
        final float[] window = scene(9);
        final OldstyleAe.Params narrow = params(false), flat = params(false);
        narrow.centerSigma = 0.2f;
        flat.centerSigma = 2f;
        final float fNarrow = OldstyleAe.meter(window, 32, 24, narrow, 1f).fractionLong, fPlain = OldstyleAe.meter(window, 32, 24, false, 1f).fractionLong,
                fFlat = OldstyleAe.meter(window, 32, 24, flat, 1f).fractionLong;
        assertTrue(fNarrow + " > " + fPlain + " > " + fFlat, fNarrow > fPlain && fPlain > fFlat);
        assertEquals("the whole frame alike: the unclipped share itself (23 of 32 columns)", 23f / 32f, fFlat, 0.02f);
    }

    /** P630h «V1: сила цветовой матрицы»: none at 0, the matrix at 1, more of it past 1; the rows' sums (the white) stay. */
    @Test public void theMatrixAtAShare() {
        final float[] ccm = {1.62f, -0.48f, -0.14f, -0.21f, 1.41f, -0.20f, 0.03f, -0.52f, 1.49f};
        assertTrue(ccm == OldstyleNative.ccmStrength(ccm, 1f));
        assertArrayEquals(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, OldstyleNative.ccmStrength(ccm, 0f), 0f);
        final float[] more = OldstyleNative.ccmStrength(ccm, 1.5f);
        assertEquals(1.93f, more[0], 1e-5f);
        assertEquals(-0.72f, more[1], 1e-5f);
        for (int r = 0; r < 3; r++)
            assertEquals(ccm[r * 3] + ccm[r * 3 + 1] + ccm[r * 3 + 2], more[r * 3] + more[r * 3 + 1] + more[r * 3 + 2], 1e-5f);
        assertTrue(ccm == OldstyleNative.ccmStrength(ccm, Float.NaN));
    }
}
