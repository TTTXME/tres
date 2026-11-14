package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P673: the owner's joint bilateral upsampling in the V1 / V2 pyramids' collapse (exp(-(g_coarse - g_fine)^2 / 2 sigma_r^2) on the 4
 * coarse texels) is off by default (sigma 0: no JBU define, the shaders before P673), its passes take the define only with a sigma
 * above 0, the guide settings map onto the shaders' JBU 1 / 2 and log floor, and the shaders declare the uniforms the passes set.
 */
public class WhyJbuOwnerTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final String SRC = "src/main/java/com/scamera/app/processing/opengl/postpipeline/";
    private static final String SH = "src/main/assets/shaders/why/";

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    @Test
    public void offByDefault() throws IOException {
        final String tone = read(SRC + "WhyTone.java");
        // P673c: V1 off, V2 on at the owner's 0.1
        String[] keys = {"fusion_jbu_sigma", "v2_jbu_sigma"};
        float[] defaults = {0f, 0.1f};
        for (int i = 0; i < keys.length; i++) {
            Matcher m = Pattern.compile("value\\(\"" + keys[i] + "\", ([\\d.]+)f\\)").matcher(tone);
            assertTrue(keys[i], m.find());
            assertEquals(keys[i] + " default", defaults[i], Float.parseFloat(m.group(1)), 0f);
        }
        assertTrue(read(SH + "lpyr.glsl").contains("\n#define JBU 0\n"));
        assertTrue(read(SH + "v2_ef_collapse.glsl").contains("\n#define JBU 0\n"));
    }

    @Test
    public void guidesMapOntoTheShaders() {
        assertEquals(1, WhyTone.jbuDefine(1));
        assertEquals(2, WhyTone.jbuDefine(2));
        assertEquals(1, WhyTone.jbuDefine(3));
        assertEquals(2, WhyTone.jbuDefine(4));
        assertEquals(0f, WhyTone.jbuLog(1), 0f);
        assertEquals(0f, WhyTone.jbuLog(2), 0f);
        assertEquals(WhyTone.JBU_LOG_FLOOR, WhyTone.jbuLog(3), 0f);
        assertEquals(WhyTone.JBU_LOG_FLOOR, WhyTone.jbuLog(4), 0f);
        assertTrue(WhyTone.JBU_LOG_FLOOR > 0f);
    }

    @Test
    public void v1SetsWhatTheShaderDeclares() throws IOException {
        final String f = read(SRC + "WhyFusion.java");
        assertTrue(f.contains("final float jbu = WhyTone.jbuSigma();"));
        // the MODE 4 passes and the MODE 5 pass: the define and the uniform, each only with a sigma
        assertEquals(2, count(f, "if (jbu > 0f) shaderProg.setDefine(\"JBU\", WhyTone.jbuDefine(jbuGuide));"));
        assertEquals(2, count(f, "if (jbu > 0f) shaderProg.setVar(\"jbuU\", jbu, WhyTone.jbuLog(jbuGuide));"));
        final String sh = read(SH + "lpyr.glsl");
        assertTrue(sh.contains("uniform vec2 jbuU;"));
        assertTrue(sh.contains("#if JBU == 1\n    Output = vec4(level4(xy, jbuOf(xy, texelFetch(LevelL, xy, 0))), 0.0, 0.0, 1.0);"));
        assertTrue(sh.contains("#if JBU == 1\n    float fusedL = level5(xy, I, p, L, jbuOf(xy, L));"));
    }

    @Test
    public void v2SetsWhatTheShaderDeclares() throws IOException {
        final String f = read(SRC + "WhyV2Fusion.java");
        assertTrue(f.contains("final float jbu = WhyTone.jbuSigmaV2();"));
        assertTrue(f.contains("if (jbu > 0f) shaderProg.setDefine(\"JBU\", WhyTone.jbuDefine(jbuGuide));"));
        final String sh = read(SH + "v2_ef_collapse.glsl");
        for (String u : new String[]{"u_jbu_sigma", "u_jbu_log"}) {
            assertTrue(u, f.contains("shaderProg.setVar(\"" + u + "\""));
            assertTrue(u, sh.contains("uniform float " + u + ";"));
        }
        // the owner's range weight: exp(-(g_coarse - g_fine)^2 / (2 sigma^2)) on texture()'s bilinear weights, normalised
        assertTrue(sh.contains("vec4 jw = bw * exp(-(d2 - vec4(d2min)) / max(2.0 * u_jbu_sigma * u_jbu_sigma, 1.0e-12));"));
    }
}
