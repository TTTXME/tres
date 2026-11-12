precision highp float;
precision highp sampler2D;
// WHY luma sharpening (WhyLumaSharpen), step 1: the guide that WhyCam's process_luma_fp16 sharpens [libfc_suppressor
// 0x7c5c4; research/hybrid5/why_sharpen.md 1, 6.2]: Ya = clamp(mulU * Y709(sceneLinear), 0, 1) on the output grid.
// mulU 1 = the G_CLEAN scale of Google's guide a3 (why_sharp_domain 1), mulU ae = the domain of the detail delta
// (why_sharp_domain 0). Keep sceneLinear() identical to why/low.glsl and why/combine.glsl.
// REF_LUMA 1 («Why тон» V2, P614): the guide the reference tone sharpens - its luma_extract of the clean RGB (why/v2_clean,
// CleanBuffer): Y709, soft knee 0.001 below it, unclamped above 1 (the reference's u_soft_knee 0.001, R16F).
#define REF_LUMA 0
#import interpolation
uniform sampler2D InputBuffer;      // white-balanced linear camera RGB (the merge after denoising), output grid
uniform sampler2D GainMap;          // lens shading gains (r, g_even, g_odd, b)
uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 neutralPointU;         // unset -> 1
uniform float inScaleU;             // <= 0 -> 1
uniform float mulU;                 // domain multiplier; <= 0 -> 1
#if REF_LUMA == 1
uniform sampler2D CleanBuffer;      // why/v2_clean (RGBA16F)
#endif
out vec4 Output;

vec3 sceneLinear(ivec2 xy, ivec2 size) {
    vec3 inColor = max(texelFetch(InputBuffer, xy, 0).rgb, vec3(0.0));
    vec4 gains = textureBicubicHardware(GainMap, vec2(xy) / vec2(size));
    gains.rgb = vec3(gains.r, (gains.g + gains.b) / 2.0, gains.a);
    float lsc = dot(gains.rgb, vec3(1.0 / 3.0));
    vec3 neutral = neutralPointU.r + neutralPointU.g + neutralPointU.b > 0.0 ? neutralPointU : vec3(1.0);
    float scale = inScaleU > 0.0 ? inScaleU : 1.0;
    return intermediateToSRGB * (sensorToIntermediate * (inColor * neutral * (lsc * scale)));
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
#if REF_LUMA == 1
    // luma_extract of the reference (its own expression)
    vec3 rgb = texelFetch(CleanBuffer, xy, 0).rgb;
    float luma = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
    float eps = max(0.0001, 0.001);
    float safe_luma;
    if (luma >= eps) { safe_luma = luma; }
    else if (luma > -eps) { safe_luma = (luma + eps) * (luma + eps) / (4.0 * eps); }
    else { safe_luma = 0.0; }
    Output = vec4(max(safe_luma, 0.0), 0.0, 0.0, 1.0);
    return;
#endif
    float m = mulU > 0.0 ? mulU : 1.0;
    float y = dot(sceneLinear(xy, textureSize(InputBuffer, 0)), vec3(0.2126, 0.7152, 0.0722));
    Output = vec4(clamp(y * m, 0.0, 1.0), 0.0, 0.0, 1.0);
}
