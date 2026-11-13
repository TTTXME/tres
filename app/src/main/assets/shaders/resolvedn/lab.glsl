precision highp float; precision highp int; precision highp sampler2D;
uniform sampler2D u_tex_rgb;
uniform int u_width;
uniform int u_height;
out vec4 o_oklab;
#define TILED 0
#if TILED == 1
uniform int originDstU;
#endif

#if TILED == 1
uniform ivec2 full_u_tex_rgb;
uniform int origin_u_tex_rgb;
vec4 dnFetch_u_tex_rgb(ivec2 p, int lod) {
    p = clamp(p, ivec2(0), full_u_tex_rgb - 1);
    return texelFetch(u_tex_rgb, p - ivec2(0, origin_u_tex_rgb), lod);
}
vec2 dnUv_u_tex_rgb(vec2 uv) {
    return (uv * vec2(full_u_tex_rgb) - vec2(0.0, float(origin_u_tex_rgb))) / vec2(textureSize(u_tex_rgb, 0));
}
vec4 dnTexture_u_tex_rgb(vec2 uv) { return texture(u_tex_rgb, dnUv_u_tex_rgb(uv)); }
vec4 dnTextureLod_u_tex_rgb(vec2 uv, float lod) { return textureLod(u_tex_rgb, dnUv_u_tex_rgb(uv), lod); }
#else
vec4 dnFetch_u_tex_rgb(ivec2 p, int lod) { return texelFetch(u_tex_rgb, p, lod); }
vec4 dnTexture_u_tex_rgb(vec2 uv) { return texture(u_tex_rgb, uv); }
vec4 dnTextureLod_u_tex_rgb(vec2 uv, float lod) { return textureLod(u_tex_rgb, uv, lod); }
#endif

float safe_cbrt(float x) {
    return (x <= 0.0000001) ? 0.0 : pow(x, 1.0 / 3.0);
}

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
#if TILED == 1
    coord.y += originDstU;
#endif
    if (coord.x >= u_width || coord.y >= u_height) { o_oklab = vec4(0.0); return; }
    vec3 rgb = max(dnFetch_u_tex_rgb( coord, 0).rgb, 0.0);

    float l_ok = max(0.4122214708 * rgb.r + 0.5363325363 * rgb.g + 0.0514459929 * rgb.b, 0.0);
    float m_ok = max(0.2119034982 * rgb.r + 0.6806995451 * rgb.g + 0.1073969566 * rgb.b, 0.0);
    float s_ok = max(0.0883024619 * rgb.r + 0.2817188376 * rgb.g + 0.6299787005 * rgb.b, 0.0);
    float l_ = safe_cbrt(l_ok);
    float m_ = safe_cbrt(m_ok);
    float s_ = safe_cbrt(s_ok);
    float L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
    float a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
    float b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;

    o_oklab = vec4(L, a, b, 1.0);
}
