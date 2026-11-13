// SCAM: smooth upsampling of small maps (lens shading, gain and luma grids): a uniform cubic B-spline over the texel centres,
// read with four taps of the hardware's bilinear filter instead of sixteen point reads. The sampler must filter LINEAR.
//
// Along one axis a sample at fraction f of its cell weighs the texels -1, 0, +1 and +2 with the B-spline basis below; the
// pairs (-1, 0) and (+1, +2) each collapse into one bilinear read placed between the two texels in proportion to their
// weights, so the four reads at the pair centres (x and y) and one blend per axis give the full 4 x 4 filter.

// B-spline weights of the texels -1, 0, +1, +2 for a sample at fraction x of the cell between texels 0 and +1 (sum 1).
vec4 cubic(float x)
{
    float sq = x * x;
    float cu = sq * x;
    vec4 basis = vec4(-cu + 3.0 * sq - 3.0 * x + 1.0,
                      3.0 * cu - 6.0 * sq + 4.0,
                      -3.0 * cu + 3.0 * sq + 3.0 * x + 1.0,
                      cu);
    return basis / 6.0;
}

// The map at texCoords (0..1 of the texture), B-spline filtered.
vec4 textureBicubicHardware(sampler2D sampler, vec2 texCoords)
{
    vec2 size = vec2(textureSize(sampler, 0));
    vec2 texel = texCoords * size - 0.5;      // in texel units, 0 at the centre of texel 0
    vec2 frac = fract(texel);
    vec2 cell = texel - frac;                 // the texel at or left of / below the sample
    vec4 wx = cubic(frac.x);
    vec4 wy = cubic(frac.y);
    // the weight of each texel pair: (x -1/0, x +1/+2, y -1/0, y +1/+2)
    vec4 pair = vec4(wx.x + wx.y, wx.z + wx.w, wy.x + wy.y, wy.z + wy.w);
    // where each pair's bilinear read goes: its first texel's edge plus the share of its second texel, in 0..1 units
    vec4 edge = cell.xxyy + vec4(-0.5, 1.5, -0.5, 1.5);
    vec4 at = (edge + vec4(wx.y, wx.w, wy.y, wy.w) / pair) * (1.0 / size).xxyy;
    // the share of the near pair (-1/0) on each axis
    vec2 near = vec2(pair.x / (pair.x + pair.y), pair.z / (pair.z + pair.w));
    vec4 above = mix(texture(sampler, at.yw), texture(sampler, at.xw), near.x);
    vec4 below = mix(texture(sampler, at.yz), texture(sampler, at.xz), near.x);
    return mix(above, below, near.y);
}
