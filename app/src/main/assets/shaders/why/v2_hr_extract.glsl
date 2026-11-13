precision highp float; precision highp int; precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Highlights): the reference tone's hr_extract, one to one - at half the grid, the cube-root chroma of the unclipped pixels between 0.1 and 1 x the threshold (the clean RGB read bilinear), weighted.
uniform sampler2D u_tex_rgb;
uniform int u_width; uniform int u_height;
uniform float u_threshold;
out vec4 Output;

float safe_cbrt(float x) { return (x <= 0.0000001) ? 0.0 : pow(x, 1.0 / 3.0); }

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) { Output = vec4(0.0); return; }
    vec2 uv = (vec2(coord) + 0.5) / vec2(u_width, u_height);
    vec3 rgb = max(texture(u_tex_rgb, uv).rgb, 0.0);
    float max_val = max(max(rgb.r, rgb.g), rgb.b);
    if (max_val > u_threshold || max_val < u_threshold * 0.10) {
        Output = vec4(0.0);
        return;
    }
    float r_cbrt = safe_cbrt(rgb.r);
    float g_cbrt = safe_cbrt(rgb.g);
    float b_cbrt = safe_cbrt(rgb.b);

    float opp_r_cbrt = 0.5 * (g_cbrt + b_cbrt);
    float opp_g_cbrt = 0.5 * (r_cbrt + b_cbrt);
    float opp_b_cbrt = 0.5 * (r_cbrt + g_cbrt);

    float delta_r = max(r_cbrt - opp_r_cbrt, 0.0);
    float delta_g = max(g_cbrt - opp_g_cbrt, 0.0);
    float delta_b = max(b_cbrt - opp_b_cbrt, 0.0);

    float chroma_sum = delta_r + delta_g + delta_b;
    float weight = smoothstep(0.01, 0.06, chroma_sum);
    Output = vec4(delta_r * weight, delta_g * weight, delta_b * weight, weight);
}
