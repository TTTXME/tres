// WhyTone.java's static helpers the nodes share (settings, the colour chain), one to one; the rest of WhyTone goes with the
// nodes that use it.
#pragma once

#include "../Pipeline.hpp"

namespace scam_post {

struct WhyToneState {
    OptMat3 colourMatrix;
};

namespace WhyTone {

inline Settings& prefs() { return Settings::current(); }
inline float value(const std::string& key, float fallback) { return prefs().hybridValue("why_" + key, fallback); }
inline bool whyVibranceOn() { return prefs().scamDevSwitch("why_vibrance", true); }
inline float skinProtect() { return std::max(0.f, std::min(1.f, value("skin_protect", 1.f))); }

/// {strength, radius share of the shorter side}
inline std::array<float, 2> sunBloom() {
    return {std::max(0.f, std::min(1.f, value("sun_bloom", 1.f))), std::max(0.005f, value("sun_bloom_radius", 0.033f))};
}

inline int lowFactor(const Parameters& p) { return 2 * std::max(1, int(Settings::javaRound(p.outputScale))); }
inline Point reduced(Point size, int f) { return {(size.x + f - 1) / f, (size.y + f - 1) / f}; }

inline ColourLook colourLook() {
    Settings& s = prefs();
    ColourLook c;
    c.warmth = s.hybridValue("colour_warmth", 0.f);
    c.tint = s.hybridValue("colour_tint", 0.f);
    c.keepWarm = s.hybridValue("colour_keep_warm", 0.f);
    c.keepCool = s.hybridValue("colour_keep_cool", 0.f);
    c.skinProtect = whyVibranceOn() ? 100.f * skinProtect() : 0.f;
    c.skinHue = s.hybridValue("colour_skin_hue", 0.f);
    c.skinEven = s.hybridValue("colour_skin_even", 0.f);
    c.protectsVibrance = !whyVibranceOn();
    const char* parts[3] = {"hue", "sat", "light"};
    for (int b = 0; b < 8; b++)
        for (int k = 0; k < 3; k++)
            c.mix[size_t(b * 3 + k)] = s.hybridValue(std::string("colour_mix_") + ColourLook::BANDS[b] + "_" + parts[k], 0.f);
    for (int z = 0; z < 3; z++) {
        c.gradeHue[size_t(z)] = s.hybridValue(std::string("colour_grade_") + ColourLook::ZONES[z] + "_hue", 0.f);
        c.gradeStr[size_t(z)] = s.hybridValue(std::string("colour_grade_") + ColourLook::ZONES[z] + "_str", 0.f);
    }
    c.gradeBalance = s.hybridValue("colour_grade_balance", 0.f);
    c.gradeBlend = s.hybridValue("colour_grade_blend", 50.f);
    auto indexOf = [](const char* const* list, int n, const std::string& v) {
        for (int i = 0; i < n; i++) if (v == list[i]) return i;
        return -1;
    };
    c.mono = std::max(0, indexOf(ColourLook::MONO, 7, s.hybridString("colour_mono", "off")));
    c.hueLock = std::max(0, indexOf(ColourLook::HUE_LOCK, 3, s.hybridString("colour_hue_lock", "full")));
    c.gamut = s.hybridValue("colour_gamut", 0.f);
    const char* primaries[3] = {"red", "green", "blue"};
    for (int p = 0; p < 3; p++) {
        c.calHue[size_t(p)] = s.hybridValue(std::string("colour_cal_") + primaries[p] + "_hue", 0.f);
        c.calSat[size_t(p)] = s.hybridValue(std::string("colour_cal_") + primaries[p] + "_sat", 0.f);
    }
    return c;
}

inline bool dynamicCctOn() { return prefs().hybridSwitch("why_dynamic_cct", false); }
inline float dynamicCctStrength() { return std::max(0.f, std::min(1.f, value("dynamic_cct_strength", 0.6f))); }
constexpr float CCT_SAT = 1.1f;
/// cctAmount without a film (the film's share is not ported: no film on iOS)
inline float cctAmount(float v, float /*neutral*/) { return v; }
inline float gain(const std::string& key) { return std::max(0.7f, std::min(1.3f, value(key, 1.f))); }

inline OptMat3 cctGain(std::array<float, 3> g, const OptMat3& white) {
    if (std::fabs(g[0] - 1.f) < 1e-4f && std::fabs(g[1] - 1.f) < 1e-4f && std::fabs(g[2] - 1.f) < 1e-4f) return white;
    const Mat3 d = {g[0], 0.f, 0.f, 0.f, g[1], 0.f, 0.f, 0.f, g[2]};
    return white ? ColourLook::mul(d, *white) : d;
}
inline OptMat3 cctSat(float k, const OptMat3& white) {
    if (std::fabs(k - 1.f) < 1e-4f) return white;
    const float luma[3] = {0.2126f, 0.7152f, 0.0722f};
    Mat3 sat{};
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) sat[size_t(i * 3 + j)] = (i == j ? k : 0.f) + (1.f - k) * luma[j];
    return white ? ColourLook::mul(sat, *white) : sat;
}
inline OptMat3 withCctSat(const OptMat3& white) {
    return cctGain({cctAmount(gain("cct_gain_r"), 1.f), cctAmount(gain("cct_gain_g"), 1.f), cctAmount(gain("cct_gain_b"), 1.f)},
                   cctSat(cctAmount(std::max(0.f, std::min(2.f, value("cct_sat", CCT_SAT))), 1.f), white));
}

inline Mat3 intermediateToSRGB(const Parameters& p) {
    Mat3 m{};
    if (p.CCT) for (size_t i = 0; i < 9 && i < p.CCT->matrix.size(); i++) m[i] = p.CCT->matrix[i];
    return m;
}

inline Mat3 colourMatrixOf(const Parameters& p, const OptMat3& white) {
    Mat3 m = intermediateToSRGB(p);
    if (white) m = ColourLook::mul(*white, m);
    if (dynamicCctOn()) m = dynamicCct(m, p.iso, cctAmount(dynamicCctStrength(), 0.f));
    const OptMat3 look = withCctSat(std::nullopt);
    return look ? ColourLook::mul(*look, m) : m;
}

inline Mat3 colourMatrix(PostPipeline& pipeline) {
    if (pipeline.why && pipeline.why->colourMatrix) return *pipeline.why->colourMatrix;
    const Parameters& p = pipeline.mParameters;
    const OptMat3 white = colourLook().whiteMatrix(p.sceneCct);
    const Mat3 m = colourMatrixOf(p, white);
    if (white) {
        std::string w;
        for (size_t i = 0; i < 9; i++) w += (i ? ", " : "[") + javaFloatString((*white)[i]);
        logI("SCAM_PIPELINE", "colour: white matrix " + w + "] scene " + javaFloatString(p.sceneCct) + " K");
    }
    if (pipeline.why) pipeline.why->colourMatrix = m;
    return m;
}

inline std::vector<float> vec(const Mat3& m) { return std::vector<float>(m.begin(), m.end()); }
inline std::vector<float> vec(const std::optional<std::vector<float>>& v) { return v ? *v : std::vector<float>(); }

/// setColour: the colour uniforms of why/low.glsl and why/combine.glsl
inline void setColour(GLProg& prog, PostPipeline& pipeline, const GLTexture& gainMap, float inScale) {
    const Parameters& p = pipeline.mParameters;
    prog.setTexture("GainMap", gainMap);
    prog.setVar("sensorToIntermediate", vec(p.sensorToProPhoto));
    prog.setVar("intermediateToSRGB", vec(colourMatrix(pipeline)));
    const auto& wp = *p.whitePoint;
    prog.setVar("neutralPointU", wp[0], wp[1], wp[2]);
    prog.setVar("inScaleU", inScale);
}

} // namespace WhyTone

/// Converter.multiply: row-major 3x3 a * b (float), as WhyTone's chain multiplies
inline Mat3 multiply(const Mat3& a, const std::vector<float>& b) {
    Mat3 bb{};
    for (size_t i = 0; i < 9 && i < b.size(); i++) bb[i] = b[i];
    return ColourLook::mul(a, bb);
}

} // namespace scam_post
