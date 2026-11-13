package com.scamera.app.processing.opengl.postpipeline;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.GLES30;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.util.Log;

final class SceneLumaCache {
    private SceneLumaCache() {}

    static boolean fits(int width, int height, long available) {
        if (width <= 0 || height <= 0) return false;
        final long pixels = (long) width * height;
        return pixels <= (256L << 20) / 4 && available >= pixels * 8 + (256L << 20);
    }

    static GLTexture prepare(GLProg prog, PostPipeline pipeline, GLTexture input, GLTexture gainMap) {
        GLTexture cache = null;
        int[] read = new int[1], draw = new int[1], viewport = new int[4];
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, read, 0);
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, draw, 0);
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);
        try {
            ActivityManager manager = (ActivityManager) ScameraApp.getAppContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return null;
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            if (memory.lowMemory || !fits(input.mSize.x, input.mSize.y, memory.availMem)) return null;
            cache = new GLTexture(input.mSize, new GLFormat(GLFormat.DataType.FLOAT_32, 1), null,
                    GLES30.GL_NEAREST, GLES30.GL_CLAMP_TO_EDGE);
            cache.BufferLoad();
            if (cache.mTextureID == 0 || cache.mBuffer == 0
                    || GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("R32F scene luma is not renderable");
            prog.useAssetProgram("why/scene_y", false);
            prog.setTexture("InputBuffer", input);
            WhyTone.setColour(prog, pipeline, gainMap, pipeline.why.inScale);
            prog.drawBlocks(cache);
            final int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR)
                throw new IllegalStateException("scene luma GL error 0x" + Integer.toHexString(error));
            final GLTexture out = cache;
            cache = null;
            return out;
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.w("SCAM_PIPELINE", "scene luma cache failed: " + error + "; original colour evaluation");
            return null;
        } finally {
            if (cache != null) cache.close();
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, draw[0]);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, read[0]);
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            prog.closed = true;
        }
    }

    static void bind(GLProg prog, GLTexture cache) {
        // GLProg assigns even sampler units; unit1 remains free even on16-unit fragment hardware.
        prog.setVar("WhySceneY", 1);
        cache.bind(GLES30.GL_TEXTURE0 + 1);
    }
}
