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
        // the recipe's rows are full-size QBC rows of each table
        assertArrayEquals(new int[]{8160, 6128, 30}, SensorModeBand.modeEntry(CAMERA_2, 13));
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
        // the lens page's preset is «Поток ISP» Auto's first variant
        final FullResRecipe preset = FullResRecipePresets.preset(new FullResRecipePresets.Device("OPPO", "OPPO", "OP627CL1", "PMA110"), "4", CAMERA_4);
        assertEquals(FullResRecipe.Preview.FAKE, preset.preview);
        assertEquals("OPPO Find X9 Ultra 50 MP (3x)", preset.id);
    }

    /** The session the engine builds: the binned RAW viewfinder, the photo RAW10 in the stills only, the keys in their scopes. */
    @Test public void theSessionOfTheRecipe() {
        final FullResRecipePlan.Env env = new FullResRecipePlan.Env(1440, 1080, 4080, 3064, FullResRecipePlan.RAW_SENSOR, Collections.<int[]>emptyList());
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
     * Owner, 2026-11-14 («Для рабочего фокуса на 9 ультра наверняка нужен либо ISP off, либо ISP FAKE OFF»): «Поток ISP» Auto
     * tries fake off first, off after it failed; a set value is the user's alone.
     */
    @Test public void x9UltraAutoIsFakeOffThenOff() {
        assertArrayEquals(new int[]{OplusX9Ultra.ISP_FAKE, OplusX9Ultra.ISP_OFF}, OplusX9Ultra.ladder(OplusX9Ultra.ISP_AUTO));
        for (int set = 1; set <= 3; set++) assertArrayEquals("the user's " + set, new int[]{set}, OplusX9Ultra.ladder(set));
        assertEquals(FullResRecipe.Preview.FAKE, OplusX9Ultra.preview(OplusX9Ultra.ISP_FAKE));
        assertEquals(FullResRecipe.Preview.RAW, OplusX9Ultra.preview(OplusX9Ultra.ISP_OFF));
        assertEquals(FullResRecipe.Preview.BOTH, OplusX9Ultra.preview(OplusX9Ultra.ISP_ON));
        final int[] auto = OplusX9Ultra.ladder(OplusX9Ultra.ISP_AUTO);
        assertEquals(OplusX9Ultra.ISP_FAKE, OplusX9Ultra.variant(auto, Collections.<Integer>emptySet()));
        assertEquals(OplusX9Ultra.ISP_OFF, OplusX9Ultra.variant(auto, Collections.singleton(OplusX9Ultra.ISP_FAKE)));
        assertEquals(-1, OplusX9Ultra.variant(auto, new java.util.HashSet<>(Arrays.asList(OplusX9Ultra.ISP_FAKE, OplusX9Ultra.ISP_OFF))));
        assertEquals("the user's fake off failed: off is not taken in its place", -1,
                OplusX9Ultra.variant(OplusX9Ultra.ladder(OplusX9Ultra.ISP_FAKE), Collections.singleton(OplusX9Ultra.ISP_FAKE)));
    }

    /** The variants' memory: per camera and firmware, under the camera's OPPO trial keys («Попробовать снова» clears it). */
    @Test public void theTrialsArePerCameraAndFirmware() {
        final String a = FullResTrials.stamp(OplusX9Ultra.TRIALS, "OPPO/CPH2841/OP627CL1:16/BP2A.250605.015/A:user/release-keys"),
                b = FullResTrials.stamp(OplusX9Ultra.TRIALS, "OPPO/CPH2841/OP627CL1:16/BP2A.250605.015/B:user/release-keys");
        assertTrue(OplusX9Ultra.failed(prefs, "2", a).isEmpty());
        OplusX9Ultra.noteFailed(prefs, "2", OplusX9Ultra.ISP_FAKE, "no preview frame for 3000 ms", a);
        final Map<Integer, String> failed = OplusX9Ultra.failed(prefs, "2", a);
        assertEquals(Collections.singleton(OplusX9Ultra.ISP_FAKE), failed.keySet());
        assertEquals(FullResTrials.NO_FRAME, failed.get(OplusX9Ultra.ISP_FAKE));
        assertTrue("another camera keeps its own", OplusX9Ultra.failed(prefs, "4", a).isEmpty());
        assertTrue("a new firmware tries them again", OplusX9Ultra.failed(prefs, "2", b).isEmpty());
        OplusX9Ultra.noteWorked(prefs, "2", OplusX9Ultra.ISP_OFF, a);
        assertEquals(OplusX9Ultra.ISP_OFF, OplusX9Ultra.worked(prefs, "2", a));
        assertEquals(-1, OplusX9Ultra.worked(prefs, "2", b));
        final String status = OplusX9Ultra.status(prefs, "2", a);
        assertTrue(status, status.startsWith("FULLRES 50 MP: ISP stream fake off — no frame."));
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
        assertTrue(line, line.contains("row 13 8160x6128, Quad 2x2"));
        assertTrue(line, line.contains("200 MP 16320x12256 not offered"));
        assertTrue(OplusX9Ultra.learn("4", true, true, CAMERA_4).contains("200 MP 16384x12288 not offered"));
        assertFalse(OplusX9Ultra.learn("3", true, true, CAMERA_3).contains("200 MP"));
        assertTrue(OplusX9Ultra.learn("0", true, true, CAMERA_2).contains("no FULLRES"));
        assertTrue(OplusX9Ultra.offered("2"));
        assertTrue(OplusX9Ultra.offered("3"));
        assertFalse(OplusX9Ultra.offered("0"));
        assertFalse(OppoNativeFullRes.twoSizes("2"));
        // both Auto variants fail: «50 Мп» dimmed, with its reasons
        OplusX9Ultra.noteFailed(prefs, "2", OplusX9Ultra.ISP_FAKE, "no preview frame for 3000 ms", OplusX9Ultra.stamp());
        assertTrue(OplusX9Ultra.offered("2"));
        assertEquals(OplusX9Ultra.ISP_OFF, OplusX9Ultra.current("2", OplusX9Ultra.ISP_AUTO));
        OplusX9Ultra.noteFailed(prefs, "2", OplusX9Ultra.ISP_OFF, "camera error 4", OplusX9Ultra.stamp());
        assertFalse(OplusX9Ultra.offered("2"));
        assertTrue(OplusX9Ultra.exhausted("2"));
        assertFalse(OplusX9Ultra.exhausted("3"));
        assertEquals("the user's On is a variant of its own", OplusX9Ultra.ISP_ON, OplusX9Ultra.current("2", OplusX9Ultra.ISP_ON));
        assertTrue(OplusX9Ultra.status(prefs, "2", OplusX9Ultra.stamp()).contains("the camera failed"));
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
