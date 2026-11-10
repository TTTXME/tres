"""P596: what a Quad merge resolved, against the truth of tools/quad_synth_burst.py or between merges of a real burst.

  python3 tools/quad_detail_metrics.py truth out.nch merge.f32 [merge2.f32 ...]
      per region (the charts, the natural scene, each moving object, the ground they uncovered): luma / chroma PSNR and the
      transfer T(f) = <out, truth> / <truth, truth> over 64 px blocks in three bands of cycles / px (noise and sharpening
      halos do not raise it: only the truth's own detail counts; > 1 is a boost), the rest's rms (noise + artefacts), the
      slanted edge's 10 - 90 % rise and overshoot, the flat patch's noise and lattice peaks, the neutral bars' false colour.
  python3 tools/quad_detail_metrics.py real meta.ark.json base.f32 new.f32 [...] [--ark ark.png] [--box name:x,y,w,h ...]
      a real burst (or a window of it, cut by tools/nch_crop.py): band energies of 64 px blocks - textured static blocks (the
      base's top 40 % fine band), the 15 % least textured static blocks, moving blocks (coverage under a quarter of its median,
      or inside a --box), their ratio to the least textured, the chroma's, the least textured blocks' lattice peaks; per box.
All in display units: white-balanced, sRGB curve, luma (R + 2 G + B) / 4."""
import json, os, sys
import numpy as np
from scipy import ndimage as nd

BANDS = ((0.06, 0.125), (0.125, 0.25), (0.25, 0.5))


def srgb(x):
    x = np.clip(x, 0, None)
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * x ** (1 / 2.4) - 0.055)


def display(a, m, scale=1.0):
    wb = np.array([m['wb'][0], 0.5 * (m['wb'][1] + m['wb'][2]), m['wb'][3]], np.float32)
    d = srgb(a * wb * scale).astype(np.float32)
    return d, d @ np.array([0.25, 0.5, 0.25], np.float32), np.stack([d[..., 0] - d[..., 1], d[..., 2] - d[..., 1]], -1)


def load(path, m):
    H, W = m['h'], m['w']
    a = np.fromfile(path, np.float32, H * W * 3).reshape(H, W, 3)
    eff = None
    if os.path.getsize(path) >= H * W * 14:
        eff = np.fromfile(path, np.uint8, H * W, offset=H * W * 12).reshape(H, W)
    return a, eff


def blocks(y, box, n=64, step=32):
    x0, y0, w, h = box
    out = []
    for by in range(y0, y0 + h - n + 1, step):
        for bx in range(x0, x0 + w - n + 1, step):
            out.append(y[by:by + n, bx:bx + n])
    return np.array(out)


_win = {}


def spectra(b):
    n = b.shape[-1]
    if n not in _win:
        fr = np.fft.fftfreq(n); fy, fx = np.meshgrid(fr, fr, indexing='ij')
        _win[n] = (np.outer(np.hanning(n), np.hanning(n)).astype(np.float32), np.hypot(fy, fx), fy, fx)
    w = _win[n][0]
    return np.fft.fft2((b - b.mean((-2, -1), keepdims=True)) * w)


def transfer(out, ref, box, mask=None):
    """T per band and the residual's rms per band (display units) over the box's blocks"""
    bo, br = blocks(out, box), blocks(ref, box)
    if mask is not None:
        keep = blocks(mask.astype(np.float32), box).mean((1, 2)) > 0.98
        bo, br = bo[keep], br[keep]
    if len(bo) == 0:
        return [float('nan')] * 3, [float('nan')] * 3, 0
    fo, fr_ = spectra(bo), spectra(br)
    rad = _win[bo.shape[-1]][1]; norm = (_win[bo.shape[-1]][0] ** 2).sum()
    T, R = [], []
    for lo, hi in BANDS:
        k = (rad >= lo) & (rad < hi)
        t = (fo[:, k] * np.conj(fr_[:, k])).real.sum() / max((np.abs(fr_[:, k]) ** 2).sum(), 1e-20)
        res = fo[:, k] - t * fr_[:, k]
        T.append(float(t)); R.append(float(np.sqrt((np.abs(res) ** 2).mean() / norm)))
    return T, R, len(bo)


def psnr(a, b, mask):
    d = (a - b)[mask]
    return float(-10 * np.log10(max(np.mean(d * d), 1e-12)))


def edge_profile(y, box, angle_deg, origin, bin_=0.25, reach=8.0):
    """the super-resolved edge spread across a straight edge through origin (x, y); 10 - 90 % rise (px), overshoot (share of the step)"""
    x0, y0, w, h = box
    yy, xx = np.mgrid[y0:y0 + h, x0:x0 + w].astype(np.float32)
    a = np.deg2rad(angle_deg)
    d = (xx - origin[0]) * np.cos(a) + (yy - origin[1]) * np.sin(a)
    v = y[y0:y0 + h, x0:x0 + w]
    k = np.abs(d) < reach
    idx = np.floor((d[k] + reach) / bin_).astype(int)
    nb = int(2 * reach / bin_)
    esf = np.bincount(idx, v[k], nb) / np.maximum(np.bincount(idx, None, nb), 1)
    return rise(esf, bin_)


def rise(esf, bin_):
    n = len(esf)
    lo, hi = np.median(esf[:n // 5]), np.median(esf[-n // 5:])
    if hi < lo:
        esf = esf[::-1]; lo, hi = hi, lo
    step = max(hi - lo, 1e-6)
    t = (esf - lo) / step
    x = np.arange(n) * bin_
    c = np.maximum.accumulate(t)
    x10 = np.interp(0.1, c, x); x90 = np.interp(0.9, c, x)
    return float(x90 - x10), float(max(t.max() - 1, -t.min(), 0))


def lattice(y, box):
    """peaks at the Quad lattice's frequencies over the background around them (flat area): period 4 (x, y, diagonal), period 2"""
    b = blocks(y, box)
    p = (np.abs(spectra(b)) ** 2).mean(0)
    n = p.shape[0]; fy, fx = _win[n][2], _win[n][3]
    out = {}
    for name, (u, v) in dict(x4=(0.25, 0), y4=(0, 0.25), d4=(0.25, 0.25), x2=(0.5, 0), y2=(0, 0.5), d2=(0.5, 0.5)).items():
        peak = (np.abs(np.abs(fx) - u) < 0.6 / n) & (np.abs(np.abs(fy) - v) < 0.6 / n)
        ring = (np.abs(np.abs(fx) - u) < 4.5 / n) & (np.abs(np.abs(fy) - v) < 4.5 / n) & ~((np.abs(np.abs(fx) - u) < 1.6 / n) & (np.abs(np.abs(fy) - v) < 1.6 / n))
        out[name] = float(p[peak].mean() / max(np.median(p[ring]), 1e-20))
    return out


def noise_bands(y, box):
    b = blocks(y, box)
    p = np.abs(spectra(b)) ** 2
    rad = _win[b.shape[-1]][1]; norm = (_win[b.shape[-1]][0] ** 2).sum()
    return [float(np.sqrt(p[:, (rad >= lo) & (rad < hi)].mean() / norm)) for lo, hi in BANDS]


def truth_mode(nch, merges):
    pre = os.path.splitext(nch)[0]
    m = json.load(open(pre + '.ark.json')); lay = json.load(open(pre + '.layout.json'))
    truth = np.load(pre + '.truth.npy')
    H, W = m['h'], m['w']
    _, ty, tc = display(truth, m)
    ch = lay['charts']; top = max(c[1] + c[3] for c in ch.values())
    inner = lambda b, k=10: [b[0] + k, b[1] + k, b[2] - 2 * k, b[3] - 2 * k]
    nfr = len(lay['shifts'])
    static = np.zeros((H, W), bool); static[top + 16:H - 48, 48:W - 48] = True
    objm, trailm = [], []
    for o in lay['objects']:
        x, y, w, h = o['box']; vx, vy = o['v']
        om = np.zeros((H, W), bool); om[y + 6:y + h - 6, x + 6:x + w - 6] = True
        tm = np.zeros((H, W), bool)
        for k in range(1, nfr):
            xx, yy = int(round(x - vx * k)), int(round(y - vy * k))
            tm[max(yy, 0):max(yy + h, 0), max(xx, 0):max(xx + w, 0)] = True
        big = np.zeros((H, W), bool); big[max(y - 8, 0):y + h + 8, max(x - 8, 0):x + w + 8] = True
        tm &= ~big
        rim = big & ~nd.binary_erosion(om, iterations=2)
        objm.append((om, rim, [x, y, w, h])); trailm.append(tm)
        static &= ~(nd.binary_dilation(tm, iterations=8) | big)
    truth0 = truth
    for path in merges:
        a, eff = load(path, m)
        # the «Качество» native merge samples half a pixel off the sites (its output pixel X is the scene at X - 0.5): the truth moved the same way
        errs = []
        for sh in (0.0, 0.5):
            t = truth0 if sh == 0 else np.stack([nd.shift(truth0[..., k], (sh, sh), order=3, mode='nearest') for k in range(3)], -1)
            errs.append((float(((a - t)[static] ** 2).mean()), sh, t))
        _, sh, truth = min(errs, key=lambda e: e[0])
        _, ty, tc = display(truth, m)
        _, y, c = display(a, m)
        print('== %s (grid offset %.1f px)' % (path, sh))
        row = lambda name, mask, box: print('  %-9s luma %6.2f dB chroma %6.2f dB | T %s | rest %s | blocks %d' % (
            (name, psnr(y, ty, mask), psnr(c, tc, mask)) + (lambda t: (' '.join('%.3f' % v for v in t[0]), ' '.join('%.5f' % v for v in t[1]), t[2]))(transfer(y, ty, box, mask))))
        full = np.ones((H, W), bool)
        for name in ('zone', 'tex2', 'tex5', 'bars'):
            b = inner(ch[name]); mk = np.zeros((H, W), bool); mk[b[1]:b[1] + b[3], b[0]:b[0] + b[2]] = True
            row(name, mk, b)
        row('natural', static, [0, top, W, H - top])
        for k, ((om, rim, box), tm) in enumerate(zip(objm, trailm)):
            row('object%d' % k, om, box)
            ys, xs = np.nonzero(tm); tb = [xs.min(), ys.min(), xs.max() - xs.min() + 1, ys.max() - ys.min() + 1]
            row('trail%d' % k, tm, tb)
            print('  rim%d      luma %6.2f dB chroma %6.2f dB (the object\'s outline, +-8 px)' % (k, psnr(y, ty, rim), psnr(c, tc, rim)))
            if eff is not None:
                print('  eff code  object %.1f trail %.1f static %.1f' % (eff[om].mean(), eff[tm].mean(), eff[static].mean()))
        e = ch['edge']; hb = [e[0] + 24, e[1] + 24, e[2] - 48, e[3] // 2 - 48]; lb = [e[0] + 24, e[1] + e[3] // 2 + 24, e[2] - 48, e[3] // 2 - 48]
        org = (e[0] + (e[2] - 1) / 2 + sh, e[1] + (e[3] - 1) / 2 + sh)
        r1, o1 = edge_profile(y, hb, 5.0, org); r0, o0 = edge_profile(ty, hb, 5.0, org)
        r2, o2 = edge_profile(y, lb, -5.0, org); r3, o3 = edge_profile(ty, lb, -5.0, org)
        print('  edge      rise %.2f px (truth %.2f) overshoot %.3f (truth %.3f); low contrast: rise %.2f (truth %.2f) overshoot %.3f (truth %.3f)' % (r1, r0, o1, o0, r2, r3, o2, o3))
        f = inner(ch['flat'], 16); fm = np.zeros((H, W), bool); fm[f[1]:f[1] + f[3], f[0]:f[0] + f[2]] = True
        print('  flat      noise rms %.5f bands %s | lattice %s' % (float((y - ty)[fm].std()), ' '.join('%.5f' % v for v in noise_bands(y - ty, f)),
              ' '.join('%s %.1f' % kv for kv in lattice(y - ty, f).items())))
        b = inner(ch['bars']); print('  bars      false colour rms %.5f' % float(np.sqrt((c[b[1]:b[1] + b[3], b[0]:b[0] + b[2]] ** 2).mean())))


def real_mode(args):
    """args: meta.json base.f32 new.f32 [...] [--ark ark.png] [--box name:x,y,w,h]: band energies of a real burst's merges"""
    boxes, ark, paths = {}, None, []
    it = iter(args)
    for a in it:
        if a == '--ark': ark = next(it)
        elif a == '--box':
            n, b = next(it).split(':'); boxes[n] = list(map(int, b.split(',')))
        else: paths.append(a)
    m = json.load(open(paths[0])); runs = paths[1:]
    H, W = m['h'], m['w']
    base, eff0 = load(runs[0], m)
    sc = 1.0 / max(float(np.percentile(display(base, m)[1], 99.5)), 1e-6)
    ys = []
    for r in runs:
        a, eff = load(r, m)
        d, y, c = display(a, m)
        ys.append((r, y, c, eff))
    if ark:
        from PIL import Image
        k = np.asarray(Image.open(ark).convert('RGB'), np.float32) / 255
        k = np.where(k <= 0.04045, k / 12.92, ((k + 0.055) / 1.055) ** 2.4)  # Ark's JPEG back to linear, then our display curve
        ky = srgb(k) @ np.array([0.25, 0.5, 0.25], np.float32)
        kc = np.stack([srgb(k)[..., 0] - srgb(k)[..., 1], srgb(k)[..., 2] - srgb(k)[..., 1]], -1)
        ys.append(('ark', ky, kc, None))
    n = 64
    by, bx = np.mgrid[16:H - n - 16:n, 16:W - n - 16:n]
    def bandE(y, kind='luma'):
        out = np.zeros((by.size, 3))
        b = np.array([y[j:j + n, i:i + n] for j, i in zip(by.ravel(), bx.ravel())])
        p = np.abs(spectra(b)) ** 2
        rad = _win[n][1]; norm = (_win[n][0] ** 2).sum()
        for k, (lo, hi) in enumerate(BANDS):
            out[:, k] = np.sqrt(p[:, (rad >= lo) & (rad < hi)].mean(1) / norm)
        return out
    E0 = bandE(ys[0][1])
    # the blocks: flat = the 15 % lowest mid band of the base; textured = the 40 % highest fine band; static = the base's coverage
    # at least half its median, moving = under a quarter (or inside a --box)
    effb = np.array([eff0[j:j + n, i:i + n].mean() for j, i in zip(by.ravel(), bx.ravel())]) if eff0 is not None else np.ones(by.size)
    inbox = np.zeros(by.size, bool)
    for b in boxes.values():
        inbox |= (bx.ravel() + n > b[0]) & (bx.ravel() < b[0] + b[2]) & (by.ravel() + n > b[1]) & (by.ravel() < b[1] + b[3])
    static = (effb >= 0.5 * np.median(effb)) & ~inbox
    flat = static & (E0[:, 1] <= np.percentile(E0[static, 1], 15))
    tex = static & (E0[:, 2] >= np.percentile(E0[static, 2], 60))
    moving = (effb < 0.25 * np.median(effb)) | inbox
    print('blocks: static %d (flat %d, textured %d), moving %d' % (static.sum(), flat.sum(), tex.sum(), moving.sum()))
    for r, y, c, eff in ys:
        E = bandE(y)
        cE = bandE(c[..., 0]) + bandE(c[..., 1])
        snr = lambda sel: [float(np.median(E[sel, k]) / max(np.median(E[flat, k]), 1e-9)) for k in range(3)]
        line = '%-34s tex/flat %s | tex %s | flat %s | moving %s | chroma tex %s' % (
            os.path.basename(r)[:34], ' '.join('%.2f' % v for v in snr(tex)),
            ' '.join('%.5f' % np.median(E[tex, k]) for k in range(3)), ' '.join('%.5f' % np.median(E[flat, k]) for k in range(3)),
            ' '.join('%.5f' % (np.median(E[moving, k]) if moving.any() else float('nan')) for k in range(3)),
            ' '.join('%.5f' % np.median(cE[tex, k]) for k in range(3)))
        print(line)
        if r != 'ark':
            fl = np.zeros((H, W), bool)
            for j, i in zip(by.ravel()[flat], bx.ravel()[flat]): fl[j:j + n, i:i + n] = True
            lat = [lattice(y, [i, j, n, n]) for j, i in zip(by.ravel()[flat][:40], bx.ravel()[flat][:40])]
            print('    flat lattice (median of %d blocks) %s' % (len(lat), ' '.join('%s %.2f' % (k, np.median([l[k] for l in lat])) for k in lat[0])))
        for name, b in boxes.items():
            sel = (bx.ravel() >= b[0]) & (bx.ravel() + n <= b[0] + b[2]) & (by.ravel() >= b[1]) & (by.ravel() + n <= b[1] + b[3])
            if sel.any():
                print('    box %-8s bands %s' % (name, ' '.join('%.5f' % np.median(E[sel, k]) for k in range(3))))


if __name__ == '__main__':
    if len(sys.argv) >= 4 and sys.argv[1] == 'truth':
        truth_mode(sys.argv[2], sys.argv[3:])
    elif len(sys.argv) >= 5 and sys.argv[1] == 'real':
        real_mode(sys.argv[2:])
    else:
        sys.exit(__doc__)
