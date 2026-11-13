// PostPipeline.Run + BuildDefaultPipeline one to one (the photo route of an SHDR shot): the light nodes before the tone (the lens
// filter, the film's camera diffusion and halation), «Why тон» V1 or V2 (a film of the old route keeps V1), the «Oldstyle» mode's
// tone and style (not with a film: the Why tone is used), the reference film route (ReferenceFilm / ReferenceFilmMix, only when the
// spectral film engine is available: never in this build) and the tail (PostPipeline.tail): «Текстура» (not with a film), the
// final size, the film's grain, the rotation. Ultra HDR (pref_ultrahdr_key, not with a film: PostPipeline.gainMapWanted): the
// linear snapshot (LinearExposure) before the tone and, after Run, the gain map (UltraHdr.hpp, as ScamProcessor runs them).
#pragma once

#include "UltraHdr.hpp"
#include "nodes/FilmGrain.hpp"
#include "nodes/FilmHalation.hpp"
#include "nodes/LensFilter.hpp"
#include "nodes/LinearExposure.hpp"
#include "nodes/OldstyleTone.hpp"
#include "nodes/ReferenceFilm.hpp"

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
/// alone decides.
inline bool gainMapPass() { return Settings::current().getBoolean("pref_ultrahdr_key", false); }

/// PostPipeline.gainMapWanted: the gain map only without a film (its boost would undo the film's highlights and grain)
inline bool gainMapWanted(bool setting, const FilmSettings* film) { return setting && !film; }

/// PostPipeline.V2_MAX_PIXELS: the largest grid «Why тон» V2 runs on (the 200 MP grids keep V1)
constexpr int64_t V2_MAX_PIXELS = 100000000LL;

/// PostPipeline.takeFilm: the shot's film snapshot (the settings now: iOS has no ShotLook of the press yet), the chosen LUT and its
/// strength; a film of the old route whose data cannot be read gives the photo no film
inline void takeFilm(PostPipeline& p) {
    Settings& prefs = Settings::current();
    p.film = FilmSim::current(prefs);
    p.filmUserStrength = std::max(0.f, std::min(1.f, float(readNumber(prefs.get(FilmLut::STRENGTH_KEY), 100.0)) / 100.f));
    p.filmUserLut = p.filmUserStrength > 0.f ? prefs.getString(FilmLut::KEY, "") : "";
    p.filmTaken = true;
    const bool nativeFilm = p.film && prefs.scamDevSwitch("film_reference", true) && referenceFilmSupported(p.film.get());
    if (p.film && !nativeFilm && !FilmChain::scene(*p.film, p.filmUserLut, p.filmUserStrength)) {
        logI("SCAM_PIPELINE", "film data unreadable (" + p.film->stock->id + "): the photo without the film");
        p.film = nullptr;
    }
}

/// PostPipeline.lightNodes: the shot's lens filter, the film's camera diffusion, the film's halation (each only when it acts)
inline std::vector<std::unique_ptr<Node>> lightNodes(const LensFilters::Choice& shotFilter, const std::shared_ptr<const FilmSettings>& film) {
    std::vector<std::unique_ptr<Node>> out;
    if (auto n = LensFilter::of(shotFilter)) out.push_back(std::move(n));
    if (auto n = LensFilter::film(film)) out.push_back(std::move(n));
    if (auto n = FilmHalation::from(film)) out.push_back(std::move(n));
    return out;
}

inline void tail(PostPipeline& p, const std::shared_ptr<const FilmSettings>& film, bool referenceFilm);

inline void buildDefaultPipeline(PostPipeline& p) {
    Settings& prefs = Settings::current();
    if (p.scamRgb.empty()) throw std::runtime_error("no merge route: the photo pipeline takes the RGB of Hybrid or SCAM HDR");
    p.add(std::make_unique<ScamRgb>());
    p.add(std::make_unique<ScamHdrDenoise>());
    if (p.merge.lastHlMask) p.add(std::make_unique<WhyHlDezipper>(*p.merge.lastHlMask));
    if (prefs.hybridSwitch("denoise", true)) p.add(std::make_unique<WhyMsDenoise>());
    // P228 the lens filter (of the settings now: the iPhone has no press snapshot yet), P281 / P380 the film's light nodes; the
    // reference film route: the lens filter, the engine, then the film's own effects (skipped once the engine drew the picture)
    const LensFilters::Choice shotFilter = LensFilters::Choice::now(prefs);
    const std::shared_ptr<const FilmSettings> film = p.film;
    const bool referenceFilm = film && prefs.scamDevSwitch("film_reference", true) && referenceFilmSupported(film.get());
    if (referenceFilm) {
        for (auto& n : lightNodes(shotFilter, nullptr)) p.add(std::move(n));
        p.add(std::make_unique<ReferenceFilm>());
        std::vector<std::unique_ptr<Node>> effects;
        if (auto n = LensFilter::film(film)) effects.push_back(std::move(n));
        if (auto n = FilmHalation::from(film)) effects.push_back(std::move(n));
        for (auto& n : effects) { p.referenceFilmEffects.insert(n.get()); p.add(std::move(n)); }
    } else {
        for (auto& n : lightNodes(shotFilter, film)) p.add(std::move(n));
    }
    // LinearExposure only keeps the Ultra HDR linear snapshot
    if (p.captureDemosaic) p.add(std::make_unique<LinearExposure>());
    // P630 «Oldstyle»: the mode's tone (V1 2.7 / V2 3.1) in place of «Why тон», then its style; a film keeps the Why tone
    const auto oldstyle = OldstyleMode::now();
    if (oldstyle && film) logI("SCAM_PIPELINE", "Oldstyle mode with a film simulation: the Why tone used");
    if (oldstyle && !film) {
        p.add(std::make_unique<OldstyleTone>(oldstyle->v2, oldstyle->params, oldstyle->postview));
        if (!oldstyle->style.neutralAfterTone()) p.add(std::make_unique<OldstyleStyleNode>(oldstyle->style));
        logI("SCAM_PIPELINE", std::string("tone Oldstyle ") + (oldstyle->v2 ? "V2" : "V1") + " style " + oldstyle->style.toString());
        tail(p, nullptr, false);
        return;
    }
    // P614 «Why тон» V2 (PreferenceKeys.whyToneV2): the reference tone instead of V1's WhyStats / WhyFusion / WhyCombine, with our
    // sharpening between them; a film of the old route or a grid above V2_MAX_PIXELS keeps V1
    const int64_t pixels = p.mParameters.rawSize ? int64_t(p.mParameters.rawSize->x) * p.mParameters.rawSize->y : 0;
    const bool v2Chosen = prefs.hybridString("tone_version", "v1") == "v2";
    const bool v2 = v2Chosen && (!film || referenceFilm) && pixels <= V2_MAX_PIXELS;
    if (v2Chosen && !v2)
        logI("SCAM_PIPELINE", std::string("WHY tone V2 chosen, V1 used (") + (film && !referenceFilm ? "film of the old route" : "above 100 MP") + ")");
    std::vector<std::unique_ptr<Node>> tone;
    if (v2) {
        tone.push_back(std::make_unique<WhyV2Stats>());
        tone.push_back(std::make_unique<WhyLumaSharpen>());
        tone.push_back(std::make_unique<WhyV2Highlights>());
        tone.push_back(std::make_unique<WhyV2Fusion>());
        tone.push_back(std::make_unique<WhyV2Tone>());
        logI("SCAM_PIPELINE", "WHY tone V2 (WhyV2Stats/WhyLumaSharpen/WhyV2Highlights/WhyV2Fusion/WhyV2Tone)");
    } else {
        auto stats = std::make_unique<WhyStats>();
        auto sharpen = std::make_unique<WhyLumaSharpen>();
        stats->earlySharpen = sharpen.get();
        tone.push_back(std::move(stats));
        tone.push_back(std::make_unique<WhyFusion>());
        tone.push_back(std::move(sharpen));
        tone.push_back(std::make_unique<WhyCombine>());
    }
    for (auto& n : tone) {
        if (referenceFilm) p.referenceFilmTone.insert(n.get());
        p.add(std::move(n));
    }
    if (referenceFilm) p.add(std::make_unique<ReferenceFilmMix>());
    tail(p, film, referenceFilm);
}

/// PostPipeline.tail: texture on request (not with a film), the final size, the film's grain, the rotation
inline void tail(PostPipeline& p, const std::shared_ptr<const FilmSettings>& film, bool referenceFilm) {
    Settings& prefs = Settings::current();
    const bool texture = prefs.hybridSwitch("why_texture", false);
    logI("SCAM_PIPELINE", std::string("WHY tail: sharpen=why (WhyLumaSharpen) texture=") + (texture ? "true" : "false")
         + (texture && film ? " (off: film simulation)" : ""));
    if (texture && !film) p.add(std::make_unique<ScamLocalContrast>());
    if (p.finalSize) p.add(std::make_unique<HybridFinalResize>(*p.finalSize, prefs.hybridDownsampler()));
    if (auto grain = FilmGrain::from(film)) {
        if (referenceFilm) p.referenceFilmEffects.insert(grain.get());
        p.add(std::move(grain));
    }
    p.add(std::make_unique<RotateWatermark>(p.getRotation()));
}

/// PostPipeline.Run: the shot's parameters and merge in, the photo out
inline PostOutput runPostPipeline(PostPipeline& p) {
    PostGlMode::invalidateFlag() = Settings::current().scamDevSwitch("post_invalidate", true);
    if (filmAssetsRoot().empty() && p.glProg) filmAssetsRoot() = p.glProg->shadersRoot() + "/../film";
    // P369: the film snapshot (once per shot), before the gain-map decision
    if (!p.filmTaken) takeFilm(p);
    p.captureDemosaic = gainMapWanted(gainMapPass(), p.film.get());
    p.referenceFilmApplied = false;
    p.referenceFilmTexture = nullptr;
    p.referenceFilmSettings = nullptr;
    p.referenceFilmUserLut = nullptr;
    p.referenceFilmEffects.clear();
    p.referenceFilmTone.clear();
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
    if (gainMapWanted(gainMapPass(), p.film.get())) out.gainMap = UltraHdr::gainMap(p, out.rgba, out.width, out.height);
    releaseDemosaicLinear(p);
    return out;
}

} // namespace scam_post
