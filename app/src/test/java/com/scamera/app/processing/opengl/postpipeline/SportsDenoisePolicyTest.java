package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.*;

public class SportsDenoisePolicyTest {
    private static SportsDenoisePolicy measured(boolean sports, float snr) {
        return SportsDenoisePolicy.of(sports, sports, snr, Float.NaN, Float.NaN);
    }

    private static void bits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    @Test public void brightSportsPreserveEveryPreviousDenoiseAmount() {
        for (float snr : new float[]{24f, 40f, 80f, 1000f}) {
            SportsDenoisePolicy p = measured(true, snr);
            ScamDenoiseTables.Config c = new ScamDenoiseTables.Config();
            c.lumaMult = .83f; c.chromaMult = 1.17f; c.revertMult = .67f;
            p.apply(c);
            bits(.85f, p.sharpKeep);
            bits(.83f, c.lumaMult); bits(1.17f, c.chromaMult); bits(.67f, c.revertMult);
            bits(-0f, p.residualGrain(-0f));
            float[][] t = {{.003f, .005f}, {.001f, .002f}};
            p.applyMs(t, new float[]{.3f, .5f});
            bits(.003f, t[0][0]); bits(.005f, t[0][1]); bits(.001f, t[1][0]); bits(.002f, t[1][1]);
        }
    }

    @Test public void ordinaryPhotoKeepsItsAmountsEvenWithVeryLowSnr() {
        SportsDenoisePolicy p = measured(false, 0f);
        ScamDenoiseTables.Config c = new ScamDenoiseTables.Config();
        p.apply(c);
        bits(0f, p.lowSnr); bits(0f, p.sharpKeep);
        bits(1f, c.lumaMult); bits(1f, c.chromaMult); bits(1f, c.revertMult);
        bits(.5f, p.residualGrain(.5f));
        float[][] t = {{.003f, .005f}}; float[] motion = {.3f, .5f};
        p.applyMs(t, motion);
        bits(.003f, t[0][0]); bits(.005f, t[0][1]); bits(.3f, motion[0]); bits(.5f, motion[1]);
    }

    @Test public void lowSnrStopsReturningTheRejectedBaseNoiseAndFiltersBothComponents() {
        SportsDenoisePolicy p = measured(true, 8f);
        ScamDenoiseTables.Config before = new ScamDenoiseTables.Config(), after = new ScamDenoiseTables.Config();
        p.apply(after);
        float[][] oldL = ScamDenoiseTables.luma(before, 8f, null), newL = ScamDenoiseTables.luma(after, 8f, null);
        float[][] oldC = ScamDenoiseTables.chroma(before, 8f, null), newC = ScamDenoiseTables.chroma(after, 8f, null);
        bits(0f, p.sharpKeep);
        for (int b = 0; b < ScamDenoiseTables.BANDS; b++) {
            assertEquals(oldL[b][0] * 1.3f, newL[b][0], 1e-6f);
            assertEquals(oldL[b][1] * .35f, newL[b][1], 1e-6f);
            assertEquals(oldC[b][0] * 1.35f, newC[b][0], 1e-6f);
        }
        assertEquals(.125f, p.residualGrain(.5f), 0f);
        float[][] t = {{.004f, .01f}, {0f, .005f}}; float[] motion = {.3f, .5f};
        p.applyMs(t, motion);
        assertEquals(.008f, t[0][0], 1e-7f); assertEquals(.0135f, t[0][1], 1e-7f);
        assertEquals(.3f, motion[0], 1e-7f); bits(.5f, motion[1]);
    }

    @Test public void transitionIsSmoothBoundedAndMonotonic() {
        float lastQ = 1f, lastSharp = 0f;
        for (int n = 0; n <= 640; n++) {
            SportsDenoisePolicy p = measured(true, n / 16f);
            assertTrue(Float.isFinite(p.lowSnr));
            assertTrue(p.lowSnr >= 0f && p.lowSnr <= lastQ);
            assertTrue(p.sharpKeep >= lastSharp && p.sharpKeep <= .85f);
            lastQ = p.lowSnr; lastSharp = p.sharpKeep;
        }
        SportsDenoisePolicy half = measured(true, 16f);
        assertEquals(.5f, half.lowSnr, 0f); assertEquals(.425f, half.sharpKeep, 0f);
        assertTrue(measured(true, 8.001f).lowSnr > .99999f);
        assertTrue(measured(true, 23.999f).lowSnr < .00001f);
    }

    @Test public void measuredSnrWinsOverTheSingleFrameModel() {
        SportsDenoisePolicy p = SportsDenoisePolicy.of(true, true, 80f, 1f, 1f);
        assertEquals("measured", p.source); bits(80f, p.snr); bits(0f, p.lowSnr);
        p = SportsDenoisePolicy.of(true, true, 4f, 1e-8f, 0f);
        assertEquals("measured", p.source); bits(1f, p.lowSnr);
    }

    @Test public void unavailableMeasurementUsesOnlyFiniteNonnegativeNoiseAndLabelsTheFallback() {
        for (float invalid : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f}) {
            SportsDenoisePolicy p = SportsDenoisePolicy.of(true, true, invalid, 0f, .00050625f);
            assertEquals("model@0.18", p.source);
            assertEquals(8f, p.snr, 1e-6f); assertEquals(1f, p.lowSnr, 1e-6f);
        }
        for (float[] noise : new float[][]{{0f, 0f}, {-1f, 1f}, {1f, -1f}, {Float.NaN, 1f},
                {1f, Float.NaN}, {Float.POSITIVE_INFINITY, 0f}}) {
            SportsDenoisePolicy p = SportsDenoisePolicy.of(true, true, Float.NaN, noise[0], noise[1]);
            assertEquals("unknown", p.source); assertTrue(Float.isNaN(p.snr));
            bits(0f, p.lowSnr); bits(.85f, p.sharpKeep);
        }
    }

    @Test public void filmNeutralGrainRemainsExactlyZeroAtEveryNoiseLevel() {
        for (float snr : new float[]{0f, 8f, 16f, 24f, 80f, Float.NaN})
            bits(0f, measured(true, snr).residualGrain(0f));
    }

    @Test public void disablingSharpPriorityDoesNotDisableTechnicalLowSnrDenoising() {
        SportsDenoisePolicy p = SportsDenoisePolicy.of(true, false, 4f, 0f, 0f);
        ScamDenoiseTables.Config c = new ScamDenoiseTables.Config(); p.apply(c);
        bits(0f, p.sharpKeep); assertTrue(c.lumaMult > 1f); assertTrue(c.chromaMult > 1f);
        float[][] t = {{.004f, .01f}}; float[] motion = {.3f, .5f}; p.applyMs(t, motion);
        bits(.3f, motion[0]); assertTrue(t[0][0] > .004f);
    }
}
