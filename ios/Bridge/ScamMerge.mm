#import "ScamMerge.h"

#include "ScamMergeCore.hpp"

#include <fstream>
#include <mutex>

@interface SCAMMergeResult ()
@property (nonatomic, readwrite) NSData *rgb;
@property (nonatomic, readwrite) NSInteger width;
@property (nonatomic, readwrite) NSInteger height;
@property (nonatomic, readwrite) double milliseconds;
@property (nonatomic, readwrite) NSArray<NSString *> *log;
@end

@implementation SCAMMergeResult
@end

@implementation SCAMMerge

+ (SCAMMergeResult *)mergeBurstAtPath:(NSString *)path tuning:(NSString *)tuning error:(NSError **)error {
    // The merge keeps process-wide hooks (scam::hybrid*Hook): one merge at a time.
    static std::mutex lock;
    std::lock_guard<std::mutex> guard(lock);
    NSMutableArray<NSString *> *lines = [NSMutableArray array];
    NSString *job = [NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID UUID].UUIDString];
    [[NSFileManager defaultManager] createDirectoryAtPath:job withIntermediateDirectories:YES attributes:nil error:nil];
    try {
        {
            std::ofstream t(std::string(job.UTF8String) + "/hybrid_tuning.txt");
            t << tuning.UTF8String;
        }
        auto report = [lines](const std::string& line) {
            NSString *s = [NSString stringWithUTF8String:line.c_str()] ?: @"";
            @synchronized (lines) { [lines addObject:s]; }
        };
        scam_ios::MergeResult r = scam_ios::mergeBurst(job.UTF8String, path.UTF8String, report);
        SCAMMergeResult *out = [SCAMMergeResult new];
        out.rgb = [NSData dataWithBytes:r.rgb.data() length:r.rgb.size() * sizeof(float)];
        out.width = r.width;
        out.height = r.height;
        out.milliseconds = r.ms;
        out.log = lines;
        [[NSFileManager defaultManager] removeItemAtPath:job error:nil];
        return out;
    } catch (const std::exception& e) {
        if (error) *error = [NSError errorWithDomain:@"SCAMMerge" code:2
                                            userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithUTF8String:e.what()] ?: @"merge failed"}];
    }
    [[NSFileManager defaultManager] removeItemAtPath:job error:nil];
    return nil;
}

@end
