package com.particlesdevs.photoncamera.processing.processor;

import android.graphics.Point;
import android.graphics.Rect;

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.RawCrop;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * P576 (owner, 2026-11-09: «У нас при цифровом кропе должны работать алгоритмы суперразрешения Sabre»): a crop shot - the digital
 * zoom's residual, a module's lens crop (P148), the nubia «2×» module (P575: the 50 MP Quad row at zoom 2) - was merged whole
 * and its photo cropped and enlarged afterwards (ZoomController.crop + the P226 upscale): an upscale of a sensor-grid merge.
 * Now the burst is cut to the crop's window BEFORE the merge and the window is merged on the Sabre grid the photo needs
 * (a 2x zoom on a 12 MP Bayer: a 3 MP window on the 2x grid = the 12 MP photo; nubia 50 MP Quad at 2x: a 12.5 MP window on the
 * 2x grid = 50 MP), so the photo's detail is the merge's own.
 * <p>
 * The window is centred on the frame (as the digital zoom), its origin on whole colour periods ({@link #period}: 2 px Bayer,
 * 2 x block for Quad / Tetra, 8 when the block is unknown) and its sides symmetric (frame - 2 x origin): the CFA order and the
 * black level of each CFA phase stay the frame's, and the crop the photo still needs ({@link Plan#rx} / {@link Plan#ry}, at most
 * one period per side) is centred. The levels, the white balance and the mosaic neutral are measured on the whole frames
 * before the cut (as before); {@link Parameters#applySabreCrop} takes the lens shading map, the hot pixels, the face boxes and
 * the RAW size to the window; the DNG, the gain map of Ultra HDR and the post pipeline follow the window's RAW size.
 * «Увеличивать кадр с кропом» off (P226 keepCrop): the window on the sensor grid (the photo keeps its pixels, as before, just
 * cheaper). Nothing changes without a crop (residual at most 1.005). scam_dev "sabre_crop 0": the whole frame as before.
 */
public final class SabreCrop {
    private SabreCrop() {}

    static final String TAG = "SCAM_HDR";
    /** The residual of a crop shot (ScamProcessor's rule). */
    static final float MIN_RESIDUAL = 1.005f;
    /** The least window side (the merge's 64 px rule, ScamHybridBurst). */
    static final int MIN_SIDE = 64;
    /**
     * The largest grid the window is merged on (px): the Sabre 2x grid of a 12.6 MP frame (8192 x 6144, the «2×» output the
     * X200 Ultra and the nubia's 50 MP row already merge). The SCAM route merges a scale between 1x and 2x on the 2x grid.
     */
    static final long MAX_GRID_PIXELS = 52_000_000L;
    /** The photo takes the target size itself when the crop is within this many px of it (no resample by a ratio ~1). */
    static final int SNAP_PX = 2;

    /** The colour period of a stream of colour block {@code block} (1 Bayer, 2 Quad, 4 Tetra; else unknown: Tetra's 8). */
    static int period(int block) {
        return block == 1 ? 2 : block == 2 ? 4 : 8;
    }

    /**
     * The window of a {@code w} x {@code h} frame for a centred crop of {@code residual}: origin = the crop's corner rounded down
     * to {@code period}, sides = frame - 2 x origin (symmetric, even, the crop centred inside). Null for no crop, a window that is
     * the whole frame, or a side below {@link #MIN_SIDE}.
     */
    static RawCrop.Area window(int w, int h, double residual, int period) {
        if (w <= 0 || h <= 0 || !(residual > MIN_RESIDUAL) || period < 2 || (period & 1) != 0) return null;
        final int x = origin(w, residual, period), y = origin(h, residual, period);
        if (x == 0 && y == 0) return null;
        final int ww = w - 2 * x, wh = h - 2 * y;
        if (ww < MIN_SIDE || wh < MIN_SIDE || (ww & 1) != 0 || (wh & 1) != 0) return null;
        return new RawCrop.Area(x, y, ww, wh);
    }

    /** The window's origin on a side of {@code side} px: the crop's corner (side - side / residual) / 2, down to {@code period}. */
    static int origin(int side, double residual, int period) {
        final double corner = (side - side / residual) / 2.0;
        return Math.max(0, (int) Math.floor(corner / period + 1e-9) * period);
    }

    /**
     * The photo size an uncropped merge of a {@code w} x {@code h} frame gives today (P226: the size the crop is brought back to):
     * the Sabre scale's grid when it fits ({@code twoXFits}, output {@code mode} not "sensor"), else the sensor grid, reduced to
     * the final size {@code fin} (pref «Уменьшить до» / the 12 / 16 / 20 MP outputs) the way ScamProcessor resizes.
     */
    static Point uncroppedPhoto(int w, int h, String mode, boolean twoXFits, Point merged, Point fin) {
        final Point out = !"sensor".equals(mode) && twoXFits && merged != null ? merged : new Point(w, h);
        return ScamProcessor.photoSize(out.x, out.y, fin != null && (long) fin.x * fin.y < (long) out.x * out.y ? fin : null);
    }

    /**
     * The grid the window ({@code ww} x {@code wh}) is merged on for a photo of {@code target} from a crop of {@code cw} x
     * {@code ch} sensor px: the scale the photo needs (target / crop), at least the sensor grid and at most the Sabre 2x grid,
     * the width on 4 px and the height on 2 px (the worker's Sabre scale words); {@code target} null: the sensor grid.
     */
    static Point grid(int ww, int wh, double cw, double ch, Point target) {
        if (target == null) return new Point(ww, wh);
        final double sx = target.x / cw, sy = target.y / ch;
        if (sx <= 1.0005 && sy <= 1.0005) return new Point(ww, wh);
        if (sx >= 1.9995 && sy >= 1.9995) return new Point(2 * ww, 2 * wh);
        int ow = (int) Math.ceil(Math.min(2.0, Math.max(1.0, sx)) * ww / 4.0 - 1e-9) * 4;
        int oh = (int) Math.ceil(Math.min(2.0, Math.max(1.0, sy)) * wh / 2.0 - 1e-9) * 2;
        ow = Math.max(ww + 3 & ~3, Math.min(2 * ww, ow));
        oh = Math.max(wh, Math.min(2 * wh, oh));
        if (ow > 2 * ww) ow = 2 * ww;
        return new Point(ow, oh);
    }

    /** What a crop shot's merge does: the window of the frame, its merged grid and the photo it gives. */
    public static final class Plan {
        /** The window in the frame as the merge got it (after P162's RAW crop). */
        public final RawCrop.Area area;
        /** The frame's size before the cut. */
        public final int frameWidth, frameHeight;
        /** The press's crop (ZoomController's residual) and the colour period the window keeps. */
        public final float residual;
        public final int period;
        /** The window's merged size (RGB from the worker): the sensor grid, a Sabre scale, the 2x grid. */
        public final int outWidth, outHeight;
        /** The photo's size (the uncropped photo's, P226); null: «Увеличивать кадр с кропом» off - the crop keeps its pixels. */
        public final Point target;
        /** The crop still to make of the merged window, per sensor axis (window / crop, 1 .. 1 + 2 periods / crop). */
        public final double rx, ry;

        Plan(RawCrop.Area area, int frameWidth, int frameHeight, float residual, int period, int outWidth, int outHeight, Point target) {
            this.area = area;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.residual = residual;
            this.period = period;
            this.outWidth = outWidth;
            this.outHeight = outHeight;
            this.target = target;
            this.rx = area.width / (frameWidth / (double) residual);
            this.ry = area.height / (frameHeight / (double) residual);
        }

        /** The window is merged above the sensor grid. */
        public boolean sabre() { return outWidth != area.width || outHeight != area.height; }

        /** The remaining crop as one factor (the film's frame share, ScamProcessor's 1.005 rule). */
        public float remaining() { return (float) Math.max(rx, ry); }

        @Override public String toString() {
            return String.format(Locale.ROOT, "zoom x%.3f on %dx%d (period %d): window %s, merged %dx%d (scale %.3f x %.3f), photo %s, "
                            + "remaining crop x%.4f x %.4f", residual, frameWidth, frameHeight, period, area, outWidth, outHeight,
                    outWidth / (double) area.width, outHeight / (double) area.height,
                    target == null ? "the crop's own pixels (no upscale)" : target.x + "x" + target.y, rx, ry);
        }
    }

    /** The limits a window's merge must respect. */
    static final class Limits {
        final int gpuMaxSide;
        /** The merge's route: the fast route merges at the scale itself, the SCAM route on the 2x grid (then resampled). */
        final boolean fastRoute;
        /** Memory available before the merge (bytes), -1 when unknown. */
        final long available;

        Limits(int gpuMaxSide, boolean fastRoute, long available) {
            this.gpuMaxSide = gpuMaxSide;
            this.fastRoute = fastRoute;
            this.available = available;
        }
    }

    /** The plan's outcome: a plan, or why the shot keeps the whole frame. */
    static final class Decision {
        final Plan plan;
        final String why;

        Decision(Plan plan, String why) {
            this.plan = plan;
            this.why = why;
        }
    }

    /**
     * The plan of a {@code w} x {@code h} frame's crop shot (residual {@code residual}, colour block {@code block}, 0 unknown):
     * {@code upscale} = «Увеличивать кадр с кропом», {@code target} the uncropped photo's size. A window whose grid exceeds the
     * GPU's side, one RGB buffer, {@link #MAX_GRID_PIXELS} or the memory available keeps the whole frame (today's path).
     */
    static Decision plan(int w, int h, float residual, int block, boolean upscale, Point target, Limits limits) {
        if (!(residual > MIN_RESIDUAL)) return new Decision(null, "no crop");
        final int period = period(block);
        final RawCrop.Area area = window(w, h, residual, period);
        if (area == null) return new Decision(null, String.format(Locale.ROOT, "zoom x%.3f leaves no window of whole %d px periods", residual, period));
        if (block >= 2 && (area.width / block < MIN_SIDE || area.height / block < MIN_SIDE))
            return new Decision(null, "window " + area + " below 64 binned px a side");
        final double cw = w / (double) residual, ch = h / (double) residual;
        final Point out = grid(area.width, area.height, cw, ch, upscale ? target : null);
        final Plan plan = new Plan(area, w, h, residual, period, out.x, out.y, upscale ? target : null);
        if (!plan.sabre()) return new Decision(plan, null);
        final boolean exact2x = out.x == 2 * area.width && out.y == 2 * area.height;
        // the grid the merge holds: the scale itself on the fast route, the 2x grid on the SCAM route (P501)
        final long gridPixels = limits.fastRoute || exact2x ? (long) out.x * out.y : 4L * area.width * area.height;
        String why = null;
        if (2L * Math.max(area.width, area.height) > limits.gpuMaxSide) why = "GPU side " + limits.gpuMaxSide;
        else if ((long) area.width * area.height > Integer.MAX_VALUE / 24L) why = "one FP16 RGB buffer";
        else if (gridPixels > MAX_GRID_PIXELS) why = String.format(Locale.ROOT, "%.1f MP grid above %.0f MP", gridPixels / 1e6, MAX_GRID_PIXELS / 1e6);
        else if (limits.available > 0 && 32L * out.x * out.y > limits.available)
            why = "memory available " + (limits.available >> 20) + " MB";
        if (why != null) return new Decision(null, String.format(Locale.ROOT, "window %s on the %dx%d grid does not fit (%s)",
                area, out.x, out.y, why));
        return new Decision(plan, null);
    }

    /**
     * The crop still to make of the merged window's image {@code imgW} x {@code imgH} (the pipeline's bitmap: sensor axes, or
     * turned by 90 degrees - told by its aspect): centred, per axis 1 / rx, 1 / ry of it; within {@link #SNAP_PX} of the photo's
     * size the photo's size itself (no resample left). Null when nothing is cut.
     */
    public static Rect cropRect(int imgW, int imgH, Plan plan) {
        if (plan == null || imgW <= 0 || imgH <= 0) return null;
        final boolean turned = turned(imgW, imgH, plan.outWidth, plan.outHeight);
        final double fx = turned ? plan.ry : plan.rx, fy = turned ? plan.rx : plan.ry;
        int w = Math.max(2, (int) Math.round(imgW / fx) & ~1), h = Math.max(2, (int) Math.round(imgH / fy) & ~1);
        if (plan.target != null) {
            final int tx = turned ? plan.target.y : plan.target.x, ty = turned ? plan.target.x : plan.target.y;
            if (Math.abs(w - tx) <= SNAP_PX && Math.abs(h - ty) <= SNAP_PX && tx <= imgW && ty <= imgH) { w = tx; h = ty; }
        }
        w = Math.min(w, imgW);
        h = Math.min(h, imgH);
        if (w == imgW && h == imgH) return null;
        final int x = (imgW - w) / 2, y = (imgH - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    /** The photo's target size in the bitmap's orientation, null without one. */
    public static Point target(int imgW, int imgH, Plan plan) {
        if (plan == null || plan.target == null) return null;
        return turned(imgW, imgH, plan.outWidth, plan.outHeight) ? new Point(plan.target.y, plan.target.x) : new Point(plan.target);
    }

    /** Whether a bitmap of {@code imgW} x {@code imgH} is the {@code ow} x {@code oh} merge turned by 90 degrees (closer aspect). */
    static boolean turned(int imgW, int imgH, int ow, int oh) {
        final double a = Math.log(imgW / (double) imgH), straight = Math.log(ow / (double) oh);
        return Math.abs(a + straight) + 1e-9 < Math.abs(a - straight);
    }

    /**
     * ScamProcessor's step before the merge: the plan of the shot's crop (residual {@code residual}) and, with one, every frame of
     * {@code frames} (the first frame's size) cut to its window and {@code p} taken there. Null: the whole frames, as before
     * (logged with the reason when the shot is a crop shot).
     */
    static Plan cut(List<ImageFrame> frames, Parameters p, float residual, boolean photo, int gpuMaxSide) {
        if (!(residual > MIN_RESIDUAL) || frames == null || frames.isEmpty() || frames.get(0) == null) return null;
        final String skip = skipReason(p, photo);
        if (skip != null) {
            Log.i(TAG, "Sabre crop (P576): zoom x" + residual + ", the whole frame merged and cropped as before (" + skip + ")");
            return null;
        }
        final ImageFrame first = frames.get(0);
        final int w = first.width, h = first.height;
        final int block = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.knownBlock(p);
        final boolean upscale = com.particlesdevs.photoncamera.settings.PreferenceKeys.cropUpscale();
        final String mode = com.particlesdevs.photoncamera.settings.PreferenceKeys.hybridOutputMode();
        final Point target = uncroppedPhoto(w, h, mode,
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.sabre2xFitsFrame(w, h, gpuMaxSide),
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.sabreSize(w, h, mode),
                com.particlesdevs.photoncamera.settings.PreferenceKeys.hybridFinalSize(w, h));
        final Limits limits = new Limits(gpuMaxSide, com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.fastRoute(),
                (long) w * h > com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.CLASSIC_INPUT_PIXELS
                        ? com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.memoryAvailableNow() : -1);
        final Decision d = plan(w, h, residual, block, upscale, target, limits);
        if (d.plan == null) {
            Log.i(TAG, "Sabre crop (P576): zoom x" + residual + " on " + w + "x" + h + ": " + d.why
                    + " - the whole frame merged and cropped as before");
            return null;
        }
        final long started = System.nanoTime();
        try {
            RawCrop.apply(frames, w, h, d.plan.area);
        } catch (IOException | RuntimeException e) {
            // the cut keeps each frame whole until its copy is made: a frame not cut is another size and leaves the burst
            Log.w(TAG, "Sabre crop (P576): the cut failed (" + e.getMessage() + ")" + (cutAny(frames, w, h) ? "" : " - the whole frame as before"));
            if (!cutAny(frames, w, h)) return null;
        }
        p.applySabreCrop(d.plan);
        Log.i(TAG, "Sabre crop (P576): " + d.plan + (d.plan.sabre() ? ", Sabre super-resolution on the window" : ", sensor grid")
                + "; block " + block + ", " + (limits.fastRoute ? "fast" : "SCAM") + " route; frames cut in "
                + (System.nanoTime() - started) / 1_000_000 + " ms");
        return d.plan;
    }

    private static boolean cutAny(List<ImageFrame> frames, int w, int h) {
        for (ImageFrame f : frames) if (f != null && f.buffer != null && (f.width != w || f.height != h)) return true;
        return false;
    }

    /** Why a crop shot keeps the whole frame before its geometry is looked at; null when the window may be cut. */
    static String skipReason(Parameters p, boolean photo) {
        if (!com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("sabre_crop", true)) return "scam_dev sabre_crop 0";
        if (!photo) return "no photo: the DNG keeps the whole frame";
        if (p.rawBinning > 1) return "the frames were binned " + p.rawBinning + "x";
        // P267: a Quad / Tetra stream without our remosaic is saved as its merged mosaic DNG alone (the whole frame)
        if (!com.particlesdevs.photoncamera.settings.PreferenceKeys.hybridSwitch("mosaic_remosaic", true)
                && com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.knownBlock(p) != 1)
            return "our remosaic off: the mosaic DNG keeps the whole frame";
        return null;
    }
}
