package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.*;

public class ResolveMemoryPolicyTest {
    @Test public void fullResolutionNeverSilentlySkipsResolve() {
        for (int[] size : new int[][]{{4096,3072},{8192,6144},{16320,12288},{16320,12256}}) {
            int rows = WhyResolveDenoise.stripRows(size[0], size[1]);
            assertTrue(rows > 0 && rows <= size[1]);
            long temporary = (long) size[0] * Math.min(size[1], rows + 14) * 16;
            assertTrue("Resolve temporary textures exceed budget", temporary <= (128L << 20));
        }
    }

    @Test public void largePyramidStripsPreserveItsThreeLevelGrid() {
        for (int[] size : new int[][]{{8192,6144},{16320,12288},{16320,12256}}) {
            int rows = WhyMsDenoise.stripRows(size[0], size[1], 0);
            assertTrue(rows >= 8 && rows < size[1]);
            assertEquals(0, rows % 8);
            assertTrue("Pyramid peak estimate exceeds budget", (long) size[0] * (rows + 128) * 40 <= (256L << 20));
        }
        assertEquals(3072, WhyMsDenoise.stripRows(4096,3072,0));
        assertEquals("An odd grid keeps its original sampling geometry", 4097, WhyMsDenoise.stripRows(3073,4097,128));
    }
}
