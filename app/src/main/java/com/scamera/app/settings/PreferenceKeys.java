package com.scamera.app.settings;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import com.scamera.app.R;
import com.scamera.app.api.CameraMode;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.util.Log;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PreferenceKeys {
    /** The scope of the settings every module shares. */
    public static final String SCOPE_GLOBAL = SettingsManager.SCOPE_GLOBAL;
    /** The process's settings store, set by {@link #initialise}. */
    private static SettingsManager settings;
    private static final String TAG = "PreferenceKeys";

    private PreferenceKeys() {}

    private static int num(Key key) { return settings.getInteger(SCOPE_GLOBAL, key); }

    private static boolean flag(Key key) { return settings.getBoolean(SCOPE_GLOBAL, key); }

    private static void store(Key key, int value) { settings.set(SCOPE_GLOBAL, key, value); }

    private static void store(Key key, boolean value) { settings.set(SCOPE_GLOBAL, key, value); }

    /**
     * Id of the selected noise-model profile, or {@code "auto"} to keep the
     * SENSOR_NOISE_PROFILE reported by Camera2.
     */
    public static String getNoiseModelProfileId() { return text("pref_noise_model_profile_key", "auto"); }

    /**
     * Derive the burst's shutter from the TET waypoint curve (see {@code TetModel})
     * instead of the per-frame heuristics, and hold that shutter constant across the
     * bracket so the ends differ by gain alone. This is what a reference shot dump shows:
     * one shutter for every frame in the burst, "Desired exposure time factor:
     * 1.000000", with the whole bracket spread carried by "Desired TET factor".
     * Off by default so the two behaviours can be compared on one build.
     */
    /**
     * «Частота мерцания»: the lamp flicker (Hz) the long Shasta frames of the hybrid snap to (HybridPlan.snapAntibanding, the reference
     * apply_antibanding). -1 «Авто» (default, P118): what the camera reports or its AE shows, else the region's mains
     * (MainsFlicker); 100 = 50 Hz mains, 120 = 60 Hz mains; 0 = no snap.
     */
    public static int getAntibandingHz() {
        final int hz = Integer.parseInt(text("pref_antibanding_hz_key", "-1"));
        return hz < 0 ? com.scamera.app.capture.MainsFlicker.hz() : hz;
    }

    /** P411: «Мерцание ламп» as set (-1 «Авто», 100 = 50 Hz, 120 = 60 Hz, 0 «Выкл.»): the camera's AE antibanding mode (Antibanding). */
    public static int antibandingSetting() {
        try {
            return Integer.parseInt(text("pref_antibanding_hz_key", "-1"));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * P212d: true when {@link #getAntibandingHz()} is known - set by hand (the override: 100, 120 or 0), or «Авто» latched from
     * evidence (MainsFlicker.known: agreeing reports, AE shutters, the merge's row profile); false while «Авто» guesses the region.
     */
    public static boolean antibandingKnown() {
        final int hz = Integer.parseInt(text("pref_antibanding_hz_key", "-1"));
        return hz >= 0 || com.scamera.app.capture.MainsFlicker.known();
    }

    /** A stored text setting, normalised by its numeric rule (a value out of range reads as the fallback). */
    private static String text(String key, String fallback) {
        return SettingsNumericRules.normalized(key, settings.getString(SCOPE_GLOBAL, key, fallback), fallback);
    }

    /** P241: the settings store (null before {@link #initialise}), for helpers of this package. */
    static SettingsManager settingsStore() { return settings; }

    public static void initialise(SettingsManager manager) {
        settings = manager;
        moduleProfiles = null;
    }

    private static ModuleProfiles moduleProfiles;
    public static ModuleProfiles profiles() {
        if (moduleProfiles == null) moduleProfiles = new ModuleProfiles(settings);
        return moduleProfiles;
    }

    /**
     * The first-run values of the camera's own settings (written once, the user's choice kept) and the code defaults of the
     * keys without an XML row; then the module-profile listener (once per process, the manager ignores a second add).
     */
    public static void setDefaults(Context context) {
        sDefaultsSet = true;
        Resources res = context.getResources();
        SettingsManager s = settings;
        s.setInitial(SCOPE_GLOBAL, Key.KEY_EIS_PHOTO, res.getBoolean(R.bool.pref_eis_photo_default));
        s.setInitial(SCOPE_GLOBAL, Key.KEY_REMOSAIC, res.getBoolean(R.bool.pref_remosaic_default));
        s.setInitial(SCOPE_GLOBAL, Key.KEY_ULTRAHDR, res.getBoolean(R.bool.pref_ultrahdr_default));
        s.setInitial(SCOPE_GLOBAL, Key.KEY_AE_MODE, res.getString(R.string.pref_ae_mode_default));
        s.setInitial(SCOPE_GLOBAL, Key.CAMERA_MODE, res.getString(R.string.pref_camera_mode_default));
        s.setInitial(SCOPE_GLOBAL, Key.KEY_VIDEO_RESOLUTION, res.getString(R.string.pref_video_resolution_default));
        for (Key zero : new Key[]{Key.KEY_FPS_PREVIEW, Key.KEY_COUNTDOWN_TIMER, Key.KEY_BRACKETING_MODE})
            s.setInitial(SCOPE_GLOBAL, zero, 0);
        s.setInitial(SCOPE_GLOBAL, Key.KEY_AE_METERING_STD, -1);
        s.setDefault(Key.CAMERA_ID, res.getString(R.string.camera_id_default));
        s.setDefault(Key.KEY_SHOW_AF_DATA, "0");
        s.addListener(PROFILE_LISTENER);
    }

    private static volatile boolean sDefaultsSet;

    /** {@link #setDefaults} ran in this process (CameraActivity, the settings' shortcut or their recreation). */
    public static boolean defaultsSet() { return sDefaultsSet; }

    /** One listener per process: every CameraActivity start and the settings shortcut (P140) call setDefaults. */
    static final SettingsManager.OnSettingChangedListener PROFILE_LISTENER = PreferenceKeys::onSettingChanged;

    /** A changed setting goes into the active module's profile (not while a profile is being applied) and the settings cache. */
    private static void onSettingChanged(SettingsManager source, String key) {
        if (key == null || profiles().isApplying()) return;
        profiles().changed(key);
        if (ScameraApp.getSettings() != null) ScameraApp.getSettings().loadCache();
    }

    /**
     * The scanned Camera IDs: the first is the default camera. P193: no per-camera JSON of the settings any more
     * (settings_for_camera_<id>); module profiles are ModuleProfiles' own files.
     */
    public static void addIds(String[] ids) {
        if (ids == null) return;
        Log.d(TAG, "camera ids " + Arrays.toString(ids));
        settings.setDefault(Key.CAMERA_ID, ids[0]);
    }

    public static void loadSettingsForCamera(String cameraID) { profiles().activate(cameraID); }

    public static void setActivityTheme(Activity activity) {
        String accent = settings.getString(SCOPE_GLOBAL, Key.KEY_THEME_ACCENT,
                activity.getResources().getString(R.string.pref_theme_accent_default_value));
        if (accent != null) activity.getTheme().applyStyle(accentStyle(accent), true);
    }

    /** The theme overlay of an accent value of «Акцент» (setActivityTheme; DialogStyle for its dialogs), 0 when unknown. */
    public static int accentStyle(String accent) {
        if (accent == null) return 0;
        switch (accent.toLowerCase(java.util.Locale.ROOT)) {
            // P130: «Из системы» (and the default) - the phone's accent on Android 12+ (values-v31), lavender before
            case "default":
            case "system": return R.style.SystemAccentTheme;
            case "lavender": return R.style.LavenderAccentTheme;
            case "amber": return R.style.AmberAccentTheme;
            case "red": return R.style.RedTheme;
            case "blue": return R.style.BlueTheme;
            case "orange": return R.style.OrangeTheme;
            case "green": return R.style.GreenTheme;
            case "pink": return R.style.PinkTheme;
            case "cyan": return R.style.CyanTheme;
            case "teal": return R.style.TealTheme;
            case "white": return R.style.WhiteTheme;
            default: return 0;
        }
    }

    /** «Название фото» (P119): the file name template of new photos (processing.FileNamePattern). */
    public static String getFileNamePattern() {
        return settings.getString(SCOPE_GLOBAL, com.scamera.app.processing.FileNamePattern.KEY,
                com.scamera.app.processing.FileNamePattern.DEFAULT);
    }

    public static void setFileNamePattern(String pattern) { settings.set(SCOPE_GLOBAL, com.scamera.app.processing.FileNamePattern.KEY, pattern); }

    public static int getAfDataValue() { return num(Key.KEY_SHOW_AF_DATA); }

    public static boolean isAfDataOn() { return getAfDataValue() > 0; }

    public static boolean isFullDebugOn() { return getAfDataValue() == 3; }

    public static boolean isHorizonOn() { return flag(Key.KEY_SHOW_HORIZON); }

    public static boolean isRemosaicOn() { return flag(Key.KEY_REMOSAIC); }

    public static boolean isShowWatermarkOn() { return flag(Key.KEY_SHOW_WATERMARK); }

    /**
     * P80 «Быстрый JPEG»: Android's encoder (libjpeg-turbo, 4:2:0) instead of jpegli (4:4:4) for the saved JPEGs: ~2x faster to
     * encode, colour detail at half resolution and a slightly larger file. Off by default (jpegli).
     */
    public static boolean getJpegFastEncoder() {
        try { return settings.getBoolean(SCOPE_GLOBAL, "pref_jpeg_fast_encoder", false); }
        catch (RuntimeException e) { return false; }
    }
    /** P24: quality of every saved JPEG (plain, Ultra HDR base image and gain map), 70-100, default 98 as before. */
    public static int getJpegQuality() {
        return (int) Math.round(SettingsNumericRules.value("pref_jpeg_quality",
                settings.getString(SCOPE_GLOBAL, "pref_jpeg_quality", "98"), 98));
    }
    /**
     * «Формат фото»: the codec of the processed photo; HEIC below Android 9 and AVIF below Android 12 or without its encoder
     * library are written as JPEG (PhotoFormat.effective).
     */
    public static com.scamera.app.processing.PhotoFormat getPhotoFormat() {
        return com.scamera.app.processing.PhotoFormat.effective(getChosenPhotoFormat(), android.os.Build.VERSION.SDK_INT,
                com.scamera.app.processing.avif.AvifEncoder.available());
    }
    /** The stored «Формат фото» value as chosen (no API fallback). */
    public static com.scamera.app.processing.PhotoFormat getChosenPhotoFormat() {
        return com.scamera.app.processing.PhotoFormat.parse(settings.getString(SCOPE_GLOBAL,
                com.scamera.app.processing.PhotoFormat.KEY, "jpeg"));
    }
    /** Stores «Формат фото» (the codec) as the settings screen stores it (the list value). */
    public static void setChosenPhotoFormat(com.scamera.app.processing.PhotoFormat format) { settings.set(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY, format.value); }
    /** HEIC quality 1-100 (default 90). */
    public static int getHeicQuality() {
        return (int) Math.round(SettingsNumericRules.value(com.scamera.app.processing.PhotoFormat.KEY_HEIC_QUALITY,
                settings.getString(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_HEIC_QUALITY, "90"), 90));
    }
    /** «HEIC 10 бит» as stored (default off); whether a shot uses it: processing.heif.Heic10Support.wanted. */
    public static boolean isHeic10Bit() { return settings.getBoolean(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_HEIC_10BIT, false); }
    /** WebP quality 1-100 (default 90); ignored by the lossless WebP. */
    public static int getWebpQuality() {
        return (int) Math.round(SettingsNumericRules.value(com.scamera.app.processing.PhotoFormat.KEY_WEBP_QUALITY,
                settings.getString(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_WEBP_QUALITY, "90"), 90));
    }
    public static boolean isWebpLossless() { return settings.getBoolean(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_WEBP_LOSSLESS, false); }
    /** AVIF quality 1-100 (default 90); not used by the lossless AVIF. */
    public static int getAvifQuality() {
        return (int) Math.round(SettingsNumericRules.value(com.scamera.app.processing.PhotoFormat.KEY_AVIF_QUALITY,
                settings.getString(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_AVIF_QUALITY, "90"), 90));
    }
    /** AVIF «Без потерь»: exact pixels (identity matrix, 4:4:4, the photo's own bit depth). */
    public static boolean isAvifLossless() { return settings.getBoolean(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_AVIF_LOSSLESS, false); }
    /** AVIF «Глубина цвета»: 8, 10 (default) or 12 bit. */
    public static int getAvifDepth() {
        int depth = (int) Math.round(PreferenceNumber.read(settings.getString(SCOPE_GLOBAL,
                com.scamera.app.processing.PhotoFormat.KEY_AVIF_DEPTH, "10"), 10));
        return depth == 8 || depth == 12 ? depth : com.scamera.app.processing.PhotoFormat.AVIF_DEFAULT_DEPTH;
    }
    /** AVIF «Цветовая субдискретизация»: 4:4:4 (default, "444") or 4:2:0 ("420"). */
    public static boolean isAvifYuv444() {
        return !"420".equals(settings.getString(SCOPE_GLOBAL,
                com.scamera.app.processing.PhotoFormat.KEY_AVIF_CHROMA, "444").trim());
    }
    /** AVIF «Скорость кодирования»: libavif speed 0-10 (default 6). */
    public static int getAvifSpeed() {
        return (int) Math.round(SettingsNumericRules.value(com.scamera.app.processing.PhotoFormat.KEY_AVIF_SPEED,
                settings.getString(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_AVIF_SPEED, "6"),
                com.scamera.app.processing.PhotoFormat.AVIF_DEFAULT_SPEED));
    }
    /** The AVIF settings of a shot; the encoder uses every core. */
    public static com.scamera.app.processing.avif.AvifEncoder.Options getAvifOptions() {
        return new com.scamera.app.processing.avif.AvifEncoder.Options(getAvifQuality(), isAvifLossless(), getAvifDepth(),
                isAvifYuv444(), getAvifSpeed(), Runtime.getRuntime().availableProcessors());
    }
    /** P46 «Цветовое пространство» as stored: sRGB (default) or Display P3; the pipeline renders and every file is tagged in it. */
    public static com.scamera.app.processing.color.OutputColour.Space getOutputColourSpace() {
        return com.scamera.app.processing.color.OutputColour.Space.parse(settings.getString(SCOPE_GLOBAL,
                com.scamera.app.processing.PhotoFormat.KEY_COLOR_SPACE, "srgb"));
    }
    /** P46 / P266 «HDR-фото» as stored (default off; the one switch with Ultra HDR); whether a shot uses it: HdrOutput.wanted. */
    public static boolean isHdrOutputOn() { return settings.getBoolean(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_HDR, false); }
    /** «Также сохранять JPEG»: a JPEG next to the HEIC / WebP / AVIF photo (it carries Ultra HDR when that is on). */
    public static boolean isAlsoSaveJpeg() { return settings.getBoolean(SCOPE_GLOBAL, com.scamera.app.processing.PhotoFormat.KEY_ALSO_JPEG, false); }
    /** Ultra HDR is computed only when it ends up in a file: the setting is on and the shot writes a JPEG. */
    public static boolean isUltraHdrActive() { return com.scamera.app.processing.PhotoFormat.ultraHdrApplies(isUltraHdrOn(), getPhotoFormat(), isAlsoSaveJpeg()); }

    public static boolean isPerLensSettingsOn() { return flag(Key.KEY_SAVE_PER_LENS_SETTINGS); }

    public static int isSaveRaw() { return num(Key.KEY_SAVE_RAW); }

    public static void setSaveRaw(int value) { store(Key.KEY_SAVE_RAW, value); }

    public static boolean isRoundEdgeOn() { return flag(Key.KEY_SHOW_ROUND_EDGE); }

    public static int getGridValue() { return num(Key.KEY_SHOW_GRID); }

    public static void setGridValue(int value) { store(Key.KEY_SHOW_GRID, value); }

    public static boolean isCameraSoundsOn() { return flag(Key.KEY_CAMERA_SOUNDS); }

    /** «Звук таймера» (P16): the countdown sound of the self-timer, independent of the shutter sound. */
    public static boolean isTimerSoundOn() { return settings.getBoolean(SCOPE_GLOBAL, Key.KEY_TIMER_SOUND, true); }

    /**
     * Colour-block side of the RAW stream for statistics and the raw viewfinder when MosaicStream has not measured one: 1
     * (P129: SCAM HDR's declared mosaic modes are gone).
     */
    public static int mosaicBlock() { return 1; }

    /** SCAM: flush the HAL request queue before the bracket (shutter lag ~0.4 s -> ~0.15 s). */
    public static boolean isScamFastCapture() {
        if (isHybridShot()) return hybridSwitch("fast_capture", true);
        return settings.getBoolean(SCOPE_GLOBAL, "pref_scamold_fast_capture", true);
    }

    /**
     * The scam-style processing route (own merge, SCAMERA tone, no second HDR+ denoise): the SCAM Hybrid (its own switch,
     * section «SCAM-гибрид») or the autonomous HDR / SCAM HDR switches. Exclusive with the native remosaic engines and
     * RAW MFSR; stored choices are retained.
     */
    public static boolean isScamHdrEnabled() { return isScamHybridEnabled() || isAutonomousHdrSwitchOn(); }
    /** The SCAM HDR route is selected and the RAW path is one SCAM HDR can merge. */
    private static boolean isAutonomousHdrSwitchOn() { return isScamHdrRoute(); }

    /**
     * SCAM HDR can run here: its SCAM network is a Hexagon v79 context, so only on the Snapdragon 8 Elite (SM8750). Every
     * other phone is hybrid-only, whatever the stored route says (owner, 2026-10-07).
     */
    public static boolean isScamHdrSupported() { return ScamHybridKeys.scamNetSoc(); }

    /**
     * The merge route (pref_merge_route): "hybrid" (SCAM Hybrid, the default on every phone) or "scamhdr" (the SCAM HDR
     * network), the latter only where {@link #isScamHdrSupported()}. scam_dev.txt "hybrid 1/0" picks the route for A/B
     * tests on such a phone.
     */
    public static String mergeRoute() {
        // P129 (owner, 2026-10-15): SCAM HDR is gone, SHDR (the former Hybrid) is the only merge; a stored route is ignored
        return ScamHybridKeys.ROUTE_HYBRID;
    }
    public static boolean isScamHdrRoute() { return ScamHybridKeys.ROUTE_SCAM_HDR.equals(mergeRoute()); }
    public static boolean isScamDiagnosticsEnabled() {
        if (isHybridShot()) return hybridSwitch("diagnostics", false);
        return settings.getBoolean(SCOPE_GLOBAL, "pref_scamhdr_diagnostics", false);
    }

    /**
     * Optional root features (off by default; everything works without root):
     * the vivo stock-AE observer for the SCAM bracket, and su as the fallback
     * launcher when the worker is not installed as a native library.
     */
    public static boolean isRootEnabled() { return settings.getBoolean(SCOPE_GLOBAL, "pref_root_enabled", false); }
    /**
     * true: the vivo stock AE solver through the root observer («Стоковый AE vivo»: Root on, vivo X200 Ultra only); false: the
     * SCAMERA planner (no root, any device; the default since 8 October 2026).
     */
    public static boolean useStockBracketPlanner() {
        // P129: the stock AE observer (root + Frida) planned SCAM HDR's bracket only; SHDR's N frames are the ZSL ring at the
        // preview exposure, always the SCAMERA plan of that exposure. Its native library and assets are gone.
        return false;
    }
    /** SCAM: build L from the ZSL N frames instead of capturing it after the shutter. */
    public static boolean isScamZslLong() {
        if (isHybridShot()) return false; // the hybrid takes no L frame (Shasta brackets after the press instead)
        return settings.getBoolean(SCOPE_GLOBAL, "pref_scamold_zsl_long", true);
    }
    /** RAW lens shading map: auto | apply | skip (HAL already corrected RAW_SENSOR). */
    public static String getRawLscMode() { return settings.getString(SCOPE_GLOBAL, "pref_raw_lsc_mode", "auto"); }
    /** P134 «Плёночный LUT»: the .cube file in Download/SCAMERA/LUT ("" = none). */
    public static String filmLut() { return settings.getString(SCOPE_GLOBAL, com.scamera.app.processing.color.FilmLut.KEY, ""); }
    /** P134: the strength of the film LUT, 0..1 (default 1). */
    public static float filmLutStrength() {
        try {
            Object v = PreferenceValue.get(settings.getDefaultPreferences(), com.scamera.app.processing.color.FilmLut.STRENGTH_KEY);
            return Math.max(0f, Math.min(1f, (float) PreferenceNumber.read(v, 100) / 100f));
        } catch (RuntimeException e) {
            return 1f;
        }
    }
    /** P142: «Раздельные фокус и экспозиция»: the tap's focus ring and exposure square are dragged on their own. */
    public static boolean isSplitAfAe() { return settings.getBoolean(SCOPE_GLOBAL, "pref_split_af_ae", false); }
    /** Lower the reported RAW black level to the frame's measured dark floor when it is higher. */
    public static boolean isRawBlackFromData() { return settings.getBoolean(SCOPE_GLOBAL, "pref_raw_black_from_data", true); }
    /** P162 «Обрезка кадра»: the RAW's zero padding (black stripes) cut off automatically (per module). */
    public static final String RAW_CROP_AUTO = "pref_raw_crop_auto";
    /** P162: the manual margins of the upright photo in sensor pixels: left, top, right, bottom. */
    public static final String[] RAW_CROP_SIDES = {"pref_raw_crop_left", "pref_raw_crop_top", "pref_raw_crop_right", "pref_raw_crop_bottom"};
    /** P162: the last shot's detection for the settings' picture (RawCrop.describe; written by the processing). */
    public static final String RAW_CROP_DETECTED = "pref_raw_crop_detected";

    public static boolean isRawCropAuto() { return settings.getBoolean(SCOPE_GLOBAL, RAW_CROP_AUTO, true); }

    /** P162: the manual margins {left, top, right, bottom} of the upright photo (0..RawCrop.MAX_MARGIN sensor px). */
    public static int[] rawCropMargins() {
        final int[] sides = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                sides[i] = (int) Math.round(Double.parseDouble(text(RAW_CROP_SIDES[i], "0")));
            } catch (NumberFormatException e) {
                sides[i] = 0;
            }
            sides[i] = Math.max(0, Math.min(com.scamera.app.processing.RawCrop.MAX_MARGIN, sides[i]));
        }
        return sides;
    }

    public static void setRawCropDetected(String text) { settings.set(SCOPE_GLOBAL, RAW_CROP_DETECTED, text); }

    /** SCAM motion (CRE) source: auto (vendor, else APK copy) | bundled (always APK copy). */
    public static String getScamCreSource() {
        if (isHybridShot()) return hybridString("cre_source", "auto");
        return settings.getString(SCOPE_GLOBAL, "pref_scamhdr_cre_source", "auto");
    }
    /** The SoC the SCAM HDR network was built for (Hexagon v79): SCAM HDR (SCAM) runs there; the SCAM Hybrid runs anywhere. */
    public static boolean isScamNetSoc() { return ScamHybridKeys.scamNetSoc(); }

    // ---- SCAM Hybrid: a route of its own (section «SCAM-гибрид», scam_hybrid_screen), separate from SCAM HDR ----

    /**
     * Prefix of every SCAM Hybrid preference: pref_scam_hybrid_&lt;key&gt;. A new hybrid setting gets a key with this prefix in
     * res/xml/preferences.xml (category of scam_hybrid_screen), numeric bounds in SettingsNumericRules, and is read with
     * {@link #hybridValue}, {@link #hybridSwitch}, {@link #hybridString} or {@link #hybridList}.
     */
    public static final String HYBRID_PREFIX = ScamHybridKeys.PREFIX;
    /** The merge route selector (hybrid | scamhdr), see {@link #mergeRoute()}. */
    public static final String ROUTE_KEY = ScamHybridKeys.ROUTE;
    /** Hybrid defaults that differ from the fallback the shared nodes pass; any other key keeps the caller's fallback. */
    private static final Map<String, Float> HYBRID_DEFAULTS = new HashMap<>();
    static {
        // N frames from the ZSL ring: owner 2026-10-07 20 -> 30, owner 2026-10-10 back to 20 (P68; SettingsMigration moves a
        // stored former default); SCAM HDR SCAM: 4.
        HYBRID_DEFAULTS.put("zsl_frames", 20f);
        // P190: frames of a FULLRES 50 MP shot (0 = auto: as zsl_frames) and of a 200 MP one (3 N + 1 ultrashort)
        // P203 (owner, 2026-10-17: «сделай по дефолту 12 обычных кадров для 50мп»): 12 N, the plan's bracketed frames besides
        HYBRID_DEFAULTS.put("fullres50_frames", 12f);
        HYBRID_DEFAULTS.put("fullres200_frames", 4f);
        // P68 (owner, 10 October 2026), every phone: Sabre 6.1 kernel always, Mochi always, Bento always with 3 ultrashort frames,
        // Shasta always with 3 frames. The worker keeps its own defaults (replays unchanged); the app writes these keys when unset.
        HYBRID_DEFAULTS.put("sabre61", 1f);
        HYBRID_DEFAULTS.put("mochi", 2f);
        HYBRID_DEFAULTS.put("bento", 2f);
        HYBRID_DEFAULTS.put("bento_frames", 2f); // P497 (owner, 2026-11-04: the third ultrashort frame adds nothing)
        HYBRID_DEFAULTS.put("shasta_mode", 2f);
        HYBRID_DEFAULTS.put("shasta_frames", 3f);
        HYBRID_DEFAULTS.put("shasta_motion_max", 4f);
        HYBRID_DEFAULTS.put("soft_tone", 0f);    // AgX + Exposure Fusion with the Bento headroom
        HYBRID_DEFAULTS.put("bento_factor", 8f); // SCAM ultrashort_tet_factor
        // P309 (owner, 2026-10-20: «нам нужно сделать более мягкий переход между экспозициями», «максимально мягкий переход ... чтобы
        // не было резких скачков»): the ultrashort frames take over along smoothstep(0.4, 0.97) of the base level (spread by sigma 6
        // cells), within 32 cells of a clip and in its connected bright area. OPPO lamp burst after the app's denoise (host
        // emulation): the step of the grain per 4 px at the clip border 1.76x -> 1.09x (fine), 1.21x -> 1.10x (coarse); sigma 2 /
        // threshold 0.6 / reach 16 gave 1.16x / 1.18x. The ring next to the clip no longer darker (x7u_1x +7 %), clipped areas and
        // bursts without a clip unchanged.
        HYBRID_DEFAULTS.put("bento_ramp", 6f);
        HYBRID_DEFAULTS.put("bento_ramp_lo", 0.4f);
        HYBRID_DEFAULTS.put("bento_ramp_hi", 0.97f);
        HYBRID_DEFAULTS.put("bento_ramp_reach", 32f);
        // P308 (owner, 2026-10-20: «нам нужно перевести всё на гпу», «сделай гпу выравнивание и дай возможность выбора»): F6 auto =
        // the GPU where the CPU field would come whole before the merge, the CPU where it streams alongside it. OPPO Find X7 Ultra
        // replays, program cache warm: the GPU 0.03-0.9 s faster than the whole CPU field, the streamed CPU field 0.03-0.3 s faster
        // than the GPU; the same field either way (merge PSNR 124 dB between them).
        HYBRID_DEFAULTS.put("la_engine", 2f);
    }

    /** The hybrid route is selected (pref_merge_route, see {@link #mergeRoute()}). */
    public static boolean isScamHybridSwitchOn() { return ScamHybridKeys.ROUTE_HYBRID.equals(mergeRoute()); }
    /**
     * The SCAM Hybrid (Sabre 6.1 kernel x SCAM 9.6 rejection x Bento x Shasta, any GLES 3.1 GPU) takes the shot: its route is
     * selected and the RAW path is plain Bayer or the SCAMERA remosaic without RAW MFSR.
     */
    public static boolean isScamHybridEnabled() { return isScamHybridSwitchOn(); }
    /** The SCAM HDR route is selected on a RAW path it can merge. */
    public static boolean isScamHdrSwitchOn() { return isAutonomousHdrSwitchOn(); }
    /** SCAM HDR (the SCAM HDR network) takes the shot: its switches are on and the hybrid does not take it. */
    public static boolean isScamHdrScamEnabled() { return isScamHdrSwitchOn() && !isScamHybridEnabled(); }
    /**
     * The SCAM capture route is used (ZSL RAW ring, SCAMERA plan, worker, SCAMERA tone), by the SCAM Hybrid or by SCAM HDR;
     * {@link #isScamHybridEnabled()} and {@link #isScamHdrScamEnabled()} tell them apart.
     */
    public static boolean isScamEnabled() { return isScamHybridEnabled() || isScamHdrSwitchOn(); }

    private static final int SHOT_SCAM = 1, SHOT_HYBRID = 2;
    /**
     * Per-shot settings profile of the thread that processes the shot: ScamProcessor sets it around its whole processing
     * (merge, post pipeline, saving) and clears it afterwards. The nodes shared by SCAM HDR and the hybrid then read the
     * knobs of the route that merged the shot, even when the switches changed between the press and the processing.
     * Thread-confined on purpose: the camera thread plans and captures the next shot (and the viewfinder runs) while the
     * SCAM processing thread still works on the previous one, and those must keep reading the live route.
     */
    private static final ThreadLocal<Integer> shotProfile = new ThreadLocal<>();
    public static void beginShotProfile(boolean hybrid) { shotProfile.set(hybrid ? SHOT_HYBRID : SHOT_SCAM); }
    public static void endShotProfile() { shotProfile.remove(); }
    /**
     * The SCAM Hybrid's settings are in force: on a thread processing a shot, whether the hybrid merged it; on any other
     * thread (viewfinder, capture and plan of the next shot) whether the hybrid takes the next shot. Then every SCAM HDR
     * getter of this class (routeInternalValue/Switch, scamHdrValue, fusion, despeckle, noise and CRE source, ...) reads the
     * hybrid's copy pref_scam_hybrid_&lt;key&gt; instead of the SCAM HDR key.
     */
    public static boolean isHybridShot() {
        final Integer profile = shotProfile.get();
        return profile != null ? profile == SHOT_HYBRID : isScamHybridEnabled();
    }
    /** A hybrid shot is being post-processed on this thread (strict: false outside ScamProcessor). */
    public static boolean isHybridShotProcessing() {
        final Integer profile = shotProfile.get();
        return profile != null && profile == SHOT_HYBRID;
    }
    /** @deprecated the hybrid is not a SCAM HDR engine any more: {@link #isHybridShot()} (per shot) or {@link #isScamHybridEnabled()}. */
    @Deprecated
    public static boolean isScamHybridShot() { return isHybridShot(); }

    /**
     * Output of the hybrid merge (pref_scam_hybrid_output), P501 (owner, 2026-11-04: «В Арк камере есть Sabre 1.5х и 2х, возьми из
     * неё», for both processing modes): the reference's Sabre scales - "sensor" (1.0x, default), "1.5x" (the merge on a 1.5x grid:
     * the fast route merges there, the SCAM merge on its 2x grid and the worker resamples), "2x" (the native 2x grid). The old
     * 12 / 16 / 20 MP (the 2x grid resized) read as 1.0x / 1.5x / 1.5x. scam_dev.txt: "hybrid_output 0|1.5|2".
     */
    public static String hybridOutputMode() {
        Float dev = scamDevValue("hybrid_output");
        if (dev != null) { final float v = dev; return v == 2f ? "2x" : v == 1.5f || v == 16f || v == 20f ? "1.5x" : "sensor"; }
        return outputMode(hybridString("output", "sensor"));
    }
    /** P501: a stored output value as the current modes (the old 12 MP is the sensor size, 16 / 20 MP the 1.5x grid). */
    public static String outputMode(String stored) {
        if ("2x".equals(stored)) return "2x";
        return "1.5x".equals(stored) || "16".equals(stored) || "20".equals(stored) ? "1.5x" : "sensor";
    }
    /** P501: the merged size of a w x h stream in {@code mode} (ScamHybridBurst.sabreSize). */
    public static android.graphics.Point hybridSabreSize(int w, int h, String mode) { return com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.sabreSize(w, h, mode); }
    /**
     * Downsampler of the final bitmap after the 2x pipeline (pref_scam_hybrid_downsampler): "lanczos" (3 lobes),
     * "bicubic" (Catmull-Rom), "area" (box average), "bilinear". scam_dev.txt: "hybrid_downsampler 0..3".
     */
    public static String hybridDownsampler() {
        Float dev = scamDevValue("hybrid_downsampler");
        if (dev != null) { int v = Math.round(dev); return v == 1 ? "bicubic" : v == 2 ? "area" : v == 3 ? "bilinear" : "lanczos"; }
        return hybridString("downsampler", "lanczos");
    }
    private static final String[] HYBRID_OUTPUT_VALUES = {"sensor", "1.5x", "2x"};
    private static final String[] HYBRID_DOWNSAMPLER_VALUES = {"lanczos", "bicubic", "area", "bilinear"};
    /** Stores pref_scam_hybrid_output by its index in {sensor, 1.5x, 2x} (quick-settings chips). */
    public static void setHybridOutputIndex(int index) { setHybridValue("output", HYBRID_OUTPUT_VALUES[Math.max(0, Math.min(HYBRID_OUTPUT_VALUES.length - 1, index))]); }
    public static int hybridDownsamplerIndex() {
        String m = hybridDownsampler();
        for (int i = 0; i < HYBRID_DOWNSAMPLER_VALUES.length; i++) if (HYBRID_DOWNSAMPLER_VALUES[i].equals(m)) return i;
        return 0;
    }
    public static void setHybridDownsamplerIndex(int index) { setHybridValue("downsampler", HYBRID_DOWNSAMPLER_VALUES[Math.max(0, Math.min(HYBRID_DOWNSAMPLER_VALUES.length - 1, index))]); }
    public static String hybridDownsamplerName() {
        switch (hybridDownsampler()) { case "bicubic": return "Bicubic"; case "area": return "Area"; case "bilinear": return "Bilinear"; default: return "Lanczos-3"; }
    }
    /** Final JPEG size for the sensor size w x h: sensor, 12/16/20 MP (4:3 of the sensor) or the native 2x grid. */
    /**
     * P226 «Увеличивать кадр с кропом» (pref_crop_upscale, per module, on by default): off, a shot with a crop - a lens crop
     * (P148 copies), a sensor-crop / ISZ module or a digital zoom - keeps the cropped pixels: no Sabre 2x grid and no resize up
     * to the output resolution.
     */
    public static boolean cropUpscale() {
        try {
            return settings.getBoolean(SCOPE_GLOBAL, CROP_UPSCALE_KEY, true);
        } catch (RuntimeException e) {
            return true;
        }
    }
    public static final String CROP_UPSCALE_KEY = "pref_crop_upscale";

    /** P228: the lens filter's id (LensFilters, "" = none). */
    public static String lensFilter() {
        try {
            String v = settings.getString(SCOPE_GLOBAL, com.scamera.app.processing.color.LensFilters.KEY, "");
            return com.scamera.app.processing.color.LensFilters.known(v) ? v : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** P228: the lens filter's strength 0..1 (stored 0..100, default 50). */
    public static float lensFilterStrength() { return lensFilterNumber(com.scamera.app.processing.color.LensFilters.STRENGTH_KEY, 50f); }

    /** P228: the filter's parameter and fine controls, stored per filter (LensFilters.tuneKey). */
    public static com.scamera.app.processing.color.LensFilters.Tune lensFilterTune(String id) {
        try {
            Object v = PreferenceValue.get(settings.getDefaultPreferences(),
                    com.scamera.app.processing.color.LensFilters.tuneKey(id));
            return com.scamera.app.processing.color.LensFilters.Tune.parse(id, v == null ? null : String.valueOf(v));
        } catch (RuntimeException e) {
            return com.scamera.app.processing.color.LensFilters.Tune.defaults(id);
        }
    }

    private static float lensFilterNumber(String key, float fallback) {
        try {
            Object v = PreferenceValue.get(settings.getDefaultPreferences(), key);
            return Math.max(0f, Math.min(1f, (float) PreferenceNumber.read(v, fallback) / 100f));
        } catch (RuntimeException e) {
            return fallback / 100f;
        }
    }

    /** P223 «ISP-превью в FULLRES и ISZ» (on by default): off, those sessions repeat the RAW stream only (the RAW viewfinder). */
    public static final String MOSAIC_ISP_PREVIEW_KEY = "pref_mosaic_isp_preview";
    /** P223 «Поправки баланса белого в FULLRES и ISZ» (on by default): off, the camera's own white balance. */
    public static final String MOSAIC_WB_KEY = "pref_mosaic_wb_corrections";

    /**
     * P223: our white-balance corrections of mosaic shots (ISZ without remosaic, FULLRES) - the frame estimate on the camera's
     * curve (P121 / P212f), the FULLRES neutral resolver (P212g), the OPPO native session's own estimate (P212e). Off: the
     * camera's white balance (on the OPPO native mode, which has no AWB of its own, the camera's plain-mode neutral).
     */
    public static boolean mosaicWbCorrections() {
        try {
            return settings.getBoolean(SCOPE_GLOBAL, MOSAIC_WB_KEY, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** P226: the shot being merged is a crop (the active module's lens / sensor crop or the digital zoom's residual). */
    public static boolean cropShot() {
        try {
            final String slot = ModuleRegistry.active();
            return com.scamera.app.control.ZoomController.processingResidual() > 1.005f
                    || ModuleRegistry.crop(slot) > 1.0001f || ModuleRegistry.sensorCrop(slot);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The photo size of a w x h stream: the Sabre scale's merged size, scaled down to «Уменьшить до» (P503,
     * pref_scam_hybrid_resize: off / 12 / 16 / 20 MP / own megapixels / own resolution) when that is smaller.
     */
    public static android.graphics.Point hybridFinalSize(int w, int h) {
        final android.graphics.Point merged = hybridSabreSize(w, h, hybridOutputMode());
        final android.graphics.Point t = com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.resizeTarget(
                hybridResizeMode(), hybridResizeCustom(), w, h);
        return t != null && t.x < merged.x && t.y < merged.y ? t : merged;
    }
    /** P503: «Уменьшить до» (pref_scam_hybrid_resize): "off", "12", "16", "20", "mp" (own megapixels) or "px" (own resolution). */
    public static String hybridResizeMode() { return hybridString("resize", "off"); }
    /** P503: the own value of the «Уменьшить до» mode (pref_scam_hybrid_resize_mp / _px), null for the presets. */
    @androidx.annotation.Nullable
    public static String hybridResizeCustom() {
        final String mode = hybridResizeMode();
        return "mp".equals(mode) ? hybridString("resize_mp", "24") : "px".equals(mode) ? hybridString("resize_px", "4000x3000") : null;
    }
    /**
     * The worker tuning lines of the hybrid settings: {tuning name, pref_scam_hybrid_ suffix} (hybridTuningText writes them; the lab
     * maps a burst's own lines back to their rows with {@link #hybridTuningSuffix}).
     */
    private static final String[][] TUNING_KEYS = {
            {"bento", "bento"},
            {"cdm", "cdm"},
            {"kernelScale", "kernel"}, {"weightCap", "weight_cap"},
            // P587: the texture / bokeh footprints of the kernel (ArkCam's cov_base / cov_blurred / cov_trans as multipliers, 1 = as before)
            {"kernelTexture", "kernel_texture"}, {"kernelBokeh", "kernel_bokeh"}, {"kernelTransition", "kernel_transition"},
            {"fwe", "fwe"}, {"dilateScale", "dilate"},
            {"shastaSharpness", "shasta_sharpness"}, {"bentoUsWeight", "bento_weight"},
            {"shastaMaxRatio", "shasta_max_ratio"}, {"filterVariance", "filter_variance"},
            {"bentoUsSigma", "bento_sigma"}, {"bentoFrames", "bento_frames"}, {"bentoChromaSigma", "bento_chroma_sigma"},
            // P309: the soft transition of the ultrashort frames (the share ramp of the base level near a clip; worker default 0 = the
            // previous mask, replays unchanged; app defaults below)
            {"bentoRamp", "bento_ramp"}, {"bentoRampLo", "bento_ramp_lo"}, {"bentoRampHi", "bento_ramp_hi"}, {"bentoRampReach", "bento_ramp_reach"},
            {"dilateFloor", "dilate_floor"}, {"widenBelow", "widen_below"}, {"chromaDiff", "chroma_diff"},
            {"bentoValidate", "bento_validate"}, {"bentoMotionMax", "bento_motion_max"},
            // P68: motion fallback of forced Shasta frames, % of the blocks (the Shasta mode is written below)
            {"shastaMotionMax", "shasta_motion_max"},
            {"rawNoise", "tensor_noise"}, {"snrScale", "snr_scale"},
            {"boost", "boost_value"}, {"varianceThreshold", "boost_threshold"}, {"motionThreshold", "motion_threshold"},
            {"lutHiSigma", "lut_sigma"},
            // Round 5 (research/hybrid5/impl_worker.md): Sabre 6.1 kernel 0 off / 1 always / 2 auto (night: 6.1 SNR key <= s61MaxKey),
            // highlights and outliers (hot*), SCAM 9.6 Bento fallback checks. Absent keys keep the worker defaults.
            {"sabre61", "sabre61"}, {"s61MaxKey", "s61_max_key"}, {"s61Mode", "s61_mode"},
            // F6 tile-local alignment (research/hybrid5/f6_local_align.md): 0 off / 1 bilinear field / 2 constant per tile;
            // the 6.1 auto rule in daylight needs F6 and this much RMS donor motion; daylight 6.1 noise multipliers.
            {"localAlign", "local_align"}, {"s61MinMotion", "s61_min_motion"},
            // P308: where F6 runs, 2 auto / 1 the GPU / 0 the CPU (worker default 0: replays unchanged; the app writes its default 2)
            {"laGpu", "la_engine"},
            {"s61DayTensorNoise", "s61_day_tensor_noise"}, {"s61DayGdNoise", "s61_day_gd_noise"},
            {"s61TensorNoise", "s61_tensor_noise"}, {"s61GdNoise", "s61_gd_noise"},
            {"hotSigma", "hot_sigma"}, {"hotBaseSigma", "hot_base_sigma"}, {"hotFrames", "hot_frames"},
            {"hotCross", "hot_cross"}, {"hotMaxLevel", "hot_max_level"}, {"hotMaxKey", "hot_max_key"},
            // P214k: plausibility cap of the fixed-pattern test, sites per MP of a strip (worker default 2000; 0 = no cap; dev key)
            {"hotCap", "hot_cap"},
            {"bentoInvalid", "bento_invalid"},
            // P14 / P22: frames of a Quad / Tetra burst merged (4 / 16 sub-frames each; worker default 30 since 7 October 2026, was 24;
            // SettingsMigration moves a stored 24 once), the kernel across
            // edges of the sub-frame merge (worker default 0.6), shared motion of a frame's sub-frames (worker default 1)
            {"mosaicFrames", "mosaic_frames"}, {"mosaicBlock", "mosaic_block"},
            {"mosaicEdgeScale", "mosaic_edge_scale"}, {"mosaicShare", "mosaic_share"},
            // P29 native mosaic merge (research/p29): mosaicPath 1 = native (worker default since P34; Tetra too since P35, mosaicTetra
            // 1 = T1, window 2 x mosaicWindow sensor px), 0 = the sub-frame split above; the keys below act only with 1 (worker defaults: window 3 full, kernel
            // scale 0.7, edge scale 0.6, flat-area kernel x2.4, eigenvalue clamp 2, ks 1 / 0.85, no fill, fill support 0.25; at the
            // 6.1 night key kernel scale 1 / edge scale 0.6, dev keys only; the full-window switch is written below).
            // The XML default of every row is stored when the screen is first opened (HybridSettingsTest): the stored former defaults
            // of a P29 build ("0" = the split, kernel scale 1) move to the native merge in SettingsMigration's defaults revision 6, the
            // stored former "Tetra path" default "0" to T1 in revision 7 (P35).
            {"mosaicPath", "mosaic_path"}, {"mosaicWindow", "mosaic_window"}, {"mosaicKernelScale", "mosaic_kernel_scale"},
            {"mosaicNativeEdgeScale", "mosaic_native_edge_scale"}, {"mosaicKernelG", "mosaic_kernel_g"},
            {"mosaicKernelRB", "mosaic_kernel_rb"}, {"mosaicChromaFill", "mosaic_chroma_fill"},
            {"mosaicFillSupport", "mosaic_fill_support"}, {"mosaicTetra", "mosaic_tetra"},
            {"mosaicNativeFlatScale", "mosaic_native_flat_scale"}, {"mosaicNativeClamp", "mosaic_native_clamp"},
            {"mosaicNativeNightKernelScale", "mosaic_native_night_kernel_scale"}, {"mosaicNativeNightEdgeScale", "mosaic_native_night_edge_scale"},
            // Tetra's own night point (worker defaults, dev keys only): kernel / edge scale, multiplier on the flat-area kernel
            {"mosaicTetraNightKernelScale", "mosaic_tetra_night_kernel_scale"}, {"mosaicTetraNightEdgeScale", "mosaic_tetra_night_edge_scale"},
            {"mosaicTetraNightFlatScale", "mosaic_tetra_night_flat_scale"},
            // P73: Tetra T1 kernel scale by day (worker default 0.35, the Quad kernel in sensor px; dev key only)
            {"mosaicTetraKernelScale", "mosaic_tetra_kernel_scale"},
            // P27: the measured exposure ratio instead of a metadata ratio the data reliably disagrees with (worker default 1 since
            // 7 October 2026; 0 = report only, scam_dev.txt "hybrid_gain_measured 0")
            {"gainMeasured", "gain_measured"},
            // P71: the noise model checked against the burst (worker default 1; scam_dev.txt "hybrid_noise_measured 0" = off)
            {"noiseMeasured", "noise_measured"},
            // P62 Mochi (v11 PhotometricMerge): 0 off / 1 auto (the reference rule) / 2 force
            {"mochi", "mochi"},
            // Shot speed (W1.0): per-pass GPU times of the merge (a glFinish per pass) and the F6 threads, from scam_dev.txt
            // only ("hybrid_profile 1", "hybrid_la_threads 8"; no preference behind them). P33 W2.1: "hybrid_la_stream 0" merges with
            // the whole F6 field first (A/B), "hybrid_la_scam N" lowers the priority of the banded F6 threads.
            {"profile", "profile"}, {"laThreads", "la_threads"}, {"laStream", "la_stream"}, {"laScam", "la_scam"},
    };
    /** The lines hybridTuningText writes beside {@link #TUNING_KEYS}, by the row they come from (switches and modes). */
    private static final Map<String, String> TUNING_EXTRA = new HashMap<>();
    static {
        TUNING_EXTRA.put("auto2x", "auto2x");
        TUNING_EXTRA.put("shastaEnable", "shasta");
        TUNING_EXTRA.put("shastaForce", "shasta_mode");
        TUNING_EXTRA.put("boostEnable", "motion_boost");
        TUNING_EXTRA.put("cellClip", "cell_clip");
        TUNING_EXTRA.put("chromaDiffClamp", "chroma_diff_clamp");
        TUNING_EXTRA.put("bentoScam", "bento_scam");
        TUNING_EXTRA.put("bentoBlend", "bento_blend");
        TUNING_EXTRA.put("mosaicUsFill", "us_fill");
        TUNING_EXTRA.put("mosaicWindowFull", "mosaic_window_full");
    }

    /**
     * The pref_scam_hybrid_ suffix (also the scam_dev.txt line "hybrid_&lt;suffix&gt;") whose value the worker tuning line
     * {@code name} carries, null for a line no setting writes (the lab: a burst's own line that replaces a row's value).
     */
    public static String hybridTuningSuffix(String name) {
        if (name == null) return null;
        for (String[] k : TUNING_KEYS) if (k[0].equals(name)) return k[1];
        return TUNING_EXTRA.get(name);
    }

    /**
     * A stored tuning row as the worker gets it: read as {@link #hybridValue} reads it (a decimal comma too) and clamped to its
     * SettingsNumericRules bounds, so a restored config or an old module profile cannot send a value the row cannot show;
     * "" when unset or not a number (the caller then writes the app default, else the worker keeps its own).
     */
    static String storedTuningValue(String key) {
        final String raw = hybridString(key, "");
        if (raw.isEmpty()) return "";
        final double d = PreferenceNumber.read(raw, Double.NaN);
        return Double.isFinite(d) ? Float.toString((float) SettingsNumericRules.value(HYBRID_PREFIX + key, d, d)) : "";
    }

    /** "key value" lines for the worker's hybrid_tuning.txt, from the pref_scam_hybrid_* preferences (scam_dev.txt "hybrid_<key>"). */
    public static String hybridTuningText() {
        StringBuilder out = new StringBuilder();
        final boolean motion = isMotionMode(), noHdr = noHdrFrames();
        for (String[] k : TUNING_KEYS) {
            Float dev = scamDevValue("hybrid_" + k[1]);
            // P86: in «Движение» the mode's own merge values (boost, motion threshold, Bento), as hybridValue gives them
            String v = noHdr && ("bento".equals(k[1]) || "shasta".equals(k[1])) ? "0.0" : dev != null ? dev.toString()
                    : motion && MotionMode.replaces(k[1]) ? Float.toString(hybridValue(k[1], 0f)) : storedTuningValue(k[1]);
            // P68: a key with an app default (HYBRID_DEFAULTS) is written also when unset; any other keeps the worker default
            if ((v == null || v.isEmpty()) && HYBRID_DEFAULTS.containsKey(k[1])) v = HYBRID_DEFAULTS.get(k[1]).toString();
            if (v == null || v.isEmpty()) continue;
            try { out.append(k[0]).append(' ').append(Float.parseFloat(v.trim())).append('\n'); } catch (NumberFormatException ignored) {}
        }
        // P69 had the per-pass GPU times on by default on the Redmi Note 11 Pro; P79: off again (the glFinish after every pass made
        // the merge 30-60 % slower on the OPPO replays, its output unchanged). The switch «Замер времени проходов GPU»
        // (pref_scam_hybrid_profile) or scam_dev.txt "hybrid_profile 1" turns them on (the dev value is written by the loop above).
        if (scamDevValue("hybrid_profile") == null && hybridSwitch("profile", false)) out.append("profile 1\n");
        // P79 (owner allowed up to 10 % quality for speed on the Redmi Note 11 Pro only): its donors take the base frame's 6.1 kernel
        // covariance (s61Mode 6, no per-frame covariance pass over 36 RAW sites a cell; Mali-G57 cells pass 1.1-2.6 s a shot). OPPO
        // replays: synthetic PSNR 46.24 -> 46.30 / 42.24 -> 42.24 dB, real handheld x7u detail energy -0.2 %.
        if (scamDevValue("hybrid_s61_mode") == null && hybridString("s61_mode", "").isEmpty() && DeviceDefaults.redmiNote11Pro())
            out.append("s61Mode 6\n");
        // P332: the 6.1 kernel's structure tensor from the green sites at the full resolution (worker HybridTuning::s61Fine, its
        // noise term x16 x this; worker default 0 = the quad tensor, replays unchanged): fine texture no longer reads as flat or
        // in a flipped direction. scam_dev.txt "hybrid_s61_fine 0" = the quad tensor as before.
        // P336 (owner, 2026-10-25: «недавно у нас увеличилось время обработки», a 2 px grid on lit panels): off by default. On the
        // host replays it made the merge pass 3-7 x slower (the PC-backlight crop 1617 against 230 ms, the night crop 1053 against
        // 377 ms; ~+1 s a 26-frame shot on the phone) and raised the merge's 2 px lattice 7-16 %; scam_dev "hybrid_s61_fine 2" = P332.
        final float s61Fine = hybridValue("s61_fine", 0f);
        if (s61Fine > 0f) out.append("s61Fine ").append(s61Fine).append('\n');
        // P337 (owner, 2026-10-25: an ISO 12800 room next to WhyCam, «у нас заметно хуже мелкая деталь»): the 6.1 kernel x0.8 at
        // a key up to 8, back to x1 at 16 (worker s61NightScale; worker default 1 = replays unchanged). Phone replay of the owner's
        // burst: fine detail +11..16 %, text in the darks +31..48 %, flat noise +18 %. scam_dev.txt "hybrid_s61_night_scale 1" = before.
        final float nightScale = hybridValue("s61_night_scale", 0.8f);
        if (nightScale > 0f && nightScale != 1f) out.append("s61NightScale ").append(Math.max(0.25f, Math.min(2f, nightScale))).append('\n');
        // P402 (owner, 2026-10-29: «dezipper нам также перенеси»): the reference app's dezipper - the base frame's weight up to
        // max(1, value - accepted donors) where the donors were rejected (worker dezipper; 0 = off, the merge program as before)
        // P555 / P563: the native Quad / Tetra merge's normalised blocks through the texture cache (worker mosaicNatTex, default 0
        // since P563: 2.4x slower on the Adreno 750; output identical); scam_dev.txt "hybrid_mosaic_nat_tex 1" = the image
        if (hybridValue("mosaic_nat_tex", 0f) != 0f) out.append("mosaicNatTex 1\n");
        // P563: the fast route's Quad / Tetra chroma median (worker fastMosaicChroma, default 1); "hybrid_fast_mosaic_chroma 0" = before
        if (hybridValue("fast_mosaic_chroma", 1f) == 0f) out.append("fastMosaicChroma 0\n");
        // P566: the fast route's Quad site response in the unpack (worker fastSiteGain, default 1); "hybrid_fast_site_gain 0" = the QPC alone
        if (hybridValue("fast_site_gain", 1f) == 0f) out.append("fastSiteGain 0\n");
        // P571: the fast route's Quad lattice off the luma after its merge (worker fastQuadLattice, default 2); P632: 2 = the
        // lattice relative to the luma (no grid on dark branches against a bright sky), 1 = in luma units (P591, before), 0 = off
        final float quadLattice = hybridValue("fast_quad_lattice", 2f);
        if (quadLattice != 2f) out.append("fastQuadLattice ").append(Math.round(Math.max(0f, Math.min(2f, quadLattice)))).append('\n');
        // P596: Quad detail - the fast route's de-grid «flat» from the merged noise and its line notch only where few frames merged,
        // its few-donor cone along the edge, the native merge's green-site structure tensor where it sees more and its base demosaic's 1.8-site cone on
        // structure (worker fastQuadFlatNoise 1.5, fastQuadLineFrames 8, fastQuadCone 1.2, mosaicQuadFine 64, mosaicQuadTexture 0.5 (the Quad texture kernel x0.5), baseDemosaicQuadRadius
        // 1.8); "hybrid_quad_detail 0" = before
        if (hybridValue("quad_detail", 1f) == 0f)
            out.append("fastQuadFlatNoise 0\nfastQuadLineFrames 0\nfastQuadCone 0\nmosaicQuadFine 0\nbaseDemosaicQuadRadius 2.2\nmosaicQuadTexture 1\n");
        // P603: Tetra moving subjects - the range-gated smoothing where few frames merged (worker mosaicTetraMotionSmooth, default 1;
        // its share 0..1); "hybrid_tetra_motion_smooth 0" = before
        final float tetraSmooth = hybridValue("tetra_motion_smooth", 1f);
        if (tetraSmooth != 1f) out.append("mosaicTetraMotionSmooth ").append(Math.max(0f, Math.min(1f, tetraSmooth))).append('\n');
        // P634 (owner, 2026-11-12: «Займись улучшением результата на Tetra»): «Быстрый» on Tetra - the along-edge pass where
        // nearly one frame merged and the clipped lights' checker pass (worker fastTetraPost 1, fastTetraClipNotch 1); «Качество»
        // - a refused normal frame from its weak homography (normalWeakHom 1); "hybrid_tetra3 0" = before
        if (hybridValue("tetra3", 1f) == 0f) out.append("fastTetraPost 0\nfastTetraClipNotch 0\nnormalWeakHom 0\n");
        // P601 (owner, 2026-11-10: «лицо женщины в автобусе за парнем видно только на качестве»): the fast route's donor flow picked
        // per 4 x 4 block among the tiles near the surroundings' flow where the tile field misfits, and a donor kept where its block
        // fits within the noise at that flow (worker fastFlowPick 2; 1 = the pick alone); "hybrid_fast_flow_pick 0" = before
        final float flowPick = hybridValue("fast_flow_pick", 2f);
        if (flowPick != 2f) out.append("fastFlowPick ").append(Math.round(Math.max(0f, Math.min(2f, flowPick)))).append('\n');
        // P607 (owner, 2026-11-10, a street lamp at night: dark blotches and a ragged star in its clipped core): the fast route's
        // hole fill only between two sides of recovered light, never darker than the merged light (worker fastLightFill, default
        // 1; its share 0..1); "hybrid_fast_light_fill 0" = before
        final float lightFill = hybridValue("fast_light_fill", 1f);
        if (lightFill != 1f) out.append("fastLightFill ").append(Math.max(0f, Math.min(1f, lightFill))).append('\n');
        // P607 «Качество»: an ultrashort frame the GPU homography refused for too few inliers starts its registration from that
        // homography (worker usWeakHom 1), and the Bento blend flags a colour at the ultrashort clip where the frames holding half
        // its weight are clipped (usClipVote 1); "hybrid_us_weak_hom 0" / "hybrid_us_clip_vote 0" = before
        if (hybridValue("us_weak_hom", 1f) == 0f) out.append("usWeakHom 0\n");
        if (hybridValue("us_clip_vote", 1f) == 0f) out.append("usClipVote 0\n");
        // P613: every GPU merge strip is checked for rows the GPU did not write (a dropped or faulted job): such a merge fails and
        // the conservative retry / the base frame save the shot instead of the stale rows (worker stripGuard 1); 0 = no check
        if (hybridValue("strip_guard", 1f) == 0f) out.append("stripGuard 0\n");
        // P662: every GPU dispatch of the «Качество» merge a submission of its own, at most 2 in flight (worker gpuPace 2: the
        // viewfinder's frames are not queued behind a whole strip of passes; the same output); "hybrid_gpu_pace 0" = before, 1..8
        final float gpuPace = hybridValue("gpu_pace", 2f);
        if (gpuPace != 2f) out.append("gpuPace ").append(Math.round(Math.max(0f, Math.min(8f, gpuPace)))).append('\n');
        // P568: the Quad native merge with shared-memory tiles (worker mosaicNatTile: 0 off, 1 always, 2 the faster measured once per GPU)
        final float natTile = hybridValue("mosaic_nat_tile", 2f);
        if (natTile != 2f) out.append("mosaicNatTile ").append(Math.round(Math.max(0f, Math.min(2f, natTile)))).append('\n');
        // P647 (owner, 2026-11-12): «Ядро по освещению» (pref_scam_hybrid_kernel_snr, on by default) - the kernel's three footprints
        // follow the shot's SNR key on top of the rows' factors (worker kernelSnr; 0 = the footprints as before, bit for bit). Written
        // in every mode: «Спорт» reads the photo's switch and footprint rows (MotionMode.DEFAULTS replaces none of them), its merge
        // (either route, fastMergeSport) applies the same curve. scam_dev.txt "hybrid_kernel_snr 0" = off.
        out.append("kernelSnr ").append(hybridSwitch("kernel_snr", true) ? 1 : 0).append('\n');
        // P666: by day the shadows keep the kernel's transition / bokeh of before P647 (worker kernelSnrShadow, on by default: no
        // line); scam_dev.txt "hybrid_kernel_snr_shadow 0" = P647b's day factors in the shadows too
        if (!hybridSwitch("kernel_snr_shadow", true)) out.append("kernelSnrShadow 0\n");
        final float dezipper = hybridValue("dezipper", 4.5f);
        if (dezipper > 0f) out.append("dezipper ").append(Math.max(0.5f, Math.min(15f, dezipper))).append('\n');
        // P335 (owner, 2026-10-25: Why's rejections and anti-zipper «улучшить и добавить нам»): the rejection's soft motion prior
        // (worker rejPrior) and the Bayer base demosaic's false-colour damping by the Gr / Gb step above its noise (baseDemosaicAlias);
        // worker defaults = before (replays unchanged). Synthetic bursts with their truth (tools/replay_synth_burst.py): moving-area
        // colour error -3 % (day) with the damping, moving luma error -0.3 % (night) with the prior. The worker's plainChromaHand and
        // shastaSharpLo / Hi stay off (the hand-held chroma median share doubled the synthetic colour error; the long frames' sharpness
        // weight left the detail as it was). scam_dev.txt "hybrid_rej_prior 0", "hybrid_base_demosaic_alias 0" = as before.
        if (hybridValue("rej_prior", 1f) > 0f) out.append("rejPrior 1\n");
        // P341 (owner, 2026-10-26: our rejection map next to WhyCam's, «займись картой реджектов»): the rejection distance from the
        // squared differences of the 3 x 3 cells (worker rejPool), not one cell's single noise draw. Phone replay of the owner's
        // bursts: a static night sky keeps 0.95 of each frame instead of 0.78 (the map clean instead of a noise cloud), the moving
        // sea stays rejected (0.44) with its ripples; replays with rejPool 0 bit-identical. scam_dev.txt "hybrid_rej_pool 0" = before.
        // P344 (owner, 2026-10-26: «Постарайся максимально насколько можешь улучшить реджект карты»): the rejection set tuned on the
        // synthetic harness with its truth and 7 real bursts (worker defaults = before, replays unchanged without these lines):
        // rejPool 3 = each of the 3 x 3 cells over its own tolerance with the donor's texture around its own sample (P341's pooled
        // test had lost day texture: static buildings Neff 8.4 -> 10.0), the exact bilinear noise factor, the base blurred as the
        // donor's sample is, each colour over its own tolerance, a 0.4 % model-error floor, the 3 x 3 means' difference for smooth
        // motion near the noise (with a 1.5 % gain floor: flicker and AE steps are not motion), a soft knee (a dead zone to 0.05,
        // 0 from 0.8 x boost: the review's 0.6 cut late hand-held frames on a smooth wall into patches) and the spread from 0.35.
        // Harness: static day map 0.89 -> 0.99 accepted, lamp ghosts 12 -> 4 %, night water ghosts 16.6 -> 10.7 %; real bursts:
        // static flat 0.85 - 0.96 -> 0.94 - 0.99 with the moving sea and hand still black. Costs ~0.3 s per 12 MP merge (Adreno 750).
        // Moving thin wires are caught a little less than with rejPool 1 (that caught the static wires too). scam_dev.txt
        // "hybrid_rej_set 0" = P341's rejPool 1; the spread floor 0.35 only over the slider's untouched default 0.15.
        // «Движение» (owner, 2026-10-26: «Для режима "движения" сделай упор на максимальный захват движения»): the hard knee (a dead
        // zone to 0.1, 0 from 0.6 x boost) and the stricter difference cdm 0.3, the spread from the slider's floor: harness night
        // dots caught 0.93 -> 0.97 (their fade 16.6 -> 12.5 %), day water 0.60 -> 0.68 (ghosts 2.8 -> 2.4 %), at a static day map
        // of 0.976 instead of 0.986 (the mode's boost 10 adds nothing on the harness).
        if (hybridValue("rej_set", 1f) > 0f) {
            final boolean motionMode = isMotionMode();
            out.append("rejPool 3\nrejVarExact 1\nrejMatchBlur 1\nrejPerChannel 1\nrejRelFloor 0.004\nrejMeanDiff 0.2\n")
                    .append("rejMeanFloor 0.015\nrejPoolT 1.2\n")
                    .append(motionMode ? "rejKneeLo 0.1\nrejKneeHi 0.6\ncdm 0.3\n" : "rejKneeLo 0.05\nrejKneeHi 0.8\n")
                    // P358: the ultrashort donors skip the reject pass (the merge reads their Bento mask x validity, never its map):
                    // -0.22 s on the OPPO, the result the same to the driver's rounding
                    .append("rejSkipUs 1\n");
            if (!motionMode && Math.abs(hybridValue("dilate_floor", 0.15f) - 0.15f) < 1e-4f) out.append("dilateFloor 0.35\n");
        } else if (hybridValue("rej_pool", 1f) > 0f) out.append("rejPool 1\n");
        // P337 (owner, 2026-10-25: a backlit sea, «отражение на воде слилось в кашу»): sun glitter keeps its sparkles - a donor clearly
        // darker than a clipped base cell is rejected (clipReject) and a saturated G is at least the mean of all its samples,
        // the clipped ones at their values (rimLift). Phone replay of the owner's burst: merged G at the base's clipped green sites
        // median 0.69 -> 0.99, p10 0.31 -> 0.94; the night and telephoto bursts unchanged (0.002 % / 0 % of the pixels).
        // worker defaults 0 (replays unchanged); scam_dev.txt "hybrid_clip_reject 0", "hybrid_rim_lift 0" = before.
        if (hybridValue("clip_reject", 1f) > 0f) out.append("clipReject 1\n");
        if (hybridValue("rim_lift", 1f) > 0f) out.append("rimLift 1\n");
        // P339 (owner, 2026-10-25: «займись ускорением обработки чтобы скорость была на уровне WhyCam»): the worker leaves the
        // ultrashort frames out when the base has no site at the clip next to another (worker usNeedClip): OPPO replays of the
        // owner's night / telephoto bursts 5.29 -> 3.15 s / 4.85 -> 3.00 s, the result the same. scam_dev "hybrid_us_need_clip 0".
        if (hybridValue("us_need_clip", 1f) > 0f) out.append("usNeedClip 1\n");
        final float alias = clamp01(hybridValue("base_demosaic_alias", 1f));
        if (alias > 0f) out.append("baseDemosaicAlias ").append(alias).append('\n');
        final Float hand = scamDevValue("hybrid_plain_chroma_hand");
        if (hand != null) out.append("plainChromaHand ").append(clamp01(hand)).append('\n');
        // P118 «Sabre 2× только когда есть смысл» (on by default; the worker keeps it off for replays)
        if (hybridSwitch("auto2x", true)) out.append("auto2x 1\n");
        final int shasta = getHybridShastaMode();
        if (shasta == 0) out.append("shastaEnable 0\n");
        else if (shasta == 2) out.append("shastaForce 1\n");
        // Rejection boost where the local motion varies (v11 Z channel of the F6 field); a switch, not a number.
        Float boostDev = scamDevValue("hybrid_motion_boost");
        out.append("boostEnable ").append(boostDev != null ? boostDev : hybridSwitch("motion_boost", true) ? 1f : 0f).append('\n');
        // Switches of the worker's round-5 fixes (on by default there): written only when turned off.
        // P394 (owner, 2026-10-29, vivo X200 Ultra hand-held: «зубчатые артефакты» along a ship's window frame, a crane and a lamp's
        // rim against a clipped light; P150 had seen the same dotted edges on a still burst): the whole-cell clip left the same Bayer
        // phase of the dark side next to every clip out of every frame. Off by default, the merge clips per site (the magenta rim
        // it once removed: host 0.41 -> 0.44 % of the clip band on a green-clipped sky, 52 -> 36 px on the lamp); on writes nothing
        // (the worker's own default, the whole cell).
        if (!hybridSwitch("cell_clip", false)) out.append("cellClip 0\n");
        if (!hybridSwitch("chroma_diff_clamp", true)) out.append("chromaDiffClamp 0\n");
        if (!hybridSwitch("bento_scam", true)) out.append("bentoScam 0\n");
        // P310 (owner, the LED fans and bar of a PC: «куча артефактов на подсветке», «проблема с маской бенто, ... исправлять
        // переходы»): the ultrashort frames' RGB blended after the merge by region (worker HybridTuning::bentoBlend, plain Bayer;
        // worker default 0 = replays unchanged), its softness from «Кадры светов: мягкий переход» (bento_ramp in 2x2 cells: sigma
        // ramp / 4, at least 0.25 - 0 the sharpest). scam_dev.txt "hybrid_bento_blend 0" = the P309 mixing inside the merge.
        if (hybridSwitch("bento_blend", true))
            out.append("bentoBlend 1\nbentoBlendSigma ").append(Math.max(0.25f, 0.25f * hybridValue("bento_ramp", HYBRID_DEFAULTS.get("bento_ramp")))).append('\n');
        // P350 (owner, 2026-10-27: «Займись самой маской коротких кадров, нам нужно улучшить её максимально, сделать её максимально
        // точной»): the ultrashort mask set measured on a truth harness (17 synthetic scenes: sunset, window, desk lamp, PWM LEDs,
        // moving lights, glitter, night lamps, coloured lights) and real bursts. The ultrashort frames registered to the base to
        // sub-pixel (they arrived 1.2 - 1.7 px off: halos, steps and colour edge pairs at every light) and F6-aligned, the spread
        // gated by the pixel's own brightness (no ultrashort on dark rails / text next to a light), motion refused per region
        // instead of the whole frame with a noise-normalised validity, the cell weight from a denoised level, radii in sensor px,
        // a +-10 % luma level match and half the PWM colour gain without a measured flicker. Harness means: core error 26.7 ->
        // 4.7, rim 39 -> 8, halo 31 -> 8, step 25 -> 8, dark-detail damage 61 -> 26, ultrashort used on 17 of 17 scenes (11
        // before); moving lights ghost more (unclipped ghost share 2.5 -> 6 %). Merge +0.2 - 0.3 s at 12 MP. scam_dev.txt
        // "hybrid_us_set 0" = before (the worker's defaults keep replays bit-identical).
        if (hybridValue("us_set", 1f) > 0f)
            out.append("bentoUsRegister 2\nlaUltrashort 1\nbentoBlendGateOwnLo 0.3\nbentoBlendGateOwnHi 0.6\nbentoRefuseScope 1\n")
                    .append("bentoMotionMax 25\nbentoValidZ 3\nbentoValidZRange 1\nbentoBlendGainMin 0.5\nbentoBlendGainLevel 0.1\n")
                    .append("bentoBlendCellMode 2\nbentoBlendScale 2\n");
        // P358 (owner, 2026-10-27: «Перепроверить и улучшить реджект карты сверхкороткого и длинного кадров»): the long (bracketed)
        // frames' noise model measured against the N frames' on flat blocks (their header model said 0.30 - 0.57 of N, measured
        // 0.61 - 0.90: the rejection read their noise as motion) and their weight from that noise at level 0.005 instead of
        // t^2 read_b / read_f (lamp: A 3.32 -> 1.48, the optimum 1.46; X7U long shutter 4.00 -> 2.63). OPPO replays: flat noise -1 to
        // -4 %, detail -0.1 % or less, no ghosts. scam_dev.txt "hybrid_long_set 0" = before.
        if (hybridValue("long_set", 1f) > 0f) out.append("noiseRoles 1\nshastaNoiseWeight 0.005\n");
        // P415 (owner, 2026-10-30, OPPO Find X8 Ultra: colour clumps in the shadows «только с shasta кадрами»): the reject's noise
        // below 0.002 - 0.004 of white with the read term the deep shadows measured (the single factor, measured above 0.002, put it
        // 2 - 4 x low: the long frames refused in random 1 - 2 cell spots). Replay of the owner's burst: shadow noise -33 %, its
        // spread 0.40 -> 0.28; the vivo hand ghost at 0.0045 unchanged. scam_dev.txt "hybrid_dark_read 0" = before.
        if (hybridValue("dark_read", 1f) > 0f) out.append("noiseDarkRead 1\n");
        // P392 (owner, 2026-10-28: «Без отказа целиком. Отсекать только пятна и ячейки, где есть движение ... Замену выбирать по
        // пикселю ... Без швов ... Без ложного движения»): after the P350 set (later lines win in the worker). The ultrashort
        // frames refused per moved region (hysteresis on the noise-normalised check, closed and grown; alias damping of its colour
        // part), the validity smoothed along the light's contour (guided filter), and per pixel the valid frames, N where N holds
        // data, inside the N clip a plausible frame that moved, else a fill pulled from the valid light around (never a dark
        // object pasted into a light, never the white clip within ~96 px of the light). scam_dev.txt "hybrid_us_partial 0" = before.
        if (hybridValue("us_partial", 1f) > 0f) out.append("bentoPartial 1\nbentoAliasDamp 1\n");
        // P441 (owner, 2026-11-01: «ступеньки на гранях», «попробуй улучшить карту бенто чтобы таких зубцов не было»): the ultrashort
        // colour smoothed along the local edge (a single Bayer frame's 2 px comb along a wire across a sunlit panel), its F6 offset
        // bilinear between the 2x2 cells and its clip mark per pixel (both stepped every 2 px along an edge through the light).
        // OPPO replays: the comb along the wire mostly gone, the purple fringe 887 -> 920 px, the lamp ring's dots gone.
        // scam_dev.txt "hybrid_us_edges 0" = before (the worker's defaults keep replays bit-identical).
        if (hybridValue("us_edges", 1f) > 0f) out.append("bentoBlendUsBox 1\nbentoBlendFieldBilinear 1\nbentoBlendPixelMark 1\n");
        if (hybridValue("us_cache", 1f) > 0f) out.append("bentoUsCache 1\n");
        if (hybridValue("bayer_split", 1f) > 0f) out.append("bayerSplit 1\n");
        out.append("bayerQuads ").append(hybridValue("bayer_quads", 1f) > 0f ? 1 : 0).append('\n');
        out.append("texMaps ").append(Math.max(0, Math.min(31, Math.round(hybridValue("tex_maps", 19f))))).append('\n'); // P469
        // P392: a long frame whose moved share is above «Длинные кадры: отказ при движении» (up to 60 %) is kept and only its moved
        // blocks are left out (dilated, feathered) instead of the whole frame (the owner's burst: all three long frames dropped for a
        // moving hand, 20 % of the blocks). scam_dev.txt "hybrid_long_scope 0" = the whole frame dropped, as before.
        // P414 (owner, 2026-10-30: «обновить карту реджекта для шаста кадров», the long frames now up to 1/60 s and +2 EV for deep
        // shadows, P409): the moved blocks leave every kept long frame, also one at or below «отказ при движении» (it merged whole,
        // its moved blocks too: the window burst's long frames, 2.8 % moved - branches behind the glass); a lone moved block that
        // only the noise decided stays in (a static dark ceiling / wall was cut out in spots: 137 of the window burst's 150 dark
        // moved blocks). Replays: flat noise in the shadows -1 .. -4 % on the hand bursts, the hands as before (no ghost), the
        // window burst's noise unchanged. Not taken (measured): a detail test against motion blur (0.2 - 0.3 % more blocks) and the
        // noise / level from the burst instead of the models (it lost 50 - 80 % of a moving hand's blocks).
        if (hybridValue("long_scope", 1f) > 0f) out.append("shastaMotionScope 1\nshastaScopeAll 1\nshastaScopeLone 1\n");
        // P313 (owner, vivo X200 Ultra 2x ISZ in daylight: «все фото в области светов очень мыльное и с кучей артефактов»): the
        // native mosaic merge (Quad / Tetra) leaves Bento's cells to the N frames and fills only what they clipped from the
        // ultrashort frames, in a pass after it (worker HybridTuning::mosaicUsFill; default 0 = replays unchanged). scam_dev.txt
        // "hybrid_us_fill 0" = the ultrashort frames inside the native merge by Bento's mask, as before.
        if (hybridSwitch("us_fill", true)) out.append("mosaicUsFill 1\n");
        if (!hybridSwitch("mosaic_window_full", true)) out.append("mosaicWindowFull 0\n");
        // P366 (owner, 2026-10-27, vivo X200 Ultra ISZ at sea at noon: «вот такие фото получаю с исз, исправь» - pink blotches in
        // the white foam): the worker holds the lamp-flicker corrections of the ultrashort frames from the base frame's EV100
        // usDaylightEv up (the burst's usSceneEv, ScamHybridBurst) and holds a tile field whose deviating tiles disagree with their
        // neighbours (usIncoherent). Its defaults (ScamHybridBurst.US_DAYLIGHT_EV / US_INCOHERENT) are not written; scam_dev.txt
        // "hybrid_us_daylight_ev 0" / "hybrid_us_incoherent 0" = off (the lamp-flicker corrections as before P366).
        final float daylightEv = hybridValue("us_daylight_ev", com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.US_DAYLIGHT_EV);
        final float incoherent = hybridValue("us_incoherent", com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.US_INCOHERENT);
        if (daylightEv != com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.US_DAYLIGHT_EV)
            out.append("usDaylightEv ").append(Math.max(0f, daylightEv)).append('\n');
        if (incoherent != com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.US_INCOHERENT)
            out.append("usIncoherent ").append(Math.max(0f, incoherent)).append('\n');
        if (MotionMode.sharpPriority()) out.append("widenBelow 0\nbaseDemosaicAniso 1\n");
        // P488 (owner, 2026-11-03: the reference app's Sport merge, SPORT-COMPARISON §A3 / §A4, items 3 and 6): in «Спорт» the guarded
        // rejection post-filter at its Sport threshold (worker rejPixDiff 0.35), the donor gate on the homography shift (donorShiftMax,
        // 120 RAW px = its 15 thumbnail px at the fast merge's 1/8 scale) and in the fast merge its Sport overrides (fastMergeSport 3:
        // boost 10, pixel difference 0.35, the 15 px / 0.75 donor gate). Photo writes none of them (scam_dev.txt "hybrid_rej_pix_diff",
        // "hybrid_donor_shift_max", "hybrid_fast_merge_sport": 0 = off, a value = on in photo too).
        // The post-filter stays off in «Спорт» too (replays on 5 bursts: detail +0.2..4.8 %, ghosting +0.1..3 % in the rejected area).
        final boolean sport = isMotionMode();
        final float pixDiff = hybridValue("rej_pix_diff", 0f), shiftMax = hybridValue("donor_shift_max", sport ? 120f : 0f);
        final int fastSport = Math.round(hybridValue("fast_merge_sport", sport ? 3f : 0f));
        if (pixDiff > 0f) out.append("rejPixDiff ").append(Math.min(1f, pixDiff)).append('\n');
        if (shiftMax > 0f) out.append("donorShiftMax ").append(shiftMax).append('\n');
        if (fastSport > 0 && fastProcessing()) out.append("fastMergeSport ").append(fastSport & 3).append('\n');
        if (fastProcessing()) out.append(FAST_TUNING); // P474: last, over the lines above
        // P600: «Все шумодавы» off - the worker's own denoise stages off too ("denoise 0", applied after every line); on: no line
        out.append(com.scamera.app.processing.opengl.postpipeline.DenoiseStages.workerTuning());
        return out.toString();
    }

    /**
     * P474 (owner, 2026-11-03: «Быстрый режим будет по умолчанию, а режим "качества" как второй вариант в настройках»): the merge
     * without the P344 rejection options and with the F6 field on a 32 RAW px grid without its L0 iterations - OPPO PHY110 detail30
     * worker 1829 -> 1300-1436 ms (warm); on motion a little more noise or a little less sharpness than «Качество». fastMerge 1: the
     * per-frame fast merge route (scam-fast-merge.h; detail30 worker 1906 -> 980 ms in a replay); a burst it does not take keeps the
     * lighter SCAM merge of the lines before it.
     */
    public static final String FAST_TUNING = "rejPool 0\nrejVarExact 0\nrejMatchBlur 0\nrejPerChannel 0\nrejRelFloor 0\nrejMeanDiff 0\n"
            + "rejMeanFloor 0\nrejKneeLo 0\nrejKneeHi 0\nlaStride 16\nlaIters 0\nfastMerge 1\n";

    /**
     * P614 (owner, 2026-11-11): «Why тон» (pref_scam_hybrid_tone_version) on the main settings page - "v1" (default, our tone as it
     * was) or "v2" (the reference tone ported one to one: WhyV2Stats / WhyV2Fusion / WhyV2Tone).
     */
    public static boolean whyToneV2() { return "v2".equals(hybridString("tone_version", "v1")); }
    /**
     * P474: «Режим обработки» (pref_scam_hybrid_processing): "fast" (default) or "quality" (the full merge). P627 (owner,
     * 2026-11-11): every mode stays the mode chosen - no route hands a shot to another (moving frames, Quad / Tetra / DCG, GPU
     * failures and limits are handled inside each). P644: «Статика» ("static") is gone - a stored one becomes "quality" once
     * (SettingsMigration.removeObsolete). Any other stored value reads as "fast".
     */
    public static String processingMode() {
        final String v = hybridString("processing", "fast");
        return "quality".equals(v) ? v : "fast";
    }
    /** P474: «Быстрый». */
    public static boolean fastProcessing() { return "fast".equals(processingMode()); }
    /**
     * P195 (owner, 2026-10-17: «добавь так же возможность отключать наш 2х2 и 4х4 ремозаик чтобы получать фото без него»):
     * pref_scam_hybrid_mosaic_remosaic, the top row of «Мозаика без ремозаика»; on (default) = our remosaic of Quad / Tetra streams
     * as before; off = no remosaic (P267, owner 2026-10-19: «При выкл сохраняй только днг без ремозаика»; «Склейка должны быть,
     * просто без ремозаика»): such a shot is merged and saved as its merged mosaic DNG alone (the sensor's layout), for Scam
     * Remosaic or another developer (ScamHybridBurst.dngMosaic). scam_dev.txt "hybrid_mosaic_remosaic 0/1".
     */
    public static boolean isHybridMosaicRemosaic() { return hybridSwitch("mosaic_remosaic", true); }
    /** The hybrid's N frames from the ZSL ring (pref_scam_hybrid_zsl_frames, 4..65, default 20; P578: was 44). */
    public static int getHybridZslFrames() {
        // P643: «Oldstyle» takes the photo mode's capture; its row «Кадров в серии» (0: the photo mode's count) is the one number
        // of the capture the mode still sets - here, where the photo mode's count is read, within the same 4 .. 65
        final int oldstyle = OldstyleMode.on() && !isMotionMode() ? Math.round(hybridValue("oldstyle_burst_frames", 0f)) : 0;
        if (oldstyle > 0) return Math.max(4, Math.min(65, oldstyle));
        return Math.round(hybridValue("zsl_frames", 20f));
    }
    /**
     * P190 (owner, 2026-10-17: «дай возможность ставить столько кадров для 50/200мп сколько я хочу»): frames of a FULLRES 50 MP
     * shot, pref_scam_hybrid_fullres50_frames 0..65 (P203: default 12 N; P578: was 64); 0 = auto (as {@link #getHybridZslFrames}, «Движение»
     * included). An explicit count applies in «Движение» too. Clamped here as well: a scam_dev.txt override is not.
     */
    public static int getFullRes50Frames() { return Math.max(0, Math.min(65, Math.round(hybridValue("fullres50_frames", 12f)))); }
    /**
     * P202 (owner, 2026-10-17: «давай дадим выбор ZSL или PSL»): a FULLRES 50 MP shot takes its N from the ZSL ring (on, P198) or
     * the whole series after the shutter (off); pref_scam_hybrid_fullres50_zsl, default on. scam_dev.txt fullres_zsl still wins.
     */
    public static boolean isFullRes50Zsl() { return scamDevSwitch("fullres_zsl", hybridSwitch("fullres50_zsl", true)); }
    /** P190: frames of a FULLRES 200 MP series (pref_scam_hybrid_fullres200_frames 3..64, default 4: 3 N + 1 ultrashort). */
    public static int getFullRes200Frames() { return Math.max(3, Math.min(64, Math.round(hybridValue("fullres200_frames", 4f)))); }
    /** P62 Mochi: v11 photometric merge of bracketed frames on GPU (pref_scam_hybrid_mochi: 0 off, 1 auto, 2 force; default 2, P68). */
    public static int getHybridMochi() { return Math.round(hybridValue("mochi", 2f)); }
    /**
     * P68 Shasta: 0 off (switch pref_scam_hybrid_shasta), else pref_scam_hybrid_shasta_mode 1 auto (the worker's sharpness gate and
     * ratio limit) or 2 always (default: the frames skip both and fall back only on motion, pref_scam_hybrid_shasta_motion_max).
     */
    public static int getHybridShastaMode() {
        if (isMotionMode()) return 0;
        if (!hybridSwitch("shasta", true)) return 0;
        return Math.max(1, Math.min(2, Math.round(hybridValue("shasta_mode", 2f))));
    }
    /**
     * SCAM Hybrid tone: the WhyCam 1.23 / SCAM 9.6 photo tone (WhyStats -> WhyFusion -> WhyCombine: Smart-HDR AE, exposure
     * fusion on a guided filter, OKLab grading, AgX Custom, detail of the Sabre merge) instead of the SCAMERA exposure,
     * Exposure Fusion and AgX render. pref_scam_hybrid_why_tone, on by default; scam_dev.txt "hybrid_why_tone 0/1".
     * Its knobs are pref_scam_hybrid_why_&lt;key&gt; (read with {@link #hybridValue}, defaults = WhyCam 2.85 X8U).
     */
    /**
     * Master switch of every noise reduction of the photo (pref_scam_hybrid_denoise «Все шумодавы», on by default; scam_dev.txt
     * "hybrid_denoise 0/1"). P600: every stage whose purpose is noise removal asks it - the registry
     * {@link com.scamera.app.processing.opengl.postpipeline.DenoiseStages} (DenoiseStagesTest keeps a new stage from
     * bypassing it). Off skips, on every route (Bayer, Quad / Tetra, the fast and the quality merge, 12 / 50 / 200 MP): the
     * Resolve denoise after the merge (ScamHdrDenoise), the pyramid denoise with its corner chroma cleanup (WhyMsDenoise), the
     * highlight frames' denoise of the WHY fusion (bracket_dn), and in the worker ("denoise 0") the plain Bayer chroma median and the
     * chroma smoothing of the ultrashort colour; the chroma denoise of lifted shadows is 0 on every route. Left as they are: the merge
     * (alignment, rejection, frame weights, the kernel, the base demosaic), the Quad / Tetra lattice and moire passes, hot pixels,
     * CA / fringes, highlights, colour, tone and sharpening (the shot's SNR is still measured for it).
     */
    public static boolean isHybridDenoiseEnabled() { return hybridSwitch("denoise", true); }
    /** Normal-exposure N frames taken from the ZSL ring: SCAM HDR 4..50; on a hybrid shot the hybrid's own count. */
    public static int getScamZslFrames() { return Math.round(routeInternalValue("zsl_frames", 4f)); }

    private static long scamDevStamp = -1, scamDevChecked;
    private static java.util.Map<String, Float> scamDevValues = java.util.Collections.emptyMap();
    /**
     * Developer overrides for the SCAM internal values: "key value" lines in Download/SCAMERA/scam_dev.txt (P191;
     * ScamDevFile) (key without the pref_scamhdr_ prefix) replace the preference for the next shot. Re-read when the file
     * changes, at most every 2 s.
     */
    /** Developer switch from scam_dev.txt ("key 0" = off); {@code fallback} without the line. */
    public static boolean scamDevSwitch(String key, boolean fallback) {
        Float v = scamDevValue(key);
        return v == null ? fallback : v != 0f;
    }

    /** True when scam_dev.txt sets this line (an A/B override that wins over the stored setting). */
    public static boolean scamDevOverrides(String key) { return scamDevValue(key) != null; }

    /** The number of a scam_dev.txt line ("key value"); {@code fallback} without the line. */
    public static float scamDevNumber(String key, float fallback) {
        Float v = scamDevValue(key);
        return v == null ? fallback : v;
    }

    /**
     * P277 lab (owner, 2026-10-21: «проверь что все настройки рабочие»): while ScamLabRun renders a side, the scam_dev.txt lines
     * read as this map (the shot's lines, without the lines of the settings compared, with the side's own developer lines)
     * instead of the file, as PreferenceValue.overlay does for the preferences: a line of the compared setting no longer gives
     * A = B, and side A has the shot's lines, not today's file. Null: the file. Only the render's own threads read it
     * (PreferenceValue.inScope, audit 2026-10-27): a shot taken meanwhile reads the file.
     */
    private static volatile java.util.Map<String, Float> labDev;

    public static void labDevOverlay(java.util.Map<String, Float> lines) {
        if (lines != null) PreferenceValue.enterScope();
        labDev = lines == null ? null : java.util.Collections.unmodifiableMap(new HashMap<>(lines));
    }

    /** The scam_dev.txt lines of the file now (a copy; never the lab's overlay): what a shot records for the lab. */
    public static java.util.Map<String, Float> scamDevLines() {
        refreshScamDev();
        return new HashMap<>(scamDevValues);
    }

    private static Float scamDevValue(String key) {
        final java.util.Map<String, Float> lab = labDev;
        if (lab != null && PreferenceValue.inScope()) return lab.get(key);
        refreshScamDevInBackground();
        return scamDevValues.get(key);
    }

    /**
     * P319 (user report: «Лайв-вью работает гораздо тормознее»): the viewfinder's GL thread and the camera thread read dev
     * keys on every frame, and every 2 s one of those reads did the file / folder-grant I/O itself (tens of ms through SAF,
     * P303) - a hitch in the viewfinder. The first read stays synchronous (the values from the start); after that a stale
     * map is read again on a thread of its own while the callers keep the current one (a changed line still takes ~2 s).
     */
    private static final java.util.concurrent.atomic.AtomicBoolean scamDevLoading = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.Executor SCAM_DEV_IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scam-dev-io");
        t.setDaemon(true);
        return t;
    });

    private static void refreshScamDevInBackground() {
        if (scamDevStamp == -1) {
            refreshScamDev();
            return;
        }
        if (System.nanoTime() - scamDevChecked <= 2_000_000_000L || !scamDevLoading.compareAndSet(false, true)) return;
        try {
            SCAM_DEV_IO.execute(() -> {
                try {
                    refreshScamDev();
                } finally {
                    scamDevLoading.set(false);
                }
            });
        } catch (RuntimeException e) {
            scamDevLoading.set(false);
        }
    }

    private static synchronized void refreshScamDev() { // P319: the background refresh and a first read never interleave
        long now = System.nanoTime();
        if (scamDevStamp == -1 || now - scamDevChecked > 2_000_000_000L) {
            scamDevChecked = now;
            java.util.Map<String, Float> values = java.util.Collections.emptyMap();
            long stamp = 0;
            try {
                // P191: Download/SCAMERA/scam_dev.txt (ScamDevFile); the stamp skips an unchanged file of the File API, a file
                // only the folder grant reaches (the owner's copy from a PC or a file manager) is read again every 2 s
                android.content.Context context = com.scamera.app.app.ScameraApp.getAppContext();
                java.io.File file = context == null ? null : ScamDevFile.file(context);
                String text = null;
                if (file != null && file.isFile()) {
                    stamp = file.lastModified();
                    if (stamp == scamDevStamp) return;
                    try {
                        text = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (java.io.IOException | SecurityException denied) {
                        // P303: listed but not the app's own (EACCES): the folder grant, as ScamDevFile.read
                        text = ScamDevFile.readGranted(context);
                        stamp = 0;
                    }
                } else if (context != null) {
                    text = ScamDevFile.readGranted(context);
                }
                if (text != null) {
                    values = new java.util.HashMap<>();
                    for (String line : text.split("\r?\n")) {
                        String[] parts = line.trim().split("\\s+");
                        // P319: a line written before the Why rename keeps working under its new key (a new line wins)
                        if (parts.length == 2) try {
                            final String k = BrandMigration.why(parts[0]);
                            if (k.equals(parts[0]) || !values.containsKey(k)) values.put(k, Float.parseFloat(parts[1]));
                        } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (RuntimeException ignored) { }
            scamDevStamp = stamp;
            scamDevValues = values;
        }
    }

    /**
     * SCAM Hybrid number pref_scam_hybrid_&lt;key&gt;, clamped by its SettingsNumericRules bounds; scam_dev.txt
     * "hybrid_&lt;key&gt; v" overrides it; unset or invalid: the hybrid default of the key, else {@code fallback}.
     * Independent of the shot profile and of SCAM HDR.
     */
    private static float clamp01(float v) { return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v)); }

    public static float hybridValue(String key, float fallback) {
        if (noHdrFrames() && ("bento".equals(key) || "shasta".equals(key))) return 0f;
        Float override = scamDevValue("hybrid_" + key);
        if (override != null) return override;
        // P86: in the «Движение» mode its own value of the key (pref_scam_hybrid_mm_<key>, the mode's default)
        if (MotionMode.replaces(key) && isMotionMode()) return hybridStored(MotionMode.PREFIX + key, MotionMode.DEFAULTS.get(key));
        return hybridStored(key, fallback);
    }
    /** SCAM Hybrid switch pref_scam_hybrid_&lt;key&gt; (Boolean or "0"/"1"); scam_dev.txt "hybrid_&lt;key&gt; 0/1" overrides it. */
    public static boolean hybridSwitch(String key, boolean fallback) {
        if (noHdrFrames() && ("bento".equals(key) || "shasta".equals(key))) return false;
        Float override = scamDevValue("hybrid_" + key);
        if (override != null) return override > 0f;
        if (MotionMode.replaces(key) && isMotionMode()) return hybridStoredSwitch(MotionMode.PREFIX + key, MotionMode.DEFAULTS.get(key) > 0f);
        return hybridStoredSwitch(key, fallback);
    }

    /** P356: ETTR (pref_ettr; the shade's «ETTR»): the capture exposed to the right (capture/Ettr). */
    public static boolean isEttr() {
        try {
            // scam_dev.txt "ettr": the A/B override of the switch
            return scamDevSwitch("ettr", settings != null && settings.getBoolean(SCOPE_GLOBAL, "pref_ettr", false));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** P86: the «Движение» photo mode (global switch beside the settings gear). */
    /** P494 (owner, 2026-11-03: the shade's HDR tile «в положении выкл он должен полностью отключать длинные и сверхкороткие кадры»). */
    public static final String HDR_FRAMES_KEY = "pref_hdr_frames";

    /** P494: the shade's «HDR» (default on): off, no long (Shasta) and no ultrashort (Bento) frames are taken or merged. */
    public static boolean hdrFrames() {
        try {
            return scamDevSwitch("hdr_frames", settings == null || settings.getBoolean(SCOPE_GLOBAL, HDR_FRAMES_KEY, true));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * P494: no long and no ultrashort frames - «Спорт» or the HDR tile off. P643: or «Oldstyle» - its series is one exposure
     * (owner, P630e: «capture like the originals»), now the photo mode's own N frames with the photo mode's bracketed frames off,
     * decided here where the photo mode decides it (the plan, the worker's tuning and the frame counts all read this).
     */
    public static boolean noHdrFrames() { return isMotionMode() || !hdrFrames() || OldstyleMode.on(); }

    public static boolean isMotionMode() {
        Boolean shot = MotionMode.shotOn();
        if (shot != null) return shot;
        try {
            return settings != null && settings.getBoolean(SCOPE_GLOBAL, MotionMode.PREF, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean isPortraitEnabled() { return settings != null && settings.getBoolean(SCOPE_GLOBAL, "pref_portrait_enabled", false); }

    public static boolean isPortraitSkinWarmthEnabled() { return isPortraitEnabled() && settings.getBoolean(SCOPE_GLOBAL, "pref_portrait_skin_warmth", true); }

    /** P103: «Яркий видоискатель в темноте» (global). */
    public static boolean isLowLightBoost() {
        try {
            return settings != null && settings.getBoolean(SCOPE_GLOBAL,
                    com.scamera.app.capture.HiddenFeatures.PREF_LOW_LIGHT_BOOST, false);
        } catch (RuntimeException e) {
            return false;
        }
    }
    public static void setMotionMode(boolean value) { settings.set(SCOPE_GLOBAL, MotionMode.PREF, value); }
    /**
     * scam_dev.txt overrides of hybrid list settings for A/B without the menu: "hybrid_&lt;key&gt; i" picks the i-th entry value
     * (order of the list), e.g. "hybrid_dn_strength_map 2" = uniform. Other list keys have their own getters (output,
     * downsampler) or none.
     */
    private static final Map<String, String[]> HYBRID_DEV_LISTS = new HashMap<>();
    static {
        HYBRID_DEV_LISTS.put("dn_strength_map", new String[]{"auto", "frames", "uniform"});
        // P493: «Подавление аберраций» for an A/B: scam_dev.txt "hybrid_fringe_method 0" = the fringe net, "1" = P399
        HYBRID_DEV_LISTS.put("fringe_method", new String[]{"neural", "classic"});
        // P614: «Why тон» for an A/B: scam_dev.txt "hybrid_tone_version 0" = V1, "1" = V2
        HYBRID_DEV_LISTS.put("tone_version", new String[]{"v1", "v2"});
    }
    /** SCAM Hybrid text value pref_scam_hybrid_&lt;key&gt; (a ListPreference entry value), {@code fallback} when unset. */
    public static String hybridString(String key, String fallback) {
        final String[] devList = HYBRID_DEV_LISTS.get(key);
        if (devList != null) {
            Float dev = scamDevValue("hybrid_" + key);
            if (dev != null) {
                int i = Math.round(dev);
                if (i >= 0 && i < devList.length) return devList[i];
            }
        }
        try {
            String v = settings.getString(SCOPE_GLOBAL, HYBRID_PREFIX + key, fallback);
            return v == null || v.trim().isEmpty() ? fallback : v.trim();
        } catch (RuntimeException error) { return fallback; }
    }
    /**
     * SCAM Hybrid number list pref_scam_hybrid_&lt;key&gt; = "a,b,c,..." (e.g. a value per noise level), each value clamped by
     * {@link SettingsNumericRules#listBounds}; {@code fallback} when unset, of another length or not a number.
     */
    public static float[] hybridList(String key, float[] fallback) {
        final String full = HYBRID_PREFIX + key;
        String v;
        try { v = settings.getString(SCOPE_GLOBAL, full, ""); }
        catch (RuntimeException error) { v = ""; }
        return SettingsNumericRules.listValue(full, v, fallback);
    }
    /** Stores pref_scam_hybrid_&lt;key&gt;: a Boolean as a switch, anything else as text (quick-settings chips). */
    public static void setHybridValue(String key, Object value) {
        if (value instanceof Boolean) settings.set(SCOPE_GLOBAL, HYBRID_PREFIX + key, (boolean) (Boolean) value);
        else settings.set(SCOPE_GLOBAL, HYBRID_PREFIX + key, String.valueOf(value));
    }
    private static float hybridDefault(String key, float fallback) {
        Float v = HYBRID_DEFAULTS.get(key);
        return v != null ? v : fallback;
    }
    private static float hybridStored(String key, float fallback) {
        final float def = hybridDefault(key, fallback);
        final String full = HYBRID_PREFIX + key;
        try {
            String v = settings.getString(SCOPE_GLOBAL, full, "");
            return v == null || v.trim().isEmpty() ? def : (float) SettingsNumericRules.value(full, v.trim(), def);
        } catch (RuntimeException error) { return def; }
    }
    private static boolean hybridStoredSwitch(String key, boolean fallback) {
        final boolean def = hybridDefault(key, fallback ? 1f : 0f) > 0f;
        try { return settings.getBoolean(SCOPE_GLOBAL, HYBRID_PREFIX + key, def); }
        catch (RuntimeException error) { return def; }
    }
    /** scam_dev.txt override of a shared knob on a hybrid shot: "hybrid_&lt;key&gt;", else the plain "&lt;key&gt;" line. */
    private static Float hybridDevValue(String key) {
        Float override = scamDevValue("hybrid_" + key);
        return override != null ? override : scamDevValue(key);
    }
    /**
     * The key a SCAM HDR preference is read from for the current shot: on a hybrid shot its hybrid copy (see
     * {@link #hybridCopyKey}), otherwise the key itself. For nodes that read pref_scamold_* / pref_agx_scam_* directly.
     */
    public static String profileKey(String key) { return key != null && isHybridShot() ? hybridCopyKey(key) : key; }
    /**
     * The hybrid's copy of a SCAM HDR key: pref_scamhdr_hybrid_&lt;k&gt;, pref_scamhdr_&lt;k&gt;, pref_scamold_&lt;k&gt; -&gt;
     * pref_scam_hybrid_&lt;k&gt;; pref_agx_scam_&lt;k&gt; -&gt; pref_scam_hybrid_agx_&lt;k&gt;; pref_scamroute_&lt;k&gt; -&gt;
     * pref_scam_hybrid_hdr_&lt;k&gt;; any other key is returned unchanged. The settings migration uses the same mapping.
     */
    public static String hybridCopyKey(String key) { return ScamHybridKeys.copyKey(key); }

    /**
     * SCAM HDR internal value pref_scamhdr_&lt;key&gt;, clamped by SettingsNumericRules (scam_dev.txt "key v" overrides it).
     * On a hybrid shot the same call reads the hybrid's copy pref_scam_hybrid_&lt;key&gt; (scam_dev.txt "hybrid_&lt;key&gt;",
     * then "key") and never the SCAM HDR key; keys starting with "hybrid_" always name a hybrid setting.
     */
    public static float routeInternalValue(String key, float fallback) {
        if (key.startsWith("hybrid_")) return hybridValue(key.substring("hybrid_".length()), fallback);
        if (isHybridShot()) {
            Float override = hybridDevValue(key);
            return override != null ? override : hybridStored(key, fallback);
        }
        return scamInternalValue(key, fallback);
    }
    /** P421: a SCAM HDR shot's own value pref_scamhdr_&lt;key&gt; (its scam_dev override first), as routeInternalValue reads it there. */
    public static float scamHdrValue(String key, float fallback) { return scamInternalValue(key, fallback); }
    private static float scamInternalValue(String key, float fallback) {
        String fullKey="pref_scamhdr_"+key;
        Float override = scamDevValue(key);
        if (override != null) return override;
        try {
            return (float)SettingsNumericRules.value(fullKey,
                    settings.getString(SCOPE_GLOBAL,fullKey,String.valueOf(fallback)),fallback);
        } catch(RuntimeException error) { return fallback; }
    }

    /**
     * RAW stream format: auto | raw16 (RAW_SENSOR) | raw10 | raw12 — the camera must offer it, else auto order. P388: none stored
     * is the phone's factory value (RAW10 on the OPPO Find X8 / X7 Ultra).
     */
    public static String getRawStreamFormat() { return settings.getString(SCOPE_GLOBAL, "pref_raw_stream_format", DeviceDefaults.rawStreamDefault()); }

    /**
     * Highlight handling. Recovery merges from the unclipped channels of a partly
     * saturated quad instead of rejecting it; protection desaturates towards the
     * quad mean near the clip so the differing per-channel saturation points do
     * not tint blown areas.
     */
    /**
     * Replay the last shot's tone curve on the viewfinder. Shows the tonemapping
     * and shadow/highlight placement of the result; cannot show detail that needs
     * a burst.
     */

    /**
     * Signal-to-noise ratio the merged frame is aimed at. Denoise strength is
     * scaled by how far this shot falls short of it, so a clean frame is denoised
     * less than a noisy one without touching the sliders. The reference dumps show a
     * merged estimate around 110 on a well-lit shot.
     */

    private static final String[] SNR_BANDS = {"verylow", "low", "med", "high", "veryhigh"};
    // Defaults fall from heavy at low SNR to light at high SNR. Chroma stays
    // higher than luma throughout: colour noise survives frame averaging better
    // and is the more objectionable of the two.
    private static final float[] DEF_LOW    = {1.60f, 1.30f, 1.00f, 0.80f, 0.60f};
    private static final float[] DEF_HIGH   = {0.70f, 0.85f, 1.00f, 1.10f, 1.20f};
    private static final float[] DEF_CHROMA = {2.00f, 1.60f, 1.20f, 0.90f, 0.70f};

    /** Develop the preview RAW stream as the viewfinder instead of showing the ISP image. */
    /** P24: DNGs with lossless JPEG (LJ92, compression 7) inside; off by default until the owner's readers are checked. */
    public static boolean isDngLossless() { return settings.getBoolean(SCOPE_GLOBAL, "pref_dng_lossless", false); }

    /** P42: face detection mode (FaceDetectModes values). */
    public static String getFaceDetectMode() {
        return com.scamera.app.control.subject.FaceDetectModes.normalize(settings.getString(
                SCOPE_GLOBAL, com.scamera.app.control.subject.FaceDetectModes.KEY,
                com.scamera.app.control.subject.FaceDetectModes.DEFAULT));
    }

    /** P42: tracking autofocus gesture (SubjectPolicy TRACK_* values). */
    public static String getTrackingAfMode() {
        return com.scamera.app.control.subject.SubjectPolicy.normalizeTracking(settings.getString(
                SCOPE_GLOBAL, com.scamera.app.control.subject.SubjectPolicy.TRACKING_KEY,
                com.scamera.app.control.subject.SubjectPolicy.TRACK_DEFAULT));
    }

    /** P182: FULLRES 50 MP by the sensor's hardware remosaic instead of the own (VivoSdkFullRes.PREF_HW_REMOSAIC). */
    public static boolean isFullResHwRemosaic() { return settings.getBoolean(SCOPE_GLOBAL, "pref_fullres_hw_remosaic", false); }

    public static boolean isLiveViewfinderRawEnabled() {
        return DeviceDefaults.rawViewfinderSoc() && settings.getBoolean(
                SCOPE_GLOBAL, Key.KEY_LIVE_VIEWFINDER_RAW, false);
    }

    /**
     * Global pre-shutter RAW capacity, independent of mode, lens and merge count.
     */
    public static int getZslBufferCountValue() {
        return Math.max(1, Math.min(100,
                settings.getInteger(SCOPE_GLOBAL, Key.KEY_ZSL_BUFFER_COUNT, 50).intValue()));
    }

    public static int getAlignMethodValue() {
        return 1; // ESD4D produces Bayer RAW; the removed legacy RGB-layout mode is unsupported.
    }

    public static int getColorMethodValue() { return num(Key.KEY_COLOR_METHOD); }

    public static int getFocusPeakValue() { return num(Key.KEY_FOCUS_PEAK); }

    /** «Фильтр Байера»: -1 auto (the camera's CFA) or a forced 2x2 order 0..3; anything else (the removed MONO / QUAD) is auto. */
    public static int getCFAValue() {
        int v = num(Key.KEY_CFA);
        return v >= 0 && v <= 3 ? v : -1;
    }

    public static int getThemeValue() { return num(Key.KEY_THEME); }

    /** P191: Diagnostics «Сохранять RAW-серию»: every SHDR shot keeps its RAW burst in Download/SCAMERA. */
    public static boolean isKeepRawBurst() {
        try {
            return com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences()
                    .getBoolean("pref_keep_raw_burst", false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean isEisPhotoOn() { return flag(Key.KEY_EIS_PHOTO); }

    public static void setEisPhoto(boolean value) { store(Key.KEY_EIS_PHOTO, value); }

    public static int getFpsMode() { return num(Key.KEY_FPS_PREVIEW); }

    public static void setFpsMode(int value) { store(Key.KEY_FPS_PREVIEW, value); }

    public static boolean isUltraHdrOn() { return flag(Key.KEY_ULTRAHDR); }

    public static String getCameraID() { return settings.getString(Key.CAMERAS_PREFERENCE_FILE_NAME.mValue, Key.CAMERA_ID); }

    public static int getCountdownTimerIndex() { return num(Key.KEY_COUNTDOWN_TIMER); }

    public static void setCountdownTimerIndex(int valueMS) { store(Key.KEY_COUNTDOWN_TIMER, valueMS); }

    public static int getBracketingMode() { return num(Key.KEY_BRACKETING_MODE); }

    public static void setBracketingMode(int value) { store(Key.KEY_BRACKETING_MODE, value); }

    public static int getAeMeteringStd() { return num(Key.KEY_AE_METERING_STD); }

    public static void setAeMeteringStd(int value) { store(Key.KEY_AE_METERING_STD, value); }

    public static void setCameraID(String value) { settings.set(Key.CAMERAS_PREFERENCE_FILE_NAME.mValue, Key.CAMERA_ID, value); }

    public static int getAfMode() { return num(Key.KEY_AF_MODE); }

    public static int getAeMode() { return num(Key.KEY_AE_MODE); }

    public static void setAeMode(int value) { store(Key.KEY_AE_MODE, value); }

    public static int getCameraModeOrdinal() { return num(Key.CAMERA_MODE); }

    public static void setCameraModeOrdinal(int value) { store(Key.CAMERA_MODE, value); }

    public static String getToneMap() { return settings.getString(SCOPE_GLOBAL, Key.TONEMAP); }

    public static String getPref(Key key) { return settings.getString(SCOPE_GLOBAL, key); }

    public static Set<String> getStringSet(Key key) { return settings.getStringSet(SCOPE_GLOBAL, key, new HashSet(0)); }

    public static boolean getBool(Key key) { return settings.getBoolean(SCOPE_GLOBAL, key); }

    public static float getFloat(Key key) { return settings.getFloat(SCOPE_GLOBAL, key).floatValue(); }

    public static String getVideoResolution() { return settings.getString(SCOPE_GLOBAL, Key.KEY_VIDEO_RESOLUTION, "1920x1080"); }

    /**
     * The settings keys this class reads. The text is the stored key itself (users' settings and backups are keyed by it);
     * before, each came from a string resource of the same text, read through the app's context when the enum loaded.
     */
    public enum Key {
        KEY_PREF_VERSION("_pref_version"),
        KEY_SAVE_PER_LENS_SETTINGS("pref_save_per_lens_settings"),
        KEY_SHOW_WATERMARK("pref_show_watermark_key"),
        KEY_SHOW_ROUND_EDGE("pref_show_roundedge_key"),
        KEY_SHOW_GRID("pref_show_grid_key"),
        KEY_CAMERA_SOUNDS("pref_camera_sounds_key"),
        KEY_TIMER_SOUND("pref_timer_sound_key"),
        KEY_LIVE_VIEWFINDER_RAW("pref_live_viewfinder_raw_key"),
        KEY_WIDE169("pref_wide169_key"),
        KEY_ZSL_BUFFER_COUNT("pref_zsl_buffer_count_key"),
        KEY_COLOR_METHOD("pref_color_method_key"),
        KEY_FOCUS_PEAK("pref_peak_method_key"),
        KEY_THEME("pref_theme_key"),
        KEY_THEME_ACCENT("pref_theme_accent_key"),
        KEY_HIDE_GALLERY_ICON("pref_hide_gallery_icon_key"),
        KEY_AF_MODE("pref_af_mode_key"),
        KEY_AE_MODE("pref_ae_mode_key"),
        KEY_AE_METERING_STD("pref_ae_metering_std_mode_key"),
        KEY_BRACKETING_MODE("pref_bracketing_mode_key"),
        KEY_COUNTDOWN_TIMER("pref_countdown_timer_key"),
        KEY_VIDEO_RESOLUTION("pref_video_resolution_key"),
        KEY_SHOW_AF_DATA("pref_show_afdata_key"),
        KEY_SHOW_HORIZON("pref_horizon"),
        KEY_SAVE_RAW("pref_save_raw_key"),
        KEY_CFA("pref_cfa_key"),
        KEY_REMOSAIC("pref_remosaic_key"),
        KEY_EIS_PHOTO("pref_eis_photo_key"),
        KEY_FPS_PREVIEW("pref_fps_preview_key"),
        KEY_ULTRAHDR("pref_ultrahdr_key"),
        CAMERA_ID("camera_id"),
        TONEMAP("tonemap_key"),
        GAMMA("gamma_key"),
        CAMERA_MODE("pref_camera_mode_key"),
        /** Scope names: the files {@code <package>_cameras}, {@code _devices}, {@code _per_lens}. */
        CAMERAS_PREFERENCE_FILE_NAME("_cameras"),
        ALL_CAMERA_IDS_KEY("all_camera_ids"),
        FRONT_IDS_KEY("front_camera_ids"),
        BACK_IDS_KEY("back_camera_ids"),
        ALL_CAMERA_LENS_KEY("all_camera_lens"),
        CAMERA_COUNT_KEY("all_camera_count"),
        DEVICES_PREFERENCE_FILE_NAME("_devices"),
        PER_LENS_FILE_NAME("_per_lens"),
        FOLDERS_LIST("pref_folders_list");

        public final String mValue;

        Key(String stored) {
            mValue = stored;
        }
    }
}
