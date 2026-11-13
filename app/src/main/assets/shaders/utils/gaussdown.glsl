precision highp float;
precision highp sampler2D;
// One Gaussian pyramid level (GLUtils.gaussianDownsample): the input low-passed and resampled at the output's grid. Each
// output texel is centred where pyramiddiff's bilinear upsampling puts it, (out + 0.5) * scale - 0.5 (gl_FragCoord carries
// the + 0.5), so the Laplacian bands fuse without ringing; sigma follows the new sample spacing (no aliasing at larger
// scales) and the truncated separable weights are renormalised (the DC level stays).
uniform sampler2D InputBuffer;
uniform ivec2 inputSize;
uniform float downscaleFactor;
out vec4 result;

#define TAPS_MAX 9 // radius <= ceil(2 sigma) <= 4 for a scale <= 4

// Normalised 1D Gaussian weights of the taps -radius .. radius around a centre `phase` past the origin texel.
void weights(float phase, float sigma, int radius, out float w[TAPS_MAX]) {
    int taps = 2 * radius + 1;
    float total = 0.0;
    for (int k = 0; k < taps; k++) {
        float d = float(k - radius) + phase;
        w[k] = exp(-0.5 * d * d / (sigma * sigma));
        total += w[k];
    }
    for (int k = 0; k < taps; k++) w[k] /= total;
}

void main() {
    vec2 centre = vec2(gl_FragCoord.xy) * downscaleFactor - vec2(0.5);
    float sigma = max(0.75, 0.5 * downscaleFactor);
    int radius = min(int(ceil(2.0 * sigma)), (TAPS_MAX - 1) / 2);
    ivec2 origin = ivec2(floor(centre));
    vec2 phase = centre - vec2(origin);
    float wx[TAPS_MAX];
    float wy[TAPS_MAX];
    weights(phase.x, sigma, radius, wx);
    weights(phase.y, sigma, radius, wy);
    ivec2 last = inputSize - 1;
    vec4 acc = vec4(0.0);
    for (int ty = 0; ty < 2 * radius + 1; ty++) {
        int y = clamp(origin.y + ty - radius, 0, last.y);
        vec4 line = vec4(0.0);
        for (int tx = 0; tx < 2 * radius + 1; tx++) {
            line += texelFetch(InputBuffer, ivec2(clamp(origin.x + tx - radius, 0, last.x), y), 0) * wx[tx];
        }
        acc += line * wy[ty];
    }
    result = acc;
}
