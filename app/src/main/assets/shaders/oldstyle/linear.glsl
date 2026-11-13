precision highp float; precision highp int; precision highp sampler2D;
// P630b «Oldstyle» (OldstyleTone): the frame the owner's tone-port module takes, as int16 samples of its 12-bit scale (4095 =
// white of the merge's exposure x uScale), read back strip by strip. The colour chain of why/v2_clean (our white balance, lens
// shading; the colour matrix unless PRE_CCM: V1's finish_raw27_rgb applies our CCM itself), without its clamp at 0 (the module
// keeps negatives). The CPU twin of the sample: OldstyleAe.sample.
#define PRE_CCM 0
#import interpolation
uniform sampler2D InputBuffer;      // white-balanced linear camera RGB (ScamRgb .. ScamHdrDenoise), output grid
uniform sampler2D GainMap;          // lens shading gains (r, g_even, g_odd, b)
uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 neutralPointU;         // unset -> 1
uniform float inScaleU;             // why_input_ev; <= 0 -> 1
uniform float uScale;               // the module's input scale x 4095
out ivec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
    vec3 c = texelFetch(InputBuffer, xy, 0).rgb;
    c = vec3(isnan(c.r) || isinf(c.r) ? 0.0 : c.r, isnan(c.g) || isinf(c.g) ? 0.0 : c.g, isnan(c.b) || isinf(c.b) ? 0.0 : c.b);
    vec4 gains = textureBicubicHardware(GainMap, vec2(xy) / vec2(size));
    gains.rgb = vec3(gains.r, (gains.g + gains.b) / 2.0, gains.a);
    float lsc = dot(gains.rgb, vec3(1.0 / 3.0));
    vec3 neutral = neutralPointU.r + neutralPointU.g + neutralPointU.b > 0.0 ? neutralPointU : vec3(1.0);
    float scale = inScaleU > 0.0 ? inScaleU : 1.0;
    c = c * neutral * (lsc * scale);
#if PRE_CCM == 0
    c = intermediateToSRGB * (sensorToIntermediate * c);
#endif
    Output = ivec4(ivec3(clamp(floor(c * uScale + 0.5), -32768.0, 32767.0)), 0);
}
