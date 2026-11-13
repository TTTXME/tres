package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Point;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P630e «Oldstyle» at the Sabre sizes (owner: «Sabre при этом у нас должен работать, 1X/1.5X/2X»): the tone runs on the merged grid
 * of each scale (12 MP: 12 / 27 / 48 MP, 50 MP: 50 / 113 / 201 MP) - its native memory per pixel (measured on the host) decides V2
 * or V1, the 2.7 HDR's padding to 32 and the dehaze's to 8 keep the frame's phase at any size, the picture comes back at the grid's
 * size; the dehaze's grid from our lens-shading map; the order with the style (its exposure in the tone).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = android.app.Application.class)
public class OldstyleToneTest {
    static final long GB = 1L << 30;

    @Test public void theSabreGridsAndTheirRoute() {
        final int[][] sensors = {{4000, 3000}, {8160, 6144}};
        final String[] modes = {"sensor", "1.5x", "2x"};
        final long[][] want = {{12_000_000L, 27_000_000L, 48_000_000L}, {50_135_040L, 112_803_840L, 200_540_160L}};
        for (int s = 0; s < 2; s++) for (int m = 0; m < 3; m++) {
            final Point g = ScamHybridBurst.sabreSize(sensors[s][0], sensors[s][1], modes[m]);
            final long px = (long) g.x * g.y;
            assertEquals(modes[m], want[s][m], px);
            // the 2.7 HDR's frame padded to 32 and the dehaze's tiles of 8 from the origin: whole tiles, the phase at (0, 0)
            assertEquals(0, ((g.x + 31) / 32 * 32) % 32);
            assertTrue((g.x + 31) / 32 * 32 - g.x < 32 && (g.y + 7) / 8 * 8 - g.y < 8);
            // a phone with 8 GB free: V2 up to 4.8 GB of buffers (24 B / px with HDR, 30 without)
            final boolean v2Hdr = OldstyleTone.route(true, true, px, 8 * GB), v2Sdr = OldstyleTone.route(true, false, px, 8 * GB);
            assertEquals(modes[m] + " " + s, px * OldstyleTone.BYTES_V2_HDR <= 0.6 * 8 * GB, v2Hdr);
            assertEquals(px * OldstyleTone.BYTES_V2_SDR <= 0.6 * 8 * GB, v2Sdr);
            assertFalse(OldstyleTone.route(false, true, px, 8 * GB));
        }
        // the 12 MP sensor: V2 at every scale; the 50 MP one at 2x (201 MP) only with the memory there
        assertTrue(OldstyleTone.route(true, true, 48_000_000L, 4 * GB));
        assertFalse(OldstyleTone.route(true, true, 200_540_160L, 6 * GB));
        assertTrue(OldstyleTone.route(true, true, 200_540_160L, 12 * GB));
        // unknown memory: P630b's 100 MP rule
        // P630h «V2: доля памяти»: the default share is the route before the row; 30 % sends the 200 MP grid to V1 at 12 GB, 90 %
        // keeps V2 at 6 GB
        for (long px : new long[]{48_000_000L, 112_000_000L, 200_540_160L})
            for (long gb : new long[]{4, 6, 8, 12})
                assertEquals(OldstyleTone.route(true, true, px, gb * GB), OldstyleTone.route(true, true, px, gb * GB, OldstyleTone.MEMORY_SHARE));
        assertEquals(new com.scamera.app.processing.oldstyle.OldstyleAe.Params().memoryShare, OldstyleTone.MEMORY_SHARE, 0f);
        assertFalse(OldstyleTone.route(true, true, 200_540_160L, 12 * GB, 0.3f));
        assertTrue(OldstyleTone.route(true, true, 200_540_160L, 6 * GB, 0.9f));
        assertFalse(OldstyleTone.route(false, true, 12_000_000L, 12 * GB, 0.9f));
        assertTrue(OldstyleTone.route(true, true, 100_000_000L, 0));
        assertFalse(OldstyleTone.route(true, true, 100_000_001L, 0));
        assertEquals(34L * 48_000_000L, OldstyleTone.bytes(false, true, 48_000_000L));
    }

    @Test public void thePictureComesBackAtTheGridsSize() throws Exception {
        final String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/scamera/app/processing/opengl/postpipeline/OldstyleTone.java")), StandardCharsets.UTF_8);
        // one planar frame and one RGB8 picture of the input's size, no scaling of its own
        // P636: from the native allocator (not zeroed), six bytes a pixel as OldstyleNative.planar's, and given back
        assertTrue(src.contains("planar = buffer(Math.multiplyExact(Math.multiplyExact(size.x, size.y), 6));"));
        assertTrue(src.contains("out = buffer(Math.multiplyExact(size.x * size.y, 3));"));
        assertTrue(src.contains("for (ByteBuffer b : new ByteBuffer[]{strip, planar, out}) if (b != null) com.scamera.app.util.Allocator.free(b);"));
        assertTrue(src.contains("rgb = new GLTexture(size, new GLFormat(GLFormat.DataType.SIMPLE_8, 3), out"));
        assertFalse(src.contains("sabre") || src.contains("resize"));
        // P636: the frame's work is in oldstyle-tone-core.h
        final String jni = new String(Files.readAllBytes(Paths.get("src/main/cpp/oldstyle-tone-core.h")), StandardCharsets.UTF_8);
        assertTrue(jni.contains("const size_t pw = (w + 31) / 32 * 32, ph = (h + 31) / 32 * 32;"));
        assertTrue(jni.contains("const size_t pw = up8(std::max<size_t>(w, 8)), ph = up8(std::max<size_t>(h, 8));"));
        // P636: the threads are set before the module runs; the 16-bit read only where the driver names it, GL_INT otherwise
        assertTrue(src.contains("OldstyleNative.setThreads(OldstyleNative.threadsFor(Runtime.getRuntime().availableProcessors(),"));
        assertTrue(src.indexOf("OldstyleNative.setThreads(") < src.indexOf("OldstyleNative.render("));
        assertTrue(src.contains("GLES30.glReadPixels(0, y, size.x, rows, GLES30.GL_RGBA_INTEGER, GLES30.GL_INT, strip);"));
    }

    /** P636: 16-bit samples only for the pair GL_RGBA_INTEGER / GL_SHORT (0x8D99 / 0x1402) and with the switch on. */
    @Test public void sixteenBitReadOnlyWhereTheDriverNamesIt() {
        assertTrue(OldstyleTone.read16(0x8D99, 0x1402, true));
        assertFalse(OldstyleTone.read16(0x8D99, 0x1402, false));
        assertFalse(OldstyleTone.read16(0x8D99, 0x1404, true)); // GL_INT
        assertFalse(OldstyleTone.read16(0x1908, 0x1402, true)); // GL_RGBA
        assertFalse(OldstyleTone.read16(0, 0, true));
    }

    @Test public void shadingGridOfTheLensShadingMap() {
        // 3 x 2 nodes of (r, g_even, g_odd, b): the mean over the smallest node, the same for U and V
        final float[] map = {1, 1, 1, 1, 2, 2, 2, 2, 1.5f, 1.5f, 1.5f, 1.5f, 3, 3, 3, 3, 1, 1, 1, 1, 4, 4, 4, 4};
        final float[] g = OldstyleTone.shadingGrid(map, 3, 2);
        assertArrayEquals(new float[]{1, 1, 2, 2, 1.5f, 1.5f, 3, 3, 1, 1, 4, 4}, g, 1e-6f);
        assertNull(OldstyleTone.shadingGrid(null, 3, 2));
        assertNull(OldstyleTone.shadingGrid(map, 1, 2));
    }
}
