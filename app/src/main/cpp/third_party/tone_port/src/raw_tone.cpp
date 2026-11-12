#include "tone_port.h"
#include "raw_image.h"
#include "parallel.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace tone {
namespace {

int signed16(uint32_t value) {
    value &= 65535;
    return value >= 32768 ? static_cast<int>(value) - 65536 : static_cast<int>(value);
}

std::array<int32_t, 9> fixed_ccm(const std::array<float, 9>& ccm) {
    std::array<int32_t, 9> fixed{};
    for (size_t i = 0; i < ccm.size(); ++i) {
        const float scaled = std::nearbyint(ccm[i] * 65536.f);
        if (!std::isfinite(scaled) || scaled < -2147483648.f || scaled >= 2147483648.f) {
            throw std::invalid_argument("Invalid RAW color matrix coefficient");
        }
        fixed[i] = static_cast<int32_t>(scaled);
    }
    return fixed;
}

}  // namespace

RawExposureGains raw_hdr_gains_31(float short_current_tet, float short_desired_tet,
                                  float long_desired_tet, float max_digital_gain) {
    const auto positive = [](float value) { return std::isfinite(value) && value > 0.f; };
    if (!positive(short_current_tet) || !positive(short_desired_tet) ||
        !positive(long_desired_tet) || !positive(max_digital_gain)) {
        throw std::invalid_argument("Invalid RAW exposure parameters");
    }
    const float short_gain = (std::pow(.9756097793579102f, long_desired_tet / short_desired_tet) *
                              short_desired_tet) / short_current_tet;
    const float long_gain = long_desired_tet / short_current_tet;
    const float threshold = max_digital_gain * 1.0010000467300415f;
    return {short_gain > threshold ? max_digital_gain : short_gain,
            long_gain > threshold ? max_digital_gain : long_gain};
}

std::vector<uint8_t> finish_raw27_rgb(const RawRgbImage& src, const std::array<float, 9>& ccm,
                                      const Curve8& curve) {
    detail::validate_raw_planar(src, 3);
    const auto matrix = fixed_ccm(ccm);
    std::vector<uint8_t> result(3 * src.width * src.height);
    detail::parallel_rows(0, std::ptrdiff_t(src.height), 9 * std::ptrdiff_t(src.width),
                          [&](std::ptrdiff_t first, std::ptrdiff_t last) {
    for (size_t y = size_t(first); y < size_t(last); ++y) {
        for (size_t x = 0; x < src.width; ++x) {
            const size_t offset = y * src.row_stride + x;
            const std::array<uint16_t, 3> rgb{static_cast<uint16_t>(src.data[offset]),
                static_cast<uint16_t>(src.data[offset + src.plane_stride]),
                static_cast<uint16_t>(src.data[offset + 2 * src.plane_stride])};
            std::array<int, 3> gamma{};
            for (size_t c = 0; c < 3; ++c) {
                const auto* row = matrix.data() + c * 3;
                const uint32_t dot = uint32_t(row[0]) * rgb[0] + uint32_t(row[1]) * rgb[1] +
                                     uint32_t(row[2]) * rgb[2] + 32768;
                const int linear = signed16((dot >> 16) + 2);
                gamma[c] = curve[std::clamp(linear / 4, 0, 1023)];
            }
            const auto [low, high] = std::minmax_element(gamma.begin(), gamma.end());
            const float midpoint = (*low + *high) * .5f;
            for (size_t c = 0; c < 3; ++c) {
                const float adjusted = gamma[c] + (gamma[c] - midpoint) * .05f + .5f;
                result[3 * (y * src.width + x) + c] = static_cast<uint8_t>(std::clamp(adjusted, 0.f, 255.f));
            }
        }
    }
    });
    return result;
}

std::vector<uint16_t> tone_raw31_ldr(const RawRgbImage& src, const Curve12& curve) {
    detail::validate_raw_planar(src, 3);
    const size_t count = src.width * src.height;
    std::vector<uint16_t> result(3 * count);
    for (size_t c = 0; c < 3; ++c) {
        detail::parallel_rows(0, std::ptrdiff_t(src.height), 2 * std::ptrdiff_t(src.width),
                              [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        for (size_t y = size_t(first); y < size_t(last); ++y) {
            const auto* row = src.data + c * src.plane_stride + y * src.row_stride;
            for (size_t x = 0; x < src.width; ++x) {
                result[c * count + y * src.width + x] = curve[std::clamp<int>(row[x], 0, 4095)];
            }
        }
        });
    }
    return result;
}

}  // namespace tone
