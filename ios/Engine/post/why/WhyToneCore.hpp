// WhyTone.java's static helpers the nodes share (settings, the colour chain), one to one; the rest of WhyTone goes with the
// nodes that use it.
#pragma once

#include "../Pipeline.hpp"
#include "../color/ToneCurve.hpp"
#include "WhyAe.hpp"
#include "../portrait/PortraitSkin.hpp"

namespace scam_post {

struct WhySkyMaskResult;

/// WhyTone.State: what WhyStats hands on to WhyFusion, WhyLumaSharpen and WhyCombine
struct WhyToneState {
    std::optional<WhyAe::Result> ae;
    /// «Портрет»: the faces' skin measured on whyLow (PortraitGpu.measure), none without a face or a measure
    std::optional<PortraitSkinResult> portrait;
    Texture low, lowDn, mid, fused, detailRef, lumaS, sky;
    int factor = 2;
    float inScale = 1.f;
    float sharpMul = 1.f;
    OptMat3 colourMatrix;
    bool inputReleased = false;
    std::shared_ptr<WhySkyMaskResult> skyMask;
    float noiseS = 0, noiseO = 0, noiseMerged = NAN;
    void closeTextures() {
        for (Texture* t : {&low, &lowDn, &mid, &fused, &detailRef, &lumaS, &sky}) if (*t) (*t)->close();
        low = lowDn = mid = fused = detailRef = lumaS = sky = nullptr;
    }
};

namespace WhyTone {

inline Settings& prefs() { return Settings::current(); }
inline int javaRoundI(float a) { return std::isnan(a) ? 0 : int(std::floor(double(a) + 0.5)); }
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


inline float clampF(float v, float lo, float hi) { return std::isfinite(v) ? std::max(lo, std::min(hi, v)) : 0.f; }
inline bool whyLayout() { return value("fusion_layout", 1.f) >= 0.5f; }
constexpr float LAYER_WEIGHTS[4] = {0.8f, 1.f, 0.1f, 1.f};
inline std::array<float, 4> layerWeights() {
    if (whyLayout()) return {value("classic_weight_ext_hl", 0.7f), value("classic_weight_mid", 2.f), value("classic_weight_hl", 1.f), value("classic_weight_shadow", 1.f)};
    return {value("weight_hl", LAYER_WEIGHTS[0]), value("weight_mid", LAYER_WEIGHTS[1]), value("weight_dark2", LAYER_WEIGHTS[2]), value("weight_bright", LAYER_WEIGHTS[3])};
}
inline float fusionCenter() { return whyLayout() ? value("classic_weight_center", 1.f) : value("weight_center", 0.6f); }

inline WhyAe::AeSettings settings(const PostPipeline& p) {
    WhyAe::AeSettings s;
    s.aeTarget = value("ae_target", 0.15f);
    s.maxBoost = value("ae_max_boost", 5.0f);
    s.minLimit = std::min(value("ae_min_limit", 0.5f), s.maxBoost);
    s.hlOverflow = value("hl_overflow", 2.0f);
    s.hlBlend = std::max(0.f, std::min(1.f, value("hl_blend", 0.5f)));
    float nightVal = value("night_thresh", 0.05f);
    if (nightVal >= 1.0f) nightVal = 0.05f;
    s.nightThresh = 1.f;
    s.noiseMax = nightVal;
    s.nightDim = value("night_dim", 1.0f);
    s.metering = javaRoundI(value("metering", 0.f));
    s.brightThresh = value("bright_thresh", 0.9f);
    s.darkThresh = value("dark_thresh", 12.5f);
    s.darkPixelThresh = value("dark_pixel_thresh", 22.f);
    s.hlBoost = value("hl_boost", 1.1f);
    s.contrastBoost = value("contrast_boost", 0.3f);
    s.shadowStr = std::max(value("shadow_str", 2.0f), 0.f);
    s.nightEvHi = value("night_ev_hi", 6.5f);
    s.nightEvLo = value("night_ev_lo", 5.0f);
    s.nightAeTarget = value("night_ae_target", 0.13f);
    s.nightShadowStr = std::max(value("night_shadow_str", 1.5f), 0.f);
    s.highlightStr = value("highlight_str", 2.1f);
    s.efEnabled = true;
    s.look = javaRoundI(value("agx_look", 4.f));
    s.slope = value("agx_slope", 2.45f);
    s.sp = value("agx_sp", 1.35f);
    s.tp = value("agx_tp", 2.0f);
    s.minEv = value("agx_min_ev", -8.5f);
    s.maxEv = value("agx_max_ev", 3.5f);
    s.sat = value("agx_sat", 1.0f);
    s.ev = value("agx_ev", 0.f);
    s.bentoHeadroom = p.merge.lastBentoApplied;
    s.hrMix = std::max(0.f, std::min(1.f, value("hr_mix", 0.5f)));
    s.v207 = whyLayout();
    return s;
}
inline float headroomCenter() {
    if (prefs().scamDevOverrides("hybrid_why_weight_center_hr")) return value("weight_center_hr", 0.75f);
    return std::max(0.2f, std::min(0.9f, 0.75f + (value("weight_center", 0.6f) - 0.6f)));
}
inline float headroomHlWeight() {
    if (prefs().scamDevOverrides("hybrid_why_weight_hl_hr")) return std::max(0.f, value("weight_hl_hr", 0.4f));
    return std::max(0.f, 0.4f * (value("weight_hl", 0.8f) / 0.8f));
}
inline std::array<float, 4> saturatedLightRamp() {
    return {value("sat_light_lo", 0.15f), value("sat_light_hi", 0.95f), value("sat_light_level_lo", 0.25f),
            std::max(value("sat_light_level_hi", 0.6f), value("sat_light_level_lo", 0.25f) + 0.01f)};
}
inline std::array<float, 2> warmLight() { return {value("sat_light_warm_lo", 0.1f), value("sat_light_warm_hi", 0.25f)}; }
constexpr float BAND_FLOOR = 0.001953125f;
inline std::optional<std::array<float, 6>> bands() {
    if (value("fusion_bands", 1.f) < 0.5f) return std::nullopt;
    const float lo = std::max(0.f, value("fusion_bands_lo", 1.2f));
    return std::array<float, 6>{lo, std::max(value("fusion_bands_hi", 1.8f), lo + 0.01f), BAND_FLOOR,
                                std::max(0.f, std::min(1.f, value("fusion_bands_strength", 1.f))),
                                float(std::max(0, std::min(4, javaRoundI(value("fusion_bands_reach", 3.f))))), std::max(0.f, value("fusion_bands_fall", 0.25f))};
}
inline std::array<float, 2> guidedLights() {
    if (bands() && !prefs().scamDevOverrides("hybrid_why_fusion_guided_lo") && !prefs().scamDevOverrides("hybrid_why_fusion_guided_hi")) return {0.f, 0.f};
    return {value("fusion_guided_lo", 0.8f), value("fusion_guided_hi", 1.6f)};
}
constexpr float EDGE_STEP[5] = {0.95f, 0.83f, 0.81f, 0.82f, 0.82f};
constexpr float EDGE_BETA[5] = {0.997f, 0.271f, 0.122f, 0.059f, 0.029f};
inline std::array<float, 3> edgeRamp(const std::array<float, 6>& band, int l) {
    const float s = EDGE_STEP[std::max(0, std::min(l, 4))];
    const float lo = 0.5f * band[0] * s, hi = 0.5f * band[1] * s;
    return {lo, hi, band[5] * hi};
}
inline float edgeBeta(int l) { return l < 5 ? EDGE_BETA[std::max(0, l)] : EDGE_BETA[4] / float(1 << std::min(20, l - 5 + 1)); }
/// P673: the joint bilateral collapse (V1 off; V2 on at 0.1 since P673c), its guide and the shader's define / log floor
inline float jbuSigma() { return std::max(0.f, value("fusion_jbu_sigma", 0.f)); }
inline float jbuSigmaV2() { return std::max(0.f, value("v2_jbu_sigma", 0.1f)); }
inline int jbuGuide() { return std::max(1, std::min(4, javaRoundI(value("fusion_jbu_guide", 1.f)))); }
inline int jbuGuideV2() { return std::max(1, std::min(4, javaRoundI(value("v2_jbu_guide", 1.f)))); }
constexpr float JBU_LOG_FLOOR = 0.01f;
inline int jbuDefine(int guide) { return guide == 2 || guide == 4 ? 2 : 1; }
inline float jbuLog(int guide) { return guide >= 3 ? JBU_LOG_FLOOR : 0.f; }
/// P677: V1's edge-aware bands on V2's pyramid (off by default)
inline std::optional<std::array<float, 6>> edgeBandsV2() {
    const float strength = std::max(0.f, std::min(1.f, value("v2_edge_bands", 0.f)));
    if (!(strength > 0.f)) return std::nullopt;
    const float lo = std::max(0.f, value("v2_edge_bands_lo", value("fusion_bands_lo", 1.2f)));
    return std::array<float, 6>{lo, std::max(value("v2_edge_bands_hi", value("fusion_bands_hi", 1.8f)), lo + 0.01f), BAND_FLOOR, strength,
                                float(std::max(0, std::min(4, javaRoundI(value("fusion_bands_reach", 3.f))))), std::max(0.f, value("fusion_bands_fall", 0.25f))};
}
/// P672: the fusion's noise model (off by default, as V2's fusion)
inline bool fusionNoiseModel() { return value("fusion_noise_model", 0.f) >= 0.5f; }
/// P672b: how why/combine carries the full-size detail (2: as V2's tonemap_final)
inline int detailSplit(bool sharp, bool film, bool inputReleased) {
    if (!sharp || film || inputReleased) return 0;
    const float v = value("detail_split", 2.f);
    return v >= 1.5f ? 2 : v >= 0.5f ? 1 : 0;
}
inline bool depthSmooth() { return value("fusion_depth_smooth", 1.f) >= 0.5f; }
constexpr float DEEP_NOISE = 0.008f, NOISE_SCALE = 1.0f;
inline float deepNoise() { return std::max(0.f, value("deep_noise", DEEP_NOISE)); }
inline float noiseScale() { return std::max(0.f, value("noise_scale", NOISE_SCALE)); }
inline std::array<float, 3> fusionNoise() {
    return {std::max(0.f, value("fusion_noise_lo", 2.f)), std::max(0.f, value("fusion_noise_hi", 4.f)), float(std::max(0, std::min(4, javaRoundI(value("fusion_noise_levels", 3.f)))))};
}
constexpr float BAND_BETA[4] = {0.9279f, 0.2203f, 0.0933f, 0.0462f};
inline float bandBeta(int l) { return l < 4 ? BAND_BETA[std::max(0, l)] : BAND_BETA[3] / float(1 << std::min(20, l - 4 + 1)); }
inline float bandsNoise() { return std::max(0.f, value("fusion_bands_noise", 2.f)); }
inline float sky() { return std::max(0.f, std::min(1.f, value("sky", 1.f))); }
inline std::array<float, 3> skyTone() {
    return {std::max(0.5f, std::min(1.5f, value("sky_gain", 0.94f))), std::max(0.5f, std::min(1.5f, value("sky_macro", 0.91f))), std::max(1.f, std::min(8.f, value("sky_top", 2.0f)))};
}
constexpr float SKY_VIBRANCE = 0.05f, SKY_GLOW = 0.2f;
inline std::array<float, 3> edgeShare() {
    if (bands() && !prefs().scamDevOverrides("hybrid_why_fusion_edge_lo") && !prefs().scamDevOverrides("hybrid_why_fusion_edge_hi"))
        return {0.f, 0.f, std::max(1e-4f, value("fusion_edge_floor", 0.0078125f))};
    return {value("fusion_edge_lo", 0.3f), value("fusion_edge_hi", 1.0f), std::max(1e-4f, value("fusion_edge_floor", 0.0078125f))};
}
inline float edgeHold() { return std::max(0.f, std::min(1.f, value("edge_hold", 1.f))); }
inline float edgeHoldLight() { return std::max(0.f, std::min(1.f, value("edge_hold_light", 1.f))); }
inline float rlEdgeGuard() { return std::max(0.f, std::min(1.f, value("sharp_rl_edge_guard", 1.f))); }
inline float localContrast() { return std::max(0.f, std::min(1.f, value("local_contrast", 0.3f))); }
constexpr float LOCAL_CONTRAST_SIGMA = 4.f, LOCAL_CONTRAST_T = 0.04f;
inline float latticeCap() { return std::max(0.f, value("sharp_lattice_cap", 0.005f)); }
inline bool rimSmooth() { return value("rim_smooth", 1.f) > 0.5f; }
/// yellowHue: 1 by default on vivo (Build.MANUFACTURER), 0 elsewhere - an iPhone is no vivo
/// P676: V1's colour after the tone as V2's (V2_COLOUR); the V1-only colour stages are off with it
inline bool v2Colour() { return value("v2_colour", 1.f) >= 0.5f; }
inline float hlWhite() { return v2Colour() ? 0.f : std::max(0.f, std::min(1.f, value("hl_white", 0.15f))); }
inline float deltaChroma() { return v2Colour() ? 0.f : std::max(0.f, std::min(1.f, value("delta_chroma", 1.f))); }
inline float yellowHue() { return v2Colour() ? 0.f : std::max(0.f, std::min(1.f, value("yellow_hue", 0.f))); }
inline float hlNeutral() { return v2Colour() ? 0.f : std::max(0.f, std::min(1.f, value("hl_neutral", 1.f))); }
inline std::array<float, 4> lightWarm() { return {v2Colour() ? 0.f : std::max(0.f, std::min(1.f, value("light_warm", 1.f))), 0.6f, 0.9f, std::max(0.f, std::min(1.f, value("light_warm_chroma", 0.5f)))}; }
inline std::array<float, 4> hlLocal() { return {std::max(0.f, std::min(2.f, value("hl_local", 0.8f))), 1.5f, 6.f, 12.f}; }
inline bool hlLocalRim() { return value("hl_local_rim", 1.f) > 0.f; }
inline bool hlMeasured() { return value("hl_measured", 1.f) > 0.f; }
inline bool hlLocalSmooth() { return value("hl_local_smooth", 1.f) > 0.f; }
inline bool rimFar() { return value("rim_far", 1.f) > 0.f; }
constexpr float MEASURED_GLARE = 0.7f, MEASURED_DETAIL = 0.5f, HL_MEASURED_LO = 1.f, HL_MEASURED_HI = 2.f;
inline float hlMeasuredGlare() { return std::max(0.f, std::min(1.f, value("hl_measured_glare", MEASURED_GLARE))); }
inline bool hlMeasuredScene() { return value("hl_measured_scene", 1.f) > 0.f; }
inline float hlMeasuredDetail() { return std::max(0.f, std::min(1.f, value("hl_measured_detail", MEASURED_DETAIL))); }
inline bool hlMeasuredMax() { return value("hl_measured_max", 1.f) > 0.f; }
inline float hlMeasuredLanding() { return std::max(0.f, std::min(1.f, value("hl_measured_landing", 1.f))); }
inline float hlLocalNight() { return std::max(0.f, std::min(1.f, value("hl_local_night", 1.f))); }
constexpr float VIBRANCE_HL_STR = 0.25f;
inline float vibranceHl() { return std::max(0.f, value("vibrance_hl", 1.0f) * value("vibrance_hl_str", VIBRANCE_HL_STR) / VIBRANCE_HL_STR); }
inline std::array<float, 4> vibranceWhyUniform() {
    return {value("vibrance_saturated", 0.f), VIBRANCE_HL_STR * vibranceHl(), value("vibrance_mid", 0.f), value("vibrance_shadows", 0.f)};
}
inline float toRadians(float deg) { return float(double(deg) / 180.0 * M_PI); }
inline std::array<float, 4> vibranceSkinUniform() {
    return {skinProtect(), toRadians(value("skin_hue_center", 41.2f)), value("skin_luma_min", 0.16f), value("skin_sat_shift", 0.f)};
}
inline float vibranceSkinHue() { return std::max(-30.f, std::min(30.f, value("skin_hue_shift", 0.f))); }
inline std::array<float, 4> vibranceHlShape() {
    if (prefs().scamDevSwitch("vibrance_hl_shape", true)) return {0.57f, 0.89f, 0.010f, 0.025f};
    return {0.45f, 0.75f, 0.002f, 0.008f};
}
inline float fusionMono() { return std::max(0.f, value("fusion_mono", 0.04f)); }
inline float deepFloor() { return std::max(0.f, value("deep_floor", 2.f)); }
inline float windowShare() { return std::max(0.f, std::min(1.f, value("window_31091", 1.f))); }
inline bool macroToe() { return value("macro_toe", 1.f) >= 0.5f; }
inline float lightChroma() { return v2Colour() ? 0.f : std::max(0.f, std::min(1.f, value("light_chroma", 1.f))); }
inline float insetStrength() { return std::max(0.f, std::min(1.f, value("agx_inset", 50.f) / 100.f)); }
inline float shoulderStrength() { return std::max(0.f, std::min(1.f, value("hr_strength", 0.6f))); }
inline std::array<float, 3> headroomTone() { return {value("hr_knee", 0.6f), value("hr_room", 0.35f), std::max(0.f, value("hr_detail", 0.35f))}; }
inline float ccmSatComp(bool tuned) { return tuned ? std::max(value("ccm_sat", 1.1f), 0.f) : 1.f; }
inline float ccmSatComp(const Parameters& p) { return ccmSatComp(p.oppoTunedCcm); }
inline bool signedHue() { return prefs().scamDevSwitch("ccm_signed_hue", true); }
inline int toneOperator() {
    if (prefs().hybridSwitch("why_curve_on", true)) return ToneCurve::CUSTOM;
    if (prefs().hybridSwitch("why_uchi_on", false)) return ToneCurve::UCHIMURA;
    if (prefs().hybridSwitch("why_aces_on", false)) return ToneCurve::ACES;
    return ToneCurve::AGX;
}
inline std::array<float, 4> acesUniform() { return {value("aces_pre_gain", 1.0f), value("aces_a", 2.61f), value("aces_d", 0.59f), value("aces_toe", 0.05f)}; }
inline std::array<std::array<float, 4>, 2> uchimuraUniforms() {
    const float m = std::max(value("uchi_m", 0.22f), 0.01f), a = std::max(value("uchi_a", 1.0f), 0.05f);
    const float p = std::max(value("uchi_p", 1.0f), m + 0.05f), l = std::min(std::max(value("uchi_l", 0.4f), 0.f), 0.95f);
    return {{{value("uchi_pre_gain", 1.0f), value("uchi_post_gain", 1.0f), p, a}, {m, l, value("uchi_c", 1.33f), value("uchi_b", 0.0f)}}};
}
inline std::optional<std::array<float, 10>> faceMask() {
    if (!prefs().hybridSwitch("why_face_mask_on", true)) return std::nullopt;
    const std::array<float, 10> m = {clampF(value("face_exposure", 0.f), -2.f, 2.f), clampF(value("face_contrast", 0.f) / 100.f, -1.f, 1.f),
        clampF(value("face_saturation", 0.f) / 100.f, -1.f, 1.f), clampF(value("face_warmth", 0.f) / 100.f, -1.f, 1.f),
        clampF(value("face_tint", 0.f) / 100.f, -1.f, 1.f), clampF(value("face_smooth", 0.f) / 100.f, 0.f, 1.f),
        clampF(value("face_detail", 0.f) / 100.f, -1.f, 1.f), prefs().hybridSwitch("why_face_mask_show", false) ? 1.f : 0.f,
        clampF(value("face_mask_size", 100.f) / 100.f, 0.5f, 1.5f), clampF(value("face_mask_skin", 100.f) / 100.f, 0.f, 1.f)};
    bool any = m[7] > 0.f;
    for (int i = 0; i < 7; i++) any |= std::fabs(m[size_t(i)]) > 1e-4f;
    if (!any) return std::nullopt;
    return m;
}
/// P513: with a face found the AE target +0.05 and the dark-pixel threshold +0.5 (scam_dev hybrid_why_portrait_off_*)
inline void portraitOffsets(WhyAe::AeSettings& s) {
    s.aeTarget += value("portrait_off_ae_target", 0.05f);
    s.darkPixelThresh += value("portrait_off_dark_pixel", 0.5f);
}
/// P512: the band of skin luminance (after the exposure) «Приоритет лиц в AE» leaves alone
constexpr float FACE_LO = 0.09f, FACE_HI = 0.20f;
/// P512: the exposure factor: (the band's nearest edge / (skin x ae)) ^ priority within 1/2 .. 2; 1 inside the band
inline float faceFactor(float ae, float face, float priority) {
    const float now = PortraitSkin::detail::jmax(face * ae, 1e-6f), target = PortraitSkin::detail::jmax(FACE_LO, PortraitSkin::detail::jmin(FACE_HI, now));
    return float(std::max(0.5, std::min(2.0, std::pow(double(target / now), double(priority)))));
}
/// P512 «Приоритет лиц в AE»: with a face found the exposure moves priority of the way (in stops) towards the one that puts the
/// measured skin into FACE_LO .. FACE_HI, at most one stop either way and never past the noise limit the scene's exposure respected
inline void facePriority(WhyAe::Result& r, const std::optional<PortraitSkinResult>& p) {
    if (!r.portraitFace || !p) return;
    const float priority = PortraitSkin::detail::jmax(0.f, PortraitSkin::detail::jmin(1.f, value("portrait_ae", 1.f)));
    const float face = PortraitSkin::faceLuma(&*p);
    if (priority <= 0.f || !(face > 0.f) || !(r.ae > 0.f)) return;
    const float factor = faceFactor(r.ae, face, priority);
    float ae = r.ae * factor;
    if (factor > 1.f && r.maxGainByNoise > 0.f) ae = PortraitSkin::detail::jmin(ae, PortraitSkin::detail::jmax(r.ae, r.maxGainByNoise));
    char buf[192];
    std::snprintf(buf, sizeof buf, "Portrait AE: skin %.4f x ae %.4f -> %.4f (priority %.2f, ae %.4f -> %.4f)", double(face), double(r.ae),
                  double(face * ae), double(priority), double(r.ae), double(ae));
    logI("SCAM_PIPELINE", buf);
    r.ae = ae;
}
constexpr const char* PORTRAIT_POINTS = "0.0000:0.0000,0.0625:0.0147,0.1250:0.0349,0.1875:0.0642,0.2500:0.1108,0.3125:0.1870,0.3750:0.2822,0.4375:0.3900,0.5000:0.5100,0.5625:0.6362,0.6250:0.7587,0.6875:0.8677,0.7500:0.9524,0.8125:0.9996,0.8750:1.0000,0.9375:1.0000,1.0000:1.0000";
constexpr float CURVE_B[17] = {0, 0, 4, 50, 178, 401, 674, 906, 1000, 906, 674, 401, 178, 50, 4, 0, 0},
                CURVE_C[17] = {0, 349, 659, 889, 1000, 964, 769, 431, 0, -431, -769, -964, -1000, -889, -659, -349, 0},
                CURVE_E[17] = {0, 349, 659, 889, 1000, 962, 769, 471, 206, 51, 6, 0, 0, 0, 0, 0, 0},
                CURVE_F[17] = {0, 0, 0, 0, 0, 0, -6, -51, -206, -471, -769, -962, -1000, -889, -659, -349, 0},
                CURVE_G[17] = {0, 667, 1000, 956, 697, 402, 180, 61, 14, 1, 0, 0, 0, 0, 0, 0, 0},
                CURVE_H[17] = {0, 0, 0, 0, 0, 0, 0, -1, -14, -61, -180, -402, -697, -956, -1000, -667, 0};
inline float curveCoeff(const float* t, float x) {
    const float idx = x * 16.f;
    const int i = int(idx);
    if (i < 0) return t[0];
    if (i >= 16) return t[16];
    const float f = idx - float(i);
    return t[i] + f * f * (3.f - 2.f * f) * (t[i + 1] - t[i]);
}
inline std::vector<float> curveModified(std::vector<float> y, const std::array<float, 7>& m) {
    bool any = false;
    for (float v : m) any |= std::fabs(v) > 1e-4f;
    if (!any) return y;
    std::vector<float> out(y.size());
    for (size_t i = 0; i < y.size(); i++) {
        const float x = y.size() > 1 ? float(i) / float(y.size() - 1) : 0.f;
        const float d = m[6] / 200.f + 2.5e-6f * (curveCoeff(CURVE_B, x) * m[0] - curveCoeff(CURVE_C, x) * m[1] - curveCoeff(CURVE_E, x) * m[2]
                        - curveCoeff(CURVE_F, x) * m[3] + curveCoeff(CURVE_G, x) * m[4] - curveCoeff(CURVE_H, x) * m[5]);
        out[i] = std::max(0.f, std::min(1.f, y[i] + d));
    }
    return out;
}
inline std::vector<float> curveSamples() {
    return curveModified(ToneCurve::parse(prefs().hybridString("why_curve_points", ToneCurve::SETTING_POINTS)).samples(ToneCurve::SAMPLES),
                         {value("curve_lift", 0.f), value("curve_contrast", 0.f), value("curve_shadow_contrast", 0.f), value("curve_highlight_contrast", 0.f),
                          value("curve_deep_shadows", 0.f), value("curve_extreme_highlights", 0.f), value("curve_offset", 0.f)});
}
inline std::vector<float> curveSamples(bool face) {
    if (!face || !prefs().hybridSwitch("why_portrait_curve_on", true)) return curveSamples();
    return curveModified(ToneCurve::parse(prefs().hybridString("why_portrait_curve_points", PORTRAIT_POINTS)).samples(ToneCurve::SAMPLES),
                         {value("portrait_curve_lift", 0.f), value("portrait_curve_contrast", 0.f), value("portrait_curve_shadow_contrast", 0.f),
                          value("portrait_curve_highlight_contrast", 0.f), value("portrait_curve_deep_shadows", -10.f),
                          value("portrait_curve_extreme_highlights", 10.f), value("portrait_curve_offset", 0.f)});
}
inline std::optional<std::array<std::array<float, 4>, 2>> whyVibrance() {
    if (whyVibranceOn()) return std::nullopt;
    const float sat = value("vibrance_saturated", 0.f), mid = value("vibrance_mid", 0.f), sh = value("vibrance_shadows", 0.f), skinSat = value("skin_sat_shift", 0.f);
    if (std::fabs(sat) < 1e-4f && std::fabs(mid) < 1e-4f && std::fabs(sh) < 1e-4f && std::fabs(skinSat) < 1e-4f) return std::nullopt;
    return std::array<std::array<float, 4>, 2>{{{sat, mid, sh, std::max(0.f, std::min(1.f, value("skin_protect", 1.f)))},
                                                {toRadians(value("skin_hue_center", 41.2f)), value("skin_luma_min", 0.16f), skinSat, 0.f}}};
}
inline float curveBoost(float c) { return c * std::max(0.f, std::min(2.f, value("curve_boost", 1.f))); }
inline int gammaType() { return std::max(0, std::min(2, javaRoundI(value("gamma_type", 0.f)))); }
inline float gamma() { const float g = value("gamma", 2.2f); return g > 0.1f ? g : 2.2f; }
inline bool colourLookOn(const ColourLook& look, int toneOp, float saturation) {
    return !look.shaderNeutral(toneOp == ToneCurve::AGX && saturation - 1.f > 0.001f);
}
inline float agxSaturation(const WhyAe::AeSettings& s) {
    const float sat = s.efEnabled && s.look >= 0 && s.look < 4 ? WhyAe::LOOKS[s.look][5] : s.sat;
    return sat * s.ccmSat;
}
inline std::array<float, 2> sunBloomGate(PostPipeline& p) {
    return !p.merge.lastBentoApplied && prefs().hybridSwitch("sun_bloom_no_short", true) ? std::array<float, 2>{0.80f, 0.95f} : std::array<float, 2>{0.55f, 0.85f};
}

inline bool dynamicCctOn() { return prefs().hybridSwitch("why_dynamic_cct", false); }
inline float dynamicCctStrength() { return std::max(0.f, std::min(1.f, value("dynamic_cct_strength", 0.6f))); }
constexpr float CCT_SAT = 1.1f;
/// WhyTone.cctAmount: camera calibration and the chosen white balance stay; only our extra CCT look gives way to the film
inline float cctAmount(float value, float neutral, const FilmSettings* film) {
    if (!film || film->strength <= 0.f) return value;
    if (film->strength >= 1.f) return neutral;
    return neutral + (value - neutral) * (1.f - film->strength);
}
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
inline OptMat3 withCctSat(const OptMat3& white, const FilmSettings* film = nullptr) {
    if (film && film->strength >= 1.f) return white;
    return cctGain({cctAmount(gain("cct_gain_r"), 1.f, film), cctAmount(gain("cct_gain_g"), 1.f, film), cctAmount(gain("cct_gain_b"), 1.f, film)},
                   cctSat(cctAmount(std::max(0.f, std::min(2.f, value("cct_sat", CCT_SAT))), 1.f, film), white));
}

inline Mat3 intermediateToSRGB(const Parameters& p) {
    Mat3 m{};
    if (p.CCT) for (size_t i = 0; i < 9 && i < p.CCT->matrix.size(); i++) m[i] = p.CCT->matrix[i];
    return m;
}

inline Mat3 colourMatrixOf(const Parameters& p, const OptMat3& white, const FilmSettings* film = nullptr) {
    Mat3 m = intermediateToSRGB(p);
    if (white) m = ColourLook::mul(*white, m);
    if (dynamicCctOn()) m = dynamicCct(m, p.iso, cctAmount(dynamicCctStrength(), 0.f, film));
    const OptMat3 look = withCctSat(std::nullopt, film);
    return look ? ColourLook::mul(*look, m) : m;
}

inline Mat3 colourMatrix(PostPipeline& pipeline) {
    if (pipeline.why && pipeline.why->colourMatrix) return *pipeline.why->colourMatrix;
    const Parameters& p = pipeline.mParameters;
    const OptMat3 white = colourLook().whiteMatrix(p.sceneCct);
    const Mat3 m = colourMatrixOf(p, white, pipeline.film.get());
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
