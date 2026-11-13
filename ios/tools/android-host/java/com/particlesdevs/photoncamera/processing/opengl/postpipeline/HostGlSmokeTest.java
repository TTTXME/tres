package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Point;
import android.opengl.GLES30;

import com.particlesdevs.photoncamera.processing.opengl.GLContext;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The host GL under Robolectric: a context, a program of the app's own source, a texture written and read back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = {scam.host.ShadowGLES20.class, scam.host.ShadowGLES30.class,
        scam.host.ShadowGLES31.class, scam.host.ShadowGLES32.class, scam.host.ShadowEGL14.class})
public class HostGlSmokeTest {
    @Test public void aProgramDrawsOnTheHostGpu() {
        org.robolectric.shadows.ShadowLog.stream = System.out;
        GLContext context = new GLContext(1, 1);
        System.out.println("HOST GL: " + GLES30.glGetString(GLES30.GL_RENDERER) + " / " + GLES30.glGetString(GLES30.GL_VERSION));
        final int w = 64, h = 32;
        ByteBuffer in = ByteBuffer.allocateDirect(w * h * 4 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < w * h * 4; i++) in.putFloat(i * 4, (i % 97) / 97f);
        GLTexture src = new GLTexture(new Point(w, h), new GLFormat(GLFormat.DataType.FLOAT_32, 4), in);
        GLTexture dst = new GLTexture(new Point(w, h), new GLFormat(GLFormat.DataType.FLOAT_32, 4));
        GLProg prog = new GLProg();
        prog.useProgram("#version 310 es\nprecision highp float;\nuniform sampler2D InputBuffer;\nout vec4 Output;\n"
                + "void main(){ Output = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0) * 2.0 + 0.25; }\n");
        prog.setTexture("InputBuffer", src);
        prog.drawBlocks(dst);
        ByteBuffer out = dst.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32, 4));
        out.order(ByteOrder.nativeOrder());
        for (int i = 0; i < w * h * 4; i++) assertEquals("texel value " + i, in.getFloat(i * 4) * 2f + 0.25f, out.getFloat(i * 4), 2e-3f);
        assertTrue(GLES30.glGetError() == GLES30.GL_NO_ERROR);
        prog.close();
        GLTexture.closeAll();
        context.close();
        System.out.println("HOST GL: a program drew " + w + "x" + h + " texels exactly");
    }
}
