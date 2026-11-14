package com.scamera.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

/** P680: the first-run dialog's memory and storage profile (device_profile_mem_*), read by the ZSL ring's memory class. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DeviceMemoryProfileTest {
    @After public void forget() {
        RamClass.forgetForTest();
    }

    private static Context phone(long totalBytes, boolean lowRam) {
        final Context context = RuntimeEnvironment.getApplication();
        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        info.totalMem = totalBytes;
        shadowOf(am).setMemoryInfo(info);
        shadowOf(am).setIsLowRamDevice(lowRam);
        return context;
    }

    @Test public void recordedOncePerStampAndReadByTheMemoryClass() {
        final Context context = phone(6L << 30, false);
        assertNull("nothing stored yet", DeviceMemoryProfile.storedMemory(context));
        final String line = DeviceMemoryProfile.recordOnce(context, "1|fp");
        assertNotNull(line);
        assertTrue(line, line.startsWith("device profile: memory 6144 MB (low class), large heap "));
        assertEquals(6L << 30, DeviceMemoryProfile.storedMemory(context)[0]);
        assertNull("the same install: not measured again", DeviceMemoryProfile.recordOnce(context, "1|fp"));
        assertTrue("the cache's write speed", DeviceMemoryProfile.storageWriteMbps(context) > 0f);
        assertFalse("the test file is gone", new File(context.getCacheDir(), "device_profile_write.tmp").exists());
        // the memory class reads the stored profile, not the ActivityManager
        phone(12L << 30, false);
        RamClass.forgetForTest();
        assertTrue(RamClass.get(context).low());
        assertEquals(6L << 30, RamClass.get(context).totalBytes);
        // an update measures again
        assertNotNull(DeviceMemoryProfile.recordOnce(context, "2|fp"));
        RamClass.forgetForTest();
        assertFalse(RamClass.get(context).low());
    }

    @Test public void theWriteTestIsBounded() {
        final long start = System.nanoTime();
        final float mbps = DeviceMemoryProfile.writeSpeed(RuntimeEnvironment.getApplication().getCacheDir());
        assertTrue(mbps > 0f);
        assertTrue((System.nanoTime() - start) / 1_000_000 < DeviceMemoryProfile.TEST_MAX_MS + 2000);
        assertEquals(0f, DeviceMemoryProfile.writeSpeed(null), 0f);
        assertEquals(0f, DeviceMemoryProfile.writeSpeed(new File("/nonexistent-dir-p680")), 0f);
    }

    @Test public void theLogLineNamesTheClass() {
        assertEquals("device profile: memory 12288 MB (normal class), large heap 512 MB, cache write 850 MB/s",
                DeviceMemoryProfile.describe(12L << 30, false, 512, 850f));
        assertEquals("device profile: memory 3072 MB (low class, low-RAM device), large heap 256 MB, cache write not measured",
                DeviceMemoryProfile.describe(3L << 30, true, 256, 0f));
    }
}
