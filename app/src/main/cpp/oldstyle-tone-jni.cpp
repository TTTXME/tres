// P630b «Oldstyle»: the owner's tone-port module (third_party/tone_port, unchanged) behind the mode's JNI
// (processing/oldstyle/OldstyleNative). One frame of linear RGB16 in three planes (the 12-bit scale of the module: 4095 = white
// of the frame's own exposure), filled by the app strip by strip (deinterleave), then one call of the module on the whole frame
// (its spatial operator and the 3.1 shadow curve read the whole frame: no tiling), RGB8 interleaved out.
//   The exposures (P630e): the supplement's plan27 / plan31 (HDR decision, the TET cap with F) and fusion_gains /
//   raw_hdr_gains_31 from the frame's actual TET (plan below), the gains handed to render.
//   V1 (profile 2.7): finish_raw27_rgb (linear RGB before the CCM, awb_ccm of the app's CCM and WB, yuv_curve) at the SDR gain;
//     with HDR the frame at gS is the RGB8 of the 2.7 YUV route and tone_rgb8_hdr_luts(v27) makes the two virtual exposures from
//     it (retone of the curve's inverse at gL / gS and at 1), the frame padded to 32 (edge repeated).
//   V2 (profile 3.1): render_raw31_hdr (linear RGB after the CCM, the gains gS / gL, postview 8 / full 10 levels) or, without
//     HDR, render_raw31_sdr at the SDR gain.
#include <jni.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <stdexcept>
#include <string>
#include <vector>

#include "tone_port.h"
#include "third_party/tone_port/src/rgb8_hdr.h"
// P630e: the owner's supplement (the HDR plans, the fusion gains, WB / CCM), its C++ block unchanged, in this unit
#include "third_party/tone_supplement/tone_supplement.cpp"

namespace {

// the module's 3.1 RAW fusion mask (its table, read for the viewfinder's LUT: previewLut)
#include "third_party/tone_port/src/raw_mask.inc"

void raise(JNIEnv* env, const std::string& message) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, message.c_str());
}

int16_t saturate16(float v) {
    if (!(v == v)) return 0;
    return static_cast<int16_t>(std::lround(std::max(-32768.f, std::min(32767.f, v))));
}

/** The planar frame times a gain (FP32, rounded, saturated to int16). */
std::vector<int16_t> scaled(const int16_t* src, size_t count, float gain) {
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

std::vector<int16_t> scaled27(const int16_t* src, size_t count, float gain) {
    std::vector<int16_t> out(count);
    for (size_t i = 0; i < count; ++i) out[i] = saturate16(std::min(V1_CEILING, src[i] * gain));
    return out;
}

/** An RGB8 frame repeated at its right and bottom edges to multiples of 32 (the 8-bit HDR's size rule; global phase at 0, 0). */
std::vector<uint8_t> pad32(const std::vector<uint8_t>& rgb, size_t w, size_t h, size_t pw, size_t ph) {
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
 *   9 low-frequency colour on (V1 HDR), 10 its motion threshold (8-bit), 11 chroma only.
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
};

Post post_from(JNIEnv* env, jfloatArray array) {
    Post p;
    if (!array || env->GetArrayLength(array) < 12) return p;
    float v[12];
    env->GetFloatArrayRegion(array, 0, 12, v);
    p.dehaze = v[0] > .5f;
    p.max_dark = std::clamp(static_cast<int>(std::lround(v[1])), 0, 255);
    p.percentile = v[2];
    p.corner = v[3];
    p.saturation = v[4] > .5f;
    p.sat = v[5];
    p.vibrance = v[6];
    p.sat_per_stop = v[7];
    p.vibrance_per_stop = v[8];
    p.low_frequency = v[9] > .5f;
    p.lf_threshold = std::clamp(static_cast<int>(std::lround(v[10])), 0, 255);
    p.lf_chroma = v[11] > .5f;
    return p;
}

/** {@code v} up to a multiple of 8. */
size_t up8(size_t v) { return (v + 7) / 8 * 8; }

/**
 * The dehaze's per-8x8 U / V gain grid: our lens-shading gain map (sw x sh nodes, two values a node: the U and V gains; node i at
 * (i + 0.5) / sw of the frame, as the GPU samples it) bilinearly at each tile's centre; no map: the unit grid.
 */
std::vector<tone_supplement::ChromaTileGain> tile_gains(const float* shading, int sw, int sh, size_t w, size_t h,
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
DehazeLog dehaze_rgb8(uint8_t* rgb, size_t w, size_t h, const Post& p, const float* shading, int sw, int sh) {
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
void saturate_rgb8(uint8_t* rgb, size_t w, size_t h, bool profile31, const Post& p, float finish_gain) {
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
std::vector<uint8_t> low_pass(const std::vector<uint8_t>& g, size_t w, size_t h) {
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
    int scale = -1;
    std::string error;
};

void low_frequency_hook(uint8_t* bright, size_t pw, size_t ph, void* user) {
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
                    small[c * cells + cell] = saturate16(std::min(V1_CEILING, float(sum[c] / 64.0) * lf->gain_long));
                    current[cell * 3 + c] = static_cast<uint8_t>((cur[c] + 32) / 64);
                }
            }
        }
        const auto reference = tone::finish_raw27_rgb({small.data(), small.size(), gw, gh, gw, cells}, lf->ccm, tone::yuv_curve());
        const auto ref = low_pass(reference, gw, gh), now = low_pass(current, gw, gh);
        std::vector<uint8_t> delta(cells * 3);
        lf->scale = tone_supplement::make_low_frequency_delta(delta.data(), std::ptrdiff_t(gw * 3), ref.data(), std::ptrdiff_t(gw * 3),
                now.data(), std::ptrdiff_t(gw * 3), int(gw), int(gh), uint8_t(lf->post->lf_threshold), lf->post->lf_chroma);
        if (lf->scale > 0) tone_supplement::apply_low_frequency_delta(bright, int(pw), int(ph), std::ptrdiff_t(pw * 3), delta.data(),
                                                                      std::ptrdiff_t(gw * 3), lf->scale);
    } catch (const std::exception& e) {
        lf->error = e.what();
    }
}

}  // namespace

extern "C" {

/**
 * Copies {@code rows} rows of RGBA int32 pixels (the GPU's RGBA16I frame read back as GL_RGBA_INTEGER / GL_INT, the read every
 * ES 3 driver supports) into the planar int16 frame at row {@code y0}.
 */
JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_oldstyle_OldstyleNative_deinterleave(JNIEnv* env, jclass, jobject strip,
        jobject planar, jint width, jint height, jint y0, jint rows) {
    auto* s = static_cast<const int32_t*>(env->GetDirectBufferAddress(strip));
    auto* p = static_cast<int16_t*>(env->GetDirectBufferAddress(planar));
    if (!s || !p || width <= 0 || height <= 0 || y0 < 0 || rows < 0 || y0 + rows > height) {
        raise(env, "Oldstyle deinterleave: bad buffers or rows");
        return;
    }
    const size_t plane = size_t(width) * height;
    for (int y = 0; y < rows; ++y) {
        const int32_t* in = s + size_t(y) * width * 4;
        const size_t o = size_t(y0 + y) * width;
        for (int x = 0; x < width; ++x) {
            p[o + x] = static_cast<int16_t>(in[x * 4]);
            p[plane + o + x] = static_cast<int16_t>(in[x * 4 + 1]);
            p[2 * plane + o + x] = static_cast<int16_t>(in[x * 4 + 2]);
        }
    }
}

/**
 * The exposure plan of the owner's supplement: {@code out} = {hdr scene, use HDR, S, L, gS, gL} for the candidates S*, L*, the
 * long share F, the frame's actual TET S0 and the gain limit M. Profile 27: plan27 + fusion_gains; 31: plan31 (the RAW payload:
 * max 8 x forgiveness 1.4; EV 0, no TET bounds) + raw_hdr_gains_31. Without HDR gS = gL = the gains of S and L as they are.
 */
JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_oldstyle_OldstyleNative_plan(JNIEnv* env, jclass, jint profile, jfloat shortCandidate,
        jfloat longCandidate, jfloat fractionLong, jfloat actualShortTet, jfloat maxGain, jfloatArray outArray) {
    if (!outArray || env->GetArrayLength(outArray) < 6 || !(shortCandidate > 0.f) || !(longCandidate > 0.f) || !(actualShortTet > 0.f)) {
        raise(env, "Oldstyle plan: bad arguments");
        return;
    }
    const auto p = profile == 31
        ? tone_supplement::plan31(shortCandidate, longCandidate, fractionLong, true, false, 0.f, 0.f, 3.4e38f)
        : tone_supplement::plan27(shortCandidate, longCandidate, fractionLong);
    float gs, gl;
    if (profile == 31) {
        const auto g = tone::raw_hdr_gains_31(actualShortTet, p.short_plan, p.long_plan, maxGain);
        gs = g.short_gain;
        gl = g.long_gain;
    } else {
        const auto g = tone_supplement::fusion_gains(actualShortTet, p.short_plan, p.long_plan, 1.f, maxGain);
        gs = g.short_gain;
        gl = g.long_gain;
    }
    const float out[6] = {p.hdr_scene ? 1.f : 0.f, p.use_hdr ? 1.f : 0.f, p.short_plan, p.long_plan, gs, gl};
    env->SetFloatArrayRegion(outArray, 0, 6, out);
}

/**
 * The tone of one frame. {@code planar}: int16 R, G, B planes (width x height each), at the frame's actual TET. {@code hdr}: the
 * plan's two gains (gainShort, gainLong); without it {@code gainLong} is the SDR gain. V1's {@code ccm} goes through awb_ccm with
 * unit WB (our colour chain white-balanced the frame). {@code post}: the YUV routes' stages on our route (Post above; null: none),
 * {@code shading}: the dehaze's U / V gain map (shadingW x shadingH x 2, null: the unit grid). {@code info} (null or 6): the
 * low-frequency scale (-1: not run), the dehaze's shadow / white / dark cut, the module's and the stages' milliseconds.
 * Returns 0, or throws on a module error.
 */
JNIEXPORT jint JNICALL
Java_com_particlesdevs_photoncamera_processing_oldstyle_OldstyleNative_render(JNIEnv* env, jclass, jint version,
        jobject planar, jint width, jint height, jfloatArray ccmArray, jfloat gainShort, jfloat gainLong, jboolean hdr,
        jboolean postview, jfloat saturation, jfloatArray postArray, jfloatArray shadingArray, jint shadingW, jint shadingH,
        jfloatArray infoArray, jobject out) {
    auto* src = static_cast<const int16_t*>(env->GetDirectBufferAddress(planar));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(out));
    if (!src || !dst || width <= 0 || height <= 0 || !(gainShort > 0.f) || !(gainLong > 0.f)) {
        raise(env, "Oldstyle render: bad buffers or gains");
        return -1;
    }
    const Post post = post_from(env, postArray);
    std::vector<float> shading;
    if (shadingArray && shadingW >= 2 && shadingH >= 2 && env->GetArrayLength(shadingArray) >= shadingW * shadingH * 2) {
        shading.resize(size_t(shadingW) * shadingH * 2);
        env->GetFloatArrayRegion(shadingArray, 0, jsize(shading.size()), shading.data());
    }
    float info[6] = {-1.f, -1.f, -1.f, -1.f, 0.f, 0.f};
    const size_t w = size_t(width), h = size_t(height), count = w * h * 3;
    try {
        const auto t0 = std::chrono::steady_clock::now();
        std::vector<uint8_t> rgb;
        if (version == 1) {
            std::array<float, 9> ccm{1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
            if (ccmArray && env->GetArrayLength(ccmArray) == 9) env->GetFloatArrayRegion(ccmArray, 0, 9, ccm.data());
            ccm = tone_supplement::awb_ccm(ccm, 512, 512, 512, 512, false);
            const auto& curve = tone::yuv_curve();
            const auto frame = scaled27(src, count, hdr ? gainShort : gainLong);
            rgb = tone::finish_raw27_rgb({frame.data(), frame.size(), w, h, w, w * h}, ccm, curve);
            if (hdr) {
                const size_t pw = (w + 31) / 32 * 32, ph = (h + 31) / 32 * 32;
                const auto padded = pad32(rgb, w, h, pw, ph);
                const auto inverse = tone::invert(curve);
                const auto q8 = static_cast<uint32_t>(std::lround(std::min(64.f, gainLong / gainShort) * 256.f));
                std::array<tone::Curve10, 3> bright{}, dark{};
                for (size_t c = 0; c < 3; ++c) {
                    bright[c] = tone::retone(inverse, curve, q8);
                    dark[c] = tone::retone(inverse, curve, 256);
                }
                LowFrequency lf{src, w, h, gainLong, ccm, &post};
                const auto toned = tone::tone_rgb8_hdr_luts(tone::Version::v27, tone::ColorInput::rgb,
                        {padded.data(), padded.size(), pw, ph, pw * 3, 0}, bright, dark,
                        post.low_frequency ? low_frequency_hook : nullptr, &lf);
                if (!lf.error.empty()) throw std::runtime_error("low-frequency colour: " + lf.error);
                info[0] = float(lf.scale);
                rgb.resize(count);
                for (size_t y = 0; y < h; ++y) std::memcpy(rgb.data() + y * w * 3, toned.data() + y * pw * 3, w * 3);
            }
        } else {
            const tone::RawRgbImage image{src, count, w, h, w, w * h};
            if (hdr) {
                rgb = tone::render_raw31_hdr(image, {gainShort, gainLong}, postview, tone::raw_curve_31(), saturation);
            } else {
                const auto frame = scaled(src, count, gainLong);
                rgb = tone::render_raw31_sdr({frame.data(), frame.size(), w, h, w, w * h}, tone::raw_curve_31(), saturation);
            }
        }
        if (rgb.size() != count) throw std::runtime_error("unexpected output size");
        const auto t1 = std::chrono::steady_clock::now();
        if (post.dehaze) {
            const auto d = dehaze_rgb8(rgb.data(), w, h, post, shading.empty() ? nullptr : shading.data(), shadingW, shadingH);
            info[1] = float(d.shadow);
            info[2] = float(d.white);
            info[3] = float(d.dark_cut);
        }
        if (post.saturation) saturate_rgb8(rgb.data(), w, h, version != 1, post, gainLong);
        const auto t2 = std::chrono::steady_clock::now();
        info[4] = std::chrono::duration<float, std::milli>(t1 - t0).count();
        info[5] = std::chrono::duration<float, std::milli>(t2 - t1).count();
        std::memcpy(dst, rgb.data(), count);
        if (infoArray && env->GetArrayLength(infoArray) >= 6) env->SetFloatArrayRegion(infoArray, 0, 6, info);
        return 0;
    } catch (const std::exception& e) {
        raise(env, std::string("Oldstyle tone-port: ") + e.what());
        return -1;
    }
}

/**
 * P630e: the viewfinder's tone LUT of a version (utils/import_oldstyle_tone_live.glsl): 1024 RGBA8 entries, entry i = linear i / 1023
 * of white. RGB: the module's own tone of that neutral (V1 finish_raw27_rgb with a unit CCM, V2 render_raw31_sdr) on a ramp frame
 * 1024 x 16; A: the long exposure's weight in the version's fusion when its max channel is there (V1: weights27 of its RGB8, V2:
 * the module's RAW mask of its encoded 12-bit code, 2766 .. 3674), 255 = all long.
 */
JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_oldstyle_OldstyleNative_previewLut(JNIEnv* env, jclass, jint version, jbyteArray outArray) {
    if (!outArray || env->GetArrayLength(outArray) < 4096) {
        raise(env, "Oldstyle previewLut: bad output");
        return;
    }
    try {
        const size_t w = 1024, h = 16, n = w * h;
        std::vector<int16_t> ramp(n * 3);
        for (size_t c = 0; c < 3; ++c)
            for (size_t y = 0; y < h; ++y)
                for (size_t x = 0; x < w; ++x) ramp[c * n + y * w + x] = static_cast<int16_t>(std::lround(x * 4095.0 / 1023.0));
        const tone::RawRgbImage image{ramp.data(), ramp.size(), w, h, w, n};
        const auto rgb = version == 1
            ? tone::finish_raw27_rgb(image, {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f}, tone::yuv_curve())
            : tone::render_raw31_sdr(image, tone::raw_curve_31(), 0.f);
        std::vector<jbyte> out(4096);
        for (size_t i = 0; i < w; ++i) {
            const uint8_t* px = rgb.data() + ((h / 2) * w + i) * 3;
            for (int c = 0; c < 3; ++c) out[i * 4 + c] = static_cast<jbyte>(px[c]);
            int weight;
            if (version == 1) {
                weight = std::min(255, tone::detail::bright_weight(tone::Version::v27, px[0], px[1], px[2]) * 2);
            } else {
                const double code = 4095.0 * std::pow(i / 1023.0, 1.0 / 2.2);
                const int c12 = static_cast<int>(std::lround(code));
                weight = c12 < 2766 ? 255 : c12 > 3674 ? 0 : static_cast<int>(std::lround(raw_mask[c12 - 2766] * 255.0 / 4096.0));
            }
            out[i * 4 + 3] = static_cast<jbyte>(weight);
        }
        env->SetByteArrayRegion(outArray, 0, 4096, out.data());
    } catch (const std::exception& e) {
        raise(env, std::string("Oldstyle previewLut: ") + e.what());
    }
}

}  // extern "C"
