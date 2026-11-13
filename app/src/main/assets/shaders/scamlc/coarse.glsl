precision highp float; precision highp sampler2D;
// Block means of a level (4x4 taps per block) written to a small RGBA texture for CPU read-back:
// the noise floor of each band is a low percentile of its local amplitude.
uniform sampler2D InputBuffer;
out vec4 Output;
void main() {
    ivec2 levelSize = textureSize(InputBuffer, 0);
    // this block's corners in the level (the output is a 96 x 72 grid of blocks)
    vec2 block = floor(gl_FragCoord.xy);
    vec2 lo = block / vec2(96.0, 72.0) * vec2(levelSize);
    vec2 hi = (block + vec2(1.0)) / vec2(96.0, 72.0) * vec2(levelSize);
    float total = 0.0;
    for (int ty = 0; ty < 4; ty++)
        for (int tx = 0; tx < 4; tx++) {
            vec2 at = mix(lo, hi, (vec2(float(tx), float(ty)) + 0.5) / 4.0);
            total += texelFetch(InputBuffer, clamp(ivec2(at), ivec2(0), levelSize - ivec2(1)), 0).r;
        }
    Output = vec4(total / 16.0, 0.0, 0.0, 1.0);
}
