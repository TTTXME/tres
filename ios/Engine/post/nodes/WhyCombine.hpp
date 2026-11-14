// WhyCombine.java one to one (the default GL model): the WHY tone's final pass - the highlight landing mask, the fused luma's
// local contrast, the rim support tiles, the sun bloom and the opposed landing of ScamRgb, the sky, the colour look and the
// tone curve - into the next main; with Ultra HDR the sky term's ratio for the gain map (skyHdrRatio). «Портрет» as the Java: the
// skin-tone correction (PORTRAIT), «Маска лица» (FACE_MASK, why/facemask on the whyLow grid) and Face AWB (FACE_AWB). Not ported
// (stays off): the scene luma cache (the reference renders without it: the original colour evaluation). The film simulation's
// scene table (FILM_SIM) and «Плёночный LUT» (FILM_LUT) as the Java.
#pragma once

#include "WhyFusion.hpp"

namespace scam_post {

class WhyCombine : public Node {
public:
    WhyCombine() : Node("", "WhyCombine") {}
    void Compile() override {}

    static constexpr float NIGHT_DETAIL = 1.f;   // P672: the night floor off (V1 detail as V2)
    std::function<void(const std::string&, const Texture&)> stageHook; // the reference's ScamDiagnostics.gpu stages

    static std::optional<std::vector<float>> hlWeights(float sigmaU) {
        if (!Settings::current().scamDevSwitch("hlmask_fast", true)) return std::nullopt;
        const float sigma = std::max(sigmaU, 0.5f);
        const int radius = int(std::ceil(3.0 * double(sigma)));
        if (radius > 159) return std::nullopt;
        const float inv = -0.5f / (sigma * sigma);
        std::vector<float> w(160, 0.f);
        for (int i = 0; i <= radius; i++) w[size_t(i)] = float(std::exp(double(float(i * i) * inv)));
        return w;
    }
    static float detailBoost(float snr, float night) {
        float day;
        if (!(snr > 20.f)) day = 1.f;
        else if (snr >= 150.f) day = 1.7f;
        else day = float(1.0 + 0.7 * std::log(double(snr) / 20.0) / std::log(150.0 / 20.0));
        if (!(night > 1.f) || !(snr > 5.f)) return day;
        const float t = snr >= 15.f ? 1.f : float(std::log(double(snr) / 5.0) / std::log(3.0));
        return std::max(day, 1.f + (night - 1.f) * t);
    }
    static float nightDetail() {
        const float v = Settings::current().hybridValue("why_night_detail", NIGHT_DETAIL);
        return std::isnan(v) ? NIGHT_DETAIL : std::max(0.f, std::min(2.f, v));
    }
    /// with a film the gain and boost only on the share the simulation's strength leaves
    static float detailGain(float sharpGain, float snr, const FilmSettings* film) {
        const float boost = detailBoost(snr, nightDetail());
        if (!film) return sharpGain * boost;
        return 1.f + (sharpGain * boost - 1.f) * (1.f - film->strength);
    }
    /// P672b: V2's rule with the detail split 2 (no light-dependent boost), else detailGain
    static float splitGain(int split, float sharpGain, float snr, const FilmSettings* film) {
        return split == 2 && !film ? sharpGain : detailGain(sharpGain, snr, film);
    }
    /// WhyAe.filmAe: the meter before the highlight overflow's cut, within the noise clamp and the x9 cap
    static float filmAe(const WhyAe::Result& r) {
        float a = r.ae * r.deficit;
        if (r.maxGainByNoise > 0.f) a = std::min(a, std::max(r.ae, r.maxGainByNoise));
        return std::min(a, std::max(r.ae, 9.f));
    }
    /// P378: the highlight landing can be drawn: «Мягкость засветов» on and no film at 100 %
    static bool landingDrawn(float soft, const FilmSettings* film) { return soft > 0.f && !(film && film->strength >= 1.f); }
    static float hlSoft() { return std::max(0.f, std::min(0.9f, WhyTone::value("hl_soft", 0.7f))); }
    static int fastShortFrames(const std::string& report) {
        static const std::regex re("HYBRID FAST MERGE HIGHLIGHT: accumulated validity of (\\d+) valid short frame");
        int n = 0;
        for (auto it = std::sregex_iterator(report.begin(), report.end(), re); it != std::sregex_iterator(); ++it)
            n = std::max(n, std::atoi((*it)[1].str().c_str()));
        return n;
    }
    static float measuredShoulder(const WhyAe::Result& r, bool realClip, const std::string& report, bool on) {
        if (!on || !realClip || r.hr > 0.f || !(r.clip > 1.05f) || !(r.gmax > r.clip) || fastShortFrames(report) <= 0) return 0.f;
        return 1.f - std::max(0.f, std::min(1.f, r.nightW));
    }
    static float measuredCeiling(const WhyAe::Result& r, float measured, bool on) {
        if (!on || !(measured > 0.f) || !(r.gmax > r.clip) || !std::isfinite(r.gmax)) return 0.f;
        const float ceil = float(std::min(double(r.gmax), double(r.clip) * 16.0));
        return r.clip + (ceil - r.clip) * std::min(1.f, measured);
    }

    static Texture detailRef(GLProg& prog, PostPipeline& pipeline, GLTexture& input, GLTexture& gainMap, float detailClip) {
        WhyToneState& st = *pipeline.why;
        Texture ref = makeTexture(st.low->mSize, GLFormat(DataType::FLOAT_32, 1), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setDefine("DETAIL_REF", 1);
        prog.useAssetProgram("why/low", false);
        prog.setTexture("InputBuffer", input);
        WhyTone::setColour(prog, pipeline, gainMap, st.inScale);
        prog.setVar("factorU", st.factor);
        prog.setVar("detailClipU", detailClip);
        prog.drawBlocks(ref);
        return ref;
    }

    static std::unique_ptr<GLBuffer> rimSupport(GLProg& prog, GLTexture& low, float ae, bool sharp, float sharpTone) {
        const int width = (low.mSize.x + 7) / 8, height = (low.mSize.y + 7) / 8;
        GLint fragmentBlocks = 0;
        glGetIntegerv(GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, &fragmentBlocks);
        if (fragmentBlocks < 1) return nullptr;
        GLint previous[2] = {0, 0};
        glGetIntegerv(GL_SHADER_STORAGE_BUFFER_BINDING, &previous[0]);
        glGetIntegeri_v(GL_SHADER_STORAGE_BUFFER_BINDING, 0, &previous[1]);
        std::unique_ptr<GLBuffer> tiles;
        try {
            tiles = std::make_unique<GLBuffer>(width * height, GLFormat(DataType::UNSIGNED_32));
            prog.setLayout(8, 8, 1);
            prog.useAssetProgram("why/rim_support", true);
            prog.setTexture("WhyLow", low);
            prog.setVar("aeU", ae);
            prog.setVar("sharpU", sharp ? 1 : 0);
            prog.setVar("sharpToneU", sharpTone);
            prog.setBufferCompute("rimTiles", *tiles);
            glDispatchCompute(GLuint(width), GLuint(height), 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            if (glGetError() != GL_NO_ERROR) throw std::runtime_error("rim support GL error");
        } catch (const std::exception& e) {
            tiles.reset();
            logI("SCAM_PIPELINE", std::string("rim support failed: ") + e.what() + "; reference scan");
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, GLuint(previous[1]));
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, GLuint(previous[0]));
        prog.closed = true;
        return tiles;
    }

    /// the sky ratio grid's longer side at most (why/skyratio, PostPipeline.skyHdrRatio)
    static constexpr int SKY_RATIO_SIDE = 512;

    /// P378 review: the SDR's own ratio of the sky term into PostPipeline.skyHdrRatio - the combine program still bound draws whyLow
    /// (fU 1: no detail, no dither) with the sky term and without it, then why/skyratio takes their luminance ratio on a grid of
    /// whyLow (its longer side SKY_RATIO_SIDE) and it is read back
    void skyHdrRatio(PostPipeline& pipeline, WhyToneState& st, const WhyAe::Result& r, GLTexture& with, GLTexture& without, bool lightChroma,
                     bool skyMeasured) {
        GLProg& prog = *glProg;
        const Texture colour = st.lowDn ? st.lowDn : st.low;
        if (lightChroma) prog.setVar("lightChromaU", 0.f);
        prog.setTexture("InputBuffer", st.low);
        prog.setTexture("WhyColour", colour);
        prog.setVar("fU", 1);
        prog.setVar("colourFU", 1);
        prog.setVar("detailGainU", 0.f);
        prog.setVar("ditherU", 0);
        if (r.hr > 0.f) prog.setVar("hrDetailU", 0.f);
        if (skyMeasured) {
            prog.setVar("hlMeasDetailU", 0.f);
            prog.setVar("hlMeasGlareU", 0.f);
        }
        prog.drawBlocks(with);
        prog.setVar("skyU", 0.f, 0.f, 1.f, 1.f);
        prog.setVar("skyMU", 0.f, 0.f, 0.f, 1.f);
        prog.drawBlocks(without);
        const Point low = st.low->mSize;
        const int box = std::max(1, (std::max(low.x, low.y) + SKY_RATIO_SIDE - 1) / SKY_RATIO_SIDE);
        const Point size = WhyTone::reduced(low, box);
        Texture ratio = makeTexture(size, GLFormat(DataType::FLOAT_32, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        try {
            prog.useAssetProgram("why/skyratio", false);
            prog.setTexture("WithSky", with);
            prog.setTexture("WithoutSky", without);
            prog.setVar("boxU", box);
            prog.setVar("p3U", pipeline.p3Output ? 1 : 0);
            prog.setVar("epsU", 1.0f / 64.0f); // GainMapComputer.DECODE_OFFSET
            prog.drawBlocks(*ratio);
            std::vector<float> b(size_t(size.x) * size_t(size.y) * 4);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            ratio->textureBuffer(GLFormat(DataType::FLOAT_32, 4), b.data());
            std::vector<float> out(size_t(size.x) * size_t(size.y));
            for (size_t i = 0; i < out.size(); i++) {
                const float v = b[4 * i];
                out[i] = v > 0.f && v < 16.f ? v : 1.f;
            }
            pipeline.skyHdrRatio = std::move(out);
            pipeline.skyHdrRatioSize = size;
        } catch (...) {
            ratio->close();
            throw;
        }
        ratio->close();
    }

    /// P512 «Маска лица»: why/facemask.glsl on the whyLow grid - the raw mask (the faces' ellipses times their skin colour within
    /// each face's spread) and two cross-bilateral passes along x and y (sigma 0.4 % of the width, at least 2 texels); none when a
    /// pass fails (the photo is made without the mask)
    static Texture faceMaskTexture(GLProg& prog, const Texture& low, const PortraitSkinResult& p, float size, float follow) {
        const int n = PortraitSkin::MAX_FACES;
        std::vector<float> skin(size_t(n) * 4, 0.f), light(size_t(n), 0.f);
        for (int i = 0; i < p.count; i++) {
            const size_t k = size_t(i);
            const bool measured = std::isfinite(p.a[k]) && std::isfinite(p.b[k]);
            skin[k * 4] = measured ? p.a[k] : 0.f;
            skin[k * 4 + 1] = measured ? p.b[k] : 0.f;
            skin[k * 4 + 2] = measured ? 1.f : 0.f;
            skin[k * 4 + 3] = measured && std::isfinite(p.spread[k]) ? p.spread[k] : 0.02f;
            light[k] = measured && std::isfinite(p.luma[k]) && p.luma[k] > 0.f ? float(std::cbrt(double(p.luma[k]))) : 0.f;
        }
        const GLFormat rg(DataType::FLOAT_16, 2);
        Texture a, b;
        try {
            a = makeTexture(low->mSize, rg, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            b = makeTexture(low->mSize, rg, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            prog.setDefine("MODE", 0);
            prog.useAssetProgram("why/facemask", false);
            prog.setTexture("InputBuffer", low);
            prog.setVar("faceCountU", p.count);
            prog.setVarVec4s("faceBoxU", p.faces);
            prog.setVarVec4s("faceSkinU", skin);
            prog.setVarFloats("faceLightU", light);
            prog.setVar("faceMaskU", size, follow);
            prog.drawBlocks(a);
            const float sigma = std::max(2.f, 0.004f * float(low->mSize.x));
            for (int pass = 0; pass < 2; pass++) {
                prog.setDefine("MODE", 1);
                prog.useAssetProgram("why/facemask", false);
                prog.setTexture("InputBuffer", pass == 0 ? a : b);
                prog.setVar("dirU", pass == 0 ? 1 : 0, pass == 0 ? 0 : 1);
                prog.setVar("sigmaU", sigma);
                prog.drawBlocks(pass == 0 ? b : a);
            }
            prog.closed = true;
            b->close();
            return a;
        } catch (const std::exception& e) {
            logI("SCAM_PIPELINE", std::string("Portrait: face mask skipped: ") + e.what());
            if (a) a->close();
            if (b) b->close();
            return nullptr;
        }
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        auto stp = pipeline.why;
        if (!stp || !stp->ae || !stp->fused) throw std::runtime_error("WHY tone: no fusion (WhyStats/WhyFusion did not run)");
        WhyToneState& st = *stp;
        if (stageHook) { stageHook("WhyFused", st.fused); stageHook("WhyLumaS", st.lumaS); }
        Texture input = previousNode->WorkingTexture;
        const WhyAe::Result& r = *st.ae;
        Texture gainMap = pipeline.GainMap, fallback;
        if (!gainMap) {
            const float one[4] = {1.f, 1.f, 1.f, 1.f};
            fallback = makeTexture({1, 1}, GLFormat(DataType::FLOAT_16, 4), one, GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        const bool sharp = st.lumaS != nullptr;
        const float sharpMul = st.sharpMul;
        const FilmSettings* film = pipeline.film.get();
        const int split = WhyTone::detailSplit(sharp, film != nullptr, st.inputReleased);
        const float detail = sharp ? splitGain(split, WhyTone::value("sharp_gain", 1.0f), pipeline.mParameters.finishSnr, film) : 2.0f;
        const ColourLook look = WhyTone::colourLook();
        const int toneOp = WhyTone::toneOperator();
        const bool colourOn = WhyTone::colourLookOn(look, toneOp, r.sat);
        const float lightChroma = toneOp == ToneCurve::AGX ? WhyTone::lightChroma() : 0.f;
        // P369: with a film the scene-referred table (FILM_SIM); a film whose data cannot be read gives the photo no film
        const std::optional<FilmChain::Scene> scene = !film ? std::nullopt : FilmChain::scene(*film, pipeline.filmUserLut, pipeline.filmUserStrength);
        const bool filmOnly = scene && film->strength >= 1.f;
        const float filmExp = filmAe(r) / std::max(r.ae, 1e-6f) * FilmSim::METER_TO_FILM;
        // P134 «Плёночный LUT» (FILM_LUT) without a film only
        const std::optional<FilmChain::State> chain = scene ? std::nullopt
                                                            : std::optional<FilmChain::State>(FilmChain::current(nullptr, pipeline.filmUserLut, pipeline.filmUserStrength));
        const float lutStrength = chain ? chain->strength : 0.f;
        const std::string lutName = chain ? chain->key : "";
        const FilmLutP lut = lutStrength > 0.f && !lutName.empty() ? FilmChain::lut(lutName) : nullptr;
        GLuint lutTexture = 0;
        Texture hlA, hlB, sunBloom, oppShare, fusedLc, faceTex;
        std::unique_ptr<GLBuffer> rimTiles;
        GLint rimBindings[2] = {0, 0};
        bool rimBound = false;
        float measured = 0.f;
        std::string skyLog;
        Texture colour = st.lowDn ? st.lowDn : st.low;
        int colourFactor = st.factor;
        if (st.factor > 2) {
            st.mid = makeTexture(WhyTone::reduced(input->mSize, 2), GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("why/low", false);
            prog.setTexture("InputBuffer", input);
            WhyTone::setColour(prog, pipeline, *gainMap, st.inScale);
            prog.setVar("factorU", 2);
            prog.drawBlocks(st.mid);
            colour = st.mid;
            colourFactor = 2;
        }
        if (detail == 0.f && st.detailRef) { st.detailRef->close(); st.detailRef = nullptr; }
        if (detail != 0.f && !st.detailRef) st.detailRef = detailRef(prog, pipeline, *input, *gainMap, sharp ? st.sharpMul : r.ae);
        const float soft = hlSoft();
        const bool realClip = pipeline.merge.lastRealClip || pipeline.merge.lastBentoUsClipped > 0.f;
        measured = measuredShoulder(r, realClip, pipeline.merge.lastReport, WhyTone::hlMeasured());
        const float measCeil = measuredCeiling(r, measured, WhyTone::hlMeasuredMax());
        if (soft > 0.f && realClip && !filmOnly) {
            const Point hlSize = st.low->mSize;
            hlA = makeTexture(hlSize, GLFormat(DataType::FLOAT_16, 1), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            hlB = makeTexture(hlSize, GLFormat(DataType::FLOAT_16, 1), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            const float sigma = std::max(1.f, 0.012f * float(hlSize.x));
            prog.useAssetProgram("why/hlmask", false);
            prog.setTexture("InputBuffer", st.low);
            prog.setVar("invClipU", 1.f / std::max(std::max(std::max(r.clip, r.hr), measCeil), 1e-6f));
            prog.setVar("dirU", 0, 0);
            prog.drawBlocks(hlB);
            const auto hw = hlWeights(sigma);
            for (int pass = 0; pass < 2; pass++) {
                if (hw) prog.setDefine("FAST", 1);
                prog.useAssetProgram("why/hlmask", false);
                prog.setTexture("InputBuffer", pass == 0 ? hlB : hlA);
                prog.setVar("dirU", pass == 0 ? 1 : 0, pass == 0 ? 0 : 1);
                prog.setVar("sigmaU", sigma);
                if (hw) {
                    prog.setVarVec4s("weightsU", *hw);
                    prog.setVar("radiusU", int(std::ceil(3.0 * double(std::max(sigma, 0.5f)))));
                }
                prog.drawBlocks(pass == 0 ? hlA : hlB);
            }
            hlA->close();
            hlA = nullptr;
        }
        const float lc = WhyTone::localContrast();
        if (lc > 0.f && !filmOnly) {
            const GLFormat one16(DataType::FLOAT_16, 1);
            GLTexture lcA(st.fused->mSize, one16, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            GLTexture lcB(st.fused->mSize, one16, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            const float lcSigma = WhyTone::LOCAL_CONTRAST_SIGMA / float(std::max(1, st.factor));
            for (int pass = 0; pass < 2; pass++) {
                prog.setDefine("MODE", 0);
                prog.useAssetProgram("why/localcontrast", false);
                if (pass == 0) prog.setTexture("InputBuffer", st.fused); else prog.setTexture("InputBuffer", lcA);
                prog.setVar("dirU", pass == 0 ? 1 : 0, pass == 0 ? 0 : 1);
                prog.setVar("sigmaU", lcSigma);
                prog.drawBlocks(pass == 0 ? lcA : lcB);
            }
            fusedLc = makeTexture(st.fused->mSize, GLFormat(DataType::FLOAT_32, 1), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.setDefine("MODE", 1);
            prog.useAssetProgram("why/localcontrast", false);
            prog.setTexture("InputBuffer", st.fused);
            prog.setTexture("Blurred", lcB);
            prog.setVar("lcU", lc, WhyTone::LOCAL_CONTRAST_T);
            prog.drawBlocks(fusedLc);
        }
        // P512: «Маска лица» of «Портрет» (a face found and its skin measured) on the whyLow grid, before the combine's defines
        const bool faceShot = r.portraitFace && st.portrait && st.portrait->count > 0;
        const std::optional<std::array<float, 10>> faceMask = faceShot ? WhyTone::faceMask() : std::nullopt;
        if (faceMask) faceTex = faceMaskTexture(prog, st.low, *st.portrait, (*faceMask)[8], (*faceMask)[9]);
        if (WhyTone::rimSmooth() && st.factor > 1 && detail != 0.f && st.detailRef && st.low->mSize == st.detailRef->mSize
            && Settings::current().hybridSwitch("why_rim_support", true))
            rimTiles = rimSupport(prog, *st.low, r.ae, sharp, sharp ? r.ae / std::max(st.sharpMul, 1e-6f) : 1.f);
        if (hlB) prog.setDefine("HL_LANDING", 1);
        oppShare = pipeline.opposedShare;
        pipeline.opposedShare = nullptr;
        const auto oppSize = pipeline.opposedShareSize;
        if (oppShare && (!hlB || !oppSize || oppSize->x * input->mSize.y != oppSize->y * input->mSize.x)) { oppShare->close(); oppShare = nullptr; }
        if (oppShare) prog.setDefine("HL_OPPOSED", 1);
        const bool oppOn = oppShare != nullptr;
        if (colourOn) prog.setDefine("COLOUR_LOOK", 1);
        const bool portraitOn = st.portrait && st.portrait->corrected();
        if (portraitOn) prog.setDefine("PORTRAIT", 1);
        // P512: «Маска лица» and Face AWB of «Портрет» (a face found and its skin measured); P577: Face AWB's default 1
        if (faceTex) prog.setDefine("FACE_MASK", 1);
        std::optional<std::array<float, 2>> faceAwb;
        if (faceShot) faceAwb = PortraitSkin::faceAwb(&*st.portrait, std::max(0.f, std::min(1.f, WhyTone::value("portrait_awb", 1.f))));
        if (faceAwb && (*faceAwb)[0] == 0.f && (*faceAwb)[1] == 0.f) faceAwb.reset();
        if (faceAwb) prog.setDefine("FACE_AWB", 1);
        if (faceShot) {
            std::string mask = "off";
            if (faceMask) {
                mask = "[";
                for (size_t i = 0; i < faceMask->size(); i++) mask += (i ? ", " : "") + javaFloatString((*faceMask)[i]);
                mask += "]";
            }
            char awb[64] = "none";
            if (faceAwb) std::snprintf(awb, sizeof awb, "%+.4f %+.4f", double((*faceAwb)[0]), double((*faceAwb)[1]));
            logI("SCAM_PIPELINE", "Portrait: mask " + mask + ", Face AWB " + awb);
        }
        if (toneOp != ToneCurve::AGX) prog.setDefine("TONE_OP", 1);
        if (lut) prog.setDefine("FILM_LUT", 1);
        if (scene) prog.setDefine("FILM_SIM", 1);
        if (r.hr > 0.f) prog.setDefine("HEADROOM_WIDE", 1);
        if (measured > 0.f) prog.setDefine("HL_MEASURED", 1);
        sunBloom = pipeline.sunBloom;
        pipeline.sunBloom = nullptr;
        const auto bloomSize = pipeline.sunBloomSize;
        if (sunBloom && (filmOnly || !bloomSize || bloomSize->x * input->mSize.y != bloomSize->y * input->mSize.x)) { sunBloom->close(); sunBloom = nullptr; }
        if (sunBloom) prog.setDefine("SUN_BLOOM", 1);
        const bool sunBloomOn = sunBloom != nullptr;
        if (st.sky) prog.setDefine("SKY", 1);
        if (WhyTone::macroToe()) prog.setDefine("MACRO_TOE", 1);
        if (lightChroma > 0.f) prog.setDefine("LIGHT_CHROMA", 1);
        if (WhyTone::v2Colour()) prog.setDefine("V2_COLOUR", 1);
        if (WhyTone::yellowHue() > 0.f) prog.setDefine("YELLOW_HUE", 1);
        if (WhyTone::hlNeutral() > 0.f) prog.setDefine("HL_NEUTRAL", 1);
        const std::optional<std::array<float, 4>> lightWarm = toneOp != ToneCurve::AGX ? std::optional<std::array<float, 4>>(WhyTone::lightWarm()) : std::nullopt;
        if (lightWarm && (*lightWarm)[0] > 0.f) prog.setDefine("LIGHT_WARM", 1);
        const auto hlLocal = WhyTone::hlLocal();
        if (hlLocal[0] > 0.f) prog.setDefine("HL_LOCAL", 1);
        if (hlLocal[0] > 0.f && WhyTone::hlLocalRim()) prog.setDefine("HL_LOCAL_RIM", 1);
        if (hlLocal[0] > 0.f && WhyTone::hlLocalSmooth()) prog.setDefine("HL_LOCAL_SMOOTH", 1);
        if (WhyTone::rimFar()) prog.setDefine("RIM_FAR_SIDE", 1);
        const float nightFade = 1.f - std::max(0.f, std::min(1.f, r.nightW)) * WhyTone::hlLocalNight();
        if (WhyTone::whyLayout()) prog.setDefine("WHY_BASE", 1);
        if (WhyTone::insetStrength() < 0.999f) prog.setDefine("AGX_INSET", 1);
        if (WhyTone::gammaType() != 0) prog.setDefine("GAMMA_TYPE", WhyTone::gammaType());
        const auto whyVib = WhyTone::whyVibrance();
        if (whyVib) prog.setDefine("WHY_VIB", 1);
        const bool whyVibrance = WhyTone::whyVibranceOn();
        if (whyVibrance) prog.setDefine("WHY_VIBRANCE", 1);
        if (WhyTone::edgeHold() > 0.f && !st.inputReleased && !filmOnly) prog.setDefine("EDGE_HOLD", 1);
        if (WhyTone::rimSmooth()) prog.setDefine("RIM_SMOOTH", 1);
        if (split > 0) prog.setDefine("DETAIL_SPLIT", 1);
        prog.setDefine("DITHER_TPDF", 1);
        if (st.sky) {
            skyLog = " sky=" + std::to_string(javaRound(100.f * st.skyMask->coverage)) + "%";
            if (st.skyMask->keep < 1.f) skyLog += " backlit (level x" + javaFloatString(float(javaRound(100.f * st.skyMask->keep)) / 100.f) + ")";
        }
        // P378 review: Ultra HDR keeps the sky term's change - the combine at whyLow with and without it (skyHdrRatio), its targets
        // made before the combine's samplers are bound: RGBA8, none above 100 MP (ScamRgb.earlyRelease)
        Texture skyWith, skyWithout;
        if (st.sky && st.skyMask->keep > 0.f && pipeline.captureDemosaic && !filmOnly) {
            const bool earlyRelease = int64_t(input->mSize.x) * input->mSize.y > 100000000LL
                                      || Settings::current().scamDevSwitch("early_release", false);
            if (earlyRelease) skyLog += " hdrSkyRatio=skipped (over 100 MP)";
            else {
                skyWith = makeTexture(st.low->mSize, GLFormat(DataType::SIMPLE_8, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
                skyWithout = makeTexture(st.low->mSize, GLFormat(DataType::SIMPLE_8, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            }
        }
        WorkingTexture = pipeline.getMain();
        if (rimTiles) prog.setDefine("RIM_SUPPORT", 1);
        const int stop = javaRound(Settings::current().scamDevNumber("why_combine_stop", 0.f));
        if (stop > 0) prog.setDefine("WHY_STOP", stop);
        prog.useAssetProgram("why/combine", false);
        if (rimTiles) {
            glGetIntegerv(GL_SHADER_STORAGE_BUFFER_BINDING, &rimBindings[0]);
            glGetIntegeri_v(GL_SHADER_STORAGE_BUFFER_BINDING, 0, &rimBindings[1]);
            rimBound = true;
            prog.setBufferCompute("rimTiles", *rimTiles);
        }
        if (sunBloom) {
            const int block = std::max(1, pipeline.sunBloomBlock);
            prog.setTexture("SunBloom", sunBloom);
            prog.setVar("sunGridU", float(bloomSize->x) / float(sunBloom->mSize.x * block), float(bloomSize->y) / float(sunBloom->mSize.y * block));
            prog.setVar("sunBloomU", WhyTone::sunBloom()[0]);
            const auto sunGate = WhyTone::sunBloomGate(pipeline);
            prog.setVar("sunGateU", sunGate[0], sunGate[1]);
        }
        if (oppShare) {
            const int block = std::max(1, pipeline.opposedShareBlock);
            prog.setTexture("OppShare", oppShare);
            prog.setVar("oppGridU", float(oppSize->x) / float(oppShare->mSize.x * block), float(oppSize->y) / float(oppShare->mSize.y * block));
        }
        if (lut) {
            lutTexture = lut->upload();
            prog.setTexture3D("FilmLut", lutTexture);
            prog.setVar("lutU", lutStrength, float(lut->size));
            prog.setVar("lutMinU", lut->domainMin[0], lut->domainMin[1], lut->domainMin[2]);
            prog.setVar("lutScaleU", 1.f / (lut->domainMax[0] - lut->domainMin[0]), 1.f / (lut->domainMax[1] - lut->domainMin[1]),
                        1.f / (lut->domainMax[2] - lut->domainMin[2]));
        }
        if (scene) {
            // P369: the film's exposure is the meter's before the highlight overflow's cut (ae x deficit)
            lutTexture = scene->lut->upload();
            prog.setTexture3D("FilmLut", lutTexture);
            prog.setVar("lutU", scene->strength, float(scene->lut->size));
            prog.setVar("filmExpU", filmExp);
            prog.setVar("filmLogU", FilmSim::LOG_LO, FilmSim::LOG_SPAN);
        }
        prog.setTexture("InputBuffer", st.inputReleased ? st.lumaS : input);
        WhyTone::setColour(prog, pipeline, *gainMap, st.inScale);
        prog.setTexture("WhyLow", st.low);
        prog.setTexture("WhyColour", colour);
        prog.setTexture("WhyFused", fusedLc ? fusedLc : st.fused);
        prog.setTexture("WhyDetailRef", st.detailRef ? st.detailRef : st.low);
        prog.setVar("detailRefU", st.detailRef ? 1 : 0);
        prog.setTexture("WhyLumaS", sharp ? st.lumaS : st.low);
        if (hlB) prog.setTexture("WhyHl", hlB);
        if (st.sky) {
            const float sky = WhyTone::sky();
            const auto tone = WhyTone::skyTone();
            const auto tops = st.skyMask->tops();
            prog.setTexture("WhySky", st.sky);
            prog.setVar("skyU", sky * st.skyMask->keep, tone[0], tone[1], tone[2]);
            prog.setVar("skyMU", sky, sky, sky * WhyTone::SKY_GLOW, WhyTone::SKY_VIBRANCE);
            prog.setVar("skyTopU", tops[0], tops[1]);
        }
        prog.setVar("sharpU", sharp ? 1 : 0);
        prog.setVar("fullInputU", st.inputReleased ? 0 : 1);
        if (WhyTone::edgeHold() > 0.f && !st.inputReleased && !filmOnly) {
            prog.setVar("edgeHoldU", WhyTone::edgeHold());
            prog.setVar("edgeHoldLightU", WhyTone::edgeHoldLight());
        }
        prog.setVar("sharpToneU", sharp ? r.ae / std::max(st.sharpMul, 1e-6f) : 1.f);
        prog.setVar("fU", st.factor);
        prog.setVar("colourFU", colourFactor);
        prog.setVar("signedColourU", pipeline.signedRgb ? 1 : 0);
        prog.setVar("signedHueU", WhyTone::signedHue() && !WhyTone::v2Colour() ? 1 : 0);
        prog.setVar("aeU", r.ae);
        prog.setVar("clipU", r.clip);
        if (WhyTone::insetStrength() < 0.999f) prog.setVar("insetU", WhyTone::insetStrength());
        if (whyVib) {
            prog.setVar("whyVibU", std::vector<float>((*whyVib)[0].begin(), (*whyVib)[0].end()));
            prog.setVar("whySkinU", std::vector<float>((*whyVib)[1].begin(), (*whyVib)[1].begin() + 3));
        }
        if (whyVibrance) {
            const auto vw = WhyTone::vibranceWhyUniform(), vs = WhyTone::vibranceSkinUniform();
            prog.setVar("vibWhyU", std::vector<float>(vw.begin(), vw.end()));
            prog.setVar("vibSkinU", std::vector<float>(vs.begin(), vs.end()));
            prog.setVar("vibSkinHueU", WhyTone::vibranceSkinHue());
        }
        if (r.hr > 0.f) {
            const auto hrTone = WhyTone::headroomTone();
            prog.setVar("hrU", r.hr);
            prog.setVar("hrKneeU", hrTone[0]);
            prog.setVar("hrRoomU", hrTone[1]);
            prog.setVar("hrEaseU", 1.f - WhyTone::shoulderStrength());
            prog.setVar("hrDetailU", st.inputReleased ? 0.f : hrTone[2] * nightFade);
        }
        if (measured > 0.f) {
            const auto hrTone = WhyTone::headroomTone();
            prog.setVar("hlMeasU", WhyTone::HL_MEASURED_LO, WhyTone::HL_MEASURED_HI, measured, WhyTone::hlMeasuredLanding());
            prog.setVar("hlMeasShoulderU", hrTone[0], hrTone[1], 1.f - WhyTone::shoulderStrength(), WhyTone::hlMeasuredMax() ? 1.f : 0.f);
            prog.setVar("hlMeasCeilU", measCeil);
            prog.setVar("hlMeasSceneU", WhyTone::hlMeasuredScene() ? 1.f : 0.f);
            prog.setVar("hlMeasDetailU", st.inputReleased ? 0.f : WhyTone::hlMeasuredDetail());
            prog.setVar("hlMeasGlareU", st.inputReleased ? 0.f : WhyTone::hlMeasuredGlare());
        }
        prog.setVar("headroomU", realClip ? 1 : 0);
        prog.setVar("hlWhiteU", WhyTone::hlWhite());
        if (lightChroma > 0.f) prog.setVar("lightChromaU", lightChroma);
        if (hlB) prog.setVar("hlSoftU", soft);
        prog.setVar("toeU", WhyTone::value("aces_toe", 0.05f));
        prog.setVar("gammaInvU", 1.f / WhyTone::gamma());
        prog.setVar("macroU", r.portraitFace ? 1.f : WhyTone::value("macro_contrast", 1.1f));
        if (portraitOn) {
            prog.setVar("portraitCountU", st.portrait->count);
            prog.setVarVec4s("portraitFacesU", st.portrait->faces);
            prog.setVarVec4s("portraitAdjustU", st.portrait->adjust);
            prog.setVar("portraitMapU", std::vector<float>{1, 0, 0, 0, 1, 0, 0, 0, 1});
        }
        if (faceTex) {   // P512
            prog.setTexture("FaceMask", faceTex);
            prog.setVar("faceAU", (*faceMask)[0], (*faceMask)[1], (*faceMask)[2], (*faceMask)[3]);
            prog.setVar("faceBU", (*faceMask)[4], (*faceMask)[5], (*faceMask)[6], (*faceMask)[7]);
        }
        if (faceAwb) prog.setVar("faceAwbU", (*faceAwb)[0], (*faceAwb)[1]);   // P512
        prog.setVar("vibU", WhyTone::value("vibrance", 0.f), WhyTone::value("vibrance_sky", 0.25f), WhyTone::value("vibrance_green", 0.05f));
        prog.setVar("vibHlU", WhyTone::vibranceHl());
        const auto shape = WhyTone::vibranceHlShape();
        prog.setVar("vibHlShapeU", std::vector<float>(shape.begin(), shape.end()));
        if (WhyTone::yellowHue() > 0.f) prog.setVar("yellowHueU", WhyTone::yellowHue());
        if (WhyTone::hlNeutral() > 0.f) prog.setVar("hlNeutralU", WhyTone::hlNeutral());
        if (lightWarm && (*lightWarm)[0] > 0.f) prog.setVar("lightWarmU", (*lightWarm)[0], (*lightWarm)[1], (*lightWarm)[2], (*lightWarm)[3]);
        if (hlLocal[0] > 0.f) prog.setVar("hlLocalU", hlLocal[0] * nightFade, hlLocal[1], hlLocal[2], hlLocal[3]);
        prog.setVar("chromaDnU", 0.f);
        prog.setVar("clarityU", WhyTone::value("clarity", 0.f));
        prog.setVar("flatProtectU", WhyTone::value("flat_protect", 0.f));
        prog.setVar("detailGainU", detail);
        prog.setVar("detailSplitU", split);
        prog.setVar("deltaChromaU", WhyTone::deltaChroma());
        prog.setVar("filmToeU", WhyTone::value("film_toe", 0.f));
        prog.setVar("agxAU", r.slope, r.sp, r.tp, r.sat);
        prog.setVar("agxBU", r.minEv, r.maxEv, r.ev, toneOp == ToneCurve::AGX ? float(r.look) : 0.f);
        if (toneOp != ToneCurve::AGX) {
            prog.setVar("toneOpU", toneOp);
            const auto aces = WhyTone::acesUniform();
            prog.setVar("acesU", std::vector<float>(aces.begin(), aces.end()));
            const auto uchi = WhyTone::uchimuraUniforms();
            prog.setVar("uchiAU", std::vector<float>(uchi[0].begin(), uchi[0].end()));
            prog.setVar("uchiBU", std::vector<float>(uchi[1].begin(), uchi[1].end()));
            prog.setVarVec4s("curveU", WhyTone::curveSamples(r.portraitFace));
            prog.setVar("curveBoostU", WhyTone::curveBoost(r.contrastC));
        }
        prog.setVar("ditherU", 1);
        if (colourOn) {
            const auto skin = look.skinUniform();
            prog.setVar("skinU", std::vector<float>(skin.begin(), skin.end()));
            const auto vibL = look.vibUniform();
            prog.setVar("vibLU", std::vector<float>(vibL.begin(), vibL.end()));
            prog.setVarFloats("mixU", look.mixUniform());
            prog.setVar("mixOnU", look.mixerOn() ? 1 : 0);
            const auto mono = look.monoUniform();
            prog.setVar("monoU", std::vector<float>(mono.begin(), mono.end()));
            prog.setVar("gamutU", std::max(0.f, std::min(1.f, look.gamut / 100.f)));
            prog.setVar("hueLockU", look.hueLock);
            const auto grade = look.gradeUniforms();
            prog.setVar("gradeAU", std::vector<float>(grade[0].begin(), grade[0].end()));
            prog.setVar("gradeBU", std::vector<float>(grade[1].begin(), grade[1].end()));
        }
        prog.drawBlocks(WorkingTexture);
        if (rimTiles) logI("SCAM_PIPELINE", "WHY rim support tiles=" + std::to_string(rimTiles->size));
        if (skyWith) {
            try {
                skyHdrRatio(pipeline, st, r, *skyWith, *skyWithout, lightChroma > 0.f, measured > 0.f);
                if (pipeline.skyHdrRatio)
                    skyLog += " hdrSkyRatio=" + std::to_string(pipeline.skyHdrRatioSize->x) + "x" + std::to_string(pipeline.skyHdrRatioSize->y);
            } catch (const std::exception& e) {
                // the photo stands; its gain map takes the scene as before
                pipeline.skyHdrRatio.reset();
                pipeline.skyHdrRatioSize.reset();
                logI("SCAM_PIPELINE", std::string("WHY combine: the sky's Ultra HDR ratio failed (") + e.what() + ")");
            }
            skyWith->close();
            skyWithout->close();
        }
        prog.closed = true;
        if (rimTiles) rimTiles.reset();
        if (rimBound) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, GLuint(rimBindings[1]));
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, GLuint(rimBindings[0]));
        }
        st.closeTextures();
        for (Texture* t : {&fallback, &hlA, &hlB, &fusedLc, &sunBloom, &oppShare, &faceTex}) if (*t) (*t)->close();
        if (lutTexture) glDeleteTextures(1, &lutTexture);
        logI("SCAM_PIPELINE", "WHY combine ae=" + javaFloatString(r.ae) + " clip=" + javaFloatString(r.clip) + " headroom=" + (realClip ? "true" : "false")
             + (WhyTone::v2Colour() ? " v2Colour" : "") + " hlWhite=" + javaFloatString(WhyTone::hlWhite()) + " hlSoft=" + javaFloatString(WhyTone::value("hl_soft", 0.7f))
             + (lightChroma > 0.f ? " lightChroma=" + javaFloatString(lightChroma) : "") + " grid=" + std::to_string(input->mSize.x) + "x" + std::to_string(input->mSize.y)
             + " lowFactor=" + std::to_string(st.factor) + (st.factor > 2 ? " colour=whyMid" : " colour=whyLow") + " detail=" + javaFloatString(detail) + (sharp ? " (WhyLumaS, mul " + javaFloatString(sharpMul) + ")" : "")
             + (sunBloomOn ? " sunBloom=" + javaFloatString(WhyTone::sunBloom()[0]) + "@" + javaFloatString(WhyTone::sunBloom()[1]) : "")
             + (oppOn ? " opposedLanding" : "") + skyLog + (colourOn ? " colourLook" : "")
             + (lut ? " filmLut=" + lutName + " " + std::to_string(lut->size) + "^3 x" + javaFloatString(lutStrength)
                    : lutStrength > 0.f && !lutName.empty() ? " filmLut=" + lutName + " unreadable" : "")
             + (scene ? " filmSim=" + scene->key + " x" + javaFloatString(scene->strength) + " exp=" + javaFloatString(filmExp) + (filmOnly ? " (film only)" : "")
                      : film ? " filmSim=" + film->lutKey() + " film data unreadable: no film" : ""));
    }
};

} // namespace scam_post
