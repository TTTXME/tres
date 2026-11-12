#include "tone_port.h"
#include "raw_image.h"
#include "parallel.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace tone {
namespace {

#include "raw_dither.inc"

constexpr std::array<uint16_t, 256> inverse_sqrt_table() {
    std::array<uint16_t, 256> table{};
    for (unsigned i = 0; i < table.size(); ++i) {
        const unsigned scaled = i < 128 ? 257 + 2 * i : 514 + 4 * (i - 128);
        unsigned low = 512, high = 1024;
        while (high - low > 1) {
            const unsigned mid = (low + high) / 2;
            if (scaled * mid * mid < (1u << 28)) low = mid;
            else high = mid;
        }
        table[i] = static_cast<uint16_t>(high / 2);
    }
    return table;
}

float inverse_sqrt31(float value) {
    static constexpr auto table = inverse_sqrt_table();
    uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    const unsigned exponent = bits >> 23;
    const unsigned index = ((bits >> 16) & 127) + ((exponent & 1) ? 0 : 128);
    const float estimate = std::ldexp(static_cast<float>(table[index]), (380 - int(exponent)) / 2 - 135);
    return estimate * ((3.f - (estimate * estimate) * value) * .5f);
}

int clip12(int value) {
    return std::clamp(value, 0, 4095);
}

int shadow_floor(const RawUnsignedImage& src) {
    constexpr int last_bin = 512;
    std::array<size_t, last_bin + 1> histogram{};
    const size_t width = src.width / 4, height = src.height / 4;
    for (size_t y = 0; y < height; ++y) {
        for (size_t x = 0; x < width; ++x) {
            const size_t i = 4 * (y * src.row_stride + x);
            const int sum = src.data[i] + src.data[src.plane_stride + i] +
                            src.data[2 * src.plane_stride + i];
            ++histogram[std::min(last_bin, sum)];
        }
    }
    const size_t target = static_cast<size_t>((static_cast<float>(width) * .2f) *
                                             static_cast<float>(height) * .01f);
    size_t count = 0;
    for (int bin = 0; bin < last_bin; ++bin) {
        count += histogram[bin];
        if (count > target) return bin / 3;
    }
    return (last_bin + (count + histogram[last_bin] <= target)) / 3;
}

uint8_t quantize(float normalized, uint8_t dither) {
    const auto code = static_cast<uint32_t>(std::max(0.f, 4095.f * normalized));
    const auto adjusted = static_cast<uint16_t>(code + dither);
    return static_cast<uint8_t>(std::min(255, adjusted >> 4));
}

std::array<float, 3> apply_raw_color31(int luma, int cr, int cb, float adjustment) {
    constexpr float scale = 0x1.001002p-12f;
    constexpr float axis_main = 0x1.a20bd6p-1f;
    constexpr float axis_secondary = 0x1.a20bd8p-2f;
    constexpr float sixth = 0x1.555556p-3f;
    const float r = static_cast<float>(clip12(luma - cr));
    const int green_q15 = luma * 32768 + cr * 9743 + cb * 3308 + 16384;
    const float g = static_cast<float>(clip12(std::max(0, green_q15) / 32768));
    const float b = static_cast<float>(clip12(luma - cb));
    const float sum = (r + g) + b;
    const float r0 = r * scale, g0 = g * scale, b0 = b * scale;
    const float mean = sum * 0x1.556aaep-14f;
    const float dr = r0 - mean, dg = g0 - mean, db = b0 - mean;
    const float chroma = (dr * dr + dg * dg) + db * db;
    const float inv = inverse_sqrt31(std::max(chroma, 0x1.0c6f7ap-20f));
    const float nr = dr * inv, ng = dg * inv, nb = db * inv;
    const float hue = std::max(0.f, nb * 0x1.31b85ep-1f +
                                  (ng * 0x1.784556p-3f - nr * 0x1.8fc9bap-1f));
    const float blue = std::max(0.f, nb * axis_main + (nr * (-axis_secondary) - ng * axis_secondary));
    const float green = std::max(0.f, (ng * axis_main - nr * axis_secondary) - nb * axis_secondary);
    const float blue_sq = blue * blue;
    const float angle = (hue * (hue * hue)) * .22f - blue_sq * .2f;
    const float square = angle * angle;
    const float base = (sum * scale) * (square * sixth);
    const float cos_angle = 1.f - square * .5f;
    const float sin_angle = (angle - (angle * square) * sixth) * 0x1.279a74p-1f;
    const float rotated_r = (base + r0 * cos_angle) + ((b - g) * scale) * sin_angle;
    const float rotated_g = (base + g0 * cos_angle) + ((r - b) * scale) * sin_angle;
    const float rotated_b = (base + b0 * cos_angle) + ((g - r) * scale) * sin_angle;
    const float saturation = ((1.f + adjustment) * (1.f + blue_sq * .25f)) *
                             (1.f + green * 0x1.333330p-3f);
    const float neutral = ((1.f - saturation) * 0x1.555556p-2f) * (rotated_b + (rotated_r + rotated_g));
    return {saturation * rotated_r + neutral, saturation * rotated_g + neutral,
            saturation * rotated_b + neutral};
}

}  // namespace

std::vector<int16_t> tone_raw31_opponent(const RawUnsignedImage& src) {
    detail::validate_raw_planar(src, 3);
    const size_t width = src.width, height = src.height;
    constexpr float high = 2880.f;
    const int black = shadow_floor(src);
    const float lower = static_cast<float>(std::max(0, black - 50));
    const float knee = static_cast<float>(black + 120);
    const float span = knee - lower;
    const float tail = high - knee;
    const float quadratic = high / (span * span + tail * (span + span));
    const float at_knee = span * (span * quadratic);
    const float slope = (high - at_knee) / tail;
    const auto map = [&](uint16_t value) {
        const float input = value;
        if (input < lower) return 0;
        if (input < knee) {
            const float delta = input - lower;
            return static_cast<int>(delta * (delta * quadratic) + .5f);
        }
        if (input < high) return static_cast<int>(at_knee + (input - knee) * slope + .5f);
        return int(value);
    };
    const size_t count = width * height;
    std::vector<int16_t> output(3 * count);
    detail::parallel_rows(0, std::ptrdiff_t(height), 12 * std::ptrdiff_t(width),
                          [&](std::ptrdiff_t first, std::ptrdiff_t last) {
    for (size_t y = size_t(first); y < size_t(last); ++y) {
        for (size_t x = 0; x < width; ++x) {
            const size_t i = y * src.row_stride + x;
            const int r = map(src.data[i]);
            const int g = map(src.data[src.plane_stride + i]);
            const int b = map(src.data[2 * src.plane_stride + i]);
            const int luma = static_cast<int>((13936u * r + 46869u * g + 4731u * b + 32768u) >> 16);
            const size_t offset = y * width + x;
            output[offset] = static_cast<int16_t>(clip12(luma));
            output[count + offset] = static_cast<int16_t>(std::clamp(luma - r, -4095, 4095));
            output[2 * count + offset] = static_cast<int16_t>(std::clamp(luma - b, -4095, 4095));
        }
    }
    });
    return output;
}

std::vector<uint8_t> pack_raw31_rgb8(const RawRgbImage& opponent, float saturation_adjustment,
                                     int origin_x, int origin_y) {
    detail::validate_raw_planar(opponent, 3);
    if (!std::isfinite(saturation_adjustment) || std::abs(saturation_adjustment) > 64.f) {
        throw std::invalid_argument("Invalid RAW saturation adjustment");
    }
    std::vector<uint8_t> output(3 * opponent.width * opponent.height);
    detail::parallel_rows(0, std::ptrdiff_t(opponent.height), 40 * std::ptrdiff_t(opponent.width),
                          [&](std::ptrdiff_t first, std::ptrdiff_t last) {
    for (size_t y = size_t(first); y < size_t(last); ++y) {
        for (size_t x = 0; x < opponent.width; ++x) {
            const size_t i = y * opponent.row_stride + x;
            const auto rgb = apply_raw_color31(opponent.data[i], opponent.data[opponent.plane_stride + i],
                                               opponent.data[2 * opponent.plane_stride + i],
                                               saturation_adjustment);
            const auto dither = raw_dither[((y + static_cast<unsigned>(origin_y)) & 31) * 32 +
                                           ((x + static_cast<unsigned>(origin_x)) & 31)];
            const size_t dst = (y * opponent.width + x) * 3;
            for (size_t c = 0; c < rgb.size(); ++c) output[dst + c] = quantize(rgb[c], dither);
        }
    }
    });
    return output;
}

namespace {

std::vector<uint8_t> render(const std::vector<uint16_t>& toned, size_t w, size_t h,
                            float saturation, int origin_x, int origin_y) {
    const auto opponent = tone_raw31_opponent({toned.data(), toned.size(), w, h, w, w * h});
    return pack_raw31_rgb8({opponent.data(), opponent.size(), w, h, w, w * h},
                           saturation, origin_x, origin_y);
}

}  // namespace

std::vector<uint8_t> render_raw31_sdr(const RawRgbImage& src, const Curve12& curve,
                                     float saturation, int origin_x, int origin_y) {
    return render(tone_raw31_ldr(src, curve), src.width, src.height, saturation, origin_x, origin_y);
}

std::vector<uint8_t> render_raw31_hdr(const RawRgbImage& src, RawExposureGains gains,
                                     bool postview, const Curve12& curve, float saturation,
                                     int origin_x, int origin_y) {
    return render(tone_raw31_hdr(src, gains.short_gain, gains.long_gain, postview, curve),
                  src.width, src.height, saturation, origin_x, origin_y);
}

}  // namespace tone
