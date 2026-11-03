// P422 + CaNetP (the camera's P399 inside the net, 2026-11-01): the colour-fringe net (CaNet: tools/canet/model.py, reference.py) as fragment passes, OpenGL ES 3.1 without extensions:
// any GPU the photo pipeline runs on. Every read is texelFetch clamped to the edge (no filtering: the same on Adreno, Mali,
// PowerVR, Xclipse); weights in an RGBA32F row per layer (reference.pack()). Grids: full -> half (ceil(n / 2)) -> quarter.
//   MODE 0  half:    features of the 2x2 mean of the render's linear RGB (white = 1): log2(Y + 1e-4) / 8, c1, c2, max channel
//   MODE 1  any:     a convolution CIN -> COUT, K x K, dilation dilU, ReLU: one draw per output group ogU (4 channels, RGBA16F),
//                    so any GLES 3 GPU draws it into one texture (no MRT); POOL 1: its input is the 2x2 mean of a twice larger
//                    grid (c2); UPCAT 1: input groups GF..2GF-1 are the bilinear 2x upsampling of their quarter textures (c5)
//   MODE 2  half:    the head (c6): 16 -> 2, 0.5 tanh, the dead zone -> RG = the chroma correction
//   MODE 3  full:    the correction bilinearly upsampled, times the gate (In2.g), added to the pixel's chroma at its own luminance
//                    (never the opposite hue, never more colour), back to camera RGB
//   CaNetP (the camera's P399 inside the net):
//   MODE 4  full:    P399's purple-fringe weight w (scamrgb.glsl purpleFringe) and the physical gate g of the net's correction:
//                    a clearly brighter light within 12 px (REACH 12: 48 taps; REACH 6: the same 32 taps), another hue than that light (its own glow is kept),
//                    a fringe hue (violet .. magenta from HUE_LO, GREEN: the axial green) or near neutral -> RG = (w, g)
//   MODE 5  half:    the 2x2 mean of MODE 4's w -> the 5th input of c1 (R; GBA 0): c1 runs with CIN 8 over (MODE 0, MODE 5)
// P493 (owner, 2026-11-03: «нашу нейросеть для подавления хроматических аберраций ... либо её, либо старый способ»): the app's copy
// of handoff-canet/shader/canet_p.glsl (v3 weights, assets/canet/canetp_v3.bin), run by postpipeline/CaNetP after ScamRgb instead of
// scamrgb's purpleFringe. Changes against the handoff text: the defines' comments sit on their own lines (GLInterface.readProgram
// replaces a #define line that contains " NAME " anywhere, so "// MODE 4: ..." after "#define HUE_LO" took the MODE value) and
// NO_FLIP (1 = the handoff's guard, always on there). Every define's default is the trained v3 setting.
precision highp float;
precision highp sampler2D;
precision highp int;
#define MODE 0
#define CIN 16
#define K 3
#define POOL 0
#define UPCAT 0
#define COUT 16
// UPCAT: the input groups of the first part (f1), the same count upsampled (f4) follow
#define GF 4
// MODE 4: the lowest fringe hue (blue-violet)
#define HUE_LO 205.0
// MODE 4: the axial green fringe too
#define GREEN 1
// MODE 4: a coloured light's own glow (clipped core, saturated green / yellow / red neighbours) is kept
#define SRC_RULES 1
// MODE 3: where the net corrects, P399's own neutralisation (In2.r) if it removes more
#define BOOST 1
// MODE 3: ... and only where the net itself takes at least this share of P399's reduction
#define BOOST_MIN 0.3
// MODE 4: the gate opens from a NEAR_LO x brighter light (full at 2 x NEAR_LO)
#define NEAR_LO 1.5
// MODE 4: ... of at least YB_LO of white (full at YB_LO + 0.4)
#define YB_LO 0.12
// MODE 4: the brighter neighbour looked for at 1, 2, 4, 6 px (6) or also 9, 12 px (12: fringe bands up to ~12 px)
#define REACH 12
// MODE 3: never the opposite hue (P493: 0 lets the net's correction pass through neutral)
#define NO_FLIP 1
uniform highp sampler2D In0;
uniform highp sampler2D In1;
uniform highp sampler2D In2;
uniform highp sampler2D In3;
uniform highp sampler2D In4;
uniform highp sampler2D In5;
uniform highp sampler2D In6;
uniform highp sampler2D In7;
uniform highp sampler2D In8;
uniform highp sampler2D In9;
uniform highp sampler2D In10;
uniform highp sampler2D In11;
uniform highp sampler2D Wt;        // the layer's weights: RGBA32F, 1 row
uniform int dilU;
uniform int ogU;                   // MODE 1: the output group (0 .. COUT / 4 - 1)
uniform mat3 toRenderU;            // camera RGB x neutral -> the render's linear RGB
uniform mat3 fromRenderU;          // its inverse
uniform vec3 neutralU;
uniform float invWhiteU;           // 1 / the base frames' white in the render's units
uniform float strengthU;           // 0..1 (1: the net's correction)
out vec4 Output;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 render(vec3 c) { return toRenderU * (c * neutralU) * invWhiteU; }

vec4 at(highp sampler2D t, ivec2 p) { return texelFetch(t, clamp(p, ivec2(0), textureSize(t, 0) - 1), 0); }

float sstep(float a, float b, float x) { float t = clamp((x - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }

float hueOf(vec3 c) {
    float hi = max(max(c.r, c.g), c.b), lo = min(min(c.r, c.g), c.b), d = max(hi - lo, 1.0e-12);
    if (hi - lo <= 0.0) return 0.0;
    float h = hi == c.r ? (c.g - c.b) / d : (hi == c.g ? 2.0 + (c.b - c.r) / d : 4.0 + (c.r - c.g) / d);
    return mod(60.0 * h, 360.0);
}

float purpleW(vec3 c) { float h = hueOf(c); return sstep(245.0, 265.0, h) * (1.0 - sstep(330.0, 350.0, h)); }

// texture of input group g (12 slots: the GLES 3 minimum is 16 texture units, the weights take one)
vec4 fetchG(int g, ivec2 p) {
    if (g == 0) return texelFetch(In0, p, 0);
    if (g == 1) return texelFetch(In1, p, 0);
    if (g == 2) return texelFetch(In2, p, 0);
    if (g == 3) return texelFetch(In3, p, 0);
    if (g == 4) return texelFetch(In4, p, 0);
    if (g == 5) return texelFetch(In5, p, 0);
    if (g == 6) return texelFetch(In6, p, 0);
    if (g == 7) return texelFetch(In7, p, 0);
    if (g == 8) return texelFetch(In8, p, 0);
    if (g == 9) return texelFetch(In9, p, 0);
    if (g == 10) return texelFetch(In10, p, 0);
    return texelFetch(In11, p, 0);
}

// group g (4 channels) of the layer's input at p; every texture of a layer input has the same size except UPCAT's upsampled part
vec4 inGroup(int g, ivec2 p) {
#if POOL == 1
    // the 2x2 mean of the finer grid: the tap clamped to this (ceil(n / 2)) grid first, then the fine reads clamped (an odd
    // last row / column averages itself)
    ivec2 fs = textureSize(In0, 0);
    ivec2 b = 2 * clamp(p, ivec2(0), (fs + 1) / 2 - 1);
    vec4 s = vec4(0.0);
    for (int j = 0; j < 4; j++) s += fetchG(g, min(b + ivec2(j & 1, j >> 1), fs - 1));
    return 0.25 * s;
#else
#if UPCAT == 1
    if (g >= GF) {
        // bilinear 2x (align corners false) from the quarter grid, clamped
        ivec2 qs = textureSize(In0, 0) / 2 + textureSize(In0, 0) % 2;   // ceil(half / 2)
        vec2 sp = (vec2(p) + 0.5) * 0.5 - 0.5;
        ivec2 i0 = ivec2(floor(sp));
        vec2 f = sp - vec2(i0);
        ivec2 a = clamp(i0, ivec2(0), qs - 1), b = clamp(i0 + 1, ivec2(0), qs - 1);
        return mix(mix(fetchG(g, a), fetchG(g, ivec2(b.x, a.y)), f.x), mix(fetchG(g, ivec2(a.x, b.y)), fetchG(g, b), f.x), f.y);
    }
#endif
    return fetchG(g, clamp(p, ivec2(0), textureSize(In0, 0) - 1));
#endif
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
#if MODE == 0
    ivec2 last = textureSize(In0, 0) - 1;
    vec3 h = vec3(0.0);
    for (int j = 0; j < 4; j++) h += render(texelFetch(In0, clamp(2 * p + ivec2(j & 1, j >> 1), ivec2(0), last), 0).rgb);
    h *= 0.25;
    float y = dot(h, LUMA);
    float s = max(h.r + h.g + h.b, 0.0) + 0.003;   // near black the chroma goes to 0 (noise there, no colour)
    Output = vec4(log2(max(y, 0.0) + 1.0e-4) * 0.125, clamp((h.r - h.g) / s, -1.0, 1.0), clamp((h.b - h.g) / s, -1.0, 1.0),
                  clamp(max(max(h.r, h.g), h.b), 0.0, 1.5));
#elif MODE == 1 || MODE == 2
    const int OGT = (COUT + 3) / 4;   // output groups in the weight row
#if MODE == 1
    int og = ogU;
#else
    int og = 0;
#endif
    const int GROUPS = (CIN + 3) / 4;
    const int R = K / 2;
    vec4 acc = texelFetch(Wt, ivec2(K * K * CIN * OGT + og, 0), 0);
    for (int ky = 0; ky < K; ky++)
        for (int kx = 0; kx < K; kx++) {
            ivec2 q = p + ivec2(kx - R, ky - R) * dilU;
            int tap = ky * K + kx;
            for (int g = 0; g < GROUPS; g++) {
                vec4 v = inGroup(g, q);
                int base = (tap * CIN + 4 * g) * OGT + og;
                acc += texelFetch(Wt, ivec2(base, 0), 0) * v.x + texelFetch(Wt, ivec2(base + OGT, 0), 0) * v.y
                     + texelFetch(Wt, ivec2(base + 2 * OGT, 0), 0) * v.z + texelFetch(Wt, ivec2(base + 3 * OGT, 0), 0) * v.w;
            }
        }
#if MODE == 1
    Output = max(acc, 0.0);
#else
    vec2 d = 0.5 * tanh(acc.xy);
    float t = clamp((max(abs(d.x), abs(d.y)) - 0.003) / 0.007, 0.0, 1.0);
    Output = vec4(d * (t * t * (3.0 - 2.0 * t)), 0.0, 1.0);
#endif
#elif MODE == 3
    vec4 c4 = texelFetch(In0, p, 0);
    // the half-res correction, bilinear (align corners false), clamped
    ivec2 hs = textureSize(In1, 0);
    vec2 sp = (vec2(p) + 0.5) * 0.5 - 0.5;
    ivec2 i0 = ivec2(floor(sp));
    vec2 f = sp - vec2(i0);
    ivec2 a = clamp(i0, ivec2(0), hs - 1), b = clamp(i0 + 1, ivec2(0), hs - 1);
    vec2 d = mix(mix(texelFetch(In1, a, 0).xy, texelFetch(In1, ivec2(b.x, a.y), 0).xy, f.x),
                 mix(texelFetch(In1, ivec2(a.x, b.y), 0).xy, texelFetch(In1, b, 0).xy, f.x), f.y) * strengthU;
    vec3 x = render(c4.rgb);
    float y = dot(x, LUMA);
    d *= texelFetch(In2, p, 0).g;      // the physical gate (MODE 4)
    float tb = clamp((y - 0.002) / 0.008, 0.0, 1.0);
    d *= tb * tb * (3.0 - 2.0 * tb);   // from 0.2 % to 1 % of white in: none in the dark (noise)
    if (d == vec2(0.0)) { Output = c4; return; }   // no correction: the pixel bit for bit
    float s = x.r + x.g + x.b + 1.0e-4;
    vec2 ci = vec2(x.r - x.g, x.b - x.g) / s;
    vec2 cn = ci + d;
#if NO_FLIP == 1
    float dt = dot(cn, ci);            // never the opposite hue: the part against the old chroma goes (a little past neutral)
    if (dt < 0.0) cn -= (dt / (dot(ci, ci) + 1.0e-8) - 0.05) * ci;
#endif
#if BOOST == 1
    float wq = texelFetch(In2, p, 0).r;
    vec2 cb = ci * (1.0 - wq);   // P399's own neutralisation where the net corrects (and takes BOOST_MIN of it), if it removes more
    float mi = sqrt(dot(ci, ci) + 1.0e-12), mn = sqrt(dot(cn, cn) + 1.0e-12);
    if (mi - mn >= BOOST_MIN * mi * wq && dot(cb, cb) < dot(cn, cn)) cn = cb;
#endif
    cn *= min((sqrt(dot(ci, ci) + 1.0e-12) + 0.02) / sqrt(dot(cn, cn) + 1.0e-12), 1.0);   // never more colour than it had (+ 0.02)
    float c1 = cn.x, c2 = cn.y;
    float k = (1.0 - c1 - c2) / 3.0 + LUMA.r * c1 + LUMA.b * c2;
    if (s <= 1.0e-3 || k <= 0.05) { Output = c4; return; }   // ill-posed (signed noise around black): unchanged
    float S = y / k;
    float g = S * (1.0 - c1 - c2) / 3.0;
    vec3 o = vec3(g + c1 * S, g, g + c2 * S);
    Output = vec4(fromRenderU * (o / invWhiteU) / max(neutralU, vec3(1.0e-6)), c4.a);
#elif MODE == 4
    ivec2 last = textureSize(In0, 0) - 1;
    vec3 cp = max(render(texelFetch(In0, p, 0).rgb), vec3(0.0));
    float hi = max(max(cp.r, cp.g), cp.b), lo = min(min(cp.r, cp.g), cp.b);
    float wh = hi > 0.0 ? purpleW(cp) * sstep(0.02, 0.06, (hi - lo) / max(hi, 1.0e-12)) : 0.0;
    float y = max(dot(cp, LUMA), 1.0e-6);
    float yb = 0.0;
    vec3 src = vec3(0.0);
#if REACH >= 12
#define NTAP 48
#else
#define NTAP 32
#endif
    const ivec2 DIRS[8] = ivec2[8](ivec2(1, 0), ivec2(1, 1), ivec2(0, 1), ivec2(-1, 1), ivec2(-1, 0), ivec2(-1, -1), ivec2(0, -1),
                                   ivec2(1, -1));
#if REACH >= 12
    const int RAD[6] = int[6](1, 2, 4, 6, 9, 12);
#else
    const int RAD[4] = int[4](1, 2, 4, 6);
#endif
    for (int ri = 0; ri < NTAP / 8; ri++)
        for (int k = 0; k < 8; k++) {
            vec3 v = max(render(texelFetch(In0, clamp(p + DIRS[k] * RAD[ri], ivec2(0), last), 0).rgb), vec3(0.0));
            float yv = dot(v, LUMA);
            if (yv > yb) { yb = yv; src = v; }
        }
    float sHi = max(max(src.r, src.g), src.b);
    float sChroma = sHi > 0.0 ? (sHi - min(min(src.r, src.g), src.b)) / max(sHi, 1.0e-12) : 0.0;
    float w = wh * sstep(2.5, 5.0, yb / y) * sstep(0.6, 0.95, yb) * (1.0 - purpleW(src) * sstep(0.15, 0.35, sChroma));
    float nearL = sstep(NEAR_LO, 2.0 * NEAR_LO, yb / y) * sstep(YB_LO, YB_LO + 0.4, yb);
    float cs = cp.r + cp.g + cp.b + 1.0e-4, sS = src.r + src.g + src.b + 1.0e-4;
    vec2 pc = vec2(cp.r - cp.g, cp.b - cp.g) / cs, sc = vec2(src.r - src.g, src.b - src.g) / sS;
    float cosang = dot(pc, sc) / (sqrt(dot(pc, pc) + 1.0e-8) * sqrt(dot(sc, sc) + 1.0e-8));
    float own = sstep(0.15, 0.35, sChroma) * sstep(0.7, 0.95, cosang);   // the light's own coloured glow
#if SRC_RULES == 1
    float col = sstep(0.15, 0.35, sChroma);
    own = max(own, col * sstep(0.9, 0.98, sHi) * sstep(0.2, 0.5, cosang));   // a clipped coloured light: a looser hue match
    float sh = hueOf(src);
    own = max(own, col * (1.0 - sstep(180.0, 200.0, sh) * (1.0 - sstep(345.0, 359.0, sh))));   // next to green / yellow / red
    float best = 0.0;
    vec3 bsrc = vec3(0.0);
    // the most saturated bright neighbour: a clipped light's colour is in its glow. P493: the taps fetched again (the handoff kept
    // all 48 in local arrays, vec3 tv[48] / float ty[48]: dynamically indexed, they leave the registers on Adreno - the 12 MP
    // pass hung the OPPO's GPU); the same values in the same order
    for (int ri = 0; ri < NTAP / 8; ri++)
        for (int k = 0; k < 8; k++) {
            vec3 tv = max(render(texelFetch(In0, clamp(p + DIRS[k] * RAD[ri], ivec2(0), last), 0).rgb), vec3(0.0));
            float ty = dot(tv, LUMA);
            float vh = max(max(tv.r, tv.g), tv.b);
            float vc = vh > 0.0 ? (vh - min(min(tv.r, tv.g), tv.b)) / max(vh, 1.0e-12) : 0.0;
            if (ty >= 0.3 * yb && ty > dot(cp, LUMA) && vc > best) { best = vc; bsrc = tv; }
        }
    float bh = hueOf(bsrc);
    own = max(own, sstep(0.25, 0.45, best) * (1.0 - sstep(180.0, 200.0, bh) * (1.0 - sstep(345.0, 359.0, bh))));
#endif
    float h = hueOf(cp), sat = (hi - lo) / max(hi, 1.0e-12);
    float fr = sstep(HUE_LO - 15.0, HUE_LO, h) * (1.0 - sstep(345.0, 359.0, h));
#if GREEN == 1
    fr = max(fr, sstep(75.0, 90.0, h) * (1.0 - sstep(150.0, 165.0, h)));
#endif
    float g = nearL * (1.0 - own) * max(fr, 1.0 - sstep(0.03, 0.08, sat));
    Output = vec4(w, g, 0.0, 1.0);
#else   // MODE 5
    ivec2 last = textureSize(In0, 0) - 1;
    float w = 0.0;
    for (int j = 0; j < 4; j++) w += texelFetch(In0, clamp(2 * p + ivec2(j & 1, j >> 1), ivec2(0), last), 0).r;
    Output = vec4(0.25 * w, 0.0, 0.0, 0.0);
#endif
}
