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

    private static long lapAt;

    /** post_sync 1 (P473 speed): the node's GPU work since its start or the last lap finished, logged as one sub-step. */
    public static void lap(String name) {
        if (!syncNodes) return;
        android.opengl.GLES30.glFinish();
        final long now = System.nanoTime();
        if (lapAt != 0) com.particlesdevs.photoncamera.util.Log.i("SCAM_PIPELINE", "post_sync lap " + name + " ms=" + (now - lapAt) / 1_000_000);
        lapAt = now;
    }

    /** The start of a node's laps (GLBasePipeline, before the node runs). */
    public static void lapStart() {
        if (syncNodes) lapAt = System.nanoTime();
    }

    private static volatile boolean invalidate;

    /**
     * P479 (speed): scam_dev.txt "post_invalidate" (on by default; never in the old model of post_ab): a pass that draws every
     * pixel of its target (GLProg.drawBlocks of a whole texture, the final output's tiles) tells the driver the target's old
     * contents are not needed (glInvalidateFramebuffer), so a tiler need not load them; every pixel is drawn anyway.
     */
    public static boolean invalidate() { return invalidate; }
    public static void setInvalidate(boolean on) { invalidate = on; }

    private static volatile boolean tileReadback;

    public static boolean tileReadback() { return tileReadback; }
    public static void setTileReadback(boolean on) { tileReadback = on; }
}
