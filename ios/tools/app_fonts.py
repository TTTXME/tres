#!/usr/bin/env python3
"""Roboto Flex everywhere (Android Fonts.java, P582): rewrites the SwiftUI system fonts of the given Swift files (default: every
file under ios/) to Font.app(size, weight) (Support/AppFont.swift). `.system(size: s[, weight: w])` and the text styles (`.caption`,
`.footnote.weight(.semibold)`, ...) at their default sizes; monospaced / serif designs stay the system's. `--check` lists what
would change and fails when anything would."""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parents[1]
WEIGHTS = {"ultraLight": 400, "thin": 400, "light": 400, "regular": 400, "medium": 500, "semibold": 600, "bold": 700,
           "heavy": 800, "black": 800}
# the iOS text styles at the default content size
STYLES = {"largeTitle": (34, 400), "title": (28, 400), "title2": (22, 400), "title3": (20, 400), "headline": (17, 600),
          "body": (17, 400), "callout": (16, 400), "subheadline": (15, 400), "footnote": (13, 400), "caption": (12, 400),
          "caption2": (11, 400)}

system = re.compile(r"\.system\(size: ([^,()]+?)(?:, weight: ([^()]+?))?\)")
style = re.compile(r"\.font\(\.(" + "|".join(STYLES) + r")(?:\.weight\(\.(\w+)\))?(?!\.monospaced\(\))(?=[).])")


def weight_expr(expr):
    def one(m):
        return str(WEIGHTS[m.group(1)]) if m.group(1) in WEIGHTS else m.group(0)
    return re.sub(r"(?<![\w)])\.(\w+)", one, expr.strip())


def rewrite(text):
    def sys_font(m):
        size, w = m.group(1).strip(), m.group(2)
        if w is None:
            return f".app({size})"
        return f".app({size}, {weight_expr(w)})"

    def style_font(m):
        size, weight = STYLES[m.group(1)]
        if m.group(2):
            weight = WEIGHTS.get(m.group(2), weight)
        return f".font(.app({size}" + (f", {weight}" if weight != 400 else "") + ")"

    text = system.sub(sys_font, text)
    return style.sub(style_font, text)


def main(argv):
    check = "--check" in argv
    files = [pathlib.Path(a) for a in argv if not a.startswith("--")] or sorted(root.rglob("*.swift"))
    changed = []
    for f in files:
        if f.name == "AppFont.swift":
            continue
        old = f.read_text(encoding="utf-8")
        new = rewrite(old)
        if new != old:
            changed.append(f)
            if not check:
                f.write_text(new, encoding="utf-8")
    for f in changed:
        print(("would change " if check else "changed ") + str(f))
    return 1 if check and changed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
