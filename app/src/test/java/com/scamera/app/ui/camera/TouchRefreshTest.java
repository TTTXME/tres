package com.scamera.app.ui.camera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** P705: the refresh rate a touch asks for - the highest seamless one of the present resolution, none on a 60 Hz panel. */
public class TouchRefreshTest {
    private static final int[] IDS = {1, 2, 3, 4, 5};
    private static final int[] W = {1440, 1440, 1440, 1080, 1440};
    private static final int[] H = {3200, 3200, 3200, 2400, 3200};
    private static final float[] HZ = {60f, 120f, 90f, 144f, 144f};

    @Test
    public void theHighestSeamlessRateOfThePresentResolution() {
        assertEquals(2, TouchRefresh.boostMode(IDS, W, H, HZ, 1440, 3200, 60f, new float[]{90f, 120f}));
        // 144 Hz of the same size is not a seamless alternative: not asked for (a blank switch on some panels)
        assertEquals(2, TouchRefresh.boostMode(IDS, W, H, HZ, 1440, 3200, 60f, new float[]{120f}));
        assertEquals(5, TouchRefresh.boostMode(IDS, W, H, HZ, 1440, 3200, 60f, new float[]{90f, 120f, 144f}));
        // the present mode is already the highest: asked for anyway (the system may idle it lower)
        assertEquals(2, TouchRefresh.boostMode(IDS, W, H, HZ, 1440, 3200, 120f, new float[]{60f, 90f}));
    }

    @Test
    public void noneOnASixtyHertzPanelOrWithoutAlternatives() {
        assertEquals(0, TouchRefresh.boostMode(new int[]{1}, new int[]{1080}, new int[]{2400}, new float[]{60f}, 1080, 2400, 60f,
                new float[0]));
        assertEquals(0, TouchRefresh.boostMode(IDS, W, H, HZ, 1440, 3200, 60f, null));
        assertEquals(0, TouchRefresh.boostMode(IDS, W, H, HZ, 1440, 3200, 60f, new float[0]));
        // another resolution's 144 Hz is never taken
        assertEquals(0, TouchRefresh.boostMode(IDS, W, H, HZ, 1080, 2400, 60f, new float[]{90f, 120f}));
    }
}
