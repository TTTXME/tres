package com.scamera.app.util;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.Nullable;

/**
 * P680 (performance audit: the ZSL ring was 50 frames of 24 MB on every phone, 1.6 GB of memfd on a 6 GB phone): the phone's
 * memory class, read once per process (ActivityManager's totalMem and isLowRamDevice do not change while the process runs). A
 * phone of {@link #LOW_TOTAL_BYTES} or less, or one the system calls a low-RAM device, is «low»: the ZSL ring the user did not
 * size gets fewer frames and a smaller share of the memory there (ZslRing). The first-run dialog stores it once
 * (DeviceMemoryProfile, {@code device_profile_mem_*}); without that profile it is asked of the ActivityManager.
 */
public final class RamClass {
    /** At most this much memory in all (an «8 GB» phone reports a little less): the low class. */
    public static final long LOW_TOTAL_BYTES = 8L << 30;

    private static volatile RamClass cached;

    /** ActivityManager.MemoryInfo.totalMem (0: unknown). */
    public final long totalBytes;
    /** ActivityManager.isLowRamDevice(). */
    public final boolean lowRamDevice;

    RamClass(long totalBytes, boolean lowRamDevice) {
        this.totalBytes = totalBytes;
        this.lowRamDevice = lowRamDevice;
    }

    /** The low class: a low-RAM device, or a known total of at most {@link #LOW_TOTAL_BYTES}. */
    public boolean low() {
        return low(totalBytes, lowRamDevice);
    }

    public static boolean low(long totalBytes, boolean lowRamDevice) {
        return lowRamDevice || (totalBytes > 0 && totalBytes <= LOW_TOTAL_BYTES);
    }

    /** This phone's class (read on the first call); an unknown one when there is no context. */
    public static RamClass get(@Nullable Context context) {
        RamClass c = cached;
        if (c != null) return c;
        c = read(context);
        if (c.totalBytes > 0) cached = c; // an unknown answer is asked again
        return c;
    }

    /** {@link #get} with the application's context. */
    public static RamClass get() {
        Context context = null;
        try {
            context = com.scamera.app.app.ScameraApp.getAppContext();
        } catch (RuntimeException | LinkageError ignored) {
        }
        return get(context);
    }

    private static RamClass read(@Nullable Context context) {
        // the profile the first-run dialog stored (DeviceMemoryProfile), else the ActivityManager now
        final long[] stored = DeviceMemoryProfile.storedMemory(context);
        if (stored != null) return new RamClass(stored[0], stored[1] != 0);
        try {
            final ActivityManager am = context == null ? null : (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return new RamClass(0, false);
            final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return new RamClass(info.totalMem, am.isLowRamDevice());
        } catch (RuntimeException e) {
            return new RamClass(0, false);
        }
    }

    /** Tests: the next {@link #get} reads the memory again. */
    public static void forgetForTest() {
        cached = null;
    }

    @Override public String toString() {
        return (low() ? "low" : "normal") + " (total " + (totalBytes >> 20) + " MB" + (lowRamDevice ? ", low-RAM device" : "") + ")";
    }
}
