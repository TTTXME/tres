#!/usr/bin/env python3
"""P687: a Quad shot's sharpening must not lift the merge's grid at straight edges into steps.

Owner, 2026-11-14 (vivo X200 Ultra, 50 MP Quad, «Быстрый», SCA_20261114_094230: «сетка» on the lever keys' edges): the Quad
merge leaves a straight edge's shoulders a faint grid of its 2 x 2 colour blocks (the reference app's Quad bursts: within 3 px of
an edge, mostly the blocks' checker - the diagonal 4 px period - on both sides), and the Quad sharpening (P542: the lattice-free
source of mode 3, which takes out the 2 px alternations only, and the third RL stage at x2) lifted it about x2 into 2 - 4 px
steps along diagonal and slanted edges. why/sharp_r1 mode 4 smooths the sharpening's increment along straight edges only.

A synthetic luma: dark bars (0.05) on a bright ground (0.5) at 8, 30, 52 and 75 degrees, the blocks' checker added 2 px inside
and 1 px outside each side (0.6 % of the contrast), a 5 % random texture, a flat noisy patch; the same scene without the checker.
The shipped shaders (moderngl): sharp_r1 mode 3 (latCapU as WhyTone.latticeCap's default) and mode 1, sharp_compose (the app's
USM), the RL stages (Airy 1 x3 at 1, Gaussian 0.5 x3 at 1.5 x QUAD_RL3_BOOST 2), then mode 2 (before) / mode 4 (P687):
  1. mode 4 with edgeAlongU 0 equals mode 2 bit for bit;
  2. the grid's gain at the edges (the output's change by the checker over the checker, rms within 4 px of the sides): mode 2
     lifts it at least x1.4 (the steps this check exists for), mode 4 keeps at most 0.7 of mode 2's lift;
  3. no grid of mode 4's own: on the scene without the checker, mode 4 - mode 2 along the edges has no mean per x, y mod 4 above
     0.05 % of the contrast;
  4. the edges keep their sharpening across: the 20 - 80 % rise of each side's mean profile (over its length) within 1 % of
     mode 2's (and shorter than the input's);
  5. the texture keeps its sharpening (its high-pass energy within 1 % of mode 2's); the flat patch within 1 % of its noise.
Usage: python tools/check_quad_edge_sharpen.py
"""
import math
import sys
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_why_sharpen as C  # noqa: E402  (the shaders through moderngl, the Python port of WhyLumaSharpen's passes)
import why_sharpen_ref as R  # noqa: E402

N = 256
LO, HI = 0.05, 0.5
ANGLES = (8.0, 30.0, 52.0, 75.0)
GRID = 0.006                   # of the contrast: the blocks' checker at an edge's shoulders
LAT_CAP = 0.005                # WhyTone.latticeCap() default (P441)
# WhyLumaSharpen at the defaults (preferences.xml), a Quad shot: rl3 at QUAD_RL3_BOOST x 1.5
P = dict(R.X8U, usm_radius=0.1, usm_amount=0.5, usm_thresh=0, bil_radius=0.0, gf_lc=0.0,
         rl=[(2, 1.0, 1.0, 3, 0.0), (1, 0.0, 0.0, 0, 0.0), (0, 0.5, 1.5 * 2.0, 3, 0.0)])
erf = np.vectorize(math.erf)
YY, XX = np.mgrid[0:N, 0:N]
CHECKER = np.where(((XX >> 1) + (YY >> 1)) & 1, 1.0, -1.0)   # the 2 x 2 blocks' checker (period 4 on the diagonals)


def bars():
    return [(40 + 58 * k, 64, math.radians(a)) for k, a in enumerate(ANGLES)]


def scene(grid, seed=7):
    """the luma; and the mask of the bars' sides (within 4 px)"""
    rng = np.random.default_rng(seed)
    img = np.full((N, N), HI)
    near = np.zeros((N, N), bool)
    for cx, cy, t in bars():
        s = np.abs((XX - cx) * -math.sin(t) + (YY - cy) * math.cos(t)) - 6.0      # signed distance to the sides (inside < 0)
        inside = 0.5 * (1 - erf(s / (0.7 * math.sqrt(2))))                        # the lens: a Gaussian edge of sigma 0.7 px
        shoulders = np.exp(-0.5 * (s + 2.0) ** 2) + np.exp(-0.5 * (s - 1.0) ** 2)
        m = (YY < 128) & (np.abs((XX - cx) * math.cos(t) + (YY - cy) * math.sin(t)) < 60)
        img = np.where(m, img - (HI - LO) * (inside + grid * CHECKER * shoulders), img)
        near |= m & (np.abs(s) < 4)
    f = np.fft.fftfreq(128)
    spec = (rng.normal(size=(128, 128)) + 1j * rng.normal(size=(128, 128))) / np.maximum(np.hypot(f[:, None], f[None, :]), 1 / 64)
    tex = np.real(np.fft.ifft2(spec)); tex /= tex.std()
    img[128:, :128] = 0.3 * (1 + 0.05 * tex)
    img[128:, 128:] = 0.3
    img += rng.normal(0, 0.002, img.shape) * np.sqrt(np.maximum(img, 0) / 0.3)
    return np.clip(img, 0, 1).astype(np.float32), near


def sharpen(ya, edge_along):
    """WhyLumaSharpen's Quad path: mode 3 / mode 1, the chain on the source, mode 2 (edge_along None) or mode 4"""
    L = C.tex(ya)
    src = C.run('why/sharp_r1', C.empty(N, N, 1), {'InputBuffer': L}, modeU=3, latCapU=LAT_CAP)
    res = C.run('why/sharp_r1', C.empty(N, N, 1), {'InputBuffer': L, 'Smoothed': src}, modeU=1)
    S = C.tex(C.gpu_sharpen(C.read(src)[..., 0], P)[0])
    if edge_along is None:
        out = C.run('why/sharp_r1', C.empty(N, N, 1), {'Sharpened': S, 'Residual': res}, modeU=2)
    else:
        out = C.run('why/sharp_r1', C.empty(N, N, 1), {'Sharpened': S, 'Residual': res, 'Source': src}, modeU=4, edgeAlongU=edge_along)
    return C.read(out)[..., 0]


def acutance(img):
    """the edges' sharpness across: per side, the mean profile over its length (0.5 px bins of the distance to it, -5 .. 5 px),
    its 20 - 80 % rise in px between the bar's inside and the ground (the mean of the sides; smaller = sharper)"""
    out = []
    for cx, cy, t in bars():
        sd = (XX - cx) * -math.sin(t) + (YY - cy) * math.cos(t)
        along = np.abs((XX - cx) * math.cos(t) + (YY - cy) * math.sin(t))
        for side in (-1, 1):
            s = side * sd - 6.0
            m = (np.abs(s) <= 5) & (along < 50) & (YY < 124)
            b = np.clip(np.round(s[m] * 2).astype(int) + 10, 0, 20)
            prof = np.bincount(b, img[m].astype(np.float64), 21) / np.maximum(np.bincount(b, None, 21), 1)
            x = np.arange(21) / 2.0 - 5.0
            lo, hi = prof[:4].mean(), prof[-4:].mean()
            r = (prof - lo) / (hi - lo)
            out.append(np.interp(0.8, r[6:15], x[6:15]) - np.interp(0.2, r[6:15], x[6:15]))
    return float(np.mean(out))


def highpass(a, sigma=1.5):
    k = np.exp(-0.5 * (np.arange(-6, 7) / sigma) ** 2); k /= k.sum()
    p = np.pad(a.astype(np.float64), 6, mode='edge')
    h = sum(k[i] * p[:, i:i + a.shape[1]] for i in range(13))
    v = sum(k[i] * h[i:i + a.shape[0], :] for i in range(13))
    return a - v


def phase_means(d, near):
    ph = ((YY % 4) * 4 + XX % 4)[near]
    pm = np.bincount(ph, d[near], 16) / np.maximum(np.bincount(ph, None, 16), 1)
    return pm - pm.mean()


def main():
    ya, near = scene(GRID)
    y0, _ = scene(0.0)
    before, after, off = sharpen(ya, None), sharpen(ya, 1.0), sharpen(ya, 0.0)
    b0, a0 = sharpen(y0, None), sharpen(y0, 1.0)
    ok = True

    def check(cond, msg):
        nonlocal ok
        print(('PASS ' if cond else 'FAIL ') + msg)
        ok &= bool(cond)

    check(np.array_equal(off.astype(np.float16), before.astype(np.float16)), 'mode 4 at edgeAlongU 0 = mode 2 bit for bit')
    e = (ya - y0)[near].std()
    g2, g4 = (before - b0)[near].std() / e, (after - a0)[near].std() / e
    print('the blocks\' grid at the edges: mode 2 x%.2f, mode 4 x%.2f' % (g2, g4))
    check(g2 >= 1.4, 'the Quad sharpening (mode 2) lifts the grid at the edges (x%.2f >= x1.4)' % g2)
    check(g4 - 1 <= 0.7 * (g2 - 1), 'mode 4 keeps at most 0.7 of mode 2\'s lift (%.2f)' % ((g4 - 1) / (g2 - 1)))
    own = np.sqrt((phase_means(a0 - b0, near) ** 2).mean()) / (HI - LO)
    check(own <= 0.0005, 'no grid of mode 4\'s own along the edges (%.4f %% of the contrast)' % (100 * own))
    ai, ab, aa = acutance(ya), acutance(before), acutance(after)
    print('edge rise 20 - 80 %%: input %.4f px, mode 2 %.4f px, mode 4 %.4f px' % (ai, ab, aa))
    check(abs(aa / ab - 1) <= 0.01 and aa < ai, 'the edges keep their sharpening across (%.4f / %.4f)' % (aa, ab))
    tb, ta, t0 = (highpass(v[136:248, 8:120]).std() for v in (before, after, ya))
    print('texture high-pass: input %.5f, mode 2 %.5f, mode 4 %.5f' % (t0, tb, ta))
    check(abs(ta / tb - 1) <= 0.01 and tb > 1.2 * t0, 'the texture keeps its sharpening (%.4f)' % (ta / tb))
    fb, fa = (v[136:248, 136:248] for v in (before, after))
    check(np.sqrt(np.mean((fa - fb) ** 2)) <= 0.01 * fb.std(), 'the flat patch as mode 2 (within 1 % of its noise)')
    print('PASS' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
