package com.scamera.app.gallery.dng;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * P696: the tags the gallery developer of a DNG without its develop record reads (DngRaw.Info): Software, NoiseProfile, the
 * exposure of IFD 0 or of the Exif IFD, the as-shot white, the levels, and the OpcodeList2 (0xC741) GainMaps as a Camera2 lens
 * shading map. P706: the decode applies those maps (0xC741 is OpcodeList2; it applied 0xC740, OpcodeList1, only), the
 * developer's decode leaves them to the pipeline's map.
 */
public class DngTagsTest {
    static final int W = DngFixtures.W, H = DngFixtures.H;

    @Test public void softwareNoiseAndExposureOfIfd0() throws IOException {
        DngFixtures.Tags t = new DngFixtures.Tags();
        t.software = "SCAMERA 0.98 (31254) 5b8d8d04";
        t.noiseProfile = new double[]{2.2342095e-4, 3.785287e-7, 2.5e-4, 4e-7, 3e-4, 5e-7};
        t.iso = 50;
        t.exposureTime = .01;
        t.fNumber = 2.3;
        t.focalLength = 22.5;
        DngRaw.Info info = DngRaw.probe(ByteBuffer.wrap(DngFixtures.bayer(t)));
        assertEquals("SCAMERA 0.98 (31254) 5b8d8d04", info.software);
        assertArrayEquals(t.noiseProfile, info.noiseProfile, 0);
        assertEquals(50, info.iso, 0);
        assertEquals(.01, info.exposureTime, 1e-9);
        assertEquals(2.3, info.fNumber, 1e-9);
        assertEquals(22.5, info.focalLength, 1e-9);
        assertTrue(info.asShotGiven);
        assertEquals(1023, info.whiteLevel, 0);
        assertEquals(64, info.blackLevel, 0);
        assertNull("no OpcodeList2: no shading map", info.shadingMap);
    }

    @Test public void theExposureOfTheExifIfd() throws IOException {
        DngFixtures.Tags t = new DngFixtures.Tags();
        t.iso = 800;
        t.exposureTime = .125;
        t.fNumber = 1.75;
        t.focalLength = 6.5;
        t.exposureInExif = true;
        DngRaw.Info info = DngRaw.probe(ByteBuffer.wrap(DngFixtures.bayer(t)));
        assertEquals(800, info.iso, 0);
        assertEquals(.125, info.exposureTime, 1e-7);
        assertEquals(1.75, info.fNumber, 1e-7);
        assertEquals(6.5, info.focalLength, 1e-6);
        // the raw is read as before
        DngRaw.Image img = DngRaw.decode(ByteBuffer.wrap(DngFixtures.bayer(t)), 1);
        assertEquals(W, img.width);
        assertEquals(DngRaw.RGGB, img.phase);
    }

    @Test public void withoutTheTagsNothingIsMadeUp() throws IOException {
        DngRaw.Info info = DngRaw.probe(ByteBuffer.wrap(DngFixtures.smallBayer()));
        assertEquals("", info.software);
        assertNull(info.noiseProfile);
        assertEquals(0, info.iso, 0);
        assertEquals(0, info.exposureTime, 0);
        assertEquals(0, info.fNumber, 0);
        assertNull(info.shadingMap);
    }

    @Test public void theOpcodeList2GainMapsAreTheCamerasShadingMap() throws IOException {
        DngFixtures.Tags t = new DngFixtures.Tags();
        t.shadingMaps = true;
        byte[] dng = DngFixtures.bayer(t);
        DngRaw.Info info = DngRaw.probe(ByteBuffer.wrap(dng));
        assertEquals(3, info.shadingW);
        assertEquals(2, info.shadingH);
        // RGGB: the (0, 0) site's map is R, (1, 0) and (0, 1) the greens, (1, 1) B
        for (int i = 0; i < 6; i++)
            for (int c = 0; c < 4; c++)
                assertEquals("point " + i + " channel " + c, DngFixtures.Tags.shadingGain(c, i), info.shadingMap[i * 4 + c], 0);
        // P706: the picture's decode applies them (OpcodeList2 is 0xC741)
        assertTrue(info.gainMap);
        DngRaw.Image img = DngRaw.decode(ByteBuffer.wrap(dng), 1);
        DngRaw.Image plain = DngRaw.decode(ByteBuffer.wrap(DngFixtures.smallBayer()), 1);
        boolean shaded = false;
        for (int i = 0; i < img.width * img.height; i++) shaded |= plain.data.get(i) != img.data.get(i);
        assertTrue("the maps reach the decode", shaded);
        // the developer's decode does not (the pipeline applies the map above): from the tags and from a record alike
        for (boolean fromRecord : new boolean[]{false, true}) {
            DngRaw.Image dev = DngRaw.decodeForDevelop(ByteBuffer.wrap(dng), 1, fromRecord);
            DngRaw.Image devPlain = DngRaw.decodeLinear(ByteBuffer.wrap(DngFixtures.smallBayer()), 1);
            assertFalse(dev.info.gainMap);
            assertArrayEquals(devPlain.linear, dev.linear, 0f);
        }
    }
}
