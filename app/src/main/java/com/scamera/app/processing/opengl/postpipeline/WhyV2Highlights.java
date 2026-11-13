package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P614 «Why тон» V2: the reference tone's two passes on the clean RGB between its statistics / hires luma and its fusion, one to
 * one and in its order (GLPostProcessor::process 0x139680-0x139e30) - bracket_denoise when short frames were merged (the ceiling
 * above 1.05) and its strength is above 0.001; hr_extract at half the grid (threshold 0.85, the RGB read bilinear), box_blur_h4 /
 * v4 (radius 24), hr_apply at the grid (threshold 0.85, «Восстановление цвета светов») when that amount is above 0.001. Every
 * target RGBA16F, LINEAR, clamp to edge. Both 0 in the owner's configuration: no pass then. Replaces State.clean; the image passes.
 */
public final class WhyV2Highlights extends Node {
    public WhyV2Highlights() { super("", "WhyV2Highlights"); }
    @Override public void bindShader() {}

    /** hr_extract's / hr_apply's u_threshold (0x3f59999a) and the box radius. */
    static final float HR_THRESHOLD = 0.85f;
    static final int HR_RADIUS = 24;

    /** Whether bracket_denoise runs: the ceiling (short frames merged) above 1.05 and the strength above 0.001, as the native test. */
    static boolean bracketOn(float clip, float strength) {
        return clip > 1.05f && strength > 0.001f;
    }

    /** Whether the highlight reconstruction runs (hr_amount above 0.001). */
    static boolean hrOn(float amount) {
        return amount > 0.001f;
    }

    /** hr_extract's grid: half the image (integer halves, at least 1). */
    static Point half(Point size) {
        return new Point(Math.max(1, size.x / 2), Math.max(1, size.y / 2));
    }

    private static GLTexture rgba(Point size) {
        return new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
    }

    @Override
    public void runPass() {
        final PostPipeline pipeline = (PostPipeline) hostPipeline;
        passOut = prevPass.passOut;
        shaderProg.closed = true;
        final WhyV2Stats.State v2 = pipeline.whyV2;
        if (v2 == null || v2.ae == null || v2.clean == null) throw new IllegalStateException("WHY V2: no statistics (WhyV2Stats did not run)");
        final WhyV2Ae.Params p = v2.params;
        final boolean bracket = bracketOn(v2.ae.clip, p.bracketDenoise), hr = hrOn(p.hrAmount);
        if (!bracket && !hr) return;
        final long started = System.currentTimeMillis();
        final Point size = v2.clean.size;
        if (bracket) {
            final GLTexture out = rgba(size);
            shaderProg.useAssetProgram("why/v2_bracket_dn", false);
            shaderProg.setTexture("u_tex_rgb", v2.clean);
            shaderProg.setVar("u_width", size.x);
            shaderProg.setVar("u_height", size.y);
            shaderProg.setVar("u_clip_ceiling", v2.ae.clip);
            shaderProg.setVar("u_strength", p.bracketDenoise);
            shaderProg.drawBlocks(out);
            shaderProg.closed = true;
            v2.clean.close();
            v2.clean = out;
        }
        if (hr) {
            final Point h = half(size);
            GLTexture extracted = null, blurred = null, diffused = null;
            try {
                extracted = rgba(h);
                shaderProg.useAssetProgram("why/v2_hr_extract", false);
                shaderProg.setTexture("u_tex_rgb", v2.clean);
                shaderProg.setVar("u_width", h.x);
                shaderProg.setVar("u_height", h.y);
                shaderProg.setVar("u_threshold", HR_THRESHOLD);
                shaderProg.drawBlocks(extracted);
                blurred = rgba(h);
                diffused = rgba(h);
                for (int dir = 0; dir < 2; dir++) {
                    shaderProg.setDefine("DIR", dir);
                    shaderProg.useAssetProgram("why/v2_box", false);
                    shaderProg.setTexture("u_input_tex", dir == 0 ? extracted : blurred);
                    shaderProg.setVar("u_width", h.x);
                    shaderProg.setVar("u_height", h.y);
                    shaderProg.setVar("u_radius", HR_RADIUS);
                    shaderProg.drawBlocks(dir == 0 ? blurred : diffused);
                }
                final GLTexture out = rgba(size);
                shaderProg.useAssetProgram("why/v2_hr_apply", false);
                shaderProg.setTexture("u_tex_rgb", v2.clean);
                shaderProg.setTexture("u_tex_diffused_hr", diffused);
                shaderProg.setVar("u_width", size.x);
                shaderProg.setVar("u_height", size.y);
                shaderProg.setVar("u_threshold", HR_THRESHOLD);
                shaderProg.setVar("u_hr_amount", p.hrAmount);
                shaderProg.drawBlocks(out);
                shaderProg.closed = true;
                v2.clean.close();
                v2.clean = out;
            } finally {
                if (extracted != null) extracted.close();
                if (blurred != null) blurred.close();
                if (diffused != null) diffused.close();
            }
        }
        Log.i("SCAM_PIPELINE", "WHY V2 highlights bracket=" + (bracket ? p.bracketDenoise : 0f) + " hr=" + (hr ? p.hrAmount : 0f)
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
