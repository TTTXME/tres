package com.scamera.app.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.util.Range;

import androidx.annotation.Nullable;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P679 (owner, 2026-11-13: «Изучи и реализуй работу без рут доступа на 17 Ultra полноценно с нашим пайплайном обработки и нашей
 * склейкой. Переключатель должен быть совмещён с dcg. Так же при включенном lofic мы должны воспользоваться преимуществами его
 * работы и получить дополнительную информацию в светах»; «Если у тебя получится без рута реализовать 4:3 без кропа, делай»): the
 * Xiaomi 17 Ultra main camera's LOFIC pair as RAW - every frame read out twice at the same moment, a 14-bit Long stream (the
 * dual-conversion-gain read) and a 10-bit Short stream (the overflow read, about 1 / 12.55 of the Long exposure) - by the session
 * recipe of the third-party LOFIC RAW release's INTEGRATION.md (firmware OS3.0.312.0.WPACNXM). Nothing here ran on a phone.
 * <p>
 * The recipe: camera "2" opened directly; a vendor session type and an AE target range of exactly [fps, fps]; six outputs in a
 * fixed order (a configured-only dummy RAW, the viewfinder, a video-encode stream, a configured-only JPEG, RAW A and RAW B, every
 * RAW 4096 x 3072); TEMPLATE_RECORD; xiaomi.app.module {180} and enableLofic {1} (int32) on the session and on every request,
 * loficEnable {1} (byte) on every request; auto exposure only (without root the HAL gives the Short stream the Long exposure and
 * full gain when AE is off).
 * <p>
 * The setups, in the order a session tries them ({@link Setup}):
 * <ul>
 * <li>4:3 · 30 fps (0xF010, [30, 30], the sensor's DXGLofic row 5, 4096 x 3072): the release reaches it only with its root
 * module - without it the HAL picks a cropped row. This phone's HAL takes current_mode as a row pick on the session and the
 * requests (P631's 50 MP row 11, the 50 MP recipe's row 0), so the candidate asks for row 5 that way. Since a sent current_mode
 * comes back as an echo, only the frames prove it: both streams 3072 rows, one RAW14 and one RAW10, ExposureCount 2. A failure is
 * remembered for this firmware.</li>
 * <li>16:10 · 60 fps (0x8004, [60, 60], the HAL's own row 27, 4096 x 2560): the release's root-free setup; proven by the mode
 * the results report (27, nothing is sent) and the frames' rows.</li>
 * </ul>
 * Any check that fails closes the session and opens the next setup; with none left the session is the normal one, with a log
 * line and a message. The frames: packed MIPI, rows back to back, the buffers' rowStride means nothing; which of A / B is Long
 * changes, so each buffer is classified by its used length. Long: black 1024, white 16383; Short: black 64, white 1023 (the
 * metadata says otherwise). A pair is the Long and the Short image of one timestamp; only pairs whose result reports
 * com.qti.stats_control.ExposureCount 2 carry a usable Short read.
 * <p>
 * scam_dev: {@code xiaomi17_lofic} -1 follows the switch, 1 forces the route on the main camera, 0 turns it off (the switch's
 * «DCG+LOFIC» then runs P641's ladder); {@code xiaomi17_lofic_setup} 0 = 4:3 first, 1 = 16:10 only; {@code lofic_pairs} the pairs
 * a shot takes; {@code lofic_blend} the merge of a pair (0 noise-weighted, 1 the release's fixed ramp) - processing.LoficMerge.
 */
public final class XiaomiLofic {
    private XiaomiLofic() {}

    static final String TAG = "SCAM_CAPTURE";
    /** The main wide camera as a physical device. */
    public static final String CAMERA_ID = "2";
    /** Every RAW output of the recipe. */
    public static final int RAW_W = 4096, RAW_H = 3072;
    /** Bytes of a packed row: RAW14 (4 samples in 7 bytes) and RAW10 (4 in 5). */
    public static final int LONG_ROW = RAW_W * 14 / 8, SHORT_ROW = RAW_W * 10 / 8;
    public static final int LONG_BLACK = 1024, LONG_WHITE = 16383, SHORT_BLACK = 64, SHORT_WHITE = 1023;
    /** The Short exposure is the Long one over this (the sensor's factory calibration; the merge measures k per frame). */
    public static final double EXPOSURE_RATIO = 12.55;
    /** The Short stream's read noise, DN of its own. */
    public static final double SHORT_READ_NOISE = 0.75;
    public static final int APP_MODULE = 180;
    static final String K_APP_MODULE = "xiaomi.app.module";
    static final String K_ENABLE_LOFIC = "com.xiaomi.sessionparams.enableLofic";
    static final String K_LOFIC_ENABLE = "com.xiaomi.camera.eiParams.loficEnable";
    static final String K_CURRENT_MODE = SensorModePick.QCOM_KEY;
    static final String K_EXPOSURE_COUNT = "com.qti.stats_control.ExposureCount";
    /** Frames of one exposure carry the same sensor timestamp; half a millisecond of slack. */
    public static final long PAIR_NS = 500_000L;
    /** The results a setup has to prove itself in. */
    public static final int WINDOW = 90;
    /** The pairs a shot takes by default (scam_dev lofic_pairs). */
    public static final int PAIRS = 6;

    // ---------------------------------------------------------------- the setups

    public enum Setup {
        FOUR_THREE("4:3", "4:3 · 30 fps", 0xF010, 30, 1440, 1080, 2880, 2160, 4096, 3072, 3072, new int[]{5}, 5),
        SIXTEEN_TEN("16:10", "16:10 · 60 fps", 0x8004, 60, 1920, 1080, 3840, 2160, 3840, 2160, 2560, new int[]{27}, -1);

        public final String id, label;
        /** The vendor session (operation) mode passed to SessionConfiguration. */
        public final int sessionType;
        /** The AE target range is exactly [fps, fps]. */
        public final int fps;
        public final int previewW, previewH, videoW, videoH, jpegW, jpegH;
        /** The image rows of both streams in this setup (the width is always 4096). */
        public final int rows;
        /** The sensor modes (current_mode) of this setup. */
        public final int[] modes;
        /** The row asked for by current_mode on the session and the requests (-1: none, the HAL's own choice). */
        public final int askRow;

        Setup(String id, String label, int sessionType, int fps, int pw, int ph, int vw, int vh, int jw, int jh, int rows, int[] modes,
              int askRow) {
            this.id = id;
            this.label = label;
            this.sessionType = sessionType;
            this.fps = fps;
            previewW = pw;
            previewH = ph;
            videoW = vw;
            videoH = vh;
            jpegW = jw;
            jpegH = jh;
            this.rows = rows;
            this.modes = modes;
            this.askRow = askRow;
        }

        public boolean modeOf(@Nullable Integer reported) {
            if (reported == null) return false;
            for (int m : modes) if (m == reported) return true;
            return false;
        }

        /** Bytes the Long / Short image of this setup fills. */
        public int longBytes() { return rows * LONG_ROW; }
        public int shortBytes() { return rows * SHORT_ROW; }
    }

    /** One output of the session, in the recipe's order. */
    public static final class Output {
        public final String role;
        /** android.graphics.ImageFormat code; 0 for the viewfinder's SurfaceTexture (PRIVATE). */
        public final int format;
        public final int width, height;
        /** ImageReader usage (0: the default). */
        public final long usage;
        /** Targeted by the repeating request. */
        public final boolean repeating;

        Output(String role, int format, int width, int height, long usage, boolean repeating) {
            this.role = role;
            this.format = format;
            this.width = width;
            this.height = height;
            this.usage = usage;
            this.repeating = repeating;
        }

        @Override public String toString() {
            return role + " " + width + "x" + height + (repeating ? " (repeating)" : " (configured only)");
        }
    }

    public static final String DUMMY = "dummy RAW", VIEWFINDER = "viewfinder", VIDEO = "video", JPEG = "JPEG", RAW_A = "RAW A",
            RAW_B = "RAW B";
    static final int RAW_SENSOR = 0x20, PRIVATE = 0x22, JPEG_FORMAT = 0x100;
    /** HardwareBuffer.USAGE_VIDEO_ENCODE. */
    static final long USAGE_VIDEO_ENCODE = 1L << 16;

    /**
     * The six outputs in the recipe's order: without the configured-only JPEG no RAW arrives, without the dummy RAW no frame does,
     * and another order or size makes the HAL pick another row.
     */
    public static List<Output> outputs(Setup s) {
        final List<Output> out = new ArrayList<>(6);
        out.add(new Output(DUMMY, RAW_SENSOR, RAW_W, RAW_H, 0, false));
        out.add(new Output(VIEWFINDER, PRIVATE, s.previewW, s.previewH, 0, true));
        out.add(new Output(VIDEO, PRIVATE, s.videoW, s.videoH, USAGE_VIDEO_ENCODE, true));
        out.add(new Output(JPEG, JPEG_FORMAT, s.jpegW, s.jpegH, 0, false));
        out.add(new Output(RAW_A, RAW_SENSOR, RAW_W, RAW_H, 0, true));
        out.add(new Output(RAW_B, RAW_SENSOR, RAW_W, RAW_H, 0, true));
        return Collections.unmodifiableList(out);
    }

    // ---------------------------------------------------------------- the keys

    /** A vendor key and its value (int[] for int32, byte[] for byte). */
    public static final class Key {
        public final String name;
        public final Object value;

        Key(String name, Object value) {
            this.name = name;
            this.value = value;
        }

        @Override public String toString() {
            final String v = value instanceof int[] ? java.util.Arrays.toString((int[]) value)
                    : value instanceof byte[] ? java.util.Arrays.toString((byte[]) value) : String.valueOf(value);
            return name.substring(name.lastIndexOf('.') + 1) + "=" + v;
        }
    }

    /** The vendor keys of the session parameters ({@code session}) or of every request: the tested combination. */
    public static List<Key> vendorKeys(Setup s, boolean session) {
        final List<Key> out = new ArrayList<>();
        out.add(new Key(K_APP_MODULE, new int[]{APP_MODULE}));
        out.add(new Key(K_ENABLE_LOFIC, new int[]{1}));
        if (!session) out.add(new Key(K_LOFIC_ENABLE, new byte[]{1}));
        if (s.askRow >= 0) out.add(new Key(K_CURRENT_MODE, new int[]{s.askRow}));
        return out;
    }

    /** Sets the setup's keys and AE range on a request builder (session parameters or a request); the log text. */
    public static String apply(@Nullable CaptureRequest.Builder b, Setup s, boolean session) {
        if (b == null) return "";
        final StringBuilder out = new StringBuilder();
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(s.fps, s.fps));
        out.append("CONTROL_MODE_AUTO, AE fps [").append(s.fps).append(", ").append(s.fps).append(']');
        if (!session) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); // auto exposure only (no root)
            out.append(", AE on");
        }
        for (Key k : vendorKeys(s, session)) {
            out.append(", ");
            try {
                if (k.value instanceof byte[]) b.set(new CaptureRequest.Key<>(k.name, byte[].class), (byte[]) k.value);
                else b.set(new CaptureRequest.Key<>(k.name, int[].class), (int[]) k.value);
                out.append(k);
            } catch (RuntimeException refused) {
                out.append(k.name).append(" refused: ").append(refused.getMessage());
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- the buffers

    public enum Stream { LONG, SHORT, NONE }

    /**
     * What a buffer of {@code used} filled bytes is (INTEGRATION.md section 3): a whole number of 7168-byte rows (at most 3072) is
     * Long, of 5120-byte rows Short (a 2560-row Long has the length of a 3584-row Short: the 3072-row limit tells them apart); the
     * last byte of a row may be 0, so a few bytes of slack. NONE: nothing, or neither.
     */
    public static Stream classify(int used) {
        if (used <= 0) return Stream.NONE;
        if (fits(used, LONG_ROW)) return Stream.LONG;
        if (fits(used, SHORT_ROW)) return Stream.SHORT;
        return Stream.NONE;
    }

    static boolean fits(int used, int row) {
        final int rows = (used + row - 1) / row;
        return rows >= 1 && rows <= RAW_H && rows * row - used < 16;
    }

    /** The rows a buffer of {@code used} bytes of {@code stream} holds (0 for none). */
    public static int rows(int used, Stream stream) {
        if (stream == Stream.LONG) return (used + LONG_ROW - 1) / LONG_ROW;
        if (stream == Stream.SHORT) return (used + SHORT_ROW - 1) / SHORT_ROW;
        return 0;
    }

    /** Offset after the last non-zero byte (the used length). */
    public static int usedBytes(ByteBuffer buffer) {
        return buffer == null ? 0 : com.scamera.app.processing.RawPayloadCheck.dataEnd(buffer);
    }

    /**
     * The per-frame test of the session's {@code rows}: data past the end of a Short image is Long (a Long row is 7 / 5 of a Short
     * one, so a Long image of the same rows reaches beyond). 256 sampled bytes after the Short end; a packed RAW14 row of a frame
     * above black 1024 has a high byte of at least 16 in every sample, so most are non-zero.
     */
    public static Stream quick(ByteBuffer buffer, int rows) {
        if (buffer == null) return Stream.NONE;
        final int from = rows * SHORT_ROW + 64, capacity = buffer.capacity();
        if (from + 7 * 256 > capacity) return Stream.NONE;
        int nonZero = 0;
        for (int i = 0; i < 256; i++) if (buffer.get(from + 7 * i) != 0) nonZero++;
        if (nonZero >= 64) return Stream.LONG;
        // a Short image: its last row holds data
        int last = 0;
        final int end = rows * SHORT_ROW - SHORT_ROW / 2;
        for (int i = 0; i < 64; i++) if (end + 5 * i < capacity && buffer.get(end + 5 * i) != 0) last++;
        return last >= 16 ? Stream.SHORT : Stream.NONE;
    }

    /** A packed RAW14 sample. */
    public static int long14(ByteBuffer b, int x, int y) {
        final int at = y * LONG_ROW + (x >> 2) * 7;
        return com.scamera.app.util.RawUnpack.raw14(b, at)[x & 3];
    }

    /** A packed RAW10 sample. */
    public static int short10(ByteBuffer b, int x, int y) {
        final int at = y * SHORT_ROW + (x >> 2) * 5;
        return ((b.get(at + (x & 3)) & 0xff) << 2) | (((b.get(at + 4) & 0xff) >> (2 * (x & 3))) & 3);
    }

    // ---------------------------------------------------------------- k, the Long / Short ratio

    /** The measured ratio of a pair: k = sum L / sum S over the pixels with 2000 < L < 14500 (both black-subtracted). */
    public static final class Ratio {
        public double k;
        /** Pixels in the band (the sampled ones times the sampling area). */
        public long pixels;
        public boolean measured;

        @Override public String toString() {
            return measured ? String.format(Locale.ROOT, "k %.2f over %d px", k, pixels) : "k not measured (" + pixels + " px in the band)";
        }
    }

    public static final int K_LOW = 2000, K_HIGH = 14500, K_MIN_PIXELS = 20000;

    /**
     * k of a Long image ({@code long14}: packed RAW14 rows) and its Short image ({@code short10}: packed RAW10 rows), every
     * {@code step}-th group of 4 samples of every {@code step}-th row pair (both CFA rows), selecting by the Long value only - a
     * threshold on S would find nothing at high gain, where S holds a few DN at the Long clip (INTEGRATION.md section 5).
     */
    public static Ratio ratio(ByteBuffer long14, ByteBuffer short10, int rows, int step) {
        final Ratio r = new Ratio();
        step = Math.max(1, step);
        double sumL = 0, sumS = 0;
        long n = 0;
        for (int y = 0; y + 1 < rows; y += 2 * step)
            for (int yy = y; yy < y + 2; yy++)
                for (int g = 0; g < RAW_W / 4; g += step) {
                    final int[] l = com.scamera.app.util.RawUnpack.raw14(long14, yy * LONG_ROW + g * 7);
                    final int at = yy * SHORT_ROW + g * 5;
                    final int lowBits = short10.get(at + 4) & 0xff;
                    for (int i = 0; i < 4; i++) {
                        final int L = l[i] - LONG_BLACK;
                        if (L <= K_LOW || L >= K_HIGH) continue;
                        final int S = (((short10.get(at + i) & 0xff) << 2) | ((lowBits >> (2 * i)) & 3)) - SHORT_BLACK;
                        sumL += L;
                        sumS += S;
                        n++;
                    }
                }
        r.pixels = n * step * step;
        if (r.pixels >= K_MIN_PIXELS && sumS > 0) {
            r.k = sumL / sumS;
            r.measured = r.k > 0 && Double.isFinite(r.k);
        }
        return r;
    }

    /** The highlight range a pair adds over the Long clip, in stops: log2(k x 959 / 15359); 0 when it adds none. */
    public static double headroomStops(double k) {
        final double h = k * (SHORT_WHITE - SHORT_BLACK) / (LONG_WHITE - LONG_BLACK);
        return h > 1 ? Math.log(h) / Math.log(2) : 0;
    }

    // ---------------------------------------------------------------- the proof of a session

    public enum Verdict { PENDING, PROVEN, FAILED }

    /** What a session's results and frames showed. */
    static final class Proof {
        final Setup setup;
        final int window;
        int results, modeOk, modeOther, withMode, pairs, countTwo, countOne;
        @Nullable Integer lastMode;
        int longRows = -1, shortRows = -1;
        boolean decided;
        Verdict verdict = Verdict.PENDING;
        String failure = "";

        Proof(Setup setup, int window) {
            this.setup = setup;
            this.window = Math.max(10, window);
        }

        /** A result: the mode it reports and its ExposureCount. */
        void result(@Nullable Integer mode, @Nullable Integer count) {
            results++;
            if (mode != null) {
                withMode++;
                lastMode = mode;
                if (setup.modeOf(mode)) modeOk++;
                else modeOther++;
            }
            if (count != null) {
                if (count == 2) countTwo++;
                else countOne++;
            }
        }

        /** A pair of images of one timestamp: the rows each stream filled. */
        void pair(int longRows, int shortRows) {
            pairs++;
            this.longRows = longRows;
            this.shortRows = shortRows;
        }

        /**
         * The verdict: FAILED at once when a pair shows other rows than the setup's (a cropped row), or when the reported modes
         * are another row's (16:10, where nothing is sent: the HAL's own report; 4:3 sends its row and the echo proves nothing);
         * FAILED at the end of the window without a pair, or (4:3) without a single ExposureCount 2; PROVEN on a pair of the
         * setup's rows with the setup's mode (16:10) or with ExposureCount 2 (4:3); PENDING otherwise.
         */
        Verdict verdict() {
            if (decided) return verdict;
            if (pairs > 0 && (longRows != setup.rows || shortRows != setup.rows)) {
                return decide(Verdict.FAILED, "a pair of " + longRows + " Long / " + shortRows + " Short rows, not " + setup.rows
                        + " (another sensor row)");
            }
            if (setup.askRow < 0 && withMode >= 5 && modeOther > modeOk) {
                return decide(Verdict.FAILED, "the results report sensor mode " + lastMode + ", not " + java.util.Arrays.toString(setup.modes));
            }
            // a HAL that reports no mode at all leaves the rows as the proof (after a few results)
            final boolean modeProof = setup.askRow >= 0 || modeOk > 0 || withMode == 0 && results >= 10;
            final boolean countProof = setup.askRow < 0 || countTwo > 0;
            if (pairs > 0 && modeProof && countProof) return decide(Verdict.PROVEN, "");
            if (results >= window) {
                if (pairs == 0) return decide(Verdict.FAILED, "no Long + Short pair in " + results + " results");
                if (!modeProof) return decide(Verdict.FAILED, "no result reports mode " + java.util.Arrays.toString(setup.modes)
                        + " (last " + lastMode + ")");
                return decide(Verdict.FAILED, "ExposureCount never 2 in " + results + " results (" + countOne + " single exposures)");
            }
            return Verdict.PENDING;
        }

        private Verdict decide(Verdict v, String why) {
            decided = true;
            verdict = v;
            failure = why;
            return v;
        }

        String describe() {
            return "setup " + setup.label + ": " + results + " results (mode " + lastMode + ", " + modeOk + " of " + withMode
                    + " in " + java.util.Arrays.toString(setup.modes) + "), ExposureCount 2 in " + countTwo + " / 1 in " + countOne
                    + ", " + pairs + " pairs (rows Long " + longRows + " / Short " + shortRows + ", want " + setup.rows + ")";
        }
    }

    // ---------------------------------------------------------------- which setups are left

    private static final String PREFS = "xiaomi_lofic";
    /** Setups that failed in this run of the app (16:10 is never remembered across runs: a failure may be the moment's). */
    private static final Set<String> RUN_FAILED = ConcurrentHashMap.newKeySet();

    /** The stored failure of a setup for this firmware ("fingerprint|reason"), null without one. */
    @Nullable
    static String storedFailure(@Nullable String stored, @Nullable String fingerprint) {
        if (stored == null) return null;
        final int bar = stored.indexOf('|');
        if (bar < 0 || !stored.substring(0, bar).equals(fingerprint == null ? "" : fingerprint)) return null;
        return stored.substring(bar + 1);
    }

    /**
     * The setups a session tries, in order: 4:3 unless it failed on this firmware (or in this run) or {@code sixteenOnly}; then
     * 16:10 unless it failed in this run.
     */
    static List<Setup> ladder(boolean fourThreeFailed, boolean sixteenFailed, boolean sixteenOnly) {
        final List<Setup> out = new ArrayList<>();
        if (!fourThreeFailed && !sixteenOnly) out.add(Setup.FOUR_THREE);
        if (!sixteenFailed) out.add(Setup.SIXTEEN_TEN);
        return out;
    }

    @Nullable
    private static SharedPreferences prefs() {
        final Context c = ScameraApp.getAppContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean failed(Setup s) {
        if (RUN_FAILED.contains(s.id)) return true;
        if (s != Setup.FOUR_THREE) return false;
        final SharedPreferences p = prefs();
        return storedFailure(p == null ? null : p.getString("setup_" + s.id, null), Build.FINGERPRINT) != null;
    }

    private static void markFailed(Setup s, String why) {
        RUN_FAILED.add(s.id);
        if (s != Setup.FOUR_THREE) return;
        final SharedPreferences p = prefs();
        if (p != null) p.edit().putString("setup_" + s.id, Build.FINGERPRINT + "|" + why.replace('|', '/')).apply();
    }

    /** The lens settings chose the mode again: every setup may run once more. */
    public static void tryAgain() {
        RUN_FAILED.clear();
        final SharedPreferences p = prefs();
        if (p != null) p.edit().clear().apply();
    }

    /** What the lens row shows for DCG+LOFIC: the setups that failed, or the order they run in. */
    public static String state() {
        final boolean four = failed(Setup.FOUR_THREE), sixteen = failed(Setup.SIXTEEN_TEN);
        if (four && sixteen) return Lang.t("DCG+LOFIC: 4:3 и 16:10 не прошли проверку — обычный режим",
                "DCG+LOFIC: 4:3 and 16:10 failed their checks - the normal mode");
        if (four) return Lang.t("DCG+LOFIC: 4:3 не прошёл проверку на этой прошивке, работает 16:10 · 60 к/с",
                "DCG+LOFIC: 4:3 failed its check on this firmware, 16:10 · 60 fps runs");
        return Lang.t("DCG+LOFIC: сначала 4:3 · 30 к/с, иначе 16:10 · 60 к/с", "DCG+LOFIC: 4:3 · 30 fps first, else 16:10 · 60 fps");
    }

    // ---------------------------------------------------------------- the switch

    /** scam_dev xiaomi17_lofic: -1 follows the switch, 1 on, 0 off. */
    static int override() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi17_lofic", -1));
    }

    /**
     * The route runs this session: the Xiaomi 17 Ultra, its main camera opened directly ({@code cameraId} "2"), the switch on
     * «DCG+LOFIC» (or scam_dev xiaomi17_lofic 1; 0 never), a plain photo session ({@code plain}), Android 10 or later (the
     * video-encode reader).
     */
    static boolean wanted(boolean phone, @Nullable String cameraId, boolean switchLofic, int override, boolean plain, int sdk) {
        if (!phone || !CAMERA_ID.equals(cameraId) || !plain || sdk < 29 || override == 0) return false;
        return override == 1 || switchLofic;
    }

    public static boolean wanted(@Nullable String cameraId, boolean plain) {
        return wanted(XiaomiDualGain.device(), cameraId, Dcg14Mode.mode() == Dcg14Mode.LOFIC, override(), plain, Build.VERSION.SDK_INT);
    }

    /** scam_dev xiaomi17_lofic 0: P641's ladder keeps «DCG+LOFIC» (this route is off). */
    public static boolean routeOff() {
        return override() == 0;
    }

    // ---------------------------------------------------------------- the session

    private static volatile Proof sProof;
    private static volatile Setup sSetup;

    public static boolean sessionActive() {
        return sSetup != null;
    }

    @Nullable
    public static Setup setup() {
        return sSetup;
    }

    /** The session's proof says the frames are this setup's. */
    public static boolean proven() {
        final Proof p = sProof;
        return p != null && p.verdict == Verdict.PROVEN;
    }

    /**
     * CaptureController, before a session is configured: the setup this session runs, null when none is left (the session is the
     * normal one). {@code note[0]} gets the message for the screen when every setup failed.
     */
    @Nullable
    public static Setup sessionStart(boolean wanted, String[] note) {
        sProof = null;
        sSetup = null;
        if (!wanted) return null;
        final boolean sixteenOnly = Math.round(PreferenceKeys.scamDevNumber("xiaomi17_lofic_setup", 0)) == 1;
        final List<Setup> left = ladder(failed(Setup.FOUR_THREE), failed(Setup.SIXTEEN_TEN), sixteenOnly);
        if (left.isEmpty()) {
            Log.w(TAG, "XIAOMI LOFIC: no setup left (" + describeFailures() + "): the normal session (P679)");
            if (note != null && note.length > 0) note[0] = Lang.t("DCG+LOFIC: камера не дала пару кадров LOFIC — обычный режим. "
                    + "Повторить попытку можно в настройках объектива.", "DCG+LOFIC: the camera gave no LOFIC pair - the normal mode. "
                    + "The lens settings can try again.");
            return null;
        }
        final Setup s = left.get(0);
        sSetup = s;
        sProof = new Proof(s, Math.round(PreferenceKeys.scamDevNumber("xiaomi17_lofic_window", WINDOW)));
        com.scamera.app.processing.RawPayloadCheck.setKeepDepth(true);
        Log.i(TAG, "XIAOMI LOFIC camera " + CAMERA_ID + ": setup " + s.label + " (session type 0x" + Integer.toHexString(s.sessionType)
                + ", AE [" + s.fps + ", " + s.fps + "], expected mode " + java.util.Arrays.toString(s.modes)
                + (s.askRow >= 0 ? ", row " + s.askRow + " asked by current_mode (root-free candidate)" : ", the HAL's own row")
                + ", " + s.rows + " rows) - outputs " + outputs(s) + "; tried before: " + describeFailures() + " (P679)");
        return s;
    }

    private static String describeFailures() {
        final StringBuilder sb = new StringBuilder();
        for (Setup s : Setup.values()) if (failed(s)) sb.append(sb.length() == 0 ? "" : ", ").append(s.id).append(" failed");
        return sb.length() == 0 ? "nothing failed" : sb.toString();
    }

    /** The session ended: nothing of it applies to the next one. */
    public static void sessionEnd() {
        sSetup = null;
        sProof = null;
        com.scamera.app.processing.RawPayloadCheck.setKeepDepth(false);
    }

    /** The session was refused or failed ({@code why}): its setup is marked, the next session takes the next one. */
    public static void sessionFailed(String why) {
        final Setup s = sSetup;
        if (s == null) return;
        markFailed(s, why);
        Log.w(TAG, "XIAOMI LOFIC setup " + s.label + " FAILED: " + why + " -> " + (s == Setup.FOUR_THREE
                ? "the 16:10 · 60 fps setup next (4:3 is not tried again on this firmware)" : "the normal session") + " (P679)");
        sessionEnd();
    }

    private static final CaptureResult.Key<int[]> R_COUNT = new CaptureResult.Key<>(K_EXPOSURE_COUNT, int[].class);

    @Nullable
    public static Integer exposureCount(@Nullable CaptureResult result) {
        if (result == null) return null;
        try {
            final int[] v = result.get(R_COUNT);
            return v == null || v.length == 0 ? null : v[0];
        } catch (RuntimeException unread) {
            return null;
        }
    }

    private static volatile String sLastState;

    /**
     * A viewfinder result of the session: the mode and the ExposureCount (logged on change). Returns FAILED once when the setup
     * is refuted, PROVEN once when proven, PENDING otherwise.
     */
    public static Verdict onResult(@Nullable CaptureResult result) {
        final Proof p = sProof;
        if (p == null || result == null) return Verdict.PENDING;
        final Integer mode = SensorModeBand.reportedMode(result), count = exposureCount(result);
        p.result(mode, count);
        final String state = "current_mode " + mode + ", ExposureCount " + count;
        if (!state.equals(sLastState)) {
            sLastState = state;
            Log.i(TAG, "XIAOMI LOFIC " + p.setup.label + ": results report " + state + (mode == null ? "" : p.setup.askRow >= 0
                    ? " (row " + p.setup.askRow + " was asked: an echo proves nothing)" : p.setup.modeOf(mode) ? " (the setup's mode)"
                    : " (NOT the setup's mode " + java.util.Arrays.toString(p.setup.modes) + ")") + " (P679)");
        }
        return settle(p);
    }

    /** A pair of the session: the rows its Long and its Short image filled. */
    public static Verdict onPair(int longRows, int shortRows, long timestamp) {
        final Proof p = sProof;
        if (p == null) return Verdict.PENDING;
        if (p.pairs < 3 || longRows != p.setup.rows || shortRows != p.setup.rows)
            Log.i(TAG, "XIAOMI LOFIC " + p.setup.label + ": pair " + (p.pairs + 1) + " at " + timestamp + ": Long RAW14 " + longRows
                    + " rows, Short RAW10 " + shortRows + " rows (want " + p.setup.rows + ") (P679)");
        p.pair(longRows, shortRows);
        return settle(p);
    }

    private static Verdict settle(Proof p) {
        if (p.decided) return Verdict.PENDING;
        final Verdict v = p.verdict();
        if (v == Verdict.PROVEN) Log.i(TAG, "XIAOMI LOFIC PROVEN: " + p.describe() + " (P679)");
        else if (v == Verdict.FAILED) Log.w(TAG, "XIAOMI LOFIC check failed: " + p.failure + "; " + p.describe() + " (P679)");
        return v;
    }

    /** The failure of the last decided setup, for the message. */
    public static String lastFailure() {
        final Proof p = sProof;
        return p == null ? "" : p.failure;
    }

    // ---------------------------------------------------------------- the ring of pairs

    /** One image of a stream, copied out of the reader (its used bytes only). */
    public static final class Image {
        public long timestamp;
        public ByteBuffer data;
        public int used, rows;
    }

    /** A Long and a Short image of one timestamp, and its result. */
    public static final class Pair {
        public Image longImage, shortImage;
        @Nullable public CaptureResult result;
        @Nullable public Integer exposureCount;

        public long timestamp() {
            return longImage != null ? longImage.timestamp : shortImage != null ? shortImage.timestamp : 0;
        }

        public boolean complete() {
            return longImage != null && shortImage != null;
        }

        /** Usable for a shot: both images and a result reporting ExposureCount 2. */
        public boolean usable() {
            return complete() && result != null && exposureCount != null && exposureCount == 2;
        }
    }

    /**
     * The session's newest pairs, by timestamp. The images are copies (the readers get their buffers back at once), the copies'
     * buffers pooled; a shot freezes the ring and takes its pairs out.
     */
    public static final class Ring {
        private final ArrayDeque<Pair> pairs = new ArrayDeque<>();
        private final ArrayDeque<ByteBuffer> longPool = new ArrayDeque<>(), shortPool = new ArrayDeque<>();
        private final ArrayDeque<CaptureResult> results = new ArrayDeque<>();
        private int capacity;
        private boolean frozen;
        private long stored, dropped;

        public Ring(int capacity) {
            this.capacity = Math.max(1, capacity);
        }

        public synchronized void capacity(int capacity) {
            this.capacity = Math.max(1, capacity);
            while (pairs.size() > this.capacity) release(pairs.pollFirst());
        }

        /** A copy of {@code used} bytes of {@code source} in a pooled buffer. */
        private ByteBuffer copy(ByteBuffer source, int used, boolean isLong) {
            final ArrayDeque<ByteBuffer> pool = isLong ? longPool : shortPool;
            ByteBuffer b = pool.poll();
            if (b == null || b.capacity() < used) b = ByteBuffer.allocateDirect(used);
            b.clear();
            final ByteBuffer src = source.duplicate();
            src.position(0).limit(Math.min(used, src.capacity()));
            b.put(src);
            b.flip();
            return b;
        }

        private void release(@Nullable Pair p) {
            if (p == null) return;
            if (p.longImage != null && p.longImage.data != null) longPool.add(p.longImage.data);
            if (p.shortImage != null && p.shortImage.data != null) shortPool.add(p.shortImage.data);
            p.longImage = p.shortImage = null;
        }

        @Nullable
        private Pair find(long timestamp) {
            for (Pair p : pairs) if (Math.abs(p.timestamp() - timestamp) <= PAIR_NS) return p;
            return null;
        }

        /**
         * A stream's image ({@code used} bytes of {@code source}); returns the pair it completed (both images present now), null
         * otherwise. Nothing is taken while a shot holds the ring.
         */
        @Nullable
        public synchronized Pair store(Stream stream, long timestamp, ByteBuffer source, int used) {
            if (frozen || stream == Stream.NONE || used <= 0) { dropped++; return null; }
            Pair p = find(timestamp);
            if (p == null) {
                while (pairs.size() >= capacity) release(pairs.pollFirst());
                p = new Pair();
                pairs.addLast(p);
            }
            final Image img = new Image();
            img.timestamp = timestamp;
            img.used = used;
            img.rows = rows(used, stream);
            img.data = copy(source, used, stream == Stream.LONG);
            if (stream == Stream.LONG) {
                if (p.longImage != null && p.longImage.data != null) longPool.add(p.longImage.data);
                p.longImage = img;
            } else {
                if (p.shortImage != null && p.shortImage.data != null) shortPool.add(p.shortImage.data);
                p.shortImage = img;
            }
            for (CaptureResult r : results) if (matches(r, p.timestamp())) attach(p, r);
            stored++;
            return p.complete() ? p : null;
        }

        private static boolean matches(@Nullable CaptureResult r, long timestamp) {
            if (r == null) return false;
            final Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
            return ts != null && Math.abs(ts - timestamp) <= PAIR_NS;
        }

        private static void attach(Pair p, CaptureResult r) {
            p.result = r;
            p.exposureCount = exposureCount(r);
        }

        /** A result of the session, kept for the pairs of its timestamp (the newest 32). */
        public synchronized void result(CaptureResult r, long timestamp) {
            results.addLast(r);
            while (results.size() > 32) results.pollFirst();
            final Pair p = find(timestamp);
            if (p != null) attach(p, r);
        }

        /** A pair of this timestamp is here (one of its images came). */
        public synchronized boolean has(long timestamp) {
            return find(timestamp) != null;
        }

        public synchronized void freeze() {
            frozen = true;
        }

        public synchronized void release() {
            frozen = false;
        }

        /**
         * A shot's pairs: up to {@code count} of the newest usable ones taken at or before {@code cutoff} (0: any), newest first,
         * taken out of the ring (their buffers belong to the caller); the ring is emptied and released.
         */
        public synchronized List<Pair> take(int count, long cutoff) {
            final List<Pair> out = new ArrayList<>();
            final java.util.Iterator<Pair> it = pairs.descendingIterator();
            while (it.hasNext()) {
                final Pair p = it.next();
                if (out.size() < count && p.usable() && (cutoff <= 0 || p.timestamp() <= cutoff)) {
                    out.add(p);
                    it.remove();
                }
            }
            for (Pair p : pairs) release(p);
            pairs.clear();
            frozen = false;
            return out;
        }

        /** Pairs whose Long image is here but no usable Short read (ExposureCount 1 or none): a shot of the Long frames alone. */
        public synchronized List<Pair> takeLongOnly(int count, long cutoff) {
            final List<Pair> out = new ArrayList<>();
            final java.util.Iterator<Pair> it = pairs.descendingIterator();
            while (it.hasNext()) {
                final Pair p = it.next();
                if (out.size() < count && p.longImage != null && p.result != null && (cutoff <= 0 || p.timestamp() <= cutoff)) {
                    out.add(p);
                    it.remove();
                }
            }
            for (Pair p : pairs) release(p);
            pairs.clear();
            frozen = false;
            return out;
        }

        public synchronized int size() {
            return pairs.size();
        }

        public synchronized int usableCount() {
            int n = 0;
            for (Pair p : pairs) if (p.usable()) n++;
            return n;
        }

        public synchronized void clear() {
            for (Pair p : pairs) release(p);
            pairs.clear();
            results.clear();
            longPool.clear();
            shortPool.clear();
            frozen = false;
        }

        public synchronized String describe() {
            int complete = 0, usable = 0;
            for (Pair p : pairs) {
                if (p.complete()) complete++;
                if (p.usable()) usable++;
            }
            return pairs.size() + " of " + capacity + " pairs (" + complete + " complete, " + usable + " with ExposureCount 2), " + stored
                    + " images stored, " + dropped + " not taken";
        }
    }

    /** The pairs a shot takes: scam_dev lofic_pairs (default {@link #PAIRS}), 1..16. */
    public static int pairsWanted() {
        return Math.max(1, Math.min(16, Math.round(PreferenceKeys.scamDevNumber("lofic_pairs", PAIRS))));
    }
}
