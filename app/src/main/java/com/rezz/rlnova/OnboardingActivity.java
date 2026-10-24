package com.rezz.rlnova;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Onboarding (mockup 01): logo + wordmark "Rlnova", headline, step indicator
 * 1 Detect - 2 Download - 3 Chat, device spec card + TIER chip, recommended
 * model card, download button. All views built programmatically (no layout
 * XML, no external dependencies).
 *
 * Motion (DESIGN-RESEARCH P2): elements enter sequentially with a 60-80ms
 * stagger (logo -> headline -> spec card -> recommendation card -> button),
 * each translationY 24dp->0 + fade, 300ms, emphasized enter
 * PathInterpolator(0.05, 0.7, 0.1, 1). Tier chip reveals with
 * scale 0.9->1.0 + fade 200ms.
 */
public class OnboardingActivity extends Activity {

    private static final long STAGGER_MS = 70;
    private static final long ENTER_MS = 300;
    private static final PathInterpolator EMPHASIZED_ENTER =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private LinearLayout specsCard, recCard;
    private TextView tierChip;
    private final java.util.ArrayList<View> staggered = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DeviceInfo device = LibraryActivity.collectDeviceInfo(this);
        ModelCatalog catalog;
        try {
            catalog = ModelCatalog.load(this);
        } catch (Exception e) {
            catalog = new ModelCatalog();
        }
        final ModelCatalog.Model recommended = catalog.recommendFor(device);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0C0F14"));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 3, pad, pad);

        // Logo + wordmark
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        int logoPx = dp(64);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(logoPx, logoPx);
        logo.setLayoutParams(llp);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout logoWrap = new LinearLayout(this);
        logoWrap.setOrientation(LinearLayout.VERTICAL);
        logoWrap.setGravity(Gravity.CENTER);
        logoWrap.addView(logo);
        staggered(logoWrap);
        root.addView(logoWrap, vp());

        TextView wordmark = new TextView(this);
        wordmark.setText("Rlnova");
        wordmark.setTextColor(Color.parseColor("#E8ECF3"));
        wordmark.setTextSize(24);
        wordmark.setTypeface(Typeface.DEFAULT_BOLD);
        wordmark.setGravity(Gravity.CENTER);
        staggered(wordmark);
        root.addView(wordmark, vp());

        // Headline
        TextView headline = new TextView(this);
        headline.setText("AI that runs 100% offline on your phone");
        headline.setTextColor(Color.parseColor("#E8ECF3"));
        headline.setTextSize(24);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hlp.topMargin = pad * 3;
        headline.setLayoutParams(hlp);
        root.addView(headline);
        staggered(headline);

        // Subheadline
        TextView sub = new TextView(this);
        sub.setText("No data ever leaves the device. Everything runs locally.");
        sub.setTextColor(Color.parseColor("#9AA6B8"));
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        staggered(sub);
        root.addView(sub, vp());

        // Device spec card
        specsCard = new LinearLayout(this);
        specsCard.setOrientation(LinearLayout.VERTICAL);
        specsCard.setBackgroundResource(R.drawable.bg_card);
        int pad16 = (int) (16 * getResources().getDisplayMetrics().density);
        specsCard.setPadding(pad16, pad16, pad16, pad16);

        LinearLayout specsHeader = new LinearLayout(this);
        specsHeader.setOrientation(LinearLayout.HORIZONTAL);
        specsHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView specsTitle = new TextView(this);
        specsTitle.setText("Your device");
        specsTitle.setTextColor(Color.parseColor("#E8ECF3"));
        specsTitle.setTextSize(16);
        specsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        specsHeader.addView(specsTitle, new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, 1f));

        tierChip = new TextView(this);
        tierChip.setText(device.tierLabel());
        tierChip.setTextColor(tierColor(device.tierLabel()));
        tierChip.setBackgroundResource(R.drawable.bg_chip_pill);
        tierChip.setTextSize(12);
        tierChip.setTypeface(Typeface.DEFAULT_BOLD);
        tierChip.setPadding(dp(12), dp(4), dp(12), dp(4));
        specsHeader.addView(tierChip, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        specsCard.addView(specsHeader);

        addSpecRow(specsCard, "RAM", DeviceInfo.formatBytes(device.totalRamBytes));
        addSpecRow(specsCard, "Chipset", device.chipset);
        addSpecRow(specsCard, "Free storage", DeviceInfo.formatBytes(device.freeStorageBytes));
        addSpecRow(specsCard, "CPU", device.cpuCores + " cores");
        root.addView(specsCard, cardParams());
        staggered(specsCard);

        // Recommended model card
        recCard = new LinearLayout(this);
        recCard.setOrientation(LinearLayout.VERTICAL);
        recCard.setBackgroundResource(R.drawable.bg_card);
        recCard.setPadding(pad16, pad16, pad16, pad16);

        TextView recLabel = new TextView(this);
        recLabel.setText("Recommended model");
        recLabel.setTextColor(Color.parseColor("#9AA6B8"));
        recLabel.setTextSize(12);
        recCard.addView(recLabel);

        TextView recName = new TextView(this);
        recName.setText(recommended != null ? recommended.name : "-");
        recName.setTextColor(Color.parseColor("#E8ECF3"));
        recName.setTextSize(18);
        recName.setTypeface(Typeface.DEFAULT_BOLD);
        recCard.addView(recName);

        TextView recDesc = new TextView(this);
        recDesc.setText(recommended != null ? recommended.description : "");
        recDesc.setTextColor(Color.parseColor("#9AA6B8"));
        recDesc.setTextSize(14);
        recCard.addView(recDesc);

        TextView recMeta = new TextView(this);
        recMeta.setText(recommended != null
                ? ModelCatalog.tierLabel(recommended.tier) + " · "
                  + DeviceInfo.formatBytes(recommended.sizeBytes) + " · " + recommended.quant
                : "");
        recMeta.setTextColor(Color.parseColor("#7C8AA5"));
        recMeta.setTextSize(12);
        recCard.addView(recMeta);
        root.addView(recCard, cardParams());
        staggered(recCard);

        // Step indicator: 1 Detect (active) - 2 Download - 3 Chat
        root.addView(buildStepIndicator(), vp());

        // Download button
        Button btnDownload = new Button(this);
        btnDownload.setText("Download recommended model");
        btnDownload.setBackgroundResource(R.drawable.bg_button_primary);
        btnDownload.setTextColor(Color.WHITE);
        btnDownload.setTextSize(16);
        btnDownload.setTypeface(Typeface.DEFAULT_BOLD);
        btnDownload.setAllCaps(false);
        btnDownload.setEnabled(recommended != null);
        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (recommended == null) return;
                DownloadService.requestIgnoreBatteryOptimization(OnboardingActivity.this);
                // POST_NOTIFICATIONS runtime request (Android 13+) before the first download
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{
                                    android.Manifest.permission.POST_NOTIFICATIONS}, 2001);
                        }
                    } catch (Exception ignored) { }
                }
                Intent i = new Intent(OnboardingActivity.this, DownloadService.class);
                i.setAction(DownloadService.ACTION_START);
                i.putExtra(DownloadService.EXTRA_ID, recommended.id);
                i.putExtra(DownloadService.EXTRA_URL, recommended.url());
                i.putExtra(DownloadService.EXTRA_NAME, recommended.name);
                i.putExtra(DownloadService.EXTRA_SIZE, recommended.sizeBytes);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
                getSharedPreferences("rlnova", MODE_PRIVATE).edit().putBoolean("onboarded", true).apply();
                startActivity(new Intent(OnboardingActivity.this, LibraryActivity.class));
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(48));
        blp.topMargin = dp(16);
        root.addView(btnDownload, blp);
        staggered(btnDownload);

        // TextButton "Browse all models"
        TextView seeAll = new TextView(this);
        seeAll.setText("Browse all models");
        seeAll.setTextColor(Color.parseColor("#5B8DEF"));
        seeAll.setTextSize(14);
        seeAll.setGravity(Gravity.CENTER);
        seeAll.setPadding(0, dp(12), 0, 0);
        seeAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("rlnova", MODE_PRIVATE).edit().putBoolean("onboarded", true).apply();
                startActivity(new Intent(OnboardingActivity.this, LibraryActivity.class));
                finish();
            }
        });
        root.addView(seeAll, vp());
        staggered(seeAll);

        setContentView(root);
        runStagger();
    }

    // ------------------------------------------------------------- stagger

    /** Register a view to enter with the staggered animation. */
    private void staggered(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(24));
        staggered.add(v);
    }

    /** Play the enter choreography: 60-80ms stagger, 300ms emphasized enter. */
    private void runStagger() {
        long delay = 0;
        for (View v : staggered) {
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ENTER_MS)
                .setStartDelay(delay)
                .setInterpolator(EMPHASIZED_ENTER)
                .start();
            delay += STAGGER_MS;
        }
        // Tier chip reveal: scale 0.9 -> 1.0 + fade, 200ms (after the spec card enters)
        tierChip.setScaleX(0.9f);
        tierChip.setScaleY(0.9f);
        tierChip.setAlpha(0f);
        tierChip.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(200)
                .setStartDelay(2 * STAGGER_MS + ENTER_MS)
                .setInterpolator(EMPHASIZED_ENTER)
                .start();
    }

    // ----------------------------------------------------------------- step

    /** Step indicator horizontal: 1 Detect (active) - 2 Download - 3 Chat. */
    private View buildStepIndicator() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String[] labels = {"Detect", "Download", "Chat"};
        for (int i = 0; i < 3; i++) {
            TextView step = new TextView(this);
            step.setText((i + 1) + " " + labels[i]);
            step.setTextSize(12);
            step.setTextColor(i == 0 ? Color.parseColor("#5B8DEF") : Color.parseColor("#5B6678"));
            step.setTypeface(i == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            p.rightMargin = dp(16);
            row.addView(step, p);
            if (i < 2) {
                View line = new View(this);
                line.setBackgroundColor(Color.parseColor("#232B3A"));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(1));
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.rightMargin = dp(16);
                row.addView(line, lp);
            }
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(24);
        rp.bottomMargin = dp(24);
        row.setLayoutParams(rp);
        return row;
    }

    private void addSpecRow(LinearLayout card, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor("#9AA6B8"));
        tvLabel.setTextSize(14);
        row.addView(tvLabel, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(Color.parseColor("#E8ECF3"));
        tvValue.setTextSize(14);
        row.addView(tvValue, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        card.addView(row);
    }

    private int tierColor(String tier) {
        switch (tier) {
            case "LOW": return Color.parseColor("#34C77B");
            case "MID": return Color.parseColor("#5B8DEF");
            default: return Color.parseColor("#A78BFA");
        }
    }

    private LinearLayout.LayoutParams vp() {
        return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams vp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(16);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}


