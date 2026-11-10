// P594: the Tetra native merge's luma guide (HybridTuning::mosaicTetraLumaGuide). A hand-held Tetra burst whose frames drift along
// one axis (Ark burst 20261109_135446: x spread over 11 px, y within 1.3 px) shares its 4x4 block rows across the frames, so along a
// horizontal edge each colour's same-row sites alternate every 4 px with sites across the edge: 8 px beads in R, G and B alike. A
// synthetic 8-frame Tetra burst (frames shifted along x only, a sensor neutral of 0.5 / 1 / 0.6) through
// hybridReconstructMosaicNative with the guide off and on: the beads along horizontal bars (the 8-periodic part of each row) fall,
// a flat coloured patch keeps its colour, a diagonal texture keeps its contrast, and the defaults / tuning keys are as documented.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <random>
#include <unistd.h>

using namespace scam;

static float scene(float X, float Y, int c, int W, int H) {
    const float neutral = c == 0 ? 0.5f : c == 2 ? 0.6f : 1.f;
    if (Y < H / 2) { // thin horizontal lines (2 px every 11 px: every phase of the 4-row block bands), as the rails of the burst
        const float v = std::fmod(Y, 11.f) < 2.f ? 0.62f : 0.18f;
        return v * neutral;
    }
    if (X < W / 2) { // a flat coloured patch (orange in the scene's white balance)
        const float rgb[3] = {0.55f, 0.32f, 0.12f};
        return rgb[c] * neutral;
    }
    // a diagonal texture of ~17 px
    const float v = 0.35f + 0.2f * std::sin((X + Y) * 0.26f);
    return v * neutral;
}

int main() {
    {
        const HybridTuning d;
        assert(d.mosaicTetraLumaGuide == 1.f && d.mosaicTetraLumaGuideRB == 1.f);
        const char* tmp = std::getenv("TMPDIR");
        std::string tmpl = std::string(tmp && *tmp ? tmp : "/tmp") + "/tetra-guide-XXXXXX";
        char* dir = tmpl.data();
        if (!mkdtemp(dir)) { std::perror("mkdtemp"); return 1; }
        { std::ofstream f(std::string(dir) + "/hybrid_tuning.txt"); f << "mosaicTetraLumaGuide 0\nmosaicTetraLumaGuideRB 0.5\n"; }
        const HybridTuning t = loadHybridTuning(dir, [](const std::string&) {});
        assert(t.mosaicTetraLumaGuide == 0.f && t.mosaicTetraLumaGuideRB == 0.5f);
        std::remove((std::string(dir) + "/hybrid_tuning.txt").c_str());
        rmdir(dir);
        // the guide is in the Tetra programs only: the Quad text has none of it
        const std::string fast = kHybMergeMosaicFast;
        assert(fast.find("#if NAT_B==4\n    if(natLumU.x>0.0&&wb>0.0)natLumGuide(a); // P594") != std::string::npos);
    }
    const int W = 512, H = 384, N = 8;
    const char* temp = std::getenv("TMPDIR");
    std::string path = std::string(temp ? temp : "/tmp") + "/scamera-tetra-guide-XXXXXX";
    const int fd = mkstemp(path.data());
    assert(fd >= 0);
    uint32_t words[32 + 8 * N]{};
    words[0] = 0x3143484e; words[1] = 11; words[2] = W; words[3] = H; words[4] = 0; words[5] = N;
    const float white = 1023, black = 64, exposure = 1, slope = 3e-4f, offset = 6.5e-6f; // the burst's measured noise (ISO 50)
    std::memcpy(words + 6, &white, 4);
    for (int i = 7; i < 11; ++i) std::memcpy(words + i, &black, 4);
    words[13] = 1; words[14] = 4; // grid 1, colour block 4 (Tetra)
    for (int i = 0; i < N; ++i) {
        uint32_t* f = words + 32 + 8 * i;
        f[0] = kRoleNormal; std::memcpy(f + 1, &exposure, 4); f[2] = 50; std::memcpy(f + 3, &slope, 4); std::memcpy(f + 4, &offset, 4);
        const float order = float(i); std::memcpy(f + 5, &order, 4);
    }
    assert(write(fd, words, sizeof(words)) == ssize_t(sizeof(words)));
    std::mt19937 rng(594); std::normal_distribution<float> n01(0.f, 1.f);
    // the burst's drift: along x over 7 px, clustered as the owner's; no vertical shift
    const float sx[N] = {0.f, 2.6f, 5.1f, 5.6f, 6.3f, 7.1f, 4.8f, 6.9f};
    std::vector<uint16_t> plane(size_t(W) * H);
    for (int f = 0; f < N; ++f) {
        for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
            const int ph = (((y >> 2) & 1) << 1) | ((x >> 2) & 1), c = ph == 0 ? 0 : ph == 3 ? 2 : 1; // Tetra RGGB of 4x4 blocks
            // the site integrates its pixel (a 4-point box: the scene's edges are not aliased by a point sample)
            float v = 0.f;
            for (int k = 0; k < 4; ++k) v += 0.25f * scene(float(x) + sx[f] + 0.25f + 0.5f * float(k & 1), float(y) + 0.25f + 0.5f * float(k >> 1), c, W, H);
            v += std::sqrt(slope * std::max(v, 0.f) + offset) * n01(rng);
            plane[size_t(y) * W + x] = uint16_t(std::clamp(std::lround(black + v * (white - black)), 0L, 1023L));
        }
        assert(write(fd, plane.data(), plane.size() * 2) == ssize_t(plane.size() * 2));
    }
    assert(close(fd) == 0);
    MappedHybridBurst burst(path);
    std::vector<float> out[2];
    for (int v = 0; v < 2; ++v) {
        HybridTuning t; t.mosaicTetraLumaGuide = float(v);
        out[v] = hybridReconstructMosaicNative(burst.input, 4, t, {}, [](const std::string& l) { if (std::getenv("CHECK_VERBOSE")) std::printf("%s\n", l.c_str()); }, nullptr, nullptr, nullptr, nullptr);
        if (out[v].size() < size_t(W) * H * 3) { std::printf("check_tetra_luma_guide: %zu floats\n", out[v].size()); unlink(path.c_str()); return 1; }
    }
    unlink(path.c_str());
    // green-equivalent luma (the sensor neutral divided out)
    auto lum = [&](const std::vector<float>& o, int x, int y) {
        const float* p = o.data() + 3 * (size_t(y) * W + x);
        return (p[0] / 0.5f + 2.f * p[1] + p[2] / 0.6f) * 0.25f;
    };
    double bead[2] = {0, 0}, col[2][3] = {}, tex[2] = {0, 0};
    for (int v = 0; v < 2; ++v) {
        // beads: per row of the bars, the 8-periodic part along x (the row's mean per phase x mod 8, their spread)
        int rows = 0;
        for (int y = 24; y < H / 2 - 24; ++y) {
            double m[8] = {}; int n[8] = {};
            for (int x = 48; x < W - 48; ++x) { m[x & 7] += lum(out[v], x, y); n[x & 7]++; }
            double mean = 0, var = 0;
            for (int k = 0; k < 8; ++k) { m[k] /= n[k]; mean += m[k] / 8; }
            for (int k = 0; k < 8; ++k) var += (m[k] - mean) * (m[k] - mean) / 8;
            bead[v] += std::sqrt(var); rows++;
        }
        bead[v] /= rows;
        int np = 0;
        for (int y = H / 2 + 32; y < H - 32; ++y) for (int x = 32; x < W / 2 - 32; ++x) {
            const float* p = out[v].data() + 3 * (size_t(y) * W + x);
            for (int c = 0; c < 3; ++c) col[v][c] += p[c];
            np++;
        }
        for (int c = 0; c < 3; ++c) col[v][c] /= np;
        // the texture's contrast: the luma's rms about its 9 x 9 mean
        int nt = 0;
        for (int y = H / 2 + 40; y < H - 40; y += 1) for (int x = W / 2 + 40; x < W - 40; ++x) {
            double s = 0;
            for (int j = -4; j <= 4; ++j) for (int i = -4; i <= 4; ++i) s += lum(out[v], x + i, y + j);
            const double d = lum(out[v], x, y) - s / 81.0;
            tex[v] += d * d; nt++;
        }
        tex[v] = std::sqrt(tex[v] / nt);
    }
    const double rgOff = col[0][0] / col[0][1], rgOn = col[1][0] / col[1][1], bgOff = col[0][2] / col[0][1], bgOn = col[1][2] / col[1][1];
    std::printf("Tetra luma guide off / on: beads along the bars %.5f / %.5f (%.2f); the patch R/G %.4f / %.4f, B/G %.4f / %.4f; "
                "texture rms %.5f / %.5f (%.3f)\n", bead[0], bead[1], bead[1] / bead[0], rgOff, rgOn, bgOff, bgOn, tex[0], tex[1], tex[1] / tex[0]);
    bool ok = true;
    if (!(bead[0] > 0.002)) { std::printf("FAIL: the burst prints no beads without the guide (the check sees nothing)\n"); ok = false; }
    if (!(bead[1] < 0.6 * bead[0])) { std::printf("FAIL: the guide leaves the beads\n"); ok = false; }
    if (std::fabs(rgOn / rgOff - 1) > 0.01 || std::fabs(bgOn / bgOff - 1) > 0.01) { std::printf("FAIL: the patch's colour moved\n"); ok = false; }
    if (!(tex[1] > 0.9 * tex[0])) { std::printf("FAIL: the texture lost its contrast\n"); ok = false; }
    std::printf(ok ? "check_tetra_luma_guide: OK\n" : "check_tetra_luma_guide: FAILED\n");
    return ok ? 0 : 1;
}
