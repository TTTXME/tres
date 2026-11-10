package com.particlesdevs.photoncamera.capture;

import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P598 (owner, 2026-11-10, nubia Z60 Ultra NX721J: «фулрез не работает»; the 50 MP must be the sensor's own mosaic, never the
 * HAL's remosaic, without root): the stock firmware's maximum-resolution RAW (P588, NubiaFullRes.maxRes) is a plain Bayer - the
 * log of build 31249 on camera 2 (OV50E40, 8192x6144): MosaicStream «colour block 1 (confident; tile votes b1/b2/b4 8629/0/0)».
 * <p>
 * What is known (SCAM TEST 2026-11-09 17:13 without the Magisk module, 2026-11-02 with it; the stock camera APK
 * com.android.camera 11.0.101): the Magisk module that gave the native Quad replaced exactly the sensor modules
 * /vendor/lib64/camera/com.zte.sensormodule.zte_{imx800,ov50e40,ov64b40}_cerro.bin (285725 / 531529 / 467404 bytes of
 * 2026-10-26 against the stock 400483 / 692894 / 636839); with it the rear cameras were no QCFA sensors (no quadra_cfa tags, the
 * full array as the normal RAW size), without it they are (is_qcfa_sensor 1, qcfa_dimension 8192x6144, binned static metadata
 * and a maximum-resolution map). The stock SensorModeTable's full row runs 20 fps on the OV50E40 and 15 fps on the OV64B40
 * (30 with the module): another register set, likely the sensor's own remosaic - which no Camera2 key can undo. The stock
 * camera never takes a full-size RAW (its Pro DNG is the largest RAW_SENSOR of the default map, 4096x3072); its 50 MP is a
 * JPEG: the QCFA mode's session operation mode 0x8012 (QcfaStreamController), com.nubia.chi.app_mode 1, the ZTE feature bit
 * 0x20 for the QCFA picture size (getZteModeFeatureValue), SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION on the snapshot (setPixelMode)
 * and com.zte.chi.control.CustomRemosiac 1 in bright light (getZteRemosaic: the QCFA size, no HDR, lux index under the
 * threshold).
 * <p>
 * So the route tries, one setting a FULLRES session, what the firmware itself offers: the plain session first (P588), the stock
 * camera's 50 MP keys and its QCFA mode, then the camera's own Qualcomm session keys (org.codeaurora.qcamera3.sessionParameters
 * RawCbSourceType 1..3, EnableXCFAOptimization 1, EnableIdealRAW 1) - only those the camera lists among its session keys
 * (a request-only key of the stock camera rides on every request). Each session is judged by MosaicStream's own verdict: a
 * confident block 2 (the sensor's Quad) keeps the setting for the camera; a plain Bayer, another block, a refused session, a
 * camera error, no frame, or three sessions without a verdict fail it (the reason kept) and the next setting runs at once. The
 * trials are stored per camera and firmware under NubiaFullRes' keys (FullResTrials: a firmware update or «Попробовать снова»
 * starts them again), so they never repeat on every launch; once every setting failed the camera's 50 Мп is dimmed with one
 * notice («50 Мп: HAL этой прошивки отдаёт только ремозаик, без рута недоступно»).
 * <p>
 * scam_dev: «nubia_probe_force N» runs only setting N of {@link #CANDIDATES} (1-based; its verdict is kept as well),
 * «nubia_probe_reset 1» forgets the trials at every app start.
 */
public final class NubiaRemosaicProbe {
    private NubiaRemosaicProbe() { }

    static final String TAG = "NubiaRemosaicProbe";

    /** The stock camera's keys (ExternalKeys$RequestKeys of com.android.camera 11.0.101) and the camera's Qualcomm session keys. */
    static final String ZTE_FEATURE = "com.zte.camera.sessionParameters.feature";
    static final String ZTE_CUSTOM_REMOSAIC = "com.zte.chi.control.CustomRemosiac";
    static final String NUBIA_APP_MODE = "com.nubia.chi.app_mode";
    static final String RAW_CB_SOURCE = "org.codeaurora.qcamera3.sessionParameters.RawCbSourceType";
    static final String XCFA_OPTIMIZATION = "org.codeaurora.qcamera3.sessionParameters.EnableXCFAOptimization";
    static final String IDEAL_RAW = "org.codeaurora.qcamera3.sessionParameters.EnableIdealRAW";
    /** The ZTE feature bit the stock camera sets for the QCFA picture size (Normal / Pro / QCFA getZteModeFeatureValue). */
    static final int ZTE_FEATURE_QCFA = 0x20;
    /** The session operation mode of the stock QCFA mode (QcfaStreamController.configSession). */
    static final int STOCK_QCFA_MODE = 0x8012;
    /** Sessions of one setting without a verdict of its frames before it counts as failed. */
    static final int STARTS = 3;
    /** The reason code of a setting whose sessions never gave a verdict. */
    static final String NO_VERDICT = "noverdict";

    /** One vendor key of a setting: on the session (a key the camera lists) or on every request only. */
    static final class Key {
        final String name;
        final Class<?> type;
        final Object value;
        final boolean session;

        Key(String name, Class<?> type, Object value, boolean session) {
            this.name = name;
            this.type = type;
            this.value = value;
            this.session = session;
        }

        @Override public String toString() {
            return name + "=" + value + (session ? "" : " (request)");
        }
    }

    /** One setting tried against the HAL's remosaic: its stored id, the session operation mode (0: the normal one), its keys. */
    static final class Candidate {
        final String id;
        final int operationMode;
        final String source;
        final List<Key> keys;

        Candidate(String id, int operationMode, String source, Key... keys) {
            this.id = id;
            this.operationMode = operationMode;
            this.source = source;
            this.keys = Collections.unmodifiableList(Arrays.asList(keys));
        }

        /** The camera lists every session key of this setting (a request-only key needs no listing). */
        boolean applicable(@Nullable Collection<String> listed) {
            for (Key k : keys) if (k.session && (listed == null || !listed.contains(k.name))) return false;
            return true;
        }

        boolean hasSessionKeys() {
            for (Key k : keys) if (k.session) return true;
            return false;
        }

        String describe() {
            final StringBuilder sb = new StringBuilder();
            if (operationMode != 0) sb.append("session mode 0x").append(Integer.toHexString(operationMode));
            for (Key k : keys) sb.append(sb.length() == 0 ? "" : ", ").append(k);
            return (sb.length() == 0 ? "no vendor key" : sb.toString()) + "; " + source;
        }
    }

    private static Key session(String name, Class<?> type, Object value) {
        return new Key(name, type, value, true);
    }

    private static Key request(String name, Class<?> type, Object value) {
        return new Key(name, type, value, false);
    }

    /** The settings in the order they are tried (ids are stored: a new one in a later build is tried after these). */
    static final List<Candidate> CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            new Candidate("plain", 0, "the maximum-resolution session as P588 runs it"),
            new Candidate("zte_qcfa", 0, "the stock camera's 50 MP keys (feature bit 0x20, CustomRemosiac 1)",
                    session(ZTE_FEATURE, Integer.class, ZTE_FEATURE_QCFA), request(ZTE_CUSTOM_REMOSAIC, Byte.class, (byte) 1)),
            new Candidate("zte_qcfa_mode", STOCK_QCFA_MODE, "the stock camera's QCFA mode (session mode 0x8012, app_mode 1)",
                    session(ZTE_FEATURE, Integer.class, ZTE_FEATURE_QCFA), request(ZTE_CUSTOM_REMOSAIC, Byte.class, (byte) 1),
                    request(NUBIA_APP_MODE, Integer.class, 1)),
            new Candidate("rawcb_1", 0, "the camera's RAW callback source 1", session(RAW_CB_SOURCE, Integer.class, 1)),
            new Candidate("rawcb_2", 0, "the camera's RAW callback source 2", session(RAW_CB_SOURCE, Integer.class, 2)),
            new Candidate("rawcb_3", 0, "the camera's RAW callback source 3", session(RAW_CB_SOURCE, Integer.class, 3)),
            new Candidate("xcfa_opt", 0, "the camera's XCFA optimisation on (its templates have 0)",
                    session(XCFA_OPTIMIZATION, Byte.class, (byte) 1)),
            new Candidate("ideal_raw", 0, "the camera's Ideal RAW", session(IDEAL_RAW, Byte.class, (byte) 1))));

    /** The settings of a camera that lists {@code listed} as session keys, in order. */
    static List<Candidate> applicable(@Nullable Collection<String> listed) {
        final List<Candidate> out = new ArrayList<>();
        for (Candidate c : CANDIDATES) if (c.applicable(listed)) out.add(c);
        return out;
    }

    @Nullable
    static Candidate byId(@Nullable String id) {
        if (id == null) return null;
        for (Candidate c : CANDIDATES) if (c.id.equals(id)) return c;
        return null;
    }

    /** The setting of {@link #CANDIDATES} numbered {@code n} (1-based, as scam_dev nubia_probe_force counts), null outside. */
    @Nullable
    static Candidate numbered(int n) {
        return n >= 1 && n <= CANDIDATES.size() ? CANDIDATES.get(n - 1) : null;
    }

    // ---------------------------------------------------------------- the trials (under NubiaFullRes' keys of the camera)

    /** Suffixes under NubiaFullRes.base(camera): the failed settings («id:code,…»), the kept one, the one in flight («id:starts»). */
    static final String FAIL = "_probe_fail", OK = "_probe_ok", RUN = "_probe_run";

    @Nullable
    private static String stored(SharedPreferences p, String camera, String suffix, String stamp) {
        final String base = NubiaFullRes.base(camera);
        return FullResTrials.counts(p, base, base + suffix, stamp) ? FullResTrials.string(p, base + suffix) : null;
    }

    /** id -> reason code of the camera's failed settings, in the order they failed. */
    static Map<String, String> fails(SharedPreferences p, String camera, String stamp) {
        final Map<String, String> out = new LinkedHashMap<>();
        final String s = stored(p, camera, FAIL, stamp);
        if (s == null || s.isEmpty()) return out;
        for (String entry : s.split(",")) {
            final int colon = entry.indexOf(':');
            if (colon > 0) out.put(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
        }
        return out;
    }

    private static String join(Map<String, String> fails) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fails.entrySet()) sb.append(sb.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(e.getValue());
        return sb.toString();
    }

    /** The kept setting of the camera (its frames were the sensor's Quad), null without one. */
    @Nullable
    static String kept(SharedPreferences p, String camera, String stamp) {
        final String s = stored(p, camera, OK, stamp);
        return s == null || s.isEmpty() ? null : s;
    }

    /** What a FULLRES session of the camera runs, from {@link #choose}. */
    static final class Choice {
        final String camera;
        final Candidate candidate;
        /** 1-based among the camera's settings, and their number. */
        final int index, total;
        /** The session's count of this setting without a verdict (1 for the first), 0 for a kept or forced one. */
        final int start;
        final boolean forced, known;

        Choice(String camera, Candidate candidate, int index, int total, int start, boolean forced, boolean known) {
            this.camera = camera;
            this.candidate = candidate;
            this.index = index;
            this.total = total;
            this.start = start;
            this.forced = forced;
            this.known = known;
        }

        @Override public String toString() {
            return "setting " + index + " of " + total + " «" + candidate.id + "» (" + candidate.describe() + ")"
                    + (forced ? ", forced (scam_dev nubia_probe_force)" : known ? ", kept: its frames were the sensor's Quad"
                    : ", session " + start + " of " + STARTS + " without a verdict");
        }
    }

    /**
     * The setting the next FULLRES session of {@code camera} runs, null when none is left (every one failed on this firmware):
     * {@code forced} (1-based, scam_dev) whatever the trials say; else the kept one; else the one in flight (a session that ended
     * without a verdict: again, up to {@link #STARTS} sessions, then it fails with {@link #NO_VERDICT}); else the first one not
     * failed. The one in flight is stored before its session starts (a crash or a closed app counts as a session without a
     * verdict).
     */
    @Nullable
    static Choice choose(SharedPreferences p, String camera, @Nullable Collection<String> listed, String stamp, int forced) {
        final List<Candidate> list = applicable(listed);
        final Candidate f = numbered(forced);
        if (f != null) {
            if (!f.applicable(listed)) {
                Log.w(TAG, "camera " + camera + ": scam_dev nubia_probe_force " + forced + " («" + f.id + "») needs session keys the camera does not list");
                return null;
            }
            return new Choice(camera, f, list.indexOf(f) + 1, list.size(), 0, true, false);
        }
        final Candidate ok = byId(kept(p, camera, stamp));
        if (ok != null && list.contains(ok)) return new Choice(camera, ok, list.indexOf(ok) + 1, list.size(), 0, false, true);
        final Map<String, String> fails = fails(p, camera, stamp);
        final String run = stored(p, camera, RUN, stamp);
        if (run != null) {
            final int colon = run.indexOf(':');
            final Candidate inFlight = byId(colon > 0 ? run.substring(0, colon) : run);
            int starts = 1;
            try {
                if (colon > 0) starts = Integer.parseInt(run.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
            }
            if (inFlight != null && list.contains(inFlight) && !fails.containsKey(inFlight.id)) {
                if (starts < STARTS) {
                    put(p, camera, stamp, RUN, inFlight.id + ":" + (starts + 1));
                    return new Choice(camera, inFlight, list.indexOf(inFlight) + 1, list.size(), starts + 1, false, false);
                }
                fails.put(inFlight.id, NO_VERDICT);
                Log.w("SCAM_CAPTURE", "nubia FULLRES camera " + camera + " remosaic probe: «" + inFlight.id + "» gave no verdict in "
                        + STARTS + " sessions: failed");
            }
        }
        for (Candidate c : list) {
            if (fails.containsKey(c.id)) continue;
            FullResTrials.edit(p, NubiaFullRes.base(camera), stamp).putString(NubiaFullRes.base(camera) + FAIL, join(fails))
                    .putString(NubiaFullRes.base(camera) + RUN, c.id + ":1").apply();
            return new Choice(camera, c, list.indexOf(c) + 1, list.size(), 1, false, false);
        }
        if (!fails.isEmpty()) FullResTrials.edit(p, NubiaFullRes.base(camera), stamp).putString(NubiaFullRes.base(camera) + FAIL, join(fails))
                .remove(NubiaFullRes.base(camera) + RUN).apply();
        return null;
    }

    private static void put(SharedPreferences p, String camera, String stamp, String suffix, String value) {
        FullResTrials.edit(p, NubiaFullRes.base(camera), stamp).putString(NubiaFullRes.base(camera) + suffix, value).apply();
    }

    /** What a failed setting leads to: the next session runs another ({@code next}), or the camera's 50 Мп is off; the notice. */
    static final class Outcome {
        final boolean next;
        final String message;

        Outcome(boolean next, String message) {
            this.next = next;
            this.message = message;
        }
    }

    /** The reason code of a failure (FullResTrials' words, CaptureController's reasons). */
    static String code(@Nullable String why) {
        return FullResTrials.code(why);
    }

    /** The one notice once every setting failed. */
    static String unavailable() {
        return Lang.t("50 Мп: HAL этой прошивки отдаёт только ремозаик, без рута недоступно",
                "50 MP: this firmware's HAL gives only its remosaic, not available without root");
    }

    /**
     * {@code choice}'s session failed for {@code why}: noted (a kept setting is kept no more); the outcome says whether another
     * setting is left for the next session (a forced one never goes on to another).
     */
    static Outcome fail(SharedPreferences p, @Nullable Collection<String> listed, String stamp, Choice choice, String why) {
        final String camera = choice.camera;
        final Map<String, String> fails = fails(p, camera, stamp);
        final String code = code(why);
        fails.remove(choice.candidate.id);
        fails.put(choice.candidate.id, code);
        final SharedPreferences.Editor e = FullResTrials.edit(p, NubiaFullRes.base(camera), stamp)
                .putString(NubiaFullRes.base(camera) + FAIL, join(fails)).remove(NubiaFullRes.base(camera) + RUN);
        if (choice.candidate.id.equals(kept(p, camera, stamp))) e.remove(NubiaFullRes.base(camera) + OK);
        e.apply();
        if (choice.forced)
            return new Outcome(false, Lang.t("50 Мп: способ " + choice.index + " («" + choice.candidate.id + "», scam_dev nubia_probe_force) — "
                            + phrase(code) + "; обычный режим",
                    "50 MP: setting " + choice.index + " («" + choice.candidate.id + "», scam_dev nubia_probe_force) — " + phrase(code)
                            + "; the normal mode"));
        int left = 0;
        for (Candidate c : applicable(listed)) if (!fails.containsKey(c.id)) left++;
        if (left == 0) return new Outcome(false, unavailable());
        return new Outcome(true, Lang.t("50 Мп: способ " + choice.index + " из " + choice.total + " — " + phrase(code) + ", пробую следующий",
                "50 MP: setting " + choice.index + " of " + choice.total + " — " + phrase(code) + ", trying the next"));
    }

    /** {@code choice}'s frames were the sensor's Quad: the setting is kept for the camera; true when it was not kept before. */
    static boolean succeed(SharedPreferences p, String stamp, Choice choice) {
        final String camera = choice.camera;
        final boolean known = choice.candidate.id.equals(kept(p, camera, stamp));
        final Map<String, String> fails = fails(p, camera, stamp);
        fails.remove(choice.candidate.id);
        FullResTrials.edit(p, NubiaFullRes.base(camera), stamp).putString(NubiaFullRes.base(camera) + OK, choice.candidate.id)
                .putString(NubiaFullRes.base(camera) + FAIL, join(fails)).remove(NubiaFullRes.base(camera) + RUN).apply();
        return !known;
    }

    /** Every setting of the camera failed on this firmware (none kept): its 50 Мп is off. */
    static boolean exhausted(SharedPreferences p, String camera, @Nullable Collection<String> listed, String stamp) {
        final List<Candidate> list = applicable(listed);
        if (list.isEmpty()) return false;
        final Candidate ok = byId(kept(p, camera, stamp));
        if (ok != null && list.contains(ok)) return false;
        final Map<String, String> fails = fails(p, camera, stamp);
        for (Candidate c : list) if (!fails.containsKey(c.id)) return false;
        return true;
    }

    /** The text of a reason code. */
    static String phrase(@Nullable String code) {
        if (FullResTrials.HW_REMOSAIC.equals(code)) return Lang.t("ремозаик HAL (обычный Байер)", "the HAL's remosaic (plain Bayer)");
        if (NO_VERDICT.equals(code)) return Lang.t("кадры не дали ответа за " + STARTS + " сессии", "the frames gave no verdict in " + STARTS + " sessions");
        return FullResTrials.phrase(code, false);
    }

    /**
     * What the camera's trials say (the dimmed 50 Мп's explanation, «Настройки по модулям»): the kept setting, or each failed one
     * and why, with the one notice once all failed; null before any trial.
     */
    @Nullable
    static String status(SharedPreferences p, String camera, @Nullable Collection<String> listed, String stamp) {
        final Candidate ok = byId(kept(p, camera, stamp));
        final List<Candidate> list = applicable(listed);
        if (ok != null)
            return Lang.t("50 Мп: мозаика сенсора без ремозаика — способ " + (list.indexOf(ok) + 1) + " («" + ok.id + "»).",
                    "50 MP: the sensor's own mosaic without remosaic — setting " + (list.indexOf(ok) + 1) + " («" + ok.id + "»).");
        final Map<String, String> fails = fails(p, camera, stamp);
        if (fails.isEmpty()) return null;
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fails.entrySet()) {
            final Candidate c = byId(e.getKey());
            sb.append(sb.length() == 0 ? "" : "; ").append(c == null ? e.getKey() : (list.indexOf(c) + 1) + " «" + c.id + "»")
                    .append(" — ").append(phrase(e.getValue()));
        }
        return (exhausted(p, camera, listed, stamp) ? unavailable() + ". " : Lang.t("50 Мп: ", "50 MP: "))
                + Lang.t("Проверено способов: ", "Settings tried: ") + fails.size() + " (" + sb + ").";
    }

    /** Forgets the camera's trials of this probe (its rows' trials stay); the keys removed. */
    static int reset(SharedPreferences p, String camera) {
        final String base = NubiaFullRes.base(camera);
        int n = 0;
        final SharedPreferences.Editor e = p.edit();
        for (String suffix : new String[]{FAIL, OK, RUN})
            if (p.contains(base + suffix)) { e.remove(base + suffix); n++; }
        e.apply();
        return n;
    }

    // ---------------------------------------------------------------- the camera's session keys, the session that runs

    private static final Map<String, Set<String>> LISTED = new ConcurrentHashMap<>();
    private static final Set<String> RESET = ConcurrentHashMap.newKeySet();
    @Nullable private static volatile SharedPreferences testPrefs;

    @Nullable
    private static SharedPreferences prefs() {
        final SharedPreferences t = testPrefs;
        if (t != null) return t;
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int forced() {
        return Math.round(PreferenceKeys.scamDevNumber("nubia_probe_force", 0));
    }

    /** NubiaFullRes.learn: the session keys of a camera on the maximum-resolution route (scam_dev nubia_probe_reset: trials forgotten once a run). */
    static void learn(String camera, @Nullable CameraCharacteristics c) {
        final Set<String> names = new HashSet<>();
        if (c != null && Build.VERSION.SDK_INT >= 28) try {
            for (CaptureRequest.Key<?> k : c.getAvailableSessionKeys()) names.add(k.getName());
        } catch (RuntimeException e) {
            Log.w(TAG, "camera " + camera + ": session keys unreadable: " + e);
        }
        learn(camera, names);
    }

    static void learn(String camera, Collection<String> sessionKeys) {
        LISTED.put(camera, new HashSet<>(sessionKeys));
        final List<Candidate> list = applicable(sessionKeys);
        final StringBuilder ids = new StringBuilder();
        for (Candidate c : list) ids.append(ids.length() == 0 ? "" : ", ").append(c.id);
        final SharedPreferences p = prefs();
        if (p != null && PreferenceKeys.scamDevSwitch("nubia_probe_reset", false) && RESET.add(camera))
            Log.i("SCAM_CAPTURE", "nubia camera " + camera + " remosaic probe: trials forgotten (scam_dev nubia_probe_reset, " + reset(p, camera) + " keys)");
        Log.i("SCAM_CAPTURE", "nubia camera " + camera + " remosaic probe: " + list.size() + " settings (" + ids + ")"
                + (p == null ? "" : ", trials: " + FullResTrials.dump(p, NubiaFullRes.base(camera))));
    }

    /** Every setting of the camera failed on this firmware (scam_dev nubia_probe_force overrides it). */
    public static boolean exhausted(@Nullable String camera) {
        final SharedPreferences p = prefs();
        if (p == null || camera == null || numbered(forced()) != null) return false;
        try {
            return exhausted(p, camera, LISTED.get(camera), NubiaFullRes.stamp());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nullable
    static String status(SharedPreferences p, String camera, String stamp) {
        return status(p, camera, LISTED.get(camera), stamp);
    }

    private static volatile Choice current;

    /**
     * CaptureController: a maximum-resolution FULLRES session of {@code camera} starts - the setting it runs is chosen (and the
     * one in flight stored); false when none is left (the camera's 50 Мп is off on this firmware: the normal session).
     */
    public static synchronized boolean sessionStart(String camera) {
        final SharedPreferences p = prefs();
        if (p == null || camera == null) {
            current = null;
            return false;
        }
        final Choice c = choose(p, camera, LISTED.get(camera), NubiaFullRes.stamp(), forced());
        current = c;
        if (c == null) Log.w("SCAM_CAPTURE", "nubia FULLRES camera " + camera + " remosaic probe: every setting failed on this firmware ("
                + FullResTrials.dump(p, NubiaFullRes.base(camera)) + "): the normal session");
        else Log.i("SCAM_CAPTURE", "nubia FULLRES camera " + camera + " remosaic probe: " + c);
        return c != null;
    }

    /** CaptureController: the session is no maximum-resolution FULLRES one. */
    public static synchronized void noSession() {
        current = null;
    }

    /** A setting of the probe runs in the session of {@code camera}. */
    public static boolean running(@Nullable String camera) {
        final Choice c = current;
        return c != null && camera != null && camera.equals(c.camera);
    }

    /** The session operation mode of the running setting (0: the normal one, or no probe). */
    public static int operationMode(@Nullable String camera) {
        final Choice c = current;
        return running(camera) ? c.candidate.operationMode : 0;
    }

    /** The running setting has keys for the session parameters. */
    public static boolean wantsSession(@Nullable String camera) {
        final Choice c = current;
        return running(camera) && c.candidate.hasSessionKeys();
    }

    /** The running setting for the log of the session parameters. */
    public static String describe() {
        final Choice c = current;
        return c == null ? "none" : "«" + c.candidate.id + "» " + c.candidate.describe();
    }

    /** MosaicStream's key of the session's stream: each setting is a stream of its own (its stored block is its own). */
    public static String streamKey() {
        final Choice c = current;
        return c == null ? "" : "|probe-" + c.candidate.id;
    }

    /**
     * VendorTagUtils.builderSessionApply: the running setting's keys on the session parameters and on every request (a session
     * key a request lacks would be dropped by the camera service; a request-only key never reaches the session's filtered keys).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void apply(@Nullable CaptureRequest.Builder builder) {
        final Choice c = current;
        if (builder == null || c == null) return;
        for (Key k : c.candidate.keys) {
            try {
                builder.set(new CaptureRequest.Key(k.name, k.type), k.value);
            } catch (RuntimeException e) {
                Log.w(TAG, k.name + " not set: " + e);
            }
        }
    }

    /**
     * CaptureController: the running setting's session of {@code camera} failed for {@code why} (a plain Bayer, a refused
     * session, a camera error, no frame); null when no setting runs there.
     */
    @Nullable
    public static synchronized Outcome failed(@Nullable String camera, String why) {
        final Choice c = current;
        final SharedPreferences p = prefs();
        if (c == null || p == null || camera == null || !camera.equals(c.camera)) return null;
        current = null;
        final Outcome o = fail(p, LISTED.get(camera), NubiaFullRes.stamp(), c, why);
        Log.w("SCAM_CAPTURE", "nubia FULLRES camera " + camera + " remosaic probe: setting " + c.index + " «" + c.candidate.id + "» failed ("
                + why + "): " + (o.next ? "the next setting in the next session" : c.forced ? "forced, FULLRES off"
                : "every setting failed, FULLRES off on this firmware") + " [" + FullResTrials.dump(p, NubiaFullRes.base(camera)) + "]");
        return o;
    }

    /** CaptureController: the session's frames are the sensor's Quad (MosaicStream's verdict 2): the notice when newly kept. */
    @Nullable
    public static synchronized String quad(@Nullable String camera) {
        final Choice c = current;
        final SharedPreferences p = prefs();
        if (c == null || p == null || camera == null || !camera.equals(c.camera)) return null;
        final boolean fresh = succeed(p, NubiaFullRes.stamp(), c);
        Log.i("SCAM_CAPTURE", "nubia FULLRES camera " + camera + " remosaic probe: setting " + c.index + " «" + c.candidate.id
                + "» gives the sensor's own Quad (block 2)" + (fresh ? ": kept for this camera" : ""));
        return fresh ? Lang.t("50 Мп: мозаика сенсора без ремозаика (способ " + c.index + " из " + c.total + ")",
                "50 MP: the sensor's own mosaic without remosaic (setting " + c.index + " of " + c.total + ")") : null;
    }

    /** Tests: a new process. */
    static void resetForTest(@Nullable SharedPreferences p) {
        LISTED.clear();
        RESET.clear();
        current = null;
        testPrefs = p;
    }

    /** Tests: the camera's listed session keys without its characteristics. */
    static void listedForTest(String camera, Collection<String> keys) {
        LISTED.put(camera, new HashSet<>(keys));
    }
}
