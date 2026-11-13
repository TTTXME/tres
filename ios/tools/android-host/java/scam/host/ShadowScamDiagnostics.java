package scam.host;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The post pipeline's per-node hook (GLBasePipeline calls ScamDiagnostics.gpu after every node but the last) as a full dump:
 * with SCAM_HOST_DUMP set, each node's output texture is written as NN-<node>.f32 (SCRG header, RGBA float32 rows).
 */
@Implements(className = "com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamDiagnostics", isInAndroidSdk = false)
public class ShadowScamDiagnostics {
    private static int index;

    @Implementation protected static void gpu(String name, GLTexture source) {
        final String dir = System.getenv("SCAM_HOST_DUMP");
        if (dir == null || dir.isEmpty() || source == null) return;
        final File out = new File(dir, String.format(java.util.Locale.ROOT, "%02d-%s.f32", index++, name));
        try {
            // the texture itself through a read framebuffer of its own (the bound one is whatever the node drew last)
            final int[] oldRead = new int[1], fb = new int[1];
            android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
            android.opengl.GLES30.glGenFramebuffers(1, fb, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, fb[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, source.mTextureID, 0);
            final ByteBuffer px = ByteBuffer.allocateDirect(source.mSize.x * source.mSize.y * 16).order(ByteOrder.nativeOrder());
            android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ALIGNMENT, 4);
            android.opengl.GLES30.glReadPixels(0, 0, source.mSize.x, source.mSize.y, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_FLOAT, px);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            android.opengl.GLES30.glDeleteFramebuffers(1, fb, 0);
            try (FileOutputStream f = new FileOutputStream(out)) {
                final ByteBuffer h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                h.putInt(0x47524353).putInt(source.mSize.x).putInt(source.mSize.y).putInt(4);
                f.write(h.array());
                final byte[] row = new byte[px.remaining()];
                px.get(row);
                f.write(row);
            }
            System.out.println("HOST POST: node " + name + " " + source.mSize.x + "x" + source.mSize.y + " " + source.mFormat + " -> " + out.getName());
        } catch (IOException | RuntimeException e) {
            System.out.println("HOST POST: node " + name + " not dumped: " + e);
        }
    }
}
