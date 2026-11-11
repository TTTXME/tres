#!/usr/bin/env python3
# Mali-G925 (r5x, mt6991) leaks a for-loop variable over a same-named function parameter: after
# «for(int c=0;...)» inside a function with «float c», «c*(1.0-x)» reads as int*float and the whole program is refused (P612,
# 31253: the Bento blend's US_CLIP_VOTE loop, «Качество» dead on every MTK shot). glslang and llvmpipe accept it, so this
# check keeps loop indices from reusing a parameter's name in every GLSL function of the app.
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
TYPES = r"(?:float|int|uint|bool|[iub]?vec[234]|mat[234](?:x[234])?)"
DECL = re.compile(r"(?<![\w.])(?:const\s+|highp\s+|mediump\s+|lowp\s+|in\s+|out\s+|inout\s+)*(" + TYPES + r")\s+(\w+)\s*(?=[=;,)\[])")
LOOP = re.compile(r"\bfor\s*\(\s*(?:highp\s+|mediump\s+|lowp\s+)?(int|uint)\s+(\w+)")
FUNC = re.compile(r"(?m)^[ \t]*(?:highp\s+|mediump\s+|lowp\s+)?(?:void|" + TYPES + r")\s+(\w+)\s*\(([^()]*)\)\s*\{")


def sources():
    for p in sorted((ROOT / "app/src/main/cpp").glob("*.h")) + sorted((ROOT / "app/src/main/cpp").glob("*.cpp")):
        text = p.read_text(errors="replace")
        for m in re.finditer(r'R"\((.*?)\)"', text, re.S):
            yield f"{p.relative_to(ROOT)}:{text.count(chr(10), 0, m.start()) + 1}", m.group(1)
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


def main():
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
