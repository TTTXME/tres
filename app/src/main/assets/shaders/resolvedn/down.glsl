precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D u_tex_src;
uniform vec2 u_texel_size;
uniform int u_dst_w;
uniform int u_dst_h;
out vec4 o_down;
#define TILED 0
#if TILED == 1
uniform int originDstU;
#endif

#if TILED == 1
uniform ivec2 full_u_tex_src;
uniform int origin_u_tex_src;
vec4 dnFetch_u_tex_src(ivec2 p, int lod) {
    p = clamp(p, ivec2(0), full_u_tex_src - 1);
    return texelFetch(u_tex_src, p - ivec2(0, origin_u_tex_src), lod);
}
vec2 dnUv_u_tex_src(vec2 uv) {
    return (uv * vec2(full_u_tex_src) - vec2(0.0, float(origin_u_tex_src))) / vec2(textureSize(u_tex_src, 0));
}
vec4 dnTexture_u_tex_src(vec2 uv) { return texture(u_tex_src, dnUv_u_tex_src(uv)); }
vec4 dnTextureLod_u_tex_src(vec2 uv, float lod) { return textureLod(u_tex_src, dnUv_u_tex_src(uv), lod); }
#else
vec4 dnFetch_u_tex_src(ivec2 p, int lod) { return texelFetch(u_tex_src, p, lod); }
vec4 dnTexture_u_tex_src(vec2 uv) { return texture(u_tex_src, uv); }
vec4 dnTextureLod_u_tex_src(vec2 uv, float lod) { return textureLod(u_tex_src, uv, lod); }
#endif

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
#if TILED == 1
    coord.y += originDstU;
#endif
    if (coord.x >= u_dst_w || coord.y >= u_dst_h) { o_down = vec4(0.0); return; }
    vec2 uv = (vec2(coord) + 0.5) / vec2(u_dst_w, u_dst_h);
    vec2 half_t = u_texel_size * 0.5;
    vec2 full_t = u_texel_size;

    vec4 c = dnTexture_u_tex_src( uv);
    vec4 d0 = dnTexture_u_tex_src( uv + vec2(-full_t.x, -full_t.y));
    vec4 d1 = dnTexture_u_tex_src( uv + vec2( full_t.x, -full_t.y));
    vec4 d2 = dnTexture_u_tex_src( uv + vec2(-full_t.x,  full_t.y));
    vec4 d3 = dnTexture_u_tex_src( uv + vec2( full_t.x,  full_t.y));

    vec4 t0 = dnTexture_u_tex_src( uv + vec2(-half_t.x, 0.0));
    vec4 t1 = dnTexture_u_tex_src( uv + vec2( half_t.x, 0.0));
    vec4 t2 = dnTexture_u_tex_src( uv + vec2(0.0, -half_t.y));
    vec4 t3 = dnTexture_u_tex_src( uv + vec2(0.0,  half_t.y));

    o_down = c * 0.25 + (d0 + d1 + d2 + d3) * 0.0625 + (t0 + t1 + t2 + t3) * 0.125;
}
