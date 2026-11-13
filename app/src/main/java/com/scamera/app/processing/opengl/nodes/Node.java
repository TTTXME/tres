package com.scamera.app.processing.opengl.nodes;

import com.scamera.app.processing.opengl.GLBasePipeline;
import com.scamera.app.processing.opengl.GLInterface;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.GLUtils;
import com.scamera.app.settings.TunableInjector;

/**
 * One pass of a {@link GLBasePipeline}: the pipeline hands it its program, its context and the pass before it
 * (GLBasePipeline.add), then calls BeforeCompile, Compile, BeforeRun, Run and AfterRun. A pass leaves its result in
 * {@link #WorkingTexture} (or leaves its program for the pipeline to draw into {@link #GetProgTex}).
 */
public class Node {
    /** The pass's output (the next pass reads it through its previousNode). */
    public GLTexture WorkingTexture;
    public String Name;
    public Node previousNode;
    public GLBasePipeline basePipeline;
    public GLInterface glInt;
    public GLUtils glUtils;
    public GLProg glProg;
    /** The shader asset Compile() makes current ("" for a pass that picks its programs itself). */
    private final String shader;

    public Node(String shader, String name) {
        this.shader = shader;
        this.Name = name;
    }

    /** The pass's @Tunable values from the settings, before every run (a changed setting is always picked up). */
    public void BeforeRun() {
        TunableInjector.inject(this);
    }

    public void Run() {
    }

    public void AfterRun() {
    }

    public void BeforeCompile() {
    }

    public void Compile() {
        basePipeline.glint.glProgram.useAssetProgram(shader);
    }

    /** Where the pipeline draws this pass's program when the pass did not draw it itself. */
    public GLTexture GetProgTex() {
        return WorkingTexture;
    }
}
