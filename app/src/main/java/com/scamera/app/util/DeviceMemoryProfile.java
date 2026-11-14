package com.scamera.app.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * P680 (owner, 2026-11-14: «в начальную компиляцию встроить то что поможет ускорить и оптимизировать работу приложения»): the
 * phone's memory and storage, measured once by the first-run dialog (ui/camera/FirstRunCompile) and kept in the preferences
 * under {@code device_profile_mem_*}: the total memory, the low-RAM flag, the large heap class, and the app cache's write speed
 * (a timed write of at most {@link #TEST_BYTES}, deleted after). {@link RamClass} reads the memory from here; the storage speed
 * is for the processing (a DNG written off the shot's path on a slow phone).
 */
public final class DeviceMemoryProfile {
    static final String PREFS = "scam_device_profile";
    static final String KEY_TOTAL = "device_profile_mem_total", KEY_LOW_RAM = "device_profile_mem_low_ram",
            KEY_LARGE_CLASS = "device_profile_mem_large_class_mb", KEY_WRITE = "device_profile_mem_storage_write_mbps",
            KEY_STAMP = "device_profile_mem_stamp";
    /** The storage test's file size (at most; it stops at {@link #TEST_MAX_MS}). */
    static final int TEST_BYTES = 32 << 20;
    static final long TEST_MAX_MS = 700;

    /** The total memory and the low-RAM flag a profile holds; null without one. */
    @Nullable
    static long[] storedMemory(@Nullable Context context) {
        try {
            if (context == null) return null;
            final SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final long total = p.getLong(KEY_TOTAL, 0);
            if (total <= 0) return null;
            return new long[]{total, p.getBoolean(KEY_LOW_RAM, false) ? 1 : 0};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The cache's write speed measured (MB/s), 0 when not measured yet. */
    public static float storageWriteMbps(@Nullable Context context) {
        try {
            return context == null ? 0f : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_WRITE, 0f);
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    /**
     * Measures and stores the profile for {@code stamp} (the install / system build, FirstRunCompile.stamp) unless it is stored
     * for it already; returns the logged line, or null when nothing was measured. Off the main thread (the storage write).
     */
    @Nullable
    public static String recordOnce(Context context, String stamp) {
        final SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (stamp.equals(p.getString(KEY_STAMP, null))) return null;
        final long start = System.nanoTime();
        long total = 0;
        boolean lowRam = false;
        int largeClass = 0;
        try {
            final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                total = info.totalMem;
                lowRam = am.isLowRamDevice();
                largeClass = am.getLargeMemoryClass();
            }
        } catch (RuntimeException ignored) {
        }
        final float mbps = writeSpeed(context.getCacheDir());
        p.edit().putLong(KEY_TOTAL, total).putBoolean(KEY_LOW_RAM, lowRam).putInt(KEY_LARGE_CLASS, largeClass)
                .putFloat(KEY_WRITE, mbps).putString(KEY_STAMP, stamp).apply();
        RamClass.forgetForTest(); // the next read takes the stored profile
        final String line = describe(total, lowRam, largeClass, mbps) + " in " + (System.nanoTime() - start) / 1_000_000 + " ms";
        Log.i("PERF", line);
        return line;
    }

    static String describe(long total, boolean lowRam, int largeClassMb, float writeMbps) {
        return "device profile: memory " + (total >> 20) + " MB (" + (RamClass.low(total, lowRam) ? "low" : "normal") + " class"
                + (lowRam ? ", low-RAM device" : "") + "), large heap " + largeClassMb + " MB, cache write "
                + (writeMbps > 0 ? String.format(java.util.Locale.US, "%.0f MB/s", writeMbps) : "not measured");
    }

    /** MB/s of a write of up to {@link #TEST_BYTES} into {@code dir} (synced, then deleted); 0 when it failed. */
    static float writeSpeed(@Nullable File dir) {
        if (dir == null) return 0f;
        final File file = new File(dir, "device_profile_write.tmp");
        final byte[] chunk = new byte[1 << 20];
        for (int i = 0; i < chunk.length; i += 4096) chunk[i] = (byte) (i >> 12); // not all zeros (a compressing file system)
        long written = 0;
        final long start = System.nanoTime();
        try (FileOutputStream out = new FileOutputStream(file)) {
            while (written < TEST_BYTES && (System.nanoTime() - start) / 1_000_000 < TEST_MAX_MS) {
                out.write(chunk);
                written += chunk.length;
            }
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            written = 0;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        final double seconds = (System.nanoTime() - start) / 1e9;
        return written <= 0 || seconds <= 0 ? 0f : (float) (written / 1048576.0 / seconds);
    }
}
