#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// The result of the SHDR merge: float32 RGB rows top-down (camera colours, base-frame units, highlights above 1).
@interface SCAMMergeResult : NSObject
@property (nonatomic, readonly) NSData *rgb;
@property (nonatomic, readonly) NSInteger width;
@property (nonatomic, readonly) NSInteger height;
@property (nonatomic, readonly) double milliseconds;
@property (nonatomic, readonly) NSArray<NSString *> *log;
@end

/// The Android merge (app/src/main/cpp, scam::hybridReconstruct) on the GPU through ScamGL.
@interface SCAMMerge : NSObject
/// Merges an NCH v10–v12 burst file with the tuning lines ("key value" per line) as the worker's offline replay.
+ (nullable SCAMMergeResult *)mergeBurstAtPath:(NSString *)path tuning:(NSString *)tuning error:(NSError **)error;
@end

NS_ASSUME_NONNULL_END
