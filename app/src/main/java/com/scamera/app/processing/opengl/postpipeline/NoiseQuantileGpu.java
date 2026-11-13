package com.scamera.app.processing.opengl.postpipeline;

import android.opengl.GLES31;
import androidx.annotation.Nullable;
import com.scamera.app.processing.opengl.GLBuffer;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class NoiseQuantileGpu {
    private NoiseQuantileGpu() {}
    private static volatile int rawReadbackState;

    @Nullable static Float quarter(GLProg prog, GLTexture noise) {
        // Keep normal maps on the cheap sampler path; exact integer access is only a fallback.
        Float sampled = quarter(prog, noise, false);
        return sampled != null ? sampled : quarter(prog, noise, true);
    }

    @Nullable static Float quarter(GLProg prog, GLTexture noise, boolean allowRaw) {
        final int pixels = noise.mSize.x * noise.mSize.y;
        for (int i = 0; i < 8 && GLES31.glGetError() != GLES31.GL_NO_ERROR; i++) {}
        int[] limit = new int[1];
        GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, limit, 0);
        if (GLES31.glGetError() != GLES31.GL_NO_ERROR || limit[0] < 128) return null;
        final boolean raw = allowRaw;
        if (raw && (noise.mFormat.getGLFormatInternal() != GLES31.GL_RGBA16F || !rawReadbackExact(prog))) return null;
        final GLFormat format = new GLFormat(GLFormat.DataType.UNSIGNED_32);
        try (GLBuffer hist = new GLBuffer(32768, format); GLBuffer state = new GLBuffer(5, format);
             ImageBinding image = raw ? new ImageBinding(noise) : null) {
            prog.setDefine("PASS", 0);
            if (raw) prog.setDefine("RAW_KEYS", 1);
            prog.setLayout(8, 8, 1);
            prog.useAssetProgram("chromadn/noisequantile", true);
            if (!raw) prog.setTexture("Noise", noise);
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
            if (GLES31.glGetError() != GLES31.GL_NO_ERROR || result == null || result.length != 5 || result[3] != 1
                    // Non-normal samples need verified raw-bit/readback agreement; the sampled path requires a complete normal map.
                    || result[0] != result[1] || result[4] != pixels || result[1] < 0 || result[1] > pixels
                    || result[2] < 0 || result[2] > 0x7c00
                    || (raw ? (result[1] == 0 ? result[2] != 0 : result[2] == 0)
                            : result[1] != pixels || result[2] < 0x0400)) return null;
            Log.i("SCAM_PIPELINE", "noise quantile GPU count=" + result[1] + " half=" + result[2] + " raw=" + raw);
            return HalfFloat.TABLE[result[2]];
        } catch (RuntimeException error) {
            Log.w("SCAM_PIPELINE", "noise quantile GPU failed, CPU selection: " + error);
            return null;
        }
    }

    static boolean rawReadbackExact(GLProg prog) {
        // Validate both the integer image view and the driver's GL_FLOAT conversion, including every subnormal.
        if (rawReadbackState != 0) return rawReadbackState > 0;
        int[] read = new int[1], draw = new int[1], viewport = new int[4];
        GLES31.glGetIntegerv(GLES31.GL_READ_FRAMEBUFFER_BINDING, read, 0);
        GLES31.glGetIntegerv(GLES31.GL_DRAW_FRAMEBUFFER_BINDING, draw, 0);
        GLES31.glGetIntegerv(GLES31.GL_VIEWPORT, viewport, 0);
        boolean exact = false;
        try (GLTexture probe = new GLTexture(new android.graphics.Point(128,128), new GLFormat(GLFormat.DataType.FLOAT_16,4),
                null, GLES31.GL_NEAREST, GLES31.GL_CLAMP_TO_EDGE);
             GLBuffer keys = new GLBuffer(65536, new GLFormat(GLFormat.DataType.UNSIGNED_32));
             ImageBinding image = new ImageBinding(probe)) {
            ByteBuffer upload = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
            for (int i = 0; i < 65536; i++) upload.putShort(i * 2, (short) i);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, probe.mTextureID);
            GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D,0,0,0,128,128,GLES31.GL_RGBA,GLES31.GL_HALF_FLOAT,upload);
            probe.BufferLoad();
            if (GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER) != GLES31.GL_FRAMEBUFFER_COMPLETE) return false;
            ByteBuffer floats = ByteBuffer.allocateDirect(65536 * 4).order(ByteOrder.nativeOrder());
            GLES31.glReadPixels(0,0,128,128,GLES31.GL_RGBA,GLES31.GL_FLOAT,floats);
            if (GLES31.glGetError() != GLES31.GL_NO_ERROR) return false;
            prog.setDefine("PASS", 2);
            prog.setLayout(8,8,1);
            prog.useAssetProgram("chromadn/noisequantile", true);
            prog.setBufferCompute("noiseState", keys);
            GLES31.glDispatchCompute(16,16,1);
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT | GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
            int[] bits = keys.readBufferIntegers(false);
            if (bits == null || bits.length != 65536 || GLES31.glGetError() != GLES31.GL_NO_ERROR) return false;
            for (int i = 0; i < 65536; i++) {
                float expected = HalfFloat.TABLE[i], actual = floats.getFloat(i * 4);
                float stored = HalfFloat.TABLE[bits[i] & 65535];
                if (Float.isNaN(expected) ? !Float.isNaN(actual) || !Float.isNaN(stored)
                        : bits[i] != i || Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(expected)) return false;
            }
            exact = true;
        } catch (RuntimeException error) {
            Log.w("SCAM_PIPELINE", "noise raw-bit probe failed: " + error);
        } finally {
            GLES31.glBindFramebuffer(GLES31.GL_DRAW_FRAMEBUFFER, draw[0]);
            GLES31.glBindFramebuffer(GLES31.GL_READ_FRAMEBUFFER, read[0]);
            GLES31.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            rawReadbackState = exact ? 1 : -1;
            Log.i("SCAM_PIPELINE", "noise raw-bit readback exact=" + exact);
        }
        return exact;
    }

    static final class ImageBinding implements AutoCloseable {
        private final int[] saved = new int[6];
        ImageBinding(GLTexture texture) {
            int[] names = {GLES31.GL_IMAGE_BINDING_NAME,GLES31.GL_IMAGE_BINDING_LEVEL,GLES31.GL_IMAGE_BINDING_LAYERED,
                    GLES31.GL_IMAGE_BINDING_LAYER,GLES31.GL_IMAGE_BINDING_ACCESS,GLES31.GL_IMAGE_BINDING_FORMAT};
            for (int i = 0; i < names.length; i++) GLES31.glGetIntegeri_v(names[i],0,saved,i);
            GLES31.glBindImageTexture(0,texture.mTextureID,0,false,0,GLES31.GL_READ_ONLY,GLES31.GL_RGBA16UI);
            if (GLES31.glGetError() != GLES31.GL_NO_ERROR) { close(); throw new IllegalStateException("integer image binding"); }
        }
        @Override public void close() {
            GLES31.glBindImageTexture(0,saved[0],saved[1],saved[2]!=0,saved[3],saved[0]==0?GLES31.GL_READ_ONLY:saved[4],
                    saved[0]==0?GLES31.GL_RGBA16UI:saved[5]);
        }
    }
}
