"""P596 «Локальный контраст»: why/localcontrast.glsl (WhyCombine on the fused luma, WhyTone.localContrast).

The shipped shader's two gaussian passes and its apply on GL against a numpy reference of them (sigma = WhyTone's 4 full-size px /
the whyLow factor 2, c and t WhyTone's defaults); then what it does: a low-contrast texture (wall panels, foliage) at the gaussian's
scale x ~(1 + c), a step against a light (a backlit edge: 0.15 sky / 0.9, the P595 case; and 0.05 / 0.9) and a branch-like thin dark
line on the sky overshoot no more than 1.27 c t (~4 codes), a smooth sky gradient unchanged (no banding: a linear ramp's gaussian is itself), a flat
field unchanged."""
import re
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_why_pyramid_bands as cwb  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
TONE = (ROOT / 'app/src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyTone.java').read_text(encoding='utf-8')
COMB = (ROOT / 'app/src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyCombine.java').read_text(encoding='utf-8')
fails = []


def check(ok, what):
    print(('PASS ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)


m = re.search(r'value\("local_contrast", ([\d.]+)f\)', TONE)
k = re.search(r'LOCAL_CONTRAST_SIGMA = ([\d.]+)f, LOCAL_CONTRAST_T = ([\d.]+)f;', TONE)
check(m is not None and k is not None, 'WhyTone: localContrast default, sigma, t')
C, SIGMA_FULL, T = float(m.group(1)), float(k.group(1)), float(k.group(2))
check(0.0 < C <= 0.5, 'the default strength %.2f in (0, 0.5]' % C)
check('glProg.setTexture("WhyFused", fusedLc != null ? fusedLc : st.fused);' in COMB and 'if (lc > 0f && !filmOnly)' in COMB,
      'WhyCombine binds the local-contrast luma, only with a strength (0 = the fused luma as before)')
SIGMA = SIGMA_FULL / 2.0   # whyLow of factor 2


def ref(f):
    r = int(np.ceil(3 * SIGMA)); w = np.exp(-0.5 * np.arange(-r, r + 1) ** 2 / SIGMA ** 2)
    def blur(a, ax):
        p = np.pad(a, [(r, r) if i == ax else (0, 0) for i in range(2)], mode='edge')
        return sum(w[i] * np.take(p, range(i, i + a.shape[ax]), axis=ax) for i in range(2 * r + 1)) / w.sum()
    g = blur(blur(f, 1), 0)
    d = f - g
    x = np.clip((np.abs(d) - T) / (2 * T), 0, 1); x = x * x * (3 - 2 * x)
    return f + C * d * (1 - x)


def gpu(gl, f):
    h, w = f.shape
    src = gl.tex(f[..., None].astype(np.float32), 'f4')
    a, b, out = gl.target(w, h, 1, 'f2'), gl.target(w, h, 1, 'f2'), gl.target(w, h, 1, 'f4')
    p0, p1 = gl.progd('why/localcontrast', {'MODE': '0'}), gl.progd('why/localcontrast', {'MODE': '1'})
    gl.draw(p0, a, [('InputBuffer', src)], {'dirU': (1, 0), 'sigmaU': SIGMA})
    gl.draw(p0, b, [('InputBuffer', a)], {'dirU': (0, 1), 'sigmaU': SIGMA})
    gl.draw(p1, out, [('InputBuffer', src), ('Blurred', b)], {'lcU': (C, T)})
    return np.frombuffer(out.read(), np.float32).reshape(h, w)


try:
    gl = cwb.Gl()
except Exception as e:  # noqa: BLE001
    print('no GL: %s' % e)
    sys.exit(1)
N = 160
yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
rng = np.random.default_rng(3)
tex = rng.standard_normal((N, N)).astype(np.float32)
from_k = np.fft.fft2(tex); fr = np.hypot(*np.meshgrid(np.fft.fftfreq(N), np.fft.fftfreq(N), indexing='ij'))
tex = np.real(np.fft.ifft2(from_k * ((fr > 0.04) & (fr < 0.12)))).astype(np.float32); tex /= tex.std()
scenes = {
    'texture 1 %': 0.5 + 0.01 * tex,
    'backlit edge 0.15 / 0.9': np.where(xx < N / 2, 0.15, 0.9).astype(np.float32),
    'dark edge 0.05 / 0.9': np.where(xx < N / 2, 0.05, 0.9).astype(np.float32),
    'thin branch on the sky': np.where(np.abs(xx - N / 2) < 1.5, 0.1, 0.8).astype(np.float32),
    'sky gradient': (0.55 + 0.3 * yy / N).astype(np.float32),
    'flat': np.full((N, N), 0.62, np.float32),
}
for name, f in scenes.items():
    g, r = gpu(gl, f), ref(f)
    check(np.abs(g - r).max() < 2e-3, '%s: GPU = reference (%.1e; the gaussian in fp16)' % (name, np.abs(g - r).max()))
    c = slice(12, N - 12)
    if name == 'texture 1 %':
        gain = float(np.std(g[c, c] - 0.5) / np.std(f[c, c] - 0.5))
        # its band 0.04 - 0.12 cycles / whyLow px: 1 - the gaussian's response there (~0.4) of the 1 + c boost
        check(1.0 + 0.3 * C < gain <= 1.0 + C + 1e-3, 'texture: amplitude x %.3f (at most 1 + c = %.2f)' % (gain, 1 + C))
    elif name in ('sky gradient', 'flat'):
        check(np.abs(g[c, c] - f[c, c]).max() < 2e-3, '%s: unchanged (%.1e)' % (name, np.abs(g[c, c] - f[c, c]).max()))
    else:
        lo, hi = f.min(), f.max()
        over = max(float(g.max() - hi), float(lo - g.min()), 0.0)
        # the boost's largest value: c max_d d (1 - smoothstep(t, 3 t, d)) = 1.27 c t (at d ~1.5 t)
        check(over <= 1.27 * C * T + 2e-4, '%s: overshoot %.4f <= 1.27 c t = %.4f (no halo, no white dots)' % (name, over, 1.27 * C * T))
print('why local contrast check ' + ('FAIL' if fails else 'PASS'))
sys.exit(1 if fails else 0)
