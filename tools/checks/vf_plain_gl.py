"""P705: the ISP viewfinder's plain program gives main_fs.glsl's bytes wherever MainRenderer takes it.

MainRenderer draws the camera's frame with main_plain_fs.glsl instead of main_fs.glsl (spliced with the live lens filter and the
Oldstyle code) while nothing of those is drawn: no lens filter, LUT, Oldstyle style or tone, no focus peaking. This check draws one
frame both ways - a frame texture of hashed bytes, every 8-bit value, full-contrast neighbours - with main_vs.glsl, unmirrored and
mirrored, at gain 1 (the viewfinder's frames) and at the gains of a series frame (P90), into an RGBA8 framebuffer and into an
RGB888 window-like pbuffer, at the X200 Ultra viewport and odd sizes, and compares the bytes. The frame is a 2D texture here
(LiveLensFilter.to2d on both: Mesa has no external one), Mesa surfaceless EGL (llvmpipe), not a phone.
"""
from pathlib import Path
import ctypes as C, json, random

E = C.CDLL('libEGL.so.1'); ptr = C.c_void_p; I = C.c_int; U = C.c_uint; F = C.c_float
getproc = E.eglGetProcAddress; getproc.restype = ptr; getproc.argtypes = [C.c_char_p]


def egl(n, r, a):
    f = getattr(E, n); f.restype = r; f.argtypes = a; return f


def gl(n, r, a):
    return C.CFUNCTYPE(r, *a)(getproc(n.encode()))


root = Path(__file__).resolve().parents[2]
assets = root / 'app/src/main/assets/shaders'
java = (root / 'app/src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java').read_text()

# the GLSL head of MainRenderer.linkProgram
head = java[java.index('GLSL_HEAD = "') + len('GLSL_HEAD = "'):]
head = head[:head.index('";')].encode().decode('unicode_escape')
assert head.startswith('#version 300 es'), head
assert 'main_plain_fs.glsl' in java, 'MainRenderer no longer links the plain program'

# MainRenderer.onSurfaceCreated: oldstyleSplice(LiveLensFilter.splice(main_fs, lensfilter_live), adjust, tone), then to2d
main_fs = (assets / 'preview/main_fs.glsl').read_text()
fs = main_fs.replace('//#lensfilter_live', (assets / 'preview/lensfilter_live.glsl').read_text())
fs = fs.replace('//#oldstyle_adjust', (assets / 'utils/import_oldstyle_adjust.glsl').read_text())
fs = fs.replace('//#oldstyle_tone', (assets / 'utils/import_oldstyle_tone_live.glsl').read_text())
to2d = lambda s: s.replace('#extension GL_OES_EGL_image_external_essl3 : require', '').replace('samplerExternalOES', 'sampler2D')
fs = to2d(fs)
plain_fs = to2d((assets / 'preview/main_plain_fs.glsl').read_text())
vs = (assets / 'preview/main_vs.glsl').read_text()

d = C.CFUNCTYPE(ptr, U, ptr, ptr)(getproc(b'eglGetPlatformDisplayEXT'))(0x31DD, None, None)
assert egl('eglInitialize', U, [ptr, ptr, ptr])(d, None, None)
assert egl('eglBindAPI', U, [U])(0x30A0)


def config(alpha):
    # GLSurfaceView's default chooser (ComponentSizeChooser 8, 8, 8, 0): the config of exactly these sizes, not the first of
    # eglChooseConfig's sorted list (Mesa lists a 10-bit one first)
    configs = (ptr * 256)(); n = I()
    a = (I * 13)(0x3033, 1, 0x3040, 0x40, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, alpha, 0x3038)
    assert egl('eglChooseConfig', U, [ptr, ptr, ptr, I, ptr])(d, a, configs, 256, C.byref(n)) and n.value > 0
    get = egl('eglGetConfigAttrib', U, [ptr, ptr, I, ptr])
    for i in range(n.value):
        v = {}
        for name, attr in [('r', 0x3024), ('g', 0x3023), ('b', 0x3022), ('a', 0x3021)]:
            x = I(); get(d, configs[i], attr, C.byref(x)); v[name] = x.value
        if v == {'r': 8, 'g': 8, 'b': 8, 'a': alpha}:
            return configs[i]
    raise AssertionError('no config of exactly 8 / 8 / 8 / %d' % alpha)


rgb888 = config(0)
ctx = egl('eglCreateContext', ptr, [ptr, ptr, ptr, ptr])(d, rgb888, None, (I * 3)(0x3098, 3, 0x3038))
assert ctx


def shader(kind, text):
    s = gl('glCreateShader', U, [U])(kind); b = C.c_char_p(text.encode())
    gl('glShaderSource', None, [U, I, ptr, ptr])(s, 1, C.byref(b), None); gl('glCompileShader', None, [U])(s)
    ok = I(); gl('glGetShaderiv', None, [U, U, ptr])(s, 0x8B81, C.byref(ok))
    if not ok.value:
        log = C.create_string_buffer(8192); gl('glGetShaderInfoLog', None, [U, I, ptr, ptr])(s, 8192, None, log)
        raise AssertionError(log.value)
    return s


def program(vs, frag):
    p = gl('glCreateProgram', U, [])()
    for k, t in [(0x8B31, vs), (0x8B30, frag)]:
        gl('glAttachShader', None, [U, U])(p, shader(k, t))
    gl('glBindAttribLocation', None, [U, U, C.c_char_p])(p, 0, b'vPosition')
    gl('glBindAttribLocation', None, [U, U, C.c_char_p])(p, 1, b'vTexCoord')
    gl('glLinkProgram', None, [U])(p); ok = I(); gl('glGetProgramiv', None, [U, U, ptr])(p, 0x8B82, C.byref(ok))
    assert ok.value, 'link'
    return p


loc = lambda p, n: gl('glGetUniformLocation', I, [U, C.c_char_p])(p, n.encode())
glUse = gl('glUseProgram', None, [U])


def ui(p, n, v): gl('glUniform1i', None, [I, I])(loc(p, n), v)


def uf(p, n, v): gl('glUniform1f', None, [I, F])(loc(p, n), v)



quad = (F * 8)(1.0, -1.0, -1.0, -1.0, 1.0, 1.0, -1.0, 1.0)  # MainRenderer.quadXY
uvs = (F * 8)(1.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0)    # MainRenderer.quadUV


def turn(degrees):
    # android.opengl.Matrix.setRotateM about z (column-major), as MainRenderer.setOrientation
    import math
    r = math.radians(degrees); c, s = math.cos(r), math.sin(r)
    if degrees % 90 == 0:
        c, s = round(c), round(s)
    return (F * 16)(c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)


def frame_texture(w, h, seed):
    rnd = random.Random(seed)
    data = bytearray(w * h * 4)
    for i in range(w * h):
        x, y = i % w, i // w
        if (x + 2 * y) % 7 == 3:
            v = [255 * ((x + y) & 1)] * 3            # 0 / 255 neighbours
        else:
            v = [(i * 3 + k * 85 + seed) & 255 if (x + y) % 3 == 0 else rnd.randrange(256) for k in range(3)]
        data[i * 4:i * 4 + 4] = bytes(v + [255])
    t = U(); gl('glActiveTexture', None, [U])(0x84C0)
    gl('glGenTextures', None, [I, ptr])(1, C.byref(t)); gl('glBindTexture', None, [U, U])(0xDE1, t)
    gl('glPixelStorei', None, [U, I])(0x0CF5, 1)
    gl('glTexImage2D', None, [U, I, I, I, I, I, U, U, ptr])(0xDE1, 0, 0x8058, w, h, 0, 0x1908, 0x1401,
                                                          (C.c_ubyte * len(data)).from_buffer(data))
    for n, v in [(0x2802, 0x812F), (0x2803, 0x812F), (0x2801, 0x2601), (0x2800, 0x2601)]:  # MainRenderer.makeIspTexture
        gl('glTexParameteri', None, [U, U, I])(0xDE1, n, v)
    return t


def target(w, h):
    t = U(); gl('glGenTextures', None, [I, ptr])(1, C.byref(t)); gl('glBindTexture', None, [U, U])(0xDE1, t)
    gl('glTexStorage2D', None, [U, I, U, I, I])(0xDE1, 1, 0x8058, w, h)
    fb = U(); gl('glGenFramebuffers', None, [I, ptr])(1, C.byref(fb)); gl('glBindFramebuffer', None, [U, U])(0x8D40, fb)
    gl('glFramebufferTexture2D', None, [U, U, U, U, I])(0x8D40, 0x8CE0, 0xDE1, t, 0)
    assert gl('glCheckFramebufferStatus', U, [U])(0x8D40) == 0x8CD5
    return t, fb


def draw(p, w, h, frame, mirror, gain, degrees, full):
    glUse(p)
    gl('glActiveTexture', None, [U])(0x84C0); gl('glBindTexture', None, [U, U])(0xDE1, frame)
    ui(p, 'sTexture', 0); ui(p, 'mirror', mirror); uf(p, 'uGain', gain)
    gl('glUniformMatrix4fv', None, [I, I, U, ptr])(loc(p, 'uTexRotateMatrix'), 1, 0, turn(degrees))
    if full:
        # main_fs's other samplers on their own units (MainRenderer.pinSamplers) and everything else off, as MainRenderer sets it
        ui(p, 'uLut', 1); ui(p, 'uLfHiTex', 2); ui(p, 'uLfLinTex', 3); ui(p, 'uOtLut', 13)
        ui(p, 'enablePeak', 0); ui(p, 'uLf', 0); ui(p, 'uLutOn', 0); ui(p, 'uOsOn', 0); ui(p, 'uOtOn', 0)
        gl('glUniform2f', None, [I, F, F])(loc(p, 'resolution'), float(w), float(h))
    gl('glViewport', None, [I, I, I, I])(0, 0, w, h)
    for a, buf in [(0, quad), (1, uvs)]:
        gl('glEnableVertexAttribArray', None, [U])(a)
        gl('glVertexAttribPointer', None, [U, I, U, U, I, ptr])(a, 2, 0x1406, 0, 8, buf)
    gl('glDrawArrays', None, [U, I, I])(5, 0, 4)


def read(w, h, channels=4):
    b = (C.c_ubyte * (w * h * 4))()
    gl('glPixelStorei', None, [U, I])(0x0D05, 1)
    gl('glReadPixels', None, [I, I, I, I, U, U, ptr])(0, 0, w, h, 0x1908, 0x1401, b)
    assert gl('glGetError', U, [])() == 0
    raw = bytes(b)
    return raw if channels == 4 else b''.join(raw[i:i + 3] for i in range(0, len(raw), 4))


results = []
full = plain = dummy3d = None
for w, h, fw, fh in [(1440, 1920, 1440, 1080), (1080, 1440, 1440, 1080), (797, 1063, 640, 480), (33, 17, 31, 19)]:
    surf = egl('eglCreatePbufferSurface', ptr, [ptr, ptr, ptr])(d, rgb888, (I * 5)(0x3057, w, 0x3056, h, 0x3038))
    assert surf and egl('eglMakeCurrent', U, [ptr, ptr, ptr, ptr])(d, surf, surf, ctx)
    if full is None:
        full = program(head + vs, head + fs)
        plain = program(head + vs, head + plain_fs)
        dummy3d = U(); gl('glActiveTexture', None, [U])(0x84C1); gl('glGenTextures', None, [I, ptr])(1, C.byref(dummy3d))
        gl('glBindTexture', None, [U, U])(0x806F, dummy3d)
        gl('glTexImage3D', None, [U, I, I, I, I, I, I, U, U, ptr])(0x806F, 0, 0x8051, 1, 1, 1, 0, 0x1907, 0x1401, (C.c_ubyte * 4)())
        gl('glActiveTexture', None, [U])(0x84C0)
    frame = frame_texture(fw, fh, w)
    for mirror, gain, degrees in [(0, 1.0, 90), (1, 1.0, 270), (0, 1.7, 90), (1, 0.6, 180), (0, 1.0, 0)]:
        t, fb = target(w, h)
        draw(full, w, h, frame, mirror, gain, degrees, True); a = read(w, h)
        draw(plain, w, h, frame, mirror, gain, degrees, False); b = read(w, h)
        assert a == b, f'plain != main_fs into RGBA8 ({w}x{h} mirror {mirror} gain {gain} turn {degrees})'
        gl('glBindFramebuffer', None, [U, U])(0x8D40, 0)
        draw(full, w, h, frame, mirror, gain, degrees, True); aw = read(w, h, 3)
        draw(plain, w, h, frame, mirror, gain, degrees, False); bw = read(w, h, 3)
        assert aw == bw, f'plain != main_fs on the RGB888 window ({w}x{h} mirror {mirror} gain {gain} turn {degrees})'
        if mirror == 0 and gain == 1.0 and degrees == 90:
            # the comparison sees a change: the plain program mirrored is not main_fs unmirrored
            draw(plain, w, h, frame, 1, gain, degrees, False)
            assert read(w, h, 3) != aw, 'the comparison is blind'
        results.append({'size': f'{w}x{h}', 'frame': f'{fw}x{fh}', 'mirror': mirror, 'gain': gain, 'turn': degrees,
                        'identical': True})
        gl('glDeleteFramebuffers', None, [I, ptr])(1, C.byref(fb)); gl('glDeleteTextures', None, [I, ptr])(1, C.byref(t))
    gl('glDeleteTextures', None, [I, ptr])(1, C.byref(frame))
    egl('eglMakeCurrent', U, [ptr, ptr, ptr, ptr])(d, None, None, None)
    egl('eglDestroySurface', U, [ptr, ptr])(d, surf)
print(json.dumps({'check': 'vf_plain', 'renderer': 'Mesa surfaceless', 'results': results}))
