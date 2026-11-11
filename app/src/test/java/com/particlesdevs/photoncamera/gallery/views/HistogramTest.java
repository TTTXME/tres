package com.particlesdevs.photoncamera.gallery.views;

import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** P617: a histogram view that only draws starts no thread and no GL context; a released one refuses work quietly. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class HistogramTest {
    @Test
    public void aDrawingHistogramHasNoThread() {
        Histogram h = new Histogram(RuntimeEnvironment.getApplication(), null);
        assertFalse(h.hasWorker());
        h.release();
        h.release();
        // the viewer's late Glide callback after the viewer went: nothing runs, nothing throws
        h.analyzeAsync(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), m -> { throw new AssertionError("ran after release"); });
        assertFalse(h.hasWorker());
    }
}
