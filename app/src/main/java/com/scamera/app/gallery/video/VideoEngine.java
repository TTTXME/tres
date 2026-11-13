package com.scamera.app.gallery.video;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * P499 (owner, 2026-11-04: «Сделай свой декодер чтобы у нас был свой видеоплеер»): the gallery's own playback engine instead of the
 * platform's MediaPlayer. Two worker threads, each with its own {@link MediaExtractor} and {@link MediaCodec}: the video one decodes
 * into the player's surface (hardware, hardware with a software fallback, or software - {@link Decoder}), the audio one decodes PCM
 * into an {@link AudioTrack} of its own session (so the player can boost it). The audio track is the clock while it plays; without
 * one (no sound, a speed the time stretch refuses) a system clock runs from the last position. Seeks are exact (the frames before the
 * target are decoded but not shown) or fast (the key frame, while scrubbing). The surface can go away (background play) and come back:
 * the video decoder is closed and reopened at the clock. All public calls are cheap and may come from the main thread; callbacks
 * arrive on the main thread.
 */
public final class VideoEngine {
    private static final String TAG = "VideoEngine";
    /** A frame later than this (real time) is dropped instead of shown. */
    private static final long LATE_US = 60_000;

    public enum Decoder { HW, HW_PLUS, SW }

    public interface Listener {
        void onPrepared();

        void onCompleted();

        void onError(String message);

        /** The video decoder in use; {@code fellBack}: the hardware one failed and the software one took over. */
        default void onDecoder(String name, boolean fellBack) {}
    }

    /** A track of the file. */
    public static final class Track {
        public final int index;
        public final MediaFormat format;
        public final String mime;
        @Nullable public final String language;

        Track(int index, MediaFormat format) {
            this.index = index;
            this.format = format;
            this.mime = string(format, MediaFormat.KEY_MIME, "");
            String lang = string(format, MediaFormat.KEY_LANGUAGE, null);
            this.language = lang == null || lang.isEmpty() || "und".equals(lang) ? null : lang;
        }

        public int integer(String key, int fallback) {
            try {
                return format.containsKey(key) ? format.getInteger(key) : fallback;
            } catch (RuntimeException e) {
                return fallback;
            }
        }
    }

    static String string(MediaFormat f, String key, @Nullable String fallback) {
        try {
            String v = f.containsKey(key) ? f.getString(key) : null;
            return v != null ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private final Context app;
    public final Uri uri;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    public final int sessionId;

    // ---- the file (set once by prepare)
    @Nullable private Track video;
    private final List<Track> audios = new ArrayList<>(), texts = new ArrayList<>();
    private long durationUs;
    private int width, height, rotation;

    // ---- the state (under lock)
    private boolean released, started, playing, completed;
    private float speed = 1f, volume = 1f;
    private int seekGen;
    private long seekUs;
    private boolean seekExact = true;
    /** The first frame after the last seek is on the screen (or there is nothing to show): the clock may run. */
    private boolean videoReady;
    private int videoGen, videoAck = -1;
    @Nullable private Surface surface;
    private Decoder decoder = Decoder.HW_PLUS;
    private int audioGen;
    private int audioIndex = -1;
    private boolean audioSuspended, audioBroken;
    private boolean videoDone, audioDone;
    private long clockBaseUs, clockAnchorNs, lastClockUs;
    private boolean clockRunning;
    private String decoderName = "";
    private int dropped;

    @Nullable private VideoWorker videoWorker;
    @Nullable private AudioWorker audioWorker;

    public VideoEngine(Context context, Uri uri, Listener listener) {
        this.app = context.getApplicationContext();
        this.uri = uri;
        this.listener = listener;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.sessionId = am != null ? am.generateAudioSessionId() : AudioManager.AUDIO_SESSION_ID_GENERATE;
    }

    private MediaExtractor extractor() throws java.io.IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(app, uri, null);
        return ex;
    }

    /** Reads the tracks off the main thread; {@link Listener#onPrepared()} or {@link Listener#onError(String)} follows. */
    public void prepare() {
        new Thread(() -> {
            MediaExtractor ex = null;
            try {
                ex = extractor();
                long dur = 0;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    Track t = new Track(i, f);
                    if (f.containsKey(MediaFormat.KEY_DURATION)) dur = Math.max(dur, f.getLong(MediaFormat.KEY_DURATION));
                    if (t.mime.startsWith("video/")) {
                        if (video == null) video = t;
                    } else if (t.mime.startsWith("audio/")) audios.add(t);
                    else if (Subtitles.isTextMime(t.mime)) texts.add(t);
                }
                durationUs = dur;
                if (video != null) {
                    width = video.integer(MediaFormat.KEY_WIDTH, 0);
                    height = video.integer(MediaFormat.KEY_HEIGHT, 0);
                    rotation = video.integer(MediaFormat.KEY_ROTATION, 0);
                    if (rotation % 180 != 0) {
                        int w = width;
                        width = height;
                        height = w;
                    }
                }
                if (video == null && audios.isEmpty()) throw new java.io.IOException("no video or audio track");
                synchronized (lock) {
                    audioIndex = audios.isEmpty() ? -1 : 0;
                }
                main.post(() -> {
                    if (!isReleased()) listener.onPrepared();
                });
            } catch (Exception | LinkageError e) {
                Log.w(TAG, "prepare " + uri + ": " + e);
                main.post(() -> {
                    if (!isReleased()) listener.onError(String.valueOf(e.getMessage()));
                });
            } finally {
                if (ex != null) ex.release();
            }
        }, "video-prepare").start();
    }

    /** Starts the workers at {@code atUs}, playing or paused. Call once, after {@link Listener#onPrepared()}. */
    public void start(long atUs, boolean play) {
        synchronized (lock) {
            if (started || released) return;
            started = true;
            playing = play;
            seekLocked(atUs, true);
        }
        if (video != null) {
            videoWorker = new VideoWorker();
            videoWorker.start();
        }
        if (!audios.isEmpty()) {
            audioWorker = new AudioWorker();
            audioWorker.start();
        }
    }

    // ------------------------------------------------------------------ getters

    public long durationMs() {
        return durationUs / 1000;
    }

    /** The picture's size as shown (the rotation applied); 0 without video. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    @Nullable
    public Track videoTrack() {
        return video;
    }

    public List<Track> audioTracks() {
        return Collections.unmodifiableList(audios);
    }

    public List<Track> textTracks() {
        return Collections.unmodifiableList(texts);
    }

    public int audioIndex() {
        synchronized (lock) {
            return audioIndex;
        }
    }

    public String decoderName() {
        synchronized (lock) {
            return decoderName;
        }
    }

    public Decoder decoder() {
        synchronized (lock) {
            return decoder;
        }
    }

    public int droppedFrames() {
        synchronized (lock) {
            return dropped;
        }
    }

    public boolean isPlaying() {
        synchronized (lock) {
            return playing;
        }
    }

    public boolean isCompleted() {
        synchronized (lock) {
            return completed;
        }
    }

    public float speed() {
        synchronized (lock) {
            return speed;
        }
    }

    /** True while the sound is off because the time stretch refused the speed. */
    public boolean audioSuspended() {
        synchronized (lock) {
            return audioSuspended;
        }
    }

    private boolean isReleased() {
        synchronized (lock) {
            return released;
        }
    }

    public long positionMs() {
        return positionUs() / 1000;
    }

    public long positionUs() {
        synchronized (lock) {
            return clockLocked();
        }
    }

    private long clockLocked() {
        if (!videoReady) return seekUs;
        long t;
        if (clockRunning) {
            long a = audioWorker != null ? audioWorker.clockUs(speed) : -1;
            long now = System.nanoTime();
            if (a >= 0) {
                t = a;
                clockBaseUs = a;
                clockAnchorNs = now;
            } else t = clockBaseUs + (long) ((now - clockAnchorNs) / 1000 * speed);
            if (t < lastClockUs) t = lastClockUs; // the audio clock starting behind the system one: hold, never step back
        } else t = clockBaseUs;
        if (durationUs > 0 && t > durationUs) t = durationUs;
        lastClockUs = t;
        return t;
    }

    // ------------------------------------------------------------------ controls

    public void play() {
        synchronized (lock) {
            if (released) return;
            if (completed) seekLocked(0, true);
            playing = true;
            startClockLocked();
            lock.notifyAll();
        }
    }

    public void pause() {
        synchronized (lock) {
            if (!playing) return;
            clockBaseUs = clockLocked();
            playing = false;
            clockRunning = false;
            if (audioWorker != null) audioWorker.pauseTrack();
            lock.notifyAll();
        }
    }

    private void startClockLocked() {
        if (!playing || !videoReady || clockRunning || completed) return;
        clockRunning = true;
        clockAnchorNs = System.nanoTime();
        if (audioWorker != null) audioWorker.playTrack();
    }

    /** Exact: the frame at {@code ms}; not exact: the key frame before it (fast, for scrubbing). */
    public void seekTo(long ms, boolean exact) {
        synchronized (lock) {
            if (released) return;
            seekLocked(ms * 1000, exact);
            lock.notifyAll();
        }
    }

    static void dbg(String m) {
        Log.d(TAG, m);
    }

    private void seekLocked(long us, boolean exact) {
        dbg("seek " + us + " surface=" + (surface != null) + " playing=" + playing);
        if (durationUs > 0) us = Math.min(us, durationUs);
        us = Math.max(0, us);
        seekGen++;
        seekUs = us;
        seekExact = exact;
        clockRunning = false;
        clockBaseUs = us;
        lastClockUs = us;
        completed = false;
        videoDone = audioDone = false;
        videoReady = video == null || surface == null;
        if (videoReady) startClockLocked();
    }

    public void setSpeed(float s) {
        synchronized (lock) {
            clockBaseUs = clockLocked();
            clockAnchorNs = System.nanoTime();
            speed = s;
            if (audioSuspended) {
                audioSuspended = false; // another try at the new speed
                audioGen++;
            } else if (audioWorker != null && !audioWorker.applySpeed(s)) {
                audioSuspended = true;
                audioGen++;
            }
            lock.notifyAll();
        }
    }

    public void setVolume(float v) {
        synchronized (lock) {
            volume = v;
            if (audioWorker != null) audioWorker.applyVolume(v);
        }
    }

    /** -1: no sound track. */
    public void setAudioTrack(int index) {
        synchronized (lock) {
            if (index == audioIndex) return;
            audioIndex = index < audios.size() ? index : -1;
            audioBroken = false;
            audioGen++;
            lock.notifyAll();
        }
    }

    public void setDecoder(Decoder d) {
        synchronized (lock) {
            if (d == decoder) return;
            decoder = d;
            videoGen++;
            lock.notifyAll();
        }
    }

    /**
     * The surface to draw into, or null (gone: background play). Waits up to 400 ms for the video decoder to let go of the old one,
     * so a destroyed surface is never drawn into.
     */
    public void setSurface(@Nullable Surface s) {
        synchronized (lock) {
            if (s == surface) return;
            dbg("setSurface " + s + " videoReady=" + videoReady);
            surface = s;
            int gen = ++videoGen;
            if (s == null && !videoReady) {
                videoReady = true; // a seek waiting for a frame that will not come
                startClockLocked();
            }
            lock.notifyAll();
            if (videoWorker == null || released) return;
            long until = System.currentTimeMillis() + 400;
            while (videoAck < gen && !released) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            playing = false;
            lock.notifyAll();
        }
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ worker helpers

    private void waitLock(long ms) {
        synchronized (lock) {
            if (released) return;
            try {
                lock.wait(Math.max(1, ms));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void setVideoReady(int gen, @Nullable String decoderUsed) {
        synchronized (lock) {
            dbg("ready gen=" + gen + " seekGen=" + seekGen + " was=" + videoReady);
            if (gen != seekGen) return;
            if (!videoReady) {
                videoReady = true;
                clockBaseUs = seekUs;
                lastClockUs = seekUs;
                startClockLocked();
            }
            lock.notifyAll();
        }
    }

    private void markDone(boolean isVideo, int gen) {
        boolean fire = false;
        synchronized (lock) {
            if (gen != seekGen || completed) return;
            if (isVideo) videoDone = true;
            else audioDone = true;
            boolean needAudio = audioWorker != null && audioIndex >= 0 && !audioSuspended && !audioBroken;
            if ((videoDone || video == null) && (audioDone || !needAudio)) {
                clockBaseUs = durationUs > 0 ? durationUs : clockLocked();
                completed = true;
                playing = false;
                clockRunning = false;
                if (audioWorker != null) audioWorker.pauseTrack();
                fire = true;
            }
            lock.notifyAll();
        }
        if (fire) main.post(() -> {
            if (!isReleased()) listener.onCompleted();
        });
    }

    private void postError(String message) {
        main.post(() -> {
            if (!isReleased()) listener.onError(message);
        });
    }

    /** Queues one sample (or the end of stream); true when the end was queued. */
    private static boolean feed(MediaExtractor ex, MediaCodec c) {
        int in = c.dequeueInputBuffer(2000);
        if (in < 0) return false;
        ByteBuffer b = c.getInputBuffer(in);
        int n = b != null ? ex.readSampleData(b, 0) : -1;
        if (n < 0) {
            c.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        c.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
        ex.advance();
        return false;
    }

    // ------------------------------------------------------------------ video

    private final class VideoWorker extends Thread {
        private MediaExtractor ex;
        @Nullable private MediaCodec codec;
        private int myVideoGen = -1, mySeekGen = -1;
        private boolean inEos, outEos, renderNext, softwareFallback, reopen, queued, gotOutput;
        @Nullable private Decoder myMode;
        private long dropBeforeUs = -1, lastPts;
        private int pending = -1;
        private long pendingPts;

        VideoWorker() {
            super("video-decode");
        }

        @Override
        public void run() {
            try {
                ex = extractor();
                ex.selectTrack(video.index);
                loop();
            } catch (Exception | LinkageError e) {
                Log.w(TAG, "video worker: " + e);
                postError(String.valueOf(e.getMessage()));
            } finally {
                closeCodec();
                if (ex != null) ex.release();
                synchronized (lock) {
                    videoAck = Integer.MAX_VALUE;
                    lock.notifyAll();
                }
            }
        }

        private void closeCodec() {
            pending = -1;
            if (codec == null) return;
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
            }
            codec = null;
        }

        /** Opens the decoder for the mode on {@code s}; false when none would. */
        private boolean openCodec(Surface s, Decoder mode) {
            MediaFormat f = video.format;
            List<String> mimes = new ArrayList<>();
            mimes.add(video.mime);
            if ("video/dolby-vision".equals(video.mime)) {
                mimes.add(MediaFormat.MIMETYPE_VIDEO_HEVC); // profile 8 / 9 streams are HEVC / AVC underneath
                mimes.add(MediaFormat.MIMETYPE_VIDEO_AVC);
            }
            for (String mime : mimes) {
                for (VideoDecoders.Candidate cand : VideoDecoders.candidates(mime, f, mode, softwareFallback)) {
                    MediaCodec c = null;
                    try {
                        c = MediaCodec.createByCodecName(cand.name);
                        MediaFormat use = MediaFormat.createVideoFormat(mime, video.integer(MediaFormat.KEY_WIDTH, 0), video.integer(MediaFormat.KEY_HEIGHT, 0));
                        copyVideoKeys(f, use);
                        c.configure(use, s, null, 0);
                        c.start();
                        codec = c;
                        queued = gotOutput = false;
                        boolean fell = softwareFallback || (mode == Decoder.HW_PLUS && !cand.hardware);
                        synchronized (lock) {
                            decoderName = cand.name;
                        }
                        main.post(() -> listener.onDecoder(cand.name, fell));
                        return true;
                    } catch (Exception e) {
                        Log.w(TAG, "decoder " + cand.name + ": " + e);
                        if (c != null) try {
                            c.release();
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
            return false;
        }

        /** Moves the running decoder to another surface; false when it cannot (it is reopened then). */
        private boolean swapSurface(Surface s) {
            try {
                codec.setOutputSurface(s);
                dbg("surface swapped");
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "setOutputSurface: " + e);
                return false;
            }
        }

        private void seekExtractor(long us) {
            ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            inEos = false;
            outEos = false;
            pending = -1;
        }

        private void loop() throws InterruptedException {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                Surface s;
                int vGen, sGen;
                long target;
                boolean exact;
                Decoder mode;
                synchronized (lock) {
                    if (released) return;
                    s = surface;
                    vGen = videoGen;
                    sGen = seekGen;
                    target = seekUs;
                    exact = seekExact;
                    mode = decoder;
                }
                if (s != null && !s.isValid()) s = null; // destroyed meanwhile: as gone
                if (vGen != myVideoGen && !reopen && codec != null && s != null && mode == myMode && swapSurface(s)) {
                    myVideoGen = vGen; // the same decoder draws into the new surface (a rotation, back from the background)
                    synchronized (lock) {
                        videoAck = vGen;
                        lock.notifyAll();
                    }
                }
                if (vGen != myVideoGen || reopen) {
                    closeCodec();
                    if (mode != myMode) softwareFallback = false; // a new choice gets its hardware try again
                    myMode = mode;
                    myVideoGen = vGen;
                    reopen = false;
                    boolean opened = s != null && openCodec(s, mode);
                    dbg("video open s=" + s + " opened=" + opened + " sGen=" + sGen + " mySeekGen=" + mySeekGen);
                    if (s != null && !opened) postError(mode == Decoder.HW ? "hw" : "decoder");
                    synchronized (lock) {
                        videoAck = vGen;
                        lock.notifyAll();
                    }
                    if (codec != null && sGen == mySeekGen) {
                        boolean timed;
                        long now;
                        synchronized (lock) {
                            timed = playing && videoReady;
                            now = clockLocked();
                        }
                        seekExtractor(now);
                        dropBeforeUs = now;
                        renderNext = !timed; // while the clock runs frames are timed as usual (the late ones dropped)
                    }
                    if (codec == null) setVideoReady(sGen, null);
                }
                if (sGen != mySeekGen) {
                    mySeekGen = sGen;
                    if (codec != null && queued) {
                        if (gotOutput) {
                            try {
                                codec.flush();
                            } catch (RuntimeException e) {
                                Log.w(TAG, "flush: " + e);
                            }
                        } else {
                            // a flush before the first output loses the codec-specific data (no picture until an in-band one): reopen
                            closeCodec();
                            if (s == null || !openCodec(s, mode)) postError("decoder");
                        }
                    }
                    seekExtractor(target);
                    dropBeforeUs = exact ? target : -1;
                    renderNext = true;
                    if (codec == null) setVideoReady(sGen, null);
                }
                if (codec == null) {
                    // no surface: the clock alone decides the end
                    long dur;
                    long now;
                    synchronized (lock) {
                        dur = durationUs;
                        now = clockLocked();
                    }
                    if (dur > 0 && now >= dur - 20_000) markDone(true, sGen);
                    waitLock(50);
                    continue;
                }
                try {
                    step(info, sGen, vGen);
                } catch (IllegalStateException e) { // MediaCodec.CodecException too
                    Log.w(TAG, "video decode: " + e);
                    Decoder m;
                    synchronized (lock) {
                        m = decoder;
                    }
                    if (m == Decoder.HW_PLUS && !softwareFallback) {
                        softwareFallback = true; // reopen with the software decoder at the clock
                        closeCodec();
                        reopen = true;
                    } else {
                        closeCodec();
                        postError("decode");
                        synchronized (lock) {
                            if (!videoReady) {
                                videoReady = true;
                                startClockLocked();
                            }
                        }
                        waitLock(200);
                    }
                }
            }
        }

        private void step(MediaCodec.BufferInfo info, int sGen, int vGen) throws InterruptedException {
            MediaCodec c = codec;
            if (!inEos) {
                inEos = feed(ex, c);
                queued = true;
            }
            if (pending < 0 && !outEos) {
                int idx = c.dequeueOutputBuffer(info, 5000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) gotOutput = true;
                if (idx < 0) return;
                gotOutput = true;
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && info.size == 0) {
                    outEos = true;
                    c.releaseOutputBuffer(idx, false);
                    if (renderNext) {
                        renderNext = false;
                        setVideoReady(sGen, null);
                    }
                    return;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                if (dropBeforeUs >= 0 && info.presentationTimeUs < dropBeforeUs && !outEos) {
                    if (info.presentationTimeUs % 1000 < 40) dbg("drop-before pts=" + info.presentationTimeUs + " < " + dropBeforeUs);
                    c.releaseOutputBuffer(idx, false);
                    return;
                }
                pending = idx;
                pendingPts = info.presentationTimeUs;
            }
            if (pending < 0) {
                // the stream ended: done when the clock passes the last frame
                long now;
                synchronized (lock) {
                    now = clockLocked();
                }
                if (now >= lastPts) markDone(true, sGen);
                waitLock(15);
                return;
            }
            if (renderNext) {
                c.releaseOutputBuffer(pending, true);
                lastPts = pendingPts;
                pending = -1;
                renderNext = false;
                dropBeforeUs = -1;
                setVideoReady(sGen, null);
                return;
            }
            long early;
            synchronized (lock) {
                if (seekGen != sGen || videoGen != vGen || released) return;
                if (!playing || !clockRunning) {
                    lock.wait(30);
                    return;
                }
                early = (long) ((pendingPts - clockLocked()) / speed);
            }
            if (early > 30_000) {
                waitLock(Math.min((early - 25_000) / 1000, 20));
                return;
            }
            if (early < -LATE_US) {
                if (dropped % 30 == 0) dbg("late pts=" + pendingPts + " early=" + early);
                c.releaseOutputBuffer(pending, false);
                synchronized (lock) {
                    dropped++;
                }
            } else c.releaseOutputBuffer(pending, System.nanoTime() + Math.max(0, early) * 1000);
            lastPts = pendingPts;
            pending = -1;
            dropBeforeUs = -1;
        }
    }

    private static void copyVideoKeys(MediaFormat from, MediaFormat to) {
        String[] keys = {MediaFormat.KEY_ROTATION, "csd-0", "csd-1", "csd-2", MediaFormat.KEY_MAX_INPUT_SIZE, MediaFormat.KEY_PROFILE,
                MediaFormat.KEY_LEVEL, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE,
                MediaFormat.KEY_HDR_STATIC_INFO, MediaFormat.KEY_FRAME_RATE};
        for (String k : keys) {
            if (!from.containsKey(k)) continue;
            try {
                switch (from.getValueTypeForKey(k)) {
                    case MediaFormat.TYPE_INTEGER:
                        to.setInteger(k, from.getInteger(k));
                        break;
                    case MediaFormat.TYPE_LONG:
                        to.setLong(k, from.getLong(k));
                        break;
                    case MediaFormat.TYPE_FLOAT:
                        to.setFloat(k, from.getFloat(k));
                        break;
                    case MediaFormat.TYPE_STRING:
                        to.setString(k, from.getString(k));
                        break;
                    case MediaFormat.TYPE_BYTE_BUFFER:
                        to.setByteBuffer(k, from.getByteBuffer(k));
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ audio

    private final class AudioWorker extends Thread {
        private MediaExtractor ex;
        @Nullable private MediaCodec codec;
        @Nullable private volatile AudioTrack track;
        private final AudioTimestamp stamp = new AudioTimestamp();
        private int sampleRate, frameBytes;
        private volatile long baseUs, framesWritten, playStartNs;
        private int myAudioGen = -1, mySeekGen = -1, selected = -1;
        private boolean inEos, outEos, queued, gotOutput;
        private long dropBeforeUs = -1;

        AudioWorker() {
            super("video-audio");
        }

        /** The audio clock in µs, or -1 while the track is not audibly playing. Called under the engine's lock. */
        long clockUs(float speed) {
            AudioTrack t = track;
            if (t == null || sampleRate <= 0) return -1;
            try {
                if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) return -1;
                long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                if (head == 0) return -1;
                long frames = head;
                if (t.getTimestamp(stamp) && stamp.nanoTime >= playStartNs) {
                    long f = stamp.framePosition + (long) ((System.nanoTime() - stamp.nanoTime) / 1e9 * sampleRate * speed);
                    if (f >= head - sampleRate / 5 && f <= framesWritten) frames = f; // smoother than the head, when sane
                }
                return baseUs + frames * 1_000_000L / sampleRate;
            } catch (RuntimeException e) {
                return -1;
            }
        }

        void playTrack() {
            AudioTrack t = track;
            if (t == null) return;
            try {
                playStartNs = System.nanoTime();
                t.play();
            } catch (RuntimeException e) {
                Log.w(TAG, "audio play: " + e);
            }
        }

        void pauseTrack() {
            AudioTrack t = track;
            if (t == null) return;
            try {
                t.pause();
            } catch (RuntimeException ignored) {
            }
        }

        boolean applySpeed(float s) {
            AudioTrack t = track;
            if (t == null) return true;
            try {
                t.setPlaybackParams(new PlaybackParams().setSpeed(s).setPitch(1f).setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL));
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "speed " + s + ": " + e);
                return false;
            }
        }

        void applyVolume(float v) {
            AudioTrack t = track;
            if (t != null) try {
                t.setVolume(v);
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void run() {
            try {
                ex = extractor();
                loop();
            } catch (Exception | LinkageError e) {
                Log.w(TAG, "audio worker: " + e);
                synchronized (lock) {
                    audioBroken = true;
                }
            } finally {
                closeTrack();
                closeCodec();
                if (ex != null) ex.release();
            }
        }

        private void closeTrack() {
            AudioTrack t = track;
            track = null;
            if (t == null) return;
            try {
                t.pause();
                t.flush();
            } catch (RuntimeException ignored) {
            }
            t.release();
        }

        private void closeCodec() {
            if (codec == null) return;
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
            }
            codec = null;
        }

        private boolean openCodec(Track t) {
            try {
                MediaCodec c = MediaCodec.createDecoderByType(t.mime);
                c.configure(t.format, null, null, 0);
                c.start();
                codec = c;
                queued = gotOutput = false;
                return true;
            } catch (Exception e) {
                Log.w(TAG, "audio decoder " + t.mime + ": " + e);
                return false;
            }
        }

        private void loop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int aGen, sGen, index;
                long target;
                boolean suspended;
                synchronized (lock) {
                    if (released) return;
                    aGen = audioGen;
                    sGen = seekGen;
                    target = seekUs;
                    index = audioIndex;
                    suspended = audioSuspended;
                }
                if (aGen != myAudioGen) {
                    myAudioGen = aGen;
                    closeTrack();
                    closeCodec();
                    if (selected >= 0) ex.unselectTrack(selected);
                    selected = -1;
                    if (index >= 0 && !suspended) {
                        Track t = audios.get(index);
                        ex.selectTrack(t.index);
                        selected = t.index;
                        if (openCodec(t)) {
                            long now = positionUs();
                            ex.seekTo(now, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                            dropBeforeUs = now;
                            inEos = outEos = false;
                        } else synchronized (lock) {
                            audioBroken = true;
                        }
                    }
                    mySeekGen = sGen;
                }
                if (sGen != mySeekGen) {
                    mySeekGen = sGen;
                    closeTrack();
                    if (codec != null) {
                        if (gotOutput || !queued) {
                            if (queued) try {
                                codec.flush();
                            } catch (RuntimeException e) {
                                Log.w(TAG, "audio flush: " + e);
                            }
                        } else { // as the video: no flush before the first output
                            closeCodec();
                            if (!openCodec(audios.get(index))) synchronized (lock) {
                                audioBroken = true;
                            }
                        }
                        ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    }
                    dropBeforeUs = target;
                    inEos = outEos = false;
                }
                if (codec == null) {
                    waitLock(50);
                    continue;
                }
                try {
                    step(info, sGen, aGen);
                } catch (IllegalStateException e) { // MediaCodec.CodecException too
                    Log.w(TAG, "audio decode: " + e);
                    closeTrack();
                    closeCodec();
                    synchronized (lock) {
                        audioBroken = true;
                    }
                }
            }
        }

        private boolean stale(int sGen, int aGen) {
            synchronized (lock) {
                return released || sGen != seekGen || aGen != audioGen;
            }
        }

        private void step(MediaCodec.BufferInfo info, int sGen, int aGen) {
            MediaCodec c = codec;
            if (!inEos) {
                inEos = feed(ex, c);
                queued = true;
            }
            if (outEos) {
                AudioTrack t = track;
                long head = 0;
                try {
                    if (t != null) head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                } catch (RuntimeException ignored) {
                }
                if (t == null || head >= framesWritten - 32) markDone(false, sGen);
                waitLock(20);
                return;
            }
            int idx = c.dequeueOutputBuffer(info, 5000);
            if (idx >= 0 || idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) gotOutput = true;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track != null) closeTrack(); // re-made for the new format with the next buffer
                return;
            }
            if (idx < 0) return;
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
            ByteBuffer out = c.getOutputBuffer(idx);
            if (out != null && info.size > 0) {
                out.position(info.offset);
                out.limit(info.offset + info.size);
                long pts = info.presentationTimeUs;
                if (track == null && !makeTrack(c.getOutputFormat())) {
                    c.releaseOutputBuffer(idx, false);
                    return;
                }
                if (dropBeforeUs >= 0) {
                    long frames = info.size / frameBytes;
                    long endUs = pts + frames * 1_000_000L / sampleRate;
                    if (endUs <= dropBeforeUs) {
                        c.releaseOutputBuffer(idx, false);
                        return;
                    }
                    long skip = Math.max(0, (dropBeforeUs - pts) * sampleRate / 1_000_000L) * frameBytes;
                    out.position((int) Math.min(info.offset + skip, info.offset + info.size));
                    if (framesWritten == 0) baseUs = Math.max(pts, dropBeforeUs);
                    dropBeforeUs = -1;
                } else if (framesWritten == 0) baseUs = pts;
                write(out, sGen, aGen);
            }
            c.releaseOutputBuffer(idx, false);
        }

        /** Writes without blocking, waiting a little while the track is full, until done or a seek / switch makes it stale. */
        private void write(ByteBuffer out, int sGen, int aGen) {
            while (out.hasRemaining()) {
                AudioTrack t = track;
                if (t == null || stale(sGen, aGen)) return;
                int n;
                try {
                    n = t.write(out, out.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                } catch (RuntimeException e) {
                    return;
                }
                if (n < 0) return;
                framesWritten += n / frameBytes;
                if (n == 0) waitLock(8);
            }
        }

        private boolean makeTrack(MediaFormat f) {
            try {
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                int encoding = f.containsKey(MediaFormat.KEY_PCM_ENCODING) ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                int mask = channelMask(channels);
                if (mask == 0) throw new IllegalArgumentException(channels + " channels");
                int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
                frameBytes = bytes * channels;
                int min = AudioTrack.getMinBufferSize(sampleRate, mask, encoding);
                int size = Math.max(min * 6, sampleRate * frameBytes / 3); // room for the time stretch up to 4x
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask).setEncoding(encoding).build())
                        .setBufferSizeInBytes(size)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setSessionId(sessionId)
                        .build();
                framesWritten = 0;
                synchronized (lock) {
                    t.setVolume(volume);
                    track = t;
                    if (speed != 1f && !applySpeed(speed)) {
                        audioSuspended = true;
                        audioGen++;
                        lock.notifyAll();
                    } else if (clockRunning) playTrack();
                }
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "audio track: " + e);
                synchronized (lock) {
                    audioBroken = true;
                }
                closeCodec();
                return false;
            }
        }
    }

    static int channelMask(int channels) {
        switch (channels) {
            case 1:
                return AudioFormat.CHANNEL_OUT_MONO;
            case 2:
                return AudioFormat.CHANNEL_OUT_STEREO;
            case 3:
                return AudioFormat.CHANNEL_OUT_STEREO | AudioFormat.CHANNEL_OUT_FRONT_CENTER;
            case 4:
                return AudioFormat.CHANNEL_OUT_QUAD;
            case 5:
                return AudioFormat.CHANNEL_OUT_QUAD | AudioFormat.CHANNEL_OUT_FRONT_CENTER;
            case 6:
                return AudioFormat.CHANNEL_OUT_5POINT1;
            case 8:
                return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
            default:
                return 0;
        }
    }

    /** «H.264», «HEVC», «AAC»... for a MIME type. */
    public static String codecLabel(String mime) {
        switch (mime) {
            case MediaFormat.MIMETYPE_VIDEO_AVC:
                return "H.264";
            case MediaFormat.MIMETYPE_VIDEO_HEVC:
                return "HEVC";
            case MediaFormat.MIMETYPE_VIDEO_VP8:
                return "VP8";
            case MediaFormat.MIMETYPE_VIDEO_VP9:
                return "VP9";
            case "video/av01":
                return "AV1";
            case "video/dolby-vision":
                return "Dolby Vision";
            case MediaFormat.MIMETYPE_VIDEO_MPEG4:
                return "MPEG-4";
            case MediaFormat.MIMETYPE_VIDEO_H263:
                return "H.263";
            case MediaFormat.MIMETYPE_AUDIO_AAC:
                return "AAC";
            case MediaFormat.MIMETYPE_AUDIO_OPUS:
                return "Opus";
            case MediaFormat.MIMETYPE_AUDIO_VORBIS:
                return "Vorbis";
            case MediaFormat.MIMETYPE_AUDIO_MPEG:
                return "MP3";
            case MediaFormat.MIMETYPE_AUDIO_FLAC:
                return "FLAC";
            case MediaFormat.MIMETYPE_AUDIO_AMR_NB:
            case MediaFormat.MIMETYPE_AUDIO_AMR_WB:
                return "AMR";
            case MediaFormat.MIMETYPE_AUDIO_AC3:
                return "AC-3";
            case MediaFormat.MIMETYPE_AUDIO_EAC3:
                return "E-AC-3";
            case MediaFormat.MIMETYPE_AUDIO_RAW:
                return "PCM";
            default:
                int slash = mime.indexOf('/');
                return (slash >= 0 ? mime.substring(slash + 1) : mime).toUpperCase(Locale.ROOT);
        }
    }
}
