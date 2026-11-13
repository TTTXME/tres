package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;
import static org.junit.Assert.*;

public class WhyToneDefaultsTest {
    // The shipped tone defaults (P512 / P513 v3_3 values, the reference fusion layout); updated 2026-11-09 from the pre-v3_3 pins
    @Test public void init35UsesApplicableProcessingValues() {
        WhyAe.Settings s = WhyTone.settings();
        assertEquals(.15f, s.aeTarget, 0f);
        assertEquals(.9f, s.brightThresh, 0f);
        assertEquals(12.5f, s.darkThresh, 0f);
        assertEquals(22f, s.darkPixelThresh, 0f);
        assertEquals(1.1f, s.hlBoost, 0f);
        assertEquals(2.1f, s.highlightStr, 0f);
        assertArrayEquals("the reference layout's classic weights", new float[]{.7f, 2f, 1f, 1f}, WhyTone.layerWeights(), 0f);
        assertEquals(.05f, s.noiseMax, 0f);
        assertEquals("noise tolerance is not the legacy exposure exponent", 1f, s.nightDim, 0f);
        assertEquals(0, WhyTone.gammaType());
        assertEquals(2.2f, WhyTone.gamma(), 0f);
        assertEquals("separate night protection remains", 6.5f, s.nightEvHi, 0f);
    }
}
