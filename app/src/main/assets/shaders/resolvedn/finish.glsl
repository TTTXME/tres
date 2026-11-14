precision highp float; precision highp sampler2D;
#import interpolation
uniform sampler2D InputBuffer;
uniform sampler2D Filtered;
uniform sampler2D GainMap;
uniform mat3 fromRenderU;
uniform vec3 neutralU;
uniform int filteredOriginU;
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec4 gain = textureBicubicHardware(GainMap, vec2(xy) / vec2(textureSize(InputBuffer, 0)));
    float lsc = (gain.r + 0.5 * (gain.g + gain.b) + gain.a) / 3.0;
    vec3 camera = fromRenderU * texelFetch(Filtered, xy - ivec2(0, filteredOriginU), 0).rgb / max(neutralU * lsc, vec3(1e-6));
    Output = vec4(camera, texelFetch(InputBuffer, xy, 0).a);
}
