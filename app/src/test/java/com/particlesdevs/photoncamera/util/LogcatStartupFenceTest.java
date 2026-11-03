package com.particlesdevs.photoncamera.util;

import android.app.Application;
import android.os.Looper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class LogcatStartupFenceTest {
    private final StringBuilder saved=new StringBuilder();
    private BatchedLogWriter queue;

    private static void set(String name,Object value) throws Exception {
        Field field=ScameraFileLog.class.getDeclaredField(name);
        field.setAccessible(true);field.set(null,value);
    }

    @Before public void startingCollector() throws Exception {
        queue=new BatchedLogWriter(new BatchedLogWriter.Sink() {
            public void write(String text) { saved.append(text); }
            public void flush() { }
        });
        set("writer",queue);set("enabled",true);set("collectorFailed",false);set("process",null);
        queue.append("session header\n");
    }

    @After public void stop() throws Exception {
        set("enabled",false);
        CountDownLatch done=new CountDownLatch(1);queue.afterFlush(done::countDown);
        assertTrue(done.await(5,TimeUnit.SECONDS));
    }

    @Test public void immediateShareWaitsForStartupCollectorMarker() throws Exception {
        CountDownLatch shared=new CountDownLatch(1);
        ScameraFileLog.afterFlush(shared::countDown);
        assertFalse("share must not flush only the header while logcat is starting",shared.await(100,TimeUnit.MILLISECONDS));
        String flush=ShadowLog.getLogsForTag("SCAMERA-file-log").stream()
                .map(line->line.msg).filter(line->line.startsWith("FLUSH ")).reduce((a,b)->b).orElseThrow(AssertionError::new);
        ScameraFileLog.append("JAVA marker\nJNI marker\n");
        Method finish=ScameraFileLog.class.getDeclaredMethod("finishBarrier",long.class,boolean.class);
        finish.setAccessible(true);finish.invoke(null,Long.parseLong(flush.substring(6)),false);
        assertTrue(shared.await(5,TimeUnit.SECONDS));
        assertEquals("session header\nJAVA marker\nJNI marker\n",saved.toString());
    }

    @Test public void failedStartupCompletesWithExplicitTimeoutBoundary() throws Exception {
        CountDownLatch shared=new CountDownLatch(1);
        ScameraFileLog.afterFlush(shared::countDown);
        assertFalse(shared.await(100,TimeUnit.MILLISECONDS));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue("startup cannot leave sharing pending indefinitely",shared.await(5,TimeUnit.SECONDS));
        assertTrue(saved.toString().contains("logcat flush timed out: snapshot may omit trailing logcat records"));
    }
}
