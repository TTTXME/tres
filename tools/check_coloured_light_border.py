"""P541: inside a saturated coloured light the border-only pixels keep the light's colour (scamhdr/scamrgb blanket border defringe).

Ark burst 142514 (Bayer): a red pedestrian signal, R near its clip, G and B far below; pixels whose kernels caught a few clipped R
samples carry the border bit alone, and the blanket defringe gave them the local light's prior - faded to neutral for so strong
a colour - at a third of their brightness: a white lattice over the red figure. Runs the shipped recovery (tools/ark_compare.py
app_highlights on the scamrgb / clipband shaders) on a synthetic signal: its border pixels stay red (saturation and the R : G
ratio of their own data), and a white lamp's coloured border fringe is still taken to the lamp's colour."""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ark_compare as C  # noqa: E402

fails = []


def check(ok, what):
    print(('PASS ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)


H, W = 96, 96
wb = np.array([2.36, 1.0, 1.63], np.float32)
lsc = np.ones((13, 17, 4), np.float32)
rng = np.random.default_rng(5)
raw = np.full((H, W, 3), 0.01, np.float32)
flags = np.zeros((H, W), np.uint8)
# the signal: a 40 x 40 red LED field (each LED 3 x 3 at 0.84 of white in R), border bits on a third of its pixels, R clipped
# means on a few
led = np.zeros((H, W), bool)
led[8:48, 8:48] = True
raw[led] = np.array([0.84, 0.22, 0.03], np.float32) * rng.uniform(0.9, 1.0, (led.sum(), 1)).astype(np.float32)
border = led & (rng.random((H, W)) < 0.33)
flags[border] = 8
core = led & (rng.random((H, W)) < 0.12)
raw[core, 0] = 1.0
flags[core] = 9
# a white lamp (all clipped) with a 1-px fringe ring of mixed colour, border bit set
lamp = np.zeros((H, W), bool)
lamp[60:84, 60:84] = True
raw[lamp] = 1.0
flags[lamp] = 15
ring = np.zeros((H, W), bool)
ring[59:85, 59:85] = True
ring &= ~lamp
raw[ring] = np.array([0.30, 0.55, 0.20], np.float32)   # a green fringe beside a white lamp (white-balanced: 0.71, 0.55, 0.33)
flags[ring] = 8
raw[(~led) & (~lamp) & (~ring)] = np.array([0.02, 0.03, 0.02], np.float32)

lin = C.app_highlights(raw, flags, lsc, (1.0 / wb[0], 1.0, 1.0 / wb[2]), 0.0)
src = raw * wb


def sat(v):
    return (v.max(-1) - v.min(-1)) / np.maximum(v.max(-1), 1e-6)


b = border & ~core
s_in, s_out = sat(src[b]), sat(lin[b])
check(np.percentile(s_out, 5) > 0.8, 'the signal\'s border pixels stay red: saturation p5 %.3f (their data %.3f)' % (np.percentile(s_out, 5),
                                                                                                                np.percentile(s_in, 5)))
rg_in = src[b, 1] / src[b, 0]
rg_out = lin[b, 1] / np.maximum(lin[b, 0], 1e-6)
check(np.max(np.abs(rg_out - rg_in)) < 0.05, 'their G : R as measured: largest change %.4f' % np.max(np.abs(rg_out - rg_in)))
check(np.min(lin[b].max(-1) / src[b].max(-1)) > 0.9, 'their brightness kept: least ratio %.3f' % np.min(lin[b].max(-1) / src[b].max(-1)))
fr_in, fr_out = sat(src[ring]).mean(), sat(lin[ring]).mean()
check(fr_out < 0.5 * fr_in, 'a white lamp\'s border fringe still defringed: saturation %.3f -> %.3f' % (fr_in, fr_out))
print('coloured light border check ' + ('FAIL' if fails else 'PASS'))
sys.exit(1 if fails else 0)
