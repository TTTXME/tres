// «Oldstyle» («ScamMaster Тон»): OldstyleAe.java (the exposure plan of the mode from the merge's samples: the supplement's rules
// plan27 / plan31 / capCaptureRatio / compensateTet / fusionGains in FP32 and our meter), OldstyleStyle.java (the style's values
// and its uniforms) and the settings readers of OldstyleMode.java / ShotLook.Oldstyle one to one, with Java's float semantics
// (float products in order, Math.log / Math.exp / Math.pow in double, (float) casts, Math.round(float) = Settings::javaRound).
// The viewfinder parts (bayerQuads, bayerBox, shownPart) are not the photo's and are not ported.
#pragma once

#include "../Settings.hpp"
#include "../why/WhyV2Ae.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <limits>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace scam_post {

namespace OldstyleAe {

constexpr float SHORT_WHITE = 0.95f;
constexpr float HIGH_PERCENTILE = 0.997f;
constexpr float CLIP_LEVEL = 0.995f;
constexpr float CLIP_NEAR = 0.98f;
constexpr float F_SIGMA = 0.47f;

inline float clipShare(float share, float from) {
    return jmath::max(0.f, jmath::min(1.f, (share - from) / (CLIP_LEVEL - from)));
}

/// The fit of log(L* / S*) (the float differences and sums as Java folds them)
inline const std::array<float, 4>& ratio27() {
    static const std::array<float, 4> c{2.1303f - 2.9937f, -0.3401f + 0.7676f, -1.6873f - 0.0224f, 0.9155f + 0.3935f};
    return c;
}
inline const std::array<float, 4>& ratio31() {
    static const std::array<float, 4> c{2.1886f - 3.0133f, -0.4974f + 0.7101f, -1.7148f - 0.0899f, 1.0632f + 0.5216f};
    return c;
}

constexpr float V1_HDR_MIN = 1.4500000476837158f, V1_HDR_MAX = 6.803999900817871f, V1_RATIO_CAP = 3.5f;
constexpr float V2_HDR_MIN = 1.3300000429153442f, V2_RATIO_MAX = 8.0f, V2_FORGIVENESS = 1.3999999761581421f;
constexpr float FUSION_BASE = 0.9756097793579102f;
constexpr int HDR_AUTO = 0, HDR_ON = 1, HDR_OFF = 2;
constexpr int DEHAZE_MAX_DARK = 24;
constexpr float DEHAZE_PERCENTILE = 0.2f, DEHAZE_CORNER = 0.2f;
constexpr int LF_THRESHOLD = 16;

struct ExposurePlan {
    bool hdrScene = false, useHdr = false;
    float shortCandidate = 0.f, longCandidate = 0.f, shortPlan = 0.f, longPlan = 0.f;
};

inline float powf(float a, float b) { return float(std::pow(double(a), double(b))); }

inline float compensateTet(float tet, float ev, float minTet, float maxTet) {
    return jmath::max(minTet, jmath::min(maxTet, tet * powf(2.0f, ev)));
}

inline void capCaptureRatio(ExposurePlan& p, float maxRatio, float fractionLong) {
    const float ratio = p.longCandidate / p.shortCandidate;
    if (ratio <= maxRatio) return;
    const float q = ratio / maxRatio;
    const float w = jmath::max(0.f, jmath::min(1.f, (fractionLong - 0.6000000238418579f) * 5.000000476837158f));
    p.shortPlan *= powf(q, w);
    p.longPlan /= powf(q, 1.0f - w);
}

inline ExposurePlan makePlan(bool scene, bool use, float s, float l) {
    ExposurePlan p;
    p.hdrScene = scene;
    p.useHdr = use;
    p.shortCandidate = p.shortPlan = s;
    p.longCandidate = p.longPlan = l;
    return p;
}

inline ExposurePlan plan27(float shortCandidate, float longCandidate, float fractionLong, float min, float max, float cap) {
    const float ratio = longCandidate / shortCandidate;
    const bool enoughRange = ratio >= min;
    const bool use = enoughRange && ratio <= max;
    ExposurePlan p = makePlan(enoughRange, use, shortCandidate, longCandidate);
    if (use) capCaptureRatio(p, cap, fractionLong);
    return p;
}

inline ExposurePlan plan31(float shortCandidate, float longCandidate, float fractionLong, bool hdrDisabled, float ev, float minTet,
                           float maxTet, float min, float maxRatio, float forgiveness) {
    const float ratio = longCandidate / shortCandidate;
    const bool scene = ratio >= min;
    const bool use = scene && !hdrDisabled && ratio <= maxRatio * forgiveness;
    ExposurePlan p = makePlan(scene, use, shortCandidate, longCandidate);
    if (use) {
        capCaptureRatio(p, maxRatio, fractionLong);
        p.shortPlan = compensateTet(p.shortPlan, ev, minTet, maxTet);
        p.longPlan = compensateTet(p.longPlan, ev, minTet, maxTet);
    }
    return p;
}

/// fusion_gains with its base: {short, long}
inline std::array<float, 2> fusionGains(float actualShortTet, float desiredShortTet, float desiredLongTet, float commonGain, float maxGain,
                                        float base) {
    const float adjustedShort = powf(base, desiredLongTet / desiredShortTet) * desiredShortTet;
    const float shortGain = (adjustedShort / actualShortTet) * commonGain;
    const float longGain = (desiredLongTet / actualShortTet) * commonGain;
    const float threshold = 1.0010000467300415f * maxGain;
    return {shortGain > threshold ? maxGain : shortGain, longGain > threshold ? maxGain : longGain};
}

struct Params {
    bool v31 = false;
    float maxGain = 16.f;
    float saturation = 0.f;
    float exposureEv = 0.f;
    bool dehaze = true;
    int maxDark = DEHAZE_MAX_DARK;
    float percentile = DEHAZE_PERCENTILE, cornerStrength = DEHAZE_CORNER;
    bool dehazeShading = true;
    float outSat = 0.f, vibrance = 1.f, satPerStop = 0.f, vibrancePerStop = 0.f;
    bool lowFrequency = true;
    int lfThreshold = LF_THRESHOLD;
    int hdrMode = HDR_AUTO;
    float v1HdrMin = V1_HDR_MIN, v1HdrMax = V1_HDR_MAX, v1RatioCap = V1_RATIO_CAP;
    float v2HdrMin = V2_HDR_MIN, v2RatioMax = V2_RATIO_MAX, v2Forgiveness = V2_FORGIVENESS;
    float fusionBase = FUSION_BASE;
    float baseEv = 0.f;
    float highPercentile = HIGH_PERCENTILE, shortWhite = SHORT_WHITE;
    float shadowEv = 0.f;
    float centerSigma = F_SIGMA;
    float curveMid = 0.f, curveContrast = 0.f;
    float ccmStrength = 1.f;
    bool lfChroma = true;
    float lfStrength = 1.f;
    int burstFrames = 0;
    bool vfTone = true;
    float vfEv = 0.f, vfMatch = 1.f, vfSmooth = 0.5f;
    float memoryShare = 0.6f;

    /// OldstyleNative.render's post array
    std::vector<float> post() const {
        const bool sat = jmath::abs(outSat) >= 1.f / 256.f || jmath::abs(vibrance - 1.f) >= 1.f / 256.f || jmath::abs(satPerStop) >= 1e-4f
                         || jmath::abs(vibrancePerStop) >= 1e-4f;
        return {dehaze ? 1.f : 0.f, float(maxDark), percentile, cornerStrength, sat ? 1.f : 0.f, outSat, vibrance, satPerStop,
                vibrancePerStop, lowFrequency ? 1.f : 0.f, float(lfThreshold), lfChroma ? 1.f : 0.f, lfStrength, curveMid, curveContrast};
    }
};

struct Meter {
    float high = 0.f, geoMean = 0.f, mean = 0.f;
    bool clipped = false;
    float shortRel = 0.f, longRel = 0.f;
    float fractionLong = 0.f;
};

inline float ratioFit(bool v31, float high, float geoMean, float mean) {
    const auto& c = v31 ? ratio31() : ratio27();
    const double l = double(c[0]) + double(c[1]) * std::log(double(high)) + double(c[2]) * std::log(double(geoMean))
                     + double(c[3]) * std::log(double(mean));
    return float(std::exp(l));
}

inline float clean(float v, float ceiling) { return std::isfinite(v) ? jmath::max(0.f, jmath::min(ceiling, v)) : 0.f; }

inline Meter meter(const std::vector<float>& rgb, int w, int h, const Params& p, float ceiling) {
    const bool v31 = p.v31;
    const int n = int(std::min<int64_t>(int64_t(rgb.size() / 3), int64_t(std::max(0, w)) * std::max(0, h)));
    Meter m;
    std::vector<float> max(static_cast<size_t>(n)), luma(static_cast<size_t>(n));
    double logSum = 0, sum = 0;
    for (int i = 0; i < n; i++) {
        const float r = clean(rgb[size_t(i) * 3], ceiling), g = clean(rgb[size_t(i) * 3 + 1], ceiling), b = clean(rgb[size_t(i) * 3 + 2], ceiling);
        max[size_t(i)] = jmath::max(r, jmath::max(g, b));
        const float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        luma[size_t(i)] = y;
        logSum += std::log(double(jmath::max(1e-4f, y)));
        sum += double(y);
    }
    std::sort(luma.begin(), luma.end());
    std::vector<float> sorted = max;
    std::sort(sorted.begin(), sorted.end());
    m.high = n == 0 ? 1.f : jmath::max(1e-3f, sorted[size_t(std::max(0, std::min(n - 1, jmath::toInt(p.highPercentile * float(n - 1)))))]);
    m.geoMean = n == 0 ? 0.18f : float(std::exp(logSum / double(n)));
    m.mean = n == 0 ? 0.18f : jmath::max(1e-4f, float(sum / double(n)));
    m.clipped = m.high >= CLIP_LEVEL * ceiling;
    const float light = m.high, near = clipShare(m.high / ceiling, CLIP_NEAR);
    const float white = near <= 0.f || p.shortWhite >= 1.f ? p.shortWhite : float(std::pow(double(p.shortWhite), 1.0 - double(near)));
    m.shortRel = white / light;
    m.longRel = m.shortRel * ratioFit(v31, light, m.geoMean, m.mean) * (p.shadowEv == 0.f ? 1.f : powf(2.f, p.shadowEv));
    double kept = 0, total = 0;
    const double k = 1.0 / (2.0 * double(p.centerSigma) * double(p.centerSigma));
    for (int y = 0; y < h; y++) {
        const double dy = (h > 1 ? y / double(h - 1) : 0.5) - 0.5;
        for (int x = 0; x < w; x++) {
            const int i = y * w + x;
            if (i >= n) break;
            const double dx = (w > 1 ? x / double(w - 1) : 0.5) - 0.5;
            const double wt = std::exp(-(dx * dx + dy * dy) * k);
            total += wt;
            if (max[size_t(i)] * m.longRel < 1.f) kept += wt;
        }
    }
    m.fractionLong = total > 0 ? float(kept / total) : 0.f;
    return m;
}

/// the rule with the settings' numbers (rule(Params, ...))
inline ExposurePlan rule(const Params& p, float shortRel, float longRel, float fractionLong, float ev) {
    const bool on = p.hdrMode == HDR_ON, off = p.hdrMode == HDR_OFF;
    const float open = std::nextafter(1.f, std::numeric_limits<float>::infinity());
    if (p.v31) {
        return plan31(shortRel, longRel, fractionLong, off, ev, 0.f, std::numeric_limits<float>::max(), on ? open : p.v2HdrMin, p.v2RatioMax,
                      on ? std::numeric_limits<float>::infinity() : p.v2Forgiveness);
    }
    const float k = powf(2.f, ev);
    ExposurePlan e = plan27(shortRel * k, longRel * k, fractionLong, on ? open : p.v1HdrMin, on ? std::numeric_limits<float>::infinity() : p.v1HdrMax,
                            p.v1RatioCap);
    if (off) {
        e.useHdr = false;
        e.shortPlan = e.shortCandidate;
        e.longPlan = e.longCandidate;
    }
    return e;
}

inline float inputScale(float maxSample) { return maxSample > 1.f && std::isfinite(maxSample) ? 1.f / maxSample : 1.f; }

struct Plan {
    float shortCandidate = 0.f, longCandidate = 0.f, shortTet = 0.f, longTet = 0.f, fractionLong = 0.f;
    bool hdrScene = false, hdr = false;
    float actualTet = 1.f;
    float gainShort = 0.f, gainLong = 0.f;
    float inputScale = 1.f;
    std::optional<Meter> meter;

    /// String.format(Locale.ROOT, "%.4g") is close to C's %.4g (Java keeps trailing zeros): the log line only
    std::string describe() const {
        auto g = [](float v) { char b[48]; std::snprintf(b, sizeof b, "%#.4g", double(v)); return std::string(b); };
        auto f3 = [](float v) { char b[48]; std::snprintf(b, sizeof b, "%.3f", double(v)); return std::string(b); };
        std::string s = "plan S*=" + g(shortCandidate) + " L*=" + g(longCandidate) + " F=" + f3(fractionLong) + " scene="
                        + (hdrScene ? "true" : "false") + " hdr=" + (hdr ? "true" : "false") + " S=" + g(shortTet) + " L=" + g(longTet)
                        + " S0=" + g(actualTet) + " gains=" + g(gainShort) + "/" + g(gainLong);
        if (meter) s += " light=" + f3(meter->high) + (meter->clipped ? " clipped" : "");
        return s;
    }
};

/// The tone's plan of a frame, metered on its samples (OldstyleAe.plan)
inline Plan plan(const std::vector<float>& rgb, int w, int h, const Params& p, float ceiling) {
    const Meter m = meter(rgb, w, h, p, ceiling);
    Plan plan;
    plan.meter = m;
    float top = 0.f;
    for (size_t i = 0; i + 2 < rgb.size(); i += 3)
        for (size_t c = 0; c < 3; c++) if (std::isfinite(rgb[i + c])) top = jmath::max(top, rgb[i + c]);
    const float shortRel = m.shortRel, longRel = m.longRel, fraction = m.fractionLong;
    const ExposurePlan e = rule(p, shortRel, longRel, fraction, 0.f);
    plan.shortCandidate = shortRel;
    plan.longCandidate = longRel;
    plan.fractionLong = fraction;
    plan.hdrScene = e.hdrScene;
    plan.hdr = e.useHdr;
    plan.shortTet = e.shortPlan;
    plan.longTet = e.longPlan;
    const float k = powf(2.f, p.exposureEv + p.baseEv);
    plan.shortTet *= k;
    plan.longTet *= k;
    const float sdr = k;
    plan.inputScale = p.v31 && plan.hdr ? inputScale(top) : 1.f;
    plan.actualTet = plan.inputScale;
    if (plan.hdr) {
        const auto g = fusionGains(plan.actualTet, plan.shortTet, plan.longTet, 1.f, p.maxGain, p.fusionBase);
        plan.gainShort = g[0];
        plan.gainLong = g[1];
    } else {
        const float g = jmath::min(p.maxGain, sdr / plan.actualTet);
        plan.gainShort = plan.gainLong = g;
    }
    return plan;
}

} // namespace OldstyleAe

/// OldstyleStyle.java: the palette's two axes and twelve advanced parameters
class OldstyleStyle {
public:
    enum Param { TONE, COLOR, EXPOSURE, AURA, HALO, GRAIN, WARMTH, HUE, HIGHLIGHTS, SHADOWS, WARM_BOOST, COOL_BOOST, SHARPNESS, VIGNETTE, COUNT };

    static const char* key(int p) {
        static const char* keys[] = {"tone", "color", "exposure", "aura", "halo", "grain", "warmth", "hue", "highlights", "shadows",
                                     "warm_boost", "cool_boost", "sharpness", "vignette"};
        return keys[p];
    }
    static bool discrete(int p) { return p == AURA || p == HALO || p == GRAIN; }
    static int clamp(int p, int v) { return std::max(discrete(p) ? 0 : -100, std::min(discrete(p) ? 3 : 100, v)); }
    static float unit(int p, int v) { return discrete(p) ? float(clamp(p, v)) / 3.f : float(clamp(p, v)) / 100.f; }

    int get(int p) const { return values_[size_t(p)]; }
    OldstyleStyle& set(int p, int v) { values_[size_t(p)] = clamp(p, v); return *this; }

    /// uOs0..uOs3: (tone, colour, exposure, warmth), (hue, highlights, shadows, warm boost), (cool boost, sharpness, vignette, aura),
    /// (halo, grain, 0, 0)
    std::array<float, 16> uniforms() const {
        static const int order[] = {TONE, COLOR, EXPOSURE, WARMTH, HUE, HIGHLIGHTS, SHADOWS, WARM_BOOST, COOL_BOOST, SHARPNESS, VIGNETTE,
                                    AURA, HALO, GRAIN};
        std::array<float, 16> u{};
        for (size_t i = 0; i < 14; i++) u[i] = unit(order[i], get(order[i]));
        return u;
    }
    float exposureEv() const { return 1.5f * unit(EXPOSURE, get(EXPOSURE)); }
    std::array<float, 16> uniformsAfterTone() const {
        auto u = uniforms();
        u[2] = 0.f;
        return u;
    }
    bool neutralAfterTone() const {
        for (int p = 0; p < COUNT; p++) if (p != EXPOSURE && get(p) != 0) return false;
        return true;
    }
    std::string toString() const {
        std::string s;
        for (int p = 0; p < COUNT; p++)
            if (get(p) != 0) s += (s.empty() ? "" : " ") + std::string(key(p)) + "=" + std::to_string(get(p));
        return s.empty() ? "neutral" : s;
    }

private:
    std::array<int, COUNT> values_{};
};

/// OldstyleMode.java's readers of the settings and ShotLook.Oldstyle (the mode's state at the press: the version, the levels of
/// V2, the tone's rows and the style)
namespace OldstyleMode {

inline bool on() { return Settings::current().getBoolean("pref_oldstyle_mode", false); }
inline bool v2() { return Settings::current().hybridString("oldstyle_version", "v1") == "v2"; }
inline bool v2Postview() { return Settings::current().hybridString("oldstyle_v2_levels", "10") == "8"; }

/// Integer.parseInt: an optional sign and decimal digits, else no number
inline std::optional<int> parseInt(const std::string& s) {
    if (s.empty()) return std::nullopt;
    size_t i = (s[0] == '-' || s[0] == '+') ? 1 : 0;
    if (i == s.size()) return std::nullopt;
    long long v = 0;
    for (; i < s.size(); i++) {
        if (s[i] < '0' || s[i] > '9') return std::nullopt;
        v = v * 10 + (s[i] - '0');
        if (v > 2147483648LL) return std::nullopt;
    }
    if (s[0] == '-') v = -v;
    if (v > 2147483647LL || v < -2147483648LL) return std::nullopt;
    return int(v);
}

inline OldstyleStyle style() {
    OldstyleStyle st;
    const Settings& s = Settings::current();
    for (int p = 0; p < OldstyleStyle::COUNT; p++) {
        const auto v = parseInt(s.getString(std::string("pref_oldstyle_style_") + OldstyleStyle::key(p), "0"));
        st.set(p, v ? *v : 0);
    }
    return st;
}

/// value(): a number row within its range, the fallback when absent, not a number or at the row's own default
inline float value(const std::string& key, float fallback) {
    const float v = Settings::current().hybridValue(key, fallback);
    if (!std::isfinite(v)) return fallback;
    static const std::map<std::string, float> steps = {{"oldstyle_fusion_base", 0.0001f}, {"oldstyle_v1_hdr_max", 0.01f}};
    const auto step = steps.find(key);
    if (jmath::abs(v - fallback) <= (step != steps.end() ? step->second * 0.5f : 0.f) + 1e-5f * jmath::max(1.f, jmath::abs(fallback)))
        return fallback;
    const auto range = Settings::bounds("pref_scam_hybrid_" + key);
    return range ? float(std::max((*range)[0], std::min((*range)[1], double(v)))) : v;
}

inline float scaled(const std::string& key, float fallback, float scale) {
    const float shown = fallback * scale, v = value(key, shown);
    return v == shown ? fallback : v / scale;
}

inline int rounded(float v) { return int(Settings::javaRound(v)); }

inline OldstyleAe::Params params(bool v2) {
    const Settings& s = Settings::current();
    OldstyleAe::Params p;
    p.v31 = v2;
    p.maxGain = value("oldstyle_max_gain", p.maxGain);
    p.saturation = value("oldstyle_v2_saturation", p.saturation);
    p.lowFrequency = s.hybridSwitch("oldstyle_v1_lowfreq", p.lowFrequency);
    p.lfThreshold = rounded(value("oldstyle_v1_lowfreq_threshold", float(p.lfThreshold)));
    p.dehaze = s.hybridSwitch("oldstyle_dehaze", p.dehaze);
    p.maxDark = rounded(value("oldstyle_dehaze_max_dark", float(p.maxDark)));
    p.percentile = value("oldstyle_dehaze_percentile", p.percentile);
    p.cornerStrength = value("oldstyle_dehaze_corner", p.cornerStrength);
    p.dehazeShading = s.hybridSwitch("oldstyle_dehaze_shading", p.dehazeShading);
    p.outSat = value("oldstyle_out_sat", p.outSat);
    p.vibrance = value("oldstyle_out_vibrance", p.vibrance);
    p.satPerStop = value("oldstyle_out_sat_per_stop", p.satPerStop);
    p.vibrancePerStop = value("oldstyle_out_vibrance_per_stop", p.vibrancePerStop);
    const std::string hdr = s.hybridString("oldstyle_hdr", "auto");
    p.hdrMode = hdr == "on" ? OldstyleAe::HDR_ON : hdr == "off" ? OldstyleAe::HDR_OFF : OldstyleAe::HDR_AUTO;
    p.baseEv = value("oldstyle_ev", p.baseEv);
    p.shortWhite = value("oldstyle_highlight_target", p.shortWhite);
    p.highPercentile = scaled("oldstyle_highlight_percentile", p.highPercentile, 100.f);
    p.shadowEv = value("oldstyle_shadow_ev", p.shadowEv);
    p.centerSigma = value("oldstyle_center", p.centerSigma);
    p.fusionBase = value("oldstyle_fusion_base", p.fusionBase);
    p.v1HdrMin = value("oldstyle_v1_hdr_min", p.v1HdrMin);
    p.v1HdrMax = value("oldstyle_v1_hdr_max", p.v1HdrMax);
    p.v1RatioCap = value("oldstyle_v1_hdr_cap", p.v1RatioCap);
    p.v2HdrMin = value("oldstyle_v2_hdr_min", p.v2HdrMin);
    p.v2RatioMax = value("oldstyle_v2_hdr_max", p.v2RatioMax);
    p.v2Forgiveness = value("oldstyle_v2_hdr_forgive", p.v2Forgiveness);
    p.curveMid = value("oldstyle_curve_mid", p.curveMid);
    p.curveContrast = value("oldstyle_curve_contrast", p.curveContrast);
    p.memoryShare = scaled("oldstyle_v2_memory", p.memoryShare, 100.f);
    p.ccmStrength = value("oldstyle_v1_ccm", p.ccmStrength);
    p.lfStrength = value("oldstyle_v1_lowfreq_strength", p.lfStrength);
    p.lfChroma = s.hybridSwitch("oldstyle_v1_lowfreq_chroma", p.lfChroma);
    p.burstFrames = rounded(value("oldstyle_burst_frames", float(p.burstFrames)));
    p.vfTone = s.hybridSwitch("oldstyle_vf_tone", p.vfTone);
    p.vfEv = value("oldstyle_vf_ev", p.vfEv);
    p.vfMatch = value("oldstyle_vf_match", p.vfMatch);
    p.vfSmooth = value("oldstyle_vf_smooth", p.vfSmooth);
    return p;
}

/// ShotLook.Oldstyle: what an Oldstyle shot takes of the mode (null outside it); the style's exposure is the tone's
struct Shot {
    bool v2 = false, postview = false;
    OldstyleAe::Params params;
    OldstyleStyle style;
    std::string toString() const { return std::string("Oldstyle ") + (v2 ? "V2" : "V1") + " " + style.toString(); }
};

inline std::optional<Shot> now() {
    if (!on()) return std::nullopt;
    Shot o;
    o.v2 = v2();
    o.postview = v2Postview();
    o.params = params(o.v2);
    o.style = style();
    o.params.exposureEv = o.style.exposureEv();
    return o;
}

} // namespace OldstyleMode

} // namespace scam_post
