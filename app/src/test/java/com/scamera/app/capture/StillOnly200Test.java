package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.util.Size;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P684 (owner, 2026-11-14, X300 Ultra: «200мп не работают на 300U, исправь»; «SDK STILL buffer lost» on the first still of every
 * 200 MP series; «Проблема не в буфере»; «без ремозаика»): the still-only route's variants - the X300 Ultra tries the stock
 * camera's full-size keys for the sensor's own Tetra first and the HAL's remosaic last, a failed series moves the camera's size on
 * for the next press, a variant that completed a series is kept, all per camera, size and firmware under the camera's FULLRES
 * trial keys; the still checks (full-size mode, binned corner) and the companion stream's size.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class StillOnly200Test {
    private static final VivoNativeFullRes.Phone X300 = VivoNativeFullRes.Phone.X300_ULTRA, X100 = VivoNativeFullRes.Phone.X100_ULTRA;
    private static final VivoSdkFullRes.Profile MAIN_200 = new VivoSdkFullRes.Profile(16320, 12256, 0, 4, true),
            TELE_200 = new VivoSdkFullRes.Profile(16320, 12288, 0, 4, true);
    private static final String FP = "vivo/PD2547/PD2547:17/BP4A/17.1.13.4:user/release-keys",
            FP2 = "vivo/PD2547/PD2547:17/BP4A/17.1.14.0:user/release-keys";
    private static final String LOST = "SDK STILL buffer lost";
    private static final StillOnly200.Variant NATIVE = StillOnly200.Variant.NATIVE, NATIVE_RAW = StillOnly200.Variant.NATIVE_RAW,
            NATIVE_KEY = StillOnly200.Variant.NATIVE_KEY, HAL = StillOnly200.Variant.HAL, KEY = StillOnly200.Variant.KEY,
            BASE = StillOnly200.Variant.BASE;
    private SharedPreferences p;

    @Before public void setUp() {
        p = RuntimeEnvironment.getApplication().getSharedPreferences("p684", Context.MODE_PRIVATE);
        p.edit().clear().commit();
    }

    private StillOnly200.Choice choose(VivoNativeFullRes.Phone phone, String camera, VivoSdkFullRes.Profile profile, String fp) {
        return StillOnly200.choose(p, phone, true, camera, profile.size.getWidth(), profile.size.getHeight(), fp, 0f);
    }

    private StillOnly200.Outcome fail(String camera, VivoSdkFullRes.Profile profile, String why) {
        return StillOnly200.failed(p, camera, profile, FP, choose(X300, camera, profile, FP), why);
    }

    @Test public void theRouteIsTheVivoStillOnlyOneOnly() {
        assertTrue(StillOnly200.applies(MAIN_200, false));
        assertTrue(StillOnly200.applies(TELE_200, false));
        assertFalse("OPPO's own route", StillOnly200.applies(TELE_200, true));
        assertFalse("the X200 Ultra's 200 MP holds its mode (not still-only)", StillOnly200.applies(VivoSdkFullRes.TELE_200, false));
        assertFalse("50 MP", StillOnly200.applies(VivoSdkFullRes.TELE_50, false));
        assertFalse(StillOnly200.applies(null, false));
    }

    @Test public void theX300UltraTriesTheNativeMosaicFirstAndTheHalRemosaicLast() {
        final StillOnly200.Variant[] x300 = StillOnly200.order(X300, true);
        assertEquals(4, x300.length);
        assertEquals(NATIVE, x300[0]);
        assertEquals(NATIVE_RAW, x300[1]);
        assertEquals(NATIVE_KEY, x300[2]);
        assertEquals("the HAL's remosaic only as the last one", HAL, x300[3]);
        for (int i = 0; i < 3; i++) assertFalse(x300[i] + " is native", x300[i].halRemosaic);
        assertTrue(HAL.halRemosaic && HAL.recipe);
        assertTrue(NATIVE.recipe && NATIVE.onlyDng && !NATIVE.modeKey);
        assertTrue(NATIVE_RAW.recipe && !NATIVE_RAW.onlyDng);
        assertTrue(NATIVE_KEY.recipe && NATIVE_KEY.modeKey);
        assertTrue(!KEY.recipe && KEY.modeKey && !BASE.recipe && !BASE.modeKey);
        final StillOnly200.Variant[] off = StillOnly200.order(X300, false), x100 = StillOnly200.order(X100, true);
        assertEquals("scam_dev vivo_x300_fullsize 0: P208, then the mode key", BASE, off[0]);
        assertEquals(KEY, off[1]);
        assertEquals("the X100 Ultra unchanged until a series fails", BASE, x100[0]);
        assertEquals(KEY, x100[1]);
        final StillOnly200.Choice first = choose(X300, "3", MAIN_200, FP);
        assertEquals(NATIVE, first.variant);
        assertEquals(0, first.step);
        assertTrue(first.toString(), first.toString().contains("step 1 of 4") && first.toString().contains("NATIVE > NATIVE_RAW > NATIVE_KEY > HAL"));
        assertEquals(BASE, choose(X100, "4", TELE_200, FP).variant);
    }

    @Test public void aFailedSeriesMovesTheSizeToTheNextVariantUntilEveryOneFailed() {
        StillOnly200.Outcome o = fail("3", MAIN_200, LOST);
        assertTrue(o.noted);
        assertFalse(o.exhausted);
        assertEquals(NATIVE_RAW, o.next);
        assertTrue(o.log, o.log.contains("NATIVE failed (" + LOST + ")") && o.log.contains("the next press tries NATIVE_RAW"));
        StillOnly200.Choice c = choose(X300, "3", MAIN_200, FP);
        assertEquals(NATIVE_RAW, c.variant);
        assertTrue(c.source, c.source.contains(LOST));
        o = fail("3", MAIN_200, "Native FULLRES mosaic does not match its sensor profile (block 1, the profile's 4)");
        assertEquals("the HAL's remosaic on a native variant moves on", NATIVE_KEY, o.next);
        o = fail("3", MAIN_200, "camera error 4");
        assertEquals(HAL, o.next);
        o = fail("3", MAIN_200, "full-size still not in the full-size mode: seamlessMode 0, expected 768");
        assertTrue(o.noted);
        assertTrue("every variant failed: the caller notes the 200 MP row", o.exhausted);
        assertNull(o.next);
        assertEquals("from the first again", NATIVE, choose(X300, "3", MAIN_200, FP).variant);
    }

    @Test public void theVariantThatCompletedASeriesIsKeptAndGetsOneMorePressAfterAFailure() {
        fail("5", TELE_200, LOST);
        StillOnly200.Choice c = choose(X300, "5", TELE_200, FP);
        assertEquals(NATIVE_RAW, c.variant);
        final String kept = StillOnly200.succeeded(p, "5", TELE_200, FP, c);
        assertNotNull(kept);
        assertTrue(kept, kept.contains("NATIVE_RAW") && kept.contains("kept"));
        assertNull("said once", StillOnly200.succeeded(p, "5", TELE_200, FP, c));
        c = choose(X300, "5", TELE_200, FP);
        assertEquals(NATIVE_RAW, c.variant);
        assertTrue(c.source, c.source.contains("completed a series before"));
        StillOnly200.Outcome o = StillOnly200.failed(p, "5", TELE_200, FP, c, LOST);
        assertTrue(o.noted && o.again);
        assertEquals(NATIVE_RAW, o.next);
        c = choose(X300, "5", TELE_200, FP);
        assertEquals("once more", NATIVE_RAW, c.variant);
        o = StillOnly200.failed(p, "5", TELE_200, FP, c, LOST);
        assertFalse(o.again);
        assertEquals("then the next one", NATIVE_KEY, o.next);
    }

    @Test public void failuresWithoutAVerdictOnTheVariantNoteNothing() {
        assertTrue(StillOnly200.counts(LOST));
        assertTrue(StillOnly200.counts("SDK STILL failed: 0"));
        assertTrue(StillOnly200.counts("SDK RAW/result/JPEG timeout at frame 0"));
        assertTrue(StillOnly200.counts("camera error 4"));
        assertTrue(StillOnly200.counts("SDK FULLRES50 sensor mode is not active"));
        assertTrue(StillOnly200.counts("binned image in a corner (1/4 of the buffer, the rest zero): the HAL did not switch the sensor"));
        assertTrue(StillOnly200.counts("Native FULLRES mosaic does not match its sensor profile (block 1, the profile's 4)"));
        assertTrue(StillOnly200.counts(null));
        assertFalse(StillOnly200.counts("camera closed"));
        assertFalse(StillOnly200.counts("camera disconnected"));
        assertFalse("a flat scene says nothing", StillOnly200.counts("Native FULLRES mosaic has not been verified from RAW pixels"));
        assertFalse(fail("3", MAIN_200, "camera closed").noted);
        assertEquals(NATIVE, choose(X300, "3", MAIN_200, FP).variant);
    }

    @Test public void scamDevHoldsAVariantAndNotesNothing() {
        assertEquals(NATIVE, StillOnly200.devVariant(1f));
        assertEquals(NATIVE_RAW, StillOnly200.devVariant(2f));
        assertEquals(NATIVE_KEY, StillOnly200.devVariant(3f));
        assertEquals(HAL, StillOnly200.devVariant(4f));
        assertEquals(KEY, StillOnly200.devVariant(5f));
        assertEquals(BASE, StillOnly200.devVariant(6f));
        for (float bad : new float[]{0f, 7f, -1f, 2.5f, Float.NaN}) assertNull(String.valueOf(bad), StillOnly200.devVariant(bad));
        final StillOnly200.Choice held = StillOnly200.choose(p, X300, true, "3", 16320, 12256, FP, 4f);
        assertEquals(HAL, held.variant);
        assertTrue(held.dev());
        assertFalse(StillOnly200.failed(p, "3", MAIN_200, FP, held, LOST).noted);
        assertNull(StillOnly200.succeeded(p, "3", MAIN_200, FP, held));
        assertEquals(NATIVE, choose(X300, "3", MAIN_200, FP).variant);
        assertEquals("no stock keys where the order has none", BASE,
                StillOnly200.choose(p, X100, true, "4", 16320, 12288, FP, 1f).variant);
        assertEquals(KEY, StillOnly200.choose(p, X100, true, "4", 16320, 12288, FP, 5f).variant);
    }

    @Test public void theMemoryIsPerCameraSizeAndFirmwareUnderTheCamerasTrialKeys() {
        fail("3", MAIN_200, LOST);
        assertEquals(NATIVE_RAW, choose(X300, "3", MAIN_200, FP).variant);
        assertEquals("the tele its own", NATIVE, choose(X300, "5", TELE_200, FP).variant);
        assertEquals("a firmware update tries again", NATIVE, choose(X300, "3", MAIN_200, FP2).variant);
        assertTrue(StillOnly200.key("3", MAIN_200).startsWith(VivoNativeFullRes.base("3") + "_"));
        assertTrue("«Попробовать снова» forgets it", FullResTrials.clear(p, VivoNativeFullRes.base("3")) > 0);
        assertEquals(NATIVE, choose(X300, "3", MAIN_200, FP).variant);
    }

    @Test public void theModeKeyGoesOnTheStillOnlyRoutesStillsOnly() {
        assertTrue(VivoSdkFullResCapture.modeKeyOnStill(MAIN_200, true, false));
        assertTrue("scam_dev vivo_fullres200_force as before", VivoSdkFullResCapture.modeKeyOnStill(MAIN_200, false, true));
        assertFalse("P208's stills as before", VivoSdkFullResCapture.modeKeyOnStill(MAIN_200, false, false));
        assertFalse("a held 200 MP mode carries its key already", VivoSdkFullResCapture.modeKeyOnStill(VivoSdkFullRes.TELE_200, true, true));
        assertFalse(VivoSdkFullResCapture.modeKeyOnStill(VivoSdkFullRes.TELE_50, true, true));
    }

    @Test public void theFullSizeModeIsCheckedBySeamlessMode() {
        assertEquals(768, X300FullSize.expectedSeamless(new Size(16320, 12256), new Size(4080, 3064)));
        assertEquals(768, X300FullSize.expectedSeamless(new Size(16320, 12288), new Size(4080, 3072)));
        assertEquals(259, X300FullSize.expectedSeamless(new Size(8160, 6128), new Size(4080, 3064)));
        assertEquals(0, X300FullSize.expectedSeamless(new Size(4080, 3064), new Size(4080, 3064)));
        assertTrue(X300FullSize.seamlessWrong(0L, 768));
        assertTrue(X300FullSize.seamlessWrong(259L, 768));
        assertFalse(X300FullSize.seamlessWrong(768L, 768));
        assertFalse("an absent tag says nothing", X300FullSize.seamlessWrong(null, 768));
        assertFalse("another size is not checked", X300FullSize.seamlessWrong(5L, 0));
    }

    private static ByteBuffer raw16(int w, int h, int cornerDivisor) {
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        final int cw = w / cornerDivisor, ch = h / cornerDivisor;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.putShort((y * w + x) * 2, (short) (x < cw && y < ch ? 64 + (x + y) % 50 : 0));
        return b;
    }

    @Test public void aBinnedImageInACornerOfTheBufferIsFound() {
        assertEquals(4, X300FullSize.binnedCorner(raw16(256, 192, 4), 256, 192, 512));
        assertEquals(2, X300FullSize.binnedCorner(raw16(256, 192, 2), 256, 192, 512));
        assertEquals("a full frame", 1, X300FullSize.binnedCorner(raw16(256, 192, 1), 256, 192, 512));
        final ByteBuffer black = ByteBuffer.allocateDirect(256 * 192 * 2);
        assertEquals("all zero: nothing inside the corner either", 1, X300FullSize.binnedCorner(black, 256, 192, 512));
        assertEquals("too small to tell", 1, X300FullSize.binnedCorner(raw16(32, 32, 4), 32, 32, 64));
    }

    @Test public void theCompanionIsTheSmallestYuvOfTheViewfinderShape() {
        final Size[] yuv = {new Size(4080, 3060), new Size(1920, 1080), new Size(1440, 1080), new Size(640, 480), new Size(320, 180),
                new Size(176, 144)};
        assertEquals(new Size(640, 480), X300FullSize.companion(yuv, 4080, 3064));
        assertEquals("no shape match: the smallest of at least 320 x 240", new Size(1920, 1080),
                X300FullSize.companion(new Size[]{new Size(1920, 1080), new Size(3840, 2160)}, 4080, 3064));
        assertNull(X300FullSize.companion(null, 4080, 3064));
    }

    private static java.util.Map<String, Object> keys(StillOnly200.Variant v, long stamp) {
        final java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (Object[] k : X300FullSize.stillKeys(v, stamp)) m.put((String) k[0], k[1]);
        return m;
    }

    @Test public void theStillsCarryTheStockKeysOfTheirVariant() {
        final java.util.Map<String, Object> hal = keys(HAL, 1000L), nat = keys(NATIVE, 1000L), raw = keys(NATIVE_RAW, 2000L);
        assertEquals(74, hal.get("com.vivo.current_ui_module"));
        assertEquals("the HAL's remosaic pair on HAL only", 1, hal.get("vivo.control.isUpscale"));
        assertEquals(0, hal.get("vivo.control.sensorMode"));
        assertFalse(nat.containsKey("vivo.control.isUpscale"));
        assertFalse(nat.containsKey("vivo.control.sensorMode"));
        assertEquals(1, nat.get("vivo.control.onlyDng"));
        assertFalse(raw.containsKey("vivo.control.onlyDng"));
        assertEquals((byte) 1, nat.get("vivo.control.isPAPNeeded"));
        assertEquals("the ids: request id = frame flag = the stamp, global id = stamp + 5", 1000L, nat.get("vivo.control.requestId"));
        assertEquals(1000L, nat.get("vivo.control.frameFlag"));
        assertEquals(1005L, nat.get("vivo.control.globalCaptureId"));
        assertEquals(2005L, raw.get("vivo.control.globalCaptureId"));
        assertEquals(5, X300FullSize.SESSION.length);
        assertEquals("vivo.control.ultra_highresolution", X300FullSize.SESSION[0][0]);
        assertEquals(1, X300FullSize.SESSION[0][1]);
    }

    @Test public void theOwnerIsToldWhatTheNextPressTries() {
        final String text = StillOnly200.ownerNext(fail("3", MAIN_200, LOST));
        assertFalse(text.isEmpty());
        assertTrue(text, text.contains("200"));
    }
}
