precision highp float; precision highp sampler2D;
// P479 (speed): for scamhdr/scamrgb's purpleFringe, per 8 x 8 block of the input the largest luminance its taps can see - the
// same tap value (non-finite zeroed, max(0), / white point, Rec.709) over every pixel of the block. A pixel whose taps' blocks
// all stay under 0.59 of the base white has a weight of exactly 0 there (smoothstep(0.6, 0.95, yb / white)), so scamrgb skips
// the 32 taps for it; every other pixel runs them as before.
uniform sampler2D InputBuffer;      // scamrgb's input
uniform vec3 whitePoint;
out vec4 Output;

void main() {
    ivec2 b = ivec2(gl_FragCoord.xy) * 8;
    ivec2 size = textureSize(InputBuffer, 0);
    const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
    vec3 wp = max(whitePoint, vec3(1.0e-6));
    float m = 0.0;
    for (int j = 0; j < 8; j++)
        for (int i = 0; i < 8; i++) {
            ivec2 p = min(b + ivec2(i, j), size - 1);
            vec3 v = texelFetch(InputBuffer, p, 0).rgb;
            v = max(vec3(isnan(v.r) || isinf(v.r) ? 0.0 : v.r, isnan(v.g) || isinf(v.g) ? 0.0 : v.g,
                         isnan(v.b) || isinf(v.b) ? 0.0 : v.b), vec3(0.0)) / wp;
            m = max(m, dot(v, LUMA));
        }
    Output = vec4(m, 0.0, 0.0, 1.0);
}
