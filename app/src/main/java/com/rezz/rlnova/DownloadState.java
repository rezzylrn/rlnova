package com.rezz.rlnova;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State unduhan per modelId, dibagikan antara service dan UI lewat map statis
 * (satu proses). version naik tiap perubahan supaya UI tau kapan re-render.
 */
public final class DownloadState {

    public static final int IDLE = 0, DOWNLOADING = 1, PAUSED = 2, DONE = 3, FAILED = 4;

    public int status = IDLE;
    public long downloaded, total;
    public String speed;
    public String error;
    /** Average bytes/s over the last few progress updates (for the ETA text). */
    public volatile long recentSpeed;

    private static final Map<String, DownloadState> MAP = new ConcurrentHashMap<>();
    private static volatile long uiVersion = 0;

    public static DownloadState get(String id) {
        DownloadState s = MAP.get(id);
        if (s == null) { s = new DownloadState(); MAP.put(id, s); }
        return s;
    }

    /** All tracked states (for the service notification, key = modelId). */
    public static Map<String, DownloadState> all() { return MAP; }

    public static void remove(String id) { MAP.remove(id); }

    /** Clear all states (used by "Delete all models" in Settings). */
    public static void clearAll() { MAP.clear(); bumpVersion(); }

    public static void bumpVersion() { uiVersion++; }

    public static long uiVersion() { return uiVersion; }

    public static int progressPercent(DownloadState st) {
        return st.total > 0 ? (int) (st.downloaded * 100 / st.total) : 0;
    }

    /** Estimated time remaining, e.g. "2m 40s left", or null when unknown. */
    public static String etaText(DownloadState st) {
        if (st.status != DOWNLOADING || st.recentSpeed <= 0 || st.total <= st.downloaded) return null;
        long secs = (st.total - st.downloaded) / st.recentSpeed;
        if (secs < 5) return null;
        if (secs < 90) return "~" + secs + "s left";
        long min = (secs + 59) / 60;
        return "~" + min + " min left";
    }

    public static String formatBytes(long b) {
        double gb = b / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) return String.format(java.util.Locale.US, "%.1f GB", gb);
        double mb = b / (1024.0 * 1024.0);
        return String.format(java.util.Locale.US, "%.0f MB", mb);
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
    }
}
