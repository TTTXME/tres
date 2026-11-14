#import "ScamWarmUp.h"

#include "post/DevelopRun.hpp"
#include "post/HybridTuning.hpp"
#include "post/Json.hpp"
#include "ScamMergeCore.hpp"

#include <cstdio>
#include <fstream>
#include <mutex>

extern "C" {
size_t scamgl_program_count(void);
int scamgl_save_programs(const char* path);
int scamgl_warm_programs(const char* path, void (*progress)(int done, int total, void* context), void* context);
}

static void reportProgress(int done, int total, void* context) {
    void (^block)(NSInteger, NSInteger) = (__bridge void (^)(NSInteger, NSInteger))context;
    if (block) block(done, total);
}

@implementation SCAMWarmUp

+ (NSArray<NSString *> *)prewarmMergeWithSettings:(NSString *)settings progress:(void (^)(NSInteger, NSInteger))progress {
    NSMutableArray<NSString *> *lines = [NSMutableArray array];
    // the merge's process-wide hooks and the pipeline's statics: not while a shot (or a gallery develop) runs
    std::lock_guard<std::timed_mutex> guard(scam_post::processLock());
    NSString *job = [NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID UUID].UUIDString];
    [[NSFileManager defaultManager] createDirectoryAtPath:job withIntermediateDirectories:YES attributes:nil error:nil];
    try {
        scam_post::Settings prefs = scam_post::Settings::fromJson(scam_post::Json::parse(settings.UTF8String));
        scam_post::Settings::Scope scope(prefs);
        { std::ofstream(std::string(job.UTF8String) + "/hybrid_tuning.txt") << scam_post::HybridTuning::text(prefs); }
        auto report = [lines, progress](const std::string& line) {
            int done = 0, total = 0;
            if (std::sscanf(line.c_str(), "HYBRID PREWARM PROGRESS %d %d", &done, &total) == 2) {
                if (progress) progress(done, total);
                return;
            }
            NSString *s = [NSString stringWithUTF8String:line.c_str()] ?: @"";
            @synchronized (lines) { [lines addObject:s]; }
        };
        const auto tuning = scam::loadHybridTuning(job.UTF8String, report);
        // the iPhone's bursts are plain Bayer: block 1 only (Android also builds the Quad / Tetra blocks 2 and 4)
        scam::hybridPrewarmGpu(tuning, 1, report);
    } catch (const std::exception &e) {
        [lines addObject:[NSString stringWithFormat:@"prewarm failed: %s", e.what()]];
    }
    [[NSFileManager defaultManager] removeItemAtPath:job error:nil];
    return lines;
}

+ (NSInteger)warmProgramsAtPath:(NSString *)path progress:(void (^)(NSInteger, NSInteger))progress {
    return scamgl_warm_programs(path.fileSystemRepresentation, reportProgress, (__bridge void *)progress);
}

+ (NSInteger)saveProgramsToPath:(NSString *)path {
    return scamgl_save_programs(path.fileSystemRepresentation);
}

+ (NSInteger)programCount {
    return (NSInteger)scamgl_program_count();
}

@end
