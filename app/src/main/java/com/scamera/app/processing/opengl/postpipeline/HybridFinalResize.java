package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * SCAM HDR hybrid on the Sabre 2x grid: the final 12/16/20 MP size is produced on the GPU as the last processing
 * step (after sharpening, before the rotation), so the readback buffer, the bitmap and the JPEG encoder never see the
 * 50 MP image (the CPU resize of a 201 MB bitmap got the process killed on a phone that was already low on memory).
 * Kernels follow {@link com.scamera.app.processing.ml.ScamPostDownscale}: lanczos, bicubic, area, bilinear.
 */
public class HybridFinalResize extends Node {
    private final Point target;
    private final String kernelName;

    public HybridFinalResize(Point target, String kernelName) {
        super("", "FinalResize");
        this.target = target;
        this.kernelName = kernelName == null ? "lanczos" : kernelName;
    }

    static int kernelIndex(String name) {
        if ("bicubic".equals(name)) return 1;
        if ("area".equals(name)) return 2;
        if ("bilinear".equals(name)) return 3;
        return 0;
    }

    @Override
    public void bindShader() {}

    /**
     * P145: {@code in} - linear light, values above 1 kept (the Ultra HDR linear snapshot) - resized to {@code target} on the
     * GPU with the same shader (LINEAR_IN 1; "area": a box mean) into a new texture of its format; the caller closes it.
     */
    static GLTexture resized(com.scamera.app.processing.opengl.GLProg prog, GLTexture in, Point target, String kernelName) {
        if (target.x >= in.size.x || target.y >= in.size.y || target.x * 4 < in.size.x || target.y * 4 < in.size.y)
            throw new IllegalStateException("resize " + in.size.x + "x" + in.size.y + " -> " + target.x + "x" + target.y + " out of range");
        prog.setDefine("LINEAR_IN", 1);
        prog.useAssetProgram("hybrid/final_resize", false);
        prog.setTexture("InputBuffer", in);
        prog.setVar("kernel", kernelIndex(kernelName));
        prog.setVar("inSize", in.size.x, in.size.y);
        prog.setVar("outSize", target.x, target.y);
        GLTexture out = new GLTexture(target, in.format, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        prog.drawBlocks(out);
        prog.closed = true;
        return out;
    }

    @Override
    public void runPass() {
        GLTexture in = prevPass.passOut;
        if (target.x >= in.size.x || target.y >= in.size.y || target.x * 4 < in.size.x || target.y * 4 < in.size.y)
            throw new IllegalStateException("final resize " + in.size.x + "x" + in.size.y + " -> " + target.x + "x" + target.y
                    + " out of range (the output buffer is already at the final size)");
        final long started = System.nanoTime();
        // The idle ping-pong texture is not needed any more: free them before the
        // allocation so the GPU peak drops instead of growing (runAll would only close them after the readback).
        hostPipeline.lazyMain2 = null; // P179: never made now
        hostPipeline.lazyMain1 = null;
        GLTexture[] mains = {hostPipeline.main1, hostPipeline.main2};
        for (int i = 0; i < mains.length; i++) {
            if (mains[i] != null && mains[i] != in) {
                mains[i].close();
                if (i == 0) hostPipeline.main1 = null; else hostPipeline.main2 = null;
            }
        }
        final int kernel = kernelIndex(kernelName);
        passOut = new GLTexture(target, in.format, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        final boolean separable = separableWanted(kernel) && separable(in, passOut, kernel);
        if (!separable) {
            shaderProg.useAssetProgram("hybrid/final_resize", false);
            shaderProg.setTexture("InputBuffer", in);
            shaderProg.setVar("kernel", kernel);
            // Integer sizes: the shader splits the input-space centre exactly (a float centre lost precision on Adreno).
            shaderProg.setVar("inSize", in.size.x, in.size.y);
            shaderProg.setVar("outSize", target.x, target.y);
            shaderProg.drawBlocks(passOut);
        }
        // drawn here: the pipeline must not draw the program into passOut again (it did: every resize ran twice)
        shaderProg.closed = true;
        ((PostPipeline) hostPipeline).finalResized = true;
        android.util.Log.i("SCAM_HDR", "hybrid final size " + target.x + "x" + target.y + " (" + kernelName + ", GPU"
                + (separable ? ", two passes" : "") + ") from " + in.size.x + "x" + in.size.y
                + " ms=" + (System.nanoTime() - started) / 1000000);
    }

    /** The output rows of one band of the two-pass route: drawn from an RGBA32F band of this many input rows at most. */
    static final int BAND_ROWS = 256;
    /** Input rows the planner adds on each side beyond the shader's reach (its float ceil may land one higher than Java's). */
    static final int REACH_MARGIN = 2;

    /**
     * The two-pass route (speed, weak GPUs): Lanczos-3, bicubic and area are separable, so hybrid/final_resize PASS 1 sums each
     * band's input rows horizontally into an RGBA32F band and PASS 2 sums that band vertically into the output - (2 reach + 1)
     * steps a pixel per axis instead of their square (Lanczos-3 at 2:1: 225 -> 45; tools/check_final_resize_separable.py: the
     * same output within float rounding, at most 1 8-bit level on 1 value in 10^5). Bilinear (4 taps), the old GL model of
     * post_ab and scam_dev resize_separable 0 keep the one pass.
     */
    static boolean separableWanted(int kernel) {
        if (kernel == 3 || com.scamera.app.processing.opengl.PostGlMode.legacy()) return false;
        try {
            return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("resize_separable", true);
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** The shader's ceil(radius) + 1 along an axis of {@code in} -> {@code out} pixels, in float as the shader has it. */
    static int reach(int in, int out, int kernel) {
        final float scale = (float) in / (float) out;
        final float radius = kernel == 2 ? 0.5f * scale : (kernel == 1 ? 2f : 3f) * scale;
        return (int) Math.ceil(radius) + 1;
    }

    /**
     * The output bands of the two-pass route: {first output row, rows, first input row, input rows} - every input row the
     * output rows' vertical taps reach ((2 y + 1) in - out) / (2 out) +- {@code reachRows}, within the frame.
     */
    static int[][] plan(int inRows, int outRows, int bandRows, int reachRows) {
        final int bands = (outRows + bandRows - 1) / bandRows;
        final int[][] plan = new int[bands][];
        for (int b = 0; b < bands; b++) {
            final int y0 = b * bandRows, rows = Math.min(bandRows, outRows - y0);
            final long den = 2L * outRows;
            final int first = Math.max(0, (int) (((2L * y0 + 1) * inRows - outRows) / den) - reachRows);
            final int last = Math.min(inRows - 1, (int) (((2L * (y0 + rows - 1) + 1) * inRows - outRows) / den) + reachRows);
            plan[b] = new int[]{y0, rows, first, last - first + 1};
        }
        return plan;
    }

    /** The two passes into {@code out}; false (nothing to keep: the one pass then draws every pixel) when GL refuses them. */
    private boolean separable(GLTexture in, GLTexture out, int kernel) {
        final int[][] plan = plan(in.size.y, target.y, BAND_ROWS, reach(in.size.y, target.y, kernel) + REACH_MARGIN);
        int bandHeight = 1;
        for (int[] b : plan) bandHeight = Math.max(bandHeight, b[3]);
        GLTexture band = null;
        try {
            //noinspection StatementWithEmptyBody: only the passes below decide
            while (android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR) { }
            band = new GLTexture(new Point(target.x, bandHeight), new com.scamera.app.processing.opengl.GLFormat(
                    com.scamera.app.processing.opengl.GLFormat.DataType.FLOAT_32, 4), null,
                    android.opengl.GLES30.GL_NEAREST, GL_CLAMP_TO_EDGE);
            band.renderInto();
            if (android.opengl.GLES30.glCheckFramebufferStatus(android.opengl.GLES30.GL_FRAMEBUFFER)
                    != android.opengl.GLES30.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.w("SCAM_HDR", "final resize: RGBA32F band not renderable, one pass");
                return false;
            }
            for (int[] b : plan) {
                pass(1, in, in.size, kernel, b[2]);
                band.renderInto();
                rows(0, b[3]);
                pass(2, band, in.size, kernel, b[2]);
                out.renderInto();
                rows(b[0], b[1]);
            }
            final int error = android.opengl.GLES30.glGetError();
            if (error == android.opengl.GLES30.GL_NO_ERROR) return true;
            android.util.Log.w("SCAM_HDR", "final resize two passes: GL error 0x" + Integer.toHexString(error) + ", one pass");
        } catch (RuntimeException e) {
            android.util.Log.w("SCAM_HDR", "final resize two passes: " + e + ", one pass");
            shaderProg.setDefine("PASS", 0); // a failed compile leaves its define pending: the one pass's own
        } finally {
            if (band != null) band.close(); // (released when the GPU is done with it)
        }
        return false;
    }

    private void pass(int which, GLTexture source, Point inSize, int kernel, int rowOrigin) {
        shaderProg.setDefine("PASS", which);
        shaderProg.useAssetProgram("hybrid/final_resize", false);
        shaderProg.setTexture("InputBuffer", source);
        shaderProg.setVar("kernel", kernel);
        shaderProg.setVar("inSize", inSize.x, inSize.y);
        shaderProg.setVar("outSize", target.x, target.y);
        shaderProg.setVar("rowOrigin", rowOrigin);
    }

    /** Rows {@code y0} .. {@code y0 + count - 1} of the bound target, in the paced bands of GLProg.drawBlocks. */
    private void rows(int y0, int count) {
        final String name = shaderProg.activeName;
        for (int y = y0, end = y0 + count; y < end; ) {
            final int r = Math.min(end - y, com.scamera.app.processing.opengl.GpuPace.bandRows(name, target.x,
                    com.scamera.app.processing.opengl.GLDrawParams.TILE_ROWS));
            android.opengl.GLES30.glViewport(0, y, target.x, r);
            shaderProg.draw();
            android.opengl.GLES30.glFlush();
            com.scamera.app.processing.opengl.GpuPace.submitted(name, (long) target.x * r);
            y += r;
        }
    }
}
