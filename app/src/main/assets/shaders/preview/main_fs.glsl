#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;   // P228: the live lens filter's tap offsets need the uv precision
// P630c: the frame's taps at full precision where the sampler is a 2D one (the developed-RAW pass; the camera's external
// texture keeps its driver's default precision: an 8-bit picture)
precision highp sampler2D;
in vec2 texCoord;
// the frame flipped (front camera) and the focus peaking switch
uniform bool mirror, enablePeak;
// the camera's frame and the screen's size (px)
uniform samplerExternalOES sTexture;
// P90: a post-shutter series frame brought back to the viewfinder's exposure (ViewfinderGain), 1.0 for viewfinder frames
uniform float uGain;
uniform vec2 resolution;
out vec4 Output;
// P228 / owner, 2026-10-18 («не видна работа лута в видоискателе»): the film LUT on the ISP viewfinder too, as on the
// developed-RAW one (rawdevelop_fs) and the photo (WhyCombine): display values in, mixed by its strength
uniform highp sampler3D uLut;
uniform int uLutOn;
uniform float uLutStrength;
uniform float uLutSize;
uniform vec3 uLutMin;
uniform vec3 uLutScale;
#define LF_SAMPLE(p) texture(sTexture, p).rgb
//#lensfilter_live
// P630 / P630c «Oldstyle»: the mode's style live, the photo's function (MainRenderer splices utils/import_oldstyle_adjust.glsl
// here); uOsOn 0 outside the mode or with every parameter at 0. Its taps: the frame at whole screen pixels (px, py: a screen pixel
// in uv), moved by what the gain / filter / LUT did to this pixel (the photo's taps are of its finished picture)
uniform int uOsOn;
vec2 osUv, osPx, osPy;
vec3 osShift;
#define OS_TAP(off) (LF_SAMPLE(osUv + (off).x * osPx + (off).y * osPy) + osShift)
//#oldstyle_adjust
// P643d: the preview of the ScamMaster tone under the style (MainRenderer splices utils/import_oldstyle_tone_live.glsl; uOtOn 0 off)
//#oldstyle_tone
void main() {
    vec2 uv = texCoord;
    if (mirror) uv.y = 1.0 - uv.y;
    // P228: the screen's pixel steps in uv (the texture is rotated / mirrored to the screen), for the live lens filter
    vec2 px = dFdx(uv), py = dFdy(uv);
    vec4 frame = texture(sTexture, uv);
    vec4 color = frame;
    if (uGain != 1.0) color.rgb = pow(clamp(pow(max(color.rgb, 0.0), vec3(2.2)) * uGain, 0.0, 1.0), vec3(1.0 / 2.2));
    if (uOtOn > 0) color.rgb = oldstyleToneLive(uv, color.rgb);
    color.rgb = lensFilter(uv, color.rgb, px, -py, resolution);
    if (uLutOn == 1) {
        vec3 lc = (clamp((clamp(color.rgb, 0.0, 1.0) - uLutMin) * uLutScale, 0.0, 1.0) * (uLutSize - 1.0) + 0.5) / uLutSize;
        color.rgb = mix(color.rgb, texture(uLut, lc).rgb, uLutStrength);
    }
    if (uOsOn == 1) {
        osUv = uv;
        osPx = px;
        osPy = py;
        osShift = color.rgb - frame.rgb;
        color.rgb = oldstyleStyle(clamp(color.rgb, 0.0, 1.0), floor(gl_FragCoord.xy), resolution);
    }
    // focus peaking, on the camera's frame itself (the filter and the LUT are no edges). P314: its 9 taps only when it is on
    // (every ISP frame and every pass of the developed RAW to the screen read them for nothing; the output without peaking is
    // the same). The edge: the luma distance from the mean of the 3x3 taps two pixels apart; weak edges (noise) are muted by
    // e^2 / (0.05 + e^2), the rest painted magenta.
    if (enablePeak) {
        vec4 around = vec4(0.0);
        for (int dx = -2; dx <= 2; dx += 2)
            for (int dy = -2; dy <= 2; dy += 2) around += texture(sTexture, uv + vec2(dx, dy) / resolution);
        around /= 9.0;
        float edge = dot(abs(frame - around), vec4(0.299, 0.587, 0.114, 0.0));
        float edge2 = edge * edge;
        color += vec4(1.0, 0.0, 1.0, 0.0) * 32.0 * edge * (edge2 / (0.05 + edge2));
    }
    Output = color;
}
