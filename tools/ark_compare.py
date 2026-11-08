"""P522: our merge of an Ark burst (tools/ark_burst_to_nch.py -> the worker) against Ark's own JPEG of it.

  render:  ark_compare.py render <merge.f32> <burst.ark.json> <ark.jpg> <out.npz> [--app-highlights]
  crops:   ark_compare.py crops <out.png> <title> <label>=<render.npz>[,<label>=<render.npz>...] <name>:<x>,<y> ...

render: our merge (sensor RGB, no white balance or lens shading) with the session's lens shading map, white balance and colour
matrix (clipped sites neutral, as the app's clip guard), sRGB-encoded, its luma mapped onto the JPEG's by quantiles - the tone curves differ, so only detail, noise, ghosts,
colour and highlights are compared. --app-highlights: the clipped areas through the app's own highlight recovery
(hlrecovery/chanprep -> reduce x2 -> scamhdr/scamrgb -> scamhdr/clipband, as ScamRgb.Run and tools/check_highlight_recovery.py,
with the worker's clip flags - the merge's last trailer, so run the worker without SCAM_NO_EFFMAP - the session's lens shading
as the gain map and its white balance; needs moderngl and EGL / llvmpipe); without it clipped sites are only made neutral.
crops: 192 px at 1:1 (x2) per point, a column per render and Ark's JPEG last."""
import json
import sys
import numpy as np
# PIL / scipy only where render / crops need them: app_highlights runs with numpy and moderngl alone (the CI's check venv)

LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)


GLOW = 1.0  # P537: the app's highlight_glow (default 1); --no-glow: 0
GLOW_R = 6  # its radius at 1x (ScamRgb: 6 x the output scale)


def app_highlights(rgb, flags, lsc, wp, k=0.0):
    """ScamRgb.Run's per-channel highlight branch on the shipped shaders: clip levels lo 1 and, with Bento (ultrashort frames at
    1 / k of the base), hi k (ScamRgb.channelClip without its histogram plateaus): white-balanced RGB."""
    import moderngl
    from pathlib import Path
    sh = Path(__file__).resolve().parents[1] / 'app/src/main/assets/shaders'
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'

    def prog(name):
        return ctx.program(vertex_shader=vs, fragment_shader='#version 430\n' + (sh / name).read_text(encoding='utf-8'))

    def tex(a, dtype='f4', linear=False):
        a = np.ascontiguousarray(a)
        t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype=dtype)
        f = moderngl.LINEAR if linear else moderngl.NEAREST
        t.filter = (f, f)
        t.repeat_x = t.repeat_y = False
        return t

    def draw(p, size, textures, uniforms):
        o = ctx.texture(size, 4, dtype='f2')
        o.filter = (moderngl.NEAREST, moderngl.NEAREST)
        fbo = ctx.framebuffer([o])
        fbo.use()
        ctx.viewport = (0, 0) + size
        for unit, (k, t) in enumerate(textures.items()):
            t.use(unit)
            if k in p:
                p[k].value = unit
        for k, v in uniforms.items():
            if k in p:
                p[k].value = v
        ctx.vertex_array(p, []).render(vertices=3)
        fbo.release()
        return o

    h, w = flags.shape
    inp, ft = tex(rgb.astype(np.float32)), tex(flags.astype(np.uint8), 'f1')
    gm = tex(lsc.astype(np.float16), 'f2', linear=True)
    common = {'whitePoint': tuple(map(float, wp)), 'inverseSize': (1.0 / w, 1.0 / h), 'cropOffset': (0, 0),
              'clipLoU': (1.0, 1.0, 1.0), 'clipHiU': (k, k, k), 'clipFlagsU': 1}
    near = ((w + 7) // 8, (h + 7) // 8)
    c8 = draw(prog('hlrecovery/chanprep.glsl'), near, {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft}, dict(common, blockU=8, pxStepU=1))
    wide = ((near[0] + 3) // 4, (near[1] + 3) // 4)
    c8.filter = (moderngl.LINEAR, moderngl.LINEAR)
    c32 = draw(prog('hlrecovery/reduce.glsl'), wide, {'InputBuffer': c8}, {})
    c32.filter = (moderngl.LINEAR, moderngl.LINEAR)
    c128 = draw(prog('hlrecovery/reduce.glsl'), ((wide[0] + 3) // 4, (wide[1] + 3) // 4), {'InputBuffer': c32}, {})
    c128.filter = (moderngl.LINEAR, moderngl.LINEAR)
    # ScamRgb's «Inpaint opposed» (P362 / P393 / P506, on by default) where flagged pixels hold no ultrashort content: its global
    # chrominance from hlrecovery/opposed (the full grid; the app's sparse grid gives the same sums)
    opp = {}
    if ((flags & 15) != 0).any() and (((flags & 15) != 0) & ((flags & 48) == 0)).any():
        src = (sh / 'hlrecovery/opposed.glsl').read_text(encoding='utf-8').replace(
            '\nLAYOUT\n', '\nlayout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;\n')
        cs = ctx.compute_shader('#version 430\n' + src)
        gx, gy = (w + 15) // 16, (h + 15) // 16
        buf = ctx.buffer(reserve=6 * gx * gy * 4)
        buf.bind_to_storage_buffer(1)
        for unit, (k_, t) in enumerate({'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft}.items()):
            t.use(unit)
            if k_ in cs:
                cs[k_].value = unit
        for k_, v in dict(common, sizeU=(w, h)).items():
            if k_ in cs:
                cs[k_].value = v
        cs.run(gx, gy, 1)
        v = np.frombuffer(buf.read(), np.uint32).reshape(gx * gy, 6)
        ssum = v[:, :3].copy().view(np.float32).astype(np.float64).sum(0)
        cnt = v[:, 3:].astype(np.float64).sum(0)
        chroma = np.where(cnt > 0, ssum / np.maximum(cnt, 1), 0.0)
        opp = dict(oppU=1, oppLocalU=1, oppWarmU=1.0, oppChromaU=tuple(map(float, chroma)))
    scam = draw(prog('scamhdr/scamrgb.glsl'), (w, h), {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft, 'Chroma8': c8, 'Chroma32': c32,
                                                       'Chroma128': c128},
                dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=1, blockU=8, chromaLimitU=0.35, defringeU=0.85, hlWhiteU=GLOW, **opp))
    band = draw(prog('scamhdr/clipband.glsl'), (w, h), {'InputBuffer': scam, 'ClipFlags': ft}, {'radiusU': 3, 'zoneU': 2, 'strengthU': 1.0, 'glowU': GLOW, 'glowRadiusU': GLOW_R})
    out = np.frombuffer(band.read(), np.float16).reshape(h, w, 4)[..., :3].astype(np.float32)
    ctx.release()
    return out


def render(f32, meta, jpg, out, app=False):
    from PIL import Image
    from scipy import ndimage as nd
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
    size = np.fromfile(f32, np.uint8).size
    if app and size >= H * W * 14:
        flags = np.fromfile(f32, np.uint8)[size - H * W:].reshape(H, W)
        shorts = [f.get('exposure', 0.0) for f in m.get('frames', []) if f.get('role') == 'short' and f.get('exposure', 0.0) > 0]
        lin = app_highlights(a, flags, lsc, (wb[1] / wb[0], 1.0, wb[1] / wb[2]), 1.0 / min(shorts) if shorts else 0.0)
        lin *= gain.mean(-1, keepdims=True)  # scamrgb applies the shading's colour only (gains / their mean); the app's vignetting later
    else:
        if app:
            sys.exit('--app-highlights: no clip flags in %s (run the worker without SCAM_NO_EFFMAP)' % f32)
        lin = np.maximum(a * gain * wb, 0)
        # clipped sites (the merge's 1.0) carry no colour: neutral at their brightest balanced channel, as the app's clip guard
        clip = np.clip((a.max(-1) - 0.9) / 0.08, 0, 1)[..., None]
        lin = lin + clip * (lin.max(-1, keepdims=True) - lin)
    lin = np.maximum(lin, 0) @ np.array(m['ccm'], np.float32).reshape(3, 3).T
    lin = lin / np.percentile(lin @ LUMA, 99.5)
    enc = np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * np.maximum(lin, 0) ** (1 / 2.4) - 0.055)
    ark = np.asarray(Image.open(jpg).convert('RGB'), np.float32) / 255
    if ark.shape[:2] != (H, W):
        sys.exit('the JPEG is %dx%d, the merge %dx%d' % (ark.shape[1], ark.shape[0], W, H))
    # as the app's guide (why/combine.glsl: I = max(Y709, max / 2)): a saturated red past sRGB has Y <= 0 and went black
    y = np.maximum(np.maximum(enc @ LUMA, 0.5 * enc.max(-1)), 1e-6)
    q = np.linspace(0, 100, 1025)
    src = np.maximum.accumulate(np.percentile(y[::4, ::4], q) + np.arange(q.size) * 1e-9)
    ty = np.interp(y, src, np.percentile((ark @ LUMA)[::4, ::4], q))
    ours = np.clip(enc * (ty / y)[..., None], 0, 1)
    np.savez(out, ours=(ours * 255 + 0.5).astype(np.uint8), ark=(ark * 255 + 0.5).astype(np.uint8))


def crops(out, title, cols, points, size=192, k=2):
    from PIL import Image, ImageDraw, ImageFont
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
        if '--no-glow' in sys.argv[6:]:
            GLOW = 0.0
        render(*sys.argv[2:6], app='--app-highlights' in sys.argv[6:])
    elif len(sys.argv) >= 6 and sys.argv[1] == 'crops':
        crops(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:])
    else:
        sys.exit(__doc__)
