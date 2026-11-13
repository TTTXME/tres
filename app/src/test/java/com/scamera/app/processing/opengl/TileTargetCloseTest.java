package com.scamera.app.processing.opengl;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import android.opengl.GLES20;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;

/**
 * P638: GLCoreBlockProcessing.close() deleted its tile target twice - on its context, and again after super.close() had
 * released that context (the owner's logs: «E libEGL: call to OpenGL ES API with no current context» on the processing thread of
 * every shot). The second delete is for a context that could not be made current at the first one only.
 */
public class TileTargetCloseTest {
    private static GLCoreBlockProcessing processing() throws Exception {
        final GLCoreBlockProcessing p = mock(GLCoreBlockProcessing.class, CALLS_REAL_METHODS);
        set(p, "bindFB", new int[]{7});
        set(p, "bindRB", new int[]{8});
        return p;
    }

    private static void set(Object o, String name, int[] value) throws Exception {
        final Field f = GLContext.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    @Test public void theTileTargetIsDeletedOnceOnItsContext() throws Exception {
        final GLCoreBlockProcessing p = processing();
        doNothing().when(p).makeCurrent();
        try (MockedStatic<GLES20> gl = Mockito.mockStatic(GLES20.class)) {
            p.close();
            gl.verify(() -> GLES20.glDeleteFramebuffers(anyInt(), any(int[].class), anyInt()), times(1));
            gl.verify(() -> GLES20.glDeleteRenderbuffers(anyInt(), any(int[].class), anyInt()), times(1));
        }
        assertEquals(0, p.bindFB[0]);
        assertEquals(0, p.bindRB[0]);
    }

    @Test public void aContextThatCouldNotBeMadeCurrentStillGetsTheDelete() throws Exception {
        final GLCoreBlockProcessing p = processing();
        doThrow(new IllegalStateException("EGL_BAD_CONTEXT")).when(p).makeCurrent();
        try (MockedStatic<GLES20> gl = Mockito.mockStatic(GLES20.class)) {
            p.close();
            gl.verify(() -> GLES20.glDeleteFramebuffers(anyInt(), any(int[].class), anyInt()), times(1));
            gl.verify(() -> GLES20.glDeleteRenderbuffers(anyInt(), any(int[].class), anyInt()), times(1));
        }
    }
}
