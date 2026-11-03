package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.app.Application;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.particlesdevs.photoncamera.control.subject.SubjectFrames;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class,
        shadows = {SubjectFrameGrabberTest.Gl20.class, SubjectFrameGrabberTest.Gl30.class})
public class SubjectFrameGrabberTest {
    private SubjectFrameGrabber grabber;
    private Sink sink;

    @Before public void setup() {
        Gl20.next = 1;
        Gl20.error = 0;
        Gl20.deletedBuffers = 0;
        Gl30.reads = Gl30.maps = Gl30.polls = Gl30.deletedFences = 0;
        Gl30.unmaps = Gl30.mapError = Gl30.unmapError = 0;
        Gl30.unmapOk = true;
        Gl30.activeMap = Gl30.mapThrows = Gl30.unmapThrows = false;
        Gl30.waitStatus = GLES30.GL_TIMEOUT_EXPIRED;
        Gl30.waitError = 0;
        Gl30.fence = 101;
        Gl30.lastDeletedFence = 0;
        Gl30.waitFlags = -1;
        Gl30.waitTimeout = -1;
        Gl30.mapped = ByteBuffer.allocateDirect(64 * 48 * 4);
        for (int i = 0; i < Gl30.mapped.capacity(); i++) Gl30.mapped.put(i, (byte) i);
        sink = new Sink();
        SubjectFrames.setSink(sink);
        SubjectFrames.setSecondarySink(null);
        grabber = new SubjectFrameGrabber();
        grabber.onContextCreated();
        grabber.onSurfaceChanged(256, 192);
    }

    @After public void cleanup() {
        SubjectFrames.clearSink(sink);
        SubjectFrames.setSecondarySink(null);
        grabber.onSurfaceChanged(0, 0);
    }

    @Test public void unfinishedGpuReadIsNeverMappedOrOverwritten() {
        grabber.afterDraw();
        for (int i = 0; i < 12; i++) grabber.afterDraw();
        assertEquals(1, Gl30.reads);
        assertEquals(0, Gl30.maps);
        assertEquals(12, Gl30.polls);
        assertEquals(0, Gl30.waitFlags);
        assertEquals(0, Gl30.waitTimeout);
        assertEquals(0, sink.obtained);
        assertEquals(0, Gl30.deletedFences);
    }

    @Test public void signaledReadDeliversItsPixelsOnceEvenWhenSinkIsThrottled() {
        grabber.afterDraw();
        sink.want = false;
        Gl30.waitStatus = GLES30.GL_CONDITION_SATISFIED;
        grabber.afterDraw();
        grabber.afterDraw();
        assertEquals(1, Gl30.maps);
        assertEquals(1, sink.frames);
        assertEquals(0, sink.released);
        assertEquals(64, sink.width);
        assertEquals(48, sink.height);
        for (int i = 0; i < sink.pixels.length; i++) assertEquals((byte) i, sink.pixels[i]);
        assertEquals(1, Gl30.deletedFences);
        assertEquals(101, Gl30.lastDeletedFence);
        assertEquals(1, Gl30.reads);
    }

    @Test public void completedReadMayBeFollowedByANewRead() {
        grabber.afterDraw();
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        Gl30.fence = 202;
        grabber.afterDraw();
        assertEquals(1, sink.frames);
        assertEquals(2, Gl30.reads);
        assertEquals(1, Gl30.maps);
        assertEquals(101, Gl30.lastDeletedFence);
        grabber.onSurfaceChanged(128, 96);
        assertEquals(2, Gl30.deletedFences);
        assertEquals(202, Gl30.lastDeletedFence);
    }

    @Test public void failedWaitDisablesReadbackWithoutBlockingOrMapping() {
        grabber.afterDraw();
        Gl30.waitStatus = GLES30.GL_WAIT_FAILED;
        grabber.afterDraw();
        grabber.afterDraw();
        assertEquals(1, Gl30.reads);
        assertEquals(1, Gl30.polls);
        assertEquals(0, Gl30.maps);
        assertEquals(0, sink.frames);
        assertEquals(1, Gl30.deletedFences);
        assertEquals(1, Gl20.deletedBuffers);
    }

    @Test public void glErrorOnWaitDisablesEvenIfItReportsReady() {
        grabber.afterDraw();
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        Gl30.waitError = GLES20.GL_INVALID_OPERATION;
        grabber.afterDraw();
        assertEquals(0, Gl30.maps);
        assertEquals(0, sink.frames);
        assertEquals(1, Gl30.deletedFences);
        assertEquals(1, Gl20.deletedBuffers);
    }

    @Test public void removingTheSinkDiscardsPendingReadWithoutMapping() {
        grabber.afterDraw();
        SubjectFrames.clearSink(sink);
        grabber.afterDraw();
        assertEquals(0, Gl30.polls);
        assertEquals(0, Gl30.maps);
        assertEquals(1, Gl30.deletedFences);
        assertEquals(1, Gl20.deletedBuffers);
    }

    @Test public void secondaryReadIsDeliveredToItsOriginalConsumer() {
        Sink second = new Sink();
        sink.want = false;
        SubjectFrames.setSecondarySink(second);
        grabber.afterDraw();
        sink.want = true;
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        grabber.afterDraw();
        assertEquals(0, sink.frames);
        assertEquals(1, second.frames);
        assertEquals(2, Gl30.reads);
    }

    @Test public void resizeDropsTheFenceAndOldDimensions() {
        grabber.afterDraw();
        grabber.onSurfaceChanged(128, 96);
        assertEquals(1, Gl30.deletedFences);
        assertEquals(1, Gl20.deletedBuffers);
        grabber.afterDraw();
        sink.want = false;
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        grabber.afterDraw();
        assertEquals(32, sink.width);
        assertEquals(24, sink.height);
        assertEquals(2, Gl30.reads);
    }

    @Test public void aNewContextNeverDeletesOrWaitsOnOldContextNames() {
        grabber.afterDraw();
        grabber.onContextCreated();
        grabber.afterDraw();
        assertEquals(0, Gl30.deletedFences);
        assertEquals(0, Gl30.polls);
        assertEquals(0, Gl20.deletedBuffers);
        assertEquals(2, Gl30.reads);
    }

    @Test public void missingFenceDisablesReadbackAndReleasesPbo() {
        Gl30.fence = 0;
        grabber.afterDraw();
        grabber.afterDraw();
        assertEquals(1, Gl30.reads);
        assertEquals(0, Gl30.maps);
        assertEquals(0, Gl30.polls);
        assertEquals(1, Gl20.deletedBuffers);
        assertEquals(0, sink.frames);
    }

    @Test public void invalidatedStorageReturnsTheUndeliveredSinkBuffer() {
        grabber.afterDraw();
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        Gl30.unmapOk = false;
        grabber.afterDraw();
        grabber.afterDraw();
        assertEquals(1, sink.obtained);
        assertEquals(1, sink.released);
        assertEquals(0, sink.frames);
        assertSame(sink.lastObtained, sink.lastReleased);
        assertEquals(1, Gl30.unmaps);
        assertEquals(1, Gl20.deletedBuffers);
        assertEquals(1, Gl30.reads);
    }

    @Test public void failedCopyUnmapsBeforeDisablingAndReturnsTheBuffer() {
        grabber.afterDraw();
        Gl30.mapped.limit(10);
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        grabber.afterDraw();
        assertEquals(1, Gl30.unmaps);
        assertEquals(1, sink.released);
        assertSame(sink.lastObtained, sink.lastReleased);
        assertEquals(0, sink.frames);
        assertEquals(1, Gl20.deletedBuffers);
    }

    @Test public void unmapGlErrorReturnsTheBufferInsteadOfDeliveringIt() {
        grabber.afterDraw();
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        Gl30.unmapError = GLES20.GL_INVALID_OPERATION;
        grabber.afterDraw();
        assertEquals(1, sink.obtained);
        assertEquals(1, sink.released);
        assertEquals(0, sink.frames);
        assertEquals(1, Gl30.unmaps);
        assertEquals(1, Gl20.deletedBuffers);
    }

    @Test public void mapGlErrorAfterObtainingTheBufferReturnsIt() {
        grabber.afterDraw();
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        Gl30.mapError = GLES20.GL_INVALID_OPERATION;
        grabber.afterDraw();
        assertEquals(1, sink.obtained);
        assertEquals(1, sink.released);
        assertEquals(0, sink.frames);
        assertEquals(1, Gl30.unmaps);
    }

    @Test public void failedUnmapReturnsTheBufferAndLeavesNoActiveMapping() {
        grabber.afterDraw();
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        Gl30.unmapThrows = true;
        grabber.afterDraw();
        assertEquals(1, sink.obtained);
        assertEquals(1, sink.released);
        assertEquals(0, sink.frames);
        assertFalse(Gl30.activeMap);
    }

    @Test public void failedMapDoesNotBorrowASinkBuffer() {
        grabber.afterDraw();
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        Gl30.mapThrows = true;
        grabber.afterDraw();
        assertEquals(0, sink.obtained);
        assertEquals(0, sink.released);
        assertEquals(0, sink.frames);
        assertEquals(0, Gl30.unmaps);
        assertEquals(1, Gl20.deletedBuffers);
    }

    @Test public void busySinkStillUnmapsAndDoesNotReceiveAFrame() {
        grabber.afterDraw();
        sink.bufferAvailable = false;
        sink.want = false;
        Gl30.waitStatus = GLES30.GL_ALREADY_SIGNALED;
        grabber.afterDraw();
        assertEquals(1, Gl30.unmaps);
        assertEquals(0, sink.frames);
        assertEquals(0, sink.released);
        assertEquals(0, Gl20.deletedBuffers);
    }

    private static final class Sink implements SubjectFrames.Sink {
        boolean want = true, bufferAvailable = true;
        int obtained, released, frames, width, height;
        byte[] pixels, lastObtained, lastReleased;

        @Override public boolean wantsFrame(long nowNs) { return want; }
        @Override public byte[] obtainBuffer(int bytes) {
            obtained++;
            return lastObtained = bufferAvailable ? new byte[bytes] : null;
        }
        @Override public void releaseBuffer(byte[] rgba) { released++; lastReleased = rgba; }
        @Override public void onFrame(byte[] rgba, int w, int h) {
            frames++;
            pixels = rgba;
            width = w;
            height = h;
        }
    }

    @Implements(GLES20.class)
    public static class Gl20 {
        static int next, error, deletedBuffers;
        @Implementation protected static int glGetError() { int e = error; error = 0; return e; }
        @Implementation protected static void glGenTextures(int n, int[] a, int at) { names(n, a, at); }
        @Implementation protected static void glGenFramebuffers(int n, int[] a, int at) { names(n, a, at); }
        @Implementation protected static void glGenBuffers(int n, int[] a, int at) { names(n, a, at); }
        @Implementation protected static void glDeleteBuffers(int n, int[] a, int at) {
            assertFalse("unmap before deleting the PBO", Gl30.activeMap);
            deletedBuffers += n;
        }
        @Implementation protected static int glCheckFramebufferStatus(int target) { return GLES20.GL_FRAMEBUFFER_COMPLETE; }
        @Implementation protected static void glBindTexture(int target, int name) {}
        @Implementation protected static void glTexParameteri(int target, int name, int value) {}
        @Implementation protected static void glBindFramebuffer(int target, int name) {}
        @Implementation protected static void glFramebufferTexture2D(int target, int point, int textureTarget, int name, int level) {}
        @Implementation protected static void glGetIntegerv(int name, int[] values, int at) { values[at] = 0; }
        @Implementation protected static void glDeleteTextures(int n, int[] names, int at) {}
        @Implementation protected static void glDeleteFramebuffers(int n, int[] names, int at) {}
        @Implementation protected static void glBindBuffer(int target, int name) {}
        @Implementation protected static void glBufferData(int target, int size, Buffer data, int usage) {}
        @Implementation protected static void glPixelStorei(int name, int value) {}
        @Implementation protected static void glFlush() {}
        private static void names(int n, int[] a, int at) { for (int i = 0; i < n; i++) a[at + i] = next++; }
    }

    @Implements(GLES30.class)
    public static class Gl30 {
        static int reads, maps, polls, unmaps, deletedFences, waitStatus, waitFlags, waitError, mapError, unmapError;
        static long fence, lastDeletedFence, waitTimeout;
        static ByteBuffer mapped;
        static boolean unmapOk, activeMap, mapThrows, unmapThrows;
        @Implementation protected static void glTexStorage2D(int target, int levels, int format, int w, int h) {}
        @Implementation protected static void glBlitFramebuffer(int sx0, int sy0, int sx1, int sy1,
                int dx0, int dy0, int dx1, int dy1, int mask, int filter) {}
        @Implementation protected static void glReadPixels(int x, int y, int w, int h, int f, int t, int offset) { reads++; }
        @Implementation protected static long glFenceSync(int condition, int flags) {
            assertEquals(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, condition);
            assertEquals(0, flags);
            return fence;
        }
        @Implementation protected static int glClientWaitSync(long sync, int flags, long timeout) {
            assertNotEquals(0, sync);
            polls++;
            waitFlags = flags;
            waitTimeout = timeout;
            Gl20.error = waitError;
            return waitStatus;
        }
        @Implementation protected static void glDeleteSync(long sync) { deletedFences++; lastDeletedFence = sync; }
        @Implementation protected static Buffer glMapBufferRange(int target, int offset, int length, int access) {
            assertTrue(waitStatus == GLES30.GL_ALREADY_SIGNALED || waitStatus == GLES30.GL_CONDITION_SATISFIED);
            assertEquals(GLES30.GL_MAP_READ_BIT, access);
            maps++;
            if (mapThrows) throw new IllegalStateException("map failure");
            Gl20.error = mapError;
            activeMap = true;
            return mapped.duplicate();
        }
        @Implementation protected static boolean glUnmapBuffer(int target) {
            assertTrue(activeMap);
            unmaps++;
            activeMap = false;
            if (unmapThrows) throw new IllegalStateException("unmap failure");
            if (unmapError != 0) Gl20.error = unmapError;
            return unmapOk;
        }
    }
}
