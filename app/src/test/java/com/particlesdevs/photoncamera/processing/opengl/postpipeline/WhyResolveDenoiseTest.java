package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P443: the reference app's resolve denoise (WhyResolveDenoise, scamdn/why_resolve.glsl): its uniforms from the camera model, the
 * frames without a map, and the shader keeping the reference app's constants (the host replay checks the maths against a numpy
 * port of its s032 to 1e-7 in fp32).
 */
public class WhyResolveDenoiseTest {
    @Test public void theDefaultsAreTheReferenceApps() {
        assertEquals(0.4f, WhyResolveDenoise.FACTOR, 0f);
        assertEquals(0.85f, WhyResolveDenoise.LUMA_REVERT, 0f);
        assertEquals(0.5f, WhyResolveDenoise.MOTION_REVERT, 0f);
        assertEquals(0.15f, WhyResolveDenoise.GRAIN, 0f);
        assertEquals(0.125f, WhyResolveDenoise.OUTLIER, 0f);
    }

    @Test public void theNoiseModelSplitsTheSlopeOverYuv() {
        assertArrayEquals(new float[]{0.562f * 1e-3f, 0.25f * 1e-3f, 0.25f * 1e-3f, 2e-6f}, WhyResolveDenoise.noiseModel(1e-3f, 2e-6f), 0f);
    }

    @Test public void theCornerBoostOnlyBelowSnr4() {
        // SNR = 0.18 / sqrt(0.18 S + O): O alone sets it here
        assertEquals(4f, WhyResolveDenoise.vignetteBoost(0f, 0.18f * 0.18f), 1e-4f);              // SNR 1
        assertEquals(5.3333f - 1.3333f * 2f, WhyResolveDenoise.vignetteBoost(0f, 0.09f * 0.09f), 1e-3f);   // SNR 2
        assertEquals(0f, WhyResolveDenoise.vignetteBoost(0f, 0.04f * 0.04f), 0f);                // SNR 4.5
        assertEquals(0f, WhyResolveDenoise.vignetteBoost(1.1351182e-3f, 1.9668776e-6f), 0f);     // the night port 230628: SNR 12.5
        assertEquals(4f, WhyResolveDenoise.vignetteBoost(1f, 1f), 0f);
    }

    @Test public void framesWithoutAMapUseTheFullFrameCount() {
        assertEquals(12.84f, WhyResolveDenoise.defaultFrames(12.84f, 20), 1e-6f);
        assertEquals(20f, WhyResolveDenoise.defaultFrames(43f, 20), 0f);                        // within the normals
        assertEquals(20f, WhyResolveDenoise.defaultFrames(Float.NaN, 20), 1e-6f);
        assertEquals(1f, WhyResolveDenoise.defaultFrames(Float.NaN, 1), 0f);
        assertEquals(1f, WhyResolveDenoise.defaultFrames(0.2f, 0), 0f);
    }

    private static String shader() throws Exception {
        Path p = Paths.get("src/main/assets/shaders/scamdn/why_resolve.glsl");
        if (!Files.exists(p)) p = Paths.get("app/src/main/assets/shaders/scamdn/why_resolve.glsl");
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void theShaderKeepsTheReferenceConstants() throws Exception {
        final String s = shader();
        for (String token : new String[]{"vec3(0.2126390, 0.7151688, 0.0721923)", "0.297327 * u + 0.100944 * v", "1.25 - num_merged_frames * 0.25",
                "* 0.70", "max(0.04, 0.22 * center_y)", "max(0.65, edge_risk)", "smoothstep(0.25, 0.5, accum_color.w)", "0x3504f333",
                "int(0xf1bbcdcbu)", "(2.0 / 8589934592.0)", "0.5 / 0.69314718056", "vignette_dist_sq / 0.25 - 0.25", "edge_risk > 0.05"})
            assertTrue(token, s.contains(token));
    }
}
