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
 * per item off the main thread and kept in memory; never from the camera.
 */
final class LookThumbs {
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
