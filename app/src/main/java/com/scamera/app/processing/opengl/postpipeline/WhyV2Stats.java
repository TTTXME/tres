package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.util.BufferUtils;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * P614 «Why тон» V2, step 1 (owner, 2026-11-11: the reference tone one to one, V1 ours, V2 the reference's): the clean linear RGB
 * of the shot (why/v2_clean: our white balance, lens shading and colour matrix, as V1 reads them), the reference tone's histogram
 * samples (histogram_cs: every u_stride-th pixel, why/v2_stride, counted on the CPU exactly) and its exposure and fusion strengths
 * (WhyV2Ae). Hands {@link State} to WhyV2Fusion / WhyV2Tone, and to WhyLumaSharpen a WhyTone.State with the exposure (the
 * sharpening is ours, untouched). The image passes through.
 */
public final class WhyV2Stats extends Node {
    public WhyV2Stats() { super("", "WhyV2Stats"); }
    @Override public void Compile() {}

    /** What the V2 nodes hand on (PostPipeline.whyV2). */
    public static final class State {
        public final WhyV2Ae.Params params = settings();
        public WhyV2Ae.Result ae;
        /** The clean linear RGB (RGBA16F, the output grid). */
        public GLTexture clean;
        /** The fused display luma (R16F, level 0 of the pyramid: the grid or half of it, {@link #fusedScale}). */
        public GLTexture fused;
        public int fusedScale = 1;

        void close() {
            if (clean != null) clean.close();
            if (fused != null) fused.close();
            clean = fused = null;
        }
    }

    /**
     * The clip ceiling of the reference's statistics and tone (u_clip_ceiling): 1 without short frames in the merge, with them its
     * highlight recovery factor itself ({@link #MAX_CEILING}, the owner's "8"; not the data's maximum). Here the merge's Bento
     * headroom (our ultrashort frames) stands for its short frames; {@code max} (the samples' maximum) is only logged.
     */
    static float clipCeiling(boolean bento, float max) {
        return bento ? MAX_CEILING : 1f;
    }

    /**
     * Whether the merge fused short frames (the reference's ceiling condition): the Bento route's «HYBRID BENTO: applied»
     * (ScamHybridBurst.lastBentoApplied) or the fast route's valid short frames («HYBRID FAST MERGE HIGHLIGHT: accumulated validity
     * of N valid short frame(s)», N > 0), which lastBentoApplied does not see.
     */
    static boolean shortFramesMerged(boolean bentoApplied, String report) {
        if (bentoApplied) return true;
        if (report == null) return false;
        final java.util.regex.Matcher m = FAST_SHORTS.matcher(report);
        while (m.find()) if (Integer.parseInt(m.group(1)) > 0) return true;
        return false;
    }

    private static final java.util.regex.Pattern FAST_SHORTS =
            java.util.regex.Pattern.compile("HYBRID FAST MERGE HIGHLIGHT: accumulated validity of (\\d+) valid short frame");

    /** The reference's highlight recovery factor (pref_highlight_recovery_factor, 8 in the owner's configuration). */
    static final float MAX_CEILING = 8f;

    /**
     * P614: V2's tone settings, defaults the owner's configuration of the reference as stored, else its native defaults
     * (WhyV2Ae.Params). Owner, 2026-11-11 («в этом меню можно было настраивать V1 и V2»): V2 reads V1's row wherever the two have
     * the same setting in the same units ({@link #MERGED}: «Настройки изображения», module-local as every pref_ row); the five of
     * its own are pref_scam_hybrid_why_v2_*; the tone operator is V1's three switches (the own curve, Uchimura, ACES, else AgX).
     * A merged row read at V1's decimal default (0.15, 0.9, ...) is the owner's float of it ({@link #owner}). The short frame
     * denoise follows «Все шумодавы» as V1's. Table: research/handoff/PLAN.md P614.
     */
    static WhyV2Ae.Params settings() {
        final WhyV2Ae.Params p = new WhyV2Ae.Params();
        p.aeTarget = owner(WhyTone.value("ae_target", p.aeTarget), p.aeTarget);
        p.maxBoost = WhyTone.value("ae_max_boost", p.maxBoost);
        p.minLimit = WhyTone.value("ae_min_limit", p.minLimit);
        p.brightThresh = owner(WhyTone.value("bright_thresh", p.brightThresh), p.brightThresh);
        p.hlBoost = WhyTone.value("hl_boost", p.hlBoost);
        p.hlOverflow = WhyTone.value("hl_overflow", p.hlOverflow);
        p.hlBlend = WhyTone.value("hl_blend", p.hlBlend);
        p.darkThresh = WhyTone.value("dark_thresh", p.darkThresh);
        p.darkPixelThresh = WhyTone.value("dark_pixel_thresh", p.darkPixelThresh);
        p.contrastBoost = WhyTone.value("contrast_boost", p.contrastBoost);
        p.nightDim = owner(WhyTone.value("night_thresh", p.nightDim), p.nightDim);
        p.highlightStr = owner(WhyTone.value("highlight_str", p.highlightStr), p.highlightStr);
        p.shadowStr = WhyTone.value("shadow_str", p.shadowStr);
        p.weightExtHl = WhyTone.value("classic_weight_ext_hl", p.weightExtHl);
        p.weightHl = WhyTone.value("classic_weight_hl", p.weightHl);
        p.weightMid = WhyTone.value("classic_weight_mid", p.weightMid);
        p.weightShadow = WhyTone.value("classic_weight_shadow", p.weightShadow);
        p.weightCenter = WhyTone.value("classic_weight_center", p.weightCenter);
        p.blendSmoothness = owner(WhyTone.value("blend_smoothness", p.blendSmoothness), p.blendSmoothness);
        p.macroContrast = WhyTone.value("macro_contrast", p.macroContrast);
        p.clarity = WhyTone.value("v2_clarity", p.clarity);
        p.toneOperator = toneOperator();
        p.gammaType = Math.round(WhyTone.value("gamma_type", p.gammaType));
        p.gamma = WhyTone.value("gamma", p.gamma);
        p.filmToe = WhyTone.value("film_toe", p.filmToe);
        p.agxLook = Math.round(WhyTone.value("v2_agx_look", p.agxLook));
        p.agxSlope = WhyTone.value("agx_slope", p.agxSlope);
        p.agxSp = WhyTone.value("agx_sp", p.agxSp);
        p.agxTp = WhyTone.value("agx_tp", p.agxTp);
        p.agxMinEv = WhyTone.value("agx_min_ev", p.agxMinEv);
        p.agxMaxEv = WhyTone.value("agx_max_ev", p.agxMaxEv);
        p.agxSat = WhyTone.value("agx_sat", p.agxSat);
        p.agxEv = WhyTone.value("agx_ev", p.agxEv);
        p.agxInset = WhyTone.value("agx_inset", p.agxInset);
        p.agxHueLock = PreferenceKeys.hybridSwitch("why_v2_agx_hue_lock", p.agxHueLock);
        p.acesToe = WhyTone.value("aces_toe", p.acesToe);
        p.acesPreGain = WhyTone.value("aces_pre_gain", p.acesPreGain);
        p.acesA = WhyTone.value("aces_a", p.acesA);
        p.acesD = WhyTone.value("aces_d", p.acesD);
        p.uchiPreGain = WhyTone.value("uchi_pre_gain", p.uchiPreGain);
        p.uchiPostGain = WhyTone.value("uchi_post_gain", p.uchiPostGain);
        p.uchiP = WhyTone.value("uchi_p", p.uchiP);
        p.uchiA = WhyTone.value("uchi_a", p.uchiA);
        p.uchiM = WhyTone.value("uchi_m", p.uchiM);
        p.uchiL = WhyTone.value("uchi_l", p.uchiL);
        p.uchiC = WhyTone.value("uchi_c", p.uchiC);
        p.uchiB = WhyTone.value("uchi_b", p.uchiB);
        p.curvePoints = PreferenceKeys.hybridString("why_curve_points", p.curvePoints);
        p.curveMods[0] = WhyTone.value("curve_lift", p.curveMods[0]);
        p.curveMods[1] = WhyTone.value("curve_contrast", p.curveMods[1]);
        p.curveMods[2] = WhyTone.value("curve_shadow_contrast", p.curveMods[2]);
        p.curveMods[3] = WhyTone.value("curve_highlight_contrast", p.curveMods[3]);
        p.curveMods[4] = WhyTone.value("curve_deep_shadows", p.curveMods[4]);
        p.curveMods[5] = WhyTone.value("curve_extreme_highlights", p.curveMods[5]);
        p.curveMods[6] = WhyTone.value("curve_offset", p.curveMods[6]);
        p.vibStrength = WhyTone.value("vibrance", p.vibStrength);
        p.vibSaturated = WhyTone.value("vibrance_saturated", p.vibSaturated);
        p.vibSky = WhyTone.value("vibrance_sky", p.vibSky);
        p.vibGreen = WhyTone.value("vibrance_green", p.vibGreen);
        p.vibHl = WhyTone.value("vibrance_hl_str", p.vibHl);
        p.vibMid = WhyTone.value("vibrance_mid", p.vibMid);
        p.vibShadow = WhyTone.value("vibrance_shadows", p.vibShadow);
        p.skinProtect = WhyTone.value("skin_protect", p.skinProtect);
        p.skinHueShift = WhyTone.value("skin_hue_shift", p.skinHueShift);
        p.skinSatShift = WhyTone.value("skin_sat_shift", p.skinSatShift);
        p.skinHueCenterDeg = WhyTone.value("v2_skin_hue_center", p.skinHueCenterDeg);
        p.skinLumaMin = WhyTone.value("skin_luma_min", p.skinLumaMin);
        p.shoulderStrength = WhyTone.value("hr_strength", p.shoulderStrength);
        p.shoulderKnee = WhyTone.value("hr_knee", p.shoulderKnee);
        p.shoulderHeadroom = WhyTone.value("hr_room", p.shoulderHeadroom);
        p.bracketDenoise = DenoiseStages.on() ? WhyTone.value("bracket_denoise", p.bracketDenoise) : 0f;
        p.hrAmount = WhyTone.value("v2_hr_amount", p.hrAmount);
        return p;
    }

    /**
     * The merged rows: V2's setting -> V1's row (after pref_scam_hybrid_why_). tools/check_why_v2_tone.py and WhyV2ToneTest read it.
     */
    static final String[][] MERGED = {
            {"ae_target", "ae_target"}, {"max_boost", "ae_max_boost"}, {"min_limit", "ae_min_limit"}, {"bright_thresh", "bright_thresh"},
            {"hl_boost", "hl_boost"}, {"hl_overflow", "hl_overflow"}, {"hl_blend", "hl_blend"}, {"dark_thresh", "dark_thresh"},
            {"dark_pixel_thresh", "dark_pixel_thresh"}, {"contrast_boost", "contrast_boost"}, {"night_dim", "night_thresh"}, {"highlight_str", "highlight_str"},
            {"shadow_str", "shadow_str"}, {"weight_ext_hl", "classic_weight_ext_hl"}, {"weight_hl", "classic_weight_hl"}, {"weight_mid", "classic_weight_mid"},
            {"weight_shadow", "classic_weight_shadow"}, {"weight_center", "classic_weight_center"}, {"blend_smoothness", "blend_smoothness"}, {"macro_contrast", "macro_contrast"},
            {"gamma_type", "gamma_type"}, {"gamma", "gamma"}, {"film_toe", "film_toe"}, {"agx_slope", "agx_slope"},
            {"agx_sp", "agx_sp"}, {"agx_tp", "agx_tp"}, {"agx_min_ev", "agx_min_ev"}, {"agx_max_ev", "agx_max_ev"},
            {"agx_sat", "agx_sat"}, {"agx_ev", "agx_ev"}, {"agx_inset", "agx_inset"}, {"aces_toe", "aces_toe"},
            {"aces_pre_gain", "aces_pre_gain"}, {"aces_a", "aces_a"}, {"aces_d", "aces_d"}, {"uchi_pre_gain", "uchi_pre_gain"},
            {"uchi_post_gain", "uchi_post_gain"}, {"uchi_p", "uchi_p"}, {"uchi_a", "uchi_a"}, {"uchi_m", "uchi_m"},
            {"uchi_l", "uchi_l"}, {"uchi_c", "uchi_c"}, {"uchi_b", "uchi_b"}, {"curve_points", "curve_points"},
            {"curve_lift", "curve_lift"}, {"curve_contrast", "curve_contrast"}, {"curve_shadow_contrast", "curve_shadow_contrast"}, {"curve_highlight_contrast", "curve_highlight_contrast"},
            {"curve_deep_shadows", "curve_deep_shadows"}, {"curve_extreme_highlights", "curve_extreme_highlights"}, {"curve_offset", "curve_offset"}, {"vibrance", "vibrance"},
            {"vibrance_saturated", "vibrance_saturated"}, {"vibrance_sky", "vibrance_sky"}, {"vibrance_green", "vibrance_green"}, {"vibrance_hl", "vibrance_hl_str"},
            {"vibrance_mid", "vibrance_mid"}, {"vibrance_shadow", "vibrance_shadows"}, {"skin_protect", "skin_protect"}, {"skin_hue_shift", "skin_hue_shift"},
            {"skin_sat_shift", "skin_sat_shift"}, {"skin_luma_min", "skin_luma_min"}, {"shoulder_strength", "hr_strength"}, {"shoulder_knee", "hr_knee"},
            {"shoulder_headroom", "hr_room"}, {"bracket_denoise", "bracket_denoise"}
    };

    /** V2's own rows (pref_scam_hybrid_why_v2_*): the settings V1 has not, or has with another meaning or default. */
    static final String[] OWN = {"agx_look", "agx_hue_lock", "clarity", "skin_hue_center", "hr_amount"};

    /** The tone operator of V1's switches (WhyTone.toneOperator: the own curve first, then Uchimura, ACES; else AgX). */
    static int toneOperator() {
        if (PreferenceKeys.hybridSwitch("why_curve_on", true)) return 3;
        if (PreferenceKeys.hybridSwitch("why_uchi_on", false)) return 2;
        if (PreferenceKeys.hybridSwitch("why_aces_on", false)) return 1;
        return 0;
    }

    /**
     * A value of a merged row at V1's decimal default is the owner's float of the same number ({@code owner}: 0.14999999 for
     * 0.15): within two ulps of it, that float, else the value as stored.
     */
    static float owner(float value, float owner) {
        return Math.abs(value - owner) <= 2f * Math.ulp(owner) ? owner : value;
    }


    /** The noise the reference's AE reads (NoiseModeler.reportedMean; its fallback without a model). */
    static float[] referenceNoise(com.scamera.app.processing.render.NoiseModeler modeler) {
        return modeler != null ? modeler.reportedMean()
                : com.scamera.app.processing.render.NoiseModeler.reportedMean(null);
    }

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        glProg.closed = true;
        final long started = System.currentTimeMillis();
        // WhyLumaSharpen's state (the exposure, the input scale); dropped, not closed, if an earlier shot left one (as WhyStats)
        final WhyTone.State st = new WhyTone.State();
        pipeline.why = st;
        st.factor = WhyTone.lowFactor(pipeline.mParameters);
        st.inScale = (float) Math.pow(2.0, WhyTone.value("input_ev", 0f));
        final State v2 = new State();
        pipeline.whyV2 = v2;
        final Point size = WorkingTexture.mSize;
        GLTexture gainMap = pipeline.GainMap;
        GLTexture fallback = null;
        if (gainMap == null) {
            fallback = new GLTexture(new Point(1, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                    BufferUtils.getFrom(new float[]{1f, 1f, 1f, 1f}), GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        GLTexture samples = null;
        try {
            // LINEAR as the reference's RGB texture (hr_extract reads it bilinear at half the grid; the rest texelFetch)
            v2.clean = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("why/v2_clean", false);
            glProg.setTexture("InputBuffer", WorkingTexture);
            WhyTone.setColour(glProg, pipeline, gainMap, st.inScale);
            glProg.drawBlocks(v2.clean);
            final int stride = WhyV2Ae.stride(size.x, size.y);
            final int sw = WhyV2Ae.samples(size.x, stride), sh = WhyV2Ae.samples(size.y, stride);
            samples = new GLTexture(new Point(sw, sh), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("why/v2_stride", false);
            glProg.setTexture("u_tex_rgb", v2.clean);
            glProg.setVar("u_stride", stride);
            glProg.drawBlocks(samples);
            glProg.closed = true;
            samples.BufferLoad();
            final ByteBuffer px = samples.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_16, 4));
            px.order(ByteOrder.nativeOrder());
            final FloatBuffer f = px.asFloatBuffer();
            // the data's maximum (logged), then the histograms at the ceiling
            float max = 0f;
            for (int i = 0; i < sw * sh; i++) {
                for (int c = 0; c < 3; c++) {
                    final float v = f.get(i * 4 + c);
                    if (v > max && Float.isFinite(v)) max = v;
                }
            }
            final boolean shorts = shortFramesMerged(ScamHybridBurst.lastBentoApplied, ScamHybridBurst.lastReport);
            final float ceiling = clipCeiling(shorts, max);
            final float clip = WhyV2Ae.clipCeiling(ceiling);
            // no face: the reference's photo path never fills its AE's face rect (u_has_face 0; setSnapshotPortraitData carries
            // only the portrait flag, aperture and depth), so its face priority never meters a photo
            final WhyV2Ae.Hist h = new WhyV2Ae.Hist();
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    final int o = (y * sw + x) * 4;
                    WhyV2Ae.add(h, f.get(o), f.get(o + 1), f.get(o + 2), clip, false);
                }
            }
            // the reference's AE noise: the one-frame mean of the camera's noise profile (not our stacked model, pipeline.noiseS / O)
            final float[] noise = referenceNoise(pipeline.mParameters.noiseModeler);
            final WhyV2Ae.Result r = WhyV2Ae.finish(h, v2.params, ceiling, noise[0], noise[1]);
            v2.ae = r;
            final WhyAe.Result shim = new WhyAe.Result();
            shim.ae = r.ae;
            shim.clip = r.clip;
            st.ae = shim;
            pipeline.linearDisplayGain = r.ae;
            Log.i("SCAM_PIPELINE", "WHY V2 stats " + r.describe() + " stride=" + stride + " samples=" + sw + "x" + sh
                    + " max=" + max + " bento=" + ScamHybridBurst.lastBentoApplied + " shorts=" + shorts + " noise=" + noise[0] + "/" + noise[1]
                    + " (stacked model " + pipeline.noiseS + "/" + pipeline.noiseO + ")"
                    + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            if (samples != null) samples.close();
            if (fallback != null) fallback.close();
        }
    }
}
