package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P641 (owner, 2026-11-12: «На 17 ультра добавь работу DCG», «добавь возможность использовать DCG+LOFIC», «У 17у на сверхширике
 * вместо DCG должен быть DSG, его тоже активируй»): the dual-gain sensor rows of the Xiaomi 17 Ultra (nezha) without root. Nothing
 * here ran on a phone: every fact below is the owner's device report (SCAM-TEST 2026-10-11, firmware OS3.0.312.0.WPACNXM), the way of
 * asking is a ladder of variants each of which has to prove itself on the frames.
 * <p>
 * The report:
 * <ul>
 * <li>the vendor's own row map (aispsensormodesettings.json): the main camera's «dxg combine + binning» rows 4 / 15 and «DXGLofic»
 * 5 / 16, the 75 mm's «dxg combine + binning» 6 / 17, the ultra wide's binning 1 / 4, LBMF 2 / 5 and 50 MP 0 - its row 3
 * (4096x3072 at 30 fps) is the only 4:3 row the map leaves unnamed;</li>
 * <li>static supportedHDRmodes.HDRDCGModes, one int a mode = type | long bits &lt;&lt; 8 | short bits &lt;&lt; 16 (the types as
 * camxoverridesettings.txt lists them: 4 DCGDirect, 5 DCGPlusVS): main {4 / 14, 5 / 14 + 10}, 75 mm {4 / 14}, ultra wide
 * {4 / 12} - the ultra wide's dual gain (Samsung's DSG on the S5KJN5) is a 12-bit one;</li>
 * <li>a third-party session of the hidden logical camera 5 ran the main sensor's row 4 by the HAL's own decision (kernel: «res
 * index: 4 … capability: DCG»; results: current_mode 4, com.xiaomi.sensor.sensormode.modetype 18, dynamic black 1024 under the
 * declared 64), and its RAW_SENSOR buffer held packed MIPI RAW14 (the same payload P72 met on the 75 mm);</li>
 * <li>DCG + LOFIC is two reads on two streams in the vendor's own sessions (mivistreamformat.json LoficFormat [324, 37]: a RAW14
 * and a RAW10 stream; smartae «Lofic zsl 4ev0, 4ev-»). P641d: the ladder's first rung asks for the second stream too and merges
 * the pair once the frames prove it ({@link LoficPair}); the other rungs take the LOFIC row's 14-bit frame alone;</li>
 * <li>EnableHDRDCGMode, HDRModePreference, numHDRexposure and com.xiaomi.sessionparams.enableLofic are listed session keys of
 * cameras 2, 3 and 4; current_mode is a result key only (a value the app sends comes back as an echo: it proves nothing).</li>
 * </ul>
 * So a variant is proven by the depth of what arrives - a frame's dynamic black in other units than the declared one, a packed
 * RAW14 / RAW12 payload, samples above the declared white - never by current_mode. The first proven variant is remembered for this
 * firmware, a failed one is never asked again by itself (choosing the mode in the lens settings again clears the failures). All
 * variants failed: DCG + LOFIC falls back to DCG, DCG / DSG to the plain session.
 * <p>
 * scam_dev: {@code xiaomi17_dxg 0} hides it, {@code xiaomi17_dxg_row N} another row, {@code xiaomi17_dxg_variant N} one variant of
 * the ladder only, {@code xiaomi17_dxg_window N} the results a variant has to prove itself in.
 */
public final class XiaomiDualGain {
    private XiaomiDualGain() {}

    static final String TAG = "SCAM_CAPTURE";
    static final String K_HDR_DCG = "org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode";
    static final String K_LOFIC = "com.xiaomi.sessionparams.enableLofic";
    static final String K_MODE = SensorModePick.QCOM_KEY;
    static final String K_HDR_MODES = "org.codeaurora.qcamera3.supportedHDRmodes.HDRDCGModes";
    static final String K_MODE_TYPE = "com.xiaomi.sensor.sensormode.modetype";
    static final String K_STATS_DCG = "com.qti.stats_control.DCGMode";
    /** modetype of the report's DCG session (camera 5, row 4). */
    static final int REPORT_DCG_MODE_TYPE = 18;
    /** The 15 Ultra's DCG session mode (P230), the last variant of the main camera. */
    static final int NIGHT_MODE = XiaomiDcg.OPERATION_MODE;
    public static final int WINDOW = 90;

    /** The dual-gain readouts of the phone. */
    public enum Kind {
        DCG("DCG", 14), LOFIC("DCG+LOFIC", 14), DSG("DSG", 12);

        public final String label;
        /** The depth the static metadata declares for the mode. */
        public final int bits;

        Kind(String label, int bits) {
            this.label = label;
            this.bits = bits;
        }
    }

    public enum Role { MAIN, ULTRA_WIDE, TELE }

    // ---------------------------------------------------------------- the phone, its cameras and rows

    static boolean phone(@Nullable String manufacturer, @Nullable String brand, @Nullable String device, @Nullable String model) {
        return new FullResRecipePresets.Device(manufacturer, brand, device, model).xiaomi17Ultra();
    }

    private static volatile Boolean sPhone;

    public static boolean device() {
        Boolean p = sPhone;
        if (p == null) sPhone = p = phone(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.MODEL);
        return p;
    }

    public static boolean enabled() {
        return device() && PreferenceKeys.scamDevSwitch("xiaomi17_dxg", true);
    }

    /** The physical rear cameras of the report: 2 the main (OVX10500U), 3 the ultra wide (S5KJN5), 4 the 75 mm (S5KHPE). */
    @Nullable
    static Role role(@Nullable String cameraId) {
        if ("2".equals(cameraId)) return Role.MAIN;
        if ("3".equals(cameraId)) return Role.ULTRA_WIDE;
        if ("4".equals(cameraId)) return Role.TELE;
        return null;
    }

    /** The kinds a camera has, the first the one its switch turns on. */
    static Kind[] kinds(@Nullable Role role) {
        if (role == Role.MAIN) return new Kind[]{Kind.DCG, Kind.LOFIC};
        if (role == Role.TELE) return new Kind[]{Kind.DCG};
        if (role == Role.ULTRA_WIDE) return new Kind[]{Kind.DSG};
        return new Kind[0];
    }

    /** The vendor's 4:3 row of a kind (aispsensormodesettings.json; the ultra wide's by elimination), -1 for none. */
    static int vendorRow(@Nullable Role role, @Nullable Kind kind) {
        if (role == Role.MAIN) return kind == Kind.DCG ? 4 : kind == Kind.LOFIC ? 5 : -1;
        if (role == Role.TELE) return kind == Kind.DCG ? 6 : -1;
        if (role == Role.ULTRA_WIDE) return kind == Kind.DSG ? 3 : -1;
        return -1;
    }

    /**
     * The row of {@code kind} in the camera's own table: the vendor's row when the table has it as a 4:3 row of the binned width
     * (4000..4200) at 30 fps - a firmware whose rows moved offers nothing; -1 otherwise.
     */
    static int row(@Nullable Role role, @Nullable Kind kind, @Nullable int[] table, int forced) {
        final int row = forced >= 0 ? forced : vendorRow(role, kind);
        final int[] e = SensorModeBand.modeEntry(table, row);
        if (row < 0 || e == null) return -1;
        if (forced >= 0) return row;
        return e[0] >= 4000 && e[0] <= 4200 && Math.abs(e[0] * 3 - e[1] * 4) <= e[0] / 8 && e[2] == 30 ? row : -1;
    }

    /**
     * The mode type of {@code kind} in HDRDCGModes ({@code modes}; null: unread): the entry without a second read for DCG / DSG,
     * the one with it (DCGPlusVS) for DCG + LOFIC; the report's 4 / 5 when the list is unread, 0 when the list has none.
     */
    static int modeType(@Nullable Kind kind, @Nullable int[] modes) {
        if (modes == null || modes.length == 0) return kind == Kind.LOFIC ? 5 : 4;
        for (int m : modes) {
            final boolean second = ((m >> 16) & 0xff) != 0;
            if ((m & 0xff) != 0 && second == (kind == Kind.LOFIC)) return m & 0xff;
        }
        return 0;
    }

    /** The long read's depth HDRDCGModes declares for {@code kind}, 0 when unread. */
    static int declaredBits(@Nullable Kind kind, @Nullable int[] modes) {
        if (modes == null) return 0;
        for (int m : modes) {
            final boolean second = ((m >> 16) & 0xff) != 0;
            if ((m & 0xff) != 0 && second == (kind == Kind.LOFIC)) return (m >> 8) & 0xff;
        }
        return 0;
    }

    // ---------------------------------------------------------------- the ladder

    /** One way of asking for the row: int32 keys on the session parameters and every request, the session's operation mode. */
    static final class Variant {
        final String id;
        final int opMode;
        final String[] keys;
        final int[] values;
        /** What speaks for it, for the log. */
        final String basis;
        /** P641d: the session also carries the second RAW10 stream of the overflow read ({@link LoficPair}). */
        boolean pair;

        Variant(String id, int opMode, String basis, Object... pairs) {
            this.id = id;
            this.opMode = opMode;
            this.basis = basis;
            keys = new String[pairs.length / 2];
            values = new int[pairs.length / 2];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = (String) pairs[2 * i];
                values[i] = (Integer) pairs[2 * i + 1];
            }
        }

        Variant paired() {
            pair = true;
            return this;
        }

        boolean sendsRow() {
            for (String k : keys) if (K_MODE.equals(k)) return true;
            return false;
        }

        @Override public String toString() {
            final StringBuilder sb = new StringBuilder(id).append(" {");
            for (int i = 0; i < keys.length; i++) sb.append(i == 0 ? "" : ", ").append(shortKey(keys[i])).append(' ').append(values[i]);
            sb.append('}');
            if (pair) sb.append(" + the overflow stream (RAW10, use case 0x").append(Long.toHexString(LoficPair.USE_CASE)).append(')');
            if (opMode != 0) sb.append(" session mode 0x").append(Integer.toHexString(opMode));
            return sb.toString();
        }
    }

    static String shortKey(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }

    /**
     * The variants of {@code kind} for {@code row}, strongest evidence first: the HAL's own listed keys alone (EnableHDRDCGMode,
     * for DCG + LOFIC with enableLofic - the HAL picks its row), then with the row asked by current_mode (the selector the 4x ISZ
     * kit's authors used on this phone's 75 mm), then the list's own mode type as the key's value, then the row alone, and on the
     * main camera last the 15 Ultra's session mode 0x9203 with the row (P230; this phone's configs name the mode's family only).
     * P641d: DCG + LOFIC starts with «lofic-pair» - the vendor's two streams (the main RAW and a RAW10 stream of use case 524554
     * for the overflow read) with both listed keys and the row; it is proven by the depth and by the pair ({@link LoficPair}).
     */
    static List<Variant> ladder(Kind kind, int row, int type, boolean main) {
        final List<Variant> out = new ArrayList<>();
        final boolean lofic = kind == Kind.LOFIC;
        if (lofic) {
            out.add(new Variant("lofic-pair", 0, "the vendor's two streams: the dual-gain frame and the overflow read",
                    K_LOFIC, 1, K_HDR_DCG, 1, K_MODE, row).paired());
            out.add(new Variant("lofic+hdr", 0, "listed session keys", K_LOFIC, 1, K_HDR_DCG, 1));
            out.add(new Variant("lofic+hdr+row", 0, "listed session keys and the vendor's row", K_LOFIC, 1, K_HDR_DCG, 1, K_MODE, row));
            if (type > 1) out.add(new Variant("lofic+type+row", 0, "the static list's mode type as the key's value",
                    K_LOFIC, 1, K_HDR_DCG, type, K_MODE, row));
        } else {
            out.add(new Variant("hdr", 0, "listed session key", K_HDR_DCG, 1));
            out.add(new Variant("hdr+row", 0, "listed session key and the vendor's row", K_HDR_DCG, 1, K_MODE, row));
            if (type > 1) out.add(new Variant("type+row", 0, "the static list's mode type as the key's value", K_HDR_DCG, type, K_MODE, row));
        }
        out.add(new Variant("row", 0, "the vendor's row alone", K_MODE, row));
        if (main) {
            if (lofic) out.add(new Variant("night+row", NIGHT_MODE, "the 15 Ultra's DCG session mode", K_LOFIC, 1, K_MODE, row));
            else out.add(new Variant("night+row", NIGHT_MODE, "the 15 Ultra's DCG session mode", K_MODE, row));
        }
        return out;
    }

    // ---------------------------------------------------------------- what is remembered

    /** What a firmware showed for one camera and kind: the proven variant, the failed ones with their reasons. */
    static final class Memory {
        String fingerprint = "";
        @Nullable String proven;
        final Map<String, String> failed = new LinkedHashMap<>();

        /** "fingerprint|P:id|F:id=reason;id=reason". */
        String encode() {
            final StringBuilder sb = new StringBuilder(clean(fingerprint)).append("|P:").append(proven == null ? "" : proven).append("|F:");
            boolean first = true;
            for (Map.Entry<String, String> e : failed.entrySet()) {
                sb.append(first ? "" : ";").append(e.getKey()).append('=').append(clean(e.getValue()));
                first = false;
            }
            return sb.toString();
        }

        /** The stored state for {@code fingerprint}: another firmware's (or an unreadable one) is empty - its variants are tried anew. */
        static Memory decode(@Nullable String stored, @Nullable String fingerprint) {
            final Memory m = new Memory();
            m.fingerprint = fingerprint == null ? "" : fingerprint;
            if (stored == null) return m;
            final String[] parts = stored.split("\\|", -1);
            if (parts.length != 3 || !parts[0].equals(clean(m.fingerprint)) || !parts[1].startsWith("P:") || !parts[2].startsWith("F:")) return m;
            m.proven = parts[1].length() > 2 ? parts[1].substring(2) : null;
            for (String f : parts[2].substring(2).split(";")) {
                final int eq = f.indexOf('=');
                if (eq > 0) m.failed.put(f.substring(0, eq), f.substring(eq + 1));
            }
            return m;
        }

        private static String clean(@Nullable String s) {
            return s == null ? "" : s.replace('|', '/').replace(';', ',').replace('=', ':').replace('\n', ' ');
        }
    }

    /**
     * The variant a session runs: {@code forced} (an index of the ladder, scam_dev) alone; else the proven one while it is in the
     * ladder and did not fail since; else the first that did not fail. Null: nothing left.
     */
    @Nullable
    static Variant next(List<Variant> ladder, Memory memory, int forced) {
        if (forced >= 0) return forced < ladder.size() ? ladder.get(forced) : null;
        if (memory.proven != null && !memory.failed.containsKey(memory.proven))
            for (Variant v : ladder) if (v.id.equals(memory.proven)) return v;
        for (Variant v : ladder) if (!memory.failed.containsKey(v.id)) return v;
        return null;
    }

    // ---------------------------------------------------------------- the proof

    public enum Verdict { PENDING, PROVEN, FAILED }

    /**
     * The depth a frame's dynamic black shows against the declared one: 14 for sixteen times it (the report's 1024 over 64), 12
     * for four times, 10 for the same units, 0 when there is none or it fits no depth.
     */
    static int bitsOfBlack(float staticBlack, float dynamicBlack) {
        if (staticBlack <= 0 || dynamicBlack <= 0) return 0;
        final float r = dynamicBlack / staticBlack;
        if (r >= 12f && r <= 21f) return 14;
        if (r >= 3f && r <= 5.3f) return 12;
        return r >= 0.75f && r <= 1.34f ? 10 : 0;
    }

    /** The depth of a frame's payload: {@code packedBits} of a packed layout whose darkest samples sit {@code low}, else {@code sampledBits}. */
    static int bitsOfFrame(int packedBits, int low, float staticBlack, int sampledBits) {
        if (packedBits >= 12 && staticBlack > 0) {
            // the darkest samples of a 14-bit packing from 8 x the black up (the report's 1024 over 64; P72's rule), of a 12-bit
            // packing from 2.5 x: the packing's own scale. Values at the declared scale are no wider frame.
            final float r = low / staticBlack;
            if (packedBits == 14) return r >= 8f ? 14 : 10;
            return r >= 2.5f ? 12 : 10;
        }
        return sampledBits >= 12 ? sampledBits : 10;
    }

    /** A variant's evidence over the session's first results and frames. */
    static final class Proof {
        final int window;
        int results, frames, bits, bestBits;
        @Nullable String how;
        /** P641d: the variant has to show the overflow pair too; the last good ratio, the proven one, the measures that were none. */
        final boolean needPair;
        double lastRatio, pairRatio;
        int pairBad;

        Proof(int window) {
            this(window, false);
        }

        Proof(int window, boolean needPair) {
            this.window = Math.max(10, window);
            this.needPair = needPair;
        }

        boolean pairProven() {
            return pairRatio > 0;
        }

        /**
         * P641d: a measured pair of frames of one timestamp. Two pairs in a row with one ratio (within a tenth) prove the stream;
         * a measure that is no pair (a copy, no line, no highlights) starts the count again. One without data says nothing.
         */
        void notePair(LoficPair.Verdict verdict, double ratio) {
            if (verdict == LoficPair.Verdict.NO_DATA) return;
            if (verdict != LoficPair.Verdict.PAIR) {
                pairBad++;
                lastRatio = 0;
                return;
            }
            if (LoficPair.agree(lastRatio, ratio)) pairRatio = (lastRatio + ratio) / 2;
            lastRatio = ratio;
        }

        /** A result's depth (0: it says none) and a frame's (0: none checked with it). */
        void note(int resultBits, int frameBits, String source) {
            final int b = Math.max(resultBits, frameBits);
            if (b > 0) bits = b;
            if (b > bestBits) {
                bestBits = b;
                how = source;
            }
        }

        /**
         * Proven by a depth above the declared 10 bits; failed once the window is over with two checked frames at least, or
         * three windows over with fewer (a session whose RAW frames never came to the check gives no proof either).
         */
        Verdict verdict() {
            if (bestBits >= 12 && (!needPair || pairProven())) return Verdict.PROVEN;
            return results >= window && frames >= 2 || results >= 3 * window ? Verdict.FAILED : Verdict.PENDING;
        }
    }

    // ---------------------------------------------------------------- the session

    private static final String PREFS = "xiaomi_dual_gain";

    /** The running session's route. */
    static final class Session {
        final String camera;
        final Role role;
        final Kind kind;
        final int row;
        final Variant variant;
        final int ladderSize, index;
        final Proof proof;
        float staticBlack;
        boolean decided;
        /** The variant proved itself on this firmware before: a session it does not prove in is the HAL's choice, no failure. */
        boolean provenBefore;
        @Nullable String lastLine;
        /** The newest depth of the results' dynamic black and of a checked frame (0: none yet), and the depth told to RawLevels. */
        int resultBits, frameBits, lastKnownBits = -1;

        Session(String camera, Role role, Kind kind, int row, Variant variant, int index, int ladderSize, int window) {
            this.camera = camera;
            this.role = role;
            this.kind = kind;
            this.row = row;
            this.variant = variant;
            this.index = index;
            this.ladderSize = ladderSize;
            // P641d: a pair needs frames of both streams at the watch's sparse turns: twice the window
            this.proof = new Proof(variant.pair ? 2 * window : window, variant.pair);
        }
    }

    private static volatile Session sSession;

    @Nullable
    static Session session() {
        return sSession;
    }

    public static boolean sessionActive() {
        return sSession != null;
    }

    @Nullable
    private static String currentCamera() {
        try {
            return ScameraApp.getSettings().mCameraID;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static SharedPreferences prefs() {
        final Context c = ScameraApp.getAppContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String camera, Kind kind) {
        return camera + "_" + kind.name().toLowerCase(Locale.ROOT);
    }

    private static Memory memory(String camera, Kind kind) {
        final SharedPreferences p = prefs();
        return Memory.decode(p == null ? null : p.getString(key(camera, kind), null), Build.FINGERPRINT);
    }

    private static void store(String camera, Kind kind, Memory m) {
        final SharedPreferences p = prefs();
        if (p != null) p.edit().putString(key(camera, kind), m.encode()).apply();
    }

    private static int forcedRow() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi17_dxg_row", -1));
    }

    private static int forcedVariant() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi17_dxg_variant", -1));
    }

    private static int window() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi17_dxg_window", WINDOW));
    }

    @Nullable
    private static int[] hdrModes(@Nullable CameraCharacteristics c) {
        if (c == null) return null;
        try {
            return c.get(new CameraCharacteristics.Key<>(K_HDR_MODES, int[].class));
        } catch (RuntimeException unread) {
            return null;
        }
    }

    /** The kinds the active module's camera offers (its table has their rows); empty elsewhere. */
    public static List<Kind> offered(@Nullable CameraCharacteristics c) {
        return offered(currentCamera(), c);
    }

    public static List<Kind> offered(@Nullable String cameraId, @Nullable CameraCharacteristics c) {
        final List<Kind> out = new ArrayList<>();
        if (c == null || !enabled()) return out;
        try {
            if (Build.VERSION.SDK_INT >= 28 && !c.getPhysicalCameraIds().isEmpty()) return out; // a logical camera: the HAL's own choice
        } catch (RuntimeException ignored) {
        }
        final Role role = role(cameraId);
        final int[] table = SensorModeBand.table(c);
        for (Kind k : kinds(role)) if (row(role, k, table, -1) >= 0) out.add(k);
        return out;
    }

    /** Dcg14Mode.available: the switch is shown on this camera. */
    public static boolean available(@Nullable CameraCharacteristics c) {
        return !offered(c).isEmpty();
    }

    /** The kind the switch's {@code mode} (Dcg14Mode.DCG / LOFIC) means on the camera: DSG on the ultra wide, DCG where there is no LOFIC. */
    @Nullable
    static Kind kindOf(int mode, List<Kind> offered) {
        if (mode == Dcg14Mode.OFF || offered.isEmpty()) return null;
        if (mode == Dcg14Mode.LOFIC && offered.contains(Kind.LOFIC)) return Kind.LOFIC;
        return offered.get(0);
    }

    /** DCG + LOFIC can be chosen on the active module's camera. */
    public static boolean loficOffered(@Nullable CameraCharacteristics c) {
        return offered(c).contains(Kind.LOFIC);
    }

    /** The switch's name on the active module's camera: «DSG» on the ultra wide, «DCG» / «DCG+LOFIC» elsewhere; null off this phone. */
    @Nullable
    public static String switchName(int mode) {
        if (!enabled()) return null;
        final Role role = role(currentCamera());
        if (role == null) return null;
        if (role == Role.ULTRA_WIDE) return Kind.DSG.label;
        return mode == Dcg14Mode.LOFIC && role == Role.MAIN ? Kind.LOFIC.label : Kind.DCG.label;
    }

    /**
     * CaptureController, before a session is configured: the route of this session. {@code wanted}: the switch is on and the
     * session can carry the keys. A kind whose every variant failed on this firmware steps down here (DCG + LOFIC to DCG, DCG / DSG
     * off) and says so; returns the message for the screen then, null otherwise.
     */
    @Nullable
    public static String sessionStart(@Nullable String cameraId, @Nullable CameraCharacteristics c, boolean wanted) {
        sSession = null;
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.setKeepDepth(false);
        if (!wanted || cameraId == null || !enabled()) return null;
        final Role role = role(cameraId);
        final List<Kind> offered = offered(cameraId, c);
        String message = null;
        for (int step = 0; step < 2; step++) {
            final Kind kind = kindOf(Dcg14Mode.mode(), offered);
            if (kind == null || role == null) return message;
            final int[] modes = hdrModes(c);
            final int row = row(role, kind, SensorModeBand.table(c), forcedRow());
            final List<Variant> ladder = row < 0 ? Collections.<Variant>emptyList() : ladder(kind, row, modeType(kind, modes), role == Role.MAIN);
            final Memory memory = memory(cameraId, kind);
            final Variant v = next(ladder, memory, forcedVariant());
            if (v != null) {
                final Session s = new Session(cameraId, role, kind, row, v, ladder.indexOf(v), ladder.size(), window());
                s.staticBlack = staticBlack(c);
                s.provenBefore = v.id.equals(memory.proven);
                sSession = s;
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.setKeepDepth(true);
                Log.i(TAG, line(s) + "asks row " + row + " by variant " + (s.index + 1) + " of " + s.ladderSize + " " + v + " (" + v.basis
                        + (v.id.equals(memory.proven) ? "; proven on this firmware before" : "") + "); HDRDCGModes "
                        + (modes == null ? "unread (the report's type " + modeType(kind, null) + ")" : describeModes(modes))
                        + "; failed before: " + (memory.failed.isEmpty() ? "none" : memory.failed)
                        + "; proof: the depth of the frames within " + s.proof.window + " results; not proven on a phone (P641)");
                return message;
            }
            // nothing left for this kind on this firmware: one step down
            final boolean toDcg = kind == Kind.LOFIC;
            Log.w(TAG, "XIAOMI " + kind.label + " camera " + cameraId + ": every variant failed on this firmware " + memory.failed
                    + ": " + (toDcg ? "DCG instead" : "the plain session") + " (the lens settings' choice tries them again)");
            message = Lang.t(kind.label + ": на этой прошивке не включился ни одним способом — " + (toDcg ? "работает DCG" : "обычный режим")
                            + ". Повторить попытку можно в настройках объектива.",
                    kind.label + ": no way of turning it on worked on this firmware - " + (toDcg ? "DCG runs" : "the normal mode")
                            + ". The lens settings can try again.");
            Dcg14Mode.setMode(toDcg ? Dcg14Mode.DCG : Dcg14Mode.OFF);
        }
        return message;
    }

    /** The running session ended or was refused: nothing of it applies to the next one. */
    public static void sessionEnd() {
        sSession = null;
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.setKeepDepth(false);
    }

    private static float staticBlack(@Nullable CameraCharacteristics c) {
        final float[] b = com.particlesdevs.photoncamera.processing.RawLevels.staticBlack(c);
        if (b == null || b.length == 0) return 64f;
        float min = b[0];
        for (float v : b) min = Math.min(min, v);
        return min > 0 ? min : 64f;
    }

    static String describeModes(int[] modes) {
        final StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < modes.length; i++) {
            final int m = modes[i];
            sb.append(i == 0 ? "" : ", ").append(m).append(" = type ").append(m & 0xff).append(" / ").append((m >> 8) & 0xff)
                    .append(((m >> 16) & 0xff) != 0 ? " + " + ((m >> 16) & 0xff) : "").append(" bit");
        }
        return sb.append('}').toString();
    }

    private static String line(Session s) {
        return "XIAOMI " + s.kind.label + " camera " + s.camera + ": ";
    }

    /** The session's vendor operation mode (the last variant of the main camera), 0 for a regular session. */
    public static int operationMode() {
        final Session s = sSession;
        return s == null ? 0 : CaptureController.sessionOperationMode(s.variant.opMode, false);
    }

    /** P641d: this session carries the overflow stream (the «lofic-pair» variant). */
    public static boolean pairWanted() {
        final Session s = sSession;
        return s != null && s.variant.pair;
    }

    /** P641d: the session's pair is proven on its frames: the ratio two pairs in a row agreed on, 0 otherwise. */
    public static double pairRatio() {
        final Session s = sSession;
        return s != null && s.variant.pair && s.proof.bestBits >= 12 ? s.proof.pairRatio : 0;
    }

    /**
     * P641d: a measured pair of the session (the main frame of {@code timestamp} and the overflow frame of the same one). Counted
     * only while the main stream is the wide one - before the HAL takes the row the second stream is not the overflow read.
     */
    public static void onPair(LoficPair.Measure m, long timestamp) {
        final Session s = sSession;
        if (s == null || !s.variant.pair || m == null) return;
        final boolean wide = knownBits(s.frameBits, s.resultBits) >= 12;
        final boolean was = s.proof.pairProven();
        if (wide) s.proof.notePair(m.verdict, m.ratio);
        if (!was || m.verdict != LoficPair.Verdict.PAIR) Log.i(TAG, line(s) + "overflow pair of timestamp " + timestamp + ": " + m
                + (wide ? "" : " (not counted: the main stream is at the declared depth)")
                + (!was && s.proof.pairProven() ? String.format(Locale.ROOT, " -> the pair is PROVEN, ratio %.3f", s.proof.pairRatio) : ""));
    }

    /** The asked row (SensorModeBand: its size), -1 without a session. */
    public static int askedRow() {
        final Session s = sSession;
        return s == null ? -1 : s.row;
    }

    /** The variant's keys on the session parameters or a request; the log text. */
    public static String apply(@Nullable CaptureRequest.Builder b) {
        final Session s = sSession;
        if (b == null || s == null) return "";
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.variant.keys.length; i++) {
            final String key = s.variant.keys[i];
            final int value = s.variant.values[i];
            out.append(i == 0 ? "" : ", ").append(shortKey(key)).append(' ');
            try {
                b.set(new CaptureRequest.Key<>(key, Integer.class), value);
                out.append(value);
            } catch (RuntimeException asInteger) {
                try {
                    b.set(new CaptureRequest.Key<>(key, int[].class), new int[]{value});
                    out.append('[').append(value).append(']');
                } catch (RuntimeException refused) {
                    out.append("refused: ").append(refused.getMessage());
                }
            }
        }
        return out.toString();
    }

    private static final CaptureResult.Key<int[]> R_MODE_TYPE = new CaptureResult.Key<>(K_MODE_TYPE, int[].class);
    private static final CaptureResult.Key<int[]> R_STATS_DCG = new CaptureResult.Key<>(K_STATS_DCG, int[].class);

    @Nullable
    private static Integer first(@Nullable CaptureResult result, CaptureResult.Key<int[]> key) {
        if (result == null) return null;
        try {
            final int[] v = result.get(key);
            return v == null || v.length == 0 ? null : v[0];
        } catch (RuntimeException unread) {
            return null;
        }
    }

    private static volatile boolean sRecheck;

    /**
     * CaptureController: the results' depth changed since the last call (the HAL entered or left the mode): the next viewfinder
     * frame is checked at once, not at the watch's next turn.
     */
    public static boolean takeRecheck() {
        if (!sRecheck) return false;
        sRecheck = false;
        return true;
    }

    /**
     * A viewfinder result of the session: what the HAL reports (logged on change) and the depth its dynamic black shows; the
     * session's known depth follows it. Returns PROVEN / FAILED once, when the variant is decided; PENDING otherwise.
     */
    public static Verdict onResult(@Nullable CaptureResult result, @Nullable CameraCharacteristics c) {
        final Session s = sSession;
        if (s == null || result == null) return Verdict.PENDING;
        final float[] dyn = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        float dynMin = -1f;
        if (dyn != null) for (float v : dyn) dynMin = dynMin < 0 ? v : Math.min(dynMin, v);
        final int resultBits = bitsOfBlack(s.staticBlack, dynMin);
        s.proof.results++;
        if (resultBits > 0 && resultBits != s.resultBits) sRecheck = true;
        // a decided session's results are described at every 15th only (the vendor keys are read by name)
        if (s.decided && resultBits == s.resultBits && s.proof.results % 15 != 0) return Verdict.PENDING;
        final Integer current = SensorModeBand.currentMode(result), type = first(result, R_MODE_TYPE), stats = first(result, R_STATS_DCG);
        final Integer white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        s.proof.note(resultBits >= 12 ? resultBits : 0, 0, "dynamic black " + Math.round(dynMin) + " over the declared " + Math.round(s.staticBlack));
        final String state = "results report current_mode " + current + (current == null ? "" : s.variant.sendsRow()
                ? " (the request's echo, no proof)" : current == s.row ? " (the HAL's own: the asked row)" : " (the HAL's own, NOT row " + s.row + ")")
                + ", modetype " + type + (type != null && type == REPORT_DCG_MODE_TYPE ? " (the report's DCG session had 18)" : "")
                + ", DCGMode " + stats + ", dynamic black " + (dynMin < 0 ? "none" : String.valueOf(Math.round(dynMin)))
                + " / white " + white + " (declared black " + Math.round(s.staticBlack) + ") -> "
                + (resultBits >= 12 ? resultBits + " bit" : resultBits == 10 ? "the declared 10-bit units" : "no depth");
        if (!state.equals(s.lastLine)) {
            s.lastLine = state;
            Log.i(TAG, line(s) + "variant " + s.variant.id + ", row " + s.row + " asked: " + state);
        }
        if (resultBits > 0) s.resultBits = resultBits;
        known(s, c, "the results' dynamic black " + Math.round(dynMin));
        return decide(s, result);
    }

    /**
     * A checked viewfinder frame of the session: a packed payload of {@code packedBits} (0: plain) whose darkest samples sit at
     * {@code low}, or plain samples proving {@code sampledBits} (0: within the declared white).
     */
    public static void onFrame(int packedBits, int low, int sampledBits, @Nullable CameraCharacteristics c) {
        final Session s = sSession;
        if (s == null) return;
        final int bits = bitsOfFrame(packedBits, low, s.staticBlack, sampledBits);
        s.proof.frames++;
        s.proof.note(0, bits, packedBits > 0 ? "a packed RAW" + packedBits + " payload, darkest samples " + low
                : "plain samples of " + sampledBits + " bit");
        if (s.proof.frames <= 4 || bits != 10) Log.i(TAG, line(s) + "frame " + s.proof.frames + ": "
                + (packedBits > 0 ? "packed MIPI RAW" + packedBits + " behind RAW_SENSOR, darkest samples " + low : "plain 16-bit samples"
                + (sampledBits > 0 ? " proving " + sampledBits + " bit" : " within the declared white")) + " -> " + bits + " bit");
        s.frameBits = bits;
        known(s, c, "a frame's payload");
    }

    /**
     * The depth the evidence shows now: a checked frame's when it is wider than declared (the payload is the truth), else the
     * results'; 0 (the declared depth) once either says so and neither says wider; -1 with no evidence yet.
     */
    static int knownBits(int frameBits, int resultBits) {
        if (frameBits >= 12) return frameBits;
        if (resultBits >= 12) return resultBits;
        return frameBits > 0 || resultBits > 0 ? 0 : -1;
    }

    /** The session's known depth (RawLevels) follows the evidence. */
    private static void known(Session s, @Nullable CameraCharacteristics c, String why) {
        final int bits = knownBits(s.frameBits, s.resultBits);
        if (bits < 0 || bits == s.lastKnownBits) return;
        s.lastKnownBits = bits;
        com.particlesdevs.photoncamera.processing.RawLevels.noteKnownBits(
                com.particlesdevs.photoncamera.processing.RawLevels.staticBlack(c),
                com.particlesdevs.photoncamera.processing.RawLevels.staticWhite(c), bits,
                "Xiaomi " + s.kind.label + " row " + s.row + ": " + why);
    }

    private static Verdict decide(Session s, @Nullable CaptureResult result) {
        if (s.decided) return Verdict.PENDING;
        final Verdict v = s.proof.verdict();
        if (v == Verdict.PENDING) return v;
        s.decided = true;
        final Memory m = memory(s.camera, s.kind);
        final String scene = scene(result);
        if (v == Verdict.PROVEN) {
            m.proven = s.variant.id;
            m.failed.remove(s.variant.id);
            store(s.camera, s.kind, m);
            Log.i(TAG, line(s) + "PROVEN by variant " + s.variant + ": " + s.proof.bestBits + " bit (" + s.proof.how + ")"
                    + (s.variant.pair ? String.format(Locale.ROOT, ", the overflow pair at a ratio of %.3f", s.proof.pairRatio) : "") + " after "
                    + s.proof.results + " results / " + s.proof.frames + " frames" + (s.proof.bestBits != s.kind.bits
                    ? "; the static list declares " + s.kind.bits + " bit" : "") + "; remembered for this firmware; " + scene);
            return v;
        }
        if (s.provenBefore || forcedVariant() >= 0) {
            // proven on this firmware before (or forced): the HAL did not take the mode this time - its choice, the session stays
            Log.w(TAG, line(s) + "variant " + s.variant + (s.provenBefore ? " (proven before)" : " (forced)") + " did not give the mode"
                    + " this time: " + s.proof.frames + " frames and " + s.proof.results + " results at the declared depth (" + s.lastLine
                    + "); " + scene + "; the session goes on as a plain one");
            return Verdict.PENDING;
        }
        final boolean pairOnly = s.variant.pair && s.proof.bestBits >= 12; // the depth came, the overflow pair did not
        m.failed.put(s.variant.id, (pairOnly ? "no overflow pair (" + s.proof.pairBad + " measures were none) in " : "10-bit frames in ")
                + s.proof.results + " results, " + scene);
        store(s.camera, s.kind, m);
        Log.w(TAG, line(s) + "variant " + s.variant + " FAILED: " + (pairOnly ? "the main stream reached " + s.proof.bestBits
                + " bit, but the second stream gave no proven overflow pair (" + s.proof.pairBad + " measures were none)"
                : s.proof.frames + " frames and " + s.proof.results + " results stayed at the declared depth (" + s.lastLine + ")")
                + "; " + scene + "; the next variant runs");
        return v;
    }

    private static String scene(@Nullable CaptureResult r) {
        if (r == null) return "scene unknown";
        final Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
        final Long exposure = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        return "scene ISO " + iso + ", exposure " + (exposure == null ? "?" : String.format(Locale.ROOT, "%.2f ms", exposure / 1e6));
    }

    /**
     * The session was refused or gave no frame ({@code why}): its variant failed. True when the switch stays on - the next
     * session takes the next variant or steps down by {@link #sessionStart}; false without a session of this route.
     */
    public static boolean sessionFailed(String why) {
        final Session s = sSession;
        if (s == null) return false;
        sessionEnd();
        if (!s.decided && forcedVariant() < 0) {
            final Memory m = memory(s.camera, s.kind);
            m.failed.put(s.variant.id, why);
            if (s.variant.id.equals(m.proven)) m.proven = null;
            store(s.camera, s.kind, m);
        }
        Log.w(TAG, line(s) + "variant " + s.variant + " FAILED: the session " + why + "; the next variant runs");
        return true;
    }

    /** The lens settings chose the mode again: this camera's failures are forgotten, every variant may run once more. */
    public static void tryAgain(@Nullable String cameraId) {
        final SharedPreferences p = prefs();
        if (p == null || cameraId == null) return;
        final SharedPreferences.Editor e = p.edit();
        for (Kind k : Kind.values()) {
            final Memory m = memory(cameraId, k);
            if (m.failed.isEmpty()) continue;
            m.failed.clear();
            e.putString(key(cameraId, k), m.encode());
        }
        e.apply();
    }

    /** What the lens row shows under its title: the proven variant, the failed ones, or that nothing ran yet. */
    public static String state(@Nullable String cameraId, Kind kind) {
        if (cameraId == null) return "";
        final Memory m = memory(cameraId, kind);
        if (m.proven != null) return Lang.t(kind.label + ": на этой прошивке подтверждён (вариант " + m.proven + ")",
                kind.label + ": proven on this firmware (variant " + m.proven + ")");
        if (!m.failed.isEmpty()) return Lang.t(kind.label + ": не сработали варианты " + m.failed.keySet(),
                kind.label + ": the variants " + m.failed.keySet() + " did not work");
        return Lang.t(kind.label + ": на телефоне ещё не проверен", kind.label + ": not tried on the phone yet");
    }

    /**
     * Dcg14Mode.frames: the session's frames are dual-gain ones of {@code bits} - the proven depth of this route (the ultra wide's
     * DSG is a 12-bit stream, under the 14 bits the other phones' rule asks for).
     */
    public static boolean frames(int bits) {
        final Session s = sSession;
        return s != null && s.proof.bestBits >= 12 && bits >= 12;
    }
}
