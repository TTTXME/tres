// The frame rules of the ScamHybridBurst constructor one to one, for a burst whose frames came with their capture roles and
// measured exposures (the iPhone's: Capture/BayerBurst, the shot's sidecar ShotFacts): the base (the sharpest of the newest
// BASE_CANDIDATES N within BASE_WINDOW_NS, RawFrameQuality), the N frames newest first without the blurred ones (P331), the N limit,
// the bracketed frames (ROLE_BRACKETED, only above x1.2 of the base, else N within normalBand), the ultrashort frames (the one
// closest to base / bento_factor and, with bento_frames 2, more within x1.3; ROLE_ULTRASHORT), each frame's exposure as its
// exposure x ISO over the base's (dropped outside x1/512 .. x512), its orderMs to the newest N and its noise (noiseFor).
// What has no iPhone counterpart is left out and named: ZSL ring frames (the «Движение» moment), DCG / LOFIC pairs, FULLRES series,
// mosaic streams, binned frames, the noise model profiles (pref_noise_model_profile_key, not ported: its files are Android's).
#pragma once

#include "GLCore.hpp"
#include "Settings.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <limits>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam_post {

namespace HybridSelect {

constexpr int ROLE_NORMAL = 1, ROLE_BRACKETED = 3, ROLE_ULTRASHORT = 5;
constexpr long long BASE_WINDOW_NS = 205000000LL;
constexpr int BASE_CANDIDATES = 4;
constexpr int WORKER_MAX_FRAMES = 73, BURST_MAX_FRAMES = 128;
constexpr long long CLASSIC_INPUT_PIXELS = 16000000LL, HUGE_INPUT_PIXELS = 100000000LL;
constexpr double FIXED_BYTES_PER_PIXEL = 40, HUGE_FIXED_BYTES_PER_PIXEL = 20;
constexpr double STRIP_BYTES_PER_COLUMN = 3800, FIELD_BYTES_PER_PIXEL = 0.15, MEMORY_SHARE = 0.6;
constexpr int MIN_NORMALS = 2, HUGE_MIN_NORMALS = 4;
constexpr float SHARP_SELECT = 0.75f;
constexpr int SHARP_KEEP = 8;
constexpr float SHARP_MAX_SHARE = 1.f / 3.f;

/// ImageFrame.CaptureRole of a captured frame (the capture's plan, checked against its measured exposure: HybridPlan.classify)
enum class CaptureRole { Normal, Long, ExtraShort };

/// A captured frame as ImageFrame holds it for the merge
struct Frame {
    int index = 0;             // its plane in the transport (capture order)
    int number = 0;            // ImageFrame.number (log)
    CaptureRole role = CaptureRole::Normal;
    long long exposureNs = 0;  // measuredExposure
    int iso = 0;               // measuredIso
    long long timestampNs = 0; // timestamp
    float noiseSlope = NAN, noiseOffset = NAN; // the frame's own noise profile (Camera2's per frame; the iPhone's DNG profile at its ISO)
    float sharpness = NAN;     // RawFrameQuality.score, NaN until measured
};

/// One frame of the burst the worker gets (the NCH frame table)
struct Entry {
    int index = 0, number = 0;
    uint32_t role = ROLE_NORMAL;
    float exposure = 1.f;
    uint32_t iso = 0;
    float slope = 0.f, offset = 0.f, orderMs = 0.f;
};

struct Result {
    std::vector<Entry> frames;
    int base = -1;                 // index of the base frame
    float sceneEv = NAN;           // the base frame's EV100 (usSceneEv)
    long long baseExposureNs = 0;
    int baseIso = 0;
    int normals = 0, bracketed = 0, ultrashort = 0;
    int esCaptured = 0, longCaptured = 0;
    std::string noiseSource = "Camera2 SENSOR_NOISE_PROFILE";
    std::vector<std::string> log;
};

/// RawFrameQuality.score (block 1): edge energy above the gradients' noise floor over the mean signal, NaN when unmeasurable
inline double score(const uint16_t* raw, int width, int height) {
    if (!raw || width < 32 || height < 32) return NAN;
    const int period = 2;
    std::vector<double> gradients(64 * 48);
    int count = 0;
    double signal = 0;
    for (int gy = 0; gy < 48; gy++) for (int gx = 0; gx < 64; gx++) {
        const int x = (width / 10 + gx * (width * 4 / 5) / 64) / period * period + (gx % 2);
        const int y = (height / 10 + gy * (height * 4 / 5) / 48) / period * period + (gy % 2);
        if (x < period || y < period || x + period >= width || y + period >= height) continue;
        const size_t pos = size_t(y) * width + x;
        const int left = raw[pos - period], right = raw[pos + period];
        const int up = raw[pos - size_t(period) * width], down = raw[pos + size_t(period) * width];
        gradients[count++] = std::hypot(double(right - left), double(down - up));
        signal += (left + right + up + down) * 0.25;
    }
    if (count < 64 || signal <= 0) return NAN;
    std::sort(gradients.begin(), gradients.begin() + count);
    const double floor = gradients[count / 2] * 2.5;
    double edges = 0;
    for (int i = count * 4 / 5; i < count; i++) edges += std::max(0.0, gradients[i] - floor);
    return edges / (count - count * 4 / 5) / (signal / count);
}

/// ScamHybridBurst.sceneEv100
inline float sceneEv100(long long exposureNs, int iso, float aperture) {
    if (exposureNs <= 0 || iso <= 0 || !(aperture > 0.f) || !std::isfinite(aperture)) return NAN;
    const double t = exposureNs * 1e-9, n = aperture;
    return float(std::log(n * n / t / (iso / 100.0)) / std::log(2.0));
}

inline float medianFinite(const std::vector<float>& v) {
    std::vector<float> f;
    for (float x : v) if (std::isfinite(x)) f.push_back(x);
    if (f.size() < 4) return NAN;
    std::sort(f.begin(), f.end());
    const size_t n = f.size();
    return (n & 1) == 1 ? f[n / 2] : 0.5f * (f[n / 2 - 1] + f[n / 2]);
}

/// ScamHybridBurst.blurredDonors (P331)
inline std::vector<int> blurredDonors(const std::vector<float>& scores, int baseIndex, float ratio, int keep, float maxShare) {
    std::vector<int> out;
    const float median = medianFinite(scores);
    if (!(ratio > 0.f) || !(median > 0.f)) return out;
    const float limit = ratio * median;
    std::vector<int> soft;
    for (int i = 0; i < int(scores.size()); i++) if (i != baseIndex && std::isfinite(scores[i]) && scores[i] < limit) soft.push_back(i);
    std::stable_sort(soft.begin(), soft.end(), [&](int a, int b) { return scores[a] < scores[b]; });
    const int donors = int(scores.size()) - (baseIndex >= 0 ? 1 : 0);
    const int most = std::min(int(std::floor(maxShare * donors)), int(scores.size()) - keep);
    for (int i = 0; i < int(soft.size()) && i < most; i++) out.push_back(soft[i]);
    return out;
}

/// MotionPolicy.blurredDonors («Движение» with sharp priority)
inline std::vector<int> motionBlurredDonors(const std::vector<float>& scores, int base, float ratio) {
    std::vector<int> out;
    if (!(ratio > 0) || base < 0 || base >= int(scores.size())) return out;
    std::vector<float> positive;
    for (float s : scores) if (std::isfinite(s) && s > 0) positive.push_back(s);
    if (positive.empty()) return out;
    std::sort(positive.begin(), positive.end());
    const size_t count = positive.size();
    const float median = (count & 1) == 1 ? positive[count / 2] : (positive[count / 2 - 1] + positive[count / 2]) * 0.5f;
    const float reference = std::isfinite(scores[base]) ? std::max(median, scores[base]) : median;
    for (int i = 0; i < int(scores.size()); i++)
        if (i != base && std::isfinite(scores[i]) && scores[i] < ratio * reference) out.push_back(i);
    return out;
}

inline bool normalBand(double ratio) { return ratio >= std::pow(2.0, -0.5) && ratio <= 1.035; }

inline double perFrameBytes(int w, int h, int mosaicBlock) {
    const double px = double(w) * h;
    double bytes = STRIP_BYTES_PER_COLUMN * w + FIELD_BYTES_PER_PIXEL * px;
    if (mosaicBlock >= 2) bytes += 2.0 * px * (1 + 1.0 / (double(mosaicBlock) * mosaicBlock));
    return bytes;
}

inline int normalLimit(int series, int workerLimit, int budget, int minNormals, int reservedExtras, int extras) {
    if (series > 0) return std::numeric_limits<int>::max();
    if (budget == std::numeric_limits<int>::max()) return workerLimit;
    return std::min(workerLimit, std::max(minNormals, budget - std::min(reservedExtras, extras)));
}

inline int minNormals(int w, int h) { return (long long)w * h > HUGE_INPUT_PIXELS ? HUGE_MIN_NORMALS : MIN_NORMALS; }

inline int memoryFrameBudget(int w, int h, int mosaicBlock, int grid, long long availableBytes) {
    const long long px = (long long)w * h;
    if (px <= CLASSIC_INPUT_PIXELS || availableBytes <= 0) return std::numeric_limits<int>::max();
    const double fixed = px > HUGE_INPUT_PIXELS ? HUGE_FIXED_BYTES_PER_PIXEL : FIXED_BYTES_PER_PIXEL;
    const double free = MEMORY_SHARE * availableBytes - fixed * double(px) * grid * grid;
    return int(std::max(0.0, std::min(double(std::numeric_limits<int>::max() - 1), std::floor(free / perFrameBytes(w, h, mosaicBlock)))));
}

/// HybridPlan.bentoFrames / ultrashortFactor as the merge reads them
inline int bentoFrames(const Settings& s) { return int(std::max(1L, std::min(3L, Settings::javaRound(s.hybridValue("bento_frames", 2.f))))); }
inline double ultrashortFactor(const Settings& s) { return std::max(2.0, std::min(16.0, double(s.hybridValue("bento_factor", 8.f)))); }

inline bool validNoise(float slope, float offset) { return std::isfinite(slope) && slope > 0 && std::isfinite(offset) && offset >= 0; }

/// The ScamHybridBurst constructor's frame rules. sharpness(index): RawFrameQuality.score of that frame (measured on demand, as
/// computeSharpness); aperture: Parameters.aperture (usSceneEv); grid: 2 when the merge runs on a Sabre grid (the memory budget);
/// availableBytes: the memory available before the merge (-1 unknown; read only above 16 MP).
inline Result select(std::vector<Frame> source, int width, int height, const Settings& s, const std::function<float(int)>& sharpness,
                     float aperture, int grid = 1, long long availableBytes = -1) {
    Result out;
    auto& log = out.log;
    if (source.empty() || int(source.size()) > BURST_MAX_FRAMES)
        throw std::runtime_error("SHDR: needs 1–" + std::to_string(BURST_MAX_FRAMES) + " frames");
    std::vector<Frame> usable;
    std::set<long long> stamps;
    for (const Frame& f : source) {
        const char* problem = f.timestampNs <= 0 || !stamps.insert(f.timestampNs).second ? "repeated or missing timestamp"
                              : f.iso <= 0 || f.exposureNs <= 0 ? "no measured exposure" : nullptr;
        if (problem) {
            log.push_back(std::string("hybrid: frame dropped (") + problem + "): frame=" + std::to_string(f.number));
            continue;
        }
        usable.push_back(f);
    }
    if (usable.empty()) throw std::runtime_error("SHDR: no usable RAW frame (" + std::to_string(source.size()) + " received)");
    auto product = [](const Frame& f) { return double(f.exposureNs) * f.iso; };
    std::vector<Frame> normal, bracketed, shorts;
    for (const Frame& f : usable) {
        switch (f.role) {
        case CaptureRole::Normal: normal.push_back(f); break;
        case CaptureRole::Long: bracketed.push_back(f); break;
        default: shorts.push_back(f); break;
        }
    }
    if (normal.empty()) {
        // P27: no N frame arrived: the shortest bracketed frame is the base, else the longest short one
        Frame promoted;
        if (!bracketed.empty()) {
            auto it = std::min_element(bracketed.begin(), bracketed.end(), [&](const Frame& a, const Frame& b) { return product(a) < product(b); });
            promoted = *it;
            bracketed.erase(it);
        } else {
            auto it = std::max_element(shorts.begin(), shorts.end(), [&](const Frame& a, const Frame& b) { return product(a) < product(b); });
            promoted = *it;
            shorts.erase(it);
        }
        normal.push_back(promoted);
        log.push_back("hybrid: no N frame arrived, frame " + std::to_string(promoted.number) + " is the base");
    }
    const bool motion = s.isMotionMode();
    if (motion) {
        bracketed.clear();
        shorts.clear(); // P641d: only an overflow frame (no iPhone counterpart) stays in «Движение»
    }
    std::stable_sort(normal.begin(), normal.end(), [](const Frame& a, const Frame& b) { return -a.timestampNs < -b.timestampNs; });
    const int reservedExtras = bentoFrames(s) + 5;
    const int workerLimit = WORKER_MAX_FRAMES - reservedExtras;
    const long long available = (long long)width * height > CLASSIC_INPUT_PIXELS ? availableBytes : -1;
    const int budget = memoryFrameBudget(width, height, 1, grid, available);
    const int limit = normalLimit(0, workerLimit, budget, minNormals(width, height), reservedExtras, int(bracketed.size() + shorts.size()));
    const std::string limitReason = limit < workerLimit ? "memory budget" : "worker limit";
    if (budget != std::numeric_limits<int>::max())
        log.push_back("hybrid memory budget " + std::to_string(width) + "x" + std::to_string(height) + ": " + std::to_string(budget)
                      + " frames (available " + std::to_string(available >> 20) + " MB) -> at most " + std::to_string(limit) + " N");
    auto sharp = [&](Frame& f) {
        if (std::isnan(f.sharpness)) f.sharpness = sharpness(f.index);
        return f.sharpness;
    };
    // the base: the sharpest of the newest candidates within the SCAM time window (no ZSL ring on the iPhone: no «Движение» moment)
    const long long newest = normal[0].timestampNs;
    const long long anchor = normal[0].timestampNs;
    int best = 0;
    double bestScore = -1;
    for (int i = 0; i < std::min(BASE_CANDIDATES, int(normal.size())); i++) {
        Frame& f = normal[size_t(i)];
        if (anchor - f.timestampNs > BASE_WINDOW_NS) break;
        const float v = sharp(f);
        const double sc = std::isnan(v) ? 0 : v;
        if (sc > bestScore) { bestScore = sc; best = i; }
    }
    const Frame base = normal[size_t(best)];
    out.base = base.index;
    out.baseExposureNs = base.exposureNs;
    out.baseIso = base.iso;
    out.sceneEv = sceneEv100(base.exposureNs, std::max(1, base.iso), aperture);
    const double ref = product(base);
    // noiseFor
    auto noiseFor = [&](const Frame& frame) -> std::pair<float, float> {
        float slope = frame.noiseSlope, offset = frame.noiseOffset;
        if (!validNoise(slope, offset)) {
            std::string fallback;
            for (const Entry& e : out.frames) {
                const Frame* g = nullptr;
                for (const Frame& u : usable) if (u.index == e.index) g = &u;
                if (!g || g->index == frame.index || !validNoise(g->noiseSlope, g->noiseOffset) || g->iso <= 0) continue;
                const float k = float(frame.iso) / g->iso;
                slope = g->noiseSlope * k;
                offset = g->noiseOffset * k * k;
                fallback = "profile of frame " + std::to_string(g->number) + " scaled to ISO " + std::to_string(frame.iso);
                break;
            }
            if (fallback.empty()) {
                // the built-in sensor model (NoiseModelProfile.LGV50_IMX363_0) is Android's: not on the iPhone, clamped as Java clamps
                fallback = "no profile to scale (the built-in sensor model is not ported)";
            }
            if (!std::isfinite(slope) || slope <= 0) slope = 1e-6f;
            if (!std::isfinite(offset) || offset < 0) offset = 0.f;
            out.noiseSource = "no valid noise profile for frame " + std::to_string(frame.number) + ": " + fallback;
            log.push_back("hybrid: " + out.noiseSource);
        }
        slope *= s.hybridValue("noise_photon", 1.f);
        offset *= s.hybridValue("noise_readout", 1.f);
        if (!std::isfinite(slope) || slope <= 0) slope = 1e-6f;
        if (!std::isfinite(offset) || offset < 0) offset = 0.f;
        return {slope, offset};
    };
    auto add = [&](const Frame& f, uint32_t role) {
        const double ratio = product(f) / ref;
        if (!(ratio > 1.0 / 512) || !(ratio < 512)) {
            if (f.index == base.index) throw std::runtime_error("SHDR: base frame exposure out of range frame=" + std::to_string(f.number));
            log.push_back("hybrid: frame " + std::to_string(f.number) + " dropped, exposure x" + std::to_string(ratio)
                          + " of the base is outside the merge range");
            return false;
        }
        Entry e;
        e.index = f.index;
        e.number = f.number;
        e.role = role;
        e.exposure = float(ratio);
        e.iso = uint32_t(f.iso);
        const auto n = noiseFor(f);
        e.slope = n.first;
        e.offset = n.second;
        e.orderMs = float(double(f.timestampNs - newest) / 1e6);
        out.frames.push_back(e);
        return true;
    };
    add(base, ROLE_NORMAL);
    // P331: the blurred N donors
    std::set<int> blurred;
    {
        const float ratio = s.hybridValue("sharp_select", SHARP_SELECT);
        const bool sharpMotion = motion && s.scamDevSwitch("motion_sharp_priority", true);
        if (ratio > 0.f && int(normal.size()) > (sharpMotion ? 1 : SHARP_KEEP)) {
            std::vector<float> scores(normal.size());
            int baseIndex = -1;
            for (size_t i = 0; i < normal.size(); i++) {
                scores[i] = sharp(normal[i]);
                if (normal[i].index == base.index) baseIndex = int(i);
            }
            const std::vector<int> drop = sharpMotion ? motionBlurredDonors(scores, baseIndex, std::max(0.85f, std::min(ratio, 1.f)))
                                                      : blurredDonors(scores, baseIndex, std::min(ratio, 1.f), SHARP_KEEP, SHARP_MAX_SHARE);
            if (!drop.empty()) {
                std::string line = "hybrid sharpness: median " + javaFloatString(medianFinite(scores)) + ", below x" + javaFloatString(ratio) + " left out:";
                for (int i : drop) {
                    blurred.insert(normal[size_t(i)].index);
                    line += " " + std::to_string(normal[size_t(i)].number) + "=" + javaFloatString(scores[size_t(i)]);
                }
                log.push_back(line);
            }
        }
    }
    int normals = 1;
    for (const Frame& f : normal) {
        if (f.index == base.index || blurred.count(f.index)) continue;
        if (normals >= limit) {
            log.push_back("hybrid: " + std::to_string(int(normal.size()) - normals) + " oldest N frames dropped, " + limitReason + " "
                          + std::to_string(limit));
            break;
        }
        if (add(f, ROLE_NORMAL)) normals++;
    }
    // bracketed frames: those really longer than the base; one that came back at the N exposure is an N frame (P27)
    std::stable_sort(bracketed.begin(), bracketed.end(), [&](const Frame& a, const Frame& b) { return product(a) < product(b); });
    int bracketedCount = 0;
    for (const Frame& f : bracketed) {
        const double r = product(f) / ref;
        if (r < 1.2) {
            if (normalBand(r) && normals < limit && add(f, ROLE_NORMAL)) {
                normals++;
                log.push_back("hybrid: bracketed frame " + std::to_string(f.number) + " at x" + javaFloatString(float(r)) + " of the base, merged as N");
            } else log.push_back("hybrid: bracketed frame " + std::to_string(f.number) + " at x" + javaFloatString(float(r)) + " is not longer than the base, dropped");
            continue;
        }
        if (add(f, ROLE_BRACKETED)) bracketedCount++;
    }
    // ultrashort: the short frame closest to base / factor and, with bento_frames 2, more at the same exposure (within x1.3)
    int ultrashort = 0;
    const int shortsCaptured = int(shorts.size());
    for (auto it = shorts.begin(); it != shorts.end();) {
        const Frame f = *it;
        const double r = product(f) / ref;
        if (r < 0.75) { ++it; continue; }
        it = shorts.erase(it);
        if (normalBand(r) && normals < limit && add(f, ROLE_NORMAL)) {
            normals++;
            log.push_back("hybrid: short frame " + std::to_string(f.number) + " at x" + javaFloatString(float(r)) + " of the base, merged as N");
        } else log.push_back("hybrid: short frame " + std::to_string(f.number) + " at x" + javaFloatString(float(r)) + " dropped");
    }
    if (!shorts.empty()) {
        const double target = ref / ultrashortFactor(s);
        std::stable_sort(shorts.begin(), shorts.end(), [&](const Frame& a, const Frame& b) {
            return std::fabs(std::log(product(a) / target)) < std::fabs(std::log(product(b) / target));
        });
        const double first = product(shorts[0]);
        const int want = bentoFrames(s);
        for (const Frame& us : shorts) {
            if (ultrashort >= want) break;
            if (product(us) / ref >= 0.75 || std::fabs(std::log(product(us) / first)) > std::log(1.3)) continue;
            if (add(us, ROLE_ULTRASHORT)) ultrashort++;
        }
    }
    out.normals = normals;
    out.bracketed = bracketedCount;
    out.ultrashort = ultrashort;
    out.esCaptured = shortsCaptured;
    out.longCaptured = int(bracketed.size());
    char buf[256];
    std::snprintf(buf, sizeof buf, " base slope=%s offset=%s EV100=%s (f/%s)", javaFloatString(out.frames[0].slope).c_str(),
                  javaFloatString(out.frames[0].offset).c_str(), javaFloatString(out.sceneEv).c_str(), javaFloatString(aperture).c_str());
    log.push_back("hybrid burst: frames=" + std::to_string(out.frames.size()) + " base=" + std::to_string(base.number) + " sharpness="
                  + javaFloatString(base.sharpness) + " normals=" + std::to_string(normals) + " bracketed=" + std::to_string(bracketedCount)
                  + " ultrashort=" + std::to_string(ultrashort) + " noise=" + out.noiseSource + buf);
    return out;
}

} // namespace HybridSelect

} // namespace scam_post
