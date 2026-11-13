precision highp float; precision highp sampler2D;
// One Laplacian band (GLUtils.createPyramid): the finer level minus the coarser one upsampled bilinearly at this texel.
uniform sampler2D fineLevel;
// 1 / the finer level's nominal size (the coarse level is sampled in its own normalised coordinates)
uniform vec2 fineTexel;
uniform sampler2D coarseLevel;
out vec4 band;

void main() {
    band = texelFetch(fineLevel, ivec2(gl_FragCoord.xy), 0) - texture(coarseLevel, vec2(gl_FragCoord.xy) * fineTexel);
}
