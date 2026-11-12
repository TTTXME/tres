precision highp float;
precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Stats): the samples the reference's histogram_cs reads - the clean RGB at gid x u_stride - written to a
// small target (one texel per sample) for the CPU histograms of WhyV2Ae (the same samples, the same bins, counted exactly).
uniform sampler2D u_tex_rgb;        // v2_clean
uniform int u_stride;               // WhyV2Ae.stride
out vec4 Output;

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy) * max(u_stride, 1);
    Output = vec4(texelFetch(u_tex_rgb, coord, 0).rgb, 1.0);
}
