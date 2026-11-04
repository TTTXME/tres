package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P480 (owner, 2026-11-03: the dezipper «полностью идентично» the reference's): the reference's highlight_chroma_dezipper
 * (shaders/scamdn/hl_dezipper.glsl, its own GLSL) right after its resolve - here right after ScamHdrDenoise (WhyResolveDenoise) -
 * on a fast merge whose highlight path was on: the R - G / B - G differences smoothed over 5 x 5 pixels and the purple excess taken
 * out, blended by the worker's base highlight mask (HALF grid, linear as its R16F texture). The pipeline adds this node only when
 * the worker sent the mask ({@link ScamHybridBurst#lastHlMask}); every other burst keeps its pipeline as before.
 * Uniforms: u_tex_size = the RGB's size (the reference's OUT); u_sabre_scale = the output grid (1 on the fast merge: OUT = sensor);
 * u_inv_zoom = 1 - the merged RGB covers the whole sensor (the digital zoom crops later, after the tone), so the mask maps 1:1 and
 * one texel step is one sensor pixel, the footprint the reference's zoom x sabre_scale steps give on its zoomed OUT.
 */
public final class WhyHlDezipper extends Node {
    private final ScamHybridBurst.HlMask mask;

    public WhyHlDezipper(ScamHybridBurst.HlMask mask) {
        super("", "WhyHlDezipper");
        this.mask = mask;
    }

    @Override public void Compile() {}

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        final GLTexture input = previousNode.WorkingTexture;
        WorkingTexture = input;
        glProg.closed = true;
        final Point size = input.mSize;
        // P501: the Sabre scale of the merged RGB (1, 1.5 or 2; the reference's u_sabre_scale), the mask the sensor's HALF grid
        final float s = Math.max(1f, Math.min(2f, pipeline.mParameters.outputScale));
        if (mask == null || Math.abs(mask.width * 2 * s - size.x) > 4 * s || Math.abs(mask.height * 2 * s - size.y) > 4 * s
                || mask.halves.capacity() < (long) mask.width * mask.height * 2) {
            Log.w("SCAM_PIPELINE", "why-hl-dezipper: skipped (mask " + (mask == null ? "none" : mask.width + "x" + mask.height)
                    + " for " + size.x + "x" + size.y + " at grid " + s + ")");
            return;
        }
        final long started = System.nanoTime();
        GLTexture m = null;
        try {
            m = new GLTexture(new Point(mask.width, mask.height), new GLFormat(GLFormat.DataType.FLOAT_16, 1), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT, 1);
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, m.mTextureID);
            final java.nio.ByteBuffer halves = mask.halves.duplicate();
            halves.position(0);
            android.opengl.GLES30.glTexSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, 0, 0, mask.width, mask.height,
                    android.opengl.GLES30.GL_RED, android.opengl.GLES30.GL_HALF_FLOAT, halves);
            final int error = android.opengl.GLES30.glGetError();
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, 0);
            if (error != android.opengl.GLES30.GL_NO_ERROR) {
                Log.w("SCAM_PIPELINE", "why-hl-dezipper: skipped (mask upload GL error " + error + ")");
                return;
            }
            GLTexture out = pipeline.getMain();
            if (out == input) out = pipeline.getMain();
            glProg.useAssetProgram("scamdn/hl_dezipper", false);
            glProg.setTexture("u_merged_rgb", input);
            glProg.setTexture("u_highlight_mask", m);
            glProg.setVar("u_tex_size", size.x, size.y, 1f / size.x, 1f / size.y);
            glProg.setVar("u_sabre_scale", s);
            glProg.setVar("u_inv_zoom", 1f);
            glProg.drawBlocks(out);
            glProg.closed = true;
            WorkingTexture = out;
            Log.i("SCAM_PIPELINE", "why-hl-dezipper " + size.x + "x" + size.y + " mask " + mask.width + "x" + mask.height + " grid " + s
                    + " ms=" + (System.nanoTime() - started) / 1_000_000);
        } finally {
            if (m != null) m.close();
        }
    }
}
