// P685 (owner, 2026-11-14: «200мп при обработке вылетают»; vivo X200 Ultra 16320x12288 Tetra: the fast merge took ~9.5 GB of the
// phone's 6.3 GB MemAvailable «anyway»): the fast route's memory and its row bands (app/src/main/cpp/scam-fast-merge.h), on
// synthetic bursts (Mesa llvmpipe: LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless).
//  - fastMergeGpuBytes holds the merge's own count of its GPU objects (on the host every buffer is counted: no hardware buffers)
//    from above, within 10 %: Bayer and Tetra, the highlight path on and off, one pass and row strips;
//  - the plan: a merge within its share of MemAvailable stays whole; above it, bands whose estimate plus the frame's outputs fit
//    the budget; a budget the smallest bands miss leaves out the frame whose drop lowers their need the most (P699: never a donor
//    that costs a band nothing - the X300 Ultra's 50 MP lost 4 of 5 N for no byte - one short frame at a time);
//  - hybridFastRoute in row bands (forced, and by a small MemAvailable) against the whole frame's merge: the same frame pick, white
//    balance and highlight path in every band (their log lines), every finite pixel, PSNR >= 48 dB over the frame and in the seams'
//    rows, no step at a seam (the rows across it differ no more than the rows beside it), the clip flags / highlight mask rows equal
//    to the whole frame's in >= 99 % of the places; the halves streamed into a memfd (StreamOut's sparse preparation) equal the
//    halves of the floats the same bands give without the stream, byte for byte.
// Build (CI): g++ -pthread -std=c++17 -O1 tools/check_fast_row_bands.cpp -ldl -lEGL -lGLESv2
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include "../app/src/main/cpp/scam-stream-out.h"
#include <cstdio>
#include <random>
#include <sys/mman.h>
#include <sys/stat.h>
using namespace scam;

static int fails = 0;
static void expect(bool ok, const std::string& what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what.c_str());
    std::fflush(stdout);
    if (!ok) ++fails;
}

struct TestBurst {
    int w = 0, h = 0, block = 1;
    std::vector<std::vector<uint16_t>> raw;
    HybridInput in;
};
// a textured scene with clipped lamps; frames shifted by whole pixels (the colour blocks' lattice kept), noise, one ultrashort frame
static TestBurst burst(int W, int H, int block, bool ultrashort, unsigned seed) {
    TestBurst b; b.w = W; b.h = H; b.block = block;
    std::mt19937 r(seed);
    const int SW = W + 64, SH = H + 64;
    std::vector<float> img(size_t(SW) * SH, 0.12f);
    for (int i = 0; i < int(size_t(SW) * SH / 1200); ++i) {
        const int cx = int(r() % SW), cy = int(r() % SH), rad = 3 + int(r() % 14);
        const float v = 0.04f + 0.5f * float(r() % 1000) / 1000.f;
        for (int y = std::max(0, cy - rad); y < std::min(SH, cy + rad); ++y)
            for (int x = std::max(0, cx - rad); x < std::min(SW, cx + rad); ++x) img[size_t(y) * SW + x] = v;
    }
    for (int i = 0; i < int(size_t(SW) * SH / 80000) + 3; ++i) {
        const int cx = 40 + int(r() % (SW - 80)), cy = 40 + int(r() % (SH - 80)), rad = 6 + int(r() % 18);
        for (int y = cy - rad; y < cy + rad; ++y)
            for (int x = cx - rad; x < cx + rad; ++x)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < rad * rad) img[size_t(y) * SW + x] = 4.f;
    }
    struct F { int dx, dy, role; float exposure; };
    std::vector<F> fs{{0, 0, kRoleNormal, 1.f}, {2 * block, 0, kRoleNormal, 1.f}, {-2 * block, 2 * block, kRoleNormal, 1.f}};
    if (ultrashort) fs.push_back({0, -2 * block, kRoleUltrashort, 0.125f});
    b.raw.resize(fs.size());
    for (size_t f = 0; f < fs.size(); ++f) {
        std::normal_distribution<float> n(0.f, 1.f);
        b.raw[f].resize(size_t(W) * H);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                float v = img[size_t(y + 32 + fs[f].dy) * SW + x + 32 + fs[f].dx] * fs[f].exposure;
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

static HybridTuning fastTune() {
    HybridTuning t; t.fastMerge = 1; t.wbR = 0.5f; t.wbB = 0.6f;
    return t;
}

// the merge's own count of its GPU objects, from its MEMORY line
static double gpuMbOf(const std::vector<std::string>& log) {
    for (const auto& l : log) {
        const size_t at = l.find("HYBRID FAST MERGE MEMORY: GPU textures and buffers ");
        if (at != std::string::npos) return std::atof(l.c_str() + at + 51);
    }
    return -1;
}

static void estimate() {
    struct Case { int w, h, block; bool us; int strip; };
    for (const Case c : {Case{768, 512, 1, true, 0}, Case{768, 512, 1, false, 0}, Case{768, 512, 4, true, 0}, Case{1024, 768, 2, true, 192}}) {
        TestBurst b = burst(c.w, c.h, c.block, c.us, 5);
        if (c.strip) setenv("SCAM_FM_STRIP_ROWS", std::to_string(c.strip).c_str(), 1);
        std::vector<std::string> log;
        std::vector<uint8_t> clip;
        hybridFastMerge(b.in, fastTune(), [&](const std::string& l) { log.push_back(l); }, nullptr, nullptr, nullptr, &clip);
        unsetenv("SCAM_FM_STRIP_ROWS");
        bool bento = false;
        for (const auto& l : log) bento = bento || l.find("highlight path on") != std::string::npos;
        FastMemShape s; s.w = c.w; s.h = c.h; s.ow = c.w; s.oh = c.h; s.loopFrames = 2; s.shorts = c.us && bento ? 1 : 0; s.bento = s.shorts > 0;
        s.quad = c.block > 1; s.stripRows = c.strip; s.clipFlags = true;
        const double est = double(fastMergeGpuBytes(s)) / (1 << 20), got = gpuMbOf(log);
        char t[200];
        std::snprintf(t, sizeof(t), "estimate %dx%d block %d%s%s: %.1f MB against the merge's %.0f MB", c.w, c.h, c.block, s.bento ? ", highlight path" : "",
                      c.strip ? ", row strips" : "", est, got);
        // (the merge's line rounds to whole MB)
        expect(got > 0 && est >= got - 0.5 && est <= 1.1 * got + 1.0, t);
    }
}

static void plan() {
    TestBurst b = burst(1024, 8192, 1, true, 7); // (not merged: tall enough for bands far smaller than the frame)
    const HybridTuning t = fastTune();
    unsetenv("SCAM_FM_ROW_BANDS");
    setenv("SCAM_FM_MEMAVAIL_MB", "100000", 1);
    FastRowPlan p = fastRowPlan(b.in, t, 1, false, false, true);
    expect(!p.bands, "plan: plenty of memory: the whole frame");
    const uint64_t whole = p.whole;
    setenv("SCAM_FM_MEMAVAIL_MB", std::to_string(int(double(whole) / 0.84 / (1 << 20)) + 1).c_str(), 1);
    p = fastRowPlan(b.in, t, 1, false, false, true);
    expect(!p.bands, "plan: just within 85 % of MemAvailable: the whole frame");
    setenv("SCAM_FM_MEMAVAIL_MB", std::to_string(int(double(whole) / 1.2 / (1 << 20))).c_str(), 1);
    p = fastRowPlan(b.in, t, 1, false, false, true);
    expect(p.bands && p.fits && p.count >= 2 && p.fixed + p.band <= p.budget && p.keepDonors < 0 && !p.dropShorts,
           "plan: above it: " + std::to_string(p.count) + " bands of " + std::to_string(p.rows) + " rows within the budget, every frame");
    setenv("SCAM_FM_MEMAVAIL_MB", "60", 1);
    p = fastRowPlan(b.in, t, 1, false, false, true);
    expect(p.bands && (p.keepDonors >= 0 || p.dropShorts || !p.fits), "plan: a budget the smallest bands miss: frames left out (or said so)");
    // P699: the smallest bands fit with every donor once the short frame is out: the donors (no row strips: no cost) all stay
    {
        const int most = std::max(2, b.h / kFastRowMinCore), core = ((b.h + most - 1) / most + kFastRowStep - 1) / kFastRowStep * kFastRowStep;
        const int rows = std::min(b.h, core + 2 * kFastBandMargin);
        const uint64_t noShort = fastMergeBytes(fastShapeOf(b.in, t, 1, rows, 2, 0, true, false, true)),
                       withShort = fastMergeBytes(fastShapeOf(b.in, t, 1, rows, 2, 1, true, false, true)),
                       noDonor = fastMergeBytes(fastShapeOf(b.in, t, 1, rows, 0, 1, true, false, true));
        expect(noDonor == withShort && noShort < withShort, "plan: without row strips a donor costs a band nothing, a short frame does");
        const uint64_t fixed = p.fixed;
        const int mb = int(double(fixed + noShort) / kFastMemBands / (1 << 20)) + 2;
        setenv("SCAM_FM_MEMAVAIL_MB", std::to_string(mb).c_str(), 1);
        p = fastRowPlan(b.in, t, 1, false, false, true);
        const bool shortMisses = double(fixed + withShort) > kFastMemBands * double(uint64_t(mb) << 20);
        expect(!shortMisses || (p.bands && p.fits && p.keepDonors < 0 && p.dropShorts && p.keepShorts == 0),
               "plan: " + std::to_string(mb) + " MB: the short frame out, every donor kept (keepDonors " + std::to_string(p.keepDonors)
               + ", keepShorts " + std::to_string(p.keepShorts) + ")");
    }
    unsetenv("SCAM_FM_MEMAVAIL_MB");
}

static double psnr(const std::vector<float>& a, const std::vector<float>& b, int W, int y0, int y1) {
    double se = 0, peak = 0; size_t n = 0;
    for (size_t i = size_t(y0) * W * 3; i < size_t(y1) * W * 3; ++i) { const double d = double(a[i]) - b[i]; se += d * d; peak = std::max(peak, double(a[i])); ++n; }
    return se > 0 ? 10 * std::log10(std::min(peak, 1.0) * std::min(peak, 1.0) / (se / double(n))) : 200;
}
static double rowStep(const std::vector<float>& v, int W, int y) { // mean |row y - row y-1|
    double s = 0;
    for (int x = 0; x < W * 3; ++x) s += std::fabs(double(v[size_t(y) * W * 3 + x]) - v[size_t(y - 1) * W * 3 + x]);
    return s / (W * 3);
}

static void bands(int block) {
    const int W = 1024, H = 3072;
    TestBurst b = burst(W, H, block, true, 9);
    const HybridTuning t = fastTune();
    const std::string tag = block == 4 ? "Tetra" : "Bayer";
    std::vector<std::string> wlog;
    std::vector<uint8_t> wclip; std::vector<uint16_t> whl;
    hybridHighlightMaskOut() = &whl;
    unsetenv("SCAM_FM_ROW_BANDS");
    const std::vector<float> whole = hybridFastRoute(b.in, t, [&](const std::string& l) { wlog.push_back(l); }, nullptr, nullptr, nullptr, &wclip);
    // the same burst in row bands of 1024 rows (3 bands, 256-row margins: the smallest bands the plan makes), floats
    setenv("SCAM_FM_ROW_BANDS", "1024", 1);
    std::vector<std::string> blog;
    std::vector<uint8_t> bclip; std::vector<uint16_t> bhl;
    hybridHighlightMaskOut() = &bhl;
    const std::vector<float> banded = hybridFastRoute(b.in, t, [&](const std::string& l) { blog.push_back(l); }, nullptr, nullptr, nullptr, &bclip);
    hybridHighlightMaskOut() = nullptr;
    int bandLines = 0, wbLines = 0, pathOn = 0, picks = 0;
    std::string wbWhole;
    for (const auto& l : wlog) if (l.rfind("HYBRID FAST MERGE: Sabre per-frame merge", 0) == 0) wbWhole = l.substr(l.find(", wb "), 18);
    for (const auto& l : blog) {
        bandLines += l.rfind("HYBRID FAST MERGE ROW BANDS (P685): 1024x3072 in 3 row bands", 0) == 0;
        if (l.rfind("[row band ", 0) == 0 && l.find("Sabre per-frame merge") != std::string::npos) {
            wbLines += l.find(wbWhole) != std::string::npos; pathOn += l.find("highlight path on") != std::string::npos;
        }
        picks += l.rfind("[row band ", 0) == 0 && l.find("(the whole frame's pick: column bands)") != std::string::npos;
    }
    expect(bandLines == 1 && wbLines == 3 && pathOn == 3 && picks == 3, tag + ": 3 row bands, each with the whole frame's pick, white balance ("
           + wbWhole + ") and highlight path");
    expect(banded.size() == whole.size() && whole.size() == size_t(W) * H * 3, tag + ": the result's size");
    bool finite = true; for (float v : banded) finite = finite && std::isfinite(v);
    expect(finite, tag + ": every value finite");
    const double all = psnr(whole, banded, W, 0, H);
    double seams = 200, stepMax = 0, stepRef = 0;
    for (int s : {1024, 2048}) {
        seams = std::min(seams, psnr(whole, banded, W, s - 48, s + 48));
        stepMax = std::max(stepMax, rowStep(banded, W, s) - rowStep(whole, W, s));
        stepRef = std::max(stepRef, std::fabs(rowStep(banded, W, s - 100) - rowStep(whole, W, s - 100)));
    }
    char line[220];
    std::snprintf(line, sizeof(line), "%s: banded against whole PSNR %.1f dB, in the seams' rows %.1f dB (>= 48)", tag.c_str(), all, seams);
    expect(all >= 48 && seams >= 48, line);
    std::snprintf(line, sizeof(line), "%s: no step at a seam (its rows' difference %.5f over the whole frame's, %.5f beside it)", tag.c_str(), stepMax, stepRef);
    expect(stepMax <= std::max(2 * stepRef, 2e-3), line);
    size_t same = 0; for (size_t i = 0; i < wclip.size() && i < bclip.size(); ++i) same += wclip[i] == bclip[i];
    expect(bclip.size() == wclip.size() && same >= wclip.size() * 99 / 100, tag + ": the clip flags agree in " + std::to_string(100.0 * same / std::max<size_t>(1, wclip.size())) + " %");
    size_t hsame = 0; for (size_t i = 8; i < whl.size() && i < bhl.size(); ++i) hsame += std::fabs(hybridHalfToFloat(whl[i]) - hybridHalfToFloat(bhl[i])) < 0.02f;
    expect(whl.size() > 8 && bhl.size() == whl.size() && std::equal(bhl.begin(), bhl.begin() + 8, whl.begin()) && hsame >= (whl.size() - 8) * 99 / 100,
           tag + ": the highlight mask (its header, " + std::to_string(100.0 * hsame / std::max<size_t>(1, whl.size() - 8)) + " % within 0.02)");
    // the stream: the same bands' halves into a memfd (sparse), against the halves of the floats above
    {
        const int fd = memfd_create("rows", 0);
        StreamOut out(dup(fd));
        hybridFinalHalvesHook() = [&](const uint16_t* hv, int w, int h, int y0, int y1, const float* id) { return out.halves(hv, w, h, y0, y1, id); };
        std::vector<std::string> slog;
        hybridStreamedFloats() = 0;
        std::vector<uint8_t> sclip; std::vector<uint16_t> shl;
        hybridHighlightMaskOut() = &shl;
        const std::vector<float> streamed = hybridFastRoute(b.in, t, [&](const std::string& l) { slog.push_back(l); }, nullptr, nullptr, nullptr, &sclip);
        hybridHighlightMaskOut() = nullptr;
        hybridFinalHalvesHook() = nullptr;
        double s = 0; float m = 0;
        const bool took = streamed.empty() && hybridStreamedFloats() == whole.size() && out.take(streamed.data(), hybridStreamedFloats(), s, m);
        hybridStreamedFloats() = 0;
        bool same16 = took;
        if (took) {
            std::vector<uint16_t> got(whole.size());
            same16 = pread(fd, got.data(), got.size() * 2, 0) == ssize_t(got.size() * 2);
            for (size_t i = 0; same16 && i < got.size(); ++i) same16 = got[i] == floatToHalf(banded[i]);
        }
        close(fd);
        expect(took && same16 && sclip == bclip && shl == bhl, tag + ": the streamed halves (sparse memfd) == the banded floats' halves, byte for byte (the trailers too)");
    }
    unsetenv("SCAM_FM_ROW_BANDS");
}

int main() {
    estimate();
    plan();
    bands(1);
    bands(4);
    std::printf(fails ? "fast row bands: %d FAILED\n" : "fast row bands: ok\n", fails);
    return fails ? 1 : 0;
}
