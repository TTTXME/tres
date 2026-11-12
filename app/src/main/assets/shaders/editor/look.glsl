// P619: the camera's LUT / film colour on the editor's finished picture (EditLook, FinishedLook): the main pass's display-encoded
// sRGB through the camera's display table, looked up as the camera's ISP viewfinder looks it up (preview/main_fs.glsl uLutOn: the
// texel centres, the file's domain) and mixed by the strength - the film's (its table holds the film at 1) or the LUT's.
uniform sampler2D uImg;
uniform highp sampler3D uLut;
uniform float uLutStrength;
uniform float uLutSize;
uniform vec3 uLutMin;
uniform vec3 uLutScale;
out vec4 o;

void main() {
    vec3 color = texelFetch(uImg, ivec2(gl_FragCoord.xy), 0).rgb;
    vec3 lc = (clamp((clamp(color.rgb, 0.0, 1.0) - uLutMin) * uLutScale, 0.0, 1.0) * (uLutSize - 1.0) + 0.5) / uLutSize;
    color.rgb = mix(color.rgb, texture(uLut, lc).rgb, uLutStrength);
    o = vec4(color, 1.0);
}
