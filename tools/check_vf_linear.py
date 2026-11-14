#!/usr/bin/env python3
"""P711: the RAW viewfinder's gather linear pass (preview/rawlinear_fs.glsl, LiveWhyTone's first pass of vf_why 1) against the pass
it replaces (preview/rawdevelop_fs.glsl u_linear_out 1), on the GPU (moderngl), no device.

Both are drawn with preview/rawdevelop_vs.glsl (the gather pass as GLSL ES 3.10, LiveWhyTone.fusedVertexShader) into an RGBA16F
target of the tone's size with the same uniforms (LiveRawRenderer.bindFrame / draw), on the frames of tools/check_vf_fused.py:
RGGB / GRBG / GBRG / BGGR Bayer with noise and sharp saturated edges, a black level of its own at each CFA site, white balance
with the highlight fade, a lens-shading map, a colour matrix, portrait / landscape rotations, the front mirror, a 16:9 viewport,
Quad cell means (block 1, cfa_mode 0), a Quad of block 2 (the fall-back to rawdevelop_fs's own fetches inside the import), an odd
frame size (likewise), the lens distortion correction, u_signed_hue 0 and 1. Pass: the half floats bit for bit, or (a driver
that orders the arithmetic differently) within 2 half-float ulps; the share of pixels that differ at all is printed.
Also: the shader compiles with glslc as GLProg hands it to the driver (#version 310 es, the imports expanded).

usage: python3 tools/check_vf_linear.py   (numpy, moderngl; LIBGL_ALWAYS_SOFTWARE=1 on a headless Linux)
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_vf_fused as cvf  # noqa: E402

SH = cvf.SH
FAILURES: list[str] = []
MAX_ULPS = 2


def check(cond: bool, msg: str) -> None:
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def ulps(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Distance in half-float ulps (sign-magnitude bits mapped onto a monotonic integer line)."""
    def line(x):
        i = x.view(np.uint16).astype(np.int32)
        return np.where(i & 0x8000, -(i & 0x7fff), i)
    return np.abs(line(a) - line(b))


def glslc_part() -> None:
    glslc = cvf.find_glslc()
    if not glslc:
        print('glslc not found: rawlinear_fs was not compiled offline')
        return
    text = cvf.read_program(cvf.read(SH / 'preview/rawlinear_fs.glsl'))
    with tempfile.TemporaryDirectory() as d:
        f = Path(d) / 'rawlinear.frag'
        f.write_text(text, encoding='utf-8')
        r = subprocess.run([glslc, '--target-env=opengl', '-std=310es', '-fshader-stage=frag', '-fauto-map-locations',
                            '-fauto-bind-uniforms', '-o', os.devnull, str(f)], capture_output=True, text=True)
        check(r.returncode == 0, 'glslc: rawlinear_fs does not compile:\n%s' % r.stderr)
        print('glslc rawlinear_fs (310 es): %s' % ('OK' if r.returncode == 0 else 'FAIL'))


def gpu_part() -> None:
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = cvf.read(SH / 'preview/rawdevelop_vs.glsl')
    old = ctx.program(vertex_shader=vs, fragment_shader=cvf.read(SH / 'preview/rawdevelop_fs.glsl'))
    new = ctx.program(vertex_shader=vs.replace('#version 300 es', '#version 310 es', 1),
                      fragment_shader=cvf.read_program(cvf.read(SH / 'preview/rawlinear_fs.glsl')))
    print('GPU: %s (%s)' % (ctx.info['GL_RENDERER'], ctx.info['GL_VERSION']))
    pos = ctx.buffer(np.array([1, -1, -1, -1, 1, 1, -1, 1], np.float32).tobytes())
    uv = ctx.buffer(np.array([1, 1, 0, 1, 1, 0, 0, 0], np.float32).tobytes())
    vaos = {p: ctx.vertex_array(p, [(pos, '2f', 'vPosition'), (uv, '2f', 'vTexCoord')]) for p in (old, new)}
    sy, sx = np.mgrid[0:13, 0:17] / np.array([12.0, 16.0])[:, None, None]
    r2 = (sx - 0.5) ** 2 + (sy - 0.5) ** 2
    t_lsc = ctx.texture((17, 13), 3, np.stack([1 + 3.4 * r2, 1 + 3.0 * r2, 1 + 2.7 * r2], -1).astype(np.float16).tobytes(), dtype='f2')
    t_lsc.filter = (moderngl.LINEAR, moderngl.LINEAR)
    t_lsc.repeat_x = t_lsc.repeat_y = False
    ccm = np.array([[1.62, -0.48, -0.14], [-0.21, 1.45, -0.24], [0.02, -0.55, 1.53]])
    orders = [[0, 1, 2, 3], [1, 0, 3, 2], [2, 3, 0, 1], [3, 2, 1, 0]]
    black = [58.0, 63.0, 67.0, 72.0]
    ldc = {'u_ldc_enabled': 1, 'u_ldc_aspect': 0.75, 'u_ldc_inv_aspect': 1 / 0.75, 'u_ldc_k1': -0.08, 'u_ldc_k2': 0.02,
           'u_ldc_k3': 0.03, 'u_ldc_p1': 0.2, 'u_ldc_cx': 0.5, 'u_ldc_cy': 0.5}

    def draw(prog, t_raw, vals, w, h):
        out = ctx.texture((w, h), 4, dtype='f2')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, w, h)
        t_raw.use(9)
        t_lsc.use(10)
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        vaos[prog].render(moderngl.TRIANGLE_STRIP)
        res = np.frombuffer(out.read(), np.float16).reshape(h, w, 4).copy()
        fb.release()
        out.release()
        return res

    # name, frame w x h, CFA, block, cfa_mode, viewport, rotation, mirror, LDC
    cases = [('1x RGGB portrait', 1024, 768, 0, 1, 1, (360, 480), 180, 0, False),
             ('1x GRBG portrait, mirror', 1024, 768, 1, 1, 1, (360, 480), 0, 1, False),
             ('1x GBRG landscape', 1024, 768, 2, 1, 1, (480, 360), 90, 0, False),
             ('1x BGGR portrait 270', 1024, 768, 3, 1, 1, (360, 480), 270, 0, False),
             ('1x RGGB 16:9 viewport', 1024, 768, 0, 1, 1, (360, 640), 180, 0, False),
             ('1x RGGB, lens distortion', 1024, 768, 0, 1, 1, (360, 480), 180, 0, True),
             ('1x RGGB, odd frame', 1023, 767, 0, 1, 1, (360, 480), 180, 0, False),
             ('Quad cell means (2x ISZ)', 512, 384, 3, 1, 0, (360, 480), 180, 0, False),
             ('Quad cell means, mirror', 512, 384, 0, 1, 0, (360, 480), 0, 1, False),
             ('Quad block 2', 1024, 768, 3, 2, 0, (360, 480), 180, 0, False)]
    rng = np.random.default_rng(711)
    for signed in (0, 1):
        for name, fw, fh, cfa, block, mode, vp, deg, mirror, with_ldc in cases:
            img = cvf.scene(fh, fw, rng)
            raw = cvf.mosaic(img, cfa, block, rng, black)
            t_raw = ctx.texture((fw, fh), 1, raw.tobytes(), dtype='u2')
            t_raw.filter = (moderngl.NEAREST, moderngl.NEAREST)
            rot = cvf.rotate_m(deg)
            quarter = abs(rot[1]) > abs(rot[0])
            crop0 = cvf.visible_crop(fw, fh, block, vp[0], vp[1], quarter)
            w, h = cvf.tone_size(vp[0], vp[1], crop0[2], crop0[3], 1 if mode == 0 and block == 1 else block, quarter)
            crop = cvf.visible_crop(fw, fh, block, w, h, quarter)
            o = orders[cfa]
            vals = {'width': fw, 'height': fh, 'bayer_pattern': cfa, 'cfa_mode': mode, 'cfa_block_size': block,
                    'crop_left': crop[0], 'crop_top': crop[1], 'crop_width': crop[2], 'crop_height': crop[3],
                    'u_black_level': tuple(black[i] for i in o), 'u_white_level': 1023.0, 'r_gain': 2.0, 'g_gain': 1.0,
                    'b_gain': 1.6, 'color_transform': tuple(ccm.T.flatten()), 'u_lsc_intensity': 1.0, 'mirror': mirror,
                    'texRotate': rot, 'u_ldc_enabled': 0, 'sTexture16': 9, 'u_lsc_map': 10, 'u_linear_out': 1,
                    'u_signed_hue': signed, 'peaking_enabled': 0, 'u_auto_exposure': 1.0}
            if with_ldc:
                vals.update(ldc)
            a, b = draw(old, t_raw, vals, w, h), draw(new, t_raw, vals, w, h)
            d = ulps(a[..., :3], b[..., :3])
            label = '%s%s' % (name, ', signed' if signed else '')
            print('%-36s tone %4dx%-4d: max %d half ulps, %.4f %% of the channels differ' % (label, w, h, int(d.max()),
                  100.0 * float((d > 0).mean())))
            check(np.isfinite(a[..., :3].astype(np.float32)).all() and float(np.abs(a[..., :3].astype(np.float32)).max()) > 0.05,
                  '%s: rawdevelop_fs drew nothing' % label)
            check(int(d.max()) <= MAX_ULPS, '%s: rawlinear_fs differs from rawdevelop_fs u_linear_out 1 by %d half ulps' % (label, int(d.max())))
            t_raw.release()
    ctx.release()


def main() -> int:
    glslc_part()
    gpu_part()
    if FAILURES:
        print('gather linear pass check FAILED (%d)' % len(FAILURES))
        return 1
    print('gather linear pass check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
