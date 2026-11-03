package com.particlesdevs.photoncamera.processing.color;

import android.content.Context;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The photo engine's colour sampled once off the GL thread; spatial effects remain a photo operation. */
public final class FilmPreviewLut {
    public enum Domain { DISPLAY, SCENE }
    private static final int CACHE_SIZE = 6;
    private static final long BUDGET = 128L << 20;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, FilmLut> CACHE = new LinkedHashMap<>(8, .75f, true);
    private static final Map<String, List<Listener>> LOADING = new LinkedHashMap<>();
    private static boolean scheduled;
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();
    private static final Executor LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vf-reference-film");
        thread.setDaemon(true);
        return thread;
    });
    interface Renderer { FilmLut render(Context context, FilmSim.Settings settings, int size, Domain domain); }
    static Renderer renderer = FilmPreviewLut::render;
    static Executor executor = LOADER;

    private FilmPreviewLut() { }

    private static final class Listener {
        final BooleanSupplier current;
        final Consumer<FilmLut> ready;
        Listener(BooleanSupplier current, Consumer<FilmLut> ready) { this.current = current; this.ready = ready; }
    }

    static FilmReferenceParameters parameters(FilmSim.Settings settings) {
        FilmReferenceParameters p = FilmReferenceParameters.from(settings, false, 1);
        for (int offset : new int[]{256, 436, 492, 536, 580}) p.integer(offset, 0);
        // A colour cube has unrelated neighbours. Only the local DIR response belongs in it.
        p.real(200, 0).real(204, 0).integer(264, 4).real(268, 1).real(440, 1).real(448, 1)
                .integer(496, 1).real(500, .5f).real(612, 60).real(620, 0).real(648, 0);
        // P487: the spatial-only advanced controls at their defaults too, so moving them does not miss the cached colour table
        p.real(208, FilmAdvanced.DIR_TAIL_WEIGHT.def).real(444, FilmAdvanced.SCATTER_SIZE.def).real(452, FilmAdvanced.HALO_SIZE.def)
                .real(480, FilmAdvanced.BOOST.def).real(484, FilmAdvanced.BOOST_RANGE.def).real(488, FilmAdvanced.PROTECT.def);
        return p;
    }

    static String key(FilmSim.Settings settings, int size, Domain domain) {
        if (size < FilmLut.MIN_SIZE || size > FilmLut.MAX_SIZE) throw new IllegalArgumentException("Preview LUT size");
        try {
            ByteBuffer packet = parameters(settings).buffer();
            byte[] bytes = new byte[packet.remaining()]; packet.get(bytes);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder key = new StringBuilder("reference-86476afc-v2:" + domain + ":" + size + ":");
            for (byte value : hash) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public static boolean supported(FilmSim.Settings settings) {
        if (!FilmReferenceParameters.supports(settings)) return false;
        try { return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("film_reference", true); }
        catch (RuntimeException | LinkageError error) { return true; }
    }

    static FilmLut cached(FilmSim.Settings settings, int size, Domain domain) {
        if (!supported(settings)) return null;
        synchronized (LOCK) { return CACHE.get(key(settings, size, domain)); }
    }

    public static void load(Context context, FilmSim.Settings settings, int size, Domain domain,
                            BooleanSupplier current, Consumer<FilmLut> ready) {
        if (!supported(settings) || !current.getAsBoolean()) return;
        final String key = key(settings, size, domain);
        FilmLut hit;
        boolean start = false;
        synchronized (LOCK) {
            hit = CACHE.get(key);
            if (hit == null) {
                if (FAILED.contains(key)) return;
                List<Listener> listeners = LOADING.get(key);
                if (listeners != null) { listeners.add(new Listener(current, ready)); return; }
                listeners = new ArrayList<>(); listeners.add(new Listener(current, ready));
                LOADING.put(key, listeners);
                JOBS.put(key, new Job(context, settings, size, domain));
                if (!scheduled) { scheduled = true; start = true; }
            }
        }
        if (hit != null) { if (current.getAsBoolean()) ready.accept(hit); return; }
        if (start) executor.execute(FilmPreviewLut::drain);
    }

    private static final Map<String, Job> JOBS = new LinkedHashMap<>();
    private static final class Job {
        final Context context;
        final FilmSim.Settings settings;
        final int size;
        final Domain domain;
        Job(Context context, FilmSim.Settings settings, int size, Domain domain) {
            this.context = context; this.settings = settings; this.size = size; this.domain = domain;
        }
    }

    private static void drain() {
        while (true) {
            String key = null;
            Job job = null;
            synchronized (LOCK) {
                java.util.Iterator<Map.Entry<String, Job>> pending = JOBS.entrySet().iterator();
                while (pending.hasNext()) {
                    Map.Entry<String, Job> candidate = pending.next();
                    List<Listener> listeners = LOADING.get(candidate.getKey());
                    boolean wanted = false;
                    if (listeners != null) for (Listener listener : listeners) wanted |= listener.current.getAsBoolean();
                    pending.remove();
                    if (wanted) { key = candidate.getKey(); job = candidate.getValue(); break; }
                    LOADING.remove(candidate.getKey());
                }
                if (job == null) { scheduled = false; return; }
            }
            FilmLut lut = null;
            try { lut = renderer.render(job.context, job.settings, job.size, job.domain); }
            catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
            List<Listener> listeners;
            synchronized (LOCK) {
                listeners = LOADING.remove(key);
                if (lut != null) {
                    CACHE.put(key, lut);
                    while (CACHE.size() > CACHE_SIZE) CACHE.remove(CACHE.keySet().iterator().next());
                } else {
                    FAILED.add(key);
                    if (FAILED.size() > CACHE_SIZE * 4) FAILED.clear();
                }
            }
            if (lut != null && listeners != null)
                for (Listener listener : listeners) if (listener.current.getAsBoolean()) {
                    try { listener.ready.accept(lut); } catch (RuntimeException ignored) { }
                }
        }
    }

    static double grid(int index, int size, Domain domain) {
        return domain == Domain.SCENE
                ? FilmSim.FILM_GREY * Math.pow(2, FilmSim.LOG_LO + FilmSim.LOG_SPAN * index / (double) (size - 1))
                : FilmSim.scene(index / (double) (size - 1));
    }

    static short half(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 65504) throw new IllegalArgumentException("Preview grid range");
        int bits = Float.floatToRawIntBits(value), exponent = (bits >>> 23 & 255) - 112;
        int mantissa = bits & 0x7fffff;
        if (exponent <= 0) {
            if (exponent < -10) return 0;
            mantissa |= 0x800000;
            int shift = 14 - exponent;
            return (short) ((mantissa + (1 << (shift - 1)) - 1 + (mantissa >>> shift & 1)) >>> shift);
        }
        int rounded = mantissa + 0xfff + (mantissa >>> 13 & 1);
        return (short) ((exponent << 10) + (rounded >>> 13));
    }

    static ByteBuffer input(int size, Domain domain) {
        if (size < FilmLut.MIN_SIZE || size > FilmLut.MAX_SIZE) throw new IllegalArgumentException("Preview LUT size");
        ByteBuffer input = ByteBuffer.allocateDirect(size * size * size * 8).order(ByteOrder.LITTLE_ENDIAN);
        short[] values = new short[size];
        for (int i = 0; i < size; i++) values[i] = half((float) grid(i, size, domain));
        for (int b = 0; b < size; b++) for (int g = 0; g < size; g++) for (int r = 0; r < size; r++)
            input.putShort(values[r]).putShort(values[g]).putShort(values[b]).putShort((short) 0x3c00);
        input.flip();
        return input;
    }

    static FilmLut output(ByteBuffer output, int size, String title) {
        ByteBuffer values = output.duplicate().order(ByteOrder.LITTLE_ENDIAN); values.clear();
        if (values.remaining() != size * size * size * 8) throw new IllegalArgumentException("Preview LUT output size");
        float[] rgb = new float[size * size * size * 3];
        for (int i = 0; i < rgb.length; i += 3) {
            for (int c = 0; c < 4; c++) {
                float value = FilmSpectral.half(values.getShort());
                if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite reference film LUT");
                if (c < 3) rgb[i + c] = Math.max(0, Math.min(1, value));
            }
        }
        return FilmLut.of(size, rgb, title);
    }

    private static FilmLut render(Context context, FilmSim.Settings settings, int size, Domain domain) {
        if (!FilmReferenceEngine.available()) return null;
        ByteBuffer input = input(size, domain);
        ByteBuffer output = ByteBuffer.allocateDirect(input.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        String report = FilmReferenceEngine.render(context, input, output, parameters(settings), size * size, size, BUDGET, 0);
        com.particlesdevs.photoncamera.util.Log.i("SCAM_PIPELINE", "reference preview LUT stock=" + settings.stock.id
                + " domain=" + domain + " grid=" + size + " colour-only local-DIR " + report);
        return output(output, size, settings.stock.brand + " " + settings.stock.name);
    }

    static void resetForTests() {
        synchronized (LOCK) { CACHE.clear(); LOADING.clear(); JOBS.clear(); FAILED.clear(); scheduled = false; }
        renderer = FilmPreviewLut::render; executor = LOADER;
    }
}
