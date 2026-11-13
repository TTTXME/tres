precision highp float; precision highp sampler2D;
// Local amplitude of a band: root of the Gaussian-weighted mean square of a Laplacian level
// (5x5 at the level's own resolution, so the window grows with the scale of the band).
uniform sampler2D InputBuffer;
out float Output;
void main() {
    ivec2 centre = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float energy = 0.0, weight = 0.0;
    for (int dy = -2; dy <= 2; dy++)
        for (int dx = -2; dx <= 2; dx++) {
            float v = texelFetch(InputBuffer, clamp(centre + ivec2(dx, dy), ivec2(0), last), 0).r;
            float w = exp(-float(dx * dx + dy * dy) * 0.25);
            energy += w * v * v;
            weight += w;
        }
    Output = sqrt(energy / weight);
}
