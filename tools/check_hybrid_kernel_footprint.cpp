// P587 (owner, 2026-11-09, after ArkCam's cov_base / cov_blurred / cov_trans: «Добавь такие параметры к нам»): the texture /
// bokeh footprints of the merge kernel (HybridTuning::kernelTexture / kernelBokeh / kernelTransition).
// - the tuning keys, the clamp (0.25 .. 4; 0 / NaN = 1) and that factors of 1 change no uniform;
// - a burst on Mesa llvmpipe (the base + 4 normal donors at sub-pixel offsets; the left part a fine random texture, the right
//   part a flat grey with noise, out of focus), merged by the Sabre 6.1 kernel and the round-4 kernel (the SCAM merge's guide)
//   and, where it runs, the fast route:
//   * every factor at 1, written in the tuning: the output bit for bit the default merge's;
//   * texture != 1 changes the textured part, the flat part (almost) not; bokeh != 1 the flat part, the textured part (almost) not;
//   * texture 2 smooths the texture (less energy of the fine detail than texture 0.5); bokeh 2 lowers the flat part's noise
//     against bokeh 0.5; transition 4 (more of the frame takes the bokeh kernel) acts like bokeh in the flat part.
//   (these with kernelSnr 0: the static footprints, as P587 measured them)
// P647: the footprints that follow the burst's SNR key (HybridTuning::kernelSnr*, hybridKernelFootprintSnr):
// - the curves: 1 at the pivot key, the day / night values beyond kernelSnrDay / kernelSnrNight, continuous, monotone on each side
//   of the pivot, flat beyond both ends; Tetra (block 4) never on the night side; the fast route's transition x kernelSnrFastNight
//   at night; the key = one site's key x the block; the user's factors multiply the automatic ones; kernelSnr 0 = the user's alone
//   (the same object, the same merge bit for bit as automatic factors of 1);
// - the burst: by day (this noise, key ~42) the texture's detail up against kernelSnr 0, at night (noise x100, site key ~4.1) the flat
//   part's noise down against kernelSnr 0, every route; one log line a shot with the key and the values used.
// P647b: the day side per colour block / route (Bayer and the fast Quad route keep the texture kernel, the Quad SCAM merge 0.92,
// the fast Quad route 0.85 across edges, the fast Bayer route its own ramp / across-edge factor / packing lift, the Bayer SCAM
// transition 1.25), the curves and keys of those; a Bayer burst of few frames keeps its kernel by day (the 5-frame burst: bit for
// bit kernelSnr 0's merge by day), while the narrowing itself (frames gate off, texture x0.85) still lifts the texture's detail.
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

static const int W = 320, H = 192, SPLIT = 160; // texture: x < SPLIT, flat: x >= SPLIT (RAW px)
static double TEX_CELL = std::getenv("FP_CELL") ? std::atof(std::getenv("FP_CELL")) : 2.0;
static double NOISE = std::getenv("FP_NOISE") ? std::atof(std::getenv("FP_NOISE")) : 1e-4;
static const double kCg[4] = {0.55, 1.0, 1.0, 0.75};

// the scene in RAW px: a fixed random texture on the left (values +-0.12 on a 2 px lattice, bilinear between them: detail at the
// Nyquist band the kernel shapes, yet continuous, so the sub-pixel donors are not rejected), 0.30 grey on the right
static double lattice(int cx, int cy) {
    uint32_t hsh = uint32_t(cx) * 73856093u ^ uint32_t(cy) * 19349663u;
    hsh ^= hsh >> 13; hsh *= 0x5bd1e995u; hsh ^= hsh >> 15;
    return 0.12 * (double(hsh & 0xFFFF) / 65535.0 * 2.0 - 1.0);
}
static double scene(double x, double y) {
    if (x >= SPLIT) return 0.30;
    const double u = x / TEX_CELL, v = y / TEX_CELL;
    const int cx = int(std::floor(u)), cy = int(std::floor(v));
    const double a = u - cx, b = v - cy;
    return 0.30 + (1 - b) * ((1 - a) * lattice(cx, cy) + a * lattice(cx + 1, cy)) + b * ((1 - a) * lattice(cx, cy + 1) + a * lattice(cx + 1, cy + 1));
}
static std::vector<uint16_t> bayer(double dx, double dy, unsigned seed, int block) {
    std::vector<uint16_t> out(size_t(W) * H);
    std::mt19937 r(seed);
    std::normal_distribution<double> n(0, 1);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            const int ph = (((y / block) & 1) << 1) | ((x / block) & 1);
            double v = scene(x + dx, y + dy) * kCg[ph];
            v += n(r) * std::sqrt(NOISE * v + 1e-6);
            out[size_t(y) * W + x] = uint16_t(std::lround(64 + std::clamp(v, 0.0, 1.0) * (4095 - 64)));
        }
    return out;
}

struct Stack {
    std::vector<std::array<double, 2>> d{{0, 0}, {0.5, 0.25}, {-0.75, 0.5}, {0.25, -0.5}, {-0.5, -0.75}};
    std::vector<std::vector<uint16_t>> raw;
    int block;
    double noise = NOISE; // the frames' noise model (NOISE when the stack was made)
    // move: the donors' texture moved by that many px against what the alignment says (a subject moving over the static flat part)
    explicit Stack(int b, double move = 0) : block(b) {
        for (size_t f = 0; f < d.size(); ++f) raw.push_back(bayer(d[f][0] + (f ? move * double(f) : 0), d[f][1], 500 + unsigned(f), b));
    }
};

static std::vector<float> merge(const Stack& b, HybridTuning t, std::vector<std::string>* log = nullptr) {
    HybridInput in; in.w = W; in.h = H; in.cfa = 0; in.white = 4095; in.black.fill(64); in.mosaic = b.block;
    std::map<const uint16_t*, int> who;
    for (size_t f = 0; f < b.d.size(); ++f) {
        HybridFrame fr; fr.raw = b.raw[f].data(); fr.role = kRoleNormal; fr.exposure = 1; fr.iso = 400; fr.slope = float(b.noise); fr.offset = 1e-6f;
        fr.orderMs = float(f) * 33.f; in.frames.push_back(fr); who[fr.raw] = int(f);
    }
    ScamAlignment align = [&](scam::Burst& burst) {
        std::array<BackwardHomography, 7> result{};
        for (int s = 1; s < 7; ++s) {
            if (burst.raw[s] == burst.raw[0] && burst.exposure[s] == 1) continue;
            const auto it = who.find(burst.raw[s]);
            if (it == who.end()) continue;
            const int f = it->second;
            result[s].h = {1, 0, float(-b.d[f][0]), 0, 1, float(-b.d[f][1]), 0, 0};
        }
        return result;
    };
    t.localAlign = 0; t.gpuAlign = 0; t.caCorrect = 0; t.chromaGpu = 0; t.chromaOverlap = 0; t.hotSigma = 0; t.hotBaseSigma = 0;
    return hybridReconstruct(in, t, align, [&](const std::string& l) { if (log) log->push_back(l); }, nullptr, nullptr);
}

// the result's green plane (3 channels per pixel, its own size): mean |a - b| and the fine-detail energy (|Laplacian|) over a part
struct Part { int x0, x1; };
static int outW(const std::vector<float>& v) { return int(std::lround(std::sqrt(double(v.size() / 3) * W / H))); }
static double diff(const std::vector<float>& a, const std::vector<float>& b, Part p) {
    const int w = outW(a), h = int(a.size() / 3) / w, s = w / W;
    double acc = 0; long n = 0;
    for (int y = 16 * s; y < h - 16 * s; ++y)
        for (int x = p.x0 * s; x < p.x1 * s; ++x) { acc += std::fabs(a[(size_t(y) * w + x) * 3 + 1] - b[(size_t(y) * w + x) * 3 + 1]); ++n; }
    return acc / n;
}
static double detail(const std::vector<float>& a, Part p) {
    const int w = outW(a), h = int(a.size() / 3) / w, s = w / W;
    auto g = [&](int x, int y) { return double(a[(size_t(y) * w + x) * 3 + 1]); };
    double acc = 0; long n = 0;
    for (int y = 16 * s; y < h - 16 * s; ++y)
        for (int x = p.x0 * s; x < p.x1 * s; ++x) { acc += std::fabs(4 * g(x, y) - g(x - 1, y) - g(x + 1, y) - g(x, y - 1) - g(x, y + 1)); ++n; }
    return acc / n;
}
static double stddev(const std::vector<float>& a, Part p) {
    const int w = outW(a), h = int(a.size() / 3) / w, s = w / W;
    double m = 0, q = 0; long n = 0;
    for (int y = 16 * s; y < h - 16 * s; ++y)
        for (int x = p.x0 * s; x < p.x1 * s; ++x) { const double v = a[(size_t(y) * w + x) * 3 + 1]; m += v; q += v * v; ++n; }
    m /= n; return std::sqrt(std::max(q / n - m * m, 0.0));
}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    // ---- keys, clamp, identity of the uniforms at 1
    const HybridTuning d;
    assert(d.kernelTexture == 1.f && d.kernelBokeh == 1.f && d.kernelTransition == 1.f);
    assert(!hybridKernelFootprint(d).active());
    {
        const char* temp = std::getenv("TMPDIR");
        std::string dir = std::string(temp ? temp : "/tmp") + "/scamera-footprint-XXXXXX";
        assert(mkdtemp(dir.data()) != nullptr);
        { std::ofstream f(dir + "/hybrid_tuning.txt"); f << "kernelTexture 0.5\nkernelBokeh 2.5\nkernelTransition 9\n"; }
        const HybridTuning t = loadHybridTuning(dir, [](const std::string&) {});
        unlink((dir + "/hybrid_tuning.txt").c_str()); rmdir(dir.c_str());
        assert(t.kernelTexture == 0.5f && t.kernelBokeh == 2.5f && t.kernelTransition == 9.f);
        const HybridKernelFootprint fp = hybridKernelFootprint(t);
        assert(fp.active() && fp.texture == 0.5f && fp.bokeh == 2.5f && fp.transition == 4.f); // clamped to 4
        HybridTuning z; z.kernelTexture = 0.f; z.kernelBokeh = std::nanf(""); z.kernelTransition = 0.01f;
        const HybridKernelFootprint fz = hybridKernelFootprint(z);
        assert(fz.texture == 1.f && fz.bokeh == 1.f && fz.transition == 0.25f);
        std::array<float, 4> a{2.2f / 0.33f, 1.f / (0.33f * 4.f), 0.002f, 1.f / 0.33f}, b{1.f / (0.33f * 5.f), 1.f / 0.015f, 1.f, 1.f};
        const auto a0 = a, b0 = b;
        hybridKernelFootprint(d).apply61(a, b);
        assert(std::memcmp(a.data(), a0.data(), sizeof(a)) == 0 && std::memcmp(b.data(), b0.data(), sizeof(b)) == 0);
        fp.apply61(a, b);
        assert(a[0] == a0[0] && a[1] == a0[1] && a[3] == a0[3] / 0.5f && b[0] == b0[0] / 2.5f);
        assert(a[2] == a0[2] * 4.f && b[1] == b0[1] / 4.f && b[2] == b0[2] && b[3] == b0[3]);
        SuperResTuning k; k.base = 0.4f; k.shrunk = 0.3f; k.stretched = 1.2f; k.flat = 1.5f; k.flat0 = 0.01f; k.flat1 = 0.02f;
        const SuperResTuning k0 = k;
        hybridKernelFootprint(d).applyRound4(k);
        assert(std::memcmp(&k, &k0, sizeof(k)) == 0);
        fp.applyRound4(k);
        assert(k.base == k0.base * 0.5f && k.flat == k0.flat * 2.5f && k.shrunk == k0.shrunk && k.stretched == k0.stretched);
        assert(k.flat0 == k0.flat0 * 4.f && k.flat1 == k0.flat1 * 4.f);
    }

    // ---- the burst
    const Stack burst(1), quad(2);
    const Part tex{24, SPLIT - 24}, flat{SPLIT + 24, W - 24};
    struct Route { const char* name; int sabre61, fast, block; };
    int routes = 0;
    for (const Route r : {Route{"Sabre 6.1", 1, 0, 1}, Route{"round 4", 0, 0, 1}, Route{"fast route", 1, 1, 1}, Route{"native Quad", 1, 0, 2}}) {
        HybridTuning base; base.sabre61 = r.sabre61; base.fastMerge = r.fast; base.fastMergeSelect = 0;
        base.kernelSnr = 0;           // P647: the static footprints (the automatic ones are checked below)
        base.mosaicQuadTexture = 1.f; // P596: the user's factors alone here (the Quad default x0.5 is checked below)
        base.mosaicQuadFine = 0.f;    // P596: on the quad tensor this synthetic texture is «texture» (the green-site one reads it as edges)
        std::vector<std::string> log;
        const Stack& stack = r.block == 2 ? quad : burst;
        const std::vector<float> ref = merge(stack, base, &log);
        if (r.fast) {
            bool fast = false;
            for (const auto& l : log) fast |= l.rfind("HYBRID FAST MERGE", 0) == 0;
            if (!fast) { std::printf("%s: not taken on this host (skipped)\n", r.name); continue; }
        }
        assert(!ref.empty());
        for (const auto& l : log) if (std::getenv("FP_LOG") || l.rfind("HYBRID KERNEL", 0) == 0 || l.rfind("HYBRID FAST MERGE:", 0) == 0) std::printf("  | %s\n", l.c_str());
        HybridTuning ones = base; ones.kernelTexture = 1.f; ones.kernelBokeh = 1.f; ones.kernelTransition = 1.f;
        const std::vector<float> same = merge(stack, ones);
        assert(same.size() == ref.size() && std::memcmp(same.data(), ref.data(), ref.size() * 4) == 0);
        auto with = [&](float t, float b, float tr) {
            HybridTuning x = base; x.kernelTexture = t; x.kernelBokeh = b; x.kernelTransition = tr;
            std::vector<std::string> l;
            auto out = merge(stack, x, &l);
            bool said = false;
            for (const auto& s : l) said |= s.find("ootprint texture x") != std::string::npos;
            assert(said && out.size() == ref.size());
            return out;
        };
        const auto t05 = with(0.5f, 1.f, 1.f), t2 = with(2.f, 1.f, 1.f), b05 = with(1.f, 0.5f, 1.f), b2 = with(1.f, 2.f, 1.f), tr4 = with(1.f, 1.f, 4.f);
        const double t05T = diff(t05, ref, tex), t05F = diff(t05, ref, flat), t2T = diff(t2, ref, tex), t2F = diff(t2, ref, flat);
        const double b05T = diff(b05, ref, tex), b05F = diff(b05, ref, flat), b2T = diff(b2, ref, tex), b2F = diff(b2, ref, flat);
        const double tr4T = diff(tr4, ref, tex), tr4F = diff(tr4, ref, flat);
        std::printf("%s: |change| texture/flat part: texture x0.5 %.3g/%.3g, x2 %.3g/%.3g; bokeh x0.5 %.3g/%.3g, x2 %.3g/%.3g; transition x4 %.3g/%.3g\n",
                    r.name, t05T, t05F, t2T, t2F, b05T, b05F, b2T, b2F, tr4T, tr4F);
        std::printf("%s: detail of the texture: ref %.4f, texture x0.5 %.4f, x2 %.4f, transition x4 %.4f; flat noise: ref %.5f, bokeh x0.5 %.5f, x2 %.5f, transition x4 %.5f\n",
                    r.name, detail(ref, tex), detail(t05, tex), detail(t2, tex), detail(tr4, tex), stddev(ref, flat), stddev(b05, flat), stddev(b2, flat), stddev(tr4, flat));
        // the texture factor acts in the texture, the bokeh factor in the flat part; directions: a wider texture kernel smooths the
        // texture, a wider bokeh kernel lowers the flat noise. Round 4 reads most of this texture as coherent (its tensor less the
        // noise bias: the across / along sigmas, not the base) and its daylight base sigma (0.16 px x0.84 here) is already below the
        // lattice, so x0.5 there changes next to nothing (the nearest sites carry the weight) and leaks into the half-flat part.
        assert(t2T > 4 * t2F && t2T > 1e-3 && detail(t2, tex) < 0.9 * detail(ref, tex));
        // (the fast route's flat part is less flat to its own noise LUT: x0.5 leaks more into it there)
        if (r.sabre61) assert(t05T > (r.fast ? 2 : 4) * t05F && t05T > 1e-3 && detail(t05, tex) > 1.05 * detail(ref, tex));
        else assert(t05T + t05F < 0.2 * t2T && detail(t05, tex) >= detail(ref, tex) - 1e-6);
        assert(b05F > 20 * b05T && b2F > 20 * b2T && b05F > 2e-5 && b2F > 2e-5);
        assert(stddev(b2, flat) < stddev(ref, flat) && stddev(ref, flat) < stddev(b05, flat));
        // a higher transition moves more of the frame onto the bokeh kernel: the flat part's noise falls and the texture's detail
        // with it (where the flat part is already wholly bokeh - the native merge's flat kernel x2.4 - only the texture changes)
        assert(tr4F + tr4T > 2e-5 && stddev(tr4, flat) <= stddev(ref, flat) && detail(tr4, tex) <= detail(ref, tex));
        ++routes;
    }
    assert(routes >= 3); // the fast route may be skipped on a host that cannot take it
    // P596: the Quad stream's own texture factor (HybridTuning::mosaicQuadTexture, default 0.5) on top of the user's 1: said, and the
    // output not the factor-1 merge's; a Bayer stream ignores it (bit for bit)
    {
        HybridTuning q; q.sabre61 = 1; q.fastMerge = 0; q.fastMergeSelect = 0; q.mosaicQuadFine = 0.f; q.kernelSnr = 0;
        assert(q.mosaicQuadTexture == 0.5f);
        std::vector<std::string> l;
        const std::vector<float> qd = merge(quad, q, &l);
        bool said = false;
        for (const auto& s : l) said |= s.find("footprint texture x0.5") != std::string::npos;
        HybridTuning q1 = q; q1.mosaicQuadTexture = 1.f;
        const std::vector<float> q1d = merge(quad, q1);
        assert(said && qd.size() == q1d.size() && std::memcmp(qd.data(), q1d.data(), qd.size() * 4) != 0);
        assert(detail(qd, tex) > detail(q1d, tex));
        HybridTuning b = q, b1 = q1;
        const std::vector<float> bd = merge(burst, b), b1d = merge(burst, b1);
        assert(bd.size() == b1d.size() && std::memcmp(bd.data(), b1d.data(), bd.size() * 4) == 0);
        std::printf("P596 native Quad texture x0.5 by default: detail %.4f -> %.4f; Bayer unchanged\n", detail(q1d, tex), detail(qd, tex));
        // round 5: the narrow kernel back to the user's below mosaicQuadTextureFrames (at most 3/4 of the donors) accepted donor
        // frames - said; this static burst's texture as without the gate (its flat part's noise-rejected donors a few 1e-5 off);
        // a subject moving over the texture (its donors rejected there) close to the factor-1 merge's
        assert(q.mosaicQuadTextureFrames == 24.f);
        bool gated = false;
        for (const auto& s : l) gated |= s.find("Quad texture kernel back to the user's below") != std::string::npos;
        HybridTuning q0 = q; q0.mosaicQuadTextureFrames = 0.f;
        const std::vector<float> q0d = merge(quad, q0);
        const double sT = diff(qd, q0d, tex), sF = diff(qd, q0d, flat);
        assert(gated && q0d.size() == qd.size() && sT < 0.02 * diff(q0d, q1d, tex) && sF < 1e-4);
        const Stack moving(2, 3.25);
        const std::vector<float> mg = merge(moving, q), m0 = merge(moving, q0), m1 = merge(moving, q1);
        const double g1 = diff(mg, m1, tex), n1 = diff(m0, m1, tex);
        std::printf("P596 round 5: static burst |change| from the ungated merge: texture %.3g, flat %.3g (the narrow kernel's own change "
                    "%.3g); a moving texture's |change| from the factor-1 merge: narrow %.3g, gated %.3g\n", sT, sF, diff(q0d, q1d, tex), n1, g1);
        assert(n1 > 1e-4 && g1 < 0.5 * n1);
    }
    // ---- P647: the footprints that follow the SNR key
    {
        const HybridTuning t;
        assert(t.kernelSnr == 1 && t.kernelSnrNight < t.kernelSnrPivot && t.kernelSnrPivot < t.kernelSnrDay && t.kernelSnrDayEdge < 1.f);
        {   // the across-edge factor on the uniforms: Sabre 6.1 k61a[0] / edge, round 4 shrunk x edge; nothing else
            HybridKernelFootprint e; e.edge = 0.8f;
            std::array<float, 4> a{6.f, 1.f, 0.002f, 3.f}, b{0.6f, 66.f, 1.f, 1.f};
            const auto a0 = a, b0 = b;
            e.apply61(a, b);
            assert(e.active() && a[0] == a0[0] / 0.8f && a[1] == a0[1] && a[2] == a0[2] && a[3] == a0[3] && std::memcmp(b.data(), b0.data(), sizeof(b)) == 0);
            SuperResTuning k; k.shrunk = 0.3f; k.base = 0.4f;
            e.applyRound4(k);
            assert(k.shrunk == 0.3f * 0.8f && k.base == 0.4f);
        }
        {
            const char* temp = std::getenv("TMPDIR");
            std::string dir = std::string(temp ? temp : "/tmp") + "/scamera-footprint-XXXXXX";
            assert(mkdtemp(dir.data()) != nullptr);
            { std::ofstream f(dir + "/hybrid_tuning.txt"); f << "kernelSnr 0\nkernelSnrNight 4\nkernelSnrPivot 12\nkernelSnrDay 50\nkernelSnrDayTexture 0.7\n"
                  "kernelSnrDayBokeh 1.3\nkernelSnrDayTransition 1.4\nkernelSnrNightTexture 1.3\nkernelSnrNightBokeh 1.1\nkernelSnrNightTransition 2.5\nkernelSnrFastNight 5\n"
                  "kernelSnrDayEdge 0.9\nmosaicQuadTextureNight 0.8\nkernelSnrDayTextureBayer 0.95\nkernelSnrDayTextureQuadFast 0.9\n"
                  "kernelSnrDayEdgeBayerFast 1.2\nkernelSnrDayFastLiftBayer 2.5\nkernelSnrFastBayerFrom 20\nkernelSnrFastBayerTo 70\nkernelSnrWiden 0.25\nkernelSnrDayTransitionBayer 1.1\nkernelSnrDayTextureQuad 0.8\nkernelSnrDayEdgeQuadFast 0.95\nkernelSnrDayFramesLo 3\nkernelSnrDayFramesHi 5\n"; }
            const HybridTuning l = loadHybridTuning(dir, [](const std::string&) {});
            unlink((dir + "/hybrid_tuning.txt").c_str()); rmdir(dir.c_str());
            assert(l.kernelSnr == 0 && l.kernelSnrNight == 4.f && l.kernelSnrPivot == 12.f && l.kernelSnrDay == 50.f && l.kernelSnrDayTexture == 0.7f);
            assert(l.kernelSnrDayBokeh == 1.3f && l.kernelSnrDayTransition == 1.4f && l.kernelSnrNightTexture == 1.3f && l.kernelSnrNightBokeh == 1.1f);
            assert(l.kernelSnrNightTransition == 2.5f && l.kernelSnrFastNight == 5.f && l.kernelSnrDayEdge == 0.9f && l.mosaicQuadTextureNight == 0.8f);
            assert(l.kernelSnrDayTextureBayer == 0.95f && l.kernelSnrDayTextureQuadFast == 0.9f && l.kernelSnrDayEdgeBayerFast == 1.2f && l.kernelSnrDayFastLiftBayer == 2.5f);
            assert(l.kernelSnrFastBayerFrom == 20.f && l.kernelSnrFastBayerTo == 70.f && l.kernelSnrWiden == 0.25f && l.kernelSnrDayTransitionBayer == 1.1f && l.kernelSnrDayTextureQuad == 0.8f && l.kernelSnrDayEdgeQuadFast == 0.95f && l.kernelSnrDayFramesLo == 3.f && l.kernelSnrDayFramesHi == 5.f);
        }
        // P647b: by day the texture narrows on Tetra and the Quad SCAM merge only; the fast Bayer route's day side from its own keys
        assert(t.kernelSnrDayTexture < 1.f && t.kernelSnrDayTextureQuad < 1.f && t.kernelSnrDayTextureBayer == 1.f && t.kernelSnrDayTextureQuadFast == 1.f && t.kernelSnrDayEdgeQuadFast < 1.f);
        assert(t.kernelSnrFastBayerFrom > t.kernelSnrPivot && t.kernelSnrFastBayerTo > t.kernelSnrFastBayerFrom && t.kernelSnrDayFastLiftBayer > 1.f);
        // the curves over keys 0.5 .. 1000: 1 at the pivot, the end values beyond the ends, monotone on each side, continuous
        for (int fast = 0; fast < 2; ++fast)
            for (int block : {1, 2, 4}) {
                const bool bayerFast = fast && block == 1;
                const float dayTexture = block == 4 ? t.kernelSnrDayTexture : block == 2 ? (fast ? t.kernelSnrDayTextureQuadFast : t.kernelSnrDayTextureQuad) : t.kernelSnrDayTextureBayer;
                const float dayEdge = bayerFast ? t.kernelSnrDayEdgeBayerFast : fast && block == 2 ? t.kernelSnrDayEdgeQuadFast : t.kernelSnrDayEdge, dayEnd = bayerFast ? t.kernelSnrFastBayerTo : t.kernelSnrDay;
                const float dayTransition = block == 1 && !fast ? t.kernelSnrDayTransitionBayer : t.kernelSnrDayTransition;
                HybridKernelFootprint prev{};
                float prevKey = 0.f;
                for (int i = 0; i <= 4000; ++i) {
                    const float key = 0.5f * std::pow(2000.f, float(i) / 4000.f);
                    const HybridKernelFootprint f = hybridSnrFactors(t, key, block, fast != 0);
                    const HybridKernelFootprint s = hybridKernelFootprintSnr(t, key / float(block), block, fast != 0);
                    assert(std::fabs(s.texture - f.texture) < 1e-6f * 4 && std::fabs(s.bokeh - f.bokeh) < 1e-6f * 4 && std::fabs(s.transition - f.transition) < 1e-5f);
                    assert(s.edge == f.edge && s.lift == f.lift);
                    // the fast packing lift: the fast route only; 1 / edge^2 where the kernel narrows across edges, the fast Bayer route its own
                    if (!fast) assert(f.lift == 1.f);
                    else if (!bayerFast) assert(f.edge < 1.f ? std::fabs(f.lift - 1.f / (f.edge * f.edge)) < 1e-5f : f.lift == 1.f);
                    else assert(f.lift >= 1.f && f.lift <= t.kernelSnrDayFastLiftBayer + 1e-6f);
                    // the fast Bayer route: every factor 1 from the pivot up to kernelSnrFastBayerFrom
                    if (bayerFast && key >= t.kernelSnrPivot && key <= t.kernelSnrFastBayerFrom)
                        assert(f.texture == 1.f && f.bokeh == 1.f && f.transition == 1.f && f.edge == 1.f && f.lift == 1.f);
                    if (i) {
                        // continuous: no step between neighbouring keys (0.2 % apart) above 1 % of the range
                        assert(std::fabs(f.texture - prev.texture) < 0.01f && std::fabs(f.bokeh - prev.bokeh) < 0.01f && std::fabs(f.transition - prev.transition) < 0.05f);
                        assert(std::fabs(f.edge - prev.edge) < 0.01f && std::fabs(f.lift - prev.lift) < 0.05f);
                        const bool day = prevKey >= t.kernelSnrPivot;
                        // texture: narrower towards the day, wider towards the night (both sides: non-increasing in the key)
                        assert(f.texture <= prev.texture + 1e-6f);
                        // bokeh / transition: away from 1 towards each end (day: up to the day value; night: up to the night value)
                        // the kernel across edges: towards its day value (narrower; the fast Bayer route wider with its lift), the lift up
                        if (day) assert(f.bokeh >= prev.bokeh - 1e-6f && f.transition >= prev.transition - 1e-6f && f.lift >= prev.lift - 1e-6f
                                        && (dayEdge <= 1.f ? f.edge <= prev.edge + 1e-6f : f.edge >= prev.edge - 1e-6f));
                        else if (key <= t.kernelSnrPivot) assert(f.bokeh <= prev.bokeh + 1e-6f && f.transition <= prev.transition + 1e-6f);
                    }
                    if (key >= dayEnd)
                        assert(f.texture == dayTexture && f.bokeh == t.kernelSnrDayBokeh && f.transition == dayTransition && f.edge == dayEdge
                               && (!bayerFast || f.lift == t.kernelSnrDayFastLiftBayer));
                    if (key <= t.kernelSnrPivot) assert(f.edge == 1.f); // the kernel across edges: the day side only
                    if (key <= t.kernelSnrNight) {
                        if (block == 4) assert(f.texture == 1.f && f.bokeh == 1.f && f.transition == 1.f); // Tetra: the day side only
                        else assert(f.texture == t.kernelSnrNightTexture && f.bokeh == t.kernelSnrNightBokeh
                                    && std::fabs(f.transition - t.kernelSnrNightTransition * (fast ? t.kernelSnrFastNight : 1.f)) < 1e-5f);
                    }
                    prev = f; prevKey = key;
                }
                const HybridKernelFootprint p = hybridSnrFactors(t, t.kernelSnrPivot, block, fast != 0);
                assert(p.texture == 1.f && p.bokeh == 1.f && p.transition == 1.f && p.edge == 1.f && p.lift == 1.f);
            }
        // P647b: a Bayer burst's day side x smoothstep over kernelSnrDayFramesLo .. Hi frames (1 below Lo, unchanged from Hi, unknown
        // frames 0 = unchanged); Quad / Tetra and the night side never
        assert(t.kernelSnrDayFramesLo > 1.f && t.kernelSnrDayFramesHi > t.kernelSnrDayFramesLo);
        for (int fast = 0; fast < 2; ++fast)
            for (int block : {1, 2, 4})
                for (float key : {5.f, 20.f, 45.f, 200.f}) {
                    const HybridKernelFootprint all = hybridSnrFactors(t, key, block, fast != 0), none = hybridSnrFactors(t, key, block, fast != 0, 0);
                    assert(all.texture == none.texture && all.edge == none.edge && all.lift == none.lift && all.transition == none.transition);
                    float prevDist = -1.f;
                    for (int n = 1; n <= 30; ++n) {
                        const HybridKernelFootprint g = hybridSnrFactors(t, key, block, fast != 0, n);
                        const float dist = std::fabs(g.texture - 1.f) + std::fabs(g.bokeh - 1.f) + std::fabs(g.transition - 1.f) + std::fabs(g.edge - 1.f) + std::fabs(g.lift - 1.f);
                        if (block != 1 || key <= t.kernelSnrPivot || n >= t.kernelSnrDayFramesHi)
                            assert(g.texture == all.texture && g.bokeh == all.bokeh && g.transition == all.transition && g.edge == all.edge && g.lift == all.lift);
                        else if (n <= t.kernelSnrDayFramesLo) assert(dist == 0.f);
                        assert(dist >= prevDist - 1e-6f); prevDist = dist;
                    }
                }
        // the key: one site's x the block (Quad 2, Tetra 4; anything else 1); a key that is not a number keeps the factors at 1
        assert(hybridSnrFootprintKey(10.f, 1) == 10.f && hybridSnrFootprintKey(10.f, 2) == 20.f && hybridSnrFootprintKey(10.f, 4) == 40.f && hybridSnrFootprintKey(10.f, 3) == 10.f);
        for (float bad : {0.f, -3.f, std::nanf(""), INFINITY}) { const auto f = hybridSnrFactors(t, bad, 1, true); assert(f.texture == 1.f && f.bokeh == 1.f && f.transition == 1.f && f.edge == 1.f && f.lift == 1.f); }
        // the user's factors multiply the automatic ones (the product within 0.25 .. 4, the transition 0.25 .. 16); kernelSnr 0: the user's
        HybridTuning u = t; u.kernelTexture = 0.5f; u.kernelBokeh = 2.f; u.kernelTransition = 3.f;
        for (float key : {3.f, 14.f, 25.f, 80.f}) {
            const HybridKernelFootprint a = hybridSnrFactors(u, key, 1, true), f = hybridKernelFootprintSnr(u, key, 1, true);
            assert(f.edge == a.edge && f.lift == a.lift && f.texture == std::clamp(0.5f * a.texture, 0.25f, 4.f) && f.bokeh == std::clamp(2.f * a.bokeh, 0.25f, 4.f) && f.transition == std::clamp(3.f * a.transition, 0.25f, 16.f));
            HybridTuning off = u; off.kernelSnr = 0;
            std::string line;
            const HybridKernelFootprint o = hybridKernelFootprintSnr(off, key, 1, true, &line), uo = hybridKernelFootprint(off);
            assert(o.texture == uo.texture && o.bokeh == uo.bokeh && o.transition == uo.transition && line.find("off") != std::string::npos);
        }
        // P666: the day side's transition / bokeh are undone in the shadows (the guide's quad luma under kernelSnrShadowHi): set only
        // on the day side (a key above the pivot) with kernelSnrShadow, the values the footprint widens by; kernelSnrShadow 0 = none,
        // every other factor the same
        for (int block : {1, 2, 4})
            for (int fast = 0; fast < 2; ++fast)
                for (float key : {3.f, 8.f, 14.f, 20.f, 40.f, 120.f}) {
                    const HybridKernelFootprint f = hybridSnrFactors(t, key, block, fast != 0);
                    if (key <= t.kernelSnrPivot) assert(!f.shade() && f.shade61()[0] == 0.f);
                    if (f.shade()) assert(f.shadeT == std::max(f.transition, 1.f) && f.shadeB == std::max(f.bokeh, 1.f) && f.shadeLo == t.kernelSnrShadowLo && f.shadeHi == t.kernelSnrShadowHi);
                    HybridTuning s0 = t; s0.kernelSnrShadow = 0;
                    const HybridKernelFootprint g = hybridSnrFactors(s0, key, block, fast != 0);
                    assert(!g.shade() && g.texture == f.texture && g.bokeh == f.bokeh && g.transition == f.transition && g.edge == f.edge && g.lift == f.lift);
                }
        assert(hybridSnrFactors(t, 120.f, 1, false).shade() && hybridSnrFactors(t, 120.f, 2, true).shade());
        {
            HybridTuning o = t; o.kernelSnr = 0;
            assert(!hybridKernelFootprintSnr(o, 120.f, 1, false).shade());
            std::string line;
            hybridKernelFootprintSnr(t, 120.f, 1, false, &line);
            assert(line.find("the shadows undo transition") != std::string::npos);
        }
        // the Quad texture factor (P596's mosaicQuadTexture) by day, up to max(it, mosaicQuadTextureNight) at night; never narrower at
        // night than by day; kernelSnr 0 / no key: P596's factor
        {
            assert(t.mosaicQuadTexture == 0.5f && t.mosaicQuadTextureNight > t.mosaicQuadTexture);
            float prevQ = 10.f;
            for (int i = 0; i <= 2000; ++i) {
                const float key = 0.5f * std::pow(2000.f, float(i) / 2000.f), q = hybridQuadTextureFactor(t, key);
                assert(q <= prevQ + 1e-6f && q >= t.mosaicQuadTexture - 1e-6f && q <= t.mosaicQuadTextureNight + 1e-6f && std::fabs(q - prevQ) < 0.01f + (i ? 0.f : 10.f));
                if (key >= t.kernelSnrPivot) assert(q == t.mosaicQuadTexture);
                if (key <= t.kernelSnrNight) assert(std::fabs(q - t.mosaicQuadTextureNight) < 1e-6f);
                prevQ = q;
            }
            HybridTuning q0 = t; q0.kernelSnr = 0;
            HybridTuning q1 = t; q1.mosaicQuadTexture = 1.f;
            for (float key : {2.f, 9.f, 30.f}) {
                assert(hybridQuadTextureFactor(q0, key) == 0.5f && hybridQuadTextureFactor(q1, key) == 1.f);
                HybridKernelFootprint a{}, b{};
                hybridQuadTexture(a, q0); hybridQuadTexture(b, q0, key);
                assert(a.texture == b.texture);
            }
            assert(hybridQuadTextureFactor(t, std::nanf("")) == 0.5f);
        }
        HybridTuning ones = t; ones.kernelSnrDayTexture = ones.kernelSnrDayBokeh = ones.kernelSnrDayTransition = 1.f;
        ones.kernelSnrNightTexture = ones.kernelSnrNightBokeh = ones.kernelSnrNightTransition = ones.kernelSnrFastNight = 1.f;
        ones.mosaicQuadTextureNight = 0.f; ones.kernelSnrDayEdge = 1.f; ones.kernelSnrDayEdgeBayerFast = ones.kernelSnrDayFastLiftBayer = ones.kernelSnrDayTransitionBayer = ones.kernelSnrDayTextureQuad = ones.kernelSnrDayEdgeQuadFast = 1.f;
        for (float key : {2.f, 30.f, 200.f}) assert(!hybridKernelFootprintSnr(ones, key, 1, true).active());

        // the burst: day (NOISE, key ~42) and night (x100, key ~4.1; the Quad block key ~8.3), every route: on against off, off = automatic factors of 1 bit for bit
        const double day = NOISE;
        NOISE = 100 * day;
        const Stack nightBurst(1), nightQuad(2);
        NOISE = day;
        int checked = 0;
        for (const Route r : {Route{"Sabre 6.1", 1, 0, 1}, Route{"round 4", 0, 0, 1}, Route{"fast route", 1, 1, 1}, Route{"native Quad", 1, 0, 2}}) {
            HybridTuning on; on.sabre61 = r.sabre61; on.fastMerge = r.fast; on.fastMergeSelect = 0; on.mosaicQuadFine = 0.f;
            HybridTuning off = on; off.kernelSnr = 0;
            HybridTuning flat1 = ones; flat1.sabre61 = r.sabre61; flat1.fastMerge = r.fast; flat1.fastMergeSelect = 0; flat1.mosaicQuadFine = 0.f;
            for (int night = 0; night < 2; ++night) {
                const Stack& st = night ? (r.block == 2 ? nightQuad : nightBurst) : (r.block == 2 ? quad : burst);
                std::vector<std::string> lOn, lOff;
                const std::vector<float> a = merge(st, on, &lOn), b = merge(st, off, &lOff), c = merge(st, flat1);
                if (r.fast) {
                    bool fast = false;
                    for (const auto& l : lOn) fast |= l.rfind("HYBRID FAST MERGE", 0) == 0;
                    if (!fast) { std::printf("P647 %s: not taken on this host (skipped)\n", r.name); break; }
                }
                assert(!a.empty() && a.size() == b.size() && b.size() == c.size());
                assert(std::memcmp(b.data(), c.data(), b.size() * 4) == 0); // off = automatic factors of 1, bit for bit
                int lines = 0, offLines = 0;
                for (const auto& l : lOn) lines += l.find("SNR footprints: key ") != std::string::npos;
                for (const auto& l : lOff) offLines += l.find("SNR footprints off (key ") != std::string::npos;
                assert(lines == 1 && offLines == 1);
                const double dOn = detail(a, tex), dOff = detail(b, tex), nOn = stddev(a, flat), nOff = stddev(b, flat);
                std::printf("P647 %s %s: texture detail off %.4f on %.4f; flat noise off %.5f on %.5f\n", r.name, night ? "night" : "day", dOff, dOn, nOff, nOn);
                // by day: more texture detail, the flat noise not up (the native Quad, already at P596's x0.5 texture kernel: its
                // texture within 3 %, the flat noise down); at night: less flat noise
                if (!night && r.block == 2) assert(dOn >= 0.97 * dOff && nOn < nOff);
                else if (!night) {
                    // P647b: a Bayer burst of 5 frames keeps its kernel by day (kernelSnrDayFramesLo / Hi): the merge bit for bit
                    // kernelSnr 0's; the day narrowing itself (frames gate off, texture x0.85 as P647) still lifts the texture's detail
                    assert(std::memcmp(a.data(), b.data(), a.size() * 4) == 0);
                    HybridTuning mech = on; mech.kernelSnrDayFramesLo = 1.f; mech.kernelSnrDayFramesHi = 2.f; mech.kernelSnrDayTextureBayer = 0.85f;
                    const std::vector<float> m = merge(st, mech);
                    const double dM = detail(m, tex), nM = stddev(m, flat);
                    std::printf("P647b %s day, the narrowing on 5 frames: texture detail %.4f, flat noise %.5f\n", r.name, dM, nM);
                    assert(dM > dOff * (r.sabre61 ? 1.01 : 1.0) - 1e-7 && nM <= nOff * 1.05);
                }
                // (round 4, the kernel the app no longer merges with - it writes sabre61 1 -: the transition is its flat0 / flat1 and its
                // texture factor its base sigma, already wide at this key: -3 % here)
                else assert(nOn < (r.sabre61 ? 0.97 : 0.99) * nOff);
                ++checked;
            }
        }
        assert(checked >= 6);
    }
    std::printf("hybrid kernel footprint check PASS (%d routes)\n", routes);
    return 0;
}
