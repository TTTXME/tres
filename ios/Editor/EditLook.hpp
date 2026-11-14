// EditLook.java one to one (P619 «LUT»): the camera's LUT and film simulation on the editor's finished picture - the state's film
// (FilmSim.Settings of the tab's stock, print, format and sliders), the display table of FinishedLook (a LUT alone is the camera's
// own table; a film the first-phase FilmSim.lut with the LUT on its print: the reference model's DISPLAY table needs the Vulkan film
// engine, which this build has not - Android without it ends at the same table), the shader's mix, and the film's grain with the
// camera's FilmGrain numbers for the whole photo's long side. The film's tables and models are Engine/post/color (scam_post).
#pragma once

#include "EditMath.hpp"
#include "../Engine/post/color/FilmLut.hpp"

#include <optional>
#include <string>

namespace scam_edit {

namespace EditLook {

using scam_post::FilmSettings;
using scam_post::FilmLutP;

constexpr int GRAIN_LUT = 17;

/// EditLook.film: the state's film simulation (none: no stock chosen, an unknown id, or its strength 0)
inline std::shared_ptr<const FilmSettings> film(const EditState& s) {
    const scam_post::FilmStock* stock = s.film.empty() ? nullptr : scam_post::FilmSim::stock(s.film);
    if (!stock) return nullptr;
    const float strength = s.get(Param::FILM_AMOUNT) / 100.f;
    if (strength <= 0.f) return nullptr;
    return std::make_shared<FilmSettings>(*stock, scam_post::FilmSim::print(s.filmPrint, stock), scam_post::FilmSim::format(s.filmFormat),
                                          s.get(Param::FILM_GRAIN) / 100.f, 0.f, 0.f, s.get(Param::FILM_EXPOSURE), s.get(Param::FILM_PRINT_EV),
                                          s.get(Param::FILM_CONTRAST) / 100.f, 0.f, 0.f, strength, "", scam_post::FilmSim::DIFFUSION_DEFAULT,
                                          s.get(Param::FILM_DIR) / 100.f, scam_post::FilmAdvanced());
}
inline std::string lut(const EditState& s) { return s.get(Param::LUT_AMOUNT) > 0.f ? s.lut : std::string(); }
inline float lutStrength(const EditState& s) { return s.get(Param::LUT_AMOUNT) / 100.f; }
inline std::string tableKey(const EditState& s) { const auto f = film(s); return scam_post::FinishedLook::key(f.get(), lut(s), lutStrength(s)); }
inline bool on(const EditState& s) { return !tableKey(s).empty(); }
inline float strength(const EditState& s) { const auto f = film(s); return scam_post::FinishedLook::shaderStrength(f.get(), lutStrength(s)); }
inline std::string key(const EditState& s) {
    const std::string t = tableKey(s);
    if (t.empty()) return "";
    const auto f = film(s);
    return t + "|" + scam_post::jfmt::fixed(strength(s), 4) + (f ? "|" + f->format->id + "|" + scam_post::Pref::javaFloat(f->grain) : std::string());
}

/// EditLook.Grain: the camera's FilmGrain numbers for the photo's long side
struct Grain {
    bool reference = false;
    float um = 0, blurPx = 0, scale = 0, sigma = 0, cell = 0, sharedPart = 0;
    bool slide = false, mono = false;
    float seed = 0;
};
inline std::optional<Grain> grain(const FilmSettings* f, float longSide) {
    if (!f || f->format->longMm <= 0.f || f->grain <= 0.f || f->grainApplied() <= 0.f || longSide < 1.f) return std::nullopt;
    Grain g;
    g.reference = !f->stock->spectral.empty();
    g.um = f->pixelUm(std::max(1, int(scam_post::Settings::javaRound(longSide))));
    g.blurPx = scam_post::FilmSim::grainBlurUm(*f->format) / g.um;
    g.scale = f->grainApplied() / g.um;
    g.sigma = scam_post::FilmSim::classicGrainSigma(*f, g.um);
    g.cell = scam_post::FilmSim::classicGrainCell(*f, g.um);
    g.sharedPart = f->stock->mono() ? 1.f : .55f;
    g.slide = f->stock->reversal();
    g.mono = f->stock->mono();
    g.seed = float(scam_post::jfmt::absInt(scam_post::jfmt::hashCode(f->stock->id)) % 9973);
    return g;
}
/// how far the grain reaches across a tile edge (px): the reference noise's blur, else 0
inline int reach(const std::optional<Grain>& g) { return !g || !g->reference ? 0 : jint(std::ceil(3.0 * double(g->blurPx))) + 1; }
/// the export's overlap around a tile: the editor's own reach and the grain's
inline int overlap(const EditState& s, float longSide) {
    const auto f = film(s);
    return std::max(EditMath::overlapPx(longSide), reach(grain(f.get(), longSide)));
}

/// EditLook.Tables: the display table of a tableKey and the reference grain's LUT
struct Tables {
    std::string key;
    std::optional<scam_post::FinishedLook::Table> colour;
    FilmLutP grainLut;
    bool reference() const { return colour && colour->reference; }
};

/// EditLook.Applied: what the renderer applies
struct Applied {
    std::string key;
    FilmLutP lut;
    float strength = 0;
    std::shared_ptr<const FilmSettings> film;
    FilmLutP grainLut;
};

inline bool wantsGrainLut(const FilmSettings* f) { return f && !f->stock->spectral.empty() && f->format->longMm > 0.f && f->grain > 0.f; }

/// EditLook.applied: the look of s with t (the tables of its key), none when they are not its tables
inline std::optional<Applied> applied(const EditState& s, const std::optional<Tables>& t) {
    const std::string k = tableKey(s);
    if (k.empty() || !t || k != t->key || !t->colour) return std::nullopt;
    const auto f = film(s);
    return Applied{key(s) + (t->reference() ? "|ref" : "|first"), t->colour->lut, strength(s), f,
                   f && !f->stock->spectral.empty() ? t->grainLut : nullptr};
}

/// EditLook.blocking: the tables of the state (the preview's and the export's); none for nothing to apply
inline std::optional<Tables> blocking(const EditState& s) {
    const std::string k = tableKey(s);
    if (k.empty()) return std::nullopt;
    const auto f = film(s);
    const auto colour = scam_post::FinishedLook::blocking(f.get(), lut(s), lutStrength(s));
    if (!colour) return std::nullopt;
    FilmLutP g = wantsGrainLut(f.get()) ? scam_post::FilmSim::grainLut(*f, GRAIN_LUT) : nullptr;
    return Tables{k, colour, g};
}

} // namespace EditLook

} // namespace scam_edit
