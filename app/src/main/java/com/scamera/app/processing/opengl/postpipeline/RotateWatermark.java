package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.util.Log;

/**
 * The last post pass: the processed frame turned into the photo's upright pixels (and mirrored for the front camera)
 * by RotateWatermark/addwatermark_rotate. P628: the watermark is drawn into the finished photo by PhotoWatermark after the
 * crops and resizes that follow (blended here, a zoomed shot cut it and a resize scaled it). P659: the pass reads nothing
 * but its input - the unused watermark and blue-noise textures (and the noise.png asset) are gone.
 */
public class RotateWatermark extends Node {
    /** The sensor rotation of the shot (degrees). */
    private final int rotation;

    public RotateWatermark(int rotation) {
        super("", "Rotate");
        this.rotation = rotation;
    }

    /** The shader's quarter turns of a sensor rotation (0, 90, 180, 270 degrees: 0, 3, 2, 1); -1 for any other. */
    static int quarterTurns(int degrees) {
        if (degrees < 0 || degrees > 270 || degrees % 90 != 0) return -1;
        return (4 - degrees / 90) % 4; // the shader turns the other way
    }

    @Override
    public void bindShader() {
        // the program is chosen in Run
    }

    @Override
    public void runPass() {
        shaderProg.useAssetProgram("RotateWatermark/addwatermark_rotate");
        shaderProg.setTexture("InputBuffer", prevPass.passOut);
        final int turns = quarterTurns(rotation);
        Log.d(passName, "Rotation:" + rotation + " selected rotation:" + turns);
        shaderProg.setVar("rotate", turns);
        shaderProg.setVar("mirror", hostPipeline.shotParams.mirrored ? 1 : 0);
        // after the hybrid final resize the working image (and cropSize) are at the final size already
        final PostPipeline post = (PostPipeline) hostPipeline;
        final Point raw = post.finalSize != null ? prevPass.passOut.size : hostPipeline.shotParams.rawSize;
        shaderProg.setVar("cropSize", post.cropSize);
        shaderProg.setVar("rawSize", raw);
        // P713: the deferred last pass (GLDeferredDraw) draws the lines each output tile reads, by this pass's mapping
        final com.scamera.app.processing.opengl.GLDeferredDraw deferred = com.scamera.app.processing.opengl.GLDeferredDraw.pending();
        if (deferred != null) deferred.mapTiles(prevPass.passOut, turns, hostPipeline.shotParams.mirrored, raw.y - post.cropSize.y);
        Log.d(passName, "Crop size:" + post.cropSize + " Raw size:" + hostPipeline.shotParams.rawSize);
    }
}
