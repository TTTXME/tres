package com.scamera.app.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * P679: the Xiaomi 17 Ultra's LOFIC pair (XiaomiLofic) - the session recipe of the release's INTEGRATION.md (outputs in their
 * order, the vendor keys, the setups), the packed RAW14 / RAW10 buffers (classification by the used length, unpacking), the k
 * estimate, the pairing by timestamp and the checks that send a session to the next setup or to the normal one. Nothing ran on a
 * phone: the buffers are synthetic, packed by the specification's formulas.
 */
public class XiaomiLoficTest {

    // ---------------------------------------------------------------- packing (INTEGRATION.md section 3, inverted)

    /** Packs 4-sample groups of RAW14: 4 high bytes (value >> 6), then the 4 x 6 low bits little-endian in 3 bytes. */
    public static void pack14(ByteBuffer out, int at, int[] p) {
        int lsb = 0;
        for (int i = 0; i < 4; i++) {
            out.put(at + i, (byte) (p[i] >> 6));
            lsb |= (p[i] & 63) << (6 * i);
        }
        out.put(at + 4, (byte) lsb);
        out.put(at + 5, (byte) (lsb >> 8));
        out.put(at + 6, (byte) (lsb >> 16));
    }

    /** Packs 4-sample groups of RAW10: 4 high bytes (value >> 2), then the 4 x 2 low bits in one byte. */
    public static void pack10(ByteBuffer out, int at, int[] p) {
        int low = 0;
        for (int i = 0; i < 4; i++) {
            out.put(at + i, (byte) (p[i] >> 2));
            low |= (p[i] & 3) << (2 * i);
        }
        out.put(at + 4, (byte) low);
    }

    /** A synthetic pair: Long levels {@code T} (DN over black) of a smooth scene with highlights, Short = T / k, with noise. */
    static ByteBuffer[] pair(int rows, double k, long seed, boolean noise) {
        final ByteBuffer l = ByteBuffer.allocateDirect(XiaomiLofic.RAW_W * XiaomiLofic.RAW_H * 2);
        final ByteBuffer s = ByteBuffer.allocateDirect(XiaomiLofic.RAW_W * XiaomiLofic.RAW_H * 2);
        final Random r = new Random(seed);
        final int[] lv = new int[4], sv = new int[4];
        for (int y = 0; y < rows; y++)
            for (int g = 0; g < XiaomiLofic.RAW_W / 4; g++) {
                for (int i = 0; i < 4; i++) {
                    final int x = 4 * g + i;
                    final double t = 30.0 * 15359 * Math.pow((x + 1.0) / XiaomiLofic.RAW_W, 3) * (0.5 + 0.5 * y / (double) rows);
                    final double ln = noise ? r.nextGaussian() * Math.sqrt(2.46 * t + 210) : 0;
                    final double sn = noise ? r.nextGaussian() * Math.sqrt(12.55 * 2.46 * (t / k) / k + 0.5625) : 0;
                    lv[i] = (int) Math.max(0, Math.min(16383, Math.round(1024 + t + ln)));
                    sv[i] = (int) Math.max(0, Math.min(1023, Math.round(64 + t / k + sn)));
                }
                pack14(l, y * XiaomiLofic.LONG_ROW + g * 7, lv);
                pack10(s, y * XiaomiLofic.SHORT_ROW + g * 5, sv);
            }
        return new ByteBuffer[]{l, s};
    }

    // ---------------------------------------------------------------- the recipe

    @Test
    public void outputsInTheRecipesOrder() {
        for (XiaomiLofic.Setup s : XiaomiLofic.Setup.values()) {
            final List<XiaomiLofic.Output> o = XiaomiLofic.outputs(s);
            assertEquals(6, o.size());
            assertEquals(Arrays.asList(XiaomiLofic.DUMMY, XiaomiLofic.VIEWFINDER, XiaomiLofic.VIDEO, XiaomiLofic.JPEG,
                    XiaomiLofic.RAW_A, XiaomiLofic.RAW_B), Arrays.asList(o.get(0).role, o.get(1).role, o.get(2).role, o.get(3).role,
                    o.get(4).role, o.get(5).role));
            // every RAW 4096 x 3072; the dummy RAW and the JPEG configured only
            for (int i : new int[]{0, 4, 5}) {
                assertEquals(XiaomiLofic.RAW_SENSOR, o.get(i).format);
                assertEquals(4096, o.get(i).width);
                assertEquals(3072, o.get(i).height);
            }
            assertFalse(o.get(0).repeating);
            assertFalse(o.get(3).repeating);
            assertTrue(o.get(1).repeating && o.get(2).repeating && o.get(4).repeating && o.get(5).repeating);
            assertEquals(XiaomiLofic.JPEG_FORMAT, o.get(3).format);
            assertEquals(XiaomiLofic.PRIVATE, o.get(1).format);
            assertEquals(XiaomiLofic.PRIVATE, o.get(2).format);
            assertEquals(1L << 16, o.get(2).usage); // HardwareBuffer.USAGE_VIDEO_ENCODE
        }
        final List<XiaomiLofic.Output> sixteen = XiaomiLofic.outputs(XiaomiLofic.Setup.SIXTEEN_TEN);
        assertEquals(1920, sixteen.get(1).width);
        assertEquals(1080, sixteen.get(1).height);
        assertEquals(3840, sixteen.get(2).width);
        assertEquals(2160, sixteen.get(3).height);
        final List<XiaomiLofic.Output> four = XiaomiLofic.outputs(XiaomiLofic.Setup.FOUR_THREE);
        assertEquals(1440, four.get(1).width);
        assertEquals(2880, four.get(2).width);
        assertEquals(4096, four.get(3).width);
        assertEquals(3072, four.get(3).height);
    }

    @Test
    public void setups() {
        final XiaomiLofic.Setup s = XiaomiLofic.Setup.SIXTEEN_TEN, f = XiaomiLofic.Setup.FOUR_THREE;
        assertEquals(0x8004, s.sessionType);
        assertEquals(60, s.fps);
        assertEquals(2560, s.rows);
        assertArrayEquals(new int[]{27}, s.modes);
        assertEquals(-1, s.askRow);
        assertEquals(18_350_080, s.longBytes());
        assertEquals(13_107_200, s.shortBytes());
        assertEquals(0xF010, f.sessionType);
        assertEquals(30, f.fps);
        assertEquals(3072, f.rows);
        assertEquals(5, f.askRow); // the sensor's DXGLofic 4:3 row, asked by current_mode (the root-free candidate)
        assertEquals(22_020_096, f.longBytes());
        assertEquals(15_728_640, f.shortBytes());
    }

    @Test
    public void vendorKeys() {
        final List<XiaomiLofic.Key> session = XiaomiLofic.vendorKeys(XiaomiLofic.Setup.SIXTEEN_TEN, true);
        assertEquals(2, session.size());
        assertEquals("xiaomi.app.module", session.get(0).name);
        assertArrayEquals(new int[]{180}, (int[]) session.get(0).value);
        assertEquals("com.xiaomi.sessionparams.enableLofic", session.get(1).name);
        assertArrayEquals(new int[]{1}, (int[]) session.get(1).value);
        final List<XiaomiLofic.Key> request = XiaomiLofic.vendorKeys(XiaomiLofic.Setup.SIXTEEN_TEN, false);
        assertEquals(3, request.size());
        assertEquals("com.xiaomi.camera.eiParams.loficEnable", request.get(2).name);
        assertArrayEquals(new byte[]{1}, (byte[]) request.get(2).value);
        // 4:3: the row asked on the session and every request
        final List<XiaomiLofic.Key> four = XiaomiLofic.vendorKeys(XiaomiLofic.Setup.FOUR_THREE, false);
        assertEquals("org.codeaurora.qcamera3.sensor_meta_data.current_mode", four.get(3).name);
        assertArrayEquals(new int[]{5}, (int[]) four.get(3).value);
        assertEquals(3, XiaomiLofic.vendorKeys(XiaomiLofic.Setup.FOUR_THREE, true).size());
    }

    @Test
    public void wantedOnTheMainCameraOnly() {
        assertTrue(XiaomiLofic.wanted(true, "2", true, -1, true, 34));
        assertFalse(XiaomiLofic.wanted(false, "2", true, -1, true, 34)); // another phone
        assertFalse(XiaomiLofic.wanted(true, "0", true, -1, true, 34)); // the logical camera
        assertFalse(XiaomiLofic.wanted(true, "4", true, -1, true, 34)); // the 75 mm
        assertFalse(XiaomiLofic.wanted(true, "2", false, -1, true, 34)); // the switch on DCG / off
        assertTrue(XiaomiLofic.wanted(true, "2", false, 1, true, 34)); // scam_dev on
        assertFalse(XiaomiLofic.wanted(true, "2", true, 0, true, 34)); // scam_dev off
        assertFalse(XiaomiLofic.wanted(true, "2", true, -1, false, 34)); // not a plain photo session
        assertFalse(XiaomiLofic.wanted(true, "2", true, -1, true, 28)); // no video-encode reader before Android 10
    }

    // ---------------------------------------------------------------- buffers

    @Test
    public void classifiesByTheUsedLength() {
        assertEquals(XiaomiLofic.Stream.LONG, XiaomiLofic.classify(2560 * 7168));
        assertEquals(2560, XiaomiLofic.rows(2560 * 7168, XiaomiLofic.Stream.LONG));
        assertEquals(XiaomiLofic.Stream.SHORT, XiaomiLofic.classify(2560 * 5120));
        assertEquals(XiaomiLofic.Stream.LONG, XiaomiLofic.classify(3072 * 7168));
        assertEquals(XiaomiLofic.Stream.SHORT, XiaomiLofic.classify(3072 * 5120));
        assertEquals(3072, XiaomiLofic.rows(3072 * 5120, XiaomiLofic.Stream.SHORT));
        // the last byte of a row may be 0: a few bytes of slack
        assertEquals(XiaomiLofic.Stream.LONG, XiaomiLofic.classify(2560 * 7168 - 3));
        // a 2560-row Long is as long as a 3584-row Short: the 3072-row limit says Long
        assertEquals(2560 * 7168, 3584 * 5120);
        assertEquals(XiaomiLofic.Stream.LONG, XiaomiLofic.classify(3584 * 5120));
        // a cropped row (2304 rows) is still classified, so the check sees its rows
        assertEquals(XiaomiLofic.Stream.LONG, XiaomiLofic.classify(2304 * 7168));
        assertEquals(XiaomiLofic.Stream.NONE, XiaomiLofic.classify(0));
        assertEquals(XiaomiLofic.Stream.NONE, XiaomiLofic.classify(1_234_567));
    }

    @Test
    public void unpacksAndFindsTheUsedLength() {
        final ByteBuffer[] p = pair(16, 40, 1, true);
        final ByteBuffer l = p[0], s = p[1];
        assertEquals(16 * 7168, XiaomiLofic.usedBytes(l), 8);
        assertEquals(16 * 5120, XiaomiLofic.usedBytes(s), 8);
        assertEquals(XiaomiLofic.Stream.LONG, XiaomiLofic.classify(XiaomiLofic.usedBytes(l)));
        assertEquals(XiaomiLofic.Stream.SHORT, XiaomiLofic.classify(XiaomiLofic.usedBytes(s)));
        // the formulas round-trip
        final ByteBuffer b = ByteBuffer.allocate(14);
        pack14(b, 0, new int[]{1024, 16383, 5555, 63});
        pack14(b, 7, new int[]{0, 1, 8191, 12345});
        final int[] want = {1024, 16383, 5555, 63, 0, 1, 8191, 12345};
        for (int x = 0; x < 8; x++) assertEquals(want[x], XiaomiLofic.long14(b, x, 0));
        final ByteBuffer c = ByteBuffer.allocate(5);
        pack10(c, 0, new int[]{64, 1023, 513, 2});
        assertArrayEquals(new int[]{64, 1023, 513, 2}, new int[]{XiaomiLofic.short10(c, 0, 0), XiaomiLofic.short10(c, 1, 0),
                XiaomiLofic.short10(c, 2, 0), XiaomiLofic.short10(c, 3, 0)});
    }

    @Test
    public void quickTestTellsTheStreams() {
        final ByteBuffer[] p = pair(64, 40, 2, true);
        assertEquals(XiaomiLofic.Stream.LONG, XiaomiLofic.quick(p[0], 64));
        assertEquals(XiaomiLofic.Stream.SHORT, XiaomiLofic.quick(p[1], 64));
        assertEquals(XiaomiLofic.Stream.NONE, XiaomiLofic.quick(ByteBuffer.allocateDirect(XiaomiLofic.RAW_W * XiaomiLofic.RAW_H * 2), 64));
    }

    // ---------------------------------------------------------------- k

    @Test
    public void measuresKPerPair() {
        for (double k : new double[]{20, 60, 212, 833}) {
            final ByteBuffer[] p = pair(256, k, 3, true);
            final XiaomiLofic.Ratio r = XiaomiLofic.ratio(p[0], p[1], 256, 2);
            assertTrue(r.toString(), r.measured);
            assertEquals("k " + k + ": " + r, k, r.k, k * 0.02);
        }
        // a dark scene: too few pixels in the band - not measured (the model 212 x gain is a root-module value)
        final ByteBuffer dark = ByteBuffer.allocateDirect(XiaomiLofic.RAW_W * XiaomiLofic.RAW_H * 2);
        final ByteBuffer darkS = ByteBuffer.allocateDirect(XiaomiLofic.RAW_W * XiaomiLofic.RAW_H * 2);
        for (int y = 0; y < 64; y++)
            for (int g = 0; g < XiaomiLofic.RAW_W / 4; g++) {
                pack14(dark, y * XiaomiLofic.LONG_ROW + g * 7, new int[]{1100, 1100, 1100, 1100});
                pack10(darkS, y * XiaomiLofic.SHORT_ROW + g * 5, new int[]{64, 64, 64, 64});
            }
        assertFalse(XiaomiLofic.ratio(dark, darkS, 64, 1).measured);
        assertEquals(5.7, XiaomiLofic.headroomStops(833), 0.05); // the release's ISO 200 measure
        assertEquals(0, XiaomiLofic.headroomStops(10), 0);
    }

    // ---------------------------------------------------------------- pairing

    @Test
    public void pairsByTimestamp() {
        final XiaomiLofic.Ring ring = new XiaomiLofic.Ring(3);
        final ByteBuffer l = ByteBuffer.allocateDirect(4 * 7168), s = ByteBuffer.allocateDirect(4 * 5120);
        l.put(0, (byte) 7);
        assertNull(ring.store(XiaomiLofic.Stream.LONG, 1000, l, 4 * 7168));
        // the Short image of another exposure is no partner
        assertNull(ring.store(XiaomiLofic.Stream.SHORT, 1000 + 16_000_000L, s, 4 * 5120));
        final XiaomiLofic.Pair p = ring.store(XiaomiLofic.Stream.SHORT, 1000 + 100, s, 4 * 5120); // same exposure (within 0.5 ms)
        assertNotNull(p);
        assertTrue(p.complete());
        assertEquals(4, p.longImage.rows);
        assertEquals(4, p.shortImage.rows);
        assertEquals(7, p.longImage.data.get(0)); // a copy of the used bytes
        assertFalse(p.usable()); // no result yet
        p.exposureCount = 1;
        p.result = org.mockito.Mockito.mock(android.hardware.camera2.TotalCaptureResult.class);
        assertFalse(p.usable()); // a single exposure: the Short read is not usable
        p.exposureCount = 2;
        assertTrue(p.usable());
        // NONE never enters; the ring keeps its newest pairs
        assertNull(ring.store(XiaomiLofic.Stream.NONE, 5000, l, 0));
        for (int i = 0; i < 4; i++) ring.store(XiaomiLofic.Stream.LONG, 100_000_000L * (i + 2), l, 4 * 7168);
        assertEquals(3, ring.size());
        assertEquals(0, ring.usableCount()); // the old pair left the ring
    }

    @Test
    public void shotTakesTheNewestUsablePairsBeforeTheCutoff() {
        final XiaomiLofic.Ring ring = new XiaomiLofic.Ring(8);
        final ByteBuffer l = ByteBuffer.allocateDirect(4 * 7168), s = ByteBuffer.allocateDirect(4 * 5120);
        final android.hardware.camera2.TotalCaptureResult r = org.mockito.Mockito.mock(android.hardware.camera2.TotalCaptureResult.class);
        for (int i = 1; i <= 6; i++) {
            final long ts = 16_625_000L * i;
            ring.store(XiaomiLofic.Stream.LONG, ts, l, 4 * 7168);
            final XiaomiLofic.Pair p = ring.store(XiaomiLofic.Stream.SHORT, ts, s, 4 * 5120);
            p.result = r;
            p.exposureCount = i == 4 ? 1 : 2; // pair 4 a single exposure
        }
        assertEquals(5, ring.usableCount());
        final List<XiaomiLofic.Pair> taken = ring.take(3, 16_625_000L * 5); // pair 6 is after the press
        assertEquals(3, taken.size());
        assertEquals(16_625_000L * 5, taken.get(0).timestamp()); // newest first
        assertEquals(16_625_000L * 3, taken.get(1).timestamp()); // pair 4 skipped
        assertEquals(16_625_000L * 2, taken.get(2).timestamp());
        assertEquals(0, ring.size()); // the ring is emptied for the next press
    }

    // ---------------------------------------------------------------- the checks and the fallback

    @Test
    public void sixteenTenProvenByItsModeAndRows() {
        final XiaomiLofic.Proof p = new XiaomiLofic.Proof(XiaomiLofic.Setup.SIXTEEN_TEN, 90);
        p.result(27, 1);
        assertEquals(XiaomiLofic.Verdict.PENDING, p.verdict());
        p.pair(2560, 2560);
        assertEquals(XiaomiLofic.Verdict.PROVEN, p.verdict()); // ExposureCount 2 is a per-shot filter in 16:10
    }

    @Test
    public void anotherModeFailsTheSetup() {
        final XiaomiLofic.Proof p = new XiaomiLofic.Proof(XiaomiLofic.Setup.SIXTEEN_TEN, 90);
        for (int i = 0; i < 5; i++) p.result(23, 1);
        assertEquals(XiaomiLofic.Verdict.FAILED, p.verdict());
        assertTrue(p.failure, p.failure.contains("sensor mode 23"));
    }

    @Test
    public void croppedRowsFailFourThree() {
        final XiaomiLofic.Proof p = new XiaomiLofic.Proof(XiaomiLofic.Setup.FOUR_THREE, 90);
        p.result(5, 2); // the asked row echoes back: no proof
        assertEquals(XiaomiLofic.Verdict.PENDING, p.verdict());
        p.pair(2304, 2304);
        assertEquals(XiaomiLofic.Verdict.FAILED, p.verdict());
        assertTrue(p.failure, p.failure.contains("2304"));
    }

    @Test
    public void fourThreeNeedsExposureCountTwo() {
        final XiaomiLofic.Proof p = new XiaomiLofic.Proof(XiaomiLofic.Setup.FOUR_THREE, 20);
        p.pair(3072, 3072);
        for (int i = 0; i < 19; i++) {
            p.result(5, 1);
            assertEquals(XiaomiLofic.Verdict.PENDING, p.verdict());
        }
        p.result(5, 1);
        assertEquals(XiaomiLofic.Verdict.FAILED, p.verdict());
        assertTrue(p.failure, p.failure.contains("ExposureCount"));
        final XiaomiLofic.Proof q = new XiaomiLofic.Proof(XiaomiLofic.Setup.FOUR_THREE, 20);
        q.result(5, 2);
        q.pair(3072, 3072);
        assertEquals(XiaomiLofic.Verdict.PROVEN, q.verdict());
    }

    @Test
    public void noPairFailsAtTheWindowsEnd() {
        final XiaomiLofic.Proof p = new XiaomiLofic.Proof(XiaomiLofic.Setup.SIXTEEN_TEN, 10);
        for (int i = 0; i < 9; i++) p.result(27, 2);
        assertEquals(XiaomiLofic.Verdict.PENDING, p.verdict());
        p.result(27, 2);
        assertEquals(XiaomiLofic.Verdict.FAILED, p.verdict());
        assertTrue(p.failure, p.failure.contains("no Long + Short pair"));
        // a HAL that reports no mode: the rows prove the setup
        final XiaomiLofic.Proof q = new XiaomiLofic.Proof(XiaomiLofic.Setup.SIXTEEN_TEN, 90);
        q.pair(2560, 2560);
        for (int i = 0; i < 9; i++) q.result(null, null);
        assertEquals(XiaomiLofic.Verdict.PENDING, q.verdict());
        q.result(null, null);
        assertEquals(XiaomiLofic.Verdict.PROVEN, q.verdict());
    }

    @Test
    public void ladderFallsBackToSixteenTenThenTheNormalSession() {
        assertEquals(Arrays.asList(XiaomiLofic.Setup.FOUR_THREE, XiaomiLofic.Setup.SIXTEEN_TEN), XiaomiLofic.ladder(false, false, false));
        assertEquals(Arrays.asList(XiaomiLofic.Setup.SIXTEEN_TEN), XiaomiLofic.ladder(true, false, false));
        assertEquals(Arrays.asList(XiaomiLofic.Setup.SIXTEEN_TEN), XiaomiLofic.ladder(false, false, true));
        assertTrue(XiaomiLofic.ladder(true, true, false).isEmpty()); // the normal session
        // a 4:3 failure is this firmware's only
        assertEquals("cropped", XiaomiLofic.storedFailure("FP1|cropped", "FP1"));
        assertNull(XiaomiLofic.storedFailure("FP1|cropped", "FP2"));
        assertNull(XiaomiLofic.storedFailure(null, "FP1"));
    }
}
