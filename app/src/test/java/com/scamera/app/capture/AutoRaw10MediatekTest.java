package com.scamera.app.capture;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** P562: «auto» keeps RAW_SENSOR on a MediaTek SoC (no RAW10 there). */
public class AutoRaw10MediatekTest {
    @Test public void mediatekIsToldBySocOrHardwareName() {
        assertTrue(CaptureController.mediatek("Mediatek", "mt6989", "k6989v1_64"));
        assertTrue(CaptureController.mediatek(null, "mt6991", null));
        assertTrue(CaptureController.mediatek(null, null, "mt6985"));
        assertFalse(CaptureController.mediatek("QTI", "qcom", "sun"));
        assertFalse(CaptureController.mediatek("Google", "tensor", "comet"));
        assertFalse(CaptureController.mediatek(null, null, null));
    }
}
