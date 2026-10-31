import argparse
import base64
import re
import zlib
from pathlib import Path


def transform(data):
    state = 0x2515606B4A7791CD
    mask = (1 << 64) - 1
    result = bytearray(data)
    for start in range(0, len(result), 8):
        state ^= state >> 12
        state ^= (state << 25) & mask
        state ^= state >> 27
        state = state * 0x2545F4914F6CDD1D & mask
        for k in range(min(8, len(result) - start)):
            result[start + k] ^= state >> (8 * k) & 255
    return bytes(result)


def decode(data, limit=16 * 1024 * 1024):
    if len(data) < 5 or data[:4] != b'HDRP' or data[4] != 2:
        raise ValueError('unsupported HDRP MakerNote')
    body = transform(data[5:])
    inflater = zlib.decompressobj(31)
    result = inflater.decompress(body, limit + 1)
    if len(result) > limit or not inflater.eof or inflater.unused_data:
        raise ValueError('oversized, incomplete or trailing gzip data')
    return result


def jpeg_payload(data):
    if not data.startswith(b'\xff\xd8'):
        raise ValueError('not a JPEG')
    chunks = {}
    ordinary = []
    p = 2
    prefix = b'http://ns.adobe.com/xmp/extension/\0'
    while p + 4 <= len(data):
        if data[p] != 255:
            raise ValueError('invalid JPEG marker')
        while p < len(data) and data[p] == 255:
            p += 1
        if p >= len(data):
            break
        marker = data[p]
        p += 1
        if marker in (0xD9, 0xDA):
            break
        if marker == 0x01 or 0xD0 <= marker <= 0xD7:
            continue
        size = int.from_bytes(data[p:p + 2], 'big')
        if size < 2 or p + size > len(data):
            raise ValueError('truncated JPEG segment')
        segment = data[p + 2:p + size]
        p += size
        if marker != 0xE1:
            continue
        if not segment.startswith(prefix):
            ordinary.append(segment)
            continue
        part = segment[len(prefix):]
        if len(part) < 40:
            raise ValueError('truncated extended XMP header')
        guid, total, offset = part[:32], int.from_bytes(part[32:36], 'big'), int.from_bytes(part[36:40], 'big')
        if total > 32 * 1024 * 1024 or offset + len(part[40:]) > total:
            raise ValueError('invalid extended XMP length')
        chunks.setdefault(guid, []).append((total, offset, part[40:]))
    for parts in chunks.values():
        parts.sort(key=lambda v: v[1])
        result = bytearray()
        total = parts[0][0]
        for length, offset, part in parts:
            if length != total or offset != len(result):
                raise ValueError('incomplete or overlapping extended XMP')
            result.extend(part)
        if len(result) != total:
            raise ValueError('incomplete extended XMP')
        ordinary.append(bytes(result))
    for xmp in ordinary:
        match = re.search(rb'GCamera:hdrp_makernote="([A-Za-z0-9+/=\s]+)"', xmp)
        if match:
            return base64.b64decode(re.sub(rb'\s', b'', match[1]), validate=True)
    raise ValueError('HDRP MakerNote missing')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    raw = args.input.read_bytes()
    args.output.write_bytes(decode(raw if raw.startswith(b'HDRP') else jpeg_payload(raw)))
