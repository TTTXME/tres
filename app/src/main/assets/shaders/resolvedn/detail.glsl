precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D u_tex_fine;    // Level k (Oklab)
uniform sampler2D u_tex_coarse;  // Level k+1 (Oklab)
uniform sampler2D u_tex_sabre_weights; // Sabre accumulated frame weights (GL_R16F)
uniform int u_has_sabre_weights;
uniform int u_width;
uniform int u_height;
uniform int u_level;             // 0, 1, 2
uniform float u_luma_thresh;
uniform float u_chroma_thresh;
uniform float u_shadow_boost;
out vec4 o_filtered_laplace;
#define TILED 0
#if TILED == 1
uniform int originDstU;
#endif

#if TILED == 1
uniform ivec2 full_u_tex_fine;
uniform int origin_u_tex_fine;
vec4 dnFetch_u_tex_fine(ivec2 p, int lod) {
    p = clamp(p, ivec2(0), full_u_tex_fine - 1);
    return texelFetch(u_tex_fine, p - ivec2(0, origin_u_tex_fine), lod);
}
vec2 dnUv_u_tex_fine(vec2 uv) {
    return (uv * vec2(full_u_tex_fine) - vec2(0.0, float(origin_u_tex_fine))) / vec2(textureSize(u_tex_fine, 0));
}
vec4 dnTexture_u_tex_fine(vec2 uv) { return texture(u_tex_fine, dnUv_u_tex_fine(uv)); }
vec4 dnTextureLod_u_tex_fine(vec2 uv, float lod) { return textureLod(u_tex_fine, dnUv_u_tex_fine(uv), lod); }
#else
vec4 dnFetch_u_tex_fine(ivec2 p, int lod) { return texelFetch(u_tex_fine, p, lod); }
vec4 dnTexture_u_tex_fine(vec2 uv) { return texture(u_tex_fine, uv); }
vec4 dnTextureLod_u_tex_fine(vec2 uv, float lod) { return textureLod(u_tex_fine, uv, lod); }
#endif

#if TILED == 1
uniform ivec2 full_u_tex_coarse;
uniform int origin_u_tex_coarse;
vec4 dnFetch_u_tex_coarse(ivec2 p, int lod) {
    p = clamp(p, ivec2(0), full_u_tex_coarse - 1);
    return texelFetch(u_tex_coarse, p - ivec2(0, origin_u_tex_coarse), lod);
}
vec2 dnUv_u_tex_coarse(vec2 uv) {
    return (uv * vec2(full_u_tex_coarse) - vec2(0.0, float(origin_u_tex_coarse))) / vec2(textureSize(u_tex_coarse, 0));
}
vec4 dnTexture_u_tex_coarse(vec2 uv) { return texture(u_tex_coarse, dnUv_u_tex_coarse(uv)); }
vec4 dnTextureLod_u_tex_coarse(vec2 uv, float lod) { return textureLod(u_tex_coarse, dnUv_u_tex_coarse(uv), lod); }
#else
vec4 dnFetch_u_tex_coarse(ivec2 p, int lod) { return texelFetch(u_tex_coarse, p, lod); }
vec4 dnTexture_u_tex_coarse(vec2 uv) { return texture(u_tex_coarse, uv); }
vec4 dnTextureLod_u_tex_coarse(vec2 uv, float lod) { return textureLod(u_tex_coarse, uv, lod); }
#endif

#if TILED == 1
uniform ivec2 full_u_tex_sabre_weights;
uniform int origin_u_tex_sabre_weights;
vec4 dnFetch_u_tex_sabre_weights(ivec2 p, int lod) {
    p = clamp(p, ivec2(0), full_u_tex_sabre_weights - 1);
    return texelFetch(u_tex_sabre_weights, p - ivec2(0, origin_u_tex_sabre_weights), lod);
}
vec2 dnUv_u_tex_sabre_weights(vec2 uv) {
    return (uv * vec2(full_u_tex_sabre_weights) - vec2(0.0, float(origin_u_tex_sabre_weights))) / vec2(textureSize(u_tex_sabre_weights, 0));
}
vec4 dnTexture_u_tex_sabre_weights(vec2 uv) { return texture(u_tex_sabre_weights, dnUv_u_tex_sabre_weights(uv)); }
vec4 dnTextureLod_u_tex_sabre_weights(vec2 uv, float lod) { return textureLod(u_tex_sabre_weights, dnUv_u_tex_sabre_weights(uv), lod); }
#else
vec4 dnFetch_u_tex_sabre_weights(ivec2 p, int lod) { return texelFetch(u_tex_sabre_weights, p, lod); }
vec4 dnTexture_u_tex_sabre_weights(vec2 uv) { return texture(u_tex_sabre_weights, uv); }
vec4 dnTextureLod_u_tex_sabre_weights(vec2 uv, float lod) { return textureLod(u_tex_sabre_weights, uv, lod); }
#endif

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
#if TILED == 1
    coord.y += originDstU;
#endif
    if (coord.x >= u_width || coord.y >= u_height) { o_filtered_laplace = vec4(0.0); return; }

    vec2 uv = (vec2(coord) + 0.5) / vec2(u_width, u_height);
    vec4 fine = dnFetch_u_tex_fine( coord, 0);
    vec4 coarse = dnTexture_u_tex_coarse( uv);
    vec4 laplace = fine - coarse;

    float L_fine = fine.r;
    float L_lap = laplace.r;
    vec2 ab_lap = laplace.gb;

    // Motion Boost Modulation:
    // Base strength (1.0x) is applied everywhere (full denoise to static merged stack).
    // In motion areas (where Sabre rejected frames, lower SNR), boost denoise strength:
    // +30% for Luma (1.30x) to preserve motion edge acuity, +50% for Chroma (1.50x) to eliminate color noise.
    float motion_factor = 0.0;
    if (u_has_sabre_weights != 0) {
        float num_merged = max(1.0, dnTexture_u_tex_sabre_weights( uv).r + 1.0);
        // Smooth cubic transition: 0.0 when static (>= 6 frames), 1.0 when motion (1 frame)
        float t = clamp(1.0 - (num_merged - 1.0) / 5.0, 0.0, 1.0);
        motion_factor = t * t * (3.0 - 2.0 * t);
    }
    float l_motion_boost = mix(1.0, 1.30, motion_factor);
    float c_motion_boost = mix(1.0, 1.50, motion_factor);

    float shadow_factor = 1.0 + u_shadow_boost * max(0.0, 1.0 - L_fine * 2.5);
    float l_th = u_luma_thresh * shadow_factor * l_motion_boost;
    float c_th = u_chroma_thresh * shadow_factor * c_motion_boost;

    float level_luma_scale = (u_level == 0) ? 1.0 : ((u_level == 1) ? 0.65 : 0.35);
    float level_chroma_scale = (u_level == 0) ? 1.0 : ((u_level == 1) ? 0.85 : 0.70);
    l_th *= level_luma_scale;
    c_th *= level_chroma_scale;

    // Luma Wavelet Shrinkage (Adaptive Soft-Threshold with edge preservation)
    float l_abs = abs(L_lap);
    float l_sign = (L_lap >= 0.0) ? 1.0 : -1.0;
    float l_gain = smoothstep(0.0, l_th * 2.5, l_abs);
    float l_filtered = l_sign * max(0.0, l_abs - l_th * (1.0 - l_gain));

    // Luma-Guided Chroma Filtering
    vec2 ab_filtered = ab_lap;
    if (c_th > 0.0001) {
        vec2 sum_ab = ab_lap;
        float sum_w = 1.0;

        int pixelStep = (u_level == 2) ? 2 : 1;
        for (int dy = -1; dy <= 1; dy++) {
            int ny = clamp(coord.y + dy * pixelStep, 0, u_height - 1);
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = clamp(coord.x + dx * pixelStep, 0, u_width - 1);
                vec4 n_fine = dnFetch_u_tex_fine( ivec2(nx, ny), 0);
                vec4 n_coarse = dnTextureLod_u_tex_coarse( (vec2(nx, ny) + 0.5) / vec2(u_width, u_height), 0.0);
                vec4 n_lap = n_fine - n_coarse;

                float dL = abs(n_fine.r - L_fine);
                float dist2 = float(dx * dx + dy * dy);
                float w = exp(-dist2 * 0.5) * exp(-dL * 35.0);

                sum_ab += n_lap.gb * w;
                sum_w += w;
            }
        }

        vec2 ab_guided = sum_ab / sum_w;

        // Edge factor: preserve chroma detail where real luminance edges exist
        float edge_factor = smoothstep(0.005, 0.035, abs(L_lap));

        // Filter strength based on c_th
        float shrink = clamp(c_th * 45.0, 0.0, 1.0);

        // In flat areas / shadows, shrink chroma noise towards zero.
        // Along physical luminance edges, keep the guided chroma detail.
        float noise_suppression = shrink * (1.0 - edge_factor);
        vec2 ab_cleaned = mix(ab_guided, vec2(0.0), noise_suppression);
        ab_filtered = mix(ab_lap, ab_cleaned, shrink);
    }

    o_filtered_laplace = vec4(l_filtered, ab_filtered.x, ab_filtered.y, 1.0);
}
