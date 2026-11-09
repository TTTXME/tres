package com.particlesdevs.photoncamera.capture;

import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P586 (owner, 2026-11-09: «… и если найдёшь DCG»; «Dag тоже активируем, если возможно»): the DCG switch (Dcg14Mode) on the
 * OnePlus 16 (PYB110). Its CameraHWConfiguration.config (SCAM TEST 2026-11-09) has enableDCGMode = TRUE (line 25875), the NDK
 * characteristics list HDRDCGModes {657924} on cameras 0 and 2 (tag 0x80620001, lines 617 / 7322; Java sees no vendor key) and the
 * seamless map names the sensor-merged DCG row of the main: pokemonmain[0x0080][0x8009] = 28 «pro 1x dcg sensor fusion» (caps bit7
 * DCGSensorMerged; row 28 = 4080x3072 @ 30, the binned Bayer), its PDAF-short twin 38. The tele (IMX-class 50 MP, pokemontele) has
 * no DCG but DAG (dual analog gain, merged in the sensor): pokemontele[0x0040][0x800B / 0x8028] = 2 «SAT 30fps DAG» (row 2 =
 * 4096x2560 @ 30, 16:10 of the 4:3 sensor - the frame is what the row reads, no stretch), the ProVideo twin [0x20040] = 4 (row 4,
 * the same size): row 2 first, row 4 when 2 failed. Rows 2 / 4 are also the tele's plain binning entries of other caps: only the
 * frames show whether DAG ran (RawLevels learns the white level and depth from them; the characteristics say 1023).
 * <p>
 * The route is the sensor-mode pick (SensorModePick) - the mode key com.oplus.engineercamera.agingtest.mode.select on the session
 * and every request - plus org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode = 1 where the camera lists it as a session key
 * (scam_dev "oplus_dcg_hdr_key 0": not). The ISP stream follows «Поток ISP» (owner: «У DCG не обязательно отключать isp»). Every
 * session proves itself by the mode its results report (OplusIsz.watchSession); a row that fails (refused, no frame, another mode)
 * is noted for this firmware, DCG goes off with a notice and the next try takes the next row. scam_dev "oplus_dcg 0" hides the switch.
 */
public final class OplusDcg {
    private OplusDcg() { }

    static final String TAG = "OplusDcg";
    /** The main's DCG row and its size; the tele's DAG rows and their size. */
    static final int MAIN_ROW = 28, MAIN_W = 4080, MAIN_H = 3072;
    static final int[] TELE_ROWS = {2, 4};
    static final int TELE_W = 4096, TELE_H = 2560;
    static final String HDR_KEY = "org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode";
    private static final CaptureRequest.Key<Integer> HDR = new CaptureRequest.Key<>(HDR_KEY, Integer.class);

    /** A PYB110 (the rows are that phone's). */
    public static boolean device() {
        return OppoNativeFullRes.family() && OppoNativeFullRes.pyb110();
    }

    public static boolean enabled() {
        return device() && PreferenceKeys.scamDevSwitch("oplus_dcg", true);
    }

    /** «DCG» on the main, «DAG (телевик)» on the tele. */
    static String name(boolean tele) {
        return tele ? Lang.t("DAG (телевик)", "DAG (tele)") : "DCG";
    }

    /** The switch's name on the camera of the active module: «DAG (телевик)» on the PYB110 tele, null elsewhere (the usual «DCG»). */
    @Nullable
    public static String switchName() {
        return enabled() && "4".equals(currentCamera()) ? name(true) : null;
    }

    /**
     * The DCG / DAG rows of a PYB110 camera ({@code pyb110}) in its table: main (2) {28} when row 28 is 4080x3072, tele (4) {2, 4}
     * of those rows that are 4096x2560; null for any other camera or table.
     */
    @Nullable
    static int[] rows(boolean pyb110, @Nullable String cameraId, @Nullable int[] table) {
        if (!pyb110 || table == null) return null;
        if ("2".equals(cameraId)) {
            final int[] e = SensorModeBand.modeEntry(table, MAIN_ROW);
            return e != null && e[0] == MAIN_W && e[1] == MAIN_H ? new int[]{MAIN_ROW} : null;
        }
        if ("4".equals(cameraId)) {
            int n = 0;
            final int[] out = new int[TELE_ROWS.length];
            for (int r : TELE_ROWS) {
                final int[] e = SensorModeBand.modeEntry(table, r);
                if (e != null && e[0] == TELE_W && e[1] == TELE_H) out[n++] = r;
            }
            return n == 0 ? null : java.util.Arrays.copyOf(out, n);
        }
        return null;
    }

    /** The row a camera tries now: the first of its rows not yet failed on this firmware ({@code tried} of them did); -1: none. */
    static int live(@Nullable int[] rows, int tried) {
        return rows == null || tried < 0 || tried >= rows.length ? -1 : rows[tried];
    }

    @Nullable
    private static String currentCamera() {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettings().mCameraID;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Dcg14Mode.available: the active camera (2 or 4 of a PYB110) has a DCG / DAG row left. */
    public static boolean available(@Nullable CameraCharacteristics c) {
        if (c == null || !enabled()) return false;
        final String camera = currentCamera();
        return live(rows(true, camera, OppoNativeFullRes.table(camera, c)), failures(camera)) >= 0;
    }

    private static volatile boolean hdrKeyListed;

    /** SensorModePick.active: the DCG / DAG row of a session of {@code cameraId} while the switch is on; null otherwise. */
    @Nullable
    static SensorModePick.Pick pick(@Nullable String cameraId, @Nullable CameraCharacteristics c) {
        if (cameraId == null || c == null || !enabled() || !Dcg14Mode.on()) return null;
        final int[] table = OppoNativeFullRes.table(cameraId, c);
        final int row = live(rows(true, cameraId, table), failures(cameraId));
        final int[] e = SensorModeBand.modeEntry(table, row);
        if (row < 0 || e == null) return null;
        boolean listed = false;
        try {
            for (CaptureRequest.Key<?> k : c.getAvailableSessionKeys()) if (HDR_KEY.equals(k.getName())) { listed = true; break; }
        } catch (RuntimeException ignored) {
        }
        hdrKeyListed = listed;
        final boolean tele = "4".equals(cameraId);
        Log.i("SCAM_CAPTURE", "OPPO family " + name(tele) + " camera " + cameraId + ": sensor mode row " + row + " " + e[0] + "x" + e[1]
                + " @ " + e[2] + " fps" + (e[1] * 4 != e[0] * 3 ? " (" + e[0] + ":" + e[1] + ", a 16:10 crop of the 4:3 sensor: the photo keeps the row's own field)" : "")
                + ", mode key on the session and every request, " + HDR_KEY + (listed ? " 1" : " not listed") + ", the ISP stream as «Поток ISP» has it,"
                + " the RAW levels learned from the frames");
        return new SensorModePick.Pick(row, e[0], e[1], false, 0, SensorModePick.DCG);
    }

    /** EnableHDRDCGMode = 1 on a DCG / DAG request where the camera lists it (its session key on every request); the log text. */
    static String applyHdrKey(CaptureRequest.Builder b) {
        if (b == null || !hdrKeyListed || !PreferenceKeys.scamDevSwitch("oplus_dcg_hdr_key", true)) return "";
        try {
            b.set(HDR, 1);
            return ", EnableHDRDCGMode 1";
        } catch (RuntimeException refused) {
            return ", EnableHDRDCGMode refused: " + refused.getMessage();
        }
    }

    /** Dcg14Mode.apply: what the route sets (the pick puts its keys itself). */
    static String describe(@Nullable String cameraId) {
        final SensorModePick.Pick p = SensorModePick.session();
        return name("4".equals(cameraId)) + " by the sensor-mode pick " + (p == null ? "(none: the row is not offered)" : p.toString())
                + " (the mode key on the session and every request)";
    }

    // ---------------------------------------------------------------- the trials

    static final String FAILED = "module_oplus_dcg_fail_";
    static final int ROW_TRIALS = 1;

    static String stamp() {
        return FullResTrials.stamp(ROW_TRIALS);
    }

    static int failures(@Nullable String cameraId) {
        return failures(OplusIsz.prefs(), cameraId, stamp());
    }

    static int failures(@Nullable SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return 0;
        try {
            final String base = FAILED + cameraId;
            return FullResTrials.counts(p, base, base, stamp) ? p.getInt(base, 0) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static void note(@Nullable SharedPreferences p, String cameraId, int tried, int row, @Nullable String why, String stamp) {
        if (p == null || cameraId == null) return;
        try {
            final String base = FAILED + cameraId, key = base + FullResTrials.WHY;
            final String stored = FullResTrials.counts(p, base, key, stamp) ? FullResTrials.string(p, key) : null;
            FullResTrials.edit(p, base, stamp).putInt(base, tried)
                    .putString(key, FullResTrials.withReason(stored, row, FullResTrials.code(why))).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * A DCG / DAG session failed: the row is noted for this firmware (the next try takes the next row), the switch goes off; the
     * owner's message.
     */
    public static String failed(String cameraId, int row, String why) {
        final SharedPreferences p = OplusIsz.prefs();
        final int[] rows = rows(true, cameraId, OppoNativeFullRes.table(cameraId, null));
        final int tried = failures(p, cameraId, stamp());
        if (live(rows, tried) == row) note(p, cameraId, tried + 1, row, why, stamp());
        final int next = live(rows, failures(p, cameraId, stamp()));
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        final String n = name("4".equals(cameraId)), reason = FullResTrials.phrase(FullResTrials.code(why), false);
        Log.w("SCAM_CAPTURE", "OPPO family " + n + " camera " + cameraId + " row " + row + " failed (" + why + "); next "
                + (next >= 0 ? "row " + next : "none: the switch is gone on this camera") + ", the normal session");
        return next >= 0
                ? Lang.t(n + ": режим сенсора " + row + " не включился (" + reason + "); в следующий раз попробую режим " + next,
                        n + ": sensor mode " + row + " did not switch on (" + reason + "); the next time mode " + next + " is tried")
                : Lang.t(n + " недоступен на этой камере (" + reason + ") — снова обычный режим",
                        n + " is not available on this camera (" + reason + ") — back to the normal mode");
    }
}
