"""P607: the fast route's hole fill (scam-fast-gpu.h kFastOutput fill, P497) on a clipped light (u_fill_light).

Runs the shipped kFastOutput compute shader whole (GL 4.3) on synthetic merges at the sensor grid:
  - a street lamp: a steep light whose short frames carry it up to their clip (15.6), the base clipped on its disc; the short
    frames refused (the final mask cut) on the disc's rim (a 1 - 2 px registration of the steep falloff) and partly inside it,
    accepted only at its centre (the owner's lamp, 2026-11-10). Before the fix (u_fill_light 0) the fill's rays stamp the peak into
    the rim along eight lines: spokes and holes. With it the merge's own radial falloff is kept: monotonic, no hole;
  - the same rim holding the base's partial clip colour (cyan: G, B at white, R lower): recoloured to the light at its own level;
  - a lamp with a refused speck inside its core (the base's white amid the short frames' light): still filled (no dark hole);
  - the wire it was made for: a refused band (the base's pink white) inside a recovered sky plateau, the real wire 16 px off:
    filled exactly as before;
  - a strip between the frame's edge and a wire (the fallback through the wire): filled exactly as before;
  - no short frames (u_fill 0): byte for byte as before."""
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/cpp/scam-fast-gpu.h').read_text(encoding='utf-8')
MERGE = (ROOT / 'app/src/main/cpp/scam-fast-merge.h').read_text(encoding='utf-8')
HYB = (ROOT / 'app/src/main/cpp/scam-hybrid.h').read_text(encoding='utf-8')
fails = []


def check(ok, what):
    print(('PASS ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)


m = re.search(r'static const char\* kFastOutput=R"FM\((.*?)\)FM";', SRC, re.S)
check(m is not None, 'kFastOutput in scam-fast-gpu.h')
SHADER = m.group(1).replace('#version 310 es', '#version 430') if m else ''
check(MERGE.count('g.u1f(A::pOutput,"u_fill_light",std::clamp(tune.fastLightFill,0.f,1.f));') == 2,
      'u_fill_light set in both output passes (tuning fastLightFill)')
check('float fastLightFill=1.f;' in HYB and 'set("fastLightFill",&t.fastLightFill)' in HYB, 'fastLightFill: default 1, read from the tuning')

N = 128            # OUT = sensor grid, HALF = N / 2
yy, xx = np.mgrid[0:N, 0:N].astype(np.float32) + 0.5


def half(a):
    return a.reshape(N // 2, 2, N // 2, 2).mean(axis=(1, 3)).astype(np.float32)


try:
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    prog = ctx.compute_shader(SHADER)

    def run(merged, base_true, hl_base, hl_final, fill=24, light=1.0):
        """merged: N x N x 3 (the accumulators' value), base_true: N x N (the base frame's level before its clip at 1),
        hl_base / hl_final: HALF masks. -> N x N x 3 output (half floats decoded)"""
        H = W = N
        acc0 = np.concatenate([merged, np.ones((H, W, 1), np.float32)], -1)
        acc1 = np.concatenate([np.ones((H, W, 2), np.float32), np.zeros((H, W, 1), np.float32), np.ones((H, W, 1), np.float32)], -1)
        quads = np.repeat(np.minimum(half(base_true), 1.0)[..., None], 4, -1)
        tex = []

        def t(a, comps, linear=False):
            o = ctx.texture((a.shape[1], a.shape[0]), comps, np.ascontiguousarray(a, np.float32).tobytes(), dtype='f4')
            f = moderngl.LINEAR if linear else moderngl.NEAREST
            o.filter = (f, f)
            o.repeat_x = o.repeat_y = False
            tex.append(o)
            return o
        units = {'u_accum_color_r': t(acc0, 4), 'u_accum_weights_gb': t(acc1, 4), 'u_frame_weights': t(np.zeros((N // 2, N // 2)), 1),
                 'u_hl_base': t(hl_base, 1, True), 'u_hl_final': t(hl_final, 1, True), 'u_base_bayer': t(quads, 4)}
        for i, (k, o) in enumerate(units.items()):
            o.use(i)
            if k in prog:
                prog[k].value = i
        vals = {'u_size': (W, H), 'u_half': (W // 2, H // 2), 'u_row0': 0, 'u_rows': H, 'u_acc_row0': 0, 'u_inv_wb': (1.0, 1.0, 1.0),
                'u_clip': 0.94, 'u_clip_short': 15.0 if fill > 0 else 0.0, 'u_fill': fill, 'u_fill_light': light, 'u_quad_lift': 0.0,
                'u_moire': 0.0, 'u_step': 1.0, 'u_black': (0.0, 0.0, 0.0, 0.0), 'u_white': 1.0, 'u_flat_noise': (0.0, 0.0, 0.0, 0.0)}
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        rgb = ctx.buffer(reserve=W * H * 6)
        flags = ctx.buffer(reserve=W * H)
        eff = ctx.buffer(reserve=(W // 2) * (H // 2) * 4)
        rgb.bind_to_storage_buffer(0)
        flags.bind_to_storage_buffer(1)
        eff.bind_to_storage_buffer(2)
        prog.run((W // 4 + 63) // 64, H)
        out = np.frombuffer(rgb.read(), np.float16).astype(np.float32).reshape(H, W, 3).copy()
        for o in tex + [rgb, flags, eff]:
            o.release()
        return out

    def blur(a, sigma=1.5):
        """separable gaussian (numpy only: the CI venv has no scipy)"""
        R = 4; k = np.exp(-0.5 * (np.arange(-R, R + 1) / sigma) ** 2); k /= k.sum()
        f = lambda v: np.convolve(np.pad(v, R, mode='edge'), k, 'valid')
        return np.apply_along_axis(f, 0, np.apply_along_axis(f, 1, a)).astype(np.float32)

    def soft_disc(r, r0, w=1.0):
        return np.clip((r0 - r) / w + 0.5, 0.0, 1.0).astype(np.float32)

    # ---- the street lamp (the owner's, 2026-11-10): L(r) steep, short frames to 15.6, base clipped on L >= 1
    r = np.hypot(xx - 64, yy - 64)
    L = (40.0 * np.exp(-(r / 6.0) ** 2) + 0.3 * np.exp(-(r / 25.0) ** 2)).astype(np.float32)
    short = np.minimum(L, 15.6)
    rh = np.hypot(np.mgrid[0:64, 0:64][1] + 0.5 - 32, np.mgrid[0:64, 0:64][0] + 0.5 - 32) * 2
    r_clip = 6.0 * np.sqrt(np.log(40.0 / 0.95))
    hl_base = soft_disc(rh, r_clip + 4, 3.0)                       # the clip mask, dilated and blurred (sh060 / sh016)
    valid = np.where(rh < 8, 1.0, 0.5).astype(np.float32)          # partly refused inside (the min over three frames)
    ang = np.arctan2(np.mgrid[0:64, 0:64][0] + 0.5 - 32, np.mgrid[0:64, 0:64][1] + 0.5 - 32)
    valid[(rh > r_clip - 4) & (np.cos(ang) > -0.3)] = 0.0         # the rim crescent the misregistered frames refused
    hl_final = (hl_base * blur(valid)).astype(np.float32)          # sh066 / sh042 smooth the validity
    fin = np.repeat(np.repeat(hl_final, 2, 0), 2, 1)
    merged = (fin * short + (1 - fin) * np.minimum(L, 1.0))[..., None].repeat(3, -1).astype(np.float32)
    old = run(merged, L, hl_base, hl_final, light=0.0)
    new = run(merged, L, hl_base, hl_final, light=1.0)
    core = (L >= 1.0) & (r > 1.5)
    def holes(o):
        """pixels in the clipped core under 0.6 x the median of their 5 x 5 neighbourhood (a dark blotch beside brighter light)"""
        y = o.max(-1)
        pad = np.pad(y, 2, mode='edge')
        win = np.stack([pad[dy:dy + N, dx:dx + N] for dy in range(5) for dx in range(5)], 0)
        return int(((y < 0.6 * np.median(win, 0)) & core).sum())
    def spokes(o):
        """the largest rise of the output above the merged input in the core (the stamped peak)"""
        return float((o.max(-1) - merged.max(-1))[core].max())
    def edge(o):
        """the hardest outward step at the light's rim: the largest drop over 1 px along 64 radii, r from 2 px to the clip + 3"""
        y = o.max(-1); worst = 0.0
        for a in np.linspace(0, 2 * np.pi, 64, endpoint=False):
            rr = np.arange(2.0, r_clip + 3.0, 1.0)
            v = y[np.clip(np.floor(64 + rr * np.sin(a)).astype(int), 0, N - 1), np.clip(np.floor(64 + rr * np.cos(a)).astype(int), 0, N - 1)]
            worst = max(worst, float(np.max(v[:-1] - v[1:])))
        return worst
    print('lamp: holes %d -> %d (input %d), stamped rise %.2f -> %.2f, the rim\'s hardest 1 px drop %.2f -> %.2f (input %.2f)' %
          (holes(old), holes(new), holes(merged), spokes(old), spokes(new), edge(old), edge(new), edge(merged)))
    check(spokes(old) > 5.0 and edge(old) > 1.5 * edge(merged), 'lamp, as before: the fill stamps the peak into the rim, a hard step (the test sees the artifact)')
    check(spokes(new) < 1.0 and holes(new) <= holes(merged) and edge(new) <= 1.05 * edge(merged),
          'lamp: the merge\'s own falloff kept (no stamped peak, no new holes, no harder step than the input)')

    # ---- the lamp's refused rim holding the base's partial clip colour (G and B at the base's white, R measured lower: cyan): the
    # light's colour at the pixel's own level (no cyan patch, no level stamped)
    merged_c = merged.copy()
    rimc = (fin < 0.5) & (L >= 1.0) & (r > 4)
    merged_c[..., 0] = np.where(rimc, 0.6 * merged_c[..., 0], merged_c[..., 0])
    oc = run(merged_c, L, hl_base, hl_final, light=1.0)
    sat = lambda a: (a.max(-1) - a.min(-1)) / np.maximum(a.max(-1), 1e-6)
    filled = rimc & (np.abs(oc - merged_c).max(-1) > 1e-3)
    print('rim with a cyan clip: %d px recoloured, saturation %.3f -> %.3f, level change max %.4f' % (
        int(filled.sum()), float(sat(merged_c)[filled].mean()) if filled.any() else 0.0, float(sat(oc)[filled].mean()) if filled.any() else 0.0,
        float(np.abs(oc.max(-1) - merged_c.max(-1))[filled].max()) if filled.any() else 0.0))
    check(filled.sum() > 20 and sat(oc)[filled].mean() < 0.1 and np.abs(oc.max(-1) - merged_c.max(-1))[filled].max() < 0.05 * merged_c.max(-1)[filled].max(),
          'lamp rim with the base\'s cyan clip: the light\'s colour at its own level (no cyan patch, no stamped level)')

    # ---- a refused speck inside the lamp's core: the base's white amid the short frames' light (filled, no dark hole)
    valid2 = np.where(rh < r_clip - 2, 1.0, 1.0).astype(np.float32)
    speck = (np.abs(np.mgrid[0:64, 0:64][1] + 0.5 - 35) < 1.5) & (np.abs(np.mgrid[0:64, 0:64][0] + 0.5 - 32) < 1.5)
    valid2[speck] = 0.0
    hl_final2 = (hl_base * valid2).astype(np.float32)
    fin2 = np.repeat(np.repeat(hl_final2, 2, 0), 2, 1)
    merged2 = (fin2 * short + (1 - fin2) * np.minimum(L, 1.0))[..., None].repeat(3, -1).astype(np.float32)
    o2 = run(merged2, L, hl_base, hl_final2, light=1.0)
    sp = np.repeat(np.repeat(speck, 2, 0), 2, 1)
    lo_in, lo_out = float(merged2.max(-1)[sp].min()), float(o2.max(-1)[sp].min())
    print('speck: its lowest %.2f -> %.2f (the light around %.2f)' % (lo_in, lo_out, float(short[sp].min())))
    check(lo_out > 4.0 * lo_in, 'lamp with a refused speck: the speck filled from the light around (no dark hole)')

    # ---- the near wire's refused copy in a recovered sky (P497): filled as before
    sky = np.full((N, N), 2.4, np.float32)
    wire = np.abs(xx - 40) < 1.5                                   # the real wire (the base dark, unclipped)
    base_true = np.where(wire, 0.3, sky)
    copy = np.abs(xx - 72) < 3.0                                   # its copy in the short frames, 32 px off: refused
    hb = np.ones((64, 64), np.float32)
    hf = np.where(np.abs(np.mgrid[0:64, 0:64][1] + 0.5 - 36) < 2.0, 0.0, 1.0).astype(np.float32)
    finw = np.repeat(np.repeat(hf, 2, 0), 2, 1)
    pink = np.stack([np.full((N, N), 0.8), np.full((N, N), 1.0), np.full((N, N), 0.85)], -1).astype(np.float32)
    mw = np.where(wire[..., None], 0.3, finw[..., None] * 2.4 + (1 - finw[..., None]) * pink).astype(np.float32)
    ow = run(mw, base_true, hb, hf, light=0.0)
    nw = run(mw, base_true, hb, hf, light=1.0)
    band = copy & (np.abs(yy - 64) < 40)
    print('wire copy: the band %.2f -> %.2f before, %.2f now (sky 2.4)' % (float(mw.max(-1)[band].mean()), float(ow.max(-1)[band].mean()),
                                                                     float(nw.max(-1)[band].mean())))
    check(float(ow.max(-1)[band].mean()) > 2.0, 'wire copy, as before: the band filled to the sky')
    check(np.array_equal(ow, nw), 'wire copy: filled exactly as before')

    # ---- a strip between the frame's edge and a wire, refused (the fallback through the wire): filled as before
    wire2 = np.abs(xx - 12) < 1.5
    base2 = np.where(wire2, 0.3, sky)
    hf2 = np.where(np.mgrid[0:64, 0:64][1] < 5, 0.0, 1.0).astype(np.float32)
    fin3 = np.repeat(np.repeat(hf2, 2, 0), 2, 1)
    ms = np.where(wire2[..., None], 0.3, fin3[..., None] * 2.4 + (1 - fin3[..., None]) * pink).astype(np.float32)
    os_, ns = run(ms, base2, hb, hf2, light=0.0), run(ms, base2, hb, hf2, light=1.0)
    strip = (xx < 9) & (np.abs(yy - 64) < 40)
    print('edge strip: %.2f -> %.2f before, %.2f now' % (float(ms.max(-1)[strip].mean()), float(os_.max(-1)[strip].mean()), float(ns.max(-1)[strip].mean())))
    check(float(os_.max(-1)[strip].mean()) > 1.5 and np.array_equal(os_, ns), 'edge strip: filled exactly as before')

    # ---- without short frames the pass is as before
    check(np.array_equal(run(merged, L, hl_base, hl_final, fill=0, light=0.0), run(merged, L, hl_base, hl_final, fill=0, light=1.0)),
          'no short frames (u_fill 0): byte for byte as before')
    ctx.release()
except ImportError:
    print('moderngl missing: GL part skipped')
    sys.exit(1)
print('fast light fill check ' + ('FAIL' if fails else 'PASS'))
sys.exit(1 if fails else 0)
