precision highp float;
precision highp int;
precision highp sampler2D;
#define LAYOUT //
LAYOUT
uniform sampler2D WhyLow;
uniform float aeU;
uniform int sharpU;
uniform float sharpToneU;
layout(std430, binding = 0) buffer rimTiles { uint support[]; };
shared uint occupied;
void main() {
    if (gl_LocalInvocationIndex == 0u) occupied = 0u;
    memoryBarrierShared(); barrier();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = textureSize(WhyLow, 0);
    if (all(lessThan(p, size))) {
        vec3 tap = texelFetch(WhyLow, p, 0).rgb;
        float ae = aeU > 0.0 ? aeU : 1.0;
        float m = sharpU == 1 && sharpToneU > 0.0 ? ae / sharpToneU : ae;
        float mx = max(max(tap.r, tap.g), max(tap.b, 0.0));
        // Strict margins certify both light shares are zero, including signed RGB.
        if (any(isnan(tap)) || any(isinf(tap)) || isnan(m) || isinf(m) || !(m > 0.0)
                || isinf(ae) || mx * m > 1.25 || mx * ae > 0.625)
            atomicOr(occupied, 1u);
    }
    memoryBarrierShared(); barrier();
    if (gl_LocalInvocationIndex == 0u) {
        uint width = uint((size.x + 7) / 8);
        support[gl_WorkGroupID.y * width + gl_WorkGroupID.x] = occupied;
    }
}
