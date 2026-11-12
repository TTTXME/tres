package com.particlesdevs.photoncamera.processing.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Random;

import org.junit.Test;

/**
 * P625: the warm-up of the burst preparation's black floor scan (PrepWarm, once a process on the session's prewarm thread) runs
 * the shot's scan on a synthetic frame only: a pure function - a shot's frame scanned before and after it gives the same blocks,
 * extremes and histogram - and it runs once.
 */
public class PrepWarmTest {
    private static ShortBuffer shot(int w, int h, long seed) {
        final ShortBuffer s = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        final Random r = new Random(seed);
        for (int i = 0; i < w * h; i++) s.put(i, (short) (i % w >= w - 96 ? 0 : 58 + r.nextInt(960)));
        return s;
    }

    private static void same(Parameters.BlockScan a, Parameters.BlockScan b) {
        for (int c = 0; c < 4; c++) {
            assertArrayEquals(a.means[c], b.means[c], 0f);
            assertArrayEquals(a.smallest[c], b.smallest[c]);
            assertArrayEquals(a.near[c], b.near[c]);
            for (int i = 0; i < a.low[c].length; i++) {
                assertEquals(a.low[c][i], b.low[c][i]);
                assertEquals(a.zero[c][i], b.zero[c][i]);
                assertEquals(a.hit[c][i], b.hit[c][i]);
            }
        }
        assertArrayEquals(a.values, b.values);
        assertEquals(a.max, b.max);
        assertEquals(a.max2, b.max2);
        assertEquals(a.padding, b.padding);
        assertEquals(a.hits, b.hits);
        assertEquals(a.counted, b.counted);
        assertEquals(a.lowest, b.lowest, 0f);
    }

    @Test public void theWarmUpLeavesAShotsScanAsItWas() {
        final int w = 1024, h = 768, bw = w / 2 / Parameters.BlockScan.BS, bh = h / 2 / Parameters.BlockScan.BS;
        final ShortBuffer frame = shot(w, h, 11);
        final Parameters.BlockScan before = Parameters.BlockScan.of(frame, w, bw, bh, 16f, true, 1, 3f);
        final long[] ms = PrepWarm.run(2);
        assertEquals(2, ms.length);
        final Parameters.BlockScan after = Parameters.BlockScan.of(frame, w, bw, bh, 16f, true, 1, 3f);
        same(before, after);
        assertTrue("the zero columns are padding", after.padding > 0);
    }

    @Test public void itRunsOnceAProcess() {
        final String first = PrepWarm.once(), second = PrepWarm.once();
        // another test of this JVM may have run it first: then both are null
        if (first != null) assertTrue(first, first.startsWith("PREP WARM (P625): black floor scan of 2048x1536 x3 ms "));
        assertNull(second);
    }

    @Test public void theSyntheticFrameHoldsThePathsOfAShot() {
        final ShortBuffer s = PrepWarm.synthetic(64, 32);
        assertEquals(0, s.get(63));
        int clipped = 0;
        for (int i = 0; i < 64 * 32; i++) if (s.get(i) == 1023) clipped++;
        assertTrue(clipped > 0);
        assertEquals(64 * 32, s.remaining());
    }
}
