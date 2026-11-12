package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.Application;
import android.graphics.ImageFormat;

import com.particlesdevs.photoncamera.processing.yuv.YuvFrames;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P650 «Склейка YUV»: the after-shutter YUV session delivers YCBCR_P010 frames (format 54). Before P650 ImageSaverSelector rejected
 * them in its default branch ("unexpected image format:54"), so the frames never reached the YUV merge and the shot failed with
 * "the camera delivered no RAW frame". The route now sends P010 (and 8-bit YUV_420_888) to the YUVSaver, which keeps them as the
 * burst's frames (IMAGE_BUFFER) exactly as the ring path does - so the series after the shutter is merged and saved like the ring.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ImageSaverSelectorYuvTest {
    private SaverImplementation base;

    @Before public void setup() {
        base = new SaverImplementation(mock(ProcessingEventsListener.class));
        ImageSaverSelector.init(base);
    }

    @Test public void p010RoutesToTheYuvSaverLikeEightBitYuv() {
        // format 54 is YCBCR_P010; before P650 it fell into the default branch and was rejected
        assertTrue(YuvFrames.isYuv(YuvFrames.P010));
        final SaverImplementation forP010 = ImageSaverSelector.getImageSaver(YuvFrames.P010, base);
        final SaverImplementation forYuv8 = ImageSaverSelector.getImageSaver(ImageFormat.YUV_420_888, base);
        assertNotNull("P010 must not be rejected", forP010);
        assertTrue("P010 takes the YUVSaver", forP010 instanceof YUVSaver);
        assertSame("the 10-bit and 8-bit YUV frames share one saver, so both reach IMAGE_BUFFER", forYuv8, forP010);
    }

    @Test public void rawAndJpegKeepTheirOwnSavers() {
        assertTrue(ImageSaverSelector.getImageSaver(ImageFormat.RAW_SENSOR, base) instanceof RAW16Saver);
        assertTrue(ImageSaverSelector.getImageSaver(ImageFormat.RAW10, base) instanceof RAW16Saver);
        assertTrue(ImageSaverSelector.getImageSaver(ImageFormat.JPEG, base) instanceof JPEGSaver);
    }

    @Test public void anUnknownFormatIsLeftUnchanged() {
        // the default branch returns the implementation it was handed (no change), never a wrong saver
        assertSame(base, ImageSaverSelector.getImageSaver(ImageFormat.PRIVATE, base));
    }
}
