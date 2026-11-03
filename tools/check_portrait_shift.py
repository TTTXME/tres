#!/usr/bin/env python3
"""P473: the scene-confirmed yellow-blue skin shift (portraitAdjustU.y) of utils/import_portrait.glsl on a host GL context."""
import numpy as np

import check_colour_output as cco
import check_light_chroma as clc
from check_yellow_hue import oklab_to_lin

VS = '''#version 310 es
void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}
'''
CORE = '''precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform vec2 imageSizeU;
#import portrait
out vec4 Output;
void main(){Output=vec4(portraitSkin(texelFetch(InputBuffer,ivec2(gl_FragCoord.xy),0).rgb,
gl_FragCoord.xy/imageSizeU),1.0);}
'''
TURN = np.radians(10.0)
BOX = (0.0, 0.0, 1.0, 1.0)


def uniforms(boxes=(BOX,), turns=(0.0,), shifts=(0.0,)):
    faces, adjust = np.zeros((5, 4), 'f4'), np.zeros((5, 4), 'f4')
    faces[:len(boxes)] = boxes
    adjust[:len(turns), 0] = turns
    adjust[:len(shifts), 1] = shifts
    return dict(portraitCountU=len(boxes), portraitFacesU=tuple(map(tuple, faces)), portraitAdjustU=tuple(map(tuple, adjust)))


def colour(hue, light=0.6, ratio=0.12):
    return oklab_to_lin(light, light * ratio * np.cos(np.radians(hue)), light * ratio * np.sin(np.radians(hue)))


def main():
    r = clc.Renderer()
    prog = r.ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\nprecision highp int;\n' + cco.expand_imports(CORE))

    def render(rgb, **values):
        rgb = np.asarray(rgb, 'f4')
        if rgb.ndim == 1:
            rgb = rgb[None, None, :]
        h, w = rgb.shape[:2]
        rgba = np.ones((h, w, 4), 'f4'); rgba[..., :3] = rgb
        texture, target = r.tex(rgba), r.ctx.texture((w, h), 4, dtype='f4')
        fb = r.ctx.framebuffer([target]); fb.use(); r.ctx.viewport = (0, 0, w, h)
        texture.use(0); prog['InputBuffer'].value = 0
        for k, v in dict(imageSizeU=(w, h), **values).items():
            if k in prog:
                prog[k].value = v
        r.ctx.vertex_array(prog, []).render(vertices=3)
        out = np.frombuffer(target.read(), 'f4').reshape(h, w, 4)[..., :3].copy()
        for obj in (fb, texture, target):
            obj.release()
        return out[0, 0] if out.shape[:2] == (1, 1) else out

    # oracle: b / L moves by the shift at constant hue band, then the linear luma is restored
    for hue, light, shift in ((66, 0.6, -0.03), (60, 0.35, -0.035), (30, 0.6, 0.03), (40, 0.8, 0.02)):
        rgb = colour(hue, light)
        lab = cco.oklab(np.asarray(rgb, 'f8'))
        expected = oklab_to_lin(lab[0], lab[1], lab[2] + shift * lab[0])
        expected *= (rgb @ clc.LUMA) / (expected @ clc.LUMA)
        got = render(rgb, **uniforms(shifts=(shift,)))
        assert np.max(np.abs(got - expected)) < 3e-6, ('CPU shift oracle', hue, shift, got, expected)
        assert abs(got @ clc.LUMA - rgb @ clc.LUMA) < 2e-7, 'preserved luminance'
        moved = np.degrees(np.arctan2(cco.oklab(got)[2], cco.oklab(got)[1])) - hue
        assert np.sign(moved) == np.sign(shift) and abs(moved) < 25, ('hue follows the shift', hue, moved)

    # the turn and the shift together: rotation first, then the shift; both stay inside their own bounds
    rgb = colour(85, ratio=0.1)
    lab = cco.oklab(np.asarray(rgb, 'f8'))
    c, s = np.cos(-TURN), np.sin(-TURN)
    a, b = c * lab[1] - s * lab[2], s * lab[1] + c * lab[2]
    expected = oklab_to_lin(lab[0], a, b - 0.02 * lab[0])
    expected *= (rgb @ clc.LUMA) / (expected @ clc.LUMA)
    assert np.max(np.abs(render(rgb, **uniforms(turns=(-TURN,), shifts=(-0.02,))) - expected)) < 3e-6, 'turn + shift oracle'

    # protected colours: greys, greens, blues, clipped, black and dark pixels never move with any shift
    for rgb in [colour(145), colour(265), (0.2, 0.2, 0.2), (1, 0.5, 0.2), (0, 0.1, 0.2), (0.001, 0.001, 0.001), colour(50, 0.08)]:
        for shift in (-0.035, 0.035):
            assert np.array_equal(render(rgb, **uniforms(shifts=(shift,))), np.asarray(rgb, 'f4')), ('protected colour', rgb, shift)

    # the face ellipse only: background bit-exact, the strongest of overlapping faces, the fifth face alone
    image = np.broadcast_to(colour(66), (65, 65, 3)).astype('f4').copy()
    box = (0.2, 0.2, 0.8, 0.8)
    got = render(image, **uniforms((box,), shifts=(-0.03,)))
    yy, xx = np.mgrid[:65, :65] + 0.5
    inside = ((xx / 65 - 0.5) / (0.6 * 0.46)) ** 2 + ((yy / 65 - 0.5) / (0.6 * 0.48)) ** 2 < 1
    assert np.array_equal(got[~inside], image[~inside]), 'background exact bypass'
    assert np.max(np.abs(got[inside] - image[inside])) > 1e-3, 'face actually changes'
    assert np.array_equal(render(image, **uniforms((box,) * 5, shifts=(-0.01, -0.03, 0.02, -0.03, 0.0))), got), 'strongest face wins'
    boxes = tuple((i / 5, 0.1, (i + 1) / 5, 0.9) for i in range(5))
    five = render(image, **uniforms(boxes, shifts=(0, 0, 0, 0, -0.03)))
    assert np.array_equal(five[:, :52], image[:, :52]), 'other four faces unchanged'
    assert np.max(np.abs(five[:, 52:] - image[:, 52:])) > 1e-3, 'fifth face is processed'

    rng = np.random.default_rng(473)
    sweep = rng.uniform(0, 1.2, (65, 65, 3)).astype('f4')
    for shift in (-0.035, 0.035):
        out = render(sweep, **uniforms(shifts=(shift,)))
        assert np.isfinite(out).all(), 'finite colours stay finite'
        assert np.max(np.abs((out - sweep) @ clc.LUMA)) < 3e-7, 'luminance bound on random colours'
        lab_in, lab_out = cco.oklab(sweep.astype('f8')), cco.oklab(out.astype('f8'))
        moved = np.any(out != sweep, axis=-1)
        d = lab_out[moved][:, 1:] / lab_out[moved][:, :1] - lab_in[moved][:, 1:] / lab_in[moved][:, :1]
        # the luma rescale keeps a / L and b / L: only b / L moves, by at most the shift
        assert moved.any() and np.max(np.abs(d[:, 0])) < 1e-3 and np.max(np.abs(d[:, 1])) <= abs(shift) + 1e-3, 'bounded shift on random colours'
        assert np.all(np.sign(d[:, 1][np.abs(d[:, 1]) > 1e-4]) == np.sign(shift)), 'shift direction on random colours'
    prog.release()
    print('portrait shift check PASS: CPU oracle, turn + shift, protected colours, masks, five faces, random bound')


if __name__ == '__main__':
    main()
