#!/usr/bin/env python3
"""P602: the diagnostics' GPU sample pass gives the samples of the read-back it replaces.

ScamDiagnostics.gpu read every step-th row of a stage with glReadPixels(GL_RGBA, GL_FLOAT) and kept every step-th pixel of it
(step = max(1, (max(W, H) + 1023) / 1024)); since P602 a compute pass (ScamDiagnostics.SAMPLE_PASS, taken from the Java source
here) copies texelFetch(src, (x, y) * step) into a buffer of w x h vec4 that is mapped later. This check runs the pass as the
app ships it (#version 310 es -> 430 for the host's desktop GL) on half-float and float textures of the stages' sizes filled with
every finite half pattern and random floats, and compares each sample bit for bit with the float read-back's.

Host GL (moderngl, EGL / llvmpipe): it proves the pass's indexing and the texel values on the host, not a GPU's conversion of
denormal halves (a driver may flush those in either path; the diagnostics only).
"""
from pathlib import Path
import re
import sys

import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamDiagnostics.java'


def sample_pass() -> str:
    text = JAVA.read_text(encoding='utf-8')
    m = re.search(r'SAMPLE_PASS=(.*?);\n', text, re.S)
    if not m:
        raise RuntimeError('SAMPLE_PASS not found in ScamDiagnostics.java')
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    src = ''.join(parts).encode('utf-8').decode('unicode_escape')
    if not src.startswith('#version 310 es\n'):
        raise RuntimeError('SAMPLE_PASS: unexpected version line')
    return '#version 430\n' + src[len('#version 310 es\n'):]


def grid(w: int, h: int):
    step = max(1, (max(w, h) + 1023) // 1024)
    return step, w // step, h // step


def context():
    try:
        return moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        return moderngl.create_standalone_context(require=430)


def check(ctx, prog, w, h, half, rng, channels=4) -> str:
    if half:
        bits = rng.integers(0, 1 << 16, size=w * h * 4, dtype=np.uint32).astype(np.uint16)
        bits[: min(bits.size, 1 << 16)] = np.arange(min(bits.size, 1 << 16), dtype=np.uint16)
        vals = bits.view(np.float16)
        vals[~np.isfinite(vals)] = np.float16(0.5)  # NaN payloads / infinities: only stay so; not compared bitwise here
        tex = ctx.texture((w, h), 4, vals.tobytes(), dtype='f2')
    else:
        vals = (rng.standard_normal(w * h * channels) * 4).astype(np.float32)
        tex = ctx.texture((w, h), channels, vals.tobytes(), dtype='f4')
    tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
    step, sw, sh = grid(w, h)
    buf = ctx.buffer(reserve=sw * sh * 16)
    tex.use(location=15)
    prog['srcU'].value = 15
    prog['dimsU'].value = (sw, sh, step)
    buf.bind_to_storage_buffer(3)
    prog.run((sw + 15) // 16, (sh + 15) // 16, 1)
    ctx.memory_barrier()
    got = np.frombuffer(buf.read(), dtype=np.float32).reshape(sh, sw, 4)
    # the read-back as before: float rows of the stage's framebuffer, every step-th pixel
    fbo = ctx.framebuffer(color_attachments=[tex])
    rows = np.frombuffer(fbo.read(components=4, dtype='f4'), dtype=np.float32).reshape(h, w, 4)
    want = rows[0:sh * step:step, 0:sw * step:step, :][:sh, :sw, :]
    same = np.array_equal(got[..., :3].view(np.uint32), want[..., :3].view(np.uint32))
    fbo.release(); buf.release(); tex.release()
    if not same:
        bad = np.argwhere(got[..., :3].view(np.uint32) != want[..., :3].view(np.uint32))[0]
        raise AssertionError(f'{w}x{h} {"half" if half else "float"}: sample {tuple(bad)} {got[tuple(bad)]} != {want[tuple(bad)]}')
    return f'{w}x{h} {"RGBA16F" if half else "RGBA32F" if channels == 4 else "R32F"} step {step} -> {sw}x{sh}: equal'


def main() -> int:
    ctx = context()
    prog = ctx.compute_shader(sample_pass())
    rng = np.random.default_rng(602)
    for w, h, half in ((4080, 3072, True), (2040, 1536, True), (2040, 1536, False), (1000, 750, True), (4100, 3001, True),
                       (1031, 777, False)):
        print(check(ctx, prog, w, h, half, rng))
    print(check(ctx, prog, 2040, 1536, False, rng, channels=1))  # WhyDetailRef / WhyFused: R32F, GL_NEAREST
    print('diag samples PASS: the sample pass = the float read-back, sample for sample')
    return 0


if __name__ == '__main__':
    sys.exit(main())
