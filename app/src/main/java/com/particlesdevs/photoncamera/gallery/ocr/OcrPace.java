package com.particlesdevs.photoncamera.gallery.ocr;

/**
 * P617 (owner, 2026-11-11: «иногда очень долго открывается галерея», the log: «index: 9179 photos to read» at the gallery's open):
 * when the text index may read its next photo. It used to start with the gallery and read photo after photo (Tesseract on every
 * core, a 2048 px decode each) while the grid decoded its thumbnails and a shot could still be merged. Now it waits
 * {@link #START_DELAY_MS} after the gallery opens, {@link #QUIET_MS} after the grid last moved or got a new list, while a shot is
 * captured or processed, and while the phone is warm; after a photo it rests as long as the photo took (at most half the time
 * busy) and after {@link #BATCH} photos {@link #BATCH_REST_MS} more. Pure arithmetic: {@link OcrIndex} reads the clock and the
 * states.
 */
final class OcrPace {
    private OcrPace() {}

    /** After the gallery opened: its list and first thumbnails come first. */
    static final long START_DELAY_MS = 6000;
    /** After the grid last scrolled or got a new list (its thumbnails load meanwhile). */
    static final long QUIET_MS = 2500;
    /** The shortest rest after a photo, and the longest a wait sleeps before it looks again. */
    static final long MIN_REST_MS = 250, POLL_MS = 1000;
    /** Photos read before a longer rest. */
    static final int BATCH = 20;
    static final long BATCH_REST_MS = 5000;
    /** PowerManager.THERMAL_STATUS_MODERATE: from here on the index waits. */
    static final int HOT_STATUS = 2;

    /**
     * How long to wait before the next photo, 0: read it now. {@code startAt}: when the index may start (the gallery's open plus
     * {@link #START_DELAY_MS}); {@code busyUntil}: the end of the grid's quiet time; {@code shotBusy}: a shot is captured, queued or
     * processed; {@code thermal}: PowerManager's thermal status.
     */
    static long waitMs(long now, long startAt, long busyUntil, boolean shotBusy, int thermal) {
        long wait = Math.max(startAt - now, busyUntil - now);
        if (shotBusy || thermal >= HOT_STATUS) wait = Math.max(wait, POLL_MS);
        return Math.max(0, wait);
    }

    /** The rest after the {@code done}-th photo, which took {@code workMs}: as long as it took, plus a longer one after a batch. */
    static long restMs(long workMs, int done) {
        long rest = Math.max(MIN_REST_MS, workMs);
        if (done > 0 && done % BATCH == 0) rest += BATCH_REST_MS;
        return rest;
    }

    /** One wait's sleep: at most {@link #POLL_MS}, so a stop or a change of state is seen soon. */
    static long sleepMs(long waitMs) {
        return Math.max(1, Math.min(POLL_MS, waitMs));
    }
}
