precision highp float;
precision highp sampler2D;
// P512 «Маска лица» of «Портрет» on the whyLow grid (WhyCombine; the combine reads it bilinearly as FaceMask).
//  MODE 0: the raw mask - every found face's ellipse (its core always, see below) (its camera box widened 1.15 x faceMaskU.x; centred: the box is in sensor
//          coordinates, the forehead may lie to any side) times how close the pixel's colour (OKLab a / L, b / L) is to that face's
//          measured skin, within the face's own spread (faceSkinU.w; faceMaskU.y of the way: 0 the whole ellipse, 1 its skin only);
//          .g the guide (OKLab L of the scene colour).
//  MODE 1: one axis (dirU) of a cross-bilateral smoothing of the mask: a gaussian of sigmaU texels whose weights fall where the
//          guide's lightness differs (the face's edge against hair, glasses, the background), so the noise of a beard or a shadow
//          leaves no speckles and the edge follows the face's outline.
#define MODE 0
uniform sampler2D InputBuffer;
#if MODE == 0
uniform int faceCountU;
uniform vec4 faceBoxU[5];           // left, top, right, bottom (normalised)
uniform vec4 faceSkinU[5];          // a / L, b / L of the skin, measured (1), spread
uniform float faceLightU[5];        // the skin's OKLab L (cube root of its mean luminance), 0 unknown
uniform vec2 faceMaskU;             // size, skin follow
#else
uniform ivec2 dirU;
uniform float sigmaU;
#endif
out vec4 Output;

#if MODE == 0
vec3 oklab(vec3 c) {
    vec3 lms = mat3(0.4122214708, 0.2119034982, 0.0883024619,
                    0.5363325363, 0.6806995451, 0.2817188376,
                    0.0514459929, 0.1073969566, 0.6299787005) * max(c, vec3(0.0));
    lms = pow(lms, vec3(1.0 / 3.0));
    return mat3(0.2104542553, 1.9779984951, 0.0259040371,
                0.7936177850, -2.4285922050, 0.7827717662,
                -0.0040720468, 0.4505937099, -0.8086757660) * lms;
}
#endif

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
#if MODE == 0
    vec3 c = texelFetch(InputBuffer, xy, 0).rgb;
    c = vec3(isnan(c.r) || isinf(c.r) ? 0.0 : c.r, isnan(c.g) || isinf(c.g) ? 0.0 : c.g, isnan(c.b) || isinf(c.b) ? 0.0 : c.b);
    vec3 lab = oklab(c);
    vec2 uv = (vec2(xy) + 0.5) / vec2(size);
    float m = 0.0;
    for (int i = 0; i < 5; i++) {
        if (i >= faceCountU) break;
        vec4 b = faceBoxU[i];
        if (b.z <= b.x || b.w <= b.y) continue;
        vec2 p = (uv - 0.5 * (b.xy + b.zw)) / (0.5 * (b.zw - b.xy) * 1.15 * max(faceMaskU.x, 0.1));
        float e = 1.0 - smoothstep(0.65, 1.0, dot(p, p));
        if (e <= 0.0) continue;
        float skin = 1.0;
        if (faceSkinU[i].z > 0.0) {
            // colour: within the face's own spread of a / L, b / L (D:/burst/face: 0.025 - 0.044; a grey wall sits 0.06 from the
            // skin, so the old 1.8 x spread took it in), never a near-neutral pixel (hair in shade, the wall, a window)
            vec2 ab = lab.x > 0.02 ? lab.yz / lab.x : vec2(0.0);
            float lo = clamp(faceSkinU[i].w, 0.015, 0.04);
            skin = (1.0 - smoothstep(lo, lo + 0.035, length(ab - faceSkinU[i].xy))) * smoothstep(0.02, 0.035, length(ab));
            // lightness: within ~40 % of the skin's L (a lit window, the white helmet, dark hair are no skin; a lit forehead and
            // the shade round the eyes are)
            if (faceLightU[i] > 0.0) skin *= 1.0 - smoothstep(0.38, 0.60, abs(lab.x - faceLightU[i]) / faceLightU[i]);
        }
        // the core of the face (eyes, brows, glasses, the lit forehead: whatever their colour) is always the face - the mask has
        // no holes there; outside it the skin colour decides the edge
        float core = 1.0 - smoothstep(0.25, 0.50, dot(p, p));
        m = max(m, e * max(core, mix(1.0, skin, clamp(faceMaskU.y, 0.0, 1.0))));
    }
    Output = vec4(m, lab.x, 0.0, 1.0);
#else
    vec2 centre = texelFetch(InputBuffer, xy, 0).rg;
    float sigma = max(sigmaU, 0.5);
    int radius = int(ceil(2.5 * sigma));
    float inv = -0.5 / (sigma * sigma);
    float tol = max(0.06 * centre.g, 0.004);
    float rinv = -0.5 / (tol * tol);
    float sum = 0.0, weight = 0.0;
    for (int i = -radius; i <= radius; i++) {
        vec2 t = texelFetch(InputBuffer, clamp(xy + dirU * i, ivec2(0), size - 1), 0).rg;
        float dl = t.g - centre.g;
        float w = exp(float(i * i) * inv + dl * dl * rinv);
        sum += t.r * w;
        weight += w;
    }
    Output = vec4(sum / max(weight, 1e-6), centre.g, 0.0, 1.0);
#endif
}
