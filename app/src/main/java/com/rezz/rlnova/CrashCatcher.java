package com.rezz.rlnova;

import android.content.Context;

/**
 * Crash catcher global (v1.2.5): memasang
 * Thread.setDefaultUncaughtExceptionHandler dari titik awal app
 * (MainActivity.onCreate paling awal — app ini TIDAK punya kelas Application
 * sendiri di manifest, dan menambah satu berarti mengubah manifest; pola
 * setara dan lebih aman dipasang di entry activity, sebelum activity lain
 * jalan). Semua crash Java apa pun threadnya (main, rlnova-gen, rlnova-tts,
 * rlnova-stt, worker web, dst.) ditulis ke filesDir/crash-log.txt:
 * timestamp, model HP + RAM, versi app, thread, exception class+message,
 * dan stack trace (maks 200 baris). Sesudah menulis, handler DEFAULT
 * DIPANGGIL — force-close berjalan normal seperti biasa.
 *
 * Saat app dibuka berikutnya MainActivity membaca crash-log.txt
 * (MainActivity.maybeShowGlobalCrashDialog, pola dialog sama dengan dialog
 * logcat TTS v1.1.17), menampilkannya scrollable + [Salin log] + [Tutup],
 * lalu menghapus file supaya dialog tidak muncul berulang. Instalasi
 * handler juga selalu try/catch: kegagalannya tidak pernah memicu crash.
 */
public final class CrashCatcher {

    public static final String FILE_NAME = "crash-log.txt";

    private CrashCatcher() { }

    /**
     * Pasang handler uncaught global. Idempotent (flag statis). Dipanggil
     * PALING AWAL di MainActivity.onCreate, sebelum super.onCreate().
     */
    public static void install(final Context ctx) {
        try {
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (prev instanceof Uncaught) return; // sudah terpasang
            Thread.setDefaultUncaughtExceptionHandler(new Uncaught(ctx, prev));
        } catch (Throwable ignored) { }
    }

    private static final class Uncaught implements
            Thread.UncaughtExceptionHandler {

        private final Context appCtx;
        private final Thread.UncaughtExceptionHandler previous;

        Uncaught(Context ctx, Thread.UncaughtExceptionHandler previous) {
            // Application context — aman untuk filesDir dari thread mana pun
            // tanpa membocorkan Activity. Defensif: null/gagal → writeLog
            // fallback ke tmpdir (juga yang dipakai tes headless JVM).
            Context ac = null;
            try {
                if (ctx != null) ac = ctx.getApplicationContext();
            } catch (Throwable ignored) { }
            this.appCtx = ac;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                writeLog(t, e);
            } catch (Throwable ignored) { }
            if (previous != null) {
                // Rantai ke handler default (Android force-close normal).
                previous.uncaughtException(t, e);
            }
        }

        private void writeLog(Thread t, Throwable e) {
            // Direktori tujuan: filesDir saat context nyata ada (app Android);
            // fallback tmpdir sistem (tes headless JVM / context aneh).
            java.io.File dir;
            try {
                dir = appCtx != null ? appCtx.getFilesDir() : null;
            } catch (Throwable ignored) {
                dir = null;
            }
            if (dir == null) {
                dir = new java.io.File(
                        System.getProperty("java.io.tmpdir", "/tmp"),
                        "rlnova-crash-test");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("=== RLNOVA CRASH ===\n");
            sb.append("timestamp: ")
              .append(java.text.DateFormat.getDateTimeInstance()
                      .format(new java.util.Date()))
              .append(" (epoch ")
              .append(System.currentTimeMillis())
              .append(")\n");
            // Model + RAM
            sb.append("device: ").append(android.os.Build.MANUFACTURER)
              .append(' ').append(android.os.Build.MODEL);
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) appCtx.getSystemService(
                                Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo mi =
                            new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    sb.append(" · RAM total ")
                      .append(String.format(java.util.Locale.US, "%.1f GB",
                              mi.totalMem / 1073741824.0))
                      .append(" · avail saat crash ")
                      .append(String.format(java.util.Locale.US, "%.1f GB",
                              mi.availMem / 1073741824.0));
                }
            } catch (Throwable ignored) { }
            sb.append('\n');
            // Versi app
            sb.append("app: Rlnova v");
            try {
                sb.append(appCtx.getPackageManager()
                        .getPackageInfo(appCtx.getPackageName(), 0).versionName);
            } catch (Throwable ignored) {
                sb.append("?");
            }
            sb.append('\n');
            // Thread + exception
            sb.append("thread: ").append(t.getName()).append('\n');
            sb.append("exception: ").append(e.getClass().getName());
            if (e.getMessage() != null) sb.append(": ").append(e.getMessage());
            sb.append('\n');
            // Stack trace lengkap, maks 200 baris
            sb.append("stack:\n");
            StackTraceElement[] st = e.getStackTrace();
            int n = Math.min(st.length, 200);
            for (int i = 0; i < n; i++) {
                sb.append("    at ").append(st[i]).append('\n');
            }
            if (st.length > n) {
                sb.append("    ... (").append(st.length - n)
                  .append(" baris lagi dipangkas)\n");
            }
            // Penyebab berantai (cause), sampai 2 level — sering ini yang
            // jadi akar crash (mis. OOM dibungkus RuntimeException).
            Throwable cause = e.getCause();
            int depth = 0;
            while (cause != null && depth < 2) {
                sb.append("caused by: ").append(cause.getClass().getName());
                if (cause.getMessage() != null)
                    sb.append(": ").append(cause.getMessage());
                sb.append('\n');
                StackTraceElement[] cst = cause.getStackTrace();
                int cn = Math.min(cst.length, 50);
                for (int i = 0; i < cn; i++) {
                    sb.append("    at ").append(cst[i]).append('\n');
                }
                cause = cause.getCause();
                depth++;
            }
            // Tulis sinkron (append bila ada crash beruntun di run yang sama)
            java.io.File f = new java.io.File(dir, FILE_NAME);
            try {
                java.io.FileWriter w = new java.io.FileWriter(f, f.exists());
                w.write(sb.toString());
                w.flush();
                w.close();
            } catch (java.io.IOException ioe) {
                // fallback terakhir: sistem file penuh dsb. — coba tulis
                // versi mini tanpa stack (header + exception saja) sebagai
                // file terpisah agar jejak crash tidak hilang total.
                try {
                    java.io.FileWriter w2 = new java.io.FileWriter(
                            new java.io.File(dir,
                                    "crash-log-mini.txt"), false);
                    w2.write(sb.substring(0, Math.min(sb.length(), 512)));
                    w2.flush();
                    w2.close();
                } catch (Throwable ignored2) { }
            }
        }
    }
}
