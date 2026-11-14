package com.scamera.app.capture;

import android.app.ActivityManager;
import android.content.Context;
import android.media.Image;

import androidx.annotation.Nullable;

import com.scamera.app.processing.RingFrame;
import com.scamera.app.util.Allocator;
import com.scamera.app.util.Log;
import com.scamera.app.util.ShotArena;

import java.nio.ByteBuffer;

/**
 * P91 The ZSL ring in the app's own memory (owner, 2026-10-11: «гибрид буфера из нашей реализации, Motion Camera и WhyCam»).
 * <ul>
 *   <li>WhyCam 1.05 / MotionCam: every RAW is copied out of the camera's reader when it arrives and its Image goes back at
 *       once, so the reader holds a handful of buffers instead of the whole ring (ours held ring + 3 = 53 HAL buffers, ~1.2 GB of
 *       camera memory), and the ring keeps filling while a shot is in flight;</li>
 *   <li>MotionCam: the depth is bounded by a memory budget;</li>
 *   <li>ours: one memfd the merge worker maps (ShotArena): a ring frame of a plain RAW16 stream goes to the worker as it is (a
 *       pinned view of its slot, no copy after the press), other frames are converted into free slots of the same memfd, and the
 *       shot's post-shutter frames are written there too, so the whole burst reaches the worker without the transport copy;
 *       the frame selection (exposure tiers, stale gaps, steadiest frames) stays as it was.</li>
 * </ul>
 * A slot is written again only when no view of it is left (Allocator.arenaSlotRefs): a shot's frames stay pinned until the
 * merge frees them. The first {@link ShotArena#HEADER} bytes are the transport header (shots are merged one after another).
 */
public final class ZslRing {
    private static final String TAG = "SCAM_CAPTURE";
    /** Slots beyond the ring for one shot's own frames (the post-shutter series, converted ring frames) while the ring runs on. */
    static final int SPARE = 16;
    /**
     * P497 (Nubia Z17, 22 MP: 17 slots fit, «ring 1 + shot 16» - one N frame merged): with less memory than ring + SPARE the spare
     * shrinks to this (three ES, three L and room) and the ring keeps the rest.
     */
    static final int MIN_SPARE = 8;

    private final int id;
    private final long slotBytes;
    private final int slots;
    /** Slots beyond the ring part ({@link #SPARE}; a native FULLRES ring its own, {@link #fullResSlots}). */
    private final int spare;
    /** P198: the ring of a native 50 MP FULLRES session ({@link #createFullRes}). */
    private final boolean fullRes;
    private final boolean[] ringOwned, reserved;
    private volatile boolean closed;
    /**
     * P662: what the ring was made for - the ring frames asked (the ring setting), the 50 MP ring's N, and whether the memory
     * budget gave it fewer slots than asked ({@link #reusableFor}).
     */
    private int askedRing, askedWanted;
    private boolean trimmed;

    private ZslRing(int id, long slotBytes, int slots, int spare, boolean fullRes) {
        this.id = id;
        this.slotBytes = slotBytes;
        this.slots = slots;
        this.spare = spare;
        this.fullRes = fullRes;
        this.ringOwned = new boolean[slots];
        this.reserved = new boolean[slots];
    }

    // ---- P662: the memfd's release and a fresh ring's first touch off the camera's threads ---------------------------------

    /**
     * P662 (OPPO X7U log: closeCamera 213 / 118 / 67 ms on the main thread at 39 / 20 / 13 filled 24 MB slots, onPause 140-228 ms):
     * the release of a closed ring (munmap + close of up to 1.6 GB, ~5.5 ms a filled slot) and the prefault of a fresh one run on
     * this thread, in their order; {@link #create} waits for the releases queued before it (its budget reads the memory
     * available).
     */
    private static final java.util.concurrent.ExecutorService MEMORY = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "SCAMERA-zsl-ring-memory");
        t.setDaemon(true);
        return t;
    });
    private static final Object RELEASES = new Object();
    /** P662: ring releases queued and not done yet. */
    private static int pendingReleases;
    /** P662: at most this long a new ring waits for the previous one's release. */
    static final long RELEASE_WAIT_MS = 2000;

    /** P662: waits (at most {@code timeoutMs}) for the ring releases queued so far; returns the ms waited. */
    public static long awaitReleases(long timeoutMs) {
        final long start = System.nanoTime();
        synchronized (RELEASES) {
            while (pendingReleases > 0) {
                final long left = timeoutMs - (System.nanoTime() - start) / 1_000_000;
                if (left <= 0) break;
                try {
                    RELEASES.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** P662: ring releases still queued (tests, the FULLRES merge's wait). */
    public static int pendingReleases() {
        synchronized (RELEASES) {
            return pendingReleases;
        }
    }

    /** P662: {@code release} on the memory thread, counted until it is done; inline when the thread refuses it. */
    static void releaseLater(Runnable release) {
        synchronized (RELEASES) {
            pendingReleases++;
        }
        final Runnable counted = () -> {
            try {
                release.run();
            } finally {
                synchronized (RELEASES) {
                    pendingReleases--;
                    RELEASES.notifyAll();
                }
            }
        };
        try {
            MEMORY.execute(counted);
        } catch (RuntimeException rejected) {
            counted.run();
        }
    }

    /**
     * P662: the slots a fresh ring prefaults - its ring part (the lowest slots, which freeSlotLocked hands out first), never the
     * shot's spare slots (written only by a shot) and nothing of a 50 MP FULLRES ring (sized for the memory a merge needs).
     */
    static int prefaultSlots(int slots, int spare, boolean fullRes) {
        return fullRes ? 0 : Math.max(0, Math.min(slots, slots - spare));
    }

    /** P662: scam_dev zsl_ring_prefault (default on) - a fresh ring's part is allocated and mapped on the memory thread. */
    static boolean prefaultOn() {
        try {
            return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("zsl_ring_prefault", true);
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** P662: the ring part prefaulted slot by slot from slot 0 (the camera fills it in that order), stopped once closed. */
    private void prefaultLater() {
        final int count = prefaultSlots(slots, spare, fullRes);
        if (count <= 0 || !prefaultOn()) return;
        try {
            MEMORY.execute(() -> {
                final long start = System.nanoTime();
                int done = 0;
                for (int i = 0; i < count && !closed; i++) {
                    if (!Allocator.arenaPrefault(id, offset(i), slotBytes)) break;
                    done++;
                }
                Log.i(TAG, "PERF ZSL ring prefault: " + done + "/" + count + " slots x " + (slotBytes >> 20) + " MB in "
                        + (System.nanoTime() - start) / 1_000_000 + " ms (off the camera thread" + (closed ? ", closed meanwhile)" : ")"));
            });
        } catch (RuntimeException ignored) {
            // the copies take the faults, as before
        }
    }

    /**
     * P662: whether a ring kept over a camera restart serves the next session as it is: the same slot size (a 12 MP lens of
     * 23 MB frames does not keep the 24 MB ring of another), the same kind (12 MP / 50 MP FULLRES), the same frames asked, and the
     * budget gave it every slot it asked (a trimmed ring is made again with the memory available now).
     */
    static boolean reusable(long slotBytes, boolean fullRes, int askedRing, int askedWanted, boolean trimmed,
                            long frameBytes, boolean wantFullRes, int ringFrames, int wanted) {
        return !trimmed && frameBytes > 0 && fullRes == wantFullRes && slotBytes == ((frameBytes + 4095) & ~4095L)
                && askedRing == ringFrames && (!fullRes || askedWanted == wanted);
    }

    /** P662: {@link #reusable} for this ring. */
    public boolean reusableFor(long frameBytes, boolean wantFullRes, int ringFrames, int wanted) {
        return !closed && reusable(slotBytes, fullRes, askedRing, askedWanted, trimmed, frameBytes, wantFullRes, ringFrames, wanted);
    }

    /** P662: why {@link #reusableFor} says no (for the log). */
    public String notReusableWhy(long frameBytes, boolean wantFullRes, int ringFrames, int wanted) {
        if (closed) return "closed";
        if (trimmed) return "trimmed by the memory budget";
        if (fullRes != wantFullRes) return fullRes ? "a 50 MP ring" : "a 12 MP ring";
        if (slotBytes != ((frameBytes + 4095) & ~4095L)) return "slot " + (slotBytes >> 20) + " MB, frame " + (frameBytes >> 20) + " MB";
        return "asked " + askedRing + "/" + askedWanted + ", now " + ringFrames + "/" + wanted;
    }

    /** P662: filled slots now (ring frames and shot frames still holding a view), for the release's log. */
    private int filledSlots() {
        int n = 0;
        for (int i = 0; i < slots; i++) if (ringOwned[i] || reserved[i] || Allocator.arenaSlotRefs(id, offset(i)) > 0) n++;
        return n;
    }

    /**
     * A ring for {@code ringFrames} frames of up to {@code frameBytes} (the larger of the camera's plane and its 16-bit unpacked
     * size) plus {@link #SPARE}, within the memory budget; null without a memfd (the caller keeps the camera's Images).
     */
    @Nullable
    public static ZslRing create(@Nullable Context context, int ringFrames, long frameBytes) {
        if (frameBytes <= 0 || ringFrames <= 0) return null;
        final long slot = (frameBytes + 4095) & ~4095L;
        final int wanted = ringFrames + SPARE;
        final long waited = awaitReleases(RELEASE_WAIT_MS); // P662: the previous ring's memory back before the budget is read
        final int fit = budgetSlots(context, slot);
        final int count = Math.max(Math.min(8, ringFrames) + 4, Math.min(wanted, fit));
        final int spare = spareSlots(count, ringFrames);
        final int id = memfd(slot, count);
        if (id <= 0) return null;
        Log.i(TAG, "ZSL ring in memfd: " + count + " slots x " + (slot >> 20) + " MB (ring " + Math.max(1, count - spare)
                + " + shot " + spare + ", asked " + ringFrames + ", budget " + fit + ")"
                + (waited > 0 ? " after " + waited + " ms for the previous ring's release" : ""));
        final ZslRing ring = new ZslRing(id, slot, count, spare, false);
        ring.askedRing = ringFrames;
        ring.trimmed = count < wanted;
        ring.prefaultLater();
        return ring;
    }

    /** P497: the shot's slots of {@code count}: SPARE, less when the ring would get fewer than it asked, at least MIN_SPARE. */
    static int spareSlots(int count, int ringFrames) {
        return Math.min(count - 1, Math.min(SPARE, Math.max(MIN_SPARE, count - ringFrames)));
    }

    private static int memfd(long slot, int count) {
        try {
            return Allocator.arenaCreate(ShotArena.HEADER + slot * count);
        } catch (Throwable t) {
            Log.w(TAG, "ZSL ring: no memfd (" + t + "), the camera's buffers are kept");
            return 0;
        }
    }

    /**
     * P198 (owner, 2026-10-17: «сделай так чтобы 50мп снимались в zsl и только 200мп снимались в psl»): the ring of a native 50 MP
     * FULLRES session. Its frames are 4x the 12 MP ones (8192 x 6144 x 2 B = 96 MB a slot), so it is sized by {@link #fullResSlots}
     * instead of the 12 MP rule (40 % of the memory available, at least 12 slots, 16 of them spare: 2.4 GB with 6 GB available, for a
     * ring of 9 frames); null without a memfd: such a session keeps
     * no ring (its reader holds 4 images, a held one would stall the viewfinder) and its shots take the series after the shutter.
     */
    @Nullable
    public static ZslRing createFullRes(@Nullable Context context, int ringSetting, int wanted, long frameBytes) {
        if (frameBytes <= 0) return null;
        final long slot = (frameBytes + 4095) & ~4095L;
        awaitReleases(RELEASE_WAIT_MS); // P662: the previous ring's memory back before the budget is read
        final long avail = availableBytes(context);
        final int[] split = fullResSlots(avail, slot, ringSetting, wanted);
        final int count = split[0] + split[1];
        final int id = memfd(slot, count);
        if (id <= 0) return null;
        Log.i(TAG, "50 MP ZSL ring in memfd: " + count + " slots x " + (slot >> 20) + " MB (ring " + split[0] + " + shot " + split[1]
                + ", asked min(ring setting " + ringSetting + ", N " + wanted + " + " + FULLRES_RING_SLACK + "), cap "
                + fullResCap(avail, slot) + " slots = min(" + FULLRES_SLOTS_MAX + ", a quarter of " + (avail >> 20) + " MB available))");
        final ZslRing ring = new ZslRing(id, slot, count, split[1], true);
        ring.askedRing = ringSetting;
        ring.askedWanted = wanted;
        ring.trimmed = split[0] < Math.min(Math.max(1, ringSetting), Math.max(1, wanted) + FULLRES_RING_SLACK) || split[1] < FULLRES_SHOT_SLOTS;
        return ring;
    }

    /** P198: at most this many 50 MP slots (16 x 96 MB = 1.5 GB, the bytes of the 12 MP ring's 66 x 24 MB). */
    public static final int FULLRES_SLOTS_MAX = 16;
    /** P198: at least this many (a ring of 2 frames and 2 slots for the shot) whatever the memory says. */
    public static final int FULLRES_SLOTS_MIN = 4;
    /** P198: slots for a 50 MP shot's own frames: the 12 MP Hybrid plan's 3 ultrashort and 3 bracketed frames. */
    public static final int FULLRES_SHOT_SLOTS = 6;
    /** P198: ring frames beyond the shot's N (the selection drops frames of another exposure or behind a gap). */
    public static final int FULLRES_RING_SLACK = 2;

    /**
     * P198: {ring frames, shot slots} of a 50 MP ring. All of it within a quarter of the memory available (the camera's own four
     * 96 MB buffers, the HAL's and the 50 MP merge, P191's 80 % of MemAvailable, need the rest; the 12 MP rule takes 40 %) and
     * {@link #FULLRES_SLOTS_MAX}; the shot's slots up to half of that; the ring the shot's N plus {@link #FULLRES_RING_SLACK}, within
     * the ring setting and what is left. A slot the ring never reaches is never written (no memory).
     */
    public static int[] fullResSlots(long availBytes, long slotBytes, int ringSetting, int wanted) {
        final int total = fullResCap(availBytes, slotBytes);
        final int shot = Math.min(FULLRES_SHOT_SLOTS, total / 2);
        final int ring = Math.max(1, Math.min(Math.min(Math.max(1, ringSetting), Math.max(1, wanted) + FULLRES_RING_SLACK), total - shot));
        return new int[]{ring, shot};
    }

    /** P198: the slots of a 50 MP ring the memory allows (unknown memory: the maximum). */
    static int fullResCap(long availBytes, long slotBytes) {
        if (availBytes <= 0 || slotBytes <= 0) return FULLRES_SLOTS_MAX;
        return (int) Math.max(FULLRES_SLOTS_MIN, Math.min(FULLRES_SLOTS_MAX, availBytes / 4 / slotBytes));
    }

    /** MotionCam-style budget: at most 40 % of the memory available now (at least room for 12 slots). */
    static int budgetSlots(@Nullable Context context, long slotBytes) {
        return budgetSlots(availableBytes(context), slotBytes);
    }

    private static long availableBytes(@Nullable Context context) {
        long avail = 0;
        try {
            ActivityManager am = context == null ? null : (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                avail = info.availMem;
            }
        } catch (RuntimeException ignored) {
        }
        return avail;
    }

    static int budgetSlots(long availBytes, long slotBytes) {
        if (availBytes <= 0 || slotBytes <= 0) return 64;
        return (int) Math.max(12, Math.min(128, availBytes * 2 / 5 / slotBytes));
    }

    /** P662: the Allocator arena id of the ring's memfd (the merge worker kept ready for the session holds it). */
    public int arenaId() {
        return id;
    }

    /** How many frames the ring part holds. */
    public int ringSlots() {
        return Math.max(1, slots - spare);
    }

    /** P198: a native 50 MP FULLRES session's ring. */
    public boolean fullRes() {
        return fullRes;
    }

    public long slotBytes() {
        return slotBytes;
    }

    private long offset(int slot) {
        return ShotArena.HEADER + slot * slotBytes;
    }

    /** A free slot (not the ring's, not reserved for a shot, no view left), or -1. */
    private int freeSlotLocked() {
        for (int i = 0; i < slots; i++)
            if (!ringOwned[i] && !reserved[i] && Allocator.arenaSlotRefs(id, offset(i)) == 0) return i;
        return -1;
    }

    /** How many ring frames are held now. */
    public synchronized int held() {
        int n = 0;
        for (boolean b : ringOwned) if (b) n++;
        return n;
    }

    /**
     * Copies the camera's frame into a free slot and gives the Image back; null (Image untouched) when no slot is free (the
     * caller drops its oldest ring frame and retries) or the frame does not fit.
     */
    @Nullable
    public RingFrame store(Image image) {
        return store(image, false);
    }

    /**
     * {@link #store(Image)}; {@code unpack}: P365 (owner, 2026-10-27: «В Why камере тоже есть живой видоискатель, сделай его
     * такимже по требованиям как там»; scam_dev zsl_ring_unpack) a packed RAW10 / RAW12 frame is unpacked into its slot as it
     * arrives (Allocator.arenaCopyUnpack, the decode of ImageFrame.materialize's after the press) and reported with RAW_SENSOR
     * geometry (rowStride w * 2), so the worker gets a zero-copy view of it (ImageFrame.viewable) and the viewfinder can draw
     * the slot itself; {@link #sourceFormat} keeps the stream's format for the merge's one-layout rule.
     */
    @Nullable
    public RingFrame store(Image image, boolean unpack) {
        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer src = plane.getBuffer();
        final int bytes = src.capacity();
        final int format = image.getFormat(), width = image.getWidth(), height = image.getHeight();
        final boolean unpacking = unpack && unpacks(format, width);
        final long need = unpacking ? (long) width * height * 2 : bytes;
        if (bytes <= 0 || need > slotBytes) return null;
        final int slot;
        synchronized (this) {
            if (closed) return null;
            slot = freeSlotLocked();
            if (slot < 0) return null;
            ringOwned[slot] = true;
        }
        final long copyStart = System.nanoTime();
        final ByteBuffer view = unpacking
                ? Allocator.arenaCopyUnpack(id, offset(slot), src, 0, format, width, plane.getRowStride(), height)
                : Allocator.arenaCopy(id, offset(slot), src, 0, bytes);
        if (view != null) STORE_PERF.copied(System.nanoTime() - copyStart, need, unpacking);
        if (view == null) {
            synchronized (this) { ringOwned[slot] = false; }
            return null;
        }
        final Frame f = unpacking
                ? new Frame(slot, view, image.getTimestamp(), android.graphics.ImageFormat.RAW_SENSOR, width, height, width * 2, 2, format)
                : new Frame(slot, view, image.getTimestamp(), format, width, height, plane.getRowStride(), plane.getPixelStride(), format);
        image.close();
        return f;
    }

    /** P681: the ring copies' times (the camera thread's), one PERF line per {@link StorePerf#EVERY_MS} at most. */
    private static final StorePerf STORE_PERF = new StorePerf();

    /** P681: the ZSL store copy time per frame, summed and logged rate-limited (a line per frame would flood the log at 30 fps). */
    static final class StorePerf {
        static final long EVERY_MS = 10_000;
        private long frames, totalNs, maxNs, bytes, lastLogMs;
        private boolean unpacked;

        synchronized void copied(long ns, long frameBytes, boolean unpack) {
            frames++;
            totalNs += ns;
            maxNs = Math.max(maxNs, ns);
            bytes = frameBytes;
            unpacked |= unpack;
            final long now = android.os.SystemClock.elapsedRealtime();
            if (lastLogMs == 0) lastLogMs = now;
            if (now - lastLogMs < EVERY_MS) return;
            Log.i(TAG, line());
            lastLogMs = now;
            frames = totalNs = maxNs = 0;
            unpacked = false;
        }

        /** The PERF line of the frames since the last one. */
        String line() {
            return String.format(java.util.Locale.ROOT, "PERF ZSL store: %d frames x %d MB%s, copy avg %.2f ms, max %.2f ms",
                    frames, bytes >> 20, unpacked ? " (unpacked)" : "", frames == 0 ? 0.0 : totalNs / 1e6 / frames, maxNs / 1e6);
        }
    }

    /** P365: a stream format the ring unpacks into its slot (MIPI RAW10 / RAW12 of whole 4-pixel groups). */
    static boolean unpacks(int format, int width) {
        return (format == android.graphics.ImageFormat.RAW10 || format == android.graphics.ImageFormat.RAW12) && width >= 4 && width % 4 == 0;
    }

    /** P365: the frame was unpacked into its slot on arrival (RAW_SENSOR geometry, a packed stream). */
    public static boolean unpacked(RingFrame frame) {
        return frame instanceof Frame && ((Frame) frame).sourceFormat != frame.getFormat();
    }

    /**
     * P365: the format the frame was read in from the camera ({@code fallback} for any other frame): ImageFrame.sourceFormat of a
     * frame unpacked on arrival is its stream's RAW10 / RAW12, as the post-shutter frames of the same stream (ScamHybridBurst
     * keepOneLayout merges one layout).
     */
    public static int sourceFormat(RingFrame frame, int fallback) {
        return unpacked(frame) ? ((Frame) frame).sourceFormat : fallback;
    }

    /**
     * Free slots of this memfd for one shot's own frames (ShotArena), up to {@code count}; null when none is free. The slots stay
     * reserved until the shot releases its arena, then their views pin them.
     */
    @Nullable
    public synchronized ShotArena reserve(int count, long frameBytes) {
        if (closed || frameBytes > slotBytes || count <= 0) return null;
        long[] offsets = new long[count];
        int got = 0;
        for (int i = 0; i < slots && got < count; i++) {
            if (!ringOwned[i] && !reserved[i] && Allocator.arenaSlotRefs(id, offset(i)) == 0) {
                reserved[i] = true;
                offsets[got++] = offset(i);
            }
        }
        if (got == 0) return null;
        if (got < count) offsets = java.util.Arrays.copyOf(offsets, got);
        final long[] mine = offsets;
        return ShotArena.inRing(id, slotBytes, frameBytes, mine, () -> unreserve(mine));
    }

    private synchronized void unreserve(long[] offsets) {
        for (long o : offsets) {
            final int slot = (int) ((o - ShotArena.HEADER) / slotBytes);
            if (slot >= 0 && slot < slots) reserved[slot] = false;
        }
    }

    /**
     * The ring is no longer used (session closed): its memfd goes once the last view of it is freed. P662: the release (munmap +
     * close of every slot no shot holds) runs on the memory thread; the next {@link #create} waits for it.
     */
    public void close() {
        final int filled;
        synchronized (this) {
            if (closed) return;
            closed = true;
            filled = filledSlots();
        }
        final int ringId = id, total = slots;
        final long mb = slotBytes >> 20;
        releaseLater(() -> {
            final long start = System.nanoTime();
            Allocator.arenaRelease(ringId);
            Log.i(TAG, "PERF ZSL ring released in " + (System.nanoTime() - start) / 1_000_000 + " ms off the caller's thread ("
                    + filled + "/" + total + " slots x " + mb + " MB filled)");
        });
    }

    /** P662: {@link #close} on the caller's thread (the FULLRES merge reads the memory right after it). */
    public void closeNow() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        Allocator.arenaRelease(id);
    }

    /** A ring frame in a slot; close() drops the ring's view (a shot's own views keep the slot pinned). */
    private final class Frame implements RingFrame {
        private final int slot;
        private ByteBuffer view;
        private final long timestamp;
        private final int format, width, height, rowStride, pixelStride;
        /** P365: the camera's format of the frame (RAW10 / RAW12 of a frame unpacked on arrival; else {@code format}). */
        private final int sourceFormat;

        Frame(int slot, ByteBuffer view, long timestamp, int format, int width, int height, int rowStride, int pixelStride, int sourceFormat) {
            this.slot = slot;
            this.view = view;
            this.timestamp = timestamp;
            this.width = width;
            this.height = height;
            this.rowStride = rowStride;
            this.format = format;
            this.pixelStride = pixelStride;
            this.sourceFormat = sourceFormat;
        }

        @Override public long getTimestamp() { return timestamp; }
        @Override public int getFormat() { return format; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public int rowStride() { return rowStride; }
        @Override public int pixelStride() { return pixelStride; }

        @Override public synchronized ByteBuffer buffer() {
            if (view == null) throw new IllegalStateException("ring frame closed");
            ByteBuffer b = view.duplicate();
            b.order(view.order());
            b.clear();
            return b;
        }

        @Override public long[] arenaSlot() { return new long[]{id, offset(slot)}; }

        @Override public void close() {
            final ByteBuffer v;
            synchronized (this) {
                v = view;
                view = null;
            }
            if (v == null) return;
            Allocator.free(v);
            synchronized (ZslRing.this) { ringOwned[slot] = false; }
        }
    }
}
