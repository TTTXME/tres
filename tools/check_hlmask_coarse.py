#!/usr/bin/env python3
"""P713: the highlight landing's mask on a coarse grid (why/hlmask.glsl DECIMATE 1, why/combine.glsl HL_COARSE 1, WhyCombine /
CoarseMask) against the mask on whyLow's grid (as before), on the GPU (moderngl).

Synthetic night / day scenes with a sun and its glow, lit windows, street lights and small LED dots (some on the frame's edges),
lights 0.8 - 6 x the ceiling, with grain, at whyLow sizes of a 3 MP, 12 MP and 50 MP photo (blocks 2, 4 and 8):
- the mask (why/hlmask's three passes, FAST weights as WhyCombine gives them, half-float targets, the combine's bilinear read at
  every whyLow texel) within 0.008 of the old one everywhere, edges included (1 - 2 half-float steps of a share of 2 - 4);
- the photo (why/combine with HL_LANDING on the scene, 8-bit) with either mask: at most 1 level apart, PSNR >= 45 dB, SSIM >= 0.995,
  CIEDE2000 under 1 everywhere (a one-level step of a colour is ~0.5 - 0.9);
- HL_COARSE 1 with the grid (1, 1, 0, 0) and the old mask gives the old photo bit for bit (the uv mapping itself changes nothing);
- the coarse passes' texel-taps are a fraction of the old ones (the GPU work: 1 / 30 and less from 12 MP on).
CoarseMask's plan is mirrored here (its Java unit test pins the same numbers).
"""
import math
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, expand_imports, set_define  # noqa: E402
import post_metrics  # noqa: E402

FAILURES = []


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def plan(low_w, low_h, sigma):
    """CoarseMask.plan (Java Math.round: half up)."""
    block = min(8, int(math.floor(sigma / 6.0 + 0.5)))
    if block < 2:
        return None
    iw, ih = max(1, int(math.floor(low_w / block + 0.5))), max(1, int(math.floor(low_h / block + 0.5)))
    sx, sy = np.float32(low_w / iw), np.float32(low_h / ih)
    var = sigma * sigma - (block * block - 1) / 12.0 - block * block / 12.0
    cs = float(np.float32(math.sqrt(max(var, 0.25)) / (0.5 * (float(sx) + float(sy)))))
    margin = int(math.ceil(3.0 * cs)) + 1
    w, h = iw + 2 * margin, ih + 2 * margin
    return dict(block=block, w=w, h=h, margin=margin, sx=float(sx), sy=float(sy), sigma=cs,
                grid=(iw / w, ih / h, margin / w, margin / h))


def weights(sigma):
    """WhyCombine.hlWeights: float exp of the shader's formula, 160 floats."""
    sigma = max(sigma, 0.5)
    r = int(math.ceil(3.0 * sigma))
    inv = np.float32(-0.5 / np.float32(sigma * sigma))
    w = np.zeros(160, np.float32)
    for i in range(r + 1):
        w[i] = np.float32(math.exp(float(np.float32(i * i) * inv)))
    return w, r


def scene(w, h, seed, night):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    img = np.zeros((h, w, 4), np.float32)
    sky = (0.04 + 0.05 * (1 - y / h)) if night else (0.45 + 0.6 * (1 - y / h))
    img[..., 0], img[..., 1], img[..., 2] = sky * 0.8, sky * 0.9, sky * 1.1
    hor = int(h * 0.55)
    img[hor:, :, :3] = (0.01 if night else 0.08) + 0.05 * rng.random((h - hor, w, 1))
    if not night:
        cx, cy, r = w * 0.7, h * 0.22, w * 0.02
        d = np.hypot(x - cx, y - cy)
        img[..., :3] += (4.0 * np.exp(-np.maximum(d - r, 0) / (w * 0.03)))[..., None] * np.array([1.0, 0.95, 0.8], np.float32)
    for _ in range(60):   # windows, some on the left / right edges
        bx = int(rng.choice([0, w - 30, int(rng.integers(0, w - 40))]))
        by = int(rng.integers(hor, h - 30))
        img[by:by + int(rng.integers(4, 30)), bx:bx + int(rng.integers(4, 40)), :3] = \
            rng.uniform(0.8, 3.0) * np.array([1.0, rng.uniform(0.6, 1.0), rng.uniform(0.3, 1.0)], np.float32)
    for _ in range(200):  # street lights and LED dots
        px, py = int(rng.integers(0, w)), int(rng.integers(0, h))
        m = np.hypot(x - px, y - py) < rng.uniform(1, 5)
        img[m, :3] = rng.uniform(1.5, 6.0) * np.array([1.0, rng.uniform(0.4, 1.0), rng.uniform(0.2, 1.0)], np.float32)
    img[..., :3] *= (1 + 0.05 * rng.standard_normal((h, w, 1))).astype(np.float32)
    img[..., 3] = 1
    return img


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: coarse highlight mask check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    mask_src = expand_imports((SH / 'why/hlmask.glsl').read_text(encoding='utf-8'))
    check('#define DECIMATE 0' in mask_src, 'why/hlmask.glsl: DECIMATE is not 0 by default')
    programs = {}

    def mask_prog(fast, dec):
        key = (fast, dec)
        if key not in programs:
            s = set_define(set_define(mask_src, 'FAST', str(fast)), 'DECIMATE', str(dec))
            programs[key] = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + s)
        return programs[key]

    def draw(p, src, out, size, **u):
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0) + tuple(size)
        src.use(0)
        p['InputBuffer'].value = 0
        for k, v in u.items():
            if k in p:
                p[k].value = v
        ctx.vertex_array(p, []).render(vertices=3)
        fb.release()

    def blur(t, size, sigma):
        w, r = weights(sigma)
        a, b = ctx.texture(size, 1, dtype='f2'), ctx.texture(size, 1, dtype='f2')
        p = mask_prog(1, 0)
        for src, dst, d in ((t, a, (1, 0)), (a, b, (0, 1))):
            p['weightsU'].write(w.tobytes())
            p['radiusU'].value = r
            draw(p, src, dst, size, dirU=d, sigmaU=sigma)
        a.release()
        return b

    def masks(img, inv_clip, sigma):
        """(old mask, None) and (coarse mask, grid) as half-float textures with the combine's linear filter; taps of each."""
        h, w = img.shape[:2]
        t = ctx.texture((w, h), 4, img.astype('f2').tobytes(), dtype='f2')
        share = ctx.texture((w, h), 1, dtype='f2')
        draw(mask_prog(0, 0), t, share, (w, h), dirU=(0, 0), invClipU=inv_clip)
        old = blur(share, (w, h), sigma)
        share.release()
        r_old = int(math.ceil(3 * max(sigma, 0.5)))
        taps_old = w * h * (1 + 2 * (r_old + 1))
        pl = plan(w, h, sigma)
        cs = ctx.texture((pl['w'], pl['h']), 1, dtype='f2')
        draw(mask_prog(0, 1), t, cs, (pl['w'], pl['h']), dirU=(0, 0), invClipU=inv_clip, blockU=pl['block'],
             scaleU=(pl['sx'], pl['sy']), marginU=(pl['margin'], pl['margin']))
        coarse = blur(cs, (pl['w'], pl['h']), pl['sigma'])
        cs.release()
        t.release()
        r_new = int(math.ceil(3 * max(pl['sigma'], 0.5)))
        taps_new = pl['w'] * pl['h'] * (pl['block'] ** 2 + 2 * (r_new + 1))
        for m in (old, coarse):
            m.filter = (moderngl.LINEAR, moderngl.LINEAR)
            m.repeat_x = m.repeat_y = False
        return old, coarse, pl, taps_old, taps_new

    up = ctx.program(vertex_shader=vs, fragment_shader='''#version 300 es
precision highp float; uniform sampler2D T; uniform vec2 size; uniform vec4 grid; out vec4 o;
void main(){ o = vec4(texture(T, gl_FragCoord.xy / size * grid.xy + grid.zw).r, 0, 0, 1); }''')

    def read_mask(tex, size, grid=(1.0, 1.0, 0.0, 0.0)):
        o = ctx.texture(size, 1, dtype='f4')
        fb = ctx.framebuffer([o])
        fb.use()
        ctx.viewport = (0, 0) + tuple(size)
        tex.use(0)
        up['T'].value = 0
        up['size'].value = size
        up['grid'].value = grid
        ctx.vertex_array(up, []).render(vertices=3)
        r = np.frombuffer(o.read(), np.float32).reshape(size[1], size[0]).copy()
        fb.release()
        o.release()
        return r

    # 1. the mask at the whyLow sizes of a 3, 12 and 50 MP photo (the 50 MP one a strip of its width: the sigma is the width's)
    for (w, h) in ((1000, 750), (2040, 1536), (4096, 1024)):
        sigma = max(1.0, float(np.float32(0.012) * w))
        for night in (False, True):
            img = scene(w, h, 5 + w, night)
            t0 = time.time()
            old, coarse, pl, taps_old, taps_new = masks(img, 1.0, sigma)
            a, b = read_mask(old, (w, h)), read_mask(coarse, (w, h), pl['grid'])
            d = np.abs(a - b)
            print('  mask %dx%d sigma %.1f block %d (%dx%d coarse, sigma %.2f, margin %d)%s: max %.4f mean %.5f; taps %.0f -> %.0f M'
                  ' (1/%.0f) [%.1f s]' % (w, h, sigma, pl['block'], pl['w'], pl['h'], pl['sigma'], pl['margin'],
                                          ' night' if night else '', d.max(), d.mean(), taps_old / 1e6, taps_new / 1e6,
                                          taps_old / taps_new, time.time() - t0))
            check(d.max() <= 0.008, 'coarse mask %dx%d differs by %.4f' % (w, h, d.max()))
            check(taps_new * (30 if w >= 2000 else 4) <= taps_old, 'coarse mask %dx%d saves too little (1/%.1f)'
                  % (w, h, taps_old / taps_new))
            old.release()
            coarse.release()

    # 2. the photo through why/combine with the landing, each mask (fU 1: whyLow is the picture's grid)
    src = expand_imports((SH / 'why/combine.glsl').read_text(encoding='utf-8'))
    check('#define HL_COARSE 0' in src, 'why/combine.glsl: HL_COARSE is not 0 by default')
    on = set_define(src, 'HL_LANDING', '1')
    prog_old = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + on)
    prog_new = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + set_define(on, 'HL_COARSE', '1'))
    clip = 2.5

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))

    def render(prog, image, fused, hl, grid):
        h, w = image.shape[:2]
        t_img, t_fused = tex(image), tex(fused)
        out = ctx.texture((w, h), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, w, h)
        units = {'InputBuffer': t_img, 'GainMap': ones, 'WhyLow': t_img, 'WhyColour': t_img, 'WhyFused': t_fused,
                 'WhyDetailRef': t_img, 'WhyLumaS': t_img, 'WhyHl': hl}
        for i, (key, t) in enumerate(units.items()):
            if key in prog:
                t.use(i)
                prog[key].value = i
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': clip, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.0,
                'vibU': (0.0, 0.0, 0.0), 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
                'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 1, 'hlWhiteU': 0.0,
                'hlSoftU': 0.7, 'hlGridU': grid}
        for key, v in vals.items():
            if key in prog:
                prog[key].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(h, w, 4)[:, :, :3]
        for t in (fb, out, t_img, t_fused):
            t.release()
        return np.round(np.clip(res, 0, 1) * 255).astype(np.uint8)

    for night in (False, True):
        w, h = 2040, 1536
        img = scene(w, h, 77, night) * np.float32(clip / 2.0)
        img[..., 3] = 1
        lum = img[..., :3] @ np.array([0.2126, 0.7152, 0.0722], np.float32)
        fused = np.zeros((h, w, 4), np.float32)
        fused[..., 0] = np.clip(lum / (1 + lum), 0, 1) ** (1 / 2.2)
        old, coarse, pl, _, _ = masks(img, 1.0 / clip, float(np.float32(0.012) * w))
        a = render(prog_old, img, fused, old, (1, 1, 0, 0))
        b = render(prog_new, img, fused, coarse, pl['grid'])
        same = render(prog_new, img, fused, old, (1.0, 1.0, 0.0, 0.0))
        m = post_metrics.compare(a, b)
        print('  photo %dx%d%s, landing on: %s' % (w, h, ' night' if night else '', post_metrics.describe(m)))
        check(m['max'] <= 1 and post_metrics.invisible(m) and m['de_max'] < 1.0,
              'the photo with the coarse mask differs visibly (%s)' % post_metrics.describe(m))
        check(np.array_equal(a, same), 'HL_COARSE 1 with the grid (1, 1, 0, 0) is not the old photo bit for bit')
        old.release()
        coarse.release()

    if FAILURES:
        print('coarse highlight mask: %d failure(s)' % len(FAILURES))
        return 1
    print('coarse highlight mask OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
