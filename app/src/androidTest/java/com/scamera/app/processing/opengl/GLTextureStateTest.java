package com.scamera.app.processing.opengl;

import org.junit.Test;

public class GLTextureStateTest {
    @Test public void highNamesPreserveSamplerBindingsAndPixels() {
        GLTextureStateProbe.verify();
    }
}
