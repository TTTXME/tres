#!/usr/bin/env python3
"""P671: the clip band computed only in the tiles near a flagged pixel gives the same pixels, bit for bit.

ScamRgb's clip band (scamhdr/clipmask, then scamhdr/clipband) changes a pixel only when a flagged pixel (flag bits 0 - 3) lies
within the windows' reach (max(radius, zone, glow radius) px per axis). ClipBandTiles marks the 32 x 32 tiles holding a flagged
pixel and widens them by the reach in whole tiles; both shaders (BAND_TILES 1) return at once outside those tiles with the value
they computed before. This draws both passes as the app does (RGBA16F scamrgb output in, RGBA16F out; the R8 mask between them)
with and without the tiles - with the mask (BAND_MASK) and with the 2-D taps, at 1x and on the Sabre 2x grid (radius 6, zone 4,
glow 12), the glow and the band on and off, P58's unflagged-hi rule on and off - over frames whose flags are random sparse
pixels and blobs with every flag byte (at the tiles' and the frame's borders too), and the merge output of a burst when given
(tools/check_band_tiles.py <merge.f32> <W> <H>: RGB float32 then the flag bytes at the end). The tile map is ClipBandTiles'
rule written again here (the Java class has its unit test against a brute-force window search). Also: a frame with no flagged
pixel has no tile at all (the app skips both passes: the scamrgb output stays, which is what clipband would copy).
"""
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
TILE = 32
FAILURES = []


def check(cond, msg):
    if not cond:
        print('FAIL ' + msg)
        FAILURES.append(msg)


def set_define(src, name, value):
    import re
    return '\n'.join(('#define %s %s' % (name, value)) if re.match(r'\s*#\s*define\s+%s(\s|$)' % name, l) else l for l in src.split('\n'))


def tile_map(flags, reach):
    """ClipBandTiles.scan + activeMap: tiles with a flag (bits 0 - 3) widened by ceil(reach / TILE) (at least 1) tiles; None when
    no tile is flagged."""
    h, w = flags.shape
    ty, tx = (h + TILE - 1) // TILE, (w + TILE - 1) // TILE
    pad = np.zeros((ty * TILE, tx * TILE), np.uint8)
    pad[:h, :w] = (flags & 15) != 0
    flagged = pad.reshape(ty, TILE, tx, TILE).max(axis=(1, 3)) > 0
    if not flagged.any():
        return None
    g = max(1, (max(0, reach) + TILE - 1) // TILE)
    out = np.zeros_like(flagged)
    for y, x in zip(*np.nonzero(flagged)):
        out[max(0, y - g):y + g + 1, max(0, x - g):x + g + 1] = True
    return (out * 255).astype(np.uint8)


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: P671 band tiles check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
    progs = {}

    def prog(name, **defs):
        key = (name, tuple(sorted(defs.items())))
        if key not in progs:
            src = (SH / name).read_text(encoding='utf-8')
            for k, v in defs.items():
                src = set_define(src, k, v)
            progs[key] = ctx.program(vertex_shader=vs, fragment_shader='#version 430\n' + src)
        return progs[key]

    def tex(a, dtype, comps):
        a = np.ascontiguousarray(a)
        t = ctx.texture((a.shape[1], a.shape[0]), comps, a.tobytes(), dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t.repeat_x = t.repeat_y = False
        return t

    def draw(p, size, dtype, textures, uniforms):
        o = ctx.texture(size, 4, dtype=dtype)
        o.filter = (moderngl.NEAREST, moderngl.NEAREST)
        fbo = ctx.framebuffer([o])
        fbo.use()
        ctx.viewport = (0, 0) + size
        for unit, (k, t) in enumerate(textures.items()):
            t.use(unit)
            if k in p:
                p[k].value = unit
        for k, v in uniforms.items():
            if k in p:
                p[k].value = v
        ctx.vertex_array(p, []).render(vertices=3)
        ctx.finish()
        fbo.release()
        return o

    def band(rgba, flags, s, glow, strength, unflagged, mask, tiles):
        h, w = flags.shape
        inp = tex(rgba.astype(np.float16), 'f2', 4)
        ft = tex(flags, 'f1', 1)
        r, z, gr = 3 * s, 2 * s, (6 * s if glow > 0 else 0)
        defs = {}
        texs = {}
        if tiles is not None:
            defs['BAND_TILES'] = 1
            texs['BandTiles'] = tex(tiles, 'f1', 1)
        u = {'radiusU': r, 'zoneU': z, 'clipHiUnflaggedU': unflagged, 'glowRadiusU': gr, 'bandTileU': TILE}
        t0 = time.time()
        if mask:
            m = draw(prog('scamhdr/clipmask.glsl', **defs), (w, h), 'f1', dict(texs, ClipFlags=ft), u)
            texs['BandMask'] = m
            defs['BAND_MASK'] = 1
        out = draw(prog('scamhdr/clipband.glsl', **defs), (w, h), 'f2', dict(texs, InputBuffer=inp, ClipFlags=ft),
                   dict(u, strengthU=strength, glowU=glow))
        dt = time.time() - t0
        data = out.read()
        for t in list(texs.values()) + [inp, ft, out]:
            t.release()
        return data, dt

    def compare(name, rgba, flags):
        h, w = flags.shape
        cases = 0
        for s in (1, 2):
            for glow, strength in ((1.0, 1.0), (0.0, 1.0), (1.0, 0.0)):
                for unflagged in (0, 1):
                    for mask in (True, False):
                        reach = max(3 * s, 2 * s, 6 * s if glow > 0 else 0)
                        tiles = tile_map(flags, reach)
                        if tiles is None:
                            check((flags & 15).max() == 0, '%s: no tile although a pixel is flagged' % name)
                            continue
                        timed = s == 1 and glow > 0 and strength > 0 and unflagged == 0 and mask
                        for _ in range(2 if timed else 1):  # timed: the second draw (the programs compiled)
                            whole, t_whole = band(rgba, flags, s, glow, strength, unflagged, mask, None)
                            tiled, t_tiled = band(rgba, flags, s, glow, strength, unflagged, mask, tiles)
                        cases += 1
                        if whole != tiled:
                            a = np.frombuffer(whole, np.uint16).reshape(h, w, 4)
                            b = np.frombuffer(tiled, np.uint16).reshape(h, w, 4)
                            ys, xs = np.nonzero((a != b).any(-1))
                            check(False, '%s s=%d glow=%.0f band=%.0f unflagged=%d mask=%s: %d pixels differ, first at %s'
                                  % (name, s, glow, strength, unflagged, mask, ys.size, (int(xs[0]), int(ys[0]))))
                        if timed:
                            print('     %s %dx%d: tiles %.1f%%, the band %.0f ms in every pixel, %.0f ms in the tiles (llvmpipe, mask + band)'
                                  % (name, w, h, 100.0 * (tiles > 0).mean(), 1e3 * t_whole, 1e3 * t_tiled))
        return cases

    rng = np.random.default_rng(671)
    tried = 0
    # synthetic frames: odd sizes (partial tiles), scattered flagged pixels with every byte value, blobs, flags on the borders
    for (w, h) in ((203, 141), (320, 96)):
        rgba = np.concatenate([rng.uniform(0.0, 1.6, (h, w, 3)), np.ones((h, w, 1))], -1).astype(np.float32)
        rgba[rng.random((h, w)) < 0.05] *= 4.0
        flags = np.zeros((h, w), np.uint8)
        pts = rng.integers(0, [h, w], (40, 2))
        flags[pts[:, 0], pts[:, 1]] = rng.integers(1, 256, 40)
        for (y, x) in ((0, 0), (h - 1, w - 1), (0, w - 1), (h - 1, 0), (31, 31), (32, 32), (63, 64), (h // 2, w - 2)):
            flags[y, x] = rng.integers(1, 256)
        yy, xx = np.mgrid[0:h, 0:w]
        blob = (yy - h * 0.6) ** 2 + (xx - w * 0.3) ** 2 < 81
        flags[blob] = rng.integers(0, 256, blob.sum())
        rgba[blob, :3] = rng.uniform(2.0, 4.0, (blob.sum(), 3))
        tried += compare('synthetic %dx%d' % (w, h), rgba, flags)
        # only flag bits 4 - 7 (no band at all): no tile
        only_high = (flags & 0xF0)
        check(tile_map(only_high, 12) is None, 'synthetic %dx%d: bits 4 - 7 alone make a tile' % (w, h))
    if len(sys.argv) >= 4:
        f32, W, H = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
        raw = np.fromfile(f32, np.uint8)
        rgb = np.frombuffer(raw[:W * H * 12].tobytes(), np.float32).reshape(H, W, 3)
        flags = raw[raw.size - W * H:].reshape(H, W).copy()
        rgba = np.concatenate([np.maximum(rgb, 0) * 1.8, np.ones((H, W, 1), np.float32)], -1)
        print('     %s: %d flagged pixels (bits 0 - 3)' % (Path(f32).name, int(((flags & 15) != 0).sum())))
        tried += compare(Path(f32).name, rgba, flags)
        tried += compare(Path(f32).name + ' (no flags)', rgba, np.zeros_like(flags))
    print('     %d clip band cases drawn in every pixel and in the flagged tiles only: %s'
          % (tried, 'bit for bit equal' if not FAILURES else 'DIFFERENT'))
    check(tried >= 40, 'at least 40 cases drawn (%d)' % tried)
    print('ALL OK' if not FAILURES else 'FAILED: %d' % len(FAILURES))
    return 1 if FAILURES else 0


if __name__ == '__main__':
    sys.exit(main())
