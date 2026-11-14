package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/** P707: the per-frame ring trims read the ring's capacity at most every TTL_NS. */
public class RingCapacityMemoTest {
    @Test
    public void readsOncePerTtlAndAgainAfterIt() {
        final AtomicInteger reads = new AtomicInteger();
        final int[] setting = {50};
        final RingCapacityMemo memo = new RingCapacityMemo(() -> { reads.incrementAndGet(); return setting[0]; });
        assertEquals(50, memo.get(1_000));
        setting[0] = 4; // a merge started: the plain ring shrinks
        for (long t = 1_000; t < 1_000 + RingCapacityMemo.TTL_NS; t += 33_000_000L) assertEquals(50, memo.get(t));
        assertEquals(1, reads.get());
        assertEquals(4, memo.get(1_000 + RingCapacityMemo.TTL_NS));
        assertEquals(2, reads.get());
    }

    @Test
    public void invalidateAndAClockStepBackReadAgain() {
        final int[] setting = {50};
        final RingCapacityMemo memo = new RingCapacityMemo(() -> setting[0]);
        assertEquals(50, memo.get(10_000_000L));
        setting[0] = 38;
        memo.invalidate();
        assertEquals(38, memo.get(10_000_001L));
        setting[0] = 20;
        assertEquals(20, memo.get(5L)); // never served from a reading "in the future"
    }

    @Test
    public void theControllerTrimsThroughTheMemo() throws Exception {
        final String s = new String(Files.readAllBytes(Paths.get("src/main/java/com/scamera/app/capture/CaptureController.java")),
                StandardCharsets.UTF_8);
        final int at = s.indexOf("private int ringCapacityLocked() {");
        assertTrue(at > 0);
        final String body = s.substring(at, s.indexOf("\n    }\n", at));
        assertTrue(body.contains("mRingCapacity.get(System.nanoTime())"));
        assertTrue(s.contains("final int keep = mRingCapacity.get(System.nanoTime()) + 16;"));
        assertTrue("a new session reads it again", s.contains("mRingCapacity.invalidate();"));
    }
}
