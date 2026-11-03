package com.particlesdevs.photoncamera.processing.processor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.PhotoFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P483: which shots save their JPEG from the readback without a bitmap (ScamProcessor.jpegStreamRoute / hybridResizeNeeded). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class JpegStreamRouteTest {
    /** The owner's defaults (OPPO / vivo backups): JPEG, sRGB, no Ultra HDR / HDR output, no stamp, no fast JPEG, 1x. */
    private static boolean route(boolean on, boolean postAb, boolean tenBit, boolean p3, boolean gainMap, boolean hdr, PhotoFormat f,
                                 boolean fast, boolean lab, boolean stamp, float zoom, boolean resize, boolean noDebug, boolean jpegli) {
        return ScamProcessor.jpegStreamRoute(on, postAb, tenBit, p3, gainMap, hdr, f, fast, lab, stamp, zoom, resize, noDebug, jpegli);
    }

    @Test public void defaultShotStreams() {
        assertTrue(route(true, false, false, false, false, false, PhotoFormat.JPEG, false, false, false, 1f, false, true, true));
    }

    @Test public void everyBitmapOnlyStepKeepsTheBitmap() {
        assertFalse("switch off", route(false, false, false, false, false, false, PhotoFormat.JPEG, false, false, false, 1f, false, true, true));
        assertFalse("post_ab", route(true, true, false, false, false, false, PhotoFormat.JPEG, false, false, false, 1f, false, true, true));
        assertFalse("10-bit", route(true, false, true, false, false, false, PhotoFormat.JPEG, false, false, false, 1f, false, true, true));
        assertFalse("Display P3", route(true, false, false, true, false, false, PhotoFormat.JPEG, false, false, false, 1f, false, true, true));
        assertFalse("gain map", route(true, false, false, false, true, false, PhotoFormat.JPEG, false, false, false, 1f, false, true, true));
        assertFalse("HDR output", route(true, false, false, false, false, true, PhotoFormat.JPEG, false, false, false, 1f, false, true, true));
        assertFalse("HEIC", route(true, false, false, false, false, false, PhotoFormat.HEIC, false, false, false, 1f, false, true, true));
        assertFalse("AVIF", route(true, false, false, false, false, false, PhotoFormat.AVIF, false, false, false, 1f, false, true, true));
        assertFalse("WebP", route(true, false, false, false, false, false, PhotoFormat.WEBP, false, false, false, 1f, false, true, true));
        assertFalse("fast JPEG", route(true, false, false, false, false, false, PhotoFormat.JPEG, true, false, false, 1f, false, true, true));
        assertFalse("lab shot", route(true, false, false, false, false, false, PhotoFormat.JPEG, false, true, false, 1f, false, true, true));
        assertFalse("merge stamp", route(true, false, false, false, false, false, PhotoFormat.JPEG, false, false, true, 1f, false, true, true));
        assertFalse("digital zoom", route(true, false, false, false, false, false, PhotoFormat.JPEG, false, false, false, 1.5f, false, true, true));
        assertTrue("zoom within the module", route(true, false, false, false, false, false, PhotoFormat.JPEG, false, false, false, 1.004f, false, true, true));
        assertFalse("hybrid resize", route(true, false, false, false, false, false, PhotoFormat.JPEG, false, false, false, 1f, true, true, true));
        assertFalse("debug overlay", route(true, false, false, false, false, false, PhotoFormat.JPEG, false, false, false, 1f, false, false, true));
        assertFalse("no jpegli", route(true, false, false, false, false, false, PhotoFormat.JPEG, false, false, false, 1f, false, true, false));
    }

    @Test public void hybridResize() {
        final Point raw = new Point(8192, 6144);
        assertFalse("no hybrid final size", ScamProcessor.hybridResizeNeeded(null, null, raw));
        assertFalse("the pipeline resized to it", ScamProcessor.hybridResizeNeeded(new Point(4096, 3072), new Point(4096, 3072), raw));
        assertTrue("left to the CPU", ScamProcessor.hybridResizeNeeded(new Point(4096, 3072), null, raw));
        assertFalse("the sensor size", ScamProcessor.hybridResizeNeeded(new Point(4096, 3072), null, new Point(4096, 3072)));
    }
}
