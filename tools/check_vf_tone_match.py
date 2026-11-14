#!/usr/bin/env python3
"""P711: how close the RAW viewfinder's tone is to the photo's WHY tone, on the host GPU (moderngl), the same RAW frame for both.

The viewfinder (LiveWhyTone vf_why 1, the default two passes): preview/rawdevelop_fs.glsl u_linear_out 1 at the tone size (here the
viewport: a 1x Bayer frame's colour grid is above it), then why/combine.glsl POINT_FUSED 1 with the fusion's per-pixel curve
(FusedCurve.fill: why/gf fusedAt, the P291 envelope, the deep floor) into an 8-bit target - as LiveRawRenderer / LiveWhyTone draw it.

The photo of the same frame (no merge, «Why тон» V1 as PostPipeline runs it for one frame): the frame demosaiced at full size
(bilinear; black, white, white balance with rawdevelop's highlight fade, the colour matrix: the merge's linear RGB stands in),
whyLow its 2 x 2 box (WhyTone.lowFactor 2), WhyFusion's Laplacian pyramid with the P391 edge-aware bands and the continuous depth
(tools/check_why_pyramid_bands.fuse, WhyTone's shipped band values) at half scale (whyLow 1024 x 768 of a 12 MP frame: coarse 24),
then why/combine POINT_FUSED 0 at full size (fU 2), area-reduced to the viewfinder's size.

Both take the same AE result (exposure, the four fusion exposures of the reference layout, fusion weights) and the same combine
uniforms and defines (the shipped defaults: V2_COLOUR, WHY_BASE, MACRO_TOE, the own curve TONE_OP 1 with the setting's points,
WHY_VIBRANCE), no dither, no sharpening / merge detail (sharpU 0, detailGainU 0: the photo's detail is not tone) and no merge-only
stages (Bento headroom, highlight landing, edge hold, sun bloom, sky term). The AE meters themselves (LiveWhyMeter's cells vs
WhyStats' whyLow) are compared by VfAeToneMatchTest; this check measures the tone path at an equal AE.

Scenes: synthetic HDR Bayer frames (RGGB, 2048 x 1536, a colour matrix and white balance): a backlit room with a window, daylight
with sky and foliage, a night street with lamps, a sunlit wall with a dark doorway (6 - 7 EV hard edges: the pyramid's halo case),
a flat indoor scene. The photo takes the scene's frame without sensor noise (a merge's limit); the viewfinder that frame (the tone
path itself) and a frame of the scene with sensor noise (what it shows). Compared on 8 x 8 block means of the viewfinder's size
(the tone, not a frame's noise or the merge's detail): mean / p95 CIEDE2000, mean L* difference (global, in the photo's shadows
L* < 30, mid-tones, lights L* > 70), the local contrast between 1 and 5 blocks (std of L* less its 5 x 5 block box mean).

Measured (P711, the shipped viewfinder): the same frame dE00 mean 0.52 - 1.06, p95 1.37 - 2.60, dL* +0.07 .. +0.37, local contrast
0.99 - 1.01 of the photo's; the noisy frame dE00 mean 1.18 - 4.03 (the night street: the frame's chroma noise), dL* within +0.2.
Pass (a regression gate with margin): the same frame mean dE00 <= MEAN_DE, p95 <= P95_DE, |dL*| <= MEAN_DL, the shadows' |dL*|
<= SHADOW_DL, the local contrast within LC_RANGE; the noisy frame within the NOISY_* bounds.

usage: python3 tools/check_vf_tone_match.py [--save DIR]   (numpy, moderngl; LIBGL_ALWAYS_SOFTWARE=1 on a headless Linux)
"""
from __future__ import annotations

import math
import os
import re
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_vf_fused as cvf  # noqa: E402
import check_why_pyramid as cwp  # noqa: E402
import check_why_pyramid_bands as cwb  # noqa: E402
from check_tone_ops import curve_samples  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
JAVA = ROOT / 'app/src/main/java/com/scamera/app'
SAVE = sys.argv[sys.argv.index('--save') + 1] if '--save' in sys.argv else None
FAILURES: list[str] = []
LUMA = np.array([0.2126, 0.7152, 0.0722])

# the gate (the shipped viewfinder's measured values with margin; see the module doc)
# the tone path (the viewfinder's tone of the photo's frame): CIEDE2000 mean / p95, |dL*| mean / in the shadows, local contrast
MEAN_DE, P95_DE, MEAN_DL, SHADOW_DL, LC_RANGE = 1.5, 3.5, 1.0, 1.5, (0.9, 1.1)
# the viewfinder's own noisy frame against the photo (a merge): its noise is not the tone's; a looser gate
NOISY_MEAN_DE, NOISY_P95_DE, NOISY_MEAN_DL = 5.0, 10.0, 1.5

H, W = 1536, 2048          # the RAW frame (half of a 12 MP frame's sides)
VP = (960, 720)            # the viewport (landscape; the colour grid 1024 x 768 is above it: the tone is the viewport)
BLACK, WHITE = 64.0, 1023.0
WB = (2.0, 1.0, 1.6)       # white-balance gains (the camera's response: 1 / WB)
CCM = np.array([[1.62, -0.48, -0.14], [-0.21, 1.45, -0.24], [0.02, -0.55, 1.53]])


def check(cond: bool, msg: str) -> None:
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


# ------------------------------------------------------------------------------------------------ shipped defaults

_WHY_SRC = None


def why_value(key: str) -> float:
    """A WHY tone default value("key", x) from the postpipeline sources (WhyTone, LiveWhyTone, WhyStats ...)."""
    global _WHY_SRC
    if _WHY_SRC is None:
        _WHY_SRC = '\n'.join(p.read_text(encoding='utf-8') for p in sorted((JAVA / 'processing/opengl/postpipeline').glob('*.java')))
    m = re.search(r'value\("%s", ([-\d.]+)f\)' % re.escape(key), _WHY_SRC)
    if not m:
        raise SystemExit('no default for %s' % key)
    return float(m.group(1))


def setting_points() -> list:
    src = (JAVA / 'processing/color/ToneCurve.java').read_text(encoding='utf-8')
    pts = re.search(r'SETTING_POINTS = "([^"]+)"', src).group(1)
    return [tuple(float(v) for v in p.split(':')) for p in pts.split(',')]


def fusion_settings() -> dict:
    """The reference layout's fusion (WhyTone.whyLayout default): layer weights, centre, smoothness, gamma, toe, mono, deep floor."""
    return {'lw': (why_value('classic_weight_ext_hl'), why_value('classic_weight_mid'), why_value('classic_weight_hl'),
                   why_value('classic_weight_shadow')),
            'center': why_value('classic_weight_center'), 'smooth': why_value('blend_smoothness'), 'gammaInv': 1 / why_value('gamma'),
            'toe': why_value('aces_toe'), 'mono': why_value('fusion_mono'), 'deepFloor': why_value('deep_floor')}


def exposures207(eff_s: float, eff_h: float) -> tuple:
    """WhyFusion.exposures207 (the viewfinder's liveExposures and the photo's em in the reference layout)."""
    return (2.0 ** (-2.5 * eff_h), 1.0, 2.0 ** (-1.2 * eff_h), min(2.0 ** (1.8 * eff_s), 16.0))


# ------------------------------------------------------------------------------------------------ FusedCurve (Java port)

def acescg(v, toe):
    x = np.maximum(v, 0.0) * 1.5
    b, e = max(toe * 0.75, 0.005), max(0.14 - (toe - 0.04) * 0.8, 0.03)
    return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e)


def fused_at(i_, em, lw, c, s, gi, t, deep_floor):
    """FusedCurve.fusedAt (GAMMA_TYPE 0, no noise term)."""
    var = max(s * s, 0.001)
    l_ = [np.maximum(acescg(i_ * em[k], t), 1e-6) ** gi for k in range(4)]
    w = [np.exp(-0.5 * (l_[k] - c) ** 2 / var) * lw[k] + 1e-6 for k in range(4)]
    if deep_floor > 0:
        wf = math.exp(-0.5 * deep_floor * deep_floor) * lw[3] + 1e-6
        w[3] = np.where(l_[3] < c, np.maximum(w[3], wf), w[3])
    return sum(w[k] * l_[k] for k in range(4)) / sum(w)


def fused_curve(em, f) -> np.ndarray:
    """FusedCurve.fill with the envelope (mono) and the deep floor, as LiveWhyTone.fillCurve."""
    i_ = 2.0 ** (cvf.LOG2_MIN + np.arange(cvf.SIZE) / cvf.PER_EV)
    f0 = fused_at(i_, em, f['lw'], f['center'], f['smooth'], f['gammaInv'], f['toe'], f['deepFloor'])
    out = f0.copy()
    if f['mono'] > 0:
        step, quarter = cvf.PER_EV // 4, 0.25 * math.log(2.0)
        for i in range(cvf.SIZE):
            env = f0[i]
            for j in range(1, 37):
                k = i - j * step
                if k < 0:
                    break
                if f0[k] >= 0.5:
                    env = max(env, f0[k] + f['mono'] * j * quarter)
            out[i] = env
    return out.astype(np.float32)


# ------------------------------------------------------------------------------------------------ scenes

def noise_field(rng, h, w, scales=(4, 8, 16, 32), amp=0.35):
    """A log-normal texture field (EV), sum of upsampled random grids."""
    acc = np.zeros((h, w))
    for s in scales:
        g = rng.normal(0, 1, (h // s + 2, w // s + 2))
        acc += np.kron(g, np.ones((s, s)))[:h, :w] * amp / len(scales) ** 0.5
    return acc


def scene(name: str, rng) -> np.ndarray:
    """Linear scene light (linear sRGB; 1 = the sensor's white after the camera's exposure; > 1 clips)."""
    y, x = np.mgrid[0:H, 0:W] / np.array([H, W], float)[:, None, None]
    img = np.zeros((H, W, 3))
    if name == 'backlit room':
        tex = 2.0 ** noise_field(rng, H, W, amp=0.5)
        img[:] = np.array([0.055, 0.045, 0.035])[None, None] * tex[..., None] * (0.7 + 0.6 * y[..., None])
        sofa = (x > 0.08) & (x < 0.45) & (y > 0.55) & (y < 0.85)
        img[sofa] = np.array([0.06, 0.025, 0.02]) * (2.0 ** noise_field(rng, H, W, (2, 4), 0.4))[sofa][:, None]
        win = (x > 0.55) & (x < 0.9) & (y > 0.12) & (y < 0.62)
        sky = np.stack([0.42 + 0.3 * y, 0.55 + 0.3 * y, 0.8 + 0.2 * y], -1) * (2.0 ** noise_field(rng, H, W, (16, 32, 64), 0.6))[..., None]
        img[win] = sky[win]
        bld = win & (y > 0.4) & ((x * 40).astype(int) % 5 != 0)
        img[bld] = np.array([0.35, 0.33, 0.3]) * (2.0 ** noise_field(rng, H, W, (2, 4), 0.3))[bld][:, None]
        frame = win & ((np.abs(x - 0.725) < 0.006) | (np.abs(y - 0.37) < 0.006))
        img[frame] = 0.02
        lamp = (x - 0.2) ** 2 + ((y - 0.25) * 0.75) ** 2 < 0.0009
        img[lamp] = np.array([5.0, 4.2, 3.0])
    elif name == 'daylight':
        img[:] = np.stack([0.32 + 0.2 * y, 0.45 + 0.2 * y, 0.75 + 0.1 * y], -1)
        clouds = np.clip(noise_field(rng, H, W, (32, 64, 128), 1.0), 0, None)
        img += clouds[..., None] * np.array([0.35, 0.3, 0.15])
        ground = y > 0.42
        grass = np.array([0.07, 0.14, 0.04])[None] * (2.0 ** noise_field(rng, H, W, (1, 2, 4, 8), 0.7))[ground][:, None]
        img[ground] = grass
        shade = ground & (x > 0.05) & (x < 0.4) & (y > 0.5)
        img[shade] *= 0.18
        wall = (x > 0.55) & (x < 0.8) & (y > 0.3) & (y < 0.75)
        img[wall] = np.array([0.72, 0.68, 0.62]) * (1 + 0.05 * noise_field(rng, H, W, (2, 4), 1.0))[wall][:, None]
        cols = np.array([[0.6, 0.08, 0.05], [0.05, 0.3, 0.6], [0.7, 0.55, 0.05], [0.45, 0.25, 0.18]])
        for i, c in enumerate(cols):
            p = (x > 0.58 + 0.05 * i) & (x < 0.62 + 0.05 * i) & (y > 0.8) & (y < 0.9)
            img[p] = c
    elif name == 'night street':
        img[:] = np.array([0.004, 0.0045, 0.006]) * (2.0 ** noise_field(rng, H, W, amp=0.5))[..., None]
        for lx, ly in ((0.2, 0.3), (0.5, 0.25), (0.8, 0.32)):
            r2 = (x - lx) ** 2 + ((y - ly) * 0.75) ** 2
            img += (0.003 / (r2 + 0.004))[..., None] * np.array([1.0, 0.75, 0.45]) * 0.05
            img[r2 < 0.0004] = np.array([25.0, 20.0, 14.0])
        road = y > 0.6
        img[road] += (np.array([0.05, 0.04, 0.03])[None] * (1.2 - y[road, None]) * (2.0 ** noise_field(rng, H, W, (1, 2), 0.4))[road][:, None])
        for wx in (0.3, 0.38, 0.62):
            p = (x > wx) & (x < wx + 0.04) & (y > 0.4) & (y < 0.5)
            img[p] = np.array([0.22, 0.15, 0.06])
    elif name == 'doorway':
        # a sunlit wall with a dark doorway (6 - 7 EV below it) and a figure inside: large, hard edges - the pyramid's halo case
        img[:] = np.array([0.62, 0.55, 0.45]) * (2.0 ** noise_field(rng, H, W, (2, 4, 8), 0.25))[..., None]
        door = (x > 0.3) & (x < 0.62) & (y > 0.2) & (y < 0.95)
        img[door] = np.array([0.006, 0.0055, 0.005]) * (2.0 ** noise_field(rng, H, W, (4, 8, 16), 0.5))[door][:, None]
        fig = door & ((x - 0.46) ** 2 / 0.004 + (y - 0.62) ** 2 / 0.05 < 1)
        img[fig] = np.array([0.05, 0.035, 0.03]) * (2.0 ** noise_field(rng, H, W, (2, 4), 0.4))[fig][:, None]
        step = (y > 0.85) & ~door
        img[step] *= 0.3
    else:  # indoor flat
        img[:] = np.array([0.16, 0.15, 0.13]) * (2.0 ** noise_field(rng, H, W, amp=0.3))[..., None] * (0.8 + 0.4 * x[..., None])
        cols = np.array([[0.28, 0.12, 0.08], [0.45, 0.28, 0.2], [0.1, 0.16, 0.3], [0.12, 0.22, 0.08], [0.32, 0.3, 0.06],
                         [0.25, 0.08, 0.2], [0.05, 0.05, 0.05], [0.5, 0.5, 0.5], [0.36, 0.22, 0.16], [0.08, 0.2, 0.22]])
        for i, c in enumerate(cols):
            px, py = i % 5, i // 5
            p = (x > 0.1 + 0.16 * px) & (x < 0.22 + 0.16 * px) & (y > 0.25 + 0.3 * py) & (y < 0.47 + 0.3 * py)
            img[p] = c
    return np.maximum(img, 0.0)


def mosaic(img: np.ndarray, rng, noise: bool = True) -> np.ndarray:
    """The RGGB RAW of the scene: camera RGB = CCM^-1 x light (no negative light), the camera's response 1 / WB, black 64, white
    1023, shot and read noise (noise False: the merge's limit, the photo's frame)."""
    cam = np.maximum(np.einsum('ij,hwj->hwi', np.linalg.inv(CCM), img), 0.0) / np.array(WB)
    yy, xx = np.mgrid[0:H, 0:W]
    site = (yy % 2) * 2 + xx % 2
    chan = np.choose(site, [0, 1, 1, 2])
    v = np.take_along_axis(cam, chan[..., None], 2)[..., 0] * (WHITE - BLACK)
    if noise:
        v = v + rng.normal(0, 1, v.shape) * np.sqrt(0.8 * np.maximum(v, 0) + 4.0)
    return np.clip(np.round(BLACK + v), 0, WHITE).astype(np.uint16)


def photo_linear(raw: np.ndarray) -> np.ndarray:
    """The photo's linear RGB stand-in: a bilinear demosaic of the frame, normalised, white-balanced with rawdevelop's highlight
    fade (apply_wb_and_hl_clip of the pixel's camera RGB), the colour matrix."""
    v = np.maximum((raw.astype(np.float64) - BLACK) / (WHITE - BLACK), 0.0)
    yy, xx = np.mgrid[0:H, 0:W]
    masks = [(yy % 2 == 0) & (xx % 2 == 0), ((yy + xx) % 2 == 1), (yy % 2 == 1) & (xx % 2 == 1)]
    out = np.zeros((H, W, 3))
    k = np.array([[1, 2, 1], [2, 4, 2], [1, 2, 1]], float)
    for c, m in enumerate(masks):
        s = np.pad(v * m, 1, mode='reflect')
        n = np.pad(m.astype(float), 1, mode='reflect')
        num = sum(k[dy, dx] * s[dy:dy + H, dx:dx + W] for dy in range(3) for dx in range(3))
        den = sum(k[dy, dx] * n[dy:dy + H, dx:dx + W] for dy in range(3) for dx in range(3))
        out[..., c] = np.where(m, v, num / np.maximum(den, 1e-9))
    raw_max = out.max(-1, keepdims=True)
    c = out * np.array(WB)
    fade = np.clip((raw_max - 0.85) / (0.98 - 0.85), 0, 1)
    fade = fade * fade * (3 - 2 * fade)
    c = c + (c.max(-1, keepdims=True) - c) * fade
    return np.einsum('ij,hwj->hwi', CCM, c).astype(np.float32)


# ------------------------------------------------------------------------------------------------ colour metrics

def srgb_to_lab(img8: np.ndarray) -> np.ndarray:
    c = img8.astype(np.float64) / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124564, 0.3575761, 0.1804375], [0.2126729, 0.7151522, 0.0721750], [0.0193339, 0.1191920, 0.9503041]])
    xyz = np.einsum('ij,hwj->hwi', m, lin) / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 216 / 24389, np.cbrt(xyz), (24389 / 27 * xyz + 16) / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


def de2000(lab1: np.ndarray, lab2: np.ndarray) -> np.ndarray:
    L1, a1, b1 = lab1[..., 0], lab1[..., 1], lab1[..., 2]
    L2, a2, b2 = lab2[..., 0], lab2[..., 1], lab2[..., 2]
    C1, C2 = np.hypot(a1, b1), np.hypot(a2, b2)
    Cm = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cm ** 7 / (Cm ** 7 + 25.0 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p, h2p = np.degrees(np.arctan2(b1, a1p)) % 360, np.degrees(np.arctan2(b2, a2p)) % 360
    dLp, dCp = L2 - L1, C2p - C1p
    dhp = h2p - h1p
    dhp = np.where(dhp > 180, dhp - 360, np.where(dhp < -180, dhp + 360, dhp))
    dhp = np.where(C1p * C2p == 0, 0, dhp)
    dHp = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dhp) / 2)
    Lm, Cmp = (L1 + L2) / 2, (C1p + C2p) / 2
    hs = h1p + h2p
    hm = np.where(C1p * C2p == 0, hs, np.where(np.abs(h1p - h2p) <= 180, hs / 2, np.where(hs < 360, (hs + 360) / 2, (hs - 360) / 2)))
    T = (1 - 0.17 * np.cos(np.radians(hm - 30)) + 0.24 * np.cos(np.radians(2 * hm)) + 0.32 * np.cos(np.radians(3 * hm + 6))
         - 0.20 * np.cos(np.radians(4 * hm - 63)))
    dth = 30 * np.exp(-(((hm - 275) / 25) ** 2))
    Rc = 2 * np.sqrt(Cmp ** 7 / (Cmp ** 7 + 25.0 ** 7))
    Sl = 1 + 0.015 * (Lm - 50) ** 2 / np.sqrt(20 + (Lm - 50) ** 2)
    Sc, Sh = 1 + 0.045 * Cmp, 1 + 0.015 * Cmp * T
    Rt = -np.sin(np.radians(2 * dth)) * Rc
    return np.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh))


def box_blur(a: np.ndarray, r: int) -> np.ndarray:
    k = 2 * r + 1
    p = np.pad(a, r, mode='edge')
    c = np.cumsum(np.cumsum(np.pad(p, ((1, 0), (1, 0))), 0), 1)
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def local_contrast(L: np.ndarray, r: int) -> float:
    return float(np.std(L - box_blur(L, r)))


def area_reduce(img: np.ndarray, w: int, h: int) -> np.ndarray:
    """An exact area average of img (H x W x C) onto w x h (the screen's scale of the photo)."""
    def axis(n_in, n_out):
        m = np.zeros((n_out, n_in))
        s = n_in / n_out
        for o in range(n_out):
            a, b = o * s, (o + 1) * s
            for i in range(int(a), min(n_in, int(math.ceil(b)))):
                m[o, i] = min(b, i + 1) - max(a, i)
        return m / s
    my, mx = axis(img.shape[0], h), axis(img.shape[1], w)
    return np.einsum('oh,hwc->owc', my, np.einsum('pw,hwc->hpc', mx, img.astype(np.float64)))


# ------------------------------------------------------------------------------------------------ GPU

class Renderer:
    def __init__(self, vf_defines=()):
        self.gl = cwb.Gl()
        self.ctx = self.gl.ctx
        combine = cvf.read(SH / 'why/combine.glsl')
        self.defines = (('V2_COLOUR', '1'), ('WHY_BASE', '1'), ('MACRO_TOE', '1'), ('TONE_OP', '1'), ('WHY_VIBRANCE', '1'))
        vs_raw = cvf.read(SH / 'preview/rawdevelop_vs.glsl')
        vs_full = '#version 310 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
        self.prog_lin = self.ctx.program(vertex_shader=vs_raw, fragment_shader=cvf.read(SH / 'preview/rawdevelop_fs.glsl'))
        self.prog_vf = self.ctx.program(vertex_shader=vs_full, fragment_shader=cvf.read_program(
            combine, (('POINT_FUSED', '1'),) + self.defines + tuple(vf_defines)))
        self.prog_photo = self.ctx.program(vertex_shader=vs_full, fragment_shader=cvf.read_program(combine, self.defines))
        self.pos = self.ctx.buffer(np.array([1, -1, -1, -1, 1, 1, -1, 1], np.float32).tobytes())
        self.uv = self.ctx.buffer(np.array([1, 1, 0, 1, 1, 0, 0, 0], np.float32).tobytes())
        self.vao_lin = self.ctx.vertex_array(self.prog_lin, [(self.pos, '2f', 'vPosition'), (self.uv, '2f', 'vTexCoord')])
        self.ones = self.ctx.texture((2, 2), 4, np.ones(16, np.float32).astype(np.float16).tobytes(), dtype='f2')
        self.t_lsc = self.ctx.texture((1, 1), 3, np.ones(3, np.float32).astype(np.float16).tobytes(), dtype='f2')
        curve = curve_samples(setting_points())
        self.common = {
            'sensorToIntermediate': tuple(np.eye(3).flatten()), 'intermediateToSRGB': tuple(np.eye(3).flatten()),
            'neutralPointU': (1.0, 1.0, 1.0), 'inScaleU': 1.0, 'signedColourU': 0, 'signedHueU': 0, 'clipU': 1.0,
            'toeU': why_value('aces_toe'), 'gammaInvU': 1 / why_value('gamma'), 'macroU': why_value('macro_contrast'),
            'vibU': (why_value('vibrance'), why_value('vibrance_sky'), why_value('vibrance_green')), 'chromaDnU': 0.0,
            'clarityU': 0.0, 'flatProtectU': 0.0, 'detailGainU': 0.0, 'detailRefU': 0, 'sharpU': 0, 'sharpToneU': 1.0,
            'deltaChromaU': 0.0, 'filmToeU': 0.0, 'ditherU': 0, 'headroomU': 0, 'hlWhiteU': 0.0, 'toneOpU': 3,
            'curveBoostU': 0.0, 'vibHlU': 1.0, 'vibHlShapeU': (0.57, 0.89, 0.010, 0.025),
            'vibWhyU': (0.0, 0.25, 0.0, 0.0), 'vibSkinU': (1.0, math.radians(41.2), 0.16, 0.0), 'vibSkinHueU': 0.0,
            'insetU': 0.5, 'GainMap': 7}
        self.curve_bytes = np.asarray(curve, np.float32).tobytes()

    @staticmethod
    def put(prog, vals):
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v

    def combine(self, prog, out_w, out_h, inputs, vals):
        out = self.ctx.texture((out_w, out_h), 4)
        fb = self.ctx.framebuffer([out])
        fb.use()
        self.ctx.viewport = (0, 0, out_w, out_h)
        for i, (name, t) in enumerate(inputs):
            t.use(i)
            if name in prog:
                prog[name].value = i
        self.ones.use(7)
        self.put(prog, dict(self.common, **vals))
        if 'curveU' in prog:
            prog['curveU'].write(self.curve_bytes)
        self.ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.uint8).reshape(out_h, out_w, 4)[..., :3].copy()
        fb.release()
        out.release()
        return res

    def agx(self, ae):
        return {'aeU': ae['ae'], 'agxAU': (why_value('agx_slope'), why_value('agx_sp'), why_value('agx_tp'), why_value('agx_sat')),
                'agxBU': (why_value('agx_min_ev'), why_value('agx_max_ev'), why_value('agx_ev'), 0.0)}

    def viewfinder(self, raw: np.ndarray, ae: dict, fus: dict, deg: float, extra: dict | None = None) -> np.ndarray:
        """rawdevelop_fs u_linear_out 1 at the viewport, why/combine POINT_FUSED 1 on it (LiveWhyTone.draw, vf_why 1)."""
        w, h = VP
        rot = cvf.rotate_m(deg)
        quarter = abs(rot[1]) > abs(rot[0])
        crop = cvf.visible_crop(W, H, 1, w, h, quarter)
        t_raw = self.ctx.texture((W, H), 1, raw.tobytes(), dtype='u2')
        t_raw.filter = (cwp.moderngl.NEAREST, cwp.moderngl.NEAREST)
        lin = self.ctx.texture((w, h), 4, dtype='f2')
        lin.filter = (cwp.moderngl.NEAREST, cwp.moderngl.NEAREST)
        fb = self.ctx.framebuffer([lin])
        fb.use()
        self.ctx.viewport = (0, 0, w, h)
        t_raw.use(2)
        self.t_lsc.use(3)
        self.put(self.prog_lin, {'width': W, 'height': H, 'bayer_pattern': 0, 'cfa_mode': 1, 'cfa_block_size': 1,
                                 'crop_left': crop[0], 'crop_top': crop[1], 'crop_width': crop[2], 'crop_height': crop[3],
                                 'u_black_level': (BLACK,) * 4, 'u_white_level': WHITE, 'r_gain': WB[0], 'g_gain': WB[1],
                                 'b_gain': WB[2], 'color_transform': tuple(CCM.T.flatten()), 'u_lsc_intensity': 0.0,
                                 'mirror': 0, 'texRotate': rot, 'u_ldc_enabled': 0, 'sTexture16': 2, 'u_lsc_map': 3,
                                 'u_linear_out': 1, 'u_signed_hue': 0, 'peaking_enabled': 0})
        self.vao_lin.render(cwp.moderngl.TRIANGLE_STRIP)
        fb.release()
        curve = fused_curve(ae['em'], fus)
        t_curve = self.ctx.texture((cvf.SIZE, 1), 1, curve.tobytes(), dtype='f4')
        t_curve.filter = (cwp.moderngl.NEAREST, cwp.moderngl.NEAREST)
        inputs = [(n, lin) for n in ('InputBuffer', 'WhyLow', 'WhyColour', 'WhyFused', 'WhyDetailRef', 'WhyLumaS')]
        inputs += [('_lut', self.ones), ('_gain', self.ones), ('FusedCurve', t_curve)]
        vals = dict(self.agx(ae), fU=1, colourFU=1, fusedCurveU=(cvf.LOG2_MIN, float(cvf.PER_EV)), fullInputU=0)
        vals.update(extra or {})
        res = self.combine(self.prog_vf, w, h, inputs, vals)
        for t in (t_raw, lin, t_curve):
            t.release()
        return res

    def photo(self, linear: np.ndarray, ae: dict, fus: dict) -> np.ndarray:
        """whyLow (box 2), WhyFusion (the pyramid with the bands), why/combine POINT_FUSED 0 at full size."""
        low = linear.reshape(H // 2, 2, W // 2, 2, 3).mean((1, 3))
        low4 = np.concatenate([low, np.ones(low.shape[:2] + (1,), np.float32)], -1)
        uni = {'expU': tuple(float(e) for e in ae['em']), 'layerU': fus['lw'], 'aeU': ae['ae'], 'centerU': fus['center'],
               'smoothU': fus['smooth'], 'gammaInvU': fus['gammaInv'], 'toeU': fus['toe'], 'monoU': fus['mono'],
               'satLightU': (0.0, 0.0, 0.0, 0.0), 'satLevelU': (0.0, 0.0), 'warmU': (why_value('sat_light_warm_lo'),
               why_value('sat_light_warm_hi')), 'deepFloorU': fus['deepFloor'], 'deepNoiseU': (0.0, 0.0, 0.0, 0.0)}
        # WhyFusion.pyramid: the continuous depth at the half scale's coarse (12 MP whyLow 2048 x 1536 at ef_coarse 48)
        levels, f = cwb.depth(low.shape[1], low.shape[0], 24)
        fused, _ = cwb.fuse(self.gl, low4, uni, coarse=24, bands=cwb.BANDS, noise=None, low_band=f, levels=levels)
        t_full = self.gl.tex(np.concatenate([linear, np.ones((H, W, 1), np.float32)], -1), 'f4')
        t_low = self.gl.tex(low4, 'f4')
        t_fused = self.gl.tex(np.ascontiguousarray(fused[..., None], np.float32), 'f4')
        inputs = [('InputBuffer', t_full), ('WhyLow', t_low), ('WhyColour', t_low), ('WhyFused', t_fused), ('WhyDetailRef', t_low),
                  ('WhyLumaS', t_low)]
        vals = dict(self.agx(ae), fU=2, colourFU=2, fullInputU=1)
        res = self.combine(self.prog_photo, W, H, inputs, vals)
        for t in (t_full, t_low, t_fused):
            t.release()
        return res


# AE per scene (shared by both paths): the exposure and the scene's shadow / highlight strengths (WhyAe effS / effH)
AE = {'backlit room': {'ae': 2.6, 'effS': 1.6, 'effH': 1.2}, 'daylight': {'ae': 1.0, 'effS': 0.8, 'effH': 0.6},
      'night street': {'ae': 3.0, 'effS': 1.5, 'effH': 2.0}, 'doorway': {'ae': 1.0, 'effS': 1.9, 'effH': 0.4},
      'indoor flat': {'ae': 1.1, 'effS': 0.3, 'effH': 0.2}}
SCENES = tuple(AE)


def orient(vf: np.ndarray, ref: np.ndarray) -> np.ndarray:
    """The viewfinder output turned / mirrored onto the photo's orientation (the dihedral transform closest to the reference)."""
    best, err = vf, float('inf')
    for k in range(4):
        for flip in (False, True):
            c = np.rot90(vf, k)
            if flip:
                c = c[:, ::-1]
            if c.shape != ref.shape:
                continue
            e = float(np.mean(np.abs(c.astype(np.float64) - ref)))
            if e < err:
                best, err = c, e
    return best


BLOCK = 8


def blocks(img8: np.ndarray, b: int = BLOCK) -> np.ndarray:
    """b x b block means in linear light, back to 8-bit sRGB values (the tone at a scale the sensor noise does not decide)."""
    c = img8.astype(np.float64) / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    h, w = lin.shape[0] // b, lin.shape[1] // b
    m = lin[:h * b, :w * b].reshape(h, b, w, b, 3).mean((1, 3))
    return 255.0 * np.where(m <= 0.0031308, 12.92 * m, 1.055 * m ** (1 / 2.4) - 0.055)


def compare(vf8: np.ndarray, photo8: np.ndarray) -> dict:
    """On BLOCK x BLOCK block means (the viewfinder's frame is one noisy frame, the photo a merge): CIEDE2000, L* by the photo's
    tonal region, the local contrast between 1 and 5 blocks (std of L* less its 5 x 5 block box mean)."""
    lv, lp = srgb_to_lab(blocks(vf8)), srgb_to_lab(blocks(photo8))
    de = de2000(lp, lv)
    dl = lv[..., 0] - lp[..., 0]
    L = lp[..., 0]
    out = {'de_mean': float(de.mean()), 'de_p95': float(np.percentile(de, 95)), 'dl_mean': float(dl.mean()),
           'L_photo': float(L.mean()), 'L_vf': float(lv[..., 0].mean()),
           'lc_photo': local_contrast(lp[..., 0], 2), 'lc_vf': local_contrast(lv[..., 0], 2)}
    for key, m in (('shadows', L < 30), ('mids', (L >= 30) & (L <= 70)), ('lights', L > 70)):
        out['dl_' + key] = float(dl[m].mean()) if m.any() else float('nan')
        out['de_' + key] = float(de[m].mean()) if m.any() else float('nan')
        out['share_' + key] = float(m.mean())
    out['lc_ratio'] = out['lc_vf'] / max(out['lc_photo'], 1e-9)
    return out


def report(name: str, label: str, res: dict) -> None:
    print('%-13s %-16s dE00 mean %.2f p95 %.2f | dL* mean %+.2f (shadows %+.2f %.0f%%, mids %+.2f, lights %+.2f %.0f%%) | L* photo'
          ' %.1f vf %.1f | local contrast vf/photo %.2f'
          % (name, label, res['de_mean'], res['de_p95'], res['dl_mean'], res['dl_shadows'], 100 * res['share_shadows'],
             res['dl_mids'], res['dl_lights'], 100 * res['share_lights'], res['L_photo'], res['L_vf'], res['lc_ratio']))


def run(vf_defines=()) -> dict:
    """Every scene: the photo of the clean frame (a merge's limit); the viewfinder of the same frame (the tone path) and of a
    noisy frame of the scene (what the viewfinder shows) -> {scene: (tone path, noisy frame)}."""
    r = Renderer(vf_defines)
    fus = fusion_settings()
    rng = np.random.default_rng(711)
    results = {}
    for name in SCENES:
        img = scene(name, rng)
        raw = mosaic(img, rng)
        clean = mosaic(img, rng, noise=False)
        a = AE[name]
        ae = {'ae': a['ae'], 'em': exposures207(a['effS'], a['effH'])}
        photo = r.photo(photo_linear(clean), ae, fus)
        photo_s = np.clip(np.round(area_reduce(photo, VP[0], VP[1])), 0, 255).astype(np.uint8)
        same = orient(r.viewfinder(clean, ae, fus, 90.0), photo_s)
        vf = orient(r.viewfinder(raw, ae, fus, 90.0), photo_s)
        results[name] = (compare(same, photo_s), compare(vf, photo_s))
        report(name, 'same frame:', results[name][0])
        report(name, 'noisy frame:', results[name][1])
        if SAVE:
            from PIL import Image
            os.makedirs(SAVE, exist_ok=True)
            Image.fromarray(np.concatenate([photo_s, same, vf], 1)[::-1]).save(os.path.join(SAVE, '%s.png' % name.replace(' ', '_')))
    r.ctx.release()
    return results


def main() -> int:
    results = run()
    for name, (res, noisy) in results.items():
        check(res['de_mean'] <= MEAN_DE, '%s: mean dE00 %.2f > %.2f' % (name, res['de_mean'], MEAN_DE))
        check(res['de_p95'] <= P95_DE, '%s: p95 dE00 %.2f > %.2f' % (name, res['de_p95'], P95_DE))
        check(abs(res['dl_mean']) <= MEAN_DL, '%s: mean dL* %+.2f beyond %.1f' % (name, res['dl_mean'], MEAN_DL))
        if not math.isnan(res['dl_shadows']):
            check(abs(res['dl_shadows']) <= SHADOW_DL, '%s: shadows dL* %+.2f beyond %.1f' % (name, res['dl_shadows'], SHADOW_DL))
        check(LC_RANGE[0] <= res['lc_ratio'] <= LC_RANGE[1], '%s: local contrast vf / photo %.2f outside %s' % (name, res['lc_ratio'], LC_RANGE))
        check(noisy['de_mean'] <= NOISY_MEAN_DE and noisy['de_p95'] <= NOISY_P95_DE and abs(noisy['dl_mean']) <= NOISY_MEAN_DL,
              '%s, the noisy frame: dE00 %.2f / p95 %.2f, dL* %+.2f' % (name, noisy['de_mean'], noisy['de_p95'], noisy['dl_mean']))
    if FAILURES:
        print('viewfinder tone match FAILED (%d)' % len(FAILURES))
        return 1
    print('viewfinder tone match PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
