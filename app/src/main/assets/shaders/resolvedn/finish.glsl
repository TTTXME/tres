precision highp float;
precision highp sampler2D;
#import interpolation
uniform sampler2D InputBuffer;
uniform sampler2D Filtered;
uniform sampler2D GainMap;
uniform mat3 fromRenderU;
uniform vec3 neutralU;
uniform int filteredOriginU;
// P479b (speed): 1 = level 0's resolvedn/rebuild and resolvedn/rgb fused in (one frame, not tiled) - Filtered is not used; the
// Oklab sum and its RGB rounded with packHalf2x16 as their RGBA16F targets would. WhyMsDenoise "ms_fuse", off by default: on the
// Adreno 750 the result still differs from the two passes (two isolated pixels 10 - 11 levels on the complete30 replay)
#define FUSED 0
#if FUSED == 1
uniform sampler2D u_tex_coarse_recon;   // level 1 reconstructed (Oklab), linear
uniform sampler2D u_tex_laplace_filt;   // level 0 filtered Laplace (Oklab diff)
uniform int u_width;                    // level 0 size
uniform int u_height;
#endif
out vec4 Output;

#if FUSED == 1
// a value as an RGBA16F target stores it
vec3 half3(vec3 v) {
    return vec3(unpackHalf2x16(packHalf2x16(v.xy)), unpackHalf2x16(packHalf2x16(vec2(v.z, 0.0))).x);
}

// resolvedn/rebuild's sum, then resolvedn/rgb's conversion, at level 0 pixel p (each rounded as its target did)
vec3 filteredAt(ivec2 p) {
    vec2 uv = (vec2(p) + 0.5) / vec2(u_width, u_height);
    vec3 lab = half3((texture(u_tex_coarse_recon, uv) + texelFetch(u_tex_laplace_filt, p, 0)).rgb);
    float L = max(lab.r, 0.0);
    float a = lab.g;
    float b = lab.b;
    float l_inv = max(0.0, L + 0.3963377774 * a + 0.2158037573 * b);
    float m_inv = max(0.0, L - 0.1055613458 * a - 0.0638541728 * b);
    float s_inv = max(0.0, L - 0.0894841775 * a - 1.2914855480 * b);
    float l3 = l_inv * l_inv * l_inv;
    float m3 = m_inv * m_inv * m_inv;
    float s3 = s_inv * s_inv * s_inv;
    return half3(vec3(
        max(+4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3, 0.0),
        max(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3, 0.0),
        max(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3, 0.0)
    ));
}
#endif

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec4 gain = textureBicubicHardware(GainMap, vec2(xy) / vec2(textureSize(InputBuffer, 0)));
    float lsc = (gain.r + 0.5 * (gain.g + gain.b) + gain.a) / 3.0;
#if FUSED == 1
    vec3 camera = fromRenderU * filteredAt(xy) / max(neutralU * lsc, vec3(1e-6));
#else
    vec3 camera = fromRenderU * texelFetch(Filtered, xy - ivec2(0, filteredOriginU), 0).rgb / max(neutralU * lsc, vec3(1e-6));
#endif
    Output = vec4(camera, texelFetch(InputBuffer, xy, 0).a);
}
