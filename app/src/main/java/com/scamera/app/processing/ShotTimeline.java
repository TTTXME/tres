package com.scamera.app.processing;

import com.scamera.app.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shot speed (wave 1, W1.0): the stage times of one shot, collected in memory and printed as ONE line
 * ({@code SHOT TIMELINE ms: ...}, every stage in ms after the shutter) once the shot is saved. OPPO's log flow control drops
 * hundreds of rows per shot, so the per-stage lines alone could not be added up; this one line survives it.
 * <p>
 * The capture marks go to the shot being captured ({@link #begin}, {@link #capture}); when the burst is queued for processing
 * its timeline moves with it ({@link #detach} on the camera thread, {@link #attach} on the processing thread), so a second
 * shot captured meanwhile starts its own. Processing marks ({@link #mark}) go to the shot being processed. Only the first
 * mark of a name counts. No effect on the image.
 */
public final class ShotTimeline {
    private static final String TAG = "ShotTimeline";
    private static ShotTimeline capturing, processing;

    private final Map<String, Long> marks = new LinkedHashMap<>();
    /** P236: values of the shot beside its marks (thermal state, viewfinder fps, shader compile ms), for the summary line. */
    private final Map<String, String> notes = new LinkedHashMap<>();

    private ShotTimeline() {}

    private void put(String name, long nanos) {
        if (!marks.containsKey(name)) marks.put(name, nanos);
    }

    /** Starts the timeline of a new capture at the shutter press. */
    public static synchronized void begin(String name) {
        com.scamera.app.ui.camera.views.viewfinder.VfDrawMeter.shot(); // P57: drawn viewfinder frames from here
        capturing = new ShotTimeline();
        capturing.put(name, android.os.SystemClock.elapsedRealtimeNanos());
        // P236 (speed-ux F): the phone's thermal state and the viewfinder's fps over the last 2 s at the press
        capturing.notes.put("thermal_press", com.scamera.app.util.ThermalState.now().toString());
        capturing.notes.put("vf_fps_press", fps(com.scamera.app.ui.camera.views.viewfinder.VfDrawMeter.fpsLast2s()));
    }

    private static String fps(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** P236: a value of the shot being captured (the first of a name counts). */
    public static synchronized void captureNote(String name, String value) {
        if (capturing != null && !capturing.notes.containsKey(name)) capturing.notes.put(name, value);
    }

    /** P236: a value of the shot being processed; nothing without one (a pass outside a shot never opens a timeline). */
    public static synchronized void note(String name, String value) {
        if (processing != null && !processing.notes.containsKey(name)) processing.notes.put(name, value);
    }

    /** P371: the shot being processed now (null without one), for a note made later on another thread ({@link #noteOn}). */
    public static synchronized Object processingShot() {
        return processing;
    }

    /** P371: a value of the shot {@code shot} ({@link #processingShot}), not of a later one; nothing once it is printed. */
    public static synchronized void noteOn(Object shot, String name, String value) {
        if (shot instanceof ShotTimeline && shot == processing && !((ShotTimeline) shot).notes.containsKey(name))
            ((ShotTimeline) shot).notes.put(name, value);
    }

    /** P236: a stage of the shot being processed that ended at {@code nanos} (elapsedRealtimeNanos), from a worker's own times. */
    public static synchronized void markAt(String name, long nanos) {
        if (processing == null) processing = new ShotTimeline();
        processing.put(name, nanos);
    }

    /** P236: a stage of the shot being processed only while one is (a GL pass outside a shot is not a shot's). */
    public static synchronized void markIfShot(String name) {
        if (processing != null) processing.put(name, android.os.SystemClock.elapsedRealtimeNanos());
    }

    /** A stage of the shot being captured (nothing without {@link #begin}). */
    public static synchronized void capture(String name) {
        if (capturing != null) capturing.put(name, android.os.SystemClock.elapsedRealtimeNanos());
    }

    /** The capture's timeline, handed to its processing task; the next shutter starts a new one. */
    public static synchronized ShotTimeline detach() {
        final ShotTimeline t = capturing;
        capturing = null;
        return t;
    }

    /** The processing thread takes over a detached timeline (null: a new one without capture marks). */
    public static synchronized void attach(ShotTimeline timeline) {
        processing = timeline != null ? timeline : new ShotTimeline();
    }

    /** A stage of the shot being processed. */
    public static synchronized void mark(String name) {
        if (processing == null) processing = new ShotTimeline();
        processing.put(name, android.os.SystemClock.elapsedRealtimeNanos());
    }

    /** Milliseconds from mark a to mark b of the shot being processed, -1 when either is missing. */
    public static synchronized long between(String a, String b) {
        if (processing == null) return -1;
        final Long ta = processing.marks.get(a), tb = processing.marks.get(b);
        return ta == null || tb == null ? -1 : (tb - ta) / 1_000_000;
    }

    /**
     * Worker report lines relayed by the client: the first line of each worker stage is a mark (its time is the relay time,
     * about 1 ms after the worker printed it).
     */
    public static void workerLine(String line) {
        if (line == null || line.length() < 8) return;
        final char c = line.charAt(0);
        if (c != 'H' && c != 'N' && c != 'S') return; // P236: 'S' too - «SCAM STOCK MOTION» / «SCAM CAPTURE OK» never matched before
        // P440: a mark the worker prints itself (last homography, end of the F6 tail, Mochi start / end, last readback, ...)
        if (line.startsWith(WORKER_MARK)) {
            final Object[] m = workerMark(line);
            if (m == null) return;
            final long at = (Long) m[1];
            if (at < 0) mark((String) m[0]);
            else {
                // a time on another clock (not CLOCK_BOOTTIME) or a garbled one: the relay time, as for the other worker lines
                final long now = android.os.SystemClock.elapsedRealtimeNanos();
                markAt((String) m[0], Math.abs(now - at) <= MARK_SKEW_NS ? at : now);
            }
            return;
        }
        // P236 (speed-ux F): the native mosaic merge's own stage times, back from its total line (the line comes at its end)
        if (line.startsWith("HYBRID MOSAIC NATIVE: total ")) {
            final long[] stages = nativeStages(line);
            if (stages != null) {
                final long end = android.os.SystemClock.elapsedRealtimeNanos(), start = end - stages[0] * 1_000_000L;
                markAt("w_response", start + (stages[2] + stages[3]) * 1_000_000L);
                markAt("w_binning", start + stages[1] * 1_000_000L);
                markAt("w_merge", start + (stages[1] + stages[5]) * 1_000_000L);
                note("w_native_ms", "pick" + stages[2] + "/response" + stages[3] + "/binning" + stages[4] + "/merge" + stages[5]
                        + "/after" + stages[6]);
            }
            return;
        }
        final String name;
        if (line.startsWith("SCAM STOCK MOTION")) name = "w_align";
        else if (line.startsWith("HYBRID MOSAIC: header block")) name = "w_detect"; // P236: after the detection (or its skip)
        else if (line.contains("site-class response")) name = "w_response";
        else if (line.startsWith("HYBRID MOSAIC BINNED") || line.startsWith("HYBRID MOSAIC: binned alignment")) name = "w_binning";
        else if (line.startsWith("HYBRID MOSAIC") && line.contains("chroma median (")) name = "w_chroma"; // its end
        // P440: the plain merge's chroma median line comes at the end of its sink (after the last readback), the first Mochi line
        // when the first long frame is corrected (until the worker prints its own HYBRID MARK lines for them)
        else if (line.startsWith("HYBRID PLAIN: chroma median (")) name = "w_chroma";
        else if (line.startsWith("HYBRID MOCHI: frame ")) name = "w_mochi";
        else if (line.startsWith("HYBRID SHASTA")) name = "w_shasta";
        else if (line.startsWith("HYBRID BENTO")) name = "w_bento";
        else if (line.startsWith("HYBRID LOCAL ALIGN")) name = "w_f6";
        // P371: the GPU alignment's lines are its start (they took w_gpu, the merge's context, since P364's gpuAlign)
        else if (line.startsWith("HYBRID GPU ALIGN")) name = "w_align";
        else if (line.startsWith("HYBRID GPU")) name = "w_gpu";
        else if (line.startsWith("HYBRID RAW CA")) name = "w_rawca";
        else if (line.startsWith("HYBRID STAGES")) name = "w_stages";
        else if (line.equals("SCAM CAPTURE OK")) name = "w_ok";
        else return;
        mark(name);
    }

    /**
     * P440 (owner, 2026-10-27: «нам нужно иметь как минимум не хуже скорость обработки чем у Why камеры»): a stage mark the worker
     * prints itself, {@code HYBRID MARK <name>} (the relay time) or {@code HYBRID MARK <name> <ms>} with the time of the event in
     * CLOCK_BOOTTIME milliseconds (SystemClock.elapsedRealtime's clock; up to 6 decimals), so a mark the worker reports late
     * still lands where the event was. The timeline name is {@code w_<name>} (a name already starting with w_ is kept).
     */
    static final String WORKER_MARK = "HYBRID MARK ";
    /** P440: a worker mark's time further than this from the relay time is not CLOCK_BOOTTIME: the relay time stands. */
    static final long MARK_SKEW_NS = 60_000_000_000L;
    private static final java.util.regex.Pattern MARK_LINE = java.util.regex.Pattern.compile(
            "^HYBRID MARK ([A-Za-z][A-Za-z0-9_]{0,47})(?: ([0-9]{1,15}(?:\\.[0-9]{1,6})?))?\\s*$");

    /** P440: {timeline name, time in ns (CLOCK_BOOTTIME) or -1 for the relay time} of a worker mark line, null when it is none. */
    static Object[] workerMark(String line) {
        if (line == null) return null;
        final java.util.regex.Matcher m = MARK_LINE.matcher(line);
        if (!m.matches()) return null;
        final String name = m.group(1).startsWith("w_") ? m.group(1) : "w_" + m.group(1);
        long ns = -1;
        if (m.group(2) != null) {
            try {
                ns = Math.round(Double.parseDouble(m.group(2)) * 1_000_000d);
            } catch (NumberFormatException e) {
                ns = -1;
            }
        }
        return new Object[]{name, ns};
    }

    private static final java.util.regex.Pattern NATIVE_TOTAL = java.util.regex.Pattern.compile(
            "HYBRID MOSAIC NATIVE: total (\\d+) ms \\(before the merge (\\d+): pick (\\d+) response (\\d+) binning (\\d+), merge (\\d+), after it (\\d+)\\)");

    /** P236: {total, before the merge, pick, response, binning, merge, after it} ms of the native merge's total line, or null. */
    static long[] nativeStages(String line) {
        final java.util.regex.Matcher m = NATIVE_TOTAL.matcher(line);
        if (!m.find()) return null;
        final long[] v = new long[7];
        for (int i = 0; i < 7; i++) v[i] = Long.parseLong(m.group(i + 1));
        return v;
    }

    /**
     * Prints the timeline of the shot being processed as one line and closes it; P236: with the thermal state and the
     * viewfinder's fps at the end, and one «SHOT SUMMARY» line (also into SCAMERA-timings.txt, whatever the profiling switch).
     */
    public static void print() {
        final String line, summary;
        synchronized (ShotTimeline.class) {
            final ShotTimeline t = processing;
            processing = null;
            if (t == null || t.marks.isEmpty()) return;
            t.viewfinderSpans();
            t.notes.put("thermal_end", com.scamera.app.util.ThermalState.now().toString());
            t.notes.put("vf_fps_end", fps(com.scamera.app.ui.camera.views.viewfinder.VfDrawMeter.fpsLast2s()));
            line = t.describe();
            summary = t.summary();
        }
        Log.i(TAG, line);
        Log.i(TAG, summary);
        com.scamera.app.util.ScameraTimingLog.summary(summary);
    }

    /**
     * P611 (owner, 2026-11-10: «Исправь так же тормоза видоискателя во время обработки фото»): what the viewfinder drew while the
     * worker merged (vf_merge: the job to the worker's end) and while the post pipeline ran (vf_post), as "fps/longest gap"
     * (VfDrawMeter.span, e.g. vf_merge=29.8fps/45ms); the press and end values alone (vf_fps_press / vf_fps_end) were the 2 s
     * before them, outside the processing. A stage without its marks has no note.
     */
    void viewfinderSpans() {
        // P616: both stages first, their gaps' notes (vf_gap_merge / vf_gap_post: the longest gap's cause, the paused or restarted
        // time left out) ahead of vf_merge / vf_post, which keep their "fps/gap" form next to each other
        final String[] merge = viewfinderStage("vf_merge", first("job", "spawn"), first("w_ok", "client_done"));
        final String[] post = viewfinderStage("vf_post", "post_start", "post_done");
        if (merge != null && merge[1] != null) notes.put("vf_gap_merge", merge[1]);
        if (post != null && post[1] != null) notes.put("vf_gap_post", post[1]);
        if (merge != null) notes.put("vf_merge", merge[0]);
        if (post != null) notes.put("vf_post", post[0]);
    }

    private String[] viewfinderStage(String note, String a, String b) {
        final Long ta = a == null ? null : marks.get(a), tb = b == null ? null : marks.get(b);
        if (ta == null || tb == null || notes.containsKey(note)) return null;
        return com.scamera.app.ui.camera.views.viewfinder.VfDrawMeter.stage(ta / 1_000_000, tb / 1_000_000);
    }

    /**
     * "SHOT TIMELINE ms: name=ms ..." in time order (P236: the marks from a worker's own times arrive late), relative to the
     * first one, with the total.
     */
    String describe() {
        final StringBuilder sb = new StringBuilder("SHOT TIMELINE ms:");
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (long v : marks.values()) { first = Math.min(first, v); last = Math.max(last, v); }
        final java.util.List<Map.Entry<String, Long>> sorted = new java.util.ArrayList<>(marks.entrySet());
        sorted.sort(Map.Entry.comparingByValue()); // stable: equal times keep their order
        for (Map.Entry<String, Long> e : sorted)
            sb.append(' ').append(e.getKey()).append('=').append((e.getValue() - first) / 1_000_000);
        sb.append(" total=").append((last - first) / 1_000_000);
        return sb.toString();
    }

    /** Milliseconds from mark a to mark b of this timeline, -1 when either is missing. */
    private long span(String a, String b) {
        final Long ta = a == null ? null : marks.get(a), tb = b == null ? null : marks.get(b);
        return ta == null || tb == null ? -1 : (tb - ta) / 1_000_000;
    }

    /**
     * P625 (owner, 2026-11-11: «ускорь подготовку снимка»): the parts of HYBRID JAVA PREP's meta= (the processing before the merge
     * call less the sharpness), from ScamProcessor's marks: crop (RawCrop), const (RawBin, FillConstParameters), roles (the frames'
     * metadata and roles), levels (the levels and the darkest frame's black), dyn (the reference frame's FillDynamicParameters), dcg
     * (the second black pass and a DCG frame's linearity), neutral (the mosaic white balance), rest (field of view, EXIF, the logs).
     * "" without a shot being processed; -1: a mark missing.
     */
    public static synchronized String prepParts() {
        if (processing == null) return "";
        final String[][] parts = {{"crop", "proc", "crop_done"}, {"const", "crop_done", "const_done"}, {"roles", "const_done", "sharp_start"},
                {"levels", "sharp_done", "levels_done"}, {"dyn", "levels_done", "dyn_done"}, {"dcg", "dyn_done", "dcg_done"},
                {"neutral", "dcg_done", "neutral_done"}, {"rest", "neutral_done", "hybrid_call"}};
        final StringBuilder sb = new StringBuilder(" (");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i][0]).append('=').append(between(parts[i][1], parts[i][2]));
        }
        return sb.append(')').toString();
    }

    /** The first of {@code names} this timeline has, or null. */
    private String first(String... names) {
        for (String n : names) if (marks.containsKey(n)) return n;
        return null;
    }

    /**
     * P236 (speed-ux F): one line per shot - hold (the press to the last still: the time to keep still), to_worker (the last
     * still to the job sent), merge (the job to the worker's end), gap (the worker's end to the post pipeline: P236's 0.4-0.6 s),
     * post, save, total, then the notes (thermal state and viewfinder fps at the press and the end, shader compiling, the
     * worker's stages). -1: a mark missing (a route without that stage).
     */
    String summary() {
        final StringBuilder sb = new StringBuilder("SHOT SUMMARY ms:");
        final String captured = first("series_done", "complete", "queued");
        final String sent = first("job", "spawn");
        final String merged = first("w_ok", "client_done");
        sb.append(" hold=").append(span("shutter", captured));
        sb.append(" to_worker=").append(span(captured, sent));
        sb.append(" merge=").append(span(sent, merged));
        sb.append(" gap=").append(span(merged, "post_start"));
        sb.append(" post=").append(span("post_start", "post_done"));
        sb.append(" save=").append(span("post_done", "saved"));
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (long v : marks.values()) { first = Math.min(first, v); last = Math.max(last, v); }
        sb.append(" total=").append((last - first) / 1_000_000);
        for (Map.Entry<String, String> e : notes.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        return sb.toString();
    }
}
