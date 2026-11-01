precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
// P443 (owner, 2026-11-01: «сделай его максимально схожим с шумодавом Арк»): the reference app's resolve denoise (its s032,
// "denoise A", ARK_DENOISE.md 1.1) on the merge. Three a-trous passes (stepU 1, 2, 4 px,
// passU 0, 1, 2) of a pair-symmetric 3x3 bilateral in GCam YUV (Y = Rec.709 luminance, U = Y - R, V = Y - B):
// - range sigma^2 = factorU x var / N, var = the camera model of the centre sample (0.562 S |Y| + 0.25 S |U| + 0.25 S |V| + O),
//   N = the merged frames of the pixel; all three channels take the luma weights;
// - motion (few frames): blurry = clamp(1.25 - 0.25 N) switches on the X prefilter of the key (pass 0) and the wide-sigma outlier
//   rescue, fallback = clamp(1 - (N - 1) / (0.7 x reference)) moves the luma revert from globalRevertU to rejectRevertU and keeps
//   the luma-guided colour filter in pass 2; grain sqrt(var / N) x grainU x blurry with the reference app's Weyl hash (pass 2);
// - colour: the luma-guided 3x3 mean (range max(0.04, 0.22 Y)) mixed in by max(0.65, edge risk) in passes 0 and 1;
// - luma revert: Y = mix(Y_filtered, Y_centre, mix(globalRevertU, rejectRevertU, max(blurry, fallback)));
// - the SNR-gated vignette boost of sigma^2 (vignetteBoostU, 0 above a one-frame SNR of 4).
// The final pass also guides low-SNR shadow chroma over a wider footprint; luminance is unchanged by that guide.
// Differences, all of the input side (ArkResolveDenoise): the input is the white-balanced linear camera RGB of ScamRgb (Ark: the
// normalised accumulators x the white-balance gains, the same domain; ScamRgb has applied the colour part of the lens shading,
// normalised to mean 1, which Ark applies after), so pass 0 reads a texture instead of the accumulators (Ark's RawTherapee
// highlight blend there is ScamRgb's own highlight recovery here) and zeroes a non-finite sample (one NaN would spread over the
// 13 x 13 footprint); N comes from the worker's effective-frame map (the noise-effective frames of the weighted mean, Ark: its
// weight sum + 1), code 0 = unknown -> framesU; the fallback ramp's reference is the map's median coverage (refU: a fully
// merged pixel in the map's units, where Ark's static pixel has N = total), so a static pixel keeps fallback 0 as in Ark.
// Integer pixel addressing (texelFetch) with the same mirror at the frame edge as Ark's frame_border (0, 0, 1, 1) on texel
// centres; the reference samples texel centres exactly, so the values are the same.
uniform sampler2D InputBuffer;      // pass 0: linear RGB (signed allowed); passes 1, 2: the signed YUV of the pass before
uniform usampler2D EffMap;          // the merge's effective-frame codes on this grid (useEffU 1; else a 1 x 1 placeholder)
uniform float darkChromaU;
uniform int useEffU;
uniform float effScaleU;            // frames per code (the map's median frames / 64)
uniform float framesU;              // N without a map and of code 0
uniform float totalU;               // u_total_frames: the merged normal frames (N is clamped to 1 .. totalU)
uniform float refU;                 // the fallback ramp's full coverage (see above)
uniform int passU;                  // 0, 1, 2 (dilation.z)
uniform int stepU;                  // 1, 2, 4 px (dilation.xy x size)
uniform vec4 noiseModelU;           // yuv_noise_model: (0.562 S, 0.25 S, 0.25 S, O)
uniform float factorU;              // denoising_factor (0.4; < 0.001: bypass)
uniform float outlierU;             // outlier_sigma_multiplier (0.125)
uniform float rejectRevertU;        // reject_luma_revert_factor (0.5)
uniform float globalRevertU;        // global_luma_revert_factor (0.85)
uniform float grainU;               // grain_strength (0.15)
uniform float vignetteBoostU;       // vignette_strength_boost
uniform float vignetteScaleU;       // vignette_spatial_scale_factor (1)
out vec4 Output;

vec3 ToGcamYUV(vec3 rgb) {
    float y = dot(rgb, vec3(0.2126390, 0.7151688, 0.0721923));
    return vec3(y, y - rgb.r, y - rgb.b);
}

vec3 FromGcamYUV(vec3 yuv) {
    float y = yuv.x;
    float u = yuv.y;
    float v = yuv.z;
    return vec3(y - u, y + 0.297327 * u + 0.100944 * v, y - v);
}

// binomial 1 2 1, normalised
float Weight(int x) {
    if (x == 0) return 0.5;
    if (abs(x) == 1) return 0.25;
    return 0.0;
}

float finite1(float v) { return (isnan(v) || isinf(v)) ? 0.0 : v; }

vec3 SampleTexture(ivec2 p) {
    vec3 c = texelFetch(InputBuffer, p, 0).rgb;
    if (passU == 0) c = vec3(finite1(c.r), finite1(c.g), finite1(c.b));
    return c;
}

// Ark's frame_border mirror of the -1 / +1 taps at texel centres: -i - 1 below 0, 2 size - 1 - i above the last texel
int mirror(int i, int size) {
    if (i < 0) i = -i - 1;
    if (i >= size) i = 2 * size - 1 - i;
    return clamp(i, 0, size - 1);
}

struct AccumulatedColor {
    vec4 accum_color;
    vec4 accum_color_wider;
};

// symmetric bilateral sample pair
AccumulatedColor ProcessSample(vec3 samples[9], int x, int y, float key, float bilateral_sigma, float outlier_sigma) {
    vec3 samp = samples[(y + 1) * 3 + x + 1];
    vec3 samp_opposite = samples[(-y + 1) * 3 - x + 1];
    float diff = (samp.x + samp_opposite.x) * 0.5 - key;
    float diff_sq = diff * diff;
    float weight = Weight(x) * Weight(y) * exp2(-diff_sq * bilateral_sigma);
    float weight_wider = Weight(x) * Weight(y) * exp2(-diff_sq * outlier_sigma);
    AccumulatedColor out_color;
    out_color.accum_color = vec4((samp + samp_opposite) * weight, 2.0 * weight);
    out_color.accum_color_wider = vec4((samp + samp_opposite) * weight_wider, 2.0 * weight_wider);
    return out_color;
}

// the reference app's Weyl generator of its sensor grain (int multiplications wrap; 0xf1bbcdcb as its 32-bit pattern)
float hash(highp ivec2 c) {
    highp int x = 0x3504f333 * c.x * c.x + c.y;
    highp int y = int(0xf1bbcdcbu) * c.y * c.y + c.x;
    return float(x * y) * (2.0 / 8589934592.0);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
    // number of merged frames
    float total_f = max(1.0, totalU);
    float num_merged_frames = max(framesU, 1.0);
    if (useEffU == 1) {
        uint code = texelFetch(EffMap, clamp(xy, ivec2(0), textureSize(EffMap, 0) - ivec2(1)), 0).r;
        if (code > 0u) num_merged_frames = float(code) * effScaleU;
    }
    num_merged_frames = clamp(num_merged_frames, 1.0, total_f);

    // blurry weight ramp: 1 to 5 frames for the luma denoiser
    float blurry_weight = clamp(1.25 - num_merged_frames * 0.25, 0.0, 1.0);
    // motion fallback / partial donor rejection relative to a fully merged pixel
    float fallback_weight = clamp(1.0 - (num_merged_frames - 1.0) / max(1.0, max(refU, 1.0) * 0.70), 0.0, 1.0);

    int k = max(stepU, 1);
    int x0 = mirror(xy.x - k, size.x), x2 = mirror(xy.x + k, size.x);
    int y0 = mirror(xy.y - k, size.y), y2 = mirror(xy.y + k, size.y);
    vec3 samples[9];
    samples[0] = SampleTexture(ivec2(x0, y0));
    samples[1] = SampleTexture(ivec2(xy.x, y0));
    samples[2] = SampleTexture(ivec2(x2, y0));
    samples[3] = SampleTexture(ivec2(x0, xy.y));
    samples[4] = SampleTexture(xy);
    samples[5] = SampleTexture(ivec2(x2, xy.y));
    samples[6] = SampleTexture(ivec2(x0, y2));
    samples[7] = SampleTexture(ivec2(xy.x, y2));
    samples[8] = SampleTexture(ivec2(x2, y2));

    // pass 0: linear RGB to GCam YUV
    if (passU == 0) {
        for (int i = 0; i < 9; ++i) samples[i] = ToGcamYUV(samples[i]);
    }

    vec3 col = samples[4];

    // zero denoiser bypass
    if (factorU < 0.001) {
        Output = passU == 2 ? vec4(max(vec3(0.0), FromGcamYUV(col)), 1.0) : vec4(col, 1.0);
        return;
    }

    float key = col.x;
    // variance of the noise model at the centre sample
    float noise_model_var = dot(noiseModelU.xyz * abs(col.xyz), vec3(1.0)) + noiseModelU.w;
    float bilateral_sigma = max(1.0e-6, noise_model_var);

    // optical vignetting compensation
    vec2 tex_coord = (vec2(xy) + 0.5) / vec2(size);
    vec2 vignette_coords_shifted = (tex_coord - 0.5) * vignetteScaleU;
    float vignette_dist_sq = dot(vignette_coords_shifted, vignette_coords_shifted);
    float vignette = clamp(vignette_dist_sq / 0.25 - 0.25, 0.0, 1.0);
    vignette = vignette * vignette * vignette;
    bilateral_sigma *= max(0.001, factorU) * (1.0 + vignetteBoostU * vignette);

    // X-shaped prefilter of the key (pass 0, motion only)
    float prefilter_weight = blurry_weight * (passU == 0 ? 1.0 : 0.0);
    float prefiltered_key = (samples[0].x + samples[2].x + samples[4].x + samples[6].x + samples[8].x) * 0.2;
    key = mix(key, prefiltered_key, prefilter_weight);

    float inv_sigma = 1.0 / bilateral_sigma;
    inv_sigma *= num_merged_frames;
    inv_sigma *= 0.5 / 0.69314718056; // 0.5 / ln(2) for exp2
    float inv_outlier_sigma = outlierU * inv_sigma;

    vec4 accum_color = vec4(col.xyz, 1.0) * (Weight(0) * Weight(0));
    vec4 accum_color_wider = vec4(col.xyz, 1.0) * (Weight(0) * Weight(0));
    AccumulatedColor sample_contrib;
    sample_contrib = ProcessSample(samples, -1, -1, key, inv_sigma, inv_outlier_sigma);
    accum_color += sample_contrib.accum_color;
    accum_color_wider += sample_contrib.accum_color_wider;
    sample_contrib = ProcessSample(samples, 0, -1, key, inv_sigma, inv_outlier_sigma);
    accum_color += sample_contrib.accum_color;
    accum_color_wider += sample_contrib.accum_color_wider;
    sample_contrib = ProcessSample(samples, 1, -1, key, inv_sigma, inv_outlier_sigma);
    accum_color += sample_contrib.accum_color;
    accum_color_wider += sample_contrib.accum_color_wider;
    sample_contrib = ProcessSample(samples, 1, 0, key, inv_sigma, inv_outlier_sigma);
    accum_color += sample_contrib.accum_color;
    accum_color_wider += sample_contrib.accum_color_wider;

    vec3 denoised = accum_color.xyz / max(accum_color.w, 1.0e-6);

    // outlier pixel protection
    float outlier_weight = (1.0 - smoothstep(0.25, 0.5, accum_color.w)) * blurry_weight;
    vec3 denoised_wider = accum_color_wider.xyz / max(accum_color_wider.w, 1.0e-6);
    denoised = mix(denoised, denoised_wider, outlier_weight);

    // luma-guided chroma filter (passes 0 and 1 always; pass 2 on motion fallback)
    float edge_risk = max(blurry_weight, fallback_weight);
    if (passU < 2 || edge_risk > 0.05) {
        float center_y = col.x;
        float sigma_y = max(0.04, 0.22 * center_y);
        float inv_2sig_y = 1.0 / (2.0 * sigma_y * sigma_y);
        vec2 sum_uv = col.yz * (Weight(0) * Weight(0));
        float sum_w = Weight(0) * Weight(0);
        for (int y = -1; y <= 1; ++y) {
            for (int x = -1; x <= 1; ++x) {
                if (x == 0 && y == 0) continue;
                vec3 s = samples[(y + 1) * 3 + x + 1];
                float diff_y = s.x - center_y;
                float rw = exp(-diff_y * diff_y * inv_2sig_y);
                float w = Weight(x) * Weight(y) * rw;
                sum_uv += s.yz * w;
                sum_w += w;
            }
        }
        vec2 guided_uv = sum_uv / sum_w;
        float blend = passU < 2 ? max(0.65, edge_risk) : edge_risk;
        denoised.yz = mix(denoised.yz, guided_uv, blend);
    }

    // luma revert: global in static areas, reject on motion fallback
    float motion_mix = max(blurry_weight, fallback_weight);
    float luma_revert = mix(globalRevertU, rejectRevertU, motion_mix);
    denoised.x = mix(denoised.x, col.x, luma_revert);

    // pass 2: the calibrated grain where few frames merged, back to linear RGB
    if (passU == 2) {
        float noise_magnitude = sqrt(max(0.0, noise_model_var) / num_merged_frames) * grainU * blurry_weight;
        float uniform_noise = hash(xy);
        denoised.x = max(0.0, denoised.x + uniform_noise * noise_magnitude);
        float sigma = sqrt(max(noise_model_var / num_merged_frames, 1.0e-12));
        float dark = darkChromaU * (1.0 - smoothstep(4.0, 16.0, max(col.x, 0.0) / sigma))
                   * (1.0 - smoothstep(0.005, 0.02, col.x));
        if (dark > 0.0) {
            // The three fine passes leave low-frequency colour noise; only noisy shadows need this wider guide.
            vec2 sum_uv = vec2(0.0);
            float sum_w = 0.0;
            float range = max(3.0 * sigma, 0.15 * max(col.x, 0.0));
            for (int y = -1; y <= 1; ++y) {
                for (int x = -1; x <= 1; ++x) {
                    ivec2 p = ivec2(mirror(xy.x + 16 * x, size.x), mirror(xy.y + 16 * y, size.y));
                    vec3 sample_yuv = SampleTexture(p);
                    float d = (sample_yuv.x - col.x) / range;
                    float w = Weight(x) * Weight(y) * exp(-0.5 * d * d);
                    sum_uv += sample_yuv.yz * w;
                    sum_w += w;
                }
            }
            denoised.yz = mix(denoised.yz, sum_uv / max(sum_w, 1.0e-6), 0.9 * dark);
        }
        Output = vec4(max(vec3(0.0), FromGcamYUV(denoised)), 1.0);
    } else {
        // intermediate passes: signed YUV
        Output = vec4(denoised, 1.0);
    }
}
