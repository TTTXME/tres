// P636 «Oldstyle»: the tone of one frame (oldstyle-tone-core.h: the app's path into the owner's tone-port module) on one thread
// (the path of P630e, the reference) against the banded path on N threads: the bytes compared, the times printed. Nine cases:
// six with P630h's rows at their defaults, three with them away (the curve's shape, the transfer's share, a strong matrix).
//   bench_oldstyle_tone [width height threads repeats [case-substring]]     (4096 3072 4 2: a 12 MP frame)
//   bench_oldstyle_tone --exhaustive      the banded pass's branch-free pixel functions == the supplement's, all 2^24 triples
// Build (as tools/check_tone_port.py does, which runs it on small frames as an equality check):
//   g++ -std=c++17 -O2 -ffp-contract=off -I app/src/main/cpp -I app/src/main/cpp/third_party/tone_port/include \
//       tools/bench_oldstyle_tone.cpp app/src/main/cpp/third_party/tone_port/src/*.cpp -o bench_oldstyle_tone -lpthread
// Exit code: the number of cases whose bytes differ.
#include "oldstyle-tone-core.h"
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <random>
static uint64_t fnv(const uint8_t* d, size_t n) { uint64_t h = 1469598103934665603ull; for (size_t i = 0; i < n; ++i) { h ^= d[i]; h *= 1099511628211ull; } return h; }
// --exhaustive: the pass's branch-free pixel functions against the supplement's own over every 8-bit triple
static int exhaustive() {
    using namespace tone_supplement;
    long bad = 0;
    const SatTable tables[4] = {make_saturation(false, 0.25f, 1.3f), make_saturation(true, 0.25f, 1.3f), make_saturation(true, 0.4f, 1.f),
                                make_saturation(false, -0.5f, 0.6f)};
    for (int a = 0; a < 256; ++a) for (int b = 0; b < 256; ++b) for (int c = 0; c < 256; ++c) {
        int x, y, z;
        const auto yuv = rgb8_to_yuv444({uint8_t(a), uint8_t(b), uint8_t(c)});
        oldstyle::rgb8_to_yuv444_fast(a, b, c, x, y, z);
        bad += x != yuv[0] || y != yuv[1] || z != yuv[2];
        const auto rgb = yuv444_to_rgb8(uint8_t(a), uint8_t(b), uint8_t(c));
        oldstyle::yuv444_to_rgb8_fast(a, b, c, x, y, z);
        bad += x != rgb[0] || y != rgb[1] || z != rgb[2];
        for (int t = 0; t < 4; ++t) for (int domain = 0; domain < 2; ++domain) {
            const auto s = saturate_rgb8({uint8_t(a), uint8_t(b), uint8_t(c)}, tables[t], domain != 0);
            x = a; y = b; z = c;
            oldstyle::saturate_rgb8_fast(x, y, z, tables[t], domain != 0);
            bad += x != s[0] || y != s[1] || z != s[2];
        }
    }
    printf("exhaustive: rgb8_to_yuv444, yuv444_to_rgb8, saturate_rgb8 (4 tables x 2 domains) over 2^24 triples: %ld differ\n", bad);
    return bad != 0;
}

int main(int argc, char** argv) {
    if (argc > 1 && !strcmp(argv[1], "--exhaustive")) return exhaustive();
    const size_t w = argc > 1 ? atoi(argv[1]) : 4096, h = argc > 2 ? atoi(argv[2]) : 3072;
    const int threads = argc > 3 ? atoi(argv[3]) : 4, reps = argc > 4 ? atoi(argv[4]) : 2;
    const char* only = argc > 5 ? argv[5] : "";
    std::vector<int16_t> src(w * h * 3);
    std::mt19937 rng(7);
    for (size_t y = 0; y < h; ++y) for (size_t x = 0; x < w; ++x) {
        // a scene-like frame: a sky gradient past white, dark ground with texture, noise, a few lights and negatives
        const float fx = float(x) / w, fy = float(y) / h;
        float base = fy < 0.45f ? 2600.f + 4200.f * (0.45f - fy) + 500.f * fx : 60.f + 900.f * fx * (1.f - fy) + 300.f * ((x / 37 + y / 23) % 3);
        if (((x / 211) % 5 == 2) && ((y / 173) % 4 == 1)) base = 9000.f;
        const float n = float(int(rng() % 129) - 64) * (0.02f * std::sqrt(base + 40.f));
        const float v[3] = {base * 0.85f + n, base + n * 0.7f, base * (fy < 0.45f ? 1.25f : 0.7f) - n};
        for (int c = 0; c < 3; ++c) src[c * w * h + y * w + x] = int16_t(std::clamp(v[c], -200.f, 32000.f));
    }
    std::vector<float> shading(17 * 13 * 2);
    for (int j = 0; j < 13; ++j) for (int i = 0; i < 17; ++i) { const float r = std::hypot((i - 8) / 8.f, (j - 6) / 6.f); shading[(j * 17 + i) * 2] = shading[(j * 17 + i) * 2 + 1] = 1.f + 1.2f * r * r; }
    const float ccm[9] = {1.62f, -0.48f, -0.14f, -0.21f, 1.45f, -0.24f, 0.03f, -0.55f, 1.52f};
    // strong: a matrix of rows past a camera's (P630h's «сила цветовой матрицы» above 1: the input's ceiling from the matrix)
    const float strong[9] = {3.4f, -1.9f, -0.5f, -0.9f, 2.9f, -1.0f, 0.1f, -2.2f, 3.1f};
    struct Case { const char* name; int version; bool hdr, postview; oldstyle::Post post; bool strong_matrix = false; };
    oldstyle::Post full; full.dehaze = true; full.max_dark = 64; full.percentile = 0.2f; full.corner = 0.2f; full.saturation = true; full.sat = 0.08f; full.vibrance = 1.2f; full.low_frequency = true;
    oldstyle::Post dz = full; dz.saturation = false;
    oldstyle::Post none;
    // P630h's rows away from their defaults: the curve's shape, half the low-frequency transfer
    oldstyle::Post rows = full; rows.curve_mid = 0.6f; rows.curve_contrast = -0.7f; rows.lf_strength = 0.5f;
    oldstyle::Post rows2 = dz; rows2.curve_mid = -1.f; rows2.curve_contrast = 1.f;
    const Case cases[] = {{"V1 HDR dehaze lowfreq (the phone log's shot)", 1, true, false, dz}, {"V1 HDR all stages", 1, true, false, full}, {"V1 SDR all stages", 1, false, false, full},
                          {"V2 HDR all stages", 2, true, false, full}, {"V2 HDR postview", 2, true, true, none}, {"V2 SDR dehaze", 2, false, false, dz},
                          {"V1 HDR rows: curve, half lowfreq", 1, true, false, rows}, {"V1 HDR rows: strong matrix", 1, true, false, rows, true},
                          {"V2 HDR rows: curve", 2, true, false, rows2}};
    int bad = 0;
    for (const auto& c : cases) {
        if (*only && !strstr(c.name, only)) continue;
        std::vector<uint8_t> ref(w * h * 3), out(w * h * 3);
        float info[8], best1[3] = {1e9f, 1e9f, 1e9f}, bestN[3] = {1e9f, 1e9f, 1e9f};
        double cpu1 = 1e18, cpuN = 1e18; // the process's CPU time (all threads): what the work costs when the cores are shared
        for (int r = 0; r < reps; ++r) {
            tone::set_threads(1);
            auto t = std::chrono::steady_clock::now();
            std::clock_t c0 = std::clock();
            oldstyle::render(c.version, src.data(), w, h, c.strong_matrix ? strong : ccm, 0.95f, 1.9f, c.hdr, c.postview, 0.1f, c.post, shading.data(), 17, 13, info, ref.data());
            float total = oldstyle::ms_since(t);
            cpu1 = std::min(cpu1, 1000.0 * double(std::clock() - c0) / CLOCKS_PER_SEC);
            if (total < best1[0]) { best1[0] = total; best1[1] = info[4]; best1[2] = info[5]; }
            tone::set_threads(threads);
            t = std::chrono::steady_clock::now();
            c0 = std::clock();
            oldstyle::render(c.version, src.data(), w, h, c.strong_matrix ? strong : ccm, 0.95f, 1.9f, c.hdr, c.postview, 0.1f, c.post, shading.data(), 17, 13, info, out.data());
            total = oldstyle::ms_since(t);
            cpuN = std::min(cpuN, 1000.0 * double(std::clock() - c0) / CLOCKS_PER_SEC);
            if (total < bestN[0]) { bestN[0] = total; bestN[1] = info[4]; bestN[2] = info[5]; }
        }
        const int low_frequency = int(info[0]); // > 0: the low-frequency colour's transfer ran (its banded form is compared)
        const bool same = ref == out;
        bad += !same;
        printf("%-46s %zux%zu  1 thread: %7.0f ms (module %6.0f, stages %5.0f, cpu %6.0f)  %d threads: %6.0f ms (module %6.0f, stages %5.0f, cpu %6.0f)  x%.2f  %s fnv %016llx lf %d\n", c.name, w, h,
               best1[0], best1[1], best1[2], cpu1, threads, bestN[0], bestN[1], bestN[2], cpuN, best1[0] / bestN[0], same ? "SAME BYTES" : "DIFFERENT", (unsigned long long) fnv(out.data(), out.size()), low_frequency);
        fflush(stdout);
    }
    return bad;
}
