#include "tone_port.h"
#include "raw_image.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <utility>

namespace tone {
namespace {

#include "raw_mask.inc"

constexpr float sample_scale = 1.f / 4095.f;
constexpr int mask_scale = 4096;
constexpr int max_tone_code = 4500;
using Exposure = std::array<uint16_t, 3>;

float from_bits(uint32_t bits) {
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

float encode_exposure(float x) {
    if (x <= 0.f) return 0.f;
    if (x >= 1.f) return 4095.f;
    uint32_t bits;
    std::memcpy(&bits, &x, sizeof(bits));
    const uint32_t fraction = bits & 0x7fffff;
    const uint32_t high = fraction >> 22;
    const int exponent = static_cast<int>((bits >> 23) + high) - 127;
    const float m = from_bits(fraction | ((127 - high) << 23)) - 1.f;
    const float m2 = m * m;
    const float log_odd = (((m2 * from_bits(0x3d5162f5) + from_bits(0x3e19502f)) * m2 +
                            from_bits(0x3e4c9a1f)) * m2 + from_bits(0x3eaaaacd)) * m2 + 1.f;
    const float log_even = ((m2 * from_bits(0xbdf18a20) + from_bits(0xbe2caabc)) * m2 +
                            from_bits(0xbe7fe8f6)) * m2 + from_bits(0xbf000012);
    const float log_x = (log_even * m2 + log_odd * m) + exponent * from_bits(0x3f317218);
    const float z = log_x * from_bits(0x3ee8ba2e);
    const float k = std::floor(z * from_bits(0x3fb8aa3b));
    if (k <= -127.f) return 0.f;
    const float r = (z + k * from_bits(0xbf317200)) + k * from_bits(0xb5bfbe8e);
    const float r2 = r * r;
    const float exp_odd = ((r2 * from_bits(0x39a797f3) + from_bits(0x3c0b192a)) * r2 +
                          from_bits(0x3e2aae1f)) * r2 + 1.f;
    const float exp_even = ((r2 * from_bits(0x3a9c2e66) + from_bits(0x3d2a66bc)) * r2 +
                           from_bits(0x3effffde)) * r2 + 1.f;
    return std::ldexp(exp_even + r * exp_odd, static_cast<int>(k)) * 4095.f;
}

uint16_t round_even(float x) {
    const auto i = static_cast<unsigned>(x);
    const float fraction = x - i;
    return static_cast<uint16_t>(i + (fraction > .5f || (fraction == .5f && i % 2 != 0)));
}

std::array<Exposure, 4096> make_tables(float gain_short, float gain_long) {
    std::array<Exposure, 4096> values{};
    const float short_scale = gain_short * sample_scale;
    const float long_scale = gain_long * sample_scale;
    for (size_t i = 0; i < values.size(); ++i) {
        const auto dark = round_even(encode_exposure(short_scale * i));
        const auto bright = round_even(encode_exposure(long_scale * i));
        const uint16_t weight = bright < 2766 ? mask_scale : bright > 3674 ? 0 : raw_mask[bright - 2766];
        values[i] = {dark, bright, weight};
    }
    return values;
}

int floor_shift(int value, int bits) {
    const int unit = 1 << bits;
    return value / unit - (value % unit < 0);
}

int symmetric(int x, int length) {
    const int period = 2 * length;
    int u = x % period;
    if (u < 0) u += period;
    return std::min(u, period - 1 - u);
}

int mix(int dark, int bright, int weight) {
    return floor_shift(dark * (mask_scale - weight) + bright * weight + 2048, 12);
}

template<typename Pixel>
struct RawLevel {
    int width;
    int height;
    int padding;
    std::vector<Pixel> data;

    RawLevel(int w, int h, int p) : width(w), height(h), padding(p),
        data(size_t(w + 2 * p) * (h + 2 * p)) {}

    size_t index(int x, int y) const {
        return size_t(y + padding) * (width + 2 * padding) + size_t(x + padding);
    }

    const Pixel& sample(int x, int y) const {
        if (padding == 0) {
            x = symmetric(x, width);
            y = symmetric(y, height);
        }
        return data[index(x, y)];
    }
};

template<typename ReadPixel>
RawLevel<Exposure> reduce(int width, int height, bool extend, ReadPixel read) {
    RawLevel<Exposure> next((width + 1) / 2, (height + 1) / 2, extend ? 1 : 0);
    const auto border = [extend](int x, int length) {
        return extend ? std::clamp(x, 0, length - 1) : symmetric(x, length);
    };
    for (int y = -next.padding; y < next.height + next.padding; ++y) {
        std::array<int, 4> yy{};
        for (int j = 0; j < 4; ++j) yy[j] = border(2 * y + j - 1, height);
        for (int x = -next.padding; x < next.width + next.padding; ++x) {
            std::array<int, 3> sums{16, 16, 16};
            for (int i = 0; i < 4; ++i) {
                const int xx = border(2 * x + i - 1, width);
                for (int j = 0; j < 4; ++j) {
                    const int coefficient = 1 + (i == 1 || i == 2) + (j == 1 || j == 2);
                    const auto p = read(xx, yy[j]);
                    for (size_t c = 0; c < p.size(); ++c) sums[c] += coefficient * p[c];
                }
            }
            auto& result = next.data[next.index(x, y)];
            for (size_t c = 0; c < result.size(); ++c) result[c] = static_cast<uint16_t>(sums[c] >> 5);
        }
    }
    return next;
}

template<typename Pixel, typename Get>
int upsample(const RawLevel<Pixel>& level, int x, int y, Get get) {
    const int ix = floor_shift(x, 1), iy = floor_shift(y, 1);
    const int nx = ix + (x % 2 != 0 ? 1 : -1), ny = iy + (y % 2 != 0 ? 1 : -1);
    return floor_shift(9 * get(level.sample(ix, iy)) + 3 * get(level.sample(nx, iy)) +
                       3 * get(level.sample(ix, ny)) + get(level.sample(nx, ny)) + 8, 4);
}

int reconstruct(const Exposure& fine, const RawLevel<Exposure>& parent,
                const RawLevel<int16_t>& merged, int x, int y) {
    const int dark = fine[0] - upsample(parent, x, y, [](const Exposure& p) { return p[0]; });
    const int bright = fine[1] - upsample(parent, x, y, [](const Exposure& p) { return p[1]; });
    return mix(dark, bright, fine[2]) + upsample(merged, x, y, [](int16_t p) { return p; });
}

}  // namespace

std::vector<uint16_t> tone_raw31_hdr(const RawRgbImage& src, float gain_short, float gain_long,
                                     bool postview, const Curve12& curve) {
    detail::validate_raw_planar(src, 3);
    if (!std::isfinite(gain_short) || !std::isfinite(gain_long) || gain_short < 0.f || gain_long < 0.f) {
        throw std::invalid_argument("Invalid exposure gain");
    }
    const int w = static_cast<int>(src.width), h = static_cast<int>(src.height);
    const auto tables = make_tables(gain_short, gain_long);
    const auto maximum = [&](size_t offset) {
        return std::clamp<int>(std::max({src.data[offset], src.data[offset + src.plane_stride],
                                       src.data[offset + 2 * src.plane_stride]}), 0, 4095);
    };
    const auto input = [&](int x, int y) { return tables[maximum(size_t(y) * src.row_stride + x)]; };
    const int extended = postview ? 1 : 3;
    const int limit = postview ? 7 : 9;
    std::vector<RawLevel<Exposure>> levels;
    levels.push_back(reduce(w, h, true, input));
    for (int depth = 1; depth < limit && (depth < extended || w > (6 << depth)); ++depth) {
        const auto& parent = levels.back();
        auto next = reduce(parent.width, parent.height, depth < extended,
                           [&](int x, int y) { return parent.data[parent.index(x, y)]; });
        levels.push_back(std::move(next));
    }
    const auto& coarse = levels.back();
    RawLevel<int16_t> merged(coarse.width, coarse.height, coarse.padding);
    for (size_t i = 0; i < merged.data.size(); ++i) {
        const auto& p = coarse.data[i];
        merged.data[i] = static_cast<int16_t>(mix(p[0], p[1], p[2]));
    }
    for (size_t i = levels.size() - 1; i > 0; --i) {
        const auto& fine = levels[i - 1];
        RawLevel<int16_t> next(fine.width, fine.height, fine.padding);
        for (int y = -next.padding; y < next.height + next.padding; ++y) {
            for (int x = -next.padding; x < next.width + next.padding; ++x) {
                const auto index = fine.index(x, y);
                next.data[index] = static_cast<int16_t>(reconstruct(fine.data[index], levels[i], merged, x, y));
            }
        }
        merged = std::move(next);
    }

    const size_t count = src.width * src.height;
    std::vector<uint16_t> out(count * 3);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const size_t offset = size_t(y) * src.row_stride + x;
            const size_t target = size_t(y) * w + x;
            const int luma = maximum(offset);
            const int code = std::clamp(reconstruct(tables[luma], levels.front(), merged, x, y), 0, max_tone_code);
            const float factor = std::max(1.f, raw_inverse[code] * (1.f / std::max(1, luma)));
            for (size_t c = 0; c < 3; ++c) {
                const float value = src.data[c * src.plane_stride + offset] * factor + .5f;
                out[c * count + target] = curve[static_cast<unsigned>(std::clamp(value, 0.f, 4095.f))];
            }
        }
    }
    return out;
}

}  // namespace tone
