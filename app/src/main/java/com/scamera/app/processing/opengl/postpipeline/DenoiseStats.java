package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.processing.render.NoiseModeler;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/** Measured noise, coverage uploads and SNR diagnostics; contains no image denoiser. */
final class DenoiseStats {
    private DenoiseStats() {}
    private static volatile boolean effUploadLogged;

    private static GLTexture tex(List<GLTexture> owned, Point size, GLFormat.DataType type, int channels, int filter) {
        GLTexture t = new GLTexture(size, new GLFormat(type, channels), null, filter, GL_CLAMP_TO_EDGE);
        owned.add(t);
        return t;
    }

    private static void release(List<GLTexture> owned, GLTexture t) {
        if (t != null && owned.remove(t)) t.close();
    }

    private static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    static float displayGain(float p50, float p90, boolean scamSoc) {
        final float mid = 0.050f, high = 0.180f, gainMax = 128f;
        p50 = Math.max(p50, 1.0e-5f); p90 = Math.max(p90, 1.0e-5f);
        float gain50 = mid / p50, gain90 = high / p90;
        float sceneGain = (float) Math.sqrt(Math.max(1f, gain50) * Math.max(1f, gain90));
        // P128: the former SCAM AE limit / bright-mid / high-limit settings had no row and no scam_dev line: their defaults
        boolean limited = scamSoc;
        final float brightMid = 0.25f;
        if (limited) {
            final float limit = 0.40f;
            float highGain = Math.max(1f, limit / p90);
            sceneGain = Math.min(Math.max(1f, gain50), highGain);
            if (brightMid > 0f && p50 >= mid) sceneGain = Math.max(sceneGain, Math.min(highGain, Math.max(1f, Math.min(2.2f, brightMid / p50))));
        } else if (brightMid > 0f) {
            sceneGain = Math.max(sceneGain, Math.max(1f, Math.min(2.2f, brightMid / p50)));
        }
        return Math.max(1f, Math.min(gainMax, sceneGain));
    }

    static final class Laps {
        final boolean on = com.scamera.app.processing.opengl.PostGlMode.syncNodes();
        final StringBuilder text = new StringBuilder();
        private long at = System.nanoTime();

        void lap(String stage) {
            if (!on) return;
            android.opengl.GLES30.glFinish();
            final long now = System.nanoTime();
            text.append(' ').append(stage).append('=').append((now - at) / 1_000_000);
            at = now;
        }
    }

    private static float[] readStats(GLTexture t) {
        int w = t.size.x, h = t.size.y;
        int[] oldRead = new int[1], framebuffer = new int[1];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        try {
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, t.id, 0);
            ByteBuffer data = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder());
            android.opengl.GLES30.glReadPixels(0, 0, w, h, android.opengl.GLES30.GL_RGBA_INTEGER, android.opengl.GLES30.GL_UNSIGNED_INT, data);
            float[] out = new float[w * h * 8];
            for (int i = 0; i < w * h * 4; i++) {
                int v = data.getInt(i * 4);
                out[2 * i] = NoiseMath.half(v & 0xffff);
                out[2 * i + 1] = NoiseMath.half((v >>> 16) & 0xffff);
            }
            return out;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    private static float[] levelNoise(GLProg glProg, List<GLTexture> owned, GLTexture level, int block, float sY, float rY, float[] dcg,
                                      float[][] rawOut) {
        Point blocks = new Point(Math.max(1, level.size.x / block), Math.max(1, level.size.y / block));
        GLTexture st = tex(owned, blocks, GLFormat.DataType.UNSIGNED_32, 4, GL_NEAREST);
        try {
            statsProgram(glProg, dcg, sY, rY);
            glProg.setTexture("InputBuffer", level);
            glProg.setVar("blockU", block);
            glProg.drawBlocks(st);
            float[] raw = readStats(st);
            if (rawOut != null) rawOut[0] = raw;
            return NoiseMath.reduceLegacy(raw, blocks.x * blocks.y, 0.8f);
        } finally {
            release(owned, st);
        }
    }

    private static void statsProgram(GLProg glProg, float[] dcg, float sY, float rY) {
        if (dcg != null) glProg.setDefine("DCG_ZONES", 1);
        glProg.useAssetProgram("scamdn/stats", false);
        glProg.setVar("atU", 0, 0);
        glProg.setVar("modelU", sY, rY);
        if (dcg != null) {
            glProg.setVar("modelHiU", rY * dcg[0]);
            glProg.setVar("kneeU", dcg[1]);
        }
    }

    private static float[][] levelNoiseBatch(GLProg glProg, List<GLTexture> owned, GLTexture[] levels, int block,
                                             float sY, float rY, float[] dcg, float[][] rawOut, Laps laps, Runnable whileGpu) {
        final int n = levels.length;
        final int[] bw = new int[n], bh = new int[n], ax = new int[n], ay = new int[n];
        for (int i = 0; i < n; i++) {
            bw[i] = Math.max(1, levels[i].size.x / block);
            bh[i] = Math.max(1, levels[i].size.y / block);
        }
        final int[] atlasSize = statsAtlas(bw, bh, ax, ay);
        final float[][] raws = new float[n][];
        final int[] texels;
        GLTexture atlas = null;
        try {
            atlas = tex(owned, new Point(atlasSize[0], atlasSize[1]), GLFormat.DataType.UNSIGNED_32, 4, GL_NEAREST);
            for (int i = 0; i < n; i++) {
                statsProgram(glProg, dcg, sY, rY);
                glProg.setTexture("InputBuffer", levels[i]);
                glProg.setVar("blockU", block);
                glProg.setVar("atU", ax[i], ay[i]);
                drawRegion(glProg, atlas, ax[i], ay[i], bw[i], bh[i]);
            }
            laps.lap("statsDraw");
            if (whileGpu != null) {
                whileGpu.run();
                laps.lap("strmap");
            }
            texels = readInts(atlas);
        } finally {
            release(owned, atlas);
        }
        laps.lap("statsRead");
        final float[][] out = new float[n][];
        com.scamera.app.util.ParallelWork.forEach(n, i -> {
            raws[i] = statsOf(texels, atlasSize[0], ax[i], ay[i], bw[i], bh[i]);
            out[i] = NoiseMath.reduce(raws[i], bw[i] * bh[i], 0.8f);
        });
        if (rawOut != null) rawOut[0] = raws[0];
        laps.lap("statsReduce");
        return out;
    }

    static int[] statsAtlas(int[] w, int[] h, int[] x, int[] y) {
        int width = 0, shelfY = 0, shelfH = 0, at = 0;
        for (int i = 0; i < w.length; i++) {
            if (i > 0 && at + w[i] > width) { shelfY += shelfH; shelfH = 0; at = 0; }
            x[i] = at;
            y[i] = shelfY;
            at += w[i];
            shelfH = Math.max(shelfH, h[i]);
            width = Math.max(width, at);
        }
        return new int[]{width, shelfY + shelfH};
    }

    static float[] statsOf(int[] texels, int atlasWidth, int x0, int y0, int w, int h) {
        final float[] table = HalfFloat.TABLE;
        final float[] out = new float[w * h * 8];
        for (int y = 0; y < h; y++) {
            final int src = ((y0 + y) * atlasWidth + x0) * 4, dst = y * w * 8;
            for (int k = 0; k < w * 4; k++) {
                final int v = texels[src + k];
                out[dst + 2 * k] = table[v & 0xffff];
                out[dst + 2 * k + 1] = table[v >>> 16];
            }
        }
        return out;
    }

    private static void drawRegion(GLProg glProg, GLTexture target, int x, int y, int w, int h) {
        target.renderInto();
        final com.scamera.app.processing.opengl.GLBlockDivider divider =
                new com.scamera.app.processing.opengl.GLBlockDivider(h, com.scamera.app.processing.opengl.GLDrawParams.TILE_ROWS);
        final int[] row = new int[2];
        while (divider.nextBlock(row)) {
            android.opengl.GLES30.glViewport(x, y + row[0], w, row[1]);
            glProg.draw();
            com.scamera.app.processing.opengl.GpuPace.block(); // P616: a submission per block while paced
        }
        android.opengl.GLES30.glFlush();
    }

    /**
     * P473: levelNoise of the finish level 0 (block 16) straight from the linear RGB {@code rgb}: scamdn/stats_cs, one work group a
     * block, the YUV and its half-float rounding made there, the sums of scamdn/stats in their order. Null when a GL call fails.
     */
    private static float[] finishNoiseCs(GLProg glProg, List<GLTexture> owned, GLTexture rgb, float sY, float rY, float[][] rawOut) {
        final int bw = Math.max(1, rgb.size.x / 16), bh = Math.max(1, rgb.size.y / 16);
        for (int i = 0; i < 8 && android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR; i++) {
            // drain: only the pass below decides
        }
        GLTexture atlas = null;
        try {
            atlas = tex(owned, new Point(bw, bh), GLFormat.DataType.UNSIGNED_32, 4, GL_NEAREST);
            glProg.setLayout(16, 16, 1);
            glProg.useAssetProgram("scamdn/stats_cs", true);
            glProg.setTexture("InputBuffer", rgb);
            glProg.setVar("modelU", sY, rY);
            glProg.setTextureCompute("outStats", atlas, true);
            glProg.computeManual(bw, bh, 1);
            final int[] texels = readInts(atlas);
            final int error = android.opengl.GLES30.glGetError();
            if (error != android.opengl.GLES30.GL_NO_ERROR) {
                Log.w("SCAM_PIPELINE", "finish noise compute pass: GL error 0x" + Integer.toHexString(error) + ", the two passes");
                return null;
            }
            final float[] raw = statsOf(texels, bw, 0, 0, bw, bh);
            rawOut[0] = raw;
            return NoiseMath.reduce(raw, bw * bh, 0.8f);
        } catch (RuntimeException e) {
            Log.w("SCAM_PIPELINE", "finish noise compute pass: " + e + ", the two passes");
            return null;
        } finally {
            release(owned, atlas);
        }
    }

    private static int[] readInts(GLTexture t) {
        int w = t.size.x, h = t.size.y;
        int[] oldRead = new int[1], framebuffer = new int[1];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        try {
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, t.id, 0);
            ByteBuffer data = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder());
            android.opengl.GLES30.glReadPixels(0, 0, w, h, android.opengl.GLES30.GL_RGBA_INTEGER, android.opengl.GLES30.GL_UNSIGNED_INT, data);
            final int[] out = new int[w * h * 4];
            data.asIntBuffer().get(out);
            return out;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    static long effHistogram(ByteBuffer eff, int[] histogram) {
        // Bulk reads in chunks of a multiple of 7 bytes, so every chunk starts on a sampled code: the samples of eff.get(i)
        // for i = 0, 7, 14, ... (1.8 M buffer calls at 12 MP before).
        final int capacity = eff.capacity();
        final byte[] chunk = new byte[7 * 9362];
        final ByteBuffer src = eff.duplicate();
        long count = 0;
        for (int start = 0; start < capacity; start += chunk.length) {
            final int n = Math.min(chunk.length, capacity - start);
            src.clear();
            src.position(start);
            src.get(chunk, 0, n);
            for (int j = 0; j < n; j += 7) {
                int v = chunk[j] & 255;
                if (v > 0) { histogram[v]++; count++; }
            }
        }
        return count;
    }

    static GLTexture effectiveFramesTexture(Point size, ByteBuffer eff) {
        android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT, 1);
        final GLFormat format = new GLFormat(GLFormat.DataType.UNSIGNED_8, 1);
        final GLTexture t = new GLTexture(size, format, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        final boolean legacy = com.scamera.app.processing.opengl.PostGlMode.legacy();
        final boolean real = !legacy && PreferenceKeys.scamDevSwitch("effmap_real", true);
        int uploadError = android.opengl.GLES30.GL_NO_ERROR;
        if (real) {
            // Errors left by earlier calls are dropped first, so only this upload decides.
            for (int i = 0; i < 8 && android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR; i++) {
                // drain
            }
            eff.rewind();
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, t.id);
            android.opengl.GLES30.glTexSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, 0, 0, size.x, size.y,
                    android.opengl.GLES30.GL_RED_INTEGER, android.opengl.GLES30.GL_UNSIGNED_BYTE, eff);
            uploadError = android.opengl.GLES30.glGetError();
            eff.rewind();
        }
        final boolean loaded = real && uploadError == android.opengl.GLES30.GL_NO_ERROR;
        if (!loaded) {
            t.renderInto();
            android.opengl.GLES30.glClearBufferuiv(android.opengl.GLES30.GL_COLOR, 0, new int[]{0, 0, 0, 0}, 0);
        }
        if (legacy) {
            Log.i("SCAM_PIPELINE", "EFFMAP old run of post_ab: map cleared (as before W3.5)");
        } else if (!effUploadLogged || !loaded) {
            effUploadLogged = true;
            Log.i("SCAM_PIPELINE", loaded ? "EFFMAP real codes uploaded (GL_RED_INTEGER), one-sided strength"
                    : real ? "EFFMAP integer upload rejected by the driver (0x" + Integer.toHexString(uploadError) + "): map cleared"
                    : "EFFMAP effmap_real 0: map cleared (as before W3.5)");
        }
        return t;
    }

    static float[] dcgZones(Parameters par) {
        if (par == null || !par.dcgShot || com.scamera.app.processing.opengl.PostGlMode.legacy()) return null;
        return NoiseModeler.dcgZones(par.dcgNoise);
    }

    static float[] dcgShaderPass(float rY, float rYlcg, float kneeY) {
        if (!(kneeY > 0f) || !Float.isFinite(kneeY) || !(rY > 0f)) return null;
        final float ratio = rYlcg / rY;
        return Float.isFinite(ratio) ? new float[]{ratio, kneeY} : null;
    }

    /**
     * {@link #finishSnr} in two halves: {@link #finishSnrBegin} measures level 0's statistics (GPU and read-back),
     * {@link #finishSnrEnd} does the CPU part. WhyResolveDenoise issues its passes in between when nothing before them needs the
     * SNR (WhyLumaSharpen and WhyCombine do): the same texels, the same arithmetic, the same SNR.
     */
    static final class PendingSnr {
        Parameters par;
        PostPipeline pipeline;
        GLProg glProg;
        GLTexture clean;
        float sY, rY, sG, rG, rGhi, kneeY, rhoS, rhoR, snrFixed, snrScale;
        float[] zones, dcgPass, g0;
        final float[][] raw0 = new float[1][];
        int s;
        Point size0;
    }

    static float finishSnr(GLProg glProg, PostPipeline pipeline, GLTexture clean, StringBuilder log) {
        return finishSnrEnd(finishSnrBegin(glProg, pipeline, clean), log);
    }

    static PendingSnr finishSnrBegin(GLProg glProg, PostPipeline pipeline, GLTexture clean) {
        final PendingSnr st = new PendingSnr();
        final Parameters par = pipeline.shotParams;
        st.par = par;
        st.pipeline = pipeline;
        st.glProg = glProg;
        st.clean = clean;
        final Point full = clean.size;
        final Point shotSize = par.labWholeSize != null ? par.labWholeSize : full;
        final int s = ScamHdrDenoise.huge(shotSize.x, shotSize.y) ? 2 : Math.max(1, Math.min(2, Math.round(par.outputScale)));
        st.s = s;
        st.size0 = new Point((full.x + s - 1) / s, (full.y + s - 1) / s);
        st.rhoS = clamp(PreferenceKeys.hybridValue("dn_model_shot", 1.2f), 0.25f, 4f);
        st.rhoR = clamp(PreferenceKeys.hybridValue("dn_model_read", 2f), 0.25f, 16f);
        st.snrFixed = clamp(PreferenceKeys.hybridValue("dn_snr", 0f), 0f, 200f);
        st.snrScale = clamp(PreferenceKeys.hybridValue("dn_snr_scale", 1f), 0.25f, 8f);
        final List<GLTexture> owned = new ArrayList<>();
        try {
            final float[] wY = {0.2126f, 0.7152f, 0.0721996f};
            float[] S = {2e-4f, 2e-4f, 2e-4f}, O = {2e-6f, 2e-6f, 2e-6f};
            boolean camera = false;
            final float[] zones = dcgZones(par);
            st.zones = zones;
            if (par.noiseModel != null && par.noiseModel.rawModel != null && par.noiseModel.rawModel[0] != null) {
                for (int c = 0; c < 3; c++) {
                    S[c] = (float) Math.max(1e-9, par.noiseModel.rawModel[c].first);
                    O[c] = (float) Math.max(1e-12, par.noiseModel.rawModel[c].second);
                }
                camera = true;
                final float k = ScamHybridBurst.lastNoiseFactor;
                if (k != 1f && zones == null) for (int c = 0; c < 3; c++) { S[c] *= k; O[c] *= k; }
            }
            float sY = 0f, rY = 0f, sG = 0f, rG = 0f;
            for (int c = 0; c < 3; c++) {
                float wp = par.asShotNeutral != null && par.asShotNeutral.length > c && par.asShotNeutral[c] > 1e-6f ? par.asShotNeutral[c] : 1f;
                float g = 1f / wp;
                sY += wY[c] * wY[c] * g * S[c];
                rY += wY[c] * wY[c] * g * g * O[c];
                if (c == 1) { sG = g * S[c]; rG = g * g * O[c]; }
            }
            float rGhi = rG, kneeY = 0f;
            float[] dcgPass = null;
            if (zones != null) {
                final float[] t = NoiseMath.dcgTerms(zones, S, O, par.asShotNeutral);
                sY = t[0]; rY = t[1]; sG = t[3]; rG = t[4]; rGhi = t[5]; kneeY = t[6];
                dcgPass = dcgShaderPass(rY, t[2], kneeY);
            }
            st.sY = sY; st.rY = rY; st.sG = sG; st.rG = rG; st.rGhi = rGhi; st.kneeY = kneeY; st.dcgPass = dcgPass;
            final boolean legacy = com.scamera.app.processing.opengl.PostGlMode.legacy();
            // P473 (speed): level 0's statistics in one compute pass from the RGB (the YUV level and the fragment statistics took
            // ~50 ms of GPU on the OPPO PHY110); scam_dev.txt "snr_cs 0", a 2x grid, DCG zones or a failure: the two passes
            final boolean cs = s == 1 && dcgPass == null && !legacy && PreferenceKeys.scamDevSwitch("snr_cs", true);
            st.g0 = cs ? finishNoiseCs(glProg, owned, clean, sY, rY, st.raw0) : null;
            if (st.g0 == null) twoPassLevel0(st, owned);
            return st;
        } finally {
            for (GLTexture t : owned) if (t != null) t.close();
            owned.clear();
        }
    }

    /** Level 0's statistics by the YUV level and the fragment statistics (the path before P473, and its fallback). */
    private static void twoPassLevel0(PendingSnr st, List<GLTexture> owned) {
        final GLProg glProg = st.glProg;
        final GLTexture clean = st.clean;
        final boolean legacy = com.scamera.app.processing.opengl.PostGlMode.legacy();
        st.raw0[0] = null;
        GLTexture base = clean;
        if (st.s == 2) {
            base = tex(owned, st.size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
            glProg.useAssetProgram("scamdn/down2x", false);
            glProg.setTexture("InputBuffer", clean);
            glProg.drawBlocks(base);
        }
        final GLTexture x0 = tex(owned, st.size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
        glProg.useAssetProgram("scamdn/yuv", false);
        glProg.setTexture("InputBuffer", base);
        glProg.drawBlocks(x0);
        if (base != clean) release(owned, base);
        st.g0 = legacy
                ? levelNoise(glProg, owned, x0, 16, st.sY, st.rY, st.dcgPass, st.raw0)
                : levelNoiseBatch(glProg, owned, new GLTexture[]{x0}, 16, st.sY, st.rY, st.dcgPass, st.raw0, new Laps(), null)[0];
    }

    static float finishSnrEnd(PendingSnr st, StringBuilder log) {
        final Parameters par = st.par;
        final PostPipeline pipeline = st.pipeline;
        final float[] g0 = st.g0;
        final float[][] raw0 = st.raw0;
        final float rG = st.rG;
        final float[] zones = st.zones;
        final float gy00 = g0 != null ? g0[0] : NoiseMath.whiteGain(0, false) / 4f;
        float p50 = 0f, p90 = 0f;
        if (raw0[0] != null) {
            final float[] raw = raw0[0];
            final int n = raw.length / 8;
            int m = 0;
            final float[] ys = new float[n];
            for (int i = 0; i < n; i++) if (raw[8 * i] >= 0f && Float.isFinite(raw[8 * i])) ys[m++] = raw[8 * i];
            if (m > 0) {
                p50 = NoiseMath.selectFloat(ys, m, Math.min(m - 1, m / 2));
                p90 = NoiseMath.selectFloat(ys, m, Math.min(m - 1, (int) (m * 0.9f)));
            }
        }
        if (pipeline.frozen != null && pipeline.frozen.p50 > 0f) {
            p50 = pipeline.frozen.p50;
            p90 = pipeline.frozen.p90;
        }
        if (pipeline.record != null) {
            pipeline.record.p50 = p50;
            pipeline.record.p90 = p90;
        }
        final float rawScale = par.scamHdrMode ? Math.max(1e-6f, par.scamHdrRawScale) : 1f;
        float mu = 0.18f, gain = 1f;
        if (p50 > 0f) {
            gain = displayGain(p50 / rawScale, p90 / rawScale, PreferenceKeys.isScamNetSoc());
            mu = 0.18f * rawScale / gain;
        }
        final float rGmu = zones != null ? NoiseModeler.zoneOffset(mu, rG, st.rGhi, st.kneeY) : rG;
        final float snrMeasured = NoiseMath.snr(mu, gy00, st.sG, rGmu, st.rhoS, st.rhoR);
        final float snr = st.snrFixed > 0f ? st.snrFixed : snrMeasured * st.snrScale;
        par.finishSnr = snr;
        // P648c: the luma noise the statistics measured on this input (its model x the measured ratio), for the Resolve's flat test
        par.finishNoiseS = gy00 * st.sY;
        par.finishNoiseO = gy00 * st.rY;
        if (log != null) log.append(" finishSnr=").append(round(snr)).append(st.snrFixed > 0f ? "(fixed)" : "").append(" mu=").append(mu)
                .append(" gain=").append(round(gain)).append(" g00=").append(round3(gy00));
        return snr;
    }

    private static float round(float v) { return Math.round(v * 100f) / 100f; }

    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
}
