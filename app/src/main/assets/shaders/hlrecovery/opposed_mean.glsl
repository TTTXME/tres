#define LAYOUT //
LAYOUT
precision highp float;
precision highp int;
precision highp image2D;
// P479b (speed, owner 2026-11-03: <= 1 level of the JPEG): ScamRgb.opposedMeans on the GPU - hlrecovery/opposed's per-group
// (sum r, g, b as float bits, count r, g, b) added by one work group of 256 (each invocation a strided run of groups, then a
// pairwise tree in shared memory; float sums instead of the CPU's double, the counts exact), the per-channel mean (0 for a channel
// with no pixel) into a 1 x 1 rgba32f image that scamhdr/scamrgb reads (OPP_GPU) - no read-back, no wait for the GPU.
layout(std430, binding = 1) readonly buffer oppSums { uint sums[]; };
layout(rgba32f, binding = 0) uniform highp writeonly image2D outMean;
uniform int groupsU;
shared vec3 sSum[256];
shared uvec3 sCount[256];

void main() {
    uint li = gl_LocalInvocationIndex;
    vec3 sum = vec3(0.0);
    uvec3 count = uvec3(0u);
    for (int g = int(li); g < groupsU; g += 256) {
        for (int c = 0; c < 3; c++) {
            float v = uintBitsToFloat(sums[6 * g + c]);
            if (!isnan(v) && !isinf(v)) sum[c] += v;
            count[c] += sums[6 * g + 3 + c];
        }
    }
    sSum[li] = sum;
    sCount[li] = count;
    memoryBarrierShared(); barrier();
    for (uint s = 128u; s > 0u; s >>= 1) {
        if (li < s) {
            sSum[li] += sSum[li + s];
            sCount[li] += sCount[li + s];
        }
        memoryBarrierShared(); barrier();
    }
    if (li == 0u) {
        vec3 n = vec3(sCount[0]);
        vec3 m = vec3(sCount[0].r > 0u ? sSum[0].r / n.r : 0.0, sCount[0].g > 0u ? sSum[0].g / n.g : 0.0,
                      sCount[0].b > 0u ? sSum[0].b / n.b : 0.0);
        imageStore(outMean, ivec2(0), vec4(m, 1.0));
    }
}
