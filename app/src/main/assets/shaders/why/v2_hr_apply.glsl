precision highp float; precision highp int; precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Highlights): the reference tone's hr_apply, one to one - a clipped channel (above 0.85 x the threshold, not all three) rebuilt from the others' cube roots plus the diffused chroma (bilinear), blended in by smoothstep(0.85 t, t, max) x u_hr_amount.
uniform sampler2D u_tex_rgb;
uniform sampler2D u_tex_diffused_hr;
uniform int u_width; uniform int u_height;
uniform float u_threshold;
uniform float u_hr_amount;
out vec4 Output;

float safe_cbrt(float x) { return (x <= 0.0000001) ? 0.0 : pow(x, 1.0 / 3.0); }

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) { Output = vec4(0.0); return; }
    vec3 rgb = max(texelFetch(u_tex_rgb, coord, 0).rgb, 0.0);
    float max_val = max(max(rgb.r, rgb.g), rgb.b);
    if (max_val < u_threshold * 0.85) {
        Output = vec4(rgb, 1.0);
        return;
    }
    bool r_clip = (rgb.r > u_threshold * 0.85);
    bool g_clip = (rgb.g > u_threshold * 0.85);
    bool b_clip = (rgb.b > u_threshold * 0.85);
    if (r_clip && g_clip && b_clip) {
        Output = vec4(rgb, 1.0);
        return;
    }
    vec2 uv = (vec2(coord) + 0.5) / vec2(u_width, u_height);
    vec4 chroma_pack = texture(u_tex_diffused_hr, uv);
    float w = chroma_pack.w;
    if (w < 0.0001) {
        Output = vec4(rgb, 1.0);
        return;
    }
    float delta_r = chroma_pack.x / w;
    float delta_g = chroma_pack.y / w;
    float delta_b = chroma_pack.z / w;

    float r_cbrt = safe_cbrt(rgb.r);
    float g_cbrt = safe_cbrt(rgb.g);
    float b_cbrt = safe_cbrt(rgb.b);

    float opp_r_cbrt = 0.5 * (g_cbrt + b_cbrt);
    float opp_g_cbrt = 0.5 * (r_cbrt + b_cbrt);
    float opp_b_cbrt = 0.5 * (r_cbrt + g_cbrt);

    vec3 rec = rgb;
    if (r_clip) {
        float r_rec_cbrt = max(opp_r_cbrt + delta_r, r_cbrt);
        rec.r = r_rec_cbrt * r_rec_cbrt * r_rec_cbrt;
    }
    if (g_clip) {
        float g_rec_cbrt = max(opp_g_cbrt + delta_g, g_cbrt);
        rec.g = g_rec_cbrt * g_rec_cbrt * g_rec_cbrt;
    }
    if (b_clip) {
        float b_rec_cbrt = max(opp_b_cbrt + delta_b, b_cbrt);
        rec.b = b_rec_cbrt * b_rec_cbrt * b_rec_cbrt;
    }
    float clip_blend = min(smoothstep(u_threshold * 0.85, u_threshold, max_val) * u_hr_amount, 1.0);
    Output = vec4(mix(rgb, rec, clip_blend), 1.0);
}
