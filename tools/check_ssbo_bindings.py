"""P552: no shader storage binding the smallest GPUs lack. GLES 3.1 guarantees GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS >= 8 only;
the phones in use report 24 (Adreno 750: bindings 0..23) and more. A binding of 24 or above must sit inside an #ifdef that the
C++ side defines only where the GPU has it (P545's NNorm2: NAT_NORM2 when GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS > 24); an
unguarded one failed the native Quad / Tetra merge's compile on an Adreno 750 («'NNorm2' : binding exceeds limit»)."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 24
GUARDS = {'NAT_NORM2'}
fails = []
for f in sorted((ROOT / 'app/src/main/cpp').glob('*.h')) + sorted((ROOT / 'app/src/main/cpp').glob('*.cpp')):
    stack = []
    for n, line in enumerate(f.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        t = line.strip()
        m = re.match(r'#\s*(ifdef|ifndef|if)\s+(.*)', t)
        if m:
            stack.append(m.group(2).split()[0] if m.group(1) == 'ifdef' else '')
            continue
        if re.match(r'#\s*endif', t) and stack:
            stack.pop()
            continue
        if re.match(r'#\s*(else|elif)', t) and stack:
            stack[-1] = ''
            continue
        for b in re.findall(r'binding\s*=\s*(\d+)', line):
            if int(b) >= LIMIT and not (GUARDS & set(stack)):
                fails.append('%s:%d binding=%s outside %s' % (f.name, n, b, '/'.join(sorted(GUARDS))))
for x in fails:
    print('FAIL ' + x)
src = (ROOT / 'app/src/main/cpp/scam-hybrid.h').read_text(encoding='utf-8')
ok = 'natNorm2=bindings>24' in src and src.count('natNorm2?"#define NAT_NORM2 1\\n":""') >= 2
print(('PASS' if ok else 'FAIL') + ' NAT_NORM2 defined only where binding 24 exists (natFastProgram and natNorm)')
if not ok:
    fails.append('define')
# P553: a failed native Quad / Tetra merge is retried natively (safe GPU, fewer frames) before the split
ladder = ('hybridNativeSafeLevel().load()==0' in src and 'for(int attempt=0;attempt<4;++attempt)' in src
          and 'merging on the sub-frame split (mosaicPath 0)' not in src
          and 'throw std::runtime_error("HYBRID MOSAIC NATIVE: every native retry failed ("+why+")");' in src)
print(('PASS' if ladder else 'FAIL') + ' native mosaic merge retried natively, never the split (P553, P556)')
if not ladder:
    fails.append('ladder')
# P555: the normalised blocks as an image (written at image unit 0, read at texture unit 7) behind NAT_TEX, both programs built with it
tex = (src.count('natTex?"#define NAT_TEX 1\\n":""') >= 2 and 'layout(rgba32ui,binding=0) writeonly uniform highp uimage2D nimg;' in src
       and 'layout(binding=7) uniform highp usampler2D nnormT;' in src and 'glActiveTexture(GL_TEXTURE7);glBindTexture(GL_TEXTURE_2D,natNormTex)' in src
       and 'GL_TEXTURE_MIN_FILTER,GL_NEAREST' in src and 'if(natTex)natNorm2=false;' in src)
print(('PASS' if tex else 'FAIL') + ' native blocks image (NAT_TEX) wired in both programs (P555)')
if not tex:
    fails.append('nat tex')
print('ssbo bindings check ' + ('FAIL' if fails else 'PASS'))
sys.exit(1 if fails else 0)
