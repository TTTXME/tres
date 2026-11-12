#!/usr/bin/env python3
"""P619: the gallery editor's «LUT» tab on the GPU (moderngl) against the camera's own stages.

- colour: editor/look.glsl (as EditGl compiles it: the editor's precisions and common.glsl in front) and the camera's ISP
  viewfinder LUT stage (preview/main_fs.glsl's uLutOn block, taken from its source) on the same 8-bit pictures and tables write
  the same RGBA8 bytes, bit for bit (a warm 17^3 look, a random smooth 33^3 one, a .cube domain of 0..2, strengths 1 / 0.37 / 0);
  both equal the numpy trilinear lookup (processing.color.FilmLut.sample) within one code (the 8-bit output's rounding and the
  hardware's sub-texel weights);
- the film grain's tiles: film/noise.glsl, film/blur.glsl and film/grain_sf.glsl (FilmGrain's passes, as EditRenderer runs them)
  on a tile with originU at its corner equal the whole picture's pixels inside the tile beyond the blur's reach; film/grain.glsl
  (our black-and-white model) everywhere;
- the camera is untouched: film/noise.glsl and film/grain.glsl with originU 0 (the camera never sets it) equal the shaders without
  it.
"""
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
FAILURES = []
VS = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
EDITOR_PREFIX = '#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp usampler2D;\n'


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def identity(n):
    b, g, r = np.meshgrid(np.arange(n), np.arange(n), np.arange(n), indexing='ij')
    return np.stack([r, g, b], -1).astype(np.float32) / (n - 1)   # [b][g][r][rgb]: red fastest, as a .cube


def trilinear(lut, rgb, lo, hi):
    n = lut.shape[0]
    v = np.clip((np.clip(rgb, 0, 1) - lo) / (hi - lo), 0, 1) * (n - 1)
    i0 = np.minimum(np.floor(v).astype(int), n - 2)
    p = v - i0
    out = np.zeros_like(rgb)
    for corner in range(8):
        dr, dg, db = corner & 1, (corner >> 1) & 1, corner >> 2
        w = (np.where(dr, p[:, 0], 1 - p[:, 0]) * np.where(dg, p[:, 1], 1 - p[:, 1]) * np.where(db, p[:, 2], 1 - p[:, 2]))
        out += w[:, None] * lut[i0[:, 2] + db, i0[:, 1] + dg, i0[:, 0] + dr]
    return out


def camera_lut_stage():
    """preview/main_fs.glsl's LUT uniforms and its uLutOn block around an 8-bit picture (the ISP frame's place)."""
    src = (SH / 'preview/main_fs.glsl').read_text(encoding='utf-8')
    uniforms = [l for l in src.splitlines() if re.match(r'\s*uniform .*\buLut\w*;', l) and 'uLutOn' not in l]
    m = re.search(r'if \(uLutOn == 1\) \{\n(.*?)\n    \}', src, re.S)
    if m is None or len(uniforms) < 5:
        raise SystemExit('preview/main_fs.glsl: the LUT stage was not found')
    return ('#version 300 es\nprecision highp float;\nuniform sampler2D uImg;\n' + '\n'.join(uniforms)
            + '\nout vec4 Output;\nvoid main() {\n    vec4 color = texelFetch(uImg, ivec2(gl_FragCoord.xy), 0);\n'
            + m.group(1) + '\n    Output = color;\n}\n')


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: editor look check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=330)
    except Exception:
        ctx = moderngl.create_standalone_context(require=330)

    editor_src = EDITOR_PREFIX + (SH / 'editor/common.glsl').read_text(encoding='utf-8') + '\n#line 1\n' \
        + (SH / 'editor/look.glsl').read_text(encoding='utf-8')
    editor = ctx.program(vertex_shader=VS, fragment_shader=editor_src)
    camera = ctx.program(vertex_shader=VS, fragment_shader=camera_lut_stage())

    rng = np.random.default_rng(619)
    W, H = 256, 128
    codes = rng.integers(0, 256, (H, W, 4), dtype=np.uint8)
    ramp = np.arange(256, dtype=np.uint8)
    codes[0, :, 0] = codes[0, :, 1] = codes[0, :, 2] = ramp            # greys
    codes[1, :, 0] = ramp
    codes[1, :, 1] = 255 - ramp
    codes[1, :, 2] = 0
    codes[..., 3] = 255
    pic = ctx.texture((W, H), 4, codes.tobytes())
    pic.filter = (moderngl.NEAREST, moderngl.NEAREST)

    def warm(n):
        lut = identity(n)
        lut[..., 0] = np.minimum(lut[..., 0] * 1.06 + 0.04, 1)
        lut[..., 2] = np.maximum(lut[..., 2] * 0.9 - 0.02, 0)
        return lut

    def smooth_random(n):
        lut = identity(n)
        x = lut.copy()
        for c in range(3):
            lut[..., c] = np.clip(x[..., c] + 0.08 * np.sin(3.1 * x[..., (c + 1) % 3] + c) * np.cos(2.3 * x[..., (c + 2) % 3]), 0, 1)
        return lut

    def render(prog, lut, strength, lo, hi, prefix):
        size = lut.shape[0]
        t3 = ctx.texture3d((size, size, size), 3, np.ascontiguousarray(lut, np.float32).astype(np.float16).tobytes(), dtype='f2')
        t3.filter = (moderngl.LINEAR, moderngl.LINEAR)
        t3.repeat_x = t3.repeat_y = t3.repeat_z = False
        out = ctx.texture((W, H), 4)
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, W, H)
        pic.use(0)
        t3.use(1)
        names = {'img': 'uImg', 'lut': 'uLut'}
        prog[names['img']].value = 0
        prog[names['lut']].value = 1
        prog[prefix + 'Strength'].value = strength
        prog[prefix + 'Size'].value = float(size)
        prog[prefix + 'Min'].value = (lo, lo, lo)
        prog[prefix + 'Scale'].value = (1 / (hi - lo),) * 3
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.uint8).reshape(H, W, 4).copy()
        fb.release()
        out.release()
        t3.release()
        return res

    rgb = codes[..., :3].reshape(-1, 3).astype(np.float32) / 255
    for name, lut in (('warm 17^3', warm(17)), ('random 33^3', smooth_random(33))):
        for lo, hi in ((0.0, 1.0), (0.0, 2.0)):
            for strength in (1.0, 0.37, 0.0):
                a = render(editor, lut, strength, lo, hi, 'uLut')
                b = render(camera, lut, strength, lo, hi, 'uLut')
                same = np.array_equal(a, b)
                lut16 = lut.astype(np.float16).astype(np.float32)
                want = rgb + (trilinear(lut16, rgb, lo, hi) - rgb) * strength
                err = np.abs(a[..., :3].reshape(-1, 3).astype(np.float32) / 255 - np.clip(want, 0, 1)).max() * 255
                print('  %s domain %.0f..%.0f strength %.2f: editor == camera %s, |editor - numpy| %.3f codes'
                      % (name, lo, hi, strength, same, err))
                check(same, '%s %.0f..%.0f %.2f: the editor\'s look differs from the camera\'s LUT stage' % (name, lo, hi, strength))
                check(err <= 1.0, '%s: the look is not the trilinear lookup within one code (%.3f codes)' % (name, err))
                if strength == 0.0:
                    check(np.array_equal(a, codes), 'strength 0 changes the picture')

    # ---- the film grain's tiles
    def film(name, edit=None):
        s = '#version 300 es\n' + (SH / 'film' / name).read_text(encoding='utf-8')
        return edit(s) if edit else s

    noise = ctx.program(vertex_shader=VS, fragment_shader=film('noise.glsl'))
    blur = ctx.program(vertex_shader=VS, fragment_shader=film('blur.glsl'))
    grain_sf = ctx.program(vertex_shader=VS, fragment_shader=film('grain_sf.glsl'))
    grain_bw = ctx.program(vertex_shader=VS, fragment_shader=film('grain.glsl'))
    gw, gh = 300, 200
    base = rng.integers(0, 256, (gh, gw, 4), dtype=np.uint8)
    base[..., 3] = 255
    glut = (0.02 + 0.05 * identity(17)).astype(np.float16)
    g3 = ctx.texture3d((17, 17, 17), 3, glut.tobytes(), dtype='f2')
    g3.filter = (moderngl.LINEAR, moderngl.LINEAR)
    g3.repeat_x = g3.repeat_y = g3.repeat_z = False

    def tex(w, h, data=None, dtype='f1'):
        t = ctx.texture((w, h), 4, data, dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t

    def draw(prog, out, **uniforms):
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, out.width, out.height)
        unit = 0
        for k, v in uniforms.items():
            if isinstance(v, moderngl.Texture) or isinstance(v, moderngl.Texture3D):
                v.use(unit)
                prog[k].value = unit
                unit += 1
            elif k in prog:
                prog[k].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        fb.release()

    def reference_chain(x, y, w, h, sigma, seed=4242.0):
        picture = tex(w, h, np.ascontiguousarray(base[y:y + h, x:x + w]).tobytes())
        a, b = tex(w, h, dtype='f2'), tex(w, h, dtype='f2')
        draw(noise, a, seed=seed, originU=(x, y))
        draw(blur, b, InputBuffer=a, dir=(1.0, 0.0), sigma=sigma)
        draw(blur, a, InputBuffer=b, dir=(0.0, 1.0), sigma=sigma)
        out = tex(w, h)
        draw(grain_sf, out, InputBuffer=picture, Noise=a, GrainLut=g3, scale=0.8, lutSize=17.0)
        res = np.frombuffer(out.read(), np.uint8).reshape(h, w, 4).copy()
        for t in (picture, a, b, out):
            t.release()
        return res

    def classic(x, y, w, h):
        picture = tex(w, h, np.ascontiguousarray(base[y:y + h, x:x + w]).tobytes())
        out = tex(w, h)
        draw(grain_bw, out, InputBuffer=picture, sigma=(0.05, 0.05, 0.0625), cell=(2.7, 2.7, 3.8), sharedPart=0.55, slide=0, mono=0,
             seed=811.0, originU=(float(x), float(y)))
        res = np.frombuffer(out.read(), np.uint8).reshape(h, w, 4).copy()
        picture.release()
        out.release()
        return res

    for sigma in (0.7, 2.6, 5.3):
        reach = int(np.ceil(3 * sigma)) + 1
        whole = reference_chain(0, 0, gw, gh, sigma)
        x, y, w, h = 97, 41, 131, 103
        tile = reference_chain(x, y, w, h, sigma)
        inner = (slice(reach, h - reach), slice(reach, w - reach))
        same = np.array_equal(tile[inner], whole[y:y + h, x:x + w][inner])
        print('  reference grain blur %.1f px: a tile at (%d, %d) == the whole picture beyond %d px: %s' % (sigma, x, y, reach, same))
        check(same, 'the reference grain of a tile differs from the whole picture\'s (blur %.1f)' % sigma)
        check(np.abs(whole[..., :3].astype(int) - base[..., :3].astype(int)).max() > 0, 'the grain changes nothing')
    whole = classic(0, 0, gw, gh)
    tile = classic(97, 41, 131, 103)
    same = np.array_equal(tile, whole[41:144, 97:228])
    print('  black-and-white grain: a tile == the whole picture: %s' % same)
    check(same, 'the black-and-white grain of a tile differs from the whole picture\'s')

    # ---- the camera is untouched: originU 0 is the shader without it (fresh programs: the camera never sets it, GL starts it at 0)
    noise = ctx.program(vertex_shader=VS, fragment_shader=film('noise.glsl'))
    grain_bw = ctx.program(vertex_shader=VS, fragment_shader=film('grain.glsl'))
    old_noise = ctx.program(vertex_shader=VS, fragment_shader=film('noise.glsl', lambda s: s.replace(' + originU', '')
                                                                         .replace('uniform ivec2 originU;', '')))
    old_bw = ctx.program(vertex_shader=VS, fragment_shader=film('grain.glsl', lambda s: s.replace(' + originU', '')
                                                                      .replace('uniform vec2 originU;', '')))
    a, b = tex(gw, gh, dtype='f4'), tex(gw, gh, dtype='f4')
    draw(noise, a, seed=77.0)
    draw(old_noise, b, seed=77.0)
    same_noise = a.read() == b.read()
    picture = tex(gw, gh, base.tobytes())
    c, d = tex(gw, gh), tex(gw, gh)
    kw = dict(sigma=(0.05, 0.05, 0.0625), cell=(2.7, 2.7, 3.8), sharedPart=0.55, slide=0, mono=0, seed=811.0)
    draw(grain_bw, c, InputBuffer=picture, **kw)
    draw(old_bw, d, InputBuffer=picture, **kw)
    same_bw = c.read() == d.read()
    print('  the camera\'s noise / black-and-white grain with originU unset == without it: %s / %s' % (same_noise, same_bw))
    check(same_noise and same_bw, 'originU changes the camera\'s film grain')

    if FAILURES:
        print('editor look check FAILED (%d)' % len(FAILURES))
        return 1
    print('editor look check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
