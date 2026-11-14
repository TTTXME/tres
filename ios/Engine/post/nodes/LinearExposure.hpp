// LinearExposure.java and PostPipeline.captureDemosaicLinear / snapshot one to one: with Ultra HDR on, the linear RGB of the merge
// before the tone is kept on the CPU for RunHDRGainMap (UltraHdr.hpp) - at the final size when the pipeline resizes (the "area"
// box mean of HybridFinalResize, as the base is made). Renders nothing: the input passes through.
// The snapshot holds the texture's halves (Android P147's half read-back, 8 B a pixel): read back as floats (exact for every half)
// in strips and narrowed on the CPU, uploaded again as GL_HALF_FLOAT - the same texels as the Android snapshot gives.
#pragma once

#include "HybridFinalResize.hpp"

#include <cstring>

namespace scam_post {

/// the half of a float, rounded to nearest even (exact for a float that is a half)
inline uint16_t floatToHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    uint32_t ax = x & 0x7fffffffu;
    if (ax >= 0x7f800000u) {
        if (ax == 0x7f800000u) return uint16_t(sign | 0x7c00u);
        const uint32_t payload = (ax >> 13) & 0x3ffu;
        return uint16_t(sign | 0x7c00u | (payload ? payload : 0x200u));
    }
    if (ax >= 0x477ff000u) return uint16_t(sign | 0x7c00u);
    if (ax < 0x38800000u) {
        float a;
        std::memcpy(&a, &ax, 4);
        return uint16_t(sign | uint32_t(std::nearbyint(a * 16777216.0f)));
    }
    const uint32_t odd = (ax >> 13) & 1u;
    ax += 0xc8000fffu + odd;
    return uint16_t(sign | (ax >> 13));
}

/// PostPipeline.snapshot: the texture's RGBA halves into the pipeline's demosaicLinear
inline void snapshotLinear(PostPipeline& p, GLTexture& tex) {
    const Point size = tex.mSize;
    std::vector<uint16_t> halves;
    try {
        halves.resize(size_t(size.x) * size_t(size.y) * 4);
    } catch (const std::bad_alloc&) {
        logI("PostPipeline", "Linear scene snapshot allocation failed; Ultra HDR will fall back to SDR");
        return;
    }
    tex.BufferLoad();
    const int strip = 256;
    std::vector<float> rows(size_t(size.x) * size_t(std::min(strip, size.y)) * 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    for (int y = 0; y < size.y; y += strip) {
        const int n = std::min(strip, size.y - y);
        glReadPixels(0, y, size.x, n, GL_RGBA, GL_FLOAT, rows.data());
        uint16_t* out = halves.data() + size_t(y) * size_t(size.x) * 4;
        for (size_t i = 0; i < size_t(n) * size_t(size.x) * 4; i++) out[i] = floatToHalf(rows[i]);
    }
    checkGl("linear scene snapshot");
    p.demosaicLinear = std::move(halves);
    p.demosaicLinearSize = size;
    p.mCaptured = true;
}

/// PostPipeline.captureDemosaicLinear
inline void captureDemosaicLinear(PostPipeline& p, const Texture& full, GLProg& prog) {
    if (p.mCaptured || !p.demosaicLinear.empty()) return;
    Texture reduced;
    if (p.finalSize && (full->mSize.x != p.finalSize->x || full->mSize.y != p.finalSize->y)) {
        try {
            reduced = HybridFinalResize::resized(prog, full, *p.finalSize, "area");
        } catch (const std::exception& e) {
            logI("PostPipeline", std::string("Linear scene snapshot resize failed (") + e.what() + "); Ultra HDR will fall back to SDR");
            return;
        }
    }
    try {
        snapshotLinear(p, reduced ? *reduced : *full);
    } catch (...) {
        if (reduced) reduced->close();
        throw;
    }
    if (reduced) reduced->close();
}

/// PostPipeline.releaseDemosaicLinear
inline void releaseDemosaicLinear(PostPipeline& p) {
    std::vector<uint16_t>().swap(p.demosaicLinear);
    p.demosaicLinearSize.reset();
}

class LinearExposure : public Node {
public:
    LinearExposure() : Node("", "LinearExposure") {}
    void Compile() override {}
    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        if (pipeline.captureDemosaic) captureDemosaicLinear(pipeline, previousNode->WorkingTexture, *glProg);
        WorkingTexture = previousNode->WorkingTexture;
        glProg->closed = true;
    }
};

} // namespace scam_post
