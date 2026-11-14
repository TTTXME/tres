// The lens filters as the photo takes them, one to one: LensFilters.java (the ids, their numbering, Tune - the parameter and the
// fine controls - and its stored text, hazeGain), DiffusionModel.java (the diffusion families, the grade -> scatter table, the
// groups' exponentials, the halo's warmth, the shape) and DiffusionGauss.plan (every exponential as three Gaussians on the box
// pyramid's levels). The CPU previews of the Android settings (LensFilters.apply, DiffusionModel.levelWeights) are not ported.
#pragma once

#include "FilmSim.hpp"

namespace scam_post {

namespace LensFilters {
constexpr const char* KEY = "pref_lens_filter";
constexpr const char* STRENGTH_KEY = "pref_lens_filter_strength";
constexpr const char* TUNE_PREFIX = "pref_lens_filter_tune_";
constexpr const char* NONE = "";
constexpr const char* BLACK_PRO_MIST = "bpm";
constexpr const char* CINEBLOOM = "cinebloom";
constexpr const char* GLIMMERGLASS = "glimmer";
constexpr const char* PEARLESCENT = "pearl";
constexpr const char* FOG = "fog";
constexpr const char* DOUBLE_FOG = "dfog";
/// The filters offered (the reference model's diffusion families, in its order)
inline const std::vector<std::string>& IDS() { static const std::vector<std::string> v = {GLIMMERGLASS, BLACK_PRO_MIST, PEARLESCENT, CINEBLOOM}; return v; }

inline float clamp01(float v) { return std::max(0.f, std::min(1.f, v)); }

inline float defaultParam(const std::string& id) {
    if (id == "gnd") return 0.45f;
    if (id == "streak") return 0.5f;
    if (id == "prism") return 0.4f;
    if (id == "kaleido") return 0.33f;
    if (id == "ir") return 0.5f;
    return 0.f;
}

/// A filter's own settings: its parameter and the fine controls, each 0..1 (0.5 = as designed); stored as "param,knee,radius,haze,warmth" x 100
struct Tune {
    float param = 0, knee = 0.5f, radius = 0.5f, haze = 0.5f, warmth = 0.5f;
    static Tune defaults(const std::string& id) {
        Tune t;
        t.param = defaultParam(id);
        return t;
    }
    /// Tune.parse: anything unreadable keeps that value's default
    static Tune parse(const std::string& id, const std::optional<std::string>& text) {
        Tune t = defaults(id);
        if (!text || text->empty()) return t;
        const std::vector<std::string> parts = jfmt::split(*text, ',');
        float v[5] = {t.param, t.knee, t.radius, t.haze, t.warmth};
        for (size_t i = 0; i < std::min(parts.size(), size_t(5)); i++) {
            float f;
            if (jfmt::parseFloat(jfmt::trim(parts[i]), f)) {
                f = f / 100.f;
                if (!std::isnan(f)) v[i] = clamp01(f);
            }
        }
        t.param = v[0];
        t.knee = v[1];
        t.radius = v[2];
        t.haze = v[3];
        t.warmth = v[4];
        return t;
    }
    std::string serialize() const {
        return std::to_string(FilmSim::round(param * 100.f)) + "," + std::to_string(FilmSim::round(knee * 100.f)) + ","
               + std::to_string(FilmSim::round(radius * 100.f)) + "," + std::to_string(FilmSim::round(haze * 100.f)) + ","
               + std::to_string(FilmSim::round(warmth * 100.f));
    }
};

inline std::string tuneKey(const std::string& id) { return TUNE_PREFIX + id; }

/// The filter's number for the shader (0: none, 1.. in the fixed numbering), -1 for an id that is not offered
inline int index(const std::string& id) { return filmDiffusionIndex(id); }
inline bool known(const std::string& id) { return index(id) >= 0; }

/// The haze control's gain: 0 at 0, 1 at 0.5 (as designed), 4 at 1
inline float hazeGain(float haze) { return haze <= 0.5f ? 2.f * haze : 1.f + 6.f * (haze - 0.5f); }

/// The filters' English names (Pro-Mist stored as "pearl")
inline std::string name(const std::string& id) {
    if (id == BLACK_PRO_MIST) return "Black Pro-Mist";
    if (id == CINEBLOOM) return "CineBloom";
    if (id == GLIMMERGLASS) return "Glimmerglass";
    if (id == PEARLESCENT) return "Pro-Mist";
    return "None";
}

/// LensFilter.Choice: a shot's filter (tune null: none)
struct Choice {
    std::string id;
    float strength = 0;
    std::optional<Tune> tune;
    /// Choice.now: the settings now, as the viewfinder reads them (PreferenceKeys.lensFilter / lensFilterStrength / lensFilterTune)
    static Choice now(const Settings& prefs) {
        std::string id = prefs.getString(KEY, "");
        if (!known(id)) id = "";
        const float s = clamp01(float(readNumber(prefs.get(STRENGTH_KEY), 50.0)) / 100.f);
        if (index(id) <= 0 || s <= 0.f) return Choice();
        const auto stored = prefs.get(tuneKey(id));
        Choice c;
        c.id = id;
        c.strength = s;
        c.tune = Tune::parse(id, stored ? std::optional<std::string>(stored->text()) : std::nullopt);
        return c;
    }
    std::string toString() const { return tune ? id + " " + jfmt::fixed(strength, 2) + " " + tune->serialize() : "none"; }
};

} // namespace LensFilters

namespace DiffusionModel {
constexpr double FRAME_UM = 36000.0;

struct Group { double lambdaUm, spread; int n; double alpha; };
struct Family { Group core, halo, bloom; double wc, wh, wb, warmth, gain; };

inline std::optional<Family> family(const std::string& id) {
    if (id == LensFilters::GLIMMERGLASS) return Family{{10, 1.5, 2, 0}, {50, 2, 3, 0}, {260, 2.5, 4, 3.2}, 0.60, 0.30, 0.10, 0.0, 0.65};
    if (id == LensFilters::BLACK_PRO_MIST) return Family{{16, 1.5, 2, 0}, {95, 2, 3, 0}, {380, 2.5, 4, 3.5}, 0.40, 0.47, 0.13, 0.65, 0.75};
    if (id == LensFilters::PEARLESCENT) return Family{{14, 1.5, 2, 0}, {150, 2, 3, 0}, {650, 2.5, 4, 2.9}, 0.28, 0.42, 0.30, 0.40, 1.05};
    if (id == LensFilters::CINEBLOOM) return Family{{20, 1.5, 2, 0}, {200, 2, 3, 0}, {1000, 2.5, 4, 2.5}, 0.22, 0.30, 0.48, 0.85, 1.00};
    if (id == LensFilters::FOG) return Family{{20, 1.5, 2, 0}, {300, 2, 3, 0}, {1500, 2.5, 4, 2.3}, 0.20, 0.20, 0.60, 0.10, 1.10};
    if (id == LensFilters::DOUBLE_FOG) return Family{{16, 1.5, 2, 0}, {220, 2, 3, 0}, {1200, 2.5, 4, 2.6}, 0.35, 0.30, 0.35, 0.20, 0.95};
    return std::nullopt;
}

inline double grade(float strength) { return strength <= 0.f ? 0.0 : std::pow(2.0, 4.0 * double(std::min(1.f, strength)) - 3.0); }

inline double scatterAtGrade(const std::string& id, double g) {
    static const double SCATTER[5] = {0.10, 0.20, 0.35, 0.55, 0.75};
    const auto f = family(id);
    if (!f || !(g > 0)) return 0.0;
    const double lg = std::log(g) / std::log(2);
    double base;
    if (lg <= -3) base = SCATTER[0] * std::pow(2.0, lg + 3);
    else if (lg >= 1) base = SCATTER[4];
    else {
        const int i = int(std::floor(lg + 3));
        const double t = (lg + 3) - i;
        base = SCATTER[i] + (SCATTER[std::min(i + 1, 4)] - SCATTER[i]) * t;
    }
    return std::max(0.0, std::min(0.99, base * f->gain));
}
inline double scatter(const std::string& id, float strength) { return scatterAtGrade(id, grade(strength)); }

struct Expanded { std::vector<double> l, w; };
inline Expanded expand(const Group& g, bool bloom) {
    const int n = std::max(1, g.n);
    Expanded e{std::vector<double>(size_t(n)), std::vector<double>(size_t(n))};
    double sum = 0;
    for (int k = 0; k < n; k++) {
        e.l[size_t(k)] = n == 1 ? g.lambdaUm
                                : std::exp(std::log(g.lambdaUm / g.spread)
                                           + (std::log(g.lambdaUm * g.spread) - std::log(g.lambdaUm / g.spread)) * k / (n - 1));
        e.w[size_t(k)] = bloom ? std::pow(e.l[size_t(k)], 2.0 - g.alpha) : 1.0;
        sum += e.w[size_t(k)];
    }
    for (int k = 0; k < n; k++) e.w[size_t(k)] /= sum;
    return e;
}

inline std::vector<double> haloWeights(const std::vector<double>& w, double warmth, int c) {
    static const double WARM_AXIS[3] = {1.30, 0.15, -1.45};
    const int n = int(w.size());
    if (n < 2) return w;
    warmth = std::max(-1.5, std::min(1.5, warmth));
    std::vector<double> g(static_cast<size_t>(n));
    double mean = 0, total = 0;
    for (int k = 0; k < n; k++) {
        g[size_t(k)] = -1.0 + 2.0 * k / (n - 1);
        mean += w[size_t(k)] * g[size_t(k)];
        total += w[size_t(k)];
    }
    mean /= total;
    std::vector<double> out(static_cast<size_t>(n));
    double s = 0;
    for (int k = 0; k < n; k++) {
        out[size_t(k)] = std::max(0.0, w[size_t(k)] * (1.0 + warmth * WARM_AXIS[c] * (g[size_t(k)] - mean)));
        s += out[size_t(k)];
    }
    if (s <= 0) return w;
    for (int k = 0; k < n; k++) out[size_t(k)] *= total / s;
    return out;
}

struct Shape { Expanded core, halo, bloom; double wc, wh, wb, warmth; };

/// DiffusionModel.shape on a frame frameUm long that is longPx pixels of the image, with the fine controls
inline Shape shape(const Family& f, const LensFilters::Tune& t, double longPx, double frameUm) {
    const double size = std::pow(2.0, (double(t.radius) - 0.5) * 2.0);
    const double px = longPx / frameUm * size;
    const double hi = std::pow(2.0, (double(t.knee) - 0.5) * 2.0), bi = LensFilters::hazeGain(t.haze);
    const double wc = f.wc, wh = f.wh * hi, wb = f.wb * bi, total = wc + wh + wb;
    Shape s{expand(f.core, false), expand(f.halo, false), expand(f.bloom, true), wc / total, wh / total, wb / total,
            f.warmth + (double(t.warmth) - 0.5) * 1.6};
    for (Expanded* g : {&s.core, &s.halo, &s.bloom}) for (double& l : g->l) l *= px;
    return s;
}
} // namespace DiffusionModel

namespace DiffusionGauss {
constexpr double FIT[3][2] = {{0.1633, 0.5360}, {0.6496, 1.5236}, {0.1870, 2.7684}};
constexpr int MIN_LEVEL = 8;

struct Blur { int level; float sigma; std::array<float, 3> weight; };
struct Plan { double p; std::vector<Blur> blurs; int top; };

inline int levelSize(int size, int level) { return std::max(1, size >> level); }
inline double boxVariance(int level) { return (std::pow(4, level) - 1) / 12.0; }

/// DiffusionGauss.plan of filter id deflecting the share p, its PSF on a frame frameUm long that is longPx pixels of this image
inline std::optional<Plan> plan(const std::string& id, double p, const LensFilters::Tune& tune, int w, int h, double longPx, double frameUm) {
    const auto f = DiffusionModel::family(id);
    if (!f || !(p > 0) || w < 2 || h < 2) return std::nullopt;
    const DiffusionModel::Shape s = DiffusionModel::shape(*f, tune, longPx, frameUm);
    std::vector<std::array<double, 4>> comps;
    for (size_t k = 0; k < s.core.l.size(); k++) comps.push_back({s.core.l[k], s.wc * s.core.w[k], s.wc * s.core.w[k], s.wc * s.core.w[k]});
    std::vector<double> halo[3];
    for (int c = 0; c < 3; c++) halo[c] = DiffusionModel::haloWeights(s.halo.w, s.warmth, c);
    for (size_t k = 0; k < s.halo.l.size(); k++) comps.push_back({s.halo.l[k], s.wh * halo[0][k], s.wh * halo[1][k], s.wh * halo[2][k]});
    for (size_t k = 0; k < s.bloom.l.size(); k++) comps.push_back({s.bloom.l[k], s.wb * s.bloom.w[k], s.wb * s.bloom.w[k], s.wb * s.bloom.w[k]});
    int lmax = 0;
    while (levelSize(w, lmax + 1) >= MIN_LEVEL && levelSize(h, lmax + 1) >= MIN_LEVEL) lmax++;
    Plan out{p, {}, 0};
    for (const auto& c : comps) for (const auto& fit : FIT) {
        const double sigma = std::max(fit[1] * c[0], 1e-3);
        int l = sigma < 6 ? 0 : int(std::floor(std::log(sigma / 3) / std::log(2)));
        l = std::max(0, std::min(l, lmax));
        const double rest = std::sqrt(std::max(sigma * sigma - boxVariance(l), 0.0)) / (1 << l);
        out.blurs.push_back({l, float(rest), {float(fit[0] * c[1]), float(fit[0] * c[2]), float(fit[0] * c[3])}});
        out.top = std::max(out.top, l);
    }
    return out;
}
/// the lens filter's plan: its strength's share on a 36 mm frame longPx long
inline std::optional<Plan> plan(const std::string& id, float strength, const LensFilters::Tune& tune, int w, int h, double longPx) {
    return plan(id, DiffusionModel::scatter(id, strength), tune, w, h, longPx, DiffusionModel::FRAME_UM);
}
} // namespace DiffusionGauss

} // namespace scam_post
