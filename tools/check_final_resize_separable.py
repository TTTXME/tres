#!/usr/bin/env python3
"""The photo's final resize in two passes (hybrid/final_resize.glsl PASS 1 + 2, HybridFinalResize.separable) against the one-pass
2D kernel (PASS 0, the route before).

The kernel is separable (w = wx wy, every tap's validity per axis), so the horizontal sums normalised by their weights into an
RGBA32F band of input rows, then the vertical sums of the band normalised the same way, are the 2D sums. This draws both on the
desktop GPU (moderngl), the two-pass route band by band as HybridFinalResize plans them (output bands of 256 rows, the input rows
each needs plus the planner's margin; HybridFinalResize.plan mirrored below), for every kernel the photo uses (Lanczos-3, bicubic,
area; bilinear keeps the 2D pass) at the ratios of the photo's resizes (12 -> 8 MP 1.22, 1.5x 1.5, 2x 2.0, 200 MP -> 20 MP 3.16,
4.0) on an RGBA16F display-encoded image (noise, edges, a flat field, clipped whites, values outside [0, 1]). Pass: the RGBA16F
outputs differ by at most 1e-3 (a quarter of an 8-bit level; float summation order), no pixel of the 8-bit output (the final pass's
round(255 v)) moves by more than 1 level, and at most 0.1 % of them move at all. It also times both routes on llvmpipe and counts
the loop steps (indicative only).
"""
import math
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SHADER = ROOT / 'app/src/main/assets/shaders/hybrid/final_resize.glsl'
FAILURES = []
VS = '#version 430\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
TILE_ROWS = 256


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def f32(v):
    return float(np.float32(v))


def reach(n_in, n_out, kernel):
    """HybridFinalResize.reach: the shader's ceil(radius) + 1 along one axis, in float as the shader computes it."""
    scale = np.float32(n_in) / np.float32(n_out)
    radius = np.float32(0.5) * scale if kernel == 2 else np.float32(2.0 if kernel == 1 else 3.0) * scale
    return int(math.ceil(float(radius))) + 1


def plan(n_in, n_out, band_rows, reach_rows):
    """HybridFinalResize.plan: {out row, rows, first input row, input rows} per output band."""
    out = []
    for y0 in range(0, n_out, band_rows):
        rows = min(band_rows, n_out - y0)
        first = ((2 * y0 + 1) * n_in - n_out) // (2 * n_out) - reach_rows
        last = ((2 * (y0 + rows - 1) + 1) * n_in - n_out) // (2 * n_out) + reach_rows
        first, last = max(0, first), min(n_in - 1, last)
        out.append((y0, rows, first, last - first + 1))
    return out


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: separable resize check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = SHADER.read_text(encoding='utf-8')
    check('#define PASS 0' in src, 'final_resize.glsl has no PASS define')
    progs = {p: ctx.program(vertex_shader=VS, fragment_shader='#version 430\n' + src.replace('#define PASS 0', '#define PASS %d' % p, 1))
             for p in (0, 1, 2)}

    def setu(p, **kv):
        for k, v in kv.items():
            if k in p:
                p[k].value = v

    rng = np.random.default_rng(1414)
    cases = [((977, 733), (800, 600)), ((1200, 900), (800, 600)), ((1640, 1230), (820, 615)), ((1600, 1200), (506, 379)),
             ((1280, 960), (320, 240))]
    worst_half = worst_8 = 0
    worst_abs = 0.0
    moved = total = 0
    steps_2d = steps_sep = 0
    t2d = tsep = 0.0
    print('final resize, two passes against the 2D kernel:')
    for (wi, hi), (wo, ho) in cases:
        img = rng.normal(0.45, 0.2, (hi, wi, 3))
        img[:, wi // 3:wi // 3 + 2] = 1.0                                   # a hard vertical line
        img[hi // 2:hi // 2 + 1, :] = 0.0                                   # a hard horizontal one
        img[20:200, 40:300] = 0.62                                          # a flat field
        img[300:420, 500:700] = 1.3                                         # clipped white (above 1: clamped by fetchLin)
        img[rng.random((hi, wi)) < 0.003] = -0.2                            # below 0
        rgba = np.concatenate([img, np.ones((hi, wi, 1))], axis=2).astype(np.float16)
        tex = ctx.texture((wi, hi), 4, rgba.tobytes(), dtype='f2')
        tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        out_a = ctx.texture((wo, ho), 4, dtype='f2')
        out_b = ctx.texture((wo, ho), 4, dtype='f2')
        fa, fb = ctx.framebuffer([out_a]), ctx.framebuffer([out_b])
        for kernel in (0, 1, 2):
            # the 2D pass (as the photo had it)
            p = progs[0]
            tex.use(0)
            setu(p, InputBuffer=0, kernel=kernel, yOffset=0, inSize=(wi, hi), outSize=(wo, ho))
            fa.use()
            ctx.viewport = (0, 0, wo, ho)
            ctx.finish()
            t0 = time.time()
            ctx.vertex_array(p, []).render(vertices=3)
            ctx.finish()
            t2d += time.time() - t0
            # the two passes, band by band
            ry = reach(hi, ho, kernel)
            bands = plan(hi, ho, TILE_ROWS, ry + 2)
            band = ctx.texture((wo, max(b[3] for b in bands)), 4, dtype='f4')
            band.filter = (moderngl.NEAREST, moderngl.NEAREST)
            fband = ctx.framebuffer([band])
            t0 = time.time()
            for y0, rows, first, in_rows in bands:
                p1, p2 = progs[1], progs[2]
                tex.use(0)
                setu(p1, InputBuffer=0, kernel=kernel, inSize=(wi, hi), outSize=(wo, ho), rowOrigin=first)
                fband.use()
                ctx.viewport = (0, 0, wo, in_rows)
                ctx.vertex_array(p1, []).render(vertices=3)
                band.use(0)
                setu(p2, InputBuffer=0, kernel=kernel, inSize=(wi, hi), outSize=(wo, ho), rowOrigin=first)
                fb.use()
                ctx.viewport = (0, y0, wo, rows)
                ctx.vertex_array(p2, []).render(vertices=3)
            ctx.finish()
            tsep += time.time() - t0
            a = np.frombuffer(out_a.read(), np.float16).reshape(ho, wo, 4)
            b = np.frombuffer(out_b.read(), np.float16).reshape(ho, wo, 4)
            ua, ub = a.view(np.uint16).astype(np.int64), b.view(np.uint16).astype(np.int64)
            half_steps = int(np.abs(ua - ub).max())
            max_abs = float(np.abs(a[..., :3].astype(np.float64) - b[..., :3].astype(np.float64)).max())
            q = lambda v: np.clip(np.round(np.clip(v.astype(np.float64), 0, 1) * 255), 0, 255)
            d8 = np.abs(q(a[..., :3]) - q(b[..., :3]))
            worst_half = max(worst_half, half_steps)
            worst_8 = max(worst_8, int(d8.max()))
            moved += int((d8 > 0).sum())
            total += d8.size
            rx = reach(wi, wo, kernel)
            steps_2d += wo * ho * (2 * rx + 1) * (2 * ry + 1)
            steps_sep += sum(wo * b[3] for b in bands) * (2 * rx + 1) + wo * ho * (2 * ry + 1)
            worst_abs = max(worst_abs, max_abs)
            print('  %4dx%-4d -> %4dx%-4d kernel %d: %d bands, RGBA16F max |d| %.1e (%d half steps), 8-bit max %d, moved %.4f %%, mean |d| %.2e'
                  % (wi, hi, wo, ho, kernel, len(bands), max_abs, half_steps, int(d8.max()), 100.0 * (d8 > 0).mean(),
                     float(np.abs(a[..., :3].astype(np.float64) - b[..., :3].astype(np.float64)).mean())))
            check(max_abs <= 1e-3, '%dx%d -> %dx%d kernel %d: %.2e apart' % (wi, hi, wo, ho, kernel, max_abs))
            fband.release()
            band.release()
        for o in (tex, out_a, out_b, fa, fb):
            o.release()
    check(worst_8 <= 1, '8-bit output moved by %d levels' % worst_8)
    check(moved <= total * 1e-3, '%d of %d 8-bit values moved' % (moved, total))
    print('  worst: |d| %.1e (%d half steps), %d 8-bit levels, %d of %d 8-bit values moved; loop steps %.2fx fewer (%d -> %d); llvmpipe %.0f -> %.0f ms'
          % (worst_abs, worst_half, worst_8, moved, total, steps_2d / steps_sep, steps_2d, steps_sep, 1e3 * t2d, 1e3 * tsep))
    if FAILURES:
        print('separable resize check FAILED (%d)' % len(FAILURES))
        return 1
    print('separable resize check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
