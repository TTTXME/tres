precision highp float;
precision highp int;
precision highp sampler2D;
// P630c «Oldstyle» (OldstyleStyleNode): the mode's style on the finished photo (display values), the viewfinder's function
// (utils/import_oldstyle_adjust): OS_TAP reads the picture at whole-pixel offsets, the edge repeated.
uniform sampler2D InputBuffer;
out vec4 Output;
#define OS_TAP(off) texelFetch(InputBuffer, clamp(ivec2(gl_FragCoord.xy + (off)), ivec2(0), textureSize(InputBuffer, 0) - 1), 0).rgb
#import oldstyle_adjust

void main() {
    vec4 c = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0);
    Output = vec4(oldstyleStyle(c.rgb, floor(gl_FragCoord.xy), vec2(textureSize(InputBuffer, 0))), c.a);
}
