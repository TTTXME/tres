package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.util.BufferUtils;
import com.scamera.app.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P614 «Why тон» V2, the last step: the reference tone's tonemap_final (why/v2_tonemap.glsl) on the clean RGB with the fused luma
 * of WhyV2Fusion and our sharpened luminance (WhyLumaSharpen) as its detail luma, into the pipeline's main texture - the display-
 * encoded photo, as WhyCombine's. Our «Плёночный LUT» takes the place of the reference's own 3D LUT (FILM_LUT), «Цветовое
 * пространство» Display P3 writes P3 primaries (P3_OUT). The own curve: the reference configuration's points, 1024 samples in an
 * R16F 1024 x 1 texture, LINEAR, read by texture() (the reference's glTexImage2D GL_R16F of its float table). An 8-bit output
 * (not the 10-bit HEIC / AVIF) gets the values the reference's RGBA8 target stores (QUANT8).
 */
public final class WhyV2Tone extends Node {
    public WhyV2Tone() { super("", "WhyV2Tone"); }
    @Override public void bindShader() {}

    /** The reference's own curve table (a 1024-float vector uploaded as 1024 x 1). */
    static final int CURVE_SAMPLES = 1024;

    /** The own curve's samples as the reference builds them (WhyV2Curve: its points, its seven modifiers, generateLut). */
    static float[] curveSamples(WhyV2Ae.Params p) {
        return WhyV2Curve.samples(p.curvePoints, p.curveMods, CURVE_SAMPLES);
    }

    @Override
    public void runPass() {
        final PostPipeline pipeline = (PostPipeline) hostPipeline;
        final WhyV2Stats.State v2 = pipeline.whyV2;
        final WhyTone.State st = pipeline.why;
        if (v2 == null || v2.ae == null || v2.clean == null || v2.fused == null)
            throw new IllegalStateException("WHY V2: no fusion (WhyV2Stats / WhyV2Fusion did not run)");
        if (st == null || st.lumaS == null) throw new IllegalStateException("WHY V2: no sharpened luminance (WhyLumaSharpen did not run)");
        final long started = System.currentTimeMillis();
        final WhyV2Ae.Params p = v2.params;
        final WhyV2Ae.Result r = v2.ae;
        final Point size = v2.clean.size;
        // what V1's WhyCombine consumes of the merge's side data: not drawn by the reference tone, given back here
        if (pipeline.sunBloom != null) { pipeline.sunBloom.close(); pipeline.sunBloom = null; }
        if (pipeline.opposedShare != null) { pipeline.opposedShare.close(); pipeline.opposedShare = null; }
        pipeline.skyHdrRatio = null;
        pipeline.skyHdrRatioSize = null;
        // our LUT (never with a film on this route: PostPipeline takes V1 for a film of the old route)
        final com.scamera.app.processing.color.FilmChain.State chain =
                com.scamera.app.processing.color.FilmChain.current(null, pipeline.filmUserLut, pipeline.filmUserStrength);
        final float lutStrength = chain != null ? chain.strength : 0f;
        final String lutName = chain != null ? chain.key : "";
        final com.scamera.app.processing.color.FilmLut lut = lutStrength > 0f && !lutName.isEmpty()
                ? com.scamera.app.processing.color.FilmChain.lut(com.scamera.app.app.ScameraApp.getAppContext(), lutName) : null;
        int lutTexture = 0;
        GLTexture curve = null;
        try {
            // the reference: glTexImage2D(GL_R16F, 1024, 1, GL_RED, GL_FLOAT), GL_LINEAR, clamp to edge (half floats filter in ES 3.0)
            curve = new GLTexture(new Point(CURVE_SAMPLES, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 1),
                    BufferUtils.floatBuffer(curveSamples(p)), GL_LINEAR, GL_CLAMP_TO_EDGE);
            if (st.inputReleased) android.opengl.GLES30.glFinish();
            passOut = pipeline.getMain();
            if (pipeline.p3Output) shaderProg.setDefine("P3_OUT", 1);
            if (lut != null) shaderProg.setDefine("FILM_LUT", 1);
            if (v2.fusedScale > 1) shaderProg.setDefine("EF_SCALED", 1);
            if (!pipeline.tenBitOutput) shaderProg.setDefine("QUANT8", 1);
            shaderProg.useAssetProgram("why/v2_tonemap", false);
            shaderProg.setTexture("u_tex_clean_rgb", v2.clean);
            shaderProg.setTexture("u_tex_accum_ef", v2.fused);
            shaderProg.setTexture("u_tex_hires_luma", st.lumaS);
            shaderProg.setTexture("u_tex_custom_curve", curve);
            if (lut != null) {
                lutTexture = lut.upload();
                shaderProg.setTexture3D("FilmLut", lutTexture);
                shaderProg.setVar("lutU", lutStrength, (float) lut.size);
            }
            shaderProg.setVar("u_width", size.x);
            shaderProg.setVar("u_height", size.y);
            shaderProg.setVar("u_auto_exposure", r.ae);
            shaderProg.setVar("u_clip_ceiling", r.clip);
            shaderProg.setVar("u_hdr_shoulder_strength", p.shoulderStrength);
            shaderProg.setVar("u_hdr_shoulder_knee", p.shoulderKnee);
            shaderProg.setVar("u_hdr_shoulder_headroom", p.shoulderHeadroom);
            shaderProg.setVar("u_aces_toe", p.acesToe);
            shaderProg.setVar("u_tonemap_operator", p.toneOperator);
            shaderProg.setVar("u_agx_look", p.agxLook);
            shaderProg.setVar("u_agx_slope", r.agxSlope);
            shaderProg.setVar("u_agx_sp", p.agxSp);
            shaderProg.setVar("u_agx_tp", p.agxTp);
            shaderProg.setVar("u_agx_min_ev", p.agxMinEv);
            shaderProg.setVar("u_agx_max_ev", p.agxMaxEv);
            shaderProg.setVar("u_agx_sat_boost", p.agxSat);
            shaderProg.setVar("u_agx_ev", p.agxEv);
            shaderProg.setVar("u_agx_hue_lock", p.agxHueLock ? 1 : 0);
            // column-major, as the reference uploads them (glUniformMatrix3fv without transpose)
            shaderProg.setVar("u_agx_mat_inset", false, WhyV2Ae.insetMatrix(p.agxInset));
            shaderProg.setVar("u_agx_mat_outset", false, WhyV2Ae.outsetMatrix(p.agxInset));
            shaderProg.setVar("u_aces_pre_gain", p.acesPreGain);
            shaderProg.setVar("u_aces_a_coeff", p.acesA);
            shaderProg.setVar("u_aces_d_coeff", r.acesD);
            shaderProg.setVar("u_uchi_pre_gain", p.uchiPreGain);
            shaderProg.setVar("u_uchi_post_gain", p.uchiPostGain);
            shaderProg.setVar("u_uchi_p", p.uchiP);
            shaderProg.setVar("u_uchi_a", r.uchiA);
            shaderProg.setVar("u_uchi_m", p.uchiM);
            shaderProg.setVar("u_uchi_l", p.uchiL);
            shaderProg.setVar("u_uchi_c", p.uchiC);
            shaderProg.setVar("u_uchi_b", p.uchiB);
            shaderProg.setVar("u_macro_contrast", r.macroContrast);
            shaderProg.setVar("u_contrast_boost", r.contrastBoost);
            shaderProg.setVar("u_vibrance_strength", p.vibStrength);
            shaderProg.setVar("u_vibrance_saturated", p.vibSaturated);
            shaderProg.setVar("u_skin_protect_toggle", p.skinProtect);
            shaderProg.setVar("u_vibrance_sky_str", p.vibSky);
            shaderProg.setVar("u_vibrance_green_str", p.vibGreen);
            shaderProg.setVar("u_vibrance_hl_str", p.vibHl);
            shaderProg.setVar("u_vibrance_mid_str", p.vibMid);
            shaderProg.setVar("u_vibrance_shadow_str", p.vibShadow);
            shaderProg.setVar("u_skin_hue_shift", p.skinHueShift);
            shaderProg.setVar("u_skin_sat_shift", p.skinSatShift);
            shaderProg.setVar("u_skin_hue_center", p.skinHueCenterRad());
            shaderProg.setVar("u_skin_luma_min", p.skinLumaMin);
            shaderProg.setVar("u_gamma_curve_type", p.gammaType);
            shaderProg.setVar("u_gamma", p.gamma);
            shaderProg.setVar("u_gamma_inv", 1f / Math.max(p.gamma, 1f));
            shaderProg.setVar("u_film_toe", p.filmToe);
            shaderProg.drawBlocks(passOut);
            shaderProg.closed = true;
        } finally {
            if (curve != null) curve.close();
            if (lutTexture != 0) android.opengl.GLES30.glDeleteTextures(1, new int[]{lutTexture}, 0);
            v2.close();
            st.closeTextures();
            pipeline.whyV2 = null;
        }
        Log.i("SCAM_PIPELINE", "WHY V2 tone ae=" + r.ae + " clip=" + r.clip + " op=" + p.toneOperator + " cb=" + r.contrastBoost
                + " macro=" + r.macroContrast + " grid=" + size.x + "x" + size.y + (v2.fusedScale > 1 ? " fused=half" : "")
                + (pipeline.p3Output ? " output=DisplayP3" : "") + (pipeline.tenBitOutput ? " 10bit" : " quant8") + (lut != null ? " filmLut=" + lutName + " " + lut.size + "^3 x" + lutStrength : "")
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
