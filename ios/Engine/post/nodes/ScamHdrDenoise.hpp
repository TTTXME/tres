// ScamHdrDenoise.java + WhyResolveDenoise.java + PostSnr.java one to one: the reference's three 1/2/4-pixel YUV bilateral passes on
// the merged linear RGB (strips of rows), the effective-frame map as N, the finish SNR measured on the input; P648c «Подстройка под
// свет (SNR)»: the night's flat-area smoothing by the shot's SNR.
#pragma once

#include "../DenoiseStats.hpp"

namespace scam_post {

/// PostSnr.java (P648 «Подстройка под свет (SNR)»): the Resolve's night smoothing of the flat areas by the shot's finish SNR
namespace PostSnr {
/// the SNR from which the shot is daylight (no change) and the one at and below which the night's full share applies
constexpr float DAY = 30.f, NIGHT = 6.f;
/// the flat smoothing's night share, the factor on a flat pixel and the even grain given back there (why_resolve uniforms)
constexpr float FLAT = 1.f, FLAT_BOOST = 3.f, FLAT_GRAIN = 1.f;

/// the switch (pref_scam_hybrid_post_snr, on by default; scam_dev.txt "hybrid_post_snr 0" too)
inline bool on() { return Settings::current().hybridSwitch("post_snr", true); }

/// 0 at DAY and above and for an unknown SNR, 1 at NIGHT and below, a smoothstep of log SNR between
inline float night(float snr) {
    if (!(snr > 0.f) || std::isinf(snr) || snr >= DAY) return 0.f;
    if (snr <= NIGHT) return 1.f;
    const double t = std::log(double(DAY / snr)) / std::log(double(DAY / NIGHT));
    return float(t * t * (3.0 - 2.0 * t));
}

/// the flat-area smoothing's share for this shot: FLAT x night; 0 off, by day and without the measured noise
inline float flatNight(float snr, bool on, float noiseS, float noiseO) {
    if (!on || !(noiseS >= 0.f) || !(noiseO >= 0.f) || !(noiseS + noiseO > 0.f)) return 0.f;
    return FLAT * night(snr);
}

/// the shot's line of the log (once per shot, ScamHdrDenoise)
inline std::string describe(float snr, bool on) {
    if (!on) return "post SNR (P648): off";
    const float n = night(snr);
    char b[160];
    std::snprintf(b, sizeof b, "post SNR (P648): snr=%.2f night=%.3f flat %.3f (factor up to x%.1f, grain %.1f)", double(snr), double(n),
                  double(FLAT * n), double(FLAT_BOOST), double(FLAT_GRAIN));
    return b;
}
} // namespace PostSnr

namespace WhyResolveDenoise {
constexpr float FACTOR = 0.4f, LUMA_REVERT = 0.85f, MOTION_REVERT = 0.5f, GRAIN = 0.15f, OUTLIER = 0.125f;
constexpr float FALLBACK_S = 1.5e-4f, FALLBACK_O = 1e-5f;

inline float vignetteBoost(float noiseS, float noiseO) {
    const double snr = 0.18 / std::sqrt(std::max(0.18 * double(std::max(noiseS, 0.f)) + double(std::max(noiseO, 0.f)), 1e-12));
    if (snr <= 1.0) return 4.f;
    if (snr < 4.0) return float(5.3333 - 1.3333 * snr);
    return 0.f;
}
inline std::array<float, 4> noiseModel(float noiseS, float noiseO) { return {0.562f * noiseS, 0.25f * noiseS, 0.25f * noiseS, noiseO}; }
inline float defaultFrames(float median, int normals) {
    const int total = std::max(1, normals);
    const float n = median > 0.f && std::isfinite(median) ? median : float(total);
    return std::max(1.f, std::min(float(total), n));
}
inline std::array<float, 2> shotNoise(const PostPipeline& p) {
    const Parameters& par = p.mParameters;
    if (!par.noiseModeler || par.noiseModeler->baseModel.size() < 2) return {FALLBACK_S, FALLBACK_O};
    float s = float(std::max(1e-9, par.noiseModeler->baseModel[1].first)), o = float(std::max(1e-12, par.noiseModeler->baseModel[1].second));
    const float k = p.merge.lastNoiseFactor;
    if (k != 1.f) { s *= k; o *= k; }
    return {s, o};
}
inline float clampf(float v, float lo, float hi) { return std::isnan(v) ? lo : std::max(lo, std::min(hi, v)); }
inline float effScale(float median) { return median > 0.f && !std::isinf(median) ? median / 64.f : NAN; }
inline int stripRows(int width, int height) {
    const int64_t budget = int64_t(128) << 20;
    return std::max(1, std::min(height, int(std::max<int64_t>(1, budget / (std::max<int64_t>(1, width) * 16) - 14))));
}
inline void drawRegion(GLProg& prog, GLTexture& target, int y, int height) {
    target.BufferLoad();
    for (int row = 0; row < height; row += 256) {
        glViewport(0, y + row, target.mSize.x, std::min(256, height - row));
        prog.draw();
    }
    glFlush();
}

inline Texture process(GLProg& prog, PostPipeline& pipeline, const Texture& input) {
    Settings& prefs = Settings::current();
    const Parameters& par = pipeline.mParameters;
    const Point size = input->mSize;
    const Point sensorSize = par.labWholeSize ? *par.labWholeSize : size;
    const int sensorX = par.labOrigin ? javaRound(par.labOrigin->x * float(sensorSize.x)) : 0;
    const int sensorY = par.labOrigin ? javaRound(par.labOrigin->y * float(sensorSize.y)) : 0;
    const int s = std::max(1, std::min(2, javaRound(par.outputScale)));
    const float factor = clampf(prefs.hybridValue("why_dn_factor", FACTOR), 0.f, 2.f);
    const float lumaRevert = clampf(prefs.hybridValue("why_dn_luma_revert", LUMA_REVERT), 0.f, 1.f);
    // P648: with «Подстройка под свет» the night's flat-area smoothing follows the shot's SNR (PostSnr; none by day, nor off)
    const bool adapt = PostSnr::on();
    const float motionRevert = clampf(prefs.hybridValue("why_dn_motion_revert", MOTION_REVERT), 0.f, 1.f);
    const float grain = clampf(prefs.hybridValue("why_dn_grain", GRAIN), 0.f, 2.f);
    std::string log = "why-resolve grid=" + std::to_string(s) + "x";
    // the SNR finished before the passes (Java with the switch on; off, it ends after them - the same values, the log's order)
    const float shotSnr = DenoiseStats::finishSnr(prog, pipeline, *input, &log);
    // P648c: the night's flat-area smoothing (only where the merged luma is flat against its measured noise; 0: none)
    const float flatNight = PostSnr::flatNight(adapt ? shotSnr : NAN, adapt, par.finishNoiseS, par.finishNoiseO);
    const auto so = shotNoise(pipeline);
    const auto model = noiseModel(so[0], so[1]);
    const float boost = vignetteBoost(so[0], so[1]);
    const int normals = std::max(1, pipeline.merge.lastNormals);
    const float median = pipeline.merge.lastEffectiveMedian;
    const float frames = defaultFrames(median, normals);
    const float scale = effScale(median);
    Texture effMap;
    const auto& eff = pipeline.merge.lastEffectiveFrames;
    if (eff && !std::isnan(scale) && eff->size() == size_t(size.x) * size_t(size.y)) effMap = DenoiseStats::effectiveFramesTexture(size, *eff);
    Texture placeholder;
    if (!effMap) placeholder = makeTexture({1, 1}, GLFormat(DataType::UNSIGNED_8, 1), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
    const Texture& effTex = effMap ? effMap : placeholder;
    const int rows = stripRows(size.x, size.y);
    const Point tile{size.x, std::min(size.y, rows + 14)};
    Texture p0 = makeTexture(tile, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
    Texture p1 = makeTexture(tile, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
    Texture out = pipeline.getMain();
    for (int y = 0; y < size.y; y += rows) {
        const int count = std::min(rows, size.y - y);
        const int origin = std::max(0, y - 7);
        const int height = std::min(size.y, y + count + 7) - origin;
        Texture source = input;
        for (int pass = 0; pass < 3; pass++) {
            const Texture& target = pass == 0 ? p0 : pass == 1 ? p1 : out;
            prog.useAssetProgram("scamdn/why_resolve", false);
            prog.setTexture("InputBuffer", source);
            prog.setTexture("EffMap", effTex);
            prog.setVar("useEffU", effMap ? 1 : 0);
            prog.setVar("effScaleU", std::isnan(scale) ? 0.f : scale);
            prog.setVar("framesU", frames);
            prog.setVar("totalU", float(normals));
            prog.setVar("fullSizeU", size.x, size.y);
            prog.setVar("sensorSizeU", sensorSize.x, sensorSize.y);
            prog.setVar("sensorOriginU", sensorX, sensorY);
            prog.setVar("inputOriginU", pass == 0 ? 0 : origin);
            prog.setVar("outputOriginU", pass == 2 ? 0 : origin);
            prog.setVar("passU", pass);
            prog.setVar("stepU", 1 << pass);
            prog.setVar("noiseModelU", model[0], model[1], model[2], model[3]);
            prog.setVar("factorU", factor);
            prog.setVar("flatNightU", flatNight);
            prog.setVar("flatBoostU", PostSnr::FLAT_BOOST);
            prog.setVar("flatGrainU", PostSnr::FLAT_GRAIN);
            prog.setVar("flatNoiseU", flatNight > 0.f ? par.finishNoiseS : 0.f, flatNight > 0.f ? par.finishNoiseO : 0.f);
            prog.setVar("outlierU", OUTLIER);
            prog.setVar("rejectRevertU", motionRevert);
            prog.setVar("globalRevertU", lumaRevert);
            prog.setVar("grainU", grain);
            prog.setVar("vignetteBoostU", boost);
            prog.setVar("vignetteScaleU", 1.f);
            if (pass < 2) prog.drawBlocks(*target, Point{size.x, height});
            else drawRegion(prog, *target, y, count);
            source = target;
        }
    }
    log += " factor=" + javaFloatString(factor) + " flat=" + javaFloatString(flatNight) + " revert=" + javaFloatString(lumaRevert) + "/" + javaFloatString(motionRevert) + " grain="
           + javaFloatString(grain) + " S=" + javaFloatString(so[0]) + " O=" + javaFloatString(so[1]) + " vignette=" + javaFloatString(boost)
           + " rows=" + std::to_string(rows) + " N=" + (effMap ? "map(x" + javaFloatString(scale) + ")" : std::string("flat")) + " default="
           + javaFloatString(frames) + " total=" + std::to_string(normals);
    logI("SCAM_PIPELINE", log);
    for (Texture* t : {&effMap, &placeholder, &p0, &p1}) if (*t) (*t)->close();
    return out;
}
} // namespace WhyResolveDenoise

class ScamHdrDenoise : public Node {
public:
    ScamHdrDenoise() : Node("", "ScamHdrDenoise") {}
    void Compile() override {}
    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        WorkingTexture = previousNode->WorkingTexture;
        glProg->closed = true;
        if (!Settings::current().hybridSwitch("denoise", true)) {
            std::string log = "denoise off (P600): the merge as it is";
            DenoiseStats::finishSnr(*glProg, pipeline, *WorkingTexture, &log);
            glProg->closed = true;
            logI("SCAM_PIPELINE", log);
            logI("SCAM_PIPELINE", PostSnr::describe(pipeline.mParameters.finishSnr, PostSnr::on()));
            return;
        }
        WorkingTexture = WhyResolveDenoise::process(*glProg, pipeline, WorkingTexture);
        pipeline.signedRgb = false;
        glProg->closed = true;
        // P648: the shot's SNR and the factors the post pipeline takes from it, once per shot
        logI("SCAM_PIPELINE", PostSnr::describe(pipeline.mParameters.finishSnr, PostSnr::on()));
    }
};

} // namespace scam_post
