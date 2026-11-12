package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;

/**
 * P635: one recipe shot's truth check (FullResTruth): the first full-size frame is measured, the line goes to the log, and every
 * frame of the shot follows its verdict - a real frame is kept; a doubled one (the binned frame written twice over) is taken
 * back to the binned frame it was made of, so the shot is merged as the normal 12 MP one instead of a 50 MP file without 50 MP
 * of detail.
 */
public final class FullResRecipeShot {
    public final String camera, profile;
    private FullResTruth.Result first;

    public FullResRecipeShot(String camera, String profile) {
        this.camera = camera;
        this.profile = profile;
    }

    /** The shot's verdict: measured on the first frame that comes (logged once), the same for every later frame. */
    public synchronized FullResTruth.Result judge(@Nullable ByteBuffer raw, int width, int height, int stride) {
        if (first != null) return first;
        final long start = System.nanoTime();
        first = FullResTruth.check(raw, width, height, stride);
        Log.i("SCAM_CAPTURE", first.line() + " (camera " + camera + ", " + profile + ", " + (System.nanoTime() - start) / 1_000_000 + " ms, P635)");
        return first;
    }

    /** The verdict so far; null before the first frame. */
    @Nullable
    public synchronized FullResTruth.Result verdict() {
        return first;
    }

    public synchronized boolean upscaled() {
        return first != null && first.verdict == FullResTruth.Verdict.UPSCALED;
    }
}
