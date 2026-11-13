package com.scamera.app.processing;

import java.util.Locale;

/**
 * P597 (owner, 2026-11-10: «лаги при включенной тетре остались», no log with the complaint): where a RAW viewfinder frame's time
 * goes, stage by stage and thread by thread, for the second VF_RAW line of every 2 s ("VF_RAW: stages ..."): the camera thread's
 * callback (its ring copy, the «Движение» thumbnail, the white balance's frame copy, the gap between two frames arriving, the
 * spread of their delivery lag), the mosaic white balance (on its thread, how often), the hand-off of a binned
 * frame (its wait and its bin, on the viewfinder's own thread), and the GL thread (how long a published frame waited for its
 * draw - the swap of the frame before blocks there -, its upload, the meters on the CPU, the whole RAW draw, the gap between two
 * new frames on the screen and the gaps that were late). Mean / max in ms over the window; a stage nobody noted shows "-".
 * Any thread; one lock, a few adds a frame.
 */
public final class VfStages {
    public enum Stage {
        /** The camera thread's callback of one live RAW frame (CaptureController.onMatchedLiveRaw), everything in it. */
        CAMERA,
        /** The ZSL ring's copy of the frame into its memfd slot (inside {@link #CAMERA}). */
        RING,
        /**
         * The mosaic viewfinder's white balance (ViewfinderMosaicWb: MosaicNeutral's statistic and estimate of the frame), on the
         * thread named with it - the camera thread for a session's first neutral (or vf_mosaic_wb_async 0), else SCAMERA-vf-wb.
         */
        WB,
        /** The copy of the frame the white balance measures on its own thread (on the camera thread, inside {@link #CAMERA}). */
        WB_COPY,
        /** MotionMeter's thumbnail of a «Движение» frame (on the camera thread, inside {@link #CAMERA}). */
        MOTION,
        /** Between two frames arriving on the camera thread. */
        ARRIVE,
        /** From the hand-off of a frame (LiveRawFrame.publishRef) to its publication: the wait for the thread and the bin. */
        HANDOFF,
        /** The bin itself (LiveRawFrame.binMosaic: cell means, the 50 / 200 MP bins). */
        BIN,
        /** From a frame's publication to the GL thread taking it for its draw (the swap of the frame before it is in here). */
        WAIT,
        /** glTexImage2D / glTexSubImage2D of a new frame. */
        UPLOAD,
        /** The CPU meters of the GL thread (LiveWhyMeter's AE cells with WhyAe, LiveRawMeter). */
        METER,
        /** The RAW viewfinder's whole draw of a frame on the GL thread (upload and meters included). */
        DRAW,
        /** glFinish after the draw (scam_dev vf_stage_gpu 1 only): the GPU's time for the frame. */
        GPU,
        /** Between two new frames drawn. */
        SHOWN,
        /**
         * P662: the hand-off's copy of a ring-slot frame into the GL thread's pixel-unpack buffer (inside {@link #HANDOFF}); the GL
         * thread's {@link #UPLOAD} is then the upload from that buffer.
         */
        PBO
    }

    /** A gap between two new frames on the screen above this is counted late (1.5 frames of a 30 fps stream). */
    public static final long LATE_NS = 50_000_000L;

    private static final Object LOCK = new Object();
    private static final int N = Stage.values().length;
    private static final long[] sum = new long[N], max = new long[N];
    private static final int[] count = new int[N];
    private static final String[] thread = new String[N];
    private static long lagMin = Long.MAX_VALUE, lagMax = Long.MIN_VALUE, lastArriveNs, lastShownNs;
    private static int late, busy;
    private static String source = "";

    private VfStages() {}

    /** {@code ns} of {@code stage}, on the calling thread (its name goes into the line). */
    public static void note(Stage stage, long ns) {
        final String name = Thread.currentThread().getName();
        synchronized (LOCK) {
            noteLocked(stage, ns, name);
        }
    }

    private static void noteLocked(Stage stage, long ns, String name) {
        final int i = stage.ordinal();
        final long v = Math.max(0, ns);
        sum[i] += v;
        if (v > max[i]) max[i] = v;
        count[i]++;
        thread[i] = name;
    }

    /**
     * A live RAW frame reached the camera thread at {@code nowNs} (System.nanoTime), {@code lagNs} after its sensor timestamp
     * (Long.MIN_VALUE: unknown - another clock): the gap to the frame before it and the spread of the lag over the window (a
     * constant lag is the sensor's and the HAL's own time; its spread is frames delivered late or in bunches).
     */
    public static void arrived(long nowNs, long lagNs) {
        final String name = Thread.currentThread().getName();
        synchronized (LOCK) {
            if (lastArriveNs != 0 && nowNs - lastArriveNs < 1_000_000_000L) noteLocked(Stage.ARRIVE, nowNs - lastArriveNs, name);
            lastArriveNs = nowNs;
            if (lagNs != Long.MIN_VALUE) {
                lagMin = Math.min(lagMin, lagNs);
                lagMax = Math.max(lagMax, lagNs);
            }
        }
    }

    /** A new frame is drawn at {@code nowNs} (GL thread): the gap to the one before, late above {@link #LATE_NS}. */
    public static void shown(long nowNs) {
        final String name = Thread.currentThread().getName();
        synchronized (LOCK) {
            if (lastShownNs != 0 && nowNs - lastShownNs < 1_000_000_000L) {
                noteLocked(Stage.SHOWN, nowNs - lastShownNs, name);
                if (nowNs - lastShownNs > LATE_NS) late++;
            }
            lastShownNs = nowNs;
        }
    }

    /** A frame came while the hand-off thread still had the one before (dropped, counted with the dropped frames too). */
    public static void handoffBusy() {
        synchronized (LOCK) {
            busy++;
        }
    }

    /** What the last published frame was made of ("4080x3072 stride 8160 fmt 32 block 4 -> cell means 1020x768 swar"). */
    public static void source(String description) {
        synchronized (LOCK) {
            source = description == null ? "" : description;
        }
    }

    private static String ms(int i) {
        return count[i] == 0 ? "-" : String.format(Locale.ROOT, "%.1f/%.1f", sum[i] / 1e6 / count[i], max[i] / 1e6);
    }

    private static String name(int... stages) {
        for (int i : stages) if (thread[i] != null) return thread[i];
        return "-";
    }

    /** The line of the window since the last call (then a new window starts); the texts of {@link Stage} say what each number is. */
    public static String take() {
        synchronized (LOCK) {
            final int cam = Stage.CAMERA.ordinal(), ring = Stage.RING.ordinal(), arrive = Stage.ARRIVE.ordinal(),
                    hand = Stage.HANDOFF.ordinal(), bin = Stage.BIN.ordinal(), wait = Stage.WAIT.ordinal(),
                    upload = Stage.UPLOAD.ordinal(), meter = Stage.METER.ordinal(), draw = Stage.DRAW.ordinal(),
                    gpu = Stage.GPU.ordinal(), shown = Stage.SHOWN.ordinal(), wb = Stage.WB.ordinal(), wbCopy = Stage.WB_COPY.ordinal(),
                    motion = Stage.MOTION.ordinal(), pbo = Stage.PBO.ordinal();
            final String lag = lagMax >= lagMin ? String.format(Locale.ROOT, "%.1f", (lagMax - lagMin) / 1e6) : "-";
            final String line = String.format(Locale.ROOT,
                    "stages ms mean/max: cam[%s] %s ring %s motion %s wb copy %s arrive %s lag spread %s | wb[%s] %s x%d"
                            + " | hand-off[%s] %s bin[%s] %s%s busy %d | gl[%s] wait %s upload %s meter %s x%d draw %s%s shown %s late %d | %s",
                    name(cam, ring, arrive), ms(cam), ms(ring), ms(motion), ms(wbCopy), ms(arrive), lag, name(wb), ms(wb), count[wb],
                    name(hand), ms(hand), name(bin), ms(bin), count[pbo] == 0 ? "" : " pbo " + ms(pbo) + " x" + count[pbo], busy,
                    name(draw, upload, wait), ms(wait), ms(upload), ms(meter), count[meter], ms(draw),
                    count[gpu] == 0 ? "" : " gpu " + ms(gpu), ms(shown), late, source.isEmpty() ? "no frame" : source);
            java.util.Arrays.fill(sum, 0);
            java.util.Arrays.fill(max, 0);
            java.util.Arrays.fill(count, 0);
            lagMin = Long.MAX_VALUE;
            lagMax = Long.MIN_VALUE;
            late = busy = 0;
            return line;
        }
    }

    /** Tests: as after a process start. */
    static void reset() {
        synchronized (LOCK) {
            take();
            java.util.Arrays.fill(thread, null);
            lastArriveNs = lastShownNs = 0;
            source = "";
        }
    }
}
