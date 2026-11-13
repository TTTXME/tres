#!/usr/bin/env python3
"""Test inputs of the photo editor check (edit_check.cpp against HostEditRender): a crop of an RGBA8 picture (16-byte header
0x38524353, w, h, 4) and two DNGs mosaicked from it - a.dng: uncompressed 16-bit RGGB, orientation 6, ForwardMatrix1 / 2,
DefaultCrop, four OpcodeList2 GainMaps; b.dng: lossless JPEG (compression 7, two components, predictor 1), BGGR, orientation 1,
ColorMatrix only, BlackLevelRepeatDim 2x2.
  make_edit_dng.py <picture.rgba> <out dir> <width> <height>"""
import os
import struct
import sys


def read_rgba(path):
    b = open(path, 'rb').read()
    magic, w, h, c = struct.unpack('<4I', b[:16])
    assert magic == 0x38524353 and c == 4
    return w, h, b[16:]


def crop(w, h, px, cw, ch):
    x0, y0 = (w - cw) // 2, (h - ch) // 2
    rows = [px[((y0 + y) * w + x0) * 4:((y0 + y) * w + x0 + cw) * 4] for y in range(ch)]
    return b''.join(rows)


def lin(v):
    e = v / 255.0
    return e / 12.92 if e <= 0.04045 else ((e + 0.055) / 1.055) ** 2.4


LUT = [lin(i) for i in range(256)]


def mosaic(cw, ch, px, pattern, black, white, gains):
    """pattern: colour at (0,0) (1,0) (0,1) (1,1); camera RGB = linear sRGB x gains (a fake camera), 0.6 of the range."""
    out = []
    span = white - black
    for y in range(ch):
        for x in range(cw):
            c = pattern[(x & 1) + 2 * (y & 1)]
            v = LUT[px[(y * cw + x) * 4 + c]] * gains[c]
            out.append(max(0, min(white, int(round(black + v * 0.6 * span)))))
    return out


def rational(v):
    if v == 0:
        return 0, 1
    d = 1000000
    return int(round(v * d)), d


class Tiff:
    def __init__(self):
        self.entries = []

    def tag(self, t, typ, values):
        self.entries.append((t, typ, values))

    def build(self, data_blobs):
        """data_blobs: name -> bytes appended after the IFD; a value ('@name', k) is the offset of blob name + k."""
        self.entries.sort(key=lambda e: e[0])
        n = len(self.entries)
        ifd_off = 8
        ext_off = ifd_off + 2 + 12 * n + 4
        ext = bytearray()
        sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8, 11: 4, 12: 8}
        blob_at = {}
        # value areas first, then the blobs
        packed = []
        for t, typ, values in self.entries:
            packed.append((t, typ, values))
        value_bytes = []
        for t, typ, values in packed:
            value_bytes.append(None)
        # lay out the blobs after the extended values: two passes
        def pack_values(typ, values, resolve):
            out = bytearray()
            for v in values:
                if isinstance(v, tuple) and isinstance(v[0], str):
                    v = resolve(v)
                if typ == 1 or typ == 7:
                    out += struct.pack('<B', v)
                elif typ == 2:
                    out += v
                elif typ == 3:
                    out += struct.pack('<H', v)
                elif typ == 4:
                    out += struct.pack('<I', v)
                elif typ == 5:
                    out += struct.pack('<II', *rational(v))
                elif typ == 10:
                    a, b = rational(v)
                    out += struct.pack('<ii', a, b)
                elif typ == 11:
                    out += struct.pack('<f', v)
                elif typ == 12:
                    out += struct.pack('<d', v)
            return bytes(out)

        def layout(resolve):
            ext = bytearray()
            body = bytearray()
            for t, typ, values in self.entries:
                vb = bytes(values) if typ in (2, 7) and isinstance(values, (bytes, bytearray)) else pack_values(typ, values, resolve)
                count = len(vb) // sizes[typ] if typ != 2 else len(vb)
                if len(vb) <= 4:
                    body += struct.pack('<HHI', t, typ, count) + vb.ljust(4, b'\0')
                else:
                    off = ext_off + len(ext)
                    ext += vb
                    if len(ext) & 1:
                        ext += b'\0'
                    body += struct.pack('<HHI', t, typ, count) + struct.pack('<I', off)
            return body, ext

        body, ext = layout(lambda v: 0)
        at = ext_off + len(ext)
        for name, blob in data_blobs:
            blob_at[name] = at
            at += len(blob)
        body, ext = layout(lambda v: blob_at[v[0]] + v[1])
        head = b'II' + struct.pack('<HI', 42, ifd_off)
        out = bytearray(head) + struct.pack('<H', n) + body + struct.pack('<I', 0) + ext
        for name, blob in data_blobs:
            out += blob
        return bytes(out)


def gain_maps(w, h):
    """Four GainMaps (DngCreator's layout: one per CFA site, pitch 2), 3 x 4 points, big-endian OpcodeList2."""
    ops = []
    for site in range(4):
        top, left = site // 2, site % 2
        pv, ph = 3, 4
        gains = [1.0 + 0.25 * (abs(i - 1) / 1.0) * (abs(j - 1.5) / 1.5) + 0.02 * site for i in range(pv) for j in range(ph)]
        params = struct.pack('>iiiiiiiiii', top, left, h, w, 0, 1, 2, 2, pv, ph)
        params += struct.pack('>dddd', 1.0 / (pv - 1), 1.0 / (ph - 1), 0.0, 0.0)
        params += struct.pack('>i', 1)
        params += b''.join(struct.pack('>f', g) for g in gains)
        ops.append(struct.pack('>IIII', 9, 0x01030000, 0, len(params)) + params)
    return struct.pack('>I', len(ops)) + b''.join(ops)


def lj92(samples, width, height, comps):
    """Lossless JPEG, predictor 1, one Huffman table: categories 0..16 coded with 5 bits."""
    out = bytearray(b'\xff\xd8')
    counts = [0] * 16
    counts[4] = 17   # 17 codes of length 5
    dht = bytes([0x00]) + bytes(counts) + bytes(range(17))
    out += b'\xff\xc4' + struct.pack('>H', 2 + len(dht)) + dht
    sof = struct.pack('>BHHB', 16, height, width, comps) + b''.join(bytes([i + 1, 0x11, 0]) for i in range(comps))
    out += b'\xff\xc3' + struct.pack('>H', 2 + len(sof)) + sof
    sos = bytes([comps]) + b''.join(bytes([i + 1, 0x00]) for i in range(comps)) + bytes([1, 0, 0])
    out += b'\xff\xda' + struct.pack('>H', 2 + len(sos)) + sos
    acc, nbits = 0, 0
    data = bytearray()

    def put(v, n):
        nonlocal acc, nbits
        acc = (acc << n) | (v & ((1 << n) - 1))
        nbits += n
        while nbits >= 8:
            b = (acc >> (nbits - 8)) & 0xFF
            data.append(b)
            if b == 0xFF:
                data.append(0)
            nbits -= 8
        acc &= (1 << nbits) - 1 if nbits else 0

    row = width * comps
    for y in range(height):
        for x in range(width):
            for c in range(comps):
                i = y * row + x * comps + c
                if x == 0 and y == 0:
                    pred = 1 << 15
                elif y == 0:
                    pred = samples[i - comps]
                elif x == 0:
                    pred = samples[(y - 1) * row + c]
                else:
                    pred = samples[i - comps]
                d = (samples[i] - pred) & 0xFFFF
                if d >= 32768:
                    d -= 65536
                s = 0 if d == 0 else abs(d).bit_length()
                put(s, 5)
                if s and s < 16:
                    put(d if d > 0 else d - 1, s)
    if nbits:
        put(0x7F, 8 - nbits)
    out += data + b'\xff\xd9'
    return bytes(out)


CM1 = [1.0234, -0.3471, -0.0912, -0.4012, 1.2251, 0.1501, -0.0423, 0.1412, 0.6015]
CM2 = [0.8211, -0.2101, -0.0811, -0.4512, 1.2841, 0.1811, -0.0711, 0.1601, 0.5822]
FM1 = [0.6812, 0.1934, 0.0897, 0.2601, 0.7812, -0.0413, 0.0201, -0.1512, 0.9563]
FM2 = [0.6512, 0.2412, 0.0719, 0.2311, 0.8601, -0.0912, 0.0102, -0.0911, 0.9061]


def dng(w, h, samples, pattern, orientation, forward, compression, black, white, blackdim, opcodes, crop_):
    t = Tiff()
    blobs = []
    if compression == 7:
        strip = lj92(samples, w // 2, h, 2)
    else:
        strip = b''.join(struct.pack('<H', v) for v in samples)
    blobs.append(('strip', strip))
    t.tag(0xFE, 4, [0])
    t.tag(0x100, 4, [w])
    t.tag(0x101, 4, [h])
    t.tag(0x102, 3, [16])
    t.tag(0x103, 3, [compression])
    t.tag(0x106, 3, [32803])
    t.tag(0x10F, 2, b'SCAM\0')
    t.tag(0x111, 4, [('strip', 0)])
    t.tag(0x112, 3, [orientation])
    t.tag(0x115, 3, [1])
    t.tag(0x116, 4, [h])
    t.tag(0x117, 4, [len(strip)])
    t.tag(0x11C, 3, [1])
    t.tag(0x828D, 3, [2, 2])
    t.tag(0x828E, 1, pattern)
    t.tag(0xC612, 1, [1, 4, 0, 0])
    if blackdim:
        t.tag(0xC619, 3, [2, 2])
        t.tag(0xC61A, 5, [black, black + 1, black - 1, black])
    else:
        t.tag(0xC61A, 4, [black])
    t.tag(0xC61D, 4, [white])
    if crop_:
        t.tag(0xC61F, 5, [8, 8])
        t.tag(0xC620, 5, [w - 16, h - 16])
    t.tag(0xC621, 10, CM1)
    t.tag(0xC622, 10, CM2)
    t.tag(0xC628, 5, [0.4812, 1.0, 0.6311])
    t.tag(0xC62A, 10, [0.25])
    t.tag(0xC65A, 3, [17])
    t.tag(0xC65B, 3, [21])
    if forward:
        t.tag(0xC714, 10, FM1)
        t.tag(0xC715, 10, FM2)
    if opcodes:
        t.tag(0xC740, 7, opcodes)
    return t.build(blobs)


def main():
    src, out, cw, ch = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    os.makedirs(out, exist_ok=True)
    w, h, px = read_rgba(src)
    pic = crop(w, h, px, cw, ch)
    open(os.path.join(out, 'picture.rgba'), 'wb').write(struct.pack('<4I', 0x38524353, cw, ch, 4) + pic)
    gains = [0.4812, 1.0, 0.6311]
    a = mosaic(cw, ch, pic, [0, 1, 1, 2], 64, 1023, gains)
    open(os.path.join(out, 'a.dng'), 'wb').write(dng(cw, ch, a, [0, 1, 1, 2], 6, True, 1, 64, 1023, False, gain_maps(cw, ch), True))
    b = mosaic(cw, ch, pic, [2, 1, 1, 0], 256, 4095, gains)
    open(os.path.join(out, 'b.dng'), 'wb').write(dng(cw, ch, b, [2, 1, 1, 0], 1, False, 7, 256, 4095, True, None, False))


if __name__ == '__main__':
    main()
