#!/usr/bin/env python3
"""P595 (owner, 2026-11-10, vivo X200 Ultra: «белые точки на краях объектов», «на небе у меня градиент появился»).

A. why/sharp_rl's halo clamp at a dark / bright edge (edgeGuardU). The WHY sharpening runs on the G_CLEAN plane - the scene light
   before the AE - and the clamp's margin (0.03 + 0.15 x the 3 x 3 range) and strong-edge test (range 0.15 .. 0.4) are levels of
   that plane: under a short exposure (the owner's backlit ship: a hazy sky at ~0.15, AE x3.5) a dark mast against the sky counts
   as a weak edge and the RL stages overshoot the sky by up to a third of it - white dots and beads on the dark objects' rims.
   A synthetic backlit scene (sky 0.15, dark rounded tops, slits, thin lines, lens blur, a merge's residual noise) through the
   app's default chain (check_why_sharpen.gpu_sharpen): with the guard no pixel within 2 px of a dark / bright edge leaves the
   input's range (9 x 9: two guarded stages of 2 px) by more than 2.5 % of the bright side (the owner's «1 - 2 codes»); without it
   (edgeGuardU 0, the clamp of before) the sky is overshot by 15 - 30 % and the dark side undershot. A texture (1/f, +-10 % at the same
   level) and the response to small detail (amp 0.005 on 0.18) are unchanged (the guard works only past one stop).
B. why/combine's dither (ditherU 1, DITHER_TPDF 1 as WhyCombine sets it for the photo): a slow grey ramp to an 8-bit target - the quantisation noise the same at every level
   (rms at the codes / at the half-codes >= 0.9; the kernel's rectangular +-0.5 LSB gave 0 .. 0.5 LSB: a clean sky's 8-bit steps
   as bands of grain between clean bands), the column means within 0.1 code of the float render, still rising with the ramp.
SHADERS=<dir> renders another copy of the shaders (the commit before P595 fails both parts).
"""
import math
import os
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

FAILURES = []


def check(cond, msg):
    print(('OK   ' if cond else 'FAIL ') + msg)
    if not cond:
        FAILURES.append(msg)


def maxf(a, r):
    p = np.pad(a, r, mode='edge')
    H, W = a.shape
    return np.maximum.reduce([p[r + dy:r + dy + H, r + dx:r + dx + W] for dy in range(-r, r + 1) for dx in range(-r, r + 1)])


def minf(a, r):
    return -maxf(-a, r)


def gauss(a, s):
    r = int(math.ceil(3 * s))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / s) ** 2)
    k /= k.sum()
    p = np.pad(a, ((0, 0), (r, r)), mode='edge')
    a = sum(k[i] * p[:, i:i + a.shape[1]] for i in range(len(k)))
    p = np.pad(a, ((r, r), (0, 0)), mode='edge')
    return sum(k[i] * p[i:i + a.shape[0], :] for i in range(len(k)))


def backlit(h=160, w=256, seed=5):
    """A hazy sky at 0.15 (G_CLEAN level of a short exposure) with dark (0.004) objects: rounded tops, a beam, 1 .. 3 px slits of
    sky, 1 px and 2 px lines; the merge's sharp edges (blur 0.6 px), its residual noise."""
    y, x = np.mgrid[0:h, 0:w].astype(np.float64)
    sky = 0.15 * (1 - 0.1 * y / h)
    m = np.zeros((h, w), bool)
    for cx, top in ((40, 50), (70, 62), (100, 44), (130, 70)):
        m |= (np.abs(x - cx) < 9) & (y > top)
        m |= (x - cx) ** 2 + (y - top) ** 2 < 81
    m[100:108, 20:150] = True
    for gx, gw in ((55, 1), (85, 2), (115, 3)):
        m[110:150, gx:gx + gw] = False
    for k in range(4):
        m |= np.abs((y - 30) - 0.55 * (x - 160 - 18 * k)) < (0.5 + 0.25 * k)
    m |= (x >= 200) & (x < 202) & (y > 90)
    v = np.where(m, 0.004, sky)
    v = gauss(v, 0.6) + np.random.default_rng(seed).normal(0, 0.0008, (h, w))
    return np.maximum(v, 0).astype(np.float32)


def texture(h=128, w=128, seed=9):
    rng = np.random.default_rng(seed)
    fy, fx = np.meshgrid(np.fft.fftfreq(h), np.fft.fftfreq(w), indexing='ij')
    f = np.hypot(fx, fy)
    f[0, 0] = 1
    t = np.real(np.fft.ifft2((rng.normal(size=(h, w)) + 1j * rng.normal(size=(h, w))) / f))
    return (0.15 * (1 + 0.1 * (t - t.mean()) / t.std())).astype(np.float32)


def part_a():
    import check_why_sharpen as C
    import why_sharpen_ref as R
    # the app's defaults (preferences.xml): USM 0.1 / 0.5, no bilateral / local contrast, RL Airy 1 x3 @1, Gaussian 0.5 x3 @1.5
    app = dict(R.X8U, usm_radius=0.1, usm_amount=0.5, usm_thresh=0, bil_radius=0.0, gf_lc=0.0,
               rl=[(2, 1.0, 1.0, 3, 0.0), (1, 0.0, 0.0, 0, 0.0), (0, 0.5, 1.5, 3, 0.0)])
    ya = backlit()
    # the bounds: two guarded stages, each within its input's 5 x 5 range +-1 %, so the result within the input's 9 x 9 range
    lmax, lmin = maxf(ya, 4), minf(ya, 4)
    edge = np.log2((maxf(ya, 2) + 0.002) / (minf(ya, 2) + 0.002)) > 2.0
    edge[:4] = edge[-4:] = False
    edge[:, :4] = edge[:, -4:] = False
    res = {}
    for g in (0.0, 1.0):
        out = C.gpu_sharpen(ya, dict(app, rl_edge_guard=g))[0]
        over = (out - lmax * 1.025)[edge]
        under = (lmin - 0.025 * lmax - out)[edge]   # 2.5 % of the bright side under the dark side's minimum
        res[g] = (out, float((out / lmax)[edge].max()), int((over > 0).sum()), int((under > 0).sum()), float((lmin - out)[edge].max() / 0.15))
        print('     guard %.0f: edge px %d, brightest / bright side 9 x 9 max %.3f (> +2.5 %%: %d px); under the dark side by > 2.5 %% '
              'of the sky: %d px (most %.1f %%)' % (g, edge.sum(), res[g][1], res[g][2], res[g][3], 100 * res[g][4]))
    check(res[0.0][1] > 1.10 and res[0.0][3] > 0,
          'without the guard the RL stages overshoot a dark object against a dim sky by > 10 %% (%.1f %%) and undershoot its dark side'
          % (100 * (res[0.0][1] - 1)))
    check(res[1.0][2] == 0 and res[1.0][3] == 0,
          'with the guard no pixel at a dark / bright edge leaves the input\'s range by > 2.5 % of the bright side')
    gain = (res[1.0][0] - ya)[edge]
    check(np.abs(gain).max() > 0.002, 'the edge is still sharpened inside its range')
    t = texture()
    a0 = C.gpu_sharpen(t, dict(app, rl_edge_guard=0.0))[0]
    a1 = C.gpu_sharpen(t, dict(app, rl_edge_guard=1.0))[0]
    hp = lambda a: (a - gauss(a, 2.0))[8:-8, 8:-8].std()
    check(abs(hp(a1) / hp(a0) - 1) < 0.005 and np.abs(a1 - a0).max() < 1e-3,
          'texture at the same level sharpened as before (high-pass %.5f / %.5f, max diff %.1e)' % (hp(a1), hp(a0), np.abs(a1 - a0).max()))
    m0 = C.mtf_gain(lambda a: C.gpu_sharpen(a, dict(app, rl_edge_guard=0.0))[0], 0.3)
    m1 = C.mtf_gain(lambda a: C.gpu_sharpen(a, dict(app, rl_edge_guard=1.0))[0], 0.3)
    check(abs(m1 - m0) < 1e-3, 'small detail (0.3 cycles / px) as before (%.3f / %.3f)' % (m1, m0))


def part_b():
    import moderngl
    from check_colour_output import SH, expand_imports, set_define
    sh = Path(os.environ['SHADERS']) if os.environ.get('SHADERS') else SH
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = set_define(set_define(expand_imports((sh / 'why/combine.glsl').read_text(encoding='utf-8')), 'TONE_OP', '0'), 'DITHER_TPDF', '1')
    W, H = 1024, 64
    grey = np.linspace(0.30, 0.36, W, dtype=np.float32)
    img = np.ones((H, W, 4), np.float32)
    img[..., :3] = grey[None, :, None]
    fused = np.zeros((H, W, 4), np.float32)
    fused[..., 0] = (grey[None, :] / 1.5) ** (1 / 2.2)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones, t_img, t_fused = tex(np.ones((1, 1, 4), np.float32)), tex(img), tex(fused)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    prog = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + src)

    def render(dither, dtype):
        out = ctx.texture((W, H), 4, dtype=dtype)
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, W, H)
        for i, (k, t) in enumerate({'InputBuffer': t_img, 'GainMap': ones, 'WhyLow': t_img, 'WhyColour': t_img, 'WhyFused': t_fused,
                                    'WhyDetailRef': t_img, 'WhyLumaS': t_img}.items()):
            if k in prog:
                t.use(i)
                prog[k].value = i
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.0,
                'vibU': (0.0, 0.0, 0.0), 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': dither,
                'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 0, 'hlWhiteU': 0.0}
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        r = np.frombuffer(out.read(), {'f4': np.float32, 'f1': np.uint8}[dtype]).reshape(H, W, 4)[..., :3].astype(np.float64)
        fb.release()
        out.release()
        return r if dtype == 'f1' else r * 255.0
    smooth = render(0, 'f4')
    q = render(1, 'f1')
    span = smooth[0, :, 1].max() - smooth[0, :, 1].min()
    res = q - smooth
    frac = np.abs(np.mod(smooth, 1.0) - 0.5)
    at_code, half = res[frac > 0.4].std(), res[frac < 0.1].std()
    cm = q.mean(0)
    err = np.abs(cm - smooth.mean(0))
    sm8 = cm[:, 1].reshape(-1, 32).mean(1)
    print('     ramp %.1f codes over %d px; noise rms at the codes %.3f, at the half-codes %.3f; column-mean error %.3f (max %.3f)'
          % (span, W, at_code, half, err.mean(), err.max()))
    check(span > 8, 'the ramp spans several 8-bit codes')
    check(at_code / half >= 0.9, 'the quantisation noise does not follow the level (rms at the codes / at the half-codes %.2f >= 0.9)'
          % (at_code / half))
    check(err.mean() < 0.1 and np.all(np.diff(sm8) > 0), 'the 8-bit ramp keeps the float ramp on average and rises (32 px means)')


def main():
    try:
        import moderngl  # noqa: F401
    except ImportError:
        print('moderngl missing: P595 check skipped')
        return 0
    part_a()
    part_b()
    print('ALL OK' if not FAILURES else 'FAILED: %d' % len(FAILURES))
    return 1 if FAILURES else 0


if __name__ == '__main__':
    sys.exit(main())
