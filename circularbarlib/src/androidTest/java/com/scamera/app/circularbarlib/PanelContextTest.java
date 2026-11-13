package com.scamera.app.circularbarlib;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** On a device: the library's test APK runs in its own package (the namespace's .test). */
@RunWith(AndroidJUnit4.class)
public class PanelContextTest {
    @Test
    public void runsInTheLibrarysTestPackage() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        assertEquals("com.scamera.app.circularbarlib.test", context.getPackageName());
    }
}
