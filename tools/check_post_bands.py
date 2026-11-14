#!/usr/bin/env python3
"""P668: a post pass drawn in bands of any height gives the same pixels, bit for bit.

GLProg.drawBlocks draws a fragment program over the target in bands (a viewport each, flushed and paced); since P668 a program
measured slow on the GPU (Xiaomi 12X, Adreno 650: a 256-row band of the tone's combine ~80 - 140 ms, the viewfinder waiting behind
two of them) gets narrower bands - multiples of 16 rows. That keeps the photo only if no post fragment shader depends on the
viewport: its pixel positions come from gl_FragCoord (window-absolute) and its textures, never from the viewport.

Every fragment shader of the post's asset folders that llvmpipe compiles is rendered with deterministic random textures and
uniforms over a frame of 200 rows in one draw (the 256-row band of before), then in bands of 16, 48 and 112 rows (each starting on
a 16-row boundary, the last one shorter) - the RGBA32F bytes must be equal. why/combine also with the photo's defines.
P683: and in bands of 8 rows - the tiles a picture under 8 MP was drawn in until P683 (PostPipeline then set TILE_ROWS 8), now
256 as for every other picture: the same pixels prove that change keeps the photo bit for bit.
"""
import os
import re
import sys
import zlib
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
DIRS = ['why', 'scamdn', 'scamhdr', 'hlrecovery', 'chromadn', 'scamlc', 'resolvedn', 'film', 'portrait', 'lensgauss',
        'RotateWatermark', 'ultrahdr', 'utils']
W, H = 96, 200
BANDS = [8, 16, 48, 112]
FAILURES = []
VS = '#version 310 es\nin vec4 vPosition;\nvoid main(){gl_Position=vPosition;}\n'


def check(cond, msg):
    if not cond:
        print('FAIL ' + msg)
        FAILURES.append(msg)


def expand(src, depth=0):
    out = []
    for line in src.replace('\r\n', '\n').split('\n'):
        if '#import' in line and '//' not in line.split('#import')[0]:
            name = line.split('#import', 1)[1].strip().split()[0]
            f = SH / 'utils' / ('import_' + name + '.glsl')
            if not f.exists():
                f = SH / 'utils' / (name + '.glsl')
            if not f.exists() or depth > 4:
                return None
            sub = expand(f.read_text(encoding='utf-8'), depth + 1)
            if sub is None:
                return None
            out.append(sub)
        else:
            out.append(line)
    return '\n'.join(out)


def set_define(src, name, value):
    return '\n'.join(('#define %s %s' % (name, value)) if re.match(r'\s*#\s*define\s+%s(\s|$)' % name, l) else l for l in src.split('\n'))


def with_header(src):
    """GLInterface.readProgram: the ES 3.10 header and highp int after the leading #extension lines (no #version of its own)."""
    if '#version' in src:
        return src
    lines = src.split('\n')
    k = 0
    while k < len(lines) and (lines[k].strip().startswith('#extension') or not lines[k].strip()):
        k += 1
    return '\n'.join(['#version 310 es'] + lines[:k] + ['precision highp int;'] + lines[k:])


def program(ctx, src):
    import moderngl
    try:
        return ctx.program(vertex_shader=VS, fragment_shader=src)
    except moderngl.Error:
        return None


def fill(ctx, prog, seed):
    import moderngl
    rng = np.random.default_rng(seed)
    textures, unit = [], 0
    for name in prog:
        m = prog[name]
        if not isinstance(m, moderngl.Uniform):
            continue
        gl = m.gl_type
        if gl in (0x8B5E,):  # sampler2D
            a = rng.uniform(0.0, 1.2, (H, W, 4)).astype('f4')
            t = ctx.texture((W, H), 4, a.tobytes(), dtype='f4')
            t.filter = (moderngl.LINEAR, moderngl.LINEAR)
            t.repeat_x = t.repeat_y = False
            t.use(unit)
            m.value = unit
            textures.append(t)
            unit += 1
        elif gl == 0x8B5F:  # sampler3D
            a = rng.uniform(0.0, 1.0, (8, 8, 8, 4)).astype('f4')
            t = ctx.texture3d((8, 8, 8), 4, a.tobytes(), dtype='f4')
            t.use(unit)
            m.value = unit
            textures.append(t)
            unit += 1
        elif gl in (0x8DD2, 0x8DCA):  # usampler2D / isampler2D
            a = rng.integers(0, 1000, (H, W, 4)).astype('u4' if gl == 0x8DD2 else 'i4')
            t = ctx.texture((W, H), 4, a.tobytes(), dtype='u4' if gl == 0x8DD2 else 'i4')
            t.filter = (moderngl.NEAREST, moderngl.NEAREST)
            t.use(unit)
            m.value = unit
            textures.append(t)
            unit += 1
        else:
            n = m.array_length * m.dimension
            if gl in (0x1404, 0x8B53, 0x8B54, 0x8B55, 0x1405, 0x8DC6, 0x8DC7, 0x8DC8, 0x8B56):  # ints / uints / bools
                vals = [int(v) for v in rng.integers(0, 3, n)]
            else:
                vals = [float(v) for v in rng.uniform(0.1, 1.0, n)]
            try:
                m.value = vals[0] if n == 1 else (tuple(vals) if m.array_length == 1 else
                                                  [tuple(vals[i * m.dimension:(i + 1) * m.dimension]) if m.dimension > 1 else vals[i]
                                                   for i in range(m.array_length)])
            except Exception:
                pass
    return textures


def render(ctx, prog, bands):
    out = ctx.texture((W, H), 4, dtype='f4')
    fb = ctx.framebuffer([out])
    fb.use()
    fb.clear(0.25, 0.5, 0.75, 1.0)
    quad = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], 'f4').tobytes())
    names = [n for n in prog if n == 'vPosition']
    vao = ctx.vertex_array(prog, [(quad, '2f', 'vPosition')] if names else [])
    y = 0
    while y < H:
        rows = min(H - y, bands)
        ctx.viewport = (0, y, W, rows)
        vao.render(mode=5 if names else 4, vertices=4 if names else 3)
        y += rows
    data = out.read()
    vao.release()
    quad.release()
    fb.release()
    out.release()
    return data


def variants():
    for d in DIRS:
        for f in sorted((SH / d).glob('*.glsl')):
            text = f.read_text(encoding='utf-8')
            if 'local_size' in text or 'LAYOUT' in text or 'gl_GlobalInvocationID' in text:
                continue  # compute
            if not re.search(r'\bout\s+(highp\s+|mediump\s+|lowp\s+)?[iu]?vec4\b|\bout\s+(highp\s+)?float\b|gl_FragColor', text):
                continue
            src = expand(text)
            if src is None:
                continue
            yield '%s/%s' % (d, f.stem), with_header(src)
    combine = expand((SH / 'why/combine.glsl').read_text(encoding='utf-8'))
    photo = combine
    for k, v in [('DITHER_TPDF', '1'), ('HL_LOCAL', '1'), ('HL_LOCAL_SMOOTH', '1'), ('RIM_SMOOTH', '1'), ('EDGE_HOLD', '1'),
                 ('WHY_BASE', '1'), ('WHY_VIBRANCE', '1'), ('HL_LANDING', '1')]:
        photo = set_define(photo, k, v)
    yield 'why/combine (photo defines)', with_header(photo)


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: P668 band check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    tried = equal = skipped = 0
    for name, src in variants():
        prog = program(ctx, src)
        if prog is None:
            skipped += 1
            continue
        tried += 1
        textures = fill(ctx, prog, zlib.crc32(name.encode()))
        whole = render(ctx, prog, 256)
        same = True
        for b in BANDS:
            got = render(ctx, prog, b)
            if got != whole:
                a = np.frombuffer(whole, 'u4').reshape(H, W, 4)
                g = np.frombuffer(got, 'u4').reshape(H, W, 4)
                rows = sorted(set(np.nonzero((a != g).any(axis=(1, 2)))[0].tolist()))
                check(False, '%s: bands of %d rows differ from one draw in rows %s' % (name, b, rows[:12]))
                same = False
        equal += same
        for t in textures:
            t.release()
        prog.release()
    print('     %d post fragment programs drawn in bands of %s rows and in one draw: %d bit for bit equal (%d not compiled by llvmpipe)'
          % (tried, BANDS, equal, skipped))
    check(tried >= 20, 'at least 20 post programs compiled (%d)' % tried)
    print('ALL OK' if not FAILURES else 'FAILED: %d' % len(FAILURES))
    return 1 if FAILURES else 0


if __name__ == '__main__':
    sys.exit(main())
