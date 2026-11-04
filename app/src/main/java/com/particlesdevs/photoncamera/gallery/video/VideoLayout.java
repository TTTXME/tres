package com.particlesdevs.photoncamera.gallery.video;

/** P499: the picture's size on the screen for the player's fit modes, then the pinch zoom on top. */
final class VideoLayout {
    private VideoLayout() {}

    /** {width, height} of the video view in a {@code bw} x {@code bh} box. */
    static int[] size(VideoPlayerActivity.Fit fit, int vw, int vh, int bw, int bh, float zoom) {
        double w, h;
        switch (fit) {
            case CROP: {
                double s = Math.max(bw / (double) vw, bh / (double) vh);
                w = vw * s;
                h = vh * s;
                break;
            }
            case STRETCH:
                w = bw;
                h = bh;
                break;
            case ORIGINAL:
                w = vw;
                h = vh;
                break;
            case R16_9:
            case R4_3: {
                double a = fit == VideoPlayerActivity.Fit.R16_9 ? 16 / 9.0 : 4 / 3.0;
                if (vh > vw) a = 1 / a; // a portrait video keeps its orientation
                if (bw / (double) bh > a) {
                    h = bh;
                    w = bh * a;
                } else {
                    w = bw;
                    h = bw / a;
                }
                break;
            }
            default: {
                double s = Math.min(bw / (double) vw, bh / (double) vh);
                w = vw * s;
                h = vh * s;
            }
        }
        return new int[]{Math.max(1, (int) Math.round(w * zoom)), Math.max(1, (int) Math.round(h * zoom))};
    }
}
