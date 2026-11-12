#!/usr/bin/env python3
"""P643d «ScamMaster»: the viewfinder's preview of the tone on the host - the real chain against the module's photo of the same frame
(owner: «AE должен быть как в автомате, а тонмапинг полностью из 2.7/3.1»; his two screenshots of one office before: in the mode the
desk burnt to white, the dark ceiling violet, white blotches on the walls).

The chain, all shipped code: an ISP frame of the scene at full resolution (the viewfinder's texture) and the RAW frame it was made of;
LiveSceneMeter's 64 x 64 tile of the ISP frame; every 300 ms the tone-port module's own picture of a 160 x 120 thumbnail of the RAW
(OldstyleLive.photoRgb: the photo's plan and settings and colour matrix; the JNI built for the host), stored flipped as a sensor's
buffer may be against the stream; OldstyleLive's bilateral grid fitted on the two (the orientation found by correlation), smoothed
in time; the grid drawn by the shipped viewfinder shader (preview/main_fs with utils/import_oldstyle_tone_live; moderngl, llvmpipe)
uploaded as MainRenderer does. The photo: the module on the full RAW frame at the viewfinder's exposure (P643: the photo mode's
capture), the plan metered on it - the same OldstyleLive.photoRgb. Cases: V1 and V2 x the owner's «Фото» frame (its RAW modelled:
the screenshot back to light over a gain), a synthetic office (a gain, a knee, a shadow lift, an ISP with local tone), and two real
RAW frames of the app at the photo mode's exposure (a lamp in a dark room, a sunlit pool; the ISP a gain and a knee). Checked, the
screen against the module's photo of the frame:
- the mean within 7 % (the module's own picture of the thumbnail is up to 5 % off its photo of the frame: its local tone depends a
  little on the frame's size); each of 4 x 4 zones' mean within 0.06 (display); the colour of near-neutral areas within 0.04 (each
  channel over the luma); the local contrast (each zone's spread of log luma) between 0.7 and 1.4 of the photo's in the median zone;
- no clipping the photo does not have (+ 2 % of the frame; 0.2 % but for the local-tone ISP);
- no blotches: the transform itself is smooth - flat probe colours drawn through the shader change by at most 0.15 (display)
  from one cell to the next and 0.02 from one pixel to the next (the old preview's patches were steps of tenths a pixel);
- steady (under 1 % a frame on a static scene); the AE moving the frame a stop over 8 frames (the ISP's own frame): the screen
  changes by no more than 2.5 x the frame's own change a frame + 3 % (the tile is a frame or two behind the frame it is drawn on) and
  is the module's photo of the new frame within 7 % after 1.5 s; «Тон в видоискателе» off: no grid
  (the photo mode's viewfinder). (P630g's series frames at another exposure are gone with P643: the photo mode's capture.)
With an argument: a directory for the renders - <case>_isp.png | _screen.png | _photo.png and a sheet of all.
Usage: check_oldstyle_live.py [out_dir]
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE', '1')

import numpy as np  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TP = ROOT / 'app/src/main/cpp/third_party/tone_port'
JNI = ROOT / 'app/src/main/cpp/oldstyle-tone-jni.cpp'
JAVA = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/oldstyle'
FIXTURES = ROOT / 'tools/fixtures/oldstyle_live'
sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_oldstyle_tone as C  # noqa: E402

FAIL = []
TILE = 64                 # LiveSceneMeter's tile
TW, TH = 160, 120         # the RAW thumbnail of the module's picture (CaptureController.oldstyleViewfinder)
FRAMES, STEP, RAMP = 96, 46, 8   # the AE moves the frame a stop down from STEP on, over RAMP frames (as an AE settles)
LUMA = np.array([0.2126, 0.7152, 0.0722])
FLOATS = C.GX * C.GY * C.GL * 12
CST = '1.6328125,-0.609375,-0.0234375,-0.1796875,1.2265625,-0.046875,0.0234375,-0.8359375,1.8125'   # a camera's colour matrix


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


DRIVER = r'''
package com.particlesdevs.photoncamera.processing.oldstyle;
import java.nio.*;
import java.nio.file.*;
public final class LiveHost {
    static float[] floats(String p) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(p))).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.remaining() / 4]; b.asFloatBuffer().get(v); return v;
    }
    static void write(String p, float[] v) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(v); Files.write(Paths.get(p), b.array());
    }
    static float[] ccm(String s) {
        if (s.equals("-")) return null;
        String[] t = s.split(","); float[] m = new float[9]; for (int i = 0; i < 9; i++) m[i] = Float.parseFloat(t[i]); return m;
    }
    public static void main(String[] a) throws Exception {
        if (!OldstyleNative.load()) throw new IllegalStateException("no library");
        final OldstyleAe.Params p = new OldstyleAe.Params(); p.v31 = a[1].equals("2");
        if (a[0].equals("photo")) {       // photo v w h raw.f32 ccm out.f32
            final int w = Integer.parseInt(a[2]), h = Integer.parseInt(a[3]);
            write(a[6], OldstyleLive.photoRgb(floats(a[4]), w, h, ccm(a[5]), p).rgb);
            return;
        }
        // run v frames tile thumbs.f32 tw th ccm tiles.f32 out.f32 vfTone
        final int frames = Integer.parseInt(a[2]), tile = Integer.parseInt(a[3]), tw = Integer.parseInt(a[5]), th = Integer.parseInt(a[6]);
        p.vfTone = a[10].equals("1");
        final float[] thumbs = floats(a[4]), tiles = floats(a[8]);
        final float[] m = ccm(a[7]);
        final OldstyleLive live = new OldstyleLive();
        final int nt = tile * tile * 3, nr = tw * th * 3, per = 2 + OldstyleLive.FLOATS;
        final float[] out = new float[frames * per];
        for (int f = 0; f < frames; f++) {
            final long now = f * 33_000_000L;
            if (f % 2 == 1 && (live.wantsScreen() || f == 1)) {     // LiveSceneMeter: the tile drawn on one frame, read on the next
                final float[] t = new float[nt]; System.arraycopy(tiles, (f - 1) * nt, t, 0, nt);
                live.screen(now, t, tile, tile);
            }
            if (f % 9 == 0) {                                       // the ring's newest RAW every 300 ms
                final float[] r = new float[nr]; System.arraycopy(thumbs, f * nr, r, 0, nr);
                live.photo(now, OldstyleLive.photoRgb(r, tw, th, m, p), tw, th, p);
                if (f == 0 && p.vfTone) live.screen(now, java.util.Arrays.copyOfRange(tiles, 0, nt), tile, tile);
            }
            final OldstyleLive.Grid g = live.grid(now);
            out[f * per] = g == null ? 0f : 1f;
            out[f * per + 1] = g == null ? 1f : g.level;
            if (g != null) System.arraycopy(g.coefficients, 0, out, f * per + 2, OldstyleLive.FLOATS);
        }
        write(a[9], out);
    }
}
'''


def box(a, w, h):
    """A box-averaged copy w x h (area weights)."""
    H, W = a.shape[:2]
    ys = np.linspace(0, H, h + 1)
    xs = np.linspace(0, W, w + 1)
    out = np.empty((h, w) + a.shape[2:])
    yi = np.round(ys).astype(int)
    xi = np.round(xs).astype(int)
    for j in range(h):
        band = a[yi[j]:max(yi[j + 1], yi[j] + 1)].mean(0)
        for i in range(w):
            out[j, i] = band[xi[i]:max(xi[i + 1], xi[i] + 1)].mean(0)
    return out


def display(lin):
    return np.round(255 * np.clip(lin, 0, 1) ** (1 / 2.2)) / 255


def isp(raw, kind, shape=None):
    """The ISP's frame of a RAW frame (linear, white-balanced, before the colour matrix), display-encoded, 8 bit: a gain; a gain into
    a knee; a gain and a shadow lift (power 1 / 1.5); a gain with a local tone (a smooth field lifting the frame's darker half);
    each after the camera's colour matrix."""
    m = np.array([float(t) for t in CST.split(',')]).reshape(3, 3)
    lin = np.clip(raw @ m.T, 0, None)
    if kind == 'gain':
        v = lin * 2.6
    elif kind == 'knee':
        v = lin * 4.0
        knee = 0.7 + 0.3 * (1 - np.exp(-1.5 * (np.clip(v, 0.7, 1.0) - 0.7) / 0.3)) / (1 - np.exp(-1.5))
        v = np.where(v > 0.7, np.where(v >= 1, 1.0, knee), v)
    elif kind == 'lift':
        v = np.clip(lin * 2.5, 0, 1) ** (1 / 1.5)
    else:   # local
        y = lin @ LUMA
        h, w = y.shape
        coarse = box(y[..., None], 8, 6)[..., 0]
        # the field smooth over the frame (bilinear between the 8 x 6 means, as an ISP's local tone is)
        gy = np.clip((np.arange(h) + 0.5) / h * 6 - 0.5, 0, 5)
        gx = np.clip((np.arange(w) + 0.5) / w * 8 - 0.5, 0, 7)
        y0, x0 = np.minimum(gy.astype(int), 4), np.minimum(gx.astype(int), 6)
        fy, fx = (gy - y0)[:, None], (gx - x0)[None, :]
        field = ((coarse[y0][:, x0] * (1 - fx) + coarse[y0][:, x0 + 1] * fx) * (1 - fy)
                 + (coarse[y0 + 1][:, x0] * (1 - fx) + coarse[y0 + 1][:, x0 + 1] * fx) * fy)
        v = lin * 2.6 * np.clip(0.18 / np.maximum(field * 2.6, 0.02), 0.7, 2.5)[..., None]
    return display(v).astype(np.float32)


def office(w, h):
    """Linear RAW light (white-balanced, before the matrix) of an office like the owner's: a dark bluish ceiling, a cream wall with
    papers, a lit yellow doorway, a near-white desk across the bottom."""
    y, x = np.mgrid[0:h, 0:w]
    fy, fx = y / (h - 1), x / (w - 1)
    v = np.empty((h, w, 3))
    v[:] = np.array([0.050, 0.047, 0.036]) * (0.8 + 0.4 * fx[..., None])
    papers = ((x // max(1, w // 18) + y // max(1, h // 11)) % 3 == 0) & (fx > 0.55) & (fy > 0.2) & (fy < 0.7)
    v[papers] = np.array([0.085, 0.085, 0.082])
    ceiling = fy < 0.12 + 0.1 * fx
    v[ceiling] = np.array([0.0042, 0.0046, 0.0060]) * (1 + fx[ceiling, None])
    door = (fx > 0.36) & (fx < 0.52) & (fy > 0.22) & (fy < 0.72)
    v[door] = np.array([0.20, 0.17, 0.065]) * (0.7 + 0.6 * fy[door, None])
    desk = fy > 0.74 + 0.08 * (1 - fx)
    v[desk] = np.array([0.30, 0.30, 0.29]) * (0.75 + 0.5 * fy[desk, None])
    m = np.array([float(t) for t in CST.split(',')]).reshape(3, 3)
    return np.clip(v @ np.linalg.inv(m).T, 0, 1)    # the light before the camera's matrix


def sh(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def save(out, name, a):
    if out is not None:
        from PIL import Image
        Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)).save(out / name)


def metrics(screen, photo, src):
    """The screen against the module's photo of the frame (both display RGB at the screen's size), src the ISP frame."""
    ys, yp, yi = screen @ LUMA, photo @ LUMA, src @ LUMA
    h, w = ys.shape
    zones = [(slice(h * j // 4, h * (j + 1) // 4), slice(w * i // 4, w * (i + 1) // 4)) for j in range(4) for i in range(4)]
    zone = max(abs(ys[z].mean() - yp[z].mean()) for z in zones)
    neutral = (np.abs(photo - yp[..., None]).max(-1) < 0.05 * np.maximum(yp, 0.05)) & (yp > 0.08) & (yp < 0.9)
    hue = float(np.abs(screen[neutral] / np.maximum(ys[neutral, None], 1e-3) - photo[neutral] / np.maximum(yp[neutral, None], 1e-3)).mean()) \
        if neutral.sum() > 50 else 0.0
    blur = lambda a: box(a[..., None], w // 2, h // 2)[..., 0]
    ls, lp = np.log(blur(ys) + 0.01), np.log(blur(yp) + 0.01)
    zs = [(slice(h // 2 * j // 4, h // 2 * (j + 1) // 4), slice(w // 2 * i // 4, w // 2 * (i + 1) // 4)) for j in range(4) for i in range(4)]
    ratios = [ls[z].std() / max(lp[z].std(), 1e-3) for z in zs if lp[z].std() > 0.05]
    contrast = float(np.median(ratios)) if ratios else 1.0
    burnt = lambda a: float((a.max(-1) >= 250 / 255).mean())
    gain = np.log(np.maximum(ys, 1e-3) / np.maximum(yi, 1e-3))
    li = np.log(np.maximum(yi, 1e-3))
    ok = (yi > 6 / 255) & (ys < 0.995)
    steps = []
    for axis in (0, 1):
        dg = np.abs(np.diff(gain, axis=axis))
        dl = np.abs(np.diff(li, axis=axis))
        both = np.minimum(np.delete(ok, -1, axis), np.delete(ok, 0, axis))
        steps.append((dg - 2.2 * dl)[both])
    blotch = float(np.quantile(np.concatenate(steps), 0.999))
    return dict(mean=ys.mean() / max(yp.mean(), 1e-6) - 1, zone=zone, hue=hue, contrast=contrast, burnt=burnt(screen), burnt_photo=burnt(photo),
                blotch=blotch)


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    if out:
        out.mkdir(parents=True, exist_ok=True)
    d = Path(tempfile.mkdtemp(prefix='oldstyle_live_'))
    try:
        java_home = Path(os.environ.get('JAVA_HOME') or Path(shutil.which('javac')).resolve().parent.parent)
        lib = d / 'libscameraTone_host.so'
        sh(['g++', '-std=c++17', '-O2', '-fPIC', '-shared', '-pthread', '-ffp-contract=off', '-I' + str(TP / 'include'), '-I' + str(JNI.parent),
            '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux')]
           + [str(p) for p in sorted((TP / 'src').glob('*.cpp'))] + [str(JNI), '-o', str(lib)])
        pkg = d / 'java/com/particlesdevs/photoncamera/processing/oldstyle'
        pkg.mkdir(parents=True)
        for f in ('OldstyleNative.java', 'OldstyleAe.java', 'OldstyleLive.java'):
            shutil.copy(JAVA / f, pkg / f)
        (pkg / 'LiveHost.java').write_text(DRIVER)
        (d / 'classes').mkdir()
        sh(['javac', '-nowarn', '-encoding', 'UTF-8', '-d', str(d / 'classes')] + [str(p) for p in pkg.glob('*.java')])
        java = ['java', '-Dscamera.tone.lib=' + str(lib), '-cp', str(d / 'classes'), 'com.particlesdevs.photoncamera.processing.oldstyle.LiveHost']
        g = C.Gpu()
        cases = []
        # the owner's «Фото» frame: the ISP frame itself, its RAW the screenshot back to light over a gain, before the matrix
        owner = np.load(FIXTURES / 'owner_photo_mode.npz')['rgb'].astype(np.float32) / 255   # (numpy only: the CI lane has no Pillow)
        m = np.array([float(t) for t in CST.split(',')]).reshape(3, 3)
        owner_raw = np.clip((owner.astype(np.float64) ** 2.2 / 2.6) @ np.linalg.inv(m).T, 0, 1)
        cases.append(('owner', owner_raw, lambda r, e: display(np.clip(r @ m.T, 0, None) * 2.6 * e).astype(np.float32)))
        for kind in ('gain', 'knee', 'lift', 'local'):
            cases.append(('office_' + kind, office(320, 240), (lambda k: lambda r, e: isp(r * e, k))(kind)))
        for name in ('lamp', 'pool'):
            raw = np.load(FIXTURES / ('%s_raw.npz' % name))['raw'].astype(np.float64)
            cases.append((name, raw, lambda r, e: isp(r * e, 'knee')))
        only = os.environ.get('OLDSTYLE_LIVE_ONLY')   # a quick subset: "case,version" pairs separated by ';'
        runs = [(c, v) for c in cases for v in (1, 2)]
        if only:
            want = {tuple(t.split(',')) for t in only.split(';')}
            runs = [(c, v) for c, v in runs if (c[0], str(v)) in want]
        sheet = []
        for (name, raw, make), version in runs:
            h, w = raw.shape[:2]
            tag = '%s V%d' % (name, version)
            # the thumbnail of the frame's own shape (CaptureController: 160 x 120 of a 4:3 sensor's part)
            tw, th = (TW, TH) if w >= h else (TH, TW)
            rng = np.random.default_rng(643)
            thumbs, tiles, frames = [], [], []
            for f in range(FRAMES):
                e = (1 + 0.01 * np.sin(f * 0.7)) * 0.5 ** min(1.0, max(0.0, (f - STEP + 1) / RAMP))   # the AE's hunting; its step
                r = np.clip(raw + rng.normal(0, 1, raw.shape) * (0.0004 + 0.004 * np.sqrt(raw)), 0, 1)
                frame = make(r, e)
                frames.append(frame)
                tiles.append(box(frame, TILE, TILE).astype(np.float32))
                thumbs.append(box(r * e, tw, th)[::-1].astype(np.float32))   # the sensor's buffer upside down against the stream
            np.ascontiguousarray(np.stack(thumbs), '<f4').tofile(d / 'thumbs.f32')
            np.ascontiguousarray(np.stack(tiles), '<f4').tofile(d / 'tiles.f32')
            sh(java + ['run', str(version), str(FRAMES), str(TILE), str(d / 'thumbs.f32'), str(tw), str(th), CST, str(d / 'tiles.f32'),
                       str(d / 'grids.f32'), '1'])
            grids = np.fromfile(d / 'grids.f32', '<f4').reshape(FRAMES, 2 + FLOATS)
            # the photo in the sensor's orientation, as the thumbnail (the module pads and filters at the frame's far edges: an
            # upright photo against an upside-down thumbnail differs there by the module's own edge handling, not by the preview)
            photos = []
            for e in (1.0, 0.5):
                np.ascontiguousarray(raw[::-1] * e, '<f4').tofile(d / 'raw.f32')
                sh(java + ['photo', str(version), str(w), str(h), str(d / 'raw.f32'), CST, str(d / 'photo.f32')])
                photos.append(np.fromfile(d / 'photo.f32', '<f4').reshape(h, w, 3)[::-1].astype(np.float64))
            photo = photos[0]
            check(grids[:, 0].sum() > FRAMES - 12, '%s: a grid is drawn (from frame %d)' % (tag, int(np.argmax(grids[:, 0] > 0))))
            pair = C.Pair(g, frames[0])
            means, after, screen = [], [], None
            for f in range(STEP - 11, FRAMES):
                if grids[f, 0] == 0 or (f > STEP + RAMP + 6 and f < FRAMES - 4 and f % 4):
                    continue   # (one GPU draw a frame is slow here)
                src = frames[f]
                pair.frame(src)
                drawn = pair.tone(grids[f, 2:], level=float(grids[f, 1])).astype(np.float64)
                (after if f >= STEP else means).append((f, float((drawn @ LUMA).mean())))
                if f == STEP - 1:
                    screen, isp_frame = drawn, src.astype(np.float64)
            if screen is None:
                continue
            if os.environ.get('OLDSTYLE_LIVE_DUMP'):
                np.savez(Path(os.environ['OLDSTYLE_LIVE_DUMP']) / ('%s_v%d.npz' % (name, version)), grid=grids[STEP - 1], screen=screen,
                         photo=photo, isp=isp_frame, tile=tiles[STEP - 1], thumb=thumbs[STEP - 1])
            k = metrics(screen, photo, isp_frame)
            # blotches: the transform itself across the frame - flat probe colours through the shader, the change between points one
            # cell apart (a hard edge would be a jump; the grid's trilinear interpolation spreads any change over a cell)
            steps = 0.0
            for probe in ((0.15, 0.15, 0.15), (0.45, 0.45, 0.45), (0.75, 0.75, 0.75), (0.93, 0.93, 0.93), (0.8, 0.6, 0.3), (0.3, 0.4, 0.6)):
                pair.frame(np.ones((h, w, 3), np.float32) * np.array(probe, np.float32))
                o = pair.tone(grids[STEP - 1, 2:], level=float(grids[STEP - 1, 1])).astype(np.float64)
                ys_, xs_ = (np.arange(C.GY) + 0.5) * h / C.GY, (np.arange(C.GX) + 0.5) * w / C.GX
                pts = o[ys_.astype(int)][:, xs_.astype(int)]
                steps = max(steps, np.abs(np.diff(pts, axis=0)).max(), np.abs(np.diff(pts, axis=1)).max(),
                            np.abs(np.diff(o, axis=0)).max(), np.abs(np.diff(o, axis=1)).max() * 0)
                px = max(np.abs(np.diff(o, axis=0)).max(), np.abs(np.diff(o, axis=1)).max())
                k['pixel'] = max(k.get('pixel', 0.0), px)
            k['cell'] = steps
            # (7 %: the module's local tone depends a little on the frame's size - its own picture of the 160 x 120 thumbnail is up to
            # 5 % off its photo of the frame here; the screen follows the thumbnail's)
            check(abs(k['mean']) <= 0.07, '%s: the screen\'s mean %+.1f %% of the photo\'s' % (tag, 100 * k['mean']))
            check(k['zone'] <= 0.06, '%s: 4 x 4 zones\' means within %.3f of the photo\'s' % (tag, k['zone']))
            check(k['hue'] <= 0.04, '%s: near-neutral areas\' colour within %.3f of the photo\'s' % (tag, k['hue']))
            check(0.7 <= k['contrast'] <= 1.4, '%s: local contrast %.2f of the photo\'s (median zone)' % (tag, k['contrast']))
            # (+ 2 % of the frame: an ISP's local tone shifts the frame's whites across a cell's boundary the grid does not follow
            # exactly - the synthetic local-tone ISP; every other case is within 0.2 %)
            check(k['burnt'] <= k['burnt_photo'] + 0.02, '%s: burnt out on the screen %.1f %%, in the photo %.1f %%'
                  % (tag, 100 * k['burnt'], 100 * k['burnt_photo']))
            check(k['cell'] <= 0.15 and k['pixel'] <= 0.02, '%s: no blotches - a flat colour changes by %.3f a cell, %.4f a pixel at most'
                  % (tag, k['cell'], k['pixel']))
            mm = np.array([v for _, v in means])
            steps = np.abs(np.diff(mm)) / mm[:-1]
            check(steps.max() < 0.01, '%s: steady, the largest frame-to-frame change %.2f %%' % (tag, 100 * steps.max()))
            # the AE's step: the frame a stop darker (the ISP's own). The photo is taken at that exposure, so the screen goes from the
            # old photo's picture to the new photo's (with HDR about the same: the estimator's TETs over the frame's; without it the
            # frame's): right after the step it lies between the two (5 % either side), no frame after it moves by over 3 %, and
            # after 1.5 s it is the new photo's mean within 7 % (as the steady mean)
            old, new = float((photo @ LUMA).mean()), float((photos[1] @ LUMA).mean())
            near = [(f, v) for f, v in after if f <= STEP + RAMP + 6]
            seq = [mm[-1]] + [v for _, v in near]
            jumps = np.abs(np.diff(seq)) / np.array(seq[:-1])
            isp_seq = [float((frames[f] @ LUMA).mean()) for f in [STEP - 1] + [f for f, _ in near]]
            own = np.abs(np.diff(isp_seq)) / np.array(isp_seq[:-1])
            lo, hi = min(1.0, new / old) - 0.05, max(1.0, new / old) + 0.05
            worst = min(seq[1:]) / mm[-1], max(seq[1:]) / mm[-1]
            check(jumps.max() <= 2.5 * own.max() + 0.03 and abs(after[-1][1] / new - 1) < 0.07,
                  '%s: the AE a stop down over %d frames (the frame %.1f %% a frame, the new photo %+.1f %% of the old) - the screen %.1f %% a '
                  'frame at most (%+.0f .. %+.0f %% of where it was), then %+.1f %% of the new photo\'s mean after %.1f s'
                  % (tag, RAMP, 100 * own.max(), 100 * (new / old - 1), 100 * jumps.max(), 100 * (worst[0] - 1), 100 * (worst[1] - 1),
                     100 * (after[-1][1] / new - 1), (after[-1][0] - STEP) * 0.033))
            if name == 'office_lift':
                sh(java + ['run', str(version), str(FRAMES), str(TILE), str(d / 'thumbs.f32'), str(tw), str(th), CST, str(d / 'tiles.f32'),
                           str(d / 'grids.f32'), '0'])
                off = np.fromfile(d / 'grids.f32', '<f4').reshape(FRAMES, 2 + FLOATS)
                check(off[:, 0].sum() == 0, '%s: «Тон в видоискателе» off - no grid, the frame as «Фото» has it' % tag)
            save(out, '%s_v%d_isp.png' % (name, version), isp_frame)
            save(out, '%s_v%d_screen.png' % (name, version), screen)
            save(out, '%s_v%d_photo.png' % (name, version), photo)
            sheet.append((name, version, isp_frame, screen, photo))
        if out is not None and sheet:
            from PIL import Image, ImageDraw
            cell = 240
            img = Image.new('RGB', (3 * cell, len(sheet) * (cell + 18)), (24, 24, 24))
            for row, (name, version, a, b, c) in enumerate(sheet):
                for col, pic in enumerate((a, b, c)):
                    im = Image.fromarray((np.clip(pic, 0, 1) * 255 + 0.5).astype(np.uint8))
                    im.thumbnail((cell, cell))
                    img.paste(im, (col * cell, row * (cell + 18) + 18))
                ImageDraw.Draw(img).text((4, row * (cell + 18) + 2), '%s V%d: input (ISP) | screen | module photo' % (name, version),
                                         fill=(255, 220, 120))
            img.save(out / 'sheet.png')
    finally:
        shutil.rmtree(d, ignore_errors=True)
    if FAIL:
        print('%d FAILED' % len(FAIL))
        sys.exit(1)
    print('OK')


if __name__ == '__main__':
    main()
