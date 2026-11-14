package com.scamera.app.ui.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.scamera.app.R;
import com.scamera.app.processing.PhotoFormat;
import com.scamera.app.settings.FormatChoice;
import com.scamera.app.settings.PhotoStyle;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.PreferenceNumber;
import com.scamera.app.settings.SettingsLevel;
import com.scamera.app.ui.Motion;
import com.scamera.app.ui.controls.IosSegmented;
import com.scamera.app.util.Lang;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * P708: the setup wizard - at the first start of a fresh install (over the camera, once the camera may run) and again from the
 * settings' «Мастер настройки». A sheet in the camera sheets' look with up to three steps: the settings mode (Simple / Custom /
 * Advanced); in the Simple mode fast or quality processing (the processing mode) with RAW kept or not (the save mode of «Формат
 * фото»), then the photo style (PhotoStyle) and the watermark; in the Custom mode where its set starts (the essentials or every
 * setting), then «Свой набор» opens. It writes the mode, and in the Simple mode only the values chosen differently from the stored
 * ones; nothing else changes (every other setting keeps its value and this phone's default).
 * <p>
 * Motion (Animators only: the system's animator scale and «remove animations» apply, without motion each step is there at once; no
 * blur or shadows - transforms and alpha, cheap on old phones): a step slides in from the side it comes from on a soft spring while
 * the one before slides out and fades, the sheet's height follows it; its rows rise one after another; the chosen card takes the
 * accent's tint, ring and tick with a small pulse; the progress pill glides to the step.
 */
public final class SettingsWizard {
    private SettingsWizard() {}

    static final String PROCESSING = "pref_scam_hybrid_processing", FAST = "fast", QUALITY = "quality";
    static final String WATERMARK = PreferenceKeys.Key.KEY_SHOW_WATERMARK.mValue;
    /** The settings opened by the first-run wizard's Custom choice: «Свой набор» over the main page. */
    public static final String EXTRA_CUSTOM_SET = "settings_custom_set";
    /** A step coming in (the settings' open spring) and the cards' choice. */
    private static final com.scamera.app.ui.settings.SettingsMotion.Spring STEP = new SettingsMotion.Spring(0.4f, 0.86f),
            PICK = new SettingsMotion.Spring(0.32f, 0.72f);
    private static WeakReference<BottomSheetDialog> shown = new WeakReference<>(null);

    // ───── when

    /** The fresh install's mark (MigrationManager) is there: the wizard has not been finished or closed yet. */
    public static boolean pending(@Nullable SharedPreferences prefs) {
        if (prefs == null) return false;
        try {
            return prefs.getBoolean(SettingsLevel.WIZARD_KEY, false);
        } catch (ClassCastException e) {
            return false;
        }
    }

    /** Shows the wizard of a fresh install over {@code activity} when it is due and none is open; whether it did. */
    public static boolean maybeShowFirstRun(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!pending(prefs) || showing()) return false;
        show(activity, true, null);
        return true;
    }

    /** A wizard is open. */
    public static boolean showing() {
        final BottomSheetDialog d = shown.get();
        return d != null && d.isShowing();
    }

    // ───── what it writes

    /** The wizard's choices (start: the values stored now). */
    static final class Choice {
        /** SimpleMode.SIMPLE / CUSTOM / ADVANCED. */
        int mode;
        boolean raw, watermark, startAll;
        String processing;
        int style;

        static Choice of(SharedPreferences prefs) {
            final Choice c = new Choice();
            c.mode = pending(prefs) ? SimpleMode.SIMPLE : SimpleMode.mode(prefs);
            c.processing = QUALITY.equals(text(prefs, PROCESSING, FAST)) ? QUALITY : FAST;
            c.raw = saveMode(prefs) != 0;
            c.watermark = PreferenceNumber.bool(prefs.getAll().get(WATERMARK), true);
            c.style = PhotoStyle.current(prefs);
            return c;
        }
    }

    private static String text(SharedPreferences prefs, String key, String fallback) {
        final Object v = prefs.getAll().get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    /** The save mode (0 photo only, 1 RAW + photo, 2 RAW only) as stored. */
    private static int saveMode(SharedPreferences prefs) {
        try {
            return FormatChoice.current().saveMode;
        } catch (RuntimeException notReady) {   // the settings not set up in this process: the stored number
            return (int) PreferenceNumber.read(prefs.getAll().get(PreferenceKeys.Key.KEY_SAVE_RAW.mValue), 0);
        }
    }

    /**
     * Stores {@code c}: the mode and the wizard done; in the Simple mode the processing mode, RAW, the watermark and the style where
     * they differ from the stored ones (each in the form it is stored in); in the Custom mode where its set starts (the essentials:
     * the user's choices dropped; every setting: taken from the settings' tree when it is built, CustomSetFragment.startAll).
     */
    static void apply(SharedPreferences prefs, Choice c) {
        final boolean simple = c.mode == SimpleMode.SIMPLE;
        final SharedPreferences.Editor e = prefs.edit();
        e.putString(SettingsLevel.MODE_KEY, simple ? SettingsLevel.SIMPLE_MODE
                : c.mode == SimpleMode.CUSTOM ? SettingsLevel.CUSTOM_MODE : SettingsLevel.ADVANCED_MODE);
        e.putBoolean(SettingsLevel.WIZARD_KEY, false);
        if (c.mode == SimpleMode.CUSTOM) {
            if (c.startAll) e.putString(com.scamera.app.settings.CustomSet.START_KEY, com.scamera.app.settings.CustomSet.START_ALL);
            else e.remove(com.scamera.app.settings.CustomSet.START_KEY).remove(com.scamera.app.settings.CustomSet.SHOWN_KEY)
                    .remove(com.scamera.app.settings.CustomSet.HIDDEN_KEY);
        }
        final Choice now = Choice.of(prefs);
        if (simple && !c.processing.equals(now.processing)) e.putString(PROCESSING, c.processing);
        if (simple && c.watermark != now.watermark) {
            final Object stored = prefs.getAll().get(WATERMARK);
            if (stored instanceof String) e.putString(WATERMARK, c.watermark ? "1" : "0");
            else e.putBoolean(WATERMARK, c.watermark);
        }
        e.apply();
        if (!simple) return;
        if (c.raw != now.raw) storeRaw(prefs, c.raw);
        if (c.style != now.style) PhotoStyle.apply(prefs, c.style);
    }

    /** RAW kept beside the photo (RAW + the photo's codec) or not (the photo alone; «RAW only» becomes the stored codec). */
    private static void storeRaw(SharedPreferences prefs, boolean raw) {
        try {
            final FormatChoice now = FormatChoice.current();
            final PhotoFormat codec = now.codecToStore(PreferenceKeys.getChosenPhotoFormat());
            FormatChoice.store(FormatChoice.of(raw ? 1 : 0, codec));
        } catch (RuntimeException notReady) {
            prefs.edit().putString(PreferenceKeys.Key.KEY_SAVE_RAW.mValue, raw ? "1" : "0").apply();
        }
    }

    /** The main page's rows the wizard writes show their stored values again (the mode switch, the processing mode). */
    static void syncRows(@Nullable PreferenceGroup page, SharedPreferences prefs) {
        if (page == null) return;
        for (String key : new String[]{SettingsLevel.MODE_ROW, PROCESSING}) {
            final Preference p = page.findPreference(key);
            if (!(p instanceof ListPreference)) continue;
            final Object v = prefs.getAll().get(key);
            if (v != null && !String.valueOf(v).equals(((ListPreference) p).getValue())) ((ListPreference) p).setValue(String.valueOf(v));
        }
    }

    // ───── the sheet

    /** The wizard over {@code activity} ({@code firstRun}: a fresh install's); {@code done} after it closed. */
    public static BottomSheetDialog show(Activity activity, boolean firstRun, @Nullable Runnable done) {
        final Wizard w = new Wizard(activity, firstRun, done);
        final BottomSheetDialog d = w.dialog;
        shown = new WeakReference<>(d);
        DialogStyle.track(d);
        d.show();
        w.entered();
        return d;
    }

    /** One wizard on screen. */
    static final class Wizard {
        final Activity activity;
        final boolean firstRun;
        @Nullable final Runnable done;
        final SharedPreferences prefs;
        final Choice choice;
        final BottomSheetDialog dialog;
        final int accent;
        final FrameLayout pager;
        final LinearLayout dots;
        /** Each progress dot's colour now (its glide starts from it). */
        final int[] dotColours = {0x40FFFFFF, 0x40FFFFFF, 0x40FFFFFF};
        final TextView back, next;
        int step;
        boolean finished;

        Wizard(Activity activity, boolean firstRun, @Nullable Runnable done) {
            this.activity = activity;
            this.firstRun = firstRun;
            this.done = done;
            prefs = PreferenceManager.getDefaultSharedPreferences(activity);
            choice = Choice.of(prefs);
            if (firstRun) choice.mode = SimpleMode.SIMPLE;   // the fresh install's mode: the wizard proposes it
            final Context c = activity;
            accent = SettingsStyle.accent(c);
            final LinearLayout body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setTag("settings_wizard");
            body.setBackground(SettingsStyle.shape(c, SettingsStyle.PAGE, 0, 36));
            body.setPadding(dp(16), dp(12), dp(16), dp(18));
            final View grab = new View(c);
            grab.setBackground(SettingsStyle.shape(c, 0x4DFFFFFF, 0, 2.5f));
            final LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(36), dp(5));
            gp.gravity = Gravity.CENTER_HORIZONTAL;
            gp.bottomMargin = dp(14);
            body.addView(grab, gp);
            dots = new LinearLayout(c);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            dots.setTag("wizard_progress");
            for (int i = 0; i < 3; i++) {
                final View dot = new View(c);
                dot.setBackground(SettingsStyle.shape(c, 0x40FFFFFF, 0, 3.5f));
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
                lp.setMarginStart(dp(3));
                lp.setMarginEnd(dp(3));
                dots.addView(dot, lp);
            }
            final LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
            pp.bottomMargin = dp(10);
            body.addView(dots, pp);
            pager = new FrameLayout(c);
            pager.setClipChildren(true);
            body.addView(pager, new LinearLayout.LayoutParams(-1, -2));
            final LinearLayout footer = new LinearLayout(c);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            back = SettingsStyle.quietCapsule(c, Lang.t(c, "Назад", "Back"), SettingsStyle.ROW_TITLE);
            back.setTag("wizard_back");
            next = SettingsStyle.capsule(c, Lang.t(c, "Далее", "Next"), accent);
            next.setTag("wizard_next");
            final LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
            bp.setMarginEnd(dp(10));
            footer.addView(back, bp);
            footer.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
            final LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
            fp.topMargin = dp(18);
            body.addView(footer, fp);
            Motion.press(back);
            Motion.press(next);
            back.setOnClickListener(v -> { if (step > 0) go(step - 1); });
            next.setOnClickListener(v -> {
                if (step + 1 < steps()) go(step + 1);
                else finish();
            });
            // the camera sheets' floating card: 8dp from the screen's sides and bottom
            final FrameLayout frame = new FrameLayout(c);
            frame.setPadding(dp(8), 0, dp(8), dp(8));
            frame.addView(body, new FrameLayout.LayoutParams(-1, -2));
            dialog = SettingsStyle.sheet(c, frame);
            dialog.setOnDismissListener(x -> closed());
            pager.addView(page(0), new FrameLayout.LayoutParams(-1, -2));
            footer(false);
            progress(false);
        }

        int dp(float v) {
            return SettingsStyle.dp(activity, v);
        }

        /** Steps of the chosen mode: the mode alone for Advanced, its start for Custom, three for Simple. */
        int steps() {
            return choice.mode == SimpleMode.SIMPLE ? 3 : choice.mode == SimpleMode.CUSTOM ? 2 : 1;
        }

        /** The sheet is on screen: the first step's rows rise in. */
        void entered() {
            final View first = pager.getChildCount() > 0 ? pager.getChildAt(0) : null;
            if (first instanceof ViewGroup) rise((ViewGroup) first, 120);
        }

        // ── steps

        View page(int i) {
            final Context c = activity;
            final LinearLayout p = new LinearLayout(c);
            p.setOrientation(LinearLayout.VERTICAL);
            p.setTag("wizard_step_" + i);
            if (i == 0) {
                p.addView(title(firstRun ? Lang.t(c, "Добро пожаловать в SCAMERA", "Welcome to SCAMERA")
                        : Lang.t(c, "Мастер настройки", "Setup assistant")));
                p.addView(lead(Lang.t(c, "Как показывать настройки?", "How should the settings look?")));
                final Card simple = new Card(c, "wizard_simple", R.drawable.cs_ic_sliders, Lang.t(c, "Простой", "Simple"),
                        Lang.t(c, "Только главное: обработка, формат, RAW, стиль и водяной знак. Остальное уже настроено для вашего телефона.",
                                "Only the essentials: processing, format, RAW, style and watermark. Everything else is already tuned for your phone."));
                final Card custom = new Card(c, "wizard_custom", R.drawable.cs_ic_toggle, Lang.t(c, "Свой", "Custom"),
                        Lang.t(c, "Вы сами выбираете, какие настройки видны: по одной или группами.",
                                "You choose which settings are shown: one by one or by groups."));
                final Card advanced = new Card(c, "wizard_advanced", R.drawable.cs_ic_list, Lang.t(c, "Расширенный", "Advanced"),
                        Lang.t(c, "Все настройки, как раньше: склейка, тон, сенсоры и модули.",
                                "Every setting as before: merge, tone, sensors and modules."));
                final int[] modes = {SimpleMode.SIMPLE, SimpleMode.CUSTOM, SimpleMode.ADVANCED};
                group(p, new Card[]{simple, custom, advanced}, choice.mode == SimpleMode.SIMPLE ? 0 : choice.mode == SimpleMode.CUSTOM ? 1 : 2,
                        k -> {
                            choice.mode = modes[k];
                            footer(true);
                            progress(true);
                        });
                p.addView(note(Lang.t(c, "Режим можно сменить в настройках в любой момент: значения не теряются.",
                        "You can change the mode in the settings at any time: no value is lost.")));
            } else if (i == 1 && choice.mode == SimpleMode.CUSTOM) {
                p.addView(title(Lang.t(c, "Свой набор", "Custom set")));
                p.addView(lead(Lang.t(c, "С чего начать?", "Where to start?")));
                final Card essentials = new Card(c, "wizard_start_essentials", R.drawable.cs_ic_sliders,
                        Lang.t(c, "С главного", "From the essentials"),
                        Lang.t(c, "Настройки простого режима; добавьте нужные.", "The Simple mode's settings; add the ones you need."));
                final Card everything = new Card(c, "wizard_start_all", R.drawable.cs_ic_list, Lang.t(c, "Со всех", "From everything"),
                        Lang.t(c, "Все настройки; уберите лишние.", "Every setting; remove the ones you do not need."));
                group(p, new Card[]{essentials, everything}, choice.startAll ? 1 : 0, k -> choice.startAll = k == 1);
                p.addView(note(Lang.t(c, "После «Готово» откроется список настроек. Его можно поменять потом: Настройки → «Настроить…».",
                        "After \"Done\" the list of settings opens. You can change it later: Settings → \"Customize…\".")));
            } else if (i == 1) {
                p.addView(title(Lang.t(c, "Обработка снимков", "Photo processing")));
                p.addView(lead(Lang.t(c, "Что важнее?", "What matters more?")));
                final Card fast = new Card(c, "wizard_fast", R.drawable.cs_ic_flash, Lang.t(c, "Скорость", "Speed"),
                        Lang.t(c, "Снимок готов быстрее, кадров в склейке меньше.", "The shot is ready sooner, fewer frames are merged."));
                final Card quality = new Card(c, "wizard_quality", R.drawable.cs_ic_merge, Lang.t(c, "Качество", "Quality"),
                        Lang.t(c, "Больше деталей и меньше шума, обработка дольше.", "More detail and less noise, longer processing."));
                group(p, new Card[]{fast, quality}, QUALITY.equals(choice.processing) ? 1 : 0, k -> choice.processing = k == 1 ? QUALITY : FAST);
                p.addView(section(Lang.t(c, "Сохранять RAW (DNG)", "Keep RAW (DNG)")));
                p.addView(segmented("wizard_raw", new String[]{Lang.t(c, "Нет", "No"), Lang.t(c, "Да", "Yes")}, choice.raw ? 1 : 0,
                        k -> choice.raw = k == 1));
                p.addView(note(Lang.t(c, "RAW — исходник для обработки на компьютере; занимает больше места.",
                        "RAW is the source file for editing on a computer; it takes more space.")));
            } else {
                p.addView(title(Lang.t(c, "Вид снимков", "Look of the photos")));
                p.addView(section(Lang.t(c, "Стиль", "Style")));
                // P718: the tone presets Насыщенный / Обычный / Нейтральный, each card with its thumbnail (StyleCards)
                p.addView(StyleCards.row(c, "wizard_style_", choice.style, k -> choice.style = k), new LinearLayout.LayoutParams(-1, -2));
                p.addView(section(Lang.t(c, "Водяной знак", "Watermark")));
                p.addView(segmented("wizard_watermark", new String[]{Lang.t(c, "Выкл.", "Off"), Lang.t(c, "Вкл.", "On")},
                        choice.watermark ? 1 : 0, k -> choice.watermark = k == 1));
                p.addView(note(Lang.t(c, "Подпись с камерой и параметрами съёмки в углу снимка.",
                        "A caption with the camera and the shot's settings in a corner of the photo.")));
            }
            return p;
        }

        TextView title(CharSequence s) {
            final TextView t = SettingsStyle.text(activity, s, 22, SettingsStyle.ROW_TITLE);
            com.scamera.app.ui.style.Fonts.apply(t, com.scamera.app.ui.style.Fonts.BOLD);
            t.setPadding(dp(4), 0, dp(4), dp(4));
            return t;
        }

        TextView lead(CharSequence s) {
            final TextView t = SettingsStyle.text(activity, s, 15, SettingsStyle.SUMMARY);
            t.setPadding(dp(4), 0, dp(4), dp(14));
            return t;
        }

        TextView section(CharSequence s) {
            final TextView t = SettingsStyle.text(activity, s, 12, SettingsStyle.SECTION);
            t.setAllCaps(true);
            t.setLetterSpacing(1f / 12f);
            com.scamera.app.ui.style.Fonts.apply(t, com.scamera.app.ui.style.Fonts.SEMIBOLD);
            t.setPadding(dp(6), dp(16), 0, dp(8));
            return t;
        }

        TextView note(CharSequence s) {
            final TextView t = SettingsStyle.text(activity, s, 13, SettingsStyle.SUMMARY);
            t.setPadding(dp(6), dp(10), dp(6), 0);
            return t;
        }

        /** Cards of which one is chosen ({@code selected}); a tap chooses another (the cards cross-fade their marks). */
        void group(LinearLayout p, Card[] all, int selected, java.util.function.IntConsumer pick) {
            for (int k = 0; k < all.length; k++) {
                final int index = k;
                all[k].select(k == selected, false);
                all[k].view.setOnClickListener(v -> {
                    if (all[index].on) return;
                    for (int j = 0; j < all.length; j++) if (j != index && all[j].on) all[j].select(false, true);
                    all[index].select(true, true);
                    pick.accept(index);
                });
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (k > 0) lp.topMargin = dp(10);
                p.addView(all[k].view, lp);
            }
        }

        IosSegmented segmented(String tag, String[] labels, int selected, java.util.function.IntConsumer pick) {
            final IosSegmented seg = new IosSegmented(activity);
            seg.setTag(tag);
            seg.setColours(accent, 0x3D767680, 0xFF636366);
            seg.setItems(labels);
            for (int i = 0; i < seg.getChildCount(); i++) {
                final View b = seg.getChildAt(i);
                b.setTag(tag + "_" + i);
                if (b instanceof TextView) {
                    com.scamera.app.ui.style.Fonts.apply((TextView) b, com.scamera.app.ui.style.Fonts.SEMIBOLD);
                    ((TextView) b).setMinHeight(dp(36));
                }
            }
            seg.setSelected(selected, false);
            seg.setListener((s, index) -> pick.accept(index));
            return seg;
        }

        // ── moving between steps

        /** To step {@code to}: the step slides in from its side, the old one out; the sheet's height follows. */
        void go(int to) {
            if (to == step || to < 0 || to >= steps()) return;
            final int dir = to > step ? 1 : -1;
            step = to;
            final View old = pager.getChildCount() > 0 ? pager.getChildAt(pager.getChildCount() - 1) : null;
            final View next = page(to);
            final int w = pager.getWidth();
            final boolean motion = SettingsMotion.enabled(pager) && w > 0 && old != null;
            for (int i = pager.getChildCount() - 1; i >= 0; i--) {   // a step still leaving goes at once
                final View v = pager.getChildAt(i);
                if (v != old) { v.animate().cancel(); pager.removeView(v); }
            }
            pager.addView(next, new FrameLayout.LayoutParams(-1, -2));
            footer(true);
            progress(true);
            if (!motion) {
                if (old != null) pager.removeView(old);
                return;
            }
            final int h0 = pager.getHeight();
            next.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int h1 = next.getMeasuredHeight();
            old.animate().cancel();
            old.animate().translationX(-dir * w * .28f).alpha(0f).scaleX(.97f).scaleY(.97f).setStartDelay(0).setDuration(200)
                    .setInterpolator(SettingsMotion.EXIT).withEndAction(() -> pager.removeView(old)).start();
            next.setTranslationX(dir * w * .34f);
            next.setAlpha(0f);
            next.animate().translationX(0f).alpha(1f).setStartDelay(40).setDuration(STEP.durationMs).setInterpolator(STEP)
                    .withEndAction(() -> Motion.rest(next)).start();
            if (next instanceof ViewGroup) rise((ViewGroup) next, 90);
            // the height glides from the old step's to the new one's, then wraps again
            final ViewGroup.LayoutParams lp = pager.getLayoutParams();
            final ValueAnimator h = ValueAnimator.ofInt(h0, h1);
            h.setDuration(STEP.durationMs);
            h.setInterpolator(SettingsMotion.EASE_OUT);
            h.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                pager.setLayoutParams(lp);
            });
            h.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    pager.setLayoutParams(lp);
                }
            });
            h.start();
        }

        /** A step's rows rise in one after another (Motion.enter), after {@code delay}. */
        void rise(ViewGroup p, long delay) {
            final List<View> rows = new ArrayList<>();
            for (int i = 0; i < p.getChildCount(); i++) rows.add(p.getChildAt(i));
            if (!SettingsMotion.enabled(p)) return;
            for (View v : rows) v.setAlpha(0f);
            Motion.enter(rows, delay, 35);
        }

        /** «Назад» only after the first step; «Далее», or «Готово» on the last step (the label cross-fades). */
        void footer(boolean animate) {
            final boolean last = step + 1 >= steps();
            final String label = last ? Lang.t(activity, "Готово", "Done") : Lang.t(activity, "Далее", "Next");
            if (!label.contentEquals(next.getText())) {
                if (animate && SettingsMotion.enabled(next)) {
                    next.animate().cancel();
                    next.animate().alpha(.35f).setDuration(90).setInterpolator(SettingsMotion.EXIT).withEndAction(() -> {
                        next.setText(label);
                        next.animate().alpha(1f).setDuration(160).setInterpolator(SettingsMotion.EASE_OUT).start();
                    }).start();
                } else {
                    next.setText(label);
                    next.setAlpha(1f);
                }
            }
            final boolean backShown = step > 0;
            if (animate) Motion.fade(back, backShown);
            else back.setVisibility(backShown ? View.VISIBLE : View.GONE);
        }

        /** The progress: the step's dot a 22dp accent pill, the others 7dp dots; steps the mode skips dimmed. */
        void progress(boolean animate) {
            for (int i = 0; i < dots.getChildCount(); i++) {
                final View dot = dots.getChildAt(i);
                final boolean current = i == step, live = i < steps();
                final int w1 = dp(current ? 22 : 7);
                final int c1 = current ? accent : 0x40FFFFFF;
                final float a1 = live ? 1f : .3f;
                final Object running = dot.getTag(R.id.settings_rise);
                if (running instanceof Animator) ((Animator) running).cancel();
                final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
                final GradientDrawable bg = (GradientDrawable) dot.getBackground();
                final int c0 = dotColours[i];
                dotColours[i] = c1;
                if (!animate || !SettingsMotion.enabled(dot)) {
                    lp.width = w1;
                    dot.setLayoutParams(lp);
                    bg.setColor(c1);
                    dot.setAlpha(a1);
                    continue;
                }
                final int w0 = lp.width;
                final float a0 = dot.getAlpha();
                final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
                a.setDuration(SettingsMotion.GLIDE.durationMs);
                a.setInterpolator(SettingsMotion.GLIDE);
                a.addUpdateListener(x -> {
                    final float k = (float) x.getAnimatedValue();
                    lp.width = Math.max(1, Math.round(w0 + (w1 - w0) * k));
                    dot.setLayoutParams(lp);
                    final float f = Math.max(0f, Math.min(1f, x.getAnimatedFraction()));
                    bg.setColor(SettingsMotion.mix(c0, c1, f));
                    dot.setAlpha(a0 + (a1 - a0) * f);
                });
                dot.setTag(R.id.settings_rise, a);
                a.start();
            }
        }

        // ── the end

        void finish() {
            if (finished) return;
            finished = true;
            apply(prefs, choice);
            dialog.dismiss();
            if (choice.mode != SimpleMode.CUSTOM) return;
            // the Custom mode: its set opens (the settings' own page; from the camera the settings first)
            if (activity instanceof SettingsActivity) {
                final View decor = activity.getWindow().getDecorView();
                decor.post(((SettingsActivity) activity)::openCustomSet);
            } else if (!activity.isFinishing()) {
                activity.startActivity(new android.content.Intent(activity, SettingsActivity.class).putExtra(EXTRA_CUSTOM_SET, true));
            }
        }

        /** Closed: finished, or swiped / backed away (the first run's mark goes too: the wizard is not asked again). */
        void closed() {
            if (!finished && firstRun) prefs.edit().putBoolean(SettingsLevel.WIZARD_KEY, false).apply();
            if (shown.get() == dialog) shown = new WeakReference<>(null);
            if (done != null) done.run();
        }
    }

    /**
     * A choice card: an icon tile, the title and a line (none for the small cards), a tick. Chosen, its fill takes the accent's tint
     * and a ring, the tick pops in, the card pulses a little; the change cross-fades on a spring.
     */
    static final class Card {
        final LinearLayout view;
        /** The icon, or (icon 0) the picture slot. */
        final ImageView image;
        final ImageView tick;
        final GradientDrawable bg;
        final int accent;
        boolean on;
        float k;   // 0 not chosen .. 1 chosen

        Card(Context c, String tag, int icon, CharSequence title, @Nullable CharSequence line) {
            accent = SettingsStyle.accent(c);
            view = new LinearLayout(c);
            view.setTag(tag);
            view.setOrientation(line == null ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            view.setGravity(line == null ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
            final int pad = SettingsStyle.dp(c, line == null ? 12 : 14);
            view.setPadding(pad, pad, pad, pad);
            view.setClickable(true);
            view.setFocusable(true);
            bg = SettingsStyle.shape(c, SettingsStyle.ROW, 0, 18);
            view.setBackground(bg);
            final ImageView ic = new ImageView(c);
            ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final int s;
            if (icon != 0) {
                ic.setImageResource(icon);
                ic.setColorFilter(accent);
                s = SettingsStyle.dp(c, 24);
            } else {
                // P718: a picture slot (StyleCards: the preset's thumbnail, LookThumbs) - the LUT tray's thumbnail size and corner
                s = SettingsStyle.dp(c, com.scamera.app.ui.camera.LutTray.THUMB_DP);
                ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                final GradientDrawable ph = SettingsStyle.shape(c, SettingsStyle.HAIRLINE, 0, com.scamera.app.ui.camera.LutTray.THUMB_CORNER_DP);
                ic.setBackground(ph);
                ic.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                ic.setClipToOutline(true);
            }
            image = ic;
            final LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(s, s);
            if (line != null) il.setMarginEnd(SettingsStyle.dp(c, 14));
            else il.bottomMargin = SettingsStyle.dp(c, icon != 0 ? 6 : 8);
            view.addView(ic, il);
            final TextView t = SettingsStyle.text(c, title, line == null ? 15 : 17, SettingsStyle.ROW_TITLE);
            com.scamera.app.ui.style.Fonts.apply(t, com.scamera.app.ui.style.Fonts.SEMIBOLD);
            if (line == null) {
                t.setGravity(Gravity.CENTER);
                view.addView(t, new LinearLayout.LayoutParams(-2, -2));
            } else {
                final LinearLayout texts = new LinearLayout(c);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(t);
                final TextView l = SettingsStyle.text(c, line, 13, SettingsStyle.SUMMARY);
                l.setPadding(0, SettingsStyle.dp(c, 3), 0, 0);
                texts.addView(l);
                view.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            }
            tick = new ImageView(c);
            tick.setImageResource(R.drawable.ios_menu_check);
            tick.setColorFilter(accent);
            tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final int ts = SettingsStyle.dp(c, 20);
            final LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ts, ts);
            if (line != null) tl.setMarginStart(SettingsStyle.dp(c, 10));
            else tl.topMargin = SettingsStyle.dp(c, 4);
            view.addView(tick, tl);
            view.setContentDescription(line == null ? title : title + ". " + line);
            Motion.press(view, .97f);
        }

        /** Chosen or not; {@code animate}: the change runs on the pick spring (else at once). */
        void select(boolean chosen, boolean animate) {
            on = chosen;
            view.setSelected(chosen);
            final Object running = view.getTag(R.id.settings_rise);
            if (running instanceof Animator) ((Animator) running).cancel();
            final float to = chosen ? 1f : 0f;
            if (!animate || !SettingsMotion.enabled(view)) {
                paint(to);
                tick.setScaleX(1f);
                tick.setScaleY(1f);
                return;
            }
            final float from = k;
            final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(PICK.durationMs);
            a.setInterpolator(SettingsMotion.EASE_OUT);
            a.addUpdateListener(x -> paint(from + (to - from) * (float) x.getAnimatedValue()));
            view.setTag(R.id.settings_rise, a);
            a.start();
            if (chosen) {
                Motion.pop(tick);
                // a small pulse of the card: up past 1 and back on the spring
                view.animate().cancel();
                view.setScaleX(1f);
                view.setScaleY(1f);
                final ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f);
                pulse.setDuration(PICK.durationMs);
                pulse.addUpdateListener(x -> {
                    final float f = x.getAnimatedFraction();
                    final float s = 1f + .025f * (float) Math.sin(Math.PI * Math.min(1f, f * 1.6f)) * (1f - f);
                    view.setScaleX(s);
                    view.setScaleY(s);
                });
                pulse.start();
            }
        }

        /** The card at {@code value} between not chosen (0) and chosen (1): fill, ring and tick. */
        private void paint(float value) {
            k = value;
            final float v = Math.max(0f, Math.min(1f, value));
            bg.setColor(SettingsMotion.mix(SettingsStyle.ROW, SettingsStyle.tint(accent, .16f), v));
            bg.setStroke(Math.max(1, SettingsStyle.dp(view.getContext(), 2)), SettingsMotion.mix(accent & 0x00FFFFFF, accent, v));
            tick.setAlpha(v);
        }
    }
}
