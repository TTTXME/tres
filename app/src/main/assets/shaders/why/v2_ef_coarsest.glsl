precision highp float; precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Fusion): the reference tone's ef_pyr_coarsest, one to one - the coarsest level's fused luma, the lumas
// weighted by their weights.
uniform sampler2D u_tex_G;
uniform sampler2D u_tex_W;
uniform int u_width;
uniform int u_height;
out vec4 Output;

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) {
        Output = vec4(0.0);
        return;
    }
    vec4 g = texelFetch(u_tex_G, coord, 0);
    vec4 w = texelFetch(u_tex_W, coord, 0);
    float fused = dot(g, w);
    Output = vec4(fused, fused, fused, 1.0);
}
