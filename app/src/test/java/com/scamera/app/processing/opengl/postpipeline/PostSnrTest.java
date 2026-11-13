package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P648 «Подстройка под свет (SNR)»: the night curve of the shot's SNR (0 by day and for an unknown SNR, 1 at night, monotone and
 * continuous between) and the flat smoothing's share (none off, by day and without the measured noise; the shader's side:
 * tools/check_post_snr.py).
 */
public class PostSnrTest {
    @Test public void nightCurve() {
        assertEquals("unknown SNR: day", 0f, PostSnr.night(Float.NaN), 0f);
        assertEquals(0f, PostSnr.night(0f), 0f);
        assertEquals(0f, PostSnr.night(-3f), 0f);
        assertEquals(0f, PostSnr.night(Float.POSITIVE_INFINITY), 0f);
        assertEquals("daylight (phones: 260 - 520)", 0f, PostSnr.night(277f), 0f);
        assertEquals(0f, PostSnr.night(PostSnr.DAY), 0f);
        assertEquals(1f, PostSnr.night(PostSnr.NIGHT), 0f);
        assertEquals(1f, PostSnr.night(3.04f), 0f);
        assertEquals("the geometric middle", 0.5f, PostSnr.night((float) Math.sqrt(PostSnr.DAY * PostSnr.NIGHT)), 1e-5f);
        float last = 1f;
        for (float snr = 1f; snr <= 60f; snr += 0.01f) {
            final float n = PostSnr.night(snr);
            assertTrue(snr + ": " + n, n <= last && n >= 0f && n <= 1f);
            assertTrue("continuous at " + snr, Math.abs(PostSnr.night(snr + 1e-3f) - n) < 2e-3f);
            last = n;
        }
        // smooth ends: no step at either knee
        assertTrue(PostSnr.night(PostSnr.DAY * 0.999f) < 1e-4f);
        assertTrue(PostSnr.night(PostSnr.NIGHT * 1.001f) > 1f - 1e-4f);
    }

    /** P648c: the flat-area smoothing's share - FLAT x night; 0 off, by day and without the measured noise. */
    @Test public void flatShare() {
        for (float snr : new float[]{Float.NaN, 30f, 58.16f, 277f})
            assertEquals(0f, PostSnr.flatNight(snr, true, 1e-4f, 1e-6f), 0f);
        for (float snr : new float[]{3f, 11.8f, 22f})
            assertEquals(0f, PostSnr.flatNight(snr, false, 1e-4f, 1e-6f), 0f);
        assertEquals("no measured noise", 0f, PostSnr.flatNight(4f, true, Float.NaN, 1e-6f), 0f);
        assertEquals(0f, PostSnr.flatNight(4f, true, 0f, 0f), 0f);
        assertEquals(PostSnr.FLAT, PostSnr.flatNight(4f, true, 1e-4f, 1e-6f), 0f);
        assertEquals(PostSnr.FLAT * PostSnr.night(11.8f), PostSnr.flatNight(11.8f, true, 1e-4f, 0f), 0f);
    }

    @Test public void logLine() {
        assertEquals("post SNR (P648): off", PostSnr.describe(5f, false));
        assertTrue(PostSnr.describe(277f, true).contains("night=0.000 flat 0.000"));
        assertTrue(PostSnr.describe(4f, true).contains("night=1.000 flat 1.000 (factor up to x3.0, grain 1.0)"));
    }
}
