package com.scamera.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.scamera.app.api.VendorTagUtils;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.ui.camera.data.CameraLensData;
import com.scamera.app.ui.settings.custompreferences.TunableKeyDialog;
import com.scamera.app.ui.settings.custompreferences.TunableKeyPreference;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;
import com.scamera.app.ui.settings.custompreferences.TunableSeekBarPreference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Automatically generates per-module sensor preference UI from {@code @SensorConfig} annotations.
 * One category per module; only the selected module's category is shown. P384 (owner, 2026-10-28: «А то что на третьем скриншоте
 * сделать как отдельную группу настроек на главной странице разделенную по модулям»): the categories live in «Сенсор по модулям»
 * ({@link #GROUP_KEY}) of the settings' main page, the module is chosen by its chips (ModuleRows, ModuleChipsPreference) - none at
 * first, so the page stays short - or by the module profile page that shows it; see {@link ModuleSelection}.
 */
public final class SensorConfigPreferenceGenerator {
    private static final String TAG = "SensorRows";

    /** P384: «Сенсор по модулям» of the main page (was the page pref_sensor_config_submenu). */
    public static final String GROUP_KEY = "expert_module_sensor";
    /** The key prefix of a module's category. */
    public static final String CATEGORY_PREFIX = "pref_category_sensor_";
    /** Rows in this order: RAW levels, stabilization, session; unknown fields after them. */
    private static final List<String> FIELD_ORDER = java.util.Arrays.asList(
            "blackLevelOverride", "whiteLevelOverride", "oisMode", "sessionType");
    /** P384: the icons of the generated list and text rows by field (the sliders keep theirs). */
    private static final Map<String, Integer> FIELD_ICONS = new HashMap<>();

    static {
        FIELD_ICONS.put("oismode", com.scamera.app.R.drawable.settings_ic_motion);
        FIELD_ICONS.put("sessiontype", com.scamera.app.R.drawable.settings_ic_chip);
        FIELD_ICONS.put("ispsurface", com.scamera.app.R.drawable.settings_ic_layers);
        FIELD_ICONS.put("rawviewfinder", com.scamera.app.R.drawable.settings_ic_eye);
    }

    private SensorConfigPreferenceGenerator() {}

    /**
     * The module whose sensor settings the page shows, or none. Choosing another one only changes what the page shows: it
     * neither switches the camera nor writes a preference.
     */
    public static final class ModuleSelection {
        private final List<String> slots;
        private final Map<String, PreferenceCategory> categories;
        /** P384: the camera each category was built for (ModuleRows rebuilds a category whose module changed its camera). */
        private final Map<String, String> cameras = new HashMap<>();
        @Nullable private String selected;

        ModuleSelection(List<String> slots, Map<String, PreferenceCategory> categories, @Nullable String selected) {
            this.slots = new ArrayList<>(slots);
            this.categories = categories;
            for (String slot : slots) cameras.put(slot, cameraOf(slot));
            select(selected);
        }

        /** The modules the page can show, in zoom-bar order. */
        public List<String> slots() { return Collections.unmodifiableList(slots); }

        /** The module shown, null when none is. */
        @Nullable public String selected() { return selected; }

        /** «1× · ID 3»: the title of the module's category. */
        public String title(String slot) {
            PreferenceCategory category = categories.get(slot);
            return category != null && category.getTitle() != null ? category.getTitle().toString() : slot;
        }

        @Nullable public PreferenceCategory category(String slot) { return categories.get(slot); }

        /** P384: the camera the module's category was built for. */
        @Nullable public String camera(String slot) { return cameras.get(slot); }

        /** Show {@code slot}'s category only; null (or a module not listed) shows none. */
        public void select(@Nullable String slot) {
            if (slot != null && !categories.containsKey(slot)) slot = null;
            selected = slot;
            final String shownSlot = slot;
            categories.forEach((s, c) -> {
                if (c.isVisible() != s.equals(shownSlot)) c.setVisible(!c.isVisible());
            });
        }

        /** P384: a module's category added (ModuleRows.sync: a new copy), the modules kept in {@code order}. */
        public void put(String slot, PreferenceCategory category, List<String> order) {
            categories.put(slot, category);
            cameras.put(slot, cameraOf(slot));
            if (!slots.contains(slot)) slots.add(slot);
            order(order);
            category.setVisible(slot.equals(selected));
        }

        /** P384: a module's category gone (a deleted copy). */
        public void remove(String slot) {
            categories.remove(slot);
            cameras.remove(slot);
            slots.remove(slot);
            if (slot.equals(selected)) selected = null;
        }

        /** P384: the modules in {@code order} (zoom order after a zoom change). */
        public void order(List<String> order) {
            slots.sort(java.util.Comparator.comparingInt(s -> order.indexOf(s) < 0 ? Integer.MAX_VALUE : order.indexOf(s)));
        }

        static String cameraOf(String slot) {
            try {
                return ModuleRegistry.slots().contains(slot) ? ModuleRegistry.camera(slot) : slot;
            } catch (RuntimeException e) {
                return slot;
            }
        }
    }

    /**
     * P384: the modules of the settings' main page (cards, Camera ID rows, sensor categories): every slot but the untouched filler
     * slots (a free place for a copy, P148), the back ones by zoom, then the front ones; a hidden module stays at its place.
     * {@code keep} is listed even when it is a filler (a module profile page shows its own module).
     */
    public static List<String> moduleSlots(@Nullable String keep) {
        List<String> slots = new ArrayList<>(ModuleRegistry.slots());
        slots.removeIf(slot -> !slot.equals(keep) && ModuleRegistry.freeSlot(slot));
        slots.sort(java.util.Comparator.comparing((String slot) -> slot.startsWith("front")).thenComparingDouble(ModuleRegistry::zoom));
        return slots;
    }

    /** A category of one module (pref_category_sensor_&lt;slot&gt;). */
    public static boolean isModuleCategory(@Nullable androidx.preference.Preference p) {
        return p instanceof PreferenceCategory && p.getKey() != null && p.getKey().startsWith(CATEGORY_PREFIX);
    }

    /** Generate the per-module sensor rows; no module is shown at first. */
    public static ModuleSelection generatePreferences(Context context, PreferenceGroup root) {
        return generatePreferences(context, root, null);
    }

    /** As {@link #generatePreferences(Context, PreferenceGroup, String, Runnable, Consumer)} without callbacks. */
    public static ModuleSelection generatePreferences(Context context, PreferenceGroup root, String preferred) {
        return generatePreferences(context, root, preferred, null, null);
    }

    /**
     * Generate and add per-module sensor preferences to «Сенсор по модулям» of {@code root}.
     *
     * @param preferred the module shown at first (a module profile page shows its own), null for none
     * @param onChange  run after a camera key was added, edited, deleted or switched (the main page's chips and summaries)
     * @param onReset   asked to reset a module's sensor settings (the fragment confirms, resets and recreates)
     * @return the module selection of the page, null when nothing was generated
     */
    public static ModuleSelection generatePreferences(Context context, PreferenceGroup root, String preferred,
                                                      @Nullable Runnable onChange, @Nullable Consumer<String> onReset) {
        PreferenceGroup group = GROUP_KEY.equals(root.getKey()) ? root : root.findPreference(GROUP_KEY);
        if (group == null) {
            Log.w(TAG, "Sensor group not found! Preferences will not be generated.");
            return null;
        }

        try {
            List<String> slots = moduleSlots(preferred);
            if (slots.isEmpty()) slots = getSortedPhysicalIds();
            if (slots.isEmpty()) {
                Log.w(TAG, "no cameras scanned yet, no sensor rows");
                addNoSensorsPreference(context, group);
                return null;
            }
            if (scanFields().isEmpty()) return null;
            Map<String, PreferenceCategory> bySlot = new LinkedHashMap<>();
            for (String slot : slots) bySlot.put(slot, addModuleCategory(context, group, slot, onChange, onReset));
            return new ModuleSelection(slots, bySlot, slots.contains(preferred) ? preferred : null);
        } catch (RuntimeException failed) {
            Log.e(TAG, "sensor rows failed", failed);
            return null;
        }
    }

    /**
     * P384: one module's category under {@code group} (built, or the existing one): its {@code @SensorConfig} rows, the sensor mode
     * of the camera's table, its camera keys, the frame-rate check, adding a key and its reset.
     */
    public static PreferenceCategory addModuleCategory(Context context, PreferenceGroup group, String slot,
                                                       @Nullable Runnable onChange, @Nullable Consumer<String> onReset) {
        if (ModuleRegistry.slots().contains(slot)) ModuleSensorSettings.ensure(slot);
        PreferenceCategory category = findOrCreateCategory(context, group, slot, getCameraLensMap().get(slot));
        for (FieldSpec s : scanFields()) addPreference(context, category, slot, s);
        UnspektraAePreferences.addRows(context, category, slot, onChange);
        addTunableKeySection(context, category, slot, onChange);
        addResetRow(context, category, slot, onReset);
        return category;
    }

    /** P384: a module's category gone (a deleted copy, or rebuilt for another camera). */
    public static void removeModuleCategory(PreferenceGroup group, String slot) {
        androidx.preference.Preference category = group.findPreference(CATEGORY_PREFIX + slot);
        if (category != null && category.getParent() != null) category.getParent().removePreference(category);
    }

    /** P384 (was the sensor page's one reset row): «Сбросить сенсор «X»» at the end of the module's rows, asked first by the fragment. */
    private static void addResetRow(Context context, PreferenceCategory category, String slot, @Nullable Consumer<String> onReset) {
        String key = "pref_reset_sensor_config_settings_" + slot;
        if (category.findPreference(key) != null) return;
        androidx.preference.Preference reset = new androidx.preference.Preference(context);
        reset.setKey(key);
        reset.setLayoutResource(com.scamera.app.R.layout.preference_card);
        reset.setTitle(context.getString(com.scamera.app.R.string.module_sensor_reset,
                ModuleRegistry.slots().contains(slot) ? ModuleRegistry.label(slot) : slot));
        reset.setSummary(context.getString(com.scamera.app.R.string.module_sensor_reset_summary));
        reset.setIcon(com.scamera.app.R.drawable.settings_ic_reset);
        reset.setOrder(20000);
        reset.setOnPreferenceClickListener(preference -> {
            if (onReset != null) onReset.accept(slot);
            return true;
        });
        category.addPreference(reset);
    }

    /**
     * P654 (owner, 2026-11-13: «Настройки "Sensor mode" и Vendor Tags перемести в начало списка Lens Settings»): the sensor mode,
     * the camera keys (vendor tags) and «Добавить ключ» first in the module's category, which heads the lens page; its other rows
     * (their orders 0 .. 20000) follow as before.
     */
    static final int SENSOR_MODE_ORDER = -100000, KEYS_ORDER = -90000, ADD_KEY_ORDER = -10000;

    private static void addTunableKeySection(Context context, PreferenceCategory category, String physicalId,
                                             @Nullable Runnable onChange) {
        // P384: the list is rebuilt only after a key was added, edited or deleted in its sheet (a switch updates its row in place)
        Runnable refresh = () -> {
            refreshTunableKeys(context, category, physicalId, onChange);
            if (onChange != null) onChange.run();
        };

        int index = 0;
        for (VendorTagUtils.TunableKey ignored : TunableKeyManager.loadKeys(context, physicalId)) {
            TunableKeyPreference row = new TunableKeyPreference(context, physicalId, index, refresh, onChange);
            row.setOrder(KEYS_ORDER + index++); // P654: right below the sensor mode
            category.addPreference(row);
        }

        addSensorModePick(context, category, physicalId);
        addDualGain(context, category, physicalId); // P641
        addFullResRecipe(context, category, physicalId, refresh); // P635
        YuvMergeRows.add(context, category, physicalId, com.scamera.app.capture.YuvMergeRoute.cameraOf(physicalId)); // P646

        // P266 «Проверить частоты кадров»: asked here, run by the next session of this module (FpsProbe)
        androidx.preference.Preference probe = new androidx.preference.Preference(context);
        probe.setKey("pref_sensorconfig_" + physicalId + "_fpsprobe");
        probe.setLayoutResource(com.scamera.app.R.layout.preference_card);
        probe.setTitle(Lang.t(context, "Проверить частоты кадров", "Check the frame rates"));
        fpsProbeTexts(context, probe, physicalId);
        probe.setIcon(com.scamera.app.R.drawable.settings_ic_fps);
        probe.setOrder(9999);
        probe.setOnPreferenceClickListener(preference -> {
            com.scamera.app.capture.FpsProbe.request(context, physicalId);
            fpsProbeTexts(context, preference, physicalId);
            return true;
        });
        category.addPreference(probe);

        androidx.preference.Preference add = new androidx.preference.Preference(context);
        add.setKey("pref_sensorconfig_" + physicalId + "_add_tunablekey");
        add.setLayoutResource(com.scamera.app.R.layout.preference_card);
        // P384: the plain name first, «vendor tag» only in the description
        add.setTitle(context.getString(com.scamera.app.R.string.module_key_add));
        add.setSummary(context.getString(com.scamera.app.R.string.module_key_add_summary));
        add.setIcon(com.scamera.app.R.drawable.settings_ic_plus);
        add.setOrder(ADD_KEY_ORDER); // P654: with the keys, before the module's other rows
        add.setOnPreferenceClickListener(row -> {
            TunableKeyDialog.show(context, physicalId, -1, refresh); // -1: a new key
            return true;
        });
        category.addPreference(add);
    }

    /**
     * P276 «Режим сенсора (SensorModeTable)»: Auto or a row of the camera's own table ("Mode #4: 8192x6144 @ 30fps", as WhyCam
     * lists it); a picked row opens the RAW stream at its size and sends the brand's mode key (SensorModePick). Shown only when
     * the module's camera has a table.
     */
    private static void addSensorModePick(Context context, PreferenceCategory category, String slot) {
        java.util.List<int[]> rows;
        try {
            final String camera = ModuleRegistry.slots().contains(slot) ? ModuleSensorSettings.physical(slot) : toPhysicalId(slot);
            final android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            rows = com.scamera.app.capture.SensorModePick.rows(manager.getCameraCharacteristics(camera));
        } catch (Exception unavailable) {
            return;
        }
        if (rows.isEmpty()) return;
        category.addPreference(sensorModeRow(context, slot, rows));
    }

    /** The key of the P641 row of {@code slot}. */
    static String dualGainKey(String slot) {
        return "pref_sensorconfig_" + slot + "_dualgain";
    }

    /**
     * P641 «DCG / DCG+LOFIC» («DSG» on the ultra wide) of the Xiaomi 17 Ultra: the lens page's row of the dual-gain switch - the
     * same switch as the top bar's DCG button, with DCG + LOFIC as a choice of its own on the main camera. Shown only on a camera
     * whose table has the vendor's row (XiaomiDualGain.offered). Choosing a mode here also forgets the ways that failed on this
     * firmware, so each is tried once more.
     */
    private static void addDualGain(Context context, PreferenceCategory category, String slot) {
        if (!com.scamera.app.capture.XiaomiDualGain.enabled()) return; // any other phone: nothing is asked
        final String camera;
        final java.util.List<com.scamera.app.capture.XiaomiDualGain.Kind> kinds;
        try {
            camera = ModuleRegistry.slots().contains(slot) ? ModuleSensorSettings.physical(slot) : toPhysicalId(slot);
            kinds = com.scamera.app.capture.XiaomiDualGain.offered(camera, ((android.hardware.camera2.CameraManager)
                    context.getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics(camera));
        } catch (Exception unavailable) {
            return;
        }
        if (kinds.isEmpty()) return;
        category.addPreference(dualGainRow(context, slot, camera, kinds));
    }

    /** The P641 row for the {@code kinds} of {@code camera} (built apart for its test). */
    public static ListPreference dualGainRow(Context context, String slot, String camera,
                                             java.util.List<com.scamera.app.capture.XiaomiDualGain.Kind> kinds) {
        final String[] entries = new String[kinds.size() + 1], values = new String[kinds.size() + 1];
        entries[0] = Lang.t(context, "Выкл.", "Off");
        values[0] = String.valueOf(com.scamera.app.capture.Dcg14Mode.OFF);
        final StringBuilder names = new StringBuilder();
        for (int i = 0; i < kinds.size(); i++) {
            final com.scamera.app.capture.XiaomiDualGain.Kind kind = kinds.get(i);
            entries[i + 1] = kind.label;
            values[i + 1] = String.valueOf(kind == com.scamera.app.capture.XiaomiDualGain.Kind.LOFIC
                    ? com.scamera.app.capture.Dcg14Mode.LOFIC : com.scamera.app.capture.Dcg14Mode.DCG);
            names.append(i == 0 ? "" : " / ").append(kind.label);
        }
        final ListPreference row = new ListPreference(context);
        row.setKey(dualGainKey(slot));
        row.setPersistent(false); // the switch itself is Dcg14Mode's (one for the phone, as the top bar's button)
        final String title = names + Lang.t(context, " (двойное усиление сенсора)", " (the sensor's dual gain)");
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setEntries(entries);
        row.setEntryValues(values);
        final int mode = com.scamera.app.capture.Dcg14Mode.mode();
        String shown = values[0];
        for (String v : values) if (v.equals(String.valueOf(mode))) shown = v;
        if (mode != com.scamera.app.capture.Dcg14Mode.OFF && shown.equals(values[0])) shown = values[1]; // LOFIC stored, a camera without it: its DCG / DSG
        row.setValue(shown);
        row.setIcon(com.scamera.app.R.drawable.settings_ic_sensor);
        row.setOrder(98);
        row.setSummaryProvider(p -> {
            final StringBuilder state = new StringBuilder();
            for (com.scamera.app.capture.XiaomiDualGain.Kind kind : kinds)
                state.append(state.length() == 0 ? "" : ". ").append(com.scamera.app.capture.XiaomiDualGain.state(camera, kind));
            return state + Lang.t(context, ". Опытная функция: на телефоне не проверена.", ". Experimental: not proven on a phone.");
        });
        row.setOnPreferenceChangeListener((p, value) -> {
            int chosen;
            try {
                chosen = Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                return false;
            }
            com.scamera.app.capture.XiaomiDualGain.tryAgain(camera);
            com.scamera.app.capture.Dcg14Mode.setMode(chosen);
            return true;
        });
        com.scamera.app.ui.settings.SettingHelp.describe(row, Lang.t(context,
                "Режим сенсора с двумя усилениями в одном кадре: больше деталей в тенях и светах, RAW глубже 10 бит (14 бит, на сверхширокой"
                        + " камере DSG — 12 бит). DCG+LOFIC — кадр DCG + кадр переполнения LOFIC той же экспозиции, склеиваются вместе:"
                        + " второй кадр идёт отдельным потоком и хранит света, выбитые в первом; камера берёт его в склейку, только когда"
                        + " сами кадры это подтверждают, иначе снимает один 14-битный кадр этого режима; не проверено на телефоне."
                        + " Это тот же переключатель, что кнопка DCG в верхней строке. Камера перебирает несколько способов включить"
                        + " режим и проверяет каждый по самим кадрам: первый сработавший запоминается для этой прошивки, несработавшие"
                        + " больше не пробуются. Если не сработал ни один, DCG+LOFIC заменяется на DCG, а DCG и DSG выключаются. Выбор режима"
                        + " здесь заново разрешает все способы. На телефоне не проверено: проверяйте днём, на контрастной сцене.",
                "A sensor mode with two gains in one frame: more detail in shadows and highlights, a RAW deeper than 10 bits (14 bits; the"
                        + " ultra wide's DSG is 12 bits). DCG+LOFIC is a DCG frame + a LOFIC overflow frame of the same exposure, merged"
                        + " together: the second frame comes on a stream of its own and holds the highlights the first one clips; the camera"
                        + " takes it into the merge only when the frames themselves prove it, else it shoots the mode's 14-bit frame alone;"
                        + " not proven on a phone. This is the same switch as the DCG button of the top bar. The camera goes through several ways of"
                        + " turning the mode on and checks each on the frames themselves: the first that works is remembered for this firmware,"
                        + " the ones that failed are not tried again. If none works, DCG+LOFIC becomes DCG, and DCG and DSG switch off. Choosing"
                        + " a mode here allows every way again. Not proven on a phone: test by day, on a contrasty scene."));
        return row;
    }

    /**
     * P635 «Полное разрешение (рецепт)»: the switch and, while it is on, the profile's fields (FullResRecipeRows), prefilled from
     * the preset of the module's camera on this phone.
     */
    private static void addFullResRecipe(Context context, PreferenceCategory category, String slot, @Nullable Runnable refresh) {
        com.scamera.app.capture.FullResRecipe preset = null;
        int routedRow = -1; // P635b: the module's picked full-size row the recipe runs instead of
        try {
            final String camera = ModuleRegistry.slots().contains(slot) ? ModuleSensorSettings.physical(slot) : toPhysicalId(slot);
            android.hardware.camera2.CameraCharacteristics c = null;
            try {
                c = ((android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics(camera);
            } catch (Exception unavailable) {
                // no camera service (a preview of the page): the preset by the phone and the camera id alone
            }
            preset = com.scamera.app.capture.FullResRecipeStore.presetFor(camera, c);
            routedRow = com.scamera.app.capture.FullResRecipeStore.routedRow(slot, camera, c);
        } catch (RuntimeException ignored) {
        }
        FullResRecipeRows.add(context, category, slot, preset, routedRow, refresh);
    }

    /** The «Режим сенсора» row of {@code slot} for the rows of its camera's table (P384: built apart for its bind test). */
    public static ListPreference sensorModeRow(Context context, String slot, java.util.List<int[]> rows) {
        final String[] entries = new String[rows.size() + 1], values = new String[rows.size() + 1];
        entries[0] = Lang.t(context, "Авто (по умолчанию)", "Auto (default)");
        values[0] = "-1";
        for (int i = 0; i < rows.size(); i++) {
            entries[i + 1] = com.scamera.app.capture.SensorModePick.label(rows.get(i));
            values[i + 1] = String.valueOf(rows.get(i)[0]);
        }
        ListPreference pick = new ListPreference(context);
        pick.setKey(com.scamera.app.capture.SensorModePick.prefKey(slot));
        pick.setTitle(ModuleSensorSettings.title("Sensor Mode"));
        pick.setDialogTitle(ModuleSensorSettings.title("Sensor Mode"));
        pick.setEntries(entries);
        pick.setEntryValues(values);
        pick.setDefaultValue("-1");
        pick.setIcon(com.scamera.app.R.drawable.settings_ic_sensor);
        pick.setOrder(SENSOR_MODE_ORDER); // P654: the first row of the lens page
        // P316 (settings audit): the ISP stream stays out only with «Поток ISP» Auto (or Off); on OPPO / OnePlus / realme a picked
        // row's shots come from the ring alone (P315 SensorModePick.seriesAllowed), which the row says
        // P384: the picked row is the value on the right (it was the summary's first line too, and the title said SensorModeTable);
        // the restart pill says when it applies (SessionKeys: a new session)
        final String hint = context.getString(com.scamera.app.R.string.module_sensor_mode_hint)
                + (com.scamera.app.capture.SensorModePick.pickTakesRingOnly() ? " " + Lang.t(context,
                        "OPPO / OnePlus / realme: снимок только из буфера — без кадров светов и теней, ручной длинной выдержки и подстройки под мерцание ламп.",
                        "OPPO / OnePlus / realme: the shot comes from the ring only - no highlight or shadow frames, no manual long shutter, no lamp-flicker timing.") : "");
        pick.setSummaryProvider(p -> hint);
        // P592: the row shows its title and the picked row; what the table is (and the OPPO note) is its long press
        com.scamera.app.ui.settings.SettingHelp.describe(pick, context.getString(com.scamera.app.R.string.help_sensor_mode)
                + (hint.length() > context.getString(com.scamera.app.R.string.module_sensor_mode_hint).length()
                ? "\n\n" + hint.substring(context.getString(com.scamera.app.R.string.module_sensor_mode_hint).length()).trim() : ""));
        return pick;
    }

    /**
     * P592: the probe's row - «asked» and the last result are its state (shown under the title); what the check does is its
     * description (the long press).
     */
    private static void fpsProbeTexts(Context context, androidx.preference.Preference probe, String slot) {
        final boolean asked = com.scamera.app.capture.FpsProbe.requested(context, slot);
        final boolean result = com.scamera.app.capture.FpsProbe.lastResult(context, slot) != null;
        final String summary = fpsProbeSummary(context, slot);
        com.scamera.app.ui.settings.SettingHelp.describe(probe, Lang.t(context,
                "Камера по секунде снимает на каждой частоте кадров. Так видно, сколько кадров в секунду она даёт на самом деле и какой режим сенсора при этом выбирает. Проверка идёт при следующем открытии камеры с этим модулем.",
                "The camera runs each frame rate for a second. It shows how many frames a second it really delivers and which sensor mode it picks for that. The check runs the next time the camera opens on this module."));
        com.scamera.app.ui.settings.SettingHelp.state(probe, asked || result ? summary : null);
        probe.setSummary(summary);
    }

    /** The probe's row: asked (runs on the camera), the last result, or what it does. */
    static String fpsProbeSummary(Context context, String slot) {
        if (com.scamera.app.capture.FpsProbe.requested(context, slot))
            return Lang.t(context, "Откройте камеру с этим модулем: проверка займёт несколько секунд",
                    "Open the camera on this module: the check takes a few seconds");
        final String last = com.scamera.app.capture.FpsProbe.lastResult(context, slot);
        return last != null ? Lang.t(context, "Запрошено → получено к/с (режим сенсора): ", "Asked → delivered fps (sensor mode): ") + last
                : Lang.t(context, "Каждая частота на секунду: что камера реально даёт и какой режим сенсора выбирает",
                "Each rate for a second: what the camera really delivers and which sensor mode it picks");
    }

    /** Rebuilds the key section: its rows go and are added again (the sensor mode too, else a second row of one key). */
    private static void refreshTunableKeys(Context context, PreferenceCategory category, String physicalId,
                                           @Nullable Runnable onChange) {
        java.util.Set<String> sectionKeys = new java.util.HashSet<>(java.util.Arrays.asList(
                "pref_sensorconfig_" + physicalId + "_add_tunablekey",
                "pref_sensorconfig_" + physicalId + "_fpsprobe",
                com.scamera.app.capture.SensorModePick.prefKey(physicalId),
                dualGainKey(physicalId), // P641
                com.scamera.app.capture.YuvMergeRoute.key(physicalId))); // P646
        for (int i = category.getPreferenceCount() - 1; i >= 0; i--) {
            androidx.preference.Preference row = category.getPreference(i);
            boolean ours = row instanceof TunableKeyPreference || sectionKeys.contains(row.getKey())
                    || FullResRecipeRows.ours(row.getKey(), physicalId); // P635
            if (ours) category.removePreference(row);
        }
        addTunableKeySection(context, category, physicalId, onChange);
    }

    private static void addNoSensorsPreference(Context context, PreferenceGroup group) {
        final String key = "pref_sensor_config_no_sensors";
        if (group.findPreference(key) != null) return;
        androidx.preference.Preference note = new androidx.preference.Preference(context);
        note.setKey(key);
        note.setTitle(Lang.t(context, "Сенсоры не найдены", "No sensors found"));
        note.setSummary(Lang.t(context, "Камеры ещё не определены. Откройте видоискатель и вернитесь в это меню.",
                "Cameras are not detected yet. Open the viewfinder and come back to this menu."));
        note.setSelectable(false);
        group.addPreference(note);
    }

    /**
     * The physical ids of the scanned cameras (the cameras file), numeric ids in numeric order; empty before the first scan.
     */
    private static List<String> getSortedPhysicalIds() {
        SettingsManager m = com.scamera.app.app.ScameraApp.getSettingsManagerStatic();
        Set<String> scanned = m == null ? null : m.getStringSet(PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME.mValue,
                PreferenceKeys.Key.ALL_CAMERA_IDS_KEY, null);
        List<String> ids = new ArrayList<>();
        if (scanned == null) return ids;
        for (String id : scanned) {
            String physical = toPhysicalId(id);
            if (!ids.contains(physical)) ids.add(physical);
        }
        ids.sort((a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } catch (NumberFormatException notNumeric) {
                return a.compareTo(b);
            }
        });
        return ids;
    }

    /** The page's fields in {@link #rank} order (getDeclaredFields() has no defined order). */
    private static List<FieldSpec> scanFields() {
        List<FieldSpec> fields = new ArrayList<>();
        for (Class<?> c : SensorConfigRegistry.OWNERS) fields.addAll(FieldSpec.sensorConfigs(c));
        fields.sort(java.util.Comparator.comparingInt(s -> rank(s.name)));
        return fields;
    }

    /** Position of a {@code @SensorConfig} field on the page (and in the module copy list); unknown fields last. */
    public static int rank(String fieldName) {
        int i = FIELD_ORDER.indexOf(fieldName);
        return i < 0 ? FIELD_ORDER.size() : i;
    }

    /** "logical-physical" ids ("0-1") name their physical part ("1"); other ids as they are. */
    public static String toPhysicalId(String id) {
        int dash = id == null ? -1 : id.indexOf('-');
        if (dash < 0) return id;
        int end = id.indexOf('-', dash + 1);
        return id.substring(dash + 1, end < 0 ? id.length() : end);
    }

    /** The module's category (kept when the group already has it). */
    private static PreferenceCategory findOrCreateCategory(Context context, PreferenceGroup group, String slot, CameraLensData lens) {
        final String key = CATEGORY_PREFIX + slot;
        for (int i = 0, n = group.getPreferenceCount(); i < n; i++) {
            androidx.preference.Preference p = group.getPreference(i);
            if (p instanceof PreferenceCategory && key.equals(p.getKey())) return (PreferenceCategory) p;
        }
        PreferenceCategory made = new PreferenceCategory(context);
        made.setKey(key);
        made.setTitle(moduleTitle(slot, lens));
        // P384: the chosen module's caption under the chips, not a group title of the page
        made.setLayoutResource(com.scamera.app.R.layout.preference_subcategory_layout);
        made.setIconSpaceReserved(false);
        group.addPreference(made);
        return made;
    }

    /** «1× · ID 3» for a module slot; «Sensor 3 · ID 0-3 · 1.0x» for a bare camera id. */
    private static String moduleTitle(String slot, CameraLensData lens) {
        if (ModuleRegistry.slots().contains(slot))
            return ModuleRegistry.label(slot) + " · ID " + ModuleRegistry.camera(slot)
                    + (ModuleRegistry.visible(slot) ? "" : Lang.t(" · скрыт", " · hidden"));
        StringBuilder t = new StringBuilder(Lang.t("Сенсор ", "Sensor ")).append(slot);
        if (lens == null) return t.toString();
        if (lens.getCameraId() != null) t.append(" · ID ").append(lens.getCameraId());
        return t.append(" · ").append(String.format(Locale.ROOT, "%.1fx", lens.getZoomFactor())).toString();
    }

    /** Lens data of the scanned cameras (the cameras file) by physical id. */
    private static Map<String, CameraLensData> getCameraLensMap() {
        Map<String, CameraLensData> out = new HashMap<>();
        SettingsManager m = com.scamera.app.app.ScameraApp.getSettingsManagerStatic();
        Set<String> rows = m == null ? null : m.getStringSet(PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME.mValue,
                PreferenceKeys.Key.ALL_CAMERA_LENS_KEY, null);
        if (rows == null) return out;
        Gson gson = new Gson();
        for (String json : rows) {
            try {
                CameraLensData lens = gson.fromJson(json, CameraLensData.class);
                if (lens != null && lens.getCameraId() != null) out.put(toPhysicalId(lens.getCameraId()), lens);
            } catch (RuntimeException broken) {
                // a row another version wrote: no lens data for it
            }
        }
        return out;
    }

    /** The row of one field for one module: a list, a text field (step 0) or a slider. */
    private static void addPreference(Context context, PreferenceCategory category, String slot, FieldSpec s) {
        // OIS rows only where the module's camera has hardware stabilization
        if ("oisMode".equalsIgnoreCase(s.name) && !isOisSupported(context, ModuleSensorSettings.physical(slot))) return;
        final String key = "pref_sensorconfig_" + slot + "_" + s.keyTail;
        // A config import may have stored the value as a number: the list and text rows read text.
        storeAsText(PreferenceManager.getDefaultSharedPreferences(context), key);
        final String title = ModuleSensorSettings.title(s.title);
        final String text = ModuleSensorSettings.description(s.description);
        final Integer icon = FIELD_ICONS.get(s.keyTail);
        if (s.isList()) {
            AnnotatedRows.addList(context, category, key, title, s.description.isEmpty() ? "" : text, s,
                    AnnotatedRows.listDefault(s, false), icon);
        } else if (s.isText()) {
            category.addPreference(textRow(context, key, title, text, s, icon));
        } else {
            TunableSeekBarPreference slider = new TunableSeekBarPreference(context);
            slider.setKey(key);
            slider.setTitle(title);
            if (!s.description.isEmpty()) slider.setSummary(text);
            slider.setMinValue(s.min);
            slider.setMaxValue(s.max);
            slider.setIsFloat(s.isFloating());
            slider.setStepPerUnit(1f / s.step);
            slider.setDefaultValue(s.fallback());
            rawLevelRow(context, slider, slot, s.name, key);
            category.addPreference(slider);
        }
    }

    /**
     * P149: the module's own RAW levels for the «Уровень чёрного / белого» rows: {black, white, bits} of the module's camera
     * (characteristics: the black pattern's largest value, the white level), null when the camera is unknown. Tests set it.
     */
    static java.util.function.BiFunction<Context, String, int[]> cameraLevels = SensorConfigPreferenceGenerator::readCameraLevels;

    private static int[] readCameraLevels(Context context, String physicalId) {
        try {
            android.hardware.camera2.CameraManager manager = context.getSystemService(android.hardware.camera2.CameraManager.class);
            if (manager == null || physicalId == null) return null;
            android.hardware.camera2.CameraCharacteristics c = manager.getCameraCharacteristics(physicalId);
            final int white = com.scamera.app.processing.RawLevels.staticWhite(c);
            final float[] black = com.scamera.app.processing.RawLevels.staticBlack(c);
            if (white <= 0) return null;
            float b = 0;
            if (black != null) for (float v : black) b = Math.max(b, v);
            return new int[]{Math.round(b), white, com.scamera.app.processing.RawLevels.bitsOfWhite(white)};
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * P149: «Авто» for -1 in the value pill, the module's own levels under the description, and a range in that module's RAW
     * units: black up to a quarter of its white (and 4x its black), white up to four bits above its white (streams wider than
     * declared, P127) - instead of 0..8192 / 0..65535, where one step of the bar was hundreds of levels. A stored value outside
     * that range widens it (it is what the shot uses).
     */
    private static void rawLevelRow(Context context, TunableSeekBarPreference seekBar, String slot, String field, String prefKey) {
        final boolean black = "blackLevelOverride".equals(field);
        if (!black && !"whiteLevelOverride".equals(field)) return;
        seekBar.setAutoValue(-1f, Lang.t("Авто", "Auto"));
        final int[] camera = cameraLevels.apply(context, ModuleSensorSettings.physical(slot));
        if (camera == null) return;
        float max = black ? Math.max(4f * camera[0], (camera[1] + 1) / 4f) : Math.min(65535f, (camera[1] + 1) * 16f - 1f);
        try {
            float stored = (float) PreferenceNumber.read(PreferenceValue.get(
                    PreferenceManager.getDefaultSharedPreferences(context), prefKey), -1);
            if (Float.isFinite(stored)) max = Math.max(max, stored);
        } catch (Exception ignored) {
        }
        seekBar.setMaxValue((float) Math.ceil(max));
        CharSequence summary = seekBar.getSummary();
        final String auto = String.format(Locale.ROOT,
                Lang.t("Авто у этого модуля: чёрный %d, белый %d (%d бит)", "Auto for this module: black %d, white %d (%d bit)"),
                camera[0], camera[1], camera[2]);
        // P592: the module's own levels are the row's state (shown); the description before them is the long press
        com.scamera.app.ui.settings.SettingHelp.state(seekBar, auto);
        seekBar.setSummary((summary == null ? "" : summary + "\n") + auto);
    }

    /**
     * A text row (step 0). P384: the value on the right is the one the camera uses - the default while none is stored (it
     * showed «—» there and the value again under the description).
     */
    private static EditTextPreference textRow(Context context, String key, String title, String text, FieldSpec s, Integer icon) {
        final String shown = AnnotatedRows.defaultText(s);
        EditTextPreference row = new EditTextPreference(context) {
            @Override public String getText() {
                String stored = super.getText();
                return stored == null ? shown : stored;
            }
        };
        AnnotatedRows.setUpText(context, row, key, title, s);
        if (icon != null) row.setIcon(icon);
        row.setSummaryProvider(p -> text.isEmpty() ? row.getText() : text);
        if (!text.isEmpty()) com.scamera.app.ui.settings.SettingHelp.describe(row, text); // P592
        return row;
    }

    /** Whether the physical camera has hardware optical stabilization. */
    private static boolean isOisSupported(Context context, String camera) {
        try {
            android.hardware.camera2.CameraManager cameras = context.getSystemService(android.hardware.camera2.CameraManager.class);
            return cameras != null && CaptureController.isOisSupported(cameras.getCameraCharacteristics(camera)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static final int NOT_STORED = -999999;

    /**
     * Rewrites a value stored as a number or flag (a config import) as text, which the list and text rows read: they threw
     * ClassCastException on it. Text values and missing keys stay as they are.
     */
    private static void storeAsText(SharedPreferences prefs, String key) {
        if (!prefs.contains(key)) return;
        try {
            prefs.getString(key, null);
            return;
        } catch (ClassCastException notText) {
            Log.w(TAG, key + " is not stored as text, rewriting it");
        }
        String text = null;
        try {
            int i = prefs.getInt(key, NOT_STORED);
            if (i != NOT_STORED) text = String.valueOf(i);
        } catch (ClassCastException notInt) {
            // try the next type
        }
        if (text == null) try {
            float f = prefs.getFloat(key, NOT_STORED);
            if (f != NOT_STORED) text = String.valueOf(f);
        } catch (ClassCastException notFloat) {
            // try the next type
        }
        if (text == null) try {
            text = prefs.getBoolean(key, false) ? "1" : "0";
        } catch (ClassCastException notFlag) {
            return; // a string set or a long: left alone
        }
        prefs.edit().putString(key, text).apply();
    }
}
