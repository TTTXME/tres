// P646 «Склейка YUV» (owner, 2026-11-12): the burst merge of a lens whose camera gives YUV (vivo X200 Pro front: no RAW).
//
// 1. The merge core is our port of the 8-bit YUV burst merge of the «2.7» / «3.1» profiles the owner supplied as a document and
//    a reference header (tools/yuv-merge-ref, host checks only): 32 x 32 tiles, YUV444 tiles read from YUV420 at integer origins,
//    16 thresholds of 8 x 8 blocks from a noise LUT, per-pixel acceptance on |Y - Ybase| with the profile's order of the two FP32
//    products, dithered rounding, the eligible / countPacked maps. Generalised to 16-bit samples (10-bit P010) - for 8-bit samples
//    the same bytes as the reference (tools/check_yuv_merge.cpp).
// 2. Ours: the alignment (pyramid global translation, per-tile projection candidates verified by Y SAD), the noise LUT measured on
//    the burst itself (no foreign calibration table: base vs donor differences of flat 8 x 8 blocks, binned by level, smoothed,
//    the camera's NOISE_PROFILE through the capture curve where a level has too few blocks), the sharpest base, guided chroma
//    upsampling, the exposure-normalised bracket fusion in linear light (ES for clipped highlights, L for shadows, a pixel only takes
//    a frame that agrees with the base where the base is not clipped) and the linearity check of the capture curve.
// Everything here is plain C++17 (no Android): the JNI (scam-yuv-jni.cpp) and the host checks include it alike.
#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace scam_yuv {

constexpr int kTile = 32;
constexpr int kTilePixels = kTile * kTile;
constexpr std::size_t kLutSize = 32 * 16 * 16;

/** The order of the two FP32 products of the per-pixel threshold: 2.7 (local x multiplier) x scale, 3.1 (scale x multiplier) x local. */
enum class Profile { P27, P31 };
enum class ChromaOrder { UV, VU };
enum class Divide { Exact, Q15 };

template <typename S>
struct Plane {
    const S* data = nullptr;
    std::ptrdiff_t row = 0, step = 1;  // in samples
    S at(int x, int y) const { return data[std::ptrdiff_t(y) * row + std::ptrdiff_t(x) * step]; }
};

/** A YUV 4:2:0 frame; {@code bits} 8 (uint8_t samples) or 9..16 (uint16_t samples holding the value in the low bits). */
template <typename S>
struct Frame420 {
    Plane<S> y, c0, c1;
    int width = 0, height = 0;
    ChromaOrder order = ChromaOrder::UV;
    int bits = 8;
    int maxCode() const { return (1 << bits) - 1; }
    int unit() const { return 1 << (bits - 8); }
};

using Matrix3 = std::array<std::array<float, 3>, 3>;
using Luma8 = std::array<uint8_t, kTilePixels>;
using CellGains = std::array<std::array<float, 4>, 16>;  // R, Ge, Go, B of the 4 x 4 cells of a tile

template <typename S>
struct Tile {
    std::array<S, 3 * kTilePixels> px{};
    std::array<uint16_t, 3> rgbQ8{};
    ChromaOrder order = ChromaOrder::UV;
};

/** The noise LUT in the document's index layout: 32 Y x 16 U x 16 V bins of the 8-bit block means (shift 3/4/4, place 0/5/9). */
struct NoiseLut {
    const float* values = nullptr;
    std::size_t size = 0;
    std::array<unsigned, 3> shift{{3, 4, 4}}, place{{0, 5, 9}};
    float at(const std::array<int, 3>& yuv8) const {
        unsigned index = 0;
        for (int c = 0; c < 3; ++c) index |= (unsigned(yuv8[c]) >> shift[c]) << place[c];
        if (index >= size) throw std::out_of_range("noise LUT index");
        return values[index];
    }
};

struct ThresholdParams {
    float spatialScale = 1.f, spatialBias = 0.f;
    float biasPre = 0.f, biasPost = .2f;
    float tileStd = .9898000359535217f;
};

struct TileThresholds {
    std::array<float, 16> local{};
    std::array<float, 3> lastGain{};
    float tile = 0.f;
    bool nearClip = false;
};

/** JFIF (full-range BT.601) YCbCr -> R'G'B' of the means used by the tile colour check (the document's exact FP32 literals). */
inline Matrix3 colourCheckMatrix(ChromaOrder order) {
    Matrix3 m{{{1.f, 0x1.8dee92p-9f, 0x1.6642d8p+0f},
               {1.f, -0x1.607d6ap-2f, -0x1.6f3512p-1f},
               {1.f, 0x1.c5be06p+0f, -0x1.922a4ep-8f}}};
    if (order == ChromaOrder::VU)
        for (auto& row : m) std::swap(row[1], row[2]);
    return m;
}

/** Reads the 32 x 32 YUV444 tile at (x, y) (chroma at ((x+i)>>1, (y+j)>>1)); false when its reads leave the frame. */
template <typename S>
bool extractTile(const Frame420<S>& in, int x, int y, const Matrix3& m, Tile<S>& dst) {
    if (x < 0 || y < 0 || in.width < kTile || in.height < kTile || x > in.width - kTile - (x & 1) ||
        y > in.height - kTile - (y & 1))
        return false;
    dst.order = in.order;
    std::array<int, 3> sum{};
    for (int j = 0; j < kTile; ++j) {
        const int v = y + j;
        for (int i = 0; i < kTile; ++i) {
            const int u = x + i;
            const S p0 = in.y.at(u, v), p1 = in.c0.at(u / 2, v / 2), p2 = in.c1.at(u / 2, v / 2);
            S* d = dst.px.data() + (j * kTile + i) * 3;
            d[0] = p0;
            d[1] = p1;
            d[2] = p2;
            sum[0] += p0;
            sum[1] += p1;
            sum[2] += p2;
        }
    }
    float l = sum[0] * (1.f / 1024), c0 = sum[1] * (1.f / 1024), c1 = sum[2] * (1.f / 1024);
    if (in.bits != 8) {  // the check runs on the 8-bit scale
        const float k = 1.f / float(in.unit());
        l *= k;
        c0 *= k;
        c1 *= k;
    }
    c0 -= 128.f;
    c1 -= 128.f;
    for (int c = 0; c < 3; ++c) {
        const float rgb = (m[c][2] * c1 + m[c][1] * c0) + m[c][0] * l;
        dst.rgbQ8[c] = uint16_t(std::clamp(rgb, 0.f, 255.f) * 256.f);
    }
    return true;
}

/** The 16 local thresholds of a base tile (in sample units) and the tile's own; {@code bits}: the samples' depth. */
template <typename S>
TileThresholds tileThresholds(const Tile<S>& base, int bits, const NoiseLut& lut, const CellGains& gains,
                              const ThresholdParams& p) {
    TileThresholds out;
    const int down = bits - 8;
    const float unit = float(1 << down);
    const float biasPre = bits == 8 ? p.biasPre : p.biasPre * unit, biasPost = bits == 8 ? p.biasPost : p.biasPost * unit;
    int clipped = 0;
    float total = 0.f;
    for (int j = 0; j < 16; ++j) {
        const int sx = j % 4, sy = j / 4;
        std::array<int, 3> mean{{32 << down, 32 << down, 32 << down}};
        for (int y = 8 * sy; y < 8 * sy + 8; ++y)
            for (int x = 8 * sx; x < 8 * sx + 8; ++x)
                for (int c = 0; c < 3; ++c) mean[c] += base.px[(y * kTile + x) * 3 + c];
        for (int& m : mean) m >>= 6 + down;
        if (base.order == ChromaOrder::VU) std::swap(mean[1], mean[2]);
        clipped += (mean[0] < 5 || mean[0] > 245);
        const auto& g = gains[j];
        out.lastGain = {g[0], (g[1] + g[2]) * .5f, g[3]};
        const float lumGain = (77.f * g[0] + 29.f * g[3]) + 150.f * out.lastGain[1];
        const float noise = lut.at(mean) + biasPre;
        const float spatial = p.spatialBias + lumGain * (p.spatialScale * (1.f / 256));
        out.local[j] = biasPost + noise * spatial;
        total += out.local[j];
    }
    out.tile = total * (p.tileStd * (1.f / 16));
    out.nearClip = clipped > 3;
    return out;
}

/** Row means (first 32) and column means (next 32) of a tile's Y, integer {@code sum >> 5}. */
inline std::array<uint8_t, 64> projections(const Luma8& tile) {
    std::array<uint8_t, 64> out{};
    for (int i = 0; i < kTile; ++i) {
        unsigned col = 0, row = 0;
        for (int j = 0; j < kTile; ++j) {
            col += tile[j * kTile + i];
            row += tile[i * kTile + j];
        }
        out[i] = uint8_t(row >> 5);
        out[kTile + i] = uint8_t(col >> 5);
    }
    return out;
}

/** The 1-D shift (-8..7) of the centre 16 samples of {@code alt} against {@code base} (projections of 32), smallest SAD. */
inline int projectionShift(const uint8_t* base, const uint8_t* alt) {
    int best = -8, bestCost = 16 * 255 + 1;
    for (int d = -8; d < 8; ++d) {
        int sad = 0;
        for (int i = 8; i < 24; ++i) sad += std::abs(int(base[i]) - int(alt[i + d]));
        if (sad < bestCost) {
            bestCost = sad;
            best = d;
        }
    }
    return best;
}

enum class Forgiveness { Rounded11, Weighted11 };
struct Band { uint8_t middle, radius; };
using Bands = std::array<Band, kTilePixels>;

/** The base's per-pixel luma band (middle, radius) from the four cross neighbours, pulled 11/16 towards them. */
inline Bands forgiveness(const Luma8& base, Forgiveness mode) {
    Bands out{};
    auto at = [&](int x, int y) { return int(base[y * kTile + x]); };
    for (int y = 0; y < kTile; ++y) {
        for (int x = 0; x < kTile; ++x) {
            const int nextY = std::min(y + 1, kTile - 1);
            const int rightY = (x == kTile - 1 && mode == Forgiveness::Rounded11) ? nextY : y;
            const int c = at(x, y), up = at(x, std::max(y - 1, 0)), down = at(x, nextY);
            const int left = at(std::max(x - 1, 0), y), right = at(std::min(x + 1, kTile - 1), rightY);
            int lo = std::min({up, down, left, right}), hi = std::max({up, down, left, right});
            if (mode == Forgiveness::Rounded11) {
                int a = (c + lo) >> 1, b = (a + lo) >> 1;
                lo = (((a + b) >> 1) + b) >> 1;
                a = (c + hi + 1) >> 1;
                b = (a + hi + 1) >> 1;
                hi = (((a + b + 1) >> 1) + b + 1) >> 1;
                lo = std::min(lo, c);
                hi = std::max(hi, c);
            } else {
                lo = (5 * c + 11 * lo) >> 4;
                hi = (5 * c + 11 * hi) >> 4;
            }
            out[y * kTile + x] = {uint8_t((lo + hi) >> 1), uint8_t((hi - lo) >> 1)};
        }
    }
    return out;
}

inline float lumaExcess(const Bands& bands, const Luma8& filteredDonor) {
    uint32_t sum = 0;
    for (int i = 0; i < kTilePixels; ++i) {
        const int d = std::max(0, std::abs(int(filteredDonor[i]) - bands[i].middle) - bands[i].radius);
        sum += uint32_t(d * d);
    }
    return float(sum) * (1.f / 1024);
}

inline int tileColourDistance(const std::array<uint16_t, 3>& a, const std::array<uint16_t, 3>& b) {
    return std::abs(int(a[0]) - b[0]) + 2 * std::abs(int(a[1]) - b[1]) + std::abs(int(a[2]) - b[2]);
}

/** The soft limit: within it the tile stays; past it its scale shrinks, and past (limit / (steepness - 1)) more it goes. */
inline bool attenuate(float metric, float limit, float steepness, float& scale) {
    if (metric <= limit) return true;
    if (limit <= 0.f) return false;
    const float k = steepness - 1.f;
    const float loss = (metric / limit) * k - k;
    if (loss > 1.f) return false;
    scale *= 1.f - loss;
    return true;
}

inline int colourLimit(float denominator, float tolerance, float threshold, float colourAdjustment, float metadataScale) {
    const int initial = int(std::round(std::min(4096.f / denominator, 2500.f) * tolerance));
    const int adjusted = initial + int(threshold * (colourAdjustment * 200.f));
    return int(std::round(float(adjusted) * metadataScale));
}

/** The donor's Y at (ox, oy) after the 2 x 2 rounding average (edge repeated), on the 8-bit scale. */
template <typename S>
Luma8 smoothedDonor(const Frame420<S>& f, int ox, int oy) {
    Luma8 out{};
    const int down = f.bits - 8;
    for (int j = 0; j < kTile; ++j)
        for (int i = 0; i < kTile; ++i) {
            const int x0 = ox + i, y0 = oy + j;
            const int x1 = std::min(x0 + 1, f.width - 1), y1 = std::min(y0 + 1, f.height - 1);
            const int a = ((int(f.y.at(x0, y0)) >> down) + (int(f.y.at(x1, y0)) >> down) + 1) >> 1;
            const int b = ((int(f.y.at(x0, y1)) >> down) + (int(f.y.at(x1, y1)) >> down) + 1) >> 1;
            out[j * kTile + i] = uint8_t((a + b + 1) >> 1);
        }
    return out;
}

// The rounding dither of the average (the document's table: 184 bytes, block s starts at ((3 + 13 s) & 127)).
inline constexpr std::array<uint8_t, 184> kDither{{
    181, 60,  30,  155, 115, 117, 169, 197, 89,  169, 106, 215, 213, 65,  157, 149, 138, 222, 67,  81,  30,  240, 165,
    122, 163, 139, 165, 139, 184, 133, 254, 55,  27,  28,  16,  103, 114, 93,  195, 160, 197, 238, 249, 49,  35,  178,
    24,  134, 135, 220, 124, 100, 171, 189, 133, 89,  38,  150, 67,  11,  193, 62,  113, 176, 91,  188, 101, 174, 180,
    113, 5,   84,  108, 69,  50,  210, 110, 227, 100, 196, 101, 206, 193, 96,  55,  202, 243, 83,  171, 112, 213, 196,
    42,  220, 253, 131, 226, 150, 39,  51,  104, 191, 211, 202, 81,  136, 23,  28,  34,  173, 126, 48,  126, 37,  14,
    217, 143, 237, 178, 149, 208, 225, 253, 0,   221, 156, 253, 135, 122, 205, 58,  127, 230, 147, 216, 189, 150, 63,
    170, 21,  160, 169, 186, 228, 251, 196, 148, 237, 148, 4,   30,  220, 123, 216, 53,  141, 161, 8,   157, 92,  12,
    125, 49,  31,  52,  37,  48,  10,  162, 72,  137, 177, 127, 137, 113, 31,  125, 218, 223, 69,  53,  144, 163, 106,
}};

template <typename S>
struct DonorTile {
    const Tile<S>* tile;
    float scale;
    bool disabled;
};

template <typename S>
struct TileResult {
    std::array<S, 3 * kTilePixels> planar{};
    std::array<uint8_t, kTilePixels / 2> countPacked{};
    std::array<uint8_t, kTilePixels> rejects{};
    uint8_t eligible = 0;
};

/**
 * One tile: the base's bytes plus every donor pixel whose |Y - Ybase| is within its block's integer threshold (the profile's
 * order of the products; {@code <=}), each accepted donor weight 1, dithered division. {@code bits}: the samples' depth.
 */
template <typename S>
void mergeTile(Profile profile, const Tile<S>& base, const DonorTile<S>* donors, std::size_t donorCount, unsigned requested,
               const TileThresholds& t, float pixelMultiplier, Divide divide, int bits, TileResult<S>& out) {
    if (requested < 1 || requested > 16 || donorCount >= requested || (divide == Divide::Q15 && requested >= 16))
        throw std::invalid_argument("invalid frame count");
    const int maxCode = (1 << bits) - 1, down = bits - 8;
    out.countPacked.fill(0);
    out.rejects.fill(0);
    out.eligible = 1;
    std::array<std::array<int, 16>, 15> maxDifference{};
    for (std::size_t j = 0; j < donorCount; ++j) {
        const DonorTile<S>& d = donors[j];
        if (d.disabled) continue;
        if (d.tile == nullptr || d.tile->order != base.order) throw std::invalid_argument("incompatible donor tile");
        ++out.eligible;
        for (int i = 0; i < 16; ++i) {
            const float local = t.local[i];
            const float v = profile == Profile::P27 ? (local * pixelMultiplier) * d.scale : (d.scale * pixelMultiplier) * local;
            if (!std::isfinite(v) || v < 0.f) throw std::invalid_argument("invalid pixel threshold");
            maxDifference[j][i] = int(std::min(v, float(maxCode)));
        }
    }
    const int countShift = requested == 16 ? 1 : 0;
    const int u = base.order == ChromaOrder::UV ? 1 : 2, v = 3 - u;
    for (int i = 0; i < kTilePixels; ++i) {
        const int x = i % kTile, y = i / kTile;
        const int block = x / 8 + 4 * (y / 8);
        const int offset = 3 * i;
        std::array<uint32_t, 3> sum{{base.px[offset], base.px[offset + 1], base.px[offset + 2]}};
        const int referenceY = base.px[offset];
        unsigned n = 1;
        for (std::size_t j = 0; j < donorCount; ++j) {
            const DonorTile<S>& d = donors[j];
            if (d.disabled) continue;
            const S* p = d.tile->px.data() + offset;
            if (std::abs(int(p[0]) - referenceY) > maxDifference[j][block]) {
                ++out.rejects[i];
                continue;
            }
            ++n;
            for (int c = 0; c < 3; ++c) sum[c] += p[c];
        }
        const unsigned index = ((3 + 13 * block) & 127) + 8 * (y & 7) + (x & 7);
        const uint32_t add = (uint32_t(n) * (uint32_t(kDither[index]) << down)) >> 8;
        auto div = [&](uint32_t value) {
            value += add;
            return S(divide == Divide::Q15 ? uint32_t((uint64_t(value) * ((32768 + n - 1) / n)) >> 15) : value / n);
        };
        out.planar[i] = div(sum[0]);
        out.planar[kTilePixels + i] = div(sum[u]);
        out.planar[2 * kTilePixels + i] = div(sum[v]);
        out.countPacked[i / 2] |= uint8_t((n >> countShift) << ((x & 1) ? 0 : 4));
    }
}

struct DonorPlacement {
    int gx = 0, gy = 0;
    uint8_t dx = 128, dy = 128;
    float scale = 1.f;
    bool disabled = false;
};

struct TilePlan {
    TileThresholds thresholds;
    std::vector<DonorPlacement> donors;
};

template <typename S>
struct MergedFrame {
    int width = 0, height = 0;
    std::vector<S> planar;  // Y, then U, then V (U / V: the base's chroma order resolved)
    std::vector<uint8_t> countPacked, eligible;
};

inline void parallelRows(int rows, int threads, const std::function<void(int)>& body) {
    threads = std::max(1, std::min(threads, rows));
    if (threads == 1) {
        for (int r = 0; r < rows; ++r) body(r);
        return;
    }
    std::atomic<int> next{0};
    std::vector<std::thread> pool;
    pool.reserve(threads);
    for (int t = 0; t < threads; ++t)
        pool.emplace_back([&] {
            for (int r; (r = next.fetch_add(1)) < rows;) body(r);
        });
    for (auto& t : pool) t.join();
}

/**
 * The whole prepared frame (the document's merge of a prepared burst): {@code base} and {@code donors} over the same working area
 * (sides multiples of 32), one plan per tile with every donor's placement; the planar YUV444 out with its maps.
 */
template <typename S>
MergedFrame<S> mergePrepared(Profile profile, const Frame420<S>& base, const Frame420<S>* donors, std::size_t donorCount,
                             const std::vector<TilePlan>& plans, unsigned requested, float pixelMultiplier, Divide divide,
                             int threads = 1) {
    if (base.width <= 0 || base.height <= 0 || base.width % kTile != 0 || base.height % kTile != 0 || requested < 1 ||
        requested > 16 || donorCount >= requested)
        throw std::invalid_argument("invalid prepared burst");
    const int nx = base.width / kTile, ny = base.height / kTile;
    const std::size_t planeSize = std::size_t(base.width) * base.height, tileCount = std::size_t(nx) * ny;
    if (plans.size() != tileCount) throw std::invalid_argument("invalid tile grid");
    for (std::size_t j = 0; j < donorCount; ++j)
        if (donors[j].order != base.order) throw std::invalid_argument("normalize input chroma order");
    MergedFrame<S> out;
    out.width = base.width;
    out.height = base.height;
    out.planar.assign(3 * planeSize, S(0));
    out.countPacked.assign(planeSize / 2, 0);
    out.eligible.assign(tileCount, 0);
    const Matrix3 matrix = colourCheckMatrix(base.order);
    std::atomic<bool> failed{false};
    parallelRows(ny, threads, [&](int ty) {
        std::vector<Tile<S>> alt(donorCount);
        std::vector<DonorTile<S>> candidates(donorCount);
        Tile<S> baseTile;
        TileResult<S> tile;
        for (int tx = 0; tx < nx; ++tx) {
            const std::size_t index = std::size_t(ty) * nx + tx;
            const TilePlan& plan = plans[index];
            if (plan.donors.size() != donorCount || !extractTile(base, tx * kTile, ty * kTile, matrix, baseTile)) {
                failed = true;
                return;
            }
            for (std::size_t j = 0; j < donorCount; ++j) {
                const DonorPlacement& d = plan.donors[j];
                const int ox = kTile * tx + d.gx + int(d.dx) - 128, oy = kTile * ty + d.gy + int(d.dy) - 128;
                const bool off = d.disabled || !extractTile(donors[j], ox, oy, matrix, alt[j]);
                candidates[j] = {&alt[j], d.scale, off};
            }
            mergeTile(profile, baseTile, candidates.data(), donorCount, requested, plan.thresholds, pixelMultiplier, divide,
                      base.bits, tile);
            out.eligible[index] = tile.eligible;
            for (int y = 0; y < kTile; ++y) {
                const std::size_t offset = (std::size_t(ty) * kTile + y) * base.width + std::size_t(tx) * kTile;
                for (int c = 0; c < 3; ++c)
                    std::copy_n(tile.planar.data() + c * kTilePixels + y * kTile, kTile, out.planar.data() + c * planeSize + offset);
                std::copy_n(tile.countPacked.data() + y * kTile / 2, kTile / 2, out.countPacked.data() + offset / 2);
            }
        }
    });
    if (failed) throw std::invalid_argument("invalid tile plan");
    return out;
}

// ======================================================================================================================
// Ours from here: alignment, the burst's own noise LUT, the tile checks' driver, chroma, the linear decode and the bracket.
// ======================================================================================================================

/** An 8-bit luma image (the frame's Y on the 8-bit scale, a bracket frame's remapped to the base's exposure). */
struct Luma {
    int width = 0, height = 0;
    std::vector<uint8_t> v;
    uint8_t at(int x, int y) const { return v[std::size_t(y) * width + x]; }
};

inline Luma halve(const Luma& in) {
    Luma out;
    out.width = std::max(1, in.width / 2);
    out.height = std::max(1, in.height / 2);
    out.v.resize(std::size_t(out.width) * out.height);
    for (int y = 0; y < out.height; ++y)
        for (int x = 0; x < out.width; ++x) {
            const int x0 = std::min(2 * x, in.width - 1), x1 = std::min(2 * x + 1, in.width - 1);
            const int y0 = std::min(2 * y, in.height - 1), y1 = std::min(2 * y + 1, in.height - 1);
            out.v[std::size_t(y) * out.width + x] = uint8_t((in.at(x0, y0) + in.at(x1, y0) + in.at(x0, y1) + in.at(x1, y1) + 2) >> 2);
        }
    return out;
}

/** Mean |a - b| over the overlap of b shifted by (dx, dy) (every {@code step}-th sample); a large value when they barely overlap. */
inline double meanAbs(const Luma& a, const Luma& b, int dx, int dy, int step) {
    const int x0 = std::max(0, -dx), x1 = std::min(a.width, b.width - dx);
    const int y0 = std::max(0, -dy), y1 = std::min(a.height, b.height - dy);
    if (x1 - x0 < a.width / 2 || y1 - y0 < a.height / 2) return 1e30;
    uint64_t sum = 0, n = 0;
    for (int y = y0; y < y1; y += step) {
        const uint8_t* pa = a.v.data() + std::size_t(y) * a.width;
        const uint8_t* pb = b.v.data() + std::size_t(y + dy) * b.width + dx;
        for (int x = x0; x < x1; x += step) {
            sum += unsigned(std::abs(int(pa[x]) - int(pb[x])));
            ++n;
        }
    }
    return n ? double(sum) / double(n) : 1e30;
}

struct Shift { int x = 0, y = 0; };

/** The global translation of {@code donor} against {@code base}: exhaustive at the coarsest level, then +-1 at each finer one. */
inline Shift globalShift(const Luma& base, const Luma& donor, int radius) {
    std::vector<Luma> pb{base}, pd{donor};
    while (pb.back().width > 384 && pb.back().height > 288 && pb.size() < 6) {
        pb.push_back(halve(pb.back()));
        pd.push_back(halve(pd.back()));
    }
    const int top = int(pb.size()) - 1;
    const int r = std::max(2, (radius + (1 << top) - 1) >> top);
    Shift best;
    double bestCost = 1e300;
    for (int dy = -r; dy <= r; ++dy)
        for (int dx = -r; dx <= r; ++dx) {
            const double c = meanAbs(pb[top], pd[top], dx, dy, 1) + 1e-3 * (std::abs(dx) + std::abs(dy));
            if (c < bestCost) {
                bestCost = c;
                best = {dx, dy};
            }
        }
    for (int level = top - 1; level >= 0; --level) {
        Shift centre{2 * best.x, 2 * best.y};
        bestCost = 1e300;
        const int step = level == 0 ? 2 : 1;
        for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
                const double c = meanAbs(pb[level], pd[level], centre.x + dx, centre.y + dy, step);
                if (c < bestCost) {
                    bestCost = c;
                    best = {centre.x + dx, centre.y + dy};
                }
            }
    }
    return best;
}

/** True when a donor tile at origin (x, y) of a frame of {@code w} x {@code h} can be read (the extraction's own rule). */
inline bool readable(int x, int y, int w, int h) {
    return x >= 0 && y >= 0 && x + kTile + (x & 1) <= w && y + kTile + (y & 1) <= h;
}

inline uint32_t tileSad(const Luma& base, const Luma& donor, int bx, int by, int ox, int oy) {
    uint32_t sad = 0;
    for (int j = 0; j < kTile; ++j) {
        const uint8_t* a = base.v.data() + std::size_t(by + j) * base.width + bx;
        const uint8_t* b = donor.v.data() + std::size_t(oy + j) * donor.width + ox;
        for (int i = 0; i < kTile; ++i) sad += unsigned(std::abs(int(a[i]) - int(b[i])));
    }
    return sad;
}

inline Luma8 lumaTile(const Luma& l, int x, int y) {
    Luma8 t{};
    for (int j = 0; j < kTile; ++j) std::memcpy(t.data() + j * kTile, l.v.data() + std::size_t(y + j) * l.width + x, kTile);
    return t;
}

struct Alignment {
    Shift global;
    std::vector<int8_t> local;  // dx, dy per tile, raster order
    double meanSad = 0;         // per pixel, of the chosen placements
};

/**
 * Per tile of the working grid ({@code nx} x {@code ny} tiles from the origin): the residual shift of the donor tile after the
 * global one - candidates (none, the projections' estimate, the left and the upper neighbour's) verified by the Y SAD, the best
 * refined by +-1 steps, all within +-{@code radius}.
 */
inline Alignment alignTiles(const Luma& base, const Luma& donor, int nx, int ny, int globalRadius, int radius) {
    Alignment a;
    a.global = globalShift(base, donor, globalRadius);
    a.local.assign(std::size_t(nx) * ny * 2, 0);
    double total = 0;
    long counted = 0;
    for (int ty = 0; ty < ny; ++ty)
        for (int tx = 0; tx < nx; ++tx) {
            const int bx = tx * kTile, by = ty * kTile;
            const int gx = bx + a.global.x, gy = by + a.global.y;
            std::vector<Shift> candidates{{0, 0}};
            if (readable(gx, gy, donor.width, donor.height)) {
                const auto pb = projections(lumaTile(base, bx, by)), pd = projections(lumaTile(donor, gx, gy));
                candidates.push_back({projectionShift(pb.data() + kTile, pd.data() + kTile), projectionShift(pb.data(), pd.data())});
            }
            const std::size_t i = std::size_t(ty) * nx + tx;
            if (tx > 0) candidates.push_back({a.local[2 * (i - 1)], a.local[2 * (i - 1) + 1]});
            if (ty > 0) candidates.push_back({a.local[2 * (i - nx)], a.local[2 * (i - nx) + 1]});
            Shift best{0, 0};
            uint32_t bestSad = UINT32_MAX;
            auto tryShift = [&](Shift s) {
                if (std::abs(s.x) > radius || std::abs(s.y) > radius) return;
                const int ox = gx + s.x, oy = gy + s.y;
                if (!readable(ox, oy, donor.width, donor.height)) return;
                const uint32_t sad = tileSad(base, donor, bx, by, ox, oy) + 4u * unsigned(std::abs(s.x) + std::abs(s.y));
                if (sad < bestSad) {
                    bestSad = sad;
                    best = s;
                }
            };
            for (Shift s : candidates) tryShift(s);
            for (int step = 0; step < radius && bestSad != UINT32_MAX; ++step) {
                const Shift c = best;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx) tryShift({c.x + dx, c.y + dy});
                if (best.x == c.x && best.y == c.y) break;
            }
            a.local[2 * i] = int8_t(best.x);
            a.local[2 * i + 1] = int8_t(best.y);
            if (bestSad != UINT32_MAX) {
                total += bestSad;
                ++counted;
            }
        }
    a.meanSad = counted ? total / (double(counted) * kTilePixels) : 0;
    return a;
}

/** Gradient energy of a luma image (every 2nd sample): the sharpest normal frame becomes the base. */
inline double sharpness(const Luma& l) {
    double s = 0;
    for (int y = 1; y < l.height - 1; y += 2)
        for (int x = 1; x < l.width - 1; x += 2) {
            const int gx = int(l.at(x + 1, y)) - int(l.at(x - 1, y)), gy = int(l.at(x, y + 1)) - int(l.at(x, y - 1));
            s += double(gx * gx + gy * gy);
        }
    return s;
}

// ---- the burst's own noise ----------------------------------------------------------------------------------------------

/** sigma of one frame's Y (sample units) per 8-bit level bin of 8 (32 bins), and how it was found. */
struct NoiseCurve {
    std::array<float, 32> sigma{};
    std::array<int, 32> blocks{};
    int measuredBins = 0, totalBlocks = 0;
    bool measured = false;      // enough flat blocks: our measurement (the model only fills the gaps)
    float modelRatio = 1.f;     // measured / model where both exist (the camera's NOISE_PROFILE through the capture curve)
};

/** Collects base vs donor differences of the flat 8 x 8 blocks of aligned tiles (both frames at one exposure). */
struct NoiseCollector {
    std::array<std::vector<float>, 32> variances;

    /**
     * {@code base}, {@code donor}: aligned 32 x 32 Y tiles in sample units, {@code down}: bits - 8. A block counts when the
     * difference looks like noise - the base's own spread within it is at most ~1.6x the difference's half variance and at least
     * half of it (texture or misalignment make them part) - and it is not near the ends of the range.
     */
    template <typename S>
    void add(const Tile<S>& base, const Tile<S>& donor, int down, int maxCode) {
        for (int b = 0; b < 16; ++b) {
            const int sx = (b % 4) * 8, sy = (b / 4) * 8;
            double sb = 0, sbb = 0, sd = 0, sdd = 0;
            int lo = maxCode, hi = 0;
            for (int y = sy; y < sy + 8; ++y)
                for (int x = sx; x < sx + 8; ++x) {
                    const int vb = base.px[(y * kTile + x) * 3], vd = donor.px[(y * kTile + x) * 3];
                    lo = std::min(lo, std::min(vb, vd));
                    hi = std::max(hi, std::max(vb, vd));
                    const double d = double(vd) - double(vb);
                    sb += vb;
                    sbb += double(vb) * vb;
                    sd += d;
                    sdd += d * d;
                }
            if (lo <= (2 << down) || hi >= maxCode - (2 << down)) continue;  // clipped: no noise there
            const double mean = sb / 64.0, varBase = std::max(0.0, sbb / 64.0 - mean * mean);
            const double half = std::max(0.0, (sdd / 64.0 - (sd / 64.0) * (sd / 64.0)) * 0.5) * 64.0 / 63.0;
            if (half <= 0) continue;
            const double ratio = varBase / half;
            if (ratio > 1.6 || ratio < 0.5) continue;
            const int bin = std::min(31, int(mean) >> (3 + down));
            variances[bin].push_back(float(half));
        }
    }

    void merge(const NoiseCollector& other) {
        for (int i = 0; i < 32; ++i) variances[i].insert(variances[i].end(), other.variances[i].begin(), other.variances[i].end());
    }
};

/**
 * The curve from the collected blocks: per bin the median variance of at least {@code minBlocks} blocks; bins without them take
 * {@code model} (the fallback curve: the camera's noise profile through the capture curve) times the median measured / model
 * ratio (no model: the measured neighbours interpolated); then a [1 2 1] smoothing. Fewer than {@code minBins} measured bins:
 * the model alone ({@code defaultSigma} without one either).
 */
inline NoiseCurve noiseCurve(NoiseCollector& c, const std::array<float, 32>& model, float defaultSigma = 1.5f, int minBlocks = 12,
                             int minBins = 4) {
    NoiseCurve out;
    std::array<float, 32> measured{};
    std::vector<float> ratios;
    for (int i = 0; i < 32; ++i) {
        auto& v = c.variances[i];
        out.blocks[i] = int(v.size());
        out.totalBlocks += int(v.size());
        if (int(v.size()) < minBlocks) continue;
        std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
        // the median of a chi-square sample sits below its mean (~0.9 at 63 degrees of freedom)
        measured[i] = std::sqrt(v[v.size() / 2] / 0.9895f);
        ++out.measuredBins;
        if (model[i] > 0) ratios.push_back(measured[i] / model[i]);
    }
    out.measured = out.measuredBins >= minBins;
    if (!ratios.empty()) {
        std::nth_element(ratios.begin(), ratios.begin() + ratios.size() / 2, ratios.end());
        out.modelRatio = ratios[ratios.size() / 2];
    }
    // the gaps: the model times the measured ratio where there is a model, else the measured neighbours interpolated
    std::array<float, 32> raw{};
    for (int i = 0; i < 32; ++i) {
        if (out.measured && measured[i] > 0) {
            raw[i] = measured[i];
            continue;
        }
        if (model[i] > 0) {
            raw[i] = model[i] * (out.measured ? out.modelRatio : 1.f);
            continue;
        }
        int lo = i - 1, hi = i + 1;
        while (lo >= 0 && !(measured[lo] > 0)) --lo;
        while (hi < 32 && !(measured[hi] > 0)) ++hi;
        if (out.measuredBins == 0) raw[i] = defaultSigma;
        else if (lo < 0) raw[i] = measured[hi];
        else if (hi >= 32) raw[i] = measured[lo];
        else raw[i] = measured[lo] + (measured[hi] - measured[lo]) * float(i - lo) / float(hi - lo);
    }
    for (int i = 0; i < 32; ++i) {
        const float a = raw[std::max(0, i - 1)], b = raw[i], d = raw[std::min(31, i + 1)];
        out.sigma[i] = std::max(0.25f, (a + 2.f * b + d) * 0.25f);
    }
    return out;
}

/** The LUT in the document's layout from a Y-only curve (every U / V bin of a Y bin holds its sigma). */
inline std::vector<float> lutOf(const NoiseCurve& curve) {
    std::vector<float> lut(kLutSize);
    for (std::size_t i = 0; i < kLutSize; ++i) lut[i] = curve.sigma[i & 31];
    return lut;
}

// ---- the capture curve and the decode to linear light ---------------------------------------------------------------------

/** How the frames' R'G'B' map to linear light. */
struct Transfer {
    enum Kind { SRGB, POINTS, HLG, LINEAR } kind = SRGB;
    std::vector<float> in, out;  // POINTS: the tonemap curve the camera was asked for (linear in -> encoded out), increasing
    std::vector<float> table;    // the decode, 4097 entries over the encoded 0..1

    static float srgbDecode(float e) { return e <= 0.04045f ? e / 12.92f : std::pow((e + 0.055f) / 1.055f, 2.4f); }
    static float hlgDecode(float e) {  // BT.2100 HLG inverse OETF (scene light 0..1)
        const float a = 0.17883277f, b = 0.28466892f, c = 0.55991073f;
        return e <= 0.5f ? e * e / 3.f : (std::exp((e - c) / a) + b) / 12.f;
    }
    float decodeExact(float e) const {
        e = std::clamp(e, 0.f, 1.f);
        switch (kind) {
            case LINEAR: return e;
            case HLG: return hlgDecode(e);
            case POINTS: {
                if (out.size() < 2) return srgbDecode(e);
                if (e <= out.front()) return in.front();
                for (std::size_t i = 1; i < out.size(); ++i)
                    if (e <= out[i]) {
                        const float span = out[i] - out[i - 1];
                        const float t = span > 0 ? (e - out[i - 1]) / span : 0.f;
                        return in[i - 1] + t * (in[i] - in[i - 1]);
                    }
                return in.back();
            }
            default: return srgbDecode(e);
        }
    }
    void build() {
        table.resize(4097);
        for (int i = 0; i <= 4096; ++i) table[i] = decodeExact(float(i) / 4096.f);
    }
    float decode(float e) const {
        const float p = std::clamp(e, 0.f, 1.f) * 4096.f;
        const int i = std::min(4095, int(p));
        const float t = p - float(i);
        return table[i] + t * (table[i + 1] - table[i]);
    }
    /** d(linear)/d(encoded) at {@code e} (for the fallback noise curve). */
    float slope(float e) const {
        const float h = 1.f / 256.f, a = std::max(0.f, e - h), b = std::min(1.f, e + h);
        return (decodeExact(b) - decodeExact(a)) / std::max(1e-6f, b - a);
    }
};

/** The YCbCr of the frames: full-range BT.601 (JFIF, the 8-bit YUV_420_888) or narrow-range BT.2020 (10-bit HLG P010). */
struct Decoder {
    bool bt2020 = false, narrow = false;
    /** The range is the stream's declared one (P667: the Image's dataspace): no guess from the frame's Y extremes. */
    bool rangeKnown = false;
    int bits = 8;
    /** Y, U, V samples (chroma order resolved) -> R'G'B' 0..1. */
    void rgbPrime(int y, int u, int v, float* rgb) const {
        const float max = float((1 << bits) - 1);
        float Y, Cb, Cr;
        if (narrow) {
            const float k = float(1 << (bits - 8));
            Y = (float(y) - 16.f * k) / (219.f * k);
            Cb = (float(u) - 128.f * k) / (224.f * k);
            Cr = (float(v) - 128.f * k) / (224.f * k);
        } else {
            const float mid = float(1 << (bits - 1));
            Y = float(y) / max;
            Cb = (float(u) - mid) / max;
            Cr = (float(v) - mid) / max;
        }
        if (bt2020) {
            rgb[0] = Y + 1.4746f * Cr;
            rgb[1] = Y - 0.16455f * Cb - 0.57135f * Cr;
            rgb[2] = Y + 1.8814f * Cb;
        } else {
            rgb[0] = Y + 1.402f * Cr;
            rgb[1] = Y - 0.344136f * Cb - 0.714136f * Cr;
            rgb[2] = Y + 1.772f * Cb;
        }
    }
};

/** Linear BT.2020 -> linear BT.709 / sRGB primaries. */
inline void bt2020To709(float* c) {
    const float r = 1.6605f * c[0] - 0.5876f * c[1] - 0.0728f * c[2];
    const float g = -0.1246f * c[0] + 1.1329f * c[1] - 0.0083f * c[2];
    const float b = -0.0182f * c[0] - 0.1006f * c[1] + 1.1187f * c[2];
    c[0] = r;
    c[1] = g;
    c[2] = b;
}

// ---- guided chroma upsampling (the merged frame's 2 x 2 chroma blocks to full resolution, steered by the merged Y) -----------

/**
 * The merge reads each pixel's chroma at ((x>>1), (y>>1)), so its chroma is constant over 2 x 2 blocks. Each pixel takes the
 * 2 x 2 nearest block means with bilinear weights times a Y similarity (Y of the pixel against the block's mean Y, width
 * {@code h} in sample units): a colour edge follows the luma edge inside the block instead of a stair of 2 px.
 */
template <typename S>
void guidedChroma(std::vector<S>& planar, int w, int h, float width, int threads = 1) {
    const int cw = (w + 1) / 2, ch = (h + 1) / 2;
    const std::size_t n = std::size_t(w) * h;
    std::vector<float> my(std::size_t(cw) * ch), mu(my.size()), mv(my.size());
    for (int j = 0; j < ch; ++j)
        for (int i = 0; i < cw; ++i) {
            float sy = 0, su = 0, sv = 0;
            int c = 0;
            for (int dy = 0; dy < 2; ++dy)
                for (int dx = 0; dx < 2; ++dx) {
                    const int x = 2 * i + dx, y = 2 * j + dy;
                    if (x >= w || y >= h) continue;
                    const std::size_t p = std::size_t(y) * w + x;
                    sy += planar[p];
                    su += planar[n + p];
                    sv += planar[2 * n + p];
                    ++c;
                }
            const std::size_t q = std::size_t(j) * cw + i;
            my[q] = sy / c;
            mu[q] = su / c;
            mv[q] = sv / c;
        }
    const float inv = 1.f / (2.f * width * width);
    const float maxCode = float(std::numeric_limits<S>::max());
    std::vector<S> outU(n), outV(n);
    parallelRows(h, threads, [&](int y) {
        const float fy = (float(y) - 0.5f) * 0.5f;
        const int j0 = std::clamp(int(std::floor(fy)), 0, ch - 1), j1 = std::min(ch - 1, j0 + 1);
        const float ty = std::clamp(fy - float(j0), 0.f, 1.f);
        for (int x = 0; x < w; ++x) {
            const float fx = (float(x) - 0.5f) * 0.5f;
            const int i0 = std::clamp(int(std::floor(fx)), 0, cw - 1), i1 = std::min(cw - 1, i0 + 1);
            const float tx = std::clamp(fx - float(i0), 0.f, 1.f);
            const std::size_t p = std::size_t(y) * w + x;
            const float Y = planar[p];
            float su = 0, sv = 0, sw = 0;
            const int js[2] = {j0, j1}, is[2] = {i0, i1};
            const float wy[2] = {1.f - ty, ty}, wx[2] = {1.f - tx, tx};
            for (int a = 0; a < 2; ++a)
                for (int b = 0; b < 2; ++b) {
                    const std::size_t q = std::size_t(js[a]) * cw + is[b];
                    const float d = Y - my[q];
                    const float wgt = (wy[a] * wx[b] + 1e-4f) * std::exp(-d * d * inv);
                    su += wgt * mu[q];
                    sv += wgt * mv[q];
                    sw += wgt;
                }
            outU[p] = S(std::clamp(std::lround(su / sw), 0L, long(maxCode)));
            outV[p] = S(std::clamp(std::lround(sv / sw), 0L, long(maxCode)));
        }
    });
    std::copy(outU.begin(), outU.end(), planar.begin() + n);
    std::copy(outV.begin(), outV.end(), planar.begin() + 2 * n);
}

// ---- the whole burst ------------------------------------------------------------------------------------------------------

enum class Role { Normal = 0, Short = 1, Long = 2 };

struct FrameInfo {
    Role role = Role::Normal;
    float ratio = 1.f;  // exposure (time x ISO) against the normal frames'
};

struct Settings {
    Profile profile = Profile::P31;
    int globalRadius = 64, localRadius = 6;
    float thresholdScale = 1.f;            // x the document's pixel multiplier 2.5452
    bool chromaCheck = true;
    float chromaTolerance = 1.f;
    bool lumaCheck = true;
    bool ownNoise = true;                  // the burst's own LUT (else the fallback curve alone)
    bool sharpestBase = true;
    bool guidedChroma = true;
    bool bracket = true;
    float deghost = 3.f;                   // the bracket frames' agreement: |frame - base| <= deghost x sigma of the base
    int threads = 4;
};

/** The fallback noise of one frame: Camera2's NOISE_PROFILE (S, O of the green, normalised to 1) and the merged frame's count. */
struct NoiseFallback {
    float s = 0.f, o = 0.f;
    float ispFactor = 1.f;  // the ISP's noise reduction is unknown: 1
};

struct Report {
    int base = 0, normals = 0, shorts = 0, longs = 0;
    int width = 0, height = 0, workX = 0, workY = 0, workW = 0, workH = 0;
    std::vector<Shift> globals;
    std::vector<double> sads;
    NoiseCurve noise;
    double meanCount = 1, disabledShare = 0;
    double meanCountAll = 1;
    std::vector<float> linearity;  // measured / expected ratio of every bracket frame (0: not measured)
    bool bracketUsed = false;
    std::string bracketWhy;
    double esShare = 0, longShare = 0;
    float linS = 0, linO = 0;      // the merged linear frame's own noise model (variance = s x + o), one frame
    int medianCount = 1;
    float outputScale = 1.f;       // linear output per decoded unit (HLG: its reference white to 1)
    std::string decodeNote;
    std::string text() const {
        std::ostringstream o;
        o.setf(std::ios::fixed);
        o.precision(3);
        o << "base " << base << " normals " << normals << " ES " << shorts << " L " << longs << " work " << workW << "x" << workH
          << "+" << workX << "+" << workY << " globals";
        for (std::size_t i = 0; i < globals.size(); ++i) o << " (" << globals[i].x << "," << globals[i].y << " sad " << sads[i] << ")";
        o << " noise " << (noise.measured ? "measured" : "model") << " bins " << noise.measuredBins << " blocks " << noise.totalBlocks
          << " model ratio " << noise.modelRatio << " sigma@64/128/192 " << noise.sigma[8] << "/" << noise.sigma[16] << "/"
          << noise.sigma[24] << " mean frames " << meanCount << " disabled tiles " << disabledShare;
        o << " bracket " << (bracketUsed ? "used" : "not used") << (bracketWhy.empty() ? "" : " (" + bracketWhy + ")");
        if (!linearity.empty()) {
            o << " linearity";
            for (float l : linearity) o << " x" << l;
        }
        o << " ES pixels " << esShare << " L pixels " << longShare << " median frames " << medianCount;
        if (!decodeNote.empty()) o << " decode " << decodeNote;
        return o.str();
    }
};

/** The frame's Y on the 8-bit scale, remapped through {@code map} (256 entries, identity when empty). */
template <typename S>
Luma lumaOf(const Frame420<S>& f, const std::vector<uint8_t>& map) {
    Luma l;
    l.width = f.width;
    l.height = f.height;
    l.v.resize(std::size_t(f.width) * f.height);
    const int down = f.bits - 8;
    for (int y = 0; y < f.height; ++y)
        for (int x = 0; x < f.width; ++x) {
            const int v = int(f.y.at(x, y)) >> down;
            l.v[std::size_t(y) * f.width + x] = map.empty() ? uint8_t(v) : map[std::min(255, v)];
        }
    return l;
}

template <typename S>
Frame420<S> cropped(const Frame420<S>& f, int x, int y, int w, int h) {
    Frame420<S> c = f;
    c.y.data += std::ptrdiff_t(y) * f.y.row + std::ptrdiff_t(x) * f.y.step;
    c.c0.data += std::ptrdiff_t(y / 2) * f.c0.row + std::ptrdiff_t(x / 2) * f.c0.step;
    c.c1.data += std::ptrdiff_t(y / 2) * f.c1.row + std::ptrdiff_t(x / 2) * f.c1.step;
    c.width = w;
    c.height = h;
    return c;
}

inline float luminance(const float* c) { return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]; }
inline float smoothstep(float a, float b, float x) {
    const float t = std::clamp((x - a) / (b - a), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

/**
 * The burst to linear RGB ({@code out}: width x height x 3 floats, 1 = the white of the normal frames' exposure; the bracket's
 * highlights above 1). Frames share one size, depth and chroma order. {@code counts} (w x h bytes, may be null): the frames merged
 * at each pixel (the border outside the working area: 1). A narrow-range decode of a frame whose darkest or brightest samples
 * sit well outside the narrow range (a full-range stream) decodes it as full range. Throws on invalid input.
 */
template <typename S>
Report mergeBurst(const std::vector<Frame420<S>>& frames, const std::vector<FrameInfo>& info, const Settings& set,
                  const Transfer& transfer, const Decoder& decoderIn, const NoiseFallback& fallback, float* out,
                  uint8_t* counts = nullptr) {
    if (frames.empty() || frames.size() != info.size()) throw std::invalid_argument("no frames");
    const Frame420<S>& first = frames[0];
    const int w = first.width, h = first.height, bits = first.bits, down = bits - 8, maxCode = first.maxCode();
    for (const auto& f : frames)
        if (f.width != w || f.height != h || f.bits != bits || f.order != first.order) throw std::invalid_argument("mixed frames");
    if (w < 2 * kTile || h < 2 * kTile) throw std::invalid_argument("frame too small");
    Report rep;
    rep.width = w;
    rep.height = h;
    Decoder decoder = decoderIn;
    if (decoder.bt2020 && decoder.rangeKnown) {
        rep.decodeNote = decoder.narrow ? "narrow range (declared)" : "full range (declared)";
    } else if (decoder.narrow) {
        // the 0.1 % of the darkest Y of the first frame: a narrow-range stream keeps them near its black (16 on the 8-bit scale)
        std::vector<uint32_t> hist(256, 0);
        for (int y = 0; y < h; y += 4)
            for (int x = 0; x < w; x += 4) ++hist[std::min(255, int(first.y.at(x, y)) >> down)];
        uint64_t total = 0, seen = 0;
        for (uint32_t c : hist) total += c;
        int low = 0, high = 255;
        for (; low < 256; ++low) {
            seen += hist[low];
            if (seen * 1000 >= total) break;
        }
        for (seen = 0; high > 0; --high) {
            seen += hist[high];
            if (seen * 1000 >= total) break;
        }
        // narrow range keeps Y within 16..235 (8-bit scale): darker or brighter samples than a margin round them say full range
        if (low < 8 || high > 240) {
            decoder.narrow = false;
            rep.decodeNote = "full range (Y 0.1% / 99.9% at " + std::to_string(low) + " / " + std::to_string(high) + " of 255)";
        } else {
            rep.decodeNote = "narrow range";
        }
    }
    std::vector<int> normals, shorts, longs;
    for (std::size_t i = 0; i < frames.size(); ++i)
        (info[i].role == Role::Normal ? normals : info[i].role == Role::Short ? shorts : longs).push_back(int(i));
    if (normals.empty()) throw std::invalid_argument("no normal frame");
    if (normals.size() > 16) normals.resize(16);
    rep.normals = int(normals.size());
    rep.shorts = int(shorts.size());
    rep.longs = int(longs.size());

    // the working area: whole tiles, centred, even origin (the chroma phase)
    rep.workW = w / kTile * kTile;
    rep.workH = h / kTile * kTile;
    rep.workX = ((w - rep.workW) / 2) & ~1;
    rep.workY = ((h - rep.workH) / 2) & ~1;
    const int nx = rep.workW / kTile, ny = rep.workH / kTile;

    // luma of every frame on the 8-bit scale (bracket frames remapped to the normal exposure for the alignment)
    std::vector<Luma> lumas(frames.size());
    parallelRows(int(frames.size()), set.threads, [&](int i) {
        std::vector<uint8_t> map;
        if (info[i].role != Role::Normal && info[i].ratio > 0) {
            map.resize(256);
            for (int v = 0; v < 256; ++v) {
                const float lin = transfer.decode(float(v) / 255.f) / info[i].ratio;
                // encode back through the inverse of the decode (bisection on the table)
                float lo = 0, hi = 1;
                for (int k = 0; k < 20; ++k) {
                    const float mid = 0.5f * (lo + hi);
                    (transfer.decode(mid) < lin ? lo : hi) = mid;
                }
                map[v] = uint8_t(std::clamp(std::lround(255.f * 0.5f * (lo + hi)), 0L, 255L));
            }
        }
        lumas[i] = lumaOf(frames[i], map);
    });

    // the base: the sharpest normal frame
    int base = normals[0];
    if (set.sharpestBase && normals.size() > 1) {
        double best = -1;
        for (int i : normals) {
            const double s = sharpness(lumas[i]);
            if (s > best) {
                best = s;
                base = i;
            }
        }
    }
    rep.base = base;
    std::vector<int> donors;
    for (int i : normals)
        if (i != base) donors.push_back(i);

    // the working crops (the base's frame of reference) and each frame's alignment on it
    const Frame420<S> baseWork = cropped(frames[base], rep.workX, rep.workY, rep.workW, rep.workH);
    Luma baseLuma;
    baseLuma.width = rep.workW;
    baseLuma.height = rep.workH;
    baseLuma.v.resize(std::size_t(rep.workW) * rep.workH);
    for (int y = 0; y < rep.workH; ++y)
        std::memcpy(baseLuma.v.data() + std::size_t(y) * rep.workW,
                    lumas[base].v.data() + std::size_t(y + rep.workY) * w + rep.workX, rep.workW);
    std::vector<Alignment> align(frames.size());
    std::vector<int> others;
    for (std::size_t i = 0; i < frames.size(); ++i)
        if (int(i) != base) others.push_back(int(i));
    // alignTiles compares the base's working crop with the whole donor frame: the global shift it finds is the donor's
    // coordinate of the crop's origin (the working origin plus the motion)
    parallelRows(int(others.size()), set.threads, [&](int k) {
        const int i = others[k];
        align[i] = alignTiles(baseLuma, lumas[i], nx, ny, set.globalRadius + std::max(rep.workX, rep.workY), set.localRadius);
    });
    for (int i : others) {
        rep.globals.push_back(Shift{align[i].global.x - rep.workX, align[i].global.y - rep.workY});
        rep.sads.push_back(align[i].meanSad);
    }

    const Matrix3 matrix = colourCheckMatrix(first.order);
    // the donor frame's coordinate of the working tile (tx, ty)
    auto origin = [&](int i, int tx, int ty) {
        const std::size_t t = std::size_t(ty) * nx + tx;
        return Shift{tx * kTile + align[i].global.x + align[i].local[2 * t], ty * kTile + align[i].global.y + align[i].local[2 * t + 1]};
    };

    // the fallback curve: NOISE_PROFILE through the capture curve, per 8-bit level bin, in sample units
    std::array<float, 32> model{};
    for (int b = 0; b < 32; ++b) {
        const float e = (float(b) * 8.f + 4.f) / 255.f;
        const float x = transfer.decode(e);
        const float sx = std::sqrt(std::max(0.f, fallback.s * x + fallback.o)) * fallback.ispFactor;
        model[b] = sx / std::max(1e-4f, transfer.slope(e)) * float(maxCode);
    }
    // our noise LUT: base vs each normal donor, flat aligned blocks
    NoiseCollector collected;
    if (set.ownNoise && !donors.empty()) {
        std::vector<NoiseCollector> parts(ny);
        parallelRows(ny, set.threads, [&](int ty) {
            Tile<S> bt, dt;
            for (int tx = 0; tx < nx; ++tx) {
                if (!extractTile(baseWork, tx * kTile, ty * kTile, matrix, bt)) continue;
                for (int j : donors) {
                    const Shift o = origin(j, tx, ty);
                    if (extractTile(frames[j], o.x, o.y, matrix, dt)) parts[ty].add(bt, dt, down, maxCode);
                }
            }
        });
        for (auto& p : parts) collected.merge(p);
    }
    rep.noise = noiseCurve(collected, model, 1.5f * float(1 << down));
    const std::vector<float> lutValues = lutOf(rep.noise);
    const NoiseLut lut{lutValues.data(), lutValues.size()};
    CellGains gains;
    for (auto& g : gains) g = {1.f, 1.f, 1.f, 1.f};
    const ThresholdParams thresholdParams;
    const float pixelMultiplier = 2.5452001094818115f * set.thresholdScale;

    // per tile: thresholds and each donor's placement after the colour and luma checks
    std::vector<TilePlan> plans(std::size_t(nx) * ny);
    std::vector<Frame420<S>> donorFrames;
    for (int j : donors) donorFrames.push_back(frames[j]);
    std::atomic<long> disabled{0};
    parallelRows(ny, set.threads, [&](int ty) {
        Tile<S> bt, dt;
        long off = 0;
        for (int tx = 0; tx < nx; ++tx) {
            TilePlan& plan = plans[std::size_t(ty) * nx + tx];
            extractTile(baseWork, tx * kTile, ty * kTile, matrix, bt);
            plan.thresholds = tileThresholds(bt, bits, lut, gains, thresholdParams);
            Luma8 b8{};
            for (int p = 0; p < kTilePixels; ++p) b8[p] = uint8_t(int(bt.px[3 * p]) >> down);
            const Bands bands = forgiveness(b8, Forgiveness::Rounded11);
            const float tile8 = plan.thresholds.tile / float(1 << down);
            for (int j : donors) {
                const Shift o = origin(j, tx, ty);
                DonorPlacement d;
                // the document's plan: the donor is read at 32 tx + gx + dx - 128 (dx = 128: the whole offset in g)
                d.gx = o.x - tx * kTile;
                d.gy = o.y - ty * kTile;
                d.dx = 128;
                d.dy = 128;
                if (!extractTile(frames[j], o.x, o.y, matrix, dt)) d.disabled = true;
                if (!d.disabled && set.chromaCheck) {
                    const int limit = colourLimit(1.f, set.chromaTolerance, tile8, 0.f, 1.f);
                    if (!attenuate(float(tileColourDistance(bt.rgbQ8, dt.rgbQ8)), float(limit), 50.f, d.scale)) d.disabled = true;
                }
                if (!d.disabled && set.lumaCheck) {
                    const float limit = tile8 * tile8;
                    if (!attenuate(lumaExcess(bands, smoothedDonor(frames[j], o.x, o.y)), limit, 50.f, d.scale)) d.disabled = true;
                }
                off += d.disabled;
                plan.donors.push_back(d);
            }
        }
        disabled += off;
    });
    rep.disabledShare = donors.empty() ? 0 : double(disabled.load()) / (double(plans.size()) * donors.size());

    const unsigned requested = unsigned(donors.size() + 1);
    MergedFrame<S> merged = mergePrepared(set.profile, baseWork, donorFrames.data(), donorFrames.size(), plans, requested,
                                          pixelMultiplier, requested < 16 ? Divide::Q15 : Divide::Exact, set.threads);
    {
        uint64_t sum = 0;
        std::array<uint64_t, 17> hist{};
        for (uint8_t c : merged.countPacked) {
            sum += (c >> 4) + (c & 15);
            ++hist[c >> 4];
            ++hist[c & 15];
        }
        rep.meanCount = double(sum) / double(merged.countPacked.size() * 2);
        uint64_t seen = 0;
        for (int k = 0; k <= 16; ++k) {
            seen += hist[k];
            if (2 * seen >= merged.countPacked.size() * 2) {
                rep.medianCount = std::max(1, k);
                break;
            }
        }
        if (counts) {
            const int shift = requested == 16 ? 1 : 0;
            std::fill(counts, counts + std::size_t(w) * h, uint8_t(1));
            for (int y = 0; y < rep.workH; ++y)
                for (int x = 0; x < rep.workW; ++x) {
                    const uint8_t c = merged.countPacked[(std::size_t(y) * rep.workW + x) / 2];
                    counts[std::size_t(y + rep.workY) * w + x + rep.workX] = uint8_t(((x & 1) ? (c & 15) : (c >> 4)) << shift);
                }
        }
    }
    if (set.guidedChroma) guidedChroma(merged.planar, rep.workW, rep.workH, 2.f * rep.noise.sigma[16] + float(1 << down), set.threads);

    // HLG is scene light with its reference white at 75 % of the signal (BT.2100: 0.265): that white becomes 1, as an SDR frame's
    const float outScale = transfer.kind == Transfer::HLG ? 1.f / Transfer::hlgDecode(0.75f) : 1.f;
    rep.outputScale = outScale;
    // linear RGB of the whole frame: the working area from the merge, the border from the base alone
    const std::size_t wn = std::size_t(rep.workW) * rep.workH;
    const Frame420<S>& bf = frames[base];
    const bool vu = first.order == ChromaOrder::VU;
    parallelRows(h, set.threads, [&](int y) {
        for (int x = 0; x < w; ++x) {
            int Y, U, V;
            const int wx = x - rep.workX, wy = y - rep.workY;
            if (wx >= 0 && wy >= 0 && wx < rep.workW && wy < rep.workH) {
                const std::size_t p = std::size_t(wy) * rep.workW + wx;
                Y = merged.planar[p];
                U = merged.planar[wn + p];
                V = merged.planar[2 * wn + p];
            } else {
                Y = bf.y.at(x, y);
                U = vu ? bf.c1.at(x / 2, y / 2) : bf.c0.at(x / 2, y / 2);
                V = vu ? bf.c0.at(x / 2, y / 2) : bf.c1.at(x / 2, y / 2);
            }
            float* o = out + (std::size_t(y) * w + x) * 3;
            decoder.rgbPrime(Y, U, V, o);
            for (int c = 0; c < 3; ++c) o[c] = transfer.decode(o[c]) * outScale;
            if (decoder.bt2020) bt2020To709(o);
        }
    });
    // the merged frame's noise in linear light: variance of one frame s x + o fitted on the curve (the post pipeline's model)
    {
        double sxx = 0, sx = 0, sy = 0, sxy = 0;
        int n = 0;
        for (int b = 2; b < 30; ++b) {
            const float e = (float(b) * 8.f + 4.f) / 255.f;
            const float x = transfer.decode(e) * outScale;
            const float sl = transfer.slope(e) * outScale * rep.noise.sigma[b] / float(maxCode);
            const double var = double(sl) * sl;
            sxx += double(x) * x;
            sx += x;
            sy += var;
            sxy += double(x) * var;
            ++n;
        }
        const double det = n * sxx - sx * sx;
        double s = det != 0 ? (n * sxy - sx * sy) / det : 0, o = det != 0 ? (sy - s * sx) / n : sy / std::max(1, n);
        if (s < 0) {
            s = 0;
            o = sy / std::max(1, n);
        }
        rep.linS = float(s);
        rep.linO = float(std::max(0.0, o));
    }

    // ---- the bracket in linear light -------------------------------------------------------------------------------------
    std::vector<int> brackets = shorts;
    brackets.insert(brackets.end(), longs.begin(), longs.end());
    if (brackets.empty()) {
        rep.bracketWhy = "no bracket frames";
        return rep;
    }
    if (!set.bracket) {
        rep.bracketWhy = "off";
        return rep;
    }
    // per pixel the frame's linear RGB at the base's position (tile placement of the bracket frame)
    auto frameLinear = [&](int i, int x, int y, float* c) {
        const Frame420<S>& f = frames[i];
        const int Y = f.y.at(x, y), a = f.c0.at(x / 2, y / 2), b = f.c1.at(x / 2, y / 2);
        decoder.rgbPrime(Y, vu ? b : a, vu ? a : b, c);
        float m = 0;
        for (int k = 0; k < 3; ++k) {
            m = std::max(m, c[k]);
            c[k] = transfer.decode(c[k]) * outScale;
        }
        if (decoder.bt2020) bt2020To709(c);
        return m;  // the frame's own largest R'G'B' (its clip)
    };
    // linearity: the bracket frame's light against the base's where both are mid-range (median of the per-pixel ratios)
    for (int i : brackets) {
        std::vector<float> ratios;
        for (int ty = 0; ty < ny; ++ty)
            for (int tx = 0; tx < nx; ++tx) {
                const Shift o = origin(i, tx, ty);
                if (!readable(o.x, o.y, w, h)) continue;
                for (int j = 1; j < kTile; j += 4)
                    for (int k = 1; k < kTile; k += 4) {
                        const int bx = rep.workX + tx * kTile + k, by = rep.workY + ty * kTile + j;
                        const float* bc = out + (std::size_t(by) * w + bx) * 3;
                        float fc[3];
                        const float clip = frameLinear(i, o.x + k, o.y + j, fc);
                        const float lb = luminance(bc) / outScale, lf = luminance(fc) / outScale;  // of the decoded clip
                        const float lbExpected = lb * info[i].ratio;
                        if (lb < 0.02f || lb > 0.7f || clip > 0.92f || lf < 0.01f || lbExpected > 0.7f || lbExpected < 0.01f) continue;
                        ratios.push_back(lf / lb);
                    }
            }
        float measured = 0;
        if (ratios.size() >= 48) {
            std::nth_element(ratios.begin(), ratios.begin() + ratios.size() / 2, ratios.end());
            measured = ratios[ratios.size() / 2] / info[i].ratio;
        }
        rep.linearity.push_back(measured);
    }
    std::vector<int> usable;
    for (std::size_t k = 0; k < brackets.size(); ++k) {
        const float m = rep.linearity[k];
        if (m > 0 && std::fabs(std::log(m)) < std::log(1.25f)) usable.push_back(brackets[k]);
    }
    if (usable.empty()) {
        rep.bracketWhy = "the frames' light does not follow their exposure (the capture curve is not the one asked for)";
        return rep;
    }
    if (usable.size() < brackets.size()) rep.bracketWhy = "some frames failed the linearity check";
    rep.bracketUsed = true;
    // the base's noise in linear light at x: one frame's model over the merged count
    const float baseFrames = float(std::max(1.0, rep.meanCount));
    std::atomic<long> esPixels{0}, longPixels{0};
    parallelRows(h, set.threads, [&](int y) {
        long es = 0, lg = 0;
        const int ty = std::clamp((y - rep.workY) / kTile, 0, ny - 1);
        for (int x = 0; x < w; ++x) {
            const int tx = std::clamp((x - rep.workX) / kTile, 0, nx - 1);
            float* o = out + (std::size_t(y) * w + x) * 3;
            const float B[3] = {o[0], o[1], o[2]};
            const float lb = luminance(B);
            // the base's clip: its largest R'G'B' (re-encoded from the decode would cost; the linear max against the curve's 1)
            const float bmax = std::max({B[0], B[1], B[2]}) / outScale;  // of the decoded clip
            const float clipW = smoothstep(0.80f, 0.97f, bmax);
            const float sigma = std::sqrt(std::max(1e-10f, (rep.linS * std::max(0.f, lb) + rep.linO) / baseFrames));
            float esSum[3] = {0, 0, 0};
            int esCount = 0;
            float accum[3] = {B[0] * baseFrames, B[1] * baseFrames, B[2] * baseFrames};
            float weight = baseFrames;
            for (int i : usable) {
                const std::size_t t = std::size_t(ty) * nx + tx;
                const int fx = x - rep.workX + align[i].global.x + align[i].local[2 * t];
                const int fy = y - rep.workY + align[i].global.y + align[i].local[2 * t + 1];
                if (fx < 0 || fy < 0 || fx >= w || fy >= h) continue;
                float c[3];
                const float fmax = frameLinear(i, fx, fy, c);
                const float r = info[i].ratio;
                for (float& v : c) v /= r;
                const float lf = luminance(c);
                // agreement with the base, within its noise and the frame's own (sigma of the frame in the base's units)
                const float sf = std::sqrt(std::max(1e-10f, (rep.linS * std::max(0.f, lf * r) + rep.linO))) / r;
                const float tol = set.deghost * std::sqrt(sigma * sigma + sf * sf);
                if (r < 1.f) {
                    if (clipW <= 0.f || fmax > 0.97f) continue;
                    // where the base still holds detail the frame must agree with it; where the base is clipped it must be
                    // at least as bright as the clip (an object that came into the highlight would be darker)
                    const bool agrees = clipW < 1.f ? std::fabs(lf - lb) <= tol + 0.05f * lb : lf >= lb * 0.9f;
                    if (!agrees) continue;
                    for (int k = 0; k < 3; ++k) esSum[k] += c[k];
                    ++esCount;
                } else {
                    // the long frame where it is well below its own clip: shot noise ~ 1 / exposure, so its weight is its ratio
                    if (fmax > 0.92f || clipW > 0.f) continue;
                    const float gate = 1.f - smoothstep(0.6f, 0.9f, fmax);
                    const float diff = std::fabs(lf - lb);
                    const float accept = gate * std::clamp(2.f - diff / std::max(1e-6f, tol), 0.f, 1.f);
                    if (accept <= 0.f) continue;
                    const float wl = r * accept;
                    for (int k = 0; k < 3; ++k) accum[k] += wl * c[k];
                    weight += wl;
                    ++lg;
                }
            }
            float res[3];
            for (int k = 0; k < 3; ++k) res[k] = accum[k] / weight;
            if (esCount > 0) {
                ++es;
                for (int k = 0; k < 3; ++k) {
                    const float e = esSum[k] / float(esCount);
                    res[k] = (1.f - clipW) * res[k] + clipW * std::max(e, clipW >= 1.f ? B[k] : 0.f);
                }
            }
            o[0] = res[0];
            o[1] = res[1];
            o[2] = res[2];
        }
        esPixels += es;
        longPixels += lg;
    });
    rep.esShare = double(esPixels.load()) / (double(w) * h);
    rep.longShare = double(longPixels.load()) / (double(w) * h);
    return rep;
}

}  // namespace scam_yuv
