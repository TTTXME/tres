package com.scamera.app.capture;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import androidx.annotation.Nullable;

import com.scamera.app.api.CameraReflectionApi;
import com.scamera.app.settings.PreferenceKeys;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P192 (owner, 2026-10-17: «Продолжи реализацию FULLRES без аппаратного ремозаика для OPPO Find X7 Ultra»): the OPPO Find X7 Ultra
 * (PHY110, firmware PHY110_16.0.0.202(CN01), measured 2026-10-17 by the diagnostic probe, evidence/oppo_phy110_fullres_20261016)
 * gives its sensors' own Quad mosaic at 8192x6144 with no hardware remosaic when the session parameter
 * com.oplus.engineercamera.agingtest.mode.select (a byte) names the sensor's NoRemosaic mode: camera 2 (main, IMX06A) 35,
 * 3 (ultra-wide, IMX882) 16, 4 (3x, IMX890) 19, 5 (6x, IMX858) 17 - a plain Camera2 session of type 0 with one RAW_SENSOR reader
 * of usage 0 (usage 3 is refused for the full size by the camera service). It runs the native FULLRES route of the vivo phones
 * (VivoNativeFullRes: one RAW stream, the own RAW viewfinder, matched serial stills, SHDR with the own remosaic); every shot still
 * proves the mosaic from the pixels and the mode from the result. Another OPPO phone takes the same route with the candidates
 * of its own sensor-mode table ({@link #fullModes}), each tried and checked in turn.
 * <p>
 * In that mode the HAL has no statistics: its AE stays SEARCHING (54 results, the exposure never moved) and its neutral is NaN, so
 * the route runs its own AE on the RAW frames ({@link NativeRawExposure}) and its own white balance (MosaicNeutral on the frames,
 * put on the camera's illuminant curve learned in its plain sessions); the colour transform and the lens shading map of the
 * camera's last plain session stand in for the ones the mode does not report. Its telephotos are listed only to the package
 * OplusCameraManager is told ({@link #TELE_PACKAGE}, saveOpPackageName and setPackageName, OplusCameraPackageSpoof).
 * <p>
 * P214j (owner's yes to devices.md G3): the route runs on the OPPO family's other ColorOS phones too - OnePlus and realme
 * ({@link #family}): the OnePlus 15 (CPH2747, SCAM TEST 2026-10-11) lists the same session key on all five cameras (a byte, as on
 * the PHY110 / PKJ110) and full-size rows in its tables (main row 6, tele row 19, both 8192x6144); the realme GT8 Pro (RMX5200, log
 * 2026-10-09) took the key from the owner's module ("vendor tag ... agingtest.mode.select is supported", as an Integer). Gated by
 * what the camera lists, not by the model: the session key and full-size rows of a binning sensor in its table
 * ({@link #candidates}); every mode still proves itself by its frames and a failed one is remembered. What was measured on the
 * PHY110 stays there: its modes without a table ({@link #mode}, {@link #model}) and the telephotos' package ({@link #teleAccess}:
 * OPPO only - the OnePlus 15 listed all five cameras to SCAM TEST's own package, and the package spoof is the owner's own
 * setting there).
 */
public final class OppoNativeFullRes {
    private OppoNativeFullRes() { }

    static final String MODEL = "PHY110";
    /** The package the telephotos are listed to (the probe: Meitu; the app keeps its own package and UID). */
    public static final String TELE_PACKAGE = "com.meitu.meiyancamera";
    static final String MODE_KEY = "com.oplus.engineercamera.agingtest.mode.select";
    private static final CaptureRequest.Key<Byte> MODE = new CaptureRequest.Key<>(MODE_KEY, Byte.class);
    /** P214j: the same key where the HAL declares it an int32 (the byte is refused: no marshaler for Byte on an int32 tag). */
    private static final CaptureRequest.Key<Integer> MODE_INT = new CaptureRequest.Key<>(MODE_KEY, Integer.class);
    private static final CaptureResult.Key<int[]> MODE_INDEX = new CaptureResult.Key<>("com.qti.sensorbps.mode_index", int[].class);
    /** P227: the same report under the name WhyCam reads (CamX lists both on the Oplus phones). */
    private static final CaptureResult.Key<int[]> MODE_INDEX_QUIC = new CaptureResult.Key<>("org.quic.camera.sensorbps.SensorModeIndex", int[].class);

    /** The OPPO Find X7 Ultra (PHY110): the phone of the measured modes. */
    public static boolean model() {
        return model(android.os.Build.MANUFACTURER, android.os.Build.MODEL);
    }

    static boolean model(@Nullable String manufacturer, @Nullable String model) {
        return brand(manufacturer) && MODEL.equals(model);
    }

    /** P192 (owner, 2026-10-17: «Добавь все смартфоны оппо в список»): any OPPO phone. */
    public static boolean brand() {
        return brand(android.os.Build.MANUFACTURER);
    }

    static boolean brand(@Nullable String manufacturer) {
        return manufacturer != null && manufacturer.equalsIgnoreCase("OPPO");
    }

    /** P586: the OnePlus 16 (PYB110, SM8975, SCAM TEST 2026-11-09). */
    static final String PYB110 = "PYB110";

    static boolean pyb110(@Nullable String manufacturer, @Nullable String model) {
        return manufacturer != null && manufacturer.equalsIgnoreCase("OnePlus") && model != null && PYB110.equalsIgnoreCase(model.trim());
    }

    /** P586: this phone is the OnePlus 16 (PYB110). */
    public static boolean pyb110() {
        return pyb110(android.os.Build.MANUFACTURER, android.os.Build.MODEL);
    }

    /**
     * P586: the PYB110's sensor-mode tables as its SCAM TEST report (2026-11-09) recorded them through the NDK (tag 0x80d00000, 3
     * values a row): camera 2 (and logical 0, line 634) the 200 MP main, line 7340; camera 4 the 50 MP tele, line 13964. The
     * report's Java characteristics list no vendor key (103 keys, 0 vendor), so the app may read no table by name: these stand in
     * then, on this model only (the session key must still be listed). Row 0 of both is the HAL's «highpixel fullsize hwremosaic»
     * (CameraHWConfiguration.config [OemCamSeamlessSettingMap]); main row 9 / tele row 9 «full size qbc», row 11 «izoom qbc», main
     * row 24 the second 16320x12288 row (in no seamless entry), main row 28 «pro 1x dcg sensor fusion», tele rows 2 / 4 «SAT 30fps DAG».
     */
    static final int[] PYB110_MAIN = {39, 3, 16320, 12288, 8, 4080, 3072, 30, 4080, 2552, 30, 4080, 2304, 60, 4080, 2552, 30,
            4080, 2304, 60, 4080, 2296, 30, 4080, 3072, 30, 4080, 3072, 30, 8160, 6144, 30, 4080, 3072, 30, 4080, 3072, 30,
            4080, 2552, 30, 4080, 2552, 30, 4080, 2552, 30, 4080, 2552, 30, 1920, 1080, 240, 1920, 1080, 480, 4080, 2304, 60,
            4080, 2304, 60, 8160, 4592, 30, 8160, 4592, 30, 3840, 2160, 120, 3840, 2160, 120, 16320, 12288, 8, 8160, 6144, 16,
            4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 30, 1968, 1476, 30, 4080, 2296, 120, 4080, 3072, 30,
            4080, 3072, 60, 4080, 3072, 30, 4080, 3072, 30, 4080, 2552, 30, 4080, 2304, 60, 4080, 3072, 30};
    static final int[] PYB110_TELE = {19, 3, 8192, 6144, 15, 4096, 3072, 30, 4096, 2560, 30, 4096, 2304, 60, 4096, 2560, 30,
            4096, 2304, 60, 4096, 2304, 30, 4096, 3072, 30, 4096, 3072, 30, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 30,
            4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 30, 2048, 1152, 240, 4096, 3072, 30, 4096, 3072, 60};

    /** P586: the recorded table of a PYB110 camera ({@link #PYB110_MAIN} for "2", {@link #PYB110_TELE} for "4"); null otherwise. */
    @Nullable
    static int[] recordedTable(boolean pyb110, @Nullable String cameraId) {
        if (!pyb110 || !PreferenceKeys.scamDevSwitch("oplus_table_fallback", true)) return null;
        return recorded(cameraId);
    }

    /** P586: the recorded PYB110 table of a camera (a copy), null for another camera; the model is the caller's to check. */
    @Nullable
    static int[] recorded(@Nullable String cameraId) {
        if ("2".equals(cameraId)) return PYB110_MAIN.clone();
        if ("4".equals(cameraId)) return PYB110_TELE.clone();
        return null;
    }

    /** P586: a camera's sensor-mode table: the one Java reads, else the PYB110's recorded one ({@link #recordedTable}). */
    @Nullable
    public static int[] table(@Nullable String cameraId, @Nullable android.hardware.camera2.CameraCharacteristics c) {
        final int[] own = SensorModeBand.table(c);
        return own != null ? own : family() ? recordedTable(pyb110(), cameraId) : null;
    }

    /**
     * P214j: the phones of the route - OPPO and its ColorOS siblings OnePlus and realme (Build.MANUFACTURER "OPPO", "OnePlus",
     * "realme"), whose Qualcomm HAL has the engineer-camera session key; a camera of theirs still needs {@link #candidates}.
     */
    public static boolean family() {
        return family(android.os.Build.MANUFACTURER);
    }

    static boolean family(@Nullable String manufacturer) {
        return brand(manufacturer) || manufacturer != null && (manufacturer.equalsIgnoreCase("OnePlus") || manufacturer.equalsIgnoreCase("realme"));
    }

    /**
     * Native FULLRES may run on a phone of the family (P214j: OPPO, OnePlus, realme; scam_dev.txt "oppo_fullres 0" turns it off);
     * a camera still needs {@link #profile}.
     */
    public static boolean device() {
        return family() && PreferenceKeys.scamDevSwitch("oppo_fullres", true);
    }

    /**
     * The telephotos are listed to {@link #TELE_PACKAGE} (scam_dev.txt "oppo_tele_access 0": not). P214j: OPPO only - the package
     * was measured on the PHY110; OnePlus and realme keep the package the owner's spoof settings give (none by default).
     */
    public static boolean teleAccess() {
        return brand() && PreferenceKeys.scamDevSwitch("oppo_tele_access", true);
    }

    /** The NoRemosaic mode measured on the PHY110 for an Android camera, -1 for another camera. */
    static int mode(@Nullable String cameraId) {
        if (cameraId == null) return -1;
        switch (cameraId) {
            case "2": return 35;
            case "3": return 16;
            case "4": return 19;
            case "5": return 17;
            default: return -1;
        }
    }

    /**
     * The Qualcomm sensor-mode table of a camera (characteristics 0x80390000: {count, values per mode, then width, height, fps
     * of each mode}). On the PHY110 the measured NoRemosaic modes are exactly the full-size rows after row 0 (the hardware-remosaic
     * one): main {0, 35, 37}, ultra-wide {0, 16}, 3x {0, 19}, 6x {0, 17, 20}. Another OPPO phone tries its own full-size rows after
     * row 0 in this order; each one proves itself (the result's mode, the pixels' mosaic) or is noted as failed and the next is
     * tried at the next FULLRES (as the X100 / X300 Ultra rows, P186). P214j: so do OnePlus and realme; the tag is read by its name
     * (the OnePlus 15 registers it as 0x80090000).
     */
    static final String TABLE_KEY = "org.quic.camera2.sensormode.info.SensorModeTable";
    private static final android.hardware.camera2.CameraCharacteristics.Key<int[]> TABLE =
            new android.hardware.camera2.CameraCharacteristics.Key<>(TABLE_KEY, int[].class);

    /**
     * {width, height, fps, mode...}: the full size of the table and its full-size modes after row 0; null without one. P214j: the
     * full size must be a binning sensor's - the table also lists a binned row of it, half or a quarter of the full size a side
     * (Quad 2x2 / Tetra 4x4; within 2 %; the profile's block still comes from the size, {@link #block}, and the frames check it).
     * The OnePlus 15's ultra-wide lists 4096x3072 at rows 0 and 11 and nothing larger: those are its plain rows, not a FULLRES (the
     * PHY110's, PKJ110's and the OnePlus 15's main and tele tables all have their 4096x3072 rows beside 8192x6144).
     */
    @Nullable
    static int[] fullModes(@Nullable int[] table) {
        if (table == null || table.length < 2) return null;
        final int count = table[0], stride = table[1];
        if (count < 2 || stride < 3 || table.length < 2 + count * stride) return null;
        long best = 0;
        int w = 0, h = 0, fps = 0;
        for (int i = 0; i < count; i++) {
            final int mw = table[2 + i * stride], mh = table[3 + i * stride];
            if ((long) mw * mh > best) { best = (long) mw * mh; w = mw; h = mh; fps = table[4 + i * stride]; }
        }
        final java.util.ArrayList<Integer> modes = new java.util.ArrayList<>();
        for (int i = 1; i < count; i++)
            if (table[2 + i * stride] == w && table[3 + i * stride] == h) modes.add(i);
        if (modes.isEmpty() || w < 4000) return null;
        boolean binned = false;
        for (int i = 0; i < count && !binned; i++)
            for (int b = 2; b <= 4 && !binned; b += 2)
                binned = Math.abs(table[2 + i * stride] * b - w) <= w / 50 && Math.abs(table[3 + i * stride] * b - h) <= h / 50;
        if (!binned) return null;
        final int[] out = new int[3 + modes.size()];
        out[0] = w;
        out[1] = h;
        out[2] = fps;
        for (int i = 0; i < modes.size(); i++) out[3 + i] = modes.get(i);
        return out;
    }

    /** The mosaic block of a full size: Tetra 4x4 above 100 MP, else Quad 2x2 (the profile's nativeBlock, checked on the frames). */
    static int block(int width, int height) {
        return (long) width * height > 100_000_000L ? 4 : 2;
    }


    /**
     * P586: what a camera offers - its candidates per size, each {mode, width, height, fps, block}: {@code small} the 50 MP class
     * (a sensor up to 100 MP: its full-size rows as P214j found them; above 100 MP: the rows of half its sides, its full-size
     * Quad, block 2 - P593: those at 24 fps or more first, the slower ones after them: the PYB110 main's row 9, 8160x6144 at 30
     * fps, then row 25, 8160x6144 at 16 fps), {@code large} the full size of a sensor above 100 MP (the PYB110 main's row 24,
     * 16320x12288 at 8 fps, its own 4x4 mosaic, block 4).
     */
    static final class Offer {
        static final Offer NONE = new Offer(new int[0][], new int[0][]);
        final int[][] small, large;

        Offer(int[][] small, int[][] large) {
            this.small = small;
            this.large = large;
        }

        boolean empty() {
            return small.length == 0 && large.length == 0;
        }
    }

    /** What the camera offers ({@link Offer}); NONE without the mode key or a table. */
    private static final Map<String, Offer> OFFER = new ConcurrentHashMap<>();

    /**
     * P586: the fps the first 50 MP-class rows of a sensor above 100 MP have. P593: the slower rows of that size follow them (they
     * were left out): the PYB110 main's row 25 (8160x6144 at 16 fps, pokemonmain[0x8038][0x8001] - caps FullSizeRawQBC2 |
     * FullSizeRaw | QBC | Izoom in the photo mode; the QBC bit is the sensor's own Quad, the hwRemosaic entries 0x0020 / 0x0008
     * lack it) is the twin of the tele's row 9 (8192x6144 at 15 fps), the one that ran in the owner's log of 2026-11-09 while the
     * main's row 9 (30 fps) killed the camera provider 1.1 s after its session was configured, twice.
     */
    static final int MIN_FPS = 24;

    /**
     * P593: rows measured dead on the PYB110 main (firmware OP64E1L1 CP2A.260605.016, log 2026-11-09 22:06:36: session
     * configured, no result, camera error 4 after 1.1 s, the provider gone for 4 s; the stored trial of an earlier try said the
     * same): tried after the camera's other rows of that size, not before them.
     */
    static final int[] PYB110_MAIN_DEAD = {9};

    /**
     * P604 (owner's log 2026-11-10 16:05:26 / 16:05:48: the main's rows 25 and 9 at 8160x6144 both died ~0.7 s after their session
     * was configured, camera error 4, while the tele's row 9 at 8192x6144 runs): the HAL's own 50 MP buffer of the main is 8192x6144
     * - CameraHWConfiguration.config [SupportedQCFAImageSizespokemonmain] «8192; 6144 # main camera full size», [UHPRawSize] 8192 x
     * 6144, and the stock's [OEMFeature2ModeMatch] buffers «OP_TURBO_RAW_QBC_50M» 62914560 bytes = 8192 x 6144 x 10 / 8 (its 200 MP
     * buffer 250675200 = 16320 x 12288 x 10 / 8, the table's own size). So the main's 50 MP rows are offered with a RAW reader of
     * that size as well, first (the 8160-wide reader of the table's size after them): a trial of their own ({@link #trialId}:
     * {@link #HAL_FRAME} + the mode), so the stored «25:died,9:died» does not cover them. Their frame has 32 columns more than
     * the sensor row: where they are, and what they hold, the frames show (the log line of the session, MosaicStream).
     */
    static final int HAL_FRAME = 1000, PYB110_HAL_W = 8192, PYB110_HAL_H = 6144;

    /**
     * P604: a row's trial in the session operation mode its seamless entry names ({@link #OP_MODE} + the mode): the PYB110 main's
     * row 25 is pokemonmain[0x8038][0x8001] - caps FullSizeRawQBC2 | FullSizeRaw | QBC | Izoom in the OEM photo mode 0x8001 (the
     * only 50 MP entry with an opMode; row 9's 0x0030 / 0x8030 are [-1]); the session asks that operation mode, the table's 8160
     * x 6144 reader, the mode key as always. A camera that refuses the operation mode fails that trial only («refused»).
     */
    static final int OP_MODE = 2000, PYB110_OPMODE = 0x8001;
    static final int[] PYB110_OPMODE_ROWS = {25};

    /**
     * P604: {@code o} with an {@link #OP_MODE} variant of each 50 MP-class row of {@code modes} (the table's size), placed after the
     * first {@code after} rows; the same offer when none is added.
     */
    static Offer opModes(Offer o, int after, int... modes) {
        if (o == null || o.small.length == 0 || modes == null) return o;
        final java.util.ArrayList<int[]> add = new java.util.ArrayList<>();
        for (int m : modes) for (int[] r : o.small) if (r.length <= 5 && r[0] == m) { add.add(new int[]{r[0], r[1], r[2], r[3], r[4], OP_MODE + m}); break; }
        if (add.isEmpty()) return o;
        final java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        final int at = Math.max(0, Math.min(after, o.small.length));
        for (int i = 0; i < at; i++) out.add(o.small[i]);
        out.addAll(add);
        for (int i = at; i < o.small.length; i++) out.add(o.small[i]);
        return new Offer(out.toArray(new int[0][]), o.large);
    }

    /**
     * P609 (owner, 2026-11-10: «Оба пути», «Fake off так же стоит попробовать»): the PYB110 main's four RAW-only 50 MP variants of
     * P604 died ~0.7 s after their sessions were configured (log 2026-11-10 19:38:58-19:39:24: 25 / 9 at 8192x6144, 25 in 0x8001,
     * 25 at 8160x6144; camera error 4, no frame), while the stock camera never runs a RAW stream alone: its sessions carry the ISP
     * preview stream. Two more variants of rows 25 and 9 at the HAL's 8192x6144 frame, each a trial of its own (no new
     * {@link #ROW_TRIALS}: the stored «1025:died,2025:died,1009:died,25:died» does not name them): {@link #ISP_FAKE} + the mode - the
     * viewfinder's ISP stream (the 1440x1080 SurfaceTexture) configured in the session, no request targets it (P276's «Фиктивное
     * откл.», WhyCam's fake off); {@link #ISP_ON} + the mode - the ISP stream in the repeating request too (the stills stay RAW
     * only). Order: 25 fake off, 25 on, 9 fake off, 9 on, then the old variants (row 9 at 8160x6144 is the one never retried).
     * The frames still prove the sensor's own Quad (a plain Bayer is a fail, as every row's).
     */
    static final int ISP_FAKE = 3000, ISP_ON = 4000;
    static final int[] PYB110_ISP_ROWS = {25, 9};

    /**
     * P609: {@code o} with a {@link #ISP_FAKE} and an {@link #ISP_ON} variant of each 50 MP-class row of {@code modes} at {@code w} x
     * {@code h} (a row of that height within 2 % of that width: the HAL's frame of a table row), first, in that order; the same
     * offer when none is added.
     */
    static Offer ispVariants(Offer o, int w, int h, int... modes) {
        if (o == null || o.small.length == 0 || modes == null) return o;
        final java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (int m : modes)
            for (int[] r : o.small)
                if (r.length <= 5 && r[0] == m && r[2] == h && Math.abs(r[1] - w) <= w / 50) {
                    out.add(new int[]{m, w, h, r[3], r[4], ISP_FAKE + m});
                    out.add(new int[]{m, w, h, r[3], r[4], ISP_ON + m});
                    break;
                }
        if (out.isEmpty()) return o;
        for (int[] r : o.small) out.add(r);
        return new Offer(out.toArray(new int[0][]), o.large);
    }

    /** P609: the session's ISP stream of the camera's running FULLRES row - 0 none (RAW only), 1 configured only (fake off), 2 repeated. */
    public static int ispStream(@Nullable String cameraId, @Nullable VivoSdkFullRes.Profile profile) {
        if (profile == null || profile.tetraBin || !PreferenceKeys.scamDevSwitch("oplus_fullres_isp", true)) return 0;
        final int id = currentId(cameraId, profile);
        return ispOf(id);
    }

    /** P609: {@link #ispStream} of a trial id. */
    static int ispOf(int id) {
        return id >= ISP_ON && id < ISP_ON + HAL_FRAME ? 2 : id >= ISP_FAKE && id < ISP_FAKE + HAL_FRAME ? 1 : 0;
    }

    /** P609: the session's ISP stream in words (the native session's log line). */
    public static String ispWords(int isp) {
        return isp == 2 ? "ISP stream in the session and the repeating request (P609), RAW viewfinder"
                : isp == 1 ? "ISP stream in the session, no request targets it (fake off, P609), RAW viewfinder"
                : "ISP stream off (RAW viewfinder)";
    }

    /** P604: the trial id of the running row of {@code profile} - the camera's live row when it is that profile's. */
    static int currentId(@Nullable String cameraId, @Nullable VivoSdkFullRes.Profile profile) {
        final Offer all = cameraId == null ? null : OFFER.get(cameraId);
        if (all == null || profile == null) return profile == null ? -1 : profile.sensorMode;
        final int[] now = all.small.length > 0 ? liveSmall(cameraId, all) : null;
        if (now != null && now[0] == profile.sensorMode && now[1] == profile.size.getWidth() && now[2] == profile.size.getHeight()) return trialId(now);
        return trialId(all.small, profile);
    }

    /** P604: the session operation mode of the camera's running FULLRES row (0: the normal one). */
    public static int operationMode(@Nullable String cameraId, @Nullable VivoSdkFullRes.Profile profile) {
        final int id = currentId(cameraId, profile);
        return id >= OP_MODE && id < OP_MODE + HAL_FRAME && PreferenceKeys.scamDevSwitch("oplus_fullres_opmode", true) ? PYB110_OPMODE : 0;
    }

    /** P604: the trial id of a row - its mode, or {@link #HAL_FRAME} + its mode for the reader of the HAL's frame size. */
    static int trialId(int[] row) {
        return row != null && row.length > 5 ? row[5] : row == null ? -1 : row[0];
    }

    /** P604: the trial id of a profile among {@code rows} (its mode when none matches its size). */
    static int trialId(int[][] rows, VivoSdkFullRes.Profile p) {
        final int at = rows == null || p == null ? -1 : indexOf(rows, p);
        return at >= 0 ? trialId(rows[at]) : p == null ? -1 : p.sensorMode;
    }

    /** P604: «25» or «25 (кадр 8192×6144)» for a trial id. */
    static String rowLabel(int id) {
        if (ispOf(id) == 2) return (id - ISP_ON) + com.scamera.app.util.Lang.t(" (8192×6144, поток ISP вкл.)", " (8192×6144, ISP stream on)");
        if (ispOf(id) == 1) return (id - ISP_FAKE) + com.scamera.app.util.Lang.t(" (8192×6144, поток ISP фикт. откл.)", " (8192×6144, ISP stream fake off)");
        if (id >= OP_MODE && id < OP_MODE + HAL_FRAME)
            return (id - OP_MODE) + com.scamera.app.util.Lang.t(" (сессия 0x" + Integer.toHexString(PYB110_OPMODE) + ")", " (session 0x" + Integer.toHexString(PYB110_OPMODE) + ")");
        return id >= HAL_FRAME ? (id - HAL_FRAME) + com.scamera.app.util.Lang.t(" (кадр по размеру HAL)", " (the HAL's frame size)") : String.valueOf(id);
    }

    /**
     * P604: {@code o} with a variant of each 50 MP-class row whose size is not {@code w} x {@code h} but has its height, at that
     * size ({@link #HAL_FRAME} trials), before the rows; the 200 MP rows untouched. The same offer when there is none.
     */
    static Offer halFrames(Offer o, int w, int h) {
        if (o == null || o.small.length == 0) return o;
        final java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (int[] r : o.small) if (r.length <= 5 && r[2] == h && r[1] != w && Math.abs(r[1] - w) <= w / 50)
            out.add(new int[]{r[0], w, h, r[3], r[4], HAL_FRAME + r[0]});
        if (out.isEmpty()) return o;
        for (int[] r : o.small) out.add(r);
        return new Offer(out.toArray(new int[0][]), o.large);
    }

    /** P593: {@code o} with the 50 MP-class rows of {@code dead} modes moved behind the others (each group in its order). */
    static Offer demoted(Offer o, int... dead) {
        if (o == null || o.small.length < 2 || dead == null || dead.length == 0) return o;
        final java.util.ArrayList<int[]> live = new java.util.ArrayList<>(), last = new java.util.ArrayList<>();
        for (int[] r : o.small) {
            boolean d = false;
            for (int m : dead) d |= m == r[0];
            (d ? last : live).add(r);
        }
        if (last.isEmpty() || live.isEmpty()) return o;
        live.addAll(last);
        return new Offer(live.toArray(new int[0][]), o.large);
    }

    /**
     * P586: the candidates of a table ({@link Offer}). The full size must exceed the camera's public RAW size ({@code publicWidth}
     * x {@code publicHeight}, 0 when unknown): the PYB110 front lists 4096x3072 at rows 0-2 beside 2048x1536 - its plain RAW, no
     * FULLRES. A size of the OEM's custom RAW list ({@code custom}, {w, h} pairs of CameraHWConfiguration.config
     * [OemSupportedCustomRAWFullSizes]) goes first among the 50 MP-class rows. Up to 100 MP every row keeps the table's full-size fps
     * (P214j's frame time, as before).
     */
    static Offer rows(@Nullable int[] table, int publicWidth, int publicHeight, @Nullable java.util.List<int[]> custom) {
        final int[] full = fullModes(table);
        if (full == null) return Offer.NONE;
        final int w = full[0], h = full[1];
        if (publicWidth > 0 && publicHeight > 0 && (long) w * h <= (long) publicWidth * publicHeight) return Offer.NONE;
        if ((long) w * h <= 100_000_000L) {
            final int[][] small = new int[full.length - 3][];
            for (int i = 3; i < full.length; i++) small[i - 3] = new int[]{full[i], w, h, full[2], block(w, h)};
            return new Offer(preferCustom(small, custom), new int[0][]);
        }
        final int[][] large = new int[full.length - 3][];
        for (int i = 3; i < full.length; i++) {
            final int[] e = SensorModeBand.modeEntry(table, full[i]);
            large[i - 3] = new int[]{full[i], w, h, e == null ? full[2] : e[2], 4};
        }
        final java.util.ArrayList<int[]> small = new java.util.ArrayList<>(), slow = new java.util.ArrayList<>();
        for (int i = 1; i < table[0]; i++) {
            final int[] e = SensorModeBand.modeEntry(table, i);
            if (e == null || e[2] <= 0 || Math.abs(e[0] * 2 - w) > w / 50 || Math.abs(e[1] * 2 - h) > h / 50) continue;
            if (publicWidth > 0 && publicHeight > 0 && (long) e[0] * e[1] <= (long) publicWidth * publicHeight) continue;
            (e[2] >= MIN_FPS ? small : slow).add(new int[]{i, e[0], e[1], e[2], 2});
        }
        final int[][] fast = preferCustom(small.toArray(new int[0][]), custom), rest = preferCustom(slow.toArray(new int[0][]), custom);
        final int[][] all = java.util.Arrays.copyOf(fast, fast.length + rest.length); // P593: the slower rows after the fast ones
        System.arraycopy(rest, 0, all, fast.length, rest.length);
        return new Offer(all, large);
    }

    /** P586: the rows whose size the OEM's custom RAW list names first, the others after them, each group in table order. */
    static int[][] preferCustom(int[][] rows, @Nullable java.util.List<int[]> custom) {
        if (custom == null || custom.isEmpty() || rows.length < 2) return rows;
        final java.util.ArrayList<int[]> in = new java.util.ArrayList<>(), out = new java.util.ArrayList<>();
        for (int[] r : rows) (listed(custom, r[1], r[2]) ? in : out).add(r);
        in.addAll(out);
        return in.toArray(new int[0][]);
    }

    static boolean listed(@Nullable java.util.List<int[]> custom, int width, int height) {
        if (custom != null) for (int[] c : custom) if (c[0] == width && c[1] == height) return true;
        return false;
    }

    /** P586: {width, height, fps, modes...} of a size's rows (the first row's size and fps), the P214j form; null for none. */
    @Nullable
    static int[] legacy(@Nullable int[][] rows) {
        if (rows == null || rows.length == 0) return null;
        final int[] out = new int[3 + rows.length];
        out[0] = rows[0][1];
        out[1] = rows[0][2];
        out[2] = rows[0][3];
        for (int i = 0; i < rows.length; i++) out[3 + i] = rows[i][0];
        return out;
    }

    /** P604: the trial ids of rows ({@link #trialId}). */
    static int[] ids(int[][] rows) {
        final int[] out = new int[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = trialId(rows[i]);
        return out;
    }

    static int[] modes(int[][] rows) {
        final int[] out = new int[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = rows[i][0];
        return out;
    }

    /**
     * P214j: the capability gate of a camera - {width, height, fps, candidate modes...} or null (no FULLRES on it). The camera lists
     * the mode key among its session keys and its sensor-mode table has full-size rows ({@link #fullModes}); only the PHY110
     * ({@code measuredPhone}, {@link #model}) falls back to its measured modes ({@link #mode}) without a readable table - never a
     * OnePlus, realme or another OPPO. P586: the 50 MP-class rows of {@link #rows} in that form (a sensor above 100 MP: its 200 MP
     * rows when it has no other).
     */
    @Nullable
    static int[] candidates(@Nullable String cameraId, boolean modeKey, @Nullable int[] table, boolean measuredPhone) {
        final Offer o = offerOf(cameraId, modeKey, table, measuredPhone, 0, 0, null);
        return legacy(o.small.length > 0 ? o.small : o.large);
    }

    /** P586: the camera's {@link Offer} from what it lists: the mode key, its table (or the PHY110's measured modes), its public RAW. */
    static Offer offerOf(@Nullable String cameraId, boolean modeKey, @Nullable int[] table, boolean measuredPhone, int publicWidth,
                         int publicHeight, @Nullable java.util.List<int[]> custom) {
        if (!modeKey) return Offer.NONE;
        final Offer o = rows(table, publicWidth, publicHeight, custom);
        if (!o.empty()) return o;
        return measuredPhone && mode(cameraId) >= 0
                ? new Offer(new int[][]{{mode(cameraId), 8192, 6144, 24, 2}}, new int[0][]) : Offer.NONE;
    }


    /**
     * The camera's characteristics as the app read them (the FULLRES button, the session setup): the table is taken from them.
     * A CameraManager of its own failed on the PHY110 with the owner's package spoof (BadParcelableException), so nothing is read
     * here but what the app hands over; a failure is not remembered. P214j: any phone of the {@link #family}. P586: the public RAW
     * size from the stream map, the PYB110's recorded table when Java reads none, the OEM's custom RAW sizes from its config.
     */
    public static void learn(@Nullable String cameraId, @Nullable android.hardware.camera2.CameraCharacteristics c) {
        if (cameraId == null || c == null || OFFER.containsKey(cameraId) || !family()) return;
        try {
            boolean key = false;
            for (CaptureRequest.Key<?> k : c.getAvailableSessionKeys())
                if (MODE_KEY.equals(k.getName())) { key = true; break; }
            int[] table = null;
            String source = "none";
            if (key) try { table = c.get(TABLE); } catch (IllegalArgumentException noTag) { table = null; }
            if (table != null) source = table.length + " values";
            else if (key && (table = recordedTable(pyb110(), cameraId)) != null) source = "none by name, the PYB110 report's recorded table (" + table[0] + " rows)";
            int pw = 0, ph = 0;
            try {
                final android.hardware.camera2.params.StreamConfigurationMap map =
                        c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                final android.util.Size[] raw = map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR);
                if (raw != null) for (android.util.Size s : raw)
                    if ((long) s.getWidth() * s.getHeight() > (long) pw * ph) { pw = s.getWidth(); ph = s.getHeight(); }
            } catch (RuntimeException noMap) {
                pw = 0;
                ph = 0;
            }
            learn(cameraId, key, table, model(), pw, ph, OplusRawSizes.custom(), source);
        } catch (RuntimeException e) {
            com.scamera.app.util.Log.w("OppoNativeFullRes", "camera " + cameraId + ": characteristics unreadable: " + e);
        }
    }

    /** P214j: the camera's offer from what it lists ({@link #candidates}), kept for this run and logged; returns it (null: none). */
    @Nullable
    static int[] learn(String cameraId, boolean modeKey, @Nullable int[] table, boolean measuredPhone) {
        final Offer o = learn(cameraId, modeKey, table, measuredPhone, 0, 0, null, table == null ? "none" : table.length + " values");
        return legacy(o.small.length > 0 ? o.small : o.large);
    }

    /** P586: {@link #learn(String, boolean, int[], boolean)} with the public RAW size and the OEM list; one log line a camera. */
    static Offer learn(String cameraId, boolean modeKey, @Nullable int[] table, boolean measuredPhone, int publicWidth, int publicHeight,
                       @Nullable java.util.List<int[]> custom, String source) {
        Offer o = offerOf(cameraId, modeKey, table, measuredPhone, publicWidth, publicHeight, custom);
        // P593: the PYB110 main's row 9 died twice on the owner's phone: its 16 fps twin (row 25) first
        final boolean deadFirst = pyb110() && java.util.Arrays.equals(table, PYB110_MAIN) && PreferenceKeys.scamDevSwitch("oplus_dead_rows_last", true);
        if (deadFirst) o = demoted(o, PYB110_MAIN_DEAD);
        // P604: the main's 50 MP rows with a reader of the HAL's frame size (8192x6144) first
        final boolean halFrame = pyb110() && java.util.Arrays.equals(table, PYB110_MAIN) && PreferenceKeys.scamDevSwitch("oplus_hal_frame", true);
        if (halFrame) o = halFrames(o, PYB110_HAL_W, PYB110_HAL_H);
        // P604: row 25 in its seamless entry's operation mode 0x8001, after the first HAL-frame reader
        if (pyb110() && java.util.Arrays.equals(table, PYB110_MAIN) && PreferenceKeys.scamDevSwitch("oplus_fullres_opmode", true))
            o = opModes(o, halFrame ? 1 : 0, PYB110_OPMODE_ROWS);
        // P609: rows 25 and 9 at 8192x6144 with the ISP stream in the session (fake off, then on) before every RAW-only variant
        final boolean isp = pyb110() && java.util.Arrays.equals(table, PYB110_MAIN) && PreferenceKeys.scamDevSwitch("oplus_fullres_isp", true);
        if (isp) o = ispVariants(o, PYB110_HAL_W, PYB110_HAL_H, PYB110_ISP_ROWS);
        OFFER.put(cameraId, o);
        final String line = line(cameraId, android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, modeKey, source, publicWidth,
                publicHeight, o, custom, OplusIsz.row(pyb110(), cameraId, table), OplusDcg.rows(pyb110(), cameraId, table))
                + (deadFirst && o.small.length > 1 ? " (row " + PYB110_MAIN_DEAD[0] + " last: the camera died in it, log 2026-11-09)" : "")
                + (halFrame && o.small.length > 0 && o.small[0].length > 5 ? " (P604: the 8192x6144 readers first - the HAL's 50 MP buffer size; the 8160-wide ones died, log 2026-11-10)" : "")
                + (isp && o.small.length > 0 && ispOf(trialId(o.small[0])) > 0 ? " (P609: first rows 25 / 9 at 8192x6144 with the ISP stream in the session, fake off then on; every RAW-only variant died, log 2026-11-10 19:38-19:39)" : "")
                + (binnedOffered(o) ? " (P609: when the 50 MP rows are gone, the 50 MP size is the 200 MP row 2x2 binned per Tetra cell)" : "");
        LINES.put(cameraId, line);
        com.scamera.app.util.Log.i("OppoNativeFullRes", line);
        return o;
    }

    /** P593: the learn line of each camera, said again when a session of it opens (a log that starts later still has it). */
    private static final Map<String, String> LINES = new ConcurrentHashMap<>();

    /** P593: the camera's learn line ({@link #line}); null before {@link #learn}. */
    @Nullable
    public static String learnLine(@Nullable String cameraId) {
        return cameraId == null ? null : LINES.get(cameraId);
    }

    /**
     * P586: the camera's learn line - «OPPO family camera 2 (OnePlus PYB110): mode key true, table …, public RAW 4080x3072,
     * FULLRES mode 9 8160x6144@30 block 2, 200 MP mode 24 16320x12288@8 block 4 (not in OemSupportedCustomRAWFullSizes), ISZ mode
     * 11 4080x3072, DCG modes 28, 38».
     */
    static String line(String cameraId, String phone, boolean modeKey, String source, int publicWidth, int publicHeight, Offer o,
                       @Nullable java.util.List<int[]> custom, @Nullable int[] isz, @Nullable int[] dcg) {
        final StringBuilder b = new StringBuilder("OPPO family camera ").append(cameraId).append(" (").append(phone).append("): mode key ")
                .append(modeKey).append(", table ").append(source);
        if (publicWidth > 0) b.append(", public RAW ").append(publicWidth).append('x').append(publicHeight);
        if (o.empty()) b.append(", no full-size NoRemosaic candidate");
        for (int[] r : o.small) b.append(String.format(Locale.ROOT, ", FULLRES mode %d %dx%d@%d block %d", r[0], r[1], r[2], r[3], r[4]));
        for (int[] r : o.large) b.append(String.format(Locale.ROOT, ", %s mode %d %dx%d@%d block %d%s", o.small.length > 0 ? "200 MP" : "FULLRES",
                r[0], r[1], r[2], r[3], r[4], custom == null || custom.isEmpty() ? "" : listed(custom, r[1], r[2]) ? "" : " (not in OemSupportedCustomRAWFullSizes)"));
        if (isz != null) b.append(String.format(Locale.ROOT, ", ISZ mode %d %dx%d", isz[0], isz[1], isz[2]));
        if (dcg != null) b.append(", ").append(OplusDcg.name("4".equals(cameraId))).append(" mode").append(dcg.length > 1 ? "s " : " ")
                .append(java.util.Arrays.toString(dcg).replace("[", "").replace("]", ""));
        return b.toString();
    }

    /** Tests only: the camera's offer is learned again. */
    static void forget(String cameraId) {
        if (cameraId != null) {
            OFFER.remove(cameraId);
            LINES.remove(cameraId);
        }
    }

    /** {width, height, fps, modes...} of the camera's first size (P214j's form; null: none or not learned). */
    @Nullable
    static int[] offer(@Nullable String cameraId) {
        if (cameraId == null) return null;
        final Offer o = OFFER.get(cameraId);
        return o == null ? null : legacy(o.small.length > 0 ? o.small : o.large);
    }

    /** The frame time of the camera's full-size mode now (24 fps when unknown). */
    static long frameNs(@Nullable String cameraId) {
        final Offer all = cameraId == null ? null : OFFER.get(cameraId);
        final VivoSdkFullRes.Profile p = profile(cameraId, PixelPro10.tele200());
        if (all != null && p != null) {
            int at = indexOf(all.large, p);
            final int[] r = at >= 0 ? all.large[at] : (at = indexOf(all.small, p)) >= 0 ? all.small[at] : null;
            if (r != null && r[3] > 0) return 1_000_000_000L / r[3];
        }
        final int[] o = offer(cameraId);
        return o != null && o[2] > 0 ? 1_000_000_000L / o[2] : 41_666_666L;
    }

    /** P586: the index of the profile's row (mode and size) among {@code rows}, -1 when it is none of them. */
    static int indexOf(int[][] rows, VivoSdkFullRes.Profile p) {
        for (int i = 0; i < rows.length; i++)
            if (rows[i][0] == p.sensorMode && rows[i][1] == p.size.getWidth() && rows[i][2] == p.size.getHeight()) return i;
        return -1;
    }

    /** P586: the 200 MP size may run (scam_dev.txt "oplus_200mp 0": not). */
    static boolean largeAllowed() {
        return PreferenceKeys.scamDevSwitch("oplus_200mp", true);
    }

    /** P586: the camera has both sizes (50 MP and 200 MP): the size sheet offers «200 Мп» beside «50 Мп». */
    public static boolean twoSizes(@Nullable String cameraId) {
        final Offer o = cameraId == null ? null : OFFER.get(cameraId);
        return o != null && o.small.length > 0 && o.large.length > 0 && largeAllowed();
    }

    /** P586: the trial keys of the camera's large rows: their own with a 50 MP size beside them, else the camera's (one size). */
    static int triedLarge(String cameraId, Offer o) {
        return o.small.length > 0 ? failures(cameraId, true) : failures(cameraId);
    }

    @Nullable
    private static int[] live(int[][] rows, int tried) {
        return tried < 0 || tried >= rows.length ? null : rows[tried];
    }

    /**
     * P593: the row tried now among {@code rows} after {@code tried} of them failed, {@code failed} the modes the trials name
     * (their reasons, FullResTrials.reasons): the first row that is not named - so a camera whose rows changed their order (the
     * PYB110 main: 9 then 25 became 25 then 9) goes on from the rows that really failed («9:died» moves on to 25, not back to 9) -
     * after as many more as failed without a name (trials of a build before P214h count rows only). Null: none left.
     */
    @Nullable
    static int[] liveRow(int[][] rows, int tried, @Nullable java.util.Set<Integer> failed) {
        if (rows == null || tried < 0) return null;
        int named = 0;
        if (failed != null) for (int[] r : rows) if (failed.contains(trialId(r))) named++;
        int unnamed = Math.max(0, tried - named);
        for (int[] r : rows) {
            if (failed != null && failed.contains(trialId(r))) continue;
            if (unnamed-- > 0) continue;
            return r;
        }
        return null;
    }

    /** P593: the modes the camera's 50 MP-class trials name as failed (this build's stamp); empty for none. */
    static java.util.Set<Integer> failedModes(@Nullable android.content.SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return java.util.Collections.emptySet();
        try {
            return FullResTrials.reasons(reasons(p, cameraId, stamp)).keySet();
        } catch (RuntimeException e) {
            return java.util.Collections.emptySet();
        }
    }

    /** P593: the 50 MP-class row the camera tries now ({@link #liveRow} of its trials); null: none left. */
    @Nullable
    private static int[] liveSmall(String cameraId, Offer o) {
        return liveRow(o.small, failures(cameraId), failedModes(prefs(), cameraId, stamp()));
    }

    /**
     * P593: the rows of a size in the order of its trials - the failed ones ({@code failed}, in the order they failed) first, then
     * the others as offered: FullResTrials.sizeLine reads the first {@code tried} as the failed ones.
     */
    static int[] trialOrder(int[] modes, java.util.Collection<Integer> failed) {
        final java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        for (int f : failed) for (int m : modes) if (m == f && !out.contains(m)) out.add(m);
        for (int m : modes) if (!out.contains(m)) out.add(m);
        final int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** The profile the camera tries now: the first candidate not yet failed; Tetra above 100 MP, else Quad; null: none left. */
    @Nullable
    static VivoSdkFullRes.Profile profile(@Nullable String cameraId) {
        return profile(cameraId, false);
    }

    /**
     * P586: the profile of the size asked ({@code large}: the 200 MP one of a camera with two): its first row not yet failed. A
     * 200 MP size with no row left gives way to the 50 MP one. P593: never the other way round - «50 Мп» whose rows all failed is
     * off and does not become 200 MP by itself (the owner's log 2026-11-09: «в следующий раз попробую режим 24» after the main's
     * row 9 died); the 200 MP rows are the camera's only when it has no 50 MP-class row at all. Null when none is left.
     */
    @Nullable
    static VivoSdkFullRes.Profile profile(@Nullable String cameraId, boolean large) {
        final Offer o = cameraId == null ? null : OFFER.get(cameraId);
        if (o == null || o.empty()) return null;
        final int[] r = rowFor(o, large, largeAllowed(), failures(cameraId), failedModes(prefs(), cameraId, stamp()), triedLarge(cameraId, o));
        if (r != null) return new VivoSdkFullRes.Profile(r[1], r[2], r[0], blockOf(cameraId, r));
        // P609: «50 Мп» with no row left is the 200 MP row's frames binned 2x2 per Tetra cell (the sensor mode stays the 200 MP one)
        final int[] big = large ? null : binnedRow(o, cameraId);
        return big == null ? null : new VivoSdkFullRes.Profile(big[1], big[2], big[0], 4, false, true);
    }

    /**
     * P609 (owner, 2026-11-10: «Оба пути»): a camera whose 50 MP-class rows all failed and which has a live 200 MP row of its own 4x4
     * Tetra (block 4 - a row its frames proved a Quad would bin into plain Bayer, a HAL remosaic by our rule) takes its «50 Мп»
     * from that row: the frames binned 2x2 inside each colour cell (TetraBin) - an 8160x6144 Quad, the sensor's own mosaic at 50
     * MP. scam_dev oplus_binned_50 0: off (P593's dimmed «50 Мп»). Null: not so.
     */
    @Nullable
    static int[] binnedRow(Offer o, String cameraId) {
        if (!binnedOffered(o) || cameraId == null) return null;
        final int[] big = live(o.large, triedLarge(cameraId, o));
        return binnedRowFor(o, failures(cameraId), failedModes(prefs(), cameraId, stamp()), triedLarge(cameraId, o),
                big == null ? 4 : blockOf(cameraId, big));
    }

    /**
     * P609: {@link #binnedRow} from the trials ({@code triedSmall} / {@code failedSmall}: the 50 MP class, {@code triedLarge}: the
     * 200 MP rows, {@code largeBlock}: the block the live 200 MP row runs) - the live 200 MP row once no 50 MP-class row is left,
     * when it bins; null otherwise (the switch is the caller's).
     */
    @Nullable
    static int[] binnedRowFor(Offer o, int triedSmall, @Nullable java.util.Set<Integer> failedSmall, int triedLarge, int largeBlock) {
        if (o == null || o.small.length == 0 || o.large.length == 0) return null;
        if (liveRow(o.small, triedSmall, failedSmall) != null) return null;
        final int[] r = live(o.large, triedLarge);
        return binnable(r, largeBlock) ? r : null;
    }

    /** P609: the offer could give its «50 Мп» by binning (both sizes, the switch on); its rows decide (binnedRow). */
    static boolean binnedOffered(@Nullable Offer o) {
        return o != null && o.small.length > 0 && o.large.length > 0 && largeAllowed() && PreferenceKeys.scamDevSwitch("oplus_binned_50", true);
    }

    /** P609: a 200 MP row whose block (as it runs, {@code block}) is the declared 4x4 Tetra, at whole 8x8 periods. */
    static boolean binnable(@Nullable int[] row, int block) {
        return row != null && row[4] == 4 && block == 4 && row[1] >= 16 && row[2] >= 16 && row[1] % 8 == 0 && row[2] % 8 == 0;
    }

    /** P609: the camera's «50 Мп» is the binned 200 MP row now. */
    public static boolean binnedFifty(@Nullable String cameraId) {
        final Offer o = cameraId == null ? null : OFFER.get(cameraId);
        return o != null && binnedRow(o, cameraId) != null;
    }

    /**
     * P593: the row of the size asked, from the trials ({@code triedSmall} / {@code failedSmall}: the 50 MP class, {@link #liveRow};
     * {@code triedLarge}: the 200 MP rows): 200 MP asked and none left - the 50 MP row; 50 MP asked and none left - null, unless
     * the camera has no 50 MP-class row at all (its 200 MP rows are its FULLRES then).
     */
    @Nullable
    static int[] rowFor(Offer o, boolean large, boolean largeAllowed, int triedSmall, @Nullable java.util.Set<Integer> failedSmall, int triedLarge) {
        final boolean two = o.small.length > 0 && o.large.length > 0 && largeAllowed;
        int[] r = large && two ? live(o.large, triedLarge) : null;
        if (r == null) r = liveRow(o.small, triedSmall, failedSmall);
        if (r == null && o.small.length == 0 && largeAllowed) r = live(o.large, triedLarge);
        return r;
    }

    /** P586: that size of a camera with two has no row left (its entry in the size sheet stays, dimmed). */
    public static boolean sizeExhausted(@Nullable String cameraId, boolean large) {
        final Offer o = cameraId == null ? null : OFFER.get(cameraId);
        if (o == null || !twoSizes(cameraId)) return false;
        return large ? live(o.large, triedLarge(cameraId, o)) == null : liveSmall(cameraId, o) == null && binnedRow(o, cameraId) == null; // P609
    }

    /**
     * P593: a size of the camera still has a row (the size sheet keeps its entries: «50 Мп» dimmed when its rows failed, «200 Мп»
     * its own choice beside it).
     */
    public static boolean anyLive(@Nullable String cameraId) {
        return profile(cameraId, false) != null || twoSizes(cameraId) && profile(cameraId, true) != null;
    }

    /**
     * P586: the block a row runs: its declared one, but a 200 MP row whose frames proved a Quad (block 2) on this firmware runs as
     * a Quad ({@link #acceptQuad}).
     */
    static int blockOf(String cameraId, int[] row) {
        if (row[4] != 4) return row[4];
        final android.content.SharedPreferences p = prefs();
        return p != null && quadNoted(p, cameraId, row[0], stamp()) ? 2 : 4;
    }

    static boolean quadNoted(android.content.SharedPreferences p, String cameraId, int mode, String stamp) {
        try {
            final String key = base200(cameraId) + QUAD + mode;
            return FullResTrials.counts(p, base200(cameraId), key, stamp) && p.getBoolean(key, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static final String QUAD = "_quad_";

    /**
     * P586: the 200 MP row's viewfinder frames are a confident Quad (block 2), not its declared 4x4: noted for this firmware, the
     * row runs as a Quad from the next session on (a log line); true when it was not noted before (the caller restarts the session).
     */
    public static boolean acceptQuad(@Nullable String cameraId, @Nullable VivoSdkFullRes.Profile profile) {
        final android.content.SharedPreferences p = prefs();
        if (p == null || cameraId == null || profile == null || profile.nativeBlock != 4) return false;
        noteQuad(p, cameraId, profile.sensorMode, stamp());
        com.scamera.app.util.Log.i("SCAM_CAPTURE", "OPPO family camera " + cameraId + " 200 MP mode " + profile.sensorMode
                + ": the frames are a Quad (block 2), not the declared 4x4 - accepted as a Quad, the session restarts with block 2");
        return true;
    }

    static void noteQuad(android.content.SharedPreferences p, String cameraId, int mode, String stamp) {
        try {
            FullResTrials.edit(p, base200(cameraId), stamp).putBoolean(base200(cameraId) + QUAD + mode, true).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /** P586: a 200 MP row failed: noted under its own keys, the owner's message (the 50 MP size stays as it is). */
    static String failedLarge(String cameraId, Offer all, VivoSdkFullRes.Profile profile, String why) {
        final int tried = failures(cameraId, true);
        if (tried < all.large.length && all.large[tried][0] == profile.sensorMode)
            noteAt(prefs(), base200(cameraId), tried + 1, profile.sensorMode, why, stamp());
        final int[] next = live(all.large, failures(cameraId, true));
        final String reason = FullResTrials.phrase(FullResTrials.code(why), false);
        com.scamera.app.util.Log.w("OppoNativeFullRes", "camera " + cameraId + " 200 MP mode " + profile.sensorMode + " "
                + profile.size + (profile.tetraBin ? " (the binned 50 MP, P609)" : "") + " failed (" + why + "); next "
                + (next == null ? profile.tetraBin ? "none: 200 MP off and the binned 50 MP with it" : "none: 200 MP off, 50 MP stays" : "mode " + next[0]));
        if (next == null && profile.tetraBin) // P609: the binned 50 MP was this row
            return com.scamera.app.util.Lang.t("FULLRES 50 Мп из режима 200 Мп не работает на этой камере (" + reason + "); 50 и 200 Мп выключены",
                    "FULLRES 50 MP from the 200 MP mode does not work on this camera (" + reason + "); 50 and 200 MP are off");
        return next != null
                ? com.scamera.app.util.Lang.t("FULLRES 200 Мп: режим " + profile.sensorMode + " не работает (" + reason + "); в следующий раз попробую режим " + next[0],
                        "FULLRES 200 MP: mode " + profile.sensorMode + " does not work (" + reason + "); the next time mode " + next[0] + " is tried")
                : com.scamera.app.util.Lang.t("FULLRES 200 Мп недоступен на этой камере (" + reason + "); 50 Мп работает как прежде",
                        "FULLRES 200 MP is not available on this camera (" + reason + "); 50 MP works as before");
    }

    private static final String FAILED = "module_oppo_fullres_fail_";
    /**
     * P214h: the route revision of the trials' stamp (FullResTrials, with Build.FINGERPRINT: a firmware update tries a camera's
     * modes again); a build that changes the route bumps it to try every camera's modes again.
     */
    static final int ROW_TRIALS = 1;

    /** P214h: the base of a camera's trial keys (the tried count itself, its reasons, its stamp). */
    static String base(String cameraId) {
        return FAILED + cameraId;
    }

    /** P214h: the stamp of this build's trials on this firmware. */
    static String stamp() {
        return FullResTrials.stamp(ROW_TRIALS);
    }

    @Nullable
    private static android.content.SharedPreferences prefs() {
        try {
            return com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int failures(String cameraId) {
        return failures(prefs(), cameraId, stamp());
    }

    /** P586: the base of the 200 MP size's trials of a camera with two sizes (a key of the camera's own: «Попробовать снова» clears it). */
    static String base200(String cameraId) {
        return base(cameraId) + LARGE;
    }

    static final String LARGE = "_200";

    /** P586: the tried count of a size ({@code large}: the 200 MP one, its own keys). */
    static int failures(String cameraId, boolean large) {
        return large ? countAt(prefs(), cameraId == null ? null : base200(cameraId), stamp()) : failures(cameraId);
    }

    static int countAt(@Nullable android.content.SharedPreferences p, @Nullable String key, String stamp) {
        if (p == null || key == null) return 0;
        try {
            return FullResTrials.counts(p, key, key, stamp) ? p.getInt(key, 0) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** P214h: {@link #failures(String)} in {@code p} under the stamp {@code stamp} (another stamp's trials are passed over). */
    static int failures(@Nullable android.content.SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return 0;
        try {
            final String key = base(cameraId);
            return FullResTrials.counts(p, key, key, stamp) ? p.getInt(key, 0) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** P214h: the reasons (mode:code,...) of the camera's failed modes; null for none or another stamp's. */
    @Nullable
    static String reasons(android.content.SharedPreferences p, String cameraId, String stamp) {
        final String key = base(cameraId) + FullResTrials.WHY;
        return FullResTrials.counts(p, base(cameraId), key, stamp) ? FullResTrials.string(p, key) : null;
    }

    /** P214h: the tried count after {@code mode} failed for {@code why}, and that mode's reason, under the stamp {@code stamp}. */
    static void note(@Nullable android.content.SharedPreferences p, String cameraId, int tried, int mode, @Nullable String why,
                     String stamp) {
        if (p == null || cameraId == null) return;
        noteAt(p, base(cameraId), tried, mode, why, stamp);
    }

    /** P586: {@link #note} under the trial keys {@code base} (a camera's 50 MP size: base(), its 200 MP size: base200()). */
    static void noteAt(@Nullable android.content.SharedPreferences p, String base, int tried, int mode, @Nullable String why, String stamp) {
        if (p == null || base == null) return;
        try {
            final String key = base + FullResTrials.WHY;
            final String stored = FullResTrials.counts(p, base, key, stamp) ? FullResTrials.string(p, key) : null;
            final String reasons = FullResTrials.withReason(stored, mode, FullResTrials.code(why));
            FullResTrials.edit(p, base, stamp).putInt(base, tried).putString(key, reasons).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /** P214h: «Попробовать снова» - the camera's trials are forgotten; its next FULLRES starts at its first mode. Keys removed. */
    static int clearFailures(@Nullable android.content.SharedPreferences p, @Nullable String cameraId) {
        if (p == null || cameraId == null) return 0;
        return FullResTrials.clear(p, base(cameraId));
    }

    /** P214h: the camera offers full-size modes (its table, read this run) but every one failed: its button stays, dimmed. */
    static boolean exhausted(@Nullable String cameraId) {
        return offer(cameraId) != null && !anyLive(cameraId); // P593: no size left (a dead 50 MP beside a live 200 MP is not it)
    }

    /**
     * P214h: the camera's trials in words - «FULLRES 50 Мп выключен: режимы 35, 37 — не та мозаика.»; the modes the reasons
     * name when its table is not known in this run; null without a failed mode.
     */
    @Nullable
    static String status(@Nullable android.content.SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return null;
        final int tried = failures(p, cameraId, stamp);
        final Offer all = OFFER.get(cameraId);
        // P586: a camera with two sizes says each size's rows (its 200 MP trials have keys of their own)
        final int tried200 = all == null || all.large.length == 0 || all.small.length == 0 ? 0 : countAt(p, base200(cameraId), stamp);
        if (tried <= 0 && tried200 <= 0) return null;
        final int[] o = offer(cameraId);
        final Map<Integer, String> why = FullResTrials.reasons(reasons(p, cameraId, stamp));
        // P593: the failed rows first, as they failed (the PYB110 main offers 25 before 9, its trial of 9 is the older one)
        final String fifty = tried <= 0 ? null : FullResTrials.sizeLine(o == null ? 0 : com.scamera.app.settings.FullResProfiles.megapixels(o[0], o[1]),
                o == null ? null : trialOrder(all != null && all.small.length > 0 ? ids(all.small) : java.util.Arrays.copyOfRange(o, 3, o.length), why.keySet()), tried, why);
        if (tried200 <= 0) return fifty;
        final String key = base200(cameraId) + FullResTrials.WHY;
        final String large = FullResTrials.sizeLine(com.scamera.app.settings.FullResProfiles.megapixels(all.large[0][1], all.large[0][2]),
                modes(all.large), tried200, FullResTrials.reasons(FullResTrials.counts(p, base200(cameraId), key, stamp) ? FullResTrials.string(p, key) : null));
        return fifty == null ? large : large == null ? fifty : fifty + "\n" + large;
    }

    /**
     * A native session of this camera failed (no frame, the mode not reported, a Bayer or wrong-block frame): its mode is noted and
     * the next candidate is tried at the next FULLRES. Returns the owner's message.
     */
    public static String failed(String cameraId, @Nullable VivoSdkFullRes.Profile profile, String why) {
        final Offer all = cameraId == null ? null : OFFER.get(cameraId);
        // P586: the 200 MP size of a camera with two has trials of its own; its failure leaves the 50 MP size as it is
        if (all != null && profile != null && all.small.length > 0 && indexOf(all.large, profile) >= 0)
            return failedLarge(cameraId, all, profile, why);
        final int tried = failures(cameraId);
        // P593: the row that runs now is the first one the trials do not name (liveRow), not the tried-th of the list
        final int[] now = all == null || all.empty() ? null
                : all.small.length > 0 ? liveSmall(cameraId, all) : live(all.large, tried);
        // P604: the row's trial id (a HAL-frame reader is a trial of its own)
        if (now != null && profile != null && now[0] == profile.sensorMode && now[1] == profile.size.getWidth() && now[2] == profile.size.getHeight())
            note(prefs(), cameraId, tried + 1, trialId(now), why, stamp()); // P214h: with the mode's reason, stamped
        final VivoSdkFullRes.Profile next = profile(cameraId, false);
        final String mode = profile == null ? "-1" : rowLabel(now != null && now[0] == profile.sensorMode && now[1] == profile.size.getWidth()
                ? trialId(now) : currentId(cameraId, profile));
        final String nextMode = next == null ? null : rowLabel(currentId(cameraId, next));
        final boolean other = next == null && twoSizes(cameraId) && profile(cameraId, true) != null; // the 200 MP size is left
        com.scamera.app.util.Log.w("OppoNativeFullRes", "camera " + cameraId + " mode " + mode + " " + (profile == null ? "" : profile.size) + " failed (" + why
                + "); next " + (next != null && next.tetraBin ? "none of the 50 MP rows: 50 MP from the 200 MP mode " + next.sensorMode + " "
                + next.size + ", 2x2 binned per Tetra cell -> " + next.shotSize() + " Quad (P609)"
                : next != null ? "mode " + nextMode + " " + next.size : other ? "none: 50 MP off, 200 MP stays its own size" : "none"));
        final String reason = FullResTrials.phrase(FullResTrials.code(why), false);
        // P609: the one notice of the switch to the binned route (the last 50 MP row failed)
        if (next != null && next.tetraBin)
            return com.scamera.app.util.Lang.t("FULLRES: режим " + mode + " не работает (" + reason + "); строки 50 Мп кончились. Теперь 50 Мп снимается в режиме 200 Мп ("
                            + next.sensorMode + ") с бинингом 2×2 в каждой ячейке Tetra — собственная мозаика Quad сенсора",
                    "FULLRES: mode " + mode + " does not work (" + reason + "); no 50 MP row is left. 50 MP is now shot in the 200 MP mode ("
                            + next.sensorMode + "), 2×2 binned in each Tetra cell: the sensor's own Quad mosaic");
        return next != null
                ? com.scamera.app.util.Lang.t("FULLRES: режим " + mode + " не работает на этой камере (" + reason + "); в следующий раз попробую режим " + nextMode,
                        "FULLRES: mode " + mode + " does not work on this camera (" + reason + "); the next time mode " + nextMode + " is tried")
                : other ? com.scamera.app.util.Lang.t("FULLRES 50 Мп недоступен на этой камере: режим " + mode + " — " + reason + ". 200 Мп остаётся отдельным размером",
                        "FULLRES 50 MP is not available on this camera: mode " + mode + " — " + reason + ". 200 MP stays a size of its own")
                : com.scamera.app.util.Lang.t("FULLRES без аппаратного ремозаика недоступен на этой камере",
                        "FULLRES without hardware remosaic is not available on this camera");
    }

    /** The camera of the running native session (null: none), for the requests and the results of its stills. */
    private static volatile String camera;
    private static volatile NativeRawExposure exposure;

    static void begin(String cameraId, NativeRawExposure ae) {
        camera = cameraId;
        exposure = ae;
        // P212e (W3): the session's white balance starts afresh, from the plain neutral just before it when there is one
        if (cameraId != null && OppoNativeWb.on()) {
            NEUTRALS.remove(cameraId); // P192's average never carries over into a session again
            final long now = android.os.SystemClock.elapsedRealtime();
            final float[] plain = PLAIN_NEUTRALS.get(cameraId);
            final Long at = PLAIN_AT.get(cameraId);
            com.scamera.app.processing.color.MosaicNeutral.init(com.scamera.app.app.ScameraApp.getAppContext());
            com.scamera.app.util.Log.i("SCAM_CAPTURE", "native white balance camera " + cameraId + ": "
                    + OppoNativeWb.begin(cameraId, now, plain, plain == null || at == null ? -1L : now - at, storedNeutral(cameraId),
                    com.scamera.app.processing.color.MosaicNeutral.middle(cameraId)));
        }
    }

    static void end() {
        camera = null;
        exposure = null;
    }

    /** A native session of this phone runs (its results carry the own neutral: not a point of the camera's curve). */
    public static boolean running() {
        return camera != null;
    }

    /**
     * P214j: the key's type on this phone - the vendor tag registry declares it a byte on the PHY110, PKJ110 and OnePlus 15 (type 0),
     * the realme GT8 Pro took it as an Integer (the owner's module, 2026-10-09: "Applied tunable key ... = 9 (Integer)", block 4 on
     * the frames; a Byte there has no marshaler). The byte is tried first; once refused, the int for the rest of the process.
     */
    private static volatile boolean intMode;

    /** P214j: where the mode goes - a byte ({@code asInt} false) or an int32 key; throws as Builder.set does when refused. */
    interface ModeSink {
        void set(boolean asInt, int mode);
    }

    /** P214j: the mode on a builder in the key's type here. */
    static void setMode(CaptureRequest.Builder builder, int mode) {
        setMode(mode, (asInt, m) -> {
            if (asInt) builder.set(MODE_INT, m);
            else builder.set(MODE, (byte) m);
        });
    }

    /** P214j: {@link #setMode(CaptureRequest.Builder, int)} on a sink: the byte unless refused before, else (and when it is) the int. */
    static void setMode(int mode, ModeSink sink) {
        if (!intMode) {
            try {
                sink.set(false, mode);
                return;
            } catch (IllegalArgumentException | UnsupportedOperationException byteRefused) {
                intMode = true;
                com.scamera.app.util.Log.w("OppoNativeFullRes", MODE_KEY + " refused as a byte (" + byteRefused
                        + "): sent as an int32 from now on");
            }
        }
        sink.set(true, mode);
    }

    /** Tests only: the key's type is tried again from the byte. */
    static void resetModeType() {
        intMode = false;
    }

    /** The mode on a request (the session parameters and every request), AE off at the own exposure, the HAL's AWB left on. */
    static void apply(CaptureRequest.Builder builder, VivoSdkFullRes.Profile profile) {
        setMode(builder, profile.sensorMode); // P214j: a byte, an int32 where the HAL declares one
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        NativeRawExposure ae = exposure;
        if (ae != null) {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ae.exposureNs());
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, ae.iso());
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, ae.frameDurationNs());
        }
    }

    /**
     * The result reports the profile's mode (the sensor's own mode index), or does not say. P227: read under both names
     * (com.qti.sensorbps.mode_index, org.quic.camera.sensorbps.SensorModeIndex); either one naming another mode is a mismatch.
     */
    static boolean modeMatches(CaptureResult result, VivoSdkFullRes.Profile profile) {
        return modeMatches(reported(result, MODE_INDEX), reported(result, MODE_INDEX_QUIC), profile.sensorMode);
    }

    static boolean modeMatches(@Nullable int[] qti, @Nullable int[] quic, int mode) {
        if (qti != null && qti.length > 0 && qti[0] != mode) return false;
        return quic == null || quic.length == 0 || quic[0] == mode;
    }

    @Nullable
    private static int[] reported(CaptureResult result, CaptureResult.Key<int[]> key) {
        try {
            return result.get(key);
        } catch (IllegalArgumentException noTag) {
            return null;
        }
    }

    /** What the camera's last plain session reported (the ISP's colour transform, the shading map), per Android camera. */
    private static final Map<String, ColorSpaceTransform> TRANSFORMS = new ConcurrentHashMap<>();
    private static final Map<String, LensShadingMap> SHADING = new ConcurrentHashMap<>();
    /** The own neutral (r / g, 1, b / g) of the running native session, from its frames. */
    private static final Map<String, float[]> NEUTRALS = new ConcurrentHashMap<>();
    /** The ISP's settled neutral of the camera's last plain session: the start of a native session's white balance. */
    private static final Map<String, float[]> PLAIN_NEUTRALS = new ConcurrentHashMap<>();
    /** P212e: when it was reported (elapsedRealtime ms): fresh, it seeds and anchors the native session's white balance. */
    private static final Map<String, Long> PLAIN_AT = new ConcurrentHashMap<>();

    /**
     * P214j: a plain session of this camera keeps its colour for the native mode ({@link #rememberPlain}): a camera of the route that
     * offers full-size modes, on any phone of the {@link #family} - it was the PHY110 only, while every phone of the route had its
     * native results completed ({@link #complete}) from that colour; complete() fills only what a native result lacks.
     */
    static boolean keepsColour(@Nullable String cameraId) {
        return device() && offer(cameraId) != null;
    }

    /**
     * A plain session's result of this phone (AWB settled): its colour transform and shading map are kept for the camera.
     * P212e (W1): on any OPPO phone (the caller's guard was the PHY110 only, while FULLRES runs on every OPPO phone), and stored for
     * the camera's native size (OppoPlainState) at most every 5 s, so a cold start straight into FULLRES has them too.
     */
    static void rememberPlain(String cameraId, CaptureResult result) {
        if (cameraId == null || result == null || camera != null) return;
        final Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);
        if (awb != null && awb != CaptureResult.CONTROL_AWB_STATE_CONVERGED && awb != CaptureResult.CONTROL_AWB_STATE_LOCKED) return;
        final ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        final boolean ispTransform = transform != null && !identity(transform);
        if (ispTransform) TRANSFORMS.put(cameraId, transform);
        final Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        float[] neutral = null;
        if (n != null && n.length >= 3 && n[1].floatValue() > 0f && n[0].floatValue() > 0f && n[2].floatValue() > 0f) {
            neutral = new float[]{n[0].floatValue() / n[1].floatValue(), 1f, n[2].floatValue() / n[1].floatValue()};
            if (!OppoNativeWb.valid(neutral)) neutral = null; // P212e: a NaN / Inf ratio is no neutral (NaN > 0 is false, Inf is not)
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (neutral != null) {
            PLAIN_NEUTRALS.put(cameraId, neutral);
            PLAIN_AT.put(cameraId, now);
        }
        final LensShadingMap map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        final boolean hasMap = map != null && map.getColumnCount() > 1 && map.getRowCount() > 1;
        if (hasMap) SHADING.put(cameraId, map);
        // P212e: the store (a map under the light of its own neutral only)
        final int[] o = offer(cameraId);
        if (neutral == null || o == null || !OppoPlainState.due(cameraId, o[0], o[1], now)) return;
        float[] gains = null;
        if (hasMap) {
            gains = new float[map.getGainFactorCount()];
            map.copyGainFactors(gains, 0);
        }
        float[] t = null;
        if (ispTransform) {
            t = new float[9];
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) t[r * 3 + c] = transform.getElement(c, r).floatValue();
        }
        OppoPlainState.remember(cameraId, o[0], o[1], neutral[0], neutral[2], t, gains, hasMap ? map.getColumnCount() : 0,
                hasMap ? map.getRowCount() : 0, now);
    }

    /** P212e (W1): the stored plain neutral of the camera's native size (null: none, or the store is off). */
    @Nullable
    static float[] storedNeutral(@Nullable String cameraId) {
        final int[] o = offer(cameraId);
        return o == null ? null : OppoPlainState.neutral(cameraId, o[0], o[1]);
    }

    /** P212e (W1): the stored plain map of the camera's native size nearest to the light of {@code neutral} (null: none). */
    @Nullable
    static OppoPlainState.Shading storedShading(@Nullable String cameraId, @Nullable float[] neutral) {
        final int[] o = offer(cameraId);
        return o == null ? null : OppoPlainState.shading(cameraId, o[0], o[1], neutral);
    }

    static boolean identity(ColorSpaceTransform t) {
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
            if (Math.abs(t.getElement(c, r).floatValue() - (r == c ? 1f : 0f)) > 1e-4f) return false;
        return true;
    }

    /**
     * The own neutral of the camera's frames, smoothed in the log domain (a new session starts from its first measurement). P212e:
     * the P192 law, used only with scam_dev.txt "oppo_wb_filter 0" (else OppoNativeWb).
     */
    static void measuredNeutral(String cameraId, float r, float b) {
        if (cameraId == null || !(r > 0.05f && r < 20f && b > 0.05f && b < 20f)) return;
        final float[] last = NEUTRALS.get(cameraId);
        if (last == null) { NEUTRALS.put(cameraId, new float[]{r, 1f, b}); return; }
        final float k = 0.3f;
        NEUTRALS.put(cameraId, new float[]{(float) Math.exp((1 - k) * Math.log(last[0]) + k * Math.log(r)), 1f,
                (float) Math.exp((1 - k) * Math.log(last[2]) + k * Math.log(b))});
    }

    /**
     * P212f (W2 = M3): a hand-set white balance (P201) is the own neutral as it is, not through the k 0.3 average (about 10 frames
     * to arrive after a Kelvin change, the ring frames inside them carried the blend): complete() fills exactly the inverse of its
     * gains from the next frame on. Only the P192 law ("oppo_wb_filter 0") reads it; the filter holds the hand-set one itself.
     */
    static void setNeutral(String cameraId, float r, float b) {
        if (cameraId == null || !(r > 0.05f && r < 20f && b > 0.05f && b < 20f)) return;
        NEUTRALS.put(cameraId, new float[]{r, 1f, b});
    }

    static void forgetNeutral(String cameraId) {
        if (cameraId != null) NEUTRALS.remove(cameraId);
    }

    /**
     * The own neutral of the camera's native frames, else its last plain session's, else (P212e, W1) its stored plain one (null:
     * none yet). P212e (W3): the own neutral is OppoNativeWb's state (reset and seeded at begin), the P192 EMA only with
     * "oppo_wb_filter 0".
     */
    @Nullable
    static float[] neutral(String cameraId) {
        if (cameraId == null) return null;
        // P223: our corrections off - the camera's own white balance, i.e. its plain mode's neutral (the native mode has no AWB)
        if (!com.scamera.app.settings.PreferenceKeys.mosaicWbCorrections()) {
            final float[] plain = PLAIN_NEUTRALS.get(cameraId);
            return plain != null ? plain : storedNeutral(cameraId);
        }
        float[] own = OppoNativeWb.on() ? OppoNativeWb.neutral(cameraId) : null;
        if (own == null) own = NEUTRALS.get(cameraId); // with the filter on, begin() cleared it (only the P192 law writes it)
        if (own != null) return own;
        final float[] plain = PLAIN_NEUTRALS.get(cameraId);
        return plain != null ? plain : storedNeutral(cameraId);
    }

    /** P212e: the held AWB gains of a native result (the HAL keeps the plain session's last ones, or its init values). */
    private static final CaptureResult.Key<float[]> AWB_R = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlRGain", float[].class);
    private static final CaptureResult.Key<float[]> AWB_G = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlGGain", float[].class);
    private static final CaptureResult.Key<float[]> AWB_B = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlBGain", float[].class);
    private static final CaptureResult.Key<int[]> AWB_CCT = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlCCT", int[].class);
    /** P212e: the raw keys behind the synthesized lens shading map (the gains, the map size the result carries). */
    private static final CaptureResult.Key<float[]> LSM_GAINS = new CaptureResult.Key<>("android.statistics.lensShadingMap", float[].class);
    private static final CaptureResult.Key<android.util.Size> LSM_SIZE = new CaptureResult.Key<>("android.lens.info.shadingMapSize", android.util.Size.class);

    @Nullable
    private static <T> T vendor(CaptureResult result, CaptureResult.Key<T> key) {
        try {
            return result.get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {R, G, B} of the result's held AWB gains, then its CCT (0: none); null without them. */
    @Nullable
    static float[] heldAwb(CaptureResult result) {
        final float[] r = vendor(result, AWB_R), g = vendor(result, AWB_G), b = vendor(result, AWB_B);
        final int[] cct = vendor(result, AWB_CCT);
        if (r == null || b == null || r.length == 0 || b.length == 0) return null;
        return new float[]{r[0], g == null || g.length == 0 ? 1f : g[0], b[0], cct == null || cct.length == 0 ? 0f : cct[0]};
    }

    /**
     * A native result gets what the mode does not report, before anything reads it: the own neutral (and the white-balance gains it
     * gives) for its NaN neutral, the plain session's colour transform for the identity one, the plain session's shading map
     * when it has none. Returns what was filled, for the log.
     * P212e (W1): with nothing in memory (a cold start straight into FULLRES) the stored plain transform and map (OppoPlainState;
     * the map through the raw keys behind the synthesized one - no hidden constructor; Parameters, the viewfinder and the meter
     * read the stored floats themselves when even that does not take); the first result of a session without any neutral seeds
     * the white balance from the held AWB gains (not the 3745 K init signature) or the curve middle; a neutral is written only
     * when it is a finite white point (never NaN), else the HAL's stays and the photo keeps its own estimate.
     */
    static String complete(String cameraId, CaptureResult result) {
        if (cameraId == null || result == null) return "";
        final StringBuilder filled = new StringBuilder();
        float[] own = neutral(cameraId);
        if (own == null) {
            final float[] held = heldAwb(result);
            if (OppoNativeWb.on()) {
                final String how = OppoNativeWb.lateSeed(cameraId, held, held == null ? 0 : (int) held[3],
                        android.os.SystemClock.elapsedRealtime());
                if (how != null) {
                    com.scamera.app.util.Log.i("SCAM_CAPTURE", "native white balance camera " + cameraId + ": " + how);
                    own = OppoNativeWb.neutral(cameraId);
                }
            } else if (held != null) {
                own = OppoNativeWb.heldNeutral(held, (int) held[3]);
                if (own != null) filled.append(" (held AWB ").append((int) held[3]).append(" K)");
            }
        }
        final Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        final boolean missing = n == null || n.length < 3 || !(n[0].floatValue() > 0f && n[1].floatValue() > 0f && n[2].floatValue() > 0f)
                || !OppoNativeWb.valid(new float[]{n[0].floatValue(), n[1].floatValue(), n[2].floatValue()});
        if (missing && OppoNativeWb.valid(own)) {
            final float r = own[0] / own[1], b = own[2] / own[1];
            if (CameraReflectionApi.setCached(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT, new Rational[]{rational(r), new Rational(1, 1), rational(b)}, result)
                    & CameraReflectionApi.setCached(CaptureResult.COLOR_CORRECTION_GAINS, new RggbChannelVector(1f / r, 1f, 1f, 1f / b), result))
                filled.append(String.format(Locale.ROOT, " neutral %.3f / %.3f", r, b));
        }
        final ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        ColorSpaceTransform plain = TRANSFORMS.get(cameraId);
        boolean storedTransform = false;
        if (plain == null && (transform == null || identity(transform))) {
            final int[] o = offer(cameraId);
            final float[] t = o == null ? null : OppoPlainState.transform(cameraId, o[0], o[1]);
            if (t != null) {
                final Rational[] e = new Rational[9];
                for (int i = 0; i < 9; i++) e[i] = new Rational(Math.round(t[i] * 10_000f), 10_000);
                plain = new ColorSpaceTransform(e);
                storedTransform = true;
            }
        }
        if (plain != null && (transform == null || identity(transform))) {
            if (CameraReflectionApi.setCached(CaptureResult.COLOR_CORRECTION_TRANSFORM, plain, result))
                filled.append(storedTransform ? " transform (stored)" : " transform");
        }
        final LensShadingMap shading = SHADING.get(cameraId);
        if (result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) == null) {
            if (shading != null && !PreferenceKeys.scamDevSwitch("oppo_shading_keys", true)) {
                // P192 (scam_dev.txt "oppo_shading_keys 0"): the synthesized key's setter - it stores the size as rows x columns,
                // so the photos read the 17 x 13 map back as 13 x 17 (log31 23:10:54 / 23:12:58 / 23:14:42, log17, log-2026-10-17)
                CameraReflectionApi.setCached(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP, shading, result);
                // the map is a synthesized key: only a read-back shows whether it took
                if (result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) != null)
                    filled.append(" shading ").append(shading.getColumnCount()).append('x').append(shading.getRowCount());
                else filled.append(" (shading map not settable)");
            } else if (shading != null) {
                // P212e: through the raw keys, read back in the plain map's own orientation
                final float[] gains = gainsOf(shading);
                filled.append(putShading(result, gains, shading.getColumnCount(), shading.getRowCount())
                        ? " shading " + shading.getColumnCount() + "x" + shading.getRowCount() : " (shading map not settable)");
            } else {
                final OppoPlainState.Shading stored = storedShading(cameraId, own);
                if (stored != null) filled.append(" shading ").append(stored)
                        .append(putShading(result, stored.gains, stored.cols, stored.rows) ? " (stored)" : " (stored, read from the store)");
            }
        }
        return filled.toString();
    }

    /** P212e: the last in-memory plain map and its gains (copyGainFactors order), copied once per map. */
    private static volatile Object[] lastGains;

    private static float[] gainsOf(LensShadingMap map) {
        final Object[] last = lastGains;
        if (last != null && last[0] == map) return (float[]) last[1];
        final float[] g = new float[map.getGainFactorCount()];
        map.copyGainFactors(g, 0);
        lastGains = new Object[]{map, g};
        return g;
    }

    /**
     * P212e (W1): a shading map ({@code cols} x {@code rows} points, copyGainFactors order) into a result through the raw keys behind
     * the synthesized one - the gains and the map size - and read back: true only when it reads back as {@code cols} x
     * {@code rows}. The size is tried as (columns, rows), then the other way round; one that reads back wrong is taken out again
     * (nothing then reads a transposed map: Parameters, the viewfinder and the meter fall back to the stored floats). The
     * synthesized key's own setter writes the size as rows x columns: every OPPO native photo filled by it was developed with
     * the plain 17 x 13 map read as 13 x 17 ('lens shading map 13x17' in the pipeline, a plain photo's is '17x13').
     */
    static boolean putShading(CaptureResult result, float[] gains, int cols, int rows) {
        if (result == null || gains == null || cols < 2 || rows < 2 || gains.length < 4 * cols * rows) return false;
        final float[] copy = java.util.Arrays.copyOf(gains, 4 * cols * rows);
        boolean set = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            final android.util.Size size = attempt == 0 ? new android.util.Size(cols, rows) : new android.util.Size(rows, cols);
            if (!CameraReflectionApi.setCached(LSM_SIZE, size, result) || !CameraReflectionApi.setCached(LSM_GAINS, copy, result)) break;
            set = true;
            LensShadingMap back;
            try { back = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP); } catch (RuntimeException e) { back = null; }
            if (back != null && back.getColumnCount() == cols && back.getRowCount() == rows) return true;
        }
        if (set) CameraReflectionApi.setCached(LSM_GAINS, null, result);
        return false;
    }

    /** P212e (W3): {@link #complete} for a ring frame or a still - its white balance is recorded for the photo (OppoNativeWb.fuse). */
    static String completeFrame(String cameraId, CaptureResult result) {
        final String filled = complete(cameraId, result);
        if (cameraId != null && result != null && OppoNativeWb.on())
            OppoNativeWb.record(cameraId, result.get(CaptureResult.SENSOR_TIMESTAMP), android.os.SystemClock.elapsedRealtime());
        return filled;
    }

    static Rational rational(float v) {
        return new Rational(Math.round(v * 1_000_000f), 1_000_000);
    }

    /** Tests: a new process (nothing remembered, no session, no offers). */
    static void resetForTest() {
        OFFER.clear();
        LINES.clear();
        TRANSFORMS.clear();
        SHADING.clear();
        NEUTRALS.clear();
        PLAIN_NEUTRALS.clear();
        PLAIN_AT.clear();
        lastGains = null;
        camera = null;
        exposure = null;
    }

    /** Tests: what a camera offers ({width, height, fps, modes...}), as learn() would find it. */
    static void offerForTest(String cameraId, int[] offer) {
        final int[][] rows = new int[Math.max(0, offer.length - 3)][];
        for (int i = 3; i < offer.length; i++) rows[i - 3] = new int[]{offer[i], offer[0], offer[1], offer[2], block(offer[0], offer[1])};
        OFFER.put(cameraId, new Offer(rows, new int[0][]));
    }
}
