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
    }

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
