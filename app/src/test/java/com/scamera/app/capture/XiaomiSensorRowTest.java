package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** P631: the Xiaomi 17 Ultra's full-size module row in a RAW-only session, its colour block and its first frame's description. */
public class XiaomiSensorRowTest {
    /** The main camera's SensorModeTable of the owner's log (build 31256): 43 rows, stride 3 (width, height, fps). */
    static final int[] MAIN = table(new int[][]{
            {8192, 6144, 30}, {4096, 3072, 30}, {4096, 3072, 30}, {4096, 3072, 30}, {4096, 3072, 30}, {4096, 3072, 30},
            {4096, 3072, 30}, {4096, 3072, 30}, {4096, 3072, 30}, {4096, 3072, 30}, {4096, 3072, 30}, {8192, 6144, 30},
            {4096, 2304, 30}, {4096, 2304, 30}, {4096, 2304, 30}, {4096, 2304, 30}, {4096, 2304, 30}, {4096, 2304, 30},
            {4096, 2304, 30}, {4096, 2304, 30}, {4096, 2304, 30}, {4096, 2304, 30}, {8192, 4608, 30}, {4096, 3072, 60},
            {4096, 2560, 60}, {4096, 2560, 60}, {4096, 2560, 60}, {4096, 2560, 60}, {4096, 2560, 60}, {4096, 2560, 60},
            {3200, 2400, 30}, {7680, 4320, 30}, {4096, 2304, 120}, {1920, 1080, 480}, {1280, 720, 480}, {2048, 1536, 30},
            {2048, 1152, 30}, {5888, 4416, 30}, {2048, 1536, 30}, {3072, 2304, 30}, {4096, 3072, 30}, {4096, 2304, 60},
            {5888, 4416, 30}});

    static int[] table(int[][] rows) {
        final int[] t = new int[2 + rows.length * 3];
        t[0] = rows.length;
        t[1] = 3;
        for (int i = 0; i < rows.length; i++) System.arraycopy(rows[i], 0, t, 2 + 3 * i, 3);
        return t;
    }

    @Test public void theLoggedTablesFullSizeRowsAreTheSensorsQuad() {
        assertEquals("row 11, the owner's 50 MP row", 2, SensorModePick.declaredBlock(MAIN, 11));
        assertEquals("row 0, the other 8192x6144", 2, SensorModePick.declaredBlock(MAIN, 0));
        assertEquals("a binned row", 0, SensorModePick.declaredBlock(MAIN, 1));
        assertEquals("8192x4608 is no full-size row", 0, SensorModePick.declaredBlock(MAIN, 22));
        assertEquals("5888x4416", 0, SensorModePick.declaredBlock(MAIN, 37));
        final SensorModePick.Pick p = SensorModePick.of(11, MAIN);
        assertNotNull(p);
        assertEquals(8192, p.width);
        assertEquals(6144, p.height);
        assertFalse("a plain pick keeps «Поток ISP»", p.ispOff);
    }

    @Test public void xiaomiMadePhones() {
        assertTrue(XiaomiSensorRow.xiaomi("Xiaomi", "Xiaomi"));
        assertTrue(XiaomiSensorRow.xiaomi("Xiaomi", "Redmi"));
        assertTrue(XiaomiSensorRow.xiaomi("Xiaomi", "POCO"));
        assertFalse(XiaomiSensorRow.xiaomi("vivo", "vivo"));
        assertFalse(XiaomiSensorRow.xiaomi("OnePlus", "OnePlus"));
        assertFalse(XiaomiSensorRow.xiaomi("nubia", "nubia"));
        assertFalse(XiaomiSensorRow.xiaomi(null, null));
    }

    @Test public void onlyThe17UltrasFullRowGoesRawOnlyUpFront() {
        assertTrue("17 Ultra, full-size row", XiaomiSensorRow.ispOff(true, true, true, false));
        assertFalse("17 Ultra, another row that never stalled", XiaomiSensorRow.ispOff(true, true, false, false));
        assertFalse("another Xiaomi's full row that never stalled", XiaomiSensorRow.ispOff(true, false, true, false));
        assertTrue("any Xiaomi row whose session with the ISP stream stalled", XiaomiSensorRow.ispOff(true, false, false, true));
        assertFalse("not a Xiaomi phone", XiaomiSensorRow.ispOff(false, true, true, true));
    }

    @Test public void aRawOnlyPickLeavesTheIspStreamOutWhateverTheSetting() {
        final SensorModePick.Pick p = SensorModePick.of(11, MAIN).withoutIsp();
        assertTrue(p.ispOff);
        assertEquals(11, p.mode);
        assertEquals(8192, p.width);
        assertTrue(p.toString().contains("RAW only"));
        assertSame("marked once", p, p.withoutIsp());
        for (int ispSurface = 0; ispSurface <= 3; ispSurface++)
            assertTrue("«Поток ISP» " + ispSurface, SensorModePick.ispStreamOff(ispSurface, p, false, false));
        // the log of 31256: On (1) and fake off (3) kept the ISP stream beside the 50 MP RAW - the HAL stopped / failed every request
        final SensorModePick.Pick plain = SensorModePick.of(11, MAIN);
        assertFalse("On, unmarked: as before", SensorModePick.ispStreamOff(1, plain, true, false));
        assertFalse("fake off, unmarked: as before (the stream stays in the session)", SensorModePick.ispStreamOff(3, plain, true, false));
        assertTrue("Off, unmarked: as before", SensorModePick.ispStreamOff(2, plain, true, false));
        assertTrue("Auto with a forced mode, unmarked: as before", SensorModePick.ispStreamOff(0, plain, true, false));
    }

    @Test public void otherPhonesPicksAreNotMarked() {
        // unit tests run without a Xiaomi Build: the mark leaves every pick as it is (vivo / OPPO / nubia / Pixel unaffected)
        final SensorModePick.Pick p = SensorModePick.of(11, MAIN);
        assertSame(p, XiaomiSensorRow.mark("2", p, MAIN));
        assertEquals(null, XiaomiSensorRow.mark("2", null, MAIN));
        assertEquals(null, XiaomiSensorRow.ispOffReason("2", p));
    }

    @Test public void aStalledRowIsRememberedOnce() {
        assertFalse(XiaomiSensorRow.ispRefused("9", 31));
        assertTrue(XiaomiSensorRow.noteIspRefused("9", 31, "test"));
        assertTrue(XiaomiSensorRow.ispRefused("9", 31));
        assertFalse("said once", XiaomiSensorRow.noteIspRefused("9", 31, "test"));
        assertFalse("another row", XiaomiSensorRow.ispRefused("9", 37));
        assertFalse("another camera", XiaomiSensorRow.ispRefused("8", 31));
        final SensorModePick.Pick p = SensorModePick.of(31, MAIN).withoutIsp();
        assertTrue(XiaomiSensorRow.ispOffReason("9", p).contains("stalled"));
        assertTrue(XiaomiSensorRow.ispOffReason("2", SensorModePick.of(11, MAIN).withoutIsp()).contains("full-size row 11"));
    }

    @Test public void theDetectorMeasuresOverTheFloorOfAStreamWithoutPedestal() {
        assertEquals("no pedestal: the floor", 0f, XiaomiSensorRow.detectorBlack(64f, 0f), 0f);
        assertEquals(3f, XiaomiSensorRow.detectorBlack(64f, 3f), 0f);
        assertEquals("the pedestal is there", 64f, XiaomiSensorRow.detectorBlack(64f, 58f), 0f);
        assertEquals("no floor", 64f, XiaomiSensorRow.detectorBlack(64f, -1f), 0f);
    }

    @Test public void aProvedBayerBeatsTheDeclaredQuadOnlyOnAXiaomiPick() {
        assertEquals("the HAL's remosaic: the stored 1 merges", 0, XiaomiSensorRow.shotBlock(2, true, 1));
        assertEquals("a proved Quad", 2, XiaomiSensorRow.shotBlock(2, true, 2));
        assertEquals("nothing proved: the declared Quad as before", 2, XiaomiSensorRow.shotBlock(2, true, 0));
        assertEquals("not a Xiaomi pick: as before", 2, XiaomiSensorRow.shotBlock(2, false, 1));
        assertEquals(4, XiaomiSensorRow.shotBlock(4, false, 1));
    }

    @Test public void theFirstFrameIsDescribedOncePerSession() {
        // armFrameLog needs a Xiaomi Build; without one nothing is armed
        XiaomiSensorRow.armFrameLog("2", SensorModePick.of(11, MAIN));
        assertFalse(XiaomiSensorRow.takeFrameLog("2", 11));
    }

    /** P695: the first frame and then one every 3 s, five lines a session; the first-frame API as before. */
    @Test public void aSessionsFrameLinesComeEveryThreeSeconds() {
        try {
            XiaomiSensorRow.armFrameLog("5", SensorModePick.of(6, XiaomiPeriscopeIszTest.PERISCOPE), true);
            assertEquals("another camera", -1, XiaomiSensorRow.takeFrameLog("4", 6, 1000));
            assertEquals("another row", -1, XiaomiSensorRow.takeFrameLog("5", 2, 1000));
            assertEquals(0, XiaomiSensorRow.takeFrameLog("5", 6, 1000));
            assertEquals(-1, XiaomiSensorRow.takeFrameLog("5", 6, 1100));
            assertEquals(1, XiaomiSensorRow.takeFrameLog("5", 6, 4000));
            assertEquals(-1, XiaomiSensorRow.takeFrameLog("5", 6, 6999));
            assertEquals(2, XiaomiSensorRow.takeFrameLog("5", 6, 7000));
            assertEquals(3, XiaomiSensorRow.takeFrameLog("5", 6, 10000));
            assertEquals(4, XiaomiSensorRow.takeFrameLog("5", 6, 13000));
            assertEquals("five lines a session", -1, XiaomiSensorRow.takeFrameLog("5", 6, 60000));
            XiaomiSensorRow.armFrameLog("5", SensorModePick.of(6, XiaomiPeriscopeIszTest.PERISCOPE), true);
            assertTrue("the first-frame call as before", XiaomiSensorRow.takeFrameLog("5", 6));
            assertFalse(XiaomiSensorRow.takeFrameLog("5", 6));
            assertEquals(-1, XiaomiSensorRow.takeFrameLog("5", 6, 99000));
        } finally {
            XiaomiSensorRow.armFrameLog(null, null, false);
        }
    }

    /** A frame whose every row is one line of the scene (rows repeat), or, {@code columns}, whose every column is one. */
    static ByteBuffer repeated(boolean columns, long seed) {
        final Random r = new Random(seed);
        final int[] line = new int[Math.max(W, H)];
        for (int i = 0; i < line.length; i++) line[i] = 100 + r.nextInt(600);
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) b.putShort((y * W + x) * 2, (short) line[columns ? y : x]);
        return b;
    }

    /** P695: the frame line tells a picture from a frame whose rows (or columns) repeat - the stripes of the owner's screenshot. */
    @Test public void theRepeatStatisticsTellStripesFromAPicture() {
        assertEquals("a picture", XiaomiSensorRow.repeatText(XiaomiSensorRow.repeatStats(frame(false, 64, 3, 11), W, H, W * 2, 64f)));
        assertEquals("a picture", XiaomiSensorRow.repeatText(XiaomiSensorRow.repeatStats(frame(true, 64, 3, 12), W, H, W * 2, 64f)));
        final double[] rows = XiaomiSensorRow.repeatStats(repeated(false, 13), W, H, W * 2, 64f);
        assertEquals(0.0, rows[0], 0.0);
        assertTrue(rows[1] > 50);
        assertEquals("ROWS REPEAT (stripes)", XiaomiSensorRow.repeatText(rows));
        assertEquals("COLUMNS REPEAT (stripes)", XiaomiSensorRow.repeatText(XiaomiSensorRow.repeatStats(repeated(true, 14), W, H, W * 2, 64f)));
        final ByteBuffer black = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("too dark to tell", XiaomiSensorRow.repeatText(XiaomiSensorRow.repeatStats(black, W, H, W * 2, 0f)));
        assertEquals("too dark to tell", XiaomiSensorRow.repeatText(XiaomiSensorRow.repeatStats(null, W, H, W * 2, 0f)));
    }

    @Test public void aLaterFrameLineHasItsExposure() {
        final String t = XiaomiSensorRow.laterText(1, 37, 13_027_175L, 100, repeated(false, 15), W, H, W * 2);
        assertTrue(t, t.contains("frame line 2/5"));
        assertTrue(t, t.contains("format 37 unpacked"));
        assertTrue(t, t.contains("exposure 13.027 ms ISO 100"));
        assertTrue(t, t.contains("ROWS REPEAT"));
        assertTrue(t, t.contains("P695"));
        final String plain = XiaomiSensorRow.laterText(0, null, null, null, frame(false, 64, 3, 16), W, H, W * 2);
        assertFalse(plain, plain.contains("unpacked"));
        assertTrue(plain, plain.contains("exposure ? ISO ?"));
        assertTrue(plain, plain.contains("a picture"));
    }

    // ---------------------------------------------------------------- frames

    static final int W = 512, H = 384;

    /** A frame: {@code quad} 2x2 cells of one colour, else a plain Bayer; {@code pedestal} added; seeded noise of {@code sigma}. */
    static ByteBuffer frame(boolean quad, int pedestal, double sigma, long seed) {
        return frame(quad, pedestal, sigma, seed, false);
    }

    /** {@code darkLeft}: the left half black (no light, the pedestal and the noise only), as a dim room's shade. */
    static ByteBuffer frame(boolean quad, int pedestal, double sigma, long seed, boolean darkLeft) {
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(seed);
        final int[] colour = {90, 200, 200, 130}; // R, G, G, B (raw, before white balance)
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            final int cx = quad ? x / 2 : x, cy = quad ? y / 2 : y;
            final int c = darkLeft && x < W / 2 ? 0 : colour[(cy & 1) * 2 + (cx & 1)];
            b.putShort((y * W + x) * 2, (short) Math.max(0, Math.round(pedestal + c + sigma * r.nextGaussian())));
        }
        return b;
    }

    /** The binned Bayer frame written twice over: every site of a 2x2 cell the same value. */
    static ByteBuffer duplicated(long seed) {
        final ByteBuffer src = frame(false, 0, 3, seed);
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
            b.putShort((y * W + x) * 2, src.getShort(((y / 2) * W + x / 2) * 2));
        return b;
    }

    @Test public void theFloorTellsAStreamWithoutPedestal() {
        assertTrue("with its pedestal", XiaomiSensorRow.frameFloor(frame(true, 64, 3, 1), W, H, W * 2) > 100f);
        final ByteBuffer dark = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(5);
        for (int i = 0; i < W * H; i++) dark.putShort(i * 2, (short) (64 + Math.round(2 * r.nextGaussian())));
        final float pedestal = XiaomiSensorRow.frameFloor(dark, W, H, W * 2);
        assertTrue("a black frame with its pedestal: " + pedestal, pedestal > 56f && pedestal < 64f);
        assertEquals(64f, XiaomiSensorRow.detectorBlack(64f, pedestal), 0f);
        final ByteBuffer lost = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < W * H; i++) lost.putShort(i * 2, (short) Math.max(0, Math.round(2 * r.nextGaussian())));
        assertEquals("without it", 0f, XiaomiSensorRow.frameFloor(lost, W, H, W * 2), 0f);
        assertEquals(-1f, XiaomiSensorRow.frameFloor(null, W, H, W * 2), 0f);
    }

    @Test public void theCellStatisticsTellQuadBayerAndADuplicatedBinnedFrame() {
        assertEquals(XiaomiSensorRow.Grain.QUAD, XiaomiSensorRow.grain(XiaomiSensorRow.cellStats(frame(true, 0, 3, 2), W, H, W * 2, 0f)));
        assertEquals(XiaomiSensorRow.Grain.BAYER, XiaomiSensorRow.grain(XiaomiSensorRow.cellStats(frame(false, 0, 3, 3), W, H, W * 2, 0f)));
        assertEquals(XiaomiSensorRow.Grain.DUPLICATED, XiaomiSensorRow.grain(XiaomiSensorRow.cellStats(duplicated(4), W, H, W * 2, 0f)));
        assertEquals("nothing to read", XiaomiSensorRow.Grain.UNKNOWN, XiaomiSensorRow.grain(XiaomiSensorRow.cellStats(null, W, H, W * 2, 0f)));
        assertEquals("too dark", XiaomiSensorRow.Grain.UNKNOWN,
                XiaomiSensorRow.grain(XiaomiSensorRow.cellStats(frame(true, 0, 2, 5, true), 64, 64, W * 2, 0f)));
    }

    @Test public void theFrameLineSaysWhatTheLogNeeds() {
        final SensorModePick.Pick p = SensorModePick.of(11, MAIN).withoutIsp();
        final String line = XiaomiSensorRow.frameLine("2", p, 11, frame(true, 0, 3, 6, true), W, H, W * 2, 64f);
        assertTrue(line, line.contains("the result reports mode 11"));
        assertTrue(line, line.contains("no pedestal"));
        assertTrue(line, line.contains("a Quad mosaic"));
        assertTrue(line, line.contains("ISP stream out of the session"));
        assertTrue(line, line.contains("P631"));
        final String other = XiaomiSensorRow.frameLine("2", SensorModePick.of(11, MAIN), 1, frame(false, 64, 3, 7), W, H, W * 2, 64f);
        assertTrue(other, other.contains("NOT the picked row"));
        assertTrue(other, other.contains("a plain Bayer"));
        assertFalse(other, other.contains("no pedestal"));
        assertTrue(other, other.contains("ISP stream in the session"));
    }
}
