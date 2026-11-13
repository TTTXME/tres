package com.scamera.app.processing.opengl;

/** Walks 0 .. total in steps of at most {@code step}: each call hands out the next (start, length) until nothing is left. */
public class GLBlockDivider {
    private final int total;
    private final int step;
    private int done;

    public GLBlockDivider(int total, int step) {
        this.total = total;
        this.step = step;
    }

    /** The next piece into {@code startAndLength} ({start, length}); false once the whole range was handed out. */
    public boolean nextBlock(int[] startAndLength) {
        if (done >= total) return false;
        startAndLength[0] = done;
        startAndLength[1] = Math.min(total - done, step);
        done += startAndLength[1];
        return true;
    }
}
