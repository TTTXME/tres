package com.scamera.app.processing.opengl;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P662 (items 3, 4): the final readback's ring of pixel-pack buffers - every tile drawn and copied exactly once, a buffer read
 * into again only after its tile was copied, a tile mapped only once the next tiles' read-backs are queued - and the idle
 * warm-up's key -> source of the programs it links into the shared context (the source useShader looks them up by).
 */
public class P662ReadbackAndWarmUpTest {
    @Test public void theRingDrawsAndCopiesEveryTileOnceAndNeverOverwritesABufferBeforeItsCopy() {
        for (int ring = 1; ring <= 3; ring++) {
            for (int tiles = 1; tiles <= 50; tiles++) {
                final List<int[]> steps = GLCoreBlockProcessing.ringSteps(tiles, ring);
                assertEquals(2 * tiles, steps.size());
                final int[] drawnAt = new int[tiles], copiedAt = new int[tiles];
                java.util.Arrays.fill(drawnAt, -1);
                java.util.Arrays.fill(copiedAt, -1);
                final int[] heldBy = new int[ring];
                java.util.Arrays.fill(heldBy, -1);
                int drawn = 0, copied = 0;
                for (int i = 0; i < steps.size(); i++) {
                    final int tile = steps.get(i)[0], kind = steps.get(i)[1];
                    final int buffer = tile % ring;
                    if (kind == 0) {
                        assertEquals("tiles drawn in order", drawn++, tile);
                        assertEquals("buffer " + buffer + " still holds an uncopied tile", -1, heldBy[buffer]);
                        heldBy[buffer] = tile;
                        drawnAt[tile] = i;
                    } else {
                        assertEquals("tiles copied in order", copied++, tile);
                        assertEquals(tile, heldBy[buffer]);
                        heldBy[buffer] = -1;
                        copiedAt[tile] = i;
                        // mapped only after the next ring - 1 read-backs are queued, or all of them are
                        assertTrue(drawn >= Math.min(tiles, tile + ring));
                    }
                }
                for (int t = 0; t < tiles; t++) assertTrue(drawnAt[t] >= 0 && copiedAt[t] > drawnAt[t]);
            }
        }
    }

    @Test public void theRingIsForFramesAboveSixteenMegapixelsWithTwoBuffersAbove100() {
        assertFalse(GLCoreBlockProcessing.ringReadbackWanted(4096, 3072));
        assertFalse("16 MP takes the one-buffer path", GLCoreBlockProcessing.ringReadbackWanted(4000, 4000));
        assertEquals(3, GLCoreBlockProcessing.ringSize(8192L * 6144));
        assertEquals(2, GLCoreBlockProcessing.ringSize(16384L * 12288));
    }

    @Test public void theWarmUpLinksAProgramUnderTheSourceUseShaderLooksFor() {
        final String compute = "#version 310 es\nvoid main(){}\n", fragment = "#version 310 es\nout vec4 o;void main(){o=vec4(1);}\n";
        assertEquals(compute, GLProg.shaderOfKey(GLProg.programKey(compute, true)));
        assertEquals(fragment, GLProg.shaderOfKey(GLProg.programKey(fragment, false)));
        assertNull("a viewfinder key is not a post program", GLProg.shaderOfKey(GLProg.viewfinderKey("a", "b")));
        assertNull("another vertex shader", GLProg.shaderOfKey("F\nvoid main(){}\n" + fragment));
        assertNull(GLProg.shaderOfKey(null));
    }
}
