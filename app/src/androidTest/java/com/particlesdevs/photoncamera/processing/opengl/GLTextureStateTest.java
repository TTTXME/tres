package com.particlesdevs.photoncamera.processing.opengl;

import org.junit.Test;

public class GLTextureStateTest {
    @Test public void highNamesPreserveSamplerBindingsAndPixels() {
        GLTextureStateProbe.verify();
    }
}
