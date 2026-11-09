package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.Calendar;
import java.util.Locale;

/**
 * P59b: the gallery in the SCAMERA card style (owner's GALLERY_TASK.md, concept "SCAMERA Gallery Concept"): the tokens of
 * the settings / shade / manual controls (UiTokens through SettingsStyle) and the pieces every gallery screen is built from.
 * Texts in ru / en (Lang). Built in code like the settings screens; the data and file logic stay where they were.
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the tokens and pieces of the
 * camera's and the settings' new look - the #1C1C1E page and sheets, #2C2C2E cards with 24dp corners and 0.5dp hairlines, white
 * titles, secondary text at 60 % white, 12sp section captions, round 40dp buttons, dark bars over photos with a light edge, capsule
 * buttons (accent / grey / red), the shared switch, slider and segmented control.
 */
public final class GalleryUi {
    private GalleryUi() {}

    /** P579: the page and sheet (#1C1C1E), a card or row (#2C2C2E), the hairline, white text, 60 % white, the red. */
    public static final int BG = SettingsStyle.PAGE, CARD = SettingsStyle.ROW, LINE = SettingsStyle.HAIRLINE, TEXT = 0xFFFFFFFF,
            MUTED = SettingsStyle.SUMMARY, SHEET = SettingsStyle.PAGE, WARN = SettingsStyle.RED, INK = SettingsStyle.INK;
    /** P579: a section caption (55 % white), the grey capsule of a secondary button, the light edge of a bar over a photo. */
    public static final int SECTION = SettingsStyle.SECTION, CAPSULE = 0xFF2C2C2E, EDGE = 0x29FFFFFF;
    /** P579: a dark bar over a photo (rgba(20, 20, 22, .9)), the thumbnail badges and the floating pills over a photo. */
    public static final int BAR = 0xE6141416, SCRIM = 0xD9141416, PILL_BG = BAR;
    /** P579: a list row's minimum height (dp). */
    public static final int ROW_MIN = 52;

    public static int dp(Context c, float v) { return SettingsStyle.dp(c, v); }

    /**
     * P502: how many times the portrait grid's columns fit across the screen now (1 in portrait, 2 on a phone held sideways):
     * the column settings stay the portrait ones, a wide screen multiplies them (3 columns sideways gave cells as tall as the screen).
     */
    public static int widthFactor(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        float widthDp = m.widthPixels / Math.max(.5f, m.density);
        return Math.max(1, Math.round(widthDp / 420f));
    }

    /**
     * P579 (owner, 2026-11-09: «Цвет пускай будет зависеть от темы смартфона, как и остальной интерфейс»): the gallery takes the
     * accent the camera, its shade and the settings use (AccentPalette.camera: the phone's Material You colour, else the chosen
     * accent, else the camera's yellow). It was AccentPalette.color, which differs only before Android 12 with no accent chosen
     * (lavender there): the gallery read differently from the camera it opens from.
     */
    public static int accent(Context c) { return com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.camera(c); }

    /** P579: a card - the fill with {@code radius} corners, no outline (the concept's grouped cards have none). */
    public static GradientDrawable card(Context c, int fill, float radius) {
        return SettingsStyle.shape(c, fill, 0, radius);
    }

    /** P579: a dark bar or card over a photo or the grid: {@link #BAR}, the 0.5dp light edge, {@code radius} corners. */
    public static GradientDrawable bar(Context c, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(BAR);
        g.setStroke(Math.max(1, dp(c, .5f)), EDGE);
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    /** P579: a semibold face (600), as the settings' titles. */
    public static Typeface semibold() {
        return SettingsStyle.semibold();
    }

    public static GradientDrawable round(Context c, int fill, int stroke, float radius) {
        return SettingsStyle.shape(c, fill, stroke, radius);
    }

    /** A pressable background: the shape with a light ripple. */
    public static RippleDrawable pressable(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(0x29FFFFFF), shape, null);
    }

    public static TextView text(Context c, CharSequence value, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        UiTokens.setTextSp(t, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** One line, no ellipsis allowed by the task: the text shrinks to fit instead (down to 70 %). */
    public static TextView fitText(Context c, CharSequence value, float sp, int color) {
        TextView t = text(c, value, sp, color);
        t.setSingleLine(true);
        t.setEllipsize(null);
        t.setHorizontallyScrolling(false);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t,
                Math.max(1, Math.round(sp * .7f)), Math.round(sp), 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        return t;
    }

    public static ImageView icon(Context c, @DrawableRes int res, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        com.particlesdevs.photoncamera.ui.Icons.set(v, res, color); // P570: a colour icon keeps its colours
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /**
     * The header's and the pages' round button; P579: the concept's 40dp #2C2C2E disc with a white line icon (it was a 44dp square
     * card with an accent icon).
     */
    public static FrameLayout squareButton(Context c, @DrawableRes int res, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(c);
        b.setContentDescription(description);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.setTooltipText(description);
        ImageView i = icon(c, res, TEXT, 22);
        b.addView(i, new FrameLayout.LayoutParams(dp(c, 22), dp(c, 22), Gravity.CENTER));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        disc(b);
        com.particlesdevs.photoncamera.ui.Motion.press(b); // P498: gives under the finger as the shade's controls
        return b;
    }

    /**
     * An action of the selection bar and of the viewer's button card: the icon above a short label, every button the same height;
     * {@code warn} draws both in the red. P579: a white line icon (tonal mono for a colour icon) and a 60 % white label, the
     * camera's bars' look (the icons were in the accent).
     */
    public static LinearLayout actionButton(Context c, @DrawableRes int res, String label, boolean warn, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(c, 8), 0, dp(c, 8));
        b.setMinimumHeight(dp(c, 52));
        b.setBackground(pressable(round(c, Color.TRANSPARENT, Color.TRANSPARENT, 16)));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setContentDescription(label);
        ImageView i = new ImageView(c);
        com.particlesdevs.photoncamera.ui.Icons.ink(i, res, warn ? WARN : TEXT);
        i.setImageResource(res);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.addView(i, new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
        TextView t = fitText(c, label, 11, warn ? WARN : MUTED);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 3);
        b.addView(t, lp);
        return b;
    }

    /** The disabled look of an action (the concept's opacity .35). */
    public static void setEnabledLook(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : .35f);
    }

    /**
     * A folder or filter chip «SCAMERA 128»: at least 36dp tall. P579: a #2C2C2E capsule with white text, the active one the accent
     * capsule with dark ink (the concept's primary capsule), no outline.
     */
    public static TextView chip(Context c, String name, int count, boolean active) {
        TextView t = new TextView(c);
        UiTokens.setTextSp(t, 15);
        t.setSingleLine(true);
        t.setEllipsize(null);
        t.setMinHeight(dp(c, 36));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(name);
        if (count >= 0) {
            int start = s.length();
            s.append("  ").append(String.valueOf(count));
            s.setSpan(new android.text.style.RelativeSizeSpan(.8f), start, s.length(), 0);
        }
        t.setText(s);
        paintChip(t, active);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** P579: a chip's look on or off (the accent capsule with dark ink / the grey one with white text). */
    public static void paintChip(TextView t, boolean active) {
        Context c = t.getContext();
        int accent = accent(c);
        t.setTextColor(active ? INK : TEXT);
        t.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(t.getContext(), active ? 600 : 400));
        t.setBackground(pressable(round(c, active ? accent : CARD, 0, 999)));
        t.setSelected(active);
    }

    /** A rounded pill over a photo (zoom «250 %», mini EXIF): P579, the dark bar with its light edge. */
    public static TextView pill(Context c, String value, boolean accentText) {
        TextView t = text(c, value, 12.5f, accentText ? accent(c) : TEXT);
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        t.setBackground(bar(c, 999));
        t.setGravity(Gravity.CENTER);
        t.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(t.getContext(), 600));
        return t;
    }

    /**
     * P579: a capsule button - {@link #PRIMARY} the accent with dark ink, {@link #SECONDARY} grey with white text, {@link #DESTRUCTIVE}
     * grey with the red text (the concept's alert and sheet buttons); 46dp tall, 16sp semibold.
     */
    public static TextView capsule(Context c, String label, int kind) {
        int accent = accent(c);
        TextView b = text(c, label, 16, kind == PRIMARY ? INK : kind == DESTRUCTIVE ? WARN : TEXT);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(b.getContext(), 600));
        b.setMaxLines(1);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinHeight(dp(c, 46));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setBackground(pressable(round(c, kind == PRIMARY ? accent : kind == DESTRUCTIVE ? 0x2EFF453A : CAPSULE, 0, 999)));
        b.setClickable(true);
        b.setFocusable(true);
        com.particlesdevs.photoncamera.ui.Motion.press(b);
        return b;
    }

    public static final int PRIMARY = 0, SECONDARY = 1, DESTRUCTIVE = 2;

    /**
     * P579: a group of rows - one #2C2C2E card with 24dp corners, its rows parted by 0.5dp hairlines that start at {@code insetDp}
     * (the text of the rows, past their icon). Rows added to it take its corners (clipped to its outline).
     */
    public static LinearLayout group(Context c, float insetDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(card(c, CARD, 24));
        l.setClipToOutline(true);
        l.setDividerDrawable(hairline(c, insetDp));
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return l;
    }

    /** The hairline between the rows of a group: 0.5dp, {@code insetDp} from the start (mirrored right to left). */
    public static android.graphics.drawable.Drawable hairline(Context c, float insetDp) {
        GradientDrawable line = new GradientDrawable();
        line.setColor(LINE);
        line.setSize(1, Math.max(1, dp(c, .5f)));
        android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{line});
        d.setLayerInsetRelative(0, dp(c, insetDp), 0, 0, 0);
        return d;
    }

    /**
     * P579: the shared segmented control (a grey thumb gliding to the chosen segment, its label in the accent), as the camera's
     * sheets build it; segments tagged {@code seg_i}; {@code pick} hears a tap's index.
     */
    public static com.particlesdevs.photoncamera.ui.controls.IosSegmented segmented(Context c, CharSequence[] labels, int selected,
                                                                                java.util.function.IntConsumer pick) {
        com.particlesdevs.photoncamera.ui.controls.IosSegmented seg = new com.particlesdevs.photoncamera.ui.controls.IosSegmented(c);
        seg.setColours(accent(c), 0x3D767680, 0xFF636366);
        seg.setItems(labels);
        for (int i = 0; i < seg.getChildCount(); i++) {
            seg.getChildAt(i).setTag("seg_" + i);
            ((TextView) seg.getChildAt(i)).setMinHeight(dp(c, 36));
        }
        seg.setSelected(selected, false);
        seg.setListener((s, index) -> pick.accept(index));
        return seg;
    }

    /** P579: the chosen row's trailing tick in the accent (rows of a choice instead of radio circles); not chosen keeps its room. */
    public static ImageView tick(Context c, boolean on) {
        ImageView v = new ImageView(c);
        v.setImageResource(com.particlesdevs.photoncamera.R.drawable.ios_menu_check);
        v.setColorFilter(accent(c));
        v.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
        return v;
    }

    /** P498: the header with several discs at the right (8dp apart). */
    public static FrameLayout header(Context c, String title, View left, View... rights) {
        if (rights.length <= 1) return header(c, title, left, rights.length == 1 ? rights[0] : null); // P579: 40dp discs
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < rights.length; i++) {
            disc(rights[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40));
            if (i > 0) lp.setMarginStart(dp(c, 8));
            group.addView(rights[i], lp);
        }
        FrameLayout h = header(c, title, left, (View) null);
        LinearLayout row = (LinearLayout) h.getChildAt(0);
        row.addView(group, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 40)));
        return h;
    }

    /** P498: the MIME type for sharing a set of files: the common one, else image/ or video/ any, else any for a mix. */
    public static String shareType(java.util.List<String> names) {
        String common = null;
        boolean video = false, image = false;
        for (String n : names) {
            String m = com.particlesdevs.photoncamera.gallery.files.GalleryVideo.isVideoName(n)
                    ? com.particlesdevs.photoncamera.gallery.files.GalleryVideo.mime(n)
                    : com.particlesdevs.photoncamera.gallery.editor.ExternalEdit.mediaTypeOf(n);
            if (m == null) m = "image/*";
            if (m.startsWith("video/")) video = true;
            else image = true;
            common = common == null ? m : common.equals(m) ? common : "";
        }
        if (common != null && !common.isEmpty()) return common;
        return video && image ? "*/*" : video ? "video/*" : "image/*";
    }

    /**
     * The header of the gallery screens: a round button on each side and the title. P579: the settings' header look - 40dp discs,
     * the title large and bold (26sp, shrinking to fit) at the left.
     */
    public static FrameLayout header(Context c, String title, View left, @Nullable View right) {
        FrameLayout h = new FrameLayout(c);
        h.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 10));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 44));
        disc(left);
        row.addView(left, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        TextView t = fitText(c, title, 26, TEXT);
        t.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(t.getContext(), 700));
        t.setLetterSpacing(-0.012f);
        t.setAccessibilityHeading(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.setMarginStart(dp(c, 12));
        tp.setMarginEnd(dp(c, 10));
        row.addView(t, tp);
        if (right != null) {
            disc(right);
            row.addView(right, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        }
        h.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return h;
    }

    /** The concept's round button: a #2C2C2E disc, its icon white (P579: tonal mono white for a colour icon, as the settings' discs). */
    static void disc(View b) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(CARD);
        b.setBackground(pressable(d));
        if (b instanceof ViewGroup && ((ViewGroup) b).getChildCount() > 0 && ((ViewGroup) b).getChildAt(0) instanceof ImageView) {
            final ImageView i = (ImageView) ((ViewGroup) b).getChildAt(0);
            if (com.particlesdevs.photoncamera.ui.Icons.colour(i)) com.particlesdevs.photoncamera.ui.Icons.mono(i, TEXT);
            else i.setImageTintList(ColorStateList.valueOf(TEXT));
        }
    }

    /** The section caption of the sheets and pages («СЪЁМКА»): P579, 12sp semibold capitals at 55 % white, letter-spaced. */
    public static TextView sectionLabel(Context c, String value) {
        TextView t = text(c, value.toUpperCase(Lang.ru() ? new Locale("ru") : Locale.ROOT), 12, SECTION);
        t.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(t.getContext(), 600));
        t.setLetterSpacing(.06f);
        t.setPadding(dp(c, 16), dp(c, 18), dp(c, 16), dp(c, 8));
        t.setAccessibilityHeading(true);
        return t;
    }

    // ---------------------------------------------------------------- texts

    /** «1 снимок / 2 снимка / 5 снимков», "1 photo / 2 photos". */
    public static String shots(int n) {
        String en = n == 1 ? "photo" : "photos";
        switch (ruForm(n)) {
            case 0: return n + " " + Lang.t("снимок", en);
            case 1: return n + " " + Lang.t("снимка", en);
            default: return n + " " + Lang.t("снимков", en);
        }
    }

    /** The day section's count «N фото» / "N photos". */
    public static String dayCount(int n) {
        return Lang.t(n + " фото", n + (n == 1 ? " photo" : " photos"));
    }

    /** P498: a day with videos: «3 фото · 1 видео», «2 видео». */
    public static String dayCount(int photos, int videos) {
        if (videos <= 0) return dayCount(photos);
        String v = Lang.t(videos + " видео", videos + (videos == 1 ? " video" : " videos"));
        return photos > 0 ? dayCount(photos) + " · " + v : v;
    }

    /** Russian plural form of {@code n}: 0 one (1, 21), 1 few (2..4, 22..24), 2 many (0, 5..20, 11..14). */
    public static int ruForm(int n) {
        int a = Math.abs(n) % 100, b = Math.abs(n) % 10;
        if (a > 10 && a < 20) return 2;
        if (b == 1) return 0;
        if (b >= 2 && b <= 4) return 1;
        return 2;
    }

    /** «Сегодня», «Вчера», «6 октября» (the year when it is not this one) / "Today", "Yesterday", "October 6". */
    public static String dayLabel(long millis, long nowMillis) {
        Calendar day = Calendar.getInstance(), now = Calendar.getInstance();
        day.setTimeInMillis(millis);
        now.setTimeInMillis(nowMillis);
        if (sameDay(day, now)) return Lang.t("Сегодня", "Today");
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(day, now)) return Lang.t("Вчера", "Yesterday");
        now.add(Calendar.DAY_OF_YEAR, 1);
        boolean ru = Lang.ru();
        int d = day.get(Calendar.DAY_OF_MONTH), y = day.get(Calendar.YEAR);
        String month = monthName(day.get(Calendar.MONTH));
        String s = ru ? d + " " + month : month + " " + d;
        if (y != now.get(Calendar.YEAR)) s += ru ? " " + y : ", " + y;
        return s;
    }

    /** «Сегодня, 11:17». */
    public static String dayTime(long millis, long nowMillis) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(millis);
        return dayLabel(millis, nowMillis) + ", " + String.format(Locale.ROOT, "%02d:%02d", day.get(Calendar.HOUR_OF_DAY), day.get(Calendar.MINUTE));
    }

    static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** The month in the genitive («октября») / "October". */
    static String monthName(int month) {
        switch (month) {
            case 0: return Lang.t("января", "January");
            case 1: return Lang.t("февраля", "February");
            case 2: return Lang.t("марта", "March");
            case 3: return Lang.t("апреля", "April");
            case 4: return Lang.t("мая", "May");
            case 5: return Lang.t("июня", "June");
            case 6: return Lang.t("июля", "July");
            case 7: return Lang.t("августа", "August");
            case 8: return Lang.t("сентября", "September");
            case 9: return Lang.t("октября", "October");
            case 10: return Lang.t("ноября", "November");
            default: return Lang.t("декабря", "December");
        }
    }

    /** A decimal with the locale's separator («1,85» in ru). */
    public static String decimal(double v, int digits) {
        String s = String.format(Locale.ROOT, "%." + digits + "f", v);
        if (digits > 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return Lang.ru() ? s.replace('.', ',') : s;
    }

    /** "1.2 MB" / «1,2 МБ». */
    public static String size(long bytes) {
        if (bytes >= 1L << 20) return decimal(bytes / (double) (1L << 20), 1) + Lang.t(" МБ", " MB");
        if (bytes >= 1L << 10) return Math.round(bytes / 1024.0) + Lang.t(" КБ", " KB");
        return bytes + Lang.t(" Б", " B");
    }

    static boolean empty(@Nullable CharSequence s) {
        return TextUtils.isEmpty(s);
    }
}
