// EditMath (gallery/editor/EditMath.java) one to one with Java's float semantics: the uniforms every editor pass gets
// (Uniforms::of), the tone curve and point-curve tables, the radii and the tile overlap. Java float arithmetic stays float here
// (built with -ffp-contract=off); Math.pow / exp / cos / atan2 / cbrt are the double functions on promoted values, as in Java.
#pragma once

#include "EditState.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <vector>

namespace scam_edit {

namespace EditMath {

constexpr float LUMA[3] = {0.2126f, 0.7152f, 0.0722f};
constexpr float P3_TO_SRGB[9] = {1.2249401f, -0.2249404f, 0.f, -0.0420569f, 1.0420571f, 0.f, -0.0196376f, -0.0786361f, 1.0982735f};
constexpr float IDENTITY3[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
constexpr int LUT_SIZE = 1024;
constexpr float DISPLAY_STEPS = 511.f;
constexpr float SCENE_LO = -14.f, SCENE_HI = 6.f;
constexpr float CENTRE = 4.f;
constexpr float MID_GREY_LOG = -2.473931f;
constexpr float REF_SIDE = 4000.f;
constexpr double PI = 3.141592653589793; // Math.PI

/// Where the colour of a source comes from (EditMath.SourceColour).
struct SourceColour {
    virtual ~SourceColour() = default;
    /// true: the texture holds linear values (RAW); false: sRGB-encoded
    virtual bool linear() const = 0;
    /// true: a RAW (scene-referred tone, WB through the camera matrices)
    virtual bool scene() const = 0;
    /// wbMul (camera multipliers, 1 for a photo) and toWork (3x3 row-major, source -> linear sRGB)
    virtual void colour(const EditState& s, float* wbMul, float* toWork) const = 0;
    /// base exposure in stops, added to Экспозиция
    virtual float baseStops() const = 0;
};

/// EditMath.display: a photo in sRGB (or Display P3 when p3)
struct DisplayColour : SourceColour {
    bool p3 = false, lin = false;
    DisplayColour(bool p3_, bool linear_) : p3(p3_), lin(linear_) {}
    bool linear() const override { return lin; }
    bool scene() const override { return false; }
    void colour(const EditState&, float* wbMul, float* toWork) const override {
        wbMul[0] = wbMul[1] = wbMul[2] = 1.f;
        for (int i = 0; i < 9; i++) toWork[i] = p3 ? P3_TO_SRGB[i] : IDENTITY3[i];
    }
    float baseStops() const override { return 0.f; }
};

// ---------------------------------------------------------------- transfer

inline float srgbToLin(float e) {
    return e <= 0.04045f ? e / 12.92f : float(std::pow(double((e + 0.055f) / 1.055f), double(2.4f)));
}

inline float linToSrgb(float x) {
    return x <= 0.0031308f ? x * 12.92f : 1.055f * float(std::pow(double(x), double(1.f / 2.4f))) - 0.055f;
}

inline float clamp01(float v) { return jmax(0.f, jmin(1.f, v)); }

inline float smoothstep(float a, float b, float x) {
    const float t = clamp01((x - a) / (b - a));
    return t * t * (3.f - 2.f * t);
}

/// Java's float % (fmod semantics)
inline float jmod(float a, float b) { return std::fmod(a, b); }

// ---------------------------------------------------------------- white balance of a photo

inline std::array<float, 3> wbGains(float t, float g) {
    const float r = float(std::pow(2.0, 0.40 * double(t) + 0.15 * double(g)));
    const float gr = float(std::pow(2.0, -0.30 * double(g)));
    const float b = float(std::pow(2.0, -0.40 * double(t) + 0.15 * double(g)));
    const float y = LUMA[0] * r + LUMA[1] * gr + LUMA[2] * b;
    return {r / y, gr / y, b / y};
}

// ---------------------------------------------------------------- tone curve

inline float sCurve(float p, float gamma) {
    if (p <= 0.f) return p;
    if (p >= 1.f) return p;
    const float pv = 0.46f;
    return p < pv ? pv * float(std::pow(double(p / pv), double(gamma)))
                  : 1.f - (1.f - pv) * float(std::pow(double((1.f - p) / (1.f - pv)), double(gamma)));
}

inline float contrast(float p, float c) {
    if (c == 0.f) return p;
    return c > 0 ? p + (sCurve(p, 1.8f) - p) * c : p + (sCurve(p, 1.f / 1.8f) - p) * -c;
}

inline float blacks(float p, float b) {
    if (b == 0.f || p >= 1.f) return p;
    const float q = 1.f - jmax(0.f, p);
    return jmax(0.f, p + b * 0.12f * q * q * q);
}

inline float whites(float p, float w) {
    if (w == 0.f) return p;
    const float q = jmin(jmax(p, 0.f), 1.f);
    return p + w * 0.18f * q * q * q;
}

inline float shoulder(float p, float k) {
    if (k >= 1.f) return jmin(p, 1.f);
    if (p <= k) return p;
    return k + (1.f - k) * (1.f - float(std::exp(double(-(p - k) / (1.f - k)))));
}

inline float filmic(float yScene, float white) {
    const float y = 1.25f * jmax(yScene, 0.f);
    return y * (1.f + y / (white * white)) / (1.f + y);
}

inline float filmicWhite(float w) { return 4.f * float(std::pow(2.0, -1.5 * double(w))); }

/// {split1, split2, split3, amount0..3}, empty when all four are 0
inline std::vector<float> curveRegions(const EditState& s) {
    const float a0 = s.get(Param::CURVE_SHADOWS), a1 = s.get(Param::CURVE_DARKS), a2 = s.get(Param::CURVE_LIGHTS),
                a3 = s.get(Param::CURVE_HIGHLIGHTS);
    if (a0 == 0.f && a1 == 0.f && a2 == 0.f && a3 == 0.f) return {};
    const float s0 = s.get(Param::CURVE_SPLIT_1) / 100.f;
    const float s1 = jmax(s0, s.get(Param::CURVE_SPLIT_2) / 100.f);
    const float s2 = jmax(s1, s.get(Param::CURVE_SPLIT_3) / 100.f);
    return {s0, s1, s2, a0, a1, a2, a3};
}

/// LightCraft's parametric curve (EditMath.parametric)
inline float parametric(float x, const std::vector<float>& r) {
    const float lo[4] = {0.f, r[0], r[1], r[2]}, hi[4] = {r[0], r[1], r[2], 1.f};
    float d = 0.f;
    for (int k = 0; k < 4; k++) {
        const float amt = r[size_t(3 + k)];
        if (amt == 0.f) continue;
        const float ctr = (lo[k] + hi[k]) / 2.f, half = (hi[k] - lo[k]) * 0.75f + 0.05f;
        const float t = jmax(-1.f, jmin(1.f, (x - ctr) / half));
        d += amt / 100.f * 0.22f * (0.5f + 0.5f * float(std::cos(double(t) * PI)));
    }
    return jmax(0.f, jmin(1.f, x + d * 4.f * x * (1.f - x)));
}

inline float linToSrgbF(float x) { return linToSrgb(x); }

inline std::vector<float> toneLut(const EditState& s, bool scene) {
    std::vector<float> lut(LUT_SIZE);
    const float c = s.get(Param::CONTRAST) / 100.f, b = s.get(Param::BLACKS) / 100.f, w = s.get(Param::WHITES) / 100.f;
    const float ev = s.get(Param::EXPOSURE);
    const float knee = ev > 0 ? 1.f - 0.25f * jmin(1.f, ev / 2.f) : 1.f;
    const float white = filmicWhite(w);
    const float sceneContrast = jmax(-1.f, jmin(1.f, c + 0.15f));
    const std::vector<float> regions = curveRegions(s);
    float prev = 0.f;
    for (int i = 0; i < LUT_SIZE; i++) {
        float p;
        if (scene) {
            const float x = SCENE_LO + (SCENE_HI - SCENE_LO) * float(i) / (float(LUT_SIZE) - 1.f);
            const float d = jmin(1.f, filmic(float(std::pow(2.0, double(x))), white));
            p = linToSrgb(d);
            p = blacks(p, b);
            p = contrast(p, sceneContrast);
            p = jmin(1.f, jmax(0.f, p));
        } else {
            p = float(i) / DISPLAY_STEPS;
            p = blacks(p, b);
            p = whites(p, w);
            p = contrast(p, c);
            p = shoulder(jmax(0.f, p), knee);
        }
        if (!regions.empty() && p <= 1.f) p = parametric(jmax(0.f, p), regions);
        prev = i == 0 ? p : jmax(prev, p);
        lut[size_t(i)] = prev;
    }
    return lut;
}

// ---------------------------------------------------------------- point curves

inline float curveAt(const std::vector<float>& pts, float x) {
    if (pts.size() < 4) return x;
    const int n = int(pts.size() / 2);
    if (x <= pts[0]) return pts[1];
    if (x >= pts[size_t(2 * (n - 1))]) return pts[size_t(2 * n - 1)];
    std::vector<float> d(static_cast<size_t>(n - 1)), m(static_cast<size_t>(n));
    for (int i = 0; i < n - 1; i++) {
        const float dx = jmax(1e-6f, pts[size_t(2 * i + 2)] - pts[size_t(2 * i)]);
        d[size_t(i)] = (pts[size_t(2 * i + 3)] - pts[size_t(2 * i + 1)]) / dx;
    }
    m[0] = d[0];
    m[size_t(n - 1)] = d[size_t(n - 2)];
    for (int i = 1; i < n - 1; i++) m[size_t(i)] = d[size_t(i - 1)] * d[size_t(i)] <= 0 ? 0.f : (d[size_t(i - 1)] + d[size_t(i)]) / 2.f;
    for (int i = 0; i < n - 1; i++) {
        if (d[size_t(i)] == 0.f) {
            m[size_t(i)] = 0.f;
            m[size_t(i + 1)] = 0.f;
            continue;
        }
        const float a = m[size_t(i)] / d[size_t(i)], b = m[size_t(i + 1)] / d[size_t(i)], h = a * a + b * b;
        if (h > 9.f) {
            const float tau = 3.f / float(std::sqrt(double(h)));
            m[size_t(i)] = tau * a * d[size_t(i)];
            m[size_t(i + 1)] = tau * b * d[size_t(i)];
        }
    }
    int k = 0;
    while (k < n - 2 && x > pts[size_t(2 * k + 2)]) k++;
    const float x0 = pts[size_t(2 * k)], x1 = pts[size_t(2 * k + 2)], y0 = pts[size_t(2 * k + 1)], y1 = pts[size_t(2 * k + 3)];
    const float h = jmax(1e-6f, x1 - x0);
    const float t = (x - x0) / h, t2 = t * t, t3 = t2 * t;
    const float v = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * h * m[size_t(k)] + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * h * m[size_t(k + 1)];
    return jmax(0.f, jmin(1.f, v));
}

/// 4 rows x LUT_SIZE (master, R, G, B); empty when every curve is straight
inline std::vector<float> pointLut(const EditState& s) {
    if (!s.anyCurve()) return {};
    std::vector<float> t(size_t(4 * LUT_SIZE));
    for (int k = 0; k < 4; k++)
        for (int i = 0; i < LUT_SIZE; i++) t[size_t(k * LUT_SIZE + i)] = curveAt(s.curves[size_t(k)], float(i) / (float(LUT_SIZE) - 1.f));
    return t;
}

// ---------------------------------------------------------------- OkLab hues

inline std::array<float, 3> hsvToRgb(float h0, float s0, float v0) {
    const float h = jmod(jmod(h0, 360.f) + 360.f, 360.f) / 60.f, c = v0 * s0;
    const float x = c * (1.f - std::fabs(jmod(h, 2.f) - 1.f));
    float r, g, b;
    if (h < 1.f) { r = c; g = x; b = 0; }
    else if (h < 2.f) { r = x; g = c; b = 0; }
    else if (h < 3.f) { r = 0; g = c; b = x; }
    else if (h < 4.f) { r = 0; g = x; b = c; }
    else if (h < 5.f) { r = x; g = 0; b = c; }
    else { r = c; g = 0; b = x; }
    const float m = v0 - c;
    return {r + m, g + m, b + m};
}

inline std::array<float, 3> oklab(float c0, float c1, float c2) {
    float l = 0.4122214708f * c0 + 0.5363325363f * c1 + 0.0514459929f * c2;
    float m = 0.2119034982f * c0 + 0.6806995451f * c1 + 0.1073969566f * c2;
    float s = 0.0883024619f * c0 + 0.2817188376f * c1 + 0.6299787005f * c2;
    l = float(std::cbrt(double(l)));
    m = float(std::cbrt(double(m)));
    s = float(std::cbrt(double(s)));
    return {0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s};
}

inline float oklchHueOfSrgbHue(float deg) {
    const auto e = hsvToRgb(deg, 1.f, 1.f);
    const auto lab = oklab(srgbToLin(e[0]), srgbToLin(e[1]), srgbToLin(e[2]));
    return float(std::atan2(double(lab[2]), double(lab[1])));
}

inline const std::array<float, 8>& bandHues() {
    static const std::array<float, 8> hues = [] {
        const float bands[8] = {0.f, 30.f, 60.f, 120.f, 180.f, 240.f, 270.f, 300.f};
        std::array<float, 8> h{};
        for (int i = 0; i < 8; i++) {
            const float v = oklchHueOfSrgbHue(bands[i]);
            h[size_t(i)] = v < 0 ? v + float(2 * PI) : v;
        }
        return h;
    }();
    return hues;
}

// ---------------------------------------------------------------- radii

inline float refScale(float longSide) { return longSide / REF_SIDE; }
constexpr float TEXTURE_SIGMA_REF = 6.f;
inline float sharpenSigmaRef(const EditState& s) { return s.get(Param::SHARPEN_RADIUS); }
inline float lumaStepRef(const EditState& s) { return 0.6f + 1.4f * s.get(Param::LUMA_NR) / 100.f; }
inline float chromaStepRef(const EditState& s) { return 1.f + 3.f * s.get(Param::COLOR_NR) / 100.f; }

/// {taps, stride}
inline std::array<float, 2> gaussTaps(float sigma) {
    const float reach = 3.f * jmax(sigma, 0.05f);
    if (reach <= 24.f) return {float(std::max(1, jint(std::ceil(double(reach))))), 1.f};
    return {24.f, reach / 24.f};
}

inline int overlapPx(float longSide) {
    const float ref = 2.f * 4.f + 3.f * jmax(TEXTURE_SIGMA_REF, 3.f);
    return jint(std::ceil(double(ref * refScale(longSide)))) + 6;
}

// ---------------------------------------------------------------- uniforms

struct Uniforms {
    bool linearIn = false, raw = false, scene = false;
    float wbMul[3] = {}, toWork[9] = {}, gain[3] = {}, air[3] = {};
    float sharpen = 0, texture = 0, clarity = 0, shadows = 0, highlights = 0, dehaze = 0, sat = 0, vib = 0;
    bool hslOn = false, mixerOn = false, mono = false, gradeOn = false, colourOn = false;
    float hsl[24] = {}, bw[8] = {}, grade[12] = {};
    float gradeBlend = 0, gradeBalance = 0, skinHue = oklchHueOfSrgbHue(25.f);
    std::vector<float> lut, points;
    float vignette = 0, vignetteMid = 0, grain = 0, grainCellRef = 0;
    bool nrLuma = false, nrChroma = false;
    float sigL = 0, sigC = 0, stepLRef = 0, stepCRef = 0;
    float sigmaSharpRef = 0, sigmaTexRef = 0;
    bool blurOn = false;

    static Uniforms of(const EditState& s, const SourceColour& src) {
        Uniforms u;
        u.linearIn = src.linear();
        u.scene = src.scene();
        src.colour(s, u.wbMul, u.toWork);
        u.raw = u.scene;
        const float ev = float(std::pow(2.0, double(s.get(Param::EXPOSURE) + src.baseStops())));
        const std::array<float, 3> wb = u.scene ? std::array<float, 3>{1, 1, 1}
                                                : wbGains(s.get(Param::TEMPERATURE) / 100.f, s.get(Param::TINT) / 100.f);
        for (int i = 0; i < 3; i++) {
            u.gain[i] = wb[size_t(i)] * ev;
            u.air[i] = u.gain[i];
        }
        u.sharpen = s.get(Param::SHARPEN) / 100.f;
        u.texture = s.get(Param::TEXTURE) / 100.f * 0.7f;
        u.clarity = s.get(Param::CLARITY) / 100.f * 0.8f;
        const float sh = s.get(Param::SHADOWS) / 100.f, hi = s.get(Param::HIGHLIGHTS) / 100.f;
        u.shadows = sh * (sh > 0 ? 1.5f : 1.2f);
        u.highlights = hi * 1.5f;
        u.dehaze = s.get(Param::DEHAZE) / 100.f;
        u.sat = s.get(Param::SATURATION) / 100.f + (u.scene ? 0.08f : 0.f);
        u.vib = s.get(Param::VIBRANCE) / 100.f;
        u.hslOn = s.anyHsl();
        u.mixerOn = u.hslOn;
        for (int b = 0; b < 8; b++) {
            u.hsl[b * 3] = s.get(hueParam(b)) / 100.f;
            u.hsl[b * 3 + 1] = s.get(satParam(b)) / 100.f;
            u.hsl[b * 3 + 2] = s.get(lumParam(b)) / 100.f;
            u.bw[b] = s.get(bwParam(b)) / 100.f;
        }
        u.mono = s.mono();
        u.gradeOn = s.anyGrade();
        for (int w = 0; w < 4; w++) {
            const float hue = oklchHueOfSrgbHue(s.get(gradeParam(w, 0)));
            const float sat = s.get(gradeParam(w, 1)) / 100.f * 0.09f;
            u.grade[w * 3] = sat * float(std::cos(double(hue)));
            u.grade[w * 3 + 1] = sat * float(std::sin(double(hue)));
            u.grade[w * 3 + 2] = s.get(gradeParam(w, 2)) / 100.f * 0.12f;
        }
        u.gradeBlend = s.get(Param::GRADE_BLEND) / 100.f;
        u.gradeBalance = s.get(Param::GRADE_BALANCE) / 100.f;
        u.colourOn = u.sat != 0.f || u.vib != 0.f || u.mixerOn || u.mono || u.gradeOn;
        u.lut = toneLut(s, u.scene);
        u.points = pointLut(s);
        u.vignette = s.get(Param::VIGNETTE) / 100.f;
        u.vignetteMid = s.get(Param::VIGNETTE_MID) / 100.f;
        u.grain = s.get(Param::GRAIN) / 100.f * 0.10f;
        u.grainCellRef = 1.f + 3.f * s.get(Param::GRAIN_SIZE) / 100.f;
        const float nl = s.get(Param::LUMA_NR) / 100.f, nc = s.get(Param::COLOR_NR) / 100.f;
        u.nrLuma = nl > 0;
        u.nrChroma = nc > 0;
        u.sigL = 0.006f + 0.05f * nl;
        u.sigC = 0.01f + 0.12f * nc;
        u.stepLRef = lumaStepRef(s);
        u.stepCRef = chromaStepRef(s);
        u.sigmaSharpRef = sharpenSigmaRef(s);
        u.sigmaTexRef = TEXTURE_SIGMA_REF;
        u.blurOn = u.sharpen != 0.f || u.texture != 0.f;
        return u;
    }
};

} // namespace EditMath
} // namespace scam_edit
