package com.scamera.app.ui.camera.views.viewfinder;

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

    /**
     * P616: the cause of a gap - the 31254 log's: a 298 ms draw blocked in the RAW upload (296 ms of it), the new camera's stream
     * (no frame for most of the gap), the settings screen (paused), a lens switch (restart), a frame there but drawn late.
     */
    @Test
    public void aGapIsClassifiedByWhereItsTimeWent() {
        assertEquals(VfDrawMeter.UPLOAD, VfDrawMeter.classify(1000, 1298, 298, 296, 990, false, false));
        assertEquals("the draw, not its upload", VfDrawMeter.DRAW, VfDrawMeter.classify(1000, 1200, 150, 10, 990, false, false));
        assertEquals("the frame came 280 ms after the draw before", VfDrawMeter.CAMERA, VfDrawMeter.classify(1000, 1300, 15, 5, 1280, false, false));
        assertEquals("a frame there, drawn late", VfDrawMeter.WAIT, VfDrawMeter.classify(1000, 1300, 15, 5, 1010, false, false));
        assertEquals("no frame time known: a wait", VfDrawMeter.WAIT, VfDrawMeter.classify(1000, 1300, 15, 5, -1, false, false));
        assertEquals(VfDrawMeter.HIDDEN, VfDrawMeter.classify(1000, 3000, 300, 290, 2990, true, false));
        assertEquals(VfDrawMeter.RESTART, VfDrawMeter.classify(1000, 1800, 20, 0, 1790, false, true));
        assertEquals("the first draw has no gap", VfDrawMeter.NONE, VfDrawMeter.classify(-1, 1000, 300, 300, 990, false, false));
        assertEquals("upload", VfDrawMeter.causeName(VfDrawMeter.UPLOAD));
        assertEquals("edge", VfDrawMeter.causeName(VfDrawMeter.NONE));
    }

    /** P616: a stage's longest gap named with its cause and node; paused and restarted time left out of the fps and the gap. */
    @Test
    public void aStageNamesItsLongestGapAndLeavesThePausedTimeOut() {
        final int len = 64;
        long[] t = new long[len];
        byte[] c = new byte[len];
        short[] d = new short[len], u = new short[len];
        String[] node = new String[len];
        int n = 0;
        for (int i = 0; i < 15; i++) { t[n] = 1000 + i * 33L; c[n] = VfDrawMeter.WAIT; d[n] = 12; n++; }
        // the post's stall: one draw of 298 ms, 296 of it the upload, in ScamHdrDenoise
        t[n] = t[n - 1] + 298; c[n] = VfDrawMeter.UPLOAD; d[n] = 298; u[n] = 296; node[n] = "ScamHdrDenoise"; n++;
        while (t[n - 1] + 33 <= 2000) { t[n] = t[n - 1] + 33; c[n] = VfDrawMeter.WAIT; d[n] = 12; n++; }
        String[] r = VfDrawMeter.stageOf(t, c, d, u, node, n, 1000, 2000, -1);
        assertTrue(r[0], r[0].endsWith("fps/298ms"));
        assertEquals("298ms:upload(296)@ScamHdrDenoise", r[1]);
        assertEquals("the same span as P611's", VfDrawMeter.spanOf(t, n, 1000, 2000), r[0]);

        // the settings screen during the post: no draw until the resume's first one (hidden) after the stage
        t = new long[len]; c = new byte[len]; d = new short[len]; u = new short[len]; node = new String[len]; n = 0;
        for (int i = 0; i < 10; i++) { t[n] = 4000 + i * 33L; c[n] = VfDrawMeter.WAIT; n++; }
        t[n] = 6500; c[n] = VfDrawMeter.HIDDEN; n++;
        r = VfDrawMeter.stageOf(t, c, d, u, node, n, 4000, 5000, -1);
        assertEquals("the draws over the 0.3 s shown, the paused 0.7 s left out", "33.7fps/33ms", r[0]);
        assertEquals("hidden=703ms", r[1]);
        // still paused when the summary is written (no draw after the stage yet)
        n--;
        t[n] = 0;
        c[n] = VfDrawMeter.NONE;
        r = VfDrawMeter.stageOf(t, c, d, u, node, n, 4000, 5000, 4400);
        assertEquals("hidden=703ms", r[1]);
        assertEquals("not paused: the tail is a gap of its own", "703ms:no_draw_yet", VfDrawMeter.stageOf(t, c, d, u, node, n, 4000, 5000, -1)[1]);
        r = VfDrawMeter.stageOf(t, c, d, u, node, n, 4300, 5000, 4250);
        assertEquals("nothing shown: unknown", "?", r[0]);

        // a lens switch in the merge, then the new camera's slow first frames
        t = new long[len]; c = new byte[len]; d = new short[len]; u = new short[len]; node = new String[len]; n = 0;
        for (int i = 0; i < 6; i++) { t[n] = 8000 + i * 33L; c[n] = VfDrawMeter.WAIT; n++; }
        t[n] = t[n - 1] + 600; c[n] = VfDrawMeter.RESTART; n++;
        t[n] = t[n - 1] + 180; c[n] = VfDrawMeter.CAMERA; n++;
        while (t[n - 1] + 33 <= 9500) { t[n] = t[n - 1] + 33; c[n] = VfDrawMeter.WAIT; n++; }
        r = VfDrawMeter.stageOf(t, c, d, u, node, n, 8000, 9500, -1);
        assertTrue(r[0], r[0].endsWith("fps/180ms"));
        assertEquals("180ms:camera,restart=600ms", r[1]);

        // a smooth stage has no note
        t = new long[len]; c = new byte[len]; n = 0;
        for (int i = 0; i < 31; i++) { t[n] = 20000 + i * 33L; c[n] = VfDrawMeter.WAIT; n++; }
        r = VfDrawMeter.stageOf(t, c, new short[len], new short[len], new String[len], n, 20000, 21000, -1);
        assertEquals(null, r[1]);
        assertEquals("-", VfDrawMeter.stageOf(t, c, d, u, node, n, 20000, 20050, -1)[0]);
    }
}
