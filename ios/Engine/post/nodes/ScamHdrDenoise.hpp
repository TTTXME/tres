// ScamHdrDenoise.java + WhyResolveDenoise.java one to one: the reference's three 1/2/4-pixel YUV bilateral passes on the merged
// linear RGB (strips of rows), the effective-frame map as N, the finish SNR measured on the input.
#pragma once

#include "../DenoiseStats.hpp"

namespace scam_post {

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
    const float motionRevert = clampf(prefs.hybridValue("why_dn_motion_revert", MOTION_REVERT), 0.f, 1.f);
    const float grain = clampf(prefs.hybridValue("why_dn_grain", GRAIN), 0.f, 2.f);
    std::string log = "why-resolve grid=" + std::to_string(s) + "x";
    DenoiseStats::finishSnr(prog, pipeline, *input, &log);
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
    log += " factor=" + javaFloatString(factor) + " revert=" + javaFloatString(lumaRevert) + "/" + javaFloatString(motionRevert) + " grain="
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
            return;
        }
        WorkingTexture = WhyResolveDenoise::process(*glProg, pipeline, WorkingTexture);
        pipeline.signedRgb = false;
        glProg->closed = true;
    }
};

} // namespace scam_post
