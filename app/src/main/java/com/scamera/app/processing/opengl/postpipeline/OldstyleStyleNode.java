package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.oldstyle.OldstyleStyle;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.util.Log;

/**
 * P630c «Oldstyle»: the mode's style (the palette's «Тон» / «Цвет», the advanced parameters) on the finished photo, after the tone,
 * with the values of the press (ShotLook.Oldstyle.style): oldstyle/adjust.glsl, the viewfinder's function
 * (utils/import_oldstyle_adjust) on display values; its radii, blur step, vignette and grain cell are shares of the picture's size,
 * as on the screen. Only added when a parameter but the exposure is not 0 (P630e: the exposure is the tone's, OldstyleStyle.exposureEv).
 */
public final class OldstyleStyleNode extends Node {
    private final OldstyleStyle style;

    public OldstyleStyleNode(OldstyleStyle style) {
        super("", "OldstyleStyle");
        this.style = style;
    }

    @Override public void bindShader() {}

    @Override
    public void runPass() {
        final GLTexture in = prevPass.passOut;
        final Point size = in.size;
        final float[] u = style.uniformsAfterTone();
        shaderProg.useAssetProgram("oldstyle/adjust", false);
        shaderProg.setTexture("InputBuffer", in);
        for (int i = 0; i < 4; i++) shaderProg.setVar("uOs" + i, u[i * 4], u[i * 4 + 1], u[i * 4 + 2], u[i * 4 + 3]);
        // the main textures alternate: the tone wrote one, this pass writes the other
        passOut = hostPipeline.getMain();
        if (passOut == in) passOut = hostPipeline.getMain();
        shaderProg.drawBlocks(passOut);
        shaderProg.closed = true;
        Log.i("SCAM_PIPELINE", "Oldstyle style " + style + " grid=" + size.x + "x" + size.y);
    }
}
