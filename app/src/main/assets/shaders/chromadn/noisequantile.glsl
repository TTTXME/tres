precision highp float;
precision highp int;
precision highp sampler2D;
#define PASS 0
#define RAW_KEYS 0
#define LAYOUT //
LAYOUT
#if RAW_KEYS == 1 || PASS == 2
layout(rgba16ui, binding = 0) readonly uniform highp uimage2D NoiseBits;
#else
uniform sampler2D Noise;
#endif
layout(std430, binding = 0) buffer noiseHist { uint bins[]; };
layout(std430, binding = 1) buffer noiseState { uint state[]; };
#if PASS == 0
shared uint valid;
void main() {
    if (gl_LocalInvocationIndex == 0u) valid = 0u;
    memoryBarrierShared(); barrier();
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    #if RAW_KEYS == 1
    ivec2 size = imageSize(NoiseBits);
    #else
    ivec2 size = textureSize(Noise, 0);
    #endif
    if (all(lessThan(xy, size))) {
        #if RAW_KEYS == 1
        uint key = imageLoad(NoiseBits, xy).r;
        if (key > 0u && key <= 31744u) {
            atomicAdd(bins[key], 1u);
            atomicAdd(valid, 1u);
        }
        #else
        float value = texelFetch(Noise, xy, 0).r;
        if (value > 0.0 && !isnan(value)) {
            uint key = packHalf2x16(vec2(value, 0.0)) & 65535u;
            if (key >= 1024u && key <= 31744u) {
                atomicAdd(bins[key], 1u);
                atomicAdd(valid, 1u);
            }
        }
        #endif
    }
    memoryBarrierShared(); barrier();
    if (gl_LocalInvocationIndex == 0u) {
        atomicAdd(state[0], valid);
        ivec2 covered = min(ivec2(gl_WorkGroupSize.xy), size - ivec2(gl_WorkGroupID.xy * gl_WorkGroupSize.xy));
        atomicAdd(state[4], uint(covered.x * covered.y));
    }
}
#elif PASS == 1
shared uint totals[128];
void main() {
    uint lane = gl_LocalInvocationIndex;
    uint count = 0u;
    for (uint i = lane * 256u; i < (lane + 1u) * 256u; i++) count += bins[i];
    totals[lane] = count;
    memoryBarrierShared(); barrier();
    if (lane != 0u) return;
    uint total = 0u;
    for (int i = 0; i < 128; i++) total += totals[i];
    state[1] = total;
    state[3] = total == state[0] ? 1u : 0u;
    if (total == 0u) { state[2] = 0u; return; }
    uint target = total / 4u, before = 0u, bucket = 0u;
    for (uint i = 0u; i < 128u; i++) {
        if (before + totals[i] > target) { bucket = i; break; }
        before += totals[i];
    }
    for (uint i = bucket * 256u; i < (bucket + 1u) * 256u; i++) {
        before += bins[i];
        if (before > target) { state[2] = i; break; }
    }
}
#else
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(xy, imageSize(NoiseBits)))) return;
    uvec4 keys = imageLoad(NoiseBits, xy);
    int at = (xy.y * imageSize(NoiseBits).x + xy.x) * 4;
    for (int i = 0; i < 4; i++) state[at + i] = keys[i];
}
#endif
