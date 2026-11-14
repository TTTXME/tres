package com.scamera.app.settings;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * P708 (owner, 2026-11-14: «добавь третий вариант кастомный, пользователь сам выберет что ему нужно, а что нет»): the settings the
 * Custom mode shows. It is stored as the user's changes against the levels (SettingsLevel): {@link #SHOWN_KEY} - settings shown
 * though they are not Simple ones, {@link #HIDDEN_KEY} - Simple settings hidden. So a setting a later version adds follows its
 * level until the user chooses: a Simple one appears, an Advanced one stays hidden. A setting is a row the user can pick (a row
 * of its own, or a page without rows of its own); its pages and groups are shown while any setting in them is.
 * <p>
 * «Начать со всех» takes every setting there is at that moment into {@link #SHOWN_KEY} (from the settings' tree,
 * {@link #START_KEY} asks for it until the tree is built). The mode changes what is shown, never a value. Pure Java.
 */
public final class CustomSet {
    public static final String SHOWN_KEY = "settings_custom_shown", HIDDEN_KEY = "settings_custom_hidden";
    /** {@link #START_ALL}: «Начать со всех» chosen where the settings' tree was not built (the first-run wizard). */
    public static final String START_KEY = "settings_custom_start", START_ALL = "all";

    /** A group's switch: every setting in it shown, none, or some. */
    public static final int NONE = 0, SOME = 1, ALL = 2;

    private final Set<String> shown, hidden;

    public CustomSet(Collection<String> shown, Collection<String> hidden) {
        this.shown = new HashSet<>(shown == null ? new HashSet<>() : shown);
        this.hidden = new HashSet<>(hidden == null ? new HashSet<>() : hidden);
    }

    /** Whether the setting {@code key} is shown; {@code simple}: it is a Simple one (SettingsLevel.shown of its path). */
    public boolean visible(String key, boolean simple) {
        return simple ? !hidden.contains(key) : shown.contains(key);
    }

    /** Shows or hides one setting (a choice equal to its level is no change of the user's: it is dropped from both sets). */
    public void set(String key, boolean simple, boolean on) {
        shown.remove(key);
        hidden.remove(key);
        if (on && !simple) shown.add(key);
        if (!on && simple) hidden.add(key);
    }

    /** The state of a group of settings ({@code keys} with their levels in {@code simple}, the same order). */
    public int state(java.util.List<String> keys, java.util.List<Boolean> simple) {
        int on = 0;
        for (int i = 0; i < keys.size(); i++) if (visible(keys.get(i), simple.get(i))) on++;
        return on == 0 ? NONE : on == keys.size() ? ALL : SOME;
    }

    /** A group's switch tapped: all of it shown, unless all of it is (then none). Returns the state now. */
    public int toggle(java.util.List<String> keys, java.util.List<Boolean> simple) {
        final boolean on = state(keys, simple) != ALL;
        for (int i = 0; i < keys.size(); i++) set(keys.get(i), simple.get(i), on);
        return on ? ALL : NONE;
    }

    /** «Только главное»: the levels again (no change of the user's left). */
    public void essentials() {
        shown.clear();
        hidden.clear();
    }

    /** «Показать все»: every one of {@code all} shown (the Simple ones by their level, the others chosen). */
    public void showAll(Collection<String> all) {
        hidden.clear();
        shown.addAll(all);
    }

    public Set<String> shown() {
        return new HashSet<>(shown);
    }

    public Set<String> hidden() {
        return new HashSet<>(hidden);
    }

    /** How many of {@code keys} are shown. */
    public int count(java.util.List<String> keys, java.util.List<Boolean> simple) {
        int n = 0;
        for (int i = 0; i < keys.size(); i++) if (visible(keys.get(i), simple.get(i))) n++;
        return n;
    }
}
