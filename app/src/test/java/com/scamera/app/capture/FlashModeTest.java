package com.scamera.app.capture;

import android.app.Application;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.scamera.app.processing.ImageFrame;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.scamera.app.capture.FlashMode.AUTO;
import static com.scamera.app.capture.FlashMode.OFF;
import static com.scamera.app.capture.FlashMode.ON;
import static com.scamera.app.capture.FlashMode.TORCH;
import static org.junit.Assert.*;

/**
 * P590 (owner, 2026-11-09: «Добавь так же разные режимы вспышки, у нас есть только фонарик»): the flash modes' cycle, the
 * preview's AE / flash keys of each mode, the press's flash-shot decision, the flash frame's request keys, the precapture wait,
 * and no flash shot in the sensor-mode routes (owner: «Тогда отключи вспышку с ISZ, DCG, dag»).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FlashModeTest {
    private static CaptureRequest.Builder builder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "0", null);
    }

    private static String src(String path) throws Exception {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void aTapCyclesOffAutoOnTorch() {
        assertEquals(AUTO, FlashMode.next(OFF, true, false));
        assertEquals(ON, FlashMode.next(AUTO, true, false));
        assertEquals(TORCH, FlashMode.next(ON, true, false));
        assertEquals(OFF, FlashMode.next(TORCH, true, false));
        // a value outside the four is off; the default (pref_ae_mode_default) is off
        assertEquals(OFF, FlashMode.normalize(7));
        assertEquals(AUTO, FlashMode.next(-3, true, false));
    }

    @Test public void aLensWithoutAFlashAndTheVideoKeepOffAndTheTorch() {
        assertEquals(TORCH, FlashMode.next(OFF, false, false));
        assertEquals(OFF, FlashMode.next(TORCH, false, false));
        // a stored Auto / On (chosen on the back lens) is off on the front lens, the next tap the torch (its screen light)
        assertEquals(OFF, FlashMode.effective(AUTO, false, false));
        assertEquals(OFF, FlashMode.effective(ON, false, false));
        assertEquals(TORCH, FlashMode.next(ON, false, false));
        assertEquals(TORCH, FlashMode.next(OFF, true, true));
        assertEquals(OFF, FlashMode.next(TORCH, true, true));
        assertEquals(OFF, FlashMode.effective(AUTO, true, true));
        assertEquals(TORCH, FlashMode.effective(TORCH, true, true));
        assertEquals(AUTO, FlashMode.effective(AUTO, true, false));
    }

    @Test public void thePreviewKeysOfEachMode() {
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, FlashMode.previewAeMode(OFF, true, false));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH, FlashMode.previewAeMode(AUTO, true, false));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH, FlashMode.previewAeMode(ON, true, false));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, FlashMode.previewAeMode(TORCH, true, false));
        // no flash shot here (no flash unit, a sensor-mode route) and the video: ON
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, FlashMode.previewAeMode(AUTO, false, false));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, FlashMode.previewAeMode(ON, false, false));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, FlashMode.previewAeMode(ON, true, true));
        assertEquals(CameraMetadata.FLASH_MODE_TORCH, FlashMode.previewFlashMode(TORCH, true));
        assertEquals(CameraMetadata.FLASH_MODE_OFF, FlashMode.previewFlashMode(TORCH, false));
        for (int m : new int[]{OFF, AUTO, ON}) assertEquals(CameraMetadata.FLASH_MODE_OFF, FlashMode.previewFlashMode(m, true));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH, FlashMode.stillAeMode(AUTO));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH, FlashMode.stillAeMode(ON));
        // a tap keeps the preview's flash AE mode, never AE off
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH, FlashMode.touchAeMode(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, FlashMode.touchAeMode(null));
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, FlashMode.touchAeMode(CameraMetadata.CONTROL_AE_MODE_OFF));
    }

    @Test public void thePressDecidesTheFlashShot() {
        final int required = CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED, converged = CaptureResult.CONTROL_AE_STATE_CONVERGED;
        assertTrue(FlashMode.flashShot(ON, converged, true));
        assertTrue(FlashMode.flashShot(ON, null, true));
        assertTrue(FlashMode.flashShot(AUTO, required, true));
        assertFalse(FlashMode.flashShot(AUTO, converged, true));
        assertFalse(FlashMode.flashShot(AUTO, null, true));
        assertFalse(FlashMode.flashShot(TORCH, required, true));
        assertFalse(FlashMode.flashShot(OFF, required, true));
        assertFalse("no flash unit", FlashMode.flashShot(ON, required, false));
        assertTrue(FlashMode.fired(CaptureResult.FLASH_STATE_FIRED));
        assertFalse(FlashMode.fired(CaptureResult.FLASH_STATE_PARTIAL));
        assertFalse(FlashMode.fired(CaptureResult.FLASH_STATE_READY));
        assertFalse(FlashMode.fired(null));
    }

    @Test public void theSensorModeRoutesTakeNoFlashShot() throws Exception {
        assertNull(FlashMode.routeBlock(false, false, false, false));
        assertEquals("FULLRES", FlashMode.routeBlock(true, false, false, false));
        assertEquals("DCG / DAG", FlashMode.routeBlock(false, true, false, false));
        assertEquals("ISZ", FlashMode.routeBlock(false, false, true, false));
        assertNotNull(FlashMode.routeBlock(false, false, false, true));
        final String s = src("src/main/java/com/scamera/app/capture/CaptureController.java");
        // the press asks the route first (Auto / On act as off there, one toast), the preview runs plain ON there
        final int press = s.indexOf("private FlashMode.Shot flashShotAtPress()");
        assertTrue(press > 0);
        final String body = s.substring(press, s.indexOf("\n    }\n", press));
        assertTrue(body.indexOf("flashRouteBlock(true)") > 0
                && body.indexOf("flashRouteBlock(true)") < body.indexOf("CONTROL_AE_STATE"));
        assertTrue(body.contains("FlashMode.routeBlockText(blocked)"));
        final int route = s.indexOf("private String flashRouteBlock(boolean atPress)");
        final String routeBody = s.substring(route, s.indexOf("\n    }\n", route));
        for (String flag : new String[]{"mVivoSdkRawSession != null", "mFullResSession", "PixelPro10.fullResOn()", "Dcg14Mode.active(",
                "mDcgRequestKeys", "mDcgOperationMode != 0", "mShutterIszAspect4x3", "mCroppedRawSession", "XiaomiTeleZoom.isz()",
                "SensorModePick.session() != null", "forcedMode != 0"})
            assertTrue(flag, routeBody.contains(flag));
        final int ae = s.indexOf("private void applyAeMode(CaptureRequest.Builder requestBuilder, int aeMode)");
        assertTrue(s.substring(ae, s.indexOf("\n    }\n", ae)).contains("mFlashSupported && flashRouteBlock(false) == null"));
        // FULLRES (the vivo / OPPO SDK session) posts its own capture: never the flash shot
        final int sdk = s.indexOf("flashShotAtPress(); // P590: FULLRES takes no flash shot");
        assertTrue(sdk > 0 && s.indexOf("mBackgroundHandler.post(this::captureVivoSdkFullRes);", sdk) - sdk < 200);
    }

    @Test public void theFlashFrameIsAStillCaptureWithTheAeFlash() throws Exception {
        HybridPlan plan = HybridPlan.flash(FlashMode.stillAeMode(AUTO));
        assertTrue(plan.flash());
        assertEquals(1, plan.requests.size());
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.requests.get(0).role);
        CaptureRequest.Builder b = builder();
        b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
        b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
        plan.apply(b, 0);
        assertEquals(Integer.valueOf(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH), b.get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE), b.get(CaptureRequest.CONTROL_CAPTURE_INTENT));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE), b.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER));
        assertEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_OFF), b.get(CaptureRequest.FLASH_MODE));
        assertEquals(Boolean.FALSE, b.get(CaptureRequest.CONTROL_AE_LOCK));

        HybridPlan manual = HybridPlan.flashManual(20_000_000L, 400);
        assertTrue(manual.flash());
        CaptureRequest.Builder m = builder();
        manual.apply(m, 0);
        assertEquals(Integer.valueOf(CameraMetadata.CONTROL_AE_MODE_OFF), m.get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_SINGLE), m.get(CaptureRequest.FLASH_MODE));
        assertEquals(Long.valueOf(20_000_000L), m.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        assertEquals(Integer.valueOf(400), m.get(CaptureRequest.SENSOR_SENSITIVITY));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE), m.get(CaptureRequest.CONTROL_CAPTURE_INTENT));

        // a normal AE frame keeps plain ON and touches no flash key
        CaptureRequest.Builder n = builder();
        HybridPlan.autoExposure(1).apply(n, 0);
        assertEquals(Integer.valueOf(CameraMetadata.CONTROL_AE_MODE_ON), n.get(CaptureRequest.CONTROL_AE_MODE));
        assertNull(n.get(CaptureRequest.FLASH_MODE));
        assertFalse(HybridPlan.autoExposure(1).flash());
    }

    @Test public void thePrecaptureWaitsForItsEnd() {
        final int pre = CaptureResult.CONTROL_AE_STATE_PRECAPTURE, conv = CaptureResult.CONTROL_AE_STATE_CONVERGED,
                req = CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
        final Integer start = CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START, idle = CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE;
        FlashMode.Precapture w = new FlashMode.Precapture(0);
        // results queued ahead of the trigger never end it
        assertNull(w.offer(idle, req, 10));
        assertNull(w.offer(idle, conv, 20));
        assertNull(w.offer(start, pre, 40));
        assertNull(w.offer(idle, pre, 300));
        assertEquals("FLASH_REQUIRED", w.offer(idle, req, 420));
        // a HAL without PRECAPTURE: after a few results past the trigger
        FlashMode.Precapture quick = new FlashMode.Precapture(0);
        assertNull(quick.offer(start, conv, 30));
        assertNull(quick.offer(idle, conv, 60));
        assertNull(quick.offer(idle, conv, 90));
        assertNotNull(quick.offer(idle, conv, 120));
        // no AE state, the timeout
        assertEquals("no AE state", new FlashMode.Precapture(0).offer(start, null, 30));
        FlashMode.Precapture slow = new FlashMode.Precapture(0);
        assertNull(slow.offer(start, pre, 30));
        assertTrue(slow.offer(idle, pre, FlashMode.PRECAPTURE_TIMEOUT_MS + 1).startsWith("timeout"));
        // the log line
        FlashMode.Shot shot = new FlashMode.Shot(AUTO, req, 0, 0);
        shot.precaptureMs = 412;
        shot.precaptureEnd = "CONVERGED";
        assertEquals("flash shot: mode auto, AE state FLASH_REQUIRED, precapture 412 ms (CONVERGED)", shot.describe());
        assertFalse(shot.manual());
        assertTrue(new FlashMode.Shot(ON, null, 10_000_000L, 200).manual());
    }

    @Test public void theFlashShotTakesNoRingFramesAndFallsBackWhenTheFlashDidNotFire() throws Exception {
        final String s = src("src/main/java/com/scamera/app/capture/CaptureController.java");
        final int still = s.indexOf("private void takeStill()");
        final String body = s.substring(still, s.indexOf("\n    /**", still));
        // the flash frame: no ring drain, one frame, its own white balance, no ETTR note, no AE restore frame
        final int branch = body.indexOf("if (flash != null) {\n                // P590: the flash frame alone");
        assertTrue(branch > 0 && branch < body.indexOf("mPendingZslNormalFrames = drainZslNormalFrames("));
        assertTrue(body.contains("final boolean manualPsl = flash == null &&"));
        assertTrue(body.contains(": HybridPlan.flash(FlashMode.stillAeMode(flash.mode));"));
        // the press hand-off check's form (tools/check_press_handoff.py): the press's value, then none for the flash frame
        assertTrue(body.contains("mImageSaver.mosaicWbAtPress = mShutterMosaicWb;\n            if (flash != null) mImageSaver.mosaicWbAtPress = null;"));
        assertTrue(body.contains("if (mScamRouted && flash == null) queueScamAeRestore();"));
        // the frame's own FLASH_STATE: FIRED releases the held ring, anything else the normal shot of the press
        assertTrue(body.contains("final boolean fired = FlashMode.fired(flashState);"));
        assertTrue(body.contains("if (fired) releaseFlashRing();"));
        assertTrue(body.contains("finishHandler.post(CaptureController.this::flashFallbackShot)"));
        // the preview results of the press stay in the ring's metadata while a flash press holds it
        assertTrue(s.contains("if (sensorTs != null && isZslMode() && !mFlashRingHold) keepRingResult(sensorTs, result);"));
    }
}
