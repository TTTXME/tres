// P630c «Oldstyle»: the mode's style (OldstyleStyle) - the palette's «Тон» / «Цвет» and the twelve advanced parameters - one
// function for the viewfinder (preview/main_fs, spliced by MainRenderer) and the photo (oldstyle/adjust.glsl, #import). The includer
// defines OS_TAP(off): the picture's display RGB at this pixel + off (picture pixels; the offsets here are whole pixels, so where
// the screen's pixels are the picture's both read the same samples). c: display RGB 0..1 of this pixel, pos: its pixel position
// in the picture, size: the picture's size in pixels. Uniforms (OldstyleStyle.uniforms, -1..1, discrete steps 0, 1/3, 2/3, 1):
uniform vec4 uOs0;   // tone, colour, exposure, warmth
uniform vec4 uOs1;   // hue, highlights, shadows, warm boost
uniform vec4 uOs2;   // cool boost, sharpness, vignette, aura
uniform vec4 uOs3;   // halo, grain, -, -

float osY(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 osSat(vec3 c, float s) {
    float y = osY(c);
    return vec3(y) + (c - vec3(y)) * s;
}

// a hash of a whole cell position, 0..1
float osHash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// the mean of eight samples on a ring of radius r (whole pixels)
vec3 osRing(float r) {
    vec3 s = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7853982;
        s += OS_TAP(floor(vec2(cos(a), sin(a)) * r + 0.5));
    }
    return s * 0.125;
}

vec3 oldstyleStyle(vec3 c, vec2 pos, vec2 size) {
    float tone = uOs0.x, colour = uOs0.y, expo = uOs0.z, warmth = uOs0.w;
    float hue = uOs1.x, hl = uOs1.y, sh = uOs1.z, warmB = uOs1.w;
    float coolB = uOs2.x, sharp = uOs2.y, vig = uOs2.z, aura = uOs2.w;
    float halo = uOs3.x, grain = uOs3.y;
    float longSide = max(size.x, size.y);
    // sharpness: away from the 3 x 3 binomial at the picture's step (OldstyleStyle.sharpStep), the blur itself at -100
    if (sharp != 0.0) {
        float st = floor(max(1.0, longSide / 1600.0) + 0.5);
        vec3 blur = (4.0 * c + 2.0 * (OS_TAP(vec2(st, 0.0)) + OS_TAP(vec2(-st, 0.0)) + OS_TAP(vec2(0.0, st)) + OS_TAP(vec2(0.0, -st)))
                + OS_TAP(vec2(st, st)) + OS_TAP(vec2(-st, st)) + OS_TAP(vec2(st, -st)) + OS_TAP(vec2(-st, -st))) / 16.0;
        c = clamp(c + (sharp > 0.0 ? 1.5 * sharp : sharp) * (c - blur), 0.0, 1.0);
    }
    // aura: a wide soft glow of the light (two rings, 1.5 % and 3 % of the long side), screened over the picture
    if (aura > 0.0) {
        vec3 w = 0.5 * (osRing(longSide * 0.015) + osRing(longSide * 0.03));
        c = 1.0 - (1.0 - c) * (1.0 - 0.75 * aura * w * smoothstep(0.5, 1.0, osY(w)));
    }
    // halo: a tight glow round bright edges (a ring at 0.6 % of the long side brighter than the pixel)
    if (halo > 0.0) {
        vec3 m = osRing(longSide * 0.006);
        c += halo * 0.9 * max(m - c, 0.0) * smoothstep(0.5, 0.95, osY(m));
    }
    // exposure: +-1.5 EV on the linearised picture
    if (expo != 0.0) c = pow(clamp(pow(max(c, 0.0), vec3(2.2)) * exp2(1.5 * expo), 0.0, 1.0), vec3(1.0 / 2.2));
    // the palette's «Тон»: up lighter and softer, down darker and denser
    if (tone != 0.0) {
        c = pow(max(c, 0.0), vec3(exp2(-0.7 * tone)));
        c = c - 0.35 * tone * c * (1.0 - c) * (2.0 * c - 1.0);
    }
    // the palette's «Цвет»: left a cool grey, right a warm salmon colour
    if (colour != 0.0) {
        c = osSat(c, colour < 0.0 ? 1.0 + 0.95 * colour : 1.0 + 0.55 * colour);
        c *= colour > 0.0 ? vec3(1.0 + 0.07 * colour, 1.0 + 0.01 * colour, 1.0 - 0.06 * colour)
                          : vec3(1.0 + 0.025 * colour, 1.0, 1.0 - 0.035 * colour);
    }
    // warmth of the light: the highlights towards warm (+) or cold (-)
    if (warmth != 0.0) c += vec3(0.09, 0.015, -0.09) * warmth * smoothstep(0.35, 1.0, osY(c));
    // hue offset: the colours turned round the grey axis, up to 35 degrees
    if (hue != 0.0) {
        float a = hue * 0.61, cs = cos(a), sn = sin(a);
        vec3 k = vec3(0.5773503);
        c = c * cs + cross(k, c) * sn + k * dot(k, c) * (1.0 - cs);
    }
    // highlights and shadows
    if (hl != 0.0) {
        float w = smoothstep(0.45, 1.0, osY(c));
        c = hl > 0.0 ? c + hl * w * 0.35 * (1.0 - c) : c * (1.0 + hl * w * 0.45);
    }
    if (sh != 0.0) {
        float w = 1.0 - smoothstep(0.0, 0.55, osY(c));
        c = sh > 0.0 ? c + sh * w * 0.3 * (1.0 - c) : c * (1.0 + sh * w * 0.6);
    }
    // warm / cool boost: the saturation of the warm (red over blue) or the cool colours
    if (warmB != 0.0 || coolB != 0.0) {
        float d = c.r - c.b;
        c = osSat(c, max(0.0, 1.0 + 0.9 * warmB * clamp(d * 4.0, 0.0, 1.0) + 0.9 * coolB * clamp(-d * 4.0, 0.0, 1.0)));
    }
    // vignette: the corners darker (+) or lighter (-), round in the picture whatever its orientation
    if (vig != 0.0) {
        float f = smoothstep(0.35, 1.0, length(pos - 0.5 * size) / (0.5 * length(size)));
        c = vig > 0.0 ? c * (1.0 - 0.7 * vig * f * f) : c - vig * 0.4 * f * f * (1.0 - c);
    }
    // grain: a cell of 1 / OldstyleStyle.GRAIN_CELLS of the long side, strongest in the mid-tones
    if (grain > 0.0) {
        float y = osY(c);
        c += (osHash(floor(pos / max(1.0, longSide / 1200.0))) - 0.5) * grain * 0.09 * (0.25 + 3.0 * y * (1.0 - y));
    }
    return clamp(c, 0.0, 1.0);
}
