package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/** Resolve A: the reference's three 1/2/4-pixel YUV bilateral passes.
 * Input adapter: normalized merged WB camera RGB and this worker's effective-frame map,
 * not the reference's accumulator weight sum. The filter itself uses the reference formulas.
 */
public final class WhyResolveDenoise {
    private WhyResolveDenoise() {}

    /** The reference app's defaults (ParamRegistry): sabre_denoising_factor, global / reject luma revert, grain strength. */
    static final float FACTOR = 0.4f, LUMA_REVERT = 0.85f, MOTION_REVERT = 0.5f, GRAIN = 0.15f;
    /** outlier_sigma_multiplier: a constant of the reference app's resolve. */
    static final float OUTLIER = 0.125f;
    /** Its noise model without a camera profile (frame meta +0x98 / +0x9c fallback). */
    static final float FALLBACK_S = 1.5e-4f, FALLBACK_O = 1e-5f;

    /** vignette_strength_boost of the one-frame SNR at grey 0.18 (the reference app 0x153440): 4, then 5.3333 - 1.3333 SNR, 0 from 4. */
    static float vignetteBoost(float noiseS, float noiseO) {
        final double snr = 0.18 / Math.sqrt(Math.max(0.18 * Math.max(noiseS, 0f) + Math.max(noiseO, 0f), 1e-12));
        if (snr <= 1.0) return 4f;
        if (snr < 4.0) return (float) (5.3333 - 1.3333 * snr);
        return 0f;
    }

    /** yuv_noise_model: the slope per GCam YUV channel and the intercept. */
    static float[] noiseModel(float noiseS, float noiseO) {
        return new float[]{0.562f * noiseS, 0.25f * noiseS, 0.25f * noiseS, noiseO};
    }

    /**
     * N of a pixel without a map entry (code 0, or no map): the map's median coverage when the worker reported it, else
     * the full normal-frame count; within 1 .. normals.
     */
    static float defaultFrames(float median, int normals) {
        final int total = Math.max(1, normals);
        final float n = median > 0f && Float.isFinite(median) ? median : total;
        return Math.max(1f, Math.min(total, n));
    }

    /** {S, O} of the shot (see the class comment). */
    static float[] shotNoise(Parameters par) {
        if (par == null || par.noiseModel == null || par.noiseModel.rawModel == null || par.noiseModel.rawModel.length < 2
                || par.noiseModel.rawModel[1] == null) return new float[]{FALLBACK_S, FALLBACK_O};
        float s = (float) Math.max(1e-9, par.noiseModel.rawModel[1].first), o = (float) Math.max(1e-12, par.noiseModel.rawModel[1].second);
        final float k = ScamHybridBurst.lastNoiseFactor;
        if (k != 1f && DenoiseStats.dcgZones(par) == null) { s *= k; o *= k; }
        return new float[]{s, o};
    }

    private static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    /**
     * The whole denoise; returns the output texture (a pipeline main texture, max(0, RGB)). P600: its caller (ScamHdrDenoise) runs
     * it only with «Все шумодавы» on; off it hands the input back.
     */
    static GLTexture process(GLProg glProg, PostPipeline pipeline, GLTexture input) {
        if (!DenoiseStages.on()) return input;
        final long started = System.nanoTime();
        final Parameters par = pipeline.shotParams;
        final Point size = input.size;
        final Point sensorSize = par.labWholeSize != null ? par.labWholeSize : size;
        final int sensorX = par.labOrigin != null ? Math.round(par.labOrigin.x * sensorSize.x) : 0;
        final int sensorY = par.labOrigin != null ? Math.round(par.labOrigin.y * sensorSize.y) : 0;
        final int s = Math.max(1, Math.min(2, Math.round(par.outputScale)));
        final float factor = clamp(PreferenceKeys.hybridValue("why_dn_factor", FACTOR), 0f, 2f);
        final float lumaRevert = clamp(PreferenceKeys.hybridValue("why_dn_luma_revert", LUMA_REVERT), 0f, 1f);
        // P648: with «Подстройка под свет» the night's flat-area smoothing follows the shot's SNR (PostSnr; none by day, nor off)
        final boolean adapt = PostSnr.on();
        final float motionRevert = clamp(PreferenceKeys.hybridValue("why_dn_motion_revert", MOTION_REVERT), 0f, 1f);
        final float grain = clamp(PreferenceKeys.hybridValue("why_dn_grain", GRAIN), 0f, 2f);
        final StringBuilder log = new StringBuilder("why-resolve grid=").append(s).append('x');
        final List<GLTexture> owned = new ArrayList<>();
        DenoiseStats.PendingSnr snr = null;
        try {
            // P479 (speed): the SNR's statistics issued now, their read-back and CPU part after this node's passes are issued
            // (only WhyLumaSharpen and WhyCombine use the SNR): no GPU drain here, the CPU part runs alongside the passes
            // OFF by default (owner, 2026-11-03): on the Snapdragon 8 Elite (Adreno 830) photos came out with only the last resolve
            // strip (the first two thirds black); the read-back with the wait as before
            snr = DenoiseStats.finishSnrBegin(glProg, pipeline, input,
                    !com.scamera.app.processing.opengl.PostGlMode.legacy() && PreferenceKeys.scamDevSwitch("snr_async", false));
            com.scamera.app.processing.opengl.PostGlMode.lap("resolve finishSnr");
            // P648: the switch on - the SNR finished before the passes (the flat smoothing reads it and the measured noise; with
            // snr_async the read-back's wait moves here); off - after the passes as before
            float shotSnr = Float.NaN;
            if (adapt) {
                shotSnr = DenoiseStats.finishSnrEnd(snr, log);
                snr = null;
            }
            // P648c: the night's flat-area smoothing (only where the merged luma is flat against its measured noise; 0: none)
            final float flatNight = PostSnr.flatNight(shotSnr, adapt, par.finishNoiseS, par.finishNoiseO);

            // ---- the uniforms of the reference app's resolve
            final float[] so = shotNoise(par);
            final float[] model = noiseModel(so[0], so[1]);
            final float boost = vignetteBoost(so[0], so[1]);
            final int normals = Math.max(1, ScamHybridBurst.lastNormals);
            final float median = ScamHybridBurst.lastEffectiveMedian;
            final float frames = defaultFrames(median, normals);
            final java.nio.ByteBuffer eff = ScamHybridBurst.lastEffectiveFrames;
            final float effScale = WhyMsDenoise.effScale(median);
            GLTexture effMap = null;
            if (eff != null && !Float.isNaN(effScale) && eff.capacity() == (long) size.x * size.y) {
                effMap = DenoiseStats.effectiveFramesTexture(size, eff);
                owned.add(effMap);
            }
            // P317 (Mali): the usampler2D always gets a unit of its own; the 1 x 1 placeholder is never read (useEffU 0)
            final GLTexture effTex = effMap != null ? effMap : tex(owned, new Point(1, 1), GLFormat.DataType.UNSIGNED_8, 1, GL_NEAREST);

            com.scamera.app.processing.opengl.PostGlMode.lap("resolve effmap");
            final int rows = stripRows(size.x, size.y);
            final Point tile = new Point(size.x, Math.min(size.y, rows + 14));
            final GLTexture p0 = tex(owned, tile, GLFormat.DataType.FLOAT_16, 4, GL_NEAREST);
            final GLTexture p1 = tex(owned, tile, GLFormat.DataType.FLOAT_16, 4, GL_NEAREST);
            // Texture construction can change sampler bindings; take the final output before binding any.
            final GLTexture out = pipeline.getMain();
            for (int y = 0; y < size.y; y += rows) {
                final int count = Math.min(rows, size.y - y);
                final int origin = Math.max(0, y - 7);
                final int height = Math.min(size.y, y + count + 7) - origin;
                GLTexture source = input;
                for (int pass = 0; pass < 3; pass++) {
                    final GLTexture target = pass == 0 ? p0 : pass == 1 ? p1 : out;
                    glProg.useAssetProgram("scamdn/why_resolve", false);
                    glProg.setTexture("InputBuffer", source);
                    glProg.setTexture("EffMap", effTex);
                    glProg.setVar("useEffU", effMap != null ? 1 : 0);
                    glProg.setVar("effScaleU", Float.isNaN(effScale) ? 0f : effScale);
                    glProg.setVar("framesU", frames);
                    glProg.setVar("totalU", (float) normals);
                    glProg.setVar("fullSizeU", size.x, size.y);
                    glProg.setVar("sensorSizeU", sensorSize.x, sensorSize.y);
                    glProg.setVar("sensorOriginU", sensorX, sensorY);
                    glProg.setVar("inputOriginU", pass == 0 ? 0 : origin);
                    glProg.setVar("outputOriginU", pass == 2 ? 0 : origin);
                    glProg.setVar("passU", pass);
                    glProg.setVar("stepU", 1 << pass);
                    glProg.setVar("noiseModelU", model[0], model[1], model[2], model[3]);
                    glProg.setVar("factorU", factor);
                    glProg.setVar("flatNightU", flatNight);
                    glProg.setVar("flatBoostU", PostSnr.FLAT_BOOST);
                    glProg.setVar("flatGrainU", PostSnr.FLAT_GRAIN);
                    glProg.setVar("flatNoiseU", flatNight > 0f ? par.finishNoiseS : 0f, flatNight > 0f ? par.finishNoiseO : 0f);
                    glProg.setVar("outlierU", OUTLIER);
                    glProg.setVar("rejectRevertU", motionRevert);
                    glProg.setVar("globalRevertU", lumaRevert);
                    glProg.setVar("grainU", grain);
                    glProg.setVar("vignetteBoostU", boost);
                    glProg.setVar("vignetteScaleU", 1f);
                    if (pass < 2) glProg.drawBlocks(target, new Point(size.x, height));
                    else drawRegion(glProg, target, y, count);
                    source = target;
                }
            }
            com.scamera.app.processing.opengl.PostGlMode.lap("resolve passes");
            if (snr != null) DenoiseStats.finishSnrEnd(snr, log);
            log.append(" factor=").append(factor).append(" flat=").append(flatNight).append(" revert=").append(lumaRevert).append('/').append(motionRevert)
               .append(" grain=").append(grain).append(" S=").append(so[0]).append(" O=").append(so[1]).append(" vignette=").append(boost)
               .append(" rows=").append(rows).append(" N=").append(effMap != null ? "map(x" + effScale + ")" : "flat").append(" default=").append(frames)
               .append(" total=").append(normals)
               .append(" ms=").append((System.nanoTime() - started) / 1_000_000);
            Log.i("SCAM_PIPELINE", log.toString());
            return out;
        } finally {
            for (GLTexture t : owned) if (t != null) t.close();
            owned.clear();
            if (snr != null) DenoiseStats.dropPending(snr); // a failure between the two halves: its pixel-pack buffer
        }
    }

    static int stripRows(int width, int height) {
        final long budget = 128L << 20;
        return Math.max(1, Math.min(height, (int) Math.max(1L, budget / (Math.max(1L, width) * 16L) - 14L)));
    }

    private static void drawRegion(GLProg prog, GLTexture target, int y, int height) {
        target.renderInto();
        for (int row = 0; row < height; row += 256) {
            android.opengl.GLES30.glViewport(0, y + row, target.size.x, Math.min(256, height - row));
            prog.draw();
            com.scamera.app.processing.opengl.GpuPace.block(); // P616: a submission per block while paced
        }
        android.opengl.GLES30.glFlush();
    }

    private static GLTexture tex(List<GLTexture> owned, Point size, GLFormat.DataType type, int channels, int filter) {
        final GLTexture t = new GLTexture(size, new GLFormat(type, channels), null, filter, GL_CLAMP_TO_EDGE);
        owned.add(t);
        return t;
    }

    private static void release(List<GLTexture> owned, GLTexture t) {
        if (t != null && owned.remove(t)) t.close();
    }
}
