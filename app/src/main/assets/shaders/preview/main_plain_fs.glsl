#extension GL_OES_EGL_image_external_essl3 : require
// P705: the ISP viewfinder's plain program - main_fs.glsl with everything off that its uniforms can switch on (no lens filter,
// LUT, Oldstyle style or tone, focus peaking): the same frame, the same gain expression, the same precisions, without the taps,
// derivatives and branches of the parts not drawn (fewer registers on the older GPUs, a shorter compile). MainRenderer takes
// it only while nothing else would be drawn; the output is main_fs's then.
precision highp float;
// as main_fs: a 2D frame (tools/checks/vf_plain_gl.py draws both programs from one) read at full precision
precision highp sampler2D;
in vec2 texCoord;
uniform bool mirror;
uniform samplerExternalOES sTexture;
// P90: a post-shutter series frame brought back to the viewfinder's exposure (ViewfinderGain), 1.0 for viewfinder frames
uniform float uGain;
out vec4 Output;
void main() {
    vec2 uv = texCoord;
    if (mirror) uv.y = 1.0 - uv.y;
    vec4 color = texture(sTexture, uv);
    if (uGain != 1.0) color.rgb = pow(clamp(pow(max(color.rgb, 0.0), vec3(2.2)) * uGain, 0.0, 1.0), vec3(1.0 / 2.2));
    Output = color;
}
