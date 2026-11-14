// P712 (owner, 2026-11-14: «Ускорением обработки и работы занимайся углублённо ... на глаз разницы быть не должно»): the fast
// route's faster GPU programs give the photo of the programs before them, bit for bit (app/src/main/cpp/scam-fast-merge.h,
// scam-fast-gpu.h, scam-chroma-gpu.h), on synthetic bursts (Mesa llvmpipe: LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless):
//  - fmOutTile (the output pass's Quad filters on a shared-memory tile), fmGuideCells (the Quad / Tetra guide from its cells'
//    values made once), fmLatticeTiled (the Quad lattice's sums on shared memory, its medians by a network) and fmChromaFused
//    (the chroma median's box pairs in one pass each) against the previous programs (each key 0): Bayer, Quad and Tetra bursts
//    with an ultrashort frame (the highlight path, the hole fill) and a moving subject - the same floats, clip flags and frame
//    map; the faster programs ran (the merge's GPU line says so);
//  - fmKeepContext (the row bands of one merge take one context, made like a new one for each band): Quad and Tetra in forced row
//    bands against a context made per band - the same floats; the bands after the first took the kept context (Tetra without
//    its chroma passes: their context's eglTerminate ends Mesa's whole display).
// Build (CI): g++ -pthread -std=c++17 -O1 tools/check_fast_speed.cpp -ldl -lEGL -lGLESv2
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <random>
using namespace scam;

static int fails = 0;
static void expect(bool ok, const std::string& what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what.c_str());
    std::fflush(stdout);
    if (!ok) ++fails;
}

struct TestBurst {
    std::vector<std::vector<uint16_t>> raw;
    HybridInput in;
};
// a textured scene with clipped lamps and a fine neutral mesh (the Quad filters' ground); frames shifted by whole colour blocks,
// a disk moving 3 px a frame, noise, one ultrashort frame
static TestBurst burst(int W, int H, int block, unsigned seed) {
    TestBurst b;
    std::mt19937 r(seed);
    const int SW = W + 64, SH = H + 64;
    std::vector<float> img(size_t(SW) * SH, 0.12f);
    for (int y = 0; y < SH; ++y)
        for (int x = 0; x < SW; ++x) img[size_t(y) * SW + x] = 0.15f + 0.06f * std::sin(x * 0.11f) * std::cos(y * 0.07f) + 0.04f * std::sin((x + y) * 0.9f);
    for (int i = 0; i < int(size_t(SW) * SH / 1500); ++i) {
        const int cx = int(r() % SW), cy = int(r() % SH), rad = 3 + int(r() % 14);
        const float v = 0.04f + 0.5f * float(r() % 1000) / 1000.f;
        for (int y = std::max(0, cy - rad); y < std::min(SH, cy + rad); ++y)
            for (int x = std::max(0, cx - rad); x < std::min(SW, cx + rad); ++x) img[size_t(y) * SW + x] = v;
    }
    for (int y = SH / 3; y < SH / 3 + 120; ++y)
        for (int x = SW / 5; x < SW / 5 + 160; ++x) img[size_t(y) * SW + x] = ((x / 3 + y / 3) & 1) ? 0.55f : 0.25f;
    for (int i = 0; i < 4; ++i) {
        const int cx = 40 + int(r() % (SW - 80)), cy = 40 + int(r() % (SH - 80)), rad = 6 + int(r() % 18);
        for (int y = cy - rad; y < cy + rad; ++y)
            for (int x = cx - rad; x < cx + rad; ++x)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < rad * rad) img[size_t(y) * SW + x] = 4.f;
    }
    struct F { int dx, dy, role; float exposure; };
    const std::vector<F> fs{{0, 0, kRoleNormal, 1.f}, {2 * block, 0, kRoleNormal, 1.f}, {-2 * block, 2 * block, kRoleNormal, 1.f},
                            {0, -2 * block, kRoleNormal, 1.f}, {0, 0, kRoleUltrashort, 0.125f}};
    const int mcx = W / 2, mcy = H / 2, mrad = std::min(W, H) / 8;
    b.raw.resize(fs.size());
    for (size_t f = 0; f < fs.size(); ++f) {
        std::normal_distribution<float> n(0.f, 1.f);
        b.raw[f].resize(size_t(W) * H);
        const int mo = 3 * int(f);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                float v = img[size_t(y + 32 + fs[f].dy) * SW + x + 32 + fs[f].dx];
                const int ex = x - (mcx + mo), ey = y - mcy;
                if (ex * ex + ey * ey < mrad * mrad) v = 0.35f + 0.25f * std::sin(ex * 0.2f) * std::cos(ey * 0.15f);
                v *= fs[f].exposure;
                v += n(r) * std::sqrt(4e-4f * std::max(v, 0.f) + 2e-6f);
                b.raw[f][size_t(y) * W + x] = uint16_t(std::lround(64 + std::clamp(v, 0.f, 1.f) * (1023 - 64)));
            }
    }
    HybridInput& in = b.in;
    in.w = W; in.h = H; in.cfa = 0; in.white = 1023; in.black.fill(64); in.mosaic = block; in.clipFlags = true;
    for (size_t f = 0; f < fs.size(); ++f) {
        HybridFrame fr; fr.raw = b.raw[f].data(); fr.role = fs[f].role; fr.exposure = fs[f].exposure; fr.iso = 100;
        fr.slope = 4e-4f; fr.offset = 2e-6f; fr.orderMs = float(f) * 33.f;
        in.frames.push_back(fr);
    }
    return b;
}

struct Run { std::vector<float> rgb; std::vector<uint8_t> eff, clip; std::vector<std::string> log; };
static Run run(const TestBurst& b, const HybridTuning& t) {
    Run o;
    o.rgb = hybridFastRoute(b.in, t, [&](const std::string& l) { o.log.push_back(l); }, nullptr, &o.eff, nullptr, &o.clip);
    return o;
}
static bool said(const Run& r, const std::string& what) {
    for (const auto& l : r.log) if (l.find(what) != std::string::npos) return true;
    return false;
}
static int saidCount(const Run& r, const std::string& what) {
    int n = 0;
    for (const auto& l : r.log) n += l.find(what) != std::string::npos;
    return n;
}
static HybridTuning fastTune(bool faster) {
    HybridTuning t; t.fastMerge = 1; t.wbR = 0.5f; t.wbB = 0.6f;
    t.fmOutTile = t.fmGuideCells = t.fmLatticeTiled = t.fmChromaFused = faster ? 1 : 0;
    return t;
}
static std::string diff(const Run& a, const Run& b) {
    size_t n = 0; double m = 0;
    for (size_t i = 0; i < a.rgb.size() && i < b.rgb.size(); ++i) if (a.rgb[i] != b.rgb[i]) { ++n; m = std::max(m, std::fabs(double(a.rgb[i]) - b.rgb[i])); }
    char t[120]; std::snprintf(t, sizeof(t), " (%zu values differ, max %.3g)", n, m);
    return t;
}

static void programs(int block, const std::string& tag) {
    const TestBurst b = burst(1024, 768, block, 7 + unsigned(block));
    const Run before = run(b, fastTune(false)), now = run(b, fastTune(true));
    expect(!before.rgb.empty() && before.rgb.size() == now.rgb.size() && before.rgb == now.rgb, tag + ": the floats bit for bit" + diff(before, now));
    expect(before.clip == now.clip && before.eff == now.eff && !now.clip.empty(), tag + ": the clip flags and the frame map");
    if (block > 1) {
        expect(said(now, ", output tiled") && said(now, ", guide cells") && !said(before, ", output tiled") && !said(before, ", guide cells"),
               tag + ": the tiled output pass and the guide's cells ran (and not with the keys 0)");
    } else expect(!said(now, ", output tiled") && !said(now, ", guide cells"), tag + ": a Bayer stream keeps its programs");
    if (block == 2) expect(said(now, "Quad lattice off the luma"), tag + ": the Quad lattice ran");
}

static void keepContext(int block, const std::string& tag) {
    const TestBurst b = burst(1024, 1536, block, 21 + unsigned(block));
    setenv("SCAM_FM_ROW_BANDS", "512", 1);
    HybridTuning made = fastTune(true), keep = fastTune(true); made.fmKeepContext = 0;
    // (Tetra without its chroma median and few-frame pass: their context's teardown calls eglTerminate, which on Mesa ends every
    // context of the display - the kept one too - and races the next band's own context; Android counts eglInitialize calls)
    if (block == 4) made.fastMosaicChroma = keep.fastMosaicChroma = made.fastTetraPost = keep.fastTetraPost = 0;
    const Run each = run(b, made), kept = run(b, keep);
    unsetenv("SCAM_FM_ROW_BANDS");
    expect(!each.rgb.empty() && each.rgb == kept.rgb && each.clip == kept.clip && each.eff == kept.eff,
           tag + " in 3 row bands: one context through the bands == a context per band, bit for bit" + diff(each, kept));
    const int took = saidCount(kept, "kept from the band before");
    expect(saidCount(each, "kept from the band before") == 0 && took == 2,
           tag + ": the bands after the first took the kept context (" + std::to_string(took) + " of 2)");
}

int main() {
    programs(1, "Bayer");
    programs(2, "Quad");
    programs(4, "Tetra");
    keepContext(2, "Quad");
    keepContext(4, "Tetra");
    std::printf(fails ? "fast speed: %d FAILED\n" : "fast speed: ok\n", fails);
    return fails ? 1 : 0;
}
