precision highp float; precision highp sampler2D;
// P677 (owner, 2026-11-13: «Да, перенеси в V2»): V1's P391 edge-aware bands (why/lpyr MODE 8 / 9 / 10) on V2's pyramid
// (WhyV2Fusion): the same rule - an edge is where the guide's log light G = log2(I + floor) of a level stands off its Gaussian 3
// levels down, D = |G_l - G_l+3| less k noise sigmas in quadrature (noise is no edge); a level's share of the per-pixel fusion's
// bands is the ramp (in the EV of D, the level's step response folded in) of the max of D less a fall per level px within the reach
// round the pixel, at least the 3 x 3 max of the finer level's share round 2 x (a coarse band never keeps more excess than the finer
// one round the same edge). V2's grid: G_l+3 is read bilinear at the pixel's centre (texture(), what V2's chain of collapses gives),
// a coarse texel c covers the finer 2c, 2c + 1.
//   MODE 0: the edge measure D of a level (R16F; level 0 at the full grid)
//   MODE 1: the edge share of a level l >= 1 (RG16F): r = max(the ramp of this level's cone max of D, the carry), g = the carry
//           alone (the coarsest level's share)
#define MODE 0
uniform sampler2D u_tex_GF;        // MODE 0: (G, Fpp) of this level (RG16F)
uniform sampler2D u_tex_ref;       // MODE 0: (G, Fpp) of the level 3 down (LINEAR)
uniform sampler2D u_tex_D;         // MODE 1: this level's D (MODE 0); unused on the coarsest level
uniform sampler2D u_tex_fine;      // MODE 1: level 0's D (level 1) or the finer level's share (MODE 1)
uniform int u_width;
uniform int u_height;
uniform vec4 u_band_noise;         // MODE 0: x: k N_l (k noise sigmas x the level's white-noise factor), y, z: S, O of the guide I's
                                   // noise (variance y I + z, x AE), w: the guide's light floor; x <= 0: no noise taken out
uniform vec4 u_band_edge;          // MODE 1: x, y: this level's ramp (EV of D), z: its fall per level px, w: unused
uniform ivec2 u_band_reach;        // MODE 1: x: the reach r of the cone max of D (level px; < 0: none, the coarsest level); y: the
                                   // carry's reach (level 1: level 0's reach, its cone one px wider round 2 x; else 1)
uniform vec3 u_band_carry;         // MODE 1: level 1 - level 0's ramp and fall (x < y); else x >= y: the finer level's share as is
out vec4 Output;

#if MODE == 1
// the largest D(q) - fall x max(Chebyshev |q - c| - slack, 0) of t's .r over |q - c| <= r + slack (why/lpyr coneMax)
float coneMax(sampler2D t, ivec2 c, int r, float fall, int slack) {
    ivec2 last = textureSize(t, 0) - ivec2(1);
    int w = r + slack;
    float b = -1024.0;
    for (int j = -w; j <= w; j++)
        for (int i = -w; i <= w; i++)
            b = max(b, texelFetch(t, clamp(c + ivec2(i, j), ivec2(0), last), 0).r - fall * float(max(max(abs(i), abs(j)) - slack, 0)));
    return b;
}
#endif

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) {
        Output = vec4(0.0);
        return;
    }
#if MODE == 0
    float g = texelFetch(u_tex_GF, coord, 0).r;
    float d = abs(g - texture(u_tex_ref, (vec2(coord) + 0.5) / vec2(u_width, u_height)).r);
    float n = 0.0;
    if (u_band_noise.x > 0.0) {
        float z = u_band_noise.w;
        float Il = max(exp2(g) - z, 0.0);
        n = u_band_noise.x * sqrt(max(u_band_noise.y * Il + u_band_noise.z, 0.0)) / ((Il + z) * 0.6931472);
    }
    Output = vec4(sqrt(max(d * d - n * n, 0.0)), 0.0, 0.0, 1.0);
#else
    float own = 0.0;
    if (u_band_reach.x >= 0) own = smoothstep(u_band_edge.x, u_band_edge.y, coneMax(u_tex_D, coord, u_band_reach.x, u_band_edge.z, 0));
    float carry = u_band_carry.x < u_band_carry.y
            ? smoothstep(u_band_carry.x, u_band_carry.y, coneMax(u_tex_fine, 2 * coord, u_band_reach.y, u_band_carry.z, 1))
            : coneMax(u_tex_fine, 2 * coord, 1, 0.0, 0);
    Output = vec4(max(own, carry), carry, 0.0, 1.0);
#endif
}
