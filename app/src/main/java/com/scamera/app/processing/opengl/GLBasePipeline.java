package com.scamera.app.processing.opengl;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.scamera.app.api.Settings;
import com.scamera.app.processing.ShotTimeline;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.processing.opengl.postpipeline.ScamDiagnostics;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static com.scamera.app.processing.opengl.GLCoreBlockProcessing.checkEglError;

/**
 * A chain of GL passes ({@link Node}s) on one processing context. Each pass compiles its program, runs, and - unless it
 * drew itself - has its program drawn into the texture it names; the last pass stays bound for the read-back. Passes
 * ping-pong between {@link #main1} and {@link #main2} through {@link #getMain}. The run is timed per pass and summed up
 * in the log at its end.
 */
public class GLBasePipeline implements AutoCloseable {
    private static final String TAG = "BasePipeline";
    /** The only pass whose failure leaves the image as it was before it instead of failing the run. */
    private static final String OPTIONAL_NODE = "Sharpening";

    /** P497: the post node finished last (any pipeline; VfDrawMeter names it beside a slow viewfinder draw). */
    public static volatile String lastNode = "";
    /** P616: the post node running now ("" between runs): a slow draw ends inside it, after {@link #lastNode}. */
    public static volatile String runningNode = "";

    /** The passes in run order (add). */
    public final ArrayList<Node> passList = new ArrayList<>();
    public GLInterface glIface = null;
    /** The shot being developed: its settings and parameters, the size the passes work at and its noise model (S, O). */
    public Settings shotSettings;
    public Parameters shotParams;
    public Point frameSize;
    public float noiseS, noiseO;
    /** The ping-pong textures. */
    public GLTexture main1, main2;
    /** Which main {@link #getMain} handed out last (1 or 2; 0: none yet). */
    public int texnum = 0;
    /**
     * P179: main2 made on its first use (getMain) instead of up front: at 200 MP its 1.6 GB RGBA16F need not sit next to the
     * input texture of the first stage. Null: main2 is made by whoever set it.
     */
    public Supplier<GLTexture> lazyMain2;
    /** P179: main1 made again on its next use after {@link #releaseMain} gave its storage back. */
    public Supplier<GLTexture> lazyMain1;

    private final String name;
    private final int[] savedFramebuffer = new int[1];
    private long nodeStart;
    /** P602: the wall time of the last run of the nodes (their timers and what lies between them), -1 when none was run. */
    private long nodesWallMs = -1;
    private final List<String> timedNames = new ArrayList<>();
    private final List<Long> timedMs = new ArrayList<>();

    public GLBasePipeline(String name) {
        this.name = name;
    }

    /**
     * P179: a ping-pong texture whose content nothing reads any more gives its storage back now and is made again (same size
     * and format) on its next use - at 200 MP an RGBA16F texture is 1.6 GB. False when {@code t} is not main1 / main2.
     */
    public boolean releaseMain(GLTexture t) {
        if (t == null || (t != main1 && t != main2)) return false;
        final Point size = new Point(t.size);
        final GLFormat format = t.format;
        final Supplier<GLTexture> remake = () -> new GLTexture(size, format, null, GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE);
        t.close();
        if (t == main1) {
            main1 = null;
            lazyMain1 = remake;
        } else {
            main2 = null;
            lazyMain2 = remake;
        }
        return true;
    }

    /** The other ping-pong texture than the last call's (main1 first), made now when it is a lazy one. */
    public GLTexture getMain() {
        if (texnum == 1) {
            texnum = 2;
            if (main2 == null && lazyMain2 != null) {
                final Supplier<GLTexture> make = lazyMain2;
                lazyMain2 = null;
                main2 = make.get();
            }
            return main2;
        }
        texnum = 1;
        if (main1 == null && lazyMain1 != null) {
            final Supplier<GLTexture> make = lazyMain1;
            lazyMain1 = null;
            main1 = make.get();
        }
        return main1;
    }

    public void add(Node node) {
        if (!passList.isEmpty()) node.prevPass = passList.get(passList.size() - 1);
        node.hostPipeline = this;
        node.glCtx = glIface;
        node.glKit = glIface.kitUtils;
        node.shaderProg = glIface.prog;
        passList.add(node);
    }

    public void startTimeMeasure() {
        PostGlMode.lapStart();
        nodeStart = System.currentTimeMillis();
    }

    public void endTimeMeasure(String node) {
        final long ms = System.currentTimeMillis() - nodeStart;
        Log.d("Pipeline", "Node:" + node + " elapsed:" + ms + " ms");
        lastNode = node; // P497: for the viewfinder's slow-draw lines (VfDrawMeter)
        timedNames.add(node);
        timedMs.add(ms);
    }

    private long timedTotal() {
        long total = 0;
        for (long ms : timedMs) total += ms;
        return total;
    }

    /** The slots of the timed nodes, slowest first (ties: the earlier one first). */
    private List<Integer> bySlowest() {
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < timedMs.size(); i++) order.add(i);
        order.sort((a, b) -> Long.compare(timedMs.get(b), timedMs.get(a)));
        return order;
    }

    /**
     * One table of the run in the order the nodes ran: each node's time and share, the total, the time outside the node
     * timers (P602: preparation, the draws of the programs, the diagnostics' read-backs and cleanup), the diagnostics'
     * read-backs, and the five slowest nodes.
     */
    public void dumpTimings(String label) {
        if (timedMs.isEmpty()) return;
        Log.d(TAG, label + " " + GLProg.takeProgramStats() + ", textures " + GLTexture.takeCreated());
        final long total = timedTotal();
        final StringBuilder table = new StringBuilder("\n===== ").append(label).append(" timings =====\n");
        for (int i = 0; i < timedMs.size(); i++) {
            final long ms = timedMs.get(i);
            table.append(String.format(Locale.ROOT, "  %-38s %7d ms  %5.1f%%%n", timedNames.get(i), ms, total > 0 ? 100.0 * ms / total : 0.0));
        }
        table.append(String.format(Locale.ROOT, "  %-38s %7d ms%n", "TOTAL", total));
        if (nodesWallMs >= 0) {
            table.append(String.format(Locale.ROOT, "  %-38s %7d ms%n", "outside the nodes", Math.max(0L, nodesWallMs - total)));
            nodesWallMs = -1;
        }
        final long[] diagnostics = ScamDiagnostics.takeGpuStats();
        if (diagnostics[1] > 0) {
            table.append(String.format(Locale.ROOT, "  %-38s %7d ms (%d stages)%n", "diagnostics read-backs", diagnostics[0], diagnostics[1]));
        }
        table.append("  slowest: ");
        final List<Integer> slowest = bySlowest();
        for (int k = 0; k < Math.min(5, slowest.size()); k++) {
            final int i = slowest.get(k);
            table.append(k > 0 ? ", " : "").append(timedNames.get(i)).append(' ').append(timedMs.get(i)).append("ms");
        }
        Log.d("Timings", table.toString());
    }

    /** The nodes by cost with their share of the total (the nodes of 0 ms left out), then the timers are reset. */
    public void logTimeSummary(String pipelineName) {
        final long total = timedTotal();
        if (total <= 0 || timedMs.isEmpty()) return;
        final StringBuilder summary = new StringBuilder("=== ").append(pipelineName).append(" timing: ").append(total)
                .append(" ms over ").append(timedMs.size()).append(" nodes ===");
        for (int i : bySlowest()) {
            final long ms = timedMs.get(i);
            if (ms > 0) summary.append(String.format(Locale.ROOT, "%n  %-28s %6d ms  %5.1f%%", timedNames.get(i), ms, 100.0 * ms / total));
        }
        Log.d("Pipeline", summary.toString());
        timedMs.clear();
        timedNames.clear();
    }

    /**
     * The run for an RGBA8 output that ends up in a Bitmap anyway: the last node is rendered tile by tile straight into a
     * new ARGB_8888 bitmap ({@link GLCoreBlockProcessing#drawBlocksToBitmap()}), so the full-frame read-back buffer (~200 MB
     * at 50 MP) never exists next to the bitmap. Construct the processing with {@code GLDrawParams.Allocate.None} (or any
     * kind: the buffer is only allocated by a full read-back).
     */
    public Bitmap runAllToBitmap() {
        // P616: the post's GPU queue kept a few blocks deep (the viewfinder's draws are not stuck behind it); P668: through the
        // final output's tiles as well (Xiaomi 12X, 31266: its 16 tiles went at once, «slow draw 182 / 243 ms ... after node Rotate»)
        GpuPace.begin();
        GLProg.postRunning = true; // P713: the warm-up beside the merge stops (GLProg.warmUpBesideMerge)
        final Bitmap bitmap;
        try {
            runNodes();
            // P440 (step 0c): the final pass's draws and read-back start here (post_final .. post_readback)
            if (!PostGlMode.legacy()) ShotTimeline.markIfShot("post_final");
            GLGpuTimer.node("final output");
            GLTexturePool.node("final output"); // P477: the inventory's node
            GLProg.compileNode("final output"); // P602
            try {
                bitmap = glIface.blockCtx.drawBlocksToBitmap();
            } finally {
                GLProg.compileNode(null);
            }
        } finally {
            GLDeferredDraw.discard(); // P713: a run that failed half way: what it held is released
            GLProg.postRunning = false;
            GpuPace.end();
        }
        GLGpuTimer.report("post");
        GLTexturePool.report("post");
        finishRun();
        return bitmap;
    }

    protected boolean skipNode(Node node) { return false; }

    /**
     * P713: whether {@code node}, the one before the final pass {@code last}, may leave its full-size draw to the final output
     * (GLDeferredDraw: it draws through GLProg.drawBlocksDeferrable). False here: every node draws at once.
     */
    protected boolean deferLastDraw(Node node, Node last) { return false; }

    /** Runs every node; the last one stays bound for the read-back. */
    private void runNodes() {
        final long wallStart = System.currentTimeMillis();
        try {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFramebuffer, 0);
            checkEglError("glGetIntegerv");
            final boolean sync = PostGlMode.syncNodes();
            GLGpuTimer.begin(); // P479: post_sync only (profiling)
            for (int i = 0; i < passList.size(); i++) {
                // P713: the last full-size pass of a streamed JPEG drawn beside the final output's tiles (GLDeferredDraw)
                final boolean defer = i == passList.size() - 2 && deferLastDraw(passList.get(i), passList.get(i + 1));
                if (defer) GLDeferredDraw.arm(true);
                try {
                    runNode(passList.get(i), i == passList.size() - 1, sync);
                } finally {
                    if (defer) GLDeferredDraw.arm(false);
                }
            }
            // the main the last node did not write goes now (the other one holds the result)
            final GLTexture spare = texnum == 1 ? main2 : main1;
            if (spare != null) spare.close();
        } finally {
            nodesWallMs = System.currentTimeMillis() - wallStart;
            GLProg.compileNode(null);
            runningNode = "";
        }
    }

    /** Hands the previous node's texture on as this node's output (a skipped or failed optional node). */
    private void passThrough(Node node) {
        node.passOut = node.prevPass.passOut;
        glIface.prog.closed = true;
    }

    private void runNode(Node node, boolean last, boolean sync) {
        if (skipNode(node)) {
            if (node.prevPass == null) throw new IllegalStateException("Cannot skip the first post node");
            passThrough(node);
            return;
        }
        GLProg.compileNode(node.passName); // P602: the programs this node compiles are named with it
        runningNode = node.passName; // P616: the node a stalled viewfinder draw fell into (VfDrawMeter)
        try {
            node.beforeShader();
            node.bindShader();
            node.refreshTunables();
            if (last) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFramebuffer[0]);
                checkEglError("glBindFramebuffer");
            }
        } catch (RuntimeException error) {
            throw failure(node, "prepare", error);
        }
        startTimeMeasure();
        GLGpuTimer.node(node.passName);
        GLTexturePool.node(node.passName); // P477: the inventory's node
        try {
            node.runPass();
        } catch (RuntimeException error) {
            if (!OPTIONAL_NODE.equals(node.passName) || node.prevPass == null) throw failure(node, "run", error);
            Log.e(TAG, "Bypassing optional node " + node.passName, error);
            passThrough(node);
        }
        // post_sync 1: the node's GPU work is finished inside its own time (W1.1 removed the glFinish per pass)
        if (sync) GLES30.glFinish();
        endTimeMeasure(node.passName);
        if (!last) {
            if (!glIface.prog.closed) {
                glIface.prog.drawBlocks(node.drawTarget());
                glIface.prog.closed = true;
            }
            // post_sync 1: this draw stays outside every node's time
            if (sync) GLES30.glFinish();
            ScamDiagnostics.gpu(node.passName, node.passOut);
        }
        try {
            node.afterPass();
        } catch (RuntimeException error) {
            // AfterRun only releases temporary resources: its failure must not discard an otherwise valid image
            Log.e(TAG, "Cleanup failed in node " + node.passName, error);
        }
    }

    /** Releases the pipeline textures and program after the read-back of the last node. */
    private void finishRun() {
        // P602: the diagnostics' GPU stages still queued, and the reads queued only for the log (the GPU is done with them)
        ScamDiagnostics.flushGpuStages();
        GLDeferredRead.flush();
        final GLTexture result = texnum == 1 ? main1 : main2;
        if (result != null) result.close();
        lazyMain2 = null;
        lazyMain1 = null;
        glIface.prog.close();
        dumpTimings("runAll");
        passList.clear();
        logTimeSummary(getClass().getSimpleName());
    }

    private static IllegalStateException failure(Node node, String phase, RuntimeException cause) {
        return new IllegalStateException("post node " + node.passName + " (" + phase + ")", cause);
    }

    private interface Release { void run() throws Exception; }

    private static void quietly(Release release) {
        try {
            release.run();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        final GLInterface gl = glIface;
        if (gl != null) {
            // pending GPU work completes before the context goes (Adreno)
            quietly(GLES30::glFinish);
            // glProcessing owns the EGL context (it extends GLContext); a separate glContext is closed too
            final GLCoreBlockProcessing processing = gl.blockCtx;
            if (processing != null) {
                quietly(processing::close);
                gl.blockCtx = null;
            }
            if (gl.plainCtx != null && gl.plainCtx != processing) {
                quietly(gl.plainCtx::close);
                gl.plainCtx = null;
            }
            if (gl.prog != null) quietly(gl.prog::close);
            glIface = null;
        }
        quietly(GLTexture::notClosed);
    }
}
