package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.opengl.GLES31;
import androidx.annotation.Nullable;
import com.particlesdevs.photoncamera.processing.opengl.GLBuffer;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.util.Log;

final class NoiseQuantileGpu {
    private NoiseQuantileGpu() {}

    @Nullable static Float quarter(GLProg prog, GLTexture noise) {
        final int pixels = noise.mSize.x * noise.mSize.y;
        for (int i = 0; i < 8 && GLES31.glGetError() != GLES31.GL_NO_ERROR; i++) {}
        int[] limit = new int[1];
        GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, limit, 0);
        if (GLES31.glGetError() != GLES31.GL_NO_ERROR || limit[0] < 128) return null;
        final GLFormat format = new GLFormat(GLFormat.DataType.UNSIGNED_32);
        try (GLBuffer hist = new GLBuffer(32768, format); GLBuffer state = new GLBuffer(4, format)) {
            prog.setDefine("PASS", 0);
            prog.setLayout(8, 8, 1);
            prog.useAssetProgram("chromadn/noisequantile", true);
            prog.setTexture("Noise", noise);
            prog.setBufferCompute("noiseHist", hist);
            prog.setBufferCompute("noiseState", state);
            GLES31.glDispatchCompute((noise.mSize.x + 7) / 8, (noise.mSize.y + 7) / 8, 1);
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
            prog.setDefine("PASS", 1);
            prog.setLayout(128, 1, 1);
            prog.useAssetProgram("chromadn/noisequantile", true);
            prog.setBufferCompute("noiseHist", hist);
            prog.setBufferCompute("noiseState", state);
            GLES31.glDispatchCompute(1, 1, 1);
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT | GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
            int[] result = state.readBufferIntegers(false);
            if (GLES31.glGetError() != GLES31.GL_NO_ERROR || result == null || result.length != 4 || result[3] != 1
                    // Sampling and readback can flush half subnormals differently; accept only a complete normal/Inf map.
                    || result[0] != result[1] || result[1] != pixels || result[2] < 0x0400 || result[2] > 0x7c00) return null;
            Log.i("SCAM_PIPELINE", "noise quantile GPU count=" + result[1] + " half=" + result[2]);
            return HalfFloat.TABLE[result[2]];
        } catch (RuntimeException error) {
            Log.w("SCAM_PIPELINE", "noise quantile GPU failed, CPU selection: " + error);
            return null;
        }
    }
}
