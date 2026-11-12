package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.Dcg14Mode;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding;
import com.particlesdevs.photoncamera.settings.FormatChoice;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SIMPLE_UI §1 The camera's top bar. P574 (the approved concept, owner 2026-11-09: «Отлично, реализуем»): one centred row of
 * 40dp items 16dp apart - the format badge («JPEG» over «12 Мп»: the format and size sheet), the torch, the timer with its
 * seconds badge, LUT (the LUT and film tray, drawn by LutTray), DCG where the module has it. Off an item is a white glyph on
 * nothing, on it is the accent on the accent's tint (the concept's 16 %): less glass over the picture. The gear and Sport went to
 * the bottom row. Every size is the concept's dp times {@link UiScale} (once per view tree). When the row does not fit at the
 * current width and font scale it steps down: (1) gaps 10; (2) gaps 6, «RAW+HEIC» without spaces; (3) gaps 2 and smaller text.
 */
public final class TopBar {
    private TopBar() {}

    static final int LEVELS = 4;
    /** P574: the concept's gap between the row's items at each level (dp before the scale). */
    static final float[] GAPS = {16, 10, 6, 2};
    /** P574: an item that is on: the accent at this alpha under it (the concept's rgba(255, 214, 10, .16)). */
    static final int ON_TINT_ALPHA = 0x29;
    /** DCG 14 bit on this module (Dcg14Mode.available of its characteristics). */
    private static volatile boolean dcgHere;

    /** The module's capabilities that change the bar: DCG and the photo sizes (CameraFragment, on every characteristics update). */
    public static void bindCamera(@Nullable CameraCharacteristics characteristics) {
        PhotoSize.bind(characteristics);
        // P590: the flash modes need a flash unit (a front lens keeps off <-> torch: its screen light, P507)
        final Boolean flash = characteristics == null ? null : characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        com.particlesdevs.photoncamera.capture.FlashMode.bindLens(characteristics == null || flash != null && flash);
        dcgHere = Dcg14Mode.device() && characteristics != null && Dcg14Mode.available(characteristics);
    }

    /** DCG on this module without characteristics (tests). */
    static void dcgAvailable(boolean here) {
        dcgHere = here;
    }

    public static boolean dcgAvailable() {
        return dcgHere;
    }

    /** The accent's tint under an item that is on. */
    static int onTint(int accent) {
        return (accent & 0x00FFFFFF) | (ON_TINT_ALPHA << 24);
    }

    /** Values, states and descriptions from the settings; then the fit. */
    public static void bind(LayoutMainTopbarBinding tb) {
        View root = tb.getRoot();
        Context c = root.getContext();
        // P495: a change of a bound state pops its icon (the first bind only sets them)
        final boolean rebind = root.getTag(R.id.topbar_row) != null;
        if (!rebind) {
            root.setTag(R.id.topbar_row, Boolean.TRUE);
            scaleTree(root, UiScale.k(c), true);
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) v.post(() -> fit(tb));
            });
            // P495: every control of the bar gives under the finger and springs back
            for (View v : new View[]{tb.formatButton, tb.torchButton, tb.timerButton, tb.lutButton, tb.dcgBadge, tb.gridButton,
                    tb.horizonButton, tb.uhdrButton})
                com.particlesdevs.photoncamera.ui.Motion.press(v);
        }
        final Object wasFlash = tb.torchButton.getTag(R.id.torch_button);
        final String wasTimer = tb.timerButton.isActivated() ? String.valueOf(tb.timerBadge.getText()) : "";
        int accent = ShadeStyle.accent(c);
        // P590: the flash mode (pref_ae_mode: 0 torch, 1 off, 2 auto, 3 on; FlashMode), as the lens and the mode allow it - off a
        // white bolt with a slash, auto a bolt with «A», on the filled bolt, the torch a flashlight; every mode but off in the accent
        final int flash = com.particlesdevs.photoncamera.capture.FlashMode.shown();
        final boolean torch = flash != com.particlesdevs.photoncamera.capture.FlashMode.OFF;
        tb.torchButton.setTag(R.id.torch_button, flash);
        button(tb.torchButton, tb.torchIcon, torch, accent, com.particlesdevs.photoncamera.capture.FlashMode.label(flash));
        ConceptStyle.icon(tb.torchIcon, com.particlesdevs.photoncamera.capture.FlashMode.icon(flash), torch ? accent : ShadeStyle.TEXT);
        // timer (0, 3 or 10 s)
        int seconds = timerSeconds(c);
        boolean timer = seconds > 0;
        tb.timerBadge.setText(String.valueOf(seconds));
        tb.timerBadge.setVisibility(timer ? View.VISIBLE : View.GONE);
        tb.timerBadge.setTextColor(ShadeStyle.onAccent(accent));
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(accent);
        badge.setCornerRadius(UiScale.dpF(c, 8));
        tb.timerBadge.setBackground(badge);
        String timerName = timer ? Lang.t(c, "Таймер: " + seconds + " с", "Timer: " + seconds + " s") : Lang.t(c, "Таймер: выкл.", "Timer: off");
        button(tb.timerButton, tb.timerIcon, timer, accent, timerName);
        // the format badge: the format over the photo size (P319: the photo this lens really writes)
        FormatChoice format = FormatChoice.current();
        int size = PhotoSize.current();
        final String sizeText = PhotoSize.badge();
        tb.formatBadge.setTag(format.name());
        tb.formatBadge.setTag(R.id.format_badge, format.longLabel());
        tb.formatBadge.setText(format.longLabel());
        tb.formatBadge.setContentDescription(c.getString(R.string.topbar_format, format.longLabel()));
        tb.sizeBadge.setText(sizeText);
        // a format or size other than the plain JPEG at the normal size is on: the badge in the accent on its tint
        final boolean formatOn = format != FormatChoice.JPEG || size != PhotoSize.NORMAL;
        tb.formatBadge.setTextColor(formatOn ? accent : ShadeStyle.TEXT);
        tb.sizeBadge.setTextColor(formatOn ? accent : ShadeStyle.TEXT);
        tb.sizeBadge.setAlpha(.85f);
        tb.formatButton.setActivated(formatOn);
        tb.formatButton.setBackground(ShadeStyle.pressable(c, formatOn ? onTint(accent) : 0x00000000, 0, 20));
        final String formatName = Lang.t(c, "Формат и размер: " + format.longLabel() + ", " + sizeText
                        + ". Нажатие — выбор, удержание — размеры",
                "Format and size: " + format.longLabel() + ", " + sizeText + ". Tap: choose, hold: sizes");
        tb.formatButton.setContentDescription(formatName);
        ViewCompat.setTooltipText(tb.formatButton, Lang.t(c, "Формат и размер", "Format and size"));
        // DCG
        boolean dcg = dcgHere, dcgOn = dcg && Dcg14Mode.mode() != Dcg14Mode.OFF;
        final String dcgText = Dcg14Mode.badge(); // P641: «DSG» on the Xiaomi 17 Ultra's ultra wide, «DCG+L» with LOFIC on
        if (!dcgText.contentEquals(tb.dcgBadge.getText())) tb.dcgBadge.setText(dcgText);
        tb.dcgBadge.setTextColor(dcgOn ? accent : ShadeStyle.TEXT);
        tb.dcgBadge.setSelected(dcgOn);
        tb.dcgBadge.setActivated(dcgOn);
        tb.dcgBadge.setBackground(ShadeStyle.pressable(c, dcgOn ? onTint(accent) : 0x00000000, 0, 999));
        tb.dcgBadge.setContentDescription(c.getString(dcgOn ? R.string.dcg14_on : R.string.dcg14_off));
        ViewCompat.setStateDescription(tb.dcgBadge, Lang.t(c, dcgOn ? "вкл." : "выкл.", dcgOn ? "on" : "off"));
        // P574: the items the «Верхняя строка» sheet can add - the grid, the level, Ultra HDR (the shade's same settings)
        final boolean wasGrid = tb.gridButton.isActivated(), wasHorizon = tb.horizonButton.isActivated();
        boolean grid = PreferenceKeys.getGridValue() > 0;
        button(tb.gridButton, tb.gridIcon, grid, accent, Lang.t(c, grid ? "Сетка: вкл." : "Сетка: выкл.", grid ? "Grid: on" : "Grid: off"));
        boolean horizon = PreferenceKeys.isHorizonOn();
        button(tb.horizonButton, tb.horizonIcon, horizon, accent,
                Lang.t(c, horizon ? "Горизонт: вкл." : "Горизонт: выкл.", horizon ? "Level: on" : "Level: off"));
        boolean uhdr = PreferenceKeys.isUltraHdrOn();
        button(tb.uhdrButton, tb.uhdrIcon, uhdr, accent, Lang.t(c, uhdr ? "Ultra HDR: вкл." : "Ultra HDR: выкл.", uhdr ? "Ultra HDR: on" : "Ultra HDR: off"));
        if (rebind) {
            // P495: torch and timer switched (here or from the shade): their icon breathes (the badges have the press)
            if (wasFlash != null && !wasFlash.equals(flash)) com.particlesdevs.photoncamera.ui.Motion.pop(tb.torchIcon);
            if (!wasTimer.equals(timer ? String.valueOf(tb.timerBadge.getText()) : "")) com.particlesdevs.photoncamera.ui.Motion.pop(tb.timerIcon);
            if (wasGrid != grid) com.particlesdevs.photoncamera.ui.Motion.pop(tb.gridIcon);
            if (wasHorizon != horizon) com.particlesdevs.photoncamera.ui.Motion.pop(tb.horizonIcon);
        }
        // P574: the row's items and their order from the stored row (TopRow); a change glides the others into place
        arrange(tb, com.particlesdevs.photoncamera.settings.TopRow.load(androidx.preference.PreferenceManager.getDefaultSharedPreferences(c)), rebind);
        fit(tb);
    }

    /** P574: the item views by their TopRow id. */
    static java.util.Map<String, View> items(LayoutMainTopbarBinding tb) {
        java.util.Map<String, View> m = new java.util.LinkedHashMap<>();
        m.put(com.particlesdevs.photoncamera.settings.TopRow.FORMAT, tb.formatButton);
        m.put(com.particlesdevs.photoncamera.settings.TopRow.TORCH, tb.torchButton);
        m.put(com.particlesdevs.photoncamera.settings.TopRow.TIMER, tb.timerButton);
        m.put(com.particlesdevs.photoncamera.settings.TopRow.LUT, tb.lutButton);
        m.put(com.particlesdevs.photoncamera.settings.TopRow.DCG, tb.dcgBadge);
        m.put(com.particlesdevs.photoncamera.settings.TopRow.GRID, tb.gridButton);
        m.put(com.particlesdevs.photoncamera.settings.TopRow.HORIZON, tb.horizonButton);
        m.put(com.particlesdevs.photoncamera.settings.TopRow.UHDR, tb.uhdrButton);
        return m;
    }

    /** P574: whether item {@code id} can show on the open camera (DCG only on a module that has it). */
    static boolean available(String id) {
        return !com.particlesdevs.photoncamera.settings.TopRow.DCG.equals(id) || dcgHere;
    }

    /**
     * P574: the row holds {@code ids} in their order (the others hidden, after them); {@code animate}: the items that stay glide
     * from where they were, a new one grows in (ViewPropertyAnimator), a removed one goes at once.
     */
    static void arrange(LayoutMainTopbarBinding tb, java.util.List<String> ids, boolean animate) {
        final ViewGroup row = (ViewGroup) tb.getRoot();
        final java.util.Map<String, View> views = items(tb);
        final boolean laidOut = row.getWidth() > 0 && animate && android.animation.ValueAnimator.areAnimatorsEnabled();
        final java.util.Map<View, Integer> before = new java.util.HashMap<>();
        if (laidOut) for (View v : views.values()) if (v.getVisibility() == View.VISIBLE) before.put(v, v.getLeft());
        java.util.List<View> order = new java.util.ArrayList<>();
        for (String id : ids) if (views.containsKey(id)) order.add(views.get(id));
        for (java.util.Map.Entry<String, View> e : views.entrySet()) if (!ids.contains(e.getKey())) order.add(e.getValue());
        boolean moved = false;
        for (int i = 0; i < order.size() && !moved; i++) moved = row.getChildAt(i) != order.get(i);
        if (moved) {
            for (View v : order) row.removeView(v);
            for (View v : order) row.addView(v);
        }
        final java.util.List<View> grown = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, View> e : views.entrySet()) {
            final View v = e.getValue();
            final boolean shown = ids.contains(e.getKey()) && available(e.getKey());
            if (shown && v.getVisibility() != View.VISIBLE && laidOut) grown.add(v);
            v.setVisibility(shown ? View.VISIBLE : View.GONE);
        }
        if (!laidOut || before.isEmpty() && grown.isEmpty()) return;
        row.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                row.getViewTreeObserver().removeOnPreDrawListener(this);
                for (java.util.Map.Entry<View, Integer> b : before.entrySet()) {
                    final View v = b.getKey();
                    if (v.getVisibility() != View.VISIBLE) continue;
                    final int dx = b.getValue() - v.getLeft();
                    if (dx == 0) continue;
                    v.setTranslationX(dx);
                    v.animate().translationX(0f).setDuration(com.particlesdevs.photoncamera.ui.Motion.ENTER.durationMs)
                            .setInterpolator(com.particlesdevs.photoncamera.ui.Motion.ENTER).start();
                }
                for (View v : grown) {
                    v.setAlpha(0f);
                    v.setScaleX(.6f);
                    v.setScaleY(.6f);
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(com.particlesdevs.photoncamera.ui.Motion.ENTER.durationMs)
                            .setInterpolator(com.particlesdevs.photoncamera.ui.Motion.ENTER).start();
                }
                return true;
            }
        });
    }

    /**
     * The «Размер фото» sheet (a long press on the size button): Обычный · 12 Мп, Подробный · 50 Мп, Максимум · 200 Мп, each with
     * what it gives; a size this module lacks says why and does nothing.
     */
    public static void openSizeSheet(CameraFragment fragment) {
        // P574: the combined «Формат | Размер» sheet on its size tab
        fragment.openFormatChooser(CameraSheets.TAB_SIZE);
    }

    /**
     * P574: a round item of the row (the concept): off, the glyph white on nothing; on, the glyph in the accent on the accent's
     * tint. The press shows the glyph's pressed pose.
     */
    private static void button(View box, ImageView icon, boolean on, int accent, String name) {
        Context c = box.getContext();
        box.setActivated(on);
        box.setContentDescription(name);
        ViewCompat.setTooltipText(box, name);
        // P583: the concept's line icon (cv_*) tinted plain (a tonal mono filter would fade its white strokes to a third)
        icon.clearColorFilter();
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(on ? accent : ShadeStyle.TEXT));
        icon.setActivated(on);
        if (icon.getParent() == box) icon.setDuplicateParentStateEnabled(true);
        box.setBackground(ShadeStyle.pressable(c, on ? onTint(accent) : 0x00000000, 0, 999));
    }

    /** The self-timer in seconds (0: off). */
    static int timerSeconds(Context c) {
        int[] values = c.getResources().getIntArray(R.array.countdowntimer_entryvalues);
        int i = PreferenceKeys.getCountdownTimerIndex();
        return i >= 0 && i < values.length ? values[i] : 0;
    }

    /** The first level 0..3 at which the bar fits its width (3 when none does: then it is as small as it gets). */
    static void fit(LayoutMainTopbarBinding tb) {
        View root = tb.getRoot();
        int width = root.getWidth();
        if (width <= 0) return;
        for (int level = 0; level < LEVELS; level++) {
            apply(tb, level);
            if (need(tb) <= width || level == LEVELS - 1) {
                root.setTag(R.id.lut_button, level);
                return;
            }
        }
    }

    /** The bar's level after the last fit (tests). */
    static int level(LayoutMainTopbarBinding tb) {
        Object l = tb.getRoot().getTag(R.id.lut_button);
        return l instanceof Integer ? (Integer) l : 0;
    }

    /** The width the items need at their current sizes. */
    private static int need(LayoutMainTopbarBinding tb) {
        ViewGroup row = (ViewGroup) tb.getRoot();
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int sum = row.getPaddingLeft() + row.getPaddingRight();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v.getVisibility() == View.GONE) continue;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int ws = lp.width >= 0 ? View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY) : unspecified;
            v.measure(ws, View.MeasureSpec.makeMeasureSpec(row.getHeight() > 0 ? row.getHeight() : 1000, View.MeasureSpec.AT_MOST));
            sum += v.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        }
        return sum;
    }

    /** The first visible item of the row (it takes no gap before it). */
    @Nullable
    private static View first(LayoutMainTopbarBinding tb) {
        ViewGroup row = (ViewGroup) tb.getRoot();
        for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).getVisibility() != View.GONE) return row.getChildAt(i);
        return null;
    }

    private static void apply(LayoutMainTopbarBinding tb, int level) {
        Context c = tb.getRoot().getContext();
        ViewGroup row = (ViewGroup) tb.getRoot();
        View lead = first(tb);
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.setMarginStart(v == lead ? 0 : UiScale.dp(c, GAPS[level]));
            v.setLayoutParams(lp);
        }
        int rowPad = UiScale.dp(c, level >= 3 ? 4 : level >= 2 ? 6 : 10);
        row.setPaddingRelative(rowPad, row.getPaddingTop(), rowPad, 0);
        String format = String.valueOf(tb.formatBadge.getTag(R.id.format_badge));
        tb.formatBadge.setText(level >= 2 ? format.replace(" + ", "+") : format);
        // the concept: a format longer than five letters a little smaller
        float formatSp = (format.length() > 5 ? 11.5f : 13f) - (level >= 3 ? 1f : 0f);
        // P583: the concept's text (px as dp x the chrome's scale, Roboto Flex): 13 / 11.5 heavy over 9 semibold, DCG 13, the badge 10
        text(tb.formatBadge, formatSp, com.particlesdevs.photoncamera.ui.style.Fonts.BOLD);
        text(tb.sizeBadge, level >= 3 ? 8.5f : 9f, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
        text(tb.dcgBadge, level >= 3 ? 12f : 13f, com.particlesdevs.photoncamera.ui.style.Fonts.BOLD);
        text(tb.timerBadge, 10, com.particlesdevs.photoncamera.ui.style.Fonts.HEAVY);
    }

    /** A label of the row at the concept's {@code px} (no autosize: the row steps down by itself). */
    private static void text(TextView v, float px, int weight) {
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(v, androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE);
        ConceptStyle.text(v, px, weight);
    }

    /**
     * The layout's dp sizes, margins and paddings times {@code k} (the bar's own height too: it is the concept's 64dp), once;
     * text sizes are set by {@link #apply}.
     */
    private static void scaleTree(View v, float k, boolean root) {
        if (Math.abs(k - 1f) < 1e-3f) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            if (lp.width > 0) lp.width = Math.round(lp.width * k);
            if (lp.height > 0) lp.height = Math.round(lp.height * k);
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                int start = m.getMarginStart(), end = m.getMarginEnd();
                m.topMargin = Math.round(m.topMargin * k);
                m.bottomMargin = Math.round(m.bottomMargin * k);
                m.setMarginStart(Math.round(start * k));
                m.setMarginEnd(Math.round(end * k));
            }
            v.setLayoutParams(lp);
        }
        v.setPaddingRelative(Math.round(v.getPaddingStart() * k), Math.round(v.getPaddingTop() * k),
                Math.round(v.getPaddingEnd() * k), Math.round(v.getPaddingBottom() * k));
        v.setMinimumWidth(Math.round(v.getMinimumWidth() * k));
        v.setMinimumHeight(Math.round(v.getMinimumHeight() * k));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scaleTree(g.getChildAt(i), k, false);
        }
    }
}
