package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The preview stall watchdog (black viewfinder on the vivo 5x tele until the module was switched). */
public class PreviewStallTest {
    @Test
    public void aSessionWithoutFramesIsAStallAfterTheLimit() {
        assertFalse(PreviewStall.stalled(10_000 + 2_499, 0, 10_000, 33));
        assertTrue(PreviewStall.stalled(10_000 + 2_500, 0, 10_000, 33));
    }

    @Test
    public void framesThatStopMidSessionAreAStall() {
        assertFalse(PreviewStall.stalled(20_000, 18_000, 10_000, 33));
        assertTrue(PreviewStall.stalled(21_000, 18_000, 10_000, 33));
    }

    @Test
    public void longManualExposuresAreNotAStall() {
        // a 4 s manual exposure: results every 4 s are normal
        assertEquals(13_000, PreviewStall.limitMs(4_000));
        assertFalse(PreviewStall.stalled(30_000, 22_000, 10_000, 4_000));
        assertTrue(PreviewStall.stalled(35_000, 22_000, 10_000, 4_000));
    }

    @Test
    public void noSessionNoStallAndRestartsAreBounded() {
        assertFalse(PreviewStall.stalled(100_000, 0, 0, 33));
        assertTrue(PreviewStall.mayRestart(0));
        assertTrue(PreviewStall.mayRestart(PreviewStall.MAX_RESTARTS - 1));
        assertFalse(PreviewStall.mayRestart(PreviewStall.MAX_RESTARTS));
    }

    /**
     * P700: the Xiaomi 17 Ultra's «DCG+LOFIC» rung «lofic-pair» (log 2026-11-14 11:18:25): every request of the session failed,
     * no result came; the owner swiped the black viewfinder away after 1.5 s, before the 2.5 s stall limit.
     */
    @Test
    public void aSessionThatFailsEveryRequestIsRefusedBeforeTheStallLimit() {
        assertFalse(PreviewStall.refused(PreviewStall.REFUSED_REQUESTS - 1, 0));
        assertTrue(PreviewStall.refused(PreviewStall.REFUSED_REQUESTS, 0));
        assertTrue(PreviewStall.refused(35, 0));
        // a session that gave a result once is no refused one (a flush or a dropped frame): the stall watchdog judges it
        assertFalse(PreviewStall.refused(35, 12_345));
        // within the half second the log's 20 fps failures took, far below the stall limit
        assertTrue(PreviewStall.REFUSED_REQUESTS * 50 < PreviewStall.MIN_LIMIT_MS / 4);
    }

    /**
     * P710 (X300 Ultra tele, owner's log 2026-11-14 09:31: «cam[CameraBackground] 7206.5/7276.3» - the camera thread 7.2 s inside one
     * RAW viewfinder frame, then «PREVIEW_STALL ... no preview frame for 7431 ms» and a restart): a check that ran 1.5 s or more
     * late shows the camera thread held; its queued results get the full limit from the moment it is free.
     */
    @Test
    public void aHeldCameraThreadIsNoStall() {
        assertFalse(PreviewStall.threadHeld(10_000, 0));
        assertFalse(PreviewStall.threadHeld(10_000 + PreviewStall.THREAD_HELD_MS - 1, 10_000));
        assertTrue(PreviewStall.threadHeld(17_200, 10_000));
        // the frames stopped 7.4 s ago, but the thread was free only now: no stall yet
        assertTrue(PreviewStall.stalled(27_431, 20_000, 10_000, 33));
        assertFalse(PreviewStall.stalled(27_431, 20_000, 10_000, 33, 27_431));
        assertFalse(PreviewStall.stalled(27_431 + 2_499, 20_000, 10_000, 33, 27_431));
        assertTrue(PreviewStall.stalled(27_431 + 2_500, 20_000, 10_000, 33, 27_431));
        // never held (0): as before
        assertEquals(PreviewStall.stalled(21_000, 18_000, 10_000, 33), PreviewStall.stalled(21_000, 18_000, 10_000, 33, 0));
        assertFalse(PreviewStall.stalled(100_000, 0, 0, 33, 50_000));
    }

    /**
     * P710: the same log's 200 MP shots - the plain session beside the merge stalled three times; each restart under the merge's
     * memory took longer (a 30 s close, «unknown device» on every reopen) and the app was killed for LOW_MEMORY. The session beside
     * a FULLRES merge is not restarted (the camera reopens after the shot), and beside a 200 MP merge it runs the ISP viewfinder.
     */
    @Test
    public void theSessionBesideAFullResMergeIsNotRestartedAndShowsTheIspViewfinderAt200Mp() {
        assertTrue(PreviewStall.restartHeldForMerge(true));
        assertFalse(PreviewStall.restartHeldForMerge(false));
        assertTrue(PreviewStall.lightSessionForMerge(true, 200, false));
        assertFalse(PreviewStall.lightSessionForMerge(true, 50, false));
        assertFalse(PreviewStall.lightSessionForMerge(false, 200, false));
        // a session without the ISP viewfinder keeps its RAW one (no black viewfinder)
        assertFalse(PreviewStall.lightSessionForMerge(true, 200, true));
    }
}
