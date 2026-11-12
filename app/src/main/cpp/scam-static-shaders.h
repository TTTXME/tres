#pragma once
#include <cstdint>
#include <cstring>
#include <string>
// P624 «Статика»: the static merge's compute shaders, one source for both backends. Each program is kStHead* + kStCommon + its
// body: GLSL ES 3.10 on the GLES fallback (kStHeadGles: the parameters as a std140 uniform block), GLSL 450 compiled offline to
// SPIR-V (tools/gen_static_spirv.py -> scam-static-spirv.h; kStHeadVk: the same parameters as push constants). Only scalar
// parameters (std140 and the push constants' std430 lay them out the same), storage buffers only (no images / samplers: the
// bilinear weights are the model's own), no float atomics, no subgroup operations on GLES; the Vulkan field pass may sum by
// subgroupAdd (ST_SUBGROUP, a SPIR-V variant used when the device reports subgroup arithmetic in compute).
//
// Model (Lecouat, Ponce, Mairal, ICCV 2021, «Lucas-Kanade Reloaded»): y_k = e_k D B W_k x + n_k. x = the HR estimate at s x the
// RAW grid (s = 2; the 1x photo is its 2 x 2 box), B = the box over a RAW pixel (s x s sub-samples), each bilinear in x
// (together a separable 3-tap kernel per axis at s = 2, 2 taps at s = 1), D = the CFA site's colour, W_k = the frame's
// homography (the GPU KLT + RANSAC alignment) plus a per-tile translation field refined by Gauss-Newton against x (the paper's
// p-step). The adjoint is a gather (no atomics): an HR pixel visits the 2 x 2 RAW pixels of each frame whose footprint can reach
// it and weighs them with exactly the forward weights, so it is the exact transpose of the forward pass.
//
// Coordinates: RAW (LR) pixel (x, y) has its centre at (x + 0.5, y + 0.5) in global LR units; a frame's homography H maps base
// LR to frame LR, Hinv frame to base (both on centres). HR pixel P of the strip has its centre at P + 0.5 in strip HR units:
// global base LR = (P + 0.5) / s + (0, r0).
//
// Buffers (std430): binding 1 = the frame table (per frame 32 floats: H 0..8, Hinv 9..17, S 18, O 19, the divisor to base units
// 20, first window row 21, used 22, the DCG knee 23 and LCG offset 24 (0: one noise zone); then the globals at 32 * K: black[4],
// white, the sites' gains[4], the colour prior's grid width 9, first row 10, rows 11 - P627; the priors' floors 12 / 13 and
// flatK 14 - P627d; edgeK 15 - P627e); bindings 2..7 are each
// program's own (declared in its body). On GLES binding 0 of the uniform blocks is the parameters' block.
namespace scam {
namespace staticmerge {

static const char* kStHeadGles=R"ST(#version 310 es
precision highp float;
precision highp int;
#define ST_GLES 1
#define PARAMS layout(std140, binding = 0) uniform StParams
)ST";
static const char* kStHeadVk=R"ST(#version 450
#define ST_VK 1
#define PARAMS layout(push_constant, std430) uniform StParams
)ST";

static const char* kStCommon=R"ST(
PARAMS {
    int K;        // frames
    int W;        // RAW width
    int H;        // RAW height
    int s;        // HR scale (1 or 2)
    int r0;       // global LR row of the strip's HR row 0 (HR row 0 = LR row r0 exactly)
    int RH;       // HR rows of the strip
    int WH;       // HR width = s * W
    int rows;     // rows of each frame's RAW window
    int cfa;      // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    int mode;     // per-pass switch
    int tile;     // field tile (LR px)
    int ntx;      // field tiles across
    int nty;      // field tiles down
    int spread;   // robust minimum filter radius (LR px)
    int frame0;   // first frame of this dispatch (z index offset)
    int outRows;  // output rows (output pass)
    float mu;     // HQS penalty
    float stepc;  // preconditioned step c (< 2)
    float lc;     // colour-difference Tikhonov weight
    float ll;     // luma Huber-TV weight
    float delta;  // luma Huber threshold
    float robK;   // robust mean test threshold (sigmas); 0 = off
    float robTau; // edge tolerance (LR px of misregistration)
    float clipLvl;// samples at / above this normalised level are left out of the data
    float sig2;   // base frame variance at mid grey (weights' unit)
    float lam;    // field: neighbour coupling (x trace / 2)
    float lamFloor;// field: coupling floor
    float maxShift;// field: |delta| cap (LR px)
    float wbR;    // white balance gains (G = 1): the regularisers work on white-balanced colour (a grey texture is no colour)
    float wbB;
    int bs;       // P627: log2 of the CFA's colour block (0 Bayer, 1 Quad 2x2, 2 Tetra 4x4): the colour period is 2 << bs
    int row0;     // P639: first row of this dispatch (a pass split into short submissions; the field pass: its first tile row)
} pc;

layout(std430, binding = 1) readonly buffer StFrames { float fi[]; };

// colour of a RAW site (global LR coordinates): the CFA order of the colour blocks (P627: b x b blocks of one colour, b = 1 << bs)
int stChannel(int x, int y) {
    int dx = ((x >> pc.bs) & 1) ^ (pc.cfa & 1), dy = ((y >> pc.bs) & 1) ^ (pc.cfa >> 1);
    return (dx + dy) == 0 ? 0 : ((dx + dy) == 2 ? 2 : 1);
}
vec2 stHom(int base, vec2 p) {
    float d = fi[base + 6] * p.x + fi[base + 7] * p.y + fi[base + 8];
    return vec2(fi[base + 0] * p.x + fi[base + 1] * p.y + fi[base + 2], fi[base + 3] * p.x + fi[base + 4] * p.y + fi[base + 5]) / d;
}
// the 1D footprint of one RAW pixel at HR distance dd (centre to centre): s = 2: two half-pixel sub-samples, each bilinear;
// s = 1: bilinear
float stKern(float dd) {
    float a = abs(dd);
    if (pc.s == 1) return max(0.0, 1.0 - a);
    return 0.5 * (max(0.0, 1.0 - abs(a - 0.5)) + max(0.0, 1.0 - (a + 0.5)));
}
float stReach() { return pc.s == 1 ? 1.0 : 1.5; }
)ST";

// the field (per frame, per tile: a translation in base LR px on top of the homography), bilinear between tile centres
static const char* kStField=R"ST(
vec2 stFieldAt(int k, vec2 pb) {
    vec2 t = pb / float(pc.tile) - 0.5;
    vec2 f = floor(t);
    vec2 a = t - f;
    int x0 = clamp(int(f.x), 0, pc.ntx - 1), y0 = clamp(int(f.y), 0, pc.nty - 1);
    int x1 = clamp(int(f.x) + 1, 0, pc.ntx - 1), y1 = clamp(int(f.y) + 1, 0, pc.nty - 1);
    int b = k * pc.ntx * pc.nty;
    vec2 v00 = fld[b + y0 * pc.ntx + x0], v10 = fld[b + y0 * pc.ntx + x1];
    vec2 v01 = fld[b + y1 * pc.ntx + x0], v11 = fld[b + y1 * pc.ntx + x1];
    vec2 ac = clamp(a, 0.0, 1.0);
    return mix(mix(v00, v10, ac.x), mix(v01, v11, ac.x), ac.y);
}
// strip HR position of RAW pixel (x, y) of frame k
vec2 stHrPos(int k, int x, int y) {
    vec2 pb = stHom(k * 32 + 9, vec2(float(x) + 0.5, float(y) + 0.5));
    pb += stFieldAt(k, pb);
    return float(pc.s) * pb - vec2(0.0, float(pc.s * pc.r0));
}
// the RAW sample, normalised and divided by the frame's exposure
float stRaw(int k, int x, int y) {
    int row = y - int(fi[k * 32 + 21]);
    if (row < 0 || row >= pc.rows || x < 0 || x >= pc.W || y < 0 || y >= pc.H) return -1.0;
    int idx = (k * pc.rows + row) * pc.W + x;
    uint wd = raw[idx >> 1];
    float dn = float((idx & 1) == 1 ? (wd >> 16) : (wd & 65535u));
    int g = 32 * pc.K;
    int site = ((y & 1) << 1) | (x & 1);
    float bl = fi[g + site];
    return (dn - bl) / max(fi[g + 4] - bl, 1.0) * fi[g + 5 + site] / fi[k * 32 + 20]; // the site's gain (green balance)
}
// forward model of one RAW pixel at HR position q on channel c of z; false when its footprint leaves the strip
bool stForward(vec2 q, int c, out float v) {
    float reach = stReach();
    int px0 = int(floor(q.x - 0.5 - reach)) + 1, py0 = int(floor(q.y - 0.5 - reach)) + 1;
    float acc = 0.0;
    v = 0.0;
    for (int jy = 0; jy < 3; ++jy) {
        int py = py0 + jy;
        float wy = stKern(q.y - (float(py) + 0.5));
        if (wy <= 0.0) continue;
        if (py < 0 || py >= pc.RH) return false;
        for (int jx = 0; jx < 3; ++jx) {
            int px = px0 + jx;
            float wx = stKern(q.x - (float(px) + 0.5));
            if (wx <= 0.0) continue;
            if (px < 0 || px >= pc.WH) return false;
            acc += wx * wy * zv[py * pc.WH + px][c];
        }
    }
    v = acc;
    return true;
}
)ST";

// ---- bodies. Each declares its own buffers (binding 0 = parameters on GLES, 1 = frame table).

// z0: per HR pixel, a Gaussian-weighted average of every frame's samples of each colour (3 x 3 RAW pixels around the HR pixel's
// position in the frame; clipped samples included, so a clipped area starts clipped). Writes z and the anchor x.
static const char* kStInit=R"ST(
layout(local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 2) readonly buffer StRaw { uint raw[]; };
layout(std430, binding = 3) writeonly buffer StZ { vec4 zo[]; };
layout(std430, binding = 4) writeonly buffer StX { vec4 xo[]; };
layout(std430, binding = 6) readonly buffer StFld { vec2 fld[]; };
layout(std430, binding = 7) readonly buffer StZv { vec4 zv[]; }; // (unused; keeps stForward compilable)
layout(std430, binding = 5) readonly buffer StCd { vec2 cd[]; };   // mode 1: the colour prior (R - G, B - G) on the block grid
)ST" "%FIELD%" R"ST(
// P627 (mode 1, a colour-block CFA): the base frame's colour differences binned to its blocks (a plain Bayer of W/b x H/b,
// bilinear demosaic, scam-static-merge.h), bilinear at a base position
vec2 stCdAt(vec2 pb) {
    int g = 32 * pc.K;
    int cw = int(fi[g + 9]), c0 = int(fi[g + 10]), cr = int(fi[g + 11]);
    vec2 c = pb / float(1 << pc.bs) - 0.5;
    vec2 f = floor(c);
    vec2 a = clamp(c - f, 0.0, 1.0);
    int x0 = clamp(int(f.x), 0, cw - 1), x1 = clamp(int(f.x) + 1, 0, cw - 1);
    int y0 = clamp(int(f.y) - c0, 0, cr - 1), y1 = clamp(int(f.y) + 1 - c0, 0, cr - 1);
    return mix(mix(cd[y0 * cw + x0], cd[y0 * cw + x1], a.x), mix(cd[y1 * cw + x0], cd[y1 * cw + x1], a.x), a.y);
}
void main() {
    int px = int(gl_GlobalInvocationID.x), py = int(gl_GlobalInvocationID.y) + pc.row0;
    if (px >= pc.WH || py >= pc.RH) return;
    vec2 pb = vec2((float(px) + 0.5) / float(pc.s), (float(py) + 0.5) / float(pc.s) + float(pc.r0));
    vec3 num = vec3(0.0), den = vec3(0.0);
    float sg = 0.6 * float(pc.s);
    for (int k = 0; k < pc.K; ++k) {
        if (fi[k * 32 + 22] < 0.5) continue;
        // P639: the nine samples' HR offsets from this pixel through the inverse homography's Jacobian at u, (A - p g^T) / d
        // (p = the pixel's own base position less the field, where u maps back), the field as at the pixel - a homography and
        // a field look-up per frame, not per sample (the offsets agree to ~1e-4 px, as the gather's)
        int hb = k * 32;
        vec2 pm = pb - stFieldAt(k, pb);
        vec2 u = stHom(hb, pm);
        float di = fi[hb + 15] * u.x + fi[hb + 16] * u.y + fi[hb + 17];
        vec2 jx = (vec2(fi[hb + 9], fi[hb + 12]) - pm * fi[hb + 15]) / di, jy = (vec2(fi[hb + 10], fi[hb + 13]) - pm * fi[hb + 16]) / di;
        int cx0 = int(floor(u.x - 0.5)) - 1, cy0 = int(floor(u.y - 0.5)) - 1;
        for (int ny = 0; ny < 3; ++ny) for (int nx = 0; nx < 3; ++nx) {
            int cx = cx0 + nx, cy = cy0 + ny;
            float yv = stRaw(k, cx, cy);
            if (yv < -0.5) continue;
            vec2 e = vec2(float(cx) + 0.5, float(cy) + 0.5) - u;
            vec2 d = float(pc.s) * (jx * e.x + jy * e.y);
            float wgt = exp(-dot(d, d) / (2.0 * sg * sg));
            int c = stChannel(cx, cy);
            if (c == 0) { num.x += wgt * yv; den.x += wgt; }
            else if (c == 1) { num.y += wgt * yv; den.y += wgt; }
            else { num.z += wgt * yv; den.z += wgt; }
        }
    }
    vec3 z = num / max(den, vec3(1e-6));
    if (pc.mode == 1) {
        // P627: a colour-block CFA leaves HR pixels whose 3 x 3 RAW neighbourhood holds no site of a colour (the middle of a
        // Tetra block): each colour starts from the measured samples with the base's binned colour difference as a weak prior
        // (the weight of one centred sample / 4), G from R / B less their difference where it has no site of its own
        vec2 d = stCdAt(pb);
        float w0 = 0.25, wr = den.x > 1e-6 ? w0 : 0.0, wb = den.z > 1e-6 ? w0 : 0.0;
        float gd = den.y + wr + wb;
        float g = gd > 1e-6 ? (num.y + wr * (z.x - d.x) + wb * (z.z - d.y)) / gd : 0.0;
        z = vec3((num.x + w0 * (g + d.x)) / (den.x + w0), g, (num.z + w0 * (g + d.y)) / (den.z + w0));
    } else {
        float gfill = den.y > 1e-6 ? z.y : (den.x > 1e-6 ? z.x : z.z);
        if (den.x <= 1e-6) z.x = gfill;
        if (den.y <= 1e-6) z.y = gfill;
        if (den.z <= 1e-6) z.z = gfill;
    }
    vec4 o = vec4(z, 0.0);
    zo[py * pc.WH + px] = o;
    xo[py * pc.WH + px] = o;
}
)ST";

// per RAW sample of every frame: the residual against z, its base weight (sig2 / var of our noise model at the prediction; 0 where
// the footprint leaves the strip or the sample is clipped). mode 0: r -> sr, weight -> sw; mode 1: weighted residual w r -> sr
// (the z-steps; sw holds the final weight); mode 2 (P639): as mode 0 and the pairs (r, base weight) for a gather that follows on
// the base weights (no robust pass in between: the pass of mode 1 would compute the same residual again)
static const char* kStResid=R"ST(
layout(local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 2) readonly buffer StRaw { uint raw[]; };
layout(std430, binding = 3) readonly buffer StZv { vec4 zv[]; };
layout(std430, binding = 4) buffer StR { float sr[]; };
layout(std430, binding = 5) buffer StWb { float sw[]; };
layout(std430, binding = 6) readonly buffer StFld { vec2 fld[]; };
layout(std430, binding = 7) writeonly buffer StRW { vec2 srw[]; }; // mode 1: (residual, weight) pairs for the gather
)ST" "%FIELD%" R"ST(
void main() {
    int x = int(gl_GlobalInvocationID.x), wr = int(gl_GlobalInvocationID.y) + pc.row0, k = int(gl_GlobalInvocationID.z) + pc.frame0;
    if (x >= pc.W || wr >= pc.rows || k >= pc.K) return;
    int y = wr + int(fi[k * 32 + 21]);
    int o = (k * pc.rows + wr) * pc.W + x;
    float yv = stRaw(k, x, y);
    float pred;
    bool ok = fi[k * 32 + 22] > 0.5 && y >= 0 && y < pc.H && yv > -0.5 && stForward(stHrPos(k, x, y), stChannel(x, y), pred);
    if (pc.mode != 1) {
        float var = fi[k * 32 + 18] * max(pred, 0.0) + fi[k * 32 + 19];
        float ol = fi[k * 32 + 24];
        if (ol > 0.0) { // P627 DCG: the LCG zone's offset above the knee (own exposure), smoothstep over 0.9 .. 1.1 x the knee
            float kn = fi[k * 32 + 23];
            float t = clamp((max(pred, 0.0) * fi[k * 32 + 20] - 0.9 * kn) / (0.2 * kn), 0.0, 1.0);
            var += (ol - fi[k * 32 + 19]) * t * t * (3.0 - 2.0 * t);
        }
        bool use = ok && yv < pc.clipLvl;
        float bw = use ? min(pc.sig2 / max(var, 1e-10), 16.0) : 0.0;
        sr[o] = use ? pred - yv : 0.0;
        sw[o] = bw;
        if (pc.mode == 2) srw[o] = use ? vec2(pred - yv, bw) : vec2(0.0);
    } else {
        srw[o] = ok ? vec2(pred - yv, sw[o]) : vec2(0.0); // one load per sample in the gather (it weighs and sums the weights)
    }
}
)ST";

// robust weight of a frame's sample (frames k >= 1): the local MEAN of its residual over 5 x 5 RAW pixels against the noise of
// that mean (sum var / n^2) plus a misregistration term (robTau x the sample's same-colour gradient)^2: zero-mean misfit (aliasing,
// interpolation) averages out, a moving object moves the mean. w = 1 / (1 + max(e - k^2, 0) / k^2). -> st (temp)
static const char* kStRobust=R"ST(
layout(local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 2) readonly buffer StRaw { uint raw[]; };
layout(std430, binding = 3) readonly buffer StR { float sr[]; };
layout(std430, binding = 4) readonly buffer StWb { float sw[]; };
layout(std430, binding = 5) writeonly buffer StT { float st[]; };
)ST" R"ST(
float stRaw2(int k, int x, int yy) {
    int y = clamp(yy, 0, pc.H - 1);
    int row = y - int(fi[k * 32 + 21]);
    row = clamp(row, 0, pc.rows - 1);
    int xc = clamp(x, 0, pc.W - 1);
    int idx = (k * pc.rows + row) * pc.W + xc;
    uint wd = raw[idx >> 1];
    float dn = float((idx & 1) == 1 ? (wd >> 16) : (wd & 65535u));
    int g = 32 * pc.K;
    int site = ((y & 1) << 1) | (xc & 1);
    return (dn - fi[g]) / max(fi[g + 4] - fi[g], 1.0) * fi[g + 5 + site] / fi[k * 32 + 20];
}
void main() {
    int x = int(gl_GlobalInvocationID.x), wr = int(gl_GlobalInvocationID.y) + pc.row0, k = int(gl_GlobalInvocationID.z) + pc.frame0;
    if (x >= pc.W || wr >= pc.rows || k >= pc.K) return;
    int o = (k * pc.rows + wr) * pc.W + x;
    if (k == 0 || pc.robK <= 0.0) { st[o] = 1.0; return; }
    float n = 0.0, srs = 0.0, svs = 0.0;
    for (int dy = -2; dy <= 2; ++dy) {
        int rr = clamp(wr + dy, 0, pc.rows - 1);
        for (int dx = -2; dx <= 2; ++dx) {
            int t = (k * pc.rows + rr) * pc.W + clamp(x + dx, 0, pc.W - 1);
            float wb = sw[t];
            if (wb <= 0.0) continue;
            n += 1.0; srs += sr[t]; svs += pc.sig2 / wb;
        }
    }
    float w = 1.0;
    if (n > 0.0) {
        int y = wr + int(fi[k * 32 + 21]);
        float gx, gy;
        if (pc.bs == 0) {
            gx = (stRaw2(k, x + 2, y) - stRaw2(k, x - 2, y)) * 0.25;
            gy = (stRaw2(k, x, y + 2) - stRaw2(k, x, y - 2)) * 0.25;
        } else { // P627: the same colour's nearest sites are the block's own (1 px; the period, 4 / 8 px, misses fine texture)
            int bm = (1 << pc.bs) - 1;
            int xa = max(x - 1, x & ~bm), xb = min(x + 1, (x & ~bm) + bm), ya = max(y - 1, y & ~bm), yb = min(y + 1, (y & ~bm) + bm);
            gx = (stRaw2(k, xb, y) - stRaw2(k, xa, y)) / float(xb - xa);
            gy = (stRaw2(k, x, yb) - stRaw2(k, x, ya)) / float(yb - ya);
        }
        float m = srs / n;
        float vm = svs / (n * n) + pc.robTau * pc.robTau * (gx * gx + gy * gy) + 1e-12;
        float e = m * m / vm, k2 = pc.robK * pc.robK;
        w = 1.0 / (1.0 + max(e - k2, 0.0) / k2);
    }
    st[o] = w;
}
)ST";

// the final weight: base weight x the minimum robust weight over (2 spread + 1)^2 (a frame that disagrees anywhere near is out
// across it, Wronski et al. 2019); counts the frame's valid and rejected (< 0.5) samples of the strip's own rows
static const char* kStMin=R"ST(
layout(local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 2) readonly buffer StT { float st[]; };
layout(std430, binding = 3) readonly buffer StWb { float sw0[]; };
layout(std430, binding = 4) writeonly buffer StW { float sw[]; };
layout(std430, binding = 5) buffer StCount { uint cnt[]; };
layout(std430, binding = 6) readonly buffer StR { float sr[]; };
layout(std430, binding = 7) writeonly buffer StRW { vec2 srw[]; }; // P639 (mode >= 2): (residual, final weight) for the gather
)ST" R"ST(
void main() {
    int x = int(gl_GlobalInvocationID.x), wr = int(gl_GlobalInvocationID.y) + pc.row0, k = int(gl_GlobalInvocationID.z) + pc.frame0;
    if (x >= pc.W || wr >= pc.rows || k >= pc.K) return;
    int o = (k * pc.rows + wr) * pc.W + x;
    float w = 1.0;
    if (k > 0 && pc.robK > 0.0) {
        for (int dy = -pc.spread; dy <= pc.spread; ++dy) {
            int rr = clamp(wr + dy, 0, pc.rows - 1);
            for (int dx = -pc.spread; dx <= pc.spread; ++dx) w = min(w, st[(k * pc.rows + rr) * pc.W + clamp(x + dx, 0, pc.W - 1)]);
        }
    }
    float b = sw0[o];
    sw[o] = b * w;
    // P639: the residual this weight was made from, paired with it - the gather that follows needs no residual pass of its own
    if (pc.mode >= 2) srw[o] = b > 0.0 ? vec2(sr[o], b * w) : vec2(0.0);
    // the last iteration (mode 1), the strip's own rows: valid / rejected samples, the normalised data residual w r^2 / sig2
    // (1 = at the noise; x 256 fixed point, each sample capped at 4)
    int y = wr + int(fi[k * 32 + 21]);
    // (P639: on one sample in 16, a hash of its place - the shares and the mean are those of all; the atomics were a tenth of
    // the pass's time)
    if ((pc.mode & 1) == 1 && b > 0.0 && y >= pc.r0 && float(y) < float(pc.r0) + float(pc.RH) / float(pc.s)
        && ((uint(x) * 2654435761u + uint(y) * 2246822519u) >> 28) == 0u) {
        atomicAdd(cnt[2 * k], 1u);
        if (k > 0 && w < 0.5) atomicAdd(cnt[2 * k + 1], 1u);
        float e = min(b * w * sr[o] * sr[o] / pc.sig2, 4.0);
        atomicAdd(cnt[2 * pc.K + k], uint(e * 256.0 + 0.5));
    }
}
)ST";

// the HR gather and z-step: for each frame, the 2 x 2 RAW pixels whose footprint reaches this HR pixel, weighed by the forward
// weights - the gradient U^T W (U z - y) and, in the same loop, the Jacobi preconditioner D = U^T W 1 per colour. Then
// z' = z - c (D + mu + the regularisers' diagonal)^-1 [U^T W (U z - y) + mu (z - x) + colour Tikhonov + luma Huber-TV]
static const char* kStGather=R"ST(
layout(local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 2) readonly buffer StZ { vec4 z[]; };
layout(std430, binding = 3) writeonly buffer StOut { vec4 zo[]; };
layout(std430, binding = 4) readonly buffer StX { vec4 xa[]; };
layout(std430, binding = 5) readonly buffer StRW { vec2 rw[]; };
layout(std430, binding = 6) readonly buffer StFld { vec2 fld[]; };
)ST" R"ST(
vec2 stFieldAt(int k, vec2 pb) {
    vec2 t = pb / float(pc.tile) - 0.5;
    vec2 f = floor(t);
    vec2 a = clamp(t - f, 0.0, 1.0);
    int x0 = clamp(int(f.x), 0, pc.ntx - 1), y0 = clamp(int(f.y), 0, pc.nty - 1);
    int x1 = clamp(int(f.x) + 1, 0, pc.ntx - 1), y1 = clamp(int(f.y) + 1, 0, pc.nty - 1);
    int b = k * pc.ntx * pc.nty;
    return mix(mix(fld[b + y0 * pc.ntx + x0], fld[b + y0 * pc.ntx + x1], a.x), mix(fld[b + y1 * pc.ntx + x0], fld[b + y1 * pc.ntx + x1], a.x), a.y);
}
float stLuma(vec4 v) { return 0.25 * pc.wbR * v.x + 0.5 * v.y + 0.25 * pc.wbB * v.z; }
float stHub(float d) { return d / max(1.0, abs(d) / pc.delta); }
void main() {
    int px = int(gl_GlobalInvocationID.x), py = int(gl_GlobalInvocationID.y) + pc.row0;
    if (px >= pc.WH || py >= pc.RH) return;
    vec2 pc0 = vec2(float(px) + 0.5, float(py) + 0.5);
    vec2 pb = vec2(pc0.x / float(pc.s), pc0.y / float(pc.s) + float(pc.r0));
    float reachLr = (pc.s == 1 ? 1.0 : 1.5) / float(pc.s);
    vec3 g = vec3(0.0), dg = vec3(0.0);
    for (int k = 0; k < pc.K; ++k) {
        if (fi[k * 32 + 22] < 0.5) continue;
        // the field once per frame and HR pixel: across the 2 x 2 RAW pixels it changes by ~1e-4 px (tiles of 64 px), so the
        // gather's weights equal the forward pass's to that, a transpose within float rounding of the motion
        vec2 fd = stFieldAt(k, pb);
        vec2 u = stHom(k * 32, pb - fd);
        int cx0 = int(ceil(u.x - 0.5 - reachLr)), cy0 = int(ceil(u.y - 0.5 - reachLr));
        int row0 = int(fi[k * 32 + 21]);
        // (P639: the inverse homography read once per frame - stHom(k * 32 + 9, .) below, its nine values local)
        int hi = k * 32 + 9;
        float i0 = fi[hi], i1 = fi[hi + 1], i2 = fi[hi + 2], i3 = fi[hi + 3], i4 = fi[hi + 4], i5 = fi[hi + 5], i6 = fi[hi + 6], i7 = fi[hi + 7], i8 = fi[hi + 8];
        for (int ny = 0; ny < 2; ++ny) {
            int cy = cy0 + ny, wr = cy - row0;
            if (wr < 0 || wr >= pc.rows || cy < 0 || cy >= pc.H) continue;
            for (int nx = 0; nx < 2; ++nx) {
                int cx = cx0 + nx;
                if (cx < 0 || cx >= pc.W) continue;
                vec2 rv = rw[(k * pc.rows + wr) * pc.W + cx];
                if (rv.y <= 0.0) continue;
                vec2 cc = vec2(float(cx) + 0.5, float(cy) + 0.5);
                vec2 q0 = vec2(i0 * cc.x + i1 * cc.y + i2, i3 * cc.x + i4 * cc.y + i5) / (i6 * cc.x + i7 * cc.y + i8) + fd;
                vec2 q = float(pc.s) * q0 - vec2(0.0, float(pc.s * pc.r0));
                float wgt = stKern(q.x - pc0.x) * stKern(q.y - pc0.y) * rv.y;
                if (wgt <= 0.0) continue;
                int c = stChannel(cx, cy);
                float e = wgt * rv.x;
                if (c == 0) { g.x += e; dg.x += wgt; } else if (c == 1) { g.y += e; dg.y += wgt; } else { g.z += e; dg.z += wgt; }
            }
        }
    }
    int o = py * pc.WH + px;
    vec4 zc = z[o];
    vec4 zl = z[py * pc.WH + max(px - 1, 0)], zr = z[py * pc.WH + min(px + 1, pc.WH - 1)];
    vec4 zu = z[max(py - 1, 0) * pc.WH + px], zd = z[min(py + 1, pc.RH - 1) * pc.WH + px];
    vec3 grad = g + pc.mu * (zc.xyz - xa[o].xyz);
    // P627d: the priors' floors (globals 12 / 13 of the frame table) where the estimate is flat - its luma gradient^2 against
    // flatK (global 14) x the noise variance - fading on edges and texture (lc / ll there, the noise's own)
    int gt = 32 * pc.K;
    float gyx = stLuma(zr) - stLuma(zl), gyy = stLuma(zd) - stLuma(zu);
    float g2 = gyx * gyx + gyy * gyy;
    float flt = fi[gt + 14] > 0.0 ? 1.0 / (1.0 + g2 / (fi[gt + 14] * pc.sig2)) : 1.0;
    // P627e: and on strong edges (global 15: edgeK, the gradient^2 against edgeK x the noise variance; 0 = off): the floors keep
    // a steep edge's colour and luma from stepping with the blocks - only the texture between gets the own prior
    if (fi[gt + 15] > 0.0) flt = max(flt, g2 / (g2 + fi[gt + 15] * pc.sig2));
    float lcP = pc.lc + (fi[gt + 12] - pc.lc) * flt, llP = pc.ll + (fi[gt + 13] - pc.ll) * flt;
    // white-balanced colour differences gR R - G, gB B - G: Tikhonov (Laplacian)
    vec3 gw = vec3(pc.wbR, 1.0, pc.wbB);
    vec3 dc = vec3(gw.x * zc.x - zc.y, 0.0, gw.z * zc.z - zc.y);
    vec3 lap = vec3(gw.x * zl.x - zl.y, 0.0, gw.z * zl.z - zl.y) + vec3(gw.x * zr.x - zr.y, 0.0, gw.z * zr.z - zr.y)
             + vec3(gw.x * zu.x - zu.y, 0.0, gw.z * zu.z - zu.y) + vec3(gw.x * zd.x - zd.y, 0.0, gw.z * zd.z - zd.y) - 4.0 * dc;
    grad.x -= lcP * gw.x * lap.x;
    grad.z -= lcP * gw.z * lap.z;
    grad.y += lcP * (lap.x + lap.z);
    // luma Huber-TV on the white-balanced luma Y = (gR R + 2 G + gB B) / 4: - div(w grad Y), w = 1 / max(1, |dY| / delta)
    float yc = stLuma(zc);
    float div = stHub(stLuma(zr) - yc) - stHub(yc - stLuma(zl)) + stHub(stLuma(zd) - yc) - stHub(yc - stLuma(zu));
    vec3 q = vec3(0.25, 0.5, 0.25) * gw;
    grad -= llP * q * div;
    // the Jacobi diagonal bounds every term's absolute row sum (Schur): data D, mu, 8 lc g (g + 1) / 8 lc (gR + gB + 2), 8 ll q
    vec3 diag = dg + vec3(pc.mu) + 8.0 * lcP * vec3(gw.x * (gw.x + 1.0), gw.x + gw.z + 2.0, gw.z * (gw.z + 1.0)) + 8.0 * llP * q;
    zo[o] = vec4(zc.xyz - pc.stepc * grad / diag, 0.0);
}
)ST";

// the field step (the paper's p-step, Gauss-Newton): one workgroup per (tile, frame >= 1). Its threads take the frame's RAW
// samples whose base position lies in the tile, J = d(U z - y) / d(translation) by central differences of the forward model
// (+-1 HR px: a slightly smoothed derivative), and reduce J^T W J, J^T W e. The new translation minimises
// |d - d_GN|_A^2 + lambda |d - mean of the 4 neighbours|^2 (lambda = lam x trace(A) / 2 + lamFloor), |d| <= maxShift.
// Tiles off the strip copy their value. -> fldo
static const char* kStFieldStep=R"ST(
layout(local_size_x = 256) in;
layout(std430, binding = 2) readonly buffer StRaw { uint raw[]; };
layout(std430, binding = 3) readonly buffer StZv { vec4 zv[]; };
layout(std430, binding = 4) readonly buffer StWb { float sw[]; };
layout(std430, binding = 6) readonly buffer StFld { vec2 fld[]; };
layout(std430, binding = 7) writeonly buffer StFldO { vec2 fldo[]; };
)ST" "%FIELD%" R"ST(
#ifdef ST_SUBGROUP
shared float red[5][64];
#else
shared float red[5][256];
#endif
void main() {
    int lid = int(gl_LocalInvocationID.x);
    int tix = int(gl_WorkGroupID.x), tiy = int(gl_WorkGroupID.y) + pc.row0, k = int(gl_WorkGroupID.z) + 1 + pc.frame0;
    int ti = (k * pc.nty + tiy) * pc.ntx + tix;
    // tile rect (base LR) and whether the strip's HR rows hold it
    float bx0 = float(tix * pc.tile), by0 = float(tiy * pc.tile);
    float sy0 = float(pc.r0), sy1 = float(pc.r0) + float(pc.RH) / float(pc.s);
    bool onStrip = fi[k * 32 + 22] > 0.5 && by0 + float(pc.tile) > sy0 + 2.0 && by0 < sy1 - 2.0;
    float a11 = 0.0, a12 = 0.0, a22 = 0.0, b1 = 0.0, b2 = 0.0;
    if (onStrip) {
        // the frame's pixels: the bounding box of the tile's corners in the frame (+1)
        vec2 c0 = stHom(k * 32, vec2(bx0, max(by0, sy0))), c1 = stHom(k * 32, vec2(bx0 + float(pc.tile), max(by0, sy0)));
        vec2 c2 = stHom(k * 32, vec2(bx0, min(by0 + float(pc.tile), sy1))), c3 = stHom(k * 32, vec2(bx0 + float(pc.tile), min(by0 + float(pc.tile), sy1)));
        int fx0 = int(floor(min(min(c0.x, c1.x), min(c2.x, c3.x)))) - 1, fx1 = int(ceil(max(max(c0.x, c1.x), max(c2.x, c3.x)))) + 1;
        int fy0 = int(floor(min(min(c0.y, c1.y), min(c2.y, c3.y)))) - 1, fy1 = int(ceil(max(max(c0.y, c1.y), max(c2.y, c3.y)))) + 1;
        int row0 = int(fi[k * 32 + 21]);
        fy0 = max(fy0, row0);
        fy1 = min(fy1, row0 + pc.rows);
        fx0 = max(fx0, 0);
        fx1 = min(fx1, pc.W);
        int nw = max(fx1 - fx0, 0), npx = nw * max(fy1 - fy0, 0);
        float sf = float(pc.s);
        for (int i = lid; i < npx; i += 256) {
            int x = fx0 + i % nw, y = fy0 + i / nw;
            // P639 (mode 1): every second colour period of the frame, as a checker - half the samples (two unknowns a tile
            // from ~2000 samples a frame), all the colours
            if (pc.mode == 1 && (((x >> (pc.bs + 1)) + (y >> (pc.bs + 1))) & 1) != 0) continue;
            int o = (k * pc.rows + (y - row0)) * pc.W + x;
            float wgt = sw[o];
            if (wgt <= 0.0) continue;
            vec2 pb = stHom(k * 32 + 9, vec2(float(x) + 0.5, float(y) + 0.5));
            if (pb.x < bx0 || pb.x >= bx0 + float(pc.tile) || pb.y < by0 || pb.y >= by0 + float(pc.tile)) continue;
            pb += stFieldAt(k, pb);
            vec2 q = sf * pb - vec2(0.0, float(pc.s * pc.r0));
            int c = stChannel(x, y);
            float f0, fxp, fxm, fyp, fym;
            if (!stForward(q, c, f0) || !stForward(q + vec2(1.0, 0.0), c, fxp) || !stForward(q - vec2(1.0, 0.0), c, fxm)
                || !stForward(q + vec2(0.0, 1.0), c, fyp) || !stForward(q - vec2(0.0, 1.0), c, fym)) continue;
            float e = f0 - stRaw(k, x, y);
            float jx = 0.5 * sf * (fxp - fxm), jy = 0.5 * sf * (fyp - fym);
            a11 += wgt * jx * jx; a12 += wgt * jx * jy; a22 += wgt * jy * jy;
            b1 += wgt * jx * e; b2 += wgt * jy * e;
        }
    }
#ifdef ST_SUBGROUP
    a11 = subgroupAdd(a11); a12 = subgroupAdd(a12); a22 = subgroupAdd(a22); b1 = subgroupAdd(b1); b2 = subgroupAdd(b2);
    int sg = int(gl_SubgroupID);
    if (subgroupElect()) { red[0][sg] = a11; red[1][sg] = a12; red[2][sg] = a22; red[3][sg] = b1; red[4][sg] = b2; }
    barrier();
    if (lid == 0) {
        for (int i = 1; i < int(gl_NumSubgroups); ++i) {
            red[0][0] += red[0][i]; red[1][0] += red[1][i]; red[2][0] += red[2][i]; red[3][0] += red[3][i]; red[4][0] += red[4][i];
        }
    }
#else
    red[0][lid] = a11; red[1][lid] = a12; red[2][lid] = a22; red[3][lid] = b1; red[4][lid] = b2;
    barrier();
    for (int st = 128; st > 0; st >>= 1) {
        if (lid < st) {
            red[0][lid] += red[0][lid + st]; red[1][lid] += red[1][lid + st]; red[2][lid] += red[2][lid + st];
            red[3][lid] += red[3][lid + st]; red[4][lid] += red[4][lid + st];
        }
        barrier();
    }
#endif
    if (lid != 0) return;
    vec2 d0 = fld[ti];
    if (!onStrip) { fldo[ti] = d0; return; }
    float A11 = red[0][0], A12 = red[1][0], A22 = red[2][0], B1 = red[3][0], B2 = red[4][0];
    int bk = k * pc.ntx * pc.nty;
    vec2 m = vec2(0.0);
    float nm = 0.0;
    if (tix > 0) { m += fld[bk + tiy * pc.ntx + tix - 1]; nm += 1.0; }
    if (tix + 1 < pc.ntx) { m += fld[bk + tiy * pc.ntx + tix + 1]; nm += 1.0; }
    if (tiy > 0) { m += fld[bk + (tiy - 1) * pc.ntx + tix]; nm += 1.0; }
    if (tiy + 1 < pc.nty) { m += fld[bk + (tiy + 1) * pc.ntx + tix]; nm += 1.0; }
    m = nm > 0.0 ? m / nm : d0;
    float lam = pc.lam * 0.5 * (A11 + A22) + pc.lamFloor;
    // (A + lam I) d = A d0 - b + lam m
    vec2 rhs = vec2(A11 * d0.x + A12 * d0.y - B1, A12 * d0.x + A22 * d0.y - B2) + lam * m;
    float m11 = A11 + lam, m22 = A22 + lam, det = m11 * m22 - A12 * A12;
    vec2 d = abs(det) > 1e-20 ? vec2(m22 * rhs.x - A12 * rhs.y, m11 * rhs.y - A12 * rhs.x) / det : d0;
    float len = length(d);
    if (len > pc.maxShift) d *= pc.maxShift / len;
    fldo[ti] = d;
}
)ST";

// the result rows of the strip: HR (mode 2: z as is) or the 1x photo (mode 1: the 2 x 2 box of z; P627f mode 3: z through a
// Lanczos-2 low-pass at the 1x grid's Nyquist, 4 s taps per axis - the detail the solve recovered up to the 1x photo's limit,
// where the box is a second pixel aperture: x0.71 at its Nyquist), interleaved RGB from the strip's HR row `rows` (its first
// output row) for outRows rows (the strip's HR rows 0 .. RH - 1 clamped: its margin is 2 s x 16 rows)
static const char* kStOutput=R"ST(
layout(local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 2) readonly buffer StZ { vec4 z[]; };
layout(std430, binding = 3) writeonly buffer StO { float ob[]; };
)ST" R"ST(
void main() {
    int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y) + pc.row0;
    int ow = pc.mode != 2 ? pc.WH / pc.s : pc.WH;
    if (x >= ow || y >= pc.outRows) return;
    vec3 v;
    if (pc.mode == 3) {
        float fs = float(pc.s), cx = (float(x) + 0.5) * fs, cy = float(pc.rows) + (float(y) + 0.5) * fs;
        int ix = int(floor(cx - 2.0 * fs)), iy = int(floor(cy - 2.0 * fs));
        vec3 acc = vec3(0.0);
        float ws = 0.0;
        for (int j = 0; j <= 4 * pc.s; ++j) {
            float dy = (float(iy + j) + 0.5 - cy) / fs;
            if (abs(dy) >= 2.0) continue;
            float wy = abs(dy) < 1e-4 ? 1.0 : 2.0 * sin(3.14159265 * dy) * sin(1.57079633 * dy) / (9.8696044 * dy * dy);
            int ry = clamp(iy + j, 0, pc.RH - 1);
            for (int i = 0; i <= 4 * pc.s; ++i) {
                float dx = (float(ix + i) + 0.5 - cx) / fs;
                if (abs(dx) >= 2.0) continue;
                float w = wy * (abs(dx) < 1e-4 ? 1.0 : 2.0 * sin(3.14159265 * dx) * sin(1.57079633 * dx) / (9.8696044 * dx * dx));
                acc += w * z[ry * pc.WH + clamp(ix + i, 0, pc.WH - 1)].xyz;
                ws += w;
            }
        }
        v = acc / ws;
    } else if (pc.mode == 1) {
        int f = pc.s, hy = pc.rows + y * f, hx = x * f;
        vec3 acc = vec3(0.0);
        for (int j = 0; j < f; ++j) for (int i = 0; i < f; ++i) acc += z[(hy + j) * pc.WH + hx + i].xyz;
        v = acc / float(f * f);
    } else {
        v = z[(pc.rows + y) * pc.WH + x].xyz;
    }
    int o = (y * ow + x) * 3;
    ob[o] = v.x; ob[o + 1] = v.y; ob[o + 2] = v.z;
}
)ST";

// program sources: name, body, needs the field / forward helpers
struct StProgram { const char* name; const char* body; bool field; const char* defines; };
enum StProg { spInit, spResid, spRobust, spMin, spGather, spField, spFieldSg, spOutput, spCount };
inline const StProgram* stPrograms(){
    static const StProgram p[spCount]={{"init",kStInit,true,""},{"resid",kStResid,true,""},{"robust",kStRobust,false,""},
        {"min",kStMin,false,""},{"gather",kStGather,false,""},{"field",kStFieldStep,true,""},
        {"field_sg",kStFieldStep,true,"#define ST_SUBGROUP 1\n"},{"output",kStOutput,false,""}};
    return p;
}
// the full source of a program for a backend (gles: GLSL ES 3.10; else GLSL 450 for glslc)
inline std::string stSource(int prog,bool gles){
    const StProgram& p=stPrograms()[prog];
    std::string body=p.body;
    const std::string mark="%FIELD%";
    const size_t at=body.find(mark);
    if(at!=std::string::npos)body.replace(at,mark.size(),p.field?kStField:"");
    std::string head=gles?kStHeadGles:kStHeadVk;
    // the subgroup extension must precede any declaration: GLSL 450 only
    std::string ext;
    if(std::strstr(p.defines,"ST_SUBGROUP")){ext="#extension GL_KHR_shader_subgroup_basic : require\n#extension GL_KHR_shader_subgroup_arithmetic : require\n";}
    return head+ext+p.defines+kStCommon+body;
}

// FNV-1a of a program's source: scam-static-spirv.h records it per SPIR-V module, the worker compares (a stale module -> GLES)
inline uint64_t stHash(const std::string& t){uint64_t h=1469598103934665603ull;for(unsigned char c:t){h^=c;h*=1099511628211ull;}return h;}

} // namespace staticmerge
} // namespace scam
