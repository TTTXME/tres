package com.scamera.app.processing.color;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * P619 (owner, 2026-11-11: «Добавь возможность в нашем редакторе в галерее применять луты из камеры и симуляции плёнок»): the
 * camera's LUT and film colour on a finished, display-encoded picture (the gallery editor's «LUT» tab). A finished JPEG has no
 * scene light, so the film goes through the camera's display route - the one the ISP viewfinder and the settings' preview take
 * (FilmChain.loadLut: FilmSim.lut of the display-encoded colour, then the reference model's DISPLAY table from
 * FilmPreviewLut when it is ready) - and not the photo's scene route (FilmChain.scene, why/combine FILM_SIM 1). A LUT alone is
 * the camera's own table (FilmLut.load: a built-in look or a .cube), looked up as the camera looks it up (FILM_LUT 1,
 * preview/main_fs.glsl) and mixed by its strength.
 *
 * <p>The table here holds the film at strength 1 with the chosen LUT on its print at the LUT's strength
 * (FilmChain.compose(film, 1, user, s)); the film's own strength is the shader's mix, so its slider never rebuilds a table. The
 * camera's display chain bakes that mix into the table (FilmChain.previewDisplay: compose(printed, s, null, 0)); both are
 * x + (printed(x) - x) s at every point (the trilinear lookup of an identity is exact) - FinishedLookTest holds them within one
 * 8-bit code.
 */
public final class FinishedLook {
    private FinishedLook() {}

    /** A display table and how it was made. */
    public static final class Table {
        /** {@link #key} of what it stands for. */
        public final String key;
        public final FilmLut lut;
        /** The reference model's table (FilmPreviewLut, DISPLAY), not the first phase's FilmSim.lut. */
        public final boolean reference;

        Table(String key, FilmLut lut, boolean reference) {
            this.key = key;
            this.lut = lut;
            this.reference = reference;
        }
    }

    /**
     * What the table of {@code film} (null none) and the LUT {@code lut} ("" none) at {@code lutStrength} depends on: "" nothing,
     * the LUT's name alone (its strength is the shader's), or the film's FilmSim.Settings.lutKey with the LUT and its strength (not
     * the film's strength, the format or the grain).
     */
    public static String key(@Nullable FilmSim.Settings film, String lut, float lutStrength) {
        final boolean user = lut != null && !lut.isEmpty() && lutStrength > 0f;
        if (film == null) return user ? "lut:" + lut : "";
        return "film:" + film.lutKey() + "|" + (user ? lut : "") + "|" + String.format(Locale.ROOT, "%.2f", user ? lutStrength : 0f);
    }

    /** The shader's mix of the table: the film's strength with a film, else the LUT's. */
    public static float shaderStrength(@Nullable FilmSim.Settings film, float lutStrength) {
        return film != null ? Math.max(0f, Math.min(1f, film.strength)) : Math.max(0f, Math.min(1f, lutStrength));
    }

    // ---- small caches of our own (FilmLut.chosen and FilmChain.lut keep one entry each, the camera's: not evicted from here)
    private static final int CACHE = 6;
    private static final Map<String, FilmLut> USER = lru(), LEGACY = lru();

    private static Map<String, FilmLut> lru() {
        return new LinkedHashMap<String, FilmLut>(8, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FilmLut> eldest) {
                return size() > CACHE;
            }
        };
    }

    /** The camera's table of a stored LUT value (FilmLut.load), parsed once; null when it cannot be read. */
    @Nullable
    public static FilmLut user(Context context, String name) {
        if (name == null || name.isEmpty()) return null;
        synchronized (USER) {
            if (USER.containsKey(name)) return USER.get(name);
        }
        final FilmLut l = FilmLut.load(context, name);
        synchronized (USER) {
            USER.put(name, l);
        }
        return l;
    }

    /** A .cube imported again under the same name: read anew. */
    public static void forget(String name) {
        synchronized (USER) {
            USER.remove(name);
        }
    }

    /** The first phase's film table (FilmSim.lut of the display colour, FilmLooks.SIZE a side), built once per lutKey. */
    static FilmLut legacy(FilmSim.Settings film) {
        final String k = film.lutKey();
        synchronized (LEGACY) {
            final FilmLut hit = LEGACY.get(k);
            if (hit != null) return hit;
        }
        final FilmLut l = FilmSim.lut(film, FilmLooks.SIZE);
        synchronized (LEGACY) {
            LEGACY.put(k, l);
        }
        return l;
    }

    /** The film's table with the LUT on its print: compose(film, 1, user, s), or the film's own without a LUT. */
    static FilmLut printed(FilmLut film, @Nullable FilmLut user, float lutStrength) {
        return user != null && lutStrength > 0f ? FilmChain.compose(film, 1f, user, lutStrength) : film;
    }

    /**
     * The table, in two phases as the camera's viewfinder loads it (FilmChain.loadLut): on this thread the LUT's table, or the
     * film's first-phase table (FilmSim.lut); then, for a stock of the reference model, its DISPLAY table when FilmPreviewLut has
     * rendered it (at once when it is cached). {@code ready} is not called for nothing to apply or a LUT that cannot be read.
     * Call it off the main thread: the first phase is a CPU build.
     */
    public static void load(Context context, @Nullable FilmSim.Settings film, String lut, float lutStrength, BooleanSupplier current,
                            Consumer<Table> ready) {
        final String key = key(film, lut, lutStrength);
        if (key.isEmpty() || !current.getAsBoolean()) return;
        final boolean withUser = lut != null && !lut.isEmpty() && lutStrength > 0f;
        final FilmLut user = withUser ? user(context, lut) : null;
        if (film == null) {
            if (user != null) ready.accept(new Table(key, user, false));
            return;
        }
        if (withUser && user == null) return; // the LUT on the print cannot be read: nothing rather than another look
        if (FilmPreviewLut.supported(film)) {
            final FilmLut cached = FilmPreviewLut.cached(film, FilmLooks.SIZE, FilmPreviewLut.Domain.DISPLAY);
            if (cached != null) {
                if (current.getAsBoolean()) ready.accept(new Table(key, printed(cached, user, lutStrength), true));
                return;
            }
        }
        final FilmLut first = legacy(film);
        if (first != null && current.getAsBoolean()) ready.accept(new Table(key, printed(first, user, lutStrength), false));
        if (FilmPreviewLut.supported(film))
            FilmPreviewLut.load(context, film, FilmLooks.SIZE, FilmPreviewLut.Domain.DISPLAY, current,
                    reference -> ready.accept(new Table(key, printed(reference, user, lutStrength), true)));
    }

    /**
     * The table {@link #load} ends at, waited for (the editor's export, off the main thread): {@code have} when it is already that
     * (the reference one, or the only one there will be), else the reference table within {@code timeoutMs}, else the first
     * phase's. Null for nothing to apply.
     */
    @Nullable
    public static Table blocking(Context context, @Nullable FilmSim.Settings film, String lut, float lutStrength, @Nullable Table have,
                                 long timeoutMs) {
        final String key = key(film, lut, lutStrength);
        if (key.isEmpty()) return null;
        final boolean referenceWanted = film != null && FilmPreviewLut.supported(film) && FilmReferenceEngine.available();
        if (have != null && have.key.equals(key) && (have.reference || !referenceWanted)) return have;
        final Table[] last = {null};
        final CountDownLatch done = new CountDownLatch(1);
        load(context, film, lut, lutStrength, () -> true, t -> {
            synchronized (last) {
                last[0] = t;
            }
            if (t.reference || !referenceWanted) done.countDown();
        });
        if (referenceWanted) {
            try {
                done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (last) {
            if (last[0] != null) return last[0];
        }
        return have != null && have.key.equals(key) ? have : null;
    }

    /**
     * The camera's display lookup of one colour (preview/main_fs.glsl and why/combine.glsl FILM_LUT 1, the trilinear FilmLut.sample)
     * mixed by {@code strength}: the host reference of the editor's look pass and the thumbnails' colour.
     */
    public static void apply(FilmLut lut, float strength, float r, float g, float b, float[] tmp, float[] out) {
        lut.sampleInto(r, g, b, tmp);
        out[0] = r + (tmp[0] - r) * strength;
        out[1] = g + (tmp[1] - g) * strength;
        out[2] = b + (tmp[2] - b) * strength;
    }

    /** ARGB pixels through {@link #apply} (the «LUT» tab's thumbnails of the photo). */
    public static int[] apply(int[] argb, FilmLut lut, float strength) {
        final int[] out = new int[argb.length];
        final float[] tmp = new float[3], o = new float[3];
        for (int i = 0; i < argb.length; i++) {
            final int p = argb[i];
            apply(lut, strength, ((p >> 16) & 255) / 255f, ((p >> 8) & 255) / 255f, (p & 255) / 255f, tmp, o);
            out[i] = (p & 0xFF000000) | (code(o[0]) << 16) | (code(o[1]) << 8) | code(o[2]);
        }
        return out;
    }

    static int code(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    /** The first-phase film table at a size of its own (the thumbnails: 17 a side builds about seven times faster than 33). */
    public static FilmLut thumbnailFilm(FilmSim.Settings film, int size) {
        return FilmSim.lut(film, size);
    }
}
