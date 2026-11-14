precision highp float; precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Fusion): the reference tone's ef_pyr_collapse, one to one - a finer level's fused luma: the coarser
// fused luma (bilinear) plus the band of the lumas (the level minus its coarser level, bilinear) weighted by the level's weights;
// u_clarity 0..1 moves the weights towards the band's own size (boost_local_contrast), beyond that a gain on the band.
// P673 JBU (owner, 2026-11-13, the reference's k_fs_ef_pyr_collapse with joint bilateral upsampling: «вместо аппаратного
// билинейного texture() ... сэмплируются 4 coarse-текселя с весом exp(-(g_coarse - g_fine)^2 / 2 sigma_r^2)»): the coarse level
// (u_tex_G_coarse and u_tex_fused_coarse alike: the band and the base stay one reconstruction) is read from the 4 texels of
// texture()'s bilinear footprint, each weighted by its bilinear weight x exp(-(g_coarse - g_fine)^2 / (2 u_jbu_sigma^2)),
// normalised. JBU 1: g is this shader's own g_fine / g_coarse - the four exposures' display lumas, at this pixel (level l) and at
// the coarse texel (level l+1), the square their squared distance; JBU 2: g is the collapsing image itself - the coarser fused
// luma at the texel against this pixel's fused luma of the plain (bilinear) collapse. u_jbu_log > 0: g as log2(max(g,
// u_jbu_log)) (sigma_r in EV), else as it is. A texel across a contrasty edge drops out, so the coarse level does not leak over
// the edge into the sky; on flat areas the weights stay bilinear; one exposure still collapses to its level exactly. The range
// weights are taken relative to the nearest texel's (the same normalised weights, no underflow); where all of them still vanish,
// bilinear. 0: the shader before P673, token for token.
#define JBU 0
// P677 BANDS 1 (owner, 2026-11-13: «Да, перенеси в V2»): V1's P391 edge-aware bands (why/lpyr EDGE_BANDS 1, edgeBand) - the band is
// the per-pixel fusion's band P = Fpp_l - expand(Fpp_l+1) (its Gaussian pyramid, why/v2_ef_prep MODE 2, read with the same weights
// as the lumas: bilinear or JBU) plus the pyramid's excess E = M - P (M: the band blended by the weights, as before) times 1 - a,
// a the level's edge share (why/v2_ef_bands MODE 1; level 0: the ramp of the cone max of level 0's D) x the strength. a = 0: M
// itself; a = 1 at every level: the per-pixel fusion (the coarsest level moved to Fpp's Gaussian by its carried share, why/
// v2_ef_coarsest BANDS 1). 0: the shader before P677, token for token.
#define BANDS 0
uniform sampler2D u_tex_G_fine;       // level l (RGBA16F)
uniform sampler2D u_tex_G_coarse;     // level l+1 (RGBA16F, LINEAR)
uniform sampler2D u_tex_W_fine;       // level l (RGBA16F)
uniform sampler2D u_tex_fused_coarse; // level l+1 (R16F, LINEAR)
uniform int u_width;                  // level l width
uniform int u_height;                 // level l height
uniform float u_clarity;
#if JBU != 0
uniform float u_jbu_sigma;            // sigma_r of the range weight (the guide's unit)
uniform float u_jbu_log;              // > 0: the guide as log2(max(g, u_jbu_log)); 0: as it is
#endif
#if BANDS == 1
uniform sampler2D u_tex_GF_fine;      // (G, Fpp) of level l (RG16F)
uniform sampler2D u_tex_GF_coarse;    // (G, Fpp) of level l+1 (RG16F, LINEAR)
uniform sampler2D u_tex_share;        // level l >= 1: its edge share (.r); level 0: its edge measure D
uniform vec4 u_band_edge;             // level 0: x, y: the ramp (EV of D), z: its fall per px; w: the strength (every level)
uniform int u_band_reach;             // level 0: the reach of the cone max of D (px); < 0: u_tex_share holds the share
#endif
out vec4 Output;

#if BANDS == 1
// the largest D(q) - fall x max(Chebyshev |q - c|, 0) of t's .r over |q - c| <= r (why/lpyr coneMax, slack 0)
float bandCone(sampler2D t, ivec2 c, int r, float fall) {
    ivec2 last = textureSize(t, 0) - ivec2(1);
    float b = -1024.0;
    for (int j = -r; j <= r; j++)
        for (int i = -r; i <= r; i++)
            b = max(b, texelFetch(t, clamp(c + ivec2(i, j), ivec2(0), last), 0).r - fall * float(max(abs(i), abs(j))));
    return b;
}
#endif

#if JBU != 0
vec4 jbuGuide(vec4 g) {
    return u_jbu_log > 0.0 ? log2(max(g, vec4(u_jbu_log))) : g;
}

// the band blended by the level's weights (u_clarity as below)
float detailOf(vec4 laplace, vec4 w_fine) {
    float c_blend = clamp(u_clarity, 0.0, 1.0);
    vec4 c_mod = mix(vec4(1.0), abs(laplace) + 0.00001, c_blend);
    vec4 eff_weights = w_fine * c_mod;
    eff_weights /= (dot(eff_weights, vec4(1.0)) + 0.00001);
    float detail = dot(eff_weights, laplace);
    if (u_clarity > 1.0) {
        detail *= (1.0 + (u_clarity - 1.0));
    } else if (u_clarity < 0.0) {
        detail *= max(1.0 + u_clarity, 0.0);
    }
    return detail;
}
#endif

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) {
        Output = vec4(0.0);
        return;
    }
    vec2 uv = (vec2(coord) + 0.5) / vec2(u_width, u_height);

    vec4 g_fine = texelFetch(u_tex_G_fine, coord, 0);
    vec4 w_fine = texelFetch(u_tex_W_fine, coord, 0);

#if JBU != 0
    // texture()'s bilinear footprint (clamp to edge), texel by texel
    ivec2 cs = textureSize(u_tex_G_coarse, 0);
    vec2 pc = uv * vec2(cs) - 0.5;
    vec2 p0 = floor(pc);
    vec2 f = pc - p0;
    ivec2 last = cs - ivec2(1);
    ivec2 a = clamp(ivec2(p0), ivec2(0), last), b = clamp(ivec2(p0) + ivec2(1), ivec2(0), last);
    ivec2 t10 = ivec2(b.x, a.y), t01 = ivec2(a.x, b.y);
    vec4 G00 = texelFetch(u_tex_G_coarse, a, 0), G10 = texelFetch(u_tex_G_coarse, t10, 0);
    vec4 G01 = texelFetch(u_tex_G_coarse, t01, 0), G11 = texelFetch(u_tex_G_coarse, b, 0);
    vec4 F4 = vec4(texelFetch(u_tex_fused_coarse, a, 0).r, texelFetch(u_tex_fused_coarse, t10, 0).r,
                   texelFetch(u_tex_fused_coarse, t01, 0).r, texelFetch(u_tex_fused_coarse, b, 0).r);
    vec4 bw = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
#if JBU == 1
    vec4 q = jbuGuide(g_fine);
    vec4 e00 = jbuGuide(G00) - q, e10 = jbuGuide(G10) - q, e01 = jbuGuide(G01) - q, e11 = jbuGuide(G11) - q;
    vec4 d2 = vec4(dot(e00, e00), dot(e10, e10), dot(e01, e01), dot(e11, e11));
#else
    float f_plain = dot(bw, F4) + detailOf(g_fine - (bw.x * G00 + bw.y * G10 + bw.z * G01 + bw.w * G11), w_fine);
    vec4 e = jbuGuide(F4) - jbuGuide(vec4(f_plain));
    vec4 d2 = e * e;
#endif
    float d2min = min(min(d2.x, d2.y), min(d2.z, d2.w));
    vec4 jw = bw * exp(-(d2 - vec4(d2min)) / max(2.0 * u_jbu_sigma * u_jbu_sigma, 1.0e-12));
    float js = jw.x + jw.y + jw.z + jw.w;
    jw = js > 1.0e-20 ? jw / js : bw;
    vec4 g_coarse = jw.x * G00 + jw.y * G10 + jw.z * G01 + jw.w * G11;
    float fused_coarse = dot(jw, F4);
    float detail = detailOf(g_fine - g_coarse, w_fine);
#else
    vec4 g_coarse = texture(u_tex_G_coarse, uv);
    float fused_coarse = texture(u_tex_fused_coarse, uv).r;

    vec4 laplace = g_fine - g_coarse;

    float c_blend = clamp(u_clarity, 0.0, 1.0);
    vec4 c_mod = mix(vec4(1.0), abs(laplace) + 0.00001, c_blend);
    vec4 eff_weights = w_fine * c_mod;
    eff_weights /= (dot(eff_weights, vec4(1.0)) + 0.00001);

    float detail = dot(eff_weights, laplace);
    if (u_clarity > 1.0) {
        detail *= (1.0 + (u_clarity - 1.0));
    } else if (u_clarity < 0.0) {
        detail *= max(1.0 + u_clarity, 0.0);
    }

#endif

#if BANDS == 1
    {
#if JBU != 0
        float fpp_coarse = dot(jw, vec4(texelFetch(u_tex_GF_coarse, a, 0).g, texelFetch(u_tex_GF_coarse, t10, 0).g,
                                        texelFetch(u_tex_GF_coarse, t01, 0).g, texelFetch(u_tex_GF_coarse, b, 0).g));
#else
        float fpp_coarse = texture(u_tex_GF_coarse, uv).g;
#endif
        float band_p = texelFetch(u_tex_GF_fine, coord, 0).g - fpp_coarse;
        float share = u_band_reach >= 0 ? smoothstep(u_band_edge.x, u_band_edge.y, bandCone(u_tex_share, coord, u_band_reach, u_band_edge.z))
                                        : texelFetch(u_tex_share, coord, 0).r;
        share *= clamp(u_band_edge.w, 0.0, 1.0);
        if (share > 0.0) detail = band_p + (1.0 - share) * (detail - band_p);
    }
#endif

    // the intermediate levels are not clamped (as there)
    float fused = fused_coarse + detail;
    Output = vec4(fused, fused, fused, 1.0);
}
