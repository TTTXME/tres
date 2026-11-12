package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CameraMetadata;
import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P626: which stream of a nubia maximum-resolution FULLRES session takes which sensor pixel mode and which request targets
 * which stream (NubiaMaxResPlan), against the camera service's rule that every target of a request lists its pixel mode.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class NubiaMaxResPlanTest {
    private static final int DEFAULT = CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT;
    private static final int MAX = CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION;

    @Test public void perShotTheRawAloneIsMaximumResolution() {
        assertArrayEquals(new int[]{MAX}, NubiaMaxResPlan.pixelModes(true, true, NubiaMaxResPlan.Stream.RAW));
        assertArrayEquals(new int[]{DEFAULT}, NubiaMaxResPlan.pixelModes(true, true, NubiaMaxResPlan.Stream.VIEWFINDER));
        assertArrayEquals(new int[]{DEFAULT}, NubiaMaxResPlan.pixelModes(true, true, NubiaMaxResPlan.Stream.OTHER));
    }

    @Test public void allInMaximumResolutionAsBefore() {
        for (NubiaMaxResPlan.Stream s : NubiaMaxResPlan.Stream.values())
            assertArrayEquals(s.name(), new int[]{MAX}, NubiaMaxResPlan.pixelModes(true, false, s));
        assertTrue(NubiaMaxResPlan.repeatingMaxRes(true, false));
        assertTrue(NubiaMaxResPlan.rawInRepeating(false));
        assertTrue(NubiaMaxResPlan.viewfinderInStill(false));
        assertFalse(NubiaMaxResPlan.keepsIspStream(false));
        assertTrue(NubiaMaxResPlan.liveRaw(false, true));
    }

    @Test public void aNormalSessionSetsNoModeAndNoMaximumResolutionKey() {
        // the log of 31256: the flag stayed from a FULLRES session and the normal session's requests were refused
        for (NubiaMaxResPlan.Stream s : NubiaMaxResPlan.Stream.values())
            assertEquals(0, NubiaMaxResPlan.pixelModes(false, false, s).length);
        assertFalse(NubiaMaxResPlan.repeatingMaxRes(false, false));
        assertFalse(NubiaMaxResPlan.repeatingMaxRes(false, true));
        assertEquals(DEFAULT, NubiaMaxResPlan.requestPixelMode(false, false, true));
        assertTrue(NubiaMaxResPlan.consistent(false, false, false));
        assertTrue(NubiaMaxResPlan.consistent(false, false, true));
    }

    @Test public void perShotTheRepeatingRequestIsTheViewfinderInDefault() {
        assertFalse(NubiaMaxResPlan.repeatingMaxRes(true, true));
        assertEquals(DEFAULT, NubiaMaxResPlan.requestPixelMode(true, true, false));
        assertArrayEquals(new NubiaMaxResPlan.Stream[]{NubiaMaxResPlan.Stream.VIEWFINDER}, NubiaMaxResPlan.targets(true, true, false));
        assertFalse("no ZSL ring of 50 MP frames", NubiaMaxResPlan.rawInRepeating(true));
        assertTrue("the ISP viewfinder stays in the session", NubiaMaxResPlan.keepsIspStream(true));
        assertFalse("no live RAW viewfinder", NubiaMaxResPlan.liveRaw(true, true));
    }

    @Test public void perShotTheStillsAreMaximumResolutionOnTheRawAlone() {
        assertEquals(MAX, NubiaMaxResPlan.requestPixelMode(true, true, true));
        assertArrayEquals(new NubiaMaxResPlan.Stream[]{NubiaMaxResPlan.Stream.RAW}, NubiaMaxResPlan.targets(true, true, true));
        assertFalse("the series viewfinder of P90 would be refused", NubiaMaxResPlan.viewfinderInStill(true));
    }

    @Test public void everyRequestOfBothLayoutsPassesTheServicesCheck() {
        for (boolean perShot : new boolean[]{false, true})
            for (boolean still : new boolean[]{false, true})
                assertTrue("perShot " + perShot + " still " + still, NubiaMaxResPlan.consistent(true, perShot, still));
    }

    @Test public void theLogLineSaysWhichStreamRunsWhichMode() {
        final String perShot = NubiaMaxResPlan.describe("2", "«qcfa_snapshot» …", true, new Size(2048, 1536), new Size(8192, 6144), 0x8012);
        assertTrue(perShot, perShot.contains("viewfinder 2048x1536 DEFAULT (repeating)"));
        assertTrue(perShot, perShot.contains("RAW 8192x6144 MAXIMUM_RESOLUTION (stills only"));
        assertTrue(perShot, perShot.contains("repeating DEFAULT -> [viewfinder]"));
        assertTrue(perShot, perShot.contains("stills MAXIMUM_RESOLUTION -> [raw]"));
        assertTrue(perShot, perShot.contains("0x8012") && perShot.contains("qcfa_snapshot"));
        final String all = NubiaMaxResPlan.describe("2", null, false, null, new Size(8192, 6144), 0);
        assertTrue(all, all.contains("repeating MAXIMUM_RESOLUTION -> [raw]") && all.contains("stills MAXIMUM_RESOLUTION -> [raw]"));
    }

    @Test public void aShotFitsThe50MpRingsShotSlots() {
        // 4 N + up to 3 ultrashort + 1 long (HybridPlan.fullResLongFrames) - the 50 MP ring's shot part, 8 slots of 96 MB
        assertEquals(1, HybridPlan.fullResLongFrames(true, 0, false));
        assertTrue(4 + 3 + HybridPlan.fullResLongFrames(true, 0, false) <= NubiaMaxResPlan.SHOT_FRAMES);
        assertTrue("three agreeing frames and a spare", NubiaMaxResPlan.PROOF_FRAMES >= 3);
    }
}
