precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D u_tex_src; // Level 3 Oklab
uniform int u_width;
uniform int u_height;
uniform float u_chroma_thresh;
uniform float u_shadow_boost;
out vec4 o_filtered;
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
    if (coord.x >= u_width || coord.y >= u_height) { o_filtered = vec4(0.0); return; }

    vec4 center = dnFetch_u_tex_src( coord, 0);
    float L_center = center.r;
    vec2 ab_center = center.gb;

    float shadow_factor = 1.0 + u_shadow_boost * max(0.0, 1.0 - L_center * 2.5);
    float c_th = u_chroma_thresh * shadow_factor;

    if (c_th <= 0.0001) {
        o_filtered = center;
        return;
    }

    vec2 sum_ab = ab_center;
    float sum_w = 1.0;

    // 5x5 Luma-Guided Chroma Bilateral filter on coarsest scale (eliminates 30-80px optical blotches)
    for (int dy = -2; dy <= 2; dy++) {
        int ny = clamp(coord.y + dy, 0, u_height - 1);
        for (int dx = -2; dx <= 2; dx++) {
            if (dx == 0 && dy == 0) continue;
            int nx = clamp(coord.x + dx, 0, u_width - 1);
            vec4 n = dnFetch_u_tex_src( ivec2(nx, ny), 0);

            float dL = abs(n.r - L_center);
            float dist2 = float(dx * dx + dy * dy);
            float w = exp(-dist2 * 0.25) * exp(-dL * 30.0);

            sum_ab += n.gb * w;
            sum_w += w;
        }
    }

    vec2 ab_smooth = sum_ab / sum_w;
    float blend_strength = clamp(c_th * 45.0, 0.0, 1.0);
    vec2 ab_final = mix(ab_center, ab_smooth, blend_strength);

    o_filtered = vec4(L_center, ab_final.x, ab_final.y, 1.0);
}
