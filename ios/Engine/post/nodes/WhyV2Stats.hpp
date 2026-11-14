// «Why тон» V2 (P614), step 1: WhyV2Stats.java one to one - the clean linear RGB of the shot (why/v2_clean), the reference
// tone's histogram samples (why/v2_stride, every u_stride-th pixel, counted on the CPU) and its exposure and fusion strengths
// (WhyV2Ae). Hands WhyV2State on to WhyV2Highlights / WhyV2Fusion / WhyV2Tone, and to WhyLumaSharpen a WhyToneState with the
// exposure. The image passes through.
#pragma once

#include "../why/WhyToneCore.hpp"
#include "../why/WhyV2Ae.hpp"

#include <regex>

namespace scam_post {

class WhyV2Stats : public Node {
public:
    WhyV2Stats() : Node("", "WhyV2Stats") {}
    void Compile() override {}

    /// the reference's highlight recovery factor (pref_highlight_recovery_factor, 8 in the owner's configuration)
    static constexpr float MAX_CEILING = 8.f;

    static float clipCeiling(bool bento, float /*max*/) { return bento ? MAX_CEILING : 1.f; }

    /// whether the merge fused short frames: Bento applied, or the fast route's «N valid short frame(s)» with N > 0
    static bool shortFramesMerged(bool bentoApplied, const std::string& report) {
        if (bentoApplied) return true;
        static const std::regex fastShorts("HYBRID FAST MERGE HIGHLIGHT: accumulated validity of (\\d+) valid short frame");
        for (auto it = std::sregex_iterator(report.begin(), report.end(), fastShorts); it != std::sregex_iterator(); ++it) {
            const std::string n = (*it)[1].str();
            if (n.find_first_not_of('0') != std::string::npos) return true;
        }
        return false;
    }

    /// owner(): a merged row's value within two ulps of the owner's float is that float
    static float owner(float value, float ownerValue) {
        return jmath::abs(value - ownerValue) <= 2.f * jmath::ulp(ownerValue) ? ownerValue : value;
    }

    /// toneOperator(): V1's switches (the own curve, Uchimura, ACES; else AgX)
    static int toneOperator() {
        Settings& s = Settings::current();
        if (s.hybridSwitch("why_curve_on", true)) return 3;
        if (s.hybridSwitch("why_uchi_on", false)) return 2;
        if (s.hybridSwitch("why_aces_on", false)) return 1;
        return 0;
    }

    /// settings(): V2's tone settings (V1's rows where they are the same setting, the pref_scam_hybrid_why_v2_* of its own)
    static WhyV2Ae::Params settings() {
        using WhyTone::value;
        Settings& s = Settings::current();
        WhyV2Ae::Params p;
        p.aeTarget = owner(value("ae_target", p.aeTarget), p.aeTarget);
        p.maxBoost = value("ae_max_boost", p.maxBoost);
        p.minLimit = value("ae_min_limit", p.minLimit);
        p.brightThresh = owner(value("bright_thresh", p.brightThresh), p.brightThresh);
        p.hlBoost = value("hl_boost", p.hlBoost);
        p.hlOverflow = value("hl_overflow", p.hlOverflow);
        p.hlBlend = value("hl_blend", p.hlBlend);
        p.darkThresh = value("dark_thresh", p.darkThresh);
        p.darkPixelThresh = value("dark_pixel_thresh", p.darkPixelThresh);
        p.contrastBoost = value("contrast_boost", p.contrastBoost);
        p.nightDim = owner(value("night_thresh", p.nightDim), p.nightDim);
        p.highlightStr = owner(value("highlight_str", p.highlightStr), p.highlightStr);
        p.shadowStr = value("shadow_str", p.shadowStr);
        p.weightExtHl = value("classic_weight_ext_hl", p.weightExtHl);
        p.weightHl = value("classic_weight_hl", p.weightHl);
        p.weightMid = value("classic_weight_mid", p.weightMid);
        p.weightShadow = value("classic_weight_shadow", p.weightShadow);
        p.weightCenter = value("classic_weight_center", p.weightCenter);
        p.blendSmoothness = owner(value("blend_smoothness", p.blendSmoothness), p.blendSmoothness);
        p.macroContrast = value("macro_contrast", p.macroContrast);
        p.clarity = value("v2_clarity", p.clarity);
        p.toneOperator = toneOperator();
        p.gammaType = int(Settings::javaRound(value("gamma_type", float(p.gammaType))));
        p.gamma = value("gamma", p.gamma);
        p.filmToe = value("film_toe", p.filmToe);
        p.agxLook = int(Settings::javaRound(value("v2_agx_look", float(p.agxLook))));
        p.agxSlope = value("agx_slope", p.agxSlope);
        p.agxSp = value("agx_sp", p.agxSp);
        p.agxTp = value("agx_tp", p.agxTp);
        p.agxMinEv = value("agx_min_ev", p.agxMinEv);
        p.agxMaxEv = value("agx_max_ev", p.agxMaxEv);
        p.agxSat = value("agx_sat", p.agxSat);
        p.agxEv = value("agx_ev", p.agxEv);
        p.agxInset = value("agx_inset", p.agxInset);
        p.agxHueLock = s.hybridSwitch("why_v2_agx_hue_lock", p.agxHueLock);
        p.acesToe = value("aces_toe", p.acesToe);
        p.acesPreGain = value("aces_pre_gain", p.acesPreGain);
        p.acesA = value("aces_a", p.acesA);
        p.acesD = value("aces_d", p.acesD);
        p.uchiPreGain = value("uchi_pre_gain", p.uchiPreGain);
        p.uchiPostGain = value("uchi_post_gain", p.uchiPostGain);
        p.uchiP = value("uchi_p", p.uchiP);
        p.uchiA = value("uchi_a", p.uchiA);
        p.uchiM = value("uchi_m", p.uchiM);
        p.uchiL = value("uchi_l", p.uchiL);
        p.uchiC = value("uchi_c", p.uchiC);
        p.uchiB = value("uchi_b", p.uchiB);
        p.curvePoints = s.hybridString("why_curve_points", p.curvePoints);
        p.curveMods[0] = value("curve_lift", p.curveMods[0]);
        p.curveMods[1] = value("curve_contrast", p.curveMods[1]);
        p.curveMods[2] = value("curve_shadow_contrast", p.curveMods[2]);
        p.curveMods[3] = value("curve_highlight_contrast", p.curveMods[3]);
        p.curveMods[4] = value("curve_deep_shadows", p.curveMods[4]);
        p.curveMods[5] = value("curve_extreme_highlights", p.curveMods[5]);
        p.curveMods[6] = value("curve_offset", p.curveMods[6]);
        p.vibStrength = value("vibrance", p.vibStrength);
        p.vibSaturated = value("vibrance_saturated", p.vibSaturated);
        p.vibSky = value("vibrance_sky", p.vibSky);
        p.vibGreen = value("vibrance_green", p.vibGreen);
        p.vibHl = value("vibrance_hl_str", p.vibHl);
        p.vibMid = value("vibrance_mid", p.vibMid);
        p.vibShadow = value("vibrance_shadows", p.vibShadow);
        p.skinProtect = value("skin_protect", p.skinProtect);
        p.skinHueShift = value("skin_hue_shift", p.skinHueShift);
        p.skinSatShift = value("skin_sat_shift", p.skinSatShift);
        p.skinHueCenterDeg = value("v2_skin_hue_center", p.skinHueCenterDeg);
        p.skinLumaMin = value("skin_luma_min", p.skinLumaMin);
        p.shoulderStrength = value("hr_strength", p.shoulderStrength);
        p.shoulderKnee = value("hr_knee", p.shoulderKnee);
        p.shoulderHeadroom = value("hr_room", p.shoulderHeadroom);
        // DenoiseStages.on(): PreferenceKeys.isHybridDenoiseEnabled
        p.bracketDenoise = s.hybridSwitch("denoise", true) ? value("bracket_denoise", p.bracketDenoise) : 0.f;
        p.hrAmount = value("v2_hr_amount", p.hrAmount);
        return p;
    }

    /// NoiseModeler.reportedMean of the camera's own profile (its fallback without one)
    static std::array<float, 2> referenceNoise(const std::optional<NoiseModel>& modeler) {
        double s = 0.0, o = 0.0;
        int n = 0;
        if (modeler && modeler->reportedModel) {
            for (const auto& c : *modeler->reportedModel) {
                s += c.first;
                o += c.second;
                n++;
            }
        }
        float fs = 0.f, fo = 0.f;
        if (n > 0) {
            fs = float(s / n);
            fo = float(o / n);
        }
        if (!(fs > 0.f)) fs = 0.00015f;
        if (!(fo > 0.f)) fo = 1e-5f;
        return {fs, fo};
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        WorkingTexture = previousNode->WorkingTexture;
        prog.closed = true;
        auto st = std::make_shared<WhyToneState>();
        pipeline.why = st;
        st->factor = WhyTone::lowFactor(pipeline.mParameters);
        st->inScale = float(std::pow(2.0, double(WhyTone::value("input_ev", 0.f))));
        auto v2 = std::make_shared<WhyV2State>();
        v2->params = settings();
        pipeline.whyV2 = v2;
        const Point size = WorkingTexture->mSize;
        Texture gainMap = pipeline.GainMap, fallback, samplesTex;
        if (!gainMap) {
            const float one[4] = {1.f, 1.f, 1.f, 1.f};
            fallback = makeTexture({1, 1}, GLFormat(DataType::FLOAT_16, 4), one, GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        auto cleanup = [&] {
            if (samplesTex) samplesTex->close();
            if (fallback) fallback->close();
        };
        try {
            v2->clean = makeTexture(size, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("why/v2_clean", false);
            prog.setTexture("InputBuffer", WorkingTexture);
            WhyTone::setColour(prog, pipeline, *gainMap, st->inScale);
            prog.drawBlocks(v2->clean);
            const int stride = WhyV2Ae::stride(size.x, size.y);
            const int sw = WhyV2Ae::samples(size.x, stride), sh = WhyV2Ae::samples(size.y, stride);
            samplesTex = makeTexture({sw, sh}, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("why/v2_stride", false);
            prog.setTexture("u_tex_rgb", v2->clean);
            prog.setVar("u_stride", stride);
            prog.drawBlocks(samplesTex);
            prog.closed = true;
            samplesTex->BufferLoad();
            const std::vector<uint8_t> px = samplesTex->textureBuffer(GLFormat(DataType::FLOAT_16, 4));
            std::vector<float> f(px.size() / 4);
            std::memcpy(f.data(), px.data(), f.size() * 4);
            float max = 0.f;
            for (size_t i = 0; i < size_t(sw) * size_t(sh); i++)
                for (size_t c = 0; c < 3; c++) {
                    const float v = f[i * 4 + c];
                    if (v > max && jmath::isFinite(v)) max = v;
                }
            const bool shorts = shortFramesMerged(pipeline.merge.lastBentoApplied, pipeline.merge.lastReport);
            const float ceiling = clipCeiling(shorts, max);
            const float clip = WhyV2Ae::clipCeiling(ceiling);
            // no face: the reference's photo path never fills its AE's face rect
            WhyV2Ae::Hist h;
            for (int y = 0; y < sh; y++)
                for (int x = 0; x < sw; x++) {
                    const size_t o = (size_t(y) * size_t(sw) + size_t(x)) * 4;
                    WhyV2Ae::add(h, f[o], f[o + 1], f[o + 2], clip, false);
                }
            const std::array<float, 2> noise = referenceNoise(pipeline.mParameters.noiseModeler);
            const WhyV2Ae::Result r = WhyV2Ae::finish(h, v2->params, ceiling, noise[0], noise[1]);
            v2->ae = r;
            WhyAe::Result shim;
            shim.ae = r.ae;
            shim.clip = r.clip;
            st->ae = shim;
            pipeline.linearDisplayGain = r.ae;
            logI("SCAM_PIPELINE", "WHY V2 stats " + r.describe() + " stride=" + std::to_string(stride) + " samples=" + std::to_string(sw) + "x"
                 + std::to_string(sh) + " max=" + javaFloatString(max) + " bento=" + (pipeline.merge.lastBentoApplied ? "true" : "false")
                 + " shorts=" + (shorts ? "true" : "false") + " noise=" + javaFloatString(noise[0]) + "/" + javaFloatString(noise[1])
                 + " (stacked model " + javaFloatString(pipeline.noiseS) + "/" + javaFloatString(pipeline.noiseO) + ")");
        } catch (...) {
            cleanup();
            throw;
        }
        cleanup();
    }
};

} // namespace scam_post
