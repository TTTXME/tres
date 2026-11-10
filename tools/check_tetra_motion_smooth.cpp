// P603 round 2: the Tetra motion smoothing (HybridTuning::mosaicTetraMotionSmooth; ChromaMedianGpu::mosaicPost's third pass and its
// CPU twin hybridTetraMotionSmooth). A synthetic merged result: a flat wall with one frame's noise, sharp steps and a fine
// texture above the noise, the coverage low (3 frames) on the right half and high (20) on the left:
// - the GPU (strips of 64 rows with their halos, along-edge and the Tetra notch before it as in the app) = the CPU twins within 2e-5;
// - where the coverage box is at or above Hi the result is the input bit for bit (and the share ramps in between);
// - where it is low: the flat wall's noise falls by a third or more, the step keeps its height (within 3 %), the texture above
//   the range keeps 85 % of its contrast;
// - the defaults, the tuning keys and the denoise switch («Все шумодавы» off: the stage off).
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <random>
#include <unistd.h>

using namespace scam;

int main() {
    const HybridTuning d;
    assert(d.mosaicTetraMotionSmooth == 1.f && d.mosaicTetraMotionSmoothRange == 1.5f && d.mosaicTetraMotionSmoothLo == 5.f &&
           d.mosaicTetraMotionSmoothHi == 9.f);
    {
        const char* tmp = std::getenv("TMPDIR");
        std::string tmpl = std::string(tmp && *tmp ? tmp : "/tmp") + "/tetra-smooth-XXXXXX";
        char* dir = tmpl.data();
        if (!mkdtemp(dir)) { std::perror("mkdtemp"); return 1; }
        { std::ofstream f(std::string(dir) + "/hybrid_tuning.txt"); f << "mosaicTetraMotionSmooth 0.5\nmosaicTetraMotionSmoothRange 3\nmosaicTetraMotionSmoothLo 4\nmosaicTetraMotionSmoothHi 7\n"; }
        const HybridTuning t = loadHybridTuning(dir, [](const std::string&) {});
        assert(t.mosaicTetraMotionSmooth == 0.5f && t.mosaicTetraMotionSmoothRange == 3.f && t.mosaicTetraMotionSmoothLo == 4.f &&
               t.mosaicTetraMotionSmoothHi == 7.f);
        { std::ofstream f(std::string(dir) + "/hybrid_tuning.txt"); f << "denoise 0\n"; }
        const HybridTuning off = loadHybridTuning(dir, [](const std::string&) {});
        assert(off.mosaicTetraMotionSmooth == 0.f); // P600: a registered denoise stage
        std::remove((std::string(dir) + "/hybrid_tuning.txt").c_str());
        rmdir(dir);
    }
    const int W = 384, H = 352, dx = 0, dy = 0;
    const float S = 3e-4f, O = 6.5e-6f; // one sensor site's noise (Ark burst 20261109_135446)
    std::mt19937 rng(603); std::normal_distribution<float> n01(0.f, 1.f);
    std::vector<float> rgb(size_t(W) * H * 3), eff(size_t(W) * H);
    const float neutral[3] = {0.5f, 1.f, 0.6f};
    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
        float v = 0.3f;
        if (y >= 120 && y < 200) v = x % 64 < 32 ? 0.2f : 0.45f;                 // a sharp step every 32 px
        if (y >= 240) v = 0.3f + 0.08f * std::sin(float(x) * 0.7f) * std::sin(float(y) * 0.6f); // fine texture far above the noise
        for (int c = 0; c < 3; ++c) {
            const float s = v * neutral[c];
            rgb[(size_t(y) * W + x) * 3 + c] = s + std::sqrt(S * s + O) * n01(rng);
        }
        eff[size_t(y) * W + x] = x < W / 2 ? 20.f : 3.f;
    }
    const float lo = d.mosaicTetraMotionSmoothLo, hi = d.mosaicTetraMotionSmoothHi, k = d.mosaicTetraMotionSmoothRange;
    std::vector<float> cpu = rgb;
    const size_t nc = hybridTetraMotionSmooth(cpu, W, H, eff, dx, dy, lo, hi, S, O, k, 1.f);
    // the GPU, in strips of 64 rows; the along-edge pass and the Tetra notch first in the same call, their CPU twins likewise
    std::vector<float> cpuChain = rgb, gpuChain = rgb, gpuAlone = rgb;
    const std::array<float, 2> rT = hybridLatticeNotchRange(eff, kTetraNotch.lowFrac, kTetraNotch.highFrac);
    const float notchT[3] = {rT[0], rT[1], 1.f};
    float cT[kLatticeNotchPMax], sT[kLatticeNotchPMax], gT[2 * kLatticeNotchRMax + 1];
    hybridLatticeNotchTables(cT, sT, gT, kTetraNotch);
    hybridAlongEdge(cpuChain, W, H, eff, dx, dy, 8.f, 1.f, 2);
    hybridLatticeNotch(cpuChain, W, H, eff, dx, dy, 1.f, -1.f, -1.f, kTetraNotch, &rT);
    const size_t ncc = hybridTetraMotionSmooth(cpuChain, W, H, eff, dx, dy, lo, hi, S, O, k, 1.f);
    const float sm[6] = {lo, hi, 1.f, k, S, O};
    size_t ng = 0, ngc = 0, ch[2]{};
    try {
        auto ctx = chromaTake();
        ctx->postStrip = 64;
        ctx->mosaicPost(gpuAlone, W, H, eff, dx, dy, 0.f, 0.f, 2, nullptr, nullptr, nullptr, nullptr, nullptr, 8, 1, -1.f, -0.5f, sm, &ng);
        ctx->mosaicPost(gpuChain, W, H, eff, dx, dy, 8.f, 1.f, 2, notchT, cT, sT, gT, ch, kTetraNotch.period, kTetraNotch.R,
                        kTetraNotch.gateLo, kTetraNotch.gateHi, sm, &ngc);
        chromaKeep(std::move(ctx));
    } catch (const std::exception& e) {
        std::fprintf(stderr, "the GPU pass: %s\n", e.what());
        return 1;
    }
    double dAlone = 0, dChain = 0;
    for (size_t i = 0; i < rgb.size(); ++i) {
        dAlone = std::max(dAlone, double(std::fabs(cpu[i] - gpuAlone[i])));
        dChain = std::max(dChain, double(std::fabs(cpuChain[i] - gpuChain[i])));
    }
    // the high-coverage half (its box wholly above Hi: x < W / 2 - 4): bit for bit
    bool same = true;
    for (int y = 0; y < H && same; ++y) for (int x = 0; x < W / 2 - 4; ++x)
        for (int c = 0; c < 3; ++c) if (cpu[(size_t(y) * W + x) * 3 + c] != rgb[(size_t(y) * W + x) * 3 + c]) { same = false; break; }
    auto luma = [&](const std::vector<float>& v, int x, int y) { const float* p = &v[(size_t(y) * W + x) * 3]; return 0.25f * p[0] + 0.5f * p[1] + 0.25f * p[2]; };
    // the flat wall (rows 20 .. 100) on the low-coverage half: the luma's std
    auto flatStd = [&](const std::vector<float>& v) {
        double s = 0, s2 = 0; int n = 0;
        for (int y = 20; y < 100; ++y) for (int x = W / 2 + 16; x < W - 8; ++x) { const double l = luma(v, x, y); s += l; s2 += l * l; n++; }
        return std::sqrt(std::max(0.0, s2 / n - (s / n) * (s / n)));
    };
    // the step's height: the means 4 .. 12 px either side of each edge (rows 130 .. 190)
    auto step = [&](const std::vector<float>& v) {
        double a = 0, b = 0; int n = 0;
        for (int y = 130; y < 190; ++y) for (int e = W / 2 + 32; e + 12 < W; e += 64) {
            for (int q = 4; q < 12; ++q) { a += luma(v, e - q, y); b += luma(v, e + q, y); }
            n += 8;
        }
        return (b - a) / n;
    };
    // the texture's contrast: luma rms about its 5 x 5 mean (rows 250 .. 340)
    auto tex = [&](const std::vector<float>& v) {
        double e = 0; int n = 0;
        for (int y = 250; y < 340; ++y) for (int x = W / 2 + 16; x < W - 8; ++x) {
            double s = 0; for (int j = -2; j <= 2; ++j) for (int i = -2; i <= 2; ++i) s += luma(v, x + i, y + j);
            const double t = luma(v, x, y) - s / 25.0; e += t * t; n++;
        }
        return std::sqrt(e / n);
    };
    const double f0 = flatStd(rgb), f1 = flatStd(cpu), s0 = step(rgb), s1 = step(cpu), t0 = tex(rgb), t1 = tex(cpu);
    std::printf("Tetra motion smoothing: %zu px (CPU) / %zu (GPU) / %zu (GPU after the along-edge pass and the notch); GPU - CPU %.3g, "
                "in the chain %.3g; flat noise %.5f -> %.5f (%.2f), step %.4f -> %.4f, texture %.5f -> %.5f (%.3f); high coverage %s\n",
                nc, ng, ngc, dAlone, dChain, f0, f1, f1 / f0, s0, s1, t0, t1, t1 / t0, same ? "bit for bit" : "CHANGED");
    bool ok = true;
    if (!(nc > 0 && dAlone < 2e-5 && dChain < 2e-5 && (ng > nc ? ng - nc : nc - ng) <= nc / 500 + 2 && (ngc > ncc ? ngc - ncc : ncc - ngc) <= ncc / 500 + 2)) {
        std::printf("FAIL: the GPU pass differs from its CPU twin\n"); ok = false;
    }
    if (!same) { std::printf("FAIL: the pass changed the high-coverage half\n"); ok = false; }
    if (!(f1 < 0.67 * f0)) { std::printf("FAIL: the flat wall's noise stays\n"); ok = false; }
    if (!(std::fabs(s1 / s0 - 1) < 0.03)) { std::printf("FAIL: the step lost its height\n"); ok = false; }
    if (!(t1 > 0.85 * t0)) { std::printf("FAIL: the texture lost its contrast\n"); ok = false; }
    std::printf(ok ? "check_tetra_motion_smooth: OK\n" : "check_tetra_motion_smooth: FAILED\n");
    return ok ? 0 : 1;
}
