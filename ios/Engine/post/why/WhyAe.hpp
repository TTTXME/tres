// WhyAe.java one to one: the Smart-HDR statistics' finish (exposure, ceiling, fusion strengths, AgX look) of the WhyCam 1.23
// photo tone. The per-pixel statistics come from the GPU (WhyAeGpu); the CPU walk is the Java's fallback only.
#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace scam_post {
namespace WhyAe {

static const float LOOKS[4][7] = {{2.7f, 1.35f, 1.6f, -10.0f, 3.5f, 1.0f, 0.25f},
                                  {2.8f, 1.5f, 1.5f, -7.5f, 3.5f, 1.2f, 0.0f},
                                  {2.1f, 1.4f, 1.4f, -8.0f, 4.0f, 0.95f, 0.0f},
                                  {2.4f, 1.35f, 1.4f, -7.5f, 3.5f, 1.05f, 0.0f}};

struct AeSettings {
    float aeTarget = 0.15f, maxBoost = 5.0f, minLimit = 0.5f;
    float hlOverflow = 2.0f, hlBlend = 0.33f;
    float nightThresh = 100.f, nightDim = 1.0f;
    double noiseS = 0.0, noiseO = 0.0;
    float noiseMax = 0.05f;
    float motionGain = 1.f;
    int metering = 0;
    float brightThresh = 0.8f, darkThresh = 15.f, darkPixelThresh = 10.f, hlBoost = 1.0f, contrastBoost = 0.5f;
    float shadowStr = 2.0f, highlightStr = 2.0f;
    float evWhite = NAN, nightEvHi = 6.5f, nightEvLo = 5.0f, nightAeTarget = 0.13f, nightShadowStr = 1.5f;
    bool efEnabled = true;
    bool portraitFace = false;
    int look = 4;
    float slope = 2.7f, sp = 1.35f, tp = 1.6f, minEv = -8.5f, maxEv = 3.5f, sat = 1.0f, ev = 0.30f;
    float ccmSat = 1.f;
    bool bentoHeadroom = false;
    float hrMix = 0.5f;
    bool v207 = false;
    bool dcg = false;
    float dcgIsoScale = 1.f;
};

struct Result {
    float ae = 1.f, clip = 1.f, deficit = 1.f, effS = 0, effH = 0, nf = 1.f;
    float p10 = 0, p50 = 0, p98 = 0, p995 = 0, mean = 0, geo = 0, gmax = 0, ovArea = 0, flat = 0, spread = 0, r = 0, m = 0, realPeak = 0;
    float contrastC = 0;
    float p98x = 0, p995x = 0;
    float maxGainByNoise = 0.f;
    float hr = 0, hrPull = 0, nightW = 0;
    bool bento = false, portraitFace = false;
    float motionGain = 1.f;
    float slope = 0, sp = 0, tp = 0, minEv = 0, maxEv = 0, sat = 0, ev = 0;
    int look = 0;

    std::string describe() const {
        char buf[1024];
        std::string s;
        std::snprintf(buf, sizeof buf, "clip=%.3f gmax=%.3f ae=%.4f geo=%.5f mean=%.5f p10=%.5f p50=%.5f p98=%.5f p995=%.5f p98x=%.3f p995x=%.3f nf=%.3f deficit=%.4f",
                      double(clip), double(gmax), double(ae), double(geo), double(mean), double(p10), double(p50), double(p98), double(p995),
                      double(p98x), double(p995x), double(nf), double(deficit));
        s = buf;
        if (maxGainByNoise > 0.f) { std::snprintf(buf, sizeof buf, " gainClamp=%.3f", double(maxGainByNoise)); s += buf; }
        if (motionGain > 1.001f) { std::snprintf(buf, sizeof buf, " motionGain=%.3f", double(motionGain)); s += buf; }
        std::snprintf(buf, sizeof buf, " effS=%.4f effH=%.4f flat=%.3f spread=%.4f ov=%.4f bento=%d agx(slope=%.3f sp=%.2f tp=%.2f ev=[%.2f,%.2f]+%.2f sat=%.2f look=%d)",
                      double(effS), double(effH), double(flat), double(spread), double(ovArea), bento ? 1 : 0, double(slope), double(sp), double(tp),
                      double(minEv), double(maxEv), double(ev), double(sat), look);
        s += buf;
        if (hr > 0.f) { std::snprintf(buf, sizeof buf, " hr=%.2f hrPull=%.2f realPeak=%.3f", double(hr), double(hrPull), double(realPeak)); s += buf; }
        if (nightW > 0.f) { std::snprintf(buf, sizeof buf, " night=%.2f", double(nightW)); s += buf; }
        return s;
    }
};

constexpr int BINS = 4096;
constexpr int TAIL_BINS = 48, TAIL_PER_OCTAVE = 12;

struct Stats {
    float gmax = -1000.f;
    std::vector<double> hy = std::vector<double>(BINS), hm = std::vector<double>(BINS), tail = std::vector<double>(TAIL_BINS);
    double tot = 0, sumY = 0, sumLog = 0;
};

inline float intBitsToFloat(int32_t b) { float f; std::memcpy(&f, &b, 4); return f; }

inline Stats fromGpu(int32_t maxKey, const std::vector<int32_t>& hy, const std::vector<int32_t>& hm, const std::vector<int32_t>& sums,
                     const std::vector<int32_t>* tail) {
    Stats st;
    if (tail) for (size_t i = 0; i < std::min(tail->size(), size_t(TAIL_BINS)); i++) st.tail[i] = double(uint32_t((*tail)[i]));
    if (maxKey != 0) st.gmax = std::max(intBitsToFloat(maxKey < 0 ? maxKey & 0x7fffffff : ~maxKey), -1000.f);
    double tot = 0;
    for (int i = 0; i < BINS; i++) {
        st.hy[size_t(i)] = double(uint32_t(hy[size_t(i)]));
        st.hm[size_t(i)] = double(uint32_t(hm[size_t(i)]));
        tot += st.hy[size_t(i)];
    }
    st.tot = tot;
    double y = 0, l = 0;
    for (size_t i = 0; i + 1 < sums.size(); i += 2) {
        y += double(intBitsToFloat(sums[i]));
        l += double(intBitsToFloat(sums[i + 1]));
    }
    st.sumY = y;
    st.sumLog = l;
    return st;
}

inline double log2d(double v) { return std::log(v) / std::log(2.0); }

inline double beyond(double q, double below, double clip, double tot, const std::vector<double>& tail) {
    double above = 0;
    for (double v : tail) above += v;
    const double need = q * tot - (tot - above);
    if (above <= 0 || need <= 0) return below;
    double acc = 0;
    for (size_t k = 0; k < tail.size(); k++) {
        acc += tail[k];
        if (acc >= need) return clip * std::pow(2.0, double(k + 1) / double(TAIL_PER_OCTAVE));
    }
    return clip * std::pow(2.0, double(tail.size()) / double(TAIL_PER_OCTAVE));
}
constexpr double HR_AREA_FROM = 0.003, HR_AREA_TO = 0.008;
inline double smooth(double e0, double e1, double v) {
    const double t = std::max(0.0, std::min(1.0, (v - e0) / (e1 - e0)));
    return t * t * (3 - 2 * t);
}
inline double clip(float gmax) { return gmax > 1.05f ? std::min(double(gmax), 4.0) : 1.0; }
inline float invClip(float gmax) { return float(1.0 / clip(gmax)); }
inline double gainClamp(double noiseS, double noiseO, double noiseMax) {
    const double sigmaMid = std::sqrt(std::max(0.5 * noiseS + noiseO, 1e-10));
    return std::max(noiseMax / sigmaMid, 1.0);
}
inline double nightIso(int iso, const AeSettings& s) {
    const double isof = std::max(iso, 1);
    return s.dcg && s.dcgIsoScale > 1.f && std::isfinite(s.dcgIsoScale) ? std::max(isof / double(s.dcgIsoScale), 1.0) : isof;
}
inline bool keepsCapturedExposure(float hr, bool dcg) { return hr > 0.f || dcg; }
inline double nightShare(const AeSettings& s, double p50) {
    if (!std::isfinite(s.evWhite) || !(p50 > 0) || !(s.nightEvHi > s.nightEvLo)) return 0.0;
    const double ev = double(s.evWhite) + log2d(p50);
    const double t = std::min(std::max((double(s.nightEvHi) - ev) / double(s.nightEvHi - s.nightEvLo), 0.0), 1.0);
    return t * t * (3 - 2 * t);
}

inline Result finish(const Stats& st, const AeSettings& s, int iso, int maxIso) {
    const float gmax = st.gmax;
    const double clipv = clip(gmax);
    const auto& hy = st.hy;
    const auto& hm = st.hm;
    const double tot = std::max(st.tot, 1.0), sumY = st.sumY, sumLog = st.sumLog;
    Result r;
    r.gmax = gmax;
    r.clip = float(clipv);
    r.bento = gmax > 1.05f;
    r.hr = s.bentoHeadroom && double(gmax) > clipv && std::isfinite(gmax) ? float(std::min(double(gmax), clipv * 16.0)) : 0.f;
    r.mean = float(sumY / tot);
    r.geo = float(std::exp(sumLog / tot));
    const double step = clipv / 4096.0;
    double p10 = 0, p50 = 0, p98 = -1, p995 = -1, p999 = -1;
    bool f10 = false, f50 = false;
    double cy = 0, cm = 0;
    for (int i = 0; i < 4096; i++) {
        cy += hy[size_t(i)];
        cm += hm[size_t(i)];
        if (!f10 && cy / tot >= 0.1) { p10 = i * step; f10 = true; }
        if (!f50 && cy / tot >= 0.5) { p50 = i * step; f50 = true; }
        if (p98 < 0 && cm / tot >= 0.98) p98 = i * step;
        if (p995 < 0 && cm / tot >= 0.995) p995 = i * step;
        if (p999 < 0 && cm / tot >= 0.999) p999 = i * step;
    }
    if (p98 < 0) p98 = clipv * 0.99975586;
    if (p995 < 0) p995 = p98;
    if (p999 < 0) p999 = p995;
    if (p98 == clipv) p98 = clipv * 0.99975586;
    r.p10 = float(p10); r.p50 = float(p50); r.p98 = float(p98); r.p995 = float(p995);
    r.nightW = float(nightShare(s, p50));
    const double aeTarget = double(s.aeTarget) + double(s.nightAeTarget - s.aeTarget) * double(r.nightW);
    const double shadowStr = double(s.shadowStr) + double(s.nightShadowStr - s.shadowStr) * double(r.nightW);
    const double p98x = beyond(0.98, p98, clipv, tot, st.tail), p995x = beyond(0.995, p995, clipv, tot, st.tail);
    r.p98x = float(p98x); r.p995x = float(p995x);
    const double meter = r.geo;
    const double fp = 0.0;
    double nf = 1.0;
    if (iso >= 1) {
        const double thr = std::max(double(s.nightThresh), 1.0), isof = nightIso(iso, s);
        if (isof > thr) {
            const double mx = std::max(double(maxIso), thr + 1);
            const double t = std::min(std::max(std::max(log2d(isof / thr), 0.0) / std::max(log2d(mx / thr), 0.001), 0.0), 1.0);
            nf = std::max(1 - t * t, 0.0);
        }
    }
    r.nf = float(nf);
    const double mg = std::max(1.0, double(s.motionGain));
    r.motionGain = float(mg);
    const double mxB = double(s.maxBoost) * mg, mnL = s.minLimit;
    double deficit = 1.0, ae;
    const bool hasNoiseModel = s.noiseS > 0 || s.noiseO > 0;
    if (meter > 1e-4) {
        const double target = hasNoiseModel ? aeTarget : (0.2857143 + 0.7142857 * std::pow(nf, 0.6)) * aeTarget;
        ae = target / meter;
        const double nd = s.nightDim;
        if (nd > 0.01 && std::fabs(nd - 1) > 0.001) ae = std::pow(ae, nd);
        double aeC = std::max(std::min(ae, mxB), mnL);
        if (keepsCapturedExposure(r.hr, s.dcg) && aeC < 1.0) aeC = std::min(1.0, mxB);
        double aeF = aeC;
        if (p995 > 0.01 && p995 * aeC > double(s.hlOverflow)) {
            const double lim = double(s.hlOverflow) / p995;
            const double blend = double(s.hlBlend) + double(1 - s.hlBlend) * fp;
            aeF = lim + (aeC - lim) * blend;
        }
        aeF = std::max(aeF, mnL);
        if (aeF < aeC && aeF > 0.001 && aeC > 0.001) deficit = aeC / aeF;
        ae = std::max(std::min(mxB, aeF), mnL);
        if (hasNoiseModel) {
            const double maxGainByNoise = gainClamp(s.noiseS, s.noiseO, s.noiseMax) * mg;
            r.maxGainByNoise = float(maxGainByNoise);
            if (ae > maxGainByNoise) ae = maxGainByNoise;
        }
        if (ae > double(9.0f) * mg) ae = double(9.0f) * mg;
    } else {
        ae = std::max(std::min(mxB, 1.0), mnL);
        if (ae > 9.0) ae = 9.0;
    }
    r.ae = float(ae);
    r.deficit = float(deficit);
    const double bt = s.brightThresh;
    const int j = int(std::min(std::max(bt / std::max(ae, 0.001) / clipv, 0.0), 1.0) * 4095.99);
    double ov = 0;
    for (int i = j; i < 4096; i++) ov += hm[size_t(i)];
    r.ovArea = float(ov / tot);
    r.look = s.look;
    r.slope = s.slope; r.sp = s.sp; r.tp = s.tp; r.minEv = s.minEv; r.maxEv = s.maxEv; r.sat = s.sat; r.ev = s.ev;
    r.effS = float(shadowStr);
    r.effH = s.highlightStr;
    if (s.efEnabled) {
        const double a = ae;
        const double sp10 = std::sqrt(std::max(p10 * a, 0.0)), sp50 = std::sqrt(std::max(p50 * a, 0.0));
        const double sp98 = std::sqrt(std::min(std::max(p98 * a, 0.0), a));
        const double spread = sp98 - sp10;
        const double rr = spread / std::max(double(s.darkThresh) * 0.15, 0.001);
        const double m = std::min(std::max((sp50 - 0.2) / 0.22, 0.0), 1.0);
        const double shadowF = (1 - 0.35 * m * m) * (rr + 0.5 * rr * rr) / (1 + 1.5 * rr);
        const double effS = std::max((s.v207 ? 1.0 : nf) * (shadowStr * shadowF + 0.5 * log2d(std::max(deficit, 1.0))), 0.0);
        const double e = std::min(std::max(p995x * a - bt, 0.0) / std::max(bt, 0.5), 0.5);
        double realPeak = a * (p98x * (1 - e) + p995x * e);
        double hlCap = s.highlightStr;
        if (r.hr > 0.f) {
            double beyondShare = 0;
            for (double v : st.tail) beyondShare += v;
            beyondShare /= tot;
            const double gate = smooth(HR_AREA_FROM, HR_AREA_TO, beyondShare);
            const double p999x = beyond(0.999, p999, clipv, tot, st.tail);
            const double top = a * p999x, mix = std::max(0.0, std::min(1.0, double(s.hrMix))) * gate;
            r.hrPull = float(mix);
            if (top > realPeak && realPeak > 1e-6) realPeak *= std::pow(top / realPeak, mix);
            hlCap = std::max(hlCap, log2d(double(r.hr) * a / std::max(bt, 1e-3)) / 1.5);
        }
        const double need = realPeak > bt ? log2d(realPeak / bt) / 1.5 : 0.0;
        double effH = std::min(need * std::max(double(s.hlBoost), 0.0), hlCap);
        if (s.v207) {
            const double f = r.ovArea, area = smooth(0.0, 1.0, std::min(std::max(20.0 * f, 0.0), 1.0));
            realPeak = a * p98x + 0.25 * area * std::min(a * std::max(p995x - p98x, 0.0), 1.5 * std::max(bt, 0.5));
            const double ex = log2d(std::max(realPeak, 1e-9) / (0.85 * std::max(bt, 1e-3))), knee = log2d(1.0 / 0.85);
            const double soft = ex <= 0.0 ? 0.0 : ex < knee ? ex * ex / (2.0 * knee) : ex - knee / 2.0;
            effH = std::min(double(s.highlightStr), 0.8333 * soft * std::max(double(s.hlBoost), 0.0)
                    * (1.0 + 0.35 * std::min(std::max(5.0 * f - 0.25, 0.0), 1.0)) * (0.2 + 0.8 * area));
        }
        const double q = std::max(1 - spread / std::max(double(s.darkPixelThresh) * 0.05, 0.001), 0.0);
        const double flat = s.portraitFace ? 0.0 : q * q * (3 - 2 * q);
        if (s.look >= 0 && s.look <= 3) {
            const float* l = LOOKS[s.look];
            r.slope = l[0]; r.sp = l[1]; r.tp = l[2]; r.minEv = l[3]; r.maxEv = l[4]; r.sat = l[5]; r.ev = l[6];
        }
        const double cb = flat * double(s.contrastBoost);
        if (cb > 1e-4) r.slope += float(cb);
        r.contrastC = float(cb);
        r.effS = float(effS);
        r.effH = float(effH);
        r.realPeak = float(realPeak);
        r.flat = float(flat);
        r.spread = float(spread);
        r.r = float(rr);
        r.m = float(m);
    }
    if (s.ccmSat != 1.f) r.sat *= s.ccmSat;
    r.portraitFace = s.portraitFace;
    return r;
}

inline float darkestExposure(const Result& r) { return float(std::pow(2.0, -1.5 * double(r.effH))); }
inline std::array<float, 4> exposures(const Result& r, float em0) {
    return {em0, 1.f, float(std::sqrt(double(em0))), float(std::min(std::pow(2.0, 1.5 * double(r.effS)), 8.0))};
}
inline std::array<float, 4> exposures(const Result& r) { return exposures(r, darkestExposure(r)); }

} // namespace WhyAe
} // namespace scam_post
