#!/usr/bin/env python3
"""P630 / P630c «Oldstyle»: the mode's style (utils/import_oldstyle_adjust.glsl: the palette's tone / colour and the twelve advanced
parameters) on the GPU (moderngl, llvmpipe): the photo pass (oldstyle/adjust.glsl) and the viewfinder (preview/main_fs as
MainRenderer splices it) give the same picture, per parameter and all at once; each parameter does what it says; the frame export
(oldstyle/linear) and the mode's shaders compiling for GLSL ES 3.10 (glslc, when found). P630b: the tone itself is the owner's tone-port module (tools/check_tone_port.py); the GPU passes of our
own earlier tone are gone.
Usage: check_oldstyle_tone.py
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SH = ROOT / 'app/src/main/assets/shaders'
JAVA = ROOT / 'app/src/main/java/com/scamera/app/processing/oldstyle'
sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import expand_imports, set_define, find_glslc  # noqa: E402

FAIL = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def info(msg):
    print('INFO ' + msg)


def tmp(name):
    return os.path.join(TMP, name)


def ints(path, shape):
    return np.fromfile(path, '<i4').reshape(shape)


def put(path, a, dt):
    np.ascontiguousarray(a, dt).tofile(path)
    return path


TMP = tempfile.mkdtemp(prefix='oldstyle_')


# ---------- GPU: OldstyleTone's passes ----------
class Gpu:
    def __init__(self):
        import moderngl
        self.mgl = moderngl
        try:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=430)
        except Exception:
            self.ctx = moderngl.create_standalone_context(require=430)
        self.vs = '#version 310 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
        self.progs = {}

    def prog(self, name, defines):
        key = (name, tuple(sorted(defines.items())))
        if key not in self.progs:
            src = (SH / ('oldstyle/%s.glsl' % name)).read_text(encoding='utf-8')
            for k, val in defines.items():
                src = set_define(src, k, str(val))
            self.progs[key] = self.ctx.program(vertex_shader=self.vs,
                                               fragment_shader='#version 310 es\nprecision highp int;\n' + expand_imports(src))
        return self.progs[key]

    def itex(self, a, pack8=False):
        a = np.ascontiguousarray(a)
        comps = 1 if a.ndim == 1 else (1 if a.ndim == 2 else a.shape[2])
        w, h = (a.shape[0], 1) if a.ndim == 1 else (a.shape[1], a.shape[0])
        t = self.ctx.texture((w, h), comps, a.astype(np.uint8 if pack8 else np.int16).tobytes(), dtype='u1' if pack8 else 'i2')
        t.filter = (self.mgl.NEAREST, self.mgl.NEAREST)
        return t

    def ftex(self, a):
        t = self.ctx.texture((a.shape[1], a.shape[0]), 4, np.ascontiguousarray(a, np.float16).tobytes(), dtype='f2')
        t.filter = (self.mgl.NEAREST, self.mgl.NEAREST)
        return t

    def target(self, w, h, kind):
        t = self.ctx.texture((w, h), 4, dtype=kind)
        t.filter = (self.mgl.NEAREST, self.mgl.NEAREST)
        return t

    def draw(self, p, out, textures, uniforms):
        fbo = self.ctx.framebuffer([out])
        fbo.use()
        self.ctx.viewport = (0, 0) + out.size
        for unit, (k, t) in enumerate(textures.items()):
            t.use(unit)
            if k in p:
                p[k].value = unit
        for k, val in uniforms.items():
            if k in p:
                p[k].value = val
        self.ctx.vertex_array(p, []).render(vertices=3)
        fbo.release()
        return out

    def read(self, t):
        w, h = t.size
        dt = {'i2': np.int16, 'u1': np.uint8, 'f4': np.float32, 'f2': np.float16}[t.dtype]
        return np.frombuffer(t.read(), dt).reshape(h, w, 4).astype(np.float64 if t.dtype in ('f4', 'f2') else np.int64)


PARAMS = ['tone', 'color', 'exposure', 'warmth', 'hue', 'highlights', 'shadows', 'warm_boost', 'cool_boost', 'sharpness', 'vignette',
          'aura', 'halo', 'grain']          # OldstyleStyle.uniforms order (uOs0..uOs3)


def uniforms(**kw):
    u = [0.0] * 16
    for k, v in kw.items():
        u[PARAMS.index(k)] = float(v)
    return {'uOs%d' % i: tuple(u[i * 4:i * 4 + 4]) for i in range(4)}


def test_image(w=160, h=120):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    img = np.stack([x / w, 0.3 + 0.4 * y / h, 1.0 - x / w], -1) * 0.8
    img[20:50, 20:60] = (0.85, 0.35, 0.2)       # a warm patch
    img[20:50, 90:130] = (0.2, 0.4, 0.85)       # a cool patch
    img[70:110, 10:70] = 0.45                   # a flat grey (grain)
    d = np.hypot(x - 115, y - 90)
    img[d < 9] = 1.0                            # a light
    img[60:62, :] = 0.05                        # a thin dark line (sharpness)
    return np.clip(img, 0, 1).astype(np.float32)


class Pair:
    """The photo pass (oldstyle/adjust.glsl) and the viewfinder (preview/main_fs as MainRenderer splices it, its 2D variant) on one
    picture whose screen pixels are its pixels."""
    def __init__(self, g, img):
        self.g, self.img = g, img
        h, w = img.shape[:2]
        self.size = (w, h)
        rgba = np.concatenate([img, np.ones((h, w, 1), np.float32)], -1)
        self.tex = g.ctx.texture((w, h), 4, rgba.astype(np.float32).tobytes(), dtype='f4')
        self.tex.filter = (g.mgl.NEAREST, g.mgl.NEAREST)
        self.tex.repeat_x = self.tex.repeat_y = False
        photo = (SH / 'oldstyle/adjust.glsl').read_text(encoding='utf-8')
        self.photo = g.ctx.program(vertex_shader=g.vs, fragment_shader='#version 310 es\nprecision highp int;\n' + expand_imports(photo))
        main = (SH / 'preview/main_fs.glsl').read_text(encoding='utf-8')
        main = main.replace('//#lensfilter_live', (SH / 'preview/lensfilter_live.glsl').read_text(encoding='utf-8'))
        main = main.replace('//#oldstyle_adjust', (SH / 'utils/import_oldstyle_adjust.glsl').read_text(encoding='utf-8'))
        main = main.replace('//#oldstyle_tone', (SH / 'utils/import_oldstyle_tone_live.glsl').read_text(encoding='utf-8'))
        main = main.replace('#extension GL_OES_EGL_image_external_essl3 : require', '').replace('samplerExternalOES', 'sampler2D')
        vs = ('#version 310 es\nout vec2 texCoord;\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));'
              'texCoord=p;gl_Position=vec4(p*2.-1.,0,1);}')
        self.vf = g.ctx.program(vertex_shader=vs, fragment_shader='#version 310 es\n' + main)
        # the viewfinder's other samplers on units of their own (MainRenderer binds them so: a 3D and a 2D sampler on one unit
        # make the draw invalid)
        for unit, name in ((10, 'uLut'), (11, 'uLfHiTex'), (12, 'uLfLinTex'), (13, 'uOtLut')):
            if name in self.vf:
                self.vf[name].value = unit

    def frame(self, img):
        """Another frame of the same size on the same texture (the programs kept)."""
        h, w = img.shape[:2]
        assert (w, h) == self.size
        self.tex.write(np.concatenate([img, np.ones((h, w, 1), np.float32)], -1).astype(np.float32).tobytes())

    def run(self, **kw):
        u = uniforms(**kw)
        w, h = self.size
        a = self.g.read(self.g.draw(self.photo, self.g.target(w, h, 'f4'), {'InputBuffer': self.tex}, u))[..., :3]
        vu = dict(u, resolution=(float(w), float(h)), uGain=1.0, uOsOn=1, uLutOn=0, uLf=0, mirror=False, enablePeak=False, uOtOn=0)
        b = self.g.read(self.g.draw(self.vf, self.g.target(w, h, 'f4'), {'sTexture': self.tex}, vu))[..., :3]
        return a, b

    def tone(self, grid, level=1.0, **kw):
        """The viewfinder with the tone preview (P643d) under the style: grid = OldstyleLive's FLOATS coefficients (node order row,
        bin, gy, gx; 4 a row), uploaded as MainRenderer does (RGBA16F, GX x 3 GL GY, linear filtering); level = uOtLevel."""
        u = uniforms(**kw)
        w, h = self.size
        tex = np.ascontiguousarray(np.asarray(grid, np.float32).reshape(3 * GL * GY, GX, 4), np.float16)
        lt = self.g.ctx.texture((GX, 3 * GL * GY), 4, tex.tobytes(), dtype='f2')
        lt.filter = (self.g.mgl.LINEAR, self.g.mgl.LINEAR)
        lt.repeat_x = lt.repeat_y = False
        vu = dict(u, resolution=(float(w), float(h)), uGain=1.0, uOsOn=1 if kw else 0, uLutOn=0, uLf=0, mirror=False,
                  enablePeak=False, uOtOn=1, uOtLevel=float(level))
        return self.g.read(self.g.draw(self.vf, self.g.target(w, h, 'f4'), {'sTexture': self.tex, 'uOtLut': lt}, vu))[..., :3]


GX, GY, GL = 16, 12, 8          # OldstyleLive's grid
LUMA3 = np.array([0.2126, 0.7152, 0.0722])


def identity_grid():
    """Every node the identity transform."""
    g = np.zeros((3, GL, GY, GX, 4), np.float64)
    for r in range(3):
        g[r, ..., r] = 1.0
    return g.ravel()


def apply_grid(grid, img, level=1.0):
    """OldstyleLive.apply on an image (u along x, v along y of the texture: row 0 at v = 0), numpy."""
    g = np.asarray(grid, np.float64).reshape(3, GL, GY, GX, 4)
    h, w = img.shape[:2]
    c = np.clip(img.astype(np.float64), 0, 1)
    if level != 1.0:
        c = np.clip(c ** 2.2 * level, 0, 1) ** (1 / 2.2)
    l = np.clip(c @ LUMA3, 0, 1) * (GL - 1)
    b0 = np.minimum(np.floor(l), GL - 2).astype(int)
    fb = l - b0
    yy, xx = np.mgrid[0:h, 0:w]
    gx = np.clip((xx + 0.5) / w * GX - 0.5, 0, GX - 1)
    gy = np.clip((yy + 0.5) / h * GY - 0.5, 0, GY - 1)
    x0, y0 = np.minimum(gx.astype(int), GX - 2), np.minimum(gy.astype(int), GY - 2)
    fx, fy = gx - x0, gy - y0
    x = np.concatenate([c, np.ones((h, w, 1))], -1)
    out = np.zeros((h, w, 3))
    for db in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                wt = (fb if db else 1 - fb) * (fy if dy else 1 - fy) * (fx if dx else 1 - fx)
                for r in range(3):
                    out[..., r] += wt * (g[r, b0 + db, y0 + dy, x0 + dx] * x).sum(-1)
    return np.clip(out, 0, 1)


def adjust_checks(g, rng):
    img = test_image()
    pair = Pair(g, img)
    a, b = pair.run()
    check(np.abs(a - img).max() < 1e-6 and np.abs(b - img).max() < 1e-6, 'style all 0: the picture as it is (photo and viewfinder)')
    worst = 0.0
    outs = {}
    for p in PARAMS:
        steps = (1 / 3, 2 / 3, 1.0) if p in ('aura', 'halo', 'grain') else (-1.0, -0.4, 0.5, 1.0)
        for v in steps:
            a, b = pair.run(**{p: v})
            worst = max(worst, float(np.abs(a - b).max()))
            outs[(p, v)] = a
            check(np.abs(a - b).max() < 2e-6, 'viewfinder == photo: %s %+.2f (largest difference %.1g)' % (p, v, float(np.abs(a - b).max())))
    a, b = pair.run(tone=0.4, color=-0.6, exposure=0.2, warmth=0.5, hue=-0.3, highlights=-0.5, shadows=0.6, warm_boost=0.4,
                    cool_boost=-0.3, sharpness=0.5, vignette=0.6, aura=2 / 3, halo=1 / 3, grain=1.0)
    check(np.abs(a - b).max() < 2e-6, 'viewfinder == photo: every parameter at once')
    Y = lambda x: x @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    sat = lambda x: (x.max(-1) - x.min(-1)).mean()
    grey, warm, cool = (slice(70, 110), slice(10, 70)), (slice(22, 48), slice(22, 58)), (slice(22, 48), slice(92, 128))
    check(Y(outs[('exposure', 1.0)]).mean() > Y(img).mean() + 0.05 and Y(outs[('exposure', -1.0)]).mean() < Y(img).mean() - 0.05,
          'exposure: + lighter, - darker')
    check(Y(outs[('tone', 1.0)]).mean() > Y(img).mean() and Y(outs[('tone', -1.0)]).mean() < Y(img).mean(), 'palette «Тон»: up lighter, down darker')
    c_neg, c_pos = outs[('color', -1.0)], outs[('color', 1.0)]
    check(sat(c_neg) < 0.2 * sat(img) and (c_pos[..., 0] - c_pos[..., 2]).mean() > (img[..., 0] - img[..., 2]).mean() + 0.05
          and sat(c_pos[warm]) > sat(img[warm]), 'palette «Цвет»: left a grey, right warmer and more colourful')
    hi = Y(img) > 0.6
    check(((outs[('warmth', 1.0)] - img)[..., 0] - (outs[('warmth', 1.0)] - img)[..., 2])[hi].mean() > 0.05, 'warmth: the light warmer')
    check(np.abs(outs[('hue', 0.5)][warm] - img[warm]).mean() > 0.05 and abs(Y(outs[('hue', 0.5)][grey]) - Y(img[grey])).max() < 1e-4,
          'hue shift: colours turn, greys stay')
    check(Y(outs[('highlights', -1.0)])[hi].mean() < Y(img)[hi].mean() - 0.05, 'highlights: - darker lights')
    lo = Y(img) < 0.25
    check(Y(outs[('shadows', 1.0)])[lo].mean() > Y(img)[lo].mean() + 0.03, 'shadows: + lighter shadows')
    check(sat(outs[('warm_boost', 1.0)][warm]) > sat(img[warm]) * 1.3 and abs(sat(outs[('warm_boost', 1.0)][cool]) - sat(img[cool])) < 0.01,
          'warm boost: the warm patch more saturated, the cool one as it was')
    check(sat(outs[('cool_boost', 1.0)][cool]) > sat(img[cool]) * 1.3 and abs(sat(outs[('cool_boost', 1.0)][warm]) - sat(img[warm])) < 0.01,
          'cool boost: the cool patch more saturated, the warm one as it was')
    edge = lambda x: np.abs(np.diff(Y(x)[55:67, 30], axis=0)).max()
    check(edge(outs[('sharpness', 1.0)]) > edge(img) * 1.1 and edge(outs[('sharpness', -1.0)]) < edge(img), 'sharpness: + crisper, - softer')
    corner = lambda x: Y(x)[:12, :12].mean()
    check(corner(outs[('vignette', 1.0)]) < corner(img) * 0.7 and corner(outs[('vignette', -1.0)]) > corner(img), 'vignette: + darker corners, - lighter')
    yy, xx = np.mgrid[0:img.shape[0], 0:img.shape[1]]
    dist = np.hypot(xx - 115, yy - 90)
    ring = (dist > 9.5) & (dist < 13) & (np.abs(yy - 61) > 2)
    check(Y(outs[('aura', 1.0)])[ring].mean() > Y(img)[ring].mean() + 0.01, 'aura: a glow round the light')
    rim = (dist > 9.0) & (dist < 10.6) & (np.abs(yy - 61) > 2)   # the halo's ring: 0.6 % of the long side (1 px here)
    check(Y(outs[('halo', 1.0)])[rim].mean() > Y(img)[rim].mean() + 0.005, 'halo: a glow at the light\'s edge')
    check(Y(outs[('grain', 1.0)][grey]).std() > 0.01 and Y(img[grey]).std() < 1e-6 and abs(Y(outs[('grain', 1.0)][grey]).mean() - 0.45) < 0.01,
          'grain: noise on the flat grey, its mean kept')
    main_fs = (SH / 'preview/main_fs.glsl').read_text(encoding='utf-8')
    check('//#oldstyle_adjust' in main_fs and 'oldstyleStyle(' in main_fs, 'viewfinder main_fs splices the style')
    renderer = (ROOT / 'app/src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java').read_text(encoding='utf-8')
    check('shaders/utils/import_oldstyle_adjust.glsl' in renderer, 'MainRenderer splices utils/import_oldstyle_adjust.glsl (the photo\'s file)')
    photo = (SH / 'oldstyle/adjust.glsl').read_text(encoding='utf-8')
    check('#import oldstyle_adjust' in photo, 'the photo pass imports the same file')


def live_tone_checks(g):
    """P643d: the viewfinder's preview of the tone (utils/import_oldstyle_tone_live, spliced by MainRenderer): off it is the frame as
    it is; with the identity grid it is the frame; with any grid it is OldstyleLive.apply (the cell bilinear by the texture
    coordinate, the two luma bins round the pixel's luma, each node an affine 3 x 4) within half-float and filtering; the level is
    applied in linear light before the grid; the style comes after it."""
    h, w = 64, 96
    yy, xx = np.mgrid[0:h, 0:w]
    img = np.stack([xx / (w - 1), (xx / (w - 1)) * 0.8, (yy / (h - 1)) * 0.6], -1).astype(np.float32)
    pair = Pair(g, img)
    _, plain = pair.run()
    check(np.abs(plain - img).max() < 1e-6, 'tone preview off (uOtOn 0): the frame as it is')
    same = pair.tone(identity_grid())
    check(np.abs(same - img).max() < 2e-3, 'tone preview, the identity grid: the frame (%.1g)' % np.abs(same - img).max())
    rng = np.random.default_rng(643)
    grid = identity_grid().reshape(3, GL, GY, GX, 4)
    grid = grid + rng.normal(0, 0.15, grid.shape) * np.array([1, 1, 1, 0.3])
    grid = grid.ravel()
    out = pair.tone(grid).astype(np.float64)
    want = apply_grid(grid, img)
    check(np.abs(out - want).max() < 6e-3, 'tone preview: OldstyleLive.apply of a random grid (%.1g)' % np.abs(out - want).max())
    lev = pair.tone(grid, level=0.5).astype(np.float64)
    want = apply_grid(grid, img, 0.5)
    check(np.abs(lev - want).max() < 6e-3, 'tone preview: the level in linear light before the grid (%.1g)' % np.abs(lev - want).max())
    styled = pair.tone(grid, tone=0.5)
    check(np.abs(styled - out).mean() > 0.01, 'the style applies after the tone preview')
    renderer = (ROOT / 'app/src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java').read_text(encoding='utf-8')
    check('shaders/utils/import_oldstyle_tone_live.glsl' in renderer and 'OLDSTYLE_TONE_MARKER = "//#oldstyle_tone"' in renderer,
          'MainRenderer splices the tone preview into main_fs')


def glslc_checks():
    glslc = find_glslc()
    if not glslc:
        info('glslc not found: the GLSL ES 3.10 compile is skipped')
        return
    sets = [('linear', {'PRE_CCM': 0}), ('linear', {'PRE_CCM': 1}), ('rgb8', {}), ('adjust', {})]
    ok = True
    with tempfile.TemporaryDirectory() as d:
        for name, defs in sets:
            src = (SH / ('oldstyle/%s.glsl' % name)).read_text(encoding='utf-8')
            for k, v in defs.items():
                src = set_define(src, k, str(v))
            f = Path(d) / 'a.frag'
            f.write_text('#version 310 es\nprecision highp int;\n' + expand_imports(src), encoding='utf-8')
            r = subprocess.run([glslc, '--target-env=opengl', '-fshader-stage=fragment', '-std=310es', '-fauto-map-locations',
                                '-fauto-bind-uniforms', '-o', os.devnull, str(f)], capture_output=True, text=True)
            if r.returncode != 0:
                ok = False
                print(r.stderr)
    check(ok, 'oldstyle shaders compile as GLSL ES 3.10 (%d define sets)' % len(sets))


def linear_checks(g):
    """oldstyle/linear's sample rule = OldstyleAe.sample: floor(v x scale x 4095 + 0.5), saturated, negatives kept (PRE_CCM 1, a
    white GainMap and neutral: the input as it is)."""
    rng = np.random.default_rng(631)
    v = (rng.normal(0.3, 1.5, (32, 48, 3))).astype(np.float16).astype(np.float32)
    v[0, 0] = (np.nan, np.inf, -9)
    src = (SH / 'oldstyle/linear.glsl').read_text(encoding='utf-8')
    src = set_define(src, 'PRE_CCM', '1')
    p = g.ctx.program(vertex_shader=g.vs, fragment_shader='#version 310 es\nprecision highp int;\n' + expand_imports(src))
    t = g.ctx.texture((48, 32), 4, np.concatenate([v, np.ones((32, 48, 1), np.float32)], -1).astype(np.float16).tobytes(), dtype='f2')
    gm = g.ctx.texture((2, 2), 4, np.ones((2, 2, 4), np.float16).tobytes(), dtype='f2')
    for tex in (t, gm):
        tex.filter = (g.mgl.NEAREST, g.mgl.NEAREST)
    for scale in (4095.0, 4095.0 * 0.25):
        out = g.draw(p, g.target(48, 32, 'i2'), {'InputBuffer': t, 'GainMap': gm}, {'uScale': scale, 'inScaleU': 1.0,
                                                                                   'neutralPointU': (1.0, 1.0, 1.0)})
        got = g.read(out)[..., :3]
        w = np.where(np.isfinite(v), v, 0).astype(np.float32)
        want = np.clip(np.floor(w * np.float32(scale) + np.float32(0.5)), -32768, 32767)
        check(np.array_equal(got, want), 'oldstyle/linear: the int16 sample = OldstyleAe.sample at scale %g' % (scale / 4095))
    # P716: uDither 1 - the rounding takes the interleaved gradient noise of the pixel (- 0.5 .. 0.5, one value for the three
    # channels): every sample within one level of the plain one, the GPU's as the formula (FP32) gives it, and a flat area's
    # mean is its value (the plain rounding: the nearest level)
    yy, xx = np.indices((32, 48)).astype(np.float32) + np.float32(0.5)
    inner = xx * np.float32(0.06711056) + yy * np.float32(0.00583715)
    inner = inner - np.floor(inner)
    ign = np.float32(52.9829189) * inner
    ign = (ign - np.floor(ign) - np.float32(0.5))[..., None]
    scale = np.float32(4095.0 * 0.25)
    out = g.draw(p, g.target(48, 32, 'i2'), {'InputBuffer': t, 'GainMap': gm}, {'uScale': float(scale), 'inScaleU': 1.0,
                                                                                'neutralPointU': (1.0, 1.0, 1.0), 'uDither': 1.0})
    got = g.read(out)[..., :3]
    w = np.where(np.isfinite(v), v, 0).astype(np.float32)
    plain = np.clip(np.floor(w * scale + np.float32(0.5)), -32768, 32767)
    want = np.clip(np.floor(w * scale + np.float32(0.5) + ign), -32768, 32767)
    same = float((got == want).mean())
    check(np.abs(got - plain).max() <= 1 and same >= 0.99, 'oldstyle/linear dithered: within one level of the plain sample, '
          '%.2f %% as the formula gives it' % (100 * same))
    flat = np.full((32, 48, 4), 1.0, np.float32)
    flat[..., :3] = np.float32(3.3) / scale
    ft = g.ctx.texture((48, 32), 4, flat.astype(np.float16).tobytes(), dtype='f2')
    ft.filter = (g.mgl.NEAREST, g.mgl.NEAREST)
    exact = float(np.float32(np.float16(flat[0, 0, 0])) * scale)
    means = []
    for dith in (0.0, 1.0):
        out = g.draw(p, g.target(48, 32, 'i2'), {'InputBuffer': ft, 'GainMap': gm}, {'uScale': float(scale), 'inScaleU': 1.0,
                                                                                     'neutralPointU': (1.0, 1.0, 1.0), 'uDither': dith})
        means.append(float(g.read(out)[..., :3].mean()))
    check(abs(means[1] - exact) < 0.03 and abs(means[0] - round(exact)) < 1e-9,
          'oldstyle/linear: a flat %.3f - plain %.3f, dithered %.3f' % (exact, means[0], means[1]))


if __name__ == '__main__':
    g = Gpu()
    adjust_checks(g, np.random.default_rng(630))
    live_tone_checks(g)
    linear_checks(g)
    glslc_checks()
    shutil.rmtree(TMP, ignore_errors=True)
    if FAIL:
        print('%d FAILED' % len(FAIL))
        sys.exit(1)
    print('OK')
