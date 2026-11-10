// P600 (owner, 2026-11-10: «убедись что переключатель шумоподавления в настройках работает и при его отключении абсолютно весь
// шумодав отключается ПОЛНОСТЬЮ»): the worker's part of «Все шумодавы» (HybridTuning::denoise, hybridDenoiseSwitch; the app
// writes "denoise 0" only with the switch off, DenoiseStages).
// - the tuning file: "denoise 0" turns every stage of kHybridDenoiseStages off after every line (a stage's own line after it
//   too) and reports HYBRID DENOISE once; "denoise 1" or no line is the default tuning, field for field;
// - a burst on Mesa llvmpipe (plain Bayer: the base + 3 normal donors at sub-pixel offsets + 2 ultrashort frames; a flat grey wall
//   with noise, a textured part and lamps clipped in the normal frames that the ultrashort frames hold), merged by the quality
//   route with the app's Bento blend and the plain chroma median:
//   * denoise 1 written (or a second call on an applied tuning) = the default merge bit for bit;
//   * denoise 0 = the merge with exactly the registry's stages off (plainChromaMedian 0, bentoBlendChroma 0) bit for bit: the
//     switch touches nothing else; its log has the HYBRID DENOISE line and no chroma median line; its green plane is the
//     default's bit for bit away from the lamps (the median moves R and B only: the merge itself is untouched) and the flat
//     wall's colour noise (R - G, B - G) is the merge's own, above the default's;
// - the fast route and the native Quad merge (no denoise stage of their own; their lattice / moire passes stay): denoise 0 is
//   the default bit for bit.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <random>
#include <unistd.h>

using namespace scam;

static const int W = 320, H = 224, SPLIT = 160; // flat wall: x >= SPLIT (RAW px), texture and lamps on the left
static int fails = 0;
static void expect(bool ok, const std::string& what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what.c_str());
    if (!ok) ++fails;
}
static const double kCg[4] = {0.55, 1.0, 1.0, 0.75};

static double lattice(int cx, int cy) {
    uint32_t h = uint32_t(cx) * 73856093u ^ uint32_t(cy) * 19349663u;
    h ^= h >> 13; h *= 0x5bd1e995u; h ^= h >> 15;
    return 0.10 * (double(h & 0xFFFF) / 65535.0 * 2.0 - 1.0);
}
static bool lamp(double x, double y) {
    const double c[3][3] = {{48, 56, 14}, {112, 120, 10}, {64, 176, 12}};
    for (const auto& l : c) if ((x - l[0]) * (x - l[0]) + (y - l[1]) * (y - l[1]) < l[2] * l[2]) return true;
    return false;
}
static double scene(double x, double y) {
    if (x >= SPLIT) return 0.20;
    if (lamp(x, y)) return 3.0; // x3 over the normal frames' white: the ultrashort frames (1/8) hold it
    const double u = x / 2.5, v = y / 2.5;
    const int cx = int(std::floor(u)), cy = int(std::floor(v));
    const double a = u - cx, b = v - cy;
    return 0.25 + (1 - b) * ((1 - a) * lattice(cx, cy) + a * lattice(cx + 1, cy)) + b * ((1 - a) * lattice(cx, cy + 1) + a * lattice(cx + 1, cy + 1));
}
static std::vector<uint16_t> bayer(double dx, double dy, double exposure, unsigned seed, int block) {
    std::vector<uint16_t> out(size_t(W) * H);
    std::mt19937 r(seed);
    std::normal_distribution<double> n(0, 1);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            const int ph = (((y / block) & 1) << 1) | ((x / block) & 1);
            double v = scene(x + dx, y + dy) * kCg[ph] * exposure;
            v += n(r) * std::sqrt(6e-4 * std::max(v, 0.0) + 4e-6);
            out[size_t(y) * W + x] = uint16_t(std::lround(64 + std::clamp(v, 0.0, 1.0) * (4095 - 64)));
        }
    return out;
}

struct Frame { double dx, dy; int role; double exposure; };
struct Stack {
    std::vector<Frame> f;
    std::vector<std::vector<uint16_t>> raw;
    int block;
    Stack(int b, bool ultrashort) : block(b) {
        f = {{0, 0, kRoleNormal, 1}, {0.5, 0.25, kRoleNormal, 1}, {-0.75, 0.5, kRoleNormal, 1}, {0.25, -0.5, kRoleNormal, 1}};
        if (ultrashort) { f.push_back({0.25, 0.25, kRoleUltrashort, 0.125}); f.push_back({-0.25, 0.5, kRoleUltrashort, 0.125}); }
        for (size_t i = 0; i < f.size(); ++i) raw.push_back(bayer(f[i].dx, f[i].dy, f[i].exposure, 700 + unsigned(i), b));
    }
};

static std::vector<float> merge(const Stack& b, HybridTuning t, std::vector<std::string>* log = nullptr) {
    HybridInput in; in.w = W; in.h = H; in.cfa = 0; in.white = 4095; in.black.fill(64); in.mosaic = b.block;
    std::map<const uint16_t*, int> who;
    for (size_t i = 0; i < b.f.size(); ++i) {
        HybridFrame fr; fr.raw = b.raw[i].data(); fr.role = b.f[i].role; fr.exposure = float(b.f[i].exposure); fr.iso = 400;
        fr.slope = float(6e-4 / b.f[i].exposure); fr.offset = float(4e-6 / (b.f[i].exposure * b.f[i].exposure));
        fr.orderMs = float(i) * 33.f; in.frames.push_back(fr); who[fr.raw] = int(i);
    }
    ScamAlignment align = [&](scam::Burst& burst) {
        std::array<BackwardHomography, 7> result{};
        for (int s = 1; s < 7; ++s) {
            if (burst.raw[s] == burst.raw[0] && burst.exposure[s] == 1) continue;
            const auto it = who.find(burst.raw[s]);
            if (it == who.end()) continue;
            const int i = it->second;
            result[s].h = {1, 0, float(-b.f[i].dx), 0, 1, float(-b.f[i].dy), 0, 0};
        }
        return result;
    };
    t.localAlign = 0; t.gpuAlign = 0; t.caCorrect = 0; t.chromaOverlap = 0; t.hotSigma = 0; t.hotBaseSigma = 0;
    return hybridReconstruct(in, t, align, [&](const std::string& l) { if (log) log->push_back(l); }, nullptr, nullptr);
}

static bool same(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && !a.empty() && std::memcmp(a.data(), b.data(), a.size() * 4) == 0;
}
static int lines(const std::vector<std::string>& log, const char* prefix) {
    int n = 0;
    for (const auto& l : log) n += l.rfind(prefix, 0) == 0 ? 1 : 0;
    return n;
}
static int outW(const std::vector<float>& v) { return int(std::lround(std::sqrt(double(v.size() / 3) * W / H))); }
// std of R - G and B - G (colour noise) and of G over the flat wall
static std::array<double, 3> flatNoise(const std::vector<float>& a) {
    const int w = outW(a), h = int(a.size() / 3) / w, s = w / W;
    double m[3] = {0, 0, 0}, q[3] = {0, 0, 0}; long n = 0;
    for (int y = 16 * s; y < h - 16 * s; ++y)
        for (int x = (SPLIT + 16) * s; x < w - 16 * s; ++x) {
            const float* p = &a[(size_t(y) * w + x) * 3];
            const double v[3] = {double(p[0] - p[1]), double(p[2] - p[1]), double(p[1])};
            for (int c = 0; c < 3; ++c) { m[c] += v[c]; q[c] += v[c] * v[c]; }
            ++n;
        }
    std::array<double, 3> out{};
    for (int c = 0; c < 3; ++c) { m[c] /= n; out[c] = std::sqrt(std::max(q[c] / n - m[c] * m[c], 0.0)); }
    return out;
}
// the green plane bit for bit away from the lamps (8 px around them)
static bool greenSameOffLamps(const std::vector<float>& a, const std::vector<float>& b) {
    const int w = outW(a), h = int(a.size() / 3) / w, s = w / W;
    if (a.size() != b.size()) return false;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            bool near = false;
            for (int dy = -8; dy <= 8 && !near; dy += 4) for (int dx = -8; dx <= 8 && !near; dx += 4) near = lamp(double(x) / s + dx, double(y) / s + dy);
            if (near) continue;
            const size_t i = (size_t(y) * w + x) * 3 + 1;
            if (std::memcmp(&a[i], &b[i], 4) != 0) return false;
        }
    return true;
}

static HybridTuning fromFile(const std::string& text, std::vector<std::string>* log) {
    const char* temp = std::getenv("TMPDIR");
    std::string dir = std::string(temp ? temp : "/tmp") + "/scamera-denoise-XXXXXX";
    assert(mkdtemp(dir.data()) != nullptr);
    { std::ofstream f(dir + "/hybrid_tuning.txt"); f << text; }
    const HybridTuning t = loadHybridTuning(dir, [&](const std::string& l) { if (log) log->push_back(l); });
    unlink((dir + "/hybrid_tuning.txt").c_str()); rmdir(dir.c_str());
    return t;
}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    // ---- the tuning: default, the file's line, the master switch over a stage's own line
    const HybridTuning d;
    expect(d.denoise == 1 && d.plainChromaMedian == 1 && d.bentoBlendChroma == 1.f && d.mosaicTetraMotionSmooth == 1.f, "default: every denoise stage on");
    expect(sizeof(kHybridDenoiseStages) / sizeof(kHybridDenoiseStages[0]) == 3, "the registry: plainChromaMedian, bentoBlendChroma, mosaicTetraMotionSmooth");
    {
        std::vector<std::string> log;
        const HybridTuning on = fromFile("cdm 0.2\ndenoise 1\n", &log);
        expect(on.denoise == 1 && on.plainChromaMedian == d.plainChromaMedian && on.bentoBlendChroma == d.bentoBlendChroma
               && lines(log, "HYBRID DENOISE") == 0, "denoise 1: the stages as without the line, no HYBRID DENOISE line");
        log.clear();
        const HybridTuning off = fromFile("plainChromaMedian 2\ndenoise 0\nbentoBlendChroma 0.7\nplainChromaMedian 3\n", &log);
        expect(off.denoise == -1 && off.plainChromaMedian == 0 && off.bentoBlendChroma == 0.f && off.mosaicTetraMotionSmooth == 0.f,
               "denoise 0: every stage off after every line (also a stage's own line after it), applied once (-1)");
        expect(lines(log, "HYBRID DENOISE: off") == 1, "denoise 0: one HYBRID DENOISE line");
        HybridTuning again = off;
        std::vector<std::string> l2;
        expect(!hybridDenoiseSwitch(again, [&](const std::string& l) { l2.push_back(l); }) && l2.empty(), "a second application does nothing");
    }

    // ---- the quality route (plain Bayer, Bento blend, the plain chroma median)
    const Stack burst(1, true);
    HybridTuning quality; quality.bentoBlend = 1; quality.bentoRefuseScope = 1; quality.bentoBlendCellMode = 2; quality.chromaGpu = 1;
    std::vector<float> offTwin[2];
    for (const int gpu : {1, 0}) {
        HybridTuning base = quality; base.chromaGpu = gpu;
        const std::string twin = gpu ? "GPU chroma" : "CPU chroma";
        std::vector<std::string> logOn, logOff;
        const std::vector<float> on = merge(burst, base, &logOn);
        for (const auto& l : logOn) if (l.rfind("HYBRID PLAIN", 0) == 0 || l.rfind("HYBRID BENTO BLEND", 0) == 0) std::printf("  on  | %s\n", l.c_str());
        expect(lines(logOn, "HYBRID PLAIN: chroma median") == 1, twin + ", on: the plain chroma median ran");
        expect(lines(logOn, "HYBRID BENTO BLEND") >= 1, twin + ", on: the Bento blend ran (its chroma smoothing with it)");
        HybridTuning one = base; one.denoise = 1;
        expect(same(merge(burst, one), on), twin + ", denoise 1: the default merge bit for bit");
        HybridTuning off = base; off.denoise = 0;
        const std::vector<float> o = merge(burst, off, &logOff);
        for (const auto& l : logOff) if (l.rfind("HYBRID DENOISE", 0) == 0 || l.rfind("HYBRID PLAIN", 0) == 0) std::printf("  off | %s\n", l.c_str());
        expect(lines(logOff, "HYBRID DENOISE: off") == 1, twin + ", denoise 0: one HYBRID DENOISE line");
        expect(lines(logOff, "HYBRID PLAIN: chroma median") == 0, twin + ", denoise 0: no chroma median line");
        expect(!same(o, on), twin + ", denoise 0: the result changes");
        HybridTuning bare = base; bare.plainChromaMedian = 0; bare.bentoBlendChroma = 0.f;
        expect(same(o, merge(burst, bare)), twin + ", denoise 0 = the registry's stages off, bit for bit (nothing else changes)");
        expect(greenSameOffLamps(o, on), twin + ", denoise 0: green bit for bit away from the lamps (the merge untouched)");
        const auto nOn = flatNoise(on), nOff = flatNoise(o);
        std::printf("  flat wall std R-G / B-G / G: on %.5f / %.5f / %.5f, off %.5f / %.5f / %.5f\n",
                    nOn[0], nOn[1], nOn[2], nOff[0], nOff[1], nOff[2]);
        expect(nOff[0] > 1.15 * nOn[0] && nOff[1] > 1.15 * nOn[1], twin + ", denoise 0: the flat wall keeps the merge's colour noise");
        expect(nOff[2] == nOn[2], twin + ", denoise 0: the flat wall's green noise is the merge's");
        if (gpu) { // the Bento stage on its own: it acts in the lamps' colour
            HybridTuning bento = base; bento.bentoBlendChroma = 0.f;
            const std::vector<float> b = merge(burst, bento);
            expect(!same(b, on) && greenSameOffLamps(b, on), "bentoBlendChroma 0 alone: a change inside the lamps only");
        }
        offTwin[gpu] = o;
    }
    expect(same(offTwin[0], offTwin[1]), "denoise 0: the CPU and the GPU twin give the same merge (no median left to round)");

    // ---- the fast route and the native Quad merge: no denoise stage of their own
    struct Route { const char* name; int fast, block; };
    for (const Route r : {Route{"fast route", 1, 1}, Route{"native Quad", 0, 2}}) {
        const Stack stack(r.block, false);
        HybridTuning base; base.fastMerge = r.fast; base.fastMergeSelect = 0;
        std::vector<std::string> log;
        const std::vector<float> on = merge(stack, base, &log);
        if (r.fast && lines(log, "HYBRID FAST MERGE") == 0) { std::printf("%s: not taken on this host (skipped)\n", r.name); continue; }
        HybridTuning off = base; off.denoise = 0;
        std::vector<std::string> logOff;
        expect(same(merge(stack, off, &logOff), on) && lines(logOff, "HYBRID DENOISE: off") == 1,
               std::string(r.name) + ": denoise 0 is the default bit for bit (its lattice / moire passes stay)");
    }
    std::printf(fails ? "check_denoise_switch: %d FAILED\n" : "check_denoise_switch: all passed\n", fails);
    // CI #557 (llvmpipe LLVM 21.1.8): every check passed, then the process aborted in static teardown («corrupted double-linked
    // list») after the GL driver's own exit handlers; not reproduced on LLVM 20 with ASAN / MALLOC_CHECK_. The verdict is printed and
    // flushed above, so the process ends without running the static destructors (the result is the same exit code)
    std::fflush(stdout);
    std::_Exit(fails ? 1 : 0);
}
