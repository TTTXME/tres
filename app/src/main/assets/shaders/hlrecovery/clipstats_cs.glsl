#define LAYOUT //
LAYOUT
precision highp float; precision highp int; precision highp sampler2D;
// P482 (speed, owner 2026-11-03): ScamRgb.channelClip's walk on the GPU, exactly - every 17th pixel of the uploaded half-float RGB;
// a sample's histogram bin comes from the CPU's own table (binLut: the bin of every half value, -1 none, -2 NaN), its value
// is counted as an integer of 2^-11 (every half in [0.8, 1.1) is one), so the per-bin counts and sums equal the CPU's double
// sums; the channel maxima as the bits of positive floats (their order is the floats' order).
uniform sampler2D InputBuffer;
layout(std430, binding = 1) readonly buffer binLut { int bins[]; };
layout(std430, binding = 2) buffer clipStats { uint stats[]; }; // counts [3][150], sums [3][150], max bits [3], NaN samples
uniform int widthU;
uniform int samplesU;

void main() {
    int s = int(gl_GlobalInvocationID.x);
    if (s >= samplesU) return;
    int i = s * 17;
    vec3 v = texelFetch(InputBuffer, ivec2(i % widthU, i / widthU), 0).rgb;
    for (int c = 0; c < 3; c++) {
        int b = bins[int(packHalf2x16(vec2(v[c], 0.0)) & 0xffffu)];
        if (b == -2) { atomicAdd(stats[903], 1u); continue; }
        if (v[c] > 0.0) atomicMax(stats[900 + c], floatBitsToUint(v[c]));
        if (b >= 0) {
            atomicAdd(stats[c * 150 + b], 1u);
            atomicAdd(stats[450 + c * 150 + b], uint(v[c] * 2048.0 + 0.5));
        }
    }
}
