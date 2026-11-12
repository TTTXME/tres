package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CaptureRequest;

import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P635: the full-resolution recipe's profile - its fields and their text form, the built-in presets (the Xiaomi 17 Ultra's as
 * the owner's documents have it, the experimental nubia and OnePlus 16 ones), the checks, the tags on a request builder.
 */
public class FullResRecipeTest {
    static final FullResRecipePresets.Device XIAOMI_17U = new FullResRecipePresets.Device("Xiaomi", "Xiaomi", "nezha", "2512BPNDAG");
    static final FullResRecipePresets.Device NUBIA = new FullResRecipePresets.Device("nubia", "nubia", "NX721J", "NX721J");
    static final FullResRecipePresets.Device ONEPLUS_16 = new FullResRecipePresets.Device("OnePlus", "OnePlus", "PYB110", "PYB110");
    static final FullResRecipePresets.Device OTHER = new FullResRecipePresets.Device("vivo", "vivo", "PD2454", "V2454A");
    /** A binning sensor's table: 8192x6144 beside 4096x3072 rows. */
    static final int[] TABLE = {4, 3, 8192, 6144, 30, 4096, 3072, 30, 4096, 2304, 60, 2048, 1536, 120};

    static FullResRecipe xiaomi() {
        return FullResRecipePresets.preset(XIAOMI_17U, "2", XiaomiSensorRowTest.MAIN);
    }

    @Test public void theXiaomi17UltraPresetIsTheDocumentsRecipe() {
        final FullResRecipe r = xiaomi();
        assertNotNull(r);
        assertEquals(8192, r.width);
        assertEquals(6144, r.height);
        assertEquals("usage 0x100003", 0x100003L, r.usage);
        assertEquals("setStreamUseCase(524547)", 524547L, r.useCase);
        assertEquals(FullResRecipe.Companion.YUV, r.companion);
        assertEquals(640, r.companionWidth);
        assertEquals(480, r.companionHeight);
        assertEquals("still", FullResRecipe.templateName(r.stillTemplate));
        assertEquals("TEMPLATE_STILL_CAPTURE", 2, r.stillTemplate);
        assertEquals("still", FullResRecipe.templateName(r.sessionTemplate));
        assertEquals(30, r.fpsLow);
        assertEquals(30, r.fpsHigh);
        assertEquals("no SENSOR_PIXEL_MODE, no addSensorPixelModeUsed", FullResRecipe.PixelMode.NEVER, r.pixelMode);
        assertEquals("the documents' dual-stream run: the binned RAW viewfinder", FullResRecipe.Preview.RAW, r.preview);
        assertEquals(0, r.operationMode);
        assertFalse(r.experimental);
        assertEquals(2, r.tags.size());
        final FullResRecipe.Tag mode = r.tags.get(0), remosaic = r.tags.get(1);
        assertEquals("org.codeaurora.qcamera3.sensor_meta_data.current_mode", mode.key);
        assertEquals(FullResRecipe.TagType.I32_ARRAY, mode.type);
        assertArrayEquals(new double[]{0}, mode.values, 0);
        assertEquals(FullResRecipe.Target.BOTH, mode.target);
        assertEquals("xiaomi.remosaic.enabled", remosaic.key);
        assertEquals(FullResRecipe.TagType.U8_ARRAY, remosaic.type);
        assertArrayEquals(new double[]{1}, remosaic.values, 0);
        assertEquals(FullResRecipe.Target.BOTH, remosaic.target);
        assertTrue(r.blockers(36).isEmpty());
        assertEquals("the stream use case needs API 33", "usecase:33", r.blockers(32).get(0));
        // the same phone under its other model code; not its logical camera
        assertNotNull(FullResRecipePresets.preset(new FullResRecipePresets.Device("Xiaomi", "Xiaomi", "nezha", "25128PNA1C"), "2", null));
        assertNull(FullResRecipePresets.preset(XIAOMI_17U, "0", null));
        assertNull(FullResRecipePresets.preset(new FullResRecipePresets.Device("Xiaomi", "Xiaomi", "xuanyuan", "25010PN30G"), "2", TABLE));
    }

    /** P635d: the phone's own stream table gives the main camera's recipe to the ultra wide and the 75 mm; trials until run. */
    @Test public void theXiaomiUltraWideAndTeleTrials() {
        final FullResRecipe main = xiaomi(), wide = FullResRecipePresets.preset(XIAOMI_17U, "3", null),
                tele = FullResRecipePresets.preset(XIAOMI_17U, "4", null);
        assertNotNull(wide);
        assertNotNull(tele);
        assertFalse("the main camera's is proven", main.experimental);
        for (FullResRecipe r : new FullResRecipe[]{wide, tele}) {
            assertTrue("not run on a phone", r.experimental);
            assertEquals(6144, r.height);
            assertEquals(main.usage, r.usage);
            assertEquals(0x80103L, r.useCase);
            assertEquals(main.companion, r.companion);
            assertEquals(640, r.companionWidth);
            assertEquals(main.stillTemplate, r.stillTemplate);
            assertEquals(main.sessionTemplate, r.sessionTemplate);
            assertEquals(FullResRecipe.PixelMode.NEVER, r.pixelMode);
            assertEquals(FullResRecipe.Preview.RAW, r.preview);
            assertEquals("the full-size rows run at 12 and 24 fps: no rate asked", 0, r.fpsLow);
            assertEquals(2, r.tags.size());
            assertEquals("xiaomi.remosaic.enabled", r.tags.get(1).key);
            assertArrayEquals(new double[]{1}, r.tags.get(1).values, 0);
            assertNotEquals(main.signature(), r.signature());
        }
        assertEquals(8192, wide.width);
        assertArrayEquals("the ultra wide's only full-size row", new double[]{0}, wide.tags.get(0).values, 0);
        assertEquals("the 75 mm's 50 MP frame is 8160 wide", 8160, tele.width);
        assertArrayEquals("the vendor's 50 MP row of the 75 mm", new double[]{2}, tele.tags.get(0).values, 0);
        // a trial never replaces a picked row (FullResRecipeStore.autoRoute)
        assertFalse(FullResRecipeStore.autoRoute(0, new int[]{2, 3, 8192, 6144, 12, 4096, 3072, 30}, wide));
        assertFalse(FullResRecipeStore.autoRoute(1, new int[]{2, 3, 16320, 12288, 7, 8160, 6144, 24}, tele));
    }

    @Test public void theExperimentalPresets() {
        final FullResRecipe n = FullResRecipePresets.preset(NUBIA, "2", TABLE);
        assertNotNull(n);
        assertTrue(n.experimental);
        assertEquals("the table's full-size row", 8192, n.width);
        assertEquals(6144, n.height);
        assertEquals(FullResRecipe.PixelMode.ANNOTATE, n.pixelMode);
        assertEquals(0x8012, n.operationMode);
        assertEquals(FullResRecipe.Preview.ISP, n.preview);
        assertEquals("a companion of the camera's own list", FullResRecipe.Companion.YUV, n.companion);
        assertEquals(0, n.companionWidth);
        assertEquals(3, n.tags.size());
        assertEquals(FullResRecipe.Target.SESSION, n.tags.get(0).target);
        assertEquals(FullResRecipe.Target.REQUEST, n.tags.get(1).target);
        assertEquals("CustomRemosiac on the stills only", FullResRecipe.Target.STILL, n.tags.get(2).target);
        assertNull("a camera without a full-size row has none", FullResRecipePresets.preset(NUBIA, "1", new int[]{1, 3, 4096, 3072, 30}));
        assertNull(FullResRecipePresets.preset(NUBIA, "2", null));
        assertEquals("pixelmode:31", n.blockers(30).get(0));

        final FullResRecipe o = FullResRecipePresets.preset(ONEPLUS_16, "2", null);
        assertNotNull(o);
        assertTrue(o.experimental);
        assertEquals(8192, o.width);
        assertEquals("com.oplus.engineercamera.agingtest.mode.select", o.tags.get(0).key);
        assertEquals(FullResRecipe.TagType.U8, o.tags.get(0).type);
        assertArrayEquals(new double[]{25}, o.tags.get(0).values, 0);
        assertEquals(FullResRecipe.Preview.ISP, o.preview);
        assertEquals(FullResRecipe.PixelMode.NEVER, o.pixelMode);
        assertNull("the tele's 50 MP runs natively", FullResRecipePresets.preset(ONEPLUS_16, "4", null));

        assertNull("no preset elsewhere", FullResRecipePresets.preset(OTHER, "2", TABLE));
        assertNull(FullResRecipePresets.preset(null, "2", TABLE));
    }

    @Test public void theEngineStartsWithNothingOfAPhone() {
        final FullResRecipe empty = new FullResRecipe.Builder().build();
        assertEquals(0, empty.width);
        assertEquals(0, empty.useCase);
        assertEquals(0, empty.usage);
        assertTrue(empty.tags.isEmpty());
        assertEquals(FullResRecipe.Companion.OFF, empty.companion);
        assertEquals("size", empty.blockers(36).get(0));
        // the engine's sources hold none of the documents' numbers
        for (String name : new String[]{"FullResRecipePlan", "FullResRecipe", "FullResRecipeTags", "FullResRecipeSession", "FullResRecipeShot", "FullResTruth"}) {
            final String src = source(name);
            for (String constant : new String[]{"524547", "0x80103", "0x100003", "1048579", "remosaic.enabled", "current_mode"})
                assertFalse(name + " holds " + constant, code(src).contains(constant));
        }
    }

    static String source(String name) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "src/main/java/com/particlesdevs/photoncamera/capture/" + name + ".java")), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A source without its comments (their examples may quote a number). */
    static String code(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test public void theFieldsRoundTrip() {
        final FullResRecipe r = xiaomi();
        final Map<String, String> f = r.fields();
        assertEquals(FullResRecipe.FIELDS, new ArrayList<>(f.keySet()));
        assertEquals("8192x6144", f.get(FullResRecipe.SIZE));
        assertEquals("0x100003", f.get(FullResRecipe.USAGE));
        assertEquals("0x80103", f.get(FullResRecipe.USE_CASE));
        assertEquals("640x480", f.get(FullResRecipe.COMPANION));
        assertEquals("org.codeaurora.qcamera3.sensor_meta_data.current_mode=i32[]:0@both\nxiaomi.remosaic.enabled=u8[]:1@both", f.get(FullResRecipe.TAGS));
        assertEquals("yes", f.get(FullResRecipe.VIEW_TAGS));
        assertEquals("still", f.get(FullResRecipe.TEMPLATE));
        assertEquals("30-30", f.get(FullResRecipe.FPS));
        assertEquals("never", f.get(FullResRecipe.PIXEL_MODE));
        assertEquals("raw", f.get(FullResRecipe.PREVIEW));
        assertEquals("4", f.get(FullResRecipe.FRAMES));
        assertEquals("0", f.get(FullResRecipe.OP_MODE));
        // every field's own text over an empty profile gives the same profile
        final FullResRecipe again = new FullResRecipe.Builder().build().overlay(f, null);
        assertEquals(r.signature(), again.signature());
        assertSame("no edit: the preset itself", r, r.overlay(new HashMap<>(), null));
        for (String field : FullResRecipe.FIELDS) assertFalse("no '_' in a field name (SessionKeys splits there): " + field, field.contains("_"));
    }

    @Test public void editsLieOverThePreset() {
        final Map<String, String> edits = new HashMap<>();
        edits.put(FullResRecipe.FRAMES, "1");
        edits.put(FullResRecipe.PREVIEW, "isp");
        edits.put(FullResRecipe.COMPANION, "viewfinder");
        edits.put(FullResRecipe.USE_CASE, "524549");
        final List<String> problems = new ArrayList<>();
        final FullResRecipe r = xiaomi().overlay(edits, problems);
        assertTrue(problems.toString(), problems.isEmpty());
        assertEquals(1, r.frames);
        assertEquals(FullResRecipe.Preview.ISP, r.preview);
        assertEquals(FullResRecipe.Companion.VIEWFINDER, r.companion);
        assertEquals(524549L, r.useCase);
        assertEquals("the fields left alone are the preset's", 0x100003L, r.usage);
        assertEquals(2, r.tags.size());
        assertTrue("an edited preset is no proven one", r.experimental);
        assertTrue(r.id.endsWith("(edited)"));
        assertNotEquals(xiaomi().signature(), r.signature());
        // a text that does not parse keeps the preset's field and is named
        edits.clear();
        edits.put(FullResRecipe.SIZE, "big");
        edits.put(FullResRecipe.TAGS, "no type here");
        edits.put(FullResRecipe.FRAMES, "99");
        final FullResRecipe kept = xiaomi().overlay(edits, problems);
        assertEquals(3, problems.size());
        assertEquals(8192, kept.width);
        assertEquals(2, kept.tags.size());
        assertEquals(4, kept.frames);
    }

    @Test public void fieldTextsThatAreAndAreNot() {
        assertTrue(FullResRecipe.valid(FullResRecipe.SIZE, "8160 x 6144"));
        assertTrue(FullResRecipe.valid(FullResRecipe.SIZE, "8192×6144"));
        assertFalse(FullResRecipe.valid(FullResRecipe.SIZE, "8192"));
        assertFalse(FullResRecipe.valid(FullResRecipe.SIZE, "0x0"));
        assertTrue(FullResRecipe.valid(FullResRecipe.USE_CASE, "0x80103"));
        assertTrue(FullResRecipe.valid(FullResRecipe.USE_CASE, "524547"));
        assertFalse(FullResRecipe.valid(FullResRecipe.USE_CASE, "-1"));
        for (String c : new String[]{"off", "viewfinder", "auto", "640x480", ""}) assertTrue(c, FullResRecipe.valid(FullResRecipe.COMPANION, c));
        assertFalse(FullResRecipe.valid(FullResRecipe.COMPANION, "yes"));
        assertTrue(FullResRecipe.valid(FullResRecipe.FPS, "30-30"));
        assertTrue(FullResRecipe.valid(FullResRecipe.FPS, "24"));
        assertTrue("empty: unchanged", FullResRecipe.valid(FullResRecipe.FPS, ""));
        assertFalse(FullResRecipe.valid(FullResRecipe.FPS, "30-15"));
        assertTrue(FullResRecipe.valid(FullResRecipe.TEMPLATE, "STILL"));
        assertFalse(FullResRecipe.valid(FullResRecipe.TEMPLATE, "photo"));
        assertTrue(FullResRecipe.valid(FullResRecipe.PIXEL_MODE, "annotate"));
        assertFalse(FullResRecipe.valid(FullResRecipe.PIXEL_MODE, "1"));
        assertTrue(FullResRecipe.valid(FullResRecipe.PREVIEW, "both"));
        assertTrue(FullResRecipe.valid(FullResRecipe.FRAMES, "8"));
        assertFalse(FullResRecipe.valid(FullResRecipe.FRAMES, "0"));
        assertTrue(FullResRecipe.valid(FullResRecipe.OP_MODE, "0x8012"));
        assertTrue("no tag at all", FullResRecipe.valid(FullResRecipe.TAGS, ""));
        assertTrue(FullResRecipe.valid(FullResRecipe.TAGS, "a.b=i32:5@session; c.d=u8[]:1,2,3@still\ne.f=f32:0.5"));
        assertFalse("two values for a scalar", FullResRecipe.valid(FullResRecipe.TAGS, "a.b=i32:1,2"));
        assertFalse(FullResRecipe.valid(FullResRecipe.TAGS, "a.b=i16:1"));
        assertFalse(FullResRecipe.valid(FullResRecipe.TAGS, "a.b=i32:1@everywhere"));
        assertFalse(FullResRecipe.valid("recipenothing", "1"));
        assertEquals(1, FullResRecipe.template("preview"));
        assertEquals(2, FullResRecipe.template("still"));
        assertEquals(5, FullResRecipe.template("zsl"));
        assertEquals(0, FullResRecipe.template("?"));
    }

    @Test public void aTagsTargets() {
        final FullResRecipe.Tag session = FullResRecipe.Tag.parse("a.b=i32:1@session"), request = FullResRecipe.Tag.parse("a.b=i32:1@request"),
                both = FullResRecipe.Tag.parse("a.b=i32:1"), still = FullResRecipe.Tag.parse("a.b=u8:1@still");
        assertTrue(session.onSession());
        assertFalse(session.onRequest(false));
        assertFalse(session.onRequest(true));
        assertFalse(request.onSession());
        assertTrue(request.onRequest(false));
        assertTrue(request.onRequest(true));
        assertEquals("no target: session and every request", FullResRecipe.Target.BOTH, both.target);
        assertTrue(both.onSession() && both.onRequest(false) && both.onRequest(true));
        assertFalse(still.onSession());
        assertFalse(still.onRequest(false));
        assertTrue(still.onRequest(true));
        assertEquals("a.b=u8:1@still", still.toString());
        assertEquals("x.y=f32:0.5@both", FullResRecipe.Tag.parse("x.y=f32:0.5").toString());
        assertEquals("x.y=i32[]:0,-2@both", FullResRecipe.Tag.parse("x.y = i32[] : 0, -2 @ both").toString());
    }

    @Test public void theChecks() {
        final Map<String, String> e = new HashMap<>();
        e.put(FullResRecipe.COMPANION, "viewfinder");
        assertEquals("a viewfinder companion without the ISP stream", Arrays.asList("companion:viewfinder"), xiaomi().overlay(e, null).blockers(36));
        e.put(FullResRecipe.PREVIEW, "both");
        assertTrue(xiaomi().overlay(e, null).blockers(36).isEmpty());
        e.put(FullResRecipe.PIXEL_MODE, "annotate");
        assertEquals(Arrays.asList("companion:pixelmode"), xiaomi().overlay(e, null).blockers(36));
        assertTrue(xiaomi().blockers(27).contains("session:28"));
    }

    @Test public void theTagsOnABuilderByTheirTypes() {
        final CaptureRequest.Builder b = Mockito.mock(CaptureRequest.Builder.class);
        final String log = FullResRecipeTags.apply(b, xiaomi().tags);
        assertTrue(log, log.contains("current_mode=i32[]:0@both") && log.contains("xiaomi.remosaic.enabled=u8[]:1@both"));
        Mockito.verify(b).set(ArgumentMatchers.any(), ArgumentMatchers.eq(new int[]{0}));
        Mockito.verify(b).set(ArgumentMatchers.any(), ArgumentMatchers.eq(new byte[]{1}));
        final CaptureRequest.Builder s = Mockito.mock(CaptureRequest.Builder.class);
        FullResRecipeTags.apply(s, Arrays.asList(FullResRecipe.Tag.parse("a.b=i32:32"), FullResRecipe.Tag.parse("c.d=u8:25"),
                FullResRecipe.Tag.parse("e.f=i64:7"), FullResRecipe.Tag.parse("g.h=f32:0.5")));
        Mockito.verify(s).set(ArgumentMatchers.any(), ArgumentMatchers.eq(Integer.valueOf(32)));
        Mockito.verify(s).set(ArgumentMatchers.any(), ArgumentMatchers.eq(Byte.valueOf((byte) 25)));
        Mockito.verify(s).set(ArgumentMatchers.any(), ArgumentMatchers.eq(Long.valueOf(7)));
        Mockito.verify(s).set(ArgumentMatchers.any(), ArgumentMatchers.eq(Float.valueOf(0.5f)));
        // a tag the builder refuses is named, the others are still set
        final CaptureRequest.Builder refusing = Mockito.mock(CaptureRequest.Builder.class);
        Mockito.doThrow(new IllegalArgumentException("no such key")).when(refusing).set(ArgumentMatchers.any(), ArgumentMatchers.eq(new int[]{0}));
        final String refused = FullResRecipeTags.apply(refusing, xiaomi().tags);
        assertTrue(refused, refused.contains("current_mode refused (no such key)") && refused.contains("xiaomi.remosaic.enabled=u8[]:1@both"));
        assertEquals("none", FullResRecipeTags.apply(b, new ArrayList<>()));
        // outside a recipe session a request gets nothing
        FullResRecipeSession.session(null);
        final CaptureRequest.Builder plain = Mockito.mock(CaptureRequest.Builder.class);
        assertEquals("", FullResRecipeSession.applyRequest(plain, true));
        Mockito.verifyNoInteractions(plain);
    }
}
