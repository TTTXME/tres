package com.scamera.app.processing;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P683: the WHY tone's AE meter reads the GL thread's frame on a thread of its own after the draw (LiveWhyTone.startAePoint). The
 * frame's buffer is the GL thread's only until its next acquire (then the producer refills it, a ring view is freed), so acquire
 * waits for the meter before it lets go of the frame - and only then: without a new frame it hands the same buffer at once.
 */
public class LiveRawOffThreadReaderTest {
    private static void publish(int value) {
        LiveRawFrame.publish(ByteBuffer.wrap(new byte[]{(byte) value, 0, 0, 0, 0, 0, 0, 0}), 2, 2, 4, 0, 1023, new float[4],
                new float[]{1, 1, 1}, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});
    }

    @Test(timeout = 20000)
    public void acquireWaitsForTheReaderBeforeItLetsGoOfTheFrame() throws Exception {
        LiveRawFrame.pendingNs = Long.MAX_VALUE;
        LiveRawFrame.setEnabled(true);
        final CountDownLatch reading = new CountDownLatch(1);
        try {
            publish(7);
            final LiveRawFrame.Frame read = LiveRawFrame.acquire();
            assertEquals(7, read.buffer.get(0));
            LiveRawFrame.offThreadReader(reading);
            // no new frame: the same buffer at once, no wait
            final LiveRawFrame.Frame same = LiveRawFrame.acquire();
            assertNotNull(same);
            assertEquals(7, same.buffer.get(0));
            publish(11);
            final AtomicReference<LiveRawFrame.Frame> next = new AtomicReference<>();
            final Thread gl = new Thread(() -> next.set(LiveRawFrame.acquire()), "gl");
            gl.start();
            gl.join(300);
            assertTrue("a new frame: acquire waits while the meter reads the old one", gl.isAlive());
            assertEquals("the frame being read is untouched", 7, read.buffer.get(0));
            reading.countDown();
            gl.join(10000);
            assertFalse(gl.isAlive());
            assertEquals(11, next.get().buffer.get(0));
            // a reader done before the next frame costs nothing
            final CountDownLatch done = new CountDownLatch(1);
            LiveRawFrame.offThreadReader(done);
            done.countDown();
            publish(13);
            assertEquals(13, LiveRawFrame.acquire().buffer.get(0));
        } finally {
            reading.countDown();
            LiveRawFrame.offThreadReader(null);
            LiveRawFrame.setEnabled(false);
            LiveRawFrame.pendingNs = LiveRawFrame.PENDING_NS;
        }
    }

    @Test
    public void theMeterJobsSettingsAreACopyOfEveryField() throws Exception {
        final com.scamera.app.processing.opengl.postpipeline.WhyAe.Settings s = new com.scamera.app.processing.opengl.postpipeline.WhyAe.Settings();
        s.noiseS = 1e-5;
        s.evWhite = 7.25f;
        s.portraitFace = true;
        s.look = 2;
        final com.scamera.app.processing.opengl.postpipeline.WhyAe.Settings c = s.copy();
        s.noiseS = 2e-5;
        s.portraitFace = false;
        assertEquals(1e-5, c.noiseS, 0);
        assertEquals(7.25f, c.evWhite, 0f);
        assertTrue(c.portraitFace);
        assertEquals(2, c.look);
        // every field a primitive: the copy shares nothing with the GL thread's settings
        for (java.lang.reflect.Field f : s.getClass().getDeclaredFields())
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) assertTrue(f.getName(), f.getType().isPrimitive());
    }

    @Test
    public void theToneMetersOffTheGlThreadOnlyAfterItsFirstResult() throws Exception {
        final String src = new String(Files.readAllBytes(Paths.get(new java.io.File("src").isDirectory() ? "src" : "app/src",
                "main/java/com/scamera/app/processing/opengl/postpipeline/LiveWhyTone.java")), StandardCharsets.UTF_8);
        // the first result (a session, a context, a portrait change) on the GL thread; later ones on the meter's thread
        assertTrue(src.contains("if (ae != null && !meterNow && meterAsync()) {"));
        assertTrue(src.contains("if (metering == null) startAePoint(view);"));
        // the job: the frame held for it, its own copy of the settings, the meter's cells copied out
        assertTrue(src.contains("com.scamera.app.processing.LiveRawFrame.offThreadReader(done);"));
        assertTrue(src.contains("final WhyAe.Settings s = settings.copy();"));
        assertTrue(src.contains("done.countDown();"));
        // the GL thread's own meter only after the job is done with it
        final int sync = src.indexOf("private boolean runAePoint(");
        assertTrue(src.indexOf("applyMetering(true);", sync) > sync && src.indexOf("applyMetering(true);", sync) < src.indexOf("meter.measure(", sync));
        // a result of another session is dropped
        assertTrue(src.contains("m.session != trackedSession) return;"));
        // nothing writes into the meter on the GL thread outside the GL thread's own run
        assertFalse(src.contains("            meter.keepQuads = skyWanted;\n            if (!skyWanted)"));
    }
}
