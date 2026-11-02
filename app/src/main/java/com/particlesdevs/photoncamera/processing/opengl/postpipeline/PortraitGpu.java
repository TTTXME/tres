package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import android.opengl.GLES20;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.portrait.PortraitSkin;
import com.particlesdevs.photoncamera.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class PortraitGpu {
    private PortraitGpu() {}

    static PortraitSkin.Result measureScene(GLProg prog, PostPipeline pipeline, GLTexture source, float[] faces) {
        return measure(prog, source, faces, pipeline);
    }

    static PortraitSkin.Result measure(GLProg prog, GLTexture source, float[] faces) {
        return measure(prog, source, faces, null);
    }

    private static PortraitSkin.Result measure(GLProg prog, GLTexture source, float[] faces, PostPipeline pipeline) {
        if (faces == null || faces.length < 4) return null;
        final int side = Math.max(source.mSize.x, source.mSize.y);
        final Point size = new Point(Math.max(1, source.mSize.x * 192 / side), Math.max(1, source.mSize.y * 192 / side));
        try (GLTexture sample = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_32, 4), null,
                GLES20.GL_NEAREST, GLES20.GL_CLAMP_TO_EDGE)) {
            if (pipeline != null) prog.setDefine("CAMERA_SOURCE", 1);
            prog.useAssetProgram("portrait/sample", false);
            prog.setTexture("InputBuffer", source);
            if (pipeline != null) WhyTone.setColour(prog, pipeline, pipeline.GainMap, 1f);
            prog.setVar("sampleSizeU", size.x, size.y);
            prog.drawBlocks(sample);
            ByteBuffer bytes = ByteBuffer.allocateDirect(size.x * size.y * 16).order(ByteOrder.nativeOrder());
            sample.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32, 4), bytes);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null;
            float[] rgb = new float[size.x * size.y * 4];
            bytes.asFloatBuffer().get(rgb);
            return PortraitSkin.measure(rgb, 4, size.x, size.y, faces, 1f);
        } catch (RuntimeException e) {
            Log.w("SCAM_PIPELINE", "Portrait skin sample skipped: " + e);
            return null;
        } finally {
            source.BufferLoad();
            prog.closed = true;
        }
    }
}
