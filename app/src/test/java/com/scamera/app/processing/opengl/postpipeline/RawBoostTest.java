package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** P501: the camera's post-RAW gain as the WHY tone's extra gain room. */
public class RawBoostTest {
    @Test public void factorOfTheBoostKey() {
        assertEquals(1f, RawBoost.factor(null), 0f);
        assertEquals(1f, RawBoost.factor(100), 0f);
        assertEquals(1f, RawBoost.factor(80), 0f);              // below 100: nothing taken away
        assertEquals(2.09f, RawBoost.factor(209), 1e-6f);       // Pixel 10 Pro, ISO 289
        assertEquals(1.37f, RawBoost.factor(137), 1e-6f);       // Pixel 7
        assertEquals(64f, RawBoost.factor(100_000), 0f);        // capped
        assertEquals(1f, RawBoost.of(null), 0f);
    }
}
