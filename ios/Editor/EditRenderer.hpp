// EditRenderer + EditGl (gallery/editor) one to one: the photo editor's GPU pipeline on OpenGL ES 3 - the Android GLSL of
// assets/shaders/editor unchanged (Mesa on the host, ScamGL on the iPhone). Passes: demosaic (RAW), the 512 px proxy (area
// downscale, moments, two guided-filter rounds), nr, develop, gauss2 x2, main (mipmapped), finish (geometry, vignette, grain).
// Stages are cached by their inputs for the preview, as on Android. P619 the «LUT» tab's look (EditLook: the camera's LUTs and film
// simulation): the display table (editor/look.glsl), then the film's grain with the camera's FilmGrain shaders (film/noise, film/blur,
// film/grain_sf or film/grain), mipmapped; finish reads it.
#pragma once

#include "CropMath.hpp"
#include "EditLook.hpp"
#include "EditMath.hpp"

#include <GLES3/gl31.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef GL_RGBA16UI
#define GL_RGBA16UI 0x8D76
#endif

namespace scam_edit {

namespace EditGl {

inline std::string readFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("editor: cannot read " + path);
    std::stringstream s;
    s << f.rdbuf();
    return s.str();
}

/// EditGl.fragmentSource: version, precisions, common.glsl, the pass
inline std::string fragmentSource(const std::string& root, const std::string& name) {
    return "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp usampler2D;\n"
        + readFile(root + "/editor/common.glsl") + "\n#line 1\n" + readFile(root + "/editor/" + name);
}

inline GLuint shader(GLenum type, const std::string& src, const std::string& name) {
    const GLuint s = glCreateShader(type);
    const char* p = src.c_str();
    glShaderSource(s, 1, &p, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096] = {};
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        glDeleteShader(s);
        throw std::runtime_error("editor shader " + name + ": " + log);
    }
    return s;
}

/// EditGl.filmSource: the camera's film shader (assets/shaders/film) with the version line
inline std::string filmSource(const std::string& root, const std::string& name) { return "#version 300 es\n" + readFile(root + "/film/" + name); }

inline GLuint link(const std::string& root, const std::string& fragmentSource, const std::string& fragment) {
    const GLuint vs = shader(GL_VERTEX_SHADER, "#version 300 es\n" + readFile(root + "/editor/quad.vert"), "quad.vert");
    const GLuint fs = shader(GL_FRAGMENT_SHADER, fragmentSource, fragment);
    const GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) {
        char log[4096] = {};
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        glDeleteProgram(p);
        throw std::runtime_error("editor program " + fragment + " link: " + log);
    }
    return p;
}
inline GLuint program(const std::string& root, const std::string& fragment) { return link(root, fragmentSource(root, fragment), fragment); }
/// EditGl.filmProgram
inline GLuint filmProgram(const std::string& root, const std::string& name) { return link(root, filmSource(root, name), "film/" + name); }

inline void params(bool linear, bool mip) {
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? (mip ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR) : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

inline GLuint texture(int w, int h, GLenum internalFormat, bool linear, int levels) {
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, levels, internalFormat, w, h);
    params(linear, levels > 1);
    return t;
}

inline int levelsFor(int w, int h) {
    int l = 1;
    for (int s = std::max(w, h); s > 1; s >>= 1) l++;
    return l;
}

/// EditGl.upload of an ARGB_8888 bitmap: RGBA8 rows top-down (row 0 = v 0), linear filter
inline GLuint uploadRgba8(const uint8_t* rgba, int w, int h) {
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    params(true, false);
    return t;
}

/// EditGl.uploadU16: w x h samples at (x, y) of a 16-bit buffer fullW samples wide (channels 1 or 3) as R16UI (RGB as RGBA16UI:
/// the shader reads .rgb), the region copied on the CPU (GL_UNPACK_SKIP_* are not in ScamGL).
inline GLuint uploadU16(const uint16_t* data, int fullW, int channels, int x, int y, int w, int h) {
    const int outCh = channels == 3 ? 4 : 1;
    std::vector<uint16_t> region(size_t(w) * size_t(h) * size_t(outCh));
    for (int j = 0; j < h; j++) {
        const uint16_t* src = data + (size_t(y + j) * size_t(fullW) + size_t(x)) * size_t(channels);
        uint16_t* dst = region.data() + size_t(j) * size_t(w) * size_t(outCh);
        if (channels == 3) {
            for (int i = 0; i < w; i++) {
                dst[i * 4] = src[i * 3];
                dst[i * 4 + 1] = src[i * 3 + 1];
                dst[i * 4 + 2] = src[i * 3 + 2];
                dst[i * 4 + 3] = 0;
            }
        } else {
            std::memcpy(dst, src, size_t(w) * 2);
        }
    }
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
    glTexImage2D(GL_TEXTURE_2D, 0, channels == 3 ? GL_RGBA16UI : GL_R16UI, w, h, 0, channels == 3 ? GL_RGBA_INTEGER : GL_RED_INTEGER,
                 GL_UNSIGNED_SHORT, region.data());
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    params(false, false);
    return t;
}

inline GLuint fbo(GLuint tex) {
    GLuint f = 0;
    glGenFramebuffers(1, &f);
    glBindFramebuffer(GL_FRAMEBUFFER, f);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    return f;
}

inline void deleteTexture(GLuint t) { if (t) glDeleteTextures(1, &t); }
inline void deleteFbo(GLuint f) { if (f) glDeleteFramebuffers(1, &f); }

/// GL_EXT_color_buffer_float (RGBA32F targets): ScamGL (Metal) renders RGBA32Float; Mesa says so in its extensions
inline bool floatTargets() {
#ifdef SCAM_IOS
    return true;
#else
    const char* all = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    return all && (std::string(" ") + all + " ").find(" GL_EXT_color_buffer_float ") != std::string::npos;
#endif
}

} // namespace EditGl

/// A RAW region's samples for rawSource: fixed point (scale = 1.0 at white), Bayer (1 channel, phase) or RGB (3 channels).
struct RawSamples {
    const uint16_t* data = nullptr;
    int width = 0, height = 0;   // the whole buffer's
    bool rgb = false;
    int phase = 0;               // DngRaw RGGB 0, GRBG 1, GBRG 2, BGGR 3
    float scale = 1.f / 16384.f;
};

class EditRenderer {
public:
    static constexpr int PROXY_SIDE = 512;
    static constexpr float EPS_MID = 0.04f, EPS_LARGE = 0.06f;

    struct Target {
        GLuint tex = 0, fbo = 0;
        int w = 0, h = 0, levels = 0;
        GLenum format = 0;
        void ensure(int w_, int h_, GLenum format_, bool linear, int levels_) {
            if (tex != 0 && w == w_ && h == h_ && format == format_ && levels == levels_) return;
            release();
            w = w_; h = h_; format = format_; levels = levels_;
            tex = EditGl::texture(w, h, format, linear, levels);
            fbo = EditGl::fbo(tex);
        }
        void release() {
            EditGl::deleteFbo(fbo);
            EditGl::deleteTexture(tex);
            tex = fbo = 0;
        }
    };

    /// What finish needs besides the state (EditRenderer.Finish).
    struct Finish {
        GLuint fbo = 0;
        int w = 0, h = 0;
        float originX = 0, originY = 0;
        bool flipY = false, swap = false;
        int mode = 0;   // 0 normal, 1 the crop tab
        bool preLook = false;
        CropMath::Affine outToBase, baseToImg, imgToTex;
        float bg[3] = {0, 0, 0};
        float outLongSide = 0;
    };

    Target hist;
    float latticeAspect = 1.f;

    ~EditRenderer() = default;

    void init(const std::string& shadersRoot) {
        root_ = shadersRoot;
        if (scam_post::filmAssetsRoot().empty()) scam_post::filmAssetsRoot() = shadersRoot + "/../film";
        pDemosaic = EditGl::program(shadersRoot, "demosaic.glsl");
        pNr = EditGl::program(shadersRoot, "nr.glsl");
        pDevelop = EditGl::program(shadersRoot, "develop.glsl");
        pArea = EditGl::program(shadersRoot, "area.glsl");
        pProxy = EditGl::program(shadersRoot, "proxy.glsl");
        pDown4 = EditGl::program(shadersRoot, "down4.glsl");
        pGauss4 = EditGl::program(shadersRoot, "gauss4.glsl");
        pGauss2 = EditGl::program(shadersRoot, "gauss2.glsl");
        pGuided = EditGl::program(shadersRoot, "guided.glsl");
        pMain = EditGl::program(shadersRoot, "main.glsl");
        pFinish = EditGl::program(shadersRoot, "finish.glsl");
        momentFormat = EditGl::floatTargets() ? GL_RGBA32F : GL_RGBA16F;
        glGenTextures(1, &lutTex);
        glBindTexture(GL_TEXTURE_2D, lutTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, EditMath::LUT_SIZE, 1);
        EditGl::params(false, false);
        glGenTextures(1, &pointsTex);
        glBindTexture(GL_TEXTURE_2D, pointsTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, EditMath::LUT_SIZE, 4);
        EditGl::params(false, false);
        invalidate();
    }

    void invalidate() {
        cachedSrc = 0;
        kNr.clear(); kDev.clear(); kBlur.clear(); kMain.clear(); kProxy.clear();
        haveNr = haveDev = haveBlur = haveMain = haveProxy = false;
        cachedLut.clear();
        cachedPoints.clear();
    }

    // ---------------------------------------------------------------- sources

    /// a RAW region (x, y, w, h of the samples) demosaiced into a linear camera-RGB texture this renderer owns
    GLuint rawSource(const RawSamples& img, int x, int y, int w, int h) {
        const GLuint in = EditGl::uploadU16(img.data, img.width, img.rgb ? 3 : 1, x, y, w, h);
        raw.ensure(w, h, GL_RGBA16F, true, 1);
        bind(raw.fbo, w, h);
        glUseProgram(pDemosaic);
        texU(pDemosaic, "uRawTex", 0, in);
        glUniform2i(loc(pDemosaic, "uSize"), w, h);
        const auto cfa = cfaOf(img.phase);
        glUniform4i(loc(pDemosaic, "uCfa"), cfa[0], cfa[1], cfa[2], cfa[3]);
        glUniform1f(loc(pDemosaic, "uScale"), img.scale);
        glUniform1i(loc(pDemosaic, "uKind"), img.rgb ? 1 : 0);
        draw();
        EditGl::deleteTexture(in);
        return raw.tex;
    }

    static std::array<int, 4> cfaOf(int phase) {
        switch (phase) {
        case 1: return {1, 0, 2, 1};
        case 2: return {1, 2, 0, 1};
        case 3: return {2, 1, 1, 0};
        default: return {0, 1, 1, 2};
        }
    }

    // ---------------------------------------------------------------- proxy

    void setProxySource(GLuint tex, int w, int h) {
        const float s = jmin(1.f, float(PROXY_SIDE) / float(std::max(w, h)));
        const int pw = std::max(8, jround(float(w) * s)), ph = std::max(8, jround(float(h) * s));
        proxySrc.ensure(pw, ph, GL_RGBA16F, true, 1);
        bind(proxySrc.fbo, pw, ph);
        glUseProgram(pArea);
        texU(pArea, "uIn", 0, tex);
        glUniform2f(loc(pArea, "uInSize"), float(w), float(h));
        glUniform2f(loc(pArea, "uOutSize"), float(pw), float(ph));
        glUniform1i(loc(pArea, "uN"), std::max(1, std::min(8, jint(std::ceil(double(float(w) / float(pw)))))));
        draw();
        largeW = (pw + 3) / 4;
        largeH = (ph + 3) / 4;
        haveProxy = false;
    }

    void updateProxy(const EditMath::Uniforms& u) {
        const std::string key = devKey(u);
        if (haveProxy && key == kProxy) return;
        kProxy = key;
        haveProxy = true;
        const int pw = proxySrc.w, ph = proxySrc.h;
        pm.ensure(pw, ph, momentFormat, false, 1);
        bind(pm.fbo, pw, ph);
        glUseProgram(pProxy);
        texU(pProxy, "uIn", 0, proxySrc.tex);
        glUniform2f(loc(pProxy, "uTexel"), 1.f / float(pw), 1.f / float(ph));
        glUniform3f(loc(pProxy, "uAir"), u.air[0], u.air[1], u.air[2]);
        developUniforms(pProxy, u);
        draw();
        const float longSide = float(std::max(pw, ph));
        guided(pm, pmTmp, pmBlur, ab, abTmp, abMid, 0.01f * longSide, EPS_MID);
        lm.ensure(largeW, largeH, momentFormat, false, 1);
        bind(lm.fbo, largeW, largeH);
        glUseProgram(pDown4);
        texU(pDown4, "uIn", 0, pm.tex);
        glUniform2i(loc(pDown4, "uInSize"), pw, ph);
        draw();
        guided(lm, lmTmp, lmBlur, lab, labTmp, abLarge, 0.04f * longSide / 4.f, EPS_LARGE);
    }

    float largeScaleX() const { return float(proxySrc.w) / (4.f * float(largeW)); }
    float largeScaleY() const { return float(proxySrc.h) / (4.f * float(largeH)); }

    // ---------------------------------------------------------------- the picture

    void renderSource(GLuint src, int w, int h, float longSide, const CropMath::Affine& texToProxy, const EditMath::Uniforms& u, bool cache) {
        if (!cache || src != cachedSrc || dev.w != w || dev.h != h || main.w != w || main.h != h) {
            haveNr = haveDev = haveBlur = haveMain = false;
            cachedSrc = src;
        }
        const float ref = EditMath::refScale(longSide);
        const bool nrOn = u.nrLuma || u.nrChroma;
        std::string k1 = "off";
        if (nrOn) {
            k1 = "nr";
            put(k1, u.sigL); put(k1, u.sigC); put(k1, u.stepLRef); put(k1, u.stepCRef);
            k1 += char(u.nrLuma); k1 += char(u.nrChroma); k1 += char(u.linearIn);
        }
        const bool nrChanged = !haveNr || k1 != kNr;
        GLuint devIn = src;
        if (nrOn) {
            nr.ensure(w, h, GL_RGBA16F, true, 1);
            if (nrChanged) {
                bind(nr.fbo, w, h);
                glUseProgram(pNr);
                texU(pNr, "uSrc", 0, src);
                glUniform2f(loc(pNr, "uTexel"), 1.f / float(w), 1.f / float(h));
                glUniform1f(loc(pNr, "uStepL"), u.stepLRef * ref);
                glUniform1f(loc(pNr, "uStepC"), u.stepCRef * ref);
                glUniform1f(loc(pNr, "uSigL"), u.sigL);
                glUniform1f(loc(pNr, "uSigC"), u.sigC);
                glUniform1f(loc(pNr, "uLumaOn"), u.nrLuma ? 1.f : 0.f);
                glUniform1f(loc(pNr, "uChromaOn"), u.nrChroma ? 1.f : 0.f);
                glUniform1i(loc(pNr, "uLinearIn"), u.linearIn ? 1 : 0);
                draw();
            }
            devIn = nr.tex;
        }
        kNr = k1;
        haveNr = true;
        const std::string k2 = k1 + "|" + devKey(u);
        const bool devChanged = !haveDev || k2 != kDev;
        dev.ensure(w, h, GL_RGBA16F, true, 1);
        if (devChanged) {
            bind(dev.fbo, w, h);
            glUseProgram(pDevelop);
            texU(pDevelop, "uIn", 0, devIn);
            glUniform2f(loc(pDevelop, "uTexel"), 1.f / float(w), 1.f / float(h));
            developUniforms(pDevelop, u);
            draw();
        }
        kDev = k2;
        haveDev = true;
        const float sA = u.sigmaSharpRef * ref, sB = u.sigmaTexRef * ref;
        std::string k3 = "off";
        if (u.blurOn) { k3 = k2 + "|"; put(k3, sA); put(k3, sB); }
        if (u.blurOn && (!haveBlur || k3 != kBlur)) {
            blurTmp.ensure(w, h, GL_RGBA16F, true, 1);
            blur.ensure(w, h, GL_RGBA16F, true, 1);
            const float a0[4] = {0, 0, 0, 1}, b0[4] = {0, 0, 0, 1}, a1[4] = {1, 0, 0, 0}, b1[4] = {0, 1, 0, 0};
            gauss2(dev, blurTmp, 1, 0, a0, b0, sA, sB);
            gauss2(blurTmp, blur, 0, 1, a1, b1, sA, sB);
        }
        kBlur = k3;
        haveBlur = true;
        std::string k4 = k3 + "|" + mainKey(u) + "|";
        put(k4, texToProxy.a); put(k4, texToProxy.c); put(k4, texToProxy.e); put(k4, texToProxy.f);
        k4 += "|" + kProxy;
        if (!haveMain || k4 != kMain) {
            kMain = k4;
            haveMain = true;
            haveLook = false;
            renderMain(w, h, texToProxy, u);
        }
        renderLook(w, h, longSide, texToProxy);
    }

    /// P619: the LUT / film to apply in the next renderSource (none: nothing)
    void setLook(const std::optional<EditLook::Applied>& a) { lookApplied = a; }

    GLuint mainTexture() const { return outTex != 0 ? outTex : main.tex; }
    int mainWidth() const { return main.w; }
    int mainHeight() const { return main.h; }

    void finish(const Finish& f, const EditState& s, const EditMath::Uniforms& u) {
        bind(f.fbo, f.w, f.h);
        glUseProgram(pFinish);
        texU(pFinish, "uImg", 0, f.preLook ? main.tex : mainTexture());
        const auto m1 = f.outToBase.mat3(), m2 = f.baseToImg.mat3(), m3 = f.imgToTex.mat3();
        glUniformMatrix3fv(loc(pFinish, "uOutToBase"), 1, GL_FALSE, m1.data());
        glUniformMatrix3fv(loc(pFinish, "uBaseToImg"), 1, GL_FALSE, m2.data());
        glUniformMatrix3fv(loc(pFinish, "uImgToTex"), 1, GL_FALSE, m3.data());
        glUniform4f(loc(pFinish, "uCrop"), s.cropL, s.cropT, s.cropR, s.cropB);
        glUniform2f(loc(pFinish, "uOutSize"), float(f.w), float(f.h));
        glUniform2f(loc(pFinish, "uOrigin"), f.originX, f.originY);
        glUniform1i(loc(pFinish, "uFlipY"), f.flipY ? 1 : 0);
        glUniform1i(loc(pFinish, "uMode"), f.mode);
        glUniform3f(loc(pFinish, "uBg"), f.bg[0], f.bg[1], f.bg[2]);
        glUniform1f(loc(pFinish, "uVig"), u.vignette);
        glUniform1f(loc(pFinish, "uVigMid"), u.vignetteMid);
        glUniform1f(loc(pFinish, "uGrain"), u.grain);
        const float cw = s.cropR - s.cropL, ch = s.cropB - s.cropT;
        const float cellsLong = EditMath::REF_SIDE / u.grainCellRef;
        float lx = cellsLong, ly = cellsLong;
        if (latticeAspect > 0) {
            if (latticeAspect >= 1) ly = cellsLong / latticeAspect;
            else lx = cellsLong * latticeAspect;
        }
        glUniform2f(loc(pFinish, "uGrainLattice"), lx, ly);
        glUniform1f(loc(pFinish, "uGrainPx"), f.outLongSide / cellsLong);
        glUniform1i(loc(pFinish, "uSeed"), 1351);
        glUniform1i(loc(pFinish, "uSwap"), f.swap ? 1 : 0);
        if (cw <= 0 || ch <= 0) return;
        draw();
    }

    void releaseTargets() {
        for (Target* t : {&raw, &proxySrc, &pm, &pmTmp, &pmBlur, &ab, &abTmp, &abMid, &lm, &lmTmp, &lmBlur, &lab, &labTmp, &abLarge, &nr,
                          &dev, &blurTmp, &blur, &main, &hist, &lookTmp, &look, &noiseA, &noiseB})
            t->release();
        outTex = 0;
        haveLook = false;
        invalidate();
    }

    void release() {
        releaseTargets();
        for (GLuint p : {pDemosaic, pNr, pDevelop, pArea, pProxy, pDown4, pGauss4, pGauss2, pGuided, pMain, pFinish, pLook, pNoise, pBlur,
                         pGrainSf, pGrainBw})
            if (p) glDeleteProgram(p);
        pDemosaic = pNr = pDevelop = pArea = pProxy = pDown4 = pGauss4 = pGauss2 = pGuided = pMain = pFinish = 0;
        pLook = pNoise = pBlur = pGrainSf = pGrainBw = 0;
        EditGl::deleteTexture(lookLutTex);
        EditGl::deleteTexture(grainLutTex);
        lookLutTex = grainLutTex = 0;
        lookLutUp = grainLutUp = nullptr;
        EditGl::deleteTexture(lutTex);
        EditGl::deleteTexture(pointsTex);
        lutTex = pointsTex = 0;
        invalidate();
    }

    void bindTarget(GLuint fbo, int w, int h) { bind(fbo, w, h); }

private:
    GLuint pDemosaic = 0, pNr = 0, pDevelop = 0, pArea = 0, pProxy = 0, pDown4 = 0, pGauss4 = 0, pGauss2 = 0, pGuided = 0, pMain = 0,
           pFinish = 0;
    GLuint lutTex = 0, pointsTex = 0;
    GLuint outTex = 0;
    // P619: the look's programs (compiled on first use), its targets, its uploaded tables and what its output was made of
    std::string root_;
    GLuint pLook = 0, pNoise = 0, pBlur = 0, pGrainSf = 0, pGrainBw = 0;
    GLuint lookLutTex = 0, grainLutTex = 0;
    EditLook::FilmLutP lookLutUp, grainLutUp;
    Target lookTmp, look, noiseA, noiseB;
    std::optional<EditLook::Applied> lookApplied;
    std::string kLook;
    bool haveLook = false;

    void lookPrograms(bool reference, bool classic) {
        if (pLook == 0) pLook = EditGl::program(root_, "look.glsl");
        if (reference && pGrainSf == 0) {
            pNoise = EditGl::filmProgram(root_, "noise.glsl");
            pBlur = EditGl::filmProgram(root_, "blur.glsl");
            pGrainSf = EditGl::filmProgram(root_, "grain_sf.glsl");
        }
        if (classic && pGrainBw == 0) pGrainBw = EditGl::filmProgram(root_, "grain.glsl");
    }

    static void tex3d(GLuint p, const char* name, int unit, GLuint tex) {
        glActiveTexture(GLenum(GL_TEXTURE0 + unit));
        glBindTexture(GL_TEXTURE_3D, tex);
        glUniform1i(loc(p, name), unit);
    }

    void filmBlur(Target& in, Target& out, float dx, float dy, float sigma) {
        bind(out.fbo, out.w, out.h);
        glUseProgram(pBlur);
        texU(pBlur, "InputBuffer", 0, in.tex);
        glUniform2f(loc(pBlur, "dir"), dx, dy);
        glUniform1f(loc(pBlur, "sigma"), sigma);
        draw();
    }

    /// EditRenderer.renderLook: the display table, then the film's grain - the noise hashed on the photo's pixel position (this
    /// texture's corner from texToProxy), its blur and the grain sized for longSide, the whole photo's long side in its pixels
    void renderLook(int w, int h, float longSide, const CropMath::Affine& texToProxy) {
        if (!lookApplied) {
            outTex = main.tex;
            haveLook = false;
            return;
        }
        const EditLook::Applied& a = *lookApplied;
        const int ox = int(std::floor(texToProxy.c * w / texToProxy.a + 0.5)), oy = int(std::floor(texToProxy.f * h / texToProxy.e + 0.5));
        const std::optional<EditLook::Grain> g = EditLook::grain(a.film.get(), longSide);
        const bool grainOn = g && (!g->reference || a.grainLut);
        std::string k = kMain + "|" + a.key + "|" + std::to_string(ox) + "," + std::to_string(oy) + "|";
        put(k, longSide);
        k += std::to_string(reinterpret_cast<uintptr_t>(a.lut.get())) + "," + std::to_string(reinterpret_cast<uintptr_t>(a.grainLut.get()))
             + (grainOn ? "1" : "0");
        if (haveLook && k == kLook && look.tex != 0 && look.w == w && look.h == h) {
            outTex = look.tex;
            return;
        }
        try {
            lookPrograms(grainOn && g->reference, grainOn && !g->reference);
        } catch (const std::exception&) {
            outTex = main.tex;
            haveLook = false;
            lookApplied.reset();
            return;
        }
        kLook = k;
        haveLook = true;
        if (a.lut != lookLutUp) {
            EditGl::deleteTexture(lookLutTex);
            lookLutTex = a.lut->upload();
            lookLutUp = a.lut;
        }
        const int levels = EditGl::levelsFor(w, h);
        look.ensure(w, h, GL_RGBA8, true, levels);
        Target& colour = grainOn ? lookTmp : look;
        if (grainOn) lookTmp.ensure(w, h, GL_RGBA8, false, 1);
        bind(colour.fbo, w, h);
        glUseProgram(pLook);
        texU(pLook, "uImg", 0, main.tex);
        tex3d(pLook, "uLut", 1, lookLutTex);
        glUniform1f(loc(pLook, "uLutStrength"), a.strength);
        glUniform1f(loc(pLook, "uLutSize"), float(a.lut->size));
        glUniform3f(loc(pLook, "uLutMin"), a.lut->domainMin[0], a.lut->domainMin[1], a.lut->domainMin[2]);
        glUniform3f(loc(pLook, "uLutScale"), 1.f / (a.lut->domainMax[0] - a.lut->domainMin[0]), 1.f / (a.lut->domainMax[1] - a.lut->domainMin[1]),
                    1.f / (a.lut->domainMax[2] - a.lut->domainMin[2]));
        draw();
        if (grainOn) {
            if (g->reference) {
                if (a.grainLut != grainLutUp) {
                    EditGl::deleteTexture(grainLutTex);
                    grainLutTex = a.grainLut->upload();
                    grainLutUp = a.grainLut;
                }
                noiseA.ensure(w, h, GL_RGBA16F, false, 1);
                noiseB.ensure(w, h, GL_RGBA16F, false, 1);
                bind(noiseA.fbo, w, h);
                glUseProgram(pNoise);
                glUniform1f(loc(pNoise, "seed"), g->seed);
                glUniform2i(loc(pNoise, "originU"), ox, oy);
                draw();
                filmBlur(noiseA, noiseB, 1.f, 0.f, g->blurPx);
                filmBlur(noiseB, noiseA, 0.f, 1.f, g->blurPx);
                bind(look.fbo, w, h);
                glUseProgram(pGrainSf);
                texU(pGrainSf, "InputBuffer", 0, lookTmp.tex);
                texU(pGrainSf, "Noise", 1, noiseA.tex);
                tex3d(pGrainSf, "GrainLut", 2, grainLutTex);
                glUniform1f(loc(pGrainSf, "scale"), g->scale);
                glUniform1f(loc(pGrainSf, "lutSize"), float(a.grainLut->size));
                draw();
            } else {
                bind(look.fbo, w, h);
                glUseProgram(pGrainBw);
                texU(pGrainBw, "InputBuffer", 0, lookTmp.tex);
                glUniform3f(loc(pGrainBw, "sigma"), g->sigma, g->sigma, g->sigma * 1.25f);
                glUniform3f(loc(pGrainBw, "cell"), g->cell, g->cell, g->cell * 1.4f);
                glUniform1f(loc(pGrainBw, "sharedPart"), g->sharedPart);
                glUniform1i(loc(pGrainBw, "slide"), g->slide ? 1 : 0);
                glUniform1i(loc(pGrainBw, "mono"), g->mono ? 1 : 0);
                glUniform1f(loc(pGrainBw, "seed"), g->seed);
                glUniform2f(loc(pGrainBw, "originU"), float(ox), float(oy));
                draw();
            }
        }
        glBindTexture(GL_TEXTURE_2D, look.tex);
        glGenerateMipmap(GL_TEXTURE_2D);
        outTex = look.tex;
    }
    GLenum momentFormat = GL_RGBA16F;
    Target raw, proxySrc;
    Target pm, pmTmp, pmBlur, ab, abTmp, abMid;
    Target lm, lmTmp, lmBlur, lab, labTmp, abLarge;
    Target nr, dev, blurTmp, blur, main;
    int largeW = 1, largeH = 1;
    GLuint cachedSrc = 0;
    std::string kNr, kDev, kBlur, kMain, kProxy;
    bool haveNr = false, haveDev = false, haveBlur = false, haveMain = false, haveProxy = false;
    std::vector<float> cachedLut, cachedPoints;

    static void put(std::string& k, float v) { k.append(reinterpret_cast<const char*>(&v), sizeof v); k += ','; }
    static void put(std::string& k, double v) { k.append(reinterpret_cast<const char*>(&v), sizeof v); k += ','; }

    static std::string devKey(const EditMath::Uniforms& u) {
        std::string b;
        b += char(u.linearIn);
        b += char(u.raw);
        for (float v : u.wbMul) put(b, v);
        for (float v : u.toWork) put(b, v);
        for (float v : u.gain) put(b, v);
        return b;
    }

    static std::string mainKey(const EditMath::Uniforms& u) {
        std::string b;
        for (float v : {u.sharpen, u.texture, u.clarity, u.shadows, u.highlights, u.dehaze, u.sat, u.vib}) put(b, v);
        b += char(u.scene); b += char(u.colourOn); b += char(u.mixerOn); b += char(u.mono); b += char(u.gradeOn);
        put(b, u.gradeBlend);
        put(b, u.gradeBalance);
        for (float v : u.hsl) put(b, v);
        for (float v : u.bw) put(b, v);
        for (float v : u.grade) put(b, v);
        b.append(reinterpret_cast<const char*>(u.lut.data()), u.lut.size() * 4);
        b += '|';
        b.append(reinterpret_cast<const char*>(u.points.data()), u.points.size() * 4);
        return b;
    }

    void guided(Target& mom, Target& tmp, Target& blurred, Target& coef, Target& coefTmp, Target& out, float sigma, float eps) {
        const int w = mom.w, h = mom.h;
        tmp.ensure(w, h, momentFormat, false, 1);
        blurred.ensure(w, h, momentFormat, false, 1);
        gauss4(mom, tmp, 1, 0, sigma);
        gauss4(tmp, blurred, 0, 1, sigma);
        coef.ensure(w, h, GL_RGBA16F, false, 1);
        bind(coef.fbo, w, h);
        glUseProgram(pGuided);
        texU(pGuided, "uIn", 0, blurred.tex);
        glUniform1f(loc(pGuided, "uEps"), eps);
        draw();
        coefTmp.ensure(w, h, GL_RGBA16F, false, 1);
        out.ensure(w, h, GL_RGBA16F, true, 1);
        gauss4(coef, coefTmp, 1, 0, sigma);
        gauss4(coefTmp, out, 0, 1, sigma);
    }

    void gauss4(Target& in, Target& out, float dx, float dy, float sigma) {
        bind(out.fbo, out.w, out.h);
        glUseProgram(pGauss4);
        texU(pGauss4, "uIn", 0, in.tex);
        glUniform2f(loc(pGauss4, "uTexel"), 1.f / float(in.w), 1.f / float(in.h));
        glUniform2f(loc(pGauss4, "uDir"), dx, dy);
        const auto taps = EditMath::gaussTaps(sigma);
        glUniform1f(loc(pGauss4, "uSigma"), jmax(sigma, 0.05f));
        glUniform1i(loc(pGauss4, "uTaps"), jint(taps[0]));
        glUniform1f(loc(pGauss4, "uStride"), jmax(1.f, float(jround(taps[1]))));
        draw();
    }

    void gauss2(Target& in, Target& out, float dx, float dy, const float* selA, const float* selB, float sA, float sB) {
        bind(out.fbo, out.w, out.h);
        glUseProgram(pGauss2);
        texU(pGauss2, "uIn", 0, in.tex);
        glUniform2f(loc(pGauss2, "uTexel"), 1.f / float(in.w), 1.f / float(in.h));
        glUniform2f(loc(pGauss2, "uDir"), dx, dy);
        glUniform4fv(loc(pGauss2, "uSelA"), 1, selA);
        glUniform4fv(loc(pGauss2, "uSelB"), 1, selB);
        glUniform1f(loc(pGauss2, "uSigA"), jmax(sA, 0.05f));
        glUniform1f(loc(pGauss2, "uSigB"), jmax(sB, 0.05f));
        const auto taps = EditMath::gaussTaps(jmax(sA, sB));
        glUniform1i(loc(pGauss2, "uTaps"), jint(taps[0]));
        glUniform1f(loc(pGauss2, "uStride"), taps[1]);
        draw();
    }

    void renderMain(int w, int h, const CropMath::Affine& texToProxy, const EditMath::Uniforms& u) {
        if (u.lut != cachedLut) {
            glBindTexture(GL_TEXTURE_2D, lutTex);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, EditMath::LUT_SIZE, 1, GL_RED, GL_FLOAT, u.lut.data());
            cachedLut = u.lut;
        }
        if (!u.points.empty() && u.points != cachedPoints) {
            glBindTexture(GL_TEXTURE_2D, pointsTex);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, EditMath::LUT_SIZE, 4, GL_RED, GL_FLOAT, u.points.data());
            cachedPoints = u.points;
        }
        main.ensure(w, h, GL_RGBA8, true, EditGl::levelsFor(w, h));
        bind(main.fbo, w, h);
        glUseProgram(pMain);
        texU(pMain, "uDev", 0, dev.tex);
        texU(pMain, "uBlur", 1, u.blurOn ? blur.tex : dev.tex);
        texU(pMain, "uMid", 2, abMid.tex);
        texU(pMain, "uLarge", 3, abLarge.tex);
        texU(pMain, "uLut", 4, lutTex);
        texU(pMain, "uPoints", 5, pointsTex);
        glUniform2f(loc(pMain, "uTexel"), 1.f / float(w), 1.f / float(h));
        const auto m = texToProxy.mat3();
        glUniformMatrix3fv(loc(pMain, "uTexToProxy"), 1, GL_FALSE, m.data());
        glUniform2f(loc(pMain, "uLargeScale"), largeScaleX(), largeScaleY());
        glUniform1i(loc(pMain, "uBlurOn"), u.blurOn ? 1 : 0);
        glUniform1f(loc(pMain, "uSharpen"), u.sharpen);
        glUniform1f(loc(pMain, "uTexture"), u.texture);
        glUniform1f(loc(pMain, "uClarity"), u.clarity);
        glUniform1f(loc(pMain, "uShadows"), u.shadows);
        glUniform1f(loc(pMain, "uHighlights"), u.highlights);
        glUniform1f(loc(pMain, "uDehaze"), u.dehaze);
        glUniform3f(loc(pMain, "uAir"), u.air[0], u.air[1], u.air[2]);
        glUniform1f(loc(pMain, "uSat"), u.sat);
        glUniform1f(loc(pMain, "uVib"), u.vib);
        glUniform1i(loc(pMain, "uScene"), u.scene ? 1 : 0);
        glUniform1i(loc(pMain, "uColourOn"), u.colourOn ? 1 : 0);
        glUniform1i(loc(pMain, "uMixerOn"), u.mixerOn ? 1 : 0);
        glUniform1i(loc(pMain, "uMono"), u.mono ? 1 : 0);
        glUniform1i(loc(pMain, "uGradeOn"), u.gradeOn ? 1 : 0);
        glUniform1i(loc(pMain, "uPointsOn"), !u.points.empty() ? 1 : 0);
        glUniform3fv(loc(pMain, "uHsl"), 8, u.hsl);
        glUniform1fv(loc(pMain, "uBw"), 8, u.bw);
        glUniform1fv(loc(pMain, "uBandHues"), 8, EditMath::bandHues().data());
        glUniform1f(loc(pMain, "uSkinHue"), u.skinHue);
        glUniform3fv(loc(pMain, "uGrade"), 4, u.grade);
        glUniform1f(loc(pMain, "uGradeBlend"), u.gradeBlend);
        glUniform1f(loc(pMain, "uGradeBalance"), u.gradeBalance);
        draw();
        glBindTexture(GL_TEXTURE_2D, main.tex);
        glGenerateMipmap(GL_TEXTURE_2D);
    }

    void developUniforms(GLuint p, const EditMath::Uniforms& u) {
        glUniform1i(loc(p, "uLinearIn"), u.linearIn ? 1 : 0);
        glUniform1i(loc(p, "uRaw"), u.raw ? 1 : 0);
        glUniform3f(loc(p, "uWbMul"), u.wbMul[0], u.wbMul[1], u.wbMul[2]);
        glUniformMatrix3fv(loc(p, "uToWork"), 1, GL_TRUE, u.toWork);
        glUniform3f(loc(p, "uGain"), u.gain[0], u.gain[1], u.gain[2]);
    }

    static void bind(GLuint fbo, int w, int h) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, w, h);
    }

    void draw() {
        glDisable(GL_BLEND);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    static GLint loc(GLuint p, const char* name) { return glGetUniformLocation(p, name); }

    static void texU(GLuint p, const char* name, int unit, GLuint tex) {
        glActiveTexture(GLenum(GL_TEXTURE0 + unit));
        glBindTexture(GL_TEXTURE_2D, tex);
        glUniform1i(loc(p, name), unit);
    }
};

} // namespace scam_edit
