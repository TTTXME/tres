package com.scamera.app.processing.opengl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;

import com.scamera.app.processing.heif.TenBitBitmaps;

import java.io.InputStream;
import java.nio.ByteBuffer;

/** Pixels on their way into a texture (GLTexture(GLImage)): a size, a layout and a direct buffer of the texels. */
public class GLImage implements AutoCloseable {
    public Point size;
    public ByteBuffer byteBuffer;
    GLFormat glFormat;

    /**
     * A bitmap's pixels: its own bytes when they are RGBA8 or R8 (8-bit normalised channels); any other config (RGBA_1010102 of
     * a 10-bit HEIC, RGBA_F16, RGB_565, hardware) converted to 8-bit sRGB RGBA.
     */
    public GLImage(Bitmap image) {
        size = new Point(image.getWidth(), image.getHeight());
        if (rawBytes(image)) {
            glFormat = new GLFormat(GLFormat.DataType.SIMPLE_8, channelsOf(image));
            byteBuffer = copyOf(image);
        } else {
            glFormat = new GLFormat(GLFormat.DataType.SIMPLE_8, 4);
            byteBuffer = TenBitBitmaps.rgba8(image);
        }
    }

    /** A decoded image (an asset: the blue-noise texture), its bytes as decoded. */
    public GLImage(InputStream encoded) {
        final Bitmap image = BitmapFactory.decodeStream(encoded);
        size = new Point(image.getWidth(), image.getHeight());
        glFormat = new GLFormat(GLFormat.DataType.SIMPLE_8, channelsOf(image));
        byteBuffer = copyOf(image);
        image.recycle();
    }

    /** {@code allocate}: a zeroed direct buffer of the whole image; otherwise no pixels (a texture without data). */
    public GLImage(Point size, GLFormat format, boolean allocate) {
        this(size, format, allocate ? ByteBuffer.allocateDirect(GLLimits.bufferBytes(size.x, size.y,
                (long) format.mChannels * format.mFormat.mSize, "image buffer")) : null);
    }

    public GLImage(Point size, GLFormat format, ByteBuffer pixels) {
        this.size = new Point(size);
        this.glFormat = new GLFormat(format);
        this.byteBuffer = pixels;
    }

    /** Whether the bitmap's own bytes are the texture: ARGB_8888 (RGBA8) and ALPHA_8 (R8). */
    static boolean rawBytes(Bitmap image) {
        return image.getConfig() == Bitmap.Config.ARGB_8888 || image.getConfig() == Bitmap.Config.ALPHA_8;
    }

    private static int channelsOf(Bitmap image) {
        return image.getByteCount() / (image.getWidth() * image.getHeight());
    }

    private static ByteBuffer copyOf(Bitmap image) {
        final ByteBuffer pixels = ByteBuffer.allocateDirect(image.getByteCount());
        image.copyPixelsToBuffer(pixels);
        pixels.position(0);
        return pixels;
    }

    /** Drops the reference to the pixels (the texture made from them keeps its own copy). */
    @Override
    public void close() {
        if (byteBuffer == null) return;
        try {
            byteBuffer.clear();
        } catch (RuntimeException ignored) {
        }
        byteBuffer = null;
    }
}
