package com.scamera.app.capture;

import android.util.Size;

import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** The still size a stream's size list gives (CaptureController.stillSize) and the area order behind it. */
public class CaptureControllerTest {

    private static Size size(int w, int h) {
        Size s = Mockito.mock(Size.class);
        Mockito.when(s.getWidth()).thenReturn(w);
        Mockito.when(s.getHeight()).thenReturn(h);
        return s;
    }

    @Test
    public void largestSizeUpToThirtyMegapixelsWins() {
        Size fhd = size(1920, 1080);
        Size[] list = {size(1280, 720), fhd, size(1600, 900)};
        assertSame(fhd, CaptureController.stillSize(list, null));
        assertSame(fhd, CaptureController.stillSize(list, size(640, 480)));
    }

    @Test
    public void aSizeAboveTheBoundGivesWayToTheNextOneDown() {
        Size fhd = size(1920, 1080);
        Size[] list = {fhd, size(1280, 720), size(8000, 4500)};
        assertSame(fhd, CaptureController.stillSize(list, null));
        // the next one down is taken even when it is above the bound too (a 200 MP sensor's 50 MP binned size)
        Size binned = size(8160, 6144);
        assertSame(binned, CaptureController.stillSize(new Size[]{size(16320, 12288), binned}, null));
    }

    @Test
    public void emptyOrSingleOversizeListGivesTheFallback() {
        Size fallback = size(1280, 720);
        assertNull(CaptureController.stillSize(new Size[]{size(8000, 4500)}, null));
        assertSame(fallback, CaptureController.stillSize(new Size[]{size(8000, 4500)}, fallback));
        assertSame(fallback, CaptureController.stillSize(new Size[0], fallback));
        assertSame(fallback, CaptureController.stillSize(null, fallback));
        assertNull(CaptureController.stillSize(null, null));
    }

    @Test
    public void exactlyThirtyMegapixelsStillCounts() {
        Size bound = size(6000, 5000);
        assertSame(bound, CaptureController.stillSize(new Size[]{size(4000, 3000), bound}, null));
    }

    @Test
    public void equalAreasKeepTheListOrderAndTheListIsUntouched() {
        Size wide = size(4000, 3000);
        Size tall = size(3000, 4000);
        Size[] list = {wide, tall};
        assertSame(tall, CaptureController.stillSize(list, null));
        assertArrayEquals(new Size[]{wide, tall}, list);
    }

    @Test
    public void areaOrderDoesNotOverflow() {
        Size huge = size(50000, 50000);
        Size small = size(2, 2);
        assertEquals(1, Integer.signum(CaptureController.BY_AREA.compare(huge, small)));
        assertEquals(-1, Integer.signum(CaptureController.BY_AREA.compare(small, huge)));
        assertEquals(0, CaptureController.BY_AREA.compare(size(4, 9), size(6, 6)));
    }
}
