package com.particlesdevs.photoncamera.processing.portrait;

import android.app.Application;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PortraitFacesTest {
    private static CameraCharacteristics camera() {
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new Rect(0, 0, 400, 300));
        when(c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)).thenReturn(new Rect(0, 0, 400, 300));
        when(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)).thenReturn(new Size(400, 300));
        return c;
    }

    private static CaptureResult result(Rect box) {
        CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(123L);
        final Face f = face(box);
        when(r.get(CaptureResult.STATISTICS_FACES)).thenReturn(new Face[]{f});
        return r;
    }

    private static Face face(Rect box) {
        Face f = mock(Face.class);
        when(f.getBounds()).thenReturn(box);
        when(f.getScore()).thenReturn(90);
        return f;
    }

    private static PortraitFaces.Result read(CameraCharacteristics c, CaptureResult r) {
        return PortraitFaces.fromResult(c, r, "0", "0", 400, 300, 123L);
    }

    @Test public void zoomCoordinatesAreReturnedToTheUnzoomedRaw() {
        CaptureResult r = result(new Rect(0, 0, 200, 150));
        when(r.get(CaptureResult.CONTROL_ZOOM_RATIO)).thenReturn(2f);
        assertArrayEquals(new float[]{.25f, .25f, .5f, .5f}, read(camera(), r).boxes, 1e-6f);
    }

    @Test public void rawPixelArrayIncludesThePreCorrectionOffset() {
        CameraCharacteristics c = camera();
        Rect active = new Rect(10, 20, 410, 320);
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(active);
        when(c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)).thenReturn(active);
        when(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)).thenReturn(new Size(420, 340));
        PortraitFaces.Result f = PortraitFaces.fromResult(c, result(new Rect(0, 0, 200, 150)), "0", "0", 420, 340, 123L);
        assertArrayEquals(new float[]{10f / 420, 20f / 340, 210f / 420, 170f / 340}, f.boxes, 1e-6f);
    }

    @Test public void maximumPixelModeUsesItsOwnArrays() {
        CameraCharacteristics c = camera();
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)).thenReturn(new Rect(0, 0, 800, 600));
        when(c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)).thenReturn(new Rect(0, 0, 800, 600));
        when(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)).thenReturn(new Size(800, 600));
        CaptureResult r = result(new Rect(200, 150, 400, 300));
        when(r.get(CaptureResult.SENSOR_PIXEL_MODE)).thenReturn(CaptureResult.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        assertArrayEquals(new float[]{.25f, .25f, .5f, .5f}, read(c, r).boxes, 1e-6f);
    }

    @Test public void nonCenteredRawCropUsesTheReportedSensorWindow() {
        CaptureResult r = result(new Rect(150, 100, 250, 200));
        when(r.get(CaptureResult.SCALER_RAW_CROP_REGION)).thenReturn(new Rect(100, 75, 300, 225));
        assertArrayEquals(new float[]{.25f, 1f / 6, .75f, 5f / 6}, read(camera(), r).boxes, 1e-6f);
    }

    @Test public void vendorCropNeedsItsReportedWindowEvenWhenTheAspectMatches() {
        CaptureResult r = result(new Rect(150, 100, 250, 200));
        PortraitFaces.Result f = PortraitFaces.fromResult(camera(), r, "0", "0", 400, 300, 123L, true);
        assertTrue(f.detected);
        assertEquals(0, f.boxes.length);
        when(r.get(CaptureResult.SCALER_RAW_CROP_REGION)).thenReturn(new Rect(100, 75, 300, 225));
        assertEquals(4, PortraitFaces.fromResult(camera(), r, "0", "0", 400, 300, 123L, true).boxes.length);
    }

    @Test public void unknownWarpOrWindowKeepsProtectionWithoutSkinCoordinates() {
        CameraCharacteristics c = camera();
        CaptureResult r = result(new Rect(100, 75, 200, 150));
        when(c.get(CameraCharacteristics.LENS_DISTORTION)).thenReturn(new float[]{.1f, 0, 0, 0, 0});
        when(r.get(CaptureResult.DISTORTION_CORRECTION_MODE)).thenReturn(CaptureResult.DISTORTION_CORRECTION_MODE_FAST);
        PortraitFaces.Result f = read(c, r);
        assertTrue(f.detected);
        assertEquals(0, f.boxes.length);
        when(r.get(CaptureResult.DISTORTION_CORRECTION_MODE)).thenReturn(CaptureResult.DISTORTION_CORRECTION_MODE_OFF);
        assertEquals(4, read(c, r).boxes.length);
        f = PortraitFaces.fromResult(camera(), r, "0", "0", 400, 200, 123L);
        assertTrue(f.detected);
        assertEquals(0, f.boxes.length);
    }

    @Test public void anotherFrameOrLogicalLensNeverSuppliesSkinBoxes() {
        CameraCharacteristics c = camera();
        CaptureResult r = result(new Rect(100, 75, 200, 150));
        assertFalse(PortraitFaces.fromResult(c, r, "0", "0", 400, 300, 122L).detected);
        TotalCaptureResult logical = mock(TotalCaptureResult.class);
        when(logical.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(123L);
        final Face logicalFace = face(new Rect(0, 0, 400, 300));
        when(logical.get(CaptureResult.STATISTICS_FACES)).thenReturn(new Face[]{logicalFace});
        when(logical.getPhysicalCameraResults()).thenReturn(Collections.emptyMap());
        PortraitFaces.Result onlyLogical = PortraitFaces.fromResult(c, logical, "2", "0", 400, 300, 123L);
        assertTrue(onlyLogical.detected);
        assertEquals(0, onlyLogical.boxes.length);
        when(logical.getPhysicalCameraResults()).thenReturn(Collections.singletonMap("2", r));
        assertArrayEquals(new float[]{.25f, .25f, .5f, .5f},
                PortraitFaces.fromResult(c, logical, "2", "0", 400, 300, 123L).boxes, 1e-6f);
    }

    @Test public void cropClipsAndDropsInvalidBoxesAndCapsTheShaderCount() {
        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, PortraitFaces.crop(new float[]{.1f, .2f, .9f, .8f,
                2f, 2f, 3f, 3f, Float.NaN, 0f, 1f, 1f}, .2f, .3f, .6f, .4f), 1e-6f);
        float[] many = new float[32];
        for (int i = 0; i < many.length; i += 4) { many[i + 2] = 1; many[i + 3] = 1; }
        assertEquals(20, PortraitFaces.crop(many, 0, 0, 1, 1).length);
        assertEquals(0, PortraitFaces.crop(many, 0, 0, 0, 1).length);
    }

    @Test public void viewportTransformInvertsAllQuarterTurnsAndFrontMirroring() {
        for (int turn = 0; turn < 4; turn++) for (boolean mirror : new boolean[]{false, true}) {
            float a = new float[]{1, 0, -1, 0}[turn], b = new float[]{0, 1, 0, -1}[turn];
            float[] rotation = {a, b, 0, 0, -b, a, 0, 0, 0, 0, 1, 0, .08f, -.06f, 0, 1};
            float vx = (mirror ? -1 : 1) * (2 * .7f - 1), vy = 2 * .2f - 1;
            float u = .5f * (1 + a * vx - b * vy + rotation[12]);
            float v = .5f * (1 + b * vx + a * vy + rotation[13]);
            float[] m = PortraitFaces.viewTransform(rotation, mirror, .1f, .2f, .6f, .7f);
            assertNotNull(m);
            assertEquals(.1f + .6f * .2f, m[0] * u + m[3] * v + m[6], 1e-6f);
            assertEquals(.2f + .7f * .7f, m[1] * u + m[4] * v + m[7], 1e-6f);
        }
        assertNull(PortraitFaces.viewTransform(null, false, 0, 0, 1, 1));
        assertNull(PortraitFaces.viewTransform(new float[16], false, 0, 0, 1, 1));
        float[] invalid = new float[16]; invalid[0] = Float.NaN;
        assertNull(PortraitFaces.viewTransform(invalid, false, 0, 0, 1, 1));
    }
}
