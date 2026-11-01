from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request


def main():
    parser = argparse.ArgumentParser(description="Regenerate the pinned GPL spectral profile tables")
    parser.add_argument("--source", type=Path)
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--python", default=sys.executable)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if bool(args.source) == args.download:
        parser.error("Choose --source DIRECTORY or --download")
    root = Path(__file__).resolve().parents[1]
    target = root / "app/src/main/cpp/third_party/spektrafilm"
    identity = json.loads((target / "SOURCE.json").read_text(encoding="utf-8"))
    needed = [row for row in identity["original"]["files"] if row["path"].startswith("tools/")]
    needed += identity["profiles"]["files"]
    with tempfile.TemporaryDirectory(prefix="scamera-film-") as temporary:
        temporary = Path(temporary)
        source = args.source.resolve() if args.source else temporary / "source"
        for row in needed:
            path = source / row["path"]
            if args.download:
                path.parent.mkdir(parents=True, exist_ok=True)
                url = f'https://raw.githubusercontent.com/chaert-s/spektrafilm-ofx/{identity["commit"]}/{row["path"]}'
                with urllib.request.urlopen(url, timeout=60) as response:
                    path.write_bytes(response.read())
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest != row["sha256"]:
                raise ValueError(f"Pinned film source differs: {row['path']}")
        output = temporary / "generated"
        output.mkdir()
        names = ("SpektraGeneratedProfileCurves.cpp", "SpektraGeneratedProfileCounts.h",
                 "SpektraHanatos2025Spectra.f32", "SpektraOutputGamutCompression.f32")
        env = dict(os.environ, SPEKTRAFILM_DATA_DIR=str(source / "Resources/data"),
                   SPEKTRAFILM_PROFILE_DIR=str(source / "Resources/data/profiles"))
        subprocess.run([args.python, str(source / "tools/generate_profile_curves.py"),
                        "--output", str(output / names[0]), "--counts-output", str(output / names[1]),
                        "--hanatos-output", str(output / names[2]),
                        "--output-gamut-compression-output", str(output / names[3])], env=env, check=True)
        for index, name in enumerate(names):
            destination = target / name if index < 2 else root / "app/src/main/assets/film-reference" / name
            if args.check:
                if (output / name).read_bytes() != destination.read_bytes():
                    raise ValueError(f"Generated film table differs: {name}")
            else:
                shutil.copyfile(output / name, destination)
            print(name, hashlib.sha256(destination.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
