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
        switch (degrees) {
            case 0: return 0;
            case 90: return 3;
            case 180: return 2;
            case 270: return 1;
            default: return -1;
        }
    }

    @Override
    public void Compile() {
        // the program is chosen in Run
    }

    @Override
    public void Run() {
        glProg.useAssetProgram("RotateWatermark/addwatermark_rotate");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        final int turns = quarterTurns(rotation);
        Log.d(Name, "Rotation:" + rotation + " selected rotation:" + turns);
        glProg.setVar("rotate", turns);
        glProg.setVar("mirror", basePipeline.mParameters.mirror ? 1 : 0);
        // after the hybrid final resize the working image (and cropSize) are at the final size already
        final PostPipeline post = (PostPipeline) basePipeline;
        final Point raw = post.finalSize != null ? previousNode.WorkingTexture.mSize : basePipeline.mParameters.rawSize;
        glProg.setVar("cropSize", post.cropSize);
        glProg.setVar("rawSize", raw);
        Log.d(Name, "Crop size:" + post.cropSize + " Raw size:" + basePipeline.mParameters.rawSize);
    }
}
