"""P522: an ArkCam burst (.7z or its unpacked folder: frame_NN.raw / short_NN.raw RAW10 MIPI, session.bin 'ARKL' v1) -> SCAMERA
NCH v12 for the merge worker or the Lab (Download/SCAMERA, opened with a template shot of the same module), plus <out>.ark.json
(white balance, colour matrix, lens shading map, timestamps) for tools/ark_compare.py.

  python3 tools/ark_burst_to_nch.py burst_ArkCam_....7z out.nch [--max-normals N] [--no-shorts]

Frame 0 = the newest normal (the one closest to the shorts), then the other normals newest first (role 1, exposure 1), then the
shorts (role 5 ultrashort). The noise model is measured on the first two frames (Ark's session values for its Quad lenses read
10 - 50x below the frames' noise: the merge took them for clean and kept a narrow kernel); --ark-noise keeps Ark's. A short frame's exposure is measured against the base (the median ratio of their unclipped mid-tone
2x2 cells): the record's 'ratio' (8) is Ark's highlight recovery factor, not the exposure (its shorts read ~0.5). Ark's sensor type 1 (Quad) is colour block 2 in the header. A .7z needs py7zr.

session.bin (little endian): int32 'ARKL', 1, w, h, row stride, CFA (Android order), sensor type, RAW format; float white, noise
S, noise O; int32 ISO at 48; float black[4] at 60, CCM[9] at 76; lens id (16 B) at 130; float highlight factor at 146; uint32
float count at 150, frames at 154, shorts at 158; floats map w, h at 162 and the lens shading map (h x w x 4) at 170; then a
77 B record per frame: int64 timestamp, exposure (ns), float WB[4], CCM[9], exposure ratio, a short flag byte, 4 B."""
import argparse, json, os, struct, sys, tempfile, shutil, glob
import numpy as np

REC = 77


def read_session(path):
    b = open(path, 'rb').read()
    if b[:4] != b'ARKL':
        sys.exit('not an ARKL session.bin')
    i = struct.unpack_from('<8i', b, 0)
    w, h, stride, cfa, stype = i[2], i[3], i[4], i[5], i[6]
    white, ns, no = struct.unpack_from('<3f', b, 32)
    iso = struct.unpack_from('<i', b, 48)[0]
    black = struct.unpack_from('<4f', b, 60)
    ccm = struct.unpack_from('<9f', b, 76)
    lens = b[130:146].split(b'\0')[0].decode('ascii', 'replace')
    hl = struct.unpack_from('<f', b, 146)[0]
    nfl = struct.unpack_from('<I', b, 150)[0]
    frames, shorts = struct.unpack_from('<2I', b, 154)
    lw, lh = struct.unpack_from('<2f', b, 162)
    lw, lh = int(lw), int(lh)
    lsc = np.frombuffer(b, '<f4', lw * lh * 4, 170).reshape(lh, lw, 4)
    off = 162 + 4 * nfl
    recs = []
    for k in range(frames + shorts):
        o = off + k * REC
        ts, ens = struct.unpack_from('<2q', b, o)
        wb = struct.unpack_from('<4f', b, o + 16)
        fccm = struct.unpack_from('<9f', b, o + 32)
        ratio = struct.unpack_from('<f', b, o + 68)[0]
        recs.append(dict(ts=ts, exposure_ns=ens, wb=wb, ccm=fccm, ratio=ratio, short=b[o + 72] == 1))
    if off + (frames + shorts) * REC != len(b):
        print('warning: session.bin is %d B, records end at %d' % (len(b), off + (frames + shorts) * REC))
    return dict(w=w, h=h, stride=stride, cfa=cfa, block={0: 1, 1: 2, 2: 4}.get(stype, 0), white=white, noise_s=ns, noise_o=no, iso=iso, black=black, ccm=ccm,
                lens=lens, hl=hl, frames=frames, shorts=shorts, lsc=lsc, recs=recs)


def unpack_raw10(path, w, h, stride):
    a = np.fromfile(path, np.uint8)  # RAW10 MIPI: 4 high bytes, then their 2 low bits each
    if a.size < stride * h:
        sys.exit('%s: %d B < %d' % (path, a.size, stride * h))
    a = a[:stride * h].reshape(h, stride)[:, :w * 5 // 4].reshape(h, w // 4, 5).astype(np.uint16)
    lo = a[..., 4]
    out = np.empty((h, w // 4, 4), np.uint16)
    for k in range(4):
        out[..., k] = (a[..., k] << 2) | ((lo >> (2 * k)) & 3)
    return out.reshape(h, w)


def short_ratio(base, short, black, white):
    """The short frame's exposure over the base's: median of their 2x2-cell means where the base is unclipped mid-tone."""
    bl = float(np.mean(black))
    b = (base[:base.shape[0] // 2 * 2, :base.shape[1] // 2 * 2].reshape(base.shape[0] // 2, 2, -1, 2).mean((1, 3)) - bl) / (white - bl)
    t = (short[:b.shape[0] * 2, :b.shape[1] * 2].reshape(b.shape[0], 2, -1, 2).mean((1, 3)) - bl) / (white - bl)
    m = (b > 0.15) & (b < 0.7) & (t > 0.01)
    if m.sum() < 1000:
        return 0.125
    return float(np.clip(np.median(t[m] / b[m]), 1.0 / 512, 1.0))


def measure_noise(f0, f1, black, white, b):
    """The sensor noise model (variance = S v + O, v and the variance in units of white - black) of two consecutive frames:
    their same sites' difference on flat b x b colour blocks of the first (hand-held: a shift of ~1 px moves little there),
    var(f0 - f1) / 2 per level bin, a least-squares line. None when too few flat blocks."""
    bl = float(np.mean(black)); sc = white - bl
    a = (f0 - bl) / sc; d = (f0 - f1) / sc
    h, w = a.shape; B = 4 * b
    a = a[:h // B * B, :w // B * B]; d = d[:h // B * B, :w // B * B]
    ta = a.reshape(h // B, B, w // B, B); td = d.reshape(h // B, B, w // B, B)
    lvl = ta.mean((1, 3)); spread = ta.std((1, 3)); dv = td.var((1, 3)) / 2
    ok = (lvl > 0.01) & (lvl < 0.85) & (spread < 0.03 + 0.08 * lvl)
    if ok.sum() < 200:
        return None
    L, V = lvl[ok], dv[ok]
    # per level bin, the lower half of the blocks (texture and motion only add)
    edges = np.quantile(L, np.linspace(0, 1, 13)); xs, ys = [], []
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (L >= lo) & (L <= hi)
        if m.sum() >= 10:
            xs.append(np.median(L[m])); ys.append(np.percentile(V[m], 30))
    if len(xs) < 4:
        return None
    S, O = np.polyfit(xs, ys, 1)
    return float(max(S, 1e-7)), float(max(O, 1e-9))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src')
    ap.add_argument('out')
    ap.add_argument('--max-normals', type=int, default=0, help='keep only the N newest normals (0: all)')
    ap.add_argument('--no-shorts', action='store_true')
    ap.add_argument('--ark-noise', action='store_true', help="the session's noise model (Ark's Quad ones read 10 - 50x low)")
    a = ap.parse_args()
    tmp = None
    src = a.src
    if os.path.isfile(src):
        import py7zr
        tmp = tempfile.mkdtemp(prefix='ark_', dir=os.path.dirname(os.path.abspath(a.out)))
        with py7zr.SevenZipFile(src) as z:
            z.extractall(tmp)
        src = tmp
    try:
        hits = glob.glob(os.path.join(src, '**', 'session.bin'), recursive=True)
        if not hits:
            sys.exit('no session.bin')
        d = os.path.dirname(hits[0])
        s = read_session(hits[0])
        w, h = s['w'], s['h']
        normals = [k for k, r in enumerate(s['recs']) if not r['short']]
        shorts = [k for k, r in enumerate(s['recs']) if r['short']]
        normals.sort(key=lambda k: -s['recs'][k]['ts'])          # newest first
        if a.max_normals:
            normals = normals[:a.max_normals]
        if a.no_shorts:
            shorts = []
        order = normals + shorts
        files = {}
        for k in normals:
            files[k] = os.path.join(d, 'frame_%02d.raw' % k)
        for j, k in enumerate(sorted(shorts)):
            files[k] = os.path.join(d, 'short_%02d.raw' % j)
        n = len(order)
        t0 = s['recs'][order[0]]['ts']
        head = bytearray(128)
        struct.pack_into('<6If4fI', head, 0, 0x3143484e, 12, w, h, s['cfa'], n, s['white'], *s['black'], 4)  # flags: clip flags, as the app
        struct.pack_into('<3I', head, 48, 0, 1, s['block'])   # colour block: 1 Bayer, 2 Quad, 4 Tetra (Ark sensor type 0/1/2)
        first = (128 + 32 * n + 4095) // 4096
        pages = (w * h * 2 + 4095) // 4096
        with open(a.out, 'wb') as f:
            f.write(head)
            base = unpack_raw10(files[order[0]], w, h, s['stride']).astype(np.float32)
            noise = measure_noise(base, unpack_raw10(files[order[1]], w, h, s['stride']).astype(np.float32), s['black'],
                                  s['white'], s['block']) if len(order) > 1 and not a.ark_noise else None
            if noise:
                s['noise_ark'] = (s['noise_s'], s['noise_o']); s['noise_s'], s['noise_o'] = noise
            expos = {}
            for k in shorts:
                expos[k] = short_ratio(base, unpack_raw10(files[k], w, h, s['stride']).astype(np.float32), s['black'], s['white'])
            for i, k in enumerate(order):
                r = s['recs'][k]
                role, expo = (5, expos[k]) if r['short'] else (1, 1.0)
                f.write(struct.pack('<IfIfffII', role, expo, s['iso'], s['noise_s'], s['noise_o'],
                                    (r['ts'] - t0) / 1e6, 0, first + i * pages))
            for i, k in enumerate(order):
                f.seek((first + i * pages) * 4096)
                f.write(unpack_raw10(files[k], w, h, s['stride']).astype('<u2').tobytes())
            f.truncate((first + n * pages) * 4096)
        r0 = s['recs'][order[0]]
        meta = dict(lens=s['lens'], w=w, h=h, cfa=s['cfa'], block=s['block'], white=s['white'], black=list(s['black']), iso=s['iso'],
                    noise_s=s['noise_s'], noise_o=s['noise_o'], noise_ark=s.get('noise_ark'), highlight_factor=s['hl'], wb=list(r0['wb']), ccm=list(r0['ccm']),
                    exposure_ms=r0['exposure_ns'] / 1e6, lsc_size=[s['lsc'].shape[1], s['lsc'].shape[0]], lsc=s['lsc'].tolist(),
                    frames=[dict(src=os.path.basename(files[k]), role='short' if s['recs'][k]['short'] else 'normal',
                                 ratio=s['recs'][k]['ratio'], exposure=expos.get(k, 1.0),
                                 order_ms=(s['recs'][k]['ts'] - t0) / 1e6) for k in order])
        json.dump(meta, open(os.path.splitext(a.out)[0] + '.ark.json', 'w'))
        print('%s: %dx%d cfa %d block %d, %d normals + %d shorts (exposure %s), exposure %.3f ms, iso %d, noise S %.3g O %.3g%s -> %s' %
              (s['lens'], w, h, s['cfa'], s['block'], len(normals), len(shorts), ', '.join('%.3f' % expos[k] for k in shorts) or '-',
               r0['exposure_ns'] / 1e6, s['iso'], s['noise_s'], s['noise_o'],
               ' (measured; Ark %.3g %.3g)' % s['noise_ark'] if s.get('noise_ark') else " (Ark's)", a.out))
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    main()
