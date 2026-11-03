package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/** Opt-in process logcat recording, with one bounded asynchronous file writer. */
public final class ScameraFileLog {
    private static final String TAG = "SCAMERA-file-log";
    private static final ThreadLocal<SimpleDateFormat> DAY = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));
    private static volatile Context context;
    private static volatile boolean enabled, collectorFailed;
    private static volatile long generation;
    private static volatile Process process;
    private static volatile File current;
    private static volatile android.net.Uri currentUri;
    private static BatchedLogWriter writer;
    private static final class Barrier {
        final Runnable action, rejected;
        Barrier(Runnable action, Runnable rejected) { this.action = action; this.rejected = rejected; }
    }
    private static final java.util.Map<Long, Barrier> barriers = new java.util.LinkedHashMap<>();
    private static long nextBarrier;
    // The following state belongs only to the writer thread.
    private static RotatingLogFile file;
    private static DocumentLogFile document;
    private static String day;

    private ScameraFileLog() {}

    static void attach(Context value) {
        if (value != null) context = value.getApplicationContext();
    }

    static File directory() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SCAMERA/Logs");
    }

    private static synchronized BatchedLogWriter writer() {
        if (writer == null) writer = new BatchedLogWriter(new BatchedLogWriter.Sink() {
            @Override public void write(String text) throws IOException {
                open();
                if (document != null) {
                    try {
                        document.write(text);
                        currentUri = document.uri();
                        current = new File(directory(), document.name());
                        return;
                    } catch (IOException e) {
                        document.close(); document = null; day = null;
                        throw e;
                    }
                }
                if (file == null || !file.write(text)) throw new IOException("Log storage unavailable");
                current = file.file();
                currentUri = null;
            }
            @Override public void flush() throws IOException {
                try {
                    if (file != null) file.flush();
                    if (document != null) document.flush();
                } finally {
                    if (!enabled) {
                        if (file != null) file.close();
                        if (document != null) document.close();
                        file = null; document = null; day = null;
                    }
                }
            }
        });
        return writer;
    }

    static synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        final long token = ++generation;
        Process old = process;
        process = null;
        if (old != null) old.destroy();
        if (!value) {
            if (writer != null) writer.flushSoon();
            return;
        }
        collectorFailed = false;
        writer().append(sessionHeader(new Date()));
        final String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        Thread collector = new Thread(() -> collect(token, since), "SCAMERA-logcat-collector");
        collector.setDaemon(true);
        collector.setPriority(Thread.MIN_PRIORITY + 1);
        collector.start();
    }

    static boolean isEnabled() { return enabled; }

    static void append(String text) {
        if (enabled) writer().append(text);
    }

    /** Java logs remain available if this firmware forbids reading the app's logcat. */
    static void fallback(String level, String tag, String message) {
        if (enabled && collectorFailed) append(level + "/" + tag + ": " + message + '\n');
    }

    private static boolean active(long token) { return enabled && generation == token; }

    private static String clientFilter() {
        return clientFilter(android.os.Build.VERSION.SDK_INT, android.os.Process.myUid(), android.os.Process.myPid());
    }

    static String clientFilter(int sdk, int uid, int pid) { return sdk >= 31 ? "--uid=" + uid : "--pid=" + pid; }

    static String[] command(String since) {
        return new String[]{"logcat", "-b", "main", "-b", "system", "-b", "crash", "-v", "threadtime",
                clientFilter(), "-T", since};
    }

    private static void collect(long token, String since) {
        Process child = null;
        try {
            child = new ProcessBuilder("logcat", "-d", "-b", "crash", "-v", "threadtime",
                    clientFilter()).redirectErrorStream(true).start();
            synchronized (ScameraFileLog.class) {
                if (!active(token)) { child.destroy(); return; }
                process = child;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int bytes = 0;
                while (active(token) && (line = reader.readLine()) != null && bytes < 256 * 1024) {
                    if (!active(token)) break;
                    append("previous crash buffer: " + line + '\n');
                    bytes += line.length();
                }
            }
            child.destroy();
            child = new ProcessBuilder(command(since)).redirectErrorStream(true).start();
            synchronized (ScameraFileLog.class) {
                if (!active(token)) { child.destroy(); return; }
                process = child;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (active(token) && (line = reader.readLine()) != null) {
                    if (!active(token)) break;
                    if (line.contains(TAG)) {
                        int at = line.indexOf("FLUSH ");
                        if (at >= 0) {
                            try { finishBarrier(Long.parseLong(line.substring(at + 6).trim()), false); }
                            catch (NumberFormatException ignored) { }
                        }
                    } else append(line + '\n');
                }
            }
            if (active(token)) {
                collectorFailed = true;
                append("[logcat collector stopped; Java/worker log fallback active]\n");
            }
        } catch (IOException | RuntimeException e) {
            if (active(token)) {
                collectorFailed = true;
                append("[logcat unavailable; Java/worker log fallback active: " + e + "]\n");
            }
        } finally {
            if (child != null) child.destroy();
            synchronized (ScameraFileLog.class) { if (process == child) process = null; }
        }
    }

    private static RotatingLogFile at(File folder, String stem) {
        return new RotatingLogFile(stem, ".txt", RotatingLogFile.CAP_BYTES, RotatingLogFile.GENERATIONS,
                () -> folder, () -> RotatingLogFile.installStem(context, stem),
                (f, rotated) -> "--- " + BuildIdentity.line() + "; " + f + (rotated ? "; continued" : "") + " ---\n");
    }

    private static void open() {
        String today = DAY.get().format(new Date());
        if ((file != null || document != null) && today.equals(day)) return;
        if (file != null) file.close();
        if (document != null) document.close();
        document = null;
        file = null;
        day = today;
        String stem = "SCAMERA_Log_" + today;
        if (android.os.Build.VERSION.SDK_INT >= 29 && context != null) {
            DocumentLogFile publicLog = new DocumentLogFile(context, stem);
            try {
                publicLog.open();
                publicLog.removeExpired(System.currentTimeMillis());
                publicLog.write("--- " + BuildIdentity.line() + "; " + DocumentLogFile.RELATIVE + publicLog.name() + " ---\n");
                document = publicLog;
                currentUri = publicLog.uri();
                current = new File(directory(), publicLog.name());
                return;
            } catch (IOException e) { publicLog.close(); }
        }
        File folder = directory();
        folder.mkdirs();
        removeExpired(folder, System.currentTimeMillis());
        file = at(folder, stem);
        if (!file.ensureOpen() && context != null) {
            File privateDir = context.getExternalFilesDir("logs");
            if (privateDir == null) privateDir = new File(context.getFilesDir(), "logs");
            privateDir.mkdirs();
            file = at(privateDir, stem);
            file.ensureOpen();
        }
        if (file.file() != null) { current = file.file(); currentUri = null; }
    }

    static void removeExpired(File folder, long now) {
        File[] logs = folder.listFiles();
        if (logs == null) return;
        long age = 10L * 24 * 60 * 60 * 1000;
        for (File log : logs) {
            if (log.getName().matches("SCAMERA_Log_[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[.][12])?[.]txt")
                    && log.isFile() && log.lastModified() > 0 && now - log.lastModified() > age) log.delete();
        }
    }

    public static String getPath() {
        File path = current;
        return (path == null ? new File(directory(), "SCAMERA_Log_" + DAY.get().format(new Date()) + ".txt") : path).getAbsolutePath();
    }

    static void flush() { if (writer != null) writer.afterFlush(() -> {}); }

    static void crash(String report) {
        if (!enabled) return;
        writer().append(report);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        if (writer().afterFlush(done::countDown)) {
            try { done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static File existing() {
        if (current != null) return current;
        String name = "SCAMERA_Log_" + DAY.get().format(new Date()) + ".txt";
        if (android.os.Build.VERSION.SDK_INT >= 29 && context != null) {
            DocumentLogFile publicLog = new DocumentLogFile(context, name.substring(0, name.length() - 4));
            if (publicLog.find()) {
                currentUri = publicLog.uri();
                return current = new File(directory(), publicLog.name());
            }
        }
        File candidate = new File(directory(), name);
        if (candidate.isFile()) return current = candidate;
        Context app = context;
        if (app != null) {
            File folder = app.getExternalFilesDir("logs");
            if (folder != null && (candidate = new File(folder, name)).isFile()) return current = candidate;
            candidate = new File(new File(app.getFilesDir(), "logs"), name);
            if (candidate.isFile()) return current = candidate;
        }
        return null;
    }

    private static void finishBarrier(long id, boolean timeout) {
        Barrier barrier;
        synchronized (ScameraFileLog.class) { barrier = barriers.remove(id); }
        if (barrier == null) return;
        if (timeout) append("[logcat flush timed out: snapshot may omit trailing logcat records]\n");
        if (!writer().afterFlush(barrier.action)) new Handler(Looper.getMainLooper()).post(barrier.rejected);
    }

    private static boolean ordered(Runnable action, Runnable rejected) {
        long id;
        synchronized (ScameraFileLog.class) {
            if (!enabled || collectorFailed) return writer().afterFlush(action);
            if (barriers.size() >= 32) return false;
            id = ++nextBarrier;
            barriers.put(id, new Barrier(action, rejected));
        }
        android.util.Log.i(TAG, "FLUSH " + id);
        new Handler(Looper.getMainLooper()).postDelayed(() -> finishBarrier(id, true), 1000);
        return true;
    }

    static void afterFlush(Runnable action) {
        Runnable rejected = () -> android.util.Log.w(TAG, "Log control queue full");
        if (!ordered(action, rejected)) rejected.run();
    }

    public static void size(Consumer<Long> result) {
        afterFlush(() -> {
            File path = existing();
            long bytes = path == null ? 0 : path.length();
            if (currentUri != null && context != null) {
                try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(currentUri, "r")) {
                    if (fd != null) bytes = Math.max(0, fd.getStatSize());
                } catch (IOException | RuntimeException ignored) { }
            }
            final long actualBytes = bytes;
            new Handler(Looper.getMainLooper()).post(() -> result.accept(actualBytes));
        });
    }

    public static void clear(Consumer<Boolean> result) {
        boolean queued = ordered(() -> {
            boolean ok = true;
            if (file != null) file.close();
            if (document != null) document.close();
            File path = existing();
            if (path != null) {
                try (java.io.OutputStream ignored = currentUri != null && context != null
                        ? context.getContentResolver().openOutputStream(currentUri, "wt") : new FileOutputStream(path, false)) {
                    if (ignored == null) throw new IOException("Cannot clear log");
                }
                catch (IOException | RuntimeException e) { ok = false; }
            }
            file = null;
            document = null;
            day = null;
            final boolean success = ok;
            new Handler(Looper.getMainLooper()).post(() -> result.accept(success));
        }, () -> result.accept(false));
        if (!queued) result.accept(false);
    }

    public static void share(Context activity, Consumer<Boolean> result) {
        boolean queued = ordered(() -> {
            try {
                File source = existing();
                if (source == null) throw new IOException("Empty log");
                File snapshot = new File(activity.getCacheDir(), "SCAMERA-current-log.txt");
                try (java.io.InputStream input = currentUri != null
                        ? activity.getContentResolver().openInputStream(currentUri) : new java.io.FileInputStream(source)) {
                    if (input == null) throw new IOException("Cannot read log");
                    Files.copy(input, snapshot.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                if (snapshot.length() == 0) throw new IOException("Empty log");
                android.net.Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", snapshot);
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(android.content.ClipData.newRawUri("SCAMERA log", uri));
                new Handler(Looper.getMainLooper()).post(() -> {
                    try { activity.startActivity(Intent.createChooser(send, "SCAMERA log")); result.accept(true); }
                    catch (RuntimeException e) { result.accept(false); }
                });
            } catch (IOException | RuntimeException e) {
                new Handler(Looper.getMainLooper()).post(() -> result.accept(false));
            }
        }, () -> result.accept(false));
        if (!queued) result.accept(false);
    }

    /** A session's first lines: its start, the build (P214b: every session names its {@link BuildIdentity}), the device. */
    static String sessionHeader(Date now) {
        return "\n=== SCAMERA log session " + now + " ===\n" + BuildIdentity.line()
                + "\nDevice: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "; Android " + android.os.Build.VERSION.RELEASE + "; UID " + android.os.Process.myUid() + "\n";
    }
}
