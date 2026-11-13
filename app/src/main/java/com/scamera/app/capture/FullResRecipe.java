package com.scamera.app.capture;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P635 (owner, 2026-11-12: «Изучи, этим ты возможно сможешь починить видоискатель на 17U, Нубии и 1+16. Реализуй как переключатель
 * в настройках линзы»; his two documents «Full-Res RAW 50 MP - Full Recipe» and «09 - Hasil probe ulang», a device-proven way to
 * the real 50 MP RAW of a phone whose Camera2 lists the binned size only): a full-resolution recipe - the profile of one lens.
 * <p>
 * The documents' finding: the full-size RAW stream alone delivers a frame of the full size that is the binned frame written twice
 * over (every 2x2 box one value); the sensor's real full readout comes only when the still request also targets a small companion
 * stream, is built from the still template, the RAW output carries a vendor stream use case, and two vendor tags sit on the session
 * and on every request. The viewfinder keeps its own stream (the binned RAW or the ISP one) beside it.
 * <p>
 * Nothing of a phone lives in the engine (the documents insist: the use case, the sizes, the usage, the tags are profile fields):
 * this class is the fields, their text form (one preference a field, {@link #fields} / {@link #overlay}) and their checks.
 * The built-in profiles: FullResRecipePresets; the session and the requests of a profile: FullResRecipePlan.
 */
public final class FullResRecipe {
    /** The companion stream a still targets beside the RAW. */
    public enum Companion { OFF, YUV, VIEWFINDER }

    /** SENSOR_PIXEL_MODE: never set (a HAL whose full size is no maximum-resolution stream), or the capture streams annotated. */
    public enum PixelMode { NEVER, ANNOTATE }

    /** The viewfinder beside the capture RAW: the ISP stream, the camera's own (binned) RAW stream, or both. */
    public enum Preview { ISP, RAW, BOTH }

    /** Where a vendor tag goes: the session parameters, every request, both, or the stills only. */
    public enum Target { SESSION, REQUEST, BOTH, STILL }

    /** The Camera2 value type of a vendor tag. */
    public enum TagType {
        I32("i32", false), I32_ARRAY("i32[]", true), U8("u8", false), U8_ARRAY("u8[]", true), I64("i64", false), F32("f32", false);

        public final String text;
        public final boolean array;

        TagType(String text, boolean array) {
            this.text = text;
            this.array = array;
        }

        @Nullable
        static TagType of(String text) {
            final String t = text.trim().toLowerCase(Locale.ROOT);
            for (TagType v : values()) if (v.text.equals(t)) return v;
            return null;
        }
    }

    /** One vendor tag of a profile. */
    public static final class Tag {
        public final String key;
        public final TagType type;
        /** The value(s): one for a scalar type. */
        public final double[] values;
        public final Target target;

        public Tag(String key, TagType type, Target target, double... values) {
            this.key = key;
            this.type = type;
            this.target = target;
            this.values = values.clone();
        }

        public boolean onSession() {
            return target == Target.SESSION || target == Target.BOTH;
        }

        /** On a request: {@code still} a shot's frame, else the repeating one. */
        public boolean onRequest(boolean still) {
            return target == Target.REQUEST || target == Target.BOTH || still && target == Target.STILL;
        }

        /** "key=i32[]:0@both". */
        @Override public String toString() {
            final StringBuilder sb = new StringBuilder(key).append('=').append(type.text).append(':');
            for (int i = 0; i < values.length; i++) sb.append(i == 0 ? "" : ",").append(number(values[i]));
            return sb.append('@').append(target.name().toLowerCase(Locale.ROOT)).toString();
        }

        @Nullable
        static Tag parse(String line) {
            final int eq = line.indexOf('='), colon = line.indexOf(':', eq + 1), at = line.lastIndexOf('@');
            if (eq <= 0 || colon <= eq) return null;
            final String key = line.substring(0, eq).trim();
            final TagType type = TagType.of(line.substring(eq + 1, colon));
            final String value = (at > colon ? line.substring(colon + 1, at) : line.substring(colon + 1)).trim();
            Target target = Target.BOTH;
            if (at > colon) {
                try {
                    target = Target.valueOf(line.substring(at + 1).trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    return null;
                }
            }
            if (key.isEmpty() || key.indexOf(' ') >= 0 || type == null || value.isEmpty()) return null;
            final String[] parts = value.split(",");
            if (!type.array && parts.length != 1) return null;
            final double[] values = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                final Double v = type == TagType.F32 ? decimal(parts[i]) : whole(parts[i]);
                if (v == null) return null;
                values[i] = v;
            }
            return new Tag(key, type, target, values);
        }
    }

    // ---------------------------------------------------------------- the fields

    /** The field names (the suffix of a module's preference key, SessionKeys splits at the last '_': no '_' inside). */
    public static final String ON = "recipe", SIZE = "recipesize", USAGE = "recipeusage", USE_CASE = "recipeusecase",
            COMPANION = "recipecompanion", TAGS = "recipetags", TEMPLATE = "recipetemplate", SESSION = "recipesession",
            FPS = "recipefps", PIXEL_MODE = "recipepixelmode", PREVIEW = "recipepreview", FRAMES = "recipeframes",
            OP_MODE = "recipeopmode", VIEW_TAGS = "recipeviewtags", VIEW_COMPANION = "recipeviewcompanion";
    /** The editable fields, in the page's order. */
    public static final List<String> FIELDS = Collections.unmodifiableList(java.util.Arrays.asList(SIZE, USAGE, USE_CASE, COMPANION,
            VIEW_COMPANION, TAGS, VIEW_TAGS, TEMPLATE, SESSION, FPS, PIXEL_MODE, PREVIEW, FRAMES, OP_MODE));

    /** The templates a field may name, as CameraDevice.TEMPLATE_*. */
    public static final String[] TEMPLATES = {"preview", "still", "record", "snapshot", "zsl", "manual"};
    /** The most frames a shot takes (the 50 MP shot arena: 8 slots). */
    public static final int MAX_FRAMES = 8;

    /** The profile's name for the log and the page ("" for a hand-made one). */
    public final String id;
    /** The capture RAW's size (also one the stream map does not list). */
    public final int width, height;
    /** The RAW reader's usage bits (0: the reader's default). */
    public final long usage;
    /** The vendor stream use case of the capture RAW output (0: none; needs API 33). */
    public final long useCase;
    public final Companion companion;
    /** The companion's size (YUV_420_888); 0 x 0: the smallest size the camera lists of at least 640 x 480. */
    public final int companionWidth, companionHeight;
    public final List<Tag> tags;
    /** CameraDevice.TEMPLATE_* of a still and of the session parameters (index into {@link #TEMPLATES} + 1). */
    public final int stillTemplate, sessionTemplate;
    /** CONTROL_AE_TARGET_FPS_RANGE of the session and the stills; 0: left as the app has it. */
    public final int fpsLow, fpsHigh;
    public final PixelMode pixelMode;
    public final Preview preview;
    /** Full-size frames a shot takes. */
    public final int frames;
    /** The session's operation mode (0: regular). */
    public final int operationMode;
    /** Not proven on a phone: said on the page and in the log. */
    public final boolean experimental;
    /**
     * P635c: the repeating (viewfinder) request carries the request tags too (the owner's documents ran it so). Off: the session
     * parameters and the stills alone carry them - a viewfinder whose camera then keeps its own mode (its autofocus, its levels).
     */
    public final boolean viewTags;
    /**
     * P653: the repeating (viewfinder) request targets the YUV companion too, not the stills alone (the seamless 50 MP capture
     * run on the Xiaomi 17 Ultra kept it on every request of a use-case lens); its frames are dropped at once.
     */
    public final boolean viewCompanion;

    private FullResRecipe(Builder b) {
        id = b.id;
        width = b.width;
        height = b.height;
        usage = b.usage;
        useCase = b.useCase;
        companion = b.companion;
        companionWidth = b.companionWidth;
        companionHeight = b.companionHeight;
        tags = Collections.unmodifiableList(new ArrayList<>(b.tags));
        stillTemplate = b.stillTemplate;
        sessionTemplate = b.sessionTemplate;
        fpsLow = b.fpsLow;
        fpsHigh = b.fpsHigh;
        pixelMode = b.pixelMode;
        preview = b.preview;
        frames = b.frames;
        operationMode = b.operationMode;
        experimental = b.experimental;
        viewTags = b.viewTags;
        viewCompanion = b.viewCompanion;
    }

    /** A profile under construction: the engine's neutral start (no size, no use case, no tag: nothing of a phone). */
    public static final class Builder {
        String id = "";
        int width, height;
        long usage, useCase;
        Companion companion = Companion.OFF;
        int companionWidth, companionHeight;
        final List<Tag> tags = new ArrayList<>();
        int stillTemplate = template("still"), sessionTemplate = template("preview");
        int fpsLow, fpsHigh;
        PixelMode pixelMode = PixelMode.NEVER;
        Preview preview = Preview.ISP;
        int frames = 1;
        int operationMode;
        boolean experimental;
        boolean viewTags = true;
        boolean viewCompanion;

        public Builder id(String v) { id = v == null ? "" : v; return this; }
        public Builder size(int w, int h) { width = w; height = h; return this; }
        public Builder usage(long v) { usage = v; return this; }
        public Builder useCase(long v) { useCase = v; return this; }
        public Builder companion(Companion c, int w, int h) { companion = c; companionWidth = w; companionHeight = h; return this; }
        public Builder tag(String key, TagType type, Target target, double... values) { tags.add(new Tag(key, type, target, values)); return this; }
        public Builder stillTemplate(String name) { stillTemplate = template(name); return this; }
        public Builder sessionTemplate(String name) { sessionTemplate = template(name); return this; }
        public Builder fps(int low, int high) { fpsLow = low; fpsHigh = high; return this; }
        public Builder pixelMode(PixelMode v) { pixelMode = v; return this; }
        public Builder preview(Preview v) { preview = v; return this; }
        public Builder frames(int v) { frames = Math.max(1, Math.min(MAX_FRAMES, v)); return this; }
        public Builder operationMode(int v) { operationMode = v; return this; }
        public Builder experimental(boolean v) { experimental = v; return this; }
        public Builder viewTags(boolean v) { viewTags = v; return this; }
        public Builder viewCompanion(boolean v) { viewCompanion = v; return this; }
        public FullResRecipe build() { return new FullResRecipe(this); }
    }

    public Builder edit() {
        final Builder b = new Builder().id(id).size(width, height).usage(usage).useCase(useCase)
                .companion(companion, companionWidth, companionHeight).fps(fpsLow, fpsHigh).pixelMode(pixelMode).preview(preview)
                .frames(frames).operationMode(operationMode).experimental(experimental).viewTags(viewTags).viewCompanion(viewCompanion);
        b.stillTemplate = stillTemplate;
        b.sessionTemplate = sessionTemplate;
        b.tags.addAll(tags);
        return b;
    }

    /** CameraDevice.TEMPLATE_* of a name of {@link #TEMPLATES}; 0 for none. */
    public static int template(@Nullable String name) {
        final String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < TEMPLATES.length; i++) if (TEMPLATES[i].equals(n)) return i + 1;
        return 0;
    }

    public static String templateName(int template) {
        return template >= 1 && template <= TEMPLATES.length ? TEMPLATES[template - 1] : "";
    }

    // ---------------------------------------------------------------- the text form

    /** The profile as its fields' texts ({@link #FIELDS}), what the page shows and stores. */
    public Map<String, String> fields() {
        final Map<String, String> out = new LinkedHashMap<>();
        out.put(SIZE, width > 0 && height > 0 ? width + "x" + height : "");
        out.put(USAGE, usage == 0 ? "0" : "0x" + Long.toHexString(usage));
        out.put(USE_CASE, useCase == 0 ? "0" : "0x" + Long.toHexString(useCase));
        out.put(COMPANION, companion == Companion.OFF ? "off" : companion == Companion.VIEWFINDER ? "viewfinder"
                : companionWidth > 0 && companionHeight > 0 ? companionWidth + "x" + companionHeight : "auto");
        out.put(VIEW_COMPANION, viewCompanion ? "yes" : "no");
        final StringBuilder t = new StringBuilder();
        for (Tag tag : tags) t.append(t.length() == 0 ? "" : "\n").append(tag);
        out.put(TAGS, t.toString());
        out.put(VIEW_TAGS, viewTags ? "yes" : "no");
        out.put(TEMPLATE, templateName(stillTemplate));
        out.put(SESSION, templateName(sessionTemplate));
        out.put(FPS, fpsLow > 0 && fpsHigh >= fpsLow ? fpsLow + "-" + fpsHigh : "");
        out.put(PIXEL_MODE, pixelMode.name().toLowerCase(Locale.ROOT));
        out.put(PREVIEW, preview.name().toLowerCase(Locale.ROOT));
        out.put(FRAMES, String.valueOf(frames));
        out.put(OP_MODE, operationMode == 0 ? "0" : "0x" + Integer.toHexString(operationMode));
        return out;
    }

    /**
     * This profile with the stored {@code edits} over it (a field's text; absent or null: the profile's own). A text that does not
     * parse leaves the field and is named in {@code problems} (may be null).
     */
    public FullResRecipe overlay(@Nullable Map<String, String> edits, @Nullable List<String> problems) {
        if (edits == null || edits.isEmpty()) return this;
        final Builder b = edit();
        boolean edited = false;
        for (String field : FIELDS) {
            final String raw = edits.get(field);
            if (raw == null) continue;
            final String v = raw.trim();
            if (applyField(b, field, v)) edited = true;
            else if (problems != null) problems.add(field + ": \"" + v + "\"");
        }
        if (edited && !fields().equals(b.build().fields())) b.id(id.isEmpty() ? "" : id + " (edited)").experimental(true);
        return b.build();
    }

    /** Whether {@code text} is a value of {@code field} (the page refuses one that is not). */
    public static boolean valid(String field, @Nullable String text) {
        return text != null && applyField(new Builder(), field, text.trim());
    }

    /** One field's text onto a builder; false when it does not parse. */
    static boolean applyField(Builder b, String field, String v) {
        final String low = v.toLowerCase(Locale.ROOT);
        switch (field) {
            case SIZE: {
                final int[] s = size(low);
                if (s == null) return false;
                b.size(s[0], s[1]);
                return true;
            }
            case USAGE: {
                final Double n = whole(low);
                if (n == null || n < 0) return false;
                b.usage(n.longValue());
                return true;
            }
            case USE_CASE: {
                final Double n = whole(low);
                if (n == null || n < 0) return false;
                b.useCase(n.longValue());
                return true;
            }
            case COMPANION: {
                if (low.equals("off") || low.isEmpty()) b.companion(Companion.OFF, 0, 0);
                else if (low.equals("viewfinder")) b.companion(Companion.VIEWFINDER, 0, 0);
                else if (low.equals("auto")) b.companion(Companion.YUV, 0, 0);
                else {
                    final int[] s = size(low);
                    if (s == null) return false;
                    b.companion(Companion.YUV, s[0], s[1]);
                }
                return true;
            }
            case TAGS: {
                final List<Tag> tags = new ArrayList<>();
                for (String line : v.split("[\\n;]")) {
                    if (line.trim().isEmpty()) continue;
                    final Tag t = Tag.parse(line.trim());
                    if (t == null) return false;
                    tags.add(t);
                }
                b.tags.clear();
                b.tags.addAll(tags);
                return true;
            }
            case VIEW_TAGS:
            case VIEW_COMPANION: {
                final Boolean yes = yesNo(low);
                if (yes == null) return false;
                if (VIEW_TAGS.equals(field)) b.viewTags(yes);
                else b.viewCompanion(yes);
                return true;
            }
            case TEMPLATE:
            case SESSION: {
                final int t = template(low);
                if (t == 0) return false;
                if (TEMPLATE.equals(field)) b.stillTemplate = t;
                else b.sessionTemplate = t;
                return true;
            }
            case FPS: {
                if (low.isEmpty() || low.equals("0")) {
                    b.fps(0, 0);
                    return true;
                }
                final String[] p = low.split("-");
                final Double lo = whole(p[0]), hi = p.length > 1 ? whole(p[1]) : lo;
                if (p.length > 2 || lo == null || hi == null || lo < 1 || hi < lo || hi > 960) return false;
                b.fps(lo.intValue(), hi.intValue());
                return true;
            }
            case PIXEL_MODE:
                try {
                    b.pixelMode(PixelMode.valueOf(low.toUpperCase(Locale.ROOT)));
                    return true;
                } catch (IllegalArgumentException e) {
                    return false;
                }
            case PREVIEW:
                try {
                    b.preview(Preview.valueOf(low.toUpperCase(Locale.ROOT)));
                    return true;
                } catch (IllegalArgumentException e) {
                    return false;
                }
            case FRAMES: {
                final Double n = whole(low);
                if (n == null || n < 1 || n > MAX_FRAMES) return false;
                b.frames(n.intValue());
                return true;
            }
            case OP_MODE: {
                final Double n = whole(low);
                if (n == null || n < 0 || n > 0xFFFF) return false;
                b.operationMode(n.intValue());
                return true;
            }
            default:
                return false;
        }
    }

    /** yes / no (also 1 / 0, on / off, true / false); null for any other text. */
    @Nullable
    static Boolean yesNo(String low) {
        if (low.equals("yes") || low.equals("1") || low.equals("on") || low.equals("true")) return Boolean.TRUE;
        if (low.equals("no") || low.equals("0") || low.equals("off") || low.equals("false")) return Boolean.FALSE;
        return null;
    }

    /** "8192x6144" (also "8192 x 6144", "8192×6144"); null when it is no size. */
    @Nullable
    static int[] size(String text) {
        final String[] p = text.replace('×', 'x').replace(" ", "").split("x");
        if (p.length != 2) return null;
        final Double w = whole(p[0]), h = whole(p[1]);
        if (w == null || h == null || w < 16 || h < 16 || w > 65535 || h > 65535) return null;
        return new int[]{w.intValue(), h.intValue()};
    }

    /** A whole number, decimal or 0x hex; null when it is none. */
    @Nullable
    static Double whole(String text) {
        final String t = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.startsWith("0x")) return (double) Long.parseLong(t.substring(2), 16);
            if (t.startsWith("-0x")) return -(double) Long.parseLong(t.substring(3), 16);
            return (double) Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    static Double decimal(String text) {
        try {
            final double v = Double.parseDouble(text.trim());
            return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ---------------------------------------------------------------- the checks

    /**
     * Why this profile cannot run on this phone ({@code sdk}: Build.VERSION.SDK_INT), empty when it can: no size, a stream use
     * case below API 33, annotated pixel modes below API 31, a viewfinder companion without the ISP stream in the session or
     * with annotated pixel modes (the viewfinder stays DEFAULT: a MAXIMUM_RESOLUTION still cannot target it), a YUV companion on
     * the viewfinder's request with annotated pixel modes (P653).
     */
    public List<String> blockers(int sdk) {
        final List<String> out = new ArrayList<>();
        if (width <= 0 || height <= 0) out.add("size");
        if (useCase != 0 && sdk < 33) out.add("usecase:33");
        if (pixelMode == PixelMode.ANNOTATE && sdk < 31) out.add("pixelmode:31");
        if (companion == Companion.VIEWFINDER && preview == Preview.RAW) out.add("companion:viewfinder");
        if (companion == Companion.VIEWFINDER && pixelMode == PixelMode.ANNOTATE) out.add("companion:pixelmode");
        // P653: a MAXIMUM_RESOLUTION companion cannot be a target of the DEFAULT viewfinder request
        if (viewCompanion && companion == Companion.YUV && pixelMode == PixelMode.ANNOTATE) out.add("viewcompanion:pixelmode");
        if (sdk < 28) out.add("session:28");
        return out;
    }

    /** What identifies the running profile (the failure memory: a changed field is a new trial). */
    public String signature() {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields().entrySet()) sb.append(e.getKey()).append('=').append(e.getValue().replace('\n', ';')).append('|');
        return sb.toString();
    }

    @Override public String toString() {
        return (id.isEmpty() ? "own profile" : "«" + id + "»") + (experimental ? " (experimental)" : "") + " " + signature();
    }
}
