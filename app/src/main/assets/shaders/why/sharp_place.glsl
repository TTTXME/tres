precision highp float; precision highp sampler2D;
// P713: WhyLumaSharpen's stripes above 100 MP - rows of one R16F plane into another at an offset: the guide's rows of a stripe
// out of the full plane (originU = -(the stripe's first row)), a stripe's own sharpened rows back into the full result (originU =
// the stripe's first row, drawn over its own rows only). R16F to R16F: the same halves.
uniform sampler2D InputBuffer;
uniform ivec2 originU;
out vec4 Output;

void main() {
    Output = vec4(texelFetch(InputBuffer, ivec2(gl_FragCoord.xy) - originU, 0).r, 0.0, 0.0, 1.0);
}
