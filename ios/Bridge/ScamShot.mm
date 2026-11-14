#import "ScamShot.h"
#import "ScamDevelopCodec.h"

#include "post/DevelopRun.hpp"
#include "post/DngPreview.hpp"
#include "post/Json.hpp"
#include "post/ShotRun.hpp"

#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>

#include <EGL/egl.h>
#include <mutex>

@interface SCAMPhoto ()
@property (nonatomic, readwrite) NSData *rgba;
@property (nonatomic, readwrite) NSInteger width;
@property (nonatomic, readwrite) NSInteger height;
@property (nonatomic, readwrite) double mergeMilliseconds;
@property (nonatomic, readwrite) double postMilliseconds;
@property (nonatomic, readwrite) NSArray<NSString *> *log;
@property (nonatomic, readwrite, nullable) NSData *gainMap;
@property (nonatomic, readwrite) NSInteger gainMapWidth;
@property (nonatomic, readwrite) NSInteger gainMapHeight;
@property (nonatomic, readwrite) float gainMapMin;
@property (nonatomic, readwrite) float gainMapMax;
@property (nonatomic, readwrite) float hdrCapacityMax;
@property (nonatomic, readwrite, nullable) NSData *developRecord;
@property (nonatomic, readwrite, nullable) NSData *mergedDng;
@property (nonatomic, readwrite) NSInteger dngRotation;
@property (nonatomic, readwrite) BOOL mirrored;
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

+ (SCAMPhoto *)processBurstAtPath:(NSString *)burst dng:(NSData *)dng settings:(NSString *)settings
                            faces:(NSArray<NSNumber *> *)faces facesDetected:(BOOL)facesDetected front:(BOOL)front
                         dngFacts:(NSDictionary<NSString *, id> *)dngFacts
                          shaders:(NSString *)shaders error:(NSError **)error {
    // the merge's process-wide hooks and the pipeline's statics: one shot (or one gallery develop) at a time
    std::lock_guard<std::timed_mutex> guard(scam_post::processLock());
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
        scam_post::ShotFaces shotFaces;
        shotFaces.detected = facesDetected;
        shotFaces.front = front;
        for (NSNumber *v in faces) shotFaces.boxes.push_back(v.floatValue);
        const long long build = [[NSBundle.mainBundle objectForInfoDictionaryKey:@"CFBundleVersion"] longLongValue];
        std::optional<scam_post::DngRequest> dngRequest;
        if (dngFacts) {
            auto text = [dngFacts](NSString *key) {
                id v = dngFacts[key];
                return std::string([v isKindOfClass:[NSString class]] ? [(NSString *)v UTF8String] ?: "" : "");
            };
            auto number = [dngFacts](NSString *key, double fallback) {
                id v = dngFacts[key];
                return [v isKindOfClass:[NSNumber class]] ? [(NSNumber *)v doubleValue] : fallback;
            };
            scam_post::DngRequest r;
            r.facts.make = text(@"make");
            r.facts.model = text(@"model");
            r.facts.device = text(@"device");
            r.facts.buildModel = text(@"buildModel");
            r.facts.software = text(@"software");
            r.facts.dateTime = text(@"dateTime");
            r.facts.subSec = text(@"subSec");
            r.facts.offset = text(@"offset");
            r.facts.facing = text(@"facing");
            r.facts.fNumber = number(@"fNumber", NAN);
            r.facts.focalLength = number(@"focalLength", NAN);
            r.facts.focal35 = int(number(@"focal35", 0));
            r.facts.lossless = number(@"lossless", 0) != 0;
            r.frames = int(number(@"frames", 0));
            r.cameraID = text(@"cameraID");
            r.filmRecipe = text(@"filmRecipe");
            r.physicalId = int(number(@"physicalId", -1));
            dngRequest = r;
        }
        const scam_post::ShotOutput out = scam_post::runShot(burst.UTF8String, dngBytes, job.UTF8String, shaders.UTF8String, log, makePostContext,
                                                             16384, shotFaces, true, {}, build, dngRequest ? &*dngRequest : nullptr);
        photo = [SCAMPhoto new];
        photo.rgba = [NSData dataWithBytes:out.photo.rgba.data() length:out.photo.rgba.size()];
        photo.width = out.photo.width;
        photo.height = out.photo.height;
        photo.mergeMilliseconds = out.mergeMs;
        photo.postMilliseconds = out.postMs;
        if (out.gainMap) {
            const std::vector<uint8_t> luma = out.gainMap->luma();
            photo.gainMap = [NSData dataWithBytes:luma.data() length:luma.size()];
            photo.gainMapWidth = out.gainMap->width;
            photo.gainMapHeight = out.gainMap->height;
            photo.gainMapMin = out.gainMap->gainMapMin;
            photo.gainMapMax = out.gainMap->gainMapMax;
            photo.hdrCapacityMax = out.gainMap->hdrCapacityMax;
        }
        photo.dngRotation = out.params.cameraRotation;
        photo.mirrored = out.params.mirror;
        if (!out.dng.empty()) photo.mergedDng = [NSData dataWithBytes:out.dng.data() length:out.dng.size()];
        if (out.develop) {
            try {
                const std::vector<uint8_t> tail = out.develop->appendix(scamDevelopCodec());
                photo.developRecord = [NSData dataWithBytes:tail.data() length:tail.size()];
            } catch (const std::exception &e) {
                log(std::string("develop record not written: ") + e.what());
            }
        }
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

+ (NSData *)dng:(NSData *)dng withPreview:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height
         rotation:(NSInteger)rotation software:(NSString *)software {
    if ((NSInteger)rgba.length < width * height * 4 || width <= 0 || height <= 0) return nil;
    int tw = 0, th = 0;
    std::vector<uint8_t> small = scam_post::DngPreview::scaled(static_cast<const uint8_t *>(rgba.bytes), int(width), int(height),
                                                               scam_post::DngPreview::DNG_LONG_SIDE, tw, th);
    small = scam_post::DngPreview::dngSource(small, tw, th, int(rotation));
    // PhotoThumbnail.jpeg: a baseline JPEG at quality 90 (ImageIO's encoder in place of Bitmap.compress)
    NSMutableData *jpeg = [NSMutableData data];
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CFDataRef pixels = CFDataCreate(nullptr, small.data(), CFIndex(small.size()));
    CGDataProviderRef provider = CGDataProviderCreateWithCFData(pixels);
    CGImageRef image = CGImageCreate(size_t(tw), size_t(th), 8, 32, size_t(tw) * 4, space,
                                     (CGBitmapInfo)((uint32_t)kCGBitmapByteOrderDefault | (uint32_t)kCGImageAlphaNoneSkipLast), provider,
                                     nullptr, false, kCGRenderingIntentDefault);
    CGImageDestinationRef dst = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)jpeg, CFSTR("public.jpeg"), 1, nullptr);
    bool encoded = false;
    if (dst && image) {
        NSDictionary *props = @{(__bridge NSString *)kCGImageDestinationLossyCompressionQuality: @0.9};
        CGImageDestinationAddImage(dst, image, (__bridge CFDictionaryRef)props);
        encoded = CGImageDestinationFinalize(dst);
    }
    if (dst) CFRelease(dst);
    if (image) CGImageRelease(image);
    CGDataProviderRelease(provider);
    CFRelease(pixels);
    CGColorSpaceRelease(space);
    if (!encoded || jpeg.length == 0) return nil;
    const uint8_t *d = static_cast<const uint8_t *>(dng.bytes);
    std::vector<uint8_t> file(d, d + dng.length);
    const uint8_t *j = static_cast<const uint8_t *>(jpeg.bytes);
    const std::vector<uint8_t> jpegBytes(j, j + jpeg.length);
    if (!scam_post::DngPreview::append(file, jpegBytes, tw, th, scam_post::DngPreview::COLOUR_SRGB, software.UTF8String ?: "")) return nil;
    return [NSData dataWithBytes:file.data() length:file.size()];
}

@end
