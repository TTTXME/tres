package com.scamera.app.processing.opengl.postpipeline;

/**
 * P713 (speed): the highlight landing's mask (why/hlmask.glsl, a gaussian of 1.2 % of the frame's width over the share of the
 * ceiling on whyLow) on a coarser grid. A gaussian of sigma texels needs no more than ~sigma / 6 of its samples per sigma: the
 * share is averaged over {@link Plan#block} x {@link Plan#block} whyLow texels (DECIMATE 1), the two blur passes run on that grid
 * with the sigma that is left once the averaging block's and the bilinear read's spread are taken out, and the combine reads the
 * result bilinearly through {@link Plan#grid} (HL_COARSE 1). The grid has {@link Plan#margin} texels on every side whose blocks
 * lie outside the frame: their texels are the frame's edge texels (clamped), as the clamped gaussian on whyLow sees beyond its
 * edges. tools/check_hlmask_coarse.py: the mask within 0.008 of the whyLow one (1 - 2 half-float steps of the share), the photo
 * within one 8-bit level.
 */
final class CoarseMask {
    /** The blur's sigma on the coarse grid is about this many coarse texels (the block is sigma / this, rounded). */
    static final float TEXELS_PER_SIGMA = 6f;
    /** The largest block (a 200 MP whyLow of 8160 texels: sigma 98, block 8, sigma 12.2 coarse texels). */
    static final int MAX_BLOCK = 8;

    private CoarseMask() {}

    /** One shot's coarse grid. */
    static final class Plan {
        /** whyLow texels a side of a block. */
        final int block;
        /** The coarse grid's size, margins included. */
        final int width, height;
        /** Coarse texels of margin on each side. */
        final int margin;
        /** whyLow texels per coarse texel along x and y (whyLow's size / the inner grid's: about {@link #block}). */
        final float scaleX, scaleY;
        /** The gaussian's sigma in coarse texels. */
        final float sigma;
        /** The frame's uv to the grid's uv: uv * (x, y) + (z, w). */
        final float[] grid;

        Plan(int block, int innerW, int innerH, int margin, float scaleX, float scaleY, float sigma) {
            this.block = block;
            this.margin = margin;
            this.width = innerW + 2 * margin;
            this.height = innerH + 2 * margin;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.sigma = sigma;
            this.grid = new float[]{(float) innerW / width, (float) innerH / height, (float) margin / width, (float) margin / height};
        }
    }

    /**
     * The coarse grid for a whyLow of {@code lowW} x {@code lowH} and a gaussian of {@code sigma} whyLow texels; null when it is
     * off ({@code on} false) or the block would be 1 (sigma under 9 texels: whyLow under ~750 texels wide).
     */
    static Plan plan(int lowW, int lowH, float sigma, boolean on) {
        if (!on || lowW <= 0 || lowH <= 0 || !(sigma > 0f)) return null;
        final int block = Math.min(MAX_BLOCK, Math.round(sigma / TEXELS_PER_SIGMA));
        if (block < 2) return null;
        final int innerW = Math.max(1, Math.round((float) lowW / block)), innerH = Math.max(1, Math.round((float) lowH / block));
        final float scaleX = (float) lowW / innerW, scaleY = (float) lowH / innerH;
        // the block's mean adds (k^2 - 1) / 12 to the variance, the bilinear read about k^2 / 12 on average
        final double variance = (double) sigma * sigma - (block * block - 1) / 12.0 - block * block / 12.0;
        final float coarseSigma = (float) (Math.sqrt(Math.max(variance, 0.25)) / (0.5 * (scaleX + scaleY)));
        final int margin = (int) Math.ceil(3.0 * coarseSigma) + 1;
        return new Plan(block, innerW, innerH, margin, scaleX, scaleY, coarseSigma);
    }
}
