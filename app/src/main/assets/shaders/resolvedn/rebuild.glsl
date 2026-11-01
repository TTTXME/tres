precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D u_tex_coarse_recon; // Level k+1 reconstructed (Oklab)
uniform sampler2D u_tex_laplace_filt;  // Level k filtered Laplace (Oklab diff)
uniform int u_width;
uniform int u_height;
out vec4 o_reconstructed;
#define TILED 0
#if TILED == 1
uniform int originDstU;
#endif

#if TILED == 1
uniform ivec2 full_u_tex_coarse_recon;
uniform int origin_u_tex_coarse_recon;
vec4 dnFetch_u_tex_coarse_recon(ivec2 p, int lod) {
    p = clamp(p, ivec2(0), full_u_tex_coarse_recon - 1);
    return texelFetch(u_tex_coarse_recon, p - ivec2(0, origin_u_tex_coarse_recon), lod);
}
vec2 dnUv_u_tex_coarse_recon(vec2 uv) {
    return (uv * vec2(full_u_tex_coarse_recon) - vec2(0.0, float(origin_u_tex_coarse_recon))) / vec2(textureSize(u_tex_coarse_recon, 0));
}
vec4 dnTexture_u_tex_coarse_recon(vec2 uv) { return texture(u_tex_coarse_recon, dnUv_u_tex_coarse_recon(uv)); }
vec4 dnTextureLod_u_tex_coarse_recon(vec2 uv, float lod) { return textureLod(u_tex_coarse_recon, dnUv_u_tex_coarse_recon(uv), lod); }
#else
vec4 dnFetch_u_tex_coarse_recon(ivec2 p, int lod) { return texelFetch(u_tex_coarse_recon, p, lod); }
vec4 dnTexture_u_tex_coarse_recon(vec2 uv) { return texture(u_tex_coarse_recon, uv); }
vec4 dnTextureLod_u_tex_coarse_recon(vec2 uv, float lod) { return textureLod(u_tex_coarse_recon, uv, lod); }
#endif

#if TILED == 1
uniform ivec2 full_u_tex_laplace_filt;
uniform int origin_u_tex_laplace_filt;
vec4 dnFetch_u_tex_laplace_filt(ivec2 p, int lod) {
    p = clamp(p, ivec2(0), full_u_tex_laplace_filt - 1);
    return texelFetch(u_tex_laplace_filt, p - ivec2(0, origin_u_tex_laplace_filt), lod);
}
vec2 dnUv_u_tex_laplace_filt(vec2 uv) {
    return (uv * vec2(full_u_tex_laplace_filt) - vec2(0.0, float(origin_u_tex_laplace_filt))) / vec2(textureSize(u_tex_laplace_filt, 0));
}
vec4 dnTexture_u_tex_laplace_filt(vec2 uv) { return texture(u_tex_laplace_filt, dnUv_u_tex_laplace_filt(uv)); }
vec4 dnTextureLod_u_tex_laplace_filt(vec2 uv, float lod) { return textureLod(u_tex_laplace_filt, dnUv_u_tex_laplace_filt(uv), lod); }
#else
vec4 dnFetch_u_tex_laplace_filt(ivec2 p, int lod) { return texelFetch(u_tex_laplace_filt, p, lod); }
vec4 dnTexture_u_tex_laplace_filt(vec2 uv) { return texture(u_tex_laplace_filt, uv); }
vec4 dnTextureLod_u_tex_laplace_filt(vec2 uv, float lod) { return textureLod(u_tex_laplace_filt, uv, lod); }
#endif

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
#if TILED == 1
    coord.y += originDstU;
#endif
    if (coord.x >= u_width || coord.y >= u_height) { o_reconstructed = vec4(0.0); return; }

    vec2 uv = (vec2(coord) + 0.5) / vec2(u_width, u_height);
    vec4 coarse_recon = dnTexture_u_tex_coarse_recon( uv);
    vec4 laplace_filt = dnFetch_u_tex_laplace_filt( coord, 0);

    o_reconstructed = coarse_recon + laplace_filt;
}
