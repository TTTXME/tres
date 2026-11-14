#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// The film simulation's and the lens filters' catalogues for the settings and the editor's «LUT» tab (Engine/post/color: FilmSim,
/// FilmLooks, LensFilters - the Android tables), and the tab's card pictures (FinishedLook.apply on the CPU, EditLookThumbs).
@interface SCAMFilm : NSObject
/// FilmSim.STOCKS in order: @{@"id", @"brand", @"name", @"type" (0 negative, 1 cine, 2 slide, 3 B&W negative, 4 B&W reversal),
/// @"iso", @"reversal", @"measured" (a stock of the reference model)}
+ (NSArray<NSDictionary<NSString *, id> *> *)stocks;
/// FilmSim.PRINTS: @{@"id", @"brand", @"name", @"film" (a print film, not a paper)}
+ (NSArray<NSDictionary<NSString *, id> *> *)prints;
/// FilmSim.FORMATS: @{@"id", @"name", @"longMm"} ("off" first: no format)
+ (NSArray<NSDictionary<NSString *, id> *> *)formats;
/// FilmSim.print(stored, stock).id: the print a stock is printed on when `stored` ("" the stock's own) is chosen
+ (NSString *)printForStored:(NSString *)stored stock:(NSString *)stock NS_SWIFT_NAME(print(stored:stock:));
/// FilmSim.format(stored).id
+ (NSString *)formatForStored:(NSString *)stored NS_SWIFT_NAME(format(stored:));
/// FilmSim.DIFFUSIONS ("" none first)
+ (NSArray<NSString *> *)diffusions;
/// FilmLooks.values: our five looks' stored values ("scam:warm400", ...)
+ (NSArray<NSString *> *)looks;
/// The imported .cube files (the LUT folder), sorted
+ (NSArray<NSString *> *)lutFiles;
/// The folder of the imported .cube files (Android: Download/SCAMERA/LUT; the app: Documents/LUT)
+ (void)setLutFolder:(NSString *)path;
+ (NSString *)lutFolder;
/// The .cube text can be read as a LUT (FilmLut.parse)
+ (BOOL)validCube:(NSData *)data NS_SWIFT_NAME(validCube(_:));
/// LensFilters.IDS: the filters offered ("glimmer", "bpm", "pearl", "cinebloom")
+ (NSArray<NSString *> *)lensFilters;
/// LensFilters.name
+ (NSString *)lensFilterName:(NSString *)identifier NS_SWIFT_NAME(lensFilterName(_:));
/// The stored Tune text of a filter as its five values 0..1 (param, knee, radius, haze, warmth): LensFilters.Tune.parse
+ (NSArray<NSNumber *> *)lensTune:(nullable NSString *)stored filter:(NSString *)identifier NS_SWIFT_NAME(lensTune(_:filter:));
/// Tune.serialize of five values 0..1
+ (NSString *)lensTuneText:(NSArray<NSNumber *> *)values NS_SWIFT_NAME(lensTuneText(_:));
/// EditLookThumbs: RGBA8 pixels through a LUT value (lut, film "") or a film stock's first-phase table at 17 a side (its default
/// print, no grain) at full strength; nil when the table cannot be made
+ (nullable NSData *)lookThumbnail:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height lut:(NSString *)lut film:(NSString *)film
    NS_SWIFT_NAME(lookThumbnail(_:width:height:lut:film:));
@end

/// The lens filter page's preview (LensFilterPreference.render): RGBA8 sRGB pixels as linear light with the highlights' headroom,
/// through the filter's diffusion (DiffusionGauss.apply of its plan, the frame as long as the picture) at `strength` 0..1 with its
/// stored fine controls (`tune`, nil: the filter's defaults), back to 8 bits with a soft shoulder above white
@interface SCAMFilm (LensPreview)
+ (nullable NSData *)lensFilterThumbnail:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height filter:(NSString *)filter
                                strength:(float)strength tune:(nullable NSString *)tune
    NS_SWIFT_NAME(lensFilterThumbnail(_:width:height:filter:strength:tune:));
@end

NS_ASSUME_NONNULL_END
