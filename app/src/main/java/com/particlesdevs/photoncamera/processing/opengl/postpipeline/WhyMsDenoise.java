package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/** Post-CCM optics cleanup and the reference's Oklab Laplacian pyramid.
 * Filtering shaders retain the extracted arithmetic; only the camera/render RGB adapter is local.
 */
public final class WhyMsDenoise extends Node {
    public WhyMsDenoise() { super("", "WhyMsDenoise"); }

    @Override
    public void Compile() {}

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

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        final GLTexture input = previousNode.WorkingTexture;
        WorkingTexture = input;
        glProg.closed = true;
        if (!DenoiseStages.on()) return; // P600: the node is not added then (PostPipeline); a pass-through all the same
        final float[] toRender = ScamRgb.toRender(pipeline), fromRender = inverse3(toRender);
        if (fromRender == null) throw new IllegalStateException("Singular denoise colour matrix");
        final float[] wp = pipeline.mParameters.whitePoint;
        final long started = System.nanoTime();
        final float[] so = WhyResolveDenoise.shotNoise(pipeline.mParameters);
        final float model = modelFactor(so[0], so[1], WhyTone.value("ms_model_scale", 1.5f));
        final float[][] th = thresholds(model);
        final float shadow = Math.max(0f, WhyTone.value("ms_shadow", 1.5f));
        final boolean pyramid = enabled() && (th[0][0] > 0f || th[1][0] > 0f || th[2][0] > 0f
                || th[0][1] > 0f || th[1][1] > 0f || th[2][1] > 0f) && input.mSize.x >= 32 && input.mSize.y >= 32;
        whole = input.mSize;
        final int requested = Math.max(0, Math.round(PreferenceKeys.hybridValue("resolve_strip_rows", 0f)));
        final int rows = stripRows(whole.x, whole.y, requested);
        tiled = rows < whole.y;
        // P479b (speed, owner 2026-11-03: <= 1 level of the JPEG): one frame (not tiled) with the pyramid - level 0's rebuild and
        // rgb fused into finish (FUSED, scam_dev.txt "ms_fuse"), resolvedn/lab fused into optics (OUT_LAB, "ms_fuse_lab"): fewer
        // full-size passes, but both OFF by default - on the complete30 replay the photo moved by 1 level on 22 % of the pixels and
        // two isolated bright pixels by 10 - 11 levels (the lab fusion: 22 pixels by 3 - 11), over the tolerance; packHalf2x16 in
        // finish did not reproduce the targets' rounding on the Adreno 750 (the same difference). Off or the old GL model: every pass
        final boolean plain = pyramid && !tiled && !com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy();
        final boolean fuse = plain && PreferenceKeys.scamDevSwitch("ms_fuse", false);
        final boolean fuseLab = plain && PreferenceKeys.scamDevSwitch("ms_fuse_lab", false);
        // P477 (memory): level 0's Oklab plane has no reader after level 0's detail pass - its texture takes level 0's rebuild (the
        // same size and format, every pixel drawn) instead of a new one, and the frame weights go then too: the shot's texture peak
        // was here (three full-size RGBA16F planes next to the two mains: 564 MB at 12 MP). scam_dev.txt "ms_reuse 0" or the old GL model: a new texture as before
        final boolean reuse = !com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy() && PreferenceKeys.scamDevSwitch("ms_reuse", true);
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
                    glProg.useAssetProgram("resolvedn/prepare", false);
                    glProg.setTexture("InputBuffer", input);
                    glProg.setTexture("GainMap", pipeline.GainMap);
                    glProg.setVar("toRenderU", toRender);
                    glProg.setVar("neutralU", wp[0], wp[1], wp[2]);
                    glProg.setVar("originDstU", origin);
                    glProg.drawBlocks(prepared);

                    GLTexture filtered = tex(owned, 0, 4);
                    if (fuseLab) glProg.setDefine("OUT_LAB", 1);
                    program("optics", filtered);
                    bind("u_tex_rgb", prepared);
                    final boolean speckle = PreferenceKeys.hybridSwitch("resolve_speckle_enable", false);
                    glProg.setVar("u_quad_speckle_strength", speckle
                            ? Math.max(0f, Math.min(1f, PreferenceKeys.hybridValue("resolve_speckle_strength", 1f))) : 0f);
                    glProg.setVar("u_quad_speckle_threshold", Math.max(0.01f,
                            Math.min(0.5f, PreferenceKeys.hybridValue("resolve_speckle_threshold", 0.15f))));
                    glProg.drawBlocks(filtered);
                    release(owned, prepared);

                    if (pyramid) {
                        GLTexture weights = null;
                        if (codes != null) {
                            weights = tex(owned, 0, 1);
                            glProg.useAssetProgram("resolvedn/frames", false);
                            glProg.setTexture("EffMap", codes);
                            glProg.setVar("effScaleU", scale);
                            glProg.setVar("framesU", WhyResolveDenoise.defaultFrames(ScamHybridBurst.lastEffectiveMedian, ScamHybridBurst.lastNormals));
                            glProg.setVar("totalU", (float) Math.max(1, ScamHybridBurst.lastNormals));
                            glProg.setVar("originDstU", origin);
                            glProg.drawBlocks(weights);
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
                            glProg.drawBlocks(lab[0]);
                            release(owned, filtered);
                        }
                        filtered = null;
                        for (int l = 1; l <= 3; l++) {
                            lab[l] = tex(owned, l, 4);
                            program("down", lab[l]);
                            bind("u_tex_src", lab[l - 1]);
                            final Point fullSource = grids.get(lab[l - 1]).full;
                            final Point fullTarget = grids.get(lab[l]).full;
                            glProg.setVar("u_texel_size", 1f / fullSource.x, 1f / fullSource.y);
                            glProg.setVar("u_dst_w", fullTarget.x);
                            glProg.setVar("u_dst_h", fullTarget.y);
                            glProg.drawBlocks(lab[l]);
                        }
                        GLTexture rebuilt = tex(owned, 3, 4);
                        program("base", rebuilt);
                        bind("u_tex_src", lab[3]);
                        glProg.setVar("u_chroma_thresh", th[3][1]);
                        glProg.setVar("u_shadow_boost", shadow);
                        glProg.drawBlocks(rebuilt);
                        for (int l = 2; l >= 0; l--) {
                            final GLTexture detail = tex(owned, l, 4);
                            program("detail", detail);
                            bind("u_tex_fine", lab[l]);
                            bind("u_tex_coarse", lab[l + 1]);
                            bind("u_tex_sabre_weights", weights != null ? weights : lab[l]);
                            glProg.setVar("u_has_sabre_weights", weights != null ? 1 : 0);
                            glProg.setVar("u_level", l);
                            glProg.setVar("u_luma_thresh", th[l][0]);
                            glProg.setVar("u_chroma_thresh", th[l][1]);
                            glProg.setVar("u_shadow_boost", shadow);
                            glProg.drawBlocks(detail);
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
                            glProg.drawBlocks(next);
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
                            glProg.drawBlocks(filtered);
                            release(owned, rebuilt);
                        }
                    }
                    if (fusedDetail != null) glProg.setDefine("FUSED", 1);
                    glProg.useAssetProgram("resolvedn/finish", false);
                    glProg.setTexture("InputBuffer", input);
                    if (fusedDetail != null) {
                        glProg.setTexture("u_tex_coarse_recon", fusedCoarse);
                        glProg.setTexture("u_tex_laplace_filt", fusedDetail);
                        glProg.setVar("u_width", whole.x);
                        glProg.setVar("u_height", whole.y);
                    } else glProg.setTexture("Filtered", filtered);
                    glProg.setTexture("GainMap", pipeline.GainMap);
                    glProg.setVar("fromRenderU", fromRender);
                    glProg.setVar("neutralU", wp[0], wp[1], wp[2]);
                    glProg.setVar("filteredOriginU", origin);
                    drawRegion(out, y, count);
                } finally {
                    for (GLTexture t : owned) t.close();
                }
            }
            WorkingTexture = out;
            Log.i("SCAM_PIPELINE", "resolve post: pyramid=" + pyramid + " model=" + model + " shadow=" + shadow
                    + " rows=" + rows + " ms=" + (System.nanoTime() - started) / 1_000_000);
        } finally {
            if (codes != null) codes.close();
            grids.clear();
            glProg.closed = true;
        }
    }

    private void program(String name, GLTexture target) {
        final Grid grid = grids.get(target);
        glProg.setDefine("TILED", tiled ? 1 : 0);
        glProg.useAssetProgram("resolvedn/" + name, false);
        glProg.setVar("u_width", grid.full.x);
        glProg.setVar("u_height", grid.full.y);
        glProg.setVar("originDstU", grid.origin);
    }

    private void bind(String name, GLTexture texture) {
        glProg.setTexture(name, texture);
        if (tiled) {
            final Grid grid = grids.get(texture);
            glProg.setVar("full_" + name, grid.full.x, grid.full.y);
            glProg.setVar("origin_" + name, grid.origin);
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
        target.BufferLoad();
        for (int row = 0; row < height; row += 256) {
            android.opengl.GLES30.glViewport(0, y + row, target.mSize.x, Math.min(256, height - row));
            glProg.draw();
            com.particlesdevs.photoncamera.processing.opengl.GpuPace.block(); // P616: a submission per block while paced
        }
        android.opengl.GLES30.glFlush();
    }
}
