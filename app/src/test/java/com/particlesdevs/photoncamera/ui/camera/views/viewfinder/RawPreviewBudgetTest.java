package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RawPreviewBudgetTest {
    @Test public void idleAndSmallTargetsKeepTheirOriginalGeometry() {
        assertArrayEquals(new int[]{1440, 1920}, RawPreviewBudget.size(1440, 1920, false));
        assertArrayEquals(new int[]{450, 600}, RawPreviewBudget.size(450, 600, true));
        assertArrayEquals(new int[]{1, 1}, RawPreviewBudget.size(0, 0, true));
    }

    @Test public void theFirstBusyTargetFitsTheBudgetInBothOrientations() {
        assertArrayEquals(new int[]{750, 1000}, RawPreviewBudget.size(1440, 1920, true));
        assertArrayEquals(new int[]{1000, 750}, RawPreviewBudget.size(1920, 1440, true));
    }

    @Test public void theTargetNeverUpscalesOrChangesTheViewportAspectBeyondRounding() {
        Random random = new Random(465);
        for (int n = 0; n < 10_000; n++) {
            int w = 1 + random.nextInt(32768), h = 1 + random.nextInt(32768);
            int[] size = RawPreviewBudget.size(w, h, true);
            assertTrue(size[0] > 0 && size[0] <= w && size[1] > 0 && size[1] <= h);
            assertTrue((long) size[0] * size[1] <= RawPreviewBudget.MAX_BUSY_PIXELS);
            double scale = Math.min(1, Math.sqrt(RawPreviewBudget.MAX_BUSY_PIXELS / ((double) w * h)));
            assertEquals(w * scale, size[0], 1);
            assertEquals(h * scale, size[1], 1);
        }
    }

    @Test public void repeatedDrawsAndBusyIdleTransitionsDoNotFeedBackIntoTheSize() {
        int[] viewport = {1440, 1920};
        for (int n = 0; n < 100; n++) {
            assertArrayEquals(new int[]{750, 1000}, RawPreviewBudget.size(viewport[0], viewport[1], true));
            assertArrayEquals(viewport, RawPreviewBudget.size(viewport[0], viewport[1], false));
        }
    }

    @Test public void degenerateWideTargetsStillStayWithinTheBudget() {
        assertArrayEquals(new int[]{750_000, 1}, RawPreviewBudget.size(Integer.MAX_VALUE, 1, true));
        assertArrayEquals(new int[]{1, 750_000}, RawPreviewBudget.size(1, Integer.MAX_VALUE, true));
    }
}
