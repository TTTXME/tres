precision highp float; precision highp int; precision highp sampler2D;
uniform sampler2D u_tex_rgb;
uniform int u_width; uniform int u_height;
uniform float u_quad_speckle_strength; uniform float u_quad_speckle_threshold;
out vec4 o_clean_rgb;
#define TILED 0
// P479b (speed, owner 2026-11-03: <= 1 level of the JPEG): 1 = resolvedn/lab fused in - the cleaned RGB goes to Oklab here,
// without its RGBA16F rounding in between (the same arithmetic as lab.glsl otherwise; WhyMsDenoise "ms_fuse")
#define OUT_LAB 0
// P671 (speed): the corner filter's 24 neighbours each took rgb_to_oklab (3 cube roots) on every pixel the lens shading lifts
// (most of the frame); OKLAB_CACHE 1 writes rgb_to_oklab of every pixel of a strip into an RGBA32F texture (exact floats), 2
// reads the neighbours' values from it - the same numbers, each computed once instead of 25 times (resolvedn/optics only; not
// tiled; scam_dev ms_oklab_cache 0: as before)
#define OKLAB_CACHE 0
#if OKLAB_CACHE != 0
uniform int cacheOriginU;           // the full frame's row of the cache's row 0
#endif
#if OKLAB_CACHE == 2
uniform highp sampler2D OkCache;    // .rgb: rgb_to_oklab(max(rgb, 0)) of the pixel (full-frame x, row - cacheOriginU)
#endif
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

void rgb_to_oklab(vec3 rgb, out float L, out float a, out float b) {
    float l_ok = max(0.4122214708 * rgb.r + 0.5363325363 * rgb.g + 0.0514459929 * rgb.b, 0.0);
    float m_ok = max(0.2119034982 * rgb.r + 0.6806995451 * rgb.g + 0.1073969566 * rgb.b, 0.0);
    float s_ok = max(0.0883024619 * rgb.r + 0.2817188376 * rgb.g + 0.6299787005 * rgb.b, 0.0);
    float l_ = safe_cbrt(l_ok);
    float m_ = safe_cbrt(m_ok);
    float s_ = safe_cbrt(s_ok);
    L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
    a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
    b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;
}

vec3 oklab_to_rgb(float L, float a, float b) {
    float l_inv = max(0.0, L + 0.3963377774 * a + 0.2158037573 * b);
    float m_inv = max(0.0, L - 0.1055613458 * a - 0.0638541728 * b);
    float s_inv = max(0.0, L - 0.0894841775 * a - 1.2914855480 * b);
    float l3 = l_inv * l_inv * l_inv;
    float m3 = m_inv * m_inv * m_inv;
    float s3 = s_inv * s_inv * s_inv;
    return vec3(
        max(+4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3, 0.0),
        max(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3, 0.0),
        max(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3, 0.0)
    );
}

#if OKLAB_CACHE == 2
void cachedOklab(int nx, int ny, out float L, out float a, out float b) {
    vec3 v = texelFetch(OkCache, ivec2(nx, ny - cacheOriginU), 0).rgb;
    L = v.r; a = v.g; b = v.b;
}
#endif

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
#if OKLAB_CACHE == 1
    {
        ivec2 p = ivec2(coord.x, coord.y + cacheOriginU);
        float cL, ca, cb;
        rgb_to_oklab(max(dnFetch_u_tex_rgb( p, 0).rgb, 0.0), cL, ca, cb);
        o_clean_rgb = vec4(cL, ca, cb, 1.0);
        return;
    }
#endif
#if TILED == 1
    coord.y += originDstU;
#endif
    if (coord.x >= u_width || coord.y >= u_height) { o_clean_rgb = vec4(0.0); return; }
    vec3 rgb = max(dnFetch_u_tex_rgb( coord, 0).rgb, 0.0);

    // 2. QUAD-BAYER SPECKLE & VIGNETTE CHROMA CLEANUP (in Oklab)
    float lsc_gain = dnFetch_u_tex_rgb( coord, 0).a;
    if (lsc_gain <= 0.0) {
        vec2 norm_uv = (vec2(coord) + 0.5) / vec2(u_width, u_height) - vec2(0.5);
        lsc_gain = 1.0 + 2.0 * dot(norm_uv, norm_uv);
    }
    float corner_boost = clamp((lsc_gain - 1.1) / 1.4, 0.0, 1.0);

    if (u_quad_speckle_strength > 0.001 || corner_boost > 0.01) {
        float L, a_val, b_val;
        rgb_to_oklab(rgb, L, a_val, b_val);

        if (u_quad_speckle_strength > 0.001) {
            float a_win[9]; float b_win[9];
            int k = 0;
            for (int dy = -1; dy <= 1; dy++) {
                int ny = clamp(coord.y + dy, 0, u_height - 1);
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = clamp(coord.x + dx, 0, u_width - 1);
                    float n_L, n_a, n_b;
#if OKLAB_CACHE == 2
                    cachedOklab(nx, ny, n_L, n_a, n_b);
#else
                    vec3 n_rgb = max(dnFetch_u_tex_rgb( ivec2(nx, ny), 0).rgb, 0.0);
                    rgb_to_oklab(n_rgb, n_L, n_a, n_b);
#endif
                    a_win[k] = n_a; b_win[k] = n_b; k++;
                }
            }
            for (int i = 1; i < 9; i++) {
                float ka = a_win[i]; float kb = b_win[i]; int j = i - 1;
                while (j >= 0 && a_win[j] > ka) { a_win[j + 1] = a_win[j]; j--; }
                a_win[j + 1] = ka;
                j = i - 1;
                while (j >= 0 && b_win[j] > kb) { b_win[j + 1] = b_win[j]; j--; }
                b_win[j + 1] = kb;
            }
            float med_a = a_win[4]; float med_b = b_win[4];
            if (u_quad_speckle_threshold > 0.0001) {
                float da = a_val - med_a; float db = b_val - med_b;
                float dist = sqrt(da * da + db * db);
                float blend_sp = smoothstep(u_quad_speckle_threshold * 0.5, u_quad_speckle_threshold * 1.5, dist) * u_quad_speckle_strength;
                a_val = mix(a_val, med_a, blend_sp);
                b_val = mix(b_val, med_b, blend_sp);
            } else {
                a_val = mix(a_val, med_a, u_quad_speckle_strength);
                b_val = mix(b_val, med_b, u_quad_speckle_strength);
            }
        }

        if (corner_boost > 0.01) {
            float sum_w = 1.0;
            float sum_a = a_val;
            float sum_b = b_val;
            for (int dy = -2; dy <= 2; dy++) {
                int ny = clamp(coord.y + dy, 0, u_height - 1);
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = clamp(coord.x + dx, 0, u_width - 1);
                    float n_L, n_a, n_b;
#if OKLAB_CACHE == 2
                    cachedOklab(nx, ny, n_L, n_a, n_b);
#else
                    vec3 n_rgb = max(dnFetch_u_tex_rgb( ivec2(nx, ny), 0).rgb, 0.0);
                    rgb_to_oklab(n_rgb, n_L, n_a, n_b);
#endif
                    float dL = n_L - L;
                    float dist2 = float(dx * dx + dy * dy);
                    float w = exp(-dist2 * 0.35) * exp(-dL * dL * 250.0);
                    sum_a += n_a * w;
                    sum_b += n_b * w;
                    sum_w += w;
                }
            }
            float filt_a = sum_a / sum_w;
            float filt_b = sum_b / sum_w;
            a_val = mix(a_val, filt_a, corner_boost * 0.85);
            b_val = mix(b_val, filt_b, corner_boost * 0.85);
        }

        rgb = oklab_to_rgb(L, a_val, b_val);
    }
#if OUT_LAB == 1
    {
        // resolvedn/lab's main on this pixel's result (its max(0) and conversion)
        vec3 c = max(rgb, 0.0);
        float l_ok = max(0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b, 0.0);
        float m_ok = max(0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b, 0.0);
        float s_ok = max(0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b, 0.0);
        float l_ = safe_cbrt(l_ok);
        float m_ = safe_cbrt(m_ok);
        float s_ = safe_cbrt(s_ok);
        o_clean_rgb = vec4(0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
                           1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
                           0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_, 1.0);
    }
#else
    o_clean_rgb = vec4(rgb, 1.0);
#endif
}
