package com.scamera.app.capture;

/**
 * Preview stall watchdog decisions (owner, 2026-10-08, vivo X200 Ultra 5x tele): the session stays configured but the camera
 * stops delivering preview frames (black viewfinder) until the module is switched or the app restarted. Seen on a cold start
 * with the tele's sensor mode 5 and right after a format change in the viewfinder; the HAL aborted after 12 s without frames
 * twice. The watchdog restarts the camera itself, as the module switch did, and logs the state first.
 */
final class PreviewStall {
    /** No preview result for this long (or three frame durations plus a second, for long manual exposures) is a stall. */
    static final long MIN_LIMIT_MS = 2500;
    /** Restarts in a row without a preview frame in between; then the watchdog only logs. */
    static final int MAX_RESTARTS = 3;

    private PreviewStall() {}

    /** The longest gap between preview results that is still normal for frames of this duration (ms, 0 = unknown). */
    static long limitMs(long frameMs) {
        return Math.max(MIN_LIMIT_MS, 3 * Math.max(0, frameMs) + 1000);
    }

    /**
     * Whether the preview stalled: {@code lastFrameMs} is the last preview result (0 = none in this session),
     * {@code sessionStartMs} when the session was configured (all on the elapsedRealtime clock).
     */
    static boolean stalled(long nowMs, long lastFrameMs, long sessionStartMs, long frameMs) {
        if (sessionStartMs <= 0) return false;
        long since = Math.max(lastFrameMs, sessionStartMs);
        return nowMs - since >= limitMs(frameMs);
    }

    /**
     * P700: failed viewfinder requests in a row, with no completed result since the session started, that show a session the HAL
     * refuses request by request (Xiaomi 17 Ultra, «DCG+LOFIC» rung «lofic-pair», log 2026-11-14 11:18:25 / 11:24:39: every
     * frame's result and its three buffers lost at 20 fps, a black viewfinder the owner swiped away after 1.5 s - before the
     * 2.5 s stall limit). About half a second of frames.
     */
    static final int REFUSED_REQUESTS = 8;

    /**
     * P700: the session's requests are refused: {@code failedInARow} failed requests (error, not flushed) since the last
     * completed result, and none completed since the session started ({@code lastFrameMs} 0).
     */
    static boolean refused(int failedInARow, long lastFrameMs) {
        return lastFrameMs == 0 && failedInARow >= REFUSED_REQUESTS;
    }

    /**
     * P710 (X300 Ultra tele, owner's log 2026-11-14 09:21 / 09:31: three PREVIEW_STALLs while a 200 MP shot was processed, the
     * camera thread 7.2 s inside one RAW viewfinder frame - «cam[CameraBackground] 7206.5/7276.3», the frame's copy 631 ms instead
     * of 10 - the system short of memory): the check runs on the camera thread every second; when it runs this much later than it
     * was due, that thread was held (the frames' handling, not the HAL) and the results queued behind it are no stall.
     */
    static final long THREAD_HELD_MS = 1500;

    /** P710: the check due at {@code dueMs} (0: none) ran at {@code nowMs}, late enough to show the camera thread held. */
    static boolean threadHeld(long nowMs, long dueMs) {
        return dueMs > 0 && nowMs - dueMs >= THREAD_HELD_MS;
    }

    /**
     * P710: {@link #stalled} counted from {@code threadFreeMs} too - the moment the camera thread was found free again after it
     * was held ({@link #threadHeld}; 0: never): its queued results get their full limit before a frame is missed.
     */
    static boolean stalled(long nowMs, long lastFrameMs, long sessionStartMs, long frameMs, long threadFreeMs) {
        return sessionStartMs > 0 && stalled(nowMs, Math.max(lastFrameMs, threadFreeMs), sessionStartMs, frameMs);
    }

    /**
     * P710: no camera restart for a stall of the plain session a FULLRES merge runs beside (CaptureController.mClosedForProcessing):
     * the camera reopens in its FULLRES session once the shot is processed anyway, and a restart under the merge's memory only
     * made it worse (the log above: the close took 30 s, every reopen «unknown device», the app killed for LOW_MEMORY).
     */
    static boolean restartHeldForMerge(boolean closedForProcessing) {
        return closedForProcessing;
    }

    /**
     * P710: the plain session beside the merge of a shot of {@code megapixels} (100 and more: the 200 MP still-only route) shows the
     * ISP viewfinder instead of the developed RAW one: no 24 MB RAW copy and upload a frame on the camera and GL threads while the
     * merge and the post pipeline take the memory - the RAW viewfinder's copies is where the camera thread was held. Not when the
     * session has no ISP viewfinder ({@code ispOff}).
     */
    static boolean lightSessionForMerge(boolean closedForProcessing, int megapixels, boolean ispOff) {
        return closedForProcessing && megapixels >= 100 && !ispOff;
    }

    /** A restart is allowed while fewer than {@link #MAX_RESTARTS} restarts in a row brought no frame. */
    static boolean mayRestart(int restartsInARow) {
        return restartsInARow < MAX_RESTARTS;
    }
}
