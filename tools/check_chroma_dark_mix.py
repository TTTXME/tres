#!/usr/bin/env python3
"""P417: preserve the dark/day chroma noise tiers on the shipped CBF shader."""
from pathlib import Path
import re
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
if '--shaders' in sys.argv:
    SH = Path(sys.argv[sys.argv.index('--shaders') + 1])
REPORT = '--report' in sys.argv

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
KY = np.array([0.2126, 0.7152, 0.0721996])
KU = np.array([-0.162450244, -0.546494309, 0.708944715])
KV = np.array([0.999996748, -0.908302439, -0.091693333])
W, H = 96, 72



def source(name):
    out = ['#version 430']
    for line in (SH / name).read_text(encoding='utf-8').splitlines():
        m = re.match(r'\s*#import\s+(\S+)', line)
        if m:
            out.append((SH / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8'))
            continue
        out.append(line)
    return '\n'.join(out)


def texture(a):
    a = np.ascontiguousarray(a, np.float32)
    t = ctx.texture((a.shape[1], a.shape[0]), a.shape[2], a.tobytes(), dtype='f4')
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    t.repeat_x = t.repeat_y = False
    return t


def draw(textures, uniforms):
    p = ctx.program(vertex_shader=VS, fragment_shader=source('scamdn/cbf.glsl'))
    out = ctx.texture((W, H), 4, dtype='f4')
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0, W, H)
    for unit, (k, t) in enumerate(textures.items()):
        t.use(unit)
        if k in p:
            p[k].value = unit
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    ctx.vertex_array(p, []).render(vertices=3)
    ctx.finish()
    data = np.frombuffer(out.read(), np.float32).astype(np.float64).reshape(H, W, 4)[..., :3]
    fbo.release(); out.release(); p.release()
    return data


def check_dark_mix(fails):
    rng = np.random.default_rng(417)
    rgb = np.empty((H, W, 3))
    rgb[:, :W // 2] = 0.001                                    # a deep shadow
    rgb[:, W // 2:] = 0.05                                     # a mid-tone
    rgb *= 1 + 0.3 * rng.standard_normal((H, W, 3))
    yuv = np.stack([rgb @ KY, rgb @ KU, rgb @ KV], -1)
    t = texture(yuv)
    tex = {'InputBuffer': t, 'DeltaUV': t, 'Orig': t, 'StrMap': t, 'Grain': t}
    noise = (2e-4, 4e-8)
    base = {'strideU': 1, 'filterU': 1, 'useDeltaU': 0, 'useMapU': 0, 'modeU': 0, 'uvsU': (1.0, 1.0), 'thrU': 0.0}
    full = draw(tex, dict(base, noiseU=noise))
    day = draw(tex, dict(base, noiseU=(noise[0] * 0.25, noise[1] * 0.25)))
    if not np.array_equal(full, draw(tex, dict(base, noiseU=noise, darkMixU=(0.0, 0.0, 0.0)))):
        fails.append('dark tier: darkMixU unset changes the pass')
    mixed = draw(tex, dict(base, noiseU=noise, darkMixU=(0.25, 0.002, 0.008)))
    dark, mid = (slice(2, H - 2), slice(2, W // 2 - 2)), (slice(2, H - 2), slice(W // 2 + 2, W - 2))
    if not np.allclose(mixed[dark], full[dark], atol=1e-7):
        fails.append('dark tier: the deep shadow is not filtered at the pass\'s (dark tier\'s) strength')
    dm = np.abs(mixed[mid] - day[mid]).max()
    if dm > 1e-6 * 0.05:
        fails.append('dark tier: the mid-tone is not filtered at the day tier\'s strength (max diff %.2e)' % dm)
    if np.array_equal(full[mid], day[mid]):
        fails.append('dark tier: the test patch does not tell the two strengths apart')
    if REPORT:
        print('dark tier: shadow as the pass, mid-tone as the day tier (max diff %.1e)' % dm)
    t.release()


def main():
    fails = []
    check_dark_mix(fails)
    if fails:
        print('FAIL\n' + '\n'.join(fails))
        sys.exit(1)
    print('check_chroma_dark_mix: OK')


main()
