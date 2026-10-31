package com.particlesdevs.photoncamera.processing.opengl;

/**
 * Shot speed (wave 1): switches of the post pipeline's GL model.
 * <ul>
 * <li>{@link #legacy()}: the model before wave 1 for one run of the post_ab A/B (PostAb): glFinish after every pass, two
 *     memory barriers and a flush per 256-row tile, FBO names given to glDeleteBuffers, the cleared effective-frame map
 *     of before W3.5, the sequential denoise statistics, the GL_FLOAT read-back of WhyStats, WhyLumaSharpen after the
 *     AE, the watermark texture always built, no shader source cache. Off in every normal shot.</li>
 * <li>{@link #syncNodes()}: scam_dev.txt "post_sync 1": a glFinish at the end of every node, so the per-node times of the
 *     "runAll timings" table include their GPU time again (without it the GPU time shows up at the next sync point).</li>
 * <li>{@link #tileReadback()}: P440, the final readback through the tiles (GLCoreBlockProcessing.drawBlocksToBitmap's strip
 *     bitmap) instead of the pixel-pack buffer, for the old run of "post_ab 1" with "post_ab_readback 1" (PostAb): EQUAL
 *     proves the buffer's bytes on the phone. Off in every normal shot.</li>
 * </ul>
 */
public final class PostGlMode {
    private PostGlMode() {}

    private static volatile boolean legacy;
    private static volatile boolean syncNodes;

    public static boolean legacy() { return legacy; }
    public static void setLegacy(boolean on) { legacy = on; }

    public static boolean syncNodes() { return syncNodes; }
    public static void setSyncNodes(boolean on) { syncNodes = on; }

    private static volatile boolean tileReadback;

    public static boolean tileReadback() { return tileReadback; }
    public static void setTileReadback(boolean on) { tileReadback = on; }
}
