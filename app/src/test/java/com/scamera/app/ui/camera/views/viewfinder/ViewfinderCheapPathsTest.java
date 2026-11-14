package com.scamera.app.ui.camera.views.viewfinder;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** P705: the viewfinder's cheaper paths give what the paths they replace gave. */
public class ViewfinderCheapPathsTest {

    @Test
    public void thePlainProgramDrawsOnlyWhileNothingButTheFrameIsDrawn() {
        assertTrue(MainRenderer.plainDraw(7, 0, false, false, true));
        assertFalse("not linked", MainRenderer.plainDraw(0, 0, false, false, true));
        assertFalse("focus peaking", MainRenderer.plainDraw(7, 1, false, false, true));
        assertFalse("ScamMaster tone grid", MainRenderer.plainDraw(7, 0, true, false, true));
        assertFalse("Oldstyle style", MainRenderer.plainDraw(7, 0, false, true, true));
        assertFalse("lens filter or LUT", MainRenderer.plainDraw(7, 0, false, false, false));
    }

    @Test
    public void theLensFilterIsPlainOnlyWithoutFilterAndWithoutAWantedLut() {
        assertTrue(LiveLensFilter.plain(0, true, 0f, ""));
        assertTrue("no LUT asked for", LiveLensFilter.plain(0, true, 0.8f, ""));
        assertTrue("strength 0", LiveLensFilter.plain(0, true, 0f, "film|x"));
        assertTrue("the RAW viewfinder's filter has no LUT of its own", LiveLensFilter.plain(0, false, 1f, "film|x"));
        assertFalse("a LUT wanted (drawn once loaded)", LiveLensFilter.plain(0, true, 0.5f, "film|x"));
        assertFalse("a lens filter", LiveLensFilter.plain(3, true, 0f, ""));
        assertFalse("the film's diffusion alone", LiveLensFilter.plain(5, false, 0f, ""));
    }

    @Test
    public void theTileThroughItsGainTableIsThePerTexelExpression() {
        final int size = 16;
        final ByteBuffer rgba = ByteBuffer.allocate(size * size * 4);
        for (int i = 0; i < size * size * 4; i++) rgba.put(i, (byte) (i * 37 + 11));
        final float[] reused = new float[size * size * 3];
        float[] table = null;
        for (float gain : new float[]{1f, 2f, 0.6f, 1.7f, 4f, 1f}) {
            table = LiveSceneMeter.gainTable(gain, table);
            final float[] fast = LiveSceneMeter.tileRgb(rgba, size, table, reused);
            final float[] expected = new float[size * size * 3];
            for (int j = 0; j < size; j++)
                for (int i = 0; i < size; i++)
                    for (int c = 0; c < 3; c++) {
                        final int v = rgba.get((j * size + i) * 4 + c) & 0xFF;
                        expected[((size - 1 - j) * size + i) * 3 + c] = gain == 1f ? v / 255f
                                : (float) Math.pow(Math.min(1f, (float) Math.pow(v / 255.0, 2.2) * gain), 1.0 / 2.2);
                    }
            assertArrayEquals("gain " + gain, expected, fast, 0f);
            assertArrayEquals("gain " + gain, expected, LiveSceneMeter.tileRgb(rgba, size, gain), 0f);
        }
        assertEquals("the table is reused", table, LiveSceneMeter.gainTable(3f, table));
    }

    @Test
    public void theWindowHasTheDefaultColourChannelsAndNoDepthOrStencil() {
        // GLSurfaceView's default chooser is 8 / 8 / 8 / 0 with a 16-bit depth buffer; MainRenderer.window8 needs 8 / 8 / 8
        assertArrayEquals(new int[]{8, 8, 8, 0, 0, 0}, GLPreview.WINDOW_CONFIG);
    }
}
