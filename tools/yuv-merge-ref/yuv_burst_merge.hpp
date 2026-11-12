#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <istream>
#include <stdexcept>
#include <vector>

namespace yuv_burst {
enum class Profile { v27, v31 };
enum class Order { UV, VU };
enum class Division { Scalar, Q15 };
constexpr int Side = 32;
constexpr int Pixels = Side * Side;

struct Plane {
    const uint8_t* data;
    std::ptrdiff_t rowStride, pixelStride;
    uint8_t at(int x, int y) const {
        return data[y * rowStride + x * pixelStride];
    }
};

struct Yuv420 {
    Plane y, c0, c1;
    int width, height;
    Order order;
};

using Matrix3 = std::array<std::array<float, 3>, 3>;
using Luma = std::array<uint8_t, Pixels>;
using Gains = std::array<float, 4>; // R, Ge, Go, B
using GainTile = std::array<Gains, 16>;
using Projections = std::array<uint8_t, 64>;

struct Tile {
    std::array<uint8_t, 3 * Pixels> samples{};
    std::array<uint16_t, 3> rgbQ8{};
    Order order = Order::UV;
};

struct Lut {
    const float* values;
    std::size_t size;
    std::array<unsigned, 3> shift, place;
    float at(const std::array<int, 3>& yuv) const {
        unsigned index = 0;
        for (int c = 0; c < 3; ++c)
            index |= (unsigned(yuv[c]) >> shift[c]) << place[c];
        if (index >= size) throw std::out_of_range("noise LUT index");
        return values[index];
    }
};

struct NoiseKey {
    int red, greenEven, blue, temperatureK;
    float gain;
};

struct NoisePlan {
    int lower = -1, upper = -1;
    float weightUpper = 0.f;
    float gainRatio = 1.f;
};

inline NoisePlan select_noise_lut(const NoiseKey* tables, std::size_t size, NoiseKey q) {
    NoisePlan result;
    int lowerCost = 0x40000000, upperCost = 0x40000000;
    for (std::size_t i = 0; i < size; ++i) {
        const auto& e = tables[i];
        int cost = 5 * (std::abs(q.red - e.red) + std::abs(q.greenEven - e.greenEven)
                     + std::abs(q.blue - e.blue));
        if (q.temperatureK != 0 && e.temperatureK != 0) cost += std::abs(q.temperatureK - e.temperatureK) >> 3;
        cost += int(double(std::abs(q.gain - e.gain)) * 10000.);
        if (q.gain >= e.gain && cost < lowerCost) {
            result.lower = int(i);
            lowerCost = cost;
        }
        if (q.gain < e.gain && cost < upperCost) {
            result.upper = int(i);
            upperCost = cost;
        }
    }
    if (result.lower >= 0 && result.upper >= 0) {
        float lo = tables[result.lower].gain, hi = tables[result.upper].gain;
        result.weightUpper = std::pow((q.gain - lo) / (hi - lo), .8f);
    } else if (result.lower >= 0 || result.upper >= 0) {
        int only = result.lower >= 0 ? result.lower : result.upper;
        result.gainRatio = q.gain / tables[only].gain;
    }
    return result;
}

inline std::vector<float> interpolate_stddev(const Lut* tables, const NoisePlan& plan) {
    if (plan.lower < 0 && plan.upper < 0)
        throw std::invalid_argument("no noise table");
    int only = plan.lower < 0 || plan.weightUpper == 1.f ? plan.upper : plan.lower;
    const Lut& lo = tables[only];
    if (plan.lower < 0 || plan.upper < 0 || plan.weightUpper == 0.f ||
        plan.weightUpper == 1.f)
        return {lo.values, lo.values + lo.size};

    const Lut& hi = tables[plan.upper];
    if (lo.size != hi.size || lo.shift != hi.shift || lo.place != hi.place)
        throw std::invalid_argument("incompatible noise tables");
    std::vector<float> out(lo.size);
    float w = plan.weightUpper;
    for (std::size_t i = 0; i < lo.size; ++i)
        out[i] = (1.f - w) * lo.values[i] + w * hi.values[i];
    return out;
}

struct NoiseDatabase {
    std::vector<NoiseKey> keys;
    std::vector<std::vector<float>> stddev;
    std::array<unsigned, 3> shift{}, place{};
};

inline NoiseDatabase read_noise_database(std::istream& in) {
    auto read = [&](char* bytes, std::streamsize n) {
        if (!in.read(bytes, n)) throw std::runtime_error("truncated noise database");
    };
    auto word = [&]() {
        uint8_t b[4];
        read(reinterpret_cast<char*>(b), 4);
        return uint32_t(b[0]) | (uint32_t(b[1]) << 8)
             | (uint32_t(b[2]) << 16) | (uint32_t(b[3]) << 24);
    };
    auto read_float = [&]() {
        uint32_t bits = word();
        float f;
        std::memcpy(&f, &bits, sizeof(f));
        return f;
    };

    char magic[8];
    read(magic, sizeof(magic));
    if (std::memcmp(magic, "YUVLUT01", sizeof(magic)) != 0)
        throw std::runtime_error("invalid noise database header");
    unsigned count = word(), bins = word();
    if (count == 0 || count > 1024 || bins == 0 || bins > 8192)
        throw std::runtime_error("invalid noise database size");
    NoiseDatabase db;
    for (int c = 0; c < 3; ++c) {
        db.shift[c] = word();
        db.place[c] = word();
        if (db.shift[c] > 8 || db.place[c] > 13)
            throw std::runtime_error("invalid noise database index");
    }

    db.keys.resize(count);
    db.stddev.resize(count);
    for (unsigned i = 0; i < count; ++i) {
        int a = int(word()), b = int(word());
        (void)word(); // The second green gain is stored but is not used for matching.
        int d = int(word()), e = int(word());
        db.keys[i] = {a, b, d, e, read_float()};
        auto& values = db.stddev[i];
        values.resize(bins);
        for (float& value : values) value = read_float();
    }
    return db;
}

inline std::vector<float> noise_values(const NoiseDatabase& db, NoiseKey query) {
    NoisePlan plan = select_noise_lut(db.keys.data(), db.keys.size(), query);
    std::vector<Lut> views;
    views.reserve(db.stddev.size());
    for (const auto& values : db.stddev)
        views.push_back({values.data(), values.size(), db.shift, db.place});
    return interpolate_stddev(views.data(), plan);
}

struct ThresholdParams {
    float spatialScale = 1.f, spatialBias = 0.f;
    float biasPre = 0.f, biasPost = .2f;
    float tileStd = .9898000359535217f;
};

struct Thresholds {
    std::array<float, 16> local{};
    std::array<float, 3> lastGain{};
    float tile = 0.f;
    bool nearClip = false;
};

struct Donor {
    const Tile* tile;
    float scale;
    bool disabled;
};

struct Result {
    std::array<uint8_t, 3 * Pixels> planar{};
    std::array<uint8_t, Pixels / 2> countPacked{};
    std::array<uint8_t, Pixels> pixelRejectCount{};
    uint8_t eligible = 0;
};

struct Shift { int x, y; };

inline Shift donor_origin(int tileX, int tileY, Shift global, uint8_t dx, uint8_t dy) {
    return {Side * tileX + global.x + int(dx) - 128,
            Side * tileY + global.y + int(dy) - 128};
}

inline Matrix3 standard_color_matrix(Order order) {
    Matrix3 m{{
        {1.f,  0x1.8dee92p-9f,  0x1.6642d8p+0f},
        {1.f, -0x1.607d6ap-2f, -0x1.6f3512p-1f},
        {1.f,  0x1.c5be06p+0f, -0x1.922a4ep-8f}
    }};
    if (order == Order::VU)
        for (auto& row : m) std::swap(row[1], row[2]);
    return m;
}

inline bool extract_tile(const Yuv420& in, int x, int y, const Matrix3& m, Tile& dst) {
    if (x < 0 || y < 0 || in.width < Side || in.height < Side ||
        x > in.width - Side - (x & 1) || y > in.height - Side - (y & 1))
        return false;

    dst.order = in.order;
    std::array<int, 3> sum{};
    for (int j = 0; j < Side; ++j) {
        for (int i = 0; i < Side; ++i) {
            int u = x + i, v = y + j;
            std::array<uint8_t, 3> p{
                in.y.at(u, v), in.c0.at(u / 2, v / 2), in.c1.at(u / 2, v / 2)
            };
            for (int c = 0; c < 3; ++c) {
                dst.samples[(j * Side + i) * 3 + c] = p[c];
                sum[c] += p[c];
            }
        }
    }
    float l = sum[0] * (1.f / 1024);
    float c0 = sum[1] * (1.f / 1024) - 128.f;
    float c1 = sum[2] * (1.f / 1024) - 128.f;
    for (int c = 0; c < 3; ++c) {
        float rgb = (m[c][2] * c1 + m[c][1] * c0) + m[c][0] * l;
        dst.rgbQ8[c] = uint16_t(std::clamp(rgb, 0.f, 255.f) * 256.f);
    }
    return true;
}

inline Thresholds make_thresholds(const Tile& base, const Lut& lut,
                                  const GainTile& gains, const ThresholdParams& p) {
    Thresholds out;
    int clipped = 0;
    float total = 0.f;
    for (int j = 0; j < 16; ++j) {
        const int sx = j % 4, sy = j / 4;
        std::array<int, 3> mean{32, 32, 32};
        for (int y = 8 * sy; y < 8 * sy + 8; ++y)
            for (int x = 8 * sx; x < 8 * sx + 8; ++x)
                for (int c = 0; c < 3; ++c)
                    mean[c] += base.samples[(y * Side + x) * 3 + c];
        for (int& m : mean) m >>= 6;
        if (base.order == Order::VU) std::swap(mean[1], mean[2]);
        clipped += (mean[0] < 5 || mean[0] > 245);

        const auto& g = gains[j];
        out.lastGain = {g[0], (g[1] + g[2]) * .5f, g[3]};
        float lumGain = (77.f * g[0] + 29.f * g[3]) + 150.f * out.lastGain[1];
        float noise = lut.at(mean) + p.biasPre;
        float spatial = p.spatialBias + lumGain * (p.spatialScale * (1.f / 256));
        out.local[j] = p.biasPost + noise * spatial;
        total += out.local[j];
    }
    out.tile = total * (p.tileStd * (1.f / 16));
    out.nearClip = clipped > 3;
    return out;
}

inline Projections project(const Luma& tile) {
    Projections out{};
    for (int i = 0; i < Side; ++i) {
        unsigned col = 0, row = 0;
        for (int j = 0; j < Side; ++j) {
            col += tile[j * Side + i];
            row += tile[i * Side + j];
        }
        out[i] = uint8_t(row >> 5);
        out[Side + i] = uint8_t(col >> 5);
    }
    return out;
}

inline int projection_shift(const uint8_t* base, const uint8_t* alt) {
    int best = -8, bestCost = 16 * 255 + 1;
    for (int d = -8; d < 8; ++d) {
        int sad = 0;
        for (int i = 8; i < 24; ++i)
            sad += std::abs(int(base[i]) - int(alt[i + d]));
        if (sad < bestCost) {
            bestCost = sad;
            best = d;
        }
    }
    return best;
}

enum class Forgiveness { Rounded11, Weighted11 };
struct Band { uint8_t middle, radius; };
using Bands = std::array<Band, Pixels>;

inline Bands forgiveness(const Luma& base, Forgiveness mode) {
    Bands out{};
    auto at = [&](int x, int y) { return int(base[y * Side + x]); };
    for (int y = 0; y < Side; ++y) {
        for (int x = 0; x < Side; ++x) {
            int nextY = std::min(y + 1, Side - 1);
            int rightY = (x == Side - 1 && mode == Forgiveness::Rounded11) ? nextY : y;
            int c = at(x, y), up = at(x, std::max(y - 1, 0)), down = at(x, nextY);
            int left = at(std::max(x - 1, 0), y);
            int right = at(std::min(x + 1, Side - 1), rightY);
            int lo = std::min({up, down, left, right});
            int hi = std::max({up, down, left, right});
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
            out[y * Side + x] = {uint8_t((lo + hi) >> 1), uint8_t((hi - lo) >> 1)};
        }
    }
    return out;
}

inline float luma_mse(const Bands& bands, const Luma& filteredDonor) {
    uint32_t sum = 0;
    for (int i = 0; i < Pixels; ++i) {
        int d = std::max(0, std::abs(int(filteredDonor[i]) - bands[i].middle)
                            - bands[i].radius);
        sum += uint32_t(d * d);
    }
    return float(sum) * (1.f / 1024);
}

inline std::array<float, 2> positive_centroid(const Luma& base, const Luma& alt) {
    int count = 1, xSum = 15, ySum = 15;
    for (int i = 0; i < Pixels; ++i) {
        if (alt[i] > base[i]) {
            ++count;
            xSum += i % Side;
            ySum += i / Side;
        }
    }
    float inv = 1.f / float(count);
    return {float(xSum) * inv, float(ySum) * inv};
}

inline int full_tile_color_distance(const Tile& a, const Tile& b) {
    return std::abs(int(a.rgbQ8[0]) - b.rgbQ8[0])
         + 2 * std::abs(int(a.rgbQ8[1]) - b.rgbQ8[1])
         + std::abs(int(a.rgbQ8[2]) - b.rgbQ8[2]);
}

inline bool attenuate(float metric, float limit, float steepness, float& scale) {
    if (metric <= limit) return true;
    if (limit <= 0.f) return false;
    float k = steepness - 1.f;
    float loss = (metric / limit) * k - k;
    if (loss > 1.f) return false;
    scale *= 1.f - loss;
    return true;
}

inline Luma smoothed_donor(const Plane& y, int w, int h, int ox, int oy) {
    Luma out{};
    for (int j = 0; j < Side; ++j) {
        for (int i = 0; i < Side; ++i) {
            int x0 = ox + i, y0 = oy + j;
            int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
            int a = (int(y.at(x0, y0)) + y.at(x1, y0) + 1) >> 1;
            int b = (int(y.at(x0, y1)) + y.at(x1, y1) + 1) >> 1;
            out[j * Side + i] = uint8_t((a + b + 1) >> 1);
        }
    }
    return out;
}

inline int color_limit(float denominator, float tolerance,
                       float threshold, float colorAdjustment, float metadataScale) {
    int initial = int(std::round(std::min(4096.f / denominator, 2500.f) * tolerance));
    int adjusted = initial + int(threshold * (colorAdjustment * 200.f));
    return int(std::round(float(adjusted) * metadataScale));
}

inline constexpr std::array<uint8_t, 184> dither{{
    181,  60,  30, 155, 115, 117, 169, 197,  89, 169, 106, 215, 213,  65, 157, 149,
    138, 222,  67,  81,  30, 240, 165, 122, 163, 139, 165, 139, 184, 133, 254,  55,
     27,  28,  16, 103, 114,  93, 195, 160, 197, 238, 249,  49,  35, 178,  24, 134,
    135, 220, 124, 100, 171, 189, 133,  89,  38, 150,  67,  11, 193,  62, 113, 176,
     91, 188, 101, 174, 180, 113,   5,  84, 108,  69,  50, 210, 110, 227, 100, 196,
    101, 206, 193,  96,  55, 202, 243,  83, 171, 112, 213, 196,  42, 220, 253, 131,
    226, 150,  39,  51, 104, 191, 211, 202,  81, 136,  23,  28,  34, 173, 126,  48,
    126,  37,  14, 217, 143, 237, 178, 149, 208, 225, 253,   0, 221, 156, 253, 135,
    122, 205,  58, 127, 230, 147, 216, 189, 150,  63, 170,  21, 160, 169, 186, 228,
    251, 196, 148, 237, 148,   4,  30, 220, 123, 216,  53, 141, 161,   8, 157,  92,
     12, 125,  49,  31,  52,  37,  48,  10, 162,  72, 137, 177, 127, 137, 113,  31,
    125, 218, 223,  69,  53, 144, 163, 106,
}};

inline Result merge_tile(Profile profile, const Tile& base, const Donor* donors,
                         std::size_t donorCount, unsigned requestedTotal,
                         const Thresholds& thresholds, float globalPixelMultiplier,
                         Division division) {
    if (requestedTotal < 1 || requestedTotal > 16 ||
        donorCount >= requestedTotal || (division == Division::Q15 && requestedTotal >= 16))
        throw std::invalid_argument("invalid frame count");

    Result out;
    out.eligible = 1;
    std::array<std::array<int, 16>, 15> maxDifference{};
    for (std::size_t j = 0; j < donorCount; ++j) {
        const Donor& donor = donors[j];
        if (donor.disabled) continue;
        if (donor.tile == nullptr || donor.tile->order != base.order)
            throw std::invalid_argument("incompatible donor tile");
        ++out.eligible;
        for (int i = 0; i < 16; ++i) {
            float local = thresholds.local[i];
            float t = profile == Profile::v27
                ? (local * globalPixelMultiplier) * donor.scale
                : (donor.scale * globalPixelMultiplier) * local;
            if (!std::isfinite(t) || t < 0.f)
                throw std::invalid_argument("invalid pixel threshold");
            maxDifference[j][i] = int(std::min(t, 255.f));
        }
    }

    const int countShift = requestedTotal == 16 ? 1 : 0;
    const int u = base.order == Order::UV ? 1 : 2;
    const int v = 3 - u;
    for (int i = 0; i < Pixels; ++i) {
        int x = i % Side, y = i / Side;
        int subtile = x / 8 + 4 * (y / 8);
        int offset = 3 * i;
        std::array<int, 3> sum{
            base.samples[offset], base.samples[offset + 1], base.samples[offset + 2]
        };
        const int referenceY = sum[0];
        unsigned n = 1;
        for (std::size_t j = 0; j < donorCount; ++j) {
            const Donor& d = donors[j];
            if (d.disabled) continue;
            const auto* p = d.tile->samples.data() + offset;
            if (std::abs(int(p[0]) - referenceY) > maxDifference[j][subtile]) {
                ++out.pixelRejectCount[i];
                continue;
            }
            ++n;
            for (int c = 0; c < 3; ++c) sum[c] += p[c];
        }
        unsigned index = ((3 + 13 * subtile) & 127) + 8 * (y & 7) + (x & 7);
        unsigned add = (n * dither[index]) >> 8;
        auto divide = [&](unsigned value) {
            value += add;
            return uint8_t(division == Division::Q15
                ? (value * ((32768 + n - 1) / n)) >> 15
                : value / n);
        };
        out.planar[i] = divide(sum[0]);
        out.planar[Pixels + i] = divide(sum[u]);
        out.planar[2 * Pixels + i] = divide(sum[v]);
        out.countPacked[i / 2] |= uint8_t((n >> countShift) << ((x & 1) ? 0 : 4));
    }
    return out;
}
struct DonorPlan {
    Shift global;
    uint8_t dx, dy;
    float scale;
    bool disabled;
};

struct TilePlan {
    Thresholds thresholds;
    std::vector<DonorPlan> donors;
};

struct FrameResult {
    int width, height;
    std::vector<uint8_t> planar, countPacked, eligible;
};

inline FrameResult merge_prepared(Profile profile, const Yuv420& base,
                                  const Yuv420* donors, std::size_t donorCount,
                                  const std::vector<TilePlan>& plans,
                                  unsigned requestedTotal, float pixelMultiplier,
                                  Division division) {
    if (base.width <= 0 || base.height <= 0 || base.width % Side != 0 ||
        base.height % Side != 0 || requestedTotal < 1 || requestedTotal > 16 ||
        donorCount >= requestedTotal)
        throw std::invalid_argument("invalid prepared burst");
    int nx = base.width / Side, ny = base.height / Side;
    std::size_t planeSize = std::size_t(base.width) * base.height;
    std::size_t tileCount = std::size_t(nx) * ny;
    if (plans.size() != tileCount) throw std::invalid_argument("invalid tile grid");

    FrameResult out{base.width, base.height, std::vector<uint8_t>(3 * planeSize),
                    std::vector<uint8_t>(planeSize / 2), std::vector<uint8_t>(tileCount)};
    std::vector<Tile> altTiles(donorCount);
    std::vector<Donor> candidates(donorCount);
    const Matrix3 matrix = standard_color_matrix(base.order);
    Tile baseTile;
    for (int ty = 0; ty < ny; ++ty) {
        for (int tx = 0; tx < nx; ++tx) {
            std::size_t index = std::size_t(ty) * nx + tx;
            const auto& plan = plans[index];
            if (plan.donors.size() != donorCount ||
                !extract_tile(base, tx * Side, ty * Side, matrix, baseTile))
                throw std::invalid_argument("invalid tile plan");

            for (std::size_t j = 0; j < donorCount; ++j) {
                const DonorPlan& d = plan.donors[j];
                if (!d.disabled && donors[j].order != base.order)
                    throw std::invalid_argument("normalize input chroma order");
                auto o = donor_origin(tx, ty, d.global, d.dx, d.dy);
                bool disabled = d.disabled ||
                    !extract_tile(donors[j], o.x, o.y, matrix, altTiles[j]);
                candidates[j] = {&altTiles[j], d.scale, disabled};
            }

            auto tile = merge_tile(profile, baseTile, candidates.data(), donorCount,
                                   requestedTotal, plan.thresholds, pixelMultiplier, division);
            out.eligible[index] = tile.eligible;
            for (int y = 0; y < Side; ++y) {
                std::size_t offset = (std::size_t(ty) * Side + y) * base.width + tx * Side;
                for (int c = 0; c < 3; ++c)
                    std::copy_n(tile.planar.data() + c * Pixels + y * Side, Side,
                                out.planar.data() + c * planeSize + offset);
                std::copy_n(tile.countPacked.data() + y * Side / 2, Side / 2,
                            out.countPacked.data() + offset / 2);
            }
        }
    }
    return out;
}
} // namespace yuv_burst
