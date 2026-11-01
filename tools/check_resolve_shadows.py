#!/usr/bin/env python3
"""Exercise the production Resolve shader's shadow guide on a host GPU."""
from pathlib import Path
import numpy as np
import moderngl


def main():
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    source = (Path(__file__).resolve().parents[1] / 'app/src/main/assets/shaders/scamdn/ark_resolve.glsl').read_text(encoding='utf-8')
    program = ctx.program(vertex_shader='''#version 430
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}''', fragment_shader='#version 430\n' + source)
    vao = ctx.vertex_array(program, [])
    w, h = 256, 128
    result = ctx.texture((w, h), 4, dtype='f4')
    fbo = ctx.framebuffer([result])
    eff = ctx.texture((w, h), 1, np.full((h, w), 64, np.uint8).tobytes(), dtype='u1')
    eff.use(1)
    uniforms = dict(InputBuffer=0, EffMap=1, useEffU=1, effScaleU=10 / 64, framesU=10., totalU=20., refU=10.,
                    passU=2, stepU=4, noiseModelU=(.562 * .002, .25 * .002, .25 * .002, 4e-6),
                    factorU=.4, outlierU=.125, rejectRevertU=.5, globalRevertU=.85, grainU=0., vignetteBoostU=0., vignetteScaleU=1.)
    for name, value in uniforms.items():
        program[name].value = value

    def render(data, on, factor=.4):
        texture = ctx.texture((w, h), 3, np.asarray(data, np.float32).tobytes(), dtype='f4')
        try:
            texture.use(0)
            program['darkChromaU'].value = float(on)
            program['factorU'].value = factor
            fbo.use()
            vao.render(moderngl.TRIANGLES, vertices=3)
            return np.frombuffer(fbo.read(components=3, dtype='f4'), np.float32).reshape(h, w, 3).copy()
        finally:
            texture.release()

    yy, xx = np.mgrid[:h, :w]
    data = np.zeros((h, w, 3), np.float32)
    data[..., 0] = .002
    data[..., 1] = .0002 * np.sin(xx * 2 * np.pi / 48)
    data[..., 2] = .0002 * np.cos(yy * 2 * np.pi / 40)
    off, on = render(data, 0), render(data, 1)
    luma = np.array([.2126390, .7151688, .0721923], np.float32)
    assert np.max(np.abs((on - off) @ luma)) < 2e-8, 'shadow guide changed luminance'
    chroma = lambda rgb: np.stack(((rgb @ luma) - rgb[..., 0], (rgb @ luma) - rgb[..., 2]), axis=-1)
    ratio = float(chroma(on).std() / chroma(off).std())
    assert ratio < .75, ('low-frequency shadow colour noise survived', ratio)
    data[..., 0] = .1
    assert np.array_equal(render(data, 0), render(data, 1)), 'bright pixels changed'
    data[..., 0] = .002
    assert np.array_equal(render(data, 0, 0), render(data, 1, 0)), 'bypass changed'
    data[..., 1:] = (.0005, -.0003)
    assert np.max(np.abs(render(data, 0) - render(data, 1))) < 2e-8, 'constant dark colour changed'
    data[..., 0] = .00001
    assert np.isfinite(render(data, 1)).all(), 'non-finite result near black'
    assert ctx.error == 'GL_NO_ERROR'
    print(f'Resolve shadows PASS: chroma RMS ratio {ratio:.4f}; luma, bright bypass and constant colour preserved')
    vao.release(); program.release(); fbo.release(); result.release(); eff.release(); ctx.release()


if __name__ == '__main__':
    main()
