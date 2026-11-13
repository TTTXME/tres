// The spectral film engine of the reference film route (Engine/post/nodes/SpektraFilmEngine.hpp: spektrafilm's Vulkan renderer,
// the Android libscameraFilm's code, through MoltenVK) set as FilmReferenceEngine's renderer at launch. It initialises itself the
// first time a shot asks for a measured film (ReferenceFilm.supported); when Vulkan cannot start the route stays off and the film
// takes the old route, as on Android without the library. Its resources are the bundle's "spektrafilm" folder (project.yml: the
// shaders compiled to SPIR-V at build time, the float tables of assets/film-reference).
#import <Foundation/Foundation.h>

#include "post/nodes/SpektraFilmEngine.hpp"

@interface SCAMFilmEngine : NSObject
@end

@implementation SCAMFilmEngine

+ (void)load {
    @autoreleasepool {
        NSString *path = [[NSBundle mainBundle] pathForResource:@"spektrafilm" ofType:nil];
        if (path && [[NSFileManager defaultManager] fileExistsAtPath:[path stringByAppendingPathComponent:@"shaders"]])
            scam_post::SpektraFilmEngine::install(path.UTF8String);
    }
}

@end
