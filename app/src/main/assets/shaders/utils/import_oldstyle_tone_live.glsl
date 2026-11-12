// P630e «Oldstyle»: the version's tone on the viewfinder - the base the style (import_oldstyle_adjust) is applied on, as the photo's
// tone-port module is (the photo: the module on the merge, then the style; the screen: this, then the style). The module itself is a
// whole-frame CPU pass (its 3.1 postview takes ~1 s a 12 MP frame on one core, ~60-90 ms at the screen's size: no live use, see
// OldstyleTone), so here its approximation on the ISP frame:
//   * the frame back to linear (gamma 2.2, as the series gain uGain does) over its own exposure;
//   * P630g: the gains are OldstyleLive's - the plan's over the frame's own measured level (the photo's mean, whatever the ISP's
//     gain), smoothed; a clipped frame's white kept white (uOt.w);
//   * the tone of each channel: the module's own tone of a neutral (uOtLut RGB: V1 finish_raw27_rgb, V2 render_raw31_sdr of a ramp,
//     OldstyleNative.previewLut) at the plan's gains (uOt: short, long; the shot's fusion_gains over the screen frame's TET);
//   * with HDR the two virtual exposures blended by the version's long weight (uOtLut A: V1 weights27 of the bright RGB8, V2 the
//     RAW mask of the encoded long) of the low-passed light (rings at 2 % and 5 % of the long side: the pyramid's large scales),
//     V1 in its 8-bit output (as its fusion), V2 in the encoded 12-bit domain before the curve (as render_raw31_hdr).
// The includer defines LF_SAMPLE(uv): the frame's display RGB there.
uniform int uOtOn;               // 0 off, 1 V1 (2.7), 2 V2 (3.1)
uniform vec4 uOt;                // gain short, gain long, HDR (0 / 1), white boost (P630g: OldstyleLive.whiteBoost, 1 = none)
uniform highp sampler2D uOtLut;  // 1024 x 1 RGBA8

vec4 otLut(float x) {
    return texture(uOtLut, vec2(clamp(x, 0.0, 1.0) * (1023.0 / 1024.0) + 0.5 / 1024.0, 0.5));
}

vec3 otCurve(vec3 lin) {
    return vec3(otLut(lin.r).r, otLut(lin.g).g, otLut(lin.b).b);
}

// P630g: the frame's white is "at least white" - its top lifted to the mean RAW light it stands for (OldstyleLive.whiteBoost,
// WHITE_FROM 0.85)
vec3 otLinear(vec3 c) {
    vec3 lin = pow(clamp(c, 0.0, 1.0), vec3(2.2));
    return lin * (1.0 + (uOt.w - 1.0) * smoothstep(0.92, 1.0, max(lin.r, max(lin.g, lin.b))));
}

// the low-passed linear light round uv: the centre and two rings of eight (px, py: a screen pixel in uv)
vec3 otLowPass(vec2 uv, vec3 centre, vec2 px, vec2 py, float longSide) {
    vec3 s = 2.0 * centre;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7853982 + 0.3926991;
        vec2 d = vec2(cos(a), sin(a));
        s += otLinear(LF_SAMPLE(uv + (d.x * px + d.y * py) * (0.02 * longSide)));
        s += otLinear(LF_SAMPLE(uv + (d.x * px + d.y * py) * (0.05 * longSide)));
    }
    return s / 18.0;
}

vec3 oldstyleToneLive(vec2 uv, vec3 c, vec2 px, vec2 py, vec2 size) {
    vec3 lin = otLinear(c);
    if (uOt.z < 0.5) return otCurve(lin * uOt.y);
    vec3 lp = otLowPass(uv, lin, px, py, max(size.x, size.y)) * uOt.y;
    float w = otLut(max(lp.r, max(lp.g, lp.b))).a;
    if (uOtOn == 1) return mix(otCurve(lin * uOt.x), otCurve(lin * uOt.y), w);
    vec3 dark = pow(clamp(lin * uOt.x, 0.0, 1.0), vec3(1.0 / 2.2)), bright = pow(clamp(lin * uOt.y, 0.0, 1.0), vec3(1.0 / 2.2));
    return otCurve(pow(mix(dark, bright, w), vec3(2.2)));
}
