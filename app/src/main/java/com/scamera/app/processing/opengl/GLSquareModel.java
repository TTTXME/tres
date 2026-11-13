package com.scamera.app.processing.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_TRIANGLE_STRIP;
import static android.opengl.GLES20.glDisableVertexAttribArray;
import static android.opengl.GLES20.glDrawArrays;
import static android.opengl.GLES20.glEnableVertexAttribArray;
import static android.opengl.GLES20.glVertexAttribPointer;

/** The full-viewport quad every fragment pass draws: four (x, y, z) corners as one triangle strip, from client memory. */
final class GLSquareModel {
    private static final float[] STRIP = {-1, 1, 0, -1, -1, 0, 1, 1, 0, 1, -1, 0};
    private static final int COMPONENTS = 3;
    private final FloatBuffer corners;

    GLSquareModel() {
        corners = ByteBuffer.allocateDirect(STRIP.length * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        corners.put(STRIP).position(0);
    }

    /** Draws the quad with its corners in vertex attribute {@code attribute}. */
    void draw(int attribute) {
        glEnableVertexAttribArray(attribute);
        glVertexAttribPointer(attribute, COMPONENTS, GL_FLOAT, false, COMPONENTS * Float.BYTES, corners);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, STRIP.length / COMPONENTS);
        glDisableVertexAttribArray(attribute);
    }
}
