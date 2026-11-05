#define MODE 0
#define LAYOUT //
LAYOUT
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
// P479 (speed): scamhdr/suncore MODE 0 / MODE 2's block sums as a compute pass - one invocation per pixel of the block (256
// at a time, in the block's row order), the sums on invocation 0 in suncore's order (rows, then columns), the same terms -
// instead of one fragment per block looping over its blockU x blockU pixels (latency-bound: 49 k fragments of 256 fetches at
// 12 MP). Out (rgba32f): n, c (exact integers), gs, gw; suncore MODE 4 / 5 turn them into MODE 0 / 2's output, the same code.
// Keep the per-pixel tests in sync with scamhdr/suncore.
uniform sampler2D InputBuffer;  // MODE 0: the clip flags (R8 normalised); MODE 2: scamrgb's input
#if MODE == 2
uniform sampler2D ClipFlags;
uniform int clipFlagsU;
uniform vec3 clipLoU;
uniform vec3 clipHiU;
uniform mat3 toRenderU;
#endif
uniform int blockU;
uniform int noShortU;          // P506: 1 = the shot took no short frames: a core clipped in every channel counts without bit 5
layout(rgba32f, binding = 0) uniform highp writeonly image2D outCells;
shared vec4 terms[256]; // per pixel: counted, in c, glow weight w, its saturation term (w <= 0: no glow)

void main() {
    ivec2 cell = ivec2(gl_WorkGroupID.xy);
    int li = int(gl_LocalInvocationIndex);
    int b = blockU, total = blockU * blockU;
    ivec2 size = textureSize(InputBuffer, 0);
#if MODE == 2
    vec3 lc = clipLoU.g > 0.0 ? clipLoU : clipHiU;
    bool level = lc.g > 0.0 && (clipFlagsU != 0 || clipHiU.g <= 0.0);
#endif
    int n = 0, c = 0;
    float gw = 0.0, gs = 0.0;
    for (int base = 0; base < total; base += 256) {
        vec4 t = vec4(0.0);
        int k = base + li;
        if (k < total) {
            int j = k / b, i = k - j * b;
            ivec2 p = cell * b + ivec2(i, j);
            if (p.x < size.x && p.y < size.y) {
                t.x = 1.0;
#if MODE == 0
                int f = int(texelFetch(InputBuffer, p, 0).r * 255.0 + 0.5);
                if ((f & 7) == 7 && ((f & 32) != 0 || noShortU != 0)) t.y = 1.0;
#else
                if (level) {
                    int f = clipFlagsU != 0 ? int(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5) : 0;
                    vec3 v = texelFetch(InputBuffer, p, 0).rgb;
                    if (any(greaterThanEqual(v, 0.987 * lc))) {
                        if (!(clipFlagsU != 0 && ((f & 15) == 0 || (f & 48) != 0)) && all(greaterThanEqual(v, 0.987 * lc))) t.y = 1.0;
                    } else if ((f & 15) == 0) {
                        vec3 r = v / lc;
                        float w = smoothstep(0.6, 0.95, max(r.r, max(r.g, r.b)));
                        if (w > 0.0) {
                            vec3 col = toRenderU * v;
                            float hi = max(col.r, max(col.g, col.b));
                            t.z = w;
                            t.w = 1.0 - min(col.r, min(col.g, col.b)) / max(hi, 0.000001);
                        }
                    }
                }
#endif
            }
        }
        terms[li] = t;
        barrier();
        if (li == 0) {
            int m = min(256, total - base);
            for (int q = 0; q < m; q++) {
                vec4 e = terms[q];
                if (e.x <= 0.0) continue;
                n++;
                if (e.y > 0.0) c++;
                if (e.z > 0.0) {
                    gs += e.z * e.w;
                    gw += e.z;
                }
            }
        }
        barrier();
    }
    if (li == 0) imageStore(outCells, cell, vec4(float(n), float(c), gs, gw));
}
