package com.rezz.rlnova;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Splash / launch screen (DESIGN-RESEARCH P2): Rlnova logo + wordmark + CTA.
 *
 * Splash <= 1s on cold start only: logo scale 0.85 -> 1.0 + alpha 0 -> 1,
 * 450ms, emphasized enter PathInterpolator(0.05, 0.7, 0.1, 1); three small
 * breathing dots below (alpha 0.3 <-> 1.0, 600ms loop) as the "AI loading"
 * metaphor, stopped when the transition fires. No progress bars, no
 * "Loading..." text, nothing over 1s.
 */
public class MainActivity extends Activity {

    private static final long SPLASH_ENTER_MS = 450;
    private static final long SPLASH_HOLD_MS = 350; // total <= 1s
    private static final PathInterpolator EMPHASIZED_ENTER =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private final android.os.Handler ui = new android.os.Handler();
    private ValueAnimator dotsAnimator;
    private boolean navigated = false;
    private LinearLayout dotsRow;

    private final Runnable autoTransition = new Runnable() {
        @Override public void run() { navigate(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v1.2.5: crash catcher global — dipasang PALING AWAL (sebelum
        // super.onCreate dan segala UI), sebelum activity lain jalan.
        CrashCatcher.install(this);

        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#0C0F14"));
        w.setNavigationBarColor(Color.parseColor("#0C0F14"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0C0F14"));
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        // Logo
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        int logoPx = dp(96);
        logo.setLayoutParams(new LinearLayout.LayoutParams(logoPx, logoPx));
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo);

        // Wordmark
        TextView title = new TextView(this);
        title.setText("Rlnova");
        title.setTextColor(Color.parseColor("#E8ECF3"));
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(16);
        title.setLayoutParams(wlp);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Private AI, on this device");
        sub.setTextColor(Color.parseColor("#9AA6B8"));
        sub.setTextSize(14f);
        sub.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        sub.setLayoutParams(slp);
        root.addView(sub);

        // Three breathing dots ("AI loading" metaphor)
        dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(20);
        dotsRow.setLayoutParams(dlp);
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(Color.parseColor("#5B8DEF"));
            LinearLayout.LayoutParams dd = new LinearLayout.LayoutParams(dp(6), dp(6));
            dd.rightMargin = i < 2 ? dp(8) : 0;
            dot.setLayoutParams(dd);
            dot.setBackground(bg);
            dot.setAlpha(0.3f);
            dotsRow.addView(dot);
        }
        root.addView(dotsRow);

        // CTA (also tappable to skip the hold time)
        TextView btnOnboard = new TextView(this);
        btnOnboard.setText("Get started");
        btnOnboard.setTextColor(Color.WHITE);
        btnOnboard.setTextSize(16f);
        btnOnboard.setTypeface(Typeface.DEFAULT_BOLD);
        btnOnboard.setGravity(android.view.Gravity.CENTER);
        btnOnboard.setBackgroundResource(R.drawable.bg_button_primary);
        btnOnboard.setPadding(dp(24), dp(14), dp(24), dp(14));
        btnOnboard.setOnClickListener(v -> navigate());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(40);
        root.addView(btnOnboard, bp);

        setContentView(root);

        // Logo entrance: scale 0.85 -> 1.0 + alpha 0 -> 1, 450ms, emphasized enter
        logo.setScaleX(0.85f);
        logo.setScaleY(0.85f);
        logo.setAlpha(0f);
        logo.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(SPLASH_ENTER_MS)
                .setInterpolator(EMPHASIZED_ENTER)
                .start();

        startDots();

        // DIAG-TEMP v1.1.17: kalau proses mati di tengah native TTS di run
        // sebelumnya, worker menulis marker stage. Di sini: baca, hapus, // DIAG-TEMP v1.1.17
        // lalu tampilkan dialog logcat (AT MOST once per launch). Semua // DIAG-TEMP v1.1.17
        // try/catch — kegagalan logcat/dialog TIDAK PERNAH crash launch. // DIAG-TEMP v1.1.17
        maybeShowTtsCrashDialog(); // DIAG-TEMP v1.1.17

        // v1.2.5: dialog crash log GLOBAL (pola sama dengan dialog logcat TTS
        // di atas). Bila run sebelumnya crash di jalur mana pun (chat/web/
        // engine/UI), crash-log.txt ada → tampilkan isi log scrollable +
        // [Salin log] + [Tutup], lalu hapus file (max 1x per launch). Semua
        // try/catch — kegagalan dialog TIDAK PERNAH crash launch.
        maybeShowGlobalCrashDialog();
    }

    // ==================== v1.2.5: crash log global ====================
    /** Baca crash-log.txt dari run sebelumnya → dialog scrollable + copy. */
    private void maybeShowGlobalCrashDialog() {
        try {
            java.io.File f = new java.io.File(getFilesDir(),
                    CrashCatcher.FILE_NAME);
            if (!f.exists() || f.length() == 0) return;
            String full = "";
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                full = sb.toString();
            } catch (Throwable ignored) { }
            f.delete(); // hapus segera — dialog max 1x per launch
            if (full.trim().isEmpty()) return;
            final String logText = full;
            final java.util.List<String> lines = new java.util.ArrayList<>();
            for (String l : logText.split("\n")) lines.add(l);
            int from = Math.max(0, lines.size() - 60);
            StringBuilder body = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                body.append(lines.get(i)).append('\n');
            }
            if (from > 0) {
                body.insert(0, "(... " + from + " baris awal dipangkas — "
                        + "salin log untuk versi lengkap)\n");
            }
            final String bodyText = body.toString();
            ui.post(() -> showGlobalCrashDialog(bodyText, logText));
        } catch (Throwable ignored) { } // never crash the launch
    }

    private void showGlobalCrashDialog(String body, final String fullText) {
        try {
            ScrollView scroll = new ScrollView(this);
            TextView msg = new TextView(this);
            msg.setText(body);
            msg.setTextSize(12f);
            msg.setTypeface(Typeface.MONOSPACE);
            msg.setPadding(dp(16), dp(8), dp(16), dp(8));
            scroll.addView(msg);
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                    .setTitle("Rlnova mati tak terduga — log crash")
                    .setView(scroll)
                    .setPositiveButton("[Salin log]", (dlg, which) -> {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager)
                                            getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "rlnova-crash", fullText));
                            android.widget.Toast.makeText(this, "Log tersalin",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) { }
                    })
                    .setNegativeButton("[Tutup]", (dlg, which) -> dlg.dismiss())
                    .create();
            d.setCanceledOnTouchOutside(false);
            d.show();
        } catch (Throwable ignored) { } // never crash the launch
    }
    // ================== AKHIR v1.2.5: crash log global ==================

    // ==================== DIAG-TEMP v1.1.17 ==================== // DIAG-TEMP v1.1.17
    // Dialog logcat: hanya kalau crash marker dari run sebelumnya ada. // DIAG-TEMP v1.1.17
    private void maybeShowTtsCrashDialog() { // DIAG-TEMP v1.1.17
        try { // DIAG-TEMP v1.1.17
            java.io.File marker = new java.io.File(getFilesDir(), "tts-crash-marker.txt"); // DIAG-TEMP v1.1.17
            if (!marker.exists()) return; // DIAG-TEMP v1.1.17
            String markerText = ""; // DIAG-TEMP v1.1.17
            try (java.io.BufferedReader r = new java.io.BufferedReader( // DIAG-TEMP v1.1.17
                    new java.io.FileReader(marker))) { // DIAG-TEMP v1.1.17
                markerText = r.readLine(); // DIAG-TEMP v1.1.17
                if (markerText == null) markerText = ""; // DIAG-TEMP v1.1.17
            } catch (Throwable ignored) { } // DIAG-TEMP v1.1.17
            marker.delete(); // DIAG-TEMP v1.1.17: hapus segera, dialog max 1x per launch
            final String stage = markerText; // DIAG-TEMP v1.1.17
            final java.util.List<String> lines = collectFilteredLogcat(); // DIAG-TEMP v1.1.17
            ui.post(() -> showTtsCrashDialog(stage, lines)); // DIAG-TEMP v1.1.17
        } catch (Throwable ignored) { } // DIAG-TEMP v1.1.17: never crash the launch
    } // DIAG-TEMP v1.1.17

    /** `logcat -d`, filter kata kunci native TTS, max 60 baris terakhir. */ // DIAG-TEMP v1.1.17
    private java.util.List<String> collectFilteredLogcat() { // DIAG-TEMP v1.1.17
        java.util.List<String> out = new java.util.ArrayList<>(); // DIAG-TEMP v1.1.17
        try { // DIAG-TEMP v1.1.17
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d"}); // DIAG-TEMP v1.1.17
            java.io.BufferedReader br = new java.io.BufferedReader( // DIAG-TEMP v1.1.17
                    new java.io.InputStreamReader(p.getInputStream())); // DIAG-TEMP v1.1.17
            String l; // DIAG-TEMP v1.1.17
            java.util.List<String> filtered = new java.util.ArrayList<>(); // DIAG-TEMP v1.1.17
            while ((l = br.readLine()) != null) { // DIAG-TEMP v1.1.17
                String low = l.toLowerCase(java.util.Locale.US); // DIAG-TEMP v1.1.17
                if (low.contains("sherpa") || low.contains("espeak") || low.contains("onnx") // DIAG-TEMP v1.1.17
                        || low.contains("fatal") || low.contains("signal") || low.contains("abort") // DIAG-TEMP v1.1.17
                        || low.contains("jni") || low.contains("onnxruntime")) { // DIAG-TEMP v1.1.17
                    filtered.add(l); // DIAG-TEMP v1.1.17
                } // DIAG-TEMP v1.1.17
            } // DIAG-TEMP v1.1.17
            p.destroy(); // DIAG-TEMP v1.1.17
            int from = Math.max(0, filtered.size() - 60); // DIAG-TEMP v1.1.17: last 60 lines max
            out.addAll(filtered.subList(from, filtered.size())); // DIAG-TEMP v1.1.17
        } catch (Throwable ignored) { } // DIAG-TEMP v1.1.17: any failure -> empty list
        return out; // DIAG-TEMP v1.1.17
    } // DIAG-TEMP v1.1.17

    private void showTtsCrashDialog(String stage, java.util.List<String> lines) { // DIAG-TEMP v1.1.17
        try { // DIAG-TEMP v1.1.17
            StringBuilder body = new StringBuilder(); // DIAG-TEMP v1.1.17
            if (lines.isEmpty()) { // DIAG-TEMP v1.1.17
                body.append("Log kosong — tidak ada baris error dari proses."); // DIAG-TEMP v1.1.17
            } else { // DIAG-TEMP v1.1.17
                for (String l : lines) body.append(l).append('\n'); // DIAG-TEMP v1.1.17
            } // DIAG-TEMP v1.1.17
            final String fullText = "TTS mati di tahap: " + stage + "\n\n" + body; // DIAG-TEMP v1.1.17
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(this) // DIAG-TEMP v1.1.17
                    .setTitle("TTS mati di tahap: " + stage) // DIAG-TEMP v1.1.17
                    .setMessage(body.toString()) // DIAG-TEMP v1.1.17
                    .setPositiveButton("[Salin log]", (dlg, which) -> { // DIAG-TEMP v1.1.17
                        try { // DIAG-TEMP v1.1.17
                            android.content.ClipboardManager cm = // DIAG-TEMP v1.1.17
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); // DIAG-TEMP v1.1.17
                            cm.setPrimaryClip(android.content.ClipData.newPlainText( // DIAG-TEMP v1.1.17
                                    "rlnova-tts-crash", fullText)); // DIAG-TEMP v1.1.17
                            android.widget.Toast.makeText(this, "Log tersalin", // DIAG-TEMP v1.1.17
                                    android.widget.Toast.LENGTH_SHORT).show(); // DIAG-TEMP v1.1.17
                        } catch (Throwable ignored) { } // DIAG-TEMP v1.1.17
                    }) // DIAG-TEMP v1.1.17
                    .setNegativeButton("[Tutup]", (dlg, which) -> dlg.dismiss()) // DIAG-TEMP v1.1.17
                    .create(); // DIAG-TEMP v1.1.17
            d.setCanceledOnTouchOutside(false); // DIAG-TEMP v1.1.17: non-cancellable
            d.show(); // DIAG-TEMP v1.1.17
        } catch (Throwable ignored) { } // DIAG-TEMP v1.1.17: never crash the launch
    } // DIAG-TEMP v1.1.17
    // =================== AKHIR DIAG-TEMP v1.1.17 ==================== // DIAG-TEMP v1.1.17

    @Override
    protected void onStart() {
        super.onStart();
        // Splash total <= 1s on cold start, then auto-transition.
        ui.postDelayed(autoTransition, SPLASH_ENTER_MS + SPLASH_HOLD_MS);
    }

    /** Breathing dots: alpha 0.3 <-> 1.0, 600ms loop, phase offset per dot. */
    private void startDots() {
        dotsAnimator = ValueAnimator.ofFloat(0f, 1f);
        dotsAnimator.setDuration(600);
        dotsAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dotsAnimator.setRepeatMode(ValueAnimator.REVERSE);
        dotsAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            for (int i = 0; i < dotsRow.getChildCount(); i++) {
                float phase = (t + i * 0.25f) % 1f;
                float alpha = 0.3f + 0.7f * (float) Math.abs(Math.sin(phase * Math.PI));
                dotsRow.getChildAt(i).setAlpha(alpha);
            }
        });
        dotsAnimator.start();
    }

    private void stopDots() {
        if (dotsAnimator != null) {
            dotsAnimator.cancel();
            dotsAnimator = null;
        }
    }

    private void navigate() {
        if (navigated) return;
        navigated = true;
        ui.removeCallbacks(autoTransition);
        stopDots();
        boolean onboarded = getSharedPreferences("rlnova", MODE_PRIVATE)
                .getBoolean("onboarded", false);
        Intent i = new Intent(this, onboarded ? LibraryActivity.class : OnboardingActivity.class);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(autoTransition);
        stopDots();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
