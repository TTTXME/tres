package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/** Three-pass Resolve filtering on the merged linear camera RGB. */
public final class ScamHdrDenoise extends Node {
    public ScamHdrDenoise() { super("", "ScamHdrDenoise"); }
    @Override public void Compile() {}

    /**
     * Developer switch (adb: touch /sdcard/Download/SCAMERA/dump-denoise): the full-resolution denoise input and its
     * parameters, written there too (P191: Download/SCAMERA, not Android/data; ScameraFiles).
     */
    private void dumpInput(PostPipeline pipeline) { dumpTexture(pipeline, previousNode.WorkingTexture, "denoise-in"); }

    private void dumpTexture(PostPipeline pipeline, GLTexture source, String name) {
        android.content.Context context = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
        if (!com.particlesdevs.photoncamera.util.ScameraFiles.exists(context, "dump-denoise")) return;
        java.io.File dir = com.particlesdevs.photoncamera.util.ScameraFiles.dir();
        int[] oldRead = new int[1], framebuffer = new int[1];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        try (java.nio.channels.FileChannel out = new java.io.FileOutputStream(new java.io.File(dir, name + ".f32")).getChannel()) {
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, source.mTextureID, 0);
            int w = source.mSize.x, h = source.mSize.y;
            java.nio.ByteBuffer row = java.nio.ByteBuffer.allocateDirect(w * 16).order(java.nio.ByteOrder.nativeOrder());
            java.nio.ByteBuffer packed = java.nio.ByteBuffer.allocateDirect(w * 12).order(java.nio.ByteOrder.nativeOrder());
            for (int y = 0; y < h; y++) {
                row.clear();
                android.opengl.GLES30.glReadPixels(0, y, w, 1, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_FLOAT, row);
                packed.clear();
                for (int x = 0; x < w; x++) {
                    packed.putFloat(row.getFloat(x * 16)).putFloat(row.getFloat(x * 16 + 4)).putFloat(row.getFloat(x * 16 + 8));
                }
                packed.flip();
                while (packed.hasRemaining()) out.write(packed);
            }
            String json = "{\"w\":" + w + ",\"h\":" + h + ",\"iso\":" + pipeline.mParameters.iso
                    + ",\"whitePoint\":" + java.util.Arrays.toString(pipeline.mParameters.whitePoint)
                    + ",\"sensorToProPhoto\":" + java.util.Arrays.toString(pipeline.mParameters.sensorToProPhoto)
                    + ",\"intermediateToSRGB\":" + java.util.Arrays.toString(pipeline.mParameters.CCT.matrix) + "}";
            java.nio.file.Files.write(new java.io.File(dir, name + ".json").toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i("SCAM_PIPELINE", name + " dumped " + w + "x" + h);
        } catch (Exception e) {
            Log.e("SCAM_PIPELINE", "denoise dump failed: " + e);
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    /**
     * Noise level (sigma of u) from the flat, quiet blocks of the frame (25th percentile of the block statistics).
     * s = output pixels per sensor pixel: on the Sabre 2x grid adjacent output pixels are kernel averages of the
     * same donor samples, so the neighbour difference is taken s pixels apart and a block covers 8 x 8 sensor
     * pixels; this keeps the reading (and every constant tuned against it) the same as on the 1x grid.
     */
    static float estimateNoise(com.particlesdevs.photoncamera.processing.opengl.GLProg glProg, GLTexture noisy, int s) {
        final int block = 8 * s;
        Point blocks = new Point((noisy.mSize.x + block - 1) / block, (noisy.mSize.y + block - 1) / block);
        GLTexture est = new GLTexture(blocks, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        int[] oldRead = new int[1], framebuffer = new int[1];
        try {
            glProg.useAssetProgram("chromadn/noiseest", false);
            glProg.setTexture("InputBuffer", noisy);
            glProg.setVar("blockU", block);
            glProg.setVar("pxStepU", s);
            glProg.drawBlocks(est);
            android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
            if (!com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()
                    && PreferenceKeys.scamDevSwitch("post_noise_quantile_gpu", true)) {
                final Float quarter = NoiseQuantileGpu.quarter(glProg, est);
                if (quarter != null) return quarter / 0.8463f / (float) Math.sqrt(1.125);
            }
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, est.mTextureID, 0);
            float[] values = new float[blocks.x * blocks.y];
            int n = 0;
            // P364: the blocks read back as the IEEE halves est holds (8 B a block, widened by HalfFloat.TABLE) where the driver
            // reads RGBA16F natively as halves and its GL_FLOAT conversion is that exact widening (WhyStats.halfReadbackExact,
            // probed once per process): the floats of the GL_FLOAT read-back without the driver converting 4 channels of every
            // block (and half the bytes); else, and in the old run of post_ab, the GL_FLOAT read-back as before
            final int halfType = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy() ? 0 : WhyStats.halfReadType();
            boolean half = halfType != 0 && WhyStats.halfReadbackExact(halfType);
            if (half) {
                java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(blocks.x * blocks.y * 8).order(java.nio.ByteOrder.nativeOrder());
                android.opengl.GLES30.glReadPixels(0, 0, blocks.x, blocks.y, android.opengl.GLES30.GL_RGBA, halfType, data);
                half = android.opengl.GLES30.glGetError() == android.opengl.GLES30.GL_NO_ERROR;
                if (half) {
                    final short[] halves = new short[blocks.x * blocks.y * 4];
                    data.asShortBuffer().get(halves);
                    final float[] table = HalfFloat.TABLE;
                    for (int i = 0; i < values.length; i++) {
                        float v = table[halves[4 * i] & 0xffff];
                        if (v > 0f && !Float.isNaN(v)) values[n++] = v;
                    }
                }
            }
            if (!half) {
                java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(blocks.x * blocks.y * 16).order(java.nio.ByteOrder.nativeOrder());
                android.opengl.GLES30.glReadPixels(0, 0, blocks.x, blocks.y, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_FLOAT, data);
                for (int i = 0; i < values.length; i++) {
                    float v = data.getFloat(i * 16);
                    if (v > 0f && !Float.isNaN(v)) values[n++] = v;
                }
            }
            if (n == 0) return 0f;
            // P364: the quarter's element by selection (the sort of ~200 k blocks took ~20 ms): the same value
            return NoiseMath.selectFloat(values, n, n / 4) / 0.8463f / (float) Math.sqrt(1.125);
        } catch (RuntimeException e) {
            return 0f;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
            est.close();
        }
    }

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        glProg.closed = true;
        if (!PreferenceKeys.isHybridDenoiseEnabled()) return;
        dumpInput(pipeline);
        WorkingTexture = ArkResolveDenoise.process(glProg, pipeline, WorkingTexture);
        pipeline.signedRgb = false;
        dumpTexture(pipeline, WorkingTexture, "denoise-out");
        glProg.closed = true;
    }

    static final long HUGE_PIXELS = 100_000_000L;
    static boolean huge(com.particlesdevs.photoncamera.processing.render.Parameters p) {
        return p != null && p.rawSize != null && huge(p.rawSize.x, p.rawSize.y);
    }
    static boolean huge(int width, int height) {
        return (long) width * height > HUGE_PIXELS;
    }
}
