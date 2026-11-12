import argparse
from hashlib import sha256
from itertools import chain
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

import numpy as np

def render_input(x, y, variant):
    light = (x*183+y*261+97*variant) % 30000
    red = (x*43+y*71+813*variant) % 24000
    blue = (x*137+y*27+194*variant) % 30000
    cr, cb = light-red, light-blue
    rgb = np.stack(((red+4)//8, (32768*light+9743*cr+3308*cb+147456)//262144, (blue+4)//8))
    rgb = np.clip(rgb, 0, 4095).astype(np.int64)
    matrix = np.rint(np.float32(65536) * np.array(
        [[1.1, -.09, .05], [-.04, 1.2, -.06], [.12, -.14, 1.09]], dtype=np.float32)).astype(np.int64)
    linear = np.einsum("rc,cyx->ryx", matrix, rgb)
    return np.clip((linear+32768)//65536, 0, 4095).astype("<i2").tobytes()


def cases():
    for version in ("27", "31"):
        yield version, "curves", 64, 64, 0, b""
        for w, h in ((128, 128), (160, 96), (64, 32)):
            y, x = np.indices((h, w), dtype=np.int32)
            ramp = (x * 255) // (w - 1)
            edge = np.where((x < w // 2)[..., None], (64, 88, 96), (248, 225, 192))
            color = np.stack(((ramp + 5*y) % 256, ((x ^ y)*17) % 256,
                              ((11*x + 3*y) ^ (7*y)) % 256), axis=-1)
            for variant, bright in enumerate((np.stack((ramp,) * 3, axis=-1), edge, color)):
                bright = bright.astype(np.uint8)
                if variant == 2:
                    bright[0, 0] = 255
                    bright[-1, -1] = (255, 0, 0)
                    bright[h // 2, w // 2] = (0, 255, 0)
                yield version, "rgb-luts-hdr", w, h, variant + 1, bright.tobytes()

        w, h = 160, 96
        y, x = np.indices((h, w), dtype=np.int32)
        ramp = (x * 255) // (w - 1)
        color = np.stack(((ramp + 5*y) % 256, ((x ^ y)*17) % 256,
                          ((11*x + 3*y) ^ (7*y)) % 256), axis=-1).astype(np.uint8)
        for mode in ("rgb-luts-hdr", "rgb-mat-hdr", "yuv-luts-hdr", "yuv-mat-hdr"):
            src = color.transpose(2, 0, 1) if mode.startswith("yuv") else color
            yield version, mode, w, h, 0, src.tobytes()

        w, h = 79, 63
        y, x = np.indices((h, w), dtype=np.int32)
        yuv = np.stack(((x * 11 + y * 5) & 255, (x * 2 + y * 9 + 11) & 255,
                        (x * 19 + y * 2 + 32) & 255)).astype(np.uint8)
        for variant in range(8):
            yield version, "retone", w, h, variant, yuv.tobytes()
        w = h = 64
        y, x = np.indices((h, w), dtype=np.int32)
        raw = np.stack(((x*73+y*43) % 4511, ((x^y)*103+15*y) % 5000,
                        ((73*x+101*y)^x) % 4900)).astype("<i2")
        for variant in range(3 if version == "27" else 1):
            yield version, "raw-rgb", w, h, variant, raw.tobytes()
        if version == "31":
            for variant, dim in enumerate((64, 64, 128, 128)):
                ty, tx = np.indices((dim, dim), dtype=np.int32)
                rgb = np.stack([(tx*27+ty*41+c*97+variant*91) % 4096 for c in range(3)])
                if variant != 1:
                    rgb[:, ::4, ::4] = np.maximum(270, rgb[:, ::4, ::4])
                if variant != 0:
                    rgb[:, 0, 0] = (0, 4, 7)
                if variant >= 2:
                    rgb[:, 0, 4] = (10, 4, 17)
                    rgb[:, 0, 8] = (100, 40, 170) if variant == 2 else (140, 80, 230)
                yield version, "raw-toe", dim, dim, variant, rgb.astype("<u2").tobytes()
            for variant in range(4):
                yield version, "raw-render", w, h, variant, render_input(x, y, variant)
                if variant == 3:
                    light, red, blue = (x+y) % 8, (x^y) % 8, (x*y) % 8
                else:
                    light = (x*47+y*83) % 4096
                    red = (x*173+y*19+variant*49) % 4096
                    blue = (x*11+y*203+variant*431) % 4096
                opponent = np.stack((light, light-red, light-blue)).astype("<i2")
                yield version, "raw-pack", w, h, variant, opponent.tobytes()
            for w, h in ((64, 64), (128, 96), (95, 63), (416, 320), (1024, 96)):
                y, x = np.indices((h, w), dtype=np.int32)
                ramp = x * 4095 // (w - 1)
                if w in (64, 128):
                    raw = np.stack((ramp * 2 - 300, ((x * 31 + y * 53) % 4800) - 300,
                                    ((x ^ y) * 83) % 5000)).astype("<i2")
                    variants = range(5)
                else:
                    delta = ((x ^ y) * 83 % 5000) // 6 + y * 1200 // h - 25
                    raw = np.stack((delta + ramp * 3 // 4,
                                    delta + ramp * 3 // 4 - ((x + y) & 127),
                                    delta + ramp * 3 // 4 + ((x * 3 + y) & 255))).astype("<i2")
                    variants = (3,)
                for mode in ("raw8", "raw10"):
                    for variant in variants:
                        yield version, mode, w, h, variant, raw.tobytes()


def full_frame_cases():
    w, h = 2304, 1792
    x = np.arange(w, dtype=np.int32)[None, :]
    y = np.arange(h, dtype=np.int32)[:, None]
    light = (x * 310 // (w - 1) + y * 40 // (h - 1)
             + 90 * ((x > w // 3) & (y < h // 4))
             - 100 * ((x < w // 2) & (y > h // 2)))
    detail = (x ^ y) & 15
    rgb = np.stack((light + 2 * detail, light - 20 + detail, light + 24 - detail), axis=-1)
    bright = np.clip(rgb, 0, 255).astype(np.uint8)
    for version in ("27", "31"):
        yield version, "rgb-luts-hdr", w, h, 0, bright.tobytes()


def full_raw_cases():
    w, h = 2304, 1792
    x = np.arange(w, dtype=np.int32)[None, :]
    y = np.arange(h, dtype=np.int32)[:, None]
    light = x*4300 // w + y*370 // h - 100
    detail = ((x ^ y)*83 + 77*y) % 211
    raw = np.stack((light + 2*detail, light - 250 + detail,
                    light + 270 - detail)).astype("<i2").tobytes()
    for mode in ("raw8", "raw10"):
        yield "31", mode, w, h, 3, raw


def case_key(version, mode, w, h, variant):
    return f"{version}/{mode}/{w}x{h}/{variant}"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("runner", type=Path)
    parser.add_argument("--profile", choices=("2.7", "3.1"))
    parser.add_argument("--full-frame", action="store_true")
    parser.add_argument("--prefix", action="append", default=[])
    args = parser.parse_args()
    expected = json.loads(Path(__file__).with_name("expected.json").read_text())
    if args.full_frame:
        expected.update(json.loads(Path(__file__).with_name("expected_full_frame.json").read_text()))
    samples = chain(cases(), full_frame_cases(), full_raw_cases()) if args.full_frame else cases()
    profile = args.profile.replace(".", "") if args.profile else None
    wanted = {key for key in expected if profile is None or key.split("/", 1)[0] == profile}
    verified = set()
    failures = []
    with TemporaryDirectory() as directory:
        src, dst = (Path(directory) / name for name in ("input", "output"))
        for version, mode, w, h, variant, pixels in samples:
            if profile is not None and profile != version:
                continue
            key = case_key(version, mode, w, h, variant)
            src.write_bytes(pixels)
            subprocess.run((*args.prefix, str(args.runner.resolve()), version, mode,
                            str(w), str(h), str(variant), str(src), str(dst)), check=True)
            digest = sha256(dst.read_bytes()).hexdigest()
            if digest != expected[key]:
                failures.append(key)
                print(f"{key}: got {digest}, expected {expected[key]}", flush=True)
            verified.add(key)
    if verified != wanted:
        raise RuntimeError("Incomplete test matrix")
    print(f"{len(verified) - len(failures)}/{len(verified)} byte-for-byte cases passed")
    return bool(failures)


if __name__ == "__main__":
    raise SystemExit(main())
