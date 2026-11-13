precision highp float; precision highp int; precision highp sampler2D;
#import interpolation
uniform sampler2D InputBuffer;
uniform mat3 sensorToIntermediate;
uniform sampler2D GainMap;
uniform mat3 intermediateToSRGB;
uniform vec3 neutralPointU;
uniform float inScaleU;
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
    float y = max(dot(sceneLinear(xy, textureSize(InputBuffer, 0)), vec3(0.2126, 0.7152, 0.0722)), 0.0);
    Output = vec4(y, 0.0, 0.0, 1.0);
}
