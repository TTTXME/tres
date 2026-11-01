#!/usr/bin/env python3
"""Compare the real GPU half-float histogram selection with an independent CPU sort."""
from pathlib import Path
import sys
import numpy as np
import moderngl

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_why_pyramid import read_program


def main():
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    source = (Path(__file__).resolve().parents[1] / 'app/src/main/assets/shaders/chromadn/noisequantile.glsl').read_text()
    shaders = [ctx.compute_shader(read_program(source, [('PASS', str(i)), ('LAYOUT', 'layout(local_size_x = %d, local_size_y = %d, local_size_z = 1) in;' % ((8, 8) if i == 0 else (128, 1)))])) for i in (0, 1)]
    rng = np.random.default_rng(991)
    normals = np.arange(0x0400, 0x7c01, dtype=np.uint16)
    cases = [np.tile(normals, 2), np.full(65536, 0x211f, np.uint16), np.tile(np.array([0x3c00, 0x7c00], np.uint16), 32768),
             np.arange(65536, dtype=np.uint16), np.zeros(65536, np.uint16), np.tile(np.arange(1024, dtype=np.uint16), 64),
             rng.integers(0, 65536, 257 * 128, dtype=np.uint16), np.tile(np.array([0x7c00, 0x7e00, 0x8000, 1], np.uint16), 16384),
             np.tile(np.arange(1, 1024, dtype=np.uint16), 64), np.array([0x3c00], np.uint16),
             np.array([0x3c00, 0x0400, 0x7c00, 0x3d00, 0x3e00], np.uint16),
             rng.integers(0x0400, 0x7c01, dtype=np.uint16, size=257 * 129)]
    for index, bits in enumerate(cases):
        w = 257 if index in (6, 11) else min(512, len(bits))
        h = (len(bits) + w - 1) // w
        bits = np.resize(bits, w * h)
        texture = ctx.texture((w, h), 1, bits.tobytes(), dtype='f2')
        texture.use(0)
        hist = ctx.buffer(bytes(32768 * 4)); hist.bind_to_storage_buffer(0)
        state = ctx.buffer(bytes(16)); state.bind_to_storage_buffer(1)
        shaders[0]['Noise'].value = 0
        shaders[0].run((w + 7) // 8, (h + 7) // 8, 1)
        ctx.memory_barrier(moderngl.SHADER_STORAGE_BARRIER_BIT)
        shaders[1].run(1, 1, 1)
        ctx.memory_barrier(moderngl.BUFFER_UPDATE_BARRIER_BIT | moderngl.SHADER_STORAGE_BARRIER_BIT)
        valid, count, key, status = np.frombuffer(state.read(), np.uint32)
        values = bits.view(np.float16)
        selected = np.sort(values[(values > 0) & ~np.isnan(values)])
        expected_fast = bool(np.all((bits >= 0x0400) & (bits <= 0x7c00)))
        assert (count == w * h) == expected_fast, (index, count, w * h)
        if count == w * h:
            assert status == 1 and valid == count == len(selected), (index, valid, count, len(selected))
            assert np.float16(selected[len(selected) // 4]).view(np.uint16) == key, (index, key)
        else:
            assert count < w * h, index
        print('ok noise quantile scene', index, 'fast' if count == w * h else 'CPU fallback')
        texture.release(); hist.release(); state.release()
    for shader in shaders:
        shader.release()
    ctx.release()


if __name__ == '__main__':
    main()
