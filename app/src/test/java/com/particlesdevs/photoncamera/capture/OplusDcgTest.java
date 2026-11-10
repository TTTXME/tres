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
 * «SAT 30fps DAG» of the report's tables; a failed row gives way to the next. P593: the owner's log of 2026-11-09 - row 28 delivered
 * 14 bit (dynamic black 1023.56, white 16368), the tele's row 2 plain 10 bit (black 64.1, white 1023): a row proves its depth or
 * gives way, the tele's candidates are a ladder, the DCG ISO is not taken as inflated on this phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class OplusDcgTest {
    @After public void tearDown() {
        ShadowBuild.reset();
    }

    @Test public void theRowsAreMain28Then38AndTheTelesLadder() {
        assertArrayEquals("DCG: row 28, its PDAF-short twin 38 after it", new int[]{28, 38}, OplusDcg.rows(true, "2", OppoNativeFullRes.PYB110_MAIN));
        assertArrayEquals("DAG: the unnamed 4:3 rows, the map's DAG rows, the unnamed 16:10 rows", new int[]{17, 8, 2, 4, 14, 15},
                OplusDcg.rows(true, "4", OppoNativeFullRes.PYB110_TELE));
        assertNull("camera 2 only on the main sensor (not the logical 0)", OplusDcg.rows(true, "0", OppoNativeFullRes.PYB110_MAIN));
        assertNull("the ultra-wide", OplusDcg.rows(true, "3", OppoNativeFullResTest.PYB110_WIDE));
        assertNull("the front", OplusDcg.rows(true, "1", OppoNativeFullResTest.PYB110_FRONT));
        assertNull("another phone", OplusDcg.rows(false, "2", OppoNativeFullRes.PYB110_MAIN));
        assertNull("a table whose row 28 is another size", OplusDcg.rows(true, "2", OppoNativeFullRes.PYB110_TELE));
        // the sizes: 4080x3072 (4:3) and 4096x2560 (16:10 of the 4:3 sensor)
        assertArrayEquals(new int[]{4080, 3072, 30}, SensorModeBand.modeEntry(OppoNativeFullRes.PYB110_MAIN, 28));
        assertArrayEquals(new int[]{4096, 2560, 30}, SensorModeBand.modeEntry(OppoNativeFullRes.PYB110_TELE, 2));
        assertArrayEquals(new int[]{4096, 2560, 30}, SensorModeBand.modeEntry(OppoNativeFullRes.PYB110_TELE, 4));
        assertArrayEquals(new int[]{4096, 3072, 30}, SensorModeBand.modeEntry(OppoNativeFullRes.PYB110_TELE, 17));
        assertArrayEquals(new int[]{4080, 3072, 30}, SensorModeBand.modeEntry(OppoNativeFullRes.PYB110_MAIN, 38));
        // a main table without row 28 offers no DCG (row 38 alone is not the route)
        final int[] no28 = OppoNativeFullRes.PYB110_MAIN.clone();
        no28[2 + 28 * 3] = 1920;
        assertNull(OplusDcg.rows(true, "2", no28));
        assertTrue(OplusDcg.fourByThree(4080, 3072));
        assertTrue(OplusDcg.fourByThree(4096, 3072));
        assertFalse(OplusDcg.fourByThree(4096, 2560));
        assertEquals("no row forced by default", -1, OplusDcg.forcedRow());
    }

    @Test public void aFailedRowGivesWayToTheNext() {
        final int[] tele = OplusDcg.rows(true, "4", OppoNativeFullRes.PYB110_TELE);
        assertEquals(17, OplusDcg.live(tele, 0));
        assertEquals(8, OplusDcg.live(tele, 1));
        assertEquals(2, OplusDcg.live(tele, 2));
        assertEquals(15, OplusDcg.live(tele, 5));
        assertEquals("none left: the switch is gone on the tele", -1, OplusDcg.live(tele, 6));
        assertEquals(-1, OplusDcg.live(null, 0));
        final SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("p586_dcg", 0);
        p.edit().clear().commit();
        OplusDcg.note(p, "4", 1, 17, "no preview frame for 3000 ms", "s1");
        assertEquals(1, OplusDcg.failures(p, "4", "s1"));
        assertEquals(8, OplusDcg.live(tele, OplusDcg.failures(p, "4", "s1")));
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

    /**
     * P593: the depth a session's frames prove - the main's row 28 by its dynamic black (64 -> 1023.56: 4 steps, 14 bit) and white
     * 16368, the samples; the tele's row 2 (black 64.1, white 1023, no sample above 1023) nothing.
     */
    @Test public void theDepthIsProvenByTheLevelsOrTheSamples() {
        final float[] row28 = {1023.5625f, 1023.5625f, 1024.5625f, 1024.5f}, row2 = {64.140625f, 64.14844f, 64.203125f, 64.19922f};
        assertEquals("the main's row 28: black and white say 14", 14, OplusDcg.provenBits(1023, 64f, row28, 16368, 0));
        assertEquals("its black alone", 14, OplusDcg.provenBits(1023, 64f, row28, 0, 0));
        assertEquals("its samples alone (RawPayloadCheck)", 14, OplusDcg.provenBits(1023, 64f, null, 1023, 14));
        assertEquals("a 12-bit black", 12, OplusDcg.provenBits(1023, 64f, new float[]{256, 256, 256, 256}, 0, 0));
        assertEquals("the tele's row 2: nothing above 10 bit", 0, OplusDcg.provenBits(1023, 64f, row2, 1023, 0));
        assertEquals("a black that only drifts", 0, OplusDcg.provenBits(1023, 64f, new float[]{60.6f, 60.6f, 61, 61}, 0, 0));
        assertEquals("a black between the steps", 0, OplusDcg.provenBits(1023, 64f, new float[]{180, 180, 180, 180}, 0, 0));
        assertEquals("no levels at all", 0, OplusDcg.provenBits(1023, 64f, null, 0, 0));
    }

    @Test public void aRowWithoutWiderFramesFailsAfterItsResults() {
        final OplusDcg.DepthWatch dag = new OplusDcg.DepthWatch("DAG", "4", 2, 1023, 64f);
        final float[] plain = {64.1f, 64.1f, 64.2f, 64.2f};
        for (int i = 0; i < OplusDcg.DepthWatch.FRAMES; i++) dag.frame();
        assertNull("another mode reported: the mode watch's matter", dag.observe(7, plain, 1023, 0));
        for (int i = 0; i < OplusDcg.DepthWatch.RESULTS - 1; i++) assertNull(dag.observe(2, plain, 1023, 0));
        assertNull("not judged yet", dag.failure());
        assertNull(dag.observe(2, plain, 1023, 0));
        final String why = dag.failure();
        assertTrue(why, why.startsWith("the row's frames stay 10 bit: no dual-gain RAW"));
        assertEquals("a code of its own", FullResTrials.NOT_WIDE, FullResTrials.code(why));
        assertNull("said once", dag.failure());
        assertNull("settled", dag.observe(2, plain, 1023, 14));

        final OplusDcg.DepthWatch dcg = new OplusDcg.DepthWatch("DCG", "2", 28, 1023, 64f);
        final String line = dcg.observe(28, new float[]{1023.56f, 1023.56f, 1024.56f, 1024.5f}, 16368, 0);
        assertTrue(line, line.startsWith("OPPO family DCG camera 2 row 28: 14 bit RAW proven"));
        assertEquals(14, dcg.proven());
        assertNull(dcg.failure());

        final OplusDcg.DepthWatch starved = new OplusDcg.DepthWatch("DAG", "4", 17, 1023, 64f);
        String said = null;
        for (int i = 0; i < OplusDcg.DepthWatch.RESULTS; i++) said = starved.observe(null, null, 0, 0);
        assertTrue("too few frames sampled: not judged, no failure: " + said, said.contains("the depth is not judged"));
        assertNull(starved.failure());
    }

    /** P593: a plain row while a candidate is left keeps the switch on (the restart takes it); the last one turns it off. */
    @Test public void aPlainRowGivesWayAtOnceTheLastTurnsTheSwitchOff() {
        ShadowBuild.setManufacturer("OnePlus");
        ShadowBuild.setModel("PYB110");
        final String why = "the row's frames stay 10 bit: no dual-gain RAW (24 results)";
        final String first = OplusDcg.failed("4", 17, why);
        assertTrue(first, first.contains("17"));
        assertTrue("the next row now", OplusDcg.searching());
        final String died = OplusDcg.failed("4", 8, "camera error 4");
        assertFalse("a dead row: the switch off until the next time", OplusDcg.searching());
        assertTrue(died, died.contains("8"));
        assertEquals(FullResTrials.DIED, FullResTrials.code("camera error 4"));
    }

    /** P593: the DCG frame's codes - real 14-bit data has codes off the 16-code grid, 10-bit data x16 none. */
    @Test public void theDcgFramePrecisionTellsX16From14Bit() {
        final int w = 64, h = 40;
        final java.nio.ByteBuffer x16 = java.nio.ByteBuffer.allocate(w * h * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        final java.nio.ByteBuffer fine = java.nio.ByteBuffer.allocate(w * h * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < w * h; i++) {
            x16.putShort(i * 2, (short) (1024 + 16 * (i % 50)));
            fine.putShort(i * 2, (short) (1024 + 3 * i % 2000));
        }
        assertEquals(0f, OplusDcg.offGridPercent(x16, w, h, w * 2), 1e-6f);
        assertTrue(OplusDcg.offGridPercent(fine, w, h, w * 2) > 50f);
        assertEquals(-1f, OplusDcg.offGridPercent(x16, w, h + 1, w * 2), 1e-6f);
        assertEquals(-1f, OplusDcg.offGridPercent(null, w, h, w * 2), 1e-6f);
        ShadowBuild.setManufacturer("OnePlus");
        ShadowBuild.setModel("PYB110");
        assertTrue(OplusDcg.precisionLine(x16, w, h, w * 2, 16368).contains("10-bit data in a 14-bit container"));
        assertTrue(OplusDcg.precisionLine(fine, w, h, w * 2, 16368).contains("real 14-bit codes"));
        assertNull("a 10-bit frame: no line", OplusDcg.precisionLine(x16, w, h, w * 2, 1023));
        assertFalse("the PYB110's DCG ISO is not inflated", OplusDcg.isoInflated());
        ShadowBuild.setModel("CPH2747");
        assertNull("not this phone", OplusDcg.precisionLine(x16, w, h, w * 2, 16368));
        assertTrue(OplusDcg.isoInflated());
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
