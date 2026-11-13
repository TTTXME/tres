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
    @Override public void Compile() {}

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

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        glProg.closed = true;
        final WhyV2Stats.State v2 = pipeline.whyV2;
        if (v2 == null || v2.ae == null || v2.clean == null) throw new IllegalStateException("WHY V2: no statistics (WhyV2Stats did not run)");
        final long started = System.currentTimeMillis();
        final WhyV2Ae.Params p = v2.params;
        final WhyV2Ae.Result r = v2.ae;
        final Point grid = v2.clean.mSize;
        final int scale = baseScale(grid.x, grid.y);
        final int[][] lv = WhyV2Ae.levels((grid.x + scale - 1) / scale, (grid.y + scale - 1) / scale);
        final int n = lv.length;
        final GLTexture[] g = new GLTexture[n], w = new GLTexture[n], fu = new GLTexture[n];
        final float[] em = WhyV2Ae.exposures(r), lw = WhyV2Ae.layerWeights(p);
        try {
            for (int l = 0; l < n; l++) {
                g[l] = plane(lv[l][0], lv[l][1], 4);
                w[l] = plane(lv[l][0], lv[l][1], 4);
                fu[l] = plane(lv[l][0], lv[l][1], 1);
            }
            for (int mode = 0; mode < 2; mode++) {
                glProg.setDefine("MODE", mode);
                if (scale == 2) glProg.setDefine("SCALE", 2);
                glProg.useAssetProgram("why/v2_ef_prep", false);
                glProg.setTexture("u_tex_clean_rgb", v2.clean);
                glProg.setVar("u_width", lv[0][0]);
                glProg.setVar("u_height", lv[0][1]);
                glProg.setVar("u_exp_multipliers", em[0], em[1], em[2], em[3]);
                glProg.setVar("u_layer_weights", lw[0], lw[1], lw[2], lw[3]);
                glProg.setVar("u_weight_center", p.weightCenter);
                glProg.setVar("u_auto_exposure", r.ae);
                glProg.setVar("u_blend_smoothness", p.blendSmoothness);
                glProg.setVar("u_gamma_curve_type", p.gammaType);
                glProg.setVar("u_gamma_inv", 1f / Math.max(p.gamma, 1f));
                glProg.setVar("u_aces_toe", p.acesToe);
                glProg.drawBlocks(mode == 0 ? g[0] : w[0]);
            }
            for (int l = 0; l + 1 < n; l++) {
                for (GLTexture[] pyr : new GLTexture[][]{g, w}) {
                    glProg.useAssetProgram("why/v2_pyr_down", false);
                    glProg.setTexture("u_tex_src", pyr[l]);
                    glProg.setVar("u_texel_size", 1f / lv[l][0], 1f / lv[l][1]);
                    glProg.setVar("u_dst_w", lv[l + 1][0]);
                    glProg.setVar("u_dst_h", lv[l + 1][1]);
                    glProg.drawBlocks(pyr[l + 1]);
                }
            }
            glProg.useAssetProgram("why/v2_ef_coarsest", false);
            glProg.setTexture("u_tex_G", g[n - 1]);
            glProg.setTexture("u_tex_W", w[n - 1]);
            glProg.setVar("u_width", lv[n - 1][0]);
            glProg.setVar("u_height", lv[n - 1][1]);
            glProg.drawBlocks(fu[n - 1]);
            for (int l = n - 2; l >= 0; l--) {
                glProg.useAssetProgram("why/v2_ef_collapse", false);
                glProg.setTexture("u_tex_G_fine", g[l]);
                glProg.setTexture("u_tex_G_coarse", g[l + 1]);
                glProg.setTexture("u_tex_W_fine", w[l]);
                glProg.setTexture("u_tex_fused_coarse", fu[l + 1]);
                glProg.setVar("u_width", lv[l][0]);
                glProg.setVar("u_height", lv[l][1]);
                glProg.setVar("u_clarity", p.clarity);
                glProg.drawBlocks(fu[l]);
            }
            glProg.closed = true;
            v2.fused = fu[0];
            v2.fusedScale = scale;
            fu[0] = null;
        } finally {
            for (int l = 0; l < n; l++) {
                if (g[l] != null) g[l].close();
                if (w[l] != null) w[l].close();
                if (fu[l] != null) fu[l].close();
            }
        }
        Log.i("SCAM_PIPELINE", "WHY V2 fusion levels=" + n + " base=" + lv[0][0] + "x" + lv[0][1] + " coarsest=" + lv[n - 1][0] + "x"
                + lv[n - 1][1] + " em=" + em[0] + "," + em[1] + "," + em[2] + "," + em[3] + " lw=" + lw[0] + "," + lw[1] + "," + lw[2]
                + "," + lw[3] + " centre=" + p.weightCenter + " smooth=" + p.blendSmoothness + " ms=" + (System.currentTimeMillis() - started));
    }
}
