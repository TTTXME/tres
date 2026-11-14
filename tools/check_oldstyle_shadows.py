#!/usr/bin/env python3
"""P716 (owner, 2026-11-14: «В мастер режиме у нас постеризация в тенях, исправь»): the «ScamMaster» (Oldstyle) tone's shadows on
the host, through the app's path into the owner's tone-port module (app/src/main/cpp/oldstyle-tone-core.h, built as the app builds
it: -ffp-contract=off, the module's sources), on smooth dark frames - a noise-free ramp and blob of a deck in the shade, and the
same with a little noise - with the int16 frame made as oldstyle/linear makes it (floor(v x scale x 4095 x frame gain + 0.5),
P716's dither: + one interleaved-gradient-noise value - 0.5 a pixel).
Before / after, per channel, in codes of the RGB8 picture:
- V1 HDR (the 2.7 plan, gains 0.92 / 3.4 and 3 / 11): the module's bright exposure is the 8-bit retone of the short one (one
  bright level per short code: steps of ~gainLong / gainShort codes in the curve's toe) and the frame was rounded before the
  gains; after: the frame drawn at its gain (OldstyleTone.preGain) and the bright exposure from the linear frame
  (linear_bright_hook) - the ramp's largest step, the mid-frequency contour energy of the blob (9 x 9 means about their 33 x 33
  means), the fine noise; the look elsewhere kept (16 x 16 block means of a full-range frame); one thread == four threads;
- V2 HDR (3.1) with the light within white at a sun of 8 x white (input scale 1 / 8, gains 8 / 32): the rounding dithered - the
  contours and colour steps go, the block means stay;
The viewfinder's post array (15 entries: frame gain 1, no linear bright exposure) keeps the path before P716: that path is
tools/check_tone_port.py's (the JNI == the module, byte for byte).
Usage: check_oldstyle_shadows.py
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

ROOT = Path(__file__).resolve().parent.parent
CPP = ROOT / 'app/src/main/cpp'
TP = CPP / 'third_party/tone_port'
FAIL = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


HARNESS = r'''
#include "oldstyle-tone-core.h"
#include <cstdio>
#include <cstdlib>
// harness <planes.i16> <w> <h> <version> <hdr> <gS> <gL> <frame gain> <linear bright> <dehaze> <lowfreq> <threads> <out.rgb>
int main(int argc, char** argv) {
    if (argc != 14) return 2;
    const size_t w = atoi(argv[2]), h = atoi(argv[3]);
    std::vector<int16_t> src(w * h * 3);
    FILE* f = fopen(argv[1], "rb");
    if (!f || fread(src.data(), 2, src.size(), f) != src.size()) return 3;
    fclose(f);
    oldstyle::Post p;
    p.frame_gain = float(atof(argv[8]));
    p.linear_bright = atoi(argv[9]) != 0;
    p.dehaze = atoi(argv[10]) != 0;  // the app's defaults (OldstyleAe.Params)
    p.max_dark = 24; p.percentile = 0.2f; p.corner = 0.2f;
    p.low_frequency = atoi(argv[11]) != 0;
    p.lf_threshold = 16;
    tone::set_threads(atoi(argv[12]));
    const float ccm[9] = {1.62f, -0.48f, -0.14f, -0.21f, 1.41f, -0.20f, 0.03f, -0.52f, 1.49f};
    std::vector<uint8_t> out(w * h * 3);
    float info[8];
    oldstyle::render(atoi(argv[4]), src.data(), w, h, atoi(argv[4]) == 1 ? ccm : nullptr, float(atof(argv[6])), float(atof(argv[7])),
                     atoi(argv[5]) != 0, false, 0.f, p, nullptr, 0, 0, info, out.data());
    f = fopen(argv[13], "wb");
    fwrite(out.data(), 1, out.size(), f);
    fclose(f);
    return 0;
}
'''

W, H = 1024, 512


def ign(w, h):
    """oldstyle/linear's dither: fract(52.9829189 * fract(dot(gl_FragCoord.xy, (0.06711056, 0.00583715)))) - 0.5, FP32."""
    y, x = np.indices((h, w)).astype(np.float32) + np.float32(0.5)
    inner = x * np.float32(0.06711056) + y * np.float32(0.00583715)
    inner = inner - np.floor(inner)
    v = np.float32(52.9829189) * inner
    return (v - np.floor(v) - np.float32(0.5)).astype(np.float32)


def planes(frame, scale, dither):
    """The int16 planes oldstyle/linear draws of a linear frame (3, h, w) at uScale / 4095 = scale."""
    d = ign(frame.shape[2], frame.shape[1]) if dither else np.float32(0)
    v = np.floor(frame.astype(np.float32) * np.float32(scale * 4095.0) + np.float32(0.5) + d)
    return np.clip(v, -32768, 32767).astype('<i2')


class Tone:
    def __init__(self, exe, tmp):
        self.exe, self.tmp = exe, tmp

    def __call__(self, frame, version, hdr, gs, gl, scale=1.0, pre=1.0, linear_bright=False, dither=False, dehaze=True, lf=True,
                 threads=1):
        """The picture of the app's path: frame drawn at scale x pre, the gains over pre (OldstyleTone)."""
        planes(frame, scale * pre, dither).tofile(self.tmp / 'in.i16')
        subprocess.run([str(self.exe), str(self.tmp / 'in.i16'), str(frame.shape[2]), str(frame.shape[1]), str(version), str(int(hdr)),
                        repr(gs / pre), repr(gl / pre), repr(pre), str(int(linear_bright)), str(int(dehaze)), str(int(lf)), str(threads),
                        str(self.tmp / 'out.rgb')], check=True)
        return np.fromfile(self.tmp / 'out.rgb', np.uint8).reshape(frame.shape[1], frame.shape[2], 3)


def pre_gain(gs, gl):
    """OldstyleTone.preGain for V1."""
    return max(1.0, min(max(gs, gl), 4.0 * min(gs, gl)))


TINT = (0.55, 1.0, 0.45)     # the deck's green


def scene(kind, noise):
    rng = np.random.default_rng(716)
    y, x = np.indices((H, W)).astype(np.float32)
    if kind == 'ramp':
        lum = 0.0005 + 0.04 * x / (W - 1)
    else:
        lum = 0.003 + 0.02 * np.exp(-((x - 600) ** 2 + (y - 256) ** 2) / (2 * 220.0 ** 2))
    f = np.stack([lum * t for t in TINT]).astype(np.float32)
    if noise:
        f = f * (1 + 0.02 * rng.standard_normal(f.shape, np.float32)) + 0.00015 * rng.standard_normal(f.shape, np.float32)
    f[:, :8, :64] = 3.0      # a light: the dehaze's white
    f[:, -24:, :] = 0.0      # a black (the scene's deepest shadow: the adaptive black points' statistics have one)
    return f


def box(a, k):
    return sliding_window_view(np.pad(a, k // 2, mode='edge'), (k, k)).mean((2, 3))


def contours(o):
    """Per channel: the mid-frequency energy of a smooth area (9 x 9 means about their 33 x 33 means), its fine noise (about 5 x 5)."""
    out = []
    for c in range(3):
        a = o[..., c].astype(np.float64)
        b9 = box(a, 9)
        out.append(((b9 - box(b9, 33))[40:-40, 40:-40].std(), (a - box(a, 5))[40:-40, 40:-40].std()))
    return out


def ramp_step(o):
    """Per channel: the largest step between neighbouring columns of the noise-free ramp (row means, the light's rows left out)."""
    rows = o[16:-32, 80:].astype(np.float64).mean(0)
    return [float(np.abs(np.diff(rows[:, c])).max()) for c in range(3)]


def blocks(o):
    return o[:H // 16 * 16, :W // 16 * 16].reshape(H // 16, 16, W // 16, 16, 3).astype(np.float64).mean((1, 3))


def main():
    tmp = Path(tempfile.mkdtemp(prefix='oldstyle_shadows_'))
    try:
        (tmp / 'h.cpp').write_text(HARNESS)
        subprocess.run(['g++', '-std=c++17', '-O2', '-ffp-contract=off', '-I' + str(CPP), '-I' + str(TP / 'include'), str(tmp / 'h.cpp')]
                       + [str(q) for q in sorted((TP / 'src').glob('*.cpp'))] + ['-o', str(tmp / 'h'), '-lpthread'], check=True)
        tone = Tone(tmp / 'h', tmp)
        ramp, blob, noisy = scene('ramp', False), scene('blob', False), scene('blob', True)
        # 1. V1 HDR: the retone of the 8-bit short exposure against the bright exposure from the frame drawn at its gain
        for gs, gl in ((0.92, 3.4), (3.0, 11.0)):
            pre = pre_gain(gs, gl)
            before = {k: tone(f, 1, True, gs, gl) for k, f in (('ramp', ramp), ('blob', blob), ('noisy', noisy))}
            after = {k: tone(f, 1, True, gs, gl, pre=pre, linear_bright=True) for k, f in (('ramp', ramp), ('blob', blob), ('noisy', noisy))}
            sb, sa = ramp_step(before['ramp']), ramp_step(after['ramp'])
            check(max(sa) <= 4 and max(sa) < max(sb), 'V1 HDR %g / %g: the dark ramp\'s largest step R/G/B %s -> %s codes'
                  % (gs, gl, '/'.join('%.0f' % v for v in sb), '/'.join('%.0f' % v for v in sa)))
            cb, ca = contours(before['blob']), contours(after['blob'])
            check(all(a[0] <= 0.7 * b[0] for a, b in zip(ca, cb)), 'V1 HDR %g / %g: the blob\'s contours (RGB) %s -> %s'
                  % (gs, gl, '/'.join('%.3f' % b[0] for b in cb), '/'.join('%.3f' % a[0] for a in ca)))
            nb, na = contours(before['noisy']), contours(after['noisy'])
            check(all(a[0] <= b[0] and a[1] <= b[1] * 1.05 for a, b in zip(na, nb)),
                  'V1 HDR %g / %g with noise: contours %s -> %s, fine noise %s -> %s (no more noise)'
                  % (gs, gl, '/'.join('%.3f' % b[0] for b in nb), '/'.join('%.3f' % a[0] for a in na),
                     '/'.join('%.2f' % b[1] for b in nb), '/'.join('%.2f' % a[1] for a in na)))
            # one thread == four threads
            four = tone(noisy, 1, True, gs, gl, pre=pre, linear_bright=True, threads=4)
            check(np.array_equal(four, after['noisy']), 'V1 HDR %g / %g: the linear bright exposure on 4 threads == 1 thread' % (gs, gl))
        # the look elsewhere: a full-range frame (0.1 % .. 1.5 x white, colours across), block means
        x = np.geomspace(0.001, 1.5, W).astype(np.float32)[None, :]
        yy = np.linspace(0, 1, H, dtype=np.float32)[:, None]
        full = np.stack([x * (0.6 + 0.6 * yy), x * np.ones_like(yy), x * (1.1 - 0.7 * yy)]).astype(np.float32)
        full[:, :8, :64] = 3.0
        full[:, -24:, :] = 0.0
        mids = (slice(None), slice(int(np.searchsorted(x[0], 0.03)) // 16, None))
        for gs, gl in ((0.92, 3.4), (3.0, 11.0), (0.6, 2.2)):
            d = blocks(tone(full, 1, True, gs, gl, pre=pre_gain(gs, gl), linear_bright=True)) - blocks(tone(full, 1, True, gs, gl))
            m = d[mids]
            check(abs(m.mean()) <= 1.0 and np.abs(m).mean() <= 1.5 and np.percentile(np.abs(m), 99) <= 4.5,
                  'V1 HDR %g / %g: from 3 %% of white up the look is kept (block means: mean %+.2f, mean |d| %.2f, p99 %.2f codes; '
                  'the whole frame mean %+.2f)' % (gs, gl, m.mean(), np.abs(m).mean(), np.percentile(np.abs(m), 99), d.mean()))
        # SDR: the frame drawn at its gain - no more steps than before, the same look
        for g in (1.0, 4.0):
            a, b = tone(ramp, 1, False, g, g), tone(ramp, 1, False, g, g, pre=pre_gain(g, g))
            fa, fb = tone(full, 1, False, g, g), tone(full, 1, False, g, g, pre=pre_gain(g, g))
            dd = np.abs(blocks(fb) - blocks(fa))
            check(max(ramp_step(b)) <= max(ramp_step(a)) and dd.mean() <= 0.25 and np.percentile(dd, 99) <= 1.5,
                  'V1 SDR %g: largest step %.0f -> %.0f, block means: mean |d| %.2f, p99 %.2f, max %.2f codes (the steps of the '
                  'frame rounded before the gain)' % (g, max(ramp_step(a)), max(ramp_step(b)), dd.mean(), np.percentile(dd, 99), dd.max()))
        # 2. V2 HDR at a sun of 8 x white: the rounding dithered; the reference is the same frame without the sun (input scale 1,
        # the gains over the scale): the precision the module has without it
        for gs, gl in ((8.0, 32.0), (4.0, 16.0)):
            sc = 1.0 / gs
            ref = blocks(tone(blob, 2, True, 1.0, gl / gs))
            b, a = tone(blob, 2, True, gs, gl, scale=sc), tone(blob, 2, True, gs, gl, scale=sc, dither=True)
            cb, ca = contours(b), contours(a)
            eb, ea = np.abs(blocks(b) - ref).mean(), np.abs(blocks(a) - ref).mean()
            check(all(x[0] <= 0.35 * y[0] for x, y in zip(ca, cb)) and ea <= min(1.0, eb),
                  'V2 HDR input scale 1/%g gains %g / %g: contours (RGB) %s -> %s, block means off the reference by %.2f -> %.2f codes '
                  '(fine noise %s -> %s)' % (gs, gs, gl, '/'.join('%.3f' % v[0] for v in cb), '/'.join('%.3f' % v[0] for v in ca), eb, ea,
                                             '/'.join('%.2f' % v[1] for v in cb), '/'.join('%.2f' % v[1] for v in ca)))
            rb, ra = ramp_step(tone(ramp, 2, True, gs, gl, scale=sc)), ramp_step(tone(ramp, 2, True, gs, gl, scale=sc, dither=True))
            check(max(ra) <= 0.35 * max(rb), 'V2 HDR input scale 1/%g: the dark ramp\'s largest step %s -> %s codes'
                  % (gs, '/'.join('%.0f' % v for v in rb), '/'.join('%.1f' % v for v in ra)))
        # the dither: the mean of a flat area kept (its values sum to about nothing over the frame)
        dz = ign(W, H)
        check(abs(float(dz.mean())) < 2e-3 and dz.min() >= -0.5 and dz.max() < 0.5,
              'the dither: mean %.4f, range %.3f .. %.3f' % (dz.mean(), dz.min(), dz.max()))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    if FAIL:
        print('%d FAILED' % len(FAIL))
        sys.exit(1)
    print('OK')


if __name__ == '__main__':
    main()
