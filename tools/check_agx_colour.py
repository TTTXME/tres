#!/usr/bin/env python3
"""AgX uses the one skin-protected light-tone vibrance gain, at the slider's actual strength.

Optional --baseline checks legacy token identity and film100 GL equality against a saved shader.
Optional --scene accepts recorded scene-linear WhyLow .npy colours for a fixed-AE colour response model,
not a reproduction of the captured photo's exposure, detail or output tone.
"""
import argparse
import itertools
import json
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_vibrance_hl as cvh
import check_film_sim as cfs
from check_colour_output import set_define, token_hash

SHADER = cvh.SH / 'why/combine.glsl'
P = 32
PATCHES = [('grey', .85, 0., 83.), ('cast', .85, .003, 83.), ('wall', .85, .02, 83.),
           ('skin', .85, .05, 41.2), ('warm', .85, .05, 83.), ('yellow', .85, .085, 80.),
           ('green', .85, .05, 150.), ('blue', .85, .05, 250.), ('mid', .45, .05, 110.)]


class Bench(cvh.Bench):
    def prog(self, name, defines=()):
        if name == 'why/combine':
            defines = tuple(defines) + (('WHY_VIBRANCE', '1'), ('LIGHT_CHROMA', '1'), ('AGX_INSET', '1'), ('WHY_BASE', '1'))
        return super().prog(name, defines)


def image():
    img = np.empty((P, P * len(PATCHES), 3), np.float32)
    for i, (_, L, C, h) in enumerate(PATCHES):
        h = np.radians(h)
        img[:, i * P:(i + 1) * P] = cvh.from_oklab(L, C * np.cos(h), C * np.sin(h))
    return img


def measured(img):
    rgb = np.asarray([img[10:22, i * P + 10:i * P + 22].mean((0, 1)) for i in range(len(PATCHES))])
    lab = cvh.display_lab(rgb)
    return rgb, lab, np.linalg.norm(lab[:, 1:], axis=1)


def uniforms():
    return {'agxAU': (2.45, 1.35, 2., 1.), 'agxBU': (-8.5, 3.5, 0., 4.), 'insetU': .5,
            'macroU': 1., 'detailGainU': 0., 'filmToeU': 0., 'headroomU': 0, 'hlWhiteU': .15,
            'vibU': (0., 0., 0.), 'vibWhyU': (0., 0., 0., 0.),
            'vibSkinU': (1., float(np.radians(41.2)), .16, 0.), 'vibHlU': 0., 'lightChromaU': 1.}


def saturation_controls(src, check, baseline=None):
    import moderngl
    gpu = cfs.Gpu(moderngl)
    colours = np.asarray([(.5, .5, .5), (1., .9, .75), (.62, .45, .36), (1., .53, .20),
                          (.30, .50, .12), (.25, .45, .95), (1.50, .12, .10), (1.14, .16, 1.71)])
    colours /= (colours @ cvh.cws.LUMA)[:, None]
    ev = np.arange(-3., 7.01, .5)
    full = np.repeat(np.repeat(colours[:, None, :] * (.18 * np.exp2(ev))[None, :, None], 4, 0), 4, 1).astype(np.float32)
    strengths = np.arange(0., 2.01, .25)
    stats = []
    try:
        for p3, gamma, head in itertools.product((0, 1), (0, 1, 2), (0, 1)):
            defs = dict(WHY_VIBRANCE=1, LIGHT_CHROMA=1, AGX_INSET=1, WHY_BASE=1, P3_OUT=p3,
                        GAMMA_TYPE=gamma, HL_LANDING=1, SUN_BLOOM=1)
            uni = dict(uniforms(), headroomU=head, hlSoftU=.7, sunBloomU=.4, sunGridU=(1., 1.),
                       gammaInvU=1 / 2.2, macroU=1., filmToeU=.1)
            rgb = np.asarray([gpu.render(full, defines=defs, uni=dict(uni, agxAU=(2.45, 1.35, 2., float(s))), src=src)
                              for s in strengths])
            if gamma == 0:
                linear = np.maximum(rgb, 0.) ** 2.2
            elif gamma == 1:
                linear = cfs.srgb_decode(rgb)
            else:
                linear = np.where(rgb < .081, rgb / 4.5, ((rgb + .099) / 1.099) ** (1 / .45))
            if p3:
                linear = linear @ cfs.P3_TO_SRGB.T
            lab = np.cbrt(np.maximum(linear @ cvh.M1.T, 0.)) @ cvh.M2.T
            chroma = np.linalg.norm(lab[..., 1:], axis=-1)
            spread = float(np.ptp(rgb[0], axis=-1).max())
            drop = float(np.diff(chroma, axis=0).min())
            lightness = float(np.abs(lab[..., 0] - lab[4, ..., 0]).max())
            label = 'P3%d gamma%d landing%d' % (p3, gamma, head)
            check(np.isfinite(rgb).all() and rgb.min() >= 0. and rgb.max() <= 1., label + ' saturation sweep is finite/in gamut')
            check(spread < 2e-4, label + ' saturation0 removes all colour including highlight landing (spread %.3g)' % spread)
            check(drop > -2e-5, label + ' saturation0..2 chroma is monotone within float precision (min delta %.3g)' % drop)
            check(lightness < 2e-5, label + ' saturation keeps OKLab lightness (max delta %.3g)' % lightness)
            if baseline is not None:
                before = gpu.render(full, defines=defs, uni=dict(uni, agxAU=(2.45, 1.35, 2., 1.)), src=baseline)
                check(np.array_equal(rgb[4], before), label + ' saturation1 is the previous render bit for bit')
            stats.append(dict(p3=p3, gamma=gamma, headroom=head, zero_rgb_spread=spread, min_chroma_delta=drop,
                              max_lightness_delta=lightness))
        skin = np.broadcast_to(cvh.from_oklab(.65, .05 * np.cos(np.radians(52)), .05 * np.sin(np.radians(52))), (16, 16, 3)).copy()
        defs = dict(WHY_VIBRANCE=1, LIGHT_CHROMA=1, AGX_INSET=1, WHY_BASE=1, COLOUR_LOOK=1)
        uni = dict(uniforms(), skinU=(0., 0., 0.), hueLockU=2, gamutU=0., gammaInvU=1 / 2.2, macroU=1.)
        reference = gpu.render(skin, defines=defs, uni=uni, src=src)
        for amount in (.5, 1.5):
            free = gpu.render(skin, defines=defs, uni=dict(uni, agxAU=(2.45, 1.35, 2., amount)), src=src)
            protected = gpu.render(skin, defines=defs, uni=dict(uni, agxAU=(2.45, 1.35, 2., amount), skinU=(1., 0., 0.)), src=src)
            if amount < 1.:
                check(np.array_equal(free, protected), 'skin protection does not bypass explicit desaturation')
            else:
                check(np.linalg.norm(protected - reference) < np.linalg.norm(free - reference) * .25,
                      'skin protection still limits saturation above1')
    finally:
        gpu.ctx.release()
    return stats


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--shader', type=Path, default=SHADER)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--scene', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    src = args.shader.read_text(encoding='utf-8').replace('\r\n', '\n')
    baseline = args.baseline.read_text(encoding='utf-8').replace('\r\n', '\n') if args.baseline else None
    failures, checks = [], []
    report = {'shader': str(args.shader), 'patches': PATCHES, 'scenes': {}}

    def check(ok, label):
        checks.append({'pass': bool(ok), 'check': label})
        print(('ok   ' if ok else 'FAIL ') + label)
        if not ok:
            failures.append(label)

    b = Bench()
    base = uniforms()
    default = dict(base, vibU=(0., .25, .05), vibHlU=1., vibWhyU=(0., .25, 0., 0.))
    try:
        runs = [('off', src, 0, base), ('default', src, 0, default)]
        for strength in (0., .25, .5, .75, 1., 1.5):
            for protection in (0., 1.):
                u = dict(base, vibHlU=strength / .25, vibWhyU=(0., strength, 0., 0.),
                         vibSkinU=(protection, float(np.radians(41.2)), .16, 0.))
                runs.append((f'hl-{strength}-{protection}', src, 0, u))
        r = b.render(image(), runs)
        measured_runs = {k: measured(v) for k, v in r.items()}
        cs = {k: v[2] for k, v in measured_runs.items()}
        skin = [p[0] for p in PATCHES].index('skin')
        wall = [p[0] for p in PATCHES].index('wall')
        share = (cs['hl-0.25-1.0'][skin] - cs['off'][skin]) / max(cs['hl-0.25-0.0'][skin] - cs['off'][skin], 1e-6)
        check(.20 < share < .42, 'AgX default HL skin gain share %.3f (expected about .30)' % share)
        wall_gain = cs['default'][wall] / cs['off'][wall]
        skin_gain = cs['default'][skin] / cs['off'][skin]
        check(wall_gain < 1.5 and skin_gain < 1.35,
              'default HL stays moderate: wall x%.3f, protected skin x%.3f' % (wall_gain, skin_gain))
        check(max(np.ptp(v[0][0]) for v in measured_runs.values()) < 2e-4, 'neutral grey stays neutral at every HL strength')
        check(max(v[2][1] for v in measured_runs.values()) < cs['off'][1] * 1.05 + 1e-3, 'near-white cast is not boosted')
        check(np.array_equal(r['off'], r['hl-0.0-1.0']), 'HL strength0 is the off render bit for bit')
        warm = [p[0] for p in PATCHES].index('warm')
        ramp = [cs[f'hl-{s}-1.0'][warm] for s in (0., .25, .5, .75, 1., 1.5)]
        check(min(np.diff(ramp)) >= -.05 and ramp[-1] > ramp[0] + 2., 'explicit HL slider remains effective and monotone on a warm light tone')
        report['C'] = {k: v.tolist() for k, v in cs.items()}
        report['skin_gain_share'] = share

        # The correction is hue-limited; an orange-yellow must remain finite, continuous and below its display gamut.
        ev = np.arange(-1., 4.001, .025)
        colours = np.asarray((1., .53, .20))
        colours /= colours @ cvh.cws.LUMA
        full = np.broadcast_to((colours[None, :] * (.18 * np.exp2(ev[:, None])))[None, :, :], (24, len(ev), 3)).copy().astype(np.float32)
        yellow = b.render(full, [('yellow', src, 0, default)])['yellow'][8:16].mean(0)
        lab = cvh.display_lab(yellow)
        step = float(np.linalg.norm(np.diff(lab, axis=0), axis=1).max())
        check(np.isfinite(yellow).all() and yellow.min() >= -1e-5 and yellow.max() <= 1.00001,
              'default yellow exposure ramp stays finite and in gamut')
        check(step < 1.5, 'default yellow .025EV ramp is continuous (max dE76 %.3f)' % step)
        report['yellow_max_step_dE76'] = step

        for path in args.scene:
            full = np.load(path, mmap_mode='r')
            scene = np.asarray(full[4::8, 4::8, :3], np.float32)
            u0 = dict(base, lightChromaU=0.)
            out = b.render(scene, [('plain', src, 0, u0), ('default', src, 0, default)])
            labs = {k: cvh.display_lab(v) for k, v in out.items()}
            chroma = {k: np.linalg.norm(v[..., 1:], axis=-1) for k, v in labs.items()}
            mask = (labs['plain'][..., 0] > 40) & (labs['plain'][..., 0] < 90) & (chroma['plain'] > 3)
            ratios = np.percentile(chroma['default'][mask] / chroma['plain'][mask], (10, 50, 90))
            check(mask.sum() > 100 and ratios[2] < 1.4, '%s scene-colour model default C*/plain p90 %.3f, median %.3f' % (path.name, ratios[2], ratios[1]))
            report['scenes'][str(path)] = {'source_shape': list(full.shape), 'sample_step': 8,
                                           'model': 'Recorded scene colours, new fixed-AE1 tone response, not the original photo replay.',
                                           'pixels': int(mask.sum()), 'C_ratio_p10_median_p90': ratios.tolist()}
    finally:
        b.gl.ctx.release()

    report['saturation_controls'] = saturation_controls(src, check, baseline)

    if baseline is not None:
        bad, count = [], 0
        families = ('TONE_OP', 'SKY', 'LIGHT_CHROMA', 'COLOUR_LOOK', 'P3_OUT', 'FILM_SIM')
        for values in itertools.product((0, 1), repeat=len(families)):
            defines = dict(zip(families, values), WHY_VIBRANCE=0, WHY_BASE=1, AGX_INSET=1)
            for route in ({}, {'POINT_FUSED': 1}, {'POINT_FUSED': 1, 'RAW_SOURCE': 1}):
                old, new = baseline, src
                for k, v in dict(defines, **route).items():
                    old, new = set_define(old, k, str(v)), set_define(new, k, str(v))
                count += 1
                if token_hash(old) != token_hash(new):
                    bad.append(dict(defines, **route))
        check(not bad, 'WHY_VIBRANCE0 legacy token identity %d/%d define sets' % (count-len(bad), count))
        report['legacy_identity'] = {'count': count, 'mismatches': bad}

        import moderngl
        gpu = cfs.Gpu(moderngl)
        try:
            full = image()
            lut = cfs.identity_u(17)
            worst = 0.
            for tone, p3, sat in itertools.product((0, 1), (0, 1), (0., 1., 2.)):
                defs = dict(FILM_SIM=1, WHY_VIBRANCE=1, LIGHT_CHROMA=1, COLOUR_LOOK=1, AGX_INSET=1,
                            WHY_BASE=1, TONE_OP=tone, P3_OUT=p3)
                uni = dict(default, filmExpU=1., strength=1., toneOpU=1, acesU=(1., 2.51, .59, .05), agxAU=(2.45, 1.35, 2., sat))
                old = gpu.render(full, defines=defs, uni=uni, lut=lut, src=baseline)
                new = gpu.render(full, defines=defs, uni=uni, lut=lut, src=src)
                worst = max(worst, float(np.abs(new-old).max()))
            check(worst == 0., 'film100 unchanged from baseline in AgX/ACES and sRGB/P3 (max |d| %.3g)' % worst)
            report['film100_max_difference'] = worst
        finally:
            gpu.ctx.release()
    report['checks'] = checks
    if args.output:
        args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('check_agx_colour: %d checks, %d failed' % (len(checks), len(failures)))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
