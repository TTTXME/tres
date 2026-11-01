package com.particlesdevs.photoncamera.util;

import android.app.Application;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class LogStoragePolicyTest {
    @Before @After public void resetWriter() throws Exception {
        android.os.Handler handler=ReflectionHelpers.getStaticField(Log.class,"logHandler");
        if(handler!=null)handler.removeCallbacksAndMessages(null);
        android.os.HandlerThread thread=ReflectionHelpers.getStaticField(Log.class,"logThread");
        if(thread!=null)thread.quitSafely();
        java.lang.reflect.Method close=Log.class.getDeclaredMethod("closeWriter");
        close.setAccessible(true);close.invoke(null);
        for(String field:new String[]{"logHandler","logThread","logContext","logDir"})
            ReflectionHelpers.setStaticField(Log.class,field,null);
        ReflectionHelpers.setStaticField(Log.class,"fileLogEnabled",false);
        for(String name:new String[]{"pendingLines","droppedLines"}){
            AtomicInteger lines=ReflectionHelpers.getStaticField(Log.class,name);
            lines.set(0);
        }
    }

    @Test public void olderSocDoesNotStartTheDcimWriter() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8550");
        assertDisabled();
    }

    @Test @Config(sdk = 29)
    public void nubiaAndroidTenDoesNotStartTheDcimWriter() {
        assertDisabled();
    }

    @Test public void unknownSocDoesNotStartTheDcimWriter() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("unknown");
        assertDisabled();
    }

    private static void assertDisabled() {
        Log.setLogEnabled(true);
        Log.setLogFolder(RuntimeEnvironment.getApplication());
        Log.i("LogStoragePolicy", "logcat stays available");
        assertNull(ReflectionHelpers.getStaticField(Log.class, "logThread"));
        assertNull(ReflectionHelpers.getStaticField(Log.class, "logHandler"));
        assertNull(ReflectionHelpers.getStaticField(Log.class, "logContext"));
        assertNull(ReflectionHelpers.getStaticField(Log.class, "bufferedWriter"));
        AtomicInteger pending = ReflectionHelpers.getStaticField(Log.class, "pendingLines");
        assertEquals(0, pending.get());
        assertEquals("logcat stays available", ShadowLog.getLogsForTag("LogStoragePolicy").get(0).msg);
    }
}
