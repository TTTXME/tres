package com.scamera.app.control;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Size;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.ui.camera.views.ExposureSquareView;
import com.scamera.app.ui.camera.views.FocusCircleView;
import com.scamera.app.ui.camera.views.viewfinder.GLPreview;
import com.scamera.app.util.Log;

/**
 * Tap-to-focus running a sequenced 3A protocol on top of Camera2
 * (see TOUCH_FOCUS_CORRECTED.md for the authoritative spec):
 * <ol>
 *   <li>the tap point is rotated by the live sensor orientation and mapped into the
 *       current zoom crop region (not the full active array), producing AF/AE
 *       metering rectangles — a square of half-side 6.125% of the smaller crop
 *       dimension, weight 122, clamped to the crop bounds. The AWB region array is
 *       never written on tap;</li>
 *   <li>regions ride on every request: the repeating preview request and each
 *       one-shot trigger request;</li>
 *   <li>AF trigger sequence: CANCEL one-shot with the new regions → repeating
 *       update (trigger IDLE) so the regions are live in the preview → wait for the
 *       cancel <em>acknowledgment</em> (AF_STATE in a per-mode membership set) →
 *       [continuous modes only, divergence: wait for a complete passive scan cycle
 *       — PASSIVE_SCAN observed, then settled — with a {@link #PASSIVE_DWELL_MS}
 *       time fallback when no scan appears; P155: without a scan the START goes in
 *       AF_MODE_AUTO, see {@link #startsActiveScan}] → bare START one-shot, sent
 *       unconditionally after the passive phase (no skip path, no second CANCEL
 *       inside the sequence) → repeating update → wait for the trigger
 *       acknowledgment (AF_STATE in {ACTIVE_SCAN, FOCUSED_LOCKED}). Both waits are
 *       acknowledgments, not convergence guarantees; partial and completed results
 *       both feed them. Beyond the passive phase the sequence purely trusts
 *       AF_STATE: instant locks are valid acks, no retries — verification of the
 *       actual image lives downstream (the still-capture path re-runs lock +
 *       precapture with the inherited region);</li>
 *   <li>acknowledgment is gated by submission identity: every request of the tap
 *       carries a per-tap tag, and only tag-matching results may ack (lenient
 *       region readback only as fallback when the tag is missing). One 2 s /
 *       30-frames-per-watcher budget per tap, shared by both waits. Watchers
 *       accept membership matches only — a null state consumes a frame and never
 *       resolves. Deadline resolution is non-destructive: the sequence completes
 *       as unknown, regions persist, nothing is reset or re-triggered
 *       (NOT_FOCUSED_LOCKED rides to the deadline the same way);</li>
 *   <li>everything is gated on device capabilities: AF/AE mode lists,
 *       CONTROL_MAX_REGIONS_*, and LENS_INFO_MINIMUM_FOCUS_DISTANCE (fixed-focus
 *       lenses are metered on but never triggered);</li>
 *   <li>AUTO fallback (divergence, user-requested): when {@link #AUTO_FALLBACK_TAPS}
 *       consecutive continuous-mode taps fail to be acknowledged, tap-to-focus
 *       switches to trigger-driven AF_MODE_AUTO for the camera session and retries
 *       the current tap — for HALs whose continuous AF cannot lock with a tap
 *       region.</li>
 * </ol>
 */
public class TouchFocus {
    private static final String TAG = "TouchFocus";
    /** How long a plain tap's ring stays, and how long the split ring / square stay bright. */
    private static final int RING_SHOWN_MS = 3_000;

    /** Each tap's acknowledgement budget: both waits together, in ms and in frames. */
    static final long ACK_BUDGET_MS = 2_000L;
    static final int ACK_BUDGET_FRAMES = 30;
    /** The tap region: a square of 2 x this share of the crop's short side, at this metering weight. */
    static final float REGION_HALF_SIDE_RATIO = 0.06125f;
    static final int REGION_WEIGHT = 122;
    // Divergence from the reference (user-requested; rev3 advises against): after the
    // cancel-ack, continuous modes wait for a COMPLETE passive scan cycle — PASSIVE_SCAN
    // observed, then settled — before the locking START, so the lens actually sweeps the
    // new region. PASSIVE_DWELL_MS is only the fallback when no scan is ever observed.
    // Never locks mid-scan; bounded by the shared 2 s budget.
    static final long PASSIVE_DWELL_MS = 400L;

    // the tap's phases: nothing in flight, CANCEL sent (its acknowledgement awaited), START sent, the passive scan awaited
    private static final int PHASE_IDLE = 0, PHASE_CANCEL_SENT = 1, PHASE_START_SENT = 2, PHASE_PASSIVE_SCAN = 3;

    // Acknowledgment membership sets — never predicates, never resolved by null.
    /** After CANCEL, before START — AUTO mode (PASSIVE_UNFOCUSED mirrors the reference; unreachable in AUTO). */
    static final int[] CANCEL_SEEN_AUTO = {CameraMetadata.CONTROL_AF_STATE_INACTIVE, CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED};
    /** After CANCEL, before START — continuous modes. PASSIVE_UNFOCUSED is included
     *  (divergence pending creator confirmation): HALs whose passive AF cannot settle
     *  report it indefinitely after a CANCEL, and a tag-gated PASSIVE_UNFOCUSED still
     *  proves the CANCEL was processed. The reference's AUTO set also lists it. */
    static final int[] CANCEL_SEEN_CONTINUOUS = {CameraMetadata.CONTROL_AF_STATE_INACTIVE, CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED};
    /** After START: the lens entered its active scan, or locked immediately.
     *  Deliberately excludes INACTIVE (stale for 1-2 frames after the trigger)
     *  and NOT_FOCUSED_LOCKED (a failed lock rides to the deadline as unknown). */
    static final int[] START_SEEN = {CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN, CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED};

    private final CaptureController cam;
    private final GLPreview finder;
    private final View ringView;
    private final Runnable hideRing = this::fadeOutRing;
    /** A tap's regions are in the viewfinder's request (read by SubjectFocus and CaptureController). */
    public volatile boolean isTouchFocus;
    private final OnTouchListener focusListener = (v, event) -> {
        if (split()) return drag(v, event, true);
        v.performClick();
        clearTap();
        restoreCameraMetering();
        return true;
    };

    // P142 «Раздельные фокус и экспозиция» (PreferenceKeys.isSplitAfAe, off by default): a tap puts the focus ring and the
    // exposure square around it at the tapped point (both regions there, as before); each is then dragged on its own - the AF
    // regions follow the ring (a new focus sequence when it is let go), the AE regions the square (the repeating request while
    // it moves, no AF trigger). A tap on the ring resets to auto as before, a tap on the square brings it back to the ring,
    // a tap elsewhere puts both there. They stay on screen (dimmed) while they mean something.
    static final long METER_INTERVAL_MS = 80;
    static final float SQUARE_SCALE = 1.7f;
    /**
     * P166 (owner, 2026-10-16: «квадрат сделай на 35% меньше, кружок фокусировки на 15% меньше»): the split ring and square,
     * on screen and as metering regions, as shares of what they were (the ring of a plain tap stays as it is).
     */
    static final float SPLIT_RING_SCALE = 0.85f, SPLIT_SQUARE_SCALE = 0.65f;
    /** The ring's own size (its layout's), before the split mode resizes it. */
    private int ringBase = -1;
    @Nullable
    private ExposureSquareView exposureView;
    @Nullable
    private MeteringRectangle lastAeRegion;
    private float dragStartX, dragStartY, dragViewX, dragViewY;
    private boolean dragging;
    private long lastMeterAt;
    private final Runnable dimSplitLater = this::dimSplit;

    private final Object phaseLock = new Object();
    private int tapPhase = PHASE_IDLE;
    // Invalidate in-flight continuations (timeouts, ack waits) on re-tap or reset.
    private int tapGeneration;
    // One shared deadline per tap: both acknowledgment waits spend the same budget.
    private long tapDeadline;
    // Dwell deadline (continuous modes): fallback START time if no passive scan appears.
    private long dwellDeadline;
    // A PASSIVE_SCAN was observed on a current-tap frame since the cancel-ack.
    private boolean sawPassiveScan;
    // Divergence (user-requested): HALs whose continuous-mode AF cannot lock with a
    // tap region (passive never settles; trigger ends NOT_FOCUSED_LOCKED or silent)
    // get tap-to-focus switched to AF_MODE_AUTO after this many consecutive failed
    // taps. Scoped to this TouchFocus instance, i.e. the camera session.
    static final int AUTO_FALLBACK_TAPS = 3; // failed taps in a row
    private int failedTaps;
    private boolean autoForTaps;
    @Nullable
    private MeteringRectangle retryRegion;
    // A NOT_FOCUSED_LOCKED was observed on a current-tap frame during the start-ack
    // wait: the trigger executed but the lock failed, leaving the lens frozen and
    // passive AF suspended until a CANCEL releases it.
    private boolean sawFailedLock;
    // Submission identity for the ack gate: every request built after the tap starts
    // (one-shots and repeating) carries this tag, which never leaves the app process,
    // so results gate by exact identity on every HAL.
    private Integer tapTag;
    // The region array written with this tap — only the LENIENT fallback of the ack
    // gate (spec rev2: a present region array counts as ours even when the HAL
    // normalized the rect; tags are the primary gate).
    @Nullable
    private MeteringRectangle[] tapRegions;
    private int tapAfMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
    // Diagnostics for the "FOCUSED_LOCKED but unfocused on region" case: how long
    // the HAL actually scanned, and whether the lens physically moved. A fake scan
    // reports ACTIVE_SCAN then locks with an unchanged LENS_FOCUS_DISTANCE.
    private volatile boolean watchScan;
    private volatile long scanAckMs;
    private volatile float lensAtScanAck;
    private final AckWait cancelWait = new AckWait("AF/cancel-ack");
    private final AckWait startWait = new AckWait("AF/start-ack");
    // Last AF state logged for the active sequence (Integer.MIN_VALUE = none/null).
    private int lastLoggedAf = Integer.MIN_VALUE;
    private Runnable budgetTimeout;
    private boolean budgetOnCameraThread;

    public TouchFocus(CaptureController cam, View focusCircle, GLPreview finder) {
        this.cam = cam;
        this.ringView = focusCircle;
        this.finder = finder;
        ringView.setOnTouchListener(focusListener);
        clearTap();
    }

    /**
     * SIMPLE_UI §1: a long press locks the focus and the exposure at a point (the focus sequence there, then AE lock once the
     * metering settled) until a tap elsewhere, a reset or a new session; the camera screen shows the «Закреплено» pill.
     */
    static final long LOCK_AE_SETTLE_MS = 600;

    public interface LockListener {
        void locked(boolean on);
    }

    @Nullable private LockListener lockListener;
    private volatile boolean aeAfLocked;
    private final Runnable holdExposure = this::holdExposure;

    public void setLockListener(@Nullable LockListener listener) {
        lockListener = listener;
    }

    public boolean isLocked() {
        return aeAfLocked;
    }

    /** Focus and exposure locked at view point (fx, fy). */
    public void lockAt(float fx, float fy) {
        processTouchToFocus(fx, fy);
        if (regionAt(fx, fy) == null) return;
        aeAfLocked = true;
        // the ring stays (dimmed) while the lock holds
        ringView.removeCallbacks(hideRing);
        ringView.removeCallbacks(dimSplitLater);
        ringView.postDelayed(dimSplitLater, RING_SHOWN_MS);
        ringView.removeCallbacks(holdExposure);
        ringView.postDelayed(holdExposure, LOCK_AE_SETTLE_MS);
        notifyLock(true);
        Log.d(TAG, "focus and exposure locked at " + fx + "," + fy);
    }

    private void holdExposure() {
        if (!aeAfLocked) return;
        synchronized (phaseLock) {
            if (burstOwnsCamera()) return;
            CaptureRequest.Builder req = previewReq();
            if (req == null) return;
            req.set(CaptureRequest.CONTROL_AE_LOCK, true);
            cam.rebuildPreviewBuilder();
        }
    }

    /** The lock goes (a tap elsewhere, a reset): AE unlocked in the builder; the caller's sequence / reset rebuilds it. */
    private void dropLock() {
        if (!aeAfLocked) return;
        aeAfLocked = false;
        ringView.removeCallbacks(holdExposure);
        CaptureRequest.Builder req = previewReq();
        if (req != null) req.set(CaptureRequest.CONTROL_AE_LOCK, false);
        notifyLock(false);
        Log.d(TAG, "focus and exposure lock released");
    }

    private void notifyLock(boolean on) {
        LockListener l = lockListener;
        if (l != null) ringView.post(() -> l.locked(on));
    }

    public void processTouchToFocus(float fx, float fy) {
        dropLock();
        final boolean split = split();
        MeteringRectangle region = regionAt(fx, fy, split ? SPLIT_RING_SCALE : 1f);
        MeteringRectangle aeRegion = split ? regionAt(fx, fy, SPLIT_SQUARE_SCALE) : region;
        if (region == null || aeRegion == null) {
            Log.w(TAG, "tap ignored: no camera or viewfinder size yet");
            return;
        }
        ringView.removeCallbacks(hideRing);
        ringView.removeCallbacks(dimSplitLater);
        ringView.post(() -> {
            sizeRing(split);
            placeRing(fx, fy);
            if (split) showExposureSquare(fx, fy);
            else hideExposureSquare();
        });
        // P110: the «Движение» shutter ceiling lets the AE meter the tapped point first
        if (cam.getParamController() != null) cam.getParamController().motionMeteringTap();
        beginTap(region, aeRegion);
        if (split) ringView.postDelayed(dimSplitLater, RING_SHOWN_MS);
        else ringView.postDelayed(hideRing, RING_SHOWN_MS);
    }

    private void markTapActive(boolean on) {
        isTouchFocus = on;
    }

    /** P142: the option is on. */
    boolean split() {
        return PreferenceKeys.isSplitAfAe();
    }

    /** The ring's own size: its layout's (the first one seen), else its width. */
    private int ringBase() {
        if (ringBase <= 0) {
            ViewGroup.LayoutParams lp = ringView.getLayoutParams();
            ringBase = lp != null && lp.width > 0 ? lp.width : Math.max(ringView.getWidth(), 1);
        }
        return ringBase;
    }

    /** P166: the ring at its split size (SPLIT_RING_SCALE of its own) or its own size. */
    private void sizeRing(boolean split) {
        ViewGroup.LayoutParams lp = ringView.getLayoutParams();
        if (lp == null) return;
        int size = split ? Math.max(1, Math.round(ringBase() * SPLIT_RING_SCALE)) : ringBase();
        if (lp.width == size && lp.height == size) return;
        lp.width = size;
        lp.height = size;
        ringView.setLayoutParams(lp);
    }

    /** P142: the exposure square (made on first use, beneath the ring in the ring's parent), null before a split tap. */
    @Nullable
    View exposureSquare() {
        return exposureView;
    }

    private ExposureSquareView ensureExposureSquare() {
        if (exposureView != null) return exposureView;
        ViewGroup parent = (ViewGroup) ringView.getParent();
        ExposureSquareView view = new ExposureSquareView(ringView.getContext());
        int side = Math.max(1, Math.round(ringBase() * SQUARE_SCALE * SPLIT_SQUARE_SCALE));
        view.setVisibility(View.INVISIBLE);
        view.setOnTouchListener((v, event) -> drag(v, event, false));
        if (parent != null) parent.addView(view, Math.max(0, parent.indexOfChild(ringView)), new ViewGroup.LayoutParams(side, side));
        exposureView = view;
        return view;
    }

    private void showExposureSquare(float fx, float fy) {
        final ExposureSquareView square = ensureExposureSquare();
        final int side = square.getLayoutParams() != null ? square.getLayoutParams().width : square.getWidth();
        square.setX(fx - side / 2f);
        square.setY(fy - side / 2f);
        square.setAlpha(1f);
        square.setVisibility(View.VISIBLE);
        ringView.setAlpha(1f);
    }

    private void hideExposureSquare() {
        if (exposureView != null) exposureView.setVisibility(View.GONE);
    }

    private void dimSplit() {
        ringView.animate().alpha(0.55f).setDuration(250).start();
        if (exposureView != null) exposureView.animate().alpha(0.55f).setDuration(250).start();
    }

    private static float centreX(View v) {
        int w = v.getLayoutParams() != null && v.getLayoutParams().width > 0 ? v.getLayoutParams().width : v.getWidth();
        return v.getX() + w / 2f;
    }

    private static float centreY(View v) {
        int h = v.getLayoutParams() != null && v.getLayoutParams().height > 0 ? v.getLayoutParams().height : v.getHeight();
        if (h <= 0) h = v.getLayoutParams() != null && v.getLayoutParams().width > 0 ? v.getLayoutParams().width : v.getWidth();
        return v.getY() + h / 2f;
    }

    /** P142: drags the ring (ring) or the square; a touch that does not move is a tap on it. */
    boolean drag(View v, MotionEvent event, boolean ring) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) grab(v, event);
        else if (action == MotionEvent.ACTION_MOVE) follow(v, event, ring);
        else if (action == MotionEvent.ACTION_UP) release(v, ring);
        else if (action == MotionEvent.ACTION_CANCEL) dragging = false;
        return true;
    }

    private void grab(View v, MotionEvent event) {
        dragStartX = event.getRawX();
        dragStartY = event.getRawY();
        dragViewX = v.getX();
        dragViewY = v.getY();
        dragging = false;
        ringView.removeCallbacks(dimSplitLater);
        ringView.setAlpha(1f);
        if (exposureView != null) exposureView.setAlpha(1f);
    }

    /** Past the touch slop the view follows the finger, its centre kept on the viewfinder; the square meters as it goes. */
    private void follow(View v, MotionEvent event, boolean ring) {
        final float dx = event.getRawX() - dragStartX, dy = event.getRawY() - dragStartY;
        if (!dragging && Math.hypot(dx, dy) < ViewConfiguration.get(v.getContext()).getScaledTouchSlop()) return;
        dragging = true;
        final float halfW = centreX(v) - v.getX(), halfH = centreY(v) - v.getY();
        final float maxX = Math.max(1, finder.getWidth()), maxY = Math.max(1, finder.getHeight());
        v.setX(Math.max(0, Math.min(maxX, dragViewX + dx + halfW)) - halfW);
        v.setY(Math.max(0, Math.min(maxY, dragViewY + dy + halfH)) - halfH);
        if (!ring && SystemClock.elapsedRealtime() - lastMeterAt >= METER_INTERVAL_MS) meterAtSquare();
    }

    /** A touch that never moved is a tap on the view (the ring: back to auto, the square: back to the ring). */
    private void release(View v, boolean ring) {
        if (dragging) {
            if (ring) refocusAtRing();
            else meterAtSquare();
        } else {
            v.performClick();
            if (!ring) joinSquare();
            else {
                clearTap();
                restoreCameraMetering();
            }
        }
        dragging = false;
        ringView.postDelayed(dimSplitLater, RING_SHOWN_MS);
    }

    /** P142: the ring was let go: a focus sequence on its region, the square's region kept for the AE. */
    private void refocusAtRing() {
        MeteringRectangle af = regionAt(centreX(ringView), centreY(ringView), SPLIT_RING_SCALE);
        if (af == null) return;
        MeteringRectangle ae = exposureView != null && exposureView.getVisibility() == View.VISIBLE
                ? regionAt(centreX(exposureView), centreY(exposureView), SPLIT_SQUARE_SCALE) : null;
        beginTap(af, ae != null ? ae : lastAeRegion != null ? lastAeRegion : af);
    }

    /** P142: a tap on the square: back to the ring, the AE on the focus point again. */
    private void joinSquare() {
        if (exposureView == null) return;
        showExposureSquare(centreX(ringView), centreY(ringView));
        meterAtSquare();
    }

    /** P142: the AE regions to the square's place: the repeating request only (no AF trigger, the AF regions stay). */
    private void meterAtSquare() {
        if (exposureView == null) return;
        MeteringRectangle ae = regionAt(centreX(exposureView), centreY(exposureView), SPLIT_SQUARE_SCALE);
        if (ae == null) return;
        lastMeterAt = SystemClock.elapsedRealtime();
        if (cam.getParamController() != null) cam.getParamController().motionMeteringTap();
        synchronized (phaseLock) {
            if (burstOwnsCamera()) return;
            CaptureRequest.Builder req = previewReq();
            CameraCharacteristics lens = liveLens();
            if (req == null || lens == null) return;
            lastAeRegion = ae;
            if (manualExposure(req)) return;   // P521: a manual exposure takes no metering
            if (maxRegions(lens, CameraCharacteristics.CONTROL_MAX_REGIONS_AE) <= 0) return;
            markTapActive(true);
            req.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{ae});
            cam.rebuildPreviewBuilder();
        }
    }

    /** SIMPLE_UI §1: told where the focus ring shows (centre, radius in its parent) and when it goes (the viewfinder's sun). */
    public interface RingListener {
        void shown(float cx, float cy, float radius);

        void hidden();
    }

    @androidx.annotation.Nullable private RingListener ringListener;

    public void setRingListener(@androidx.annotation.Nullable RingListener listener) {
        ringListener = listener;
    }

    private void placeRing(float fx, float fy) {
        // the layout's size: a split resize has not been measured yet
        ViewGroup.LayoutParams lp = ringView.getLayoutParams();
        int w = lp != null && lp.width > 0 ? lp.width : ringView.getMeasuredWidth();
        int h = lp != null && lp.height > 0 ? lp.height : ringView.getMeasuredHeight();
        ringView.setX(fx - w / 2.0f);
        ringView.setY(fy - h / 2.0f);
        ringView.setVisibility(View.VISIBLE);
        if (ringListener != null) ringListener.shown(fx, fy, Math.max(w, h) / 2f);
        ringView.animate().scaleY(1.2f).scaleX(1.2f).setDuration(250)
                .withEndAction(() -> ringView.animate().scaleY(1f).scaleX(1f).setDuration(250).start())
                .start();
    }

    /**
     * Sets state of focus circle view based on AF State. Also closes the scan-watch
     * diagnostic: time from the ACTIVE_SCAN acknowledgment to the first live
     * FOCUSED_LOCKED, with lens motion across it.
     */
    /** The preview's AF state for the focus ring; a lock right after an observed scan logs how long the scan took. */
    public void setState(@Nullable Integer afState) {
        if (afState == null) return;
        if (watchScan && afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED) {
            watchScan = false;
            Log.w(TAG, "lock after " + (SystemClock.elapsedRealtime() - scanAckMs) + "ms of scan; lens "
                    + lensAtScanAck + " -> " + cam.focusDiopters + " diopters");
        }
        ((FocusCircleView) ringView).setAfState(afState);
    }

    /** Back to the camera's own AF / AE (the settings' modes, the template's regions). */
    private void restoreCameraMetering() {
        cam.restore3A();
    }

    // ------------------------------------------------------------------ mapping

    /** A viewfinder tap as a metering region inside the current zoom crop. */
    @Nullable
    private MeteringRectangle regionAt(float viewX, float viewY) {
        return regionAt(viewX, viewY, 1f);
    }

    /** {@code sizeScale}: the region's side as a share of the tap region's (P166: the split ring and square). */
    @Nullable
    private MeteringRectangle regionAt(float viewX, float viewY, float sizeScale) {
        final CameraCharacteristics lens = liveLens();
        final Rect space = lens == null ? null : tapSpace(lens);
        if (space == null) return null;
        final Integer facing = lens.get(CameraCharacteristics.LENS_FACING);
        final boolean front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        final int turn = screenTurn();
        final int sensor = cam.mSensorOrientation;
        warnIfPreCorrectionDiffers(lens);
        final int w = finder.getWidth(), h = finder.getHeight();
        final int[] square = mapTapToCrop(viewX, viewY, w, h, space.left, space.top, space.width(), space.height(), sensor, turn, front, sizeScale);
        if (square == null) return null;
        Log.d(TAG, "tap (" + (int) viewX + "," + (int) viewY + ")v" + w + "x" + h + " so=" + sensor + " rot=" + turn + " mirror=" + front
                + " space=" + space.width() + "x" + space.height() + " -> rect=[" + square[0] + "," + square[1] + " " + square[2] + "x" + square[2] + "]");
        return new MeteringRectangle(square[0], square[1], square[2], square[2], REGION_WEIGHT);
    }

    /**
     * The screen's turn on the gravity scale (90 = upright portrait): the display's rotation while the view is attached (right
     * even flat on a table), else the gravity sensor's.
     */
    private int screenTurn() {
        try {
            final Display display = finder.getDisplay();
            if (display != null) return 90 + 90 * display.getRotation();
        } catch (Exception detached) {
            // no display: the sensor's below
        }
        final Gravity gravity = ScameraApp.getGravity();
        return gravity == null ? 90 : gravity.getRotation();
    }

    /**
     * Diagnostic (spec rev3, Part III): when the precorrection active array differs from the active array, many HALs expect
     * region coordinates in precorrection space - the strongest single candidate for "real scan, wrong distance". Logged only.
     */
    private static void warnIfPreCorrectionDiffers(CameraCharacteristics lens) {
        final Rect active = lens.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        final Rect pre = lens.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        if (active == null || pre == null || active.equals(pre)) return;
        Log.w(TAG, "precorrection array " + pre + " differs from active array " + active
                + ": the HAL may want tap regions in precorrection coordinates (try that mapping)");
    }

    /**
     * The region a tap maps into: the live zoom crop of the newest preview request (the point stays under the finger at any
     * zoom), else the active array, else the whole pixel array; null when the lens says none of them.
     */
    @Nullable
    private static Rect tapSpace(CameraCharacteristics lens) {
        final CaptureRequest last = CaptureController.mPreviewCaptureRequest;
        final Rect zoomed = last == null ? null : last.get(CaptureRequest.SCALER_CROP_REGION);
        if (zoomed != null) return zoomed;
        final Rect active = lens.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active != null) return active;
        final Size pixels = lens.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        return pixels == null ? null : new Rect(0, 0, pixels.getWidth(), pixels.getHeight());
    }

    /**
     * Pure view-tap -> crop-rectangle geometry (JVM-testable). The sensor image is turned (sensorOrientation + gravityRotation
     * + 270) % 360 clockwise to stand upright on screen, so the opposite turn takes a tap back into sensor space; a front
     * preview is mirrored first.
     *
     * @return {@code [x, y, side]} of the square region, fully clamped inside the crop
     */
    @Nullable
    static int[] mapTapToCrop(float tapX, float tapY, int finderW, int finderH, int left, int top, int width, int height,
                              int sensorTurn, int screenTurn, boolean mirror) {
        return mapTapToCrop(tapX, tapY, finderW, finderH, left, top, width, height, sensorTurn, screenTurn, mirror, 1f);
    }

    /** {@link #mapTapToCrop} with the region's side times {@code sizeScale} (P166), around the same point. */
    @Nullable
    static int[] mapTapToCrop(float viewX, float viewY, int viewWidth, int viewHeight, int cropLeft, int cropTop, int cropWidth,
                              int cropHeight, int sensorOrientation, int gravityRotation, boolean mirrored, float sizeScale) {
        if (Math.min(Math.min(viewWidth, viewHeight), Math.min(cropWidth, cropHeight)) <= 0) return null;
        final int degrees = Math.floorMod(90 - sensorOrientation - gravityRotation, 360);
        final double angle = Math.toRadians(degrees);
        final double cos = Math.cos(angle), sin = Math.sin(angle);
        // the tap about the view's centre (0..1 each way; the front preview mirrored across)
        final float across = (mirrored ? 1f - viewX / viewWidth : viewX / viewWidth) - 0.5f;
        final float down = viewY / viewHeight - 0.5f;
        // turned clockwise as seen on a y-down screen, back to 0..1
        final float sx = (float) (across * cos - down * sin) + 0.5f;
        final float sy = (float) (across * sin + down * cos) + 0.5f;
        final int side = Math.max(2, Math.round(REGION_HALF_SIDE_RATIO * 2f * sizeScale * Math.min(cropWidth, cropHeight)));
        final int left = clamp(cropLeft + Math.round(sx * cropWidth) - side / 2, cropLeft, cropLeft + cropWidth - side);
        final int top = clamp(cropTop + Math.round(sy * cropHeight) - side / 2, cropTop, cropTop + cropHeight - side);
        return new int[]{left, top, side};
    }

    /** {@code value} into [low, high], low winning when the range is empty (a region larger than the crop). */
    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }

    // ------------------------------------------------------------------ sequence

    /** P521: the preview runs a manual exposure («Про»: ISO and shutter both set, the AE off). */
    static boolean manualExposure(CaptureRequest.Builder req) {
        Integer ae = req.get(CaptureRequest.CONTROL_AE_MODE);
        return ae != null && ae == CameraMetadata.CONTROL_AE_MODE_OFF;
    }

    /**
     * A tap's focus: its regions (AF; AE unless the exposure is manual) and modes into the viewfinder's request, which is tagged
     * with the tap (the acknowledgement gate's submission identity, spec rev2), then a CANCEL once with them, the repeating
     * request with IDLE, and the wait for the CANCEL's acknowledgement (normally 1-2 frames on the INACTIVE after it). A lens
     * that cannot focus meters there and is never triggered.
     */
    private void beginTap(MeteringRectangle region, MeteringRectangle aeRegion) {
        final CaptureRequest.Builder req = previewReq();
        final CameraCharacteristics lens = liveLens();
        if (req == null || lens == null) {
            Log.w(TAG, "tap focus with no running camera");
            return;
        }
        synchronized (phaseLock) {
            if (burstOwnsCamera()) return;
            tapGeneration++; // whatever an earlier tap or reset left in flight is void now
            endTapLocked();
            tapTag = tapGeneration;
            req.setTag(tapTag);
            // Modes the lens does not list fall back to the current ones; after the AUTO fallback engaged (continuous touch
            // focus failed again and again on this HAL) the tap runs in trigger-driven AF_MODE_AUTO
            final int afNow = valueOr(req.get(CaptureRequest.CONTROL_AF_MODE), tapAfMode);
            final int afWanted = autoForTaps ? CameraMetadata.CONTROL_AF_MODE_AUTO : PreferenceKeys.getAfMode();
            final int afMode = supportedMode(lens, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, afWanted, afNow);
            // P521 (owner, 2026-11-06, video: ISO and shutter fixed in «Про», a tap still changed the exposure): a manual exposure
            // (the preview's AE off) stays manual - the tap focuses and leaves the exposure and its metering alone
            final boolean manual = manualExposure(req);
            final Integer aeNow = req.get(CaptureRequest.CONTROL_AE_MODE);
            // P590: the preview's own automatic mode (its flash mode: ON_AUTO_FLASH / ON_ALWAYS_FLASH), else ON
            final int aeMode = manual ? CameraMetadata.CONTROL_AE_MODE_OFF
                    : supportedMode(lens, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
                    com.scamera.app.capture.FlashMode.touchAeMode(aeNow), valueOr(aeNow, CameraMetadata.CONTROL_AE_MODE_ON));
            final Float nearest = lens.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            final boolean focusable = nearest == null || nearest > 0;
            final MeteringRectangle[] afRegions = {region};
            // P142: the exposure square's own region (the tap's region without the option)
            final MeteringRectangle[] aeRegions = {aeRegion != null ? aeRegion : region};
            retryRegion = region;
            lastAeRegion = aeRegions[0];
            tapAfMode = afMode;
            markTapActive(true);
            // only the AF and AE region arrays: AWB stays as it is
            final boolean afWritten = maxRegions(lens, CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0;
            if (afWritten) req.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
            final boolean aeWritten = !manual && maxRegions(lens, CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0;
            if (aeWritten) req.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
            tapRegions = afWritten || aeWritten ? afRegions : null;
            req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            req.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            req.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            if (!focusable || afMode == CameraMetadata.CONTROL_AF_MODE_OFF) {
                Log.d(TAG, "tap meters only: the lens cannot refocus");
                req.set(AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                cam.rebuildPreviewBuilder();
                return;
            }
            pulseAf(req, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            cam.rebuildPreviewBuilder();
            tapPhase = PHASE_CANCEL_SENT;
            tapDeadline = SystemClock.elapsedRealtime() + ACK_BUDGET_MS;
            cancelWait.arm(continuousAf(afMode) ? CANCEL_SEEN_CONTINUOUS : CANCEL_SEEN_AUTO, tapDeadline);
            armBudgetLocked();
        }
    }

    /**
     * P155 (owner, X300 Ultra, 2026-10-16: «не работал нормально фокус по тапу, с DCG вкл же работает нормально»; video: a tap
     * on a near object left the lens on the window behind it): in a continuous mode a START on PASSIVE_FOCUSED only locks
     * the lens where it is (the Camera2 AF state machine), so the tap focuses only when the HAL's passive AF has already
     * swept the new region. The X300 Ultra with DCG 14 bit did (PASSIVE_SCAN ~0.4 s after the cancel, ~0.6 s long); in its
     * default sensor mode no scan came, and every tap ended FOCUSED_LOCKED after 12 frames with the lens unmoved. Without
     * an observed passive scan the START therefore goes in AF_MODE_AUTO: an active scan on the tap region, locked there
     * until the next tap or the reset to auto (as the AUTO fallback). The shared budget running out keeps the bare START.
     */
    static boolean startsActiveScan(boolean scanSeen, boolean budgetOver, boolean autoModeSupported) {
        return !scanSeen && !budgetOver && autoModeSupported;
    }

    /**
     * Steps 4-5 of the sequence: a bare START, sent unconditionally after the
     * pre-START wait resolves by ack or deadline — no skip path, and no second
     * CANCEL inside the sequence (spec rev2). Callers must hold {@link #phaseLock}.
     * With {@code activeScan} (P155) the START and the repeating request after it
     * switch to AF_MODE_AUTO.
     */
    private void sendStartLocked(boolean activeScan) {
        CaptureRequest.Builder req = previewReq();
        if (req == null || burstOwnsCamera()) {
            endTapLocked();
            return;
        }
        Log.d(TAG, "sending AF START" + (activeScan ? " in AF_MODE_AUTO (no passive scan on the region: an active scan)" : ""));
        if (activeScan) {
            tapAfMode = CameraMetadata.CONTROL_AF_MODE_AUTO;
            req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        }
        // 4. The trigger rides its own one-shot frame. The repeating request already
        //    carries regions + TRIGGER_IDLE from step 2 and is deliberately NOT
        //    resubmitted here: a back-to-back content-identical repeating replacement
        //    can race the one-shot on strict HALs and drop the trigger frame.
        // (pulseAf leaves the builder on IDLE afterwards - hygiene only, no submission attached: later rebuilds by other
        // components must not fold a stale trigger into their repeating updates)
        pulseAf(req, CameraMetadata.CONTROL_AF_TRIGGER_START);
        // P155: the repeating request takes the mode switch too (not content-identical): a continuous-mode frame after the
        // trigger would end the scan it started
        if (activeScan) cam.rebuildPreviewBuilder();
        // Acknowledgment only: the lens entered its active scan (or locked immediately).
        // Success/failure is read from the live per-frame AF_STATE afterward.
        tapPhase = PHASE_START_SENT;
        sawFailedLock = false;
        startWait.arm(START_SEEN, tapDeadline);
    }

    private static boolean continuousAf(int mode) {
        return mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO || mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
    }

    /** Engage the AUTO fallback after enough consecutive continuous-mode failures. */
    static boolean shouldEngageAutoFallback(int failedInARow, boolean autoListed) {
        return failedInARow >= AUTO_FALLBACK_TAPS && autoListed;
    }

    private static boolean autoAfListed() {
        final CameraCharacteristics lens = liveLens();
        final int auto = CameraMetadata.CONTROL_AF_MODE_AUTO;
        return lens != null && supportedMode(lens, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, auto, -1) == auto;
    }

    /**
     * Completion rule for the passive phase (divergence, user-requested): once a
     * PASSIVE_SCAN was observed, wait for it to settle — the lens is never locked
     * mid-scan, and a scan that starts at dwell expiry still runs to completion.
     * Without any observed scan, the time dwell is the fallback. The shared budget
     * always wins.
     */
    static boolean passivePhaseComplete(@Nullable Integer afState, boolean scanSeen, boolean dwellOver, boolean budgetOver) {
        final boolean scanning = is(afState, CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN);
        return budgetOver || (scanSeen ? afState != null && !scanning : dwellOver && !scanning);
    }

    /**
     * Fed from CaptureController's session callback on the background handler.
     * Both waits are acknowledgments with membership sets; null states never
     * resolve, they only consume frames toward the deadline. Acknowledgment is
     * additionally restricted to results captured with the new region — results
     * from requests carrying a different (pre-tap) region are stale.
     */
    public void onCaptureResult(CaptureResult frame) {
        if (tapPhase == PHASE_IDLE) return;
        final Integer afState = frame.get(CaptureResult.CONTROL_AF_STATE);
        synchronized (phaseLock) {
            if (burstOwnsCamera()) {
                endTapLocked();
                return;
            }
            final boolean ours = fromThisTap(frame.getRequest());
            traceAfState(afState, ours);
            if (tapPhase == PHASE_CANCEL_SENT) cancelAckFrame(afState, ours);
            else if (tapPhase == PHASE_PASSIVE_SCAN) dwellFrame(afState, ours);
            else if (tapPhase == PHASE_START_SENT) startAckFrame(afState, ours);
        }
    }

    /**
     * The CANCEL acknowledged: a continuous mode then waits for a whole passive scan of the new region before its START
     * (divergence, user-requested: the quick settle of this HAL class locked at the wrong distance); AUTO starts at once.
     */
    private void cancelAckFrame(@Nullable Integer afState, boolean ours) {
        if (!cancelWait.onFrame(afState, ours)) return;
        if (!continuousAf(tapAfMode)) {
            sendStartLocked(false);
            return;
        }
        tapPhase = PHASE_PASSIVE_SCAN;
        sawPassiveScan = false;
        dwellDeadline = Math.min(SystemClock.elapsedRealtime() + PASSIVE_DWELL_MS, tapDeadline);
        Log.d(TAG, "cancel acked, waiting for a passive scan cycle before START (fallback " + PASSIVE_DWELL_MS + "ms)");
    }

    /** The passive phase: a scan of ours seen and settled (or its fallback time / the budget over) sends the START. */
    private void dwellFrame(@Nullable Integer afState, boolean ours) {
        if (ours && is(afState, CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN)) sawPassiveScan = true;
        final long now = SystemClock.elapsedRealtime();
        final boolean budgetOver = now >= tapDeadline;
        if (!passivePhaseComplete(afState, sawPassiveScan, now >= dwellDeadline, budgetOver)) return;
        Log.d(TAG, "passive phase complete (scanCycle=" + sawPassiveScan + "), sending AF START");
        sendStartLocked(startsActiveScan(sawPassiveScan, budgetOver, autoAfListed()));
    }

    /** After the START: once it resolves (or runs out of frames / time) the tap's sequence ends. */
    private void startAckFrame(@Nullable Integer afState, boolean ours) {
        if (ours && is(afState, CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) sawFailedLock = true;
        if (!startWait.onFrame(afState, ours)) return;
        final boolean acknowledged = startWait.answer() != null;
        Log.d(TAG, "AF trigger " + (acknowledged ? "acknowledged" : sawFailedLock ? "executed but FAILED to lock" : "UNACKNOWLEDGED")
                + ", sequence complete");
        if (is(startWait.answer(), CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN)) {
            watchScan = true;
            scanAckMs = SystemClock.elapsedRealtime();
            lensAtScanAck = cam.focusDiopters;
        }
        // AUTO fallback (divergence, user-requested): consecutive failed continuous-mode taps mean this HAL cannot lock with a
        // tap region in continuous mode - tap-to-focus switches to AF_MODE_AUTO and the current tap is tried again at once
        if (continuousAf(tapAfMode)) {
            failedTaps = acknowledged ? 0 : failedTaps + 1;
            if (!acknowledged && !autoForTaps && retryRegion != null
                    && shouldEngageAutoFallback(failedTaps, autoAfListed())) {
                autoForTaps = true;
                Log.w(TAG, "continuous touch focus failed " + failedTaps
                        + " taps in a row: tap focus runs in AF_MODE_AUTO for the rest of this session");
                beginTap(retryRegion, lastAeRegion);
                return;
            }
        }
        if (!acknowledged) reviveAf();
        tapPhase = PHASE_IDLE;
        disarmBudgetLocked();
    }

    /**
     * An unacknowledged START: a lock that failed (the lens frozen at a failed distance, passive AF suspended until a CANCEL)
     * is released back to passive AF (divergence: the reference leaves it to its downstream refocus); a silent HAL gets its AF
     * mode again through the repeating request (as the still path revives AF) - no CANCEL, no region change.
     */
    private void reviveAf() {
        final CaptureRequest.Builder req = previewReq();
        if (req == null || burstOwnsCamera()) return;
        if (sawFailedLock) {
            Log.w(TAG, "failed lock: CANCEL, passive AF again");
            pulseAf(req, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
        } else {
            Log.w(TAG, "silent HAL: the AF mode once more on the repeating request");
            req.set(CaptureRequest.CONTROL_AF_MODE, tapAfMode);
            cam.rebuildPreviewBuilder();
        }
    }

    private static boolean is(@Nullable Integer state, int wanted) {
        return state != null && state == wanted;
    }

    /**
     * The frame came from a request of the current tap. Primary gate is submission identity (spec rev2): every request built
     * after the tap starts carries the tap's sequence tag, so the comparison is exact on every HAL. Without a tag: a present
     * region array counts as ours even when the HAL normalised the rect; an absent one matches only a tap that wrote none.
     * Gated-out frames spend deadline frames and nothing else.
     */
    private boolean fromThisTap(@Nullable CaptureRequest sent) {
        if (sent == null) return false;
        final Object tag = sent.getTag();
        if (tag != null) return tag.equals(tapTag);
        final boolean anyRegions = sent.get(CaptureRequest.CONTROL_AF_REGIONS) != null
                || sent.get(CaptureRequest.CONTROL_AE_REGIONS) != null;
        return anyRegions || tapRegions == null;
    }

    private void endTapLocked() {
        tapPhase = PHASE_IDLE;
        watchScan = false;
        sawPassiveScan = false;
        sawFailedLock = false;
        tapRegions = null;
        lastLoggedAf = Integer.MIN_VALUE;
        cancelWait.standDown();
        startWait.standDown();
        disarmBudgetLocked();
    }

    /** Logs each DISTINCT AF state observed during a sequence, with eligibility. */
    private void traceAfState(@Nullable Integer afState, boolean eligible) {
        final int value = afState != null ? afState : Integer.MIN_VALUE;
        if (value == lastLoggedAf) return;
        lastLoggedAf = value;
        Log.d(TAG, "seq afState=" + (afState != null ? afName(afState) : "null") + " eligible=" + eligible);
    }

    /** CONTROL_AF_STATE_* names by value (0 INACTIVE .. 6 NOT_FOCUSED_LOCKED). */
    private static final String[] AF_STATE_NAMES = {"INACTIVE", "PASSIVE_SCAN", "PASSIVE_FOCUSED", "ACTIVE_SCAN",
            "FOCUSED_LOCKED", "NOT_FOCUSED_LOCKED", "PASSIVE_UNFOCUSED"};

    private static String afName(int afState) {
        return afState >= 0 && afState < AF_STATE_NAMES.length ? AF_STATE_NAMES[afState] : String.valueOf(afState);
    }

    /**
     * One hard backstop per tap for frames that stop coming at all. It ends the sequence as unknown and changes nothing else:
     * the regions stay, the ring keeps following the live AF state.
     */
    private void armBudgetLocked() {
        disarmBudgetLocked();
        final int generation = tapGeneration;
        budgetTimeout = () -> {
            synchronized (phaseLock) {
                if (generation != tapGeneration || tapPhase == PHASE_IDLE) return;
                Log.w(TAG, "tap focus budget expired, resolving as unknown (state=" + tapPhase + ")");
                endTapLocked();
            }
        };
        // one host only: a second firing after a phase change would end the next phase's watchers early
        final Handler cameraThread = cam.mBackgroundHandler;
        budgetOnCameraThread = cameraThread != null;
        final long after = ACK_BUDGET_MS + 100;
        if (cameraThread != null) cameraThread.postDelayed(budgetTimeout, after);
        else ringView.postDelayed(budgetTimeout, after);
    }

    private void disarmBudgetLocked() {
        final Runnable pending = budgetTimeout;
        if (pending == null) return;
        budgetTimeout = null;
        final Handler cameraThread = cam.mBackgroundHandler;
        if (budgetOnCameraThread && cameraThread != null) cameraThread.removeCallbacks(pending);
        else ringView.removeCallbacks(pending);
    }

    /**
     * Back to the camera's own focus: a CANCEL once (the old regions on it), the session's default regions (never an empty
     * array: a zero-area, zero-weight region clears them without upsetting strict HALs), the viewfinder's AF / AE modes (a
     * manual exposure keeps its AE off, P521), the repeating request with IDLE.
     */
    private void backToCameraFocus() {
        synchronized (phaseLock) {
            tapGeneration++;
            endTapLocked();
            markTapActive(false);
            if (burstOwnsCamera()) return;
            final CaptureRequest.Builder req = previewReq();
            final CameraCharacteristics lens = liveLens();
            if (req == null || lens == null) return;
            pulseAf(req, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL); // the req is on IDLE again for the repeating request
            final MeteringRectangle none = new MeteringRectangle(0, 0, 0, 0, 0);
            if (maxRegions(lens, CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0)
                req.set(CaptureRequest.CONTROL_AF_REGIONS, regionsOr(cam.mPreviewMeteringAF, none));
            if (maxRegions(lens, CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0)
                req.set(CaptureRequest.CONTROL_AE_REGIONS, regionsOr(cam.mPreviewMeteringAE, none));
            req.set(CaptureRequest.CONTROL_AF_MODE, cam.mPreviewAFMode);
            if (!manualExposure(req)) req.set(CaptureRequest.CONTROL_AE_MODE, cam.mPreviewAEMode);
            cam.rebuildPreviewBuilder();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final CaptureRequest.Key<Integer> AF_TRIGGER = CaptureRequest.CONTROL_AF_TRIGGER;

    /** {@code trigger} on one frame of its own, then the builder back on IDLE; the repeating request is not resubmitted. */
    private void pulseAf(CaptureRequest.Builder b, int trigger) {
        b.set(AF_TRIGGER, trigger);
        cam.rebuildPreviewBuilderOneShot();
        b.set(AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
    }

    /** The viewfinder's request builder, null without a running camera. */
    @Nullable
    private CaptureRequest.Builder previewReq() {
        return cam.mPreviewRequestBuilder;
    }

    /** A still burst owns the camera: taps neither start nor touch the 3A then. */
    private static boolean burstOwnsCamera() {
        return CaptureController.burst;
    }

    @Nullable
    private static CameraCharacteristics liveLens() {
        return CaptureController.mCameraCharacteristics;
    }

    /** {@code requested} when the lens lists it under {@code availableKey}, else {@code fallback}. */
    private static int supportedMode(CameraCharacteristics lens, CameraCharacteristics.Key<int[]> listKey, int wanted, int otherwise) {
        final int[] modes = lens.get(listKey);
        if (modes == null) return otherwise;
        for (int m : modes) if (m == wanted) return wanted;
        return otherwise;
    }

    private static int maxRegions(CameraCharacteristics lens, CameraCharacteristics.Key<Integer> key) {
        final Integer most = lens.get(key);
        return most == null ? 0 : most;
    }

    private static MeteringRectangle[] regionsOr(@Nullable MeteringRectangle[] given, MeteringRectangle single) {
        return given != null && given.length > 0 ? given : new MeteringRectangle[]{single};
    }

    private static int valueOr(@Nullable Integer boxed, int otherwise) {
        return boxed != null ? boxed : otherwise;
    }

    /**
     * Waits for one 3A state out of a set. A null state or a frame of another request (a stale region) counts toward the
     * deadline and nothing else. The deadline ends the wait as a match does (the caller must not react differently), with no
     * state resolved.
     */
    static final class AckWait {
        private final String label;
        private int[] wanted = new int[0];
        private long untilElapsed;
        private int frames;
        private boolean waiting;
        @Nullable
        private Integer resolved;

        AckWait(String label) {
            this.label = label;
        }

        void arm(int[] acceptedStates, long deadline) {
            wanted = acceptedStates;
            untilElapsed = deadline;
            frames = 0;
            resolved = null;
            waiting = true;
        }

        void standDown() {
            waiting = false;
        }

        /** The state that ended the wait, null when the deadline did. */
        @Nullable
        Integer answer() {
            return resolved;
        }

        /** One result; true when it ends the wait. */
        boolean onFrame(@Nullable Integer state, boolean ours) {
            if (!waiting) return false;
            frames++;
            if (ours && state != null && wants(state)) {
                waiting = false;
                resolved = state;
                Log.d(TAG, label + " resolved: state " + state + " after " + frames + " frames");
                return true;
            }
            if (frames < ACK_BUDGET_FRAMES && SystemClock.elapsedRealtime() < untilElapsed) return false;
            waiting = false;
            resolved = null;
            Log.d(TAG, label + " resolved: deadline after " + frames + " frames");
            return true;
        }

        private boolean wants(int state) {
            for (int accepted : wanted) if (accepted == state) return true;
            return false;
        }
    }

    // ------------------------------------------------------------------ UI

    //Thread safe
    //call when focus circle needs to be hidden immediately
    public void resetFocusCircle() {
        clearTap();
    }

    private void clearTap() {
        ringView.removeCallbacks(hideRing);
        ringView.removeCallbacks(dimSplitLater);
        ringView.post(hideRing);
        ringView.post(this::hideExposureSquare);
        lastAeRegion = null;
        dropLock();
        backToCameraFocus();
    }

    //Must be run on UI Thread
    private void fadeOutRing() {
        if (ringListener != null) ringListener.hidden();
        if (ringView.getVisibility() != View.VISIBLE) return;
        ringView.animate().alpha(0f).scaleX(1.8f).scaleY(1.8f).setDuration(100).withEndAction(this::parkRing).start();
    }

    /** The faded ring gone and back to rest: the viewfinder's centre, its own scale, opaque. */
    private void parkRing() {
        ringView.setVisibility(View.GONE);
        ringView.setX(finder.getWidth() / 2f);
        ringView.setY(finder.getHeight() / 2f);
        ringView.setScaleX(1f);
        ringView.setScaleY(1f);
        ringView.setAlpha(1f);
    }
}
