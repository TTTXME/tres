// PostPipeline.Run + BuildDefaultPipeline (the photo route of an SHDR shot, «Why тон» V1) one to one for what an iPhone shot
// takes: no film simulation, no lens filter, no Oldstyle, no Ultra HDR. V2 and the other routes throw until they are ported.
#pragma once

#include "nodes/RotateWatermark.hpp"
#include "nodes/ScamHdrDenoise.hpp"
#include "nodes/ScamRgb.hpp"
#include "nodes/WhyCombine.hpp"
#include "nodes/WhyFusion.hpp"
#include "nodes/WhyHlDezipper.hpp"
#include "nodes/WhyLumaSharpen.hpp"
#include "nodes/WhyMsDenoise.hpp"
#include "nodes/WhyStats.hpp"

namespace scam_post {

struct PostOutput {
    std::vector<uint8_t> rgba; // RGBA8 rows as the Java bitmap holds them
    int width = 0, height = 0;
};

inline void buildDefaultPipeline(PostPipeline& p) {
    Settings& prefs = Settings::current();
    if (p.scamRgb.empty()) throw std::runtime_error("no merge route: the photo pipeline takes the RGB of Hybrid or SCAM HDR");
    p.add(std::make_unique<ScamRgb>());
    p.add(std::make_unique<ScamHdrDenoise>());
    if (p.merge.lastHlMask) p.add(std::make_unique<WhyHlDezipper>(*p.merge.lastHlMask));
    if (prefs.hybridSwitch("denoise", true)) p.add(std::make_unique<WhyMsDenoise>());
    if (prefs.getBoolean("pref_oldstyle_mode", false)) throw std::runtime_error("post port: Oldstyle is not ported");
    if (prefs.hybridString("tone_version", "v1") == "v2") throw std::runtime_error("post port: «Why тон» V2 is not ported yet");
    auto stats = std::make_unique<WhyStats>();
    auto sharpen = std::make_unique<WhyLumaSharpen>();
    stats->earlySharpen = sharpen.get();
    p.add(std::move(stats));
    p.add(std::make_unique<WhyFusion>());
    p.add(std::move(sharpen));
    p.add(std::make_unique<WhyCombine>());
    if (prefs.hybridSwitch("why_texture", false)) throw std::runtime_error("post port: «Текстура» (ScamLocalContrast) is not ported yet");
    if (p.finalSize) throw std::runtime_error("post port: HybridFinalResize is not ported yet");
    p.add(std::make_unique<RotateWatermark>(p.getRotation()));
}

/// PostPipeline.Run: the shot's parameters and merge in, the photo out
inline PostOutput runPostPipeline(PostPipeline& p) {
    PostGlMode::invalidateFlag() = Settings::current().scamDevSwitch("post_invalidate", true);
    p.workSize = *p.mParameters.rawSize;
    p.computeNoise();
    p.cropSize = *p.mParameters.rawSize;
    p.finalSize.reset();
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
