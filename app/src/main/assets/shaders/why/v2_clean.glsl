precision highp float; precision highp sampler2D;
// P614 «Why тон» V2, step 1 (WhyV2Stats): the clean linear RGB the reference tone reads (its u_tex_clean_rgb: the merge after its
// lens shading and colour matrix, linear sRGB / Rec.709, white-balanced, no clip) at the full output grid, from our colour chain -
// sceneLinear() of why/low.glsl (NaN / inf zeroed), then clamped at 0 as the reference's colour stage writes it. RGBA16F, the reference's texture format.
#import interpolation
uniform sampler2D InputBuffer;      // white-balanced linear camera RGB (ScamRgb .. ScamHdrDenoise), output grid
uniform sampler2D GainMap;          // lens shading gains (r, g_even, g_odd, b)
uniform mat3 sensorToIntermediate;  // as why/low.glsl
uniform mat3 intermediateToSRGB;    // as why/low.glsl
uniform vec3 neutralPointU;         // unset -> 1
uniform float inScaleU;             // why_input_ev; <= 0 -> 1
out vec4 Output;

vec3 sceneLinear(ivec2 xy, ivec2 size) {
    vec3 inColor = texelFetch(InputBuffer, xy, 0).rgb;
    inColor = vec3(isnan(inColor.r) || isinf(inColor.r) ? 0.0 : inColor.r,
                   isnan(inColor.g) || isinf(inColor.g) ? 0.0 : inColor.g,
                   isnan(inColor.b) || isinf(inColor.b) ? 0.0 : inColor.b);
    vec4 gains = textureBicubicHardware(GainMap, vec2(xy) / vec2(size));
    gains.rgb = vec3(gains.r, (gains.g + gains.b) / 2.0, gains.a);
    float lsc = dot(gains.rgb, vec3(1.0 / 3.0));
    vec3 neutral = neutralPointU.r + neutralPointU.g + neutralPointU.b > 0.0 ? neutralPointU : vec3(1.0);
    float scale = inScaleU > 0.0 ? inScaleU : 1.0;
    return intermediateToSRGB * (sensorToIntermediate * (inColor * neutral * (lsc * scale)));
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    // the reference's input to its tone: max(0, CCM x (LSC x camera RGB)) (sabre_linear_color_lsc_ccm clamps there)
    Output = vec4(max(sceneLinear(xy, textureSize(InputBuffer, 0)), vec3(0.0)), 1.0);
}
