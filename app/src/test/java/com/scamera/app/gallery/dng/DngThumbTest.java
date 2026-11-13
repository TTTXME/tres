package com.scamera.app.gallery.dng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

/** P620: the DNG's grid thumbnail - a sparse render of the raw rows, and the faster parts of the render giving the same pixels. */
public class DngThumbTest {
    static final int W = DngPreviewTest.W, H = DngPreviewTest.H;

    static byte[] dng16(double[] n) {
        int[] raw = DngPreviewTest.scene(n, 64, 1023);
        DngPreviewTest.Tiff t = DngPreviewTest.baseTags(16, 1, n, 64, 1023);
        ByteBuffer d = ByteBuffer.allocate(2 * W * H).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : raw) d.putShort((short) v);
        t.blob(0x111, 4, 1, d.array());
        t.tag(0x117, 4, 1, 2 * W * H);
        return t.build();
    }

    @Test
    public void aSparseRenderReadsTwoRepeatRowsOfEachBlock() {
        int used = 0;
        for (int y = 0; y < 2 * 11; y++) if (DngPreview.sparseRow(y, 2, 11)) used++;
        assertEquals("2 of 11 Bayer row pairs", 2 * DngPreview.SPARSE_ROWS, used);
        for (int y = 0; y < 8; y++) assertTrue("small blocks read every row", DngPreview.sparseRow(y, 2, 3));
    }

    @Test
    public void theSparseThumbnailMatchesTheFullRender() throws Exception {
        double[] n = {0.5, 1.0, 0.7};
        byte[] file = dng16(n);
        // 4 x 4 px for 64 x 48: blocks of 6 x 6 Bayer tiles, sparse
        DngPreview.Result sparse = DngPreview.read(ByteBuffer.wrap(file), 4, 4, true);
        DngPreview.Result full = DngPreview.read(ByteBuffer.wrap(file), 4, 4, false);
        assertEquals(full.width, sparse.width);
        assertEquals(full.height, sparse.height);
        DngPreviewTest.assertGreyAndRed(sparse);
        for (int i = 0; i < full.argb.length; i++)
            for (int s = 0; s < 24; s += 8)
                assertTrue("pixel " + i, Math.abs((full.argb[i] >> s & 0xFF) - (sparse.argb[i] >> s & 0xFF)) <= 1);
        // the viewer's read is unchanged by the thumbnail flag's default
        assertTrue(Arrays.equals(full.argb, DngPreview.read(ByteBuffer.wrap(file), 4, 4).argb));
    }

    @Test
    public void theViewersBlockKeepsA200MpDngAt4096() {
        // 50 MP (4080 x 3072 Bayer tiles) for 4096: one tile a block, as before; 200 MP (8160 x 6144 tiles): two (4080 x 3072)
        assertEquals(1, DngPreview.viewerBlock(1, 4080, 3072, 4096, 4096));
        assertEquals(2, DngPreview.viewerBlock(1, 8160, 6144, 4096, 4096));
        assertEquals("thumbnails keep the block that covers the cell", 8, DngPreview.viewerBlock(8, 4080, 3072, 360, 360));
        assertEquals(4, DngPreview.viewerBlock(4, 8160, 6144, 2048, 2048));
    }

    @Test
    public void srgbStepsGiveTheCurveExactly() {
        Random r = new Random(7);
        for (int i = 0; i < 2_000_000; i++) {
            double v = i % 2 == 0 ? r.nextDouble() : Math.pow(r.nextDouble(), 5);
            assertEquals("v " + v, DngPreview.srgb8(v), DngPreview.srgb8Fast(v));
        }
        for (double v : new double[]{0, 1e-12, 0.0031308, 0.5, 0.999999, 1}) assertEquals(DngPreview.srgb8(v), DngPreview.srgb8Fast(v));
    }

    @Test
    public void selectionGivesTheSortedValue() {
        Random r = new Random(11);
        for (int t = 0; t < 50; t++) {
            float[] a = new float[1 + r.nextInt(5000)];
            for (int i = 0; i < a.length; i++) a[i] = t % 3 == 0 ? r.nextInt(7) : r.nextFloat(); // ties too
            float[] sorted = a.clone();
            Arrays.sort(sorted);
            int k = r.nextInt(a.length);
            assertEquals(sorted[k], DngPreview.select(a.clone(), k), 0f);
            assertEquals(sorted[(int) (a.length * 0.99)], DngPreview.select(a.clone(), (int) (a.length * 0.99)), 0f);
        }
    }
}
