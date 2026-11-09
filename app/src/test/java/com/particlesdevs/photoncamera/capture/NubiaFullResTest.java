package com.particlesdevs.photoncamera.capture;

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
}
