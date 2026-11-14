package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.scamera.app.processing.ImageFrame;
import com.scamera.app.processing.ImageSaver;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * P699 (owner, X300 Ultra 2026-11-14 on build 31270: «Исправь все фулрезы которые нормально не работают»): the 200 MP still-only
 * stills of cameras 3 and 5 came back as the HAL's remosaicked Bayer on every native variant («native FULLRES still: block 1, the
 * profile's 4», each press lost). Such a series now completes as a Bayer photo merged as block 1, the next native variant is tried
 * for the Tetra on the next press and, with none left, the first one that gave the Bayer is kept; the 50 MP rows keep their own
 * block (a Bayer row moves on, P188); the shot's stream key is the stills', not the normal-mode viewfinder's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class StillOnly200BayerTest {
    private static final VivoNativeFullRes.Phone X300 = VivoNativeFullRes.Phone.X300_ULTRA, X100 = VivoNativeFullRes.Phone.X100_ULTRA;
    private static final VivoSdkFullRes.Profile TELE_200 = new VivoSdkFullRes.Profile(16320, 12288, 0, 4, true),
            HAL_200 = new VivoSdkFullRes.Profile(16320, 12288, 0, 1, true),
            MAIN_50 = new VivoSdkFullRes.Profile(8160, 6128, 3, 2);
    private static final String FP = "vivo/PD2547/PD2547:17/CP2A.260605.016_V000L1/261111010218:user/release-keys";
    private static final StillOnly200.Variant NATIVE = StillOnly200.Variant.NATIVE, NATIVE_RAW = StillOnly200.Variant.NATIVE_RAW,
            NATIVE_KEY = StillOnly200.Variant.NATIVE_KEY, HAL = StillOnly200.Variant.HAL, BASE = StillOnly200.Variant.BASE,
            KEY = StillOnly200.Variant.KEY;
    private SharedPreferences p;

    @Before public void setUp() {
        p = RuntimeEnvironment.getApplication().getSharedPreferences("p699", Context.MODE_PRIVATE);
        p.edit().clear().commit();
        VivoSdkFullResCapture.MosaicProof.clear();
    }

    private StillOnly200.Choice choose(VivoNativeFullRes.Phone phone, String camera) {
        return StillOnly200.choose(p, phone, true, camera, TELE_200.size.getWidth(), TELE_200.size.getHeight(), FP, 0f);
    }

    @Test public void aStillOnlySeriesTakesTheHalsBayerOrTheTetraOfItsFirstConfidentStill() {
        assertEquals("the HAL's remosaic: a Bayer series", 1, VivoSdkFullResCapture.seriesBlock(TELE_200, -1, 1));
        assertEquals("the native Tetra", 4, VivoSdkFullResCapture.seriesBlock(TELE_200, -1, 4));
        assertEquals("a Quad is no 200 MP still", -1, VivoSdkFullResCapture.seriesBlock(TELE_200, -1, 2));
        assertEquals("then every still agrees", 1, VivoSdkFullResCapture.seriesBlock(TELE_200, 1, 1));
        assertEquals("a Tetra after Bayer stills", -1, VivoSdkFullResCapture.seriesBlock(TELE_200, 1, 4));
        assertEquals(-1, VivoSdkFullResCapture.seriesBlock(TELE_200, 4, 1));
        assertEquals("the HAL variant's profile takes the Tetra if it comes", 4, VivoSdkFullResCapture.seriesBlock(HAL_200, -1, 4));
        assertEquals("a 50 MP row keeps its profile's block (a Bayer row moves on to the next row)", -1,
                VivoSdkFullResCapture.seriesBlock(MAIN_50, -1, 1));
        assertEquals(2, VivoSdkFullResCapture.seriesBlock(MAIN_50, -1, 2));
        assertEquals("the X200 Ultra's held 200 MP mode is no still-only route", -1,
                VivoSdkFullResCapture.seriesBlock(VivoSdkFullRes.TELE_200, -1, 1));
    }

    @Test public void theShotMergesWithTheBlockItsStillsProved() {
        assertEquals(1, VivoSdkFullResCapture.shotBlock(TELE_200, 1, false));
        assertEquals(4, VivoSdkFullResCapture.shotBlock(TELE_200, 4, true));
        assertEquals("no confident still: the profile's", 4, VivoSdkFullResCapture.shotBlock(TELE_200, -1, false));
        assertEquals("no confident still of a variant kept as the HAL's Bayer", 1, VivoSdkFullResCapture.shotBlock(TELE_200, -1, true));
        assertEquals(1, VivoSdkFullResCapture.shotBlock(HAL_200, -1, false));
    }

    @Test public void theStillsAreAStreamOfTheirOwnNotTheViewfindersBayer() {
        final String session = "5|16320x12288||0|native-full-0";
        final Object[] still = CaptureController.fullResShotKey(session, false, 1);
        assertNotEquals("the viewfinder stored block 1 under the session's key", session, still[0]);
        assertTrue(((String) still[0]).startsWith(session));
        assertEquals(1, still[1]);
        assertEquals(4, CaptureController.fullResShotKey(session, false, 4)[1]);
        final Object[] fifty = CaptureController.fullResShotKey("3|8160x6128||0|native-full-3", false, 0);
        assertEquals("a 50 MP shot as before", "3|8160x6128||0|native-full-3", fifty[0]);
        assertEquals(1, fifty[1]);
        final Object[] bin = CaptureController.fullResShotKey("5|16320x12288||0|native-full-0", true, 0);
        assertEquals("5|16320x12288||0|native-full-0" + CaptureController.TETRA_BIN_KEY, bin[0]);
        assertEquals(2, bin[1]);
    }

    /**
     * The owner's decision for the X300 Ultra («просто оставляем 200мп с ремозаиком»): its 200 MP is the HAL's Bayer from the first
     * press - its order NATIVE then HAL, no NATIVE_RAW / NATIVE_KEY trial; the X100 Ultra still looks for its Tetra.
     */
    @Test public void theX300UltraIsTheHalsBayerFromTheFirstPress() {
        assertTrue(StillOnly200.remosaicOnly(X300));
        final StillOnly200.Variant[] order = StillOnly200.order(X300, true);
        assertEquals(2, order.length);
        assertEquals(NATIVE, order[0]);
        assertEquals(HAL, order[1]);
        final StillOnly200.Choice c = choose(X300, "5");
        assertEquals(NATIVE, c.variant);
        assertTrue(c.source, c.source.contains("remosaic"));
        assertFalse(StillOnly200.remosaicOnly(X100));
        assertEquals(BASE, choose(X100, "4").variant);
    }

    @Test public void eachNativeVariantThatGivesTheBayerMovesOnAndTheFirstOneIsKeptAtTheEnd() {
        StillOnly200.Choice c = choose(X100, "4");
        assertEquals(BASE, c.variant);
        assertFalse(c.bayer);
        StillOnly200.Outcome o = StillOnly200.bayer(p, "4", TELE_200, FP, c);
        assertTrue(o.noted);
        assertFalse(o.exhausted);
        assertEquals("the native Tetra next", KEY, o.next);
        assertTrue(o.log, o.log.contains("block 1") && o.log.contains("KEY"));
        assertFalse(StillOnly200.ownerBayer(o).isEmpty());
        c = choose(X100, "4");
        assertEquals(KEY, c.variant);
        assertFalse("this one looks for the Tetra", c.bayer);
        assertTrue(c.source, c.source.contains("BASE") && c.source.contains("Bayer"));
        o = StillOnly200.bayer(p, "4", TELE_200, FP, c);
        assertTrue("no native variant left", o.exhausted);
        assertEquals("the first one that gave the Bayer", BASE, o.next);
        c = choose(X100, "4");
        assertEquals(BASE, c.variant);
        assertTrue(c.bayer);
        assertTrue(c.source, c.source.contains("kept"));
        o = StillOnly200.bayer(p, "4", TELE_200, FP, c);
        assertNull("the kept Bayer again: nothing new", o.log);
        assertTrue(StillOnly200.ownerBayer(o).isEmpty());
        assertEquals(BASE, choose(X100, "4").variant);
        assertEquals("another camera its own", BASE, choose(X100, "3").variant);
        assertFalse(choose(X100, "3").bayer);
    }

    @Test public void aHardFailureAfterTheBayerGoesBackToIt() {
        StillOnly200.bayer(p, "4", TELE_200, FP, choose(X100, "4"));
        assertEquals(KEY, choose(X100, "4").variant);
        final StillOnly200.Outcome o = StillOnly200.failed(p, "4", TELE_200, FP, choose(X100, "4"), "SDK STILL buffer lost");
        assertFalse("200 MP is not noted failed", o.exhausted);
        assertEquals(BASE, o.next);
        assertTrue(o.log, o.log.contains("goes back to BASE"));
        final StillOnly200.Choice c = choose(X100, "4");
        assertEquals(BASE, c.variant);
        assertTrue("its photos merge as Bayer", c.bayer);
    }

    @Test public void aVariantThatGivesTheTetraLaterIsKeptAsTheTetra() {
        StillOnly200.Choice c = choose(X100, "4");
        StillOnly200.bayer(p, "4", TELE_200, FP, c);
        c = choose(X100, "4");
        assertEquals(KEY, c.variant);
        assertNotNull(StillOnly200.succeeded(p, "4", TELE_200, FP, c));
        c = choose(X100, "4");
        assertEquals(KEY, c.variant);
        assertFalse("the native Tetra, no Bayer", c.bayer);
        final StillOnly200.Outcome o = StillOnly200.failed(p, "4", TELE_200, FP, c, "SDK STILL buffer lost");
        assertTrue("once more", o.again);
    }

    @Test public void theHalVariantsBayerIsAPlainSuccessAndScamDevNotesNothing() {
        final StillOnly200.Choice hal = StillOnly200.choose(p, X300, true, "5", 16320, 12288, FP, 4f);
        assertEquals(HAL, hal.variant);
        assertFalse("held by scam_dev", StillOnly200.bayer(p, "5", TELE_200, FP, hal).noted);
        final StillOnly200.Choice raw = StillOnly200.choose(p, X300, true, "5", 16320, 12288, FP, 2f);
        assertEquals(NATIVE_RAW, raw.variant);
        assertFalse("held by scam_dev", StillOnly200.bayer(p, "5", TELE_200, FP, raw).noted);
        assertEquals(NATIVE, choose(X300, "5").variant);
    }

    @Test public void failuresNotedUnderTheOldRuleAreTriedAgain() {
        final String key = StillOnly200.key("5", TELE_200);
        // the owner's log: «module_fullres_rowfail_5_still200_16320x12288_step=2 ... why=Native FULLRES mosaic does not match ...»
        p.edit().putString(key + "_stamp", "r1 " + FP).putInt(key + "_step", 2).putBoolean(key + "_ok", false)
                .putString(key + "_why", "Native FULLRES mosaic does not match its sensor profile").commit();
        assertNotEquals("r1 " + FP, StillOnly200.stamp(FP));
        assertEquals("from the first variant again", NATIVE, choose(X300, "5").variant);
    }

    // ---- the series itself ----------------------------------------------------------------------------------------------------

    private static final int W = 512, H = 512;

    /** A textured scene through a colour-block CFA of {@code block} (1 Bayer, 2 Quad, 4 Tetra) with shot-like noise. */
    private static ByteBuffer mosaic(int block, long seed) {
        final Random r = new Random(seed);
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final double[] colour = {0.45, 1.0, 0.7};
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            final int phase = (((y / block) & 1) << 1) | ((x / block) & 1);
            final int c = phase == 0 ? 0 : phase == 3 ? 2 : 1;
            final double scene = 280 + 180.0 * x / W + 70 * Math.sin(x * 0.06) * Math.cos(y * 0.045);
            final double v = 64 + scene * colour[c] + r.nextGaussian() * Math.sqrt(scene * colour[c] * 0.5 + 4);
            b.putShort((y * W + x) * 2, (short) Math.max(0, Math.min(1023, Math.round(v))));
        }
        return b;
    }

    private static CaptureRequest.Builder builder(String cameraId) throws Exception {
        final Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, cameraId, null);
    }

    /** Drives a series of {@code blocks.length} stills of {@code profile} whose pixels have those blocks: {completed, failures}. */
    private static Object[] series(VivoSdkFullRes.Profile small, int[] blocks, boolean bayerExpected) throws Exception {
        final String cameraId = "5";
        final int count = blocks.length;
        final CaptureRequest preview = builder(cameraId).build();
        final CameraDevice device = mock(CameraDevice.class);
        final CameraCaptureSession session = mock(CameraCaptureSession.class);
        final ImageSaver saver = mock(ImageSaver.class);
        final List<CaptureRequest> requests = new ArrayList<>();
        final List<CameraCaptureSession.CaptureCallback> callbacks = new ArrayList<>();
        final ArrayList<ImageFrame> frames = new ArrayList<>();
        when(device.createCaptureRequest(anyInt())).thenAnswer(call -> builder(cameraId));
        when(session.capture(any(), any(), any())).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            callbacks.add(call.getArgument(1));
            return requests.size();
        });
        doAnswer(call -> {
            final Image image = call.getArgument(0);
            final ImageFrame frame = mock(ImageFrame.class);
            frame.timestamp = image.getTimestamp();
            frames.add(frame);
            image.close();
            return null;
        }).when(saver).initProcess(any(Image.class));
        when(saver.snapshotFrames()).thenAnswer(call -> new ArrayList<>(frames));
        final int[] completed = {0};
        final List<String> failures = new ArrayList<>();
        final VivoSdkFullResCapture capture = new VivoSdkFullResCapture(device, session, mock(VivoSdkRawSession.class), mock(Surface.class),
                null, saver, new Handler(Looper.getMainLooper()), () -> 10L, 90, 1, 10, count, small, preview,
                java.util.Collections.emptyList(), new VivoSdkFullResCapture.Listener() {
                    public void started() { }
                    public void result(int index, TotalCaptureResult result) { }
                    public void complete(ScamCaptureSequence sequence, HashMap<Long, Double> exposures) { completed[0]++; }
                    public void failed(String reason) { failures.add(reason); }
                });
        capture.bayerExpected(bayerExpected);
        capture.start();
        for (int i = 0; i < count && failures.isEmpty(); i++) {
            final CaptureRequest request = requests.get(i);
            final long timestamp = 100L + i;
            callbacks.get(i).onCaptureStarted(session, request, timestamp, i);
            final TotalCaptureResult result = mock(TotalCaptureResult.class);
            when(result.getRequest()).thenReturn(request);
            when(result.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
            when(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(40_000_000L);
            when(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(800);
            callbacks.get(i).onCaptureCompleted(session, request, result);
            callbacks.get(i).onCaptureSequenceCompleted(session, i + 1, i);
            final Image image = mock(Image.class);
            when(image.getTimestamp()).thenReturn(timestamp);
            when(image.getFormat()).thenReturn(ImageFormat.RAW_SENSOR);
            when(image.getWidth()).thenReturn(W);
            when(image.getHeight()).thenReturn(H);
            final Image.Plane plane = mock(Image.Plane.class);
            when(plane.getBuffer()).thenReturn(blocks[i] == 0 ? flat() : blocks[i] == -1 ? FullGridCheckTest.replicated(0, i)
                    : blocks[i] == -2 ? FullGridCheckTest.bilinear(0, i) : mosaic(blocks[i], i));
            when(plane.getRowStride()).thenReturn(W * 2);
            when(plane.getPixelStride()).thenReturn(2);
            when(image.getPlanes()).thenReturn(new Image.Plane[]{plane});
            capture.image(image);
        }
        return new Object[]{completed[0], failures, capture.shotBlock()};
    }

    /** A flat grey frame: no colour block to read. */
    private static ByteBuffer flat() {
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < W * H; i++) b.putShort(i * 2, (short) 300);
        return b;
    }

    private static final VivoSdkFullRes.Profile SMALL_TETRA = new VivoSdkFullRes.Profile(W, H, 0, 4, true),
            SMALL_QUAD_ROW = new VivoSdkFullRes.Profile(W, H, 3, 2);

    @Test public void aStillOnlySeriesOfTheHalsBayerCompletesAsBayer() throws Exception {
        final Object[] r = series(SMALL_TETRA, new int[]{1, 1, 1}, false);
        assertEquals(r[1].toString(), 1, r[0]);
        assertEquals("merged as Bayer", 1, r[2]);
    }

    @Test public void aStillOnlySeriesOfTheTetraStaysTetra() throws Exception {
        final Object[] r = series(SMALL_TETRA, new int[]{4, 4}, false);
        assertEquals(r[1].toString(), 1, r[0]);
        assertEquals(4, r[2]);
    }

    @Test public void stillsThatDisagreeFailTheSeries() throws Exception {
        final Object[] r = series(SMALL_TETRA, new int[]{1, 4}, false);
        assertEquals(0, r[0]);
        @SuppressWarnings("unchecked") final List<String> failures = (List<String>) r[1];
        assertEquals(1, failures.size());
        assertTrue(failures.get(0), failures.get(0).contains("mosaic does not match"));
    }

    @Test public void aFlatSceneOfAVariantKeptAsBayerIsBayer() throws Exception {
        final Object[] r = series(SMALL_TETRA, new int[]{0, 0}, true);
        assertEquals(r[1].toString(), 1, r[0]);
        assertEquals(1, r[2]);
        final Object[] unproven = series(SMALL_TETRA, new int[]{0, 0}, false);
        assertEquals("never proven, never reported, no Bayer before: as before", 0, unproven[0]);
        @SuppressWarnings("unchecked") final List<String> failures = (List<String>) unproven[1];
        assertTrue(failures.toString(), failures.get(0).contains("not been verified"));
    }

    /** P699: a Bayer still whose 2x2 cells repeat their green is a 50 MP readout replicated: no 200 MP photo (a verdict). */
    @Test public void aReplicatedStillFailsTheSeriesAndAnInterpolatedOneIsOnlyLogged() throws Exception {
        final Object[] r = series(SMALL_TETRA, new int[]{-1, -1}, false);
        assertEquals(0, r[0]);
        @SuppressWarnings("unchecked") final List<String> failures = (List<String>) r[1];
        assertEquals(failures.toString(), VivoSdkFullResCapture.REPLICATED_WHY, failures.get(0));
        assertTrue("the variant's verdict: the next variant, at the end the 200 MP row noted failed",
                StillOnly200.counts(VivoSdkFullResCapture.REPLICATED_WHY));
        final Object[] smooth = series(SMALL_TETRA, new int[]{-2, -2}, false);
        assertEquals(smooth[1].toString(), 1, smooth[0]);
        assertEquals(1, smooth[2]);
    }

    @Test public void aFiftyMegapixelRowOfTheHalsBayerStillFails() throws Exception {
        final Object[] r = series(SMALL_QUAD_ROW, new int[]{1, 1}, false);
        assertEquals(0, r[0]);
        @SuppressWarnings("unchecked") final List<String> failures = (List<String>) r[1];
        assertTrue(failures.toString(), failures.get(0).contains("mosaic does not match"));
    }
}
