package com.scamera.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P86 «Движение»: the viewfinder / ring rate and the merge keys the mode replaces. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MotionModeTest {
    @SuppressWarnings("unchecked")
    private static Range<Integer>[] ranges(int... lohi) {
        Range<Integer>[] out = new Range[lohi.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = new Range<>(lohi[2 * i], lohi[2 * i + 1]);
        return out;
    }


    @Test
    public void aFastSeriesTakesFewerFramesBehindAQueue() {
        // P113: an empty merge queue keeps the setting; 2-3 waiting shots: 4 frames; more: 2
        assertEquals(8, MotionMode.framesForQueue(8, 0));
        assertEquals(8, MotionMode.framesForQueue(8, 1));
        assertEquals(4, MotionMode.framesForQueue(8, 2));
        assertEquals(4, MotionMode.framesForQueue(8, 3));
        assertEquals(2, MotionMode.framesForQueue(8, 4));
        assertEquals(2, MotionMode.framesForQueue(8, 9));
        assertEquals(3, MotionMode.framesForQueue(3, 2)); // never more than the setting
        assertEquals(1, MotionMode.framesForQueue(1, 5));
    }

    @Test
    public void aFastRingWithAnEmptyQueueTakesUpToTwelveFramesOfItsWindow() {
        // P488: 60 fps (16.7 ms a frame), no shot waiting: up to 12 of the frames inside the window; 30 fps, a queue or a lowered
        // setting keep the count
        assertEquals(12, MotionMode.ringFrames(8, 0, 15, 16_666_667L));
        assertEquals(10, MotionMode.ringFrames(8, 0, 10, 16_666_667L));
        assertEquals(8, MotionMode.ringFrames(8, 0, 6, 16_666_667L)); // never fewer than wanted
        assertEquals(8, MotionMode.ringFrames(8, 0, 15, 33_333_333L));
        assertEquals(8, MotionMode.ringFrames(8, 1, 15, 16_666_667L));
        assertEquals(4, MotionMode.ringFrames(4, 0, 15, 16_666_667L));
        assertEquals(20, MotionMode.ringFrames(20, 0, 15, 16_666_667L));
        assertEquals(8, MotionMode.ringFrames(8, 0, 15, 0L));
    }

    @Test
    public void replacesOnlyTheMergeKeysOfTheMode() {
        assertTrue(MotionMode.replaces("zsl_frames"));
        assertTrue(MotionMode.replaces("shasta"));
        assertEquals(8f, MotionMode.DEFAULTS.get("zsl_frames"), 0f);
        assertEquals(0f, MotionMode.DEFAULTS.get("shasta"), 0f);
        assertEquals(0.5f, MotionMode.DEFAULTS.get("why_dn_motion_revert"), 0f); // P471 revisited: the photo's 0.5 (owner's A/B)
        assertTrue(!MotionMode.replaces("why_sharp_rl3_amount"));
    }


}
