precision highp float; precision highp int; precision highp sampler2D;
// P711 (owner, 2026-11-14: «добавь оптимизацию live viewfinder, он должен хорошо работать даже на Snapdragon 865»): the RAW
// viewfinder's linear frame for the photo's WHY tone (LiveWhyTone vf_why 1, P314's first pass) - rawdevelop_fs.glsl u_linear_out 1
// in a pass of its own, from textureGather (GLSL ES 3.10, as why/combine): a plain Bayer frame's range-filtered quad is 5 gathers
// instead of rawdevelop_fs's 20 fetches (import_vfraw.glsl: the same sites, the same arithmetic, the same RAW_TIE; its copies of
// rawdevelop_fs's functions are kept identical by tools/check_vf_fused.py), a frame of cell means 1 instead of 4, and none of
// rawdevelop_fs's own look (its uniforms, its 650 lines' registers). The same uniforms as rawdevelop_fs (LiveRawRenderer.bindFrame:
// the RAW on unit 9, the lens-shading map on unit 10) and preview/rawdevelop_vs.glsl as its vertex shader (as the fused pass's).
// tools/checks/vf_linear_gl.py: its RGBA16F output against rawdevelop_fs u_linear_out 1, frame by frame.
in vec2 texCoord;
out vec4 FragColor;
// P370 (B2): 1 = a colour outside sRGB keeps its negative channel (rawdevelop_fs u_signed_hue)
uniform int u_signed_hue;
#import vfraw
void main() {
    vec3 lin = vfLinearSigned(texCoord);
    FragColor = vec4(u_signed_hue == 1 ? lin : max(lin, vec3(0.0)), 1.0);
}
