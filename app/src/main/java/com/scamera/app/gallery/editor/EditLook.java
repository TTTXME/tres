package com.scamera.app.gallery.editor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.scamera.app.gallery.editor.EditState.Param;
import com.scamera.app.processing.color.FilmAdvanced;
import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.color.FinishedLook;
import com.scamera.app.util.Log;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * P619 (owner, 2026-11-11: «Добавь возможность в нашем редакторе в галерее применять луты из камеры и симуляции плёнок»): the
 * «LUT» tab's math - the camera's LUT and film simulation on the editor's finished picture, after the main pass (the user's tone
 * and colour, display-encoded sRGB) and before the geometry, the vignette and the editor's own grain:
 * <ol>
 *   <li>colour: the camera's display table (FinishedLook: a LUT alone is the camera's own table; a film is the display route of
 *       the ISP viewfinder - FilmSim.lut, then the reference model's DISPLAY table - with the LUT on its print), looked up exactly
 *       as preview/main_fs.glsl does (editor/look.glsl) and mixed by the film's strength, or the LUT's without a film;</li>
 *   <li>the film's grain with a format: the camera's FilmGrain passes on the looked-up picture (film/noise, film/blur and
 *       film/grain_sf for a stock of the reference model; film/grain for our black-and-white model), sized on the whole photo's
 *       long side as the camera sizes it on the shot, the noise hashed on the photo's pixel position so the export's tiles,
 *       the zoom's regions and the preview see one field.</li>
 * </ol>
 * The film's halation, in-emulsion softness and camera diffusion are left out: they spread the light above the display's white
 * (a lamp's real energy), which a finished picture no longer has.
 */
public final class EditLook {
    private static final String TAG = "EditLook";
    /** The reference grain's LUT side (FilmGrain.GRAIN_LUT). */
    static final int GRAIN_LUT = 17;

    private EditLook() {}

    /** The film simulation of the state (null: none chosen, an unknown id, or its strength 0 - the camera's FilmSim.current rule). */
    @Nullable
    public static FilmSim.Settings film(EditState s) {
        final FilmSim.Stock stock = s.film.isEmpty() ? null : FilmSim.stock(s.film);
        if (stock == null) return null;
        final float strength = s.get(Param.FILM_AMOUNT) / 100f;
        if (strength <= 0f) return null;
        return new FilmSim.Settings(stock, FilmSim.print(s.filmPrint, stock), FilmSim.format(s.filmFormat), s.get(Param.FILM_GRAIN) / 100f,
                0f, 0f, s.get(Param.FILM_EXPOSURE), s.get(Param.FILM_PRINT_EV), s.get(Param.FILM_CONTRAST) / 100f, 0f, 0f, strength, "",
                FilmSim.DIFFUSION_DEFAULT, s.get(Param.FILM_DIR) / 100f, FilmAdvanced.DEFAULT);
    }

    /** The chosen LUT ("" none or at strength 0). */
    static String lut(EditState s) {
        return s.get(Param.LUT_AMOUNT) > 0f ? s.lut : "";
    }

    static float lutStrength(EditState s) {
        return s.get(Param.LUT_AMOUNT) / 100f;
    }

    /** What the colour table depends on (FinishedLook.key); "" nothing to apply. */
    static String tableKey(EditState s) {
        return FinishedLook.key(film(s), lut(s), lutStrength(s));
    }

    /** Whether the state applies a LUT or a film. */
    public static boolean on(EditState s) {
        return !tableKey(s).isEmpty();
    }

    /** The shader's mix of the table. */
    static float strength(EditState s) {
        return FinishedLook.shaderStrength(film(s), lutStrength(s));
    }

    /** Everything the look's output depends on besides the picture: the table, the mix and the grain ("" nothing). */
    static String key(EditState s) {
        final String t = tableKey(s);
        if (t.isEmpty()) return "";
        final FilmSim.Settings f = film(s);
        return t + "|" + String.format(Locale.ROOT, "%.4f", strength(s)) + (f == null ? "" : "|" + f.format.id + "|" + f.grain);
    }

    // ---------------------------------------------------------------- grain

    /** The film's grain on a picture: the camera's FilmGrain numbers for the photo's long side; null for none. */
    static final class Grain {
        /** A stock of the reference model (film/grain_sf with a grain LUT), else our black-and-white model (film/grain). */
        final boolean reference;
        /** The film's pixel size (um) and, reference: the noise's blur (px) and the grain's scale; classic: film/grain's uniforms. */
        final float um, blurPx, scale, sigma, cell, sharedPart;
        final boolean slide, mono;
        final float seed;

        Grain(FilmSim.Settings f, float longSide) {
            reference = f.stock.spectral != null;
            um = f.pixelUm(Math.max(1, Math.round(longSide)));
            blurPx = FilmSim.grainBlurUm(f.format) / um;
            scale = f.grainApplied() / um;
            sigma = FilmSim.classicGrainSigma(f, um);
            cell = FilmSim.classicGrainCell(f, um);
            sharedPart = f.stock.mono() ? 1f : .55f;
            slide = f.stock.reversal();
            mono = f.stock.mono();
            seed = (float) (Math.abs(f.stock.id.hashCode()) % 9973);
        }
    }

    /**
     * The grain of {@code f} on a picture {@code longSide} pixels long (the whole photo's, in the texture's pixels), null without a
     * film, a format or «Зерно» (FilmGrain.from / addsGrain: the classic stocks' film sharpness needs «Мягкость», not offered here).
     */
    @Nullable
    static Grain grain(@Nullable FilmSim.Settings f, float longSide) {
        if (f == null || f.format.longMm <= 0f || f.grain <= 0f || f.grainApplied() <= 0f || longSide < 1f) return null;
        return new Grain(f, longSide);
    }

    /** How far the grain reaches across a tile edge (px): the reference noise's blur (film/blur.glsl: ceil(3 sigma)), else 0. */
    static int reach(@Nullable Grain g) {
        return g == null || !g.reference ? 0 : (int) Math.ceil(3.0 * g.blurPx) + 1;
    }

    /** The export's / the zoom's overlap around a tile: the editor's own reach and the grain's. */
    static int overlap(EditState s, float longSide) {
        return Math.max(EditMath.overlapPx(longSide), reach(grain(film(s), longSide)));
    }

    // ---------------------------------------------------------------- tables

    /** The loaded tables of one {@link #tableKey}: the display table (FinishedLook) and the reference grain's LUT. */
    public static final class Tables {
        final String key;
        @Nullable final FinishedLook.Table colour;
        @Nullable final FilmLut grainLut;

        Tables(String key, @Nullable FinishedLook.Table colour, @Nullable FilmLut grainLut) {
            this.key = key;
            this.colour = colour;
            this.grainLut = grainLut;
        }

        boolean reference() {
            return colour != null && colour.reference;
        }
    }

    /** What the renderer applies (a state's look with its tables), or null: nothing, or its tables not loaded yet. */
    static final class Applied {
        final String key;
        final FilmLut lut;
        final float strength;
        @Nullable final FilmSim.Settings film;
        @Nullable final FilmLut grainLut;

        Applied(String key, FilmLut lut, float strength, @Nullable FilmSim.Settings film, @Nullable FilmLut grainLut) {
            this.key = key;
            this.lut = lut;
            this.strength = strength;
            this.film = film;
            this.grainLut = grainLut;
        }
    }

    /** The look of {@code s} with {@code t} (the tables of its key), null when they are not its tables. */
    @Nullable
    static Applied applied(EditState s, @Nullable Tables t) {
        final String k = tableKey(s);
        if (k.isEmpty() || t == null || !k.equals(t.key) || t.colour == null) return null;
        final FilmSim.Settings f = film(s);
        return new Applied(key(s) + (t.reference() ? "|ref" : "|first"), t.colour.lut, strength(s), f,
                f != null && f.stock.spectral != null ? t.grainLut : null);
    }

    /** Whether the tables need the reference grain's LUT (a reference stock with a format and grain). */
    static boolean wantsGrainLut(@Nullable FilmSim.Settings f) {
        return f != null && f.stock.spectral != null && f.format.longMm > 0f && f.grain > 0f;
    }

    /**
     * The tables of a state off the main thread, two phases as the camera's viewfinder (FinishedLook.load): {@code ready} hears each
     * on the main thread while the state still wants that key. One worker; a newer request makes an older one's phases moot.
     */
    static final class Loader {
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "edit-look");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        private final Handler main = new Handler(Looper.getMainLooper());
        private volatile String wanted = "";
        private volatile boolean shut;

        /** The tables for {@code s} (nothing when its key is "" or already being loaded). */
        void request(Context context, EditState s, Consumer<Tables> ready) {
            final String key = tableKey(s);
            final FilmSim.Settings f = film(s);
            final boolean grainLut = wantsGrainLut(f);
            final String full = key + (grainLut ? "|grain" : "");
            if (key.isEmpty() || full.equals(wanted) || shut) {
                if (key.isEmpty()) wanted = "";
                return;
            }
            wanted = full;
            final String lut = lut(s);
            final float lutStrength = lutStrength(s);
            final Context app = context.getApplicationContext();
            worker.execute(() -> {
                if (!full.equals(wanted) || shut) return;
                try {
                    final FilmLut g = grainLut ? FilmSim.grainLut(f, GRAIN_LUT) : null;
                    FinishedLook.load(app, f, lut, lutStrength, () -> full.equals(wanted) && !shut, table -> {
                        final Tables t = new Tables(key, table, g);
                        main.post(() -> {
                            if (full.equals(wanted) && !shut) ready.accept(t);
                        });
                    });
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "look tables " + key + ": " + e);
                }
            });
        }

        /** The state wants another key (or none): what is loading is not wanted any more. */
        void forget() {
            wanted = "";
        }

        void shutdown() {
            shut = true;
            worker.shutdownNow();
        }
    }

    /**
     * The tables for the export (blocking, its worker thread): {@code have} (the preview's) when it is the final one for the key,
     * else the reference table within {@code timeoutMs}, else the first phase's; null for nothing to apply.
     */
    @Nullable
    static Tables blocking(Context context, EditState s, @Nullable Tables have, long timeoutMs) {
        final String key = tableKey(s);
        if (key.isEmpty()) return null;
        final FilmSim.Settings f = film(s);
        final FinishedLook.Table colour = FinishedLook.blocking(context, f, lut(s), lutStrength(s),
                have != null && key.equals(have.key) ? have.colour : null, timeoutMs);
        if (colour == null) return null;
        FilmLut g = have != null && key.equals(have.key) ? have.grainLut : null;
        if (g == null && wantsGrainLut(f)) g = FilmSim.grainLut(f, GRAIN_LUT);
        return new Tables(key, colour, g);
    }
}
