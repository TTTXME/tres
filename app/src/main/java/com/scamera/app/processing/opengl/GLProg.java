package com.scamera.app.processing.opengl;

import android.graphics.Point;
import com.scamera.app.util.Log;

import com.scamera.app.app.ScameraApp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.opengl.GLES31.*;
import static com.scamera.app.processing.opengl.GLCoreBlockProcessing.checkEglError;

/**
 * The GL programs of a processing context and the pass in use: programs come from processed shader assets (GLInterface's
 * #import / #define), linked once per context, else from the driver binaries kept in this process and on disk (P30 / P69)
 * or compiled; uniforms, samplers, images and buffers are set on the program in use; fragment passes are drawn in bands.
 */
public class GLProg implements AutoCloseable {
    private static final String TAG = "GLProgram";
    /**
     * P30: driver binaries of the programs linked in this process (every shot creates a new EGL context and compiled its
     * ~40 post-pipeline programs again). A binary from the same driver runs the identical code; one the driver refuses is
     * compiled as before. Up to 96 MB, least recently used first out.
     */
    private static final class Binary { final int format; final byte[] data; Binary(int f, byte[] d) { format = f; data = d; } }
    private static final java.util.LinkedHashMap<String, Binary> BINARIES = new java.util.LinkedHashMap<>(64, 0.75f, true);
    private static long binaryBytes;
    private static final long BINARY_LIMIT = 96L << 20;
    public static long compileMs, binaryMs;
    public static int compiled, fromBinary;
    private static synchronized Binary binaryFor(String key) { return BINARIES.get(key); }
    private static synchronized void storeBinary(String key, Binary b) {
        Binary old = BINARIES.put(key, b);
        if (old != null) binaryBytes -= old.data.length;
        binaryBytes += b.data.length;
        for (java.util.Iterator<java.util.Map.Entry<String, Binary>> it = BINARIES.entrySet().iterator(); binaryBytes > BINARY_LIMIT && it.hasNext();) {
            binaryBytes -= it.next().getValue().data.length;
            it.remove();
        }
    }
    private static synchronized void dropBinary(String key) {
        Binary old = BINARIES.remove(key);
        if (old != null) binaryBytes -= old.data.length;
        GLProgramDiskCache disk = disk();
        if (disk != null && driver != null) disk.drop(driver, key);
    }
    /** P69: the binaries also on disk (GLProgramDiskCache): the first shot after a start loads them instead of compiling. */
    private static GLProgramDiskCache disk;
    private static boolean diskTried;
    /** Renderer, GL version and build fingerprint: part of every disk name (read with a current context). */
    private static String driver;
    private static synchronized GLProgramDiskCache disk() {
        if (!diskTried) {
            diskTried = true;
            try {
                android.content.Context context = ScameraApp.getAppContext();
                if (context != null) disk = new GLProgramDiskCache(programDir(context));
            } catch (RuntimeException ignored) {}
        }
        return disk;
    }
    /**
     * P565: the binaries in the app's no-backup files, not its cache: the nubia (NX721J) lost the whole cache folder between two
     * shots nine minutes apart (build 31244: 51, then 54 programs compiled, 1.3 - 1.4 s of each shot, the idle warm-up found no
     * kept recipe), while the X8U's cache hit 46 of 46. The folder stays bounded (GLProgramDiskCache's limits); an old cache
     * folder is moved over once.
     */
    static File programDir(android.content.Context context) {
        final File dir = new File(context.getNoBackupFilesDir(), "glprog");
        final File old = new File(context.getCacheDir(), "glprog");
        if (!dir.exists() && old.isDirectory() && !old.renameTo(dir)) dir.mkdirs();
        return dir;
    }
    private static synchronized String driver() {
        if (driver == null) {
            String renderer = glGetString(GL_RENDERER), version = glGetString(GL_VERSION);
            glGetError();
            if (renderer == null || version == null) return null;
            driver = renderer + "|" + version + "|" + android.os.Build.FINGERPRINT;
        }
        return driver;
    }
    /** The binary of {@code key}: from this process, else from disk (then kept in this process too). */
    private static Binary loadBinary(String key) {
        Binary b = binaryFor(key);
        if (b != null) return b;
        GLProgramDiskCache disk = disk();
        String id = disk == null ? null : driver();
        Object[] stored = id == null ? null : disk.load(id, key);
        if (stored == null) return null;
        b = new Binary((Integer) stored[0], (byte[]) stored[1]);
        storeBinary(key, b);
        return b;
    }
    /** "programs: N compiled in X ms, M from driver binaries in Y ms" since the last call (logged per shot). */
    public static synchronized String takeProgramStats() {
        GLProgramDiskCache cache = disk;
        String text = "programs: " + compiled + " compiled in " + compileMs + " ms, " + fromBinary + " from driver binaries in " + binaryMs + " ms"
                + (cache == null ? "" : " (" + cache.takeStats() + ")") // P237
                + compiledNames(); // P602
        // P236 (speed-ux F): the shot's shader compiling (the first shot after an install or a driver update: 0.5-0.8 s)
        com.scamera.app.processing.ShotTimeline.markIfShot("post_compile");
        com.scamera.app.processing.ShotTimeline.note("post_compile_ms", compiled + "/" + compileMs + "+" + fromBinary + "bin/" + binaryMs);
        compileMs = binaryMs = 0; compiled = fromBinary = 0;
        COMPILED.clear();
        compiledMore = 0;
        return text;
    }

    // ---- P602 (owner, 2026-11-10, Pixel 7 log of 31249: «runAll programs: 4 compiled in 1041 ms, 0 from driver binaries ... 4 miss /
    // 4 written»): the line named no program. Every other program of that shot was linked already in the post context kept for the
    // process (P353: nothing to load, so 0 from binaries), and the 4 had no binary on disk (miss), were compiled and stored (written):
    // programs this install had never linked - a scene's own defines (sky, sun bloom, a clipped light ...) or a setting changed.
    // Which ones, how long each and in which node is what the next log needs to tell a one-off from a cost of every shot.
    /** P602: the programs compiled since the last {@link #takeProgramStats}: "node:name{defines} ms", at most {@link #COMPILED_NAMED}. */
    private static final java.util.ArrayList<String> COMPILED = new java.util.ArrayList<>();
    private static int compiledMore;
    static final int COMPILED_NAMED = 12;
    /** P602: the post node running on this thread (GLBasePipeline), for the compiled programs' names. */
    private static final ThreadLocal<String> NODE = new ThreadLocal<>();

    /** P602: the node whose programs this thread makes from here on (null: none). */
    public static void compileNode(String node) {
        if (node == null) NODE.remove(); else NODE.set(node);
    }

    /** P602: one compiled program for the shot's line. */
    private static synchronized void noteCompiled(String name, java.util.List<String[]> defines, long ms) {
        if (COMPILED.size() >= COMPILED_NAMED) { compiledMore++; return; }
        COMPILED.add(compiledName(NODE.get(), name, defines) + " " + ms + " ms");
    }

    /**
     * P602: "node:name{A,B=2}" - the asset (or "source") and its defines in their order, a value only when it is not 1; the compute
     * layout left out, long values cut; at most ~160 characters.
     */
    static String compiledName(String node, String name, java.util.List<String[]> defines) {
        final StringBuilder s = new StringBuilder();
        if (node != null && !node.isEmpty()) s.append(node).append(':');
        s.append(name != null ? name : "source");
        if (defines == null) return s.toString();
        final StringBuilder d = new StringBuilder();
        for (String[] define : defines) {
            if (define == null || define.length < 2 || "LAYOUT".equals(define[0])) continue;
            if (d.length() > 0) d.append(',');
            if (s.length() + d.length() > 150) { d.append("..."); break; }
            d.append(define[0]);
            if (!"1".equals(define[1])) d.append('=').append(define[1].length() > 12 ? define[1].substring(0, 12) + "~" : define[1]);
        }
        if (d.length() > 0) s.append('{').append(d).append('}');
        return s.toString();
    }

    /** P602: " compiled: a 412 ms; b 230 ms" (nothing when none was compiled). */
    private static String compiledNames() {
        if (COMPILED.isEmpty()) return "";
        return " compiled: " + String.join("; ", COMPILED) + (compiledMore > 0 ? "; +" + compiledMore + " more" : "");
    }
    /** P237: idle warm-ups run once per process; the programs compiled by the last one. */
    private static final java.util.concurrent.atomic.AtomicBoolean WARMED = new java.util.concurrent.atomic.AtomicBoolean();
    static final int WARM_UP_LIMIT = 64;

    /**
     * P237 (speed-ux B5): compiles, in a context of its own on the calling (background) thread, the post-pipeline programs whose
     * driver binary is missing from the disk cache although their source was kept (a driver update, an evicted or lost binary),
     * so the next shot loads them instead of compiling (0.5-0.8 s on the first shot). Once per process; nothing when every binary
     * is there or nothing was kept yet. {@code idle}: asked before each program - false stops (a shot started). Never throws.
     */
    public static void warmUp(java.util.function.BooleanSupplier idle) {
        warmUp(idle, null);
    }

    /** P470: {@link #warmUp(java.util.function.BooleanSupplier)} reporting (done, total) after each program it looked at. */
    public static void warmUp(java.util.function.BooleanSupplier idle, java.util.function.BiConsumer<Integer, Integer> progress) {
        if (!WARMED.compareAndSet(false, true)) return;
        final GLProgramDiskCache cache = disk();
        if (cache == null) return;
        GLContext context = null;
        int done = 0, failed = 0, wanted = 0, built = 0, recipeKeys = 0;
        final long t0 = android.os.SystemClock.elapsedRealtime();
        warmTid = android.os.Process.myTid(); // P371 (review m5): a shot waiting for one of its compiles raises this thread
        // P662 (item 3): the shared context the first shot takes (GLContext(w, h, true)) is made here: the recipes' programs linked
        // into it from their binaries and the two one-time half checks run here, off the first shot's critical path (32 - 59 binaries
        // in 50 - 93 ms and 15 - 55 ms of checks there). A shot that starts first takes it before (then this one is a context of its
        // own, as before); one that starts meanwhile makes its own (the kept one is held here), as when two pipelines overlap.
        final boolean intoShared = warmUpShared() && !GLContext.keptExists();
        int linkedShared = 0;
        long linkMs = 0, checksMs = 0;
        String checks = "";
        try {
            context = new GLContext(1, 1, intoShared);
            final boolean shared = intoShared && context.holdsKept();
            final String id = driver();
            // P371: first the programs of the recipes kept by earlier runs, made from this APK's assets (the first shot after an
            // install compiled every one of them: 46 in 836 ms on the X8U), then the kept sources this driver lacks (P237)
            final java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            if (recipesOn()) {
                for (String recipe : cache.recipes(RECIPE_WARM_UP_LIMIT)) {
                    final String key = recipeKey(recipe, GLProg::assetSource);
                    if (key != null && keys.add(key)) recipeKeys++;
                }
            }
            keys.addAll(cache.missingSources(id, WARM_UP_LIMIT));
            int looked = 0;
            for (String key : keys) {
                if (progress != null) progress.accept(looked++, keys.size());
                if (id != null && cache.has(id, key)) {
                    // its binary is there (the shot loads it); P662: into the shared context now
                    if (shared && (idle == null || idle.getAsBoolean())) {
                        final long l0 = android.os.SystemClock.elapsedRealtime();
                        if (context.program.linkFromBinary(key)) linkedShared++;
                        linkMs += android.os.SystemClock.elapsedRealtime() - l0;
                    }
                    continue;
                }
                wanted++;
                if (idle != null && !idle.getAsBoolean()) break;
                // P371: a shot that needs this program meanwhile waits for it instead of compiling it a second time
                final java.util.concurrent.CountDownLatch compiling = new java.util.concurrent.CountDownLatch(1);
                if (COMPILING.putIfAbsent(key, compiling) != null) continue;
                try {
                    if (context.program.precompile(key, shared)) {
                        done++;
                        built++;
                        if (shared) linkedShared++; // P662: and kept linked in the shared context
                    } else failed++;
                } catch (RuntimeException e) {
                    failed++;
                } finally {
                    COMPILING.remove(key);
                    compiling.countDown();
                }
            }
            // P662: the post pipeline's one-time checks of this driver (HalfRgbUpload.exact, WhyStats.halfReadbackExact)
            if (shared && (idle == null || idle.getAsBoolean())) {
                final long c0 = android.os.SystemClock.elapsedRealtime();
                checks = com.scamera.app.processing.opengl.postpipeline.PostChecks.warmUp(context.program);
                checksMs = android.os.SystemClock.elapsedRealtime() - c0;
            }
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "program warm-up: " + e);
        } finally {
            warmTid = 0;
            if (context != null) try { context.close(); } catch (RuntimeException ignored) { }
        }
        Log.i(TAG, "program warm-up: " + done + " of " + wanted + " missing binaries compiled in idle time in "
                + (android.os.SystemClock.elapsedRealtime() - t0) + " ms" + (failed > 0 ? ", " + failed + " failed" : "")
                + " (" + recipeKeys + " programs from kept recipes, " + built + " built)"
                + (intoShared ? "; shared context: " + linkedShared + " programs linked from driver binaries in " + linkMs + " ms, checks "
                + (checks.isEmpty() ? "none" : checks) + " in " + checksMs + " ms" : ""));
    }

    /**
     * P662 (item 3): scam_dev gl_warm_up_shared (on by default): the idle warm-up's context is the post pipeline's shared one, the
     * recipes' programs linked into it and the one-time checks run there. 0: a context of its own, closed after, as before.
     */
    static boolean warmUpShared() {
        try {
            return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("gl_warm_up_shared", true);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * P662 (item 3): links {@code key}'s program into this context from its kept driver binary, so that useShader finds it linked
     * (the first shot loads no binaries); true when it is linked now or was before. Its recipe is kept at its first use, as
     * useShader keeps it for a program it links.
     */
    boolean linkFromBinary(String key) {
        final String shader = shaderOfKey(key);
        if (shader == null) return false;
        if (programsBySource.containsKey(shader)) return true;
        final int program = programFromBinary(key);
        if (program == 0) return false;
        locations.remove(program);
        programsBySource.put(shader, program);
        prelinked.add(shader);
        return true;
    }

    /** P662: the processed source of a post-pipeline cache key ({@link #programKey}), null for another key. */
    static String shaderOfKey(String key) {
        if (key == null) return null;
        if (key.startsWith("C\n")) return key.substring(2);
        final String fragmentHead = "F\n" + VERTEX_SOURCE + "\n";
        return key.startsWith(fragmentHead) ? key.substring(fragmentHead.length()) : null;
    }

    /** P662: sources the warm-up linked whose first use has not kept its recipe yet. */
    private final java.util.Set<String> prelinked = new java.util.HashSet<>();

    // ---- P371 (speed): program recipes. The disk cache is keyed by the processed source, so a build that changes any shader
    // or common header finds no binary of its own and P237's kept sources are the old build's: the first shot after every
    // install compiled all its post programs (X8U 46 in 836 ms, X200U 35 in 1072 ms). A recipe is how a program's source was
    // made - the asset (or Java source), the defines in order, compute or not - kept on disk once per process; the idle warm-up
    // makes each recipe's source again from the installed APK through the same GLInterface.loadShader, so the keys are the ones
    // the next shot asks for. scam_dev gl_warm_up_recipes 0: none kept or made (as before).
    static final int RECIPE_WARM_UP_LIMIT = 160;
    private static final char RECIPE_SEP = '\u0001', RECIPE_DEFINE = '\u0002';
    /** Recipes already handed to the disk cache by this process. */
    private static final java.util.Set<String> RECORDED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * P371 (review m5): at most this many recipes a process, of them this many 'S' (a Java-generated source: a per-call define
     * makes a new one each time) - the set stays bounded and those never push the asset programs out of the disk's recipe LRU.
     */
    static final int MAX_RECORDED = 256, MAX_RECORDED_SOURCES = 48;
    private static final java.util.concurrent.atomic.AtomicInteger RECORDED_SOURCES = new java.util.concurrent.atomic.AtomicInteger();
    /** P371: the keys the idle warm-up is compiling right now (a shot waits on the latch, then loads the binary). */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> COMPILING =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** A shot waits at most this long for the warm-up's compile of the same program (then compiles it itself, as before). */
    static final long COMPILING_WAIT_MS = 10000;

    static boolean recipesOn() {
        try {
            return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("gl_warm_up_recipes", true);
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /**
     * P371: the recipe text: "R1", kind ('A' asset shaders/NAME.glsl, 'U' shaders/utils/NAME.glsl, 'S' the source itself),
     * compute, the defines ("-": none given) and the name or source, separated by U+0001 (GLSL and the define values hold no
     * control characters; a define's name and value by U+0002).
     */
    static String recipe(char kind, String id, boolean compute, java.util.List<String[]> defines) {
        final StringBuilder r = new StringBuilder("R1").append(RECIPE_SEP).append(kind).append(RECIPE_SEP).append(compute ? '1' : '0')
                .append(RECIPE_SEP).append(defines == null ? "-" : String.valueOf(defines.size()));
        if (defines != null) for (String[] d : defines) r.append(RECIPE_SEP).append(d[0]).append(RECIPE_DEFINE).append(d[1]);
        return r.append(RECIPE_SEP).append(id).toString();
    }

    /** P371: {kind, id, compute ("1"/"0")} of a recipe and its defines (null: none given) in out[0], or null when damaged. */
    static String[] parseRecipe(String recipe, java.util.List<java.util.ArrayList<String[]>> out) {
        if (recipe == null || !recipe.startsWith("R1" + RECIPE_SEP)) return null;
        final String[] head = recipe.split(String.valueOf(RECIPE_SEP), 5);
        if (head.length < 5 || head[1].length() != 1 || !("0".equals(head[2]) || "1".equals(head[2]))) return null;
        String rest = head[4];
        java.util.ArrayList<String[]> defines = null;
        if (!"-".equals(head[3])) {
            final int count;
            try { count = Integer.parseInt(head[3]); } catch (NumberFormatException e) { return null; }
            if (count < 0 || count > 256) return null;
            defines = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int end = rest.indexOf(RECIPE_SEP);
                if (end < 0) return null;
                final String d = rest.substring(0, end);
                final int split = d.indexOf(RECIPE_DEFINE);
                if (split < 0) return null;
                defines.add(new String[]{d.substring(0, split), d.substring(split + 1)});
                rest = rest.substring(end + 1);
            }
        }
        if (out != null) out.add(defines);
        return new String[]{head[1], rest, head[2]};
    }

    /** The cache key of a processed source (useShader's: "C\n" + compute source, "F\n" + the vertex shader + "\n" + fragment). */
    static String programKey(String shader, boolean compute) {
        return (compute ? "C\n" : "F\n" + VERTEX_SOURCE + "\n") + shader;
    }

    /** P371: the cache key a recipe gives with the installed assets ({@code assets}: path -> text, null when missing), or null. */
    static String recipeKey(String recipe, java.util.function.Function<String, String> assets) {
        final java.util.List<java.util.ArrayList<String[]>> defines = new java.util.ArrayList<>(1);
        final String[] r = parseRecipe(recipe, defines);
        if (r == null) return null;
        final String source;
        switch (r[0]) {
            case "A": source = assets.apply("shaders/" + r[1] + ".glsl"); break;
            case "U": source = assets.apply("shaders/utils/" + r[1] + ".glsl"); break;
            case "S": source = r[1]; break;
            default: return null;
        }
        if (source == null || source.isEmpty()) return null;
        try {
            final java.util.ArrayList<String[]> d = defines.get(0);
            return programKey(d == null ? GLInterface.loadShader(source) : GLInterface.loadShader(source, d), "1".equals(r[2]));
        } catch (RuntimeException e) {
            return null; // an asset this APK no longer has, an #import gone
        }
    }

    private static String assetSource(String path) {
        try {
            final String text = ScameraApp.getAssetLoader().getString(path);
            return text == null || text.isEmpty() ? null : text;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** P371: the recipe of a program this process has just made, kept once (the disk cache's writer thread). */
    private static void keepRecipe(String recipe) {
        if (!recordable(recipe)) return;
        final GLProgramDiskCache cache = disk();
        if (cache != null) cache.keepRecipe(recipe);
    }

    /** P371 (review m5): true for a recipe to keep now (new to this process, within MAX_RECORDED / MAX_RECORDED_SOURCES). */
    static boolean recordable(String recipe) {
        if (recipe == null || RECORDED.contains(recipe) || RECORDED.size() >= MAX_RECORDED) return false;
        final String[] r = parseRecipe(recipe, null);
        if (r != null && "S".equals(r[0]) && RECORDED_SOURCES.incrementAndGet() > MAX_RECORDED_SOURCES) return false;
        return RECORDED.add(recipe);
    }

    /** P371 (review m5): the idle warm-up's thread while it runs (0: none), and the shots waiting for one of its compiles. */
    private static volatile int warmTid;
    private static final java.util.concurrent.atomic.AtomicInteger WAITERS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * P371: waits while the idle warm-up compiles {@code key}; true when it did (its binary is kept now). Review m5: the warm-up
     * runs at THREAD_PRIORITY_BACKGROUND - while a shot waits for it, it runs at the default priority (no inversion behind the
     * viewfinder and the camera thread), back to the background once the last waiter is done.
     */
    static boolean awaitWarmUp(String key) {
        final java.util.concurrent.CountDownLatch compiling = COMPILING.get(key);
        if (compiling == null) return false;
        final int tid = warmTid;
        if (tid > 0 && WAITERS.getAndIncrement() == 0) priority(tid, android.os.Process.THREAD_PRIORITY_DEFAULT);
        try {
            return compiling.await(COMPILING_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (tid > 0 && WAITERS.decrementAndGet() == 0 && tid == warmTid) priority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND);
        }
    }

    private static void priority(int tid, int priority) {
        try { android.os.Process.setThreadPriority(tid, priority); } catch (RuntimeException ignored) { }
    }

    /**
     * P237: compiles and links one program from its cache key ("C\n" + compute source, or "F\n" + the vertex shader + "\n" +
     * fragment source; P312: or a viewfinder key, {@link #viewfinderKey}) so its binary is kept (keepBinary); true when it linked.
     */
    boolean precompile(String key) {
        return precompile(key, false);
    }

    /** P662: {@link #precompile(String)}; {@code keep}: a post-pipeline program stays linked in this context (linkFromBinary). */
    boolean precompile(String key, boolean keep) {
        if (key.startsWith("V\n")) {
            int program = cachedViewfinderProgram(key);
            if (program == 0) program = linkViewfinderProgram(key);
            if (program != 0) glDeleteProgram(program);
            return program != 0;
        }
        final boolean compute = key.startsWith("C\n");
        final String fragmentHead = "F\n" + vertexShaderSource + "\n";
        if (!compute && !key.startsWith(fragmentHead)) return false;
        final String shader = compute ? key.substring(2) : key.substring(fragmentHead.length());
        final int stored = programFromBinary(key);
        if (stored != 0) {
            if (keep && !programsBySource.containsKey(shader)) {
                locations.remove(stored);
                programsBySource.put(shader, stored);
                prelinked.add(shader);
            } else glDeleteProgram(stored);
            return true;
        }
        final int shaderHandle = compileShader(compute ? GL_COMPUTE_SHADER : GL_FRAGMENT_SHADER, shader);
        final int program;
        if (compute) {
            program = glCreateProgram();
            glAttachShader(program, shaderHandle);
            glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
            glLinkProgram(program);
        } else program = createProgram(vertexShader, shaderHandle);
        final int[] linked = new int[1];
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0);
        if (linked[0] != 0) keepBinary(key, program);
        if (keep && linked[0] != 0 && !programsBySource.containsKey(shader)) {
            // P662: the program stays this context's, as useShader would have linked it
            locations.remove(program);
            programsBySource.put(shader, program);
            prelinked.add(shader);
        } else if (compute) glDeleteProgram(program); // a fragment program is the GLProg's (createProgram), deleted with it
        glDeleteShader(shaderHandle);
        glGetError();
        return linked[0] != 0;
    }

    private static int programFromBinary(String key) {
        Binary b = loadBinary(key);
        if (b == null) return 0;
        // P312: errors left by the context's earlier work (the viewfinder's frame) are not this binary's: it was dropped for them
        for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) { }
        int program = glCreateProgram();
        glProgramBinary(program, b.format, java.nio.ByteBuffer.wrap(b.data), b.data.length);
        int[] ok = new int[1];
        glGetProgramiv(program, GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0 || glGetError() != GL_NO_ERROR) { glDeleteProgram(program); dropBinary(key); return 0; }
        return program;
    }

    // ---- P312: the viewfinder's programs in the same cache
    // (owner, 2026-10-21, a video of the camera's start: the viewfinder stood still for 0.4 s - 2.1 s just after it appeared, every
    // start. The developed-RAW viewfinder compiled its programs from source on its GL thread at the first RAW frame - rawdevelop
    // (650 lines) and the ten programs of the photo's WHY tone (combine: 815 lines) - while no ISP frame could be drawn. Their
    // driver binaries are kept here like the post pipeline's: the next start links them in a few ms.)

    /**
     * P312: the cache key of a viewfinder program: "V\n" + its attribute names (bound to locations 0, 1, ... before the link,
     * comma separated) + "\n" + the vertex source + U+0001 + the fragment source (GLSL has no control characters).
     */
    public static String viewfinderKey(String vertex, String fragment, String... attributes) {
        return "V\n" + String.join(",", attributes) + "\n" + vertex + '\u0001' + fragment;
    }

    /** P312: {attribute names (comma separated), vertex source, fragment source} of a viewfinder key, or null. */
    static String[] parseViewfinderKey(String key) {
        if (key == null || !key.startsWith("V\n")) return null;
        final int a = key.indexOf('\n', 2);
        final int s = a < 0 ? -1 : key.indexOf('\u0001', a + 1);
        if (s < 0) return null;
        return new String[]{key.substring(2, a), key.substring(a + 1, s), key.substring(s + 1)};
    }

    /**
     * P312: the linked program of a viewfinder key from its kept driver binary (this process, else the disk), 0 when there is
     * none or the driver refuses it (then dropped). On the thread of a current context.
     */
    public static int cachedViewfinderProgram(String key) {
        return programFromBinary(key);
    }

    /** P312: whether the binary of a key is kept (this process, or on the disk for this driver: then loaded into the process). */
    public static boolean hasBinary(String key) {
        return loadBinary(key) != null;
    }

    /**
     * P312: compiles and links a viewfinder key (its attributes bound to locations 0, 1, ...; the binary retrievable) and keeps its
     * binary; 0 when it does not compile or link (logged). On the thread of a current context.
     */
    public static int linkViewfinderProgram(String key) {
        final String[] parts = parseViewfinderKey(key);
        if (parts == null) return 0;
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final int vs = compileQuiet(GL_VERTEX_SHADER, parts[1]);
        final int fs = vs == 0 ? 0 : compileQuiet(GL_FRAGMENT_SHADER, parts[2]);
        int program = 0;
        if (fs != 0) {
            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            final String[] attributes = parts[0].isEmpty() ? new String[0] : parts[0].split(",");
            for (int i = 0; i < attributes.length; i++) glBindAttribLocation(program, i, attributes[i]);
            glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
            glLinkProgram(program);
            final int[] linked = new int[1];
            glGetProgramiv(program, GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                Log.e(TAG, "viewfinder program link: " + glGetProgramInfoLog(program));
                glDeleteProgram(program);
                program = 0;
            }
        }
        if (vs != 0) glDeleteShader(vs);
        if (fs != 0) glDeleteShader(fs);
        if (program != 0) keepBinary(key, program);
        glGetError();
        Log.d(TAG, "viewfinder program " + (program != 0 ? "compiled" : "failed") + " in " + (android.os.SystemClock.elapsedRealtime() - t0)
                + " ms (" + parts[2].length() + " chars) on " + Thread.currentThread().getName());
        return program;
    }

    /** P312: {@link #cachedViewfinderProgram}, else {@link #linkViewfinderProgram}. */
    public static int viewfinderProgram(String key) {
        final int program = cachedViewfinderProgram(key);
        return program != 0 ? program : linkViewfinderProgram(key);
    }

    private static int compileQuiet(int type, String source) {
        final int shader = glCreateShader(type);
        if (shader == 0) return 0;
        glShaderSource(shader, source);
        glCompileShader(shader);
        final int[] ok = new int[1];
        glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != 0) return shader;
        Log.e(TAG, "viewfinder shader compile: " + glGetShaderInfoLog(shader));
        glDeleteShader(shader);
        return 0;
    }
    private static void keepBinary(String key, int program) {
        int[] length = new int[1];
        glGetProgramiv(program, GL_PROGRAM_BINARY_LENGTH, length, 0);
        if (length[0] <= 0) { glGetError(); return; }
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(length[0]);
        int[] written = new int[1], format = new int[1];
        glGetProgramBinary(program, length[0], written, 0, format, 0, data);
        if (glGetError() != GL_NO_ERROR || written[0] <= 0) return;
        byte[] bytes = new byte[written[0]];
        data.position(0); data.get(bytes);
        storeBinary(key, new Binary(format[0], bytes));
        GLProgramDiskCache disk = disk();
        String id = disk == null ? null : driver();
        if (id != null) disk.store(id, key, format[0], bytes);
    }
    // ---- the program in use, its uniforms and its draws -------------------------------------------------------------------

    public final static String glVersion = "#version 310 es\n";
    /** The vertex stage of every fragment pass (part of the cache key of each program: never change its text lightly). */
    static final String VERTEX_SOURCE = glVersion + "precision mediump float;\nin vec4 vPosition;\nvoid main() {\ngl_Position = vPosition;\n}\n";
    final String vertexShaderSource = VERTEX_SOURCE;
    private final int vertexShader;
    private final GLSquareModel quad = new GLSquareModel();
    /** One pixel per tile is read back here by the flushing draws (drawBlocks with a flush type). */
    private final ByteBuffer flushPixels = ByteBuffer.allocateDirect(4 * 4 * 4096);
    /** Linked programs by their processed source. */
    private final Map<String, Integer> programsBySource = new HashMap<>();
    /**
     * P659: uniform and attribute locations by program (fixed once a program is linked): setVar and every tile's draw asked the
     * driver by name each time (~600 setVar call sites, a draw per 256 rows of every pass). Dropped when a name is linked anew.
     */
    private final Map<Integer, Map<String, Integer>> locations = new HashMap<>();
    private final Map<String, Integer> textureUnits = new HashMap<>();
    private Map<String, GLComputeLayout> mComputeLayouts = null;
    private int nextUnit;
    /** The program in use (its GL name), whether it is a compute one, and the shader name of a compute program. */
    public int activeProgram;
    public boolean computeBound = false;
    public int computeShader;
    /** The pass is done with the program (its textures may be reused); a new program in use clears it. */
    public boolean closed = true;
    /** #define overrides for the next program (cleared once it is in use), and whether there are new ones. */
    ArrayList<String[]> pendingDefines = new ArrayList<>();
    boolean definesChanged = false;

    public GLProg() {
        vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
        flushPixels.mark();
    }

    // -- defines: "NAME value" overrides of the next program's #define lines (GLInterface.readProgram) --
    public void setDefine(String name, String value) {
        pendingDefines.add(new String[]{name, value});
        definesChanged = true;
    }
    public void setDefine(String name, Point value) {
        setDefine(name, value.x, value.y);
    }
    public void setDefine(String name, boolean on) {
        setDefine(name, on ? "1" : "0");
    }
    public void setDefine(String name, float... values) {
        setDefine(name, true, values);
    }
    /** {@code transposed} is not used: the values as a comma list ("1.0, 2.5"). */
    public void setDefine(String name, boolean transposed, float... values) {
        final StringBuilder list = new StringBuilder();
        for (int i = 0; i < values.length; i++) list.append(i > 0 ? ", " : "").append(values[i]);
        setDefine(name, list.toString());
    }
    public void setDefine(String name, int... values) {
        final StringBuilder list = new StringBuilder();
        for (int i = 0; i < values.length; i++) list.append(i > 0 ? ", " : "").append(values[i]);
        setDefine(name, list.toString());
    }
    /** The work group size of the next compute program (its LAYOUT define). */
    public void setLayout(int x, int y, int z) {
        setDefine("LAYOUT", "layout(local_size_x = " + x + ", local_size_y = " + y + ", local_size_z = " + z + ") in;");
    }

    // -- programs --
    public void useAssetProgram(String name) {
        useAssetProgram(name, false);
    }

    public void useAssetProgram(String name, boolean compute) {
        if (PostGlMode.legacy()) {
            useProgramSource(ScameraApp.getAssetLoader().getString("shaders/" + name + ".glsl"), compute, 'A', name);
            return;
        }
        // W1.11: the asset text, its #import / #define processing and the compute layouts are the same for the same name and
        // defines in every pass and every shot; they were read from the APK and parsed again for each of the ~84 passes
        final String key = assetKey(name);
        Prepared prepared = PREPARED.get(key);
        if (prepared == null) {
            final String source = ScameraApp.getAssetLoader().getString("shaders/" + name + ".glsl");
            final String shader = definesChanged ? GLInterface.loadShader(source, pendingDefines) : GLInterface.loadShader(source);
            prepared = new Prepared(shader, GLInterface.getLayouts(shader));
            if (PREPARED.size() >= PREPARED_LIMIT) PREPARED.clear();
            PREPARED.put(key, prepared);
        }
        computeBound = compute;
        closed = false;
        useShader(prepared.shader, prepared.layouts, compute, 'A', name);
    }

    /** A processed asset program (W1.11): the source handed to the driver and its layouts. */
    private static final class Prepared {
        final String shader;
        final Map<String, GLComputeLayout> layouts;
        Prepared(String shader, Map<String, GLComputeLayout> layouts) { this.shader = shader; this.layouts = layouts; }
    }
    private static final java.util.concurrent.ConcurrentHashMap<String, Prepared> PREPARED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int PREPARED_LIMIT = 512;

    /** Asset name plus the pending defines, in the order readProgram applies them. */
    private String assetKey(String name) {
        if (!definesChanged || pendingDefines.isEmpty()) return name;
        final StringBuilder key = new StringBuilder(name);
        for (String[] d : pendingDefines) key.append('|').append(d[0]).append('=').append(d[1]);
        return key.toString();
    }

    public void useUtilProgram(String name) {
        useUtilProgram(name, false);
    }

    public void useUtilProgram(String name, boolean compute) {
        useProgramSource(ScameraApp.getAssetLoader().getString("shaders/utils/" + name + ".glsl"), compute, 'U', name);
    }

    /** P371: {@code recipeKind} / {@code recipeId}: how the source was made (see {@link #recipe}; 0: not recorded). */
    private void useProgramSource(String source, boolean compute, char recipeKind, String recipeId) {
        computeBound = compute;
        closed = false;
        final String shader = definesChanged ? GLInterface.loadShader(source, pendingDefines) : GLInterface.loadShader(source);
        useShader(shader, GLInterface.getLayouts(shader), compute, recipeKind, recipeId);
    }

    private void forgetDefines() {
        pendingDefines.clear();
        definesChanged = false;
    }

    /**
     * Makes {@code shader} the program in use: linked before in this context, else from a kept driver binary (P30 / P69, or
     * the idle warm-up's, P371), else compiled and linked (its binary kept when it links).
     */
    private void useShader(String shader, Map<String, GLComputeLayout> layouts, boolean compute, char recipeKind, String recipeId) {
        mComputeLayouts = layouts;
        if (programsBySource.containsKey(shader)) {
            // P662: a program the idle warm-up linked keeps its recipe at its first use (the defines as they are now)
            if (prelinked.remove(shader) && recipeKind != 0 && recipeId != null && recipesOn())
                keepRecipe(recipe(recipeKind, recipeId, compute, definesChanged ? pendingDefines : null));
            forgetDefines();
            final Integer linked = programsBySource.get(shader);
            if (linked == null) return;
            glUseProgram(linked);
            checkEglError("glUseProgram");
            activeProgram = linked;
        } else {
            final String key = programKey(shader, compute);
            final long t0 = android.os.SystemClock.elapsedRealtime();
            int program = programFromBinary(key);
            // P371: the idle warm-up compiling this very program: wait for it, then load its binary (no second compile)
            if (program == 0 && awaitWarmUp(key)) program = programFromBinary(key);
            int stage = 0;
            if (program != 0) {
                synchronized (GLProg.class) { fromBinary++; binaryMs += android.os.SystemClock.elapsedRealtime() - t0; }
            } else {
                stage = compileShader(compute ? GL_COMPUTE_SHADER : GL_FRAGMENT_SHADER, shader);
                program = compute ? linkCompute(stage) : createProgram(vertexShader, stage);
                final int[] status = new int[1];
                glGetProgramiv(program, GL_LINK_STATUS, status, 0);
                if (status[0] != 0) keepBinary(key, program);
                final long ms = android.os.SystemClock.elapsedRealtime() - t0;
                synchronized (GLProg.class) { compiled++; compileMs += ms; }
                noteCompiled(recipeId, definesChanged ? pendingDefines : null, ms); // P602
            }
            computeShader = stage;
            locations.remove(program); // a name the driver handed out again
            glGetError();
            glUseProgram(program);
            checkEglError("glUseProgram");
            // P371: how this source was made, for the next install's idle warm-up (the defines as they are now)
            if (recipeKind != 0 && recipeId != null && recipesOn()) keepRecipe(recipe(recipeKind, recipeId, compute, definesChanged ? pendingDefines : null));
            forgetDefines();
            activeProgram = program;
            programsBySource.put(shader, program);
        }
        textureUnits.clear();
        nextUnit = 0;
        GLGpuTimer.mark(recipeId != null ? recipeId : "source"); // P479: post_sync only (profiling)
    }

    /** A compute program of {@code computeStage}, linked with its binary retrievable (its link status is the caller's). */
    private static int linkCompute(int computeStage) {
        final int program = glCreateProgram();
        glAttachShader(program, computeStage);
        glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
        glLinkProgram(program);
        return program;
    }

    /** A compiled shader stage of {@code source}; throws with the source when it does not compile (the log has the reason). */
    public int compileShader(final int type, final String source) {
        int stage = glCreateShader(type);
        if (stage != 0) {
            glShaderSource(stage, source);
            glCompileShader(stage);
            final int[] status = new int[1];
            glGetShaderiv(stage, GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Error compiling shader: " + glGetShaderInfoLog(stage));
                glDeleteShader(stage);
                stage = 0;
            }
        }
        if (stage == 0) throw new RuntimeException("Error creating shader. Program:" + source);
        return stage;
    }

    /** The program of two compiled stages, its binary retrievable (P30: kept for the next shot's context). */
    public int createProgram(final int vertexStage, final int fragmentStage) {
        int program = glCreateProgram();
        if (program != 0) {
            glAttachShader(program, vertexStage);
            glAttachShader(program, fragmentStage);
            glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
            glLinkProgram(program);
            final int[] status = new int[1];
            glGetProgramiv(program, GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Error compiling program: " + glGetProgramInfoLog(program));
                glDeleteProgram(program);
                program = 0;
            }
        }
        if (program == 0) throw new RuntimeException("Error creating program.");
        return program;
    }

    /** The location of {@code name} in the program in use (P659: asked once per program). */
    private int location(String name, boolean attribute) {
        Map<String, Integer> known = locations.get(activeProgram);
        if (known == null) {
            known = new HashMap<>();
            locations.put(activeProgram, known);
        }
        final String key = attribute ? "\u0000" + name : name;
        Integer at = known.get(key);
        if (at == null) {
            at = attribute ? glGetAttribLocation(activeProgram, name) : glGetUniformLocation(activeProgram, name);
            known.put(key, at);
        }
        return at;
    }

    // -- compute dispatch --
    private boolean requireCompute() {
        if (computeBound) return true;
        Log.e(TAG, "dispatch of a program that is not compute", new IllegalStateException("Program must be compute!"));
        return false;
    }

    /** Every write of the dispatch visible to whatever reads next (GL_ALL_SHADER_BITS: every barrier bit). */
    private static void computeBarriers() {
        glMemoryBarrier(GL_ALL_SHADER_BITS);
    }

    /** Enough work groups of the program's layout to cover {@code size} x {@code z}; waits for them (GpuPace.finish). */
    public void computeAuto(Point size, int z) {
        if (!requireCompute()) return;
        final GLComputeLayout group = mComputeLayouts.get("in");
        if (group == null) {
            Log.e(TAG, "no work group layout", new IllegalStateException("glComputeLayout is null"));
            return;
        }
        glDispatchCompute(groups(size.x, group.groupXY.x), groups(size.y, group.groupXY.y), groups(z, group.groupZ));
        computeBarriers();
        GpuPace.finish(); // P616: a polled fence on the paced post thread (glFinish elsewhere)
    }

    private static int groups(int extent, int groupSize) {
        return extent / groupSize + (extent % groupSize != 0 ? 1 : 0);
    }

    /** P479: {@link #computeManual} without the wait (the same barriers), for a pass whose result is read back later. */
    public void computeManualAsync(int x, int y, int z) {
        if (!requireCompute()) return;
        glDispatchCompute(x, y, z);
        computeBarriers();
    }

    public void computeManual(int x, int y, int z) {
        if (!requireCompute()) return;
        glDispatchCompute(x, y, z);
        computeBarriers();
        GpuPace.finish(); // P616
    }

    // -- fragment draws --
    /** One full-viewport quad of the program in use. */
    public void draw() {
        quad.draw(location("vPosition", true));
        if (PostGlMode.legacy()) {
            // the old model: two barriers and a flush after every tile (a fragment pass writes only its render target, which
            // the GL orders with every later read of that texture)
            computeBarriers();
            glFlush();
        }
    }

    private static final int[] COLOUR0 = {GL_COLOR_ATTACHMENT0};

    public void drawBlocks(GLTexture target, Point size) {
        target.renderInto();
        drawBlocks(size.x, size.y);
    }

    public void drawBlocks(GLTexture target) {
        target.renderInto();
        // P479: every pixel of the target is drawn below (no post shader discards): its old contents need not be loaded
        if (PostGlMode.invalidate()) glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, COLOUR0, 0);
        drawBlocks(target.size.x, target.size.y);
        // W1.1: no glFinish after every pass: read-backs are the sync points (post_sync 1: a glFinish at each node end)
        if (PostGlMode.legacy()) glFinish();
    }

    /**
     * The program over w x h in bands of TILE_ROWS rows. P497 (owner, 2026-11-04: «видоискатель тормозит ... на всех
     * смартфонах»): a submission per band - the GPU lets the viewfinder's context in between submissions only (Xiaomi 12X:
     * viewfinder draws of 274 - 771 ms while one pass was one submission); P616: at most a few bands queued (GpuPace).
     */
    public void drawBlocks(int w, int h) {
        final GLBlockDivider bands = new GLBlockDivider(h, GLDrawParams.TILE_ROWS);
        final int[] band = new int[2];
        while (bands.nextBlock(band)) {
            glViewport(0, band[0], w, band[1]);
            draw();
            if (!PostGlMode.legacy()) {
                glFlush();
                GpuPace.submitted();
            }
        }
    }

    public void drawBlocks(GLTexture target, int bandRows) {
        drawBlocks(target, bandRows, false);
    }

    public void drawBlocks(GLTexture target, boolean forceFlush) {
        if (!forceFlush) {
            drawBlocks(target);
            return;
        }
        target.renderInto();
        drawBlocks(target.size.x, target.size.y, GLDrawParams.TILE_ROWS, -1, target.format.getGLType());
    }

    public void drawBlocks(GLTexture target, int bandRows, boolean forceFlush) {
        target.renderInto();
        drawBlocks(target.size.x, target.size.y, bandRows, -1, forceFlush ? target.format.mFormat.ordinal() : -1);
    }

    /**
     * Bands of {@code bandRows}, each flushed; with a {@code flushType} one pixel of each band is read back as well (a sync
     * point per band). {@code flushFormat} -1: RGBA for GL_FLOAT, RGBA_INTEGER otherwise.
     */
    public void drawBlocks(int w, int h, int bandRows, int flushFormat, int flushType) {
        flushPixels.reset();
        final int format = flushFormat != -1 ? flushFormat : flushType == GL_FLOAT ? GL_RGBA : GL_RGBA_INTEGER;
        final GLBlockDivider bands = new GLBlockDivider(h, bandRows);
        final int[] band = new int[2];
        while (bands.nextBlock(band)) {
            glViewport(0, band[0], w, band[1]);
            quad.draw(location("vPosition", true));
            glFlush();
            GpuPace.submitted(); // P616
            if (flushType == -1) continue;
            glReadPixels(0, band[0], 1, 1, format, flushType, flushPixels);
            final int error = glGetError();
            if (error != 0) Log.d("GLPrograms", "GLError: " + error);
        }
    }

    // -- inputs --
    /** The texture unit of a sampler of this program: every sampler its own, two units apart, for the program's lifetime in use. */
    private int unitOf(String sampler) {
        Integer unit = textureUnits.get(sampler);
        if (unit == null) {
            unit = nextUnit;
            textureUnits.put(sampler, unit);
            nextUnit += 2;
        }
        return unit;
    }

    public void setTexture(String sampler, GLTexture texture) {
        if (texture == null) {
            Log.e(TAG, "no texture for " + sampler, new IllegalArgumentException("Wrong Texture:" + sampler));
            return;
        }
        final int unit = unitOf(sampler);
        setVar(sampler, unit);
        texture.bind(GL_TEXTURE0 + unit);
    }

    /** P134: a 3D texture (sampler3D) by its GL name, on a unit of its own like {@link #setTexture}. */
    public void setTexture3D(String sampler, int textureName) {
        final int unit = unitOf(sampler);
        setVar(sampler, unit);
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_3D, textureName);
    }

    public void setBufferCompute(String block, GLBuffer buffer) {
        setBufferCompute(block, buffer, GL_SHADER_STORAGE_BUFFER);
    }

    /**
     * Binds {@code buffer} at the binding the program declares for {@code block}. P317: a block the program does not declare
     * throws (it read and wrote nothing, silently: the Mali AE histograms 0 / 0); the callers log it and fall back to the CPU.
     */
    public void setBufferCompute(String block, GLBuffer buffer, int target) {
        buffer.BindBase(binding("buffer block", block), target);
    }

    private int binding(String what, String name) {
        final GLComputeLayout layout = mComputeLayouts == null ? null : mComputeLayouts.get(name);
        if (layout != null) return layout.bindingIndex;
        final String message = "no " + what + " " + name + " in the program's layouts " + (mComputeLayouts == null ? "(none)" : mComputeLayouts.keySet());
        Log.e(TAG, message);
        throw new IllegalStateException(message);
    }

    public void setTextureCompute(String image, GLTexture texture, boolean write) {
        setTextureCompute(image, texture, write ? GL_WRITE_ONLY : GL_READ_ONLY);
    }

    /** Binds level 0 of {@code texture} as the program's image {@code image} (P317: an undeclared one throws). */
    public void setTextureCompute(String image, GLTexture texture, int access) {
        glBindImageTexture(binding("image", image), texture.id, 0, false, 0, access, texture.format.getGLFormatInternal());
        checkEglError("glBindImageTexture tex.mTextureID:" + texture.id);
    }

    // -- uniforms --
    private static RuntimeException badSize(String name) {
        return new RuntimeException("Wrong var size " + name);
    }

    /** int / ivecN uniforms (1 .. 4 values), or an int[8] array. */
    public void setVar(String name, int... v) {
        final int at = location(name, false);
        switch (v.length) {
            case 1: glUniform1i(at, v[0]); break;
            case 2: glUniform2i(at, v[0], v[1]); break;
            case 3: glUniform3i(at, v[0], v[1], v[2]); break;
            case 4: glUniform4i(at, v[0], v[1], v[2], v[3]); break;
            case 8: glUniform1iv(at, 8, v, 0); break; // P659: it threw "Wrong var size" right after setting the array
            default: throw badSize(name);
        }
        checkEglError("setVar:" + name);
    }

    public void setVar(String name, Point v) {
        setVar(name, v.x, v.y);
    }

    /** float / vecN uniforms (1 .. 4 values), or a mat3 of 9 ({@code transpose}: row-major values). */
    public void setVar(String name, boolean transpose, float... v) {
        final int at = location(name, false);
        switch (v.length) {
            case 1: glUniform1f(at, v[0]); break;
            case 2: glUniform2f(at, v[0], v[1]); break;
            case 3: glUniform3f(at, v[0], v[1], v[2]); break;
            case 4: glUniform4f(at, v[0], v[1], v[2], v[3]); break;
            case 9: glUniformMatrix3fv(at, 1, transpose, v, 0); break;
            default: throw badSize(name);
        }
        checkEglError("setVar:" + name);
    }

    public void setVar(String name, float... v) {
        setVar(name, true, v);
    }

    /** A float array uniform (uniform float name[len]). */
    public void setVarFloats(String name, float... v) {
        glUniform1fv(location(name, false), v.length, v, 0);
        checkEglError("setVarFloats:" + name);
    }

    /** A vec4 array uniform (uniform vec4 name[len]) from 4 * len floats (P144: the own tone curve's table). */
    public void setVarVec4s(String name, float... v) {
        glUniform4fv(location(name, false), v.length / 4, v, 0);
        checkEglError("setVarVec4s:" + name);
    }

    public void setVarU(String name, Point v) {
        setVarU(name, v.x, v.y);
    }

    /** uint / uvecN uniforms (1 .. 4 values). */
    public void setVarU(String name, int... v) {
        final int at = location(name, false);
        switch (v.length) {
            case 1: glUniform1ui(at, v[0]); break;
            case 2: glUniform2ui(at, v[0], v[1]); break;
            case 3: glUniform3ui(at, v[0], v[1], v[2]); break;
            case 4: glUniform4ui(at, v[0], v[1], v[2], v[3]); break;
            default: throw badSize(name);
        }
        checkEglError("setVarU:" + name);
    }

    /** Ends the use of the current program; the linked programs stay for the context (P353: the next shot's). */
    @Override
    public void close() {
        closed = true;
    }
}
