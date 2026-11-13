// The SHDR merge of an NCH burst as scam-neural-worker.cpp runs it for SCAM_HYBRID (the job's markers and tuning, the CRE out):
// shared by tools/hybrid_replay.cpp (Mesa / ScamGL on a Mac) and the app (Bridge/ScamMerge.mm, ScamGL on the iPhone's GPU).
// The result carries the worker's trailers (effective-frame map, clip flags, base highlight mask) and writeWorkerOutput lays
// them out as the worker writes its output file, which ScamNeuralClient reads.
#pragma once

#include "scam-capture.h"
#include "scam-hybrid.h"
#include "scam-stream-out.h"

#include <chrono>
#include <fstream>
#include <functional>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace scam_ios {

struct MergeResult {
    std::vector<float> rgb; // float32 RGB rows top-down, camera colours, base-frame units
    int width = 0, height = 0;
    double ms = 0;
    std::vector<uint16_t> mergedDng;
    std::vector<uint8_t> effMap, clipFlags; // uint8 per output pixel (the effective frames in 1/8, the clip bits)
    std::vector<uint16_t> hlMask;           // "SCHLMSK1" header + the base highlight mask at HALF of the sensor grid
    size_t sensorPixels = 0;
    bool rgbHalf = false;                   // job marker rgb-half: the RGB written as IEEE halves
};

inline bool jobMarker(const std::string& jobDir, const char* name) {
    struct stat st{};
    return stat((jobDir + "/" + name).c_str(), &st) == 0;
}

inline MergeResult mergeBurst(const std::string& jobDir, const std::string& burstPath,
                              const std::function<void(const std::string&)>& report) {
    const auto tuning = scam::loadHybridTuning(jobDir, report);
    scam::MappedHybridBurst burst(burstPath, false);
    if (!burst.hybrid) throw std::runtime_error("not an NCH v10-v12 hybrid burst");
    scam::HybridInput hin = burst.input;
    MergeResult out;
    out.sensorPixels = size_t(hin.w) * hin.h;
    out.rgbHalf = jobMarker(jobDir, "rgb-half");
    // the worker's rules: clip flags on the header's request (flag 4) or the job marker; no effective map with header flag 16
    const bool wantClipFlags = hin.clipFlags || jobMarker(jobDir, "clip-flags");
    const bool noEffMap = hin.noEffMap;
    if (noEffMap) report("HYBRID EFFECTIVE MAP: not made (header flag 16: the app reads none at this size)");
    scam::hybridStageDir() = "";
    if (jobMarker(jobDir, "hl-mask")) scam::hybridHighlightMaskOut() = &out.hlMask;
    // no CRE here (the vendor motion engine of the phone): its lazy start reports it unavailable, the merge aligns itself
    const bool creLazy = tuning.creLazy > 0 && tuning.gpuAlign == 1 && tuning.laGpu != 0 && tuning.localAlign > 0;
    scam::ScamAlignment alignment;
    if (creLazy) {
        alignment = [](scam::Burst&) -> std::array<scam::BackwardHomography, 7> { throw std::runtime_error("CRE unavailable"); };
        scam::hybridAlignmentEnsure() = [] { return false; };
    }
    report("HYBRID REPLAY: " + burstPath + " " + std::to_string(hin.w) + "x" + std::to_string(hin.h) + " frames="
           + std::to_string(hin.frames.size()) + " grid=" + std::to_string(hin.grid) + " mosaic=" + std::to_string(hin.mosaic));
    report(tuning.fastMerge > 0 ? "HYBRID ROUTE: «Быстрый» (the fast merge route; no other route takes this shot)"
                                : "HYBRID ROUTE: «Качество» (the SCAM merge; no other route takes this shot)");
    const auto started = std::chrono::steady_clock::now();
    struct Reset {
        ~Reset() { scam::hybridAlignmentEnsure() = nullptr; scam::hybridHighlightMaskOut() = nullptr; }
    } reset;
    out.rgb = scam::hybridReconstruct(hin, tuning, alignment, report, &out.mergedDng, noEffMap ? nullptr : &out.effMap, nullptr,
                                      wantClipFlags ? &out.clipFlags : nullptr);
    out.width = hin.w;
    out.height = hin.h;
    const size_t px = out.rgb.size() / 3;
    if (hin.outW > 0 && px != size_t(hin.outW) * hin.outH) {
        int g = 0;
        for (int k = 1; k <= 2; ++k) if (px == size_t(hin.w) * k * size_t(hin.h) * k) g = k;
        if (!g) throw std::runtime_error("Hybrid: a result of " + std::to_string(px) + " px is no grid of the burst");
        scam::hybridResampleResult(out.rgb, hin.w * g, hin.h * g, hin.outW, hin.outH, out.effMap.empty() ? nullptr : &out.effMap,
                                   out.clipFlags.empty() ? nullptr : &out.clipFlags);
        report("HYBRID SABRE OUTPUT: the " + std::to_string(hin.w * g) + "x" + std::to_string(hin.h * g) + " result resampled to "
               + std::to_string(hin.outW) + "x" + std::to_string(hin.outH));
        out.width = hin.outW;
        out.height = hin.outH;
    } else if (hin.outW > 0) {
        out.width = hin.outW;
        out.height = hin.outH;
    } else {
        for (int k = 1; k <= 2; ++k) if (px == size_t(hin.w) * k * size_t(hin.h) * k) { out.width = hin.w * k; out.height = hin.h * k; }
    }
    out.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    double sum = 0;
    float maximum = 0;
    for (float v : out.rgb) { sum += v; maximum = std::max(maximum, v); }
    report("SCAM RGB: mean=" + std::to_string(sum / double(std::max<size_t>(1, out.rgb.size()))) + " max=" + std::to_string(maximum));
    return out;
}

// The worker's output file: the RGB (halves with rgb-half), then its trailers in the worker's order and conditions.
inline std::vector<uint8_t> workerOutput(const MergeResult& r) {
    std::vector<uint8_t> bytes;
    auto put = [&](const void* data, size_t n) {
        const uint8_t* p = static_cast<const uint8_t*>(data);
        bytes.insert(bytes.end(), p, p + n);
    };
    if (r.rgbHalf) {
        std::vector<uint16_t> half(r.rgb.size());
        double s = 0;
        float m = 0;
        scam::halvesOf(r.rgb.data(), half.data(), r.rgb.size(), false, s, m);
        put(half.data(), half.size() * sizeof(uint16_t));
    } else {
        put(r.rgb.data(), r.rgb.size() * sizeof(float));
    }
    if (!r.mergedDng.empty()) put(r.mergedDng.data(), r.mergedDng.size() * sizeof(uint16_t));
    const bool noEffMap = r.effMap.empty();
    if (!r.effMap.empty() && r.effMap.size() * 3 == r.rgb.size()) put(r.effMap.data(), r.effMap.size());
    if (!r.clipFlags.empty() && (noEffMap ? r.clipFlags.size() * 3 == r.rgb.size() : r.clipFlags.size() == r.effMap.size()))
        put(r.clipFlags.data(), r.clipFlags.size());
    if (r.hlMask.size() > 8 && (r.hlMask.size() - 8) * 4 == r.sensorPixels) put(r.hlMask.data(), r.hlMask.size() * sizeof(uint16_t));
    return bytes;
}

} // namespace scam_ios
