package com.scamera.app.processing.color;

import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * P370 (B6): the white balance of a mosaic RAW viewfinder whose ISP runs no 3A (the X200 Ultra's ISZ modules under the vivo SDK
 * lease: the identity transform, gains frozen at R 2.434 / B 1.569 - MosaicNeutralResolver.ispIdle). The viewfinder showed those
 * frozen gains while the photo measures its own neutral (ScamProcessor.applyMosaicNeutral: the teacher's neutral blended with the
 * locus, else the P121 estimate on the camera's curve). Now, while the last results say idle: a measurement every
 * {@link #EVERY}th frame resolved the photo's way, smoothed in ln space with a {@link #DEAD_BAND} ln dead band; the viewfinder's
 * gains are 1 / that neutral (A2's table is looked up at it). Never the press or the photo's neutral. scam_dev.txt
 * "vf_mosaic_wb 0": the HAL's gains as before. One instance per CaptureController (its callback thread).
 */
public final class ViewfinderMosaicWb {
    /** A measurement every this many frames (8 - 16: the statistic is a 256-cell grid, cheap, but the frame is open). */
    static final int EVERY = 12;
    /** A neutral within this (ln r, ln b) of the shown one does not move it; a larger step moves it by {@link #STEP}. */
    static final double DEAD_BAND = 0.01, STEP = 0.5;
    /** Results kept for the idle test (MosaicNeutralResolver.ispIdle: at least 4 over 0.3 s of sensor time). */
    static final int KEEP = 24;
    static final long KEEP_NS = 1_000_000_000L;
    /** The lease's HAL gains are logged this often (C3: do they ever move?). */
    static final long LOG_MS = 5000;

    /** What a measurement of the frame gives (each asked for when needed): the photo's statistic and its P121 estimate (null: none). */
    public interface Measure {
        @Nullable MosaicNeutral.Stat stat();
        @Nullable MosaicNeutral.Result own();
        /** The result's neutral (R/G, 1, B/G), null: none. */
        @Nullable float[] hal();

        /**
         * P597: this measurement on a copy of the frame taken now (the camera's Image is open only during its callback), to be read
         * on another thread; null: none (measured on the caller, as before).
         */
        @Nullable default Measure detached() { return null; }
    }

    /**
     * P597 (owner, 2026-11-10: «лаги при включенной тетре остались»; scam_dev vf_mosaic_wb_async): once a neutral is shown, the
     * measurement goes to a thread of its own on a copy of the frame (Measure.detached) - the camera thread had resolve's
     * statistic and estimate on every 12th frame of the Tetra module, 196 k colour cells of 16 samples per phase each (the
     * grid of GRID cells takes every cell of a block-4 frame: 510 / 256 is 1): host 73 - 89 ms for MosaicNeutral.measure2 and 50 -
     * 59 ms for estimate on 4080 x 3072, four frames' time held on the camera thread 2.5 times a second. The same measurement
     * and the same smoothing, one frame later; the first neutral of a session is still measured at once (the viewfinder starts
     * with the photo's colour, not the HAL's frozen gains). One at a time: a frame due while one is measured keeps the shown one.
     */
    public static boolean async() {
        try {
            return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_mosaic_wb_async", true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** P597: a measurement on the worker (its copy is in use until it is done); bumped by {@link #reset()}: a later result is not shown. */
    private boolean measuring;
    private int generation;
    /** P597: tests run the measurement themselves (null: the worker thread). */
    static volatile java.util.concurrent.Executor workerHook;
    private static java.util.concurrent.ExecutorService worker;

    private static synchronized java.util.concurrent.Executor worker() {
        final java.util.concurrent.Executor hook = workerHook;
        if (hook != null) return hook;
        if (worker == null) worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "SCAMERA-vf-wb");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        return worker;
    }

    /** P597: whether a measurement runs on the worker now (tests). */
    synchronized boolean measuring() {
        return measuring;
    }

    /** What the idle test reads of the last results (P370 review: a sample each, not a second of CaptureResults). */
    private final ArrayDeque<MosaicNeutralResolver.IdleSample> recent = new ArrayDeque<>();
    private String camera;
    private int session = Integer.MIN_VALUE;
    private int frames;
    @Nullable private String idle;
    @Nullable private float[] shown;
    /**
     * P599: Long.MIN_VALUE overflowed {@code nowMs - loggedMs} to a negative number - the line below the 5 s test was never logged
     * (no «mosaic viewfinder camera» line in any log since P370), as P297's lookAt.
     */
    private long loggedMs = Long.MIN_VALUE / 2;

    /**
     * P599 (owner, 2026-11-10: «проблема с балансом белого с quad, tetra. Баланс белого сначала правильный, но если удержать какое-то
     * время камеру на месте он меняется на неверный, нам нужно чтобы он оставался таким какой был до этого изменения»; screen
     * recording 11:59:38, the 4.8x module: the helmet's white R/G 1.00 -> 1.04, B/G 1.01 -> 1.03 within 0.25 s at 2.25 s after the
     * phone came to rest, every time, neutral again as soon as it moved): the idle test (MosaicNeutralResolver.ispIdleOf: the HAL's
     * gains within 0.5 % over the last second) cannot tell an ISP that runs no 3A from a running AWB that converged on a still
     * scene - a held camera passed it ~1 s after the AWB settled, the next 12th frame then put this class's own neutral in place of
     * the HAL's (a step: the first neutral is taken whole), and the next movement made the gains move and the HAL's came back. An
     * ISP that runs no 3A never moves its gains (P366: R 2.4336 - 2.4365 / B 1.5693 over 7 s, a scene that changed; camera 2:
     * R 1.154 / B 3.095 in two sessions): gains that moved more than {@link #MOVE_LN} from the session's first settled ones prove a
     * running AWB, and the idle test is not taken for the rest of the session (camera and session) - the HAL's white balance stays,
     * held or not; the press carries the same proof (MosaicNeutralResolver.Press.awbMoves). scam_dev mosaic_awb_moves 0: as before.
     */
    static final double MOVE_LN = 0.01;
    /** P599: the first passing results of a session are not the base (a HAL's start values before its first frame's statistics). */
    static final int SETTLE_RESULTS = 3;
    private String movedCamera;
    private int movedSession = Integer.MIN_VALUE, settled;
    private double baseR = Double.NaN, baseB = Double.NaN, movedBy;
    private boolean awbMoves;

    /** P599: scam_dev mosaic_awb_moves (default on): a session whose HAL's AWB moved never counts as idle. */
    public static boolean movesKept() {
        try {
            return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("mosaic_awb_moves", true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** P599: one result of camera {@code id} in session {@code sessionId} into the movement proof. */
    private void observeAwb(String id, int sessionId, MosaicNeutralResolver.IdleSample sample) {
        if (!id.equals(movedCamera) || sessionId != movedSession) {
            movedCamera = id;
            movedSession = sessionId;
            awbMoves = false;
            settled = 0;
            baseR = baseB = Double.NaN;
            movedBy = 0;
        }
        if (awbMoves || sample == null || !sample.passes) return;
        if (++settled <= SETTLE_RESULTS) return;
        if (Double.isNaN(baseR)) {
            baseR = sample.lnR;
            baseB = sample.lnB;
            return;
        }
        final double by = Math.max(Math.abs(sample.lnR - baseR), Math.abs(sample.lnB - baseB));
        if (by > MOVE_LN) {
            awbMoves = true;
            movedBy = by;
            com.scamera.app.util.Log.i("SCAM_CAPTURE", String.format(Locale.ROOT, "mosaic viewfinder camera %s: the HAL's AWB"
                    + " moves (its gains %.1f %% off the session's first ones): its white balance is kept for the session, never taken"
                    + " for an idle ISP%s", id, 100 * by, movesKept() ? "" : " (mosaic_awb_moves 0: not used)"));
        }
    }

    /** P599: the HAL's AWB of camera {@code id} moved in session {@code sessionId} (the press: the photo keeps its white balance). */
    public synchronized boolean awbMoves(@Nullable String id, int sessionId) {
        return awbMoves && id != null && id.equals(movedCamera) && sessionId == movedSession;
    }

    public static boolean on() {
        try {
            return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_mosaic_wb", true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Forget the shown neutral (a new camera or session, a plain stream, the ISP running again). */
    public synchronized void reset() {
        recent.clear();
        frames = 0;
        idle = null;
        shown = null;
        generation++; // P597: a measurement on the worker now is not shown (it still holds its copy until it is done)
    }

    /**
     * One viewfinder frame of camera {@code id} in session {@code sessionId}: its result, whether its stream is a mosaic, the
     * measurement (asked only when due) and the resolver's inputs (prior, teacher's same-sensor cameras). Returns the neutral
     * {R/G, 1, B/G} the viewfinder shows, null for the HAL's gains.
     */
    @Nullable
    public synchronized float[] frame(String id, int sessionId, @Nullable CaptureResult result, boolean mosaic, Measure measure,
                                      @Nullable MosaicNeutral.Prior prior, @Nullable java.util.List<String> sameSensor, long nowMs) {
        if (!mosaic || result == null || id == null) {
            if (shown != null || !recent.isEmpty()) reset();
            return null;
        }
        if (!id.equals(camera) || sessionId != session) {
            reset();
            camera = id;
            session = sessionId;
        }
        final MosaicNeutralResolver.IdleSample sample = MosaicNeutralResolver.IdleSample.of(result);
        observeAwb(id, sessionId, sample); // P599
        recent.addLast(sample);
        final Long ts = sample.timestamp();
        while (recent.size() > KEEP) recent.removeFirst();
        if (ts != null) {
            // only the last second of sensor time: an older (running) ISP's result never holds the idle test back
            while (!recent.isEmpty()) {
                final Long first = recent.peekFirst().timestamp();
                if (first != null && ts - first <= KEEP_NS) break;
                recent.removeFirst();
            }
        }
        if (frames++ % EVERY != 0) return shown;
        idle = MosaicNeutralResolver.ispIdleOf(recent);
        // P599: a converged AWB holds its gains too - a session whose AWB moved keeps the HAL's white balance
        final String heldBy = idle != null && awbMoves && movesKept() ? idle : null;
        if (heldBy != null) idle = null;
        if (nowMs - loggedMs >= LOG_MS) {
            loggedMs = nowMs;
            final android.hardware.camera2.params.RggbChannelVector g = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            com.scamera.app.util.Log.i("SCAM_CAPTURE", String.format(Locale.ROOT,
                    "mosaic viewfinder camera %s: HAL gains %s, %s, shown neutral %s", id,
                    g == null ? "none" : String.format(Locale.ROOT, "R %.4f / G %.4f / B %.4f", g.getRed(), 0.5f * (g.getGreenEven() + g.getGreenOdd()), g.getBlue()),
                    idle == null ? heldBy != null ? String.format(Locale.ROOT, "the HAL's AWB moved %.1f %% in this session (its gains"
                            + " held now: a converged AWB, not an idle ISP)", 100 * movedBy) : "the ISP may run its 3A" : idle,
                    shown == null ? "the HAL's" : String.format(Locale.ROOT, "%.3f / %.3f", shown[0], shown[2])));
        }
        if (idle == null) {
            shown = null;
            return null;
        }
        // P597: once a neutral is shown, the measurement on its own thread (the frame copied now)
        if (shown != null && async()) {
            if (measuring) return shown;
            final Measure copy = measure.detached();
            if (copy != null) {
                measuring = true;
                final int gen = generation;
                final Runnable work = () -> {
                    float[] target = null;
                    final long start = System.nanoTime();
                    try {
                        target = resolve(id, copy, prior, sameSensor);
                    } catch (RuntimeException e) {
                        com.scamera.app.util.Log.w("SCAM_CAPTURE", "mosaic viewfinder white balance: " + e);
                    } finally {
                        synchronized (ViewfinderMosaicWb.this) {
                            measuring = false;
                            if (gen == generation && target != null && shown != null) shown = smooth(shown, target);
                        }
                        com.scamera.app.processing.VfStages.note(com.scamera.app.processing.VfStages.Stage.WB,
                                System.nanoTime() - start);
                    }
                };
                try {
                    worker().execute(work);
                } catch (RuntimeException rejected) {
                    work.run(); // no thread: here, as before
                }
                return shown;
            }
        }
        final long start = System.nanoTime();
        final float[] target = resolve(id, measure, prior, sameSensor);
        if (target != null) shown = smooth(shown, target);
        com.scamera.app.processing.VfStages.note(com.scamera.app.processing.VfStages.Stage.WB, System.nanoTime() - start);
        return shown;
    }

    /**
     * The photo's neutral for a frame without a route in an idle ISP session (ScamProcessor.applyMosaicNeutral): the teacher's neutral
     * blended with the locus when it has learned enough (MosaicTeacher.use, MosaicNeutral.taught; scam_dev "mosaic_awb_teacher_use
     * 0": never), else the P121 estimate on the curve; null for neither.
     */
    @Nullable
    static float[] resolve(String id, Measure measure, @Nullable MosaicNeutral.Prior prior, @Nullable java.util.List<String> sameSensor) {
        final MosaicNeutral.Stat stat = measure.stat();
        if (stat != null && MosaicTeacher.on() && MosaicTeacher.useMode() != MosaicTeacher.USE_NEVER) {
            final MosaicTeacher.Use teacher = MosaicTeacher.use(id, stat.r, stat.b, sameSensor);
            if (teacher != null && teacher.usable()) return MosaicNeutral.taught(id, stat, prior, teacher).neutral.clone();
        }
        final MosaicNeutral.Result own = measure.own();
        if (own == null) return null;
        // P315 (the photo's rule): an estimate far off every learned illuminant is not taken as one
        final float[] hal = measure.hal();
        if (hal != null && MosaicNeutralResolver.offLocus(id, own.learnedDistance, hal, prior) != null) return null;
        return own.neutral.clone();
    }

    /** {@code shown} moved toward {@code target} in ln space: not within DEAD_BAND, by STEP of the way beyond it; green 1. */
    static float[] smooth(@Nullable float[] shown, float[] target) {
        final double tr = Math.log(target[0] / target[1]), tb = Math.log(target[2] / target[1]);
        if (shown == null) return new float[]{(float) Math.exp(tr), 1f, (float) Math.exp(tb)};
        final double sr = Math.log(shown[0] / shown[1]), sb = Math.log(shown[2] / shown[1]);
        final double dr = tr - sr, db = tb - sb;
        if (Math.hypot(dr, db) <= DEAD_BAND) return shown;
        return new float[]{(float) Math.exp(sr + STEP * dr), 1f, (float) Math.exp(sb + STEP * db)};
    }

    /** The viewfinder's white-balance gains {R, G, B} of a neutral (green 1). */
    public static float[] gains(float[] neutral) {
        return new float[]{neutral[1] / neutral[0], 1f, neutral[1] / neutral[2]};
    }
}
