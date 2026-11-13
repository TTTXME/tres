package com.scamera.app.processing.watermark;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.scamera.app.settings.PreferenceNumber;
import com.scamera.app.settings.PreferenceValue;
import com.scamera.app.settings.SettingsNumericRules;

import java.util.Locale;

/**
 * P628 (owner, 2026-11-11: «Добавь нормальную кастомизацию для вотермарки … перерисуй. Так же убери по дефолту надпись "Shot on"»;
 * «Я должен иметь возможность для вотермарки например выводить только дату и время»): the watermark's settings as one value - its
 * layout, where it sits, which fields it shows (each on its own: any subset, a single one included, none forced), the custom text,
 * the font, size, colour, opacity, margin and the camera glyph. Read from the settings ({@link #from}) by the shot and by the
 * settings page's preview, so both draw the same thing. The keys are global (ModuleProfiles: the «pref_watermark_» prefix).
 */
public final class WatermarkSpec {
    public static final String KEY_ON = "pref_show_watermark_key";
    public static final String KEY_LAYOUT = "pref_watermark_layout", KEY_POSITION = "pref_watermark_position",
            KEY_SHOW_TEXT = "pref_watermark_show_text", KEY_TEXT = "pref_watermark_text", KEY_SHOW_DEVICE = "pref_watermark_show_device",
            KEY_SHOW_LENS = "pref_watermark_show_lens", KEY_SHOW_APERTURE = "pref_watermark_show_aperture",
            KEY_SHOW_SHUTTER = "pref_watermark_show_shutter", KEY_SHOW_ISO = "pref_watermark_show_iso",
            KEY_SHOW_TIME = "pref_watermark_show_time", KEY_DATE_FORMAT = "pref_watermark_date_format", KEY_LOGO = "pref_watermark_logo",
            KEY_FONT = "pref_watermark_font", KEY_SIZE = "pref_watermark_size", KEY_COLOR = "pref_watermark_color",
            KEY_OPACITY = "pref_watermark_opacity", KEY_MARGIN = "pref_watermark_margin";

    /** Every key whose change redraws the settings page's preview. */
    public static final String[] KEYS = {KEY_ON, KEY_LAYOUT, KEY_POSITION, KEY_SHOW_TEXT, KEY_TEXT, KEY_SHOW_DEVICE, KEY_SHOW_LENS,
            KEY_SHOW_APERTURE, KEY_SHOW_SHUTTER, KEY_SHOW_ISO, KEY_SHOW_TIME, KEY_DATE_FORMAT, KEY_LOGO, KEY_FONT, KEY_SIZE, KEY_COLOR,
            KEY_OPACITY, KEY_MARGIN};

    /** «Подпись» (text and glyph, no plate), «Плашка» (the shade's glass pill), «Полоса» (a band across the edge), «Рамка». */
    public enum Layout { TEXT, BADGE, BAR, FRAME }

    /** The corner or edge the watermark sits on, as the photo is viewed (upright). */
    public enum Position {
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT, TOP_LEFT, TOP_CENTER, TOP_RIGHT;

        public boolean top() { return this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT; }
        public boolean right() { return this == BOTTOM_RIGHT || this == TOP_RIGHT; }
        public boolean center() { return this == BOTTOM_CENTER || this == TOP_CENTER; }
    }

    /** The app's font (Roboto Flex), a condensed sans, a serif, a monospace. */
    public enum Font { SCAM, CONDENSED, SERIF, MONO }

    /** The text colour: chosen by the photo under the watermark, white, black (a plate / frame takes the opposite). */
    public enum Colour { AUTO, WHITE, BLACK }

    /** Date and time, the date, the time, ISO 8601, the month as a word. */
    public enum DateFormat { DATE_TIME, DATE, TIME, ISO, TEXT }

    /** The default custom text: the app's name (the former default's second line, without «SHOT ON»). */
    public static final String DEFAULT_TEXT = "SCAMERA";
    /** The longest custom text drawn (characters); the rest is cut, then the line is shortened to fit its width. */
    public static final int MAX_TEXT = 64;
    public static final int DEFAULT_SIZE = 7, DEFAULT_OPACITY = 100, DEFAULT_MARGIN = 3;

    public Layout layout = Layout.TEXT;
    public Position position = Position.BOTTOM_LEFT;
    public boolean showText = true, showDevice, showLens, showAperture, showShutter, showIso, showTime, logo = true;
    public String text = DEFAULT_TEXT;
    public DateFormat dateFormat = DateFormat.DATE_TIME;
    public Font font = Font.SCAM;
    public Colour colour = Colour.AUTO;
    /** The title's text height as a share of the photo's short side: size / 100 * {@link #TITLE_SHARE}. */
    public float size = DEFAULT_SIZE;
    /** 0..1. */
    public float opacity = DEFAULT_OPACITY / 100f;
    /** Percent of the short side between the watermark and the photo's edges (the frame's border). */
    public float margin = DEFAULT_MARGIN;

    /** The title's height per size step: the default 7 gives 2.8 % of the short side (the former signature's line). */
    public static final float TITLE_SHARE = .4f;

    public WatermarkSpec() {}

    public WatermarkSpec copy() {
        WatermarkSpec s = new WatermarkSpec();
        s.layout = layout; s.position = position; s.showText = showText; s.showDevice = showDevice; s.showLens = showLens;
        s.showAperture = showAperture; s.showShutter = showShutter; s.showIso = showIso; s.showTime = showTime; s.logo = logo;
        s.text = text; s.dateFormat = dateFormat; s.font = font; s.colour = colour; s.size = size; s.opacity = opacity; s.margin = margin;
        return s;
    }

    /** Only the fields named on (everything else off), for tests and the preview of a subset. */
    public WatermarkSpec only(boolean text, boolean device, boolean lens, boolean aperture, boolean shutter, boolean iso, boolean time,
                              boolean logo) {
        WatermarkSpec s = copy();
        s.showText = text; s.showDevice = device; s.showLens = lens; s.showAperture = aperture; s.showShutter = shutter; s.showIso = iso;
        s.showTime = time; s.logo = logo;
        return s;
    }

    /** The stored settings ({@code prefs} null: the defaults). Unknown values fall back to the defaults. */
    public static WatermarkSpec from(@Nullable SharedPreferences prefs) {
        WatermarkSpec s = new WatermarkSpec();
        if (prefs == null) return s;
        s.layout = layout(str(prefs, KEY_LAYOUT, "text"));
        s.position = position(str(prefs, KEY_POSITION, "bl"));
        s.showText = bool(prefs, KEY_SHOW_TEXT, true);
        s.text = str(prefs, KEY_TEXT, DEFAULT_TEXT);
        s.showDevice = bool(prefs, KEY_SHOW_DEVICE, false);
        s.showLens = bool(prefs, KEY_SHOW_LENS, false);
        s.showAperture = bool(prefs, KEY_SHOW_APERTURE, false);
        s.showShutter = bool(prefs, KEY_SHOW_SHUTTER, false);
        s.showIso = bool(prefs, KEY_SHOW_ISO, false);
        s.showTime = bool(prefs, KEY_SHOW_TIME, false);
        s.dateFormat = dateFormat(str(prefs, KEY_DATE_FORMAT, "datetime"));
        s.logo = bool(prefs, KEY_LOGO, true);
        s.font = font(str(prefs, KEY_FONT, "scam"));
        s.colour = colour(str(prefs, KEY_COLOR, "auto"));
        s.size = (float) SettingsNumericRules.value(KEY_SIZE, PreferenceValue.get(prefs, KEY_SIZE), DEFAULT_SIZE);
        s.opacity = (float) SettingsNumericRules.value(KEY_OPACITY, PreferenceValue.get(prefs, KEY_OPACITY), DEFAULT_OPACITY) / 100f;
        s.margin = (float) SettingsNumericRules.value(KEY_MARGIN, PreferenceValue.get(prefs, KEY_MARGIN), DEFAULT_MARGIN);
        return s;
    }

    // ───── P633: the spec as JSON (the photo's stored watermark, the editor's state) ─────

    /** The layout's stored value («text», «badge», «bar», «frame»: the settings' values). */
    public static String code(Layout l) {
        switch (l) {
            case BADGE: return "badge";
            case BAR: return "bar";
            case FRAME: return "frame";
            default: return "text";
        }
    }

    public static String code(Position p) {
        switch (p) {
            case BOTTOM_CENTER: return "bc";
            case BOTTOM_RIGHT: return "br";
            case TOP_LEFT: return "tl";
            case TOP_CENTER: return "tc";
            case TOP_RIGHT: return "tr";
            default: return "bl";
        }
    }

    public static String code(Font f) {
        switch (f) {
            case CONDENSED: return "condensed";
            case SERIF: return "serif";
            case MONO: return "mono";
            default: return "scam";
        }
    }

    public static String code(Colour c) {
        switch (c) {
            case WHITE: return "white";
            case BLACK: return "black";
            default: return "auto";
        }
    }

    public static String code(DateFormat d) {
        switch (d) {
            case DATE: return "date";
            case TIME: return "time";
            case ISO: return "iso";
            case TEXT: return "text";
            default: return "datetime";
        }
    }

    /**
     * P633: every field of the spec as one JSON object with the settings' stored values (keys in a fixed order, so equal specs give
     * equal text): what a photo shot with the watermark keeps of it (WatermarkPatch) and what the editor's state holds.
     */
    public String toJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("layout", code(layout));
            o.put("position", code(position));
            o.put("showText", showText);
            o.put("text", text == null ? "" : text);
            o.put("device", showDevice);
            o.put("lens", showLens);
            o.put("aperture", showAperture);
            o.put("shutter", showShutter);
            o.put("iso", showIso);
            o.put("time", showTime);
            o.put("dateFormat", code(dateFormat));
            o.put("logo", logo);
            o.put("font", code(font));
            o.put("colour", code(colour));
            o.put("size", (double) size);
            o.put("opacity", (double) opacity);
            o.put("margin", (double) margin);
            return o.toString();
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The spec of {@link #toJson} (unknown values: the defaults, numbers clamped to the settings' ranges); null for no JSON object. */
    @Nullable
    public static WatermarkSpec fromJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            WatermarkSpec s = new WatermarkSpec();
            s.layout = layout(o.optString("layout", "text"));
            s.position = position(o.optString("position", "bl"));
            s.showText = o.optBoolean("showText", true);
            s.text = o.optString("text", DEFAULT_TEXT);
            if (s.text.length() > 4 * MAX_TEXT) s.text = s.text.substring(0, 4 * MAX_TEXT);
            s.showDevice = o.optBoolean("device", false);
            s.showLens = o.optBoolean("lens", false);
            s.showAperture = o.optBoolean("aperture", false);
            s.showShutter = o.optBoolean("shutter", false);
            s.showIso = o.optBoolean("iso", false);
            s.showTime = o.optBoolean("time", false);
            s.dateFormat = dateFormat(o.optString("dateFormat", "datetime"));
            s.logo = o.optBoolean("logo", true);
            s.font = font(o.optString("font", "scam"));
            s.colour = colour(o.optString("colour", "auto"));
            s.size = clamp((float) o.optDouble("size", DEFAULT_SIZE), MIN_SIZE, MAX_SIZE, DEFAULT_SIZE);
            s.opacity = clamp((float) o.optDouble("opacity", DEFAULT_OPACITY / 100f), MIN_OPACITY / 100f, 1f, DEFAULT_OPACITY / 100f);
            s.margin = clamp((float) o.optDouble("margin", DEFAULT_MARGIN), 0f, MAX_MARGIN, DEFAULT_MARGIN);
            return s;
        } catch (org.json.JSONException | RuntimeException e) {
            return null;
        }
    }

    /** The settings' ranges (preferences.xml: size 3..20, opacity 10..100, margin 0..10). */
    public static final int MIN_SIZE = 3, MAX_SIZE = 20, MIN_OPACITY = 10, MAX_MARGIN = 10;

    private static float clamp(float v, float lo, float hi, float fallback) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return fallback;
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WatermarkSpec && toJson().equals(((WatermarkSpec) other).toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }

    private static String str(SharedPreferences prefs, String key, String fallback) {
        Object v = PreferenceValue.get(prefs, key);
        return v == null ? fallback : v.toString();
    }

    private static boolean bool(SharedPreferences prefs, String key, boolean fallback) {
        return PreferenceNumber.bool(PreferenceValue.get(prefs, key), fallback);
    }

    static Layout layout(String v) {
        switch (lower(v)) {
            case "badge": return Layout.BADGE;
            case "bar": return Layout.BAR;
            case "frame": return Layout.FRAME;
            default: return Layout.TEXT;
        }
    }

    static Position position(String v) {
        switch (lower(v)) {
            case "bc": return Position.BOTTOM_CENTER;
            case "br": return Position.BOTTOM_RIGHT;
            case "tl": return Position.TOP_LEFT;
            case "tc": return Position.TOP_CENTER;
            case "tr": return Position.TOP_RIGHT;
            default: return Position.BOTTOM_LEFT;
        }
    }

    static Font font(String v) {
        switch (lower(v)) {
            case "condensed": return Font.CONDENSED;
            case "serif": return Font.SERIF;
            case "mono": return Font.MONO;
            default: return Font.SCAM;
        }
    }

    static Colour colour(String v) {
        switch (lower(v)) {
            case "white": return Colour.WHITE;
            case "black": return Colour.BLACK;
            default: return Colour.AUTO;
        }
    }

    static DateFormat dateFormat(String v) {
        switch (lower(v)) {
            case "date": return DateFormat.DATE;
            case "time": return DateFormat.TIME;
            case "iso": return DateFormat.ISO;
            case "text": return DateFormat.TEXT;
            default: return DateFormat.DATE_TIME;
        }
    }

    private static String lower(String v) {
        return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    }
}
