#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>

namespace tone_supplement {

struct ExposurePlan {
    bool hdr_scene;
    bool use_hdr;
    float short_candidate;
    float long_candidate;
    float short_plan;
    float long_plan;
};

float effective_tet(float exposure_ms, float analog, float digital) {
    return (exposure_ms * analog) * digital;
}

float compensate_tet(float tet, float ev, float min_tet, float max_tet) {
    return std::clamp(tet * std::pow(2.0f, ev), min_tet, max_tet);
}

void cap_capture_ratio(ExposurePlan& p, float max_ratio, float fraction_long) {
    const float ratio = p.long_candidate / p.short_candidate;
    if (ratio <= max_ratio) return;
    const float q = ratio / max_ratio;
    const float w = std::clamp(
        (fraction_long - 0.6000000238418579f) * 5.000000476837158f,
        0.0f, 1.0f);
    p.short_plan *= std::pow(q, w);
    p.long_plan /= std::pow(q, 1.0f - w);
}

ExposurePlan plan27(float short_candidate, float long_candidate, float fraction_long) {
    const float ratio = long_candidate / short_candidate;
    const bool enough_range = ratio >= 1.4500000476837158f;
    const bool use = enough_range && ratio <= 6.803999900817871f;
    ExposurePlan p{enough_range, use, short_candidate, long_candidate,
                   short_candidate, long_candidate};
    if (use) cap_capture_ratio(p, 3.5f, fraction_long);
    return p;
}

ExposurePlan plan31(float short_candidate, float long_candidate, float fraction_long,
                    bool raw_payload, bool hdr_disabled, float ev,
                    float min_tet, float max_tet) {
    const float ratio = long_candidate / short_candidate;
    const float max_ratio = raw_payload ? 8.0f : 3.5f;
    const float forgiveness = raw_payload ? 1.3999999761581421f : 1.9440000057220459f;
    const bool scene = ratio >= 1.3300000429153442f;
    const bool use = scene && !hdr_disabled && ratio <= max_ratio * forgiveness;
    ExposurePlan p{scene, use, short_candidate, long_candidate,
                   short_candidate, long_candidate};
    if (use) {
        cap_capture_ratio(p, max_ratio, fraction_long);
        p.short_plan = compensate_tet(p.short_plan, ev, min_tet, max_tet);
        p.long_plan = compensate_tet(p.long_plan, ev, min_tet, max_tet);
    }
    return p;
}

struct DigitalGains { float short_gain; float long_gain; };

DigitalGains fusion_gains(float actual_short_tet, float desired_short_tet,
                          float desired_long_tet, float common_gain, float max_gain) {
    constexpr float base = 0.9756097793579102f;
    const float adjusted_short = std::pow(base, desired_long_tet / desired_short_tet)
                               * desired_short_tet;
    const float short_gain = (adjusted_short / actual_short_tet) * common_gain;
    const float long_gain = (desired_long_tet / actual_short_tet) * common_gain;
    const float threshold = 1.0010000467300415f * max_gain;
    return {short_gain > threshold ? max_gain : short_gain,
            long_gain > threshold ? max_gain : long_gain};
}

DigitalGains fusion_gains_yuv(float actual_short_tet, float short_relative_gain,
                              float desired_long_tet, float common_gain, float max_gain) {
    const float desired_short_tet = actual_short_tet * short_relative_gain;
    return fusion_gains(actual_short_tet, desired_short_tet, desired_long_tet,
                        common_gain, max_gain);
}

using Matrix3 = std::array<float, 9>;

Matrix3 awb_ccm(const Matrix3& ccm, int r_q9, int ge_q9, int go_q9, int b_q9,
                bool normalize, float digital_gain = 1.0f) {
    std::array<float, 3> wb{
        r_q9 * (1.0f / 512.0f), (ge_q9 + go_q9) * (1.0f / 1024.0f),
        b_q9 * (1.0f / 512.0f)
    };
    if (normalize) {
        const float y = ((wb[0] * 77.0f + wb[2] * 29.0f) + wb[1] * 150.0f)
                      * (1.0f / 256.0f);
        for (float& value : wb) value *= 1.0f / y;
    }
    Matrix3 output{};
    for (int i = 0; i < 9; ++i) output[i] = (ccm[i] * wb[i % 3]) * digital_gain;
    return output;
}

using Rgb8 = std::array<uint8_t, 3>;

int floor_shift(int value, int bits) {
    if (value >= 0) return value >> bits;
    return -(((-value) + ((1 << bits) - 1)) >> bits);
}

int signed16(int value) {
    const int v = static_cast<uint16_t>(value);
    return v >= 32768 ? v - 65536 : v;
}

uint8_t clip8(int x) { return static_cast<uint8_t>(std::clamp(x, 0, 255)); }

Rgb8 yuv_to_rgb8(uint8_t y, uint8_t chroma0, uint8_t chroma1, bool nv21) {
    constexpr std::array<int, 9> uv{
        4096, 12, 5733, 4096, -1410, -2938, 4096, 7260, -25
    };
    constexpr std::array<int, 9> vu{
        4096, 5732, 13, 4096, -2938, -1410, 4096, -25, 7260
    };
    const auto& m = nv21 ? vu : uv;
    const int c0 = int(chroma0) - 128;
    const int c1 = int(chroma1) - 128;
    Rgb8 rgb{};
    for (int c = 0; c < 3; ++c) {
        const int dot = m[3*c] * y + m[3*c+1] * c0 + m[3*c+2] * c1;
        rgb[c] = clip8(floor_shift(dot + 2048, 12));
    }
    return rgb;
}

Rgb8 yuv444_to_rgb8(uint8_t y, uint8_t u, uint8_t v) {
    const int U = int(u) - 128;
    const int V = int(v) - 128;
    const int base = 64 * y + 32;
    return {clip8(floor_shift(base + 90 * V, 6)),
            clip8(floor_shift(base - 22 * U - 46 * V, 6)),
            clip8(floor_shift(base + 113 * U, 6))};
}

Rgb8 rgb8_to_yuv444(Rgb8 rgb) {
    const int R = rgb[0], G = rgb[1], B = rgb[2];
    return {clip8(floor_shift(38 * R + 75 * G + 15 * B + 64, 7)),
            clip8(128 + floor_shift(-22 * R - 42 * G + 64 * B + 64, 7)),
            clip8(128 + floor_shift(64 * R - 54 * G - 10 * B + 64, 7))};
}

std::array<float, 2> output_saturation_params(float sat, float vibrance,
        float finish_gain, float sat_per_stop, float vibrance_per_stop) {
    constexpr float epsilon = 0.00009999999747378752f;
    if (finish_gain <= 1.0010000467300415f ||
        (std::abs(sat_per_stop) < epsilon && std::abs(vibrance_per_stop) < epsilon)) {
        return {sat, vibrance};
    }
    const float stops = std::log(finish_gain) * 1.4426950216293335f;
    return {std::max(0.0f, sat + stops * sat_per_stop),
            vibrance + stops * vibrance_per_stop};
}

struct SatTable {
    bool bypass = false;
    bool constant = false;
    int constant_q8 = 0;
    std::array<int16_t, 8256> q8{};
};

int sat_index(int mx, int mn) {
    const int m = mx >> 1;
    return m * (m + 1) / 2 + (mn >> 1);
}

SatTable make_saturation(bool profile31, float sat, float vibrance) {
    SatTable t;
    const bool neutral_vibrance = std::abs(vibrance - 1.0f) < 1.0f / 256.0f;
    t.bypass = neutral_vibrance && std::abs(sat) < 1.0f / 256.0f;
    if (t.bypass) return t;
    t.constant = profile31 && neutral_vibrance;
    if (t.constant) {
        t.constant_q8 = static_cast<int>(std::round(sat * 256.0f));
        return t;
    }
    for (int mx = 2; mx <= 254; mx += 2) {
        const float inv_mx = 1.0f / mx;
        for (int mn = 0; mn < mx; mn += 2) {
            const float x = (mx - mn) * inv_mx;
            const float sum = static_cast<float>(mx + mn);
            const float mid = sum * 0.5f;
            const float adj = (sum / (2.0f - std::pow(x, vibrance)) - mid) / (mx - mid);
            const float weight = std::clamp(
                (x - 0.10000000149011612f) * 14.285714149475098f, 0.0f, 1.0f);
            const float v = ((sat - 1.0f) + adj) * (weight * 256.0f);
            t.q8[sat_index(mx, mn)] = static_cast<int16_t>(
                signed16(static_cast<int>(std::round(v))));
        }
    }
    return t;
}

Rgb8 saturate_rgb8(Rgb8 rgb, const SatTable& table, bool vector_domain) {
    if (table.bypass) return rgb;
    const int mx = *std::max_element(rgb.begin(), rgb.end());
    const int mn = *std::min_element(rgb.begin(), rgb.end());
    const int coeff = table.constant ? table.constant_q8 : table.q8[sat_index(mx, mn)];
    for (uint8_t& c : rgb) {
        int correction = coeff * (2 * c - mx - mn) + 256;
        if (vector_domain) correction = signed16(correction);
        c = clip8(c + floor_shift(correction, 9));
    }
    return rgb;
}

int make_low_frequency_delta(uint8_t* delta, std::ptrdiff_t delta_stride,
        const uint8_t* reference, std::ptrdiff_t reference_stride,
        const uint8_t* current, std::ptrdiff_t current_stride,
        int w, int h, uint8_t threshold, bool chroma_only) {
    if (w <= 0 || h <= 0 || delta_stride < 3 * w ||
        reference_stride < 3 * w || current_stride < 3 * w) {
        throw std::invalid_argument("invalid low-frequency RGB grid");
    }
    float total = 0.0f;
    for (int y = 0; y < h; ++y) {
        unsigned excess = 0;
        for (int x = 0; x < w; ++x) {
            const uint8_t* ref = reference + y * reference_stride + 3 * x;
            const uint8_t* dst = current + y * current_stride + 3 * x;
            int gain_q10 = 1024;
            if (chroma_only) {
                const int ref_y = 77 * ref[0] + 150 * ref[1] + 29 * ref[2];
                const int dst_y = 77 * dst[0] + 150 * dst[1] + 29 * dst[2];
                gain_q10 = std::min(65535, (1024 * dst_y) / std::max(1, ref_y));
            }
            for (int c = 0; c < 3; ++c) {
                const int diff = clip8((gain_q10 * ref[c] + 512) >> 10) - dst[c];
                delta[y * delta_stride + 3 * x + c] = clip8(128 + diff);
                excess += std::max(0, std::abs(diff) - threshold);
            }
        }
        total += static_cast<float>(excess);
    }
    const float mean = total / static_cast<float>(3 * w * h);
    const float w_reject = std::clamp((mean - 0.5f) * 2.0f, 0.0f, 1.0f);
    if (w_reject > 0.9900000095367432f) return 0;
    return static_cast<int>((1.0f - w_reject) * 32.0f);
}

void apply_low_frequency_delta(uint8_t* rgb, int w, int h, std::ptrdiff_t stride,
                               const uint8_t* delta, std::ptrdiff_t delta_stride,
                               int scale_x32) {
    if (w < 16 || h < 16 || w % 8 || h % 8 ||
        stride < 3 * w || delta_stride < 3 * (w / 8) ||
        scale_x32 < 0 || scale_x32 > 32) {
        throw std::invalid_argument("invalid RGB or low-frequency grid");
    }
    for (int y = 4; y < h - 4; ++y) {
        const int fy = (y - 4) % 8;
        const uint8_t* top = delta + ((y - 4) / 8) * delta_stride;
        const uint8_t* bottom = top + delta_stride;
        uint8_t* row = rgb + y * stride;
        for (int x = 4; x < w - 4; ++x) {
            const int fx = (x - 4) % 8;
            const int pos = 3 * ((x - 4) / 8);
            for (int c = 0; c < 3; ++c) {
                const int k = pos + c;
                const int a = scale_x32 * ((8 - fx) * (top[k] - 128)
                                                   + fx * (top[k + 3] - 128));
                const int b = scale_x32 * ((8 - fx) * (bottom[k] - 128)
                                                   + fx * (bottom[k + 3] - 128));
                const int dy = floor_shift(signed16(b - a + 4), 3);
                const int dv = floor_shift(signed16(a + 128 + fy * dy), 8);
                row[3*x+c] = clip8(row[3*x+c] + dv);
            }
        }
    }
}

struct HazeStats {
    int shadow;
    int white;
    int dark_cut;
    std::array<int, 3> dark_yuv;
};

HazeStats dehaze_stats(const uint8_t* y_plane, const uint8_t* u_plane,
                       const uint8_t* v_plane, int w, int h,
                       std::ptrdiff_t stride, int max_dark, float percentile) {
    std::array<int, 256> hist{};
    for (int y = 0; y < h; y += 2)
        for (int x = 0; x < w; x += 2)
            ++hist[y_plane[y*stride+x]];
    int mode = 6;
    int count = 0;
    for (int i = 6; i < max_dark / 2; ++i) {
        if (hist[i] > 11 * count / 10) {
            mode = i;
            count = hist[i];
        }
    }
    const int target = static_cast<int>((percentile * 0.01f) * ((w / 2) * (h / 2)));
    int p = 0;
    int cumulative = 0;
    for (int i = 0; i < max_dark / 2; ++i) {
        cumulative += hist[i];
        if (cumulative < target) p = i;
    }
    const int dark = std::min(p, mode);
    const int extra = hist[dark] > 6 * hist[dark / 3]
        ? 0 : 2 * std::max(8, std::min(2 * dark, max_dark));
    HazeStats result{(3 * dark / 2) & 255, 252, (max_dark + extra + 2) / 3, {}};
    count = hist[252];
    for (int i = 150; i <= 252; ++i) {
        if (hist[i] > count) {
            result.white = i;
            count = hist[i];
        }
    }
    std::array<int, 3> sum{0, 128, 128};
    int pixels = 1;
    for (int y = 0; y < h; y += 2) {
        for (int x = 0; x < w; x += 2) {
            const auto pos = y * stride + x;
            if (y_plane[pos] >= result.dark_cut) continue;
            sum[0] += y_plane[pos];
            sum[1] += u_plane[pos];
            sum[2] += v_plane[pos];
            ++pixels;
        }
    }
    for (int c = 0; c < 3; ++c) result.dark_yuv[c] = (sum[c] + pixels / 2) / pixels;
    return result;
}

std::array<uint16_t, 256> dehaze_luma_q4_unsmoothed(const HazeStats& stats) {
    const int span = stats.white - stats.shadow;
    if (span <= 0) throw std::invalid_argument("invalid dehaze luma span");
    std::array<uint16_t, 256> q4{};
    for (int y = 0; y < 256; ++y) {
        const int boosted = (16 * stats.white * std::max(0, y - stats.shadow) + span / 2) / span;
        q4[y] = static_cast<uint16_t>(std::min(4080, (3 * boosted + 16 * y + 2) / 4));
    }
    return q4;
}

std::array<uint16_t, 256> tint_chroma_q4(int dark_chroma, float corner_strength) {
    const float delta = static_cast<float>(dark_chroma - 128);
    const int a = static_cast<int>(128.5f - delta * corner_strength);
    const int b = static_cast<int>(128.5f + delta * (6.0f * corner_strength));
    const int lo = std::min(a, b);
    const int hi = std::max(a, b);
    std::array<uint16_t, 256> q4{};
    for (int c = 0; c < 256; ++c) {
        const int adjusted = c + 128 - std::clamp(c, lo, hi);
        q4[c] = static_cast<uint16_t>(4 * (c + 3 * adjusted));
    }
    return q4;
}

std::array<uint16_t, 256> smooth_dehaze_luma(
        const std::array<uint16_t, 256>& input, int shadow) {
    std::array<uint16_t, 256> result{};
    const int radius = shadow / 4 + 1;
    const float inv = 1.0f / (2 * radius + 1);
    for (int i = 0; i < 256; ++i) {
        const int r = std::min({i, 255 - i, radius});
        int sum = 0;
        for (int j = i - r; j <= i + r; ++j) sum += input[j];
        const float mean = r == radius ? sum * inv : float(sum) / (2 * r + 1);
        result[i] = static_cast<uint16_t>(std::clamp(int(mean + 0.5f), 0, 4080));
    }
    return result;
}

using ChromaTileGain = std::array<float, 2>;

void dehaze_yuv444(uint8_t* y_plane, uint8_t* u_plane, uint8_t* v_plane,
                   int w, int h, std::ptrdiff_t stride, int max_dark,
                   float percentile, float corner_strength,
                   const ChromaTileGain* gain_grid, std::ptrdiff_t gain_stride) {
    if (w < 8 || h < 8 || w % 8 || h % 8 || stride < w ||
        max_dark < 0 || max_dark > 255 || gain_stride < w / 8) {
        throw std::invalid_argument("invalid dehaze image or gain grid");
    }
    const auto stats = dehaze_stats(y_plane, u_plane, v_plane, w, h, stride,
                                   max_dark, percentile);
    if (stats.dark_cut <= 0) throw std::invalid_argument("invalid dehaze cutoff");
    const auto y_lut = smooth_dehaze_luma(dehaze_luma_q4_unsmoothed(stats), stats.shadow);
    const auto u_lut = tint_chroma_q4(stats.dark_yuv[1], corner_strength);
    const auto v_lut = tint_chroma_q4(stats.dark_yuv[2], corner_strength);
    constexpr std::array<uint8_t, 128> dither{
        181,60,30,155,115,117,169,197,89,169,106,215,213,65,157,149,
        138,222,67,81,30,240,165,122,163,139,165,139,184,133,254,55,
        27,28,16,103,114,93,195,160,197,238,249,49,35,178,24,134,
        135,220,124,100,171,189,133,89,38,150,67,11,193,62,113,176,
        91,188,101,174,180,113,5,84,108,69,50,210,110,227,100,196,
        101,206,193,96,55,202,243,83,171,112,213,196,42,220,253,131,
        226,150,39,51,104,191,211,202,81,136,23,28,34,173,126,48,
        126,37,14,217,143,237,178,149,208,225,253,0,221,156,253,135
    };
    for (int ty = 0; ty < h / 8; ++ty) {
        for (int tx = 0; tx < w / 8; ++tx) {
            const auto& gain = gain_grid[ty * gain_stride + tx];
            const float inv = 1.0f / ((gain[0] + gain[1]) * 0.5f) + 0.2f;
            const int alpha = static_cast<int>((inv * inv) * 4096.0f) / stats.dark_cut;
            for (int y = ty * 8; y < ty * 8 + 8; ++y) {
                for (int x = tx * 8; x < tx * 8 + 8; ++x) {
                    const auto i = y * stride + x;
                    const int Y = y_plane[i], U = u_plane[i], V = v_plane[i];
                    const int weight = std::min((alpha * Y + 127) >> 8, 16);
                    const int d = dither[(x + 5 * y + U) & 127];
                    y_plane[i] = static_cast<uint8_t>((y_lut[Y] + (d >> 4)) >> 4);
                    u_plane[i] = static_cast<uint8_t>((16 * weight * U
                                            + (16 - weight) * u_lut[U] + d) >> 8);
                    v_plane[i] = static_cast<uint8_t>((16 * weight * V
                                            + (16 - weight) * v_lut[V] + d) >> 8);
                }
            }
        }
    }
}

}  // namespace tone_supplement
