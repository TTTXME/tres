package com.scamera.app.control;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.hardware.SensorManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** P662: the gravity readings are copied into three rotating arrays instead of a new one per event. */
@RunWith(RobolectricTestRunner.class)
public class GravityCopyTest {
    @Test
    public void thePublishedCopyIsNotWrittenByTheNextTwoReadings() {
        final Gravity g = new Gravity((SensorManager) RuntimeEnvironment.getApplication().getSystemService(Context.SENSOR_SERVICE));
        final float[] source = {1f, 2f, 9.5f};
        final float[] a = g.copyOf(source);
        assertNotSame(source, a);
        source[0] = 5f;
        final float[] b = g.copyOf(source);
        source[0] = 7f;
        final float[] c = g.copyOf(source);
        assertArrayEquals("the first copy keeps its values", new float[]{1f, 2f, 9.5f}, a, 0f);
        assertArrayEquals(new float[]{5f, 2f, 9.5f}, b, 0f);
        assertNotSame(a, b);
        assertNotSame(b, c);
        assertSame("the fourth reading reuses the first array", a, g.copyOf(source));
        assertArrayEquals(new float[]{7f, 2f, 9.5f}, a, 0f);
    }
}
