// P646 «Склейка YUV»: host checks of app/src/main/cpp/scam-yuv-merge.h.
//  1. The port against the owner's reference (tools/yuv-merge-ref/yuv_burst_merge.hpp): every stage on random 8-bit data and the
//     whole prepared frame for both profiles, both chroma orders, both divisions and 1..16 frames - the same bytes; the document's
//     profile-distinguishing case (Y 80 / 93, countPacked 0x11 / 0x22).
//  2. 16-bit samples (10-bit P010): the same acceptance as 8-bit on the same picture x 4.
//  3. Ours, measured (printed) and bounded: the alignment, the burst's own noise LUT against the truth and against fixed LUTs, the
//     sharpest base, the guided chroma, the bracket in linear light on a synthetic HDR scene with motion (highlights recovered, no
//     ghost, unclipped areas unchanged within noise, shadows less noisy) and the linearity check of a wrong capture curve.
// Build: g++ -std=c++17 -O2 -ffp-contract=off -Iapp/src/main/cpp -Itools/yuv-merge-ref tools/check_yuv_merge.cpp
#include "scam-yuv-merge.h"
#include "yuv_burst_merge.hpp"

#include <cstdio>
#include <cstdlib>
#include <numeric>
#include <random>

using namespace scam_yuv;

static int failures = 0;
#define CHECK(c)                                                              \
    do {                                                                      \
        if (!(c)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); \
            ++failures;                                                       \
        }                                                                     \
    } while (0)

template <typename S>
struct Owned {
    int w = 0, h = 0, bits = 8;
    std::vector<S> y, c0, c1;
    ChromaOrder order = ChromaOrder::UV;
    Owned() = default;
    Owned(int w_, int h_, int bits_ = 8) : w(w_), h(h_), bits(bits_), y(std::size_t(w_) * h_), c0(std::size_t(w_ / 2) * (h_ / 2)), c1(c0.size()) {}
    Frame420<S> view() const {
        Frame420<S> f;
        f.y = {y.data(), w, 1};
        f.c0 = {c0.data(), w / 2, 1};
        f.c1 = {c1.data(), w / 2, 1};
        f.width = w;
        f.height = h;
        f.order = order;
        f.bits = bits;
        return f;
    }
    yuv_burst::Yuv420 ref() const {
        return {{y.data(), w, 1}, {c0.data(), w / 2, 1}, {c1.data(), w / 2, 1}, w, h,
                order == ChromaOrder::UV ? yuv_burst::Order::UV : yuv_burst::Order::VU};
    }
};

static std::mt19937 rng(646);
static int uni(int a, int b) { return std::uniform_int_distribution<int>(a, b)(rng); }
static float unif(float a, float b) { return std::uniform_real_distribution<float>(a, b)(rng); }

static Owned<uint8_t> randomFrame(int w, int h, ChromaOrder order, int base, int spread) {
    Owned<uint8_t> f(w, h);
    f.order = order;
    for (auto& v : f.y) v = uint8_t(std::clamp(base + uni(-spread, spread), 0, 255));
    for (auto& v : f.c0) v = uint8_t(uni(90, 160));
    for (auto& v : f.c1) v = uint8_t(uni(90, 160));
    return f;
}

// ---------------------------------------------------------------------------------------------------------------------------
static void checkStages() {
    for (int round = 0; round < 40; ++round) {
        const ChromaOrder order = round & 1 ? ChromaOrder::VU : ChromaOrder::UV;
        Owned<uint8_t> f = randomFrame(96, 96, order, uni(20, 230), uni(0, 60));
        const auto mine = f.view();
        const auto ref = f.ref();
        const int x = uni(-2, 70), y = uni(-2, 70);
        Tile<uint8_t> t;
        yuv_burst::Tile rt;
        const bool a = extractTile(mine, x, y, colourCheckMatrix(order), t);
        const bool b = yuv_burst::extract_tile(ref, x, y, yuv_burst::standard_color_matrix(ref.order), rt);
        CHECK(a == b);
        if (!a) continue;
        CHECK(std::equal(t.px.begin(), t.px.end(), rt.samples.begin()));
        CHECK(t.rgbQ8 == rt.rgbQ8);
        // thresholds from a random LUT and random gains
        std::vector<float> lut(kLutSize);
        for (float& v : lut) v = unif(0.f, 9.f);
        CellGains g;
        yuv_burst::GainTile rg;
        for (int i = 0; i < 16; ++i)
            for (int c = 0; c < 4; ++c) rg[i][c] = g[i][c] = unif(0.5f, 2.5f);
        ThresholdParams p;
        yuv_burst::ThresholdParams rp;
        p.spatialScale = rp.spatialScale = unif(0.5f, 1.5f);
        p.biasPost = rp.biasPost = unif(0.f, 0.5f);
        const auto mt = tileThresholds(t, 8, NoiseLut{lut.data(), lut.size()}, g, p);
        const auto rt2 = yuv_burst::make_thresholds(rt, yuv_burst::Lut{lut.data(), lut.size(), {3, 4, 4}, {0, 5, 9}}, rg, rp);
        CHECK(mt.local == rt2.local && mt.tile == rt2.tile && mt.nearClip == rt2.nearClip && mt.lastGain == rt2.lastGain);
        // projections, shifts, bands, the luma metric, the smoothed donor
        Luma8 l{};
        yuv_burst::Luma rl{};
        for (int i = 0; i < kTilePixels; ++i) rl[i] = l[i] = t.px[3 * i];
        CHECK(projections(l) == yuv_burst::project(rl));
        const auto pa = projections(l);
        Luma8 l2{};
        for (int i = 0; i < kTilePixels; ++i) l2[i] = uint8_t(std::clamp(int(l[(i + uni(0, 3)) % kTilePixels]) + uni(-3, 3), 0, 255));
        const auto pb = projections(l2);
        CHECK(projectionShift(pa.data(), pb.data()) == yuv_burst::projection_shift(pa.data(), pb.data()));
        CHECK(projectionShift(pa.data() + 32, pb.data() + 32) == yuv_burst::projection_shift(pa.data() + 32, pb.data() + 32));
        for (int m = 0; m < 2; ++m) {
            const auto bands = forgiveness(l, m ? Forgiveness::Weighted11 : Forgiveness::Rounded11);
            const auto rbands = yuv_burst::forgiveness(rl, m ? yuv_burst::Forgiveness::Weighted11 : yuv_burst::Forgiveness::Rounded11);
            bool same = true;
            for (int i = 0; i < kTilePixels; ++i) same &= bands[i].middle == rbands[i].middle && bands[i].radius == rbands[i].radius;
            CHECK(same);
            yuv_burst::Luma rl2{};
            for (int i = 0; i < kTilePixels; ++i) rl2[i] = l2[i];
            CHECK(lumaExcess(bands, l2) == yuv_burst::luma_mse(rbands, rl2));
        }
        if (x >= 0 && y >= 0 && x + 32 <= 96 && y + 32 <= 96) {
            const auto sd = smoothedDonor(mine, x, y);
            const auto rsd = yuv_burst::smoothed_donor(ref.y, 96, 96, x, y);
            CHECK(std::equal(sd.begin(), sd.end(), rsd.begin()));
        }
        const float den = unif(0.5f, 4.f), tol = unif(0.5f, 2.f), th = unif(0.f, 20.f), adj = unif(0.f, 1.f), ms = unif(0.5f, 1.5f);
        CHECK(colourLimit(den, tol, th, adj, ms) == yuv_burst::color_limit(den, tol, th, adj, ms));
        float s1 = 1.f, s2 = 1.f;
        const float metric = unif(0.f, 30.f), limit = unif(0.f, 20.f);
        CHECK(attenuate(metric, limit, 50.f, s1) == yuv_burst::attenuate(metric, limit, 50.f, s2) && s1 == s2);
    }
    std::printf("stages: extract, thresholds, projections, shift, bands, luma metric, smoothed donor, colour limit, attenuation == reference\n");
}

static void checkPrepared() {
    int cases = 0;
    for (int round = 0; round < 60; ++round) {
        const ChromaOrder order = round & 1 ? ChromaOrder::VU : ChromaOrder::UV;
        const Profile profile = (round / 2) & 1 ? Profile::P31 : Profile::P27;
        const Divide divide = (round / 4) & 1 ? Divide::Q15 : Divide::Exact;
        const int sizes[] = {1, 2, 3, 5, 8, 11, 15, 16};
        int requested = sizes[round % 8];
        if (divide == Divide::Q15 && requested == 16) requested = 15;
        const int donors = uni(0, requested - 1);
        const int w = 96, h = 64;
        const int level = uni(30, 220);
        Owned<uint8_t> base = randomFrame(w, h, order, level, 25);
        std::vector<Owned<uint8_t>> d;
        for (int j = 0; j < donors; ++j) d.push_back(randomFrame(w, h, order, level, 25));
        std::vector<Frame420<uint8_t>> dv;
        std::vector<yuv_burst::Yuv420> rdv;
        for (auto& f : d) {
            dv.push_back(f.view());
            rdv.push_back(f.ref());
        }
        const int nx = w / 32, ny = h / 32;
        std::vector<TilePlan> plans(nx * ny);
        std::vector<yuv_burst::TilePlan> rplans(nx * ny);
        for (int t = 0; t < nx * ny; ++t) {
            for (int i = 0; i < 16; ++i) plans[t].thresholds.local[i] = rplans[t].thresholds.local[i] = unif(0.f, 12.f);
            for (int j = 0; j < donors; ++j) {
                DonorPlacement p;
                p.gx = uni(-3, 3);
                p.gy = uni(-3, 3);
                p.dx = uint8_t(128 + uni(-4, 4));
                p.dy = uint8_t(128 + uni(-4, 4));
                p.scale = unif(0.f, 1.2f);
                p.disabled = uni(0, 5) == 0;
                plans[t].donors.push_back(p);
                rplans[t].donors.push_back({{p.gx, p.gy}, p.dx, p.dy, p.scale, p.disabled});
            }
        }
        const float mult = 2.5452001094818115f;
        const auto mine = mergePrepared(profile, base.view(), dv.data(), dv.size(), plans, unsigned(requested), mult, divide, round % 3 + 1);
        const auto ref = yuv_burst::merge_prepared(profile == Profile::P27 ? yuv_burst::Profile::v27 : yuv_burst::Profile::v31,
                                                   base.ref(), rdv.data(), rdv.size(), rplans, unsigned(requested), mult,
                                                   divide == Divide::Q15 ? yuv_burst::Division::Q15 : yuv_burst::Division::Scalar);
        CHECK(mine.planar == ref.planar);
        CHECK(mine.countPacked == ref.countPacked);
        CHECK(mine.eligible == ref.eligible);
        ++cases;
        // the tile with its reject counts
        Tile<uint8_t> bt;
        yuv_burst::Tile rbt;
        extractTile(base.view(), 0, 0, colourCheckMatrix(order), bt);
        yuv_burst::extract_tile(base.ref(), 0, 0, yuv_burst::standard_color_matrix(base.ref().order), rbt);
        std::vector<Tile<uint8_t>> dt(donors);
        std::vector<yuv_burst::Tile> rdt(donors);
        std::vector<DonorTile<uint8_t>> cand;
        std::vector<yuv_burst::Donor> rcand;
        for (int j = 0; j < donors; ++j) {
            extractTile(dv[j], 0, 0, colourCheckMatrix(order), dt[j]);
            yuv_burst::extract_tile(rdv[j], 0, 0, yuv_burst::standard_color_matrix(rdv[j].order), rdt[j]);
            cand.push_back({&dt[j], plans[0].donors[j].scale, plans[0].donors[j].disabled});
            rcand.push_back({&rdt[j], plans[0].donors[j].scale, plans[0].donors[j].disabled});
        }
        TileResult<uint8_t> tr;
        mergeTile(profile, bt, cand.data(), cand.size(), unsigned(requested), plans[0].thresholds, mult, divide, 8, tr);
        yuv_burst::Thresholds rth;
        rth.local = rplans[0].thresholds.local;
        const auto rtr = yuv_burst::merge_tile(profile == Profile::P27 ? yuv_burst::Profile::v27 : yuv_burst::Profile::v31, rbt,
                                               rcand.data(), rcand.size(), unsigned(requested), rth, mult,
                                               divide == Divide::Q15 ? yuv_burst::Division::Q15 : yuv_burst::Division::Scalar);
        CHECK(tr.planar == rtr.planar && tr.countPacked == rtr.countPacked && tr.rejects == rtr.pixelRejectCount && tr.eligible == rtr.eligible);
    }
    // the document's case that tells the profiles apart
    for (int pass = 0; pass < 2; ++pass) {
        Owned<uint8_t> b(64, 64), d(64, 64);
        std::fill(b.y.begin(), b.y.end(), 80);
        std::fill(d.y.begin(), d.y.end(), 106);
        for (auto* f : {&b, &d}) {
            std::fill(f->c0.begin(), f->c0.end(), 120);
            std::fill(f->c1.begin(), f->c1.end(), 128);
        }
        std::vector<TilePlan> plans(4);
        for (auto& p : plans) {
            p.thresholds.local.fill(0x1.32838ep+4f);
            DonorPlacement dp;
            dp.scale = 0x1.110498p-1f;
            p.donors.push_back(dp);
        }
        const auto dv = d.view();
        const auto r = mergePrepared(pass ? Profile::P31 : Profile::P27, b.view(), &dv, 1, plans, 2, 2.5452001094818115f, Divide::Q15);
        bool ok = true;
        for (int i = 0; i < 64 * 64; ++i)
            ok &= r.planar[i] == (pass ? 93 : 80) && r.planar[i + 4096] == 120 && r.planar[i + 8192] == 128;
        for (auto c : r.countPacked) ok &= c == (pass ? 0x22 : 0x11);
        for (auto e : r.eligible) ok &= e == 2;
        CHECK(ok);
    }
    std::printf("prepared frame: %d random bursts (2.7 / 3.1, UV / VU, exact / Q15, 1..16 frames) == reference; 2.7 -> Y 80, 3.1 -> Y 93\n", cases);
}

static void checkSixteenBit() {
    int same = 0, total = 0, worst = 0;
    for (int round = 0; round < 12; ++round) {
        const int w = 64, h = 64, donors = 3;
        Owned<uint8_t> b = randomFrame(w, h, ChromaOrder::UV, 120, 30);
        std::vector<Owned<uint8_t>> d;
        for (int j = 0; j < donors; ++j) d.push_back(randomFrame(w, h, ChromaOrder::UV, 120, 30));
        auto widen = [](const Owned<uint8_t>& f) {
            Owned<uint16_t> o(f.w, f.h, 10);
            for (std::size_t i = 0; i < f.y.size(); ++i) o.y[i] = uint16_t(f.y[i] * 4);
            for (std::size_t i = 0; i < f.c0.size(); ++i) {
                o.c0[i] = uint16_t(f.c0[i] * 4);
                o.c1[i] = uint16_t(f.c1[i] * 4);
            }
            return o;
        };
        Owned<uint16_t> b16 = widen(b);
        std::vector<Owned<uint16_t>> d16;
        std::vector<Frame420<uint8_t>> dv;
        std::vector<Frame420<uint16_t>> dv16;
        for (auto& f : d) {
            d16.push_back(widen(f));
            dv.push_back(f.view());
        }
        for (auto& f : d16) dv16.push_back(f.view());
        std::vector<TilePlan> p8(4), p16(4);
        for (int t = 0; t < 4; ++t)
            for (int i = 0; i < 16; ++i) {
                const float k = float(uni(1, 12)) + 0.05f;  // fraction < 1/4: the same integer acceptance at x4
                p8[t].thresholds.local[i] = k / 2.5452001094818115f;
                p16[t].thresholds.local[i] = 4.f * k / 2.5452001094818115f;
                if (i == 0)
                    for (int j = 0; j < donors; ++j) {
                        p8[t].donors.push_back(DonorPlacement{});
                        p16[t].donors.push_back(DonorPlacement{});
                    }
            }
        const auto r8 = mergePrepared(Profile::P31, b.view(), dv.data(), dv.size(), p8, 4, 2.5452001094818115f, Divide::Exact);
        const auto r16 = mergePrepared(Profile::P31, b16.view(), dv16.data(), dv16.size(), p16, 4, 2.5452001094818115f, Divide::Exact);
        same += r8.countPacked == r16.countPacked;
        ++total;
        for (std::size_t i = 0; i < r8.planar.size(); ++i) worst = std::max(worst, std::abs(int(r16.planar[i]) - 4 * int(r8.planar[i])));
    }
    CHECK(same == total);
    CHECK(worst <= 4);
    std::printf("16-bit samples: %d/%d bursts accept the same pixels as 8-bit x4, output within %d codes of 4x the 8-bit one\n", same, total, worst);
}

// ---- synthetic scenes ------------------------------------------------------------------------------------------------------

/** A smooth textured linear-light scene (0..1 at the normal exposure; {@code hdr}: a window to 6, a dark patch). */
struct Scene {
    int w, h;
    std::vector<float> rgb;  // linear
    Scene(int w_, int h_, bool hdr, unsigned seed) : w(w_), h(h_), rgb(std::size_t(w_) * h_ * 3) {
        std::mt19937 r(seed);
        std::uniform_real_distribution<float> u(0.f, 1.f);
        std::vector<float> blobs;
        for (int i = 0; i < 60; ++i) blobs.insert(blobs.end(), {u(r) * w, u(r) * h, 3.f + 25.f * u(r), u(r) * 0.5f - 0.2f});
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                float v = 0.06f + 0.4f * float(x) / w;
                for (std::size_t b = 0; b < blobs.size(); b += 4) {
                    const float dx = x - blobs[b], dy = y - blobs[b + 1], s = blobs[b + 2];
                    v += blobs[b + 3] * std::exp(-(dx * dx + dy * dy) / (2 * s * s));
                }
                v += 0.04f * std::sin(x * 0.7f) * std::sin(y * 0.55f);
                v = std::max(0.004f, v);
                float c[3] = {v * 1.05f, v, v * 0.9f};
                if (x > 2 * w / 3 && y < h / 3) {  // a sky / window: up to 6x the normal white, coloured
                    if (hdr) {
                        const float k = 1.6f + 4.f * float(y) / (h / 3.f);
                        c[0] = k * 0.85f;
                        c[1] = k;
                        c[2] = k * 1.2f;
                    } else {
                        c[0] *= 1.4f;
                        c[2] *= 1.6f;
                    }
                }
                if (hdr && x < w / 4 && y > 2 * h / 3) {  // a deep shadow with fine texture
                    const float d = 0.006f + 0.004f * std::sin(x * 0.9f) * std::sin(y * 0.8f);
                    c[0] = d * 1.1f;
                    c[1] = d;
                    c[2] = d * 0.8f;
                }
                for (int k = 0; k < 3; ++k) rgb[(std::size_t(y) * w + x) * 3 + k] = c[k];
            }
    }
};

/** Encodes linear RGB (clipped at 1) through {@code curve} to JFIF YUV420 8-bit; noise added in linear light (shot + read). */
static Owned<uint8_t> encode(const std::vector<float>& lin, int w, int h, const std::function<float(float)>& curve, float shot,
                             float read, unsigned seed, int dxShift = 0, int dyShift = 0) {
    std::mt19937 r(seed);
    std::normal_distribution<float> n(0.f, 1.f);
    Owned<uint8_t> f(w, h);
    std::vector<float> Y(std::size_t(w) * h), Cb(Y.size()), Cr(Y.size());
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const int sx = std::clamp(x + dxShift, 0, w - 1), sy = std::clamp(y + dyShift, 0, h - 1);
            float e[3];
            for (int k = 0; k < 3; ++k) {
                float v = lin[(std::size_t(sy) * w + sx) * 3 + k];
                v += std::sqrt(std::max(0.f, shot * v + read)) * n(r);
                e[k] = curve(std::clamp(v, 0.f, 1.f));
            }
            const std::size_t p = std::size_t(y) * w + x;
            Y[p] = 0.299f * e[0] + 0.587f * e[1] + 0.114f * e[2];
            Cb[p] = (e[2] - Y[p]) / 1.772f;
            Cr[p] = (e[0] - Y[p]) / 1.402f;
        }
    for (std::size_t p = 0; p < Y.size(); ++p) f.y[p] = uint8_t(std::clamp(std::lround(Y[p] * 255.f), 0L, 255L));
    for (int y = 0; y < h / 2; ++y)
        for (int x = 0; x < w / 2; ++x) {
            float cb = 0, cr = 0;
            for (int j = 0; j < 2; ++j)
                for (int i = 0; i < 2; ++i) {
                    cb += Cb[std::size_t(2 * y + j) * w + 2 * x + i];
                    cr += Cr[std::size_t(2 * y + j) * w + 2 * x + i];
                }
            f.c0[std::size_t(y) * (w / 2) + x] = uint8_t(std::clamp(std::lround(cb / 4 * 255.f + 128.f), 0L, 255L));
            f.c1[std::size_t(y) * (w / 2) + x] = uint8_t(std::clamp(std::lround(cr / 4 * 255.f + 128.f), 0L, 255L));
        }
    return f;
}

static float srgbEncode(float x) { return x <= 0.0031308f ? 12.92f * x : 1.055f * std::pow(x, 1.f / 2.4f) - 0.055f; }

/** The capture curve the app asks for: 64 points of the sRGB curve (CaptureController's tonemap), piecewise linear. */
static Transfer pointsCurve(std::vector<float>& in, std::vector<float>& out) {
    in.clear();
    out.clear();
    for (int i = 0; i < 64; ++i) {
        const float x = std::pow(float(i) / 63.f, 2.2f);
        in.push_back(x);
        out.push_back(srgbEncode(x));
    }
    Transfer t;
    t.kind = Transfer::POINTS;
    t.in = in;
    t.out = out;
    t.build();
    return t;
}

static std::function<float(float)> pointsEncoder(const std::vector<float>& in, const std::vector<float>& out) {
    return [in, out](float x) {
        for (std::size_t i = 1; i < in.size(); ++i)
            if (x <= in[i]) return out[i - 1] + (x - in[i - 1]) / (in[i] - in[i - 1]) * (out[i] - out[i - 1]);
        return out.back();
    };
}

static void checkAlignment() {
    Scene s(320, 256, false, 3);
    std::vector<float> in, out;
    pointsCurve(in, out);
    const auto enc = pointsEncoder(in, out);
    Owned<uint8_t> base = encode(s.rgb, s.w, s.h, enc, 0.f, 0.f, 1);
    Luma bl = lumaOf(base.view(), {});
    int exact = 0, total = 0;
    for (auto [dx, dy] : std::vector<std::pair<int, int>>{{0, 0}, {3, -4}, {-5, 6}, {9, 11}, {-20, 17}, {24, -22}}) {
        Owned<uint8_t> d = encode(s.rgb, s.w, s.h, enc, 0.f, 0.f, 2, dx, dy);
        Luma dl = lumaOf(d.view(), {});
        const Alignment a = alignTiles(bl, dl, s.w / 32, s.h / 32, 32, 4);
        // the donor at (x + dx) shows the base's (x): its global shift is -dx (whole tiles interior; the border clamps)
        const bool g = a.global.x == -dx && a.global.y == -dy;
        int good = 0, tiles = 0;
        for (int ty = 1; ty < s.h / 32 - 1; ++ty)
            for (int tx = 1; tx < s.w / 32 - 1; ++tx) {
                const std::size_t t = std::size_t(ty) * (s.w / 32) + tx;
                ++tiles;
                good += a.local[2 * t] == 0 && a.local[2 * t + 1] == 0;
            }
        exact += g;
        ++total;
        CHECK(g);
        CHECK(good * 10 >= tiles * 9);
        std::printf("alignment: shift (%d,%d) -> global (%d,%d), %d/%d interior tiles with no residual, SAD %.2f\n", dx, dy,
                    a.global.x, a.global.y, good, tiles, a.meanSad);
    }
    // a local motion: a 64 x 64 block moved by (5, -3) in the donor
    std::vector<float> moved = s.rgb;
    for (int y = 96; y < 160; ++y)
        for (int x = 96; x < 160; ++x)
            for (int k = 0; k < 3; ++k) moved[(std::size_t(y) * s.w + x) * 3 + k] = s.rgb[(std::size_t(y + -3) * s.w + x + 5) * 3 + k];
    Owned<uint8_t> d = encode(moved, s.w, s.h, enc, 0.f, 0.f, 4);
    const Alignment a = alignTiles(bl, lumaOf(d.view(), {}), s.w / 32, s.h / 32, 32, 6);
    const std::size_t t = std::size_t(4) * (s.w / 32) + 4;  // the tile (128, 128): inside the moved block
    std::printf("alignment: local motion (5,-3) -> tile residual (%d,%d)\n", -a.local[2 * t], -a.local[2 * t + 1]);
    CHECK(a.local[2 * t] == -5 && a.local[2 * t + 1] == 3);
}

/** Noise of one frame in 8-bit codes against the level: sigma(Y) = 0.9 + 0.018 Y, Gaussian, added to Y and chroma alike. */
static float trueSigma(float y8) { return 0.9f + 0.018f * y8; }

/** A noisy copy shifted by (dx, dy); {@code objectX} >= 0: a 48 x 48 object {@code objectLift} codes brighter there (it came in). */
static Owned<uint8_t> noisyCopy(const Owned<uint8_t>& clean, unsigned seed, int dx = 0, int dy = 0, int objectX = -1, int objectY = -1,
                                int objectLift = 0, int objectSize = 48) {
    std::mt19937 r(seed);
    std::normal_distribution<float> n(0.f, 1.f);
    Owned<uint8_t> f(clean.w, clean.h);
    for (int y = 0; y < clean.h; ++y)
        for (int x = 0; x < clean.w; ++x) {
            const int sx = std::clamp(x + dx, 0, clean.w - 1), sy = std::clamp(y + dy, 0, clean.h - 1);
            float v = clean.y[std::size_t(sy) * clean.w + sx];
            if (objectX >= 0 && x >= objectX && x < objectX + objectSize && y >= objectY && y < objectY + objectSize)
                v += float(objectLift);
            f.y[std::size_t(y) * clean.w + x] = uint8_t(std::clamp(std::lround(v + trueSigma(v) * n(r)), 0L, 255L));
        }
    for (int y = 0; y < clean.h / 2; ++y)
        for (int x = 0; x < clean.w / 2; ++x) {
            const int sx = std::clamp(x + dx / 2, 0, clean.w / 2 - 1), sy = std::clamp(y + dy / 2, 0, clean.h / 2 - 1);
            const std::size_t p = std::size_t(y) * (clean.w / 2) + x, q = std::size_t(sy) * (clean.w / 2) + sx;
            f.c0[p] = uint8_t(std::clamp(std::lround(clean.c0[q] + 0.5f * n(r)), 0L, 255L));
            f.c1[p] = uint8_t(std::clamp(std::lround(clean.c1[q] + 0.5f * n(r)), 0L, 255L));
        }
    return f;
}

struct Quality {
    double psnrStatic = 0, ghost = 0;
};

/** PSNR outside the object, mean |error| inside it (codes), of the merged result's luma against the clean frame. */
static Quality quality(const std::vector<float>& outLin, const Owned<uint8_t>& clean, const Transfer& t, int mx, int my, int size = 12) {
    double se = 0, ghost = 0;
    long n = 0, g = 0;
    for (int y = 16; y < clean.h - 16; ++y)
        for (int x = 16; x < clean.w - 16; ++x) {
            const std::size_t p = std::size_t(y) * clean.w + x;
            // back to the 8-bit luma of the merged result (the clean frame's domain)
            float e[3];
            for (int k = 0; k < 3; ++k) e[k] = srgbEncode(std::clamp(outLin[p * 3 + k], 0.f, 1.f));
            (void)t;
            const double yv = 255.0 * (0.299 * e[0] + 0.587 * e[1] + 0.114 * e[2]);
            const double d = yv - clean.y[p];
            if (x >= mx && x < mx + size && y >= my && y < my + size) {
                ghost += std::fabs(d);
                ++g;
            } else if (x < mx - 8 || x >= mx + 20 || y < my - 8 || y >= my + 20) {
                se += d * d;
                ++n;
            }
        }
    Quality q;
    q.psnrStatic = 10 * std::log10(255.0 * 255.0 / (se / n));
    q.ghost = ghost / std::max(1L, g);
    return q;
}

static void checkNoiseAndMerge() {
    Scene s(384, 320, false, 9);
    std::vector<float> in, out;
    const Transfer t = pointsCurve(in, out);
    const auto enc = pointsEncoder(in, out);
    const Owned<uint8_t> clean = encode(s.rgb, s.w, s.h, enc, 0.f, 0.f, 1);
    const int mx = 160, my = 128;
    std::vector<Owned<uint8_t>> frames;
    frames.push_back(noisyCopy(clean, 10));
    for (int j = 1; j < 6; ++j) frames.push_back(noisyCopy(clean, 10 + j, j % 3 - 1, (j * 7) % 3 - 1, mx, my, 14, 12));
    std::vector<Frame420<uint8_t>> views;
    std::vector<FrameInfo> info(frames.size());
    for (auto& f : frames) views.push_back(f.view());
    Decoder dec;
    NoiseFallback fb;  // no camera profile: our measurement only
    Settings set;
    set.sharpestBase = false;
    set.threads = 2;
    std::vector<float> rgb(std::size_t(s.w) * s.h * 3);
    const Report rep = mergeBurst(views, info, set, t, dec, fb, rgb.data());
    // the measured curve against the truth on the bins it measured
    double err = 0;
    int bins = 0;
    for (int b = 0; b < 32; ++b) {
        if (rep.noise.blocks[b] < 12) continue;
        const float truth = trueSigma(b * 8.f + 4.f);
        err += std::fabs(rep.noise.sigma[b] - truth) / truth;
        ++bins;
    }
    err /= std::max(1, bins);
    std::printf("noise LUT from the burst: %d bins measured (%d blocks), mean |error| %.1f%% of the true sigma; %s\n", bins,
                rep.noise.totalBlocks, 100 * err, rep.text().c_str());
    CHECK(rep.noise.measured && bins >= 6 && err < 0.15);
    const Quality ours = quality(rgb, clean, t, mx, my);
    // the same merge with fixed tables: one too low (sigma 0.6), one too high (sigma 8)
    auto fixed = [&](float sigma) {
        Settings f = set;
        f.ownNoise = false;
        NoiseFallback k;
        // a flat model: sigma codes over the whole range (s = 0, o from sigma at the curve's slope ~ 1 / mid)
        std::vector<float> outLin(rgb.size());
        Transfer flat;
        flat.kind = Transfer::POINTS;
        flat.in = t.in;
        flat.out = t.out;
        flat.build();
        // the model of mergeBurst is sigma_x / slope * max; give it o such that sigma ~ constant at mid-grey
        const float e = 0.5f, sl = flat.slope(e);
        k.o = std::pow(sigma / 255.f * sl, 2.f);
        mergeBurst(views, info, f, flat, dec, k, outLin.data());
        return quality(outLin, clean, t, mx, my);
    };
    const Quality low = fixed(0.6f), high = fixed(8.f);
    Settings single = set;
    std::vector<Frame420<uint8_t>> one{views[0]};
    std::vector<FrameInfo> oneInfo(1);
    std::vector<float> rgb1(rgb.size());
    mergeBurst(one, oneInfo, single, t, dec, fb, rgb1.data());
    const Quality none = quality(rgb1, clean, t, mx, my);
    std::printf("merge of 6 frames, static PSNR / moving-block error (codes): one frame %.2f dB / %.2f; our LUT %.2f dB / %.2f; "
                "fixed sigma 0.6: %.2f dB / %.2f; fixed sigma 8: %.2f dB / %.2f\n",
                none.psnrStatic, none.ghost, ours.psnrStatic, ours.ghost, low.psnrStatic, low.ghost, high.psnrStatic, high.ghost);
    CHECK(ours.psnrStatic > none.psnrStatic + 3.0);
    CHECK(ours.psnrStatic > low.psnrStatic + 0.5);
    CHECK(ours.ghost < high.ghost * 0.7);
    CHECK(ours.ghost < none.ghost * 2.0);
    // the profiles on the same burst
    Settings p27 = set;
    p27.profile = Profile::P27;
    std::vector<float> rgb27(rgb.size());
    mergeBurst(views, info, p27, t, dec, fb, rgb27.data());
    const Quality q27 = quality(rgb27, clean, t, mx, my);
    std::printf("profiles on the same burst: 2.7 %.3f dB / %.3f, 3.1 %.3f dB / %.3f\n", q27.psnrStatic, q27.ghost, ours.psnrStatic, ours.ghost);
}

static void checkSharpestBase() {
    Scene s(256, 192, false, 5);
    std::vector<float> in, out;
    const Transfer t = pointsCurve(in, out);
    const Owned<uint8_t> clean = encode(s.rgb, s.w, s.h, pointsEncoder(in, out), 0.f, 0.f, 1);
    std::vector<Owned<uint8_t>> frames;
    for (int j = 0; j < 4; ++j) {
        Owned<uint8_t> f = noisyCopy(clean, 30 + j);
        if (j != 2) {  // three blurred frames (camera shake), frame 2 sharp
            Owned<uint8_t> b = f;
            for (int y = 0; y < s.h; ++y)
                for (int x = 0; x < s.w; ++x) {
                    int sum = 0;
                    for (int k = -2; k <= 2; ++k) sum += f.y[std::size_t(y) * s.w + std::clamp(x + k, 0, s.w - 1)];
                    b.y[std::size_t(y) * s.w + x] = uint8_t(sum / 5);
                }
            f = b;
        }
        frames.push_back(f);
    }
    std::vector<Frame420<uint8_t>> views;
    for (auto& f : frames) views.push_back(f.view());
    std::vector<FrameInfo> info(4);
    Settings set;
    std::vector<float> rgb(std::size_t(s.w) * s.h * 3);
    const Report rep = mergeBurst(views, info, set, t, Decoder{}, NoiseFallback{}, rgb.data());
    std::printf("sharpest base: frame %d of 4 (the only unblurred one is 2)\n", rep.base);
    CHECK(rep.base == 2);
}

static void checkGuidedChroma() {
    // a colour edge on odd columns / rows: 4:2:0 smears it over 2 px, the merged luma knows where it is
    const int w = 128, h = 96;
    std::vector<float> lin(std::size_t(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const bool red = (x + (y / 9)) % 13 < 7 && (y % 11) < 6;
            float* c = &lin[(std::size_t(y) * w + x) * 3];
            if (red) {
                c[0] = 0.55f;
                c[1] = 0.05f;
                c[2] = 0.04f;
            } else {
                c[0] = 0.08f;
                c[1] = 0.32f;
                c[2] = 0.45f;
            }
        }
    std::vector<float> in, out;
    const Transfer t = pointsCurve(in, out);
    const Owned<uint8_t> f = encode(lin, w, h, pointsEncoder(in, out), 0.f, 0.f, 1);
    std::vector<Frame420<uint8_t>> views{f.view()};
    std::vector<FrameInfo> info(1);
    double err[2];
    for (int g = 0; g < 2; ++g) {
        Settings set;
        set.guidedChroma = g == 1;
        std::vector<float> rgb(lin.size());
        mergeBurst(views, info, set, t, Decoder{}, NoiseFallback{0.f, 1e-6f}, rgb.data());
        double e = 0;
        long n = 0;
        for (int y = 2; y < h - 2; ++y)
            for (int x = 2; x < w - 2; ++x)
                for (int k = 0; k < 3; ++k, ++n) {
                    const std::size_t p = (std::size_t(y) * w + x) * 3 + k;
                    const double d = srgbEncode(std::clamp(rgb[p], 0.f, 1.f)) - srgbEncode(lin[p]);
                    e += d * d;
                }
        err[g] = 10 * std::log10(1.0 / (e / n));
    }
    std::printf("chroma: R'G'B' PSNR against the 4:4:4 truth: nearest %.2f dB, guided %.2f dB\n", err[0], err[1]);
    CHECK(err[1] > err[0] + 1.0);
}

static void checkBracket() {
    const int w = 320, h = 256;
    Scene s(w, h, true, 21);
    std::vector<float> in, out;
    const Transfer t = pointsCurve(in, out);
    const auto enc = pointsEncoder(in, out);
    const float shot = 0.0016f, read = 2e-6f;
    // a moving object in the bracket frames only: a bright 40 x 40 square in the ES / L frames where the base has the scene
    auto withObject = [&](float level, int ox, int oy) {
        std::vector<float> l = s.rgb;
        for (int y = oy; y < oy + 40; ++y)
            for (int x = ox; x < ox + 40; ++x)
                for (int k = 0; k < 3; ++k) l[(std::size_t(y) * w + x) * 3 + k] = level;
        return l;
    };
    auto scaled = [&](const std::vector<float>& l, float r) {
        std::vector<float> o(l);
        for (float& v : o) v *= r;
        return o;
    };
    std::vector<Owned<uint8_t>> frames;
    std::vector<FrameInfo> info;
    for (int j = 0; j < 4; ++j) {
        frames.push_back(encode(s.rgb, w, h, enc, shot, read, 100 + j));
        info.push_back({Role::Normal, 1.f});
    }
    // ES 1/8: a dark object passing through the window (ghost test in the highlights); L x4: a grey object in the mid tones
    const auto esScene = withObject(0.3f, 250, 20);
    for (int j = 0; j < 2; ++j) {
        frames.push_back(encode(scaled(esScene, 0.125f), w, h, enc, shot, read, 200 + j));
        info.push_back({Role::Short, 0.125f});
    }
    const auto lScene = withObject(0.02f, 120, 120);
    frames.push_back(encode(scaled(lScene, 4.f), w, h, enc, shot, read, 300));
    info.push_back({Role::Long, 4.f});
    std::vector<Frame420<uint8_t>> views;
    for (auto& f : frames) views.push_back(f.view());
    Settings set;
    set.sharpestBase = false;
    set.threads = 3;
    std::vector<float> on(std::size_t(w) * h * 3), off(on.size());
    const NoiseFallback camera{shot, read};  // the camera's NOISE_PROFILE of this synthetic sensor
    const Report rep = mergeBurst(views, info, set, t, Decoder{}, camera, on.data());
    Settings noBracket = set;
    noBracket.bracket = false;
    mergeBurst(views, info, noBracket, t, Decoder{}, camera, off.data());
    std::printf("bracket: %s\n", rep.text().c_str());
    CHECK(rep.bracketUsed);
    // 1. highlights: the window (base clipped, ES not, no object) back within 10 % of the truth
    std::vector<float> errs;
    for (int y = 4; y < h / 3 - 4; ++y)
        for (int x = 2 * w / 3 + 4; x < w - 4; ++x) {
            if (x >= 240 && x < 300 && y >= 10 && y < 70) continue;
            const std::size_t p = std::size_t(y) * w + x;
            const float truth = luminance(&s.rgb[p * 3]);
            if (truth < 1.4f || truth > 7.f) continue;
            errs.push_back(std::fabs(luminance(&on[p * 3]) - truth) / truth);
        }
    std::nth_element(errs.begin(), errs.begin() + errs.size() / 2, errs.end());
    const float hlErr = errs[errs.size() / 2];
    // 2. no ghost: where the ES frames show the dark object inside the window, the output stays the clipped base, never darker
    double ghostEs = 0;
    long nEs = 0;
    for (int y = 24; y < 56; ++y)
        for (int x = 254; x < 286; ++x) {
            const std::size_t p = std::size_t(y) * w + x;
            if (x < 2 * w / 3 + 2) continue;
            ghostEs += luminance(&on[p * 3]) < luminance(&off[p * 3]) * 0.95f;
            ++nEs;
        }
    // where the L frame shows the object in the mid tones / shadows, the output is the base's within noise
    double ghostL = 0;
    long nL = 0;
    for (int y = 124; y < 156; ++y)
        for (int x = 124; x < 156; ++x) {
            const std::size_t p = std::size_t(y) * w + x;
            ghostL += std::fabs(luminance(&on[p * 3]) - luminance(&off[p * 3])) / std::max(0.01f, luminance(&off[p * 3]));
            ++nL;
        }
    ghostL /= nL;
    // 3. unclipped areas the L frame clips (mid / bright tones, 0.3..0.9) unchanged within noise
    double midChange = 0;
    long nMid = 0;
    for (int y = 8; y < h - 8; ++y)
        for (int x = 8; x < 2 * w / 3 - 8; ++x) {
            const std::size_t p = std::size_t(y) * w + x;
            const float truth = luminance(&s.rgb[p * 3]);
            if (truth < 0.3f || truth > 0.85f) continue;
            midChange += std::fabs(luminance(&on[p * 3]) - luminance(&off[p * 3]));
            ++nMid;
        }
    midChange /= std::max(1L, nMid);
    // 4. the deep shadow: noise against the truth with and without the long frame
    double seOn = 0, seOff = 0;
    long nS = 0;
    for (int y = 2 * h / 3 + 6; y < h - 6; ++y)
        for (int x = 6; x < w / 4 - 6; ++x) {
            if (x >= 116 && x < 164 && y >= 116 && y < 164) continue;
            const std::size_t p = std::size_t(y) * w + x;
            const double truth = luminance(&s.rgb[p * 3]);
            seOn += std::pow(luminance(&on[p * 3]) - truth, 2);
            seOff += std::pow(luminance(&off[p * 3]) - truth, 2);
            ++nS;
        }
    const double shadowGain = std::sqrt(seOff / seOn);
    std::printf("bracket: highlights median error %.1f%% of the truth (no bracket: clipped at 1); ES ghost pixels %.2f%%; "
                "L ghost change %.2f%%; mid tones mean change %.5f (sigma there ~%.4f); deep-shadow RMS error x%.2f lower\n",
                100 * hlErr, 100.0 * ghostEs / std::max(1L, nEs), 100 * ghostL, midChange,
                std::sqrt(shot * 0.5f + read) / 2.f, shadowGain);
    CHECK(hlErr < 0.10f);
    CHECK(ghostEs / std::max(1L, nEs) < 0.02);
    CHECK(ghostL < 0.08);
    CHECK(midChange < 0.004);
    CHECK(shadowGain > 1.25);
    for (float l : rep.linearity) CHECK(l > 0.85f && l < 1.15f);
    // a wrong curve: the frames encoded with another curve (a gamma 1.6 S-curve) than the one decoded: the check refuses
    auto other = [](float x) { const float g = std::pow(x, 1.f / 1.6f); return g * g * (3.f - 2.f * g) * 0.5f + g * 0.5f; };
    std::vector<Owned<uint8_t>> wrong;
    for (int j = 0; j < 4; ++j) wrong.push_back(encode(s.rgb, w, h, other, shot, read, 400 + j));
    wrong.push_back(encode(scaled(s.rgb, 0.125f), w, h, other, shot, read, 410));
    wrong.push_back(encode(scaled(s.rgb, 4.f), w, h, other, shot, read, 411));
    std::vector<FrameInfo> wi{{Role::Normal, 1}, {Role::Normal, 1}, {Role::Normal, 1}, {Role::Normal, 1}, {Role::Short, 0.125f}, {Role::Long, 4.f}};
    std::vector<Frame420<uint8_t>> wv;
    for (auto& f : wrong) wv.push_back(f.view());
    const Report wr = mergeBurst(wv, wi, set, t, Decoder{}, camera, on.data());
    std::printf("wrong capture curve: %s\n", wr.text().c_str());
    CHECK(!wr.bracketUsed);
}


static float hlgEncode(float x) {
    const float a = 0.17883277f, b = 0.28466892f, c = 0.55991073f;
    return x <= 1.f / 12.f ? std::sqrt(3.f * x) : a * std::log(12.f * x - b) + c;
}

/** 10-bit P010 as a camera's HLG10 stream: linear BT.709 -> BT.2020 -> HLG -> narrow-range BT.2020 Y'CbCr (or full range). */
static Owned<uint16_t> encodeHlg(const std::vector<float>& lin, int w, int h, float sigma, unsigned seed, bool narrow) {
    std::mt19937 r(seed);
    std::normal_distribution<float> n(0.f, 1.f);
    // the inverse of scam_yuv::bt2020To709
    const double m[3][3] = {{1.6605, -0.5876, -0.0728}, {-0.1246, 1.1329, -0.0083}, {-0.0182, -0.1006, 1.1187}};
    double inv[3][3];
    const double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                       m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            const int a = (j + 1) % 3, b = (j + 2) % 3, c = (i + 1) % 3, d = (i + 2) % 3;
            inv[i][j] = (m[a][c] * m[b][d] - m[a][d] * m[b][c]) / det;
        }
    Owned<uint16_t> f(w, h, 10);
    std::vector<float> Y(std::size_t(w) * h), Cb(Y.size()), Cr(Y.size());
    for (std::size_t p = 0; p < Y.size(); ++p) {
        float c709[3], e[3];
        for (int k = 0; k < 3; ++k) c709[k] = std::clamp(lin[p * 3 + k] + sigma * std::sqrt(lin[p * 3 + k] + 1e-3f) * n(r), 0.f, 1.f);
        for (int k = 0; k < 3; ++k) e[k] = hlgEncode(std::clamp(float(inv[k][0] * c709[0] + inv[k][1] * c709[1] + inv[k][2] * c709[2]), 0.f, 1.f));
        Y[p] = 0.2627f * e[0] + 0.678f * e[1] + 0.0593f * e[2];
        Cb[p] = (e[2] - Y[p]) / 1.8814f;
        Cr[p] = (e[0] - Y[p]) / 1.4746f;
    }
    auto q = [&](float v, bool luma) {
        const float code = narrow ? (luma ? 64.f + 876.f * v : 512.f + 896.f * v) : (luma ? 1023.f * v : 512.f + 1023.f * v);
        return uint16_t(std::clamp(std::lround(code), 0L, 1023L));
    };
    for (std::size_t p = 0; p < Y.size(); ++p) f.y[p] = q(Y[p], true);
    for (int y = 0; y < h / 2; ++y)
        for (int x = 0; x < w / 2; ++x) {
            float cb = 0, cr = 0;
            for (int j = 0; j < 2; ++j)
                for (int i = 0; i < 2; ++i) {
                    cb += Cb[std::size_t(2 * y + j) * w + 2 * x + i];
                    cr += Cr[std::size_t(2 * y + j) * w + 2 * x + i];
                }
            f.c0[std::size_t(y) * (w / 2) + x] = q(cb / 4, false);
            f.c1[std::size_t(y) * (w / 2) + x] = q(cr / 4, false);
        }
    return f;
}

static void checkTenBitHlg() {
    const int w = 256, h = 192;
    Scene s(w, h, false, 77);
    for (int pass = 0; pass < 2; ++pass) {
        const bool narrow = pass == 0;
        std::vector<Owned<uint16_t>> frames;
        for (int j = 0; j < 4; ++j) frames.push_back(encodeHlg(s.rgb, w, h, 0.01f, 500 + j, narrow));
        std::vector<Frame420<uint16_t>> views;
        for (auto& f : frames) views.push_back(f.view());
        std::vector<FrameInfo> info(4);
        Transfer t;
        t.kind = Transfer::HLG;
        t.build();
        Decoder d;
        d.bits = 10;
        d.bt2020 = d.narrow = true;  // asked for narrow; the full-range pass must be found by its darkest samples
        Settings set;
        std::vector<float> rgb(std::size_t(w) * h * 3);
        const Report rep = mergeBurst(views, info, set, t, d, NoiseFallback{0.0001f, 1e-6f}, rgb.data());
        std::vector<float> err;
        for (std::size_t p = 0; p < std::size_t(w) * h; ++p) {
            const float truth = luminance(&s.rgb[p * 3]);
            if (truth < 0.05f || truth > 0.9f) continue;
            err.push_back(std::fabs(luminance(&rgb[p * 3]) / rep.outputScale - truth) / truth);
        }
        std::nth_element(err.begin(), err.begin() + err.size() / 2, err.end());
        CHECK(std::fabs(rep.outputScale - 3.7735f) < 0.01f);   // HLG's reference white (75 %: 0.265 of the peak) -> 1
        std::printf("10-bit HLG P010 (%s): linear luminance median error %.2f%%, %s\n", narrow ? "narrow" : "full range",
                    100 * err[err.size() / 2], rep.decodeNote.c_str());
        CHECK(err[err.size() / 2] < 0.03f);
        CHECK(narrow ? rep.decodeNote == "narrow range" : rep.decodeNote.rfind("full range", 0) == 0);
    }
}

/**
 * P667: a room (no deep black, no clipped white: Y within the narrow range) taken (a) at 8 bits with our capture curve and (b) as
 * the camera's HLG10 P010 stream, its white at HLG's reference (BT.2408: 75 % of the signal) in FULL range - Camera2's HLG10
 * dataspace (DataSpace.DATASPACE_BT2020_HLG) - must give the same linear light to the tone: the same brightness and contrast.
 * With the range declared (the Image's dataspace) it does; guessed from the Y extremes (before P667) the room reads as narrow
 * range and its light comes out with the highlights too bright and the shadows too dark.
 */
static void checkHlgMatchesEightBit() {
    const int w = 256, h = 192;
    Scene room(w, h, false, 91);
    for (float& v : room.rgb) v = std::clamp(0.62f * v, 0.f, 0.85f);  // a dim room: 0.4 % .. 85 % of the white
    std::vector<float> in, out;
    const Transfer points = pointsCurve(in, out);
    const auto curve = pointsEncoder(in, out);
    std::vector<FrameInfo> info(4);
    Settings set;
    std::vector<float> eight(std::size_t(w) * h * 3);
    {
        std::vector<Owned<uint8_t>> frames;
        for (int j = 0; j < 4; ++j) frames.push_back(encode(room.rgb, w, h, curve, 0.0004f, 1e-6f, 900 + j));
        std::vector<Frame420<uint8_t>> views;
        for (auto& f : frames) views.push_back(f.view());
        mergeBurst(views, info, set, points, Decoder{}, NoiseFallback{0.0001f, 1e-6f}, eight.data());
    }
    // the HLG stream: scene light x (1 = the white) at HLG's reference white, 0.265 of its peak
    const float white = Transfer::hlgDecode(0.75f);
    std::vector<float> scaled(room.rgb);
    for (float& v : scaled) v *= white;
    Transfer hlg;
    hlg.kind = Transfer::HLG;
    hlg.build();
    // mode: 2 full (declared), 1 narrow unless the extremes say full (the guess before P667), 3 narrow (declared, narrow frames)
    for (int mode : {2, 1, 3}) {
        std::vector<Owned<uint16_t>> frames;
        for (int j = 0; j < 4; ++j) frames.push_back(encodeHlg(scaled, w, h, 0.004f, 950 + j, mode == 3));
        std::vector<Frame420<uint16_t>> views;
        for (auto& f : frames) views.push_back(f.view());
        Decoder d;
        d.bits = 10;
        d.bt2020 = true;
        d.narrow = mode != 2;
        d.rangeKnown = mode != 1;
        std::vector<float> ten(std::size_t(w) * h * 3);
        const Report rep = mergeBurst(views, info, set, hlg, d, NoiseFallback{0.0001f, 1e-6f}, ten.data());
        // brightness: the median luminance ratio of 8 x 8 blocks (the two bursts' noise averaged out); contrast: p90 / p10 of the
        // luminance; both against the 8-bit result
        std::vector<float> ratio, l8, l10;
        for (std::size_t p = 0; p < std::size_t(w) * h; ++p) {
            l8.push_back(luminance(&eight[p * 3]));
            l10.push_back(luminance(&ten[p * 3]));
        }
        for (int by = 0; by + 8 <= h; by += 8)
            for (int bx = 0; bx + 8 <= w; bx += 8) {
                double a = 0, b = 0;
                for (int y = by; y < by + 8; ++y)
                    for (int x = bx; x < bx + 8; ++x) {
                        a += l8[std::size_t(y) * w + x];
                        b += l10[std::size_t(y) * w + x];
                    }
                if (a > 64 * 0.01) ratio.push_back(float(b / a));
            }
        auto pct = [](std::vector<float> v, float q) {
            const std::size_t k = std::min(v.size() - 1, std::size_t(q * float(v.size())));
            std::nth_element(v.begin(), v.begin() + k, v.end());
            return v[k];
        };
        const float bright = pct(ratio, 0.5f), hi = pct(ratio, 0.98f), lo = pct(ratio, 0.02f);
        const float contrast = (pct(l10, 0.9f) / pct(l10, 0.1f)) / (pct(l8, 0.9f) / pct(l8, 0.1f));
        const float mean10 = std::accumulate(l10.begin(), l10.end(), 0.f) / float(l10.size());
        const float mean8 = std::accumulate(l8.begin(), l8.end(), 0.f) / float(l8.size());
        std::printf("HLG10 vs 8-bit (%s): brightness x%.3f (2 %% .. 98 %% of 8x8 blocks x%.3f .. x%.3f), mean x%.3f, contrast p90/p10 x%.3f, %s\n",
                    mode == 2 ? "full range declared" : mode == 1 ? "full range, range guessed" : "narrow range declared", bright, lo,
                    hi, mean10 / mean8, contrast, rep.decodeNote.c_str());
        if (mode == 1) {
            // the guess the phone's photo took: narrow - the room's light no longer the 8-bit one's
            CHECK(rep.decodeNote == "narrow range");
            CHECK(hi > 1.15f || contrast > 1.15f);
        } else {
            CHECK(std::fabs(bright - 1.f) < 0.02f);
            CHECK(lo > 0.95f && hi < 1.05f);
            CHECK(std::fabs(contrast - 1.f) < 0.03f);
            CHECK(std::fabs(mean10 / mean8 - 1.f) < 0.02f);
            CHECK(rep.decodeNote == (mode == 2 ? "full range (declared)" : "narrow range (declared)"));
        }
    }
}

int main() {
    checkStages();
    checkPrepared();
    checkSixteenBit();
    checkAlignment();
    checkNoiseAndMerge();
    checkSharpestBase();
    checkGuidedChroma();
    checkBracket();
    checkTenBitHlg();
    checkHlgMatchesEightBit();
    if (failures) {
        std::fprintf(stderr, "check_yuv_merge: %d failures\n", failures);
        return 1;
    }
    std::printf("check_yuv_merge: OK\n");
    return 0;
}
