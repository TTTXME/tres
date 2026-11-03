package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.app.Application;

import com.particlesdevs.photoncamera.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ViewfinderGpuPriorityTest {
    private static final int VERSION = 0x3098, PRIORITY = 0x3100, HIGH = 0x3101, MEDIUM = 0x3102;
    private Fixture h;

    private static final class Fixture {
        EGL10 egl;
        EGLDisplay display;
        EGLConfig config;
        EGLContext context;
        MockedStatic<Log> log;
    }

    @Before public void setUp() {
        h = new Fixture();
        h.egl = mock(EGL10.class);
        h.display = mock(EGLDisplay.class);
        h.config = mock(EGLConfig.class);
        h.context = mock(EGLContext.class);
        h.log = mockStatic(Log.class);
        when(h.egl.eglQueryString(h.display, EGL10.EGL_EXTENSIONS)).thenReturn("EGL_KHR_create_context EGL_IMG_context_priority");
        when(h.egl.eglCreateContext(same(h.display), same(h.config), same(EGL10.EGL_NO_CONTEXT), any(int[].class)))
                .thenReturn(h.context);
    }

    @After public void tearDown() {
        h.log.close();
    }

    private void grant(int priority) {
        when(h.egl.eglQueryContext(same(h.display), same(h.context), eq(PRIORITY), any(int[].class))).thenAnswer(call -> {
            ((int[]) call.getArgument(3))[0] = priority;
            return true;
        });
    }

    private List<int[]> attributes(int count) {
        ArgumentCaptor<int[]> captured = ArgumentCaptor.forClass(int[].class);
        verify(h.egl, times(count)).eglCreateContext(same(h.display), same(h.config), same(EGL10.EGL_NO_CONTEXT), captured.capture());
        return captured.getAllValues();
    }

    @Test public void defaultRequestsHighAndReportsWhatTheDriverGranted() {
        assertEquals(1f, GLPreview.DEFAULT_GPU_PRIORITY, 0f);
        grant(HIGH);
        assertSame(h.context, GLPreview.createViewfinderContext(h.egl, h.display, h.config, true));
        assertArrayEquals(new int[]{VERSION, 3, PRIORITY, HIGH, EGL10.EGL_NONE}, attributes(1).get(0));
        h.log.verify(() -> Log.i("GLPreview", "viewfinder GPU priority requested=high supported=true granted=high"));
    }

    @Test public void rejectedHighClearsTheErrorBeforeTheOrdinaryFallback() {
        when(h.egl.eglCreateContext(same(h.display), same(h.config), same(EGL10.EGL_NO_CONTEXT), any(int[].class)))
                .thenReturn(EGL10.EGL_NO_CONTEXT, h.context);
        when(h.egl.eglGetError()).thenReturn(EGL10.EGL_BAD_ATTRIBUTE);
        grant(MEDIUM);
        assertSame(h.context, GLPreview.createViewfinderContext(h.egl, h.display, h.config, true));
        List<int[]> attrs = attributes(2);
        assertArrayEquals(new int[]{VERSION, 3, PRIORITY, HIGH, EGL10.EGL_NONE}, attrs.get(0));
        assertArrayEquals(new int[]{VERSION, 3, EGL10.EGL_NONE}, attrs.get(1));
        InOrder order = inOrder(h.egl);
        order.verify(h.egl).eglQueryString(h.display, EGL10.EGL_EXTENSIONS);
        order.verify(h.egl).eglCreateContext(same(h.display), same(h.config), same(EGL10.EGL_NO_CONTEXT), any(int[].class));
        order.verify(h.egl).eglGetError();
        order.verify(h.egl).eglCreateContext(same(h.display), same(h.config), same(EGL10.EGL_NO_CONTEXT), any(int[].class));
        h.log.verify(() -> Log.i("GLPreview", "viewfinder GPU priority requested=high supported=true granted=medium"));
    }

    @Test public void nullHighContextAlsoFallsBack() {
        when(h.egl.eglCreateContext(same(h.display), same(h.config), same(EGL10.EGL_NO_CONTEXT), any(int[].class)))
                .thenReturn(null, h.context);
        grant(MEDIUM);
        assertSame(h.context, GLPreview.createViewfinderContext(h.egl, h.display, h.config, true));
        assertArrayEquals(new int[]{VERSION, 3, EGL10.EGL_NONE}, attributes(2).get(1));
    }

    @Test public void missingExtensionKeepsTheOrdinaryContextAndDoesNotQueryPriority() {
        when(h.egl.eglQueryString(h.display, EGL10.EGL_EXTENSIONS)).thenReturn(null);
        assertSame(h.context, GLPreview.createViewfinderContext(h.egl, h.display, h.config, true));
        assertArrayEquals(new int[]{VERSION, 3, EGL10.EGL_NONE}, attributes(1).get(0));
        verify(h.egl, never()).eglQueryContext(any(), any(), anyInt(), any(int[].class));
        h.log.verify(() -> Log.i("GLPreview", "viewfinder GPU priority requested=high supported=false granted=unknown"));
    }

    @Test public void similarExtensionNameDoesNotEnableUnsupportedAttributes() {
        when(h.egl.eglQueryString(h.display, EGL10.EGL_EXTENSIONS)).thenReturn("EGL_IMG_context_priority_other");
        assertSame(h.context, GLPreview.createViewfinderContext(h.egl, h.display, h.config, true));
        assertArrayEquals(new int[]{VERSION, 3, EGL10.EGL_NONE}, attributes(1).get(0));
    }

    @Test public void explicitNormalOverrideKeepsTheOriginalContextAttributes() {
        grant(MEDIUM);
        assertSame(h.context, GLPreview.createViewfinderContext(h.egl, h.display, h.config, false));
        assertArrayEquals(new int[]{VERSION, 3, EGL10.EGL_NONE}, attributes(1).get(0));
        h.log.verify(() -> Log.i("GLPreview", "viewfinder GPU priority requested=normal supported=true granted=medium"));
    }

    @Test public void acceptedHintDoesNotMisreportADowngradedContext() {
        grant(MEDIUM);
        assertSame(h.context, GLPreview.createViewfinderContext(h.egl, h.display, h.config, true));
        attributes(1);
        h.log.verify(() -> Log.i("GLPreview", "viewfinder GPU priority requested=high supported=true granted=medium"));
    }

    @Test public void failedPriorityQueryRetainsTheUsableContextAndClearsItsError() {
        when(h.egl.eglQueryContext(same(h.display), same(h.context), eq(PRIORITY), any(int[].class))).thenReturn(false);
        assertSame(h.context, GLPreview.createViewfinderContext(h.egl, h.display, h.config, true));
        attributes(1);
        verify(h.egl).eglGetError();
        h.log.verify(() -> Log.i("GLPreview", "viewfinder GPU priority requested=high supported=true granted=unknown"));
    }
}
