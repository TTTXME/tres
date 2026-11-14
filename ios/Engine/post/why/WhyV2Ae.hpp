// «Why тон» V2 (P614): WhyV2Ae.java and WhyV2Curve.java one to one (pure maths: the reference tone's histograms, exposure and
// fusion strengths, the pyramid's levels, the AgX inset / outset, the own curve's table), and WhyV2Stats.State (what the V2
// nodes hand on). Java float semantics: float maths in float, Math.log / exp / pow / sqrt in double, the "fma" as the Java
// writes it ((float) (a x b + c) in double), Math.min / max with NaN and signed zeros as Java's.
#pragma once

#include "../GLCore.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <optional>
#include <string>
#include <vector>

namespace scam_post {

namespace jmath {
/// Math.max(float, float): NaN if either is NaN, +0 over -0
inline float max(float a, float b) {
    if (a != a) return a;
    if (b != b) return b;
    if (a == 0.f && b == 0.f) return std::signbit(a) ? b : a;
    return a >= b ? a : b;
}
/// Math.min(float, float): NaN if either is NaN, -0 under +0
inline float min(float a, float b) {
    if (a != a) return a;
    if (b != b) return b;
    if (a == 0.f && b == 0.f) return std::signbit(a) ? a : b;
    return a <= b ? a : b;
}
/// Math.abs(float)
inline float abs(float a) { return std::fabs(a); }
/// Math.ulp(float)
inline float ulp(float f) {
    if (std::isnan(f)) return f;
    const float a = std::fabs(f);
    if (std::isinf(a)) return std::numeric_limits<float>::infinity();
    if (a == std::numeric_limits<float>::max()) return a - std::nextafter(a, 0.f);
    return std::nextafter(a, std::numeric_limits<float>::infinity()) - a;
}
/// (int) of a float: truncation, NaN 0, saturated
inline int toInt(float v) {
    if (v != v) return 0;
    if (v >= 2147483647.f) return 2147483647;
    if (v <= -2147483648.f) return int(-2147483647 - 1);
    return int(v);
}
/// (long) of a float: truncation, NaN 0, saturated
inline int64_t toLong(float v) {
    if (v != v) return 0;
    if (v >= 9.2233720368547758e18f) return std::numeric_limits<int64_t>::max();
    if (v <= -9.2233720368547758e18f) return std::numeric_limits<int64_t>::min();
    return int64_t(v);
}
/// Float.isFinite
inline bool isFinite(float v) { return std::isfinite(v); }

/// String.trim: strips every char <= ' ' at both ends
inline std::string trim(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && static_cast<unsigned char>(s[a]) <= ' ') a++;
    while (b > a && static_cast<unsigned char>(s[b - 1]) <= ' ') b--;
    return s.substr(a, b - a);
}
/// String.split(String regex) of a one-char literal: the parts, trailing empty strings removed (no match: the string itself)
inline std::vector<std::string> split(const std::string& s, char sep) {
    std::vector<std::string> out;
    if (s.find(sep) == std::string::npos) return {s};
    std::string cur;
    for (char c : s) {
        if (c == sep) { out.push_back(cur); cur.clear(); }
        else cur += c;
    }
    out.push_back(cur);
    while (!out.empty() && out.back().empty()) out.pop_back();
    return out;
}
/// Float.parseFloat: false for what Java rejects (NumberFormatException). Decimal / "NaN" / "Infinity" with an optional sign and
/// a trailing f / F / d / D; hexadecimal floats are taken by strtof.
inline bool parseFloat(const std::string& in, float& out) {
    std::string s = trim(in);
    if (s.empty()) return false;
    std::string body = s;
    size_t sign = (body[0] == '+' || body[0] == '-') ? 1 : 0;
    const std::string rest = body.substr(sign);
    if (rest == "NaN") { out = std::numeric_limits<float>::quiet_NaN(); return true; }
    if (rest == "Infinity") { out = body[0] == '-' ? -std::numeric_limits<float>::infinity() : std::numeric_limits<float>::infinity(); return true; }
    const char last = body.back();
    if (last == 'f' || last == 'F' || last == 'd' || last == 'D') body.pop_back();
    if (body.size() <= sign) return false;
    for (size_t i = sign; i < body.size(); i++) {
        const char c = body[i];
        const bool hex = body.size() > sign + 1 && body[sign] == '0' && (body[sign + 1] == 'x' || body[sign + 1] == 'X');
        if (!(std::isdigit(static_cast<unsigned char>(c)) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'
              || (hex && (std::isxdigit(static_cast<unsigned char>(c)) || c == 'x' || c == 'X' || c == 'p' || c == 'P'))))
            return false;
    }
    char* end = nullptr;
    const float v = std::strtof(body.c_str(), &end);
    if (end != body.c_str() + body.size()) return false;
    out = v;
    return true;
}
/// String.format(Locale.US, "%.4f", float): the double's shortest decimal digits (FloatingDecimal) rounded half up at 4 places
inline std::string format4(float f) {
    const double d = f;
    if (std::isnan(d)) return "NaN";
    if (std::isinf(d)) return d > 0 ? "Infinity" : "-Infinity";
    const bool neg = std::signbit(d);
    const double a = std::fabs(d);
    if (a == 0.0) return neg ? "-0.0000" : "0.0000";
    char buf[64];
    int prec = 1;
    for (; prec <= 17; prec++) {
        std::snprintf(buf, sizeof buf, "%.*e", prec - 1, a);
        if (std::strtod(buf, nullptr) == a) break;
    }
    std::snprintf(buf, sizeof buf, "%.*e", std::min(prec, 17) - 1, a);
    const std::string s = buf;
    const size_t e = s.find('e');
    std::string digits;
    for (size_t i = 0; i < e; i++) if (s[i] != '.') digits += s[i];
    const int exp = std::atoi(s.c_str() + e + 1);
    // digits[i] weighs 10^(exp - i); keep those down to 10^-4
    const int keep = exp + 5;
    std::string kept;
    bool up = false;
    if (keep < 0) {
        up = false;
    } else if (keep == 0) {
        up = digits[0] >= '5';
    } else {
        for (int i = 0; i < keep; i++) kept += i < int(digits.size()) ? digits[size_t(i)] : '0';
        up = keep < int(digits.size()) && digits[size_t(keep)] >= '5';
    }
    // kept as an integer of 10^-4 units (+1 when rounding up), in decimal digits
    std::string n = kept.empty() ? "0" : kept;
    if (up) {
        int i = int(n.size()) - 1;
        while (i >= 0 && n[size_t(i)] == '9') { n[size_t(i)] = '0'; i--; }
        if (i < 0) n.insert(n.begin(), '1');
        else n[size_t(i)]++;
    }
    while (n.size() < 5) n.insert(n.begin(), '0');
    size_t lead = 0;
    while (lead + 5 < n.size() && n[lead] == '0') lead++;
    n = n.substr(lead);
    return std::string(neg ? "-" : "") + n.substr(0, n.size() - 4) + "." + n.substr(n.size() - 4);
}
} // namespace jmath

namespace WhyV2Ae {

/// The owner's own-curve points (pref_custom_tone_curve_points_key of the reference configuration).
constexpr const char* OWNER_POINTS = "0.0000:0.0000,0.0625:0.0314,0.1250:0.0599,0.1875:0.0881,0.2500:0.1282,0.3125:0.1971,"
                                     "0.3750:0.2867,0.4375:0.3915,0.5000:0.5100,0.5625:0.6347,0.6250:0.7542,0.6875:0.8577,0.7500:0.9350,0.8125:0.9757,"
                                     "0.8750:0.9937,0.9375:1.0000,1.0000:1.0000";

constexpr int BINS = 4096;

/// WhyV2Ae.Params: the reference tone's parameters (the owner's configuration of it as the defaults)
struct Params {
    float aeTarget = 0.14999999f, maxBoost = 5.0f, minLimit = 0.5f, facePriority = 0.7f;
    float nightDim = 0.049999997f, hlOverflow = 2.0f, hlBlend = 0.5f;
    float brightThresh = 0.90000004f, darkThresh = 12.5f, darkPixelThresh = 22.f, hlBoost = 1.1f, contrastBoost = 0.3f;
    float expEv = 0.f;
    float highlightStr = 2.1000001f, shadowStr = 2.0f;
    float weightExtHl = 0.7f, weightHl = 1.0f, weightMid = 2.0f, weightShadow = 1.0f;
    float weightCenter = 1.0f, blendSmoothness = 0.45000002f, macroContrast = 1.1f, clarity = 0.f;
    int toneOperator = 3, agxLook = 4, gammaType = 0;
    float gamma = 2.2f, filmToe = 0.f;
    float agxSlope = 2.45f, agxSp = 1.35f, agxTp = 2.0f, agxMinEv = -8.5f, agxMaxEv = 3.5f, agxSat = 1.0f, agxEv = 0.f;
    bool agxHueLock = true;
    float agxInset = 50.f;
    float acesToe = 0.05f, acesPreGain = 1.0f, acesA = 2.61f, acesD = 0.59f;
    float uchiPreGain = 1.0f, uchiPostGain = 1.0f, uchiP = 1.0f, uchiA = 1.0f, uchiM = 0.22f, uchiL = 0.4f, uchiC = 1.33f, uchiB = 0.f;
    float vibStrength = 0.f, vibSaturated = 0.f, skinProtect = 1.0f, vibSky = 0.25f, vibGreen = 0.05f, vibHl = 0.25f;
    float vibMid = 0.f, vibShadow = 0.f, skinHueShift = 0.f, skinSatShift = 0.f, skinHueCenterDeg = 41.25f, skinLumaMin = 0.16f;
    float shoulderStrength = 0.6f, shoulderKnee = 0.6f, shoulderHeadroom = 0.35f;
    float skinHueCenterRad() const { return skinHueCenterDeg * 0.017453292f; }
    float lutStrength = 1.0f;
    std::string curvePoints = OWNER_POINTS;
    std::array<float, 7> curveMods{};
    float bracketDenoise = 0.f, hrAmount = 0.f;
};

/// WhyV2Ae.Hist
struct Hist {
    std::vector<int> luma = std::vector<int>(BINS), hl = std::vector<int>(BINS);
    int total = 0, facePixels = 0;
    int64_t faceLumaSum = 0;
};

/// WhyV2Ae.Result
struct Result {
    float ae = 1.f, clip = 1.f, deficit = 1.f, geo = 0, meter = 0, p10 = 0, p50 = 0, p98 = 0, p995 = 0, ovShare = 0, spread = 0, rr = 0, m = 0,
          area = 0, realPeak = 0, flat = 0;
    float effS = 0, effH = 0, contrastBoost = 0, noiseCap = 0;
    float agxSlope = 0, acesD = 0, uchiA = 0, macroContrast = 0;
    bool face = false;

    std::string describe() const {
        char buf[1024];
        std::snprintf(buf, sizeof buf,
                      "ae=%.4f clip=%.3f geo=%.5f meter=%.5f p10=%.5f p50=%.5f p98=%.5f p995=%.5f deficit=%.4f noiseCap=%.3f ov=%.4f spread=%.4f"
                      " rr=%.3f m=%.3f area=%.3f peak=%.4f flat=%.3f effS=%.4f effH=%.4f cb=%.4f slope=%.3f acesD=%.3f uchiA=%.3f macro=%.3f face=%d",
                      double(ae), double(clip), double(geo), double(meter), double(p10), double(p50), double(p98), double(p995), double(deficit),
                      double(noiseCap), double(ovShare), double(spread), double(rr), double(m), double(area), double(realPeak), double(flat),
                      double(effS), double(effH), double(contrastBoost), double(agxSlope), double(acesD), double(uchiA), double(macroContrast), face ? 1 : 0);
        return buf;
    }
};

inline int stride(int width, int height) { return std::max(1, std::max(width / 1024, height / 768)); }
inline int samples(int n, int stride) { return (n + stride - 1) / stride; }
inline float clipCeiling(float ceiling) { return ceiling > 1.05f ? ceiling : 1.f; }

inline int bin(float v) {
    if (std::isnan(v)) return 0;
    const float c = jmath::max(0.f, jmath::min(1.f, v));
    return std::max(0, std::min(BINS - 1, jmath::toInt(c * 4095.99f)));
}
inline int64_t uintOf(float v) { return v > 0.f ? (jmath::toLong(v) & 0xffffffffLL) : 0LL; }

inline void add(Hist& h, float r, float g, float b, float clip, bool inFace) {
    const float vLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    const float vHl = jmath::max(vLuma, jmath::max(r, jmath::max(g, b)));
    const float inv = 1.0f / jmath::max(clip, 0.0001f);
    h.luma[size_t(bin(vLuma * inv))]++;
    h.hl[size_t(bin(vHl * inv))]++;
    h.total++;
    if (inFace) {
        h.facePixels++;
        h.faceLumaSum = (h.faceLumaSum + uintOf(vLuma * 1000.0f)) & 0xffffffffLL;
    }
}

/// the Java's fma: (float) ((double) a x b + c)
inline float fma(float a, float b, float c) { return float(double(a) * double(b) + double(c)); }

/// WhyV2Ae.finish
inline Result finish(const Hist& h, const Params& p, float clipCeil, float noiseS, float noiseO) {
    Result r;
    const float clip = clipCeiling(clipCeil);
    r.clip = clip;
    double logSum = 0;
    for (int i = 0; i < BINS; i++) {
        const int n = h.luma[size_t(i)];
        if (n == 0) continue;
        float v = (float(i) / 4096.f + 0.0001220703125f) * clip;
        if (v < 1e-4f) v = 1e-4f;
        logSum += double(float(std::log(double(v)))) * double(n);
    }
    const int total = std::max(h.total, 1);
    const float totalF = float(total), invTotal = 1.f / totalF;
    const float step = clip * 0.000244140625f;
    float p10 = 0.f, p50 = 0.f;
    int cum = 0;
    bool half = false;
    for (int i = 0; i < BINS; i++) {
        cum += h.luma[size_t(i)];
        const float share = float(cum) * invTotal;
        const float v = step * float(i);
        if (share >= 0.1f && p10 == 0.f) p10 = v;
        if (share >= 0.5f) { p50 = v; half = true; break; }
    }
    if (!half) p50 = 0.f;
    float p98 = clip, p995 = 0.f;
    cum = 0;
    for (int i = 0; i < BINS; i++) {
        cum += h.hl[size_t(i)];
        const float share = float(cum) * invTotal;
        const float v = step * float(i);
        if (share >= 0.98f && p98 == clip) p98 = v;
        if (share >= 0.995f && p995 == 0.f) p995 = v;
    }
    if (p98 == clip) p98 = clip * 0.99975586f;
    if (p995 == 0.f) p995 = p98;
    r.p10 = p10; r.p50 = p50; r.p98 = p98; r.p995 = p995;
    const float geo = float(std::exp(logSum / double(total)));
    r.geo = geo;
    float meter = geo;
    const bool face = h.facePixels > 0;
    r.face = face;
    if (face) {
        float fm = float(double(h.faceLumaSum) * 0.001) / float(h.facePixels);
        fm = jmath::max(jmath::min(fm, 1.f), 0.001f);
        float prio = p.facePriority > 1.f ? 1.f : p.facePriority;
        if (p.facePriority < 0.f) prio = 0.f;
        meter = fma(prio, fm - geo, geo);
    }
    r.meter = meter;
    const float ev = float(std::pow(2.0, double(p.expEv)));
    const float maxB = jmath::max(ev, 1.f) * p.maxBoost, minL = jmath::min(ev, 1.f) * p.minLimit;
    float deficit = 1.f, capped;
    float noiseCap = 0.f;
    if (meter > 1e-4f) {
        const float a = p.aeTarget * ev / meter;
        float aeC = maxB < a ? maxB : a;
        if (aeC < minL) aeC = minL;
        float aeF = aeC;
        if (p.hlOverflow < 9.99f && p995 > 0.01f && aeC * p995 > p.hlOverflow) {
            const float lim = p.hlOverflow / p995;
            const float blend = fma(face ? p.facePriority : 0.f, 1.f - p.hlBlend, p.hlBlend);
            aeF = fma(blend, aeC - lim, lim);
        }
        if (aeF < minL) aeF = minL;
        if (aeF > 0.001f && aeC > 0.001f && aeF < aeC) deficit = aeC / aeF;
        capped = aeF;
        if (noiseS > 0.f || noiseO > 0.f) {
            float v = fma(noiseS, 0.5f, noiseO);
            if (v < 1e-10f) v = 1e-10f;
            const float sigma = float(std::sqrt(double(v)));
            float d = jmath::max(sigma * 0.5f, 0.026f);
            if (!(d < sigma)) d = sigma;
            float nd = p.nightDim > 0.2f ? 0.2f : p.nightDim;
            if (p.nightDim < 0.01f) nd = 0.01f;
            float cap = nd / d;
            if (cap < 1.f) cap = 1.f;
            noiseCap = cap;
            if (aeF > cap) capped = cap;
        }
    } else {
        capped = 1.f;
    }
    float ae = maxB < capped ? maxB : capped;
    if (ae < minL) ae = minL;
    r.ae = ae;
    r.deficit = deficit;
    r.noiseCap = noiseCap;
    float t = p.brightThresh / (jmath::max(ae, 0.001f) * clip);
    t = jmath::max(jmath::min(t, 1.f), 0.f);
    const int j = jmath::toInt(t * 4095.99f);
    int64_t ov = 0;
    for (int i = std::max(0, j); i < BINS; i++) ov += h.hl[size_t(i)];
    const float f = float(ov) / totalF;
    r.ovShare = f;
    const float sp10 = float(std::sqrt(double(jmath::max(ae * p10, 0.f))));
    const float a98 = jmath::max(ae * p98, 0.f);
    const float sp98 = float(std::sqrt(double(a98 > ae ? ae : a98)));
    const float spread = sp98 - sp10;
    const float rr = spread / jmath::max(p.darkThresh * 0.15f, 0.001f);
    const float sp50 = float(std::sqrt(double(jmath::max(ae * p50, 0.f))));
    const float m = jmath::max(jmath::min(fma(sp50, 4.5454545f, -0.9090909f), 1.f), 0.f);
    const float rrHalf = fma(rr, 0.5f, 1.f);
    float effS = p.shadowStr * fma(m * m, -0.35f, 1.f) * rr * rrHalf / (rrHalf + rr);
    effS = fma(float(std::log(double(jmath::max(deficit, 1.f))) / std::log(2.0)), 0.5f, effS);
    effS = jmath::max(effS, 0.f);
    const float s20 = f * 20.f;
    float area = s20 > 1.f ? 1.f : s20;
    if (s20 < 0.f) area = 0.f;
    area = area * area * (3.f - 2.f * area);
    float tail = jmath::max(ae * p995 - a98, 0.f);
    const float lim = 1.5f * jmath::max(p.brightThresh, 0.5f);
    if (lim < tail) tail = lim;
    const float realPeak = fma(area, 0.25f * tail, a98);
    const float bt85 = p.brightThresh * 0.85f;
    float soft = 0.f;
    if (realPeak > bt85) {
        const float ex = float(std::log(double(realPeak / bt85)) / std::log(2.0));
        const float knee = float(std::log(double(p.brightThresh / bt85)) / std::log(2.0));
        if (ex < knee) {
            const float u = ex / knee;
            soft = u * u * (knee * 0.5f);
        } else {
            soft = fma(knee, -0.5f, ex);
        }
        soft *= 0.8333333f;
    }
    const float bigArea = fma(f, 5.f, -0.25f);
    const float gate = bigArea < 0.f ? 1.f : fma(bigArea > 1.f ? 1.f : bigArea, 0.35f, 1.f);
    float q = 1.f - spread / jmath::max(p.darkPixelThresh * 0.05f, 0.001f);
    q = jmath::max(q, 0.f);
    float effH = fma(area, 0.8f, 0.2f) * (gate * soft * jmath::max(p.hlBoost, 0.f));
    if (!(effH < p.highlightStr)) effH = p.highlightStr;
    const float flat = q * q * (3.f - 2.f * q);
    const float cb = q * q * p.contrastBoost * (3.f - 2.f * q);
    r.spread = spread; r.rr = rr; r.m = m; r.area = area; r.realPeak = realPeak; r.flat = flat;
    r.effS = effS;
    r.effH = effH;
    r.contrastBoost = cb;
    r.agxSlope = p.agxSlope; r.acesD = p.acesD; r.uchiA = p.uchiA; r.macroContrast = p.macroContrast;
    if (cb > 1e-4f) {
        r.acesD = jmath::max(fma(cb, -0.8f, p.acesD), 0.1f);
        r.agxSlope = p.agxSlope + cb;
        r.uchiA = fma(cb, 0.75f, p.uchiA);
        r.macroContrast = fma(cb, 0.3f, p.macroContrast);
    }
    return r;
}

/// ef_pyr_prep u_exp_multipliers
inline std::array<float, 4> exposures(const Result& r) {
    return {float(std::pow(2.0, double(-2.5f * r.effH))), float(std::pow(2.0, double(-1.2f * r.effH))), 1.f,
            float(std::min(std::pow(2.0, double(1.8f * r.effS)), 16.0))};
}

inline std::array<float, 4> layerWeights(const Params& p) { return {p.weightExtHl, p.weightHl, p.weightMid, p.weightShadow}; }

/// the Laplacian pyramid's level sizes
inline std::vector<std::array<int, 2>> levels(int width, int height) {
    std::vector<std::array<int, 2>> out;
    int w = width, h = height;
    out.push_back({w, h});
    while (w > 48 && h > 48 && out.size() <= 11) {
        w = w >> 1;
        h = h > 3 ? h / 2 : 1;
        out.push_back({w, h});
    }
    return out;
}

/// tonemap_final u_agx_mat_inset, column-major
inline std::vector<float> insetMatrix(float strength) {
    float t = strength > 1.f ? strength * 0.01f : strength;
    t = jmath::max(0.f, jmath::min(1.f, t));
    static const double m[9] = {0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                                0.0784335999999992, 0.878468636469772, 0.0784336,
                                0.0792237451477643, 0.0791661274605434, 0.879142973793104};
    std::vector<float> out(9);
    for (int i = 0; i < 9; i++) {
        const double id = i % 4 == 0 ? 1.0 : 0.0;
        out[size_t(i)] = float((1.0 - double(t)) * id + double(t) * m[i]);
    }
    return out;
}

/// u_agx_mat_outset: the inverse of insetMatrix (column-major), in double
inline std::vector<float> outsetMatrix(float strength) {
    const std::vector<float> a = insetMatrix(strength);
    const double a00 = a[0], a10 = a[1], a20 = a[2], a01 = a[3], a11 = a[4], a21 = a[5], a02 = a[6], a12 = a[7], a22 = a[8];
    const double r[9] = {
        a11 * a22 - a12 * a21, a02 * a21 - a01 * a22, a01 * a12 - a02 * a11,
        a12 * a20 - a10 * a22, a00 * a22 - a02 * a20, a02 * a10 - a00 * a12,
        a10 * a21 - a11 * a20, a01 * a20 - a00 * a21, a00 * a11 - a01 * a10};
    const double det = a00 * r[0] + a01 * r[3] + a02 * r[6];
    const double inv = std::fabs(det) > 1e-12 ? 1.0 / det : 1.0;
    std::vector<float> out(9);
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) out[size_t(j * 3 + i)] = float(r[i * 3 + j] * inv);
    return out;
}

} // namespace WhyV2Ae

namespace WhyV2Curve {

constexpr const char* DEFAULT_POINTS =
    "0.0000:0.0000,0.1250:0.0688,0.2500:0.1600,0.3750:0.3188,0.5000:0.5000,0.6250:0.6812,0.7500:0.8400,0.8750:0.9312,1.0000:1.0000";

/// ToneCurveHelper.parsePoints: [x0, y0, x1, y1, ...] sorted by x (stable)
inline std::vector<float> parse(const std::string& text) {
    std::vector<std::array<float, 2>> out;
    if (!jmath::trim(text).empty()) {
        for (const std::string& pair : jmath::split(text, ',')) {
            const std::vector<std::string> xy = jmath::split(jmath::trim(pair), ':');
            if (xy.size() != 2) continue;
            float x, y;
            if (!jmath::parseFloat(xy[0], x) || !jmath::parseFloat(xy[1], y)) continue;
            out.push_back({jmath::max(0.f, jmath::min(1.f, x)), jmath::max(0.f, jmath::min(1.f, y))});
        }
    }
    if (out.size() < 2) return parse(DEFAULT_POINTS);
    // Double.compare: -0 before +0, NaN last
    auto key = [](float v) { return std::isnan(v) ? 2 : 0; };
    std::stable_sort(out.begin(), out.end(), [&](const std::array<float, 2>& a, const std::array<float, 2>& b) {
        if (key(a[0]) != key(b[0])) return key(a[0]) < key(b[0]);
        if (a[0] < b[0]) return true;
        if (a[0] > b[0]) return false;
        return std::signbit(a[0]) && !std::signbit(b[0]);
    });
    std::vector<float> p(out.size() * 2);
    for (size_t i = 0; i < out.size(); i++) {
        p[2 * i] = out[i][0];
        p[2 * i + 1] = out[i][1];
    }
    return p;
}

constexpr float CURVE_B[17] = {0, 0, 4, 50, 178, 401, 674, 906, 1000, 906, 674, 401, 178, 50, 4, 0, 0},
                CURVE_C[17] = {0, 349, 659, 889, 1000, 964, 769, 431, 0, -431, -769, -964, -1000, -889, -659, -349, 0},
                CURVE_E[17] = {0, 349, 659, 889, 1000, 962, 769, 471, 206, 51, 6, 0, 0, 0, 0, 0, 0},
                CURVE_F[17] = {0, 0, 0, 0, 0, 0, -6, -51, -206, -471, -769, -962, -1000, -889, -659, -349, 0},
                CURVE_G[17] = {0, 667, 1000, 956, 697, 402, 180, 61, 14, 1, 0, 0, 0, 0, 0, 0, 0},
                CURVE_H[17] = {0, 0, 0, 0, 0, 0, 0, -1, -14, -61, -180, -402, -697, -956, -1000, -667, 0};

inline float coeff(const float* t, float x) {
    const float idx = x * 16.f;
    const int i = jmath::toInt(idx);
    if (i < 0) return t[0];
    if (i >= 16) return t[16];
    const float f = idx - float(i);
    return t[i] + f * f * (3.f - f * 2.f) * (t[i + 1] - t[i]);
}

inline float deltaY(float x, const std::array<float, 7>& m) {
    float v = coeff(CURVE_B, x) * m[0];
    v -= coeff(CURVE_C, x) * m[1];
    v -= coeff(CURVE_E, x) * m[2];
    v -= coeff(CURVE_F, x) * m[3];
    v += coeff(CURVE_G, x) * m[4];
    v -= coeff(CURVE_H, x) * m[5];
    return m[6] / 200.f + v * 2.5e-6f;
}

inline std::vector<float> applyModifiers(const std::vector<float>& p, const std::array<float, 7>& m) {
    bool none = true;
    for (float v : m) none &= jmath::abs(v) < 1e-4f;
    if (none) return p;
    std::vector<float> out = p;
    for (size_t i = 0; i + 1 < p.size(); i += 2) out[i + 1] = jmath::max(0.f, jmath::min(1.f, p[i + 1] + deltaY(p[i], m)));
    return out;
}

/// serializePoints ("%.4f:%.4f", Locale.US) then parsePoints
inline std::vector<float> stored(const std::vector<float>& p) {
    std::string s;
    for (size_t i = 0; i + 1 < p.size(); i += 2) {
        if (i > 0) s += ',';
        s += jmath::format4(p[i]) + ":" + jmath::format4(p[i + 1]);
    }
    return parse(s);
}

/// ToneCurveHelper.generateLut: Fritsch-Carlson tangents and the Hermite cubic, float
inline std::vector<float> lut(const std::vector<float>& pts, int n) {
    std::vector<float> out(static_cast<size_t>(n));
    const int k = int(pts.size() / 2);
    if (k < 2) {
        for (int i = 0; i < n; i++) out[size_t(i)] = float(i) / float(n - 1);
        return out;
    }
    std::vector<float> x(static_cast<size_t>(k)), y(static_cast<size_t>(k));
    for (int i = 0; i < k; i++) {
        x[size_t(i)] = pts[size_t(2 * i)];
        y[size_t(i)] = pts[size_t(2 * i + 1)];
    }
    x[0] = 0.f;
    x[size_t(k - 1)] = 1.f;
    for (int i = 1; i < k; i++) if (!(x[size_t(i)] > x[size_t(i - 1)])) x[size_t(i)] = jmath::min(1.f, x[size_t(i - 1)] + 0.001f);
    std::vector<float> h(static_cast<size_t>(k - 1)), d(static_cast<size_t>(k - 1)), m(static_cast<size_t>(k));
    for (int i = 0; i < k - 1; i++) {
        h[size_t(i)] = x[size_t(i + 1)] - x[size_t(i)];
        d[size_t(i)] = (y[size_t(i + 1)] - y[size_t(i)]) / jmath::max(1e-6f, h[size_t(i)]);
    }
    m[0] = d[0];
    m[size_t(k - 1)] = d[size_t(k - 2)];
    for (int i = 1; i < k - 1; i++) m[size_t(i)] = d[size_t(i - 1)] * d[size_t(i)] <= 0.f ? 0.f : (d[size_t(i - 1)] + d[size_t(i)]) * 0.5f;
    for (int i = 0; i < k - 1; i++) {
        if (jmath::abs(d[size_t(i)]) < 1e-7f) {
            m[size_t(i)] = 0.f;
            m[size_t(i + 1)] = 0.f;
            continue;
        }
        const float a = m[size_t(i)] / d[size_t(i)], b = m[size_t(i + 1)] / d[size_t(i)];
        if (a < 0.f) m[size_t(i)] = 0.f;
        if (b < 0.f) m[size_t(i + 1)] = 0.f;
        const float s = a * a + b * b;
        if (s > 9.f) {
            const float t = 3.f / float(std::sqrt(double(s)));
            m[size_t(i)] = t * a * d[size_t(i)];
            m[size_t(i + 1)] = t * b * d[size_t(i)];
        }
    }
    int seg = 0;
    for (int j = 0; j < n; j++) {
        const float t = float(j) / float(n - 1);
        while (seg < k - 2 && t > x[size_t(seg + 1)]) seg++;
        const float hh = jmath::max(1e-6f, x[size_t(seg + 1)] - x[size_t(seg)]);
        const float u = jmath::max(0.f, jmath::min(1.f, (t - x[size_t(seg)]) / hh));
        const float u2 = u * u, u3 = u2 * u;
        const float h00 = u3 * 2.f - u2 * 3.f + 1.f, h10 = u3 - 2.f * u2 + u, h01 = -2.f * u3 + u2 * 3.f, h11 = u3 - u2;
        const float v = h00 * y[size_t(seg)] + h10 * hh * m[size_t(seg)] + h01 * y[size_t(seg + 1)] + h11 * hh * m[size_t(seg + 1)];
        out[size_t(j)] = jmath::max(0.f, jmath::min(1.f, v));
    }
    return out;
}

inline std::vector<float> samples(const std::string& points, const std::array<float, 7>& m, int n) {
    return lut(stored(applyModifiers(parse(points), m)), n);
}

} // namespace WhyV2Curve

/// WhyV2Stats.State: what the V2 nodes hand on (PostPipeline.whyV2)
struct WhyV2State {
    WhyV2Ae::Params params;
    std::optional<WhyV2Ae::Result> ae;
    Texture clean;  // the clean linear RGB (RGBA16F, the output grid)
    Texture fused;  // the fused display luma (R16F, level 0 of the pyramid)
    int fusedScale = 1;
    void close() {
        if (clean) clean->close();
        if (fused) fused->close();
        clean = fused = nullptr;
    }
};

} // namespace scam_post
