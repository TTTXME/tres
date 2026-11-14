package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P695: the Xiaomi 15 Ultra periscope's 4x ISZ rows by the HAL's row map. The table is the one the owner's log of build d082f255
 * printed for camera 5 (2026-11-14); nothing here ran on the phone.
 */
public class XiaomiPeriscopeIszTest {
    static final int[] PERISCOPE = {42, 3, 16320, 12288, 6, 8160, 6144, 24, 4080, 3072, 30, 4080, 3072, 60, 4080, 3072, 30, 4080,
            3072, 30, 4080, 3072, 29, 4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 30, 8160, 6144, 24, 8160, 6144,
            24, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30, 4080, 2304, 30,
            4080, 2304, 30, 4080, 2304, 30, 8160, 4608, 24, 8160, 4608, 24, 4080, 2560, 30, 4080, 2560, 30, 4080, 2560, 30, 4080,
            2560, 30, 4080, 2560, 30, 3808, 2376, 60, 3808, 2376, 60, 3808, 2376, 60, 3808, 2376, 60, 3808, 2376, 60, 4080, 2304,
            120, 7680, 4320, 30, 1920, 1080, 240, 1280, 720, 240, 4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 30, 4080, 2304, 30};

    @Test public void theTableCannotTellTheRowsApart() {
        // rows 2..10 are all the full row's sides over 4: the 4x4 bin (a Bayer) and the 1x1 crop (the 16-cell mosaic) alike
        for (int row = 2; row <= 10; row++) assertEquals(4080, SensorModeBand.modeEntry(PERISCOPE, row)[0]);
        assertEquals("the size rule declares the full-size row only", 4, SensorModePick.declaredBlock(PERISCOPE, 0));
        assertEquals("row 6 gets nothing from the sizes", 0, SensorModePick.declaredBlock(PERISCOPE, 6));
        assertEquals("nor does the binning row 2", 0, SensorModePick.declaredBlock(PERISCOPE, 2));
    }

    @Test public void theRowMapsFourXRowsAreTetra() {
        assertTrue(XiaomiPeriscopeIsz.periscopeTable(PERISCOPE));
        assertEquals("16.4x: the 4x ISZ row", 4, XiaomiPeriscopeIsz.rowBlock(true, PERISCOPE, 6));
        assertEquals("its 16:9 twin", 4, XiaomiPeriscopeIsz.rowBlock(true, PERISCOPE, 17));
        assertEquals("the map's binning row", 0, XiaomiPeriscopeIsz.rowBlock(true, PERISCOPE, 2));
        assertEquals("the other ISZ pair is measured (which one is the 2x is not in the map)", 0, XiaomiPeriscopeIsz.rowBlock(true, PERISCOPE, 4));
        assertEquals(0, XiaomiPeriscopeIsz.rowBlock(true, PERISCOPE, 15));
        assertEquals("the full-size row keeps the size rule", 0, XiaomiPeriscopeIsz.rowBlock(true, PERISCOPE, 0));
        assertEquals("another phone", 0, XiaomiPeriscopeIsz.rowBlock(false, PERISCOPE, 6));
        assertEquals(0, XiaomiPeriscopeIsz.rowBlock(true, null, 6));
    }

    /** P641: the Xiaomi 17 Ultra's 75 mm has the same 16320x12288 array; its table is not this one (its row 2 is a 2x2 bin). */
    @Test public void theSeventeenUltrasTeleIsNotThePeriscope() {
        assertFalse(XiaomiPeriscopeIsz.periscopeTable(XiaomiDualGainTest.TELE));
        assertEquals(0, XiaomiPeriscopeIsz.rowBlock(true, XiaomiDualGainTest.TELE, 6));
        assertEquals(0, XiaomiPeriscopeIsz.rowBlock(true, XiaomiDualGainTest.TELE, 17));
        assertFalse(XiaomiPeriscopeIsz.periscopeTable(XiaomiDualGainTest.MAIN));
    }

    @Test public void theRowMapsBlockOnThePick() {
        final SensorModePick.Pick pick = SensorModePick.of(6, PERISCOPE);
        assertEquals(0, pick.block);
        assertFalse(pick.rowMap);
        final SensorModePick.Pick marked = pick.withRowMapBlock(4);
        assertEquals(4, marked.block);
        assertTrue(marked.rowMap);
        assertEquals(6, marked.mode);
        assertTrue(marked.toString().contains("the row map's block 4"));
        assertTrue("the RAW-only mark keeps it", marked.withoutIsp().rowMap);
        assertEquals(4, marked.withoutIsp().block);
        assertFalse("no row map without a mosaic block", pick.withRowMapBlock(0).rowMap);
        // the 17 Ultra kit's Tetra rows (P641) keep their own declaration, not a row map's
        assertFalse(new SensorModePick.Pick(26, 4080, 3072, false, 4, null, true).rowMap);
    }

    @Test public void theSessionsRowMapBlock() {
        try {
            SensorModePick.session("5", SensorModePick.of(6, PERISCOPE).withRowMapBlock(4));
            assertEquals(4, SensorModePick.rowMapBlock());
            assertEquals(4, SensorModePick.sessionBlock(null));
            SensorModePick.session("5", SensorModePick.of(6, PERISCOPE));
            assertEquals(0, SensorModePick.rowMapBlock());
            SensorModePick.session("5", null);
            assertEquals(0, SensorModePick.rowMapBlock());
        } finally {
            SensorModePick.session(null, null);
        }
    }

    /** Off this phone a pick is never touched (the test's Build is no Xiaomi 15 Ultra). */
    @Test public void anotherPhonesPickStaysAsItIs() {
        final SensorModePick.Pick pick = SensorModePick.of(6, PERISCOPE);
        assertSame(pick, XiaomiPeriscopeIsz.mark("5", pick, PERISCOPE));
        assertEquals(null, XiaomiPeriscopeIsz.mark("5", null, PERISCOPE));
    }
}
