precision highp float; precision highp int; precision highp sampler2D;
uniform sampler2D u_tex_oklab;
uniform int u_width;
uniform int u_height;
out vec4 o_rgb;
#define TILED 0
#if TILED == 1
uniform int originDstU;
#endif

#if TILED == 1
uniform ivec2 full_u_tex_oklab;
uniform int origin_u_tex_oklab;
vec4 dnFetch_u_tex_oklab(ivec2 p, int lod) {
    p = clamp(p, ivec2(0), full_u_tex_oklab - 1);
    return texelFetch(u_tex_oklab, p - ivec2(0, origin_u_tex_oklab), lod);
}
vec2 dnUv_u_tex_oklab(vec2 uv) {
    return (uv * vec2(full_u_tex_oklab) - vec2(0.0, float(origin_u_tex_oklab))) / vec2(textureSize(u_tex_oklab, 0));
}
vec4 dnTexture_u_tex_oklab(vec2 uv) { return texture(u_tex_oklab, dnUv_u_tex_oklab(uv)); }
vec4 dnTextureLod_u_tex_oklab(vec2 uv, float lod) { return textureLod(u_tex_oklab, dnUv_u_tex_oklab(uv), lod); }
#else
vec4 dnFetch_u_tex_oklab(ivec2 p, int lod) { return texelFetch(u_tex_oklab, p, lod); }
vec4 dnTexture_u_tex_oklab(vec2 uv) { return texture(u_tex_oklab, uv); }
vec4 dnTextureLod_u_tex_oklab(vec2 uv, float lod) { return textureLod(u_tex_oklab, uv, lod); }
#endif

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
#if TILED == 1
    coord.y += originDstU;
#endif
    if (coord.x >= u_width || coord.y >= u_height) { o_rgb = vec4(0.0); return; }
    vec3 lab = dnFetch_u_tex_oklab( coord, 0).rgb;
    float L = max(lab.r, 0.0);
    float a = lab.g;
    float b = lab.b;

    float l_inv = max(0.0, L + 0.3963377774 * a + 0.2158037573 * b);
    float m_inv = max(0.0, L - 0.1055613458 * a - 0.0638541728 * b);
    float s_inv = max(0.0, L - 0.0894841775 * a - 1.2914855480 * b);
    float l3 = l_inv * l_inv * l_inv;
    float m3 = m_inv * m_inv * m_inv;
    float s3 = s_inv * s_inv * s_inv;
    vec3 rgb = vec3(
        max(+4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3, 0.0),
        max(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3, 0.0),
        max(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3, 0.0)
    );
    o_rgb = vec4(rgb, 1.0);
}
