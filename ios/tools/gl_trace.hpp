// GL pass trace for the merge replays (hybrid_replay built with -DSCAM_GL_TRACE): after every compute dispatch and draw, the
// float targets it wrote are read back and their mean, sum and non-finite count printed to stderr ("GLTRACE <n> ..."), so two
// GL backends (Mesa, ScamGL on Metal) running the same burst can be compared pass by pass. Included before the merge's headers:
// the GL entry points the merge calls go through the wrappers below.
#pragma once

#include <GLES3/gl31.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <vector>

namespace gltrace {

struct Tex { int w = 0, h = 0; GLenum fmt = 0; };
inline std::map<GLuint, Tex>& textures() { static std::map<GLuint, Tex> m; return m; }
inline std::map<GLuint, std::map<GLenum, GLuint>>& fboAttachments() { static std::map<GLuint, std::map<GLenum, GLuint>> m; return m; }
inline std::map<GLuint, int>& programOrder() { static std::map<GLuint, int> m; return m; }
struct Image { GLuint tex = 0; GLenum access = 0; };
inline std::map<GLuint, Image>& images() { static std::map<GLuint, Image> m; return m; }
inline GLuint& boundTexture() { static GLuint t = 0; return t; }
inline GLuint& boundFbo() { static GLuint f = 0; return f; }
inline GLuint& readFbo() { static GLuint f = 0; return f; }
inline GLuint& currentProgram() { static GLuint p = 0; return p; }
inline long& passes() { static long n = 0; return n; }
inline bool enabled() { static const bool on = std::getenv("SCAM_GL_TRACE") != nullptr; return on; }

inline bool floatFormat(GLenum f) {
    switch (f) {
    case GL_R16F: case GL_RG16F: case GL_RGBA16F: case GL_R32F: case GL_RG32F: case GL_RGBA32F: case GL_RGBA8: case GL_RG8: case GL_R8:
        return true;
    default:
        return false;
    }
}

inline void dump(const char* kind, GLuint tex) {
    const auto it = textures().find(tex);
    if (it == textures().end() || !floatFormat(it->second.fmt) || it->second.w <= 0 || it->second.h <= 0) return;
    const Tex t = it->second;
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    glReadBuffer(GL_COLOR_ATTACHMENT0);
    std::vector<float> px(size_t(t.w) * t.h * 4);
    if (t.fmt == GL_RGBA8 || t.fmt == GL_RG8 || t.fmt == GL_R8) {  // normalised 8-bit: GLES reads these only as bytes
        std::vector<unsigned char> b(px.size());
        glReadPixels(0, 0, t.w, t.h, GL_RGBA, GL_UNSIGNED_BYTE, b.data());
        for (size_t i = 0; i < b.size(); ++i) px[i] = b[i] / 255.f;
    } else {
        glReadPixels(0, 0, t.w, t.h, GL_RGBA, GL_FLOAT, px.data());
    }
    glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo());
    glDeleteFramebuffers(1, &fbo);
    double sum[4] = {0, 0, 0, 0};
    long bad = 0;
    for (size_t i = 0; i < px.size(); ++i) {
        if (!std::isfinite(px[i])) { ++bad; continue; }
        sum[i & 3] += px[i];
    }
    const double n = double(t.w) * t.h;
    std::fprintf(stderr, "GLTRACE %ld %s prog#%d %dx%d fmt=0x%x mean=%.7g,%.7g,%.7g,%.7g nonfinite=%ld\n", passes(), kind,
                 programOrder().count(currentProgram()) ? programOrder()[currentProgram()] : -1, t.w, t.h, t.fmt, sum[0] / n,
                 sum[1] / n, sum[2] / n, sum[3] / n, bad);
}

inline GLuint createProgram() {
    const GLuint p = glCreateProgram();
    const int n = int(programOrder().size());
    programOrder()[p] = n;
    return p;
}
inline void useProgram(GLuint p) { currentProgram() = p; glUseProgram(p); }
inline void bindTexture(GLenum target, GLuint t) { if (target == GL_TEXTURE_2D) boundTexture() = t; glBindTexture(target, t); }
inline void texStorage2D(GLenum target, GLsizei levels, GLenum fmt, GLsizei w, GLsizei h) {
    if (target == GL_TEXTURE_2D) textures()[boundTexture()] = Tex{int(w), int(h), fmt};
    glTexStorage2D(target, levels, fmt, w, h);
}
inline void bindFramebuffer(GLenum target, GLuint f) {
    if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) boundFbo() = f;
    if (target == GL_FRAMEBUFFER || target == GL_READ_FRAMEBUFFER) readFbo() = f;
    glBindFramebuffer(target, f);
}
inline void framebufferTexture2D(GLenum target, GLenum att, GLenum tt, GLuint tex, GLint level) {
    if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) fboAttachments()[boundFbo()][att] = tex;
    glFramebufferTexture2D(target, att, tt, tex, level);
}
inline void bindImageTexture(GLuint unit, GLuint tex, GLint level, GLboolean layered, GLint layer, GLenum access, GLenum fmt) {
    images()[unit] = Image{tex, access};
    glBindImageTexture(unit, tex, level, layered, layer, access, fmt);
}
inline void dispatchCompute(GLuint x, GLuint y, GLuint z) {
    glDispatchCompute(x, y, z);
    if (!enabled()) return;
    ++passes();
    glMemoryBarrier(GL_ALL_BARRIER_BITS);
    for (const auto& [unit, img] : images())
        if (img.tex && img.access != GL_READ_ONLY) dump("compute", img.tex);
}
inline void drawArrays(GLenum mode, GLint first, GLsizei count) {
    glDrawArrays(mode, first, count);
    if (!enabled()) return;
    ++passes();
    const auto it = fboAttachments().find(boundFbo());
    if (it == fboAttachments().end()) return;
    const std::map<GLenum, GLuint> atts = it->second;
    for (const auto& [att, tex] : atts) dump("draw", tex);
}

} // namespace gltrace

#define glCreateProgram gltrace::createProgram
#define glUseProgram gltrace::useProgram
#define glBindTexture gltrace::bindTexture
#define glTexStorage2D gltrace::texStorage2D
#define glBindFramebuffer gltrace::bindFramebuffer
#define glFramebufferTexture2D gltrace::framebufferTexture2D
#define glBindImageTexture gltrace::bindImageTexture
#define glDispatchCompute gltrace::dispatchCompute
#define glDrawArrays gltrace::drawArrays
