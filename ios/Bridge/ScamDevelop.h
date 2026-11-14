#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// A developed picture: RGBA8 rows top-down, upright, sRGB.
@interface SCAMDevelopPicture : NSObject
@property (nonatomic, readonly) NSData *rgba;
@property (nonatomic, readonly) NSInteger width;
@property (nonatomic, readonly) NSInteger height;
@end

/// P665 «Проявка» (Android EditDevelop + DevelopRun): a DNG of ours developed by the camera's own post pipeline (Engine/post/
/// DevelopRun.hpp on ScamGL) with the shot's settings (the DNG's develop record) and the ones changed in the editor - the preview
/// (the DNG binned to 2x2 cells of at most 2048 px) and the copy's full-size develop (Malvar-He-Cutler, the last one kept). Each
/// develop waits for a shot being processed (one shot or develop at a time). Not thread safe: one queue per developer.
@interface SCAMDevelop : NSObject
/// The developer of a DNG carrying the camera's develop record (a Bayer DNG); nil for any other file.
- (nullable instancetype)initWithDNG:(NSData *)dng shaders:(NSString *)shaders NS_SWIFT_NAME(init(dng:shaders:));
/// The record in a line for the log (build, headroom, maps).
@property (nonatomic, readonly) NSString *summary;
/// The shot's settings as the develop reads them (DevelopRun.shotValues): key -> NSNumber (a switch: boolean), NSString or an
/// array of strings, as the camera stored them.
@property (nonatomic, readonly) NSDictionary<NSString *, id> *shotValues;
/// The preview develop with changes ({key: true / false / "text" / null} JSON: the camera settings changed over the shot's).
- (nullable SCAMDevelopPicture *)previewWithChanges:(NSString *)changes error:(NSError **)error NS_SWIFT_NAME(preview(changes:));
/// Full-size pixels per preview pixel (after the first preview; 1 before).
@property (nonatomic, readonly) double scale;
/// The whole DNG developed with changes, kept for the next call with the same changes.
- (nullable SCAMDevelopPicture *)fullWithChanges:(NSString *)changes error:(NSError **)error NS_SWIFT_NAME(full(changes:));
/// Drops the kept pictures (the editor is left).
- (void)releaseCaches;
@end

NS_ASSUME_NONNULL_END
