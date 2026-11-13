package com.scamera.app.gallery.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P617: when the gallery's text index may read its next photo, and how long it rests after one. */
public class OcrPaceTest {
    @Test
    public void waitsForTheOpenDelayAndTheGridsQuietTime() {
        long now = 100_000;
        assertEquals("the open's delay", 4_000, OcrPace.waitMs(now, now + 4_000, 0, false, 0));
        assertEquals("the grid moved", OcrPace.QUIET_MS, OcrPace.waitMs(now, now - 1, now + OcrPace.QUIET_MS, false, 0));
        assertEquals("the longer of both", 4_000, OcrPace.waitMs(now, now + 4_000, now + 1_000, false, 0));
        assertEquals("free to read", 0, OcrPace.waitMs(now, now - 1, now - 1, false, 0));
    }

    @Test
    public void waitsWhileAShotIsProcessedOrThePhoneIsWarm() {
        long now = 100_000;
        assertTrue(OcrPace.waitMs(now, 0, 0, true, 0) > 0);
        assertTrue("moderate", OcrPace.waitMs(now, 0, 0, false, OcrPace.HOT_STATUS) > 0);
        assertEquals("light is fine", 0, OcrPace.waitMs(now, 0, 0, false, OcrPace.HOT_STATUS - 1));
    }

    @Test
    public void restsAsLongAsThePhotoTookAndLongerAfterABatch() {
        assertEquals(1_200, OcrPace.restMs(1_200, 1));
        assertEquals("a quick photo still rests", OcrPace.MIN_REST_MS, OcrPace.restMs(10, 2));
        assertEquals(1_200 + OcrPace.BATCH_REST_MS, OcrPace.restMs(1_200, OcrPace.BATCH));
        assertEquals(1_200, OcrPace.restMs(1_200, OcrPace.BATCH + 1));
    }

    @Test
    public void aWaitLooksAgainWithinASecond() {
        assertEquals(OcrPace.POLL_MS, OcrPace.sleepMs(60_000));
        assertEquals(300, OcrPace.sleepMs(300));
        assertEquals(1, OcrPace.sleepMs(0));
    }
}
