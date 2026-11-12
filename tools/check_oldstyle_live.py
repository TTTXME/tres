#!/usr/bin/env python3
"""P630g «Oldstyle»: the viewfinder's live tone against the photo, a simulated viewfinder on the host (owner's recording, a vivo:
the viewfinder burnt out in the mode and changed brightness; «Фото» and the saved photo right).

A scene; RAW frames of it at the viewfinder's exposure with noise and the AE's small hunting, metered every 300 ms as
CaptureController does (the shipped OldstyleAe); the ISP's frames of the same RAW with the ISP's own gain and knee, their level
measured as LiveSceneMeter does; the shipped OldstyleLive gives each frame's gains; the frame is drawn by the shipped viewfinder
shader (preview/main_fs with utils/import_oldstyle_tone_live, moderngl) with the version's LUT from the tone-port module
(OldstyleNative.previewLut); the photo of the scene is the module's (the JNI built for the host, the burst at the plan's exposure,
default stages). Checked, per version (2.7 / 3.1), scene (a lit wall, a room with a window past the RAW's white, a lamp-lit patch in
a dark room: HDR by the plan's rule) and ISP gain (1, 2, 3.5, 6):
- a static scene is steady: the screen's mean changes by less than 1 % from frame to frame;
- the screen's mean is the photo's within 5 % (8 % at an ISP gain of 6): the gains are calibrated on the module's own picture of
  the metered samples (OldstyleLive.photoMean), so the module's frame-dependent parts (3.1's shadow curve, the dehaze) are in;
- the screen burns out no more than the photo does (+ 0.5 % of the frame);
- P630e's state (the RAW frame's gains on the ISP frame) is far off at an ISP gain of 3.5 (the burnt-out viewfinder).
Usage: check_oldstyle_live.py
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
sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_oldstyle_tone as C  # noqa: E402

FAIL = []
W, H, FRAMES = 128, 96, 120


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
    public static void main(String[] a) throws Exception {
        // v w h frames raw.f32 (frames x w*h*3, the RAW frame of each viewfinder frame) levels.f32 (frames) scene.f32 exposure lut.rgba photo.rgb
        if (!OldstyleNative.load()) throw new IllegalStateException("no library");
        final boolean v31 = a[0].equals("2"); final int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]), frames = Integer.parseInt(a[3]);
        final float[] raw = floats(a[4]), levels = floats(a[5]), scene = floats(a[6]);
        final float exposure = Float.parseFloat(a[7]);
        final OldstyleLive live = new OldstyleLive();
        final OldstyleAe.Params p = new OldstyleAe.Params(); p.v31 = v31;
        OldstyleAe.Capture press = null;
        final float[] frame = new float[w * h * 3];
        for (int f = 0; f < frames; f++) {
            final long now = f * 33_000_000L;
            if (f % 9 == 0) {
                System.arraycopy(raw, f * frame.length, frame, 0, frame.length);
                press = OldstyleAe.capture(OldstyleAe.meter(frame, w, h, v31, 1f, true), v31, 1.0);
                press.meterRgb = frame.clone(); press.meterW = w; press.meterH = h;   // as CaptureController.meterOldstyle
                live.plan(now, v31 ? 2 : 1, press, p.maxGain, OldstyleLive.photoMean(press, p), OldstyleLive.lut(v31 ? 2 : 1));
            }
            if (f % 2 == 1) live.level(now, levels[f]);
            final float[] t = live.gains(now);
            System.out.println(t == null ? "none" : t[1] + " " + t[2] + " " + t[3] + " " + t[4]);
        }
        final float[] rawGains = OldstyleAe.viewfinderGains(press, p.maxGain);
        System.out.println("raw " + rawGains[0] + " " + rawGains[1] + " " + (press.hdr ? 1 : 0));
        // the photo: the burst at the plan's exposure, the tone's plan from the capture over the frames' TET, the module
        final float tet = (float) press.frameTet;
        final float[] merged = new float[scene.length];
        for (int i = 0; i < merged.length; i++) merged[i] = Math.max(0f, Math.min(1f, scene[i] * exposure * tet));
        final OldstyleAe.Plan plan = OldstyleAe.plan(merged, w, h, p, 1f, press, tet);
        final int n = w * h;
        final ByteBuffer planar = OldstyleNative.planar(w, h);
        final ShortBuffer s = planar.asShortBuffer();
        for (int i = 0; i < n; i++) for (int c = 0; c < 3; c++) s.put(c * n + i, OldstyleAe.sample(merged[i * 3 + c], plan.inputScale));
        final ByteBuffer out = ByteBuffer.allocateDirect(n * 3);
        OldstyleNative.render(v31 ? 2 : 1, planar, w, h, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, plan.gainShort, plan.gainLong, plan.hdr,
                false, 0f, p.post(), null, 0, 0, null, out);
        final byte[] o = new byte[n * 3]; out.position(0); out.get(o); Files.write(Paths.get(a[9]), o);
        final byte[] lut = new byte[4096]; OldstyleNative.previewLut(v31 ? 2 : 1, lut); Files.write(Paths.get(a[8]), lut);
        System.out.println("photo " + plan.describe());
    }
}
'''


SCENES = ('wall', 'window', 'lamp')   # a lit wall (SDR), a window past the RAW's white, a lamp-lit patch (HDR by the plan's rule)


def scene(kind):
    y, x = np.mgrid[0:H, 0:W]
    v = 0.02 + 0.05 * x / (W - 1) + 0.02 * y / (H - 1)
    if kind == 'lamp':
        v = 0.006 + 0.01 * x / (W - 1) + 0.004 * y / (H - 1)
    box = (x > W * 0.62) & (y > H * 0.17) & (y < H * 0.62)
    v = np.where(box, {'wall': 0.16, 'window': 0.9, 'lamp': 0.2}[kind], v)
    return (v[..., None] * np.array([1.05, 1.0, 0.9])).astype(np.float32)


def isp(raw, gain):
    """The ISP's frame of a RAW frame: its gain, a soft knee into white, display-encoded, 8 bit."""
    v = raw * gain
    knee = 0.7 + 0.3 * (1 - np.exp(-1.5 * (np.clip(v, 0.7, 1.0) - 0.7) / 0.3)) / (1 - np.exp(-1.5))
    v = np.where(v > 0.7, np.where(v >= 1, 1.0, knee), v)
    return np.round(255 * np.clip(v, 0, 1) ** (1 / 2.2)) / 255


def level(frame):
    """LiveSceneMeter.linearMean: the display luma of each texel to the power 2.2."""
    return float(((frame @ np.array([0.2126, 0.7152, 0.0722])) ** 2.2).clip(0, 1).mean())


def sh(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def main():
    d = Path(tempfile.mkdtemp(prefix='oldstyle_live_'))
    try:
        java_home = Path(os.environ.get('JAVA_HOME') or Path(shutil.which('javac')).resolve().parent.parent)
        lib = d / 'libscameraTone_host.so'
        sh(['g++', '-std=c++17', '-O2', '-fPIC', '-shared', '-ffp-contract=off', '-I' + str(TP / 'include'),
            '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux')]
           + [str(p) for p in sorted((TP / 'src').glob('*.cpp'))] + [str(JNI), '-o', str(lib)])
        pkg = d / 'java/com/particlesdevs/photoncamera/processing/oldstyle'
        pkg.mkdir(parents=True)
        for f in ('OldstyleNative.java', 'OldstyleAe.java', 'OldstyleLive.java'):
            shutil.copy(JAVA / f, pkg / f)
        (pkg / 'LiveHost.java').write_text(DRIVER)
        (d / 'classes').mkdir()
        sh(['javac', '-nowarn', '-encoding', 'UTF-8', '-d', str(d / 'classes')] + [str(p) for p in pkg.glob('*.java')])
        g = C.Gpu()
        luma = np.array([0.2126, 0.7152, 0.0722])
        only = os.environ.get('OLDSTYLE_LIVE_ONLY')   # a quick subset: "version,window,k" triples separated by ';'
        cases = [(v, w, k) for v in (1, 2) for w in SCENES for k in (1.0, 2.0, 3.5, 6.0)]
        if only:
            cases = [(int(a), b, float(c)) for a, b, c in (t.split(',') for t in only.split(';'))]
        for version, window, k in cases:
            if True:
                sc = scene(window)
                if True:
                    rng = np.random.default_rng(630)
                    raws, frames, levels = [], [], []
                    for f in range(FRAMES):
                        e = 4.0 * (1 + 0.01 * np.sin(f * 0.7))
                        v = sc * e
                        r = np.clip(v + rng.normal(0, 1, v.shape) * (0.002 + 0.02 * np.sqrt(v)), 0, 1).astype(np.float32)
                        raws.append(r)
                        frames.append(isp(r, k))
                        levels.append(level(frames[-1]))
                    np.ascontiguousarray(np.stack(raws), '<f4').tofile(d / 'raw.f32')
                    np.ascontiguousarray(levels, '<f4').tofile(d / 'levels.f32')
                    np.ascontiguousarray(sc, '<f4').tofile(d / 'scene.f32')
                    r = sh(['java', '-Dscamera.tone.lib=' + str(lib), '-cp', str(d / 'classes'),
                            'com.particlesdevs.photoncamera.processing.oldstyle.LiveHost', str(version), str(W), str(H), str(FRAMES),
                            str(d / 'raw.f32'), str(d / 'levels.f32'), str(d / 'scene.f32'), '4.0', str(d / 'lut.rgba'), str(d / 'photo.rgb')])
                    lines = r.stdout.strip().splitlines()
                    lut = np.frombuffer((d / 'lut.rgba').read_bytes(), np.uint8).reshape(1024, 4) / 255.0
                    photo = np.frombuffer((d / 'photo.rgb').read_bytes(), np.uint8).reshape(H, W, 3) / 255.0
                    means, last = [], None
                    for f in range(FRAMES):
                        if lines[f] == 'none':
                            continue
                        gs, gl, hdr, boost = (float(t) for t in lines[f].split())
                        if f < 45 and f != FRAMES - 1:
                            continue   # the first second and a half: the state settles (and one GPU draw a frame is slow here)
                        pair = C.Pair(g, frames[f].astype(np.float32))
                        last = pair.tone(version, (gs, gl), hdr > 0.5, lut, boost=boost)
                        means.append(float((last @ luma).mean()))
                    steps = np.abs(np.diff(means)) / np.array(means[:-1])
                    pm, vm = float((photo @ luma).mean()), means[-1]
                    burnt = lambda a: float((a.max(-1) >= 250 / 255).mean())
                    hdr_photo = 'hdr=true' in lines[-1]
                    tag = 'V%d %s ISP x%g' % (version, window, k)
                    check(steps.max() < 0.01, '%s: steady, the largest frame-to-frame change %.2f %%' % (tag, 100 * steps.max()))
                    limit = 0.08 if k > 4 else 0.05   # an ISP gain of 6: most of the frame is past the screen's white
                    check(abs(vm / pm - 1) <= limit, '%s: the screen\'s mean %.1f vs the photo\'s %.1f (%+.1f %%, limit %d %%, %s)'
                          % (tag, 255 * vm, 255 * pm, 100 * (vm / pm - 1), round(100 * limit), 'HDR' if hdr_photo else 'SDR'))
                    check(burnt(last) <= burnt(photo) + 0.005, '%s: burnt out on the screen %.1f %%, in the photo %.1f %%'
                          % (tag, 100 * burnt(last), 100 * burnt(photo)))
                    if k == 3.5:
                        rg = [float(t) for t in lines[FRAMES].split()[1:]]
                        old = C.Pair(g, frames[-1].astype(np.float32)).tone(version, (rg[0], rg[1]), rg[2] > 0.5, lut)
                        om = float((old @ luma).mean())
                        check(om > pm * 1.2, '%s: P630e\'s RAW gains on the ISP frame were the over-bright viewfinder (mean %.1f, '
                              '%+.0f %% of the photo\'s; burnt %.1f %%)' % (tag, 255 * om, 100 * (om / pm - 1), 100 * burnt(old)))
    finally:
        shutil.rmtree(d, ignore_errors=True)
    if FAIL:
        print('%d FAILED' % len(FAIL))
        sys.exit(1)
    print('OK')


if __name__ == '__main__':
    main()
