package scam.host;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
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
            final ByteBuffer px = source.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32, 4));
            px.order(ByteOrder.nativeOrder());
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
