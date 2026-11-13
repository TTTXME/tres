precision highp float; precision highp int; precision highp sampler2D;
#define CAMERA_SOURCE 0
#if CAMERA_SOURCE == 1
#import interpolation
uniform sampler2D GainMap;
uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 neutralPointU;
uniform float inScaleU;
#endif
uniform sampler2D InputBuffer;
uniform ivec2 sampleSizeU;
out vec4 Output;

vec3 portraitSample(ivec2 xy, ivec2 size) {
    vec3 rgb = texelFetch(InputBuffer, xy, 0).rgb;
#if CAMERA_SOURCE == 1
    rgb = vec3(isnan(rgb.r) || isinf(rgb.r) ? 0.0 : rgb.r,
               isnan(rgb.g) || isinf(rgb.g) ? 0.0 : rgb.g,
               isnan(rgb.b) || isinf(rgb.b) ? 0.0 : rgb.b);
    vec4 gains = textureBicubicHardware(GainMap, vec2(xy) / vec2(size));
    float lsc = (gains.r + (gains.g + gains.b) * 0.5 + gains.a) / 3.0;
    vec3 neutral = neutralPointU.r + neutralPointU.g + neutralPointU.b > 0.0 ? neutralPointU : vec3(1.0);
    float scale = inScaleU > 0.0 ? inScaleU : 1.0;
    rgb = intermediateToSRGB * (sensorToIntermediate * (rgb * neutral * (lsc * scale)));
#endif
    return rgb;
}

void main() {
    ivec2 size = textureSize(InputBuffer, 0);
    vec2 center = gl_FragCoord.xy / vec2(sampleSizeU) * vec2(size);
    vec2 stepU = max(vec2(size) / vec2(sampleSizeU) / 4.0, vec2(1.0));
    vec3 sum = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            ivec2 p = clamp(ivec2(center + (vec2(x, y) - 1.5) * stepU), ivec2(0), size - 1);
            sum += portraitSample(p, size);
        }
    }
    Output = vec4(sum / 16.0, 1.0);
}
