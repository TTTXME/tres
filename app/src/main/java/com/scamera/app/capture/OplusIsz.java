package com.scamera.app.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import com.scamera.app.settings.ModuleRegistry;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import java.util.Locale;

/**
 * P586 (owner, 2026-11-09: «пойми как на нём активировать фуллрез, ISZ без ремозаика и если найдёшь DCG»): the OnePlus 16
 * (PYB110) ISZ without the HAL's remosaic. Its CameraHWConfiguration.config [OemCamSeamlessSettingMap] (SCAM TEST 2026-11-09,
 * lines 23039-23112 / 23190-23195) names the in-sensor zoom rows: main pokemonmain[0x0018] = 11 «izoom qbc» (4080x3072 @ 30, the
 * centre of the half-size Quad, 2x) beside [0x0008] = 29 «izoom hwRemosaic»; tele pokemontele[0x0018] = 11 «izoom qbcs» (4096x3072
 * @ 30). enableSensorRemosaic and enableMultiPassISPRemosaic are FALSE (lines 25856, 25877): a QBC row is the sensor's own Quad. The
 * route is a module of its own on each camera - a copy of the main / tele module at twice its zoom, its sensor-mode row 11 picked
 * (SensorModePick: com.oplus.engineercamera.agingtest.mode.select on the session and every request), a sensor crop (no digital
 * crop of its frame again; IszCapturePolicy takes it as ISZ) - made once a camera ({@link #ensure}); its session has no ISP
 * stream whatever «Поток ISP» (SensorModePick.ispStreamOff). Every session proves itself: the mode its results report
 * ({@link #watchSession}: 11, else after 15 results the row fails) and the colour block of its frames (MosaicStream: a plain Bayer
 * is the HAL's remosaic - the row fails); a failed row is noted for this firmware, the module goes back to a digital 2x crop of
 * the normal mode with a notice ({@link #failed}). The CROPPED_RAW ISZ of the HAL (izoom hwRemosaic) is off on the whole OPPO
 * family (CroppedRaw.supported). scam_dev "oplus_isz 0" turns the modules off.
 */
public final class OplusIsz {
    private OplusIsz() { }

    static final String TAG = "OplusIsz";
    /** The izoom QBC row of the PYB110 main and tele. */
    static final int ROW = 11;

    /** The route may run here: a PYB110 (scam_dev "oplus_isz 0": not). */
    public static boolean device() {
        return OppoNativeFullRes.family() && OppoNativeFullRes.pyb110() && PreferenceKeys.scamDevSwitch("oplus_isz", true);
    }

    /**
     * {row, width, height, fps} of the camera's ISZ row on the PYB110 ({@code pyb110}): row 11 of camera 2 (4080x3072) or 4
     * (4096x3072) when the table has it at a quarter of its full size (a 2x crop of the half-size Quad); null otherwise.
     */
    @Nullable
    static int[] row(boolean pyb110, @Nullable String cameraId, @Nullable int[] table) {
        if (!pyb110 || !("2".equals(cameraId) || "4".equals(cameraId))) return null;
        final int[] e = SensorModeBand.modeEntry(table, ROW), full = OppoNativeFullRes.fullModes(table);
        if (e == null || full == null) return null;
        // the full size is 4x (main, 16320 / 4080) or 2x (tele, 8192 / 4096) the row: either way a crop of a half-size Quad
        final boolean quarter = Math.abs(e[0] * 4 - full[0]) <= full[0] / 50, half = Math.abs(e[0] * 2 - full[0]) <= full[0] / 50;
        if (!(quarter || half) || Math.abs(e[1] * 4 - e[0] * 3) > e[0] / 50) return null; // a 4:3 crop (4080x3072 within 2 %)
        return new int[]{ROW, e[0], e[1], e[2]};
    }

    /** The ISZ row of a camera of this phone (its table, or the recorded one); null without one. */
    @Nullable
    static int[] row(@Nullable String cameraId) {
        return row(OppoNativeFullRes.pyb110(), cameraId, OppoNativeFullRes.recorded(cameraId));
    }

    /**
     * SensorModePick: a picked row of {@code cameraId} that is its ISZ row runs the route - its Quad (block 2) declared, RAW only;
     * any other pick as it was. Not after the row failed on this firmware.
     */
    static SensorModePick.Pick mark(@Nullable String cameraId, @Nullable String slot, SensorModePick.Pick p) {
        if (p == null || p.route != null || !device() || p.mode != ROW) return p;
        final int[] r = row(cameraId);
        if (r == null || r[1] != p.width || r[2] != p.height || failed(prefs(), cameraId, stamp())) return p;
        return new SensorModePick.Pick(p.mode, p.width, p.height, false, 2, SensorModePick.ISZ);
    }

    // ---------------------------------------------------------------- the modules

    static final String OFFERED = "module_oplus_isz_offered_";

    /** «2×» for a zoom of 2, «6,4×»-style for 6.4 (one decimal, the decimal point as the module labels have it). */
    static String label(float zoom) {
        final float z = Math.round(zoom * 10f) / 10f;
        final String text = Math.abs(z - Math.round(z)) < 0.05f ? String.valueOf(Math.round(z)) : String.format(Locale.ROOT, "%.1f", z);
        return text + "×";
    }

    /**
     * Once per camera: the back module of camera 2 and of camera 4 (visible, not a copy) gets a copy at twice its zoom with the ISZ
     * row picked and its sensor crop on; a deleted one is not made again. True when this phone takes the route (IszModule then
     * makes no CROPPED_RAW «2×»).
     */
    public static boolean ensure(@Nullable Context context) {
        if (context == null || !device()) return false;
        try {
            final SharedPreferences p = prefs();
            if (p == null) return true;
            for (String camera : new String[]{"2", "4"}) {
                final int[] r = row(camera);
                if (r == null || p.getBoolean(OFFERED + camera, false)) continue;
                String source = null;
                for (String slot : ModuleRegistry.slots())
                    if (slot.startsWith("back") && ModuleRegistry.visible(slot) && ModuleRegistry.duplicateOf(slot) == null
                            && camera.equals(ModuleRegistry.camera(slot)) && SensorModePick.picked(slot) < 0) { source = slot; break; }
                if (source == null) continue;
                p.edit().putBoolean(OFFERED + camera, true).apply(); // offered once, made or not
                final float zoom = 2f * ModuleRegistry.zoom(source);
                final String target = ModuleRegistry.duplicate(source);
                if (target == null) {
                    Log.w(TAG, "no free module slot for the ISZ of camera " + camera + " (copy of " + source + ", zoom " + zoom + ", row " + r[0] + ")");
                    continue;
                }
                p.edit().putString("module_name_" + target, label(zoom))
                        .putString("module_zoom_" + target, String.format(Locale.ROOT, "%.2f", zoom))
                        .putBoolean("module_sensorcrop_" + target, true)
                        .putString(SensorModePick.prefKey(target), String.valueOf(r[0]))
                        .apply();
                Log.i("SCAM_CAPTURE", "OPPO family ISZ module " + target + " («" + label(zoom) + "») on camera " + camera + ": sensor mode row "
                        + r[0] + " " + r[1] + "x" + r[2] + " (izoom QBC, the sensor's Quad, no remosaic), zoom " + zoom + ", sensor crop, ISP stream off");
            }
            alignNoiseProfiles(p);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "ISZ modules not created: " + e);
            return true;
        }
    }

    static final String NOISE_KEY = "pref_noise_model_profile_key", NOISE_ALIGNED = "module_oplus_isz_noise_";

    /** P593: the two modules' «Профиль модели шума» differ (a missing one is «Авто»). */
    static boolean noiseDiffers(@Nullable Object own, @Nullable Object source) {
        final String a = own == null ? "auto" : own.toString(), b = source == null ? "auto" : source.toString();
        return !a.equals(b);
    }

    /**
     * P593 (owner's log 2026-11-09 22:07:24: the main's «2×» ISZ shot ran with «Noise model profile: IMX363_0 (LG V50) at ISO
     * 5197», slope 0.0144 - about 13x the camera's own profile - while its source module and the tele's «2×» took the HAL's): an
     * ISZ module copy takes its source module's «Профиль модели шума», once a module (the copy keeps whatever its slot's profile
     * held where the source has the default, ModuleProfiles.copy); a later choice of the owner on the copy stays.
     */
    private static void alignNoiseProfiles(SharedPreferences p) {
        try {
            for (String slot : ModuleRegistry.slots()) {
                final String source = ModuleRegistry.duplicateOf(slot), camera = ModuleRegistry.camera(slot);
                if (source == null || !slot.startsWith("back") || !("2".equals(camera) || "4".equals(camera))
                        || SensorModePick.picked(slot) != ROW || p.getBoolean(NOISE_ALIGNED + slot, false)) continue;
                p.edit().putBoolean(NOISE_ALIGNED + slot, true).apply();
                final Object own = PreferenceKeys.profiles().snapshot(slot).get(NOISE_KEY), src = PreferenceKeys.profiles().snapshot(source).get(NOISE_KEY);
                if (!noiseDiffers(own, src)) continue;
                PreferenceKeys.profiles().copy(source, java.util.Collections.singletonList(slot), java.util.Collections.singleton(NOISE_KEY));
                Log.i("SCAM_CAPTURE", "OPPO family ISZ module " + slot + " (camera " + camera + "): noise model profile " + own + " -> "
                        + (src == null ? "auto" : src) + ", its source module " + source + "'s (once; P593)");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "ISZ modules' noise profile not aligned: " + e);
        }
    }

    // ---------------------------------------------------------------- the trials (FullResTrials' stamp)

    static final String FAILED = "module_oplus_isz_fail_";
    static final int ROW_TRIALS = 1;

    static String stamp() {
        return FullResTrials.stamp(ROW_TRIALS);
    }

    @Nullable
    static SharedPreferences prefs() {
        try {
            return com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The camera's ISZ row failed on this firmware. */
    static boolean failed(@Nullable SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return false;
        try {
            final String base = FAILED + cameraId;
            return FullResTrials.counts(p, base, base, stamp) && p.getInt(base, 0) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void note(@Nullable SharedPreferences p, String cameraId, int row, @Nullable String why, String stamp) {
        if (p == null || cameraId == null) return;
        try {
            final String base = FAILED + cameraId;
            FullResTrials.edit(p, base, stamp).putInt(base, 1)
                    .putString(base + FullResTrials.WHY, FullResTrials.withReason(null, row, FullResTrials.code(why))).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The ISZ session of {@code slot} failed (refused, no frame, another mode reported, a plain Bayer): noted for this firmware, the
     * module's row back to Auto and its sensor crop off (a digital crop of the normal mode at its zoom); the owner's message.
     */
    public static String failed(String cameraId, @Nullable String slot, SensorModePick.Pick pick, String why) {
        final SharedPreferences p = prefs();
        note(p, cameraId, pick.mode, why, stamp());
        if (p != null && slot != null)
            p.edit().putString(SensorModePick.prefKey(slot), "-1").putBoolean("module_sensorcrop_" + slot, false).apply();
        Log.w("SCAM_CAPTURE", "OPPO family ISZ camera " + cameraId + " row " + pick.mode + " failed (" + why
                + "): the module crops the normal mode digitally from now on (this firmware)");
        return Lang.t("ISZ: режим сенсора " + pick.mode + " не дал мозаику без ремозаика (" + FullResTrials.phrase(FullResTrials.code(why), false)
                        + ") — модуль снимает обычный режим с цифровым кропом",
                "ISZ: sensor mode " + pick.mode + " gave no mosaic without remosaic (" + FullResTrials.phrase(FullResTrials.code(why), false)
                        + ") — the module takes the normal mode with a digital crop");
    }

    // ---------------------------------------------------------------- the mode the results report

    private static volatile NubiaFullRes.ModeWatch watch;
    private static volatile String watchWhat;

    /**
     * CaptureController: a session of {@code camera} starts - its OPPO-family pick (ISZ / DCG / DAG), its native FULLRES profile, or
     * neither (the normal session: its own mode is logged once). The PYB110 only (scam_dev "oplus_mode_watch 0": none).
     */
    public static void watchSession(@Nullable String camera, @Nullable SensorModePick.Pick pick, @Nullable VivoSdkFullRes.Profile nativeProfile) {
        if (camera == null || !OppoNativeFullRes.family() || !OppoNativeFullRes.pyb110() || !PreferenceKeys.scamDevSwitch("oplus_mode_watch", true)) {
            watch = null;
            return;
        }
        // P593: the camera's learn line again with every session (the owner's log of 2026-11-09 began after the camera was learned)
        final String learned = OppoNativeFullRes.learnLine(camera);
        if (learned != null) Log.i("SCAM_CAPTURE", learned);
        final int expected = pick != null && pick.route != null ? pick.mode : nativeProfile != null ? nativeProfile.sensorMode : -1;
        watchWhat = pick != null && pick.route != null ? pick.route : nativeProfile != null ? "FULLRES" : "normal";
        watch = new NubiaFullRes.ModeWatch("OPPO family (" + watchWhat + ")", camera, expected);
    }

    /** The running watch is a native FULLRES session's (its failure turns FULLRES off), not a pick's. */
    public static boolean watchingFullRes() {
        return watch != null && "FULLRES".equals(watchWhat);
    }

    /** CaptureController: a result of the session's camera; a failure reason (the asked mode is not active) or null. */
    @Nullable
    public static String watchResult(@Nullable String camera, @Nullable CaptureResult result) {
        final NubiaFullRes.ModeWatch w = watch;
        if (w == null || result == null || camera == null || !camera.equals(w.camera)) return null;
        Integer reported = null;
        try {
            reported = SensorModeBand.reportedMode(result);
        } catch (RuntimeException ignored) {
        }
        final String line = w.observe(reported);
        if (line != null) Log.i("SCAM_CAPTURE", line);
        return w.failure();
    }

    /** Tests: a new process. */
    static void resetForTest() {
        watch = null;
        watchWhat = null;
    }
}
