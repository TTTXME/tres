precision highp float; precision highp int; precision highp sampler2D; precision highp usampler2D;
// Three full-output-grid a-trous passes; coordinates remain global on bounded-memory strips.
uniform sampler2D InputBuffer;      // pass 0: linear RGB (signed allowed); passes 1, 2: the signed YUV of the pass before
uniform usampler2D EffMap;          // the merge's effective-frame codes on this grid (useEffU 1; else a 1 x 1 placeholder)
uniform ivec2 fullSizeU;
uniform ivec2 sensorSizeU;
uniform ivec2 sensorOriginU;
uniform int inputOriginU;
uniform int outputOriginU;
uniform int useEffU;
uniform float effScaleU;            // frames per code (the map's median frames / 64)
uniform float framesU;              // N without a map and of code 0
uniform float totalU;               // u_total_frames: the merged normal frames (N is clamped to 1 .. totalU)
uniform int passU;                  // 0, 1, 2 (dilation.z)
uniform int stepU;                  // 1, 2, 4 px (dilation.xy x size)
uniform vec4 noiseModelU;           // yuv_noise_model: (0.562 S, 0.25 S, 0.25 S, O)
uniform float factorU;              // denoising_factor (0.4; < 0.001: bypass)
uniform float flatNightU;           // P648c: the night's share of the flat-area smoothing (0, unset: none - bit for bit as before)
uniform vec2 flatNoiseU;            // P648c: the merged image's measured luma noise, variance S y + O (finishSnr's block statistics)
uniform float flatBoostU;           // P648c: the factor's multiplier on a flat pixel at flatNightU 1 (<= 1: none; the camera model's
                                    // range under-reads the merged noise, so a flat pixel takes a wider one)
uniform float flatGrainU;           // P648c: an even grain given back where the flat smoothing took it (x the pixel's noise sigma; 0: none)
uniform vec3 flatShadowU;           // P666: the shadows' coarse texture test - x .. y: the local SNR (3 x 3 mean luma / its measured sigma)
                                    // over which the shadows end, z: the noise share of the coarse variance (x >= y or z <= 0: none, as P648c)
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
    vec3 c = texelFetch(InputBuffer, clamp(p - ivec2(0, inputOriginU), ivec2(0), textureSize(InputBuffer, 0) - 1), 0).rgb;
    if (passU == 0) c = vec3(finite1(c.r), finite1(c.g), finite1(c.b));
    return c;
}

// P666: the luma of a pass-0 input texel (linear RGB)
float SampleLuma(ivec2 p) {
    vec3 c = texelFetch(InputBuffer, clamp(p - ivec2(0, inputOriginU), ivec2(0), textureSize(InputBuffer, 0) - 1), 0).rgb;
    return dot(vec3(finite1(c.r), finite1(c.g), finite1(c.b)), vec3(0.2126390, 0.7151688, 0.0721923));
}

// P648c: the flatness pass 0 measured, carried to passes 1 and 2 in the intermediate's alpha
float SampleFlat(ivec2 p) {
    return texelFetch(InputBuffer, clamp(p - ivec2(0, inputOriginU), ivec2(0), textureSize(InputBuffer, 0) - 1), 0).a;
}

// P648c: flatness from the luma variance against the merged image's measured noise (r): 1 up to FLAT_LO (nothing but noise),
// 0 from FLAT_HI (texture, an edge, a gradient)
#define FLAT_LO 0.5
#define FLAT_HI 0.8

// Why's frame_border mirror of the -1 / +1 taps at texel centres: -i - 1 below 0, 2 size - 1 - i above the last texel
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
    ivec2 xy = ivec2(gl_FragCoord.xy) + ivec2(0, outputOriginU);
    ivec2 size = fullSizeU;
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
    float fallback_weight = clamp(1.0 - (num_merged_frames - 1.0) / max(1.0, total_f * 0.70), 0.0, 1.0);

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

    // P648c: the night's extra smoothing only where the picture is flat (sky, walls, skin, paint). Pass 0 measures r, the merged
    // luma's 3 x 3 variance over its measured noise there, and carries it in the intermediate's alpha; pass 1 averages r over its 3 x 3
    // at step 2 (a steadier estimate over ~ 7 x 7 pixels: flat = 1 below FLAT_LO, 0 from FLAT_HI) and carries the flatness; pass 2 takes
    // min(own, its 3 x 3 mean at step 4) - a pixel next to texture or an edge gets less. Passes 1 and 2 smooth, only on pixels the
    // frames merged on (a rejected moving subject as before)
    float flat_px = 1.0, flat_m = 0.0;
    if (flatNightU > 0.0) {
        if (passU == 0) {
            float m = 0.0, m2 = 0.0;
            for (int i = 0; i < 9; ++i) { m += samples[i].x; m2 += samples[i].x * samples[i].x; }
            m *= 1.0 / 9.0;
            float var = max(m2 * (1.0 / 9.0) - m * m, 0.0) * (9.0 / 8.0);
            float nv = max(flatNoiseU.x * max(m, 0.0) + flatNoiseU.y, 1.0e-12);
            flat_px = min(var / nv, 64.0);   // r (pass 0 does not smooth more)
            // P666: in the shadows a texture under the pixel noise (bark, foliage, brick, fabric in the shade) still stands out of it
            // over a few pixels, where the noise averages down: the variance of the nine 3 x 3 means of a 9 x 9 window over the noise,
            // / flatShadowU.z (that variance's share of pure noise), counts as r there - the shadow's texture is not called flat, its
            // flat parts are smoothed as before; above the shadows (local SNR from flatShadowU.y) r as P648c
            if (flatShadowU.y > flatShadowU.x && flatShadowU.z > 0.0) {
                float shade = 1.0 - smoothstep(flatShadowU.x, flatShadowU.y, max(m, 0.0) * inversesqrt(nv));
                if (shade > 0.0) {
                    float cs = 0.0, cs2 = 0.0;
                    for (int bj = -1; bj <= 1; ++bj)
                        for (int bi = -1; bi <= 1; ++bi) {
                            float bm = 0.0;
                            for (int j = -1; j <= 1; ++j)
                                for (int i = -1; i <= 1; ++i)
                                    bm += SampleLuma(ivec2(mirror(xy.x + 3 * bi + i, size.x), mirror(xy.y + 3 * bj + j, size.y)));
                            bm *= 1.0 / 9.0;
                            cs += bm; cs2 += bm * bm;
                        }
                    cs *= 1.0 / 9.0;
                    float rc = max(cs2 * (1.0 / 9.0) - cs * cs, 0.0) * (9.0 / 8.0) / nv;
                    flat_px = max(flat_px, min(shade * rc / flatShadowU.z, 64.0));
                }
            }
        } else {
            float sum = 0.0;
            for (int j = -1; j <= 1; ++j)
                for (int i = -1; i <= 1; ++i)
                    sum += SampleFlat(ivec2(i < 0 ? x0 : i > 0 ? x2 : xy.x, j < 0 ? y0 : j > 0 ? y2 : xy.y));
            flat_px = passU == 1 ? 1.0 - smoothstep(FLAT_LO, FLAT_HI, sum * (1.0 / 9.0)) : min(SampleFlat(xy), sum * (1.0 / 9.0));
            float stillF = smoothstep(1.5, 3.0, num_merged_frames) * smoothstep(0.6, 0.95, num_merged_frames / max(framesU, 1.0));
            flat_m = clamp(flatNightU, 0.0, 1.0) * flat_px * stillF;
        }
    }
    float flatAlpha = flatNightU > 0.0 ? flat_px : 1.0;

    // zero denoiser bypass
    if (factorU < 0.001) {
        Output = passU == 2 ? vec4(max(vec3(0.0), FromGcamYUV(col)), 1.0) : vec4(col, flatAlpha);
        return;
    }

    float key = col.x;
    // variance of the noise model at the centre sample
    float noise_model_var = dot(noiseModelU.xyz * abs(col.xyz), vec3(1.0)) + noiseModelU.w;
    float bilateral_sigma = max(1.0e-6, noise_model_var);

    // optical vignetting compensation
    ivec2 sensorSize = all(greaterThan(sensorSizeU, ivec2(0))) ? sensorSizeU : size;
    vec2 tex_coord = (vec2(xy + sensorOriginU) + 0.5) / vec2(sensorSize);
    vec2 vignette_coords_shifted = (tex_coord - 0.5) * vignetteScaleU;
    float vignette_dist_sq = dot(vignette_coords_shifted, vignette_coords_shifted);
    float vignette = clamp(vignette_dist_sq / 0.25 - 0.25, 0.0, 1.0);
    vignette = vignette * vignette * vignette;
    float factor = factorU;
    if (flat_m > 0.0 && flatBoostU > 1.0) factor *= mix(1.0, flatBoostU, flat_m);
    bilateral_sigma *= max(0.001, factor) * (1.0 + vignetteBoostU * vignette);

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
    if (flat_m > 0.0) luma_revert *= 1.0 - flat_m;   // P648c: a flat pixel takes the denoised luma
    denoised.x = mix(denoised.x, col.x, luma_revert);

    // pass 2: the calibrated grain where few frames merged, back to linear RGB
    if (passU == 2) {
        float noise_magnitude = sqrt(max(0.0, noise_model_var) / num_merged_frames) * grainU * blurry_weight;
        float uniform_noise = hash(xy + sensorOriginU);
        // P648c: the flat areas keep a fine, even grain (no wax): x the pixel's merged noise sigma, by the flat share
        if (flat_m > 0.0 && flatGrainU > 0.0)
            noise_magnitude += sqrt(max(flatNoiseU.x * max(denoised.x, 0.0) + flatNoiseU.y, 0.0)) * flatGrainU * flat_m;
        denoised.x = max(0.0, denoised.x + uniform_noise * noise_magnitude);
        Output = vec4(max(vec3(0.0), FromGcamYUV(denoised)), 1.0);
    } else {
        // intermediate passes: signed YUV
        Output = vec4(denoised, flatAlpha);
    }
}
