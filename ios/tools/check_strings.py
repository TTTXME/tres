#!/usr/bin/env python3
"""Every iOS interface key exists in English and Russian, and every L("key") used in Swift is defined."""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parents[1]
pair = re.compile(r'^"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)";\s*$')

def load(path):
    keys = {}
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        m = pair.match(line)
        if not m:
            sys.exit(f"{path}:{n}: not a strings entry: {line}")
        if m.group(1) in keys:
            sys.exit(f"{path}:{n}: duplicate key {m.group(1)}")
        keys[m.group(1)] = m.group(2)
    return keys

failed = False
for name in ("Localizable.strings", "InfoPlist.strings"):
    en = load(root / "Resources/en.lproj" / name)
    ru = load(root / "Resources/ru.lproj" / name)
    for key in sorted(set(en) ^ set(ru)):
        print(f"{name}: {key} only in {'en' if key in en else 'ru'}")
        failed = True
    if name == "Localizable.strings":
        used = set()
        for swift in root.rglob("*.swift"):
            used |= set(re.findall(r'\bL\("([^"]+)"\)', swift.read_text(encoding="utf-8")))
        for key in sorted(used - set(en)):
            print(f"{name}: {key} used in Swift but not defined")
            failed = True
for word in ("Google", "GCam", "HDR+"):
    for path in list(root.rglob("*.swift")) + list(root.rglob("*.strings")):
        if word in path.read_text(encoding="utf-8"):
            print(f"{path}: forbidden name {word}")
            failed = True
sys.exit(1 if failed else 0)
