package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P641: the 4x ISZ rows of the Xiaomi 17 Ultra's 75 mm - the stock sensor module's row 12 (it cannot stream) against the 4x ISZ
 * kit's rows 12 / 26 / 29, told apart by the table. The stock table is the owner's device report's; nothing ran on a phone.
 */
public class XiaomiTeleIszTest {
    /** The table with the kit's module: rows 26 and 29 are 4080x3072 at 30 fps (stock: 4080x2560 at 30 / 60). */
    private static int[] kit() {
        final int[] t = XiaomiDualGainTest.TELE.clone();
        for (int row : new int[]{26, 29}) {
            t[2 + 3 * row] = 4080;
            t[3 + 3 * row] = 3072;
            t[4 + 3 * row] = 30;
        }
        return t;
    }

    @Test public void theStockTableIsNotTheKits() {
        assertFalse(XiaomiTeleIsz.kitTable(XiaomiDualGainTest.TELE));
        assertTrue(XiaomiTeleIsz.kitTable(kit()));
        assertFalse(XiaomiTeleIsz.kitTable(null));
        assertFalse("the main camera's table", XiaomiTeleIsz.kitTable(XiaomiDualGainTest.MAIN));
        // the stock rows the kit rebuilds
        assertEquals(2560, SensorModeBand.modeEntry(XiaomiDualGainTest.TELE, 26)[1]);
        assertEquals(60, SensorModeBand.modeEntry(XiaomiDualGainTest.TELE, 29)[2]);
        // the two rows read 1x1: the whole array at 7 fps, and row 12
        assertEquals(16320, SensorModeBand.modeEntry(XiaomiDualGainTest.TELE, 0)[0]);
        assertEquals(7, SensorModeBand.modeEntry(XiaomiDualGainTest.TELE, 0)[2]);
        assertEquals(4080, SensorModeBand.modeEntry(XiaomiDualGainTest.TELE, 12)[0]);
    }

    @Test public void theRowsVerdicts() {
        final int[] stock = XiaomiDualGainTest.TELE, kit = kit();
        assertEquals("row 12 of the stock module never ends its frame", XiaomiTeleIsz.Verdict.STOCK_BROKEN, XiaomiTeleIsz.verdict(true, "4", stock, 12));
        assertEquals("the stock rows 26 / 29 are binned 16:10 rows: a pick as any other", XiaomiTeleIsz.Verdict.OTHER, XiaomiTeleIsz.verdict(true, "4", stock, 26));
        assertEquals(XiaomiTeleIsz.Verdict.OTHER, XiaomiTeleIsz.verdict(true, "4", stock, 29));
        assertEquals(XiaomiTeleIsz.Verdict.KIT_BAYER, XiaomiTeleIsz.verdict(true, "4", kit, 12));
        assertEquals(XiaomiTeleIsz.Verdict.KIT_TETRA, XiaomiTeleIsz.verdict(true, "4", kit, 26));
        assertEquals(XiaomiTeleIsz.Verdict.KIT_TETRA, XiaomiTeleIsz.verdict(true, "4", kit, 29));
        assertEquals("the 2x ISZ row", XiaomiTeleIsz.Verdict.OTHER, XiaomiTeleIsz.verdict(true, "4", kit, 9));
        assertEquals("another camera's row 12", XiaomiTeleIsz.Verdict.OTHER, XiaomiTeleIsz.verdict(true, "2", XiaomiDualGainTest.MAIN, 12));
        assertEquals("another phone", XiaomiTeleIsz.Verdict.OTHER, XiaomiTeleIsz.verdict(false, "4", stock, 12));
    }

    /** Off this phone a pick is never touched (the test's Build is no Xiaomi 17 Ultra). */
    @Test public void anotherPhonesPickStaysAsItIs() {
        final SensorModePick.Pick pick = new SensorModePick.Pick(12, 4080, 3072);
        assertTrue(pick == XiaomiTeleIsz.mark("4", pick, XiaomiDualGainTest.TELE));
        assertEquals(null, XiaomiTeleIsz.mark("4", null, XiaomiDualGainTest.TELE));
        assertEquals(null, XiaomiTeleIsz.directReason("4", null));
    }
}
