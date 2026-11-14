// Ultra HDR, the gain map of a shot one to one: PostPipeline.RunHDRGainMap (the scene-luma plane of the linear snapshot,
// ultrahdr/sceneluma with the lens shading and the sky term's ratio; the medians of the plane and of the SDR base by GLHistogram;
// ultrahdr/gainmap; P145's range and requantisation on the GPU: ultrahdr/gainmax, ultrahdr/gainquant) and GainMapComputer
// (SCALE, DECODE_OFFSET, the range, the requantisation, the CPU normalisation of a map the GPU did not requantise; the result's
// GainMapMin / GainMapMax / HDRCapacityMax). The map is pixel-aligned with the photo (its rows as the photo's).
#pragma once

#include "Pipeline.hpp"
#include "nodes/LinearExposure.hpp"
#include "why/WhyToneCore.hpp"
#include "why/WhyV2Ae.hpp"

#include <array>
#include <cmath>
#include <limits>
#include <optional>
#include <vector>

namespace scam_post {

namespace GainMapComputer {

/// the total log2 range of the encoding (6 stops); uScale of ultrahdr/gainmap.glsl
constexpr float SCALE = 6.0f;
/// OffsetSDR / OffsetHDR of the metadata (1/64)
constexpr float DECODE_OFFSET = 1.0f / 64.0f;
/// downsample factor per axis
constexpr int SCALE_DOWN = 1;
constexpr float RANGE_PAD_FRACTION = 0.02f;
constexpr float MIN_RANGE = 1e-3f;

/// GainMapComputer.Result: the map (RGBA8 rows, R = G = B, A 255), its log2 range and HDRCapacityMax
struct Result {
    std::vector<uint8_t> rgba;
    int width = 0, height = 0;
    float gainMapMin = 0.f, gainMapMax = 0.f, hdrCapacityMax = 0.f;

    Result() = default;
    Result(std::vector<uint8_t> map, int w, int h, float min, float max)
        : rgba(std::move(map)), width(w), height(h), gainMapMin(min), gainMapMax(max), hdrCapacityMax(jmath::max(max, 1e-3f)) {}

    /// IsoGainMap.luma: one byte a pixel (R), rows without padding
    std::vector<uint8_t> luma() const {
        std::vector<uint8_t> out(size_t(width) * size_t(height));
        for (size_t i = 0; i < out.size(); i++) out[i] = rgba[i * 4];
        return out;
    }
};

inline float rangeMax(float maxBoost) {
    if (!std::isfinite(maxBoost) || maxBoost < 0.f) maxBoost = 0.f;
    if (maxBoost < MIN_RANGE) maxBoost = MIN_RANGE;
    return maxBoost + jmath::max(maxBoost * RANGE_PAD_FRACTION, MIN_RANGE);
}

inline float boost(int encoded, float scale) { return (float(encoded) / (1 * 255.f)) * scale; }

inline std::array<int, 256> requantisation(float gMax, float scale) {
    const float range = gMax - 0.f;
    std::array<int, 256> lut{};
    for (int v = 0; v < 256; v++) {
        float vNorm = (boost(v, scale) - 0.f) / range;
        if (vNorm < 0.f) vNorm = 0.f;
        else if (vNorm > 1.f) vNorm = 1.f;
        lut[size_t(v)] = int(Settings::javaRound(vNorm * 255.0f));
    }
    return lut;
}

inline Result normalised(std::vector<uint8_t> map, int w, int h, float gMax) { return Result(std::move(map), w, h, 0.f, gMax); }

/// GainMapComputer.compute: the CPU range and requantisation of an encoded map (box filter of `down` in the log domain)
inline Result compute(const std::vector<uint8_t>& src, int sw, int sh, int down, float scale) {
    if (sw <= 0 || sh <= 0) throw std::runtime_error("Empty gain map: " + std::to_string(sw) + "x" + std::to_string(sh));
    down = std::max(1, down);
    const int gw = std::max(1, sw / down), gh = std::max(1, sh / down);
    std::vector<float> row(static_cast<size_t>(gw));
    auto boostRow = [&](int gy) {
        const int y0 = gy * down;
        const int rows = std::min(down, sh - y0);
        for (int gx = 0; gx < gw; gx++) {
            const int x0 = gx * down, x1 = std::min(x0 + down, sw);
            float sum = 0.f;
            int count = 0;
            for (int y = 0; y < rows; y++)
                for (int x = x0; x < x1; x++) {
                    sum += float(src[(size_t(y0 + y) * size_t(sw) + size_t(x)) * 4]);
                    count++;
                }
            row[size_t(gx)] = (sum / (float(count) * 255.f)) * scale;
        }
    };
    float maxBoost = -std::numeric_limits<float>::infinity();
    for (int gy = 0; gy < gh; gy++) {
        boostRow(gy);
        for (float lb : row) if (lb > maxBoost) maxBoost = lb;
    }
    const float gMin = 0.f, gMax = rangeMax(maxBoost), range = gMax - gMin;
    std::vector<uint8_t> out(size_t(gw) * size_t(gh) * 4);
    for (int gy = 0; gy < gh; gy++) {
        boostRow(gy);
        for (int gx = 0; gx < gw; gx++) {
            float vNorm = (row[size_t(gx)] - gMin) / range;
            if (vNorm < 0.f) vNorm = 0.f;
            else if (vNorm > 1.f) vNorm = 1.f;
            const uint8_t b = uint8_t(Settings::javaRound(vNorm * 255.0f));
            uint8_t* px = out.data() + (size_t(gy) * size_t(gw) + size_t(gx)) * 4;
            px[0] = px[1] = px[2] = b;
            px[3] = 255;
        }
    }
    return Result(std::move(out), gw, gh, gMin, gMax);
}

} // namespace GainMapComputer

/// PostPipeline.GainMapRaw
struct GainMapRaw {
    std::vector<uint8_t> rgba;
    int w = 0, h = 0, down = 1;
    float scale = GainMapComputer::SCALE;
    float normalisedMax = NAN;
};

/// GLHistogram (processing/opengl/scripts) with the default program (GLHistogram/histogram): 256 bins of R, G, B, A of every
/// third pixel of each third row
class GLHistogram {
public:
    GLHistogram(GLProg& prog, int size) : prog_(prog), histSize_(size) {
        for (auto& b : buffers_) b = std::make_unique<GLBuffer>(histSize_, GLFormat(DataType::UNSIGNED_32));
    }
    int resize = 3;
    std::array<std::vector<int32_t>, 4> compute(GLTexture& input) {
        input.Bufferize();
        const int tile = 8;
        prog_.setDefine("SCALE", resize);
        prog_.setDefine("HISTSIZE", histSize_);
        prog_.setDefine("COL_R", true);
        prog_.setDefine("COL_G", true);
        prog_.setDefine("COL_B", true);
        prog_.setDefine("COL_A", true);
        prog_.setDefine("COL_CUSTOM", false);
        prog_.setDefine("CUSTOM_PROGRAM", std::string());
        prog_.setDefine("SPATIAL_KERNEL", false);
        prog_.setLayout(tile, tile, 1);
        prog_.useAssetProgram("GLHistogram/histogram", true);
        prog_.setTexture("inTexture", input);
        const float top = float(histSize_ - 1);
        prog_.setVar("binScale", 1.f * top, 1.f * top, 1.f * top, 1.f * top); // the shader's per-channel bin scale (was "exposure")
        prog_.setVar("input1", 0.f);
        prog_.setVar("input2", 0.f);
        prog_.setBufferCompute("histogramRed", *buffers_[0]);
        prog_.setBufferCompute("histogramGreen", *buffers_[1]);
        prog_.setBufferCompute("histogramBlue", *buffers_[2]);
        prog_.setBufferCompute("histogramAlpha", *buffers_[3]);
        prog_.computeAuto({input.mSize.x / resize, input.mSize.y / resize}, 1);
        std::array<std::vector<int32_t>, 4> out;
        for (size_t i = 0; i < 4; i++) out[i] = buffers_[i]->readBufferIntegers(true);
        return out;
    }

private:
    GLProg& prog_;
    int histSize_;
    std::array<std::unique_ptr<GLBuffer>, 4> buffers_;
};

namespace UltraHdr {

/// PostPipeline.histogramMedian: the bin where the count from the top crosses half of all R, G, B samples, over 0..1
inline float histogramMedian(const std::array<std::vector<int32_t>, 4>& result, int histSize) {
    int64_t total = 0;
    for (int i = 0; i < histSize; i++) total += int64_t(result[0][size_t(i)]) + int64_t(result[1][size_t(i)]) + int64_t(result[2][size_t(i)]);
    if (total <= 0) return 0.f;
    const int64_t threshold = total / 2;
    int64_t acc = 0;
    for (int i = histSize - 1; i >= 0; i--) {
        acc += int64_t(result[0][size_t(i)]) + int64_t(result[1][size_t(i)]) + int64_t(result[2][size_t(i)]);
        if (acc > threshold) return float(i) / float(histSize - 1);
    }
    return 1.f;
}

/// PostPipeline.sceneLumaWeights: LUMA^T . intermediateToSRGB . sensorToProPhoto . diag(neutral)
inline std::array<float, 3> sceneLumaWeights(const Mat3& intermediateToSRGB, const std::vector<float>& sensorToProPhoto,
                                             const std::vector<float>& neutral) {
    const float luma[3] = {0.2126f, 0.7152f, 0.0722f};
    const Mat3 m = multiply(intermediateToSRGB, sensorToProPhoto);
    std::array<float, 3> w{};
    for (size_t c = 0; c < 3; c++) {
        float s = 0.f;
        for (size_t r = 0; r < 3; r++) s += luma[r] * m[r * 3 + c];
        w[c] = s * neutral[c];
    }
    return w;
}

inline float srgbToLinear(float c) {
    c = jmath::min(jmath::max(c, 0.f), 1.f);
    return c <= 0.04045f ? c / 12.92f : float(std::pow((double(c) + 0.055) / 1.055, 2.4));
}

/// PostPipeline.rotationIndex: 90 -> 3, 180 -> 2, 270 -> 1
inline int rotationIndex(int rotation) {
    switch (rotation) {
    case 90: return 3;
    case 180: return 2;
    case 270: return 1;
    default: return 0;
    }
}

/// PostPipeline.gainMapMaxByte: the largest encoded byte of the map (-1: the pass failed)
inline int gainMapMaxByte(GLProg& prog, GLTexture& map, int w, int h) {
    while (glGetError() != GL_NO_ERROR) {}
    try {
        GLBuffer max(1, GLFormat(DataType::UNSIGNED_32));
        prog.setLayout(16, 16, 1);
        prog.useAssetProgram("ultrahdr/gainmax", true);
        prog.setTexture("InputBuffer", map);
        prog.setVar("sizeU", w, h);
        prog.setBufferCompute("gainMax", max);
        prog.computeManual((w + 15) / 16, (h + 15) / 16, 1);
        const std::vector<int32_t> v = max.readBufferIntegers(false);
        if (glGetError() != GL_NO_ERROR || v.empty() || v[0] < 0 || v[0] > 255) return -1;
        return v[0];
    } catch (const std::exception& e) {
        logI("PostPipeline", std::string("UltraHDR gain map max on the GPU failed: ") + e.what());
        return -1;
    }
}

/// PostPipeline.RunHDRGainMap after Run (its textures closed, the snapshot kept): `sdr` the photo Run returned
inline GainMapRaw runHDRGainMap(PostPipeline& p, const std::vector<uint8_t>& sdrRgba, int sdrWidth, int sdrHeight, int down, float scale) {
    if (p.demosaicLinear.empty() || !p.demosaicLinearSize)
        throw std::runtime_error("Linear buffer missing; Run() must complete first with ultraHdr enabled");
    const Parameters& parameters = p.mParameters;
    const Point base = p.finalSize ? *p.finalSize : *parameters.rawSize;
    p.workSize = base;
    p.computeNoise();
    p.captureDemosaic = false;
    p.cropSize = base;
    const Point rotatedSize = p.getRotatedCoords(base);
    if (sdrWidth != rotatedSize.x || sdrHeight != rotatedSize.y)
        throw std::runtime_error("SDR/gain-map size mismatch: sdr=" + std::to_string(sdrWidth) + "x" + std::to_string(sdrHeight) + " map="
                                 + std::to_string(rotatedSize.x) + "x" + std::to_string(rotatedSize.y));
    // RunHDRGainMap's own processing (Android: a context of its own): a program without the run's state
    GLProg prog(p.glProg->shadersRoot());
    if (p.demosaicLinearSize->x != p.workSize.x || p.demosaicLinearSize->y != p.workSize.y)
        throw std::runtime_error("Linear buffer size does not match workSize; cannot measure scene plane");
    std::vector<Texture> textures;
    auto keep = [&](Texture t) { textures.push_back(t); return t; };
    auto finish = [&] {
        for (auto& t : textures) if (t) t->close();
        textures.clear();
        p.skyHdrRatio.reset();
        p.skyHdrRatioSize.reset();
        for (GLTexture* t : std::vector<GLTexture*>(openTextures().begin(), openTextures().end())) t->close();
        releaseDemosaicLinear(p);
    };
    try {
        // the halves go up as they came (GL_HALF_FLOAT into the RGBA16F texture): the same texels
        Texture linTex = keep(makeTexture(*p.demosaicLinearSize, GLFormat(DataType::FLOAT_16, 4)));
        {
            GLint previous = 0;
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
            glBindTexture(GL_TEXTURE_2D, linTex->mTextureID);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, p.demosaicLinearSize->x, p.demosaicLinearSize->y, GL_RGBA, GL_HALF_FLOAT,
                            p.demosaicLinear.data());
            glBindTexture(GL_TEXTURE_2D, GLuint(previous));
            checkGl("half snapshot upload");
        }
        // GLTexture(GLImage) of the SDR bitmap: RGBA8, linear filter
        Texture sdrTex = keep(makeTexture({sdrWidth, sdrHeight}, GLFormat(DataType::SIMPLE_8, 4), sdrRgba.data(), GL_LINEAR, GL_CLAMP_TO_EDGE));
        // the lens-shading gain map for flat-fielding the scene plane (as ScamRgb's)
        Point mapSz = parameters.mapSize ? *parameters.mapSize : Point{1, 1};
        std::vector<float> gm = parameters.gainMap ? *parameters.gainMap : std::vector<float>();
        if (gm.size() < 4) {
            gm = {1.f, 1.f, 1.f, 1.f};
            mapSz = {1, 1};
        }
        Texture gainTex = keep(makeTexture(mapSz, GLFormat(DataType::FLOAT_16, 4), gm.data(), GL_LINEAR, GL_CLAMP_TO_EDGE));
        // the sky term's ratio (WhyCombine), linear filter
        Texture skyRatioTex;
        if (p.skyHdrRatio && p.skyHdrRatioSize && p.skyHdrRatio->size() == size_t(p.skyHdrRatioSize->x) * size_t(p.skyHdrRatioSize->y))
            skyRatioTex = keep(makeTexture(*p.skyHdrRatioSize, GLFormat(DataType::FLOAT_16, 1), p.skyHdrRatio->data(), GL_LINEAR, GL_CLAMP_TO_EDGE));
        p.skyHdrRatio.reset();
        p.skyHdrRatioSize.reset();
        // the scene-luma plane at full resolution, pixel-aligned with the base
        Texture lTex = keep(makeTexture(rotatedSize, GLFormat(DataType::FLOAT_16, 4)));
        lTex->BufferLoad();
        prog.useAssetProgram("ultrahdr/sceneluma", false);
        prog.setTexture("InputBuffer", linTex);
        prog.setTexture("GainMap", gainTex);
        prog.setVar("rotate", rotationIndex(p.getRotation()));
        prog.setVar("mirror", parameters.mirror ? 1 : 0);
        prog.setVar("cropSize", p.cropSize);
        prog.setVar("rawSize", base);
        const std::array<float, 3> w = sceneLumaWeights(WhyTone::colourMatrix(p), *parameters.sensorToProPhoto, *parameters.whitePoint);
        prog.setVar("wU", w[0], w[1], w[2]);
        if (skyRatioTex) {
            prog.setTexture("SkyRatio", skyRatioTex);
            prog.setVar("skyRatioU", 1);
        } else {
            prog.setVar("skyRatioU", 0);
        }
        glDisable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, lTex->mBuffer);
        glViewport(0, 0, rotatedSize.x, rotatedSize.y);
        prog.draw();
        checkGl("scene-luma draw");
        // the anchor: the medians of the rendering and of the scene plane
        const int histSize = 256;
        float lMed, sMedLin;
        {
            GLHistogram hist(prog, histSize);
            lMed = jmath::max(histogramMedian(hist.compute(*lTex), histSize), 1e-4f);
            const float sMedDisp = histogramMedian(hist.compute(*sdrTex), histSize);
            sMedLin = srgbToLinear(sMedDisp);
        }
        const float anchor = sMedLin / lMed;
        logI("PostPipeline", "UltraHDR anchor:" + javaFloatString(anchor) + " Lmed:" + javaFloatString(lMed) + " SmedLin:" + javaFloatString(sMedLin));
        const int gw = std::max(1, rotatedSize.x / down), gh = std::max(1, rotatedSize.y / down);
        Texture outTex = keep(makeTexture({gw, gh}, GLFormat(DataType::SIMPLE_8, 4)));
        outTex->BufferLoad();
        prog.useAssetProgram("ultrahdr/gainmap", false);
        prog.setTexture("InputBuffer", sdrTex);
        prog.setTexture("LBuffer", lTex);
        prog.setVar("uAnchor", anchor);
        prog.setVar("uDown", down);
        prog.setVar("uScale", scale);
        prog.setVar("uEps", GainMapComputer::DECODE_OFFSET);
        prog.setVar("uP3", p.p3Output ? 1 : 0);
        glBindFramebuffer(GL_FRAMEBUFFER, outTex->mBuffer);
        glViewport(0, 0, gw, gh);
        prog.draw();
        checkGl("gain-map comparison draw");
        // P145: GainMapComputer's range and requantisation on the GPU
        float normalisedMax = NAN;
        Texture quantTex;
        if (down == 1 && !PostGlMode::legacy()) {
            const int maxByte = gainMapMaxByte(prog, *outTex, gw, gh);
            if (maxByte >= 0) {
                const float gMax = GainMapComputer::rangeMax(GainMapComputer::boost(maxByte, scale));
                const std::array<int, 256> lut = GainMapComputer::requantisation(gMax, scale);
                std::vector<float> lutF(256);
                for (size_t i = 0; i < 256; i++) lutF[i] = float(lut[i]);
                quantTex = keep(makeTexture({gw, gh}, GLFormat(DataType::SIMPLE_8, 4)));
                quantTex->BufferLoad();
                prog.useAssetProgram("ultrahdr/gainquant", false);
                prog.setTexture("InputBuffer", outTex);
                prog.setVarVec4s("lutU", lutF);
                glBindFramebuffer(GL_FRAMEBUFFER, quantTex->mBuffer);
                glViewport(0, 0, gw, gh);
                prog.draw();
                if (glGetError() == GL_NO_ERROR) {
                    normalisedMax = gMax;
                } else {
                    quantTex->close();
                    quantTex = nullptr;
                }
                logI("PostPipeline", "UltraHDR gain map on the GPU: max byte " + std::to_string(maxByte) + " -> GainMapMax " + javaFloatString(gMax)
                     + (quantTex ? "" : " (GL error: CPU normalisation)"));
            }
        }
        GainMapRaw raw;
        raw.w = gw;
        raw.h = gh;
        raw.down = down;
        raw.scale = scale;
        raw.normalisedMax = normalisedMax;
        raw.rgba = (quantTex ? quantTex : outTex)->textureBuffer(GLFormat(DataType::SIMPLE_8, 4));
        prog.close();
        finish();
        return raw;
    } catch (...) {
        prog.close();
        finish();
        throw;
    }
}

/// ScamProcessor: the map of the photo (GPU-normalised, or GainMapComputer.compute on the CPU), null when the pass fails
inline std::optional<GainMapComputer::Result> gainMap(PostPipeline& p, const std::vector<uint8_t>& sdrRgba, int sdrWidth, int sdrHeight) {
    try {
        GainMapRaw gm = runHDRGainMap(p, sdrRgba, sdrWidth, sdrHeight, GainMapComputer::SCALE_DOWN, GainMapComputer::SCALE);
        logI("ScamProcessor", "Ultra HDR gain map " + std::to_string(gm.w) + "x" + std::to_string(gm.h) + " for " + std::to_string(sdrWidth) + "x"
             + std::to_string(sdrHeight));
        const bool gpu = !std::isnan(gm.normalisedMax);
        GainMapComputer::Result gain = gpu ? GainMapComputer::normalised(std::move(gm.rgba), gm.w, gm.h, gm.normalisedMax)
                                           : GainMapComputer::compute(gm.rgba, gm.w, gm.h, gm.down, gm.scale);
        logI("ScamProcessor", "Ultra HDR gain map: min=" + javaFloatString(gain.gainMapMin) + " max=" + javaFloatString(gain.gainMapMax) + " (log2) "
             + std::to_string(gain.width) + "x" + std::to_string(gain.height) + (gpu ? " normalised on the GPU" : ""));
        return gain;
    } catch (const std::exception& e) {
        logI("ScamProcessor", std::string("Ultra HDR gain-map pass failed, falling back to SDR: ") + e.what());
        releaseDemosaicLinear(p);
        return std::nullopt;
    }
}

} // namespace UltraHdr

} // namespace scam_post
