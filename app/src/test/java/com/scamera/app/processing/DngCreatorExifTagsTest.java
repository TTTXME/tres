package com.scamera.app.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.Application;
import android.graphics.Point;
import android.util.SizeF;

import com.scamera.app.processing.render.Parameters;
import com.scamera.app.util.DeviceName;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowSystemProperties;

import java.util.TimeZone;

/**
 * What DngCreator.setParameters gives the native writer besides the colour (DngCreatorColourTagsTest). P698 (owner,
 * 2026-11-14: «Верни DNG как было»): the layout of the DNGs before P618 - ExposureTime, FNumber, ISO and FocalLength in IFD 0,
 * no Exif IFD tags, no lens tags, no DefaultCrop - with the shot's time as DateTime, our description and the DCP key as the
 * UniqueCameraModel. The native writer is a mock; tools/check_dng_metadata.cpp checks the file it writes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = DngCreatorExifTagsTest.NoNativeDng.class,
        instrumentedPackages = "com.scamera.app.processing")
public class DngCreatorExifTagsTest {
    @Implements(DngCreator.class)
    public static class NoNativeDng {
        @Implementation protected static void __staticInitializer__() { }
    }

    private static final long SHOT = 1794394987042L; // 2026-11-11 14:03:07.042 +03:00
    private TimeZone zone;

    @Before
    public void setUp() {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        ShadowBuild.setBrand("vivo");
        ShadowBuild.setManufacturer("vivo");
        ShadowBuild.setModel("V2454A");
        ShadowSystemProperties.override("ro.vivo.market.name", "vivo X200 Ultra");
        DeviceName.forget();
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(zone);
        ShadowSystemProperties.reset();
        DeviceName.forget();
    }

    private static Parameters shot() {
        final Parameters p;
        try (org.mockito.MockedStatic<com.scamera.app.app.ScameraApp> app =
                     org.mockito.Mockito.mockStatic(com.scamera.app.app.ScameraApp.class)) {
            app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(new com.scamera.app.api.Settings());
            p = new Parameters();
        }
        p.captureTimeMs = SHOT;
        p.iso = 102400;
        p.exposureTime = 1 / 852.0;
        p.focalMm = 9.12f;
        p.fNumber = 1.69f;
        p.focalLengthOfShot = p.apertureOfShot = true;
        p.lensFocalLengths = new float[]{9.12f};
        p.lensApertures = new float[]{1.69f};
        p.lensFacing = 1;
        p.sensorSizeMm = new SizeF(9.83f, 7.37f);
        p.dngFieldZoom = 1f;
        p.cameraID = "5";
        p.physicalID = 5;
        p.multiFrameCount = 8;
        p.dngMerged = true;
        p.rawSize = new Point(4080, 3072);
        p.shadingGrid = new Point(1, 1);
        p.shadingMap = new float[]{1, 1, 1, 1};
        p.asShotNeutral = new float[]{0.5f, 1f, 0.6f};
        p.cfaBlack = new float[]{64, 64, 64, 64};
        return p;
    }

    private static DngCreator writer(Parameters p) {
        final DngCreator dng = mock(DngCreator.class);
        doCallRealMethod().when(dng).setParameters(any());
        doCallRealMethod().when(dng).toDouble(any());
        try {
            dng.setParameters(p);
        } catch (RuntimeException afterTheMetadata) {
            // a colour field this shot does not fill: the time, lens and names are given before the colour
        }
        return dng;
    }

    @Test public void theShotsTimeExposureAndNamesGoToIfd0AsBefore() {
        final Parameters p = shot();
        final DngCreator dng = writer(p);
        // P698: the layout of the DNGs before P618 - the exposure in IFD 0, no Exif IFD tags, no lens tags, no crop
        verify(dng, never()).setTag(any());
        verify(dng).setIso((short) 65535);
        verify(dng).setExposureTime(1 / 852.0);
        verify(dng).setFocalLength((double) 9.12f);
        verify(dng).setAperture((double) 1.69f);
        verify(dng, never()).setDefaultCrop(anyInt(), anyInt(), anyInt(), anyInt());
        verify(dng).setDateTime("2026:11:11 14:03:07");
        verify(dng).setUniqueCameraModel("V2454A-vivo-vivo"); // P696: the DCP key
        verify(dng).setMake("vivo");
        verify(dng).setModel("vivo X200 Ultra");
        final ArgumentCaptor<String> desc = ArgumentCaptor.forClass(String.class);
        verify(dng).setDescription(desc.capture());
        assertEquals("SCAMERA\n Frames=8\n Camera=5\n RAW=merged burst\n Route=SHDR", desc.getValue());
        assertFalse(desc.getValue(), desc.getValue().toLowerCase(java.util.Locale.ROOT).contains("photon"));
        final ArgumentCaptor<String> software = ArgumentCaptor.forClass(String.class);
        verify(dng).setSoftware(software.capture());
        assertTrue(software.getValue(), software.getValue().startsWith("SCAMERA "));
    }

    @Test public void anIszPhotoKeepsTheWholeFrameAsBefore() {
        final Parameters p = shot();
        p.photoAspect4x3 = true;
        p.dngFieldZoom = 0f;
        final DngCreator dng = writer(p);
        verify(dng, never()).setDefaultCrop(anyInt(), anyInt(), anyInt(), anyInt());
        verify(dng, never()).setTag(any());
    }
}
