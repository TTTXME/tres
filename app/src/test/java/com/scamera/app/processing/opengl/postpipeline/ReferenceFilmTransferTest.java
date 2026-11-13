package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ReferenceFilmTransferTest {
    @Test public void largeImagesRespectTheDriverLimitAndTheTransferBudget() {
        assertEquals(256, ReferenceFilm.transferRows(8192, 6144, 256, 128L << 20));
        assertEquals(64, ReferenceFilm.transferRows(32768, 8192, 256, 16L << 20));
        assertEquals(16, ReferenceFilm.transferRows(8192, 6144, 256, 1L << 20));
    }

    @Test public void smallAndPartialTransfersDoNotAllocateUnusedRows() {
        assertEquals(7, ReferenceFilm.transferRows(128, 7, 256, 128L << 20));
        assertEquals(47, ReferenceFilm.transferRows(128, 128, 47, 128L << 20));
    }

    @Test(expected=IllegalArgumentException.class)
    public void impossibleRowsAreRefusedBeforeAllocatingOrDispatching() {
        ReferenceFilm.transferRows(8192, 6144, 256, 65535);
    }
}
