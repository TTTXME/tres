// The post pipeline's GL layer, ported one to one from processing/opengl (GLFormat, GLTexture, GLProg, GLBlockDivider,
// GLDrawParams). The same C++ runs on Mesa (the host reference) and on the iPhone (ScamGL on Metal).
#pragma once

#include "GLShaderSource.hpp"

#include <GLES3/gl31.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace scam_post {

struct Point {
    int x = 0, y = 0;
    bool operator==(const Point& o) const { return x == o.x && y == o.y; }
};

/// GLFormat.DataType (id, bytes)
enum class DataType { NONE, FLOAT_16, FLOAT_32, FLOAT_64, SIGNED_8, SIGNED_16, SIGNED_32, SIGNED_64, UNSIGNED_8, UNSIGNED_16,
                      UNSIGNED_32, UNSIGNED_64, BOOLEAN, SIMPLE_8 };

inline int dataSize(DataType t) {
    switch (t) {
    case DataType::FLOAT_16: case DataType::SIGNED_16: case DataType::UNSIGNED_16: return 2;
    case DataType::FLOAT_32: case DataType::SIGNED_32: case DataType::UNSIGNED_32: return 4;
    case DataType::FLOAT_64: case DataType::SIGNED_64: case DataType::UNSIGNED_64: return 8;
    case DataType::NONE: return 0;
    default: return 1;
    }
}

struct GLDrawParams {
    static int& TileSize() { static int t = 256; return t; }
    static constexpr int WorkDim = 4;
};

struct GLFormat {
    DataType mFormat = DataType::NONE;
    int mChannels = 1;
    GLint filter = GL_NEAREST;
    GLint wrap = GL_CLAMP_TO_EDGE;

    GLFormat() = default;
    explicit GLFormat(DataType f) : mFormat(f), mChannels(1) {}
    GLFormat(DataType f, int channels) : mFormat(f), mChannels(channels) {
        if (f == DataType::FLOAT_16 || f == DataType::FLOAT_32 || f == DataType::FLOAT_64) filter = GL_LINEAR;
    }

    GLenum getGLFormatInternal() const {
        static const GLenum f16[] = {GL_R16F, GL_RG16F, GL_RGB16F, GL_RGBA16F}, f32[] = {GL_R32F, GL_RG32F, GL_RGB32F, GL_RGBA32F};
        static const GLenum u8[] = {GL_R8UI, GL_RG8UI, GL_RGB8UI, GL_RGBA8UI}, u16[] = {GL_R16UI, GL_RG16UI, GL_RGB16UI, GL_RGBA16UI};
        static const GLenum u32[] = {GL_R32UI, GL_RG32UI, GL_RGB32UI, GL_RGBA32UI}, s8[] = {GL_R8I, GL_RG8I, GL_RGB8I, GL_RGBA8I};
        static const GLenum n8[] = {GL_R8, GL_RG8, GL_RGB8, GL_RGBA8}, s16[] = {GL_R16I, GL_RG16I, GL_RGB16I, GL_RGBA16I};
        static const GLenum s32[] = {GL_R32I, GL_RG32I, GL_RGB32I, GL_RGBA32I};
        const int c = mChannels - 1;
        if (c < 0 || c > 3) return 0;
        switch (mFormat) {
        case DataType::FLOAT_16: return f16[c];
        case DataType::FLOAT_32: return f32[c];
        case DataType::UNSIGNED_8: return u8[c];
        case DataType::UNSIGNED_16: return u16[c];
        case DataType::UNSIGNED_32: return u32[c];
        case DataType::SIGNED_8: return s8[c];
        case DataType::SIMPLE_8: return n8[c];
        case DataType::SIGNED_16: return s16[c];
        case DataType::SIGNED_32: return s32[c];
        default: return 0;
        }
    }

    GLenum getGLFormatExternal() const {
        static const GLenum plain[] = {GL_RED, GL_RG, GL_RGB, GL_RGBA}, integer[] = {GL_RED_INTEGER, GL_RG_INTEGER, GL_RGB_INTEGER, GL_RGBA_INTEGER};
        const int c = mChannels - 1;
        if (c < 0 || c > 3) return 0;
        switch (mFormat) {
        case DataType::FLOAT_16: case DataType::FLOAT_32: case DataType::FLOAT_64: case DataType::SIGNED_8: case DataType::SIMPLE_8:
        case DataType::UNSIGNED_8: return plain[c];
        case DataType::UNSIGNED_16: case DataType::UNSIGNED_32: case DataType::UNSIGNED_64: case DataType::SIGNED_16:
        case DataType::SIGNED_32: case DataType::SIGNED_64: return integer[c];
        default: return 0;
        }
    }

    GLenum getGLType() const {
        switch (mFormat) {
        case DataType::FLOAT_32: case DataType::FLOAT_64: case DataType::FLOAT_16: return GL_FLOAT;
        case DataType::UNSIGNED_8: case DataType::SIMPLE_8: return GL_UNSIGNED_BYTE;
        case DataType::UNSIGNED_16: return GL_UNSIGNED_SHORT;
        case DataType::UNSIGNED_32: return GL_UNSIGNED_INT;
        case DataType::SIGNED_8: return GL_BYTE;
        case DataType::SIGNED_16: return GL_SHORT;
        case DataType::SIGNED_32: return GL_INT;
        default: return 0;
        }
    }

    /// Bytes a pixel takes in client memory for getGLFormatExternal / getGLType (FLOAT_16 transfers as GL_FLOAT).
    int clientBytes() const { return mChannels * (mFormat == DataType::FLOAT_16 ? 4 : dataSize(mFormat)); }
};

inline void checkGl(const char* where) {
    const GLenum e = glGetError();
    if (e != GL_NO_ERROR) throw std::runtime_error(std::string("GL error ") + std::to_string(e) + " at " + where);
}

class GLTexture {
public:
    Point mSize;
    GLFormat mFormat;
    GLuint mTextureID = 0;
    GLuint mBuffer = 0;

    GLTexture(Point size, const GLFormat& format, const void* pixels = nullptr, GLint filter = -1, GLint wrap = -1)
        : mSize(size), mFormat(format) {
        if (filter >= 0) mFormat.filter = filter;
        if (wrap >= 0) mFormat.wrap = wrap;
        glGenTextures(1, &mTextureID);
        GLint previous = 0;
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
        glTexStorage2D(GL_TEXTURE_2D, 1, mFormat.getGLFormatInternal(), mSize.x, mSize.y);
        if (pixels) glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, mSize.x, mSize.y, mFormat.getGLFormatExternal(), mFormat.getGLType(), pixels);
        reSetParameters();
        glBindTexture(GL_TEXTURE_2D, GLuint(previous));
        checkGl("GLTexture");
    }
    GLTexture(int w, int h, const GLFormat& format, const void* pixels = nullptr, GLint filter = -1, GLint wrap = -1)
        : GLTexture(Point{w, h}, format, pixels, filter, wrap) {}
    GLTexture(const GLTexture&) = delete;
    GLTexture& operator=(const GLTexture&) = delete;
    ~GLTexture() { close(); }

    void reSetParameters() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, mFormat.filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mFormat.filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, mFormat.wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, mFormat.wrap);
    }

    void setFilter(GLint filter) {
        mFormat.filter = filter;
        GLint previous = 0;
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
        reSetParameters();
        glBindTexture(GL_TEXTURE_2D, GLuint(previous));
    }

    void loadData(const void* pixels) {
        GLint previous = 0;
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, mSize.x, mSize.y, mFormat.getGLFormatExternal(), mFormat.getGLType(), pixels);
        glBindTexture(GL_TEXTURE_2D, GLuint(previous));
    }

    void BufferLoad() {
        if (!mBuffer) glGenFramebuffers(1, &mBuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, mBuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mTextureID, 0);
        glViewport(0, 0, mSize.x, mSize.y);
    }

    void bind(GLenum slot) {
        glActiveTexture(slot);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
    }

    /// glReadPixels of the whole texture in `out` (getGLFormatExternal / getGLType of outputFormat).
    std::vector<uint8_t> textureBuffer(const GLFormat& outputFormat) {
        BufferLoad();
        std::vector<uint8_t> buffer(size_t(mSize.x) * size_t(mSize.y) * size_t(outputFormat.clientBytes()));
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, mSize.x, mSize.y, outputFormat.getGLFormatExternal(), outputFormat.getGLType(), buffer.data());
        return buffer;
    }

    void close() {
        if (mTextureID) glDeleteTextures(1, &mTextureID);
        if (mBuffer) glDeleteFramebuffers(1, &mBuffer);
        mTextureID = mBuffer = 0;
    }
};

using Texture = std::shared_ptr<GLTexture>;
inline Texture makeTexture(Point size, const GLFormat& format, const void* pixels = nullptr, GLint filter = -1, GLint wrap = -1) {
    return std::make_shared<GLTexture>(size, format, pixels, filter, wrap);
}

/// GLProg: fragment and compute programs of the post assets, their defines, textures on units 0, 2, 4, ..., uniforms and
/// the drawing in rows of GLDrawParams.TileSize.
class GLProg {
public:
    explicit GLProg(std::string shadersRoot) : root_(std::move(shadersRoot)) {
        vertexShader_ = compile(GL_VERTEX_SHADER, vertexSource());
    }
    ~GLProg() { close(); }

    bool closed = true;
    bool isCompute = false;
    GLuint mCurrentProgramActive = 0;

    void setDefine(const std::string& name, const std::string& value) { defines_.emplace_back(name, value); }
    void setDefine(const std::string& name, bool b) { if (b) setDefine(name, std::string("1")); }
    void setDefine(const std::string& name, int v) { setDefine(name, std::to_string(v)); }
    void setDefine(const std::string& name, std::initializer_list<float> vars) {
        // GLProg.setDefine(String, float...): "vecN(a,b,..)" or the value for one
        std::string v;
        if (vars.size() == 1) v = javaFloat(*vars.begin());
        else {
            v = "vec" + std::to_string(vars.size()) + "(";
            bool first = true;
            for (float f : vars) { v += (first ? "" : ",") + javaFloat(f); first = false; }
            v += ")";
        }
        setDefine(name, v);
    }

    void useAssetProgram(const std::string& name, bool compute = false) {
        const std::string shader = assetProgram(name, defines_.empty() ? nullptr : &defines_, root_);
        useShader(shader, compute);
    }
    void useProgram(const std::string& source, bool compute = false) {
        useShader(readProgram(source, defines_.empty() ? nullptr : &defines_, root_), compute);
    }

    void setTexture(const std::string& var, const GLTexture& tex) {
        int unit;
        auto it = textureBinds_.find(var);
        if (it != textureBinds_.end()) unit = it->second;
        else { unit = newTextureId_; textureBinds_[var] = unit; newTextureId_ += 2; }
        setVar(var, unit);
        const_cast<GLTexture&>(tex).bind(GL_TEXTURE0 + GLenum(unit));
    }
    void setTexture(const std::string& var, const Texture& tex) { setTexture(var, *tex); }

    void setVar(const std::string& name, int a) { glUniform1i(loc(name), a); }
    void setVar(const std::string& name, int a, int b) { glUniform2i(loc(name), a, b); }
    void setVar(const std::string& name, int a, int b, int c) { glUniform3i(loc(name), a, b, c); }
    void setVar(const std::string& name, int a, int b, int c, int d) { glUniform4i(loc(name), a, b, c, d); }
    void setVar(const std::string& name, float a) { glUniform1f(loc(name), a); }
    void setVar(const std::string& name, float a, float b) { glUniform2f(loc(name), a, b); }
    void setVar(const std::string& name, float a, float b, float c) { glUniform3f(loc(name), a, b, c); }
    void setVar(const std::string& name, float a, float b, float c, float d) { glUniform4f(loc(name), a, b, c, d); }
    void setVar(const std::string& name, Point p) { glUniform2i(loc(name), p.x, p.y); }
    /// setVar(String, boolean transpose, float...) for mat3 (9) / mat4 (16)
    void setVar(const std::string& name, bool transpose, const std::vector<float>& m) {
        if (m.size() == 9) glUniformMatrix3fv(loc(name), 1, transpose ? GL_TRUE : GL_FALSE, m.data());
        else if (m.size() == 16) glUniformMatrix4fv(loc(name), 1, transpose ? GL_TRUE : GL_FALSE, m.data());
    }
    void setVarFloats(const std::string& name, const std::vector<float>& v) { glUniform1fv(loc(name), GLsizei(v.size()), v.data()); }

    void draw() { glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); }

    void drawBlocks(GLTexture& target) {
        target.BufferLoad();
        drawBlocks(target.mSize.x, target.mSize.y);
    }
    void drawBlocks(const Texture& target) { drawBlocks(*target); }

    /// GLBlockDivider rows of TileSize, a viewport and a draw each (and a flush, as the post's submissions)
    void drawBlocks(int w, int h) {
        const int block = GLDrawParams::TileSize();
        for (int passed = 0; passed < h;) {
            const int rows = std::min(h - passed, block);
            glViewport(0, passed, w, rows);
            draw();
            glFlush();
            passed += rows;
        }
    }

    void computeManual(int x, int y, int z) { glDispatchCompute(GLuint(x), GLuint(y), GLuint(z)); }

    void close() {
        for (auto& kv : cache_) glDeleteProgram(kv.second);
        cache_.clear();
        if (vertexShader_) glDeleteShader(vertexShader_);
        vertexShader_ = 0;
    }

    static std::string javaFloat(float f) {
        // Java's Float.toString for the define values the post nodes pass (finite, as "%s")
        char buf[48];
        if (f == float(int64_t(f)) && std::abs(f) < 1e7f) std::snprintf(buf, sizeof buf, "%.1f", double(f));
        else std::snprintf(buf, sizeof buf, "%.9g", double(f));
        return buf;
    }

private:
    std::string root_;
    GLuint vertexShader_ = 0;
    Defines defines_;
    std::unordered_map<std::string, GLuint> cache_;
    std::map<std::string, int> textureBinds_;
    int newTextureId_ = 0;

    GLint loc(const std::string& name) const { return glGetUniformLocation(mCurrentProgramActive, name.c_str()); }

    static GLuint compile(GLenum type, const std::string& src) {
        GLuint s = glCreateShader(type);
        const char* p = src.c_str();
        glShaderSource(s, 1, &p, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[2048] = {};
            glGetShaderInfoLog(s, sizeof log, nullptr, log);
            throw std::runtime_error(std::string("shader compile: ") + log + "\n" + src.substr(0, 400));
        }
        return s;
    }

    void useShader(const std::string& shader, bool compute) {
        isCompute = compute;
        closed = false;
        defines_.clear();
        auto it = cache_.find(shader);
        GLuint program = 0;
        if (it != cache_.end()) program = it->second;
        else {
            program = glCreateProgram();
            GLuint s = compile(compute ? GL_COMPUTE_SHADER : GL_FRAGMENT_SHADER, shader);
            if (!compute) glAttachShader(program, vertexShader_);
            glAttachShader(program, s);
            glLinkProgram(program);
            GLint linked = 0;
            glGetProgramiv(program, GL_LINK_STATUS, &linked);
            if (!linked) {
                char log[2048] = {};
                glGetProgramInfoLog(program, sizeof log, nullptr, log);
                throw std::runtime_error(std::string("program link: ") + log);
            }
            glDeleteShader(s);
            cache_[shader] = program;
        }
        glUseProgram(program);
        mCurrentProgramActive = program;
        textureBinds_.clear();
        newTextureId_ = 0;
    }
};

} // namespace scam_post
