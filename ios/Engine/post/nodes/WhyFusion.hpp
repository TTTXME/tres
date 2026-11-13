// WhyFusion.java one to one (the default GL model): the bracket denoise of whyLow, the sky (WhySky), the Laplacian pyramid of
// the exposures with the edge-aware bands, the guided filter's share, the 31091 window. The fused luma goes to WhyTone.State.
#pragma once

#include "../why/WhySky.hpp"
#include "WhyStats.hpp"

namespace scam_post {

class WhyFusion : public Node {
public:
    WhyFusion() : Node("", "WhyFusion") {}
    void Compile() override {}

    static constexpr int GF_RADIUS = 32;
    static constexpr float GF_EPS = 0.001f, COARSE = 48.f, CENTER_31091 = 0.6f;
    static constexpr int REF_DOWN = 3;
    static std::array<float, 4> layers31091() { return {0.8f, 1.f, 0.7f, 0.5f}; }

    static std::optional<std::array<float, 2>> guideNoise(float s, float o, float merged, float ae, float inScale, float c) {
        if (!(merged > 0.f) || !(s > 0.f || o > 0.f) || !(ae > 0.f) || !(inScale > 0.f) || !(c > 0.f) || !std::isfinite(merged)) return std::nullopt;
        const double g = double(ae) * double(inScale), k = double(c) * double(merged) / 4.0;
        return std::array<float, 2>{float(k * g * double(std::max(s, 0.f))), float(k * g * g * double(std::max(o, 0.f)))};
    }
    static float headroomExposure(float em0, float ae, float clip, float hr) {
        if (!(hr > clip) || !(ae > 0.f) || !std::isfinite(hr)) return em0;
        return std::min(em0, 2.f / (ae * hr));
    }
    static int pyramidLevels(int w, int h, float coarse) {
        const int c = std::max(2, javaRound(coarse));
        int levels = 0;
        while (std::min((w + 1) / 2, (h + 1) / 2) >= c && levels < 16) { w = (w + 1) / 2; h = (h + 1) / 2; levels++; }
        return std::max(1, levels);
    }
    static std::array<float, 2> depth(int w, int h, float coarse) {
        const int c = std::max(2, javaRound(coarse));
        const double d = std::log(std::min(w, h) / double(c)) / std::log(2.0);
        if (!(d > 1.0)) return {1.f, 1.f};
        const int levels = int(std::ceil(d - 1e-6));
        const double f = d - (levels - 1);
        return {float(levels), f >= 1.0 - 1e-6 ? 1.f : float(std::max(0.0, f))};
    }
    static std::array<float, 4> exposures207(const WhyAe::Result& r) {
        return {float(std::pow(2.0, -2.5 * double(r.effH))), 1.f, float(std::pow(2.0, -1.2 * double(r.effH))), float(std::min(std::pow(2.0, 1.8 * double(r.effS)), 16.0))};
    }
    static std::array<float, 4> exposures31(const WhyAe::Result& r) {
        return {float(std::pow(2.0, -1.5 * double(r.effH))), 1.f, float(std::min(std::pow(2.0, double(r.effS)), 8.0)), float(std::min(std::pow(2.0, 2.0 * double(r.effS)), 16.0))};
    }
    static std::optional<std::array<float, 3>> windowParams(float depthV, const WhyAe::Result& r, float ev) {
        if (!(depthV > 0.f) || !(r.hr > r.clip) || !std::isfinite(ev) || !(r.ae > 0.f)) return std::nullopt;
        const float median = float(std::log(double(std::max(r.p50, 1e-6f))) / std::log(2.0)) + ev;
        const float t = std::max(0.f, std::min(1.f, (median - 10.f) / 1.5f));
        const float strength = depthV * (1.f - t * t * (3.f - 2.f * t));
        if (strength < 1e-4f) return std::nullopt;
        const float shift = float(std::log(double(r.ae)) / std::log(2.0)) - ev;
        return std::array<float, 3>{strength, 10.5f + shift, 12.f + shift};
    }
    static int windowLevel(int width, int deepest) {
        const int l = javaRound(float(std::log(std::sqrt(3.0) * width / 111.0) / std::log(2.0)));
        return std::max(1, std::min(l, deepest));
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        WorkingTexture = previousNode->WorkingTexture;
        prog.closed = true;
        auto stp = pipeline.why;
        if (!stp || !stp->ae || !stp->low) throw std::runtime_error("WHY tone: no statistics (WhyStats did not run)");
        WhyToneState& st = *stp;
        const WhyAe::Result& r = *st.ae;
        const Point lowSize = st.low->mSize;
        float strength = Settings::current().hybridSwitch("denoise", true) ? WhyTone::value("bracket_denoise", 0.f) : 0.f;
        st.lowDn = st.low;
        if (r.clip > 1.05f && strength > 0.001f) {
            st.lowDn = makeTexture(lowSize, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("why/bracket_dn", false);
            prog.setTexture("InputBuffer", st.low);
            prog.setVar("strengthU", strength);
            prog.drawBlocks(st.lowDn);
        }
        const bool why = WhyTone::whyLayout();
        const std::array<float, 4> em = why ? exposures207(r) : WhyAe::exposures(r, headroomExposure(WhyAe::darkestExposure(r), r.ae, r.clip, r.hr));
        std::array<float, 4> lw = WhyTone::layerWeights();
        const auto noiseI = guideNoise(st.noiseS, st.noiseO, st.noiseMerged, r.ae, st.inScale, WhyTone::noiseScale());
        const float tau = noiseI ? WhyTone::deepNoise() : 0.f;
        deepNoise_[0] = tau;
        deepNoise_[1] = tau > 0.f ? (*noiseI)[0] : 0.f;
        deepNoise_[2] = tau > 0.f ? (*noiseI)[1] : 0.f;
        deepNoise_[3] = 0.f;
        float center = WhyTone::fusionCenter();
        std::array<float, 6> satLight{};
        if (r.hr > 0.f && !why) {
            const auto ramp = WhyTone::saturatedLightRamp();
            if (ramp[1] > ramp[0]) satLight = {center, lw[0], ramp[0], ramp[1], ramp[2], ramp[3]};
            center = WhyTone::headroomCenter();
            lw[0] = WhyTone::headroomHlWeight();
        }
        try {
            WhySky::build(prog, pipeline, st, *st.lowDn);
        } catch (const std::exception& e) {
            logI("SCAM_PIPELINE", std::string("WHY sky: failed (") + e.what() + "), no sky term");
            if (st.sky) st.sky->close();
            st.sky = nullptr;
            st.skyMask = nullptr;
            prog.closed = true;
        }
        const Parameters& prm = pipeline.mParameters;
        const auto win = WhyTone::bands() && !why
                             ? windowParams(WhyTone::windowShare(), r, WhySkyMaskEv::evOffset(prm.iso, prm.exposureTime, prm.aperture, st.inScale))
                             : std::nullopt;
        Texture fused31;
        if (win) fused31 = pyramid(st, exposures31(r), layers31091(), r.ae, CENTER_31091, std::array<float, 6>{}, strength, noiseI, true, std::nullopt, nullptr);
        pyramid(st, em, lw, r.ae, center, satLight, strength, noiseI, false, win, fused31);
        if (fused31) fused31->close();
    }

private:
    float deepNoise_[4] = {0, 0, 0, 0};

    Texture stats(Point size) { return makeTexture(size, GLFormat(DataType::FLOAT_32, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE); }
    void gammaDefine() {
        const int t = WhyTone::gammaType();
        if (t != 0) glProg->setDefine("GAMMA_TYPE", t);
    }
    void fusionProgram(const std::string& asset, int mode, GLTexture& source, const std::array<float, 4>& em, const std::array<float, 4>& lw, float ae,
                       float center, const std::array<float, 6>& satLight) {
        GLProg& prog = *glProg;
        prog.setDefine("MODE", mode);
        gammaDefine();
        prog.useAssetProgram(asset, false);
        prog.setTexture("InputBuffer", source);
        prog.setVar("expU", em[0], em[1], em[2], em[3]);
        prog.setVar("layerU", lw[0], lw[1], lw[2], lw[3]);
        prog.setVar("aeU", ae);
        prog.setVar("centerU", center);
        prog.setVar("smoothU", WhyTone::value("blend_smoothness", 0.45f));
        prog.setVar("gammaInvU", 1.f / WhyTone::gamma());
        prog.setVar("toeU", WhyTone::value("aces_toe", 0.05f));
        prog.setVar("monoU", WhyTone::fusionMono());
        prog.setVar("satLightU", satLight[0], satLight[1], satLight[2], satLight[3]);
        prog.setVar("satLevelU", satLight[4], satLight[5]);
        const auto warm = WhyTone::warmLight();
        prog.setVar("warmU", warm[0], warm[1]);
        prog.setVar("deepFloorU", WhyTone::deepFloor());
        prog.setVar("deepNoiseU", deepNoise_[0], deepNoise_[1], deepNoise_[2], deepNoise_[3]);
    }
    Texture box(Texture in, int r) {
        GLProg& prog = *glProg;
        Texture h = stats(in->mSize);
        prog.useAssetProgram("why/box", false);
        prog.setTexture("InputBuffer", in);
        prog.setVar("dirU", 1, 0);
        prog.setVar("radiusU", r);
        prog.drawBlocks(h);
        in->close();
        Texture v = stats(in->mSize);
        prog.useAssetProgram("why/box", false);
        prog.setTexture("InputBuffer", h);
        prog.setVar("dirU", 0, 1);
        prog.setVar("radiusU", r);
        prog.drawBlocks(v);
        h->close();
        return v;
    }
    Texture guided(WhyToneState& st, const std::array<float, 4>& em, const std::array<float, 4>& lw, float ae, float center, const std::array<float, 6>& satLight) {
        GLProg& prog = *glProg;
        const Point lowSize = st.low->mSize;
        const int radius = GF_RADIUS > 1 ? GF_RADIUS >> 1 : 1;
        const float eps = GF_EPS;
        const Point half{(lowSize.x + 1) / 2, (lowSize.y + 1) / 2};
        Texture means[3];
        for (int mode = 0; mode < 3; mode++) {
            Texture down = stats(half);
            fusionProgram("why/gf", mode, *st.lowDn, em, lw, ae, center, satLight);
            prog.drawBlocks(down);
            means[mode] = box(down, radius);
        }
        Texture ab[2];
        for (int mode = 0; mode < 2; mode++) {
            Texture coef = stats(half);
            prog.setDefine("MODE", mode);
            prog.useAssetProgram("why/ab", false);
            prog.setTexture("MeanII", means[0]);
            prog.setTexture("MeanP", means[1]);
            prog.setTexture("MeanIP", means[2]);
            prog.setVar("epsU", eps);
            prog.drawBlocks(coef);
            ab[mode] = box(coef, radius);
        }
        for (auto& t : means) t->close();
        Texture fused = makeTexture(lowSize, GLFormat(DataType::FLOAT_32, 1), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        fusionProgram("why/gf", 3, *st.lowDn, em, lw, ae, center, satLight);
        prog.setTexture("MeanA", ab[0]);
        prog.setTexture("MeanB", ab[1]);
        prog.drawBlocks(fused);
        prog.closed = true;
        ab[0]->close();
        ab[1]->close();
        return fused;
    }
    void bandUniforms(const std::array<float, 6>& band, const std::array<float, 3>* nz, const std::optional<std::array<float, 2>>& noiseI,
                      const float* beta, int level, float lowCut) {
        GLProg& prog = *glProg;
        const auto ramp = WhyTone::edgeRamp(band, level);
        prog.setVar("bandEdgeU", ramp[0], ramp[1], band[2], band[3]);
        prog.setVar("bandReachU", int(band[4]), 0);
        prog.setVar("coneU", ramp[2], 0.f, 0.f, 0.f);
        if (nz) {
            prog.setVar("noiseBandU", (*nz)[0], (*nz)[1], (*noiseI)[0], (*noiseI)[1]);
            prog.setVar("noiseBetaU", beta[0], beta[1], beta[2], beta[3]);
        } else {
            prog.setVar("noiseBandU", 0.f, 0.f, 0.f, 0.f);
            prog.setVar("noiseBetaU", 0.f, 0.f, 0.f, 0.f);
        }
        prog.setVar("levelU", level);
        prog.setVar("lowCutU", lowCut);
    }
    void measureUniforms(const std::array<float, 6>& band, const std::optional<std::array<float, 2>>& noiseI, int level) {
        GLProg& prog = *glProg;
        prog.setVar("bandEdgeU", 0.f, 0.f, band[2], band[3]);
        prog.setVar("refScaleU", float(1 << REF_DOWN));
        const float k = WhyTone::bandsNoise();
        if (noiseI && k > 0.f) prog.setVar("bandNoiseU", k * WhyTone::edgeBeta(level), (*noiseI)[0], (*noiseI)[1]);
        else prog.setVar("bandNoiseU", 0.f, 0.f, 0.f);
    }
    void shareUniforms(const std::array<float, 6>& band, int level, bool coarsest) {
        GLProg& prog = *glProg;
        const auto ramp = WhyTone::edgeRamp(band, level), ramp0 = WhyTone::edgeRamp(band, 0);
        prog.setVar("bandEdgeU", ramp[0], ramp[1], band[2], band[3]);
        prog.setVar("bandReachU", coarsest ? -1 : int(band[4]), level == 1 ? int(band[4]) : 1);
        if (level == 1) prog.setVar("coneU", ramp[2], ramp0[0], ramp0[1], ramp0[2]);
        else prog.setVar("coneU", ramp[2], 0.f, 0.f, 0.f);
    }

    Texture pyramid(WhyToneState& st, const std::array<float, 4>& em, const std::array<float, 4>& lw, float ae, float center,
                    const std::array<float, 6>& satLight, float strength, const std::optional<std::array<float, 2>>& noiseI, bool aux,
                    const std::optional<std::array<float, 3>>& win, const Texture& fused31) {
        GLProg& prog = *glProg;
        const Point low = st.low->mSize;
        const float coarse = WhyTone::value("ef_coarse", COARSE);
        const auto bandOpt = WhyTone::bands();
        const bool bands = bandOpt.has_value();
        const std::array<float, 6> band = bandOpt ? *bandOpt : std::array<float, 6>{};
        int levels;
        float lowBand = 1.f;
        if (bands && WhyTone::depthSmooth()) {
            const auto d = depth(low.x, low.y, coarse);
            levels = std::min(int(d[0]), pyramidLevels(low.x, low.y, 2.f));
            if (levels == int(d[0])) lowBand = d[1];
        } else {
            levels = pyramidLevels(low.x, low.y, coarse);
        }
        const GLFormat rgba(DataType::FLOAT_16, 4), one(DataType::FLOAT_32, 1);
        const int deep = bands ? levels + REF_DOWN - 1 : levels;
        std::vector<Texture> L(size_t(levels + 1)), W(size_t(levels + 1)), F(size_t(levels + 1)), G(size_t(levels + 1)), D(size_t(levels + 1)), A(size_t(levels + 1));
        std::vector<Texture> GF(size_t(deep + 1));
        Texture D0;
        const auto edge = WhyTone::edgeShare();
        const bool edgeOn = edge[1] > edge[0];
        const auto nz = WhyTone::fusionNoise();
        const bool noiseCap = bands && noiseI && nz[1] > nz[0] && nz[2] > 0;
        float beta[4] = {0, 0, 0, 0};
        if (noiseCap) for (int l = 0; l < std::min(4, int(nz[2])); l++) beta[l] = WhyTone::BAND_BETA[l];
        Texture guidedF;
        const int winLevel = win && bands && fused31 ? windowLevel(low.x, deep) : 0;
        Texture out;
        Point size = low;
        const GLFormat r16(DataType::FLOAT_16, 1), rg16(DataType::FLOAT_16, 2);
        for (int l = 1; l <= deep; l++) {
            size = {(size.x + 1) / 2, (size.y + 1) / 2};
            if (bands) GF[size_t(l)] = makeTexture(size, rg16, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            if (l > levels) continue;
            L[size_t(l)] = makeTexture(size, rgba, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            W[size_t(l)] = makeTexture(size, rgba, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            F[size_t(l)] = makeTexture(size, one, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            if (edgeOn) G[size_t(l)] = makeTexture(size, r16, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            if (bands) {
                if (l < levels) D[size_t(l)] = makeTexture(size, r16, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
                A[size_t(l)] = makeTexture(size, rg16, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            }
        }
        fusionProgram("why/lpyr", 0, *st.lowDn, em, lw, ae, center, satLight);
        prog.drawBlocks(L[1]);
        fusionProgram("why/lpyr", 1, *st.lowDn, em, lw, ae, center, satLight);
        prog.drawBlocks(W[1]);
        for (int l = 2; l <= levels; l++)
            for (auto* p : {&L, &W}) {
                prog.setDefine("MODE", 2);
                gammaDefine();
                prog.useAssetProgram("why/lpyr", false);
                prog.setTexture("InputBuffer", (*p)[size_t(l - 1)]);
                prog.drawBlocks((*p)[size_t(l)]);
            }
        if (bands) {
            fusionProgram("why/lpyr", 7, *st.lowDn, em, lw, ae, center, satLight);
            prog.setVar("bandEdgeU", 0.f, 0.f, band[2], band[3]);
            prog.drawBlocks(GF[1]);
            for (int l = 2; l <= deep; l++) {
                prog.setDefine("MODE", 2);
                gammaDefine();
                prog.useAssetProgram("why/lpyr", false);
                prog.setTexture("InputBuffer", GF[size_t(l - 1)]);
                prog.drawBlocks(GF[size_t(l)]);
            }
            D0 = makeTexture(low, GLFormat(DataType::FLOAT_16, 1), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            fusionProgram("why/lpyr", 9, *st.lowDn, em, lw, ae, center, satLight);
            prog.setTexture("RefG", GF[size_t(REF_DOWN)]);
            measureUniforms(band, noiseI, 0);
            prog.drawBlocks(D0);
            for (int l = 1; l < levels; l++) {
                prog.setDefine("MODE", 8);
                gammaDefine();
                prog.useAssetProgram("why/lpyr", false);
                prog.setTexture("LevelGF", GF[size_t(l)]);
                prog.setTexture("RefG", GF[size_t(l + REF_DOWN)]);
                measureUniforms(band, noiseI, l);
                prog.drawBlocks(D[size_t(l)]);
            }
            for (int l = 1; l <= levels; l++) {
                prog.setDefine("MODE", 10);
                gammaDefine();
                prog.useAssetProgram("why/lpyr", false);
                prog.setTexture("FineA", l == 1 ? D0 : A[size_t(l - 1)]);
                if (l < levels) prog.setTexture("LevelA", D[size_t(l)]);
                shareUniforms(band, l, l == levels);
                prog.drawBlocks(A[size_t(l)]);
            }
            for (int l = 1; l < levels; l++) { D[size_t(l)]->close(); D[size_t(l)] = nullptr; }
            for (int l = levels + 1; l <= deep; l++) {
                if (l == winLevel) continue;
                GF[size_t(l)]->close();
                GF[size_t(l)] = nullptr;
            }
        }
        const float edgeScale = float(1 << levels);
        if (edgeOn) {
            fusionProgram("why/lpyr", 6, *st.lowDn, em, lw, ae, center, satLight);
            prog.setVar("edgeMixU", edge[0], edge[1], edge[2], edgeScale);
            prog.drawBlocks(G[1]);
            for (int l = 2; l <= levels; l++) {
                prog.setDefine("MODE", 2);
                gammaDefine();
                prog.useAssetProgram("why/lpyr", false);
                prog.setTexture("InputBuffer", G[size_t(l - 1)]);
                prog.drawBlocks(G[size_t(l)]);
                G[size_t(l - 1)]->close();
                G[size_t(l - 1)] = nullptr;
            }
        }
        if (bands) prog.setDefine("EDGE_BANDS", 1);
        prog.setDefine("MODE", 3);
        gammaDefine();
        prog.useAssetProgram("why/lpyr", false);
        prog.setTexture("LevelL", L[size_t(levels)]);
        prog.setTexture("LevelW", W[size_t(levels)]);
        if (bands) {
            prog.setTexture("LevelGF", GF[size_t(levels)]);
            prog.setTexture("LevelA", A[size_t(levels)]);
            prog.setVar("bandEdgeU", 0.f, 0.f, band[2], band[3]);
        }
        prog.drawBlocks(F[size_t(levels)]);
        for (int l = levels - 1; l >= 1; l--) {
            if (bands) prog.setDefine("EDGE_BANDS", 1);
            prog.setDefine("MODE", 4);
            gammaDefine();
            prog.useAssetProgram("why/lpyr", false);
            prog.setTexture("LevelL", L[size_t(l)]);
            prog.setTexture("LevelW", W[size_t(l)]);
            prog.setTexture("CoarseL", L[size_t(l + 1)]);
            prog.setTexture("CoarseF", F[size_t(l + 1)]);
            if (bands) {
                prog.setTexture("LevelGF", GF[size_t(l)]);
                prog.setTexture("CoarseGF", GF[size_t(l + 1)]);
                prog.setTexture("LevelA", A[size_t(l)]);
                bandUniforms(band, noiseCap ? &nz : nullptr, noiseI, beta, l, l == levels - 1 ? 1.f - lowBand : 0.f);
            }
            prog.drawBlocks(F[size_t(l)]);
        }
        const auto gmix = WhyTone::guidedLights();
        if (gmix[1] > gmix[0] || edgeOn) {
            for (auto& t : W) if (t) t->close();
            std::fill(W.begin(), W.end(), nullptr);
            guidedF = guided(st, em, lw, ae, center, satLight);
        }
        out = makeTexture(low, one, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        if (!aux) st.fused = out;
        const bool sky = !aux && guidedF && st.sky;
        if (sky) prog.setDefine("SKY", 1);
        if (bands) prog.setDefine("EDGE_BANDS", 1);
        if (winLevel > 0) prog.setDefine("WINDOW", 1);
        fusionProgram("why/lpyr", 5, *st.lowDn, em, lw, ae, center, satLight);
        if (winLevel > 0) {
            prog.setTexture("WinMean", GF[size_t(winLevel)]);
            prog.setTexture("Fused31", fused31);
            prog.setVar("winU", (*win)[0], (*win)[1], (*win)[2], 0.f);
            prog.setVar("winScaleU", float(1 << winLevel));
        }
        prog.setTexture("CoarseL", L[1]);
        prog.setTexture("CoarseF", F[1]);
        if (bands) {
            prog.setTexture("CoarseGF", GF[1]);
            prog.setTexture("LevelA", D0);
            bandUniforms(band, noiseCap ? &nz : nullptr, noiseI, beta, 0, levels == 1 ? 1.f - lowBand : 0.f);
        }
        if (guidedF) {
            prog.setTexture("GuidedF", guidedF);
            prog.setVar("guidedMixU", gmix[0], gmix[1]);
        } else {
            prog.setVar("guidedMixU", 0.f, 0.f);
        }
        if (sky) {
            prog.setTexture("WhySky", st.sky);
            prog.setVar("skyGuidedU", WhyTone::sky());
        }
        if (edgeOn) prog.setTexture("GuideC", G[size_t(levels)]);
        prog.setVar("edgeMixU", edgeOn ? edge[0] : 0.f, edgeOn ? edge[1] : 0.f, edgeOn ? edge[2] : 0.f, edgeScale);
        prog.drawBlocks(out);
        prog.closed = true;
        for (auto* p : {&L, &W, &F, &G, &GF, &D, &A}) for (auto& t : *p) if (t) t->close();
        if (D0) D0->close();
        if (guidedF) guidedF->close();
        if (aux) return out;
        logI("SCAM_PIPELINE", "WHY fusion (Laplacian pyramid) em=" + javaFloatString(em[0]) + "," + javaFloatString(em[1]) + "," + javaFloatString(em[2]) + ","
             + javaFloatString(em[3]) + " levels=" + std::to_string(levels) + " bracketDn=" + javaFloatString(st.lowDn != st.low ? strength : 0.f));
        return nullptr;
    }
};

} // namespace scam_post
