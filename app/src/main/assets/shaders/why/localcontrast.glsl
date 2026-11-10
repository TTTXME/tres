precision highp float;
precision highp sampler2D;
// P596 (owner, 2026-11-10, «Локальный контраст»: 140309's facade by stage - after the whole chain Ark leads 1.12 - 1.22 at
// 0.03 - 0.15 cycles / px, local contrast, not fine detail): the fused display luma (WhyFusion, whyLow grid) gains its local
// contrast against a gaussian of itself, F + c D (1 - smoothstep(t, 3 t, |D|)), D = F - G(F): small differences (texture,
// wall panels, foliage) x (1 + c), large ones (an edge against a light, a branch against the sky) left as they are - no halo
// beyond c t. WhyCombine runs it on the photo (WhyTone.localContrast; 0 = not run, the fused luma as before).
//  MODE 0: one axis (dirU) of the gaussian of sigmaU texels of InputBuffer's .r (clamped edges);
//  MODE 1: InputBuffer = the fused luma, Blurred = its gaussian, lcU = (c, t).
uniform sampler2D InputBuffer;
uniform sampler2D Blurred;
uniform ivec2 dirU;
uniform float sigmaU;
uniform vec2 lcU;
#define MODE 0
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
#if MODE == 1
    float f = texelFetch(InputBuffer, xy, 0).r;
    float d = f - texelFetch(Blurred, xy, 0).r;
    Output = vec4(f + lcU.x * d * (1.0 - smoothstep(lcU.y, 3.0 * lcU.y, abs(d))), 0.0, 0.0, 1.0);
#else
    ivec2 size = textureSize(InputBuffer, 0);
    int r = int(ceil(3.0 * sigmaU));
    float k = -0.5 / (sigmaU * sigmaU);
    float sum = texelFetch(InputBuffer, xy, 0).r, weight = 1.0;
    for (int i = 1; i <= r; i++) {
        float w = exp(k * float(i * i));
        sum += (texelFetch(InputBuffer, clamp(xy - dirU * i, ivec2(0), size - 1), 0).r
                + texelFetch(InputBuffer, clamp(xy + dirU * i, ivec2(0), size - 1), 0).r) * w;
        weight += 2.0 * w;
    }
    Output = vec4(sum / weight, 0.0, 0.0, 1.0);
#endif
}
