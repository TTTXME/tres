package com.scamera.app.processing.opengl.nodes;

import com.scamera.app.processing.opengl.GLBasePipeline;
import com.scamera.app.processing.opengl.GLInterface;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.GLUtils;
import com.scamera.app.settings.TunableInjector;

/**
 * One pass of a {@link GLBasePipeline}. The pipeline wires it up (GLBasePipeline.add: its program, its context and the pass
 * before it), then for each pass calls beforeShader, bindShader, refreshTunables, runPass and afterPass in that order. A pass
 * leaves its result in {@link #passOut}, or leaves its program bound for the pipeline to draw into {@link #drawTarget}.
 */
public class Node {
    /** What the pass produced; the next pass reads it through its {@link #prevPass}. */
    public GLTexture passOut;
    public String passName;
    // set by the pipeline when the pass is added
    public Node prevPass;
    public GLBasePipeline hostPipeline;
    public GLInterface glCtx;
    public GLUtils glKit;
    public GLProg shaderProg;
    /** The shader asset bindShader() makes current ("" for a pass that picks its programs itself). */
    private final String shaderAsset;

    public Node(String shaderAsset, String passName) {
        this.shaderAsset = shaderAsset;
        this.passName = passName;
    }

    /** The pass's @Tunable values from the settings, before every run (a changed setting is always picked up). */
    public void refreshTunables() {
        TunableInjector.inject(this);
    }

    /** Before the program is made current: a pass that sets defines does it here. */
    public void beforeShader() {
    }

    /** Makes the pass's shader asset the current program. */
    public void bindShader() {
        hostPipeline.glIface.prog.useAssetProgram(shaderAsset);
    }

    /** The pass itself (nothing for a pass the pipeline only draws). */
    public void runPass() {
    }

    /** After the pass: frees what it made for itself. */
    public void afterPass() {
    }

    /** Where the pipeline draws this pass's program when the pass did not draw it itself. */
    public GLTexture drawTarget() {
        return passOut;
    }
}
