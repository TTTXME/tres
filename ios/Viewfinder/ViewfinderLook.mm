// The live viewfinder's look from the settings (Android LiveLensFilter.read / readLut, FilmChain.current / loadLut, the diffusion
// weights of DiffusionModel.levelWeights / combine / mipSigmas) and the film's EXIF recipe (FilmSim.Settings.recipe), on the C++ port
// of Engine/post/color (read only). Swift reaches this class by name (ViewfinderNative.swift: NSClassFromString + perform), so the
// bridging header stays as it is.

#import <Foundation/Foundation.h>

#include "../Engine/post/color/FilmLut.hpp"
#include "../Engine/post/color/LensFilters.hpp"

#include <array>
#include <cmath>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

using namespace scam_post;

@interface SCAMViewfinderLook : NSObject
/// request: @{@"settings": PostSettings.json(), @"long": the viewport's long side in pixels (0: no weights), @"levels": the mip levels of
/// the half-size pyramid}. Returns @{@"key", @"strength", @"size", @"lut" (NSData float RGB, r fastest), @"min", @"max",
/// @"filter" (0 none, 1..6 a diffusion), @"filterStrength", @"weights" (levels + 1 RGB triples: the frame's, then mip level 0..),
/// @"recipe" ("" without a film)}; with @"recipeOnly": @1 only @"recipe". Nil when the settings cannot be read.
- (nullable NSDictionary *)look:(NSDictionary *)request;
@end

namespace {

NSString *ns(const std::string &s) { return [NSString stringWithUTF8String:s.c_str()] ?: @""; }

/// the bundle's assets/film (FilmSpectral's arrays) when nothing set the root yet (as Bridge/ScamFilm.mm)
void filmAssets() {
    static std::once_flag once;
    std::call_once(once, [] {
        if (!filmAssetsRoot().empty()) return;
        NSString *path = [[NSBundle mainBundle] pathForResource:@"film" ofType:nil];
        if (path) filmAssetsRoot() = path.UTF8String;
    });
}

// ───── DiffusionModel's level weights (Java DiffusionModel: eeExp, eeGauss, ee, mipSigmas, levelWeights, radii, combine, combinedEe,
// nnls), on the C++ port's family / shape / haloWeights

using Weights = std::vector<std::array<float, 3>>;

double eeExp(double r, double lambda) {
    const double x = r / std::max(lambda, 1e-9);
    return 1.0 - (1.0 + x) * std::exp(-x);
}

double eeGauss(double r, double sigma) {
    if (sigma <= 0) return r > 0 ? 1.0 : 0.0;
    return 1.0 - std::exp(-r * r / (2 * sigma * sigma));
}

double ee(const DiffusionModel::Shape &s, int c, double r) {
    double e = 0;
    for (size_t k = 0; k < s.core.l.size(); k++) e += s.wc * s.core.w[k] * eeExp(r, s.core.l[k]);
    const std::vector<double> hw = DiffusionModel::haloWeights(s.halo.w, s.warmth, c);
    for (size_t k = 0; k < s.halo.l.size(); k++) e += s.wh * hw[k] * eeExp(r, s.halo.l[k]);
    for (size_t k = 0; k < s.bloom.l.size(); k++) e += s.wb * s.bloom.w[k] * eeExp(r, s.bloom.l[k]);
    return e;
}

/// DiffusionModel.mipSigmas: level k is the mip level k - 1 of the half-size frame sampled with a B-spline; level 0 the frame
std::vector<double> mipSigmas(int levels) {
    std::vector<double> s(size_t(levels) + 1, 0.0);
    for (int k = 1; k <= levels; k++) s[size_t(k)] = std::sqrt((std::pow(4.0, k) - 1) / 9.0 + std::pow(4.0, k) / 3.0);
    return s;
}

std::vector<double> radii(double longPx, int m) {
    std::vector<double> r(static_cast<size_t>(m));
    for (int i = 0; i < m; i++) r[size_t(i)] = 0.5 * std::pow(longPx / 0.5, i / double(m - 1));
    return r;
}

std::vector<double> nnls(const std::vector<std::vector<double>> &b, const std::vector<double> &t) {
    const size_t n = b.size(), m = t.size();
    std::vector<std::vector<double>> h(n, std::vector<double>(n, 0.0));
    std::vector<double> g(n, 0.0);
    for (size_t j = 0; j < n; j++) {
        for (size_t i = 0; i < m; i++) g[j] += b[j][i] * t[i];
        for (size_t k = 0; k < n; k++) {
            double v = 0;
            for (size_t i = 0; i < m; i++) v += b[j][i] * b[k][i];
            h[j][k] = v;
        }
    }
    std::vector<double> a(n, 0.0);
    for (int it = 0; it < 4000; it++) {
        double change = 0;
        for (size_t j = 0; j < n; j++) {
            if (h[j][j] <= 1e-12) continue;
            double r = g[j];
            for (size_t k = 0; k < n; k++) r -= h[j][k] * a[k];
            const double next = std::max(0.0, a[j] + r / h[j][j]);
            change = std::max(change, std::abs(next - a[j]));
            a[j] = next;
        }
        if (change < 1e-10) break;
    }
    return a;
}

std::vector<std::vector<double>> basisOf(const std::vector<double> &sigmas, const std::vector<double> &rad) {
    std::vector<std::vector<double>> basis(sigmas.size(), std::vector<double>(rad.size(), 0.0));
    for (size_t k = 0; k < sigmas.size(); k++)
        for (size_t i = 0; i < rad.size(); i++) basis[k][i] = eeGauss(rad[i], sigmas[k]);
    return basis;
}

/// DiffusionModel.levelWeights(id, p, tune, longPx, frameUm, sigmas)
std::optional<Weights> levelWeights(const std::string &id, double p, const LensFilters::Tune &tune, double longPx, double frameUm,
                                    const std::vector<double> &sigmas) {
    const auto f = DiffusionModel::family(id);
    if (!f || !(p > 0) || longPx <= 0) return std::nullopt;
    const DiffusionModel::Shape s = DiffusionModel::shape(*f, tune, longPx, frameUm);
    const size_t n = sigmas.size();
    const int m = 48;
    const std::vector<double> rad = radii(longPx, m);
    const auto basis = basisOf(sigmas, rad);
    Weights out(n, {0.f, 0.f, 0.f});
    for (int c = 0; c < 3; c++) {
        std::vector<double> target(static_cast<size_t>(m));
        for (int i = 0; i < m; i++) target[size_t(i)] = ee(s, c, rad[size_t(i)]);
        std::vector<double> a = nnls(basis, target);
        double sum = 0;
        for (double v : a) sum += v;
        if (sum <= 0) { a.assign(n, 0.0); a[0] = 1; sum = 1; }
        for (size_t k = 0; k < n; k++) out[k][size_t(c)] = float(p * a[k] / sum + (k == 0 ? 1.0 - p : 0.0));
    }
    return out;
}

/// DiffusionModel.combine: two diffusions in a row, fitted again to the same levels
std::optional<Weights> combine(const std::optional<Weights> &a, const std::optional<Weights> &b, const std::vector<double> &sigmas,
                               double longPx) {
    if (!a) return b;
    if (!b) return a;
    const size_t n = sigmas.size();
    const int m = 48;
    const std::vector<double> rad = radii(longPx, m);
    const auto basis = basisOf(sigmas, rad);
    Weights out(n, {0.f, 0.f, 0.f});
    for (int c = 0; c < 3; c++) {
        std::vector<double> target(static_cast<size_t>(m), 0.0);
        for (size_t j = 0; j < a->size() && j < n; j++) for (size_t k = 0; k < b->size() && k < n; k++) {
            const double w = double((*a)[j][size_t(c)]) * double((*b)[k][size_t(c)]);
            if (w == 0) continue;
            const double sg = std::sqrt(sigmas[j] * sigmas[j] + sigmas[k] * sigmas[k]);
            for (int i = 0; i < m; i++) target[size_t(i)] += w * eeGauss(rad[size_t(i)], sg);
        }
        std::vector<double> w = nnls(basis, target);
        double sum = 0;
        for (double v : w) sum += v;
        if (sum <= 0) { w.assign(n, 0.0); w[0] = 1; sum = 1; }
        for (size_t k = 0; k < n; k++) out[k][size_t(c)] = float(w[k] / sum);
    }
    return out;
}

/// FilmSim.Settings.diffusionP: the film's diffusion share (0 without one)
double diffusionP(const FilmSettings &film) {
    if (film.diffusion.empty()) return 0.0;
    return DiffusionModel::scatterAtGrade(film.diffusion, film.diffusionGrade) * film.strength;
}

/// LiveLensFilter.weights(id, s, tune, film, longPx, mipLevels)
std::optional<Weights> liveWeights(const std::string *id, float s, const LensFilters::Tune &tune, const FilmSettings *film, int longPx,
                                   int mipLevels) {
    const int n = std::min(12, mipLevels);
    const std::vector<double> sigmas = mipSigmas(n);
    std::optional<Weights> lens;
    if (id) lens = levelWeights(*id, DiffusionModel::scatter(*id, s), tune, longPx, DiffusionModel::FRAME_UM, sigmas);
    if (!film) return lens;
    const std::optional<Weights> own = levelWeights(film->diffusion, diffusionP(*film), LensFilters::Tune::defaults(film->diffusion),
                                                    longPx, film->diffusionFrameUm(), sigmas);
    return combine(lens, own, sigmas, longPx);
}

} // namespace

@implementation SCAMViewfinderLook

- (nullable NSDictionary *)look:(NSDictionary *)request {
    NSString *json = [request[@"settings"] isKindOfClass:[NSString class]] ? request[@"settings"] : @"{\"prefs\":{},\"dev\":{}}";
    const int longPx = [request[@"long"] intValue];
    const int levels = [request[@"levels"] intValue];
    try {
        filmAssets();
        Settings prefs = Settings::fromJson(Json::parse(json.UTF8String ?: "{}"));
        Settings::Scope scope(prefs);
        NSMutableDictionary *out = [NSMutableDictionary dictionary];
        const std::shared_ptr<const FilmSettings> sim = FilmSim::current(prefs);
        out[@"recipe"] = sim ? ns(sim->recipe()) : @"";
        if ([request[@"recipeOnly"] intValue] != 0) return out;

        // LiveLensFilter.readLut: the film and the chosen LUT as one table (FilmChain.current), its strength
        const float userStrength = std::max(0.f, std::min(1.f, float(readNumber(prefs.get(FilmLut::STRENGTH_KEY), 100.0)) / 100.f));
        const std::string user = userStrength > 0.f ? prefs.getString(FilmLut::KEY, "") : "";
        const FilmChain::State chain = FilmChain::current(sim.get(), user, userStrength);
        const float strength = chain.strength;
        const std::string key = strength > 0.f ? chain.key : "";
        out[@"key"] = ns(key);
        out[@"strength"] = @(strength);
        if (!key.empty()) {
            // FilmChain.loadLut without FilmPreviewLut (the reference model's display table needs the engine's preview renderer, not
            // ported): the first-phase table, as Android shows until (or without) that table
            const FilmLutP lut = FilmChain::lut(key);
            if (lut && lut->size >= 2 && lut->rgb.size() >= size_t(lut->size) * lut->size * lut->size * 3) {
                out[@"size"] = @(lut->size);
                out[@"lut"] = [NSData dataWithBytes:lut->rgb.data() length:lut->rgb.size() * sizeof(float)];
                out[@"min"] = @[@(lut->domainMin[0]), @(lut->domainMin[1]), @(lut->domainMin[2])];
                out[@"max"] = @[@(lut->domainMax[0]), @(lut->domainMax[1]), @(lut->domainMax[2])];
            }
        }

        // LiveLensFilter.read: the lens filter, else the film's own diffusion alone
        std::string id = prefs.getString(LensFilters::KEY, "");
        float s = LensFilters::clamp01(float(readNumber(prefs.get(LensFilters::STRENGTH_KEY), 50.0)) / 100.f);
        int index = LensFilters::index(id);
        const FilmSettings *filmDiffusion = sim && diffusionP(*sim) > 0 ? sim.get() : nullptr;
        const bool lensOn = index > 0 && s > 0.f;
        int filter = 0;
        LensFilters::Tune tune;
        if (lensOn || filmDiffusion) {
            if (!lensOn) {
                id = filmDiffusion->diffusion;
                s = 1.f;
                index = LensFilters::index(id);
            }
            if (lensOn) {
                const auto stored = prefs.get(LensFilters::tuneKey(id));
                tune = LensFilters::Tune::parse(id, stored ? std::optional<std::string>(stored->text()) : std::nullopt);
            } else {
                tune = LensFilters::Tune::defaults(id);
            }
            filter = index;
        }
        out[@"filter"] = @(filter);
        out[@"filterStrength"] = @(s);
        // the diffusion filters (1..6) draw from the pyramid with these weights; the others are not offered
        if (filter >= 1 && filter <= 6 && longPx > 0 && levels > 0) {
            const std::optional<Weights> w = liveWeights(lensOn ? &id : nullptr, s, tune, filmDiffusion, longPx, levels);
            if (w) {
                NSMutableArray *flat = [NSMutableArray arrayWithCapacity:w->size() * 3];
                for (const auto &rgb : *w) for (float v : rgb) [flat addObject:@(v)];
                out[@"weights"] = flat;
            }
        }
        return out;
    } catch (const std::exception &e) {
        NSLog(@"SCAMViewfinderLook: %s", e.what());
        return nil;
    }
}

@end
