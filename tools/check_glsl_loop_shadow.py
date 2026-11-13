#!/usr/bin/env python3
# Mali-G925 (r5x, mt6991) leaks a for-loop variable over a same-named function parameter: after
# «for(int c=0;...)» inside a function with «float c», «c*(1.0-x)» reads as int*float and the whole program is refused (P612,
# 31253: the Bento blend's US_CLIP_VOTE loop, «Качество» dead on every MTK shot). glslang and llvmpipe accept it, so this
# check keeps loop indices from reusing a parameter's name in every GLSL function of the app.
#
# P662, --dump DIR (the shaders lane, after tools/check_glsl_programs.cpp): the programs as the merge hands them to the driver
# (SCAM_GLSL_DUMP, scam-glsl-guard.h; llvmpipe builds the Mali / PowerVR variants: no SHARED_TILES, cellsPlain, single blend) are
# compiled by the NDK's glslc as strict GLSL ES 3.10 (no implicit int / float conversion: Mali's S0001), then once more with the
# Mali-G925's loop scoping emulated: «for(int c=...» opens a scope of its own, «{int c;for(c=...», that lasts to the end of the
# enclosing block (a later loop of the same name opens another), so a name the loop shadows reads as the loop's int after it - the
# 31253 blend0 refusal «3:676: S0001: Type mismatch in arithmetic operation between 'int' and 'float'» (P612) fails here,
# glslang and llvmpipe alone accept it. The scope opens where the loop's statement starts (before the «if (...)» / «for (...)» heads it is the body of); a loop
# right after an «else» or in a switch's body is left as it is (counted).
#   usage: check_glsl_loop_shadow.py [--dump DIR [--glslc PATH]]
import glob, os, pathlib, re, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
TYPES = r"(?:float|int|uint|bool|[iub]?vec[234]|mat[234](?:x[234])?)"
DECL = re.compile(r"(?<![\w.])(?:const\s+|highp\s+|mediump\s+|lowp\s+|in\s+|out\s+|inout\s+)*(" + TYPES + r")\s+(\w+)\s*(?=[=;,)\[])")
LOOP = re.compile(r"\bfor\s*\(\s*(?:highp\s+|mediump\s+|lowp\s+)?(int|uint)\s+(\w+)")
FUNC = re.compile(r"(?m)^[ \t]*(?:highp\s+|mediump\s+|lowp\s+)?(?:void|" + TYPES + r")\s+(\w+)\s*\(([^()]*)\)\s*\{")


def sources():
    for p in sorted((ROOT / "app/src/main/cpp").glob("*.h")) + sorted((ROOT / "app/src/main/cpp").glob("*.cpp")):
        text = p.read_text(errors="replace")
        # raw strings with any delimiter (R"( )", P624's R"ST( )ST")
        for m in re.finditer(r'R"(\w{0,16})\((.*?)\)\1"', text, re.S):
            yield f"{p.relative_to(ROOT)}:{text.count(chr(10), 0, m.start()) + 1}", m.group(2)
    for p in sorted((ROOT / "app/src/main/assets/shaders").rglob("*.glsl")):
        yield str(p.relative_to(ROOT)), p.read_text(errors="replace")


def body(src, start):
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return src[start:]


def find_glslc(argv):
    if '--glslc' in argv:
        return argv[argv.index('--glslc') + 1]
    found = shutil.which('glslc')
    if found:
        return found
    for env in ('ANDROID_NDK_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        base = os.environ.get(env)
        if base and os.path.isdir(base):
            for pattern in (('ndk', '*', 'shader-tools', '*', 'glslc*'), ('shader-tools', '*', 'glslc*')):
                hits = sorted(glob.glob(os.path.join(base, *pattern)), reverse=True)
                if hits:
                    return hits[0]
    return None


STAGES = {'.comp': 'compute', '.vert': 'vertex', '.frag': 'fragment'}
LEAK_FOR = re.compile(r"\bfor\s*\(\s*((?:highp\s+|mediump\s+|lowp\s+)?(?:int|uint))\s+(\w+)\s*=")
ERR = re.compile(r":(\d+): error: (.*)")


def glslc_run(glslc, stage, src, extra=()):
    with tempfile.TemporaryDirectory() as tmp:
        f = os.path.join(tmp, 'p.glsl')
        with open(f, 'w') as out:
            out.write(src)
        r = subprocess.run([glslc, '--target-env=opengl', '-fauto-map-locations', '-fshader-stage=' + stage, *extra, f,
                            '-o', os.path.join(tmp, 'o')], capture_output=True, encoding='utf-8', errors='replace')
        return r.returncode, r.stdout + r.stderr


def statement_start(src, i):
    """Where the statement holding the loop at i starts: back over «if (...)» / «for (...)» / «while (...)» heads (a loop that is
    the body of another one or of an if); None after an else (left as it is)."""
    while True:
        j = len(src[:i].rstrip())
        if j == 0 or src[j - 1] in ';{}':
            return i
        if src[j - 1] != ')':
            return None
        depth, k = 0, j - 1
        while k >= 0:
            if src[k] == ')':
                depth += 1
            elif src[k] == '(':
                depth -= 1
                if depth == 0:
                    break
            k -= 1
        m = re.search(r"\b(if|for|while)\s*$", src[:k])
        if not m:
            return None
        i = m.start()


def leak_emulated(src):
    """Mali's loop scoping: «for(int x=» opens a scope of its own at the start of the loop's statement that lasts to the end of
    the enclosing block - «{int x;for(x=» there and «}» before the block's closing brace (a later loop of the same name opens
    another, shadowing it, as Mali accepts). Returns (source, loops not emulated: right after an else, or in a switch's body)."""
    stack = [None]  # per open brace: (open position, closing inserts) ; None for the file scope
    loops = {m.start(): m for m in LEAK_FOR.finditer(src)}
    edits, skipped = [], 0  # (position, order, text)
    opens = []
    for i, c in enumerate(src):
        if c == '{':
            stack.append([i, 0])
        elif c == '}':
            if len(stack) > 1:
                top = stack.pop()
                if top[1]:
                    edits.append((i, 0, '}' * top[1]))
        elif i in loops:
            m = loops[i]
            typ, name = re.sub(r'\s+', ' ', m.group(1)).split(' ')[-1], m.group(2)
            at = statement_start(src, i)
            block = stack[-1]
            if at is None or block is None or re.search(r"\bswitch\s*\([^{}]*\)\s*$", src[:block[0]]):
                skipped += 1
                continue
            block[1] += 1
            edits.append((at, 1, '{' + typ + ' ' + name + ';'))
            edits.append((m.start(), 2, None, m.end(), 'for(' + name + '='))
    out, pos = [], 0
    for e in sorted(edits, key=lambda e: (e[0], e[1])):
        out.append(src[pos:e[0]])
        if e[2] is None:
            out.append(e[4])
            pos = e[3]
        else:
            out.append(e[2])
            pos = e[0]
    out.append(src[pos:])
    return ''.join(out), skipped


def check_dump(directory, glslc):
    bad, n, skipped = [], 0, 0
    files = sorted(p for p in pathlib.Path(directory).iterdir() if p.suffix in STAGES)
    for p in files:
        n += 1
        stage = STAGES[p.suffix]
        src = p.read_text(errors='replace')
        rc, log = glslc_run(glslc, stage, src)
        if rc != 0:
            bad.append(f"{p.name}: refused as strict GLSL ES: {log.strip()[:400]}")
            continue
        r = subprocess.run([glslc, '--target-env=opengl', '-fauto-map-locations', '-fshader-stage=' + stage, '-E', '-'],
                           input=src, capture_output=True, encoding='utf-8', errors='replace')
        if r.returncode != 0:
            bad.append(f"{p.name}: not preprocessed: {r.stderr.strip()[:200]}")
            continue
        emulated, sk = leak_emulated(r.stdout)
        rc, log = glslc_run(glslc, stage, emulated)
        if rc != 0:
            errs = [(int(m.group(1)), m.group(2)) for m in ERR.finditer(log)]
            lines = emulated.split('\n')
            shown = '; '.join(f"line {line}: {msg} «{lines[line - 1].strip()[:120] if 0 < line <= len(lines) else ''}»" for line, msg in errs[:3])
            bad.append(f"{p.name}: refused with Mali's loop-index scoping emulated: {shown or log.strip()[:300]}")
        skipped += sk
    for line in bad:
        print("FAIL", line)
    print(f"{n} dumped programs (strict GLSL ES + Mali loop-index leak emulated), {len(bad)} refused"
          f"{f', {skipped} loops after an else / in a switch not emulated' if skipped else ''}")
    if n == 0:
        print("FAIL no dumped programs in", directory)
        return 1
    return 1 if bad else 0


def main():
    if '--dump' in sys.argv:
        glslc = find_glslc(sys.argv)
        if not glslc:
            print('glslc not found (PATH, $ANDROID_NDK_HOME, $ANDROID_HOME): GLSL program check FAIL')
            return 1
        return check_dump(sys.argv[sys.argv.index('--dump') + 1], glslc)
    bad, funcs = [], 0
    for where, src in sources():
        src = re.sub(r"//[^\n]*", "", src)
        for f in FUNC.finditer(src):
            funcs += 1
            params = {n: t for t, n in DECL.findall(f.group(2) + ")")}
            b = body(src, f.end() - 1)
            loops = {n for _, n in LOOP.findall(b)}
            for n in sorted(loops):
                clash = params.get(n)
                if clash:
                    bad.append(f"{where}: {f.group(1)}(): loop index «{n}» over the {clash} parameter «{n}»")
    for line in bad:
        print("FAIL", line)
    print(f"{funcs} GLSL functions, {len(bad)} loop indices over a parameter")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
