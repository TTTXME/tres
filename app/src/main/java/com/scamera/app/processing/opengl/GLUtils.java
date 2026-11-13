package com.scamera.app.processing.opengl;

import android.graphics.Point;

import com.scamera.app.util.Log;

/** Pyramids of a texture on the pipeline's program (ScamLocalContrast's local contrast bands). */
public class GLUtils {
    private final GLProg prog;

    public GLUtils(GLCoreBlockProcessing blockProcessing) {
        prog = blockProcessing.program;
    }

    /** {@code in} blurred and resampled to {@code outSize} (utils/gaussdown: the blur widens with the scale, no aliasing). */
    public GLTexture gaussianDownsample(GLTexture in, Point outSize) {
        final GLTexture out = new GLTexture(outSize, in.format);
        final Point from = in.size;
        prog.useUtilProgram("gaussdown", false);
        prog.setTexture("srcLevel", in);
        prog.setVar("srcSize", from);
        prog.setVar("srcPerOut", (float) ((double) from.x / outSize.x));
        prog.drawBlocks(out, outSize);
        prog.closed = true;
        return out;
    }

    /** Gaussian levels (level 0 the input itself) and the Laplacian bands between neighbours. */
    public static class Pyramid {
        /** The Gaussian levels and the Laplacian bands. */
        public GLTexture[] gaussian, bands;
        /** The nominal size of each level: the previous one divided by the ratio, rounded down. */
        public Point[] levelSizes;
        public int count;
        public double ratio;

        /** Closes every level and band - level 0, the caller's input, included. */
        public void releasePyramid() {
            for (GLTexture level : gaussian) level.close();
            for (GLTexture band : bands) band.close();
        }
    }

    /**
     * {@code levels} Gaussian levels of {@code input}, each {@code step} times smaller (at least 1 x 1); step 0: automatic,
     * 2 once a level gets small. Band i = level i minus level i + 1 upsampled (utils/pyramiddiff), at level i's nominal size.
     */
    public Pyramid createPyramid(int levels, double step, GLTexture input) {
        final Pyramid p = new Pyramid();
        p.count = levels;
        p.ratio = step;
        p.gaussian = new GLTexture[levels];
        p.levelSizes = new Point[levels];
        p.gaussian[0] = input;
        p.levelSizes[0] = new Point(input.size);
        final boolean automatic = step == 0;
        for (int level = 1; level < levels; level++) {
            final Point above = p.gaussian[level - 1].size;
            if (automatic && (above.x <= step + 2 || above.y <= step + 2)) step = 2;
            final Point size = new Point(Math.max(1, (int) (above.x / step)), Math.max(1, (int) (above.y / step)));
            p.gaussian[level] = gaussianDownsample(p.gaussian[level - 1], size);
            p.levelSizes[level] = new Point((int) (p.levelSizes[level - 1].x / step), (int) (p.levelSizes[level - 1].y / step));
            Log.d("Pyramid", "downscale:" + p.levelSizes[level]);
        }
        prog.useUtilProgram("pyramiddiff", false);
        p.bands = new GLTexture[levels - 1];
        for (int band = 0; band < p.bands.length; band++) {
            final GLTexture fine = p.gaussian[band], coarse = p.gaussian[band + 1];
            prog.setTexture("fineLevel", fine);
            prog.setTexture("coarseLevel", coarse);
            prog.setVar("fineTexel", 1.0f / p.levelSizes[band].x, 1.0f / p.levelSizes[band].y);
            p.bands[band] = new GLTexture(p.levelSizes[band], coarse.format);
            prog.drawBlocks(p.bands[band]);
            Log.d("Pyramid", "diff:" + p.bands[band].size + " downscaled:" + fine.size);
        }
        return p;
    }
}
