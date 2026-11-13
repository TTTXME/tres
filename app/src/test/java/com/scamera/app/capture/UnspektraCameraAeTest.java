package com.scamera.app.capture;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Range;
import android.util.Rational;
import com.scamera.app.api.Settings;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.manual.ParamController;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class UnspektraCameraAeTest {
    private MockedStatic<ScameraApp> app;
    private SharedPreferences prefs;
    private CaptureController camera;
    private ParamController manual;
    private CameraCharacteristics oldCharacteristics;
    private UnspektraCameraAe ae;
    private long timestamp;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences(); prefs.edit().clear().commit();
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(ScameraApp::getSettings).thenReturn(mock(Settings.class));
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        camera = mock(CaptureController.class); manual = mock(ParamController.class);
        when(camera.getParamController()).thenReturn(manual);
        when(camera.physicalId()).thenReturn("2");
        when(camera.honouredIsoCap()).thenReturn(6400);
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{1});
        when(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenReturn(new Range<>(100,6400));
        when(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)).thenReturn(new Range<>(10000L,1000000000L));
        when(c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)).thenReturn(new Rational(1,3));
        oldCharacteristics = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = c;
        ae = new UnspektraCameraAe(camera);
        timestamp = 1_000_000_000L;
    }

    @After public void teardown() { CaptureController.mCameraCharacteristics = oldCharacteristics; app.close(); }

    private void selected(boolean selected) {
        prefs.edit().putString("pref_sensorconfig_2_ae_engine", selected ? "1" : "0").commit();
    }

    private CaptureResult frame(int iso, long time, boolean aeOff) {
        CaptureResult r = mock(CaptureResult.class);
        CaptureRequest request = mock(CaptureRequest.class);
        timestamp += 33_333_333L;
        when(r.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
        when(r.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(iso);
        when(r.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(time);
        when(r.get(CaptureResult.CONTROL_AE_MODE)).thenReturn(aeOff ? 0 : 1);
        when(r.getRequest()).thenReturn(request);
        when(request.get(CaptureRequest.SENSOR_SENSITIVITY)).thenReturn(iso);
        when(request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)).thenReturn(time);
        ae.noteResult(r);
        return r;
    }

    private CaptureRequest.Builder builder() {
        try {
            Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
            return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                    .newInstance(metadata.getConstructor().newInstance(), false, -1, "2", null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @Test public void androidRemainsDefaultAndAlternativeWaitsForAValidRaw() {
        assertFalse(ae.eligible());
        selected(true); assertTrue(ae.eligible()); assertFalse(ae.ownsExposure());
        CaptureResult r = frame(400,10000000L,false);
        assertFalse(ae.observe(r,Double.NaN,ae.revision()));
        assertTrue(ae.observe(r,0,ae.revision())); assertTrue(ae.ownsExposure());
        CaptureRequest.Builder b = builder(); ae.apply(b);
        assertEquals(Integer.valueOf(0),b.get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(Integer.valueOf(400),b.get(CaptureRequest.SENSOR_SENSITIVITY));
        assertEquals(Long.valueOf(10000000L),b.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        selected(false); assertFalse(ae.ownsExposure());
    }

    @Test public void staleAndDuplicateFramesCannotMoveTheRequest() {
        selected(true);
        CaptureResult old = frame(400,10000000L,false);
        frame(400,10000000L,false);
        for (int n=0;n<8;n++) frame(400,10000000L,false);
        assertFalse(ae.observe(old,3,ae.revision()));
        CaptureResult current = frame(400,10000000L,false);
        long revision = ae.revision(); ae.resetMeter();
        assertFalse(ae.observe(current,3,revision));
        assertTrue(ae.observe(current,3,ae.revision()));
        assertFalse(ae.observe(current,3,ae.revision()));
    }

    @Test public void limitsDoNotRestrictFullyManualAndPriorityKeepsItsFixedDimension() {
        selected(true);
        prefs.edit().putString("pref_sensorconfig_2_ae_min_iso","200")
                .putString("pref_sensorconfig_2_ae_max_iso","800").putString("pref_sensorconfig_2_ae_slow_den","100").commit();
        when(manual.manualIsoValue()).thenReturn(1600);
        assertTrue(ae.observe(frame(400,20000000L,false),0,ae.revision()));
        CaptureRequest.Builder b = builder(); ae.apply(b);
        assertEquals(Integer.valueOf(1600),b.get(CaptureRequest.SENSOR_SENSITIVITY));
        assertEquals(Long.valueOf(10000000L),b.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        when(manual.manualPreviewShutterNs()).thenReturn(50000000L);
        assertFalse(ae.eligible()); assertFalse(ae.ownsExposure());
        CaptureRequest.Builder untouched = builder(); ae.apply(untouched); assertNull(untouched.get(CaptureRequest.CONTROL_AE_MODE));
    }

    @Test public void ignoredAeOffRestoresAndroidOnlyForThisSession() {
        selected(true); ae.observe(frame(400,10000000L,false),0,ae.revision());
        for (int n=0;n<24;n++) assertFalse(ae.observe(frame(400,10000000L,false),0,ae.revision()));
        assertFalse(ae.eligible()); assertFalse(ae.ownsExposure());
        ae.reset(); assertTrue(ae.eligible()); assertFalse(ae.ownsExposure());
    }

    @Test public void inFlightRequestsAreNotFedBackAsTheNewExposure() {
        selected(true); ae.observe(frame(400,10000000L,false),0,ae.revision());
        for (int n=0;n<30;n++) assertFalse(ae.observe(frame(200,20000000L,true),2,ae.revision()));
        assertTrue(ae.ownsExposure());
        assertTrue(ae.observe(frame(400,10000000L,true),2,ae.revision()));
        CaptureRequest.Builder b = builder(); ae.apply(b);
        assertNotEquals(Integer.valueOf(200),b.get(CaptureRequest.SENSOR_SENSITIVITY));
    }

    @Test public void everyOwnFrameStepsWhileTheLastRequestIsInFlight() {
        // P476: the loop moves on each frame of one of its own requests (smooth), not only once the HAL shows the newest one
        selected(true); ae.observe(frame(400,10000000L,false),0,ae.revision());
        assertTrue(ae.observe(frame(400,10000000L,true),2,ae.revision()));
        CaptureRequest.Builder b1 = builder(); ae.apply(b1);
        double e1 = (double)b1.get(CaptureRequest.SENSOR_SENSITIVITY) * b1.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
        assertTrue(ae.observe(frame(400,10000000L,true),2,ae.revision()));
        CaptureRequest.Builder b2 = builder(); ae.apply(b2);
        double e2 = (double)b2.get(CaptureRequest.SENSOR_SENSITIVITY) * b2.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
        assertTrue(e1 + " -> " + e2, e2 > e1);
        assertTrue("one frame's step stays small", Math.log(e2 / e1) / Math.log(2) <= .21);
    }

    @Test public void aSustainedExternalOverrideReleasesTheLoop() {
        selected(true); ae.observe(frame(400,10000000L,false),0,ae.revision());
        for (int n=0;n<65;n++) ae.observe(frame(200,20000000L,true),2,ae.revision());
        assertFalse(ae.ownsExposure()); assertFalse(ae.eligible());
    }

    @Test public void slowAutomaticShuttersExtendAndRestoreFrameDuration() {
        selected(true); prefs.edit().putString("pref_sensorconfig_2_ae_slow_den","0").commit();
        ae.observe(frame(400,200000000L,false),0,ae.revision());
        CaptureRequest.Builder b = builder(); b.set(CaptureRequest.SENSOR_FRAME_DURATION,33333333L);
        ae.apply(b); assertEquals(Long.valueOf(200000000L),b.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        assertEquals(Long.valueOf(200000000L),b.get(CaptureRequest.SENSOR_FRAME_DURATION));
        selected(false); ae.apply(b); assertEquals(Long.valueOf(33333333L),b.get(CaptureRequest.SENSOR_FRAME_DURATION));
    }
}
