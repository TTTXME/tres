// Offline replay of the SHDR merge («Качество») of an NCH burst (Engine/ScamMergeCore.hpp: the worker's call with cre-off).
// One source for both GL backends: Linux + Mesa (the reference) and macOS + ScamGL (GLES compute on Metal).
// Usage: hybrid_replay <job dir with hybrid_tuning.txt and the job markers> <burst.nch> <out.f32> [worker output]
// Output: float32 RGB rows top-down, preceded by a 16-byte header "SCRG" + width + height + channels (uint32 LE); with a fourth
// argument also the worker's own output file (the RGB and its trailers, as ScamNeuralClient reads it).
#include "../Engine/ScamMergeCore.hpp"

#include <cstdio>
#include <fstream>

int main(int argc, char** argv) {
    if (argc != 4 && argc != 5) {
        std::fprintf(stderr, "usage: %s <job dir> <burst.nch> <out.f32> [worker output]\n", argv[0]);
        return 2;
    }
    try {
        auto report = [](const std::string& line) { std::printf("%s\n", line.c_str()); std::fflush(stdout); };
        const scam_ios::MergeResult r = scam_ios::mergeBurst(argv[1], argv[2], report);
        std::ofstream out(argv[3], std::ios::binary | std::ios::trunc);
        const uint32_t header[4] = {0x47524353u, uint32_t(r.width), uint32_t(r.height), 3u};
        out.write(reinterpret_cast<const char*>(header), sizeof header);
        out.write(reinterpret_cast<const char*>(r.rgb.data()), std::streamsize(size_t(r.width) * r.height * 3 * sizeof(float)));
        if (!out) throw std::runtime_error("cannot write the result");
        if (argc == 5) {
            const std::vector<uint8_t> worker = scam_ios::workerOutput(r);
            std::ofstream w(argv[4], std::ios::binary | std::ios::trunc);
            w.write(reinterpret_cast<const char*>(worker.data()), std::streamsize(worker.size()));
            if (!w) throw std::runtime_error("cannot write the worker output");
            report("HYBRID REPLAY WORKER OUTPUT: " + std::to_string(worker.size()) + " bytes (effective map " + std::to_string(r.effMap.size())
                   + ", clip flags " + std::to_string(r.clipFlags.size()) + ", highlight mask " + std::to_string(r.hlMask.size()) + ")");
        }
        report("HYBRID REPLAY DONE: " + std::to_string(r.width) + "x" + std::to_string(r.height) + " in " + std::to_string(int(r.ms)) + " ms");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "HYBRID REPLAY FAILED: %s\n", error.what());
        return 1;
    }
}
