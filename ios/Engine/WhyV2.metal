// «Why тон» V2 on Metal: app/src/main/assets/shaders/why/v2_*.glsl one to one (same expressions and constants).
// Built with MTL_FAST_MATH = NO, so pow/exp/log keep their full precision as GLSL highp.
#include <metal_stdlib>
#include "ShaderTypes.h"
using namespace metal;

constexpr sampler linearClamp(coord::normalized, address::clamp_to_edge, filter::linear);

static inline bool outside(uint2 gid, int w, int h) { return int(gid.x) >= w || int(gid.y) >= h; }

// why/v2_clean.glsl: max(0, M2 (M1 (rgb neutral lsc scale))); the lens shading is already in the input on iOS (lsc = 1).
kernel void why_v2_clean(texture2d<float, access::read> input [[texture(0)]],
                         texture2d<float, access::write> output [[texture(1)]],
                         constant WhyV2CleanParams &p [[buffer(0)]],
                         uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) return;
    float3 c = input.read(gid).rgb;
    c = float3(isnan(c.r) || isinf(c.r) ? 0.0 : c.r, isnan(c.g) || isinf(c.g) ? 0.0 : c.g, isnan(c.b) || isinf(c.b) ? 0.0 : c.b);
    float3 neutral = p.neutral.r + p.neutral.g + p.neutral.b > 0.0 ? p.neutral : float3(1.0);
    float scale = p.scale > 0.0 ? p.scale : 1.0;
    float3 v = p.intermediateToSRGB * (p.sensorToIntermediate * (c * neutral * scale));
    output.write(float4(max(v, float3(0.0)), 1.0), gid);
}

// why/v2_stride.glsl: the histogram samples (every stride-th texel), into a buffer for the CPU histograms.
kernel void why_v2_stride(texture2d<float, access::read> clean [[texture(0)]],
                          device float4 *samples [[buffer(0)]],
                          constant int2 &dims [[buffer(1)]],
                          constant int &stride [[buffer(2)]],
                          uint2 gid [[thread_position_in_grid]]) {
    if (int(gid.x) >= dims.x || int(gid.y) >= dims.y) return;
    uint2 c = gid * uint(max(stride, 1));
    samples[gid.y * uint(dims.x) + gid.x] = float4(clean.read(c).rgb, 1.0);
}

// why/sharp_in.glsl REF_LUMA 1: the reference's luma_extract of the clean RGB (soft knee 0.001, unclamped).
kernel void why_v2_luma(texture2d<float, access::read> clean [[texture(0)]],
                        texture2d<float, access::write> output [[texture(1)]],
                        uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) return;
    float3 rgb = clean.read(gid).rgb;
    float luma = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
    float eps = max(0.0001, 0.001);
    float safe_luma;
    if (luma >= eps) { safe_luma = luma; }
    else if (luma > -eps) { safe_luma = (luma + eps) * (luma + eps) / (4.0 * eps); }
    else { safe_luma = 0.0; }
    output.write(float4(max(safe_luma, 0.0), 0.0, 0.0, 1.0), gid);
}

static float apply_display_gamma_scalar(float x, int curve_type, float gamma_inv) {
    if (curve_type == 1) {
        return (x <= 0.0031308) ? (12.92 * x) : (1.055 * pow(max(x, 0.000001), 1.0 / 2.4) - 0.055);
    } else if (curve_type == 2) {
        return (x < 0.018) ? (4.5 * x) : (1.099 * pow(max(x, 0.000001), 0.45) - 0.099);
    } else {
        return pow(max(x, 0.000001), gamma_inv);
    }
}

static float acescg_toe(float v, float toe) {
    float x = max(v, 0.0) * 1.5;
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e);
}

// why/v2_ef_prep.glsl, MODE 0 (o_G) and MODE 1 (o_W) written together.
kernel void why_v2_ef_prep(texture2d<float, access::read> clean [[texture(0)]],
                           texture2d<float, access::write> outG [[texture(1)]],
                           texture2d<float, access::write> outW [[texture(2)]],
                           constant WhyV2PrepParams &p [[buffer(0)]],
                           uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= outG.get_width() || gid.y >= outG.get_height()) return;
    if (outside(gid, p.width, p.height)) {
        outG.write(float4(0.0), gid);
        outW.write(float4(0.0), gid);
        return;
    }
    int2 coord = int2(gid);
    float3 c;
    if (p.scale == 2) {
        int2 last = int2(clean.get_width(), clean.get_height()) - int2(1);
        int2 o = coord * 2;
        c = 0.25 * (max(clean.read(uint2(min(o, last))).rgb, float3(0.0))
                  + max(clean.read(uint2(min(o + int2(1, 0), last))).rgb, float3(0.0))
                  + max(clean.read(uint2(min(o + int2(0, 1), last))).rgb, float3(0.0))
                  + max(clean.read(uint2(min(o + int2(1, 1), last))).rgb, float3(0.0)));
    } else {
        c = max(clean.read(gid).rgb, float3(0.0));
    }
    float3 rgb = c * p.autoExposure;
    float4 r_exps = rgb.r * p.expMultipliers;
    float4 g_exps = rgb.g * p.expMultipliers;
    float4 b_exps = rgb.b * p.expMultipliers;
    float4 l_meter = max(r_exps * 0.2126 + g_exps * 0.7152 + b_exps * 0.0722, max(max(r_exps, g_exps), b_exps) * 0.5);
    float4 lumas;
    lumas.x = apply_display_gamma_scalar(acescg_toe(l_meter.x, p.acesToe), p.gammaCurveType, p.gammaInv);
    lumas.y = apply_display_gamma_scalar(acescg_toe(l_meter.y, p.acesToe), p.gammaCurveType, p.gammaInv);
    lumas.z = apply_display_gamma_scalar(acescg_toe(l_meter.z, p.acesToe), p.gammaCurveType, p.gammaInv);
    lumas.w = apply_display_gamma_scalar(acescg_toe(l_meter.w, p.acesToe), p.gammaCurveType, p.gammaInv);
    outG.write(lumas, gid);
    float4 diff_luma = lumas - float4(p.weightCenter);
    float4 vars = float4(max(p.blendSmoothness * p.blendSmoothness, 0.001));
    float4 w_luma = exp(-0.5 * (diff_luma * diff_luma) / vars) * p.layerWeights + 0.000001;
    w_luma /= (w_luma.x + w_luma.y + w_luma.z + w_luma.w);
    outW.write(w_luma, gid);
}

// why/v2_pyr_down.glsl
kernel void why_v2_pyr_down(texture2d<float, access::sample> src [[texture(0)]],
                            texture2d<float, access::write> dst [[texture(1)]],
                            constant WhyV2LevelParams &p [[buffer(0)]],
                            uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
    if (outside(gid, p.width, p.height)) { dst.write(float4(0.0), gid); return; }
    float2 uv = (float2(gid) + 0.5) / float2(p.width, p.height);
    float2 half_t = p.texelSize * 0.5;
    float2 full_t = p.texelSize;
    float4 c = src.sample(linearClamp, uv);
    float4 d0 = src.sample(linearClamp, uv + float2(-full_t.x, -full_t.y));
    float4 d1 = src.sample(linearClamp, uv + float2( full_t.x, -full_t.y));
    float4 d2 = src.sample(linearClamp, uv + float2(-full_t.x,  full_t.y));
    float4 d3 = src.sample(linearClamp, uv + float2( full_t.x,  full_t.y));
    float4 t0 = src.sample(linearClamp, uv + float2(-half_t.x, 0.0));
    float4 t1 = src.sample(linearClamp, uv + float2( half_t.x, 0.0));
    float4 t2 = src.sample(linearClamp, uv + float2(0.0, -half_t.y));
    float4 t3 = src.sample(linearClamp, uv + float2(0.0,  half_t.y));
    dst.write(c * 0.25 + (d0 + d1 + d2 + d3) * 0.0625 + (t0 + t1 + t2 + t3) * 0.125, gid);
}

// why/v2_ef_coarsest.glsl
kernel void why_v2_ef_coarsest(texture2d<float, access::read> g [[texture(0)]],
                               texture2d<float, access::read> w [[texture(1)]],
                               texture2d<float, access::write> fused [[texture(2)]],
                               constant WhyV2LevelParams &p [[buffer(0)]],
                               uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= fused.get_width() || gid.y >= fused.get_height()) return;
    if (outside(gid, p.width, p.height)) { fused.write(float4(0.0), gid); return; }
    float f = dot(g.read(gid), w.read(gid));
    fused.write(float4(f, f, f, 1.0), gid);
}

// why/v2_ef_collapse.glsl
kernel void why_v2_ef_collapse(texture2d<float, access::read> gFine [[texture(0)]],
                               texture2d<float, access::sample> gCoarse [[texture(1)]],
                               texture2d<float, access::read> wFine [[texture(2)]],
                               texture2d<float, access::sample> fusedCoarse [[texture(3)]],
                               texture2d<float, access::write> fused [[texture(4)]],
                               constant WhyV2LevelParams &p [[buffer(0)]],
                               uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= fused.get_width() || gid.y >= fused.get_height()) return;
    if (outside(gid, p.width, p.height)) { fused.write(float4(0.0), gid); return; }
    float2 uv = (float2(gid) + 0.5) / float2(p.width, p.height);
    float4 g_fine = gFine.read(gid);
    float4 w_fine = wFine.read(gid);
    float4 g_coarse = gCoarse.sample(linearClamp, uv);
    float fused_coarse = fusedCoarse.sample(linearClamp, uv).r;
    float4 laplace = g_fine - g_coarse;
    float c_blend = clamp(p.clarity, 0.0, 1.0);
    float4 c_mod = mix(float4(1.0), abs(laplace) + 0.00001, float4(c_blend));
    float4 eff_weights = w_fine * c_mod;
    eff_weights /= (dot(eff_weights, float4(1.0)) + 0.00001);
    float detail = dot(eff_weights, laplace);
    if (p.clarity > 1.0) {
        detail *= (1.0 + (p.clarity - 1.0));
    } else if (p.clarity < 0.0) {
        detail *= max(1.0 + p.clarity, 0.0);
    }
    float f = fused_coarse + detail;
    fused.write(float4(f, f, f, 1.0), gid);
}

// ---- why/v2_tonemap.glsl ----

static float invert_display_gamma_scalar(float y, int curve_type, float gamma) {
    if (curve_type == 1) {
        return (y <= 0.04045) ? (y / 12.92) : pow(max((y + 0.055) / 1.055, 0.0), 2.4);
    } else if (curve_type == 2) {
        return (y < 0.081) ? (y / 4.5) : pow(max((y + 0.099) / 1.099, 0.0), 1.0 / 0.45);
    } else {
        return pow(max(y, 0.0), gamma);
    }
}

static float3 apply_display_gamma_vec3(float3 c, int curve_type, float gamma_inv) {
    if (curve_type == 1) {
        float3 low = c * 12.92;
        float3 high = 1.055 * pow(max(c, float3(0.000001)), float3(1.0 / 2.4)) - 0.055;
        return mix(high, low, step(c, float3(0.0031308)));
    } else if (curve_type == 2) {
        float3 low = c * 4.5;
        float3 high = 1.099 * pow(max(c, float3(0.000001)), float3(0.45)) - 0.099;
        return mix(high, low, step(c, float3(0.018)));
    } else {
        return pow(max(c, float3(0.000001)), float3(gamma_inv));
    }
}

static float safe_cbrt(float x) {
    return (x <= 0.0000001) ? 0.0 : pow(x, 1.0 / 3.0);
}

static float eval_acescg(float v, float toe, float pre_gain, float a_coeff, float d_coeff) {
    float x = max(v, 0.0) * pre_gain;
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    return (x * (a_coeff * x + b)) / (x * (2.43 * x + d_coeff) + e);
}

static float uchimura_curve(float x, float P, float a, float m, float l, float c, float b_offset) {
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

constant float coeff_c[17] = {
    0.0, 0.349, 0.659, 0.889, 1.000, 0.964, 0.769, 0.431,
    0.0, -0.431, -0.769, -0.964, -1.000, -0.889, -0.659, -0.349, 0.0
};

static float get_curve_contrast_delta(float x, float boost) {
    float idx = clamp(x * 16.0, 0.0, 16.0);
    int i0 = clamp(int(floor(idx)), 0, 15);
    float t = idx - float(i0);
    float s = t * t * (3.0 - 2.0 * t);
    float c = mix(coeff_c[i0], coeff_c[i0 + 1], s);
    return -c * (boost * 0.125);
}

static float eval_tonemap_scalar(float y, int op, constant WhyV2ToneParams &p) {
    if (op == 2) {
        float x = max(y, 0.0) * p.uchiPreGain;
        return uchimura_curve(x, p.uchiP, p.uchiA, p.uchiM, p.uchiL, p.uchiC, p.uchiB) * p.uchiPostGain;
    } else {
        return eval_acescg(y, p.acesToe, p.acesPreGain, p.acesA, p.acesD);
    }
}

static float agx_tonescale(float x, float slope, float sp, float tp, float px) {
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

static float inverse_acescg(float y, float toe, float pre_gain, float a_coeff, float d_coeff) {
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

static float acescg_toe_default(float v, float toe) {
    return eval_acescg(v, toe, 1.5, 2.51, 0.59);
}

kernel void why_v2_tonemap(texture2d<float, access::read> cleanRgb [[texture(0)]],
                           texture2d<float, access::sample> accumEf [[texture(1)]],
                           texture2d<float, access::read> hiresLuma [[texture(2)]],
                           texture2d<float, access::sample> customCurve [[texture(3)]],
                           texture2d<float, access::write> output [[texture(4)]],
                           constant WhyV2ToneParams &p [[buffer(0)]],
                           uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) return;
    int2 coord = int2(gid);
    if (coord.x >= p.width || coord.y >= p.height) { output.write(float4(0.0, 0.0, 0.0, 1.0), gid); return; }
    float3 orig_lin = max(cleanRgb.read(gid).rgb, float3(0.0));

    // 1. guided 3x3 regression of the fused luma on the clean display luma, with the dark gate
    float momentX = 0.0, momentY = 0.0, momentX2 = 0.0, momentXY = 0.0, ws = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            int2 c = clamp(coord + int2(dx, dy), int2(0), int2(p.width - 1, p.height - 1));
            float3 rgb_s = max(cleanRgb.read(uint2(c)).rgb, float3(0.0)) * p.autoExposure;
            float l_m = max(rgb_s.r * 0.2126 + rgb_s.g * 0.7152 + rgb_s.b * 0.0722, max(max(rgb_s.r, rgb_s.g), rgb_s.b) * 0.5);
            float x = apply_display_gamma_scalar(acescg_toe_default(l_m, p.acesToe), p.gammaCurveType, p.gammaInv);
            float y;
            if (p.efScaled == 1) {
                y = accumEf.sample(linearClamp, (float2(c) + 0.5) / float2(p.width, p.height)).r;
            } else {
                y = accumEf.read(uint2(c)).r;
            }
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

    float3 rgb_center = orig_lin * p.autoExposure;
    float l_center = max(rgb_center.r * 0.2126 + rgb_center.g * 0.7152 + rgb_center.b * 0.0722,
                         max(max(rgb_center.r, rgb_center.g), rgb_center.b) * 0.5);
    float center_luma = apply_display_gamma_scalar(acescg_toe_default(l_center, p.acesToe), p.gammaCurveType, p.gammaInv);
    float target_lores_fused = max(regA * center_luma + regB, 0.0);
    const float kDarkThreshold = 0.007;
    if (center_luma < kDarkThreshold) {
        float t_fade = center_luma / kDarkThreshold;
        target_lores_fused = mix(center_luma, target_lores_fused, t_fade * t_fade);
    }

    // 2. ACES inversion and base tonemap
    float target_aces = invert_display_gamma_scalar(target_lores_fused, p.gammaCurveType, p.gamma);
    float target_lin_luma = inverse_acescg(target_aces, p.acesToe, 1.5, 2.51, 0.59);
    if (p.clipCeiling > 1.05) {
        float max_lin = 0.18 * exp2(p.agxMaxEv + max(p.agxEv, 0.0));
        if (p.tonemapOperator == 1) max_lin = 3.0;
        else if (p.tonemapOperator == 2 || p.tonemapOperator == 3) max_lin = 4.0;
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
    float effective_ae = p.autoExposure;
    if (p.clipCeiling > 1.05 && p.autoExposure > 1.0) {
        float hl_weight = smoothstep(0.70, 1.20, orig_lin_luma);
        effective_ae = mix(p.autoExposure, 1.0, hl_weight);
    }
    float base_multiplier = clamp(target_lin_luma / max(orig_lin_luma * effective_ae, 0.0001), 0.05, 12.0);
    float3 final_lin = orig_lin * effective_ae * base_multiplier;

    // 3. high-res luma detail transfer
    float tOriginal_luma = max(hiresLuma.read(gid).r, 0.000001);
    float unsharpened_luma = orig_lin.r * 0.2126 + orig_lin.g * 0.7152 + orig_lin.b * 0.0722;
    float L_a3 = safe_cbrt(tOriginal_luma);
    float L_base = safe_cbrt(unsharpened_luma);
    float luma_delta = L_a3 - L_base;
    float max_trench = min(0.035, L_base * 0.25);
    luma_delta = clamp(luma_delta, -max_trench, 0.050);

    // 4. OKLab and vibrance
    float L = 0.4122214708 * final_lin.r + 0.5363325363 * final_lin.g + 0.0514459929 * final_lin.b;
    float M = 0.2119034982 * final_lin.r + 0.6806995451 * final_lin.g + 0.1073969566 * final_lin.b;
    float S = 0.0883024619 * final_lin.r + 0.2817188376 * final_lin.g + 0.6299787005 * final_lin.b;
    float l_ = safe_cbrt(max(L, 0.0));
    float m_ = safe_cbrt(max(M, 0.0));
    float s_ = safe_cbrt(max(S, 0.0));
    float oklab_L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
    float oklab_a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
    float oklab_b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;
    if (abs(p.macroContrast - 1.0) > 0.001) {
        float pivot_L = 0.40;
        oklab_L = max(pivot_L + (oklab_L - pivot_L) * p.macroContrast, 0.0001);
    }
    float cur_sat = max(sqrt(oklab_a * oklab_a + oklab_b * oklab_b), 0.0001);
    float skin_theta = atan2(oklab_b, oklab_a);
    float d_theta = skin_theta - p.skinHueCenter;
    float w_theta = exp(-0.5 * (d_theta * d_theta) / (0.28 * 0.28));
    float w_chroma = smoothstep(0.015, 0.040, cur_sat) * (1.0 - smoothstep(0.18, 0.28, cur_sat));
    float w_luma = smoothstep(p.skinLumaMin - 0.06, p.skinLumaMin + 0.06, oklab_L) * (1.0 - smoothstep(0.88, 0.98, oklab_L));
    float skin_weight = clamp(w_theta * w_chroma * w_luma, 0.0, 1.0);
    float sat_t = smoothstep(0.02, 0.09, cur_sat);
    float total_boost = mix(p.vibStrength, p.vibSaturated, sat_t);
    if (abs(p.vibSky) > 0.001 && oklab_b < 0.0) {
        float sky_mask = clamp(-oklab_b / cur_sat, 0.0, 1.0);
        total_boost += p.vibSky * sky_mask;
    }
    if (abs(p.vibGreen) > 0.001 && oklab_a < 0.0) {
        float green_mask = clamp(-oklab_a / cur_sat, 0.0, 1.0);
        total_boost += p.vibGreen * green_mask;
    }
    if (abs(p.vibHl) > 0.001) {
        float hl_mask = smoothstep(0.60, 0.80, oklab_L) * (1.0 - smoothstep(0.88, 0.98, oklab_L));
        total_boost += p.vibHl * hl_mask;
    }
    if (abs(p.vibMid) > 0.001) {
        float mid_mask = smoothstep(0.15, 0.35, oklab_L) * (1.0 - smoothstep(0.55, 0.75, oklab_L));
        total_boost += p.vibMid * mid_mask;
    }
    if (abs(p.vibShadow) > 0.001) {
        float shadow_mask = 1.0 - smoothstep(0.08, 0.25, oklab_L);
        total_boost += p.vibShadow * shadow_mask;
    }
    float skin_protect_factor = 1.0 - 0.70 * skin_weight;
    float skin_mult = mix(1.0, skin_protect_factor, p.skinProtect);
    float final_sat_mult = max(0.0, 1.0 + total_boost * skin_mult);
    oklab_a *= final_sat_mult;
    oklab_b *= final_sat_mult;
    if (skin_weight > 0.001) {
        if (abs(p.skinHueShift) > 0.001) {
            float rad = p.skinHueShift * 0.0174532925 * skin_weight;
            float cos_h = cos(rad);
            float sin_h = sin(rad);
            float new_a = oklab_a * cos_h - oklab_b * sin_h;
            float new_b = oklab_a * sin_h + oklab_b * cos_h;
            oklab_a = new_a;
            oklab_b = new_b;
        }
        if (abs(p.skinSatShift) > 0.001) {
            float skin_sat_mult = max(0.0, 1.0 + p.skinSatShift * skin_weight);
            oklab_a *= skin_sat_mult;
            oklab_b *= skin_sat_mult;
        }
    }
    float in_chroma = sqrt(oklab_a * oklab_a + oklab_b * oklab_b);
    float2 in_hue_dir = (in_chroma > 0.000001) ? (float2(oklab_a, oklab_b) / in_chroma) : float2(0.0);
    l_ = max(0.0, oklab_L + 0.3963377774 * oklab_a + 0.2158037573 * oklab_b);
    m_ = max(0.0, oklab_L - 0.1055613458 * oklab_a - 0.0638541728 * oklab_b);
    s_ = max(0.0, oklab_L - 0.0894841775 * oklab_a - 1.2914855480 * oklab_b);
    float l3 = l_ * l_ * l_; float m3 = m_ * m_ * m_; float s3 = s_ * s_ * s_;
    float3 rgb_scene = float3(
        max(+4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3, 0.0),
        max(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3, 0.0),
        max(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3, 0.0));

    // 5. shoulder (HDR), AgX / alternatives
    if (p.clipCeiling > 1.05 && p.shoulderStrength > 0.001) {
        float peak_val = max(rgb_scene.r, max(rgb_scene.g, rgb_scene.b));
        float dyn_max_ev = p.agxMaxEv + max(p.agxEv, 0.0);
        float agx_top_lin = (p.tonemapOperator == 0) ? (0.18 * exp2(dyn_max_ev)) : 4.0;
        float soft_shoulder_knee = agx_top_lin * p.shoulderKnee;
        if (peak_val > soft_shoulder_knee) {
            float excess = peak_val - soft_shoulder_knee;
            float max_headroom = agx_top_lin * p.shoulderHeadroom;
            float compressed_peak = soft_shoulder_knee + (excess * max_headroom) / (excess + max_headroom);
            float scale = mix(1.0, compressed_peak / peak_val, p.shoulderStrength);
            rgb_scene *= scale;
        }
    }
    float3 rgb_inset_val = max(p.agxInset * rgb_scene, float3(0.0));
    float3 graded_lin = float3(0.0);
    if (p.tonemapOperator == 0) {
        float eff_max_ev_dyn = p.agxMaxEv + max(p.agxEv, 0.0);
        float ev_range = max(eff_max_ev_dyn - p.agxMinEv, 1.0);
        float px = clamp(-p.agxMinEv / ev_range, 0.05, 0.95);
        float3 log_rgb = clamp((log2(max(rgb_inset_val, float3(0.0000001)) / 0.18) + p.agxEv - p.agxMinEv) / ev_range, float3(0.0), float3(1.0));
        float3 cur_rgb = float3(agx_tonescale(log_rgb.r, p.agxSlope, p.agxSp, p.agxTp, px),
                                agx_tonescale(log_rgb.g, p.agxSlope, p.agxSp, p.agxTp, px),
                                agx_tonescale(log_rgb.b, p.agxSlope, p.agxSp, p.agxTp, px));
        if (p.agxLook == 3) {
            cur_rgb.r = pow(max(cur_rgb.r, 0.0), 0.96);
            cur_rgb.b = pow(max(cur_rgb.b, 0.0), 1.04);
        }
        float3 lin_pass = pow(max(cur_rgb, float3(0.0)), float3(2.4));
        float3 rgb_out = p.agxOutset * lin_pass;
        if (abs(p.agxSatBoost - 1.0) > 0.001) {
            float out_luma = dot(rgb_out, float3(0.2126, 0.7152, 0.0722));
            rgb_out = float3(out_luma) + (rgb_out - float3(out_luma)) * p.agxSatBoost;
        }
        graded_lin = clamp(rgb_out, float3(0.0), float3(1.0));
    } else if (p.tonemapOperator == 3) {
        float ev_half = 5.0;
        float3 ev_val = log2(max(rgb_inset_val, float3(0.0000001)) / 0.18);
        float3 norm_x = clamp(float3(0.5) + ev_val * (0.5 / ev_half), float3(0.0), float3(1.0));
        float3 cur_rgb = float3(customCurve.sample(linearClamp, float2(norm_x.r, 0.5)).r,
                                customCurve.sample(linearClamp, float2(norm_x.g, 0.5)).r,
                                customCurve.sample(linearClamp, float2(norm_x.b, 0.5)).r);
        if (abs(p.contrastBoost) > 0.0001) {
            cur_rgb.r = clamp(cur_rgb.r + get_curve_contrast_delta(norm_x.r, p.contrastBoost), 0.0, 1.0);
            cur_rgb.g = clamp(cur_rgb.g + get_curve_contrast_delta(norm_x.g, p.contrastBoost), 0.0, 1.0);
            cur_rgb.b = clamp(cur_rgb.b + get_curve_contrast_delta(norm_x.b, p.contrastBoost), 0.0, 1.0);
        }
        float3 lin_pass = pow(max(cur_rgb, float3(0.0)), float3(2.4));
        graded_lin = clamp(p.agxOutset * lin_pass, float3(0.0), float3(1.0));
    } else {
        float3 cur_rgb = float3(eval_tonemap_scalar(rgb_inset_val.r, p.tonemapOperator, p),
                                eval_tonemap_scalar(rgb_inset_val.g, p.tonemapOperator, p),
                                eval_tonemap_scalar(rgb_inset_val.b, p.tonemapOperator, p));
        graded_lin = clamp(p.agxOutset * clamp(cur_rgb, float3(0.0), float3(1.0)), float3(0.0), float3(1.0));
    }

    // 5.5 detail reconstruction and hue lock
    float post_L = dot(graded_lin, float3(0.4122214708, 0.5363325363, 0.0514459929));
    float post_M = dot(graded_lin, float3(0.2119034982, 0.6806995451, 0.1073969566));
    float post_S = dot(graded_lin, float3(0.0883024619, 0.2817188376, 0.6299787005));
    float p_l = safe_cbrt(max(post_L, 0.0));
    float p_m = safe_cbrt(max(post_M, 0.0));
    float p_s = safe_cbrt(max(post_S, 0.0));
    float post_oklab_L = 0.2104542553 * p_l + 0.7936177850 * p_m - 0.0040720468 * p_s;
    float post_oklab_a = 1.9779984951 * p_l - 2.4285922050 * p_m + 0.4505937099 * p_s;
    float post_oklab_b = 0.0259040371 * p_l + 0.7827717662 * p_m - 0.8086757660 * p_s;
    if (p.agxHueLock != 0 && !(p.tonemapOperator == 0 && p.agxLook == 3) && in_chroma > 0.000001) {
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
    float3 final_graded = float3(
        clamp(+4.0767416621 * p_l3 - 3.3077115913 * p_m3 + 0.2309699292 * p_s3, 0.0, 1.0),
        clamp(-1.2684380046 * p_l3 + 2.6097574011 * p_m3 - 0.3413193965 * p_s3, 0.0, 1.0),
        clamp(-0.0041960863 * p_l3 - 0.7034186147 * p_m3 + 1.7076147010 * p_s3, 0.0, 1.0));

    // 6. display gamma, film toe, spatial dither
    float3 out_sdr = apply_display_gamma_vec3(final_graded, p.gammaCurveType, p.gammaInv);
    if (p.filmToe > 0.0) {
        float luma_sdr = max(dot(out_sdr, float3(0.2126, 0.7152, 0.0722)), 0.0001);
        float toe_limit = 0.15;
        if (luma_sdr < toe_limit) {
            float norm_luma = luma_sdr / toe_limit;
            float crushed = pow(max(norm_luma, 0.000001), 1.0 + p.filmToe);
            float toe_ratio = (crushed * toe_limit) / luma_sdr;
            out_sdr *= toe_ratio;
        }
    }
    float d1 = fract(0.06711056 * float(coord.x) + 0.00583715 * float(coord.y));
    float dither = (fract(52.9829189 * d1) - 0.5) / 255.0;
    if (p.quant8 == 1) {
        output.write(float4(floor(clamp(out_sdr + dither, float3(0.0), float3(1.0)) * 255.0 + 0.5) / 255.0, 1.0), gid);
    } else {
        output.write(float4(clamp(out_sdr + dither, float3(0.0), float3(1.0)), 1.0), gid);
    }
}
