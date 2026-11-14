package com.scamera.app.gallery.dng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** P706: the DNG facts of the viewer's details sheet, read from the tags only. */
public class DngFactsTest {
    @Test public void theRawsTagsInWords() throws IOException {
        DngFixtures.Tags t = new DngFixtures.Tags();
        t.shadingMaps = true;
        t.noiseProfile = new double[]{2e-4, 3e-7};
        t.colorMatrix1 = new double[]{0.9, -0.3, -0.1, -0.4, 1.3, 0.1, -0.05, 0.2, 0.6};
        DngFacts f = DngFacts.of(ByteBuffer.wrap(DngFixtures.bayer(t)));
        assertEquals("1.4.0.0", f.version);
        assertEquals("RGGB", f.cfa);
        assertEquals(16, f.bits);
        assertEquals(1, f.compression);
        assertEquals(64, f.black, 0);
        assertEquals(1023, f.white, 0);
        assertEquals(DngFixtures.W, f.width);
        assertEquals(DngFixtures.H, f.height);
        assertEquals(1, f.colorMatrices);
        assertEquals(0, f.forwardMatrices);
        assertTrue(f.noiseProfile);
        assertNull(f.opcodes1);
        assertEquals("GainMap ×4", f.opcodes2);
        assertNull(f.opcodes3);
    }

    @Test public void uniqueCameraModelAndAPlainFile() throws IOException {
        int[] raw = DngPreviewTest.scene(DngRawTest.N, 64, 1023);
        DngPreviewTest.Tiff t = DngRawTest.bayer(raw, 64, 1023);
        byte[] m = "vivo X200 Ultra\0".getBytes(StandardCharsets.US_ASCII);
        t.blob(0xC614, 2, m.length, m);
        DngFacts f = DngFacts.of(ByteBuffer.wrap(t.build()));
        assertEquals("vivo X200 Ultra", f.uniqueCameraModel);
        assertFalse(f.noiseProfile);
        assertEquals(0, f.colorMatrices);
        assertNull(f.opcodes2);
    }

    @Test public void patternsAndOpcodes() {
        assertEquals("BGGR", DngFacts.cfaName(new byte[]{2, 1, 1, 0}, 2, 2));
        byte[] quad = {0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 2, 2, 1, 1, 2, 2};
        assertEquals("Quad RGGB", DngFacts.cfaName(quad, 4, 4));
        assertNull(DngFacts.cfaName(null, 2, 2));
        assertEquals("GainMap", DngFacts.opcodes(DngRawTest.gainMapList(2f)));
        assertEquals("—", DngFacts.opcodes(new byte[]{0, 0, 0, 0}));
        assertEquals("#99", DngFacts.opcodeName(99));
        assertEquals("lossless JPEG", DngFacts.compressionName(7));
    }
}
