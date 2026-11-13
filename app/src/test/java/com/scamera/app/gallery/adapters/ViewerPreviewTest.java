package com.scamera.app.gallery.adapters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P620: the viewer's preview stands for the page only with the picture's aspect (the view stretches it to the picture). */
public class ViewerPreviewTest {
    @Test
    public void previewNeedsThePicturesAspect() {
        assertTrue(ViewerPreview.sameAspect(478, 360, 8160, 6144));
        assertTrue(ViewerPreview.sameAspect(360, 478, 6144, 8160));
        assertFalse("a turned thumbnail", ViewerPreview.sameAspect(360, 478, 8160, 6144));
        assertFalse(ViewerPreview.sameAspect(360, 360, 8160, 6144));
        assertFalse(ViewerPreview.sameAspect(0, 360, 8160, 6144));
    }
}
