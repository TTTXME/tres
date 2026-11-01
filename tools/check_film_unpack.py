#!/usr/bin/env python3
from pathlib import Path
from contextlib import ExitStack

import moderngl
import numpy as np

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/cpp/third_party/spektrafilm/shaders/SpektraFormatConvertGlobal.comp').read_text()
source = source.replace('layout(push_constant) uniform FormatConvertParams',
                        'layout(std430, binding = 2) readonly buffer FormatConvertParams')
try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)

words = np.arange(65536, dtype=np.uint16)
expected = words.view(np.float16).astype(np.float32)
with ExitStack() as resources:
    shader = ctx.compute_shader(source)
    resources.callback(shader.release)
    input_buffer = ctx.buffer(words.tobytes())
    resources.callback(input_buffer.release)
    output_buffer = ctx.buffer(reserve=expected.nbytes)
    resources.callback(output_buffer.release)
    params = ctx.buffer(np.array([16384, 0, 16384, 0, 0, 16384], dtype=np.uint32).tobytes())
    resources.callback(params.release)
    input_buffer.bind_to_storage_buffer(0)
    output_buffer.bind_to_storage_buffer(1)
    params.bind_to_storage_buffer(2)
    shader.run(group_x=64)
    ctx.memory_barrier()
    actual = np.frombuffer(output_buffer.read(), dtype=np.float32)
    numeric = ~np.isnan(expected)
    np.testing.assert_array_equal(actual[numeric].view(np.uint32), expected[numeric].view(np.uint32))
    assert np.isnan(actual[~numeric]).all()
print('PASS GPU film unpack: all 65536 half patterns, subnormals, signed zero and infinities')
