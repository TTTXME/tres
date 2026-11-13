package com.scamera.app.capture;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** CaptureController's one main-thread handler: made once, kept, and made again when the main looper it holds is not current. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CaptureControllerMainHandlerTest {
    private Field cached;
    private Method mainHandler;
    private HandlerThread other;

    @Before
    public void setUp() throws Exception {
        cached = CaptureController.class.getDeclaredField("sMainHandler");
        cached.setAccessible(true);
        mainHandler = CaptureController.class.getDeclaredMethod("mainHandler");
        mainHandler.setAccessible(true);
        cached.set(null, null);
    }

    @After
    public void tearDown() throws Exception {
        cached.set(null, null);
        if (other != null) other.quitSafely();
    }

    private Handler main() throws Exception {
        return (Handler) mainHandler.invoke(null);
    }

    @Test
    public void madeOnTheMainLooperOnceAndKept() throws Exception {
        final Handler first = main();
        assertSame(Looper.getMainLooper(), first.getLooper());
        assertSame("no new handler for each post", first, main());
        assertSame(first, cached.get(null));
    }

    @Test
    public void aHandlerOfAnotherLooperIsReplaced() throws Exception {
        other = new HandlerThread("not-main");
        other.start();
        final Handler stale = new Handler(other.getLooper());
        cached.set(null, stale);
        final Handler now = main();
        assertNotSame(stale, now);
        assertSame("made again on the current main looper", Looper.getMainLooper(), now.getLooper());
        assertSame(now, main());
    }
}
