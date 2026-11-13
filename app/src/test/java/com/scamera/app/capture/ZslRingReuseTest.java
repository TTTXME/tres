package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P662: the ZSL ring kept over a camera restart (reused only when it is the ring the new session would make), the prefaulted part
 * of a fresh ring, and the memfd release off the caller's thread that the next ring waits for.
 */
@RunWith(RobolectricTestRunner.class)
public class ZslRingReuseTest {
    private static final long FRAME_24MB = 4096L * 3072 * 2; // 24 MB, already page aligned
    private static final long FRAME_23MB = 4080L * 3072 * 2 + 1000; // another lens, rounded up to its own slot

    @Test
    public void theSameRingIsReusedOnlyForTheSameSlotKindAndAskedFrames() {
        // the 12 MP ring of 50 asked frames, not trimmed: the same lens and setting reuse it
        assertTrue(ZslRing.reusable(FRAME_24MB, false, 50, 0, false, FRAME_24MB, false, 50, 0));
        // a lens of another frame size: made again (no 24 MB ring for 23 MB frames, no smaller ring for larger ones)
        assertFalse(ZslRing.reusable(FRAME_24MB, false, 50, 0, false, FRAME_23MB, false, 50, 0));
        assertFalse(ZslRing.reusable((FRAME_23MB + 4095) & ~4095L, false, 50, 0, false, FRAME_24MB, false, 50, 0));
        // the ring setting changed
        assertFalse(ZslRing.reusable(FRAME_24MB, false, 50, 0, false, FRAME_24MB, false, 30, 0));
        // trimmed by the memory budget: made again with the memory available now
        assertFalse(ZslRing.reusable(FRAME_24MB, false, 50, 0, true, FRAME_24MB, false, 50, 0));
        // another kind: the 50 MP FULLRES ring never serves a 12 MP session or the other way round
        assertFalse(ZslRing.reusable(FRAME_24MB, true, 50, 12, false, FRAME_24MB, false, 50, 0));
        assertFalse(ZslRing.reusable(FRAME_24MB, false, 50, 0, false, FRAME_24MB, true, 50, 12));
        // a 50 MP ring also needs the same N
        final long frame50 = 8192L * 6144 * 2;
        assertTrue(ZslRing.reusable(frame50, true, 50, 12, false, frame50, true, 50, 12));
        assertFalse(ZslRing.reusable(frame50, true, 50, 12, false, frame50, true, 50, 20));
        // no frame size: never
        assertFalse(ZslRing.reusable(FRAME_24MB, false, 50, 0, false, 0, false, 50, 0));
    }

    @Test
    public void onlyTheRingPartOfA12MpRingIsPrefaulted() {
        assertEquals(50, ZslRing.prefaultSlots(66, 16, false));
        assertEquals(9, ZslRing.prefaultSlots(17, 8, false));
        assertEquals(0, ZslRing.prefaultSlots(19, 8, true)); // FULLRES: its memory is the merge's
        assertEquals(0, ZslRing.prefaultSlots(8, 8, false));
    }

    @Test
    public void aReleaseRunsOffTheCallersThreadAndTheNextRingWaitsForIt() throws Exception {
        final CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicBoolean ranHere = new AtomicBoolean();
        final Thread caller = Thread.currentThread();
        ZslRing.releaseLater(() -> {
            ranHere.set(Thread.currentThread() == caller);
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            done.countDown();
        });
        assertEquals("the release is pending while it runs", 1, ZslRing.pendingReleases());
        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            go.countDown();
        }).start();
        ZslRing.awaitReleases(5000);
        assertTrue("the wait ends once the release is done", done.await(0, TimeUnit.MILLISECONDS));
        assertEquals(0, ZslRing.pendingReleases());
        assertFalse("not on the caller's thread", ranHere.get());
    }

    @Test
    public void theWaitForAReleaseIsBounded() {
        final CountDownLatch hold = new CountDownLatch(1);
        ZslRing.releaseLater(() -> {
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        try {
            final long t0 = System.nanoTime();
            ZslRing.awaitReleases(30);
            assertTrue("gives up after its timeout", (System.nanoTime() - t0) / 1_000_000 < 2000);
            assertEquals(1, ZslRing.pendingReleases());
        } finally {
            hold.countDown();
            ZslRing.awaitReleases(5000);
        }
        assertEquals(0, ZslRing.pendingReleases());
    }
}
