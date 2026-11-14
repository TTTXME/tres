#!/usr/bin/env python3
"""P713: WhyLumaSharpen above 100 MP on row stripes with a halo (WhyLumaSharpen.issueStriped) gives the full grid's sharpened luma
bit for bit, on the GPU (moderngl).

The passes of the full grid as WhyLumaSharpen.issue draws them above 100 MP (no R1, no lattice, no guided filter): why/sharp_in
(the guide, lens shading by the bicubic gain map; one full plane), why/sharp_compose (USM, halo control, the Sobel boost; GATHER 1), and two RL
stages of why/sharp_rl (half 2 through textureGather, PSF_HALF 2, and half 3 through the loop; three iterations each, damping and
the edge guard on), each into an R16F plane. Then the same on stripes: the guide's rows of the stripe and its halo copied out (why/sharp_place),
the same passes on the stripe's planes, why/sharp_place of its own rows into the full plane. The halo is WhyLumaSharpen.stripeHalo's
(mirrored here); stripes of 64 / 96 rows (many boundaries, a short last one) on a 160 x 517 frame with edges, texture, lights and
a dark / bright border. The R16F halves must be equal - and a halo of 8 rows must not be (the check can fail).
"""
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, expand_imports, set_define  # noqa: E402

FAILURES = []
VS = '#version 310 es\nin vec4 vPosition;\nvoid main(){gl_Position=vPosition;}\n'


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def stripe_halo(nb, halves, iters):
    """WhyLumaSharpen.stripeHalo."""
    h = 11 + max(0, nb) + 8
    for half, n in zip(halves, iters):
        h += max(0, n) * (2 * min(max(half, 0), 8) + nb + 2)
    return (h + 15) // 16 * 16


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: sharpening stripes check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)

    def prog(name, **defines):
        src = expand_imports((SH / ('why/%s.glsl' % name)).read_text(encoding='utf-8'))
        for k, v in defines.items():
            src = set_define(src, k, str(v))
        return ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\nprecision highp int;\n' + src)

    p_in = prog('sharp_in')
    p_comp = prog('sharp_compose', GATHER=1)
    p_rl2, p_rl0 = prog('sharp_rl', PSF_HALF=2), prog('sharp_rl')
    p_place = prog('sharp_place')
    quad = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], 'f4').tobytes())

    def draw(p, target, rows, textures, uniforms):
        fb = ctx.framebuffer([target])
        fb.use()
        vao = ctx.vertex_array(p, [(quad, '2f', 'vPosition')])
        for i, (name, t) in enumerate(textures.items()):
            t.use(i)
            p[name].value = i
        for k, v in uniforms.items():
            if k in p:
                p[k].value = v
        ctx.viewport = (0, rows[0], target.size[0], rows[1] - rows[0])
        vao.render(mode=moderngl.TRIANGLE_STRIP, vertices=4)
        vao.release()
        fb.release()

    def plane(w, h):
        t = ctx.texture((w, h), 1, dtype='f2')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t.repeat_x = t.repeat_y = False
        return t

    W, H = 160, 517
    rng = np.random.default_rng(7130)
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    img = np.zeros((H, W, 4), np.float32)
    img[..., :3] = (0.05 + 0.25 * (np.sin(x * 0.31) * np.cos(y * 0.17) > 0) + 0.1 * (x > W / 2))[..., None]
    img[(y // 37 + x // 23) % 5 == 0, :3] = 2.5                              # lights over the clip
    img[:, :8, :3] = 0.002                                                   # a dark border against them
    img[..., :3] *= (1 + 0.08 * rng.standard_normal((H, W, 3))).astype(np.float32)
    img[..., 3] = 1
    image = ctx.texture((W, H), 4, img.astype('f2').tobytes(), dtype='f2')
    image.filter = (moderngl.NEAREST, moderngl.NEAREST)
    gm = (1 + 0.2 * rng.random((13, 17, 4))).astype('f4')
    gain = ctx.texture((17, 13), 4, gm.tobytes(), dtype='f4')
    gain.filter = (moderngl.LINEAR, moderngl.LINEAR)
    gain.repeat_x = gain.repeat_y = False
    eye = tuple(np.eye(3).flatten())
    colour = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1.0, 0.9, 1.1), 'inScaleU': 1.0, 'mulU': 1.0}
    compose = {'downU': 1, 'gfLcU': 0.0, 'usmSigmaU': 0.6, 'usmAmountU': 0.15, 'usmThreshU': 0.0, 'bilAmountU': 0.0, 'bilRadiusU': 1,
               'bilSigmaSU': 0.5, 'bilSigmaLU': 0.1, 'nbU': 1, 'haloU': 1.0, 'protectShadowsU': 0.0, 'protectHighlightsU': 1.0,
               'grainU': 0.0, 'sobelU': (0.3, 0.02), 'thinU': 0.1}

    def quadrant(half, sigma):
        q = np.zeros(81, np.float32)
        tot = 0.0
        for dy in range(-half, half + 1):
            for dx in range(-half, half + 1):
                tot += math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
        for dy in range(half + 1):
            for dx in range(half + 1):
                q[dy * (half + 1) + dx] = math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma)) / tot
        return q
    stages = [(p_rl2, 2, quadrant(2, 0.9), 0.3), (p_rl0, 3, quadrant(3, 1.2), 0.45)]
    iters = 3

    def guide():
        ya = plane(W, H)
        draw(p_in, ya, (0, H), {'InputBuffer': image, 'GainMap': gain}, colour)
        return ya
    ya_full = guide()

    def chain(w, h, origin):
        """The passes after the guide on a w x h plane whose row 0 is the image's row origin (None: the full grid)."""
        if origin is None:
            ya = ya_full
        else:
            ya = plane(w, h)
            draw(p_place, ya, (0, h), {'InputBuffer': ya_full}, {'originU': (0, -origin)})
        obs = plane(w, h)
        draw(p_comp, obs, (0, h), {'InputBuffer': ya, 'GfAB': ya}, compose)
        ratio = plane(w, h)
        for p, half, q, amount in stages:
            est = obs
            for it in range(iters):
                p['psfU'].write(q[:p['psfU'].array_length].tobytes())
                draw(p, ratio, (0, h), {'Observed': obs, 'Estimate': est}, {'modeU': 0, 'psfHalfU': half})
                nxt = plane(w, h)
                p['psfU'].write(q[:p['psfU'].array_length].tobytes())
                draw(p, nxt, (0, h), {'Observed': obs, 'Estimate': est, 'Ratio': ratio},
                     {'modeU': 2 if it == iters - 1 else 1, 'psfHalfU': half, 'amountU': amount, 'dampU': 0.5, 'haloU': 1.0,
                      'marginU': 0.15, 'macroU': 0.4, 'edgeGuardU': 1.0, 'protectShadowsU': 0.0, 'protectHighlightsU': 1.0, 'nbU': 1})
                est = nxt
            obs = est
        return obs

    whole = np.frombuffer(chain(W, H, None).read(), np.uint16).reshape(H, W)
    halo = stripe_halo(1, [2, 3], [iters, iters])

    def striped(rows, halo_rows):
        out = plane(W, H)
        for y0 in range(0, H, rows):
            y1 = min(H, y0 + rows)
            a, b = max(0, y0 - halo_rows), min(H, y1 + halo_rows)
            s = chain(W, b - a, a)
            draw(p_place, out, (y0, y1), {'InputBuffer': s}, {'originU': (0, a)})
        return np.frombuffer(out.read(), np.uint16).reshape(H, W)

    for rows in (64, 96):
        got = striped(rows, halo)
        bad = int((got != whole).sum())
        print('  stripes of %d rows + 2 x %d rows of halo over %dx%d: %s' % (rows, halo, W, H,
                                                                           'bit for bit the full grid' if bad == 0 else '%d halves differ' % bad))
        check(bad == 0, 'stripes of %d rows differ from the full grid in %d halves' % (rows, bad))
    short = striped(64, 8)
    differ = int((short != whole).sum())
    print('  a halo of 8 rows instead: %d halves differ (the check sees a short halo)' % differ)
    check(differ > 0, 'a short halo went unnoticed: the check does not exercise the stripes\' edges')
    print('sharpening stripes %s (halo %d rows: WhyLumaSharpen.stripeHalo)' % ('OK' if not FAILURES else 'FAILED', halo))
    return 1 if FAILURES else 0


if __name__ == '__main__':
    sys.exit(main())
