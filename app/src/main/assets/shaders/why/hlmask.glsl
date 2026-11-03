precision highp float;
precision highp sampler2D;
// P154 «Мягкость засветов»: the blurred share of the data ceiling that WhyCombine's highlight landing follows (WhyHl).
//  dirU (0, 0): the max channel of the colour source (whyLow) over the ceiling, one value per texel;
//  dirU (1, 0) / (0, 1): one axis of a gaussian of sigmaU texels (clamped edges) of that share.
// The landing reads it bilinearly: a blown light lifts its bright surroundings gradually with distance, so the light
// fades into white instead of the white plateau standing on a grey edge (owner, X300 Ultra window, 2026-10-16).
uniform sampler2D InputBuffer;
uniform float invClipU;              // 1 / data ceiling
uniform ivec2 dirU;
uniform float sigmaU;                // texels
// P479b (speed, owner 2026-11-03: <= 1 level of the JPEG): 1 = the gaussian's weights from WhyCombine (weightsU, packed 4 a vec4,
// radiusU taps a side) and the two sides of each tap added first - no exp per tap and per pixel; "hlmask_fast 0": as before
#define FAST 0
#if FAST == 1
uniform vec4 weightsU[40];
uniform int radiusU;
#endif
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
    if (dirU == ivec2(0)) {
        vec3 c = texelFetch(InputBuffer, xy, 0).rgb;
        float m = max(c.r, max(c.g, c.b));
        Output = vec4(clamp(m * invClipU, 0.0, 4.0), 0.0, 0.0, 1.0);
        return;
    }
#if FAST == 1
    float sum = texelFetch(InputBuffer, xy, 0).r * weightsU[0].x;
    float weight = weightsU[0].x;
    for (int i = 1; i <= radiusU; i++) {
        float w = weightsU[i >> 2][i & 3];
        sum += (texelFetch(InputBuffer, clamp(xy - dirU * i, ivec2(0), size - 1), 0).r
                + texelFetch(InputBuffer, clamp(xy + dirU * i, ivec2(0), size - 1), 0).r) * w;
        weight += 2.0 * w;
    }
    Output = vec4(sum / weight, 0.0, 0.0, 1.0);
#else
    float sigma = max(sigmaU, 0.5);
    int radius = int(ceil(3.0 * sigma));
    float inv = -0.5 / (sigma * sigma);
    float sum = 0.0;
    float weight = 0.0;
    for (int i = -radius; i <= radius; i++) {
        ivec2 p = clamp(xy + dirU * i, ivec2(0), size - 1);
        float w = exp(float(i * i) * inv);
        sum += texelFetch(InputBuffer, p, 0).r * w;
        weight += w;
    }
    Output = vec4(sum / weight, 0.0, 0.0, 1.0);
#endif
}
