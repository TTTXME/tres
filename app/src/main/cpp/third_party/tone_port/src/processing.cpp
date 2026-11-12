#include "tone_port.h"
#include "rgb8_hdr.h"
#include "parallel.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
#include <stdexcept>

namespace tone {
namespace {

// SCAMERA P636: the dark exposure's storage is written whole by make_exposures before anything reads it: not zeroed first
// (with one thread zeroed, as the vector was).
struct ExposurePair {
    std::vector<uint8_t> bright;
    std::unique_ptr<uint8_t[]> dark;
    size_t dark_bytes;
};

std::vector<uint8_t> tone_pair(Version version, const Image& frame, ExposurePair& pair) {
    return detail::tone_virtual_owned(version, frame.width, frame.height, pair.bright, pair.dark.get(), pair.dark_bytes);
}

#include "profile_tables.inc"

using Pixel = std::array<int, 3>;
using FloatMatrix = std::array<float, 9>;

constexpr Matrix yuv_to_rgb{4096, 12, 5733, 4096, -1410, -2938, 4096, 7260, -25};
constexpr Matrix rgb_to_yuv{1232, 2400, 464, -688, -1360, 2048, 2048, -1712, -336};

void require(bool condition, const char* error) {
    if (!condition) throw std::invalid_argument(error);
}

size_t checked_product(size_t a, size_t b) {
    require(a == 0 || b <= std::numeric_limits<size_t>::max() / a, "Image size overflow");
    return a * b;
}

size_t validate(const Image& src, ColorInput color) {
    require(src.data && src.width && src.height, "Empty image");
    const size_t count = checked_product(src.width, src.height);
    require(count <= std::numeric_limits<size_t>::max() / 3, "Image size overflow");
    const auto visible = checked_product(src.width, color == ColorInput::rgb ? 3 : 1);
    require(src.row_stride >= visible, "Row stride is too small");
    const auto rows = checked_product(src.row_stride, src.height - 1);
    require(rows <= src.bytes && visible <= src.bytes - rows, "Image is truncated");
    if (color == ColorInput::yuv) {
        require(src.plane_stride >= rows + visible, "Overlapping YUV planes");
        require(src.plane_stride <= (src.bytes - rows - visible) / 2, "YUV image is truncated");
    }
    return count;
}

int64_t floor_div(int64_t value, int64_t divisor) {
    return value / divisor - (value % divisor < 0);
}

int clip10(int64_t value) {
    return static_cast<int>(std::clamp<int64_t>(value, 0, 1023));
}

uint8_t clip8(int64_t value) {
    return static_cast<uint8_t>(std::clamp<int64_t>(value, 0, 255));
}

uint8_t from_lut(uint16_t value) {
    return clip8((int(value) + 2) / 4);
}

Pixel read_rgb_indices(ColorInput color, const Image& src, size_t x, size_t y) {
    const auto* row = src.data + y * src.row_stride;
    if (color == ColorInput::rgb) {
        const auto* pixel = row + 3 * x;
        return {4 * pixel[0], 4 * pixel[1], 4 * pixel[2]};
    }
    const int l = 4 * row[x];
    const int u = row[x + src.plane_stride] - 128;
    const int v = row[x + 2 * src.plane_stride] - 128;
    return {clip10(l + floor_div(90 * v + 8, 16)),
            clip10(l + floor_div(-22 * u - 46 * v + 8, 16)),
            clip10(l + floor_div(113 * u + 8, 16))};
}

int64_t dot(const Matrix& matrix, size_t row, const Pixel& pixel) {
    const auto* m = matrix.data() + 3 * row;
    return int64_t(m[0]) * pixel[0] + int64_t(m[1]) * pixel[1] + int64_t(m[2]) * pixel[2];
}

Pixel transform(const Matrix& matrix, const Pixel& p, unsigned shift) {
    Pixel output{};
    const int64_t unit = int64_t{1} << shift;
    for (size_t c = 0; c < 3; ++c) {
        output[c] = clip10(floor_div(dot(matrix, c, p) + unit / 2, unit));
    }
    return output;
}

template <typename Convert>
ExposurePair make_exposures(ColorInput color, const Image& src, Convert convert) {
    const auto count = validate(src, color);
    ExposurePair out{std::vector<uint8_t>(count * 3),
                     std::unique_ptr<uint8_t[]>(detail::thread_count() > 1 ? new uint8_t[count * 3] : new uint8_t[count * 3]()),
                     count * 3};
    detail::parallel_rows(0, std::ptrdiff_t(src.height), 9 * std::ptrdiff_t(src.width),
                          [&](std::ptrdiff_t first, std::ptrdiff_t last) {
    for (size_t y = size_t(first); y < size_t(last); ++y) {
        for (size_t x = 0; x < src.width; ++x) {
            const auto pixel = read_rgb_indices(color, src, x, y);
            const size_t offset = 3 * (y * src.width + x);
            convert(pixel, out.bright.data() + offset, out.dark.get() + offset);
        }
    }
    });
    return out;
}

FloatMatrix multiply(const FloatMatrix& a, const FloatMatrix& b) {
    FloatMatrix c{};
    for (size_t y = 0; y < 3; ++y) {
        for (size_t x = 0; x < 3; ++x) {
            c[3 * y + x] = (a[3 * y] * b[x] + a[3 * y + 1] * b[x + 3]) + a[3 * y + 2] * b[x + 6];
        }
    }
    return c;
}

FloatMatrix inverse_matrix(const FloatMatrix& m) {
    FloatMatrix out{
        m[4]*m[8] - m[5]*m[7], m[2]*m[7] - m[1]*m[8], m[1]*m[5] - m[2]*m[4],
        m[5]*m[6] - m[3]*m[8], m[0]*m[8] - m[2]*m[6], m[2]*m[3] - m[0]*m[5],
        m[3]*m[7] - m[4]*m[6], m[1]*m[6] - m[0]*m[7], m[0]*m[4] - m[1]*m[3]
    };
    const float det = (m[0] * out[0] + m[1] * out[3]) + m[2] * out[6];
    require(std::isfinite(det) && det != 0.f, "Singular white balance matrix");
    for (float& x : out) x /= det;
    return out;
}

FloatMatrix with_gains(const WhiteBalance& wb, bool normalize) {
    for (int x : wb.gains_q9) require(x > 0 && x <= 65535, "Invalid white balance gain");
    std::array<float, 3> g{
        wb.gains_q9[0] / 512.f,
        (wb.gains_q9[1] + wb.gains_q9[2]) / 1024.f,
        wb.gains_q9[3] / 512.f
    };
    if (normalize) {
        const float scale = 1.f / (((77.f * g[0] + 29.f * g[2]) + 150.f * g[1]) / 256.f);
        for (float& x : g) x *= scale;
    }
    auto out = wb.rgb_to_rgb;
    for (size_t i = 0; i < out.size(); ++i) out[i] *= g[i % 3];
    return out;
}

struct FixedMatrix {
    Matrix values;
    unsigned shift;
};

FixedMatrix white_balance_matrix(const WhiteBalance& source, const WhiteBalance& target, float gain, bool normalize) {
    auto dest = with_gains(target, normalize);
    for (float& x : dest) x *= gain;
    const auto m = multiply(dest, inverse_matrix(with_gains(source, normalize)));
    float norm = 0.f;
    for (size_t i = 0; i < m.size(); i += 3) {
        norm = std::max(norm, std::abs(m[i]) + (std::abs(m[i + 1]) + std::abs(m[i + 2])));
    }
    require(std::isfinite(norm) && norm > 0.f, "Invalid white balance matrix");
    const int bits = 15 + static_cast<int>(std::floor(-std::log(norm * 1.0001000166f) * 1.4426950216f));
    require(bits >= 0 && bits <= 30, "White balance matrix is out of range");
    FixedMatrix out{{}, static_cast<unsigned>(bits)};
    for (size_t i = 0; i < m.size(); ++i) {
        const float x = std::round(std::ldexp(m[i], bits));
        require(std::isfinite(x) && x >= -32768.f && x <= 32767.f, "White balance coefficient overflow");
        out.values[i] = static_cast<int16_t>(x);
    }
    return out;
}

uint32_t as_q8(float value) {
    const float rounded = std::round(value * 256.f);
    require(std::isfinite(rounded) && rounded >= 0.f && rounded < 4294967296.f,
            "Digital gain is out of range");
    return static_cast<uint32_t>(rounded);
}

}  // namespace

uint8_t detail::bright_weight(Version version, uint8_t r, uint8_t g, uint8_t b) {
    return (version == Version::v27 ? weights27 : weights31)[std::max({r, g, b})];
}

std::vector<uint8_t> tone_rgb8_hdr_luts(Version version, ColorInput color, const Image& src,
                                        const std::array<Curve10, 3>& bright,
                                        const std::array<Curve10, 3>& dark) {
    return tone_rgb8_hdr_luts(version, color, src, bright, dark, nullptr, nullptr);
}

std::vector<uint8_t> tone_rgb8_hdr_luts(Version version, ColorInput color, const Image& src,
                                        const std::array<Curve10, 3>& bright,
                                        const std::array<Curve10, 3>& dark, BrightHook hook, void* user) {
    auto pair = make_exposures(color, src, [&](const Pixel& p, uint8_t* a, uint8_t* b) {
        for (size_t c = 0; c < 3; ++c) {
            a[c] = from_lut(bright[c][p[c]]);
            b[c] = from_lut(dark[c][p[c]]);
        }
    });
    if (hook) hook(pair.bright.data(), src.width, src.height, user);
    return tone_pair(version, src, pair);
}

std::vector<uint8_t> tone_rgb8_hdr_matrices(Version version, ColorInput color, const Image& src,
                                            const Curve10& forward, const Curve10& inverse,
                                            const Matrix& bright, unsigned bright_shift,
                                            const Matrix& dark, unsigned dark_shift) {
    require(bright_shift <= 31 && dark_shift <= 31, "Invalid matrix shift");
    auto pair = make_exposures(color, src, [&](const Pixel& p, uint8_t* a, uint8_t* b) {
        const Pixel linear{inverse[p[0]], inverse[p[1]], inverse[p[2]]};
        const auto ab = transform(bright, linear, bright_shift);
        const auto bb = transform(dark, linear, dark_shift);
        for (size_t c = 0; c < 3; ++c) {
            a[c] = from_lut(forward[ab[c]]);
            b[c] = from_lut(forward[bb[c]]);
        }
    });
    return tone_pair(version, src, pair);
}

std::vector<uint8_t> retone_yuv(const Image& src, const WhiteBalance& source_awb,
                                const WhiteBalance& target_awb, float gain,
                                const Curve8& source_curve, const Inverse& source_inverse,
                                const Curve8& target_curve, bool normalize_awb_gain) {
    const auto count = validate(src, ColorInput::yuv);
    require(std::isfinite(gain) && gain > 0.f, "Invalid digital gain");
    std::vector<uint8_t> output(count * 3);
    constexpr float threshold = 1.f / 512.f;
    bool matrix_changed = false;
    for (size_t i = 0; i < source_awb.rgb_to_rgb.size(); ++i) {
        const float difference = source_awb.rgb_to_rgb[i] - target_awb.rgb_to_rgb[i];
        require(std::isfinite(difference), "Invalid white balance matrix");
        matrix_changed |= std::abs(difference) > threshold;
    }
    const bool gains_changed = source_awb.gains_q9 != target_awb.gains_q9;
    if (std::abs(gain - 1.f) <= threshold && !matrix_changed && !gains_changed && source_curve == target_curve) {
        for (size_t c = 0; c < 3; ++c) {
            for (size_t y = 0; y < src.height; ++y) {
                std::copy_n(src.data + c * src.plane_stride + y * src.row_stride, src.width,
                            output.data() + c * count + y * src.width);
            }
        }
        return output;
    }

    std::array<Curve10, 3> curves{};
    FixedMatrix ccm{};
    Curve10 inverse{};
    if (matrix_changed) {
        ccm = white_balance_matrix(source_awb, target_awb, gain, normalize_awb_gain);
        inverse = expand_inverse(source_inverse);
        curves[0] = expand_forward(target_curve);
    } else {
        const std::array<size_t, 3> channels{0, 1, 3};
        for (size_t c = 0; c < 3; ++c) {
            const auto i = channels[c];
            require(source_awb.gains_q9[i] > 0 && target_awb.gains_q9[i] > 0, "Invalid white balance gain");
            const float channel_gain = gains_changed ? gain * target_awb.gains_q9[i] / source_awb.gains_q9[i] : gain;
            curves[c] = retone(source_inverse, target_curve, as_q8(channel_gain));
        }
    }

    for (size_t y = 0; y < src.height; ++y) {
        const auto* row = src.data + y * src.row_stride;
        for (size_t x = 0; x < src.width; ++x) {
            const Pixel yuv{row[x], row[x + src.plane_stride] - 128, row[x + 2 * src.plane_stride] - 128};
            const auto rgb = transform(yuv_to_rgb, yuv, 10);
            Pixel toned{};
            if (matrix_changed) {
                const Pixel linear{inverse[rgb[0]], inverse[rgb[1]], inverse[rgb[2]]};
                const auto balanced = transform(ccm.values, linear, ccm.shift);
                for (size_t c = 0; c < 3; ++c) toned[c] = curves[0][balanced[c]];
            } else {
                for (size_t c = 0; c < 3; ++c) toned[c] = curves[c][rgb[c]];
            }
            const int rounding = 64 * dither[(x + 7 * y) % dither.size()];
            for (size_t c = 0; c < 3; ++c) {
                output[c * count + y * src.width + x] =
                    clip8(floor_div(dot(rgb_to_yuv, c, toned) + rounding, 16384) + (c == 0 ? 0 : 128));
            }
        }
    }
    return output;
}

}  // namespace tone
