// P523: the Quad / Tetra base demosaic (P282) damps its aliasing colour toward the camera neutral, not toward R = G = B of the
// sensor (magenta once white-balanced: pink shoelaces and stripes on moving people, X8U Quad bursts). Checks the default and the
// tuning key, the neutral (wbR / wbB, else the base frame's grey world over its colour blocks for every CFA order and block size,
// clipped blocks left out) and that the fast native merge carries the damping toward it.
// P525: the demosaic sets its pixels' coverage toward one frame, and the along-edge pass (hybridAlongEdge) takes the beads off a
// slanted edge where the coverage is low, leaves the edge's profile across it, leaves a covered (static) area alone and gives the
// same result on one thread as on many.
// P529: the lattice notch (hybridLatticeNotch) takes a diagonal ~3 px checker off the few-donor areas, leaves static (well covered)
// and one-frame areas alone, and gives the same result on one thread as on many.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <unistd.h>
using namespace scam;

static bool near(float a, float b, float tol) { return std::fabs(a - b) <= tol; }

int main() {
    const HybridTuning d;
    assert(d.baseDemosaicNeutral == 1.f);
    assert(d.wbR == 0.f && d.wbB == 0.f);

    const char* tmp = std::getenv("TMPDIR");
    std::string tmpl = std::string(tmp && *tmp ? tmp : "/tmp") + "/hybrid-neutral-XXXXXX";
    char* dir = tmpl.data();
    if (!mkdtemp(dir)) { std::perror("mkdtemp"); return 1; }
    {
        std::ofstream f(std::string(dir) + "/hybrid_tuning.txt");
        f << "baseDemosaicNeutral 0\nwbR 0.4787\nwbB 0.6535\n";
    }
    const HybridTuning t = loadHybridTuning(dir, [](const std::string&) {});
    assert(t.baseDemosaicNeutral == 0.f && t.wbR == 0.4787f && t.wbB == 0.6535f);
    std::remove((std::string(dir) + "/hybrid_tuning.txt").c_str());
    rmdir(dir);

    // the camera neutral when given
    HybridInput none;
    auto n = hybridMosaicNeutral(none, 2, t);
    assert(n[0] == 0.4787f && n[1] == 0.6535f);
    // no frame and no neutral: 1, 1 (the damping as before)
    n = hybridMosaicNeutral(none, 2, d);
    assert(n[0] == 1.f && n[1] == 1.f);

    // grey world of a mosaic whose colour blocks hold R 0.24, G 0.5, B 0.325 (above black 64, white 1023): neutral 0.48 / 0.65,
    // for every CFA order of the blocks and Quad / Tetra blocks; clipped green blocks in a corner do not count
    for (int b : {2, 4})
        for (int cfa = 0; cfa < 4; cfa++) {
            HybridInput in;
            in.w = 64 * b; in.h = 48 * b; in.cfa = cfa; in.white = 1023.f; in.black = {64.f, 64.f, 64.f, 64.f};
            std::vector<uint16_t> raw(size_t(in.w) * in.h);
            for (int y = 0; y < in.h; y++)
                for (int x = 0; x < in.w; x++) {
                    const int bx = x / b, by = y / b, ph = (((by + (cfa >> 1)) & 1) << 1) | ((bx + (cfa & 1)) & 1);
                    float v = ph == 0 ? 0.24f : ph == 3 ? 0.325f : 0.5f;
                    if (ph != 0 && ph != 3 && bx < 8 && by < 8) v = 1.f; // clipped greens: would pull G up
                    raw[size_t(y) * in.w + x] = uint16_t(std::lround(64.f + v * (1023.f - 64.f)));
                }
            HybridFrame f; f.raw = raw.data(); in.frames.push_back(f);
            n = hybridMosaicNeutral(in, b, d);
            if (!near(n[0], 0.48f, 0.003f) || !near(n[1], 0.65f, 0.003f)) {
                std::fprintf(stderr, "block %d cfa %d: neutral %f %f\n", b, cfa, n[0], n[1]);
                return 1;
            }
        }

    const std::string fast = kHybMergeMosaicFast;
    // P525
    assert(d.mosaicAlongEdge == 1.f && d.baseDemosaicCover == 1);
    {
        const int W = 160, H = 128;
        std::vector<float> rgb(size_t(W) * H * 3), eff(size_t(W) * H, 1.f);
        // a slanted edge (0.1 -> 0.6, 0.3 rad) with a 2 px bead pattern of +-0.06 along it within 2 px of the edge
        auto edgeDist = [](int x, int y) { return (float(y) - 64.f) * std::cos(0.3f) - (float(x) - 80.f) * std::sin(0.3f); };
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                const float dd = edgeDist(x, y), v = 0.1f + 0.5f / (1.f + std::exp(-2.f * dd));
                const float bead = std::fabs(dd) < 2.f ? 0.06f * (((x / 2 + y / 2) & 1) ? 1.f : -1.f) : 0.f;
                for (int c = 0; c < 3; c++) rgb[(size_t(y) * W + x) * 3 + c] = v + bead;
            }
        std::vector<float> one = rgb, many = rgb, still = rgb;
        mergeRowBandsSerial() = true;
        const size_t n1 = hybridAlongEdge(one, W, H, eff, 0, 0, 3.f, 1.f);
        mergeRowBandsSerial() = false;
        const size_t nm = hybridAlongEdge(many, W, H, eff, 0, 0, 3.f, 1.f);
        assert(n1 == nm && n1 > 0 && one == many);
        // the beads along the edge: their residual against the clean edge falls by half at least; across it the profile stays
        double before = 0, after = 0, prof = 0;
        int cnt = 0;
        for (int y = 16; y < H - 16; y++)
            for (int x = 16; x < W - 16; x++) {
                const float dd = edgeDist(x, y);
                const float clean = 0.1f + 0.5f / (1.f + std::exp(-2.f * dd));
                const size_t i = (size_t(y) * W + x) * 3 + 1;
                if (std::fabs(dd) < 2.f) { before += std::fabs(rgb[i] - clean); after += std::fabs(one[i] - clean); ++cnt; }
                else if (std::fabs(dd) < 6.f) prof = std::max(prof, double(std::fabs(one[i] - clean)));
            }
        if (!(cnt > 0 && after < 0.5 * before && prof < 0.03)) {
            std::fprintf(stderr, "along-edge: beads %f -> %f, profile change %f\n", before / cnt, after / cnt, prof);
            return 1;
        }
        // covered (static) pixels: nothing changes
        std::vector<float> full(size_t(W) * H, 30.f);
        assert(hybridAlongEdge(still, W, H, full, 0, 0, 3.f, 1.f) == 0 && still == rgb);
    }
    // P529
    assert(d.mosaicLatticeNotch == 0.f); // P546: the Quad merge's saturated sums instead
    assert(d.mosaicQuadFrameSat == 0.03f && d.mosaicQuadFrameSatLo == 0.8f && d.mosaicQuadFrameSatHi == 0.98f);
    {
        const int W = 192, H = 160;
        std::vector<float> rgb(size_t(W) * H * 3), eff(size_t(W) * H, 30.f);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                const float v = 0.3f + 0.05f * std::cos(6.2831853f * 0.3f * float(x + y)) + 0.02f * float(x) / W; // the checker on a ramp
                for (int c = 0; c < 3; c++) rgb[(size_t(y) * W + x) * 3 + c] = v;
                if (x >= 48 && x < 144 && y >= 40 && y < 120) eff[size_t(y) * W + x] = 4.f;  // few donors in the middle
                if (x < 24) eff[size_t(y) * W + x] = 1.f;                                    // one frame on the left
            }
        std::vector<float> one = rgb, many = rgb;
        mergeRowBandsSerial() = true;
        const size_t n1 = hybridLatticeNotch(one, W, H, eff, 0, 0, 1.f);
        mergeRowBandsSerial() = false;
        const size_t nm = hybridLatticeNotch(many, W, H, eff, 0, 0, 1.f);
        assert(n1 == nm && n1 > 0 && one == many);
        auto amp = [&](const std::vector<float>& a, int x0, int x1, int y0, int y1) { // the checker's amplitude: |mean of v cos|
            double s = 0, c = 0; int k = 0;
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                const double ph = 6.2831853 * 0.3 * double(x + y), v = a[(size_t(y) * W + x) * 3 + 1];
                s += v * std::cos(ph); c += v * std::sin(ph); ++k;
            }
            return 2.0 * std::hypot(s, c) / k;
        };
        const double before = amp(rgb, 64, 128, 56, 104), after = amp(one, 64, 128, 56, 104);
        if (!(after < 0.25 * before)) { std::fprintf(stderr, "notch: checker %f -> %f\n", before, after); return 1; }
        for (int y = 0; y < H; y++)                                                         // static and one-frame pixels
            for (int x = 0; x < W; x++)
                if (eff[size_t(y) * W + x] != 4.f && (x < 24 || x >= 160 || y < 20 || y >= 140)) {
                    const size_t i = (size_t(y) * W + x) * 3;
                    assert(one[i] == rgb[i] && one[i + 1] == rgb[i + 1] && one[i + 2] == rgb[i + 2]);
                }
    }
    // P531: the post passes on the GPU (ChromaMedianGpu::mosaicPost) = their CPU twins, the bands' order of no account
    {
        const int W = 200, H = 176, dx = 1, dy = 0;
        std::vector<float> rgb(size_t(W) * H * 3), eff(size_t(W) * H);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                const float dd = (float(y) - 88.f) * std::cos(0.4f) - (float(x) - 100.f) * std::sin(0.4f);
                const float v = 0.1f + 0.5f / (1.f + std::exp(-2.f * dd)) + 0.04f * std::cos(6.2831853f * 0.3f * float(x + y))
                                + (std::fabs(dd) < 2.f ? 0.05f * (((x / 2 + y / 2) & 1) ? 1.f : -1.f) : 0.f);
                for (int c = 0; c < 3; c++) rgb[(size_t(y) * W + x) * 3 + c] = v * (c == 0 ? 0.9f : c == 2 ? 1.1f : 1.f);
                eff[size_t(y) * W + x] = 1.f + 29.f * float((x * 7 + y * 3) % 31) / 30.f * (y > 60 && y < 130 ? 0.15f : 1.f);
            }
        std::vector<float> cpu = rgb, gpu = rgb;
        const size_t ca = hybridAlongEdge(cpu, W, H, eff, dx, dy, 3.f, 1.f), cn = hybridLatticeNotch(cpu, W, H, eff, dx, dy, 1.f);
        assert(ca > 0 && cn > 0);
        const std::array<float, 2> range = hybridLatticeNotchRange(eff);
        float cosT[kLatticeNotchPeriod], sinT[kLatticeNotchPeriod], g[2 * kLatticeNotchR + 1];
        hybridLatticeNotchTables(cosT, sinT, g);
        const float notch[3] = {range[0], range[1], 1.f};
        size_t changed[2]{};
        std::vector<float> strips = rgb;
        size_t stripChanged[2]{};
        try {
            auto ctx = chromaTake();
            ctx->mosaicPost(gpu, W, H, eff, dx, dy, 3.f, 1.f, 1, notch, cosT, sinT, g, changed);
            ctx->postStrip = 64; // three strips: the same floats
            ctx->mosaicPost(strips, W, H, eff, dx, dy, 3.f, 1.f, 1, notch, cosT, sinT, g, stripChanged);
            chromaKeep(std::move(ctx));
        } catch (const std::exception& e) {
            std::fprintf(stderr, "post passes on the GPU: %s\n", e.what());
            return 1;
        }
        {
            double d = 0; int at = -1;
            for (size_t i = 0; i < gpu.size(); i++) if (std::fabs(strips[i] - gpu[i]) > d) { d = std::fabs(strips[i] - gpu[i]); at = int(i); }
            if (d > 0 || stripChanged[0] != changed[0] || stripChanged[1] != changed[1]) {
                std::fprintf(stderr, "strips: %g at row %d col %d, along %zu / %zu, notch %zu / %zu\n", d, at / 3 / W, at / 3 % W, stripChanged[0], changed[0], stripChanged[1], changed[1]);
                return 1;
            }
        }
        double worst = 0;
        for (size_t i = 0; i < cpu.size(); i++) worst = std::max(worst, double(std::fabs(cpu[i] - gpu[i])));
        const auto off = [](size_t a, size_t b) { return a > b ? a - b : b - a; };
        if (!(worst < 2e-5 && off(changed[0], ca) <= ca / 500 + 2 && off(changed[1], cn) <= cn / 500 + 2)) {
            std::fprintf(stderr, "post passes: GPU - CPU %g, along %zu / %zu, notch %zu / %zu\n", worst, changed[0], ca, changed[1], cn);
            return 1;
        }
        // P543: the Tetra notch's spec (period 8, sigma 8, radius 24, no lower gate, coverage 0.4 .. 0.8) - the GPU = the CPU twin
        {
            assert(d.mosaicTetraLatticeNotch == 1.f);
            // P544: the Tetra merge's saturated sums where the donors were rejected (natSatU, natSatG), on by default
            assert(d.mosaicTetraFrameSat == 0.03f && d.mosaicTetraFrameSatLo == 0.8f && d.mosaicTetraFrameSatHi == 0.98f);
            std::vector<float> c3 = rgb, g3 = rgb;
            const size_t n3 = hybridLatticeNotch(c3, W, H, eff, dx, dy, 1.f, -1.f, -1.f, kTetraNotch);
            const std::array<float, 2> rT = hybridLatticeNotchRange(eff, kTetraNotch.lowFrac, kTetraNotch.highFrac);
            const float notchT[3] = {rT[0], rT[1], 1.f};
            float cT[kLatticeNotchPMax], sT[kLatticeNotchPMax], gT[2 * kLatticeNotchRMax + 1];
            hybridLatticeNotchTables(cT, sT, gT, kTetraNotch);
            size_t ch3[2]{};
            auto ctx = chromaTake();
            ctx->postStrip = 64;
            ctx->mosaicPost(g3, W, H, eff, dx, dy, 0.f, 0.f, 1, notchT, cT, sT, gT, ch3, kTetraNotch.period, kTetraNotch.R, kTetraNotch.gateLo,
                            kTetraNotch.gateHi);
            chromaKeep(std::move(ctx));
            double w3 = 0;
            for (size_t i = 0; i < c3.size(); i++) w3 = std::max(w3, double(std::fabs(c3[i] - g3[i])));
            if (!(n3 > 0 && w3 < 2e-5 && off(ch3[1], n3) <= n3 / 500 + 2 && c3 != rgb)) {
                std::fprintf(stderr, "Tetra notch: GPU - CPU %g, changed %zu / %zu\n", w3, ch3[1], n3);
                return 1;
            }
            std::printf("ok: the Tetra notch spec: GPU = CPU (%g), %zu px\n", w3, n3);
        }
        // P543: the Tetra notch takes a 4 px checker (the 4x4 blocks' lattice, < 1/4 left) off a few-donor area and keeps a diagonal texture near
        // its frequency (roof tiles at (1/16, 1/8) cycles / px, Tetra burst 1)
        {
            const int Wt = 256, Ht = 256;
            std::vector<float> chk(size_t(Wt) * Ht * 3), til(size_t(Wt) * Ht * 3), effT(size_t(Wt) * Ht, 3.f);
            for (int y = 0; y < Ht; y++)
                for (int x = 0; x < Wt; x++) {
                    // the merge's checker is a soft one (its energy at the fundamental, the harmonics ~1/10: Tetra burst 2)
                    const float c = 0.3f + 0.03f * std::cos(6.2831853f * (float(x) - 1.5f) / 8.f) * std::cos(6.2831853f * (float(y) - 1.5f) / 8.f);
                    const float t = 0.3f + 0.03f * std::cos(6.2831853f * (0.125f * float(x) + 0.0625f * float(y)));
                    for (int k = 0; k < 3; k++) { chk[(size_t(y) * Wt + x) * 3 + k] = c; til[(size_t(y) * Wt + x) * 3 + k] = t; }
                }
            for (int y = 0; y < Ht; y++) if (y < 64 || y >= 192) for (int x = 0; x < Wt; x++) effT[size_t(y) * Wt + x] = 40.f; // static rows: the band's reference
            auto amp = [&](const std::vector<float>& v) { double a = 0; int n = 0;
                for (int y = 96; y < 160; y++) for (int x = 64; x < Wt - 64; x++) { a += std::fabs(v[(size_t(y) * Wt + x) * 3 + 1] - 0.3f); n++; } return a / n; };
            const double c0 = amp(chk), t0 = amp(til);
            hybridLatticeNotch(chk, Wt, Ht, effT, 0, 0, 1.f, -1.f, -1.f, kTetraNotch);
            hybridLatticeNotch(til, Wt, Ht, effT, 0, 0, 1.f, -1.f, -1.f, kTetraNotch);
            const double c1 = amp(chk), t1 = amp(til);
            if (!(c1 < 0.25 * c0 && t1 > 0.97 * t0)) {
                std::fprintf(stderr, "Tetra notch: checker %g -> %g, tiles %g -> %g\n", c0, c1, t0, t1);
                return 1;
            }
            std::printf("ok: the Tetra notch: a 4 px checker %.4f -> %.4f, roof-like tiles %.4f -> %.4f\n", c0, c1, t0, t1);
        }
        // Tetra: the along-edge taps step 2 px (no notch), in three strips
        {
            std::vector<float> c2 = rgb, g2 = rgb;
            const size_t n2 = hybridAlongEdge(c2, W, H, eff, dx, dy, 3.f, 1.f, 2);
            const float none[3] = {0.f, 0.f, 0.f};
            size_t ch2[2]{};
            auto ctx = chromaTake();
            ctx->postStrip = 64;
            ctx->mosaicPost(g2, W, H, eff, dx, dy, 3.f, 1.f, 2, none, cosT, sinT, g, ch2);
            chromaKeep(std::move(ctx));
            double w2 = 0;
            for (size_t i = 0; i < c2.size(); i++) w2 = std::max(w2, double(std::fabs(c2[i] - g2[i])));
            if (!(n2 > 0 && c2 != rgb && w2 < 2e-5 && off(ch2[0], n2) <= n2 / 500 + 2 && ch2[1] == 0)) {
                std::fprintf(stderr, "along-edge step 2: GPU - CPU %g, %zu / %zu\n", w2, ch2[0], n2);
                return 1;
            }
        }
        // a second take of the kept context (as the chroma median after the post passes)
        auto again = chromaTake();
        chromaGiveBack(std::move(again));
    }
    // P534: a mostly clipped colour is flagged clipped (both native merge programs), 0.5 by default
    assert(d.mosaicClipShare == 0.5f && d.mosaicTetraAlongEdgeK == 25.f);
    {
        const std::string rule = "if(a.clipDen[c]>0.0&&a.clipDen[c]>=natClipShareU*(a.den[c]+a.clipDen[c]))cfl|=1u<<uint(c);}";
        const std::string generic = kHybMergeMosaic;
        assert(fast.find(rule) != std::string::npos && generic.find(rule) != std::string::npos);
        assert(fast.find("uniform float natClipShareU;") != std::string::npos && generic.find("uniform float natClipShareU;") != std::string::npos);
        // P535: the clip border bit from 2 % of the colour's weight clipped
        assert(d.mosaicBorderShare == 0.02f);
        const std::string border = "if(a.clipDen[c]>0.0&&a.clipDen[c]>=natBorderShareU*(a.den[c]+a.clipDen[c]))cfl|=8u;";
        assert(fast.find(border) != std::string::npos && generic.find(border) != std::string::npos);
    }
    // the demosaic pass sets the coverage of its pixels
    assert(fast.find("if(natDemN.w>0.5)eff[i]=mix(eff[i],1.0,t);") != std::string::npos);

    // the fast native merge: the damping goes toward natDemN (its share z), the old damping when z = 0
    assert(fast.find("uniform vec4 natDemN;") != std::string::npos);
    assert(fast.find("float nz=natDemN.z*(1.0-damp)*max(G,0.0);") != std::string::npos);
    assert(fast.find("vec3 o=vec3(G+dR*damp+nz*(natDemN.x-1.0),G,G+dB*damp+nz*(natDemN.y-1.0));") != std::string::npos);
    std::puts("check_hybrid_native_neutral: OK");
    return 0;
}
