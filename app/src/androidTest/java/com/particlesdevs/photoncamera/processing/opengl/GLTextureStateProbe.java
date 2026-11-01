package com.particlesdevs.photoncamera.processing.opengl;

import android.graphics.Point;
import java.nio.ByteBuffer;
import static android.opengl.GLES30.*;

public final class GLTextureStateProbe {
    private static int get(int key) {
        int[] value = new int[1];
        glGetIntegerv(key, value, 0);
        return value[0];
    }

    private static void binding(int active, int texture) {
        if (get(GL_ACTIVE_TEXTURE) != active || get(GL_TEXTURE_BINDING_2D) != texture)
            throw new AssertionError("Texture edit changed sampler state");
        if (glGetError() != GL_NO_ERROR) throw new AssertionError("Texture edit GL error");
    }

    public static void verify() {
        GLFormat format = new GLFormat(GLFormat.DataType.SIMPLE_8, 4);
        try (GLCoreBlockProcessing core = new GLCoreBlockProcessing(new Point(1, 1), format, GLDrawParams.Allocate.None)) {
            int units = get(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            int[] reserved = new int[units + 64];
            glGenTextures(reserved.length, reserved, 0);
            int active = GL_TEXTURE0 + 3, sentinel = reserved[0];
            glActiveTexture(active);
            glBindTexture(GL_TEXTURE_2D, sentinel);
            ByteBuffer pixels = ByteBuffer.allocateDirect(4);
            pixels.put(new byte[]{17, 63, (byte) 191, (byte) 255}).rewind();
            try (GLTexture texture = new GLTexture(new Point(1, 1), format, pixels);
                 GLImage image = new GLImage(new Point(1, 1), format, pixels);
                 GLTexture fromImage = new GLTexture(image)) {
                if (texture.mTextureID < units) throw new AssertionError("High texture name not exercised");
                binding(active, sentinel);
                texture.setFilter(GL_NEAREST);
                binding(active, sentinel);
                pixels.put(0, (byte) 91);
                texture.loadData(pixels);
                binding(active, sentinel);
                texture.BufferLoad();
                ByteBuffer read = ByteBuffer.allocateDirect(4);
                glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, read);
                for (int i = 0; i < 4; i++) {
                    if (read.get(i) != pixels.get(i)) throw new AssertionError("Texture upload changed data");
                }
                binding(active, sentinel);
            } finally {
                glDeleteTextures(reserved.length, reserved, 0);
            }
        }
    }
}
