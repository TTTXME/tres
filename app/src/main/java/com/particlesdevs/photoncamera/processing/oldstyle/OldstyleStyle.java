package com.particlesdevs.photoncamera.processing.oldstyle;

/**
 * P630c «Oldstyle»: the mode's style (owner, 2026-11-12, two reference videos: «Палитра» and «Расширенные», «стилистика должна
 * остаться наша»). The palette's two axes - «Тон» (up lighter and airier, down darker and denser) and «Цвет» (left a cool grey,
 * right a warm salmon colour) - and twelve advanced parameters, each -100..100 (0 none) or, for the discrete ones (aura, halo,
 * grain), 0..3 (off, low, medium, high). One maths for the viewfinder and the photo: utils/import_oldstyle_adjust.glsl
 * (oldstyleStyle), spliced into preview/main_fs and imported by oldstyle/adjust.glsl; the uniforms are {@link #uniforms}.
 */
public final class OldstyleStyle {
    /** The parameters in their stored and uniform order (the palette's two first). */
    public enum Param {
        TONE("tone", false), COLOR("color", false),
        EXPOSURE("exposure", false), AURA("aura", true), HALO("halo", true), GRAIN("grain", true),
        WARMTH("warmth", false), HUE("hue", false), HIGHLIGHTS("highlights", false), SHADOWS("shadows", false),
        WARM_BOOST("warm_boost", false), COOL_BOOST("cool_boost", false), SHARPNESS("sharpness", false), VIGNETTE("vignette", false);

        public final String key;
        /** 0..3 steps (off, low, medium, high) instead of -100..100. */
        public final boolean discrete;

        Param(String key, boolean discrete) {
            this.key = key;
            this.discrete = discrete;
        }

        public int min() { return discrete ? 0 : -100; }
        public int max() { return discrete ? 3 : 100; }

        public int clamp(int v) { return Math.max(min(), Math.min(max(), v)); }

        /** The shader's value: -1..1, a discrete step 0, 1/3, 2/3, 1. */
        public float unit(int v) { return discrete ? clamp(v) / 3f : clamp(v) / 100f; }
    }

    /** The advanced tab's chips in the reference's order. */
    public static final Param[] ADVANCED = {Param.EXPOSURE, Param.AURA, Param.HALO, Param.GRAIN, Param.WARMTH, Param.HUE,
            Param.HIGHLIGHTS, Param.SHADOWS, Param.WARM_BOOST, Param.COOL_BOOST, Param.SHARPNESS, Param.VIGNETTE};
    /** The base of the palette (one for now; the HUD names it). */
    public static final String BASE = "Natural";

    private final int[] values = new int[Param.values().length];

    public OldstyleStyle() {}

    public OldstyleStyle(OldstyleStyle other) {
        System.arraycopy(other.values, 0, values, 0, values.length);
    }

    public int get(Param p) { return values[p.ordinal()]; }

    public OldstyleStyle set(Param p, int v) {
        values[p.ordinal()] = p.clamp(v);
        return this;
    }

    public boolean neutral() {
        for (int v : values) if (v != 0) return false;
        return true;
    }

    public void reset() {
        java.util.Arrays.fill(values, 0);
    }

    /**
     * The four vec4 uniforms of oldstyleStyle (uOs0..uOs3): (tone, colour, exposure, warmth), (hue, highlights, shadows, warm
     * boost), (cool boost, sharpness, vignette, aura), (halo, grain, 0, 0).
     */
    public float[] uniforms() {
        final Param[] order = {Param.TONE, Param.COLOR, Param.EXPOSURE, Param.WARMTH, Param.HUE, Param.HIGHLIGHTS, Param.SHADOWS,
                Param.WARM_BOOST, Param.COOL_BOOST, Param.SHARPNESS, Param.VIGNETTE, Param.AURA, Param.HALO, Param.GRAIN};
        final float[] u = new float[16];
        for (int i = 0; i < order.length; i++) u[i] = order[i].unit(get(order[i]));
        return u;
    }

    /**
     * P630e, the order with the tone: «Экспозиция» acts before the tone module, as a real exposure change - both virtual
     * exposures' TETs times 2^EV (OldstyleAe.Params.exposureEv; the viewfinder's tone gains the same, MainRenderer), so the 2.7 /
     * 3.1 curve and fusion see it; ±1.5 EV over its range, as the after-tone shader's own exposure was.
     */
    public float exposureEv() {
        return 1.5f * Param.EXPOSURE.unit(get(Param.EXPOSURE));
    }

    /**
     * The uniforms of the style after the tone (P630e): {@link #uniforms} with the exposure at 0 (it went into the tone's gains).
     * Every other parameter - «Тон», «Цвет», warmth, hue, highlights / shadows, the boosts, sharpness, vignette, grain, aura, halo -
     * acts on the tone's display values, the same order on the screen and in the photo. Highlights and shadows stay here: the
     * fusion's job is to even the light and the shadows out, a change of them before it would mostly be undone by its weights.
     */
    public float[] uniformsAfterTone() {
        final float[] u = uniforms();
        u[2] = 0f;
        return u;
    }

    /** Whether the style has anything to do after the tone (every parameter but the exposure at 0: no pass). */
    public boolean neutralAfterTone() {
        for (Param p : Param.values()) if (p != Param.EXPOSURE && get(p) != 0) return false;
        return true;
    }

    @Override public boolean equals(Object o) {
        return o instanceof OldstyleStyle && java.util.Arrays.equals(values, ((OldstyleStyle) o).values);
    }

    @Override public int hashCode() { return java.util.Arrays.hashCode(values); }

    @Override public String toString() {
        final StringBuilder s = new StringBuilder();
        for (Param p : Param.values()) if (get(p) != 0) s.append(s.length() == 0 ? "" : " ").append(p.key).append('=').append(get(p));
        return s.length() == 0 ? "neutral" : s.toString();
    }

    /** The palette's HUD line from its three words (the UI's Lang.t): «База: Natural · Тон: 81 · Цвет: -100». */
    public String hud(String base, String tone, String colour) {
        return base + ": " + BASE + "  ·  " + tone + ": " + get(Param.TONE) + "  ·  " + colour + ": " + get(Param.COLOR);
    }

    /** The long side of the picture / this = the grain's cell (px), the same share of the picture on the screen and in the photo. */
    public static final float GRAIN_CELLS = 1200f;

    /** The sharpness blur's step (px) of a picture whose long side is {@code longSide}: the same share of it everywhere. */
    public static float sharpStep(int longSide) {
        return Math.max(1f, longSide / 1600f);
    }
}
