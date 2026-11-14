#!/usr/bin/env python3
"""A DNG with the tags of a lab burst's Parameters (meta.json "params"): the colour tags, AsShotNeutral, NoiseProfile, the lens
shading as four OpcodeList2 GainMaps (DngCreator's layout), levels, CFA, orientation and EXIF, over a tiny raw image - the input
of shot_params_check --dng (the DNG reader of the iOS app against the Parameters Android built from the camera)."""
import json
import math
import struct
import sys


def rational(v, signed):
    if v == 0:
        return 0, 1
    m, e = math.frexp(abs(v))           # v = m * 2^e, m in [0.5, 1)
    k = 24 - e                          # num = v * 2^k has 24 significant bits
    num = int(round(abs(v) * 2.0 ** k))
    while k > 0 and num % 2 == 0:
        num //= 2
        k -= 1
    while k > 31:
        num //= 2
        k -= 1
    while k < 0:
        num *= 2
        k += 1
    return (-num if v < 0 else num), 2 ** k


def main(meta_path, out_path):
    p = json.load(open(meta_path))['params']
    entries = {0: [], 1: [], 2: []}     # IFD 0, raw IFD, EXIF IFD
    blobs = []

    def tag(ifd, t, typ, values):
        entries[ifd].append((t, typ, values))

    cm = lambda k: p.get(k)
    for t, k in ((0xC621, 'ColorMatrix1'), (0xC622, 'ColorMatrix2'), (0xC623, 'calibrationTransform1'),
                 (0xC624, 'calibrationTransform2'), (0xC714, 'ForwardTransform1'), (0xC715, 'ForwardTransform2')):
        if cm(k):
            tag(0, t, 10, [rational(v, True) for v in cm(k)])
    tag(0, 0xC628, 5, [rational(v, False) for v in p['whitePoint']])
    tag(0, 0xC65A, 3, [p['calibrationIlluminant1']])
    tag(0, 0xC65B, 3, [p['calibrationIlluminant2']])
    tag(0, 0x0112, 3, [{0: 1, 90: 6, 180: 3, 270: 8}[p['cameraRotation']]])
    nm = p['noiseModeler']['base']
    tag(0, 0xC761, 12, [x for pair in nm for x in pair])
    tag(0, 0x00FE, 4, [1])
    w, h = 16, 16
    tag(1, 0x00FE, 4, [0])
    tag(1, 0x0100, 4, [w])
    tag(1, 0x0101, 4, [h])
    tag(1, 0x0102, 3, [16])
    tag(1, 0x0103, 3, [1])
    tag(1, 0x0106, 3, [32803])
    tag(1, 0x0115, 3, [1])
    cfa = ['RGGB', 'GRBG', 'GBRG', 'BGGR'][p['cfaPattern']]
    tag(1, 0x828D, 3, [2, 2])
    tag(1, 0x828E, 1, [{'R': 0, 'G': 1, 'B': 2}[c] for c in cfa])
    tag(1, 0xC61A, 4, [int(b) for b in p['blackLevel']])
    tag(1, 0xC61D, 4, [p['whiteLevel']])
    mw, mh = p['mapSize']['x'], p['mapSize']['y']
    gm = p['gainMap']
    ops = b''
    for site in range(4):
        top, left = site // 2, site % 2
        c = cfa[site]
        ch = 0 if c == 'R' else 3 if c == 'B' else (1 if top == 0 else 2)
        body = struct.pack('>10i', top, left, 2 * (2 * h // 2), 2 * (2 * w // 2), 0, 1, 2, 2, mh, mw)
        body += struct.pack('>4d', 1.0 / max(1, mh - 1), 1.0 / max(1, mw - 1), 0.0, 0.0) + struct.pack('>i', 1)
        body += b''.join(struct.pack('>f', gm[(i * 4) + ch]) for i in range(mw * mh))
        ops += struct.pack('>4I', 9, 0x01030000, 0, len(body)) + body
    tag(1, 0xC740, 7, list(struct.pack('>I', 4) + ops))
    tag(2, 0x8827, 3, [p['iso']])
    tag(2, 0x829A, 5, [rational(p['exposureTime'], False)])
    tag(2, 0x829D, 5, [rational(p['aperture'], False)])
    tag(2, 0x920A, 5, [rational(p['focalLength'], False)])

    sizes = {1: 1, 3: 2, 4: 4, 5: 8, 7: 1, 10: 8, 12: 8}
    out = bytearray(b'II*\x00' + struct.pack('<I', 0))
    raw = bytes(w * h * 2)

    def pack(typ, v):
        if typ in (5, 10):
            return struct.pack('<ii' if typ == 10 else '<II', *v)
        return struct.pack({1: '<B', 3: '<H', 4: '<I', 7: '<B', 12: '<d'}[typ], v)

    def write_ifd(ifd, extra):
        items = sorted(entries[ifd] + extra)
        at = len(out)
        size = 2 + 12 * len(items) + 4
        data_at = at + size
        table, data = bytearray(struct.pack('<H', len(items))), bytearray()
        for t, typ, values in items:
            payload = b''.join(pack(typ, v) for v in values)
            if len(payload) <= 4:
                field = payload.ljust(4, b'\x00')
            else:
                if (data_at + len(data)) % 2:
                    data += b'\x00'
                field = struct.pack('<I', data_at + len(data))
                data += payload
            table += struct.pack('<HHI', t, typ, len(values)) + field
        table += struct.pack('<I', 0)
        out.extend(table + data)
        return at

    strip_at = len(out)
    out.extend(raw)
    raw_ifd = write_ifd(1, [(0x0111, 4, [strip_at]), (0x0116, 4, [h]), (0x0117, 4, [len(raw)])])
    exif_ifd = write_ifd(2, [])
    ifd0 = write_ifd(0, [(0x014A, 4, [raw_ifd]), (0x8769, 4, [exif_ifd])])
    struct.pack_into('<I', out, 4, ifd0)
    open(out_path, 'wb').write(out)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
