package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CaptureRequest;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P635: the session and the requests the engine composes of a profile - which output gets the vendor use case, which request
 * targets which stream, the tags of the session / the repeating request / a still, the templates, the pixel modes; the viewfinder
 * always has a stream of its own.
 */
public class FullResRecipePlanTest {
    /** The Xiaomi main camera: a 1440x1080 ISP viewfinder, its own 4096x3072 RAW, a few YUV sizes. */
    static final FullResRecipePlan.Env ENV = new FullResRecipePlan.Env(1440, 1080, 4096, 3072, FullResRecipePlan.RAW_SENSOR,
            Arrays.asList(new int[]{1920, 1080}, new int[]{640, 480}, new int[]{4096, 3072}, new int[]{320, 240}, new int[]{1280, 720}));

    @After public void noSession() {
        FullResRecipeSession.session(null);
    }

    private static FullResRecipe edited(FullResRecipe r, String... fieldValue) {
        final Map<String, String> e = new HashMap<>();
        for (int i = 0; i < fieldValue.length; i += 2) e.put(fieldValue[i], fieldValue[i + 1]);
        return r.overlay(e, null);
    }

    private static List<FullResRecipePlan.Stream> streams(FullResRecipePlan.Stream... s) {
        return Arrays.asList(s);
    }

    @Test public void theXiaomiSessionIsTheDocumentsDualStream() {
        final FullResRecipePlan plan = FullResRecipePlan.of(FullResRecipeTest.xiaomi(), ENV);
        assertNotNull(plan);
        assertEquals("preview RAW 4096x3072 + still RAW 8192x6144 + YUV 640x480", 3, plan.outputs.size());
        final FullResRecipePlan.Output view = plan.output(FullResRecipePlan.Stream.RAW_PREVIEW), raw = plan.output(FullResRecipePlan.Stream.RAW_CAPTURE),
                yuv = plan.output(FullResRecipePlan.Stream.COMPANION);
        assertEquals(4096, view.width);
        assertEquals(3072, view.height);
        assertEquals(8192, raw.width);
        assertEquals(6144, raw.height);
        assertEquals(FullResRecipePlan.RAW_SENSOR, raw.format);
        assertEquals(0x100003L, raw.usage);
        // the use case on the capture RAW output alone
        assertEquals(524547L, raw.useCase);
        assertEquals(0, view.useCase);
        assertEquals(0, yuv.useCase);
        assertEquals(0, view.usage);
        assertEquals(640, yuv.width);
        assertEquals(480, yuv.height);
        assertEquals(FullResRecipePlan.YUV_420_888, yuv.format);
        // no pixel mode anywhere
        for (FullResRecipePlan.Output o : plan.outputs) assertEquals(o.toString(), 0, o.pixelModes.length);
        assertNull(plan.pixelMode(true));
        assertNull(plan.pixelMode(false));
        // the viewfinder repeats on its own stream; a still is the capture RAW and the companion in one request
        assertEquals(streams(FullResRecipePlan.Stream.RAW_PREVIEW), plan.targets(false));
        assertEquals(streams(FullResRecipePlan.Stream.RAW_CAPTURE, FullResRecipePlan.Stream.COMPANION), plan.targets(true));
        assertTrue(plan.rawViewfinder());
        assertFalse(plan.ispStream());
        assertFalse(plan.viewfinderCompanion());
        // both tags on the session and on every request
        assertEquals(2, plan.sessionTags().size());
        assertEquals(2, plan.requestTags(false).size());
        assertEquals(2, plan.requestTags(true).size());
        assertEquals(2, plan.recipe.stillTemplate);
        assertTrue(plan.consistent(true) && plan.consistent(false));
        final String line = plan.describe("2");
        for (String part : new String[]{"FULLRES RECIPE camera 2 «Xiaomi 17 Ultra 50 MP»", "{raw_preview 4096x3072 fmt 0x20}",
                "{raw_capture 8192x6144 fmt 0x20 usage 0x100003 useCase 0x80103}", "{companion 640x480 fmt 0x23}",
                "repeating -> [raw_preview]", "stills x4 template still -> [raw_capture, companion], no sensor pixel mode",
                "session template still operation mode 0x0 fps 30-30",
                "session tags: org.codeaurora.qcamera3.sensor_meta_data.current_mode=i32[]:0@both; xiaomi.remosaic.enabled=u8[]:1@both",
                "(P635)"})
            assertTrue(part + " in " + line, line.contains(part));
    }

    @Test public void theViewfinderAlwaysHasAStreamOfItsOwn() {
        // the ISP viewfinder beside the capture RAW (the three-stream layout the documents did not try with a SurfaceTexture)
        final FullResRecipePlan isp = FullResRecipePlan.of(edited(FullResRecipeTest.xiaomi(), FullResRecipe.PREVIEW, "isp"), ENV);
        assertEquals(streams(FullResRecipePlan.Stream.VIEWFINDER), isp.targets(false));
        assertEquals(streams(FullResRecipePlan.Stream.RAW_CAPTURE, FullResRecipePlan.Stream.COMPANION), isp.targets(true));
        assertNull(isp.output(FullResRecipePlan.Stream.RAW_PREVIEW));
        assertEquals(1440, isp.output(FullResRecipePlan.Stream.VIEWFINDER).width);
        // both
        final FullResRecipePlan both = FullResRecipePlan.of(edited(FullResRecipeTest.xiaomi(), FullResRecipe.PREVIEW, "both"), ENV);
        assertEquals(streams(FullResRecipePlan.Stream.VIEWFINDER, FullResRecipePlan.Stream.RAW_PREVIEW), both.targets(false));
        assertEquals(4, both.outputs.size());
        // the ISP stream as the companion: the still targets it, no YUV stream of its own
        final FullResRecipePlan vf = FullResRecipePlan.of(edited(FullResRecipeTest.xiaomi(), FullResRecipe.PREVIEW, "isp", FullResRecipe.COMPANION, "viewfinder"), ENV);
        assertNull(vf.output(FullResRecipePlan.Stream.COMPANION));
        assertTrue(vf.viewfinderCompanion());
        assertEquals(streams(FullResRecipePlan.Stream.RAW_CAPTURE, FullResRecipePlan.Stream.VIEWFINDER), vf.targets(true));
        // no companion at all: the still is the RAW alone (the documents' upscaled case - the truth check's)
        final FullResRecipePlan alone = FullResRecipePlan.of(edited(FullResRecipeTest.xiaomi(), FullResRecipe.COMPANION, "off"), ENV);
        assertEquals(streams(FullResRecipePlan.Stream.RAW_CAPTURE), alone.targets(true));
        // a camera without a binned RAW stream of its own shows the ISP viewfinder instead, and says so
        final FullResRecipePlan noRaw = FullResRecipePlan.of(FullResRecipeTest.xiaomi(), new FullResRecipePlan.Env(1440, 1080, 0, 0, 0, null));
        assertEquals(FullResRecipe.Preview.ISP, noRaw.preview);
        assertEquals(streams(FullResRecipePlan.Stream.VIEWFINDER), noRaw.targets(false));
        assertTrue(noRaw.describe("2").contains("the camera has no stream for «raw»"));
        // one whose only RAW size is the capture's own has no second RAW stream either
        final FullResRecipePlan same = FullResRecipePlan.of(FullResRecipeTest.xiaomi(), new FullResRecipePlan.Env(1440, 1080, 8192, 6144, 0x20, null));
        assertEquals(FullResRecipe.Preview.ISP, same.preview);
        // no ISP stream: the RAW viewfinder; neither: no plan (the normal session)
        final FullResRecipePlan noIsp = FullResRecipePlan.of(edited(FullResRecipeTest.xiaomi(), FullResRecipe.PREVIEW, "isp"),
                new FullResRecipePlan.Env(0, 0, 4096, 3072, 0x20, null));
        assertEquals(FullResRecipe.Preview.RAW, noIsp.preview);
        assertNull(FullResRecipePlan.of(FullResRecipeTest.xiaomi(), new FullResRecipePlan.Env(0, 0, 0, 0, 0, null)));
        for (FullResRecipePlan p : new FullResRecipePlan[]{isp, both, vf, alone, noRaw, same, noIsp}) {
            assertFalse("a repeating request with no target", p.targets(false).isEmpty());
            assertFalse("the capture RAW never repeats", p.targets(false).contains(FullResRecipePlan.Stream.RAW_CAPTURE));
            assertEquals(FullResRecipePlan.Stream.RAW_CAPTURE, p.targets(true).get(0));
            assertTrue(p.consistent(true) && p.consistent(false));
        }
    }

    @Test public void anAnnotatedProfileMarksTheCaptureStreamsOnly() {
        final FullResRecipe nubia = FullResRecipePresets.preset(FullResRecipeTest.NUBIA, "2", FullResRecipeTest.TABLE);
        final FullResRecipePlan plan = FullResRecipePlan.of(nubia, new FullResRecipePlan.Env(1440, 1080, 4096, 3072, 0x20,
                Arrays.asList(new int[]{8192, 6144}, new int[]{4160, 3120}, new int[]{7680, 4320}, new int[]{4608, 2592})));
        assertEquals(FullResRecipePlan.PIXEL_MAXIMUM_RESOLUTION, plan.output(FullResRecipePlan.Stream.RAW_CAPTURE).pixelModes[0]);
        assertEquals(FullResRecipePlan.PIXEL_MAXIMUM_RESOLUTION, plan.output(FullResRecipePlan.Stream.COMPANION).pixelModes[0]);
        assertEquals("the viewfinder stays DEFAULT", FullResRecipePlan.PIXEL_DEFAULT, plan.output(FullResRecipePlan.Stream.VIEWFINDER).pixelModes[0]);
        assertEquals(Integer.valueOf(FullResRecipePlan.PIXEL_MAXIMUM_RESOLUTION), plan.pixelMode(true));
        assertEquals(Integer.valueOf(FullResRecipePlan.PIXEL_DEFAULT), plan.pixelMode(false));
        assertTrue(plan.consistent(true) && plan.consistent(false));
        // the companion of the camera's own list: the smallest of the RAW's aspect
        assertEquals(4160, plan.output(FullResRecipePlan.Stream.COMPANION).width);
        assertEquals(3120, plan.output(FullResRecipePlan.Stream.COMPANION).height);
        // the session tag on the session only, the request tag on every request, the still tag on the stills only
        assertEquals(1, plan.sessionTags().size());
        assertEquals(NubiaRemosaicProbe.ZTE_FEATURE, plan.sessionTags().get(0).key);
        assertEquals(1, plan.requestTags(false).size());
        assertEquals(2, plan.requestTags(true).size());
        assertEquals(NubiaRemosaicProbe.ZTE_CUSTOM_REMOSAIC, plan.requestTags(true).get(1).key);
        assertTrue(plan.describe("2").contains("operation mode 0x8012"));
        assertTrue(plan.describe("2").contains("(experimental)"));
        // a still that targeted the DEFAULT viewfinder would be refused by the camera service: not consistent
        final FullResRecipe wrong = nubia.edit().companion(FullResRecipe.Companion.VIEWFINDER, 0, 0).build();
        assertFalse(FullResRecipePlan.of(wrong, ENV).consistent(true));
        // without a list the camera gives no companion size: none (the profile then runs without one)
        assertNull(FullResRecipePlan.companionSize(java.util.Collections.<int[]>emptyList(), 8192, 6144));
        assertEquals("no size of the aspect: the smallest listed", 1280,
                FullResRecipePlan.companionSize(Arrays.asList(new int[]{1920, 1080}, new int[]{1280, 720}), 8192, 6144)[0]);
    }

    @Test public void everyRequestOfTheSessionCarriesItsTags() {
        final FullResRecipe nubia = FullResRecipePresets.preset(FullResRecipeTest.NUBIA, "2", FullResRecipeTest.TABLE);
        FullResRecipeSession.session(FullResRecipePlan.of(nubia, ENV));
        final CaptureRequest.Builder repeating = Mockito.mock(CaptureRequest.Builder.class), still = Mockito.mock(CaptureRequest.Builder.class);
        final String r = FullResRecipeSession.applyRequest(repeating, false), s = FullResRecipeSession.applyRequest(still, true);
        assertTrue(r, r.contains(NubiaRemosaicProbe.NUBIA_APP_MODE) && !r.contains(NubiaRemosaicProbe.ZTE_CUSTOM_REMOSAIC));
        assertTrue(s, s.contains(NubiaRemosaicProbe.NUBIA_APP_MODE) && s.contains(NubiaRemosaicProbe.ZTE_CUSTOM_REMOSAIC));
        Mockito.verify(repeating, Mockito.times(1)).set(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(still, Mockito.times(2)).set(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(still).set(ArgumentMatchers.any(), ArgumentMatchers.eq(Byte.valueOf((byte) 1)));
    }

    @Test public void aShotIsTheProfilesFrames() {
        final HybridPlan plan = HybridPlan.autoExposure(6);
        assertEquals(6, plan.requests.size());
        final HybridPlan four = plan.limitedTo(4);
        assertEquals(4, four.requests.size());
        assertTrue(four.description.contains("recipe: 4 of 6 frames"));
        assertEquals("already within: the plan itself", plan, plan.limitedTo(6));
        assertEquals(plan, plan.limitedTo(0));
        assertEquals(1, plan.limitedTo(1).requests.size());
    }

    /**
     * P635c (owner, 2026-11-12: «PDAF судя по всему не работает с фулрезом»): «Ключи на видоискателе» off - the repeating request
     * carries none of the profile's tags, the session parameters and the stills keep theirs. The documents ran the viewfinder with
     * them, so the presets keep them on.
     */
    @Test public void theViewfinderRequestCanGoWithoutTheTags() {
        final FullResRecipe preset = FullResRecipeTest.xiaomi();
        assertTrue("the documents' dual stream: tags on the session and on every request", preset.viewTags);
        assertEquals("yes", preset.fields().get(FullResRecipe.VIEW_TAGS));
        for (FullResRecipePresets.Device d : new FullResRecipePresets.Device[]{FullResRecipeTest.XIAOMI_17U, FullResRecipeTest.NUBIA,
                FullResRecipeTest.ONEPLUS_16, FullResRecipeTest.OTHER})
            for (String id : new String[]{"0", "1", "2", "3", "4"}) {
                final FullResRecipe r = FullResRecipePresets.preset(d, id, null);
                if (r != null) assertTrue("camera " + id, r.viewTags);
            }
        final FullResRecipe off = edited(preset, FullResRecipe.VIEW_TAGS, "no");
        assertFalse(off.viewTags);
        assertTrue("an edited preset is a trial", off.experimental);
        assertNotEquals("a new trial for the failure memory", preset.signature(), off.signature());
        final FullResRecipePlan plan = FullResRecipePlan.of(off, ENV);
        assertNotNull(plan);
        assertTrue(plan.requestTags(false).isEmpty());
        assertEquals(2, plan.requestTags(true).size());
        assertEquals(2, plan.sessionTags().size());
        assertTrue(plan.describe("2"), plan.describe("2").contains("repeating tags: none; still tags: org.codeaurora"));
        // the request builders of such a session: a still is tagged, the viewfinder's request is left alone
        FullResRecipeSession.session(plan);
        final CaptureRequest.Builder view = Mockito.mock(CaptureRequest.Builder.class), still = Mockito.mock(CaptureRequest.Builder.class);
        assertEquals("none", FullResRecipeSession.applyRequest(view, false));
        Mockito.verifyNoInteractions(view);
        assertTrue(FullResRecipeSession.applyRequest(still, true).contains("xiaomi.remosaic.enabled"));
        // the field's texts
        for (String v : new String[]{"yes", "no", "on", "off", "1", "0", "TRUE", "false"}) assertTrue(v, FullResRecipe.valid(FullResRecipe.VIEW_TAGS, v));
        assertFalse(FullResRecipe.valid(FullResRecipe.VIEW_TAGS, "maybe"));
        assertFalse(FullResRecipe.valid(FullResRecipe.VIEW_TAGS, ""));
        // stills only, the session without the mode key too: the tags' own target
        final FullResRecipePlan stills = FullResRecipePlan.of(edited(preset, FullResRecipe.TAGS,
                "org.codeaurora.qcamera3.sensor_meta_data.current_mode=i32[]:0@still\nxiaomi.remosaic.enabled=u8[]:1@still"), ENV);
        assertTrue(stills.sessionTags().isEmpty());
        assertTrue(stills.requestTags(false).isEmpty());
        assertEquals(2, stills.requestTags(true).size());
        // what a result reports for the tags is logged under the keys' last two parts
        assertEquals("sensor_meta_data.current_mode", FullResRecipeTags.shortKey("org.codeaurora.qcamera3.sensor_meta_data.current_mode"));
        assertEquals("remosaic.enabled", FullResRecipeTags.shortKey("xiaomi.remosaic.enabled"));
        assertEquals("plain", FullResRecipeTags.shortKey("plain"));
        assertEquals("none", FullResRecipeTags.read(null, preset.tags));
    }
}
