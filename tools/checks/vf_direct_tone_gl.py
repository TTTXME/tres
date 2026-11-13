"""P662 (item 5): the RAW viewfinder's copy pass is the identity where MainRenderer.directTone skips it.

P314's WHY tone is drawn into an RGBA8 target and the filter / peaking pass (main_fs.glsl spliced with the live lens filter and
the Oldstyle code, LiveLensFilter.to2d, MainRenderer's VS_2D) copies it to the screen. With no lens filter, no peaking, no
Oldstyle style, no LUT and gain 1, MainRenderer now draws the tone straight to the screen. This check draws a stand-in of the
combine pass (float colours from a hash, every 8-bit rounding boundary hit, edges of full contrast between neighbours) both ways
- into the RGBA8 target and through the spliced copy program, and straight - into an RGBA8 framebuffer and into an RGB888
window-like pbuffer (GLSurfaceView's default config), at the X200 Ultra viewport 1440 x 1920 and odd sizes, and compares bytes.
Mesa surfaceless EGL (llvmpipe), not a phone: it proves the shader path is the identity, not a driver's texture unit.
"""
from pathlib import Path
import ctypes as C, json, sys

E = C.CDLL('libEGL.so.1'); ptr = C.c_void_p; I = C.c_int; U = C.c_uint; F = C.c_float
getproc = E.eglGetProcAddress; getproc.restype = ptr; getproc.argtypes = [C.c_char_p]


def egl(n, r, a):
    f = getattr(E, n); f.restype = r; f.argtypes = a; return f


def gl(n, r, a):
    return C.CFUNCTYPE(r, *a)(getproc(n.encode()))


root = Path(__file__).resolve().parents[2]
assets = root / 'app/src/main/assets/shaders'
java = (root / 'app/src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java').read_text()

# the app's own text: VS_2D and the GLSL head of MainRenderer.linkProgram
import re
vs_decl = java[java.index('VS_2D = "'):]
vs_decl = vs_decl[:vs_decl.index('}\\n";') + len('}\\n";')]
vs_2d = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', vs_decl)).encode().decode('unicode_escape')
head = java[java.index('GLSL_HEAD = "') + len('GLSL_HEAD = "'):]
head = head[:head.index('";')].encode().decode('unicode_escape')
assert 'gl_Position = vec4(vPosition, 0.0, 1.0)' in vs_2d and 'texCoord = vPosition * 0.5 + 0.5' in vs_2d, vs_2d
assert head.startswith('#version 300 es'), head

# MainRenderer.onSurfaceCreated: oldstyleSplice(LiveLensFilter.splice(main_fs, lensfilter_live), adjust, tone), then to2d
main_fs = (assets / 'preview/main_fs.glsl').read_text()
fs = main_fs.replace('//#lensfilter_live', (assets / 'preview/lensfilter_live.glsl').read_text())
fs = fs.replace('//#oldstyle_adjust', (assets / 'utils/import_oldstyle_adjust.glsl').read_text())
fs = fs.replace('//#oldstyle_tone', (assets / 'utils/import_oldstyle_tone_live.glsl').read_text())
fs = fs.replace('#extension GL_OES_EGL_image_external_essl3 : require', '').replace('samplerExternalOES', 'sampler2D')

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
    gl('glLinkProgram', None, [U])(p); ok = I(); gl('glGetProgramiv', None, [U, U, ptr])(p, 0x8B82, C.byref(ok))
    assert ok.value, 'link'
    return p


loc = lambda p, n: gl('glGetUniformLocation', I, [U, C.c_char_p])(p, n.encode())
glUse = gl('glUseProgram', None, [U])


def ui(p, n, v): gl('glUniform1i', None, [I, I])(loc(p, n), v)


def uf(p, n, v): gl('glUniform1f', None, [I, F])(loc(p, n), v)


# a stand-in of why/combine: float colours, every channel near and on the 8-bit rounding boundaries, full-contrast neighbours
TONE = head + '''precision highp float;
out vec4 Output;
uniform int seedU;
float h(vec2 p, float k) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + k * 0.731 + float(seedU)) * 43758.5453); }
void main() {
    vec2 p = floor(gl_FragCoord.xy);
    vec3 c = vec3(h(p, 1.0), h(p, 2.0), h(p, 3.0));
    int m = int(mod(p.x + 3.0 * p.y, 5.0));
    if (m == 1) c = (floor(c * 255.0) + 0.5) / 255.0;              // exactly on a boundary
    if (m == 2) c = (floor(c * 255.0) + 0.5 + 1e-4) / 255.0;       // just above
    if (m == 3) c = vec3(mod(p.x, 2.0), 1.0 - mod(p.y, 2.0), mod(p.x + p.y, 2.0)); // 0 / 1 checker: edges of 255
    Output = vec4(c, h(p, 4.0));
}
'''
quad = (F * 8)(1.0, -1.0, -1.0, -1.0, 1.0, 1.0, -1.0, 1.0)  # MainRenderer.quadXY


def texture_target(w, h):
    t = U(); gl('glGenTextures', None, [I, ptr])(1, C.byref(t)); gl('glBindTexture', None, [U, U])(0xDE1, t)
    gl('glTexStorage2D', None, [U, I, U, I, I])(0xDE1, 1, 0x8058, w, h)  # RGBA8, as MainRenderer.rawTarget
    for n, v in [(0x2802, 0x812F), (0x2803, 0x812F), (0x2801, 0x2601), (0x2800, 0x2601)]:  # clamp, LINEAR (linearClamp)
        gl('glTexParameteri', None, [U, U, I])(0xDE1, n, v)
    fb = U(); gl('glGenFramebuffers', None, [I, ptr])(1, C.byref(fb)); gl('glBindFramebuffer', None, [U, U])(0x8D40, fb)
    gl('glFramebufferTexture2D', None, [U, U, U, U, I])(0x8D40, 0x8CE0, 0xDE1, t, 0)
    assert gl('glCheckFramebufferStatus', U, [U])(0x8D40) == 0x8CD5
    return t, fb


def draw(p, w, h):
    glUse(p)
    gl('glViewport', None, [I, I, I, I])(0, 0, w, h)
    gl('glEnableVertexAttribArray', None, [U])(0)
    gl('glVertexAttribPointer', None, [U, I, U, U, I, ptr])(0, 2, 0x1406, 0, 8, quad)
    gl('glDrawArrays', None, [U, I, I])(5, 0, 4)


def read(w, h, channels=4):
    b = (C.c_ubyte * (w * h * 4))()
    gl('glPixelStorei', None, [U, I])(0x0D05, 1)
    gl('glReadPixels', None, [I, I, I, I, U, U, ptr])(0, 0, w, h, 0x1908, 0x1401, b)
    assert gl('glGetError', U, [])() == 0
    raw = bytes(b)
    return raw if channels == 4 else b''.join(raw[i:i + 3] for i in range(0, len(raw), 4))


tone = copy = None
dummy3d = None


def copy_uniforms(p, w, h):
    glUse(p)
    ui(p, 'sTexture', 0); ui(p, 'uLut', 1); ui(p, 'uLfHiTex', 2); ui(p, 'uLfLinTex', 3); ui(p, 'uOtLut', 4)
    ui(p, 'enablePeak', 0); ui(p, 'mirror', 0); uf(p, 'uGain', 1.0)
    gl('glUniform2f', None, [I, F, F])(loc(p, 'resolution'), float(w), float(h))
    ui(p, 'uLf', 0); ui(p, 'uLutOn', 0); ui(p, 'uOsOn', 0); ui(p, 'uOtOn', 0)


results = []
surfaces = {}
for w, h in [(1440, 1920), (1080, 1440), (797, 1063), (33, 17)]:
    surf = egl('eglCreatePbufferSurface', ptr, [ptr, ptr, ptr])(d, rgb888, (I * 5)(0x3057, w, 0x3056, h, 0x3038))
    assert surf and egl('eglMakeCurrent', U, [ptr, ptr, ptr, ptr])(d, surf, surf, ctx)
    if tone is None:
        tone = program(head + vs_2d, TONE)
        copy = program(head + vs_2d, head + fs)
        dummy3d = U(); gl('glActiveTexture', None, [U])(0x84C1); gl('glGenTextures', None, [I, ptr])(1, C.byref(dummy3d))
        gl('glBindTexture', None, [U, U])(0x806F, dummy3d)
        gl('glTexImage3D', None, [U, I, I, I, I, I, I, U, U, ptr])(0x806F, 0, 0x8051, 1, 1, 1, 0, 0x1907, 0x1401, (C.c_ubyte * 4)())
        gl('glActiveTexture', None, [U])(0x84C0)
    for seed in range(3):
        glUse(tone); ui(tone, 'seedU', seed)
        # before: the tone into the RGBA8 target, the copy pass onto an RGBA8 framebuffer and onto the RGB888 window
        t, fb = texture_target(w, h)
        draw(tone, w, h)
        target = read(w, h)
        out_t, out_fb = texture_target(w, h)
        gl('glActiveTexture', None, [U])(0x84C0); gl('glBindTexture', None, [U, U])(0xDE1, t)
        copy_uniforms(copy, w, h); draw(copy, w, h)
        copied = read(w, h)
        gl('glBindFramebuffer', None, [U, U])(0x8D40, 0)
        gl('glActiveTexture', None, [U])(0x84C0); gl('glBindTexture', None, [U, U])(0xDE1, t)
        copy_uniforms(copy, w, h); draw(copy, w, h)
        copied_window = read(w, h, 3)
        # after: the tone straight into an RGBA8 framebuffer and into the window
        gl('glBindFramebuffer', None, [U, U])(0x8D40, out_fb)
        glUse(tone); ui(tone, 'seedU', seed); draw(tone, w, h)
        direct = read(w, h)
        gl('glBindFramebuffer', None, [U, U])(0x8D40, 0)
        draw(tone, w, h)
        direct_window = read(w, h, 3)
        rgb = lambda b: b''.join(b[i:i + 3] for i in range(0, len(b), 4))
        assert rgb(copied) == rgb(target), f'the copy pass changed the tone target ({w}x{h} seed {seed})'
        assert rgb(direct) == rgb(copied), f'direct != copy pass ({w}x{h} seed {seed})'
        assert direct_window == copied_window, f'direct != copy pass on the RGB888 window ({w}x{h} seed {seed})'
        diff_alpha = sum(1 for i in range(3, len(direct), 4) if direct[i] != copied[i])
        if seed == 0:
            # the comparison sees a change: the copy pass mirrored (one texel row moved) is not the tone
            gl('glBindFramebuffer', None, [U, U])(0x8D40, 0)
            gl('glActiveTexture', None, [U])(0x84C0); gl('glBindTexture', None, [U, U])(0xDE1, t)
            copy_uniforms(copy, w, h); ui(copy, 'mirror', 1); draw(copy, w, h)
            assert read(w, h, 3) != direct_window, 'the comparison is blind'
        results.append({'size': f'{w}x{h}', 'seed': seed, 'rgb_identical': True, 'window_identical': True,
                        'alpha_bytes_differing_rgba8_target': diff_alpha})
        gl('glDeleteFramebuffers', None, [I, ptr])(1, C.byref(fb)); gl('glDeleteFramebuffers', None, [I, ptr])(1, C.byref(out_fb))
        gl('glDeleteTextures', None, [I, ptr])(1, C.byref(t)); gl('glDeleteTextures', None, [I, ptr])(1, C.byref(out_t))
    egl('eglMakeCurrent', U, [ptr, ptr, ptr, ptr])(d, None, None, None)
    egl('eglDestroySurface', U, [ptr, ptr])(d, surf)
print(json.dumps({'check': 'vf_direct_tone', 'renderer': 'Mesa surfaceless', 'results': results}))
