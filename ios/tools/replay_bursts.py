#!/usr/bin/env python3
"""Merge replay of NCH bursts with tools/hybrid_replay (ios/tools/hybrid_replay.cpp) and the comparison of two runs.

  replay_bursts.py run <hybrid_replay binary> <bursts dir (*.nch, *.nch.xz)> <out dir> [extra tuning lines file]
  replay_bursts.py compare <reference dir> <candidate dir> [--max-psnr-drop dB] [--max-off-share fraction]

run: the base tuning is tools/replay_tuning.txt (as tools/replay_hybrid_ab.py); writes <out>/<burst>.f32 and .log.
compare: per burst max / mean absolute difference and PSNR of the candidate against the reference, both normalised by the
reference's 99.9th percentile; exits 1 when a burst is missing or its PSNR is below the limit (default 40 dB).
"""
import lzma
import math
import struct
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]


def unpack(path):
    data = path.read_bytes()
    if path.suffix == '.xz':
        d = lzma.decompress(data)
        n = struct.unpack('<I', d[20:24])[0]
        first = (128 + 32 * n + 4095) // 4096
        planes = np.frombuffer(d[first * 4096:], np.uint8)
        half = planes.size // 2
        body = planes[:half].astype(np.uint16) | (planes[half:].astype(np.uint16) << 8)
        data = d[:first * 4096] + body.astype('<u2').tobytes()
    return data


def read_result(path):
    raw = path.read_bytes()
    magic, w, h, c = struct.unpack('<4I', raw[:16])
    if magic != 0x47524353:
        raise ValueError(f'{path}: not a hybrid_replay result')
    return np.frombuffer(raw[16:16 + w * h * c * 4], np.float32).reshape(h, w, c)


def run(binary, bursts, out, extra):
    out.mkdir(parents=True, exist_ok=True)
    tuning = (ROOT / 'tools/replay_tuning.txt').read_text().rstrip('\n') + '\n'
    if extra:
        tuning += Path(extra).read_text()
    failed = 0
    for path in sorted(list(bursts.glob('*.nch')) + list(bursts.glob('*.nch.xz'))):
        name = path.name.split('.')[0]
        packed = path.suffix == '.xz'
        nch = out / (name + '.nch') if packed else path
        if packed:
            nch.write_bytes(unpack(path))
        job = out / ('job_' + name)
        job.mkdir(exist_ok=True)
        (job / 'hybrid_tuning.txt').write_text(tuning)
        started = time.time()
        p = subprocess.run([str(binary), str(job), str(nch), str(out / (name + '.f32'))], capture_output=True, text=True,
                           errors='replace', timeout=1800)
        (out / (name + '.log')).write_text(p.stdout + '\n--- stderr\n' + p.stderr)
        if packed:
            nch.unlink()
        ok = p.returncode == 0
        failed += not ok
        print(f'{name}: rc={p.returncode} {time.time() - started:.1f}s', p.stderr.strip().splitlines()[-1:] if not ok else '')
    return 1 if failed else 0


def compare(ref_dir, cand_dir, limit, off_share=1.0):
    worst = 0
    lines = []
    for ref_path in sorted(ref_dir.glob('*.f32')):
        cand_path = cand_dir / ref_path.name
        if not cand_path.exists():
            lines.append(f'{ref_path.stem}: MISSING in the candidate')
            worst = 1
            continue
        ref, cand = read_result(ref_path), read_result(cand_path)
        if ref.shape != cand.shape:
            lines.append(f'{ref_path.stem}: shape {cand.shape} vs reference {ref.shape}')
            worst = 1
            continue
        finite = np.isfinite(cand).all()
        scale = float(np.percentile(ref, 99.9)) or 1.0
        diff = np.abs(cand.astype(np.float64) - ref) / scale
        mse = float(np.mean(diff ** 2))
        psnr = 99.0 if mse == 0 else 10 * math.log10(1.0 / mse)
        off = float(np.mean(diff > 0.01))  # values more than 1 % of the scale away (a branch the GPUs take differently)
        bad = not finite or psnr < limit or off > off_share
        worst |= bad
        lines.append(f'{ref_path.stem}: {ref.shape[1]}x{ref.shape[0]} PSNR {psnr:.2f} dB max {diff.max():.5f} mean {diff.mean():.6f}'
                     f' p99.9 {np.percentile(diff, 99.9):.5f} off>1% {off:.6f} finite={finite}' + ('  <-- BELOW LIMIT' if bad else ''))
    print('\n'.join(lines) or 'no reference results')
    return 1 if worst or not lines else 0


if __name__ == '__main__':
    if len(sys.argv) >= 5 and sys.argv[1] == 'run':
        sys.exit(run(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), sys.argv[5] if len(sys.argv) > 5 else None))
    if len(sys.argv) >= 4 and sys.argv[1] == 'compare':
        limit = float(sys.argv[sys.argv.index('--max-psnr-drop') + 1]) if '--max-psnr-drop' in sys.argv else 40.0
        share = float(sys.argv[sys.argv.index('--max-off-share') + 1]) if '--max-off-share' in sys.argv else 1.0
        sys.exit(compare(Path(sys.argv[2]), Path(sys.argv[3]), limit, share))
    print(__doc__)
    sys.exit(2)
