"""P623 round 2: why/combine HL_LOCAL_SMOOTH - HL_LOCAL's edge-aware mean bilinear over the four whyLow texels round the pixel.

Owner, 2026-11-11 (Ark 140421 / 140250 on «Быстрый»): ragged white teeth along a fog lamp's rim on a white body 7 x white, a dashed
line along a sunlit hood's bright edge. HL_LOCAL took its taps round one whyLow texel (the B-spline base lbx / lby) for all f x f
output pixels, so where the edge-aware mean jumps (a darker rim against a far brighter body) its darkening stepped by 2 px blocks.

Runs the shipped HL_LOCAL block of why/combine.glsl (cut out between its #if and #endif, HL_LOCAL_RIM 1 as WhyCombine sets it) on
the 2 x output grid of a synthetic whyLow (gridPos as the combine's, the pixel's yLow bilinear), with the app's hlLocalU (0.8, 1.5
EV, 6 taps, guard 12), and reads the factor it applies to L:
  - a diagonal rim at 1.6 x white against a body at 7 x white: the per-texel version leaves isolated pixels along the rim
    (darkened against all four neighbours: the teeth); bilinear, its largest step between neighbours at most half the
    per-texel one and at most a tenth of the isolated pixels;
  - inside a flat window above white and away from edges: the factor as before (within 1e-5); a 2 x white blot in a 4 x
    white window darkened as before (within 0.01);
  - f 1 (the same-size grid): bit for bit as before (one texel, one pass of the loop).
"""
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/assets/shaders/why/combine.glsl').read_text(encoding='utf-8')
JAVA = (ROOT / 'app/src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyCombine.java').read_text(encoding='utf-8')
TONE = (ROOT / 'app/src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyTone.java').read_text(encoding='utf-8')
fails = []


def check(ok, what):
    print(('PASS ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)


m = re.search(r'#if HL_LOCAL == 1\n    if \(hlLocalU\.x > 0\.0\) \{\n(.*?)\n    \}\n#endif', SRC, re.S)
check(m is not None, 'the HL_LOCAL block in why/combine.glsl')
check('#define HL_LOCAL_SMOOTH 0\n' in SRC, 'HL_LOCAL_SMOOTH defaults to 0 in the shader (WhyCombine sets it)')
check('glProg.setDefine("HL_LOCAL_SMOOTH", 1);' in JAVA and 'hlLocalSmooth()' in JAVA, 'WhyCombine sets HL_LOCAL_SMOOTH from WhyTone.hlLocalSmooth()')
check('value("hl_local_smooth", 1f)' in TONE, 'scam_dev hybrid_why_hl_local_smooth (default 1)')
BLOCK = m.group(1) if m else ''
HL = (0.8, 1.5, 6.0, 12.0)
GRIDPOS = ('void gridPos(int x, int f, out int base, out float frac) {\n    int q = x / f;\n    int r = x - q * f;\n'
           '    float c = float(2 * r + 1 - f) / float(2 * f);\n    if (c < 0.0) { base = q - 1; frac = c + 1.0; } else { base = q; frac = c; }\n}\n')


def shader(smooth, f):
    return ('#version 430\n#define HL_LOCAL_RIM 1\n#define HL_LOCAL_SMOOTH %d\nuniform sampler2D WhyLow;\nuniform vec4 hlLocalU;\nout vec4 o;\n'
            'const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n' % smooth + GRIDPOS +
            'float yAt(ivec2 p){ivec2 l=textureSize(WhyLow,0)-ivec2(1);return max(dot(texelFetch(WhyLow,clamp(p,ivec2(0),l),0).rgb,LUMA),0.000001);}\n'
            'void main(){ivec2 xy=ivec2(gl_FragCoord.xy);int f=%d;float ae=1.0;int lbx=0,lby=0;float lfx=0.0,lfy=0.0;float yLow;\n'
            ' if(f==1){yLow=yAt(xy);}else{gridPos(xy.x,f,lbx,lfx);gridPos(xy.y,f,lby,lfy);\n'
            '  yLow=mix(mix(yAt(ivec2(lbx,lby)),yAt(ivec2(lbx+1,lby)),lfx),mix(yAt(ivec2(lbx,lby+1)),yAt(ivec2(lbx+1,lby+1)),lfx),lfy);}\n'
            ' vec3 post=vec3(0.9,0.0,0.0);\n'
            '    if (hlLocalU.x > 0.0) {\n' % f + BLOCK + '\n    }\n o=vec4(post.x/0.9,0.0,0.0,1.0);}\n')


try:
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
    progs = {(s, f): ctx.program(vertex_shader=vs, fragment_shader=shader(s, f)) for s in (0, 1) for f in (1, 2)}

    def run(y, smooth, f):
        """y: H x W luminance of the whyLow grid (x white) -> the factor HL_LOCAL applies to L on the f x output grid"""
        h, w = y.shape
        t = ctx.texture((w, h), 3, np.repeat(y[..., None], 3, -1).astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        out = ctx.texture((w * f, h * f), 4, dtype='f4')
        fbo = ctx.framebuffer([out])
        fbo.use()
        ctx.viewport = (0, 0, w * f, h * f)
        p = progs[(smooth, f)]
        t.use(0)
        p['WhyLow'].value = 0
        p['hlLocalU'].value = HL
        ctx.vertex_array(p, []).render(vertices=3)
        r = np.frombuffer(out.read(), np.float32).reshape(h * f, w * f, 4)[..., 0].copy()
        for o in (t, out, fbo):
            o.release()
        return r

    N = 96
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    # a white body at 7 x white with a darker rim band (1.6 x white, 4 texels wide) along a diagonal, the lamp (0.5) below it
    s = (xx + 0.6 * yy) / np.hypot(1.0, 0.6) - 50.0
    body = np.where(s < 0, 7.0, np.where(s < 4, 1.6, 0.5)).astype(np.float32)
    # a soft edge (a texel of transition), as the B-spline whyLow has it
    k = np.exp(-0.5 * (np.arange(-3, 4) / 0.7) ** 2)
    k /= k.sum()
    lb = np.log(body)
    lb = np.apply_along_axis(lambda v: np.convolve(np.pad(v, 3, mode='edge'), k, 'valid'), 1, lb)
    lb = np.apply_along_axis(lambda v: np.convolve(np.pad(v, 3, mode='edge'), k, 'valid'), 0, lb)
    body = np.exp(lb).astype(np.float32)
    a0, a1 = run(body, 0, 2), run(body, 1, 2)
    zone = np.zeros_like(a0, bool)
    S2 = np.kron(s, np.ones((2, 2)))
    zone[(S2 > -14) & (S2 < 6)] = True
    zone[:8] = zone[-8:] = False
    zone[:, :8] = zone[:, -8:] = False

    def steps(a):
        dx = np.abs(np.diff(a, axis=1))[:, :-1][zone[:, 1:-1]]
        return dx.max()

    def isolated(a):
        """output pixels darkened (or not) against all four neighbours by > 0.01: the teeth"""
        c = a[1:-1, 1:-1]
        nb = np.stack([a[:-2, 1:-1], a[2:, 1:-1], a[1:-1, :-2], a[1:-1, 2:]])
        iso = (np.abs(c - nb) > 0.01).all(0) & zone[1:-1, 1:-1]
        return int(iso.sum())

    np.set_printoptions(linewidth=250, precision=3, suppress=True)
    j0, j1 = steps(a0), steps(a1)
    r0, r1 = isolated(a0), isolated(a1)
    print('  rim against a 7 x white body: largest step %.4f per texel, %.4f bilinear; isolated pixels %d, %d' % (j0, j1, r0, r1))
    check(j0 > 0.02, 'the per-texel version steps at the rim (the check sees the teeth)')
    check(j1 <= 0.5 * j0, 'bilinear: the largest step at most half the per-texel one')
    check(r0 > 20 and r1 <= r0 // 10, 'bilinear: no isolated (darker or lighter) pixels along the rim (a tenth of the per-texel teeth at most)')
    flat = np.full((N, N), 4.0, np.float32)
    flat[40:46, 40:46] = 2.0
    b0, b1 = run(flat, 0, 2), run(flat, 1, 2)
    far = np.zeros_like(b0, bool)
    far[10:30, 10:30] = True
    check(np.abs(b0 - b1)[far].max() < 1e-5, 'a flat window above white away from detail: as before')
    blot0, blot1 = b0[84:88, 84:88].mean(), b1[84:88, 84:88].mean()
    print('  a 2 x white blot in a 4 x white window: factor %.3f per texel, %.3f bilinear' % (blot0, blot1))
    check(blot0 < 0.95 and abs(blot0 - blot1) < 0.01, 'the window\'s detail (a blot) darkened as before (within 0.01)')
    c0, c1 = run(body, 0, 1), run(body, 1, 1)
    check(np.array_equal(c0, c1), 'f 1: bit for bit as before')
except ImportError as e:
    print('SKIP GL part: %s' % e)

if fails:
    print('hl local smooth check FAIL: ' + '; '.join(fails))
    sys.exit(1)
print('hl local smooth check PASS')
