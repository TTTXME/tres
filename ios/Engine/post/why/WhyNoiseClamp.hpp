// WhyNoiseClamp.java one to one, and WhySkyMask.evOffset.
#pragma once

#include "WhyAe.hpp"
#include "../Settings.hpp"

#include <optional>
#include <string>
#include <vector>

namespace scam_post {
namespace WhyNoiseClamp {

constexpr double EXPONENT = 0.8;
constexpr int STEP = 8;
constexpr double MEDIAN_CODE = 64.0;
constexpr float EXPECTED_SHARE = 0.55f;

inline bool wanted(bool fullRes) {
    const float mode = Settings::current().scamDevNumber("ae_clamp_stack", 1.f);
    return mode >= 2.f || (mode >= 1.f && fullRes);
}
inline float effectiveFrames(const std::optional<std::vector<uint8_t>>& map, int width, int height, float medianFrames, int normals) {
    if (!map || width <= 0 || height <= 0 || size_t(width) * size_t(height) != map->size() || normals <= 1 || !(medianFrames > 0.f)
        || std::isinf(medianFrames)) return 1.f;
    int histogram[256] = {};
    long count = 0;
    for (int y = STEP / 2; y < height; y += STEP) {
        const size_t row = size_t(y) * size_t(width);
        for (int x = STEP / 2; x < width; x += STEP) {
            const int v = (*map)[row + size_t(x)];
            if (v > 0) { histogram[v]++; count++; }
        }
    }
    if (count == 0) return 1.f;
    const long target = (count + 3) / 4;
    int p25 = 1;
    long seen = 0;
    for (int v = 1; v < 256; v++) {
        seen += histogram[v];
        if (seen >= target) { p25 = v; break; }
    }
    const double frames = std::min(p25 * double(medianFrames), p25 * double(normals)) / MEDIAN_CODE;
    return float(std::max(1.0, std::min(double(normals), frames)));
}
inline float fusionFrames(const std::optional<std::vector<uint8_t>>& map, int width, int height, float medianFrames, int normals) {
    const bool usable = map && width > 0 && height > 0 && size_t(width) * size_t(height) == map->size() && medianFrames > 0.f && !std::isinf(medianFrames);
    if (usable) return effectiveFrames(map, width, height, medianFrames, normals);
    return normals > 1 ? std::max(1.f, std::min(float(normals), EXPECTED_SHARE * float(normals))) : 1.f;
}
inline double modelScale(float effective) { return effective > 1.f ? std::pow(double(effective), -EXPONENT) : 1.0; }

inline std::optional<std::string> apply(WhyAe::AeSettings& s, bool fullRes, const std::optional<std::vector<uint8_t>>& map, int width, int height,
                                        float medianFrames, int normals) {
    if (!(s.noiseS > 0 || s.noiseO > 0) || !wanted(fullRes)) return std::nullopt;
    const float effective = effectiveFrames(map, width, height, medianFrames, normals);
    const double before = WhyAe::gainClamp(s.noiseS, s.noiseO, s.noiseMax);
    char buf[256];
    if (!(effective > 1.f)) {
        std::snprintf(buf, sizeof buf, "AE noise clamp: Neff=1 (%s) clamp %.3f kept", !map ? "no frame map"
                      : size_t(width) * size_t(height) != map->size() ? "frame map of another size" : !(medianFrames > 0.f)
                      ? "no median coverage in the worker's report" : normals <= 1 ? "one N frame" : "map at one frame", before);
        return std::string(buf);
    }
    const double k = modelScale(effective);
    s.noiseS *= k;
    s.noiseO *= k;
    const double after = WhyAe::gainClamp(s.noiseS, s.noiseO, s.noiseMax);
    std::snprintf(buf, sizeof buf, "AE noise clamp: Neff=%.2f (of %d N, median coverage %.2f) clamp %.3f -> %.3f", double(effective), normals,
                  double(medianFrames), before, after);
    return std::string(buf);
}

} // namespace WhyNoiseClamp

namespace WhySkyMaskEv {
inline double log2d(double v) { return std::log(v) / std::log(2.0); }
inline const double SAT_EV = std::log(9.6) / std::log(2.0);
/// WhySkyMask.evOffset: the EV100 of a whyLow value of 1
inline float evOffset(int iso, double exposureSeconds, float aperture, float inScale) {
    if (iso <= 0 || !(exposureSeconds > 0) || !(aperture > 0.f) || !(inScale > 0.f)) return NAN;
    const double n2 = double(aperture) * double(aperture);
    return float(log2d(n2 / exposureSeconds) - log2d(iso / 100.0) + SAT_EV - log2d(double(inScale)));
}
} // namespace WhySkyMaskEv

} // namespace scam_post
