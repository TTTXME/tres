package com.scamera.app.capture;

import android.util.Range;

import com.scamera.app.processing.ImageFrame;
import com.scamera.app.settings.PreferenceKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P646 «Склейка YUV»: the series after the shutter of a lens with the YUV route (our tone curve, so the frames are linearizable):
 * N frames at the metered exposure, then where the scene needs them (the newest ring frame's Y: YuvMergeRoute.meter) the short
 * frames (ES: highlights, the gain lowered first, then the shutter) and one long frame (L: shadows, the gain raised first, the
 * shutter only up to the hand-held cap) - the bracketing of HybridPlan in its own rows (pref_scam_hybrid_yuv_*). The count is cut
 * to the memory budget of packed frames (an 8 MP P010 frame is 24 MB).
 */
final class YuvMergePlan {
    private YuvMergePlan() {}

    /** Packed frames of one series at most (the merge also holds the merged frame and the float RGB). */
    static final long BUDGET_BYTES = 288L << 20;
    /**
     * P656: the «Из буфера ZSL» ring's frames at most in bytes of their packed copies - twice the series' budget (the ring holds
     * the camera's own Images and packs them all at the press). Its count followed «Число кадров в серии» alone (P650: up to 65
     * + 2, the reader's 56): ~1.3 GB of 10-bit frames and as much again packed; the default 20 + 2 stays under it at 8 and 10 bits.
     */
    static final long RING_BUDGET_BYTES = 2 * BUDGET_BYTES;

    /**
     * P656: the YUV ring's capacity of the «Из буфера ZSL» route: the general count + 2 (P650), within the ZSL buffer setting,
     * {@link #RING_BUDGET_BYTES} of {@code frameBytes} (0: unknown, no cap) + 2 and at least {@code floor}.
     */
    static int ringFrames(int zslBuffer, int generalFrames, long frameBytes, int floor) {
        int n = Math.min(zslBuffer, generalFrames + 2);
        if (frameBytes > 0) n = (int) Math.min(n, RING_BUDGET_BYTES / frameBytes + 2);
        return Math.max(floor, n);
    }
    /** The clipped share (Y >= 250) from which the short frames are taken, and the deep-shadow share (Y <= 24) for the long one. */
    static final float CLIP_TRIGGER = 0.002f, SHADOW_TRIGGER = 0.08f;
    static final long LONG_CAP_NS = 66_000_000L;

    static final class Tuning {
        final int frames;
        final boolean bracket;
        final float esEv, longEv;

        Tuning(int frames, boolean bracket, float esEv, float longEv) {
            // P650 (owner, 2026-11-12: «привяжи количество кадров в склейке к общей настройке количества кадров»): the N count is
            // the general «Кадры ZSL» setting (getHybridZslFrames, 4..65), the same the RAW merge uses - not a YUV-only cap. The
            // memory budget of packed frames (build) and the camera's limits clamp it from there, as on the RAW path.
            this.frames = Math.max(2, Math.min(65, frames));
            this.bracket = bracket;
            this.esEv = Math.max(1f, Math.min(4f, esEv));
            this.longEv = Math.max(0.5f, Math.min(3f, longEv));
        }

        static Tuning read() {
            // P650: «Кадры YUV» 0 = the general «Кадры ZSL» count (getHybridZslFrames, the RAW merge's), as getFullRes50Frames' 0;
            // a non-zero value is an explicit per-lens override. The memory budget and the camera's limits clamp it (build).
            final int own = Math.round(PreferenceKeys.hybridValue("yuv_frames", 0f));
            final int frames = own > 0 ? own : PreferenceKeys.getHybridZslFrames();
            return new Tuning(frames, PreferenceKeys.hybridSwitch("yuv_bracket", true)
                    && !com.scamera.app.settings.MotionMode.on() && PreferenceKeys.hdrFrames(),
                    PreferenceKeys.hybridValue("yuv_es_ev", 3f), PreferenceKeys.hybridValue("yuv_long_ev", 2f));
        }
    }

    /**
     * {@code clip} / {@code shadow}: the scene's shares (YuvMergeRoute.meter); {@code frameBytes}: one packed frame. Never throws:
     * without a known exposure the camera's AE exposes the N frames (no brackets).
     */
    static HybridPlan build(long nNs, int nIso, Range<Long> times, Range<Integer> isos, float clip, float shadow, Tuning t,
                            long frameBytes, boolean bracketAllowed) {
        final int maxFrames = (int) Math.max(2, Math.min(16, BUDGET_BYTES / Math.max(1, frameBytes)));
        // P690 (owner's log 2026-11-14: 16 N frames filled the 16-frame budget, so a backlit scene - 17 % clipped, 16 % deep
        // shadows - got no short and no long frame): the brackets the scene asks for are kept out of the N's share of the budget
        final int reserved = bracketsWanted(t, bracketAllowed, clip, shadow);
        final int normals = Math.max(2, Math.min(t.frames, maxFrames - reserved));
        // P650: the general frame count was cut to the packed-frame memory budget (the RAW path logs its own cap the same way)
        final String memCap = normals < t.frames
                ? String.format(Locale.ROOT, " N %d->%d (memory: %d frames of %.1f MB)", t.frames, normals, maxFrames, frameBytes / 1048576.0) : "";
        if (nNs <= 0 || nIso <= 0 || times == null || isos == null) {
            final HybridPlan ae = HybridPlan.autoExposure(normals);
            return HybridPlan.of(ae.requests, 0, 0, "YUV MERGE plan: " + normals + " frames at the camera's AE (no known exposure), no brackets" + memCap);
        }
        final List<HybridPlan.Request> out = new ArrayList<>();
        final double n = (double) nNs * nIso;
        for (int i = 0; i < normals; i++) out.add(new HybridPlan.Request(ImageFrame.CaptureRole.NORMAL, nNs, nIso, 1.0));
        final StringBuilder why = new StringBuilder();
        int room = maxFrames - normals;
        if (!bracketAllowed) why.append(" brackets: off (the camera's curve)");
        else if (!t.bracket) why.append(" brackets: off");
        else {
            if (clip >= CLIP_TRIGGER && room >= 1) {
                final double target = n / Math.pow(2, t.esEv);
                final int iso = (int) Math.max(isos.getLower(), Math.min(nIso, Math.round(target / nNs)));
                final long ns = Math.max(times.getLower(), Math.min(nNs, Math.round(target / iso)));
                final double ratio = ns * (double) iso / n;
                if (ratio <= 0.6) {
                    final int count = Math.min(2, room);
                    for (int i = 0; i < count; i++) out.add(new HybridPlan.Request(ImageFrame.CaptureRole.EXTRA_SHORT, ns, iso, ratio));
                    room -= count;
                } else why.append(String.format(Locale.ROOT, " ES skipped (sensor floor x%.2f)", ratio));
            } else why.append(clip >= CLIP_TRIGGER ? " no ES (no room in the memory budget)"
                    : String.format(Locale.ROOT, " no ES (clipped %.4f)", clip));
            if (shadow >= SHADOW_TRIGGER && room >= 1) {
                final double target = n * Math.pow(2, t.longEv);
                int iso = (int) Math.max(isos.getLower(), Math.min(isos.getUpper(), Math.round(nIso * Math.pow(2, t.longEv))));
                long ns = nNs;
                if (ns * (double) iso < target * 0.95) {
                    final long cap = Math.min(times.getUpper(), Math.max(nNs, LONG_CAP_NS));
                    ns = Math.max(nNs, Math.min(cap, Math.round(target / iso)));
                }
                final double ratio = ns * (double) iso / n;
                if (ratio >= 1.4) out.add(new HybridPlan.Request(ImageFrame.CaptureRole.LONG, ns, iso, ratio));
                else why.append(String.format(Locale.ROOT, " L skipped (x%.2f)", ratio));
            } else why.append(shadow >= SHADOW_TRIGGER ? " no L (no room in the memory budget)"
                    : String.format(Locale.ROOT, " no L (shadows %.3f)", shadow));
        }
        final String description = String.format(Locale.ROOT, "YUV MERGE plan: N=%.3fms*ISO%d clip=%.4f shadows=%.3f requests=%s%s"
                + " (at most %d frames of %.1f MB)%s", nNs / 1e6, nIso, clip, shadow, out, why, maxFrames, frameBytes / 1048576.0, memCap);
        return HybridPlan.of(out, nNs, nIso, description);
    }

    /** P690: the bracket frames this scene asks for - 2 short ones over the clip trigger, 1 long one over the shadow trigger. */
    static int bracketsWanted(Tuning t, boolean bracketAllowed, float clip, float shadow) {
        if (!bracketAllowed || !t.bracket) return 0;
        return (clip >= CLIP_TRIGGER ? 2 : 0) + (shadow >= SHADOW_TRIGGER ? 1 : 0);
    }
}
