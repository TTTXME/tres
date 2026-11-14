package com.scamera.app.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The OPPO Find X Ultras have OIS on every module although Camera2 lists OFF only (owner, SCAM TEST reports 2026-10-11). */
public class HalHiddenOisTest {
    @Test
    public void oppoFindXUltrasHaveOis() {
        assertTrue(OisSupport.halHiddenOis("OPPO", "PHY110"));
        assertTrue(OisSupport.halHiddenOis("OPPO", "PKJ110"));
        assertTrue(OisSupport.halHiddenOis("oppo", "cph2841"));
        assertTrue(OisSupport.halHiddenOis("OnePlus", "CPH2747"));
    }

    @Test
    public void otherPhonesFollowCamera2() {
        assertFalse(OisSupport.halHiddenOis("Xiaomi", "2201116TG"));
        assertFalse(OisSupport.halHiddenOis("OPPO", "CPH2747"));
        assertFalse(OisSupport.halHiddenOis("OPPO", "PLG110")); // Find X9 Pro: OFF in the templates as well
        assertFalse(OisSupport.halHiddenOis("vivo", "V2454A"));
        assertFalse(OisSupport.halHiddenOis("OPPO", null));
    }
}
