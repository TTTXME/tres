// Parameters.ReCalcColor for a shot whose colour comes from its DNG tags alone (an iPhone's: no capture-result transform, no
// OPPO tuning, no plain-session table, no DCP profile): the neutral, the calibration fallbacks (P370 A5, P580), the DNG SDK white
// estimate (DngColor), camera -> XYZ (D50) -> ProPhoto (Converter), the placeholder / missing forward matrix routes (P370 A3) and
// «Профиль матрицы» / «Своя матрица 3x3» (P408). Java float / double arithmetic kept step by step (built with -ffp-contract=off).
#pragma once

#include "../Parameters.hpp"
#include "../Settings.hpp"

#include <array>
#include <cmath>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

namespace scam_post {

struct CcmPreset { int id; const char* name; float m[9]; };
#include "CcmPresets.inc"

/// The colour tags of the shot's DNG (row-major as the DNG stores them; a missing tag empty) and its as-shot neutral.
struct ShotColourTags {
    std::vector<float> colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2, calibration1, calibration2;
    int illuminant1 = -1, illuminant2 = -1;   // -1: the tag is absent
    std::vector<float> neutral;               // AsShotNeutral
};

namespace ShotColour {

using D9 = std::array<double, 9>;
using D3 = std::array<double, 3>;
using F9 = std::array<float, 9>;

namespace Conv { // Converter
inline const F9 sXYZtoSRGB{3.1338561f, -1.6168667f, -0.4906146f, -0.9787684f, 1.9161415f, 0.0334540f, 0.0719453f, -0.2289914f, 1.4052427f};
inline const F9 sProPhotoToXYZ{0.797779f, 0.135213f, 0.031303f, 0.288000f, 0.711900f, 0.000100f, 0.000000f, 0.000000f, 0.825105f};
inline const F9 sXYZtoProPhoto{1.345753f, -0.255603f, -0.051025f, -0.544426f, 1.508096f, 0.020472f, 0.000000f, 0.000000f, 1.211968f};
inline const float D50_XYZ[3] = {0.9642f, 1, 0.8249f};

inline void map(const F9& m, const float* in, float* out) {
    out[0] = in[0] * m[0] + in[1] * m[1] + in[2] * m[2];
    out[1] = in[0] * m[3] + in[1] * m[4] + in[2] * m[5];
    out[2] = in[0] * m[6] + in[1] * m[7] + in[2] * m[8];
}
inline F9 multiply(const F9& a, const F9& b) {
    F9 o;
    o[0] = a[0] * b[0] + a[1] * b[3] + a[2] * b[6];
    o[3] = a[3] * b[0] + a[4] * b[3] + a[5] * b[6];
    o[6] = a[6] * b[0] + a[7] * b[3] + a[8] * b[6];
    o[1] = a[0] * b[1] + a[1] * b[4] + a[2] * b[7];
    o[4] = a[3] * b[1] + a[4] * b[4] + a[5] * b[7];
    o[7] = a[6] * b[1] + a[7] * b[4] + a[8] * b[7];
    o[2] = a[0] * b[2] + a[1] * b[5] + a[2] * b[8];
    o[5] = a[3] * b[2] + a[4] * b[5] + a[5] * b[8];
    o[8] = a[6] * b[2] + a[7] * b[5] + a[8] * b[8];
    return o;
}
inline bool invert(const F9& m, F9& out) {
    const double a00 = m[0], a01 = m[1], a02 = m[2], a10 = m[3], a11 = m[4], a12 = m[5], a20 = m[6], a21 = m[7], a22 = m[8];
    const double t00 = a11 * a22 - a21 * a12, t01 = a21 * a02 - a01 * a22, t02 = a01 * a12 - a11 * a02;
    const double t10 = a20 * a12 - a10 * a22, t11 = a00 * a22 - a20 * a02, t12 = a10 * a02 - a00 * a12;
    const double t20 = a10 * a21 - a20 * a11, t21 = a20 * a01 - a00 * a21, t22 = a00 * a11 - a10 * a01;
    const double det = a00 * t00 + a01 * t10 + a02 * t20;
    if (std::fabs(det) < 1e-9) return false;
    out = {float(t00 / det), float(t01 / det), float(t02 / det), float(t10 / det), float(t11 / det), float(t12 / det),
           float(t20 / det), float(t21 / det), float(t22 / det)};
    return true;
}
inline F9 lerp(const F9& a, const F9& b, double f) {
    F9 r;
    for (int i = 0; i < 9; i++) r[i] = float((double(a[i]) * (1.0 - f)) + (double(b[i]) * f));
    return r;
}
inline void normalizeFM(F9& fm) {
    const float one[3] = {1, 1, 1};
    float xyz[3];
    map(fm, one, xyz);
    const F9 m{1.0f / xyz[0], 0, 0, 0, 1.0f / xyz[1], 0, 0, 0, 1.0f / xyz[2]};
    const F9 intermediate = multiply(m, fm);
    const F9 m2{D50_XYZ[0], 0, 0, 0, D50_XYZ[1], 0, 0, 0, D50_XYZ[2]};
    fm = multiply(m2, intermediate);
}
inline F9 cameraToXYZD50(const F9& fwd1, const F9& fwd2, const F9& cal1, const F9& cal2, const float* neutral, double factor) {
    const float cameraNeutral[3] = {neutral[0], neutral[1], neutral[2]};
    const F9 cc = lerp(cal1, cal2, factor);
    F9 inv;
    if (!invert(cc, inv)) throw std::invalid_argument("Cannot invert interpolated calibration transform, input matrices are invalid.");
    float ref[3];
    map(inv, cameraNeutral, ref);
    const float maxNeutral = std::max(std::max(ref[0], ref[1]), ref[2]);
    const F9 D{maxNeutral / ref[0], 0, 0, 0, maxNeutral / ref[1], 0, 0, 0, maxNeutral / ref[2]};
    const F9 intermediate = lerp(fwd1, fwd2, factor);
    const F9 intermediate2 = multiply(D, inv);
    return multiply(intermediate, intermediate2);
}
} // namespace Conv

namespace Dng { // gallery/dng/DngColor (+ DngPreview.mul / inverse)
inline const D3 D50{0.3457, 0.3585};
inline D9 mul(const D9& a, const D9& b) {
    D9 r;
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
    return r;
}
inline double det(const D9& m) { return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]); }
inline D9 inverse(const D9& m) {
    const double d = det(m);
    return {(m[4] * m[8] - m[5] * m[7]) / d, (m[2] * m[7] - m[1] * m[8]) / d, (m[1] * m[5] - m[2] * m[4]) / d,
            (m[5] * m[6] - m[3] * m[8]) / d, (m[0] * m[8] - m[2] * m[6]) / d, (m[2] * m[3] - m[0] * m[5]) / d,
            (m[3] * m[7] - m[4] * m[6]) / d, (m[1] * m[6] - m[0] * m[7]) / d, (m[0] * m[4] - m[1] * m[3]) / d};
}
inline D3 applyM(const D9& m, const D3& v) {
    return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}
inline D3 xyToXyz(const D3& xy) { return {xy[0] / xy[1], 1, (1 - xy[0] - xy[1]) / xy[1]}; }
inline double illuminantTemperature(int code, double fallback) {
    switch (code) {
    case 1: case 4: case 9: case 18: case 20: return 5500;
    case 2: case 14: return 4150;
    case 3: case 17: return 2850;
    case 10: case 19: case 21: return 6500;
    case 11: case 22: return 7500;
    case 12: return 6400;
    case 13: return 5050;
    case 15: return 3525;
    case 16: return 2925;
    case 23: return 5000;
    case 24: return 3200;
    default: return fallback;
    }
}
inline D3 planckUv(double t) {
    const double u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t);
    const double v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t);
    return {u, v, 0};
}
inline D3 xyToUv(const D3& xy) {
    const double d = -2 * xy[0] + 12 * xy[1] + 3;
    return {4 * xy[0] / d, 6 * xy[1] / d, 0};
}
inline D3 uvToXy(const D3& uv) {
    const double d = 2 * uv[0] - 8 * uv[1] + 4;
    return {3 * uv[0] / d, 2 * uv[1] / d, 0};
}
inline double dist2(const D3& uv, double t) {
    const D3 p = planckUv(t);
    return (uv[0] - p[0]) * (uv[0] - p[0]) + (uv[1] - p[1]) * (uv[1] - p[1]);
}
/// {CCT, Duv}
inline std::array<double, 2> cctDuv(const D3& xy) {
    const D3 uv = xyToUv(xy);
    double lo = 1e6 / 25000, hi = 1e6 / 1430;
    const double g = (std::sqrt(5.0) - 1) / 2;
    double a = hi - g * (hi - lo), b = lo + g * (hi - lo);
    double fa = dist2(uv, 1e6 / a), fb = dist2(uv, 1e6 / b);
    for (int i = 0; i < 80; i++) {
        if (fa < fb) {
            hi = b; b = a; fb = fa; a = hi - g * (hi - lo); fa = dist2(uv, 1e6 / a);
        } else {
            lo = a; a = b; fa = fb; b = lo + g * (hi - lo); fb = dist2(uv, 1e6 / b);
        }
    }
    const double t = 1e6 / ((lo + hi) / 2);
    const D3 p = planckUv(t);
    const double d = std::sqrt(dist2(uv, t));
    return {t, uv[1] >= p[1] ? d : -d};
}
inline D3 xyFromCctDuv(double cct, double duv) {
    const D3 p = planckUv(cct), q = planckUv(cct * 1.001);
    const double tu = q[0] - p[0], tv = q[1] - p[1], len = std::hypot(tu, tv);
    double nu = -tv / len, nv = tu / len;
    if (nv < 0) { nu = -nu; nv = -nv; }
    return uvToXy({p[0] + duv * nu, p[1] + duv * nv, 0});
}
inline D9 identity() { return {1, 0, 0, 0, 1, 0, 0, 0, 1}; }
inline std::optional<D9> sdkNormalised(const std::optional<D9>& cm) {
    if (!cm) return cm;
    const D3 v = applyM(*cm, xyToXyz(D50));
    const double max = std::max(v[0], std::max(v[1], v[2]));
    if (!(max > 0) || (max >= 0.99 && max <= 1.01)) return cm;
    D9 out;
    for (int i = 0; i < 9; i++) out[i] = (*cm)[i] / max;
    return out;
}

/// DngColor.forCamera: the colour matrices (SDK-normalised) and calibrations, no forward matrices, no analog balance
struct Color {
    std::optional<D9> cm1, cm2, cc1, cc2;
    double t1 = 2850, t2 = 6500;

    double weight1(double cct) const {
        if (!cm2 || t1 == t2) return 1;
        const double lo = std::min(t1, t2), hi = std::max(t1, t2);
        const double t = std::max(lo, std::min(hi, cct));
        const double g = (1 / t - 1 / hi) / (1 / lo - 1 / hi);
        return t1 <= t2 ? g : 1 - g;
    }
    double weight2(double cct) const { return 1 - weight1(cct); }
    static std::optional<D9> mix(const std::optional<D9>& a, const std::optional<D9>& b, double wa) {
        if (!b) return a;
        if (!a) return b;
        D9 r;
        for (int i = 0; i < 9; i++) r[i] = (*a)[i] * wa + (*b)[i] * (1 - wa);
        return r;
    }
    D9 abcc(double cct) const {
        const auto cc = mix(cc1, cc2, weight1(cct));
        return cc ? *cc : identity();
    }
    D9 xyzToCamera(double cct) const { return mul(abcc(cct), *mix(cm1, cm2, weight1(cct))); }
    bool usable() const {
        if (!cm1) return false;
        for (const auto* m : {&cm1, &cm2, &cc1, &cc2})
            if (*m) for (double v : **m) if (!std::isfinite(v)) return false;
        return std::fabs(det(xyzToCamera(t1))) > 1e-9 && std::fabs(det(xyzToCamera(t2))) > 1e-9;
    }
    std::optional<D3> estimateXy(const D3& neutral) const {
        D3 xy = D50;
        for (int i = 0; i < 40; i++) {
            const D9 m = xyzToCamera(cctDuv(xy)[0]);
            if (std::fabs(det(m)) < 1e-12) return std::nullopt;
            const D3 xyz = applyM(inverse(m), neutral);
            const double s = xyz[0] + xyz[1] + xyz[2];
            if (s <= 1e-12) return std::nullopt;
            const D3 next{xyz[0] / s, xyz[1] / s, 0};
            if (std::fabs(next[0] - xy[0]) + std::fabs(next[1] - xy[1]) < 1e-9) return next;
            xy = next;
        }
        return xy;
    }
    D3 xyForNeutral(const D3& n) const {
        const auto xy = estimateXy(n);
        return xy ? *xy : D50;
    }
    static D9 adaptation(const D3& src, const D3& dst) {
        static const D9 BRADFORD{0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
        const D3 s = applyM(BRADFORD, src), d = applyM(BRADFORD, dst);
        const D9 scale{d[0] / s[0], 0, 0, 0, d[1] / s[1], 0, 0, 0, d[2] / s[2]};
        return mul(inverse(BRADFORD), mul(scale, BRADFORD));
    }
    /// cameraToXyzD50 without forward matrices (forCamera has none)
    D9 cameraToXyzD50(const D3& n) const {
        const D3 xy = xyForNeutral(n);
        const double cct = cctDuv(xy)[0];
        const D9 camToXyz = inverse(xyzToCamera(cct));
        return mul(adaptation(xyToXyz(xy), xyToXyz(D50)), camToXyz);
    }
};
} // namespace Dng

inline std::optional<D9> toDouble(const std::vector<float>& f) {
    if (f.size() < 9) return std::nullopt;
    D9 d;
    for (int i = 0; i < 9; i++) d[i] = f[i];
    return d;
}
inline F9 toF9(const std::vector<float>& v) {
    F9 f{};
    for (size_t i = 0; i < 9 && i < v.size(); i++) f[i] = v[i];
    return f;
}
inline std::vector<float> vecOf(const F9& f) { return std::vector<float>(f.begin(), f.end()); }

/// PhotoMatrix.usableMatrix: finite, not all zero
inline bool usableMatrix(const std::vector<float>& m) {
    if (m.size() < 9) return false;
    bool any = false;
    for (size_t i = 0; i < 9; i++) {
        if (!std::isfinite(m[i])) return false;
        any |= m[i] != 0.f;
    }
    return any;
}
inline bool usableForward(const std::vector<float>& fm) {
    if (!usableMatrix(fm)) return false;
    for (int i = 0; i < 3; i++) {
        float sum = 0.0f;
        for (int j = 0; j < 3; j++) sum += std::fabs(fm[size_t(i * 3 + j)]);
        if (sum == 0.0f) return false;
    }
    return true;
}
/// OppoTunedColor.placeholderForward: the sRGB -> XYZ (D50) matrix
inline bool placeholderForward(const F9& fm) {
    static const F9 SRGB_TO_XYZ_D50{0.4360747f, 0.3850649f, 0.1430804f, 0.2225045f, 0.7168786f, 0.0606169f, 0.0139322f, 0.0971045f, 0.7141733f};
    for (int i = 0; i < 9; i++) if (!(std::fabs(fm[i] - SRGB_TO_XYZ_D50[i]) < 0.01f)) return false;
    return true;
}
inline D3 illuminantXyz(int code) {
    switch (code) {
    case 21: return {0.95047, 1, 1.08883};
    case 17: case 3: return {1.09850, 1, 0.35585};
    case 23: return {0.96422, 1, 0.82521};
    case 20: return {0.95682, 1, 0.92149};
    case 22: return {0.94972, 1, 1.22638};
    default: {
        const D3 xy = Dng::xyFromCctDuv(Dng::illuminantTemperature(code, 6504), 0);
        return {xy[0] / xy[1], 1, (1 - xy[0] - xy[1]) / xy[1]};
    }
    }
}
inline bool placeholderColorMatrix(const F9& cm, int illuminant) {
    static const D9 XYZ_TO_SRGB_D65{3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560, 0.0556434, -0.2040259, 1.0572252};
    bool zero = true;
    for (float v : cm) zero &= v == 0.f;
    if (zero) return true;
    const D3 xyz = illuminantXyz(illuminant);
    double v[3];
    for (int r = 0; r < 3; r++) v[r] = cm[size_t(r * 3)] * xyz[0] + cm[size_t(r * 3 + 1)] * xyz[1] + cm[size_t(r * 3 + 2)] * xyz[2];
    const double lo = std::min(v[0], std::min(v[1], v[2])), hi = std::max(v[0], std::max(v[1], v[2]));
    if (lo > 0 && hi / lo <= 1.02) return true;
    bool d65 = true, d50 = true;
    for (int i = 0; i < 9; i++) {
        d65 &= std::fabs(cm[size_t(i)] - XYZ_TO_SRGB_D65[size_t(i)]) < 0.01;
        d50 &= std::fabs(cm[size_t(i)] - Conv::sXYZtoSRGB[size_t(i)]) < 0.01;
    }
    return d65 || d50;
}
inline double daylightWeight2(int ref1, int ref2) {
    const double t1 = Dng::illuminantTemperature(ref1, 2850), t2 = Dng::illuminantTemperature(ref2, 6500);
    if (t1 == t2) return 0;
    const double lo = std::min(t1, t2), hi = std::max(t1, t2), t = std::max(lo, std::min(hi, 6504.0));
    const double low = (1 / t - 1 / hi) / (1 / lo - 1 / hi);
    return t2 <= t1 ? low : 1 - low;
}
/// PhotoMatrix.normaliseRows
inline bool normaliseRows(std::vector<float>& m) {
    bool ok = true;
    for (size_t row = 0; row < 9; row += 3) {
        const float avr = m[row] + m[row + 1] + m[row + 2];
        if (!(std::fabs(avr) >= 0.25f) || !std::isfinite(avr)) { ok = false; continue; }
        m[row] /= avr;
        m[row + 1] /= avr;
        m[row + 2] /= avr;
    }
    return ok;
}
/// CcmPresets.parse
inline std::optional<std::vector<float>> parsePreset(const std::string& text) {
    std::string t = text;
    for (char& c : t) if (c == ',' || c == ';') c = ' ';
    std::istringstream in(t);
    std::vector<std::string> parts;
    for (std::string s; in >> s;) parts.push_back(s);
    if (parts.size() != 9) return std::nullopt;
    std::vector<float> m(9);
    for (size_t i = 0; i < 9; i++) {
        char* end = nullptr;
        m[i] = std::strtof(parts[i].c_str(), &end);
        if (!end || *end || !std::isfinite(m[i])) return std::nullopt;
    }
    return m;
}
inline std::optional<std::vector<float>> presetMatrix(int id) {
    for (const auto& p : kCcmPresets) if (p.id == id) return std::vector<float>(p.m, p.m + 9);
    return std::nullopt;
}

inline std::string arrayString(const std::vector<float>& v) {
    std::string s = "[";
    for (size_t i = 0; i < v.size(); i++) s += (i ? ", " : "") + javaFloatString(v[i]);
    return s + "]";
}

constexpr float DUV_GUARD = 0.025f;

/// Parameters.ReCalcColor (customNeutral off, no capture result): fills whitePoint, the calibration fields, sensorToProPhoto,
/// proPhotoToSRGB / CCT, sceneCct / sceneCctSource / sceneDuv, colourSource / colourSourceWhy.
inline void reCalcColor(Parameters& p, const ShotColourTags& t, const std::function<void(const std::string&)>& log) {
    std::vector<float> wp(3, 0.f);
    for (size_t i = 0; i < 3 && i < t.neutral.size(); i++) wp[i] = t.neutral[i];
    auto usableNeutral = [](const std::vector<float>& w) {
        if (w.size() < 3) return false;
        for (int i = 0; i < 3; i++) if (!(w[size_t(i)] > 0.f) || std::isinf(w[size_t(i)])) return false;
        return true;
    };
    if (!usableNeutral(wp)) {
        log("the result's neutral " + arrayString(wp) + " is no white point: [1.0, 1.0, 1.0] used");
        wp = {1.f, 1.f, 1.f};
    }
    if (wp[1] > 0.f && wp[1] != 1.f && std::isfinite(wp[1])) {
        log("the neutral " + arrayString(wp) + " is normalised to green 1");
        wp = {wp[0] / wp[1], 1.f, wp[2] / wp[1]};
    }
    p.whitePoint = wp;
    int ref1 = t.illuminant1 >= 0 ? t.illuminant1 : 21;
    int ref2 = t.illuminant2 >= 0 ? int(int8_t(t.illuminant2)) : ref1;
    p.calibrationIlluminant1 = ref1;
    p.calibrationIlluminant2 = ref2;
    std::vector<float> calibration1 = t.calibration1, calibration2 = t.calibration2, colorMat1 = t.colorMatrix1, colorMat2 = t.colorMatrix2;
    std::vector<float> forwardt1 = t.forwardMatrix1, forwardt2 = t.forwardMatrix2;
    std::string missing = t.illuminant1 < 0 ? " illuminant 1 (D65 taken)" : "";
    if (!usableMatrix(calibration1)) calibration1.clear();
    if (!usableMatrix(calibration2)) calibration2.clear();
    if (!usableMatrix(colorMat1)) colorMat1.clear();
    const bool cm2Reported = !colorMat2.empty();
    if (!usableMatrix(colorMat2)) colorMat2.clear();
    const bool singleCalibration = !colorMat1.empty() && (ref1 == 0 || ref2 == 0 || (cm2Reported && colorMat2.empty()));
    if (singleCalibration) {
        missing += " calibration 2 (illuminants " + std::to_string(ref1) + " / " + std::to_string(ref2)
                   + (colorMat2.empty() ? ", ColorMatrix2 unusable" : "") + ": the first calibration alone)";
        ref2 = ref1;
        p.calibrationIlluminant2 = ref2;
        calibration2.clear();
        colorMat2.clear();
    }
    const std::vector<float> identity{1, 0, 0, 0, 1, 0, 0, 0, 1};
    if (calibration1.empty()) { calibration1 = !calibration2.empty() ? calibration2 : identity; missing += " CC1"; }
    if (calibration2.empty()) { calibration2 = calibration1; missing += " CC2"; }
    if (colorMat1.empty() && !colorMat2.empty()) { colorMat1 = colorMat2; missing += " CM1"; }
    if (colorMat2.empty() && !colorMat1.empty()) { colorMat2 = colorMat1; missing += " CM2"; }
    if (colorMat1.empty()) missing += " CM1 CM2";
    const bool forward1 = usableForward(forwardt1), forward2 = usableForward(forwardt2);
    if (forward1 && !forward2) { forwardt2 = forwardt1; missing += " FM2"; }
    else if (forward2 && !forward1) { forwardt1 = forwardt2; missing += " FM1"; }
    if (singleCalibration && forward1) forwardt2 = forwardt1;
    const bool noForward = !forward1 && !forward2;
    if (noForward) { forwardt1 = forwardt2 = identity; missing += " FM1 FM2"; }
    if (!missing.empty()) log("camera colour tags missing or unusable:" + missing + " (the other calibration stands in)");

    const F9 cal1 = toF9(calibration1), cal2 = toF9(calibration2), fwd1 = toF9(forwardt1), fwd2 = toF9(forwardt2);
    p.calibrationTransform1 = vecOf(cal1);
    p.calibrationTransform2 = vecOf(cal2);
    p.ForwardTransform1 = vecOf(fwd1);
    p.ForwardTransform2 = vecOf(fwd2);
    const F9 cm1 = colorMat1.empty() ? F9{} : toF9(colorMat1), cm2 = colorMat1.empty() ? F9{} : toF9(colorMat2);
    p.ColorMatrix1 = vecOf(cm1);
    p.ColorMatrix2 = vecOf(cm2);

    F9 nfwd1 = fwd1, nfwd2 = fwd2;
    Conv::normalizeFM(nfwd1);
    Conv::normalizeFM(nfwd2);
    double interpolationFactor = 0;
    p.sceneDuv = NAN;
    std::string colourFailure;
    std::optional<Dng::Color> dngWhite;
    auto makeWhite = [&] {
        Dng::Color c;
        c.cm1 = Dng::sdkNormalised(toDouble(p.ColorMatrix1.value()));
        c.cm2 = Dng::sdkNormalised(toDouble(p.ColorMatrix2.value()));
        c.cc1 = toDouble(*p.calibrationTransform1);
        c.cc2 = toDouble(*p.calibrationTransform2);
        c.t1 = Dng::illuminantTemperature(ref1, 2850);
        c.t2 = Dng::illuminantTemperature(ref2, 6500);
        return c;
    };
    const D3 n{wp[0], wp[1], wp[2]};
    const bool placeholderCms = !colorMat1.empty() && placeholderColorMatrix(cm1, ref1) && placeholderColorMatrix(cm2, ref2);
    const bool whiteSpec = Settings::current().scamDevSwitch("dng_white_spec", true);
    char buf[256];
    try {
        if (colorMat1.empty()) throw std::invalid_argument("no colour matrix");
        if (!whiteSpec || placeholderCms)
            throw std::invalid_argument("post port: the former DNG estimate (dng_white_spec 0 / placeholder matrices) is not ported");
        dngWhite = makeWhite();
        if (!dngWhite->usable()) throw std::invalid_argument("singular or non-finite colour matrices");
        const auto xy = dngWhite->estimateXy(n);
        if (!xy) throw std::invalid_argument("no chromaticity for the neutral " + arrayString(wp));
        const auto cd = Dng::cctDuv(*xy);
        if (!(cd[0] > 0) || !std::isfinite(cd[0]) || !std::isfinite(cd[1]))
            throw std::invalid_argument("no colour temperature for the neutral " + arrayString(wp));
        p.sceneDuv = float(cd[1]);
        interpolationFactor = dngWhite->weight2(cd[0]);
        if (std::fabs(cd[1]) > DUV_GUARD) {
            p.sceneCct = 0.f;
            std::snprintf(buf, sizeof buf, "unknown: the DNG estimate %.0f K is Duv %.4f off the locus (> %.3f)", cd[0], cd[1], double(DUV_GUARD));
            p.sceneCctSource = buf;
        } else {
            p.sceneCct = float(cd[0]);
            p.sceneCctSource = "the DNG estimate";
        }
        std::snprintf(buf, sizeof buf, "DNG white: %.0f K Duv %.4f, calibration 2 weight %.4f (illuminants %d / %d)", cd[0], cd[1],
                      interpolationFactor, ref1, ref2);
        log(buf);
    } catch (const std::invalid_argument& e) {
        interpolationFactor = daylightWeight2(ref1, ref2);
        p.sceneCct = 0.f;
        p.sceneDuv = NAN;
        colourFailure = std::string("the white estimate failed (") + e.what() + ")";
        p.sceneCctSource = "unknown: " + colourFailure;
        log(colourFailure);
    }
    bool chainFailed = false;
    F9 sensorToProPhoto = toF9(*p.sensorToProPhoto);
    try {
        const F9 sensorToXYZ = Conv::cameraToXYZD50(nfwd1, nfwd2, cal1, cal2, wp.data(), interpolationFactor);
        for (float v : sensorToXYZ) if (!std::isfinite(v)) throw std::invalid_argument("non-finite camera -> XYZ");
        sensorToProPhoto = Conv::multiply(Conv::sXYZtoProPhoto, sensorToXYZ);
    } catch (const std::invalid_argument& e) {
        chainFailed = true;
        colourFailure = (colourFailure.empty() ? "" : colourFailure + "; ") + "camera -> XYZ failed (" + e.what() + ")";
        p.sceneCct = 0.f;
        p.sceneCctSource = "unknown: " + colourFailure;
        log(colourFailure);
    }
    const bool placeholderFm = placeholderForward(fwd1) && placeholderForward(fwd2);
    if (placeholderFm) log("the DNG forward matrices are the sRGB -> XYZ placeholder: the plain-session table or the ISP matrix (P315)");
    std::string route = "DNG_FM", why;
    std::optional<F9> cmOnly;
    if (chainFailed) {
        why = colourFailure;
        route = "IDENTITY_FALLBACK";
        log("colour: " + route + " (" + why + ")");
    } else if (placeholderFm || noForward) {
        why = noForward ? "no forward matrix" : "the forward matrices are the sRGB -> XYZ placeholder";
        // Parameters.colourMatrixRoute
        auto colourMatrixRoute = [&]() -> std::optional<F9> {
            if (placeholderColorMatrix(cm1, ref1) || placeholderColorMatrix(cm2, ref2)) return std::nullopt;
            Dng::Color dc = dngWhite ? *dngWhite : makeWhite();
            if (!dc.usable()) return std::nullopt;
            const auto xy = dc.estimateXy(n);
            if (!xy) return std::nullopt;
            const auto cd = Dng::cctDuv(*xy);
            if (!(std::fabs(cd[1]) <= DUV_GUARD)) return std::nullopt;
            const D9 c2x = dc.cameraToXyzD50(n);
            F9 cc, ccInv;
            const D9 part = dc.abcc(cd[0]);
            for (int i = 0; i < 9; i++) cc[size_t(i)] = float(part[size_t(i)]);
            if (!Conv::invert(cc, ccInv)) return std::nullopt;
            double target = 0;
            for (int r = 0; r < 3; r++)
                target = std::max(target, ccInv[size_t(r * 3)] * n[0] + ccInv[size_t(r * 3 + 1)] * n[1] + ccInv[size_t(r * 3 + 2)] * n[2]);
            const double y = c2x[3] * n[0] + c2x[4] * n[1] + c2x[5] * n[2];
            if (!(y > 1e-9) || !(target > 0)) return std::nullopt;
            F9 toXyz;
            for (int i = 0; i < 9; i++) toXyz[size_t(i)] = float(c2x[size_t(i)] * target / y);
            const F9 out = Conv::multiply(Conv::sXYZtoProPhoto, toXyz);
            for (float v : out) if (!std::isfinite(v)) return std::nullopt;
            return out;
        };
        if ((cmOnly = colourMatrixRoute())) {
            route = "DNG_CM_ONLY";
            why += ": the colour matrices alone";
        } else {
            route = "IDENTITY_PLACEHOLDER";
            why += ": nothing better, camera RGB taken as sRGB";
        }
        log("colour: " + route + " (" + why + ")");
    }
    if (route == "IDENTITY_FALLBACK") {
        sensorToProPhoto = {1.0f / wp[0], 0, 0, 0, 1.0f / wp[1], 0, 0, 0, 1.0f / wp[2]};
    } else if (cmOnly) {
        sensorToProPhoto = *cmOnly;
    }
    p.sensorToProPhoto = vecOf(sensorToProPhoto);
    F9 proPhotoToSRGB = Conv::multiply(Conv::sXYZtoSRGB, Conv::sProPhotoToXYZ);
    if (route == "IDENTITY_FALLBACK") proPhotoToSRGB = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    std::vector<float> pro = vecOf(proPhotoToSRGB);
    // P408: «Профиль матрицы», else «Своя матрица 3x3»
    Settings& prefs = Settings::current();
    int presetId = 0;
    try { presetId = std::stoi(prefs.hybridString("why_ccm_profile", "0")); } catch (...) { presetId = 0; }
    std::optional<std::vector<float>> own = presetId > 0 ? presetMatrix(presetId) : std::nullopt;
    const bool preset = own.has_value();
    if (!own && prefs.hybridSwitch("why_ccm_override", false)) own = parsePreset(prefs.hybridString("why_ccm_matrix", "1, 0, 0, 0, 1, 0, 0, 0, 1"));
    if (own && normaliseRows(*own)) {
        std::vector<float> s(9);
        for (int i = 0; i < 9; i++) s[size_t(i)] = i % 4 == 0 ? 1.f / wp[size_t(i / 3)] : 0.f;
        p.sensorToProPhoto = s;
        pro = *own;
        route = preset ? "CCM_PRESET" : "CCM_OVERRIDE";
        why = preset ? "profile " + std::to_string(presetId) : "";
    }
    p.proPhotoToSRGB = pro;
    p.CCT = ColorCorrection{pro};
    p.colourSource = route;
    p.colourSourceWhy = why;
    std::snprintf(buf, sizeof buf, ", scene %.0f K (", double(p.sceneCct));
    std::string summary = "colour: " + route + (why.empty() ? "" : " (" + why + ")") + buf + p.sceneCctSource;
    if (!std::isnan(p.sceneDuv)) { std::snprintf(buf, sizeof buf, ", Duv %.4f", double(p.sceneDuv)); summary += buf; }
    log(summary + "), neutral " + arrayString(wp));
}

} // namespace ShotColour
} // namespace scam_post
