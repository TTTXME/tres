package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.ImageReader;

import com.scamera.app.api.CameraEvents;
import com.scamera.app.api.CameraManager2;
import com.scamera.app.api.Settings;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P680: an open runs beside the previous camera's async close (its readers, sizes and characteristics are made meanwhile): the
 * close gives back only what it took when it was scheduled - never the new open's readers, LOFIC readers or lease; the vendor
 * lease path still waits for the close first; a pause closes off the main thread only without a lease, an SDK session or a video.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CameraCloseOverlapTest {
    private CaptureController controller;
    private MockedStatic<ScameraApp> appStatic;
    private MockedConstruction<CameraManager2> managers;

    private static Object get(Object o, String name) throws Exception {
        Field f = CaptureController.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void put(Object o, String name, Object value) throws Exception {
        Field f = CaptureController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        SettingsManager settings = new SettingsManager(context);
        appStatic = mockStatic(ScameraApp.class);
        appStatic.when(ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(ScameraApp::getSettings).thenReturn(new Settings());
        ScameraApp app = mock(ScameraApp.class);
        when(app.getSettingsManager()).thenReturn(settings);
        appStatic.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        appStatic.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        PreferenceKeys.initialise(settings);
        CameraManager2.forgetCache();
        managers = mockConstruction(CameraManager2.class, (mock, c) -> when(mock.getCameraIdList()).thenReturn(new String[]{"3"}));
        Activity activity = mock(Activity.class);
        when(activity.getSystemService(Context.CAMERA_SERVICE)).thenReturn(mock(CameraManager.class));
        controller = new CaptureController(activity, mock(ExecutorService.class), mock(CameraEvents.class));
        CaptureController.isProcessing = false;
    }

    @After public void cleanup() {
        CameraManager2.forgetCache();
        managers.close();
        appStatic.close();
    }

    /** Runs nothing on the close thread until {@code gate} opens; returns the latch that says the thread is held. */
    private static CountDownLatch holdCloseThread(CountDownLatch gate) throws Exception {
        Field f = CaptureController.class.getDeclaredField("CAMERA_CLOSE");
        f.setAccessible(true);
        final CountDownLatch held = new CountDownLatch(1);
        ((ExecutorService) f.get(null)).execute(() -> {
            held.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        return held;
    }

    private static void drainCloseThread() throws Exception {
        Field f = CaptureController.class.getDeclaredField("CAMERA_CLOSE");
        f.setAccessible(true);
        ((ExecutorService) f.get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    @Test public void theAsyncCloseClosesOnlyWhatItTookWhenItWasScheduled() throws Exception {
        final ImageReader oldRaw = mock(ImageReader.class), oldLofic = mock(ImageReader.class), oldLoficRaw = mock(ImageReader.class);
        final VivoSdkRawSession oldLease = mock(VivoSdkRawSession.class);
        put(controller, "mImageReaderRaw", oldRaw);
        put(controller, "mLoficReader", oldLofic);
        put(controller, "mLoficRawA", oldLoficRaw);
        put(controller, "mIszLease", oldLease);
        final CountDownLatch gate = new CountDownLatch(1);
        holdCloseThread(gate);
        controller.closeCamera(true, true);
        assertTrue("the close is in flight", (Boolean) get(controller, "mAsyncCloseInFlight"));
        assertNull("the readers' fields go at once", get(controller, "mImageReaderRaw"));
        // the next open makes its own readers (and, past its wait, a lease) while the close is still queued
        final ImageReader newRaw = mock(ImageReader.class), newLofic = mock(ImageReader.class), newLoficRaw = mock(ImageReader.class);
        final VivoSdkRawSession newLease = mock(VivoSdkRawSession.class);
        put(controller, "mImageReaderRaw", newRaw);
        put(controller, "mLoficReader", newLofic);
        put(controller, "mLoficRawA", newLoficRaw);
        put(controller, "mIszLease", newLease);
        ((java.util.concurrent.atomic.AtomicBoolean) get(controller, "mCameraOpening")).set(true);
        gate.countDown();
        drainCloseThread();
        final CountDownLatch leaseThread = new CountDownLatch(1);
        LeaseGuard.executor().execute(leaseThread::countDown); // the give-back's check ran before this
        assertTrue(leaseThread.await(5, TimeUnit.SECONDS));
        verify(oldRaw).close();
        verify(oldLofic).close();
        verify(oldLoficRaw).close();
        verify(newRaw, never()).close();
        verify(newLofic, never()).close();
        verify(newLoficRaw, never()).close();
        assertSame("the new open's reader stays", newLofic, get(controller, "mLoficReader"));
        assertSame(newLoficRaw, get(controller, "mLoficRawA"));
        assertSame("the new open's lease is not given back", newLease, get(controller, "mIszLease"));
        verify(newLease, never()).close();
        assertFalse((Boolean) get(controller, "mAsyncCloseInFlight"));
        assertEquals("the outputs are closed", 0, CaptureController.awaitCameraOutputsClosed(10));
    }

    @Test public void aSynchronousCloseGivesBackTheCurrentLofic() throws Exception {
        final ImageReader lofic = mock(ImageReader.class);
        put(controller, "mLoficReader", lofic);
        controller.closeCamera();
        verify(lofic, times(1)).close();
        assertNull(get(controller, "mLoficReader"));
    }

    @Test public void theVendorLeasePathWaitsForTheCloseFirst() {
        assertFalse("a plain open prepares beside the close", CaptureController.closeWaitFirst(false, false, false));
        assertTrue("a lease or SDK session still held", CaptureController.closeWaitFirst(true, false, false));
        assertTrue("an ISZ / sensor-mode lease taken by this open", CaptureController.closeWaitFirst(false, true, false));
        assertTrue("a FULLRES SDK session taken by this open", CaptureController.closeWaitFirst(false, false, true));
    }

    @Test public void aPauseClosesOffTheMainThreadOnlyWithoutALeaseAnSdkSessionOrAVideo() {
        assertTrue(CaptureController.pauseCloseAsync(false, false, false, false, true));
        assertFalse("scam_dev pause_close_async 0", CaptureController.pauseCloseAsync(false, false, false, false, false));
        assertFalse("a vivo SDK lease (P640)", CaptureController.pauseCloseAsync(true, false, false, false, true));
        assertFalse("a FULLRES SDK session", CaptureController.pauseCloseAsync(false, true, false, false, true));
        assertFalse("a video", CaptureController.pauseCloseAsync(false, false, true, false, true));
        assertFalse("closed for a FULLRES merge", CaptureController.pauseCloseAsync(false, false, false, true, true));
    }

    @Test public void aPauseStopsTheViewfinderRequestAndTheTextureWaitsForTheClose() throws Exception {
        final CameraCaptureSession session = mock(CameraCaptureSession.class);
        put(controller, "mCaptureSession", session);
        final CountDownLatch gate = new CountDownLatch(1);
        holdCloseThread(gate);
        controller.closeCameraOnPause();
        verify(session).stopRepeating();
        verify(session, never()).close();
        // the GL viewfinder's texture release waits for the close (bounded)
        final long waited = CaptureController.awaitCameraOutputsClosed(30);
        assertTrue(waited >= 20);
        gate.countDown();
        drainCloseThread();
        verify(session).close();
        assertEquals(0, CaptureController.awaitCameraOutputsClosed(10));
    }

    @Test public void theCharacteristicsMapTakesNullAsNoEntry() {
        final CaptureController.CharacteristicsMap map = new CaptureController.CharacteristicsMap();
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        map.put("3", c);
        assertSame(c, map.get("3"));
        assertNull(map.get(null));
        assertNull(map.remove(null));
        assertFalse(map.containsKey(null));
        map.put("3", null);
        assertNull(map.get("3"));
        assertTrue(map.isEmpty());
        assertNull(map.put(null, c));
    }
}
