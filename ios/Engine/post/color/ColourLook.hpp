// processing/color/ColourLook.java one to one (P134 «Цвет»: the white matrix and the combine uniforms), and
// CcmPresets.dynamicCct.
#pragma once

#include <array>
#include <cmath>
#include <optional>
#include <string>
#include <vector>

namespace scam_post {

using Mat3 = std::array<float, 9>;
using OptMat3 = std::optional<Mat3>;

struct ColourLook {
    static constexpr const char* BANDS[8] = {"red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta"};
    static constexpr float BAND_HUE[8] = {29.f, 53.f, 110.f, 143.f, 195.f, 264.f, 294.f, 344.f};
    static constexpr const char* MONO[7] = {"off", "neutral", "yellow", "orange", "red", "green", "blue"};
    static constexpr const char* HUE_LOCK[3] = {"full", "blues", "off"};
    static constexpr const char* ZONES[3] = {"shadows", "mids", "highlights"};

    float warmth = 0, tint = 0, keepWarm = 0, keepCool = 0;
    float skinProtect = 0, skinHue = 0, skinEven = 0, vibShadows = 0, vibHighlights = 0;
    bool protectsVibrance = false;
    std::array<float, 24> mix{};
    std::array<float, 3> gradeHue{}, gradeStr{};
    float gradeBalance = 0, gradeBlend = 50.f;
    int mono = 0, hueLock = 0;
    float gamut = 0;
    std::array<float, 3> calHue{}, calSat{};

    bool shaderNeutral() const {
        if ((protectsVibrance && skinProtect != 0.f) || skinHue != 0.f || skinEven != 0.f || vibShadows != 0.f || vibHighlights != 0.f) return false;
        for (float v : mix) if (v != 0.f) return false;
        for (float s : gradeStr) if (s != 0.f) return false;
        return mono == 0 && hueLock == 0 && gamut == 0.f;
    }
    bool shaderNeutral(bool positiveAgxSaturation) const { return !(positiveAgxSaturation && skinProtect > 0.f) && shaderNeutral(); }
    bool mixerOn() const { for (float v : mix) if (v != 0.f) return true; return false; }
    bool matrixNeutral(float sceneCct) const {
        return warmth == 0.f && tint == 0.f && sceneLightK(sceneCct)[0] == 0.f && sceneLightK(sceneCct)[1] == 0.f && calibrationNeutral();
    }
    bool calibrationNeutral() const {
        for (int i = 0; i < 3; i++) if (calHue[i] != 0.f || calSat[i] != 0.f) return false;
        return true;
    }

    OptMat3 calibrationMatrix() const {
        if (calibrationNeutral()) return std::nullopt;
        std::array<double, 9> m{};
        for (int p = 0; p < 3; p++) {
            auto lab = oklab(p == 0 ? 1 : 0, p == 1 ? 1 : 0, p == 2 ? 1 : 0);
            double c = std::hypot(lab[1], lab[2]) * (1 + 0.6 * clamp(calSat[p] / 100.f, -1.f, 1.f));
            double h = std::atan2(lab[2], lab[1]) + 0.5 * clamp(calHue[p] / 100.f, -1.f, 1.f);
            auto rgb = linearOf(lab[0], c * std::cos(h), c * std::sin(h));
            for (int r = 0; r < 3; r++) m[size_t(r * 3 + p)] = rgb[size_t(r)];
        }
        auto k = mulVec(invert(m), {1, 1, 1});
        Mat3 out{};
        for (int r = 0; r < 3; r++)
            for (int p = 0; p < 3; p++) out[size_t(r * 3 + p)] = float(m[size_t(r * 3 + p)] * k[size_t(p)]);
        return out;
    }

    std::array<float, 3> skinUniform() const {
        return {clamp(skinProtect / 100.f, 0.f, 1.f), clamp(skinHue / 100.f, -1.f, 1.f), clamp(skinEven / 100.f, 0.f, 1.f)};
    }
    std::array<float, 2> vibUniform() const { return {clamp(vibShadows, -1.f, 1.f), clamp(vibHighlights, -1.f, 1.f)}; }
    std::vector<float> mixUniform() const {
        std::vector<float> u(24);
        for (int i = 0; i < 24; i++) u[size_t(i)] = clamp(mix[size_t(i)] / 100.f, -1.f, 1.f);
        return u;
    }
    std::array<float, 3> monoUniform() const {
        static const float T[7][3] = {{0, 0, 0}, {1.f, 1.f, 1.f}, {1.f, 0.9f, 0.1f}, {1.f, 0.6f, 0.05f}, {1.f, 0.2f, 0.02f},
                                      {0.25f, 1.f, 0.3f}, {0.1f, 0.25f, 1.f}};
        if (mono > 0 && mono < 7) return {T[mono][0], T[mono][1], T[mono][2]};
        return {0.f, 0.f, 0.f};
    }
    std::array<std::array<float, 4>, 2> gradeUniforms() const {
        float off[6];
        for (int z = 0; z < 3; z++) {
            float s = clamp(gradeStr[size_t(z)] / 100.f, 0.f, 1.f) * 0.06f;
            double h = oklabHueOfWheel(gradeHue[size_t(z)]) * M_PI / 180.0;
            off[2 * z] = float(s * std::cos(h));
            off[2 * z + 1] = float(s * std::sin(h));
        }
        return {{{off[0], off[1], off[2], off[3]}, {off[4], off[5], clamp(gradeBalance / 100.f, -1.f, 1.f), clamp(gradeBlend / 100.f, 0.f, 1.f)}}};
    }

    static double oklabHueOfWheel(double wheel) {
        double h = std::fmod(std::fmod(wheel, 360.0) + 360.0, 360.0) / 60.0;
        int i = int(std::floor(h));
        double f = h - i;
        double rgb[3];
        switch (i % 6) {
        case 0: rgb[0] = 1; rgb[1] = f; rgb[2] = 0; break;
        case 1: rgb[0] = 1 - f; rgb[1] = 1; rgb[2] = 0; break;
        case 2: rgb[0] = 0; rgb[1] = 1; rgb[2] = f; break;
        case 3: rgb[0] = 0; rgb[1] = 1 - f; rgb[2] = 1; break;
        case 4: rgb[0] = f; rgb[1] = 0; rgb[2] = 1; break;
        default: rgb[0] = 1; rgb[1] = 0; rgb[2] = 1 - f; break;
        }
        for (double& c : rgb) c = c <= 0.04045 ? c / 12.92 : std::pow((c + 0.055) / 1.055, 2.4);
        double l = std::cbrt(0.4122214708 * rgb[0] + 0.5363325363 * rgb[1] + 0.0514459929 * rgb[2]);
        double m = std::cbrt(0.2119034982 * rgb[0] + 0.6806995451 * rgb[1] + 0.1073969566 * rgb[2]);
        double s = std::cbrt(0.0883024619 * rgb[0] + 0.2817188376 * rgb[1] + 0.6299787005 * rgb[2]);
        double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        return std::fmod(std::atan2(b, a) * 180.0 / M_PI + 360.0, 360.0);
    }

    OptMat3 whiteMatrix(float sceneCct) const {
        if (matrixNeutral(sceneCct)) return std::nullopt;
        OptMat3 cal = calibrationMatrix();
        if (warmth == 0.f && tint == 0.f && sceneLightK(sceneCct)[0] == 0.f && sceneLightK(sceneCct)[1] == 0.f) return cal;
        const double REF_T = 6504.0;
        auto ref = lms(daylightXy(REF_T));
        double t = 1e6 / (1e6 / REF_T + 0.5 * clamp(warmth, -100.f, 100.f));
        auto uv = xyToUv(daylightXy(t));
        auto n = locusNormal(t);
        double duv = -0.00015 * clamp(tint, -100.f, 100.f);
        auto target = lms(uvToXy({uv[0] + duv * n[0], uv[1] + duv * n[1]}));
        auto k = sceneLightK(sceneCct);
        std::array<double, 3> dst{};
        if (k[0] > 0.f || k[1] > 0.f) {
            double kk = std::max(k[0], k[1]);
            auto scene = lms(k[0] > 0.f ? planckXy(sceneCct) : daylightXy(sceneCct));
            for (int c = 0; c < 3; c++) dst[size_t(c)] = ref[size_t(c)] + (scene[size_t(c)] - ref[size_t(c)]) * kk;
        } else dst = ref;
        std::array<double, 9> diag{};
        for (int c = 0; c < 3; c++) diag[size_t(c * 4)] = dst[size_t(c)] / ref[size_t(c)] * (target[size_t(c)] / ref[size_t(c)]);
        auto b = mul(XYZ_TO_SRGB(), mul(M16_INV(), mul(diag, mul(M16(), SRGB_TO_XYZ()))));
        double y = 0.2126 * (b[0] + b[1] + b[2]) + 0.7152 * (b[3] + b[4] + b[5]) + 0.0722 * (b[6] + b[7] + b[8]);
        Mat3 out{};
        for (int i = 0; i < 9; i++) out[size_t(i)] = float(b[size_t(i)] / y);
        return cal ? mul(out, *cal) : out;
    }

    std::array<float, 2> sceneLightK(float cct) const {
        if (!(cct > 1000.f) || cct > 40000.f) return {0.f, 0.f};
        float mired = 1e6f / cct;
        float warm = clamp(keepWarm / 100.f, 0.f, 1.f) * clamp((mired - 200.f) / (370.f - 200.f), 0.f, 1.f);
        float cool = clamp(keepCool / 100.f, 0.f, 1.f) * clamp((cct - 7000.f) / (12000.f - 7000.f), 0.f, 1.f);
        return {warm, cool};
    }

    static std::array<double, 2> daylightXy(double t) {
        t = std::max(4000.0, std::min(25000.0, t));
        double x = t <= 7000.0 ? 0.244063 + 0.09911e3 / t + 2.9678e6 / (t * t) - 4.6070e9 / (t * t * t)
                               : 0.237040 + 0.24748e3 / t + 1.9018e6 / (t * t) - 2.0064e9 / (t * t * t);
        return {x, -3.0 * x * x + 2.870 * x - 0.275};
    }
    static std::array<double, 2> planckXy(double t) {
        t = std::max(1667.0, std::min(25000.0, t));
        double t2 = t * t, t3 = t2 * t;
        double x = t <= 4000.0 ? -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
                               : -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390;
        double x2 = x * x, x3 = x2 * x;
        double y = t <= 2222.0 ? -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
                 : t <= 4000.0 ? -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
                               : 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;
        return {x, y};
    }

    static Mat3 mul(const Mat3& a, const Mat3& b) {
        Mat3 r{};
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[size_t(i * 3 + j)] = a[size_t(i * 3)] * b[size_t(j)] + a[size_t(i * 3 + 1)] * b[size_t(3 + j)] + a[size_t(i * 3 + 2)] * b[size_t(6 + j)];
        return r;
    }
    static float clamp(float v, float lo, float hi) { return std::isnan(v) ? 0.f : std::max(lo, std::min(hi, v)); }

private:
    using D9 = std::array<double, 9>;
    static const D9& SRGB_TO_XYZ() { static const D9 m = {0.4123908, 0.3575843, 0.1804808, 0.2126390, 0.7151687, 0.0721923, 0.0193308, 0.1191948, 0.9505322}; return m; }
    static const D9& XYZ_TO_SRGB() { static const D9 m = invert(SRGB_TO_XYZ()); return m; }
    static const D9& M16() { static const D9 m = {0.401288, 0.650173, -0.051461, -0.250268, 1.204414, 0.045854, -0.002079, 0.048952, 0.953127}; return m; }
    static const D9& M16_INV() { static const D9 m = invert(M16()); return m; }

    static std::array<double, 3> oklab(double r, double g, double b) {
        double l = std::cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = std::cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = std::cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return {0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s, 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }
    static std::array<double, 3> linearOf(double L, double a, double b) {
        double l = L + 0.3963377774 * a + 0.2158037573 * b, m = L - 0.1055613458 * a - 0.0638541728 * b, s = L - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l; m = m * m * m; s = s * s * s;
        return {4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }
    static std::array<double, 3> mulVec(const D9& m, std::array<double, 3> v) {
        return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }
    static std::array<double, 2> locusNormal(double t) {
        double m = 1e6 / t;
        auto a = xyToUv(daylightXy(1e6 / (m - 1.0))), b = xyToUv(daylightXy(1e6 / (m + 1.0)));
        double du = b[0] - a[0], dv = b[1] - a[1];
        double len = std::hypot(du, dv);
        if (len < 1e-12) return {0, 1};
        double nu = -dv / len, nv = du / len;
        return nv >= 0 ? std::array<double, 2>{nu, nv} : std::array<double, 2>{-nu, -nv};
    }
    static std::array<double, 2> xyToUv(std::array<double, 2> xy) {
        double d = -2.0 * xy[0] + 12.0 * xy[1] + 3.0;
        return {4.0 * xy[0] / d, 6.0 * xy[1] / d};
    }
    static std::array<double, 2> uvToXy(std::array<double, 2> uv) {
        double d = 2.0 * uv[0] - 8.0 * uv[1] + 4.0;
        return {3.0 * uv[0] / d, 2.0 * uv[1] / d};
    }
    static std::array<double, 3> lms(std::array<double, 2> xy) {
        const D9& M = M16();
        double xyz[3] = {xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1]};
        return {M[0] * xyz[0] + M[1] * xyz[1] + M[2] * xyz[2], M[3] * xyz[0] + M[4] * xyz[1] + M[5] * xyz[2],
                M[6] * xyz[0] + M[7] * xyz[1] + M[8] * xyz[2]};
    }
    static D9 mul(const D9& a, const D9& b) {
        D9 r{};
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[size_t(i * 3 + j)] = a[size_t(i * 3)] * b[size_t(j)] + a[size_t(i * 3 + 1)] * b[size_t(3 + j)] + a[size_t(i * 3 + 2)] * b[size_t(6 + j)];
        return r;
    }
    static D9 invert(const D9& m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        return {A / det, -(b * i - c * h) / det, (b * f - c * e) / det, B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }
};

/// CcmPresets.dynamicCct
inline Mat3 dynamicCct(const Mat3& m, int iso, float strength) {
    if (strength <= 0.001f || iso <= 400) return m;
    const float t = float(std::max(0.0, std::min(1.0, std::log(std::max(400.f, std::min(6400.f, float(iso))) / 400.0) / std::log(16.0)))
                          * std::max(0.f, std::min(1.f, strength)));
    if (t <= 0.001f) return m;
    Mat3 out{};
    for (int r = 0; r < 3; r++) {
        const float sum = m[size_t(r * 3)] + m[size_t(r * 3 + 1)] + m[size_t(r * 3 + 2)];
        for (int c = 0; c < 3; c++) out[size_t(r * 3 + c)] = m[size_t(r * 3 + c)] * (1.f - t) + (r == c ? sum * t : 0.f);
    }
    return out;
}

} // namespace scam_post
