package com.rezz.rlnova;

import android.app.ActivityManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

/**
 * Deteksi spesifikasi perangkat dan klasifikasi tier.
 * Murni AOSP API, tanpa dependency.
 *
 * Tier (SPEC.md):
 *   LOW  : total RAM <= 4 GB
 *   MID  : total RAM 6-8 GB
 *   HIGH : total RAM >= 12 GB
 */
public final class DeviceInfo {

    public enum Tier { LOW, MID, HIGH }

    public final long totalRamBytes;
    public final int cpuCores;
    public final String chipset;
    public final long freeStorageBytes;
    public final boolean hasVulkan;

    public DeviceInfo(long totalRamBytes, int cpuCores, String chipset,
                      long freeStorageBytes, boolean hasVulkan) {
        this.totalRamBytes = totalRamBytes;
        this.cpuCores = cpuCores;
        this.chipset = chipset;
        this.freeStorageBytes = freeStorageBytes;
        this.hasVulkan = hasVulkan;
    }

    /** Bangun dari perangkat nyata. */
    public static DeviceInfo fromDevice(ActivityManager am, boolean hasVulkan) {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long ram = mi.totalMem;
        int cores = Runtime.getRuntime().availableProcessors();
        String chipset = Build.BOARD + " / " + Build.HARDWARE;
        if (Build.VERSION.SDK_INT >= 31) chipset = Build.SOC_MODEL;
        long free = 0;
        try {
            StatFs stat = new StatFs(android.os.Environment.getDataDirectory().getPath());
            free = stat.getAvailableBytes();
        } catch (Exception ignored) { }
        return new DeviceInfo(ram, cores, chipset, free, hasVulkan);
    }

    /** Klasifikasi tier murni dari angka RAM total (dalam GB). */
    public static Tier classify(long totalRamBytes) {
        double gb = totalRamBytes / (1024.0 * 1024.0 * 1024.0);
        if (gb <= 4.5) return Tier.LOW;   // HP 4GB (dan semua di bawahnya)
        if (gb <= 8.5) return Tier.MID;   // 6 GB, 8 GB (an 8 GB device reports ~7.4 GB)
        return Tier.HIGH;                 // 12 GB ke atas
    }

    public Tier tier() {
        return classify(totalRamBytes);
    }

    public String tierLabel() {
        switch (tier()) {
            case LOW: return "LOW";
            case MID: return "MID";
            default: return "HIGH";
        }
    }

    public int recommendedThreads() {
        return Math.max(1, cpuCores - 2);
    }

    // ---------- format helper ----------

    public static String formatBytes(long b) {
        if (b < 0) return "-";
        double gb = b / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) {
            return String.format(java.util.Locale.US, "%.1f GB", gb);
        }
        double mb = b / (1024.0 * 1024.0);
        return String.format(java.util.Locale.US, "%.0f MB", mb);
    }
}
