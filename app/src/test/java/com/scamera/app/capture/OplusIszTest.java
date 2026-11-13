package com.scamera.app.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import java.util.List;

/**
 * P586: the OnePlus 16 (PYB110) ISZ without the remosaic - the izoom QBC row 11 of the main and the tele (the report's tables,
 * OppoNativeFullRes.PYB110_MAIN / PYB110_TELE), RAW only whatever «Поток ISP», proved by the reported mode.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class OplusIszTest {
    @After public void tearDown() {
        OplusIsz.resetForTest();
        ShadowBuild.reset();
    }

    private static void pyb110() {
        ShadowBuild.setManufacturer("OnePlus");
        ShadowBuild.setModel("PYB110");
    }

    @Test public void theIszRowsAreRow11OfTheMainAndTheTele() {
        assertArrayEquals("main: 4080x3072, a quarter of 16320", new int[]{11, 4080, 3072, 30}, OplusIsz.row(true, "2", OppoNativeFullRes.PYB110_MAIN));
        assertArrayEquals("tele: 4096x3072, half of 8192", new int[]{11, 4096, 3072, 30}, OplusIsz.row(true, "4", OppoNativeFullRes.PYB110_TELE));
        assertNull("another phone", OplusIsz.row(false, "2", OppoNativeFullRes.PYB110_MAIN));
        assertNull("the ultra-wide", OplusIsz.row(true, "3", OppoNativeFullResTest.PYB110_WIDE));
        assertNull("the front", OplusIsz.row(true, "1", OppoNativeFullResTest.PYB110_FRONT));
        assertNull("no table", OplusIsz.row(true, "2", null));
    }

    @Test public void theModuleLabelsAreTwiceTheirZoom() {
        assertEquals("2×", OplusIsz.label(2f));
        assertEquals("6.4×", OplusIsz.label(6.4f));
        assertEquals("6×", OplusIsz.label(6.02f));
    }

    /** The pick of row 11 on the PYB110 main / tele is the route's: its Quad declared, RAW only; any other row or phone as it was. */
    @Test public void row11IsMarkedAsTheIszRoute() {
        pyb110();
        assertTrue(OplusIsz.device());
        final SensorModePick.Pick main = OplusIsz.mark("2", "back4", new SensorModePick.Pick(11, 4080, 3072));
        assertEquals(SensorModePick.ISZ, main.route);
        assertEquals(2, main.block);
        assertTrue(main.rawOnly());
        assertFalse(main.dcg());
        final SensorModePick.Pick tele = OplusIsz.mark("4", "back5", new SensorModePick.Pick(11, 4096, 3072));
        assertTrue(tele.rawOnly());
        final SensorModePick.Pick other = new SensorModePick.Pick(9, 8160, 6144);
        assertSame("another row: unchanged", other, OplusIsz.mark("2", "back4", other));
        final SensorModePick.Pick wrongSize = new SensorModePick.Pick(11, 4096, 3072);
        assertSame("not the camera's row 11", wrongSize, OplusIsz.mark("2", "back4", wrongSize));
        ShadowBuild.setModel("CPH2747");
        final SensorModePick.Pick op15 = new SensorModePick.Pick(11, 4080, 3072);
        assertSame("the OnePlus 15: unchanged", op15, OplusIsz.mark("2", "back4", op15));
    }

    /**
     * Owner, 2026-11-09: «При съемке без ремозаика isp превью у него должно отключаться» - an ISZ row runs with no ISP stream
     * whatever «Поток ISP» (0 Auto, 1 On, 2 Off, 3 fake off); «У DCG не обязательно отключать isp» - the DCG / DAG row follows it.
     */
    @Test public void noIspStreamForTheIszRowsTheDcgRowFollowsTheSetting() {
        final SensorModePick.Pick isz = new SensorModePick.Pick(11, 4080, 3072, false, 2, SensorModePick.ISZ);
        for (int setting = 0; setting <= 3; setting++)
            assertTrue("ISZ, «Поток ISP» " + setting, SensorModePick.ispStreamOff(setting, isz, false, false));
        final SensorModePick.Pick dcg = new SensorModePick.Pick(28, 4080, 3072, false, 0, SensorModePick.DCG);
        assertFalse("DCG, Auto: the ISP stream stays (as Xiaomi's DCG)", SensorModePick.ispStreamOff(0, dcg, false, false));
        assertFalse("DCG, On", SensorModePick.ispStreamOff(1, dcg, false, false));
        assertTrue("DCG, Off", SensorModePick.ispStreamOff(2, dcg, false, false));
        assertFalse("DCG, fake off: in the session", SensorModePick.ispStreamOff(3, dcg, false, false));
        // the rules before P586
        final SensorModePick.Pick plain = new SensorModePick.Pick(8, 4096, 3072);
        assertTrue("a module's pick, Auto", SensorModePick.ispStreamOff(0, plain, false, false));
        assertFalse("a module's pick, On", SensorModePick.ispStreamOff(1, plain, false, false));
        assertTrue("a nubia row, On", SensorModePick.ispStreamOff(1, plain, false, true));
        assertTrue("a vivo ISZ, Auto", SensorModePick.ispStreamOff(0, null, true, false));
        assertFalse("nothing forced, Auto", SensorModePick.ispStreamOff(0, null, false, false));
        assertTrue("Off", SensorModePick.ispStreamOff(2, null, false, false));
        assertFalse("fake off", SensorModePick.ispStreamOff(3, null, false, false));
    }

    /** The sessions' reported mode: an ISZ row's 11 runs, a native FULLRES session is watched as such, the normal one logs its own. */
    @Test public void theSessionsWatchTheirReportedMode() {
        final NubiaFullRes.ModeWatch w = new NubiaFullRes.ModeWatch("OPPO family (oplus ISZ)", "2", 11);
        assertTrue(w.observe(34).contains("OPPO family (oplus ISZ) camera 2: the results report sensor mode 34, asked 11 (waiting)"));
        for (int i = 0; i < NubiaFullRes.ModeWatch.PATIENCE; i++) w.observe(34);
        assertTrue(w.failure().contains("sensor mode is not active"));
        final NubiaFullRes.ModeWatch runs = new NubiaFullRes.ModeWatch("OPPO family (FULLRES)", "2", 9);
        assertTrue(runs.observe(9).contains("the row runs"));
        assertNull(runs.failure());
        pyb110();
        OplusIsz.watchSession("2", null, new VivoSdkFullRes.Profile(8160, 6144, 9, 2));
        assertTrue(OplusIsz.watchingFullRes());
        OplusIsz.watchSession("2", new SensorModePick.Pick(11, 4080, 3072, false, 2, SensorModePick.ISZ), null);
        assertFalse(OplusIsz.watchingFullRes());
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        OplusIsz.watchSession("2", null, new VivoSdkFullRes.Profile(8192, 6144, 35, 2));
        assertFalse("the PHY110 as before: no watch", OplusIsz.watchingFullRes());
    }

    /** The OEM's custom RAW sizes of CameraHWConfiguration.config (the PYB110 report, lines 22986-22990). */
    /**
     * P593 (log 2026-11-09 22:07:24: the main's «2×» took «IMX363_0 (LG V50)», its source and the tele's «2×» the camera's own):
     * an ISZ copy whose noise-model profile is not its source module's gets the source's; equal ones (a missing one is «Авто») stay.
     */
    @Test public void anIszCopyTakesItsSourcesNoiseProfile() {
        assertTrue(OplusIsz.noiseDiffers("lgv50_imx363_0", null));
        assertTrue(OplusIsz.noiseDiffers("lgv50_imx363_0", "auto"));
        assertTrue(OplusIsz.noiseDiffers(null, "imported"));
        assertFalse("both «Авто»", OplusIsz.noiseDiffers(null, "auto"));
        assertFalse(OplusIsz.noiseDiffers("auto", null));
        assertFalse(OplusIsz.noiseDiffers("lgv50_imx363_0", "lgv50_imx363_0"));
        assertEquals("pref_noise_model_profile_key", OplusIsz.NOISE_KEY);
    }

    /** P593: the learn line is said again with every OnePlus 16 session (the owner's log began after the camera was learned). */
    @Test public void aSessionSaysItsCamerasLearnLine() {
        pyb110();
        try {
            OppoNativeFullRes.learn("p593-4", true, OppoNativeFullRes.PYB110_TELE, false, 4096, 3072, null, "test");
            final String line = OppoNativeFullRes.learnLine("p593-4");
            assertTrue(line, line.startsWith("OPPO family camera p593-4 (OnePlus PYB110): mode key true, table test"));
            assertTrue(line, line.contains("FULLRES mode 9 8192x6144@15 block 2"));
            assertFalse("one 50 MP row on the tele: nothing moved", line.contains("last: the camera died"));
            assertNull(OppoNativeFullRes.learnLine("p593-none"));
            OplusIsz.watchSession("p593-4", null, null);
            assertFalse(OplusIsz.watchingFullRes());
        } finally {
            OppoNativeFullRes.forget("p593-4");
        }
        assertNull("forgotten with its offer", OppoNativeFullRes.learnLine("p593-4"));
    }

    @Test public void theCustomRawSizesAreParsed() {
        final String config = "[FlowControl]\nisEnableFlowControl = TRUE;\n\n[OemSupportedCustomRAWFullSizes]\n"
                + "    #                        width  height  width   height\n"
                + "    Size[pokemonmain]       =   8160;  6144;   8160;   4590;   6144;   6144;   8160;   3745\n"
                + "    Size[pokemontele]       =   8192;  6144;   8192;   4608;   6144;   6144;   8192;   3760\n"
                + "##############caps_mask##############\n[OemCamSeamlessSettingMap]\n    Size[x] = 1; 2\n";
        final List<int[]> sizes = OplusRawSizes.parse(config);
        assertEquals(7, sizes.size());
        assertTrue(OppoNativeFullRes.listed(sizes, 8160, 6144));
        assertTrue(OppoNativeFullRes.listed(sizes, 8192, 6144));
        assertFalse("the 200 MP size is not listed", OppoNativeFullRes.listed(sizes, 16320, 12288));
        assertFalse("not of the next section", OppoNativeFullRes.listed(sizes, 1, 2));
        assertTrue(OplusRawSizes.parse(null).isEmpty());
    }

    /** P586: CROPPED_RAW (the HAL's own ISZ, izoom hwRemosaic) is off on the OPPO family unless scam_dev turns it on. */
    @Test public void croppedRawIsOffOnTheFamily() {
        assertFalse(CroppedRaw.familyAllows(true, false));
        assertTrue("scam_dev oplus_cropped_raw 1", CroppedRaw.familyAllows(true, true));
        assertTrue("other phones as before", CroppedRaw.familyAllows(false, false));
    }
}
