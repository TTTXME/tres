precision highp float; precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Fusion): the reference tone's pyr_downsample, one to one - the next level of the Gaussian pyramids of
// the lumas and the weights: 9 bilinear taps of the finer level (the source texture LINEAR, clamp to edge) at the coarse texel's
// centre, +-1 and +-0.5 source texels (weights 1/4, 1/16 diagonals, 1/8 axes).
uniform sampler2D u_tex_src;
uniform vec2 u_texel_size;          // 1 / source size
uniform int u_dst_w;
uniform int u_dst_h;
out vec4 Output;

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_dst_w || coord.y >= u_dst_h) { Output = vec4(0.0); return; }
    vec2 uv = (vec2(coord) + 0.5) / vec2(u_dst_w, u_dst_h);
    vec2 half_t = u_texel_size * 0.5;
    vec2 full_t = u_texel_size;

    vec4 c = texture(u_tex_src, uv);
    vec4 d0 = texture(u_tex_src, uv + vec2(-full_t.x, -full_t.y));
    vec4 d1 = texture(u_tex_src, uv + vec2( full_t.x, -full_t.y));
    vec4 d2 = texture(u_tex_src, uv + vec2(-full_t.x,  full_t.y));
    vec4 d3 = texture(u_tex_src, uv + vec2( full_t.x,  full_t.y));

    vec4 t0 = texture(u_tex_src, uv + vec2(-half_t.x, 0.0));
    vec4 t1 = texture(u_tex_src, uv + vec2( half_t.x, 0.0));
    vec4 t2 = texture(u_tex_src, uv + vec2(0.0, -half_t.y));
    vec4 t3 = texture(u_tex_src, uv + vec2(0.0,  half_t.y));

    Output = c * 0.25 + (d0 + d1 + d2 + d3) * 0.0625 + (t0 + t1 + t2 + t3) * 0.125;
}
