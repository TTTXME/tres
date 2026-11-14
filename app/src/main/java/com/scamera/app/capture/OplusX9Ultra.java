package com.scamera.app.capture;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Lang;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P689 (owner, 2026-11-14: «Изучи отчёт по X9 Ultra и реализуй фулрез 50/200 без ремозаика без рута»): the OPPO Find X9 Ultra
 * (CPH2841 / PMA110 / PMA120, OP627CL1, SM8850, Android 16) takes its FULLRES 50 MP - the sensor's own Quad 2x2, no remosaic, no
 * root - by the X9 Ultra full-size recipe, run by our recipe engine (FullResRecipePlan) from the size switch «50 Мп».
 * <p>
 * The owner's SCAM TEST report of 2026-10-11 (CPH2841_16.0.10.602(EX01), no root): every camera lists
 * com.oplus.engineercamera.agingtest.mode.select and com.oplus.is.sdk.camera.package among its session keys; its Java
 * characteristics list no vendor key (as the OnePlus 16's: the table is read by its name, 0x80090000 here). The cameras
 * (com.oplus.sensor name, sensor-mode table, com.oplus.sensor.qcfafactor):
 * <ul>
 * <li>2 - lighthousemain, the 1x 200 MP (4x4): row 0 16320x12256@10, the 50 MP rows 5 / 6 / 12 / 13 / 44 / 46 8160x6128@30 and 45
 * @21, public RAW 4080x3064;</li>
 * <li>3 - lighthousewide, the 0.6x 50 MP (2x2): rows 0 / 5 / 9 8192x6144@15, public RAW 4096x3072;</li>
 * <li>4 - lighthousetele, the 3x 200 MP (4x4): row 0 16384x12288@7, the 50 MP rows 7 / 8 / 15 / 39 8192x6144@30 and 40 @15;</li>
 * <li>5 - lighthouseultratele, the 10x 50 MP (2x2): rows 0 / 25 8192x6144@15;</li>
 * <li>1 - lighthousefront, 50 MP (2x2): rows 0 / 22 8192x6144@15; 0 - the logical rear camera (main's table).</li>
 * </ul>
 * Its CameraHWConfiguration.config (world-readable, in the report) names each full-size QBC row - OemCamSeamlessSettingMap caps
 * 0x0030 «full size qbc»: wide 5, front 22, tele 8 and ultra-tele 25 in the photo operation mode 0x8001 - and the only 200 MP row of
 * each 200 MP sensor, row 0, as caps 0x0020 «full size hwRemosaic»; [OemSupportedCustomRAWFullSizes] lists 8160x6128 for the main
 * and 8192x6144 for the others.
 * <p>
 * The X9 Ultra full-size recipe (a camera app's profiles for this phone, proven on it without root): Camera2, two RAW10 streams -
 * the binned viewfinder RAW (4080x3064 main, 4096x3072 the others) and the photo RAW at the full size, CPU-read usage 0x3; the
 * session parameters com.oplus.is.sdk.camera.package = 1 (byte) and com.oplus.is.from.extension = 1 (int32); the sensor mode by
 * agingtest.mode.select (int32) on the still requests ONLY - on the viewfinder's RAW it gave CamxResultExtBindFailed and lost
 * buffers (that is what our native OPPO route, OppoNativeFullRes, does: the mode on the session and every request, one RAW
 * stream), and two full-size streams did not run either; the modes 5 (0.6x), 13 (1x), 8 (3x), 25 (10x); the photo a Quad 2x2; up to
 * 3 frames a merge. So on this phone the native route offers nothing ({@link OppoNativeFullRes#learn}) and «50 Мп» runs this
 * recipe ({@link FullResRecipePresets#x9Ultra}); its first still is judged on the pixels (FullResTruth, RAW10 too: a doubled or a
 * corner-binned frame is no FULLRES) and the merge measures the mosaic block itself.
 * <p>
 * 200 MP stays off: each 200 MP sensor has row 0 alone at its full size, the config names it «full size hwRemosaic», and the
 * recipe's own 200 MP profile works only with root and a patched camera.qcom.sm8850.so (MCTFENode::FetchSensorInfo's IPP input
 * width limit raised to 16384 - «IPP Path input width ... Out-of-limit» otherwise); no other row, stream or key in the report
 * reaches it. The size sheet gets no «200 Мп» ({@link OppoNativeFullRes#twoSizes} is false here) and the camera's learn line says why.
 * <p>
 * The ISP stream (owner, 2026-11-14: «Для рабочего фокуса на 9 ультра наверняка нужен либо ISP off, либо ISP FAKE OFF»): the
 * report cannot prove which one keeps the HAL's focus (its live results ran with an ISP stream); the recipe ran with none. «Поток
 * ISP» Auto tries fake off first (the stream in the session, in no request: the Find X7 Ultra's 50 MP focus, P276) and, when that
 * session fails, off (the recipe as proven) - remembered per camera and firmware ({@link #ladder}); a set value (On, Off, fake off)
 * is the user's and runs alone.
 * <p>
 * P709 (owner's log of 2026-11-14, CPH2841 CP2A.260605.016): P689's sessions - a RAW_SENSOR viewfinder beside the RAW10 photo
 * stream, the package spoofed - were configured on cameras 3, 4 and 5 (and failed earlier on 2), gave no frame, and the camera
 * provider died 1.3-1.8 s later in every variant (error 4, the devices gone for seconds). What differed from the recipe: its two
 * streams are both RAW10 (FullResRecipePlan.viewfinderRawFormat now gives the viewfinder the capture's format), and it ran under
 * its own package (this app tells the Oplus manager the telephotos' package on OPPO, and the session's
 * com.oplus.is.sdk.camera.package then made it a camera-unit session). The variants now are the ISP stream and the package
 * ({@link #ladder}); a variant the camera dies in turns 50 MP off ({@link #fiftyStaysOn}).
 * <p>
 * P709, the rows re-checked against the owner's SCAM TEST report (Android 16, BP2A.250605.015; the sensor-mode tables of the
 * Android 17 firmware CP2A.260605.016 in his log of 2026-11-14 are the same, row for row, on cameras 0-5) - its
 * CameraHWConfiguration.config, OemCamSeamlessSettingMap, caps 0x0030 «full size qbc»: wide [0x0030][-1] = 5, tele
 * [0x0030][0x8001] = 8 (its [-1] entry says 19, a 4096x2560 row of the table: the photo operation mode's 8 is the full size),
 * ultra-tele [0x0030][0x8001] = 25 (no [-1] entry), front [0x0030][-1] = 22, main [0x0030][-1] = 6 - the recipe's 13 for the main
 * is named nowhere in the config; yet the owner's working rows (2026-11-14, with ISP auto) are 5 / 12 / 13 for the main, not 6, so
 * the main tries 13, 12, 5 ({@link #LENSES}). The 200 MP sensors' [0x0020] «full size hwRemosaic» is row 0. The
 * full-size QBC entries of the tele and ultra-tele are those of the stock photo operation mode 0x8001: the last variant runs the
 * session in it ({@link #PHOTO_OP_MODE}). None of the rows can have caused the crashes of the 2026-11-14 log: the mode key goes on
 * the stills only, and no still was sent (the proof still waits for the first viewfinder frame, which never came).
 */
public final class OplusX9Ultra {
    private OplusX9Ultra() { }

    static final String TAG = "SCAM_CAPTURE";
    /** The models of the X9 Ultra (Build.MODEL; Build.DEVICE OP627CL1). */
    static final String[] MODELS = {"CPH2841", "PMA110", "PMA120"};
    static final String SDK_PACKAGE = "com.oplus.is.sdk.camera.package", FROM_EXTENSION = "com.oplus.is.from.extension";
    /** «Поток ISP» values (CaptureController.ispSurface): 0 Auto, 1 On, 2 Off, 3 fake off; the trial ids of the variants. */
    static final int ISP_AUTO = 0, ISP_ON = 1, ISP_OFF = 2, ISP_FAKE = 3;
    /**
     * The route revision of the trials' stamp (with Build.FINGERPRINT: a firmware update tries the variants again). P709: 2 - the
     * variants of P689 (r1: a RAW_SENSOR viewfinder, the package spoofed) are not those of now; their failures do not count.
     */
    static final int TRIALS = 2;
    /** The most frames one merge takes on this route (the recipe's mergeFrameLimit). */
    static final int FRAMES = 3;

    /** A lens of the recipe. */
    static final class Lens {
        final String camera, name, sensor;
        /** The full-size row (the gate's, the lens page's preset): {@link #rows}[0]. */
        final int mode, width, height, block;
        /** P709: the rows the stills may ask for, in the order tried (the full-size native Quad row first). */
        final int[] rows;
        /** In the recipe as proven on the phone; otherwise taken from the camera's own full-size QBC row, not run yet. */
        final boolean proven;

        Lens(String camera, String name, String sensor, int[] rows, int width, int height, boolean proven) {
            this.camera = camera;
            this.name = name;
            this.sensor = sensor;
            this.rows = rows.clone();
            this.mode = rows[0];
            this.width = width;
            this.height = height;
            this.block = 2;
            this.proven = proven;
        }
    }

    /**
     * The 50 MP lenses: the recipe's four rear ones, and the front's full-size QBC row (22, OemCamSeamlessSettingMap
     * lighthousefront[0x0030]) the recipe does not name - offered as not proven, judged like the others.
     * <p>
     * P709, the rows (owner, 2026-11-14, working with ISP auto: «Основа - mode 5/12/13; Ширик - mode 5/9; 3х теле - mode 8/15/39;
     * 10х теле - mode 0/25. Вот эти сенсормоды работают с авто исп»): each lens tries the owner's working rows of its full size,
     * the full-size native Quad row first - the one the camera's CameraHWConfiguration.config names «full size qbc» (SCAM TEST
     * report: wide [0x0030][-1] = 5, tele [0x0030][0x8001] = 8, ultra-tele [0x0030][0x8001] = 25). The main's config row, 6, is not
     * among the owner's working rows; of his 5 / 12 / 13 (all 8160x6128@30) the recipe's 13 goes first, then 12, then 5 (the
     * config's «izoom qbc lbmf 2exp» row of the photo mode: an in-sensor zoom row, so last); the 10x's row 0 (8192x6144@15, no
     * config name) after its 25. The proof still judges each (FullResTruth).
     */
    static final Lens[] LENSES = {
            new Lens("3", "0.6x", "lighthousewide", new int[]{5, 9}, 8192, 6144, true),
            new Lens("2", "1x", "lighthousemain", new int[]{13, 12, 5}, 8160, 6128, true),
            new Lens("4", "3x", "lighthousetele", new int[]{8, 15, 39}, 8192, 6144, true),
            new Lens("5", "10x", "lighthouseultratele", new int[]{25, 0}, 8192, 6144, true),
            new Lens("1", "front", "lighthousefront", new int[]{22}, 8192, 6144, false),
    };

    /** P709: the rows of a camera's lens its table has at the lens's size (all of them while the table is unknown). */
    private static final Map<String, int[]> ROWS = new ConcurrentHashMap<>();

    /** P709: {@code lens}'s rows that {@code table} (null: unknown) lists at the lens's full size, in the lens's order. */
    static int[] usableRows(Lens lens, @Nullable int[] table) {
        if (table == null) return lens.rows.clone();
        int n = 0;
        final int[] out = new int[lens.rows.length];
        for (int r : lens.rows) {
            final int[] e = SensorModeBand.modeEntry(table, r);
            if (e != null && e[0] == lens.width && e[1] == lens.height) out[n++] = r;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** P709: the rows the camera's variants ask for (learned; the lens's own before). */
    static int[] rows(@Nullable String cameraId) {
        final int[] learned = cameraId == null ? null : ROWS.get(cameraId);
        if (learned != null) return learned;
        final Lens lens = lens(cameraId);
        return lens == null ? new int[0] : lens.rows.clone();
    }

    /** The 200 MP sensors' full size (row 0 alone, «full size hwRemosaic»): {width, height} of cameras 2 and 4. */
    @Nullable
    static int[] size200(@Nullable String camera) {
        if ("2".equals(camera)) return new int[]{16320, 12256};
        if ("4".equals(camera)) return new int[]{16384, 12288};
        return null;
    }

    /** The phone is an OPPO Find X9 Ultra. */
    public static boolean phone() {
        return phone(android.os.Build.MANUFACTURER, android.os.Build.MODEL);
    }

    static boolean phone(@Nullable String manufacturer, @Nullable String model) {
        if (manufacturer == null || !manufacturer.trim().equalsIgnoreCase("OPPO") || model == null) return false;
        for (String m : MODELS) if (m.equalsIgnoreCase(model.trim())) return true;
        return false;
    }

    /** The route runs on this phone (scam_dev.txt "x9u_fullres 0": no FULLRES on the X9 Ultra at all). */
    public static boolean on() {
        return phone() && PreferenceKeys.scamDevSwitch("x9u_fullres", true);
    }

    @Nullable
    static Lens lens(@Nullable String camera) {
        if (camera != null) for (Lens l : LENSES) if (l.camera.equals(camera)) return l;
        return null;
    }

    // ---------------------------------------------------------------- the gate

    /** Per camera: null - the recipe can run on it; else why not (learned from its characteristics). */
    private static final Map<String, String> GATE = new ConcurrentHashMap<>();
    private static final Map<String, String> LINES = new ConcurrentHashMap<>();
    private static final String OK = "";

    /**
     * Why the recipe cannot run on a camera ({@code modeKey} / {@code packageKey}: its session keys list agingtest.mode.select /
     * is.sdk.camera.package; {@code table}: its sensor-mode table, null when unreadable - the recipe's numbers stand then); null:
     * it can. A table whose recipe row has another size is another firmware's: no guess at its rows.
     */
    @Nullable
    static String gate(@Nullable Lens lens, boolean modeKey, boolean packageKey, @Nullable int[] table) {
        if (lens == null) return "no lens of the X9 Ultra full-size recipe";
        if (!modeKey) return "the camera lists no " + OppoNativeFullRes.MODE_KEY + " session key";
        if (!packageKey) return "the camera lists no " + SDK_PACKAGE + " session key";
        if (table != null) {
            final int[] e = SensorModeBand.modeEntry(table, lens.mode);
            if (e == null || e[0] != lens.width || e[1] != lens.height)
                return "row " + lens.mode + " of this firmware's table is " + (e == null ? "missing" : e[0] + "x" + e[1])
                        + ", not " + lens.width + "x" + lens.height;
        }
        return null;
    }

    /** P689: learned from what the camera lists (OppoNativeFullRes.learn on this phone); one log line a camera. */
    static String learn(String cameraId, boolean modeKey, boolean packageKey, @Nullable int[] table) {
        final Lens lens = lens(cameraId);
        final String why = gate(lens, modeKey, packageKey, table);
        GATE.put(cameraId, why == null ? OK : why);
        if (lens != null) ROWS.put(cameraId, usableRows(lens, table));
        final StringBuilder b = new StringBuilder("OPPO Find X9 Ultra camera ").append(cameraId);
        if (lens != null) b.append(" (").append(lens.name).append(", ").append(lens.sensor).append(')');
        b.append(": mode key ").append(modeKey).append(", ").append(SDK_PACKAGE).append(' ').append(packageKey).append(", table ")
                .append(table == null ? "unreadable (the recipe's rows stand)" : table[0] + " rows");
        if (why == null) b.append(String.format(Locale.ROOT, "; FULLRES 50 MP by the X9 Ultra full-size recipe: rows %s (first: %d) %dx%d, Quad 2x2,"
                        + " RAW10 usage 0x3 beside the binned RAW10 viewfinder, %s=1 (byte) and %s=1 (int32) on the session, the row on the stills"
                        + " only%s", java.util.Arrays.toString(rows(cameraId)), lens.mode, lens.width, lens.height, SDK_PACKAGE, FROM_EXTENSION,
                lens.proven ? "" : " (the camera's own full-size QBC row: not in the recipe, not proven yet)"));
        else b.append("; no FULLRES: ").append(why);
        final int[] large = size200(cameraId);
        if (large != null) b.append(String.format(Locale.ROOT, "; 200 MP %dx%d not offered: row 0 is its only row of that size"
                + " («full size hwRemosaic» in the camera's config), and the 200 MP stills need root and a patched camera library", large[0], large[1]));
        b.append(" (native OPPO route off on this phone: the mode on the viewfinder's RAW breaks it, P689)");
        LINES.put(cameraId, b.toString());
        com.scamera.app.util.Log.i(TAG, b.toString());
        return b.toString();
    }

    /** P689: the camera's learn line (null before it is learned), said again when a session of it opens. */
    @Nullable
    public static String learnLine(@Nullable String cameraId) {
        return cameraId == null ? null : LINES.get(cameraId);
    }

    /** The recipe may run on the camera (learned, its gate passed). */
    static boolean gated(@Nullable String cameraId) {
        return cameraId != null && OK.equals(GATE.get(cameraId));
    }

    /** P689: why «200 Мп» is not offered on this phone (the owner's message, the size sheet's explanation). */
    public static String no200Reason() {
        return Lang.t("200 Мп без рута на X9 Ultra недоступен: у сенсора 200 Мп лишь один режим полного размера (0), и это аппаратный ремозаик камеры;"
                        + " снимки 200 Мп этот телефон отдаёт только с рутом и изменённой библиотекой камеры.",
                "200 MP without root is not available on the X9 Ultra: the 200 MP sensor has one full-size mode (0), the camera's hardware"
                        + " remosaic; this phone gives 200 MP stills only with root and a modified camera library.");
    }

    // ---------------------------------------------------------------- the variants

    /** The «Поток ISP» the last session of this phone asked (CaptureController.ispSurface), for the size sheet's offer. */
    private static volatile int ispSetting = ISP_AUTO;

    /**
     * P709: a variant's id - its «Поток ISP» ({@link #isp}, the ones digit) and the package the Oplus camera manager is told at the
     * camera's open ({@link #ownPackage}): {@link #OWN} the app's own (no package spoof, as the recipe ran), {@link #APP} what the
     * app tells it otherwise (on OPPO the telephotos' package, OppoNativeFullRes.TELE_PACKAGE, CameraPackageSpoofSettings). Every
     * variant runs the binned RAW viewfinder in RAW10 beside the RAW10 photo stream (FullResRecipePlan.viewfinderRawFormat).
     */
    static final int OWN = 10, APP = 20;
    /**
     * P709: the hundreds digit of a variant: the session in the stock photo operation mode 0x8001 (OemCamSeamlessSettingMap keys
     * the tele's and ultra-tele's full-size QBC rows by it), not the regular 0.
     */
    static final int PHOTO = 100, PHOTO_OP_MODE = 0x8001;
    /** P709: the thousands of a variant's id: the row its stills ask for (id = row * ROW + the variant's kind). */
    static final int ROW = 1000;
    static final int FAKE_OWN = OWN + ISP_FAKE, OFF_OWN = OWN + ISP_OFF, ON_OWN = OWN + ISP_ON,
            FAKE_APP = APP + ISP_FAKE, OFF_APP = APP + ISP_OFF, ON_APP = APP + ISP_ON, OFF_OWN_PHOTO = PHOTO + OFF_OWN;

    /** P709: the id of the variant {@code kind} (ISP stream, package, operation mode) with the stills' {@code row}. */
    static int id(int row, int kind) {
        return row * ROW + kind % ROW;
    }

    /** P709: the row a variant's stills ask for. */
    static int row(int variant) {
        return variant / ROW;
    }

    /** The «Поток ISP» of a variant (1 On, 2 Off, 3 fake off). */
    static int isp(int variant) {
        return variant % 10;
    }

    /** The variant runs under the app's own package (no package spoof at the camera's open). */
    static boolean ownPackage(int variant) {
        return variant % 100 / 10 == OWN / 10;
    }

    /** P709: the session operation mode of a variant (0: regular; {@link #PHOTO_OP_MODE} for the {@link #PHOTO} variants). */
    static int operationMode(int variant) {
        return variant % ROW >= PHOTO ? PHOTO_OP_MODE : 0;
    }

    /**
     * P709: the variants a camera's FULLRES tries, in order ({@code spoofing}: the app tells the Oplus camera manager another
     * package - without it an {@link #APP} variant is its {@link #OWN} one; {@code rows}: the lens's rows, the full-size one first).
     * <p>
     * The owner's log of 2026-11-14 (CPH2841, CP2A.260605.016, build e4d84fb7): P689's two variants - fake off and off, both with
     * a RAW_SENSOR viewfinder beside the RAW10 photo stream, the package spoofed - configured on every camera, gave no frame and
     * the camera provider died 1.3-1.8 s later (error 4). The owner the same day: his rows work with «Поток ISP» Auto (the ISP
     * stream in the session and in the repeating request, the app as it runs: on OPPO the package spoofed). So every variant runs
     * the recipe's two RAW10 streams, and «Поток ISP» Auto tries: the ISP stream on with the app's package - each of the owner's
     * rows, the full-size one first; then on the full-size row the ISP stream on under the own package, fake off (scam_dev
     * "x9u_isp_fake_first 0": not), off under the own package (the recipe exactly), off with the app's package, and off under the
     * own package in the stock photo operation mode 0x8001. A set «Поток ISP» runs its own value: the app's package on each row,
     * then on the full-size row the own package and the own package in 0x8001.
     */
    static int[] ladder(int ispSurface, boolean spoofing, int[] rows) {
        final java.util.List<Integer> all = new java.util.ArrayList<>();
        if (rows.length == 0) return new int[0];
        final int full = rows[0];
        if (ispSurface == ISP_ON || ispSurface == ISP_OFF || ispSurface == ISP_FAKE) {
            for (int r : rows) all.add(id(r, APP + ispSurface));
            all.add(id(full, OWN + ispSurface));
            all.add(id(full, PHOTO + OWN + ispSurface));
        } else {
            for (int r : rows) all.add(id(r, ON_APP));
            all.add(id(full, ON_OWN));
            if (PreferenceKeys.scamDevSwitch("x9u_isp_fake_first", true)) all.add(id(full, FAKE_OWN));
            all.add(id(full, OFF_OWN));
            all.add(id(full, OFF_APP));
            all.add(id(full, OFF_OWN_PHOTO));
        }
        final java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        for (int v : all) out.add(spoofing || ownPackage(v) ? v : v - (APP - OWN)); // no spoof: the app's package is the own one
        final int[] ids = new int[out.size()];
        int i = 0;
        for (int v : out) ids[i++] = v;
        return ids;
    }

    /** The recipe's viewfinder of a variant. */
    static FullResRecipe.Preview preview(int variant) {
        final int isp = isp(variant);
        return isp == ISP_ON ? FullResRecipe.Preview.BOTH : isp == ISP_FAKE ? FullResRecipe.Preview.FAKE : FullResRecipe.Preview.RAW;
    }

    static String variantName(int variant) {
        final int isp = isp(variant);
        final String stream = isp == ISP_ON ? Lang.t("поток ISP вкл.", "ISP stream on")
                : isp == ISP_FAKE ? Lang.t("поток ISP фикт. откл.", "ISP stream fake off") : Lang.t("поток ISP откл.", "ISP stream off");
        if (variant < OWN) return stream; // P689's variants (a RAW_SENSOR viewfinder), named in older trials only
        return Lang.t("режим ", "mode ") + row(variant) + ", " + stream + (ownPackage(variant) ? Lang.t(", RAW10, без подмены пакета", ", RAW10, no package spoof")
                : Lang.t(", RAW10, с подменой пакета", ", RAW10, package spoofed"))
                + (operationMode(variant) != 0 ? Lang.t(", режим сессии 0x8001 (фото)", ", session mode 0x8001 (photo)") : "");
    }

    /** The first variant of {@code ladder} the trials do not name; -1: none left. */
    static int variant(int[] ladder, Set<Integer> failed) {
        for (int v : ladder) if (!failed.contains(v)) return v;
        return -1;
    }

    /** The app tells the Oplus camera manager another package than its own (OplusCameraPackageSpoof). */
    static boolean spoofing() {
        try {
            return com.scamera.app.capture.spoof.OplusCameraPackageSpoof.spoofing();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * P709: the «Поток ISP» of the module the camera opens with (its stored value, what SensorConfigInjector gives
     * CaptureController.ispSurface at the session); the last session's when it cannot be read.
     */
    static int ispSettingOf(@Nullable String cameraId) {
        try {
            final SharedPreferences p = prefs();
            if (p == null || cameraId == null) return ispSetting;
            final String key = com.scamera.app.settings.ModuleSensorSettings.prefix(
                    com.scamera.app.settings.ModuleSensorSettings.runtimeScope(cameraId)) + "ispsurface";
            final Object v = p.getAll().get(key);
            if (v == null) return ISP_AUTO;
            final int n = v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v).trim());
            return n >= ISP_AUTO && n <= ISP_FAKE ? n : ISP_AUTO;
        } catch (RuntimeException e) {
            return ispSetting;
        }
    }

    /**
     * P709: the camera about to open (ScameraApp's camera id, "logical-physical" or one id) runs an X9 Ultra FULLRES variant under
     * the app's own package: the package spoof (OplusCameraPackageSpoof, before every openCamera) tells the Oplus camera manager
     * the app's own package for this open. False off the X9 Ultra, without FULLRES chosen, for a camera without a lens of the
     * recipe or whose next variant keeps the package.
     */
    public static boolean ownPackageForOpen() {
        try {
            if (!on() || !PixelPro10.fullResOn()) return false;
            final String[] ids = String.valueOf(com.scamera.app.app.ScameraApp.getSettings().cameraId).split("-");
            final String camera = ids[ids.length - 1];
            if (lens(camera) == null || GATE.containsKey(camera) && !gated(camera)) return false;
            final int v = variant(ladder(ispSettingOf(camera), true, rows(camera)), failed(prefs(), camera, stamp()).keySet());
            final boolean own = v >= 0 && ownPackage(v);
            if (own) com.scamera.app.util.Log.i(TAG, "OPPO Find X9 Ultra camera " + camera + ": FULLRES variant «" + variantName(v)
                    + "» opens under the app's own package (no package spoof for this open, P709)");
            return own;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- the trials (per camera and firmware)

    /** Under the camera's OPPO trial keys (FullResTrials: «Попробовать снова» clears them, the open line dumps them). */
    static String base(String cameraId) {
        return OppoNativeFullRes.base(cameraId) + "_x9u";
    }

    static String stamp() {
        return FullResTrials.stamp(TRIALS);
    }

    static final String WORKED = "_ok";

    /** The variants that failed on this camera and firmware, with their reason codes (empty: none). */
    static Map<Integer, String> failed(@Nullable SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return new java.util.LinkedHashMap<>();
        try {
            final String key = base(cameraId) + FullResTrials.WHY;
            return FullResTrials.reasons(FullResTrials.counts(p, base(cameraId), key, stamp) ? FullResTrials.string(p, key) : null);
        } catch (RuntimeException e) {
            return new java.util.LinkedHashMap<>();
        }
    }

    /** A variant failed on the camera for {@code why} (this firmware). */
    static void noteFailed(@Nullable SharedPreferences p, String cameraId, int variant, @Nullable String why, String stamp) {
        if (p == null || cameraId == null) return;
        try {
            final String key = base(cameraId) + FullResTrials.WHY;
            final String stored = FullResTrials.counts(p, base(cameraId), key, stamp) ? FullResTrials.string(p, key) : null;
            final SharedPreferences.Editor e = FullResTrials.edit(p, base(cameraId), stamp)
                    .putString(key, FullResTrials.withReason(stored, variant, FullResTrials.code(why)));
            if (worked(p, cameraId, stamp) == variant) e.remove(base(cameraId) + WORKED);
            e.apply();
        } catch (RuntimeException ignored) {
        }
    }

    /** A variant gave a real full frame on the camera (this firmware). */
    static void noteWorked(@Nullable SharedPreferences p, String cameraId, int variant, String stamp) {
        if (p == null || cameraId == null) return;
        try {
            FullResTrials.edit(p, base(cameraId), stamp).putInt(base(cameraId) + WORKED, variant).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /** The variant that worked on the camera and firmware; -1: none yet. */
    static int worked(@Nullable SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return -1;
        try {
            final String key = base(cameraId) + WORKED;
            return FullResTrials.counts(p, base(cameraId), key, stamp) ? p.getInt(key, -1) : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Nullable
    private static SharedPreferences prefs() {
        try {
            return com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The variant the camera runs now under «Поток ISP» {@code ispSurface}; -1: none left (or no recipe here). */
    static int current(@Nullable String cameraId, int ispSurface) {
        if (!on() || !gated(cameraId)) return -1;
        return variant(ladder(ispSurface, spoofing(), rows(cameraId)), failed(prefs(), cameraId, stamp()).keySet());
    }

    /** P689: «50 Мп» on this camera (VivoSdkFullRes.offered through OppoNativeFullRes.anyLive). */
    public static boolean offered(@Nullable String cameraId) {
        return current(cameraId, ispSetting) >= 0;
    }

    /** P689: the camera has the recipe but every variant of it failed on this firmware (its «50 Мп» dimmed). */
    public static boolean exhausted(@Nullable String cameraId) {
        return on() && gated(cameraId) && current(cameraId, ispSetting) < 0;
    }

    /**
     * P689: the recipe the size switch runs on the camera now (FullResRecipeStore.active, the lens's own recipe switch off), with
     * its trial id; null when there is none ({@code ispSurface}: the session's «Поток ISP»).
     */
    @Nullable
    static FullResRecipeStore.Active active(@Nullable String slot, @Nullable String cameraId, @Nullable int[] table, int ispSurface) {
        ispSetting = ispSurface;
        if (slot == null || !PixelPro10.fullResOn()) return null;
        final int v = current(cameraId, ispSurface);
        if (v < 0) {
            if (on() && gated(cameraId)) com.scamera.app.util.Log.w(TAG, "OPPO Find X9 Ultra camera " + cameraId
                    + ": FULLRES 50 MP off on this firmware, every variant failed (" + failed(prefs(), cameraId, stamp()) + ")");
            return null;
        }
        final FullResRecipe recipe = FullResRecipePresets.x9Ultra(cameraId, table, preview(v), operationMode(v), row(v));
        if (recipe == null) return null;
        final SharedPreferences p = prefs();
        final int worked = worked(p, cameraId, stamp());
        // P709: the package the camera was opened with (OplusCameraPackageSpoof, ownPackageForOpen) - said, and a mismatch too
        final boolean spoofing = spoofing(), ownOpen = !spoofing || ownPackageOpened();
        com.scamera.app.util.Log.i(TAG, "OPPO Find X9 Ultra camera " + cameraId + ": FULLRES 50 MP by the recipe, variant «"
                + variantName(v) + "» (id " + v + ", «Поток ISP» " + (ispSurface == ISP_AUTO ? "Auto" : "set by the user") + ", ladder "
                + java.util.Arrays.toString(ladder(ispSurface, spoofing, rows(cameraId))) + ")"
                + (worked == v ? ", worked on this firmware before" : worked >= 0 ? ", «" + variantName(worked) + "» worked before" : ", not run on this firmware yet")
                + ", failed before: " + failed(p, cameraId, stamp()) + "; the camera opened under "
                + (ownOpen ? "the app's own package" : "the spoofed package")
                + (ownOpen != ownPackage(v) ? " - NOT the variant's (its «Поток ISP» changed after the open): the variant's package"
                + " comes with the next open" : "") + "; both RAW streams RAW10 (P689, P709)");
        return new FullResRecipeStore.Active(slot, recipe, -1, cameraId, v);
    }

    /** P709: the package the camera was last opened with is the app's own while the app spoofs it otherwise. */
    static boolean ownPackageOpened() {
        try {
            return com.scamera.app.capture.spoof.OplusCameraPackageSpoof.ownPackageOpened();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * P709: after a failed variant 50 MP stays chosen only when the camera is alive and another variant is left - the next session
     * runs it at once; a variant the camera died in (the X9 Ultra's provider restarts: its devices are gone for seconds) or none
     * left turns 50 MP off, so the camera reopens with its normal session and never runs a chain of dying sessions by itself: the
     * owner's next «50 Мп» tries the next variant (and «Попробовать снова» on the dimmed size starts them again).
     */
    static boolean fiftyStaysOn(String code, int next) {
        return next >= 0 && !FullResTrials.DIED.equals(code);
    }

    /** P689: the running variant failed: remembered for the camera and firmware; returns the owner's message. */
    static String failed(String cameraId, int variant, String why) {
        final SharedPreferences p = prefs();
        noteFailed(p, cameraId, variant, why, stamp());
        final int next = current(cameraId, ispSetting);
        final String code = FullResTrials.code(why);
        final boolean stays = fiftyStaysOn(code, next);
        if (!stays) PixelPro10.setFullRes(false); // P709: the normal session reopens, no automatic chain of dying sessions
        com.scamera.app.util.Log.w(TAG, "OPPO Find X9 Ultra camera " + cameraId + " FULLRES variant «" + variantName(variant) + "» (id " + variant
                + ") failed (" + why + "); next " + (next < 0 ? "none: 50 MP off on this firmware («Попробовать снова» tries again)"
                : "«" + variantName(next) + "» (id " + next + ")" + (stays ? " at once" : ", when 50 MP is chosen again: 50 MP turned off, the normal session reopens"))
                + " (P689, P709)");
        return message(variant, next, FullResTrials.phrase(code, false), stays);
    }

    /** P709: the owner's message of a failed variant ({@code reason}: its phrase; {@code stays}: {@link #fiftyStaysOn}). */
    static String message(int variant, int next, String reason, boolean stays) {
        if (next < 0)
            return Lang.t("FULLRES 50 Мп недоступен на этой камере (" + reason + "): все варианты испробованы. 50 Мп выключен; «Попробовать снова» — по нажатию на 50 Мп",
                    "FULLRES 50 MP is not available on this camera (" + reason + "): every variant was tried. 50 MP is off; a tap on 50 MP offers “Try again”");
        return stays
                ? Lang.t("FULLRES 50 Мп: вариант «" + variantName(variant) + "» не работает (" + reason + "); пробую «" + variantName(next) + "»",
                        "FULLRES 50 MP: the variant “" + variantName(variant) + "” does not work (" + reason + "); trying “" + variantName(next) + "”")
                : Lang.t("FULLRES 50 Мп: вариант «" + variantName(variant) + "» не работает (" + reason + "). 50 Мп выключен; выберите 50 Мп снова — будет «"
                        + variantName(next) + "»", "FULLRES 50 MP: the variant “" + variantName(variant) + "” does not work (" + reason
                        + "). 50 MP is off; choose 50 MP again to try “" + variantName(next) + "”");
    }

    /** P689: the running variant gave a real full frame (the proof or a shot): remembered for the camera and firmware. */
    static void worked(String cameraId, int variant) {
        final SharedPreferences p = prefs();
        if (worked(p, cameraId, stamp()) == variant) return;
        noteWorked(p, cameraId, variant, stamp());
        com.scamera.app.util.Log.i(TAG, "OPPO Find X9 Ultra camera " + cameraId + " FULLRES variant «" + variantName(variant)
                + "»: a real full frame, remembered for this firmware (P689)");
    }

    /**
     * P689: the camera's trials in words (the dimmed «50 Мп», FullResTrials.status) - «FULLRES 50 Мп: поток ISP фикт. откл. — нет
     * кадра; поток ISP откл. — камера отклонила сессию.»; null without a failed variant.
     */
    @Nullable
    static String status(@Nullable SharedPreferences p, @Nullable String cameraId, String stamp) {
        final Map<Integer, String> why = failed(p, cameraId, stamp);
        if (why.isEmpty()) return null;
        final StringBuilder b = new StringBuilder("FULLRES 50 ").append(Lang.t("Мп", "MP")).append(": ");
        boolean first = true;
        for (Map.Entry<Integer, String> e : why.entrySet()) {
            b.append(first ? "" : "; ").append(variantName(e.getKey())).append(" — ").append(FullResTrials.phrase(e.getValue(), false));
            first = false;
        }
        b.append('.');
        if (size200(cameraId) != null) b.append('\n').append(no200Reason()); // the 200 MP size the sheet does not offer
        return b.toString();
    }

    /** Tests: nothing learned, the «Поток ISP» of no session. */
    static void resetForTest() {
        GATE.clear();
        LINES.clear();
        ROWS.clear();
        ispSetting = ISP_AUTO;
    }
}
