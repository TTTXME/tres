precision highp float;
precision highp sampler2D;

// Ultra HDR scene-luma plane: linear scene luminance sampled from the captured
// post-demosaic buffer, with the exact same mirror / rotate / crop transform
// the stored SDR base went through (coordinate logic mirrors
// addwatermark_rotate.glsl) so the plane is pixel-aligned with the base image.
//
// This is the pre-local-tone-map scene energy: fusion LTM compresses
// highlights below display white inside the rendering chain, so the
// recoverable highlight headroom only exists here. No clamp - scene luminance
// may legitimately reach/exceed 1.0.

uniform sampler2D InputBuffer;
uniform sampler2D GainMap;
uniform int rotate;
uniform bool mirror;
uniform ivec2 cropSize;
uniform ivec2 rawSize;
// P370 (B3): the luminance weights of the white-balanced camera RGB the plane reads - LUMA^T . intermediateToSRGB .
// sensorToProPhoto . diag(neutral) (PostPipeline: the render's own colour matrix): Rec.709 weights on camera RGB gave the
// HDR boost by hue (the X8U's blue +0.41 EV, orange -0.14 EV). Unset (0): Rec.709 on the input as before.
uniform vec3 wU;
// P378 review (Ultra HDR keeps the sky term's change): the SDR's own ratio of the sky term (WhyCombine: why/combine at whyLow with
// and without it, why/skyratio; sensor layout as InputBuffer, normalised coordinates, linear filter) - the plane takes it, so the
// HDR rendition is the lowered SDR sky times the scene's headroom over the SDR before the term, not the scene's old light. 0 /
// unset: none (the plane as before).
uniform sampler2D SkyRatio;
uniform int skyRatioU;

out vec4 Output;

#define lum709(x) dot(x, vec3(0.2126, 0.7152, 0.0722))
#import interpolation

void main() {
    // the texel of the base image at this output pixel: RotateWatermark's turn, mirror and crop (P387: inside the texture)
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = ivec2(textureSize(InputBuffer, 0)) - 1;
    int skipped = rawSize.y - cropSize.y;
    ivec2 at;
    if (rotate == 0) {
        p.y += skipped;
        if (mirror) p.y = last.y - p.y;
        at = p;
    } else if (rotate == 1) {
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
        at = min(p, last);
    }
    vec3 c = texelFetch(InputBuffer, at, 0).rgb;
    // the lens shading of that texel (R, the greens' mean, B), averaged: the scene's light before the shading correction
    vec4 g = textureBicubicHardware(GainMap, vec2(at) / vec2(rawSize));
    float shading = dot(vec3(g.r, (g.g + g.b) / 2.0, g.a), vec3(1.0 / 3.0));
    float l = (wU == vec3(0.0) ? lum709(max(c, vec3(0.0))) : max(dot(wU, c), 0.0)) * shading;
    if (skyRatioU == 1) l *= max(texture(SkyRatio, (vec2(at) + 0.5) / vec2(last + 1)).r, 0.0);
    Output = vec4(l, l, l, 1.0);
}
