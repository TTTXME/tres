package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P677: V1's P391 edge-aware bands on V2's pyramid are off by default (hybrid_why_v2_edge_bands 0: no BANDS define, V2's passes before
 * P677), WhyV2Fusion takes the BANDS define only with them on, and the shaders declare the uniforms the passes set.
 */
public class WhyV2BandsTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final String SRC = "src/main/java/com/scamera/app/processing/opengl/postpipeline/";
    private static final String SH = "src/main/assets/shaders/why/";

    @Test
    public void offByDefault() throws IOException {
        final Matcher m = Pattern.compile("value\\(\"v2_edge_bands\", ([\\d.]+)f\\)").matcher(read(SRC + "WhyTone.java"));
        assertTrue(m.find());
        assertEquals(0f, Float.parseFloat(m.group(1)), 0f);
        assertNull(WhyTone.edgeBandsV2());
        assertTrue(read(SH + "v2_ef_collapse.glsl").contains("\n#define BANDS 0\n"));
        assertTrue(read(SH + "v2_ef_coarsest.glsl").contains("\n#define BANDS 0\n"));
    }

    @Test
    public void definesOnlyWithTheBands() throws IOException {
        final String f = read(SRC + "WhyV2Fusion.java");
        assertTrue(f.contains("final float[] band = scale == 1 ? WhyTone.edgeBandsV2() : null;"));
        int n = 0;
        for (int i = f.indexOf("shaderProg.setDefine(\"BANDS\", 1);"); i >= 0; i = f.indexOf("shaderProg.setDefine(\"BANDS\", 1);", i + 1)) {
            assertTrue(f.substring(Math.max(0, i - 40), i).contains("if (band != null)"));
            n++;
        }
        assertEquals(2, n);
    }

    @Test
    public void shadersDeclareWhatThePassesSet() throws IOException {
        final String f = read(SRC + "WhyV2Fusion.java");
        final String[][] u = {{"v2_ef_collapse.glsl", "u_tex_GF_fine", "u_tex_GF_coarse", "u_tex_share", "u_band_edge", "u_band_reach"},
                {"v2_ef_coarsest.glsl", "u_tex_GF", "u_tex_share", "u_band_strength"},
                {"v2_ef_bands.glsl", "u_tex_GF", "u_tex_ref", "u_tex_D", "u_tex_fine", "u_band_noise", "u_band_edge", "u_band_reach", "u_band_carry"},
                {"v2_ef_prep.glsl", "u_band_floor"}};
        for (String[] row : u) {
            final String sh = read(SH + row[0]);
            for (int i = 1; i < row.length; i++) {
                assertTrue(row[0] + " " + row[i], sh.contains(" " + row[i] + ";"));
                assertTrue(row[i], f.contains("\"" + row[i] + "\""));
            }
        }
    }
}
