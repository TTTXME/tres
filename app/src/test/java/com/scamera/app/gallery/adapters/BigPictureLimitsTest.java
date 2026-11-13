package com.scamera.app.gallery.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P620: 50 / 200 MP pictures in the viewer - sampled by the decoder, bounded Ultra HDR bitmaps, a few tile decoders. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class BigPictureLimitsTest {
    @Test
    public void twiceTheRequestIsSampledByTheDecoder() {
        DownsampleStrategy s = ImageAdapter.SAMPLED_INSIDE;
        assertEquals(DownsampleStrategy.SampleSizeRounding.MEMORY, s.getSampleSizeRounding(8160, 6144, 4096, 4096));
        assertEquals(DownsampleStrategy.SampleSizeRounding.MEMORY, s.getSampleSizeRounding(16320, 12288, 4096, 4096));
        assertEquals("a 27 MP photo keeps its 4096 px", DownsampleStrategy.CENTER_INSIDE.getSampleSizeRounding(6000, 4500, 4096, 4096),
                s.getSampleSizeRounding(6000, 4500, 4096, 4096));
        assertEquals(DownsampleStrategy.CENTER_INSIDE.getScaleFactor(16320, 12288, 4096, 4096), s.getScaleFactor(16320, 12288, 4096, 4096), 0f);
        assertEquals("never up", 1f, s.getScaleFactor(2000, 1500, 4096, 4096), 0f);
    }

    @Test
    public void anUltraHdrPageIsAt50MpAtMost() {
        // a 200 MP Ultra HDR JPEG: AT_MOST 8192 samples it by 2 (8160 x 6144 with its gain map); 50 MP stays whole
        assertEquals(0.5f, DownsampleStrategy.AT_MOST.getScaleFactor(16320, 12288, ImageAdapter.HDR_MAX_SIDE, ImageAdapter.HDR_MAX_SIDE), 0f);
        assertEquals(1f, DownsampleStrategy.AT_MOST.getScaleFactor(8160, 6144, ImageAdapter.HDR_MAX_SIDE, ImageAdapter.HDR_MAX_SIDE), 0f);
    }

    @Test
    public void aFewTileDecodersAPage() {
        int n = FdRegionDecoder.maxDecoders();
        assertTrue(n >= 1 && n <= 3);
    }
}
