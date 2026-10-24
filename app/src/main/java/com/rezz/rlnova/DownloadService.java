package com.rezz.rlnova;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Download manager: foreground service + notification, HTTP Range resume,
 * pause/resume/cancel, final size verification. Pure AOSP HttpURLConnection.
 *
 * Control model: one worker thread per modelId. PAUSE closes the connection
 * and keeps the remainder in a "<id>.part" file; RESUME starts a new worker
 * that continues from .part via the Range header (server rejects Range ->
 * restart from 0). UI reads state via DownloadState (static map, one process).
 *
 * Survives app close (v1.1.0):
 *  - START_STICKY: after the process is killed and restarted with a null
 *    intent, a still-tracked download is re-entered into the foreground.
 *  - Ongoing notification with progress, Pause/Resume + Cancel actions and a
 *    content intent that reopens the Downloads screen.
 *  - foregroundServiceType="dataSync" (Android 14+ requirement) in manifest.
 *  - POST_NOTIFICATIONS runtime permission is requested by the UI before the
 *    first download (LibraryActivity.ensureNotificationPermission()).
 *  - Battery optimization: prompted once per install via
 *    requestIgnoreBatteryOptimization() (Doze can kill long downloads).
 */
public class DownloadService extends Service {

    public static final String CHANNEL_ID = "rlnova_downloads";
    public static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.rezz.rlnova.action.START";
    public static final String ACTION_PAUSE = "com.rezz.rlnova.action.PAUSE";
    public static final String ACTION_RESUME = "com.rezz.rlnova.action.RESUME";
    public static final String ACTION_CANCEL = "com.rezz.rlnova.action.CANCEL";

    public static final String EXTRA_ID = "modelId";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_SIZE = "sizeBytes";

    /** One-shot flag so the battery dialog is only prompted once per install. */
    private static volatile boolean batteryPromptShown = false;

    private final Map<String, Thread> workers = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();

    /** URL last used per id, for RESUME without carrying it from the UI. */
    private final Map<String, String> lastUrlMap = new ConcurrentHashMap<>();
    private String lastUrl(String id) { return lastUrlMap.get(id); }

    /**
     * Ask the system to ignore battery optimizations for this app (Doze can
     * otherwise suspend a multi-GB download on battery). Prompted at most
     * once per install; a system dialog explains why in one sentence.
     */
    public static void requestIgnoreBatteryOptimization(android.app.Activity activity) {
        if (batteryPromptShown) return;
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(activity.getPackageName())) return;
            batteryPromptShown = true;
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {
            // Not critical: downloads still work, Doze may just be more aggressive.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Model Downloads",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("AI model download progress");
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            // START_STICKY restart with a null intent: re-enter foreground if a
            // download is still tracked, otherwise stop for good.
            boolean anyDownloading = false;
            for (Thread t : workers.values()) if (t != null && t.isAlive()) anyDownloading = true;
            if (anyDownloading) {
                String first = workers.keySet().iterator().next();
                ensureForeground(names.containsKey(first) ? names.get(first) : "model");
            } else {
                stopIfIdle();
            }
            return START_STICKY;
        }
        String id = intent.getStringExtra(EXTRA_ID);
        if (id == null) {
            stopIfIdle();
            return START_STICKY;
        }
        switch (intent.getAction()) {
            case ACTION_START: {
                String url = intent.getStringExtra(EXTRA_URL);
                String name = intent.getStringExtra(EXTRA_NAME);
                long size = intent.getLongExtra(EXTRA_SIZE, -1);
                if (url != null) startDownload(id, url, name, size);
                break;
            }
            case ACTION_PAUSE: {
                Thread t = workers.get(id);
                DownloadState st = DownloadState.get(id);
                if (t != null) t.interrupt(); // worker responds with PAUSED status
                else st.status = DownloadState.PAUSED;
                notifyUiChanged();
                stopIfIdle();
                break;
            }
            case ACTION_RESUME: {
                if (workers.containsKey(id)) break; // still running
                DownloadState st = DownloadState.get(id);
                st.status = DownloadState.DOWNLOADING;
                st.error = null;
                String name = names.containsKey(id) ? names.get(id) : id;
                ensureForeground(name); // required: UI starts via startForegroundService()
                Worker w = new Worker(id, lastUrl(id));
                Thread nt = new Thread(w, "dl-" + id);
                workers.put(id, nt);
                nt.start();
                break;
            }
            case ACTION_CANCEL: {
                Thread t = workers.get(id);
                if (t != null) t.interrupt();
                DownloadState.remove(id);
                lastUrlMap.remove(id);
                File part = new File(new File(getFilesDir(), "models"), id + ".part");
                if (!part.delete()) part.deleteOnExit();
                notifyUiChanged();
                stopIfIdle();
                break;
            }
        }
        return START_STICKY;
    }

    private void startDownload(String id, String url, String name, long expectedSize) {
        if (workers.containsKey(id)) return; // already running
        if (name != null) names.put(id, name);
        lastUrlMap.put(id, url);
        ensureForeground(name != null ? name : id);
        DownloadState st = DownloadState.get(id);
        st.status = DownloadState.DOWNLOADING;
        st.error = null;
        if (expectedSize > 0) st.total = expectedSize;
        Worker w = new Worker(id, url);
        Thread t = new Thread(w, "dl-" + id);
        workers.put(id, t);
        t.start();
    }

    private class Worker implements Runnable {
        final String id, url;
        volatile boolean cancelledFlag = false;
        Worker(String id, String url) { this.id = id; this.url = url; }

        @Override
        public void run() {
            DownloadState st = DownloadState.get(id);
            File dir = new File(getFilesDir(), "models");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, id + ".part");
            try {
                long startFrom = out.exists() ? out.length() : 0;
                HttpURLConnection conn = open(startFrom);
                int code = conn.getResponseCode();
                if (startFrom > 0 && code != 206) {
                    // Server does not support Range: start from zero.
                    startFrom = 0;
                    out.delete();
                    conn.disconnect();
                    conn = open(0);
                    code = conn.getResponseCode();
                }
                if (code < 200 || code >= 300) { fail(st, "HTTP " + code, id); return; }
                long contentLen = conn.getContentLengthLong();
                if (contentLen > 0) st.total = contentLen + startFrom;

                InputStream in = conn.getInputStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out, startFrom > 0);
                byte[] buf = new byte[64 * 1024];
                long downloaded = startFrom;
                st.downloaded = downloaded;
                long winBytes = 0, lastTick = System.currentTimeMillis();
                int lastPercent = -1;
                long read;
                while ((read = in.read(buf)) != -1) {
                    if (cancelledFlag || Thread.currentThread().isInterrupted()) {
                        // PAUSE: connection closed, .part is kept for resume.
                        try { in.close(); } catch (Exception ignored) { }
                        conn.disconnect();
                        st.status = DownloadState.PAUSED;
                        notifyUiChanged();
                        workers.remove(id);
                        updateNotification(st, id); // freeze bar + "paused" text
                        stopIfIdle();
                        return;
                    }
                    fos.write(buf, 0, (int) read);
                    downloaded += read;
                    st.downloaded = downloaded;
                    winBytes += read;
                    long now = System.currentTimeMillis();
                    long dt = now - lastTick;
                    if (dt >= 500) {
                        st.speed = DownloadState.formatSpeed(winBytes * 1000 / dt);
                        st.recentSpeed = winBytes * 1000 / dt;
                        winBytes = 0; lastTick = now;
                        notifyUiChanged();
                        int pct = st.total > 0 ? (int) (downloaded * 100 / st.total) : 0;
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            updateNotification(st, id);
                        }
                    }
                }
                fos.close();
                conn.disconnect();
                workers.remove(id);

                if (st.total > 0 && out.length() != st.total) {
                    fail(st, "Size mismatch: " + out.length() + " != " + st.total, id);
                    return;
                }
                // v1.1.7: ekstensi final mengikuti id — LLM pakai <id>.gguf,
                // model suara (whisper) pakai <id>.bin. LLM id-nya mengandung
                // "-q" kuantisasi... lebih aman: id voice punya prefix "voice-".
                // v1.1.8: espeak-ng-data TIDAK di-rename — file .zip-nya tetap
                // dipakai TtsEngine.maybeExtractEspeak (dihapus setelah ekstrak).
                if (id.equals(VoiceCatalog.PIPER_ESPEAK_ID)) {
                    st.status = DownloadState.DONE;
                    updateNotification(st, id);
                    notifyUiChanged();
                    stopIfIdle();
                    return;
                }
                String ext = id.startsWith("voice-") ? ".bin" : ".gguf";
                File dst = new File(dir, id + ext);
                if (!out.renameTo(dst)) {
                    copy(out, dst);
                    out.delete();
                }
                st.status = DownloadState.DONE;
                updateNotification(st, id);
                notifyUiChanged();
                stopIfIdle();
            } catch (Exception e) {
                if (cancelledFlag || Thread.currentThread().isInterrupted()) return; // pause/cancel
                fail(st, String.valueOf(e.getMessage()), id);
                workers.remove(id);
                stopIfIdle();
            }
        }

        private void copy(File src, File dst) throws Exception {
            java.io.FileInputStream fis = new java.io.FileInputStream(src);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
            byte[] b = new byte[65536]; int r;
            while ((r = fis.read(b)) > 0) fos.write(b, 0, r);
            fis.close(); fos.close();
        }

        private HttpURLConnection open(long rangeFrom) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
            c.setRequestProperty("User-Agent", "Rlnova/1.1 (Android)");
            return c;
        }

        private void fail(DownloadState st, String msg, String id) {
            st.status = DownloadState.FAILED;
            st.error = msg;
            notifyUiChanged();
            stopIfIdle();
        }
    }

    private void stopIfIdle() {
        if (workers.isEmpty()) {
            stopForeground(true);
            stopSelf(); // don't linger forever after the last worker ends
        }
    }

    /** Content intent: reopens the app on the Downloads screen. */
    private PendingIntent contentPi() {
        Intent open = new Intent(this, LibraryActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("openTab", "downloads");
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent actionPi(String action, String modelId) {
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(action);
        i.putExtra(EXTRA_ID, modelId);
        return PendingIntent.getService(this,
                action.hashCode() + modelId.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureForeground(String name) {
        startForeground(NOTIF_ID, initialNotification("Downloading " + name));
    }

    private Notification initialNotification(String title) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi())
                .build();
    }

    /**
     * Live progress notification: progress bar + downloaded/total + percent +
     * speed, Pause/Resume + Cancel action buttons, content intent to the
     * Downloads screen. Survives the app being swiped away.
     */
    private void updateNotification(DownloadState st, String id) {
        int pct = st.total > 0 ? (int) (st.downloaded * 100 / st.total) : 0;
        boolean paused = st.status == DownloadState.PAUSED;
        String name = names.containsKey(id) ? names.get(id) : "model";
        String eta = DownloadState.etaText(st);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(paused ? "Download paused" : "Downloading " + name)
                .setContentText(DownloadState.formatBytes(st.downloaded) + " of "
                        + DownloadState.formatBytes(st.total) + " (" + pct + "%)"
                        + (st.speed != null && !paused ? " · " + st.speed : "")
                        + (eta != null ? " · " + eta : ""))
                .setProgress(100, pct, false)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi());

        // Action buttons (platform shows max 3). Tapping routes back into the
        // service so the download can be controlled from the notification.
        b.addAction(new Notification.Action.Builder(null,
                paused ? "Resume" : "Pause",
                actionPi(paused ? ACTION_RESUME : ACTION_PAUSE, id)).build());
        b.addAction(new Notification.Action.Builder(null, "Cancel",
                actionPi(ACTION_CANCEL, id)).build());

        Notification n = b.build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, n);
    }

    private void notifyUiChanged() {
        DownloadState.bumpVersion();
    }

    @Override
    public void onDestroy() {
        for (Thread t : workers.values()) if (t != null) t.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
