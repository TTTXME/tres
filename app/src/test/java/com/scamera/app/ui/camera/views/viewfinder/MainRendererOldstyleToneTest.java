package com.scamera.app.ui.camera.views.viewfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.scamera.app.processing.oldstyle.OldstyleAe;
import com.scamera.app.processing.oldstyle.OldstyleLive;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P643d «ScamMaster»: the viewfinder shows the preview of the tone under the style, in the photo's order - OldstyleLive's bilateral
 * grid (the ISP frame carried to the module's own picture of the viewfinder's frame: its local tone and colour) spliced before the
 * style in main_fs, the style's own exposure 0 while it is drawn (the module's picture has it), off outside the mode, with «Тон в
 * видоискателе» off, and on the developed-RAW pass (not the frame the grid was fitted on). The picture: tools/check_oldstyle_tone.py
 * and tools/check_oldstyle_live.py (GPU). The tests of P643b's luma curve and of P630e .. P630g's tone went with them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MainRendererOldstyleToneTest {
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @After public void off() {
        OldstyleLive.shared().off();
    }

    @Test public void theStylesExposureIsInTheModulesPicture() throws Exception {
        final float[] style = new com.scamera.app.processing.oldstyle.OldstyleStyle()
                .set(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.EXPOSURE, 100)
                .set(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.TONE, 30).uniforms();
        final float[] after = MainRenderer.styleAfterTone(style, true);
        assertEquals(0f, after[2], 0f);
        assertEquals(style[0], after[0], 0f);
        assertSame(style, MainRenderer.styleAfterTone(style, false));   // no preview drawn: the style keeps its exposure
        // the picture the preview follows is made with the style's exposure, as the shot's snapshot makes the photo's
        final String capture = read("src/main/java/com/scamera/app/capture/CaptureController.java");
        final int viewfinder = capture.indexOf("private void oldstyleViewfinder() {");
        final String body = capture.substring(viewfinder, capture.indexOf("\n    }\n", viewfinder));
        assertTrue(body.contains("params.exposureEv = com.scamera.app.settings.OldstyleMode.style().exposureEv();"));
        assertTrue(body.indexOf("params.exposureEv =") < body.indexOf("OldstyleLive.photoRgb(frame[0], sw, sh, frame[1], params)"));
        assertTrue(read("src/main/java/com/scamera/app/processing/opengl/postpipeline/ShotLook.java")
                .contains("exposureEv = style.exposureEv()"));
    }

    @Test public void theToneIsSplicedBeforeTheStyle() throws Exception {
        final String main = read("src/main/assets/shaders/preview/main_fs.glsl");
        final String spliced = MainRenderer.oldstyleSplice(main, "//ADJUST", "//TONE");
        assertTrue(spliced.contains("//ADJUST") && spliced.contains("//TONE"));
        assertFalse(spliced.contains(MainRenderer.OLDSTYLE_TONE_MARKER));
        final int tone = main.indexOf("color.rgb = oldstyleToneLive(uv, color.rgb);"), style = main.indexOf("color.rgb = oldstyleStyle(");
        assertTrue(tone > 0 && style > tone);
        // right after the series gain, before the lens filter and the LUT (the photo: the tone, then the style)
        assertTrue(tone < main.indexOf("color.rgb = lensFilter(") && tone > main.indexOf("if (uGain != 1.0)"));
        final String src = read("src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java");
        assertTrue(src.contains("mSceneMeter.measure(quadXY, quadUV, ispTex, LiveSceneMeter.LIVE_TONE_FRAMES, mFrameGain);"));
        // P643d: the shader is the bilateral grid - an affine 3 x 4 a node, the cell by the texture coordinate the frame is sampled at,
        // the two luma bins round the pixel's luma; no taps of the frame (what made the blotches), no tone of its own
        final String shader = read("src/main/assets/shaders/utils/import_oldstyle_tone_live.glsl");
        final String code = shader.replaceAll("(?m)//.*$", "");
        assertTrue(code.contains("uniform int uOtOn;") && code.contains("uniform highp sampler2D uOtLut;") && code.contains("uniform highp float uOtLevel;"));
        assertFalse(code.contains("LF_SAMPLE") || code.contains("otLowPass"));
        assertTrue(code.contains("const highp float OT_GX = " + OldstyleLive.GX + ".0, OT_GY = " + OldstyleLive.GY + ".0, OT_GL = " + OldstyleLive.GL + ".0;"));
        assertTrue(code.contains("highp float y = cell.y + (row * OT_GL + bin) * OT_GY;"));   // OldstyleLive.index's texture rows
        assertEquals(OldstyleLive.GX, MainRenderer.OT_TEX_W);
        assertEquals(3 * OldstyleLive.GL * OldstyleLive.GY, MainRenderer.OT_TEX_H);
        assertTrue(src.contains("GLES30.GL_RGBA16F, OT_TEX_W, OT_TEX_H, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT"));
        assertEquals(OldstyleLive.index(1, 2, 3, 4), ((((1 * OldstyleLive.GL) + 2) * OldstyleLive.GY + 3) * OldstyleLive.GX + 4) * 4);
        final String capture = read("src/main/java/com/scamera/app/capture/CaptureController.java");
        assertTrue(capture.contains("live.photo(System.nanoTime(), com.scamera.app.processing.oldstyle.OldstyleLive.photoRgb(frame[0], sw, sh, frame[1], params),"));
        assertTrue(capture.contains("if (mOldstyleToneLive || live.wantsScreen()) live.off();"));
        assertFalse(capture.contains("meterOldstyle") || capture.contains("oldstyleShot") || capture.contains("HybridPlan.oldstyle"));
    }

    /**
     * P630f (owner, OPPO PHY110: «После первого снимка или включения лога чёрный экран»): the tone's texture belongs to one GL
     * context - after a new one (the gallery and back, the settings and back) nothing of it is there, so it is made again; the
     * renderer forgets it first thing in onSurfaceCreated. P643d: with it the grid's generation it holds (a new context uploads
     * the grid again).
     */
    @Test public void theTextureIsMadeAgainInANewGlContext() throws Exception {
        final MainRenderer.ToneLut lut = new MainRenderer.ToneLut();
        assertFalse(lut.ready());
        lut.created(7);
        assertTrue(lut.ready());
        lut.generation = 5;
        lut.contextLost();
        assertFalse("a new context: the old texture is gone", lut.ready());
        assertEquals(Integer.MIN_VALUE, lut.generation);
        assertEquals(0, lut.texture);
        lut.created(3);
        assertTrue(lut.ready());
        final String src = read("src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java");
        final int created = src.indexOf("public void onSurfaceCreated(GL10 gl, EGLConfig config) {"), lost = src.indexOf("mToneLut.contextLost();");
        assertTrue(created > 0 && lost > created && lost - created < 200);
        // the upload asks the holder, binds the holder's texture, and no texture name is kept outside it
        assertTrue(src.contains("if (!mToneLut.ready()) {") && src.contains("mToneLut.created(t[0]);"));
        assertTrue(src.contains("GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mToneLut.texture);"));
        assertTrue(src.contains("mToneLut.generation = g.generation;"));
        assertFalse(src.contains("mOtLutTex"));
    }

    /**
     * The grid of a draw is OldstyleLive's or none: nothing before a fit (the module's picture and the frame's tile), nothing once the
     * preview is off; the developed-RAW pass (its frame is not the one the grid was fitted on) draws none.
     */
    @Test public void theDrawTakesOldstyleLivesGridOrNoTone() throws Exception {
        final OldstyleLive live = OldstyleLive.shared();
        live.off();
        final int n = 32;
        final float[] picture = new float[n * n * 3], frame = new float[n * n * 3];
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++)
                for (int c = 0; c < 3; c++) {
                    frame[(j * n + i) * 3 + c] = 0.05f + 0.5f * i / n + 0.3f * j / n;
                    picture[(j * n + i) * 3 + c] = Math.min(1f, frame[(j * n + i) * 3 + c] * 1.4f);
                }
        final long now = System.nanoTime();
        assertNull(live.grid(now));
        live.photo(now, new OldstyleLive.Picture(picture, true), n, n, new OldstyleAe.Params());
        assertNull("the module's picture without the frame's tile: no tone yet", live.grid(now));
        assertTrue(live.wantsScreen());
        live.screen(now, frame, n, n);
        live.photo(now + 1_000_000L, new OldstyleLive.Picture(picture, true), n, n, new OldstyleAe.Params());
        final OldstyleLive.Grid g = live.grid(now + 2_000_000L);
        assertNotNull(g);
        assertEquals(OldstyleLive.FLOATS, g.coefficients.length);
        assertTrue("the frame lifted to the picture", OldstyleLive.apply(g.coefficients, 1f, new float[]{0.3f, 0.3f, 0.3f}, 0.5f, 0.5f)[1] > 0.36f);
        final OldstyleAe.Params off = new OldstyleAe.Params();
        off.vfTone = false;
        live.photo(now + 3_000_000L, new OldstyleLive.Picture(picture, true), n, n, off);
        assertNull("«Тон в видоискателе» off: the photo mode's viewfinder", live.grid(now + 4_000_000L));
        final String src = read("src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java");
        assertTrue(src.contains("OldstyleLive.shared().grid(System.nanoTime(),"));
        assertTrue(src.contains("oldstyleUniforms(uOsOn2d, uOsAdjust2d, false);") && src.contains("oldstyleTone(uOtTone2d, null);"));
        assertTrue(src.contains("oldstyleUniforms(uOsOn, uOsAdjust, liveTone != null);") && src.contains("oldstyleTone(uOtTone, liveTone);"));
        assertTrue(src.contains("if (l[2] >= 0) GLES20.glUniform1f(l[2], g.level);"));
    }

    /**
     * The tile as the shader has the frame before the tone: display RGB, rows in the order of the texture's t (the readback's bottom row
     * is t = 1), the series gain in light.
     */
    @Test public void theFramesTileForThePreview() {
        final java.nio.ByteBuffer tile = java.nio.ByteBuffer.allocate(2 * 2 * 4);
        final int[][] px = {{200, 100, 50}, {0, 0, 0}, {64, 64, 64}, {255, 255, 255}};   // readback rows: bottom (t = 1) first
        for (int i = 0; i < 4; i++) for (int c = 0; c < 3; c++) tile.put(i * 4 + c, (byte) px[i][c]);
        final float[] rgb = LiveSceneMeter.tileRgb(tile, 2, 1f);
        // the readback's row 0 (t = 1) is the tile's row 1
        assertEquals(200 / 255f, rgb[(1 * 2 + 0) * 3], 0f);
        assertEquals(50 / 255f, rgb[(1 * 2 + 0) * 3 + 2], 0f);
        assertEquals(64 / 255f, rgb[0], 0f);
        assertEquals(1f, rgb[(0 * 2 + 1) * 3], 0f);
        // a series frame drawn at twice the light: each value times 2 ^ (1 / 2.2), at most white
        final float[] lifted = LiveSceneMeter.tileRgb(tile, 2, 2f);
        assertEquals(64 / 255f * (float) Math.pow(2, 1 / 2.2), lifted[0], 1e-4f);
        assertEquals(1f, lifted[(0 * 2 + 1) * 3], 0f);
    }
}
