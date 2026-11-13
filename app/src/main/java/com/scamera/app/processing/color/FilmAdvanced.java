package com.scamera.app.processing.color;

import androidx.annotation.Nullable;

import com.scamera.app.util.Lang;

import java.util.Locale;

/**
 * P487 (owner, 2026-11-03: «добавь такие же настройки спектра нам», three pages of the reference film app): the film page's DIR
 * couplers, halation and development / spectral-model controls - each one a field of the spectral renderer's parameter block
 * (FilmReferenceParameters.from writes it at spektrafilm-ofx 86476afc's default when untouched, so today's look is unchanged).
 * The DIR and halation switches are their amounts (FilmSim.DIR_KEY / HALATION_KEY above 0), not keys of their own; the film
 * exposure stays FilmSim.EXPOSURE_KEY. Hanatos 2025 uses the per-stock archived bandpass data
 * (Resources/data/profiles/archive/*.json) is not in our generated profiles, the renderer refuses that method without it.
 */
public final class FilmAdvanced {
    /** P487: one slider of the page: its key, range, step, upstream default, unit, and whether the colour LUT depends on it. */
    public static final class Param {
        public final String key, unit, en;
        public final float min, max, step, def;
        public final boolean lut;
        private final java.util.function.Function<android.content.Context, String> title;

        Param(String key, float min, float max, float step, float def, String unit, boolean lut, String en,
              java.util.function.Function<android.content.Context, String> title) {
            this.key = key; this.min = min; this.max = max; this.step = step; this.def = def; this.unit = unit; this.lut = lut;
            this.en = en; this.title = title;
        }

        public String title(@Nullable android.content.Context c) { return title.apply(c); }

        /** The stored value clamped to the range (the default when unset or unreadable). */
        float read(@Nullable String stored) { return FilmSim.clamp(FilmSim.number(stored, def), min, max); }
    }

    private static final String P = "pref_film_sim_";
    // P487: DIR couplers (SpektraParameters dirCouplers*: inhibitor diffusion, its tail, same-layer / interlayer inhibition)
    public static final Param DIR_SPREAD = new Param(P + "dir_spread", 0, 100, 1, 20, " µm", false, "Inhibitor spread", c -> Lang.t(c, "Ширина цветной каймы", "Inhibitor spread"));
    public static final Param DIR_TAIL = new Param(P + "dir_tail", 0, 1000, 10, 200, " µm", false, "Spread tail", c -> Lang.t(c, "Дальняя кайма", "Spread tail"));
    public static final Param DIR_TAIL_WEIGHT = new Param(P + "dir_tail_weight", 0, 1, .01f, .06f, "", false, "Tail weight", c -> Lang.t(c, "Сила дальней каймы", "Tail weight"));
    public static final Param DIR_SAME = new Param(P + "dir_same", 0, 2, .05f, 1, "", true, "Same-layer inhibition", c -> Lang.t(c, "Мягкость каждого цвета", "Same-layer inhibition"));
    public static final Param DIR_INTER = new Param(P + "dir_inter", 0, 2, .05f, 1, "", true, "Interlayer inhibition", c -> Lang.t(c, "Разделение цветов", "Interlayer inhibition"));
    /** P487: the nine DIR gammas in packet order (offsets 220 .. 252); their defaults are the stock's (FilmReferenceParameters.dirGamma). */
    public static final String[] DIR_GAMMA_KEYS = {P + "dir_gamma_rr", P + "dir_gamma_gg", P + "dir_gamma_bb", P + "dir_gamma_rg",
            P + "dir_gamma_rb", P + "dir_gamma_gr", P + "dir_gamma_gb", P + "dir_gamma_br", P + "dir_gamma_bg"};
    public static final String[] DIR_GAMMA_NAMES = {"R-R", "G-G", "B-B", "R-G", "R-B", "G-R", "G-B", "B-R", "B-G"};
    public static final float DIR_GAMMA_MAX = 1f, DIR_GAMMA_STEP = .001f;
    // P487: halation (scatter and halation amount x FilmSim.HALATION_KEY, their sizes, the highlight boost)
    public static final Param SCATTER = new Param(P + "scatter", 0, 2, .05f, 1, "", false, "Scatter amount", c -> Lang.t(c, "Дымка вокруг ярких мест", "Scatter amount"));
    public static final Param SCATTER_SIZE = new Param(P + "scatter_size", 0, 2, .05f, 1, "", false, "Scatter size", c -> Lang.t(c, "Размер дымки", "Scatter size"));
    public static final Param HALO = new Param(P + "halation_amount", 0, 2, .05f, 1, "", false, "Halation amount", c -> Lang.t(c, "Красное свечение (ореол)", "Halation amount"));
    public static final Param HALO_SIZE = new Param(P + "halation_size", 0, 2, .05f, 1, "", false, "Halation size", c -> Lang.t(c, "Размер свечения", "Halation size"));
    public static final Param BOOST = new Param(P + "halation_boost", 0, 3, .1f, 0, " EV", false, "Highlight boost", c -> Lang.t(c, "Ярче свечение от ламп", "Highlight boost"));
    public static final Param BOOST_RANGE = new Param(P + "halation_boost_range", 0, 1, .01f, .3f, "", false, "Boost range", c -> Lang.t(c, "Плавность усиления", "Boost range"));
    public static final Param PROTECT = new Param(P + "halation_protect", 0, 8, .1f, 4, " EV", false, "Boost protect", c -> Lang.t(c, "С какой яркости усиливать", "Boost protect"));
    // P487: the film's development (push / pull, gamma, the negative's bleach bypass and leuco cyan coupling)
    public static final Param PUSH = new Param(P + "push", -2, 2, .1f, 0, " EV", true, "Push / pull", c -> Lang.t(c, "Пуш / пулл (пере- и недопроявка)", "Push / pull"));
    public static final Param DEV_GAMMA = new Param(P + "dev_gamma", .5f, 2, .05f, 1, "", true, "Development gamma", c -> Lang.t(c, "Контраст проявки", "Development gamma"));
    public static final Param BLEACH = new Param(P + "bleach", 0, 1, .05f, 0, "", true, "Negative bleach bypass", c -> Lang.t(c, "Без отбеливания (как в старом кино)", "Negative bleach bypass"));
    public static final Param LEUCO = new Param(P + "leuco", 0, 2, .05f, 1, "", true, "Leuco cyan coupling", c -> Lang.t(c, "Оттенок без отбеливания", "Leuco cyan coupling"));
    public static final String PUSH_MODE_KEY = P + "push_mode", SPECTRAL_KEY = P + "spectral";
    /**
     * P487: spektrafilm's PushPullMode and RgbToRawMethod as stored, the default first (Hanatos 2026); Hanatos 2025 since its
     * archived per-film bandpass is in the generated profiles (SOURCE.json, Resources/data/profiles/archive).
     */
    public static final String[] PUSH_MODES = {"standard", "experimental"},
            SPECTRAL_MODELS = {"hanatos2026", "mallett2019", "hanatos2025"};
    static final int[] SPECTRAL_METHOD = {2, 1, 0};

    public static final Param[] SLIDERS = {DIR_SPREAD, DIR_TAIL, DIR_TAIL_WEIGHT, DIR_SAME, DIR_INTER, SCATTER, SCATTER_SIZE, HALO,
            HALO_SIZE, BOOST, BOOST_RANGE, PROTECT, PUSH, DEV_GAMMA, BLEACH, LEUCO};
    /** Every stored key (FilmSim.KEYS appends them: the lab, the backups, the pages' and the tray's resets). */
    public static final String[] KEYS;
    static {
        KEYS = new String[SLIDERS.length + DIR_GAMMA_KEYS.length + 2];
        int k = 0;
        for (Param p : SLIDERS) KEYS[k++] = p.key;
        for (String g : DIR_GAMMA_KEYS) KEYS[k++] = g;
        KEYS[k++] = PUSH_MODE_KEY;
        KEYS[k] = SPECTRAL_KEY;
    }

    @Nullable
    public static Param param(@Nullable String key) {
        for (Param p : SLIDERS) if (p.key.equals(key)) return p;
        return null;
    }

    /**
     * P487: the key whose value 0 makes {@code key} act on nothing (the page hides it then): the DIR amount for the DIR rows, the
     * halation amount for the halation rows, the boost for its range and threshold, the bleach bypass for the leuco coupling, the
     * push / pull for its mode; null for an independent control.
     */
    @Nullable
    public static String gate(@Nullable String key) {
        if (key == null) return null;
        if (gammaIndex(key) >= 0 || key.equals(DIR_SPREAD.key) || key.equals(DIR_TAIL.key) || key.equals(DIR_TAIL_WEIGHT.key)
                || key.equals(DIR_SAME.key) || key.equals(DIR_INTER.key)) return FilmSim.DIR_KEY;
        if (key.equals(SCATTER.key) || key.equals(SCATTER_SIZE.key) || key.equals(HALO.key) || key.equals(HALO_SIZE.key)
                || key.equals(BOOST.key)) return FilmSim.HALATION_KEY;
        if (key.equals(BOOST_RANGE.key) || key.equals(PROTECT.key)) return BOOST.key;
        if (key.equals(LEUCO.key)) return BLEACH.key;
        if (key.equals(PUSH_MODE_KEY)) return PUSH.key;
        return null;
    }

    /** P487: the value a {@link #gate} is given to compare its control (spektrafilm's full amount, a visible boost / bypass / push). */
    public static String gateValue(String gate) {
        if (gate.equals(BOOST.key) || gate.equals(PUSH.key)) return "1.00";
        if (gate.equals(BLEACH.key)) return "0.50";
        return "100";
    }

    public static int gammaIndex(@Nullable String key) {
        for (int i = 0; i < DIR_GAMMA_KEYS.length; i++) if (DIR_GAMMA_KEYS[i].equals(key)) return i;
        return -1;
    }

    // ───── the values

    /** The sliders' values in {@link #SLIDERS} order (each clamped), the DIR gammas (NaN = the stock's own), the two choices. */
    private final float[] v;
    private final float[] gamma;
    public final int pushMode, spectral;

    private FilmAdvanced(float[] values, @Nullable float[] gamma, int pushMode, int spectral) {
        v = values.clone();
        this.gamma = new float[DIR_GAMMA_KEYS.length];
        for (int i = 0; i < this.gamma.length; i++)
            this.gamma[i] = gamma == null || !Float.isFinite(gamma[i]) ? Float.NaN : FilmSim.clamp(gamma[i], 0f, DIR_GAMMA_MAX);
        this.pushMode = pushMode == 1 ? 1 : 0;
        this.spectral = spectral >= 0 && spectral < SPECTRAL_MODELS.length ? spectral : 0;
    }

    private static float[] defaults() {
        final float[] d = new float[SLIDERS.length];
        for (int i = 0; i < d.length; i++) d[i] = SLIDERS[i].def;
        return d;
    }

    public static final FilmAdvanced DEFAULT = new FilmAdvanced(defaults(), null, 0, 0);

    private static FilmAdvanced of(float[] values, float[] gamma, int pushMode, int spectral) {
        return new FilmAdvanced(values, gamma, pushMode, spectral);
    }

    public float get(Param p) {
        for (int i = 0; i < SLIDERS.length; i++) if (SLIDERS[i] == p) return v[i];
        throw new IllegalArgumentException(p.key);
    }

    /** The DIR gamma {@code i} (packet order) set on the page, NaN for the stock's own. */
    public float gamma(int i) { return gamma[i]; }

    /** spektrafilm's RgbToRawMethod of the chosen model (2 Hanatos 2026, 1 Mallett 2019, 0 Hanatos 2025). */
    public int rgbToRawMethod() { return SPECTRAL_METHOD[spectral]; }

    /** P487: the settings stored now, read by {@code stored} (key, fallback -> value); out-of-range values clamped. */
    public static FilmAdvanced read(java.util.function.BinaryOperator<String> stored) {
        final float[] values = new float[SLIDERS.length];
        for (int i = 0; i < SLIDERS.length; i++) values[i] = SLIDERS[i].read(stored.apply(SLIDERS[i].key, null));
        final float[] g = new float[DIR_GAMMA_KEYS.length];
        for (int i = 0; i < g.length; i++) {
            final String s = stored.apply(DIR_GAMMA_KEYS[i], null);
            g[i] = s == null || s.trim().isEmpty() ? Float.NaN : FilmSim.number(s, Float.NaN);
        }
        return of(values, g, choice(PUSH_MODES, stored.apply(PUSH_MODE_KEY, PUSH_MODES[0])),
                choice(SPECTRAL_MODELS, stored.apply(SPECTRAL_KEY, SPECTRAL_MODELS[0])));
    }

    static int choice(String[] ids, @Nullable String stored) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(stored)) return i;
        return 0;
    }

    /** Every value at spektrafilm's default (the DIR gammas the stock's own). */
    public boolean isDefault() {
        return lutToken().isEmpty() && spatialDefault();
    }

    private boolean spatialDefault() {
        for (int i = 0; i < SLIDERS.length; i++) if (!SLIDERS[i].lut && v[i] != SLIDERS[i].def) return false;
        return true;
    }

    private static final String TOKEN = "x";

    /**
     * P487: what the colour LUT depends on (FilmSim.Settings.lutKey's 9th field: the viewfinder and the page ask for a new table
     * when it changes, FilmChain parses it back): the spectral model, the development and the DIR inhibitions / gammas; "" at the
     * defaults (the keys of an untouched recipe are as before).
     */
    public String lutToken() {
        boolean any = pushMode != 0 || spectral != 0;
        for (int i = 0; i < SLIDERS.length; i++) if (SLIDERS[i].lut && v[i] != SLIDERS[i].def) any = true;
        for (float g : gamma) if (Float.isFinite(g)) any = true;
        if (!any) return "";
        final StringBuilder b = new StringBuilder(TOKEN).append(spectral).append(',').append(pushMode);
        for (int i = 0; i < SLIDERS.length; i++) if (SLIDERS[i].lut) b.append(',').append(fmt(v[i]));
        for (float g : gamma) b.append(',').append(Float.isFinite(g) ? fmt(g) : "-");
        return b.toString();
    }

    public static boolean isToken(@Nullable String field) {
        return field != null && field.startsWith(TOKEN) && field.indexOf(',') > 0;
    }

    /** P487: the colour settings of a {@link #lutToken} (the spatial ones at their defaults); DEFAULT when unreadable. */
    public static FilmAdvanced fromToken(@Nullable String token) {
        if (!isToken(token)) return DEFAULT;
        final String[] f = token.substring(TOKEN.length()).split(",", -1);
        int lut = 0;
        for (Param p : SLIDERS) if (p.lut) lut++;
        if (f.length != 2 + lut + DIR_GAMMA_KEYS.length) return DEFAULT;
        final float[] values = new float[SLIDERS.length];
        int k = 2;
        for (int i = 0; i < SLIDERS.length; i++) values[i] = SLIDERS[i].lut ? SLIDERS[i].read(f[k++]) : SLIDERS[i].def;
        final float[] g = new float[DIR_GAMMA_KEYS.length];
        for (int i = 0; i < g.length; i++) g[i] = "-".equals(f[k + i]) ? Float.NaN : FilmSim.number(f[k + i], Float.NaN);
        return of(values, g, (int) FilmSim.number(f[1], 0), (int) FilmSim.number(f[0], 0));
    }

    private static String fmt(float x) {
        return String.format(Locale.ROOT, "%.3f", x);
    }

    /** P487: the non-default values for the photo's EXIF recipe ("" when untouched). */
    public String describe() {
        if (isDefault()) return "";
        final StringBuilder b = new StringBuilder();
        if (spectral != 0) b.append("; spectral ").append(SPECTRAL_MODELS[spectral]);
        if (pushMode != 0) b.append("; push mode ").append(PUSH_MODES[pushMode]);
        for (int i = 0; i < SLIDERS.length; i++)
            if (v[i] != SLIDERS[i].def) b.append("; ").append(SLIDERS[i].en.toLowerCase(Locale.ROOT)).append(' ').append(fmt(v[i]));
        for (int i = 0; i < gamma.length; i++)
            if (Float.isFinite(gamma[i])) b.append("; DIR gamma ").append(DIR_GAMMA_NAMES[i]).append(' ').append(fmt(gamma[i]));
        return b.toString();
    }

    /** P487: a choice's name on the page. */
    public static String pushModeName(@Nullable android.content.Context c, int i) {
        return i == 1 ? Lang.t(c, "Экспериментальный", "Experimental") : Lang.t(c, "Стандартный", "Standard");
    }

    public static String spectralName(int i) {
        return i == 1 ? "Mallett 2019" : i == 2 ? "Hanatos 2025" : "Hanatos 2026";
    }

    /** P487: a colour layer's everyday name (R red, G green, B blue) for the DIR calibration's help. */
    private static String layer(@Nullable android.content.Context c, char rgb) {
        return rgb == 'R' ? Lang.t(c, "красный", "red") : rgb == 'G' ? Lang.t(c, "зелёный", "green") : Lang.t(c, "синий", "blue");
    }

    /**
     * P487 (owner: «с описанием понятным самому обычному юзеру»): what each control changes in the photo, in everyday words
     * (ParamHelp.of: the page's long press, the lab's summary).
     */
    @Nullable
    public static String help(@Nullable android.content.Context c, @Nullable String key) {
        if (key == null) return null;
        final int g = gammaIndex(key);
        if (g >= 0) {
            final String from = layer(c, DIR_GAMMA_NAMES[g].charAt(0)), to = layer(c, DIR_GAMMA_NAMES[g].charAt(2));
            return g < 3 ? Lang.t(c, "Тонкая настройка: насколько " + from + " цвет приглушает сам себя. По умолчанию — как у выбранной плёнки, обычно трогать не нужно.",
                    "Fine tuning: how much the " + from + " colour tones itself down. The default is the chosen film's own; usually leave it.")
                    : Lang.t(c, "Тонкая настройка: насколько " + from + " цвет приглушает " + to + ". По умолчанию — как у выбранной плёнки, обычно трогать не нужно.",
                    "Fine tuning: how much the " + from + " colour tones down the " + to + ". The default is the chosen film's own; usually leave it.");
        }
        switch (key) {
            case PUSH_MODE_KEY:
                return Lang.t(c, "«Стандартный» — меняется только контраст. «Экспериментальный» — меняются ещё яркость и цвета, ближе к настоящей пере- и недопроявке.",
                        "«Standard» changes only the contrast. «Experimental» also changes the brightness and the colours, closer to real push / pull development.");
            case SPECTRAL_KEY:
                return Lang.t(c, "Как цвета снимка переводятся в свет для плёнки. Hanatos 2026 — по умолчанию, точнее всего. Hanatos 2025 — прежняя версия: чуть иначе передаёт яркие и насыщенные цвета. Mallett 2019 — самая простая, насыщенные цвета выглядят иначе.",
                        "How the photo's colours are turned into light for the film. Hanatos 2026 is the default and the most accurate. Hanatos 2025 is the earlier version: bright, saturated colours come out slightly differently. Mallett 2019 is the simplest; saturated colours look different.");
            default:
        }
        final Param p = param(key);
        if (p == null) return null;
        if (p == DIR_SPREAD) return Lang.t(c, "Насколько широко вокруг цветных краёв идёт тонкая кайма, которая делает их чётче. Больше — шире и мягче кайма.",
                "How wide the thin fringe round colour edges is that makes them crisper. More is a wider, softer fringe.");
        if (p == DIR_TAIL) return Lang.t(c, "Слабая широкая кайма далеко от края цветной детали. Больше — шире.",
                "A faint, wide fringe far from a colour detail's edge. More is wider.");
        if (p == DIR_TAIL_WEIGHT) return Lang.t(c, "Насколько заметна дальняя кайма. 0 — её нет.", "How visible the far fringe is. 0 is none.");
        if (p == DIR_SAME) return Lang.t(c, "Больше — каждый цвет мягче и спокойнее, меньше «кислотных» пятен. Меньше — цвета жёстче.",
                "More makes each colour softer and calmer, fewer garish patches. Less makes colours harsher.");
        if (p == DIR_INTER) return Lang.t(c, "Больше — цвета сильнее отличаются друг от друга, картинка сочнее. Меньше — цвета ближе друг к другу, бледнее.",
                "More makes colours stand apart, a juicier picture. Less brings them closer together, paler.");
        if (p == SCATTER) return Lang.t(c, "Лёгкая светлая дымка вокруг ярких мест: свет немного расплывается внутри плёнки. 0 — без дымки.",
                "A light haze round bright areas: the light spreads a little inside the film. 0 is no haze.");
        if (p == SCATTER_SIZE) return Lang.t(c, "Насколько далеко расходится дымка. 1 — как у настоящей плёнки.",
                "How far the haze spreads. 1 is as on real film.");
        if (p == HALO) return Lang.t(c, "Красно-оранжевое свечение вокруг ярких источников, как на плёнке: фонари, окна, солнце. 0 — без свечения.",
                "A red-orange glow round bright lights, as on film: street lamps, windows, the sun. 0 is no glow.");
        if (p == HALO_SIZE) return Lang.t(c, "Насколько широко расходится свечение вокруг ярких мест. 1 — как у настоящей плёнки.",
                "How wide the glow round bright areas spreads. 1 is as on real film.");
        if (p == BOOST) return Lang.t(c, "Делает свечение вокруг самых ярких мест — ламп, солнца, бликов — сильнее. 0 — выключено. С ним снимок обрабатывается дольше.",
                "Makes the glow round the brightest spots - lamps, the sun, reflections - stronger. 0 is off. With it the photo takes longer to process.");
        if (p == BOOST_RANGE) return Lang.t(c, "Насколько плавно усиливается свечение: больше — мягкий переход, меньше — резкий.",
                "How gradually the glow is strengthened: more is a soft transition, less a sharp one.");
        if (p == PROTECT) return Lang.t(c, "Чем больше, тем только самые яркие места получают усиленное свечение; меньше — и места потемнее.",
                "The higher, the fewer, brightest spots get the stronger glow; lower lets dimmer spots glow too.");
        if (p == PUSH) return Lang.t(c, "Плюс — как при перепроявке: контрастнее и плотнее. Минус — как при недопроявке: мягче и бледнее. 0 — обычная проявка.",
                "Plus is like pushed film: more contrast and depth. Minus is like pulled film: softer and paler. 0 is normal development.");
        if (p == DEV_GAMMA) return Lang.t(c, "Сильнее — контрастнее и насыщеннее, слабее — мягче и бледнее. 1 — как у плёнки.",
                "Higher is contrastier and more saturated, lower softer and paler. 1 is the film's own.");
        if (p == BLEACH) return Lang.t(c, "Эффект «без отбеливания»: цвета приглушённые, контраст жёстче, тени глубже — как в старом кино. 0 — выключено.",
                "The bleach bypass look: muted colours, harder contrast, deeper shadows - like old cinema. 0 is off.");
        return Lang.t(c, "Работает только вместе с «Без отбеливания»: меняет оттенок этого эффекта. 1 — как у настоящей плёнки.",
                "Works only together with «Negative bleach bypass»: changes that look's tint. 1 is as on real film.");
    }
}
