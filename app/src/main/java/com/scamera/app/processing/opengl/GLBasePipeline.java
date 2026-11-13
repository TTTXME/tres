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

    public final ArrayList<Node> Nodes = new ArrayList<>();
    public GLInterface glint = null;
    public Settings mSettings;
    public Parameters mParameters;
    public Point workSize;
    public float noiseS;
    public float noiseO;
    /** The ping-pong textures; main3..5 belong to particular pipelines. */
    public GLTexture main1, main2, main3, main4, main5;
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
        final Point size = new Point(t.mSize);
        final GLFormat format = t.mFormat;
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
        if (!Nodes.isEmpty()) node.previousNode = Nodes.get(Nodes.size() - 1);
        node.basePipeline = this;
        node.glInt = glint;
        node.glUtils = glint.glUtils;
        node.glProg = glint.glProgram;
        Nodes.add(node);
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
        runNodes();
        // P440 (step 0c): the final pass's draws and read-back start here (post_final .. post_readback)
        if (!PostGlMode.legacy()) ShotTimeline.markIfShot("post_final");
        GLGpuTimer.node("final output");
        // P477: no node makes a texture any more: what the pool keeps goes now
        GLTexturePool.node("final output");
        GLTexturePool.end();
        GLProg.compileNode("final output"); // P602
        final Bitmap bitmap;
        try {
            bitmap = glint.glProcessing.drawBlocksToBitmap();
        } finally {
            GLProg.compileNode(null);
        }
        GLGpuTimer.report("post");
        GLTexturePool.report("post");
        finishRun();
        return bitmap;
    }

    protected boolean skipNode(Node node) { return false; }

    /** Runs every node; the last one stays bound for the read-back. */
    private void runNodes() {
        final long wallStart = System.currentTimeMillis();
        GpuPace.begin(); // P616: the post's GPU queue kept a few blocks deep (the viewfinder's draws are not stuck behind it)
        try {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFramebuffer, 0);
            checkEglError("glGetIntegerv");
            final boolean sync = PostGlMode.syncNodes();
            GLGpuTimer.begin(); // P479: post_sync only (profiling)
            for (int i = 0; i < Nodes.size(); i++) runNode(Nodes.get(i), i == Nodes.size() - 1, sync);
            // the main the last node did not write goes now (the other one holds the result)
            final GLTexture spare = texnum == 1 ? main2 : main1;
            if (spare != null) spare.close();
        } finally {
            nodesWallMs = System.currentTimeMillis() - wallStart;
            GLProg.compileNode(null);
            runningNode = "";
            GpuPace.end();
        }
    }

    /** Hands the previous node's texture on as this node's output (a skipped or failed optional node). */
    private void passThrough(Node node) {
        node.WorkingTexture = node.previousNode.WorkingTexture;
        glint.glProgram.closed = true;
    }

    private void runNode(Node node, boolean last, boolean sync) {
        if (skipNode(node)) {
            if (node.previousNode == null) throw new IllegalStateException("Cannot skip the first post node");
            passThrough(node);
            return;
        }
        GLProg.compileNode(node.Name); // P602: the programs this node compiles are named with it
        runningNode = node.Name; // P616: the node a stalled viewfinder draw fell into (VfDrawMeter)
        try {
            node.BeforeCompile();
            node.Compile();
            node.BeforeRun();
            if (last) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFramebuffer[0]);
                checkEglError("glBindFramebuffer");
            }
        } catch (RuntimeException error) {
            throw failure(node, "prepare", error);
        }
        startTimeMeasure();
        GLGpuTimer.node(node.Name);
        GLTexturePool.node(node.Name); // P477: the inventory's node
        try {
            node.Run();
        } catch (RuntimeException error) {
            if (!OPTIONAL_NODE.equals(node.Name) || node.previousNode == null) throw failure(node, "run", error);
            Log.e(TAG, "Bypassing optional node " + node.Name, error);
            passThrough(node);
        }
        // post_sync 1: the node's GPU work is finished inside its own time (W1.1 removed the glFinish per pass)
        if (sync) GLES30.glFinish();
        endTimeMeasure(node.Name);
        if (!last) {
            if (!glint.glProgram.closed) {
                glint.glProgram.drawBlocks(node.GetProgTex());
                glint.glProgram.closed = true;
            }
            // post_sync 1: this draw stays outside every node's time
            if (sync) GLES30.glFinish();
            ScamDiagnostics.gpu(node.Name, node.WorkingTexture);
        }
        try {
            node.AfterRun();
        } catch (RuntimeException error) {
            // AfterRun only releases temporary resources: its failure must not discard an otherwise valid image
            Log.e(TAG, "Cleanup failed in node " + node.Name, error);
        }
    }

    /** Releases the pipeline textures and program after the read-back of the last node. */
    private void finishRun() {
        // P602: the diagnostics' GPU stages still queued, and the reads queued only for the log (the GPU is done with them)
        ScamDiagnostics.flushGpuStages();
        GLDeferredRead.flush();
        final GLTexture result = texnum == 1 ? main1 : main2;
        if (result != null) result.close();
        if (main3 != null) main3.close();
        lazyMain2 = null;
        lazyMain1 = null;
        glint.glProgram.close();
        dumpTimings("runAll");
        Nodes.clear();
        logTimeSummary(getClass().getSimpleName());
    }

    private static IllegalStateException failure(Node node, String phase, RuntimeException cause) {
        return new IllegalStateException("post node " + node.Name + " (" + phase + ")", cause);
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
        final GLInterface gl = glint;
        if (gl != null) {
            // pending GPU work completes before the context goes (Adreno)
            quietly(GLES30::glFinish);
            // glProcessing owns the EGL context (it extends GLContext); a separate glContext is closed too
            final GLCoreBlockProcessing processing = gl.glProcessing;
            if (processing != null) {
                quietly(processing::close);
                gl.glProcessing = null;
            }
            if (gl.glContext != null && gl.glContext != processing) {
                quietly(gl.glContext::close);
                gl.glContext = null;
            }
            if (gl.glProgram != null) quietly(gl.glProgram::close);
            glint = null;
        }
        quietly(GLTexture::notClosed);
    }
}
