package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * P443 (owner, 2026-11-01: «Исправь шумодав, сделай его максимально схожим с шумодавом Арк», the night port 230628: the quay wall,
 * the fenders' chains and the hull at the waterline had much less fine detail than the reference app's photo): the reference app's
 * resolve denoise ("denoise A" of its 2.10 APK, shader s032, ARK_DENOISE.md 1.1) as the SCAM Hybrid's finish denoise, the default
 * engine («Движок шумодава»: ArkCam resolve; «SCAM 9.6» = {@link ScamDenoise}, unchanged). With «Пирамидальный шумодав»
 * ({@link WhyMsDenoise}, its denoise B) after it, the chain is the reference app's.
 * <p>
 * scamdn/ark_resolve: three a-trous passes (1, 2, 4 px) of a pair-symmetric 3x3 bilateral in GCam YUV, range sigma^2 = factor x the
 * camera model's variance / N (N = the pixel's merged frames), the colour pulled to a luma-guided 3x3 mean, the luma put back to
 * 85 % (static) / 50 % (motion) of its input, grain where fewer than 5 frames merged and the SNR-gated corner boost. On a static
 * pixel of a 20-frame night merge it removes ~10-15 % of the luma noise and none of the texture (ScamDenoise's coarse bands kept
 * 0-12 % of theirs).
 * <p>
 * The reference app's inputs, here:
 * <ul>
 * <li>domain: the merged, white-balanced linear camera RGB before the colour matrix and the lens-shading gain (its accumulators x the
 *     white-balance gains); here ScamRgb's output, the same domain, with the colour part of the shading (normalised to mean 1) already
 *     applied, the luminance part after this node as there. ScamRgb's highlight recovery replaces the RawTherapee blend of its pass 0.</li>
 * <li>noise model (0.562 S, 0.25 S, 0.25 S, O): S, O = the green pair of the camera's one-frame profile of the normalised RAW (the
 *     model ScamDenoise and the AE read: Parameters.noiseModeler.baseModel[1]) x the worker's measured factor (lastNoiseFactor, not
 *     on a DCG shot's zones); without a model its fallback 1.5e-4 / 1e-5. Like the reference app, not scaled by the white balance.</li>
 * <li>N: the worker's effective-frame map (the noise-effective frames of each pixel's weighted mean; code 64 = its median coverage
 *     lastEffectiveMedian), code 0 (unknown) and no map: the median, else 0.55 x the merged normals (WhyNoiseClamp.EXPECTED_SHARE);
 *     clamped to 1 .. lastNormals (u_total_frames). The motion fallback ramp's reference is that median: the reference app's static
 *     pixel has N = its frame total (a weight sum), ours a noise-effective count ~0.55 of it, and with the frame total there every
 *     static pixel would have taken the motion revert.</li>
 * <li>vignette_strength_boost from the one-frame SNR 0.18 / sqrt(0.18 S + O): 4 at SNR 1, 0 from 4; spatial scale 1.</li>
 * <li>«Удаление одиночных точек» (chromadn/despeckle) runs first, in the place of the reference app's hot-pixel filter of its RAW.</li>
 * </ul>
 * A 200 MP shot keeps {@link ScamDenoise} (its 2x2-means path, P306: two full-grid RGBA16F ping-pong textures would be 3.2 GB).
 * The finish SNR (the sharpening and the detail gain read it) is measured as ScamDenoise measures it ({@link ScamDenoise#finishSnr}).
 * The final pass's additional luma-guided shadow colour filter addresses the low-frequency colour mottle seen on actual room/night
 * replays; it changes chroma only, is gated by local signal-to-noise and level, and can be disabled with hybrid_ark_dn_dark_chroma.
 */
public final class ArkResolveDenoise {
    private ArkResolveDenoise() {}

    /** The reference app's defaults (ParamRegistry): sabre_denoising_factor, global / reject luma revert, grain strength. */
    static final float FACTOR = 0.4f, LUMA_REVERT = 0.85f, MOTION_REVERT = 0.5f, GRAIN = 0.15f;
    /** outlier_sigma_multiplier: a constant of the reference app's resolve. */
    static final float OUTLIER = 0.125f;
    /** Its noise model without a camera profile (frame meta +0x98 / +0x9c fallback). */
    static final float FALLBACK_S = 1.5e-4f, FALLBACK_O = 1e-5f;

    /** «Движок шумодава» (pref_scam_hybrid_denoise_engine): "ark" (default) or "scam"; scam_dev "hybrid_denoise_engine 1" = scam. */
    static boolean engineArk() {
        return !"scam".equals(PreferenceKeys.hybridString("denoise_engine", "ark"));
    }

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
     * WhyNoiseClamp.EXPECTED_SHARE x the merged normals; within 1 .. normals.
     */
    static float defaultFrames(float median, int normals) {
        final int total = Math.max(1, normals);
        final float n = median > 0f && Float.isFinite(median) ? median : WhyNoiseClamp.EXPECTED_SHARE * total;
        return Math.max(1f, Math.min(total, n));
    }

    /** {S, O} of the shot (see the class comment). */
    static float[] shotNoise(Parameters par) {
        if (par == null || par.noiseModeler == null || par.noiseModeler.baseModel == null || par.noiseModeler.baseModel.length < 2
                || par.noiseModeler.baseModel[1] == null) return new float[]{FALLBACK_S, FALLBACK_O};
        float s = (float) Math.max(1e-9, par.noiseModeler.baseModel[1].first), o = (float) Math.max(1e-12, par.noiseModeler.baseModel[1].second);
        final float k = ScamHybridBurst.lastNoiseFactor;
        if (k != 1f && ScamDenoise.dcgZones(par) == null) { s *= k; o *= k; }
        return new float[]{s, o};
    }

    private static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    /** Set once {@link #process} took the pipeline's output texture (ScamHdrDenoise turns the cursor back on a failure after it). */
    static volatile boolean lastMainTaken;

    /** The whole denoise; returns the output texture (a pipeline main texture, max(0, RGB)). */
    static GLTexture process(GLProg glProg, PostPipeline pipeline, GLTexture input) {
        final long started = System.nanoTime();
        lastMainTaken = false;
        final Parameters par = pipeline.mParameters;
        final Point size = input.mSize;
        final int s = Math.max(1, Math.min(2, Math.round(par.outputScale)));
        final float factor = clamp(PreferenceKeys.hybridValue("ark_dn_factor", FACTOR), 0f, 2f);
        final float lumaRevert = clamp(PreferenceKeys.hybridValue("ark_dn_luma_revert", LUMA_REVERT), 0f, 1f);
        final float motionRevert = clamp(PreferenceKeys.hybridValue("ark_dn_motion_revert", MOTION_REVERT), 0f, 1f);
        final float grain = clamp(PreferenceKeys.hybridValue("ark_dn_grain", GRAIN), 0f, 2f);
        final boolean darkChroma = PreferenceKeys.hybridSwitch("ark_dn_dark_chroma", true);
        final boolean despeckle = PreferenceKeys.hybridSwitch("despeckle", true);
        final StringBuilder log = new StringBuilder("ark-resolve grid=").append(s).append('x');
        final List<GLTexture> owned = new ArrayList<>();
        try {
            // ---- despeckle (the reference app's hot-pixel filter runs on its RAW): the noise sigma of u = sqrt(Y + c) first
            final GLTexture noisy = tex(owned, size, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
            glProg.useAssetProgram("chromadn/luma", false);
            glProg.setTexture("InputBuffer", input);
            glProg.setVar("offsetC", 0.008f);
            glProg.setVar("signedU", 0);
            glProg.drawBlocks(noisy);
            final float sigmaU = ScamHdrDenoise.estimateNoise(glProg, noisy, s);
            pipeline.scamNoiseSigma = sigmaU;
            release(owned, noisy);
            GLTexture clean = input;
            if (despeckle) {
                clean = tex(owned, size, GLFormat.DataType.FLOAT_16, 4, GL_NEAREST);
                glProg.useAssetProgram("chromadn/despeckle", false);
                glProg.setTexture("InputBuffer", input);
                glProg.setVar("sigma", sigmaU);
                glProg.setVar("offsetC", 0.008f);
                glProg.setVar("pxStepU", s);
                glProg.drawBlocks(clean);
            }
            // ---- the finish SNR and the sports policy, as ScamDenoise measures them
            ScamDenoise.finishSnr(glProg, pipeline, clean, log);

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
                effMap = ScamDenoise.effectiveFramesTexture(size, eff);
                owned.add(effMap);
            }
            // P317 (Mali): the usampler2D always gets a unit of its own; the 1 x 1 placeholder is never read (useEffU 0)
            final GLTexture effTex = effMap != null ? effMap : tex(owned, new Point(1, 1), GLFormat.DataType.UNSIGNED_8, 1, GL_NEAREST);

            // ---- three passes: 1 px (RGB -> YUV), 2 px, 4 px (-> max(0, RGB)) into the pipeline's output
            final GLTexture p0 = tex(owned, size, GLFormat.DataType.FLOAT_16, 4, GL_NEAREST);
            final GLTexture p1 = tex(owned, size, GLFormat.DataType.FLOAT_16, 4, GL_NEAREST);
            GLTexture source = clean;
            GLTexture out = null;
            for (int pass = 0; pass < 3; pass++) {
                GLTexture target;
                if (pass == 2) {
                    // the output (a main) is taken before the samplers are bound: a new texture binds itself on unit 1 + its name
                    target = pipeline.getMain();
                    lastMainTaken = true;
                    out = target;
                } else target = pass == 0 ? p0 : p1;
                glProg.useAssetProgram("scamdn/ark_resolve", false);
                glProg.setTexture("InputBuffer", source);
                glProg.setTexture("EffMap", effTex);
                glProg.setVar("useEffU", effMap != null ? 1 : 0);
                glProg.setVar("effScaleU", Float.isNaN(effScale) ? 0f : effScale);
                glProg.setVar("framesU", frames);
                glProg.setVar("totalU", (float) normals);
                glProg.setVar("refU", frames);
                glProg.setVar("passU", pass);
                glProg.setVar("stepU", 1 << pass);
                glProg.setVar("noiseModelU", model[0], model[1], model[2], model[3]);
                glProg.setVar("factorU", factor);
                glProg.setVar("outlierU", OUTLIER);
                glProg.setVar("rejectRevertU", motionRevert);
                glProg.setVar("globalRevertU", lumaRevert);
                glProg.setVar("grainU", grain);
                glProg.setVar("darkChromaU", darkChroma ? 1f : 0f);
                glProg.setVar("vignetteBoostU", boost);
                glProg.setVar("vignetteScaleU", 1f);
                glProg.drawBlocks(target);
                if (pass == 0 && clean != input) release(owned, clean);
                source = target;
            }
            log.append(" factor=").append(factor).append(" revert=").append(lumaRevert).append('/').append(motionRevert)
               .append(" grain=").append(grain).append(" S=").append(so[0]).append(" O=").append(so[1]).append(" vignette=").append(boost)
               .append(" darkChroma=").append(darkChroma).append(" N=").append(effMap != null ? "map(x" + effScale + ")" : "flat").append(" default=").append(frames)
               .append(" total=").append(normals).append(" despeckle=").append(despeckle).append(" sigmaU=").append(sigmaU)
               .append(" ms=").append((System.nanoTime() - started) / 1_000_000);
            Log.i("SCAM_PIPELINE", log.toString());
            return out;
        } finally {
            for (GLTexture t : owned) if (t != null) t.close();
            owned.clear();
        }
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
