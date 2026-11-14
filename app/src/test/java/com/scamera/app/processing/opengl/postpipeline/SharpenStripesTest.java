package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Point;

import org.junit.Test;

/** P713: WhyLumaSharpen's stripes above 100 MP (tools/check_sharpen_stripes.py proves the plane bit for bit with this halo). */
public class SharpenStripesTest {
    @Test
    public void haloCoversEveryPassRead() {
        // the check's chain: nb 1, half 2 and half 3, three iterations each -> 11 + 1 + 8 + 3 * (4 + 3) + 3 * (6 + 3) = 68 -> 80
        assertEquals(80, WhyLumaSharpen.stripeHalo(1, new int[]{2, 3}, new int[]{3, 3}));
        // no RL stage: the compose pass alone
        assertEquals(32, WhyLumaSharpen.stripeHalo(1, new int[]{0, 0, 0}, new int[]{0, 0, 0}));
        // a PSF half above the shader's 8 counts as 8
        assertEquals(WhyLumaSharpen.stripeHalo(1, new int[]{8}, new int[]{3}), WhyLumaSharpen.stripeHalo(1, new int[]{20}, new int[]{3}));
        assertTrue(WhyLumaSharpen.stripeHalo(2, new int[]{2}, new int[]{3}) % 16 == 0);
    }

    @Test
    public void onlyAboveHundredMegapixelsAndPositionFree() {
        assertFalse(WhyLumaSharpen.stripesWanted(new Point(8192, 6144), false, 0f, false, false));
        assertFalse(WhyLumaSharpen.stripesWanted(new Point(16320, 12288), true, 0f, false, false));   // the guided filter
        assertFalse(WhyLumaSharpen.stripesWanted(new Point(16320, 12288), false, 0.01f, false, false)); // the grain's position hash
        assertFalse(WhyLumaSharpen.stripesWanted(new Point(16320, 12288), false, 0f, true, false));   // V2's clean guide
        assertFalse(WhyLumaSharpen.stripesWanted(new Point(16320, 12288), false, 0f, false, true));   // the neutral film guide
    }

    private static java.util.List<String> glLines(String text) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (String l : text.split("\n")) if (l.contains("shaderProg.")) out.add(l.trim().replace("kn.", ""));
        return out;
    }

    @Test
    public void stripesRunTheFullGridsPassesWithItsUniforms() throws java.io.IOException {
        java.io.File f = new java.io.File("src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyLumaSharpen.java");
        if (!f.exists()) f = new java.io.File("app/" + f.getPath());
        final String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        // issue's full grid: from its compose pass to the R1 tail
        final int i = s.lastIndexOf('\n', s.indexOf("useAssetProgram(\"why/sharp_compose\""));
        final int j = s.indexOf("        if (r1 || lattice) {\n            // P214e R1: S(B)");
        // the stripes' composeAndRl: from its compose pass to its return
        final int m = s.indexOf("    private GLTexture composeAndRl(");
        final int k = s.lastIndexOf('\n', s.indexOf("useAssetProgram(\"why/sharp_compose\"", m));
        final int e = s.indexOf("ratioOut[0] = ratio;", m);
        assertTrue(i > 0 && j > i && m > j && k > m && e > k);
        final java.util.List<String> full = glLines(s.substring(i, j)), stripes = glLines(s.substring(k, e));
        assertTrue(full.size() > 30);
        assertEquals("the same GL calls, in the same order, with the same knobs", full, stripes);
    }
}
