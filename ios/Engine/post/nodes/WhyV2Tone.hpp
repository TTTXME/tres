// «Why тон» V2 (P614), the last step: WhyV2Tone.java one to one - the reference tone's tonemap_final (why/v2_tonemap) on the
// clean RGB with the fused luma of WhyV2Fusion and our sharpened luminance (WhyLumaSharpen) as its detail luma, into the
// pipeline's main texture (the display-encoded photo). The own curve: 1024 samples (WhyV2Curve) in an R16F 1024 x 1 texture,
// LINEAR. The port's output is the RGBA8 photo (runAllToRgba), so QUANT8 is always on (the Java's tenBitOutput off); Display P3
// output (P3_OUT) with «Цветовое пространство» Display P3 (ShotRun). «Плёночный LUT» (FILM_LUT) as the Java (never a film on this route).
#pragma once

#include "WhyV2Stats.hpp"

namespace scam_post {

class WhyV2Tone : public Node {
public:
    WhyV2Tone() : Node("", "WhyV2Tone") {}
    void Compile() override {}

    static constexpr int CURVE_SAMPLES = 1024;

    static std::vector<float> curveSamples(const WhyV2Ae::Params& p) {
        return WhyV2Curve::samples(p.curvePoints, p.curveMods, CURVE_SAMPLES);
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        auto v2 = pipeline.whyV2;
        auto st = pipeline.why;
        if (!v2 || !v2->ae || !v2->clean || !v2->fused)
            throw std::runtime_error("WHY V2: no fusion (WhyV2Stats / WhyV2Fusion did not run)");
        if (!st || !st->lumaS) throw std::runtime_error("WHY V2: no sharpened luminance (WhyLumaSharpen did not run)");
        const WhyV2Ae::Params& p = v2->params;
        const WhyV2Ae::Result r = *v2->ae;
        const Point size = v2->clean->mSize;
        // what V1's WhyCombine consumes of the merge's side data: not drawn by the reference tone, given back here
        if (pipeline.sunBloom) { pipeline.sunBloom->close(); pipeline.sunBloom = nullptr; }
        if (pipeline.opposedShare) { pipeline.opposedShare->close(); pipeline.opposedShare = nullptr; }
        pipeline.skyHdrRatio.reset();
        pipeline.skyHdrRatioSize.reset();
        // our LUT (never with a film on this route: PostPipeline takes V1 for a film of the old route)
        const FilmChain::State chain = FilmChain::current(nullptr, pipeline.filmUserLut, pipeline.filmUserStrength);
        const float lutStrength = chain.strength;
        const std::string lutName = chain.key;
        const FilmLutP lut = lutStrength > 0.f && !lutName.empty() ? FilmChain::lut(lutName) : nullptr;
        GLuint lutTexture = 0;
        Texture curve;
        auto cleanup = [&] {
            if (curve) curve->close();
            if (lutTexture) glDeleteTextures(1, &lutTexture);
            v2->close();
            st->closeTextures();
            pipeline.whyV2 = nullptr;
        };
        try {
            const std::vector<float> samples = curveSamples(p);
            curve = makeTexture({CURVE_SAMPLES, 1}, GLFormat(DataType::FLOAT_16, 1), samples.data(), GL_LINEAR, GL_CLAMP_TO_EDGE);
            if (st->inputReleased) glFinish();
            WorkingTexture = pipeline.getMain();
            const bool p3Output = pipeline.p3Output, tenBitOutput = false;
            if (p3Output) prog.setDefine("P3_OUT", 1);
            if (lut) prog.setDefine("FILM_LUT", 1);
            if (v2->fusedScale > 1) prog.setDefine("EF_SCALED", 1);
            if (!tenBitOutput) prog.setDefine("QUANT8", 1);
            prog.useAssetProgram("why/v2_tonemap", false);
            prog.setTexture("u_tex_clean_rgb", v2->clean);
            prog.setTexture("u_tex_accum_ef", v2->fused);
            prog.setTexture("u_tex_hires_luma", st->lumaS);
            prog.setTexture("u_tex_custom_curve", curve);
            if (lut) {
                lutTexture = lut->upload();
                prog.setTexture3D("FilmLut", lutTexture);
                prog.setVar("lutU", lutStrength, float(lut->size));
            }
            prog.setVar("u_width", size.x);
            prog.setVar("u_height", size.y);
            prog.setVar("u_auto_exposure", r.ae);
            prog.setVar("u_clip_ceiling", r.clip);
            prog.setVar("u_hdr_shoulder_strength", p.shoulderStrength);
            prog.setVar("u_hdr_shoulder_knee", p.shoulderKnee);
            prog.setVar("u_hdr_shoulder_headroom", p.shoulderHeadroom);
            prog.setVar("u_aces_toe", p.acesToe);
            prog.setVar("u_tonemap_operator", p.toneOperator);
            prog.setVar("u_agx_look", p.agxLook);
            prog.setVar("u_agx_slope", r.agxSlope);
            prog.setVar("u_agx_sp", p.agxSp);
            prog.setVar("u_agx_tp", p.agxTp);
            prog.setVar("u_agx_min_ev", p.agxMinEv);
            prog.setVar("u_agx_max_ev", p.agxMaxEv);
            prog.setVar("u_agx_sat_boost", p.agxSat);
            prog.setVar("u_agx_ev", p.agxEv);
            prog.setVar("u_agx_hue_lock", p.agxHueLock ? 1 : 0);
            // column-major, as the reference uploads them (glUniformMatrix3fv without transpose)
            prog.setVar("u_agx_mat_inset", false, WhyV2Ae::insetMatrix(p.agxInset));
            prog.setVar("u_agx_mat_outset", false, WhyV2Ae::outsetMatrix(p.agxInset));
            prog.setVar("u_aces_pre_gain", p.acesPreGain);
            prog.setVar("u_aces_a_coeff", p.acesA);
            prog.setVar("u_aces_d_coeff", r.acesD);
            prog.setVar("u_uchi_pre_gain", p.uchiPreGain);
            prog.setVar("u_uchi_post_gain", p.uchiPostGain);
            prog.setVar("u_uchi_p", p.uchiP);
            prog.setVar("u_uchi_a", r.uchiA);
            prog.setVar("u_uchi_m", p.uchiM);
            prog.setVar("u_uchi_l", p.uchiL);
            prog.setVar("u_uchi_c", p.uchiC);
            prog.setVar("u_uchi_b", p.uchiB);
            prog.setVar("u_macro_contrast", r.macroContrast);
            prog.setVar("u_contrast_boost", r.contrastBoost);
            prog.setVar("u_vibrance_strength", p.vibStrength);
            prog.setVar("u_vibrance_saturated", p.vibSaturated);
            prog.setVar("u_skin_protect_toggle", p.skinProtect);
            prog.setVar("u_vibrance_sky_str", p.vibSky);
            prog.setVar("u_vibrance_green_str", p.vibGreen);
            prog.setVar("u_vibrance_hl_str", p.vibHl);
            prog.setVar("u_vibrance_mid_str", p.vibMid);
            prog.setVar("u_vibrance_shadow_str", p.vibShadow);
            prog.setVar("u_skin_hue_shift", p.skinHueShift);
            prog.setVar("u_skin_sat_shift", p.skinSatShift);
            prog.setVar("u_skin_hue_center", p.skinHueCenterRad());
            prog.setVar("u_skin_luma_min", p.skinLumaMin);
            prog.setVar("u_gamma_curve_type", p.gammaType);
            prog.setVar("u_gamma", p.gamma);
            prog.setVar("u_gamma_inv", 1.f / jmath::max(p.gamma, 1.f));
            prog.setVar("u_film_toe", p.filmToe);
            prog.drawBlocks(WorkingTexture);
            prog.closed = true;
        } catch (...) {
            cleanup();
            throw;
        }
        cleanup();
        logI("SCAM_PIPELINE", "WHY V2 tone ae=" + javaFloatString(r.ae) + " clip=" + javaFloatString(r.clip) + " op=" + std::to_string(p.toneOperator)
             + " cb=" + javaFloatString(r.contrastBoost) + " macro=" + javaFloatString(r.macroContrast) + " grid=" + std::to_string(size.x) + "x"
             + std::to_string(size.y) + (v2->fusedScale > 1 ? " fused=half" : "") + " quant8"
             + (lut ? " filmLut=" + lutName + " " + std::to_string(lut->size) + "^3 x" + javaFloatString(lutStrength) : ""));
    }
};

} // namespace scam_post
