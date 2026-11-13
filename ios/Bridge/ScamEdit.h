#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// A picture of the editor: RGBA8 rows top-down, sRGB.
@interface SCAMEditImage : NSObject
@property (nonatomic, readonly) NSData *rgba;
@property (nonatomic, readonly) NSInteger width;
@property (nonatomic, readonly) NSInteger height;
/// the copy only: saved smaller than the crop (the output pixel limit)
@property (nonatomic, readonly) BOOL shrunk;
@end

/// P633 / P642: a photo's watermark record (Android WatermarkPatch / WatermarkStore; Editor/WatermarkPatch.hpp): the pixels the
/// camera had under its watermark and the watermark's settings (WatermarkSpec JSON), kept at the very end of the photo's file in
/// Android's format, so the editor can take the watermark off exactly («Убрать») or draw another one («Изменить»).
@interface SCAMWatermarkRecord : NSObject
/// the photo's stored size
@property (nonatomic, readonly) NSInteger width;
@property (nonatomic, readonly) NSInteger height;
/// RGBA_1010102 pixels (an Android 10-bit HEIC's), else 8 bits a channel
@property (nonatomic, readonly) BOOL tenBit;
@property (nonatomic, readonly) BOOL displayP3;
/// WatermarkSpec.toJson of the watermark drawn
@property (nonatomic, readonly) NSString *spec;
@property (nonatomic, readonly) NSInteger pieceCount;
@property (nonatomic, readonly) long long pixels;
@property (nonatomic, readonly) long long encodedBytes;
/// WatermarkPatch.toString (logs)
@property (nonatomic, readonly) NSString *summary;
/// the record as stored (WatermarkPatch.toBytes)
@property (nonatomic, readonly) NSData *bytes;

/// WatermarkStore.read + WatermarkPatch.fromBytes: the record at the end of a photo's file; nil when there is none or the bytes are
/// not one.
- (nullable instancetype)initWithFile:(NSData *)file NS_SWIFT_NAME(init(file:));
/// WatermarkPatch.capture: the pixels of rects (stored px, l, t, r, b after each other) of RGBA8 rows top-down (sRGB, or Display P3
/// when displayP3; the fourth byte read as opaque) before the watermark is drawn, coded with the predictive code; spec: the
/// watermark's WatermarkSpec JSON.
- (nullable instancetype)initWithRows:(const uint8_t *)rows width:(NSInteger)width height:(NSInteger)height bytesPerRow:(NSInteger)bytesPerRow
                                rects:(NSArray<NSNumber *> *)rects spec:(NSString *)spec displayP3:(BOOL)displayP3 error:(NSError **)error
    NS_SWIFT_NAME(init(rows:width:height:bytesPerRow:rects:spec:displayP3:));
/// whether every piece decodes and matches its CRC
- (BOOL)verify;
/// WatermarkStore.append: the photo's file with the record at its end in its container's form (a JPEG after its last EOI, a WebP's
/// «SCWM» chunk, a HEIC / AVIF top-level «uuid» box); nil for a container it does not know.
+ (nullable NSData *)file:(NSData *)file appendingRecord:(NSData *)record NS_SWIFT_NAME(file(_:appending:));
@end

/// The Android photo editor (gallery/editor: EditRenderer, EditMath, CropMath, DngRaw) on the GPU (Editor/*.hpp on ScamGL): one
/// photo's preview source and its own GL context. The state is EditState's JSON. Not thread safe: one queue per session.
@interface SCAMEditSession : NSObject
/// A decoded photo (EditSource's JPEG / decoder route): the preview picture (RGBA8 rows top-down, sRGB, or Display P3 when
/// displayP3), its EXIF orientation still to apply (1..8), the original's stored size; shaders: the app's shader folder.
- (nullable instancetype)initWithPreview:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height displayP3:(BOOL)displayP3
                             orientation:(NSInteger)orientation fullWidth:(NSInteger)fullWidth fullHeight:(NSInteger)fullHeight
                                 shaders:(NSString *)shaders error:(NSError **)error
    NS_SWIFT_NAME(init(preview:width:height:displayP3:orientation:fullWidth:fullHeight:shaders:));
/// A DNG (EditSource's DNG route): its raw data binned to the preview, the DNG colour model.
- (nullable instancetype)initWithDNG:(NSData *)dng shaders:(NSString *)shaders error:(NSError **)error NS_SWIFT_NAME(init(dng:shaders:));

@property (nonatomic, readonly) BOOL isRaw;
/// the preview texture (as stored) and the original (as the route stores it)
@property (nonatomic, readonly) NSInteger previewWidth;
@property (nonatomic, readonly) NSInteger previewHeight;
@property (nonatomic, readonly) NSInteger fullWidth;
@property (nonatomic, readonly) NSInteger fullHeight;
@property (nonatomic, readonly) NSInteger orientation;
/// a Quad RAW: edited at half resolution (2x2 cells averaged)
@property (nonatomic, readonly) BOOL quadHalf;

/// EditorView's frame: the state in a view of width x height px with the margin (px) around the picture, the background
/// (sRGB 0..1); cropMode: the crop tab's frame (the whole turned photo, outside the crop dimmed); before: the photo's own tone.
- (nullable SCAMEditImage *)renderState:(NSString *)json width:(NSInteger)width height:(NSInteger)height margin:(double)margin
                               cropMode:(BOOL)cropMode before:(BOOL)before background:(NSArray<NSNumber *> *)background
    NS_SWIFT_NAME(renderState(_:width:height:margin:cropMode:before:background:));
/// EditorView.histogram: R, G, B counts (3 x 256) of a 160 px render of the crop.
- (nullable NSArray<NSArray<NSNumber *> *> *)histogramForState:(NSString *)json before:(BOOL)before NS_SWIFT_NAME(histogram(forState:before:));
/// The crop of the state at about side px on its long side (a card, the watermark's ink).
- (nullable SCAMEditImage *)thumbnailForState:(NSString *)json side:(NSInteger)side NS_SWIFT_NAME(thumbnail(forState:side:));
/// A RAW's white balance the sliders ask for: @[CCT K, Duv], nil for a photo or without DNG matrices.
- (nullable NSArray<NSNumber *> *)whiteForState:(NSString *)json NS_SWIFT_NAME(white(forState:));

/// EditExporter: the copy at full resolution (tiles) from the original's picture (RGBA8, the preview's colour space) - at most
/// outMax pixels; progress 0..1 on the calling thread.
- (nullable SCAMEditImage *)exportState:(NSString *)json picture:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height
                                 outMax:(long long)outMax progress:(nullable void (^)(float))progress error:(NSError **)error
    NS_SWIFT_NAME(exportState(_:picture:width:height:outMax:progress:));
/// The same from the DNG decoded again (2x2 binned over the RAW limit or budget / 6 bytes of free memory).
- (nullable SCAMEditImage *)exportState:(NSString *)json dng:(NSData *)dng budget:(long long)budget outMax:(long long)outMax
                               progress:(nullable void (^)(float))progress error:(NSError **)error
    NS_SWIFT_NAME(exportState(_:dng:budget:outMax:progress:));

/// P633: the photo's own watermark record (EditSource.readWatermark): kept when it is this picture's (its stored size, upright) and
/// every piece reads back - then the preview with the pixels back is made, shown and exported whenever the state takes the
/// watermark off (EditState.wmRemove), and the export's picture gets the pixels back too. NO (no record kept) for a RAW, nil or a
/// record of another photo.
- (BOOL)setWatermarkRecord:(nullable SCAMWatermarkRecord *)record NS_SWIFT_NAME(setWatermarkRecord(_:));
@property (nonatomic, readonly) BOOL hasWatermarkRecord;

/// EditExporter.outMax: the copy's pixel limit for budget bytes of free memory.
+ (long long)outMaxForBudget:(long long)budget NS_SWIFT_NAME(outMax(forBudget:));
/// EditSource.PREVIEW_SIDE
+ (NSInteger)previewSide NS_SWIFT_NAME(previewSide());
@end

NS_ASSUME_NONNULL_END
