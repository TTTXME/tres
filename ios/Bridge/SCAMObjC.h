#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// AVFoundation reports invalid settings by raising NSException, which Swift cannot catch.
@interface SCAMObjC : NSObject
+ (nullable NSString *)catchException:(NS_NOESCAPE void (^)(void))block;
@end

NS_ASSUME_NONNULL_END
