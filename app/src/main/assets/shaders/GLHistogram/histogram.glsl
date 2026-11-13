precision highp sampler2D;
precision highp int;
precision highp float;
// Per-channel histograms (scripts/GLHistogram): every SCALE-th texel of inTexture, scaled by `exposure` (bins - 1 per unit),
// counted into HISTSIZE bins - first in the work group's shared counters, then added to the channel's buffer.
// P317: memoryBarrierShared() with every barrier() (ESSL 3.10 8.14, as why/aestats.glsl).
uniform sampler2D inTexture;
uniform vec4 exposure;
#define COL_R 1
#define COL_G 1
#define COL_B 1
#define COL_A 1
#define HISTSIZE 256
#define SCALE 1
// bins each of the group's 64 invocations clears and flushes
#define PER_INVOCATION uint(HISTSIZE/64)

#if COL_R == 1
layout(std430, binding = 1) buffer histogramRed { uint countR[]; };
shared uint groupR[HISTSIZE];
#endif
#if COL_G == 1
layout(std430, binding = 2) buffer histogramGreen { uint countG[]; };
shared uint groupG[HISTSIZE];
#endif
#if COL_B == 1
layout(std430, binding = 3) buffer histogramBlue { uint countB[]; };
shared uint groupB[HISTSIZE];
#endif
#if COL_A == 1
layout(std430, binding = 4) buffer histogramAlpha { uint countA[]; };
shared uint groupA[HISTSIZE];
#endif

#define LAYOUT //
LAYOUT

void main() {
    uint first = uint(gl_LocalInvocationIndex) * PER_INVOCATION;
    for (uint k = first; k < first + PER_INVOCATION; k++) {
        #if COL_R == 1
        groupR[k] = 0u;
        #endif
        #if COL_G == 1
        groupG[k] = 0u;
        #endif
        #if COL_B == 1
        groupB[k] = 0u;
        #endif
        #if COL_A == 1
        groupA[k] = 0u;
        #endif
    }
    memoryBarrierShared(); barrier();

    ivec2 at = ivec2(gl_GlobalInvocationID.xy) * SCALE;
    ivec2 size = textureSize(inTexture, 0).xy;
    if (all(lessThan(at, size))) {
        uvec4 bin = clamp(uvec4(exposure * texture(inTexture, (vec2(at) + 0.5) / vec2(size))), uvec4(0), uvec4(HISTSIZE - 1));
        #if COL_R == 1
        atomicAdd(groupR[bin.r], 1u);
        #endif
        #if COL_G == 1
        atomicAdd(groupG[bin.g], 1u);
        #endif
        #if COL_B == 1
        atomicAdd(groupB[bin.b], 1u);
        #endif
        #if COL_A == 1
        atomicAdd(groupA[bin.a], 1u);
        #endif
    }
    memoryBarrierShared(); barrier();

    for (uint k = first; k < first + PER_INVOCATION; k++) {
        #if COL_R == 1
        atomicAdd(countR[k], groupR[k]);
        #endif
        #if COL_G == 1
        atomicAdd(countG[k], groupG[k]);
        #endif
        #if COL_B == 1
        atomicAdd(countB[k], groupB[k]);
        #endif
        #if COL_A == 1
        atomicAdd(countA[k], groupA[k]);
        #endif
    }
}
