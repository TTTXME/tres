package com.scamera.app.processing.opengl.scripts;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLBuffer;
import com.scamera.app.processing.opengl.GLContext;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLImage;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.util.Log;

/**
 * Per-channel histograms of a texture on the GPU (GLHistogram/histogram compute shader, 8x8 groups over every
 * {@link #resize}-th pixel): one uint SSBO of {@code bins} counters per channel, read back and cleared after each run.
 */
public class GLHistogram implements AutoCloseable {
    private static final String[] BLOCKS = {"histogramRed", "histogramGreen", "histogramBlue", "histogramAlpha"};
    /** Channels counted (R, G, B, A); a channel left out keeps its last result in {@link #outputArr}. */
    public boolean Rc = true, Gc = true, Bc = true, Ac = true;
    /** Sampling step in pixels. */
    public int resize = 3;
    /** Per-channel scale of the samples before binning (1: the value 0..1 over the bins). */
    public float[] exposure = {1f, 1f, 1f, 1f};
    /** The counts of the last run per channel (empty for a channel never counted). */
    public int[][] outputArr = new int[4][0];

    private final GLProg glProg;
    private final int bins;
    private final GLBuffer[] counters = new GLBuffer[4];
    /** P617: a context made here (the gallery's histogram), ended by {@link #close}. */
    private GLContext ownContext;

    public GLHistogram(int bins) {
        this(new GLContext(1, 1), bins);
    }

    public GLHistogram() {
        this(256);
    }

    private GLHistogram(GLContext context, int bins) {
        this(context.mProgram, bins);
        ownContext = context;
    }

    /** On the program (and context) of a running pipeline. */
    public GLHistogram(GLProg program, int bins) {
        this.glProg = program;
        this.bins = bins;
        final GLFormat counter = new GLFormat(GLFormat.DataType.UNSIGNED_32);
        for (int c = 0; c < 4; c++) counters[c] = new GLBuffer(bins, counter);
    }

    /** The histograms of an image (closed afterwards). */
    public int[][] Compute(GLImage image) {
        final GLTexture texture = new GLTexture(image);
        final int[][] counts = Compute(texture);
        image.close();
        return counts;
    }

    public int[][] Compute(GLTexture input) {
        final long start = System.currentTimeMillis();
        input.Bufferize();
        final boolean[] wanted = {Rc, Gc, Bc, Ac};
        glProg.setDefine("SCALE", resize);
        glProg.setDefine("HISTSIZE", bins);
        glProg.setDefine("COL_R", Rc);
        glProg.setDefine("COL_G", Gc);
        glProg.setDefine("COL_B", Bc);
        glProg.setDefine("COL_A", Ac);
        // the shader's custom-program hooks are off
        glProg.setDefine("COL_CUSTOM", false);
        glProg.setDefine("CUSTOM_PROGRAM", "");
        glProg.setDefine("SPATIAL_KERNEL", false);
        glProg.setLayout(8, 8, 1);
        glProg.useAssetProgram("GLHistogram/histogram", true);
        glProg.setTexture("inTexture", input);
        final float top = bins - 1;
        glProg.setVar("exposure", exposure[0] * top, exposure[1] * top, exposure[2] * top, exposure[3] * top);
        glProg.setVar("input1", 0f);
        glProg.setVar("input2", 0f);
        for (int c = 0; c < 4; c++) glProg.setBufferCompute(BLOCKS[c], counters[c]);
        glProg.computeAuto(new Point(input.mSize.x / resize, input.mSize.y / resize), 1);
        for (int c = 0; c < 4; c++) if (wanted[c]) outputArr[c] = counters[c].readBufferIntegers(true);
        Log.d("GLHistogram", " elapsed:" + (System.currentTimeMillis() - start) + " ms");
        return outputArr;
    }

    @Override
    public void close() {
        for (GLBuffer b : counters) if (b != null) b.close();
        if (ownContext != null) {
            ownContext.close();
            ownContext = null;
        }
    }
}
