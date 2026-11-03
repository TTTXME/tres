#define LAYOUT //
LAYOUT
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp uimage2D;
// P473 (speed): scamdn/stats of the finish level 0 as one work group per 16 x 16 block (the fragment version reads ~1500 texels
// on one invocation per block) and the level's YUV made here from the linear RGB (scamdn/yuv was a full-size pass of its own):
// each pixel converted once, rounded to half floats as the RGBA16F level stored it, then the same sums in the same order as
// scamdn/stats (one invocation per sub-block, the block's own terms on invocation 0). Block 16 only, no DCG zones.
uniform sampler2D InputBuffer;   // the linear RGB (scamdn/yuv's input)
uniform vec2 modelU;
layout(rgba32ui, binding = 0) uniform highp writeonly uimage2D outStats;
shared vec3 px[256];
shared vec3 subMean[16];
shared vec3 slope[2];
shared float e[96]; // m1, m2, u1, u2, v1, v2 of the 16 sub-blocks
float finite1(float v) { return (isnan(v) || isinf(v)) ? 0.0 : v; }
vec3 half3(vec3 v) { return vec3(unpackHalf2x16(packHalf2x16(v.xy)), unpackHalf2x16(packHalf2x16(vec2(v.z, 0.0))).x); }
float median16(float v[16]) {
    // P364: an odd-even merge sorting network (min / max, no branches), only its comparators that reach elements 7 and 8: the
    // same middle pair as the insertion sort for numbers
    float t;
    t = min(v[0], v[1]); v[1] = max(v[0], v[1]); v[0] = t;
    t = min(v[2], v[3]); v[3] = max(v[2], v[3]); v[2] = t;
    t = min(v[0], v[2]); v[2] = max(v[0], v[2]); v[0] = t;
    t = min(v[1], v[3]); v[3] = max(v[1], v[3]); v[1] = t;
    t = min(v[1], v[2]); v[2] = max(v[1], v[2]); v[1] = t;
    t = min(v[4], v[5]); v[5] = max(v[4], v[5]); v[4] = t;
    t = min(v[6], v[7]); v[7] = max(v[6], v[7]); v[6] = t;
    t = min(v[4], v[6]); v[6] = max(v[4], v[6]); v[4] = t;
    t = min(v[5], v[7]); v[7] = max(v[5], v[7]); v[5] = t;
    t = min(v[5], v[6]); v[6] = max(v[5], v[6]); v[5] = t;
    t = min(v[0], v[4]); v[4] = max(v[0], v[4]); v[0] = t;
    t = min(v[2], v[6]); v[6] = max(v[2], v[6]); v[2] = t;
    t = min(v[2], v[4]); v[4] = max(v[2], v[4]); v[2] = t;
    t = min(v[1], v[5]); v[5] = max(v[1], v[5]); v[1] = t;
    t = min(v[3], v[7]); v[7] = max(v[3], v[7]); v[3] = t;
    t = min(v[3], v[5]); v[5] = max(v[3], v[5]); v[3] = t;
    t = min(v[1], v[2]); v[2] = max(v[1], v[2]); v[1] = t;
    t = min(v[3], v[4]); v[4] = max(v[3], v[4]); v[3] = t;
    t = min(v[5], v[6]); v[6] = max(v[5], v[6]); v[5] = t;
    t = min(v[8], v[9]); v[9] = max(v[8], v[9]); v[8] = t;
    t = min(v[10], v[11]); v[11] = max(v[10], v[11]); v[10] = t;
    t = min(v[8], v[10]); v[10] = max(v[8], v[10]); v[8] = t;
    t = min(v[9], v[11]); v[11] = max(v[9], v[11]); v[9] = t;
    t = min(v[9], v[10]); v[10] = max(v[9], v[10]); v[9] = t;
    t = min(v[12], v[13]); v[13] = max(v[12], v[13]); v[12] = t;
    t = min(v[14], v[15]); v[15] = max(v[14], v[15]); v[14] = t;
    t = min(v[12], v[14]); v[14] = max(v[12], v[14]); v[12] = t;
    t = min(v[13], v[15]); v[15] = max(v[13], v[15]); v[13] = t;
    t = min(v[13], v[14]); v[14] = max(v[13], v[14]); v[13] = t;
    t = min(v[8], v[12]); v[12] = max(v[8], v[12]); v[8] = t;
    t = min(v[10], v[14]); v[14] = max(v[10], v[14]); v[10] = t;
    t = min(v[10], v[12]); v[12] = max(v[10], v[12]); v[10] = t;
    t = min(v[9], v[13]); v[13] = max(v[9], v[13]); v[9] = t;
    t = min(v[11], v[15]); v[15] = max(v[11], v[15]); v[11] = t;
    t = min(v[11], v[13]); v[13] = max(v[11], v[13]); v[11] = t;
    t = min(v[9], v[10]); v[10] = max(v[9], v[10]); v[9] = t;
    t = min(v[11], v[12]); v[12] = max(v[11], v[12]); v[11] = t;
    t = min(v[13], v[14]); v[14] = max(v[13], v[14]); v[13] = t;
    t = min(v[0], v[8]); v[8] = max(v[0], v[8]); v[0] = t;
    t = min(v[4], v[12]); v[12] = max(v[4], v[12]); v[4] = t;
    t = min(v[4], v[8]); v[8] = max(v[4], v[8]); v[4] = t;
    t = min(v[2], v[10]); v[10] = max(v[2], v[10]); v[2] = t;
    t = min(v[6], v[14]); v[14] = max(v[6], v[14]); v[6] = t;
    t = min(v[6], v[10]); v[10] = max(v[6], v[10]); v[6] = t;
    t = min(v[6], v[8]); v[8] = max(v[6], v[8]); v[6] = t;
    t = min(v[1], v[9]); v[9] = max(v[1], v[9]); v[1] = t;
    t = min(v[5], v[13]); v[13] = max(v[5], v[13]); v[5] = t;
    t = min(v[5], v[9]); v[9] = max(v[5], v[9]); v[5] = t;
    t = min(v[3], v[11]); v[11] = max(v[3], v[11]); v[3] = t;
    t = min(v[7], v[15]); v[15] = max(v[7], v[15]); v[7] = t;
    t = min(v[7], v[11]); v[11] = max(v[7], v[11]); v[7] = t;
    t = min(v[7], v[9]); v[9] = max(v[7], v[9]); v[7] = t;
    t = min(v[7], v[8]); v[8] = max(v[7], v[8]); v[7] = t;
    return 0.5 * (v[7] + v[8]);
}

void main() {
    const int sub = 4;
    vec2 model = modelU.x > 0.0 ? modelU : vec2(1.0e-4, 1.0e-6);
    ivec2 size = textureSize(InputBuffer, 0);
    ivec2 blk = ivec2(gl_WorkGroupID.xy), o = blk * 16;
    int lid = int(gl_LocalInvocationIndex);
    bool inside = o.x + 4 * sub <= size.x && o.y + 4 * sub <= size.y;
    {
        ivec2 p = min(o + ivec2(lid % 16, lid / 16), size - 1);
        vec3 c = texelFetch(InputBuffer, p, 0).rgb;
        c = vec3(finite1(c.r), finite1(c.g), finite1(c.b));
        px[lid] = half3(vec3(dot(c, vec3(0.2126, 0.7152, 0.0721996)),
                             dot(c, vec3(-0.162450244, -0.546494309, 0.708944715)),
                             dot(c, vec3(0.999996748, -0.908302439, -0.091693333))));
    }
    barrier();
    if (lid < 16) {
        int si = lid % 4, sj = lid / 4;
        vec3 s = vec3(0.0);
        for (int j = 0; j < sub; j++)
            for (int i = 0; i < sub; i++)
                s += px[(sj * sub + j) * 16 + si * sub + i];
        subMean[lid] = s / float(sub * sub);
    }
    barrier();
    float meanY = 0.0, var = 1.0, t = 0.0;
    if (lid == 0) {
        vec3 sx = vec3(0.0), sy = vec3(0.0), tot = vec3(0.0);
        float m0 = 0.0, sumM = 0.0, sumM2 = 0.0;
        for (int sj = 0; sj < 4; sj++) {
            for (int si = 0; si < 4; si++) {
                vec3 m = subMean[sj * 4 + si];
                tot += m;
                sx += (float(si) - 1.5) * m;
                sy += (float(sj) - 1.5) * m;
                if (sj == 0 && si == 0) m0 = m.x;
                sumM += m.x - m0;
                sumM2 += (m.x - m0) * (m.x - m0);
            }
        }
        sx /= 20.0 * float(sub);
        sy /= 20.0 * float(sub);
        slope[0] = sx; slope[1] = sy;
        meanY = tot.x / 16.0;
        var = max(model.x * max(meanY, 0.0) + model.y, 1.0e-12);
        float subVar = max(sumM2 / 16.0 - (sumM / 16.0) * (sumM / 16.0), 0.0);
        t = min(subVar * float(sub * sub) / var, 60000.0);
    }
    barrier();
    if (lid < 16) {
        int si = lid % 4, sj = lid / 4;
        vec3 sx = slope[0], sy = slope[1];
        vec3 e1 = vec3(0.0), e2 = vec3(0.0);
        float n1 = float(2 * sub * (sub - 1)), n2 = float(2 * sub * (sub - 2));
        for (int j = 0; j < sub; j++) {
            for (int i = 0; i < sub; i++) {
                int q = (sj * sub + j) * 16 + si * sub + i;
                vec3 c = px[q];
                vec3 d;
                if (i + 1 < sub) { d = px[q + 1] - c - sx; e1 += d * d; }
                if (j + 1 < sub) { d = px[q + 16] - c - sy; e1 += d * d; }
                if (i + 2 < sub) { d = px[q + 2] - c - 2.0 * sx; e2 += d * d; }
                if (j + 2 < sub) { d = px[q + 32] - c - 2.0 * sy; e2 += d * d; }
            }
        }
        e1 /= n1;
        e2 /= n2;
        e[lid] = e1.x; e[16 + lid] = e2.x; e[32 + lid] = e1.y; e[48 + lid] = e2.y; e[64 + lid] = e1.z; e[80 + lid] = e2.z;
    }
    barrier();
    if (lid == 0) {
        if (!inside) { imageStore(outStats, blk, uvec4(packHalf2x16(vec2(-1.0, 0.0)), 0u, 0u, 0u)); return; }
        float a[16], b[16];
        float norm = 1.0 / (0.945 * var);
        for (int k = 0; k < 16; k++) { a[k] = e[k]; b[k] = e[16 + k]; }
        vec2 gY = min(vec2(median16(a), median16(b)) * norm, vec2(60000.0));
        for (int k = 0; k < 16; k++) { a[k] = e[32 + k]; b[k] = e[48 + k]; }
        vec2 gU = min(vec2(median16(a), median16(b)) * norm, vec2(60000.0));
        for (int k = 0; k < 16; k++) { a[k] = e[64 + k]; b[k] = e[80 + k]; }
        vec2 gV = min(vec2(median16(a), median16(b)) * norm, vec2(60000.0));
        imageStore(outStats, blk, uvec4(packHalf2x16(vec2(meanY, t)), packHalf2x16(gY), packHalf2x16(gU), packHalf2x16(gV)));
    }
}
