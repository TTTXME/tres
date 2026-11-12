package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Log;

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

    @Override public void Compile() {}

    @Override
    public void Run() {
        final GLTexture in = previousNode.WorkingTexture;
        final Point size = in.mSize;
        final float[] u = style.uniformsAfterTone();
        glProg.useAssetProgram("oldstyle/adjust", false);
        glProg.setTexture("InputBuffer", in);
        for (int i = 0; i < 4; i++) glProg.setVar("uOs" + i, u[i * 4], u[i * 4 + 1], u[i * 4 + 2], u[i * 4 + 3]);
        // the main textures alternate: the tone wrote one, this pass writes the other
        WorkingTexture = basePipeline.getMain();
        if (WorkingTexture == in) WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
        Log.i("SCAM_PIPELINE", "Oldstyle style " + style + " grid=" + size.x + "x" + size.y);
    }
}
