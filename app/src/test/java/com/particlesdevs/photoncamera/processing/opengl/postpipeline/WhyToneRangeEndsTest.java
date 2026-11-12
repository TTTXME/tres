package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.settings.SettingsNumericRules;

import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * P614 (owner, 2026-11-11): eight rows V1 and V2 share widened to the reference's ranges - AE highlight protection threshold
 * 0.5-10, highlight / shadow strength 0-5, macro contrast 0.5-2, gamma 1-3, AgX min EV -15..-2, AgX EV +-3, ACES toe 0.005-0.3.
 * V1's Java at the ends: the AE and its synthetic exposures finite and in range, the viewfinder's fused curve finite and never
 * falling, the AgX / ACES curves finite and never falling (the shaders' formulas: why/combine agxTonescale, toneAces,
 * inverseAcescg, import why_fusion acescg - the same clamps).
 */
public class WhyToneRangeEndsTest {
    private static final String P = "pref_scam_hybrid_why_";

    @Test public void theWidenedRangesAreTheReferences() {
        assertArrayEquals(new double[]{0.5, 10, 0}, SettingsNumericRules.bounds(P + "hl_overflow"), 0);
        assertArrayEquals(new double[]{0, 5, 0}, SettingsNumericRules.bounds(P + "highlight_str"), 0);
        assertArrayEquals(new double[]{0, 5, 0}, SettingsNumericRules.bounds(P + "shadow_str"), 0);
        assertArrayEquals(new double[]{0.5, 2, 0}, SettingsNumericRules.bounds(P + "macro_contrast"), 0);
        assertArrayEquals(new double[]{1, 3, 0}, SettingsNumericRules.bounds(P + "gamma"), 0);
        assertArrayEquals(new double[]{-15, -2, 0}, SettingsNumericRules.bounds(P + "agx_min_ev"), 0);
        assertArrayEquals(new double[]{-3, 3, 0}, SettingsNumericRules.bounds(P + "agx_ev"), 0);
        assertArrayEquals(new double[]{0.005, 0.3, 0}, SettingsNumericRules.bounds(P + "aces_toe"), 0);
    }

    private static final int W = 96, H = 64;

    /** WhyAeTest's synthetic frame (a cubic ramp, coloured; a 7x corner above the ceiling with bento). */
    private static FloatBuffer synth(double scale, boolean bento) {
        float[] out = new float[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double t = x / (double) (W - 1), u = y / (double) (H - 1);
                double base = 0.002 + 1.5 * t * t * t;
                double k = bento && x >= 80 && y < 12 ? 7.0 : 1.0;
                int o = (y * W + x) * 3;
                out[o] = (float) (base * (0.7 + 0.6 * u) * scale * k);
                out[o + 1] = (float) (base * (1.0 - 0.3 * u * t) * scale * k);
                out[o + 2] = (float) (base * (0.5 + 0.8 * ((x * 7 + y * 3) % 11) / 10.0) * scale * k);
            }
        }
        return FloatBuffer.wrap(out);
    }

    private static void finitePositive(String what, float[] em) {
        for (float v : em) assertTrue(what + " " + v, Float.isFinite(v) && v > 0f);
    }

    @Test public void theAeAndItsExposuresStayFiniteAtTheEnds() {
        for (float str : new float[]{0f, 5f})
            for (float overflow : new float[]{0.5f, 10f})
                for (boolean v207 : new boolean[]{false, true})
                    for (double scale : new double[]{0.02, 1.0, 6.0})
                        for (boolean bento : new boolean[]{false, true}) {
                            WhyAe.Settings s = new WhyAe.Settings();
                            s.highlightStr = str;
                            s.shadowStr = str;
                            s.hlOverflow = overflow;
                            s.v207 = v207;
                            s.minEv = -15f;
                            s.ev = 3f;
                            String c = "str " + str + " overflow " + overflow + " v207 " + v207 + " scale " + scale + " bento " + bento;
                            WhyAe.Result r = WhyAe.smartHdr(synth(scale, bento), 3, W, H, s, 100, 6400);
                            assertTrue(c + " ae " + r.ae, Float.isFinite(r.ae) && r.ae >= s.minLimit - 1e-6f && r.ae <= 9f);
                            // the highlight strength up to the setting (with real headroom up to what brings it under bright_thresh)
                            assertTrue(c + " effH " + r.effH, Float.isFinite(r.effH) && r.effH >= 0f && (r.effH <= str + 1e-6f || r.hr > 0f));
                            assertTrue(c + " effS " + r.effS, Float.isFinite(r.effS) && r.effS >= 0f);
                            float[] em = WhyAe.exposures(r, WhyFusion.headroomExposure(WhyAe.darkestExposure(r), r.ae, r.clip, r.hr));
                            finitePositive(c + " exposures", em);
                            finitePositive(c + " exposures207", WhyFusion.exposures207(r));
                            assertTrue(c + " darkest <= 1", em[0] <= 1f && WhyFusion.exposures207(r)[0] <= 1f);
                        }
    }

    @Test public void theFusedCurveIsFiniteAndNeverFallsAtTheEnds() {
        final float[] lw = {0.8f, 1f, 0.1f, 1f};
        final float[] table = new float[FusedCurve.SIZE];
        for (float effH : new float[]{0f, 5f})
            for (float effS : new float[]{0f, 5f})
                for (float gamma : new float[]{1f, 3f})
                    for (float toe : new float[]{0.005f, 0.3f}) {
                        WhyAe.Result r = new WhyAe.Result();
                        r.effH = effH;
                        r.effS = effS;
                        for (float[] em : new float[][]{WhyAe.exposures(r), WhyFusion.exposures207(r)}) {
                            FusedCurve.fill(table, em, lw, 0.6f, 0.45f, 1f / gamma, toe, 0.04f);
                            for (int i = 0; i < table.length; i++) {
                                String c = "effH " + effH + " effS " + effS + " gamma " + gamma + " toe " + toe + " i " + i;
                                assertTrue(c + " " + table[i], Float.isFinite(table[i]) && table[i] >= 0f);
                                if (i > 0) assertTrue(c + " falls " + table[i - 1] + " -> " + table[i], table[i] >= table[i - 1] - 1e-6f);
                            }
                        }
                    }
    }

    @Test public void agxAndAcesNeverFallAtTheEnds() {
        for (float minEv : new float[]{-15f, -2f})
            for (float maxEv : new float[]{1f, 8f})
                for (float ev : new float[]{-3f, 0f, 3f})
                    for (float slope : new float[]{1f, 2.45f, 5f}) {
                        float last = -1f;
                        for (int i = 0; i <= 1000; i++) {
                            float y = ToneCurve.agxAt(i / 1000f, slope, 1.35f, 2.0f, minEv, maxEv, ev);
                            String c = "AgX min " + minEv + " max " + maxEv + " ev " + ev + " slope " + slope + " x " + i / 1000f;
                            assertTrue(c + " " + y, Float.isFinite(y) && y >= 0f && y <= 1f);
                            assertTrue(c + " falls " + last + " -> " + y, y >= last - 1e-5f);
                            last = y;
                        }
                    }
        for (float toe : new float[]{0.005f, 0.3f}) {
            float last = -1f;
            for (int i = 0; i <= 2000; i++) {
                float v = (float) Math.pow(2.0, -16 + i * 22.0 / 2000);
                float y = ToneCurve.aces(v, toe, 1f, 2.61f, 0.59f);
                assertTrue("ACES toe " + toe + " v " + v + " " + y, Float.isFinite(y) && y >= 0f && y >= last - 1e-6f);
                last = y;
            }
        }
    }
}
