precision highp float;
precision highp int;
precision highp sampler2D;
#define PASS 0
#define LAYOUT //
LAYOUT
uniform sampler2D Noise;
layout(std430, binding = 0) buffer noiseHist { uint bins[]; };
layout(std430, binding = 1) buffer noiseState { uint state[]; };
#if PASS == 0
shared uint valid;
void main() {
    if (gl_LocalInvocationIndex == 0u) valid = 0u;
    memoryBarrierShared(); barrier();
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(xy, textureSize(Noise, 0)))) {
        float value = texelFetch(Noise, xy, 0).r;
        if (value > 0.0 && !isnan(value)) {
            uint key = packHalf2x16(vec2(value, 0.0)) & 65535u;
            if (key >= 1024u && key <= 31744u) {
                atomicAdd(bins[key], 1u);
                atomicAdd(valid, 1u);
            }
        }
    }
    memoryBarrierShared(); barrier();
    if (gl_LocalInvocationIndex == 0u) atomicAdd(state[0], valid);
}
#else
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
#endif
