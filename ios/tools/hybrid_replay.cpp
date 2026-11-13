// Offline replay of the SHDR merge («Качество») of an NCH burst (Engine/ScamMergeCore.hpp: the worker's call with cre-off).
// One source for both GL backends: Linux + Mesa (the reference) and macOS + ScamGL (GLES compute on Metal).
// Usage: hybrid_replay <job dir with hybrid_tuning.txt> <burst.nch> <out.f32>
// Output: float32 RGB rows top-down, preceded by a 16-byte header "SCRG" + width + height + channels (uint32 LE).
#include "../Engine/ScamMergeCore.hpp"

#include <cstdio>
#include <fstream>

int main(int argc, char** argv) {
    if (argc != 4) {
        std::fprintf(stderr, "usage: %s <job dir> <burst.nch> <out.f32>\n", argv[0]);
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
        report("HYBRID REPLAY DONE: " + std::to_string(r.width) + "x" + std::to_string(r.height) + " in " + std::to_string(int(r.ms)) + " ms");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "HYBRID REPLAY FAILED: %s\n", error.what());
        return 1;
    }
}
