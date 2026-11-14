"""P596: the fast route's Quad de-grid (scam-fast-gpu.h kFastOutput deGrid) with the merged noise (u_flat_noise).

Runs the shipped at() / lumaAt() / deGrid() in a GL compute shader against a numpy port of them, on a low-contrast texture, a flat
noisy field, a beaded thin line and a slanted ribbed band, at 30 merged frames and at 1:
  - u_flat_noise 0: the old fixed thresholds (the port of the shader before P596), GPU = port;
  - the defaults (1.5, 8): at 1 frame byte for byte as before (moving subjects); at 30 frames the flat noisy field as before (its
    noise not raised), the texture and the ribs keep more of their detail, and nothing rises above the input (no overshoot)."""
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


def grab(pattern):
    m = re.search(pattern, SRC, re.S)
    check(m is not None, 'kFastOutput: ' + pattern.split('(')[0].replace('\\', ''))
    return m.group(0) if m else ''


AT = grab(r'vec3 at\(ivec2 p\)\{.*?\n\}')
SC = grab(r'ivec2 sc\(int dx, int dy\) \{[^\n]*\}')
LUMA = grab(r'float lumaAt\(ivec2 p, ivec2 lo, ivec2 hi\) \{[^\n]*\}')
DEGRID = grab(r'vec3 deGrid\(ivec2 p, vec3 v\) \{.*?\n\}')
check(MERGE.count('g.u4f(PO,"u_flat_noise",S,O,quad&&sensorType==1?std::clamp(tune.fastQuadFlatNoise,0.f,8.f):0.f,'
                  'quad&&sensorType==1?std::clamp(tune.fastQuadLineFrames,0.f,64.f):0.f);') == 2 and MERGE.count('u_flat_noise') == 2,
      'u_flat_noise set in both output passes, Quad (block 2) only')
check('float fastQuadFlatNoise=1.5f;' in HYB and 'float fastQuadLineFrames=8.f;' in HYB, 'defaults 1.5 / 8')

S_, O_ = 4.4e-4, 1e-7          # the noise model (variance S v + O, normalised)


def port(img, frames, z, w):
    """numpy port of deGrid (u_step 1); img H x W x 3 (the merge's values), frames H x W"""
    H, W = img.shape[:2]
    Y = img @ np.array([0.25, 0.5, 0.25], np.float32)
    def L(dy, dx):
        yy = np.clip(np.arange(H)[:, None] + dy, 0, H - 1); xx = np.clip(np.arange(W)[None, :] + dx, 0, W - 1)
        return Y[yy, xx]
    y0 = Y
    xm2, xm1, xp1, xp2 = L(0, -2), L(0, -1), L(0, 1), L(0, 2)
    ym2, ym1, yp1, yp2 = L(-2, 0), L(-1, 0), L(1, 0), L(2, 0)
    nx = (xm2 + 2 * (xm1 + y0 + xp1) + xp2) / 8
    ny = (ym2 + 2 * (ym1 + y0 + yp1) + yp2) / 8
    gx = np.abs(xp2 - xm2) + 0.5 * np.abs(L(1, 2) - L(1, -2)) + 0.5 * np.abs(L(-1, 2) - L(-1, -2))
    gy = np.abs(yp2 - ym2) + 0.5 * np.abs(L(2, 1) - L(-2, 1)) + 0.5 * np.abs(L(2, -1) - L(-2, -1))
    rel = (gx + gy) / (4 * y0)
    lo, hi = np.full_like(y0, 0.04), np.full_like(y0, 0.10)
    relF = rel
    dx4, dy4 = np.abs(L(0, 4) - L(0, -4)), np.abs(L(4, 0) - L(-4, 0))
    m = np.maximum(np.sqrt(np.maximum(S_ * y0 + O_, 0) / np.maximum(frames, 1)) / y0, 1e-6)
    if z > 0:
        lo = np.minimum(lo, z * m); hi = np.maximum(np.minimum(hi, 2.5 * z * m), lo + 1e-6)
        relF = np.maximum(rel, np.maximum((dx4 + dy4) / (2 * y0) - 1.5 * m, 0))
    ss = lambda a, b, x: (lambda t: t * t * (3 - 2 * t))(np.clip((x - a) / (b - a), 0, 1))
    flat = 1 - ss(lo, hi, relF)
    hl = ss(1.5, 3.0, gy / np.maximum(gx, 1e-5 * y0)) * (1 - flat)
    vl = ss(1.5, 3.0, gx / np.maximum(gy, 1e-5 * y0)) * (1 - flat)
    if w > 0:
        few = 1 - ss(0.5 * w, w, frames)
        hl = hl * few * (1 - ss(0.15, 0.4, dx4 / np.maximum(0.5 * gy, 1e-5 * y0)) * ss(2.0 * m, 4.0 * m, dx4 / y0))
        vl = vl * few * (1 - ss(0.15, 0.4, dy4 / np.maximum(0.5 * gx, 1e-5 * y0)) * ss(2.0 * m, 4.0 * m, dy4 / y0))
    yt = y0 + (nx - y0) * hl
    yt = yt + (ny - yt) * vl
    yt = yt + (0.5 * (nx + ny) - yt) * flat
    return img * (yt / y0)[..., None]


def gauss(a, sigma):
    """separable gaussian, reflected borders (numpy only: the CI venv has no scipy)"""
    R = int(np.ceil(3 * sigma)); k = np.exp(-0.5 * (np.arange(-R, R + 1) / sigma) ** 2); k /= k.sum()
    f = lambda r: np.convolve(np.pad(r, R, mode='reflect'), k, 'valid')
    return np.apply_along_axis(f, 0, np.apply_along_axis(f, 1, a)).astype(np.float32)


rng = np.random.default_rng(5)
N = 128
yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
tex = gauss(rng.standard_normal((N, N)).astype(np.float32), 0.8)
level = 0.2
sig30 = np.sqrt((S_ * level + O_) / 30)
scenes = {
    'texture 3 %': level * (1 + 0.03 * tex / tex.std()),
    'flat noisy': np.full((N, N), level, np.float32),
    'beaded line': np.where(np.abs(yy - 64) < 1, 0.05 * (1 + 0.04 * np.cos(np.pi * xx / 2)), level).astype(np.float32),
    'slanted ribs': level * (1 + 0.06 * np.sign(np.sin(2 * np.pi * (xx + 0.25 * yy) / 5.0))).astype(np.float32),
    # 140309's facade: vertical grooves of 4.5 px at 4 % crossing a horizontal seam (the line notch along x took them there)
    'grooves 4.5 px': (level * (1 + 0.04 * np.cos(2 * np.pi * xx / 4.5)) * np.where(np.abs(yy - 64) < 2, 0.6, 1.0)).astype(np.float32),
    # the Quad lattice itself: period 4 at 1.5 % on a flat field - taken off as before
    'lattice 4 px': (level * (1 + 0.015 * np.cos(np.pi * xx / 2) * np.cos(np.pi * yy / 2))).astype(np.float32),
}
grey = np.array([0.45, 1.0, 0.62], np.float32)
try:
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    cs = ('#version 430\nlayout(local_size_x=8,local_size_y=8) in;\nuniform highp sampler2D u_accum_color_r;\n'
          'uniform highp sampler2D u_accum_weights_gb;\nuniform highp sampler2D u_frame_weights;\nuniform ivec2 u_size;\n'
          'uniform ivec2 u_half;\nuniform int u_acc_row0;\nuniform vec3 u_inv_wb;\nuniform float u_step;\nuniform vec4 u_flat_noise;\n'
          'layout(std430,binding=0) buffer O{float o[];};\n' + AT + '\n' + SC + '\n' + LUMA + '\n' + DEGRID +
          '\nvoid main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(p.x>=u_size.x||p.y>=u_size.y)return;vec3 v=deGrid(p,at(p));'
          'int i=(p.y*u_size.x+p.x)*3;o[i]=v.r;o[i+1]=v.g;o[i+2]=v.b;}\n')
    prog = ctx.compute_shader(cs)

    def gpu(img, frames, z, w):
        H, W = img.shape[:2]
        acc = ctx.texture((W, H), 4, np.concatenate([img, np.ones((H, W, 1), np.float32)], -1).astype('f4').tobytes(), dtype='f4')
        wts = ctx.texture((W, H), 4, np.ones((H, W, 4), np.float32).tobytes(), dtype='f4')
        fw = ctx.texture((W, H), 1, (frames - 1).astype('f4').tobytes(), dtype='f4')  # the merge's weights (eff - 1), HALF = OUT here
        for t in (acc, wts, fw):
            t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        acc.use(0); wts.use(1); fw.use(2)
        prog['u_accum_color_r'].value = 0; prog['u_accum_weights_gb'].value = 1; prog['u_frame_weights'].value = 2
        prog['u_size'].value = (W, H); prog['u_half'].value = (W, H); prog['u_acc_row0'].value = 0
        prog['u_inv_wb'].value = (1.0, 1.0, 1.0); prog['u_step'].value = 1.0; prog['u_flat_noise'].value = (S_, O_, z, w)
        out = ctx.buffer(reserve=H * W * 12); out.bind_to_storage_buffer(0)
        prog.run((W + 7) // 8, (H + 7) // 8)
        r = np.frombuffer(out.read(), np.float32).reshape(H, W, 3).copy()
        for t in (acc, wts, fw, out):
            t.release()
        return r

    hp = lambda a: a - gauss(a, 2.0)
    for name, base in scenes.items():
        for nf in (1, 30):
            noisy = base + rng.standard_normal(base.shape).astype(np.float32) * np.float32(np.sqrt((S_ * level + O_) / nf))
            img = np.maximum(noisy, 1e-3)[..., None] * grey
            frames = np.full((N, N), float(nf), np.float32)
            old_g, old_p = gpu(img, frames, 0.0, 0.0), port(img, frames, 0.0, 0.0)
            new_g, new_p = gpu(img, frames, 1.5, 8.0), port(img, frames, 1.5, 8.0)
            check(np.abs(old_g - old_p).max() < 2e-5 and np.abs(new_g - new_p).max() < 2e-5,
                  '%s, %d frame(s): GPU = port (old %.1e, new %.1e)' % (name, nf, np.abs(old_g - old_p).max(), np.abs(new_g - new_p).max()))
            c = slice(8, N - 8)
            Lm = lambda a: (a @ np.array([0.25, 0.5, 0.25], np.float32))[c, c]
            truth = (base[..., None] * grey)
            e_in, e_old, e_new = (np.std(hp(Lm(a))) for a in (truth, old_g, new_g))
            corr = lambda a: float(np.sum(hp(Lm(a)) * hp(Lm(truth))) / max(np.sum(hp(Lm(truth)) ** 2), 1e-20))
            def p4(a):  # the period-4 energy along x on the line / the field (the beads, the lattice)
                F = np.abs(np.fft.rfft(hp(Lm(a)), axis=1)) ** 2; k = F.shape[1] - 1; return float(F[:, round(k / 2)].sum())
            if nf == 1:
                if name in ('beaded line', 'lattice 4 px', 'flat noisy'):
                    check(np.array_equal(new_g, old_g) or p4(new_g) <= 1.05 * p4(old_g) + 1e-12,
                          '%s, 1 frame: the period 4 notched as before (%.3g -> %.3g)' % (name, p4(old_g), p4(new_g)))
                else:
                    check(corr(new_g) >= corr(old_g) - 0.01, '%s, 1 frame: detail as before or more (%.3f -> %.3f)' % (name, corr(old_g), corr(new_g)))
                continue
            if name == 'lattice 4 px':
                check(p4(new_g) <= 1.05 * p4(old_g) + 1e-12, 'lattice 4 px, 30 frames: taken off as before (%.3g -> %.3g)' % (p4(old_g), p4(new_g)))
            elif name == 'flat noisy':
                check(abs(e_new / e_old - 1) < 0.02, 'flat noisy, 30 frames: high-pass noise %.6f -> %.6f (not raised)' % (e_old, e_new))
            elif name == 'beaded line':
                check(np.abs(new_g - old_g).max() < 2e-3 or corr(new_g) >= corr(old_g) - 1e-3,
                      'beaded line, 30 frames: the line kept (transfer %.3f -> %.3f)' % (corr(old_g), corr(new_g)))
            else:
                gain = 0.02 if name.startswith('grooves') else 0.05  # the grooves were 0.82 already off the seam
                check(corr(new_g) > corr(old_g) + gain, '%s, 30 frames: detail transfer %.3f -> %.3f (input 1)' % (name, corr(old_g), corr(new_g)))
            check(Lm(new_g).max() <= Lm(img).max() * 1.0001 + 1e-6 and Lm(new_g).min() >= Lm(img).min() * 0.9999 - 1e-6,
                  '%s, 30 frames: inside the input\'s range (no overshoot)' % name)
    ctx.release()
except ImportError:
    print('moderngl missing: GL part skipped')
    sys.exit(1)
print('fast quad detail check ' + ('FAIL' if fails else 'PASS'))
sys.exit(1 if fails else 0)
