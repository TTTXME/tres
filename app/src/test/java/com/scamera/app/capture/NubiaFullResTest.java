package com.scamera.app.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

/**
 * P575: nubia FULLRES / «2×» ISZ from the cameras' sensor-mode tables, with the tables of the NX721J's SCAM TEST report
 * (2026-11-02: the root dumpsys static metadata, org.quic.camera2.sensormode.info.SensorModeTable).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class NubiaFullResTest {
    /** Camera 0 (and logical 4): IMX800, pixel array 8192x6144. */
    static final int[] MAIN = {10, 3, 8192, 6144, 30, 4096, 3072, 30, 4096, 3072, 60, 4096, 3072, 120, 3840, 2160, 60,
            2048, 1536, 240, 2048, 1536, 360, 2048, 1152, 30, 1280, 720, 480, 4096, 3072, 5};
    /** Camera 2: OV50E40, pixel array 8192x6144. */
    static final int[] WIDE = {6, 3, 8192, 6144, 30, 4096, 3072, 30, 4096, 3072, 60, 4096, 3072, 10, 3840, 2160, 120, 2048, 1152, 30};
    /** Camera 3: OV64B40 periscope, pixel array 9248x6944. */
    static final int[] TELE = {9, 3, 9248, 6944, 30, 4624, 3472, 30, 4624, 3472, 60, 3840, 2160, 60, 3840, 2160, 120, 1920, 1080, 240,
            1280, 720, 480, 4624, 3472, 30, 4624, 3472, 15};
    /** P608: the stock HAL's tables (the owner's log of 31252, no Magisk module): camera 2 OV50E40, camera 3 OV64B40. */
    static final int[] WIDE_STOCK = {6, 3, 8192, 6144, 20, 4096, 3072, 30, 4096, 3072, 60, 4096, 3072, 10, 3840, 2160, 120, 2048, 1152, 30};
    static final int[] TELE_STOCK = {9, 3, 9248, 6944, 15, 4624, 3472, 30, 4624, 3472, 60, 3840, 2160, 60, 3840, 2160, 120, 1920, 1080, 240,
            1280, 720, 480, 4624, 3472, 30, 4624, 3472, 15};
    /** Camera 1, the front: its table lists 3840x2160 over a 1928x1088 pixel array. */
    static final int[] FRONT = {2, 3, 3840, 2160, 30, 1920, 1080, 30};

    @After public void tearDown() {
        NubiaFullRes.resetForTest();
        ShadowBuild.reset();
    }

    @Test public void theNubiaPhones() {
        assertTrue(NubiaFullRes.phone("nubia", "nubia"));
        assertTrue(NubiaFullRes.phone("NUBIA", null));
        assertTrue("a ZTE-made phone of the nubia brand", NubiaFullRes.phone("ZTE", "nubia"));
        assertFalse(NubiaFullRes.phone("ZTE", "ZTE"));
        assertFalse(NubiaFullRes.phone("OPPO", "OPPO"));
        assertFalse(NubiaFullRes.phone(null, null));
    }

    @Test public void theRearCamerasOfferTheirFullSizeRow() {
        assertArrayEquals(new int[]{0, 8192, 6144, 30}, NubiaFullRes.fullRow(MAIN, 8192, 6144));
        assertArrayEquals(new int[]{0, 8192, 6144, 30}, NubiaFullRes.fullRow(WIDE, 8192, 6144));
        assertArrayEquals(new int[]{0, 9248, 6944, 30}, NubiaFullRes.fullRow(TELE, 9248, 6944));
        assertEquals("the binned Quad row beside it", 1, NubiaFullRes.binnedRow(MAIN, 8192, 6144));
        assertEquals(1, NubiaFullRes.binnedRow(TELE, 9248, 6944));
        // the merge's block of that row: the sensor's own Quad (64 MP is under the Tetra threshold)
        assertEquals(2, SensorModePick.declaredBlock(MAIN, 0));
        assertEquals(2, SensorModePick.declaredBlock(TELE, 0));
        assertEquals("a binned row is no mosaic", 0, SensorModePick.declaredBlock(MAIN, 1));
    }

    @Test public void noFullResWithoutAFullSizeRowOfTheArray() {
        assertNull("the front's largest row is not its pixel array", NubiaFullRes.fullRow(FRONT, 1928, 1088));
        assertNull("a table of another array", NubiaFullRes.fullRow(MAIN, 4096, 3072));
        assertNull("no binned row beside the full one", NubiaFullRes.fullRow(new int[]{2, 3, 8192, 6144, 30, 3840, 2160, 60}, 8192, 6144));
        assertNull(NubiaFullRes.fullRow(null, 8192, 6144));
        assertNull(NubiaFullRes.fullRow(new int[]{0, 3}, 8192, 6144));
        assertNull("no pixel array", NubiaFullRes.fullRow(MAIN, 0, 0));
    }

    @Test public void onlyANubiaPhoneLearnsAndPicks() {
        ShadowBuild.setManufacturer("nubia");
        ShadowBuild.setBrand("nubia");
        ShadowBuild.setModel("NX721J");
        assertTrue(NubiaFullRes.device());
        assertNotNull(NubiaFullRes.learn("0", MAIN, 8192, 6144));
        assertNull(NubiaFullRes.learn("1", FRONT, 1928, 1088));
        assertTrue(NubiaFullRes.known("0"));
        assertTrue(NubiaFullRes.known("1"));
        assertFalse(NubiaFullRes.known("3"));
        assertTrue(NubiaFullRes.offered("0"));
        assertFalse(NubiaFullRes.offered("1"));
        assertEquals(50, NubiaFullRes.megapixels("0"));
        NubiaFullRes.offerForTest("3", new int[]{0, 9248, 6944, 30});
        assertEquals("the tele's 64 MP", 60, NubiaFullRes.megapixels("3"));

        SensorModePick.Pick pick = NubiaFullRes.fullResPick("0", null, true);
        assertNotNull(pick);
        assertEquals(0, pick.mode);
        assertEquals(8192, pick.width);
        assertEquals(6144, pick.height);
        assertTrue("the size switch's row: its failure turns FULLRES off", pick.fullRes);
        assertNull("the size switch at 12 Мп: the normal session", NubiaFullRes.fullResPick("0", null, false));
        assertNull("the front has no row", NubiaFullRes.fullResPick("1", null, true));

        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setBrand("OPPO");
        assertFalse(NubiaFullRes.device());
        assertFalse("another phone never takes the route", NubiaFullRes.offered("0"));
        assertNull(NubiaFullRes.fullResPick("0", null, true));
    }

    @Test public void theRowGoesOnTheSessionAsTheQualcommModeKey() {
        // the key itself on a builder: SensorModePickTest (a plain JUnit test, the builder mocked)
        assertEquals(SensorModePick.Brand.QCOM, SensorModePick.brand("nubia"));
        assertEquals("org.codeaurora.qcamera3.sensor_meta_data.current_mode", SensorModePick.QCOM_KEY);
    }

    @Test public void theNormalSessionOnlyLogsItsMode() {
        NubiaFullRes.ModeWatch w = new NubiaFullRes.ModeWatch("4", -1);
        assertNull("no key: nothing said", w.observe(null));
        assertTrue(w.observe(1).contains("normal session: the results report sensor mode 1"));
        assertNull("said once", w.observe(1));
        for (int i = 0; i < 40; i++) w.observe(1);
        assertNull("a normal session never fails over", w.failure());
    }

    @Test public void thePickedRowProvesItselfOrFailsOver() {
        NubiaFullRes.ModeWatch runs = new NubiaFullRes.ModeWatch("0", 0);
        assertTrue(runs.observe(0).contains("the row runs"));
        assertNull(runs.failure());

        NubiaFullRes.ModeWatch late = new NubiaFullRes.ModeWatch("0", 0);
        assertTrue(late.observe(1).contains("(waiting)"));
        for (int i = 0; i < 5; i++) late.observe(1);
        assertTrue(late.observe(0).contains("the row runs"));
        for (int i = 0; i < 40; i++) late.observe(1);
        assertNull("once it ran, a later report does not fail it", late.failure());

        NubiaFullRes.ModeWatch never = new NubiaFullRes.ModeWatch("0", 0);
        for (int i = 0; i < NubiaFullRes.ModeWatch.PATIENCE - 1; i++) never.observe(1);
        assertNull(never.failure());
        never.observe(1);
        final String why = never.failure();
        assertNotNull(why);
        assertEquals(FullResTrials.MODE, FullResTrials.code(why));
        assertNull("said once", never.failure());

        NubiaFullRes.ModeWatch silent = new NubiaFullRes.ModeWatch("0", 0);
        for (int i = 0; i < 100; i++) silent.observe(null);
        assertNull("results without the key say nothing", silent.failure());
    }

    @Test public void aFailedRowIsRememberedForThisFirmwareAndRetried() {
        final SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("nubia_trials", 0);
        p.edit().clear().apply();
        final String stamp = "r1 nubia/NX721J/1";
        assertEquals(0, NubiaFullRes.failures(p, "0", stamp));
        assertNull(NubiaFullRes.status(p, "0", stamp));
        NubiaFullRes.offerForTest("0", new int[]{0, 8192, 6144, 30});
        NubiaFullRes.note(p, "0", 0, "the frames are remosaicked by the HAL (plain Bayer, no mosaic)", stamp);
        assertEquals(1, NubiaFullRes.failures(p, "0", stamp));
        assertEquals("another camera keeps its row", 0, NubiaFullRes.failures(p, "2", stamp));
        final String status = NubiaFullRes.status(p, "0", stamp);
        assertNotNull(status);
        assertTrue(status, status.contains("FULLRES 50"));
        assertTrue(status, status.contains("0"));
        assertEquals("a firmware update tries the row again", 0, NubiaFullRes.failures(p, "0", "r1 nubia/NX721J/2"));
        assertTrue(NubiaFullRes.clearFailures(p, "0") > 0);
        assertEquals(0, NubiaFullRes.failures(p, "0", stamp));
        assertEquals(FullResTrials.HW_REMOSAIC, FullResTrials.code("the frames are remosaicked by the HAL (plain Bayer, no mosaic)"));
        assertEquals(FullResTrials.NO_FRAME, FullResTrials.code("no preview frame for 3000 ms"));
    }

    @Test public void theFailureMessageIsRussianAndNamesTheRow() {
        final String text = NubiaFullRes.failed("0", 0, "the frames are remosaicked by the HAL (plain Bayer, no mosaic)");
        assertTrue(text, text.contains("0"));
        assertTrue(text, text.contains("FULLRES"));
    }

    /** P585: the 2026-11-09 session advertised 4096x3072 (binned static metadata): no FULLRES, and the log says why. */
    @org.junit.Test public void aBinnedAdvertisedArrayHasNoRowAndTheLogNamesTheLargerOne() {
        org.junit.Assert.assertNull(NubiaFullRes.fullRow(MAIN, 4096, 3072));
        org.junit.Assert.assertTrue(NubiaFullRes.largerRow(MAIN, 4096, 3072).contains("row 0 is 8192x6144"));
        org.junit.Assert.assertEquals("", NubiaFullRes.largerRow(MAIN, 8192, 6144));
    }

    /** P588: the stock HAL (no Magisk module) advertises 4096x3072 with a maximum-resolution RAW of 8192x6144: FULLRES through it. */
    @org.junit.Test public void theMaximumResolutionRawIsTheFullResWithoutARow() {
        final android.util.Size array = new android.util.Size(4096, 3072);
        NubiaFullRes.learnMaxRes("p588", new PixelPro10.FullRes(android.graphics.ImageFormat.RAW_SENSOR, new android.util.Size(8192, 6144)), array);
        NubiaFullRes.learnMaxRes("p588small", new PixelPro10.FullRes(android.graphics.ImageFormat.RAW_SENSOR, new android.util.Size(4096, 3072)), array);
        org.junit.Assert.assertNull("no row pick for it", NubiaFullRes.offer("p588"));
        org.junit.Assert.assertFalse("not larger than the binned array: none", NubiaFullRes.maxRes("p588small") && NubiaFullRes.device());
    }

    // ---------------------------------------------------------------- P608: the full mode asked and proven

    private static void nubia() {
        ShadowBuild.setManufacturer("nubia");
        ShadowBuild.setBrand("nubia");
        ShadowBuild.setModel("NX721J");
    }

    private static PixelPro10.FullRes raw(int w, int h) {
        return new PixelPro10.FullRes(android.graphics.ImageFormat.RAW_SENSOR, new android.util.Size(w, h));
    }

    /**
     * P608 (owner: «Ты так же не дал возможность включить 64мп на телевике»): the stock tele (camera 3) advertises 4624x3472 and a
     * maximum-resolution RAW of 9248x6944 - no streamable row, the maximum-resolution route, its full row 0 asked, «64 Мп».
     */
    @Test public void theTeleOffersItsSixtyFourMegapixelsThroughTheMaximumResolutionRoute() {
        nubia();
        assertNull("no row of the advertised array", NubiaFullRes.learn("3", TELE_STOCK, new android.util.Size(4624, 3472), raw(9248, 6944)));
        assertTrue(NubiaFullRes.maxRes("3"));
        assertEquals("the maximum-resolution size's row", 0, NubiaFullRes.maxResRow("3"));
        assertTrue(NubiaFullRes.offered("3"));
        assertFalse(NubiaFullRes.exhausted("3"));
        assertEquals(60, NubiaFullRes.megapixels("3"));
        assertTrue(NubiaFullRes.sizeText("3"), NubiaFullRes.sizeText("3").startsWith("64 "));
        assertEquals(new android.util.Size(9248, 6944), NubiaFullRes.maxResSize("3"));
        // the 0.5x (camera 2) the same way: row 0 8192x6144, «50»
        assertNull(NubiaFullRes.learn("2", WIDE_STOCK, new android.util.Size(4096, 3072), raw(8192, 6144)));
        assertEquals(0, NubiaFullRes.maxResRow("2"));
        assertTrue(NubiaFullRes.sizeText("2").startsWith("50 "));
        // no row of that size in the table: the mode is not asked (-1), the route as before
        NubiaFullRes.learnMaxRes("9", raw(8000, 6000), new android.util.Size(4000, 3000), TELE_STOCK);
        assertEquals(-1, NubiaFullRes.maxResRow("9"));
        assertEquals(-1, NubiaFullRes.maxResRow("1"));
        assertEquals(-1, NubiaFullRes.maxResRow(null));
        assertEquals(1, NubiaFullRes.rowOfSize(TELE_STOCK, 4624, 3472));
        assertEquals(-1, NubiaFullRes.rowOfSize(null, 4624, 3472));
    }

    @Test public void theMaximumResolutionSessionMustReportItsFullRow() {
        nubia();
        NubiaFullRes.learn("2", WIDE_STOCK, new android.util.Size(4096, 3072), raw(8192, 6144));
        NubiaFullRes.watchMaxRes("2");
        assertEquals("nothing reported yet", 0, NubiaFullRes.maxResModeState("2"));
        assertNull(NubiaFullRes.observeForTest(1));
        assertEquals("the binned row so far", -1, NubiaFullRes.maxResModeState("2"));
        assertEquals("another camera's state is none", 0, NubiaFullRes.maxResModeState("3"));
        String why = null;
        for (int i = 1; i < NubiaFullRes.ModeWatch.PATIENCE && why == null; i++) why = NubiaFullRes.observeForTest(1);
        assertNotNull("the 31252 log: mode 1 in every result", why);
        assertTrue(why, why.contains(NubiaFullRes.BINNED_TAG));
        assertEquals(NubiaRemosaicProbe.BINNED, NubiaRemosaicProbe.code(why));
        assertNull("said once", NubiaFullRes.observeForTest(1));

        NubiaFullRes.watchMaxRes("2");
        NubiaFullRes.observeForTest(1);
        assertNull(NubiaFullRes.observeForTest(0));
        assertEquals("the full row runs", 1, NubiaFullRes.maxResModeState("2"));
        for (int i = 0; i < 40; i++) assertNull(NubiaFullRes.observeForTest(1));

        NubiaFullRes.watchSession("2", null);
        assertEquals("the normal session is no maximum-resolution one", 0, NubiaFullRes.maxResModeState("2"));
        for (int i = 0; i < 40; i++) assertNull("the normal session never fails over", NubiaFullRes.observeForTest(1));

        // a camera whose maximum-resolution size has no row: nothing asked, nothing failed (logged as a normal session)
        NubiaFullRes.learnMaxRes("9", raw(8000, 6000), new android.util.Size(4000, 3000), TELE_STOCK);
        NubiaFullRes.watchMaxRes("9");
        for (int i = 0; i < 40; i++) assertNull(NubiaFullRes.observeForTest(1));
        assertEquals(0, NubiaFullRes.maxResModeState("9"));
    }

    // ---- the data guard: a binned frame packed into the maximum-resolution buffer

    private static final int W = 256, H = 192;

    private static java.nio.ByteBuffer frame(int stride, int offset) {
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(offset + stride * H).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.position(offset);
        return b;
    }

    private static void put(java.nio.ByteBuffer b, int at, int v) {
        b.put(at, (byte) v);
        b.put(at + 1, (byte) (v >> 8));
    }

    @Test public void aBinnedFramePackedIntoTheFullBufferIsFound() {
        final java.util.Random r = new java.util.Random(608);
        // the HAL's way (31252): the (W/2)x(H/2) binned frame at its own row pitch W bytes - a quarter of the rows
        java.nio.ByteBuffer b = frame(W * 2, 0);
        for (int i = 0; i < (W / 2) * (H / 2); i++) put(b, i * 2, 64 + r.nextInt(900));
        assertTrue(NubiaFullRes.partialFrame(b, W, H, W * 2, 64));
        // at the buffer's pitch: the left half of half the rows
        b = frame(W * 2, 0);
        for (int y = 0; y < H / 2; y++) for (int x = 0; x < W / 2; x++) put(b, y * W * 2 + x * 2, 64 + r.nextInt(900));
        assertTrue(NubiaFullRes.partialFrame(b, W, H, W * 2, 64));
        // padded rows and a buffer that starts further on
        final int stride = W * 2 + 64, offset = 128;
        b = frame(stride, offset);
        for (int y = 0; y < H / 4; y++) for (int x = 0; x < W; x++) put(b, offset + y * stride + x * 2, 64 + r.nextInt(900));
        assertTrue(NubiaFullRes.partialFrame(b, W, H, stride, 64));
        assertTrue("black unknown: an empty bottom (0) is still below any pedestal", NubiaFullRes.partialFrame(b, W, H, stride, 0));
    }

    @Test public void aRealFrameIsNoPartialOne() {
        final java.util.Random r = new java.util.Random(6080);
        java.nio.ByteBuffer b = frame(W * 2, 0);
        for (int i = 0; i < W * H; i++) put(b, i * 2, 64 + r.nextInt(900));
        assertFalse("a full frame", NubiaFullRes.partialFrame(b, W, H, W * 2, 64));
        b = frame(W * 2, 0);
        for (int i = 0; i < W * H; i++) put(b, i * 2, 60 + r.nextInt(9));
        assertFalse("a dark frame scatters round its pedestal", NubiaFullRes.partialFrame(b, W, H, W * 2, 64));
        b = frame(W * 2, 0);
        for (int i = 0; i < W * H; i++) put(b, i * 2, i < W * H / 4 ? 64 + r.nextInt(900) : 64);
        assertFalse("a flat bottom at the pedestal is a picture (a dark lower half), not an empty buffer", NubiaFullRes.partialFrame(b, W, H, W * 2, 64));
        b = frame(W * 2, 0);
        for (int i = 0; i < W * H; i++) put(b, i * 2, i < W * H / 4 ? 64 + r.nextInt(900) : 1023);
        assertFalse("a clipped bottom is no empty one", NubiaFullRes.partialFrame(b, W, H, W * 2, 64));
        assertFalse("all empty: no smaller frame in it", NubiaFullRes.partialFrame(frame(W * 2, 0), W, H, W * 2, 64));
        assertFalse(NubiaFullRes.partialFrame(null, W, H, W * 2, 64));
        assertFalse("a buffer shorter than its rows", NubiaFullRes.partialFrame(java.nio.ByteBuffer.allocate(W * 2 * 10), W, H, W * 2, 64));
        assertFalse("a stride under the row", NubiaFullRes.partialFrame(frame(W * 2, 0), W, H, W, 64));
    }

    @Test public void thePartialFrameNamesTheBinnedModeOrACrop() {
        final String binned = NubiaFullRes.partialWhy(8192, 6144, false), crop = NubiaFullRes.partialWhy(8192, 6144, true);
        assertEquals(NubiaRemosaicProbe.BINNED, NubiaRemosaicProbe.code(binned));
        assertEquals("the full row reported, the frame a part: an in-sensor-zoom crop", NubiaRemosaicProbe.CROP, NubiaRemosaicProbe.code(crop));
        assertTrue(binned.contains("8192x6144"));
    }

    @Test public void theTrialsOfTheBinnedSessionsAreForgotten() {
        assertEquals("P608: revision 2 - every verdict of revision 1 came from binned frames", 2, NubiaFullRes.ROW_TRIALS);
        assertEquals(FullResTrials.stamp(2), NubiaFullRes.stamp());
    }

    // ---- P626: the per-shot way's watch, the frame's fill

    @Test public void aPerShotWatchCountsTheMaximumResolutionResultsOnly() {
        nubia();
        NubiaFullRes.learn("2", WIDE_STOCK, new android.util.Size(4096, 3072), raw(8192, 6144));
        NubiaFullRes.watchMaxRes("2", true);
        final NubiaFullRes.ModeWatch w = new NubiaFullRes.ModeWatch("nubia", "2", 0, true, true);
        assertFalse("the DEFAULT viewfinder's results say nothing", w.counts(null));
        assertFalse(w.counts(android.hardware.camera2.CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT));
        assertTrue(w.counts(android.hardware.camera2.CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION));
        assertTrue("the all-maximum-resolution watch counts every result", new NubiaFullRes.ModeWatch("nubia", "2", 0, true).counts(null));
        // the proof burst's results: its frames are the patience
        String why = null;
        for (int i = 0; i < NubiaMaxResPlan.PROOF_FRAMES && why == null; i++) why = NubiaFullRes.observeForTest(1);
        assertNotNull("binned in every MAXIMUM_RESOLUTION result of the proof", why);
        assertTrue(why, why.contains(NubiaMaxResPlan.PROOF_FRAMES + " MAXIMUM_RESOLUTION results"));
        assertEquals(NubiaRemosaicProbe.BINNED, NubiaRemosaicProbe.code(why));
        NubiaFullRes.watchMaxRes("2", true);
        assertNull(NubiaFullRes.observeForTest(0));
        assertEquals("the full row runs", 1, NubiaFullRes.maxResModeState("2"));
        final String line = w.observe(0, NubiaFullRes.resultDetail(1, 0, Boolean.TRUE));
        assertTrue(line, line.contains("asked 0") && line.contains("request MAXIMUM_RESOLUTION, result pixel mode DEFAULT, rawBinningFactorUsed true"));
        assertTrue(NubiaFullRes.resultDetail(null, null, null).contains("request DEFAULT, result pixel mode none, rawBinningFactorUsed none"));
    }

    @Test public void theFillOfTheFrameIsMeasured() {
        final java.util.Random r = new java.util.Random(626);
        java.nio.ByteBuffer b = frame(W * 2, 0);
        for (int i = 0; i < W * H; i++) put(b, i * 2, 64 + r.nextInt(900));
        assertEquals("a full frame", H, NubiaFullRes.pictureRows(b, W, H, W * 2, 64));
        // the binned frame at its own pitch: a quarter of the rows
        b = frame(W * 2, 0);
        for (int i = 0; i < (W / 2) * (H / 2); i++) put(b, i * 2, 64 + r.nextInt(900));
        final int rows = NubiaFullRes.pictureRows(b, W, H, W * 2, 64);
        assertTrue("about a quarter: " + rows, rows >= H / 4 && rows <= H / 4 + NubiaFullRes.FILL_STEP_ROWS);
        final String quarter = NubiaFullRes.fillLine("2", "«qcfa_snapshot»", 8192, 6144, 16384, 1536);
        assertTrue(quarter, quarter.contains("picture rows 0..1535 of 6144 (25 %)") && quarter.contains("binned"));
        final String full = NubiaFullRes.fillLine("2", null, 8192, 6144, 16384, 6144);
        assertTrue(full, full.contains("(100 %)") && full.contains("the full field"));
        assertEquals("all empty", 0, NubiaFullRes.pictureRows(frame(W * 2, 0), W, H, W * 2, 64));
        assertTrue(NubiaFullRes.fillLine("2", null, 8192, 6144, 16384, 0).contains("no picture"));
        assertEquals(0, NubiaFullRes.pictureRows(null, W, H, W * 2, 64));
        assertEquals("a buffer shorter than its rows", 0, NubiaFullRes.pictureRows(java.nio.ByteBuffer.allocate(W * 2 * 10), W, H, W * 2, 64));
    }
}
