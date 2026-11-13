package com.scamera.app.ui.camera.views.viewfinder;

final class RawPreviewBudget {
    static final int MAX_BUSY_PIXELS = 750_000;

    private RawPreviewBudget() {}

    static int[] size(int width, int height, boolean busy) {
        final int w = Math.max(1, width), h = Math.max(1, height);
        if (!busy || (long) w * h <= MAX_BUSY_PIXELS) return new int[]{w, h};
        final double scale = Math.sqrt(MAX_BUSY_PIXELS / ((double) w * h));
        int targetW = Math.max(1, (int) (w * scale));
        int targetH = Math.max(1, (int) (h * scale));
        // A one-pixel minor axis can exceed the budget for an extreme aspect ratio.
        if ((long) targetW * targetH > MAX_BUSY_PIXELS) {
            if (targetW > targetH) targetW = MAX_BUSY_PIXELS / targetH;
            else targetH = MAX_BUSY_PIXELS / targetW;
        }
        return new int[]{targetW, targetH};
    }
}
