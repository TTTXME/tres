#!/usr/bin/env python3
"""P614 «Why тон» V2: the shipped V2 chain (why/v2_*.glsl driven as WhyV2Stats / WhyV2Fusion / WhyV2Tone, the Java WhyV2Ae through
javac) against an independent numpy model of the reference tone (v2.07: histogram_cs, ef_pyr_prep / pyr_downsample /
ef_pyr_coarsest / ef_pyr_collapse, tonemap_final) on the GPU (moderngl, llvmpipe). Checks:
- the histograms the Java counts from the strided samples are numpy's binning of the clean RGB at the reference's stride;
- level sizes, exposures and layer weights as the native code makes them;
- flat tiles (no edges: the pyramid's bands and the 3x3 regression vanish) render as the numpy model of the reference's per-pixel
  maths - the fused luma, the ACES inversion, OKLab macro contrast and vibrance, the AgX / own-curve tonescale with the inset /
  outset, the hue lock, the display gamma, the dither - for the own curve (the default) and AgX, with and without HDR headroom;
- a step edge: the pyramid's collapse equals the per-pixel fusion far from it and stays within the lumas' range near it;
- P3_OUT writes the same colour in P3 primaries; QUANT8 stores the RGBA8 codes; why/sharp_in REF_LUMA is the reference's
  luma_extract; EF_SCALED (a guard: never on V2's grids) is within 1/255 of the full grid on a smooth image;
- the eight rows shared with V1 widened to the reference's ranges (P614): XML / SettingsNumericRules there, flat tiles at the ends
  as the numpy model;
- the shaders compile for GLSL ES 3.10 (glslc, when found) in every define set.
Usage: check_why_v2_tone.py
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import why_v2_host as H  # noqa: E402
from check_colour_output import expand_imports, set_define, find_glslc  # noqa: E402

FAIL = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


# ---------- the numpy model of the reference tone (per pixel, flat surroundings) ----------
def gamma_enc(x, gi):
    return np.power(np.maximum(x, 1e-6), gi)


def gamma_dec(y, g):
    return np.power(np.maximum(y, 0.0), g)


def acescg(v, toe, pre, a, d):
    x = np.maximum(v, 0.0) * pre
    b = max(toe * 0.75, 0.005)
    e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
    return (x * (a * x + b)) / (x * (2.43 * x + d) + e)


def inv_acescg(y, toe, pre, a, d):
    b = max(toe * 0.75, 0.005)
    e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
    y = np.clip(y, 0.0, a / 2.43 - 0.01)
    A = 2.43 * y - a
    B = d * y - b
    C = e * y
    disc = B * B - 4 * A * C
    return np.where(disc < 0, 0.0, np.maximum((-B - np.sqrt(np.maximum(disc, 0))) / (2 * A - 1e-6) / max(pre, 1e-3), 0.0))


def meter(rgb):
    return np.maximum(rgb @ H.LUMA, rgb.max(-1) * 0.5)


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])
M2I = np.array([[1.0, 0.3963377774, 0.2158037573], [1.0, -0.1055613458, -0.0638541728], [1.0, -0.0894841775, -1.2914855480]])
M1I = np.array([[4.0767416621, -3.3077115913, 0.2309699292], [-1.2684380046, 2.6097574011, -0.3413193965],
                [-0.0041960863, -0.7034186147, 1.7076147010]])


def to_lab(rgb):
    return np.cbrt(np.maximum(rgb @ M1.T, 0.0)) @ M2.T


def from_lab(lab, lo=0.0, hi=None):
    lms = np.maximum(lab @ M2I.T, 0.0) ** 3
    out = lms @ M1I.T
    return np.clip(out, lo, hi) if hi is not None else np.maximum(out, lo)


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def agx_tonescale(x, slope, sp, tp, px):
    py = 0.5
    s_base = max(slope * (1 - px) / (1 - py), 1e-5)
    s_den = max(slope * (1 - px), 1e-5) ** -sp
    ss = max((s_base ** sp - 1) * s_den, 1e-5) ** (-1 / sp)
    t_base = max(slope * px / py, 1e-5)
    t_den = max(slope * px, 1e-5) ** -tp
    ts = max((t_base ** tp - 1) * t_den, 1e-5) ** (-1 / tp)
    ms = slope * (x - px) / max(ss, 1e-5)
    hi = ss * (ms / (1 + np.maximum(ms, 0) ** sp) ** (1 / sp)) + py
    mr = slope * (px - x) / max(ts, 1e-5)
    lo = py - ts * (mr / (1 + np.maximum(mr, 0) ** tp) ** (1 / tp))
    return np.where(x >= px, hi, lo)


def curve_at(c, x):
    n = c.size
    p = np.clip(x, 0, 1) * n - 0.5
    f = np.floor(p)
    i0 = np.clip(f, 0, n - 1).astype(int)
    i1 = np.clip(f + 1, 0, n - 1).astype(int)
    return c[i0] + (c[i1] - c[i0]) * (p - f)


def model(rgb, r, op, xy):
    """The reference's per-pixel tone of a flat area (u_tex_accum_ef = the per-pixel fusion, luma delta 0)."""
    P = lambda k: r['P.' + k]  # noqa: E731
    ae, clip = r['ae'], r['clip']
    gi, g = 1.0 / max(P('gamma'), 1.0), P('gamma')
    toe = P('acesToe')
    em = np.array([r['em%d' % i] for i in range(4)])
    lw = np.array([r['lw%d' % i] for i in range(4)])
    x = rgb * ae
    lumas = gamma_enc(acescg(meter(x[:, None, :] * em[None, :, None]), toe, 1.5, 2.51, 0.59), gi)
    wv = np.exp(-0.5 * (lumas - P('weightCenter')) ** 2 / max(P('blendSmoothness') ** 2, 0.001)) * lw + 1e-6
    wv /= wv.sum(-1, keepdims=True)
    fused = (lumas * wv).sum(-1)
    centre = gamma_enc(acescg(meter(x), toe, 1.5, 2.51, 0.59), gi)
    target = np.maximum(fused, 0.0)
    dark = centre < 0.007
    target = np.where(dark, centre + (target - centre) * (centre / 0.007) ** 2, target)
    tl = inv_acescg(gamma_dec(target, g), toe, 1.5, 2.51, 0.59)
    if clip > 1.05:
        mx = 0.18 * 2 ** (P('agxMaxEv') + max(P('agxEv'), 0)) if op == 0 else (3.0 if op == 1 else 4.0)
        top = max(mx, 1.0)
        knee = top * 0.7
        ex = tl - knee
        tl = np.where(tl > knee, knee + ex * (top - knee) / (ex + (top - knee)), tl)
    ol = np.maximum(np.maximum(rgb @ H.LUMA, rgb.max(-1) * 0.5), 1e-4)
    eff = np.full(ol.shape, ae)
    if clip > 1.05 and ae > 1.0:
        eff = ae + (1.0 - ae) * smooth(0.70, 1.20, ol)
    mult = np.clip(tl / np.maximum(ol * eff, 1e-4), 0.05, 12.0)
    lin = rgb * (eff * mult)[:, None]
    lab = to_lab(lin)
    L, a, b = lab[:, 0], lab[:, 1], lab[:, 2]
    if abs(r['macro'] - 1) > 0.001:
        L = np.maximum(0.4 + (L - 0.4) * r['macro'], 1e-4)
    sat = np.maximum(np.hypot(a, b), 1e-4)
    th = np.arctan2(b, a) - np.radians(np.float32(P('skinHueCenterDeg')))
    skin = np.clip(np.exp(-0.5 * th * th / 0.28 ** 2) * smooth(0.015, 0.040, sat) * (1 - smooth(0.18, 0.28, sat))
                   * smooth(P('skinLumaMin') - 0.06, P('skinLumaMin') + 0.06, L) * (1 - smooth(0.88, 0.98, L)), 0, 1)
    boost = P('vibStrength') + (P('vibSaturated') - P('vibStrength')) * smooth(0.02, 0.09, sat)
    boost = boost + np.where(b < 0, P('vibSky') * np.clip(-b / sat, 0, 1), 0) + np.where(a < 0, P('vibGreen') * np.clip(-a / sat, 0, 1), 0)
    boost = boost + P('vibHl') * smooth(0.60, 0.80, L) * (1 - smooth(0.88, 0.98, L))
    boost = boost + P('vibMid') * smooth(0.15, 0.35, L) * (1 - smooth(0.55, 0.75, L)) + P('vibShadow') * (1 - smooth(0.08, 0.25, L))
    sm = np.maximum(0, 1 + boost * (1 + (1 - 0.7 * skin - 1) * P('skinProtect')))
    a, b = a * sm, b * sm
    ic = np.hypot(a, b)
    hd = np.stack([np.where(ic > 1e-6, a / np.maximum(ic, 1e-12), 0), np.where(ic > 1e-6, b / np.maximum(ic, 1e-12), 0)], -1)
    scene = from_lab(np.stack([L, a, b], -1))
    if clip > 1.05 and P('shoulderStrength') > 0.001:
        peak = scene.max(-1)
        topl = 0.18 * 2 ** (P('agxMaxEv') + max(P('agxEv'), 0)) if op == 0 else 4.0
        k = topl * P('shoulderKnee')
        ex = peak - k
        room = topl * P('shoulderHeadroom')
        comp = k + ex * room / (ex + room)
        sc = np.where(peak > k, 1 + (comp / np.maximum(peak, 1e-12) - 1) * P('shoulderStrength'), 1.0)
        scene = scene * sc[:, None]
    inset = np.array([r['in%d' % i] for i in range(9)]).reshape(3, 3).T
    outset = np.array([r['out%d' % i] for i in range(9)]).reshape(3, 3).T
    ins = np.maximum(scene @ inset.T, 0)
    if op == 0:
        rng = max(P('agxMaxEv') + max(P('agxEv'), 0) - P('agxMinEv'), 1.0)
        px = np.clip(-P('agxMinEv') / rng, 0.05, 0.95)
        lg = np.clip((np.log2(np.maximum(ins, 1e-7) / 0.18) + P('agxEv') - P('agxMinEv')) / rng, 0, 1)
        cur = agx_tonescale(lg, r['slope'], P('agxSp'), P('agxTp'), px)
        out = np.maximum(cur, 0) ** 2.4 @ outset.T
        if abs(P('agxSat') - 1) > 0.001:
            ol_ = out @ H.LUMA
            out = ol_[:, None] + (out - ol_[:, None]) * P('agxSat')
        graded = np.clip(out, 0, 1)
    else:
        nx = np.clip(0.5 + np.log2(np.maximum(ins, 1e-7) / 0.18) * 0.1, 0, 1)
        cur = curve_at(r['curve'], nx)
        if abs(r['cb']) > 1e-4:
            cc = np.array([0.0, 0.349, 0.659, 0.889, 1.0, 0.964, 0.769, 0.431, 0.0, -0.431, -0.769, -0.964, -1.0, -0.889, -0.659, -0.349, 0.0])
            idx = np.clip(nx * 16, 0, 16)
            i0 = np.clip(np.floor(idx), 0, 15).astype(int)
            t = idx - i0
            s = t * t * (3 - 2 * t)
            cur = np.clip(cur - (cc[i0] + (cc[i0 + 1] - cc[i0]) * s) * (r['cb'] * 0.125), 0, 1)
        graded = np.clip(np.maximum(cur, 0) ** 2.4 @ outset.T, 0, 1)
    post = to_lab(graded)
    if P('agxHueLock') != 0:
        bw = np.clip(-hd[:, 1], 0, 1)
        ch = np.hypot(post[:, 1], post[:, 2])
        lock = ic > 1e-6
        post[:, 1] = np.where(lock, post[:, 1] + (ch * hd[:, 0] - post[:, 1]) * bw, post[:, 1])
        post[:, 2] = np.where(lock, post[:, 2] + (ch * hd[:, 1] - post[:, 2]) * bw, post[:, 2])
    post[:, 0] = np.clip(post[:, 0], 1e-4, 1.0)
    fin = from_lab(post, 0.0, 1.0)
    sdr = gamma_enc(fin, gi)
    d1 = np.mod(0.06711056 * xy[:, 0] + 0.00583715 * xy[:, 1], 1.0)
    dither = (np.mod(52.9829189 * d1, 1.0) - 0.5) / 255
    return np.clip(sdr + dither[:, None], 0, 1), fused


def tiles(colours, scales, tile=96):
    """An image of flat tiles (one per colour x scale) and the centre pixel of each."""
    rows, cols = len(colours), len(scales)
    img = np.zeros((rows * tile, cols * tile, 3), np.float32)
    centres = []
    for i, c in enumerate(colours):
        for j, s in enumerate(scales):
            img[i * tile:(i + 1) * tile, j * tile:(j + 1) * tile] = np.array(c, np.float32) * s
            centres.append((j * tile + tile // 2, i * tile + tile // 2))
    return img, np.array(centres)


def main():
    rng = np.random.default_rng(7)
    # ---- the histograms: the Java's counts of the strided samples = numpy's bins of the clean RGB at the reference's stride
    img = (rng.random((1600, 2100, 3)) ** 2.5 * 1.2).astype(np.float32)
    _, r = H.statistics(img)
    s = H.stride(2100, 1600)
    smp = img.astype(np.float16).astype(np.float32)[::s, ::s]
    yl = (0.2126 * smp[..., 0] + 0.7152 * smp[..., 1] + 0.0722 * smp[..., 2]).astype(np.float32)
    hl = np.maximum(yl, smp.max(-1))
    bins = lambda v: np.clip((np.clip(v, 0, 1) * np.float32(4095.99)).astype(np.int64), 0, 4095)  # noqa: E731
    check(s == 2 and int(r['total']) == smp.shape[0] * smp.shape[1], 'stride 2, %d samples' % r['total'])
    check(np.array_equal(np.bincount(bins(yl).ravel(), minlength=4096), r['hist_luma'])
          and np.array_equal(np.bincount(bins(hl).ravel(), minlength=4096), r['hist_hl']), 'the luma / max histograms as histogram_cs bins them')
    check(H.levels(4096, 3072)[-1] == (64, 48) and len(H.levels(4096, 3072)) == 7 and H.levels(4000, 3000)[-1] == (62, 46),
          'pyramid levels (to the side 48)')
    em = [r['em%d' % i] for i in range(4)]
    check(abs(em[0] - 2 ** (-2.5 * r['effH'])) < 1e-6 and abs(em[1] - 2 ** (-1.2 * r['effH'])) < 1e-6 and em[2] == 1.0
          and abs(em[3] - min(2 ** (1.8 * r['effS']), 16)) < 1e-4, 'exposures (2^-2.5H, 2^-1.2H, 1, min(2^1.8S, 16))')
    check([r['lw%d' % i] for i in range(4)] == [0.7, 1.0, 2.0, 1.0] and r['P.toneOperator'] == 3 and abs(r['P.weightCenter'] - 1) < 1e-7
          and abs(r['P.blendSmoothness'] - 0.45) < 1e-6, 'the reference configuration: layers 0.7/1/2/1, centre 1, smoothness 0.45, own curve')

    # ---- flat images against the numpy model, own curve and AgX, without and with HDR headroom (the shot's statistics of a
    # mixed frame; every colour x level a uniform 64 x 64 image: the bands and the regression vanish, the pyramid is exact)
    colours = [(1, 1, 1), (0.62, 0.45, 0.36), (0.45, 0.60, 0.90), (0.3, 0.5, 0.12), (1.0, 0.6, 0.13), (0.9, 0.15, 0.12)]
    for hdr in (False, True):
        scales = 0.18 * 2.0 ** np.arange(-5.0, 2.6 if hdr else 1.6, 1.0)
        frame = (rng.random((512, 683, 3)) ** 3 * (6.0 if hdr else 1.0)).astype(np.float32)
        _, base = H.statistics(frame, bento=hdr)
        for op in (3, 0):
            worst = worst_f = 0.0
            for c in colours:
                for sc in scales:
                    tile = np.empty((64, 64, 3), np.float32)
                    tile[:] = np.array(c, np.float32) * sc
                    keep = {}
                    out, r = H.render(tile, keep=keep, fixed=base, uniforms={'u_tonemap_operator': op})
                    xy = np.array([[33.0, 21.0], [5.0, 60.0]])
                    px = tile[xy[:, 1].astype(int), xy[:, 0].astype(int)].astype(np.float16).astype(np.float64)
                    ref, fused = model(px, r, op, xy)
                    worst_f = max(worst_f, float(np.abs(keep['fused'][xy[:, 1].astype(int), xy[:, 0].astype(int)] - fused).max()))
                    worst = max(worst, float(np.abs(out[xy[:, 1].astype(int), xy[:, 0].astype(int)] - ref).max()))
            tag = ('HDR clip %.2f' % base['clip']) if hdr else 'SDR'
            check(worst_f < 2e-3, 'flat (%s, op %d): fused luma as the per-pixel fusion (%.1e)' % (tag, op, worst_f))
            check(worst < 2.5e-3, 'flat (%s, op %d): the tone as the reference model (max %.1e, ae %.3f)' % (tag, op, worst, base['ae']))
        if hdr:
            check(base['clip'] > 1.05, 'HDR: the clip ceiling above 1.05 with Bento headroom (%.2f)' % base['clip'])

    # ---- P614 (owner, 2026-11-11): eight rows V2 shares with V1 widened to the reference's ranges (AE highlight protection threshold
    # to 10, highlight / shadow strength to 5, macro contrast 0.5-2, gamma 1-3, AgX min EV -15..-2, AgX EV +-3, ACES toe to 0.3): the
    # rows' XML ranges and SettingsNumericRules as those, and the flat tiles at the ends as the reference model, with HDR headroom
    xml = (H.ROOT / 'app/src/main/res/xml/preferences.xml').read_text(encoding='utf-8')
    rules = (H.JAVA.parent / 'settings/SettingsNumericRules.java').read_text(encoding='utf-8')
    wide = {'hl_overflow': (0.5, 10), 'highlight_str': (0, 5), 'shadow_str': (0, 5), 'macro_contrast': (0.5, 2), 'gamma': (1, 3),
            'agx_min_ev': (-15, -2), 'agx_ev': (-3, 3), 'aces_toe': (0.005, 0.3)}
    import re as _re
    off = []
    for k, (lo, hi) in wide.items():
        m = _re.search(r'android:key="pref_scam_hybrid_why_%s"[^>]*?app:minValue="([^"]*)" app:maxValue="([^"]*)"' % k, xml)
        b = _re.search(r'"pref_scam_hybrid_why_%s":(?: case "[a-z_]+":)* return new double\[\]\{([^,]+),([^,]+),0\}' % k, rules)
        if not m or (float(m.group(1)), float(m.group(2))) != (lo, hi) or not b or (float(b.group(1)), float(b.group(2))) != (lo, hi):
            off.append(k)
    check(not off, 'widened rows: XML and SettingsNumericRules at the reference\'s ranges %s' % off)
    ends = [{'gamma': 1.0}, {'gamma': 3.0}, {'acesToe': 0.005}, {'acesToe': 0.3}, {'macroContrast': 0.5}, {'macroContrast': 2.0},
            {'agxMinEv': -15.0, 'agxEv': -3.0}, {'agxMinEv': -2.0, 'agxEv': 3.0}, {'agxMinEv': -15.0, 'agxEv': 3.0},
            {'highlightStr': 5.0, 'shadowStr': 5.0, 'hlOverflow': 10.0}, {'highlightStr': 0.0, 'shadowStr': 0.0, 'hlOverflow': 0.5},
            {'gamma': 3.0, 'acesToe': 0.3, 'macroContrast': 0.5, 'agxMinEv': -2.0, 'agxEv': -3.0, 'highlightStr': 5.0, 'shadowStr': 5.0},
            {'gamma': 1.0, 'acesToe': 0.005, 'macroContrast': 2.0, 'agxMinEv': -15.0, 'agxEv': 3.0, 'hlOverflow': 10.0}]
    scales = 0.18 * 2.0 ** np.arange(-5.0, 2.6, 1.5)
    frame = (rng.random((512, 683, 3)) ** 3 * 6.0).astype(np.float32)
    worst = worst_f = 0.0
    finite = True
    for e in ends:
        _, base = H.statistics(frame, bento=True, params=e)
        for op in (3, 0):
            for c in colours[:4]:
                for sc in scales:
                    tile = np.empty((64, 64, 3), np.float32)
                    tile[:] = np.array(c, np.float32) * sc
                    keep = {}
                    out, r = H.render(tile, keep=keep, fixed=base, params=e, uniforms={'u_tonemap_operator': op})
                    xy = np.array([[33.0, 21.0], [5.0, 60.0]])
                    px = tile[xy[:, 1].astype(int), xy[:, 0].astype(int)].astype(np.float16).astype(np.float64)
                    ref, fused = model(px, r, op, xy)
                    finite = finite and bool(np.isfinite(out).all())
                    worst_f = max(worst_f, float(np.abs(keep['fused'][xy[:, 1].astype(int), xy[:, 0].astype(int)] - fused).max()))
                    worst = max(worst, float(np.abs(out[xy[:, 1].astype(int), xy[:, 0].astype(int)] - ref).max()))
    # under one 8-bit code: the fused luma is R16F as the reference's, and the ACES inversion at toe 0.3 / the linear display at gamma
    # 1 are steeper than at the defaults (3.4e-3 at most there; the 1:1 run against the reference's own shaders: <= 1 code)
    check(finite and worst_f < 2e-3 and worst < 1 / 255, 'widened rows at their ends (%d sets, own curve and AgX, HDR): the fused luma '
          '(%.1e) and the tone (%.1e) as the reference model' % (len(ends), worst_f, worst))

    # ---- a step edge: far from it the per-pixel fusion, near it within the lumas' range (no runaway bands)
    img = np.full((256, 512, 3), 0.02, np.float32)
    img[:, 256:] = 1.5
    keep = {}
    out, r = H.render(img, keep=keep)
    f = keep['fused']
    check(abs(f[128, 20] - f[128, 5]) < 2e-3 and abs(f[128, 500] - f[128, 490]) < 2e-3, 'step edge: flat far from the edge')
    check(np.isfinite(out).all() and out.min() >= 0 and out.max() <= 1, 'step edge: finite output in 0..1')
    mono = np.diff(out[128, :, 1])
    check((mono[200:312] > -0.25).all(), 'step edge: no deep dark band at the bright side (%.3f)' % mono[200:312].min())

    # ---- P3_OUT: the same colour in P3 primaries
    img, cen = tiles(colours, 0.18 * 2.0 ** np.arange(-2.0, 1.1, 1.0), 64)
    srgb, _ = H.render(img)
    p3, _ = H.render(img, p3=True)
    S2P = np.array([[0.8224619687, 0.1775380313, 0.0], [0.0331941989, 0.9668058011, 0.0], [0.0170826307, 0.0723974407, 0.9105199286]])
    a = srgb[cen[:, 1], cen[:, 0]].astype(np.float64)
    b = p3[cen[:, 1], cen[:, 0]].astype(np.float64)
    lin_a = np.clip(a, 0, 1) ** 2.2 @ S2P.T
    check(np.abs(np.clip(lin_a, 0, 1) ** (1 / 2.2) - b).max() < 4e-3, 'P3_OUT: the sRGB colour written in P3 primaries')

    # ---- QUANT8 (an 8-bit photo): the values the reference's RGBA8 target stores, the same render rounded to codes
    q8, _ = H.render(img, quant8=True)
    check(np.array_equal(q8 * 255.0, np.round(q8 * 255.0)) and np.abs(q8 - np.floor(srgb * 255.0 + 0.5) / 255.0).max() < 1e-6,
          'QUANT8: round(x 255) / 255 of the float output')

    # ---- why/sharp_in REF_LUMA (V2's sharpening guide): the reference's luma_extract, soft knee 0.001 below it, unclamped above 1
    vals = np.array([-0.01, -0.0005, 0.0, 0.0004, 0.001, 0.2, 1.0, 3.7], np.float32)
    grey = np.repeat(np.repeat(vals[None, :, None], 3, -1), 4, 0)
    g = H.gpu()
    ct = g.tex(np.concatenate([grey, np.ones(grey.shape[:2] + (1,), np.float32)], -1), 'f2')
    ya = g.read(g.draw(g.prog('sharp_in', {'REF_LUMA': 1}), g.target((len(vals), 4), 1), {'CleanBuffer': ct}, {}), 1)[0, :, 0]
    y = vals.astype(np.float16).astype(np.float64) * (0.2126 + 0.7152 + 0.0722)
    eps = 0.001
    want = np.where(y >= eps, y, np.where(y > -eps, (y + eps) ** 2 / (4 * eps), 0.0))
    check(np.abs(ya - want).max() <= 1e-3 * np.maximum(want, 1e-3).max() and ya[-1] > 3.0,
          'sharp_in REF_LUMA: luma_extract of the clean RGB (soft knee, unclamped: %s)' % np.round(ya, 5).tolist())
    ct.release()

    # ---- EF_SCALED: the fused luma at half the grid within 1/255 of the full one on a smooth image
    yy, xx = np.mgrid[0:1200, 0:1600].astype(np.float32)
    img = (0.18 * 2.0 ** (4 * np.sin(xx / 300) * np.cos(yy / 260) - 1))[..., None] * np.array([1, 0.9, 0.8], np.float32)
    full, _ = H.render(img.astype(np.float32))
    H.FULL_LIMIT = 1_000_000
    half, _ = H.render(img.astype(np.float32))
    H.FULL_LIMIT = 100_000_000
    d = np.abs(full - half)[100:-100, 100:-100]
    check(np.percentile(d, 99) < 1.5 / 255, 'EF_SCALED: half-grid fusion within 1.5/255 at p99 (%.4f)' % np.percentile(d, 99))

    # ---- «Настройки изображения» -> WhyV2Ae.Params -> the passes' uniforms (P614: V2 reads V1's row where the setting is the same,
    # WhyV2Stats.MERGED, its own five pref_scam_hybrid_why_v2_* rows, the operator from V1's switches): every row read exists, its
    # XML default is the Params default (the owner's configuration / the reference's native default, as float32) - or V1's decimal
    # of it within two ulps, which owner() turns into the owner's float -, an override reaches the field
    import re as _re
    xml = (H.ROOT / 'app/src/main/res/xml/preferences.xml').read_text(encoding='utf-8')
    jsrc = (H.JAVA / 'opengl/postpipeline/WhyV2Stats.java').read_text(encoding='utf-8')
    body = jsrc[jsrc.index('static WhyV2Ae.Params settings()'):jsrc.index('static final String[][] MERGED')]
    reads = _re.findall(r'p\.(\w+(?:\[\d\])?) = (owner\()?[^;]*?"(?:why_)?([a-z0-9_]+)"', body)
    base = H.java_ae(np.full((4, 4, 3), 0.2, np.float32), 1)
    bad = []
    tc = (H.JAVA / 'color/ToneCurve.java').read_text(encoding='utf-8')
    ae = (H.JAVA / 'opengl/postpipeline/WhyV2Ae.java').read_text(encoding='utf-8')
    pts = lambda src, name: ''.join(_re.findall(r'"([^"]*)"', src[src.index(name + ' ='):].split(';')[0]))
    for f, snapped, k in reads:
        if 'android:key="pref_scam_hybrid_why_%s"' % k not in xml:
            bad.append(k + ': no row')
            continue
        if f == 'curvePoints':   # one shared curve: V1's default points (ToneCurve.SETTING_POINTS) are the owner's (OWNER_POINTS)
            if pts(tc, 'SETTING_POINTS') != pts(ae, 'OWNER_POINTS'):
                bad.append('curve_points: V1 default points are not the owner\'s')
            continue
        m = _re.search(r'android:key="pref_scam_hybrid_why_%s"[^>]*?android:defaultValue="([^"]*)"' % k, xml)
        d = m.group(1) if m else None
        if d is None:
            bad.append(k + ': no default')
            continue
        if f.startswith('curveMods') or f == 'agxHueLock':
            continue
        want = np.float32(float(d))
        got = base.get('P.' + f)
        if got is None:
            bad.append(k + ': no field ' + f)
            continue
        got = np.float32(got)
        if got != want and not (snapped and abs(float(got) - float(want)) <= 2 * float(np.spacing(got))):
            bad.append('%s: XML %s, Params %s%s' % (k, d, got, '' if snapped else ' (not owner())'))
    merged = _re.findall(r'\{"([a-z0-9_]+)", "([a-z0-9_]+)"\}', jsrc[jsrc.index('static final String[][] MERGED'):])
    own = _re.findall(r'"([a-z0-9_]+)"', jsrc[jsrc.index('static final String[] OWN'):].split(';')[0])
    keys = {k for _, _, k in reads}
    bad += ['MERGED %s -> %s not read' % m for m in merged if m[1] not in keys]
    bad += ['OWN %s not read' % o for o in own if 'v2_' + o not in keys]
    v2rows = _re.findall(r'android:key="pref_scam_hybrid_why_v2_([a-z0-9_]+)"', xml)
    check(len(reads) == 71 and len(merged) == 66 and sorted(own) == sorted(v2rows) and 'p.toneOperator = toneOperator();' in body
          and 'scam_hybrid_why_v2_screen' not in xml and not bad,
          'V2 settings: %d read (%d merged into V1 rows, %d own, the operator from V1\'s switches), XML defaults = Params defaults %s'
          % (len(reads), len(merged), len(own), bad))
    over = {'aeTarget': 0.31, 'toneOperator': 1, 'hrAmount': 0.6, 'bracketDenoise': 1.2, 'agxSlope': 3.3, 'skinHueCenterDeg': 50.5,
            'gammaType': 2, 'vibMid': -0.4}
    o = H.java_ae(np.full((4, 4, 3), 0.2, np.float32), 1, params=over)
    check(all(abs(o['P.' + k] - v) < 1e-6 for k, v in over.items()) and abs(o['P.skinHueCenterRad'] - np.float32(50.5) * np.float32(0.017453292)) < 1e-7,
          'V2 settings: an override reaches its Params field (and the hue centre its radians)')
    # the bracket / highlight reconstruction passes: no change at 0, a change near white when on
    hi = np.full((64, 64, 3), 0.5, np.float32)
    hi[20:44, 20:44] = (1.4, 0.99, 0.7)
    hi[30:34, 30:34] = (1.6, 1.2, 0.9)
    base_img, _ = H.render(hi, bento=True)
    on_img, ron = H.render(hi, bento=True, params={'bracketDenoise': 1.0, 'hrAmount': 1.0})
    check(np.isfinite(on_img).all() and np.abs(on_img - base_img).max() > 1e-3 and np.abs(on_img - base_img)[:, :8].max() < 1e-6,
          'WhyV2Highlights: bracket_denoise / hr_apply act near white only (%.4f)' % np.abs(on_img - base_img).max())

    # ---- the Java constants the host mirrors
    src = (H.JAVA / 'opengl/postpipeline/WhyV2Fusion.java').read_text(encoding='utf-8')
    check('FULL_LIMIT = PostPipeline.V2_MAX_PIXELS;' in src and 'V2_MAX_PIXELS = 100_000_000L;' in
          (H.JAVA / 'opengl/postpipeline/PostPipeline.java').read_text(encoding='utf-8'), 'WhyV2Fusion.FULL_LIMIT as mirrored here')
    src = (H.JAVA / 'opengl/postpipeline/WhyV2Stats.java').read_text(encoding='utf-8')
    check('MAX_CEILING = 8f' in src, 'WhyV2Stats.MAX_CEILING as mirrored here')

    # ---- glslc: every define set compiles for GLSL ES 3.10
    glslc = find_glslc()
    if glslc:
        sets = {'v2_clean': [{}], 'v2_stride': [{}], 'v2_ef_prep': [{}, {'MODE': '1'}, {'SCALE': '2'}, {'SCALE': '2', 'MODE': '1'}],
                'v2_pyr_down': [{}], 'v2_ef_coarsest': [{}], 'v2_ef_collapse': [{}],
                'v2_tonemap': [{}, {'P3_OUT': '1'}, {'FILM_LUT': '1'}, {'EF_SCALED': '1'}, {'QUANT8': '1'},
                               {'P3_OUT': '1', 'FILM_LUT': '1', 'EF_SCALED': '1', 'QUANT8': '1'}],
                'sharp_in': [{}, {'REF_LUMA': '1'}], 'v2_bracket_dn': [{}], 'v2_hr_extract': [{}], 'v2_box': [{}, {'DIR': '1'}],
                'v2_hr_apply': [{}]}
        n = 0
        for name, cases in sets.items():
            for defines in cases:
                src = (H.SH / ('why/%s.glsl' % name)).read_text(encoding='utf-8')
                for k, v in defines.items():
                    src = set_define(src, k, v)
                with tempfile.TemporaryDirectory() as d:
                    f = Path(d) / 'x.frag'
                    f.write_text('#version 310 es\nprecision highp int;\n' + expand_imports(src), encoding='utf-8')
                    res = subprocess.run([glslc, '--target-env=opengl', '-fshader-stage=fragment', '-std=310es', '-fauto-map-locations',
                                          '-fauto-bind-uniforms', '-o', os.devnull, str(f)], capture_output=True, text=True)
                    check(res.returncode == 0, 'glslc %s %s%s' % (name, defines, ('\n' + res.stderr) if res.returncode else ''))
                    n += 1
        print('glslc: %d V2 programs compiled' % n)
    else:
        print('glslc not found: compile skipped')
    print('why V2 tone check %s' % ('FAIL (%d)' % len(FAIL) if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
