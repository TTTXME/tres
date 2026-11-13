#import "ScamEdit.h"

#include "../Editor/EditExport.hpp"
#include "../Editor/WatermarkPatch.hpp"

#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>

#include <EGL/egl.h>

#include <memory>
#include <string>

@interface SCAMEditImage ()
@property (nonatomic, readwrite) NSData *rgba;
@property (nonatomic, readwrite) NSInteger width;
@property (nonatomic, readwrite) NSInteger height;
@property (nonatomic, readwrite) BOOL shrunk;
@end

@implementation SCAMEditImage
@end

static NSError *editError(const std::string &what) {
    return [NSError errorWithDomain:@"SCAMEdit" code:1
                           userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithUTF8String:what.c_str()] ?: @"editor failed"}];
}

static SCAMEditImage *imageOf(std::vector<uint8_t> &&rgba, int w, int h, bool shrunk) {
    SCAMEditImage *i = [SCAMEditImage new];
    i.rgba = [NSData dataWithBytes:rgba.data() length:rgba.size()];
    i.width = w;
    i.height = h;
    i.shrunk = shrunk;
    return i;
}


// ───── P633: the watermark's record ─────

/// The record's raw DEFLATE (Foundation's zlib: RFC 1951 data without header or checksum, WatermarkPatch.hpp wraps it) and
/// Android's lossless WebP pieces through ImageIO (8 bits, sRGB, as BitmapFactory decodes them; the pieces' CRC checks the words).
static const scam_edit::wm::Codec &appleCodec() {
    static const scam_edit::wm::Codec codec = [] {
        scam_edit::wm::Codec c;
        c.deflateRaw = [](const uint8_t *p, size_t n) {
            @autoreleasepool {
                NSData *in = [NSData dataWithBytesNoCopy:const_cast<uint8_t *>(p) length:n freeWhenDone:NO];
                NSError *error = nil;
                NSData *out = [in compressedDataUsingAlgorithm:NSDataCompressionAlgorithmZlib error:&error];
                if (!out) throw std::runtime_error("the watermark record's deflate failed");
                const uint8_t *b = static_cast<const uint8_t *>(out.bytes);
                return std::vector<uint8_t>(b, b + out.length);
            }
        };
        c.inflateRaw = [](const uint8_t *p, size_t n, uint8_t *out, size_t outLen) {
            @autoreleasepool {
                NSData *in = [NSData dataWithBytesNoCopy:const_cast<uint8_t *>(p) length:n freeWhenDone:NO];
                NSError *error = nil;
                NSData *plain = [in decompressedDataUsingAlgorithm:NSDataCompressionAlgorithmZlib error:&error];
                if (!plain || plain.length < outLen) return false;
                std::memcpy(out, plain.bytes, outLen);
                return true;
            }
        };
        c.decodeWebp = [](const uint8_t *p, size_t n, int w, int h, std::vector<uint32_t> &words) {
            @autoreleasepool {
                NSData *data = [NSData dataWithBytesNoCopy:const_cast<uint8_t *>(p) length:n freeWhenDone:NO];
                CGImageSourceRef src = CGImageSourceCreateWithData((__bridge CFDataRef)data, nullptr);
                if (!src) return false;
                CGImageRef image = CGImageSourceCreateImageAtIndex(src, 0, nullptr);
                CFRelease(src);
                if (!image) return false;
                bool ok = int(CGImageGetWidth(image)) == w && int(CGImageGetHeight(image)) == h;
                std::vector<uint8_t> px(size_t(w) * size_t(h) * 4);
                CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
                CGContextRef ctx = ok ? CGBitmapContextCreate(px.data(), size_t(w), size_t(h), 8, size_t(w) * 4, space,
                                                              kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big) : nullptr;
                if (ctx) {
                    CGContextSetBlendMode(ctx, kCGBlendModeCopy);
                    CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), image);
                    CGContextRelease(ctx);
                } else {
                    ok = false;
                }
                CGColorSpaceRelease(space);
                CGImageRelease(image);
                if (!ok) return false;
                words.resize(size_t(w) * size_t(h));
                for (size_t i = 0; i < words.size(); i++)
                    words[i] = uint32_t(px[4 * i]) | uint32_t(px[4 * i + 1]) << 8 | uint32_t(px[4 * i + 2]) << 16 | uint32_t(px[4 * i + 3]) << 24;
                return true;
            }
        };
        return c;
    }();
    return codec;
}

@interface SCAMWatermarkRecord ()
- (const scam_edit::wm::Patch &)patch;
@end

@implementation SCAMWatermarkRecord {
    std::shared_ptr<scam_edit::wm::Patch> _patch;
}

- (const scam_edit::wm::Patch &)patch { return *_patch; }

- (nullable instancetype)initWithFile:(NSData *)file {
    if (!(self = [super init])) return nil;
    const auto bytes = scam_edit::wm::store::read(static_cast<const uint8_t *>(file.bytes), file.length);
    if (!bytes) return nil;
    try {
        _patch = std::make_shared<scam_edit::wm::Patch>(scam_edit::wm::Patch::fromBytes(bytes->data(), bytes->size()));
    } catch (const std::exception &e) {
        NSLog(@"SCAMEdit watermark record not read: %s", e.what());
        return nil;
    }
    return self;
}

- (nullable instancetype)initWithRows:(const uint8_t *)rows width:(NSInteger)width height:(NSInteger)height bytesPerRow:(NSInteger)bytesPerRow
                                rects:(NSArray<NSNumber *> *)rects spec:(NSString *)spec displayP3:(BOOL)displayP3 error:(NSError **)error {
    if (!(self = [super init])) return nil;
    if (width <= 0 || height <= 0 || bytesPerRow < width * 4 || rects.count % 4 != 0) {
        if (error) *error = editError("no watermark record of these rows");
        return nil;
    }
    std::vector<scam_edit::wm::Rect> list;
    for (NSUInteger i = 0; i + 3 < rects.count; i += 4)
        list.push_back(scam_edit::wm::Rect{rects[i].intValue, rects[i + 1].intValue, rects[i + 2].intValue, rects[i + 3].intValue});
    try {
        _patch = std::make_shared<scam_edit::wm::Patch>(scam_edit::wm::capture(rows, int(width), int(height), size_t(bytesPerRow), list,
                                                                                spec.UTF8String ?: "",
                                                                                displayP3 ? scam_edit::wm::SPACE_P3 : scam_edit::wm::SPACE_SRGB,
                                                                                true, appleCodec()));
    } catch (const std::exception &e) {
        if (error) *error = editError(e.what());
        return nil;
    }
    return self;
}

- (NSInteger)width { return _patch->width; }
- (NSInteger)height { return _patch->height; }
- (BOOL)tenBit { return _patch->format == scam_edit::wm::FORMAT_1010102; }
- (BOOL)displayP3 { return _patch->colourSpace == scam_edit::wm::SPACE_P3; }
- (NSString *)spec { return [[NSString alloc] initWithBytes:_patch->spec.data() length:_patch->spec.size() encoding:NSUTF8StringEncoding] ?: @""; }
- (NSInteger)pieceCount { return NSInteger(_patch->pieces.size()); }
- (long long)pixels { return _patch->pixels(); }
- (long long)encodedBytes { return _patch->encodedBytes(); }
- (NSString *)summary { return [NSString stringWithUTF8String:_patch->describe().c_str()] ?: @""; }

- (NSData *)bytes {
    const std::vector<uint8_t> b = _patch->toBytes();
    return [NSData dataWithBytes:b.data() length:b.size()];
}

- (BOOL)verify { return _patch->verify(appleCodec()); }

+ (nullable NSData *)file:(NSData *)file appendingRecord:(NSData *)record {
    const uint8_t *f = static_cast<const uint8_t *>(file.bytes), *r = static_cast<const uint8_t *>(record.bytes);
    std::vector<uint8_t> out(f, f + file.length);
    if (!scam_edit::wm::store::append(out, std::vector<uint8_t>(r, r + record.length))) return nil;
    return [NSData dataWithBytes:out.data() length:out.size()];
}

@end

@implementation SCAMEditSession {
    EGLDisplay _display;
    EGLSurface _surface;
    EGLContext _context;
    std::unique_ptr<scam_edit::EditRenderer> _renderer;
    scam_edit::PreviewSource _source;
    GLuint _sourceTex;
    BOOL _ownTex;
    std::string _shaders;
    scam_edit::EditRenderer::Target _view;
    // P633: the photo's own watermark record, the preview as decoded and with the record's pixels back, which one is uploaded
    std::shared_ptr<scam_edit::wm::Patch> _record;
    std::vector<uint8_t> _dirtyPreview, _cleanPreview;
    BOOL _shownClean;
}

- (BOOL)makeContext:(NSString *)shaders error:(NSError **)error {
    _display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(_display, nullptr, nullptr);
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint configAttrs[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig config;
    EGLint n = 0;
    eglChooseConfig(_display, configAttrs, &config, 1, &n);
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    _context = eglCreateContext(_display, config, EGL_NO_CONTEXT, ctxAttrs);
    const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    _surface = eglCreatePbufferSurface(_display, config, size);
    if (![self current]) {
        if (error) *error = editError("no GL context for the editor");
        return NO;
    }
    _shaders = shaders.UTF8String;
    try {
        _renderer.reset(new scam_edit::EditRenderer());
        _renderer->init(_shaders);
    } catch (const std::exception &e) {
        if (error) *error = editError(e.what());
        return NO;
    }
    return YES;
}

- (BOOL)current {
    return eglMakeCurrent(_display, _surface, _surface, _context) == EGL_TRUE;
}

/// EditorView: the preview's texture and the proxy (once a source)
- (void)uploadSource {
    if (_ownTex && _sourceTex) scam_edit::EditGl::deleteTexture(_sourceTex);
    _sourceTex = 0;
    _ownTex = NO;
    bool owned = false;
    _sourceTex = _source.upload(*_renderer, owned);
    _ownTex = owned;
    _renderer->invalidate();
    _renderer->setProxySource(_sourceTex, _source.width, _source.height);
}

- (nullable instancetype)initWithPreview:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height displayP3:(BOOL)displayP3
                             orientation:(NSInteger)orientation fullWidth:(NSInteger)fullWidth fullHeight:(NSInteger)fullHeight
                                 shaders:(NSString *)shaders error:(NSError **)error {
    if (!(self = [super init])) return nil;
    if ((NSInteger)rgba.length < width * height * 4 || width <= 0 || height <= 0) {
        if (error) *error = editError("the preview picture is incomplete");
        return nil;
    }
    if (![self makeContext:shaders error:error]) return nil;
    const uint8_t *p = static_cast<const uint8_t *>(rgba.bytes);
    _source.rgba.assign(p, p + size_t(width) * size_t(height) * 4);
    _source.width = int(width);
    _source.height = int(height);
    _source.p3 = displayP3;
    _source.orientation = orientation >= 1 && orientation <= 8 ? int(orientation) : 1;
    _source.fullW = int(fullWidth);
    _source.fullH = int(fullHeight);
    _source.colour = std::make_shared<scam_edit::EditMath::DisplayColour>(displayP3, false);
    try {
        [self uploadSource];
    } catch (const std::exception &e) {
        if (error) *error = editError(e.what());
        return nil;
    }
    return self;
}

- (nullable instancetype)initWithDNG:(NSData *)dng shaders:(NSString *)shaders error:(NSError **)error {
    if (!(self = [super init])) return nil;
    try {
        _source = scam_edit::openDngPreview(static_cast<const uint8_t *>(dng.bytes), dng.length);
    } catch (const std::exception &e) {
        if (error) *error = editError(e.what());
        return nil;
    }
    if (![self makeContext:shaders error:error]) return nil;
    try {
        [self uploadSource];
    } catch (const std::exception &e) {
        if (error) *error = editError(e.what());
        return nil;
    }
    return self;
}

- (void)dealloc {
    if (_renderer && [self current]) {
        if (_ownTex) scam_edit::EditGl::deleteTexture(_sourceTex);
        _view.release();
        _renderer->release();
        glFinish();
    }
    _renderer.reset();
    eglMakeCurrent(_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (_surface) eglDestroySurface(_display, _surface);
    if (_context) eglDestroyContext(_display, _context);
}

- (BOOL)isRaw { return _source.raw != nullptr; }
- (NSInteger)previewWidth { return _source.width; }
- (NSInteger)previewHeight { return _source.height; }
- (NSInteger)fullWidth { return _source.fullW; }
- (NSInteger)fullHeight { return _source.fullH; }
- (NSInteger)orientation { return _source.orientation; }
- (BOOL)quadHalf { return _source.raw && _source.raw->info.cell == 2; }

- (BOOL)setWatermarkRecord:(nullable SCAMWatermarkRecord *)record {
    if (_record && _shownClean && [self current]) {
        _source.rgba = _dirtyPreview;
        [self uploadSource];
    }
    _record.reset();
    _dirtyPreview.clear();
    _cleanPreview.clear();
    _shownClean = NO;
    if (!record || _source.raw) return NO;
    const scam_edit::wm::Patch &p = record.patch;
    if (!scam_edit::wm::recordFits(p, _source.fullW, _source.fullH, _source.orientation) || !p.verify(appleCodec())) {
        NSLog(@"SCAMEdit watermark record does not fit the photo (%s on %dx%d, orientation %d): not offered", p.describe().c_str(),
              _source.fullW, _source.fullH, _source.orientation);
        return NO;
    }
    try {
        _cleanPreview = scam_edit::wm::cleanPreview(_source, p, appleCodec());
    } catch (const std::exception &e) {
        NSLog(@"SCAMEdit watermark record: %s", e.what());
        _cleanPreview.clear();
        return NO;
    }
    _dirtyPreview = _source.rgba;
    _record = std::make_shared<scam_edit::wm::Patch>(p);
    return YES;
}

- (BOOL)hasWatermarkRecord { return _record != nullptr; }

/// EditorView.onDrawFrame / EditExporter: the preview with the photo's own watermark taken off (EditSource.cleanFor) or as it is
- (void)showClean:(bool)clean {
    clean = clean && _record;
    if (bool(_shownClean) == clean) return;
    _source.rgba = clean ? _cleanPreview : _dirtyPreview;
    [self uploadSource];
    _shownClean = clean;
}

static bool parseState(NSString *json, bool before, scam_edit::EditState &out) {
    scam_edit::EditState s;
    if (!scam_edit::EditState::fromJson(json.UTF8String ?: "", s)) return false;
    out = before ? s.withNeutralTone() : s;
    return true;
}

/// the preview's chain (EditorView.onDrawFrame): proxy, then the picture, cached by their inputs
- (scam_edit::EditMath::Uniforms)prepare:(const scam_edit::EditState &)tone {
    const scam_edit::EditMath::Uniforms u = scam_edit::EditMath::Uniforms::of(tone, *_source.colour);
    _renderer->updateProxy(u);
    // P619: the «LUT» tab's look (EditorView: the tables EditLook loaded for the state; the cache keeps them per key)
    _renderer->setLook(scam_edit::EditLook::on(tone) ? scam_edit::EditLook::applied(tone, scam_edit::EditLook::blocking(tone)) : std::nullopt);
    _renderer->renderSource(_sourceTex, _source.width, _source.height, float(std::max(_source.width, _source.height)),
                            scam_edit::CropMath::Affine(), u, true);
    return u;
}

- (nullable SCAMEditImage *)renderState:(NSString *)json width:(NSInteger)width height:(NSInteger)height margin:(double)margin
                               cropMode:(BOOL)cropMode before:(BOOL)before background:(NSArray<NSNumber *> *)background {
    scam_edit::EditState st, tone;
    if (!parseState(json, false, st) || width <= 0 || height <= 0 || ![self current]) return nil;
    tone = before ? st.withNeutralTone() : st;
    try {
        [self showClean:st.wmRemove];
        const auto u = [self prepare:tone];
        const auto o = _source.oriented();
        float bg[3] = {0, 0, 0};
        for (NSUInteger i = 0; i < 3 && i < background.count; i++) bg[i] = background[i].floatValue;
        const int w = int(width), h = int(height);
        _view.ensure(w, h, GL_RGBA8, false, 1);
        const scam_edit::Rect c = scam_edit::canvas(float(w), float(h), float(margin), st, o[0], o[1], cropMode);
        scam_edit::EditRenderer::Finish f = scam_edit::finishFor(*_renderer, st, c, cropMode, o, _source.orientation, bg);
        f.fbo = _view.fbo;
        f.w = w;
        f.h = h;
        // the background outside the canvas (Android clears the screen to it first)
        const GLfloat clear[4] = {bg[0], bg[1], bg[2], 1.f};
        glBindFramebuffer(GL_FRAMEBUFFER, _view.fbo);
        glClearBufferfv(GL_COLOR, 0, clear);
        _renderer->finish(f, st, u);
        return imageOf(scam_edit::readTarget(w, h), w, h, false);
    } catch (const std::exception &e) {
        NSLog(@"SCAMEdit render: %s", e.what());
        return nil;
    }
}

- (nullable NSArray<NSArray<NSNumber *> *> *)histogramForState:(NSString *)json before:(BOOL)before {
    scam_edit::EditState st, tone;
    if (!parseState(json, false, st) || ![self current]) return nil;
    tone = before ? st.withNeutralTone() : st;
    try {
        [self showClean:st.wmRemove];
        const auto u = [self prepare:tone];
        const auto o = _source.oriented();
        const auto px = scam_edit::CropMath::cropPixels(st, o[0], o[1]);
        const double k = 160.0 / std::max(px[0], px[1]);
        const int w = std::max(8, int(scam_edit::jroundD(px[0] * k))), h = std::max(8, int(scam_edit::jroundD(px[1] * k)));
        _renderer->hist.ensure(w, h, GL_RGBA8, false, 1);
        const float bg[3] = {0, 0, 0};
        scam_edit::EditRenderer::Finish f = scam_edit::finishFor(*_renderer, st, scam_edit::Rect{0, 0, float(w), float(h)}, false, o,
                                                                 _source.orientation, bg);
        f.fbo = _renderer->hist.fbo;
        f.w = w;
        f.h = h;
        _renderer->finish(f, st, u);
        const auto counts = scam_edit::histogramOf(scam_edit::readTarget(w, h), w * h);
        NSMutableArray *out = [NSMutableArray arrayWithCapacity:3];
        for (const auto &ch : counts) {
            NSMutableArray *a = [NSMutableArray arrayWithCapacity:256];
            for (int v : ch) [a addObject:@(v)];
            [out addObject:a];
        }
        return out;
    } catch (const std::exception &e) {
        NSLog(@"SCAMEdit histogram: %s", e.what());
        return nil;
    }
}

- (nullable SCAMEditImage *)thumbnailForState:(NSString *)json side:(NSInteger)side {
    scam_edit::EditState st;
    if (!parseState(json, false, st) || ![self current] || side <= 0) return nil;
    try {
        [self showClean:st.wmRemove];
        const auto u = [self prepare:st];
        const auto o = _source.oriented();
        const auto px = scam_edit::CropMath::cropPixels(st, o[0], o[1]);
        const double k = double(side) / std::max(px[0], px[1]);
        const int w = std::max(8, int(scam_edit::jroundD(px[0] * k))), h = std::max(8, int(scam_edit::jroundD(px[1] * k)));
        scam_edit::EditRenderer::Target t;
        t.ensure(w, h, GL_RGBA8, false, 1);
        const float bg[3] = {0, 0, 0};
        scam_edit::EditRenderer::Finish f = scam_edit::finishFor(*_renderer, st, scam_edit::Rect{0, 0, float(w), float(h)}, false, o,
                                                                 _source.orientation, bg);
        f.fbo = t.fbo;
        f.w = w;
        f.h = h;
        f.preLook = true;
        _renderer->finish(f, st, u);
        auto pixels = scam_edit::readTarget(w, h);
        t.release();
        return imageOf(std::move(pixels), w, h, false);
    } catch (const std::exception &e) {
        NSLog(@"SCAMEdit thumbnail: %s", e.what());
        return nil;
    }
}

- (nullable NSArray<NSNumber *> *)whiteForState:(NSString *)json {
    scam_edit::EditState st;
    if (!parseState(json, false, st) || !_source.raw) return nil;
    const auto *raw = dynamic_cast<const scam_edit::RawColour *>(_source.colour.get());
    if (!raw) return nil;
    const auto w = raw->white(st);
    if (w.size() < 2) return nil;
    return @[@(w[0]), @(w[1])];
}

/// EditExporter.export's GL part with the reader; the preview's proxy goes again afterwards (the export replaced it)
- (nullable SCAMEditImage *)runExport:(NSString *)json reader:(scam_edit::Reader &)reader outMax:(long long)outMax
                          progress:(nullable void (^)(float))progress error:(NSError **)error {
    scam_edit::EditState st;
    if (!parseState(json, false, st)) {
        if (error) *error = editError("not an edit state");
        return nil;
    }
    if (![self current]) {
        if (error) *error = editError("no GL context for the editor");
        return nil;
    }
    try {
        // P633: the proxy from the preview the state shows (EditExporter: source.previewFor(source.cleanFor(state)))
        [self showClean:st.wmRemove];
        // a renderer of its own (EditExporter's): the preview keeps its cached stages
        scam_edit::EditRenderer r;
        r.init(_shaders);
        scam_edit::ExportResult res = scam_edit::renderCopy(r, _source, reader, st, _source.orientation, outMax, [progress](float p) {
            if (progress) progress(p);
        });
        r.release();
        glFinish();
        return imageOf(std::move(res.rgba), res.W, res.H, res.shrunk);
    } catch (const std::exception &e) {
        if (error) *error = editError(e.what());
        return nil;
    }
}

- (nullable SCAMEditImage *)exportState:(NSString *)json picture:(NSData *)rgba width:(NSInteger)width height:(NSInteger)height
                                 outMax:(long long)outMax progress:(nullable void (^)(float))progress error:(NSError **)error {
    if ((NSInteger)rgba.length < width * height * 4 || width <= 0 || height <= 0) {
        if (error) *error = editError("the picture is incomplete");
        return nil;
    }
    // P633: «Убрать» / «Изменить»: the pixels under the photo's own watermark back (EditSource reader.restoreWatermark)
    scam_edit::EditState st;
    if (_record && parseState(json, false, st) && st.wmRemove) {
        try {
            const uint8_t *p = static_cast<const uint8_t *>(rgba.bytes);
            std::vector<uint8_t> restored(p, p + size_t(width) * size_t(height) * 4);
            scam_edit::wm::restorePicture(restored, int(width), int(height), *_record, appleCodec());
            scam_edit::PictureReader reader(restored.data(), int(width), int(height), _source.p3);
            return [self runExport:json reader:reader outMax:outMax progress:progress error:error];
        } catch (const std::exception &e) {
            if (error) *error = editError(e.what());
            return nil;
        }
    }
    scam_edit::PictureReader reader(static_cast<const uint8_t *>(rgba.bytes), int(width), int(height), _source.p3);
    return [self runExport:json reader:reader outMax:outMax progress:progress error:error];
}

- (nullable SCAMEditImage *)exportState:(NSString *)json dng:(NSData *)dng budget:(long long)budget outMax:(long long)outMax
                               progress:(nullable void (^)(float))progress error:(NSError **)error {
    const auto *colour = dynamic_cast<const scam_edit::RawColour *>(_source.colour.get());
    if (!colour) {
        if (error) *error = editError("not a RAW session");
        return nil;
    }
    try {
        bool binned = false;
        const scam_edit::DngRaw::Image full = scam_edit::decodeDngForExport(static_cast<const uint8_t *>(dng.bytes), dng.length, budget, &binned);
        scam_edit::RawReader reader(full, *colour);
        SCAMEditImage *out = [self runExport:json reader:reader outMax:outMax progress:progress error:error];
        if (out && binned) out.shrunk = YES;
        return out;
    } catch (const std::exception &e) {
        if (error) *error = editError(e.what());
        return nil;
    }
}

+ (long long)outMaxForBudget:(long long)budget { return scam_edit::outMax(budget); }
+ (NSInteger)previewSide { return scam_edit::PREVIEW_SIDE; }

@end
