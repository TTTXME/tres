// WhyAeGpu.java one to one: the Smart-HDR per-pixel statistics on the GPU (why/aestats, two passes, chained or with the read-back
// between them); WhyAe's CPU walk when the GPU's histogram weights do not add up.
#pragma once

#include "../GLCore.hpp"
#include "../Pipeline.hpp"
#include "WhyAe.hpp"

namespace scam_post {
namespace WhyAeGpu {

constexpr int TILE = 64;

inline double totalWeight(int w, int h, int mode) {
    if (mode < 1) return double(w) * h;
    const float halfW = float(w) * 0.5f, halfH = float(h) * 0.5f;
    double tot = 0;
    for (int y = 0; y < h; y++) {
        const float dy = (float(y) - halfH) / halfH, dy2 = dy * dy;
        for (int x = 0; x < w; x++) {
            const float dx = (float(x) - halfW) / halfW, d2 = dx * dx + dy2;
            if (mode == 2) tot += d2 < 0.04f ? 10 : d2 < 0.16f ? 2 : 0;
            else if (mode == 1) tot += d2 < 0.25f ? 4 : d2 < 0.5625f ? 2 : 1;
            else tot += 1;
        }
    }
    return tot;
}

inline std::optional<WhyAe::Stats> check(WhyAe::Stats st, int w, int h, int mode) {
    const double weight = totalWeight(w, h, mode);
    double hmTotal = 0;
    for (double v : st.hm) hmTotal += v;
    const double tolerance = mode < 1 ? weight * 1e-4 : weight * 1e-3;
    if (std::fabs(st.tot - weight) > tolerance || std::fabs(hmTotal - weight) > tolerance) {
        logI("SCAM_PIPELINE", "WHY AE GPU: histogram weight " + std::to_string(int64_t(st.tot)) + " / " + std::to_string(int64_t(hmTotal))
             + " instead of " + std::to_string(int64_t(weight)) + ", CPU statistics");
        return std::nullopt;
    }
    return st;
}

/// begin + end: the two passes chained (PASS 2 with GPU_CLIP reads the maximum on the GPU), then the read-back
inline std::optional<WhyAe::Stats> chained(GLProg& prog, GLTexture& low, int mode) {
    const int w = low.mSize.x, h = low.mSize.y;
    const int gx = (w + TILE - 1) / TILE, gy = (h + TILE - 1) / TILE;
    const GLFormat u32(DataType::UNSIGNED_32);
    while (glGetError() != GL_NO_ERROR) {}
    try {
        GLBuffer max(1, u32), hy(WhyAe::BINS, u32), hm(WhyAe::BINS, u32), sums(2 * gx * gy, u32), tail(WhyAe::TAIL_BINS, u32);
        prog.setDefine("PASS", 1);
        prog.setLayout(8, 8, 1);
        prog.useAssetProgram("why/aestats", true);
        prog.setTexture("whyLow", low);
        prog.setVar("sizeU", w, h);
        prog.setVar("modeU", mode);
        prog.setBufferCompute("aeMax", max);
        prog.computeManualAsync(gx, gy, 1);
        prog.setDefine("PASS", 2);
        prog.setDefine("GPU_CLIP", 1);
        prog.setLayout(8, 8, 1);
        prog.useAssetProgram("why/aestats", true);
        prog.setTexture("whyLow", low);
        prog.setVar("sizeU", w, h);
        prog.setVar("modeU", mode);
        prog.setBufferCompute("aeMax", max);
        prog.setBufferCompute("aeHistY", hy);
        prog.setBufferCompute("aeHistM", hm);
        prog.setBufferCompute("aeSums", sums);
        prog.setBufferCompute("aeTail", tail);
        prog.computeManualAsync(gx, gy, 1);
        if (glGetError() != GL_NO_ERROR) return std::nullopt;
        glFlush();
        const int32_t key = max.readBufferIntegers(false)[0];
        const auto y = hy.readBufferIntegers(false), m = hm.readBufferIntegers(false), s = sums.readBufferIntegers(false), t = tail.readBufferIntegers(false);
        if (glGetError() != GL_NO_ERROR || y.empty() || m.empty() || t.empty()) return std::nullopt;
        return check(WhyAe::fromGpu(key, y, m, s, &t), w, h, mode);
    } catch (const std::exception& e) {
        logI("SCAM_PIPELINE", std::string("WHY AE GPU failed: ") + e.what() + ", CPU statistics");
        return std::nullopt;
    }
}

/// WhyAe.stats: the per-pixel part on the CPU (whyLow read back as floats: the exact widening of its halves)
inline WhyAe::Stats cpuStats(GLTexture& low, int mode) {
    const int width = low.mSize.x, height = low.mSize.y;
    low.BufferLoad();
    std::vector<float> px(size_t(width) * height * 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, px.data());
    float gmax = -1000.f;
    for (size_t i = 0; i < px.size(); i += 4)
        for (int c = 0; c < 3; c++) {
            const float v = px[i + size_t(c)];
            if (!std::isnan(v) && !std::isinf(v) && v > gmax) gmax = v;
        }
    const float invClip = WhyAe::invClip(gmax);
    const float halfW = float(width) * 0.5f, halfH = float(height) * 0.5f;
    WhyAe::Stats st;
    st.gmax = gmax;
    double tot = 0, sumY = 0, sumLog = 0;
    auto clamp01 = [](float v) { return std::max(0.f, std::min(1.f, v)); };
    for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++) {
            const float* o = &px[(size_t(y) * width + x) * 4];
            const float rr = o[0], gg = o[1], bb = o[2];
            float yv = gg * 0.7152f + rr * 0.2126f + bb * 0.0722f;
            float mv = std::max(std::max(std::max(gg, bb), rr), yv);
            double w = 1.0;
            if (mode >= 1) {
                const float dx = (float(x) - halfW) / halfW, dy = (float(y) - halfH) / halfH, d2 = dx * dx + dy * dy;
                if (mode == 2) w = d2 < 0.04f ? 10 : d2 < 0.16f ? 2 : 0;
                else if (mode == 1) w = d2 < 0.25f ? 4 : d2 < 0.5625f ? 2 : 1;
            }
            if (std::isnan(yv)) yv = 0.f;
            if (std::isnan(mv)) mv = 0.f;
            const int iy = int(clamp01(yv * invClip) * 4095.99f), im = int(clamp01(mv * invClip) * 4095.99f);
            st.hy[size_t(iy)] += w;
            st.hm[size_t(im)] += w;
            if (mv * invClip > 1.f) {
                const float ratio = mv * invClip;
                st.tail[size_t(std::min(WhyAe::TAIL_BINS - 1, std::max(0, int(float(std::log(double(ratio)) / std::log(2.0)) * float(WhyAe::TAIL_PER_OCTAVE)))))] += w;
            }
            tot += w;
            if (w != 0) {
                sumY += double(yv) * w;
                sumLog += double(float(std::log(double(std::max(yv, 1e-4f))))) * w;
            }
        }
    st.tot = tot;
    st.sumY = sumY;
    st.sumLog = sumLog;
    return st;
}

} // namespace WhyAeGpu
} // namespace scam_post
