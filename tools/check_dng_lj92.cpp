// P704: the lossless JPEG of the DNG writer (tiny_dng_writer's lj92_encode) through its fast passes (no delinearize table, one
// contiguous tile: DngCreator's call) is byte for byte the stream of the original passes (SCAM_LJ92_REFERENCE): 10-bit and
// 16-bit mosaics, flat, noisy, worst-case alternating extremes (32 bits a sample), runs of 0xFF bytes, tiny and odd sizes; a
// sample above the bit depth is refused by both. Prints the two timings of a 50 MP mosaic (2W x H/2 as the writer calls it).
// Build (CI): g++ -pthread -std=c++17 -O2 -fsanitize=address,undefined -Iapp/src/main/cpp/deps tools/check_dng_lj92.cpp
#define TINY_DNG_WRITER_IMPLEMENTATION
#include "tiny_dng_writer.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

namespace tdw = tinydngwriter;
static int fails = 0;
static void expect(bool ok, const std::string& what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what.c_str());
    if (!ok) ++fails;
}
static int encode(std::vector<uint16_t>& img, int w, int h, int depth, bool reference, std::vector<uint8_t>& out, double* ms = nullptr) {
    if (reference) setenv("SCAM_LJ92_REFERENCE", "1", 1); else unsetenv("SCAM_LJ92_REFERENCE");
    uint8_t* enc = nullptr; int n = 0;
    const auto t0 = std::chrono::steady_clock::now();
    const int rc = tdw::detail::lj92_encode(img.data(), w, h, depth, w * h, 0, nullptr, 0, &enc, &n);
    if (ms) *ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    out.assign(enc, enc + (rc == 0 && enc ? n : 0));
    free(enc);
    unsetenv("SCAM_LJ92_REFERENCE");
    return rc;
}
static void same(std::vector<uint16_t> img, int w, int h, int depth, const std::string& what) {
    std::vector<uint8_t> a, b;
    // (the reference passes may write past their 3 bytes per sample on the worst case: compared only where they fit)
    const int ra = encode(img, w, h, depth, false, a), rb = encode(img, w, h, depth, true, b);
    expect(ra == rb && a == b && (ra != 0 || !a.empty()), what + " (" + std::to_string(a.size()) + " bytes)");
}
int main() {
    std::mt19937 r(5);
    auto noisy = [&](int w, int h, int base, int sigma, int maxv) {
        std::vector<uint16_t> v(size_t(w) * h);
        std::normal_distribution<float> n(0.f, float(sigma));
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            const float s = base + 0.3f * base * std::sin(x * 0.01f) * std::cos(y * 0.013f) + n(r);
            v[size_t(y) * w + x] = uint16_t(std::clamp(int(std::lround(s)), 0, maxv));
        }
        return v;
    };
    same(noisy(2048, 768, 400, 6, 1023), 2048, 768, 16, "10-bit mosaic, mid-tone noise");
    same(noisy(2048, 768, 30000, 900, 65535), 2048, 768, 16, "16-bit merged mosaic with headroom");
    same(std::vector<uint16_t>(size_t(1024) * 64, 777), 1024, 64, 16, "flat");
    {
        std::vector<uint16_t> v(size_t(1000) * 50);
        for (size_t i = 0; i < v.size(); ++i) v[i] = (i + i / 1000) & 1 ? 65535 : 0;
        same(v, 1000, 50, 16, "alternating 0 / 65535 (the longest codes)");
    }
    {
        std::vector<uint16_t> v(size_t(512) * 40);
        for (auto& x : v) x = uint16_t(r());
        same(v, 512, 40, 16, "uniform 16-bit noise");
        std::vector<uint16_t> f(size_t(513) * 41, 65535);
        same(f, 513, 41, 16, "all 65535, odd size");
    }
    same(noisy(3, 1, 400, 30, 1023), 3, 1, 16, "3 x 1");
    same(noisy(1, 7, 400, 30, 1023), 1, 7, 16, "1 x 7");
    same(noisy(64, 64, 400, 30, 1023), 64, 64, 12, "12-bit depth");
    {
        std::vector<uint16_t> v = noisy(256, 16, 400, 30, 1023);
        v[256 * 9 + 17] = 5000;
        std::vector<uint8_t> a, b;
        const int ra = encode(v, 256, 16, 12, false, a), rb = encode(v, 256, 16, 12, true, b);
        expect(ra == tdw::detail::LJ92_ERROR_TOO_WIDE && rb == ra, "a sample above the depth: refused by both");
    }
    {   // 50 MP as DngCreator calls it: the 8192 x 6144 mosaic as 16384 x 3072
        std::vector<uint16_t> v = noisy(16384, 3072, 2000, 40, 65535);
        std::vector<uint8_t> a, b; double fastMs = 0, refMs = 0;
        encode(v, 16384, 3072, 16, false, a, &fastMs);
        encode(v, 16384, 3072, 16, true, b, &refMs);
        char line[200];
        std::snprintf(line, sizeof(line), "50 MP 16-bit: the same %zu bytes, fast %.0f ms against %.0f ms", a.size(), fastMs, refMs);
        expect(a == b && !a.empty(), line);
    }
    std::printf(fails ? "lj92: %d FAILED\n" : "lj92: ok\n", fails);
    return fails ? 1 : 0;
}
