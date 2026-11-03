package com.particlesdevs.photoncamera.util;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class LogStoragePolicyTest {
    @Test public void flagshipAlsoDoesNotStartPermanentDcimLogging() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8650");
        assertDisabled();
    }
    @Test public void olderSocDoesNotStartPermanentDcimLogging() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8550");
        assertDisabled();
    }
    @Test @Config(sdk=29) public void nubiaAndroidTenHasNoPermanentWriter() { assertDisabled(); }
    @Test public void unknownSocHasNoPermanentWriter() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("unknown");
        assertDisabled();
    }
    private static void assertDisabled() {
        ScameraFileLog.setEnabled(false);
        Log.setLogEnabled(true);
        Log.setLogFolder(RuntimeEnvironment.getApplication());
        Log.i("LogStoragePolicy", "logcat stays available");
        assertFalse(ScameraFileLog.isEnabled());
        assertEquals("logcat stays available", ShadowLog.getLogsForTag("LogStoragePolicy").get(0).msg);
    }
}
