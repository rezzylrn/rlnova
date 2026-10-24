package com.rezz.rlnova;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Preferensi performa (panel Optimasi, mockup 05) + history benchmark.
 * Tanpa dependency: SharedPreferences "perf" + CSV app-private perf_history.csv.
 *
 * Format baris CSV: timestamp;threads;gpuLayers;ctx;prefillTps;decodeTps
 * (header "timestamp;..." ditulis sekali).
 */
public final class PerfPrefs {

    public static final class Perf {
        public int threads;
        public int gpuLayers;
        public int nCtx;
        public boolean useGpu;
        public boolean battery;

        /** Threads efektif: mode hemat baterai = threads/2. */
        public int effectiveThreads(int cores) {
            int t = battery ? Math.max(1, threads / 2) : threads;
            if (cores > 0 && t > cores) t = cores;
            return Math.max(1, t);
        }

        /** GPU efektif: hemat baterai / switch GPU OFF / Mali → 0. */
        public int effectiveGpuLayers() {
            return (battery || !useGpu) ? 0 : Math.max(0, gpuLayers);
        }
    }

    /** Satu baris history benchmark. */
    public static final class BenchRecord {
        public long timestamp;
        public int threads, gpuLayers, ctx;
        public float prefillTps, decodeTps;

        public String summary() {
            return threads + " threads · GPU " + gpuLayers + " layers · ctx " + ctx;
        }
    }

    private static final String PREFS = "perf";

    public static Perf load(Context ctx) {
        return load(ctx, null);
    }

    /** Load prefs; nilai yang belum diset = default per tier DeviceInfo. */
    public static Perf load(Context ctx, DeviceInfo dev) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (dev == null) dev = currentDevice(ctx);
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        Perf p = new Perf();
        boolean mali = isMaliGpu();
        boolean hasSaved = sp.contains("threads");

        p.threads = sp.getInt("threads",
                dev != null ? dev.recommendedThreads() : Math.max(1, cores - 2));
        p.threads = clamp(p.threads, 1, cores);
        p.nCtx = sp.getInt("nCtx", dev != null ? defaultCtx(dev.tier()) : 2048);
        // v1.1.5: GPU default OFF — Vulkan offload eksperimental, offload hanya
        // aktif kalau user menyalakan switch di panel Optimasi.
        p.useGpu = sp.getBoolean("useGpu", false);
        int defLayers = mali ? 0 : (p.useGpu && dev != null ? defaultGpuLayers(dev) : 0);
        p.gpuLayers = clamp(sp.getInt("gpuLayers", defLayers), 0, 99);
        p.battery = sp.getBoolean("battery", false);
        return p;
    }

    private static volatile long saveEpoch = 0;

    /** Epoch config: naik tiap kali pengaturan performa disimpan (sinyal reload model). */
    public static long saveEpochValue() { return saveEpoch; }

    public static void save(Context ctx, Perf p) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("threads", p.threads)
                .putInt("gpuLayers", p.gpuLayers)
                .putInt("nCtx", p.nCtx)
                .putBoolean("useGpu", p.useGpu)
                .putBoolean("battery", p.battery)
                .apply();
        saveEpoch++; // ChatRoomFragment paksa reload model saat epoch berubah
    }

    /** Auto-tune: set threads + gpuLayers + ctx dari benchmark tercepat tercatat. */
    public static void applyBench(Context ctx, BenchRecord r) {
        if (r == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("threads", r.threads)
                .putInt("gpuLayers", r.gpuLayers)
                .putBoolean("useGpu", r.gpuLayers > 0)
                .putInt("nCtx", r.ctx)
                .apply();
    }

    // ------------------------------------------------------ benchmark history

    private static File historyFile(Context ctx) {
        return new File(ctx.getFilesDir(), "perf_history.csv");
    }

    public static void appendBench(Context ctx, BenchRecord r) {
        try {
            File f = historyFile(ctx);
            boolean fresh = !f.exists();
            FileWriter w = new FileWriter(f, true);
            if (fresh) w.write("timestamp;threads;gpuLayers;ctx;prefillTps;decodeTps\n");
            w.write(r.timestamp + ";" + r.threads + ";" + r.gpuLayers + ";" + r.ctx
                    + ";" + r.prefillTps + ";" + r.decodeTps + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    /** Hapus seluruh history benchmark (dipakai "Hapus cache" juga? tidak — hanya tombol khusus). */
    public static void clearHistory(Context ctx) {
        try { historyFile(ctx).delete(); } catch (Exception ignored) { }
    }

    /** N benchmark terakhir, urut waktu ascending (bar chart). */
    public static List<BenchRecord> lastBench(Context ctx, int count) {
        List<BenchRecord> all = readAll(ctx);
        int start = Math.max(0, all.size() - count);
        return new ArrayList<>(all.subList(start, all.size()));
    }

    /** Config with the highest decode tok/s. Null if never benchmarked. */
    public static BenchRecord fastestBench(Context ctx) {
        BenchRecord best = null;
        for (BenchRecord r : readAll(ctx)) {
            if (best == null || r.decodeTps > best.decodeTps) best = r;
        }
        return best;
    }

    private static List<BenchRecord> readAll(Context ctx) {
        List<BenchRecord> out = new ArrayList<>();
        try {
            File f = historyFile(ctx);
            if (!f.exists()) return out;
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("timestamp")) continue;
                String[] p = line.split(";");
                if (p.length < 6) continue;
                BenchRecord r = new BenchRecord();
                try {
                    r.timestamp   = Long.parseLong(p[0]);
                    r.threads     = Integer.parseInt(p[1]);
                    r.gpuLayers   = Integer.parseInt(p[2]);
                    r.ctx         = Integer.parseInt(p[3]);
                    r.prefillTps  = Float.parseFloat(p[4]);
                    r.decodeTps   = Float.parseFloat(p[5]);
                } catch (NumberFormatException e) { continue; }
                out.add(r);
            }
            br.close();
        } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------ device helpers

    public static DeviceInfo currentDevice(Context ctx) {
        android.app.ActivityManager am =
                (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        boolean vk = hasVulkan(ctx);
        return DeviceInfo.fromDevice(am, vk);
    }

    /** Vulkan tersedia? (PackageManager feature) */
    public static boolean hasVulkan(Context ctx) {
        try {
            return ctx.getPackageManager().hasSystemFeature("android.hardware.vulkan.level");
        } catch (Exception e) {
            return false;
        }
    }

    /** GPU Mali / Exynos terdeteksi → Vulkan dikenal tidak stabil (default OFF). */
    public static boolean isMaliGpu() {
        try {
            String hw  = android.os.Build.HARDWARE;
            String brd = android.os.Build.BOARD;
            String chip = android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : "";
            String all = ((hw == null ? "" : hw) + " " + (brd == null ? "" : brd)
                    + " " + (chip == null ? "" : chip)).toLowerCase(Locale.US);
            return all.contains("mali") || all.contains("exynos");
        } catch (Exception e) {
            return false;
        }
    }

    public static int defaultCtx(DeviceInfo.Tier tier) {
        if (tier == DeviceInfo.Tier.HIGH) return 8192;
        if (tier == DeviceInfo.Tier.MID) return 4096;
        return 2048;
    }

    private static int defaultGpuLayers(DeviceInfo dev) {
        if (dev == null) return 0;
        switch (dev.tier()) {
            case HIGH: return 99;
            case MID:  return 16;
            default:   return 8;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
