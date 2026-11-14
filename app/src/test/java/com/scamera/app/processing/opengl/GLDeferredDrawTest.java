package com.scamera.app.processing.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P713: the lines of the post's last texture an output tile of RotateWatermark/addwatermark_rotate reads (GLDeferredDraw.sourceLines)
 * against the shader's own index arithmetic run for every pixel of the tile: the deferred pass draws exactly those lines before it.
 */
public class GLDeferredDrawTest {
    /** addwatermark_rotate.glsl's texelFetch index for output pixel (x, y): {at.x, at.y}. */
    private static int[] shaderAt(int x, int y, int rotate, boolean mirror, int texW, int texH, int cropH, int rawH) {
        final int lastX = texW - 1, lastY = texH - 1;
        final int skipped = rawH - cropH;
        int px = x, py = y;
        if (rotate == 1) {
            px += skipped;
            if (mirror) px = cropH - 1 - px;
            return new int[]{lastX - py, px};
        } else if (rotate == 2) {
            if (mirror) py = lastY - py;
            return new int[]{lastX - px, lastY - py};
        } else if (rotate == 3) {
            if (mirror) px = cropH - 1 - px;
            return new int[]{py, lastY - px};
        }
        py += skipped;
        if (mirror) py = lastY - py;
        return new int[]{px, py};
    }

    @Test
    public void tilesReadTheLinesTheDeferredPassDrawsFirst() {
        final int texW = 40, texH = 28, tile = 7;
        for (int rotate = 0; rotate < 4; rotate++) {
            for (int m = 0; m < 2; m++) {
                final boolean mirror = m == 1;
                for (int skipped = 0; skipped <= (rotate % 2 == 0 ? 3 : 0); skipped += 3) {
                    final boolean turned = rotate % 2 == 1;
                    // the picture: the texture turned (and the crop's skipped rows off the sensor's height)
                    final int outW = turned ? texH - skipped : texW, outH = turned ? texW : texH - skipped;
                    final int cropH = texH - skipped;
                    final boolean[] covered = new boolean[turned ? texW : texH];
                    for (int y0 = 0; y0 < outH; y0 += tile) {
                        final int y1 = Math.min(outH, y0 + tile);
                        final int[] lines = GLDeferredDraw.sourceLines(rotate, mirror, y0, y1, texW, texH, skipped);
                        assertEquals("rotate " + rotate + ": columns", turned ? 1 : 0, lines[2]);
                        for (int y = y0; y < y1; y++) {
                            for (int x = 0; x < outW; x++) {
                                final int[] at = shaderAt(x, y, rotate, mirror, texW, texH, cropH, texH);
                                final int line = turned ? at[0] : at[1];
                                assertTrue("rotate " + rotate + " mirror " + mirror + " skipped " + skipped + " pixel " + x + "," + y
                                                + " reads line " + line + " outside [" + lines[0] + ", " + lines[1] + ")",
                                        line >= lines[0] && line < lines[1]);
                                covered[line] = true;
                            }
                        }
                        // no more lines than the tile reads (the deferred pass draws ahead of the tiles by no more than needed)
                        assertTrue(lines[1] - lines[0] <= y1 - y0);
                    }
                }
            }
        }
    }
}
