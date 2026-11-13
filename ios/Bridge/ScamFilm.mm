#import "ScamFilm.h"

#include "../Engine/post/color/FilmLut.hpp"
#include "../Engine/post/color/LensFilters.hpp"

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
