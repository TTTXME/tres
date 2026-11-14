package scam.host;

import android.graphics.Bitmap;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.nio.ByteBuffer;

/**
 * The final readback's tile path (GLCoreBlockProcessing.TileBlitter, outputs above the pixel-pack buffer's 16 MP) on the host:
 * Robolectric's legacy Canvas draws nothing, so the strips it blits left a black photo. The tiles' rows go into one buffer of
 * the bitmap's size instead, and into the bitmap with copyPixelsFromBuffer at close (the pixel-pack path's own call).
 */
@Implements(className = "com.scamera.app.processing.opengl.GLCoreBlockProcessing$TileBlitter", isInAndroidSdk = false)
public class ShadowTileBlitter {
    private Bitmap dst;
    private ByteBuffer whole;
    private int rowBytes;

    @Implementation protected void __constructor__(Bitmap dst, int stripRows) {
        this.dst = dst;
        rowBytes = dst.getWidth() * 4;
        whole = ByteBuffer.allocateDirect(rowBytes * dst.getHeight());
    }

    @Implementation protected void blit(ByteBuffer tile, int y, int height) {
        try {
            final ByteBuffer rows = tile.duplicate();
            rows.clear();
            rows.limit(height * rowBytes);
            whole.position(y * rowBytes);
            whole.put(rows);
        } finally {
            tile.clear();
        }
    }

    @Implementation protected void close() {
        whole.rewind();
        dst.copyPixelsFromBuffer(whole);
    }
}
