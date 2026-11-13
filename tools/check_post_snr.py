#!/usr/bin/env python3
"""P648 «Подстройка под свет (SNR)»: the Resolve's night smoothing of the flat areas (scamdn/why_resolve flatNightU / flatBoostU /
flatGrainU, flatNoiseU the merged image's measured luma noise; PostSnr; P666 flatShadowU the shadows' coarse texture test).
- unset / 0: bit for bit the pass of before (the switch off and every daylight shot), whatever the other flat uniforms hold;
- a frame of one merged frame per pixel (a rejected, moving subject; a «Спорт» shot whose donors all fell out): bit for bit too;
- a flat noisy patch smoother, monotonically in the share and continuously from 0; its mean kept;
- a 1 - 2 px texture above the noise kept (its band's transfer >= 0.97), an edge without a halo beside it, a colour ramp's slope and a
  hue boundary's step kept (owner, 2026-11-13: detail, colour transitions; «не выглядело пластиковым»: the grain given back raises
  the flat fine noise), every intermediate finite;
- P666 (owner, 2026-11-13: «он сильно мылит тени»): a shadow's texture under the pixel noise keeps its band (P648c's test alone
  smoothed it), a flat shadow is still smoothed, everything above the shadows bit for bit P648c's, flatShadowU without flatNightU
  bit for bit the pass of before."""
import numpy as np

import check_resolve_denoise as R

NOISE = (.562 * .004, .25 * .004, .25 * .004, .00002)


def resolve(g, image, codes, total, extra):
    h, w = image.shape[:2]
    inp = g.tex(image, 'f4'); eff = g.tex(codes, 'u1')
    p0, p1, out = g.target(w, h, 'f4'), g.target(w, h, 'f4'), g.target(w, h, 'f4')
    values = dict(useEffU=1, effScaleU=total / 64, framesU=float(total), totalU=float(total), fullSizeU=(w, h), noiseModelU=NOISE,
                  factorU=.4, outlierU=.125, rejectRevertU=.5, globalRevertU=.85, grainU=0., vignetteBoostU=0., vignetteScaleU=1.,
                  flatNightU=0., flatBoostU=0., flatGrainU=0., flatNoiseU=(0., 0.), flatShadowU=(0., 0., 0.))
    # a program keeps a uniform between draws: 0 stands for unset (the very first draw below is unset)
    values.update(extra)
    source = inp
    for pas, k in enumerate((1, 2, 4)):
        target = (p0, p1, out)[pas]
        g.draw('scamdn/why_resolve', target, dict(InputBuffer=source, EffMap=eff),
               dict(values, passU=pas, stepU=k, inputOriginU=0, outputOriginU=0))
        source = target
    result = g.read(out)[..., :3]
    for t in (inp, eff, p0, p1, out): t.release()
    return result


def main():
    g = R.Gpu(); rng = np.random.default_rng(648); checks = 0
    h = w = 96; total = 12
    image = np.full((h, w, 4), .05, np.float32); image[..., 3] = 1
    image[..., :3] += rng.normal(0, .006, (h, w, 3)).astype(np.float32)
    image[:, 48:, :3] += .1                                          # an edge at x 48
    yy, xx = np.mgrid[0:h, 0:w]
    image[:40, :40, :3] += (.03 * np.sin(xx[:40, :40] * 1.3) * np.sin(yy[:40, :40] * 1.1))[..., None]   # a 1 - 2 px texture
    merged = np.full((h, w), 64, np.uint8)                           # every pixel the shot's median (12 frames)
    single = np.full((h, w), 64 // total, np.uint8)                  # one frame everywhere (a one-frame shot)
    y = lambda rgb: rgb @ np.array([.2126390, .7151688, .0721923])
    # the measured luma noise: the input's white noise (.006 a channel) as twice its variance - a merged image's noise is correlated
    # (demosaic, merge), its 3 x 3 variance reads ~ 0.6 of the measured one, where the white noise here reads 1
    noise = (0., 2. * .006 ** 2 * (.2126390 ** 2 + .7151688 ** 2 + .0721923 ** 2))
    full = dict(flatNightU=1., flatBoostU=3., flatGrainU=0., flatNoiseU=noise)
    off = resolve(g, image, merged, total, {})
    for z in (dict(flatNightU=0.), dict(flatNightU=0., flatBoostU=3., flatGrainU=1., flatNoiseU=noise), dict(flatNightU=-1., flatBoostU=3.),
              dict(flatNightU=0., flatShadowU=SHADOW, flatNoiseU=noise)):
        assert np.array_equal(off, resolve(g, image, merged, total, z)), ('flat smoothing at 0 changed the resolve', z)
        checks += 1
    assert np.array_equal(resolve(g, image, single, total, {}), resolve(g, image, single, total, full)), \
        'one-frame pixels took the flat smoothing'
    checks += 1
    flat_sd = lambda o: y(o)[56:88, 8:40].std()
    last = flat_sd(off); means = [off[56:88, 8:40].mean()]
    for share in (.001, .25, .5, 1.):
        on = resolve(g, image, merged, total, dict(full, flatNightU=share))
        assert np.isfinite(on).all()
        sd = flat_sd(on)
        assert sd <= last + 1e-7, ('the flat noise rose with the share', share, sd, last)
        if share == .001: assert abs(sd - last) < 2e-3 * last, ('a step at share 0', sd, last)
        last = sd; means.append(on[56:88, 8:40].mean()); checks += 1
    assert last < .8 * flat_sd(off), ('the flat patch did not smooth', last, flat_sd(off))
    assert max(means) - min(means) < 3e-4, 'the flat mean moved'
    on = resolve(g, image, merged, total, full)
    band = lambda o: (y(o) - y(o)[4:36, 4:36].mean())[4:36, 4:36]
    truth = band(image[..., :3].astype(np.float64))
    tr = lambda o: float((band(o) * truth).sum() / (truth * truth).sum())
    assert tr(on) >= .97 * tr(off), ('the texture lost its 1 - 2 px band', tr(on), tr(off))
    assert np.abs(y(on)[56:88, 40:56].mean(0) - y(off)[56:88, 40:56].mean(0)).max() < .002, 'a halo beside the edge'
    grain = resolve(g, image, merged, total, dict(full, flatGrainU=1.5))
    assert flat_sd(grain) > flat_sd(on) * 1.02, 'the flat grain was not given back'
    checks += 3
    # colour transitions: a smooth chroma ramp and a hue boundary (orange | violet at one luma) under noise
    ramp = np.zeros((h, w, 4), np.float32); ramp[..., 3] = 1
    t = np.linspace(0, 1, w, dtype=np.float32)[None, :]
    ramp[..., 0] = .06 + .04 * t; ramp[..., 1] = .05; ramp[..., 2] = .06 - .04 * t           # red -> blue at one level
    ramp[48:, :48, :3] = (.09, .05, .03); ramp[48:, 48:, :3] = (.05, .045, .085)            # a hue boundary in the lower half
    ramp[..., :3] += rng.normal(0, .003, (h, w, 3)).astype(np.float32)
    uv = lambda rgb: np.stack((y(rgb) - rgb[..., 0], y(rgb) - rgb[..., 2]), -1)
    res = []
    for out in (resolve(g, ramp, merged, total, {}), resolve(g, ramp, merged, total, full)):
        top = uv(out)[8:40, 8:-8].mean(0)
        res.append((np.polyfit(np.arange(top.shape[0]), top[:, 0], 1)[0],
                    np.abs(uv(out)[56:88, 52:60].mean((0, 1)) - uv(out)[56:88, 36:44].mean((0, 1))).sum()))
    assert abs(res[1][0] / res[0][0] - 1) < .02, ('the colour ramp lost its slope', res)
    assert res[1][1] >= .98 * res[0][1], ('the hue boundary softened', res)
    checks += 2
    checks += shadows(g, np.random.default_rng(666), merged, total, noise, full)
    print('P648 flat night smoothing PASS:', checks, 'cases (0 / one frame bit for bit, monotone and continuous, mean, 1 - 2 px texture, '
          'no halo, grain given back, colour ramp and hue boundary kept; P666 shadow texture kept, flat shadow smoothed, the rest P648c)')


SHADOW = (2., 5., .2)   # PostSnr.shadowBand of a night shot of SNR 10, PostSnr.SHADOW_COARSE


def shadows(g, rng, merged, total, noise, full):
    """P666: a dark half (luma ~ .008: local SNR ~ 1.3 against the measured noise) holding a 12 x 14 px texture of .005 - under the
    pixel noise (its luma sigma ~ .0045) - beside a flat dark patch, and a lit half (.05) as the main check's."""
    h, w = merged.shape
    yy, xx = np.mgrid[0:h, 0:w]
    img = np.full((h, w, 4), .05, np.float32); img[..., 3] = 1
    img[:, :48, :3] = .008
    tex = .005 * np.sin(xx * 2 * np.pi / 12) * np.sin(yy * 2 * np.pi / 14)
    img[:40, :40, :3] += tex[:40, :40, None].astype(np.float32)
    img[..., :3] += rng.normal(0, .006, (h, w, 3)).astype(np.float32)
    y = lambda rgb: rgb.astype(np.float64) @ np.array([.2126390, .7151688, .0721923])
    base = resolve(g, img, merged, total, {})
    old = resolve(g, img, merged, total, full)
    new = resolve(g, img, merged, total, dict(full, flatShadowU=SHADOW))
    assert np.isfinite(new).all()
    t = tex[6:34, 6:34]
    tr = lambda o: float(((y(o)[6:34, 6:34] - y(o)[6:34, 6:34].mean()) * t).sum() / (t * t).sum())
    assert tr(old) < .95 * tr(base), ('P648c alone kept the shadow texture - the case does not test P666', tr(old), tr(base))
    assert tr(new) >= .97 * tr(base), ('the shadow texture was smoothed', tr(new), tr(old), tr(base))
    sd = lambda o: y(o)[56:88, 8:40].std()
    assert sd(new) < .93 * sd(base) and sd(new) <= 1.02 * sd(old), ('the flat shadow was not smoothed', sd(new), sd(old), sd(base))
    assert np.array_equal(new[:, 64:], old[:, 64:]), 'the lit half changed from P648c'
    print('  P666 shadows: texture kept %.3f (P648c %.3f) of the plain resolve, flat shadow sd %.5f (P648c %.5f, plain %.5f)'
          % (tr(new) / tr(base), tr(old) / tr(base), sd(new), sd(old), sd(base)))
    return 4


if __name__ == '__main__':
    main()
