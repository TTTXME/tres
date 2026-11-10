package com.particlesdevs.photoncamera.processing.opengl;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES30;

import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * P602 (owner, 2026-11-10, Pixel 7 log of 31249): a texture read back only for a log line, without a wait for the GPU in the
 * middle of the pipeline. {@link #queue} copies the texture into a pixel pack buffer (glReadPixels into the buffer: queued, the
 * CPU goes on); {@link #flush} - at the end of a post run (GLBasePipeline.finishRun, the context current, the GPU done with it) -
 * maps each buffer and hands its bytes to the reader, in the order they were queued. Nothing it does changes a texture, a binding
 * or a pass of the pipeline: what the reader gets is what a read-back at the queue's point gave.
 */
public final class GLDeferredRead {
    private GLDeferredRead() {}

    /** What is done with the bytes (RGBA, the type queued, rows from the bottom as glReadPixels gives them). */
    public interface Reader { void read(ByteBuffer rgba); }

    private static final class Entry {
        final int buffer, bytes;
        final Reader reader;
        final EGLContext context;
        Entry(int buffer, int bytes, Reader reader, EGLContext context) {
            this.buffer = buffer; this.bytes = bytes; this.reader = reader; this.context = context;
        }
    }

    private static final ThreadLocal<ArrayDeque<Entry>> QUEUED = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Queues the read-back of {@code texture} (w x h, its whole level 0) as RGBA of {@code type} (GL_FLOAT for a float target);
     * false when it could not be queued (any GL error: nothing is queued, the bindings are as before).
     */
    public static boolean queue(GLTexture texture, int type, int bytesPerPixel, Reader reader) {
        if (texture == null || texture.mTextureID == 0) return false;
        final int w = texture.mSize.x, h = texture.mSize.y;
        final long bytes = (long) w * h * bytesPerPixel;
        if (w <= 0 || h <= 0 || bytes > (64L << 20)) return false;
        final int[] oldRead = new int[1], oldPack = new int[1], fb = new int[1], pbo = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        GLES30.glGetIntegerv(GLES30.GL_PIXEL_PACK_BUFFER_BINDING, oldPack, 0);
        int error;
        try {
            for (int i = 0; i < 16 && GLES30.glGetError() != GLES30.GL_NO_ERROR; i++) { } // not this read's
            GLES30.glGenFramebuffers(1, fb, 0);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fb[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture.mTextureID, 0);
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return false;
            GLES30.glGenBuffers(1, pbo, 0);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[0]);
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, (int) bytes, null, GLES30.GL_STREAM_READ);
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, type, 0);
            error = GLES30.glGetError();
        } catch (RuntimeException e) {
            error = -1;
        } finally {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, oldPack[0]);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (fb[0] != 0) GLES30.glDeleteFramebuffers(1, fb, 0);
        }
        if (error != GLES30.GL_NO_ERROR) {
            if (pbo[0] != 0) GLES30.glDeleteBuffers(1, pbo, 0);
            GLES30.glGetError();
            return false;
        }
        QUEUED.get().addLast(new Entry(pbo[0], (int) bytes, reader, EGL14.eglGetCurrentContext()));
        return true;
    }

    /** Every read queued on this thread handed to its reader now, in order (one of another context is dropped); never throws. */
    public static void flush() {
        final ArrayDeque<Entry> q = QUEUED.get();
        if (q.isEmpty()) return;
        final EGLContext now = EGL14.eglGetCurrentContext();
        final int[] oldPack = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_PIXEL_PACK_BUFFER_BINDING, oldPack, 0);
        try {
            while (!q.isEmpty()) {
                final Entry e = q.pollFirst();
                if (!e.context.equals(now)) continue; // its buffer went with its context
                try {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, e.buffer);
                    final java.nio.Buffer mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, e.bytes, GLES30.GL_MAP_READ_BIT);
                    if (mapped instanceof ByteBuffer) {
                        try {
                            e.reader.read(((ByteBuffer) mapped).order(ByteOrder.nativeOrder()));
                        } catch (RuntimeException failure) {
                            Log.w("SCAM_PIPELINE", "deferred read: " + failure);
                        } finally {
                            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                        }
                    }
                } catch (RuntimeException failure) {
                    Log.w("SCAM_PIPELINE", "deferred read: " + failure);
                } finally {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
                    GLES30.glDeleteBuffers(1, new int[]{e.buffer}, 0);
                    GLES30.glGetError();
                }
            }
        } finally {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, oldPack[0]);
        }
    }
}
