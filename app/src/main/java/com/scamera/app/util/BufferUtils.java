package com.scamera.app.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Direct buffers for GL uploads. */
public final class BufferUtils {
    private BufferUtils() {
    }

    /** {@code values} as a direct little-endian buffer of floats, positioned at 0 (a texture's or a buffer's data). */
    public static ByteBuffer floatBuffer(float[] values) {
        final ByteBuffer out = ByteBuffer.allocateDirect(Float.BYTES * values.length).order(ByteOrder.LITTLE_ENDIAN);
        out.asFloatBuffer().put(values);
        return out; // asFloatBuffer() is a view: the byte buffer's own position stays 0
    }
}
