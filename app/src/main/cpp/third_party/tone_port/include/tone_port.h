#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace tone {

enum class Version { v27, v31 };
enum class ColorInput { rgb, yuv };

using Curve8 = std::array<uint8_t, 1024>;
using Curve10 = std::array<uint16_t, 1024>;
using Curve12 = std::array<uint16_t, 4096>;
using Inverse = std::array<uint16_t, 256>;
using Matrix = std::array<int16_t, 9>;

struct Image {
    const uint8_t* data;
    size_t bytes;
    size_t width;
    size_t height;
    size_t row_stride;
    size_t plane_stride;
};

struct WhiteBalance {
    int32_t color_temperature;
    std::array<int32_t, 4> gains_q9;
    std::array<float, 9> rgb_to_rgb;
};

struct RawRgbImage {
    const int16_t* data;
    size_t samples;
    size_t width;
    size_t height;
    size_t row_stride;
    size_t plane_stride;
};

struct RawExposureGains {
    float short_gain;
    float long_gain;
};

struct RawUnsignedImage {
    const uint16_t* data;
    size_t samples;
    size_t width;
    size_t height;
    size_t row_stride;
    size_t plane_stride;
};

// SCAMERA P636: the threads the module's row loops may use (the output is the same bytes at any count). 1: the single-thread
// module as it was (the default; the environment's TONE_PORT_THREADS sets another default); 0 or less: the cores, at most 8.
void set_threads(int count);
int threads();

const Curve8& yuv_curve();
const Curve12& raw_curve_31();
Inverse invert(const Curve8& curve);
Curve10 expand_inverse(const Inverse& inverse);
Curve10 expand_forward(const Curve8& curve);
Curve10 retone(const Inverse& inverse, const Curve8& curve, uint32_t gain_q8);

std::vector<uint8_t> tone_rgb8_hdr_luts(Version version, ColorInput color, const Image& frame,
                                        const std::array<Curve10, 3>& bright,
                                        const std::array<Curve10, 3>& dark);
// SCAMERA P630e (the only change to this module): the same with a hook called on the bright (long) virtual RGB8 exposure
// (width x height, dense) between make_exposures and tone_pair - the supplement's point of the low-frequency colour transfer.
using BrightHook = void (*)(uint8_t* bright, size_t width, size_t height, void* user);
std::vector<uint8_t> tone_rgb8_hdr_luts(Version version, ColorInput color, const Image& frame,
                                        const std::array<Curve10, 3>& bright,
                                        const std::array<Curve10, 3>& dark, BrightHook hook, void* user);
std::vector<uint8_t> tone_rgb8_hdr_matrices(Version version, ColorInput color, const Image& frame,
                                            const Curve10& forward, const Curve10& inverse,
                                            const Matrix& bright, unsigned bright_shift,
                                            const Matrix& dark, unsigned dark_shift);

std::vector<uint16_t> tone_raw31_hdr(const RawRgbImage& src, float gain_short, float gain_long,
                                     bool postview, const Curve12& curve);
RawExposureGains raw_hdr_gains_31(float short_current_tet, float short_desired_tet,
                                  float long_desired_tet, float max_digital_gain);
std::vector<uint8_t> finish_raw27_rgb(const RawRgbImage& src, const std::array<float, 9>& ccm,
                                      const Curve8& curve);
std::vector<uint16_t> tone_raw31_ldr(const RawRgbImage& src, const Curve12& curve);
std::vector<int16_t> tone_raw31_opponent(const RawUnsignedImage& toned_rgb);
std::vector<uint8_t> pack_raw31_rgb8(const RawRgbImage& opponent, float saturation_adjustment,
                                     int origin_x = 0, int origin_y = 0);
std::vector<uint8_t> render_raw31_sdr(const RawRgbImage& linear_rgb, const Curve12& curve,
                                     float saturation_adjustment, int origin_x = 0, int origin_y = 0);
std::vector<uint8_t> render_raw31_hdr(const RawRgbImage& linear_rgb, RawExposureGains gains,
                                     bool postview, const Curve12& curve, float saturation_adjustment,
                                     int origin_x = 0, int origin_y = 0);
std::vector<uint8_t> retone_yuv(const Image& src, const WhiteBalance& source_awb,
                                const WhiteBalance& target_awb, float gain,
                                const Curve8& source_curve, const Inverse& source_inverse,
                                const Curve8& target_curve, bool normalize_awb_gain);

}  // namespace tone
