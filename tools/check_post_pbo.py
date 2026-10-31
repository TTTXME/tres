#!/usr/bin/env python3
"""GPU RGBA8 tile/PBO readback parity, including GL-error fallbacks; not Android timing."""
import argparse
import ctypes as C
import ctypes.util
import json
from pathlib import Path
import statistics
import sys
import time

import moderngl
import numpy as np

PACK, STREAM, READ, ALIGN, ROW = 0x88EB, 0x88E1, 1, 0x0D05, 0x0D02
RGBA, BYTE, INVALID = 0x1908, 0x1401, 0x0502
VS = '''#version 300 es
void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0.,1.);}
'''
FS = '''#version 300 es
precision highp float;precision highp int;
uniform int rowStart;out vec4 outColor;
void main(){int x=int(gl_FragCoord.x),y=int(gl_FragCoord.y)+rowStart;
outColor=vec4(float((x*17+y*23)&255),float((x*29+y*7)&255),float((x^y)&255),float((x+y*3)&255))/255.;}
'''


class GL:
    def __init__(self):
        if sys.platform == 'win32':
            lib = C.WinDLL('opengl32.dll')
            get = lib.wglGetProcAddress
            factory = C.WINFUNCTYPE
        else:
            lib = C.CDLL(ctypes.util.find_library('EGL') or 'libEGL.so.1')
            get = lib.eglGetProcAddress
            factory = C.CFUNCTYPE
        get.restype = C.c_void_p
        get.argtypes = [C.c_char_p]
        self.library = lib
        def load(name, result, *args):
            pointer = get(name.encode())
            if pointer in (None, 0, 1, 2, 3, C.c_void_p(-1).value):
                pointer = C.cast(getattr(lib, name), C.c_void_p).value
            return factory(result, *args)(pointer)
        u, i, n, p = C.c_uint, C.c_int, C.c_ssize_t, C.c_void_p
        self.gen = load('glGenBuffers', None, i, C.POINTER(u))
        self.bind = load('glBindBuffer', None, u, u)
        self.data = load('glBufferData', None, u, n, p, u)
        self.read = load('glReadPixels', None, i, i, i, i, u, u, p)
        self.map = load('glMapBufferRange', p, u, n, n, u)
        self.unmap = load('glUnmapBuffer', C.c_ubyte, u)
        self.delete = load('glDeleteBuffers', None, i, C.POINTER(u))
        self.error = load('glGetError', u)
        self.pixel = load('glPixelStorei', None, u, i)
        self.disable = load('glDisable', None, u)

    def clean(self):
        for _ in range(32):
            if self.error() == 0:
                return
        raise AssertionError('persistent GL error')


class Bench:
    def __init__(self):
        try:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=430)
        except Exception:
            self.ctx = moderngl.create_standalone_context(require=430)
        self.gl = GL()
        self.gl.disable(0x0BD0)
        self.gl.pixel(ALIGN, 4)
        self.gl.pixel(ROW, 0)
        self.program = self.ctx.program(vertex_shader=VS, fragment_shader=FS)
        self.vao = self.ctx.vertex_array(self.program, [])
        self.gl.clean()

    def render(self, width, height, tile, pbo, fault=None):
        g, ctx = self.gl, self.ctx
        total = width * height * 4
        texture = ctx.texture((width, min(tile, height)), 4, dtype='f1')
        fb = ctx.framebuffer([texture])
        buffer = C.c_uint()
        mapped = False
        try:
            fb.use()
            if pbo:
                g.gen(1, C.byref(buffer))
                g.bind(PACK, buffer.value)
                size = total - 1 if fault == 'short-buffer' else total
                g.data(PACK, size, None, STREAM)
                assert g.error() == 0
            output = bytearray(total)
            for y in range(0, height, tile):
                rows = min(tile, height-y)
                ctx.viewport = (0, 0, width, rows)
                self.program['rowStart'].value = y
                self.vao.render(vertices=3)
                if pbo:
                    g.read(0, 0, width, rows, RGBA, BYTE, C.c_void_p(y*width*4))
                else:
                    data = C.create_string_buffer(width*rows*4)
                    g.read(0, 0, width, rows, RGBA, BYTE, data)
                    output[y*width*4:(y+rows)*width*4] = data.raw
            error = g.error()
            if fault == 'short-buffer':
                assert error == INVALID, hex(error)
                return None
            assert error == 0, hex(error)
            if pbo:
                pointer = g.map(PACK, 0, total+1 if fault == 'map-range' else total, READ)
                mapped = bool(pointer)
                error = g.error()
                if fault == 'map-range':
                    assert not pointer and error != 0
                    return None
                assert pointer and error == 0
                output[:] = C.string_at(pointer, total)
                assert g.unmap(PACK)
                mapped = False
            assert g.error() == 0
            return bytes(output)
        finally:
            if mapped:
                g.unmap(PACK)
            g.bind(PACK, 0)
            if buffer.value:
                g.delete(1, C.byref(buffer))
            g.clean()
            fb.release()
            texture.release()

    def close(self):
        self.vao.release()
        self.program.release()
        self.ctx.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json', type=Path)
    args = parser.parse_args()
    bench = Bench()
    cases = []
    try:
        renderer = bench.ctx.info['GL_RENDERER']
        sizes = ((1, 1), (5, 3), (127, 257), (640, 480), (1025, 513), (4096, 3072), (4000, 4000))
        for width, height in sizes:
            yy, xx = np.mgrid[:height, :width]
            expected = np.stack(((xx*17+yy*23)&255, (xx*29+yy*7)&255, (xx^yy)&255, (xx+yy*3)&255), -1).astype('u1').tobytes()
            del xx, yy
            for tile in (13, 16, 256):
                times = {False: [], True: []}
                for repeat in range(3):
                    for pbo in ((False, True) if repeat % 2 == 0 else (True, False)):
                        start = time.perf_counter()
                        actual = bench.render(width, height, tile, pbo)
                        times[pbo].append((time.perf_counter()-start)*1000)
                        assert actual == expected, (width, height, tile, pbo)
                cases.append(dict(width=width,height=height,tile=tile,byte_equal=True,
                                  tile_host_ms=statistics.median(times[False]),pbo_host_ms=statistics.median(times[True])))
        fallbacks = []
        for fault in ('short-buffer', 'map-range'):
            assert bench.render(127, 257, 13, True, fault) is None
            direct = bench.render(127, 257, 13, False)
            assert direct == bench.render(127, 257, 13, True)
            fallbacks.append(dict(fault=fault,clean_tile_fallback_equal=True))
        result = dict(renderer=renderer,cases=cases,fallbacks=fallbacks,
                      scope='Host GPU OpenGL RGBA8 tile/client-memory versus pixel-pack buffer, one map and copy; not Android driver/Bitmap or press-to-file timing.')
        if args.json:
            args.json.parent.mkdir(parents=True,exist_ok=True)
            args.json.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print(f'{len(cases)} GPU PBO/client-memory/oracle cases x3 repeats PASS; {len(fallbacks)} GL-error fallbacks PASS; {renderer}')
    finally:
        bench.close()


if __name__ == '__main__':
    main()
