package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P630e «Oldstyle»: the viewfinder shows the version's tone under the style, in the photo's order - the live tone (its gains the
 * shot's plan, the style's exposure in them) spliced before the style in main_fs, the style's own exposure 0 while the tone is live,
 * the version switched live (the LUT made per version), off outside the mode. The picture: tools/check_oldstyle_tone.py (GPU).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MainRendererOldstyleToneTest {
    @After public void off() {
        MainRenderer.setOldstyleTone(0, 1f, 1f, false);
    }

    @Test public void theStylesExposureGoesIntoTheGains() {
        final float[] style = new com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle()
                .set(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.EXPOSURE, 100)
                .set(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.TONE, 30).uniforms();
        MainRenderer.setOldstyleTone(2, 0.9f, 3.5f, true);
        final float[] t = MainRenderer.oldstyleTone();
        assertArrayEquals(new float[]{2f, 0.9f, 3.5f, 1f}, t, 0f);
        final float k = (float) Math.pow(2, 1.5);
        assertArrayEquals(new float[]{0.9f * k, 3.5f * k}, MainRenderer.toneGains(t, style), 1e-5f);
        assertArrayEquals(new float[]{0.9f, 3.5f}, MainRenderer.toneGains(t, null), 0f);
        final float[] after = MainRenderer.styleAfterTone(style, true);
        assertEquals(0f, after[2], 0f);
        assertEquals(style[0], after[0], 0f);
        assertSame(style, MainRenderer.styleAfterTone(style, false));   // no live tone: the style keeps its exposure
        MainRenderer.setOldstyleTone(0, 1f, 1f, false);
        assertNull(MainRenderer.oldstyleTone());
    }

    @Test public void theToneIsSplicedBeforeTheStyle() throws Exception {
        final String main = new String(Files.readAllBytes(Paths.get("src/main/assets/shaders/preview/main_fs.glsl")), StandardCharsets.UTF_8);
        final String spliced = MainRenderer.oldstyleSplice(main, "//ADJUST", "//TONE");
        assertTrue(spliced.contains("//ADJUST") && spliced.contains("//TONE"));
        assertFalse(spliced.contains(MainRenderer.OLDSTYLE_TONE_MARKER));
        final int tone = main.indexOf("color.rgb = oldstyleToneLive("), style = main.indexOf("color.rgb = oldstyleStyle(");
        assertTrue(tone > 0 && style > tone);
        // right after the series gain, before the lens filter and the LUT (the photo: the tone, then the style)
        assertTrue(tone < main.indexOf("color.rgb = lensFilter("));
        final String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/particlesdevs/photoncamera/ui/camera/views/viewfinder/MainRenderer.java")), StandardCharsets.UTF_8);
        assertTrue(src.contains("OldstyleNative.previewLut(version, lut)") && src.contains("mOtLutVersion == version"));
        final String capture = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java")), StandardCharsets.UTF_8);
        // the version as the panel has it at each metering (a V1 / V2 switch shows at once), off outside the mode
        assertTrue(capture.contains("MainRenderer.setOldstyleTone(v2 ? 2 : 1, g[0], g[1], c.hdr)"));
        assertTrue(capture.contains("MainRenderer.setOldstyleTone(0, 1f, 1f, false)"));
    }
}
