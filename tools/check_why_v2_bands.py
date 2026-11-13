#!/usr/bin/env python3
"""P677: V1's P391 edge-aware bands on V2's pyramid (owner, 2026-11-13: «Да, перенеси в V2») - why/v2_ef_prep MODE 2 (the guide's
log light and the per-pixel fusion), why/v2_ef_bands (the edge measure D, the shares), why/v2_ef_collapse / v2_ef_coarsest BANDS 1
(the band P + (1 - a) (M - P), the coarsest level moved to Fpp's Gaussian by its carried share). Off by default (WhyTone.edgeBandsV2:
hybrid_why_v2_edge_bands 0). Checks, on the host GPU (Mesa llvmpipe) as why_v2_host / why_v2_bands draw them:
1. the default in WhyTone.java is 0 (off); with BANDS off (and v2_ef_prep's MODE 0 / 1) the three shaders are the shaders before
   P677 token for token (their code - comments, blank lines and the BANDS / MODE 2 branches dropped - hashes to the pinned hash);
2. strength 0 with the define is V2's fusion bit for bit (a = 0: M itself), with and without the JBU;
3. every share 1 (a ramp from 0 EV) collapses to the per-pixel fusion Fpp within the R16F rounding (the bands telescope);
4. the halos (V2 at its JBU default 0.1): a narrow gap of sky between a dark beam and a dark block: its glow lower than V2's
   without JBU and bands and at most 1e-3 over the open sky, the open sky above the beam no brighter than with the JBU alone;
   a flat image unchanged (1e-6), clouds' texture >= 0.97 of the JBU alone's high-pass contrast.
Usage: check_why_v2_bands.py
"""
import hashlib
import re
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_why_pyramid_bands as B  # noqa: E402
import why_v2_bands as VB  # noqa: E402
import why_v2_host as V  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders/why'
FAIL = []
PINNED = {'v2_ef_prep.glsl': '9e62229166ab9c6a83233c27829ea2b27faff0b8b2f1ed7eb92b882e4d060378',
          'v2_ef_collapse.glsl': 'c5517a211f73253f1760a48524130d9ce473255e544174d1e54e73ed9f564b07',
          'v2_ef_coarsest.glsl': 'e073ccc41cb72b1aeab1b08cb2ba390435a7c77f6d08b1c9bb466a503c462cab'}
JBU = (1, 0.1, 0.0)


def check(ok, msg):
    print(('ok   ' if ok else 'FAIL ') + msg, flush=True)
    if not ok:
        FAIL.append(msg)


def ours(cond, name):
    """A condition on P677's define BANDS or v2_ef_prep's MODE 2 (false with them off)."""
    if re.search(r'\bBANDS\b', cond):
        return True
    return name == 'v2_ef_prep.glsl' and re.fullmatch(r'\s*MODE == 2\s*', cond) is not None


def off_code(text, name):
    """The shader's code with P677's define off: their #if branches resolved (an #elif after a dropped #if becomes the #if, an
    #else after it unwrapped), their #define lines dropped, comments, trailing blanks and empty lines removed."""
    out, stack = [], []   # stack entries: 'normal', 'skip' (inside a false branch of ours, nothing emitted yet), 'unwrap', 'drop'
    for raw in text.split('\n'):
        line = raw.split('//')[0].rstrip()
        s = line.strip()
        m = re.match(r'#\s*(if|ifdef|ifndef|elif|else|endif)\b(.*)', s)
        if re.match(r'#\s*define\s+BANDS\s', s):
            continue
        if m:
            kw, cond = m.group(1), m.group(2)
            if kw in ('if', 'ifdef', 'ifndef'):
                if stack and stack[-1] in ('skip', 'drop'):
                    stack.append('drop')
                elif kw == 'if' and ours(cond, name):
                    stack.append('skip')
                else:
                    stack.append('normal')
                    out.append(s)
                continue
            top = stack[-1]
            if kw == 'elif':
                if top == 'skip':
                    if not ours(cond, name):
                        stack[-1] = 'normal'
                        out.append('#if' + cond)
                elif top == 'normal':
                    if ours(cond, name):
                        stack[-1] = 'dropbranch'
                    else:
                        out.append(s)
                elif top == 'dropbranch':
                    if not ours(cond, name):
                        stack[-1] = 'normal'
                        out.append(s)
                continue
            if kw == 'else':
                if top == 'skip':
                    stack[-1] = 'unwrap'
                elif top == 'normal':
                    out.append(s)
                elif top == 'dropbranch':
                    stack[-1] = 'normal'
                    out.append(s)
                elif top == 'unwrap':
                    stack[-1] = 'drop'
                continue
            stack.pop()
            if top in ('normal', 'dropbranch'):
                out.append(s)
            continue
        if stack and stack[-1] in ('skip', 'drop', 'dropbranch'):
            continue
        if s:
            out.append(line)
    return '\n'.join(out) + '\n'


def code_hash(text, name):
    return hashlib.sha256(off_code(text, name).encode('utf-8')).hexdigest()


def rgb(lum):
    return np.repeat(lum.astype(np.float32)[..., None], 3, -1) * np.array([0.8, 1.0, 1.3], np.float32)


def fused(clean, fixed=None, jbu=JBU, bands=None, strength=None, ramp=None):
    VB.STRENGTH, VB.RAMP = strength, ramp
    keep = {}
    _, r = V.render(clean, keep=keep, fixed=fixed, jbu=jbu, bands=bands)
    VB.STRENGTH = VB.RAMP = None
    return keep, r


def main():
    src = (ROOT / 'app/src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyTone.java').read_text(encoding='utf-8')
    m = re.search(r'value\("v2_edge_bands", ([\d.]+)f\)', src)
    check(m is not None and float(m.group(1)) == 0.0, 'WhyTone: hybrid_why_v2_edge_bands off by default (0)')
    for name, h in PINNED.items():
        text = (SH / name).read_text(encoding='utf-8')
        got = code_hash(text, name)
        check(got == h, 'why/%s with BANDS off: the shader before P677 token for token (%s)' % (name, got[:12]))
    for name in ('v2_ef_collapse.glsl', 'v2_ef_coarsest.glsl'):
        check('\n#define BANDS 0\n' in (SH / name).read_text(encoding='utf-8'), 'why/%s: #define BANDS 0' % name)

    h, w = 768, 1024
    y, x = np.mgrid[0:h, 0:w]
    # a dark block under a dark beam with a narrow gap of sky between them (the pool's awning), the open sky above
    block = (y > 330) & (y < 700)
    beam = (y > 260 + 0.1 * (x - 300)) & (y < 290 + 0.1 * (x - 300)) & (x > 300)
    gap = ~block & ~beam & (y > 290 + 0.1 * (x - 300)) & (x > 300) & (x < 800)
    near = ~block & ~beam & (y < 250) & (y > 200)
    scene = np.where(block | beam, 0.02, 0.6)
    clouds = B.clouds()[:h, :w] * 0.8
    cases = {'scene': rgb(scene), 'flat': rgb(np.full((h, w), 0.3)), 'clouds': rgb(clouds)}
    base = {k: fused(c) for k, c in cases.items()}
    for jb in (JBU, None):
        k0, r0 = fused(cases['clouds'], base['clouds'][1], jbu=jb)
        k1, _ = fused(cases['clouds'], base['clouds'][1], jbu=jb, bands=(), strength=0.0)
        check(np.array_equal(k0['fused'], k1['fused']), 'strength 0 with BANDS 1: V2\'s fusion bit for bit (%s)' % ('JBU 0.1' if jb else 'no JBU'))
    for k, c in cases.items():
        kk, _ = fused(c, base[k][1], bands=(), ramp=(0.0, 0.001))
        d = float(np.abs(kk['fused'] - kk['fpp']).max())
        check(d <= 2e-3, '%s: every share 1 collapses to the per-pixel fusion, |d| %.1e (R16F)' % (k, d))
    Fo = fused(cases['scene'], base['scene'][1], jbu=None)[0]['fused']
    F0 = base['scene'][0]['fused']
    F1 = fused(cases['scene'], base['scene'][1], bands=())[0]['fused']
    rel = lambda f, m: float(f[m].mean() - f[30:120, 100:900].mean())
    go, g0, g1 = rel(Fo, gap), rel(F0, gap), rel(F1, gap)
    check(g1 < go and g1 <= 1e-3, 'the gap of sky under the beam: %+.5f over the open sky (no JBU %+.5f, JBU alone %+.5f)' % (g1, go, g0))
    o0, o1 = rel(F0, near), rel(F1, near)
    check(o1 <= max(o0, 0.0) + 5e-4, 'the open sky above the beam: %+.5f (JBU alone %+.5f)' % (o1, o0))
    kf = fused(cases['flat'], base['flat'][1], bands=())[0]['fused']
    check(float(np.abs(kf - base['flat'][0]['fused']).max()) <= 1e-6, 'a flat image unchanged')
    kc = fused(cases['clouds'], base['clouds'][1], bands=())[0]['fused']
    t0, t1 = B.hp_std(base['clouds'][0]['fused']), B.hp_std(kc)
    check(t1 >= 0.97 * t0, 'clouds\' texture %.5f (JBU alone %.5f, %.3f)' % (t1, t0, t1 / t0))
    print('why V2 edge bands check %s' % ('FAIL (%d)' % len(FAIL) if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--hash':
        for p in sys.argv[2:]:
            print(Path(p).name, code_hash(Path(p).read_text(encoding='utf-8'), Path(p).name))
        sys.exit(0)
    sys.exit(main())
