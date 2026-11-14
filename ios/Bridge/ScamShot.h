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
/// Ultra HDR (pref_ultrahdr_key): the photo's gain map, one byte a pixel (rows top-down, pixel-aligned with rgba; the codes linear
/// in log2 gain between gainMapMin and gainMapMax, gamma 1), nil when Ultra HDR is off or its pass failed
@property (nonatomic, readonly, nullable) NSData *gainMap;
@property (nonatomic, readonly) NSInteger gainMapWidth;
@property (nonatomic, readonly) NSInteger gainMapHeight;
/// GainMapComputer.Result: GainMapMin / GainMapMax (log2) and HDRCapacityMax (log2, the alternate rendition's headroom)
@property (nonatomic, readonly) float gainMapMin;
@property (nonatomic, readonly) float gainMapMax;
@property (nonatomic, readonly) float hdrCapacityMax;
/// P665: the gallery developer's record of the shot (DevelopRecord.append's bytes: the record, its length and «SCDVEND1»), to be
/// appended to the shot's DNG file as is; nil when it could not be made
@property (nonatomic, readonly, nullable) NSData *developRecord;
@end

/// The Android photo route of an SHDR shot (Engine/post/ShotRun.hpp): the merge and the post pipeline on the GPU (ScamGL).
@interface SCAMShot : NSObject
/// burst: the NCH file of the frames; dng: the first frame's DNG; settings: the app's preferences as the lab json's
/// {"prefs": {key: [type, value]}, "dev": {key: number}}; faces: «Портрет»'s face boxes as Android's Parameters.portraitFaces
/// (left, top, right, bottom, normalised, in the unrotated RAW buffer; four numbers a face, at most five faces), facesDetected:
/// whether a face was found (also with no boxes: its place in the RAW unknown); shaders: the folder of the app's shader assets.
+ (nullable SCAMPhoto *)processBurstAtPath:(NSString *)burst dng:(NSData *)dng settings:(NSString *)settings
                                     faces:(NSArray<NSNumber *> *)faces facesDetected:(BOOL)facesDetected front:(BOOL)front
                                   shaders:(NSString *)shaders error:(NSError **)error;
@end

NS_ASSUME_NONNULL_END
