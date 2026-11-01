package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;
import static org.junit.Assert.*;

public class WhyToneDefaultsTest {
    @Test public void init35UsesApplicableProcessingValues() {
        WhyAe.Settings s = WhyTone.settings();
        assertEquals(.18f, s.aeTarget, 0f);
        assertEquals(1f, s.brightThresh, 0f);
        assertEquals(10f, s.darkThresh, 0f);
        assertEquals(22.5f, s.darkPixelThresh, 0f);
        assertEquals(1f, s.hlBoost, 0f);
        assertEquals(2f, s.highlightStr, 0f);
        assertArrayEquals(new float[]{1f, 1f, 1f, 1.5f}, WhyTone.layerWeights(), 0f);
        assertEquals(.05f, s.noiseMax, 0f);
        assertEquals("noise tolerance is not the legacy exposure exponent", 1f, s.nightDim, 0f);
        assertEquals(0, WhyTone.gammaType());
        assertEquals(2.2f, WhyTone.gamma(), 0f);
        assertEquals("separate night protection remains", 6.5f, s.nightEvHi, 0f);
    }
}
