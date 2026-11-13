package com.scamera.app.processing.opengl.postpipeline;

import android.opengl.GLES31;
import com.scamera.app.processing.opengl.GLBuffer;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.util.Log;

final class RimSupportGpu {
    private RimSupportGpu() {}

    static GLBuffer prepare(GLProg prog, GLTexture low, float ae, boolean sharp, float sharpTone) {
        final int width = (low.mSize.x + 7) / 8, height = (low.mSize.y + 7) / 8;
        int[] previous = new int[2], fragmentBlocks = new int[1];
        GLES31.glGetIntegerv(GLES31.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, fragmentBlocks, 0);
        if (fragmentBlocks[0] < 1) return null;
        GLES31.glGetIntegerv(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING, previous, 0);
        GLES31.glGetIntegeri_v(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING, 0, previous, 1);
        GLBuffer tiles = null;
        try {
            tiles = new GLBuffer(width * height, new GLFormat(GLFormat.DataType.UNSIGNED_32));
            int[] bytes = new int[1];
            GLES31.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER, GLES31.GL_BUFFER_SIZE, bytes, 0);
            if (tiles.mBufferID == 0 || bytes[0] != width * height * 4)
                throw new IllegalStateException("rim support buffer allocation");
            prog.setLayout(8, 8, 1);
            prog.useAssetProgram("why/rim_support", true);
            prog.setTexture("WhyLow", low);
            prog.setVar("aeU", ae);
            prog.setVar("sharpU", sharp ? 1 : 0);
            prog.setVar("sharpToneU", sharpTone);
            prog.setBufferCompute("rimTiles", tiles);
            GLES31.glDispatchCompute(width, height, 1);
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
            final int error = GLES31.glGetError();
            if (error != GLES31.GL_NO_ERROR)
                throw new IllegalStateException("rim support GL error 0x" + Integer.toHexString(error));
            return tiles;
        } catch (RuntimeException | OutOfMemoryError error) {
            if (tiles != null) tiles.close();
            Log.w("SCAM_PIPELINE", "rim support failed: " + error + "; reference scan");
            return null;
        } finally {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, previous[1]);
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, previous[0]);
            prog.closed = true;
        }
    }
}
