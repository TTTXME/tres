#!/usr/bin/env python3
"""P683: the film data stored losslessly smaller (APK and install size).

The codec ("delta + shuffle + deflate"): an array of N little-endian words of W bytes (W 4: float32 bits, 2: float16 bits) is
taken as unsigned integers, each replaced by its difference to the one before it modulo 2^(8W) (the first kept), the bytes
regrouped by significance (every word's byte 0, then every word's byte 1, ...) and the result deflated (zlib, level 9). Decoding
inflates, regroups the bytes back and sums the differences: exactly the bytes that went in (the integer differences wrap, no
float arithmetic). Measured: the Hanatos 2025 spectra 11.94 MB -> 2.49 MB (6.07 MB by zlib alone); the film / paper arrays
7.36 MB -> 4.40 MB.

Two users:
- tools/embed_film_reference.py stores SpektraHanatos2025Spectra.f32 this way in libscameraFilm (film-reference-resources.cpp decodeFilmResource decodes
  it at its first use, into the same bytes; the repo keeps the plain .f32);
- assets/film/*.bin are 'SFD2' files (FilmSpectral.parse; this tool writes them from the 'SFD1' files research/filmsim/export.py
  makes): 'SFD2', count u32, then per array: name len u8, name, dtype u8 (0 f32, 1 f16), ndim u8, dims u32..., codec u8 (0 the
  plain words, 1 this codec), the CRC-32 of the plain words u32, the stored length u32, the stored bytes.

usage:
  film_pack.py sfd2 <in.bin> <out.bin>      an SFD1 (or SFD2) file as SFD2
  film_pack.py check <assets/film dir> <sha256 manifest>   every SFD2 file decodes to the arrays of the manifest (the SFD1 arrays
                                                            before P683), bit for bit
  film_pack.py manifest <dir> <out>         the manifest of a directory's arrays (sha256 of each array's plain words)
"""
import array
import hashlib
import itertools
import struct
import sys
import zlib
from pathlib import Path

CODEC_PLAIN, CODEC_DELTA = 0, 1


def _words(data, width):
    code = {2: 'H', 4: 'I'}[width]
    a = array.array(code)
    if a.itemsize != width:
        a = array.array({2: 'H', 4: 'L'}[width])
    if a.itemsize != width:
        raise ValueError('no %d-byte array type' % width)
    a.frombytes(data)
    if sys.byteorder != 'little':
        a.byteswap()
    return a


def _bytes(a):
    if sys.byteorder != 'little':
        a = array.array(a.typecode, a)
        a.byteswap()
    return a.tobytes()


def encode(data, width):
    """The plain little-endian words -> the stored bytes of the codec (no numpy: CMake's Python runs this)."""
    if len(data) % width:
        raise ValueError('not whole words')
    a = _words(data, width)
    mask = (1 << (8 * width)) - 1
    d = array.array(a.typecode, [a[0]] if a else [])
    d.extend((a[i] - a[i - 1]) & mask for i in range(1, len(a)))
    raw = _bytes(d)
    shuffled = b''.join(raw[k::width] for k in range(width))
    return zlib.compress(shuffled, 9)


def decode(stored, width, words):
    """The stored bytes of the codec -> the plain little-endian words (as encode was given them)."""
    shuffled = zlib.decompress(stored)
    if len(shuffled) != words * width:
        raise ValueError('length %d, %d words of %d bytes expected' % (len(shuffled), words, width))
    raw = bytearray(words * width)
    for k in range(width):
        raw[k::width] = shuffled[k * words:(k + 1) * words]
    mask = (1 << (8 * width)) - 1
    d = _words(bytes(raw), width)
    return _bytes(array.array(d.typecode, itertools.accumulate(d, lambda x, y: (x + y) & mask)))


def read_sfd(path):
    """{name: (dtype, dims, plain bytes)} of an SFD1 or SFD2 file, in the file's order."""
    d = Path(path).read_bytes()
    magic = d[:4]
    if magic not in (b'SFD1', b'SFD2'):
        raise ValueError('%s: not SFD1 / SFD2' % path)
    n = struct.unpack_from('<I', d, 4)[0]
    o = 8
    out = {}
    for _ in range(n):
        ln = d[o]
        o += 1
        name = d[o:o + ln].decode('ascii')
        o += ln
        dt, nd = d[o], d[o + 1]
        o += 2
        dims = struct.unpack_from('<%dI' % nd, d, o)
        o += 4 * nd
        width = 2 if dt == 1 else 4
        words = 1
        for v in dims:
            words *= v
        if magic == b'SFD1':
            plain = d[o:o + words * width]
            o += words * width
        else:
            codec, crc, length = struct.unpack_from('<BII', d, o)
            o += 9
            stored = d[o:o + length]
            o += length
            plain = stored if codec == CODEC_PLAIN else decode(stored, width, words)
            if zlib.crc32(plain) != crc:
                raise ValueError('%s: %s CRC mismatch' % (path, name))
        if len(plain) != words * width:
            raise ValueError('%s: %s truncated' % (path, name))
        out[name] = (dt, dims, plain)
    if o != len(d):
        raise ValueError('%s: %d bytes after the arrays' % (path, len(d) - o))
    return out


def write_sfd2(path, arrays):
    body = bytearray(b'SFD2' + struct.pack('<I', len(arrays)))
    for name, (dt, dims, plain) in arrays.items():
        nb = name.encode('ascii')
        body += struct.pack('<B', len(nb)) + nb + struct.pack('<BB', dt, len(dims)) + struct.pack('<%dI' % len(dims), *dims)
        width = 2 if dt == 1 else 4
        packed = encode(plain, width)
        codec, stored = (CODEC_DELTA, packed) if len(packed) < len(plain) else (CODEC_PLAIN, plain)
        body += struct.pack('<BII', codec, zlib.crc32(plain), len(stored)) + stored
    Path(path).write_bytes(bytes(body))


def manifest(directory):
    rows = []
    for f in sorted(Path(directory).glob('*.bin')):
        for name, (dt, dims, plain) in read_sfd(f).items():
            rows.append('%s %s %d %s %s' % (f.name, name, dt, 'x'.join(map(str, dims)), hashlib.sha256(plain).hexdigest()))
    return '\n'.join(rows) + '\n'


def main(argv):
    if len(argv) == 3 and argv[0] == 'sfd2':
        arrays = read_sfd(argv[1])
        write_sfd2(argv[2], arrays)
        back = read_sfd(argv[2])
        if back != arrays:
            print('FAIL %s: the SFD2 file does not decode to the same arrays' % argv[2])
            return 1
        return 0
    if len(argv) == 3 and argv[0] == 'manifest':
        Path(argv[2]).write_text(manifest(argv[1]), encoding='utf-8')
        return 0
    if len(argv) == 3 and argv[0] == 'check':
        failures = 0
        files = sorted(Path(argv[1]).glob('*.bin'))
        for f in files:
            if f.read_bytes()[:4] != b'SFD2':
                print('FAIL %s: not SFD2 (tools/film_pack.py sfd2)' % f.name)
                failures += 1
        want = Path(argv[2]).read_text(encoding='utf-8')
        try:
            got = manifest(argv[1])
        except ValueError as e:
            print('FAIL %s' % e)
            return 1
        if got != want:
            a, b = set(want.splitlines()), set(got.splitlines())
            for row in sorted(a - b)[:10]:
                print('FAIL expected  ' + row)
            for row in sorted(b - a)[:10]:
                print('FAIL decoded   ' + row)
            failures += 1
        # the codec on edge cases: wrapping differences, one word, no word, NaN / Inf bits, both widths
        import random
        rng = random.Random(683)
        for width in (2, 4):
            for words in (0, 1, 2, 7, 1000):
                plain = bytes(rng.randrange(256) for _ in range(words * width))
                if decode(encode(plain, width), width, words) != plain:
                    print('FAIL codec width %d, %d words' % (width, words))
                    failures += 1
        print('     %d SFD2 film files, %d arrays decoded bit for bit as the manifest' % (len(files), len(want.splitlines())))
        print('ALL OK' if not failures else 'FAILED: %d' % failures)
        return 1 if failures else 0
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
