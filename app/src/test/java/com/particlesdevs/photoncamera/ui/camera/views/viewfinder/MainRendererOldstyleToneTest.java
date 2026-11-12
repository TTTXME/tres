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
        // per version (P630f: ToneLut) and, P630h, per shape of the tone curve: the LUT and its key are OldstyleLive's
        assertTrue(src.contains("final int key = com.particlesdevs.photoncamera.processing.oldstyle.OldstyleLive.lutKey(version);")
                && src.contains("final byte[] lut = com.particlesdevs.photoncamera.processing.oldstyle.OldstyleLive.lut(version);")
                && src.contains("if (mToneLut.ready(key)) return true;") && src.contains("mToneLut.uploaded(texture, key);"));
        assertFalse(src.contains("OldstyleNative.previewLut("));
        final String capture = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java")), StandardCharsets.UTF_8);
        // the version as the panel has it at each metering (a V1 / V2 switch shows at once), off outside the mode
        // P630g: the plan goes to OldstyleLive (the screen frame's own level, smoothed, calibrated on the module's picture), the
        // version of each metering with it; off outside the mode whoever turned it on
        // P630h: with the settings (the gains' limit and base, the mode's exposure, the rows of «Видоискатель»), the tone curve's
        // shape in the LUT before the plan
        assertTrue(capture.contains("live.plan(System.nanoTime(), v2 ? 2 : 1, c, params,"));
        assertTrue(capture.indexOf("OldstyleLive.curve(params.curveMid, params.curveContrast);") > 0
                && capture.indexOf("OldstyleLive.curve(params.curveMid, params.curveContrast);") < capture.indexOf("live.plan(System.nanoTime(), v2 ? 2 : 1, c, params,"));
        assertTrue(capture.contains("OldstyleLive.photoMean(c, params)"));
        assertTrue(capture.contains("if (mOldstyleToneLive || live.wantsLevel()) live.off();"));
        assertFalse("never the RAW frame's gains straight onto the screen", capture.contains("MainRenderer.setOldstyleTone("));
        // the renderer draws with OldstyleLive's state of this frame (null: no tone) and measures the frame's level for it
        assertTrue(src.contains("final float[] liveTone = readyTone(0f);"));
        assertTrue(src.contains("OldstyleLive.shared().gains(System.nanoTime(), assumedLevel)"));
        assertTrue(src.contains("mSceneMeter.measure(pVertex, pTexCoord, hTex[0], LiveSceneMeter.LIVE_TONE_FRAMES, mFrameGain);"));
        final String shader = new String(Files.readAllBytes(Paths.get("src/main/assets/shaders/utils/import_oldstyle_tone_live.glsl")), StandardCharsets.UTF_8);
        assertTrue(shader.contains("uniform vec4 uOt;") && shader.contains("smoothstep(" + com.particlesdevs.photoncamera.processing.oldstyle.OldstyleLive.WHITE_FROM + ", 1.0,"));
    }

    /**
     * P630f (owner, OPPO PHY110: «После первого снимка или включения лога чёрный экран»): the tone's LUT texture belongs to one GL
     * context - after a new one (the gallery and back, the settings and back) nothing of it is ready, whatever version was
     * uploaded before, so the LUT is uploaded again; the renderer forgets it first thing in onSurfaceCreated.
     */
    @Test public void theLutIsUploadedAgainInANewGlContext() throws Exception {
        final MainRenderer.ToneLut lut = new MainRenderer.ToneLut();
        assertFalse(lut.ready(1));
        lut.uploaded(7, 1);
        assertTrue(lut.ready(1));
        assertFalse("the other version: its own upload", lut.ready(2));
        lut.contextLost();
        assertFalse("a new context: the old texture is gone", lut.ready(1));
        assertEquals(0, lut.texture);
        lut.uploaded(3, 2);
        assertTrue(lut.ready(2));
        final String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/particlesdevs/photoncamera/ui/camera/views/viewfinder/MainRenderer.java")), StandardCharsets.UTF_8);
        final int created = src.indexOf("public void onSurfaceCreated(GL10 gl, EGLConfig config) {"), lost = src.indexOf("mToneLut.contextLost();");
        assertTrue(created > 0 && lost > created && lost - created < 200);
        // the upload asks the holder, binds the holder's texture, and no texture name is kept outside it
        assertTrue(src.contains("if (mToneLut.ready(key)) return true;"));
        assertTrue(src.contains("GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mToneLut.texture);"));
        assertFalse(src.contains("mOtLutTex"));
    }

    /**
     * P630g: the tone of a draw - a state set by hand wins (the tests, a forced state), else OldstyleLive's; nothing while that has
     * no plan or no level (the frame as it is: never a half-made tone), and the style keeps its exposure then.
     */
    @Test public void theDrawTakesOldstyleLivesStateOrNoTone() {
        final com.particlesdevs.photoncamera.processing.oldstyle.OldstyleLive live =
                com.particlesdevs.photoncamera.processing.oldstyle.OldstyleLive.shared();
        live.off();
        assertNull(MainRenderer.toneNow(0f));
        final com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Capture c =
                new com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Capture(1.0, 2.0, 0.8f, true, true, 1.0, 2.0, 0f);
        c.meterMean = 0.06f;
        live.plan(System.nanoTime(), 2, c, 16f);
        assertNull("a plan without the frame's level: no tone yet", MainRenderer.toneNow(0f));
        assertTrue(live.wantsLevel());
        live.level(System.nanoTime(), 0.18f);
        final float[] t = MainRenderer.toneNow(0f);
        assertEquals(2f, t[0], 0f);
        assertEquals(5, t.length);
        // the ISP frame three times the RAW's mean: the plan's gains over that
        assertEquals(2f / 3f, t[2], 0.02f);
        assertEquals(2f, t[2] / t[1] * (float) Math.pow(0.9756097793579102, 2.0), 0.02f);
        // a frame nobody measures (the developed RAW through the filter pass): the assumed level
        assertEquals(t[2], MainRenderer.toneNow(0.18f)[2], 0.01f);
        MainRenderer.setOldstyleTone(1, 0.5f, 1.5f, true);
        assertArrayEquals(new float[]{1f, 0.5f, 1.5f, 1f}, MainRenderer.toneNow(0f), 0f);
        MainRenderer.setOldstyleTone(0, 1f, 1f, false);
        live.off();
        assertNull(MainRenderer.toneNow(0f));
    }

    /** P630g: LiveSceneMeter's level of a tile - each texel's display luma to the power 2.2, times the series gain, at most white. */
    @Test public void theFramesLinearLevel() {
        final java.nio.ByteBuffer tile = java.nio.ByteBuffer.allocate(4 * 4);
        final int[] codes = {0, 128, 255, 186};
        for (int i = 0; i < 4; i++) tile.put(i * 4, (byte) codes[i]);
        final double[] lin = {0, Math.pow(128 / 255.0, 2.2), 1, Math.pow(186 / 255.0, 2.2)};
        assertEquals((float) ((lin[0] + lin[1] + lin[2] + lin[3]) / 4), LiveSceneMeter.linearMean(tile, 4, 1f), 1e-5f);
        // a series frame twice brighter on the screen: each texel x 2, white stays white
        assertEquals((float) ((2 * lin[1] + 1 + Math.min(1.0, 2 * lin[3])) / 4), LiveSceneMeter.linearMean(tile, 4, 2f), 1e-5f);
        assertEquals((float) ((Math.min(1.0, 8 * lin[1]) + 1 + 1) / 4), LiveSceneMeter.linearMean(tile, 4, 8f), 1e-5f);
        assertEquals(0f, LiveSceneMeter.linearMean(tile, 0, 1f), 0f);
    }
}
