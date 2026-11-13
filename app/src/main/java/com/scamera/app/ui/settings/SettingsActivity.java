package com.scamera.app.ui.settings;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.material.snackbar.Snackbar;
import com.scamera.app.R;
import com.scamera.app.api.CameraMode;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.app.base.BaseActivity;
import com.scamera.app.settings.BackupRestoreUtil;
import com.scamera.app.processing.render.NoiseModelProfile;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.settings.TunablePreferenceGenerator;
import com.scamera.app.ui.settings.custompreferences.ResetPreferences;
import com.scamera.app.ui.settings.custompreferences.TunablePngPreference;
import com.scamera.app.util.Log;
import com.scamera.app.util.log.FragmentLifeCycleMonitor;
import com.scamera.app.util.Lang;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

import static com.scamera.app.settings.PreferenceKeys.SCOPE_GLOBAL;

public class SettingsActivity extends BaseActivity implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    /** P140: the launcher shortcut «Настройки» (res/xml/shortcuts.xml) opens this screen with this action. */
    public static final String ACTION_SHORTCUT = "org.codeaurora.snapcam.action.SETTINGS";
    /** P143: the shade's config menu «Импорт из файла…»: the config picker at once, back to the camera when cancelled. */
    public static final String ACTION_IMPORT_CONFIG = "org.codeaurora.snapcam.action.IMPORT_CONFIG";
    /** P143: the camera's config menu asks for the lasting access to Download/SCAMERA (the system folder picker). */
    public static final String ACTION_OPEN_FOLDER = "org.codeaurora.snapcam.action.OPEN_FOLDER";
    /** SIMPLE_UI: the camera opens a settings page at a row (the LUT tray's «Все LUT ›»): EXTRA_PAGE, EXTRA_KEY. */
    public static final String ACTION_OPEN_PAGE = "org.codeaurora.snapcam.action.OPEN_PAGE";
    public static final String EXTRA_PAGE = "open_page", EXTRA_KEY = "open_key";
    private static int cameraModeAsked = -1;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        // P140: from the shortcut the camera may never have run in this process: its defaults and the module-profile
        // listener (CameraActivity.onCreate) come first, so a change made here lands in the module's profile as usual
        // P262 (owner, 2026-10-19: importing a curve «просто перезапускается, а кривая не переносится»): the system file picker
        // in front, Android stopped the camera's process; it came back with these settings recreated, the camera never run,
        // so the defaults and the listener were missing and the settings crashed - the picked file lost. Any process that
        // has not set them up does it here.
        if (ACTION_SHORTCUT.equals(getIntent().getAction()) || !PreferenceKeys.defaultsSet()) {
            PreferenceManager.setDefaultValues(this, R.xml.preferences, com.scamera.app.settings.MigrationManager.readAgain);
            PreferenceKeys.setDefaults(this);
            if (ScameraApp.getSettings() != null) ScameraApp.getSettings().loadCache();
        }
        setContentView(R.layout.activity_settings);
        // P584: every text of the settings in the concept's Roboto Flex, its size in dp
        ConceptFonts.follow(getWindow().getDecorView());
        
        // Get camera mode from intent
        cameraModeAsked = getIntent().getIntExtra("camera_mode", -1);
        
        // Setup window insets to handle navigation bar
        setupWindowInsets();
        
        if (savedInstanceState == null) getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
        if (savedInstanceState == null && ACTION_OPEN_PAGE.equals(getIntent().getAction())) {
            String page = getIntent().getStringExtra(EXTRA_PAGE), key = getIntent().getStringExtra(EXTRA_KEY);
            if (page != null && key != null) openPageAt(page, key);
        }
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentLifeCycleMonitor(), true);

    }
    
    /** The page's container stays above the navigation bar (its bottom margin follows the bar's height). */
    private void setupWindowInsets() {
        View pages = findViewById(R.id.settings_container);
        if (pages == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(pages, (v, insets) -> {
            MarginLayoutParams margins = (MarginLayoutParams) v.getLayoutParams();
            if (margins != null) {
                margins.bottomMargin = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                v.setLayoutParams(margins);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(pages);
    }

    public void back(View view) {
        onBackPressed();
    }

    @Override
    protected void onPause() {
        // P156: leaving the settings returns the main settings to the camera module's profile (the chip may have chosen
        // another module for editing); before the camera's onResume, so the camera never opens with the edited module's values
        if (isFinishing()) com.scamera.app.settings.ModuleRegistry.syncProfile();
        super.onPause();
    }
    /** A nested page opens as a page of its own, sliding in (the lab its own screen; a module's pages stay the module's). */
    @Override
    public boolean onPreferenceStartScreen(@NonNull PreferenceFragmentCompat from, PreferenceScreen screen) {
        String key = screen.getKey();
        if (ScamLabFragment.SCREEN.equals(key)) {
            openLab();
            return true;
        }
        // P189: inside a module's group every page stays the module's (P384: its sensor is on its root page)
        String module = from instanceof SettingsFragment ? ((SettingsFragment) from).moduleProfile() : null;
        SettingsFragment page = module != null ? SettingsFragment.modulePage(module, key) : pageOf(key, null);
        slide(getSupportFragmentManager().beginTransaction())
                .replace(R.id.settings_container, page, key).addToBackStack(key).commit();
        return true;
    }

    /** The settings page of an XML screen ({@code root}, null for the main page), {@code target} marked and scrolled to. */
    private static SettingsFragment pageOf(@Nullable String root, @Nullable String target) {
        Bundle a = new Bundle();
        if (root != null) a.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, root);
        if (target != null) a.putString("search_target", target);
        SettingsFragment page = new SettingsFragment();
        page.setArguments(a);
        return page;
    }

    /** The slide of the settings' pages (an XML page, a module's pages, the settings copy). */
    static FragmentTransaction slide(FragmentTransaction ft) {
        return ft.setCustomAnimations(R.anim.animate_slide_left_enter, R.anim.animate_slide_left_exit,
                R.anim.animate_card_enter, R.anim.animate_slide_right_exit);
    }

    /** P277: the lab («Лаборатория», «Гайд и лаборатория» before 2026-10-27; the root row and the search). */
    void openLab() {
        getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, ScamLabFragment.list())
                .addToBackStack(ScamLabFragment.SCREEN).commit();
    }

    /** A page with one row marked and scrolled to (the camera's links into the settings). */
    void openPageAt(String page, String key) {
        getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, pageOf(page, key))
                .addToBackStack("open_page").commit();
    }

    void openSearchResult(SettingsSearchFragment.Entry entry) {
        if (ScamLabFragment.SCREEN.equals(entry.page) || ScamLabFragment.SCREEN.equals(entry.key)) { openLab(); return; }
        if (ModuleRows.LENS.equals(entry.page)) {
            // P413: a row of the lens page opens a module's lens page - the module of a sensor row, else the camera's module now
            String slot = lensSlot(entry.key);
            SettingsFragment lens = SettingsFragment.modulePage(slot != null ? slot
                    : com.scamera.app.settings.ModuleRegistry.active(), ModuleRows.LENS);
            lens.getArguments().putString("search_target", entry.key);
            if (slot != null) lens.getArguments().putString(SettingsFragment.ARG_SENSOR_SLOT, slot);
            getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, lens)
                    .addToBackStack("search_result").commit();
            return;
        }
        String root = "prefscreen".equals(entry.page) ? null : entry.page;
        getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, pageOf(root, entry.key))
                .addToBackStack("search_result").commit();
    }

    /** P413: the module of a sensor row's key (pref_sensorconfig_<slot>_…, pref_category_sensor_<slot>), null for another row. */
    @Nullable
    static String lensSlot(@Nullable String key) {
        if (key == null) return null;
        for (String prefix : new String[]{"pref_sensorconfig_",
                com.scamera.app.settings.SensorConfigPreferenceGenerator.CATEGORY_PREFIX}) {
            if (!key.startsWith(prefix)) continue;
            String rest = key.substring(prefix.length());
            int end = rest.indexOf('_');
            String slot = end < 0 ? rest : rest.substring(0, end);
            return slot.isEmpty() ? null : slot;
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    /** P495: the iOS back - the settings leave to the right over the camera coming back (the camera's push backwards). */
    @SuppressWarnings("deprecation")
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.motion_pop_enter, R.anim.motion_pop_exit);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener, PreferenceManager.OnPreferenceTreeClickListener {
        private static final String KEY_MAIN_PARENT_SCREEN = "prefscreen";
        /** P189: argument of a page of a module's group: the module profile it shows and edits ("back2@full200"). */
        static final String ARG_MODULE = "module_profile";
        /** P189: the rows {@link ModulePageFilter} hid on this module page (applyAvailability keeps them hidden). */
        private java.util.Set<String> moduleHidden = java.util.Collections.emptySet();
        /**
         * 2026-10-27: the rows this page's code hid (the two sound switches behind «Звуки»); applyAvailability keeps them hidden
         * (it used to show every row it did not hide itself, on every change and resume).
         */
        private final java.util.Set<String> codeHidden = new java.util.HashSet<>();
        /** Argument of a root page: the module whose sensor rows «Сенсор по модулям» shows (its chips change it; "" none). */
        public static final String ARG_SENSOR_SLOT = "sensor_slot";
        /**
         * P384: argument of a module profile's root page: the profile the main settings held when it opened (the main page behind it
         * shows that profile's values); back gives it back.
         */
        public static final String ARG_RETURN_PROFILE = "return_profile";
        private Activity activity;
        private com.scamera.app.settings.SensorConfigPreferenceGenerator.ModuleSelection sensorSelection;
        /** P384: the camera groups of a root page (the module cards, the Camera ID rows, the sensor chips). */
        @Nullable private ModuleRows moduleRows;
        /** P385: this page as built and the user's order on it (SettingsLayoutTree). */
        @Nullable private SettingsLayoutTree.Snapshot layout;
        private final ModuleRows.Host moduleHost = new ModuleRows.Host() {
            @Override public Context context() { return requireContext(); }
            @Override public void open(androidx.fragment.app.Fragment page, String name) {
                slide(getParentFragmentManager().beginTransaction()).replace(R.id.settings_container, page).addToBackStack(name).commit();
            }
            @Override public void recreate() { if (getActivity() != null) getActivity().recreate(); }
            @Nullable @Override public androidx.recyclerview.widget.RecyclerView list() { return getView() == null ? null : getListView(); }
            @Override public Bundle args() { return requireArguments(); }
            @Override public void headerChanged() { updateHeader(); }
            @Override public void resetSensor(String slot) { askSensorReset(slot); }
        };

        /**
         * One module's sensor settings and camera keys: P384 - «Сенсор по модулям» with that module chosen (was a page of its own;
         * P390: on «Камеры и модули»).
         */
        public static SettingsFragment sensorPage(String slot) {
            // P413: the module's lens page (its sensor rows head it)
            SettingsFragment page = modulePage(slot, ModuleRows.LENS);
            Bundle args = page.getArguments();
            args.putString(ARG_SENSOR_SLOT, slot);
            args.putString("search_target", com.scamera.app.settings.SensorConfigPreferenceGenerator.CATEGORY_PREFIX + slot);
            page.setArguments(args);
            return page;
        }

        /** P189: a page of {@code profile}'s group: the settings root ({@code rootKey} null) or one of its pages. */
        public static SettingsFragment modulePage(String profile, @Nullable String rootKey) {
            SettingsFragment page = new SettingsFragment();
            Bundle args = new Bundle();
            if (rootKey != null) args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, rootKey);
            args.putString(ARG_MODULE, profile);
            page.setArguments(args);
            return page;
        }

        /** P189: the module profile of this page of «Настройки по модулям»; null on the usual pages. */
        @Nullable String moduleProfile() {
            return getArguments() == null ? null : getArguments().getString(ARG_MODULE);
        }

        /**
         * P606: the module profile of a lens page (the module card's «Настройки линзы» and its sub-pages); null on any other page.
         */
        @Nullable String lensProfile() {
            final String module = moduleProfile();
            if (module == null || getArguments() == null) return null;
            final String root = getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT);
            if (ModuleRows.LENS.equals(root)) return module;
            if (root == null || fullPreferenceScreen == null) return null;
            final Preference lens = fullPreferenceScreen.findPreference(ModuleRows.LENS);
            return lens instanceof androidx.preference.PreferenceGroup && ((androidx.preference.PreferenceGroup) lens).findPreference(root) != null
                    ? module : null;
        }

        /** P189: a module page holds its profile in the main settings (the camera may have taken its own back meanwhile). */
        private static boolean takeProfile(@Nullable String module) {
            if (module == null || !PreferenceKeys.isPerLensSettingsOn() || module.equals(PreferenceKeys.profiles().editing())) return false;
            PreferenceKeys.profiles().activate(module);
            return true;
        }
        private SettingsManager settingsStore;
        private Context ctx;
        private View pageView;
        private PreferenceScreen fullPreferenceScreen;
        private boolean tunablesBuilt = false;
        private boolean sensorsBuilt = false;
        private ActivityResultLauncher<String[]> lutImportLauncher;
        private ActivityResultLauncher<String[]> noiseModelImportLauncher;
        private ActivityResultLauncher<String[]> configImportLauncher;
        /** P144: «Загрузить» → «Из файла…» of the own tone curve (the picker starts in Download/SCAMERA/ToneCurves). */
        private ActivityResultLauncher<String[]> curveImportLauncher;
        /** P134: «Плёночный LUT» → «Импортировать .cube…» (the picker starts in Download/SCAMERA/LUT). */
        private ActivityResultLauncher<String[]> cubeLutImportLauncher;
        /** P144: the switches of «Кривая AgX и ACES» of which one at most is on. */
        static final java.util.List<String> TONE_OPERATORS = java.util.Arrays.asList(
                "pref_scam_hybrid_why_aces_on", "pref_scam_hybrid_why_uchi_on", "pref_scam_hybrid_why_curve_on");
        /** P143: this screen was opened by the camera's config menu for the picker only. */
        private boolean importFromCamera;
        /** P143: Download/SCAMERA opened to the app once (ScameraFolder.grantedTree): every file there is listed. */
        private ActivityResultLauncher<android.net.Uri> folderGrantLauncher;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // P189: a module page binds its profile's values (before the rows are built; no listener of this page yet)
            String module = moduleProfile();
            takeProfile(module);
            ctx = requireContext();
            settingsStore = ScameraApp.getSettingsManagerStatic();
            // P384: the root page keeps its open module card and chosen sensor module in its arguments (they survive a recreate)
            if (getArguments() == null) setArguments(new Bundle());
            com.scamera.app.settings.SettingsMigration.prepare(requireContext(), settingsStore.getDefaultPreferences());
            setPreferencesFromResource(R.xml.preferences, null);
            generateTunablePreferences();
            if (rootKey == null || ModuleRows.PAGE.equals(rootKey) || ModuleRows.LENS.equals(rootKey)) {
                // P384: the camera groups, built from ModuleRegistry (P390: on their own page «Камеры и модули»; the root builds them
                // too, for the search); their rows open and close in place, so the list animates the difference (the other pages
                // keep their full rebind)
                generateSensorConfigPreferences();
                moduleRows = ModuleRows.build(moduleHost, getPreferenceScreen(), module, sensorSelection);
                if (ModuleRows.LENS.equals(rootKey) && sensorSelection != null)
                    LensPage.arrange(getPreferenceScreen(), sensorSelection.selected(), com.scamera.app.settings.PreferenceKeys.Key.KEY_CFA.mValue);
                getPreferenceManager().setPreferenceComparisonCallback(new PreferenceManager.SimplePreferenceComparisonCallback());
            }
            // «Для разработчика»: the hidden scam_dev.txt lines (DevKeys), edited in the file itself
            DeveloperPreferences.populate(getPreferenceScreen(), getPreferenceManager().getContext(), this::askScameraFolder);
            fullPreferenceScreen = getPreferenceScreen();
            if (rootKey != null) {
                PreferenceScreen selected = findPreference(rootKey);
                if (selected == null) throw new IllegalArgumentException("Unknown settings page: " + rootKey);
                selected.setVisible(true);   // P413: the lens page is hidden as a row of its parent, shown as a page
                // P592 (found by SettingsCoverageTest): a page that depends on a row of its parent («Маска лица» on «Защита лиц») is
                // the root here: its dependency is not in its tree and binding it threw; the dependency greys its row on the parent
                if (selected.getDependency() != null && selected.findPreference(selected.getDependency()) == null) selected.setDependency(null);
                setPreferenceScreen(selected);
            }
            // P385: the user's order of this page (before the module page's filter, which judges the groups as they end up)
            layout = SettingsLayoutTree.snapshot(getPreferenceScreen());
            if (layout != null) SettingsLayoutTree.apply(layout, SettingsLayoutTree.stored(settingsStore.getDefaultPreferences()));
            seedMissingListValues(fullPreferenceScreen);
            setupScalarInputs(getPreferenceScreen());
            // P17: the tele's smooth optical zoom exists on the Xiaomi 17 Ultra only
            Preference xiaomiZoom = findPreference(com.scamera.app.capture.XiaomiTeleZoom.PREF);
            if (xiaomiZoom != null && !com.scamera.app.capture.XiaomiTeleZoom.phone()) xiaomiZoom.setVisible(false);
            Preference hwRemosaic = findPreference(com.scamera.app.capture.VivoSdkFullRes.PREF_HW_REMOSAIC);
            if (hwRemosaic != null && !com.scamera.app.capture.VivoSdkFullRes.hardwareRemosaicOffered()) hwRemosaic.setVisible(false);
            // P190: «Кадров в снимке 50 / 200 Мп» only where a module has FULLRES of that size
            for (String key : new String[]{com.scamera.app.settings.FullResProfiles.FRAMES_50,
                    com.scamera.app.settings.FullResProfiles.FRAMES_200}) {
                Preference frames = findPreference(key);
                if (frames != null && !com.scamera.app.settings.FullResProfiles.sizeOffered(
                        com.scamera.app.settings.FullResProfiles.framesTag(key))) frames.setVisible(false);
            }
            if (module != null) moduleHidden = ModulePageFilter.apply(getPreferenceScreen(), module, rootKey == null);
            // P413: the lens page is a module card's, never a row of «Камеры и модули» (the availability pass keeps it hidden)
            if (!ModuleRows.LENS.equals(rootKey)) {
                Preference lensPage = findPreference(ModuleRows.LENS);
                if (lensPage != null) { lensPage.setVisible(false); codeHidden.add(ModuleRows.LENS); }
            }
            setupPhotoFormat();
            setupSounds();
            setupCaptureReport();
            SettingsStyle.apply(getPreferenceScreen());
        }

        /**
         * «Формат фото» is the one format choice of the top bar and the shade (FormatChoice): JPEG, HEIC, WebP, AVIF, RAW,
         * RAW + JPEG / HEIC / WebP / AVIF, stored as the save mode and the codec, never as a value of its own (the list is
         * not persistent). HEIC needs Android 9 (PhotoFormat.HEIC_MIN_SDK), AVIF Android 12 and its encoder: without them
         * those options are not offered.
         */
        private void setupPhotoFormat() {
            ListPreference format = findPreference(com.scamera.app.processing.PhotoFormat.KEY);
            if (format == null) return;
            java.util.List<com.scamera.app.settings.FormatChoice> offered =
                    com.scamera.app.settings.FormatChoice.offered(android.os.Build.VERSION.SDK_INT);
            CharSequence[] entries = new CharSequence[offered.size()], values = new CharSequence[offered.size()];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = offered.get(i).longLabel();
                values[i] = offered.get(i).name();
            }
            format.setPersistent(false);
            format.setEntries(entries);
            format.setEntryValues(values);
            format.setValue(com.scamera.app.settings.FormatChoice.current().name());
            format.setOnPreferenceChangeListener((preference, value) -> {
                com.scamera.app.settings.FormatChoice.store(
                        com.scamera.app.settings.FormatChoice.valueOf(value.toString()));
                return true;
            });
        }

        /**
         * P319 (user report 2026-10-22: «Многие меню можно объединить, например "звук затвора" и "звук таймера"»): one «Звуки»
         * row - shutter and timer, shutter only, timer only, none - over the two switches (their keys, defaults, the shade's
         * sound tile and backups unchanged), which this page hides. Not persistent: it reads and writes the two switches.
         */
        private void setupSounds() {
            ListPreference sounds = findPreference("pref_camera_sounds_all");
            androidx.preference.TwoStatePreference shutter = findPreference(com.scamera.app.settings.PreferenceKeys.Key.KEY_CAMERA_SOUNDS.mValue);
            androidx.preference.TwoStatePreference timer = findPreference(com.scamera.app.settings.PreferenceKeys.Key.KEY_TIMER_SOUND.mValue);
            if (sounds == null) return;
            if (shutter == null || timer == null) {
                sounds.setVisible(false);
                codeHidden.add(sounds.getKey());
                return;
            }
            shutter.setVisible(false);
            timer.setVisible(false);
            codeHidden.add(shutter.getKey());
            codeHidden.add(timer.getKey());
            sounds.setPersistent(false);
            sounds.setValue(soundsValue(shutter.isChecked(), timer.isChecked()));
            sounds.setOnPreferenceChangeListener((preference, value) -> {
                final String v = String.valueOf(value);
                shutter.setChecked("all".equals(v) || "shutter".equals(v));
                timer.setChecked("all".equals(v) || "timer".equals(v));
                return true;
            });
        }

        static String soundsValue(boolean shutter, boolean timer) {
            return shutter ? timer ? "all" : "shutter" : timer ? "timer" : "none";
        }

        // ───── P6b: the card look of «Камеры и сенсоры» on every page ─────
        private SettingsStyle.Header header;
        private android.widget.LinearLayout chipBox;
        /** P574: the collapsing header (a search hit scrolls to its row with the header collapsed, not under it). */
        @Nullable private com.google.android.material.appbar.AppBarLayout headerBar;

        @NonNull @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
            View list = super.onCreateView(inflater, container, state);
            Context c = requireContext();
            android.widget.LinearLayout page = new android.widget.LinearLayout(c);
            page.setOrientation(android.widget.LinearLayout.VERTICAL);
            page.setBackgroundColor(SettingsStyle.PAGE);
            // P584: the concept's bar - 16dp over its 44dp (the 40dp discs centred in it)
            page.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 16), 0);
            // SIMPLE_UI §4: as the concept, the search is a pill under the root's header only; P189: a module page has none
            // (a hit would open the usual page)
            header = SettingsStyle.header(c, () -> requireActivity().getOnBackPressedDispatcher().onBackPressed(), null, this::resetPage);
            // P385: the page's order (a module page shows the same pages filtered: arranged on the usual ones)
            if (moduleProfile() == null && SettingsLayoutFragment.editable(layout)) SettingsStyle.arrangeDisc(c, header, this::openLayout);
            boolean root = getArguments() == null || getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) == null;
            // P574 (the concept): the bar of round buttons stays; the large title, the search and the chips scroll away with the
            // list (and come back at its top), the bar's small title taking the large one's place
            header.view.removeAllViews();
            page.addView(header.bar, new android.widget.LinearLayout.LayoutParams(-1, -2));
            android.widget.LinearLayout top = new android.widget.LinearLayout(c);
            top.setOrientation(android.widget.LinearLayout.VERTICAL);
            top.addView(header.titles, new android.widget.LinearLayout.LayoutParams(-1, -2));
            if (root && moduleProfile() == null) {
                android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(-1, -2);
                sp.bottomMargin = SettingsStyle.dp(c, 10);   // P584: the concept's 10dp under the search
                top.addView(SettingsStyle.searchPill(c, this::openSearch), sp);
                // 2026-10-27 (owner: settings anyone can set up alone): the two gestures that explain a setting and put its
                // default back. P592 (owner's review: the hints under the search go): said once, the first time the settings open
                tipOnce(c);
            }
            chipBox = new android.widget.LinearLayout(c);
            top.addView(chipBox, new android.widget.LinearLayout.LayoutParams(-1, -2));
            androidx.coordinatorlayout.widget.CoordinatorLayout scroller = new androidx.coordinatorlayout.widget.CoordinatorLayout(c);
            com.google.android.material.appbar.AppBarLayout bar = new com.google.android.material.appbar.AppBarLayout(c);
            // fixed ids: the coordinator keeps the header's collapsed state when the page comes back
            scroller.setId(R.id.settings_header_coordinator);
            bar.setId(R.id.settings_header_bar);
            headerBar = bar;
            bar.setBackgroundColor(SettingsStyle.PAGE);
            bar.setStateListAnimator(null);
            bar.setElevation(0f);
            bar.setTag("settings_header_scroll");
            com.google.android.material.appbar.AppBarLayout.LayoutParams tl = new com.google.android.material.appbar.AppBarLayout.LayoutParams(-1, -2);
            tl.setScrollFlags(com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL);
            bar.addView(top, tl);
            bar.addOnOffsetChangedListener((v, offset) -> {
                int h = header.titles.getHeight();
                SettingsStyle.collapse(header, h <= 0 ? 0f : -offset / (float) h);
            });
            scroller.addView(bar, new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1, -2));
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp = new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1, -1);
            lp.setBehavior(new com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior());
            scroller.addView(list, lp);
            page.addView(scroller, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
            return page;
        }

        @NonNull @Override
        public androidx.recyclerview.widget.RecyclerView onCreateRecyclerView(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent, @Nullable Bundle state) {
            androidx.recyclerview.widget.RecyclerView list = super.onCreateRecyclerView(inflater, parent, state);
            list.setClipToPadding(false);
            list.setPadding(0, 0, 0, SettingsStyle.dp(requireContext(), 28));
            list.setVerticalScrollBarEnabled(false);
            // P574: a row changed in place is bound again in the same view (no cross-fade to a new one): a switch slides, a
            // segmented control's thumb glides, a slider keeps its knob
            if (list.getItemAnimator() instanceof androidx.recyclerview.widget.SimpleItemAnimator)
                ((androidx.recyclerview.widget.SimpleItemAnimator) list.getItemAnimator()).setSupportsChangeAnimations(false);
            // SIMPLE_UI §4: the rows of a group share one card (the concept's .card / .row)
            list.addItemDecoration(new SettingsCardDecoration(requireContext(), SettingsStyle::standalone));
            // P384: the module cards open and close in place: rows fade in with a small rise, the rows below glide
            if (getArguments() == null || getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) == null
                    || ModuleRows.PAGE.equals(getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT)))
                list.setItemAnimator(new SettingsMotion.ItemAnimator(requireContext()));
            return list;
        }

        /** Three columns only so that the «Конфиг» tiles share a row; every other row spans all three. */
        @NonNull @Override
        public androidx.recyclerview.widget.RecyclerView.LayoutManager onCreateLayoutManager() {
            androidx.recyclerview.widget.GridLayoutManager grid = new androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3);
            grid.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                @Override public int getSpanSize(int position) {
                    androidx.recyclerview.widget.RecyclerView list = getListView();
                    if (list == null || !(list.getAdapter() instanceof androidx.preference.PreferenceGroupAdapter)) return 3;
                    Preference p = ((androidx.preference.PreferenceGroupAdapter) list.getAdapter()).getItem(position);
                    return p != null && p.getKey() != null && SettingsStyle.TILES.contains(p.getKey()) ? 1 : 3;
                }
            });
            return grid;
        }

        @NonNull @Override
        protected androidx.recyclerview.widget.RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen screen) {
            return new androidx.preference.PreferenceGroupAdapter(screen) {
                @Override public void onBindViewHolder(@NonNull androidx.preference.PreferenceViewHolder holder, int position) {
                    super.onBindViewHolder(holder, position);
                    Preference p = getItem(position);
                    if (p != null) SettingsStyle.bind(holder, p);
                    // P319: a long press on a row opens its help (what it does, how to set it, its value); P592: the only place
                    // a description is shown. A recycled row of a category loses the listener of the row it showed before.
                    if (SettingHelp.helps(p)) {
                        holder.itemView.setOnLongClickListener(v -> {
                            SettingHelp.show(v.getContext(), p, lensProfile());   // P606: a lens page's pin is its lens's
                            return true;
                        });
                    } else {
                        holder.itemView.setOnLongClickListener(null);
                        holder.itemView.setLongClickable(false);
                    }
                    // P592: a note (a row that only explains) shows its title; a tap opens its text as the long press does
                    if (p != null && SettingsStyle.note(p)) holder.itemView.setOnClickListener(v -> SettingHelp.show(v.getContext(), p));
                }
            };
        }

        /**
         * P357 (owner, 2026-10-27: «добавь кнопку сброса всех настроек»): the header's reset - on the root page the full reset of
         * «Сбросить настройки» (its dialog), on any other page this page's rows back to their defaults (PageReset), confirmed.
         */
        private void resetPage() {
            final Context c = getContext();
            final PreferenceScreen screen = getPreferenceScreen();
            if (c == null || screen == null) return;
            final boolean root = getArguments() == null || getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) == null;
            if (root && moduleProfile() == null) {
                Preference all = fullPreferenceScreen == null ? null : fullPreferenceScreen.findPreference("pref_reset_preferences_key");
                if (all != null) onDisplayPreferenceDialog(all);
                return;
            }
            final CharSequence title = screen.getTitle();
            // P574 (the concept): the centred confirmation, «Отмена» / red «Сбросить»
            DialogStyle.confirm(c, Lang.t(c, "Сбросить «" + title + "»?", "Reset «" + title + "»?"),
                    // P584: the concept's alert text (the pages inside it keep theirs: PageReset resets this page's rows only)
                    Lang.t(c, "Пункты этой страницы вернутся к значениям по умолчанию.",
                            "The settings of this page go back to their defaults."),
                    Lang.t(c, "Сбросить", "Reset"), () -> {
                        int n = PageReset.reset(c, screen);
                        // the developer rows write scam_dev.txt, not the preferences
                        if (DeveloperPreferences.SCREEN.equals(screen.getKey()))
                            n += DeveloperPreferences.resetAll(fullPreferenceScreen != null ? fullPreferenceScreen : screen,
                                    getPreferenceManager().getContext(), this::askScameraFolder);
                        android.widget.Toast.makeText(c, n == 0 ? Lang.t(c, "Всё уже по умолчанию", "Everything is at its default already")
                                : Lang.t(c, "Сброшено параметров: " + n, "Settings reset: " + n), android.widget.Toast.LENGTH_SHORT).show();
                    });
        }

        /**
         * P630h (owner: «Добавь больше настроек для Oldstyle» - with a row that undoes them): «Сбросить Oldstyle» - every row of the
         * categories of «Oldstyle Тон» back to its default through {@link PageReset} (as the header's page reset does), confirmed;
         * the tone version (the page's first row, also the camera panel's tile) and the panel's style are not this row's.
         */
        private void resetOldstyle() {
            final Context c = getContext();
            final PreferenceScreen screen = getPreferenceScreen();
            if (c == null || screen == null) return;
            DialogStyle.confirm(c, Lang.t(c, "Сбросить ScamMaster?", "Reset ScamMaster?"),
                    Lang.t(c, "Все ползунки и переключатели «ScamMaster Тон» вернутся к значениям по умолчанию. Версия тона и стиль останутся.",
                            "Every slider and switch of “ScamMaster Tone” goes back to its default. The tone version and the style stay."),
                    Lang.t(c, "Сбросить", "Reset"), () -> {
                        final int n = resetOldstyleRows(c, screen);
                        android.widget.Toast.makeText(c, n == 0 ? Lang.t(c, "Всё уже по умолчанию", "Everything is at its default already")
                                : Lang.t(c, "Сброшено параметров: " + n, "Settings reset: " + n), android.widget.Toast.LENGTH_SHORT).show();
                    });
        }

        /** The rows of the categories of {@code screen} («Oldstyle Тон») back to their defaults; how many changed. */
        static int resetOldstyleRows(Context c, PreferenceScreen screen) {
            int n = 0;
            for (String key : com.scamera.app.settings.OldstyleMode.CATEGORIES) {
                final Preference group = screen.findPreference(key);
                if (group instanceof androidx.preference.PreferenceGroup) n += PageReset.reset(c, (androidx.preference.PreferenceGroup) group);
            }
            return n;
        }

        /** P592: the store of the one-time tips (not the settings: a config import or a reset does not show them again). */
        static final String TIPS = "scamera_tips", TIP_LONG_PRESS = "long_press_tip_shown";

        /** P592: «Удерживайте пункт…», the long press and the double tap of a slider - a toast the first time the settings open. */
        public static void tipOnce(Context c) {
            try {
                android.content.SharedPreferences tips = c.getSharedPreferences(TIPS, Context.MODE_PRIVATE);
                if (tips.getBoolean(TIP_LONG_PRESS, false)) return;
                tips.edit().putBoolean(TIP_LONG_PRESS, true).apply();
                android.widget.Toast.makeText(c, Lang.t(c,
                        "Удерживайте пункт, чтобы узнать, что он делает. Двойной тап по ползунку — значение по умолчанию.",
                        "Hold a row to see what it does. Double-tap a slider to restore its default."), android.widget.Toast.LENGTH_LONG).show();
            } catch (RuntimeException ignored) {
                // no tip is better than no settings
            }
        }

        /**
         * P574 (the concept): the bar's path to this page - «Настройки › Обработка › …» (the pages above it), none on the root.
         */
        private String crumb(@Nullable PreferenceScreen screen) {
            boolean root = getArguments() == null || getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) == null;
            if (root || screen == null) return "";
            java.util.ArrayList<String> path = new java.util.ArrayList<>();
            for (androidx.preference.PreferenceGroup g = screen.getParent(); g != null; g = g.getParent())
                if (g instanceof PreferenceScreen && g.getParent() != null && !android.text.TextUtils.isEmpty(g.getTitle())) path.add(0, g.getTitle().toString());
            path.add(0, Lang.t(getContext(), "Настройки", "Settings"));
            return android.text.TextUtils.join(" › ", path);
        }

        /** Title, and the chip: the active route on the root page, the module being edited on pages with per-module values. */
        private void updateHeader() {
            if (header == null || !isAdded()) return;
            PreferenceScreen screen = getPreferenceScreen();
            CharSequence title = screen == null ? null : screen.getTitle();
            // P584 (the concept): the main page is «Настройки», as its crumb names it on every other page
            boolean main = getArguments() == null || getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) == null;
            header.heading.setText(main || title == null || title.length() == 0 ? Lang.t(getContext(), "Настройки", "Settings") : title);
            header.subtitle.setVisibility(View.GONE);
            header.crumb.setText(crumb(screen));
            // P592 (owner, 2026-11-10: «Убираем все описания»): a page's description is no longer under its large title (it was,
            // P574); it is the long press on the row that opens the page. A module page's subtitle below is a state and stays.
            chipBox.removeAllViews();
            String chip = null;
            boolean modulePicker = false;
            String module = moduleProfile();
            if (module != null) {
                // P189: a page of the module's group: the profile it edits, fixed (no picker)
                String slot = com.scamera.app.settings.FullResProfiles.slotOf(module);
                if (getArguments() == null || getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) == null) {
                    header.heading.setText(com.scamera.app.settings.FullResProfiles.label(module));
                    String size = com.scamera.app.settings.FullResProfiles.size(module);
                    header.subtitle.setText(size != null ? Lang.t(getContext(), "Действует, когда включён FULLRES " + size, "Applies while FULLRES " + size + " is on")
                            : !com.scamera.app.settings.FullResProfiles.variants(slot).isEmpty() ? Lang.t(getContext(), "Действует без FULLRES", "Applies without FULLRES")
                            : Lang.t(getContext(), "Только настройки этого модуля", "This module's own settings"));
                    header.subtitle.setVisibility(View.VISIBLE);
                } else if (screen != null && ModuleRows.LENS.equals(screen.getKey())) {
                    // P413: the lens page names its module (its sensor rows are the module's, shared with its FULLRES shots)
                    String id = com.scamera.app.settings.FullResProfiles.label(module) + " · ID "
                            + com.scamera.app.settings.ModuleRegistry.camera(slot);
                    header.subtitle.setText(com.scamera.app.settings.FullResProfiles.isVariant(module)
                            || !com.scamera.app.settings.FullResProfiles.variants(slot).isEmpty()
                            ? id + "\n" + getString(R.string.module_sensor_shared, com.scamera.app.settings.ModuleRegistry.label(slot)) : id);
                    header.subtitle.setVisibility(View.VISIBLE);
                }
                chip = PreferenceKeys.isPerLensSettingsOn()
                        ? Lang.t(getContext(), "Настраивается: ", "Editing: ") + com.scamera.app.settings.FullResProfiles.label(module)
                                + " · ID " + com.scamera.app.settings.ModuleRegistry.camera(slot)
                        : Lang.t(getContext(), "Общие настройки: свои настройки модулей выключены", "Common settings: separate module settings are off");
            }
            else if (screen != null && PreferenceKeys.isPerLensSettingsOn() && SettingsStyle.hasModuleSettings(screen)) {
                // P156 (owner: «Сделай так чтобы эта кнопка была интерактивна и мы могли менять модули тут для настройки»):
                // the module whose profile is edited here; with more than one shown module the chip picks another one
                String slot = editedModule();
                chip = Lang.t(getContext(),"Настраивается: ","Editing: ") + com.scamera.app.settings.FullResProfiles.label(slot)
                        + " · ID " + com.scamera.app.settings.ModuleRegistry.camera(com.scamera.app.settings.FullResProfiles.slotOf(slot));
                modulePicker = editableModules().size() > 1;
                if (modulePicker) chip += "  ▾";
            }
            if (chip != null) {
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = SettingsStyle.dp(requireContext(), 4);
                android.widget.TextView view = SettingsStyle.chip(requireContext(), chip);
                if (modulePicker) {
                    view.setOnClickListener(v -> pickEditedModule());
                    view.setFocusable(true);
                    view.setContentDescription(chip.replace("  ▾", "") + Lang.t(getContext(),". Выбрать другой модуль",". Choose another module"));
                }
                chipBox.addView(view, lp);
            }
        }

        /**
         * P156: the module whose profile the main settings hold (the camera's module unless the chip chose another); P181: a
         * module's FULLRES profile («back2@full200») while FULLRES is on for it.
         */
        public static String editedModule() {
            String slot = null;
            try { slot = PreferenceKeys.profiles().editing(); } catch (RuntimeException ignored) { }
            return slot == null || slot.isEmpty() ? com.scamera.app.settings.FullResProfiles.profileFor(com.scamera.app.settings.ModuleRegistry.active()) : slot;
        }

        /**
         * P156: the shown modules, the back ones by zoom, then the front ones; P181 (owner: «отдельные настройки для 50мп/200мп
         * как для отдельных модулей»): each module with FULLRES followed by its FULLRES profiles (50 MP, 200 MP).
         */
        static java.util.List<String> editableModules() {
            java.util.List<String> slots = new java.util.ArrayList<>(), out = new java.util.ArrayList<>();
            try {
                for (String slot : com.scamera.app.settings.ModuleRegistry.slots())
                    if (com.scamera.app.settings.ModuleRegistry.visible(slot)) slots.add(slot);
            } catch (RuntimeException ignored) { }
            slots.sort(java.util.Comparator.comparing((String s) -> s.startsWith("front"))
                    .thenComparingDouble(com.scamera.app.settings.ModuleRegistry::zoom));
            for (String slot : slots) {
                out.add(slot);
                out.addAll(com.scamera.app.settings.FullResProfiles.variants(slot));
            }
            return out;
        }

        /**
         * P156: the chip's picker on the pages with per-module values: the chosen module's profile goes into the main settings,
         * the pages show and edit its values (the activity is recreated, the open page stays); the camera keeps its module,
         * and leaving the settings returns to the camera module's profile.
         */
        private void pickEditedModule() {
            java.util.List<String> slots = editableModules();
            String editing = editedModule(), camera = com.scamera.app.settings.FullResProfiles.profileFor(com.scamera.app.settings.ModuleRegistry.active());
            CharSequence[] labels = new CharSequence[slots.size()], tags = new CharSequence[slots.size()];
            for (int i = 0; i < slots.size(); i++) {
                String slot = slots.get(i);
                labels[i] = com.scamera.app.settings.FullResProfiles.label(slot) + " · ID "
                        + com.scamera.app.settings.ModuleRegistry.camera(com.scamera.app.settings.FullResProfiles.slotOf(slot))
                        + (slot.equals(camera) ? Lang.t(getContext(), " — сейчас в камере", " — in the camera now") : "");
                tags[i] = slot;
            }
            SettingsStyle.optionSheet(requireContext(), Lang.t(getContext(),"Настраивать модуль","Module to edit"), labels, tags,
                    slots.indexOf(editing), SettingsStyle.accent(requireContext()), i -> {
                        String slot = slots.get(i);
                        if (slot.equals(editing)) return;
                        PreferenceKeys.profiles().activate(slot);
                        if (getActivity() != null) getActivity().recreate();
                    });
        }

        private void openSearch() {
            SettingsSearchFragment fragment = SettingsSearchFragment.create(SettingsSearchFragment.index(fullPreferenceScreen));
            getParentFragmentManager().beginTransaction().replace(R.id.settings_container, fragment).addToBackStack("settings_search").commit();
        }

        private void setupScalarInputs(PreferenceGroup group) {
            for (int i=0; i<group.getPreferenceCount(); i++) {
                Preference p=group.getPreference(i);
                if (p instanceof PreferenceGroup) { setupScalarInputs((PreferenceGroup)p); continue; }
                if (!(p instanceof androidx.preference.EditTextPreference) || p.getKey()==null) continue;
                double[] bounds=com.scamera.app.settings.SettingsNumericRules.bounds(p.getKey());
                if (bounds==null) continue;
                androidx.preference.EditTextPreference edit=(androidx.preference.EditTextPreference)p;
                edit.setOnBindEditTextListener(input -> input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        | (bounds[2]==1 ? 0 : android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)));
                edit.setOnPreferenceChangeListener((preference,value) -> {
                    String error=com.scamera.app.settings.SettingsNumericRules.error(p.getKey(),value);
                    if(error!=null) ScameraApp.showToast(error);
                    return error==null;
                });
            }
        }




        private void setupCaptureReport() {
            // P129: the last SHDR shot's worker report (it used to sit behind the SCAM HDR launch check)
            Preference report = findPreference("shdr_capture_report");
            if (report != null) report.setOnPreferenceClickListener(pref -> {
                startActivity(new android.content.Intent(requireContext(), ScamActivity.class));
                return true;
            });
            Preference clear = findPreference("scamera_log_clear");
            if (clear != null) {
                clear.setSummary(getString(R.string.scamera_log_clear_summary, "0 B"));
                updateLogSize(clear);
                clear.setOnPreferenceClickListener(pref -> {
                    com.scamera.app.util.ScameraFileLog.clear(ok -> {
                        if (!isAdded()) return;
                        ScameraApp.showToast(getString(ok ? R.string.scamera_log_cleared : R.string.scamera_log_failed));
                        updateLogSize(clear);
                    });
                    return true;
                });
            }
            Preference share = findPreference("scamera_log_share");
            if (share != null) share.setOnPreferenceClickListener(pref -> {
                com.scamera.app.util.ScameraFileLog.share(requireContext(), ok -> {
                    if (!ok && isAdded()) ScameraApp.showToast(getString(R.string.scamera_log_failed));
                });
                return true;
            });
            // P214f: the last 20 messages of the camera screen's message card (UiMessages), newest first
            Preference messages = findPreference("scam_recent_messages");
            if (messages != null) messages.setOnPreferenceClickListener(pref -> {
                MessageHistorySheet.show(requireContext());
                return true;
            });
        }


        private void updateLogSize(Preference clear) {
            com.scamera.app.util.ScameraFileLog.size(bytes -> {
                if (isAdded()) clear.setSummary(getString(R.string.scamera_log_clear_summary,
                        android.text.format.Formatter.formatShortFileSize(requireContext(), bytes)));
            });
        }

        /**
         * ListPreference.SimpleSummaryProvider calls getEntry() while binding the row, and
         * getEntry() throws NullPointerException when no value has been persisted yet.
         * Defaults declared in XML are only written by PreferenceManager.setDefaultValues(),
         * which is skipped for keys added after the first run, so a freshly introduced
         * ListPreference binds with a null value and takes the whole settings screen down.
         * Seed those entries from their first entryValue before the adapter ever sees them.
         */
        private void seedMissingListValues(PreferenceGroup group) {
            if (group == null) {
                return;
            }
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference preference = group.getPreference(i);
                if (preference instanceof PreferenceGroup) {
                    seedMissingListValues((PreferenceGroup) preference);
                } else if (preference instanceof ListPreference) {
                    ListPreference list = (ListPreference) preference;
                    CharSequence[] values = list.getEntryValues();
                    if (list.getValue() == null && values != null && values.length > 0) {
                        Log.w("SettingsActivity", "No stored value for " + list.getKey()
                                + ", falling back to " + values[0]);
                        list.setValueIndex(0);
                    }
                }
            }
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            activity = getActivity();
            ctx = getContext();
            settingsStore = Objects.requireNonNull(ScameraApp.getInstance(activity)).getSettingsManager();

            // The PNG rows' import: the system's document picker (not the gallery); the row's list opens again after it
            lutImportLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), picked -> {
                if (picked == null) return;
                String refused = TunablePngPreference.handleImportResult(ctx, picked);
                ScameraApp.showToast(refused == null ? Lang.t(ctx, "PNG загружен", "PNG imported")
                        : Lang.t(ctx, "PNG не загружен: ", "PNG import failed: ") + refused);
                TunablePngPreference.refreshActivePreference();
            });
            TunablePngPreference.setImportLauncher(lutImportLauncher);

            // Noise-model calibration files are read as data: the coefficient arrays are
            // matched textually, nothing in the file is compiled or executed.
            noiseModelImportLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) {
                            return;
                        }
                        String result = importNoiseModel(uri);
                        ScameraApp.showToast(result);
                    }
            );

            // Config import from any folder (a file copied from another phone is not listed by MediaStore);
            // the picker starts in Download/SCAMERA/XML.
            configImportLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument() {
                        @NonNull @Override
                        public Intent createIntent(@NonNull android.content.Context context, @NonNull String[] input) {
                            Intent intent = super.createIntent(context, input);
                            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                    com.scamera.app.util.ConfigFolder.initialPickerUri());
                            return intent;
                        }
                    },
                    uri -> {
                        if (uri == null) {
                            // P143: opened from the camera for the picker only: back to the camera
                            if (importFromCamera && getActivity() != null) getActivity().finish();
                            return;
                        }
                        String result = BackupRestoreUtil.restoreFromUri(ctx, uri);
                        if (pageView != null) Snackbar.make(pageView, result, Snackbar.LENGTH_LONG).show();
                        else ScameraApp.showToast(result);
                    }
            );
            
            curveImportLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument() {
                        @NonNull @Override
                        public Intent createIntent(@NonNull android.content.Context context, @NonNull String[] input) {
                            Intent intent = super.createIntent(context, input);
                            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                    com.scamera.app.util.ScameraFolder.CURVES.initialPickerUri());
                            return intent;
                        }
                    },
                    uri -> {
                        if (uri != null) importToneCurve(uri);
                    }
            );
            cubeLutImportLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument() {
                        @NonNull @Override
                        public Intent createIntent(@NonNull android.content.Context context, @NonNull String[] input) {
                            Intent intent = super.createIntent(context, input);
                            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                    com.scamera.app.util.ScameraFolder.LUTS.initialPickerUri());
                            return intent;
                        }
                    },
                    uri -> {
                        if (uri != null) importFilmLut(uri);
                    }
            );
            folderGrantLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) grantFolder(uri);
                if (importFromCamera && getActivity() != null) getActivity().finish();
            });
            com.scamera.app.ui.settings.custompreferences.ToneCurvePreference curve =
                    findPreference(com.scamera.app.ui.settings.custompreferences.ToneCurvePreference.KEY);
            if (curve != null) {
                curve.setImporter(() -> curveImportLauncher.launch(new String[]{"*/*"}));
                curve.setFolderOpener(() -> folderGrantLauncher.launch(com.scamera.app.util.ScameraFolder.grantHint()));
            }
            com.scamera.app.ui.settings.custompreferences.ProfilesPreference profiles = findPreference("profiles_page");
            if (profiles != null)
                profiles.setFolderOpener(() -> folderGrantLauncher.launch(com.scamera.app.util.ScameraFolder.grantHint()));
            com.scamera.app.ui.settings.custompreferences.FilmLutPreference filmLut =
                    findPreference(com.scamera.app.processing.color.FilmLut.KEY);
            if (filmLut != null) {
                filmLut.setImporter(() -> cubeLutImportLauncher.launch(new String[]{"*/*"}));
                filmLut.setFolderOpener(() -> folderGrantLauncher.launch(com.scamera.app.util.ScameraFolder.grantHint()));
            }

            // Check if we're opening the tunable submenu specifically
            String rootKey = getArguments() != null ? getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) : null;
            // P143: the camera's config menu asked for a file: the picker opens over the settings' root once
            if (rootKey == null && savedInstanceState == null && getActivity() != null
                    && ACTION_IMPORT_CONFIG.equals(getActivity().getIntent().getAction())) {
                getActivity().getIntent().setAction(null);
                importFromCamera = true;
                configImportLauncher.launch(new String[]{"*/*"});
            }
            if (rootKey == null && savedInstanceState == null && getActivity() != null
                    && ACTION_OPEN_FOLDER.equals(getActivity().getIntent().getAction())) {
                getActivity().getIntent().setAction(null);
                importFromCamera = true;
                folderGrantLauncher.launch(com.scamera.app.util.ScameraFolder.grantHint());
            }
            Log.d("SettingsFragment", "onCreate with rootKey: " + rootKey);
            
// Generators add ListPreferences after onCreatePreferences() ran, so re-run the
            // guard over whatever the tree looks like now.
            seedMissingListValues(getPreferenceScreen());

            // Keep every category reachable regardless of the last camera mode.
            setVersionDetails();
            setBackupPref();
            setRestorePref();
            updateSettingsAvailability();
        }
        
        /** The {@code @Tunable} rows (TunablePreferenceGenerator), once per page. */
        private void generateTunablePreferences() {
            if (tunablesBuilt) return;
            tunablesBuilt = true;
            try {
                com.scamera.app.settings.TunableSettingsManager.ensureTunableClassesRegistered();
                for (Class<?> owner : com.scamera.app.settings.TunableRegistry.TUNABLE_CLASSES)
                    TunablePreferenceGenerator.registerTunableClass(owner);
                TunablePreferenceGenerator.generatePreferences(ctx, getPreferenceScreen());
            } catch (RuntimeException e) {
                Log.e("SettingsActivity", "tunable rows failed", e);
            }
        }

        /** The per-module sensor rows (SensorConfigPreferenceGenerator), once per page. */
        private void generateSensorConfigPreferences() {
            if (sensorsBuilt) return;
            sensorsBuilt = true;
            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) return;
            try {
                // P384: the module chosen before (the chips, a recreate) or asked for (sensorPage), else a module page's own, else none
                String preferred = getArguments() == null ? null : getArguments().getString(ARG_SENSOR_SLOT);
                if ((preferred == null || preferred.isEmpty()) && moduleProfile() != null)
                    preferred = com.scamera.app.settings.FullResProfiles.slotOf(moduleProfile());
                if (preferred != null && preferred.isEmpty()) preferred = null;
                sensorSelection = com.scamera.app.settings.SensorConfigPreferenceGenerator.generatePreferences(ctx, screen,
                        preferred, () -> { if (moduleRows != null) moduleRows.sync(); }, this::askSensorReset);
            } catch (RuntimeException e) {
                Log.e("SettingsActivity", "sensor rows failed", e);
            }
        }

        /**
         * P384 «Сбросить сенсор «X»» (one row per module now, was the sensor page's one row): asked first, then the module's sensor
         * settings reset; the settings are built again (the open card and the chosen module stay, in the page's arguments).
         */
        void askSensorReset(String slot) {
            if (ctx == null) return;
            DialogStyle.builder(ctx)
                .setTitle(Lang.t(getContext(),"Сбросить настройки модуля?","Reset the module settings?"))
                .setMessage(com.scamera.app.settings.ModuleRegistry.label(slot)+" · ID "+com.scamera.app.settings.ModuleRegistry.camera(slot))
                .setNegativeButton(Lang.t(getContext(),"Отмена","Cancel"),null)
                .setPositiveButton(Lang.t(getContext(),"Сбросить","Reset"),(d,w)->{
                    com.scamera.app.settings.ModuleSensorSettings.reset(slot);
                    if(getActivity()!=null)getActivity().recreate();
                }).show();
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            pageView = view;
            setupToolbar();
            setDivider(null);
            // P384: a module profile's root page: back gives the main settings the profile they held before it opened (the main page
            // behind it shows that one's values; was ModuleProfilesFragment's), or the camera's
            if (moduleProfile() != null && (getArguments() == null || getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) == null))
                requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        String ret = getArguments() == null ? null : getArguments().getString(ARG_RETURN_PROFILE);
                        if (ret != null && PreferenceKeys.isPerLensSettingsOn()) PreferenceKeys.profiles().activate(ret);
                        else com.scamera.app.settings.ModuleRegistry.syncProfile();
                        setEnabled(false);
                        requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    }
                });
            String target = getArguments() == null ? null : getArguments().getString("search_target");
            if (target != null && findPreference(target) != null) {
                Preference found = findPreference(target);
                // P384: a row of a folded module card, or of a module not chosen in «Сенсор по модулям», is shown first - once:
                // after a recreate the page's arguments hold what the user opened or chose since
                if (moduleRows != null && savedInstanceState == null) moduleRows.reveal(found);
                android.text.SpannableString highlighted = new android.text.SpannableString(found.getTitle());
                highlighted.setSpan(new android.text.style.ForegroundColorSpan(SettingsStyle.accent(requireContext())), 0,
                        highlighted.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                found.setTitle(highlighted);
                // P574: the header collapsed first, so the row is not left under the expanded large title
                if (headerBar != null) headerBar.setExpanded(false, false);
                scrollToPreference(target);
            }
        }

        /** The activity toolbar stays hidden: every page has the shared header (SettingsStyle.header). */
        private void setupToolbar() {
            if (activity != null) {
                View toolbar = activity.findViewById(R.id.settings_toolbar);
                if (toolbar != null) toolbar.setVisibility(View.GONE);
            }
            updateHeader();
        }

        @Override
        public void onResume() {
            super.onResume();
            // P189: back from the camera (it took its module's profile): the module page takes its own again
            if (takeProfile(moduleProfile())) { requireActivity().recreate(); return; }
            // Update toolbar title when fragment resumes (e.g., after navigating back)
            setupToolbar();
            if (moduleRows != null) moduleRows.sync();   // P384: a module changed on another page or in the camera
            // P385: back from «Расположение» (or an order imported meanwhile): the page takes the order now stored
            if (layout != null) {
                String order = SettingsLayoutTree.stored(settingsStore.getDefaultPreferences());
                if (!order.equals(layout.applied)) SettingsLayoutTree.apply(layout, order);
            }
            updateSettingsAvailability();
            showFileNamePreview();
            refreshProfiles();   // P246: a config put into the folder meanwhile shows up
            settingsStore.getDefaultPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        /** «Название фото»: the row shows the name a photo taken now gets (P119). */
        private void showFileNamePreview() {
            Preference row = findPreference(com.scamera.app.processing.FileNamePattern.KEY);
            if (row == null || !isAdded()) return;
            row.setSummary(getString(R.string.prefs_file_name_summary) + "\n" + com.scamera.app.processing.FileNamePattern.format(
                    PreferenceKeys.getFileNamePattern(), System.currentTimeMillis()) + ".jpg");
            // P592: the name a photo gets now (the summary's last line) is the row's state; set before the row binds again
            final String summary = String.valueOf(row.getSummary());
            SettingHelp.state(row, summary.substring(summary.lastIndexOf('\n') + 1));
        }

        @Override public void onPause() {
            settingsStore.getDefaultPreferences().unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }

        /** P574: a confirmation or pull-down menu still open goes with the page (no leaked window on a rotation). */
        @Override public void onDestroyView() {
            DialogStyle.dismissShown();
            headerBar = null;
            super.onDestroyView();
        }

        @Override public void onDestroy() {
            if (settingsStore != null) settingsStore.getDefaultPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
            super.onDestroy();
        }

        private final java.util.Map<String, Preference.SummaryProvider> originalProviders = new java.util.HashMap<>();
        private final java.util.Map<String, CharSequence> originalSummaries = new java.util.HashMap<>();
        /**
         * P143: keeps the folder the user opened (read and write, across restarts) and says what it reaches: Download/SCAMERA
         * (or Download) covers the configs and the curves; another folder covers neither.
         */
        /**
         * P475: a refused scam_dev.txt write: «Доступ ко всем файлам» (Android 11+, AllFilesAccess), else the folder picker at
         * Download/SCAMERA (the grant writes it too).
         */
        void askScameraFolder() {
            if (!com.scamera.app.ui.camera.AllFilesAccess.granted()
                    && com.scamera.app.ui.camera.AllFilesAccess.openSettings(ctx)) return;
            if (folderGrantLauncher != null) folderGrantLauncher.launch(com.scamera.app.util.ScameraFolder.grantHint());
        }

        void grantFolder(android.net.Uri tree) {
            try {
                ctx.getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (RuntimeException e) {
                Log.w("SettingsFragment", "folder grant not kept: " + e);
            }
            boolean configs = com.scamera.app.util.ScameraFolder.CONFIGS.grantedTree(ctx) != null;
            String text = configs
                    ? com.scamera.app.util.Lang.t(ctx, "Папка открыта: все файлы из " + com.scamera.app.util.ScameraFolder.CONFIGS.relative
                            + " теперь в списке конфигов", "Folder open: every file in " + com.scamera.app.util.ScameraFolder.CONFIGS.relative
                            + " is in the config list now")
                    : com.scamera.app.util.Lang.t(ctx, "Эта папка не Download/SCAMERA: конфиги из неё в список не попадут",
                            "This folder is not Download/SCAMERA: its configs are not listed");
            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show();
            refreshProfiles();
        }

        private void refreshProfiles() {
            com.scamera.app.ui.settings.custompreferences.ProfilesPreference profiles = findPreference("profiles_page");
            if (profiles != null) profiles.refresh();
        }

        /**
         * P134: a picked .cube: checked (FilmLut.parse), copied into Download/SCAMERA/LUT under its own name and chosen; a file
         * that is no 3D LUT is refused with a message.
         */
        void importFilmLut(android.net.Uri uri) {
            byte[] data = null;
            String name = "LUT.cube";
            try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
            } catch (RuntimeException ignored) {
            }
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".cube")) name = name.replaceAll("\\.[^.]*$", "") + ".cube";
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > com.scamera.app.processing.color.FilmLut.MAX_BYTES) { out = null; break; }
                    }
                    if (out != null) data = out.toByteArray();
                }
            } catch (java.io.IOException | RuntimeException e) {
                Log.w("SettingsFragment", "LUT import failed: " + e);
            }
            com.scamera.app.processing.color.FilmLut lut = null;
            if (data != null) try {
                lut = com.scamera.app.processing.color.FilmLut.parse(new java.io.ByteArrayInputStream(data));
            } catch (java.io.IOException ignored) {
            }
            if (lut == null) {
                android.widget.Toast.makeText(ctx, com.scamera.app.util.Lang.t(ctx,
                        "Это не 3D LUT .cube (нужны LUT_3D_SIZE от 2 до 65 и все строки r g b)",
                        "Not a 3D .cube LUT (it needs LUT_3D_SIZE 2 to 65 and every r g b row)"), android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            try {
                String saved = com.scamera.app.util.ScameraFolder.LUTS.save(ctx, name, data);
                String kept = saved.substring(saved.lastIndexOf('/') + 1);
                com.scamera.app.ui.settings.custompreferences.FilmLutPreference pref =
                        findPreference(com.scamera.app.processing.color.FilmLut.KEY);
                com.scamera.app.ui.settings.custompreferences.FilmLutPreference.forgetFile(kept);
                if (pref != null) pref.choose(kept);
                else settingsStore.getDefaultPreferences().edit().putString(com.scamera.app.processing.color.FilmLut.KEY, kept).apply();
                com.scamera.app.processing.color.FilmLut.forget();
                android.widget.Toast.makeText(ctx, com.scamera.app.util.Lang.t(ctx,
                        "LUT загружен: " + kept + " (" + lut.size + "³)", "LUT loaded: " + kept + " (" + lut.size + "³)"),
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException | RuntimeException e) {
                Log.w("SettingsFragment", "LUT save failed: " + e);
                android.widget.Toast.makeText(ctx, com.scamera.app.util.Lang.t(ctx,
                        "Не удалось сохранить LUT в Download/SCAMERA/LUT", "Could not save the LUT to Download/SCAMERA/LUT"),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }

        /** P144: a picked file's "x:y" points onto the own curve's graph (and into the store). */
        void importToneCurve(android.net.Uri uri) {
            // P262: whatever fails here says so; it never takes the app down (the owner saw it restart, the curve lost)
            try {
                importToneCurveChecked(uri);
            } catch (RuntimeException e) {
                Log.e("SettingsFragment", "tone curve import crashed: " + Log.getStackTraceString(e));
                if (ctx != null) android.widget.Toast.makeText(ctx, com.scamera.app.util.Lang.t(ctx,
                        "Кривую не удалось загрузить: " + e, "The curve could not be loaded: " + e), android.widget.Toast.LENGTH_LONG).show();
            }
        }

        private void importToneCurveChecked(android.net.Uri uri) {
            com.scamera.app.processing.color.ToneCurve curve = null;
            String text = null;
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in != null) text = com.scamera.app.ui.settings.custompreferences.ToneCurvePreference.readText(in);
                if (text != null) curve = com.scamera.app.processing.color.ToneCurve.fromFileText(text);
            } catch (java.io.IOException | RuntimeException e) {
                Log.w("SettingsFragment", "tone curve import failed: " + e);
            }
            if (curve == null) {
                android.widget.Toast.makeText(ctx, com.scamera.app.util.Lang.t(ctx,
                        "В файле нет кривой: нужны точки «x:y» от 0 до 1 или файл кривой .json",
                        "No curve in the file: it needs «x:y» points from 0 to 1 or a .json curve file"),
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            // P231: an WhyCam curve file keeps its name - the curve goes into Download/SCAMERA/ToneCurves under it (our .txt)
            String name = com.scamera.app.processing.color.ToneCurve.jsonName(text);
            if (name != null) {
                try {
                    String file = com.scamera.app.util.ScameraFolder.CURVES.baseName(name);
                    if (!file.isEmpty()) com.scamera.app.util.ScameraFolder.CURVES.save(ctx, file + ".txt",
                            curve.toFileText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException | RuntimeException e) {
                    Log.w("SettingsFragment", "tone curve copy failed: " + e);
                }
            }
            com.scamera.app.ui.settings.custompreferences.ToneCurvePreference pref =
                    findPreference(com.scamera.app.ui.settings.custompreferences.ToneCurvePreference.KEY);
            if (pref != null) pref.load(curve);
            else settingsStore.getDefaultPreferences().edit()
                    .putString(com.scamera.app.ui.settings.custompreferences.ToneCurvePreference.KEY, curve.serialize()).apply();
            android.widget.Toast.makeText(ctx, com.scamera.app.util.Lang.t(ctx,
                    "Кривая загружена: " + curve.size() + " точек", "Curve loaded: " + curve.size() + " points"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }

        private void updateSettingsAvailability() {
            if (!isAdded() || settingsStore == null) return;
            com.scamera.app.settings.SettingsAvailability state =
                    com.scamera.app.settings.DeviceAvailability.of(settingsStore.getDefaultPreferences().getAll());
            applyAvailability(getPreferenceScreen(), state);
            if (layout != null) SettingsLayoutTree.hideEmpty(layout);   // P385: a group that gave its rows away
        }

        /** P385: «Расположение» of this page. */
        private void openLayout() {
            if (layout == null || !isAdded()) return;
            PreferenceScreen screen = getPreferenceScreen();
            slide(getParentFragmentManager().beginTransaction()).replace(R.id.settings_container,
                    SettingsLayoutFragment.of(layout, screen == null ? null : screen.getTitle())).addToBackStack("settings_layout").commit();
        }
        private void applyAvailability(PreferenceGroup group, com.scamera.app.settings.SettingsAvailability state) {
            applyAvailability(group, state, null);
        }

        /**
         * {@code groupReason}: why the group holding these rows is inactive as a whole (P614: a group of the other «Why тон»
         * version), null when it is not - its rows go grey with it, the note shown once under the group's title.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void applyAvailability(PreferenceGroup group, com.scamera.app.settings.SettingsAvailability state,
                                       @Nullable String groupReason) {
            if (group == null) return;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference p = group.getPreference(i);
                if (p instanceof PreferenceGroup) {
                    if (p instanceof PreferenceScreen) p.setEnabled(true);
                    if (!(p instanceof PreferenceScreen)) {
                        // P614: a category of one tone version: greyed as a group, its note under its title
                        final String off = state.groupReason(p.getKey());
                        if (SettingsStyle.groupOff(p, off)) p.setEnabled(off == null);   // its rows follow (parent dependency)
                        applyAvailability((PreferenceGroup) p, state, off);
                        continue;
                    }
                    applyAvailability((PreferenceGroup) p, state, null);
                }
                String key = p.getKey();
                if (key == null) continue;
                // Rows of a photo format that is not chosen are hidden, not explained (SettingsAvailability.hidden).
                // P189: shared rows stay off module pages; 2026-10-27: a row the page's code hid stays hidden
                boolean hidden = state.hidden(key) || moduleHidden.contains(key) || codeHidden.contains(key)
                        || (moduleRows != null && moduleRows.hides(key))
                        || SettingsStyle.emptyNote(p);   // P592: a note that was only a description
                if (p.isVisible() == hidden) p.setVisible(!hidden);
                String reason = state.reason(key);
                // P614: the group's own note is not repeated on each of its rows
                String note = reason != null && reason.equals(groupReason) ? null : reason;
                if (p.getClass() != Preference.class) p.setEnabled(reason == null);
                if (note != null) {
                    if (!originalSummaries.containsKey(key)) {
                        originalSummaries.put(key, p.getSummary());
                        originalProviders.put(key, p.getSummaryProvider());
                    }
                    p.setEnabled(false);
                    Preference.SummaryProvider provider = originalProviders.get(key);
                    SettingHelp.state(p, note);   // P592: why the row is off stays under its title (its description does not)
                    p.setSummaryProvider(pref -> {
                        CharSequence base = provider == null ? originalSummaries.get(key) : provider.provideSummary(pref);
                        return base == null || base.length() == 0 ? note : base + "\n" + note;
                    });
                } else if (originalSummaries.containsKey(key)) {
                    p.setEnabled(reason == null);
                    SettingHelp.state(p, null);
                    p.setSummaryProvider(originalProviders.remove(key));
                    CharSequence summary = originalSummaries.remove(key);
                    if (p.getSummaryProvider() == null) p.setSummary(summary);
                } else if (reason != null) {
                    p.setEnabled(false);
                }
            }
        }

        /** «Загрузить настройки»: a listed config, or the system picker for one elsewhere; the result as a snackbar. */
        private void setRestorePref() {
            activity.runOnUiThread(() -> {
                Preference load = findPreference("pref_restore_preferences_key");
                if (load == null) return;
                load.setOnPreferenceChangeListener((row, chosen) -> {
                    String name = chosen.toString();
                    if (com.scamera.app.ui.settings.custompreferences.RestorePreference.PICK.equals(name)) {
                        if (configImportLauncher != null) configImportLauncher.launch(new String[]{"*/*"});
                        return false;
                    }
                    Snackbar.make(pageView, BackupRestoreUtil.restorePreferences(ctx, name), Snackbar.LENGTH_LONG).show();
                    return true;
                });
            });
        }

        /** «Сохранить настройки»: the config under the typed name; the result as a snackbar. */
        private void setBackupPref() {
            activity.runOnUiThread(() -> {
                Preference save = findPreference("pref_backup_preferences_key");
                if (save == null) return;
                save.setOnPreferenceChangeListener((row, name) -> {
                    Snackbar.make(pageView, BackupRestoreUtil.backupSettings(ctx, name.toString()), Snackbar.LENGTH_LONG).show();
                    return true;
                });
            });
        }

        /** The phone as brand:device («vivo:pd2454»), on the version row. */
        static String thisDevice() {
            return android.os.Build.BRAND.toLowerCase(java.util.Locale.ROOT) + ":" + android.os.Build.DEVICE.toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key == null || !isResumed()) return; // a restore clears the store (null key); a page in the back stack waits
            // P144: one tone operator at a time - ACES, Uchimura or the own curve switched on turns the other two off
            if (TONE_OPERATORS.contains(key) && sharedPreferences.getBoolean(key, false)) {
                for (String other : TONE_OPERATORS) {
                    if (other.equals(key)) continue;
                    // the store first (a switch row may not know a value written elsewhere), then the row on screen
                    if (sharedPreferences.getBoolean(other, false)) sharedPreferences.edit().putBoolean(other, false).apply();
                    androidx.preference.TwoStatePreference p = findPreference(other);
                    if (p != null) p.setChecked(false);
                }
            }
            // P638: «Спорт» switched on here while «Oldstyle» is the camera's mode: the mode is «Спорт» (one mode at a time)
            if (key.equals(com.scamera.app.settings.MotionMode.PREF) && sharedPreferences.getBoolean(key, false))
                com.scamera.app.settings.MotionMode.settleWithOldstyle(
                        android.preference.PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext()));
            if (key.equals(com.scamera.app.util.ScameraDebugLog.PREF_KEY)) {
                boolean on = sharedPreferences.getBoolean(key, false);
                // Only bind the context here. Calling init() would itself call
                // setEnabled() with the freshly stored value, and the setEnabled()
                // below would then hit its own "value == enabled" early return -
                // so the writer was never opened and no file appeared.
                com.scamera.app.util.ScameraDebugLog.attach(ctx);
                com.scamera.app.util.ScameraDebugLog.setEnabled(on);
                if (on) {
                    // P214b: after the session header, so the toast names the file really written
                    com.scamera.app.util.ScameraDebugLog.whenOpen(ScameraApp::showToast);
                }
            }
            
            // P384: the main page's switch and its question rebuild the page themselves (ModuleActions.perLensSwitch)
            boolean perLensOn = key.equals(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue) && !ModuleActions.writingPerLens
                    && PreferenceKeys.isPerLensSettingsOn();
            boolean look = key.equalsIgnoreCase(PreferenceKeys.Key.KEY_THEME.mValue)
                    || key.equalsIgnoreCase(PreferenceKeys.Key.KEY_THEME_ACCENT.mValue);
            if (perLensOn) restartActivity();
            if (look) restartActivity();
            if (key.equalsIgnoreCase(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue)) {
                try {
                    toggleGalleryIconVisibility(settingsStore.getBoolean(SettingsManager.SCOPE_GLOBAL, PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON));
                } catch (RuntimeException e) {
                    Log.e("SettingsFragment", "gallery icon not changed: " + e);
                }
            }
            updateSettingsAvailability();
        }

        /** The gallery's own launcher icon off or on, said in a snackbar (or why it could not be changed). */
        private void toggleGalleryIconVisibility(boolean hidden) {
            String note;
            try {
                if (!com.scamera.app.gallery.GalleryLauncherIcon.apply(ctx, hidden)) return;
                note = hidden
                        ? com.scamera.app.util.Lang.t("Значок галереи убран с рабочего стола", "The gallery icon is removed from the launcher")
                        : com.scamera.app.util.Lang.t("Галерея появится на рабочем столе отдельным значком", "The gallery appears in the launcher as its own icon");
            } catch (RuntimeException e) {
                note = com.scamera.app.util.Lang.t("Не удалось изменить значок галереи: ", "Could not change the gallery icon: ") + e.getMessage();
            }
            if (activity == null) return;
            final String text = note;
            activity.runOnUiThread(() -> Snackbar.make(activity.findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).show());
        }

        /** A theme or scope change: the activity again (FragmentManager restores the open page and its back stack). */
        private void restartActivity() {
            if (getActivity() != null) getActivity().recreate();
        }

        /** SIMPLE_UI §4 «О приложении»: version, install time (UTC) and the phone on one row. */
        private void setVersionDetails() {
            activity.runOnUiThread(() -> {
                Preference about = findPreference("pref_version_key");
                if (about == null) return;
                PackageInfo app;
                try {
                    app = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                } catch (PackageManager.NameNotFoundException e) {
                    return;
                }
                SimpleDateFormat when = new SimpleDateFormat("dd MMM yyyy HH:mm:ss z", Locale.US);
                when.setTimeZone(TimeZone.getTimeZone("UTC"));
                about.setSummary(ctx.getString(R.string.version_summary, app.versionName + "." + app.versionCode,
                        when.format(new Date(app.lastUpdateTime)), thisDevice()));
            });
        }

        /**
         * Read a calibration file and store it as the active profile. Anything that does not
         * contain all four coefficient arrays is rejected rather than half-applied.
         */
        private String importNoiseModel(android.net.Uri uri) {
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return Lang.t(getContext(),"Не удалось открыть файл","Couldn't open the file");
                }
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                String source = buffer.toString("UTF-8");
                NoiseModelProfile profile = NoiseModelProfile.parse(source, "imported", Lang.t(getContext(),"Импортированный","Imported"));
                if (profile == null) {
                    return Lang.t(getContext(),"Файл не содержит noise_model_A/B/C/D","The file has no noise_model_A/B/C/D");
                }
                NoiseModelProfile.setImported(profile);
                settingsStore.set(PreferenceKeys.SCOPE_GLOBAL,
                        "pref_noise_model_profile_key", NoiseModelProfile.IMPORTED_ID);
                return Lang.t(getContext(),"Модель шума импортирована","Noise model imported");
            } catch (Exception e) {
                Log.e("SettingsFragment", "Noise model import failed", e);
                return Lang.t(getContext(),"Ошибка импорта: ","Import error: ") + e;
            }
        }

        /** Write the active profile back out in the calibration-file format. */
        private String exportNoiseModel() {
            NoiseModelProfile profile =
                    NoiseModelProfile.byId(PreferenceKeys.getNoiseModelProfileId());
            if (profile == null) {
                return Lang.t(getContext(),"Активен автоматический профиль, экспортировать нечего","The automatic profile is active, nothing to export");
            }
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "SCAMERA");
            java.io.File written = profile.exportTo(dir);
            return written != null
                    ? Lang.t(getContext(),"Сохранено: ","Saved: ") + written.getName()
                    : Lang.t(getContext(),"Не удалось записать файл","Couldn't write the file");
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            // Log which preference was clicked
            Log.d("SettingsFragment", "onPreferenceTreeClick: " + preference.getKey());
            if (PreferenceKeys.Key.KEY_THEME_ACCENT.mValue.equals(preference.getKey())) {
                getParentFragmentManager().beginTransaction().replace(R.id.settings_container,new AccentSettingsFragment()).addToBackStack("accent").commit();return true;
            }
            // P630h «Сбросить Oldstyle»: the rows of the group's categories back to their defaults (the version stays)
            if (com.scamera.app.settings.OldstyleMode.RESET.equals(preference.getKey())) {
                resetOldstyle();
                return true;
            }
            if ("pref_dcp_profile_key".equals(preference.getKey())) {
                getParentFragmentManager().beginTransaction().replace(R.id.settings_container,new DcpSettingsFragment()).addToBackStack("dcp").commit();return true;
            }
            // P189: an empty PreferenceScreen does not navigate by itself (androidx returns at once without children)
            if (ScamLabFragment.SCREEN.equals(preference.getKey()) && activity instanceof SettingsActivity) {
                ((SettingsActivity) activity).openLab();
                return true;
            }
            if ("settings_layout_editor".equals(preference.getKey())) {
                openLayout();
                return true;
            }
            if ("module_copy_settings".equals(preference.getKey())) {
                slide(getParentFragmentManager().beginTransaction()).replace(R.id.settings_container, new ModuleCopyFragment()).addToBackStack("module_copy").commit();
                return true;
            }
            if ("pref_noise_model_import_key".equals(preference.getKey())) {
                if (noiseModelImportLauncher != null) {
                    noiseModelImportLauncher.launch(new String[]{"*/*"});
                }
                return true;
            }
            if (com.scamera.app.processing.FileNamePattern.KEY.equals(preference.getKey())) {
                FileNameDialog.show(requireContext(), this::showFileNamePreview);
                return true;
            }
            if ("pref_noise_model_export_key".equals(preference.getKey())) {
                ScameraApp.showToast(exportNoiseModel());
                return true;
            }
            
            // Static pages such as ACES fall through to PreferenceFragmentCompat (SCAMERA-26788: handled here, some AndroidX / vendor
            // combinations dispatched them twice)
            // Return false to allow default handling (like opening other subscreens)
            return super.onPreferenceTreeClick(preference);
        }

        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            // P574: a list opens its menu at the row that was tapped
            if (SettingsStyle.showDialog(requireContext(), preference, SettingsStyle.rowOf(getListView(), preference))) return;
            if (preference instanceof ResetPreferences) {
                ResetPreferences.confirm(requireContext(), preference);   // P574: the centred confirmation
            } else {
                super.onDisplayPreferenceDialog(preference);
            }
        }

    }
}
