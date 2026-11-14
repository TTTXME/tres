#!/usr/bin/env python3
"""P713: how far two 8-bit photos are apart - the numbers the speed work's checks report and gate on (owner, 2026-11-14:
«на глаз разницы быть не должно»).

compare(a, b) for two H x W x 3 uint8 sRGB images: the max / mean absolute difference in 8-bit levels, the share of channels that
differ, PSNR (dB, over all channels), SSIM of the luma (Gaussian window, sigma 1.5, the usual constants) and CIEDE2000 mean and
99th percentile (sRGB D65 -> CIELAB). Imported by tools/check_hlmask_coarse.py and the other P713 checks.
"""
import numpy as np


def srgb_to_lab(rgb8):
    c = rgb8.astype(np.float64) / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    xyz = lin @ m.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > (6 / 29) ** 3, np.cbrt(xyz), xyz / (3 * (6 / 29) ** 2) + 4 / 29)
    L = 116 * f[..., 1] - 16
    a = 500 * (f[..., 0] - f[..., 1])
    b = 200 * (f[..., 1] - f[..., 2])
    return np.stack([L, a, b], -1)


def ciede2000(lab1, lab2):
    L1, a1, b1 = lab1[..., 0], lab1[..., 1], lab1[..., 2]
    L2, a2, b2 = lab2[..., 0], lab2[..., 1], lab2[..., 2]
    C1, C2 = np.hypot(a1, b1), np.hypot(a2, b2)
    Cb = 0.5 * (C1 + C2)
    G = 0.5 * (1 - np.sqrt(Cb ** 7 / (Cb ** 7 + 25.0 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p = np.degrees(np.arctan2(b1, a1p)) % 360
    h2p = np.degrees(np.arctan2(b2, a2p)) % 360
    dLp = L2 - L1
    dCp = C2p - C1p
    dh = h2p - h1p
    dh = np.where(dh > 180, dh - 360, np.where(dh < -180, dh + 360, dh))
    dh = np.where(C1p * C2p == 0, 0, dh)
    dHp = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dh / 2))
    Lbp = 0.5 * (L1 + L2)
    Cbp = 0.5 * (C1p + C2p)
    hs = h1p + h2p
    hbp = np.where(C1p * C2p == 0, hs,
                   np.where(np.abs(h1p - h2p) <= 180, hs / 2, np.where(hs < 360, (hs + 360) / 2, (hs - 360) / 2)))
    T = (1 - 0.17 * np.cos(np.radians(hbp - 30)) + 0.24 * np.cos(np.radians(2 * hbp))
         + 0.32 * np.cos(np.radians(3 * hbp + 6)) - 0.20 * np.cos(np.radians(4 * hbp - 63)))
    dtheta = 30 * np.exp(-((hbp - 275) / 25) ** 2)
    Rc = 2 * np.sqrt(Cbp ** 7 / (Cbp ** 7 + 25.0 ** 7))
    Sl = 1 + 0.015 * (Lbp - 50) ** 2 / np.sqrt(20 + (Lbp - 50) ** 2)
    Sc = 1 + 0.045 * Cbp
    Sh = 1 + 0.015 * Cbp * T
    Rt = -np.sin(np.radians(2 * dtheta)) * Rc
    return np.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh))


def _blur(x, sigma=1.5):
    r = int(np.ceil(3.5 * sigma))
    g = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    g /= g.sum()
    p = np.pad(x, ((0, 0), (r, r)), mode='reflect')
    x = sum(g[i] * p[:, i:i + x.shape[1]] for i in range(2 * r + 1))
    p = np.pad(x, ((r, r), (0, 0)), mode='reflect')
    return sum(g[i] * p[i:i + x.shape[0], :] for i in range(2 * r + 1))


def ssim_luma(a8, b8):
    w = np.array([0.299, 0.587, 0.114])
    x, y = a8.astype(np.float64) @ w, b8.astype(np.float64) @ w
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    mx, my = _blur(x), _blur(y)
    sxx, syy, sxy = _blur(x * x) - mx * mx, _blur(y * y) - my * my, _blur(x * y) - mx * my
    s = ((2 * mx * my + c1) * (2 * sxy + c2)) / ((mx * mx + my * my + c1) * (sxx + syy + c2))
    return float(s.mean())


def compare(a8, b8):
    a8 = np.asarray(a8)[..., :3]
    b8 = np.asarray(b8)[..., :3]
    d = np.abs(a8.astype(np.int32) - b8.astype(np.int32))
    mse = float((d.astype(np.float64) ** 2).mean())
    psnr = float('inf') if mse == 0 else 10 * np.log10(255.0 ** 2 / mse)
    de = ciede2000(srgb_to_lab(a8), srgb_to_lab(b8))
    return {'max': int(d.max()), 'mean': float(d.mean()), 'changed': float((d > 0).mean()), 'psnr': psnr,
            'ssim': ssim_luma(a8, b8) if mse > 0 else 1.0, 'de_mean': float(de.mean()), 'de_p99': float(np.quantile(de, 0.99)),
            'de_max': float(de.max())}


def describe(m):
    return ('max %d mean %.4f levels, %.3f %% changed, PSNR %s dB, SSIM %.5f, dE2000 mean %.4f p99 %.3f max %.2f'
            % (m['max'], m['mean'], 100 * m['changed'], 'inf' if m['psnr'] == float('inf') else '%.1f' % m['psnr'], m['ssim'],
               m['de_mean'], m['de_p99'], m['de_max']))


def invisible(m, psnr=45.0, ssim=0.995):
    """The owner's bar for a change that is not bit for bit: PSNR >= 45 dB and SSIM >= 0.995 of the 8-bit photo."""
    return m['psnr'] >= psnr and m['ssim'] >= ssim
