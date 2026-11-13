package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/** Post-CCM optics cleanup and the reference's Oklab Laplacian pyramid.
 * Filtering shaders retain the extracted arithmetic; only the camera/render RGB adapter is local.
 */
public final class WhyMsDenoise extends Node {
    public WhyMsDenoise() { super("", "WhyMsDenoise"); }

    @Override
    public void bindShader() {}

    static boolean enabled() {
        return DenoiseStages.on() && PreferenceKeys.hybridSwitch("why_ms_denoise", true);
    }

    /** The noise model's factor of the thresholds (1 without a model or with the scale at 0). */
    static float modelFactor(float noiseS, float noiseO, float scale) {
        if (!(scale > 0.001f) || !(noiseS > 0f || noiseO > 0f)) return 1f;
        final double sigma = Math.sqrt(Math.max(Math.max(noiseS, 0f) * 0.18 + Math.max(noiseO, 0f), 1e-10));
        final double nf = Math.max(0.4, Math.min(3.5, 20.0 / Math.max(0.18 / sigma, 1.0)));
        return (float) Math.max(0.25, Math.min(4.0, 1.0 + (nf - 1.0) * scale));
    }

    /** The levels' default shares (the owner's reference config, 2026-10-29). */
    static final float[] LUMA_LEVELS = {0.4f, 0.1f, 0.35f}, CHROMA_LEVELS = {0.2f, 0.4f, 0.6f};

    /** {luma, chroma} thresholds of level 0, 1, 2 and the base's chroma (index 3): the settings x the model factor. */
    static float[][] thresholds(float model) {
        final float luma = 0.012f * Math.max(0f, WhyTone.value("ms_luma", 0.2f)) * model;
        final float chroma = 0.02f * Math.max(0f, WhyTone.value("ms_chroma", 0.5f)) * model;
        final float[][] t = new float[4][2];
        for (int l = 0; l < 3; l++) {
            t[l][0] = luma * Math.max(0f, WhyTone.value("ms_luma_l" + l, LUMA_LEVELS[l]));
            t[l][1] = chroma * Math.max(0f, WhyTone.value("ms_chroma_l" + l, CHROMA_LEVELS[l]));
        }
        t[3][1] = t[2][1];   // the base takes the coarsest level's colour share, as the reference app's driver
        return t;
    }

    static float[] inverse3(float[] m) {
        final double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        final double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12) return null;
        return new float[]{(float) ((e * i - f * h) / det), (float) ((c * h - b * i) / det), (float) ((b * f - c * e) / det),
                (float) ((f * g - d * i) / det), (float) ((a * i - c * g) / det), (float) ((c * d - a * f) / det),
                (float) ((d * h - e * g) / det), (float) ((b * g - a * h) / det), (float) ((a * e - b * d) / det)};
    }

    /** The motion boost's {luminance, colour} at full motion for the setting {@code k} (1: the reference app's 0.30, 0.50). */
    static float[] motionBoost(float k) {
        k = Math.max(0f, Math.min(3f, k));
        return new float[]{0.30f * k, 0.50f * k};
    }

    /** Frames per code of the merge's frame map (its median code 64 stands for {@code median} frames); NaN without one. */
    static float effScale(float median) {
        return median > 0f && !Float.isInfinite(median) ? median / 64f : Float.NaN;
    }

    private static Point half(Point p) { return new Point((p.x + 1) / 2, (p.y + 1) / 2); }

    static int stripRows(int width, int height, int requested) {
        if (requested <= 0 && (long) width * height <= 16_000_000L) return height;
        // Whole-pyramid sampling positions stay aligned to its three powers of two.
        if ((width & 7) != 0 || (height & 7) != 0) return height;
        final long budget = 256L << 20;
        final int rows = requested == 1 ? 128 : requested > 0 ? requested
                : (int) Math.max(8L, budget / (Math.max(1L, width) * 40L) - 128L);
        return Math.min(height, Math.max(8, rows / 8 * 8));
    }

    private static final class Grid {
        final Point full;
        final int origin;
        Grid(Point full, int origin) { this.full = full; this.origin = origin; }
    }
    private final java.util.IdentityHashMap<GLTexture, Grid> grids = new java.util.IdentityHashMap<>();
    private Point whole;
    private int origin, end;
    private boolean tiled;
    /** P671: this run's optics read the Oklab cache (for the log line). */
    private boolean okCacheUsed;

    @Override
    public void runPass() {
        final PostPipeline pipeline = (PostPipeline) hostPipeline;
        final GLTexture input = prevPass.passOut;
        passOut = input;
        shaderProg.closed = true;
        if (!DenoiseStages.on()) return; // P600: the node is not added then (PostPipeline); a pass-through all the same
        final float[] toRender = ScamRgb.toRender(pipeline), fromRender = inverse3(toRender);
        if (fromRender == null) throw new IllegalStateException("Singular denoise colour matrix");
        final float[] wp = pipeline.shotParams.asShotNeutral;
        final long started = System.nanoTime();
        final float[] so = WhyResolveDenoise.shotNoise(pipeline.shotParams);
        final float model = modelFactor(so[0], so[1], WhyTone.value("ms_model_scale", 1.5f));
        final float[][] th = thresholds(model);
        final float shadow = Math.max(0f, WhyTone.value("ms_shadow", 1.5f));
        final boolean pyramid = enabled() && (th[0][0] > 0f || th[1][0] > 0f || th[2][0] > 0f
                || th[0][1] > 0f || th[1][1] > 0f || th[2][1] > 0f) && input.size.x >= 32 && input.size.y >= 32;
        whole = input.size;
        okCacheUsed = false;
        final int requested = Math.max(0, Math.round(PreferenceKeys.hybridValue("resolve_strip_rows", 0f)));
        final int rows = stripRows(whole.x, whole.y, requested);
        tiled = rows < whole.y;
        // P479b (speed, owner 2026-11-03: <= 1 level of the JPEG): one frame (not tiled) with the pyramid - level 0's rebuild and
        // rgb fused into finish (FUSED, scam_dev.txt "ms_fuse"), resolvedn/lab fused into optics (OUT_LAB, "ms_fuse_lab"): fewer
        // full-size passes, but both OFF by default - on the complete30 replay the photo moved by 1 level on 22 % of the pixels and
        // two isolated bright pixels by 10 - 11 levels (the lab fusion: 22 pixels by 3 - 11), over the tolerance; packHalf2x16 in
        // finish did not reproduce the targets' rounding on the Adreno 750 (the same difference). Off or the old GL model: every pass
        final boolean plain = pyramid && !tiled && !com.scamera.app.processing.opengl.PostGlMode.legacy();
        final boolean fuse = plain && PreferenceKeys.scamDevSwitch("ms_fuse", false);
        final boolean fuseLab = plain && PreferenceKeys.scamDevSwitch("ms_fuse_lab", false);
        // P477 (memory): level 0's Oklab plane has no reader after level 0's detail pass - its texture takes level 0's rebuild (the
        // same size and format, every pixel drawn) instead of a new one, and the frame weights go then too: the shot's texture peak
        // was here (three full-size RGBA16F planes next to the two mains: 564 MB at 12 MP). scam_dev.txt "ms_reuse 0" or the old GL model: a new texture as before
        final boolean reuse = !com.scamera.app.processing.opengl.PostGlMode.legacy() && PreferenceKeys.scamDevSwitch("ms_reuse", true);
        final GLTexture out = pipeline.getMain();
        GLTexture codes = null;
        final java.nio.ByteBuffer eff = ScamHybridBurst.lastEffectiveFrames;
        final float scale = effScale(ScamHybridBurst.lastEffectiveMedian);
        try {
            if (pyramid && eff != null && Float.isFinite(scale) && eff.capacity() == (long) whole.x * whole.y)
                codes = DenoiseStats.effectiveFramesTexture(whole, eff);
            for (int y = 0; y < whole.y; y += rows) {
                final int count = Math.min(rows, whole.y - y);
                origin = tiled ? Math.max(0, y - 64) : 0;
                end = tiled ? Math.min(whole.y, y + count + 64) : whole.y;
                grids.clear();
                grids.put(input, new Grid(whole, 0));
                final List<GLTexture> owned = new ArrayList<>();
                GLTexture fusedDetail = null, fusedCoarse = null; // P479b: in owned, closed with it
                try {
                    GLTexture prepared = tex(owned, 0, 4);
                    shaderProg.useAssetProgram("resolvedn/prepare", false);
                    shaderProg.setTexture("InputBuffer", input);
                    shaderProg.setTexture("GainMap", pipeline.GainMap);
                    shaderProg.setVar("toRenderU", toRender);
                    shaderProg.setVar("neutralU", wp[0], wp[1], wp[2]);
                    shaderProg.setVar("originDstU", origin);
                    shaderProg.drawBlocks(prepared);

                    GLTexture filtered = tex(owned, 0, 4);
                    final boolean speckle = PreferenceKeys.hybridSwitch("resolve_speckle_enable", false);
                    final float speckleStrength = speckle
                            ? Math.max(0f, Math.min(1f, PreferenceKeys.hybridValue("resolve_speckle_strength", 1f))) : 0f;
                    final float speckleThreshold = Math.max(0.01f,
                            Math.min(0.5f, PreferenceKeys.hybridValue("resolve_speckle_threshold", 0.15f)));
                    // P671: the corner filter's Oklab neighbours from a per-strip cache (the same numbers; not tiled)
                    final boolean cached = !tiled && oklabCache() && opticsCached(prepared, filtered, fuseLab, speckleStrength, speckleThreshold);
                    okCacheUsed |= cached;
                    if (!cached) {
                        if (fuseLab) shaderProg.setDefine("OUT_LAB", 1);
                        program("optics", filtered);
                        bind("u_tex_rgb", prepared);
                        shaderProg.setVar("u_quad_speckle_strength", speckleStrength);
                        shaderProg.setVar("u_quad_speckle_threshold", speckleThreshold);
                        shaderProg.drawBlocks(filtered);
                    }
                    release(owned, prepared);

                    if (pyramid) {
                        GLTexture weights = null;
                        if (codes != null) {
                            weights = tex(owned, 0, 1);
                            shaderProg.useAssetProgram("resolvedn/frames", false);
                            shaderProg.setTexture("EffMap", codes);
                            shaderProg.setVar("effScaleU", scale);
                            shaderProg.setVar("framesU", WhyResolveDenoise.defaultFrames(ScamHybridBurst.lastEffectiveMedian, ScamHybridBurst.lastNormals));
                            shaderProg.setVar("totalU", (float) Math.max(1, ScamHybridBurst.lastNormals));
                            shaderProg.setVar("originDstU", origin);
                            shaderProg.drawBlocks(weights);
                            // P477: one frame - the merge's codes have no other reader (a tiled frame reads them per strip)
                            if (reuse && !tiled) { codes.close(); codes = null; }
                        }
                        final GLTexture[] lab = new GLTexture[4];
                        if (fuseLab) {
                            lab[0] = filtered; // P479b: optics wrote Oklab
                        } else {
                            lab[0] = tex(owned, 0, 4);
                            program("lab", lab[0]);
                            bind("u_tex_rgb", filtered);
                            shaderProg.drawBlocks(lab[0]);
                            release(owned, filtered);
                        }
                        filtered = null;
                        for (int l = 1; l <= 3; l++) {
                            lab[l] = tex(owned, l, 4);
                            program("down", lab[l]);
                            bind("u_tex_src", lab[l - 1]);
                            final Point fullSource = grids.get(lab[l - 1]).full;
                            final Point fullTarget = grids.get(lab[l]).full;
                            shaderProg.setVar("u_texel_size", 1f / fullSource.x, 1f / fullSource.y);
                            shaderProg.setVar("u_dst_w", fullTarget.x);
                            shaderProg.setVar("u_dst_h", fullTarget.y);
                            shaderProg.drawBlocks(lab[l]);
                        }
                        GLTexture rebuilt = tex(owned, 3, 4);
                        program("base", rebuilt);
                        bind("u_tex_src", lab[3]);
                        shaderProg.setVar("u_chroma_thresh", th[3][1]);
                        shaderProg.setVar("u_shadow_boost", shadow);
                        shaderProg.drawBlocks(rebuilt);
                        for (int l = 2; l >= 0; l--) {
                            final GLTexture detail = tex(owned, l, 4);
                            program("detail", detail);
                            bind("u_tex_fine", lab[l]);
                            bind("u_tex_coarse", lab[l + 1]);
                            bind("u_tex_sabre_weights", weights != null ? weights : lab[l]);
                            shaderProg.setVar("u_has_sabre_weights", weights != null ? 1 : 0);
                            shaderProg.setVar("u_level", l);
                            shaderProg.setVar("u_luma_thresh", th[l][0]);
                            shaderProg.setVar("u_chroma_thresh", th[l][1]);
                            shaderProg.setVar("u_shadow_boost", shadow);
                            shaderProg.drawBlocks(detail);
                            if (fuse && l == 0) {
                                // P479b: level 0's sum and its RGB are finish's (fusedDetail + fusedCoarse)
                                fusedDetail = detail;
                                fusedCoarse = rebuilt;
                                release(owned, lab[1]);
                                break;
                            }
                            final GLTexture next;
                            if (reuse && l == 0) {
                                // P477: level 0's plane is read by its detail pass only (fine, and the weights' stand-in without
                                // weights); a coarser level's is the next level's coarse input, so only level 0's is free here
                                next = lab[l];
                                lab[l] = null;
                            } else next = tex(owned, l, 4);
                            if (reuse && l == 0) release(owned, weights); // P477: its last reader was level 0's detail pass
                            program("rebuild", next);
                            bind("u_tex_coarse_recon", rebuilt);
                            bind("u_tex_laplace_filt", detail);
                            shaderProg.drawBlocks(next);
                            release(owned, detail);
                            release(owned, rebuilt);
                            release(owned, lab[l + 1]);
                            rebuilt = next;
                        }
                        release(owned, lab[0]);
                        release(owned, weights);
                        if (!fuse) {
                            filtered = tex(owned, 0, 4);
                            program("rgb", filtered);
                            bind("u_tex_oklab", rebuilt);
                            shaderProg.drawBlocks(filtered);
                            release(owned, rebuilt);
                        }
                    }
                    if (fusedDetail != null) shaderProg.setDefine("FUSED", 1);
                    shaderProg.useAssetProgram("resolvedn/finish", false);
                    shaderProg.setTexture("InputBuffer", input);
                    if (fusedDetail != null) {
                        shaderProg.setTexture("u_tex_coarse_recon", fusedCoarse);
                        shaderProg.setTexture("u_tex_laplace_filt", fusedDetail);
                        shaderProg.setVar("u_width", whole.x);
                        shaderProg.setVar("u_height", whole.y);
                    } else shaderProg.setTexture("Filtered", filtered);
                    shaderProg.setTexture("GainMap", pipeline.GainMap);
                    shaderProg.setVar("fromRenderU", fromRender);
                    shaderProg.setVar("neutralU", wp[0], wp[1], wp[2]);
                    shaderProg.setVar("filteredOriginU", origin);
                    drawRegion(out, y, count);
                } finally {
                    for (GLTexture t : owned) t.close();
                }
            }
            passOut = out;
            Log.i("SCAM_PIPELINE", "resolve post: pyramid=" + pyramid + " model=" + model + " shadow=" + shadow
                    + " opticsOklab=" + (okCacheUsed ? "cached" : "inline")
                    + " rows=" + rows + " ms=" + (System.nanoTime() - started) / 1_000_000);
        } finally {
            if (codes != null) codes.close();
            grids.clear();
            shaderProg.closed = true;
        }
    }

    /** P671: the optics strips' rows (the cache holds two more above and below: 4 x 260 x 16 B a column, 17 MB at 4096 wide). */
    static final int OPTICS_STRIP = 256;

    /**
     * P671: scam_dev ms_oklab_cache (default on; off or the old GL model: as before) - resolvedn/optics' corner filter reads its
     * neighbours' Oklab from a cache written per strip (OKLAB_CACHE 1: rgb_to_oklab of every pixel of the strip and two rows on
     * each side into RGBA32F, exact floats; OKLAB_CACHE 2: the filter on the strip's rows): each pixel converted once instead of
     * up to 25 times, the same numbers (tools/check_ms_oklab_cache.py: bit for bit).
     */
    static boolean oklabCache() {
        return !com.scamera.app.processing.opengl.PostGlMode.legacy() && PreferenceKeys.scamDevSwitch("ms_oklab_cache", true);
    }

    /** P671: optics over {@code filtered} in strips with the Oklab cache; false (nothing drawn, no error left) when it cannot. */
    private boolean opticsCached(GLTexture prepared, GLTexture filtered, boolean fuseLab, float speckleStrength, float speckleThreshold) {
        final int w = whole.x, h = whole.y;
        GLTexture cache = null;
        try {
            while (android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR) {
                // drain: only the passes below decide
            }
            cache = new GLTexture(new Point(w, Math.min(h, OPTICS_STRIP + 4)), new GLFormat(GLFormat.DataType.FLOAT_32, 4), null,
                    GL_NEAREST, GL_CLAMP_TO_EDGE);
            final int[] colour0 = {android.opengl.GLES30.GL_COLOR_ATTACHMENT0};
            final boolean invalidate = com.scamera.app.processing.opengl.PostGlMode.invalidate();
            for (int y = 0; y < h; y += OPTICS_STRIP) {
                final int rows = Math.min(OPTICS_STRIP, h - y);
                final int first = Math.max(0, y - 2), stop = Math.min(h, y + rows + 2);
                shaderProg.setDefine("OKLAB_CACHE", 1);
                program("optics", filtered);
                bind("u_tex_rgb", prepared);
                shaderProg.setVar("cacheOriginU", first);
                cache.renderInto();
                if (invalidate) android.opengl.GLES30.glInvalidateFramebuffer(android.opengl.GLES30.GL_FRAMEBUFFER, 1, colour0, 0);
                android.opengl.GLES30.glViewport(0, 0, w, stop - first);
                shaderProg.draw();
                android.opengl.GLES30.glFlush();
                com.scamera.app.processing.opengl.GpuPace.block();
                if (fuseLab) shaderProg.setDefine("OUT_LAB", 1);
                shaderProg.setDefine("OKLAB_CACHE", 2);
                program("optics", filtered);
                bind("u_tex_rgb", prepared);
                shaderProg.setTexture("OkCache", cache);
                shaderProg.setVar("cacheOriginU", first);
                shaderProg.setVar("u_quad_speckle_strength", speckleStrength);
                shaderProg.setVar("u_quad_speckle_threshold", speckleThreshold);
                filtered.renderInto();
                if (invalidate && y == 0) android.opengl.GLES30.glInvalidateFramebuffer(android.opengl.GLES30.GL_FRAMEBUFFER, 1, colour0, 0);
                android.opengl.GLES30.glViewport(0, y, w, rows);
                shaderProg.draw();
                android.opengl.GLES30.glFlush();
                com.scamera.app.processing.opengl.GpuPace.block();
            }
            final int error = android.opengl.GLES30.glGetError();
            if (error != android.opengl.GLES30.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(error));
            return true;
        } catch (RuntimeException failure) {
            Log.w("SCAM_PIPELINE", "resolve optics Oklab cache: " + failure + ", the plain pass");
            shaderProg.setDefine("OKLAB_CACHE", 0); // a failed compile leaves its defines pending: the plain pass's own
            return false;
        } finally {
            if (cache != null) cache.close(); // (released when the GPU is done with it)
        }
    }

    private void program(String name, GLTexture target) {
        final Grid grid = grids.get(target);
        shaderProg.setDefine("TILED", tiled ? 1 : 0);
        shaderProg.useAssetProgram("resolvedn/" + name, false);
        shaderProg.setVar("u_width", grid.full.x);
        shaderProg.setVar("u_height", grid.full.y);
        shaderProg.setVar("originDstU", grid.origin);
    }

    private void bind(String name, GLTexture texture) {
        shaderProg.setTexture(name, texture);
        if (tiled) {
            final Grid grid = grids.get(texture);
            shaderProg.setVar("full_" + name, grid.full.x, grid.full.y);
            shaderProg.setVar("origin_" + name, grid.origin);
        }
    }

    private GLTexture tex(List<GLTexture> owned, int level, int channels) {
        final Point full = new Point(Math.max(1, whole.x >> level), Math.max(1, whole.y >> level));
        final int first = origin >> level;
        final Point size = new Point(full.x, Math.max(1, (end >> level) - first));
        final GLTexture t = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, channels), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        owned.add(t);
        grids.put(t, new Grid(full, first));
        return t;
    }

    private void release(List<GLTexture> owned, GLTexture t) {
        if (t != null && owned.remove(t)) { grids.remove(t); t.close(); }
    }

    private void drawRegion(GLTexture target, int y, int height) {
        target.renderInto();
        for (int row = 0; row < height; row += 256) {
            android.opengl.GLES30.glViewport(0, y + row, target.size.x, Math.min(256, height - row));
            shaderProg.draw();
            com.scamera.app.processing.opengl.GpuPace.block(); // P616: a submission per block while paced
        }
        android.opengl.GLES30.glFlush();
    }
}
