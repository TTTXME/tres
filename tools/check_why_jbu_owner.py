#!/usr/bin/env python3
"""P673: the owner's joint bilateral upsampling in the collapse of the WHY fusion pyramids (owner, 2026-11-13: the reference's
k_fs_ef_pyr_collapse with JBU - «вместо аппаратного билинейного texture() ... сэмплируются 4 coarse-текселя с весом
exp(-(g_coarse - g_fine)^2 / 2 sigma_r^2)») - V2 (why/v2_ef_collapse.glsl JBU: texture()'s 4 bilinear texels of the coarse lumas and
fused luma) and V1 (why/lpyr.glsl JBU, MODE 4 / 5: expand's 2 x 2 nodes of CoarseL, CoarseF and the bands' CoarseGF), each texel
weighted by its bilinear weight x exp(-(g_coarse - g_fine)^2 / (2 sigma^2)), normalised; JBU 1: g the four exposures' lumas (their
squared distance), JBU 2: g the collapsing image (the coarser fused luma against the pixel's fused luma of the plain expand); a log
floor > 0: g in log2. Off by default (WhyTone.jbuSigma / jbuSigmaV2: 0). Checks, on the host GPU (Mesa llvmpipe) as
check_why_pyramid_bands / why_v2_host draw them:
1. the defaults in WhyTone.java are 0 (off); with JBU off the two shaders are the shaders before P673 token for token (their code -
   comments, blank lines and the JBU branches dropped - hashes to the pinned hash of the files before P673);
2. exact reconstruction: the collapse of one exposure (weights (1, 0, 0, 0)) gives its finest level back for every guide (V1:
   within 1e-6 of fp32; V2: within the R16F rounding) - the band and the base are read with the same weights;
3. a flat image is the off path's within 1e-6 (V2: 1e-3, the exact texel fetches against texture()'s 8-bit sub-texel weights: one
   R16F step near 1); a smooth 6 EV ramp within 2e-3 (V1) / 3e-3 (V2) and no blocking (its largest second difference at most 1.5 x
   the off path's + 1e-4 (V2: + 1e-3, one R16F step));
4. the halos: V2 - a hard horizon, a 40 px dark beam and thin twigs against a bright sky: the sky beside the edge no brighter than
   the off path (+ one R16F step), the horizon's glow lower than the off path's; V1 (with the P391 bands) - the horizon, the beam
   and the twigs no further from the guided filter than the off path (+1e-4); cloud texture >= 0.99 of the off path's (both).
Usage: check_why_jbu_owner.py
"""
import hashlib
import re
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_why_pyramid as cwp  # noqa: E402
import check_why_pyramid_bands as B  # noqa: E402
import why_v2_host as V  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders/why'
FAIL = []
# (guide, sigma_r, log floor): the lumas (the owner's g_coarse / g_fine) at the host A/B's sigma and twice it, the collapsing
# image, the lumas in log2 (sigma in EV)
SPECS = ((1, 0.05, 0.0), (1, 0.1, 0.0), (2, 0.05, 0.0), (1, 0.5, 0.01))
# sha256 of the code of the shaders before P673 (normalised as off_code does)
PINNED = {'lpyr.glsl': 'f73f5d323828e8429ea9453271753b7279aeafabf2b9fd18aa7edc38b4c6bb35',
          'v2_ef_collapse.glsl': '0237680a68ababcb6ec2dfa5a751c4732532e0ce7ba85ffc648bb74da9ecbad0'}


def check(ok, msg):
    print(('ok   ' if ok else 'FAIL ') + msg, flush=True)
    if not ok:
        FAIL.append(msg)


def ours(cond, name):
    """A condition on P673's define JBU (false with it off: JBU 0); P677's BANDS (V2's edge bands) is dropped the same way."""
    return re.search(r'\b(JBU|BANDS)\b', cond) is not None


def off_code(text, name):
    """The shader's code with P673's define off: their #if branches resolved (an #elif after a dropped #if becomes the #if, an
    #else after it unwrapped), their #define lines dropped, comments, trailing blanks and empty lines removed."""
    out, stack = [], []   # stack entries: 'normal', 'skip' (inside a false branch of ours, nothing emitted yet), 'unwrap', 'drop'
    for raw in text.split('\n'):
        line = raw.split('//')[0].rstrip()
        s = line.strip()
        m = re.match(r'#\s*(if|ifdef|ifndef|elif|else|endif)\b(.*)', s)
        if re.match(r'#\s*define\s+(JBU|BANDS)\s', s):
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


def v1_reconstruction(gl, spec):
    """lpyr MODE 2 reduce of random lumas with weights (1, 0, 0, 0), MODE 3 and the MODE 4 chain (JBU, no bands): the collapse's
    finest level against the lumas' .x."""
    rng = np.random.default_rng(3)
    h, w = 193, 257
    L1 = np.exp(rng.uniform(-4, 0, (h, w, 4))).astype(np.float32)
    L1[64:, 100:] *= 0.05                                           # a contrasty edge
    W1 = np.zeros((h, w, 4), np.float32)
    W1[..., 0] = 1.0
    ju = {'jbuU': (float(spec[1]), float(spec[2]))}

    def P(mode, j):
        return gl.progd('why/lpyr', dict({'MODE': str(mode), 'EDGE_BANDS': '0'}, **({'JBU': str(spec[0])} if j else {})))
    L, Wt = [gl.tex(L1, 'f4')], [gl.tex(W1, 'f4')]
    sizes = [(w, h)]
    for l in range(4):
        sizes.append(((sizes[-1][0] + 1) // 2, (sizes[-1][1] + 1) // 2))
        for p in (L, Wt):
            t = gl.target(*sizes[-1], 4, 'f4')
            gl.draw(P(2, False), t, [('InputBuffer', p[-1])], {})
            p.append(t)
    n = len(L) - 1
    F = [None] * (n + 1)
    F[n] = gl.target(*sizes[n], 1, 'f4')
    gl.draw(P(3, False), F[n], [('LevelL', L[n]), ('LevelW', Wt[n])], {})
    for l in range(n - 1, -1, -1):
        F[l] = gl.target(*sizes[l], 1, 'f4')
        gl.draw(P(4, True), F[l], [('LevelL', L[l]), ('LevelW', Wt[l]), ('CoarseL', L[l + 1]), ('CoarseF', F[l + 1])], ju)
    out = gl.read(F[0])[..., 0]
    return float(np.abs(out - L1[..., 0]).max() / L1[..., 0].max())


def v2_reconstruction(spec):
    """why_v2_host's pyramid passes on random lumas with weights (1, 0, 0, 0): the JBU collapse's level 0 against G.x."""
    g = V.gpu()
    rng = np.random.default_rng(3)
    lv = V.levels(517, 389)
    G0 = rng.uniform(0.02, 1.0, (389, 517, 4)).astype(np.float32)
    G0[150:, 200:] *= 0.1
    W0 = np.zeros_like(G0)
    W0[..., 0] = 1.0
    G, W = [g.tex(G0, 'f2', True)], [g.tex(W0, 'f2', True)]
    for i in range(len(lv) - 1):
        for pyr in (G, W):
            u = {'u_texel_size': (1.0 / lv[i][0], 1.0 / lv[i][1]), 'u_dst_w': lv[i + 1][0], 'u_dst_h': lv[i + 1][1]}
            pyr.append(g.draw(g.prog('v2_pyr_down'), g.target(lv[i + 1], 4, linear=True), {'u_tex_src': pyr[i]}, u))
    n = len(lv)
    F = [None] * n
    F[n - 1] = g.draw(g.prog('v2_ef_coarsest'), g.target(lv[n - 1], 1, linear=True), {'u_tex_G': G[n - 1], 'u_tex_W': W[n - 1]},
                      {'u_width': lv[n - 1][0], 'u_height': lv[n - 1][1]})
    for i in range(n - 2, -1, -1):
        F[i] = g.draw(g.prog('v2_ef_collapse', {'JBU': spec[0]}), g.target(lv[i], 1, linear=True),
                      {'u_tex_G_fine': G[i], 'u_tex_G_coarse': G[i + 1], 'u_tex_W_fine': W[i], 'u_tex_fused_coarse': F[i + 1]},
                      {'u_width': lv[i][0], 'u_height': lv[i][1], 'u_clarity': 0.0, 'u_jbu_sigma': float(spec[1]),
                       'u_jbu_log': float(spec[2])})
    out = g.read(F[0], 1)[..., 0]
    return float(np.abs(out - G0[..., 0].astype(np.float16).astype(np.float32)).max())


def name_of(spec):
    return 'guide %d sigma %g%s' % (spec[0], spec[1], ' log2' if spec[2] > 0 else '')


def main():
    src = (ROOT / 'app/src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyTone.java').read_text(encoding='utf-8')
    for key, want in (('fusion_jbu_sigma', 0.0), ('v2_jbu_sigma', 0.1)):  # P673c: V2 on at the owner's 0.1, V1 off
        m = re.search(r'value\("%s", ([\d.]+)f\)' % key, src)
        check(m is not None and float(m.group(1)) == want, 'WhyTone: hybrid_why_%s default %g' % (key, want))
    for name, h in PINNED.items():
        text = (SH / name).read_text(encoding='utf-8')
        got = code_hash(text, name)
        check(got == h, 'why/%s with JBU off: the shader before P673 token for token (%s)' % (name, got[:12]))
        check('\n#define JBU 0\n' in text, 'why/%s: #define JBU 0' % name)

    # ---- exact reconstruction (one exposure); V1 first, V2 after (each host chain has its own GL context)
    gl = B.Gl()
    for sp in SPECS:
        e1 = v1_reconstruction(gl, sp)
        check(e1 <= 1e-6, 'V1 %s: one exposure collapses to its level, rel. |d| %.1e' % (name_of(sp), e1))

    # ---- V1: why/lpyr with the bands (check_why_pyramid_bands' scenes, 1024 x 768, 5 levels at coarse 24)
    H, W = B.H, B.W
    yy, xx = np.mgrid[0:H, 0:W]
    tw, _ = B.twigs()
    img = cwp.rgba(np.exp(np.random.default_rng(5).uniform(np.log(0.002), np.log(20.0), (191, 255)))[..., None] * np.ones(3))
    a, _ = B.fuse(gl, img, B.BENTO, 8, B.BANDS)
    b, _ = B.fuse(gl, img, B.BENTO, 8, B.BANDS, jbu=(1, 0.0, 0.0))
    check(np.array_equal(a, b), 'V1 sigma 0: the bands\' pyramid bit for bit (no JBU define)')
    flat = B.grey(np.full((96, 128), 0.3))
    ramp = B.grey(0.05 * 2 ** (6 * xx / W))
    f0, _ = B.fuse(gl, flat, B.FUSION, 24, B.BANDS)
    r0, _ = B.fuse(gl, ramp, B.FUSION, 24, B.BANDS)
    scenes = [('horizon', B.grey(np.where(yy < 384, 1.5, 0.03)), B.OLD), ('beam', B.grey(np.where((xx >= 500) & (xx < 540), 0.03, 1.2)), B.FUSION),
              ('twigs', B.grey(tw), B.FUSION)]
    base = {}
    for name, im, u in scenes:
        ref = cwp.guided(gl, im, u)
        F, _ = B.fuse(gl, im, u, 24, B.BANDS)
        base[name] = (ref, float(np.abs(F - ref).mean()), u)
    cl = B.grey(B.clouds())
    c0, _ = B.fuse(gl, cl, B.FUSION, 24, B.BANDS)
    for sp in SPECS:
        f1, _ = B.fuse(gl, flat, B.FUSION, 24, B.BANDS, jbu=sp)
        r1, _ = B.fuse(gl, ramp, B.FUSION, 24, B.BANDS, jbu=sp)
        d2a, d2b = np.abs(np.diff(r0, 2, 1)).max(), np.abs(np.diff(r1, 2, 1)).max()
        check(np.abs(f1 - f0).max() <= 1e-6 and np.abs(r1 - r0).max() <= 2e-3 and d2b <= 1.5 * d2a + 1e-4,
              'V1 %s: flat |d| %.1e, ramp |d| %.1e, ramp 2nd difference %.2e (off %.2e)'
              % (name_of(sp), np.abs(f1 - f0).max(), np.abs(r1 - r0).max(), d2b, d2a))
        for name, im, _ in scenes:
            ref, e0, u = base[name]
            F, _ = B.fuse(gl, im, u, 24, B.BANDS, jbu=sp)
            e1 = float(np.abs(F - ref).mean())
            check(e1 <= e0 + 1e-4, 'V1 %s %s: mean |F - guided| %.5f (off %.5f)' % (name_of(sp), name, e1, e0))
        c1, _ = B.fuse(gl, cl, B.FUSION, 24, B.BANDS, jbu=sp)
        check(B.hp_std(c1) >= 0.99 * B.hp_std(c0), 'V1 %s: cloud texture %.5f (off %.5f)' % (name_of(sp), B.hp_std(c1), B.hp_std(c0)))

    # ---- V2: the shipped chain (why_v2_host), 2048 x 1536 (7 levels), the statistics of the off render for every spec
    h2, w2 = 1536, 2048
    y2, x2 = np.mgrid[0:h2, 0:w2]

    def rgb(lum):
        return np.repeat(lum.astype(np.float32)[..., None], 3, -1) * np.array([0.8, 1.0, 1.3], np.float32)

    for sp in SPECS:
        e2 = v2_reconstruction(sp)
        check(e2 <= 2e-3, 'V2 %s: one exposure collapses to its level, |d| %.1e (R16F)' % (name_of(sp), e2))

    def fused(clean, sp, fixed=None):
        keep = {}
        _, r = V.render(clean, keep=keep, fixed=fixed, jbu=sp)
        return keep['fused'], r

    sky = np.where(y2 < 3 * h2 // 4, 0.6, 0.12)
    cases = {'flat': rgb(np.full((h2, w2), 0.3)), 'ramp': rgb(0.05 * 2 ** (6 * x2 / w2)),
             'horizon': rgb(np.where(y2 < h2 // 2, 1.5, 0.03)), 'beam': rgb(np.where((x2 >= 1000) & (x2 < 1040) & (y2 < 3 * h2 // 4), 0.02, sky)),
             'twig': rgb(np.where((x2 >= 1000) & (x2 < 1003) & (y2 < 3 * h2 // 4), 0.02, sky)),
             'clouds': rgb(np.kron(B.clouds()[:768, :1024], np.ones((2, 2))) * 0.8)}
    off = {k: fused(c, None) for k, c in cases.items()}
    a, _ = fused(cases['clouds'], (1, 0.0, 0.0), off['clouds'][1])
    check(np.array_equal(a, off['clouds'][0]), 'V2 sigma 0: the reference\'s collapse bit for bit (no JBU define)')

    def glow(F, name):
        if name == 'horizon':
            p = F[:, 900:1100].mean(1)
            return float((p[h2 // 2 - 64:h2 // 2] - p[100:200].mean()).max())
        p = F[200:900].mean(0)
        e = 1040 if name == 'beam' else 1003
        return float((p[e:e + 64] - p[1700:1900].mean()).max())

    for sp in SPECS:
        f1, _ = fused(cases['flat'], sp, off['flat'][1])
        r1, _ = fused(cases['ramp'], sp, off['ramp'][1])
        r0 = off['ramp'][0]
        d2a, d2b = np.abs(np.diff(r0[768], 2)).max(), np.abs(np.diff(r1[768], 2)).max()
        check(np.abs(f1 - off['flat'][0]).max() <= 1e-3 and np.abs(r1 - r0).max() <= 3e-3 and d2b <= 1.5 * d2a + 1e-3,
              'V2 %s: flat |d| %.1e, ramp |d| %.1e, ramp 2nd difference %.2e (off %.2e)'
              % (name_of(sp), np.abs(f1 - off['flat'][0]).max(), np.abs(r1 - r0).max(), d2b, d2a))
        for name in ('horizon', 'beam', 'twig'):
            F, _ = fused(cases[name], sp, off[name][1])
            g0, g1 = glow(off[name][0], name), glow(F, name)
            lim = g0 - 1e-3 if name == 'horizon' else max(g0, 0.0) + 5e-4
            check(g1 <= lim, 'V2 %s %s: the sky beside the edge %+.5f over the open sky (off %+.5f)' % (name_of(sp), name, g1, g0))
        c1, _ = fused(cases['clouds'], sp, off['clouds'][1])
        check(B.hp_std(c1) >= 0.99 * B.hp_std(off['clouds'][0]), 'V2 %s: cloud texture %.5f (off %.5f)'
              % (name_of(sp), B.hp_std(c1), B.hp_std(off['clouds'][0])))
    print('why owner JBU check %s' % ('FAIL (%d)' % len(FAIL) if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--hash':
        for p in sys.argv[2:]:
            print(Path(p).name, code_hash(Path(p).read_text(encoding='utf-8'), Path(p).name))
        sys.exit(0)
    sys.exit(main())
