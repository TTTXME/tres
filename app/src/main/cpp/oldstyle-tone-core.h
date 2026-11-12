// P630b / P630e «Oldstyle»: the tone of one frame through the owner's tone-port module and supplement, without the JNI (the
// JNI in oldstyle-tone-jni.cpp calls render(); a host check or benchmark includes this file alone).
// P636 (speed, the same bytes): with more than one thread (tone::set_threads) the frame's passes run in bands of rows on the
// module's threads (third_party/tone_port/src/parallel.h) and the copies between them go:
//   - the input times its gain through a table of the 65536 int16 values (the same expression per value) into storage that
//     is not zeroed first;
//   - a frame already a multiple of 32 goes to the 8-bit HDR as it is (no padded copy, no crop copy);
//   - the low-frequency colour's 8 x 8 boxes and its transfer (the supplement's apply_low_frequency_delta, its arithmetic
//     without the branches) in bands;
//   - dehaze / tint and the output saturation in ONE pass over the RGB8 straight into the caller's buffer: the supplement's
//     dehaze_stats on the samples it reads (every second pixel of every second row), its tables, and its per-pixel
//     arithmetic (stages_fast below, checked against the supplement's own dehaze_yuv444 by tools/check_tone_port.py);
// with one thread the path of P630e runs as it was (render_reference: the reference of the fast one).
#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "tone_port.h"
#include "third_party/tone_port/src/parallel.h"
#include "third_party/tone_port/src/rgb8_hdr.h"
// P630e: the owner's supplement (the HDR plans, the fusion gains, WB / CCM), its C++ block unchanged, in this unit
#include "third_party/tone_supplement/tone_supplement.cpp"

namespace oldstyle {

inline int16_t saturate16(float v) {
    if (!(v == v)) return 0;
    return static_cast<int16_t>(std::lround(std::max(-32768.f, std::min(32767.f, v))));
}

/** The planar frame times a gain (FP32, rounded, saturated to int16). */
inline std::vector<int16_t> scaled(const int16_t* src, size_t count, float gain) {
    std::vector<int16_t> out(count);
    for (size_t i = 0; i < count; ++i) out[i] = gain == 1.f ? src[i] : saturate16(src[i] * gain);
    return out;
}

/**
 * P630e: V1's input to finish_raw27_rgb at most {@link #V1_CEILING} (2 x white). The module's CCM sums in Q16 modulo 2^32 and keeps
 * the low 16 bits of the result's high half as signed: a channel far past white (a light at the SDR gain, or the low-frequency
 * reference at the long gain) times a CCM row above 1 wraps there to a negative - a black hole in the light (seen on 140250's sun).
 * Two whites keep every row of a camera CCM (|row| < 4) inside the range; past white the 2.7 curve is 255 either way.
 */
constexpr float V1_CEILING = 8190.f;

/**
 * P630h: the ceiling for a CCM whose rows are larger than a camera's (the settings' matrix share above 1): no row's sum of
 * magnitudes times the ceiling leaves the signed 16 bits. A camera CCM (|row| < 4): V1_CEILING as before.
 */
inline float v1_ceiling(const std::array<float, 9>& ccm) {
    float worst = 0.f;
    for (int r = 0; r < 3; ++r) worst = std::max(worst, std::fabs(ccm[r * 3]) + std::fabs(ccm[r * 3 + 1]) + std::fabs(ccm[r * 3 + 2]));
    return worst > 4.f ? std::min(V1_CEILING, 32760.f / worst) : V1_CEILING;
}

inline std::vector<int16_t> scaled27(const int16_t* src, size_t count, float gain, float ceiling = V1_CEILING) {
    std::vector<int16_t> out(count);
    for (size_t i = 0; i < count; ++i) out[i] = saturate16(std::min(ceiling, src[i] * gain));
    return out;
}

/**
 * P630h: the settings' shape of a tone curve's output v (0..1 of the curve's top): the midtones as a power 2^-mid (mid > 0
 * brighter), then the S-contrast as a share of the smoothstep (contrast < 0: its mirror); monotonic for mid in any range and
 * contrast in -1 .. 1, 0 and 1 stay.
 */
inline float shape(float v, float mid, float contrast) {
    v = std::pow(std::clamp(v, 0.f, 1.f), std::exp2(-mid));
    const float s = v * v * (3.f - 2.f * v);
    return std::clamp(v + contrast * (s - v), 0.f, 1.f);
}

inline bool shaped(float mid, float contrast) { return std::fabs(mid) >= 1e-4f || std::fabs(contrast) >= 1e-4f; }

/** The module's curve through {@link #shape} (its top kept, monotonic as the module's inverse needs); unshaped: the curve itself. */
template <class Curve>
Curve shaped_curve(const Curve& curve, float mid, float contrast) {
    if (!shaped(mid, contrast)) return curve;
    contrast = std::clamp(contrast, -1.f, 1.f);
    mid = std::clamp(mid, -4.f, 4.f);
    const float top = float(*std::max_element(curve.begin(), curve.end()));
    Curve out = curve;
    if (!(top > 0.f)) return out;
    typename Curve::value_type last = 0;
    for (size_t i = 0; i < curve.size(); ++i) {
        const auto v = static_cast<typename Curve::value_type>(std::lround(top * shape(curve[i] / top, mid, contrast)));
        last = std::max(last, v);
        out[i] = last;
    }
    return out;
}

/** An RGB8 frame repeated at its right and bottom edges to multiples of 32 (the 8-bit HDR's size rule; global phase at 0, 0). */
inline std::vector<uint8_t> pad32(const std::vector<uint8_t>& rgb, size_t w, size_t h, size_t pw, size_t ph) {
    std::vector<uint8_t> out(pw * ph * 3);
    for (size_t y = 0; y < ph; ++y) {
        const uint8_t* row = rgb.data() + std::min(y, h - 1) * w * 3;
        uint8_t* dst = out.data() + y * pw * 3;
        std::memcpy(dst, row, w * 3);
        for (size_t x = w; x < pw; ++x) std::memcpy(dst + x * 3, row + (w - 1) * 3, 3);
    }
    return out;
}

/**
 * P630e: the stages of the supplement's YUV routes carried over to our linear RGB route, after (or, for the low-frequency colour,
 * inside) the module's tone. Layout of the Java side's float[] (OldstyleNative.POST_*):
 *   0 dehaze on, 1 max_dark, 2 percentile (%), 3 corner_strength,
 *   4 output saturation on, 5 sat, 6 vibrance, 7 sat per stop, 8 vibrance per stop,
 *   9 low-frequency colour on (V1 HDR), 10 its motion threshold (8-bit), 11 chroma only,
 *   P630h (absent in a 12-entry array: the module's own): 12 the low-frequency colour's share (0..1), 13 the tone curve's
 *   midtones, 14 its S-contrast (shape above).
 */
struct Post {
    bool dehaze = false;
    int max_dark = 64;
    float percentile = 0.2f, corner = 0.f;
    bool saturation = false;
    float sat = 0.f, vibrance = 1.f, sat_per_stop = 0.f, vibrance_per_stop = 0.f;
    bool low_frequency = false;
    int lf_threshold = 16;
    bool lf_chroma = true;
    float lf_strength = 1.f, curve_mid = 0.f, curve_contrast = 0.f;
};

/** {@code v} up to a multiple of 8. */
inline size_t up8(size_t v) { return (v + 7) / 8 * 8; }

/**
 * The dehaze's per-8x8 U / V gain grid: our lens-shading gain map (sw x sh nodes, two values a node: the U and V gains; node i at
 * (i + 0.5) / sw of the frame, as the GPU samples it) bilinearly at each tile's centre; no map: the unit grid.
 */
inline std::vector<tone_supplement::ChromaTileGain> tile_gains(const float* shading, int sw, int sh, size_t w, size_t h,
                                                        size_t tw, size_t th) {
    std::vector<tone_supplement::ChromaTileGain> grid(tw * th, tone_supplement::ChromaTileGain{1.f, 1.f});
    if (!shading || sw < 2 || sh < 2) return grid;
    for (size_t ty = 0; ty < th; ++ty) {
        for (size_t tx = 0; tx < tw; ++tx) {
            const float u = std::clamp(std::min(1.f, (tx * 8 + 4.f) / w) * sw - .5f, 0.f, float(sw - 1));
            const float v = std::clamp(std::min(1.f, (ty * 8 + 4.f) / h) * sh - .5f, 0.f, float(sh - 1));
            const int x0 = std::min(sw - 2, static_cast<int>(u)), y0 = std::min(sh - 2, static_cast<int>(v));
            const float fx = u - x0, fy = v - y0;
            for (int c = 0; c < 2; ++c) {
                auto at = [&](int x, int y) { return shading[(size_t(y) * sw + x) * 2 + c]; };
                const float top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx;
                const float bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx;
                grid[ty * tw + tx][c] = top + (bottom - top) * fy;
            }
        }
    }
    return grid;
}

/** Dehaze statistics of the last frame, for the log: shadow, white, dark cut. */
struct DehazeLog { int shadow = -1, white = -1, dark_cut = -1; };

/**
 * The supplement's dehaze / tint on our RGB8: rgb8_to_yuv444 (Q7), dehaze_yuv444 on the frame grown to multiples of 8 (edge
 * repeated, the 8 x 8 tiles from the frame's origin), yuv444_to_rgb8 (Q6) back into the w x h picture.
 */
inline DehazeLog dehaze_rgb8(uint8_t* rgb, size_t w, size_t h, const Post& p, const float* shading, int sw, int sh) {
    const size_t pw = up8(std::max<size_t>(w, 8)), ph = up8(std::max<size_t>(h, 8));
    std::vector<uint8_t> y(pw * ph), u(pw * ph), v(pw * ph);
    for (size_t yy = 0; yy < ph; ++yy) {
        const uint8_t* row = rgb + std::min(yy, h - 1) * w * 3;
        for (size_t xx = 0; xx < pw; ++xx) {
            const uint8_t* px = row + std::min(xx, w - 1) * 3;
            const auto o = tone_supplement::rgb8_to_yuv444({px[0], px[1], px[2]});
            y[yy * pw + xx] = o[0];
            u[yy * pw + xx] = o[1];
            v[yy * pw + xx] = o[2];
        }
    }
    const auto grid = tile_gains(shading, sw, sh, w, h, pw / 8, ph / 8);
    const auto stats = tone_supplement::dehaze_stats(y.data(), u.data(), v.data(), int(pw), int(ph), std::ptrdiff_t(pw),
                                                     p.max_dark, p.percentile);
    tone_supplement::dehaze_yuv444(y.data(), u.data(), v.data(), int(pw), int(ph), std::ptrdiff_t(pw), p.max_dark, p.percentile,
                                   p.corner, grid.data(), std::ptrdiff_t(pw / 8));
    for (size_t yy = 0; yy < h; ++yy) {
        for (size_t xx = 0; xx < w; ++xx) {
            const size_t i = yy * pw + xx;
            const auto o = tone_supplement::yuv444_to_rgb8(y[i], u[i], v[i]);
            std::memcpy(rgb + (yy * w + xx) * 3, o.data(), 3);
        }
    }
    return {stats.shadow, stats.white, stats.dark_cut};
}

/** The supplement's output saturation (output_saturation_params at the finish gain, make_saturation, saturate_rgb8). */
inline void saturate_rgb8(uint8_t* rgb, size_t w, size_t h, bool profile31, const Post& p, float finish_gain) {
    const auto sv = tone_supplement::output_saturation_params(p.sat, p.vibrance, finish_gain, p.sat_per_stop, p.vibrance_per_stop);
    const auto table = tone_supplement::make_saturation(profile31, sv[0], sv[1]);
    if (table.bypass) return;
    const size_t vw = w & ~size_t(15), vh = h & ~size_t(15);
    for (size_t y = 0; y < h; ++y) {
        for (size_t x = 0; x < w; ++x) {
            uint8_t* px = rgb + (y * w + x) * 3;
            const auto o = tone_supplement::saturate_rgb8({px[0], px[1], px[2]}, table, x < vw && y < vh);
            std::memcpy(px, o.data(), 3);
        }
    }
}

/** The 3 x 3 binomial low-pass of an RGB8 grid (edges clamped, rounded). */
inline std::vector<uint8_t> low_pass(const std::vector<uint8_t>& g, size_t w, size_t h) {
    std::vector<uint8_t> out(g.size());
    static const int k[3] = {1, 2, 1};
    for (size_t y = 0; y < h; ++y) {
        for (size_t x = 0; x < w; ++x) {
            for (int c = 0; c < 3; ++c) {
                int sum = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    const size_t yy = std::min(h - 1, size_t(std::max<long>(0, long(y) + dy)));
                    for (int dx = -1; dx <= 1; ++dx) {
                        const size_t xx = std::min(w - 1, size_t(std::max<long>(0, long(x) + dx)));
                        sum += k[dy + 1] * k[dx + 1] * g[(yy * w + xx) * 3 + c];
                    }
                }
                out[(y * w + x) * 3 + c] = static_cast<uint8_t>((sum + 8) / 16);
            }
        }
    }
    return out;
}

/**
 * The low-frequency colour of V1's HDR (the supplement's transfer, with our merge as its colour reference): the reference is the
 * linear frame itself, 8 x 8 box-averaged, at the long gain through finish_raw27_rgb (the long exposure's CCM and tone curve) -
 * the colour the bright virtual exposure would have without its 8-bit retone of the short one; the current grid the bright
 * exposure 8 x 8 box-averaged; both low-passed (3 x 3 binomial), then make_low_frequency_delta and apply_low_frequency_delta on
 * the bright exposure before the fusion (tone_port's BrightHook).
 */
struct LowFrequency {
    const int16_t* src;
    size_t w, h;
    float gain_long;
    std::array<float, 9> ccm;
    const Post* post;
    const tone::Curve8* curve;
    float ceiling;
    int scale = -1;
    std::string error;
};

inline void low_frequency_hook(uint8_t* bright, size_t pw, size_t ph, void* user) {
    auto* lf = static_cast<LowFrequency*>(user);
    try {
        const size_t gw = pw / 8, gh = ph / 8, plane = lf->w * lf->h, cells = gw * gh;
        if (gw < 2 || gh < 2) return;
        std::vector<int16_t> small(cells * 3);
        std::vector<uint8_t> current(cells * 3);
        for (size_t gy = 0; gy < gh; ++gy) {
            for (size_t gx = 0; gx < gw; ++gx) {
                double sum[3] = {0, 0, 0};
                int cur[3] = {0, 0, 0};
                for (size_t y = gy * 8; y < gy * 8 + 8; ++y) {
                    const size_t sy = std::min(y, lf->h - 1);
                    for (size_t x = gx * 8; x < gx * 8 + 8; ++x) {
                        const size_t sx = std::min(x, lf->w - 1);
                        for (int c = 0; c < 3; ++c) {
                            sum[c] += lf->src[c * plane + sy * lf->w + sx];
                            cur[c] += bright[(y * pw + x) * 3 + c];
                        }
                    }
                }
                const size_t cell = gy * gw + gx;
                for (int c = 0; c < 3; ++c) {
                    small[c * cells + cell] = saturate16(std::min(lf->ceiling, float(sum[c] / 64.0) * lf->gain_long));
                    current[cell * 3 + c] = static_cast<uint8_t>((cur[c] + 32) / 64);
                }
            }
        }
        const auto reference = tone::finish_raw27_rgb({small.data(), small.size(), gw, gh, gw, cells}, lf->ccm, *lf->curve);
        const auto ref = low_pass(reference, gw, gh), now = low_pass(current, gw, gh);
        std::vector<uint8_t> delta(cells * 3);
        lf->scale = tone_supplement::make_low_frequency_delta(delta.data(), std::ptrdiff_t(gw * 3), ref.data(), std::ptrdiff_t(gw * 3),
                now.data(), std::ptrdiff_t(gw * 3), int(gw), int(gh), uint8_t(lf->post->lf_threshold), lf->post->lf_chroma);
        // P630h: the settings' share of the transfer (the supplement's scale is x / 32)
        if (lf->post->lf_strength < 1.f) lf->scale = std::clamp(int(std::lround(lf->scale * lf->post->lf_strength)), 0, 32);
        if (lf->scale > 0) tone_supplement::apply_low_frequency_delta(bright, int(pw), int(ph), std::ptrdiff_t(pw * 3), delta.data(),
                                                                      std::ptrdiff_t(gw * 3), lf->scale);
    } catch (const std::exception& e) {
        lf->error = e.what();
    }
}

// ---- P636: the fast path (more than one thread), the same bytes as the functions above ----

/** saturate16(min(ceiling, v * gain)) of every int16 value v (ceiling < 0: saturate16(v * gain), v itself at gain 1). */
inline std::vector<int16_t> gain_table(float gain, float ceiling) {
    std::vector<int16_t> table(65536);
    for (int i = 0; i < 65536; ++i) {
        const int16_t v = static_cast<int16_t>(static_cast<uint16_t>(i));
        table[i] = ceiling >= 0.f ? saturate16(std::min(ceiling, v * gain)) : gain == 1.f ? v : saturate16(v * gain);
    }
    return table;
}

/** The planar frame through {@code table} (indexed by the sample's bits), in bands; the storage is not zeroed first. */
inline std::unique_ptr<int16_t[]> scaled_fast(const int16_t* src, size_t count, const std::vector<int16_t>& table) {
    std::unique_ptr<int16_t[]> out(new int16_t[count]);
    const std::ptrdiff_t block = 1 << 16, blocks = std::ptrdiff_t((count + block - 1) / block);
    int16_t* dst = out.get();
    tone::detail::parallel_rows(0, blocks, block, [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        const size_t end = std::min(count, size_t(last) * block);
        for (size_t i = size_t(first) * block; i < end; ++i) dst[i] = table[static_cast<uint16_t>(src[i])];
    }, 4);
    return out;
}

/** memcpy in bands. */
inline void copy_fast(uint8_t* dst, const uint8_t* src, size_t bytes) {
    const std::ptrdiff_t block = 1 << 18, blocks = std::ptrdiff_t((bytes + block - 1) / block);
    tone::detail::parallel_rows(0, blocks, block, [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        const size_t begin = size_t(first) * block, end = std::min(bytes, size_t(last) * block);
        std::memcpy(dst + begin, src + begin, end - begin);
    }, 4);
}

// The supplement's per-pixel integer functions without their branches, for the pass below (the same value for every input
// the pass can give them; bench_oldstyle_tone --exhaustive compares each with the supplement's over all 2^24 pixels):
//   floor_shift(v, b) is floor(v / 2^b), which is the arithmetic shift v >> b; signed16(v) is the low 16 bits as int16.
static_assert((-1 >> 1) == -1 && (-257 >> 8) == -2, "arithmetic right shift expected");

inline uint8_t clip8_fast(int x) { return static_cast<uint8_t>(x < 0 ? 0 : x > 255 ? 255 : x); }

/** low_frequency_hook with its boxes and its transfer in bands. */
inline void low_frequency_hook_fast(uint8_t* bright, size_t pw, size_t ph, void* user) {
    auto* lf = static_cast<LowFrequency*>(user);
    try {
        const size_t gw = pw / 8, gh = ph / 8, plane = lf->w * lf->h, cells = gw * gh;
        if (gw < 2 || gh < 2) return;
        std::vector<int16_t> small(cells * 3);
        std::vector<uint8_t> current(cells * 3);
        tone::detail::parallel_rows(0, std::ptrdiff_t(gh), std::ptrdiff_t(pw) * 8 * 6, [&](std::ptrdiff_t first, std::ptrdiff_t last) {
            for (size_t gy = size_t(first); gy < size_t(last); ++gy) {
                for (size_t gx = 0; gx < gw; ++gx) {
                    double sum[3] = {0, 0, 0};
                    int cur[3] = {0, 0, 0};
                    for (size_t y = gy * 8; y < gy * 8 + 8; ++y) {
                        const size_t sy = std::min(y, lf->h - 1);
                        for (size_t x = gx * 8; x < gx * 8 + 8; ++x) {
                            const size_t sx = std::min(x, lf->w - 1);
                            for (int c = 0; c < 3; ++c) {
                                sum[c] += lf->src[c * plane + sy * lf->w + sx];
                                cur[c] += bright[(y * pw + x) * 3 + c];
                            }
                        }
                    }
                    const size_t cell = gy * gw + gx;
                    for (int c = 0; c < 3; ++c) {
                        small[c * cells + cell] = saturate16(std::min(lf->ceiling, float(sum[c] / 64.0) * lf->gain_long));
                        current[cell * 3 + c] = static_cast<uint8_t>((cur[c] + 32) / 64);
                    }
                }
            }
        }, 4);
        const auto reference = tone::finish_raw27_rgb({small.data(), small.size(), gw, gh, gw, cells}, lf->ccm, *lf->curve);
        const auto ref = low_pass(reference, gw, gh), now = low_pass(current, gw, gh);
        std::vector<uint8_t> delta(cells * 3);
        lf->scale = tone_supplement::make_low_frequency_delta(delta.data(), std::ptrdiff_t(gw * 3), ref.data(), std::ptrdiff_t(gw * 3),
                now.data(), std::ptrdiff_t(gw * 3), int(gw), int(gh), uint8_t(lf->post->lf_threshold), lf->post->lf_chroma);
        // P630h: the settings' share of the transfer (the supplement's scale is x / 32), as low_frequency_hook's
        if (lf->post->lf_strength < 1.f) lf->scale = std::clamp(int(std::lround(lf->scale * lf->post->lf_strength)), 0, 32);
        if (lf->scale <= 0) return;
        if (pw < 16 || ph < 16 || pw % 8 || ph % 8) { // the supplement's own refusal, as the whole call gives it
            tone_supplement::apply_low_frequency_delta(bright, int(pw), int(ph), std::ptrdiff_t(pw * 3), delta.data(),
                                                       std::ptrdiff_t(gw * 3), lf->scale);
            return;
        }
        // the supplement's transfer (apply_low_frequency_delta: rows and columns 4 .. size - 5, each from the four delta
        // cells around it), its arithmetic without the branches of floor_shift / signed16 / clip8, rows in bands
        const int scale = lf->scale;
        const std::ptrdiff_t stride = std::ptrdiff_t(pw * 3), delta_stride = std::ptrdiff_t(gw * 3);
        tone::detail::parallel_rows(4, std::ptrdiff_t(ph) - 4, std::ptrdiff_t(pw) * 12, [&](std::ptrdiff_t first, std::ptrdiff_t last) {
            for (std::ptrdiff_t y = first; y < last; ++y) {
                const int fy = int((y - 4) % 8);
                const uint8_t* top = delta.data() + ((y - 4) / 8) * delta_stride;
                const uint8_t* bottom = top + delta_stride;
                uint8_t* row = bright + y * stride;
                for (int x = 4; x < int(pw) - 4; ++x) {
                    const int fx = (x - 4) % 8;
                    const int pos = 3 * ((x - 4) / 8);
                    for (int c = 0; c < 3; ++c) {
                        const int k = pos + c;
                        const int a = scale * ((8 - fx) * (top[k] - 128) + fx * (top[k + 3] - 128));
                        const int b = scale * ((8 - fx) * (bottom[k] - 128) + fx * (bottom[k + 3] - 128));
                        const int dy = static_cast<int16_t>(static_cast<uint16_t>(b - a + 4)) >> 3;
                        const int dv = static_cast<int16_t>(static_cast<uint16_t>(a + 128 + fy * dy)) >> 8;
                        row[3 * x + c] = clip8_fast(row[3 * x + c] + dv);
                    }
                }
            }
        });
    } catch (const std::exception& e) {
        lf->error = e.what();
    }
}

/** The dither of the supplement's dehaze_yuv444 (its table, copied: the function keeps it to itself). */
constexpr std::array<uint8_t, 128> kDehazeDither{
    181,60,30,155,115,117,169,197,89,169,106,215,213,65,157,149,
    138,222,67,81,30,240,165,122,163,139,165,139,184,133,254,55,
    27,28,16,103,114,93,195,160,197,238,249,49,35,178,24,134,
    135,220,124,100,171,189,133,89,38,150,67,11,193,62,113,176,
    91,188,101,174,180,113,5,84,108,69,50,210,110,227,100,196,
    101,206,193,96,55,202,243,83,171,112,213,196,42,220,253,131,
    226,150,39,51,104,191,211,202,81,136,23,28,34,173,126,48,
    126,37,14,217,143,237,178,149,208,225,253,0,221,156,253,135
};

inline void rgb8_to_yuv444_fast(int R, int G, int B, int& Y, int& U, int& V) {
    Y = clip8_fast((38 * R + 75 * G + 15 * B + 64) >> 7);
    U = clip8_fast(128 + ((-22 * R - 42 * G + 64 * B + 64) >> 7));
    V = clip8_fast(128 + ((64 * R - 54 * G - 10 * B + 64) >> 7));
}

inline void yuv444_to_rgb8_fast(int y, int u, int v, int& R, int& G, int& B) {
    const int U = u - 128, V = v - 128, base = 64 * y + 32;
    R = clip8_fast((base + 90 * V) >> 6);
    G = clip8_fast((base - 22 * U - 46 * V) >> 6);
    B = clip8_fast((base + 113 * U) >> 6);
}

inline void saturate_rgb8_fast(int& R, int& G, int& B, const tone_supplement::SatTable& table, bool vector_domain) {
    const int mx = std::max(R, std::max(G, B)), mn = std::min(R, std::min(G, B));
    const int coeff = table.constant ? table.constant_q8 : table.q8[tone_supplement::sat_index(mx, mn)];
    int cr = coeff * (2 * R - mx - mn) + 256, cg = coeff * (2 * G - mx - mn) + 256, cb = coeff * (2 * B - mx - mn) + 256;
    if (vector_domain) {
        cr = static_cast<int16_t>(static_cast<uint16_t>(cr));
        cg = static_cast<int16_t>(static_cast<uint16_t>(cg));
        cb = static_cast<int16_t>(static_cast<uint16_t>(cb));
    }
    R = clip8_fast(R + (cr >> 9));
    G = clip8_fast(G + (cg >> 9));
    B = clip8_fast(B + (cb >> 9));
}

/**
 * dehaze_rgb8 then saturate_rgb8 (each when on) of {@code rgb} into {@code dst} (may be {@code rgb} itself) in one pass over
 * bands of 8-row tile rows:
 *   - the statistics: the supplement's dehaze_stats reads every second pixel of every second row of the padded YUV planes; those
 *     samples alone are converted (rgb8_to_yuv444 of the edge-repeated pixel) into planes of half the rows whose row r holds
 *     padded row 2 r, handed to dehaze_stats with half the row stride (its index y * stride + x then lands on them);
 *   - the tables as dehaze_yuv444 makes them from the statistics; per pixel rgb8_to_yuv444, dehaze_yuv444's arithmetic with
 *     the tile's alpha, yuv444_to_rgb8, then saturate_rgb8 of the supplement (the branch-free forms above).
 */
inline DehazeLog stages_fast(const uint8_t* rgb, uint8_t* dst, size_t w, size_t h, bool profile31, const Post& p,
                             const float* shading, int sw, int sh, float finish_gain) {
    DehazeLog log;
    const size_t pw = up8(std::max<size_t>(w, 8)), ph = up8(std::max<size_t>(h, 8)), tw = pw / 8, th = ph / 8;
    tone_supplement::SatTable table;
    bool saturate = false;
    if (p.saturation) {
        const auto sv = tone_supplement::output_saturation_params(p.sat, p.vibrance, finish_gain, p.sat_per_stop, p.vibrance_per_stop);
        table = tone_supplement::make_saturation(profile31, sv[0], sv[1]);
        saturate = !table.bypass;
    }
    std::vector<tone_supplement::ChromaTileGain> grid;
    tone_supplement::HazeStats stats{};
    std::array<uint16_t, 256> y_lut{}, u_lut{}, v_lut{};
    if (p.dehaze) {
        const size_t hw = pw, hh = ph / 2; // half the rows, every second column used
        std::unique_ptr<uint8_t[]> ys(new uint8_t[hw * hh]), us(new uint8_t[hw * hh]), vs(new uint8_t[hw * hh]);
        tone::detail::parallel_rows(0, std::ptrdiff_t(hh), std::ptrdiff_t(pw) * 4, [&](std::ptrdiff_t first, std::ptrdiff_t last) {
            for (size_t r = size_t(first); r < size_t(last); ++r) {
                const uint8_t* row = rgb + std::min(2 * r, h - 1) * w * 3;
                for (size_t x = 0; x < pw; x += 2) {
                    const uint8_t* px = row + std::min(x, w - 1) * 3;
                    int Y, U, V;
                    rgb8_to_yuv444_fast(px[0], px[1], px[2], Y, U, V);
                    ys[r * hw + x] = static_cast<uint8_t>(Y);
                    us[r * hw + x] = static_cast<uint8_t>(U);
                    vs[r * hw + x] = static_cast<uint8_t>(V);
                }
            }
        }, 32);
        stats = tone_supplement::dehaze_stats(ys.get(), us.get(), vs.get(), int(pw), int(ph), std::ptrdiff_t(pw / 2), p.max_dark, p.percentile);
        // dehaze_yuv444's own checks and tables
        if (p.max_dark < 0 || p.max_dark > 255) throw std::invalid_argument("invalid dehaze image or gain grid");
        if (stats.dark_cut <= 0) throw std::invalid_argument("invalid dehaze cutoff");
        y_lut = tone_supplement::smooth_dehaze_luma(tone_supplement::dehaze_luma_q4_unsmoothed(stats), stats.shadow);
        u_lut = tone_supplement::tint_chroma_q4(stats.dark_yuv[1], p.corner);
        v_lut = tone_supplement::tint_chroma_q4(stats.dark_yuv[2], p.corner);
        grid = tile_gains(shading, sw, sh, w, h, tw, th);
        log = {stats.shadow, stats.white, stats.dark_cut};
    }
    if (!p.dehaze && !saturate) {
        if (dst != rgb) copy_fast(dst, rgb, w * h * 3);
        return log;
    }
    const size_t vw = w & ~size_t(15), vh = h & ~size_t(15);
    const bool dehaze = p.dehaze;
    tone::detail::parallel_rows(0, std::ptrdiff_t(th), std::ptrdiff_t(w) * 8 * 12, [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        std::vector<int> alphas(dehaze ? tw : 0);
        for (size_t ty = size_t(first); ty < size_t(last); ++ty) {
            if (dehaze) {
                for (size_t tx = 0; tx < tw; ++tx) {
                    const auto& gain = grid[ty * tw + tx];
                    const float inv = 1.0f / ((gain[0] + gain[1]) * 0.5f) + 0.2f;
                    alphas[tx] = static_cast<int>((inv * inv) * 4096.0f) / stats.dark_cut;
                }
            }
            const size_t y1 = std::min(h, ty * 8 + 8);
            for (size_t y = ty * 8; y < y1; ++y) {
                const uint8_t* in = rgb + y * w * 3;
                uint8_t* out = dst + y * w * 3;
                const int row5 = 5 * int(y);
                for (size_t x = 0; x < w; ++x) {
                    int R = in[x * 3], G = in[x * 3 + 1], B = in[x * 3 + 2];
                    if (dehaze) {
                        int Y, U, V;
                        rgb8_to_yuv444_fast(R, G, B, Y, U, V);
                        const int alpha = alphas[x >> 3];
                        const int weight = std::min((alpha * Y + 127) >> 8, 16);
                        const int d = kDehazeDither[(int(x) + row5 + U) & 127];
                        const uint8_t y2 = static_cast<uint8_t>((y_lut[Y] + (d >> 4)) >> 4);
                        const uint8_t u2 = static_cast<uint8_t>((16 * weight * U + (16 - weight) * u_lut[U] + d) >> 8);
                        const uint8_t v2 = static_cast<uint8_t>((16 * weight * V + (16 - weight) * v_lut[V] + d) >> 8);
                        yuv444_to_rgb8_fast(y2, u2, v2, R, G, B);
                    }
                    if (saturate) saturate_rgb8_fast(R, G, B, table, x < vw && y < vh);
                    out[x * 3] = static_cast<uint8_t>(R);
                    out[x * 3 + 1] = static_cast<uint8_t>(G);
                    out[x * 3 + 2] = static_cast<uint8_t>(B);
                }
            }
        }
    }, 2);
    return log;
}

/** The milliseconds from {@code since}. */
inline float ms_since(std::chrono::steady_clock::time_point since) {
    return std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - since).count();
}

/**
 * The tone of one frame into {@code dst} (w x h x 3 RGB8); the arguments as the JNI's render. {@code info} (8 floats): 0 the
 * low-frequency scale (-1: not run), 1 - 3 the dehaze's shadow / white / dark cut, 4 the module's ms (with the input's gain and the
 * 8-bit HDR), 5 the stages' ms, 6 the threads, 7 the input gain's ms inside 4. Throws on a module error.
 */
inline void render(int version, const int16_t* src, size_t w, size_t h, const float* ccm9, float gainShort, float gainLong,
                   bool hdr, bool postview, float saturation, const Post& post, const float* shading, int shadingW, int shadingH,
                   float* info, uint8_t* dst) {
    for (int i = 0; i < 8; ++i) info[i] = i < 4 ? -1.f : 0.f;
    const size_t count = w * h * 3;
    const bool fast = tone::threads() > 1;
    info[6] = float(tone::threads());
    const auto t0 = std::chrono::steady_clock::now();
    std::vector<uint8_t> rgb;
    if (version == 1) {
        std::array<float, 9> ccm{1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
        if (ccm9) std::copy(ccm9, ccm9 + 9, ccm.begin());
        ccm = tone_supplement::awb_ccm(ccm, 512, 512, 512, 512, false);
        // P630h: the settings' curve shape and the ceiling of a matrix stronger than a camera's (both paths, any thread count)
        const auto curve = shaped_curve(tone::yuv_curve(), post.curve_mid, post.curve_contrast);
        const float ceiling = v1_ceiling(ccm);
        if (fast) {
            const auto frame = scaled_fast(src, count, gain_table(hdr ? gainShort : gainLong, ceiling));
            info[7] = ms_since(t0);
            rgb = tone::finish_raw27_rgb({frame.get(), count, w, h, w, w * h}, ccm, curve);
        } else {
            const auto frame = scaled27(src, count, hdr ? gainShort : gainLong, ceiling);
            info[7] = ms_since(t0);
            rgb = tone::finish_raw27_rgb({frame.data(), frame.size(), w, h, w, w * h}, ccm, curve);
        }
        if (hdr) {
            const size_t pw = (w + 31) / 32 * 32, ph = (h + 31) / 32 * 32;
            const bool whole = fast && pw == w && ph == h; // P636: no padded copy of a frame that needs no padding
            std::vector<uint8_t> padded;
            if (!whole) padded = pad32(rgb, w, h, pw, ph);
            const uint8_t* frame8 = whole ? rgb.data() : padded.data();
            const auto inverse = tone::invert(curve);
            const auto q8 = static_cast<uint32_t>(std::lround(std::min(64.f, gainLong / gainShort) * 256.f));
            std::array<tone::Curve10, 3> bright{}, dark{};
            for (size_t c = 0; c < 3; ++c) {
                bright[c] = tone::retone(inverse, curve, q8);
                dark[c] = tone::retone(inverse, curve, 256);
            }
            LowFrequency lf{src, w, h, gainLong, ccm, &post, &curve, ceiling};
            auto toned = tone::tone_rgb8_hdr_luts(tone::Version::v27, tone::ColorInput::rgb,
                    {frame8, pw * ph * 3, pw, ph, pw * 3, 0}, bright, dark,
                    post.low_frequency ? (fast ? low_frequency_hook_fast : low_frequency_hook) : nullptr, &lf);
            if (!lf.error.empty()) throw std::runtime_error("low-frequency colour: " + lf.error);
            info[0] = float(lf.scale);
            if (whole) {
                rgb = std::move(toned);
            } else {
                rgb.resize(count);
                for (size_t y = 0; y < h; ++y) std::memcpy(rgb.data() + y * w * 3, toned.data() + y * pw * 3, w * 3);
            }
        }
    } else {
        const tone::RawRgbImage image{src, count, w, h, w, w * h};
        const auto curve = shaped_curve(tone::raw_curve_31(), post.curve_mid, post.curve_contrast); // P630h
        if (hdr) {
            rgb = tone::render_raw31_hdr(image, {gainShort, gainLong}, postview, curve, saturation);
        } else if (fast) {
            const auto frame = scaled_fast(src, count, gain_table(gainLong, -1.f));
            info[7] = ms_since(t0);
            rgb = tone::render_raw31_sdr({frame.get(), count, w, h, w, w * h}, curve, saturation);
        } else {
            const auto frame = scaled(src, count, gainLong);
            info[7] = ms_since(t0);
            rgb = tone::render_raw31_sdr({frame.data(), frame.size(), w, h, w, w * h}, curve, saturation);
        }
    }
    if (rgb.size() != count) throw std::runtime_error("unexpected output size");
    const auto t1 = std::chrono::steady_clock::now();
    DehazeLog d;
    if (fast) {
        d = stages_fast(rgb.data(), dst, w, h, version != 1, post, shading, shadingW, shadingH, gainLong);
    } else {
        if (post.dehaze) d = dehaze_rgb8(rgb.data(), w, h, post, shading, shadingW, shadingH);
        if (post.saturation) saturate_rgb8(rgb.data(), w, h, version != 1, post, gainLong);
        std::memcpy(dst, rgb.data(), count);
    }
    if (post.dehaze) {
        info[1] = float(d.shadow);
        info[2] = float(d.white);
        info[3] = float(d.dark_cut);
    }
    info[4] = std::chrono::duration<float, std::milli>(t1 - t0).count();
    info[5] = ms_since(t1);
}

}  // namespace oldstyle
