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
        // The two idle ping-pong textures (and the scratch one) are not needed any more: free them before the
        // allocation so the GPU peak drops instead of growing (runAll would only close them after the readback).
        hostPipeline.lazyMain2 = null; // P179: never made now
        hostPipeline.lazyMain1 = null;
        GLTexture[] mains = {hostPipeline.main1, hostPipeline.main2, hostPipeline.main3};
        for (int i = 0; i < mains.length; i++) {
            if (mains[i] != null && mains[i] != in) {
                mains[i].close();
                if (i == 0) hostPipeline.main1 = null; else if (i == 1) hostPipeline.main2 = null; else hostPipeline.main3 = null;
            }
        }
        shaderProg.useAssetProgram("hybrid/final_resize", false);
        shaderProg.setTexture("InputBuffer", in);
        shaderProg.setVar("kernel", kernelIndex(kernelName));
        // Integer sizes: the shader splits the input-space centre exactly (a float centre lost precision on Adreno).
        shaderProg.setVar("inSize", in.size.x, in.size.y);
        shaderProg.setVar("outSize", target.x, target.y);
        passOut = new GLTexture(target, in.format, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        shaderProg.drawBlocks(passOut);
        ((PostPipeline) hostPipeline).finalResized = true;
        android.util.Log.i("SCAM_HDR", "hybrid final size " + target.x + "x" + target.y + " (" + kernelName + ", GPU) from "
                + in.size.x + "x" + in.size.y + " ms=" + (System.nanoTime() - started) / 1000000);
    }
}
