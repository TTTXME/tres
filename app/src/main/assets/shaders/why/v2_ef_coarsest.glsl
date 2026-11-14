precision highp float; precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Fusion): the reference tone's ef_pyr_coarsest, one to one - the coarsest level's fused luma, the lumas
// weighted by their weights.
// P677 BANDS 1 (V1's P391 bands, why/lpyr baseOf): moved towards the per-pixel fusion's Gaussian (why/v2_ef_prep MODE 2's .g) by
// the level's carried edge share (why/v2_ef_bands MODE 1's .g) x the strength. 0: the shader before P677, token for token.
#define BANDS 0
uniform sampler2D u_tex_G;
uniform sampler2D u_tex_W;
uniform int u_width;
uniform int u_height;
#if BANDS == 1
uniform sampler2D u_tex_GF;           // (G, Fpp) of the coarsest level
uniform sampler2D u_tex_share;        // its edge share (.g: the carry)
uniform float u_band_strength;
#endif
out vec4 Output;

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) {
        Output = vec4(0.0);
        return;
    }
    vec4 g = texelFetch(u_tex_G, coord, 0);
    vec4 w = texelFetch(u_tex_W, coord, 0);
    float fused = dot(g, w);
#if BANDS == 1
    fused += texelFetch(u_tex_share, coord, 0).g * clamp(u_band_strength, 0.0, 1.0) * (texelFetch(u_tex_GF, coord, 0).g - fused);
#endif
    Output = vec4(fused, fused, fused, 1.0);
}
