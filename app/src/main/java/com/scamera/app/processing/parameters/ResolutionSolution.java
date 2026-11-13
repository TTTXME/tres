package com.scamera.app.processing.parameters;

/** Pixel counts the stream choice compares a size with: a full photo, a small one and the preview's ceiling. */
public final class ResolutionSolution {
    public static final int PHOTO_PIXELS = 30_000_000, SMALL_PIXELS = 8_000_000, PREVIEW_PIXELS = 5_000_000;

    private ResolutionSolution() {
    }
}
