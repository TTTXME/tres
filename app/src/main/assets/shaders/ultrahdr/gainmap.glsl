precision highp float;
precision highp sampler2D;
// The Ultra HDR gain map (the last stage of the scene-anchored pass): per uDown x uDown block, log2 of the anchored scene
// luminance over the base's, both with the hdrgm offset added (the decoder applies (SDR + OffsetSDR) 2^gain - OffsetHDR),
// faded out in deep blacks (no shadow noise boosted on HDR displays), over [0, uScale] in 8 bits (R = G = B, A = 1).
// InputBuffer: the stored SDR base (display-encoded RGBA8). LBuffer: the scene-luma plane (ultrahdr/sceneluma.glsl, linear,
// pixel-aligned) - the only place the highlight headroom the local tone map compressed away is left.
// uAnchor: linear median of the base / linear median of the scene plane: the midtones gain nothing, brighter scene content
// more, anything below stays at identity (clamp).
uniform sampler2D InputBuffer;
uniform sampler2D LBuffer;
uniform float uAnchor;
uniform int uDown;
uniform float uScale;
uniform float uEps;
// P370 (B3): 1 = the base is Display P3 (PhotoOutput): its luminance takes the P3 weights; 0 / unset: sRGB (Rec.709)
uniform int uP3;
out vec4 Output;

#define SHADOW_FADE 0.02
const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);
const vec3 WP3 = vec3(0.2290, 0.6917, 0.0793);

float decode(float v) {
    v = clamp(v, 0.0, 1.0);
    if (v <= 0.04045) return v / 12.92;
    return pow((v + 0.055) / 1.055, 2.4);
}

void main() {
    ivec2 corner = ivec2(gl_FragCoord.xy) * uDown;
    float base = 0.0;
    float scene = 0.0;
    int n = 0;
    for (int y = 0; y < uDown; y++) {
        for (int x = 0; x < uDown; x++) {
            ivec2 p = corner + ivec2(x, y);
            vec3 sdr = texelFetch(InputBuffer, p, 0).rgb;
            vec3 linear = vec3(decode(sdr.r), decode(sdr.g), decode(sdr.b));
            float sdrY = uP3 == 1 ? dot(linear, WP3) : dot(linear, W709);
            float sceneY = dot(max(texelFetch(LBuffer, p, 0).rgb, vec3(0.0)), W709) * uAnchor;
            base += sdrY + uEps;
            scene += sceneY + uEps;
            n++;
        }
    }
    float sdrL = base / float(n);
    float sceneL = scene / float(n);
    float boost = log2(sceneL / sdrL);
    boost *= smoothstep(0.0, SHADOW_FADE, sdrL - uEps);
    int code = int(clamp(boost / uScale, 0.0, 1.0) * 255.0 + 0.5);
    float level = float(code) / 255.0;
    Output = vec4(level, level, level, 1.0);
}
