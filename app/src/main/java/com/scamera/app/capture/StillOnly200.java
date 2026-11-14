package com.scamera.app.capture;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.scamera.app.util.Lang;

/**
 * P684 (owner, 2026-11-14, X300 Ultra on build 31268: «200мп не работают на 300U, исправь»; «Проблема не в буфере»; «Нам нужно
 * чтобы как на 200 ультра работали 50 и 200мп без ремозаика»): how a 200 MP series of P208's still-only route (the X100 / X300
 * Ultra: the viewfinder in the normal mode, the full-size RAW stream for the stills only) asks the HAL for its stills - an ordered
 * trial, every still verified, the variant that worked remembered per camera, size and firmware.
 * <p>
 * Root cause of «SDK STILL buffer lost» on the first still (three owner logs, cameras 3 and 5, no still result before the loss):
 * P208 assumed the HAL switches the sensor into the 200 MP mode for a still of the full-size stream by itself. The X300 Ultra's
 * HAL picks its sensor mode / use case from its selector (PD2547.json): a full-size use case (FULLSATREMOSAIC for SCENARIO 12 at
 * 16320 wide, SATREMOSAIC) only with vivo.control.ultra_highresolution 1 in a photo UI module (com.vivo.current_ui_module, a
 * session key); the still-only session had neither (nor any key of the stock camera's full-size stills), so the HAL never ran a
 * full-size readout and returned the 16320-wide buffer lost. The route was never seen working on this phone (2026-10-19: a failed
 * 200 MP still in the owner's log, P315; P284 asked for a working 200 MP the same day).
 * <p>
 * The X300 Ultra's variants in order ({@link #order}) - the sensor's own Tetra mosaic first (the selector's full-size use cases
 * read the sensor without its remosaic, «NORemosaic»), the HAL's remosaic last (X300FullSize has the keys):
 * <ul>
 * <li>{@link Variant#NATIVE}: the stock full-size keys (session, viewfinder, decision frame, still keys and ids) without the still's
 * remosaic pair (vivo.control.sensorMode 0 / isUpscale 1).</li>
 * <li>{@link Variant#NATIVE_RAW}: as NATIVE without vivo.control.onlyDng (the DNG path may remosaic).</li>
 * <li>{@link Variant#NATIVE_KEY}: NATIVE with our mode key (vivo.control.forceSensorMode = the Tetra row) on every still.</li>
 * <li>{@link Variant#HAL}: the stock camera's whole recipe: the HAL's remosaic, a plain Bayer 200 MP (block 1) - last, so 200 MP
 * never fails outright on this phone; its photo says so in the log.</li>
 * </ul>
 * Every still is checked: vcf.parameter.seamlessMode (768: the 200 MP full-size mode), the result's sensor mode (native variants),
 * a binned image in a corner of the buffer, its mosaic (MosaicBlockDetector: Tetra for the native variants, Bayer for HAL). A
 * series that fails moves the camera's size to the next variant for the next press (FULLRES stays on); one that completes keeps
 * its variant (a kept one gets one more press after a failure). The memory is stamped with {@link #TRIALS} and
 * Build.FINGERPRINT under the camera's FULLRES trial keys (VivoNativeFullRes.base): «Попробовать снова» forgets it; when every
 * variant failed the 200 MP row is noted failed (the size off, its button dimmed). scam_dev «vivo_fullres200_variant» 1..6 holds
 * one variant (nothing noted), «vivo_x300_fullsize 0» drops the stock keys (P208 then our mode key, as on the X100 Ultra). The
 * normal session's sensor mode and the other cameras are never touched; the X200 Ultra (its 200 MP session holds its mode, not
 * still-only) and OPPO are outside.
 * <p>
 * P699 (owner, X300 Ultra 2026-11-14 on build 31270: «Исправь все фулрезы которые нормально не работают»; cameras 3 and 5: NATIVE and
 * NATIVE_RAW each failed as «block 1, the profile's 4», a confident plain Bayer on every still, seamlessMode absent): the HAL
 * remosaicks the full-size stills whatever the native variants ask. A still-only series whose stills prove that Bayer completes as
 * a Bayer 200 MP photo (merged as block 1, VivoSdkFullResCapture.seriesBlock) instead of failing. The owner then decided («Для X300
 * Ultra просто оставляем 200мп с ремозаиком»): the X300 Ultra's order is NATIVE (the HAL's Bayer seen arriving whole) then HAL, its
 * profile a Bayer ({@link #remosaicOnly}), no press spent on the Tetra. Elsewhere (the X100 Ultra) a native variant's Bayer is noted
 * ({@link #bayer}): the next press tries the next native variant for the Tetra; with none left the first one that gave the Bayer is
 * kept ({@link Choice#bayer}), and a later hard failure goes back to it. {@link #TRIALS} 2 forgets the failures noted under the old
 * rule.
 */
final class StillOnly200 {
    private StillOnly200() { }

    enum Variant {
        NATIVE(true, false, true, false, "the stock full-size keys without the still's remosaic pair"),
        NATIVE_RAW(true, false, false, false, "the stock full-size keys without the still's remosaic pair and without onlyDng"),
        NATIVE_KEY(true, false, true, true, "the stock full-size keys without the remosaic pair, our mode key on every still"),
        HAL(true, true, true, false, "the stock camera's whole recipe: the HAL's remosaic, a plain Bayer"),
        KEY(false, false, false, true, "no stock keys, our mode key on every still"),
        BASE(false, false, false, false, "P208 as before: no stock keys, no mode key");

        /** The stock full-size keys (session, viewfinder, decision frame, stills) and the YUV companion stream. */
        final boolean recipe;
        /** The still's remosaic pair (vivo.control.sensorMode 0, isUpscale 1): the HAL's Bayer, block 1. */
        final boolean halRemosaic;
        /** vivo.control.onlyDng 1 on the stills (a recipe variant). */
        final boolean onlyDng;
        /** vivo.control.forceSensorMode = the profile's row on every still. */
        final boolean modeKey;
        final String words;

        Variant(boolean recipe, boolean halRemosaic, boolean onlyDng, boolean modeKey, String words) {
            this.recipe = recipe;
            this.halRemosaic = halRemosaic;
            this.onlyDng = onlyDng;
            this.modeKey = modeKey;
            this.words = words;
        }

        /** The owner's words of what the variant does. */
        String ownerWords() {
            switch (this) {
                case HAL: return Lang.t("как в стоковой камере, с ремозаиком камеры (Байер)", "as the stock camera, with the camera's remosaic (Bayer)");
                case BASE: return Lang.t("как раньше, без ключей", "as before, without keys");
                case KEY: return Lang.t("родная мозаика Tetra: ключ режима 200 Мп на кадрах", "the native Tetra mosaic: the 200 MP mode key on the frames");
                default: return Lang.t("родная мозаика Tetra с ключами стоковой камеры (вариант " + name() + ")",
                        "the native Tetra mosaic with the stock camera's keys (variant " + name() + ")");
            }
        }
    }

    /** The generation of the memory: variants noted under another one (or another firmware) are tried again. */
    static final int TRIALS = 2;
    private static final String SUFFIX = "_still200_", STAMP = "_stamp", STEP = "_step", OK = "_ok", WHY = "_why";
    /** P699: the step of the first native variant that delivered the HAL's remosaicked Bayer on this firmware (absent: none). */
    private static final String BAYER = "_bayer";
    /** P699: the why of a variant that delivered the HAL's Bayer (no failure). */
    static final String BAYER_WHY = "the HAL's remosaicked Bayer (block 1) from ";

    /** The route is this one: a native still-only profile of a vivo phone (never the OPPO's own mode route). */
    static boolean applies(@Nullable VivoSdkFullRes.Profile profile, boolean oppo) {
        return profile != null && profile.nativeRoute() && profile.stillOnly && !oppo;
    }

    /**
     * The order a camera tries the variants in: the X300 Ultra with the stock keys ({@code recipe}: scam_dev «vivo_x300_fullsize»,
     * on by default) the HAL's remosaic only (P699: NATIVE, whose stills the owner's log shows arriving whole as the HAL's
     * remosaicked Bayer, then the stock camera's whole recipe on a hard failure); else P208's way first, then our mode key.
     * scam_dev «vivo_fullres200_variant» 2 / 3 still holds NATIVE_RAW / NATIVE_KEY for a test.
     */
    static Variant[] order(@Nullable VivoNativeFullRes.Phone phone, boolean recipe) {
        return phone == VivoNativeFullRes.Phone.X300_ULTRA && recipe
                ? new Variant[]{Variant.NATIVE, Variant.HAL}
                : new Variant[]{Variant.BASE, Variant.KEY};
    }

    /**
     * P699 (owner, 2026-11-14: «Для X300 Ultra просто оставляем 200мп с ремозаиком, раз без ремозаика использовать невозможно»):
     * the phone's 200 MP still-only stills are the HAL's remosaicked Bayer (block 1), whichever variant asks.
     */
    static boolean remosaicOnly(@Nullable VivoNativeFullRes.Phone phone) {
        return phone == VivoNativeFullRes.Phone.X300_ULTRA;
    }

    /** The key base of a camera's size (under the camera's FULLRES trial keys: «Попробовать снова» clears it). */
    static String key(String cameraId, int width, int height) {
        return VivoNativeFullRes.base(cameraId) + SUFFIX + width + "x" + height;
    }

    static String key(String cameraId, VivoSdkFullRes.Profile profile) {
        return key(cameraId, profile.size.getWidth(), profile.size.getHeight());
    }

    /** The stamp of this memory on the firmware {@code fingerprint}. */
    static String stamp(@Nullable String fingerprint) {
        return "r" + TRIALS + " " + (fingerprint == null ? "" : fingerprint);
    }

    /** The variant of one session / series and where it comes from (for the log). */
    static final class Choice {
        final Variant variant;
        /** Its place in the camera's order (0-based); -1: held by scam_dev. */
        final int step;
        final Variant[] order;
        final String source;
        /** P699: the variant is kept as the HAL's remosaicked Bayer (its stills: block 1) on this firmware. */
        final boolean bayer;

        Choice(Variant variant, int step, Variant[] order, String source) {
            this(variant, step, order, source, false);
        }

        Choice(Variant variant, int step, Variant[] order, String source, boolean bayer) {
            this.variant = variant;
            this.step = step;
            this.order = order;
            this.source = source;
            this.bayer = bayer;
        }

        boolean dev() {
            return step < 0;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Choice && ((Choice) o).variant == variant && ((Choice) o).step == step;
        }

        @Override public int hashCode() {
            return variant.hashCode() * 31 + step;
        }

        @Override public String toString() {
            final StringBuilder chain = new StringBuilder();
            for (Variant v : order) chain.append(chain.length() == 0 ? "" : " > ").append(v);
            return variant + " (" + variant.words + ")" + (dev() ? "" : ", step " + (step + 1) + " of " + order.length)
                    + " [" + chain + "], " + source;
        }
    }

    /** What a failed series did to the memory: noted (the next variant, or every one failed) or not (no verdict on the variant). */
    static final class Outcome {
        final boolean noted, exhausted;
        /** The variant of the next press (null: none noted, or every one failed). */
        @Nullable final Variant next;
        /** The next press tries the same variant once more (it had completed a series before). */
        final boolean again;
        final String log;

        Outcome(boolean noted, boolean exhausted, @Nullable Variant next, boolean again, String log) {
            this.noted = noted;
            this.exhausted = exhausted;
            this.next = next;
            this.again = again;
            this.log = log;
        }
    }

    private static int index(Variant[] order, Variant v) {
        for (int i = 0; i < order.length; i++) if (order[i] == v) return i;
        return -1;
    }

    /** The scam_dev variant (1 NATIVE, 2 NATIVE_RAW, 3 NATIVE_KEY, 4 HAL, 5 KEY, 6 BASE), null for none or another value. */
    @Nullable
    static Variant devVariant(float dev) {
        if (Float.isNaN(dev) || dev != Math.rint(dev) || dev < 1 || dev > Variant.values().length) return null;
        return Variant.values()[(int) dev - 1];
    }

    private static boolean current(SharedPreferences p, String key, String stamp) {
        try {
            return stamp.equals(p.getString(key + STAMP, null));
        } catch (ClassCastException e) {
            return false;
        }
    }

    /**
     * The variant of this camera's size: scam_dev's ({@code dev}; a stock-key variant only where the order has them), else the
     * one the memory holds for this firmware ({@code fingerprint}), else the first of {@link #order}.
     */
    static Choice choose(@Nullable SharedPreferences p, @Nullable VivoNativeFullRes.Phone phone, boolean recipe, @Nullable String cameraId,
                         int width, int height, @Nullable String fingerprint, float dev) {
        final Variant[] order = order(phone, recipe);
        final Variant held = devVariant(dev);
        if (held != null && (!held.recipe || order[0].recipe))
            return new Choice(held, -1, order, "scam_dev vivo_fullres200_variant " + (int) dev);
        final String first = "first try on this firmware" + (order[0].recipe ? " (X300 Ultra: the HAL's remosaic, P699)" : "");
        if (p == null || cameraId == null) return new Choice(order[0], 0, order, first);
        try {
            final String key = key(cameraId, width, height);
            if (!current(p, key, stamp(fingerprint))) return new Choice(order[0], 0, order, first);
            final int step = p.getInt(key + STEP, 0);
            final String why = p.getString(key + WHY, null);
            if (step < 0 || step >= order.length)
                return new Choice(order[0], 0, order, "every variant failed before on this firmware, from the first again");
            final boolean ok = p.getBoolean(key + OK, false);
            // P699: a kept variant that delivered the HAL's Bayer; a variant noted after another's Bayer looks for the native Tetra
            final boolean bayer = p.getInt(key + BAYER, -1) == step;
            if (bayer && ok) return new Choice(order[step], step, order, "it delivered the HAL's remosaicked Bayer (block 1) before on this"
                    + " firmware, no native variant gave the Tetra: kept", true);
            if (bayer) return new Choice(order[step], step, order, "it delivered the HAL's remosaicked Bayer (block 1) before on this"
                    + " firmware, once more after a failed series (last: " + why + ")", true);
            return new Choice(order[step], step, order, ok ? "it completed a series before on this firmware"
                    : why == null ? first : why.startsWith(BAYER_WHY) ? "noted after a series of " + why.substring(BAYER_WHY.length())
                    + " gave the HAL's remosaicked Bayer on this firmware (this one tries for the native Tetra)"
                    : "noted after a failed series on this firmware (last: " + why + ")");
        } catch (RuntimeException e) {
            return new Choice(order[0], 0, order, first);
        }
    }

    /**
     * A failure that is the variant's verdict: a buffer lost, a failed or aborted still, a timeout, a camera error, a still not in
     * the full-size mode or in another sensor mode, a binned corner, a mosaic other than the variant's. Not: the camera closed or
     * taken away under the series (the app left, a switch, another client), a scene that shows no mosaic at all (no verdict).
     */
    static boolean counts(@Nullable String why) {
        if (why == null) return true;
        if (why.startsWith("camera closed") || why.startsWith("camera disconnected")) return false;
        return !why.contains("not been verified");
    }

    /**
     * A series of {@code choice} failed with {@code why}: the camera's size moves on to the next variant (a variant that had
     * completed a series before gets one more press first), or every variant has failed. Nothing is noted for scam_dev's
     * variant or a failure that is no verdict on the variant ({@link #counts}).
     */
    static Outcome failed(@Nullable SharedPreferences p, String cameraId, VivoSdkFullRes.Profile profile, @Nullable String fingerprint,
                          Choice choice, @Nullable String why) {
        final String head = "200 MP still-only camera " + cameraId + " " + profile.size + ": variant " + choice.variant + " failed ("
                + why + ")";
        if (choice.dev()) return new Outcome(false, false, null, false, head + "; held by scam_dev vivo_fullres200_variant, nothing noted");
        if (!counts(why)) return new Outcome(false, false, null, false, head + "; no verdict on the variant, nothing noted");
        final Variant[] order = choice.order;
        final int at = Math.max(0, index(order, choice.variant));
        final String text = why == null ? "unknown" : why.length() > 120 ? why.substring(0, 120) : why;
        boolean wasOk = false;
        int next = at + 1, bayer = -1;
        if (p != null) try {
            final String key = key(cameraId, profile);
            final boolean now = current(p, key, stamp(fingerprint));
            wasOk = now && p.getBoolean(key + OK, false) && p.getInt(key + STEP, -1) == at;
            bayer = now ? p.getInt(key + BAYER, -1) : -1;
            if (wasOk) next = at; // it completed a series before: once more before the next variant
            // P699: no native variant left (the HAL recipe next, or none): back to the one that delivered the HAL's Bayer
            final boolean back = !wasOk && bayer >= 0 && bayer < order.length && bayer != at
                    && (next >= order.length || order[next].halRemosaic);
            if (back) next = bayer;
            else bayer = -1;
            p.edit().putString(key + STAMP, stamp(fingerprint)).putInt(key + STEP, next).putBoolean(key + OK, back)
                    .putString(key + WHY, text).apply();
        } catch (RuntimeException ignored) {
        }
        if (wasOk) return new Outcome(true, false, choice.variant, true, head + "; it completed a series before on this firmware: the next press"
                + " tries it once more");
        if (bayer >= 0) return new Outcome(true, false, order[bayer], true, head + "; the next press goes back to " + order[bayer]
                + ", which delivered the HAL's remosaicked Bayer (block 1) on this firmware");
        if (next >= order.length)
            return new Outcome(true, true, null, false, head + "; every variant (" + order.length + ") failed on this firmware: the 200 MP row"
                    + " is noted failed");
        return new Outcome(true, false, order[next], false, head + "; the next press tries " + order[next] + " (" + order[next].words
                + "), step " + (next + 1) + " of " + order.length);
    }

    /** A series of {@code choice} completed: its variant is kept for this camera's size on this firmware. The log line, null if known. */
    @Nullable
    static String succeeded(@Nullable SharedPreferences p, String cameraId, VivoSdkFullRes.Profile profile, @Nullable String fingerprint,
                            Choice choice) {
        if (choice.dev() || p == null) return null;
        try {
            final String key = key(cameraId, profile);
            final int at = Math.max(0, index(choice.order, choice.variant));
            if (current(p, key, stamp(fingerprint)) && p.getBoolean(key + OK, false) && p.getInt(key + STEP, -1) == at) return null;
            p.edit().putString(key + STAMP, stamp(fingerprint)).putInt(key + STEP, at).putBoolean(key + OK, true)
                    .remove(key + WHY).remove(key + BAYER).apply();
            return "200 MP still-only camera " + cameraId + " " + profile.size + ": variant " + choice.variant + " (" + choice.variant.words
                    + ") completed a series, kept on this firmware";
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * P699: a series of {@code choice} completed with the HAL's remosaicked Bayer stills (block 1). The HAL variant's own Bayer is
     * a plain success ({@link #succeeded}). A native variant's is noted: the next press tries the next native variant for the
     * sensor's Tetra; with none left, the first native variant that gave the Bayer on this firmware is kept as the Bayer. The log
     * line (null when nothing changed); {@code next} of the outcome is the variant of the next press.
     */
    static Outcome bayer(@Nullable SharedPreferences p, String cameraId, VivoSdkFullRes.Profile profile, @Nullable String fingerprint,
                         Choice choice) {
        final String head = "200 MP still-only camera " + cameraId + " " + profile.size + ": variant " + choice.variant
                + " delivered the HAL's remosaicked Bayer (block 1), merged as Bayer";
        if (choice.variant.halRemosaic) {
            final String kept = succeeded(p, cameraId, profile, fingerprint, choice);
            return new Outcome(kept != null, false, choice.variant, false, kept);
        }
        if (choice.dev()) return new Outcome(false, false, null, false, head + "; held by scam_dev vivo_fullres200_variant, nothing noted");
        if (p == null) return new Outcome(false, false, null, false, head);
        final Variant[] order = choice.order;
        final int at = Math.max(0, index(order, choice.variant));
        try {
            final String key = key(cameraId, profile);
            final boolean now = current(p, key, stamp(fingerprint));
            final int noted = now ? p.getInt(key + BAYER, -1) : -1;
            final int first = noted >= 0 && noted < order.length && !order[noted].halRemosaic ? noted : at;
            if (now && p.getBoolean(key + OK, false) && p.getInt(key + STEP, -1) == at && noted == at)
                return new Outcome(false, false, choice.variant, false, null); // the kept Bayer variant again
            final int next = at + 1;
            if (next < order.length && !order[next].halRemosaic && next != first) {
                p.edit().putString(key + STAMP, stamp(fingerprint)).putInt(key + STEP, next).putBoolean(key + OK, false)
                        .putInt(key + BAYER, first).putString(key + WHY, BAYER_WHY + choice.variant).apply();
                return new Outcome(true, false, order[next], false, head + "; the next press tries " + order[next] + " ("
                        + order[next].words + ") for the native Tetra, step " + (next + 1) + " of " + order.length);
            }
            p.edit().putString(key + STAMP, stamp(fingerprint)).putInt(key + STEP, first).putBoolean(key + OK, true)
                    .putInt(key + BAYER, first).remove(key + WHY).apply();
            return new Outcome(true, true, order[first], false, head + "; no native variant gave the Tetra on this firmware: "
                    + order[first] + " kept as the HAL's Bayer");
        } catch (RuntimeException e) {
            return new Outcome(false, false, null, false, head);
        }
    }

    /** scam_dev «vivo_x300_fullsize» (on by default): the X300 Ultra's stock full-size keys are tried. */
    static boolean recipeSwitch() {
        return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vivo_x300_fullsize", true);
    }

    /** {@link #choose} for this phone and firmware, scam_dev and the app's preferences. */
    static Choice choose(@Nullable String cameraId, int width, int height) {
        return choose(prefs(), VivoNativeFullRes.phone(), recipeSwitch(), cameraId, width, height, android.os.Build.FINGERPRINT,
                com.scamera.app.settings.PreferenceKeys.scamDevNumber("vivo_fullres200_variant", 0));
    }

    static Choice choose(String cameraId, VivoSdkFullRes.Profile profile) {
        return choose(cameraId, profile.size.getWidth(), profile.size.getHeight());
    }

    /** The camera's 200 MP still-only size runs the HAL's remosaic now (its profile a Bayer, block 1). */
    static boolean halBayer(@Nullable String cameraId, int width, int height) {
        try {
            return cameraId != null && choose(cameraId, width, height).variant.halRemosaic;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** {@link #failed} in the app's preferences on this firmware. */
    static Outcome failed(String cameraId, VivoSdkFullRes.Profile profile, Choice choice, @Nullable String why) {
        return failed(prefs(), cameraId, profile, android.os.Build.FINGERPRINT, choice, why);
    }

    /** {@link #bayer} in the app's preferences on this firmware. */
    static Outcome bayer(String cameraId, VivoSdkFullRes.Profile profile, Choice choice) {
        return bayer(prefs(), cameraId, profile, android.os.Build.FINGERPRINT, choice);
    }

    /** {@link #succeeded} in the app's preferences on this firmware. */
    @Nullable
    static String succeeded(String cameraId, VivoSdkFullRes.Profile profile, Choice choice) {
        return succeeded(prefs(), cameraId, profile, android.os.Build.FINGERPRINT, choice);
    }

    @Nullable
    private static SharedPreferences prefs() {
        try {
            return com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * P699: the owner's message after a series of a native variant that gave the HAL's Bayer ({@link #bayer}); empty when nothing is
     * to say (the kept Bayer variant again, the HAL variant).
     */
    static String ownerBayer(Outcome outcome) {
        if (!outcome.noted || outcome.next == null || outcome.next.halRemosaic) return "";
        if (outcome.exhausted) return Lang.t("200 Мп: камера отдаёт только свой ремозаик (Байер) - снимки 200 Мп обрабатываются как Байер",
                "200 MP: the camera gives only its own remosaic (Bayer) - 200 MP photos are processed as Bayer");
        return Lang.t("200 Мп: снимок сохранён как Байер (ремозаик камеры); следующее нажатие попробует родную мозаику - " + outcome.next.ownerWords(),
                "200 MP: the photo is saved as Bayer (the camera's remosaic); the next press tries the native mosaic - " + outcome.next.ownerWords());
    }

    /** The owner's message after a failed series whose next press tries another variant. */
    static String ownerNext(Outcome outcome) {
        final Variant v = outcome.next;
        if (v == null) return "";
        if (outcome.again) return Lang.t("200 Мп: снимок не вышел; следующее нажатие повторит способ, который уже работал",
                "200 MP: the shot failed; the next press tries the way that worked before once more");
        return Lang.t("200 Мп: снимок не вышел; следующее нажатие попробует другой способ — " + v.ownerWords(),
                "200 MP: the shot failed; the next press tries another way - " + v.ownerWords());
    }
}
