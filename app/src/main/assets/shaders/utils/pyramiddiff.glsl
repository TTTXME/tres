precision highp float;
precision highp sampler2D;
// One Laplacian band (GLUtils.createPyramid): the finer level minus the coarser one upsampled bilinearly at this texel.
// size: 1 / the finer level's nominal size (the coarse level is sampled in its own normalised coordinates).
uniform sampler2D target;
uniform sampler2D base;
uniform vec2 size;
uniform ivec2 size2;
out vec4 result;

void main() {
    vec2 at = vec2(gl_FragCoord.xy) * vec2(size);
    result = texelFetch(target, ivec2(gl_FragCoord.xy), 0) - texture(base, at);
}
