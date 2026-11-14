// P646 «Склейка YUV»: the JNI of processing/yuv/YuvMergeNative (library scameraYuv): the camera's YUV_420_888 / P010 planes into
// one packed planar frame (pack), and the burst of packed frames into linear RGB (merge: scam-yuv-merge.h).
#include <jni.h>

#include <string>
#include <vector>

#include "scam-yuv-merge.h"

namespace {

void raise(JNIEnv* env, const std::string& message) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, message.c_str());
}

template <typename S>
bool packPlanes(const uint8_t* y, const uint8_t* u, const uint8_t* v, int yRow, int yPixel, int uRow, int uPixel, int vRow,
                int vPixel, int w, int h, int shift, jlong ySize, jlong uSize, jlong vSize, S* out) {
    const int cw = (w + 1) / 2, ch = (h + 1) / 2;
    auto fits = [](jlong size, int row, int pixel, int cols, int rows) {
        return jlong(rows - 1) * row + jlong(cols - 1) * pixel + jlong(sizeof(S)) <= size;
    };
    if (!fits(ySize, yRow, yPixel, w, h) || !fits(uSize, uRow, uPixel, cw, ch) || !fits(vSize, vRow, vPixel, cw, ch)) return false;
    auto read = [&](const uint8_t* p) {
        S s;
        std::memcpy(&s, p, sizeof(S));
        return S(s >> shift);
    };
    for (int r = 0; r < h; ++r)
        for (int c = 0; c < w; ++c) out[std::size_t(r) * w + c] = read(y + std::ptrdiff_t(r) * yRow + std::ptrdiff_t(c) * yPixel);
    S* ou = out + std::size_t(w) * h;
    S* ov = ou + std::size_t(cw) * ch;
    for (int r = 0; r < ch; ++r)
        for (int c = 0; c < cw; ++c) {
            ou[std::size_t(r) * cw + c] = read(u + std::ptrdiff_t(r) * uRow + std::ptrdiff_t(c) * uPixel);
            ov[std::size_t(r) * cw + c] = read(v + std::ptrdiff_t(r) * vRow + std::ptrdiff_t(c) * vPixel);
        }
    return true;
}

template <typename S>
scam_yuv::Frame420<S> view(void* data, int w, int h, int bits) {
    const int cw = (w + 1) / 2, ch = (h + 1) / 2;
    const S* p = static_cast<const S*>(data);
    scam_yuv::Frame420<S> f;
    f.y = {p, w, 1};
    f.c0 = {p + std::size_t(w) * h, cw, 1};
    f.c1 = {p + std::size_t(w) * h + std::size_t(cw) * ch, cw, 1};
    f.width = w;
    f.height = h;
    f.bits = bits;
    f.order = scam_yuv::ChromaOrder::UV;
    return f;
}

template <typename S>
scam_yuv::Report run(JNIEnv* env, jobjectArray frames, int w, int h, int bits, const std::vector<scam_yuv::FrameInfo>& info,
                     const scam_yuv::Settings& set, const scam_yuv::Transfer& t, const scam_yuv::Decoder& d,
                     const scam_yuv::NoiseFallback& fb, float* out, uint8_t* counts) {
    std::vector<scam_yuv::Frame420<S>> views;
    const std::size_t samples = std::size_t(w) * h + 2 * std::size_t((w + 1) / 2) * ((h + 1) / 2);
    for (jsize i = 0; i < env->GetArrayLength(frames); ++i) {
        jobject b = env->GetObjectArrayElement(frames, i);
        void* data = b ? env->GetDirectBufferAddress(b) : nullptr;
        if (!data || env->GetDirectBufferCapacity(b) < jlong(samples * sizeof(S))) throw std::invalid_argument("frame buffer too small");
        views.push_back(view<S>(data, w, h, bits));
        env->DeleteLocalRef(b);
    }
    return scam_yuv::mergeBurst(views, info, set, t, d, fb, out, counts);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_scamera_app_processing_yuv_YuvMergeNative_pack(
        JNIEnv* env, jclass, jobject y, jobject u, jobject v, jint yRow, jint yPixel, jint uRow, jint uPixel, jint vRow,
        jint vPixel, jint width, jint height, jint sampleBytes, jint shift, jobject out) {
    auto* py = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y));
    auto* pu = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u));
    auto* pv = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v));
    void* po = env->GetDirectBufferAddress(out);
    if (!py || !pu || !pv || !po || width < 2 || height < 2 || (sampleBytes != 1 && sampleBytes != 2)) return JNI_FALSE;
    const jlong need = (jlong(width) * height + 2 * jlong((width + 1) / 2) * ((height + 1) / 2)) * sampleBytes;
    if (env->GetDirectBufferCapacity(out) < need) return JNI_FALSE;
    const jlong ys = env->GetDirectBufferCapacity(y), us = env->GetDirectBufferCapacity(u), vs = env->GetDirectBufferCapacity(v);
    return sampleBytes == 1
                   ? packPlanes<uint8_t>(py, pu, pv, yRow, yPixel, uRow, uPixel, vRow, vPixel, width, height, 0, ys, us, vs,
                                         static_cast<uint8_t*>(po))
                   : packPlanes<uint16_t>(py, pu, pv, yRow, yPixel, uRow, uPixel, vRow, vPixel, width, height, shift, ys, us, vs,
                                          static_cast<uint16_t*>(po));
}

/**
 * settings: profile (0 2.7, 1 3.1), global radius, local radius, threshold scale, chroma check, chroma tolerance, luma check, own
 * noise, sharpest base, guided chroma, bracket, de-ghost, threads, transfer (0 sRGB, 1 points, 2 HLG, 3 linear), decode (0 JFIF
 * BT.601, 1 BT.2020 narrow unless its Y extremes say full, 2 BT.2020 full range, 3 BT.2020 narrow: 2 / 3 the dataspace's range), P701 the points' prior model (0 over linear light, 1 over the camera's sRGB signal), the output's chroma factor (1: unchanged), the shadow toe's gamma and cap (1, 1: none). curve: the
 * points' linear inputs then their encoded outputs. noise: S, O of the camera's profile.
 * stats out: base, mean frames, linear S, linear O, bracket used, ES share, L share, noise measured, measured bins, median frames,
 * P701 the model the bracket measured (0 none, 1 over linear light, 2 over sRGB), the scene's geometric mean before and after the
 * shadow toe, 1 when the counts came back as the post's codes (the toe's noise in them).
 */
JNIEXPORT jstring JNICALL Java_com_scamera_app_processing_yuv_YuvMergeNative_merge(
        JNIEnv* env, jclass, jobjectArray frames, jint width, jint height, jint bits, jfloatArray ratios, jintArray roles,
        jfloatArray settings, jfloatArray curve, jfloatArray noise, jobject outRgb, jobject countsOut, jfloatArray stats) {
    try {
        const jsize n = env->GetArrayLength(frames);
        if (n < 1 || env->GetArrayLength(ratios) != n || env->GetArrayLength(roles) != n) throw std::invalid_argument("frame lists differ");
        std::vector<float> r(n), s(19, 0.f), c, nz(2, 0.f);
        std::vector<jint> ro(n);
        env->GetFloatArrayRegion(ratios, 0, n, r.data());
        env->GetIntArrayRegion(roles, 0, n, ro.data());
        const jsize given = std::min<jsize>(19, env->GetArrayLength(settings));
        env->GetFloatArrayRegion(settings, 0, given, s.data());
        if (given < 17) s[16] = 1.f;  // P701: no chroma factor given: unchanged
        if (given < 19) s[17] = s[18] = 1.f;  // P701: no shadow toe given: none
        if (curve) {
            c.resize(env->GetArrayLength(curve));
            env->GetFloatArrayRegion(curve, 0, jsize(c.size()), c.data());
        }
        if (noise) env->GetFloatArrayRegion(noise, 0, std::min<jsize>(2, env->GetArrayLength(noise)), nz.data());
        std::vector<scam_yuv::FrameInfo> info(n);
        for (jsize i = 0; i < n; ++i) info[i] = {scam_yuv::Role(std::clamp<jint>(ro[i], 0, 2)), r[i]};
        scam_yuv::Settings set;
        set.profile = s[0] > .5f ? scam_yuv::Profile::P31 : scam_yuv::Profile::P27;
        set.globalRadius = std::clamp(int(s[1]), 4, 256);
        set.localRadius = std::clamp(int(s[2]), 1, 16);
        set.thresholdScale = std::clamp(s[3], 0.1f, 8.f);
        set.chromaCheck = s[4] > .5f;
        set.chromaTolerance = std::clamp(s[5], 0.1f, 8.f);
        set.lumaCheck = s[6] > .5f;
        set.ownNoise = s[7] > .5f;
        set.sharpestBase = s[8] > .5f;
        set.guidedChroma = s[9] > .5f;
        set.bracket = s[10] > .5f;
        set.deghost = std::clamp(s[11], 0.5f, 10.f);
        set.threads = std::clamp(int(s[12]), 1, 8);
        scam_yuv::Transfer t;
        t.kind = scam_yuv::Transfer::Kind(std::clamp(int(s[13]), 0, 3));
        if (t.kind == scam_yuv::Transfer::POINTS) {
            if (c.size() < 4 || c.size() % 2) throw std::invalid_argument("capture curve without points");
            t.in.assign(c.begin(), c.begin() + c.size() / 2);
            t.out.assign(c.begin() + c.size() / 2, c.end());
            t.innerSrgb = s[15] > .5f;  // P701
        }
        t.build();
        scam_yuv::Decoder d;
        d.bits = bits;
        // P667: 1 narrow unless the Y extremes say full (no range declared), 2 full, 3 narrow (the Image's dataspace declared it)
        const int decode = std::clamp(int(std::lround(s[14])), 0, 3);
        d.bt2020 = decode > 0;
        d.narrow = decode == 1 || decode == 3;
        d.rangeKnown = decode >= 2;
        const scam_yuv::NoiseFallback fb{nz[0], nz[1]};
        float* out = static_cast<float*>(env->GetDirectBufferAddress(outRgb));
        if (!out || env->GetDirectBufferCapacity(outRgb) < jlong(width) * height * 12) throw std::invalid_argument("RGB buffer too small");
        uint8_t* counts = countsOut ? static_cast<uint8_t*>(env->GetDirectBufferAddress(countsOut)) : nullptr;
        if (counts && env->GetDirectBufferCapacity(countsOut) < jlong(width) * height) counts = nullptr;
        const scam_yuv::Report rep = bits <= 8 ? run<uint8_t>(env, frames, width, height, 8, info, set, t, d, fb, out, counts)
                                               : run<uint16_t>(env, frames, width, height, bits, info, set, t, d, fb, out, counts);
        scam_yuv::scaleChroma(out, std::size_t(width) * height, std::clamp(s[16], 0.f, 2.f), set.threads);  // P701
        float geo[2] = {1.f, 1.f};
        // P701: with the toe, the frame-count map becomes the post's codes here (each pixel's lifted noise in them)
        const bool codes = scam_yuv::shadowToe(out, std::size_t(width) * height, std::clamp(s[17], 0.f, 1.f), std::clamp(s[18], 1.f, 4.f),
                                               set.threads, geo, counts, rep.medianCount, rep.linS, rep.linO);
        if (stats && env->GetArrayLength(stats) >= 10) {
            const float v[10] = {float(rep.base), float(rep.meanCount), rep.linS, rep.linO, rep.bracketUsed ? 1.f : 0.f,
                                 float(rep.esShare), float(rep.longShare), rep.noise.measured ? 1.f : 0.f,
                                 float(rep.noise.measuredBins), float(rep.medianCount)};
            env->SetFloatArrayRegion(stats, 0, 10, v);
            if (env->GetArrayLength(stats) >= 11) {
                const float m = float(rep.curveMeasured);
                env->SetFloatArrayRegion(stats, 10, 1, &m);
            }
            if (env->GetArrayLength(stats) >= 13) env->SetFloatArrayRegion(stats, 11, 2, geo);
            if (env->GetArrayLength(stats) >= 14) {
                const float c = codes ? 1.f : 0.f;
                env->SetFloatArrayRegion(stats, 13, 1, &c);
            }
        }
        return env->NewStringUTF(rep.text().c_str());
    } catch (const std::exception& e) {
        raise(env, std::string("YUV merge: ") + e.what());
        return nullptr;
    }
}

}  // extern "C"
