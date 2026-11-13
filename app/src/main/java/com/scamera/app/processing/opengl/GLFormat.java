package com.scamera.app.processing.opengl;

import android.opengl.GLES30;

/**
 * The texel layout of a texture: a sample type ({@link DataType}) times 1 to 4 channels, with the sampling the texture is made
 * with ({@link #filter}, {@link #wrap}). The GL enums of a layout come from per-type tables: the sized internal format, the
 * client-side format family and the component type of glTexImage2D / glReadPixels.
 */
public class GLFormat {
    /** Client-side format families by channel count (index channels - 1). */
    private static final int[] COLOUR = {GLES30.GL_RED, GLES30.GL_RG, GLES30.GL_RGB, GLES30.GL_RGBA};
    private static final int[] INTEGER = {GLES30.GL_RED_INTEGER, GLES30.GL_RG_INTEGER, GLES30.GL_RGB_INTEGER, GLES30.GL_RGBA_INTEGER};
    private static final int[] NO_FORMATS = null;

    /**
     * Sample types. Each knows its size in bytes, its sized internal formats for 1..4 channels (none: no texture of it is
     * made here), the client format family it is read and written with, and the GL component type. The 8-bit integer
     * types are uploaded with the plain colour family, as the textures of this app always were.
     */
    public enum DataType {
        NONE(0, NO_FORMATS, null, 0),
        FLOAT_16(2, new int[]{GLES30.GL_R16F, GLES30.GL_RG16F, GLES30.GL_RGB16F, GLES30.GL_RGBA16F}, COLOUR, GLES30.GL_FLOAT),
        FLOAT_32(4, new int[]{GLES30.GL_R32F, GLES30.GL_RG32F, GLES30.GL_RGB32F, GLES30.GL_RGBA32F}, COLOUR, GLES30.GL_FLOAT),
        FLOAT_64(8, NO_FORMATS, COLOUR, GLES30.GL_FLOAT),
        SIGNED_8(1, new int[]{GLES30.GL_R8I, GLES30.GL_RG8I, GLES30.GL_RGB8I, GLES30.GL_RGBA8I}, COLOUR, GLES30.GL_BYTE),
        SIGNED_16(2, new int[]{GLES30.GL_R16I, GLES30.GL_RG16I, GLES30.GL_RGB16I, GLES30.GL_RGBA16I}, INTEGER, GLES30.GL_SHORT),
        SIGNED_32(4, new int[]{GLES30.GL_R32I, GLES30.GL_RG32I, GLES30.GL_RGB32I, GLES30.GL_RGBA32I}, INTEGER, GLES30.GL_INT),
        SIGNED_64(8, NO_FORMATS, INTEGER, 0),
        UNSIGNED_8(1, new int[]{GLES30.GL_R8UI, GLES30.GL_RG8UI, GLES30.GL_RGB8UI, GLES30.GL_RGBA8UI}, COLOUR, GLES30.GL_UNSIGNED_BYTE),
        UNSIGNED_16(2, new int[]{GLES30.GL_R16UI, GLES30.GL_RG16UI, GLES30.GL_RGB16UI, GLES30.GL_RGBA16UI}, INTEGER, GLES30.GL_UNSIGNED_SHORT),
        UNSIGNED_32(4, new int[]{GLES30.GL_R32UI, GLES30.GL_RG32UI, GLES30.GL_RGB32UI, GLES30.GL_RGBA32UI}, INTEGER, GLES30.GL_UNSIGNED_INT),
        UNSIGNED_64(8, NO_FORMATS, INTEGER, 0),
        BOOLEAN(1, NO_FORMATS, null, 0),
        /** Normalised unsigned 8 bit (GL_R8 .. GL_RGBA8), sampled as float. */
        SIMPLE_8(1, new int[]{GLES30.GL_R8, GLES30.GL_RG8, GLES30.GL_RGB8, GLES30.GL_RGBA8}, COLOUR, GLES30.GL_UNSIGNED_BYTE);

        /** Bytes of one sample. */
        public final int mSize;
        private final int[] internal;
        private final int[] family;
        private final int glType;

        DataType(int bytes, int[] internal, int[] family, int glType) {
            this.mSize = bytes;
            this.internal = internal;
            this.family = family;
            this.glType = glType;
        }

        boolean floating() { return glType == GLES30.GL_FLOAT; }
    }

    public final int mChannels;
    final DataType mFormat;
    public int filter = GLES30.GL_NEAREST;
    public int wrap = GLES30.GL_CLAMP_TO_EDGE;

    /** One channel of {@code type}, nearest sampling. */
    public GLFormat(DataType type) {
        this(type, 1, false);
    }

    /** {@code channels} of {@code type}; float types sample linearly. */
    public GLFormat(DataType type, int channels) {
        this(type, channels, true);
    }

    /** The type and channels of {@code other}, with the default (nearest, clamped) sampling. */
    public GLFormat(GLFormat other) {
        this(other.mFormat, other.mChannels, false);
    }

    private GLFormat(DataType type, int channels, boolean linearFloats) {
        mFormat = type;
        mChannels = channels;
        if (linearFloats && type.floating()) filter = GLES30.GL_LINEAR;
    }

    /** P602: the sample type (ScamDiagnostics: which textures its sample pass reads as the float read-back does). */
    public DataType dataType() { return mFormat; }

    private boolean channelsKnown() { return mChannels >= 1 && mChannels <= 4; }

    /** The sized internal format; -1 for {@link DataType#NONE}, 0 for a layout no texture is made of. */
    public int getGLFormatInternal() {
        if (mFormat == DataType.NONE) return -1;
        return mFormat.internal != null && channelsKnown() ? mFormat.internal[mChannels - 1] : 0;
    }

    /** The client-side format of uploads and read-backs; 0 when there is none. */
    public int getGLFormatExternal() {
        return mFormat.family != null && channelsKnown() ? mFormat.family[mChannels - 1] : 0;
    }

    /** The component type of uploads and read-backs; 0 when there is none. */
    public int getGLType() { return mFormat.glType; }

    @Override
    public String toString() {
        return "GLFormat{mChannels=" + mChannels + ", mFormat=" + mFormat + '}';
    }
}
