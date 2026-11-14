package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P701: the WHY AE of a YUV merge shot (WhyTone.yuvExposure): the day target (the camera's AE exposed the frames; the vivo X200 Pro
 * front camera's indoor series read as 80 % night, AE 0.5 against the day target's 0.57) times the shadow toe's geometric-mean factor
 * (the meter sees the scene before the toe). Any other shot: nothing changes.
 */
public class WhyYuvExposureTest {
    /** A flat grey scene of {@code level} (RGB, linear). */
    private static FloatBuffer grey(int w, int h, float level) {
        final FloatBuffer b = FloatBuffer.allocate(w * h * 3);
        for (int i = 0; i < w * h * 3; i++) b.put(level);
        b.flip();
        return b;
    }

    /** The AE of the grey scene at {@code level}, its median at EV100 4.5 (a night scene by P442's rule). */
    private static float ae(WhyAe.Settings s, float level) {
        s.evWhite = 4.5f - (float) (Math.log(level) / Math.log(2));
        return WhyAe.smartHdr(grey(64, 48, level), 3, 64, 48, s, 0, 0).ae;
    }

    @Test public void notAYuvShotChangesNothing() {
        final WhyAe.Settings s = WhyTone.settings(), t = WhyTone.settings();
        assertNull(WhyTone.yuvExposure(s, 0f));
        assertNull(WhyTone.yuvExposure(s, Float.NaN));
        assertEquals(t.aeTarget, s.aeTarget, 0f);
        assertEquals(t.nightAeTarget, s.nightAeTarget, 0f);
        assertEquals(ae(t, 0.25f), ae(s, 0.25f), 0f);
    }

    @Test public void aYuvShotKeepsTheDayTargetTimesTheToesFactor() {
        final WhyAe.Settings s = WhyTone.settings();
        final float day = s.aeTarget;
        final String line = WhyTone.yuvExposure(s, 1.071f);
        assertTrue(line, line != null && line.contains("YUV merge"));
        assertEquals(day * 1.071f, s.aeTarget, 1e-6f);
        assertEquals(s.aeTarget, s.nightAeTarget, 0f);
        // the factor is bounded
        final WhyAe.Settings w = WhyTone.settings();
        WhyTone.yuvExposure(w, 9f);
        assertEquals(day * 2f, w.aeTarget, 1e-6f);
    }

    @Test public void theNightRuleNoLongerDarkensAYuvShot() {
        final float level = 0.25f;  // ae within the limits either way (0.5 .. 5)
        final WhyAe.Settings raw = WhyTone.settings(), yuv = WhyTone.settings();
        WhyTone.yuvExposure(yuv, 1f);
        final float aRaw = ae(raw, level), aYuv = ae(yuv, level);
        assertEquals(raw.nightAeTarget / level, aRaw, 1e-3f);
        assertEquals(raw.aeTarget / level, aYuv, 1e-3f);
        assertTrue(aYuv > aRaw);
    }
}
