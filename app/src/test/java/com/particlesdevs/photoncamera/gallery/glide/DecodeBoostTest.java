package com.particlesdevs.photoncamera.gallery.glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.os.Process;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * P637: the gallery's decode threads are background threads (as Glide makes them) except for an open's first seconds and for the
 * viewer's tiles: then the default priority, one step above it while a shot is processed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DecodeBoostTest {
    @org.junit.Before
    public void setUp() {
        DecodeBoost.reset(); // another test's open (the host clock does not move on by itself)
    }

    @org.junit.After
    public void tearDown() {
        DecodeBoost.reset();
    }

    @Test
    public void theLevels() {
        assertEquals("Glide's own: nice 9", 9, DecodeBoost.IDLE);
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, DecodeBoost.front(false));
        assertEquals("ahead of a shot's helpers at the default priority", Process.THREAD_PRIORITY_FOREGROUND, DecodeBoost.front(true));
        assertTrue(DecodeBoost.front(true) < DecodeBoost.front(false));
    }

    @Test
    public void anOpenPutsTheDecodeThreadsInFrontThenBack() throws Exception {
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final int[] tid = new int[1];
        final int[] atStart = new int[1];
        Thread t = DecodeBoost.factory().newThread(() -> {
            tid[0] = Process.myTid();
            atStart[0] = Process.getThreadPriority(tid[0]);
            started.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        t.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        try {
            assertEquals("a decode thread starts as Glide's do", DecodeBoost.IDLE, atStart[0]);
            assertTrue(DecodeBoost.known(tid[0]));
            DecodeBoost.forOpen(true);
            assertEquals(DecodeBoost.FRONT_BUSY, DecodeBoost.level());
            assertEquals(DecodeBoost.FRONT_BUSY, Process.getThreadPriority(tid[0]));
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(DecodeBoost.OPEN_MS + 100));
            assertEquals("background again after the open", DecodeBoost.IDLE, DecodeBoost.level());
            assertEquals(DecodeBoost.IDLE, Process.getThreadPriority(tid[0]));
            DecodeBoost.forOpen(false);
            assertEquals(DecodeBoost.FRONT, Process.getThreadPriority(tid[0]));
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(DecodeBoost.OPEN_MS + 100));
        } finally {
            release.countDown();
            t.join(10_000);
        }
        assertTrue("an ended thread is forgotten", !DecodeBoost.known(tid[0]));
    }

    @Test
    public void aViewerTileIsDecodedAtTheDefaultPriorityAtLeast() throws Exception {
        final int[] got = new int[2];
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); // as AsyncTask does before every task
            DecodeBoost.here();
            got[0] = Process.getThreadPriority(Process.myTid());
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            DecodeBoost.here();
            got[1] = Process.getThreadPriority(Process.myTid());
        });
        t.start();
        t.join(10_000);
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, got[0]);
        assertEquals("a thread already ahead is not lowered", Process.THREAD_PRIORITY_DISPLAY, got[1]);
    }

    /** P645: the grid's first fill puts the decode threads in front again (however late the list came), once per open. */
    @Test
    public void theGridsFirstFillBoostsOncePerOpen() {
        DecodeBoost.forOpen(false);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(DecodeBoost.OPEN_MS + 100));
        assertEquals("the open's boost is over (the list came late)", DecodeBoost.IDLE, DecodeBoost.level());
        DecodeBoost.forGrid();
        assertTrue("the grid's first screen in front", DecodeBoost.level() < DecodeBoost.IDLE);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(DecodeBoost.OPEN_MS + 100));
        assertEquals("bounded in time", DecodeBoost.IDLE, DecodeBoost.level());
        DecodeBoost.forGrid();
        assertEquals("once per open", DecodeBoost.IDLE, DecodeBoost.level());
        DecodeBoost.forOpen(false);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(DecodeBoost.OPEN_MS + 100));
        DecodeBoost.forGrid();
        assertTrue("a new open's grid again", DecodeBoost.level() < DecodeBoost.IDLE);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(DecodeBoost.OPEN_MS + 100));
    }
}
