#import "ScamShot.h"

#include "post/Json.hpp"
#include "post/ShotRun.hpp"

#include <EGL/egl.h>
#include <mutex>

@interface SCAMPhoto ()
@property (nonatomic, readwrite) NSData *rgba;
@property (nonatomic, readwrite) NSInteger width;
@property (nonatomic, readwrite) NSInteger height;
@property (nonatomic, readwrite) double mergeMilliseconds;
@property (nonatomic, readwrite) double postMilliseconds;
@property (nonatomic, readwrite) NSArray<NSString *> *log;
@end

@implementation SCAMPhoto
@end

// the post pipeline's GL context on this thread (the merge makes and drops its own)
static void makePostContext() {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint configAttrs[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig config;
    EGLint n = 0;
    eglChooseConfig(display, configAttrs, &config, 1, &n);
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
    const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, size);
    if (!eglMakeCurrent(display, surface, surface, context)) throw std::runtime_error("no GL context for the post pipeline");
}

@implementation SCAMShot

+ (SCAMPhoto *)processBurstAtPath:(NSString *)burst dng:(NSData *)dng settings:(NSString *)settings shaders:(NSString *)shaders
                            error:(NSError **)error {
    static std::mutex lock; // the merge's process-wide hooks and the pipeline's statics: one shot at a time
    std::lock_guard<std::mutex> guard(lock);
    NSMutableArray<NSString *> *lines = [NSMutableArray array];
    NSString *job = [NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID UUID].UUIDString];
    [[NSFileManager defaultManager] createDirectoryAtPath:job withIntermediateDirectories:YES attributes:nil error:nil];
    auto log = [lines](const std::string& line) {
        NSString *s = [NSString stringWithUTF8String:line.c_str()] ?: @"";
        @synchronized (lines) { [lines addObject:s]; }
    };
    SCAMPhoto *photo = nil;
    try {
        scam_post::Settings prefs = scam_post::Settings::fromJson(scam_post::Json::parse(settings.UTF8String));
        scam_post::Settings::Scope scope(prefs);
        scam_post::postLog() = log;
        const uint8_t *bytes = static_cast<const uint8_t *>(dng.bytes);
        const std::vector<uint8_t> dngBytes(bytes, bytes + dng.length);
        makePostContext();
        const scam_post::ShotOutput out = scam_post::runShot(burst.UTF8String, dngBytes, job.UTF8String, shaders.UTF8String, log);
        photo = [SCAMPhoto new];
        photo.rgba = [NSData dataWithBytes:out.photo.rgba.data() length:out.photo.rgba.size()];
        photo.width = out.photo.width;
        photo.height = out.photo.height;
        photo.mergeMilliseconds = out.mergeMs;
        photo.postMilliseconds = out.postMs;
        photo.log = lines;
    } catch (const std::exception &e) {
        if (error) *error = [NSError errorWithDomain:@"SCAMShot" code:2
                                            userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithUTF8String:e.what()] ?: @"processing failed",
                                                       @"log": [lines copy]}];
    }
    scam_post::postLog() = [](const std::string& s) { std::fprintf(stderr, "%s\n", s.c_str()); };
    [[NSFileManager defaultManager] removeItemAtPath:job error:nil];
    return photo;
}

@end
