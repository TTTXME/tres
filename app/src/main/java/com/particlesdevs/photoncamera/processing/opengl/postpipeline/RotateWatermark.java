package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;
import android.graphics.Point;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.opengl.GLImage;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

import java.io.IOException;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_REPEAT;

/**
 * The final rotation (and the front camera's mirror) of the processed frame into the photo's upright pixels. P628: the watermark is
 * no longer blended here - PhotoWatermark draws it into the finished photo after the crops and resizes that follow this pass (a
 * zoomed shot cut the signature blended here, a resize scaled it); the shader's WATERMARK branch stays off.
 */
public class RotateWatermark extends Node {
    private int rotate;
    private boolean watermarkNeeded;
    private GLImage watermark;
    private GLImage noise;
    public RotateWatermark(int rotation) {
        super("", "Rotate");
        rotate = rotation;
        watermarkNeeded = false; // P628: PhotoWatermark
    }

    @Override
    public void Compile() {}
    @Override
    public void AfterRun() {
        if(watermark != null) watermark.close();
        if(noise != null) noise.close();
    }

    @Override
    public void Run() {

        final boolean legacy = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy();
        glProg.setDefine("WATERMARK",watermarkNeeded);

        glProg.useAssetProgram("RotateWatermark/addwatermark_rotate");
        // W1.11: with WATERMARK 0 the shader never reads the watermark, and the noise it samples is not used (the dither
        // line is commented out): no font rendering, logo or noise.png decode and no two uploads for nothing.
        if (watermarkNeeded || legacy) try {
            watermark = new GLImage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
            noise = new GLImage(ScameraApp.getAssetLoader().getInputStream("noise.png"));
            glProg.setTexture("Watermark", new GLTexture(watermark,GL_LINEAR,GL_CLAMP_TO_EDGE,0));
            glProg.setTexture("Noise", new GLTexture(noise,GL_LINEAR,GL_REPEAT,0));
        } catch (IOException e) {
            Log.d(Name,"Failed to load watermark or noise texture:" + Log.getStackTraceString(e));
        }

        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        int rot = -1;
        Log.d(Name,"Rotation:"+rotate);
        switch (rotate){
            case 0:
                //WorkingTexture = new GLTexture(size.x,size.y, previousNode.WorkingTexture.mFormat, null);
                rot = 0;
                break;
            case 90:
                //WorkingTexture = new GLTexture(size.y,size.x, previousNode.WorkingTexture.mFormat, null);
                rot = 3;
                break;
            case 180:
                //WorkingTexture = new GLTexture(size, previousNode.WorkingTexture.mFormat, null);
                rot = 2;
                break;
            case 270:
                //WorkingTexture = new GLTexture(size.y,size.x, previousNode.WorkingTexture.mFormat, null);
                rot = 1;
                break;
        }
        Log.d(Name,"selected rotation:"+rot);
        glProg.setVar("rotate",rot);
        if(basePipeline.mParameters.mirror) {
            glProg.setVar("mirror", 1);
        } else {
            glProg.setVar("mirror", 0);
        }
        // After the hybrid final resize the working image (and cropSize) are already at the final size.
        Point rawSz = ((PostPipeline) basePipeline).finalSize != null ? previousNode.WorkingTexture.mSize : basePipeline.mParameters.rawSize;
        glProg.setVar("cropSize",((PostPipeline)basePipeline).cropSize);
        glProg.setVar("rawSize",rawSz);
        Log.d(Name,"Crop size:"+((PostPipeline)basePipeline).cropSize);
        Log.d(Name,"Raw size:"+basePipeline.mParameters.rawSize);

    }
}
