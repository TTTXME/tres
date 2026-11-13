// «Oldstyle»: ToneNative.hpp's definitions - oldstyle-tone-jni.cpp's setThreads and render (its post_from and the arguments'
// checks) without the JNI, on the module's own core (app/src/main/cpp/oldstyle-tone-core.h, unchanged). The app compiles this
// unit with the module's sources (project.yml: third_party/tone_port, -ffp-contract=off as the Android build); a host tool takes
// it through ToneUnity.hpp.
#include "ToneNative.hpp"

#include "oldstyle-tone-core.h"

#include <stdexcept>
#include <string>
#include <vector>

namespace scam_post {
namespace ToneNative {

namespace {

oldstyle::Post postFrom(const float* array, int count) {
    oldstyle::Post p;
    if (!array || count < 12) return p;
    float v[15] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f};
    for (int i = 0; i < std::min(15, count); ++i) v[i] = array[i];
    p.dehaze = v[0] > .5f;
    p.max_dark = std::clamp(static_cast<int>(std::lround(v[1])), 0, 255);
    p.percentile = v[2];
    p.corner = v[3];
    p.saturation = v[4] > .5f;
    p.sat = v[5];
    p.vibrance = v[6];
    p.sat_per_stop = v[7];
    p.vibrance_per_stop = v[8];
    p.low_frequency = v[9] > .5f;
    p.lf_threshold = std::clamp(static_cast<int>(std::lround(v[10])), 0, 255);
    p.lf_chroma = v[11] > .5f;
    p.lf_strength = v[12] == v[12] ? std::clamp(v[12], 0.f, 1.f) : 1.f;
    p.curve_mid = v[13] == v[13] ? v[13] : 0.f;
    p.curve_contrast = v[14] == v[14] ? v[14] : 0.f;
    return p;
}

} // namespace

int setThreads(int count) {
    tone::set_threads(count);
    return tone::threads();
}

void render(int version, const int16_t* planar, int width, int height, const float* ccm9, float gainShort, float gainLong, bool hdr,
            bool postview, float saturation, const float* postArray, int postCount, const float* shadingArray, int shadingW, int shadingH,
            float* infoOut, uint8_t* out) {
    if (!planar || !out || width <= 0 || height <= 0 || !(gainShort > 0.f) || !(gainLong > 0.f))
        throw std::runtime_error("Oldstyle render: bad buffers or gains");
    const oldstyle::Post post = postFrom(postArray, postCount);
    std::vector<float> shading;
    if (shadingArray && shadingW >= 2 && shadingH >= 2) shading.assign(shadingArray, shadingArray + size_t(shadingW) * shadingH * 2);
    float info[8];
    float ccm[9] = {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
    if (ccm9) std::copy(ccm9, ccm9 + 9, ccm);
    try {
        oldstyle::render(version, planar, size_t(width), size_t(height), ccm, gainShort, gainLong, hdr, postview, saturation, post,
                         shading.empty() ? nullptr : shading.data(), shadingW, shadingH, info, out);
    } catch (const std::exception& e) {
        throw std::runtime_error(std::string("Oldstyle tone-port: ") + e.what());
    }
    if (infoOut) std::copy(info, info + 8, infoOut);
}

} // namespace ToneNative
} // namespace scam_post
