precision highp float; precision highp int; precision highp sampler2D;
// P614 «Why тон» V2, the last step (WhyV2Tone): the reference tone's tonemap_final (v2.07), one to one - the 3x3 guided regression of
// the fused display luma on the clean luma and its dark gate, the ACES inversion to the per-pixel gain (with its asymptote cap and
// the dark-scene AE hold in HDR), the OKLab macro contrast, skin mask, vibrance and skin tuning, the max-channel Reinhard shoulder
// above the tonemapper top in HDR, the AgX inset / AgX tonescale (or ACES, Uchimura, the own curve with its contrast boost) / outset,
// the hue lock and the detail transfer in OKLab, the display gamma, the film toe and the spatial dither. Ours in it (WhyV2Tone):
// - u_tex_hires_luma: the reference's luma_extract of the clean RGB (R16F, soft knee 0.001, unclamped: why/sharp_in REF_LUMA) as
//   the reference builds it, sharpened by OUR sharpening (WhyLumaSharpen) in the place of its own; the shader's detail transfer as
//   there (no sharpening: the reference's value exactly);
// - FILM_LUT: our «Плёночный LUT» in the place of the reference's own 3D LUT (off in its configuration);
// - P3_OUT: the graded colour written in Display P3 primaries before the display gamma;
// - EF_SCALED: the fused luma at half the grid (an image above WhyV2Fusion.FULL_LIMIT), read bilinear;
// - QUANT8: the output as the reference's RGBA8 target stores it (round(x 255) / 255), for an 8-bit photo.
// The own curve is the reference's R16F 1024 x 1 table read by texture() (LINEAR). The CIELAB skin helpers of the reference shader
// (get_rt_lch, eval_rt_skinsat) are never called there and are left out.
#define P3_OUT 0
#define FILM_LUT 0
#define EF_SCALED 0
#define QUANT8 0
uniform sampler2D u_tex_clean_rgb;
uniform sampler2D u_tex_accum_ef;
uniform sampler2D u_tex_hires_luma;
uniform sampler2D u_tex_custom_curve;
uniform int u_width; uniform int u_height;
uniform float u_auto_exposure; uniform float u_clip_ceiling; uniform float u_aces_toe;
uniform int u_tonemap_operator;
uniform int u_agx_look; uniform float u_agx_slope; uniform float u_agx_sp; uniform float u_agx_tp;
uniform float u_agx_min_ev; uniform float u_agx_max_ev; uniform float u_agx_sat_boost; uniform float u_agx_ev;
uniform int u_agx_hue_lock;
uniform mat3 u_agx_mat_inset; uniform mat3 u_agx_mat_outset;
uniform float u_aces_pre_gain; uniform float u_aces_a_coeff; uniform float u_aces_d_coeff;
uniform float u_uchi_pre_gain; uniform float u_uchi_post_gain; uniform float u_uchi_p; uniform float u_uchi_a;
uniform float u_uchi_m; uniform float u_uchi_l; uniform float u_uchi_c; uniform float u_uchi_b;
uniform float u_macro_contrast;
uniform float u_contrast_boost;
uniform float u_vibrance_strength; uniform float u_vibrance_saturated; uniform float u_skin_protect_toggle;
uniform float u_vibrance_sky_str; uniform float u_vibrance_green_str; uniform float u_vibrance_hl_str;
uniform float u_vibrance_mid_str; uniform float u_vibrance_shadow_str;
uniform float u_skin_hue_shift; uniform float u_skin_sat_shift;
uniform float u_skin_hue_center; uniform float u_skin_luma_min;
uniform int u_gamma_curve_type; uniform float u_gamma; uniform float u_gamma_inv; uniform float u_film_toe;
uniform float u_hdr_shoulder_strength; uniform float u_hdr_shoulder_knee; uniform float u_hdr_shoulder_headroom;
#if FILM_LUT == 1
precision highp sampler3D;
uniform highp sampler3D FilmLut;
uniform vec2 lutU;                  // x strength 0..1, y LUT size N
#endif
out vec4 Output;

#if P3_OUT == 1
const mat3 SRGB_TO_P3 = mat3(0.8224619687, 0.0331941989, 0.0170826307, 0.1775380313, 0.9668058011, 0.0723974407,
                             0.0000000000, 0.0000000000, 0.9105199286);
#endif

float fusedAt(ivec2 c) {
#if EF_SCALED == 1
    return texture(u_tex_accum_ef, (vec2(c) + 0.5) / vec2(u_width, u_height)).r;
#else
    return texelFetch(u_tex_accum_ef, c, 0).r;
#endif
}


float invert_display_gamma_scalar(float y, int curve_type, float gamma) {
    if (curve_type == 1) { // sRGB inverse (IEC 61966-2-1)
        return (y <= 0.04045) ? (y / 12.92) : pow(max((y + 0.055) / 1.055, 0.0), 2.4);
    } else if (curve_type == 2) { // Rec.709 inverse
        return (y < 0.081) ? (y / 4.5) : pow(max((y + 0.099) / 1.099, 0.0), 1.0 / 0.45);
    } else { // Pure Power inverse
        return pow(max(y, 0.0), gamma);
    }
}

float apply_display_gamma_scalar(float x, int curve_type, float gamma_inv) {
    if (curve_type == 1) { // sRGB IEC 61966-2-1
        return (x <= 0.0031308) ? (12.92 * x) : (1.055 * pow(max(x, 0.000001), 1.0 / 2.4) - 0.055);
    } else if (curve_type == 2) { // Rec.709
        return (x < 0.018) ? (4.5 * x) : (1.099 * pow(max(x, 0.000001), 0.45) - 0.099);
    } else { // Pure Power
        return pow(max(x, 0.000001), gamma_inv);
    }
}

vec3 apply_display_gamma_vec3(vec3 c, int curve_type, float gamma_inv) {
    if (curve_type == 1) { // sRGB IEC 61966-2-1
        vec3 low = c * 12.92;
        vec3 high = 1.055 * pow(max(c, vec3(0.000001)), vec3(1.0 / 2.4)) - 0.055;
        return mix(high, low, step(c, vec3(0.0031308)));
    } else if (curve_type == 2) { // Rec.709
        vec3 low = c * 4.5;
        vec3 high = 1.099 * pow(max(c, vec3(0.000001)), vec3(0.45)) - 0.099;
        return mix(high, low, step(c, vec3(0.018)));
    } else { // Pure Power
        return pow(max(c, vec3(0.000001)), vec3(gamma_inv));
    }
}

float safe_cbrt(float x) {
    return (x <= 0.0000001) ? 0.0 : pow(x, 1.0 / 3.0);
}

float eval_acescg(float v, float toe, float pre_gain, float a_coeff, float d_coeff) {
    float x = max(v, 0.0) * pre_gain;
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    return (x * (a_coeff * x + b)) / (x * (2.43 * x + d_coeff) + e);
}

float uchimura_curve(float x, float P, float a, float m, float l, float c, float b_offset) {
    float l0 = ((P - m) * l) / a;
    float S0 = m + l0;
    float S1 = m + a * l0;
    float C2 = (a * P) / (P - S1);
    float CP = -C2 / P;
    float w0 = 1.0 - smoothstep(0.0, m, x);
    float w2 = step(m + l0, x);
    float w1 = 1.0 - w0 - w2;
    float T = m * pow(max(x / m, 0.0), c) + b_offset;
    float S = P - (P - S1) * exp(CP * (x - S0));
    float L = m + a * (x - m);
    return T * w0 + L * w1 + S * w2;
}

float get_curve_contrast_delta(float x, float boost) {
    const float coeff_c[17] = float[17](
        0.0, 0.349, 0.659, 0.889, 1.000, 0.964, 0.769, 0.431,
        0.0, -0.431, -0.769, -0.964, -1.000, -0.889, -0.659, -0.349, 0.0
    );
    float idx = clamp(x * 16.0, 0.0, 16.0);
    int i0 = clamp(int(floor(idx)), 0, 15);
    float t = idx - float(i0);
    float s = t * t * (3.0 - 2.0 * t);
    float c = mix(coeff_c[i0], coeff_c[i0 + 1], s);
    return -c * (boost * 0.125);
}

float eval_tonemap_scalar(float y, int op) {
    if (op == 2) {
        float x = max(y, 0.0) * u_uchi_pre_gain;
        return uchimura_curve(x, u_uchi_p, u_uchi_a, u_uchi_m, u_uchi_l, u_uchi_c, u_uchi_b) * u_uchi_post_gain;
    } else {
        return eval_acescg(y, u_aces_toe, u_aces_pre_gain, u_aces_a_coeff, u_aces_d_coeff);
    }
}

vec3 agx_inset(vec3 rgb) {
    return max(u_agx_mat_inset * rgb, 0.0);
}

vec3 agx_outset(vec3 rgb) {
    return u_agx_mat_outset * rgb;
}

float agx_tonescale(float x, float slope, float sp, float tp, float px) {
    const float py = 0.50;
    if (x >= px) {
        float s_base = max(slope * (1.0 - px) / (1.0 - py), 0.00001);
        float s_denom = pow(max(slope * (1.0 - px), 0.00001), -sp);
        float ss = pow(max((pow(s_base, sp) - 1.0) * s_denom, 0.00001), -1.0 / sp);
        float ms = slope * (x - px) / max(ss, 0.00001);
        return ss * (ms / pow(1.0 + pow(max(ms, 0.0), sp), 1.0 / sp)) + py;
    } else {
        float t_base = max(slope * px / py, 0.00001);
        float t_denom = pow(max(slope * px, 0.00001), -tp);
        float ts = pow(max((pow(t_base, tp) - 1.0) * t_denom, 0.00001), -1.0 / tp);
        float mr = slope * (px - x) / max(ts, 0.00001);
        return py - ts * (mr / pow(1.0 + pow(max(mr, 0.0), tp), 1.0 / tp));
    }
}

float inverse_acescg(float y, float toe, float pre_gain, float a_coeff, float d_coeff) {
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    float safe_y = clamp(y, 0.0, a_coeff / 2.43 - 0.01);
    float A = 2.43 * safe_y - a_coeff;
    float B = d_coeff * safe_y - b;
    float C = e * safe_y;
    float disc = B * B - 4.0 * A * C;
    if (disc < 0.0) return 0.0;
    return max((-B - sqrt(disc)) / (2.0 * A - 0.000001) / max(pre_gain, 0.001), 0.0);
}

float acescg_toe_default(float v, float toe) {
    return eval_acescg(v, toe, 1.5, 2.51, 0.59);
}

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) { Output = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec3 orig_lin = max(texelFetch(u_tex_clean_rgb, coord, 0).rgb, 0.0);

    // 1. Local 3x3 edge-preserving linear regression (Guided Filter) & shadow noise gate
    float momentX = 0.0, momentY = 0.0, momentX2 = 0.0, momentXY = 0.0, ws = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            ivec2 c = clamp(coord + ivec2(dx, dy), ivec2(0), ivec2(u_width - 1, u_height - 1));
            vec3 rgb_s = max(texelFetch(u_tex_clean_rgb, c, 0).rgb, 0.0) * u_auto_exposure;
            float l_m = max(rgb_s.r * 0.2126 + rgb_s.g * 0.7152 + rgb_s.b * 0.0722, max(max(rgb_s.r, rgb_s.g), rgb_s.b) * 0.5);
            float x = apply_display_gamma_scalar(acescg_toe_default(l_m, u_aces_toe), u_gamma_curve_type, u_gamma_inv);
            float y = fusedAt(c);
            float w = exp(-0.5 * float(dx * dx + dy * dy) / 0.49);
            momentX += x * w;
            momentY += y * w;
            momentX2 += x * x * w;
            momentXY += x * y * w;
            ws += w;
        }
    }
    momentX /= ws;
    momentY /= ws;
    momentX2 /= ws;
    momentXY /= ws;
    float regA = (momentXY - momentX * momentY) / (max(momentX2 - momentX * momentX, 0.0) + 0.00001);
    float regB = momentY - regA * momentX;

    vec3 rgb_center = orig_lin * u_auto_exposure;
    float l_center = max(rgb_center.r * 0.2126 + rgb_center.g * 0.7152 + rgb_center.b * 0.0722, max(max(rgb_center.r, rgb_center.g), rgb_center.b) * 0.5);
    float center_luma = apply_display_gamma_scalar(acescg_toe_default(l_center, u_aces_toe), u_gamma_curve_type, u_gamma_inv);
    float target_lores_fused = max(regA * center_luma + regB, 0.0);

    const float kDarkThreshold = 0.007;
    if (center_luma < kDarkThreshold) {
        float t_fade = center_luma / kDarkThreshold;
        target_lores_fused = mix(center_luma, target_lores_fused, t_fade * t_fade);
    }

    // 2. ACES INVERSION & BASE TONEMAP
    float target_aces = invert_display_gamma_scalar(target_lores_fused, u_gamma_curve_type, u_gamma);
    float target_lin_luma = inverse_acescg(target_aces, u_aces_toe, 1.5, 2.51, 0.59);
    
    // Prevent inverse_acescg asymptote explosion
    if (u_clip_ceiling > 1.05) {
        float max_lin = 0.18 * exp2(u_agx_max_ev + max(u_agx_ev, 0.0));
        if (u_tonemap_operator == 1) max_lin = 3.0; // ACES
        else if (u_tonemap_operator == 2 || u_tonemap_operator == 3) max_lin = 4.0; // Uchimura / Custom Curve
        
        // We cap target_lin_luma safely at the tonemapper ceiling to prevent inverse_acescg asymptote explosion
        float max_target = max(max_lin, 1.0);
        float knee = max_target * 0.7;
        
        if (target_lin_luma > knee) {
            float excess = target_lin_luma - knee;
            float H = max_target - knee;
            target_lin_luma = knee + (excess * H) / (excess + H);
        }
    }

    float y_709 = orig_lin.r * 0.2126 + orig_lin.g * 0.7152 + orig_lin.b * 0.0722;
    float max_ch = max(max(orig_lin.r, orig_lin.g), orig_lin.b);
    float orig_lin_luma = max(max(y_709, max_ch * 0.5), 0.0001);

    // In HDR mode (u_clip_ceiling > 1.05), prevent dark-scene auto-exposure boost (> 1.0)
    // from blowing out recovered short-frame highlights into the tonemapper ceiling:
    float effective_ae = u_auto_exposure;
    if (u_clip_ceiling > 1.05 && u_auto_exposure > 1.0) {
        float hl_weight = smoothstep(0.70, 1.20, orig_lin_luma);
        effective_ae = mix(u_auto_exposure, 1.0, hl_weight);
    }

    float base_multiplier = clamp(target_lin_luma / max(orig_lin_luma * effective_ae, 0.0001), 0.05, 12.0);
    vec3 final_lin = orig_lin * effective_ae * base_multiplier;

    // 3. High-Res Luma Detail Transfer
    float tOriginal_luma = max(texelFetch(u_tex_hires_luma, coord, 0).r, 0.000001);
    float unsharpened_luma = orig_lin.r * 0.2126 + orig_lin.g * 0.7152 + orig_lin.b * 0.0722;
    float L_a3 = safe_cbrt(tOriginal_luma);
    float L_base = safe_cbrt(unsharpened_luma);
    float luma_delta = L_a3 - L_base;
    float max_trench = min(0.035, L_base * 0.25);
    luma_delta = clamp(luma_delta, -max_trench, 0.050);

    // 4. Oklab Conversion & Vibrance
    float scene_luma = orig_lin_luma * u_auto_exposure;
    float L = 0.4122214708 * final_lin.r + 0.5363325363 * final_lin.g + 0.0514459929 * final_lin.b;
    float M = 0.2119034982 * final_lin.r + 0.6806995451 * final_lin.g + 0.1073969566 * final_lin.b;
    float S = 0.0883024619 * final_lin.r + 0.2817188376 * final_lin.g + 0.6299787005 * final_lin.b;

    float l_ = safe_cbrt(max(L, 0.0));
    float m_ = safe_cbrt(max(M, 0.0));
    float s_ = safe_cbrt(max(S, 0.0));

    float oklab_L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
    float oklab_a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
    float oklab_b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;

    if (abs(u_macro_contrast - 1.0) > 0.001) {
        float pivot_L = 0.40;
        oklab_L = max(pivot_L + (oklab_L - pivot_L) * u_macro_contrast, 0.0001);
    }

    // 4.1. Smooth 3D Oklab Skin Mask (Gaussian Hue + Soft Lightness/Chroma envelope)
    float cur_sat = max(sqrt(oklab_a * oklab_a + oklab_b * oklab_b), 0.0001);
    float skin_theta = atan(oklab_b, oklab_a);
    float d_theta = skin_theta - u_skin_hue_center;
    float w_theta = exp(-0.5 * (d_theta * d_theta) / (0.28 * 0.28));
    float w_chroma = smoothstep(0.015, 0.040, cur_sat) * (1.0 - smoothstep(0.18, 0.28, cur_sat));
    float w_luma = smoothstep(u_skin_luma_min - 0.06, u_skin_luma_min + 0.06, oklab_L) * (1.0 - smoothstep(0.88, 0.98, oklab_L));
    float skin_weight = clamp(w_theta * w_chroma * w_luma, 0.0, 1.0);

    // 4.2. Vibrance boost calculation in Oklab
    float sat_t = smoothstep(0.02, 0.09, cur_sat);
    float total_boost = mix(u_vibrance_strength, u_vibrance_saturated, sat_t);

    if (abs(u_vibrance_sky_str) > 0.001 && oklab_b < 0.0) {
        float sky_mask = clamp(-oklab_b / cur_sat, 0.0, 1.0);
        total_boost += u_vibrance_sky_str * sky_mask;
    }
    if (abs(u_vibrance_green_str) > 0.001 && oklab_a < 0.0) {
        float green_mask = clamp(-oklab_a / cur_sat, 0.0, 1.0);
        total_boost += u_vibrance_green_str * green_mask;
    }

    if (abs(u_vibrance_hl_str) > 0.001) {
        float hl_mask = smoothstep(0.60, 0.80, oklab_L) * (1.0 - smoothstep(0.88, 0.98, oklab_L));
        total_boost += u_vibrance_hl_str * hl_mask;
    }
    if (abs(u_vibrance_mid_str) > 0.001) {
        float mid_mask = smoothstep(0.15, 0.35, oklab_L) * (1.0 - smoothstep(0.55, 0.75, oklab_L));
        total_boost += u_vibrance_mid_str * mid_mask;
    }
    if (abs(u_vibrance_shadow_str) > 0.001) {
        float shadow_mask = 1.0 - smoothstep(0.08, 0.25, oklab_L);
        total_boost += u_vibrance_shadow_str * shadow_mask;
    }

    // 4.3. Apply skin protection
    float skin_protect_factor = 1.0 - 0.70 * skin_weight;
    float skin_mult = mix(1.0, skin_protect_factor, u_skin_protect_toggle);
    float final_sat_mult = max(0.0, 1.0 + total_boost * skin_mult);

    oklab_a *= final_sat_mult;
    oklab_b *= final_sat_mult;

    // 4.4. Skin Tone Tuning (Hue & Saturation)
    if (skin_weight > 0.001) {
        if (abs(u_skin_hue_shift) > 0.001) {
            float rad = u_skin_hue_shift * 0.0174532925 * skin_weight;
            float cos_h = cos(rad);
            float sin_h = sin(rad);
            float new_a = oklab_a * cos_h - oklab_b * sin_h;
            float new_b = oklab_a * sin_h + oklab_b * cos_h;
            oklab_a = new_a;
            oklab_b = new_b;
        }
        if (abs(u_skin_sat_shift) > 0.001) {
            float skin_sat_mult = max(0.0, 1.0 + u_skin_sat_shift * skin_weight);
            oklab_a *= skin_sat_mult;
            oklab_b *= skin_sat_mult;
        }
    }

    float in_chroma = sqrt(oklab_a * oklab_a + oklab_b * oklab_b);
    vec2 in_hue_dir = (in_chroma > 0.000001) ? (vec2(oklab_a, oklab_b) / in_chroma) : vec2(0.0);

    l_ = max(0.0, oklab_L + 0.3963377774 * oklab_a + 0.2158037573 * oklab_b);
    m_ = max(0.0, oklab_L - 0.1055613458 * oklab_a - 0.0638541728 * oklab_b);
    s_ = max(0.0, oklab_L - 0.0894841775 * oklab_a - 1.2914855480 * oklab_b);
    float l3 = l_*l_*l_; float m3 = m_*m_*m_; float s3 = s_*s_*s_;
    vec3 rgb_scene = vec3(
        max(+4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3, 0.0),
        max(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3, 0.0),
        max(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3, 0.0)
    );

    // 5. AgX / Alternative Tonemapping
    // When HDR Short Frames are fused (u_clip_ceiling > 1.05), compress extreme RAW highlights
    // using RAWBracket's max-channel Reinhard shoulder to guarantee that details stay inside
    // the tonemapper curve (below AgX max EV ceiling):
    if (u_clip_ceiling > 1.05 && u_hdr_shoulder_strength > 0.001) {
        float peak_val = max(rgb_scene.r, max(rgb_scene.g, rgb_scene.b));
        float dyn_max_ev = u_agx_max_ev + max(u_agx_ev, 0.0);
        float agx_top_lin = (u_tonemap_operator == 0) ? (0.18 * exp2(dyn_max_ev)) : 4.0;
        float soft_shoulder_knee = agx_top_lin * u_hdr_shoulder_knee;
        if (peak_val > soft_shoulder_knee) {
            float excess = peak_val - soft_shoulder_knee;
            float max_headroom = agx_top_lin * u_hdr_shoulder_headroom;
            float compressed_peak = soft_shoulder_knee + (excess * max_headroom) / (excess + max_headroom);
            float scale = mix(1.0, compressed_peak / peak_val, u_hdr_shoulder_strength);
            rgb_scene *= scale;
        }
    }

    vec3 rgb_inset_val = agx_inset(rgb_scene);
    vec3 graded_lin = vec3(0.0);

    if (u_tonemap_operator == 0) {
        float eff_slope = u_agx_slope;
        float eff_sp = u_agx_sp;
        float eff_tp = u_agx_tp;
        float eff_min_ev = u_agx_min_ev;
        float eff_max_ev = u_agx_max_ev;
        float eff_sat = u_agx_sat_boost;

        float eff_max_ev_dyn = eff_max_ev + max(u_agx_ev, 0.0);
        float ev_range = max(eff_max_ev_dyn - eff_min_ev, 1.0);
        float px = clamp(-eff_min_ev / ev_range, 0.05, 0.95);
        vec3 log_rgb = clamp((log2(max(rgb_inset_val, 0.0000001) / 0.18) + u_agx_ev - eff_min_ev) / ev_range, 0.0, 1.0);
        vec3 cur_rgb = vec3(
            agx_tonescale(log_rgb.r, eff_slope, eff_sp, eff_tp, px),
            agx_tonescale(log_rgb.g, eff_slope, eff_sp, eff_tp, px),
            agx_tonescale(log_rgb.b, eff_slope, eff_sp, eff_tp, px)
        );
        if (u_agx_look == 3) {
            cur_rgb.r = pow(max(cur_rgb.r, 0.0), 0.96);
            cur_rgb.b = pow(max(cur_rgb.b, 0.0), 1.04);
        }
        vec3 lin_pass = pow(max(cur_rgb, 0.0), vec3(2.4));
        vec3 rgb_out = agx_outset(lin_pass);
        if (abs(eff_sat - 1.0) > 0.001) {
            float out_luma = dot(rgb_out, vec3(0.2126, 0.7152, 0.0722));
            rgb_out = vec3(out_luma) + (rgb_out - vec3(out_luma)) * eff_sat;
        }
        graded_lin = clamp(rgb_out, 0.0, 1.0);
    } else if (u_tonemap_operator == 3) {
        float ev_half = 5.0;
        vec3 ev_val = log2(max(rgb_inset_val, 0.0000001) / 0.18);
        vec3 norm_x = clamp(vec3(0.5) + ev_val * (0.5 / ev_half), 0.0, 1.0);
        vec3 cur_rgb = vec3(
            texture(u_tex_custom_curve, vec2(norm_x.r, 0.5)).r,
            texture(u_tex_custom_curve, vec2(norm_x.g, 0.5)).r,
            texture(u_tex_custom_curve, vec2(norm_x.b, 0.5)).r
        );
        if (abs(u_contrast_boost) > 0.0001) {
            cur_rgb.r = clamp(cur_rgb.r + get_curve_contrast_delta(norm_x.r, u_contrast_boost), 0.0, 1.0);
            cur_rgb.g = clamp(cur_rgb.g + get_curve_contrast_delta(norm_x.g, u_contrast_boost), 0.0, 1.0);
            cur_rgb.b = clamp(cur_rgb.b + get_curve_contrast_delta(norm_x.b, u_contrast_boost), 0.0, 1.0);
        }
        vec3 lin_pass = pow(max(cur_rgb, 0.0), vec3(2.4));
        vec3 rgb_out = agx_outset(lin_pass);
        graded_lin = clamp(rgb_out, 0.0, 1.0);
    } else {
        vec3 cur_rgb = vec3(
            eval_tonemap_scalar(rgb_inset_val.r, u_tonemap_operator),
            eval_tonemap_scalar(rgb_inset_val.g, u_tonemap_operator),
            eval_tonemap_scalar(rgb_inset_val.b, u_tonemap_operator)
        );
        vec3 rgb_out = agx_outset(clamp(cur_rgb, 0.0, 1.0));
        graded_lin = clamp(rgb_out, 0.0, 1.0);
    }

    // 5.5 Post-Tonemap Detail Reconstruction & Hue Lock
    float post_L = dot(graded_lin, vec3(0.4122214708, 0.5363325363, 0.0514459929));
    float post_M = dot(graded_lin, vec3(0.2119034982, 0.6806995451, 0.1073969566));
    float post_S = dot(graded_lin, vec3(0.0883024619, 0.2817188376, 0.6299787005));

    float p_l = safe_cbrt(max(post_L, 0.0));
    float p_m = safe_cbrt(max(post_M, 0.0));
    float p_s = safe_cbrt(max(post_S, 0.0));

    float post_oklab_L = 0.2104542553 * p_l + 0.7936177850 * p_m - 0.0040720468 * p_s;
    float post_oklab_a = 1.9779984951 * p_l - 2.4285922050 * p_m + 0.4505937099 * p_s;
    float post_oklab_b = 0.0259040371 * p_l + 0.7827717662 * p_m - 0.8086757660 * p_s;

    if (u_agx_hue_lock != 0 && !(u_tonemap_operator == 0 && u_agx_look == 3) && in_chroma > 0.000001) {
        float blue_weight = clamp(-in_hue_dir.y, 0.0, 1.0);
        float agx_chroma = sqrt(post_oklab_a * post_oklab_a + post_oklab_b * post_oklab_b);
        post_oklab_a = mix(post_oklab_a, agx_chroma * in_hue_dir.x, blue_weight);
        post_oklab_b = mix(post_oklab_b, agx_chroma * in_hue_dir.y, blue_weight);
    }

    float hl_fade = 1.0 - smoothstep(0.75, 1.0, post_oklab_L) * 0.50;
    post_oklab_L = clamp(post_oklab_L + luma_delta * hl_fade, 0.0001, 1.0);

    p_l = max(0.0, post_oklab_L + 0.3963377774 * post_oklab_a + 0.2158037573 * post_oklab_b);
    p_m = max(0.0, post_oklab_L - 0.1055613458 * post_oklab_a - 0.0638541728 * post_oklab_b);
    p_s = max(0.0, post_oklab_L - 0.0894841775 * post_oklab_a - 1.2914855480 * post_oklab_b);
    float p_l3 = p_l * p_l * p_l;
    float p_m3 = p_m * p_m * p_m;
    float p_s3 = p_s * p_s * p_s;
    vec3 final_graded = vec3(
        clamp(+4.0767416621 * p_l3 - 3.3077115913 * p_m3 + 0.2309699292 * p_s3, 0.0, 1.0),
        clamp(-1.2684380046 * p_l3 + 2.6097574011 * p_m3 - 0.3413193965 * p_s3, 0.0, 1.0),
        clamp(-0.0041960863 * p_l3 - 0.7034186147 * p_m3 + 1.7076147010 * p_s3, 0.0, 1.0)
    );

    // 6. Display Gamma & Film Toe
#if P3_OUT == 1
    // «Цветовое пространство» Display P3: the same colours written in P3 primaries (no wider gamut)
    final_graded = clamp(SRGB_TO_P3 * final_graded, 0.0, 1.0);
#endif
    vec3 out_sdr = apply_display_gamma_vec3(final_graded, u_gamma_curve_type, u_gamma_inv);

    if (u_film_toe > 0.0) {
        float luma_sdr = max(dot(out_sdr, vec3(0.2126, 0.7152, 0.0722)), 0.0001);
        float toe_limit = 0.15;
        if (luma_sdr < toe_limit) {
            float norm_luma = luma_sdr / toe_limit;
            float crushed = pow(max(norm_luma, 0.000001), 1.0 + u_film_toe);
            float toe_ratio = (crushed * toe_limit) / luma_sdr;
            out_sdr *= toe_ratio;
        }
    }

#if FILM_LUT == 1
    {
        // the 3D LUT's slot of the reference tone with our «Плёночный LUT» in it, sampled as the reference samples its own: the
        // display value itself as the coordinate (no domain, no texel-centre mapping), trilinear, then mixed by the strength
        vec3 l = texture(FilmLut, out_sdr).rgb;
        out_sdr = mix(out_sdr, l, lutU.x);
    }
#endif

    // Spatial Dithering
    float d1 = fract(0.06711056 * float(coord.x) + 0.00583715 * float(coord.y));
    float dither = (fract(52.9829189 * d1) - 0.5) / 255.0;
#if QUANT8 == 1
    // the reference renders into an RGBA8 texture: the stored value (the GL conversion rounds x 255 to the nearest code)
    Output = vec4(floor(clamp(out_sdr + dither, 0.0, 1.0) * 255.0 + 0.5) / 255.0, 1.0);
#else
    Output = vec4(clamp(out_sdr + dither, 0.0, 1.0), 1.0);
#endif
}
