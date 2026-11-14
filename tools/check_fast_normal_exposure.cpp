// P714 (Pixel 6 Pro, log 2026-11-14 13:13:05, «Быстрый», ISO 812 -> 578 over the AE ramp: «Sabre per-frame merge, 8 frames (base 0
// + 4 normal donors + 3 long; ... left out: 0 weak short, 14 other)», effective map median 6.4 of 19 ZSL normals): the fast merge's
// normal donors (fastNormalDonorExposure) on a synthetic handheld burst with that phone's noise profile (Mesa llvmpipe:
// LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless):
//  - frames 1.6 - 3.4 % darker than the base and a -0.5 EV widening are donors («0 other»); a brighter frame (x1.05) stays out;
//  - the flat area's noise falls against the gate of before (SCAM_FM_NORMAL_EXACT: |exposure - 1| < 0.02) and is no worse with a
//    -0.5 EV ring than with the exact frames alone; the static texture's error falls;
//  - a moving disc (the base's position) keeps the base's error, and its trail is no worse: no ghosting from the darker donors;
//  - the base stays frames[0] when a darker donor is the sharpest (its gain is 1, the donors' are to frames[0]).
// Build (CI): g++ -pthread -std=c++17 -O1 tools/check_fast_normal_exposure.cpp -ldl -lEGL -lGLESv2
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>
using namespace scam;

namespace {
int fails = 0;
void expect(bool ok, const std::string& what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what.c_str());
    std::fflush(stdout);
    if (!ok) ++fails;
}
struct Rng { uint64_t s; explicit Rng(uint64_t v) : s(v) {}
    uint32_t u() { s = s * 6364136223846793005ULL + 1442695040888963407ULL; return uint32_t(s >> 33); }
    float uni() { return (u() + 0.5f) / 2147483648.f; }
    float gauss() { const float a = uni(), b = uni(); return std::sqrt(-2.f * std::log(a)) * std::cos(6.2831853f * b); } };
// a smooth random texture: a bilinear grid of random values, `cell` px apart (periodic)
struct Tex { int n, cell; std::vector<float> g;
    Tex(int cells, int c, uint64_t seed) : n(cells), cell(c), g(size_t(cells) * cells) { Rng r(seed); for (auto& v : g) v = r.uni() * 2.f - 1.f; }
    float at(float x, float y) const { const float fx = x / cell, fy = y / cell; const int ix = int(std::floor(fx)), iy = int(std::floor(fy));
        const float ax = fx - ix, ay = fy - iy; auto G = [&](int i, int j) { return g[size_t(((j % n) + n) % n) * n + size_t(((i % n) + n) % n)]; };
        return (1 - ay) * ((1 - ax) * G(ix, iy) + ax * G(ix + 1, iy)) + ay * ((1 - ax) * G(ix, iy + 1) + ax * G(ix + 1, iy + 1)); } };

const int W = 512, H = 384;
const Tex t1(64, 24, 11), t2(256, 3, 12), t3(64, 5, 13);
// an indoor textured surface at 0.1 - 0.3, a flat patch (the noise), a disc moving 9 px a frame from (100, 280)
float scene(float x, float y, int c, int k, float blur) {
    float L = 0.16f * (1.f + 0.45f * t1.at(x, y)) * (1.f + 0.25f * blur * t2.at(x, y));
    if (x > 330 && y > 40 && y < 170) L = 0.14f;
    const float cx = 100.f + 9.f * k, cy = 280.f, d = std::hypot(x - cx, y - cy);
    if (d < 40.f) L = 0.35f * (1.f + 0.5f * t3.at(x - cx, y - cy));
    static const float gain[4] = {0.55f, 1.f, 1.f, 0.6f};
    return L * gain[c];
}
struct Fr { float dx, dy, e, iso; int role; float blur = 1.f; };
struct Syn { std::vector<std::vector<uint16_t>> raw; HybridInput in; };
const float S0 = 0.0016787727f, O0 = 1.2595227e-5f; // the 13:13:05 shot's base model (ISO 591, SENSOR_NOISE_PROFILE)
Syn make(const std::vector<Fr>& fs, bool noise, uint64_t seed) {
    Syn b; b.raw.resize(fs.size()); Rng r(seed);
    for (size_t f = 0; f < fs.size(); ++f) {
        const float k = fs[f].iso / 591.f, S = S0 * k, O = O0 * k * k;
        b.raw[f].resize(size_t(W) * H);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                float v = scene(x + fs[f].dx, y + fs[f].dy, ((y & 1) << 1) | (x & 1), fs[f].role == kRoleNormal ? int(f) : 0, fs[f].blur) * fs[f].e;
                if (noise) v += r.gauss() * std::sqrt(S * std::max(v, 0.f) + O);
                b.raw[f][size_t(y) * W + x] = uint16_t(std::lround(std::min(1023.f, std::max(0.f, v * 959.f + 64.f))));
            }
        HybridFrame fr; fr.role = fs[f].role; fr.exposure = fs[f].e; fr.iso = unsigned(fs[f].iso); fr.slope = S; fr.offset = O; fr.orderMs = 33.f * f;
        b.in.frames.push_back(fr);
    }
    for (size_t f = 0; f < fs.size(); ++f) b.in.frames[f].raw = b.raw[f].data();
    b.in.w = W; b.in.h = H; b.in.cfa = 0; b.in.white = 1023; b.in.black.fill(64); b.in.mosaic = 1;
    return b;
}
struct Res { std::vector<float> rgb; std::string head, select; };
Res run(const Syn& b) {
    HybridTuning t; t.fastMerge = 1; t.wbR = 0.55f; t.wbB = 0.6f;
    Res r; std::vector<uint8_t> eff;
    r.rgb = hybridFastMerge(b.in, t, [&](const std::string& l) {
        if (l.find("Sabre per-frame merge") != std::string::npos) r.head = l.substr(0, l.find(", 512x"));
        if (l.find("FrameSelector") != std::string::npos) r.select = l.substr(0, 60);
    }, nullptr, &eff, nullptr, nullptr);
    return r;
}
// G error against the truth: 0 flat patch, 1 static texture, 2 the disc at the base's position, 3 its trail
double rmse(const std::vector<float>& o, const std::vector<float>& truth, int region) {
    double s2 = 0; long n = 0;
    for (int y = 8; y < H - 8; ++y)
        for (int x = 8; x < W - 8; ++x) {
            const bool flat = x > 340 && x < 505 && y > 50 && y < 160, patch = x > 320 && y > 30 && y < 180;
            const bool disc = std::hypot(x - 100.f, y - 280.f) < 48.f, trail = y > 230 && y < 330 && x > 50 && x < 330 && !disc;
            if (!(region == 0 ? flat : region == 1 ? !patch && !disc && !trail : region == 2 ? disc : trail)) continue;
            const double d = o[(size_t(y) * W + x) * 3 + 1] - truth[(size_t(y) * W + x) * 3 + 1]; s2 += d * d; ++n;
        }
    return std::sqrt(s2 / std::max(1L, n));
}
std::vector<Fr> burst(int kind) { // 0: the AE ramp, 1: a -0.5 EV widening, 2: exact exposures (the reference)
    Rng r(7); std::vector<Fr> fs; float dx = 0, dy = 0;
    for (int i = 0; i < 12; ++i) {
        float e = 1.f, iso = 591.f;
        if (kind == 0 && i >= 3) { e = 0.966f + 0.018f * float((i * 7) % 5) / 4.f; iso = 591.f + 221.f * float(i - 3) / 8.f; }
        if (kind == 1 && i >= 3) { e = 0.71f + 0.25f * float(i % 5) / 4.f; iso = 591.f / e; }
        if (i) { dx += 0.6f * r.gauss(); dy += 0.6f * r.gauss(); }
        fs.push_back({dx, dy, e, iso, kRoleNormal});
    }
    fs.push_back({dx, dy, 2.f, 1182.f, kRoleBracketed});
    return fs;
}
} // namespace

int main() {
    setenv("SCAM_FM_MAX_DONORS", "0", 1);
    const std::vector<float> truth = run(make({burst(2)[0]}, false, 99)).rgb; // the clean base frame through the same passes
    unsetenv("SCAM_FM_MAX_DONORS");
    double exactFlat = 0;
    {
        const Res e = run(make(burst(2), true, 99));
        exactFlat = rmse(e.rgb, truth, 0);
        expect(e.head.find("left out: 0 weak short, 0 other") != std::string::npos, "exact exposures: every normal frame a donor (" + e.head + ")");
    }
    for (int kind : {0, 1}) {
        const Syn b = make(burst(kind), true, 99);
        setenv("SCAM_FM_NORMAL_EXACT", "1", 1);
        const Res before = run(b);
        unsetenv("SCAM_FM_NORMAL_EXACT");
        const Res after = run(b);
        const std::string name = kind == 0 ? "AE ramp (1.6 - 3.4 % darker)" : "-0.5 EV widening";
        expect(before.head.find(" 0 other") == std::string::npos && after.head.find("left out: 0 weak short, 0 other") != std::string::npos,
               name + ": the darker normal frames are donors now (before: " + before.head.substr(before.head.find("frames (")) + ")");
        const double fb = rmse(before.rgb, truth, 0), fa = rmse(after.rgb, truth, 0), tb = rmse(before.rgb, truth, 1), ta = rmse(after.rgb, truth, 1);
        const double db = rmse(before.rgb, truth, 2), da = rmse(after.rgb, truth, 2), rb = rmse(before.rgb, truth, 3), ra = rmse(after.rgb, truth, 3);
        char l[300];
        std::snprintf(l, sizeof(l), "%s: flat noise %.5f -> %.5f (exact frames alone %.5f), static texture %.5f -> %.5f", name.c_str(), fb, fa, exactFlat, tb, ta);
        expect(fa < 0.87 * fb && ta < 0.9 * tb && fa < 1.25 * exactFlat, l);
        std::snprintf(l, sizeof(l), "%s: the moving disc %.5f -> %.5f, its trail %.5f -> %.5f (no ghosting)", name.c_str(), db, da, rb, ra);
        expect(da <= 1.03 * db && ra <= 1.0 * rb + 1e-5, l);
    }
    { // a darker donor (x0.9) the sharpest of the burst, frames[0] softer: the base stays at the base exposure
        std::vector<Fr> fs = burst(2);
        fs[0].blur = 0.f; fs[1].blur = 0.f; fs[2].blur = 0.f;
        for (size_t i = 3; i < 12; ++i) fs[i].blur = 0.f;
        fs[5].e = 0.9f; fs[5].blur = 2.5f;
        const Syn b = make(fs, true, 5);
        const Res r = run(b);
        expect(r.select.find("base idx=0 ") != std::string::npos || r.select.find("base idx=1 ") != std::string::npos
                   || r.select.find("base idx=2 ") != std::string::npos || r.select.find("base idx=3 ") != std::string::npos
                   || r.select.find("base idx=4 ") != std::string::npos,
               "a sharper darker donor is not the base (" + r.select + ")");
    }
    { // a brighter normal frame (x1.05: its clipped sites would merge darker) stays out
        std::vector<Fr> fs = burst(2); fs[4].e = 1.05f;
        const Res r = run(make(fs, true, 3));
        expect(r.head.find("left out: 0 weak short, 1 other") != std::string::npos, "a brighter normal frame stays out (" + r.head + ")");
    }
    std::printf("check_fast_normal_exposure: %s\n", fails ? "FAILED" : "OK");
    return fails ? 1 : 0;
}
