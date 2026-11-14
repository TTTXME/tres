precision highp float; precision highp sampler2D;
// WHY luma sharpening (WhyLumaSharpen), P214e R1 (owner, 2026-10-18; PLAN "Research 2026-10-17"): on a mosaic shot (a Quad /
// Tetra stream merged on the mosaic, Parameters.mosaicBlock > 1) the sharpening reads the 3x3-smoothed guide B(Ya) and only
// its detail S(B(Ya)) - B(Ya) is added to Ya. The site lattice of the mosaic merge reaches the sharpening through B only at
// cos^2(pi fx) cos^2(pi fy) of its amplitude - the diagonal 4 px one (0.25, +-0.25 cycles / px) at a quarter, the 2 px one
// (0.5 cycles / px) not at all - and otherwise passes through at its own level, instead of being lifted x2.5-2.9 (diagonal)
// by the USM, the bilateral high boost and the Richardson-Lucy stages. Measured on the owner's 50 MP burst: diagonal lattice
// 0.084 / 0.093 % -> 0.045 / 0.049 % of the level (0.032 / 0.036 % unsharpened). Image detail below ~0.2 cycles / px keeps
// most of its sharpening (B passes 0.65 of it at 0.2, 0.9 at 0.1).
// P337 (owner, 2026-10-25: a mesh on the sea of a 3.7x telephoto shot): a plain Bayer shot reads Ya without its 2 px lattice
// (mode 3): the merge leaves the checker / row / column alternation at 10 % of the high band (sea, vivo X200 Ultra tele), the
// USM, bilateral and Richardson-Lucy stages lifted it to 16 % in the JPEG (row alternation 1.8 x WhyCam's). Mode 3 takes out
// exactly the 2 px alternations of the 5 x 5 sites around (weights (1 2 2 2 1) / 8 per axis, as scamdn/lgrain): a constant or
// a linear Ya gives 0 in them, every other detail is sharpened as before, and the lattice comes back at its own level (mode 2).
//  modeU 0: Output = B(InputBuffer), B = [1 2 1]^T [1 2 1] / 16, edge pixels repeated     -> R16F: the sharpening's source
//  modeU 3: Output = InputBuffer - its 2 px alternations (P337, plain Bayer)             -> R16F: the sharpening's source
//  modeU 1: Output = InputBuffer - Smoothed = Ya - B, B as stored by mode 0              -> R16F: the residual D (Ya goes)
//  modeU 2: Output = max(Sharpened + Residual, 0) = Ya + S(B) - B                        -> R16F: WhyCombine's WhyLumaS
// Ya + S - B is never formed from a B computed again (a driver may round the fp16 store differently from the shader): D is
// the difference of two fp16 planes, exact in fp16 wherever B lies within a factor 2 of Ya (all but the strongest edges) and
// is not below the fp16 normal range, so where the chain leaves B alone (S = B) the result is Ya bit for bit, else within an
// ulp of it.
// P364: GATHER 1 - mode 3 reads its 5 x 5 sites as nine 2 x 2 textureGather blocks instead of 25 texelFetch, the same taps
// added in the same order (row by row, left to right) with the same expression; InputBuffer is clamp-to-edge, so a block past
// the border repeats the edge texels as clamp() did (bit-identical). 0 = the loop.
#define GATHER 0
uniform sampler2D InputBuffer;      // modes 0, 1: Ya (.r), output grid
uniform sampler2D Smoothed;         // mode 1: B(Ya) (.r) of mode 0
uniform sampler2D Sharpened;        // mode 2: S(B(Ya)) (.r), the chain's last output
uniform sampler2D Residual;         // mode 2: D = Ya - B (.r) of mode 1
uniform int modeU;
// P441 (owner, 2026-11-01: «ступеньки на гранях»): mode 3's estimate is 0 only for a constant or linear Ya; along every edge or
// thin line it is a +-step / 8 burst of 2 px alternation within 2 px of the edge, which the USM, bilateral and RL stages then
// lifted into a dashed line (mode 2 puts back only the unlifted estimate). The merge's own lattice is ~0.1 % of the level, so the
// estimate is clamped to latCapU x Ya + 0.0002: the lattice still goes, an edge keeps its pixels. <= 0 / unset: unclamped.
uniform float latCapU;
// P687 (owner, 2026-11-14, a 50 MP Quad shot: «сетка» - lever keys on a brushed panel with stepped edges): the merge of a Quad
// stream leaves a straight edge's shoulders a faint grid of its 2 x 2 blocks (mostly their checker, the diagonal 4 px period),
// and a Quad shot's sharpening (P542: the lattice source, whose mode 3 takes out the 2 px alternations only, and the third RL
// stage at x2) lifts it about x2: 2 - 4 px steps along diagonal and slanted edges (the reference app's Quad bursts through the app
// chain: the edge crossings' wobble locked to x, y mod 4 0.019 - 0.033 px unsharpened, 0.039 - 0.051 px sharpened).
// modeU 4 = mode 2 with the sharpening's increment S - Source smoothed along a straight edge: a Gaussian of sigma 2 px on the
// edge's tangent (its taps on whole rows or columns, a cubic across), the orientation from the source's structure tensor over
// 8 px differences (blind to the blocks' period 4). A straight edge's increment is the same all along it, so its sharpening across
// the edge stays; what varies along it (the steps) is not lifted. Off straight edges - texture, flat ground, corners, bends: a
// low orientation coherence, or a source that changes along the tangent by more than ~5 - 15 % of the edge's contrast - the
// increment is mode 2's, bit for bit.
//  modeU 4: Output = max(Source + mix(S - Source, along(S - Source), a) + Residual, 0); a = 0 -> max(S + Residual, 0) (mode 2)
uniform sampler2D Source;           // mode 4: the sharpening's source (mode 3's output) the chain started from
uniform float edgeAlongU;           // mode 4: the along-edge share at full coherence (0: mode 2 exactly)
out vec4 Output;

#define EDGE_TAPS 5
// mode 4: (Sharpened - Source, Source) at a fractional position on a row (steep: along x) or a column (along y): a Catmull-Rom
// cubic over the four texels of that row / column around it (a linear blend would flatten the increment's narrow overshoots
// across the edge, which a tap off the pixel centres reads between two texels)
vec2 incrementAt(vec2 p, bool steep, ivec2 last) {
    float c = steep ? p.x : p.y;
    float f0 = floor(c), f = c - f0;
    vec4 w = vec4(((-0.5 * f + 1.0) * f - 0.5) * f, (1.5 * f - 2.5) * f * f + 1.0, ((-1.5 * f + 2.0) * f + 0.5) * f, (0.5 * f - 0.5) * f * f);
    ivec2 base = steep ? ivec2(int(f0) - 1, int(floor(p.y + 0.5))) : ivec2(int(floor(p.x + 0.5)), int(f0) - 1);
    ivec2 dir1 = steep ? ivec2(1, 0) : ivec2(0, 1);
    vec2 acc = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        ivec2 q = clamp(base + i * dir1, ivec2(0), last);
        float sv = texelFetch(Source, q, 0).r;
        acc += w[i] * vec2(texelFetch(Sharpened, q, 0).r - sv, sv);
    }
    return acc;
}

#if GATHER == 1
// mode 3: tap (i, j) of value t added to lat, as the loop below adds it
float latTap(float lat, int i, int j, float t) {
    const float w5[5] = float[5](0.125, 0.25, 0.25, 0.25, 0.125);
    float sj = (j & 1) == 0 ? 1.0 : -1.0;
    float si = (i & 1) == 0 ? 1.0 : -1.0;
    float v = t * w5[i + 2] * w5[j + 2];
    return lat + (si * sj + sj + si) * v;   // checker + row + column alternation, sign + at xy
}
#endif

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    if (modeU == 2) {
        Output = vec4(max(texelFetch(Sharpened, xy, 0).r + texelFetch(Residual, xy, 0).r, 0.0), 0.0, 0.0, 1.0);
        return;
    }
    if (modeU == 1) {
        Output = vec4(texelFetch(InputBuffer, xy, 0).r - texelFetch(Smoothed, xy, 0).r, 0.0, 0.0, 1.0);
        return;
    }
    if (modeU == 4) {
        float sh = texelFetch(Sharpened, xy, 0).r, res = texelFetch(Residual, xy, 0).r;
        float o = sh + res;
        if (edgeAlongU > 0.0) {
            ivec2 lastS = textureSize(Source, 0) - ivec2(1);
            // the edge's orientation, blind to the 2 x 2 blocks' grid: the source's structure tensor from differences over 8 px
            // (+-4: two whole periods of the blocks, which cancel) at the 3 x 3 points 2 px apart around (an edge 3 - 4 px away,
            // the dark side's grid beside a bright edge, still reads its edge), weights [1 2 1] x [1 2 1] / 16
            float jxx = 0.0, jyy = 0.0, jxy = 0.0;
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    ivec2 q = xy + 2 * ivec2(i, j);
                    float gx = texelFetch(Source, clamp(q + ivec2(4, 0), ivec2(0), lastS), 0).r
                             - texelFetch(Source, clamp(q - ivec2(4, 0), ivec2(0), lastS), 0).r;
                    float gy = texelFetch(Source, clamp(q + ivec2(0, 4), ivec2(0), lastS), 0).r
                             - texelFetch(Source, clamp(q - ivec2(0, 4), ivec2(0), lastS), 0).r;
                    float w = (i == 0 ? 0.5 : 0.25) * (j == 0 ? 0.5 : 0.25);
                    jxx += w * gx * gx; jyy += w * gy * gy; jxy += w * gx * gy;
                }
            }
            float m = texelFetch(Source, xy, 0).r;
            float tr = jxx + jyy, dif = sqrt((jxx - jyy) * (jxx - jyy) + 4.0 * jxy * jxy);
            float coh = dif / max(tr, 1.0e-12);                    // (l1 - l2) / (l1 + l2)
            float rel = sqrt(0.5 * (tr + dif)) / (8.0 * max(m, 0.002)); // the edge's change per px over this pixel's level
            float a = edgeAlongU * smoothstep(0.5, 0.8, coh) * smoothstep(0.02, 0.06, rel);
            if (a > 0.0) {
                float th = 0.5 * atan(2.0 * jxy, jxx - jyy);       // the gradient's direction; the edge runs across it
                vec2 t = vec2(-sin(th), cos(th));
                // taps on whole rows (a steep edge) or columns (a flat one), the other coordinate where the tangent crosses them
                bool steep = abs(t.y) >= abs(t.x);
                vec2 stp = steep ? vec2(t.x / t.y, 1.0) : vec2(1.0, t.y / t.x);
                float d2 = dot(stp, stp);                           // squared px along the edge per tap
                float src = texelFetch(Source, xy, 0).r;
                float inc = sh - src, acc = inc, ws = 1.0, along = 0.0;
                for (int k = 1; k <= EDGE_TAPS; k++) {
                    float w = exp(-float(k * k) * d2 / 8.0);       // sigma 2 px along the edge
                    vec2 u = incrementAt(vec2(xy) + float(k) * stp, steep, lastS), v = incrementAt(vec2(xy) - float(k) * stp, steep, lastS);
                    acc += w * (u.x + v.x);
                    along += w * ((u.y - src) * (u.y - src) + (v.y - src) * (v.y - src));
                    ws += 2.0 * w;
                }
                // a straight edge: the source keeps its level along the tangent - against the edge's contrast (the source 2 px to
                // either side across it) a few hundredths for the steps and the grid beside the edge, a sizeable share for texture
                // and bends: those keep mode 2
                vec2 nrm = vec2(t.y, -t.x);
                float across = abs(incrementAt(vec2(xy) + 2.0 * nrm, steep, lastS).y - incrementAt(vec2(xy) - 2.0 * nrm, steep, lastS).y);
                float wander = sqrt(along / max(ws - 1.0, 1.0e-6)) / max(across, 1.0e-4);
                a *= 1.0 - smoothstep(0.05, 0.15, wander);
                o = src + mix(inc, acc / ws, a) + res;
            }
        }
        Output = vec4(max(o, 0.0), 0.0, 0.0, 1.0);
        return;
    }
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    if (modeU == 3) {
#if GATHER == 1
        // blocks (x0, y0) .. (x0 + 1, y0 + 1) by a gather at the centre of texel (x0 + 1, y0 + 1): .w (x0, y0), .z (x0 + 1, y0),
        // .x (x0, y0 + 1), .y (x0 + 1, y0 + 1)
        vec2 inv = 1.0 / vec2(last + ivec2(1));
        float lt = 0.0;
        for (int by = -2; by <= 2; by += 2) {
            vec4 g0 = textureGather(InputBuffer, (vec2(xy + ivec2(-2, by)) + 1.0) * inv);
            vec4 g1 = textureGather(InputBuffer, (vec2(xy + ivec2(0, by)) + 1.0) * inv);
            vec4 g2 = textureGather(InputBuffer, (vec2(xy + ivec2(2, by)) + 1.0) * inv);
            lt = latTap(lt, -2, by, g0.w); lt = latTap(lt, -1, by, g0.z); lt = latTap(lt, 0, by, g1.w);
            lt = latTap(lt, 1, by, g1.z); lt = latTap(lt, 2, by, g2.w);
            if (by < 2) {
                lt = latTap(lt, -2, by + 1, g0.x); lt = latTap(lt, -1, by + 1, g0.y); lt = latTap(lt, 0, by + 1, g1.x);
                lt = latTap(lt, 1, by + 1, g1.y); lt = latTap(lt, 2, by + 1, g2.x);
            }
        }
        float c0 = texelFetch(InputBuffer, xy, 0).r;
        if (latCapU > 0.0) { float cap = latCapU * max(c0, 0.0) + 0.0002; lt = clamp(lt, -cap, cap); }
        Output = vec4(c0 - lt, 0.0, 0.0, 1.0);
        return;
#endif
        const float w5[5] = float[5](0.125, 0.25, 0.25, 0.25, 0.125);
        float lat = 0.0;
        for (int j = -2; j <= 2; j++) {
            float sj = (j & 1) == 0 ? 1.0 : -1.0;
            for (int i = -2; i <= 2; i++) {
                float si = (i & 1) == 0 ? 1.0 : -1.0;
                float v = texelFetch(InputBuffer, clamp(xy + ivec2(i, j), ivec2(0), last), 0).r * w5[i + 2] * w5[j + 2];
                lat += (si * sj + sj + si) * v;   // checker + row + column alternation, sign + at xy
            }
        }
        float c = texelFetch(InputBuffer, xy, 0).r;
        if (latCapU > 0.0) { float cap = latCapU * max(c, 0.0) + 0.0002; lat = clamp(lat, -cap, cap); }
        Output = vec4(c - lat, 0.0, 0.0, 1.0);
        return;
    }
    float s = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        float row = 0.0;
        for (int dx = -1; dx <= 1; dx++)
            row += (dx == 0 ? 2.0 : 1.0) * texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r;
        s += (dy == 0 ? 2.0 : 1.0) * row;
    }
    Output = vec4(s * 0.0625, 0.0, 0.0, 1.0);
}
