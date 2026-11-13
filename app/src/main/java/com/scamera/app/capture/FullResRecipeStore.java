package com.scamera.app.capture;

import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;

import androidx.annotation.Nullable;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.ModuleRegistry;
import com.scamera.app.settings.ModuleSensorSettings;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P635: a lens's full-resolution recipe as its page stores it - the switch (pref_sensorconfig_&lt;slot&gt;_recipe) and the fields
 * the owner changed (pref_sensorconfig_&lt;slot&gt;_recipe…, a field's text; a field never touched is the preset's), so the
 * module's copy and reset take them along - and the memory of a profile that failed on this lens: its fields and the firmware,
 * so that any change of a field (or a new firmware, or «Сбросить к пресету») tries it again.
 * scam_dev "fullres_recipe 0" turns every recipe off.
 */
public final class FullResRecipeStore {
    private FullResRecipeStore() {}

    static final String TAG = "SCAM_CAPTURE";
    static final String FAIL_PREFIX = "module_recipe_fail_";
    /** P635b: a profile whose frame was judged real on this lens and firmware (its sessions need no proof still). */
    static final String OK_PREFIX = "module_recipe_ok_";

    public static String key(String slot, String field) {
        return ModuleSensorSettings.prefix(slot) + field;
    }

    @Nullable
    private static SharedPreferences prefs() {
        try {
            return ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The switch of {@code slot}. */
    public static boolean on(@Nullable SharedPreferences p, @Nullable String slot) {
        if (p == null || slot == null) return false;
        try {
            return p.getBoolean(key(slot, FullResRecipe.ON), false);
        } catch (ClassCastException text) {
            return "true".equalsIgnoreCase(String.valueOf(p.getAll().get(key(slot, FullResRecipe.ON))));
        }
    }

    /** The fields of {@code slot} the owner changed (their texts). */
    public static Map<String, String> edits(@Nullable SharedPreferences p, @Nullable String slot) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (p == null || slot == null) return out;
        final Map<String, ?> all = p.getAll();
        for (String field : FullResRecipe.FIELDS) {
            final Object v = all.get(key(slot, field));
            if (v != null) out.put(field, String.valueOf(v));
        }
        return out;
    }

    /**
     * The profile of {@code slot}: the preset of its camera with the owner's fields over it; without a preset the engine's empty
     * profile with them (no size until one is typed). {@code problems}: the fields whose text does not parse (may be null).
     */
    public static FullResRecipe profile(@Nullable SharedPreferences p, @Nullable String slot, @Nullable FullResRecipe preset,
                                        @Nullable List<String> problems) {
        final FullResRecipe base = preset != null ? preset : new FullResRecipe.Builder().build();
        return base.overlay(edits(p, slot), problems);
    }

    /** «Сбросить к пресету»: the owner's fields of {@code slot} go (the switch stays), and the memory of a failure with them. */
    public static void resetToPreset(@Nullable SharedPreferences p, String slot) {
        if (p == null) return;
        final SharedPreferences.Editor e = p.edit();
        for (String field : FullResRecipe.FIELDS) e.remove(key(slot, field));
        e.remove(FAIL_PREFIX + slot).remove(OK_PREFIX + slot).apply();
    }

    /** The built-in profile of {@code camera} on this phone (FullResRecipePresets), for the page; null: none. */
    @Nullable
    public static FullResRecipe presetFor(@Nullable String camera, @Nullable CameraCharacteristics c) {
        try {
            return FullResRecipePresets.preset(FullResRecipePresets.Device.current(), camera, SensorModeBand.table(c));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the failure memory

    static String stamp(FullResRecipe recipe, String fingerprint) {
        return fingerprint + "\n" + recipe.signature();
    }

    /** Why this very profile failed on {@code slot} on this firmware; null: it did not (or a field changed since). */
    @Nullable
    public static String failure(@Nullable SharedPreferences p, @Nullable String slot, FullResRecipe recipe, String fingerprint) {
        if (p == null || slot == null) return null;
        final String stored = p.getString(FAIL_PREFIX + slot, null);
        if (stored == null) return null;
        final String stamp = stamp(recipe, fingerprint) + "\n";
        return stored.startsWith(stamp) ? stored.substring(stamp.length()) : null;
    }

    /** The profile failed on {@code slot} for {@code why}: remembered until a field, the firmware or the preset changes. */
    public static void fail(@Nullable SharedPreferences p, @Nullable String slot, FullResRecipe recipe, String fingerprint, String why) {
        if (p == null || slot == null) return;
        p.edit().putString(FAIL_PREFIX + slot, stamp(recipe, fingerprint) + "\n" + why).apply();
    }

    public static void clearFailure(@Nullable SharedPreferences p, @Nullable String slot) {
        if (p != null && slot != null) p.edit().remove(FAIL_PREFIX + slot).apply();
    }

    // ---------------------------------------------------------------- P635b: the proof

    /** This very profile gave a real full frame on {@code slot} on this firmware. */
    public static boolean proved(@Nullable SharedPreferences p, @Nullable String slot, FullResRecipe recipe, String fingerprint) {
        return p != null && slot != null && stamp(recipe, fingerprint).equals(p.getString(OK_PREFIX + slot, null));
    }

    public static void prove(@Nullable SharedPreferences p, @Nullable String slot, FullResRecipe recipe, String fingerprint) {
        if (p != null && slot != null) p.edit().putString(OK_PREFIX + slot, stamp(recipe, fingerprint)).apply();
    }

    public static boolean provedActive(@Nullable Active active) {
        return active != null && proved(prefs(), active.slot, active.recipe, String.valueOf(Build.FINGERPRINT));
    }

    public static void proveActive(@Nullable Active active) {
        if (active != null) prove(prefs(), active.slot, active.recipe, String.valueOf(Build.FINGERPRINT));
    }

    // ---------------------------------------------------------------- P635b: a picked full-size row runs as the recipe

    /**
     * P635b (owner's third log, Xiaomi 17 Ultra: the module's rows 11, 0 and 22 - 8192 wide - each gave the binned frame in a
     * quarter of the buffer): a module whose picked sensor mode row is as wide as its camera's proven preset runs the preset's
     * recipe instead of the row, the switch off or not - the row alone cannot give the full frame. Not an experimental preset
     * (a guess never replaces what the owner picked), not a narrower row.
     */
    static boolean autoRoute(int row, @Nullable int[] table, @Nullable FullResRecipe preset) {
        if (row < 0 || preset == null || preset.experimental) return false;
        final int[] e = SensorModeBand.modeEntry(table, row);
        return e != null && e[0] == preset.width && e[1] > 0 && e[1] <= preset.height;
    }

    /** The picked row of {@code slot} its camera's recipe runs instead of ({@link #autoRoute}); -1: none. */
    public static int routedRow(@Nullable String slot, @Nullable String camera, @Nullable CameraCharacteristics c) {
        try {
            final int row = SensorModePick.picked(slot);
            return autoRoute(row, SensorModeBand.table(c), presetFor(camera, c)) ? row : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The recipe of {@code slot}'s camera failed on this firmware (the row's message says so). */
    public static boolean failedHere(@Nullable String slot, @Nullable String camera, @Nullable CameraCharacteristics c) {
        final FullResRecipe preset = presetFor(camera, c);
        final SharedPreferences p = prefs();
        return preset != null && failure(p, slot, profile(p, slot, preset, null), String.valueOf(Build.FINGERPRINT)) != null;
    }

    // ---------------------------------------------------------------- the running lens

    /** What a session of the active lens runs. */
    public static final class Active {
        public final String slot;
        public final FullResRecipe recipe;
        /** P635b: the module's picked sensor mode row this recipe runs instead of (the switch is off); -1: the switch is on. */
        public final int autoRow;

        Active(String slot, FullResRecipe recipe) {
            this(slot, recipe, -1);
        }

        Active(String slot, FullResRecipe recipe, int autoRow) {
            this.slot = slot;
            this.recipe = recipe;
            this.autoRow = autoRow;
        }
    }

    /** Why the lens's switch does nothing now (the page greys the rows and says it); null: the recipe can run. */
    @Nullable
    public static String blocked(@Nullable SharedPreferences p, @Nullable String slot, FullResRecipe recipe, int sdk, String fingerprint) {
        final List<String> blockers = recipe.blockers(sdk);
        if (!blockers.isEmpty()) return blockers.get(0);
        final String failed = failure(p, slot, recipe, fingerprint);
        return failed == null ? null : "failed:" + failed;
    }

    /**
     * The recipe a session of {@code physicalId} runs: the active module's, when its switch is on, its camera is this one, the
     * profile can run here and did not fail on this firmware; null otherwise (the normal session). Says once per answer why a
     * switched-on recipe does not run.
     */
    @Nullable
    public static Active active(@Nullable String physicalId, @Nullable CameraCharacteristics c) {
        try {
            if (physicalId == null || !PreferenceKeys.scamDevSwitch("fullres_recipe", true)) return null;
            final SharedPreferences p = prefs();
            final String slot = ModuleRegistry.active();
            if (p == null || slot == null || !physicalId.equals(ModuleRegistry.camera(slot))) return null;
            final int[] table = SensorModeBand.table(c);
            final FullResRecipe preset = FullResRecipePresets.preset(FullResRecipePresets.Device.current(), physicalId, table);
            // P635b: a picked row as wide as the proven preset runs as the recipe, the switch on or not
            final int row = SensorModePick.picked(slot);
            final boolean auto = !on(p, slot) && autoRoute(row, table, preset);
            if (!on(p, slot) && !auto) return null;
            final List<String> problems = new ArrayList<>();
            final FullResRecipe recipe = profile(p, slot, preset, problems);
            final String blocked = blocked(p, slot, recipe, Build.VERSION.SDK_INT, String.valueOf(Build.FINGERPRINT));
            if (!problems.isEmpty()) Log.w(TAG, "FULLRES RECIPE camera " + physicalId + ": fields that do not parse (the preset's kept): " + problems);
            if (blocked != null) {
                Log.w(TAG, "FULLRES RECIPE camera " + physicalId + " " + recipe + ": not run (" + blocked + "), the normal session (P635)");
                return null;
            }
            return new Active(slot, recipe, auto ? row : -1);
        } catch (RuntimeException e) {
            Log.w(TAG, "FULLRES RECIPE: " + e);
            return null;
        }
    }

    /** {@link #fail} for the active lens's running profile (CaptureController). */
    public static void failActive(@Nullable Active active, String why) {
        if (active == null) return;
        fail(prefs(), active.slot, active.recipe, String.valueOf(Build.FINGERPRINT), why);
    }
}
