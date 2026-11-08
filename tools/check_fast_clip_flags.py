"""P540: the fast merge's clip flags over the short frames' light (scam-fast-gpu.h kFastOutput clipAt).

Runs the shipped clipAt in a GL compute shader: without short frames a channel is flagged at >= 0.94 (as before P540); with them
a value above the base frames' 1 (measured by the short frames) is flagged only at their own clip (u_clip_short), and inside their
final mask also a value at the base's white. The output's border bit is not set over that light."""
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/cpp/scam-fast-gpu.h').read_text(encoding='utf-8')
MERGE = (ROOT / 'app/src/main/cpp/scam-fast-merge.h').read_text(encoding='utf-8')
fails = []


def check(ok, what):
    print(('PASS ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)


def ref(x, sd, clip, cs):
    x, clip, cs = np.float32(x), np.float32(clip), np.float32(cs)  # the shader's floats
    if cs <= 0:
        return x >= clip
    if x >= cs:
        return True
    return (not sd) and clip <= x <= np.float32(1.02)


m = re.search(r'bool clipAt\(float x, bool shortData\) \{.*?\n\}', SRC, re.S)
check(m is not None, 'clipAt in kFastOutput')
check(SRC.count('if(clipAt(v.r,sd))f|=1u;') == 1 and SRC.count('if(clipAt(v.g,sd))f|=2u;') == 1
      and SRC.count('if(clipAt(v.b,sd))f|=4u;') == 1, 'every channel flag through clipAt')
check('if(cf>0.02&&cf<0.98&&!(sd&&max(v.r,max(v.g,v.b))>1.02))f|=8u;' in SRC, 'border bit not over the short frames\' light')
check(MERGE.count('g.u1f(A::pOutput,"u_clip_short",validShorts>0?clipShort():0.f);') == 2, 'u_clip_short set in both output passes')
check('return e>1.f?0.94f*e:0.f;' in MERGE, 'u_clip_short = 0.94 x the lowest valid short gain')

xs = np.array([0.5, 0.93, 0.94, 0.99, 1.0, 1.02, 1.021, 1.5, 3.0, 8.0, 8.35, 8.4, 20.0], np.float32)
cases = [(sd, cs) for sd in (0, 1) for cs in (0.0, 8.35)]
try:
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    body = m.group(0) if m else ''
    cs_src = ('#version 430\nlayout(local_size_x=1) in;\nuniform float u_clip;\nuniform float u_clip_short;\nuniform int sdU;\n'
              'layout(std430,binding=0) buffer X{float x[];};\nlayout(std430,binding=1) buffer Y{uint y[];};\n' + body +
              '\nvoid main(){uint i=gl_GlobalInvocationID.x;y[i]=clipAt(x[i],sdU!=0)?1u:0u;}\n')
    prog = ctx.compute_shader(cs_src)
    bx = ctx.buffer(xs.tobytes())
    for sd, cs in cases:
        by = ctx.buffer(reserve=4 * xs.size)
        bx.bind_to_storage_buffer(0)
        by.bind_to_storage_buffer(1)
        prog['u_clip'].value = 0.94
        if 'u_clip_short' in prog:
            prog['u_clip_short'].value = cs
        prog['sdU'].value = sd
        prog.run(xs.size)
        got = np.frombuffer(by.read(), np.uint32).astype(bool)
        want = np.array([ref(float(x), sd, 0.94, cs) for x in xs])
        check(np.array_equal(got, want), 'GL clipAt, short data %d, short clip %.2f: %s' % (sd, cs, ''.join('1' if g else '0' for g in got)))
    ctx.release()
except ImportError:
    print('moderngl missing: GL part skipped')
    sys.exit(1)

# the rule's intent on the reference
check(ref(1.0, 0, 0.94, 0.0) and ref(3.0, 0, 0.94, 0.0), 'no short frames: >= 0.94 flagged (unchanged)')
check(not ref(3.0, 0, 0.94, 8.35) and not ref(3.0, 1, 0.94, 8.35), 'short frames: measured light above 1 not flagged')
check(ref(0.97, 0, 0.94, 8.35) and not ref(0.97, 1, 0.94, 8.35), 'short frames: the base\'s white flagged outside their mask only')
check(ref(8.4, 1, 0.94, 8.35), 'short frames: their own clip flagged')
print('fast clip flags check ' + ('FAIL' if fails else 'PASS'))
sys.exit(1 if fails else 0)
