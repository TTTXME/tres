precision highp float;
precision highp sampler2D;
// P630b «Oldstyle» (OldstyleTone): the module's RGB8 picture (an RGB8 texture) into the pipeline's main texture: code / 255.
uniform sampler2D InputBuffer;
out vec4 Output;

void main() {
    Output = vec4(texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
}
