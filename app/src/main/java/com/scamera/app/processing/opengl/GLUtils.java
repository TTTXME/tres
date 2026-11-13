package com.scamera.app.processing.opengl;

import android.graphics.Point;

import com.scamera.app.util.Log;

/** Pyramids of a texture on the pipeline's program (ScamLocalContrast's local contrast bands). */
public class GLUtils {
    private final GLProg glProg;

    public GLUtils(GLCoreBlockProcessing blockProcessing) {
        glProg = blockProcessing.mProgram;
    }

    /** {@code in} blurred and resampled to {@code outSize} (utils/gaussdown: the blur widens with the scale, no aliasing). */
    public GLTexture gaussianDownsample(GLTexture in, Point outSize) {
        final GLTexture out = new GLTexture(outSize, in.mFormat);
        glProg.useUtilProgram("gaussdown", false);
        glProg.setTexture("InputBuffer", in);
        glProg.setVar("inputSize", in.mSize);
        glProg.setVar("downscaleFactor", (float) ((double) in.mSize.x / outSize.x));
        glProg.drawBlocks(out, outSize);
        glProg.closed = true;
        return out;
    }

    /** Gaussian levels (level 0 the input itself) and the Laplacian bands between neighbours. */
    public static class Pyramid {
        public GLTexture[] gauss;
        public GLTexture[] laplace;
        /** The nominal size of each level: the previous one divided by the step, rounded down. */
        public Point[] sizes;
        public int levels;
        public double step;

        /** Closes every level and band - level 0, the caller's input, included. */
        public void releasePyramid() {
            for (GLTexture level : gauss) level.close();
            for (GLTexture band : laplace) band.close();
        }
    }

    /**
     * {@code levels} Gaussian levels of {@code input}, each {@code step} times smaller (at least 1 x 1); step 0: automatic,
     * 2 once a level gets small. Band i = level i minus level i + 1 upsampled (utils/pyramiddiff), at level i's nominal size.
     */
    public Pyramid createPyramid(int levels, double step, GLTexture input) {
        final Pyramid p = new Pyramid();
        p.levels = levels;
        p.step = step;
        p.gauss = new GLTexture[levels];
        p.sizes = new Point[levels];
        p.gauss[0] = input;
        p.sizes[0] = new Point(input.mSize);
        final boolean automatic = step == 0;
        for (int level = 1; level < levels; level++) {
            final Point above = p.gauss[level - 1].mSize;
            if (automatic && (above.x <= step + 2 || above.y <= step + 2)) step = 2;
            final Point size = new Point(Math.max(1, (int) (above.x / step)), Math.max(1, (int) (above.y / step)));
            p.gauss[level] = gaussianDownsample(p.gauss[level - 1], size);
            p.sizes[level] = new Point((int) (p.sizes[level - 1].x / step), (int) (p.sizes[level - 1].y / step));
            Log.d("Pyramid", "downscale:" + p.sizes[level]);
        }
        glProg.useUtilProgram("pyramiddiff", false);
        p.laplace = new GLTexture[levels - 1];
        for (int band = 0; band < p.laplace.length; band++) {
            final GLTexture fine = p.gauss[band], coarse = p.gauss[band + 1];
            glProg.setTexture("target", fine);
            glProg.setTexture("base", coarse);
            glProg.setVar("size", 1.0f / p.sizes[band].x, 1.0f / p.sizes[band].y);
            glProg.setVar("size2", coarse.mSize);
            p.laplace[band] = new GLTexture(p.sizes[band], coarse.mFormat);
            glProg.drawBlocks(p.laplace[band]);
            Log.d("Pyramid", "diff:" + p.laplace[band].mSize + " downscaled:" + fine.mSize);
        }
        return p;
    }
}
