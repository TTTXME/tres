"""P596: a synthetic Quad (2x2 colour block) burst with its ground truth, for measuring what the merge resolves.

  python3 tools/quad_synth_burst.py scene.jpg out.nch [--frames 30] [--noise 4e-4] [--seed 1] [--block 2]

The scene (any sharp photo, read at 2 scene px per sensor px) gets a strip of charts on top (zone plate, low-contrast
textures at 2 % and 5 %, a slanted edge, fine neutral bars, a flat grey patch) and two moving objects (3 px / frame along x;
1.2 px / frame on a diagonal). Every frame: the hand's shift (a random walk with a drift, sub-pixel) and a small roll, the
lens (gaussian 0.8 sensor px), the pixel aperture (2x2 box), the sensor neutral (R 0.42, B 0.60 of G), the block mosaic,
shot noise (variance = noise x level), a per-site response of +-0.4 %, 10 bit. Frame 0 is the base (the truth's moment),
the others older. Beside out.nch: out.ark.json (white balance, an identity shading map: tools/ark_compare.py and the
metrics read it), out.truth.npy (H x W x 3, the base frame's sensor RGB before noise, the merge's units) and out.layout.json
(the charts' and objects' boxes in sensor px)."""
import argparse, json, os, struct, sys
import numpy as np
from scipy import ndimage as nd

NEUTRAL = np.array([0.42, 1.0, 0.60], np.float32)
CCM = [1.4473797, -0.3308154, -0.1165648, -0.1476406, 1.4112117, -0.2635708, 0.0614060, -0.5328364, 1.4714302]


def charts(rng, n=512):
    """six n x n scene-px tiles (linear grey / RGB 0..1) and their names"""
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32) - (n - 1) / 2
    r2 = xx * xx + yy * yy
    zone = 0.4 + 0.3 * np.cos(np.pi * (0.25 / (n / 2)) * r2)        # 0.5 cycles / sensor px at the tile's edge
    def tex(c):
        t = nd.gaussian_filter(rng.standard_normal((n, n)).astype(np.float32), 0.7)
        return 0.3 * (1 + c * t / t.std())
    a = np.deg2rad(5.0)
    edge = np.where(xx * np.cos(a) + yy * np.sin(a) > 0, 0.6, 0.1).astype(np.float32)
    edge[n // 2:, :] = np.where((xx * np.cos(a) - yy * np.sin(a))[n // 2:, :] > 0, 0.45, 0.2)  # a lower-contrast edge below
    bars = np.full((n, n), 0.35, np.float32)
    x = xx + (n - 1) / 2 + 0.03 * yy                                  # a slight slant: every phase of the blocks
    for k, period in enumerate((4.4, 6.0, 8.0, 12.0)):               # scene px = 2.2 / 3 / 4 / 6 sensor px
        rows = slice(k * n // 4 + 6, (k + 1) * n // 4 - 6)
        bars[rows] = 0.35 + 0.25 * np.sign(np.sin(2 * np.pi * x[rows] / period))
    flat = np.full((n, n), 0.25, np.float32)
    g = lambda t: np.repeat(t[..., None], 3, -1)
    return [g(zone), g(tex(0.02)), g(tex(0.05)), g(edge), g(bars), g(flat)], ['zone', 'tex2', 'tex5', 'edge', 'bars', 'flat']


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scene'); ap.add_argument('out')
    ap.add_argument('--frames', type=int, default=30)
    ap.add_argument('--noise', type=float, default=4e-4)
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--block', type=int, default=2)
    ap.add_argument('--window', default='512,384,3072,2304', help="the scene's window in the photo: x, y, w, h (scene px)")
    ap.add_argument('--walk', type=float, default=1.5, help='the hand: random walk step per frame, sensor px')
    a = ap.parse_args()
    from PIL import Image
    rng = np.random.default_rng(a.seed)
    x0, y0, sw, sh = map(int, a.window.split(','))
    im = np.asarray(Image.open(a.scene).convert('RGB').crop((x0, y0, x0 + sw, y0 + sh)), np.float32) / 255
    scene = np.where(im <= 0.04045, im / 12.92, ((im + 0.055) / 1.055) ** 2.4).astype(np.float32) * 0.8
    tiles, names = charts(rng)
    n = tiles[0].shape[0]
    layout = {'charts': {}, 'objects': []}
    for k, (t, name) in enumerate(zip(tiles, names)):
        scene[:n, k * n:(k + 1) * n] = t
        layout['charts'][name] = [k * n // 2, 0, n // 2, n // 2]       # sensor px: x, y, w, h
    H, W = sh // 2, sw // 2
    H -= H % 8; W -= W % 8
    psf = lambda v: np.stack([nd.gaussian_filter(v[..., c], 1.6, mode='reflect') for c in range(v.shape[-1])], -1)
    base = psf(scene)
    # moving objects: a detailed piece of the scene with a soft rim, premultiplied; position at the base, velocity per frame
    objs = []
    for (sx, sy, w, h, px, py, vx, vy) in ((sw * 5 // 8, sh * 5 // 8, 360, 260, sw // 4, sh * 3 // 8, 6.0, 0.0),
                                           (sw * 3 // 8, sh * 6 // 8, 260, 300, sw * 5 // 8, sh * 3 // 8, 1.7, 1.7)):
        piece = scene[sy:sy + h, sx:sx + w].copy()
        piece = np.clip(0.25 + 1.4 * (piece - piece.mean()), 0.02, 0.9)   # stands out from what it crosses
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        d = np.minimum(np.minimum(xx, w - 1 - xx), np.minimum(yy, h - 1 - yy))
        alpha = np.clip(d / 2.0, 0, 1)[..., None]
        pad = 8
        pm = np.pad(piece * alpha, ((pad, pad), (pad, pad), (0, 0))); al = np.pad(alpha, ((pad, pad), (pad, pad), (0, 0)))
        objs.append((psf(pm), psf(al), px - pad, py - pad, vx, vy))
        layout['objects'].append({'box': [px // 2, py // 2, w // 2, h // 2], 'v': [vx / 2, vy / 2]})

    def frame_scene(k):
        s = base.copy()
        for pm, al, px, py, vx, vy in objs:
            fx, fy = px - vx * k, py - vy * k                           # older frames: the object was behind
            ix, iy = int(np.floor(fx)), int(np.floor(fy))
            sub = [nd.shift(v[..., c], (fy - iy, fx - ix), order=1, mode='constant') for v in (pm, al) for c in range(v.shape[-1])]
            pms, als = np.stack(sub[:3], -1), sub[3][..., None]
            hh, ww = als.shape[:2]
            s[iy:iy + hh, ix:ix + ww] = s[iy:iy + hh, ix:ix + ww] * (1 - als) + pms
        return s

    def sensor(s):  # scene (2x) -> sensor RGB
        return s[:H * 2, :W * 2].reshape(H, 2, W, 2, 3).mean((1, 3)) * NEUTRAL

    truth = sensor(frame_scene(0))
    np.save(os.path.splitext(a.out)[0] + '.truth.npy', truth.astype(np.float32))
    b = a.block
    yy, xx = np.mgrid[0:H, 0:W]
    col = (((yy // b) & 1) * 2 + ((xx // b) & 1))                       # RGGB blocks
    chan = np.array([0, 1, 1, 2])[col]
    site = 1 + 0.004 * np.array([[1, -1], [-0.5, 0.5]])[yy % 2, xx % 2] if b > 1 else 1.0
    black, white = 64.0, 1023.0
    nfr = a.frames
    pos = np.cumsum(rng.normal(0, a.walk, (nfr, 2)) + np.array([0.35, 0.2]), 0); pos[0] = 0
    roll = np.cumsum(rng.normal(0, 0.004, nfr)); roll[0] = 0             # degrees
    head = bytearray(128)
    struct.pack_into('<6If4fI', head, 0, 0x3143484e, 12, W, H, 0, nfr, white, black, black, black, black, 4)
    struct.pack_into('<3I', head, 48, 0, 1, b)
    first = (128 + 32 * nfr + 4095) // 4096
    pages = (W * H * 2 + 4095) // 4096
    shifts = []
    with open(a.out, 'wb') as f:
        f.write(head)
        for i in range(nfr):
            f.write(struct.pack('<IfIfffII', 1, 1.0, 100, a.noise, 1e-9, -33.0 * i, 0, first + i * pages))
        for i in range(nfr):
            s = frame_scene(i)
            if i:
                th = np.deg2rad(roll[i]); c, sn = np.cos(th), np.sin(th)
                m = np.array([[c, -sn], [sn, c]]); ctr = np.array([sh / 2, sw / 2])
                off = ctr - m @ ctr + 2 * pos[i][::-1]                  # output (y, x) -> input: the frame sees the scene shifted
                s = np.stack([nd.affine_transform(s[..., ch], m, off, order=3, mode='reflect') for ch in range(3)], -1)
            v = np.take_along_axis(sensor(s), chan[..., None], -1)[..., 0] * site
            v = v + rng.standard_normal(v.shape).astype(np.float32) * np.sqrt(np.maximum(a.noise * v, 0) + 1e-9)
            raw = np.clip(np.rint(black + (white - black) * v), 0, white).astype('<u2')
            f.seek((first + i * pages) * 4096); f.write(raw.tobytes())
            shifts.append([float(pos[i][0]), float(pos[i][1]), float(roll[i])])
        f.truncate((first + nfr * pages) * 4096)
    layout['shifts'] = shifts
    json.dump(layout, open(os.path.splitext(a.out)[0] + '.layout.json', 'w'))
    lsc = np.ones((13, 17, 4), np.float32)
    meta = dict(lens='synthetic', w=W, h=H, cfa=0, block=b, white=white, black=[black] * 4, iso=100, noise_s=a.noise, noise_o=1e-9,
                wb=[float(1 / NEUTRAL[0]), 1.0, 1.0, float(1 / NEUTRAL[2])], ccm=CCM, lsc_size=[17, 13], lsc=lsc.tolist(),
                frames=[dict(role='normal', exposure=1.0) for _ in range(nfr)])
    json.dump(meta, open(os.path.splitext(a.out)[0] + '.ark.json', 'w'))
    print('%s: %dx%d block %d, %d frames, noise %.3g, shifts up to %.1f px' % (a.out, W, H, b, nfr, a.noise, float(np.abs(pos).max())))


if __name__ == '__main__':
    main()
