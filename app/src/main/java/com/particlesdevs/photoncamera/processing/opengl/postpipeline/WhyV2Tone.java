package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.Log;

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
    @Override public void Compile() {}

    /** The reference's own curve table (a 1024-float vector uploaded as 1024 x 1). */
    static final int CURVE_SAMPLES = 1024;

    /** The own curve's samples as the reference builds them (WhyV2Curve: its points, its seven modifiers, generateLut). */
    static float[] curveSamples(WhyV2Ae.Params p) {
        return WhyV2Curve.samples(p.curvePoints, p.curveMods, CURVE_SAMPLES);
    }

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        final WhyV2Stats.State v2 = pipeline.whyV2;
        final WhyTone.State st = pipeline.why;
        if (v2 == null || v2.ae == null || v2.clean == null || v2.fused == null)
            throw new IllegalStateException("WHY V2: no fusion (WhyV2Stats / WhyV2Fusion did not run)");
        if (st == null || st.lumaS == null) throw new IllegalStateException("WHY V2: no sharpened luminance (WhyLumaSharpen did not run)");
        final long started = System.currentTimeMillis();
        final WhyV2Ae.Params p = v2.params;
        final WhyV2Ae.Result r = v2.ae;
        final Point size = v2.clean.mSize;
        // what V1's WhyCombine consumes of the merge's side data: not drawn by the reference tone, given back here
        if (pipeline.sunBloom != null) { pipeline.sunBloom.close(); pipeline.sunBloom = null; }
        if (pipeline.opposedShare != null) { pipeline.opposedShare.close(); pipeline.opposedShare = null; }
        pipeline.skyHdrRatio = null;
        pipeline.skyHdrRatioSize = null;
        // our LUT (never with a film on this route: PostPipeline takes V1 for a film of the old route)
        final com.particlesdevs.photoncamera.processing.color.FilmChain.State chain =
                com.particlesdevs.photoncamera.processing.color.FilmChain.current(null, pipeline.filmUserLut, pipeline.filmUserStrength);
        final float lutStrength = chain != null ? chain.strength : 0f;
        final String lutName = chain != null ? chain.key : "";
        final com.particlesdevs.photoncamera.processing.color.FilmLut lut = lutStrength > 0f && !lutName.isEmpty()
                ? com.particlesdevs.photoncamera.processing.color.FilmChain.lut(com.particlesdevs.photoncamera.app.ScameraApp.getAppContext(), lutName) : null;
        int lutTexture = 0;
        GLTexture curve = null;
        try {
            // the reference: glTexImage2D(GL_R16F, 1024, 1, GL_RED, GL_FLOAT), GL_LINEAR, clamp to edge (half floats filter in ES 3.0)
            curve = new GLTexture(new Point(CURVE_SAMPLES, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 1),
                    BufferUtils.getFrom(curveSamples(p)), GL_LINEAR, GL_CLAMP_TO_EDGE);
            if (st.inputReleased) android.opengl.GLES30.glFinish();
            WorkingTexture = pipeline.getMain();
            if (pipeline.p3Output) glProg.setDefine("P3_OUT", 1);
            if (lut != null) glProg.setDefine("FILM_LUT", 1);
            if (v2.fusedScale > 1) glProg.setDefine("EF_SCALED", 1);
            if (!pipeline.tenBitOutput) glProg.setDefine("QUANT8", 1);
            glProg.useAssetProgram("why/v2_tonemap", false);
            glProg.setTexture("u_tex_clean_rgb", v2.clean);
            glProg.setTexture("u_tex_accum_ef", v2.fused);
            glProg.setTexture("u_tex_hires_luma", st.lumaS);
            glProg.setTexture("u_tex_custom_curve", curve);
            if (lut != null) {
                lutTexture = lut.upload();
                glProg.setTexture3D("FilmLut", lutTexture);
                glProg.setVar("lutU", lutStrength, (float) lut.size);
            }
            glProg.setVar("u_width", size.x);
            glProg.setVar("u_height", size.y);
            glProg.setVar("u_auto_exposure", r.ae);
            glProg.setVar("u_clip_ceiling", r.clip);
            glProg.setVar("u_hdr_shoulder_strength", p.shoulderStrength);
            glProg.setVar("u_hdr_shoulder_knee", p.shoulderKnee);
            glProg.setVar("u_hdr_shoulder_headroom", p.shoulderHeadroom);
            glProg.setVar("u_aces_toe", p.acesToe);
            glProg.setVar("u_tonemap_operator", p.toneOperator);
            glProg.setVar("u_agx_look", p.agxLook);
            glProg.setVar("u_agx_slope", r.agxSlope);
            glProg.setVar("u_agx_sp", p.agxSp);
            glProg.setVar("u_agx_tp", p.agxTp);
            glProg.setVar("u_agx_min_ev", p.agxMinEv);
            glProg.setVar("u_agx_max_ev", p.agxMaxEv);
            glProg.setVar("u_agx_sat_boost", p.agxSat);
            glProg.setVar("u_agx_ev", p.agxEv);
            glProg.setVar("u_agx_hue_lock", p.agxHueLock ? 1 : 0);
            // column-major, as the reference uploads them (glUniformMatrix3fv without transpose)
            glProg.setVar("u_agx_mat_inset", false, WhyV2Ae.insetMatrix(p.agxInset));
            glProg.setVar("u_agx_mat_outset", false, WhyV2Ae.outsetMatrix(p.agxInset));
            glProg.setVar("u_aces_pre_gain", p.acesPreGain);
            glProg.setVar("u_aces_a_coeff", p.acesA);
            glProg.setVar("u_aces_d_coeff", r.acesD);
            glProg.setVar("u_uchi_pre_gain", p.uchiPreGain);
            glProg.setVar("u_uchi_post_gain", p.uchiPostGain);
            glProg.setVar("u_uchi_p", p.uchiP);
            glProg.setVar("u_uchi_a", r.uchiA);
            glProg.setVar("u_uchi_m", p.uchiM);
            glProg.setVar("u_uchi_l", p.uchiL);
            glProg.setVar("u_uchi_c", p.uchiC);
            glProg.setVar("u_uchi_b", p.uchiB);
            glProg.setVar("u_macro_contrast", r.macroContrast);
            glProg.setVar("u_contrast_boost", r.contrastBoost);
            glProg.setVar("u_vibrance_strength", p.vibStrength);
            glProg.setVar("u_vibrance_saturated", p.vibSaturated);
            glProg.setVar("u_skin_protect_toggle", p.skinProtect);
            glProg.setVar("u_vibrance_sky_str", p.vibSky);
            glProg.setVar("u_vibrance_green_str", p.vibGreen);
            glProg.setVar("u_vibrance_hl_str", p.vibHl);
            glProg.setVar("u_vibrance_mid_str", p.vibMid);
            glProg.setVar("u_vibrance_shadow_str", p.vibShadow);
            glProg.setVar("u_skin_hue_shift", p.skinHueShift);
            glProg.setVar("u_skin_sat_shift", p.skinSatShift);
            glProg.setVar("u_skin_hue_center", p.skinHueCenterRad());
            glProg.setVar("u_skin_luma_min", p.skinLumaMin);
            glProg.setVar("u_gamma_curve_type", p.gammaType);
            glProg.setVar("u_gamma", p.gamma);
            glProg.setVar("u_gamma_inv", 1f / Math.max(p.gamma, 1f));
            glProg.setVar("u_film_toe", p.filmToe);
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
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
