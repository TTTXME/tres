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
/// P165: the shot's merged DNG as Android writes it (the merge's mosaic, 16 bit over its headroom, the shot's tags), nil when it
/// was not asked or not made (the camera's DNG of the reference frame stays then)
@property (nonatomic, readonly, nullable) NSData *mergedDng;
/// the DNG's sensor rotation (Parameters.cameraRotation, degrees clockwise) and whether the photo is mirrored (a front camera's:
/// P620 gives its DNG no preview)
@property (nonatomic, readonly) NSInteger dngRotation;
@property (nonatomic, readonly) BOOL mirrored;
@end

/// The Android photo route of an SHDR shot (Engine/post/ShotRun.hpp): the merge and the post pipeline on the GPU (ScamGL).
@interface SCAMShot : NSObject
/// burst: the NCH file of the frames; dng: the first frame's DNG; settings: the app's preferences as the lab json's
/// {"prefs": {key: [type, value]}, "dev": {key: number}}; faces: «Портрет»'s face boxes as Android's Parameters.portraitFaces
/// (left, top, right, bottom, normalised, in the unrotated RAW buffer; four numbers a face, at most five faces), facesDetected:
/// whether a face was found (also with no boxes: its place in the RAW unknown); shaders: the folder of the app's shader assets.
/// dngFacts (P165, nil: no merged DNG - RAW saving off): the facts of the shot's files - make, model, device, buildModel, software,
/// dateTime, subSec, offset (strings), facing, cameraID, filmRecipe (strings), fNumber, focalLength (numbers), focal35, frames,
/// physicalId (integers), lossless (boolean).
+ (nullable SCAMPhoto *)processBurstAtPath:(NSString *)burst dng:(NSData *)dng settings:(NSString *)settings
                                     faces:(NSArray<NSNumber *> *)faces facesDetected:(BOOL)facesDetected front:(BOOL)front
                                  dngFacts:(nullable NSDictionary<NSString *, id> *)dngFacts
                                   shaders:(NSString *)shaders error:(NSError **)error;

/// P620 ScamProcessor.appendDngPreview: the saved photo (RGBA8 rows, upright, sRGB) at most 1024 px, turned back to the sensor's
/// orientation (rotation: the DNG's, degrees clockwise), as a baseline JPEG (quality 90) appended to dng as its preview
/// (DngPreview.append); nil when the DNG is not one it makes sense of.
+ (nullable NSData *)dng:(NSData *)dng withPreview:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height
                rotation:(NSInteger)rotation software:(NSString *)software
    NS_SWIFT_NAME(dng(_:preview:width:height:rotation:software:));
@end

NS_ASSUME_NONNULL_END
