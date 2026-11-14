// P665 «Проявка» (Android gallery/editor/EditDevelop.java), header-only C++: the RGB a DNG of ours gives the camera's post pipeline
// (Engine/post/DevelopRun.hpp) - its level times the record's headroom, demosaiced:
// - the preview: the DNG binned so that a 2x2 cell of it is one pixel of at most PREVIEW_SIDE (a cell's R, mean G, B; superpixel);
// - the copy: the whole DNG demosaiced (Malvar-He-Cutler, mhc), as IEEE halves above HALF_ABOVE pixels.
#pragma once

#include "DngRaw.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <thread>
#include <vector>

namespace scam_edit {
namespace develop {

/// the preview develop's longer side at most (px)
constexpr int PREVIEW_SIDE = 2048;
/// above this many pixels the RGB goes to the pipeline as halves (6 B a pixel instead of 12)
constexpr long long HALF_ABOVE = 16000000LL;

/// ParallelWork.forEach: rows split over the cores
inline void forEachRow(int rows, const std::function<void(int)>& body) {
    const int n = std::max(1, std::min(rows, int(std::thread::hardware_concurrency())));
    if (n <= 1) {
        for (int y = 0; y < rows; y++) body(y);
        return;
    }
    std::vector<std::thread> pool;
    for (int t = 0; t < n; t++)
        pool.emplace_back([&, t] {
            for (int y = t; y < rows; y += n) body(y);
        });
    for (std::thread& th : pool) th.join();
}

/// the colour (0 R, 1 G, 2 B) of the mosaic at (x, y) for DngRaw's phase codes (RGGB, GRBG, GBRG, BGGR)
inline int colour(int phase, int x, int y) {
    const int i = ((y & 1) << 1) | (x & 1);
    switch (phase) {
        case DngRaw::GRBG: return i == 1 ? 0 : i == 2 ? 2 : 1;
        case DngRaw::GBRG: return i == 1 ? 2 : i == 2 ? 0 : 1;
        case DngRaw::BGGR: return i == 0 ? 2 : i == 3 ? 0 : 1;
        default: return i == 0 ? 0 : i == 3 ? 2 : 1;
    }
}

/// the bin whose 2x2 cells give at most PREVIEW_SIDE on the longer side
inline int binFor(int w, int h) {
    int bin = 1;
    while (std::max(w, h) / (2 * bin) > PREVIEW_SIDE && bin < 16) bin *= 2;
    return bin;
}

/// RGB of every 2x2 cell (its R, the mean of its G, its B) times gain: (w / 2) x (h / 2) float pixels
inline std::vector<float> superpixel(const std::vector<float>& mosaic, int w, int h, int phase, float gain) {
    const int ow = w / 2, oh = h / 2;
    std::vector<float> out(size_t(ow) * oh * 3);
    forEachRow(oh, [&](int y) {
        for (int x = 0; x < ow; x++) {
            float r = 0, g = 0, b = 0;
            for (int k = 0; k < 4; k++) {
                const int sx = 2 * x + (k & 1), sy = 2 * y + (k >> 1);
                const float v = mosaic[size_t(sy) * w + sx];
                const int c = colour(phase, sx, sy);
                if (c == 0) r = v;
                else if (c == 2) b = v;
                else g += v;
            }
            const size_t o = (size_t(y) * ow + x) * 3;
            out[o] = r * gain;
            out[o + 1] = g * .5f * gain;
            out[o + 2] = b * gain;
        }
    });
    return out;
}

/// IEEE half of v (round to nearest even; above the largest half: infinity; NaN kept)
inline uint16_t toHalf(float v) {
    uint32_t bits;
    std::memcpy(&bits, &v, 4);
    const uint32_t sign = (bits >> 16) & 0x8000;
    int exp = int((bits >> 23) & 0xFF);
    uint32_t mant = bits & 0x7FFFFF;
    if (exp == 0xFF) return uint16_t(sign | 0x7C00 | (mant != 0 ? 0x200 : 0));
    exp = exp - 127 + 15;
    if (exp >= 0x1F) return uint16_t(sign | 0x7C00);
    if (exp <= 0) {
        if (exp < -10) return uint16_t(sign);
        mant |= 0x800000;
        const int shift = 14 - exp;
        uint32_t h = mant >> shift;
        const uint32_t rem = mant & ((1u << shift) - 1), halfway = 1u << (shift - 1);
        if (rem > halfway || (rem == halfway && (h & 1) != 0)) h++;
        return uint16_t(sign | h);
    }
    uint32_t h = (uint32_t(exp) << 10) | (mant >> 13);
    const uint32_t rem = mant & 0x1FFF;
    if (rem > 0x1000 || (rem == 0x1000 && (h & 1) != 0)) h++;
    return uint16_t(sign | h);
}

/// the mirror of i into 0..n-1 about the edge sample (keeps the CFA parity)
inline int mirror(int i, int n) {
    if (i < 0) i = -i;
    if (i >= n) i = 2 * (n - 1) - i;
    return std::max(0, std::min(n - 1, i));
}

inline float at(const float* m, int w, int h, int x, int y) { return m[size_t(mirror(y, h)) * w + mirror(x, w)]; }

/// one pixel of mhc (without the gain) into out
inline void mhcAt(const float* m, int w, int h, int phase, int x, int y, float out[3]) {
    const int c = colour(phase, x, y);
    const float v = at(m, w, h, x, y);
    if (c == 1) {
        out[1] = v;
        // the colour to the left / right of this green
        const int horizontal = colour(phase, x + 1, y);
        const float n2 = at(m, w, h, x, y - 2) + at(m, w, h, x, y + 2), e2 = at(m, w, h, x - 2, y) + at(m, w, h, x + 2, y);
        const float d1 = at(m, w, h, x - 1, y - 1) + at(m, w, h, x + 1, y - 1) + at(m, w, h, x - 1, y + 1) + at(m, w, h, x + 1, y + 1);
        const float hor = at(m, w, h, x - 1, y) + at(m, w, h, x + 1, y), ver = at(m, w, h, x, y - 1) + at(m, w, h, x, y + 1);
        // the colour in this row: 5 C + 4 (W + E) - (W2 + E2) - diagonals + (N2 + S2) / 2, / 8; the other one transposed
        const float inRow = (5 * v + 4 * hor - e2 - d1 + .5f * n2) / 8.f;
        const float inCol = (5 * v + 4 * ver - n2 - d1 + .5f * e2) / 8.f;
        out[horizontal] = inRow;
        out[2 - horizontal] = inCol;
    } else {
        out[c] = v;
        const float g4 = at(m, w, h, x - 1, y) + at(m, w, h, x + 1, y) + at(m, w, h, x, y - 1) + at(m, w, h, x, y + 1);
        const float c4 = at(m, w, h, x - 2, y) + at(m, w, h, x + 2, y) + at(m, w, h, x, y - 2) + at(m, w, h, x, y + 2);
        const float d4 = at(m, w, h, x - 1, y - 1) + at(m, w, h, x + 1, y - 1) + at(m, w, h, x - 1, y + 1) + at(m, w, h, x + 1, y + 1);
        out[1] = (4 * v + 2 * g4 - c4) / 8.f;
        out[2 - c] = (6 * v + 2 * d4 - 1.5f * c4) / 8.f;
    }
}

/// Malvar-He-Cutler (2004) demosaic of the mosaic (w x h, DngRaw's phase) times gain, never below 0: the RGB as the pipeline takes
/// it - float32 RGB (12 B a pixel) or, with half, IEEE halves (6 B a pixel), native order; the borders mirror about the edge sample.
inline std::vector<uint8_t> mhc(const std::vector<float>& mosaic, int w, int h, int phase, float gain, bool half) {
    std::vector<uint8_t> out(size_t(w) * h * (half ? 6 : 12));
    const float* m = mosaic.data();
    forEachRow(h, [&](int y) {
        float rgb[3] = {0, 0, 0};
        uint8_t* row = out.data() + size_t(y) * w * (half ? 6 : 12);
        for (int x = 0; x < w; x++) {
            mhcAt(m, w, h, phase, x, y, rgb);
            for (int c = 0; c < 3; c++) {
                const float v = std::max(0.f, rgb[c] * gain);
                if (half) {
                    const uint16_t hv = toHalf(v);
                    std::memcpy(row + (size_t(x) * 3 + c) * 2, &hv, 2);
                } else {
                    std::memcpy(row + (size_t(x) * 3 + c) * 4, &v, 4);
                }
            }
        }
    });
    return out;
}

} // namespace develop
} // namespace scam_edit
