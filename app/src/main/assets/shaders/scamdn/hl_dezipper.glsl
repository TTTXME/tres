#version 310 es
precision highp float;

// P480: the reference's highlight_chroma_dezipper (its program 0x160) verbatim, run by WhyHlDezipper right after the resolve
// (WhyResolveDenoise) on a fast merge whose highlight path was on. The one change: tex_coord, which its full-screen triangle
// interpolates to the pixel centre ((i + 0.5) / size), is gl_FragCoord / size here (the app's vertex shader has no varying).
#define tex_coord (gl_FragCoord.xy * u_tex_size.zw)
layout(location = 0) out vec4 o_dezippered_rgb;

uniform sampler2D u_merged_rgb;
uniform sampler2D u_highlight_mask;
uniform vec4 u_tex_size; // x=w, y=h, z=1/w, w=1/h
uniform float u_sabre_scale;
uniform float u_inv_zoom;

void main() {
    vec2 mask_uv = (tex_coord - vec2(0.5)) * u_inv_zoom + vec2(0.5);
    float mask = texture(u_highlight_mask, mask_uv).r;
    vec4 center = texture(u_merged_rgb, tex_coord);
    if (mask < 0.005) {
        o_dezippered_rgb = center;
        return;
    }

    float diff_r = center.r - center.g;
    float diff_b = center.b - center.g;
    vec2 sum_diff = vec2(diff_r, diff_b);
    float sum_w = 1.0;

    // 5x5 Chroma Difference Filter scaled by u_sabre_scale and zoom to always cover the full Bayer / fringe period
    float zoom = 1.0 / max(u_inv_zoom, 0.01);
    float step_scale = max(1.0, u_sabre_scale) * max(1.0, zoom);
    vec2 bayer_step = vec2(u_tex_size.z * step_scale, u_tex_size.w * step_scale);

    for (int dy = -2; dy <= 2; ++dy) {
        for (int dx = -2; dx <= 2; ++dx) {
            if (dx == 0 && dy == 0) continue;
            vec2 off = vec2(float(dx) * bayer_step.x, float(dy) * bayer_step.y);
            vec3 samp = texture(u_merged_rgb, tex_coord + off).rgb;

            float spatial_d2 = float(dx * dx + dy * dy);
            float spatial_w = exp(-spatial_d2 * 0.25);

            sum_diff += vec2(samp.r - samp.g, samp.b - samp.g) * spatial_w;
            sum_w += spatial_w;
        }
    }

    vec2 smooth_diff = sum_diff / sum_w;
    // Luminance G is preserved exactly; only R and B chroma phases are smoothed
    vec3 chroma_clean_rgb = vec3(
        max(0.0, center.g + smooth_diff.x),
        center.g,
        max(0.0, center.g + smooth_diff.y)
    );

    // Chromatic Aberration / Purple Fringing Suppression on Highlight Boundaries:
    // Purple / pink fringing occurs where both R > G and B > G.
    float purple_excess = max(0.0, min(chroma_clean_rgb.r - chroma_clean_rgb.g, chroma_clean_rgb.b - chroma_clean_rgb.g));
    chroma_clean_rgb.r -= purple_excess;
    chroma_clean_rgb.b -= purple_excess;

    float blend_factor = clamp(mask, 0.0, 1.0);
    o_dezippered_rgb = vec4(mix(center.rgb, chroma_clean_rgb, blend_factor), center.a);
}
