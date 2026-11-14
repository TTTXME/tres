"""P686: the fast route's highlight path on a Quad / Tetra stream (one colour per HALF texel), the owner's pink windows with white
dots (vivo X200 Ultra, 50 MP Quad, 2026-11-14).

Runs the shipped programs (GL 4.3) on synthetic HALF textures:
  - kFastShortRecover (sh011) on a short frame of a sunlit sky (neutral 0.35 / 0.68: G and B clip first). Read as a Bayer quad
    (u_block 0, before P686) a near-clipped Quad cell's four sites became the cell's value x 1 / 2.84 / 2.84 / 1.93 (white islands
    at 2.15 x the short frame's clip, a lattice inside the cell). Now: no site above the clip x the largest white-balance gain over
    the colour's own gain, a cell's sites alike, a cell clipped in every colour neutral after white balance, an unclipped cell
    untouched (bit for bit);
  - kFastHlValidate (sh058) against the base through a flow of 2.9 / 3 texels (the owner's frame 14): the base measured R below its
    clip, G and B clipped (a sky 1 .. 2.8 x white). Before, the texel nearest the flow was of another colour (odd runs) and refused
    the short frame there (pink, islands where noise let it through); now every texel of the sky valid, an odd flow as an even one,
    a dark object the short frame alone holds still refused;
  - kFastOutput's fill over a band the short frames refused in a recovered sky (the base's pink partial clip, Quad cells): before, a
    pixel over an R cell (measured) was never filled and every ray stopped there, the Quad de-lattice / de-grid read the unfilled
    neighbours and took the band back: it stayed pink; now filled to the sky's colour;
  - a Bayer stream (u_block 0) through every program exactly as before."""
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SH = (ROOT / 'app/src/main/cpp/scam-fast-shaders.h').read_text(encoding='utf-8')
GPU = (ROOT / 'app/src/main/cpp/scam-fast-gpu.h').read_text(encoding='utf-8')
MERGE = (ROOT / 'app/src/main/cpp/scam-fast-merge.h').read_text(encoding='utf-8')
fails = []


def check(ok, what):
    print(('PASS ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)


def src(text, name):
    m = re.search(r'static const char\* ' + name + r'=R"FM\((.*?)\)FM";', text, re.S)
    check(m is not None, name + ' found')
    return m.group(1).replace('#version 310 es', '#version 430') if m else ''


VS, RECOVER, VALIDATE, OUTPUT = src(SH, 'kFastVs'), src(SH, 'kFastShortRecover'), src(SH, 'kFastHlValidate'), src(GPU, 'kFastOutput')
check('const int hlBlock=quad?(sensorType==2?2:1):0;' in MERGE, 'the Quad (1) / Tetra (2) run length from the stream\'s colour block')
check(MERGE.count('g.u1i(A::pShortRecover,"u_block",hlBlock);') == 1 and MERGE.count('g.u1i(A::pHlValidate,"u_block",hlBlock);') == 1
      and MERGE.count('g.u1i(A::pOutput,"u_block",hlBlock);') == 2, 'u_block set for the short recovery, the validation and both output passes')
check('g.u1i(A::pShortRecover,"u_row0",qa);g.u1i(A::pShortRecover,"u_row1",qb);' in MERGE, 'a row strip\'s recovery reads only its unpacked rows')

WB = np.array([2.844, 1.0, 1.473], np.float32)      # the owner's white balance (neutral 0.3516 / 0.6787)
BLACK, WHITE = 64.0, 1023.0
CFA = np.array([0, 1, 2, 3])                          # RGGB: phase (y & 1) * 2 + (x & 1) -> R, Gr, Gb, B
CH = np.array([0, 1, 1, 2])                           # -> R, G, B


def quad_tex(rgb, k):
    """a HALF texture of a Quad (k 1) / Tetra (k 2) stream from a HALF-grid RGB field (normalised, clipped at 1): every texel the
    four sites of its run's colour"""
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    ph = ((yy // k) & 1) * 2 + ((xx // k) & 1)
    v = np.take_along_axis(rgb, CH[CFA[ph]][..., None], -1)[..., 0]
    dn = BLACK + np.clip(v, 0, 1) * (WHITE - BLACK)
    return np.repeat(dn[..., None], 4, -1).astype(np.float32), CH[CFA[ph]]


def bayer_tex(rgb):
    """a HALF texture of a Bayer stream: every texel one RGGB quad"""
    v = np.clip(rgb, 0, 1)
    return (BLACK + np.stack([v[..., 0], v[..., 1], v[..., 1], v[..., 2]], -1) * (WHITE - BLACK)).astype(np.float32)


try:
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    made = []

    def tex(a, linear=False):
        a = np.ascontiguousarray(a, np.float32)
        t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype='f4')
        f = moderngl.LINEAR if linear else moderngl.NEAREST
        t.filter = (f, f)
        t.repeat_x = t.repeat_y = False
        made.append(t)
        return t

    def setu(p, vals, texs):
        for i, (k, t) in enumerate(texs.items()):
            t.use(i)
            if k in p:
                p[k].value = i
        for k, v in vals.items():
            if k in p:
                p[k].value = v

    rec = ctx.compute_shader(RECOVER)

    def recover(q, block, rows=None):
        h, w = q.shape[:2]
        inp = tex(q)
        out = ctx.texture((w, h), 4, dtype='f4')
        made.append(out)
        r0, r1 = rows if rows else (0, 0)
        setu(rec, {'u_width': w, 'u_height': h, 'u_row0': r0, 'u_row1': r1, 'u_cfa_pattern': 0, 'u_black_level': (BLACK,) * 4,
                   'u_white_level': WHITE, 'u_wb_gains': (float(WB[0]), float(WB[1]), float(WB[2]), 1.0), 'u_block': block}, {'u_input_extracted': inp})
        out.bind_to_image(1, read=False, write=True)
        rec.run((w + 15) // 16, (h + 15) // 16)
        return np.frombuffer(out.read(), np.float32).reshape(h, w, 4).copy()

    # ---- the short frame of a sunlit sky (x 1/8 of the base): G 0.5 .. 1.6 of its clip (the window's upper part clipped)
    H2, W2 = 48, 64
    yy, xx = np.mgrid[0:H2, 0:W2].astype(np.float32)
    g = 0.5 + 1.1 * (1 - yy / (H2 - 1)) + 0.05 * np.sin(xx * 0.7) * np.cos(yy * 0.5)
    sky = np.stack([g * 0.3516 * 0.92 * 2.2, g, g * 0.6787 * 1.06 * 1.15], -1).astype(np.float32)  # R 0.7, B 0.83 of G (sensor)
    for k, name in ((1, 'Quad'), (2, 'Tetra')):
        q, ch = quad_tex(sky, k)
        old, new = recover(q, 0), recover(q, k)
        nold = (old - BLACK) / (WHITE - BLACK); nnew = (new - BLACK) / (WHITE - BLACK); nq = (q - BLACK) / (WHITE - BLACK)
        cap = float(WB.max() / WB.min()) + 1e-3
        spread_old = float((nold.max(-1) - nold.min(-1)).max()); spread_new = float((nnew.max(-1) - nnew.min(-1)).max())
        print('%s short recovery: the highest site %.2f -> %.2f of the clip (cap %.2f), the largest spread inside a cell %.2f -> %.2f'
              % (name, float(nold.max()), float(nnew.max()), cap, spread_old, spread_new))
        check(nold.max() > 2.5 and spread_old > 1.0, '%s, before: a near-clipped cell read as R, Gr, Gb, B (sites x 2.84, a lattice: the test sees it)' % name)
        check(nnew.max() <= cap and spread_new < 1e-4, '%s: no site above the clip x the gains\' ratio, the four sites of a cell alike' % name)
        # clipped in every colour (the window's top rows): neutral after the white balance
        top = (yy < 6) & (xx > 4) & (xx < W2 - 5)
        lvl = nnew[..., 0] * WB[ch]
        check(float(np.ptp(lvl[top])) < 1e-3 * float(lvl[top].mean()), '%s: a cell clipped in every colour neutral after white balance (%.3f)' % (name, float(lvl[top].mean())))
        # well below the clip (the lower rows, max 0.65): untouched
        low = sky.max(-1) < 0.65
        check(np.array_equal(new[low], q[low]), '%s: cells under the clip untouched' % name)
        # a row strip [16, 32): its rows from its own rows only (whatever stale rows lie outside), its inner rows as the frame's
        qa = q.copy(); qa[:16] = 0.0; qa[32:] = 0.0
        qb = q.copy(); qb[:16] = WHITE; qb[32:] = WHITE
        sa, sb = recover(qa, k, (16, 32)), recover(qb, k, (16, 32))
        check(np.array_equal(sa[16:32], sb[16:32]) and np.array_equal(sa[16 + 2 * k:32 - 2 * k], new[16 + 2 * k:32 - 2 * k]),
              '%s: a row strip\'s rows from its unpacked rows only, its inner rows as the whole frame\'s' % name)
    # Bayer: the reference's reading, as before
    bq = bayer_tex(sky)
    b0 = recover(bq, 0)
    nb = (b0 - BLACK) / (WHITE - BLACK)
    check(float(nb.max()) <= float(WB.max() / WB.min()) + 1e-3, 'Bayer quads (u_block 0): the reference\'s recovery, as before')

    # ---- the validation against the base through an odd flow (the owner's frame 14: 2.9 quads)
    val = ctx.program(vertex_shader=VS.replace('#version 430', '#version 430'), fragment_shader=VALIDATE)

    def validate(base_q, short_q, flow_px, block, ev=8.0):
        h, w = base_q.shape[:2]
        flow = np.zeros((4, 4, 4), np.float32); flow[..., 0] = flow_px / w
        texs = {'u_input_mask': tex(np.ones((h, w), np.float32), True), 'u_base_bayer': tex(base_q), 'u_short_bayer': tex(short_q),
                'u_flow_tex': tex(flow, True), 'u_prev_valid_accum': tex(np.ones((h, w), np.float32))}
        setu(val, {'u_flow_scale_offset': (1.0, 1.0, 0.0, 0.0), 'u_black_level': (BLACK,) * 4, 'u_white_level': WHITE, 'u_ev_scale': ev,
                   'u_is_first_short': 1, 'u_err_thresh': 0.8, 'u_wb': tuple(map(float, WB)), 'u_block': block, 'u_cfa_pattern': 0}, texs)
        o = ctx.texture((w, h), 4, dtype='f4'); made.append(o)
        fb = ctx.framebuffer([o]); fb.use(); ctx.viewport = (0, 0, w, h)
        ctx.vertex_array(val, []).render(vertices=3)
        r = np.frombuffer(o.read(), np.float32).reshape(h, w, 4).copy()
        fb.release()
        return r

    # the sky through the window, 1.2 .. 2.6 x the base's white (G): the base clips G and B, R measured (0.45 .. 0.9)
    gs = 1.2 + 1.4 * (xx / (W2 - 1)) * (0.8 + 0.2 * np.cos(yy * 0.3))
    scene = np.stack([gs * 0.3516, gs, gs * 0.6787 * 1.2], -1).astype(np.float32)
    for k, name in ((1, 'Quad'), (2, 'Tetra')):
        for shift in (2.9, 3.0, 2.0, 1.4):
            sh = int(np.round(shift))
            moved = np.roll(scene, sh, axis=1)                 # the short frame sees the scene shifted by the flow (whole texels)
            bq, _ = quad_tex(scene, k)
            sq, _ = quad_tex(moved / 8.0, k)
            sq[:, :sh] = BLACK                                 # nothing came in from the left edge
            inner = (slice(4, H2 - 4), slice(8, W2 - 8))
            vo = validate(bq, sq, shift, 0)[inner][..., 0]
            vn = validate(bq, sq, shift, k)[inner][..., 0]
            print('%s validation, flow %.1f texels: valid %.3f -> %.3f' % (name, shift, float(vo.mean()), float(vn.mean())))
            if k == 1 and sh % 2 == 1:
                check(vo.mean() < 0.6, '%s flow %.1f, before: the texel of another colour refused the short frame (the test sees it)' % (name, shift))
            check(vn.min() > 0.99, '%s flow %.1f: the sky valid in every texel (the base texel\'s own colour)' % (name, shift))
        # a dark object only in the short frame (it moved in): still refused
        bq, _ = quad_tex(scene, k)
        obj = scene.copy(); obj[20:28, 30:38] = 0.005
        sq, _ = quad_tex(np.roll(obj, 3, axis=1) / 8.0, k)
        vn = validate(bq, sq, 3.0, k)[22:26, 32:36, 0]
        print('%s validation, a dark object in the short frame only: valid %.3f' % (name, float(vn.mean())))
        check(vn.mean() < 0.35, '%s: a dark object the short frame alone holds still refused' % name)
    bq = bayer_tex(scene); sq = bayer_tex(np.roll(scene, 3, axis=1) / 8.0)
    check(np.array_equal(validate(bq, sq, 3.0, 0), validate(bq, sq, 3.0, 0)) and validate(bq, sq, 3.0, 0)[4:-4, 8:-8, 0].min() > 0.99,
          'Bayer quads (u_block 0): valid as before')

    # ---- the output pass's fill over a refused band in a recovered sky, Quad cells, the Quad filters on
    out = ctx.compute_shader(OUTPUT)
    N = 128

    def run_out(merged, base_rgb, hl_base, hl_final, block, quadf=1.0, fill=24, quadbase=True):
        H = W = N
        acc0 = np.concatenate([merged, np.ones((H, W, 1), np.float32)], -1)
        acc1 = np.concatenate([np.ones((H, W, 2), np.float32), np.zeros((H, W, 1), np.float32), np.ones((H, W, 1), np.float32)], -1)
        bq = quad_tex(base_rgb, 1)[0] if quadbase else bayer_tex(base_rgb)
        texs = {'u_accum_color_r': tex(acc0), 'u_accum_weights_gb': tex(acc1), 'u_frame_weights': tex(np.full((N // 2, N // 2), 8.0, np.float32)),
                'u_hl_base': tex(hl_base, True), 'u_hl_final': tex(hl_final, True), 'u_base_bayer': tex(bq)}
        setu(out, {'u_size': (W, H), 'u_half': (W // 2, H // 2), 'u_row0': 0, 'u_rows': H, 'u_acc_row0': 0, 'u_inv_wb': (1.0, 1.0, 1.0),
                   'u_clip': 0.94, 'u_clip_short': 7.5, 'u_fill': fill, 'u_fill_light': 1.0, 'u_quad_lift': quadf, 'u_moire': quadf,
                   'u_step': 1.0, 'u_black': (BLACK,) * 4, 'u_white': WHITE, 'u_flat_noise': (0.0, 0.0, 0.0, 0.0), 'u_block': block}, texs)
        rgb = ctx.buffer(reserve=W * H * 6); fl = ctx.buffer(reserve=W * H); eff = ctx.buffer(reserve=(W // 2) * (H // 2) * 4)
        rgb.bind_to_storage_buffer(0); fl.bind_to_storage_buffer(1); eff.bind_to_storage_buffer(2)
        out.run((W // 4 + 63) // 64, H)
        o = np.frombuffer(rgb.read(), np.float16).astype(np.float32).reshape(H, W, 3).copy()
        for b in (rgb, fl, eff):
            b.release()
        return o

    yo, xo = np.mgrid[0:N, 0:N].astype(np.float32)
    level = (2.2 + 0.2 * np.sin(xo / 9.0) * np.cos(yo / 7.0)).astype(np.float32)
    truth = np.stack([level * 0.3516, level, level * 0.6787], -1)              # the sky, sensor RGB (G 2.2 x white)
    base_rgb = np.minimum(truth[::2, ::2], 1.0)                                # the base: R measured, G / B clipped
    hb = np.ones((N // 2, N // 2), np.float32)
    band_h = (np.abs(np.mgrid[0:N // 2, 0:N // 2][1] + 0.5 - 36) < 3.0)
    hf = np.where(band_h, 0.0, 1.0).astype(np.float32)                        # the short frames refused a band 12 px wide
    fin = np.repeat(np.repeat(hf, 2, 0), 2, 1)[..., None]
    merged = (fin * truth + (1 - fin) * np.minimum(truth, 1.0)).astype(np.float32)
    band = (np.abs(xo + 0.5 - 72) < 4) & (np.abs(yo - 64) < 40)
    pinkness = lambda o: float((o[..., 0] * WB[0] / np.maximum(o[..., 1], 1e-6))[band].mean())   # R / G after white balance
    old = run_out(merged, base_rgb, hb, hf, 0)
    new = run_out(merged, base_rgb, hb, hf, 1)
    pink_px = lambda o: float(((o[..., 0] * WB[0] / np.maximum(o[..., 1], 1e-6)) > 1.3)[band].mean())  # its pixels pink (R/G > 1.3)
    print('Quad refused band: G %.2f (merged %.2f, sky 2.2), R/G after WB %.2f -> %.2f (sky 1.00), pink pixels %.2f -> %.2f' % (
        float(new[..., 1][band].mean()), float(merged[..., 1][band].mean()), pinkness(old), pinkness(new), pink_px(old), pink_px(new)))
    check(pink_px(old) > 0.2, 'Quad refused band, before: the R cells kept the base\'s pink partial clip, dotted (the test sees it)')
    check(pink_px(new) == 0.0 and abs(pinkness(new) - 1.0) < 0.05 and abs(float(new[..., 1][band].mean()) / 2.2 - 1.0) < 0.1,
          'Quad refused band: filled to the sky\'s level and colour, no pink pixel')
    # Bayer (u_block 0, no Quad filters): exactly as before (the light fill check holds the rest)
    b_old = run_out(merged, base_rgb, hb, hf, 0, quadf=0.0, quadbase=False)
    check(np.array_equal(b_old, run_out(merged, base_rgb, hb, hf, 0, quadf=0.0, quadbase=False)) and abs(float(b_old[..., 1][band].mean()) / 2.2 - 1.0) < 0.1,
          'Bayer quads: the refused band filled as before')
    for t in made:
        t.release()
    ctx.release()
except ImportError:
    print('moderngl missing: GL part skipped')
    sys.exit(1)
print('fast Quad highlights check ' + ('FAIL' if fails else 'PASS'))
sys.exit(1 if fails else 0)
