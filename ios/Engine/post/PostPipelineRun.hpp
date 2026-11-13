// PostPipeline.Run + BuildDefaultPipeline (the photo route of an SHDR shot, «Why тон» V1 or V2) one to one for what an iPhone
// shot takes: no film simulation, no lens filter, no Oldstyle, no Ultra HDR. The other routes throw until they are ported.
// The tail (PostPipeline.tail): «Текстура» (ScamLocalContrast), the final size (HybridFinalResize), the rotation.
#pragma once

#include "nodes/HybridFinalResize.hpp"
#include "nodes/RotateWatermark.hpp"
#include "nodes/ScamHdrDenoise.hpp"
#include "nodes/ScamLocalContrast.hpp"
#include "nodes/ScamRgb.hpp"
#include "nodes/WhyCombine.hpp"
#include "nodes/WhyFusion.hpp"
#include "nodes/WhyHlDezipper.hpp"
#include "nodes/WhyLumaSharpen.hpp"
#include "nodes/WhyMsDenoise.hpp"
#include "nodes/WhyStats.hpp"
#include "nodes/WhyV2Fusion.hpp"
#include "nodes/WhyV2Highlights.hpp"
#include "nodes/WhyV2Stats.hpp"
#include "nodes/WhyV2Tone.hpp"

namespace scam_post {

struct PostOutput {
    std::vector<uint8_t> rgba; // RGBA8 rows as the Java bitmap holds them
    int width = 0, height = 0;
};

/// PostPipeline.V2_MAX_PIXELS: the largest grid «Why тон» V2 runs on (the 200 MP grids keep V1)
constexpr int64_t V2_MAX_PIXELS = 100000000LL;

inline void buildDefaultPipeline(PostPipeline& p) {
    Settings& prefs = Settings::current();
    if (p.scamRgb.empty()) throw std::runtime_error("no merge route: the photo pipeline takes the RGB of Hybrid or SCAM HDR");
    p.add(std::make_unique<ScamRgb>());
    p.add(std::make_unique<ScamHdrDenoise>());
    if (p.merge.lastHlMask) p.add(std::make_unique<WhyHlDezipper>(*p.merge.lastHlMask));
    if (prefs.hybridSwitch("denoise", true)) p.add(std::make_unique<WhyMsDenoise>());
    if (prefs.getBoolean("pref_oldstyle_mode", false)) throw std::runtime_error("post port: Oldstyle is not ported");
    // P614 «Why тон» V2 (PreferenceKeys.whyToneV2, no film here): the reference tone instead of V1's WhyStats / WhyFusion /
    // WhyCombine, with our sharpening between them; a grid above V2_MAX_PIXELS keeps V1
    const int64_t pixels = p.mParameters.rawSize ? int64_t(p.mParameters.rawSize->x) * p.mParameters.rawSize->y : 0;
    const bool v2Chosen = prefs.hybridString("tone_version", "v1") == "v2";
    const bool v2 = v2Chosen && pixels <= V2_MAX_PIXELS;
    if (v2Chosen && !v2) logI("SCAM_PIPELINE", "WHY tone V2 chosen, V1 used (above 100 MP)");
    if (v2) {
        p.add(std::make_unique<WhyV2Stats>());
        p.add(std::make_unique<WhyLumaSharpen>());
        p.add(std::make_unique<WhyV2Highlights>());
        p.add(std::make_unique<WhyV2Fusion>());
        p.add(std::make_unique<WhyV2Tone>());
        logI("SCAM_PIPELINE", "WHY tone V2 (WhyV2Stats/WhyLumaSharpen/WhyV2Highlights/WhyV2Fusion/WhyV2Tone)");
    } else {
        auto stats = std::make_unique<WhyStats>();
        auto sharpen = std::make_unique<WhyLumaSharpen>();
        stats->earlySharpen = sharpen.get();
        p.add(std::move(stats));
        p.add(std::make_unique<WhyFusion>());
        p.add(std::move(sharpen));
        p.add(std::make_unique<WhyCombine>());
    }
    // PostPipeline.tail: texture on request (not with a film), the final size, the rotation (no film grain without a film)
    const bool texture = prefs.hybridSwitch("why_texture", false);
    logI("SCAM_PIPELINE", std::string("WHY tail: sharpen=why (WhyLumaSharpen) texture=") + (texture ? "true" : "false"));
    if (texture && p.film == nullptr) p.add(std::make_unique<ScamLocalContrast>());
    if (p.finalSize) p.add(std::make_unique<HybridFinalResize>(*p.finalSize, prefs.hybridDownsampler()));
    p.add(std::make_unique<RotateWatermark>(p.getRotation()));
}

/// PostPipeline.Run: the shot's parameters and merge in, the photo out
inline PostOutput runPostPipeline(PostPipeline& p) {
    PostGlMode::invalidateFlag() = Settings::current().scamDevSwitch("post_invalidate", true);
    p.linearDisplayGain = 1.f;
    p.whyV2 = nullptr;
    p.workSize = *p.mParameters.rawSize;
    p.computeNoise();
    p.cropSize = *p.mParameters.rawSize;
    p.finalSize.reset();
    p.finalResized = false;
    if (p.mParameters.hybridFinalSize && p.mParameters.hybridFinalSize->x < p.mParameters.rawSize->x
        && p.mParameters.hybridFinalSize->y < p.mParameters.rawSize->y) {
        p.finalSize = *p.mParameters.hybridFinalSize;
        p.cropSize = *p.finalSize;
    }
    const Point rotated = p.getRotatedCoords(p.finalSize ? *p.finalSize : *p.mParameters.rawSize);
    GLDrawParams::TileSize() = int64_t(p.mParameters.rawSize->x) * p.mParameters.rawSize->y < 8000000 ? 8 : 256;
    buildDefaultPipeline(p);
    PostOutput out;
    out.rgba = p.runAllToRgba(rotated);
    out.width = rotated.x;
    out.height = rotated.y;
    for (GLTexture* t : std::vector<GLTexture*>(openTextures().begin(), openTextures().end())) t->close();
    return out;
}

} // namespace scam_post
