package com.scamera.app.processing.opengl.postpipeline;

import com.scamera.app.processing.opengl.nodes.Node;

/**
 * Keeps the linear scene snapshot for the Ultra HDR gain-map pass (the linear RGB of the merge, before the WHY
 * tone). The exposure itself is WhyStats' (WhyAe). Renders nothing: passes the input texture through and marks the
 * program closed so the pipeline skips the draw for this node.
 */
public class LinearExposure extends Node {
    public LinearExposure() {
        super("", "LinearExposure");
    }

    @Override
    public void bindShader() {}

    @Override
    public void runPass() {
        PostPipeline pipeline = (PostPipeline) hostPipeline;
        if (pipeline.captureDemosaic) {
            pipeline.captureDemosaicLinear(prevPass.passOut, shaderProg);
        }
        passOut = prevPass.passOut;
        shaderProg.closed = true;
    }
}
