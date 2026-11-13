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

/** Historical arithmetic fixtures for the removed SCAM denoiser; never packaged in the app. */
public final class ScamDenoise {
    private ScamDenoise() {}

    /**
     * P335 (owner, 2026-10-25: the grain «между Why и нашим»): the share of the luma noise the denoise removed on the finest scale
     * that comes back at the output (scam_dev hybrid_dn_luma_grain; 0 = the denoise as before).
     */
    static final float LUMA_GRAIN = 0.5f;

    /**
     * The film recipe sets its grain, including an explicit zero: no extra denoise residual at 100 %.
     * Without a film the value is unchanged; at partial strength it fades with our share.
     */
    static float lumaGrainUnderFilm(float lumaGrain, @androidx.annotation.Nullable com.scamera.app.processing.color.FilmSim.Settings film) {
        return film != null ? lumaGrain * (1f - film.strength) : lumaGrain;
    }

    /**
     * True for a shot merged by the SCAM Hybrid. P421: the NLM engine of the hybrid is gone as a choice (the reference app has the
     * one finish denoise); {@link ScamHdrDenoise} stays the fallback when this node fails.
     */
    public static boolean enabledFor(PostPipeline p) {
        return p != null && p.shotParams != null && p.shotParams.scamRgb != null && PreferenceKeys.isHybridShot();
    }



    private static GLTexture tex(List<GLTexture> owned, Point size, GLFormat.DataType type, int channels, int filter) {
        GLTexture t = new GLTexture(size, new GLFormat(type, channels), null, filter, GL_CLAMP_TO_EDGE);
        owned.add(t);
        return t;
    }
    private static void release(List<GLTexture> owned, GLTexture t) {
        if (t != null && owned.remove(t)) t.close();
    }
    private static Point half(Point p) { return new Point((p.x + 1) / 2, (p.y + 1) / 2); }

    /** Settings of the hybrid section «Шумоподавление WHY / SCAM». */
    static ScamDenoiseTables.Config readConfig() {
        ScamDenoiseTables.Config c = new ScamDenoiseTables.Config();
        for (int i = 0; i < c.lumaSnr.length; i++) {
            String k = "dn_luma_t" + (i + 1) + "_";
            c.lumaSnr[i] = clamp(PreferenceKeys.hybridValue(k + "snr", c.lumaSnr[i]), 0.1f, 500f);
            c.lumaStrength[i] = PreferenceKeys.hybridList(k + "strength", c.lumaStrength[i]);
            c.lumaRevert[i] = PreferenceKeys.hybridList(k + "revert", c.lumaRevert[i]);
            c.lumaOutlier[i] = PreferenceKeys.hybridList(k + "outlier", c.lumaOutlier[i]);
        }
        for (int i = 0; i < c.chromaSnr.length; i++) {
            String k = "dn_chroma_t" + (i + 1) + "_";
            c.chromaSnr[i] = clamp(PreferenceKeys.hybridValue(k + "snr", c.chromaSnr[i]), 0.1f, 500f);
            c.chromaStrength[i] = PreferenceKeys.hybridList(k + "strength", c.chromaStrength[i]);
            c.chromaOutlier[i] = PreferenceKeys.hybridList(k + "outlier", c.chromaOutlier[i]);
        }
        c.lumaMult = clamp(PreferenceKeys.hybridValue("dn_luma_mult", 1f), 0f, 4f);
        c.gid14Mult = clamp(PreferenceKeys.hybridValue("dn_luma_gid14_mult", 1f), 0f, 4f);
        c.sabreMult = clamp(PreferenceKeys.hybridValue("dn_sabre_luma_mult", 1f), 0f, 4f);
        c.chromaMult = clamp(PreferenceKeys.hybridValue("dn_chroma_mult", 1f), 0f, 4f);
        c.chromaFloor = clamp(PreferenceKeys.hybridValue("dn_chroma_floor", 0f), 0f, 5f);
        c.revertMult = clamp(PreferenceKeys.hybridValue("dn_revert_mult", 1f), 0f, 4f);
        c.revertMax = clamp(PreferenceKeys.hybridValue("dn_revert_max", 1f), 0f, 9f);
        c.coarseStock = clamp(PreferenceKeys.hybridValue("dn_coarse_stock", 0f), 0f, 1f);
        return c;
    }
    private static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    /**
     * Linear display gain the tone stage will apply, mirrored from {@link LinearExposure} on block means of the frame
     * (p50/p90 of the white-balanced luminance): the denoise runs before it, the reference EstimateSnr needs it for the mean
     * signal 0.18 / gain. The mid / high anchors and the cap are the former LinearExposure defaults (their settings went
     * with the SCAMERA tone).
     */
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

    /**
     * P364: scam_dev.txt "post_sync 1" (PostGlMode.syncNodes): the GPU time of each stage of this node - a glFinish at every
     * stage end, logged as "scam-denoise laps ms". Off (every normal shot): nothing is synced or logged.
     */
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

    /** Reads an RGBA32UI statistics texture back as 8 floats per block. */
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

    /** Block statistics of one level (scamdn/stats), reduced on the host; null when too few flat blocks (old model of post_ab). */
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

    /**
     * scamdn/stats with the single-frame model (sY, rY); P320: a DCG shot ({@code dcg} = {rY_lcg / rY, knee in Y}, null for any
     * other) takes the DCG_ZONES variant, rY being the high-gain zone's and modelHiU the low-gain one's. P364: the atlas corner
     * atU is (0, 0) until levelNoiseBatch sets it (the program keeps a uniform between uses).
     */
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

    /**
     * W1.6: {@link #levelNoise} of several levels at once. Every statistics pass is drawn first, then they are read back
     * (one sync instead of one per level), and the levels are reduced in parallel with the primitive-key sorts of
     * {@link ScamDenoiseTables#reduce}. The same passes, read-backs and reductions, so the same gains; rawOut gets level 0.
     * <p>
     * P364: the passes write the rectangles of one RGBA32UI atlas ({@link #statsAtlas}; scamdn/stats atU = the level's corner,
     * the same block origins), read back by ONE glReadPixels (seven read-backs were seven GPU round trips behind the viewfinder's
     * work: statsRead 14-44 ms), widened by HalfFloat.TABLE (= ScamDenoiseTables.half) on the helpers beside the reductions.
     * {@code whileGpu} (the strength map: nothing in it reads the statistics) runs after the passes are queued, so its CPU part
     * (effective-frame histogram, upload) overlaps the GPU. (A Y-only variant of the statistics for the luma levels was the same
     * bits but 2.5 x slower on level 1 on the OPPO's Adreno 750, so every level keeps the full shader.)
     */
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

    /**
     * P364: shelf layout of the statistics atlas: rectangle 0 (level 0) alone on the first shelf, then the others left to right in
     * order, a new shelf when one would pass the width so far. Writes the corners into x / y; returns {width, height}.
     */
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

    /**
     * P364: the statistics of one level from the atlas ints (4 per texel, rows from y = 0, {@code atlasWidth} texels a row): 8
     * floats per block in {@link #readStats}' layout, each half widened by HalfFloat.TABLE (bit for bit ScamDenoiseTables.half).
     */
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

    /** P364: one pass into the rectangle (x, y, w, h) of target, rows tiled as drawBlocks tiles them (gl_FragCoord is target's). */
    private static void drawRegion(GLProg glProg, GLTexture target, int x, int y, int w, int h) {
        target.renderInto();
        final com.scamera.app.processing.opengl.GLBlockDivider divider =
                new com.scamera.app.processing.opengl.GLBlockDivider(h, com.scamera.app.processing.opengl.GLDrawParams.TILE_ROWS);
        final int[] row = new int[2];
        while (divider.nextBlock(row)) {
            android.opengl.GLES30.glViewport(x, y + row[0], w, row[1]);
            glProg.draw();
        }
        android.opengl.GLES30.glFlush();
    }

    /** P364: an RGBA32UI texture read back whole as ints (4 per texel, rows from y = 0): one glReadPixels. */
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

    /** Histogram of every 7th code of the effective-frame map (codes 1..255); returns the number of non-zero samples. */
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

    /** Effective-frame map of the worker as a texture (codes, 0 = unknown); median code in effRef[0]. Null when absent. */
    private static GLTexture loadEffectiveFrames(List<GLTexture> owned, Point size, float[] effRef) {
        ByteBuffer eff = ScamHybridBurst.lastEffectiveFrames;
        if (eff == null || eff.capacity() != size.x * size.y) return null;
        eff.rewind();
        int[] histogram = new int[256];
        long count = effHistogram(eff, histogram);
        if (count == 0) return null;
        long seen = 0;
        int median = 1;
        for (int v = 1; v < 256; v++) { seen += histogram[v]; if (seen * 2 >= count) { median = v; break; } }
        effRef[0] = median;
        eff.rewind();
        GLTexture t = effectiveFramesTexture(size, eff);
        owned.add(t);
        return t;
    }

    /**
     * The effective-frame map texture (GL_R8UI) of {@link #loadEffectiveFrames} and ScamHdrDenoise.
     * <p>
     * Until W3.5 its upload used GL_RED, which GL_R8UI does not accept (GL_INVALID_OPERATION, the "glTexSubImage2D glError
     * 0x502" of every shot): the map was cleared to zero (W1.1), every code read "unknown" and the strength map was 1.0
     * everywhere. W3.5 (owner, 2026-10-07) uploads the real codes (GL_RED_INTEGER / GL_UNSIGNED_BYTE); the shaders read them
     * ONE-SIDED (scamdn/strmap, chromadn/nlm, chromadn/apply: the noise factor is clamped to >= 1), so the map only
     * strengthens the denoise where fewer than the median frames merged (the frame edge, rejected motion) and every pixel
     * at or above the median keeps the strength of the cleared map exactly.
     * <p>
     * The cleared map (the behaviour before W3.5) is kept for scam_dev.txt "effmap_real 0", for the old run of post_ab (so
     * the A/B shows this change on one worker result) and when the driver rejects the integer upload.
     */
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

    /** The successful upload of the effective-frame map is logged once per process (the driver does not change). */
    private static volatile boolean effUploadLogged;

    /**
     * P214d: the reference gain of {@link #lscTerm} sits this much above the map's smallest luminance gain: the GainMap
     * texture holds fp16 (2^-11 relative) and is sampled bilinearly, so the texels next to the minimum read g <= 1 and keep
     * f = 1 exactly instead of 1 + a rounding error.
     */
    static final float LSC_MARGIN = 1f + 1f / 512f;

    /**
     * P214d: default cap of the lens-shading variance multiplier: scam_dev "denoise_lsc_max". P330: 2 (sigma at most x1.41; was 4):
     * at f > 2 scamdn/lbf also cuts the revert to 0.71, so the corners lost about 30 % of a level that the centre puts back whole.
     */
    static final float LSC_CAP = 2f;

    /**
     * P214d: the luminance gain of one lens-shading site (r, g_even, g_odd, b): the mean of r, (g_even + g_odd) / 2 and b,
     * the factor why/low, why/combine and why/sharp_in multiply the denoised image by (scamrgb applies only the colour part,
     * normalised to mean 1).
     */
    static float lscGain(float r, float gEven, float gOdd, float b) {
        return (r + 0.5f * (gEven + gOdd) + b) / 3f;
    }

    /**
     * P214d: the lens-shading factor of scamdn/strmap for one (interpolated) site gain: min(g^2, cap) with g = gain / the
     * reference gain, 1 where g <= 1 or the gain is not a number (the Java mirror of the shader, for the tests and the log).
     */
    static float lscFactor(float gain, float invReference, float cap) {
        final float g = gain * invReference;
        return g > 1f ? Math.min(g * g, Math.max(cap, 1f)) : 1f;
    }

    /**
     * P214d: the lens-shading term of the strength map, from the map ScamRgb uploaded as the pipeline's GainMap
     * (Parameters.gainMap, {@code mw} x {@code mh} sites of (r, g_even, g_odd, b)).
     * <p>
     * Why: the denoise works before the WHY tone, which multiplies every pixel by the luminance gain of the map (corner
     * gains 2.0-3.8 in the phone logs, quality survey section 7): the noise the denoise leaves in a corner comes out
     * up to 3.8 x larger in amplitude than the same residue in the centre, on a signal that collected fewer photons. GCam 11
     * multiplies its strength map by (1, g, g^2) of the shading gain (GCAM11_PIPELINE.md in SCAMERA-PC/research/gcam11,
     * section 9 item 10a: "the corners with the amplified vignetting correction are denoised harder"). Here the
     * variance multiplier of scamdn/strmap is multiplied by min(g^2, cap), g = the site's luminance gain over the smallest
     * one of the map (times
     * {@link #LSC_MARGIN}): one-sided like the effective-frame factor, so the optical centre keeps f = 1 exactly and only the
     * shaded part of the frame is filtered harder (cap 4 = sigma at most x2: a corner is filtered as for twice its noise).
     * <p>
     * Not a noise-model correction: GCam denoises AFTER its gain map, and its (1, g, g^2) on the (shot, read) terms only
     * carries the model through that gain. Here only the colour part of the shading comes before the denoise, and the
     * model already reads the local, still unshaded signal (lbf / cbf: sigma^2 = f (noise.x Y + noise.y)): at f = 1 a
     * corner is filtered for the noise it has (GCam's behaviour). The g^2 on top is the deliberate part: a corner keeps
     * less of its noise, so after the WHY gain its grain comes closer to the centre's - at the cost of fine corner texture
     * near the noise level (the lbf revert also drops to 0.71 x where f > 2). Phone check: sigma of a flat wall / sky in
     * the centre and the corners, and the detail of a textured scene's corners, against "denoise_lsc 0".
     * <p>
     * Null (no term, the map as before) without a real map: no data, a 1 x 1 map (the identity of a HAL without one, or of
     * a RAW whose shading the HAL applied), a flat map, no valid site, or cap <= 1. Sites that are not a positive finite
     * number are skipped (the shader reads them as factor 1).
     *
     * @return {1 / reference gain, cap, the largest factor of the map (for the log)} or null
     */
    static float[] lscTerm(float[] gainMap, int mw, int mh, float cap) {
        if (gainMap == null || mw < 1 || mh < 1 || (long) mw * mh < 2 || gainMap.length < 4L * mw * mh) return null;
        if (!(cap > 1f) || Float.isInfinite(cap)) return null;
        float lo = Float.POSITIVE_INFINITY, hi = 0f;
        for (int i = 0, n = mw * mh; i < n; i++) {
            final float g = lscGain(gainMap[4 * i], gainMap[4 * i + 1], gainMap[4 * i + 2], gainMap[4 * i + 3]);
            if (!(g > 0f) || Float.isInfinite(g)) continue;
            lo = Math.min(lo, g);
            hi = Math.max(hi, g);
        }
        if (lo == Float.POSITIVE_INFINITY) return null;
        final float reference = lo * LSC_MARGIN;
        if (!(hi > reference)) return null; // flat: no shading to follow
        final float inv = 1f / reference;
        return new float[]{inv, cap, lscFactor(hi, inv, cap)};
    }

    /**
     * P214d: {@link #lscTerm} of this shot: scam_dev "denoise_lsc" (default 1) and "denoise_lsc_max" (the cap, default
     * {@link #LSC_CAP}, 1..16); off in the old run of post_ab (the A/B shows this change on one worker result) and without
     * the pipeline's GainMap texture.
     */
    static float[] lscTerm(PostPipeline pipeline) {
        if (com.scamera.app.processing.opengl.PostGlMode.legacy()) return null;
        if (!PreferenceKeys.scamDevSwitch("denoise_lsc", true)) return null;
        if (pipeline == null || pipeline.GainMap == null || pipeline.shotParams == null) return null;
        final Parameters par = pipeline.shotParams;
        if (par.shadingGrid == null) return null;
        final float cap = clamp(PreferenceKeys.scamDevNumber("denoise_lsc_max", LSC_CAP), 1f, 16f);
        // P277: a lab fragment's map is cropped: the reference of the whole map (LabFrame), a lab shot records it
        final LabFrame frozen = pipeline.frozen;
        if (frozen != null && (frozen.lsc != null || frozen.lscNone)) return frozen.lsc == null ? null : frozen.lsc.clone();
        final float[] term = lscTerm(par.shadingMap, par.shadingGrid.x, par.shadingGrid.y, cap);
        if (pipeline.record != null) {
            pipeline.record.lsc = term == null ? null : term.clone();
            pipeline.record.lscNone = term == null;
        }
        return term;
    }

    /**
     * Dark fade of the denoise (scamdn/cbf, scamdn/final2x, chromadn/apply): the colour of pixels darker than mean RGB
     * 0.0008..0.003 fades to neutral, which hid the black-level tint, mostly the per-pixel clip bias of the clamped merge
     * RGB. On signed hybrid input that bias is gone and the fade greyed dark saturated colours (the teal curtain of the
     * OPPO shot 2026-10-07 at mean RGB ~0.0011 kept 18-54 % of its colour). There a colour whose deviation from neutral
     * |RGB - mean| exceeds what a black-level tint gives keeps it: {lo, hi} = where it starts to stay / stays fully, in
     * linear white-balanced RGB (default 1.5e-4 / 4e-4: a 0.05 DN black-level error tints a neutral black by ~1.5e-4).
     * scam_dev.txt "hybrid_dn_dark_chroma_hi 0" (or the old run of post_ab, or clamped input such as SCAM HDR) = the
     * fade of the luminance alone, as before; "hybrid_dn_dark_chroma_lo / _hi v" set the floor in units of 1e-4.
     */
    static float[] darkChroma(boolean signedInput) {
        if (!signedInput || com.scamera.app.processing.opengl.PostGlMode.legacy()) return new float[]{0f, 0f};
        final float hi = clamp(PreferenceKeys.hybridValue("dn_dark_chroma_hi", 4f), 0f, 100f) * 1e-4f;
        if (hi <= 0f) return new float[]{0f, 0f};
        final float lo = Math.min(clamp(PreferenceKeys.hybridValue("dn_dark_chroma_lo", 1.5f), 0f, 100f) * 1e-4f, 0.99f * hi);
        return new float[]{lo, hi};
    }

    /**
     * The keep floor of {@link #darkChroma} following the colour noise the denoise leaves (scamdn/cbf, scamdn/final2x
     * darkNoiseU): with the fixed floor alone, the colour noise of a neutral black at high ISO or with few frames
     * reached the floor and stayed as coloured blotches (host ScamDenoise port, OPPO ISO 6400 night scene and a dark chart:
     * neutral blotch +10..22 % against the former fade, the more the noisier). The floor becomes max(lo, k sigma) and
     * the ramp ends at max(hi, 2 k sigma), sigma^2 = GC[0][1] (sY mean + rY): the measured noise of the level-0 input
     * before the denoise (the stride-2 difference gain of Y at level 0 on the single-frame luma model, the term the chroma
     * filters scale by UVS), in linear white-balanced RGB variance like darkChroma; k = 0.35 (the residual colour noise is ~0.18 sigma: the floor sits at ~2 x the noise left). At the
     * noise of the 37-frame ISO 6400 shot of 2026-10-07 (measured G_C0 0.034-0.046) the fixed floor stays the larger
     * one (nothing changes); at 5 / 10 x that noise variance the blotches come back to within 3 % of the former fade's
     * while 92 % / 79 % of the dark scene colour is kept (former fade 56 % / 53 %, fixed floor alone 95 % / 86 % with
     * +18 / +22 % blotches). scam_dev.txt "hybrid_dn_dark_chroma_noise k" (0 = the fixed floor alone).
     * Returns {lo, hi, x, y}: darkChromaU = (lo, hi), darkNoiseU = (x, y), floor^2 = x mean + y.
     */
    static float[] darkKeep(float[] darkChroma, float gc0, float sY, float rY) {
        if (darkChroma == null || darkChroma[1] <= 0f) return new float[]{0f, 0f, 0f, 0f};
        final float k = clamp(PreferenceKeys.hybridValue("dn_dark_chroma_noise", 0.35f), 0f, 4f);
        final float g = Float.isFinite(gc0) ? Math.max(gc0, 0f) : 0f;
        return new float[]{darkChroma[0], darkChroma[1], k * k * g * sY, k * k * g * rY};
    }

    /**
     * P320: the measured two noise zones of a DCG shot (NoiseModeler.dcgZones of Parameters.dcgNoise), which replace the
     * camera's single curve here; null for any other shot, a DCG shot without usable zones, and the old run of post_ab (the A/B
     * shows this change on one worker result).
     */
    static float[] dcgZones(Parameters par) {
        if (par == null || !par.dcgShot || com.scamera.app.processing.opengl.PostGlMode.legacy()) return null;
        return NoiseModeler.dcgZones(par.dcgNoise);
    }

    /**
     * P320: the DCG_ZONES uniforms of scamdn stats / lbf / cbf, {rY_lcg / rY, knee in Y}; null (the plain shaders on the measured
     * single curve) for one measured zone (knee 0: GLSL smoothstep(0, 0, Y) is undefined) or a term that is not usable.
     */
    static float[] dcgShaderPass(float rY, float rYlcg, float kneeY) {
        if (!(kneeY > 0f) || !Float.isFinite(kneeY) || !(rY > 0f)) return null;
        final float ratio = rYlcg / rY;
        return Float.isFinite(ratio) ? new float[]{ratio, kneeY} : null;
    }

    /** Set once {@link #process} took the pipeline's output texture (a failure after it cannot fall back to another pass). */
    static volatile boolean lastMainTaken;

    /** The whole noise reduction; returns the output texture (a pipeline main texture). */
    /** P179: the input's storage went back during the last process (no NLM fallback can read it then). */
    static boolean lastInputReleased;



    /**
     * P443: the finish SNR of {@link #process} (Parameters.finishSnr: the WHY sharpening's low-light share, P135, and the detail gain
     * of WhyCombine, P340) and its sports policy (pipeline.sportsDenoise, which WhyMsDenoise reads) for a shot denoised by
     * {@link WhyResolveDenoise}: the same single-frame model (DCG zones, lastNoiseFactor), the level-0 YUV at the sensor scale of
     * {@code clean} (the despeckled input: on the 1x grid process()'s x0 exactly; on the 2x grid despeckled before its kD8 down
     * instead of after), the same level-0 block statistics, display gain, mu and ScamDenoiseTables.snr with dn_model_shot /
     * dn_model_read / dn_snr / dn_snr_scale, the lab's frozen brightness. Nothing of the denoise itself runs; the engines' sharpening
     * and detail gain stay the same for a shot. Returns the SNR (also logged into {@code log}).
     */
    static float finishSnr(GLProg glProg, PostPipeline pipeline, GLTexture clean, StringBuilder log) {
        final Parameters par = pipeline.shotParams;
        final Point full = clean.size;
        final int s = ScamHdrDenoise.huge(full.x, full.y) ? 2 : Math.max(1, Math.min(2, Math.round(par.outputScale)));
        final Point size0 = new Point((full.x + s - 1) / s, (full.y + s - 1) / s);
        final float rhoS = clamp(PreferenceKeys.hybridValue("dn_model_shot", 1.2f), 0.25f, 4f);
        final float rhoR = clamp(PreferenceKeys.hybridValue("dn_model_read", 2f), 0.25f, 16f);
        final float snrFixed = clamp(PreferenceKeys.hybridValue("dn_snr", 0f), 0f, 200f);
        final float snrScale = clamp(PreferenceKeys.hybridValue("dn_snr_scale", 1f), 0.25f, 8f);
        final List<GLTexture> owned = new ArrayList<>();
        try {
            final float[] wY = {0.2126f, 0.7152f, 0.0721996f};
            float[] S = {2e-4f, 2e-4f, 2e-4f}, O = {2e-6f, 2e-6f, 2e-6f};
            boolean camera = false;
            final float[] zones = dcgZones(par);
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
            GLTexture base = clean;
            if (s == 2) {
                base = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                glProg.useAssetProgram("scamdn/down2x", false);
                glProg.setTexture("InputBuffer", clean);
                glProg.drawBlocks(base);
            }
            final GLTexture x0 = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
            glProg.useAssetProgram("scamdn/yuv", false);
            glProg.setTexture("InputBuffer", base);
            glProg.drawBlocks(x0);
            if (base != clean) release(owned, base);
            final float[][] raw0 = new float[1][];
            final float[] g0 = com.scamera.app.processing.opengl.PostGlMode.legacy()
                    ? levelNoise(glProg, owned, x0, 16, sY, rY, dcgPass, raw0)
                    : levelNoiseBatch(glProg, owned, new GLTexture[]{x0}, 16, sY, rY, dcgPass, raw0, new Laps(), null)[0];
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
            final float rGmu = zones != null ? NoiseModeler.zoneOffset(mu, rG, rGhi, kneeY) : rG;
            final float snrMeasured = NoiseMath.snr(mu, gy00, sG, rGmu, rhoS, rhoR);
            final float snr = snrFixed > 0f ? snrFixed : snrMeasured * snrScale;
            par.finishSnr = snr;
            if (log != null) log.append(" finishSnr=").append(round(snr)).append(snrFixed > 0f ? "(fixed)" : "").append(" mu=").append(mu)
                    .append(" gain=").append(round(gain)).append(" g00=").append(round3(gy00));
            return snr;
        } finally {
            for (GLTexture t : owned) if (t != null) t.close();
            owned.clear();
        }
    }

    /**
     * P306: memory the 200 MP denoise asks to be free before it starts (bytes): on the 2x2 means its largest set of RGBA16F
     * textures is five half-size ones (the chroma pass of level 0: base, the luma result, two filter stages, the strength map and
     * pyramids beside them), plus the full-grid noise statistic (R16F), plus a quarter and 512 MB of margin.
     */
    static long hugeBytes(Point full) { return hugeBytes(full.x, full.y); }

    static long hugeBytes(int width, int height) {
        final long half = (long) ((width + 1) / 2) * ((height + 1) / 2);
        return (long) (1.25 * (5 * half * 8 + (long) width * height * 2)) + (512L << 20);
    }

    /** Level-1 hand-over (scamdn/llow): RG = (Den1 - Y1 - LP121(Den1), LP121(Den1)). */








    private static float round(float v) { return Math.round(v * 100f) / 100f; }
    private static String col(float[][] t, int f) {
        float[] v = new float[t.length];
        for (int i = 0; i < t.length; i++) v[i] = t[i][f];
        return NoiseMath.fmt(v);
    }
    private static String gainsLog(float[][] gy, float[][] gc, float[][] uvs) {
        StringBuilder sb = new StringBuilder();
        for (int L = 0; L < gy.length; L++) {
            if (L > 0) sb.append(';');
            sb.append('L').append(L).append(':').append(round3(gy[L][0])).append(',').append(round3(gy[L][1]))
              .append('|').append(round3(gc[L][0])).append(',').append(round3(gc[L][1]))
              .append("|uvs ").append(round(uvs[L][0])).append(',').append(round(uvs[L][2]));
        }
        return sb.toString();
    }
    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
}
