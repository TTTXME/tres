package com.scamera.app.ui.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.scamera.app.processing.color.FilmLooks;
import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.color.LensFilters;
import com.scamera.app.ui.settings.custompreferences.FilmLutPreference;
import com.scamera.app.ui.settings.custompreferences.LensFilterPreference;
import com.scamera.app.ui.settings.custompreferences.LutSamples;
import com.scamera.app.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P509 (owner, 2026-11-05: «для лутов, симуляций плёнки и т.д. сделай небольшие миниатюры, по которым будет понятно, что это внутри
 * этого меню. Под миниатюрами я не имею в виду живые превью»): the LUT tray's cards show a small still picture - the settings' drawn
 * sample (LutSamples, no bundled photo) through the LUT, the film stock's colour or the lens filter, at full strength. Rendered once
 * per item off the main thread and kept in memory; never from the camera. P718 (owner, 2026-11-14: «сгенерируй по аналогии с
 * карточками из лутов или симуляций плёнок»): the «Стиль» presets' cards too (the wizard, the settings' style page) - the sunset
 * sample, bright sky over dark ridges, through a CPU sketch of the preset ({@link #presetPixels}).
 */
public final class LookThumbs {
    private LookThumbs() {}

    /** The picture's size in pixels (the card shows it at 52dp, ~156 px on a 3x screen: soft but quick to draw). */
    static final int SIZE = 112;
    /** The samples: the landscape for colour (FilmLutPreference's 0), the lights at night for the filters' glow (LensFilterPreference's 0). */
    static final int COLOUR_SAMPLE = 0, FILTER_SAMPLE = 0;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 << 20) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lut-thumbs");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Render { Bitmap draw(Context c) throws Exception; }

    static String lutKey(String value) { return "lut|" + value; }
    static String filterKey(String id) { return "filter|" + id; }
    static String filmKey(String id) { return "film|" + id; }
    static String presetKey(int style) { return "preset|" + style; }

    /** P718: the sample for the presets - «Закат», a bright sky and sun over dark ridges and trees (the HDR terms read at 53dp). */
    static final int PRESET_SAMPLE = 1;

    /** P718: a «Стиль» preset's thumbnail (PhotoStyle): the sunset sample through {@link #presetPixels}. */
    public static void preset(ImageView v, int style) {
        bind(v, presetKey(style), c -> rgb(presetPixels(FilmLutPreference.pixels(c, PRESET_SAMPLE, SIZE, SIZE, ""), SIZE, SIZE, style),
                SIZE, SIZE));
    }

    /**
     * P718: a preset's look on display pixels for its card - a cheap sketch of WhyV2Ae.applyStyle, not the photo's tone: the
     * fusion's shadow lift and highlight compression as an exposure over the local base (the log luma box-blurred over a tenth of
     * the side), {@link #LIFT_EV} / {@link #COMPRESS_EV} EV per unit of the preset's factor (1.8 x effS and 2.5 x effH at the
     * strengths the owner's bursts measured, ~0.45 / ~0.3), the macro contrast on the cube root of the luminance round 0.40 (as
     * OKLab L in why/v2_tonemap; the default 1.1's excess by the preset's factor), the vibrance on the chroma (muted / saturated
     * colours, the sky and light-tone terms). Every term {@link #EMPHASIS} x its change from Normal, so the difference reads on a
     * 53dp card.
     */
    static int[] presetPixels(int[] src, int w, int h, int style) {
        final com.scamera.app.processing.opengl.postpipeline.WhyV2Ae.Style s =
                com.scamera.app.processing.opengl.postpipeline.WhyV2Ae.style(style);
        if (style == com.scamera.app.processing.opengl.postpipeline.WhyV2Ae.STYLE_NORMAL) return src;
        final int n = w * h;
        final float[] lin = new float[n * 3], lg = new float[n];
        for (int i = 0; i < n; i++) {
            final int p = src[i];
            float y = 0f;
            for (int k = 0; k < 3; k++) {
                final float v = toLinear(((p >> (16 - 8 * k)) & 0xFF) / 255f);
                lin[i * 3 + k] = v;
                y += LUMA[k] * v;
            }
            lg[i] = (float) (Math.log(Math.max(y, 1e-4f)) / Math.log(2.0));
        }
        final float[] base = box(box(lg, w, h, Math.max(1, w / 10), true), w, h, Math.max(1, h / 10), false);
        final float e = EMPHASIS;
        final float contrast = 1f + e * ((1f + 0.1f * s.macro) / 1.1f - 1f);
        final int[] out = new int[n];
        final float[] d = new float[3];
        for (int i = 0; i < n; i++) {
            final float b = base[i];
            final float dark = ramp(-1.0f, -4.0f, b), bright = ramp(-1.5f, 0f, b);
            float gain = (float) Math.pow(2.0, e * (LIFT_EV * (s.shadow - 1f) * dark - COMPRESS_EV * (s.highlight - 1f) * bright));
            float yl = 0f;
            for (int k = 0; k < 3; k++) yl += LUMA[k] * lin[i * 3 + k] * gain;
            if (yl > 1e-6f) {
                final float l = (float) Math.cbrt(yl), lc = Math.max(0.0001f, 0.4f + (l - 0.4f) * contrast);
                gain *= (lc / l) * (lc / l) * (lc / l);
            }
            float y = 0f;
            for (int k = 0; k < 3; k++) {
                d[k] = toDisplay(lin[i * 3 + k] * gain);
                y += LUMA[k] * d[k];
            }
            // the chroma: muted -> saturated colours by its size, the sky (blue) and light-tone terms by their masks
            final float chroma = Math.max(Math.max(d[0], d[1]), d[2]) - Math.min(Math.min(d[0], d[1]), d[2]);
            final float satT = ramp(0.08f, 0.35f, chroma);
            final float sky = d[2] > d[0] ? Math.min(1f, (d[2] - d[0]) / Math.max(chroma, 1e-4f)) : 0f;
            final float light = ramp(0.6f, 0.8f, y) * (1f - ramp(0.88f, 0.98f, y));
            final float boost = s.vibrance + (s.vibSaturated - s.vibrance) * satT + (s.vibTerms - 1f) * 0.25f * (sky + light);
            final float sat = Math.max(0f, 1f + e * boost);
            int px = 0xFF000000;
            for (int k = 0; k < 3; k++) {
                final float v = y + (d[k] - y) * sat;
                px |= Math.round(Math.max(0f, Math.min(1f, v)) * 255f) << (16 - 8 * k);
            }
            out[i] = px;
        }
        return out;
    }

    /** P718: how much more than the photo's change a card shows (the photo's own +-0.24 EV and +-8-15 % chroma vanish at 53dp). */
    static final float EMPHASIS = 2f;
    /** P718: EV per unit of the shadow / highlight factor in {@link #presetPixels} (1.8 x 0.45 and 2.5 x 0.3). */
    static final float LIFT_EV = 0.81f, COMPRESS_EV = 0.75f;
    private static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};

    private static float ramp(float from, float to, float v) {
        final float t = Math.max(0f, Math.min(1f, (v - from) / (to - from)));
        return t * t * (3f - 2f * t);
    }

    private static float toLinear(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
    }

    private static float toDisplay(float v) {
        v = Math.max(0f, Math.min(1f, v));
        return v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1f / 2.4f) - 0.055f;
    }

    /** A box mean along x ({@code horizontal}) or y with radius {@code r}, the edges clamped. */
    private static float[] box(float[] a, int w, int h, int r, boolean horizontal) {
        final float[] out = new float[a.length];
        final int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            double sum = 0;
            for (int j = -r; j <= r; j++) sum += a[at(line, Math.max(0, Math.min(len - 1, j)), w, horizontal)];
            for (int i = 0; i < len; i++) {
                out[at(line, i, w, horizontal)] = (float) (sum / (2 * r + 1));
                sum += a[at(line, Math.min(len - 1, i + r + 1), w, horizontal)] - a[at(line, Math.max(0, i - r), w, horizontal)];
            }
        }
        return out;
    }

    private static int at(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /** A LUT's thumbnail ("" = the sample itself): a built-in look or a .cube of the folder. */
    static void lut(ImageView v, String value) {
        bind(v, lutKey(value), c -> rgb(FilmLutPreference.pixels(c, COLOUR_SAMPLE, SIZE, SIZE, value), SIZE, SIZE));
    }

    /** A lens filter's thumbnail ("" = none): the lights through the filter at its defaults. */
    static void filter(ImageView v, String id) {
        bind(v, filterKey(id), c -> id.isEmpty()
                ? LensFilterPreference.render(FILTER_SAMPLE, SIZE, SIZE, "", 0f, 0f)
                : LensFilterPreference.render(FILTER_SAMPLE, SIZE, SIZE, id, 1f, LensFilters.defaultParam(id)));
    }

    /** A film stock's thumbnail ("" = none): the sample through the stock's colour (its default print, no grain or halation). */
    static void film(ImageView v, String id) {
        bind(v, filmKey(id), c -> {
            final int[] base = FilmLutPreference.pixels(c, COLOUR_SAMPLE, SIZE, SIZE, "");
            final FilmSim.Stock stock = FilmSim.stock(id);
            if (stock == null) return rgb(base, SIZE, SIZE);
            final FilmSim.Settings set = new FilmSim.Settings(stock, FilmSim.print("", stock), FilmSim.format(FilmSim.DEFAULT_FORMAT),
                    0f, 0f, 0f, 0f, 0f, 0f, 1f);
            final FilmLut lut = FilmSim.lut(set, FilmLooks.SIZE);
            return rgb(lut == null ? base : LutSamples.apply(base, lut), SIZE, SIZE);
        });
    }

    private static Bitmap rgb(int[] px, int w, int h) { return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888); }

    /** {@code v} shows the picture of {@code key}: at once when it is ready, else when it is drawn (if v still wants that key). */
    static void bind(ImageView v, String key, Render render) {
        v.setTag(com.scamera.app.R.id.lut_tray, key);
        final Bitmap ready = CACHE.get(key);
        if (ready != null) { v.setImageBitmap(ready); return; }
        v.setImageDrawable(null);
        final Context c = v.getContext().getApplicationContext();
        WORKER.execute(() -> {
            Bitmap b = CACHE.get(key);
            if (b == null) {
                try {
                    b = render.draw(c);
                } catch (Throwable e) { // a missing .cube or a stock the legacy table cannot render: the card keeps its name only
                    Log.w("LookThumbs", "thumbnail " + key + ": " + e);
                    return;
                }
                if (b == null) return;
                CACHE.put(key, b);
            }
            final Bitmap shown = b;
            MAIN.post(() -> {
                if (key.equals(v.getTag(com.scamera.app.R.id.lut_tray))) {
                    v.setImageBitmap(shown);
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(160).start();
                }
            });
        });
    }

    /** A .cube file changed or was removed: its thumbnail is drawn again. */
    static void forget(String value) {
        CACHE.remove(lutKey(value));
    }
}
