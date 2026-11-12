precision highp float;
precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Fusion): the reference tone's ef_pyr_collapse, one to one - a finer level's fused luma: the coarser
// fused luma (bilinear) plus the band of the lumas (the level minus its coarser level, bilinear) weighted by the level's weights;
// u_clarity 0..1 moves the weights towards the band's own size (boost_local_contrast), beyond that a gain on the band.
uniform sampler2D u_tex_G_fine;       // level l (RGBA16F)
uniform sampler2D u_tex_G_coarse;     // level l+1 (RGBA16F, LINEAR)
uniform sampler2D u_tex_W_fine;       // level l (RGBA16F)
uniform sampler2D u_tex_fused_coarse; // level l+1 (R16F, LINEAR)
uniform int u_width;                  // level l width
uniform int u_height;                 // level l height
uniform float u_clarity;
out vec4 Output;

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) {
        Output = vec4(0.0);
        return;
    }
    vec2 uv = (vec2(coord) + 0.5) / vec2(u_width, u_height);

    vec4 g_fine = texelFetch(u_tex_G_fine, coord, 0);
    vec4 w_fine = texelFetch(u_tex_W_fine, coord, 0);

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

    // the intermediate levels are not clamped (as there)
    float fused = fused_coarse + detail;
    Output = vec4(fused, fused, fused, 1.0);
}
