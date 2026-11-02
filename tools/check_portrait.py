#!/usr/bin/env python3
"""Run the shipped portrait/sample, portrait import and WHY integration on a host GL context."""
import numpy as np

import check_colour_output as cco
import check_light_chroma as clc
import check_vf_fused as vf
from check_yellow_hue import oklab_to_lin

VS = '''#version 310 es
out vec2 texCoord;
void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
texCoord=p;gl_Position=vec4(p*2.-1.,0,1);}
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


def uniforms(boxes=(BOX,), turns=(-TURN,), count=None):
    faces, adjust = np.zeros((5, 4), 'f4'), np.zeros((5, 4), 'f4')
    faces[:len(boxes)] = boxes
    adjust[:len(turns), 0] = turns
    return dict(portraitCountU=len(boxes) if count is None else count,
                portraitFacesU=tuple(map(tuple, faces)), portraitAdjustU=tuple(map(tuple, adjust)))


def colour(hue, light=0.6, ratio=0.1):
    return oklab_to_lin(light, light * ratio * np.cos(np.radians(hue)), light * ratio * np.sin(np.radians(hue)))


def hue(rgb):
    lab = cco.oklab(rgb)
    return np.degrees(np.arctan2(lab[..., 2], lab[..., 1]))


def main():
    r = clc.Renderer()
    programs = {}

    def render(src, rgb, size=None, textures=None, **values):
        rgb = np.asarray(rgb, 'f4')
        if rgb.ndim == 1:
            rgb = rgb[None, None, :]
        h, w = rgb.shape[:2]
        size = size or (w, h)
        rgba = np.ones((h, w, 4), 'f4'); rgba[..., :3] = rgb
        if src not in programs:
            programs[src] = r.ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + cco.expand_imports(src))
        p = programs[src]
        texture, target = r.tex(rgba), r.ctx.texture(size, 4, dtype='f4')
        fb = r.ctx.framebuffer([target]); fb.use(); r.ctx.viewport = (0, 0, *size)
        if 'InputBuffer' in p:
            texture.use(0); p['InputBuffer'].value = 0
        gain = None
        if 'GainMap' in p:
            gain = r.tex(np.ones((1, 1, 4), 'f4')); gain.use(1); p['GainMap'].value = 1
        for unit, (name, supplied) in enumerate((textures or {}).items(), 2):
            if name in p:
                supplied.use(unit); p[name].value = unit
        for k, v in dict(imageSizeU=(w, h), **values).items():
            if k in p:
                p[k].value = v
        r.ctx.vertex_array(p, []).render(vertices=3)
        out = np.frombuffer(target.read(), 'f4').reshape(size[1], size[0], 4)[..., :3].copy()
        for obj in (fb, texture, target):
            obj.release()
        if gain is not None:
            gain.release()
        return out

    for start, turn, end in ((85, -TURN, 75), (5, TURN, 15), (74, -TURN, 70), (20, TURN, 25)):
        rgb = colour(start)
        got = render(CORE, rgb, **uniforms(turns=(turn,)))[0, 0]
        expected = colour(end)
        expected *= (rgb @ clc.LUMA) / (expected @ clc.LUMA)
        assert np.max(np.abs(got - expected)) < 3e-6, ('CPU colour oracle', start, got, expected)
        assert abs(hue(got) - start) <= 10.001, ('bounded hue', start, hue(got))
        assert abs(got @ clc.LUMA - rgb @ clc.LUMA) < 2e-7, 'preserved luminance'

    unchanged = [colour(h, light) for h in (30, 45, 65, 145, 265) for light in (0.24, 0.5, 0.8)]
    unchanged += [(0.2, 0.2, 0.2), (0, 0.1, 0.2), (1, 0.5, 0.2), (-0.1, 0.2, 0.1), (0.001, 0.001, 0.001)]
    for rgb in unchanged:
        for turn in (-TURN, TURN):
            got = render(CORE, rgb, **uniforms(turns=(turn,)))[0, 0]
            assert np.array_equal(got, np.asarray(rgb, 'f4')), ('protected colour', rgb, turn, got)

    image = np.broadcast_to(colour(85), (65, 65, 3)).astype('f4').copy()
    box = (0.2, 0.2, 0.8, 0.8)
    got = render(CORE, image, **uniforms((box,)))
    yy, xx = np.mgrid[:65, :65] + 0.5
    inside = ((xx / 65 - 0.5) / (0.6 * 0.46)) ** 2 + ((yy / 65 - 0.5) / (0.6 * 0.48)) ** 2 < 1
    assert np.array_equal(got[~inside], image[~inside]), 'background exact bypass'
    assert np.max(np.abs(got[inside] - image[inside])) > 1e-3, 'face actually changes'
    assert np.array_equal(render(CORE, image, **uniforms((box,), count=0)), image), 'no faces exact bypass'
    overlap = render(CORE, image, **uniforms((box,) * 5, (-TURN,) * 5))
    assert np.array_equal(overlap, got), 'five overlapping faces never accumulate'
    boxes = tuple((i / 5, 0.1, (i + 1) / 5, 0.9) for i in range(5))
    five = render(CORE, image, **uniforms(boxes, (0, 0, 0, 0, -TURN)))
    assert np.array_equal(five[:, :52], image[:, :52]), 'other four faces unchanged'
    assert np.max(np.abs(five[:, 52:] - image[:, 52:])) > 1e-3, 'fifth face is processed'
    rng = np.random.default_rng(17)
    sweep = rng.uniform(0, 1.2, (65, 65, 3)).astype('f4')
    for turn in (-TURN, TURN):
        out = render(CORE, sweep, **uniforms(turns=(turn,)))
        assert np.isfinite(out).all(), 'finite colours stay finite'
        assert np.max(np.abs((out - sweep) @ clc.LUMA)) < 3e-7, 'luminance bound on random colours'

    sample = (cco.SH / 'portrait/sample.glsl').read_text(encoding='utf-8')
    ramp = rng.uniform(0, 1, (8, 8, 3)).astype('f4')
    got = render(sample, ramp, (2, 2), sampleSizeU=(2, 2))
    assert np.max(np.abs(got - ramp.reshape(2, 4, 2, 4, 3).mean(axis=(1, 3)))) < 2e-7, 'sample 4x4 box means'
    assert np.max(np.abs(render(sample, colour(45), sampleSizeU=(1, 1)) - colour(45))) < 2e-7, 'sample clamps tiny images'
    identity = tuple(np.eye(3).flat)
    scene = dict(sensorToIntermediate=identity, intermediateToSRGB=identity, neutralPointU=(1, 1, 1), inScaleU=1.0)
    converted = render(cco.set_define(sample, 'CAMERA_SOURCE', '1'), ramp, (2, 2), sampleSizeU=(2, 2),
                       **dict(scene, intermediateToSRGB=tuple(np.diag([1.2, 0.8, 0.6]).flat), inScaleU=0.5))
    assert np.max(np.abs(converted - got * [0.6, 0.4, 0.3])) < 2e-7, 'sample camera-to-scene colour chain'

    low = (cco.SH / 'why/low.glsl').read_text(encoding='utf-8')
    plain = render(low, image, factorU=1, **scene)
    zero = render(cco.set_define(low, 'PORTRAIT', '1'), image, factorU=1, **scene, **uniforms(count=0))
    corrected = render(cco.set_define(low, 'PORTRAIT', '1'), image, factorU=1, **scene, **uniforms())
    assert np.array_equal(plain, zero), 'film scene count zero exact bypass'
    film_error = np.max(np.abs(corrected - render(CORE, image, **uniforms())))
    # Separate programs can contract the identity colour chain into different float operations.
    assert film_error < 3e-6, ('film scene uses the same portrait correction', film_error)
    assert np.max(np.abs((corrected - plain) @ clc.LUMA)) < 3e-7, 'film portrait preserves luminance'
    print(f'film scene / direct portrait error: {film_error:.3g}')

    combine = (cco.SH / 'why/combine.glsl').read_text(encoding='utf-8')
    variants = ({}, {'P3_OUT': '1'}, {'POINT_FUSED': '1', 'RAW_SOURCE': '1'},
                {'POINT_FUSED': '1', 'RAW_SOURCE': '1', 'P3_OUT': '1', 'COLOUR_LOOK': '1'},
                {'TONE_OP': '1', 'FILM_SIM': '1'}, {'FILM_LUT': '1', 'SKY': '1', 'LIGHT_CHROMA': '1'})
    for defs in variants:
        src = combine
        for k, v in dict(defs, PORTRAIT='1').items():
            src = cco.set_define(src, k, v)
        r.ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + cco.expand_imports(src)).release()
    values = dict(uniforms(), portraitMapU=identity)
    rows, scales = [clc.norm(colour(85)), clc.norm(colour(45))], [0.09, 0.18, 0.36]
    for defs in ({}, {'P3_OUT': '1'}):
        base = r.render(rows, scales, defs)
        off = r.render(rows, scales, dict(defs, PORTRAIT='1'), dict(values, portraitCountU=0))
        assert np.array_equal(base, off), ('combine count zero exact bypass', defs)
        away = np.eye(3); away[0, 2] = 2
        background = r.render(rows, scales, dict(defs, PORTRAIT='1'), dict(values, portraitMapU=tuple(away.T.flat)))
        assert np.array_equal(base, background), ('combine mapped background exact bypass', defs)
        active = r.render(rows, scales, dict(defs, PORTRAIT='1'), values)
        assert np.isfinite(active).all() and np.max(np.abs(active[0] - base[0])) > 1e-4, ('combine correction active', defs)
        assert np.array_equal(active[1], base[1]), ('combine natural skin unchanged', defs)

    raw = vf.mosaic(np.broadcast_to(colour(85), (128, 160, 3)), 0, 1, rng, [64] * 4, noise=False)
    raw_texture = r.ctx.texture((160, 128), 1, raw.tobytes(), dtype='u2')
    raw_texture.filter = (clc.moderngl.NEAREST, clc.moderngl.NEAREST)
    curve = r.ctx.texture((2, 1), 1, np.full(2, 0.5, 'f4').tobytes(), dtype='f4')
    source = combine
    for k in ('RAW_SOURCE', 'POINT_FUSED', 'PORTRAIT'):
        source = cco.set_define(source, k, '1')
    raw_values = dict(scene, width=160, height=128, bayer_pattern=0, cfa_mode=1, cfa_block_size=1,
                      crop_left=40, crop_top=24, crop_width=80, crop_height=64, u_black_level=(64,) * 4,
                      u_white_level=1023.0, r_gain=2.0, g_gain=1.0, b_gain=1.6, color_transform=identity,
                      u_lsc_intensity=0.0, fU=1, colourFU=1, vpOriginU=(0, 0), vpSizeU=(80, 64),
                      fusedCurveU=(-16.0, 16.0), macroU=1.0, ditherU=0,
                      u_ldc_aspect=1.25, u_ldc_inv_aspect=0.8, u_ldc_cx=0.5, u_ldc_cy=0.5,
                      u_ldc_k1=0.4, u_ldc_k2=0.1, u_ldc_k3=0.1, u_ldc_p1=0.0)
    face = (0.29, 0.23, 0.47, 0.49)
    yy, xx = np.mgrid[:64, :80] + 0.5
    for mirror in (0, 1):
        for ldc in (0, 1):
            u, v = xx / 80, 1 - yy / 64 if mirror else yy / 64
            if ldc:
                nx, ny = (u - 0.5) * 2, (v - 0.5) * 2 * 0.8
                rr = nx * nx + ny * ny
                radial = (1 + 0.4 * rr + 0.1 * rr * rr) / 1.1
                u, v = np.clip(nx * radial * 0.5 + 0.5, 0, 1), np.clip(ny * radial * 0.5 * 1.25 + 0.5, 0, 1)
            sensor_x, sensor_y = (40 + u * 80) / 160, (24 + v * 64) / 128
            ellipse = ((sensor_x - 0.38) / (0.18 * 0.46)) ** 2 + ((sensor_y - 0.36) / (0.26 * 0.48)) ** 2
            values = dict(raw_values, mirror=mirror, u_ldc_enabled=ldc, **uniforms((face,)))
            textures = dict(sTexture16=raw_texture, FusedCurve=curve)
            base = render(source, np.ones((64, 80, 3)), textures=textures, **dict(values, portraitCountU=0))
            active = render(source, np.ones((64, 80, 3)), textures=textures, **values)
            delta = np.max(np.abs(active - base), axis=2)
            assert np.isfinite(active).all(), ('RAW finite', mirror, ldc)
            assert np.all(delta[ellipse > 1.01] == 0), ('RAW cropped/mirrored/LDC background exact bypass', mirror, ldc)
            assert np.min(delta[ellipse < 0.4]) > 1e-4, ('RAW correction follows sensor face coordinates', mirror, ldc)
    raw_texture.release(); curve.release()
    print('portrait check PASS: GPU/CPU colour oracle, masks, five faces, sample/film, WHY cropped RAW/mirror/LDC and photo/P3')


if __name__ == '__main__':
    main()
