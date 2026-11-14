package com.scamera.app.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * P689 (owner, 2026-11-14: «Изучи отчёт по X9 Ultra и реализуй фулрез 50/200 без ремозаика без рута»): the OPPO Find X9 Ultra's
 * FULLRES 50 MP by the X9 Ultra full-size recipe - the per-camera rows of the owner's SCAM TEST report (2026-10-11), the gate of
 * what a camera lists, the recipe's streams, keys and scopes, the «Поток ISP» ladder (Auto: fake off, then off), the trials per
 * camera and firmware, and 200 MP kept off. The sensor-mode tables are the report's (NDK tag 0x80090000, cameras 1-5).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class OplusX9UltraTest {
    static final int[] CAMERA_1 = {23, 3, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096,
            3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 2560, 30, 4096, 2560, 60, 4096,
            2560, 30, 4096, 2560, 30, 4096, 3072, 60, 4096, 2560, 30, 4096, 2560, 60, 4096, 2560, 30, 4096, 2560, 30, 4096,
            3072, 60, 4096, 2304, 30, 4096, 2304, 60, 8192, 6144, 15};
    static final int[] CAMERA_2 = {51, 3, 16320, 12256, 10, 4080, 3064, 30, 4080, 3064, 30, 4080, 3064, 30, 4080, 3064, 30,
            8160, 6128, 30, 8160, 6128, 30, 4080, 3064, 30, 4080, 3064, 30, 4080, 3064, 30, 4080, 3064, 30, 4080, 3064, 30,
            8160, 6128, 30, 8160, 6128, 30, 4080, 3064, 30, 4080, 3064, 30, 2560, 1920, 30, 2560, 1920, 30, 1920, 1440, 30,
            1920, 1440, 30, 4080, 2552, 30, 4080, 2552, 30, 4080, 2552, 30, 4080, 2296, 30, 4080, 2552, 30, 4080, 2552, 60,
            4080, 2552, 60, 4080, 2552, 60, 3840, 2160, 120, 8160, 4592, 30, 4080, 2552, 30, 4080, 2552, 30, 4080, 2552, 30,
            4080, 2296, 30, 4080, 2552, 30, 4080, 2552, 60, 4080, 2552, 60, 4080, 2552, 60, 3840, 2160, 120, 8160, 4592, 30,
            4080, 2296, 120, 4080, 2296, 60, 2000, 1148, 240, 2000, 1148, 480, 8160, 6128, 30, 8160, 6128, 21, 8160, 6128, 30,
            4080, 2296, 30, 4080, 3064, 60, 4080, 2296, 60, 4080, 3064, 30};
    static final int[] CAMERA_3 = {24, 3, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 8192,
            6144, 15, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 8192, 6144, 15, 3200, 2400, 30, 4096, 2560, 30, 4096,
            2560, 60, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 60, 4096, 2560, 30, 2048, 1152, 120, 2048, 1152, 240, 4096,
            3072, 60, 4096, 2304, 30, 4096, 2304, 60, 4096, 2560, 30, 4096, 2560, 30};
    static final int[] CAMERA_4 = {63, 3, 16384, 12288, 7, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30,
            4096, 3072, 30, 4096, 3072, 30, 8192, 6144, 30, 8192, 6144, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30,
            4096, 3072, 30, 4096, 3072, 30, 3840, 2880, 30, 8192, 6144, 30, 1920, 1440, 30, 4096, 2560, 30, 4096, 2560, 30,
            4096, 2560, 30, 4096, 2560, 60, 4096, 2560, 60, 4096, 2560, 60, 4096, 2560, 60, 3840, 2160, 120, 4096, 2560, 60,
            4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 60, 4096, 2560, 60, 4096, 2304, 119, 4096, 2560, 60,
            3840, 2160, 120, 4096, 2560, 60, 2048, 1152, 120, 1920, 1080, 240, 1280, 720, 482, 4096, 3072, 30, 8192, 6144, 30,
            8192, 6144, 15, 4096, 3072, 60, 4096, 2304, 30, 4096, 2304, 60, 8192, 4608, 30, 4096, 3072, 30, 8192, 4608, 30,
            4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 60, 4096, 2560, 60,
            4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 60, 4096, 2560, 60, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30,
            4096, 3072, 30, 4096, 3072, 30};
    static final int[] CAMERA_5 = {30, 3, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096,
            3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096,
            2560, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 60, 4096, 2560, 30, 4096, 2560, 30, 4096,
            2560, 30, 4096, 2560, 30, 4096, 2560, 60, 2048, 1152, 120, 2048, 1152, 240, 4096, 3072, 30, 8192, 6144, 15, 4096,
            3072, 60, 4096, 2304, 30, 4096, 2304, 60, 4096, 3072, 30};

    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> app;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        app = mockStatic(ScameraApp.class);
        app.when(() -> ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        OppoNativeFullRes.resetForTest();
        OplusX9Ultra.resetForTest();
    }

    @After
    public void tearDown() {
        prefs.edit().clear().commit();
        OppoNativeFullRes.resetForTest();
        if (app != null) app.close();
    }

    private static void x9Ultra() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("CPH2841");
    }

    @Test public void thePhoneIsTheX9UltraByItsModels() {
        for (String model : new String[]{"CPH2841", "PMA110", "PMA120", " pma120 "})
            assertTrue(model, OplusX9Ultra.phone("OPPO", model));
        assertFalse("the Find X7 Ultra", OplusX9Ultra.phone("OPPO", "PHY110"));
        assertFalse("another brand", OplusX9Ultra.phone("OnePlus", "CPH2841"));
        assertFalse(OplusX9Ultra.phone(null, "CPH2841"));
        assertFalse(OplusX9Ultra.phone("OPPO", null));
        assertTrue(new FullResRecipePresets.Device("OPPO", "OPPO", "OP627CL1", "CPH2841").x9Ultra());
        assertFalse(new FullResRecipePresets.Device("OPPO", "OPPO", "OP5DD3L1", "PKJ110").x9Ultra());
    }

    /** What the report shows per camera, and what the native OPPO route made of it before P689. */
    @Test public void theReportsRowsAndWhatTheNativeRouteMadeOfThem() {
        // the recipe's rows are full-size QBC rows of each table; P709: the config's main row is 6 (report line 25953), the
        // recipe's 13 is in no config entry - the owner's working rows (2026-11-14, ISP auto) are the main's 5 / 12 / 13, all of the size
        assertArrayEquals(new int[]{8160, 6128, 30}, SensorModeBand.modeEntry(CAMERA_2, 6));
        for (int r : new int[]{5, 12, 13}) assertArrayEquals(new int[]{8160, 6128, 30}, SensorModeBand.modeEntry(CAMERA_2, r));
        for (int r : new int[]{5, 9}) assertArrayEquals(new int[]{8192, 6144, 15}, SensorModeBand.modeEntry(CAMERA_3, r));
        for (int r : new int[]{8, 15, 39}) assertArrayEquals(new int[]{8192, 6144, 30}, SensorModeBand.modeEntry(CAMERA_4, r));
        for (int r : new int[]{0, 25}) assertArrayEquals(new int[]{8192, 6144, 15}, SensorModeBand.modeEntry(CAMERA_5, r));
        // the tele's [0x0030][-1] = 19 (report line 26077) is no full-size row: the photo operation mode's 8 (line 26121) is
        assertArrayEquals(new int[]{4096, 2560, 30}, SensorModeBand.modeEntry(CAMERA_4, 19));
        assertArrayEquals(new int[]{8192, 6144, 15}, SensorModeBand.modeEntry(CAMERA_3, 5));
        assertArrayEquals(new int[]{8192, 6144, 30}, SensorModeBand.modeEntry(CAMERA_4, 8));
        assertArrayEquals(new int[]{8192, 6144, 15}, SensorModeBand.modeEntry(CAMERA_5, 25));
        assertArrayEquals(new int[]{8192, 6144, 15}, SensorModeBand.modeEntry(CAMERA_1, 22));
        // the 200 MP sensors: row 0 alone at the full size
        assertArrayEquals(new int[]{16320, 12256, 10}, SensorModeBand.modeEntry(CAMERA_2, 0));
        assertArrayEquals(new int[]{16384, 12288, 7}, SensorModeBand.modeEntry(CAMERA_4, 0));
        assertNull("main: no full-size row after row 0", OppoNativeFullRes.fullModes(CAMERA_2));
        assertNull("3x: no full-size row after row 0", OppoNativeFullRes.fullModes(CAMERA_4));
        final List<int[]> custom = Arrays.asList(new int[]{8160, 6128}, new int[]{8192, 6144});
        // before P689 the native route offered the main and the 3x nothing, and the 50 MP sensors their rows with the mode on
        // every request (what the recipe found breaks the viewfinder's RAW)
        assertTrue(OppoNativeFullRes.offerOf("2", true, CAMERA_2, false, 4080, 3064, custom).empty());
        assertTrue(OppoNativeFullRes.offerOf("4", true, CAMERA_4, false, 4096, 3072, custom).empty());
        assertArrayEquals(new int[]{5, 9}, OppoNativeFullRes.modes(OppoNativeFullRes.offerOf("3", true, CAMERA_3, false, 4096, 3072, custom).small));
        assertArrayEquals(new int[]{25}, OppoNativeFullRes.modes(OppoNativeFullRes.offerOf("5", true, CAMERA_5, false, 4096, 3072, custom).small));
        assertArrayEquals(new int[]{22}, OppoNativeFullRes.modes(OppoNativeFullRes.offerOf("1", true, CAMERA_1, false, 4096, 3072, custom).small));
    }

    @Test public void theGateIsWhatTheCameraLists() {
        assertNull(OplusX9Ultra.gate(OplusX9Ultra.lens("2"), true, true, CAMERA_2));
        assertNull(OplusX9Ultra.gate(OplusX9Ultra.lens("3"), true, true, CAMERA_3));
        assertNull(OplusX9Ultra.gate(OplusX9Ultra.lens("4"), true, true, CAMERA_4));
        assertNull(OplusX9Ultra.gate(OplusX9Ultra.lens("5"), true, true, CAMERA_5));
        assertNull(OplusX9Ultra.gate(OplusX9Ultra.lens("1"), true, true, CAMERA_1));
        assertNull("an unreadable table: the recipe's rows stand", OplusX9Ultra.gate(OplusX9Ultra.lens("2"), true, true, null));
        assertNotNull("the logical camera", OplusX9Ultra.gate(OplusX9Ultra.lens("0"), true, true, CAMERA_2));
        assertTrue(OplusX9Ultra.gate(OplusX9Ultra.lens("2"), false, true, CAMERA_2).contains("agingtest"));
        assertTrue(OplusX9Ultra.gate(OplusX9Ultra.lens("2"), true, false, CAMERA_2).contains("is.sdk.camera.package"));
        assertTrue("another firmware's table: no guess", OplusX9Ultra.gate(OplusX9Ultra.lens("2"), true, true, CAMERA_3)
                .contains("row 13 of this firmware's table is 4096x2560, not 8160x6128"));
        // P709: the lenses' rows, the full-size one first (owner, 2026-11-14: working with ISP auto)
        assertArrayEquals(new int[]{13, 12, 5}, OplusX9Ultra.lens("2").rows);
        assertArrayEquals(new int[]{5, 9}, OplusX9Ultra.lens("3").rows);
        assertArrayEquals(new int[]{8, 15, 39}, OplusX9Ultra.lens("4").rows);
        assertArrayEquals(new int[]{25, 0}, OplusX9Ultra.lens("5").rows);
        assertArrayEquals(new int[]{22}, OplusX9Ultra.lens("1").rows);
        assertArrayEquals("every row at the full size on the report's tables", new int[]{13, 12, 5}, OplusX9Ultra.usableRows(OplusX9Ultra.lens("2"), CAMERA_2));
        assertArrayEquals(new int[]{25, 0}, OplusX9Ultra.usableRows(OplusX9Ultra.lens("5"), CAMERA_5));
        assertArrayEquals("a row of another size is left out", new int[]{5}, OplusX9Ultra.usableRows(OplusX9Ultra.lens("3"),
                new int[]{6, 3, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 8192, 6144, 15}));
    }

    /** The recipe per lens: sizes, format, usage, keys and their scopes, frames, the native block. */
    @Test public void theRecipeOfEachLens() {
        final String[] cameras = {"3", "2", "4", "5", "1"};
        final int[][] tables = {CAMERA_3, CAMERA_2, CAMERA_4, CAMERA_5, CAMERA_1};
        final int[] modes = {5, 13, 8, 25, 22}, widths = {8192, 8160, 8192, 8192, 8192}, heights = {6144, 6128, 6144, 6144, 6144};
        for (int i = 0; i < cameras.length; i++) {
            final FullResRecipe r = FullResRecipePresets.x9Ultra(cameras[i], tables[i], FullResRecipe.Preview.FAKE);
            assertNotNull(cameras[i], r);
            assertEquals(widths[i], r.width);
            assertEquals(heights[i], r.height);
            assertEquals("the photo stream RAW10", FullResRecipe.RAW10, r.format);
            assertEquals("CPU-read usage", 0x3L, r.usage);
            assertEquals(0L, r.useCase);
            assertEquals(FullResRecipe.Companion.OFF, r.companion);
            assertEquals(FullResRecipe.PixelMode.NEVER, r.pixelMode);
            assertEquals("up to 3 frames a merge", 3, r.frames);
            assertEquals(0, r.operationMode);
            assertEquals("the front's row is not the recipe's", "1".equals(cameras[i]), r.experimental);
            assertEquals(0, r.operationMode);
            assertEquals(OplusX9Ultra.PHOTO_OP_MODE, FullResRecipePresets.x9Ultra(cameras[i], tables[i], FullResRecipe.Preview.RAW,
                    OplusX9Ultra.operationMode(OplusX9Ultra.OFF_OWN_PHOTO)).operationMode);
            assertEquals(2, OplusX9Ultra.lens(cameras[i]).block);
            assertEquals(3, r.tags.size());
            final FullResRecipe.Tag pkg = r.tags.get(0), ext = r.tags.get(1), mode = r.tags.get(2);
            assertEquals("com.oplus.is.sdk.camera.package=u8:1@session", pkg.toString());
            assertEquals("com.oplus.is.from.extension=i32:1@session", ext.toString());
            assertEquals("com.oplus.engineercamera.agingtest.mode.select=i32:" + modes[i] + "@still", mode.toString());
            assertTrue("the edit page reads it back", r.signature().contains("recipeformat=raw10"));
        }
        assertNull("the logical camera", FullResRecipePresets.x9Ultra("0", CAMERA_2, FullResRecipe.Preview.FAKE));
        assertNull("a table with another row 13", FullResRecipePresets.x9Ultra("2", CAMERA_3, FullResRecipe.Preview.FAKE));
        // P709: a variant's row on the stills; a row not of the lens, or of another size, gives none
        assertEquals("com.oplus.engineercamera.agingtest.mode.select=i32:12@still",
                FullResRecipePresets.x9Ultra("2", CAMERA_2, FullResRecipe.Preview.BOTH, 0, 12).tags.get(2).toString());
        assertEquals("com.oplus.engineercamera.agingtest.mode.select=i32:0@still",
                FullResRecipePresets.x9Ultra("5", CAMERA_5, FullResRecipe.Preview.BOTH, 0, 0).tags.get(2).toString());
        assertNull("row 6 is not among the owner's working rows", FullResRecipePresets.x9Ultra("2", CAMERA_2, FullResRecipe.Preview.BOTH, 0, 6));
        assertNull(FullResRecipePresets.x9Ultra("3", new int[]{10, 3, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096,
                3072, 30, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30}, FullResRecipe.Preview.BOTH, 0, 9));
        // the lens page's preset is «Поток ISP» Auto's first variant: P709, the ISP stream on (owner: his rows work with ISP auto)
        final FullResRecipe preset = FullResRecipePresets.preset(new FullResRecipePresets.Device("OPPO", "OPPO", "OP627CL1", "PMA110"), "4", CAMERA_4);
        assertEquals(FullResRecipe.Preview.BOTH, preset.preview);
        assertEquals("OPPO Find X9 Ultra 50 MP (3x)", preset.id);
    }

    /** The session the engine builds: the binned RAW viewfinder, the photo RAW10 in the stills only, the keys in their scopes. */
    @Test public void theSessionOfTheRecipe() {
        // P709: the viewfinder's binned RAW in the capture's RAW10 (the camera lists both formats at 4080x3064)
        final int viewFormat = FullResRecipePlan.viewfinderRawFormat(FullResRecipe.RAW10, true, true);
        assertEquals(FullResRecipePlan.RAW10, viewFormat);
        final FullResRecipePlan.Env env = new FullResRecipePlan.Env(1440, 1080, 4080, 3064, viewFormat, Collections.<int[]>emptyList());
        final FullResRecipePlan fake = FullResRecipePlan.of(FullResRecipePresets.x9Ultra("2", CAMERA_2, FullResRecipe.Preview.FAKE), env);
        assertNotNull(fake);
        assertTrue(fake.ispStream());
        assertTrue("fake off", fake.ispFake());
        assertTrue(fake.rawViewfinder());
        assertEquals(Collections.singletonList(FullResRecipePlan.Stream.RAW_PREVIEW), fake.targets(false));
        assertEquals(Collections.singletonList(FullResRecipePlan.Stream.RAW_CAPTURE), fake.targets(true));
        final FullResRecipePlan.Output capture = fake.output(FullResRecipePlan.Stream.RAW_CAPTURE);
        assertEquals(8160, capture.width);
        assertEquals(6128, capture.height);
        assertEquals(FullResRecipePlan.RAW10, capture.format);
        assertEquals(FullResRecipePlan.RAW10, fake.captureFormat());
        assertEquals(0x3L, capture.usage);
        assertEquals("the viewfinder's RAW is the binned 4080x3064", 4080, fake.output(FullResRecipePlan.Stream.RAW_PREVIEW).width);
        assertEquals("P709: both RAW streams RAW10, as the recipe", FullResRecipePlan.RAW10, fake.output(FullResRecipePlan.Stream.RAW_PREVIEW).format);
        assertTrue(fake.describe("2"), fake.describe("2").contains("{raw_preview 4080x3064 fmt 0x25} {raw_capture 8160x6128 fmt 0x25 usage 0x3}"));
        assertEquals(2, fake.sessionTags().size());
        assertTrue("the mode never on the viewfinder's request", fake.requestTags(false).isEmpty());
        assertEquals(1, fake.requestTags(true).size());
        assertEquals(OppoNativeFullRes.MODE_KEY, fake.requestTags(true).get(0).key);
        assertTrue(fake.consistent(true));
        assertTrue(fake.consistent(false));
        assertTrue(fake.describe("2").contains("fake off"));
        final FullResRecipePlan off = FullResRecipePlan.of(FullResRecipePresets.x9Ultra("2", CAMERA_2, FullResRecipe.Preview.RAW), env);
        assertFalse("off: no ISP stream at all (the recipe as proven)", off.ispStream());
        assertFalse(off.ispFake());
        assertEquals(Arrays.asList(FullResRecipePlan.Stream.RAW_PREVIEW, FullResRecipePlan.Stream.RAW_CAPTURE), streams(off));
        final FullResRecipePlan on = FullResRecipePlan.of(FullResRecipePresets.x9Ultra("2", CAMERA_2, FullResRecipe.Preview.BOTH), env);
        assertEquals(Arrays.asList(FullResRecipePlan.Stream.VIEWFINDER, FullResRecipePlan.Stream.RAW_PREVIEW), on.targets(false));
        // fake off without an ISP stream to configure is the RAW viewfinder alone
        final FullResRecipePlan noIsp = FullResRecipePlan.of(FullResRecipePresets.x9Ultra("2", CAMERA_2, FullResRecipe.Preview.FAKE),
                new FullResRecipePlan.Env(0, 0, 4080, 3064, FullResRecipePlan.RAW_SENSOR, null));
        assertFalse(noIsp.ispStream());
        assertEquals(FullResRecipe.Preview.RAW, noIsp.preview);
    }

    private static List<FullResRecipePlan.Stream> streams(FullResRecipePlan plan) {
        final java.util.ArrayList<FullResRecipePlan.Stream> out = new java.util.ArrayList<>();
        for (FullResRecipePlan.Output o : plan.outputs) out.add(o.stream);
        return out;
    }

    /**
     * P709: the viewfinder RAW's format beside the capture RAW - the capture's own where listed (the X9 Ultra recipe's two RAW10
     * streams), RAW_SENSOR as before for a RAW_SENSOR capture.
     */
    @Test public void theViewfinderRawTakesTheCapturesFormat() {
        assertEquals(FullResRecipePlan.RAW10, FullResRecipePlan.viewfinderRawFormat(FullResRecipe.RAW10, true, true));
        assertEquals(FullResRecipePlan.RAW10, FullResRecipePlan.viewfinderRawFormat(FullResRecipe.RAW10, false, true));
        assertEquals("no RAW10 listed: RAW_SENSOR", FullResRecipePlan.RAW_SENSOR, FullResRecipePlan.viewfinderRawFormat(FullResRecipe.RAW10, true, false));
        assertEquals("a RAW_SENSOR capture as before", FullResRecipePlan.RAW_SENSOR, FullResRecipePlan.viewfinderRawFormat(FullResRecipe.RAW16, true, true));
        assertEquals(FullResRecipePlan.RAW10, FullResRecipePlan.viewfinderRawFormat(FullResRecipe.RAW16, false, true));
        assertEquals("none listed", 0, FullResRecipePlan.viewfinderRawFormat(FullResRecipe.RAW10, false, false));
    }

    /**
     * Owner, 2026-11-14: P689's fake off and off both died (his log); his rows work with «Поток ISP» Auto («Основа - mode 5/12/13;
     * Ширик - mode 5/9; 3х теле - mode 8/15/39; 10х теле - mode 0/25»). P709: Auto tries the ISP stream on with the app's package on
     * each of the lens's rows (the full-size one first), then on the full-size row the ISP stream on under the own package, fake
     * off, off (own), off (the app's), off (own) in the photo operation mode 0x8001 - all with two RAW10 streams; a set value runs
     * its own «Поток ISP».
     */
    @Test public void x9UltraAutoLadder() {
        final int[] main = {13, 12, 5};
        assertArrayEquals(new int[]{13021, 12021, 5021, 13011, 13013, 13012, 13022, 13112}, OplusX9Ultra.ladder(OplusX9Ultra.ISP_AUTO, true, main));
        assertArrayEquals("without a package spoof the app's package is the own one", new int[]{13011, 12011, 5011, 13013, 13012, 13112},
                OplusX9Ultra.ladder(OplusX9Ultra.ISP_AUTO, false, main));
        assertArrayEquals("the 10x: row 25 first, then row 0", new int[]{25021, 21, 25011, 25013, 25012, 25022, 25112},
                OplusX9Ultra.ladder(OplusX9Ultra.ISP_AUTO, true, new int[]{25, 0}));
        for (int set = 1; set <= 3; set++)
            assertArrayEquals("the user's " + set, new int[]{8000 + OplusX9Ultra.APP + set, 15000 + OplusX9Ultra.APP + set, 39000 + OplusX9Ultra.APP + set,
                    8000 + OplusX9Ultra.OWN + set, 8000 + OplusX9Ultra.PHOTO + OplusX9Ultra.OWN + set}, OplusX9Ultra.ladder(set, true, new int[]{8, 15, 39}));
        assertArrayEquals(new int[]{5012, 9012, 5112}, OplusX9Ultra.ladder(OplusX9Ultra.ISP_OFF, false, new int[]{5, 9}));
        assertEquals("no rows: no variant", 0, OplusX9Ultra.ladder(OplusX9Ultra.ISP_AUTO, true, new int[0]).length);
        // the variants' parts
        final int first = OplusX9Ultra.id(13, OplusX9Ultra.ON_APP);
        assertEquals(13021, first);
        assertEquals(13, OplusX9Ultra.row(first));
        assertEquals(OplusX9Ultra.ISP_ON, OplusX9Ultra.isp(first));
        assertFalse(OplusX9Ultra.ownPackage(first));
        assertEquals(0, OplusX9Ultra.operationMode(first));
        assertEquals(FullResRecipe.Preview.BOTH, OplusX9Ultra.preview(first));
        assertEquals("mode 13, ISP stream on, RAW10, package spoofed", OplusX9Ultra.variantName(first));
        final int photo = OplusX9Ultra.id(25, OplusX9Ultra.OFF_OWN_PHOTO);
        assertEquals(25112, photo);
        assertEquals(25, OplusX9Ultra.row(photo));
        assertTrue(OplusX9Ultra.ownPackage(photo));
        assertEquals(OplusX9Ultra.ISP_OFF, OplusX9Ultra.isp(photo));
        assertEquals(0x8001, OplusX9Ultra.operationMode(photo));
        assertEquals(FullResRecipe.Preview.RAW, OplusX9Ultra.preview(photo));
        assertEquals("mode 25, ISP stream off, RAW10, no package spoof, session mode 0x8001 (photo)", OplusX9Ultra.variantName(photo));
        final int row0 = OplusX9Ultra.id(0, OplusX9Ultra.FAKE_OWN);
        assertEquals(0, OplusX9Ultra.row(row0));
        assertEquals(FullResRecipe.Preview.FAKE, OplusX9Ultra.preview(row0));
        assertEquals("mode 0, ISP stream fake off, RAW10, no package spoof", OplusX9Ultra.variantName(row0));
        assertEquals("P689's variant in an older trial", "ISP stream off", OplusX9Ultra.variantName(OplusX9Ultra.ISP_OFF));
        // the order of the trials
        final int[] auto = OplusX9Ultra.ladder(OplusX9Ultra.ISP_AUTO, true, main);
        final java.util.Set<Integer> failed = new java.util.HashSet<>();
        for (int v : auto) {
            assertEquals(v, OplusX9Ultra.variant(auto, failed));
            failed.add(v);
        }
        assertEquals(-1, OplusX9Ultra.variant(auto, failed));
        assertEquals("P689's failures (r1 ids) name none of them", 13021,
                OplusX9Ultra.variant(auto, new java.util.HashSet<>(Arrays.asList(OplusX9Ultra.ISP_FAKE, OplusX9Ultra.ISP_OFF))));
        assertEquals("P709: the revision of the trials moved past P689's", 2, OplusX9Ultra.TRIALS);
    }

    /**
     * P709: a variant the camera died in (the provider restarts, the devices are gone for seconds) turns 50 MP off - the normal
     * session reopens and the owner's next «50 Мп» runs the next variant, never a chain of dying sessions; a camera alive goes on
     * at once while a variant is left.
     */
    @Test public void aDeadVariantTurnsFiftyOff() {
        final int a = OplusX9Ultra.id(5, OplusX9Ultra.ON_OWN), b = OplusX9Ultra.id(9, OplusX9Ultra.ON_OWN);
        assertFalse(OplusX9Ultra.fiftyStaysOn(FullResTrials.DIED, b));
        assertFalse("none left", OplusX9Ultra.fiftyStaysOn(FullResTrials.NO_FRAME, -1));
        assertTrue(OplusX9Ultra.fiftyStaysOn(FullResTrials.NO_FRAME, b));
        assertTrue(OplusX9Ultra.fiftyStaysOn(FullResTrials.REFUSED, b));
        assertEquals(FullResTrials.DIED, FullResTrials.code("camera error 4"));
        final String died = OplusX9Ultra.message(a, b, FullResTrials.phrase(FullResTrials.DIED, false), false);
        assertTrue(died, died.contains("50 MP is off; choose 50 MP again to try “mode 9, ISP stream on, RAW10, no package spoof”"));
        final String alive = OplusX9Ultra.message(a, b, FullResTrials.phrase(FullResTrials.NO_FRAME, false), true);
        assertTrue(alive, alive.contains("(no frame); trying “mode 9, ISP stream on, RAW10, no package spoof”"));
        final String none = OplusX9Ultra.message(b, -1, FullResTrials.phrase(FullResTrials.DIED, false), false);
        assertTrue(none, none.startsWith("FULLRES 50 MP is not available on this camera (the camera failed): every variant was tried."));
    }

    /** The variants' memory: per camera and firmware, under the camera's OPPO trial keys («Попробовать снова» clears it). */
    @Test public void theTrialsArePerCameraAndFirmware() {
        final String a = FullResTrials.stamp(OplusX9Ultra.TRIALS, "OPPO/CPH2841/OP627CL1:16/BP2A.250605.015/A:user/release-keys"),
                b = FullResTrials.stamp(OplusX9Ultra.TRIALS, "OPPO/CPH2841/OP627CL1:16/BP2A.250605.015/B:user/release-keys");
        assertTrue(OplusX9Ultra.failed(prefs, "2", a).isEmpty());
        final int fake = OplusX9Ultra.id(13, OplusX9Ultra.FAKE_OWN), off = OplusX9Ultra.id(13, OplusX9Ultra.OFF_OWN);
        OplusX9Ultra.noteFailed(prefs, "2", fake, "no preview frame for 3000 ms", a);
        final Map<Integer, String> failed = OplusX9Ultra.failed(prefs, "2", a);
        assertEquals(Collections.singleton(fake), failed.keySet());
        assertEquals(FullResTrials.NO_FRAME, failed.get(fake));
        assertTrue("another camera keeps its own", OplusX9Ultra.failed(prefs, "4", a).isEmpty());
        assertTrue("a new firmware tries them again", OplusX9Ultra.failed(prefs, "2", b).isEmpty());
        OplusX9Ultra.noteWorked(prefs, "2", off, a);
        assertEquals(off, OplusX9Ultra.worked(prefs, "2", a));
        assertEquals(-1, OplusX9Ultra.worked(prefs, "2", b));
        final String status = OplusX9Ultra.status(prefs, "2", a);
        assertTrue(status, status.startsWith("FULLRES 50 MP: mode 13, ISP stream fake off, RAW10, no package spoof — no frame."));
        // P709: P689's failures (route revision 1) do not count under revision 2 on the same firmware
        final String fw = "OPPO/CPH2841/OP627CL1:17/CP2A.260605.016/B:user/release-keys";
        OplusX9Ultra.noteFailed(prefs, "3", OplusX9Ultra.ISP_FAKE, "camera error 4", FullResTrials.stamp(1, fw));
        OplusX9Ultra.noteFailed(prefs, "3", OplusX9Ultra.ISP_OFF, "camera error 4", FullResTrials.stamp(1, fw));
        assertEquals(2, OplusX9Ultra.failed(prefs, "3", FullResTrials.stamp(1, fw)).size());
        assertTrue(OplusX9Ultra.failed(prefs, "3", FullResTrials.stamp(OplusX9Ultra.TRIALS, fw)).isEmpty());
        FullResTrials.clear(prefs, OppoNativeFullRes.base("3"));
        assertTrue("the main's 200 MP is explained there too", status.contains("200 MP without root is not available"));
        assertNull("no failed variant: nothing to say", OplusX9Ultra.status(prefs, "3", a));
        assertTrue("under the camera's OPPO trial keys", OplusX9Ultra.base("2").startsWith(OppoNativeFullRes.base("2") + "_"));
        assertTrue(FullResTrials.clear(prefs, OppoNativeFullRes.base("2")) > 0);
        assertTrue(OplusX9Ultra.failed(prefs, "2", a).isEmpty());
    }

    /** On the phone: the native route offers nothing, «50 Мп» is the recipe's while a variant is left, 200 MP never. */
    @Test public void onThePhoneFiftyIsTheRecipesAndTwoHundredIsOff() {
        x9Ultra();
        assertTrue(OplusX9Ultra.on());
        final String line = OplusX9Ultra.learn("2", true, true, CAMERA_2);
        assertTrue(line, line.contains("rows [13, 12, 5] (first: 13) 8160x6128, Quad 2x2"));
        assertTrue(line, line.contains("200 MP 16320x12256 not offered"));
        assertTrue(OplusX9Ultra.learn("4", true, true, CAMERA_4).contains("200 MP 16384x12288 not offered"));
        assertFalse(OplusX9Ultra.learn("3", true, true, CAMERA_3).contains("200 MP"));
        assertTrue(OplusX9Ultra.learn("0", true, true, CAMERA_2).contains("no FULLRES"));
        assertTrue(OplusX9Ultra.offered("2"));
        assertTrue(OplusX9Ultra.offered("3"));
        assertFalse(OplusX9Ultra.offered("0"));
        assertFalse(OppoNativeFullRes.twoSizes("2"));
        // P709: every Auto variant fails in turn (no package spoof in the test: the own-package ones), the main's rows 13, 12, 5 with
        // the ISP stream on first: «50 Мп» dimmed at the end, with its reasons
        final int[] order = {13011, 12011, 5011, 13013, 13012, 13112};
        for (int k = 0; k < order.length; k++) {
            assertTrue(OplusX9Ultra.offered("2"));
            assertEquals(order[k], OplusX9Ultra.current("2", OplusX9Ultra.ISP_AUTO));
            OplusX9Ultra.noteFailed(prefs, "2", order[k], k % 2 == 0 ? "camera error 4" : "no preview frame for 3000 ms", OplusX9Ultra.stamp());
        }
        assertFalse(OplusX9Ultra.offered("2"));
        assertTrue(OplusX9Ultra.exhausted("2"));
        assertFalse(OplusX9Ultra.exhausted("3"));
        assertEquals("the user's fake off: its own row-12 variant is left", 12013, OplusX9Ultra.current("2", OplusX9Ultra.ISP_FAKE));
        final String status = OplusX9Ultra.status(prefs, "2", OplusX9Ultra.stamp());
        assertTrue(status, status.contains("mode 13, ISP stream on, RAW10, no package spoof — the camera failed"));
        // «Попробовать снова» (FullResTrials.clear of the camera's OPPO keys): the first variant again
        assertTrue(FullResTrials.clear(prefs, OppoNativeFullRes.base("2")) > 0);
        assertTrue(OplusX9Ultra.offered("2"));
        assertEquals(13011, OplusX9Ultra.current("2", OplusX9Ultra.ISP_AUTO));
    }

    /** P709: on the phone a variant the camera died in turns «50 Мп» off; a variant without a frame leaves it for the next one. */
    @Test public void onThePhoneADeadVariantTurnsTheSizeOff() {
        x9Ultra();
        app.when(ScameraApp::getAppContext).thenReturn(RuntimeEnvironment.getApplication());
        OplusX9Ultra.learn("3", true, true, CAMERA_3);
        PixelPro10.setFullRes(true);
        assertTrue(PixelPro10.fullResChosen());
        assertEquals(5011, OplusX9Ultra.current("3", OplusX9Ultra.ISP_AUTO));
        final String noFrame = OplusX9Ultra.failed("3", 5011, "no preview frame for 3000 ms");
        assertTrue(noFrame, noFrame.contains("trying “mode 9, ISP stream on, RAW10, no package spoof”"));
        assertTrue("alive: the next variant at once", PixelPro10.fullResChosen());
        final String died = OplusX9Ultra.failed("3", 9011, "camera error 4");
        assertTrue(died, died.contains("50 MP is off; choose 50 MP again to try “mode 5, ISP stream fake off, RAW10, no package spoof”"));
        assertFalse("died: the normal session reopens", PixelPro10.fullResChosen());
        assertTrue(OplusX9Ultra.offered("3"));
        PixelPro10.setFullRes(true);
        for (int v : new int[]{5013, 5012}) OplusX9Ultra.failed("3", v, "no preview frame for 3000 ms");
        assertTrue(PixelPro10.fullResChosen());
        final String last = OplusX9Ultra.failed("3", 5112, "no preview frame for 3000 ms");
        assertTrue(last, last.contains("every variant was tried"));
        assertFalse("none left: off", PixelPro10.fullResChosen());
        assertTrue(OplusX9Ultra.exhausted("3"));
    }

    @Test public void notOnAnotherPhone() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        assertFalse(OplusX9Ultra.on());
        OplusX9Ultra.learn("2", true, true, CAMERA_2);
        assertFalse(OplusX9Ultra.offered("2"));
        assertFalse(OplusX9Ultra.exhausted("2"));
    }

    /** The engine's new fields: the capture format (raw16 as before, raw10) and the «fake» viewfinder, as text and back. */
    @Test public void theFormatAndFakeFieldsRoundTrip() {
        final FullResRecipe r = FullResRecipePresets.x9Ultra("5", CAMERA_5, FullResRecipe.Preview.FAKE);
        final Map<String, String> f = r.fields();
        assertEquals(FullResRecipe.FIELDS, new java.util.ArrayList<>(f.keySet()));
        assertEquals("raw10", f.get(FullResRecipe.FORMAT));
        assertEquals("0x3", f.get(FullResRecipe.USAGE));
        assertEquals("fake", f.get(FullResRecipe.PREVIEW));
        assertEquals("no", f.get(FullResRecipe.VIEW_TAGS));
        assertEquals(r.signature(), new FullResRecipe.Builder().build().overlay(f, null).signature());
        assertTrue(FullResRecipe.valid(FullResRecipe.FORMAT, "RAW10"));
        assertTrue(FullResRecipe.valid(FullResRecipe.FORMAT, "raw16"));
        assertFalse(FullResRecipe.valid(FullResRecipe.FORMAT, "raw12"));
        assertTrue(FullResRecipe.valid(FullResRecipe.PREVIEW, "fake"));
        // RAW_SENSOR (every profile before the field) keeps its signature: stored proofs and failures stay valid
        final FullResRecipe sensor = r.edit().format(FullResRecipe.RAW16).build();
        assertFalse(sensor.signature().contains(FullResRecipe.FORMAT));
        assertEquals("raw16", sensor.fields().get(FullResRecipe.FORMAT));
        assertEquals(FullResRecipePlan.RAW_SENSOR, FullResRecipePlan.of(sensor, new FullResRecipePlan.Env(1440, 1080, 4096, 3072,
                FullResRecipePlan.RAW_SENSOR, null)).captureFormat());
        // a «viewfinder» companion cannot ride on a fake-off ISP stream (no request may target it)
        assertTrue(r.edit().companion(FullResRecipe.Companion.VIEWFINDER, 0, 0).build().blockers(35).contains("companion:viewfinder"));
        assertTrue(r.blockers(35).isEmpty());
    }
}
