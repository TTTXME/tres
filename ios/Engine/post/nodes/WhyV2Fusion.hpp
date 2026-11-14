// «Why тон» V2 (P614), step 2: WhyV2Fusion.java one to one - the reference tone's Laplacian pyramid exposure fusion:
// ef_pyr_prep at level 0 (the lumas and weights of the four synthetic exposures), pyr_downsample of both down to the level whose
// side reaches 48 px (WhyV2Ae::levels), ef_pyr_coarsest there and ef_pyr_collapse back up (R16F). The fused luma of level 0
// goes to WhyV2Tone (WhyV2State.fused). The image passes through.
#pragma once

#include "WhyV2Stats.hpp"

namespace scam_post {

class WhyV2Fusion : public Node {
public:
    WhyV2Fusion() : Node("", "WhyV2Fusion") {}
    void Compile() override {}

    /// PostPipeline.V2_MAX_PIXELS: level 0 is half the grid above it (never on V2's grids)
    static constexpr int64_t FULL_LIMIT = 100000000LL;
    static int baseScale(int w, int h) { return int64_t(w) * h > FULL_LIMIT ? 2 : 1; }
    /// P677: the edge measure's reference, the guide's Gaussian this many levels down
    static constexpr int REF_DOWN = 3;

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        WorkingTexture = previousNode->WorkingTexture;
        prog.closed = true;
        auto v2 = pipeline.whyV2;
        if (!v2 || !v2->ae || !v2->clean) throw std::runtime_error("WHY V2: no statistics (WhyV2Stats did not run)");
        const WhyV2Ae::Params& p = v2->params;
        const WhyV2Ae::Result& r = *v2->ae;
        const Point grid = v2->clean->mSize;
        const int scale = baseScale(grid.x, grid.y);
        const auto lv = WhyV2Ae::levels((grid.x + scale - 1) / scale, (grid.y + scale - 1) / scale);
        const int n = int(lv.size());
        std::vector<Texture> g(static_cast<size_t>(n)), w(static_cast<size_t>(n)), fu(static_cast<size_t>(n));
        const std::array<float, 4> em = WhyV2Ae::exposures(r), lw = WhyV2Ae::layerWeights(p);
        // P673: the joint bilateral collapse (0: off); P677: V1's edge-aware bands (off by default)
        const float jbu = WhyTone::jbuSigmaV2();
        const int jbuGuide = WhyTone::jbuGuideV2();
        const auto band = scale == 1 ? WhyTone::edgeBandsV2() : std::nullopt;
        const int deep = n + REF_DOWN - 1;
        std::vector<Texture> gf(static_cast<size_t>(deep)), dd(static_cast<size_t>(n)), sh(static_cast<size_t>(n));
        auto cleanup = [&] {
            for (auto* a : {&gf, &dd, &sh}) for (auto& t : *a) if (t) t->close();
            for (int l = 0; l < n; l++)
                for (Texture* t : {&g[size_t(l)], &w[size_t(l)], &fu[size_t(l)]}) if (*t) (*t)->close();
        };
        try {
            for (int l = 0; l < n; l++) {
                g[size_t(l)] = plane(lv[size_t(l)][0], lv[size_t(l)][1], 4);
                w[size_t(l)] = plane(lv[size_t(l)][0], lv[size_t(l)][1], 4);
                fu[size_t(l)] = plane(lv[size_t(l)][0], lv[size_t(l)][1], 1);
            }
            for (int mode = 0; mode < 2; mode++) {
                prog.setDefine("MODE", mode);
                if (scale == 2) prog.setDefine("SCALE", 2);
                prog.useAssetProgram("why/v2_ef_prep", false);
                prog.setTexture("u_tex_clean_rgb", v2->clean);
                prog.setVar("u_width", lv[0][0]);
                prog.setVar("u_height", lv[0][1]);
                prog.setVar("u_exp_multipliers", em[0], em[1], em[2], em[3]);
                prog.setVar("u_layer_weights", lw[0], lw[1], lw[2], lw[3]);
                prog.setVar("u_weight_center", p.weightCenter);
                prog.setVar("u_auto_exposure", r.ae);
                prog.setVar("u_blend_smoothness", p.blendSmoothness);
                prog.setVar("u_gamma_curve_type", p.gammaType);
                prog.setVar("u_gamma_inv", 1.f / jmath::max(p.gamma, 1.f));
                prog.setVar("u_aces_toe", p.acesToe);
                prog.drawBlocks(mode == 0 ? g[0] : w[0]);
            }
            for (int l = 0; l + 1 < n; l++) {
                for (std::vector<Texture>* pyr : {&g, &w}) {
                    prog.useAssetProgram("why/v2_pyr_down", false);
                    prog.setTexture("u_tex_src", (*pyr)[size_t(l)]);
                    prog.setVar("u_texel_size", 1.f / float(lv[size_t(l)][0]), 1.f / float(lv[size_t(l)][1]));
                    prog.setVar("u_dst_w", lv[size_t(l + 1)][0]);
                    prog.setVar("u_dst_h", lv[size_t(l + 1)][1]);
                    prog.drawBlocks((*pyr)[size_t(l + 1)]);
                }
            }
            if (band) bandPasses(*v2, p, r, em, lw, lv, *band, pipeline, gf, dd, sh);
            if (band) prog.setDefine("BANDS", 1);
            prog.useAssetProgram("why/v2_ef_coarsest", false);
            prog.setTexture("u_tex_G", g[size_t(n - 1)]);
            prog.setTexture("u_tex_W", w[size_t(n - 1)]);
            prog.setVar("u_width", lv[size_t(n - 1)][0]);
            prog.setVar("u_height", lv[size_t(n - 1)][1]);
            if (band) {
                prog.setTexture("u_tex_GF", gf[size_t(n - 1)]);
                prog.setTexture("u_tex_share", sh[size_t(n - 1)]);
                prog.setVar("u_band_strength", (*band)[3]);
            }
            prog.drawBlocks(fu[size_t(n - 1)]);
            for (int l = n - 2; l >= 0; l--) {
                if (jbu > 0.f) prog.setDefine("JBU", WhyTone::jbuDefine(jbuGuide));
                if (band) prog.setDefine("BANDS", 1);
                prog.useAssetProgram("why/v2_ef_collapse", false);
                if (band) {
                    prog.setTexture("u_tex_GF_fine", gf[size_t(l)]);
                    prog.setTexture("u_tex_GF_coarse", gf[size_t(l + 1)]);
                    prog.setTexture("u_tex_share", l == 0 ? dd[0] : sh[size_t(l)]);
                    const auto ramp0 = WhyTone::edgeRamp(*band, 0);
                    prog.setVar("u_band_edge", l == 0 ? ramp0[0] : 0.f, l == 0 ? ramp0[1] : 0.f, l == 0 ? ramp0[2] : 0.f, (*band)[3]);
                    prog.setVar("u_band_reach", l == 0 ? int((*band)[4]) : -1);
                }
                if (jbu > 0.f) {
                    prog.setVar("u_jbu_sigma", jbu);
                    prog.setVar("u_jbu_log", WhyTone::jbuLog(jbuGuide));
                }
                prog.setTexture("u_tex_G_fine", g[size_t(l)]);
                prog.setTexture("u_tex_G_coarse", g[size_t(l + 1)]);
                prog.setTexture("u_tex_W_fine", w[size_t(l)]);
                prog.setTexture("u_tex_fused_coarse", fu[size_t(l + 1)]);
                prog.setVar("u_width", lv[size_t(l)][0]);
                prog.setVar("u_height", lv[size_t(l)][1]);
                prog.setVar("u_clarity", p.clarity);
                prog.drawBlocks(fu[size_t(l)]);
            }
            prog.closed = true;
            v2->fused = fu[0];
            v2->fusedScale = scale;
            fu[0] = nullptr;
        } catch (...) {
            cleanup();
            throw;
        }
        cleanup();
        logI("SCAM_PIPELINE", "WHY V2 fusion levels=" + std::to_string(n) + " base=" + std::to_string(lv[0][0]) + "x" + std::to_string(lv[0][1])
             + " coarsest=" + std::to_string(lv[size_t(n - 1)][0]) + "x" + std::to_string(lv[size_t(n - 1)][1]) + " em=" + javaFloatString(em[0]) + ","
             + javaFloatString(em[1]) + "," + javaFloatString(em[2]) + "," + javaFloatString(em[3]) + " lw=" + javaFloatString(lw[0]) + ","
             + javaFloatString(lw[1]) + "," + javaFloatString(lw[2]) + "," + javaFloatString(lw[3]) + " centre=" + javaFloatString(p.weightCenter)
             + " smooth=" + javaFloatString(p.blendSmoothness)
             + (jbu > 0.f ? " jbu=" + javaFloatString(jbu) + "/" + std::to_string(jbuGuide) : "")
             + (band ? " edgeBands=" + javaFloatString((*band)[0]) + ".." + javaFloatString((*band)[1]) + "EV x" + javaFloatString((*band)[3]) : ""));
    }

private:
    /// P677: WhyV2Fusion.bandPasses - V1's edge-aware bands' passes on V2's pyramid (fills gf, dd, sh)
    void bandPasses(WhyV2State& v2, const WhyV2Ae::Params& p, const WhyV2Ae::Result& r, const std::array<float, 4>& em,
                    const std::array<float, 4>& lw, const std::vector<std::array<int, 2>>& lv, const std::array<float, 6>& band,
                    PostPipeline& pipeline, std::vector<Texture>& gf, std::vector<Texture>& dd, std::vector<Texture>& sh) {
        GLProg& prog = *glProg;
        const int n = int(lv.size()), deep = int(gf.size());
        std::vector<std::array<int, 2>> sz(static_cast<size_t>(deep));
        for (int l = 0; l < deep; l++) {
            sz[size_t(l)] = l < n ? lv[size_t(l)]
                                  : std::array<int, 2>{std::max(sz[size_t(l - 1)][0] >> 1, 1), sz[size_t(l - 1)][1] > 3 ? sz[size_t(l - 1)][1] / 2 : 1};
            gf[size_t(l)] = plane(sz[size_t(l)][0], sz[size_t(l)][1], 2);
        }
        prog.setDefine("MODE", 2);
        prog.useAssetProgram("why/v2_ef_prep", false);
        prog.setTexture("u_tex_clean_rgb", v2.clean);
        prog.setVar("u_width", lv[0][0]);
        prog.setVar("u_height", lv[0][1]);
        prog.setVar("u_exp_multipliers", em[0], em[1], em[2], em[3]);
        prog.setVar("u_layer_weights", lw[0], lw[1], lw[2], lw[3]);
        prog.setVar("u_weight_center", p.weightCenter);
        prog.setVar("u_auto_exposure", r.ae);
        prog.setVar("u_blend_smoothness", p.blendSmoothness);
        prog.setVar("u_gamma_curve_type", p.gammaType);
        prog.setVar("u_gamma_inv", 1.f / jmath::max(p.gamma, 1.f));
        prog.setVar("u_aces_toe", p.acesToe);
        prog.setVar("u_band_floor", band[2]);
        prog.drawBlocks(gf[0]);
        for (int l = 1; l < deep; l++) {
            prog.useAssetProgram("why/v2_pyr_down", false);
            prog.setTexture("u_tex_src", gf[size_t(l - 1)]);
            prog.setVar("u_texel_size", 1.f / float(sz[size_t(l - 1)][0]), 1.f / float(sz[size_t(l - 1)][1]));
            prog.setVar("u_dst_w", sz[size_t(l)][0]);
            prog.setVar("u_dst_h", sz[size_t(l)][1]);
            prog.drawBlocks(gf[size_t(l)]);
        }
        const float k = WhyTone::bandsNoise(), c = WhyTone::noiseScale();
        const bool noise = k > 0.f && std::isfinite(pipeline.fusionNoiseS) && std::isfinite(pipeline.fusionNoiseO)
                           && (pipeline.fusionNoiseS > 0.f || pipeline.fusionNoiseO > 0.f) && r.ae > 0.f;
        const float ns = noise ? c * r.ae * std::max(pipeline.fusionNoiseS, 0.f) : 0.f;
        const float no = noise ? c * r.ae * r.ae * std::max(pipeline.fusionNoiseO, 0.f) : 0.f;
        for (int l = 0; l + 1 < n; l++) {
            dd[size_t(l)] = plane(sz[size_t(l)][0], sz[size_t(l)][1], 1);
            prog.setDefine("MODE", 0);
            prog.useAssetProgram("why/v2_ef_bands", false);
            prog.setTexture("u_tex_GF", gf[size_t(l)]);
            prog.setTexture("u_tex_ref", gf[size_t(l + REF_DOWN)]);
            prog.setVar("u_width", sz[size_t(l)][0]);
            prog.setVar("u_height", sz[size_t(l)][1]);
            prog.setVar("u_band_noise", noise ? k * WhyTone::edgeBeta(l) : 0.f, ns, no, band[2]);
            prog.drawBlocks(dd[size_t(l)]);
        }
        const auto ramp0 = WhyTone::edgeRamp(band, 0);
        for (int l = 1; l < n; l++) {
            sh[size_t(l)] = plane(sz[size_t(l)][0], sz[size_t(l)][1], 2);
            const auto ramp = WhyTone::edgeRamp(band, l);
            prog.setDefine("MODE", 1);
            prog.useAssetProgram("why/v2_ef_bands", false);
            prog.setTexture("u_tex_fine", l == 1 ? dd[0] : sh[size_t(l - 1)]);
            prog.setTexture("u_tex_D", l < n - 1 ? dd[size_t(l)] : dd[0]);
            prog.setVar("u_width", sz[size_t(l)][0]);
            prog.setVar("u_height", sz[size_t(l)][1]);
            prog.setVar("u_band_edge", ramp[0], ramp[1], ramp[2], 0.f);
            prog.setVar("u_band_reach", l == n - 1 ? -1 : int(band[4]), l == 1 ? int(band[4]) : 1);
            if (l == 1) prog.setVar("u_band_carry", ramp0[0], ramp0[1], ramp0[2]);
            else prog.setVar("u_band_carry", 1.f, 0.f, 0.f);
            prog.drawBlocks(sh[size_t(l)]);
        }
        for (int l = 1; l + 1 < n; l++) { dd[size_t(l)]->close(); dd[size_t(l)] = nullptr; }
        for (int l = n; l < deep; l++) { gf[size_t(l)]->close(); gf[size_t(l)] = nullptr; }
    }

    static Texture plane(int w, int h, int channels) {
        return makeTexture({w, h}, GLFormat(DataType::FLOAT_16, channels), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
    }
};

} // namespace scam_post
