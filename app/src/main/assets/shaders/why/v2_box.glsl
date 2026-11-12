precision highp float;
precision highp int;
precision highp sampler2D;
// P614 «Why тон» V2 (WhyV2Highlights): the reference tone's box_blur_h4 (DIR 0) / box_blur_v4 (DIR 1), one to one - a box of 2 u_radius + 1 texels along a row / a column, clamped to the edge.
#define DIR 0
uniform sampler2D u_input_tex;
uniform int u_width; uniform int u_height; uniform int u_radius;
out vec4 Output;
void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    if (coord.x >= u_width || coord.y >= u_height) { Output = vec4(0.0); return; }
    vec4 sum = vec4(0.0);
    int count = 0;
    for (int i = -u_radius; i <= u_radius; i++) {
#if DIR == 0
        int nx = clamp(coord.x + i, 0, u_width - 1);
        sum += texelFetch(u_input_tex, ivec2(nx, coord.y), 0);
#else
        int ny = clamp(coord.y + i, 0, u_height - 1);
        sum += texelFetch(u_input_tex, ivec2(coord.x, ny), 0);
#endif
        count++;
    }
    Output = (count > 0) ? (sum / float(count)) : vec4(0.0);
}
