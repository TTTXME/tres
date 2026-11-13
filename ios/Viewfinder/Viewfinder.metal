// The live viewfinder's look on the camera's frames: Android's ISP viewfinder pass (shaders/preview/main_fs.glsl with
// lensfilter_live.glsl spliced in, and lensfilter_prep.glsl), ported to Metal for the AVCaptureVideoDataOutput frames:
//  - the lens filter's diffusion (the only filters offered: Glimmerglass, Black Pro-Mist, Pro-Mist, CineBloom, and the film's own
//    diffusion) from the half-size blur pyramid, with DiffusionModel's level weights (Viewfinder/ViewfinderLook.mm);
//  - the film / LUT table (FilmChain) on the display values, mixed by its strength;
//  - focus peaking on the camera's frame itself.
// The frame arrives upright (the output connection's rotation) and mirrored for the front lens (its mirroring), as Android's
// texture after uTexRotateMatrix / mirror.

#include <metal_stdlib>
using namespace metal;

struct VfOut {
    float4 position [[position]];
    float2 uv;     // the frame's coordinate (0..1, y down)
    float2 screen; // the picture's coordinate (0..1, y down)
};

// u[0]: xy uv scale (aspect fit around the centre), zw unused
// u[1]: xy resolution (drawable pixels), z LUT size, w LUT strength
// u[2]: xyz LUT domain min
// u[3]: xyz 1 / (LUT domain max - min)
// u[4]: x LUT on, y lens filter levels (0: no pyramid), z peaking, w lens filter on
// u[5]: x lens filter strength, y 1: the frame is Display P3 (brought to sRGB, as Android's ISP frame and the LUT's input)
// u[6]: the frame's own diffusion weight (W0), u[7..18]: the weights of mip levels 0..11
constant int VF_U_W0 = 6;

vertex VfOut vf_vertex(uint vid [[vertex_id]], constant float4 *u [[buffer(0)]]) {
    const float2 corners[4] = {float2(-1, -1), float2(1, -1), float2(-1, 1), float2(1, 1)};
    const float2 p = corners[vid];
    VfOut o;
    o.position = float4(p, 0, 1);
    o.screen = float2((p.x + 1) * 0.5, (1 - p.y) * 0.5);
    o.uv = (o.screen - 0.5) * u[0].xy + 0.5;
    return o;
}

static float3 lfLin(float3 c) { return pow(max(c, float3(0.0)), float3(2.2)); }
static float3 lfEnc(float3 c) { return pow(max(c, float3(0.0)), float3(1.0 / 2.2)); }
static float lfLuma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

// lensfilter_live.glsl lfLevel: level k of the pyramid at p, upsampled with a cubic B-spline from four bilinear taps
static float3 lfLevel(texture2d<float> t, sampler sm, float2 p, int k) {
    const float2 size = float2(t.get_width(uint(k)), t.get_height(uint(k)));
    const float2 q = p * size - 0.5;
    const float2 i = floor(q);
    const float2 f = q - i;
    const float2 f2 = f * f, f3 = f2 * f;
    const float2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    const float2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    const float2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    const float2 w3 = f3 / 6.0;
    const float2 g0 = w0 + w1, g1 = w2 + w3;
    const float2 h0 = (i - 0.5 + w1 / g0) / size, h1 = (i + 1.5 + w3 / g1) / size;
    const float lod = float(k);
    return g0.y * (g0.x * t.sample(sm, float2(h0.x, h0.y), level(lod)).rgb + g1.x * t.sample(sm, float2(h1.x, h0.y), level(lod)).rgb)
         + g1.y * (g0.x * t.sample(sm, float2(h0.x, h1.y), level(lod)).rgb + g1.x * t.sample(sm, float2(h1.x, h1.y), level(lod)).rgb);
}

static bool inside(float2 uv) { return all(uv >= 0.0) && all(uv <= 1.0); }

static float3 srgbDecode(float3 v) {
    v = clamp(v, 0.0, 1.0);
    return select(pow((v + 0.055) / 1.055, float3(2.4)), v / 12.92, v <= 0.04045);
}
static float3 srgbEncode(float3 v) {
    v = clamp(v, 0.0, 1.0);
    return select(1.055 * pow(v, float3(1.0 / 2.4)) - 0.055, v * 12.92, v <= 0.0031308);
}

// the camera's Display P3 frame (both use the sRGB transfer) in sRGB primaries, out-of-gamut colours clipped
static float3 frameColor(float3 c, constant float4 *u) {
    if (u[5].y < 0.5) return c;
    const float3 p = srgbDecode(c);
    const float3 l = float3(dot(p, float3(1.2249401, -0.2249404, 0.0)),
                            dot(p, float3(-0.0420569, 1.0420571, 0.0)),
                            dot(p, float3(-0.0196376, -0.0786361, 1.0982735)));
    return srgbEncode(l);
}

// lensfilter_prep.glsl's second target: the linear frame with its highlights' headroom, at half the viewport's size (half floats:
// no square roots); its mip levels are the pyramid
fragment float4 vf_prep(VfOut in [[stage_in]], texture2d<float> frame [[texture(0)]], constant float4 *u [[buffer(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    if (!inside(in.uv)) return float4(0, 0, 0, 1);
    const float3 d = frameColor(frame.sample(s, in.uv).rgb, u);
    const float3 lin = lfLin(d);
    const float t = smoothstep(0.82, 0.92, lfLuma(d));
    return float4(lin * (1.0 + 3.0 * t), 1.0);
}

fragment float4 vf_main(VfOut in [[stage_in]], texture2d<float> frame [[texture(0)]], texture3d<float> lut [[texture(1)]],
                        texture2d<float> pyramid [[texture(2)]], constant float4 *u [[buffer(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    constexpr sampler sm(filter::linear, mip_filter::linear, address::clamp_to_edge);
    if (!inside(in.uv)) return float4(0, 0, 0, 1);
    const float2 resolution = max(u[1].xy, float2(1.0));
    const float4 framePx = frame.sample(s, in.uv);
    float3 color = frameColor(framePx.rgb, u);
    // lensFilter(): the diffusion filters (uLf 1..6) - the frame times W0 plus the pyramid's levels times their weights
    const int levels = int(u[4].y);
    if (u[4].w > 0.5 && u[5].x > 0.0 && levels > 0) {
        const float3 c = lfLin(color);
        float3 o = u[VF_U_W0].rgb * c;
        for (int k = 1; k <= 12; k++) {
            if (k > levels) break;
            const float3 w = u[VF_U_W0 + k].rgb;
            if (w.r + w.g + w.b > 1e-5) o += w * lfLevel(pyramid, sm, in.screen, k - 1);
        }
        color = lfEnc(clamp(o, 0.0, 1.0));
    }
    // the film / LUT table (main_fs.glsl uLutOn)
    if (u[4].x > 0.5) {
        const float size = u[1].z;
        const float3 lc = (clamp((clamp(color, 0.0, 1.0) - u[2].xyz) * u[3].xyz, 0.0, 1.0) * (size - 1.0) + 0.5) / size;
        color = mix(color, lut.sample(s, lc).rgb, u[1].w);
    }
    // focus peaking on the camera's frame itself (the filter and the LUT are no edges)
    if (u[4].z > 0.5) {
        float4 avg = float4(0.0);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) avg += frame.sample(s, in.uv + float2(i * 2, j * 2) / resolution);
        }
        avg /= 9.0;
        const float diff = dot(abs(framePx - avg), float4(0.299, 0.587, 0.114, 0.0));
        const float w = (diff * diff) / (0.05 + diff * diff);
        color += float3(1.0, 0.0, 1.0) * 32.0 * diff * w;
    }
    return float4(clamp(color, 0.0, 1.0), 1.0);
}
