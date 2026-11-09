#!/usr/bin/env python3
"""P577 colour audit: every colour row of why/combine.glsl at its range ends on the GPU (moderngl), as the photo ships it.

    python3 tools/check_colour_params.py [--report]

tools/check_colour_look.py renders the «Цвет» blocks without WHY_VIBRANCE (the P341 / P404 forms of scam_dev "why_vibrance 0");
this bench renders the shipped variant: WHY_VIBRANCE 1 (P419 / P519), LIGHT_CHROMA, HL_NEUTRAL, AGX_INSET 0.5, WHY_BASE,
MACRO_TOE, YELLOW_HUE (vivo) and AgX «Custom» (look 4), with WhyCombine's default uniforms. The chart: an OKLab hue wheel (24 hues)
at three chromas (pastel 0.025, mid 0.06, saturated 0.11) and three lightnesses, skin patches (OKLab hue 40 - 55), a grey ramp,
sky blues and foliage greens. Output measured in display OKLab (gamma 2.2 decoded). Per row, at its minimum / maximum:
- finite and inside [0, 1]; greys stay grey (except the rows that tint on purpose); the hue stays (except the hue rows);
- the effect goes the way its description says, on its zone, and colours off its zone stay put;
- COLOUR_LOOK 1 at the defaults renders COLOUR_LOOK 0 bit for bit with these defines too.
SIGNED_HUE=0/1 in the environment as check_colour_look.py (signedHueU; 1 = the shipped default)."""
import math
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, expand_imports, oklab, set_define  # noqa: E402

SIGNED_HUE = int(os.environ.get('SIGNED_HUE', '1'))
REPORT = '--report' in sys.argv
failures = []


def check(cond, msg):
    print(('ok   ' if cond else 'FAIL ') + msg)
    if not cond:
        failures.append(msg)


M1 = np.array([[1, 0.3963377774, 0.2158037573], [1, -0.1055613458, -0.0638541728], [1, -0.0894841775, -1.2914855480]])
M2 = np.array([[4.0767416621, -3.3077115913, 0.2309699292], [-1.2684380046, 2.6097574011, -0.3413193965],
               [-0.0041960863, -0.7034186147, 1.7076147010]])


def lin_of(L, C, h_deg):
    h = np.radians(h_deg)
    lab = np.stack([np.broadcast_to(L, np.shape(h)), C * np.cos(h), C * np.sin(h)], -1)
    return (lab @ M1.T) ** 3 @ M2.T


def chart():
    groups = {}
    rows = []

    def add(name, lin):
        lin = np.atleast_2d(np.asarray(lin, np.float64))
        groups[name] = slice(len(rows), len(rows) + len(lin))
        rows.extend(lin)
    hues = np.arange(0, 360, 15.0)
    for cname, c in (('pastel', 0.025), ('mid', 0.06), ('sat', 0.11)):
        for L in (0.45, 0.62, 0.78):
            add('%s%d' % (cname, int(L * 100)), lin_of(L, c, hues))
    for cname, c in (('deep', 0.03),):
        add('deep22', lin_of(0.22, c, hues))
    # skin (OKLab hue 40 - 55, C / L 0.08 - 0.13) at three lightnesses
    sk = []
    for L in (0.55, 0.68, 0.8):
        for h in (40, 45, 50, 55):
            for r in (0.08, 0.11):
                sk.append(lin_of(L, r * L, h))
    add('skin', sk)
    add('grey', np.repeat(np.geomspace(0.004, 0.85, 32)[:, None], 3, 1))
    add('sky', [lin_of(L, c, h) for L in (0.68, 0.8) for c in (0.05, 0.09) for h in (240, 250, 260)])
    add('foliage', [lin_of(L, c, h) for L in (0.42, 0.55) for c in (0.06, 0.1) for h in (125, 135, 145)])
    lin = np.maximum(np.array(rows), 0.0)
    return lin.astype(np.float32), groups, hues


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: colour parameter check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = expand_imports((SH / 'why/combine.glsl').read_text(encoding='utf-8'))
    colours, G, hues = chart()
    # the scene light at the AE the bench uses: OKLab L 0.62 ~ 0.24 linear, the photo's mid-tones
    n = len(colours)
    W = 64
    H = -(-n // W)
    img = np.zeros((H, W, 4), np.float32)
    img.reshape(-1, 4)[:n, :3] = colours
    lum = np.array([0.2126, 0.7152, 0.0722])
    y = np.maximum(np.maximum(colours @ lum, colours.max(1) * 0.5), 1e-4)
    grid = np.linspace(0, 2.51 / 2.43 - 0.0101, 20000)
    A, B, C = 2.43 * grid - 2.51, 0.59 * grid - 0.0375, 0.132 * grid
    table = np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0)
    fused = np.zeros((H, W, 4), np.float32)
    fused.reshape(-1, 4)[:n, 0] = np.interp(np.minimum(y, table.max()), table, grid) ** (1 / 2.2)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    face_tex = tex(np.ones((H, W, 4), np.float32))
    t_img, t_fused = tex(img), tex(fused)
    # a 17^3 film LUT: the display value inverted (a strong, easy to measure look)
    N = 17
    g = np.linspace(0, 1, N, dtype=np.float32)
    bb, gg, rr = np.meshgrid(g, g, g, indexing='ij')
    lut_inv = np.stack([1 - rr, 1 - gg, 1 - bb, np.ones_like(rr)], -1).astype('f4')
    lut_id = np.stack([rr, gg, bb, np.ones_like(rr)], -1).astype('f4')
    t_lut_inv = ctx.texture3d((N, N, N), 4, lut_inv.tobytes(), dtype='f4')
    t_lut_id = ctx.texture3d((N, N, N), 4, lut_id.tobytes(), dtype='f4')
    for t in (t_lut_inv, t_lut_id):
        t.filter = (moderngl.LINEAR, moderngl.LINEAR)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    shipped_defines = {'WHY_VIBRANCE': '1', 'LIGHT_CHROMA': '1', 'HL_NEUTRAL': '1', 'AGX_INSET': '1', 'WHY_BASE': '1',
                       'MACRO_TOE': '1', 'YELLOW_HUE': '1'}
    look_neutral = {'skinU': (1.0, 0.0, 0.0), 'vibLU': (0.0, 0.0), 'mixOnU': 0, 'monoU': (0.0, 0.0, 0.0), 'gamutU': 0.0,
                    'hueLockU': 0, 'gradeAU': (0.0, 0.0, 0.0, 0.0), 'gradeBU': (0.0, 0.0, 0.0, 0.5)}
    progs = {}

    def program(defs):
        key = tuple(sorted(defs.items()))
        if key not in progs:
            s = src
            for k, v in defs.items():
                s = set_define(s, k, v)
            progs[key] = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + s)
        return progs[key]

    def render(u=None, look=None, mix=None, extra_defs=None, lut=None):
        """u: uniform overrides; look: the COLOUR_LOOK uniforms (None: COLOUR_LOOK 0)."""
        defs = dict(shipped_defines)
        defs['COLOUR_LOOK'] = '1' if look is not None else '0'
        if extra_defs:
            defs.update(extra_defs)
        if lut is not None:
            defs['FILM_LUT'] = '1'
        prog = program(defs)
        out = ctx.texture((W, H), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, W, H)
        units = {'InputBuffer': t_img, 'GainMap': ones, 'WhyLow': t_img, 'WhyColour': t_img, 'WhyFused': t_fused,
                 'WhyDetailRef': t_img, 'WhyLumaS': t_img, 'FaceMask': face_tex}
        for i, (k, t) in enumerate(units.items()):
            if k in prog:
                t.use(i)
                prog[k].value = i
        if lut is not None and 'FilmLut' in prog:
            lut[0].use(9)
            prog['FilmLut'].value = 9
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1,
                'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.0, 'ditherU': 0,
                'agxAU': (2.45, 1.35, 2.0, 1.0), 'agxBU': (-8.5, 3.5, 0.0, 4.0), 'headroomU': 0, 'hlWhiteU': 0.15,
                'signedHueU': SIGNED_HUE,
                # WhyCombine's defaults (WhyTone.vibranceWhyUniform / vibranceSkinUniform / vibranceHlShape, the XML defaults)
                'vibU': (0.0, 0.25, 0.05), 'vibWhyU': (0.0, 0.25, 0.0, 0.0), 'vibSkinU': (1.0, math.radians(41.2), 0.16, 0.0),
                'vibSkinHueU': 0.0, 'vibHlU': 1.0, 'vibHlShapeU': (0.57, 0.89, 0.010, 0.025), 'lightChromaU': 1.0,
                'hlNeutralU': 1.0, 'insetU': 0.5, 'yellowHueU': 1.0,
                'faceAU': (0.0, 0.0, 0.0, 0.0), 'faceBU': (0.0, 0.0, 0.0, 0.0), 'faceAwbU': (0.0, 0.0)}
        if look is not None:
            vals.update(look_neutral)
            vals.update(look)
        if lut is not None:
            vals.update({'lutU': (lut[1], float(N)), 'lutMinU': (0.0, 0.0, 0.0), 'lutScaleU': (1.0, 1.0, 1.0)})
        if u:
            vals.update(u)
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        if look is not None and 'mixU' in prog:
            prog['mixU'].value = tuple(mix if mix is not None else [0.0] * 24)
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3].reshape(-1, 3)[:n].astype(np.float64)
        fb.release()
        out.release()
        return res

    def lab_of(disp):
        return oklab(np.maximum(disp, 0) ** 2.2)

    def lch(disp, sl):
        lab = lab_of(disp[sl])
        return lab[:, 0], np.hypot(lab[:, 1], lab[:, 2]), np.degrees(np.arctan2(lab[:, 2], lab[:, 1])) % 360, lab

    def dhue(a, b):
        return (a - b + 180) % 360 - 180

    def sane(name, disp):
        ok = np.isfinite(disp).all() and disp.min() >= 0 and disp.max() <= 1
        if not ok:
            check(False, '%s: non-finite or out of [0, 1] (min %.3g max %.3g)' % (name, np.nanmin(disp), np.nanmax(disp)))
        return ok

    def grey_spread(disp):
        lab = lab_of(disp[G['grey']])
        return float(np.hypot(lab[:, 1], lab[:, 2]).max())

    base = render()
    g0 = grey_spread(base)
    print('defaults: greys max OKLab chroma %.5f' % g0)
    check(np.array_equal(base, render(look={})), 'COLOUR_LOOK 1 at its defaults = COLOUR_LOOK 0 bit for bit (shipped WHY_VIBRANCE 1 defines)')
    check(np.array_equal(render(extra_defs={'P3_OUT': '1'}), render(look={}, extra_defs={'P3_OUT': '1'})),
          'COLOUR_LOOK 1 at its defaults = COLOUR_LOOK 0 bit for bit (Display P3)')
    sane('defaults', base)

    every = slice(0, n)
    coloured = np.concatenate([np.arange(n)[G[k]] for k in G if k != 'grey'])
    wheel_keys = [k for k in G if k.startswith(('pastel', 'mid', 'sat', 'deep'))]

    def wheel(disp, keys=None):
        idx = np.concatenate([np.arange(n)[G[k]] for k in (keys or wheel_keys)])
        lab = lab_of(disp[idx])
        return idx, lab

    def report(name, disp, ref=None):
        ref = base if ref is None else ref
        _, c0, h0, _ = lch(ref, every)
        _, c1, h1, _ = lch(disp, every)
        m = c0[coloured] > 0.02
        if REPORT:
            print('   %s: chroma x%.3f..x%.3f, hue moves up to %.2f deg, greys %.5f' % (
                name, (c1[coloured][m] / c0[coloured][m]).min(), (c1[coloured][m] / c0[coloured][m]).max(),
                np.abs(dhue(h1[coloured][m], h0[coloured][m])).max(), grey_spread(disp)))

    def chroma_ratio(disp, idx, ref=None):
        ref = base if ref is None else ref
        c0 = np.hypot(*lab_of(ref[idx])[:, 1:].T)
        c1 = np.hypot(*lab_of(disp[idx])[:, 1:].T)
        return c1 / np.maximum(c0, 1e-9)

    def hue_shift(disp, idx, ref=None):
        ref = base if ref is None else ref
        l0, l1 = lab_of(ref[idx]), lab_of(disp[idx])
        return dhue(np.degrees(np.arctan2(l1[:, 2], l1[:, 1])), np.degrees(np.arctan2(l0[:, 2], l0[:, 1])))

    def idx_of(*keys):
        return np.concatenate([np.arange(n)[G[k]] for k in keys])

    def scene_lab(keys):
        return oklab(colours[idx_of(*keys)].astype(np.float64))

    pastel = idx_of(*[k for k in wheel_keys if k.startswith('pastel')])
    satd = idx_of(*[k for k in wheel_keys if k.startswith('sat')])
    midc = idx_of(*[k for k in wheel_keys if k.startswith('mid')])
    skin = idx_of('skin')
    greys = idx_of('grey')
    wheel_all = idx_of(*wheel_keys)
    wheel_hue = np.tile(hues, len(wheel_keys))   # every wheel group has the 24 hues
    far_skin = np.abs(dhue(wheel_hue, 47)) > 70

    # ------------------------------------------------------------------ vibrance (vibU.x pastel, vibWhyU.x saturated, ...)
    def vib_row(name, setter, lo, hi, zone, off_zone, expect_up=True, off_tol=0.01, hue_tol=2.5):
        # the YELLOW_HUE turn (a saturated orange-yellow's hue, by its C / L) off here: a richer colour crosses its gate by design
        def at(v):
            return dict(setter(v), yellowHueU=0.0)
        ref = render(at(0.0))
        r_lo, r_hi = render(at(lo)), render(at(hi))
        ok_lo, ok_hi = sane(name + ' min', r_lo), sane(name + ' max', r_hi)
        gl, gh = grey_spread(r_lo), grey_spread(r_hi)
        z_lo, z_hi = chroma_ratio(r_lo, zone, ref).mean(), chroma_ratio(r_hi, zone, ref).mean()
        print('%s %+g / %+g: zone chroma x%.3f / x%.3f; greys %.5f / %.5f' % (name, lo, hi, z_lo, z_hi, gl, gh))
        check(ok_lo and ok_hi, name + ': finite, in range at both ends')
        check(max(gl, gh) <= g0 + 0.002, name + ': greys stay grey (%.4f / %.4f)' % (gl, gh))
        if expect_up:
            check(z_lo < 1.0 - 0.02 and z_hi > 1.0 + 0.02, name + ': lower = paler, higher = richer on its zone')
        if off_zone is not None and len(off_zone):
            d = max(np.abs(chroma_ratio(r_lo, off_zone, ref) - 1).max(), np.abs(chroma_ratio(r_hi, off_zone, ref) - 1).max())
            check(d < off_tol, name + ': colours off its zone keep their chroma (max change %.4f)' % d)
        sel = np.concatenate([zone])
        hs = max(np.abs(hue_shift(r_lo, sel, ref)).max(), np.abs(hue_shift(r_hi, sel, ref)).max())
        check(hs < hue_tol, name + ': hue stays (max %.2f deg)' % hs)
        report(name + ' min', r_lo, ref)
        report(name + ' max', r_hi, ref)
        return r_lo, r_hi

    def vib_u(x=0.0, sky=0.25, green=0.05):
        return {'vibU': (x, sky, green)}

    # «Сочность пастельных тонов» (vibU.x; -1 .. 1): pastel colours; saturated ones barely (mix to vibWhyU.x from chroma 0.02 .. 0.09)
    scene_c = np.hypot(*oklab(colours.astype(np.float64))[:, 1:].T)
    # the sky / green terms (default on) also act on the pastel blues and greens: measure the pastel zone off those hues
    neutral_sky = lambda x: {'vibU': (x, 0.0, 0.0)}
    vib_row('vibrance (pastel)', neutral_sky, -1.0, 1.0, pastel, None)
    # «Сочность ярких тонов» (vibWhyU.x; 0 .. 1)
    r_hi = render({'vibWhyU': (1.0, 0.25, 0.0, 0.0), 'vibU': (0.0, 0.0, 0.0)})
    r_0 = render({'vibU': (0.0, 0.0, 0.0)})
    print('vibrance (saturated) 1: saturated x%.3f, pastel x%.3f' % (chroma_ratio(r_hi, satd, r_0).mean(), chroma_ratio(r_hi, pastel, r_0).mean()))
    check(sane('vibrance saturated', r_hi) and chroma_ratio(r_hi, satd, r_0).mean() > 1.05
          and chroma_ratio(r_hi, satd, r_0).mean() > chroma_ratio(r_hi, pastel, r_0).mean(),
          'vibrance (saturated): richer saturated colours, more than pastel ones')
    check(grey_spread(r_hi) <= g0 + 0.002, 'vibrance (saturated): greys stay grey')
    # «Сочность неба» (vibU.y; -1 .. 1): blues only (OKLab b < 0)
    sl = scene_lab(wheel_keys)
    blue_side = wheel_all[sl[:, 2] < -0.01]
    warm_side = wheel_all[(sl[:, 2] > 0.005) & (sl[:, 1] > 0.005)]
    vib_row('vibrance sky', lambda v: vib_u(sky=v, green=0.0), -1.0, 1.0, idx_of('sky'), warm_side)
    # «Сочность зелени» (vibU.z): greens (OKLab a < 0) only
    green_side = wheel_all[sl[:, 1] < -0.01]
    red_side = wheel_all[sl[:, 1] > 0.005]
    vib_row('vibrance green', lambda v: vib_u(sky=0.0, green=v), -1.0, 1.0, idx_of('foliage'), red_side)
    # «Сочность в светах» (vibWhyU.y; 0 .. 1.5): OKLab L 0.60 .. 0.98 only
    lt = oklab(colours.astype(np.float64))[:, 0]
    light = wheel_all[(lt[wheel_all] > 0.74)]
    dark = wheel_all[(lt[wheel_all] < 0.5)]
    r0 = render({'vibWhyU': (0.0, 0.0, 0.0, 0.0)})
    r1 = render({'vibWhyU': (0.0, 1.5 * 1.0, 0.0, 0.0)})
    print('vibrance light tones 0 / 1.5: light x%.3f, dark x%.4f' % (chroma_ratio(r1, light, r0).mean(), chroma_ratio(r1, dark, r0).mean()))
    check(sane('vib hl', r1) and chroma_ratio(r1, light, r0).mean() > 1.05 and np.abs(chroma_ratio(r1, dark, r0) - 1).max() < 0.01,
          'vibrance light tones: the light tones only')
    check(grey_spread(r1) <= g0 + 0.002, 'vibrance light tones: greys stay grey')
    # «Сочность полутонов» (vibWhyU.z; -1 .. 1.5), «Сочность в тенях» (vibWhyU.w; -1 .. 1)
    # (the deep shadows: a channel on the AgX floor turns a colour's display hue by a few degrees as its chroma changes - the per-channel
    # tone scale's own behaviour, not the row's)
    for name, k, lo, hi, zone, off, ht in (('vibrance mid', 2, -1.0, 1.5, wheel_all[(lt[wheel_all] > 0.55) & (lt[wheel_all] < 0.66)],
                                            None, 2.5),
                                           ('vibrance shadows', 3, -1.0, 1.0, idx_of('deep22'), wheel_all[lt[wheel_all] > 0.4], 5.0)):
        def st(v, k=k):
            w = [0.0, 0.25, 0.0, 0.0]
            w[k] = v
            return {'vibWhyU': tuple(w)}
        vib_row(name, st, lo, hi, zone, off, hue_tol=ht)
    # «Защита кожи в сочности» (vibSkinU.x 0 .. 1): skin held back from every vibrance, the rest untouched
    strong = {'vibU': (1.0, 0.0, 0.0), 'vibWhyU': (1.0, 0.25, 0.0, 0.0)}
    p0 = render(dict(strong, vibSkinU=(0.0, math.radians(41.2), 0.16, 0.0)))
    p1 = render(dict(strong, vibSkinU=(1.0, math.radians(41.2), 0.16, 0.0)))
    rv = render({'vibU': (0.0, 0.0, 0.0), 'vibWhyU': (0.0, 0.0, 0.0, 0.0)})
    g_off, g_on = chroma_ratio(p0, skin, rv).mean(), chroma_ratio(p1, skin, rv).mean()
    far = wheel_all[far_skin]
    print('skin protect under vibrance 1: skin x%.3f unprotected, x%.3f protected; far hues change %.4f'
          % (g_off, g_on, np.abs(chroma_ratio(p1, far, p0) - 1).max()))
    check(g_on - 1 < 0.6 * (g_off - 1), 'skin protection holds the vibrance back on skin')
    check(np.abs(chroma_ratio(p1, far, p0) - 1).max() < 0.005, 'skin protection leaves hues far from skin alone')
    # «Сдвиг насыщенности кожи» (vibSkinU.w -0.5 .. 0.5), «Сдвиг оттенка кожи» (vibSkinHueU -30 .. 30; plus = to yellow)
    s_lo = render({'vibSkinU': (1.0, math.radians(41.2), 0.16, -0.5)})
    s_hi = render({'vibSkinU': (1.0, math.radians(41.2), 0.16, 0.5)})
    print('skin saturation shift -0.5 / +0.5: skin x%.3f / x%.3f, far hues %.4f' % (
        chroma_ratio(s_lo, skin).mean(), chroma_ratio(s_hi, skin).mean(),
        max(np.abs(chroma_ratio(s_lo, far) - 1).max(), np.abs(chroma_ratio(s_hi, far) - 1).max())))
    check(sane('skin sat', s_lo) and sane('skin sat', s_hi) and chroma_ratio(s_lo, skin).mean() < 0.9 < 1.1 < chroma_ratio(s_hi, skin).mean(),
          'skin saturation shift: minus paler, plus richer skin')
    check(max(np.abs(chroma_ratio(s_lo, far) - 1).max(), np.abs(chroma_ratio(s_hi, far) - 1).max()) < 0.005
          and max(grey_spread(s_lo), grey_spread(s_hi)) <= g0 + 0.002, 'skin saturation shift: skin only')
    h_lo, h_hi = render({'vibSkinHueU': -30.0}), render({'vibSkinHueU': 30.0})
    dl, dh = hue_shift(h_lo, skin).mean(), hue_shift(h_hi, skin).mean()
    print('skin hue shift -30 / +30: skin hue %+.1f / %+.1f deg; far hues %.3f deg' % (
        dl, dh, max(np.abs(hue_shift(h_lo, far)).max(), np.abs(hue_shift(h_hi, far)).max())))
    check(dl < -3 and dh > 3, 'skin hue shift: plus towards yellow (OKLab hue up), minus towards red')
    check(max(np.abs(hue_shift(h_lo, far)).max(), np.abs(hue_shift(h_hi, far)).max()) < 0.2, 'skin hue shift: skin only')

    # ------------------------------------------------------------------ AgX saturation (agxAU.w; «Насыщенность» 0 .. 2, x «Насыщенность OPPO»)
    sats = [0.0, 0.5, 1.0, 1.5, 2.0]
    rs = [render({'agxAU': (2.45, 1.35, 2.0, s)}) for s in sats]
    cs = np.array([np.hypot(*lab_of(r[coloured])[:, 1:].T) for r in rs])
    ls = np.array([lab_of(r[coloured])[:, 0] for r in rs])
    print('AgX saturation 0 / 0.5 / 1 / 1.5 / 2: mean chroma %s, max |dL| %.4f, grey %.5f' % (
        ' / '.join('%.4f' % c for c in cs.mean(1)), np.abs(ls - ls[2]).max(), max(grey_spread(r) for r in rs)))
    check(all(sane('agx sat %g' % s, r) for s, r in zip(sats, rs)), 'AgX saturation: finite, in range 0 .. 2')
    check(cs[0].max() < 0.002, 'AgX saturation 0: black and white (max chroma %.4f)' % cs[0].max())
    check((np.diff(cs, axis=0) >= -1e-4).all(), 'AgX saturation: chroma never falls as it rises (min step %.2e)' % np.diff(cs, axis=0).min())
    hs = max(np.abs(hue_shift(rs[1], coloured)).max(), np.abs(hue_shift(rs[4], coloured)[cs[2] > 0.02]).max())
    check(hs < 1.0, 'AgX saturation: the hue stays (max %.2f deg)' % hs)
    check(np.abs(ls - ls[2]).max() < 0.01, 'AgX saturation: lightness kept (max %.4f)' % np.abs(ls - ls[2]).max())
    # with the skin protection (COLOUR_LOOK 1 skinU.x 1, as WhyTone.colourLookOn turns it on for a saturation above 1)
    a2 = render({'agxAU': (2.45, 1.35, 2.0, 2.0)}, look={'skinU': (1.0, 0.0, 0.0)})
    a2u = render({'agxAU': (2.45, 1.35, 2.0, 2.0)}, look={'skinU': (0.0, 0.0, 0.0)})
    print('AgX saturation 2 on skin: x%.3f protected, x%.3f unprotected; far hues %.4f' % (
        chroma_ratio(a2, skin).mean(), chroma_ratio(a2u, skin).mean(), np.abs(chroma_ratio(a2, far, a2u) - 1).max()))
    check(chroma_ratio(a2, skin).mean() < chroma_ratio(a2u, skin).mean() - 0.1, 'skin protection holds the AgX saturation back on skin')
    check(np.abs(chroma_ratio(a2, far, a2u) - 1).max() < 0.005, 'skin protection of the AgX saturation: skin only')

    # ------------------------------------------------------------------ «Цвет» (COLOUR_LOOK 1)
    # skin hue (skinU.y -1 .. 1: plus = golden), evenness (skinU.z)
    k_lo, k_hi = render(look={'skinU': (1.0, -1.0, 0.0)}), render(look={'skinU': (1.0, 1.0, 0.0)})
    dl, dh = hue_shift(k_lo, skin).mean(), hue_shift(k_hi, skin).mean()
    print('«Тон кожи» -100 / +100: skin hue %+.1f / %+.1f deg; far %.3f' % (dl, dh, max(np.abs(hue_shift(k_lo, far)).max(), np.abs(hue_shift(k_hi, far)).max())))
    check(dl < -3 and dh > 3 and max(np.abs(hue_shift(k_lo, far)).max(), np.abs(hue_shift(k_hi, far)).max()) < 0.2,
          '«Тон кожи»: plus golden (hue up), minus redder; skin only')
    ev = render(look={'skinU': (1.0, 0.0, 1.0)})
    _, _, hs0, _ = lch(base, skin)
    _, _, hs1, _ = lch(ev, skin)
    print('«Ровная кожа» 100: skin hue spread %.1f -> %.1f deg' % (np.std(hs0), np.std(hs1)))
    check(np.std(hs1) < np.std(hs0) * 0.8 and np.abs(hue_shift(ev, far)).max() < 0.2, '«Ровная кожа»: skin hues closer together, others kept')
    # monochrome filters: every filter grey; neutral keeps a grey's level
    MONO_T = {'neutral': (1, 1, 1), 'yellow': (1, 0.9, 0.1), 'orange': (1, 0.6, 0.05), 'red': (1, 0.2, 0.02), 'green': (0.25, 1, 0.3),
              'blue': (0.1, 0.25, 1)}
    for name, t in MONO_T.items():
        r = render(look={'monoU': t})
        sp = np.hypot(*lab_of(r)[:, 1:].T).max()
        gd = np.abs(lab_of(r[greys])[:, 0] - lab_of(base[greys])[:, 0]).max()
        if name == 'neutral' or REPORT:
            print('monochrome %s: max chroma %.5f, greys |dL| %.5f' % (name, sp, gd))
        check(sp < 0.003 and gd < 0.002, 'monochrome %s: grey picture, greys keep their level' % name)
    # P512 Face AWB / face mask on a monochrome picture: no colour comes back
    r = render({'faceAwbU': (0.02, 0.02)}, look={'monoU': (1.0, 1.0, 1.0)}, extra_defs={'FACE_AWB': '1'})
    sp = np.hypot(*lab_of(r)[:, 1:].T).max()
    print('monochrome + Face AWB (0.02, 0.02): max chroma %.5f' % sp)
    check(sp < 0.003, 'monochrome stays grey with Face AWB (a face found)')
    # mixer: every band, saturation / hue / lightness at -100 / +100 on its own hue only
    BAND_HUE = [29, 53, 110, 143, 195, 264, 294, 344]
    names = ['red', 'orange', 'yellow', 'green', 'aqua', 'blue', 'purple', 'magenta']
    scene_h = np.degrees(np.arctan2(sl[:, 2], sl[:, 1])) % 360
    sc_chroma = np.hypot(sl[:, 1], sl[:, 2])
    bad = []
    for b, bh in enumerate(BAND_HUE):
        prev, nxt = BAND_HUE[b - 1], BAND_HUE[(b + 1) % 8]
        own = wheel_all[(np.abs(dhue(scene_h, bh)) < 8) & (sc_chroma > 0.05)]
        # off: beyond both neighbouring centres (the raised-cosine bands end there)
        offm = np.array([not ((dhue(h, prev) > -1 and dhue(nxt, h) > -1)) for h in scene_h])
        offb = wheel_all[offm & (sc_chroma > 0.02)]
        for part, lo_hi in ((1, (-1.0, 1.0)), (0, (-1.0, 1.0)), (2, (-1.0, 1.0))):
            rr = []
            for v in lo_hi:
                mix = [0.0] * 24
                mix[b * 3 + part] = v
                rr.append(render(look={'mixOnU': 1, 'skinU': (0.0, 0.0, 0.0)}, mix=mix))
            if not all(sane('mixer', x) for x in rr):
                bad.append('%s %d range' % (names[b], part))
                continue
            if part == 1:
                lo_c, hi_c = chroma_ratio(rr[0], own).mean(), chroma_ratio(rr[1], own).mean()
                ok = lo_c < 0.3 and hi_c > 1.15
            elif part == 0:
                lo_c, hi_c = hue_shift(rr[0], own).mean(), hue_shift(rr[1], own).mean()
                ok = lo_c < -5 and hi_c > 5
            else:
                lo_c = (lab_of(rr[0][own])[:, 0] - lab_of(base[own])[:, 0]).mean()
                hi_c = (lab_of(rr[1][own])[:, 0] - lab_of(base[own])[:, 0]).mean()
                ok = lo_c < -0.02 and hi_c > 0.02
            off_d = max(np.abs(rr[0][offb] - base[offb]).max(), np.abs(rr[1][offb] - base[offb]).max(),
                        np.abs(rr[0][greys] - base[greys]).max(), np.abs(rr[1][greys] - base[greys]).max())
            if REPORT:
                print('   mixer %s %s: %+.3f / %+.3f, off-band max %.5f' % (names[b], ('hue', 'sat', 'light')[part], lo_c, hi_c, off_d))
            if not ok or off_d > 1e-4:
                bad.append('%s %s (%.3f / %.3f, off %.4f)' % (names[b], ('hue', 'sat', 'light')[part], lo_c, hi_c, off_d))
    mix = [0.0] * 24
    mix[1 * 3 + 1] = 1.0
    on_p = render(look={'mixOnU': 1}, mix=mix)
    own = wheel_all[(np.abs(dhue(scene_h, 53)) < 8) & (sc_chroma > 0.05)]
    print('mixer orange saturation +100: oranges x%.3f with the skin protection (default 1), x%.3f without' % (
        chroma_ratio(on_p, own).mean(), chroma_ratio(render(look={'mixOnU': 1, 'skinU': (0.0, 0.0, 0.0)}, mix=mix), own).mean()))
    check(not bad, 'mixer: each band moves its hue / saturation / lightness the right way, other hues and greys untouched %s' % bad)
    # grading: a zone tints its lightness range; skin protected
    for z, (zname, lsel) in enumerate((('shadows', lambda L: L < 0.35), ('mids', lambda L: (L > 0.45) & (L < 0.6)),
                                       ('highlights', lambda L: L > 0.8))):
        off = 0.06 * np.array([math.cos(math.radians(250)), math.sin(math.radians(250))])
        ga, gb = [0.0, 0.0, 0.0, 0.0], [0.0, 0.0, 0.0, 0.5]
        if z < 2:
            ga[2 * z], ga[2 * z + 1] = off
        else:
            gb[0], gb[1] = off
        r = render(look={'gradeAU': tuple(ga), 'gradeBU': tuple(gb)})
        lg, lg0 = lab_of(r[greys]), lab_of(base[greys])
        zone = lsel(lg0[:, 0])
        # the zones' feather at blending 50: shadows / highlights from the pivot 0.5 out to 0.4 away, mid-tones 0.1 .. 0.9
        Lg = lg0[:, 0]
        other = (Lg > 0.52) if z == 0 else ((Lg < 0.08) | (Lg > 0.92)) if z == 1 else (Lg < 0.48)
        dz = (lg[zone, 2] - lg0[zone, 2]).mean() if zone.any() else 0.0
        do = np.abs(lg[other, 1:] - lg0[other, 1:]).max() if other.any() else 0.0
        print('grading %s blue: its greys b %+.4f, far greys %.4f' % (zname, dz, do))
        check(dz < -0.01 and do < 0.005 and sane('grade', r), 'grading %s: tints its own tones' % zname)
    # hue lock off / blues, gamut compression: sane at the ends
    for name, look in (('hue lock off', {'hueLockU': 2}), ('hue lock blues', {'hueLockU': 1}), ('gamut 100', {'gamutU': 1.0})):
        r = render(look=look)
        check(sane(name, r) and grey_spread(r) <= g0 + 0.002, name + ': finite, greys grey')

    # ------------------------------------------------------------------ «Маска лица» (FACE_MASK; the whole chart a face here)
    fd = {'FACE_MASK': '1'}
    f0 = render(extra_defs=fd)
    check(np.abs(f0 - base).max() < 1e-6, 'face mask at its neutral values = no mask (within float rounding; WhyCombine sets no FACE_MASK then)')
    for name, au, bu, test in (
            ('face saturation -100', (0, 0, -1, 0), (0, 0, 0, 0), lambda r: chroma_ratio(r, coloured).mean() < 0.5),
            ('face saturation +100', (0, 0, 1, 0), (0, 0, 0, 0), lambda r: chroma_ratio(r, coloured).mean() > 1.2),
            ('face warmth +100', (0, 0, 0, 1), (0, 0, 0, 0), lambda r: (lab_of(r[greys])[:, 2] > 0.005).mean() > 0.5),
            ('face warmth -100', (0, 0, 0, -1), (0, 0, 0, 0), lambda r: (lab_of(r[greys])[:, 2] < -0.005).mean() > 0.5),
            ('face tint +100', (0, 0, 0, 0), (1, 0, 0, 0), lambda r: (lab_of(r[greys])[:, 1] > 0.005).mean() > 0.5),
            ('face tint -100', (0, 0, 0, 0), (-1, 0, 0, 0), lambda r: (lab_of(r[greys])[:, 1] < -0.005).mean() > 0.5)):
        r = render({'faceAU': tuple(map(float, au)), 'faceBU': tuple(map(float, bu))}, extra_defs=fd)
        check(sane(name, r) and test(r), name + ' acts the stated way (warm = +b, magenta = +a)')

    # ------------------------------------------------------------------ film LUT strength (FILM_LUT): 0 .. 1 a straight mix
    r_id = render(lut=(t_lut_id, 1.0))
    check(np.abs(r_id - base).max() < 2e-3, 'film LUT: an identity table at 100 %% renders the photo (max %.5f)' % np.abs(r_id - base).max())
    r_half = render(lut=(t_lut_inv, 0.5))
    r_full = render(lut=(t_lut_inv, 1.0))
    exp_half = 0.5 * base + 0.5 * r_full
    check(np.abs(r_half - exp_half).max() < 1e-4, 'film LUT strength 50 %: half way between the photo and the look')

    ctx.release()
    if failures:
        print('colour parameter check FAIL (%d)' % len(failures))
        return 1
    print('colour parameter check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
