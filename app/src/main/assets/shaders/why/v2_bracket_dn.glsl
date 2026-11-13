precision highp float; precision highp int; precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Highlights): the reference tone's bracket_denoise, one to one - a 5x5 range / colour-weighted mean of the clean RGB above luma 0.95 (short frames merged), blended by smoothstep(0.95, 1.15, luma) x strength.
uniform sampler2D u_tex_rgb;
uniform int u_width; uniform int u_height;
uniform float u_clip_ceiling;
uniform float u_strength;
out vec4 Output;
void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) { Output = vec4(0.0); return; }
    vec3 c0 = texelFetch(u_tex_rgb, coord, 0).rgb;
    float luma0 = 0.2126 * c0.r + 0.7152 * c0.g + 0.0722 * c0.b;
    if (luma0 <= 0.95) {
        Output = vec4(c0, 1.0);
        return;
    }
    float bracket_mask = smoothstep(0.95, 1.15, luma0);
    float sigma_r = max(0.18 * luma0, 0.20);
    float inv_2_sigma_r2 = 1.0 / (2.0 * sigma_r * sigma_r);
    float sum_w = 0.0;
    vec3 sum_c = vec3(0.0);
    for (int dy = -2; dy <= 2; dy++) {
        int ny = clamp(coord.y + dy, 0, u_height - 1);
        float dist_y2 = float(dy * dy);
        for (int dx = -2; dx <= 2; dx++) {
            int nx = clamp(coord.x + dx, 0, u_width - 1);
            vec3 cn = texelFetch(u_tex_rgb, ivec2(nx, ny), 0).rgb;
            float luman = 0.2126 * cn.r + 0.7152 * cn.g + 0.0722 * cn.b;
            float dist_spatial2 = float(dx * dx) + dist_y2;
            float w_spatial = exp(-dist_spatial2 * 0.20);
            float diff_luma = luman - luma0;
            float w_range = exp(-diff_luma * diff_luma * inv_2_sigma_r2);
            vec3 diff_c = cn - c0;
            float diff_c2 = dot(diff_c, diff_c);
            float w_color = exp(-diff_c2 * inv_2_sigma_r2 * 0.5);
            float w = w_spatial * w_range * w_color;
            sum_w += w;
            sum_c += cn * w;
        }
    }
    vec3 filt_c = sum_c / max(sum_w, 0.0001);
    float blend_weight = bracket_mask * clamp(u_strength, 0.0, 1.0);
    Output = vec4(mix(c0, filt_c, blend_weight), 1.0);
}
