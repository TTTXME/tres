package com.scamera.app.capture;

import android.hardware.camera2.CaptureRequest;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The vivo SDK lease of FULLRES: VivoCameraManager values 40 (SDK RAW) = 1 and 33 (VCF) = 2 while our camera is open, the
 * values found before given back when it closes.
 * <p>
 * P179: the lease is journaled on disk before it is taken (owner pid, the values found) and the journal cleared once they
 * are back. A process killed while it holds the lease (LOW_MEMORY, a crash: no finally runs) leaves the journal; the next
 * start of the camera ({@link #recoverStale}) gives the values back - only while they are still the ones we set and no
 * camera is in use by anyone (another client's own lease is never reset), else the journal is just dropped.
 * <p>
 * P640 (owner, 2026-11-12, X200 Ultra: after a while with our camera the stock camera's viewfinder is black until a reboot):
 * the values are the phone's, not ours, and every way they could stay set is closed.
 * <ul>
 * <li>One lease a process ({@link Held}): a second session opened while the first is still held (the FULLRES session's values
 * waiting to be given back, the ISZ lease taken meanwhile) shares it instead of reading our own values as «the values
 * found» - the values go back when the last holder closes.</li>
 * <li>SDK RAW found already set with no lease of this process is a lease that was never given back, never «the value found»:
 * the journal's values, else the values of the last clean start ({@link Journal#baseline}), else {@link #SDK_DEFAULT}.</li>
 * <li>{@link #sweep}: what is still set and held by nobody goes back (a give that failed, a dead process's journal, values
 * left with no journal) - at the app's start, after a camera closed.</li>
 * <li>{@link Watch}: a process that dies holding the lease (a native crash, a kill) has it given back by the guard process
 * (LeaseGuardService); an uncaught exception gives it back itself ({@link #onCrash}).</li>
 * </ul>
 */
public final class VivoSdkRawSession implements AutoCloseable {
    interface Provider {
        int get(int key) throws Exception;
        void set(int key, int value) throws Exception;
        void settle();
    }

    /** P179: where the lease is written down: {pid, the SDK value found, the VCF value found}, or null. */
    interface Journal {
        @androidx.annotation.Nullable int[] read();
        void write(int pid, int sdk, int vcf);
        void clear();
        /** P640: {the SDK value, the VCF value} read at the last open that found no lease set (kept over {@link #clear}); null: none. */
        @androidx.annotation.Nullable default int[] baseline() { return null; }
        default void baseline(int sdk, int vcf) { }
    }

    /** P640: told when this process takes the lease and when the values are back (the guard process, LeaseGuard). */
    interface Watch {
        void taken(int sdk, int vcf);
        void back();
    }

    /**
     * P640: a provider's lease of this process - the values to give back and how many sessions hold it. It stays on the books
     * with no holder while a give failed: the next close, open or {@link #sweep} gives the same values back.
     */
    static final class Held {
        final int sdk, vcf;
        int holders;

        Held(int sdk, int vcf) {
            this.sdk = sdk;
            this.vcf = vcf;
        }
    }

    static final int SDK_KEY = 40, VCF_KEY = 33, SDK_OURS = 1, VCF_OURS = 2;
    /** P640: SDK RAW of a phone nobody leased (the X200 Ultra's reports: «restored SDK40=0»). */
    static final int SDK_DEFAULT = 0;
    /** The leases of this process by provider (the lock of every lease step). */
    private static final java.util.Map<Provider, Held> HELD = new java.util.WeakHashMap<>();
    @androidx.annotation.Nullable private static volatile Watch watch;
    /** P640: {the SDK value, the VCF value} an uncaught exception gives back without a lock ({@link #onCrash}); null: no lease. */
    @androidx.annotation.Nullable private static volatile int[] crashValues;
    @androidx.annotation.Nullable private static volatile Provider system;
    private static final String TAG = "VivoSdkRawSession";

    private static final AtomicLong CAPTURE_ID = new AtomicLong();
    private final Provider provider;
    @androidx.annotation.Nullable private final Journal journal;
    private final Held held;
    private volatile boolean closed;
    /** This session no longer counts among the lease's holders. */
    private boolean released;

    private VivoSdkRawSession(Provider provider, @androidx.annotation.Nullable Journal journal, Held held) {
        this.provider = provider;
        this.journal = journal;
        this.held = held;
    }

    /** The phone's VivoCameraManager: one object a process (P640: the leases are kept by provider). */
    static Provider systemProvider() throws Exception {
        Provider known = system;
        if (known != null) return known;
        synchronized (VivoSdkRawSession.class) {
            if (system != null) return system;
            Class<?> api = Class.forName("android.hardware.vivocamera.VivoCameraManager");
            Object manager = api.getMethod("getInstance").invoke(null);
            Method get = api.getMethod("getInfo", int.class);
            Method set = api.getMethod("setValue", int.class, int.class);
            system = new Provider() {
                @Override public int get(int key) throws Exception { return (Integer) get.invoke(manager, key); }
                @Override public void set(int key, int value) throws Exception { set.invoke(manager, key, value); }
                @Override public void settle() { SystemClock.sleep(100); }
            };
            return system;
        }
    }

    static void watch(@androidx.annotation.Nullable Watch w) {
        watch = w;
    }

    /** The tests' process death: this process's books of {@code provider}'s lease are gone, the values stay as they are. */
    static void died(Provider provider) {
        synchronized (HELD) {
            HELD.remove(provider);
        }
    }

    /** The provider {@link #systemProvider} answers with (the tests' stand-in for the phone's manager; null: none yet). */
    static void system(@androidx.annotation.Nullable Provider provider) {
        system = provider;
    }

    /** "SDK RAW a, VCF b" - the values this lease gives back, for the log. */
    public String found() {
        return "SDK RAW " + held.sdk + ", VCF " + held.vcf;
    }

    public static VivoSdkRawSession open() throws Exception {
        return open(systemProvider(), PrefsJournal.get(), android.os.Process.myPid());
    }

    /**
     * P192: the native FULLRES session of a phone without the vivo SDK lease (OPPO Find X7 Ultra): nothing is set or given back,
     * the session only marks the route.
     */
    static VivoSdkRawSession inert() {
        final int[] values = new int[64];
        return new VivoSdkRawSession(new Provider() {
            @Override public int get(int key) { return values[key & 63]; }
            @Override public void set(int key, int value) { values[key & 63] = value; }
            @Override public void settle() { }
        }, null, new Held(0, 0));
    }

    static VivoSdkRawSession open(Provider provider) throws Exception {
        return Lease.open(provider, null, 0);
    }

    static VivoSdkRawSession open(Provider provider, @androidx.annotation.Nullable Journal journal, int pid) throws Exception {
        return Lease.open(provider, journal, pid);
    }

    /**
     * The lease's steps as statics of their own class (a static mock of the session in the tests leaves them real).
     */
    static final class Lease {
        private Lease() {}

        static VivoSdkRawSession open(Provider provider, @androidx.annotation.Nullable Journal journal, int pid) throws Exception {
            synchronized (HELD) {
                Held held = HELD.get(provider);
                final boolean first = held == null;
                if (first) {
                    int sdk = provider.get(SDK_KEY), vcf = provider.get(VCF_KEY);
                    final int[] journaled = journal == null ? null : journal.read();
                    if (sdk == SDK_OURS) {
                        // P640: set already and no lease of this process: a lease that was never given back (a dead process's,
                        // a failed give's) - what it found goes back, never our own value
                        final int[] base = journal == null ? null : journal.baseline();
                        if (journaled != null && journaled[1] != SDK_OURS) {
                            sdk = journaled[1];
                            vcf = journaled[2];
                        } else if (base != null && base[0] != SDK_OURS) {
                            sdk = base[0];
                            vcf = base[1];
                        } else {
                            sdk = SDK_DEFAULT;
                        }
                    } else if (journal != null) {
                        journal.baseline(sdk, vcf);
                    }
                    held = new Held(sdk, vcf);
                }
                if (journal != null) journal.write(pid, held.sdk, held.vcf);
                final VivoSdkRawSession session = new VivoSdkRawSession(provider, journal, held);
                HELD.put(provider, held);
                held.holders++;
                if (first) {
                    if (provider == system) crashValues = new int[]{held.sdk, held.vcf};
                    final Watch w = watch;
                    if (w != null) try { w.taken(held.sdk, held.vcf); } catch (RuntimeException ignored) { }
                }
                try {
                    provider.set(VCF_KEY, VCF_OURS);
                    provider.set(SDK_KEY, SDK_OURS);
                    provider.settle();
                    if (provider.get(SDK_KEY) != SDK_OURS || provider.get(VCF_KEY) != VCF_OURS)
                        throw new IllegalStateException("Vivo SDK RAW properties were not applied");
                    return session;
                } catch (Exception failure) {
                    try { session.close(); } catch (Exception restore) { failure.addSuppressed(restore); }
                    throw failure;
                }
            }
        }

        /** P640: the values of {@code held} go back and the lease leaves the books (the caller holds the lock; throws: it stays). */
        static void settle(Provider provider, @androidx.annotation.Nullable Journal journal, Held held) throws Exception {
            give(provider, held.sdk, held.vcf);
            HELD.remove(provider);
            if (journal != null) journal.clear();
            if (provider == system) crashValues = null;
            final Watch w = watch;
            if (w != null) try { w.back(); } catch (RuntimeException ignored) { }
        }

        /**
         * P640: what is set and held by nobody goes back. "none"; "held" (a session of this process holds the lease);
         * "pending restored" (a give that had failed); the journal's result ({@link #recoverStale}); "orphan restored" (SDK RAW
         * set with no lease and no journal, no camera in use: the last clean start's values, else the default); "busy";
         * "failed: ...".
         */
        static String sweep(Provider provider, @androidx.annotation.Nullable Journal journal, int pid, java.util.function.BooleanSupplier camerasIdle) {
            try {
                synchronized (HELD) {
                    final Held held = HELD.get(provider);
                    if (held != null) {
                        if (held.holders > 0) return "held";
                        settle(provider, journal, held);
                        return "pending restored";
                    }
                }
                if (journal != null) {
                    final String stale = recoverStale(provider, journal, pid, camerasIdle);
                    if (!"none".equals(stale)) return stale;
                }
                if (provider.get(SDK_KEY) != SDK_OURS) return "none";
                if (!camerasIdle.getAsBoolean()) return "busy";
                synchronized (HELD) {
                    if (HELD.get(provider) != null) return "held";
                    final int[] base = journal == null ? null : journal.baseline();
                    final boolean known = base != null && base[0] != SDK_OURS;
                    give(provider, known ? base[0] : SDK_DEFAULT, known ? base[1] : provider.get(VCF_KEY));
                    if (journal != null) journal.clear();
                    return "orphan restored";
                }
            } catch (Exception e) {
                return "failed: " + e;
            }
        }

        /**
         * P640 (the guard process): the process that held the lease died - its values go back while SDK RAW is still ours.
         * "not ours" / "restored" / "failed: ...".
         */
        static String restoreAfterDeath(Provider provider, int sdk, int vcf) {
            try {
                if (provider.get(SDK_KEY) != SDK_OURS) return "not ours";
                give(provider, sdk == SDK_OURS ? SDK_DEFAULT : sdk, vcf);
                return "restored";
            } catch (Exception e) {
                return "failed: " + e;
            }
        }

        static void give(Provider provider, int sdk, int vcf) throws Exception {
            Exception failure = null;
            try { provider.set(VCF_KEY, vcf); } catch (Exception e) { failure = e; }
            try { provider.set(SDK_KEY, sdk); } catch (Exception e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            provider.settle();
            if (failure != null) throw failure;
            if (provider.get(SDK_KEY) != sdk || provider.get(VCF_KEY) != vcf)
                throw new IllegalStateException("Vivo SDK RAW properties were not restored");
        }

        static String recoverStale(Provider provider, Journal journal, int pid, java.util.function.BooleanSupplier camerasIdle) {
            int[] stale = journal.read();
            if (stale == null || stale[0] == pid) return "none";
            try {
                if (provider.get(SDK_KEY) != SDK_OURS || provider.get(VCF_KEY) != VCF_OURS) {
                    journal.clear();
                    return "not ours";
                }
                if (!camerasIdle.getAsBoolean()) return "busy";
                // P640: a journal that holds our own value (written by a build that read a stuck lease as «found») gives the default
                give(provider, stale[1] == SDK_OURS ? SDK_DEFAULT : stale[1], stale[2]);
                journal.clear();
                return "restored";
            } catch (Exception e) {
                return "failed: " + e;
            }
        }
    }

    public void apply(CaptureRequest.Builder builder, boolean capture, long previewTimestamp) {
        if (closed) throw new IllegalStateException("Vivo SDK RAW session is closed");
        ints(builder, "com.vivo.current_ui_module", 16);
        builder.set(new CaptureRequest.Key<>("vivo.control.currentModeEx", long[].class), new long[]{256});
        ints(builder, "vivo.control.is_pro_mode", 1);
        ints(builder, "vivo.control.echo.mode", 1);
        ints(builder, "vivo.control.eis.config.enable", 0);
        ints(builder, "vivo.control.advance_fullsize", 1);
        ints(builder, "vivo.control.ai_highresolution", 1);
        ints(builder, "vivo.control.reprocessMode", 0);
        if (capture) {
            long id = CAPTURE_ID.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
            longs(builder, "vivo.control.requestId", id);
            longs(builder, "vivo.control.globalCaptureId", id);
            longs(builder, "vivo.control.frameFlag", id);
            longs(builder, "vivo.control.displayTimeStamp", previewTimestamp);
            builder.set(new CaptureRequest.Key<>("vivo.control.isPAPNeeded", byte[].class), new byte[]{1});
            ints(builder, "vivo.control.sensorMode", 1);
            ints(builder, "vivo.control.isUpscale", 0);
            ints(builder, "vivo.control.isCapture", 0);
        }
    }

    public void applyStreamUsage(CaptureRequest.Builder builder) {
        ints(builder, "vivo.control.streamsUsage", 2, 1, 0);
    }

    public static void applyAutomaticExposure(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        // Stock Vivo Pro uses zero sensor values for AUTO instead of retaining the template's manual values.
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, 0);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 0L);
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 0L);
    }

    private static void ints(CaptureRequest.Builder builder, String name, int... values) {
        builder.set(new CaptureRequest.Key<>(name, int[].class), values);
    }

    private static void longs(CaptureRequest.Builder builder, String name, long value) {
        builder.set(new CaptureRequest.Key<>(name, long[].class), new long[]{value});
    }

    /**
     * P640: this session lets go of the lease; the values go back when no session of the process holds it any more. A give that
     * fails throws and leaves the lease on the books: close() again, the next open or {@link #sweep} gives the same values back.
     */
    @Override public void close() throws Exception {
        synchronized (HELD) {
            closed = true;
            if (HELD.get(provider) != held) return;
            if (!released) {
                released = true;
                held.holders--;
            }
            if (held.holders > 0) return;
            Lease.settle(provider, journal, held);
        }
    }

    /** {@link Lease#sweep} on this phone; cheap when nothing is set. The result for the log. */
    public static String sweep(android.content.Context context) {
        try {
            return Lease.sweep(systemProvider(), PrefsJournal.get(), android.os.Process.myPid(), () -> camerasIdle(context));
        } catch (Exception | LinkageError e) {
            return "failed: " + e;
        }
    }

    /**
     * P640: an uncaught exception is about to end the process - the lease's values are sent back at once (no lock, no wait: the
     * calls are one-way). Never throws.
     */
    public static void onCrash() {
        try {
            final int[] values = crashValues;
            final Provider provider = system;
            if (values == null || provider == null) return;
            crashValues = null;
            provider.set(VCF_KEY, values[1]);
            provider.set(SDK_KEY, values[0]);
        } catch (Throwable ignored) {
            // the process is going down anyway: the guard process and the journal are behind this
        }
    }

    /** P640 (the guard process): {@link Lease#restoreAfterDeath} on this phone. */
    static String restoreAfterDeath(int sdk, int vcf) {
        try {
            return Lease.restoreAfterDeath(systemProvider(), sdk, vcf);
        } catch (Exception | LinkageError e) {
            return "failed: " + e;
        }
    }

    /**
     * P179: a lease left by a process of ours that died gives the values back - while they are still ours and no camera is
     * in use (else another client may hold its own lease: the journal is dropped, nothing reset; with a camera busy it
     * waits for the next start). The result for the log: "none", "restored", "not ours", "busy" or "failed: ...".
     */
    static String recoverStale(Provider provider, Journal journal, int pid, java.util.function.BooleanSupplier camerasIdle) {
        return Lease.recoverStale(provider, journal, pid, camerasIdle);
    }

    /** True when no camera of this phone is in use by anyone (the availability callbacks report each camera at once). */
    static boolean camerasIdle(android.content.Context context) {
        android.hardware.camera2.CameraManager manager = context.getSystemService(android.hardware.camera2.CameraManager.class);
        if (manager == null) return false;
        java.util.Set<String> busy = java.util.concurrent.ConcurrentHashMap.newKeySet();
        android.os.HandlerThread thread = new android.os.HandlerThread("SdkLeaseIdle");
        thread.start();
        android.hardware.camera2.CameraManager.AvailabilityCallback callback = new android.hardware.camera2.CameraManager.AvailabilityCallback() {
            @Override public void onCameraAvailable(String id) { busy.remove(id); }
            @Override public void onCameraUnavailable(String id) { busy.add(id); }
        };
        try {
            manager.registerAvailabilityCallback(callback, new android.os.Handler(thread.getLooper()));
            SystemClock.sleep(200);
            return busy.isEmpty();
        } catch (RuntimeException e) {
            return false;
        } finally {
            try { manager.unregisterAvailabilityCallback(callback); } catch (RuntimeException ignored) {}
            thread.quitSafely();
        }
    }

    /** The journal in the app's own preferences (written at once: the process may die right after). */
    static final class PrefsJournal implements Journal {
        private static final String FILE = "scam_sdk_lease", PID = "pid", SDK = "sdk", VCF = "vcf", BASE_SDK = "base_sdk", BASE_VCF = "base_vcf";
        private final android.content.SharedPreferences prefs;

        private PrefsJournal(android.content.SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @androidx.annotation.Nullable
        static PrefsJournal get() {
            android.content.Context c = com.scamera.app.app.ScameraApp.getAppContext();
            return c == null ? null : new PrefsJournal(c.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE));
        }

        @androidx.annotation.Nullable
        @Override public int[] read() {
            if (!prefs.contains(PID)) return null;
            return new int[]{prefs.getInt(PID, 0), prefs.getInt(SDK, 0), prefs.getInt(VCF, 0)};
        }

        @Override public void write(int pid, int sdk, int vcf) {
            prefs.edit().putInt(PID, pid).putInt(SDK, sdk).putInt(VCF, vcf).commit();
        }

        @Override public void clear() {
            prefs.edit().remove(PID).remove(SDK).remove(VCF).commit(); // (P640: the baseline stays)
        }

        @androidx.annotation.Nullable
        @Override public int[] baseline() {
            if (!prefs.contains(BASE_SDK)) return null;
            return new int[]{prefs.getInt(BASE_SDK, 0), prefs.getInt(BASE_VCF, 0)};
        }

        @Override public void baseline(int sdk, int vcf) {
            if (prefs.getInt(BASE_SDK, Integer.MIN_VALUE) == sdk && prefs.getInt(BASE_VCF, Integer.MIN_VALUE) == vcf) return;
            prefs.edit().putInt(BASE_SDK, sdk).putInt(BASE_VCF, vcf).apply();
        }
    }
}
