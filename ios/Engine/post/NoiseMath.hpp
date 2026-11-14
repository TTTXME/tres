// NoiseMath.java one to one (the selections give the same order statistics as the Java quickselects, bit for bit).
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <optional>
#include <vector>

namespace scam_post {
namespace NoiseMath {

/// Float.floatToIntBits (NaN canonical) made sortable in Float.compare's order
inline int32_t sortableBits(float f) {
    int32_t bits;
    if (std::isnan(f)) bits = 0x7fc00000;
    else std::memcpy(&bits, &f, 4);
    return bits ^ ((bits >> 31) & 0x7fffffff);
}
inline float floatOfSortable(int32_t key) {
    const int32_t bits = key ^ ((key >> 31) & 0x7fffffff);
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}
/// the element at k of a[0..n) in Float.compare order
inline float selectFloat(const float* a, int n, int k) {
    std::vector<int32_t> c(static_cast<size_t>(n));
    for (int i = 0; i < n; i++) c[size_t(i)] = sortableBits(a[i]);
    std::nth_element(c.begin(), c.begin() + k, c.end());
    return floatOfSortable(c[size_t(k)]);
}
inline float medianSelect(const float* a, int n) {
    std::vector<int32_t> c(static_cast<size_t>(n));
    for (int i = 0; i < n; i++) c[size_t(i)] = sortableBits(a[i]);
    std::sort(c.begin(), c.end());
    const float hi = floatOfSortable(c[size_t(n / 2)]);
    if ((n & 1) == 1) return hi;
    return 0.5f * (floatOfSortable(c[size_t(n / 2 - 1)]) + hi);
}

inline float snr(float mu, float g01, float sG, float rG, float rhoS, float rhoR) {
    const double var = std::max(double(g01), 1e-6) * 0.5 * (double(rhoS * sG * std::max(mu, 0.f)) + double(rhoR * rG));
    return var > 0 ? float(double(mu) / std::sqrt(var)) : 1000.f;
}

inline float whiteGain(int level, bool chroma) { return float(2.0 * std::pow(chroma ? 0.09765625 : 0.19269013, level)); }

/// reduce: {gY1, gY2, gU1, gU2, gV1, gV2, uvsU1, uvsU2, uvsV1, uvsV2, blocks used}, null below 16 valid blocks
inline std::optional<std::vector<float>> reduce(const std::vector<float>& blocks, int count, float clip) {
    std::vector<int> valid;
    valid.reserve(size_t(count));
    for (int i = 0; i < count; i++) {
        const size_t o = size_t(8 * i);
        const float y = blocks[o];
        bool ok = y > 1e-5f && y < clip && blocks[o + 2] > 0.f;
        for (int k = 1; k < 8 && ok; k++) ok = std::isfinite(blocks[o + size_t(k)]);
        if (ok) valid.push_back(i);
    }
    const int n = int(valid.size());
    if (n < 16) return std::nullopt;
    std::vector<int64_t> keys(static_cast<size_t>(n));
    for (int i = 0; i < n; i++) keys[size_t(i)] = (int64_t(sortableBits(blocks[size_t(8 * valid[size_t(i)] + 1)])) << 32) | int64_t(uint32_t(i));
    const int m = std::max(16, n / 4);
    std::sort(keys.begin(), keys.end());
    std::vector<int> byT(static_cast<size_t>(m));
    for (int i = 0; i < m; i++) byT[size_t(i)] = valid[size_t(uint32_t(keys[size_t(i)]))];
    std::vector<float> out(11), tmp(static_cast<size_t>(m));
    for (int k = 0; k < 6; k++) {
        for (int i = 0; i < m; i++) tmp[size_t(i)] = blocks[size_t(8 * byT[size_t(i)] + 2 + k)];
        out[size_t(k)] = medianSelect(tmp.data(), m);
    }
    std::vector<int64_t> keysY(static_cast<size_t>(m));
    for (int i = 0; i < m; i++) keysY[size_t(i)] = (int64_t(sortableBits(blocks[size_t(8 * byT[size_t(i)])])) << 32) | int64_t(uint32_t(i));
    std::sort(keysY.begin(), keysY.end());
    std::vector<int> byY(static_cast<size_t>(m));
    for (int i = 0; i < m; i++) byY[size_t(i)] = byT[size_t(uint32_t(keysY[size_t(i)]))];
    const int d = std::max(8, m / 2);
    std::vector<float> r(static_cast<size_t>(d));
    for (int s = 0; s < 2; s++)
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < d; i++) {
                const size_t o = size_t(8 * byY[size_t(i)]);
                r[size_t(i)] = blocks[o + 2 + size_t(s)] / std::max(blocks[o + 4 + size_t(2 * c + s)], 1e-6f);
            }
            out[size_t(6 + 2 * c + s)] = float(std::sqrt(double(std::max(medianSelect(r.data(), d), 1e-4f))));
        }
    out[10] = float(m);
    return out;
}

} // namespace NoiseMath
} // namespace scam_post
