package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P662: the FULLRES merge after the camera's close waits for the device's onClosed, the ZSL ring's release and the memory
 * available to settle - not a fixed 300 ms - and never longer than those 300 ms.
 */
public class ClosedForProcessingWaitTest {
    /** A fake clock: sleeps and waits advance it. */
    private static final class Clock {
        long now;
        long get() { return now; }
        void sleep(long ms) { now += ms; }
    }

    @Test
    public void aClosedDeviceAndSettledMemoryEndTheWaitEarly() {
        final Clock c = new Clock();
        final long[] mem = {1000L << 20};
        CaptureController.ClosedWait w = CaptureController.ClosedWait.run(300,
                left -> { c.now += 40; return true; },     // onClosed after 40 ms
                left -> { c.now += 5; return true; },      // the ring released 5 ms later
                () -> mem[0], c::get, c::sleep);
        assertTrue(w.closed);
        assertTrue(w.released);
        assertTrue(w.settled);
        assertEquals(40, w.closedMs);
        assertEquals(45, w.releasedMs);
        assertEquals("three steady 10 ms steps after the release", 75, w.totalMs);
        assertTrue("well under the old fixed sleep", w.totalMs < CaptureController.CLOSED_FOR_PROCESSING_MS);
        assertEquals(1000L << 20, w.availBytes);
    }

    @Test
    public void memoryStillComingBackIsWaitedFor() {
        final Clock c = new Clock();
        final long[] mem = {1000L << 20};
        // the HAL hands back 64 MB every 10 ms for 100 ms, then nothing
        CaptureController.ClosedWait w = CaptureController.ClosedWait.run(300, left -> true, left -> true,
                () -> c.now < 100 ? (mem[0] = (1000L << 20) + (c.now / 10) * (64L << 20)) : mem[0], c::get, c::sleep);
        assertTrue(w.settled);
        assertTrue("waited until the memory stopped growing", w.totalMs >= 100);
        assertTrue(w.totalMs <= CaptureController.CLOSED_FOR_PROCESSING_MS);
        assertEquals((1000L << 20) + 9 * (64L << 20), w.availBytes);
    }

    @Test
    public void theWaitNeverExceedsTheOldSleep() {
        final Clock c = new Clock();
        // no onClosed (its budget used up), memory always growing
        CaptureController.ClosedWait w = CaptureController.ClosedWait.run(300,
                left -> { c.now += left; return false; }, left -> true, () -> 1000L + c.now * (32L << 20), c::get, c::sleep);
        assertFalse(w.closed);
        assertFalse(w.settled);
        assertTrue(w.totalMs <= 300);
        final Clock d = new Clock();
        w = CaptureController.ClosedWait.run(300, left -> true, left -> true, () -> 1000L + d.now * (32L << 20), d::get, d::sleep);
        assertFalse(w.settled);
        assertTrue(w.totalMs <= 300);
        assertTrue(w.toString(), w.toString().contains("was a fixed 300 ms"));
    }
}
