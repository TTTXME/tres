package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

public class ScamRgbUniformParityTest {
    @Test public void clipBandReceivesEveryDeclaredInput() throws Exception {
        String shader = read("src/main/assets/shaders/scamhdr/clipband.glsl");
        String java = read("src/main/java/com/scamera/app/processing/opengl/postpipeline/ScamRgb.java");
        int start = java.indexOf("glProg.useAssetProgram(\"scamhdr/clipband\"");
        assertTrue(start >= 0);
        String pass = java.substring(start, java.indexOf("glProg.drawBlocks(", start));
        Matcher uniforms = Pattern.compile("(?m)^uniform\\s+\\w+\\s+(\\w+)\\s*;").matcher(shader);
        int count = 0;
        while (uniforms.find()) {
            String uniform = uniforms.group(1);
            assertTrue("clipband input missing: " + uniform,
                    Pattern.compile("glProg\\.set(?:Texture|Var)\\(\\s*\"" + uniform + "\"").matcher(pass).find());
            count++;
        }
        assertTrue("the shader's inputs must actually be inspected", count >= 6);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
