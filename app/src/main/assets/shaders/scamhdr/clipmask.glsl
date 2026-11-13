precision highp float; precision highp sampler2D;
// P479 (speed): the vertical half of scamhdr/clipband's two flag dilations (its "zone" and "reach" tests), the same taps and
// the same clamping per axis, so clipband's horizontal half ORs exactly the 2-D window it tested pixel by pixel before (74 R8
// fetches a pixel there, 12 here + 12 there). Normalised R8 out: bit 0 = a flagged pixel (flagAt & 15) within zoneU rows,
// bit 1 = one on the reach taps (every rs rows within radiusU), bit 2 (P537) = a pixel with a clipped mean (flag bits 0-2) on
// the glow taps (every gs rows within glowRadiusU; 0 = none).
uniform sampler2D ClipFlags;        // worker flags, normalised R8 (code = r * 255)
uniform int radiusU;                // clipband's window radius (<= 0 -> 3)
uniform int zoneU;                  // clipband's band reach (<= 0 -> 2)
uniform int clipHiUnflaggedU;       // as clipband
uniform int glowRadiusU;            // P537: clipband's glow reach (0 = off)
out vec4 Output;

uint flagAt(ivec2 p) {
    uint f = uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5);
    if (clipHiUnflaggedU != 0 && (f & 16u) != 0u && (f & 7u) == 0u) f &= ~8u;
    return f;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int last = textureSize(ClipFlags, 0).y - 1;
    int r = radiusU > 0 ? radiusU : 3;
    int z = zoneU > 0 ? zoneU : 2;
    bool zone = false;
    for (int dy = -z; dy <= z && !zone; dy++)
        zone = (flagAt(ivec2(xy.x, clamp(xy.y + dy, 0, last))) & 15u) != 0u;
    bool reach = false;
    int rs = max(1, (r + 2) / 3);
    for (int dy = -r; dy <= r && !reach; dy += rs)
        reach = (flagAt(ivec2(xy.x, clamp(xy.y + dy, 0, last))) & 15u) != 0u;
    bool glow = false;
    int gs = max(1, (glowRadiusU + 2) / 3);
    for (int dy = -glowRadiusU; dy <= glowRadiusU && !glow && glowRadiusU > 0; dy += gs)
        glow = (flagAt(ivec2(xy.x, clamp(xy.y + dy, 0, last))) & 7u) != 0u;
    Output = vec4(float((zone ? 1 : 0) + (reach ? 2 : 0) + (glow ? 4 : 0)) / 255.0, 0.0, 0.0, 1.0);
}
