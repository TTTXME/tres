"""P522: our merge of an Ark burst (tools/ark_burst_to_nch.py -> the worker) against Ark's own JPEG of it.

  render:  ark_compare.py render <merge.f32> <burst.ark.json> <ark.jpg> <out.npz>
  crops:   ark_compare.py crops <out.png> <title> <label>=<render.npz>[,<label>=<render.npz>...] <name>:<x>,<y> ...

render: our merge (sensor RGB, no white balance or lens shading) with the session's lens shading map, white balance and colour
matrix (clipped sites neutral, as the app's clip guard), sRGB-encoded, its luma mapped onto the JPEG's by quantiles - the tone curves differ, so only detail, noise, ghosts,
colour and highlights are compared. crops: 192 px at 1:1 (x2) per point, a column per render and Ark's JPEG last."""
import json
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage as nd

LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)


def render(f32, meta, jpg, out):
    m = json.load(open(meta))
    H, W = m['h'], m['w']
    a = np.fromfile(f32, np.float32, H * W * 3).reshape(H, W, 3)
    lsc = np.array(m['lsc'], np.float32)                          # lh x lw x [R, Geven, Godd, B]
    lh, lw = lsc.shape[:2]
    g = np.stack([lsc[..., 0], 0.5 * (lsc[..., 1] + lsc[..., 2]), lsc[..., 3]], -1)
    yy = (np.arange(H) + 0.5) / H * (lh - 1)
    xx = (np.arange(W) + 0.5) / W * (lw - 1)
    grid = np.meshgrid(yy, xx, indexing='ij')
    gain = np.stack([nd.map_coordinates(g[..., c], grid, order=1, mode='nearest') for c in range(3)], -1)
    wb = np.array([m['wb'][0], 0.5 * (m['wb'][1] + m['wb'][2]), m['wb'][3]], np.float32)
    lin = np.maximum(a * gain * wb, 0)
    # clipped sites (the merge's 1.0) carry no colour: neutral at their brightest balanced channel, as the app's clip guard
    clip = np.clip((a.max(-1) - 0.9) / 0.08, 0, 1)[..., None]
    lin = lin + clip * (lin.max(-1, keepdims=True) - lin)
    lin = lin @ np.array(m['ccm'], np.float32).reshape(3, 3).T
    lin = lin / np.percentile(lin @ LUMA, 99.5)
    enc = np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * np.maximum(lin, 0) ** (1 / 2.4) - 0.055)
    ark = np.asarray(Image.open(jpg).convert('RGB'), np.float32) / 255
    if ark.shape[:2] != (H, W):
        sys.exit('the JPEG is %dx%d, the merge %dx%d' % (ark.shape[1], ark.shape[0], W, H))
    y = np.maximum(enc @ LUMA, 1e-6)
    q = np.linspace(0, 100, 1025)
    src = np.maximum.accumulate(np.percentile(y[::4, ::4], q) + np.arange(q.size) * 1e-9)
    ty = np.interp(y, src, np.percentile((ark @ LUMA)[::4, ::4], q))
    ours = np.clip(enc * (ty / y)[..., None], 0, 1)
    np.savez(out, ours=(ours * 255 + 0.5).astype(np.uint8), ark=(ark * 255 + 0.5).astype(np.uint8))


def crops(out, title, cols, points, size=192, k=2):
    cols = [c.split('=', 1) for c in cols.split(',')]
    srcs = [(label, np.load(f)['ours']) for label, f in cols] + [('Ark', np.load(cols[0][1])['ark'])]
    picks = [(p.split(':')[0], *map(int, p.split(':')[1].split(','))) for p in points]
    try:
        big = ImageFont.truetype('DejaVuSans.ttf', 20)
        small = ImageFont.truetype('DejaVuSans.ttf', 14)
    except OSError:
        big = small = ImageFont.load_default()
    pad, head, cw, n = 8, 44, size * k, len(srcs)
    img = Image.new('RGB', (n * cw + (n + 1) * pad, head + len(picks) * (cw + pad) + pad), (24, 24, 24))
    d = ImageDraw.Draw(img)
    d.text((pad, 10), title, fill=(240, 240, 240), font=big)
    for i, (name, x, y) in enumerate(picks):
        h0, w0 = srcs[0][1].shape[:2]
        x0 = int(np.clip(x - size // 2, 0, w0 - size))
        y0 = int(np.clip(y - size // 2, 0, h0 - size))
        for j, (label, src) in enumerate(srcs):
            tile = np.kron(src[y0:y0 + size, x0:x0 + size], np.ones((k, k, 1), np.uint8))
            img.paste(Image.fromarray(tile), (pad + j * (cw + pad), head + i * (cw + pad)))
            d.text((pad + j * (cw + pad) + 6, head + i * (cw + pad) + 4), label + ': ' + name, fill=(255, 230, 120), font=small,
                   stroke_width=2, stroke_fill=(0, 0, 0))
    img.save(out)


if __name__ == '__main__':
    if len(sys.argv) >= 6 and sys.argv[1] == 'render':
        render(*sys.argv[2:6])
    elif len(sys.argv) >= 6 and sys.argv[1] == 'crops':
        crops(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:])
    else:
        sys.exit(__doc__)
