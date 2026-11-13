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
// P636 (speed): the frame's work is in oldstyle-tone-core.h (render: the reference path on one thread, the banded one on more,
// the same bytes); setThreads hands the thread count to the module.
#include <jni.h>

#include "oldstyle-tone-core.h"

namespace {

using namespace oldstyle;

// the module's 3.1 RAW fusion mask (its table, read for the viewfinder's LUT: previewLut)
#include "third_party/tone_port/src/raw_mask.inc"

void raise(JNIEnv* env, const std::string& message) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, message.c_str());
}

Post post_from(JNIEnv* env, jfloatArray array) {
    Post p;
    if (!array || env->GetArrayLength(array) < 12) return p;
    float v[15] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f};
    env->GetFloatArrayRegion(array, 0, std::min<jsize>(15, env->GetArrayLength(array)), v);
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
    p.lf_strength = v[12] == v[12] ? std::clamp(v[12], 0.f, 1.f) : 1.f;
    p.curve_mid = v[13] == v[13] ? v[13] : 0.f;
    p.curve_contrast = v[14] == v[14] ? v[14] : 0.f;
    return p;
}

}  // namespace

extern "C" {

/**
 * Copies {@code rows} rows of RGBA int32 pixels (the GPU's RGBA16I frame read back as GL_RGBA_INTEGER / GL_INT, the read every
 * ES 3 driver supports) into the planar int16 frame at row {@code y0}.
 */
JNIEXPORT void JNICALL
Java_com_scamera_app_processing_oldstyle_OldstyleNative_deinterleave(JNIEnv* env, jclass, jobject strip,
        jobject planar, jint width, jint height, jint y0, jint rows) {
    auto* s = static_cast<const int32_t*>(env->GetDirectBufferAddress(strip));
    auto* p = static_cast<int16_t*>(env->GetDirectBufferAddress(planar));
    if (!s || !p || width <= 0 || height <= 0 || y0 < 0 || rows < 0 || y0 + rows > height) {
        raise(env, "Oldstyle deinterleave: bad buffers or rows");
        return;
    }
    const size_t plane = size_t(width) * height;
    tone::detail::parallel_rows(0, rows, std::ptrdiff_t(width) * 4, [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        for (int y = int(first); y < int(last); ++y) {
            const int32_t* in = s + size_t(y) * width * 4;
            const size_t o = size_t(y0 + y) * width;
            for (int x = 0; x < width; ++x) {
                p[o + x] = static_cast<int16_t>(in[x * 4]);
                p[plane + o + x] = static_cast<int16_t>(in[x * 4 + 1]);
                p[2 * plane + o + x] = static_cast<int16_t>(in[x * 4 + 2]);
            }
        }
    }, 32);
}

/** P636: the same from RGBA int16 pixels (the frame read as GL_RGBA_INTEGER / GL_SHORT where the driver names that pair). */
JNIEXPORT void JNICALL
Java_com_scamera_app_processing_oldstyle_OldstyleNative_deinterleave16(JNIEnv* env, jclass, jobject strip,
        jobject planar, jint width, jint height, jint y0, jint rows) {
    auto* s = static_cast<const int16_t*>(env->GetDirectBufferAddress(strip));
    auto* p = static_cast<int16_t*>(env->GetDirectBufferAddress(planar));
    if (!s || !p || width <= 0 || height <= 0 || y0 < 0 || rows < 0 || y0 + rows > height) {
        raise(env, "Oldstyle deinterleave: bad buffers or rows");
        return;
    }
    const size_t plane = size_t(width) * height;
    tone::detail::parallel_rows(0, rows, std::ptrdiff_t(width) * 4, [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        for (int y = int(first); y < int(last); ++y) {
            const int16_t* in = s + size_t(y) * width * 4;
            const size_t o = size_t(y0 + y) * width;
            for (int x = 0; x < width; ++x) {
                p[o + x] = in[x * 4];
                p[plane + o + x] = in[x * 4 + 1];
                p[2 * plane + o + x] = in[x * 4 + 2];
            }
        }
    }, 32);
}

/** P636: the threads of the module's and the stages' row loops (1: the single-thread path of P630e; 0 or less: the cores, at most 8). Returns the count in use. */
JNIEXPORT jint JNICALL
Java_com_scamera_app_processing_oldstyle_OldstyleNative_setThreads(JNIEnv*, jclass, jint count) {
    tone::set_threads(count);
    return tone::threads();
}

/**
 * The exposure plan of the owner's supplement: {@code out} = {hdr scene, use HDR, S, L, gS, gL} for the candidates S*, L*, the
 * long share F, the frame's actual TET S0 and the gain limit M. Profile 27: plan27 + fusion_gains; 31: plan31 (the RAW payload:
 * max 8 x forgiveness 1.4; EV 0, no TET bounds) + raw_hdr_gains_31. Without HDR gS = gL = the gains of S and L as they are.
 */
JNIEXPORT void JNICALL
Java_com_scamera_app_processing_oldstyle_OldstyleNative_plan(JNIEnv* env, jclass, jint profile, jfloat shortCandidate,
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
 * {@code shading}: the dehaze's U / V gain map (shadingW x shadingH x 2, null: the unit grid). {@code info} (null, 6 or 8): the
 * low-frequency scale (-1: not run), the dehaze's shadow / white / dark cut, the module's and the stages' milliseconds; P636:
 * the threads and the input gain's milliseconds.
 * Returns 0, or throws on a module error.
 */
JNIEXPORT jint JNICALL
Java_com_scamera_app_processing_oldstyle_OldstyleNative_render(JNIEnv* env, jclass, jint version,
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
    float info[8];
    std::array<float, 9> ccm{1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
    if (ccmArray && env->GetArrayLength(ccmArray) == 9) env->GetFloatArrayRegion(ccmArray, 0, 9, ccm.data());
    try {
        oldstyle::render(version, src, size_t(width), size_t(height), ccm.data(), gainShort, gainLong, hdr, postview, saturation,
                         post, shading.empty() ? nullptr : shading.data(), shadingW, shadingH, info, dst);
        if (infoArray && env->GetArrayLength(infoArray) >= 6)
            env->SetFloatArrayRegion(infoArray, 0, std::min<jsize>(8, env->GetArrayLength(infoArray)), info);
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
static void preview_lut(JNIEnv* env, jint version, float mid, float contrast, jbyteArray outArray) {
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
            ? tone::finish_raw27_rgb(image, {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f}, shaped_curve(tone::yuv_curve(), mid, contrast))
            : tone::render_raw31_sdr(image, shaped_curve(tone::raw_curve_31(), mid, contrast), 0.f);
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

JNIEXPORT void JNICALL
Java_com_scamera_app_processing_oldstyle_OldstyleNative_previewLut(JNIEnv* env, jclass, jint version, jbyteArray outArray) {
    preview_lut(env, version, 0.f, 0.f, outArray);
}

/** P630h: previewLut with the tone curve shaped as the shot's (Post entries 13, 14). */
JNIEXPORT void JNICALL
Java_com_scamera_app_processing_oldstyle_OldstyleNative_previewLutCurve(JNIEnv* env, jclass, jint version, jfloat mid,
        jfloat contrast, jbyteArray outArray) {
    preview_lut(env, version, mid == mid ? mid : 0.f, contrast == contrast ? contrast : 0.f, outArray);
}

}  // extern "C"
