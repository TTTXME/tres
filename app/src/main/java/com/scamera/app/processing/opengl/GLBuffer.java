package com.scamera.app.processing.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static android.opengl.GLES31.GL_MAP_READ_BIT;
import static android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER;
import static android.opengl.GLES31.GL_STATIC_DRAW;
import static android.opengl.GLES31.glBindBuffer;
import static android.opengl.GLES31.glBindBufferBase;
import static android.opengl.GLES31.glBufferData;
import static android.opengl.GLES31.glDeleteBuffers;
import static android.opengl.GLES31.glGenBuffers;
import static android.opengl.GLES31.glMapBufferRange;
import static android.opengl.GLES31.glUnmapBuffer;
import static com.scamera.app.processing.opengl.GLCoreBlockProcessing.checkEglError;

/**
 * A shader storage buffer of {@code size} elements of one sample type, zero-filled when made. Programs bind it through
 * GLProg.setBufferCompute at the binding their layout declares - never at its own name (P317: a buffer name is no binding
 * index; it matched the shader only when the driver handed out the names 1..n, as Adreno does).
 */
public class GLBuffer implements AutoCloseable {
    public int mBufferID;
    /** Elements and bytes of the store. */
    public int size;
    public int byteSize;
    /** The usage hint of glBufferData. */
    public int mode;
    public GLFormat mFormat;
    /** The client copy the store is (re)filled from: zeros unless a caller wrote into it. */
    public ByteBuffer byteBuffer;

    public GLBuffer(int elements, GLFormat format) {
        this(elements, format, GL_STATIC_DRAW);
    }

    public GLBuffer(int elements, GLFormat format, int usage) {
        mFormat = new GLFormat(format);
        size = elements;
        byteSize = elements * format.mFormat.mSize;
        mode = usage;
        byteBuffer = ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder());
        final int[] name = new int[1];
        glGenBuffers(1, name, 0);
        mBufferID = name[0];
        Bind();
        Buffering();
    }

    public void Bind() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, mBufferID);
        checkEglError("bind buffer:" + mBufferID);
    }

    /** Binds this buffer at {@code index} of the indexed target {@code target} (GL_SHADER_STORAGE_BUFFER, ...). */
    public void BindBase(int index, int target) {
        Bind();
        glBindBufferBase(target, index, mBufferID);
        checkEglError("bind buffer base:" + mBufferID);
    }

    /** The whole store from {@link #byteBuffer} again (the bound buffer). */
    public void Buffering() {
        glBufferData(GL_SHADER_STORAGE_BUFFER, byteSize, byteBuffer.asIntBuffer(), mode);
        checkEglError("clear buffer:" + mBufferID);
    }

    /** The store as native-order ints (null when it could not be mapped); {@code clear}: refilled from byteBuffer after. */
    public int[] readBufferIntegers(boolean clear) {
        Bind();
        checkEglError("bind SSBO");
        final ByteBuffer mapped = (ByteBuffer) glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, byteSize, GL_MAP_READ_BIT);
        checkEglError("getByteBuffer");
        int[] values = null;
        if (mapped != null) {
            final IntBuffer ints = mapped.order(ByteOrder.nativeOrder()).asIntBuffer();
            values = new int[ints.remaining()];
            ints.get(values);
        }
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
        checkEglError("unmap buffer:" + mBufferID);
        if (clear) Buffering();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        checkEglError("unbind buffer:" + mBufferID);
        return values;
    }

    @Override
    public void close() {
        glDeleteBuffers(1, new int[]{mBufferID}, 0);
    }
}
