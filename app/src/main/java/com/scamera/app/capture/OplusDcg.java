package com.scamera.app.capture;

import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

import androidx.annotation.Nullable;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

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
 * <p>
 * P593 (owner's log 2026-11-09: «разницы в качестве между DCG/DAG вкл и выкл не видно»): the main's row 28 is real - its results
 * report mode 28, the HAL's dynamic levels are black 1023.56 / white 16368 against the declared 64 / 1023, «RAW samples prove 14
 * bit» - while the tele's row 2 reports mode 2 with black 64.1 / white 1023 and no sample above 1023: a plain 10-bit 16:10
 * binning row, no dual-gain RAW (the config's [SHDRTypeMatching] has DCG_enable 0 on every tele entry, 2 = sensor fusion only on
 * the main's pro mode 0x8009; the tele's «SAT 30fps DAG» is the caps mask 0x0040 «dcg 2exp», merged by the ISP for video). So a
 * row has to prove itself by its depth too ({@link DepthWatch}): after the session reports the asked row, a sample or a dynamic
 * level above the declared depth; none in {@link DepthWatch#RESULTS} results - the row is noted («not14»), said, and the next
 * candidate starts at once; with none left the switch is gone on that camera with «DAG не найден». The tele's candidates are its
 * rows the seamless map does not name (17, 8: 4096x3072 @ 30), the map's DAG rows (2, 4), then its unnamed 16:10 rows (14, 15);
 * the named plain rows (1 binning, 7 default, 10 / 12 LBMF, 11 / 13 izoom) are left out. The main: 28, then its PDAF-short twin
 * 38. scam_dev "oplus_dcg_row N" forces one row (it still has to prove itself; nothing is noted). The DCG frames' ISO is not
 * taken as inflated here ({@link #isoInflated}): the worker measured 13.4x the HAL profile / 16 on the main's 14-bit frames.
 */
public final class OplusDcg {
    private OplusDcg() { }

    static final String TAG = "OplusDcg";
    /** The main's DCG row and its size; the tele's DAG candidates and their sizes (P593: the ladder, see the class note). */
    static final int MAIN_ROW = 28, MAIN_W = 4080, MAIN_H = 3072;
    static final int[] MAIN_ROWS = {MAIN_ROW, 38};
    static final int[] TELE_ROWS = {17, 8, 2, 4, 14, 15};
    static final int TELE_W = 4096, TELE_H = 2560, TELE_FULL_H = 3072;
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
     * The DCG / DAG candidates of a PYB110 camera ({@code pyb110}) in its table: main (2) {28, 38} of those rows that are 4080x3072
     * at 30 fps (none without row 28), tele (4) {17, 8, 2, 4, 14, 15} of those that are 4096x3072 or 4096x2560 at 30 fps; null
     * for any other camera or table.
     */
    @Nullable
    static int[] rows(boolean pyb110, @Nullable String cameraId, @Nullable int[] table) {
        if (!pyb110 || table == null) return null;
        final boolean main = "2".equals(cameraId);
        if (!main && !"4".equals(cameraId)) return null;
        int n = 0;
        final int[] list = main ? MAIN_ROWS : TELE_ROWS, out = new int[list.length];
        for (int r : list) {
            final int[] e = SensorModeBand.modeEntry(table, r);
            if (e == null || e[2] != 30) continue;
            if (main ? e[0] == MAIN_W && e[1] == MAIN_H : e[0] == TELE_W && (e[1] == TELE_H || e[1] == TELE_FULL_H)) out[n++] = r;
        }
        if (n == 0 || main && out[0] != MAIN_ROW) return null;
        return java.util.Arrays.copyOf(out, n);
    }

    /** P593: scam_dev "oplus_dcg_row N": the one row the switch runs (to test a row by hand); -1: the candidates in turn. */
    static int forcedRow() {
        return Math.round(PreferenceKeys.scamDevNumber("oplus_dcg_row", -1f));
    }

    /** P593: the frame of a row is 4:3 (4080x3072 is, within 2 %), not a 16:10 crop of the sensor (4096x2560). */
    static boolean fourByThree(int width, int height) {
        return Math.abs(height * 4 - width * 3) <= width * 3 / 50;
    }

    /**
     * P593: the DCG frames' reported ISO is the gain their photons saw, not that gain times the range (as the vivo DCG modes
     * report it, ScamProcessor.dcgIsoScale): the main's row 28 at ISO 1600 measured a noise slope of 2.37e-4 against the HAL
     * profile's 3.23e-4 (0.74x) and 2.02e-5 for that profile / 16 (the worker's «measured / model 13.408»); the levels are the
     * 10-bit ones x 16 (black 1024, white 16368).
     */
    public static boolean isoInflated() {
        return !device();
    }

    /** The row a camera tries now: the first of its rows not yet failed on this firmware ({@code tried} of them did); -1: none. */
    static int live(@Nullable int[] rows, int tried) {
        return rows == null || tried < 0 || tried >= rows.length ? -1 : rows[tried];
    }

    @Nullable
    private static String currentCamera() {
        try {
            return com.scamera.app.app.ScameraApp.getSettings().cameraId;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Dcg14Mode.available: the active camera (2 or 4 of a PYB110) has a DCG / DAG row left. */
    public static boolean available(@Nullable CameraCharacteristics c) {
        if (c == null || !enabled()) return false;
        final String camera = currentCamera();
        final int[] rows = rows(true, camera, OppoNativeFullRes.table(camera, c));
        return rows != null && (forcedRow() >= 0 || live(rows, failures(camera)) >= 0);
    }

    private static volatile boolean hdrKeyListed;

    /** SensorModePick.active: the DCG / DAG row of a session of {@code cameraId} while the switch is on; null otherwise. */
    @Nullable
    static SensorModePick.Pick pick(@Nullable String cameraId, @Nullable CameraCharacteristics c) {
        if (cameraId == null || c == null || !enabled() || !Dcg14Mode.on()) return null;
        final int[] table = OppoNativeFullRes.table(cameraId, c);
        final int[] all = rows(true, cameraId, table);
        final int forced = all == null ? -1 : forcedRow();
        final int tried = failures(cameraId);
        final int row = forced >= 0 ? forced : live(all, tried);
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
                + " @ " + e[2] + " fps" + (fourByThree(e[0], e[1]) ? "" : " (" + e[0] + ":" + e[1] + ", a 16:10 crop of the 4:3 sensor: the photo keeps the row's own field)")
                + (forced >= 0 ? " (forced: scam_dev oplus_dcg_row)" : ", candidate " + (tried + 1) + " of " + all.length + " " + java.util.Arrays.toString(all)
                + (tried > 0 ? " (failed before: " + reasons(OplusIsz.prefs(), cameraId, stamp()) + ")" : ""))
                + ", mode key on the session and every request, " + HDR_KEY + (listed ? " 1" : " not listed") + ", the ISP stream as «Поток ISP» has it,"
                + " the RAW depth to be proven by the frames (" + DepthWatch.RESULTS + " results)");
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

    /** P604: the stored reasons of the camera's failed rows («17:not14,8:noframe»), for the log; "?" without. */
    static String reasons(@Nullable SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return "?";
        try {
            final String base = FAILED + cameraId, key = base + FullResTrials.WHY;
            final String v = FullResTrials.counts(p, base, key, stamp) ? FullResTrials.string(p, key) : null;
            return v == null ? "?" : v;
        } catch (RuntimeException e) {
            return "?";
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

    /** P593: the last {@link #failed} left the switch on - the next candidate starts with the camera's restart (an info, no error). */
    private static volatile boolean searching;

    public static boolean searching() {
        return searching;
    }

    /**
     * A DCG / DAG session failed: the row is noted for this firmware (the next try takes the next row), the switch goes off; the
     * owner's message. P593: a row that ran but gave no RAW wider than declared ({@link DepthWatch}, «not14») leaves the switch on
     * while a candidate is left - the camera restarts into the next one ({@link #searching}); a forced row (scam_dev) is not noted.
     */
    public static String failed(String cameraId, int row, String why) {
        final SharedPreferences p = OplusIsz.prefs();
        final int[] rows = rows(true, cameraId, OppoNativeFullRes.table(cameraId, null));
        final boolean forced = forcedRow() >= 0;
        final int tried = failures(p, cameraId, stamp());
        if (!forced && live(rows, tried) == row) note(p, cameraId, tried + 1, row, why, stamp());
        final int next = forced ? -1 : live(rows, failures(p, cameraId, stamp()));
        final boolean plain = FullResTrials.NOT_WIDE.equals(FullResTrials.code(why));
        // P604: a row whose frames stopped (the tele's row 14, log 2026-11-10 16:06:05: three stall restarts of the same row, no
        // verdict) gives way at once too
        final boolean stopped = FullResTrials.NO_FRAME.equals(FullResTrials.code(why));
        searching = (plain || stopped) && next >= 0;
        if (!searching) Dcg14Mode.setMode(Dcg14Mode.OFF);
        final String n = name("4".equals(cameraId)), reason = FullResTrials.phrase(FullResTrials.code(why), false);
        Log.w("SCAM_CAPTURE", "OPPO family " + n + " camera " + cameraId + " row " + row + " failed (" + why + "); next "
                + (forced ? "none (a forced row): the switch off" : next < 0 ? "none: the switch is gone on this camera"
                : searching ? "row " + next + " now (the switch stays on)" : "row " + next + " the next time") + (searching ? "" : ", the normal session"));
        if (stopped && searching) return Lang.t(n + ": в режиме " + row + " кадры остановились (" + reason + "); пробую режим " + next,
                n + ": the frames stopped in mode " + row + " (" + reason + "); trying mode " + next);
        if (plain) return next >= 0
                ? Lang.t(n + ": режим " + row + " отдаёт обычный RAW (" + reason + "); пробую режим " + next,
                        n + ": mode " + row + " gives a plain RAW (" + reason + "); trying mode " + next)
                : Lang.t(n + " на этой камере не найден: режим " + row + " тоже отдаёт обычный RAW (" + reason + ") — снова обычный режим",
                        n + " was not found on this camera: mode " + row + " gives a plain RAW too (" + reason + ") — back to the normal mode");
        return next >= 0
                ? Lang.t(n + ": режим сенсора " + row + " не включился (" + reason + "); в следующий раз попробую режим " + next,
                        n + ": sensor mode " + row + " did not switch on (" + reason + "); the next time mode " + next + " is tried")
                : Lang.t(n + " недоступен на этой камере (" + reason + ") — снова обычный режим",
                        n + " is not available on this camera (" + reason + ") — back to the normal mode");
    }

    // ---------------------------------------------------------------- P593: the depth the row's frames prove

    /**
     * The bits a session's frames prove above the declared depth: the depth of a sample above the declared white
     * ({@code sampledBits}, RawLevels.sampledBits: 0 for none), of the result's dynamic white, or of its dynamic black when that is
     * the static black times a power of two (within 25 %: 64 -> 1024 = 4 steps = 14 bit); 0 when nothing says more than declared.
     */
    static int provenBits(int declaredWhite, float staticBlack, @Nullable float[] dynamicBlack, int dynamicWhite, int sampledBits) {
        final int declared = com.scamera.app.processing.RawLevels.bitsOfWhite(declaredWhite);
        int best = sampledBits > declared ? sampledBits : 0;
        if (dynamicWhite > 0) {
            final int b = com.scamera.app.processing.RawLevels.bitsOfWhite(dynamicWhite);
            if (b > declared) best = Math.max(best, b);
        }
        if (dynamicBlack != null && dynamicBlack.length >= 4 && staticBlack > 0) {
            float min = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) min = Math.min(min, dynamicBlack[i]);
            if (min > 0) {
                final double ratio = min / staticBlack;
                final int steps = (int) Math.round(Math.log(ratio) / Math.log(2));
                if (steps >= 1 && Math.abs(ratio / Math.pow(2, steps) - 1) <= 0.25) best = Math.max(best, declared + steps);
            }
        }
        return best;
    }

    /** The depth watch of a DCG / DAG session: the row's frames prove more than the declared depth, or the row fails. */
    static final class DepthWatch {
        /** Results of the asked row after which a row without proof fails; RAW16 frames that must have been sampled by then. */
        static final int RESULTS = 24, FRAMES = 4;
        final String camera, what;
        final int row, declaredWhite;
        final float staticBlack;
        private int results, frames, proven;
        private boolean settled;
        private String failure;

        DepthWatch(String what, String camera, int row, int declaredWhite, float staticBlack) {
            this.what = what;
            this.camera = camera;
            this.row = row;
            this.declaredWhite = declaredWhite;
            this.staticBlack = staticBlack;
        }

        void frame() {
            frames++;
        }

        int proven() {
            return proven;
        }

        /** One result of the session ({@code reported}: its mode, null when it says none); the log line to write, or null. */
        @Nullable
        String observe(@Nullable Integer reported, @Nullable float[] dynamicBlack, int dynamicWhite, int sampledBits) {
            if (settled || reported != null && reported != row) return null; // another mode: the mode watch's matter
            results++;
            final String levels = "dynamic black " + (dynamicBlack == null || dynamicBlack.length < 1 ? "none" : String.format(java.util.Locale.ROOT, "%.1f", dynamicBlack[0]))
                    + ", dynamic white " + (dynamicWhite > 0 ? String.valueOf(dynamicWhite) : "none") + ", samples "
                    + (sampledBits > 0 ? sampledBits + " bit" : "within the declared white") + "; declared black " + Math.round(staticBlack) + ", white " + declaredWhite;
            final int bits = provenBits(declaredWhite, staticBlack, dynamicBlack, dynamicWhite, sampledBits);
            if (bits > 0) {
                settled = true;
                proven = bits;
                return "OPPO family " + what + " camera " + camera + " row " + row + ": " + bits + " bit RAW proven (" + levels
                        + "): the dual-gain frames are delivered";
            }
            if (results < RESULTS) return null;
            settled = true;
            if (frames < FRAMES) return "OPPO family " + what + " camera " + camera + " row " + row + ": the depth is not judged (" + frames
                    + " RAW16 frames sampled in " + results + " results; " + levels + ")";
            final int declared = com.scamera.app.processing.RawLevels.bitsOfWhite(declaredWhite);
            failure = "the row's frames stay " + declared + " bit: no dual-gain RAW (" + results + " results, " + frames + " frames sampled; " + levels + ")";
            return null;
        }

        /** The failure reason once the row gave no wider RAW (said once); null otherwise. */
        @Nullable
        String failure() {
            final String f = failure;
            failure = null;
            return f;
        }
    }

    private static volatile DepthWatch depth;

    /** CaptureController: a session of {@code camera} starts with {@code pick}; a DCG / DAG pick is watched for its depth. */
    public static void watchSession(@Nullable String camera, @Nullable SensorModePick.Pick pick, @Nullable CameraCharacteristics c) {
        searching = false;
        if (camera == null || pick == null || !pick.dcg() || c == null || !PreferenceKeys.scamDevSwitch("oplus_dcg_depth", true)) {
            depth = null;
            return;
        }
        final float[] black = com.scamera.app.processing.RawLevels.staticBlack(c);
        float min = 0f;
        if (black != null) {
            min = Float.MAX_VALUE;
            for (float b : black) min = Math.min(min, b);
        }
        depth = new DepthWatch(name("4".equals(camera)), camera, pick.mode, com.scamera.app.processing.RawLevels.staticWhite(c), min);
    }

    /**
     * P604: the DCG / DAG session of {@code camera} has not proven its depth yet - a stall of its frames then is the row's failure
     * (the tele's row 14 stopped after its first results and restarted three times, log 2026-11-10 16:06:05 - 16:06:15).
     */
    public static boolean unproven(@Nullable String camera) {
        final DepthWatch w = depth;
        return w != null && camera != null && camera.equals(w.camera) && w.proven() == 0;
    }

    /** CaptureController: a viewfinder frame of the session was sampled (RawPayloadCheck; {@code raw16}: a RAW_SENSOR frame). */
    public static void watchFrame(boolean raw16) {
        final DepthWatch w = depth;
        if (w != null && raw16) w.frame();
    }

    /** CaptureController: a result of the session's camera; a failure reason (the row gives no wider RAW) or null. */
    @Nullable
    public static String watchResult(@Nullable String camera, @Nullable android.hardware.camera2.CaptureResult result) {
        final DepthWatch w = depth;
        if (w == null || result == null || camera == null || !camera.equals(w.camera)) return null;
        Integer reported = null;
        float[] black = null;
        int white = 0;
        try {
            reported = SensorModeBand.reportedMode(result);
            black = result.get(android.hardware.camera2.CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
            final Integer dw = result.get(android.hardware.camera2.CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
            white = dw == null ? 0 : dw;
        } catch (RuntimeException ignored) {
        }
        final String line = w.observe(reported, black, white, com.scamera.app.processing.RawLevels.sampledBits());
        if (line != null) Log.i("SCAM_CAPTURE", line);
        return w.failure();
    }

    /**
     * P593: the share of a RAW16 frame's samples off the 16-code grid, in per cent (every 7th sample of every 5th row): a 14-bit
     * frame that is 10-bit data shifted by 4 has none; -1 when the buffer does not hold the frame.
     */
    public static float offGridPercent(@Nullable java.nio.ByteBuffer buffer, int width, int height, int rowStride) {
        if (buffer == null || width <= 0 || height <= 0 || rowStride < 2 * width
                || buffer.capacity() < (long) (height - 1) * rowStride + 2L * width) return -1f;
        final java.nio.ShortBuffer data = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        long off = 0, total = 0;
        for (int y = 0; y < height; y += 5) {
            final int base = (int) ((long) y * rowStride / 2);
            for (int x = 0; x < width; x += 7) {
                if ((data.get(base + x) & 0xf) != 0) off++;
                total++;
            }
        }
        return total == 0 ? -1f : 100f * off / total;
    }

    /** P593: the log line of {@link #offGridPercent} for a DCG shot's reference frame of {@code white} levels; null off the route. */
    @Nullable
    public static String precisionLine(@Nullable java.nio.ByteBuffer buffer, int width, int height, int rowStride, int white) {
        if (!device() || white <= 4095) return null;
        final float off = offGridPercent(buffer, width, height, rowStride);
        if (off < 0) return null;
        return String.format(java.util.Locale.ROOT, "OPPO family DCG frame precision: %.1f %% of the samples off the 16-code grid: %s", off,
                off >= 20f ? "real 14-bit codes" : off <= 1f ? "10-bit data in a 14-bit container (x16): no extra depth from the dual gain"
                        : "few fine codes (a companded or mostly coarse frame)");
    }
}
