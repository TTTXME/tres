#!/usr/bin/env python3
"""P402 (owner, 2026-10-29: «Перенеси то чего нам не хватает», the reference app's settings): why/combine.glsl's AGX_INSET
(«Плёночное выгорание светов»), hrEaseU («Сила удержания цвета в светах») and WHY_BASE (the reference fusion layout's base of the
tone) on the GPU (moderngl), the shipped shader as check_light_chroma's Renderer draws it. Checks:
- AGX_INSET with the full strength (insetU 1) is the shader without it (within 2e-6: the inverse is computed); at 0.5 a neutral
  grey keeps its colour (the inset's rows sum to 1, within 1e-3) and a saturated light keeps more chroma than at full strength;
- hrEaseU 0 bit-identical to unset; at 1 (strength 0) the light above the shoulder's knee is brighter than with the shoulder, at
  0.4 in between; below the knee bit-identical;
- WHY_BASE without headroom (clip 1) and an exposure in range bit-identical; with headroom (clip 4) the fused target above 0.7 of
  the tonemapper top is pulled under it (never brighter than without), an exposure sweep stays monotonic and without steps.
Usage: check_why_v207.py
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_light_chroma as clc  # noqa: E402

FAIL = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def chroma(disp):
    lin = np.where(disp <= 0.04045, disp / 12.92, ((disp + 0.055) / 1.055) ** 2.4)
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    lab = np.cbrt(np.maximum(lin @ m1.T, 0)) @ m2.T
    return np.hypot(lab[..., 1], lab[..., 2])


def kernel_acescg(v, toe):
    """import why_fusion acescg (pre-gain 1.5, a 2.51, d 0.59, the toe's b and e)."""
    x = max(v, 0.0) * 1.5
    b, e = max(toe * 0.75, 0.005), max(0.14 - (toe - 0.04) * 0.8, 0.03)
    return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e)


def widened(r, cols, rows):
    """P614 (owner, 2026-11-11): the rows V1 shares with V2 widened to the reference's ranges - gamma 1-3, ACES toe to 0.3, macro
    contrast 0.5-2, AgX min EV -15..-2, AgX EV +-3: V1's tone at every combination of the ends (the fused luma of a flat area as the
    fusion makes it with that gamma / toe) finite and in 0..1, the exposure sweeps of grey, skin, sky, yellow and green never falling.
    The saturated red light dips a little where the macro contrast lifts its dark OKLab L out of the gamut (0.008 at the former ends,
    0.03 at the new; the reference's V2 0.05 at macro 0.8): no V1 clamp for it."""
    import itertools
    fine = 0.18 * 2.0 ** np.arange(-9.0, 7.01, 0.05)
    modes = {'AgX': ({}, {}), 'AgX macro toe': ({'MACRO_TOE': '1'}, {}),
             'AgX base + headroom': ({'WHY_BASE': '1', 'HEADROOM_WIDE': '1', 'MACRO_TOE': '1'}, {'hrU': 20.0, 'hrKneeU': 0.5, 'hrRoomU': 0.4}),
             'ACES': ({'TONE_OP': '1', 'MACRO_TOE': '1'}, {'toneOpU': 1}), 'Uchimura': ({'TONE_OP': '1', 'MACRO_TOE': '1'}, {'toneOpU': 2})}
    keep = [i for i, k in enumerate(cols) if k != 'red LED']
    old = clc.cwh.fused_for
    try:
        for name, (defines, extra) in modes.items():
            agx = name.startswith('AgX')
            bad, worst, n = [], 0.0, 0
            for gamma, toe, macro, minev, ev in itertools.product((1.0, 3.0), (0.005, 0.3), (0.5, 2.0), (-15.0, -2.0) if agx else (-8.5,),
                                                                  (-3.0, 3.0) if agx else (0.0,)):
                clc.cwh.fused_for = lambda m, g=gamma, t=toe: float(kernel_acescg(m, t) ** (1.0 / g))
                out = r.render(rows, fine, defines, dict(extra, gammaInvU=1.0 / gamma, toeU=toe, macroU=macro, agxBU=(minev, 3.5, ev, 4.0),
                                                         acesU=(1.0, 2.61, 0.59, toe)))
                d = np.diff(out[keep] @ clc.LUMA, axis=1).min()
                worst = min(worst, float(d))
                n += 1
                if not (np.isfinite(out).all() and out.min() >= 0 and out.max() <= 1 and d >= -5e-4):
                    bad.append((gamma, toe, macro, minev, ev))
            check(not bad, '%s at the widened ends (%d sets): finite, 0..1, sweeps never falling (min step %.1e) %s' % (name, n, worst, bad[:4]))
    finally:
        clc.cwh.fused_for = old


def main():
    r = clc.Renderer()
    scales = 0.18 * 2.0 ** np.arange(-3.0, 4.01, 0.5)
    cols = {'grey': (1, 1, 1), 'skin': (0.62, 0.45, 0.36), 'sky': (0.45, 0.60, 0.90), 'red LED': (1.5, 0.12, 0.10),
            'yellow': (1.0, 0.6, 0.13), 'green': (0.3, 0.5, 0.12)}
    rows = [clc.norm(v) for v in cols.values()]
    base = r.render(rows, scales, {})
    full = r.render(rows, scales, {'AGX_INSET': '1'}, {'insetU': 1.0})
    check(np.abs(full - base).max() <= 2e-6, 'AGX_INSET at full strength is the shader without it (%.1e)' % np.abs(full - base).max())
    for op in ('0', '1'):
        b = r.render(rows, scales, {'TONE_OP': op} if op == '1' else {}, {'toneOpU': 3})
        f = r.render(rows, scales, dict({'AGX_INSET': '1'}, **({'TONE_OP': op} if op == '1' else {})), {'insetU': 1.0, 'toneOpU': 3})
        check(np.abs(f - b).max() <= 2e-6, 'TONE_OP %s: the full strength as without (%.1e)' % (op, np.abs(f - b).max()))
    half = r.render(rows, scales, {'AGX_INSET': '1'}, {'insetU': 0.5})
    grey = list(cols).index('grey')
    check(np.abs(half[grey] - base[grey]).max() <= 1e-3, 'a grey keeps its colour at half strength (%.1e)' % np.abs(half[grey] - base[grey]).max())
    led = list(cols).index('red LED')
    lit = scales >= 0.18 * 2
    ch, cb = chroma(half[led][lit]), chroma(base[led][lit])
    print('red LED chroma, full %s, half %s' % (np.round(cb, 3), np.round(ch, 3)))
    check(np.all(ch >= cb - 1e-4) and (ch > cb + 0.005).any(), 'a saturated light keeps more chroma at half strength')

    hr = {'hrU': 20.0, 'hrKneeU': 0.5, 'hrRoomU': 0.4}
    bright = 0.18 * 2.0 ** np.arange(-2.0, 7.01, 0.25)
    wide = {'HEADROOM_WIDE': '1'}
    plain = r.render(rows, bright, wide, hr)
    zero = r.render(rows, bright, wide, dict(hr, hrEaseU=0.0))
    check(np.abs(zero - plain).max() == 0.0, 'hrEaseU 0 bit-identical to unset')
    off = r.render(rows, bright, wide, dict(hr, hrEaseU=1.0))
    mid = r.render(rows, bright, wide, dict(hr, hrEaseU=0.4))
    top = 0.18 * 2.0 ** (3.5 + 0.3)
    below = bright * 1.0 < 0.5 * top * 0.45
    check(np.abs(off[:, below] - plain[:, below]).max() == 0.0, 'below the knee bit-identical')
    y = lambda a: a @ clc.LUMA
    up = bright > 0.5 * top
    check(np.all(y(off[:, up]) >= y(plain[:, up]) - 1e-6) and (y(off[:, up]) > y(plain[:, up]) + 1e-3).any(),
          'without the shoulder (strength 0) the light above the knee is brighter')
    check(np.all(y(mid[:, up]) <= y(off[:, up]) + 1e-6) and np.all(y(mid[:, up]) >= y(plain[:, up]) - 1e-6),
          'strength 0.6 lies between')

    arkd = {'WHY_BASE': '1'}
    low = r.render(rows, scales, arkd, {'clipU': 1.0, 'headroomU': 0})
    lowb = r.render(rows, scales, {}, {'clipU': 1.0, 'headroomU': 0})
    check(np.abs(low - lowb).max() == 0.0, 'WHY_BASE without headroom bit-identical (%.1e)' % np.abs(low - lowb).max())
    hb = r.render(rows, bright, wide, hr)
    ha = r.render(rows, bright, dict(wide, **arkd), hr)
    check(np.all(y(ha) <= y(hb) + 1e-5), 'WHY_BASE with headroom never brighter than without')
    fine = 0.18 * 2.0 ** np.arange(-2.0, 7.01, 0.05)
    for i, k in enumerate(cols):
        s = y(r.render([rows[i]], fine, dict(wide, **arkd), hr)[0])
        d = np.diff(s)
        check(np.all(d >= -2e-4), '%s: WHY_BASE exposure sweep monotonic (min step %.1e)' % (k, d.min()))
        sb = y(r.render([rows[i]], fine, wide, hr)[0])
        check(np.abs(d).max() <= np.abs(np.diff(sb)).max() + 0.01, '%s: no step (%.3f against %.3f)' % (k, np.abs(d).max(), np.abs(np.diff(sb)).max()))
    widened(r, cols, rows)
    print('why reference tone check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
