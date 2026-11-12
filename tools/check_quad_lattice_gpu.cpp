// P571: ChromaMedianGpu::quadLattice against a CPU reference of the same steps (the 4 x 4 box, the clip at 2.5 sigma of the
// 32 x 32 mean of |r|, the phase means over 8 x 8 samples, less their 4 x 4 box; window ends as scipy's mode 'nearest'), on an
// image with a 2 / 4 px lattice, noise and edges, in several strips (a frame above the strip height): the floats agree to rounding,
// and the lattice falls.
// P632: both units (relative to the luma's 4 x 4 box, the default, and P591's luma units) against the reference; and a synthetic
// Quad scene - dark branches (0.06) across a bright sky (0.6 .. 0.7), the same relative 4 x 4 site lattice on both: the grid
// energy (the rms of the 4 x 4 phase means of log Y less its 4 x 4 box) on the branches' interior falls in relative units, while
// luma units print the sky's lattice on them several times larger (the grid of burst 142159 on «Быстрый»).
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <algorithm>
#include <cstdio>
#include <random>

using namespace scam;

static std::vector<float> reference(const std::vector<float>& rgb, int W, int H, float k, bool rel) {
    auto at = [&](const std::vector<float>& v, int x, int y) { return v[size_t(std::clamp(y, 0, H - 1)) * W + std::clamp(x, 0, W - 1)]; };
    auto box = [&](const std::vector<float>& v, int r0, int r1) { // mean over [x + r0, x + r1] x [y + r0, y + r1]
        std::vector<float> o(v.size());
        for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
            double m = 0; for (int dy = r0; dy <= r1; ++dy) for (int dx = r0; dx <= r1; ++dx) m += at(v, x + dx, y + dy);
            o[size_t(y) * W + x] = float(m / double((r1 - r0 + 1) * (r1 - r0 + 1)));
        }
        return o;
    };
    std::vector<float> Y(size_t(W) * H), r(Y.size()), a(Y.size()), c(Y.size()), L(Y.size());
    for (size_t i = 0; i < Y.size(); ++i) Y[i] = 0.25f * rgb[3 * i] + 0.5f * rgb[3 * i + 1] + 0.25f * rgb[3 * i + 2];
    const auto b4 = box(Y, -2, 1);
    for (size_t i = 0; i < Y.size(); ++i) {
        r[i] = rel ? (b4[i] > 1.0e-4f ? Y[i] / b4[i] - 1.f : 0.f) : Y[i] - b4[i]; // P632: relative to the box
        a[i] = std::fabs(r[i]);
    }
    const auto s32 = box(a, -16, 15);
    for (size_t i = 0; i < Y.size(); ++i) { const float sg = k * 1.2533f * s32[i]; c[i] = std::clamp(r[i], -sg, sg); }
    // P591: the median of 8 phase samples along x, then of 8 along y (the GPU's med8: the mean of the middle two)
    auto med8 = [](float v[8]) { std::sort(v, v + 8); return 0.5f * (v[3] + v[4]); };
    std::vector<float> Mx(Y.size());
    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
        const int px = x & 3, nx = (W - px + 3) >> 2;
        float v[8];
        for (int di = -4; di <= 3; ++di) v[di + 4] = c[size_t(y) * W + px + 4 * std::clamp((x >> 2) + di, 0, nx - 1)];
        Mx[size_t(y) * W + x] = med8(v);
    }
    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
        const int py = y & 3, ny = (H - py + 3) >> 2;
        float v[8];
        for (int dj = -4; dj <= 3; ++dj) v[dj + 4] = Mx[size_t(py + 4 * std::clamp((y >> 2) + dj, 0, ny - 1)) * W + x];
        L[size_t(y) * W + x] = med8(v);
    }
    const auto l4 = box(L, -2, 1);
    std::vector<float> out(rgb);
    for (size_t i = 0; i < Y.size(); ++i) {
        const float l = L[i] - l4[i], Yi = Y[i];
        if (rel) { const float g = std::clamp(1.f - l, 0.25f, 1.75f); for (int q = 0; q < 3; ++q) out[3 * i + q] *= g; }
        else if (Yi > 1.0e-4f) { const float g = std::clamp((Yi - l) / Yi, 0.25f, 1.75f); for (int q = 0; q < 3; ++q) out[3 * i + q] *= g; }
        else for (int q = 0; q < 3; ++q) out[3 * i + q] -= l;
    }
    return out;
}

// P632: the grid energy of a synthetic Quad scene's dark branches across a bright sky, before and after the pass in both units
static bool branches() {
    const int W = 256, H = 320;
    const float lat[4][4] = {{0.02f, -0.01f, 0.015f, -0.02f}, {-0.01f, 0.01f, -0.015f, 0.012f}, {0.01f, -0.02f, 0.01f, 0.0f}, {-0.02f, 0.015f, -0.01f, 0.012f}};
    std::mt19937 rng(632); std::normal_distribution<float> n01(0.f, 1.f); std::uniform_real_distribution<float> u01(0.f, 1.f);
    std::vector<uint8_t> br(size_t(W) * H, 0);
    for (int k = 0; k < 14; ++k) { // straight branches 3 - 8 px wide at any angle
        const float th = 3.14159265f * u01(rng), cx = W * u01(rng), cy = H * u01(rng), hw = 1.5f + 2.5f * u01(rng);
        for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x)
            if (std::fabs((x - cx) * std::sin(th) - (y - cy) * std::cos(th)) < hw) br[size_t(y) * W + x] = 1;
    }
    auto at = [&](int x, int y) { x = std::clamp(x, 0, W - 1); y = std::clamp(y, 0, H - 1); return br[size_t(y) * W + x] ? 0.06f : 0.6f + 0.1f * x / W; };
    std::vector<float> rgb(size_t(W) * H * 3);
    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
        float m = 0; for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) m += at(x + dx, y + dy); // soft edges
        const float v = m / 9.f * (1.f + lat[y & 3][x & 3]);
        for (int q = 0; q < 3; ++q) rgb[(size_t(y) * W + x) * 3 + q] = v * (q == 1 ? 1.f : 0.8f) + 0.0005f * n01(rng);
    }
    // interiors: every pixel of a 5 x 5 (branch) / 7 x 7 (sky) neighbourhood the same, 40 px off the frame's edges
    auto interior = [&](bool branch, int r) {
        std::vector<uint8_t> m(br.size(), 0);
        for (int y = 40; y < H - 40; ++y) for (int x = 40; x < W - 40; ++x) {
            bool all = true;
            for (int dy = -r; dy <= r && all; ++dy) for (int dx = -r; dx <= r && all; ++dx) all = (br[size_t(y + dy) * W + x + dx] != 0) == branch;
            m[size_t(y) * W + x] = all;
        }
        return m;
    };
    const auto mb = interior(true, 2), ms = interior(false, 3);
    // the rms of the 4 x 4 phase means (less their mean) of r = log Y - its 4 x 4 box over the mask
    auto energy = [&](const std::vector<float>& v, const std::vector<uint8_t>& mask) {
        std::vector<float> L(size_t(W) * H);
        for (size_t i = 0; i < L.size(); ++i) L[i] = std::log(std::max(0.25f * v[3 * i] + 0.5f * v[3 * i + 1] + 0.25f * v[3 * i + 2], 1e-5f));
        double sum[16] = {}, n[16] = {};
        for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
            if (!mask[size_t(y) * W + x]) continue;
            double b = 0;
            for (int dy = -2; dy <= 1; ++dy) for (int dx = -2; dx <= 1; ++dx) b += L[size_t(std::clamp(y + dy, 0, H - 1)) * W + std::clamp(x + dx, 0, W - 1)];
            const int p = (y & 3) * 4 + (x & 3);
            sum[p] += L[size_t(y) * W + x] - b / 16.0; n[p] += 1;
        }
        double mean = 0, e = 0;
        for (int p = 0; p < 16; ++p) { sum[p] /= std::max(n[p], 1.0); mean += sum[p] / 16; }
        for (int p = 0; p < 16; ++p) e += (sum[p] - mean) * (sum[p] - mean) / 16;
        return std::sqrt(e);
    };
    std::vector<float> rel(rgb), units(rgb);
    {
        ChromaMedianGpu g;
        g.quadLattice(rel, W, H, 2.5f, true);
        g.quadLattice(units, W, H, 2.5f, false);
    }
    const double b0 = energy(rgb, mb), s0 = energy(rgb, ms), b1 = energy(rel, mb), s1 = energy(rel, ms), b2 = energy(units, mb), s2 = energy(units, ms);
    std::printf("branches across the sky, grid energy (log luma): branches %.4f -> relative %.4f / luma units %.4f; sky %.4f -> %.4f / %.4f\n",
                b0, b1, b2, s0, s1, s2);
    // relative: the lattice falls on both; luma units: the sky's lattice several times larger on the branches (P632's cause)
    return b1 < 0.5 * b0 && s1 < 0.3 * s0 && b2 > 2.0 * b0;
}

int main() {
    const int W = 256, H = 700;
    std::mt19937 rng(571); std::normal_distribution<float> n01(0.f, 1.f);
    std::vector<float> rgb(size_t(W) * H * 3);
    const float lat[4][4] = {{0.02f, -0.01f, 0.015f, -0.02f}, {-0.01f, 0.01f, -0.015f, 0.012f}, {0.01f, -0.02f, 0.01f, 0.0f}, {-0.02f, 0.015f, -0.01f, 0.012f}};
    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
        const float base = (x > 100 && y > 300 ? 0.6f : 0.2f) + 0.05f * std::sin(x * 0.03f);
        const float v = base * (1.f + (y < 450 ? lat[y & 3][x & 3] : 0.f));
        for (int q = 0; q < 3; ++q) rgb[(size_t(y) * W + x) * 3 + q] = v * (q == 1 ? 1.f : 0.8f) + 0.004f * n01(rng);
    }
    std::vector<float> result;
    double maxd = 0;
    std::string t;
    for (const bool rel : {true, false}) {
        const auto ref = reference(rgb, W, H, 2.5f, rel);
        std::vector<float> gpu(rgb), strips(rgb);
        {
            ChromaMedianGpu g;
            t = g.quadLattice(gpu, W, H, 2.5f, rel);  // one strip (1024 rows)
            g.qlStrip = 96;
            g.quadLattice(strips, W, H, 2.5f, rel);   // 8 strips of 96 rows: each owned row reads the image as it was
        }
        double md = 0, sd = 0;
        for (size_t i = 0; i < ref.size(); ++i) { md = std::max(md, double(std::fabs(ref[i] - gpu[i]))); sd = std::max(sd, double(std::fabs(strips[i] - gpu[i]))); }
        std::printf("%s: max |gpu - reference| %.3g; strips of 96 rows against one strip: max |d| %.3g\n", rel ? "relative" : "luma units", md, sd);
        maxd = std::max(maxd, md);
        if (sd > 1e-6) maxd = 1;
        if (rel) result = std::move(gpu);
    }
    // the lattice's energy in the lattice rows (flat part) before and after
    // the neighbour difference in a flat lattice area (rows 40..280) against the same in a flat area without it (rows 460..690):
    // the noise floor
    auto rms = [&](const std::vector<float>& v, int ya, int yb) {
        double e = 0; int n = 0;
        for (int y = ya; y < yb; ++y) for (int x = 20; x < 90; ++x) {
            const size_t i = (size_t(y) * W + x) * 3 + 1, j = (size_t(y) * W + x + 1) * 3 + 1;
            e += double(v[i] - v[j]) * double(v[i] - v[j]); ++n;
        }
        return std::sqrt(e / n);
    };
    const double before = rms(rgb, 40, 280), after = rms(result, 40, 280), floor = rms(rgb, 460, 690);
    std::printf("quadLattice: %s; max |gpu - reference| %.3g; neighbour difference rms %.4f -> %.4f (noise alone %.4f)\n", t.c_str(), maxd, before, after, floor);
    bool ok = maxd < 2e-5 && before > 1.3 * floor && after < 1.1 * floor;
    ok = branches() && ok;
    std::printf(ok ? "check_quad_lattice_gpu: OK\n" : "check_quad_lattice_gpu: FAILED\n");
    return ok ? 0 : 1;
}
