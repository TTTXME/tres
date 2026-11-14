package com.scamera.app.capture;

import android.os.Build;

import androidx.annotation.Nullable;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P631 (owner, 2026-11-11: «Исправь работу фулреза на Xiaomi 17 Ultra»; log of build 31256 and a screenshot: «50 MP» on 1x, the
 * viewfinder black with a white strip): the main camera's module row of the full size (SensorModeTable row 11, 8192x6144 beside
 * the binned 4096x3072 rows, asked with org.codeaurora.qcamera3.sensor_meta_data.current_mode) runs on this phone's HAL only in a
 * RAW-only session. With «Поток ISP» Off / Auto the session had the RAW stream alone: the viewfinder ran (VF_DRAW 20.7 fps) and the
 * shot merged 8192x6144. With «Поток ISP» On the 1440x1920 ISP stream beside it gave six frames and the HAL stopped (PREVIEW_STALL
 * "frames stopped"); with «Фиктивное откл.» the HAL failed every request (ERROR_REQUEST, "got error frame" on every frame number)
 * and three restarts of the same session left the viewfinder black.
 * <ul>
 *   <li>A full-size row picked on the Xiaomi 17 Ultra leaves the ISP stream out of its session whatever «Поток ISP» says
 *       ({@link #ispOff}); any other row picked on a Xiaomi phone does once a session of it with the ISP stream stalled
 *       ({@link #noteIspRefused}, until the app restarts).</li>
 *   <li>That stream came without its black pedestal ("RAW black floor: 36864 of 49152 blocks below 16 DN"): the colour-block
 *       detector, which skips the tiles that do not reach 128 DN over the declared black of 64, found no answer in 12 frames, so
 *       the viewfinder drew it as a plain Bayer while the merge took the row's declared Quad. The detector of such a session
 *       measures over the frame's own floor ({@link #detectorBlack}); a confident plain Bayer (the HAL's own remosaic of the full
 *       readout) is then what the shot merges ({@link #shotBlock}), a confident Quad keeps the native mosaic merge.</li>
 *   <li>The first frame of each such session is described once in the log ({@link #frameLine}): the mode the result reports, the
 *       frame's floor and its 2x2 site statistics (a Quad mosaic, a plain Bayer, or the binned frame written twice over).</li>
 * </ul>
 * scam_dev "xiaomi_row_raw_only 0" turns it off.
 */
public final class XiaomiSensorRow {
    private XiaomiSensorRow() {}

    static final String TAG = "SCAM_CAPTURE";

    /** A Xiaomi-made phone (Xiaomi, Redmi, POCO). */
    static boolean xiaomi(@Nullable String manufacturer, @Nullable String brand) {
        final String m = manufacturer == null ? "" : manufacturer.trim().toLowerCase(Locale.ROOT);
        final String b = brand == null ? "" : brand.trim().toLowerCase(Locale.ROOT);
        return m.equals("xiaomi") || b.equals("xiaomi") || b.equals("redmi") || b.equals("poco");
    }

    /** This phone is a Xiaomi one and the route is on (scam_dev "xiaomi_row_raw_only 0": off). */
    public static boolean device() {
        return xiaomi(Build.MANUFACTURER, Build.BRAND) && PreferenceKeys.scamDevSwitch("xiaomi_row_raw_only", true);
    }

    /**
     * The session of a picked row leaves its ISP stream out: on the Xiaomi 17 Ultra its full-size row ({@code fullRow}: the
     * largest row of the table while a binned row of half or a quarter its sides exists, SensorModePick.declaredBlock), and on any
     * Xiaomi phone a row whose session with the ISP stream stalled before ({@code learned}).
     */
    static boolean ispOff(boolean xiaomi, boolean seventeenUltra, boolean fullRow, boolean learned) {
        return xiaomi && (seventeenUltra && fullRow || learned);
    }

    private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();

    private static String key(@Nullable String camera, int mode) {
        return camera + "|" + mode;
    }

    /** A session of {@code camera}'s row {@code mode} with the ISP stream stalled before (this run of the app). */
    static boolean ispRefused(@Nullable String camera, int mode) {
        return camera != null && REFUSED.contains(key(camera, mode));
    }

    /**
     * CaptureController: the session of a picked row stalled with the ISP stream in it; true when this is news (the next session
     * of the row leaves the ISP stream out).
     */
    public static boolean noteIspRefused(@Nullable String camera, int mode, String why) {
        if (camera == null || !REFUSED.add(key(camera, mode))) return false;
        Log.w(TAG, "Xiaomi sensor row camera " + camera + " mode " + mode + ": its session with the ISP stream stalled (" + why
                + "): the next sessions of the row leave the ISP stream out, the RAW viewfinder (P631)");
        return true;
    }

    /** SensorModePick.active: the pick, marked to leave the ISP stream out where {@link #ispOff} says so. */
    @Nullable
    static SensorModePick.Pick mark(@Nullable String camera, @Nullable SensorModePick.Pick pick, @Nullable int[] table) {
        if (pick == null || pick.ispOff || !device()) return pick;
        final boolean full = SensorModePick.declaredBlock(table, pick.mode) > 1;
        return ispOff(true, XiaomiTeleZoom.phone(), full, ispRefused(camera, pick.mode)) ? pick.withoutIsp() : pick;
    }

    /** Why the session of {@code pick} has no ISP stream, for the log; null when not by this route. */
    @Nullable
    public static String ispOffReason(@Nullable String camera, @Nullable SensorModePick.Pick pick) {
        if (pick == null || !pick.ispOff) return null;
        return ispRefused(camera, pick.mode)
                ? "a Xiaomi row whose session with the ISP stream stalled before, P631"
                : "the Xiaomi HAL runs the full-size row " + pick.mode
                        + " only without the ISP stream, whatever the module's ISP stream setting, P631";
    }

    // ---------------------------------------------------------------- the stream's colour block

    /**
     * The black the colour-block detector measures over: the frame's own {@code floor} when it lies under half the declared
     * {@code black} (a stream without its pedestal: the declared black would leave every dark tile out), else the declared one.
     */
    public static float detectorBlack(float black, float floor) {
        return floor >= 0f && floor < black * 0.5f ? floor : black;
    }

    /**
     * The colour block a shot of a picked Xiaomi row declares at the shutter: its declared mosaic ({@code declared}), unless the
     * stream's frames proved a plain Bayer ({@code stored} 1: the HAL remosaicked the full readout) - then 0, so the stored 1 is
     * what the merge takes (MosaicBlockStore.choose never lets a stored 1 beat a declared mosaic). Elsewhere {@code declared}.
     */
    public static int shotBlock(int declared, boolean xiaomiPick, int stored) {
        return xiaomiPick && stored == 1 ? 0 : declared;
    }

    // ---------------------------------------------------------------- the first frame of a session

    private static volatile String armed;
    private static int linesTaken;
    private static long nextLineAtMs;
    /**
     * P695: frame lines of a session - its first frame, then one every {@link #FRAME_LINE_EVERY_MS} (the 15 Ultra periscope's row 6
     * turned to stripes a few seconds in: what its frames became is in the later lines).
     */
    public static final int FRAME_LINES = 5;
    static final long FRAME_LINE_EVERY_MS = 3000;

    /** CaptureController: a session with {@code pick} starts; its first frames are described ({@link #takeFrameLog}). */
    public static void armFrameLog(@Nullable String camera, @Nullable SensorModePick.Pick pick) {
        armFrameLog(camera, pick, device());
    }

    /** {@link #armFrameLog(String, SensorModePick.Pick)} on a phone that is ({@code xiaomi}) a Xiaomi one or not (tests). */
    static synchronized void armFrameLog(@Nullable String camera, @Nullable SensorModePick.Pick pick, boolean xiaomi) {
        armed = camera != null && pick != null && xiaomi ? key(camera, pick.mode) : null;
        linesTaken = 0;
        nextLineAtMs = Long.MIN_VALUE;
    }

    /** True once per armed session, for its first frame. */
    public static synchronized boolean takeFrameLog(@Nullable String camera, int mode) {
        return takeFrameLog(camera, mode, Long.MIN_VALUE) == 0;
    }

    /**
     * P695: the index of the frame line due now ({@code nowMs}, uptime), -1 when none: the session's first frame (0), then one every
     * {@link #FRAME_LINE_EVERY_MS}, {@link #FRAME_LINES} in all; {@code nowMs} Long.MIN_VALUE: the first only.
     */
    public static synchronized int takeFrameLog(@Nullable String camera, int mode, long nowMs) {
        if (armed == null || !armed.equals(key(camera, mode))) return -1;
        if (nowMs == Long.MIN_VALUE) {
            if (linesTaken > 0) return -1;
            armed = null;
            return 0;
        }
        if (linesTaken > 0 && nowMs < nextLineAtMs) return -1;
        final int line = linesTaken++;
        nextLineAtMs = nowMs + FRAME_LINE_EVERY_MS;
        if (linesTaken >= FRAME_LINES) armed = null;
        return line;
    }

    /**
     * P695: {mean |d| down, mean |d| across} of sites 8 apart (the same colour in a Bayer, Quad or Tetra frame) at least 32 DN over
     * {@code floor}, in the frame's centre; {0, 0} when nothing is read. A picture has both of the same order; a frame whose rows
     * repeat one line (vertical stripes in the sensor's frame, horizontal on the portrait screen) has «down» near 0 against
     * «across», and the other way round for repeated columns.
     */
    static double[] repeatStats(@Nullable ByteBuffer raw, int width, int height, int rowStride, float floor) {
        final double[] out = {0, 0};
        if (raw == null || width < 64 || height < 64 || rowStride < width * 2) return out;
        final ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int base = b.position(), limit = b.limit();
        final int min = Math.round(floor) + 32;
        double down = 0, across = 0;
        long nDown = 0, nAcross = 0;
        for (int y = height / 10; y + 8 < height * 9 / 10; y += 16) {
            final long row = base + (long) y * rowStride, below = row + 8L * rowStride;
            if (below + 2L * width > limit) break;
            for (int x = width / 10; x + 8 < width * 9 / 10; x += 7) {
                final int v = b.getShort((int) (row + 2L * x)) & 0xFFFF;
                if (v < min) continue;
                final int r = b.getShort((int) (row + 2L * (x + 8))) & 0xFFFF;
                final int d = b.getShort((int) (below + 2L * x)) & 0xFFFF;
                if (r >= min) { across += Math.abs(v - r); nAcross++; }
                if (d >= min) { down += Math.abs(v - d); nDown++; }
            }
        }
        if (nDown < 500 || nAcross < 500) return out;
        return new double[]{down / nDown, across / nAcross};
    }

    /** P695: what {@link #repeatStats} says: "rows repeat", "columns repeat", "a picture" or "too dark to tell". */
    static String repeatText(double[] s) {
        if (s == null || s.length < 2 || s[0] + s[1] <= 0) return "too dark to tell";
        if (s[0] < 0.2 * s[1] && s[1] >= 2) return "ROWS REPEAT (stripes)";
        if (s[1] < 0.2 * s[0] && s[0] >= 2) return "COLUMNS REPEAT (stripes)";
        return "a picture";
    }

    /** P695: the tail of a frame line - its index, the stream's packed format, the frame's exposure and its repeat statistics. */
    public static String laterText(int line, @Nullable Integer packedFormat, @Nullable Long exposureNs, @Nullable Integer iso,
                                   @Nullable ByteBuffer raw, int width, int height, int rowStride) {
        final float floor = frameFloor(raw, width, height, rowStride);
        final double[] r = repeatStats(raw, width, height, rowStride, Math.max(0f, floor));
        return " | frame line " + (line + 1) + "/" + FRAME_LINES + (packedFormat == null ? "" : ", format " + packedFormat + " unpacked")
                + ", exposure " + (exposureNs == null ? "?" : String.format(Locale.ROOT, "%.3f ms", exposureNs / 1e6)) + " ISO "
                + (iso == null ? "?" : iso) + ", |d| 8 sites down / across " + String.format(Locale.ROOT, "%.1f / %.1f", r[0], r[1])
                + " -> " + repeatText(r) + " (P695)";
    }

    /**
     * The low percentile (2 %) of the sites of the frame's centre, in DN (values above 4095 count as 4095); -1 when the frame
     * cannot be read. A stream with its pedestal has it near the declared black, one without it near 0.
     */
    public static float frameFloor(@Nullable ByteBuffer raw, int width, int height, int rowStride) {
        if (raw == null || width < 64 || height < 64 || rowStride < width * 2) return -1f;
        final ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int base = b.position(), limit = b.limit();
        final int[] hist = new int[4096];
        int n = 0;
        for (int y = height / 10; y < height * 9 / 10; y += 16) {
            final long row = base + (long) y * rowStride;
            for (int x = width / 10; x < width * 9 / 10; x += 15) {
                final long at = row + 2L * x;
                if (at + 2 > limit) break;
                hist[Math.min(4095, b.getShort((int) at) & 0xFFFF)]++;
                n++;
            }
        }
        if (n == 0) return -1f;
        final int target = Math.max(1, n / 50);
        int sum = 0;
        for (int v = 0; v < hist.length; v++) {
            sum += hist[v];
            if (sum >= target) return v;
        }
        return 4095f;
    }

    /** What the frame's 2x2 site statistics say. */
    enum Grain { QUAD, BAYER, DUPLICATED, UNKNOWN }

    /**
     * {within, across, exact, pairs, exactAcross}: of horizontal neighbour pairs at least 32 DN over {@code floor}, the mean
     * |difference| of the pairs inside a 2x2 cell (sites 0-1, 2-3 of every 4), of the pairs across two cells (sites 1-2, 3-4), the
     * share of the inside pairs that are equal, their count and the share of the across pairs that are equal. Rows of the
     * frame's centre, every 8th pair of rows.
     */
    static double[] cellStats(@Nullable ByteBuffer raw, int width, int height, int rowStride, float floor) {
        final double[] out = {0, 0, 0, 0, 0};
        if (raw == null || width < 64 || height < 64 || rowStride < width * 2) return out;
        final ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int base = b.position(), limit = b.limit();
        final int min = Math.round(floor) + 32;
        double within = 0, across = 0;
        long nWithin = 0, nAcross = 0, exact = 0, exactAcross = 0;
        final int x0 = (width / 10) & ~3, x1 = (width * 9 / 10) & ~3;
        for (int y = (height / 10) & ~1; y < height * 9 / 10; y += 16) {
            final long row = base + (long) y * rowStride;
            if (row + 2L * (x1 + 1) > limit) break;
            for (int x = x0; x + 4 < x1; x += 2) {
                final int a = b.getShort((int) (row + 2L * x)) & 0xFFFF;
                final int c = b.getShort((int) (row + 2L * (x + 1))) & 0xFFFF;
                final int d = b.getShort((int) (row + 2L * (x + 2))) & 0xFFFF;
                if (a >= min && c >= min) {
                    within += Math.abs(a - c);
                    nWithin++;
                    if (a == c) exact++;
                }
                if (c >= min && d >= min) {
                    across += Math.abs(c - d);
                    nAcross++;
                    if (c == d) exactAcross++;
                }
            }
        }
        if (nWithin == 0 || nAcross == 0) return out;
        return new double[]{within / nWithin, across / nAcross, (double) exact / nWithin, nWithin, (double) exactAcross / nAcross};
    }

    /**
     * The grain of {@link #cellStats}: the binned frame written twice over (at least 90 % of the pairs of one phase equal), a Quad
     * mosaic (the pairs of one phase differ by less than 0.6 of the other's: the same colour inside a cell, whichever phase the
     * cells start at), else a plain Bayer; unknown with fewer than 1000 pairs.
     */
    static Grain grain(double[] stats) {
        if (stats == null || stats.length < 5 || stats[3] < 1000) return Grain.UNKNOWN;
        if (Math.max(stats[2], stats[4]) >= 0.9) return Grain.DUPLICATED;
        return Math.min(stats[0], stats[1]) < 0.6 * Math.max(stats[0], stats[1]) ? Grain.QUAD : Grain.BAYER;
    }

    static String grainText(Grain g) {
        switch (g) {
            case QUAD: return "a Quad mosaic: the sensor's own full readout";
            case BAYER: return "a plain Bayer: the HAL's remosaic of the full readout";
            case DUPLICATED: return "the binned frame written twice over: no full resolution";
            default: return "too dark to tell";
        }
    }

    /** The log line of a session's first frame of a picked Xiaomi row. */
    public static String frameLine(String camera, SensorModePick.Pick pick, @Nullable Integer reported, @Nullable ByteBuffer raw,
                                   int width, int height, int rowStride, float black) {
        final float floor = frameFloor(raw, width, height, rowStride);
        final double[] s = cellStats(raw, width, height, rowStride, detectorBlack(black, floor)); // over the black the data has
        return "Xiaomi sensor row camera " + camera + " " + pick + ": the result reports mode "
                + (reported == null ? "none" : reported) + (reported != null && reported != pick.mode ? " (NOT the picked row)" : "")
                + ", frame " + width + "x" + height + " stride " + rowStride + ", floor " + Math.round(floor) + " DN (declared black "
                + Math.round(black) + (detectorBlack(black, floor) != black ? ", no pedestal: the detector measures over the floor" : "")
                + "), 2x2 cell pairs |d| " + String.format(Locale.ROOT, "%.1f", s[0]) + " inside / "
                + String.format(Locale.ROOT, "%.1f", s[1]) + " across, " + Math.round(s[2] * 100) + " % equal of " + (long) s[3]
                + " -> " + grainText(grain(s)) + ", ISP stream " + (pick.ispOff ? "out of the session" : "in the session") + " (P631)";
    }
}
