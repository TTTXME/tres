precision highp float; precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Fusion): the reference tone's ef_pyr_prep, one to one - four synthetic exposures of the clean RGB x AE,
// each metered as max(Y709, 0.5 max channel), through the ACEScg toe and the display gamma (o_G), and their Gaussian weights
// around the centre, x the layer weights, normalised (o_W). The reference writes both at once (two colour attachments); here
// MODE 0 writes o_G and MODE 1 o_W, the same expressions. SCALE 2 (an image above WhyV2Fusion.FULL_LIMIT): level 0 at half the
// grid, the clean RGB as its 2x2 mean.
#define MODE 0
#define SCALE 1
uniform sampler2D u_tex_clean_rgb;
uniform int u_width;
uniform int u_height;
uniform vec4 u_exp_multipliers;
uniform vec4 u_layer_weights;
uniform float u_weight_center;
uniform float u_auto_exposure;
uniform float u_blend_smoothness;
uniform int u_gamma_curve_type;
uniform float u_gamma_inv;
uniform float u_aces_toe;
out vec4 Output;

float apply_display_gamma_scalar(float x, int curve_type, float gamma_inv) {
    if (curve_type == 1) { // sRGB IEC 61966-2-1
        return (x <= 0.0031308) ? (12.92 * x) : (1.055 * pow(max(x, 0.000001), 1.0 / 2.4) - 0.055);
    } else if (curve_type == 2) { // Rec.709
        return (x < 0.018) ? (4.5 * x) : (1.099 * pow(max(x, 0.000001), 0.45) - 0.099);
    } else { // Pure Power
        return pow(max(x, 0.000001), gamma_inv);
    }
}

float acescg_toe(float v, float toe) {
    float x = max(v, 0.0) * 1.5;
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e);
}

vec3 cleanAt(ivec2 coord) {
#if SCALE == 2
    ivec2 last = textureSize(u_tex_clean_rgb, 0) - ivec2(1);
    ivec2 o = coord * 2;
    return 0.25 * (max(texelFetch(u_tex_clean_rgb, min(o, last), 0).rgb, 0.0)
                 + max(texelFetch(u_tex_clean_rgb, min(o + ivec2(1, 0), last), 0).rgb, 0.0)
                 + max(texelFetch(u_tex_clean_rgb, min(o + ivec2(0, 1), last), 0).rgb, 0.0)
                 + max(texelFetch(u_tex_clean_rgb, min(o + ivec2(1, 1), last), 0).rgb, 0.0));
#else
    return max(texelFetch(u_tex_clean_rgb, coord, 0).rgb, 0.0);
#endif
}

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) {
        Output = vec4(0.0);
        return;
    }
    vec3 rgb = cleanAt(coord) * u_auto_exposure;

    vec4 r_exps = rgb.r * u_exp_multipliers;
    vec4 g_exps = rgb.g * u_exp_multipliers;
    vec4 b_exps = rgb.b * u_exp_multipliers;
    vec4 l_meter = max(r_exps * 0.2126 + g_exps * 0.7152 + b_exps * 0.0722, max(max(r_exps, g_exps), b_exps) * 0.5);

    vec4 lumas;
    lumas.x = apply_display_gamma_scalar(acescg_toe(l_meter.x, u_aces_toe), u_gamma_curve_type, u_gamma_inv);
    lumas.y = apply_display_gamma_scalar(acescg_toe(l_meter.y, u_aces_toe), u_gamma_curve_type, u_gamma_inv);
    lumas.z = apply_display_gamma_scalar(acescg_toe(l_meter.z, u_aces_toe), u_gamma_curve_type, u_gamma_inv);
    lumas.w = apply_display_gamma_scalar(acescg_toe(l_meter.w, u_aces_toe), u_gamma_curve_type, u_gamma_inv);

#if MODE == 0
    Output = lumas;
#else
    vec4 diff_luma = lumas - vec4(u_weight_center);
    vec4 vars = vec4(max(u_blend_smoothness * u_blend_smoothness, 0.001));
    vec4 w_luma = exp(-0.5 * (diff_luma * diff_luma) / vars) * u_layer_weights + 0.000001;
    w_luma /= (w_luma.x + w_luma.y + w_luma.z + w_luma.w);
    Output = w_luma;
#endif
}
