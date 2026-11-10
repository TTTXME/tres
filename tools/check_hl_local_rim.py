"""P607: why/combine HL_LOCAL (P508, the local contrast of the light above white) with HL_LOCAL_RIM on a light's falloff.

Runs the shipped HL_LOCAL block of why/combine.glsl (cut out between its #if and #endif) on a synthetic whyLow grid, f 1, ae 1, with
the app's hlLocalU (WhyTone.hlLocal: 0.8, 1.5 EV, 6 taps, guard 12), and reads the factor it applies to L:
  - a lit sign's glow (a plateau 13 x white falling through white to 0.3 over ~12 px): without the guard (HL_LOCAL_RIM 0, as
    before) the falloff one tap inside the light is darkened - the grey band along a sign's glow at night (owner, 2026-11-10);
    with it no pixel on a line that leaves the light is darkened;
  - lettering near a small sign's edge (1 px strokes, lines out of the sign everywhere): darkened exactly as before;
  - detail inside a window above white (a 2 x white blot in a 4 x white plateau, far from its edges): darkened exactly as before;
  - a lamp's core 20 x brighter than its glow (the P508 guard): untouched either way."""
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/assets/shaders/why/combine.glsl').read_text(encoding='utf-8')
JAVA = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyCombine.java').read_text(encoding='utf-8')
TONE = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyTone.java').read_text(encoding='utf-8')
fails = []


def check(ok, what):
    print(('PASS ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)


m = re.search(r'#if HL_LOCAL == 1\n    if \(hlLocalU\.x > 0\.0\) \{\n(.*?)\n    \}\n#endif', SRC, re.S)
check(m is not None, 'the HL_LOCAL block in why/combine.glsl')
check('#define HL_LOCAL_RIM 0\n' in SRC, 'HL_LOCAL_RIM defaults to 0 in the shader (WhyCombine sets it)')
check('glProg.setDefine("HL_LOCAL_RIM", 1);' in JAVA and 'hlLocalRim()' in JAVA, 'WhyCombine sets HL_LOCAL_RIM from WhyTone.hlLocalRim()')
check('value("hl_local_rim", 1f)' in TONE, 'scam_dev hybrid_why_hl_local_rim (default 1)')
BLOCK = m.group(1) if m else ''
HL = (0.8, 1.5, 6.0, 12.0)


def shader(rim):
    return ('#version 430\n#define HL_LOCAL_RIM %d\nuniform sampler2D WhyLow;\nuniform vec4 hlLocalU;\nout vec4 o;\n'
            'const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n'
            'void main(){ivec2 xy=ivec2(gl_FragCoord.xy);int f=1;int lbx=0,lby=0;float ae=1.0;\n'
            'float yLow=max(dot(texelFetch(WhyLow,xy,0).rgb,LUMA),0.000001);vec3 post=vec3(0.9,0.0,0.0);\n'
            '    if (hlLocalU.x > 0.0) {\n' % rim + BLOCK + '\n    }\n o=vec4(post.x/0.9,0.0,0.0,1.0);}\n')


try:
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
    progs = {r: ctx.program(vertex_shader=vs, fragment_shader=shader(r)) for r in (0, 1)}

    def run(y, rim):
        """y: H x W luminance of the whyLow grid (x white) -> the factor HL_LOCAL applies to L"""
        h, w = y.shape
        t = ctx.texture((w, h), 3, np.repeat(y[..., None], 3, -1).astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        out = ctx.texture((w, h), 4, dtype='f4')
        fbo = ctx.framebuffer([out])
        fbo.use()
        ctx.viewport = (0, 0, w, h)
        p = progs[rim]
        t.use(0)
        p['WhyLow'].value = 0
        p['hlLocalU'].value = HL
        ctx.vertex_array(p, []).render(vertices=3)
        r = np.frombuffer(out.read(), np.float32).reshape(h, w, 4)[..., 0].copy()
        for o in (t, out, fbo):
            o.release()
        return r

    N = 160
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    # a lit sign: a 40 x 30 plateau at 13 x white whose glow falls off (exponential, ~3 px per EV) to 0.3 outside
    dx = np.maximum(np.abs(xx - 80) - 20, 0)
    dy = np.maximum(np.abs(yy - 80) - 15, 0)
    d = np.hypot(dx, dy)
    sign = (0.3 + 12.7 * np.exp2(-d / 1.5)).astype(np.float32)
    s0, s1 = run(sign, 0), run(sign, 1)
    band = (sign > 1.0) & (sign < 8.0)
    print('sign glow: the falloff\'s darkest factor %.3f before, %.3f now (its plateau %.3f / %.3f)'
          % (s0[band].min(), s1[band].min(), s0[sign > 12.9].min(), s1[sign > 12.9].min()))
    check(s0[band].min() < 0.97, 'sign glow, as before: the falloff darkened (the grey band, the test sees it)')
    check(s1[band].min() > 0.995 and s1[sign > 12.9].min() > 0.995, 'sign glow: the falloff and the plateau not darkened')
    # a small lit sign (36 x 20) with lettering near its edge: 1 px strokes at 3 x white on its 12 x white, every 4 px - lines
    # out of the sign everywhere; the strokes are fine dips of their own: darkened as before (the letters keep their contrast)
    sg = np.full((N, N), 0.3, np.float32)
    sg[70:90, 62:98] = 12.0
    strokes = np.zeros_like(sg, bool)
    strokes[73:87, 64:96:4] = True
    sg[strokes] = 3.0
    g0, g1 = run(sg, 0), run(sg, 1)
    print('sign lettering: the strokes\' factor mean %.3f before, %.3f now' % (float(g0[strokes].mean()), float(g1[strokes].mean())))
    check(g0[strokes].mean() < 0.97 and np.abs(g0 - g1)[strokes].max() < 2e-3, 'lettering near a sign\'s edge: darkened as before')
    # a window: 4 x white with a 2 x white blot (6 x 6) in its middle, the window 120 x 120 (its edges > 2 taps from the blot)
    win = np.full((N, N), 0.2, np.float32)
    win[20:140, 20:140] = 4.0
    win[77:83, 77:83] = 2.0
    w0, w1 = run(win, 0), run(win, 1)
    inner = np.zeros_like(win, bool)
    inner[60:100, 60:100] = True
    print('window detail: the blot\'s factor %.3f before, %.3f now; inner area max |diff| %.2e'
          % (w0[80, 80], w1[80, 80], float(np.abs(w0 - w1)[inner].max())))
    check(w0[80, 80] < 0.95 and np.abs(w0 - w1)[inner].max() < 2e-3, 'window detail away from its edges: darkened as before')
    # a lamp: a core 20 x its glow (the P508 guard)
    r = np.hypot(xx - 80, yy - 80)
    lamp = (0.3 + 1.7 * np.exp(-(r / 25) ** 2) + 40.0 * np.exp(-(r / 3) ** 2)).astype(np.float32)
    l0, l1 = run(lamp, 0), run(lamp, 1)
    near = (r > 3) & (r < 12)
    print('lamp: the glow round the core %.3f before, %.3f now' % (l0[near].min(), l1[near].min()))
    check(l1[near].min() >= l0[near].min() - 1e-6, 'lamp core and glow: not darker than before')
    ctx.release()
except ImportError:
    print('moderngl missing: GL part skipped')
    sys.exit(1)
print('hl local rim check ' + ('FAIL' if fails else 'PASS'))
sys.exit(1 if fails else 0)
