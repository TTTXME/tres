// PostPipeline.Run + BuildDefaultPipeline (the photo route of an SHDR shot: «Why тон» V1 or V2, or the «Oldstyle» mode's tone and
// style) one to one for what an iPhone shot takes: no film simulation, no lens filter. The other routes throw until they are ported.
// Ultra HDR (pref_ultrahdr_key): the linear snapshot (LinearExposure) before the tone and, after Run, the gain map of the photo
// (UltraHdr.hpp: RunHDRGainMap + GainMapComputer, as ScamProcessor runs them).
// The tail (PostPipeline.tail): «Текстура» (ScamLocalContrast), the final size (HybridFinalResize), the rotation.
#pragma once

#include "UltraHdr.hpp"
#include "nodes/LinearExposure.hpp"
#include "nodes/OldstyleTone.hpp"

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
    /// Ultra HDR on: the gain map of the photo (pixel-aligned, its rows as the photo's) with its metadata; none when off or failed
    std::optional<GainMapComputer::Result> gainMap;
};

/// Settings.gainMapPass on iOS: the top row's Ultra HDR (pref_ultrahdr_key). Android also asks for a JPEG (PhotoFormat.ultraHdrApplies)
/// or «HDR в HEIC / AVIF»; the iPhone writes the gain map into the JPEG and the HEIC alike (ImageIO's ISO gain map), so the switch
/// alone decides. No film on iOS: PostPipeline.gainMapWanted is the switch.
inline bool gainMapPass() { return Settings::current().getBoolean("pref_ultrahdr_key", false); }

/// PostPipeline.V2_MAX_PIXELS: the largest grid «Why тон» V2 runs on (the 200 MP grids keep V1)
constexpr int64_t V2_MAX_PIXELS = 100000000LL;

inline void tail(PostPipeline& p);

inline void buildDefaultPipeline(PostPipeline& p) {
    Settings& prefs = Settings::current();
    if (p.scamRgb.empty()) throw std::runtime_error("no merge route: the photo pipeline takes the RGB of Hybrid or SCAM HDR");
    p.add(std::make_unique<ScamRgb>());
    p.add(std::make_unique<ScamHdrDenoise>());
    if (p.merge.lastHlMask) p.add(std::make_unique<WhyHlDezipper>(*p.merge.lastHlMask));
    if (prefs.hybridSwitch("denoise", true)) p.add(std::make_unique<WhyMsDenoise>());
    // no lens filter or film nodes on iOS; LinearExposure only keeps the Ultra HDR linear snapshot
    if (p.captureDemosaic) p.add(std::make_unique<LinearExposure>());
    // P630 «Oldstyle»: the mode's tone (V1 2.7 / V2 3.1) in place of «Why тон», then its style (no film here)
    if (const auto oldstyle = OldstyleMode::now()) {
        p.add(std::make_unique<OldstyleTone>(oldstyle->v2, oldstyle->params, oldstyle->postview));
        if (!oldstyle->style.neutralAfterTone()) p.add(std::make_unique<OldstyleStyleNode>(oldstyle->style));
        logI("SCAM_PIPELINE", std::string("tone Oldstyle ") + (oldstyle->v2 ? "V2" : "V1") + " style " + oldstyle->style.toString());
        tail(p);
        return;
    }
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
    tail(p);
}

/// PostPipeline.tail: texture on request (not with a film), the final size, the rotation (no film grain without a film)
inline void tail(PostPipeline& p) {
    Settings& prefs = Settings::current();
    const bool texture = prefs.hybridSwitch("why_texture", false);
    logI("SCAM_PIPELINE", std::string("WHY tail: sharpen=why (WhyLumaSharpen) texture=") + (texture ? "true" : "false"));
    if (texture && p.film == nullptr) p.add(std::make_unique<ScamLocalContrast>());
    if (p.finalSize) p.add(std::make_unique<HybridFinalResize>(*p.finalSize, prefs.hybridDownsampler()));
    p.add(std::make_unique<RotateWatermark>(p.getRotation()));
}

/// PostPipeline.Run: the shot's parameters and merge in, the photo out
inline PostOutput runPostPipeline(PostPipeline& p) {
    PostGlMode::invalidateFlag() = Settings::current().scamDevSwitch("post_invalidate", true);
    p.captureDemosaic = gainMapPass();
    p.mCaptured = false;
    releaseDemosaicLinear(p);
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
    // ScamProcessor: the gain-map pass on the photo as the pipeline produced it (a failure leaves the photo SDR)
    if (gainMapPass()) out.gainMap = UltraHdr::gainMap(p, out.rgba, out.width, out.height);
    releaseDemosaicLinear(p);
    return out;
}

} // namespace scam_post
