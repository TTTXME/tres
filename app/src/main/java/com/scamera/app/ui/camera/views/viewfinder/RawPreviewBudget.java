package com.scamera.app.ui.camera.views.viewfinder;

final class RawPreviewBudget {
    static final int MAX_BUSY_PIXELS = 750_000;

    private RawPreviewBudget() {}

    static int[] size(int width, int height, boolean busy) {
        return size(width, height, busy ? MAX_BUSY_PIXELS : Integer.MAX_VALUE);
    }

    /** P683: busy - a shot is processed (750k); reduced - the tone did not keep up (RawPreviewLoad: ~1.1 MP); the smaller cap. */
    static int[] size(int width, int height, boolean busy, boolean reduced) {
        return size(width, height, busy ? MAX_BUSY_PIXELS : reduced ? RawPreviewLoad.REDUCED_PIXELS : Integer.MAX_VALUE);
    }

    /** P711: busy (750k) or the adaptive tone's step (RawPreviewLoad.cap: Integer.MAX_VALUE at the full size); the smaller cap. */
    static int[] size(int width, int height, boolean busy, int reducedCap) {
        return size(width, height, Math.min(busy ? MAX_BUSY_PIXELS : Integer.MAX_VALUE, reducedCap));
    }

    static int[] size(int width, int height, int maxPixels) {
        final int w = Math.max(1, width), h = Math.max(1, height);
        if ((long) w * h <= maxPixels) return new int[]{w, h};
        final double scale = Math.sqrt(maxPixels / ((double) w * h));
        int targetW = Math.max(1, (int) (w * scale));
        int targetH = Math.max(1, (int) (h * scale));
        // A one-pixel minor axis can exceed the budget for an extreme aspect ratio.
        if ((long) targetW * targetH > maxPixels) {
            if (targetW > targetH) targetW = maxPixels / targetH;
            else targetH = maxPixels / targetW;
        }
        return new int[]{targetW, targetH};
    }
}
