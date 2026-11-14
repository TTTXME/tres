package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.opengl.GLES30;

import com.scamera.app.processing.opengl.GLBuffer;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLImage;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.PostGlMode;
import com.scamera.app.processing.opengl.scripts.GLHistogram;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.processing.ultrahdr.GainMapComputer;
import com.scamera.app.util.Allocator;
import com.scamera.app.util.BufferUtils;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static android.opengl.GLES31.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES31.GL_LINEAR;

/**
 * The Ultra HDR side of {@link PostPipeline}: the linear scene light kept from the photo's own run (LinearExposure hands it
 * over before the tone) and, after the SDR picture is finished, the gain map measured from it.
 *
 * <p>The picture's local tone mapping squeezes blown highlights under display white, so the SDR result alone cannot tell
 * how bright they were. The map is therefore built from the light before the tone: a luminance plane L of the kept light,
 * turned and cropped onto the SDR picture (ultrahdr/sceneluma); a gain k matching the medians of L and of the linearised
 * SDR luminance (the medians, not top percentiles: two large clipped areas would land both percentiles in the same flat
 * white and cancel out); then ultrahdr/gainmap stores log2((k L + eps) / (SDR + eps)). Mid grey gets exactly no boost,
 * darker tones none either, saturated SDR cores over bright light several stops.
 *
 * <p>The kept light lives in native memory ({@link Allocator}): at 50 MP it is 400 MB as halves, 800 MB as floats, far
 * past the Java heap. It is used once and freed by {@link #release}.
 */
final class SceneGainMap {
    private static final String TAG = "PostPipeline";
    private static final int HIST_BINS = 256;
    private static final GLFormat RGBA16F = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
    private static final GLFormat RGBA8 = new GLFormat(GLFormat.DataType.SIMPLE_8, 4);

    /** The kept light, its size, and whether it holds the texture's IEEE halves (P147, 8 B a pixel) or floats (16 B). */
    private ByteBuffer light;
    private Point lightSize;
    private boolean lightInHalves;
    /** One snapshot per run: the first LinearExposure pass wins. */
    private boolean takenThisRun;

    void newRun() {
        takenThisRun = false;
    }

    boolean holdsLight() {
        return light != null && lightSize != null;
    }

    Point lightSize() {
        return lightSize;
    }

    /**
     * Keeps {@code full}'s light. P145: when the pipeline ends at the hybrid's final size the light is box-shrunk to it first
     * (HybridFinalResize "area"), so the map matches the base resized on the GPU and the readback shrinks with it.
     */
    void keep(GLTexture full, GLProg prog, Point finalSize) {
        if (takenThisRun || light != null) return;
        final boolean shrink = finalSize != null && (full.size.x != finalSize.x || full.size.y != finalSize.y);
        if (!shrink) {
            read(full);
            return;
        }
        if (prog == null) {
            Log.e(TAG, "Linear scene snapshot: no program to resize it; Ultra HDR will fall back to SDR");
            return;
        }
        final GLTexture small;
        try {
            small = HybridFinalResize.resized(prog, full, finalSize, "area");
        } catch (RuntimeException e) {
            Log.e(TAG, "Linear scene snapshot resize failed (" + e + "); Ultra HDR will fall back to SDR");
            return;
        }
        try {
            read(small);
        } finally {
            small.close();
        }
    }

    /**
     * The texture's pixels into native memory: P147 as the halves it holds where the driver hands them back exactly
     * (WhyStats' probe; half the readback and half the upload later), else as floats (GL_FLOAT). P27: a copy above one 2 GB
     * buffer (~134 MP as floats) is skipped like a failed allocation.
     */
    private void read(GLTexture tex) {
        final int w = tex.size.x, h = tex.size.y;
        tex.bindFramebuffer();
        final int halfType = PostGlMode.legacy() ? 0 : WhyStats.halfReadType();
        if (halfType != 0 && WhyStats.halfReadbackExact(halfType) && readHalves(tex, halfType)) return;
        lightInHalves = false;
        final long floatBytes = 16L * w * h;
        if (floatBytes > Integer.MAX_VALUE) {
            Log.e(TAG, "Linear scene snapshot of " + (floatBytes >> 20) + " MB is above 2 GB; Ultra HDR will fall back to SDR");
            return;
        }
        final ByteBuffer floats = Allocator.allocate((int) floatBytes);
        if (floats == null) {
            Log.e(TAG, "Linear scene snapshot allocation of " + floatBytes + " B failed; Ultra HDR will fall back to SDR");
            return;
        }
        tex.bindFramebuffer();
        tex.textureBuffer(RGBA16F, floats);
        hold(floats, tex.size, false);
    }

    private boolean readHalves(GLTexture tex, int halfType) {
        final long bytes = 8L * tex.size.x * tex.size.y;
        if (bytes > Integer.MAX_VALUE) return false;
        final ByteBuffer halves = Allocator.allocate((int) bytes);
        if (halves == null) return false;
        halves.order(ByteOrder.nativeOrder());
        tex.bindFramebuffer();
        GLES30.glReadPixels(0, 0, tex.size.x, tex.size.y, GLES30.GL_RGBA, halfType, halves);
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            Allocator.free(halves);
            return false;
        }
        hold(halves, tex.size, true);
        return true;
    }

    private void hold(ByteBuffer pixels, Point size, boolean halves) {
        pixels.rewind();
        light = pixels;
        lightSize = new Point(size.x, size.y);
        lightInHalves = halves;
        takenThisRun = true;
    }

    /** Frees the kept light; any number of times. */
    void release() {
        if (light != null) Allocator.free(light);
        light = null;
        lightSize = null;
        lightInHalves = false;
    }

    /** A gain-map pass that failed before {@link #measure}: the textures and the kept light go as measure would let them go. */
    void abandon() {
        GLTexture.closeAll();
        release();
    }

    /** What the gain-map pass needs of the pipeline beyond the kept light. */
    static final class Shot {
        Parameters parameters;
        GLProg prog;
        /** The SDR picture's size (rotated) and the light's own (sensor layout, the pipeline's work size). */
        Point pictureSize, base, cropSize;
        int rotateIndex;
        float[] lumaWeights;
        boolean p3;
        /** P378 review: the SDR's ratio of WhyCombine's sky term (null: none), applied to the scene plane. */
        float[] skyRatio;
        Point skyRatioSize;
    }

    /** The gain map of the finished SDR picture {@code sdr}; see the class comment. Frees the kept light. */
    PostPipeline.GainMapRaw measure(Shot shot, Bitmap sdr, int down, float scale) {
        GLTexture lightTex = null, shading = null, skyRatio = null;
        try {
            lightTex = uploadLight();
            final GLTexture sdrTex = uploadSdr(sdr);
            shading = shadingMap(shot.parameters);
            skyRatio = skyRatioTexture(shot.skyRatio, shot.skyRatioSize);
            final GLTexture plane = new GLTexture(shot.pictureSize, RGBA16F);
            plane.renderInto();
            drawScenePlane(shot, lightTex, shading, skyRatio, plane);
            // the light, the shading and the sky ratio are done with: their memory goes before the next passes (P659)
            lightTex.close();
            lightTex = null;
            shading.close();
            shading = null;
            if (skyRatio != null) {
                skyRatio.close();
                skyRatio = null;
            }
            final float anchor = anchor(shot.prog, plane, sdrTex);
            final int gw = Math.max(1, shot.pictureSize.x / down), gh = Math.max(1, shot.pictureSize.y / down);
            final GLTexture encoded = new GLTexture(new Point(gw, gh), RGBA8);
            encoded.renderInto();
            drawGainMap(shot, sdrTex, plane, anchor, down, scale, encoded);
            sdrTex.close();
            plane.close();
            final float[] range = {Float.NaN};
            final GLTexture quantised = down == 1 && !PostGlMode.legacy() ? requantise(shot.prog, encoded, gw, gh, scale, range) : null;
            final GLTexture result = quantised != null ? quantised : encoded;
            // the map's own framebuffer: after a failed requantisation the deleted one was still bound (P659 fix)
            result.bindFramebuffer();
            final ByteBuffer bytes = result.textureBuffer(RGBA8, false);
            bytes.position(0);
            final Bitmap map = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            map.copyPixelsFromBuffer(bytes);
            encoded.close();
            if (quantised != null) quantised.close();
            return new PostPipeline.GainMapRaw(map, gw, gh, down, scale, range[0]);
        } finally {
            closeQuietly(shading);
            closeQuietly(skyRatio);
            abandon(); // the light is used once
        }
    }

    /**
     * The SDR picture as an RGBA8 texture. An ARGB_8888 bitmap goes up from its own pixels (GLUtils.texSubImage2D: the bytes
     * Bitmap.copyPixelsToBuffer gives, as the gallery editor uploads its photos) - no direct copy of the picture next to it
     * (48 MB at 12 MP, 200 MB at 50 MP); another config (the 10-bit RGBA_1010102, hardware) or a refused upload: through
     * GLImage's 8-bit copy as before. The same texels either way.
     */
    static GLTexture uploadSdr(Bitmap sdr) {
        if (sdr.getConfig() == Bitmap.Config.ARGB_8888) {
            GLTexture tex = null;
            try {
                //noinspection StatementWithEmptyBody: only the upload below decides
                while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { }
                tex = new GLTexture(new Point(sdr.getWidth(), sdr.getHeight()), new GLFormat(GLFormat.DataType.SIMPLE_8, 4), null,
                        GL_LINEAR, GL_CLAMP_TO_EDGE, 0);
                final int[] previous = new int[1];
                GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, previous, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.id);
                android.opengl.GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, sdr);
                final int error = GLES30.glGetError();
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previous[0]);
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
                if (error == GLES30.GL_NO_ERROR) return tex;
                Log.w(TAG, "UltraHDR SDR upload from the bitmap refused (0x" + Integer.toHexString(error) + "), through a copy");
            } catch (RuntimeException e) {
                Log.w(TAG, "UltraHDR SDR upload from the bitmap failed (" + e + "), through a copy");
            }
            if (tex != null) tex.close();
        }
        final GLImage sdrImage = new GLImage(sdr);
        try {
            return new GLTexture(sdrImage);
        } finally {
            sdrImage.close(); // the direct copy of the bitmap only served the upload
        }
    }

    private GLTexture uploadLight() {
        light.rewind();
        if (!lightInHalves) return new GLTexture(lightSize, RGBA16F, light);
        // P147: the halves go up as they came (GL_HALF_FLOAT into the RGBA16F texture): the same texels
        final GLTexture tex = new GLTexture(lightSize, RGBA16F);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.id);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, lightSize.x, lightSize.y, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, light);
        logGlError("half snapshot upload");
        return tex;
    }

    /**
     * The lens-shading map that flattens the scene plane (sceneluma.glsl), the one the SDR path used (ScamRgb), uploaded again
     * from the parameters (Run's teardown deleted the pipeline's); a 1x1 identity when it is missing or fails.
     */
    private static GLTexture shadingMap(Parameters parameters) {
        try {
            final float[] map = parameters.shadingMap;
            if (map != null && map.length >= 4) {
                final Point size = parameters.shadingGrid != null ? parameters.shadingGrid : new Point(1, 1);
                return new GLTexture(size, RGBA16F, BufferUtils.floatBuffer(map), GL_LINEAR, GL_CLAMP_TO_EDGE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to upload GainMap for UltraHDR, falling back to identity", e);
        }
        return new GLTexture(new Point(1, 1), RGBA16F, BufferUtils.floatBuffer(new float[]{1f, 1f, 1f, 1f}), GL_LINEAR, GL_CLAMP_TO_EDGE);
    }

    private static GLTexture skyRatioTexture(float[] ratio, Point size) {
        if (ratio == null || size == null || ratio.length != size.x * size.y) return null;
        return new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 1), BufferUtils.floatBuffer(ratio), GL_LINEAR, GL_CLAMP_TO_EDGE);
    }

    private static void drawScenePlane(Shot shot, GLTexture lightTex, GLTexture shading, GLTexture skyRatio, GLTexture plane) {
        final GLProg prog = shot.prog;
        prog.useAssetProgram("ultrahdr/sceneluma");
        prog.setTexture("InputBuffer", lightTex);
        prog.setTexture("GainMap", shading);
        prog.setVar("rotate", shot.rotateIndex);
        prog.setVar("mirror", shot.parameters.mirrored ? 1 : 0);
        prog.setVar("cropSize", shot.cropSize);
        prog.setVar("rawSize", shot.base);
        // P370 (B3): luminance as the render weighs it (its colour matrix and white balance), not Rec.709 of camera RGB
        prog.setVar("wU", shot.lumaWeights[0], shot.lumaWeights[1], shot.lumaWeights[2]);
        if (skyRatio != null) prog.setTexture("SkyRatio", skyRatio);
        prog.setVar("skyRatioU", skyRatio != null ? 1 : 0);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        renderInto(prog, plane, shot.pictureSize.x, shot.pictureSize.y);
        logGlError("scene-luma draw");
    }

    /** k = linearised SDR median / scene-plane median. */
    private static float anchor(GLProg prog, GLTexture plane, GLTexture sdrTex) {
        final float planeMedian, sdrMedian;
        try (GLHistogram histogram = new GLHistogram(prog, HIST_BINS)) {
            planeMedian = Math.max(median(histogram.Compute(plane)), 1e-4f);
            sdrMedian = srgbDecode(median(histogram.Compute(sdrTex)));
        }
        final float k = sdrMedian / planeMedian;
        Log.d(TAG, "UltraHDR anchor:" + k + " Lmed:" + planeMedian + " SmedLin:" + sdrMedian);
        return k;
    }

    private static void drawGainMap(Shot shot, GLTexture sdrTex, GLTexture plane, float anchor, int down, float scale,
                                    GLTexture target) {
        final GLProg prog = shot.prog;
        prog.useAssetProgram("ultrahdr/gainmap");
        prog.setTexture("InputBuffer", sdrTex);
        prog.setTexture("LBuffer", plane);
        prog.setVar("uAnchor", anchor);
        prog.setVar("uDown", down);
        prog.setVar("uScale", scale);
        prog.setVar("uEps", GainMapComputer.DECODE_OFFSET);
        prog.setVar("uP3", shot.p3 ? 1 : 0); // P370 (B3): a Display P3 base's own luminance
        renderInto(prog, target, target.size.x, target.size.y);
        logGlError("gain-map comparison draw");
    }

    /**
     * P145: GainMapComputer's range and requantisation on the GPU (otherwise two CPU passes over the map): the largest byte,
     * the range it decodes to and the byte table into ultrahdr/gainquant. Null (the CPU normalises) when a pass fails;
     * {@code range[0]} the GainMapMax of the returned map.
     */
    private static GLTexture requantise(GLProg prog, GLTexture encoded, int w, int h, float scale, float[] range) {
        final int top = largestByte(prog, encoded, w, h);
        if (top < 0) return null;
        final float gMax = GainMapComputer.rangeMax(GainMapComputer.boost(top, scale));
        final int[] table = GainMapComputer.requantisation(gMax, scale);
        final float[] tableU = new float[table.length];
        for (int i = 0; i < table.length; i++) tableU[i] = table[i];
        GLTexture out = new GLTexture(new Point(w, h), RGBA8);
        out.renderInto();
        prog.useAssetProgram("ultrahdr/gainquant");
        prog.setTexture("InputBuffer", encoded);
        prog.setVarVec4s("lutU", tableU);
        renderInto(prog, out, w, h);
        if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
            range[0] = gMax;
        } else {
            out.close();
            out = null;
        }
        Log.d(TAG, "UltraHDR gain map on the GPU: max byte " + top + " -> GainMapMax " + gMax
                + (out == null ? " (GL error: CPU normalisation)" : ""));
        return out;
    }

    /** P145: the largest encoded byte of the map (compute ultrahdr/gainmax); -1 when the pass failed. */
    static int largestByte(GLProg prog, GLTexture map, int w, int h) {
        //noinspection StatementWithEmptyBody
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            // earlier errors are not this pass's
        }
        try (GLBuffer top = new GLBuffer(1, new GLFormat(GLFormat.DataType.UNSIGNED_32))) {
            prog.setLayout(16, 16, 1);
            prog.useAssetProgram("ultrahdr/gainmax", true);
            prog.setTexture("InputBuffer", map);
            prog.setVar("sizeU", w, h);
            prog.setBufferCompute("gainMax", top);
            prog.computeManual((w + 15) / 16, (h + 15) / 16, 1);
            final int[] v = top.readBufferIntegers(false);
            final boolean ok = GLES30.glGetError() == GLES30.GL_NO_ERROR && v != null && v[0] >= 0 && v[0] <= 255;
            return ok ? v[0] : -1;
        } catch (RuntimeException e) {
            Log.w(TAG, "UltraHDR gain map max on the GPU failed: " + e);
            return -1;
        }
    }

    private static void renderInto(GLProg prog, GLTexture target, int w, int h) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer);
        GLES30.glViewport(0, 0, w, h);
        prog.draw();
    }

    /**
     * The median of a GLHistogram result over R, G and B together, as a bin position in [0, 1]: walking down from the top
     * bin, the first bin where more than half of all samples have been passed.
     */
    static float median(int[][] hist) {
        final long[] perBin = new long[HIST_BINS];
        long all = 0L;
        for (int b = 0; b < HIST_BINS; b++) {
            perBin[b] = (long) hist[0][b] + hist[1][b] + hist[2][b];
            all += perBin[b];
        }
        if (all <= 0L) return 0f;
        final long half = all / 2L;
        long above = 0L;
        for (int b = HIST_BINS - 1; b >= 0; b--) {
            above += perBin[b];
            if (above > half) return (float) b / (float) (HIST_BINS - 1);
        }
        return 1f;
    }

    /** sRGB transfer decoded, the input clamped to [0, 1]. */
    static float srgbDecode(float v) {
        final float c = Math.min(Math.max(v, 0f), 1f);
        if (c <= 0.04045f) return c / 12.92f;
        return (float) Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static void logGlError(String what) {
        final int err = GLES30.glGetError();
        if (err != GLES30.GL_NO_ERROR) Log.e(TAG, "GL error 0x" + Integer.toHexString(err) + " in " + what);
    }

    private static void closeQuietly(GLTexture tex) {
        if (tex == null) return;
        try {
            tex.close();
        } catch (Exception ignored) {
            // the closeAll after it deletes what is left
        }
    }
}
