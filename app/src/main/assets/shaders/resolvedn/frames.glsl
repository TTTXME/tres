precision highp float; precision highp int; precision highp usampler2D;
uniform usampler2D EffMap;
uniform float effScaleU;
uniform float framesU;
uniform float totalU;
uniform int originDstU;
out vec4 Output;

void main() {
    uint code = texelFetch(EffMap, ivec2(gl_FragCoord.xy) + ivec2(0, originDstU), 0).r;
    float n = code > 0u ? float(code) * effScaleU : framesU;
    Output = vec4(clamp(n, 1.0, max(totalU, 1.0)) - 1.0, 0.0, 0.0, 1.0);
}
