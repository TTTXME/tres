// The post pipeline's GL layer, ported one to one from processing/opengl (GLFormat, GLTexture, GLBuffer, GLProg with its
// layouts and defines, GLBlockDivider, GLDrawParams). The same C++ runs on Mesa (the host reference) and on the iPhone (ScamGL
// on Metal). Speed-only parts of the Java (program binaries, the texture pool, GPU timers, pacing) are left out: they never
// change a pixel.
#pragma once

#include "GLShaderSource.hpp"

#include <GLES3/gl31.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace scam_post {

struct Point {
    int x = 0, y = 0;
    bool operator==(const Point& o) const { return x == o.x && y == o.y; }
    bool operator!=(const Point& o) const { return !(*this == o); }
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

/// PostGlMode: the new GL model (legacy false), and its scam_dev switches set per run by PostPipeline.Run.
struct PostGlMode {
    static bool& invalidateFlag() { static bool v = true; return v; }
    static bool invalidate() { return invalidateFlag(); }
    static bool legacy() { return false; }
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
    /// Java's copy constructor: the format and channels only (filter and wrap back to NEAREST / CLAMP_TO_EDGE)
    static GLFormat copyOf(const GLFormat& in) { GLFormat f; f.mFormat = in.mFormat; f.mChannels = in.mChannels; return f; }

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
    if (e != GL_NO_ERROR) throw std::runtime_error(std::string("GL error 0x") + [&] {
        char b[16]; std::snprintf(b, sizeof b, "%x", e); return std::string(b); }() + " at " + where);
}

class GLTexture;
/// GLTexture.closeAll: every texture not closed by its owner goes at the end of the run.
inline std::set<GLTexture*>& openTextures() { static std::set<GLTexture*> s; return s; }

class GLTexture {
public:
    Point mSize;
    GLFormat mFormat;
    GLuint mTextureID = 0;
    GLuint mBuffer = 0;

    /// GLTexture(Point, GLFormat, Buffer, filter, wrap): filter / wrap -1 = the format's own (GLTexture(Point, GLFormat)).
    GLTexture(Point size, const GLFormat& format, const void* pixels = nullptr, GLint filter = -1, GLint wrap = -1)
        : mSize(size), mFormat(GLFormat::copyOf(format)) {
        mFormat.filter = filter >= 0 ? filter : format.filter;
        mFormat.wrap = wrap >= 0 ? wrap : format.wrap;
        glGenTextures(1, &mTextureID);
        GLint previous = 0;
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
        glTexStorage2D(GL_TEXTURE_2D, 1, mFormat.getGLFormatInternal(), mSize.x, mSize.y);
        checkGl("glTexStorage2D");
        if (pixels) glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, mSize.x, mSize.y, mFormat.getGLFormatExternal(), mFormat.getGLType(), pixels);
        checkGl("glTexSubImage2D");
        reSetParameters();
        glBindTexture(GL_TEXTURE_2D, GLuint(previous));
        openTextures().insert(this);
    }
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

    bool mainFormat() const { return mFormat.mFormat == DataType::FLOAT_16 && mFormat.mChannels == GLDrawParams::WorkDim; }

    void loadData(const void* pixels) {
        GLint previous = 0;
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, mSize.x, mSize.y, mFormat.getGLFormatExternal(), mFormat.getGLType(), pixels);
        glBindTexture(GL_TEXTURE_2D, GLuint(previous));
    }

    void Bufferize() { if (!mBuffer) glGenFramebuffers(1, &mBuffer); }
    void BindBuffer() {
        glBindFramebuffer(GL_FRAMEBUFFER, mBuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mTextureID, 0);
    }
    void BufferLoad() {
        Bufferize();
        glBindFramebuffer(GL_FRAMEBUFFER, mBuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mTextureID, 0);
        glViewport(0, 0, mSize.x, mSize.y);
        checkGl("Tex BufferLoad");
    }

    void bind(GLenum slot) {
        glActiveTexture(slot);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
    }

    /// glReadPixels of the whole texture from the bound framebuffer (the caller bound this texture's, as Java's textureBuffer).
    void textureBuffer(const GLFormat& outputFormat, void* out) {
        glReadPixels(0, 0, mSize.x, mSize.y, outputFormat.getGLFormatExternal(), outputFormat.getGLType(), out);
    }
    /// Java's textureBuffer(GLFormat): the bytes of a fresh buffer; the framebuffer is this texture's (BufferLoad).
    std::vector<uint8_t> textureBuffer(const GLFormat& outputFormat) {
        BufferLoad();
        std::vector<uint8_t> buffer(size_t(mSize.x) * size_t(mSize.y) * size_t(outputFormat.clientBytes()));
        glReadPixels(0, 0, mSize.x, mSize.y, outputFormat.getGLFormatExternal(), outputFormat.getGLType(), buffer.data());
        return buffer;
    }

    void close() {
        if (mTextureID) glDeleteTextures(1, &mTextureID);
        if (mBuffer) glDeleteFramebuffers(1, &mBuffer);
        mTextureID = mBuffer = 0;
        openTextures().erase(this);
    }
};

using Texture = std::shared_ptr<GLTexture>;
inline Texture makeTexture(Point size, const GLFormat& format, const void* pixels = nullptr, GLint filter = -1, GLint wrap = -1) {
    return std::make_shared<GLTexture>(size, format, pixels, filter, wrap);
}

/// GLBuffer: a shader storage buffer of `size` elements, its client copy zeroed.
class GLBuffer {
public:
    GLuint mBufferID = 0;
    int size = 0, byteSize = 0;
    GLenum mode = GL_STATIC_DRAW;
    GLFormat mFormat;
    std::vector<uint8_t> byteBuffer;

    GLBuffer(int size, const GLFormat& format, GLenum mode = GL_STATIC_DRAW)
        : size(size), byteSize(size * dataSize(format.mFormat)), mode(mode), mFormat(GLFormat::copyOf(format)), byteBuffer(size_t(byteSize)) {
        glGenBuffers(1, &mBufferID);
        Bind();
        Buffering();
    }
    GLBuffer(const GLBuffer&) = delete;
    GLBuffer& operator=(const GLBuffer&) = delete;
    ~GLBuffer() { close(); }

    void Bind() { glBindBuffer(GL_SHADER_STORAGE_BUFFER, mBufferID); }
    void UnBind() { glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0); }
    void BindBase(GLuint number, GLenum type) { Bind(); glBindBufferBase(type, number, mBufferID); }
    void Buffering() { glBufferData(GL_SHADER_STORAGE_BUFFER, byteSize, byteBuffer.data(), mode); }
    std::vector<int32_t> readBufferIntegers(bool clear) {
        std::vector<int32_t> value;
        Bind();
        void* p = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, byteSize, GL_MAP_READ_BIT);
        if (p) {
            value.resize(size_t(byteSize) / 4);
            std::memcpy(value.data(), p, value.size() * 4);
        }
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
        if (clear) Buffering();
        UnBind();
        return value;
    }
    void close() {
        if (mBufferID) glDeleteBuffers(1, &mBufferID);
        mBufferID = 0;
    }
};

/// GLComputeLayout: a compute program's local size ("in") or a block's / image's binding, parsed from the source.
struct GLComputeLayout {
    int x = 0, y = 0, z = 0, binding = 0;
};

/// GLInterface.getLayouts
inline std::map<std::string, GLComputeLayout> getLayouts(const std::string& program) {
    std::map<std::string, GLComputeLayout> layouts;
    std::istringstream in(program);
    std::string val;
    auto param = [](const std::vector<std::string>& params, const std::string& name) {
        for (std::string p : params) {
            p.erase(std::remove(p.begin(), p.end(), ' '), p.end());
            const size_t eq = p.find('=');
            if (eq != std::string::npos && p.substr(0, eq) == name) return std::stoi(p.substr(eq + 1));
            if (eq == std::string::npos && p == name) return 0;
        }
        return 0;
    };
    while (std::getline(in, val)) {
        const size_t comment = val.find("//");
        if (comment != std::string::npos) val = val.substr(0, comment);
        if (val.find("layout") == std::string::npos) continue;
        const size_t brace = val.find('{');
        if (brace != std::string::npos) val = val.substr(0, brace);
        // the last whitespace-separated word, without ';'
        std::istringstream words(val);
        std::string w, last;
        while (words >> w) last = w;
        last.erase(std::remove(last.begin(), last.end(), ';'), last.end());
        const size_t l = val.find('('), r = val.rfind(')');
        const size_t left = l == std::string::npos ? 0 : l, right = r == std::string::npos ? val.size() - 1 : r;
        std::vector<std::string> params;
        std::string inner = right > left ? val.substr(left + 1, right - left - 1) : "";
        std::istringstream ps(inner);
        std::string item;
        while (std::getline(ps, item, ',')) params.push_back(item);
        GLComputeLayout layout;
        if (last == "in") {
            layout.x = param(params, "local_size_x");
            layout.y = param(params, "local_size_y");
            layout.z = param(params, "local_size_z");
        } else {
            layout.binding = param(params, "binding");
        }
        layouts[last] = layout;
    }
    return layouts;
}

/// Java's Float.toString / Arrays.toString of float[] for the define values.
inline std::string javaFloatString(float f) {
    if (std::isnan(f)) return "NaN";
    if (std::isinf(f)) return f > 0 ? "Infinity" : "-Infinity";
    if (f == 0) return std::signbit(f) ? "-0.0" : "0.0";
    const double a = std::fabs(double(f));
    char buf[64];
    int digits = 1;
    for (; digits <= 9; ++digits) {
        std::snprintf(buf, sizeof buf, "%.*e", digits - 1, double(f));
        if (std::strtof(buf, nullptr) == f) break;
    }
    // the shortest digits as a plain decimal in [1e-3, 1e7), else d.ddddE±n
    std::snprintf(buf, sizeof buf, "%.*e", digits - 1, double(f));
    std::string s = buf;
    const size_t e = s.find('e');
    std::string mant = s.substr(0, e);
    const int exp = std::stoi(s.substr(e + 1));
    std::string sign;
    if (mant[0] == '-') { sign = "-"; mant = mant.substr(1); }
    std::string digitsStr;
    for (char c : mant) if (c != '.') digitsStr += c;
    if (a >= 1e-3 && a < 1e7) {
        std::string out;
        if (exp >= 0) {
            std::string intPart = digitsStr.substr(0, std::min<size_t>(digitsStr.size(), size_t(exp) + 1));
            while (intPart.size() < size_t(exp) + 1) intPart += '0';
            std::string frac = digitsStr.size() > size_t(exp) + 1 ? digitsStr.substr(size_t(exp) + 1) : "0";
            out = intPart + "." + frac;
        } else {
            out = "0." + std::string(size_t(-exp - 1), '0') + digitsStr;
        }
        return sign + out;
    }
    std::string frac = digitsStr.size() > 1 ? digitsStr.substr(1) : "0";
    return sign + digitsStr.substr(0, 1) + "." + frac + "E" + std::to_string(exp);
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
    const std::string& shadersRoot() const { return root_; }

    void setDefine(const std::string& name, const std::string& value) { defines_.emplace_back(name, value); }
    void setDefine(const std::string& name, const char* value) { setDefine(name, std::string(value)); }
    void setDefine(const std::string& name, bool b) { setDefine(name, std::string(b ? "1" : "0")); }
    /// setDefine(String, int...): Arrays.toString without the brackets ("1, 2")
    void setDefine(const std::string& name, int v) { setDefine(name, std::to_string(v)); }
    void setDefineInts(const std::string& name, const std::vector<int>& v) {
        std::string s;
        for (size_t i = 0; i < v.size(); ++i) s += (i ? ", " : "") + std::to_string(v[i]);
        setDefine(name, s);
    }
    /// setDefine(String, float...): Arrays.toString of the floats without the brackets ("1.0, 0.5")
    void setDefineFloats(const std::string& name, const std::vector<float>& v) {
        std::string s;
        for (size_t i = 0; i < v.size(); ++i) s += (i ? ", " : "") + javaFloatString(v[i]);
        setDefine(name, s);
    }
    void setDefine(const std::string& name, float v) { setDefineFloats(name, {v}); }
    void setDefine(const std::string& name, Point p) { setDefineInts(name, {p.x, p.y}); }
    void setLayout(int x, int y, int z) {
        setDefine("LAYOUT", "layout(local_size_x = " + std::to_string(x) + ", local_size_y = " + std::to_string(y)
                  + ", local_size_z = " + std::to_string(z) + ") in;");
    }

    void useAssetProgram(const std::string& name, bool compute = false) {
        const std::string shader = assetProgram(name, defines_.empty() ? nullptr : &defines_, root_);
        useShader(shader, compute);
    }
    void useUtilProgram(const std::string& name, bool compute = false) { useAssetProgram("utils/" + name, compute); }
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

    void setBufferCompute(const std::string& var, GLBuffer& buff, GLenum type = GL_SHADER_STORAGE_BUFFER) {
        auto it = layouts_.find(var);
        if (it == layouts_.end()) throw std::runtime_error("no buffer block " + var + " in the program's layouts");
        buff.BindBase(GLuint(it->second.binding), type);
    }
    void setTextureCompute(const std::string& var, const GLTexture& tex, bool write) {
        setTextureCompute(var, tex, write ? GLenum(GL_WRITE_ONLY) : GLenum(GL_READ_ONLY));
    }
    void setTextureCompute(const std::string& var, const GLTexture& tex, GLenum access) {
        auto it = layouts_.find(var);
        if (it == layouts_.end()) throw std::runtime_error("no image " + var + " in the program's layouts");
        glBindImageTexture(GLuint(it->second.binding), tex.mTextureID, 0, GL_FALSE, 0, access, tex.mFormat.getGLFormatInternal());
        checkGl("glBindImageTexture");
    }
    void setTextureCompute(const std::string& var, const Texture& tex, bool write) { setTextureCompute(var, *tex, write); }

    void setVar(const std::string& name, int a) { glUniform1i(loc(name), a); }
    void setVar(const std::string& name, int a, int b) { glUniform2i(loc(name), a, b); }
    void setVar(const std::string& name, int a, int b, int c) { glUniform3i(loc(name), a, b, c); }
    void setVar(const std::string& name, int a, int b, int c, int d) { glUniform4i(loc(name), a, b, c, d); }
    void setVar(const std::string& name, float a) { glUniform1f(loc(name), a); }
    void setVar(const std::string& name, float a, float b) { glUniform2f(loc(name), a, b); }
    void setVar(const std::string& name, float a, float b, float c) { glUniform3f(loc(name), a, b, c); }
    void setVar(const std::string& name, float a, float b, float c, float d) { glUniform4f(loc(name), a, b, c, d); }
    void setVar(const std::string& name, Point p) { glUniform2i(loc(name), p.x, p.y); }
    /// setVar(String, float...): 1 - 4 floats or a mat3 (9, transposed as Java's default)
    void setVar(const std::string& name, const std::vector<float>& v) { setVar(name, true, v); }
    void setVar(const std::string& name, bool transpose, const std::vector<float>& v) {
        const GLint a = loc(name);
        switch (v.size()) {
        case 1: glUniform1f(a, v[0]); break;
        case 2: glUniform2f(a, v[0], v[1]); break;
        case 3: glUniform3f(a, v[0], v[1], v[2]); break;
        case 4: glUniform4f(a, v[0], v[1], v[2], v[3]); break;
        case 9: glUniformMatrix3fv(a, 1, transpose ? GL_TRUE : GL_FALSE, v.data()); break;
        default: throw std::runtime_error("Wrong var size " + name);
        }
    }
    void setVarInts(const std::string& name, const std::vector<int>& v) {
        const GLint a = loc(name);
        switch (v.size()) {
        case 1: glUniform1i(a, v[0]); break;
        case 2: glUniform2i(a, v[0], v[1]); break;
        case 3: glUniform3i(a, v[0], v[1], v[2]); break;
        case 4: glUniform4i(a, v[0], v[1], v[2], v[3]); break;
        default: throw std::runtime_error("Wrong var size " + name);
        }
    }
    void setVarFloats(const std::string& name, const std::vector<float>& v) { glUniform1fv(loc(name), GLsizei(v.size()), v.data()); }
    void setVarVec4s(const std::string& name, const std::vector<float>& v) { glUniform4fv(loc(name), GLsizei(v.size() / 4), v.data()); }
    void setVarU(const std::string& name, unsigned a) { glUniform1ui(loc(name), a); }
    void setVarU(const std::string& name, unsigned a, unsigned b) { glUniform2ui(loc(name), a, b); }

    void draw() { glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); }

    void drawBlocks(GLTexture& target) {
        target.BufferLoad();
        if (PostGlMode::invalidate()) {
            const GLenum colour0 = GL_COLOR_ATTACHMENT0;
            glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &colour0);
        }
        drawBlocks(target.mSize.x, target.mSize.y);
    }
    void drawBlocks(const Texture& target) { drawBlocks(*target); }
    void drawBlocks(GLTexture& target, Point size) { target.BufferLoad(); drawBlocks(size.x, size.y); }

    /// GLBlockDivider rows of TileSize, a viewport and a draw each, a flush after each (the post's submissions)
    void drawBlocks(int w, int h) { drawBlocks(w, h, GLDrawParams::TileSize()); }
    void drawBlocks(int w, int h, int bh) {
        for (int passed = 0; passed < h;) {
            const int rows = std::min(h - passed, bh);
            glViewport(0, passed, w, rows);
            draw();
            glFlush();
            passed += rows;
        }
    }

    void computeManual(int x, int y, int z) {
        glDispatchCompute(GLuint(x), GLuint(y), GLuint(z));
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        glMemoryBarrier(GL_ALL_SHADER_BITS);
        glFinish();
    }
    void computeManualAsync(int x, int y, int z) {
        glDispatchCompute(GLuint(x), GLuint(y), GLuint(z));
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        glMemoryBarrier(GL_ALL_SHADER_BITS);
    }
    /// computeAuto: the dispatch of `size` (x, y) and z by the program's local size
    void computeAuto(Point size, int z) {
        auto it = layouts_.find("in");
        if (it == layouts_.end()) throw std::runtime_error("glComputeLayout is null");
        const GLComputeLayout& l = it->second;
        computeManual(size.x / l.x + (size.x % l.x ? 1 : 0), size.y / l.y + (size.y % l.y ? 1 : 0), z / l.z + (z % l.z ? 1 : 0));
    }

    void close() {
        for (auto& kv : cache_) glDeleteProgram(kv.second);
        cache_.clear();
        if (vertexShader_) glDeleteShader(vertexShader_);
        vertexShader_ = 0;
    }

private:
    std::string root_;
    GLuint vertexShader_ = 0;
    Defines defines_;
    std::unordered_map<std::string, GLuint> cache_;
    std::map<std::string, int> textureBinds_;
    std::map<std::string, GLComputeLayout> layouts_;
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
            char log[4096] = {};
            glGetShaderInfoLog(s, sizeof log, nullptr, log);
            throw std::runtime_error(std::string("shader compile: ") + log + "\n" + src.substr(0, 400));
        }
        return s;
    }

    void useShader(const std::string& shader, bool compute) {
        isCompute = compute;
        closed = false;
        defines_.clear();
        layouts_ = getLayouts(shader);
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
                char log[4096] = {};
                glGetProgramInfoLog(program, sizeof log, nullptr, log);
                throw std::runtime_error(std::string("program link: ") + log);
            }
            glDeleteShader(s);
            cache_[shader] = program;
        }
        glGetError();
        glUseProgram(program);
        mCurrentProgramActive = program;
        textureBinds_.clear();
        newTextureId_ = 0;
    }
};

} // namespace scam_post
