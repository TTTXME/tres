#!/usr/bin/env python3
"""P623: the sun's glow on the fast route (why/combine HL_MEASURED), on the production why/combine.glsl.

Owner, 2026-11-11 (Ark burst 140250, «Быстрый»): «на сцене с солнцем у нас есть обводка на солнце, а у V2 нет». The fast merge's
valid short frames measure the sun and its glow up to their clip (4 - 20 x white), but no Bento ran: WhyAe has no real headroom
(hr 0) and the kernel's two shoulders (everything above 1 into <= 1.35, then the roll-off from 0.5) put 1.5 .. 8 x white on one
grey - a flat grey glow with the landing's white disc in it. With HL_MEASURED 1 the light above white goes over to HEADROOM_WIDE's
shoulder (hlMeasU 1 .. 2 x white, WhyTone.headroomTone, shoulder strength 0.6).

A sun's radial profile along a row (a clipped core 6 x white, 20 px, then an exponential glow over a sky at 0.35 x white; the
colour source fed so that the fusion gain is 1, ae 1, clip 4, headroomU 1), the AgX tone and the ACES operator. Checks:
- the glow falls monotonically from the core outwards (no ring: luma never rises outwards by more than 0.25 code);
- no flat grey glow: from 4 x to 1.2 x white the luma falls >= 10 codes, and no 20 px run under white (the white core
  aside) falls < 1 code while the scene falls by a quarter (HL_MEASURED 0, the kernel's shoulders, fails both: the test of the check itself);
- at and below white the picture is the one before, bit for bit (HL_MEASURED 0 vs 1); round 2 (hlMeasShoulderU.w 0) leaves a
  saturated light (a lime sign's colour, the same profile: MEAS_SAT) as before up to 2 x white (MEAS_HIGH from 3 x its luminance);
  round 3 (w 1, the default: the brightest channel decides and is shouldered, the gate open from MEAS_HIGH_MAX 2 .. 4 x of it) the
  same up to 2 x white and towards white far above; both monotonic;
- HL_MEASURED 1 with strength 0 renders exactly as the default program (the shader without it).
- round 5: with the measured level blending the shoulder in too (hlMeasSceneU 1) the glow stays monotonic, nothing at or below
  white changes, a lime light up to 2 x white keeps the kernel's colour;
- round 4: with the measured detail (hlMeasDetailU 0.5) a +-5 % texture on the glow 1.5 .. 4 x white keeps >= 0.6 code more
  between neighbours than without it, light under white unchanged;
- round 6 (140421's amber lens beside its white streak, hlMeasGlareU 0.7): an amber light 2.5 x white meeting a pale streak 10 x
  white - the largest R - B step between neighbours falls by >= 30 %, the amber 6 px and more from the streak keeps its colour bit
  for bit, a saturated red light 10 x white beside the amber changes nothing, light under white changes nothing, strength 0 = round 5.
Usage: check_sun_glow.py [--report]
"""
from pathlib import Path
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
JAVA = ROOT / 'app/src/main/java/com/scamera/app/processing/opengl/postpipeline'
LUMA = np.array([0.2126, 0.7152, 0.0722])
W = 256
CORE, R0, L, SKY = 6.0, 20, 45.0, 0.35
SUN = np.array([1.0, 0.96, 0.88])
CLIP = 4.0


def inverse_aces(y, toe=0.05):
    b = max(toe * 0.75, 0.005)
    e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
    y = min(max(y, 0.0), 2.51 / 2.43 - 0.01)
    A = 2.43 * y - 2.51; B = 0.59 * y - b; C = e * y
    disc = B * B - 4 * A * C
    if disc < 0: return 0.0
    x = (-B - np.sqrt(disc)) / (2 * A - 1e-6)
    return max(x / 1.5, 0.0)


def fused_for(target):
    lo, hi = 0.0, 2.51 / 2.43 - 0.0101
    for _ in range(60):
        mid = (lo + hi) / 2
        if inverse_aces(mid) < target: lo = mid
        else: hi = mid
    return lo ** (1 / 2.2)


LIME = np.array([0.75, 1.0, 0.1])   # a saturated light (a lime sign's frame, an LED): keeps its colour


def scene(colour=SUN):
    r = np.arange(W, dtype=np.float64)
    level = np.where(r < R0, CORE, np.minimum(CORE, SKY + CORE * np.exp(-(r - R0) / L)))
    return level, level[:, None] * colour / colour.max()


def source(measured):
    s = (SH / 'why/combine.glsl').read_text(encoding='utf-8')
    if measured:
        assert '#define HL_MEASURED 0\n' in s
        s = s.replace('#define HL_MEASURED 0\n', '#define HL_MEASURED 1\n')
    for imp in ['interpolation', 'coords']:
        p = SH / ('utils/import_' + imp + '.glsl')
        if p.exists(): s = s.replace('#import ' + imp, p.read_text(encoding='utf-8'))
    return s


def render(measured, strength=1.0, op=0, colour=SUN, rmax=1.0, detail=0.0, texture=0.0, sceneU=0.0, glare=0.0, row=None):
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    _, rgb = scene(colour)
    if row is not None:
        rgb = row
    orig = np.zeros((1, W, 4), np.float32)
    fused = np.zeros_like(orig)
    orig[0, :, :3] = rgb
    for i, c in enumerate(rgb):
        fused[0, i, 0] = fused_for(max(max(float(c @ LUMA), c.max() * 0.5), 1e-4))

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST); return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    full = orig.copy()
    full[0, :, :3] *= (1.0 + texture * np.where(np.arange(W) % 2 == 0, 1.0, -1.0))[:, None]
    t_orig, t_fused, t_full = tex(orig), tex(fused), tex(full)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    src = source(measured)
    if op:
        src = src.replace('#define TONE_OP 0\n', '#define TONE_OP 1\n')
    prog = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + src)
    out = ctx.texture((W, 1), 4, dtype='f4'); fb = ctx.framebuffer([out]); fb.use(); ctx.viewport = (0, 0, W, 1)
    units = {'InputBuffer': t_full, 'GainMap': ones, 'WhyLow': t_orig, 'WhyColour': t_orig, 'WhyFused': t_fused,
             'WhyDetailRef': t_orig, 'WhyLumaS': t_orig}
    for i, (k, t) in enumerate(units.items()):
        if k in prog: t.use(i); prog[k].value = i
    eye = tuple(np.eye(3).flatten())
    vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
            'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': CLIP, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1,
            'vibU': (0.0, 0.25, 0.05), 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.0, 'ditherU': 0,
            'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 1, 'hlWhiteU': 0.15,
            'toneOpU': op, 'acesU': (1.0, 2.61, 0.59, 0.05)}
    if measured:
        vals.update({'hlMeasU': (1.0, 2.0, strength, 0.0), 'hlMeasShoulderU': (0.6, 0.35, 0.4, rmax), 'hlMeasCeilU': 0.0,
                     'hlMeasDetailU': detail, 'hlMeasSceneU': sceneU, 'hlMeasGlareU': glare})
    if row is not None:
        vals['fullInputU'] = 1
    for k, v in vals.items():
        if k in prog: prog[k].value = v
    ctx.vertex_array(prog, []).render(vertices=3)
    img = np.frombuffer(out.read(), np.float32).reshape(W, 4)[:, :3] * 255.0
    ctx.release()
    return img


def glow_faults(y, level):
    """(luma rises outwards, the glow's range from 4 to 1.2 x white, the 20 px runs there falling < 1 code while the scene
    falls by a quarter)"""
    rise = float(np.max(np.diff(y)))
    zone = np.where((level <= 4.0) & (level >= 1.2))[0]
    span = float(y[zone[0]] - y[zone[-1]])
    flat = 0
    for i in zone:
        j = i + 20
        if j <= zone[-1] and y[i] < 253.0 and level[j] < level[i] * 0.75 and y[i] - y[j] < 1.0:
            flat += 1
    return rise, span, flat


def check(report=False):
    failures = []
    level, _ = scene()
    java = (JAVA / 'WhyCombine.java').read_text(encoding='utf-8')
    tone = (JAVA / 'WhyTone.java').read_text(encoding='utf-8')
    if 'shaderProg.setDefine("HL_MEASURED", 1);' not in java or 'fastShortFrames(report) <= 0' not in java:
        failures.append('WhyCombine does not set HL_MEASURED from the fast merge\'s short frames')
    if 'value("hl_measured", 1f)' not in tone:
        failures.append('no scam_dev hybrid_why_hl_measured (default 1) in WhyTone')
    for op, name in ((0, 'AgX'), (1, 'ACES')):
        before = render(False, op=op)
        after = render(True, op=op)
        off = render(True, strength=0.0, op=op)
        if not np.array_equal(off, before):
            failures.append(f'{name}: HL_MEASURED 1 with strength 0 differs from the default program: max {np.abs(off - before).max():.3f}')
        below = level <= 1.0
        if not np.array_equal(after[below], before[below]):
            failures.append(f'{name}: at or below white the picture changed: max {np.abs(after[below] - before[below]).max():.3f}')
        # round 2 (scam_dev hybrid_why_hl_measured_max 0): a saturated light under 3 x white stays on the kernel's ceiling bit for bit
        lime = render(False, op=op, colour=LIME), render(True, op=op, colour=LIME, rmax=0.0)
        low = level <= 2.0      # the lime light's brightest channel under MEAS_HIGH_MAX (2 x) and its luminance under MEAS_HIGH (3 x)
        if not np.array_equal(lime[0][low], lime[1][low]):
            failures.append(f'{name}: round 2, a saturated light up to 2 x white changed: max {np.abs(lime[0][low] - lime[1][low]).max():.3f}')
        # round 3 (the default): the brightest channel decides and is shouldered, the saturated light's gate opening on it from
        # MEAS_HIGH_MAX (2 .. 4 x) - a saturated light up to 2 x white (its brightest channel) keeps the kernel's colour bit for bit; far
        # brighter it takes the tone's path towards white (a lamp's lens 5 - 10 x white, as Ark and V2); monotonic
        lime3 = render(True, op=op, colour=LIME)
        sat = lambda p: 1.0 - p.min(-1) / np.maximum(p.max(-1), 1e-6)
        if not np.array_equal(lime[0][low], lime3[low]):
            failures.append(f'{name}: round 3, a saturated light up to 2 x white changed: max {np.abs(lime[0][low] - lime3[low]).max():.3f}')
        for tag, img in (('round 2', lime[1]), ('round 3', lime3)):
            yl = img @ LUMA
            if np.max(np.diff(yl)) > 0.25:
                failures.append(f'{name}: {tag}, the saturated light\'s profile rises outwards by {np.max(np.diff(yl)):.2f} codes')
        if report:
            print(f'{name} lime saturation kernel / round 3 at 1.5, 3, 6 x white: ' + ' '.join(
                f'{sat(lime[0])[i]:.2f}/{sat(lime3)[i]:.2f}' for i in (int(np.argmin(np.abs(level - v))) for v in (1.5, 3.0, 6.0))))
        if not np.array_equal(render(True, op=op, rmax=0.0)[level <= 1.0], before[level <= 1.0]):
            failures.append(f'{name}: round 2 changed the picture at or below white')
        yb, ya = before @ LUMA, after @ LUMA
        rb, sb, fb_ = glow_faults(yb, level)
        ra, sa, fa = glow_faults(ya, level)
        if ra > 0.25: failures.append(f'{name}: the glow rises outwards by {ra:.2f} codes (a ring)')
        if sa < 10.0: failures.append(f'{name}: from 4 to 1.2 x white the glow falls only {sa:.1f} codes (a flat grey glow)')
        if fa > 0: failures.append(f'{name}: {fa} flat 20 px runs in the glow')
        if sb >= 10.0 and fb_ == 0: failures.append(f'{name}: the check does not see the kernel shoulders\' flat glow (span {sb:.1f})')
        if report:
            pick = [0, 20, 30, 40, 50, 60, 70, 80, 90, 100, 120, 140, 160, 200]
            print(f'{name} scene  ' + ' '.join(f'{level[i]:5.2f}' for i in pick))
            print(f'{name} before ' + ' '.join(f'{yb[i]:5.1f}' for i in pick) + f'   span {sb:.1f} flat runs {fb_}')
            print(f'{name} after  ' + ' '.join(f'{ya[i]:5.1f}' for i in pick) + f'   span {sa:.1f} flat runs {fa} rise {ra:.2f}')
    # round 4: the measured light's own detail above white (hlMeasDetailU, the share 0.5 the app sets): a +-5 % texture on the glow
    # 1.5 .. 4 x white survives (the same-size grid: the detail delta is off, ae 1), at and below white nothing changes
    for op, name in ((0, 'AgX'), (1, 'ACES')):
        t0 = render(True, op=op, texture=0.05) @ LUMA
        t1 = render(True, op=op, texture=0.05, detail=0.5) @ LUMA
        zone = (level >= 1.5) & (level <= 4.0)
        amp0 = float(np.abs(np.diff(t0))[zone[1:]].mean())
        amp1 = float(np.abs(np.diff(t1))[zone[1:]].mean())
        if amp1 < amp0 + 0.6:
            failures.append(f'{name}: the measured light\'s texture 1.5 .. 4 x white {amp0:.2f} -> {amp1:.2f} codes (< +0.6)')
        if not np.array_equal(t0[level <= 0.8], t1[level <= 0.8]):
            failures.append(f'{name}: the measured detail changed light under white')
        if report:
            print(f'{name} measured detail: a +-5 % texture 1.5 .. 4 x white {amp0:.2f} -> {amp1:.2f} codes between neighbours')
    # round 5: the shoulder also blends in by the light's own measured level (hlMeasSceneU 1, the app's default): the glow still falls
    # monotonically, nothing at or below white changes, and a lime light up to 2 x white keeps the kernel's colour
    for op, name in ((0, 'AgX'), (1, 'ACES')):
        r5 = render(True, op=op, sceneU=1.0)
        if np.max(np.diff(r5 @ LUMA)) > 0.25:
            failures.append(f'{name}: round 5, the glow rises outwards')
        if not np.array_equal(r5[level <= 1.0], render(False, op=op)[level <= 1.0]):
            failures.append(f'{name}: round 5 changed the picture at or below white')
        l5, l0 = render(True, op=op, colour=LIME, sceneU=1.0), render(False, op=op, colour=LIME)
        if not np.array_equal(l5[level <= 2.0], l0[level <= 2.0]):
            failures.append(f'{name}: round 5 changed a saturated light up to 2 x white')
    # round 6: an amber light 2.5 x white (R - B ~0.45 of it) meets a pale streak 10 x white at x 128 (a 2 px ramp, as the merge's
    # edge on 140421's lens); a dim amber under white at x < 40. Beside the streak the amber fades towards white over a few px
    amber = np.array([1.0, 0.68, 0.55])
    x = np.arange(W)
    lv = np.where(x < 40, 0.6, np.where(x < 127, 2.5, np.where(x < 128, 6.0, 10.0)))
    row = lv[:, None] * np.where((x >= 128)[:, None], np.array([1.0, 0.98, 0.95]), amber)
    red = np.where((x >= 127)[:, None], 10.0 * np.array([1.0, 0.08, 0.05]), row)
    for op, name in ((0, 'AgX'), (1, 'ACES')):
        g0, g1 = render(True, op=op, sceneU=1.0, row=row), render(True, op=op, sceneU=1.0, row=row, glare=0.7)
        rb0, rb1 = g0[:, 0] - g0[:, 2], g1[:, 0] - g1[:, 2]
        s0, s1 = float(np.abs(np.diff(rb0[100:136])).max()), float(np.abs(np.diff(rb1[100:136])).max())
        if s1 > 0.7 * s0:
            failures.append(f'{name}: round 6, the amber -> streak R - B step {s0:.1f} -> {s1:.1f} codes (> 70 %)')
        far = x < 122
        if not np.array_equal(g0[far], g1[far]):
            failures.append(f'{name}: round 6 changed the amber 6 px and more from the streak')
        if not np.array_equal(render(True, op=op, sceneU=1.0, row=row, glare=0.0), g0):
            failures.append(f'{name}: round 6 with strength 0 differs from round 5')
        r0, r1 = render(True, op=op, sceneU=1.0, row=red), render(True, op=op, sceneU=1.0, row=red, glare=0.7)
        if not np.array_equal(r0, r1):
            failures.append(f'{name}: round 6 changed a saturated red light beside the amber: max {np.abs(r0 - r1).max():.3f}')
        if report:
            print(f'{name} round 6 R - B by x 118 .. 128: ' + ' '.join(f'{rb0[i]:.0f}/{rb1[i]:.0f}' for i in range(118, 129))
                  + f'   largest step {s0:.1f} -> {s1:.1f}')
    if failures:
        print('sun glow FAIL:\n  ' + '\n  '.join(failures))
        return 1
    print('sun glow PASS: the measured glow falls smoothly from the core (no ring, no flat grey), at and below white unchanged, '
          'strength 0 = the shader before')
    return 0


if __name__ == '__main__':
    sys.exit(check('--report' in sys.argv))
