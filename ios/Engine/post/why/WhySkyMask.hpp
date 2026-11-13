// WhySkyMask.java one to one: the sky's cells (whyLow statistics per cell) decided into a sky mask on the CPU.
#pragma once

#include <algorithm>
#include <array>
#include <climits>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace scam_post {
namespace WhySkyMask {

constexpr int CELLS_LONG = 64, SAMPLES = 32;
constexpr int TOP_ROW0 = 0, TOP_COL0 = 1, TOP_ROWN = 2, TOP_COLN = 3;
constexpr float EV_LO = 10.5f, EV_HI = 12.f;
constexpr float OVER_MEDIAN_LO = 1.5f, OVER_MEDIAN_HI = 2.5f;
constexpr float GROUND_MIN = 0.1f, GROUND_PERCENTILE = 0.2f;
constexpr float SKY_STRUCTURE = 0.15f;
constexpr float TEX_LO = 0.06f, TEX_HI = 0.12f;
constexpr float SPREAD_LO = 0.45f, SPREAD_HI = 0.9f;
constexpr float CHROMA_LO = 0.04f, CHROMA_HI = 0.08f;
constexpr float WARM_LO = 0.008f, WARM_HI = 0.02f, RED_LO = 0.015f, RED_HI = 0.03f;
constexpr float SEED = 0.5f, GROW = 0.3f, MIN_COVERAGE = 0.05f;
constexpr float CLIP_SHARE = 0.002f, CLIP_LEVEL = 0.9f;
constexpr float CORE_OVER = 2.5f, GLOW_OVER = 3.f, GLOW_REACH = 1.f;
constexpr float SUN_EV = 17.5f;
constexpr float BLOB_MAX_SHARE = 0.06f, BLOB_FILL = 0.35f, BLOB_ASPECT = 2.5f, BLOB_SOLID = 0.9f;
constexpr float RING_FALL_12 = 0.12f, RING_FALL_23 = 0.12f, RING_SMOOTH = 0.35f, RING_EASE = 1.3f, RING_OVER = 0.4f, RING_SIDE = 0.1f;
constexpr float BLOB_FULL = 0.9f, DISC_FILL = 0.55f, DISC_ASPECT = 1.7f, DISC_RING = 0.25f;
constexpr int DISC_CELLS = 9;
constexpr float RING_TRIM = 0.5f, BLOB_ROUND_SHARE = 0.15f;
constexpr float JOINT_STEP = 0.25f, JOINT_FLAT = 0.05f, JOINT_GREY = 0.015f, JOINT_AREA = 0.02f;
constexpr float JOINT_HUE = 0.012f, JOINT_SIDE_WANDER = 1.f;
constexpr int JOINT_RUN_MIN = 4;
constexpr float SHADE_EV = 9.f, SHADE_EV_HI = 10.5f, SHADE_STOPS = 4.5f, SHADE_STOPS_LO = 3.5f;
constexpr float TOP_FROM = 0.2f, TOP_TO = 1.0f;

inline double log2d(double v) { return std::log(v) / std::log(2.0); }
inline int cellPx(int w, int h) { return std::max(4, (std::max(w, h) + CELLS_LONG - 1) / CELLS_LONG); }
inline int step(int cell) { return std::max(1, cell / SAMPLES); }
inline int topEdge(int rotation, bool mirror) {
    switch (((rotation % 360) + 360) % 360) {
    case 90: return TOP_COL0;
    case 180: return mirror ? TOP_ROW0 : TOP_ROWN;
    case 270: return TOP_COLN;
    default: return mirror ? TOP_ROWN : TOP_ROW0;
    }
}

struct Scene { float evOffset; int top; float ceiling; };

struct Cells {
    int nx = 0, ny = 0, cell = 0, stepPx = 0;
    std::vector<float> r, g, b, l, sd, tex, max, clip;
    Cells(int nx, int ny, int cell, int step) : nx(nx), ny(ny), cell(cell), stepPx(step) {
        const size_t n = size_t(nx) * size_t(ny);
        for (auto* v : {&r, &g, &b, &l, &sd, &tex, &max, &clip}) v->assign(n, 0.f);
    }
    static Cells grid(int w, int h) {
        const int c = cellPx(w, h);
        return Cells((w + c - 1) / c, (h + c - 1) / c, c, step(c));
    }
    static Cells fromGpu(int w, int h, const std::vector<float>& mode0, const std::vector<float>& mode1) {
        Cells c = grid(w, h);
        for (size_t i = 0; i < size_t(c.nx) * size_t(c.ny); i++) {
            c.r[i] = mode0[4 * i];
            c.g[i] = mode0[4 * i + 1];
            c.b[i] = mode0[4 * i + 2];
            c.l[i] = mode0[4 * i + 3];
            c.sd[i] = float(std::sqrt(std::max(0.0, double(mode1[4 * i]) - double(c.l[i]) * double(c.l[i]))));
            c.tex[i] = mode1[4 * i + 1];
            c.max[i] = mode1[4 * i + 2];
            c.clip[i] = mode1[4 * i + 3];
        }
        return c;
    }
};

struct Mask {
    int nx = 0, ny = 0;
    std::vector<float> cells;
    float keep = 1;
    bool backlit = false;
    float coverage = 0, skyEv = 0, subjectEv = 0, skyLog2 = 0;
    std::string backlitWhy;
    std::array<float, 2> tops() const { return {skyLog2 + TOP_FROM, skyLog2 + TOP_TO}; }
};
struct Decision { std::shared_ptr<Mask> mask; std::string log; };

struct Frame {
    int top, nx, ny, us, vs;
    Frame(const Cells& c, int top) : top(top), nx(c.nx), ny(c.ny) {
        const bool rows = top == TOP_ROW0 || top == TOP_ROWN;
        us = rows ? nx : ny;
        vs = rows ? ny : nx;
    }
    int at(int u, int v) const {
        if (u < 0 || v < 0 || u >= us || v >= vs) return -1;
        switch (top) {
        case TOP_COL0: return u * nx + v;
        case TOP_COLN: return u * nx + (nx - 1 - v);
        case TOP_ROWN: return (ny - 1 - v) * nx + u;
        default: return v * nx + u;
        }
    }
    bool far(int i) const {
        const int x = i % nx, y = i / nx;
        switch (top) {
        case TOP_COL0: return 2 * x >= nx;
        case TOP_COLN: return 2 * x < nx;
        case TOP_ROWN: return 2 * y < ny;
        default: return 2 * y >= ny;
        }
    }
};

inline float smooth(float e0, float e1, float x) {
    const float t = std::max(0.f, std::min(1.f, (x - e0) / (e1 - e0)));
    return t * t * (3.f - 2.f * t);
}
inline std::array<float, 3> oklab(float r, float g, float b) {
    const double l = std::cbrt(std::max(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b, 0.0));
    const double m = std::cbrt(std::max(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b, 0.0));
    const double s = std::cbrt(std::max(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b, 0.0));
    return {float(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s), float(1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s),
            float(0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)};
}
inline float colourGate(float r, float g, float b) {
    const float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    if (!(y > 0.f) || !std::isfinite(y)) return 0.f;
    const float k = 0.5f / y;
    const auto lab = oklab(r * k, g * k, b * k);
    const float chroma = float(std::hypot(double(lab[1]), double(lab[2])));
    const float blue = std::max(0.f, std::min(1.f, -lab[2] / std::max(chroma, 1e-6f)));
    return (1.f - smooth(CHROMA_LO, CHROMA_HI, chroma) * (1.f - smooth(0.5f, 0.8f, blue)))
           * (1.f - smooth(WARM_LO, WARM_HI, lab[2])) * (1.f - smooth(RED_LO, RED_HI, lab[1]));
}
inline float colourGate(const Cells& c, size_t i) { return colourGate(c.r[i], c.g[i], c.b[i]); }

inline bool topCanSeed(const std::vector<float>& mean, float evOffset) {
    if (mean.empty() || mean.size() % 4 != 0 || !std::isfinite(evOffset)) return true;
    for (size_t i = 0; i < mean.size(); i += 4) {
        if (!std::isfinite(mean[i]) || !std::isfinite(mean[i + 1]) || !std::isfinite(mean[i + 2]) || !std::isfinite(mean[i + 3])) return true;
        const float upper = smooth(EV_LO, EV_HI, mean[i + 3] + evOffset) * colourGate(mean[i], mean[i + 1], mean[i + 2]);
        if (!(upper < SEED - 0.00001f)) return true;
    }
    return false;
}

inline float median(const std::vector<float>& v, const std::vector<bool>* take) {
    std::vector<float> s;
    for (size_t i = 0; i < v.size(); i++) if ((!take || (*take)[i]) && std::isfinite(v[i])) s.push_back(v[i]);
    if (s.empty()) return NAN;
    std::sort(s.begin(), s.end());
    const size_t k = s.size();
    return (k & 1) == 1 ? s[k / 2] : 0.5f * (s[k / 2 - 1] + s[k / 2]);
}
inline float medianOf(std::vector<float> s) { return median(s, nullptr); }
inline float percentile(const std::vector<float>& v, const std::vector<bool>* take, float q) {
    std::vector<float> t;
    for (size_t i = 0; i < v.size(); i++) if ((!take || (*take)[i]) && std::isfinite(v[i])) t.push_back(v[i]);
    if (t.empty()) return NAN;
    std::sort(t.begin(), t.end());
    const int k = int(t.size());
    return t[size_t(std::min(k - 1, int(std::floor(double(q * float(k - 1) + 0.5f)))))];
}
inline float sum(const std::vector<float>& v) { float s = 0.f; for (float x : v) s += x; return s; }
inline float weightedMean(const std::vector<float>& v, const std::vector<float>& w) {
    double s = 0, sw = 0;
    for (size_t i = 0; i < v.size(); i++) if (w[i] > 0.f && std::isfinite(v[i])) { s += double(w[i]) * double(v[i]); sw += double(w[i]); }
    return sw > 0 ? float(s / sw) : NAN;
}

inline float weight(const Cells& c, const std::vector<float>& w, const std::vector<bool>& veto, int x, int y) {
    if (x < 0 || y < 0 || x >= c.nx || y >= c.ny) return 0.f;
    const size_t i = size_t(y * c.nx + x);
    return veto[i] ? 0.f : w[i];
}
inline std::vector<float> bridge(const Cells& c, const std::vector<float>& w0, const std::vector<bool>& veto) {
    std::vector<float> out = w0;
    const int axes[4][2] = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
    for (int y = 0; y < c.ny; y++)
        for (int x = 0; x < c.nx; x++) {
            const size_t i = size_t(y * c.nx + x);
            if (veto[i]) { out[i] = 0.f; continue; }
            for (const auto& a : axes) {
                float plus = 0.f, minus = 0.f;
                for (int k = 1; k <= 2; k++) {
                    plus = std::max(plus, weight(c, w0, veto, x + k * a[0], y + k * a[1]));
                    minus = std::max(minus, weight(c, w0, veto, x - k * a[0], y - k * a[1]));
                }
                out[i] = std::max(out[i], std::min(plus, minus));
            }
        }
    return out;
}
inline std::vector<float> flood(const Cells& c, const std::vector<float>& w0, int top) {
    const int n = c.nx * c.ny;
    std::vector<bool> seen(size_t(n), false);
    std::vector<int> queue(static_cast<size_t>(n));
    int head = 0, tail = 0;
    const int edge = top == TOP_COL0 || top == TOP_COLN ? c.ny : c.nx;
    for (int k = 0; k < edge; k++) {
        int i;
        switch (top) {
        case TOP_COL0: i = k * c.nx; break;
        case TOP_COLN: i = k * c.nx + c.nx - 1; break;
        case TOP_ROWN: i = (c.ny - 1) * c.nx + k; break;
        default: i = k; break;
        }
        if (w0[size_t(i)] >= SEED && !seen[size_t(i)]) { seen[size_t(i)] = true; queue[size_t(tail++)] = i; }
    }
    while (head < tail) {
        const int i = queue[size_t(head++)];
        const int x = i % c.nx, y = i / c.nx;
        const int next[4][2] = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
        for (const auto& p : next) {
            if (p[0] < 0 || p[1] < 0 || p[0] >= c.nx || p[1] >= c.ny) continue;
            const int j = p[1] * c.nx + p[0];
            if (!seen[size_t(j)] && w0[size_t(j)] >= GROW) { seen[size_t(j)] = true; queue[size_t(tail++)] = j; }
        }
    }
    std::vector<float> w(static_cast<size_t>(n));
    for (int i = 0; i < n; i++) w[size_t(i)] = seen[size_t(i)] ? w0[size_t(i)] : 0.f;
    return w;
}
inline void grow(const Cells& c, std::vector<bool>& region, float floor) {
    const int n = c.nx * c.ny;
    std::vector<int> queue(static_cast<size_t>(n));
    int head = 0, tail = 0;
    for (int i = 0; i < n; i++) if (region[size_t(i)]) queue[size_t(tail++)] = i;
    while (head < tail) {
        const int i = queue[size_t(head++)];
        const int x = i % c.nx, y = i / c.nx;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                const int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= c.nx || yy >= c.ny) continue;
                const int j = yy * c.nx + xx;
                if (!region[size_t(j)] && c.l[size_t(j)] >= floor) { region[size_t(j)] = true; queue[size_t(tail++)] = j; }
            }
    }
}
inline float ringLevel(std::vector<float>& v, int n) {
    std::sort(v.begin(), v.begin() + n);
    const float floor = (n % 2 == 1 ? v[size_t(n / 2)] : 0.5f * (v[size_t(n / 2 - 1)] + v[size_t(n / 2)])) - RING_TRIM;
    double s = 0;
    int k = 0;
    for (int i = 0; i < n; i++) { if (v[size_t(i)] < floor) continue; s += double(v[size_t(i)]); k++; }
    return float(s / k);
}

inline int glowCores(const Cells& c, float skyL, std::vector<bool>& core) {
    const int n = c.nx * c.ny;
    if (!std::isfinite(skyL)) return 0;
    std::vector<int> label(size_t(n), -1), queue(static_cast<size_t>(n)), dist(static_cast<size_t>(n)), q2(static_cast<size_t>(n));
    int found = 0, comp = 0;
    for (int s0 = 0; s0 < n; s0++) {
        if (label[size_t(s0)] >= 0 || !(c.clip[size_t(s0)] >= CLIP_SHARE)) continue;
        int head = 0, tail = 0;
        queue[size_t(tail++)] = s0;
        label[size_t(s0)] = comp;
        while (head < tail) {
            const int i = queue[size_t(head++)];
            const int x = i % c.nx, y = i / c.nx;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    const int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= c.nx || yy >= c.ny) continue;
                    const int k = yy * c.nx + xx;
                    if (label[size_t(k)] < 0 && c.clip[size_t(k)] >= CLIP_SHARE) { label[size_t(k)] = comp; queue[size_t(tail++)] = k; }
                }
        }
        const int area = tail;
        int x0 = c.nx, y0 = c.ny, x1 = -1, y1 = -1;
        float solid = 0.f;
        double cx = 0, cy = 0;
        for (int k = 0; k < area; k++) {
            const int i = queue[size_t(k)], x = i % c.nx, y = i / c.nx;
            x0 = std::min(x0, x); y0 = std::min(y0, y); x1 = std::max(x1, x); y1 = std::max(y1, y);
            solid = std::max(solid, c.clip[size_t(i)]);
            cx += x + 0.5;
            cy += y + 0.5;
        }
        cx /= area;
        cy /= area;
        const int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        int full = 0, fx0 = c.nx, fy0 = c.ny, fx1 = -1, fy1 = -1;
        for (int k = 0; k < area; k++) {
            const int i = queue[size_t(k)];
            if (c.clip[size_t(i)] < BLOB_FULL) continue;
            full++;
            fx0 = std::min(fx0, i % c.nx); fy0 = std::min(fy0, i / c.nx); fx1 = std::max(fx1, i % c.nx); fy1 = std::max(fy1, i / c.nx);
        }
        const int fw = fx1 - fx0 + 1, fh = fy1 - fy0 + 1;
        const bool round = full >= DISC_CELLS && float(full) >= DISC_FILL * float(fw) * float(fh) && float(std::max(fw, fh)) <= DISC_ASPECT * float(std::min(fw, fh));
        const bool compact = round ? float(area) <= BLOB_ROUND_SHARE * float(n)
                                   : float(area) <= std::max(1.f, BLOB_MAX_SHARE * float(n)) && float(area) >= BLOB_FILL * float(bw) * float(bh)
                                         && float(std::max(bw, bh)) <= BLOB_ASPECT * float(std::min(bw, bh)) && solid >= BLOB_SOLID;
        comp++;
        if (!compact) continue;
        std::fill(dist.begin(), dist.end(), INT_MAX);
        head = 0;
        int t2 = 0;
        for (int k = 0; k < area; k++) { dist[size_t(queue[size_t(k)])] = 0; q2[size_t(t2++)] = queue[size_t(k)]; }
        std::vector<std::vector<float>> ringL(4, std::vector<float>(q2.size())), sideL(4, std::vector<float>(q2.size()));
        int ringN[4] = {0, 0, 0, 0}, sideN[4] = {0, 0, 0, 0};
        while (head < t2) {
            const int i = q2[size_t(head++)];
            if (dist[size_t(i)] >= 3) continue;
            const int x = i % c.nx, y = i / c.nx;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    const int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= c.nx || yy >= c.ny) continue;
                    const int k = yy * c.nx + xx;
                    if (dist[size_t(k)] != INT_MAX) continue;
                    dist[size_t(k)] = dist[size_t(i)] + 1;
                    q2[size_t(t2++)] = k;
                    if (!std::isfinite(c.l[size_t(k)]) || c.l[size_t(k)] < skyL - 2.f) continue;
                    const int d = dist[size_t(k)];
                    ringL[size_t(d)][size_t(ringN[d]++)] = c.l[size_t(k)];
                    if (d == 1) {
                        const double ax = xx + 0.5 - cx, ay = yy + 0.5 - cy;
                        const int side = std::fabs(ax) >= std::fabs(ay) ? (ax >= 0 ? 0 : 1) : (ay >= 0 ? 2 : 3);
                        sideL[size_t(side)][size_t(sideN[side]++)] = c.l[size_t(k)];
                    }
                }
        }
        if (ringN[1] == 0 || ringN[2] == 0 || ringN[3] == 0) continue;
        const double m1 = ringLevel(ringL[1], ringN[1]), m2 = ringLevel(ringL[2], ringN[2]), m3 = ringLevel(ringL[3], ringN[3]);
        const bool disc = round && m1 - double(skyL) >= double(DISC_RING);
        if (!disc && !(m1 - m2 >= double(RING_FALL_12) && m2 - m3 >= double(RING_FALL_23) && m2 - m3 >= double(RING_SMOOTH) * (m1 - m2)
                       && m2 - m3 <= double(RING_EASE) * (m1 - m2) && m1 - double(skyL) >= double(RING_OVER))) continue;
        int sides = 0, misses = 0;
        for (int k = 0; k < 4; k++) {
            if (sideN[k] == 0) continue;
            sides++;
            if (double(ringLevel(sideL[size_t(k)], sideN[k])) - m3 < double(RING_SIDE)) misses++;
        }
        if (!disc && (sides == 0 || misses > (sides == 4 ? 1 : 0))) continue;
        for (int k = 0; k < area; k++) core[size_t(queue[size_t(k)])] = true;
        found++;
    }
    return found;
}

inline bool topStep(const Cells& c, const Frame& fr, const std::vector<int>& label, int me, const std::vector<int>& queue, int tail,
                    const std::vector<int>& cu, const std::vector<int>& cv, int run) {
    std::vector<float> steps;
    for (int k = 0; k < tail; k++) {
        const int i = queue[size_t(k)];
        const int up = fr.at(cu[size_t(i)], cv[size_t(i)] - 1);
        if (up < 0 || label[size_t(up)] == me) continue;
        float above = -INFINITY;
        for (int d = 1; d <= 3; d++) {
            const int j = fr.at(cu[size_t(i)], cv[size_t(i)] - d);
            if (j >= 0 && label[size_t(j)] != me && std::isfinite(c.l[size_t(j)])) above = std::max(above, c.l[size_t(j)]);
        }
        if (above > -INFINITY) steps.push_back(above - c.l[size_t(i)]);
    }
    return int(steps.size()) >= run && medianOf(steps) >= JOINT_STEP;
}
inline bool sideStep(const Cells& c, const Frame& fr, const std::vector<int>& label, int me, const std::vector<int>& queue, int tail,
                     const std::vector<int>& cu, const std::vector<int>& cv, int tall) {
    std::vector<int> lo(size_t(fr.vs), INT_MAX), hi(size_t(fr.vs), INT_MIN);
    for (int k = 0; k < tail; k++) {
        const int i = queue[size_t(k)];
        lo[size_t(cv[size_t(i)])] = std::min(lo[size_t(cv[size_t(i)])], cu[size_t(i)]);
        hi[size_t(cv[size_t(i)])] = std::max(hi[size_t(cv[size_t(i)])], cu[size_t(i)]);
    }
    std::vector<float> steps;
    int rows = 0;
    double sl = 0, sl2 = 0, sh = 0, sh2 = 0;
    for (int v = 0; v < fr.vs; v++) {
        if (lo[size_t(v)] == INT_MAX) continue;
        rows++;
        sl += lo[size_t(v)];
        sl2 += double(lo[size_t(v)]) * lo[size_t(v)];
        sh += hi[size_t(v)];
        sh2 += double(hi[size_t(v)]) * hi[size_t(v)];
        for (int side = -1; side <= 1; side += 2) {
            const int edge = side < 0 ? lo[size_t(v)] : hi[size_t(v)];
            const int own = fr.at(edge, v);
            float beside = -INFINITY;
            for (int d = 1; d <= 3; d++) {
                const int j = fr.at(edge + side * d, v);
                if (j >= 0 && label[size_t(j)] != me && std::isfinite(c.l[size_t(j)])) beside = std::max(beside, c.l[size_t(j)]);
            }
            if (beside > -INFINITY && own >= 0) steps.push_back(beside - c.l[size_t(own)]);
        }
    }
    if (rows < tall || int(steps.size()) < tall) return false;
    const double wanderLo = std::sqrt(std::max(0.0, sl2 / rows - (sl / rows) * (sl / rows)));
    const double wanderHi = std::sqrt(std::max(0.0, sh2 / rows - (sh / rows) * (sh / rows)));
    return wanderLo <= double(JOINT_SIDE_WANDER) && wanderHi <= double(JOINT_SIDE_WANDER) && medianOf(steps) >= JOINT_STEP;
}
inline bool skyHasColour(const std::vector<float>& w, const std::vector<int>& label, int me, const std::vector<float>& ca, const std::vector<float>& cb,
                         const std::vector<float>& ch, float ra, float rb) {
    std::vector<float> a, b;
    for (size_t i = 0; i < w.size(); i++) {
        if (label[i] == me || !(w[i] >= GROW) || !(ch[i] > JOINT_GREY)) continue;
        a.push_back(ca[i]);
        b.push_back(cb[i]);
    }
    if (int(a.size()) < JOINT_RUN_MIN) return false;
    return std::fabs(medianOf(a) - ra) <= JOINT_HUE && std::fabs(medianOf(b) - rb) <= JOINT_HUE;
}
inline int joints(const Cells& c, const Frame& fr, const std::vector<float>& w, std::vector<bool>& veto) {
    const int n = c.nx * c.ny;
    std::vector<bool> flat(size_t(n), false);
    std::vector<float> ca(static_cast<size_t>(n)), cb(static_cast<size_t>(n)), ch(static_cast<size_t>(n));
    for (int i = 0; i < n; i++) {
        const size_t si = size_t(i);
        flat[si] = w[si] >= GROW && std::isfinite(c.l[si]);
        const float y = 0.2126f * c.r[si] + 0.7152f * c.g[si] + 0.0722f * c.b[si];
        if (!(y > 0.f) || !std::isfinite(y)) { ch[si] = NAN; flat[si] = false; continue; }
        const auto lab = oklab(c.r[si] * 0.5f / y, c.g[si] * 0.5f / y, c.b[si] * 0.5f / y);
        ca[si] = lab[1];
        cb[si] = lab[2];
        ch[si] = float(std::hypot(double(lab[1]), double(lab[2])));
    }
    std::vector<int> label(size_t(n), -1), queue(static_cast<size_t>(n)), cu(static_cast<size_t>(n)), cv(static_cast<size_t>(n));
    const int run = std::max(JOINT_RUN_MIN, fr.us / 8), tall = std::max(JOINT_RUN_MIN, fr.vs / 8);
    int marked = 0, comp = 0;
    for (int u = 0; u < fr.us; u++)
        for (int v = 0; v < fr.vs; v++) { const int i = fr.at(u, v); cu[size_t(i)] = u; cv[size_t(i)] = v; }
    for (int s0 = 0; s0 < n; s0++) {
        if (!flat[size_t(s0)] || label[size_t(s0)] >= 0) continue;
        int head = 0, tail = 0;
        queue[size_t(tail++)] = s0;
        label[size_t(s0)] = comp;
        bool onTop = false;
        while (head < tail) {
            const int i = queue[size_t(head++)];
            if (cv[size_t(i)] == 0) onTop = true;
            const int next[4] = {fr.at(cu[size_t(i)] + 1, cv[size_t(i)]), fr.at(cu[size_t(i)] - 1, cv[size_t(i)]),
                                 fr.at(cu[size_t(i)], cv[size_t(i)] + 1), fr.at(cu[size_t(i)], cv[size_t(i)] - 1)};
            for (int k : next) {
                if (k < 0 || label[size_t(k)] >= 0 || !flat[size_t(k)] || !(std::fabs(c.l[size_t(k)] - c.l[size_t(i)]) <= JOINT_FLAT)) continue;
                label[size_t(k)] = comp;
                queue[size_t(tail++)] = k;
            }
        }
        const int me = comp++;
        const bool stepFound = onTop ? sideStep(c, fr, label, me, queue, tail, cu, cv, tall)
                                     : float(tail) >= std::max(float(run), JOINT_AREA * float(n)) && topStep(c, fr, label, me, queue, tail, cu, cv, run);
        if (!stepFound) continue;
        double sa = 0, sb = 0;
        for (int k = 0; k < tail; k++) { sa += double(ca[size_t(queue[size_t(k)])]); sb += double(cb[size_t(queue[size_t(k)])]); }
        const float ra = float(sa / tail), rb = float(sb / tail);
        if (std::hypot(double(ra), double(rb)) > double(JOINT_GREY) && skyHasColour(w, label, me, ca, cb, ch, ra, rb)) continue;
        for (int k = 0; k < tail; k++) veto[size_t(queue[size_t(k)])] = true;
        marked += tail;
    }
    return marked;
}

inline std::string fmt(const char* f, double a) { char b[64]; std::snprintf(b, sizeof b, f, a); return b; }

inline Decision decide(const Cells& c, const Scene& s) {
    if (!std::isfinite(s.evOffset)) return {nullptr, "none (exposure unknown)"};
    const int n = c.nx * c.ny;
    const Frame fr(c, s.top);
    std::vector<float> w0(static_cast<size_t>(n)), look(static_cast<size_t>(n));
    std::vector<bool> farHalf(size_t(n), false), ground(size_t(n), false);
    int farN = 0, groundN = 0;
    for (int i = 0; i < n; i++) {
        const size_t si = size_t(i);
        farHalf[si] = fr.far(i);
        if (!std::isfinite(c.l[si]) || !std::isfinite(c.tex[si]) || !std::isfinite(c.sd[si])) continue;
        look[si] = smooth(EV_LO, EV_HI, c.l[si] + s.evOffset) * (1.f - smooth(TEX_LO, TEX_HI, c.tex[si]))
                   * (1.f - smooth(SPREAD_LO, SPREAD_HI, c.sd[si])) * colourGate(c, si);
        if (!farHalf[si]) continue;
        farN++;
        ground[si] = look[si] < 0.5f;
        if (ground[si]) groundN++;
    }
    float med = median(c.l, &farHalf);
    if (!std::isfinite(med)) med = median(c.l, nullptr);
    const float below = float(groundN) >= GROUND_MIN * float(farN) ? median(c.l, &ground) : percentile(c.l, &farHalf, GROUND_PERCENTILE);
    if (std::isfinite(below) && below < med) {
        double sl = 0, sl2 = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            const size_t si = size_t(i);
            if (!(look[si] >= 0.5f) || !(c.l[si] - below >= OVER_MEDIAN_LO)) continue;
            sl += double(c.l[si]);
            sl2 += double(c.l[si]) * double(c.l[si]);
            k++;
        }
        const double spread = k > 1 ? std::sqrt(std::max(0.0, sl2 / k - (sl / k) * (sl / k))) : 0;
        if (spread >= double(SKY_STRUCTURE)) med = below;
    }
    for (int i = 0; i < n; i++) {
        const size_t si = size_t(i);
        if (!std::isfinite(c.l[si]) || !std::isfinite(c.tex[si]) || !std::isfinite(c.sd[si])) continue;
        w0[si] = look[si] * smooth(OVER_MEDIAN_LO, OVER_MEDIAN_HI, c.l[si] - med);
    }
    std::vector<bool> joint(size_t(n), false), core(size_t(n), false), veto(size_t(n), false);
    std::vector<float> w = flood(c, bridge(c, w0, veto), s.top);
    float total = sum(w);
    char buf[256];
    if (total / float(n) < MIN_COVERAGE) {
        std::snprintf(buf, sizeof buf, "none (sky %.1f %% of the frame, under %.0f %%; median EV %.1f)", double(100.f * total / float(n)),
                      double(100.f * MIN_COVERAGE), double(med + s.evOffset));
        return {nullptr, buf};
    }
    const int joined = joints(c, fr, w, joint);
    if (joined > 0) {
        for (int i = 0; i < n; i++) if (joint[size_t(i)]) veto[size_t(i)] = true;
        w = flood(c, bridge(c, w0, veto), s.top);
        total = sum(w);
        if (total / float(n) < MIN_COVERAGE) {
            std::snprintf(buf, sizeof buf, "none (sky %.1f %% of the frame above a joined surface)", double(100.f * total / float(n)));
            return {nullptr, buf};
        }
    }
    float skyL = weightedMean(c.l, w);
    const float ceilingL = float(log2d(double(std::max(CLIP_LEVEL * s.ceiling, 1e-6f))));
    bool anyCore = false;
    for (int i = 0; i < n; i++) {
        const size_t si = size_t(i);
        core[si] = (c.clip[si] >= CLIP_SHARE && (ceilingL - skyL >= CORE_OVER || ceilingL + s.evOffset >= SUN_EV)) || c.l[si] >= skyL + GLOW_OVER;
    }
    const int glow = glowCores(c, skyL, core);
    for (int i = 0; i < n; i++) if (core[size_t(i)]) { anyCore = true; veto[size_t(i)] = true; }
    if (anyCore) {
        w = flood(c, bridge(c, w0, veto), s.top);
        total = sum(w);
        if (total / float(n) < MIN_COVERAGE) {
            std::snprintf(buf, sizeof buf, "none (sky %.1f %% of the frame beside a clipped core)", double(100.f * total / float(n)));
            return {nullptr, buf};
        }
        skyL = weightedMean(c.l, w);
    }
    if (anyCore) grow(c, core, skyL + GLOW_REACH);
    bool coreTouches = false;
    for (int y = 0; y < c.ny && !coreTouches; y++)
        for (int x = 0; x < c.nx && !coreTouches; x++) {
            if (!core[size_t(y * c.nx + x)]) continue;
            for (int dy = -1; dy <= 1 && !coreTouches; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    const int xx = x + dx, yy = y + dy;
                    if (xx >= 0 && yy >= 0 && xx < c.nx && yy < c.ny && std::max(w0[size_t(yy * c.nx + xx)], w[size_t(yy * c.nx + xx)]) >= GROW) {
                        coreTouches = true;
                        break;
                    }
                }
        }
    std::vector<bool> subject(size_t(n), false);
    for (int i = 0; i < n; i++) subject[size_t(i)] = farHalf[size_t(i)] && w[size_t(i)] < 0.05f && !core[size_t(i)] && std::isfinite(c.l[size_t(i)]);
    const float subjectL = median(c.l, &subject);
    const float shade = std::isfinite(subjectL)
                            ? (1.f - smooth(SHADE_EV, SHADE_EV_HI, subjectL + s.evOffset)) * smooth(SHADE_STOPS_LO, SHADE_STOPS, skyL - subjectL) : 0.f;
    const float keep = coreTouches ? 0.f : 1.f - shade;
    const bool backlit = keep < 1.f;
    std::string why = coreTouches ? (glow > 0 ? "a clipped core with a radial glow touches the sky" : "a clipped core touches the sky")
                      : shade > 0.f ? fmt("the subject is indoor-dim under the sky: %.0f %%", double(100.f * shade)) : "";
    std::vector<float> out(size_t(4 * n));
    for (int y = 0; y < c.ny; y++)
        for (int x = 0; x < c.nx; x++) {
            const int i = y * c.nx + x;
            float least = 1.f;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    const int xx = x + dx, yy = y + dy;
                    if (xx >= 0 && yy >= 0 && xx < c.nx && yy < c.ny) least = std::min(least, w[size_t(yy * c.nx + xx)]);
                }
            bool nearCore = false;
            for (int dy = -2; dy <= 2 && !nearCore; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    const int xx = x + dx, yy = y + dy;
                    if (xx >= 0 && yy >= 0 && xx < c.nx && yy < c.ny && core[size_t(yy * c.nx + xx)]) { nearCore = true; break; }
                }
            double sw = 0, sl = 0;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    const int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= c.nx || yy >= c.ny) continue;
                    const float wv = w[size_t(yy * c.nx + xx)];
                    if (wv > 0.f && std::isfinite(c.l[size_t(yy * c.nx + xx)])) { sw += double(wv); sl += double(wv) * double(c.l[size_t(yy * c.nx + xx)]); }
                }
            out[size_t(4 * i)] = w[size_t(i)];
            out[size_t(4 * i + 1)] = nearCore ? 0.f : w[size_t(i)] * least;
            out[size_t(4 * i + 2)] = sw > 0.05 ? float(sl / sw) : skyL;
        }
    auto m = std::make_shared<Mask>();
    m->nx = c.nx;
    m->ny = c.ny;
    m->cells = out;
    m->keep = keep;
    m->backlit = keep < 0.5f;
    m->coverage = sum(w) / float(n);
    m->skyLog2 = skyL;
    m->skyEv = skyL + s.evOffset;
    m->subjectEv = subjectL + s.evOffset;
    m->backlitWhy = why;
    std::snprintf(buf, sizeof buf, "%.1f %% of the frame, sky EV %.1f, subject EV %.1f, median EV %.1f", double(100.f * m->coverage), double(m->skyEv),
                  double(m->subjectEv), double(med + s.evOffset));
    std::string log = buf;
    if (joined > 0) log += ", a joined surface cut off (" + std::to_string(joined) + " cells)";
    if (backlit) log += ", backlit (" + why + "): " + (keep == 0.f ? std::string("the sky's level kept") : fmt("%.0f %% of the sky's lowering", double(100.f * keep)));
    return {m, log};
}

} // namespace WhySkyMask
} // namespace scam_post
