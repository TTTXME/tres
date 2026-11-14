package com.scamera.app.ui.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import com.scamera.app.R;
import com.scamera.app.settings.CustomSet;
import com.scamera.app.settings.SettingsLevel;
import com.scamera.app.ui.Motion;
import com.scamera.app.ui.settings.custompreferences.SegmentedListPreference;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P708: the settings modes on the settings pages - Simple (SettingsLevel holds the rows' levels), Custom (the user's own set,
 * CustomSet, edited in CustomSetFragment) and Advanced (everything). The main page gets rows of its own - «Режим настроек»
 * (Простой / Свой / Расширенный), «Настроить…» in the Custom mode, the setup wizard again, and in the Simple and Custom modes a
 * banner while a hidden setting is off its recommended default (its reset, with a confirmation, or the Advanced mode). A page
 * filters its rows by the mode's {@link Filter} (Simple: the main page and the pages Simple all the way up; Custom: every page;
 * never a module's pages): SettingsFragment.applyAvailability hides the rows it names, {@link #groups} the groups. Switching the
 * mode only shows or hides rows (the list's item animator lets them rise in and fade out), it never changes a value.
 */
final class SimpleMode {
    private SimpleMode() {}

    /** The settings modes (SettingsLevel.MODE_KEY). */
    static final int ADVANCED = 0, SIMPLE = 1, CUSTOM = 2;

    /** The stored mode (none or unknown: Advanced). */
    static int mode(@Nullable SharedPreferences prefs) {
        if (prefs == null) return ADVANCED;
        try {
            final String m = prefs.getString(SettingsLevel.MODE_KEY, SettingsLevel.ADVANCED_MODE);
            return SettingsLevel.simpleMode(m) ? SIMPLE : SettingsLevel.customMode(m) ? CUSTOM : ADVANCED;
        } catch (ClassCastException e) {
            return ADVANCED;
        }
    }

    /** A mode that hides settings (Simple or Custom) is on. */
    static boolean on(@Nullable SharedPreferences prefs) {
        return mode(prefs) != ADVANCED;
    }

    static void set(SharedPreferences prefs, int mode) {
        prefs.edit().putString(SettingsLevel.MODE_KEY, mode == SIMPLE ? SettingsLevel.SIMPLE_MODE
                : mode == CUSTOM ? SettingsLevel.CUSTOM_MODE : SettingsLevel.ADVANCED_MODE).apply();
    }

    /** The Custom mode's set as stored. */
    static CustomSet custom(SharedPreferences prefs) {
        return new CustomSet(strings(prefs, CustomSet.SHOWN_KEY), strings(prefs, CustomSet.HIDDEN_KEY));
    }

    private static Set<String> strings(SharedPreferences prefs, String key) {
        try {
            final Set<String> v = prefs.getStringSet(key, null);
            return v == null ? new java.util.HashSet<>() : new java.util.HashSet<>(v);
        } catch (ClassCastException e) {
            return new java.util.HashSet<>();
        }
    }

    static void store(SharedPreferences prefs, CustomSet set) {
        prefs.edit().putStringSet(CustomSet.SHOWN_KEY, set.shown()).putStringSet(CustomSet.HIDDEN_KEY, set.hidden()).apply();
    }

    /** Whether {@code page} is filtered in the Simple mode: the main page, or a page that is Simple all the way up. */
    static boolean filters(@Nullable PreferenceGroup page) {
        for (Preference q = page; q != null; q = q.getParent()) {
            if (SettingsLevel.ROOT.equals(q.getKey())) return true;
            if (!SettingsLevel.simple(q.getKey())) return false;
        }
        return page != null && SettingsLevel.ROOT.equals(page.getKey());
    }

    /**
     * What the stored mode hides on {@code page}: null for none (Advanced, a module's pages, a page not Simple in the Simple mode -
     * opened from a link, it shows what it has).
     */
    @Nullable
    static Filter filter(@Nullable SharedPreferences prefs, @Nullable PreferenceGroup page, boolean modulePage) {
        if (page == null || modulePage) return null;
        final int m = mode(prefs);
        if (m == SIMPLE) return filters(page) ? new Filter(SIMPLE, page, null) : null;
        if (m == CUSTOM) return new Filter(CUSTOM, page, custom(prefs));
        return null;
    }

    /** Whether the Simple mode hides {@code p} on {@code page}: it or a group between it and the page is not SIMPLE. */
    static boolean hides(Preference p, PreferenceGroup page) {
        for (Preference q = p; q != null && q != page; q = q.getParent())
            if (!SettingsLevel.simple(q.getKey()) && !SettingsLevel.modeRow(q.getKey())) return true;
        return false;
    }

    /** {@code p}'s place in the Simple set: it and every group and page above it are SIMPLE (SettingsLevel.shown). */
    static boolean simpleLevel(Preference p) {
        for (Preference q = p; q != null && !SettingsLevel.ROOT.equals(q.getKey()); q = q.getParent())
            if (!SettingsLevel.simple(q.getKey())) return false;
        return true;
    }

    /**
     * A setting the user picks in the Custom mode: a row with a key that is not a group, or a page / group without settings of its
     * own (the lab, a page built when opened). The groups and pages holding settings are shown while one of them is.
     */
    static boolean setting(Preference p) {
        return p.getKey() != null && !SettingsLevel.modeRow(p.getKey()) && (!(p instanceof PreferenceGroup) || !holds((PreferenceGroup) p));
    }

    /** {@code g} holds a setting (a keyed row or group below it). */
    static boolean holds(PreferenceGroup g) {
        for (int i = 0; i < g.getPreferenceCount(); i++) {
            final Preference c = g.getPreference(i);
            if (c.getKey() != null || c instanceof PreferenceGroup && holds((PreferenceGroup) c)) return true;
        }
        return false;
    }

    /** What a mode hides on one page. */
    static final class Filter {
        final int mode;
        final PreferenceGroup page;
        @Nullable final CustomSet set;
        /** Custom: a group or page shows (one of its settings does); found once per pass. */
        private final java.util.IdentityHashMap<Preference, Boolean> any = new java.util.IdentityHashMap<>();

        Filter(int mode, PreferenceGroup page, @Nullable CustomSet set) {
            this.mode = mode;
            this.page = page;
            this.set = set;
        }

        /** Whether the mode hides {@code p} on the page (it, or a group between it and the page). */
        boolean hides(Preference p) {
            if (mode == SIMPLE) return SimpleMode.hides(p, page);
            for (Preference q = p; q != null && q != page; q = q.getParent()) if (!shows(q)) return true;
            return false;
        }

        private boolean shows(Preference q) {
            final String key = q.getKey();
            if (key != null && SettingsLevel.modeRow(key)) return true;
            if (!(q instanceof PreferenceGroup) || !holds((PreferenceGroup) q)) return key == null || set.visible(key, simpleLevel(q));
            final Boolean known = any.get(q);
            if (known != null) return known;
            boolean shown = false;
            final PreferenceGroup g = (PreferenceGroup) q;
            for (int i = 0; i < g.getPreferenceCount() && !shown; i++) {
                final Preference c = g.getPreference(i);
                if (c.getKey() != null && SettingsLevel.modeRow(c.getKey())) continue;
                if (setting(c)) shown = set.visible(c.getKey(), simpleLevel(c));
                else if (c instanceof PreferenceGroup && holds((PreferenceGroup) c)) shown = shows(c);
            }
            any.put(q, shown);
            return shown;
        }
    }

    /** The key path of {@code p} up to (not with) {@code page}, nearest first (SettingsLevel.shown). */
    static List<String> chain(Preference p, PreferenceGroup page) {
        final List<String> out = new ArrayList<>();
        for (Preference q = p; q != null && q != page; q = q.getParent()) out.add(q.getKey());
        return out;
    }

    /**
     * The groups of {@code group} shown on its page (not the rows of a nested page) hidden by the mode ({@code filter}), or shown
     * again: only those this pass hid ({@code hidden} keeps them), so a group another rule hides stays hidden.
     */
    static void groups(PreferenceGroup group, @Nullable Filter filter, Set<String> hidden) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference p = group.getPreference(i);
            if (!(p instanceof PreferenceGroup) || p instanceof PreferenceScreen) continue;
            final String key = p.getKey();
            if (key != null) {
                if (filter != null && filter.hides(p)) {
                    if (p.isVisible()) {
                        p.setVisible(false);
                        hidden.add(key);
                    }
                } else if (hidden.remove(key)) p.setVisible(true);
            }
            groups((PreferenceGroup) p, filter, hidden);
        }
    }

    // ───── the banner: hidden settings off their recommended default

    /** The persistent rows of {@code root} (the whole tree) {@code filter} hides: the banner's candidates. */
    static List<String> hiddenKeys(PreferenceGroup root, Filter filter) {
        final List<String> out = new ArrayList<>();
        collect(root, filter, out);
        return out;
    }

    private static void collect(PreferenceGroup group, Filter filter, List<String> out) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference p = group.getPreference(i);
            if (p instanceof PreferenceGroup) {
                collect((PreferenceGroup) p, filter, out);
                continue;
            }
            if (p.getKey() != null && p.isPersistent() && filter.hides(p)) out.add(p.getKey());
        }
    }

    /**
     * The settings of {@code root} the stored mode hides whose stored value is off its recommended default (PageReset.defaults);
     * none in the Advanced mode.
     */
    static List<String> changed(Context c, PreferenceGroup root, Map<String, ?> stored, SharedPreferences prefs) {
        final Filter f = filter(prefs, root, false);
        return f == null ? new ArrayList<>() : SettingsLevel.changed(stored, PageReset.defaults(c), hiddenKeys(root, f));
    }

    /** Every setting of {@code root} the user can pick in the Custom mode (the wizard's «Начать со всех»). */
    static List<String> settings(PreferenceGroup root) {
        final List<String> out = new ArrayList<>();
        settings(root, out);
        return out;
    }

    private static void settings(PreferenceGroup g, List<String> out) {
        for (int i = 0; i < g.getPreferenceCount(); i++) {
            final Preference p = g.getPreference(i);
            if (setting(p)) out.add(p.getKey());
            else if (p instanceof PreferenceGroup) settings((PreferenceGroup) p, out);
        }
    }

    /** Puts {@code keys} back to their recommended defaults, each in the type it is stored in now; how many changed. */
    static int reset(Context c, SharedPreferences prefs, List<String> keys) {
        final Map<String, String> defaults = PageReset.defaults(c);
        final Map<String, ?> stored = prefs.getAll();
        final SharedPreferences.Editor e = prefs.edit();
        int n = 0;
        for (String key : keys) {
            final String def = defaults.get(key);
            if (def == null || !stored.containsKey(key)) continue;
            final Object v = SettingsLevel.typed(stored.get(key), def);
            if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
            else if (v instanceof Integer) e.putInt(key, (Integer) v);
            else if (v instanceof Long) e.putLong(key, (Long) v);
            else if (v instanceof Float) e.putFloat(key, (Float) v);
            else e.putString(key, String.valueOf(v));
            n++;
        }
        e.apply();
        return n;
    }

    /** The titles of {@code keys} on {@code root} (the confirmation lists a few). */
    static List<String> titles(PreferenceGroup root, List<String> keys) {
        final List<String> out = new ArrayList<>();
        for (String key : keys) {
            final Preference p = root.findPreference(key);
            final CharSequence t = p == null ? null : p.getTitle();
            out.add(t == null || t.length() == 0 ? key : t.toString());
        }
        return out;
    }

    // ───── the main page's rows

    /** What the rows ask of their page. */
    interface Host {
        /** The setup wizard over the settings. */
        void wizard();

        /** «Настроить…»: the Custom mode's set. */
        void editCustom();

        /** The hidden settings off their default back to it (asked first). */
        void resetHidden();
    }

    /**
     * The mode switch, «Настроить…» (shown in the Custom mode), the wizard row and the (hidden) banner at the top of the main page;
     * returns the banner.
     */
    static Banner addRows(PreferenceGroup root, Host host) {
        final Context c = root.getContext();
        final SegmentedListPreference mode = new SegmentedListPreference(c, null);
        mode.setKey(SettingsLevel.MODE_ROW);
        mode.setTitle(Lang.t(c, "Режим настроек", "Settings mode"));
        mode.setDialogTitle(mode.getTitle());
        mode.setEntries(new CharSequence[]{Lang.t(c, "Простой", "Simple"), Lang.t(c, "Свой", "Custom"),
                Lang.t(c, "Расширенный", "Advanced")});
        mode.setEntryValues(new CharSequence[]{SettingsLevel.SIMPLE_MODE, SettingsLevel.CUSTOM_MODE, SettingsLevel.ADVANCED_MODE});
        mode.setDefaultValue(SettingsLevel.ADVANCED_MODE);
        mode.setIcon(R.drawable.cs_ic_toggle);
        mode.setOrder(-4);
        SettingHelp.describe(mode, Lang.t(c,
                "Простой режим показывает только главное: режим обработки, размер и формат фото, RAW, тон, стиль, плёнку и LUT, "
                        + "водяной знак и основные настройки камеры. Свой — только те настройки, что вы выбрали («Настроить…»); "
                        + "новые настройки следующих версий появляются в нём, если они из главных. Расширенный режим показывает все "
                        + "настройки. Скрытые настройки не сбрасываются: они сохраняют свои значения и значения по умолчанию для "
                        + "этого телефона. Переключение режима ничего не меняет в снимках.",
                "The Simple mode shows only the essentials: the processing mode, photo size and format, RAW, tone, style, film and "
                        + "LUT, the watermark and the basic camera settings. Custom shows only the settings you chose (\"Customize\"); "
                        + "new settings of later versions appear in it when they are essentials. The Advanced mode shows every "
                        + "setting. Hidden settings are not reset: they keep their values and this phone's defaults. Switching "
                        + "the mode changes nothing in the photos."));
        root.addPreference(mode);
        final Preference custom = new Preference(c);
        custom.setKey(SettingsLevel.CUSTOM_ROW);
        custom.setPersistent(false);
        custom.setTitle(Lang.t(c, "Настроить…", "Customize…"));
        custom.setIcon(R.drawable.cs_ic_sliders);
        custom.setOrder(-3);
        custom.setVisible(false);
        SettingHelp.describe(custom, Lang.t(c,
                "Какие настройки показывать в своём режиме: по одной или группами, с поиском. Скрытые настройки сохраняют свои "
                        + "значения.",
                "Which settings the Custom mode shows: one by one or by groups, with a search. Hidden settings keep their values."));
        custom.setOnPreferenceClickListener(p -> {
            host.editCustom();
            return true;
        });
        root.addPreference(custom);
        final Preference wizard = new Preference(c);
        wizard.setKey(SettingsLevel.WIZARD_ROW);
        wizard.setPersistent(false);
        wizard.setTitle(Lang.t(c, "Мастер настройки", "Setup assistant"));
        wizard.setIcon(R.drawable.cs_ic_restore);
        wizard.setOrder(-2);
        SettingHelp.describe(wizard, Lang.t(c,
                "Несколько шагов, как при первом запуске: режим настроек, скорость или качество обработки, RAW, стиль и водяной "
                        + "знак (или начало своего набора). Меняет только то, что вы выберете в нём.",
                "A few steps, as at the first start: the settings mode, fast or quality processing, RAW, style and watermark (or "
                        + "the start of a custom set). It changes only what you choose in it."));
        wizard.setOnPreferenceClickListener(p -> {
            host.wizard();
            return true;
        });
        root.addPreference(wizard);
        final Banner banner = new Banner(c, host);
        banner.setOrder(-1);
        banner.setVisible(false);
        root.addPreference(banner);
        return banner;
    }

    /**
     * «Изменены расширенные настройки»: shown in the Simple mode while hidden settings are off their recommended defaults, with
     * «Сбросить к рекомендуемым» and «Открыть расширенные». It slides in once each time it appears (its content: the list's own
     * item animator moves the row).
     */
    static final class Banner extends Preference {
        private final Host host;
        private int count;
        /** It came in on screen since it was last hidden (the slide runs once per appearance). */
        private boolean entered;

        Banner(Context c, Host host) {
            super(c);
            this.host = host;
            setKey(SettingsLevel.BANNER_ROW);
            setPersistent(false);
            setSelectable(false);
            setLayoutResource(R.layout.preference_mode_banner);
            setTitle(Lang.t(c, "Изменены расширенные настройки", "Advanced settings changed"));
            SettingHelp.describe(this, Lang.t(c,
                    "Скрытые в простом или своём режиме настройки отличаются от рекомендуемых для этого телефона. Их можно вернуть "
                            + "к рекомендуемым (меняются только они) или открыть расширенный режим и посмотреть.",
                    "Settings hidden in the Simple or Custom mode differ from the ones recommended for this phone. You can put them "
                            + "back to the recommended values (only they change) or open the Advanced mode to see them."));
        }

        /** Its own layout always (the page's styling gives the other rows their card layouts). */
        @Override public void setLayoutResource(int layoutResId) {
            super.setLayoutResource(R.layout.preference_mode_banner);
        }

        /** {@code n} hidden settings are off their default: shown with that count (0: hidden). */
        void show(int n) {
            if (n != count) {
                count = n;
                notifyChanged();
            }
            if (n == 0) entered = false;
        }

        int count() {
            return count;
        }

        @Override
        public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            final Context c = holder.itemView.getContext();
            final int accent = SettingsStyle.accent(c);
            holder.itemView.setClickable(false);
            final TextView title = (TextView) holder.findViewById(R.id.mode_banner_title);
            final TextView text = (TextView) holder.findViewById(R.id.mode_banner_text);
            final TextView reset = (TextView) holder.findViewById(R.id.mode_banner_reset);
            final TextView open = (TextView) holder.findViewById(R.id.mode_banner_open);
            final View icon = holder.findViewById(R.id.mode_banner_icon);
            if (title == null || text == null || reset == null || open == null) return;
            final SharedPreferences prefs = getSharedPreferences() != null ? getSharedPreferences()
                    : androidx.preference.PreferenceManager.getDefaultSharedPreferences(c);
            title.setText(mode(prefs) == CUSTOM ? Lang.t(c, "Изменены скрытые настройки", "Hidden settings changed") : getTitle());
            com.scamera.app.ui.style.Fonts.apply(title, com.scamera.app.ui.style.Fonts.SEMIBOLD);
            text.setText(Lang.t(c, "Скрытых настроек не по умолчанию: " + count + ". Снимки учитывают их значения.",
                    "Hidden settings off their default: " + count + ". The photos use their values."));
            if (icon instanceof android.widget.ImageView) ((android.widget.ImageView) icon).setColorFilter(accent);
            reset.setText(Lang.t(c, "Сбросить к рекомендуемым", "Reset to recommended"));
            reset.setTextColor(accent);
            reset.setBackground(SettingsStyle.shape(c, SettingsStyle.tint(accent, .16f), 0, 20));
            open.setText(Lang.t(c, "Открыть расширенные", "Open advanced"));
            open.setTextColor(SettingsStyle.ROW_TITLE);
            open.setBackground(SettingsStyle.shape(c, SettingsStyle.CAPSULE, 0, 20));
            for (TextView b : new TextView[]{reset, open}) {
                com.scamera.app.ui.style.Fonts.apply(b, com.scamera.app.ui.style.Fonts.SEMIBOLD);
                Motion.press(b);
            }
            reset.setOnClickListener(v -> host.resetHidden());
            open.setOnClickListener(v -> set(prefs, ADVANCED));
            final View content = holder.findViewById(R.id.mode_banner);
            if (!entered && content != null) {
                entered = true;
                slideIn(content);
            }
        }

        /** The banner's content drops in from above on a soft spring (at rest at once without motion). */
        private static void slideIn(View v) {
            v.animate().cancel();
            Motion.rest(v);
            if (!ValueAnimator.areAnimatorsEnabled()) return;
            v.setAlpha(0f);
            v.setTranslationY(-SettingsStyle.dp(v.getContext(), 14));
            v.setScaleX(.97f);
            v.setScaleY(.97f);
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(120)
                    .setDuration(Motion.ENTER.durationMs).setInterpolator(Motion.ENTER).withEndAction(() -> Motion.rest(v)).start();
        }
    }
}
