#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// The photo of a burst: RGBA8 rows top-down, upright (the pipeline's rotation applied), sRGB.
@interface SCAMPhoto : NSObject
@property (nonatomic, readonly) NSData *rgba;
@property (nonatomic, readonly) NSInteger width;
@property (nonatomic, readonly) NSInteger height;
@property (nonatomic, readonly) double mergeMilliseconds;
@property (nonatomic, readonly) double postMilliseconds;
@property (nonatomic, readonly) NSArray<NSString *> *log;
@end

/// The Android photo route of an SHDR shot (Engine/post/ShotRun.hpp): the merge and the post pipeline on the GPU (ScamGL).
@interface SCAMShot : NSObject
/// burst: the NCH file of the frames; dng: the first frame's DNG; settings: the app's preferences as the lab json's
/// {"prefs": {key: [type, value]}, "dev": {key: number}}; shaders: the folder of the app's shader assets.
+ (nullable SCAMPhoto *)processBurstAtPath:(NSString *)burst dng:(NSData *)dng settings:(NSString *)settings
                                    shaders:(NSString *)shaders error:(NSError **)error;
@end

NS_ASSUME_NONNULL_END
