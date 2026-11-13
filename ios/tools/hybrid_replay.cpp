// Offline replay of the SHDR merge («Качество»): the same scam::hybridReconstruct call as scam-neural-worker.cpp makes for
// an NCH burst with SCAM_HYBRID and the job marker cre-off (SCAMERA tile alignment, no vendor CRE).
// One source for both GL backends: Linux + Mesa (the reference) and macOS / iOS + ScamGL (GLES compute on Metal).
// Usage: hybrid_replay <job dir with hybrid_tuning.txt> <burst.nch> <out.f32>
// Output: float32 RGB rows top-down, preceded by a 16-byte header "SCRG" + width + height + channels (uint32 LE).
#include "scam-capture.h"
#include "scam-hybrid.h"

#include <cstdio>
#include <fstream>

int main(int argc, char** argv) {
    if (argc != 4) {
        std::fprintf(stderr, "usage: %s <job dir> <burst.nch> <out.f32>\n", argv[0]);
        return 2;
    }
    try {
        auto report = [](const std::string& line) { std::printf("%s\n", line.c_str()); std::fflush(stdout); };
        const auto tuning = scam::loadHybridTuning(argv[1], report);
        scam::MappedHybridBurst burst(argv[2], false);
        if (!burst.hybrid) throw std::runtime_error("not an NCH v10-v12 hybrid burst");
        scam::HybridInput hin = burst.input;
        hin.noEffMap = true;
        // As the worker with cre-off: with creLazy the merge asks for the CRE first and is told there is none.
        const bool creLazy = tuning.creLazy > 0 && tuning.gpuAlign == 1 && tuning.laGpu != 0 && tuning.localAlign > 0;
        scam::ScamAlignment alignment;
        if (creLazy) {
            alignment = [](scam::Burst&) -> std::array<scam::BackwardHomography, 7> { throw std::runtime_error("CRE unavailable"); };
            scam::hybridAlignmentEnsure() = [] { return false; };
        }
        report(std::string("HYBRID REPLAY: ") + argv[2] + " " + std::to_string(hin.w) + "x" + std::to_string(hin.h) + " frames="
               + std::to_string(hin.frames.size()) + " grid=" + std::to_string(hin.grid) + " mosaic=" + std::to_string(hin.mosaic));
        std::vector<uint16_t> mergedDng;
        const auto started = std::chrono::steady_clock::now();
        std::vector<float> result = scam::hybridReconstruct(hin, tuning, alignment, report, &mergedDng, nullptr);
        int w = hin.w, h = hin.h;
        const size_t px = result.size() / 3;
        if (hin.outW > 0 && px != size_t(hin.outW) * hin.outH) {
            int g = 0;
            for (int k = 1; k <= 2; ++k) if (px == size_t(hin.w) * k * size_t(hin.h) * k) g = k;
            if (!g) throw std::runtime_error("result is no grid of the burst");
            scam::hybridResampleResult(result, hin.w * g, hin.h * g, hin.outW, hin.outH, nullptr, nullptr);
            w = hin.outW;
            h = hin.outH;
        } else if (hin.outW > 0) {
            w = hin.outW;
            h = hin.outH;
        } else {
            for (int k = 1; k <= 2; ++k) if (px == size_t(hin.w) * k * size_t(hin.h) * k) { w = hin.w * k; h = hin.h * k; }
        }
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
        std::ofstream out(argv[3], std::ios::binary | std::ios::trunc);
        const uint32_t header[4] = {0x47524353u, uint32_t(w), uint32_t(h), 3u};
        out.write(reinterpret_cast<const char*>(header), sizeof header);
        out.write(reinterpret_cast<const char*>(result.data()), std::streamsize(size_t(w) * h * 3 * sizeof(float)));
        if (!out) throw std::runtime_error("cannot write the result");
        report("HYBRID REPLAY DONE: " + std::to_string(w) + "x" + std::to_string(h) + " in " + std::to_string(int(ms)) + " ms");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "HYBRID REPLAY FAILED: %s\n", error.what());
        return 1;
    }
}
