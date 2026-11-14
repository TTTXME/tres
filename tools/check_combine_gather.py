#!/usr/bin/env python3
"""P671: why/combine.glsl GATHER_TAPS 1 gives the photo the same pixels, bit for bit.

The photo's combine reads its one-channel windows - the 4 x 4 B-spline taps of WhyFused and WhyDetailRef, the edge hold's 3 x 3
scene luma (WhySceneY) and the 5 x 5 range of WhyLumaS - as 2 x 2 textureGather blocks (CLAMP_TO_EDGE: an out-of-range texel is
the edge's, as texelFetch at the clamped position) and adds them in the order the single reads did. This renders the shader with
the photo's defines (EDGE_HOLD, EDGE_LUMA_CACHE, RIM_SMOOTH, HL_LOCAL ...) with GATHER_TAPS 0 and 1 over deterministic random
textures of the photo's sizes (the whyLow grid half the output, odd sizes so the windows hit every border) and uniforms that
take the windows' paths (2x grid, the detail reference, the sharpened luma, the edge hold, a detail gain above 1), several seeds
and both grids (f 2 and 1): the RGBA32F outputs must be equal. It also times both on llvmpipe (indicative only).
"""
import sys
import time
import zlib
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_post_bands as cb  # noqa: E402

FAILURES = []


def check(cond, msg):
    if not cond:
        print('FAIL ' + msg)
        FAILURES.append(msg)


PHOTO = [('DITHER_TPDF', '1'), ('HL_LOCAL', '1'), ('HL_LOCAL_SMOOTH', '1'), ('RIM_SMOOTH', '1'), ('EDGE_HOLD', '1'),
         ('WHY_BASE', '1'), ('WHY_VIBRANCE', '1'), ('HL_LANDING', '1'), ('EDGE_LUMA_CACHE', '1')]


def source(gather, extra=()):
    src = cb.expand((cb.SH / 'why/combine.glsl').read_text(encoding='utf-8'))
    for k, v in PHOTO + list(extra):
        src = cb.set_define(src, k, v)
    src = cb.set_define(src, 'GATHER_TAPS', '1' if gather else '0')
    return cb.with_header(src)


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: P671 combine gather check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    W, H = 173, 131
    LW, LH = (W + 1) // 2, (H + 1) // 2
    sizes = {'WhyLow': (LW, LH), 'WhyColour': (LW, LH), 'WhyFused': (LW, LH), 'WhyDetailRef': (LW, LH), 'WhyHl': (LW, LH),
             'WhySky': (LW, LH)}
    one = {'WhyFused', 'WhyDetailRef', 'WhyLumaS', 'WhySceneY'}
    tried = 0
    times = {0: 0.0, 1: 0.0}
    for extra in ((), (('SUN_BLOOM', '1'), ('HL_OPPOSED', '1'))):
        progs = {}
        for g in (0, 1):
            progs[g] = ctx.program(vertex_shader=cb.VS, fragment_shader=source(g, extra))
        for seed in range(4):
            for f in (2, 1):
                outs = {}
                for g in (0, 1):
                    prog = progs[g]
                    rng = np.random.default_rng(seed * 7 + f)
                    textures, unit = [], 0
                    for name in sorted(n for n in prog if isinstance(prog[n], moderngl.Uniform)):
                        m = prog[name]
                        if m.gl_type == 0x8B5E:  # sampler2D
                            w, h = sizes.get(name, (W, H))
                            comps = 1 if name in one else 4
                            a = rng.uniform(0.0, 1.3, (h, w, comps)).astype('f4')
                            a[rng.random((h, w)) < 0.05] *= 6.0   # bright spots: the windows' ranges and the rims
                            t = ctx.texture((w, h), comps, a.tobytes(), dtype='f4')
                            t.filter = (moderngl.NEAREST, moderngl.NEAREST)
                            t.repeat_x = t.repeat_y = False
                            t.use(unit)
                            m.value = unit
                            textures.append(t)
                            unit += 1
                    urng = np.random.default_rng(zlib.crc32(b'combine') + seed)  # the other uniforms as check_post_bands does
                    for name in sorted(n for n in prog if isinstance(prog[n], moderngl.Uniform)):
                        m = prog[name]
                        if m.gl_type in (0x8B5E, 0x8B5F, 0x8DD2, 0x8DCA):
                            continue
                        n = m.array_length * m.dimension
                        if m.gl_type in (0x1404, 0x8B53, 0x8B54, 0x8B55, 0x1405, 0x8DC6, 0x8DC7, 0x8DC8, 0x8B56):
                            vals = [int(v) for v in urng.integers(0, 3, n)]
                        else:
                            vals = [float(v) for v in urng.uniform(0.1, 1.0, n)]
                        try:
                            m.value = vals[0] if n == 1 else (tuple(vals) if m.array_length == 1 else
                                                              [tuple(vals[i * m.dimension:(i + 1) * m.dimension]) if m.dimension > 1
                                                               else vals[i] for i in range(m.array_length)])
                        except Exception:
                            pass
                    for k, v in (('fU', f), ('colourFU', f), ('detailRefU', 1), ('sharpU', 1), ('fullInputU', 1), ('edgeHoldU', 0.8),
                                 ('edgeHoldLightU', 0.5), ('detailGainU', 1.3), ('aeU', 0.6), ('sharpToneU', 1.0), ('clipU', 1.4)):
                        if k in prog:
                            prog[k].value = v
                    out = ctx.texture((W, H), 4, dtype='f4')
                    fb = ctx.framebuffer([out])
                    fb.use()
                    ctx.viewport = (0, 0, W, H)
                    quad = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], 'f4').tobytes())
                    vao = ctx.vertex_array(prog, [(quad, '2f', 'vPosition')])
                    vao.render(mode=5, vertices=4)
                    ctx.finish()
                    t0 = time.time()
                    vao.render(mode=5, vertices=4)
                    ctx.finish()
                    times[g] += time.time() - t0
                    outs[g] = out.read()
                    for t in textures + [out]:
                        t.release()
                    vao.release()
                    quad.release()
                    fb.release()
                tried += 1
                if outs[0] != outs[1]:
                    a = np.frombuffer(outs[0], 'u4').reshape(H, W, 4)
                    b = np.frombuffer(outs[1], 'u4').reshape(H, W, 4)
                    ys, xs = np.nonzero((a != b).any(-1))
                    check(False, 'combine %s seed %d f %d: %d pixels differ with GATHER_TAPS 1, first at %s'
                          % (dict(extra) or 'photo', seed, f, ys.size, (int(xs[0]), int(ys[0]))))
        for p in progs.values():
            p.release()
    print('     why/combine (photo defines) GATHER_TAPS 0 / 1: %d renders, %s; llvmpipe %.0f / %.0f ms (indicative)'
          % (tried, 'bit for bit equal' if not FAILURES else 'DIFFERENT', 1e3 * times[0], 1e3 * times[1]))
    check(tried >= 16, 'at least 16 renders (%d)' % tried)
    print('ALL OK' if not FAILURES else 'FAILED: %d' % len(FAILURES))
    return 1 if FAILURES else 0


if __name__ == '__main__':
    sys.exit(main())
