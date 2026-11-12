package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import android.opengl.GLES30;

import com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe;
import com.particlesdevs.photoncamera.processing.oldstyle.OldstyleNative;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * P630b «Oldstyle»: the mode's tone in place of «Why тон» through the owner's tone-port module (third_party/tone_port, the source
 * of truth; OldstyleNative). The merge, any route and any Sabre size (1x / 1.5x / 2x: the module takes the merged grid as it is),
 * stays ours; only the tone stage is the module's.
 * <ol>
 *   <li>The clean linear RGB of the merge (why/v2_clean) and its strided samples (why/v2_stride, WhyV2Ae.stride) for the plan
 *       (P630e, OldstyleAe.plan: the supplement's plan27 / plan31 and fusion_gains, from the shot's capture plan when the burst was
 *       exposed by it, else metered on the merge; the style's exposure in both TETs).</li>
 *   <li>The module's frame: oldstyle/linear (the same colour chain, no clamp at 0; V1 before our CCM, V2 after it; V2 scaled so its
 *       light stays within white) as RGBA16I, read back in strips of {@link #STRIP} rows (GL_RGBA_INTEGER / GL_INT) into one planar int16 frame.</li>
 *   <li>One call of the module on the whole frame (its pyramid and 3.1's shadow curve read the whole frame: no tiles): V1
 *       finish_raw27_rgb (+ the 2.7 HDR of its RGB8 with the two gains, the low-frequency colour on its bright exposure), V2
 *       render_raw31_hdr / _sdr; then the supplement's dehaze / tint and output saturation on the RGB8 (P630e). CPU, one thread.
 *       The version by memory ({@link #route}): V2 where its buffers fit the budget, else V1, measured per pixel on the host.</li>
 *   <li>The RGB8 picture uploaded and written to the pipeline's main texture (oldstyle/rgb8: code / 255).</li>
 * </ol>
 */
public final class OldstyleTone extends Node {
    private final boolean v2;
    private final OldstyleAe.Params params;
    private final boolean postview;
    @androidx.annotation.Nullable
    private final OldstyleAe.Capture capture;

    public OldstyleTone(boolean v2, OldstyleAe.Params params, boolean postview, @androidx.annotation.Nullable OldstyleAe.Capture capture) {
        super("", "OldstyleTone");
        this.v2 = v2;
        this.params = params;
        this.postview = postview;
        this.capture = capture;
    }

    @Override public void Compile() {}

    /**
     * P630e, the module's native memory a pixel beyond its input planes and the output (host, 12 MP, peak RSS): V1 SDR 9, V1 HDR 25
     * (its padded RGB8, two virtual exposures, the fusion's pyramid), V2 SDR 21, V2 HDR 15; plus our planar int16 frame (6) and
     * the RGB8 out (3). The dehaze / saturation run after the module's buffers are freed (3 B / px of YUV planes).
     */
    static final int BYTES_V1_SDR = 18, BYTES_V1_HDR = 34, BYTES_V2_SDR = 30, BYTES_V2_HDR = 24;
    /** The share of the available memory the tone may take. */
    static final float MEMORY_SHARE = 0.6f;
    /** Rows read back at a time. */
    static final int STRIP = 256;

    /** The native bytes of one tone call ({@code v2}, {@code hdr}) on {@code pixels}. */
    static long bytes(boolean v2, boolean hdr, long pixels) {
        return pixels * (v2 ? (hdr ? BYTES_V2_HDR : BYTES_V2_SDR) : (hdr ? BYTES_V1_HDR : BYTES_V1_SDR));
    }

    /**
     * Whether a grid of {@code pixels} takes V2 when V2 is chosen: its buffers within {@link #MEMORY_SHARE} of {@code available}
     * bytes (0: not known - the 100 MP rule of P630b). Since P630e V1 is no fallback by size alone: at Sabre 1.5x / 2x of a 50 MP
     * sensor (112 / 200 MP) V2 runs where the memory is there, V1 where it is not.
     */
    static boolean route(boolean chosen, boolean hdr, long pixels, long available) {
        if (!chosen) return false;
        if (available <= 0) return pixels <= 100_000_000L;
        return bytes(true, hdr, pixels) <= (long) (available * (double) MEMORY_SHARE);
    }

    static long availableMemory() {
        try {
            final android.app.ActivityManager am = (android.app.ActivityManager) com.particlesdevs.photoncamera.app.ScameraApp
                    .getAppContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            final android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.availMem;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * The dehaze's U / V gain map from our lens-shading gain map (mapW x mapH nodes of r, g_even, g_odd, b): the luma shading our
     * colour chain applied (their mean, as why/v2_clean), over its smallest node (the centre ~1) - the same value for U and V. Null
     * without a map.
     */
    static float[] shadingGrid(float[] gainMap, int mapW, int mapH) {
        if (gainMap == null || mapW < 2 || mapH < 2 || gainMap.length < mapW * mapH * 4) return null;
        final float[] lsc = new float[mapW * mapH];
        float min = Float.MAX_VALUE;
        for (int i = 0; i < lsc.length; i++) {
            final float r = gainMap[i * 4], g = (gainMap[i * 4 + 1] + gainMap[i * 4 + 2]) * 0.5f, b = gainMap[i * 4 + 3];
            lsc[i] = (r + g + b) / 3f;
            if (lsc[i] > 0f) min = Math.min(min, lsc[i]);
        }
        if (!(min > 0f) || min == Float.MAX_VALUE) return null;
        final float[] out = new float[lsc.length * 2];
        for (int i = 0; i < lsc.length; i++) out[i * 2] = out[i * 2 + 1] = Math.max(1f, lsc[i] / min);
        return out;
    }

    /** V1's CCM for the module (row-major): the colour chain's intermediateToSRGB x sensorToIntermediate. */
    static float[] ccm(float[] intermediateToSrgb, float[] sensorToIntermediate) {
        return OldstyleNative.mul(intermediateToSrgb, sensorToIntermediate);
    }

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        final GLTexture input = previousNode.WorkingTexture;
        final Point size = input.mSize;
        final long started = System.currentTimeMillis();
        if (!OldstyleNative.load()) throw new IllegalStateException("Oldstyle: the tone-port library (scameraTone) did not load");
        final long pixels = (long) size.x * size.y, available = availableMemory();
        // what the Why tones consume of the merge's side data: not drawn by this tone, given back here
        if (pipeline.sunBloom != null) { pipeline.sunBloom.close(); pipeline.sunBloom = null; }
        if (pipeline.opposedShare != null) { pipeline.opposedShare.close(); pipeline.opposedShare = null; }
        pipeline.skyHdrRatio = null;
        pipeline.skyHdrRatioSize = null;
        GLTexture gainMap = pipeline.GainMap, fallback = null, clean = null, samples = null, frame = null, rgb = null;
        try {
            if (gainMap == null) {
                fallback = new GLTexture(new Point(1, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                        BufferUtils.getFrom(new float[]{1f, 1f, 1f, 1f}), GL_LINEAR, GL_CLAMP_TO_EDGE);
                gainMap = fallback;
            }
            // 1. the plan from the clean linear RGB's samples
            clean = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("why/v2_clean", false);
            glProg.setTexture("InputBuffer", input);
            WhyTone.setColour(glProg, pipeline, gainMap, 1f);
            glProg.drawBlocks(clean);
            final int stride = WhyV2Ae.stride(size.x, size.y);
            final int sw = WhyV2Ae.samples(size.x, stride), sh = WhyV2Ae.samples(size.y, stride);
            samples = new GLTexture(new Point(sw, sh), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("why/v2_stride", false);
            glProg.setTexture("u_tex_rgb", clean);
            glProg.setVar("u_stride", stride);
            glProg.drawBlocks(samples);
            glProg.closed = true;
            clean.close();
            clean = null;
            samples.BufferLoad();
            final ByteBuffer px = samples.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_16, 4));
            px.order(ByteOrder.nativeOrder());
            final FloatBuffer f = px.asFloatBuffer();
            final float[] rgbSamples = new float[sw * sh * 3];
            for (int i = 0; i < sw * sh; i++) for (int c = 0; c < 3; c++) {
                final float v = f.get(i * 4 + c);
                rgbSamples[i * 3 + c] = Float.isFinite(v) ? v : 0f;
            }
            samples.close();
            samples = null;
            final boolean shorts = WhyV2Stats.shortFramesMerged(ScamHybridBurst.lastBentoApplied, ScamHybridBurst.lastReport);
            final double frameTet = pipeline.mParameters.exposureTime * 1e9 * pipeline.mParameters.iso;
            // the version by memory first (the plan of V2 and V1 differ in their rules), with the HDR of V2's plan
            params.v31 = v2;
            OldstyleAe.Plan plan = OldstyleAe.plan(rgbSamples, sw, sh, params, shorts ? WhyV2Stats.MAX_CEILING : 1f, capture, frameTet);
            final boolean useV2 = route(v2, plan.hdr, pixels, available);
            if (v2 && !useV2) {
                Log.i("SCAM_PIPELINE", "Oldstyle V2 chosen, V1 used: " + (bytes(true, plan.hdr, pixels) >> 20) + " MB of buffers for "
                        + size.x + "x" + size.y + ", " + (available >> 20) + " MB available");
                params.v31 = false;
                plan = OldstyleAe.plan(rgbSamples, sw, sh, params, shorts ? WhyV2Stats.MAX_CEILING : 1f, capture, frameTet);
            }
            if (!useV2 && bytes(false, plan.hdr, pixels) > (long) (available * (double) MEMORY_SHARE) && available > 0)
                Log.w("SCAM_PIPELINE", "Oldstyle: " + (bytes(false, plan.hdr, pixels) >> 20) + " MB of V1 buffers for " + size.x + "x"
                        + size.y + " above the budget of " + (available >> 20) + " MB available, run anyway");
            pipeline.linearDisplayGain = plan.gainLong;
            // 2. the module's frame, read back in strips into the planar int16 frame
            final float[] s2i = pipeline.mParameters.sensorToProPhoto, i2s = WhyTone.colourMatrix(pipeline);
            frame = new GLTexture(size, new GLFormat(GLFormat.DataType.SIGNED_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            glProg.setDefine("PRE_CCM", useV2 ? 0 : 1);
            glProg.useAssetProgram("oldstyle/linear", false);
            glProg.setTexture("InputBuffer", input);
            WhyTone.setColour(glProg, pipeline, gainMap, 1f);
            glProg.setVar("uScale", plan.inputScale * OldstyleNative.WHITE);
            glProg.drawBlocks(frame);
            glProg.closed = true;
            final long readStart = System.currentTimeMillis();
            final ByteBuffer planar = OldstyleNative.planar(size.x, size.y);
            final ByteBuffer strip = ByteBuffer.allocateDirect(size.x * STRIP * 16).order(ByteOrder.nativeOrder());
            frame.BufferLoad();
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1);
            for (int y = 0; y < size.y; y += STRIP) {
                final int rows = Math.min(STRIP, size.y - y);
                strip.position(0);
                GLES30.glReadPixels(0, y, size.x, rows, GLES30.GL_RGBA_INTEGER, GLES30.GL_INT, strip);
                OldstyleNative.deinterleave(strip, planar, size.x, size.y, y, rows);
            }
            frame.close();
            frame = null;
            // 3. the module
            final long toneStart = System.currentTimeMillis();
            final ByteBuffer out = ByteBuffer.allocateDirect(Math.multiplyExact(size.x * size.y, 3)).order(ByteOrder.nativeOrder());
            final float[] shading = params.dehaze && params.dehazeShading && pipeline.mParameters.mapSize != null
                    ? shadingGrid(pipeline.mParameters.gainMap, pipeline.mParameters.mapSize.x, pipeline.mParameters.mapSize.y) : null;
            final float[] info = new float[6];
            OldstyleNative.render(useV2 ? OldstyleNative.V2 : OldstyleNative.V1, planar, size.x, size.y, useV2 ? null : ccm(i2s, s2i),
                    plan.gainShort, plan.gainLong, plan.hdr, postview, params.saturation, params.post(), shading,
                    shading == null ? 0 : pipeline.mParameters.mapSize.x, shading == null ? 0 : pipeline.mParameters.mapSize.y, info, out);
            final long toneMs = System.currentTimeMillis() - toneStart;
            // 4. the picture into the main texture
            out.position(0);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
            rgb = new GLTexture(size, new GLFormat(GLFormat.DataType.SIMPLE_8, 3), out, GL_NEAREST, GL_CLAMP_TO_EDGE);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
            WorkingTexture = pipeline.getMain();
            if (WorkingTexture == input) WorkingTexture = pipeline.getMain();
            glProg.useAssetProgram("oldstyle/rgb8", false);
            glProg.setTexture("InputBuffer", rgb);
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
            Log.i("SCAM_PIPELINE", "Oldstyle " + (useV2 ? "V2 (tone-port 3.1)" : "V1 (tone-port 2.7)") + " " + plan.describe()
                    + (useV2 && postview ? " postview" : "") + " grid=" + size.x + "x" + size.y + " samples=" + sw + "x" + sh
                    + " shorts=" + shorts + " frameTET=" + Math.round(frameTet) + " readback ms=" + (toneStart - readStart)
                    + " module ms=" + Math.round(info[4]) + " stages ms=" + Math.round(info[5]) + " (" + toneMs + ")"
                    + (params.dehaze ? String.format(java.util.Locale.ROOT, " dehaze shadow=%.0f white=%.0f cut=%.0f grid=%s", info[1],
                    info[2], info[3], shading != null ? "lens shading" : "unit") : " dehaze off")
                    + (!useV2 && plan.hdr ? (info[0] >= 0 ? " lowfreq x" + Math.round(info[0]) + "/32" : " lowfreq off") : "")
                    + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            for (GLTexture t : new GLTexture[]{fallback, clean, samples, frame, rgb}) if (t != null) t.close();
        }
    }
}
