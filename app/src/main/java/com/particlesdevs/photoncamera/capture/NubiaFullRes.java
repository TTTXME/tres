package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.util.Size;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P575 (owner, 2026-11-09: «изучи отчёт с Нубии и реализуй переключение на фулрез, исз прямо в видоискателе как на других
 * телефонах»; «Фулрез на Нубии работает при переключении на сенсормод фулреза и отключении ISP превью. Нам нужно всё без
 * ремозаика чтобы использовать наш»): FULLRES and a 2x ISZ on the nubia Snapdragon phones (NX721J, SM8650: SCAM TEST
 * 2026-11-02, Build.MANUFACTURER / BRAND "nubia", a ZTE CamX HAL).
 * <p>
 * What the report shows: every rear camera lists one RAW size, its full pixel array (camera 0 / logical 4, IMX800: 8192x6144;
 * camera 2, OV50E40: 8192x6144; camera 3, OV64B40: 9248x6944), no maximum-resolution map, no ULTRA_HIGH_RESOLUTION capability.
 * Their org.quic.camera2.sensormode.info.SensorModeTable has the full size at row 0 and the binned Quad size (half a side) at row
 * 1 (camera 0: 8192x6144@30, 4096x3072@30/60/120, ...; camera 3: 9248x6944@30, 4624x3472@30, ...). The kernel log of the
 * probe's sessions of the main sensor shows «imx800 ... res index: 1, width: 0x1000, height: 0xc00, capability: NORMAL 2PD»
 * (the binned mode) and the logical camera's results com.qti.sensorbps.mode_index 1 while its RAW stream was 8192x6144: the
 * HAL fills the full-size buffer from the binned mode unless asked for the full one.
 * <p>
 * The route is the module sensor-mode pick of P276 (SensorModePick: the row's RAW size, the Qualcomm key
 * org.codeaurora.qcamera3.sensor_meta_data.current_mode on the session parameters and on every request, no ISP stream in the
 * session - the RAW viewfinder): the size switch (12 / 50 Мп, PhotoSize) runs the camera's full-size row ({@link #fullRow}), a
 * «2×» module on the main camera ({@link #ensureIszModule}) runs the same row with the zoom of 2 (the full-resolution centre,
 * the sensor's own 2x crop). Nothing of this touches the normal session (no pick: the HAL's own mode as before). Every session
 * of the row proves itself: the result's reported mode ({@link ModeWatch}) and the frames' colour block (MosaicStream: a plain
 * Bayer is the HAL's remosaic, which the owner does not want - the row counts as failed, FULLRES goes off with a notice and the
 * camera opens its normal session again; the trial is remembered per firmware, FullResTrials).
 * scam_dev "nubia_fullres 0" turns the route off.
 */
public final class NubiaFullRes {
    private NubiaFullRes() { }

    static final String TAG = "NubiaFullRes";

    /** A nubia phone (Build.MANUFACTURER or BRAND "nubia"; the ZTE-made Z / Red Magic Snapdragon line). */
    static boolean phone(@Nullable String manufacturer, @Nullable String brand) {
        return "nubia".equalsIgnoreCase(manufacturer == null ? "" : manufacturer.trim())
                || "nubia".equalsIgnoreCase(brand == null ? "" : brand.trim());
    }

    /** The route may run on this phone (scam_dev.txt "nubia_fullres 0": not); a camera still needs its full-size row. */
    public static boolean device() {
        return phone(android.os.Build.MANUFACTURER, android.os.Build.BRAND) && PreferenceKeys.scamDevSwitch("nubia_fullres", true);
    }

    // ---------------------------------------------------------------- the camera's full-size row

    /**
     * {row, width, height, fps} of the full-size row of a binning sensor's table, null without one: the largest row, of the
     * camera's pixel array (within 1 %: the front camera's table lists 3840x2160 over a 1928x1088 array - no FULLRES of it), while
     * a row of half its sides exists (the binned Quad mode, within 2 %); the first row of that size.
     */
    @Nullable
    static int[] fullRow(@Nullable int[] table, int arrayWidth, int arrayHeight) {
        if (table == null || table.length < 2 || arrayWidth <= 0 || arrayHeight <= 0) return null;
        long best = 0;
        int row = -1;
        for (int[] r : SensorModePick.rows(table))
            if ((long) r[1] * r[2] > best) { best = (long) r[1] * r[2]; row = r[0]; }
        if (row < 0) return null;
        final int[] e = SensorModeBand.modeEntry(table, row);
        if (e == null || Math.abs(e[0] - arrayWidth) > arrayWidth / 100 || Math.abs(e[1] - arrayHeight) > arrayHeight / 100) return null;
        boolean binned = false;
        for (int[] r : SensorModePick.rows(table))
            if (Math.abs(r[1] * 2 - e[0]) <= e[0] / 50 && Math.abs(r[2] * 2 - e[1]) <= e[1] / 50) { binned = true; break; }
        return binned ? new int[]{row, e[0], e[1], e[2]} : null;
    }

    /** The binned row beside the full one (the first row of half its sides), -1 without one: for the log. */
    static int binnedRow(@Nullable int[] table, int width, int height) {
        for (int[] r : SensorModePick.rows(table))
            if (Math.abs(r[1] * 2 - width) <= width / 50 && Math.abs(r[2] * 2 - height) <= height / 50) return r[0];
        return -1;
    }

    private static final Map<String, int[]> OFFER = new ConcurrentHashMap<>();
    private static final int[] NONE = new int[0];

    /** The camera's characteristics as the app read them: its full-size row is learned once per run and logged. */
    public static void learn(@Nullable String cameraId, @Nullable CameraCharacteristics c) {
        if (cameraId == null || c == null || OFFER.containsKey(cameraId) || !device()) return;
        try {
            final Size array = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            final int[] full = learn(cameraId, SensorModeBand.table(c), array == null ? 0 : array.getWidth(), array == null ? 0 : array.getHeight());
            if (full == null) learnMaxRes(cameraId, PixelPro10.fullResStream(c), array);
            // P598: the session keys the remosaic probe's settings may use on this camera
            if (full == null && MAXRES.containsKey(cameraId)) NubiaRemosaicProbe.learn(cameraId, c);
        } catch (RuntimeException e) {
            Log.w(TAG, "camera " + cameraId + ": characteristics unreadable: " + e);
        }
    }

    /** The offer of a camera from its table and pixel array, kept for this run and logged; returns it (null: none). */
    @Nullable
    static int[] learn(String cameraId, @Nullable int[] table, int arrayWidth, int arrayHeight) {
        final int[] full = fullRow(table, arrayWidth, arrayHeight);
        OFFER.put(cameraId, full == null ? NONE : full);
        Log.i("SCAM_CAPTURE", "nubia camera " + cameraId + " (" + android.os.Build.MODEL + "): SensorModeTable "
                + (table == null || table.length < 1 ? "none" : table[0] + " rows") + ", pixel array " + arrayWidth + "x" + arrayHeight
                + (full == null ? ": no full-size row of a binning sensor, no FULLRES / ISZ" + largerRow(table, arrayWidth, arrayHeight)
                : String.format(Locale.ROOT, ": FULLRES row %d %dx%d @ %d fps (binned row %d), Quad block %d", full[0], full[1], full[2],
                full[3], binnedRow(table, full[1], full[2]), OppoNativeFullRes.block(full[1], full[2]))));
        return full;
    }

    /**
     * P585: why a table with a larger row than the advertised pixel array gives no FULLRES - the stock HAL advertises its binned
     * static metadata (2026-11-09 on the NX721J: pixel array and RAW 4096x3072, ISO from 100); the 8192x6144 of the 2026-11-02
     * report and of 2026-11-08 came with a Magisk module installed (owner, 2026-11-09: «На старой сборке работал магиск модуль»):
     * the larger row cannot be streamed (its RAW size is not in the stream configurations).
     */
    static String largerRow(@Nullable int[] table, int arrayWidth, int arrayHeight) {
        int best = -1;
        long area = (long) arrayWidth * arrayHeight;
        for (int[] r : SensorModePick.rows(table))
            if ((long) r[1] * r[2] > area) { area = (long) r[1] * r[2]; best = r[0]; }
        if (best < 0) return "";
        final int[] e = SensorModeBand.modeEntry(table, best);
        return String.format(Locale.ROOT, " (the table's row %d is %dx%d, larger than the advertised %dx%d: the HAL advertises its "
                + "binned static metadata (stock HAL, no Magisk module), so that row's RAW size cannot be streamed)",
                best, e[0], e[1], arrayWidth, arrayHeight);
    }

    /**
     * P588 (owner, 2026-11-09: FULLRES without root; the SCAM TEST of 17:13 without the Magisk module): the stock HAL advertises
     * the binned 4096x3072 but a maximum-resolution map (android.scaler.streamConfigurationMapMaximumResolution: RAW_SENSOR
     * 8192x6144, binning factor 2x2, «RAW_SENSOR 8192x6144 in SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION -> true» on cameras 0 / 2;
     * camera 3 9248x6944): FULLRES runs the generic MAXIMUM_RESOLUTION session (P108's route, PixelPro10.fullResStream) with no
     * ISP stream; its frames must be the sensor's mosaic (MosaicStream: a plain Bayer = the HAL's remosaic). P598: each session
     * runs one setting against that remosaic (NubiaRemosaicProbe: the stock camera's 50 MP keys and QCFA mode, the camera's
     * Qualcomm session keys); a plain Bayer fails the setting and the next one runs, every one failed - 50 Мп off on this
     * firmware with one notice, a confident Quad keeps the setting for the camera.
     */
    private static final Map<String, Size> MAXRES = new ConcurrentHashMap<>();

    static void learnMaxRes(String cameraId, @Nullable PixelPro10.FullRes fr, @Nullable Size array) {
        if (fr == null || fr.size == null || !PreferenceKeys.scamDevSwitch("nubia_maxres", true)) return;
        final long area = array == null ? 0 : (long) array.getWidth() * array.getHeight();
        if ((long) fr.size.getWidth() * fr.size.getHeight() < area * 3 / 2) return;
        MAXRES.put(cameraId, fr.size);
        Log.i("SCAM_CAPTURE", "nubia camera " + cameraId + ": FULLRES through the maximum-resolution RAW " + fr.size
                + " (SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, no ISP stream, the frames must be a mosaic - P588)");
    }

    /** P605: the maximum-resolution RAW size of the camera's FULLRES (null: none learned). */
    @Nullable
    public static Size maxResSize(@Nullable String cameraId) {
        return cameraId == null ? null : MAXRES.get(cameraId);
    }

    /** The camera's FULLRES is the maximum-resolution session (P588). */
    public static boolean maxRes(@Nullable String cameraId) {
        return cameraId != null && device() && MAXRES.containsKey(cameraId);
    }

    /** {row, width, height, fps} the camera offers (null: none or not learned in this run). */
    @Nullable
    static int[] offer(@Nullable String cameraId) {
        if (cameraId == null) return null;
        final int[] o = OFFER.get(cameraId);
        return o == null || o.length == 0 ? null : o;
    }

    /** The camera's characteristics were learned in this run (with or without a row). */
    public static boolean known(@Nullable String cameraId) {
        return cameraId != null && OFFER.containsKey(cameraId);
    }

    /** The megapixels of the camera's full-size row, to the nearest ten (FullResProfiles' tag); 0 without one. */
    public static int megapixels(@Nullable String cameraId) {
        final int[] o = device() ? offer(cameraId) : null;
        if (o == null && maxRes(cameraId)) {
            final Size s = MAXRES.get(cameraId);
            return com.particlesdevs.photoncamera.settings.FullResProfiles.megapixels(s.getWidth(), s.getHeight());
        }
        return o == null ? 0 : com.particlesdevs.photoncamera.settings.FullResProfiles.megapixels(o[1], o[2]);
    }

    /**
     * The camera has a FULLRES row that has not failed on this firmware, or its maximum-resolution route has a setting left
     * against the HAL's remosaic (P598, NubiaRemosaicProbe).
     */
    public static boolean offered(@Nullable String cameraId) {
        return device() && (offer(cameraId) != null && failures(cameraId) == 0 || maxRes(cameraId) && !NubiaRemosaicProbe.exhausted(cameraId));
    }

    /** The size switch of the active module: the camera's FULLRES, on a module without its own sensor mode (not the «2×»). */
    public static boolean offeredHere(@Nullable String cameraId) {
        return offered(cameraId) && !iszModule(activeSlot());
    }

    /**
     * The camera's row failed, or every setting of its maximum-resolution route gave the HAL's remosaic (P598): FULLRES stays in
     * the sheet, dimmed (a tap says why, «Попробовать снова»).
     */
    public static boolean exhausted(@Nullable String cameraId) {
        return device() && (offer(cameraId) != null && failures(cameraId) > 0 || maxRes(cameraId) && NubiaRemosaicProbe.exhausted(cameraId));
    }

    /** A module of this phone with its own picked row (the «2×» ISZ module, or a row the owner picked): a sensor preset. */
    public static boolean iszModule(@Nullable String slot) {
        return slot != null && device() && SensorModePick.picked(slot) >= 0;
    }

    @Nullable
    private static String activeSlot() {
        try {
            return ModuleRegistry.active();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The FULLRES pick of a session of {@code physicalId}: its full-size row while the size switch is on ({@code chosen}) and the
     * camera offers it; null otherwise (the normal session: the HAL's own mode).
     */
    @Nullable
    static SensorModePick.Pick fullResPick(@Nullable String physicalId, @Nullable CameraCharacteristics c, boolean chosen) {
        if (!chosen || physicalId == null || !device()) return null;
        learn(physicalId, c);
        final int[] o = offer(physicalId);
        if (!offered(physicalId) || o == null) return null; // P588: a maximum-resolution FULLRES has no row pick
        return new SensorModePick.Pick(o[0], o[1], o[2], true);
    }

    // ---------------------------------------------------------------- the trials (FullResTrials)

    private static final String FAILED = "module_nubia_fullres_fail_";
    /** The route's revision of the trials' stamp (with Build.FINGERPRINT: a firmware update tries the row again). */
    static final int ROW_TRIALS = 1;

    static String base(String cameraId) {
        return FAILED + cameraId;
    }

    static String stamp() {
        return FullResTrials.stamp(ROW_TRIALS);
    }

    @Nullable
    private static SharedPreferences prefs() {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int failures(@Nullable String cameraId) {
        return failures(prefs(), cameraId, stamp());
    }

    static int failures(@Nullable SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return 0;
        try {
            final String key = base(cameraId);
            return FullResTrials.counts(p, key, key, stamp) ? p.getInt(key, 0) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Nullable
    static String reasons(SharedPreferences p, String cameraId, String stamp) {
        final String key = base(cameraId) + FullResTrials.WHY;
        return FullResTrials.counts(p, base(cameraId), key, stamp) ? FullResTrials.string(p, key) : null;
    }

    /** The camera's row {@code row} failed for {@code why} (one row: the camera's FULLRES is off on this firmware). */
    static void note(@Nullable SharedPreferences p, String cameraId, int row, @Nullable String why, String stamp) {
        if (p == null || cameraId == null) return;
        try {
            final String reasons = FullResTrials.withReason(reasons(p, cameraId, stamp), row, FullResTrials.code(why));
            FullResTrials.edit(p, base(cameraId), stamp).putInt(base(cameraId), 1)
                    .putString(base(cameraId) + FullResTrials.WHY, reasons).apply();
        } catch (RuntimeException ignored) {
        }
    }

    static int clearFailures(@Nullable SharedPreferences p, @Nullable String cameraId) {
        if (p == null || cameraId == null) return 0;
        return FullResTrials.clear(p, base(cameraId));
    }

    /** «FULLRES 50 Мп выключен: режим 0 — это аппаратный ремозаик камеры (обычный Байер).»; null without a failed row. */
    @Nullable
    static String status(@Nullable SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return null;
        if (maxRes(cameraId)) { // P598: the maximum-resolution route's settings against the HAL's remosaic
            final String probe = NubiaRemosaicProbe.status(p, cameraId, stamp);
            if (probe != null) return probe;
        }
        if (failures(p, cameraId, stamp) <= 0) return null;
        final int[] o = offer(cameraId);
        return FullResTrials.sizeLine(o == null ? 0 : com.particlesdevs.photoncamera.settings.FullResProfiles.megapixels(o[1], o[2]),
                o == null ? null : new int[]{o[0]}, 1, FullResTrials.reasons(reasons(p, cameraId, stamp)));
    }

    /**
     * A FULLRES session of the camera failed (refused, no frame, the mode not reported, a plain Bayer): the row is noted for this
     * firmware and the owner's message returned (FULLRES goes off, the normal session opens).
     */
    public static String failed(String cameraId, int row, String why) {
        note(prefs(), cameraId, row, why, stamp());
        Log.w("SCAM_CAPTURE", "nubia FULLRES camera " + cameraId + " row " + row + " failed (" + why + "): FULLRES off, the normal session");
        return Lang.t("FULLRES: режим сенсора " + row + " не дал мозаику без ремозаика (" + FullResTrials.phrase(FullResTrials.code(why), false)
                        + ") — снова обычный режим",
                "FULLRES: sensor mode " + row + " gave no mosaic without remosaic (" + FullResTrials.phrase(FullResTrials.code(why), false)
                        + ") — back to the normal mode");
    }

    // ---------------------------------------------------------------- the mode the results report

    /**
     * The mode a session's results report (com.qti.sensorbps.mode_index / org.quic.camera.sensorbps.SensorModeIndex, as
     * OppoNativeFullRes reads them; on the NX721J it followed the kernel's res index: logical camera 4 reported 1 when the IMX800
     * ran its binned res 1, camera 3 reported 0 when the OV64B40 ran its full res 0). A picked row ({@code expected} >= 0) whose
     * results name another mode for {@link #PATIENCE} results and never its own is not active: the session fails over. A
     * session without a pick ({@code expected} -1) only logs the mode of its first report (the normal session's own mode).
     * Results without the key say nothing.
     */
    static final class ModeWatch {
        static final int PATIENCE = 15;
        /** P586: whose session it is in the log («nubia», «OPPO family»). */
        final String who;
        final String camera;
        final int expected;
        private int mismatches;
        private boolean logged, settled;

        ModeWatch(String camera, int expected) {
            this("nubia", camera, expected);
        }

        ModeWatch(String who, String camera, int expected) {
            this.who = who;
            this.camera = camera;
            this.expected = expected;
        }

        /** One result's reported mode (null: none): the log line to write (or null) and, through {@link #failure}, the verdict. */
        @Nullable
        String observe(@Nullable Integer reported) {
            if (settled || reported == null) return null;
            if (expected < 0) {
                settled = true;
                return who + " camera " + camera + " normal session: the results report sensor mode " + reported;
            }
            if (reported == expected) {
                settled = true;
                return who + " camera " + camera + ": the results report sensor mode " + reported + " (asked " + expected + "): the row runs";
            }
            mismatches++;
            if (!logged) {
                logged = true;
                return who + " camera " + camera + ": the results report sensor mode " + reported + ", asked " + expected + " (waiting)";
            }
            return null;
        }

        /** The failure reason once the asked row never showed in {@link #PATIENCE} reports; null otherwise. */
        @Nullable
        String failure() {
            if (settled || expected < 0 || mismatches < PATIENCE) return null;
            settled = true;
            return "the sensor mode is not active (the results report another mode than row " + expected + ")";
        }
    }

    private static volatile ModeWatch watch;

    /** CaptureController: a session of {@code camera} starts with {@code pick} (null: its normal session). */
    public static void watchSession(@Nullable String camera, @Nullable SensorModePick.Pick pick) {
        watch = camera == null || !device() ? null : new ModeWatch(camera, pick == null ? -1 : pick.mode);
    }

    /** CaptureController: a result of the session's camera; a failure reason (the row is not active) or null. */
    @Nullable
    public static String watchResult(@Nullable String camera, @Nullable CaptureResult result) {
        final ModeWatch w = watch;
        if (w == null || result == null || camera == null || !camera.equals(w.camera)) return null;
        Integer reported = null;
        try {
            reported = SensorModeBand.modeIndex(result);
        } catch (RuntimeException ignored) {
        }
        final String line = w.observe(reported);
        if (line != null) Log.i("SCAM_CAPTURE", line);
        return w.failure();
    }

    // ---------------------------------------------------------------- the «2×» ISZ module

    static final String ISZ_OFFERED = "module_nubia_isz_offered_";
    static final String ISZ_LABEL = "2×";

    /**
     * Once per camera: the back main module (visible, not a copy, zoom 1) of a camera with a full-size row gets a «2×» copy on the
     * same camera, zoom 2, with that row picked (SensorModePick) - the sensor's full-resolution Quad, its centre at 2x; a deleted
     * one is not created again. IszModule calls this with the main slot and its characteristics.
     */
    public static boolean ensureIszModule(@Nullable Context context, @Nullable String mainSlot, @Nullable CameraCharacteristics c) {
        if (context == null || mainSlot == null || c == null || !device() || !PreferenceKeys.scamDevSwitch("nubia_isz_module", true)) return false;
        try {
            final SharedPreferences p = prefs();
            if (p == null) return false;
            final String cameraId = ModuleRegistry.camera(mainSlot);
            learn(cameraId, c);
            final int[] o = offer(cameraId);
            if (o == null || p.getBoolean(ISZ_OFFERED + cameraId, false)) return o != null;
            p.edit().putBoolean(ISZ_OFFERED + cameraId, true).apply(); // offered once, made or not
            final String target = ModuleRegistry.duplicate(mainSlot);
            if (target == null) {
                Log.w(TAG, "no free module slot for the 2x ISZ of camera " + cameraId + " (copy the main module, zoom 2, sensor mode row "
                        + o[0] + ")");
                return true;
            }
            p.edit().putString("module_name_" + target, ISZ_LABEL)
                    .putString("module_zoom_" + target, "2")
                    .putString(SensorModePick.prefKey(target), String.valueOf(o[0]))
                    .apply();
            Log.i("SCAM_CAPTURE", "nubia ISZ module " + target + " («" + ISZ_LABEL + "») on camera " + cameraId + ": sensor mode row "
                    + o[0] + " " + o[1] + "x" + o[2] + " (full-resolution Quad), zoom 2");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "ISZ module not created: " + e);
            return false;
        }
    }

    /** Tests: a new process. */
    static void resetForTest() {
        OFFER.clear();
        MAXRES.clear();
        watch = null;
    }

    /** Tests: what a camera offers ({row, width, height, fps}). */
    static void offerForTest(String cameraId, @Nullable int[] offer) {
        OFFER.put(cameraId, offer == null ? NONE : offer);
    }
}
