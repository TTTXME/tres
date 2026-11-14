#!/usr/bin/env python3
"""P614 «Why тон» V2 on the host: the SHIPPED shaders (why/v2_*.glsl, as GLInterface builds them) driven as WhyV2Stats /
WhyV2Fusion / WhyV2Tone drive them, and the SHIPPED Java statistics (WhyV2Ae.java, javac) - for tools/check_why_v2_tone.py and
host renders of whole frames.

  render(clean, luma_s=None, p3=False) -> (HxWx3 float32 display RGB 0..1, the AE result dict)

clean: HxWx3 float32 linear sRGB / Rec.709, white-balanced (what why/v2_clean writes: our colour chain's output). luma_s: our
sharpened luminance (WhyLumaSharpen) or None for its guide unsharpened (why/sharp_in REF_LUMA: the reference's luma_extract). bento / noise: the
merge's Bento headroom flag and the one-frame noise model (variance S x + O). Environment: EGL_PLATFORM=surfaceless
LIBGL_ALWAYS_SOFTWARE=1 (set here when absent).
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE', '1')

import numpy as np  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SH = ROOT / 'app/src/main/assets/shaders'
JAVA = ROOT / 'app/src/main/java/com/scamera/app/processing'
sys.path.insert(0, str(ROOT / 'tools'))
from check_colour_output import expand_imports, set_define  # noqa: E402

PKG = 'com.scamera.app.processing.opengl.postpipeline'
LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)

# WhyV2Fusion.FULL_LIMIT / WhyV2Stats.MAX_CEILING / WhyV2Tone.CURVE_SAMPLES (read from the sources below, checked there)
FULL_LIMIT = 100_000_000
MAX_CEILING = 8.0
CURVE_SAMPLES = 1024

DRIVER = r'''
package %s;
import java.io.*; import java.nio.*; import java.nio.file.*;
public final class WhyV2AeHost {
    public static void main(String[] a) throws Exception {
        // a[0]: float32 samples file (sw*sh*3, x fastest), a[1] sw, a[2] sh, a[3] stride, a[4] ceiling, a[5] S, a[6] O,
        // a[7] face rect "l,t,r,b" or "-", a[8] (optional) a file of Params overrides "field=value" (curveMods: 7 numbers, commas)
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[0]))).order(ByteOrder.LITTLE_ENDIAN);
        int sw = Integer.parseInt(a[1]), sh = Integer.parseInt(a[2]), stride = Integer.parseInt(a[3]);
        float ceiling = Float.parseFloat(a[4]);
        float[] face = null;
        if (!a[7].equals("-")) { String[] f = a[7].split(","); face = new float[4]; for (int i = 0; i < 4; i++) face[i] = Float.parseFloat(f[i]); }
        WhyV2Ae.Params p = new WhyV2Ae.Params();
        if (a.length > 8) for (String line : Files.readAllLines(Paths.get(a[8]))) {
            int eq = line.indexOf('='); if (eq < 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            java.lang.reflect.Field fl = WhyV2Ae.Params.class.getField(k);
            if (k.equals("curveMods")) { String[] m = v.split(","); for (int i = 0; i < 7; i++) p.curveMods[i] = Float.parseFloat(m[i]); }
            else if (fl.getType() == float.class) fl.setFloat(p, Float.parseFloat(v));
            else if (fl.getType() == int.class) fl.setInt(p, Integer.parseInt(v));
            else if (fl.getType() == boolean.class) fl.setBoolean(p, Boolean.parseBoolean(v));
            else fl.set(p, v);
        }
        float clip = WhyV2Ae.clipCeiling(ceiling);
        WhyV2Ae.Hist h = new WhyV2Ae.Hist();
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
            int o = (y * sw + x) * 12;
            float cx = x * stride, cy = y * stride;
            boolean in = face != null && cx >= face[0] && cx <= face[2] && cy >= face[1] && cy <= face[3];
            WhyV2Ae.add(h, b.getFloat(o), b.getFloat(o + 4), b.getFloat(o + 8), clip, in);
        }
        WhyV2Ae.Result r = WhyV2Ae.finish(h, p, ceiling, Float.parseFloat(a[5]), Float.parseFloat(a[6]));
        float[] em = WhyV2Ae.exposures(r), lw = WhyV2Ae.layerWeights(p);
        float[] in = WhyV2Ae.insetMatrix(p.agxInset), out = WhyV2Ae.outsetMatrix(p.agxInset);
        StringBuilder s = new StringBuilder();
        s.append("ae=").append(r.ae).append("\nclip=").append(r.clip).append("\neffS=").append(r.effS).append("\neffH=").append(r.effH)
         .append("\ncb=").append(r.contrastBoost).append("\nslope=").append(r.agxSlope).append("\nacesD=").append(r.acesD)
         .append("\nuchiA=").append(r.uchiA).append("\nmacro=").append(r.macroContrast).append("\ngeo=").append(r.geo)
         .append("\np10=").append(r.p10).append("\np50=").append(r.p50).append("\np98=").append(r.p98).append("\np995=").append(r.p995)
         .append("\ndeficit=").append(r.deficit).append("\nflat=").append(r.flat).append("\nnoiseCap=").append(r.noiseCap)
         .append("\ndescribe=").append(r.describe());
        for (int i = 0; i < 4; i++) s.append("\nem").append(i).append("=").append(em[i]).append("\nlw").append(i).append("=").append(lw[i]);
        for (int i = 0; i < 9; i++) s.append("\nin").append(i).append("=").append(in[i]).append("\nout").append(i).append("=").append(out[i]);
        for (java.lang.reflect.Field fl : WhyV2Ae.Params.class.getFields()) {
            if (fl.getType() == float.class) s.append("\nP.").append(fl.getName()).append("=").append(fl.getFloat(p));
            else if (fl.getType() == int.class) s.append("\nP.").append(fl.getName()).append("=").append(fl.getInt(p));
        }
        s.append("\nP.agxHueLock=").append(p.agxHueLock ? 1 : 0).append("\nP.skinHueCenterRad=").append(p.skinHueCenterRad());
        float[] c = WhyV2Curve.samples(p.curvePoints, p.curveMods, %d);
        StringBuilder cs = new StringBuilder();
        for (int i = 0; i < c.length; i++) cs.append(i == 0 ? "" : ",").append(c[i]);
        s.append("\ncurve=").append(cs);
        StringBuilder hs = new StringBuilder();
        for (int i = 0; i < WhyV2Ae.BINS; i++) hs.append(i == 0 ? "" : ",").append(h.luma[i]);
        for (int i = 0; i < WhyV2Ae.BINS; i++) hs.append(",").append(h.hl[i]);
        s.append("\nhist=").append(hs).append("\ntotal=").append(h.total);
        System.out.println(s);
    }
}
''' % (PKG, CURVE_SAMPLES)

_JAVA_DIR = None


def _java_build():
    """javac WhyV2Ae.java + ToneCurve.java (plain Java: java.util and androidx.annotation stubs) and the driver once per process."""
    global _JAVA_DIR
    if _JAVA_DIR is not None:
        return _JAVA_DIR
    d = Path(tempfile.mkdtemp(prefix='why_v2_java_'))
    src = d / 'src'
    pkg = src / PKG.replace('.', '/')
    pkg.mkdir(parents=True)
    shutil.copy(JAVA / 'opengl/postpipeline/WhyV2Ae.java', pkg / 'WhyV2Ae.java')
    shutil.copy(JAVA / 'opengl/postpipeline/WhyV2Curve.java', pkg / 'WhyV2Curve.java')
    (pkg / 'WhyV2AeHost.java').write_text(DRIVER, encoding='utf-8')
    col = src / 'com/scamera/app/processing/color'
    col.mkdir(parents=True)
    shutil.copy(JAVA / 'color/ToneCurve.java', col / 'ToneCurve.java')
    ann = src / 'androidx/annotation'
    ann.mkdir(parents=True)
    (ann / 'Nullable.java').write_text('package androidx.annotation;\npublic @interface Nullable {}\n', encoding='utf-8')
    out = d / 'classes'
    out.mkdir()
    files = [str(p) for p in src.rglob('*.java')]
    subprocess.run(['javac', '-nowarn', '-encoding', 'UTF-8', '-d', str(out)] + files, check=True, capture_output=True)
    _JAVA_DIR = out
    return out


def java_ae(samples, stride, ceiling=1.0, noise=(0.0, 0.0), face=None, params=None):
    """WhyV2Ae on strided samples (sh x sw x 3 float32) through the shipped Java: a dict of the result and the parameters.
    params: {Params field: value} overrides (curveMods a 7-list)."""
    out = _java_build()
    with tempfile.NamedTemporaryFile(suffix='.f32', delete=False) as f:
        f.write(np.ascontiguousarray(samples, '<f4').tobytes())
        name = f.name
    try:
        sh, sw = samples.shape[:2]
        args = ['java', '-cp', str(out), PKG + '.WhyV2AeHost', name, str(sw), str(sh), str(stride), repr(float(ceiling)),
                repr(float(noise[0])), repr(float(noise[1])), '-' if face is None else ','.join(map(str, face))]
        pf = None
        if params:
            pf = name + '.params'
            with open(pf, 'w') as fh:
                for k, v in params.items():
                    fh.write('%s=%s\n' % (k, ','.join(repr(float(x)) for x in v) if isinstance(v, (list, tuple)) else
                                          (str(v).lower() if isinstance(v, bool) else v)))
            args.append(pf)
        res = subprocess.run(args, check=True, capture_output=True, text=True)
    finally:
        os.unlink(name)
        if params and pf:
            os.unlink(pf)
    r = {}
    for line in res.stdout.splitlines():
        k, _, v = line.partition('=')
        r[k] = v
    for k in list(r):
        if k in ('describe', 'curve', 'hist'):
            continue
        r[k] = float(r[k])
    r['curve'] = np.array([float(x) for x in r['curve'].split(',')], np.float32)
    hist = np.array([int(x) for x in r['hist'].split(',')], np.int64)
    r['hist_luma'], r['hist_hl'] = hist[:4096], hist[4096:]
    return r


def stride(w, h):
    return max(1, max(w // 1024, h // 768))


def levels(w, h):
    out = [(w, h)]
    while w > 48 and h > 48 and len(out) <= 11:
        w = w >> 1
        h = h // 2 if h > 3 else 1
        out.append((w, h))
    return out


class Gpu:
    def __init__(self):
        import moderngl
        self.mgl = moderngl
        try:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=430)
        except Exception:
            self.ctx = moderngl.create_standalone_context(require=430)
        self.progs = {}
        self.vs = '#version 310 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'

    def prog(self, name, defines=None):
        key = (name, tuple(sorted((defines or {}).items())))
        if key not in self.progs:
            src = (SH / ('why/%s.glsl' % name)).read_text(encoding='utf-8')
            for k, v in (defines or {}).items():
                src = set_define(src, k, str(v))
            # GLInterface.readProgram: the app's version line and highp int ahead of a shader without its own
            self.progs[key] = self.ctx.program(vertex_shader=self.vs,
                                               fragment_shader='#version 310 es\nprecision highp int;\n' + expand_imports(src))
        return self.progs[key]

    def tex(self, a, dtype='f2', linear=False):
        a = np.ascontiguousarray(a)
        comps = 1 if a.ndim == 2 else a.shape[2]
        data = a.astype(np.float16 if dtype == 'f2' else np.float32).tobytes()
        t = self.ctx.texture((a.shape[1], a.shape[0]), comps, data, dtype=dtype)
        f = self.mgl.LINEAR if linear else self.mgl.NEAREST
        t.filter = (f, f)
        t.repeat_x = t.repeat_y = False
        return t

    def target(self, size, comps, dtype='f2', linear=False):
        t = self.ctx.texture(size, comps, dtype=dtype)
        f = self.mgl.LINEAR if linear else self.mgl.NEAREST
        t.filter = (f, f)
        t.repeat_x = t.repeat_y = False
        return t

    def draw(self, p, out, textures, uniforms):
        fbo = self.ctx.framebuffer([out])
        fbo.use()
        self.ctx.viewport = (0, 0) + out.size
        for unit, (k, t) in enumerate(textures.items()):
            t.use(unit)
            if k in p:
                p[k].value = unit
        for k, v in uniforms.items():
            if k in p:
                p[k].value = v
        self.ctx.vertex_array(p, []).render(vertices=3)
        fbo.release()
        return out

    def read(self, t, comps):
        dt = np.float16 if t.dtype == 'f2' else np.float32
        w, h = t.size
        return np.frombuffer(t.read(), dt).reshape(h, w, t.components).astype(np.float32)[..., :comps]


_GPU = None


def gpu():
    global _GPU
    if _GPU is None:
        _GPU = Gpu()
    return _GPU


def statistics(clean, bento=False, noise=(0.0, 0.0), face=None, params=None):
    """WhyV2Stats on the host: why/v2_stride on the RGBA16F clean RGB, the samples' maximum, the ceiling, the shipped Java AE."""
    g = gpu()
    h, w = clean.shape[:2]
    rgba = np.concatenate([clean, np.ones((h, w, 1), np.float32)], -1)
    ct = g.tex(rgba, 'f2', linear=True)   # WhyV2Stats: LINEAR (hr_extract reads it bilinear)
    s = stride(w, h)
    sw, sh = (w + s - 1) // s, (h + s - 1) // s
    o = g.draw(g.prog('v2_stride'), g.target((sw, sh), 4), {'u_tex_rgb': ct}, {'u_stride': s})
    samples = g.read(o, 3)
    fin = samples[np.isfinite(samples)]
    mx = float(max(fin.max(), 0.0)) if fin.size else 0.0
    ceiling = MAX_CEILING if bento else 1.0                 # WhyV2Stats.clipCeiling: the reference's highlight factor
    r = java_ae(samples, s, ceiling, noise, face, params)
    r['stride'] = s
    r['max'] = mx
    return ct, r


class _BandApi:
    """why_v2_bands' drawing interface on this module's Gpu."""
    def __init__(self, g):
        self.g = g

    def target(self, w, h, comps, linear):
        return self.g.target((w, h), comps, linear=linear)

    def draw(self, name, defines, out, textures, uniforms):
        self.g.draw(self.g.prog(name, {k: v for k, v in defines.items()} or None), out, dict(textures), uniforms)


def render(clean, luma_s=None, bento=False, noise=(0.0, 0.0), p3=False, face=None, ae=None, keep=None, uniforms=None, fixed=None,
           quant8=False, scale=None, params=None, lut=None, lut_strength=1.0, jbu=None, bands=None):
    """The V2 chain on the host (see the module doc). ae: a dict to override the AE result's keys (an A/B), uniforms: of the
    tonemap pass (an A/B), keep: a dict that receives the intermediate planes (fused) when given, fixed: the statistics' result of
    another image to render with (no statistics of this one), quant8: the QUANT8 define (an 8-bit photo), scale: force level 0's
    scale (1 or 2; None: WhyV2Fusion.baseScale), params: WhyV2Ae.Params overrides (the V2 settings), lut: an N x N x N x 3
    film LUT (FILM_LUT, its strength lut_strength), jbu (P673): (guide, sigma_r, log floor) of the collapse's joint bilateral
    upsampling (JBU <guide>: 1 the lumas, 2 the fused luma; log floor > 0: the guide in log2); None / sigma 0: JBU 0, as before;
    bands (P677): None = BANDS 0 (as before), else the guide's noise (S', O') x AE or () for none - V1's P391 edge-aware bands
    (why_v2_bands, WhyTone's band constants)."""
    g = gpu()
    h, w = clean.shape[:2]
    if fixed is not None:
        ct = g.tex(np.concatenate([clean, np.ones((h, w, 1), np.float32)], -1), 'f2', linear=True)
        r = dict(fixed)
    else:
        ct, r = statistics(clean, bento, noise, face, params)
    if ae:
        r.update(ae)
    if luma_s is None:
        # no sharpening: WhyLumaSharpen's guide itself, why/sharp_in REF_LUMA (the reference's luma_extract of the clean RGB, R16F),
        # drawn before WhyV2Highlights as on the phone
        lt = g.draw(g.prog('sharp_in', {'REF_LUMA': 1}), g.target((w, h), 1), {'CleanBuffer': ct}, {})
    else:
        lt = g.tex(luma_s.astype(np.float32), 'f2')
    ct = highlights(g, ct, r)
    if scale is None:
        scale = 2 if w * h > FULL_LIMIT else 1
    lv = levels((w + scale - 1) // scale, (h + scale - 1) // scale)
    n = len(lv)
    G = [g.target(lv[i], 4, linear=True) for i in range(n)]
    W = [g.target(lv[i], 4, linear=True) for i in range(n)]
    F = [g.target(lv[i], 1, linear=True) for i in range(n)]
    em = (r['em0'], r['em1'], r['em2'], r['em3'])
    lw = (r['lw0'], r['lw1'], r['lw2'], r['lw3'])
    for mode in (0, 1):
        d = {'MODE': mode}
        if scale == 2:
            d['SCALE'] = 2
        g.draw(g.prog('v2_ef_prep', d), G[0] if mode == 0 else W[0], {'u_tex_clean_rgb': ct},
               {'u_width': lv[0][0], 'u_height': lv[0][1], 'u_exp_multipliers': em, 'u_layer_weights': lw,
                'u_weight_center': r['P.weightCenter'], 'u_auto_exposure': r['ae'], 'u_blend_smoothness': r['P.blendSmoothness'],
                'u_gamma_curve_type': int(r['P.gammaType']), 'u_gamma_inv': 1.0 / max(r['P.gamma'], 1.0), 'u_aces_toe': r['P.acesToe']})
    for i in range(n - 1):
        for pyr in (G, W):
            g.draw(g.prog('v2_pyr_down'), pyr[i + 1], {'u_tex_src': pyr[i]},
                   {'u_texel_size': (1.0 / lv[i][0], 1.0 / lv[i][1]), 'u_dst_w': lv[i + 1][0], 'u_dst_h': lv[i + 1][1]})
    bst = None
    if bands is not None:
        import why_v2_bands as VB
        pu = {'_clean': ct, 'u_width': lv[0][0], 'u_height': lv[0][1], 'u_exp_multipliers': em, 'u_layer_weights': lw,
              'u_weight_center': r['P.weightCenter'], 'u_auto_exposure': r['ae'], 'u_blend_smoothness': r['P.blendSmoothness'],
              'u_gamma_curve_type': int(r['P.gammaType']), 'u_gamma_inv': 1.0 / max(r['P.gamma'], 1.0), 'u_aces_toe': r['P.acesToe']}
        if scale != 1:
            raise ValueError('bands: level 0 at the full grid only')
        bst = VB.passes(_BandApi(g), pu, lv, tuple(bands) or None)
    dc, tc, uc = ({}, [], {}) if bst is None else VB.coarsest(bst, n)
    g.draw(g.prog('v2_ef_coarsest', dc or None), F[n - 1], dict({'u_tex_G': G[n - 1], 'u_tex_W': W[n - 1]}, **dict(tc)),
           dict({'u_width': lv[n - 1][0], 'u_height': lv[n - 1][1]}, **uc))
    for i in range(n - 2, -1, -1):
        cu = {'u_width': lv[i][0], 'u_height': lv[i][1], 'u_clarity': r['P.clarity']}
        on = jbu is not None and jbu[1] > 0
        dd = {}
        if on:
            cu.update(u_jbu_sigma=float(jbu[1]), u_jbu_log=float(jbu[2]))
            dd['JBU'] = int(jbu[0])
        tx = {'u_tex_G_fine': G[i], 'u_tex_G_coarse': G[i + 1], 'u_tex_W_fine': W[i], 'u_tex_fused_coarse': F[i + 1]}
        if bst is not None:
            d2, t2, u2 = VB.collapse(bst, i)
            dd.update(d2); tx.update(dict(t2)); cu.update(u2)
        g.draw(g.prog('v2_ef_collapse', dd or None), F[i], tx, cu)
    if bst is not None:
        if keep is not None:
            keep['fpp'] = g.read(bst['GF'][0], 2)[..., 1]
        VB.release(bst)
    if keep is not None:
        keep['fused'] = g.read(F[0], 1)[..., 0]
        keep['levels'] = lv
    curve = g.tex(r['curve'][None, :], 'f2', linear=True)  # WhyV2Tone: R16F, LINEAR
    d = {}
    if p3:
        d['P3_OUT'] = 1
    if scale == 2:
        d['EF_SCALED'] = 1
    if quant8:
        d['QUANT8'] = 1
    lt3 = None
    if lut is not None:
        d['FILM_LUT'] = 1
        n3 = lut.shape[0]
        lt3 = g.ctx.texture3d((n3, n3, n3), 3, np.ascontiguousarray(lut, np.float32).astype(np.float16).tobytes(), dtype='f2')
        lt3.filter = (g.mgl.LINEAR, g.mgl.LINEAR)
        lt3.repeat_x = lt3.repeat_y = lt3.repeat_z = False
    out = g.target((w, h), 4, 'f4')
    u = {'u_width': w, 'u_height': h, 'u_auto_exposure': r['ae'], 'u_clip_ceiling': r['clip'],
         'u_hdr_shoulder_strength': r['P.shoulderStrength'], 'u_hdr_shoulder_knee': r['P.shoulderKnee'],
         'u_hdr_shoulder_headroom': r['P.shoulderHeadroom'], 'u_aces_toe': r['P.acesToe'], 'u_tonemap_operator': int(r['P.toneOperator']),
         'u_agx_look': int(r['P.agxLook']), 'u_agx_slope': r['slope'], 'u_agx_sp': r['P.agxSp'], 'u_agx_tp': r['P.agxTp'],
         'u_agx_min_ev': r['P.agxMinEv'], 'u_agx_max_ev': r['P.agxMaxEv'], 'u_agx_sat_boost': r['P.agxSat'], 'u_agx_ev': r['P.agxEv'],
         'u_agx_hue_lock': int(r['P.agxHueLock']),
         'u_agx_mat_inset': tuple(r['in%d' % i] for i in range(9)), 'u_agx_mat_outset': tuple(r['out%d' % i] for i in range(9)),
         'u_aces_pre_gain': r['P.acesPreGain'], 'u_aces_a_coeff': r['P.acesA'], 'u_aces_d_coeff': r['acesD'],
         'u_uchi_pre_gain': r['P.uchiPreGain'], 'u_uchi_post_gain': r['P.uchiPostGain'], 'u_uchi_p': r['P.uchiP'], 'u_uchi_a': r['uchiA'],
         'u_uchi_m': r['P.uchiM'], 'u_uchi_l': r['P.uchiL'], 'u_uchi_c': r['P.uchiC'], 'u_uchi_b': r['P.uchiB'],
         'u_macro_contrast': r['macro'], 'u_contrast_boost': r['cb'], 'u_vibrance_strength': r['P.vibStrength'],
         'u_vibrance_saturated': r['P.vibSaturated'], 'u_skin_protect_toggle': r['P.skinProtect'], 'u_vibrance_sky_str': r['P.vibSky'],
         'u_vibrance_green_str': r['P.vibGreen'], 'u_vibrance_hl_str': r['P.vibHl'], 'u_vibrance_mid_str': r['P.vibMid'],
         'u_vibrance_shadow_str': r['P.vibShadow'], 'u_skin_hue_shift': r['P.skinHueShift'], 'u_skin_sat_shift': r['P.skinSatShift'],
         'u_skin_hue_center': r['P.skinHueCenterRad'], 'u_skin_luma_min': r['P.skinLumaMin'],
         'u_gamma_curve_type': int(r['P.gammaType']), 'u_gamma': r['P.gamma'], 'u_gamma_inv': 1.0 / max(r['P.gamma'], 1.0),
         'u_film_toe': r['P.filmToe']}
    if uniforms:
        u.update(uniforms)
    tx = {'u_tex_clean_rgb': ct, 'u_tex_accum_ef': F[0], 'u_tex_hires_luma': lt, 'u_tex_custom_curve': curve}
    if lt3 is not None:
        tx['FilmLut'] = lt3
        u['lutU'] = (float(lut_strength), float(lut.shape[0]))
    g.draw(g.prog('v2_tonemap', d), out, tx, u)
    img = g.read(out, 3)
    for t in G + W + F + [ct, lt, curve, out] + ([lt3] if lt3 is not None else []):
        t.release()
    return img, r


def highlights(g, ct, r):
    """WhyV2Highlights on the host: bracket_denoise (ceiling > 1.05, strength > 0.001), hr_extract / box / hr_apply (amount >
    0.001), the shipped why/v2_* shaders; returns the clean texture to fuse (the input released when replaced)."""
    w, h = ct.size
    if r['clip'] > 1.05 and r['P.bracketDenoise'] > 0.001:
        out = g.target((w, h), 4, linear=True)
        g.draw(g.prog('v2_bracket_dn'), out, {'u_tex_rgb': ct}, {'u_width': w, 'u_height': h, 'u_clip_ceiling': r['clip'],
                                                                'u_strength': r['P.bracketDenoise']})
        ct.release()
        ct = out
    if r['P.hrAmount'] > 0.001:
        hw, hh = max(1, w // 2), max(1, h // 2)
        ex, bl, df = (g.target((hw, hh), 4, linear=True) for _ in range(3))
        g.draw(g.prog('v2_hr_extract'), ex, {'u_tex_rgb': ct}, {'u_width': hw, 'u_height': hh, 'u_threshold': 0.85})
        g.draw(g.prog('v2_box', {'DIR': 0}), bl, {'u_input_tex': ex}, {'u_width': hw, 'u_height': hh, 'u_radius': 24})
        g.draw(g.prog('v2_box', {'DIR': 1}), df, {'u_input_tex': bl}, {'u_width': hw, 'u_height': hh, 'u_radius': 24})
        out = g.target((w, h), 4, linear=True)
        g.draw(g.prog('v2_hr_apply'), out, {'u_tex_rgb': ct, 'u_tex_diffused_hr': df},
               {'u_width': w, 'u_height': h, 'u_threshold': 0.85, 'u_hr_amount': r['P.hrAmount']})
        for t in (ex, bl, df, ct):
            t.release()
        ct = out
    return ct


def burst_clean(f32, meta):
    """A merge of the reference app's burst (camera RGB: its burst converted for the worker, then merged) as the clean RGB: the
    session's lens shading, white balance and colour matrix (clipped sites neutral, as the compare tool's render without the app's
    highlight path). Returns HxWx3 float32 linear sRGB."""
    import json
    from scipy import ndimage as nd
    m = json.load(open(meta))
    H, W = m['h'], m['w']
    a = np.fromfile(f32, np.float32, H * W * 3).reshape(H, W, 3)
    lsc = np.array(m['lsc'], np.float32)
    lh, lw = lsc.shape[:2]
    gm = np.stack([lsc[..., 0], 0.5 * (lsc[..., 1] + lsc[..., 2]), lsc[..., 3]], -1)
    grid = np.meshgrid((np.arange(H) + 0.5) / H * (lh - 1), (np.arange(W) + 0.5) / W * (lw - 1), indexing='ij')
    gain = np.stack([nd.map_coordinates(gm[..., c], grid, order=1, mode='nearest') for c in range(3)], -1).astype(np.float32)
    wb = np.array([m['wb'][0], 0.5 * (m['wb'][1] + m['wb'][2]), m['wb'][3]], np.float32)
    lin = np.maximum(a * gain * wb, 0)
    clip = np.clip((a.max(-1) - 0.9) / 0.08, 0, 1)[..., None]
    lin = lin + clip * (lin.max(-1, keepdims=True) - lin)
    return (lin @ np.array(m['ccm'], np.float32).reshape(3, 3).T).astype(np.float32)


if __name__ == '__main__':
    print(__doc__)
