#import "ScamDevelop.h"
#import "ScamDevelopCodec.h"

#include "../Editor/EditDevelop.hpp"
#include "post/DevelopRun.hpp"

#include <EGL/egl.h>

#include <chrono>
#include <mutex>
#include <optional>
#include <string>

@interface SCAMDevelopPicture ()
@property (nonatomic, readwrite) NSData *rgba;
@property (nonatomic, readwrite) NSInteger width;
@property (nonatomic, readwrite) NSInteger height;
@end

@implementation SCAMDevelopPicture
@end

static NSError *developError(NSString *what) {
    return [NSError errorWithDomain:@"SCAMDevelop" code:1 userInfo:@{NSLocalizedDescriptionKey: what ?: @"develop failed"}];
}

static NSError *developError(const std::string &what) {
    return developError([NSString stringWithUTF8String:what.c_str()]);
}

@implementation SCAMDevelop {
    NSData *_dng;
    std::string _shaders;
    scam_post::DevelopRecord _record;
    bool _gainMaps;
    std::vector<float> _previewRgb;
    int _previewW, _previewH, _dngW, _dngH;
    SCAMDevelopPicture *_full;
    std::string _fullKey;
}

- (nullable instancetype)initWithDNG:(NSData *)dng shaders:(NSString *)shaders {
    if (!(self = [super init])) return nil;
    const uint8_t *p = static_cast<const uint8_t *>(dng.bytes);
    std::optional<scam_post::DevelopRecord> r = scam_post::DevelopRecord::read(p, dng.length, scamDevelopCodec());
    if (!r) return nil;
    try {
        const scam_edit::DngRaw::Info info = scam_edit::DngRaw::probe(p, dng.length);
        if (info.kind != scam_edit::DngRaw::KIND_BAYER || info.cell != 1) return nil;
        const auto size = info.size(1);
        _dngW = size[0];
        _dngH = size[1];
        // the DNG's OpcodeList2 lens shading stays out when the shot's Parameters carry the map (the pipeline applies it, ScamRgb)
        const scam_post::Parameters shot = scam_post::Parameters::fromJson(r->meta["params"]);
        _gainMaps = !(shot.hasGainMap && shot.gainMap);
    } catch (const std::exception &) {
        return nil;
    }
    _record = std::move(*r);
    _dng = dng;
    _shaders = shaders.UTF8String;
    return self;
}

- (NSString *)summary {
    return [NSString stringWithFormat:@"develop record: build %lld, headroom %.3g, maps: clip %d, frames %d, highlight mask %d, DNG %dx%d",
                                      (long long)scam_post::DevelopRecord::numberOf(_record.meta["build"], 0.f), _record.headroom(), !_record.clipFlags.empty(),
                                      !_record.frames.empty(), !_record.hlMask.empty(), _dngW, _dngH];
}

- (NSDictionary<NSString *, id> *)shotValues {
    NSMutableDictionary<NSString *, id> *out = [NSMutableDictionary dictionary];
    const scam_post::Settings s = scam_post::Develop::settings(_record, {});
    for (const auto &[key, v] : s.prefs) {
        NSString *k = [NSString stringWithUTF8String:key.c_str()];
        if (!k) continue;
        switch (v.type) {
            case scam_post::Pref::Type::Bool: out[k] = @(v.b); break;
            case scam_post::Pref::Type::Int:
            case scam_post::Pref::Type::Long: out[k] = @((long long)v.n); break;
            case scam_post::Pref::Type::Float: out[k] = @(float(v.n)); break;
            case scam_post::Pref::Type::String: out[k] = [NSString stringWithUTF8String:v.s.c_str()] ?: @""; break;
            case scam_post::Pref::Type::Set: {
                NSMutableArray<NSString *> *a = [NSMutableArray array];
                for (const std::string &x : v.set) [a addObject:[NSString stringWithUTF8String:x.c_str()] ?: @""];
                out[k] = a;
                break;
            }
        }
    }
    return out;
}

- (double)scale {
    return _previewW > 0 && _dngW > 0 ? double(_dngW) / _previewW : 1.0;
}

- (void)releaseCaches {
    _full = nil;
    _fullKey.clear();
    std::vector<float>().swap(_previewRgb);
}

/// The pipeline's run on rgb under the process lock, in a GL context of its own on this thread.
- (nullable SCAMDevelopPicture *)run:(std::vector<uint8_t> &&)rgb width:(int)w height:(int)h half:(bool)half
                             changes:(NSString *)changes error:(NSError **)error {
    std::unique_lock<std::timed_mutex> lock(scam_post::processLock(), std::defer_lock);
    if (!lock.try_lock_for(std::chrono::seconds(scam_post::Develop::LOCK_WAIT_S))) {
        if (error) *error = developError(NSLocalizedString(@"editor.develop.camera_busy", nil));
        return nil;
    }
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLContext context = EGL_NO_CONTEXT;
    SCAMDevelopPicture *picture = nil;
    const auto previousLog = scam_post::postLog();
    scam_post::postLog() = [](const std::string &line) { NSLog(@"SCAMDevelop %s", line.c_str()); };
    try {
        const scam_post::Develop::Changes c = scam_post::Develop::changesFromJson(changes.UTF8String ?: "");
        auto makeContext = [&] {
            eglInitialize(display, nullptr, nullptr);
            eglBindAPI(EGL_OPENGL_ES_API);
            const EGLint configAttrs[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
            EGLConfig config;
            EGLint n = 0;
            eglChooseConfig(display, configAttrs, &config, 1, &n);
            const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
            context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
            const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            EGLSurface surface = eglCreatePbufferSurface(display, config, size);
            if (context == EGL_NO_CONTEXT || !eglMakeCurrent(display, surface, surface, context))
                throw std::runtime_error("no GL context for the develop");
        };
        scam_post::PostOutput out = scam_post::Develop::develop(_record, std::move(rgb), w, h, half, c, _shaders, makeContext,
                                                                [](const std::string &line) { NSLog(@"SCAMDevelop %s", line.c_str()); });
        picture = [SCAMDevelopPicture new];
        picture.rgba = [NSData dataWithBytes:out.rgba.data() length:out.rgba.size()];
        picture.width = out.width;
        picture.height = out.height;
    } catch (const std::exception &e) {
        if (error) *error = developError(e.what());
    }
    if (context != EGL_NO_CONTEXT) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(display, context);
    }
    scam_post::postLog() = previousLog;
    return picture;
}

- (nullable SCAMDevelopPicture *)previewWithChanges:(NSString *)changes error:(NSError **)error {
    try {
        if (_previewRgb.empty()) {
            const uint8_t *p = static_cast<const uint8_t *>(_dng.bytes);
            const int bin = scam_edit::develop::binFor(_dngW, _dngH);
            const scam_edit::DngRaw::Image img = scam_edit::DngRaw::decodeLinear(p, _dng.length, bin, _gainMaps);
            if (img.info.kind != scam_edit::DngRaw::KIND_BAYER || img.info.cell != 1 || img.linear.empty())
                throw std::runtime_error("develop: not a Bayer DNG");
            _previewW = img.width / 2;
            _previewH = img.height / 2;
            _previewRgb = scam_edit::develop::superpixel(img.linear, img.width, img.height, img.phase, _record.headroom());
        }
        std::vector<uint8_t> rgb(_previewRgb.size() * sizeof(float));
        std::memcpy(rgb.data(), _previewRgb.data(), rgb.size());
        return [self run:std::move(rgb) width:_previewW height:_previewH half:false changes:changes error:error];
    } catch (const std::exception &e) {
        if (error) *error = developError(e.what());
        return nil;
    }
}

- (nullable SCAMDevelopPicture *)fullWithChanges:(NSString *)changes error:(NSError **)error {
    const std::string key = changes.UTF8String ?: "";
    if (_full && key == _fullKey) return _full;
    _full = nil;
    _fullKey.clear();
    try {
        std::vector<uint8_t> rgb;
        int w = 0, h = 0;
        bool half = false;
        {
            const uint8_t *p = static_cast<const uint8_t *>(_dng.bytes);
            const scam_edit::DngRaw::Image img = scam_edit::DngRaw::decodeLinear(p, _dng.length, 1, _gainMaps);
            if (img.info.kind != scam_edit::DngRaw::KIND_BAYER || img.info.cell != 1 || img.linear.empty())
                throw std::runtime_error("develop: not a Bayer DNG");
            w = img.width;
            h = img.height;
            half = (long long)w * h > scam_edit::develop::HALF_ABOVE;
            const auto t0 = std::chrono::steady_clock::now();
            rgb = scam_edit::develop::mhc(img.linear, w, h, img.phase, _record.headroom(), half);
            NSLog(@"SCAMDevelop demosaiced %dx%d in %d ms", w, h,
                  int(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count()));
        }
        SCAMDevelopPicture *picture = [self run:std::move(rgb) width:w height:h half:half changes:changes error:error];
        if (picture) {
            _full = picture;
            _fullKey = key;
        }
        return picture;
    } catch (const std::exception &e) {
        if (error) *error = developError(e.what());
        return nil;
    }
}

@end
