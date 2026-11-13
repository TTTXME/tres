// The SHDR merge of an NCH burst as scam-neural-worker.cpp runs it for SCAM_HYBRID with the job marker cre-off: shared by
// tools/hybrid_replay.cpp (Mesa / ScamGL on a Mac) and the app (Bridge/ScamMerge.mm, ScamGL on the iPhone's GPU).
#pragma once

#include "scam-capture.h"
#include "scam-hybrid.h"

#include <chrono>
#include <functional>
#include <string>
#include <vector>

namespace scam_ios {

struct MergeResult {
    std::vector<float> rgb; // float32 RGB rows top-down, camera colours, base-frame units
    int width = 0, height = 0;
    double ms = 0;
};

inline MergeResult mergeBurst(const std::string& jobDir, const std::string& burstPath,
                              const std::function<void(const std::string&)>& report) {
    const auto tuning = scam::loadHybridTuning(jobDir, report);
    scam::MappedHybridBurst burst(burstPath, false);
    if (!burst.hybrid) throw std::runtime_error("not an NCH v10-v12 hybrid burst");
    scam::HybridInput hin = burst.input;
    hin.noEffMap = true;
    const bool creLazy = tuning.creLazy > 0 && tuning.gpuAlign == 1 && tuning.laGpu != 0 && tuning.localAlign > 0;
    scam::ScamAlignment alignment;
    if (creLazy) {
        alignment = [](scam::Burst&) -> std::array<scam::BackwardHomography, 7> { throw std::runtime_error("CRE unavailable"); };
        scam::hybridAlignmentEnsure() = [] { return false; };
    }
    report("HYBRID REPLAY: " + burstPath + " " + std::to_string(hin.w) + "x" + std::to_string(hin.h) + " frames="
           + std::to_string(hin.frames.size()) + " grid=" + std::to_string(hin.grid) + " mosaic=" + std::to_string(hin.mosaic));
    std::vector<uint16_t> mergedDng;
    const auto started = std::chrono::steady_clock::now();
    MergeResult out;
    out.rgb = scam::hybridReconstruct(hin, tuning, alignment, report, &mergedDng, nullptr);
    scam::hybridAlignmentEnsure() = nullptr;
    out.width = hin.w;
    out.height = hin.h;
    const size_t px = out.rgb.size() / 3;
    if (hin.outW > 0 && px != size_t(hin.outW) * hin.outH) {
        int g = 0;
        for (int k = 1; k <= 2; ++k) if (px == size_t(hin.w) * k * size_t(hin.h) * k) g = k;
        if (!g) throw std::runtime_error("result is no grid of the burst");
        scam::hybridResampleResult(out.rgb, hin.w * g, hin.h * g, hin.outW, hin.outH, nullptr, nullptr);
        out.width = hin.outW;
        out.height = hin.outH;
    } else if (hin.outW > 0) {
        out.width = hin.outW;
        out.height = hin.outH;
    } else {
        for (int k = 1; k <= 2; ++k) if (px == size_t(hin.w) * k * size_t(hin.h) * k) { out.width = hin.w * k; out.height = hin.h * k; }
    }
    out.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    return out;
}

} // namespace scam_ios
