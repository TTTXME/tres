package com.scamera.app.ui.camera.views.viewfinder;

import android.opengl.GLES20;
import android.opengl.GLES30;

import com.scamera.app.control.subject.SubjectFrames;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;

/**
 * Hands the tracking-AF worker a small copy of the frame that was just drawn (P42).
 * <p>
 * WhyCam adds a 640x480 YUV analysis stream to its session for this; here no stream is added (the session and the
 * sensor mode stay as they are). After the renderer drew a frame (ISP or developed RAW), two
 * {@code glBlitFramebuffer} passes halve the default framebuffer twice — at exactly 2:1 a linear blit is a 2x2 box
 * average, so this is a small mip pyramid without aliasing — and the quarter-size level is read into a pixel-pack
 * buffer. Later draws poll its fence without waiting and map it only once the read has finished. Only when the sink
 * wants a frame (tracking active, or software face detection; throttled by the sink) — otherwise one volatile read
 * per frame.
 * <p>
 * The frame is exactly what the user sees: rotation, front mirror, zoom and the RAW viewfinder's own crop need no
 * mapping. GL thread only.
 */
final class SubjectFrameGrabber {
    private static final String TAG = "SubjectFrameGrabber";
    private static final int LEVELS = 2;

    private final int[] fbo = new int[LEVELS];
    private final int[] tex = new int[LEVELS];
    private final int[] levelW = new int[LEVELS];
    private final int[] levelH = new int[LEVELS];
    private final int[] pbo = new int[1];
    private int surfaceW, surfaceH;
    private boolean ready, failed, pending;
    private long pendingFence;
    private int pendingW, pendingH;
    /** The sink that asked for the pending frame (the tracker or the P60 field-of-view check). */
    private SubjectFrames.Sink pendingSink;

    /** New GL context: every name is gone with the old one. */
    void onContextCreated() {
        for (int i = 0; i < LEVELS; i++) fbo[i] = tex[i] = 0;
        pbo[0] = 0;
        ready = failed = pending = false;
        pendingFence = 0;
        pendingSink = null;
    }

    void onSurfaceChanged(int width, int height) {
        if (width == surfaceW && height == surfaceH) return;
        surfaceW = width;
        surfaceH = height;
        release();
    }

    /** Called after a frame was drawn into the default framebuffer, before the swap. */
    void afterDraw() {
        if (failed) return;
        final long now = System.nanoTime();
        SubjectFrames.Sink sink = SubjectFrames.sink();
        SubjectFrames.Sink second = SubjectFrames.secondarySink();
        if (pending && pendingSink != sink && pendingSink != second) release();
        boolean want = sink != null && surfaceW >= 16 && surfaceH >= 16 && sink.wantsFrame(now);
        if (!want) { // P60: the field-of-view self-check takes the frames the tracker does not want
            if (second != null && surfaceW >= 16 && surfaceH >= 16 && second.wantsFrame(now)) {
                sink = second;
                want = true;
            }
        }
        if (!pending && !want) return;
        // Errors left by earlier passes were already reported by them; start from a clean queue so the
        // checks below only see this grabber's own calls.
        for (int i = 0; i < 8 && GLES20.glGetError() != GLES20.GL_NO_ERROR; i++) { /* drain */ }
        if (pending) deliver(pendingSink);
        if (!want || failed || pending) return;
        if (!ready && !allocate()) return;
        try {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo[0]);
            GLES30.glBlitFramebuffer(0, 0, surfaceW, surfaceH, 0, 0, levelW[0], levelH[0],
                    GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR);
            for (int i = 1; i < LEVELS; i++) {
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[i - 1]);
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo[i]);
                GLES30.glBlitFramebuffer(0, 0, levelW[i - 1], levelH[i - 1], 0, 0, levelW[i], levelH[i],
                        GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR);
            }
            int last = LEVELS - 1;
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[last]);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[0]);
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4);
            GLES30.glReadPixels(0, 0, levelW[last], levelH[last], GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0);
            pendingFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (pendingFence == 0) {
                disable("readback fence creation failed");
                return;
            }
            GLES30.glFlush();
            pendingW = levelW[last];
            pendingH = levelH[last];
            pendingSink = sink;
            pending = true;
        } catch (RuntimeException e) {
            disable("readback failed: " + e);
        } finally {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
        check("readback");
    }

    private void deliver(SubjectFrames.Sink sink) {
        final int status;
        try {
            status = GLES30.glClientWaitSync(pendingFence, 0, 0);
        } catch (RuntimeException e) {
            disable("readback fence poll failed: " + e);
            return;
        }
        if (!check("fence poll") || status == GLES30.GL_TIMEOUT_EXPIRED) return;
        if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) {
            disable("readback fence poll failed: " + status);
            return;
        }
        GLES30.glDeleteSync(pendingFence);
        pendingFence = 0;
        pending = false;
        pendingSink = null;
        int bytes = pendingW * pendingH * 4;
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[0]);
        byte[] dst = null;
        boolean mapped = false;
        String failure = null;
        try {
            ByteBuffer pixels = (ByteBuffer) GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes,
                    GLES30.GL_MAP_READ_BIT);
            if (pixels != null) {
                mapped = true;
                dst = sink != null ? sink.obtainBuffer(bytes) : null;
                if (dst != null) {
                    pixels.position(0);
                    pixels.get(dst, 0, bytes);
                }
            }
        } catch (RuntimeException e) {
            failure = "map failed: " + e;
        } finally {
            if (mapped) {
                try {
                    if (!GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) failure = "mapped storage invalidated";
                } catch (RuntimeException e) {
                    failure = "unmap failed: " + e;
                }
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        }
        if (failure != null) disable(failure);
        if (!check("map") || failed) {
            if (dst != null) sink.releaseBuffer(dst);
            return;
        }
        if (dst != null) sink.onFrame(dst, pendingW, pendingH);
    }

    private boolean allocate() {
        release();
        int w = surfaceW, h = surfaceH;
        GLES20.glGenTextures(LEVELS, tex, 0);
        GLES20.glGenFramebuffers(LEVELS, fbo, 0);
        int[] prevTex = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, prevTex, 0);
        for (int i = 0; i < LEVELS; i++) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            levelW[i] = w;
            levelH[i] = h;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex[0]);
                disable("framebuffer incomplete: " + status);
                return false;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex[0]);
        GLES20.glGenBuffers(1, pbo, 0);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[0]);
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, levelW[LEVELS - 1] * levelH[LEVELS - 1] * 4, null,
                GLES30.GL_STREAM_READ);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        ready = check("allocate");
        if (ready) Log.d(TAG, "tracking frames " + levelW[LEVELS - 1] + "x" + levelH[LEVELS - 1]
                + " from " + surfaceW + "x" + surfaceH);
        return ready;
    }

    private void release() {
        if (pendingFence != 0) GLES30.glDeleteSync(pendingFence);
        pendingFence = 0;
        pendingSink = null;
        if (tex[0] != 0) GLES20.glDeleteTextures(LEVELS, tex, 0);
        if (fbo[0] != 0) GLES20.glDeleteFramebuffers(LEVELS, fbo, 0);
        if (pbo[0] != 0) GLES20.glDeleteBuffers(1, pbo, 0);
        for (int i = 0; i < LEVELS; i++) fbo[i] = tex[i] = 0;
        pbo[0] = 0;
        ready = pending = false;
    }

    private boolean check(String step) {
        int error = GLES20.glGetError();
        if (error == GLES20.GL_NO_ERROR) return true;
        // Never disturb the viewfinder: on any GL error the grabber switches itself off for this context.
        disable(step + " GL error " + error);
        return false;
    }

    private void disable(String reason) {
        failed = true;
        release();
        Log.e(TAG, reason + "; tracking frames disabled");
    }
}
