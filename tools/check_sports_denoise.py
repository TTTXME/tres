#!/usr/bin/env python3
"""Actual Java policy and actual GL filters: noise variance, hard edges, coverage and bright-path identity."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
PKG = 'com.particlesdevs.photoncamera.processing.opengl.postpipeline'
JAVA = ROOT / 'app/src/main/java' / PKG.replace('.', '/')
SHADERS = ROOT / 'app/src/main/assets/shaders'
VS = '#version 310 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
PROGRAMS = {}


def java_tools():
    suffix = '.exe' if sys.platform == 'win32' else ''
    names = ('javac' + suffix, 'java' + suffix)
    home = os.environ.get('JAVA_HOME')
    if home:
        tools = tuple(Path(home) / 'bin' / name for name in names)
        if all(tool.is_file() for tool in tools):
            return tuple(map(str, tools))
    tools = tuple(shutil.which(name) for name in names)
    if all(tools):
        return tools
    local = ROOT.parent / 'local-tools/jdk17/jdk-17.0.20.1+1/bin'
    tools = tuple(local / name for name in names)
    if all(tool.is_file() for tool in tools):
        return tuple(map(str, tools))
    raise RuntimeError('JDK required: set JAVA_HOME or put javac and java on PATH')


def texture(a, dtype='f4', linear=False):
    a = np.ascontiguousarray(a, np.uint8 if dtype == 'u1' else np.float16 if dtype == 'f2' else np.float32)
    t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype=dtype)
    t.repeat_x = t.repeat_y = False
    t.filter = (moderngl.LINEAR, moderngl.LINEAR) if linear else (moderngl.NEAREST, moderngl.NEAREST)
    return t


def draw(name, bindings, uniforms, size, channels=4, mode=None, motion=False, dtype='f4'):
    key = (name, mode, motion)
    if key not in PROGRAMS:
        source = (SHADERS / name).read_text(encoding='utf-8')
        if mode is not None:
            source = source.replace('#define MODE 0', '#define MODE %d' % mode, 1)
        if motion:
            source = source.replace('#define MOTION 0', '#define MOTION 1', 1)
        PROGRAMS[key] = ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + source)
    p = PROGRAMS[key]
    out = ctx.texture(size, channels, dtype=dtype)
    out.repeat_x = out.repeat_y = False
    out.filter = (moderngl.LINEAR, moderngl.LINEAR)
    fb = ctx.framebuffer([out]); vao = ctx.vertex_array(p, [])
    for unit, (key, t) in enumerate(bindings.items()):
        if key in p:
            t.use(unit); p[key].value = unit
    for key, value in uniforms.items():
        if key in p:
            p[key].value = value
    fb.use(); ctx.viewport = (0, 0, *size); vao.render(vertices=3)
    vao.release(); fb.release()
    return out


def read(t):
    return np.frombuffer(t.read(), np.float32).reshape(t.height, t.width, t.components).copy()


def luma(noisy, policy, rejected, with_map=True, strength_map=None):
    h, w = noisy.shape
    src = texture(noisy)
    m = texture(strength_map if strength_map is not None else
                np.dstack([np.full_like(noisy, 4), np.full_like(noisy, rejected)]))
    units = {k: src for k in ('InputBuffer', 'Recon', 'Yin', 'Base', 'LowFreq', 'Chroma')}; units['StrMap'] = m
    strength, revert, threshold = policy['luma'][0]
    values = dict(strideU=1, filterU=1, noiseU=(0., .00050625 * strength ** 2), thrU=threshold * 16,
                  stageU=1, rfU=revert, modeU=0, useYinU=0, useMapU=int(with_map),
                  mapInvU=(1 / w, 1 / h), sharpU=policy['sharp'])
    out = draw('scamdn/lbf.glsl', units, values, (w, h))
    a = read(out)[..., 0]
    for t in (src, m, out): t.release()
    return a


def multiscale(rgb, policy, frames):
    h, w, _ = rgb.shape; owned = []
    src = texture(np.dstack([rgb, np.ones((h, w))]), linear=True); owned.append(src)
    eff = texture(np.full((h, w), 64 if frames else 0, np.uint8), 'u1'); owned.append(eff)
    identity = (1., 0., 0., 0., 1., 0., 0., 0., 1.)
    lab = [None]; size = (w, h)
    for level in (1, 2, 3):
        size = ((size[0] + 1) // 2, (size[1] + 1) // 2)
        lab.append(draw('why/msdn.glsl', {'InputBuffer': src if level == 1 else lab[-1]},
                        {'toRenderU': identity, 'neutralU': (1., 1., 1.)}, size, mode=0 if level == 1 else 1, dtype='f2'))
        owned.append(lab[-1])
    th = policy['ms']
    rebuilt = draw('why/msdn.glsl', {'InputBuffer': lab[3]}, {'chromaThU': th[3][1], 'shadowU': 1.5}, lab[3].size, mode=2, dtype='f2')
    owned.append(rebuilt)
    for level in (2, 1, 0):
        units = {'InputBuffer': src if level == 0 else lab[level], 'Coarse': lab[level + 1], 'CoarseRecon': rebuilt, 'EffMap': eff}
        uniforms = {'toRenderU': identity, 'fromRenderU': identity, 'neutralU': (1., 1., 1.), 'lumaThU': th[level][0],
                    'chromaThU': th[level][1], 'shadowU': 1.5, 'levelU': level,
                    'effScaleU': (frames or 1) / 64, 'motionU': tuple(policy['motion'])}
        rebuilt = draw('why/msdn.glsl', units, uniforms, (w, h) if level == 0 else lab[level].size,
                       mode=4 if level == 0 else 3, motion=bool(frames), dtype='f4' if level == 0 else 'f2')
        owned.append(rebuilt)
    result = read(rebuilt)[..., :3]
    for t in owned: t.release()
    return result


def profile(a):
    return np.mean(a[12:-12], axis=0)


def edge_metrics(a):
    p = profile(a)
    lo, hi = np.mean(p[8:40]), np.mean(p[-40:-8]); z = (p - lo) / (hi - lo)
    def cross(t):
        at = np.flatnonzero(z >= t)[0]
        return float(at - 1 + (t - z[at - 1]) / (z[at] - z[at - 1]))
    return {'contrast': float(hi - lo), 'width10_90': cross(.9) - cross(.1), 'g50': cross(.5)}


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    javac, java = java_tools()
    with tempfile.TemporaryDirectory(prefix='sports-policy-') as folder:
        subprocess.run([javac, '-d', folder, str(JAVA / 'ScamDenoiseTables.java'),
                        str(JAVA / 'SportsDenoisePolicy.java'), str(ROOT / 'tools/java/SportsDenoiseReplay.java')], check=True)
        def policy(snr, policy_snr, sports=True):
            s = subprocess.check_output([java, '-cp', folder, PKG + '.SportsDenoiseReplay',
                                         str(snr), str(policy_snr), str(sports).lower()], text=True)
            return json.loads(s)
        old, low, bright = policy(8, 80), policy(8, 8), policy(8, 24)
        assert bright == old, (bright, old)
        off, off_bright = policy(8, 8, False), policy(8, 80, False)
        assert off == off_bright
    rng = np.random.default_rng(763)
    h, w = 128, 192
    clean = np.full((h, w), .18, np.float32); clean[:, w // 2:] = .6
    noisy = clean + rng.normal(0, .0225, clean.shape).astype(np.float32)
    flat = np.s_[12:-12, 12:w // 2 - 12]
    results = {'renderer': ctx.info.get('GL_RENDERER'), 'policies': {'old': old, 'low': low}, 'luma': {}, 'ms': {}}
    codes = np.full((h, w), 64, np.uint8); codes[:, w // 2:] = 16; codes[0] = 0
    eff = texture(codes, 'u1'); gain = texture(np.ones((1, 1, 4), np.float32))
    maps = []
    for sharp_ref in (0., 256.):
        t = draw('scamdn/strmap.glsl', {'EffMap': eff, 'GainMap': gain},
                 {'factorU': 1, 'effRefU': 64., 'sharpRefU': sharp_ref, 'effMaxU': 3., 'noEffU': 0,
                  'flatU': 0., 'lscU': (0., 4.), 'fullInvU': (1 / w, 1 / h)}, (w, h), channels=2)
        maps.append(read(t)); t.release()
    eff.release(); gain.release()
    np.testing.assert_array_equal(maps[0][..., 0], maps[1][..., 0])
    assert np.max(np.abs(maps[0][..., 1] - maps[1][..., 1])) > .7
    a = luma(noisy, low, 0., strength_map=maps[0]); b = luma(noisy, low, 0., strength_map=maps[1])
    np.testing.assert_array_equal(a, b)
    bright_delta = float(np.max(np.abs(luma(noisy, old, 0., strength_map=maps[0]) -
                                       luma(noisy, old, 0., strength_map=maps[1]))))
    assert bright_delta > .001
    results['sharp_reference'] = {'q1_bit_identical_with_changed_sharp_ref': True,
                                  'variance_channel_bit_identical': True, 'q0_output_difference_max': bright_delta}
    for name, rejected, with_map in [('accepted', 0., True), ('rejected', 1., True), ('base_only', .75, True), ('no_map', 0., False)]:
        a, b = luma(noisy, old, rejected, with_map), luma(noisy, low, rejected, with_map)
        np.testing.assert_array_equal(a, luma(noisy, bright, rejected, with_map))
        ratio = float(np.var(b[flat] - clean[flat]) / np.var(a[flat] - clean[flat]))
        before, after = edge_metrics(a), edge_metrics(b)
        assert ratio < .85, (name, ratio)
        assert abs(after['g50'] - before['g50']) < .15, (name, before, after)
        assert after['width10_90'] - before['width10_90'] < .5, (name, before, after)
        assert after['contrast'] / before['contrast'] > .98, (name, before, after)
        results['luma'][name] = {'noise_variance_ratio': ratio, 'before': before, 'after': after, 'bright_bit_identical': True}
    rgb = np.repeat(clean[..., None], 3, axis=2) + rng.normal(0, .0225, (h, w, 3)).astype(np.float32)
    lumaw = np.array([.2126, .7152, .0722])
    for name, frames in [('accepted', 8), ('rejected', 2), ('base_only', 1), ('no_map', None)]:
        a, b = multiscale(rgb, old, frames), multiscale(rgb, low, frames)
        np.testing.assert_array_equal(a, multiscale(rgb, bright, frames))
        ya, yb = a @ lumaw, b @ lumaw
        v = float(np.var(yb[flat]) / np.var(ya[flat]))
        ca, cb = a[..., 0] - a[..., 1], b[..., 0] - b[..., 1]
        cv = float(np.var(cb[flat]) / np.var(ca[flat]))
        before, after = edge_metrics(ya), edge_metrics(yb)
        assert v < .95 and cv < .95, (name, v, cv)
        assert abs(after['g50'] - before['g50']) < .15 and after['width10_90'] - before['width10_90'] < .5, (name, before, after)
        assert after['contrast'] / before['contrast'] > .98
        results['ms'][name] = {'luma_variance_ratio': v, 'chroma_variance_ratio': cv, 'before': before, 'after': after, 'bright_bit_identical': True}
    results['source_sha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in
                                [JAVA / 'SportsDenoisePolicy.java', JAVA / 'ScamDenoiseTables.java', SHADERS / 'scamdn/lbf.glsl', SHADERS / 'why/msdn.glsl']}
    results['scope'] = 'Actual Java policy/tables; actual host GL luma pass and full multiscale pyramid; synthetic noisy moving-subject hard step; not whole-phone image quality.'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
