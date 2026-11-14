#!/usr/bin/env python3
"""P671: resolvedn/optics with the Oklab cache (OKLAB_CACHE) gives the same pixels, bit for bit.

WhyMsDenoise's optics pass filters the colour of every pixel the lens shading lifts (its gain > 1.1: most of a phone frame) over a
5 x 5 window in Oklab, each neighbour converted with rgb_to_oklab (three cube roots) - 24 conversions a pixel. With the cache a
pass per strip writes rgb_to_oklab of every pixel of the strip and its two rows above and below into an RGBA32F texture (exact
floats: the same numbers the filter computed), and the filter reads them (OKLAB_CACHE 2). This draws the pass as WhyMsDenoise
does - in one draw without the cache, strip by strip with it (strips of 256, 37 and 5 rows) - on a frame of RGBA16F input with
the shading gain in alpha (below and above the corner threshold, zero, missing), the speckle median off and at three strengths:
the RGBA16F outputs must be equal. It also times both on llvmpipe (indicative only).
"""
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
FAILURES = []


def check(cond, msg):
    if not cond:
        print('FAIL ' + msg)
        FAILURES.append(msg)


def set_define(src, name, value):
    import re
    return '\n'.join(('#define %s %s' % (name, value)) if re.match(r'\s*#\s*define\s+%s(\s|$)' % name, l) else l for l in src.split('\n'))


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: P671 optics cache check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
    progs = {}

    def prog(**defs):
        key = tuple(sorted(defs.items()))
        if key not in progs:
            src = (SH / 'resolvedn/optics.glsl').read_text(encoding='utf-8')
            for k, v in defs.items():
                src = set_define(src, k, v)
            progs[key] = ctx.program(vertex_shader=vs, fragment_shader='#version 430\n' + src)
        return progs[key]

    def uniforms(p, values):
        for k, v in values.items():
            if k in p:
                p[k].value = v

    def run(inp, w, h, cache, strip, speckle):
        p = prog(OKLAB_CACHE=2 if cache else 0)
        base = {'u_width': w, 'u_height': h, 'u_quad_speckle_strength': speckle, 'u_quad_speckle_threshold': 0.15}
        out = ctx.texture((w, h), 4, dtype='f2')
        out.filter = (moderngl.NEAREST, moderngl.NEAREST)
        fbo = ctx.framebuffer([out])
        vao = ctx.vertex_array(p, [])
        t0 = time.time()
        if not cache:
            fbo.use()
            ctx.viewport = (0, 0, w, h)
            inp.use(0)
            uniforms(p, dict(base, u_tex_rgb=0))
            vao.render(vertices=3)
        else:
            c = prog(OKLAB_CACHE=1)
            cvao = ctx.vertex_array(c, [])
            ctex = ctx.texture((w, min(h, strip + 4)), 4, dtype='f4')
            ctex.filter = (moderngl.NEAREST, moderngl.NEAREST)
            cfbo = ctx.framebuffer([ctex])
            for y in range(0, h, strip):
                rows = min(strip, h - y)
                origin, stop = max(0, y - 2), min(h, y + rows + 2)
                cfbo.use()
                ctx.viewport = (0, 0, w, stop - origin)
                inp.use(0)
                uniforms(c, dict(base, u_tex_rgb=0, cacheOriginU=origin))
                cvao.render(vertices=3)
                fbo.use()
                ctx.viewport = (0, y, w, rows)
                inp.use(0)
                ctex.use(1)
                uniforms(p, dict(base, u_tex_rgb=0, OkCache=1, cacheOriginU=origin))
                vao.render(vertices=3)
            cvao.release()
            cfbo.release()
            ctex.release()
        ctx.finish()
        dt = time.time() - t0
        data = out.read()
        vao.release()
        fbo.release()
        out.release()
        return data, dt

    rng = np.random.default_rng(671)
    tried = 0
    timing = None
    for (w, h) in ((211, 133), (64, 300)):
        a = np.concatenate([rng.uniform(-0.05, 1.4, (h, w, 3)), rng.choice([0.0, 1.0, 1.05, 1.2, 2.0, 4.0], (h, w, 1))], -1)
        a[rng.random((h, w)) < 0.03, :3] *= 8.0
        a[rng.random((h, w)) < 0.01, :3] = 0.0
        inp = ctx.texture((w, h), 4, a.astype(np.float16).tobytes(), dtype='f2')
        inp.filter = (moderngl.NEAREST, moderngl.NEAREST)
        for speckle in (0.0, 0.35, 0.7, 1.0):
            whole, _ = run(inp, w, h, False, 0, speckle)
            for strip in (256, 37, 5):
                got, _ = run(inp, w, h, True, strip, speckle)
                tried += 1
                if got != whole:
                    x = np.frombuffer(whole, np.uint16).reshape(h, w, 4)
                    y = np.frombuffer(got, np.uint16).reshape(h, w, 4)
                    ys, xs = np.nonzero((x != y).any(-1))
                    check(False, '%dx%d speckle %.1f strips of %d: %d pixels differ, first at %s'
                          % (w, h, speckle, strip, ys.size, (int(xs[0]), int(ys[0]))))
        inp.release()
    # timing: a 1536 x 1024 frame lifted everywhere (the corner filter on every pixel, as at a gain map mean of 2)
    w, h = 1536, 1024
    a = np.concatenate([rng.uniform(0.0, 1.0, (h, w, 3)), np.full((h, w, 1), 2.0)], -1)
    inp = ctx.texture((w, h), 4, a.astype(np.float16).tobytes(), dtype='f2')
    for k in range(2):
        whole, t0 = run(inp, w, h, False, 0, 0.0)
        got, t1 = run(inp, w, h, True, 256, 0.0)
    check(whole == got, 'timing frame differs')
    timing = (t0, t1)
    print('     resolvedn/optics with the Oklab cache: %d cases, %s; llvmpipe %dx%d: %.0f ms without, %.0f ms with (cache + filter)'
          % (tried, 'bit for bit equal' if not FAILURES else 'DIFFERENT', w, h, 1e3 * timing[0], 1e3 * timing[1]))
    check(tried >= 24, 'at least 24 cases (%d)' % tried)
    print('ALL OK' if not FAILURES else 'FAILED: %d' % len(FAILURES))
    return 1 if FAILURES else 0


if __name__ == '__main__':
    sys.exit(main())
