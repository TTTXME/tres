package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P597: the VF_RAW "stages" line - mean / max of each stage over the window, the threads, the gaps and the late ones. */
public class VfStagesTest {
    @Test public void theLineNamesEveryStageWithItsMeanAndMaxAndStartsANewWindow() throws Exception {
        VfStages.reset();
        final Thread camera = new Thread(() -> {
            VfStages.arrived(1_000_000_000L, 60_000_000L);
            VfStages.arrived(1_033_000_000L, 61_000_000L);
            VfStages.arrived(1_100_000_000L, 75_000_000L);
            VfStages.note(VfStages.Stage.RING, 3_000_000L);
            VfStages.note(VfStages.Stage.RING, 5_000_000L);
            VfStages.note(VfStages.Stage.CAMERA, 4_000_000L);
            VfStages.note(VfStages.Stage.MOTION, 1_500_000L);
            VfStages.note(VfStages.Stage.WB_COPY, 3_500_000L);
        }, "CameraBackground");
        final Thread wb = new Thread(() -> {
            VfStages.note(VfStages.Stage.WB, 90_000_000L);
            VfStages.note(VfStages.Stage.WB, 110_000_000L);
        }, "SCAMERA-vf-wb");
        wb.start();
        wb.join();
        camera.start();
        camera.join();
        final Thread hand = new Thread(() -> {
            VfStages.note(VfStages.Stage.BIN, 2_500_000L);
            VfStages.note(VfStages.Stage.HANDOFF, 3_000_000L);
            VfStages.source("4080x3072 stride 8160 fmt 32 block 4 -> cell means 1020x768 swar");
        }, "SCAMERA-vf-handoff");
        hand.start();
        hand.join();
        final Thread gl = new Thread(() -> {
            VfStages.note(VfStages.Stage.WAIT, 1_000_000L);
            VfStages.shown(2_000_000_000L);
            VfStages.shown(2_033_000_000L);
            VfStages.shown(2_100_000_000L); // 67 ms: late
            VfStages.note(VfStages.Stage.UPLOAD, 800_000L);
            VfStages.note(VfStages.Stage.METER, 1_200_000L);
            VfStages.note(VfStages.Stage.DRAW, 6_000_000L);
            VfStages.note(VfStages.Stage.DRAW, 10_000_000L);
        }, "GLThread 7");
        gl.start();
        gl.join();
        VfStages.handoffBusy();
        assertEquals("stages ms mean/max: cam[CameraBackground] 4.0/4.0 ring 4.0/5.0 motion 1.5/1.5 wb copy 3.5/3.5 arrive 50.0/67.0"
                        + " lag spread 15.0 | wb[SCAMERA-vf-wb] 100.0/110.0 x2 | hand-off[SCAMERA-vf-handoff] 3.0/3.0 bin[SCAMERA-vf-handoff] 2.5/2.5 busy 1"
                        + " | gl[GLThread 7] wait 1.0/1.0 upload 0.8/0.8 meter 1.2/1.2 x1 draw 8.0/10.0 shown 50.0/67.0 late 1"
                        + " | 4080x3072 stride 8160 fmt 32 block 4 -> cell means 1020x768 swar",
                VfStages.take());
        // a new window: nothing noted yet, the threads and the frame's description stay
        final String empty = VfStages.take();
        assertTrue(empty, empty.startsWith("stages ms mean/max: cam[CameraBackground] - ring - motion - wb copy - arrive - lag spread -"
                + " | wb[SCAMERA-vf-wb] - x0"));
        assertTrue(empty, empty.contains("draw - shown - late 0"));
        // the GPU's time only when it was measured (scam_dev vf_stage_gpu 1)
        VfStages.note(VfStages.Stage.GPU, 7_000_000L);
        assertTrue(VfStages.take().contains(" gpu 7.0/7.0 shown"));
        VfStages.reset();
        assertTrue(VfStages.take().endsWith("| no frame"));
    }

    @Test public void aPauseIsNoGap() {
        VfStages.reset();
        VfStages.shown(1_000_000_000L);
        VfStages.shown(5_000_000_000L); // the viewfinder was off for 4 s
        VfStages.arrived(1_000_000_000L, Long.MIN_VALUE);
        VfStages.arrived(9_000_000_000L, Long.MIN_VALUE);
        final String line = VfStages.take();
        assertTrue(line, line.contains("arrive - lag spread -"));
        assertTrue(line, line.contains("shown - late 0"));
        VfStages.reset();
    }
}
