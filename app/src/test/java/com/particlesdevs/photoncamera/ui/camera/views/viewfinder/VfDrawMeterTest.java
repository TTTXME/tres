package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VfDrawMeterTest {
    @Test
    public void lineReportsDrawnFramesRateGapAndDrawTime() {
        assertEquals("t=+4.0s drawn=58 (29.0 fps) maxGap=41ms draw=1.2/6.8ms",
                VfDrawMeter.line(4000, 2000, 58, 41, 1.24, 6.81));
        assertEquals("t=+0.0s drawn=0 (0.0 fps) maxGap=0ms draw=0.0/0.0ms", VfDrawMeter.line(0, 0, 0, 0, 0, 0));
    }

    /** P236: the fps over the last 2 s for the shot summary, from the recent draw times (any time, not only after a shot). */
    @Test
    public void fpsOverTheLastTwoSecondsCountsTheRecentDraws() {
        final long base = 10_000_000L; // far beyond anything an earlier test drew
        for (int i = 0; i < 60; i++) VfDrawMeter.recent(base + i * 33L); // 30 fps for ~2 s
        assertEquals(30.0, VfDrawMeter.fpsSince(base + 59 * 33L, 2000), 0.6);
        assertEquals("nothing drawn in the window", 0.0, VfDrawMeter.fpsSince(base + 10_000_000L, 2000), 1e-9);
        assertEquals("a frame newer than now is not counted", 0.0, VfDrawMeter.fpsSince(base - 10, 2000), 1e-9);
    }

    /**
     * P611: a stage of a shot (the merge, the post pipeline) as the draws in it per second and its longest gap, edges included;
     * the OnePlus 16 log's stages: 30 fps through the ISP viewfinder's merge, 15 with a 300 ms stall in the 200 MP post.
     */
    @Test
    public void aStageOfTheShotGivesItsFpsAndLongestGap() {
        final long[] ring = new long[64];
        int next = 0;
        for (int i = 0; i < 30; i++) ring[next++] = 1000 + i * 33L;  // 1 s at 30 fps
        assertEquals("the end edge: 43 ms after the last draw", "30.0fps/43ms", VfDrawMeter.spanOf(ring, next, 1000, 2000));
        // a stall of 300 ms from 1.4 s, then 15 fps
        java.util.Arrays.fill(ring, 0);
        next = 0;
        for (int i = 0; i < 12; i++) ring[next++] = 1000 + i * 33L;
        for (int i = 0; i < 9; i++) ring[next++] = 1700 + i * 66L;
        assertEquals("17.0fps/337ms", VfDrawMeter.spanOf(ring, next, 1000, 2000));
        assertEquals("nothing drawn: the whole stage is the gap", "0.0fps/500ms", VfDrawMeter.spanOf(new long[8], 0, 5000, 5500));
        assertEquals("the start edge: first draw 200 ms in", "1.0fps/800ms", VfDrawMeter.spanOf(new long[]{5200, 0, 0}, 1, 5000, 6000));
        assertEquals("too short", "-", VfDrawMeter.spanOf(ring, next, 1000, 1050));
        assertEquals("the wrong way round", "-", VfDrawMeter.spanOf(ring, next, 2000, 1000));
        // a full ring whose oldest draw is after the start no longer covers the stage
        final long[] full = new long[4];
        for (int i = 0; i < 4; i++) full[i] = 3000 + i * 33L;
        assertEquals("?", VfDrawMeter.spanOf(full, 0, 2900, 3200));
        assertTrue(VfDrawMeter.spanOf(full, 0, 3000, 3200).startsWith("20.0fps/"));
        assertTrue("the ring keeps a 200 MP shot's 36 s of merge and post at 60 fps", VfDrawMeter.RECENT >= 36 * 60);
    }

    /** P611: the shared ring through {@link VfDrawMeter#span}: the draws in a stage far from the other tests' times. */
    @Test
    public void spanReadsTheRecentDraws() {
        final long base = 50_000_000L;
        for (int i = 0; i < 90; i++) VfDrawMeter.recent(base + i * 33L);
        final String s = VfDrawMeter.span(base, base + 89 * 33L);
        assertTrue(s, s.startsWith("30.") && s.endsWith("fps/33ms"));
    }
}
