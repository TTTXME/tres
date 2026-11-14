package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P614 «Why тон» V2, step 2: the reference tone's Laplacian pyramid exposure fusion (its stage 5), one to one - ef_pyr_prep at the
 * full grid (the lumas and weights of the four synthetic exposures, RGBA16F), the Gaussian pyramids of both by pyr_downsample down
 * to the level whose side reaches 48 px (WhyV2Ae.levels), ef_pyr_coarsest there and ef_pyr_collapse back up (R16F). The fused
 * display luma of level 0 goes to WhyV2Tone (State.fused). Level 0 is the full grid at every size V2 runs at, as the reference's
 * (it fuses at its merge's full size and downsizes only the finished 8-bit picture, its stage 7); the half grid above
 * {@link #FULL_LIMIT} is a guard only (V2 runs up to PostPipeline.V2_MAX_PIXELS, the same 100 MP). The image passes through.
 */
public final class WhyV2Fusion extends Node {
    public WhyV2Fusion() { super("", "WhyV2Fusion"); }
    @Override public void bindShader() {}

    /**
     * Above this many pixels level 0 would be half the grid (2x2 mean of the clean RGB): never on V2's grids (PostPipeline.V2_MAX_PIXELS).
     * A half grid is not the reference: its 3x3 regression then reads an upsampled fused luma (p99.9 30 codes off on real shots).
     */
    static final long FULL_LIMIT = PostPipeline.V2_MAX_PIXELS;

    /** The scale of level 0 against the grid: 1, or 2 above {@link #FULL_LIMIT}. */
    static int baseScale(int w, int h) {
        return (long) w * h > FULL_LIMIT ? 2 : 1;
    }

    private static GLTexture plane(int w, int h, int channels) {
        return new GLTexture(new Point(w, h), new GLFormat(GLFormat.DataType.FLOAT_16, channels), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
    }

    /** P677: the edge measure's reference, the guide's Gaussian this many levels down (WhyFusion.REF_DOWN, why/lpyr RefG). */
    static final int REF_DOWN = 3;

    /**
     * P677: the passes of V1's edge-aware bands on V2's pyramid ({@code lv}: its levels): (G, Fpp) of level 0 (why/v2_ef_prep MODE 2)
     * and its Gaussian pyramid by V2's own reduce, REF_DOWN - 1 levels beyond, the edge measure D of the levels 0 .. n - 2 against
     * the guide REF_DOWN levels down (why/v2_ef_bands MODE 0, k noise sigmas taken out: the fusion's noise pair x AE), the edge share
     * of the levels 1 .. n - 1 (MODE 1: the ramp of the level's cone max of D, at least the finer level's carry) - why/lpyr MODE 7 /
     * 2 / 8 / 9 / 10 of WhyFusion on this grid. Fills {@code gf}, {@code dd}, {@code sh}.
     */
    private void bandPasses(WhyV2Stats.State v2, WhyV2Ae.Params p, WhyV2Ae.Result r, float[] em, float[] lw, int[][] lv, float[] band,
                            PostPipeline pipeline, GLTexture[] gf, GLTexture[] dd, GLTexture[] sh) {
        final int n = lv.length, deep = gf.length;
        final int[][] sz = new int[deep][];
        for (int l = 0; l < deep; l++) {
            sz[l] = l < n ? lv[l] : new int[]{Math.max(sz[l - 1][0] >> 1, 1), sz[l - 1][1] > 3 ? sz[l - 1][1] / 2 : 1};
            gf[l] = plane(sz[l][0], sz[l][1], 2);
        }
        shaderProg.setDefine("MODE", 2);
        shaderProg.useAssetProgram("why/v2_ef_prep", false);
        shaderProg.setTexture("u_tex_clean_rgb", v2.clean);
        shaderProg.setVar("u_width", lv[0][0]);
        shaderProg.setVar("u_height", lv[0][1]);
        shaderProg.setVar("u_exp_multipliers", em[0], em[1], em[2], em[3]);
        shaderProg.setVar("u_layer_weights", lw[0], lw[1], lw[2], lw[3]);
        shaderProg.setVar("u_weight_center", p.weightCenter);
        shaderProg.setVar("u_auto_exposure", r.ae);
        shaderProg.setVar("u_blend_smoothness", p.blendSmoothness);
        shaderProg.setVar("u_gamma_curve_type", p.gammaType);
        shaderProg.setVar("u_gamma_inv", 1f / Math.max(p.gamma, 1f));
        shaderProg.setVar("u_aces_toe", p.acesToe);
        shaderProg.setVar("u_band_floor", band[2]);
        shaderProg.drawBlocks(gf[0]);
        for (int l = 1; l < deep; l++) {
            shaderProg.useAssetProgram("why/v2_pyr_down", false);
            shaderProg.setTexture("u_tex_src", gf[l - 1]);
            shaderProg.setVar("u_texel_size", 1f / sz[l - 1][0], 1f / sz[l - 1][1]);
            shaderProg.setVar("u_dst_w", sz[l][0]);
            shaderProg.setVar("u_dst_h", sz[l][1]);
            shaderProg.drawBlocks(gf[l]);
        }
        // the guide I's noise (x AE): the fusion's noise pair of the clean RGB at the full grid (WhyFusion.guideNoise without whyLow's
        // 2 x 2 mean); none without a model
        final float k = WhyTone.bandsNoise(), c = WhyTone.noiseScale();
        final boolean noise = k > 0f && Float.isFinite(pipeline.fusionNoiseS) && Float.isFinite(pipeline.fusionNoiseO)
                && (pipeline.fusionNoiseS > 0f || pipeline.fusionNoiseO > 0f) && r.ae > 0f;
        final float ns = noise ? c * r.ae * Math.max(pipeline.fusionNoiseS, 0f) : 0f, no = noise ? c * r.ae * r.ae * Math.max(pipeline.fusionNoiseO, 0f) : 0f;
        for (int l = 0; l + 1 < n; l++) {
            dd[l] = plane(sz[l][0], sz[l][1], 1);
            shaderProg.setDefine("MODE", 0);
            shaderProg.useAssetProgram("why/v2_ef_bands", false);
            shaderProg.setTexture("u_tex_GF", gf[l]);
            shaderProg.setTexture("u_tex_ref", gf[l + REF_DOWN]);
            shaderProg.setVar("u_width", sz[l][0]);
            shaderProg.setVar("u_height", sz[l][1]);
            shaderProg.setVar("u_band_noise", noise ? k * WhyTone.edgeBeta(l) : 0f, ns, no, band[2]);
            shaderProg.drawBlocks(dd[l]);
        }
        final float[] ramp0 = WhyTone.edgeRamp(band, 0);
        for (int l = 1; l < n; l++) {
            sh[l] = plane(sz[l][0], sz[l][1], 2);
            final float[] ramp = WhyTone.edgeRamp(band, l);
            shaderProg.setDefine("MODE", 1);
            shaderProg.useAssetProgram("why/v2_ef_bands", false);
            shaderProg.setTexture("u_tex_fine", l == 1 ? dd[0] : sh[l - 1]);
            shaderProg.setTexture("u_tex_D", l < n - 1 ? dd[l] : dd[0]);
            shaderProg.setVar("u_width", sz[l][0]);
            shaderProg.setVar("u_height", sz[l][1]);
            shaderProg.setVar("u_band_edge", ramp[0], ramp[1], ramp[2], 0f);
            shaderProg.setVar("u_band_reach", l == n - 1 ? -1 : (int) band[4], l == 1 ? (int) band[4] : 1);
            if (l == 1) shaderProg.setVar("u_band_carry", ramp0[0], ramp0[1], ramp0[2]);
            else shaderProg.setVar("u_band_carry", 1f, 0f, 0f);
            shaderProg.drawBlocks(sh[l]);
        }
        for (int l = 1; l + 1 < n; l++) {
            dd[l].close();
            dd[l] = null;
        }
        for (int l = n; l < deep; l++) {
            gf[l].close();
            gf[l] = null;
        }
    }

    @Override
    public void runPass() {
        final PostPipeline pipeline = (PostPipeline) hostPipeline;
        passOut = prevPass.passOut;
        shaderProg.closed = true;
        final WhyV2Stats.State v2 = pipeline.whyV2;
        if (v2 == null || v2.ae == null || v2.clean == null) throw new IllegalStateException("WHY V2: no statistics (WhyV2Stats did not run)");
        final long started = System.currentTimeMillis();
        final WhyV2Ae.Params p = v2.params;
        final WhyV2Ae.Result r = v2.ae;
        final Point grid = v2.clean.size;
        final int scale = baseScale(grid.x, grid.y);
        final int[][] lv = WhyV2Ae.levels((grid.x + scale - 1) / scale, (grid.y + scale - 1) / scale);
        final int n = lv.length;
        final GLTexture[] g = new GLTexture[n], w = new GLTexture[n], fu = new GLTexture[n];
        final float[] em = WhyV2Ae.exposures(r), lw = WhyV2Ae.layerWeights(p);
        // P673: the owner's joint bilateral collapse (0: off, texture() as the reference)
        final float jbu = WhyTone.jbuSigmaV2();
        final int jbuGuide = WhyTone.jbuGuideV2();
        // P677: V1's P391 edge-aware bands (null: off) - (G, Fpp) of every level and REF_DOWN - 1 beyond, D of the levels 0 .. n - 2,
        // the shares of the levels 1 .. n - 1
        final float[] band = scale == 1 ? WhyTone.edgeBandsV2() : null;
        final int deep = n + REF_DOWN - 1;
        final GLTexture[] gf = new GLTexture[deep], dd = new GLTexture[n], sh = new GLTexture[n];
        try {
            for (int l = 0; l < n; l++) {
                g[l] = plane(lv[l][0], lv[l][1], 4);
                w[l] = plane(lv[l][0], lv[l][1], 4);
                fu[l] = plane(lv[l][0], lv[l][1], 1);
            }
            for (int mode = 0; mode < 2; mode++) {
                shaderProg.setDefine("MODE", mode);
                if (scale == 2) shaderProg.setDefine("SCALE", 2);
                shaderProg.useAssetProgram("why/v2_ef_prep", false);
                shaderProg.setTexture("u_tex_clean_rgb", v2.clean);
                shaderProg.setVar("u_width", lv[0][0]);
                shaderProg.setVar("u_height", lv[0][1]);
                shaderProg.setVar("u_exp_multipliers", em[0], em[1], em[2], em[3]);
                shaderProg.setVar("u_layer_weights", lw[0], lw[1], lw[2], lw[3]);
                shaderProg.setVar("u_weight_center", p.weightCenter);
                shaderProg.setVar("u_auto_exposure", r.ae);
                shaderProg.setVar("u_blend_smoothness", p.blendSmoothness);
                shaderProg.setVar("u_gamma_curve_type", p.gammaType);
                shaderProg.setVar("u_gamma_inv", 1f / Math.max(p.gamma, 1f));
                shaderProg.setVar("u_aces_toe", p.acesToe);
                shaderProg.drawBlocks(mode == 0 ? g[0] : w[0]);
            }
            for (int l = 0; l + 1 < n; l++) {
                for (GLTexture[] pyr : new GLTexture[][]{g, w}) {
                    shaderProg.useAssetProgram("why/v2_pyr_down", false);
                    shaderProg.setTexture("u_tex_src", pyr[l]);
                    shaderProg.setVar("u_texel_size", 1f / lv[l][0], 1f / lv[l][1]);
                    shaderProg.setVar("u_dst_w", lv[l + 1][0]);
                    shaderProg.setVar("u_dst_h", lv[l + 1][1]);
                    shaderProg.drawBlocks(pyr[l + 1]);
                }
            }
            // P677: V1's edge-aware bands on this pyramid (null: off, the passes before P677)
            if (band != null) bandPasses(v2, p, r, em, lw, lv, band, pipeline, gf, dd, sh);
            if (band != null) shaderProg.setDefine("BANDS", 1);
            shaderProg.useAssetProgram("why/v2_ef_coarsest", false);
            shaderProg.setTexture("u_tex_G", g[n - 1]);
            shaderProg.setTexture("u_tex_W", w[n - 1]);
            shaderProg.setVar("u_width", lv[n - 1][0]);
            shaderProg.setVar("u_height", lv[n - 1][1]);
            if (band != null) {
                shaderProg.setTexture("u_tex_GF", gf[n - 1]);
                shaderProg.setTexture("u_tex_share", sh[n - 1]);
                shaderProg.setVar("u_band_strength", band[3]);
            }
            shaderProg.drawBlocks(fu[n - 1]);
            for (int l = n - 2; l >= 0; l--) {
                if (jbu > 0f) shaderProg.setDefine("JBU", WhyTone.jbuDefine(jbuGuide));
                if (band != null) shaderProg.setDefine("BANDS", 1);
                shaderProg.useAssetProgram("why/v2_ef_collapse", false);
                if (band != null) {
                    shaderProg.setTexture("u_tex_GF_fine", gf[l]);
                    shaderProg.setTexture("u_tex_GF_coarse", gf[l + 1]);
                    shaderProg.setTexture("u_tex_share", l == 0 ? dd[0] : sh[l]);
                    final float[] ramp0 = WhyTone.edgeRamp(band, 0);
                    shaderProg.setVar("u_band_edge", l == 0 ? ramp0[0] : 0f, l == 0 ? ramp0[1] : 0f, l == 0 ? ramp0[2] : 0f, band[3]);
                    shaderProg.setVar("u_band_reach", l == 0 ? (int) band[4] : -1);
                }
                if (jbu > 0f) {
                    shaderProg.setVar("u_jbu_sigma", jbu);
                    shaderProg.setVar("u_jbu_log", WhyTone.jbuLog(jbuGuide));
                }
                shaderProg.setTexture("u_tex_G_fine", g[l]);
                shaderProg.setTexture("u_tex_G_coarse", g[l + 1]);
                shaderProg.setTexture("u_tex_W_fine", w[l]);
                shaderProg.setTexture("u_tex_fused_coarse", fu[l + 1]);
                shaderProg.setVar("u_width", lv[l][0]);
                shaderProg.setVar("u_height", lv[l][1]);
                shaderProg.setVar("u_clarity", p.clarity);
                shaderProg.drawBlocks(fu[l]);
            }
            shaderProg.closed = true;
            v2.fused = fu[0];
            v2.fusedScale = scale;
            fu[0] = null;
        } finally {
            for (GLTexture[] a : new GLTexture[][]{gf, dd, sh}) for (GLTexture t : a) if (t != null) t.close();
            for (int l = 0; l < n; l++) {
                if (g[l] != null) g[l].close();
                if (w[l] != null) w[l].close();
                if (fu[l] != null) fu[l].close();
            }
        }
        Log.i("SCAM_PIPELINE", "WHY V2 fusion levels=" + n + " base=" + lv[0][0] + "x" + lv[0][1] + " coarsest=" + lv[n - 1][0] + "x"
                + lv[n - 1][1] + " em=" + em[0] + "," + em[1] + "," + em[2] + "," + em[3] + " lw=" + lw[0] + "," + lw[1] + "," + lw[2]
                + "," + lw[3] + " centre=" + p.weightCenter + " smooth=" + p.blendSmoothness
                + (jbu > 0f ? " jbu=" + jbu + "/" + jbuGuide : "") + (band != null ? " edgeBands=" + band[0] + ".." + band[1] + "EV x" + band[3] : "")
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
