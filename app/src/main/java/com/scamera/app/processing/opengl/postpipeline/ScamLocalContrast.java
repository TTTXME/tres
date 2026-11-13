package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.GLUtils;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * Mid-frequency local contrast ("texture") of the SCAM HDR render, on the display-encoded image after
 * the tone curves and before sharpening. A SCAM render carries 20-45 % more contrast than ours in
 * the structures of 2-16 pixels (lettering, weave, wood grain, the body of an edge) while the finest
 * scale and the noise agree; fusion and USM cannot add that without raising the noise. The Laplacian
 * levels 1-3 of the luminance are scaled where their local amplitude is well above the band's own noise
 * floor (a low percentile of the amplitude), and less on strong edges.
 */
public final class ScamLocalContrast extends Node {
    public ScamLocalContrast() { super("", "ScamLocalContrast"); }
    @Override public void bindShader() {}

    /** Low percentile of a level's local amplitude, from a coarse RGBA read-back. */
    private float noiseFloor(GLTexture energy) {
        GLTexture coarse = new GLTexture(new Point(96, 72), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        int[] oldRead = new int[1], framebuffer = new int[1];
        try {
            shaderProg.useAssetProgram("scamlc/coarse", false);
            shaderProg.setTexture("InputBuffer", energy);
            shaderProg.drawBlocks(coarse);
            android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, coarse.id, 0);
            java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(96 * 72 * 16).order(java.nio.ByteOrder.nativeOrder());
            android.opengl.GLES30.glReadPixels(0, 0, 96, 72, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_FLOAT, data);
            float[] values = new float[96 * 72];
            int n = 0;
            for (int i = 0; i < values.length; i++) {
                float v = data.getFloat(i * 16);
                if (v > 0f && !Float.isNaN(v)) values[n++] = v;
            }
            if (n == 0) return 0f;
            java.util.Arrays.sort(values, 0, n);
            return values[n / 4];
        } catch (RuntimeException e) {
            return 0f;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
            coarse.close();
        }
    }

    @Override public void runPass() {
        PostPipeline pipeline = (PostPipeline) hostPipeline;
        passOut = prevPass.passOut;
        // Texture on top of the WHY tone (hybrid switch why_texture), at the strength matched to a SCAM render.
        final float amount = 1f;
        long started = System.currentTimeMillis();
        GLFormat mono = new GLFormat(GLFormat.DataType.FLOAT_16, 1);
        GLTexture luma = new GLTexture(passOut.size, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        shaderProg.useAssetProgram("scamlc/luma", false);
        shaderProg.setTexture("InputBuffer", passOut);
        shaderProg.drawBlocks(luma);
        // Sabre 2x grid (outputScale 2): the bands are defined in SENSOR pixels, so the pyramid gets log2(s) extra fine
        // levels and every band index shifts by e; the sub-sensor band(s) laplace[0..e-1] (the merge's own extra detail)
        // stay at gain 1. At s = 1 (e = 0) this is the 1x pyramid unchanged.
        final int s = Math.max(1, Math.round(pipeline.shotParams.outputScale));
        final int e = 31 - Integer.numberOfLeadingZeros(s); // floor(log2(s)): 0 at 1x, 1 on the 2x grid
        GLUtils.Pyramid pyramid = glKit.createPyramid(5 + e, 2, luma);
        // levels e+1..e+3 (2-16 sensor px) and, last, the finest 1-sensor-px level e
        GLTexture[] energy = new GLTexture[4];
        float[] floors = new float[4];
        for (int i = 0; i < 4; i++) {
            GLTexture lap = pyramid.bands[i < 3 ? e + i + 1 : e];
            energy[i] = new GLTexture(lap.size, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            shaderProg.useAssetProgram("scamlc/energy", false);
            shaderProg.setTexture("InputBuffer", lap);
            shaderProg.drawBlocks(energy[i]);
            floors[i] = Math.max(noiseFloor(energy[i]), 1.0e-5f);
        }
        float g1 = PreferenceKeys.routeInternalValue("texture_g1", 1.25f);
        float g2 = PreferenceKeys.routeInternalValue("texture_g2", 1.3f);
        float g3 = PreferenceKeys.routeInternalValue("texture_g3", 1.3f);
        // Finest scale (about 1 px): tuned together with the former SCAM unsharp mask (ScamSharpen, removed in P118), 1.35
        // brought the 0.6-1.2 px signal of a daylight scene to an SCAM render's (and past a CameraPit 6.1 one), at no more
        // noise. The merge-only SoCs (8 Gen 3 etc.) have the noisier frames, where this band (gated by its own noise floor)
        // carries more of the detail: 1.5.
        float g0 = PreferenceKeys.routeInternalValue("texture_g0", PreferenceKeys.isScamNetSoc() ? 1.35f : 1.5f);
        // The output (a main, made again on its next use above 100 MP or with early release) is taken before the samplers
        // are bound: a new texture binds itself on unit 1 + its name, which may be a sampler's unit (the pass then read its
        // own output as a band).
        final GLTexture out = pipeline.getMain();
        shaderProg.useAssetProgram("scamlc/apply", false);
        shaderProg.setTexture("InputBuffer", passOut);
        // apply.glsl samples every band bilinearly by normalised position, so only the level choice moves with e
        shaderProg.setTexture("Lap0", pyramid.bands[e]);
        shaderProg.setTexture("Lap1", pyramid.bands[e + 1]);
        shaderProg.setTexture("Lap2", pyramid.bands[e + 2]);
        shaderProg.setTexture("Lap3", pyramid.bands[e + 3]);
        shaderProg.setTexture("En0", energy[3]);
        shaderProg.setTexture("En1", energy[0]);
        shaderProg.setTexture("En2", energy[1]);
        shaderProg.setTexture("En3", energy[2]);
        shaderProg.setVar("gain", (g1 - 1f) * amount, (g2 - 1f) * amount, (g3 - 1f) * amount, (g0 - 1f) * amount);
        shaderProg.setVar("floorE", floors[0], floors[1], floors[2], floors[3]);
        shaderProg.setVar("core", PreferenceKeys.routeInternalValue("texture_core0", 0.8f), PreferenceKeys.routeInternalValue("texture_core1", 1.8f));
        shaderProg.setVar("bmax", PreferenceKeys.routeInternalValue("texture_bmax", 0.12f));
        passOut = out;
        shaderProg.drawBlocks(passOut);
        shaderProg.closed = true;
        for (GLTexture en : energy) en.close();
        pyramid.releasePyramid();
        Log.i("SCAM_PIPELINE", "localContrast amount=" + amount + " gains=" + g1 + "," + g2 + "," + g3 + " fine=" + g0
                + " floors=" + floors[0] + "," + floors[1] + "," + floors[2] + "," + floors[3] + " step=" + s + " subLevels=" + e
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
