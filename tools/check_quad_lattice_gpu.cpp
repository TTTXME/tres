// P571: ChromaMedianGpu::quadLattice against a CPU reference of the same steps (the 4 x 4 box, the clip at 2.5 sigma of the
// 32 x 32 mean of |r|, the phase means over 8 x 8 samples, less their 4 x 4 box; window ends as scipy's mode 'nearest'), on an
// image with a 2 / 4 px lattice, noise and edges, in several strips (a frame above the strip height): the floats agree to rounding,
// and the lattice falls.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <random>

using namespace scam;

static std::vector<float> reference(const std::vector<float>& rgb, int W, int H, float k) {
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
    for (size_t i = 0; i < Y.size(); ++i) { r[i] = Y[i] - b4[i]; a[i] = std::fabs(r[i]); }
    const auto s32 = box(a, -16, 15);
    for (size_t i = 0; i < Y.size(); ++i) { const float sg = k * 1.2533f * s32[i]; c[i] = std::clamp(r[i], -sg, sg); }
    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
        const int px = x & 3, py = y & 3, nx = (W - px + 3) >> 2, ny = (H - py + 3) >> 2;
        double m = 0;
        for (int dj = -4; dj <= 3; ++dj) for (int di = -4; di <= 3; ++di)
            m += c[size_t(py + 4 * std::clamp((y >> 2) + dj, 0, ny - 1)) * W + px + 4 * std::clamp((x >> 2) + di, 0, nx - 1)];
        L[size_t(y) * W + x] = float(m / 64.0);
    }
    const auto l4 = box(L, -2, 1);
    std::vector<float> out(rgb);
    for (size_t i = 0; i < Y.size(); ++i) { const float l = L[i] - l4[i]; for (int q = 0; q < 3; ++q) out[3 * i + q] -= l; }
    return out;
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
    const auto ref = reference(rgb, W, H, 2.5f);
    std::vector<float> gpu(rgb), strips(rgb);
    std::string t;
    {
        ChromaMedianGpu g;
        t = g.quadLattice(gpu, W, H);  // one strip (1024 rows)
        g.qlStrip = 96;
        g.quadLattice(strips, W, H);   // 8 strips of 96 rows: each owned row reads the image as it was
    }
    double maxd = 0, stripd = 0;
    for (size_t i = 0; i < ref.size(); ++i) { maxd = std::max(maxd, double(std::fabs(ref[i] - gpu[i]))); stripd = std::max(stripd, double(std::fabs(strips[i] - gpu[i]))); }
    std::printf("strips of 96 rows against one strip: max |d| %.3g\n", stripd);
    if (stripd > 1e-6) maxd = 1;
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
    const double before = rms(rgb, 40, 280), after = rms(gpu, 40, 280), floor = rms(rgb, 460, 690);
    std::printf("quadLattice: %s; max |gpu - reference| %.3g; neighbour difference rms %.4f -> %.4f (noise alone %.4f)\n", t.c_str(), maxd, before, after, floor);
    const bool ok = maxd < 2e-5 && before > 1.3 * floor && after < 1.1 * floor;
    std::printf(ok ? "check_quad_lattice_gpu: OK\n" : "check_quad_lattice_gpu: FAILED\n");
    return ok ? 0 : 1;
}
