precision highp float; precision highp sampler2D;
#import interpolation
uniform sampler2D InputBuffer;
uniform mat3 toRenderU;
uniform vec3 neutralU;
uniform int originDstU;
uniform sampler2D GainMap;
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy) + ivec2(0, originDstU);
    vec4 gain = textureBicubicHardware(GainMap, vec2(xy) / vec2(textureSize(InputBuffer, 0)));
    float lsc = (gain.r + 0.5 * (gain.g + gain.b) + gain.a) / 3.0;
    vec3 rgb = toRenderU * (texelFetch(InputBuffer, xy, 0).rgb * neutralU * lsc);
    Output = vec4(rgb, max(max(gain.r, gain.g), max(gain.b, gain.a)));
}
