// ScamGL: the subset of EGL 1.5 / OpenGL ES 3.1 that the SCAMERA merge (app/src/main/cpp/scam-*.h) uses, on Metal.
// Compute programs only (the «Качество» route): GLSL is translated at link time (ScamGLCompiler: glslang → SPIR-V →
// SPIRV-Cross MSL) and compiled with fast math off. Every GL command is recorded in order into the context's Metal command
// buffer (compute dispatches, blits for buffer / texture uploads and copies); CPU reads (glMapBufferRange, glReadPixels)
// wait for the work recorded before them, as in GL. Objects are shared by all contexts (names are unique).
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <TargetConditionals.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>

#include "ScamGLCompiler.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

id<MTLDevice> metalDevice() {
    static id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    return device;
}

bool verbose() {
    static const bool on = std::getenv("SCAMGL_LOG") != nullptr;
    return on;
}

void note(const std::string& text) {
    if (verbose()) std::fprintf(stderr, "SCAMGL: %s\n", text.c_str());
}

struct Buffer {
    id<MTLBuffer> mtl = nil;
    size_t size = 0;
};

struct Texture {
    id<MTLTexture> mtl = nil;
    GLenum target = 0, internal = 0;
    int width = 0, height = 0, layers = 1, levels = 1;
    GLint minFilter = GL_NEAREST_MIPMAP_LINEAR, magFilter = GL_LINEAR, wrapS = GL_REPEAT, wrapT = GL_REPEAT;
    id<MTLSamplerState> sampler = nil;
};

struct Shader {
    GLenum type = 0;
    std::string source;
};

struct Location {
    int member = -1, element = 0, sampler = -1;
};

struct Linked {
    scamgl::Compiled compiled;
    id<MTLComputePipelineState> pipeline = nil;
    std::string log;
};

struct Program {
    std::vector<GLuint> shaders;
    bool linked = false;
    std::string log;
    std::shared_ptr<Linked> code;
    std::vector<uint8_t> block;
    std::vector<Location> locations;
    std::unordered_map<std::string, int> byName;
    std::vector<int> samplerUnit; // per Sampled resource, in resource order
};

struct Framebuffer {
    GLuint color[8] = {};
};

struct BufferBinding {
    GLuint buffer = 0;
    GLintptr offset = 0;
    GLsizeiptr size = 0;
};

struct ImageUnit {
    GLuint texture = 0;
    GLint level = 0, layer = 0;
    GLboolean layered = GL_FALSE;
    GLenum format = 0;
};

struct Sync {
    id<MTLCommandBuffer> buffer = nil;
};

struct Context {
    id<MTLCommandQueue> queue = nil;
    id<MTLCommandBuffer> cb = nil;
    id<MTLComputeCommandEncoder> compute = nil;
    std::vector<id<MTLCommandBuffer>> inFlight;
    GLenum error = GL_NO_ERROR;
    GLuint program = 0;
    std::unordered_map<GLenum, GLuint> bound;
    BufferBinding ssbo[96], ubo[96];
    ImageUnit images[32];
    GLuint tex2D[32] = {}, tex2DArray[32] = {}, tex3D[32] = {};
    GLenum activeUnit = 0;
    GLuint readFb = 0, drawFb = 0;
    GLint unpackRowLength = 0, unpackAlignment = 4, packRowLength = 0, packAlignment = 4;
};

std::recursive_mutex gLock;
std::unordered_map<GLuint, Buffer> gBuffers;
std::unordered_map<GLuint, Texture> gTextures;
std::unordered_map<GLuint, Shader> gShaders;
std::unordered_map<GLuint, Program> gPrograms;
std::unordered_map<GLuint, Framebuffer> gFramebuffers;
std::unordered_map<std::string, std::shared_ptr<Linked>> gLinkCache;
GLuint gNextName = 1;
thread_local Context* gCurrent = nullptr;

using Guard = std::lock_guard<std::recursive_mutex>;

Context* ctx() { return gCurrent; }

void fail(GLenum error) {
    if (Context* c = ctx(); c && c->error == GL_NO_ERROR) c->error = error;
}

// ---- command stream ----

id<MTLCommandBuffer> commandBuffer(Context& c) {
    if (!c.cb) c.cb = [c.queue commandBuffer];
    return c.cb;
}

void endCompute(Context& c) {
    if (c.compute) {
        [c.compute endEncoding];
        c.compute = nil;
    }
}

id<MTLComputeCommandEncoder> computeEncoder(Context& c) {
    if (!c.compute) c.compute = [commandBuffer(c) computeCommandEncoder];
    return c.compute;
}

void submit(Context& c, bool wait) {
    endCompute(c);
    if (c.cb) {
        [c.cb commit];
        c.inFlight.push_back(c.cb);
        c.cb = nil;
    }
    if (wait) {
        for (id<MTLCommandBuffer> b : c.inFlight) {
            [b waitUntilCompleted];
            if (b.error) {
                std::fprintf(stderr, "SCAMGL: GPU error: %s\n", b.error.localizedDescription.UTF8String);
                if (c.error == GL_NO_ERROR) c.error = GL_OUT_OF_MEMORY;
            }
        }
        c.inFlight.clear();
    } else {
        // Drop the completed ones; the queue keeps the order of the rest.
        std::vector<id<MTLCommandBuffer>> pending;
        for (id<MTLCommandBuffer> b : c.inFlight) {
            if (b.status < MTLCommandBufferStatusCompleted) pending.push_back(b);
            else if (b.error && c.error == GL_NO_ERROR) c.error = GL_OUT_OF_MEMORY;
        }
        c.inFlight.swap(pending);
    }
}

void blit(Context& c, void (^body)(id<MTLBlitCommandEncoder>)) {
    endCompute(c);
    id<MTLBlitCommandEncoder> b = [commandBuffer(c) blitCommandEncoder];
    body(b);
    [b endEncoding];
}

// ---- formats ----

struct Format {
    MTLPixelFormat pixel;
    int bytes;
};

Format formatOf(GLenum internal) {
    switch (internal) {
    case GL_R8: return {MTLPixelFormatR8Unorm, 1};
    case GL_RG8: return {MTLPixelFormatRG8Unorm, 2};
    case GL_RGBA8: return {MTLPixelFormatRGBA8Unorm, 4};
    case GL_R8UI: return {MTLPixelFormatR8Uint, 1};
    case GL_RGBA8UI: return {MTLPixelFormatRGBA8Uint, 4};
    case GL_R16F: return {MTLPixelFormatR16Float, 2};
    case GL_RG16F: return {MTLPixelFormatRG16Float, 4};
    case GL_RGBA16F: return {MTLPixelFormatRGBA16Float, 8};
    case GL_R32F: return {MTLPixelFormatR32Float, 4};
    case GL_RG32F: return {MTLPixelFormatRG32Float, 8};
    case GL_RGBA32F: return {MTLPixelFormatRGBA32Float, 16};
    case GL_R16UI: return {MTLPixelFormatR16Uint, 2};
    case GL_RG16UI: return {MTLPixelFormatRG16Uint, 4};
    case GL_RGBA16UI: return {MTLPixelFormatRGBA16Uint, 8};
    case GL_R16I: return {MTLPixelFormatR16Sint, 2};
    case GL_R32UI: return {MTLPixelFormatR32Uint, 4};
    case GL_RG32UI: return {MTLPixelFormatRG32Uint, 8};
    case GL_RGBA32UI: return {MTLPixelFormatRGBA32Uint, 16};
    case GL_R32I: return {MTLPixelFormatR32Sint, 4};
    case GL_RG32I: return {MTLPixelFormatRG32Sint, 8};
    case GL_RGBA32I: return {MTLPixelFormatRGBA32Sint, 16};
    case GL_R11F_G11F_B10F: return {MTLPixelFormatRG11B10Float, 4};
    case GL_RGB10_A2: return {MTLPixelFormatRGB10A2Unorm, 4};
    default: return {MTLPixelFormatInvalid, 0};
    }
}

int componentsOf(GLenum format) {
    switch (format) {
    case GL_RED: case GL_RED_INTEGER: return 1;
    case GL_RG: case GL_RG_INTEGER: return 2;
    case GL_RGB: case GL_RGB_INTEGER: return 3;
    case GL_RGBA: case GL_RGBA_INTEGER: return 4;
    default: return 0;
    }
}

int typeBytes(GLenum type) {
    switch (type) {
    case GL_UNSIGNED_BYTE: case GL_BYTE: return 1;
    case GL_UNSIGNED_SHORT: case GL_SHORT: case GL_HALF_FLOAT: return 2;
    case GL_UNSIGNED_INT: case GL_INT: case GL_FLOAT: return 4;
    default: return 0;
    }
}

size_t rowBytes(int width, int bytesPerPixel, GLint rowLength, GLint alignment) {
    size_t row = size_t(rowLength > 0 ? rowLength : width) * size_t(bytesPerPixel);
    const size_t a = size_t(alignment > 0 ? alignment : 1);
    return (row + a - 1) / a * a;
}

id<MTLSamplerState> samplerOf(Texture& t) {
    if (t.sampler) return t.sampler;
    MTLSamplerDescriptor* d = [MTLSamplerDescriptor new];
    auto filter = [](GLint f) { return f == GL_NEAREST || f == GL_NEAREST_MIPMAP_NEAREST || f == GL_NEAREST_MIPMAP_LINEAR
                                       ? MTLSamplerMinMagFilterNearest : MTLSamplerMinMagFilterLinear; };
    auto wrap = [](GLint w) { return w == GL_REPEAT ? MTLSamplerAddressModeRepeat
                                     : w == GL_MIRRORED_REPEAT ? MTLSamplerAddressModeMirrorRepeat : MTLSamplerAddressModeClampToEdge; };
    d.minFilter = filter(t.minFilter);
    d.magFilter = filter(t.magFilter);
    d.mipFilter = t.levels > 1 && (t.minFilter == GL_LINEAR_MIPMAP_LINEAR || t.minFilter == GL_NEAREST_MIPMAP_LINEAR)
        ? MTLSamplerMipFilterLinear : t.levels > 1 && t.minFilter != GL_NEAREST && t.minFilter != GL_LINEAR
        ? MTLSamplerMipFilterNearest : MTLSamplerMipFilterNotMipmapped;
    d.sAddressMode = wrap(t.wrapS);
    d.tAddressMode = wrap(t.wrapT);
    d.rAddressMode = MTLSamplerAddressModeClampToEdge;
    d.normalizedCoordinates = YES;
    t.sampler = [metalDevice() newSamplerStateWithDescriptor:d];
    return t.sampler;
}

id<MTLBuffer> dummyBuffer() {
    static id<MTLBuffer> b = [metalDevice() newBufferWithLength:256 options:MTLResourceStorageModeShared];
    return b;
}

Buffer* bufferBound(Context& c, GLenum target) {
    auto it = c.bound.find(target);
    if (it == c.bound.end() || it->second == 0) return nullptr;
    auto b = gBuffers.find(it->second);
    return b == gBuffers.end() ? nullptr : &b->second;
}

// ---- programs ----

void setupLocations(Program& p) {
    p.locations.clear();
    p.byName.clear();
    const auto& c = p.code->compiled;
    p.block.assign(c.blockSize, 0);
    for (int m = 0; m < int(c.members.size()); ++m) {
        const auto& member = c.members[m];
        const int base = int(p.locations.size());
        for (uint32_t e = 0; e < member.count; ++e) p.locations.push_back({m, int(e), -1});
        p.byName[member.name] = base;
        if (member.arrayStride) {
            for (uint32_t e = 0; e < member.count; ++e) p.byName[member.name + "[" + std::to_string(e) + "]"] = base + int(e);
        }
    }
    p.samplerUnit.clear();
    int s = 0;
    for (const auto& r : c.resources) {
        if (r.type != scamgl::Resource::Sampled) continue;
        p.byName[r.name] = int(p.locations.size());
        p.locations.push_back({-1, 0, s});
        p.samplerUnit.push_back(std::max(0, r.glBinding));
        ++s;
    }
}

std::shared_ptr<Linked> linkCompute(const std::string& source) {
    auto cached = gLinkCache.find(source);
    if (cached != gLinkCache.end()) return cached->second;
    auto linked = std::make_shared<Linked>();
#if TARGET_OS_IPHONE
    linked->compiled = scamgl::compile(source, scamgl::Stage::Compute, true);
#else
    linked->compiled = scamgl::compile(source, scamgl::Stage::Compute, false);
#endif
    if (!linked->compiled.ok) {
        linked->log = linked->compiled.log;
        return linked;
    }
    MTLCompileOptions* options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion2_4;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    options.fastMathEnabled = NO;
#pragma clang diagnostic pop
    NSError* error = nil;
    id<MTLLibrary> library = [metalDevice() newLibraryWithSource:@(linked->compiled.msl.c_str()) options:options error:&error];
    if (!library) {
        linked->log = std::string("MSL: ") + (error ? error.localizedDescription.UTF8String : "unknown");
        if (verbose()) std::fprintf(stderr, "SCAMGL MSL source:\n%s\n", linked->compiled.msl.c_str());
        return linked;
    }
    id<MTLFunction> function = [library newFunctionWithName:@(linked->compiled.entry.c_str())];
    if (!function) {
        linked->log = "MSL: no entry point " + linked->compiled.entry;
        return linked;
    }
    linked->pipeline = [metalDevice() newComputePipelineStateWithFunction:function error:&error];
    if (!linked->pipeline) {
        linked->log = std::string("pipeline: ") + (error ? error.localizedDescription.UTF8String : "unknown");
        return linked;
    }
    const auto& l = linked->compiled.local;
    if (l[0] * l[1] * l[2] > linked->pipeline.maxTotalThreadsPerThreadgroup) {
        linked->log = "workgroup " + std::to_string(l[0] * l[1] * l[2]) + " above the pipeline's "
            + std::to_string(linked->pipeline.maxTotalThreadsPerThreadgroup) + " threads";
        linked->pipeline = nil;
        return linked;
    }
    gLinkCache[source] = linked;
    note("linked " + linked->compiled.entry + " local " + std::to_string(l[0]) + "x" + std::to_string(l[1]) + "x" + std::to_string(l[2]));
    return linked;
}

template <typename T>
void writeUniform(GLuint programName, GLint location, int components, GLsizei count, const T* values, int kind) {
    Guard g(gLock);
    if (location < 0) return;
    auto it = gPrograms.find(programName);
    if (it == gPrograms.end() || !it->second.linked) { fail(GL_INVALID_OPERATION); return; }
    Program& p = it->second;
    if (location >= int(p.locations.size())) { fail(GL_INVALID_OPERATION); return; }
    const Location loc = p.locations[size_t(location)];
    if (loc.sampler >= 0) {
        if (kind == 0) { fail(GL_INVALID_OPERATION); return; }
        p.samplerUnit[size_t(loc.sampler)] = int(values[0]);
        return;
    }
    const auto& m = p.code->compiled.members[size_t(loc.member)];
    const uint32_t stride = m.arrayStride ? m.arrayStride : m.size;
    for (GLsizei i = 0; i < count; ++i) {
        const uint32_t element = uint32_t(loc.element + i);
        if (element >= m.count) break;
        uint8_t* dst = p.block.data() + m.offset + element * stride;
        const int n = std::min<int>(components, int(m.vecsize));
        for (int k = 0; k < n; ++k) {
            const T v = values[size_t(i) * size_t(components) + size_t(k)];
            if (m.baseType == 0) { const float f = float(v); std::memcpy(dst + 4 * k, &f, 4); }
            else if (m.baseType == 1) { const int32_t x = int32_t(v); std::memcpy(dst + 4 * k, &x, 4); }
            else if (m.baseType == 2) { const uint32_t x = uint32_t(v); std::memcpy(dst + 4 * k, &x, 4); }
            else { const uint32_t x = v != T(0) ? 1u : 0u; std::memcpy(dst + 4 * k, &x, 4); }
        }
    }
}

id<MTLTexture> imageView(Texture& t, const ImageUnit& unit, bool arrayed) {
    id<MTLTexture> view = t.mtl;
    const Format f = formatOf(unit.format);
    const bool sliceOfArray = t.target == GL_TEXTURE_2D_ARRAY && !arrayed;
    const bool otherFormat = f.pixel != MTLPixelFormatInvalid && f.pixel != t.mtl.pixelFormat;
    if (sliceOfArray || otherFormat || unit.level != 0) {
        const MTLTextureType type = arrayed ? MTLTextureType2DArray : MTLTextureType2D;
        const NSUInteger first = sliceOfArray ? NSUInteger(unit.layer) : 0;
        const NSUInteger slices = sliceOfArray ? 1 : NSUInteger(t.layers);
        view = [t.mtl newTextureViewWithPixelFormat:otherFormat ? f.pixel : t.mtl.pixelFormat textureType:type
                                             levels:NSMakeRange(NSUInteger(unit.level), 1) slices:NSMakeRange(first, slices)];
    }
    return view;
}

void dispatch(Context& c, GLuint gx, GLuint gy, GLuint gz, Buffer* indirect, GLintptr indirectOffset) {
    auto pit = gPrograms.find(c.program);
    if (pit == gPrograms.end() || !pit->second.linked || !pit->second.code || !pit->second.code->pipeline) { fail(GL_INVALID_OPERATION); return; }
    Program& p = pit->second;
    const auto& cc = p.code->compiled;
    id<MTLComputeCommandEncoder> e = computeEncoder(c);
    [e setComputePipelineState:p.code->pipeline];
    std::vector<uint32_t> sizes(32, 0);
    int sampled = 0;
    for (const auto& r : cc.resources) {
        switch (r.type) {
        case scamgl::Resource::DefaultBlock:
            if (p.block.empty()) [e setBuffer:dummyBuffer() offset:0 atIndex:NSUInteger(r.mslIndex)];
            else if (p.block.size() <= 4096) [e setBytes:p.block.data() length:p.block.size() atIndex:NSUInteger(r.mslIndex)];
            else [e setBuffer:[metalDevice() newBufferWithBytes:p.block.data() length:p.block.size() options:MTLResourceStorageModeShared]
                       offset:0 atIndex:NSUInteger(r.mslIndex)];
            break;
        case scamgl::Resource::UniformBlock:
        case scamgl::Resource::StorageBlock: {
            const int slot = r.glBinding >= 0 && r.glBinding < 96 ? r.glBinding : 0;
            const BufferBinding& b = r.type == scamgl::Resource::UniformBlock ? c.ubo[slot] : c.ssbo[slot];
            auto bit = gBuffers.find(b.buffer);
            if (bit == gBuffers.end() || !bit->second.mtl) {
                [e setBuffer:dummyBuffer() offset:0 atIndex:NSUInteger(r.mslIndex)];
            } else {
                [e setBuffer:bit->second.mtl offset:NSUInteger(b.offset) atIndex:NSUInteger(r.mslIndex)];
                const size_t avail = bit->second.size - size_t(b.offset);
                if (r.mslIndex < 32) sizes[size_t(r.mslIndex)] = uint32_t(b.size > 0 ? std::min<size_t>(size_t(b.size), avail) : avail);
            }
            break;
        }
        case scamgl::Resource::Image: {
            const ImageUnit& unit = c.images[r.glBinding & 31];
            auto t = gTextures.find(unit.texture);
            if (t == gTextures.end() || !t->second.mtl) { note("image unit " + std::to_string(r.glBinding) + " empty"); break; }
            [e setTexture:imageView(t->second, unit, r.arrayed) atIndex:NSUInteger(r.mslIndex)];
            break;
        }
        case scamgl::Resource::Sampled: {
            const int unit = p.samplerUnit[size_t(sampled++)] & 31;
            const GLuint name = r.arrayed ? c.tex2DArray[unit] : c.tex2D[unit];
            auto t = gTextures.find(name);
            if (t == gTextures.end() || !t->second.mtl) { note("texture unit " + std::to_string(unit) + " empty"); break; }
            [e setTexture:t->second.mtl atIndex:NSUInteger(r.mslIndex)];
            [e setSamplerState:samplerOf(t->second) atIndex:NSUInteger(r.mslSampler)];
            break;
        }
        }
    }
    if (cc.bufferSizeIndex >= 0) [e setBytes:sizes.data() length:sizes.size() * 4 atIndex:NSUInteger(cc.bufferSizeIndex)];
    const MTLSize local = MTLSizeMake(cc.local[0], cc.local[1], cc.local[2]);
    if (indirect) {
        [e dispatchThreadgroupsWithIndirectBuffer:indirect->mtl indirectBufferOffset:NSUInteger(indirectOffset) threadsPerThreadgroup:local];
    } else {
        if (gx == 0 || gy == 0 || gz == 0) return;
        [e dispatchThreadgroups:MTLSizeMake(gx, gy, gz) threadsPerThreadgroup:local];
    }
}

GLuint newName() { return gNextName++; }

} // namespace

// ================================ EGL ================================

extern "C" {

EGLDisplay eglGetDisplay(EGLNativeDisplayType) { return reinterpret_cast<EGLDisplay>(1); }
EGLBoolean eglInitialize(EGLDisplay, EGLint* major, EGLint* minor) {
    if (major) *major = 1;
    if (minor) *minor = 5;
    return metalDevice() ? EGL_TRUE : EGL_FALSE;
}
EGLBoolean eglTerminate(EGLDisplay) { return EGL_TRUE; }
EGLBoolean eglBindAPI(EGLenum) { return EGL_TRUE; }
EGLint eglGetError(void) { return EGL_SUCCESS; }
const char* eglQueryString(EGLDisplay, EGLint name) {
    if (name == EGL_VENDOR) return "SCAMERA";
    if (name == EGL_VERSION) return "1.5 SCAMERA Metal";
    if (name == EGL_CLIENT_APIS) return "OpenGL_ES";
    return "EGL_KHR_surfaceless_context";
}
EGLBoolean eglChooseConfig(EGLDisplay, const EGLint*, EGLConfig* configs, EGLint size, EGLint* count) {
    if (configs && size > 0) configs[0] = reinterpret_cast<EGLConfig>(1);
    if (count) *count = 1;
    return EGL_TRUE;
}
EGLContext eglCreateContext(EGLDisplay, EGLConfig, EGLContext, const EGLint*) {
    if (!metalDevice()) return EGL_NO_CONTEXT;
    auto* c = new Context();
    c->queue = [metalDevice() newCommandQueue];
    return c;
}
EGLBoolean eglDestroyContext(EGLDisplay, EGLContext context) {
    auto* c = static_cast<Context*>(context);
    if (!c) return EGL_FALSE;
    submit(*c, true);
    if (gCurrent == c) gCurrent = nullptr;
    delete c;
    return EGL_TRUE;
}
EGLSurface eglCreatePbufferSurface(EGLDisplay, EGLConfig, const EGLint*) { return reinterpret_cast<EGLSurface>(2); }
EGLBoolean eglDestroySurface(EGLDisplay, EGLSurface) { return EGL_TRUE; }
EGLBoolean eglMakeCurrent(EGLDisplay, EGLSurface, EGLSurface, EGLContext context) {
    if (gCurrent && gCurrent != context) submit(*gCurrent, false);
    gCurrent = static_cast<Context*>(context);
    return EGL_TRUE;
}
EGLBoolean eglQueryContext(EGLDisplay, EGLContext, EGLint, EGLint*) { return EGL_FALSE; }
__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char*) { return nullptr; }

// ================================ GL ================================

GLenum glGetError(void) {
    Context* c = ctx();
    if (!c) return GL_NO_ERROR;
    const GLenum e = c->error;
    c->error = GL_NO_ERROR;
    return e;
}

const GLubyte* glGetString(GLenum name) {
    switch (name) {
    case GL_VENDOR: return reinterpret_cast<const GLubyte*>("SCAMERA");
    case GL_RENDERER: return reinterpret_cast<const GLubyte*>("Apple GPU (SCAMERA Metal)");
    case GL_VERSION: return reinterpret_cast<const GLubyte*>("OpenGL ES 3.1 SCAMERA Metal");
    case GL_SHADING_LANGUAGE_VERSION: return reinterpret_cast<const GLubyte*>("OpenGL ES GLSL ES 3.10");
    case GL_EXTENSIONS: return reinterpret_cast<const GLubyte*>("");
    default: return nullptr;
    }
}

void glGetIntegerv(GLenum name, GLint* data) {
    Context* c = ctx();
    switch (name) {
    case GL_MAX_SHADER_STORAGE_BLOCK_SIZE: *data = 128 << 20; break;
    case GL_MAX_TEXTURE_SIZE: *data = 16384; break;
    case GL_MAX_ARRAY_TEXTURE_LAYERS: *data = 2048; break;
    case GL_MAX_COMPUTE_IMAGE_UNIFORMS: *data = 8; break;
    case GL_MAX_IMAGE_UNITS: *data = 8; break;
    case GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS: *data = 16; break;
    case GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS: *data = 16; break;
    case GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS: *data = 80; break;
    case GL_MAX_VIEWPORT_DIMS: data[0] = data[1] = 16384; break;
    case GL_NUM_PROGRAM_BINARY_FORMATS: *data = 0; break;
    case GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT: *data = 16; break;
    case GL_ACTIVE_TEXTURE: *data = GLint(GL_TEXTURE0 + (c ? c->activeUnit : 0)); break;
    case GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS: *data = 1024; break;
    case GL_MAX_COMPUTE_SHARED_MEMORY_SIZE: *data = 32768; break;
    default: *data = 0; note("glGetIntegerv " + std::to_string(name)); break;
    }
}

void glGetInteger64v(GLenum name, GLint64* data) {
    if (name == GL_MAX_SHADER_STORAGE_BLOCK_SIZE) { *data = GLint64(128) << 20; return; }
    GLint v = 0;
    glGetIntegerv(name, &v);
    *data = v;
}

void glGetIntegeri_v(GLenum name, GLuint index, GLint* data) {
    if (name == GL_MAX_COMPUTE_WORK_GROUP_COUNT) { *data = 65535; return; }
    if (name == GL_MAX_COMPUTE_WORK_GROUP_SIZE) { *data = index == 2 ? 64 : 1024; return; }
    *data = 0;
}

void glFlush(void) { if (Context* c = ctx()) submit(*c, false); }
void glFinish(void) { if (Context* c = ctx()) submit(*c, true); }
void glMemoryBarrier(GLbitfield) {} // serial compute encoder + tracked resources: Metal orders the dispatches
void glEnable(GLenum) {}
void glDisable(GLenum) {}
void glBlendEquation(GLenum) {}
void glBlendFunc(GLenum, GLenum) {}
void glViewport(GLint, GLint, GLsizei, GLsizei) {}
void glScissor(GLint, GLint, GLsizei, GLsizei) {}
void glPixelStorei(GLenum name, GLint value) {
    Context* c = ctx();
    if (!c) return;
    if (name == GL_UNPACK_ROW_LENGTH) c->unpackRowLength = value;
    else if (name == GL_UNPACK_ALIGNMENT) c->unpackAlignment = value;
    else if (name == GL_PACK_ROW_LENGTH) c->packRowLength = value;
    else if (name == GL_PACK_ALIGNMENT) c->packAlignment = value;
}

// ---- buffers ----

void glGenBuffers(GLsizei n, GLuint* names) {
    Guard g(gLock);
    for (GLsizei i = 0; i < n; ++i) { names[i] = newName(); gBuffers[names[i]] = Buffer(); }
}
void glDeleteBuffers(GLsizei n, const GLuint* names) {
    Guard g(gLock);
    for (GLsizei i = 0; i < n; ++i) gBuffers.erase(names[i]);
}
void glBindBuffer(GLenum target, GLuint name) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return;
    if (name && !gBuffers.count(name)) gBuffers[name] = Buffer();
    c->bound[target] = name;
}
void glBindBufferBase(GLenum target, GLuint index, GLuint name) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c || index >= 96) { fail(GL_INVALID_VALUE); return; }
    if (name && !gBuffers.count(name)) gBuffers[name] = Buffer();
    BufferBinding b{name, 0, 0};
    if (target == GL_SHADER_STORAGE_BUFFER) c->ssbo[index] = b;
    else if (target == GL_UNIFORM_BUFFER) c->ubo[index] = b;
    c->bound[target] = name;
}
void glBindBufferRange(GLenum target, GLuint index, GLuint name, GLintptr offset, GLsizeiptr size) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c || index >= 96) { fail(GL_INVALID_VALUE); return; }
    BufferBinding b{name, offset, size};
    if (target == GL_SHADER_STORAGE_BUFFER) c->ssbo[index] = b;
    else if (target == GL_UNIFORM_BUFFER) c->ubo[index] = b;
    c->bound[target] = name;
}
void glBufferData(GLenum target, GLsizeiptr size, const void* data, GLenum) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return;
    Buffer* b = bufferBound(*c, target);
    if (!b || size < 0) { fail(GL_INVALID_OPERATION); return; }
    // A new store: commands recorded before keep the old one alive.
    const size_t bytes = std::max<size_t>(size_t(size), 16);
    b->mtl = data ? [metalDevice() newBufferWithBytes:data length:bytes options:MTLResourceStorageModeShared]
                  : [metalDevice() newBufferWithLength:bytes options:MTLResourceStorageModeShared];
    if (!b->mtl) { b->size = 0; fail(GL_OUT_OF_MEMORY); return; }
    if (data && size_t(size) < bytes) std::memset(static_cast<uint8_t*>(b->mtl.contents) + size, 0, bytes - size_t(size));
    if (!data) std::memset(b->mtl.contents, 0, bytes);
    b->size = size_t(size);
}
void glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const void* data) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return;
    Buffer* b = bufferBound(*c, target);
    if (!b || !b->mtl || offset < 0 || size < 0 || size_t(offset + size) > b->size) { fail(GL_INVALID_VALUE); return; }
    if (size == 0) return;
    if (!c->cb && c->inFlight.empty()) { std::memcpy(static_cast<uint8_t*>(b->mtl.contents) + offset, data, size_t(size)); return; }
    // In order with the recorded commands: a staging copy blitted after them.
    id<MTLBuffer> staging = [metalDevice() newBufferWithBytes:data length:NSUInteger(size) options:MTLResourceStorageModeShared];
    id<MTLBuffer> dst = b->mtl;
    blit(*c, ^(id<MTLBlitCommandEncoder> e) {
        [e copyFromBuffer:staging sourceOffset:0 toBuffer:dst destinationOffset:NSUInteger(offset) size:NSUInteger(size)];
    });
}
void glCopyBufferSubData(GLenum readTarget, GLenum writeTarget, GLintptr readOffset, GLintptr writeOffset, GLsizeiptr size) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return;
    Buffer* src = bufferBound(*c, readTarget);
    Buffer* dst = bufferBound(*c, writeTarget);
    if (!src || !dst || !src->mtl || !dst->mtl || size_t(readOffset + size) > src->size || size_t(writeOffset + size) > dst->size) {
        fail(GL_INVALID_VALUE);
        return;
    }
    id<MTLBuffer> s = src->mtl, d = dst->mtl;
    blit(*c, ^(id<MTLBlitCommandEncoder> e) {
        [e copyFromBuffer:s sourceOffset:NSUInteger(readOffset) toBuffer:d destinationOffset:NSUInteger(writeOffset) size:NSUInteger(size)];
    });
}
void* glMapBufferRange(GLenum target, GLintptr offset, GLsizeiptr length, GLbitfield) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return nullptr;
    Buffer* b = bufferBound(*c, target);
    if (!b || !b->mtl || offset < 0 || length <= 0 || size_t(offset + length) > b->size) { fail(GL_INVALID_VALUE); return nullptr; }
    submit(*c, true);
    return static_cast<uint8_t*>(b->mtl.contents) + offset;
}
GLboolean glUnmapBuffer(GLenum) { return GL_TRUE; }

// ---- textures ----

void glGenTextures(GLsizei n, GLuint* names) {
    Guard g(gLock);
    for (GLsizei i = 0; i < n; ++i) { names[i] = newName(); gTextures[names[i]] = Texture(); }
}
void glDeleteTextures(GLsizei n, const GLuint* names) {
    Guard g(gLock);
    for (GLsizei i = 0; i < n; ++i) gTextures.erase(names[i]);
}
void glActiveTexture(GLenum unit) {
    if (Context* c = ctx()) c->activeUnit = (unit - GL_TEXTURE0) & 31;
}
void glBindTexture(GLenum target, GLuint name) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return;
    if (name && !gTextures.count(name)) gTextures[name] = Texture();
    if (name) gTextures[name].target = target;
    if (target == GL_TEXTURE_2D) c->tex2D[c->activeUnit] = name;
    else if (target == GL_TEXTURE_2D_ARRAY) c->tex2DArray[c->activeUnit] = name;
    else if (target == GL_TEXTURE_3D) c->tex3D[c->activeUnit] = name;
}
static Texture* boundTexture(Context& c, GLenum target) {
    const GLuint name = target == GL_TEXTURE_2D ? c.tex2D[c.activeUnit] : target == GL_TEXTURE_2D_ARRAY ? c.tex2DArray[c.activeUnit] : c.tex3D[c.activeUnit];
    auto it = gTextures.find(name);
    return it == gTextures.end() ? nullptr : &it->second;
}
void glTexParameteri(GLenum target, GLenum name, GLint value) {
    Guard g(gLock);
    Context* c = ctx();
    Texture* t = c ? boundTexture(*c, target) : nullptr;
    if (!t) return;
    if (name == GL_TEXTURE_MIN_FILTER) t->minFilter = value;
    else if (name == GL_TEXTURE_MAG_FILTER) t->magFilter = value;
    else if (name == GL_TEXTURE_WRAP_S) t->wrapS = value;
    else if (name == GL_TEXTURE_WRAP_T) t->wrapT = value;
    else return;
    t->sampler = nil;
}
static void storage(GLenum target, GLsizei levels, GLenum internal, GLsizei width, GLsizei height, GLsizei depth) {
    Guard g(gLock);
    Context* c = ctx();
    Texture* t = c ? boundTexture(*c, target) : nullptr;
    const Format f = formatOf(internal);
    if (!t || f.pixel == MTLPixelFormatInvalid || width <= 0 || height <= 0) {
        note("glTexStorage: unsupported format " + std::to_string(internal));
        fail(GL_INVALID_ENUM);
        return;
    }
    MTLTextureDescriptor* d = [MTLTextureDescriptor new];
    d.textureType = target == GL_TEXTURE_2D_ARRAY ? MTLTextureType2DArray : target == GL_TEXTURE_3D ? MTLTextureType3D : MTLTextureType2D;
    d.pixelFormat = f.pixel;
    d.width = NSUInteger(width);
    d.height = NSUInteger(height);
    d.depth = target == GL_TEXTURE_3D ? NSUInteger(depth) : 1;
    d.arrayLength = target == GL_TEXTURE_2D_ARRAY ? NSUInteger(depth) : 1;
    d.mipmapLevelCount = NSUInteger(std::max(1, int(levels)));
    d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsagePixelFormatView | MTLTextureUsageRenderTarget;
    d.storageMode = MTLStorageModeShared;
    t->mtl = [metalDevice() newTextureWithDescriptor:d];
    if (!t->mtl) { fail(GL_OUT_OF_MEMORY); return; }
    t->target = target;
    t->internal = internal;
    t->width = width;
    t->height = height;
    t->layers = target == GL_TEXTURE_2D ? 1 : depth;
    t->levels = std::max(1, int(levels));
    t->sampler = nil;
}
void glTexStorage2D(GLenum target, GLsizei levels, GLenum internal, GLsizei width, GLsizei height) {
    storage(target, levels, internal, width, height, 1);
}
void glTexStorage3D(GLenum target, GLsizei levels, GLenum internal, GLsizei width, GLsizei height, GLsizei depth) {
    storage(target, levels, internal, width, height, depth);
}
void glTexSubImage2D(GLenum target, GLint level, GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type,
                     const void* pixels) {
    Guard g(gLock);
    Context* c = ctx();
    Texture* t = c ? boundTexture(*c, target) : nullptr;
    if (!t || !t->mtl) { fail(GL_INVALID_OPERATION); return; }
    const int bpp = componentsOf(format) * typeBytes(type);
    if (bpp != formatOf(t->internal).bytes) { note("glTexSubImage2D: conversion not supported"); fail(GL_INVALID_OPERATION); return; }
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    if (Buffer* unpack = bufferBound(*c, GL_PIXEL_UNPACK_BUFFER)) src = static_cast<const uint8_t*>(unpack->mtl.contents) + reinterpret_cast<uintptr_t>(pixels);
    const size_t row = rowBytes(width, bpp, c->unpackRowLength, c->unpackAlignment);
    const size_t bytes = row * size_t(height - 1) + size_t(width) * size_t(bpp);
    id<MTLBuffer> staging = [metalDevice() newBufferWithBytes:src length:bytes options:MTLResourceStorageModeShared];
    id<MTLTexture> dst = t->mtl;
    blit(*c, ^(id<MTLBlitCommandEncoder> e) {
        [e copyFromBuffer:staging sourceOffset:0 sourceBytesPerRow:row sourceBytesPerImage:row * size_t(height)
               sourceSize:MTLSizeMake(NSUInteger(width), NSUInteger(height), 1) toTexture:dst destinationSlice:0
         destinationLevel:NSUInteger(level) destinationOrigin:MTLOriginMake(NSUInteger(x), NSUInteger(y), 0)];
    });
}
void glBindImageTexture(GLuint unit, GLuint texture, GLint level, GLboolean layered, GLint layer, GLenum, GLenum format) {
    Context* c = ctx();
    if (!c || unit >= 32) { fail(GL_INVALID_VALUE); return; }
    c->images[unit] = ImageUnit{texture, level, layer, layered, format};
}

// ---- framebuffers (readback) ----

void glGenFramebuffers(GLsizei n, GLuint* names) {
    Guard g(gLock);
    for (GLsizei i = 0; i < n; ++i) { names[i] = newName(); gFramebuffers[names[i]] = Framebuffer(); }
}
void glDeleteFramebuffers(GLsizei n, const GLuint* names) {
    Guard g(gLock);
    for (GLsizei i = 0; i < n; ++i) gFramebuffers.erase(names[i]);
}
void glBindFramebuffer(GLenum target, GLuint name) {
    Context* c = ctx();
    if (!c) return;
    if (target == GL_READ_FRAMEBUFFER || target == GL_FRAMEBUFFER) c->readFb = name;
    if (target == GL_DRAW_FRAMEBUFFER || target == GL_FRAMEBUFFER) c->drawFb = name;
}
void glFramebufferTexture2D(GLenum target, GLenum attachment, GLenum, GLuint texture, GLint) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return;
    const GLuint fb = target == GL_READ_FRAMEBUFFER ? c->readFb : c->drawFb;
    const GLuint i = attachment - GL_COLOR_ATTACHMENT0;
    if (i < 8) gFramebuffers[fb].color[i] = texture;
}
GLenum glCheckFramebufferStatus(GLenum) { return GL_FRAMEBUFFER_COMPLETE; }
void glReadBuffer(GLenum) {}
void glDrawBuffers(GLsizei, const GLenum*) {}
void glInvalidateFramebuffer(GLenum, GLsizei, const GLenum*) {}
void glReadPixels(GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, void* pixels) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return;
    auto fb = gFramebuffers.find(c->readFb);
    auto t = fb == gFramebuffers.end() ? gTextures.end() : gTextures.find(fb->second.color[0]);
    if (t == gTextures.end() || !t->second.mtl) { fail(GL_INVALID_OPERATION); return; }
    const int bpp = componentsOf(format) * typeBytes(type);
    if (bpp != formatOf(t->second.internal).bytes) { note("glReadPixels: conversion not supported"); fail(GL_INVALID_OPERATION); return; }
    submit(*c, true);
    uint8_t* dst = static_cast<uint8_t*>(pixels);
    if (Buffer* pack = bufferBound(*c, GL_PIXEL_PACK_BUFFER)) dst = static_cast<uint8_t*>(pack->mtl.contents) + reinterpret_cast<uintptr_t>(pixels);
    const size_t row = rowBytes(width, bpp, c->packRowLength, c->packAlignment);
    [t->second.mtl getBytes:dst bytesPerRow:row fromRegion:MTLRegionMake2D(NSUInteger(x), NSUInteger(y), NSUInteger(width), NSUInteger(height))
                mipmapLevel:0];
}
void glClearBufferfv(GLenum buffer, GLint drawBuffer, const GLfloat* value) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c || buffer != GL_COLOR || drawBuffer < 0 || drawBuffer >= 8) return;
    auto fb = gFramebuffers.find(c->drawFb);
    auto t = fb == gFramebuffers.end() ? gTextures.end() : gTextures.find(fb->second.color[drawBuffer]);
    if (t == gTextures.end() || !t->second.mtl) { fail(GL_INVALID_OPERATION); return; }
    endCompute(*c);
    MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture = t->second.mtl;
    pass.colorAttachments[0].loadAction = MTLLoadActionClear;
    pass.colorAttachments[0].storeAction = MTLStoreActionStore;
    pass.colorAttachments[0].clearColor = MTLClearColorMake(value[0], value[1], value[2], value[3]);
    [[commandBuffer(*c) renderCommandEncoderWithDescriptor:pass] endEncoding];
}
void glDrawArrays(GLenum, GLint, GLsizei) {
    note("glDrawArrays: render programs are not implemented (the fast merge route)");
    fail(GL_INVALID_OPERATION);
}

// ---- shaders and programs ----

GLuint glCreateShader(GLenum type) {
    Guard g(gLock);
    const GLuint name = newName();
    gShaders[name] = Shader{type, {}};
    return name;
}
void glDeleteShader(GLuint name) {
    Guard g(gLock);
    gShaders.erase(name);
}
void glShaderSource(GLuint name, GLsizei count, const GLchar* const* strings, const GLint* lengths) {
    Guard g(gLock);
    auto it = gShaders.find(name);
    if (it == gShaders.end()) { fail(GL_INVALID_VALUE); return; }
    std::string s;
    for (GLsizei i = 0; i < count; ++i) s.append(strings[i], lengths && lengths[i] >= 0 ? size_t(lengths[i]) : std::strlen(strings[i]));
    it->second.source = s;
}
void glCompileShader(GLuint) {} // translated at link time
void glGetShaderiv(GLuint, GLenum name, GLint* value) {
    *value = name == GL_COMPILE_STATUS ? GL_TRUE : 0;
}
void glGetShaderInfoLog(GLuint, GLsizei size, GLsizei* length, GLchar* log) {
    if (length) *length = 0;
    if (log && size > 0) log[0] = 0;
}
GLuint glCreateProgram(void) {
    Guard g(gLock);
    const GLuint name = newName();
    gPrograms[name] = Program();
    return name;
}
void glDeleteProgram(GLuint name) {
    Guard g(gLock);
    gPrograms.erase(name);
}
void glAttachShader(GLuint program, GLuint shader) {
    Guard g(gLock);
    gPrograms[program].shaders.push_back(shader);
}
void glProgramParameteri(GLuint, GLenum, GLint) {}
void glLinkProgram(GLuint name) {
    Guard g(gLock);
    auto it = gPrograms.find(name);
    if (it == gPrograms.end()) { fail(GL_INVALID_VALUE); return; }
    Program& p = it->second;
    p.linked = false;
    p.log.clear();
    std::string compute;
    for (GLuint s : p.shaders) {
        auto sh = gShaders.find(s);
        if (sh != gShaders.end() && sh->second.type == GL_COMPUTE_SHADER) compute = sh->second.source;
    }
    if (compute.empty()) { p.log = "ScamGL: only compute programs are supported"; return; }
    p.code = linkCompute(compute);
    if (!p.code->pipeline) {
        p.log = p.code->log;
        std::fprintf(stderr, "SCAMGL: link failed: %s\n", p.log.c_str());
        return;
    }
    setupLocations(p);
    p.linked = true;
}
void glGetProgramiv(GLuint name, GLenum pname, GLint* value) {
    Guard g(gLock);
    auto it = gPrograms.find(name);
    if (it == gPrograms.end()) { *value = 0; return; }
    if (pname == GL_LINK_STATUS) *value = it->second.linked ? GL_TRUE : GL_FALSE;
    else if (pname == GL_INFO_LOG_LENGTH) *value = GLint(it->second.log.size() + 1);
    else if (pname == GL_PROGRAM_BINARY_LENGTH) *value = 0;
    else if (pname == GL_ACTIVE_UNIFORMS && it->second.code) {
        *value = GLint(it->second.code->compiled.members.size() + it->second.samplerUnit.size());
    } else if (pname == GL_ACTIVE_UNIFORM_MAX_LENGTH && it->second.code) {
        size_t n = 0;
        for (const auto& m : it->second.code->compiled.members) n = std::max(n, m.name.size() + 4);
        for (const auto& r : it->second.code->compiled.resources) n = std::max(n, r.name.size() + 1);
        *value = GLint(n);
    }
    else if (pname == GL_COMPUTE_WORK_GROUP_SIZE && it->second.code) {
        for (int i = 0; i < 3; ++i) value[i] = GLint(it->second.code->compiled.local[i]);
    } else *value = 0;
}
void glGetProgramInfoLog(GLuint name, GLsizei size, GLsizei* length, GLchar* log) {
    Guard g(gLock);
    auto it = gPrograms.find(name);
    const std::string text = it == gPrograms.end() ? std::string() : it->second.log;
    const size_t n = size > 0 ? std::min(text.size(), size_t(size - 1)) : 0;
    if (log && size > 0) { std::memcpy(log, text.data(), n); log[n] = 0; }
    if (length) *length = GLsizei(n);
}
void glGetProgramBinary(GLuint, GLsizei, GLsizei* length, GLenum*, void*) {
    if (length) *length = 0;
    fail(GL_INVALID_OPERATION);
}
void glProgramBinary(GLuint name, GLenum, const void*, GLsizei) {
    Guard g(gLock);
    gPrograms[name].linked = false;
    fail(GL_INVALID_ENUM);
}
void glUseProgram(GLuint name) {
    if (Context* c = ctx()) c->program = name;
}
GLint glGetUniformLocation(GLuint program, const GLchar* name) {
    Guard g(gLock);
    auto it = gPrograms.find(program);
    if (it == gPrograms.end() || !it->second.linked) return -1;
    auto loc = it->second.byName.find(name);
    return loc == it->second.byName.end() ? -1 : loc->second;
}

static GLenum glTypeOf(const scamgl::Member& m) {
    if (m.columns == 4 && m.vecsize == 4) return GL_FLOAT_MAT4;
    if (m.columns == 3 && m.vecsize == 3) return GL_FLOAT_MAT3;
    static const GLenum floats[] = {GL_FLOAT, GL_FLOAT_VEC2, GL_FLOAT_VEC3, GL_FLOAT_VEC4};
    static const GLenum ints[] = {GL_INT, GL_INT_VEC2, GL_INT_VEC3, GL_INT_VEC4};
    static const GLenum uints[] = {GL_UNSIGNED_INT, GL_UNSIGNED_INT_VEC2, GL_UNSIGNED_INT_VEC3, GL_UNSIGNED_INT_VEC4};
    static const GLenum bools[] = {GL_BOOL, GL_BOOL_VEC2, GL_BOOL_VEC3, GL_BOOL_VEC4};
    const int i = int(std::min<uint32_t>(std::max<uint32_t>(m.vecsize, 1), 4)) - 1;
    return m.baseType == 1 ? ints[i] : m.baseType == 2 ? uints[i] : m.baseType == 3 ? bools[i] : floats[i];
}
void glGetActiveUniform(GLuint program, GLuint index, GLsizei bufSize, GLsizei* length, GLint* size, GLenum* type, GLchar* name) {
    Guard g(gLock);
    auto it = gPrograms.find(program);
    if (it == gPrograms.end() || !it->second.code) { fail(GL_INVALID_VALUE); return; }
    const auto& c = it->second.code->compiled;
    std::string n;
    if (index < c.members.size()) {
        const auto& m = c.members[index];
        n = m.arrayStride ? m.name + "[0]" : m.name;
        if (size) *size = GLint(m.count);
        if (type) *type = glTypeOf(m);
    } else {
        size_t k = index - c.members.size();
        const scamgl::Resource* found = nullptr;
        for (const auto& r : c.resources) if (r.type == scamgl::Resource::Sampled && k-- == 0) { found = &r; break; }
        if (!found) { fail(GL_INVALID_VALUE); return; }
        n = found->name;
        if (size) *size = 1;
        if (type) *type = found->arrayed ? GL_SAMPLER_2D_ARRAY : GL_SAMPLER_2D;
    }
    const size_t len = bufSize > 0 ? std::min(n.size(), size_t(bufSize - 1)) : 0;
    if (name && bufSize > 0) { std::memcpy(name, n.data(), len); name[len] = 0; }
    if (length) *length = GLsizei(len);
}
template <typename T>
static void readUniform(GLuint program, GLint location, T* out) {
    Guard g(gLock);
    auto it = gPrograms.find(program);
    if (location < 0 || it == gPrograms.end() || !it->second.linked || location >= GLint(it->second.locations.size())) {
        fail(GL_INVALID_OPERATION);
        return;
    }
    const Program& p = it->second;
    const Location loc = p.locations[size_t(location)];
    if (loc.sampler >= 0) { out[0] = T(p.samplerUnit[size_t(loc.sampler)]); return; }
    const auto& m = p.code->compiled.members[size_t(loc.member)];
    const uint8_t* src = p.block.data() + m.offset + uint32_t(loc.element) * (m.arrayStride ? m.arrayStride : m.size);
    const uint32_t n = m.vecsize * std::max<uint32_t>(m.columns, 1);
    for (uint32_t k = 0; k < n; ++k) {
        if (m.baseType == 0) { float f; std::memcpy(&f, src + 4 * k, 4); out[k] = T(f); }
        else if (m.baseType == 1) { int32_t x; std::memcpy(&x, src + 4 * k, 4); out[k] = T(x); }
        else { uint32_t x; std::memcpy(&x, src + 4 * k, 4); out[k] = T(x); }
    }
}
void glGetUniformfv(GLuint program, GLint location, GLfloat* params) { readUniform(program, location, params); }
void glGetUniformiv(GLuint program, GLint location, GLint* params) { readUniform(program, location, params); }

static GLuint currentProgram() { Context* c = ctx(); return c ? c->program : 0; }

void glUniform1i(GLint l, GLint a) { writeUniform(currentProgram(), l, 1, 1, &a, 1); }
void glUniform2i(GLint l, GLint a, GLint b) { const GLint v[] = {a, b}; writeUniform(currentProgram(), l, 2, 1, v, 1); }
void glUniform4i(GLint l, GLint a, GLint b, GLint c, GLint d) { const GLint v[] = {a, b, c, d}; writeUniform(currentProgram(), l, 4, 1, v, 1); }
void glUniform1f(GLint l, GLfloat a) { writeUniform(currentProgram(), l, 1, 1, &a, 0); }
void glUniform2f(GLint l, GLfloat a, GLfloat b) { const GLfloat v[] = {a, b}; writeUniform(currentProgram(), l, 2, 1, v, 0); }
void glUniform3f(GLint l, GLfloat a, GLfloat b, GLfloat c) { const GLfloat v[] = {a, b, c}; writeUniform(currentProgram(), l, 3, 1, v, 0); }
void glUniform4f(GLint l, GLfloat a, GLfloat b, GLfloat c, GLfloat d) { const GLfloat v[] = {a, b, c, d}; writeUniform(currentProgram(), l, 4, 1, v, 0); }
void glUniform1fv(GLint l, GLsizei n, const GLfloat* v) { writeUniform(currentProgram(), l, 1, n, v, 0); }
void glUniform4fv(GLint l, GLsizei n, const GLfloat* v) { writeUniform(currentProgram(), l, 4, n, v, 0); }
void glUniform2fv(GLint l, GLsizei n, const GLfloat* v) { writeUniform(currentProgram(), l, 2, n, v, 0); }
void glUniform3fv(GLint l, GLsizei n, const GLfloat* v) { writeUniform(currentProgram(), l, 3, n, v, 0); }
void glUniform3iv(GLint l, GLsizei n, const GLint* v) { writeUniform(currentProgram(), l, 3, n, v, 1); }
void glUniform4iv(GLint l, GLsizei n, const GLint* v) { writeUniform(currentProgram(), l, 4, n, v, 1); }
void glUniform1iv(GLint l, GLsizei n, const GLint* v) { writeUniform(currentProgram(), l, 1, n, v, 1); }
void glUniform2iv(GLint l, GLsizei n, const GLint* v) { writeUniform(currentProgram(), l, 2, n, v, 1); }
void glUniform1uiv(GLint l, GLsizei n, const GLuint* v) { writeUniform(currentProgram(), l, 1, n, v, 2); }
void glProgramUniform1i(GLuint p, GLint l, GLint a) { writeUniform(p, l, 1, 1, &a, 1); }
void glProgramUniform2i(GLuint p, GLint l, GLint a, GLint b) { const GLint v[] = {a, b}; writeUniform(p, l, 2, 1, v, 1); }
void glProgramUniform1f(GLuint p, GLint l, GLfloat a) { writeUniform(p, l, 1, 1, &a, 0); }
void glProgramUniform2f(GLuint p, GLint l, GLfloat a, GLfloat b) { const GLfloat v[] = {a, b}; writeUniform(p, l, 2, 1, v, 0); }
void glProgramUniform3f(GLuint p, GLint l, GLfloat a, GLfloat b, GLfloat c) { const GLfloat v[] = {a, b, c}; writeUniform(p, l, 3, 1, v, 0); }
void glProgramUniform4f(GLuint p, GLint l, GLfloat a, GLfloat b, GLfloat c, GLfloat d) {
    const GLfloat v[] = {a, b, c, d};
    writeUniform(p, l, 4, 1, v, 0);
}
void glProgramUniformMatrix4fv(GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat* value) {
    Guard g(gLock);
    auto it = gPrograms.find(program);
    if (location < 0 || it == gPrograms.end() || !it->second.linked || location >= GLint(it->second.locations.size())) return;
    Program& p = it->second;
    const Location loc = p.locations[size_t(location)];
    if (loc.member < 0) return;
    const auto& m = p.code->compiled.members[size_t(loc.member)];
    const uint32_t colStride = m.matrixStride ? m.matrixStride : 16;
    const uint32_t stride = m.arrayStride ? m.arrayStride : m.size;
    for (GLsizei i = 0; i < count && uint32_t(loc.element + i) < m.count; ++i) {
        uint8_t* dst = p.block.data() + m.offset + uint32_t(loc.element + i) * stride;
        for (int col = 0; col < 4; ++col)
            for (int row = 0; row < 4; ++row) {
                const float v = transpose ? value[i * 16 + row * 4 + col] : value[i * 16 + col * 4 + row];
                std::memcpy(dst + col * colStride + row * 4, &v, 4);
            }
    }
}

// ---- dispatch, sync, queries ----

void glDispatchCompute(GLuint x, GLuint y, GLuint z) {
    Guard g(gLock);
    if (Context* c = ctx()) dispatch(*c, x, y, z, nullptr, 0);
}
void glDispatchComputeIndirect(GLintptr offset) {
    Guard g(gLock);
    Context* c = ctx();
    if (!c) return;
    Buffer* b = bufferBound(*c, GL_DISPATCH_INDIRECT_BUFFER);
    if (!b || !b->mtl) { fail(GL_INVALID_OPERATION); return; }
    dispatch(*c, 0, 0, 0, b, offset);
}
GLsync glFenceSync(GLenum, GLbitfield) {
    Context* c = ctx();
    if (!c) return nullptr;
    id<MTLCommandBuffer> last = c->cb;
    submit(*c, false);
    if (!last && !c->inFlight.empty()) last = c->inFlight.back();
    auto* s = new Sync();
    s->buffer = last;
    return reinterpret_cast<GLsync>(s);
}
GLenum glClientWaitSync(GLsync sync, GLbitfield, GLuint64 timeout) {
    auto* s = reinterpret_cast<Sync*>(sync);
    if (!s || !s->buffer || s->buffer.status >= MTLCommandBufferStatusCompleted) return GL_ALREADY_SIGNALED;
    if (timeout == 0) return GL_TIMEOUT_EXPIRED;
    [s->buffer waitUntilCompleted];
    return GL_CONDITION_SATISFIED;
}
void glDeleteSync(GLsync sync) { delete reinterpret_cast<Sync*>(sync); }
void glGenQueries(GLsizei n, GLuint* names) {
    Guard g(gLock);
    for (GLsizei i = 0; i < n; ++i) names[i] = newName();
}
void glDeleteQueries(GLsizei, const GLuint*) {}

} // extern "C"
