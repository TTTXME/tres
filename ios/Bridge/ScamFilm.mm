#import "ScamFilm.h"

#include "../Engine/post/color/FilmLut.hpp"
#include "../Engine/post/color/LensFilters.hpp"

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

using namespace scam_post;

static NSString *ns(const std::string &s) { return [NSString stringWithUTF8String:s.c_str()] ?: @""; }
static std::string cpp(NSString *s) { return s.UTF8String ?: ""; }

/// the bundle's assets/film (FilmSpectral's arrays) when nothing set the root yet
static void filmAssets() {
    if (!filmAssetsRoot().empty()) return;
    NSString *path = [[NSBundle mainBundle] pathForResource:@"film" ofType:nil];
    if (path) filmAssetsRoot() = path.UTF8String;
}

@implementation SCAMFilm

/// The LUT folder is Documents/LUT (the Files app shows it: the .cube files are copied there), set before any shot or edit reads it
+ (void)load {
    @autoreleasepool {
        NSURL *docs = [[NSFileManager defaultManager] URLsForDirectory:NSDocumentDirectory inDomains:NSUserDomainMask].firstObject;
        if (!docs) return;
        NSURL *folder = [docs URLByAppendingPathComponent:@"LUT" isDirectory:YES];
        [[NSFileManager defaultManager] createDirectoryAtURL:folder withIntermediateDirectories:YES attributes:nil error:nil];
        if (folder.path) filmLutFolder() = folder.path.UTF8String;
    }
}

+ (NSArray<NSDictionary<NSString *, id> *> *)stocks {
    NSMutableArray *out = [NSMutableArray array];
    for (const FilmStock &s : FilmSim::STOCKS())
        [out addObject:@{@"id": ns(s.id), @"brand": ns(s.brand), @"name": ns(s.name), @"type": @(s.type), @"iso": @(s.iso),
                         @"reversal": @(s.reversal()), @"measured": @(!s.spectral.empty())}];
    return out;
}

+ (NSArray<NSDictionary<NSString *, id> *> *)prints {
    NSMutableArray *out = [NSMutableArray array];
    for (const FilmPrint &p : FilmSim::PRINTS()) [out addObject:@{@"id": ns(p.id), @"brand": ns(p.brand), @"name": ns(p.name), @"film": @(p.film)}];
    return out;
}

+ (NSArray<NSDictionary<NSString *, id> *> *)formats {
    NSMutableArray *out = [NSMutableArray array];
    for (const FilmFormat &f : FilmSim::FORMATS()) [out addObject:@{@"id": ns(f.id), @"name": ns(f.name), @"longMm": @(f.longMm)}];
    return out;
}

+ (NSString *)printForStored:(NSString *)stored stock:(NSString *)stock {
    return ns(FilmSim::print(cpp(stored), FilmSim::stock(cpp(stock))).id);
}

+ (NSString *)formatForStored:(NSString *)stored { return ns(FilmSim::format(cpp(stored)).id); }

+ (NSArray<NSString *> *)diffusions {
    NSMutableArray *out = [NSMutableArray array];
    for (const std::string &d : FilmSim::DIFFUSIONS()) [out addObject:ns(d)];
    return out;
}

+ (NSArray<NSString *> *)looks {
    NSMutableArray *out = [NSMutableArray array];
    for (const std::string &v : FilmLooks::values()) [out addObject:ns(v)];
    return out;
}

+ (NSArray<NSString *> *)lutFiles {
    NSMutableArray *out = [NSMutableArray array];
    for (const std::string &f : FilmLutFiles::list()) [out addObject:ns(f)];
    return out;
}

+ (void)setLutFolder:(NSString *)path {
    filmLutFolder() = cpp(path);
    FilmLutFiles::forget();
}

+ (NSString *)lutFolder { return ns(filmLutFolder()); }

+ (BOOL)validCube:(NSData *)data {
    const std::string text(static_cast<const char *>(data.bytes), data.length);
    return FilmLut::parse(text) != nullptr;
}

+ (NSArray<NSString *> *)lensFilters {
    NSMutableArray *out = [NSMutableArray array];
    for (const std::string &id : LensFilters::IDS()) [out addObject:ns(id)];
    return out;
}

+ (NSString *)lensFilterName:(NSString *)identifier { return ns(LensFilters::name(cpp(identifier))); }

+ (NSArray<NSNumber *> *)lensTune:(nullable NSString *)stored filter:(NSString *)identifier {
    const LensFilters::Tune t = LensFilters::Tune::parse(cpp(identifier), stored ? std::optional<std::string>(cpp(stored)) : std::nullopt);
    return @[@(t.param), @(t.knee), @(t.radius), @(t.haze), @(t.warmth)];
}

+ (NSString *)lensTuneText:(NSArray<NSNumber *> *)values {
    LensFilters::Tune t;
    float *f[5] = {&t.param, &t.knee, &t.radius, &t.haze, &t.warmth};
    for (NSUInteger i = 0; i < 5 && i < values.count; i++) *f[i] = LensFilters::clamp01(values[i].floatValue);
    return ns(t.serialize());
}

+ (nullable NSData *)lookThumbnail:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height lut:(NSString *)lut film:(NSString *)film {
    if ((NSInteger)rgba.length < width * height * 4 || width <= 0 || height <= 0) return nil;
    filmAssets();
    try {
        FilmLutP table;
        const std::string filmId = cpp(film), lutName = cpp(lut);
        if (!filmId.empty()) {
            const FilmStock *st = FilmSim::stock(filmId);
            if (!st) return nil;
            // EditLookThumbs.thumbSettings: its default print and format, no grain, the film alone
            const FilmSettings s(*st, FilmSim::print(std::string(), st), FilmSim::format(std::string(FilmSim::DEFAULT_FORMAT)), 0, 0, 0, 0, 0, 0, 0,
                                 0, 1, "", FilmSim::DIFFUSION_DEFAULT, FilmSim::LEGACY_DIR_DEFAULT);
            table = FilmSim::lut(s, 17);
        } else if (!lutName.empty()) {
            table = FinishedLook::user(lutName);
        }
        if (!table) return nil;
        NSMutableData *out = [NSMutableData dataWithLength:rgba.length];
        const uint8_t *in = static_cast<const uint8_t *>(rgba.bytes);
        uint8_t *o = static_cast<uint8_t *>(out.mutableBytes);
        float tmp[3];
        auto code = [](float v) { return uint8_t(Settings::javaRound(std::max(0.f, std::min(1.f, v)) * 255.f)); };
        for (NSInteger i = 0; i < width * height; i++) {
            const float r = in[i * 4] / 255.f, g = in[i * 4 + 1] / 255.f, b = in[i * 4 + 2] / 255.f;
            table->sampleInto(r, g, b, tmp);
            o[i * 4] = code(r + (tmp[0] - r));
            o[i * 4 + 1] = code(g + (tmp[1] - g));
            o[i * 4 + 2] = code(b + (tmp[2] - b));
            o[i * 4 + 3] = in[i * 4 + 3];
        }
        return out;
    } catch (const std::exception &e) {
        NSLog(@"SCAMFilm thumbnail: %s", e.what());
        return nil;
    }
}

@end

// ───── the lens filter's CPU preview (LensFilterPreference.render: linear, LensFilters.apply -> DiffusionGauss.apply, pixels) ─────

namespace {
using Img = std::vector<float>;

Img gaussDown(const Img &src, int w, int h, int nw, int nh) {
    Img out(size_t(nw) * nh * 3);
    for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) for (int c = 0; c < 3; c++) {
        float s = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++)
            s += src[(size_t(std::min(h - 1, 2 * y + j)) * w + std::min(w - 1, 2 * x + i)) * 3 + c];
        out[(size_t(y) * nw + x) * 3 + c] = s * .25f;
    }
    return out;
}

int gaussMirror(int i, int n) {
    if (n == 1) return 0;
    const int period = 2 * (n - 1);
    i = ((i % period) + period) % period;
    return i < n ? i : period - i;
}

Img gaussBlur(const Img &src, int w, int h, float sigma) {
    const int r = std::min(int(std::ceil(3 * sigma)), 24);
    std::vector<float> t(size_t(2 * r + 1));
    float sum = 0;
    for (int i = -r; i <= r; i++) sum += t[size_t(i + r)] = sigma < 1e-3f ? (i == 0 ? 1.f : 0.f) : float(std::exp(-0.5 * i * i / (sigma * sigma)));
    for (float &v : t) v /= sum;
    Img a(src.size()), b(src.size());
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
        float s = 0;
        for (int i = -r; i <= r; i++) s += t[size_t(i + r)] * src[(size_t(y) * w + gaussMirror(x + i, w)) * 3 + c];
        a[(size_t(y) * w + x) * 3 + c] = s;
    }
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
        float s = 0;
        for (int i = -r; i <= r; i++) s += t[size_t(i + r)] * a[(size_t(gaussMirror(y + i, h)) * w + x) * 3 + c];
        b[(size_t(y) * w + x) * 3 + c] = s;
    }
    return b;
}

void gaussBilinear(const Img &img, int w, int h, int fw, int fh, int x, int y, float out[3]) {
    const double u = (x + .5) * w / fw - .5, v = (y + .5) * h / fh - .5;
    const int x0 = int(std::floor(u)), y0 = int(std::floor(v));
    const double ax = u - x0, ay = v - y0;
    const int xa = std::max(0, std::min(w - 1, x0)), xb = std::max(0, std::min(w - 1, x0 + 1));
    const int ya = std::max(0, std::min(h - 1, y0)), yb = std::max(0, std::min(h - 1, y0 + 1));
    for (int c = 0; c < 3; c++) {
        const double a = img[(size_t(ya) * w + xa) * 3 + c], b = img[(size_t(ya) * w + xb) * 3 + c];
        const double d = img[(size_t(yb) * w + xa) * 3 + c], e = img[(size_t(yb) * w + xb) * 3 + c];
        out[c] = float((a + (b - a) * ax) * (1 - ay) + (d + (e - d) * ax) * ay);
    }
}

/// DiffusionGauss.apply: the plan on an RGB float image, in place
void gaussApply(Img &rgb, int w, int h, const DiffusionGauss::Plan &plan) {
    using DiffusionGauss::levelSize;
    std::vector<Img> lv(size_t(plan.top + 1));
    lv[0] = rgb;
    for (int l = 1; l <= plan.top; l++) lv[size_t(l)] = gaussDown(lv[size_t(l - 1)], levelSize(w, l - 1), levelSize(h, l - 1), levelSize(w, l), levelSize(h, l));
    Img acc;
    for (int l = plan.top; l >= 0; l--) {
        const int lw = levelSize(w, l), lh = levelSize(h, l);
        Img sum(size_t(lw) * lh * 3, 0.f);
        for (const auto &b : plan.blurs) {
            if (b.level != l) continue;
            const Img g = gaussBlur(lv[size_t(l)], lw, lh, b.sigma);
            for (size_t i = 0; i < sum.size(); i++) sum[i] += b.weight[i % 3] * g[i];
        }
        if (!acc.empty()) {
            const int cw = levelSize(w, l + 1), ch = levelSize(h, l + 1);
            float s[3];
            for (int y = 0; y < lh; y++) for (int x = 0; x < lw; x++) {
                gaussBilinear(acc, cw, ch, lw, lh, x, y, s);
                for (int c = 0; c < 3; c++) sum[(size_t(y) * lw + x) * 3 + c] += s[c];
            }
        }
        acc = std::move(sum);
    }
    const float p = float(plan.p);
    for (size_t i = 0; i < rgb.size(); i++) rgb[i] = (1 - p) * rgb[i] + p * acc[i];
}

float lensToLinear(float v) { return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f); }

/// LensFilterPreference.toByte: a soft shoulder above white, then the sRGB curve
uint8_t lensToByte(float v) {
    v = std::max(0.f, v);
    const float t = v <= 0.8f ? v : 0.8f + 0.2f * float(1.0 - std::exp(-(v - 0.8f) / 0.2f));
    const float e = t <= 0.0031308f ? 12.92f * t : 1.055f * std::pow(t, 1 / 2.4f) - 0.055f;
    return uint8_t(std::lround(std::max(0.f, std::min(1.f, e)) * 255.f));
}
} // namespace

@implementation SCAMFilm (LensPreview)

+ (nullable NSData *)lensFilterThumbnail:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height filter:(NSString *)filter
                                strength:(float)strength tune:(nullable NSString *)tune {
    if ((NSInteger)rgba.length < width * height * 4 || width < 2 || height < 2) return nil;
    const int w = int(width), h = int(height);
    const uint8_t *in = static_cast<const uint8_t *>(rgba.bytes);
    // LensFilterPreference.linear: linear RGB with the highlights' headroom (a source brighter than white by its sRGB luma, up to 4x)
    Img rgb(size_t(w) * h * 3);
    for (size_t i = 0; i < size_t(w) * h; i++) {
        const float r = in[i * 4], g = in[i * 4 + 1], b = in[i * 4 + 2];
        const float y = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255.f;
        const float t = std::max(0.f, std::min(1.f, (y - 0.82f) / 0.1f));
        const float boost = 1.f + 3.f * t * t * (3.f - 2.f * t);
        rgb[i * 3] = lensToLinear(r / 255.f) * boost;
        rgb[i * 3 + 1] = lensToLinear(g / 255.f) * boost;
        rgb[i * 3 + 2] = lensToLinear(b / 255.f) * boost;
    }
    const std::string id = cpp(filter);
    if (LensFilters::index(id) > 0 && strength > 0.f) {
        const LensFilters::Tune t = LensFilters::Tune::parse(id, tune ? std::optional<std::string>(cpp(tune)) : std::nullopt);
        if (const auto plan = DiffusionGauss::plan(id, std::min(1.f, strength), t, w, h, double(std::max(w, h)))) gaussApply(rgb, w, h, *plan);
    }
    NSMutableData *out = [NSMutableData dataWithLength:size_t(w) * h * 4];
    uint8_t *o = static_cast<uint8_t *>(out.mutableBytes);
    for (size_t i = 0; i < size_t(w) * h; i++) {
        o[i * 4] = lensToByte(rgb[i * 3]);
        o[i * 4 + 1] = lensToByte(rgb[i * 3 + 1]);
        o[i * 4 + 2] = lensToByte(rgb[i * 3 + 2]);
        o[i * 4 + 3] = 255;
    }
    return out;
}

@end
