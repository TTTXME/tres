#!/usr/bin/env python3
"""P402 (owner, 2026-10-29: «dezipper нам также перенеси»): the merge's dezipper (HybridTuning::dezipper, kHybDezip put into
kHybMergeCommon by hybridDezipSource only when it is on). Checks on app/src/main/cpp/scam-hybrid.h:
- off (the default): kHybCommon, kHybHelpers, kHybMergeCommon and kHybMergeMain1 are the sources before P402 (their SHA-256; no
  line of the merge program changed), hybridPartialSpec adds DEZIP only for dezipper > 0, the app sends no line for 0;
- on: kHybMergeCommon with the dezipper as hybridDezipSource builds it (before frameSamples, its call after each role line)
  compiles as the merge program with kHybMergeMain1 on the host GPU (moderngl, GLSL 4.30);
- the base frame's weight (kHybDezip's dezip, mirrored): 1 with every donor accepted, the dezipper with every donor rejected,
  max(1, dezipper - accepted) at 70 % or less of the donors, a smooth ramp between (98 % .. 70 %), never below 1.
P480 (owner, 2026-11-03: the dezipper «полностью идентично» the reference's): kHybDezip is the reference base self-merge's whole
motion fallback (dzBase: the isotropised covariance, the linear kernel, blend_eq, the green-phase chroma correction), run by its own
pass after the merge (kHybDzPass, with the per-cell coverage of kHybDzCover); the merge program only stores its weight sums
(hybridDezipSource: kHybDezipMerge, hybridDezipMain: the store). The reference constants are checked in its text, the three
programs compiled.
Usage: check_hybrid_dezipper.py
"""
from pathlib import Path
import re
import hashlib
import sys

import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
H = ROOT / 'app/src/main/cpp/scam-hybrid.h'
SRC = H.read_text(encoding='utf-8')
JAVA = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/settings/PreferenceKeys.java').read_text(encoding='utf-8')
FAIL = []
# P478 (2026-11-03): one comment of kHybMergeCommon renamed the reference app (P335's line) - the code is unchanged
MERGE_SOURCES = '7b467ebd634f91722dc84bd8a1115c9351c66aac25edb13b20243f18eb64e8e5'


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def body(src, name):
    m = re.search(r'static const char\* ' + name + r'=R"\((.*?)\)";', src, re.S)
    assert m, name
    return m.group(1)


def dezip_source(common):
    """hybridDezipSource (P480: the merge's declarations, kHybDezipMerge, before frameSamples)."""
    head = "void frameSamples(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win){\n"
    assert common.count(head) == 1
    return common.replace(head, body(SRC, 'kHybDezipMerge') + head)


def dezip_main(main):
    """hybridDezipMain (P480: the merge also stores its per-colour weight sums for kHybDzPass)."""
    out = "        outRgb[o]=col.x;outRgb[o+1]=col.y;outRgb[o+2]=col.z;\n"
    assert main.count(out) == 1
    return main.replace(out, out + "#ifndef DZ_NODEN\n        dzDen[o]=a.den.x;dzDen[o+1]=a.den.y;dzDen[o+2]=a.den.z;\n#endif\n")


def dezip(donors, expected, t):
    hole = np.clip((0.98 - donors / max(expected, 1.0)) / 0.28, 0.0, 1.0)
    return 1.0 + (max(1.0, t - donors) - 1.0) * hole


def main():
    # the four sources of the merge program before P402 (fc15982a), as one stream: unchanged while the dezipper is off
    # P415: noiseU's deep-shadow read term (its uniform and one line, a no-op while darkReadU.x is 0: noiseDarkRead off) is the only
    # change since; both lines must be there exactly once and are taken out of the stream
    p415 = ('uniform vec3 darkReadU;      // P415 noiseDarkRead: x the read term\'s extra factor in noiseU, full below y, none from z (0: off)\n',
            '    if(darkReadU.x>0.0)var+=darkReadU.x*fNoiseP[f].xy.y*g*g*(1.0-smoothstep(darkReadU.y,darkReadU.z,l));\n')
    stream = ''.join(body(SRC, n) for n in ('kHybCommon', 'kHybHelpers', 'kHybMergeCommon', 'kHybMergeMain1'))
    check(all(stream.count(line) == 1 for line in p415), 'P415: the deep-shadow read term in noiseU, once')
    for line in p415:
        stream = stream.replace(line, '')
    # P525: the demosaic writes its pixels' coverage, so Eff is read-write (the binding itself unchanged)
    p525 = ('layout(std430,binding=4) buffer Eff{float eff[];}; // P525: the demosaic sets its pixels\' coverage\n',
            'layout(std430,binding=4) writeonly buffer Eff{float eff[];};\n')
    check(stream.count(p525[0]) == 1, 'P525: Eff read-write, once')
    stream = stream.replace(*p525)
    got = hashlib.sha256(stream.encode()).hexdigest()
    check(got == MERGE_SOURCES, 'the merge program\'s sources unchanged without the dezipper (%s)' % got[:12])
    spec = re.search(r'inline std::string hybridPartialSpec\(.*?\n\}', SRC, re.S).group(0)
    check('+(t.dezipper>0.f?"#define DEZIP 1\\n":"")' in spec, 'hybridPartialSpec: DEZIP only for dezipper > 0')
    check('float dezipper=0.f;' in SRC and 'set("dezipper",&t.dezipper)' in SRC, 'HybridTuning::dezipper 0 by default, its tuning key')
    check('if (dezipper > 0f) out.append("dezipper ")' in JAVA, 'the app sends no line for 0')
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    text = body(SRC, 'kHybCommon') + body(SRC, 'kHybHelpers') + dezip_source(body(SRC, 'kHybMergeCommon')) + dezip_main(body(SRC, 'kHybMergeMain1'))
    dz = body(SRC, 'kHybDezip')
    for item in ('float hole=clamp((0.98-wsf/E)/0.28,0.0,1.0);', 'float be=clamp((0.98-mn/E)/0.28,0.0,1.0);',
                 'r*=mix(1.0,max(1.0,dezipU.x-wsf),hole);', 'vec3(0.462098,0.462098,0.0)', 'float lkm=0.85*hole;',
                 'max(1.0-0.45*length(d),0.0)', 'baseG=mix(baseG,gC,0.50);', 'readonly buffer DzCover'):
        check(item in dz, 'P480 kHybDezip: ' + item)
    check('dzBase' not in text, 'P480: no dezipper code in the merge program itself (its weight sums only)')
    check(abs(0.693147 / 1.5 - 0.462098) < 1e-6, 'P480: motion_cov 1 / 1.5 as our precision (x ln 2)')
    prefix = body(SRC, 'kHybCommon') + body(SRC, 'kHybHelpers')
    passes = [('the merge program with the dezipper', text), ('kHybDzCover', prefix + body(SRC, 'kHybDzCover')),
              ('kHybDzPass', prefix + '#define RIM_STATS 1\n' + body(SRC, 'kHybMergeCommon') + dz + body(SRC, 'kHybDzPass'))]
    for name, src in passes:
        try:
            ctx.compute_shader(src.replace('#version 310 es', '#version 430', 1))
            ok = True
        except Exception as e:
            print(str(e)[:2000])
            ok = False
        check(ok, name + ' compiles')
    t, n = 4.5, 9
    check(dezip(9.0, n, t) == 1.0 and dezip(8.9, n, t) == 1.0, 'every donor accepted: the base weighs 1')
    check(abs(dezip(0.0, n, t) - 4.5) < 1e-12, 'every donor rejected: the dezipper (%.2f)' % dezip(0.0, n, t))
    check(abs(dezip(0.7 * n, n, t) - 1.0) < 1e-12 and abs(dezip(2.0, n, t) - 2.5) < 1e-12, 'max(1, dezipper - accepted) at 70 % or less')
    ws = [dezip(d, n, t) for d in np.linspace(0, n, 901)]
    check(min(ws) >= 1.0 and np.abs(np.diff(ws)).max() < 0.02, 'never below 1, no step (%.4f)' % np.abs(np.diff(ws)).max())
    print('hybrid dezipper check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
