package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FilmPreviewExposureTest {
    @Test public void referenceFilmReceivesSceneLightWithoutWhyExposure() {
        for (float ae : new float[]{.25f, .7f, 1, 2.3f, 8}) for (float scale : new float[]{.5f, 1, 4})
            assertEquals(1, ae * scale * LiveWhyTone.filmExposure(true, ae, scale, 3), 1e-6f);
    }

    @Test public void legacyPreviewExposureIsUnchangedUntilReferenceTableArrives() {
        assertEquals(2.7f * com.scamera.app.processing.color.FilmSim.METER_TO_FILM,
                LiveWhyTone.filmExposure(false, 8, .5f, 2.7f), 0);
    }
}
