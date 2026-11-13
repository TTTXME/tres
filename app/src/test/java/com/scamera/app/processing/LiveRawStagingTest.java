package com.scamera.app.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P662 (item 6): a ring-slot frame goes to the hand-off thread while the GL thread offers a pixel-unpack buffer, is copied into
 * it there (its bytes), and the frame carries the buffer; the GL thread claims it only for that very frame. A buffer no frame
 * needs any more goes back to the pool; a revoked one (the GL context destroyed) is never written or handed out again; the
 * frame's own buffer (the pinned slot) stays the frame's and every view is freed exactly once, as before.
 */
public class LiveRawStagingTest {
    private static final java.util.IdentityHashMap<ByteBuffer, Integer> FREED = new java.util.IdentityHashMap<>();
    private final java.util.ArrayList<Runnable> queued = new java.util.ArrayList<>();
    private final Object owner = new Object();

    private void start() {
        synchronized (FREED) { FREED.clear(); }
        LiveRawFrame.freeHook = v -> { synchronized (FREED) { FREED.merge(v, 1, Integer::sum); } };
        LiveRawFrame.retireGraceMs = 0;
        LiveRawFrame.pendingNs = 0; // every new frame is taken (a waiting one replaced)
        LiveRawFrame.setMaxLongSide(0);
        LiveRawFrame.resetStaging();
        LiveRawFrame.handoffHook = queued::add;
        LiveRawFrame.setEnabled(true);
    }

    private static void stop() {
        LiveRawFrame.setEnabled(false);
        LiveRawFrame.handoffHook = null;
        LiveRawFrame.resetStaging();
        LiveRawFrame.pendingNs = LiveRawFrame.PENDING_NS;
        LiveRawFrame.retireGraceMs = 2000;
        LiveRawFrame.freeHook = null;
    }

    private static int frees(ByteBuffer v) {
        synchronized (FREED) { return FREED.getOrDefault(v, 0); }
    }

    /** A w x h plain 16-bit slot, sample i = base + i. */
    private static ByteBuffer slot(int w, int h, int base) {
        ByteBuffer v = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < w * h; i++) v.putShort(i * 2, (short) (base + i));
        v.clear();
        return v;
    }

    private static LiveRawFrame.Meta meta(int w, int h) {
        LiveRawFrame.Meta m = new LiveRawFrame.Meta();
        m.width = w; m.height = h; m.rowStride = w * 2; m.format = android.graphics.ImageFormat.RAW_SENSOR;
        m.black = new float[]{0, 0, 0, 0}; m.gains = new float[]{1, 1, 1}; m.ccm = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        return m;
    }

    private LiveRawFrame.Staging offered(int id, int bytes, ByteBuffer[] mapping) {
        LiveRawFrame.Staging s = new LiveRawFrame.Staging(owner, id, bytes);
        mapping[id] = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        LiveRawFrame.offerStaging(s, mapping[id]);
        return s;
    }

    @Test public void aRingSlotFrameIsCopiedIntoAnOfferedBufferOnTheHandOffThread() {
        start();
        try {
            final int w = 8, h = 4, bytes = w * h * 2;
            final ByteBuffer[] mapping = new ByteBuffer[2];
            // nothing offered: on the caller, as before (no buffer)
            ByteBuffer a = slot(w, h, 10);
            LiveRawFrame.publishRef(a, meta(w, h), null);
            assertEquals(0, queued.size());
            LiveRawFrame.Frame fa = LiveRawFrame.acquire();
            assertNull(fa.staging);
            // a buffer offered: the next slot goes to the hand-off thread and is copied into it
            LiveRawFrame.Staging s0 = offered(0, bytes, mapping);
            assertTrue(LiveRawFrame.stagingOffered(bytes));
            assertFalse("too small for a larger frame", LiveRawFrame.stagingOffered(bytes + 2));
            ByteBuffer b = slot(w, h, 200);
            final int[] asked = {0};
            LiveRawFrame.publishRef(b, meta(w, h), () -> asked[0]++);
            assertEquals("handed over", 1, queued.size());
            queued.remove(0).run();
            assertEquals(1, asked[0]);
            assertFalse("taken by the frame", LiveRawFrame.stagingOffered(bytes));
            LiveRawFrame.Frame fb = LiveRawFrame.acquire();
            assertSame(s0, fb.staging);
            assertTrue(fb.ringSlot);
            assertTrue("a ring slot without a buffer is one too", fa.ringSlot);
            assertEquals("the frame is still the pinned slot (the meters read it)", 200, fb.buffer.getShort(0));
            assertEquals(1, LiveRawFrame.pinnedViews());
            for (int i = 0; i < w * h; i++) assertEquals("the buffer holds the frame's bytes", b.getShort(i * 2), mapping[0].getShort(i * 2));
            assertFalse("another frame's version is not this buffer's", LiveRawFrame.claimStaging(s0, fb.version - 1));
            assertTrue(LiveRawFrame.claimStaging(s0, fb.version));
            assertFalse("claimed once", LiveRawFrame.claimStaging(s0, fb.version));
            assertTrue("the GL thread's now", LiveRawFrame.withdrawStaging(s0));
            assertEquals(0, frees(b));
            assertEquals(1, frees(a));
        } finally { stop(); }
    }

    @Test public void aBufferNoFrameNeedsGoesBackToThePool() {
        start();
        try {
            final int w = 8, h = 2, bytes = w * h * 2;
            final ByteBuffer[] mapping = new ByteBuffer[2];
            LiveRawFrame.Staging s0 = offered(0, bytes, mapping), s1 = offered(1, bytes, mapping);
            ByteBuffer a = slot(w, h, 1), b = slot(w, h, 2), c = slot(w, h, 3);
            LiveRawFrame.publishRef(a, meta(w, h), null);
            queued.remove(0).run();
            assertTrue("a holds s0, s1 is still offered", LiveRawFrame.stagingOffered(bytes));
            LiveRawFrame.publishRef(b, meta(w, h), null);
            queued.remove(0).run();
            // b replaced a before the GL thread took it: a's buffer back in the pool
            assertTrue(LiveRawFrame.stagingOffered(bytes));
            LiveRawFrame.Frame fb = LiveRawFrame.acquire();
            assertEquals(2, fb.buffer.getShort(0));
            assertSame(s1, fb.staging);
            LiveRawFrame.publishRef(c, meta(w, h), null);
            assertEquals(1, queued.size());
            queued.remove(0).run();
            LiveRawFrame.Frame fc = LiveRawFrame.acquire();
            assertEquals(3, fc.buffer.getShort(0));
            assertSame(s0, fc.staging);
            // b's buffer: the GL thread moved on from b without uploading from it - back in the pool, no frame's
            assertTrue(LiveRawFrame.stagingOffered(bytes));
            assertFalse(LiveRawFrame.claimStaging(s1, fb.version));
            assertTrue(LiveRawFrame.claimStaging(s0, fc.version));
            for (int i = 0; i < w * h; i++) assertEquals(3 + i, mapping[0].getShort(i * 2));
            // the viewfinder off: a buffer a frame held goes back to the pool too
            LiveRawFrame.setEnabled(false);
            assertTrue(LiveRawFrame.stagingOffered(bytes));
            assertEquals(1, frees(a));
            assertEquals(1, frees(b));
            assertEquals(1, frees(c));
        } finally { stop(); }
    }

    @Test public void aRevokedBufferIsNeverHandedOutOrWritten() {
        start();
        try {
            final int w = 8, h = 2, bytes = w * h * 2;
            final ByteBuffer[] mapping = new ByteBuffer[2];
            LiveRawFrame.Staging s0 = offered(0, bytes, mapping);
            ByteBuffer a = slot(w, h, 40);
            LiveRawFrame.publishRef(a, meta(w, h), null);
            queued.remove(0).run();
            LiveRawFrame.Frame fa = LiveRawFrame.acquire();
            assertSame(s0, fa.staging);
            LiveRawFrame.revokeStaging(owner, 100);
            assertFalse("revoked: not the GL thread's to upload from (the frame's own buffer then)", LiveRawFrame.claimStaging(s0, fa.version));
            LiveRawFrame.Staging s1 = offered(1, bytes, mapping);
            LiveRawFrame.revokeStaging(owner, 100);
            assertFalse("out of the pool", LiveRawFrame.stagingOffered(bytes));
            mapping[1].putShort(0, (short) -1);
            ByteBuffer b = slot(w, h, 50);
            LiveRawFrame.publishRef(b, meta(w, h), null);
            assertEquals("nothing offered: on the caller", 0, queued.size());
            LiveRawFrame.Frame fb = LiveRawFrame.acquire();
            assertNull(fb.staging);
            assertEquals("never written", -1, mapping[1].getShort(0));
            LiveRawFrame.offerStaging(s1, mapping[1]);
            assertFalse("a revoked buffer is not offered again", LiveRawFrame.stagingOffered(bytes));
            assertEquals(50, fb.buffer.getShort(0));
            assertEquals(1, frees(a));
        } finally { stop(); }
    }

    @Test public void onlyPlainSixteenBitFramesOfWholeRowsAreStaged() {
        assertTrue(LiveRawFrame.stagesFrame(android.graphics.ImageFormat.RAW_SENSOR, 4096, 8192, 3072));
        assertFalse("padded rows", LiveRawFrame.stagesFrame(android.graphics.ImageFormat.RAW_SENSOR, 4080, 8192, 3072));
        assertFalse("packed", LiveRawFrame.stagesFrame(android.graphics.ImageFormat.RAW10, 4096, 5120, 3072));
        assertFalse(LiveRawFrame.stagesFrame(android.graphics.ImageFormat.RAW_SENSOR, 0, 0, 3072));
    }
}
