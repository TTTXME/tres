package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** P662: a merge worker started ahead of the merge - up to 16 MP always, up to 100 MP with the memory for it, never at 200 MP. */
@RunWith(RobolectricTestRunner.class)
public class WorkerKeepReadyTest {
    @Test
    public void largeFramesNeedTheMemory() {
        assertTrue("12 MP: always (P371)", ScamNeuralClient.standbyAllowed(4096L * 3072, -1));
        assertTrue("16 MP: always", ScamNeuralClient.standbyAllowed(ScamHybridBurst.CLASSIC_INPUT_PIXELS, 100L << 20));
        assertTrue("50 MP with 4 GB available", ScamNeuralClient.standbyAllowed(8192L * 6144, 4L << 30));
        assertFalse("50 MP with 2 GB available", ScamNeuralClient.standbyAllowed(8192L * 6144, 2L << 30));
        assertFalse("50 MP, memory unknown", ScamNeuralClient.standbyAllowed(8192L * 6144, -1));
        assertFalse("200 MP: its client budget reads the memory first", ScamNeuralClient.standbyAllowed(16384L * 12288, 12L << 30));
    }
}
