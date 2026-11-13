precision highp float; precision highp sampler2D;
// The last pass of the post pipeline (RotateWatermark): the processed frame turned upright - rotate = quarter turns of the
// output against the sensor rows (0, 3 = a 90 degree photo, 2, 1 = 270) - mirrored for the front camera, and the crop's rows
// below the ones the sensor delivered beyond it skipped; clamped to 0..1, opaque. P387: every index stays inside the texture
// (size - 1 is its last texel; texelFetch outside it gave the black edge column of the portrait photos up to build 31136).
// P628: the watermark is drawn into the finished photo afterwards (PhotoWatermark), not here.
uniform sampler2D InputBuffer;
uniform int yOffset, rotate;
uniform bool mirror;
uniform ivec2 cropSize, rawSize;
out vec4 Output;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) + ivec2(0, yOffset);
    ivec2 last = ivec2(textureSize(InputBuffer, 0)) - 1;
    int skipped = rawSize.y - cropSize.y;
    ivec2 at;
    if (rotate == 1) {
        p.x += skipped;
        if (mirror) p.x = cropSize.y - 1 - p.x;
        at = ivec2(last.x - p.y, p.x);
    } else if (rotate == 2) {
        if (mirror) p.y = last.y - p.y;
        at = last - p;
    } else if (rotate == 3) {
        if (mirror) p.x = cropSize.y - 1 - p.x;
        at = ivec2(p.y, last.y - p.x);
    } else {
        p.y += skipped;
        if (mirror) p.y = last.y - p.y;
        at = p;
    }
    Output = vec4(clamp(texelFetch(InputBuffer, at, 0).rgb, 0.0, 1.0), 1.0);
}
