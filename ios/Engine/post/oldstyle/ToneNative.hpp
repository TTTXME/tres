// «Oldstyle»: the owner's tone-port module as OldstyleNative.java calls it (the JNI of app/src/main/cpp/oldstyle-tone-jni.cpp
// without the JNI): the threads of its row loops and the tone of one planar int16 frame into RGB8. Declarations only, so the post
// pipeline's headers compile without the module's include path; the definitions are ToneNative.cpp (the app compiles it with
// the module's sources, project.yml) - a host tool includes ToneUnity.hpp (the module's sources and ToneNative.cpp in its unit).
#pragma once

#include <cstdint>

namespace scam_post {
namespace ToneNative {

/// OldstyleNative.WHITE: the module's white at unity gain
constexpr int WHITE = 4095;
/// the profiles of render
constexpr int V1 = 1, V2 = 2;

/// OldstyleNative.setThreads: 1 the single-thread path, 0 or less the cores (at most 8); returns the count in use
int setThreads(int count);

/// OldstyleNative.render (oldstyle-tone-jni.cpp): planar int16 R, G, B (width x height each) into out (width x height x 3 RGB8).
/// ccm9: V1's row-major colour matrix (null: identity); post: OldstyleAe.Params.post (postCount entries; fewer than 12: none);
/// shading: the dehaze's U / V gain map (shadingW x shadingH x 2, null: the unit grid); info: 8 floats. Throws on a module error.
void render(int version, const int16_t* planar, int width, int height, const float* ccm9, float gainShort, float gainLong, bool hdr,
            bool postview, float saturation, const float* post, int postCount, const float* shading, int shadingW, int shadingH,
            float* info, uint8_t* out);

} // namespace ToneNative
} // namespace scam_post
