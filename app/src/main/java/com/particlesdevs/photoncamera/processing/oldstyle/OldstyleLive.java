package com.particlesdevs.photoncamera.processing.oldstyle;

/**
 * P643d «ScamMaster»: the viewfinder's preview of the tone (owner, 2026-11-12: «AE должен быть как в автомате, а тонмапинг
 * полностью из 2.7/3.1» - the screen is the 2.7 / 3.1 look, not a brightness curve).
 * <p>
 * The tone is the owner's module, a whole-frame CPU pass: it cannot run on every viewfinder frame. So every 300 ms it draws the
 * photo of the ring's newest RAW frame as a thumbnail ({@link #photoRgb}: the photo's plan and settings, the camera's colour
 * matrix and lens shading - the photo of the frame the viewfinder shows, P643: the photo is taken at that exposure), and the screen
 * frame - the ISP's, at full resolution - is carried to that picture by a bilateral grid: per cell of a {@link #GX} x {@link #GY}
 * grid over the frame and per {@link #GL} bins of the pixel's luma, an affine 3 x 4 colour transform from the ISP frame to the
 * module's picture ({@link #fit}: least squares on the ISP frame's thumbnail and the module's picture of the same view, ridge-pulled
 * to the luma bin's fit, that to the global fit - a cell with few or no samples is its bin's transform; then a [1 2 1] blur over
 * the cells). The GPU applies it trilinearly: the cell bilinearly by the pixel's place, the two bins round the pixel's luma
 * (utils/import_oldstyle_tone_live). So the module's local tone and colour are on the screen - smooth in space and in luma, no
 * hard edge - and the ISP frame's detail with them.
 * <p>
 * The pairing: the ISP frame's thumbnail is LiveSceneMeter's tile in the external texture's own coordinates (the ones the
 * viewfinder's shader samples at); the RAW frame's thumbnail is the sensor's part the stream shows. Their orientation (the stream's
 * buffer may be flipped against the RAW) is found once a fit by the correlation of the two lumas over the four flips
 * ({@link #orientation}); no orientation that agrees - no new fit.
 * <p>
 * Steady: the grid moves to each new fit with {@link #TAU_NS} (the settings' «Плавность видоискателя»). When the AE moves the
 * frame between two fits, the screen does what the photo will do: with HDR the tone's gains are the estimator's TETs over the
 * frame's (fusion_gains: the photo's brightness does not follow the frame's exposure), so the frame is drawn at the level the fit
 * saw ({@link #level}, linear light, at once on a jump); without HDR the photo is the frame at the AE's exposure, so the frame is
 * drawn as it is. The next fits then bring the screen to the module's picture of the new frame. Nothing is drawn before a fit, nor
 * with the module's picture or the screen's tile stale: the frame as «Фото» shows it then.
 * <p>
 * Thread-safe; times are System.nanoTime of the callers (the camera thread's photo, the GL thread's tile and draw).
 */
public final class OldstyleLive {
    private static final OldstyleLive SHARED = new OldstyleLive();

    public static OldstyleLive shared() {
        return SHARED;
    }

    /** The grid: cells across and down the frame, luma bins (nodes at luma 0, 1 / (GL - 1) .. 1). */
    public static final int GX = 16, GY = 12, GL = 8;
    /** The grid's floats: 12 a node (the transform's three rows of r, g, b, offset), node order (row, bin, gy, gx). */
    public static final int FLOATS = GX * GY * GL * 12;
    /** The grid follows a new fit with this time constant. */
    public static final double TAU_NS = 0.5e9;
    /** The screen frame's level follows its tiles with this time constant, and at once past {@link #LEVEL_SNAP}. */
    public static final double LEVEL_TAU_NS = 0.1e9, LEVEL_SNAP = 1.1;
    /** The level is the tile's linear mean; under this it is this (a black frame has no level). */
    static final float MIN_LEVEL = 1e-4f;
    /** The module's picture or the screen's tile older than this is not there. */
    public static final long STALE_NS = 1_500_000_000L;
    /** The ridge weights (in samples) towards the bin's fit, the bin's towards the global, the global's towards the identity. */
    static final float RIDGE_NODE = 2f, RIDGE_BIN = 8f, RIDGE_GLOBAL = 4f;
    /** A node's transform departs from its bin's by at most this, coefficient by coefficient. */
    static final float MAX_DEPARTURE = 1.5f;
    /**
     * The white anchor: in every cell, {@link #ANCHOR_WEIGHT} samples' weight on the screen's white (1, 1, 1) mapped to the neutral
     * of the picture's {@link #ANCHOR_QUANTILE} luma - the tile is a mean of the frame's pixels, so its brightest is under the
     * frame's; without the anchor the top bin's transform is extrapolated to them (a white the module would not make).
     */
    static final float ANCHOR_WEIGHT = 2f, ANCHOR_QUANTILE = 0.998f;
    /** The orientation is taken when the two lumas' correlation reaches this. */
    static final float MIN_CORRELATION = 0.5f;
    static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};

    /**
     * The state the renderer draws with: the grid (null: the renderer has this generation), its generation, and the level to draw
     * the frame at (linear light, applied before the grid; 1: as it is).
     */
    public static final class Grid {
        public final float[] coefficients;
        public final int generation;
        public final float level;

        Grid(float[] coefficients, int generation, float level) {
            this.coefficients = coefficients;
            this.generation = generation;
            this.level = level;
        }
    }

    /** The module's picture of a frame ({@link #photoRgb}): its display RGB and whether its tone is the HDR one. */
    public static final class Picture {
        public final float[] rgb;
        public final boolean hdr;

        public Picture(float[] rgb, boolean hdr) {
            this.rgb = rgb;
            this.hdr = hdr;
        }
    }

    private boolean on;
    private double tauNs = TAU_NS;
    private float match = 1f, ev = 0f;
    /** The latest tile of the screen frame (display RGB, tw x th, row 0 at the texture's t = 0) and its level (ln, linear). */
    private float[] tile;
    private int tw, th;
    private long tileAt;
    private double logLevel = Double.NaN;
    /** The fit: its target, what is drawn now, the orientation, the level its tile had, whether its picture is HDR. */
    private float[] target, shown;
    private double fitLogLevel = Double.NaN, shownLogLevel = Double.NaN;
    private boolean fitHdr;
    private long fitAt, shownAt, photoAt;
    private int generation, orientation = -1;

    /** Outside the mode, the camera closed, or «Тон в видоискателе» off: the frame as it is until a new fit. */
    public synchronized void off() {
        on = false;
        tile = null;
        target = shown = null;
        logLevel = fitLogLevel = shownLogLevel = Double.NaN;
        orientation = -1;
        generation++;
    }

    /** The preview wants the screen frame's tile measured. */
    public synchronized boolean wantsScreen() {
        return on;
    }

    /**
     * The screen frame's tile (display RGB 0..1 interleaved, {@code w} x {@code h}, row 0 at the external texture's t = 0, x along
     * s; with the frame's series gain as the shader applies it), measured now.
     */
    public synchronized void screen(long nowNs, float[] rgb, int w, int h) {
        if (!on || rgb == null || w < 4 || h < 4 || rgb.length < w * h * 3) return;
        tile = rgb.clone();
        tw = w;
        th = h;
        final double l = Math.log(Math.max(MIN_LEVEL, linearMean(rgb, w * h)));
        if (Double.isNaN(logLevel) || nowNs - tileAt > STALE_NS || Math.abs(l - logLevel) > Math.log(LEVEL_SNAP)) logLevel = l;
        else logLevel += (1.0 - Math.exp(-Math.max(0L, nowNs - tileAt) / LEVEL_TAU_NS)) * (l - logLevel);
        tileAt = nowNs;
    }

    /**
     * The module's picture of the viewfinder's frame ({@link #photoRgb}: display RGB 0..1, {@code w} x {@code h} over the sensor's
     * part the stream shows), with the settings' rows of «Видоискатель» ({@code p}: off - {@link #off}; its brightness, its share
     * of the match, its smoothing): the grid fitted on it and the latest tile.
     */
    public synchronized void photo(long nowNs, Picture picture, int w, int h, OldstyleAe.Params p) {
        final float[] rgb = picture == null ? null : picture.rgb;
        if (p == null || !p.vfTone || rgb == null || w < 8 || h < 8 || rgb.length < w * h * 3) {
            off();
            return;
        }
        on = true;
        tauNs = Math.max(1e6, Math.min(10e9, p.vfSmooth * 1e9));
        match = Math.max(0f, Math.min(1f, p.vfMatch));
        ev = Float.isFinite(p.vfEv) ? p.vfEv : 0f;
        photoAt = nowNs;
        if (tile == null || nowNs - tileAt > STALE_NS) return;
        final int o = orientation(tile, tw, th, rgb, w, h, orientation);
        if (o < 0) return;
        orientation = o;
        final int n = tw * th;
        final float[] x = new float[n * 3], y = new float[n * 3], u = new float[n], v = new float[n];
        final float lift = (float) Math.pow(2.0, ev / 2.2);
        for (int j = 0; j < th; j++) {
            for (int i = 0; i < tw; i++) {
                final int k = j * tw + i;
                u[k] = (i + 0.5f) / tw;
                v[k] = (j + 0.5f) / th;
                final float[] m = sample(rgb, w, h, (o & 1) != 0 ? 1f - u[k] : u[k], (o & 2) != 0 ? 1f - v[k] : v[k]);
                for (int c = 0; c < 3; c++) {
                    x[k * 3 + c] = tile[k * 3 + c];
                    // the share of the match and the screen's own brightness, on the target
                    y[k * 3 + c] = Math.min(1f, (tile[k * 3 + c] + match * (m[c] - tile[k * 3 + c])) * lift);
                }
            }
        }
        advance(nowNs, true);   // the grid's way to the old fit up to now: the time before the new one does not count for it
        target = fit(x, y, u, v, n);
        fitLogLevel = logLevel;
        fitHdr = picture.hdr;
        fitAt = nowNs;
        if (shown == null) {
            shown = target.clone();
            shownLogLevel = fitLogLevel;
            shownAt = nowNs;
            generation++;
        }
    }

    /** {@link #grid(long, int)} with the coefficients always copied. */
    public Grid grid(long nowNs) {
        return grid(nowNs, Integer.MIN_VALUE);
    }

    /**
     * The grid to draw with now, or null - off, no fit yet, or the module's picture or the screen's tile stale: the frame as it
     * is. The grid moves towards the latest fit by its time constant (a new generation when it moved); its coefficients are a copy,
     * or null when the caller has {@code knownGeneration} already (the renderer uploads a grid once).
     */
    public synchronized Grid grid(long nowNs, int knownGeneration) {
        if (!on || shown == null || nowNs - photoAt > STALE_NS || nowNs - tileAt > STALE_NS) return null;
        advance(nowNs, false);
        final float level = !fitHdr || Double.isNaN(shownLogLevel) || Double.isNaN(logLevel) ? 1f : (float) Math.exp(shownLogLevel - logLevel);
        return new Grid(generation == knownGeneration ? null : shown.clone(), generation, level);
    }

    /** The drawn grid towards the latest fit up to {@code nowNs} (at most every 30 ms unless {@code always}). */
    private void advance(long nowNs, boolean always) {
        if (shown == null || target == null) return;
        final long dt = Math.max(0L, nowNs - shownAt);
        if (dt < (always ? 1L : 30_000_000L)) return;
        final float a = (float) (1.0 - Math.exp(-dt / tauNs));
        // the level the drawn grid expects moves with it (the old fit's level, then the new one's)
        if (!Double.isNaN(fitLogLevel))
            shownLogLevel = Double.isNaN(shownLogLevel) ? fitLogLevel : shownLogLevel + a * (fitLogLevel - shownLogLevel);
        float moved = 0f;
        for (int i = 0; i < FLOATS; i++) {
            final float d = a * (target[i] - shown[i]);
            shown[i] += d;
            moved = Math.max(moved, Math.abs(d));
        }
        shownAt = nowNs;
        if (moved > 1e-4f) generation++;
    }

    /** The linear mean luma of display RGB (the power 2.2 of each channel). */
    static float linearMean(float[] rgb, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double l = 0;
            for (int c = 0; c < 3; c++) l += LUMA[c] * Math.pow(Math.max(0f, Math.min(1f, rgb[i * 3 + c])), 2.2);
            sum += l;
        }
        return n == 0 ? 0f : (float) (sum / n);
    }

    /** {@code rgb} (w x h x 3) bilinearly at {@code (a, b)} in [0, 1] of its width and height. */
    static float[] sample(float[] rgb, int w, int h, float a, float b) {
        final float fx = Math.max(0f, Math.min(w - 1f, a * w - 0.5f)), fy = Math.max(0f, Math.min(h - 1f, b * h - 0.5f));
        final int x0 = Math.min(w - 2, (int) fx), y0 = Math.min(h - 2, (int) fy);
        final float dx = fx - x0, dy = fy - y0;
        final float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            final float t = rgb[(y0 * w + x0) * 3 + c] * (1 - dx) + rgb[(y0 * w + x0 + 1) * 3 + c] * dx;
            final float d = rgb[((y0 + 1) * w + x0) * 3 + c] * (1 - dx) + rgb[((y0 + 1) * w + x0 + 1) * 3 + c] * dx;
            out[c] = t * (1 - dy) + d * dy;
        }
        return out;
    }

    /**
     * Which of the four flips (bit 0: across, bit 1: down) puts the module's picture on the tile: the one whose lumas correlate
     * best (log, over the tile's samples) when it reaches {@link #MIN_CORRELATION}, else -1; {@code previous} kept unless
     * another is better by 0.05.
     */
    static int orientation(float[] tile, int tw, int th, float[] rgb, int w, int h, int previous) {
        final int n = tw * th;
        final double[] a = new double[n];
        for (int k = 0; k < n; k++) a[k] = Math.log(0.01 + luma(tile, k));
        int best = -1;
        double bestR = -2;
        final double[] r = new double[4];
        for (int o = 0; o < 4; o++) {
            final double[] b = new double[n];
            for (int j = 0; j < th; j++)
                for (int i = 0; i < tw; i++) {
                    final float uu = (i + 0.5f) / tw, vv = (j + 0.5f) / th;
                    final float[] m = sample(rgb, w, h, (o & 1) != 0 ? 1f - uu : uu, (o & 2) != 0 ? 1f - vv : vv);
                    b[j * tw + i] = Math.log(0.01 + LUMA[0] * m[0] + LUMA[1] * m[1] + LUMA[2] * m[2]);
                }
            r[o] = correlation(a, b);
            if (r[o] > bestR) {
                bestR = r[o];
                best = o;
            }
        }
        if (previous >= 0 && r[previous] >= MIN_CORRELATION && r[previous] > bestR - 0.05) return previous;
        return bestR >= MIN_CORRELATION ? best : -1;
    }

    private static double luma(float[] rgb, int k) {
        return LUMA[0] * rgb[k * 3] + LUMA[1] * rgb[k * 3 + 1] + LUMA[2] * rgb[k * 3 + 2];
    }

    private static double correlation(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) {
            ma += a[i];
            mb += b[i];
        }
        ma /= a.length;
        mb /= a.length;
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < a.length; i++) {
            sab += (a[i] - ma) * (b[i] - mb);
            saa += (a[i] - ma) * (a[i] - ma);
            sbb += (b[i] - mb) * (b[i] - mb);
        }
        return saa <= 1e-12 || sbb <= 1e-12 ? 0 : sab / Math.sqrt(saa * sbb);
    }

    /**
     * The bilateral grid of {@code n} pairs: the screen's display RGB {@code x} to the module's {@code y} (interleaved), at
     * {@code u}, {@code v} in [0, 1] of the frame. Each node's affine transform is the ridge least squares of the samples by
     * their trilinear weights (the node's cell bilinearly, its luma bin by the sample's luma), pulled with {@link #RIDGE_NODE}
     * samples' weight to its bin's transform; each bin's is the fit of all samples by their luma weight, pulled to the global fit;
     * the global one to the identity. A node departs from its bin's by at most {@link #MAX_DEPARTURE} a coefficient, and the
     * nodes are blurred [1 2 1] across the cells. With the white anchor ({@link #ANCHOR_WEIGHT}). {@link #FLOATS} floats, node
     * order (row, bin, gy, gx), 4 a row.
     */
    public static float[] fit(float[] xs, float[] ys, float[] us, float[] vs, int samples) {
        // the white anchor: one more sample in each cell (weight ANCHOR_WEIGHT)
        final float[] lumas = new float[samples];
        for (int k = 0; k < samples; k++) lumas[k] = LUMA[0] * ys[k * 3] + LUMA[1] * ys[k * 3 + 1] + LUMA[2] * ys[k * 3 + 2];
        java.util.Arrays.sort(lumas);
        final float top = samples == 0 ? 1f : Math.min(1f, lumas[Math.min(samples - 1, (int) (ANCHOR_QUANTILE * (samples - 1)))]);
        final int n = samples + GX * GY;
        final float[] x = java.util.Arrays.copyOf(xs, n * 3), y = java.util.Arrays.copyOf(ys, n * 3), u = java.util.Arrays.copyOf(us, n),
                v = java.util.Arrays.copyOf(vs, n), weight = new float[n];
        java.util.Arrays.fill(weight, 0, samples, 1f);
        for (int cy = 0, k = samples; cy < GY; cy++)
            for (int cx = 0; cx < GX; cx++, k++) {
                for (int c = 0; c < 3; c++) {
                    x[k * 3 + c] = 1f;
                    y[k * 3 + c] = top;
                }
                u[k] = (cx + 0.5f) / GX;
                v[k] = (cy + 0.5f) / GY;
                weight[k] = ANCHOR_WEIGHT;
            }
        final double[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
        // the global fit
        final double[][] gm = new double[4][4], gb = new double[4][3];
        for (int k = 0; k < n; k++) accumulate(gm, gb, x, y, k, weight[k]);
        final double[] global = solve(gm, gb, RIDGE_GLOBAL, identity);
        // the bins
        final double[][][] bm = new double[GL][4][4], bb = new double[GL][4][3];
        final double[][][] nm = new double[GL * GY * GX][4][4], nb = new double[GL * GY * GX][4][3];
        for (int k = 0; k < n; k++) {
            final double l = Math.max(0, Math.min(1, LUMA[0] * x[k * 3] + LUMA[1] * x[k * 3 + 1] + LUMA[2] * x[k * 3 + 2])) * (GL - 1);
            final int l0 = Math.min(GL - 2, (int) l);
            final double fl = l - l0;
            final double gx = Math.max(0, Math.min(GX - 1, u[k] * GX - 0.5)), gy = Math.max(0, Math.min(GY - 1, v[k] * GY - 0.5));
            final int x0 = Math.min(GX - 2, (int) gx), y0 = Math.min(GY - 2, (int) gy);
            final double fx = gx - x0, fy = gy - y0;
            for (int dl = 0; dl < 2; dl++) {
                final double wl = (dl == 0 ? 1 - fl : fl) * weight[k];
                if (wl <= 0) continue;
                accumulate(bm[l0 + dl], bb[l0 + dl], x, y, k, wl);
                for (int dy = 0; dy < 2; dy++)
                    for (int dx = 0; dx < 2; dx++) {
                        final double w = wl * (dy == 0 ? 1 - fy : fy) * (dx == 0 ? 1 - fx : fx);
                        if (w > 0) {
                            final int node = ((l0 + dl) * GY + y0 + dy) * GX + x0 + dx;
                            accumulate(nm[node], nb[node], x, y, k, w);
                        }
                    }
            }
        }
        final double[][] bins = new double[GL][];
        for (int l = 0; l < GL; l++) bins[l] = solve(bm[l], bb[l], RIDGE_BIN, global);
        final double[][] nodes = new double[GL * GY * GX][];
        for (int l = 0; l < GL; l++)
            for (int node = l * GY * GX; node < (l + 1) * GY * GX; node++) {
                final double[] a = solve(nm[node], nb[node], RIDGE_NODE, bins[l]);
                for (int i = 0; i < 12; i++) a[i] = bins[l][i] + Math.max(-MAX_DEPARTURE, Math.min(MAX_DEPARTURE, a[i] - bins[l][i]));
                nodes[node] = a;
            }
        // the [1 2 1] blur across the cells, edges held
        final float[] out = new float[FLOATS];
        for (int l = 0; l < GL; l++)
            for (int gy = 0; gy < GY; gy++)
                for (int gx = 0; gx < GX; gx++) {
                    final double[] s = new double[12];
                    double ws = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            final int yy = Math.max(0, Math.min(GY - 1, gy + dy)), xx = Math.max(0, Math.min(GX - 1, gx + dx));
                            final double w = (dy == 0 ? 2 : 1) * (dx == 0 ? 2 : 1);
                            final double[] a = nodes[(l * GY + yy) * GX + xx];
                            for (int i = 0; i < 12; i++) s[i] += w * a[i];
                            ws += w;
                        }
                    for (int row = 0; row < 3; row++)
                        for (int c = 0; c < 4; c++) out[index(row, l, gy, gx) + c] = (float) (s[row * 4 + c] / ws);
                }
        return out;
    }

    /** The first float of a node's row in the grid (and in the renderer's texture: x gx, y (row x GL + bin) x GY + gy). */
    public static int index(int row, int bin, int gy, int gx) {
        return ((((row * GL) + bin) * GY + gy) * GX + gx) * 4;
    }

    private static void accumulate(double[][] m, double[][] b, float[] x, float[] y, int k, double w) {
        final double[] f = {x[k * 3], x[k * 3 + 1], x[k * 3 + 2], 1};
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) m[i][j] += w * f[i] * f[j];
            for (int c = 0; c < 3; c++) b[i][c] += w * f[i] * y[k * 3 + c];
        }
    }

    /** (M + ridge I) A = B + ridge prior for A (4 x 3, returned as 3 rows of 4: row c = output channel c's coefficients). */
    private static double[] solve(double[][] m, double[][] b, double ridge, double[] prior) {
        final double[][] a = new double[4][7];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) a[i][j] = m[i][j] + (i == j ? ridge : 0);
            for (int c = 0; c < 3; c++) a[i][4 + c] = b[i][c] + ridge * prior[c * 4 + i];
        }
        for (int col = 0; col < 4; col++) {
            int p = col;
            for (int r = col + 1; r < 4; r++) if (Math.abs(a[r][col]) > Math.abs(a[p][col])) p = r;
            final double[] t = a[col];
            a[col] = a[p];
            a[p] = t;
            final double d = a[col][col];
            if (Math.abs(d) < 1e-12) return prior.clone();
            for (int r = 0; r < 4; r++) {
                if (r == col) continue;
                final double f = a[r][col] / d;
                for (int j = col; j < 7; j++) a[r][j] -= f * a[col][j];
            }
        }
        final double[] out = new double[12];
        for (int c = 0; c < 3; c++)
            for (int i = 0; i < 4; i++) out[c * 4 + i] = a[i][4 + c] / a[i][i];
        for (double v : out) if (!Double.isFinite(v)) return prior.clone();
        return out;
    }

    /** What the shader makes of a pixel ({@code c}: display RGB, at {@code u}, {@code v}) with a grid and a level (host tests). */
    public static float[] apply(float[] grid, float level, float[] c, float u, float v) {
        final float[] in = new float[3];
        for (int i = 0; i < 3; i++) {
            final float x = Math.max(0f, Math.min(1f, c[i]));
            in[i] = level == 1f ? x : (float) Math.pow(Math.min(1.0, Math.pow(x, 2.2) * level), 1 / 2.2);
        }
        final float l = Math.max(0f, Math.min(1f, LUMA[0] * in[0] + LUMA[1] * in[1] + LUMA[2] * in[2])) * (GL - 1);
        final int b0 = Math.min(GL - 2, (int) l);
        final float fb = l - b0;
        final float gx = Math.max(0f, Math.min(GX - 1f, u * GX - 0.5f)), gy = Math.max(0f, Math.min(GY - 1f, v * GY - 0.5f));
        final int x0 = Math.min(GX - 2, (int) gx), y0 = Math.min(GY - 2, (int) gy);
        final float fx = gx - x0, fy = gy - y0;
        final float[] out = new float[3];
        for (int row = 0; row < 3; row++) {
            float s = 0;
            for (int db = 0; db < 2; db++)
                for (int dy = 0; dy < 2; dy++)
                    for (int dx = 0; dx < 2; dx++) {
                        final float w = (db == 0 ? 1 - fb : fb) * (dy == 0 ? 1 - fy : fy) * (dx == 0 ? 1 - fx : fx);
                        final int at = index(row, b0 + db, y0 + dy, x0 + dx);
                        s += w * (grid[at] * in[0] + grid[at + 1] * in[1] + grid[at + 2] * in[2] + grid[at + 3]);
                    }
            out[row] = Math.max(0f, Math.min(1f, s));
        }
        return out;
    }

    /**
     * The tone-port module's own picture (display RGB 0..1, w x h x 3) of a frame at the viewfinder's exposure ({@code rgb}:
     * interleaved linear RGB of w x h cells, white-balanced, before the colour matrix - the ring's newest RAW), made as the photo
     * is: {@code ccm} the camera's matrix (row-major, white-balanced RGB to linear sRGB; null: none), the tone's plan metered on the
     * frame after it ({@link OldstyleAe#plan}, the merge's plan), V1 the frame with the matrix in the module, V2 the frame after it,
     * the module with {@code p}'s stages. Null without the library or the frame.
     */
    public static Picture photoRgb(float[] rgb, int w, int h, float[] ccm, OldstyleAe.Params p) {
        if (rgb == null || w < 8 || h < 8 || rgb.length < w * h * 3 || !OldstyleNative.load()) return null;
        try {
            final int n = w * h;
            final float[] m = ccm != null && ccm.length == 9 ? OldstyleNative.ccmStrength(ccm, p.ccmStrength) : new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            final float[] pre = new float[n * 3], post = new float[n * 3];
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < 3; c++) pre[i * 3 + c] = Float.isFinite(rgb[i * 3 + c]) ? Math.max(0f, Math.min(1f, rgb[i * 3 + c])) : 0f;
                for (int c = 0; c < 3; c++)
                    post[i * 3 + c] = Math.max(0f, m[c * 3] * pre[i * 3] + m[c * 3 + 1] * pre[i * 3 + 1] + m[c * 3 + 2] * pre[i * 3 + 2]);
            }
            final OldstyleAe.Plan plan = OldstyleAe.plan(post, w, h, p, 1f);
            final float[] frame = p.v31 ? post : pre;
            final java.nio.ByteBuffer planar = OldstyleNative.planar(w, h);
            final java.nio.ShortBuffer s = planar.asShortBuffer();
            for (int i = 0; i < n; i++)
                for (int ch = 0; ch < 3; ch++) s.put(ch * n + i, OldstyleAe.sample(frame[i * 3 + ch], plan.inputScale));
            final java.nio.ByteBuffer out = java.nio.ByteBuffer.allocateDirect(n * 3);
            OldstyleNative.render(p.v31 ? OldstyleNative.V2 : OldstyleNative.V1, planar, w, h, p.v31 ? null : ccm == null ? null : m,
                    plan.gainShort, plan.gainLong, plan.hdr, false, p.saturation, p.post(), null, 0, 0, null, out);
            final float[] o = new float[n * 3];
            for (int i = 0; i < n * 3; i++) o[i] = (out.get(i) & 0xFF) / 255f;
            return new Picture(o, plan.hdr);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
