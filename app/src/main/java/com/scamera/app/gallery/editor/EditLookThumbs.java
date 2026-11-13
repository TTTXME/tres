package com.scamera.app.gallery.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.color.FinishedLook;
import com.scamera.app.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P619: the «LUT» tab's cards show the photo itself (the main pass's picture as edited, without the look: EditorView.thumbnail)
 * through each LUT / film stock at full strength - the camera's tray shows its drawn sample the same way (LookThumbs). The film
 * cards take the stock's first-phase table at 17 a side (its default print, no grain), the LUT cards the camera's own table. Drawn
 * once per photo picture off the main thread; a newer picture makes the older ones' work moot.
 */
final class EditLookThumbs {
    private static final String TAG = "EditLookThumbs";
    /** The photo's picture for the cards: its long side (px; a 53dp card is ~160 px on a 3x screen). */
    static final int SIDE_PX = 160;
    /** The film cards' table side (FinishedLook.thumbnailFilm). */
    static final int FILM_SIZE = 17;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "edit-look-thumbs");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> done = new HashMap<>();
    @Nullable private int[] base;
    private int w, h;
    private volatile int version;

    /** A new picture of the photo (ARGB, w x h): every card is drawn again. */
    void setBase(int[] argb, int width, int height) {
        base = argb;
        w = width;
        h = height;
        version++;
        done.clear();
    }

    /** The table key of a LUT value ("lut|" + value) or of a film stock ("film|" + id). */
    static String lutKey(String value) {
        return "lut|" + value;
    }

    static String filmKey(String id) {
        return "film|" + id;
    }

    /** {@code v} shows the photo through the LUT {@code value} ("" the photo itself). */
    void lut(ImageView v, String value) {
        bind(v, lutKey(value), c -> value.isEmpty() ? null : FinishedLook.user(c, value));
    }

    /** {@code v} shows the photo through the film stock {@code id} ("" the photo itself). */
    void film(ImageView v, String id) {
        bind(v, filmKey(id), c -> {
            FilmSim.Stock st = id.isEmpty() ? null : FilmSim.stock(id);
            return st == null ? null : FinishedLook.thumbnailFilm(thumbSettings(st), FILM_SIZE);
        });
    }

    /** A stock as its card shows it: its default print and format, no grain, the film alone (LookThumbs.film's settings). */
    static FilmSim.Settings thumbSettings(FilmSim.Stock st) {
        return new FilmSim.Settings(st, FilmSim.print("", st), FilmSim.format(FilmSim.DEFAULT_FORMAT), 0f, 0f, 0f, 0f, 0f, 0f, 1f);
    }

    interface Table {
        @Nullable FilmLut of(Context c);
    }

    private void bind(ImageView v, String key, Table table) {
        v.setTag(com.scamera.app.R.id.lut_tray, key);
        Bitmap ready = done.get(key);
        if (ready != null) {
            v.setImageBitmap(ready);
            return;
        }
        if (base == null) return;
        final int[] px = base;
        final int bw = w, bh = h, ver = version;
        final Context c = v.getContext().getApplicationContext();
        WORKER.execute(() -> {
            if (ver != version) return;
            Bitmap b;
            try {
                FilmLut l = table.of(c);
                int[] out = l == null ? px : FinishedLook.apply(px, l, 1f);
                b = Bitmap.createBitmap(out, bw, bh, Bitmap.Config.ARGB_8888);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "thumbnail " + key + ": " + e);
                return;
            }
            final Bitmap shown = b;
            main.post(() -> {
                if (ver != version) return;
                done.put(key, shown);
                if (key.equals(v.getTag(com.scamera.app.R.id.lut_tray))) {
                    v.setImageBitmap(shown);
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(160).start();
                }
            });
        });
    }
}
