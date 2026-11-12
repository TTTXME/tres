package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P651 (owner, 2026-11-13, vivo V2419A: a green ScamMaster viewfinder): the tone preview's picture is made only of a ring frame
 * whose capture result has arrived (its white balance and colour matrix), and of the CFA of the physical camera the ring streams.
 */
public class ScamMasterViewfinderFrameTest {
    @Test public void theViewfinderFrameWaitsForItsResult() throws Exception {
        final String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java")), StandardCharsets.UTF_8);
        final int at = s.indexOf("private float[][] oldstyleFrame(int sw, int sh) {");
        assertTrue(at > 0);
        final int end = s.indexOf("private static final long OLDSTYLE_VIEWFINDER_NS", at);
        final String body = s.substring(at, end);
        final int skip = body.indexOf("if (r == null || r.get(CaptureResult.COLOR_CORRECTION_GAINS) == null) continue;");
        final int take = body.indexOf("{ newest = candidate; meta = r; break; }");
        assertTrue("a frame without its result is skipped before one is taken", skip > 0 && take > skip);
        assertTrue("the physical camera's CFA first",
                body.indexOf("Integer cfa = lc == null ? null : lc.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);") > 0);
        assertTrue("no frame with its result: no picture (the last one stays)", body.contains("if (newest == null) {")
                && body.indexOf("return null;", body.indexOf("if (newest == null) {")) > 0);
    }
}
