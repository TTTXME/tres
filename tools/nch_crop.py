"""P596: cut a window out of an NCH v12 burst (+ its .ark.json and Ark's JPEG) for quick merges of one region.
  python3 tools/nch_crop.py in.nch out.nch x0 y0 w h [ark.jpg]
The origin is rounded down to whole 8 px colour periods (Bayer / Quad / Tetra keep their phase); the lens shading map is
resampled over the window; out.ark.json gets "crop": [x0, y0, w, h, W, H]; the JPEG's window is saved beside as out.ark.png."""
import json, os, struct, sys
import numpy as np


def main():
    src, dst, x0, y0, w, h = sys.argv[1], sys.argv[2], *map(int, sys.argv[3:7])
    x0 -= x0 % 8; y0 -= y0 % 8; w -= w % 8; h -= h % 8
    with open(src, 'rb') as f:
        head = bytearray(f.read(128))
        magic, ver, W, H, cfa, n = struct.unpack_from('<6I', head, 0)
        if magic != 0x3143484e:
            sys.exit('not an NCH')
        recs = [bytearray(f.read(32)) for _ in range(n)]
        first = (128 + 32 * n + 4095) // 4096
        pages = (w * h * 2 + 4095) // 4096
        struct.pack_into('<2I', head, 8, w, h)
        with open(dst, 'wb') as o:
            o.write(head)
            for i, r in enumerate(recs):
                page = struct.unpack_from('<I', r, 28)[0]
                r2 = bytearray(r); struct.pack_into('<I', r2, 28, first + i * pages); o.write(r2)
                recs[i] = (page, r2)
            for i, (page, _) in enumerate(recs):
                a = np.memmap(src, '<u2', 'r', page * 4096, (H, W))[y0:y0 + h, x0:x0 + w]
                o.seek((first + i * pages) * 4096); o.write(np.ascontiguousarray(a).tobytes())
            o.truncate((first + n * pages) * 4096)
    js = os.path.splitext(src)[0] + '.ark.json'
    if os.path.exists(js):
        from scipy import ndimage as nd
        m = json.load(open(js))
        lsc = np.array(m['lsc'], np.float32); lh, lw = lsc.shape[:2]
        # the renderer samples the map at (y + 0.5) / H * (lh - 1): the window's own 17 x 13 nodes span its (0 .. h) the same way
        yy = (y0 + np.arange(lh) / (lh - 1) * h) / H * (lh - 1)
        xx = (x0 + np.arange(lw) / (lw - 1) * w) / W * (lw - 1)
        g = np.meshgrid(yy, xx, indexing='ij')
        m['lsc'] = np.stack([nd.map_coordinates(lsc[..., c], g, order=1, mode='nearest') for c in range(4)], -1).tolist()
        m['w'], m['h'], m['crop'] = w, h, [x0, y0, w, h, W, H]
        json.dump(m, open(os.path.splitext(dst)[0] + '.ark.json', 'w'))
    if len(sys.argv) > 7:
        from PIL import Image
        Image.open(sys.argv[7]).convert('RGB').crop((x0, y0, x0 + w, y0 + h)).save(os.path.splitext(dst)[0] + '.ark.png')
    print('%s: %dx%d at (%d, %d) of %dx%d, %d frames' % (dst, w, h, x0, y0, W, H, n))


if __name__ == '__main__':
    main()
