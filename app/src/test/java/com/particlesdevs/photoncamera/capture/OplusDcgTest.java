package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

/**
 * P586: the DCG switch on the OnePlus 16 (PYB110) - the main's row 28 «pro 1x dcg sensor fusion» and the tele's DAG rows 2 / 4
 * «SAT 30fps DAG» of the report's tables; a failed row gives way to the next.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class OplusDcgTest {
    @After public void tearDown() {
        ShadowBuild.reset();
    }

    @Test public void theRowsAreMain28AndTele2Then4() {
        assertArrayEquals(new int[]{28}, OplusDcg.rows(true, "2", OppoNativeFullRes.PYB110_MAIN));
        assertArrayEquals("DAG: row 2, row 4 when 2 failed", new int[]{2, 4}, OplusDcg.rows(true, "4", OppoNativeFullRes.PYB110_TELE));
        assertNull("camera 2 only on the main sensor (not the logical 0)", OplusDcg.rows(true, "0", OppoNativeFullRes.PYB110_MAIN));
        assertNull("the ultra-wide", OplusDcg.rows(true, "3", OppoNativeFullResTest.PYB110_WIDE));
        assertNull("the front", OplusDcg.rows(true, "1", OppoNativeFullResTest.PYB110_FRONT));
        assertNull("another phone", OplusDcg.rows(false, "2", OppoNativeFullRes.PYB110_MAIN));
        assertNull("a table whose row 28 is another size", OplusDcg.rows(true, "2", OppoNativeFullRes.PYB110_TELE));
        // the sizes: 4080x3072 (4:3) and 4096x2560 (16:10 of the 4:3 sensor)
        assertArrayEquals(new int[]{4080, 3072, 30}, SensorModeBand.modeEntry(OppoNativeFullRes.PYB110_MAIN, 28));
        assertArrayEquals(new int[]{4096, 2560, 30}, SensorModeBand.modeEntry(OppoNativeFullRes.PYB110_TELE, 2));
        assertArrayEquals(new int[]{4096, 2560, 30}, SensorModeBand.modeEntry(OppoNativeFullRes.PYB110_TELE, 4));
    }

    @Test public void aFailedRowGivesWayToTheNext() {
        final int[] tele = OplusDcg.rows(true, "4", OppoNativeFullRes.PYB110_TELE);
        assertEquals(2, OplusDcg.live(tele, 0));
        assertEquals(4, OplusDcg.live(tele, 1));
        assertEquals("none left: the switch is gone on the tele", -1, OplusDcg.live(tele, 2));
        assertEquals(-1, OplusDcg.live(null, 0));
        final SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("p586_dcg", 0);
        p.edit().clear().commit();
        OplusDcg.note(p, "4", 1, 2, "no preview frame for 3000 ms", "s1");
        assertEquals(1, OplusDcg.failures(p, "4", "s1"));
        assertEquals(4, OplusDcg.live(tele, OplusDcg.failures(p, "4", "s1")));
        assertEquals("a firmware update tries again", 0, OplusDcg.failures(p, "4", "s2"));
        assertEquals("the main's trials are its own", 0, OplusDcg.failures(p, "2", "s1"));
    }

    /** The DCG / DAG pick keeps the ISP stream as «Поток ISP» has it and declares no block (the merge measures the binned Bayer). */
    @Test public void theDcgPickIsNoIszRoute() {
        final SensorModePick.Pick dag = new SensorModePick.Pick(2, 4096, 2560, false, 0, SensorModePick.DCG);
        assertTrue(dag.dcg());
        assertFalse(dag.rawOnly());
        assertEquals(0, dag.block);
        assertFalse(SensorModePick.ispStreamOff(0, dag, false, false));
        assertTrue(dag.toString().contains("oplus DCG"));
    }

    @Test public void theTeleSaysDag() {
        assertTrue(OplusDcg.name(true).contains("DAG"));
        assertEquals("DCG", OplusDcg.name(false));
        ShadowBuild.setManufacturer("OnePlus");
        ShadowBuild.setModel("PYB110");
        assertTrue(OplusDcg.device());
        ShadowBuild.setModel("CPH2747");
        assertFalse("the OnePlus 15: not this route", OplusDcg.device());
        ShadowBuild.setManufacturer("Xiaomi");
        assertFalse(OplusDcg.device());
    }
}
