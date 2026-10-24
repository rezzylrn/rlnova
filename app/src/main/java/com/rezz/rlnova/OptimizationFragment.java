package com.rezz.rlnova;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * OptimizationFragment (mockup 05) — the Optimization panel (user focus).
 *
 * Card "Model speed test": mono 32sp number (last decode tok/s),
 * bar chart of the last 5 runs (custom View, no dependency), "Run benchmark"
 * button -> nativeBench on a background thread (blocks until done; the
 * result is appended to PerfPrefs).
 *
 * Card "Performance parameters": 3 sliders + values, 2 switches (Use GPU —
 * default OFF + warning caption on Mali; Battery saver —
 * threads/2 + GPU 0).
 *
 * "Save settings" (bumps the epoch -> chat reloads the model) +
 * "Apply benchmark result" (auto-tune from the fastest history entry).
 */
public class OptimizationFragment extends Fragment {

    private static final int COL_BG      = 0xFF0C0F14;
    private static final int COL_CARD    = 0xFF131822;
    private static final int COL_DIV     = 0xFF232B3A;
    private static final int COL_TEXT    = 0xFFEDF1F7;
    private static final int COL_SUB     = 0xFF9AA6B8;
    private static final int COL_PRIMARY = 0xFF5B8DEF;
    private static final int COL_BAR     = 0xFF5B8DEF;
    private static final int COL_BAR_DIM = 0xFF3A4358;

    private SeekBar sbThreads, sbGpu, sbCtx;
    private TextView tvThreads, tvGpu, tvCtx;
    private android.widget.Switch swGpu, swBattery;
    private TextView tvGpuCaption;
    private TextView bigNumber;
    private BarChartView chart;
    private TextView benchHint;
    private TextView btnBench, btnApply, btnSimpan;

    private DeviceInfo device;
    private PerfPrefs.Perf perf;
    private final android.os.Handler ui = new android.os.Handler();
    private volatile boolean benchRunning = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle s) {
        Context ctx = getActivity();
        device = ((ModelLibraryFragment.Host) getActivity()).deviceInfo();

        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(COL_BG);
        scroll.setFillViewport(true);
        scroll.setPadding(0, statusBarInset(), 0, 0);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(88)); // ruang BottomNav
        scroll.addView(root);

        TextView title = new TextView(ctx);
        title.setText("Optimization");
        title.setTextColor(COL_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(ctx);
        subtitle.setText("Tune model speed for this device");
        subtitle.setTextColor(COL_SUB);
        subtitle.setTextSize(12);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splp.topMargin = dp(2);
        root.addView(subtitle, splp);

        // ---------------- Card 1: Model speed test ----------------
        LinearLayout card1 = card(ctx);
        TextView h1 = new TextView(ctx);
        h1.setText("Model speed test");
        h1.setTextColor(COL_TEXT);
        h1.setTextSize(16);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        card1.addView(h1);

        bigNumber = new TextView(ctx);
        bigNumber.setTypeface(Typeface.MONOSPACE);
        bigNumber.setTextSize(32);
        bigNumber.setTextColor(COL_TEXT);
        bigNumber.setPadding(0, dp(8), 0, 0);
        card1.addView(bigNumber);

        TextView unit = new TextView(ctx);
        unit.setText("tokens per second (decode)");
        unit.setTextColor(COL_SUB);
        unit.setTextSize(12);
        unit.setPadding(0, dp(2), 0, 0);
        card1.addView(unit);

        benchHint = new TextView(ctx);
        benchHint.setTextColor(COL_SUB);
        benchHint.setTextSize(12);
        benchHint.setPadding(0, dp(8), 0, 0);
        card1.addView(benchHint);

        BarChartView chart = new BarChartView(ctx);
        chart.setMinimumHeight(dp(88));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        clp.topMargin = dp(12);
        card1.addView(chart, clp);
        this.chart = chart;

        btnBench = primaryButton(ctx, "Run benchmark");
        btnBench.setOnClickListener(v -> runBenchmark());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        blp.topMargin = dp(14);
        card1.addView(btnBench, blp);
        root.addView(card1, cardLp());

        // ---------------- Card 2: Performance parameters ----------------
        LinearLayout card2 = card(ctx);
        TextView h2 = new TextView(ctx);
        h2.setText("Performance parameters");
        h2.setTextColor(COL_TEXT);
        h2.setTextSize(16);
        h2.setTypeface(Typeface.DEFAULT_BOLD);
        card2.addView(h2);

        int cores = device != null ? Math.max(1, device.cpuCores) : 4;
        perf = PerfPrefs.load(ctx, device);

        LinearLayout rowT = sliderRow(card2, "CPU threads");
        tvThreads = monoValue(rowT);
        sbThreads = sliderBar(rowT, 0, cores - 1, perf.threads - 1, "cpu");

        LinearLayout rowG = sliderRow(card2, "GPU layers");
        tvGpu = monoValue(rowG);
        sbGpu = sliderBar(rowG, 0, 99, perf.gpuLayers, "gpu");

        LinearLayout rowC = sliderRow(card2, "Context size");
        tvCtx = monoValue(rowC);
        tvCtx.setText(String.valueOf(perf.nCtx));
        sbCtx = sliderBarCtx(rowC, perf.nCtx);

        LinearLayout gpuRow = switchRow(ctx, "GPU offload (Vulkan)", perf.useGpu);
        swGpu = (android.widget.Switch) gpuRow.getTag();
        card2.addView(gpuRow);
        tvGpuCaption = caption(ctx);
        card2.addView(tvGpuCaption);
        updateGpuCaption();

        LinearLayout batRow = switchRow(ctx, "Battery saver mode", perf.battery);
        swBattery = (android.widget.Switch) batRow.getTag();
        card2.addView(batRow);
        TextView batCap = new TextView(ctx);
        batCap.setText("Threads are halved and GPU is off while this mode is on");
        batCap.setTextColor(COL_SUB);
        batCap.setTextSize(11);
        batCap.setPadding(dp(4), 0, 0, dp(8));
        card2.addView(batCap);

        btnApply = primaryButton(ctx, "Apply benchmark result");
        btnApply.setOnClickListener(v -> applyBenchTune());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        alp.topMargin = dp(10);
        card2.addView(btnApply, alp);

        btnSimpan = primaryButton(ctx, "Save settings");
        btnSimpan.setOnClickListener(v -> saveSettings());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        slp.topMargin = dp(10);
        card2.addView(btnSimpan, slp);

        TextView saveCaption = new TextView(ctx);
        saveCaption.setText("applied when the model reloads");
        saveCaption.setTextColor(COL_SUB);
        saveCaption.setTextSize(12);
        saveCaption.setGravity(Gravity.CENTER);
        saveCaption.setPadding(0, dp(6), 0, 0);
        card2.addView(saveCaption);
        root.addView(card2, cardLp());

        refreshBenchViews();
        return scroll;
    }

    // ---------------------------------------------------------- benchmark

    private void runBenchmark() {
        if (benchRunning) return;
        if (!LlamaEngine.libraryLoaded()) {
            Toast.makeText(getActivity(),
                    "Model library not loaded yet — open a conversation in the Chat tab first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final LlamaEngine engine = ChatRoomFragment.sharedEngine();
        if (engine == null || !engine.isReady()) {
            Toast.makeText(getActivity(),
                    "No model loaded yet — start a conversation in the Chat tab first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (engine.isGenerating()) {
            Toast.makeText(getActivity(),
                    "Wait for the current generation to finish", Toast.LENGTH_SHORT).show();
            return;
        }
        benchRunning = true;
        btnBench.setEnabled(false);
        btnBench.setText("Testing…");
        benchHint.setText("Running benchmark — you can leave this screen");
        new Thread(() -> {
            String res = engine.bench();
            ui.post(() -> onBenchResult(res));
        }, "rlnova-bench").start();
    }

    private void onBenchResult(String res) {
        benchRunning = false;
        btnBench.setEnabled(true);
        btnBench.setText("Run benchmark");
        Context ctx = getActivity();
        if (ctx == null) return;
        float prefill = -1, decode = -1;
        if (res != null && res.startsWith("p=")) {
            try {
                java.util.regex.Matcher mp = java.util.regex.Pattern
                        .compile("p=([0-9.]+)").matcher(res);
                java.util.regex.Matcher md = java.util.regex.Pattern
                        .compile("d=([0-9.]+)").matcher(res);
                if (mp.find()) prefill = Float.parseFloat(mp.group(1));
                if (md.find()) decode = Float.parseFloat(md.group(1));
            } catch (Exception ignored) { }
        }
        if (decode < 0) {
            String msg = res != null && res.startsWith("ERR=")
                    ? res.substring(4) : "Benchmark failed";
            benchHint.setText(msg);
            return;
        }
        PerfPrefs.BenchRecord r = new PerfPrefs.BenchRecord();
        r.timestamp = System.currentTimeMillis();
        PerfPrefs.Perf cur = PerfPrefs.load(ctx, device);
        r.threads = perf.effectiveThreads(device != null ? device.cpuCores : 4);
        r.gpuLayers = perf.effectiveGpuLayers();
        r.ctx = perf.nCtx;
        r.prefillTps = prefill;
        r.decodeTps = decode;
        PerfPrefs.appendBench(ctx, r);
        refreshBenchViews();
        benchHint.setText("Done — prefill " + fmt1(prefill) + " tok/s, decode "
                + fmt1(decode) + " tok/s");
    }

    private void refreshBenchViews() {
        Context ctx = getActivity();
        if (ctx == null) return;
        List<PerfPrefs.BenchRecord> hist = PerfPrefs.lastBench(ctx, 5);
        if (hist.isEmpty()) {
            bigNumber.setText("—");
            benchHint.setText("No benchmark data yet");
        } else {
            PerfPrefs.BenchRecord last = hist.get(hist.size() - 1);
            bigNumber.setText(fmt1(last.decodeTps));
            benchHint.setText("Last: " + last.summary());
        }
        chart.setValues(hist);
    }

    private void applyBenchTune() {
        Context ctx = getActivity();
        if (ctx == null) return;
        PerfPrefs.BenchRecord best = PerfPrefs.fastestBench(ctx);
        if (best == null) {
            Toast.makeText(ctx, "No benchmark results yet", Toast.LENGTH_SHORT).show();
            return;
        }
        PerfPrefs.applyBench(ctx, best);
        perf = PerfPrefs.load(ctx, device);
        syncControlsToPerf();
        Toast.makeText(ctx, "Applied: " + perf.threads + " threads, GPU "
                + perf.gpuLayers + " layers, context " + perf.nCtx, Toast.LENGTH_LONG).show();
    }

    private void saveSettings() {
        Context ctx = getActivity();
        if (ctx == null) return;
        PerfPrefs.Perf p = new PerfPrefs.Perf();
        p.threads = sbThreads.getProgress() + 1; // slider index -> value 1..cores
        p.gpuLayers = sbGpu.getProgress();
        p.nCtx = 1024 + sbCtx.getProgress() * 1024;
        p.useGpu = swGpu.isChecked();
        p.battery = swBattery.isChecked();
        PerfPrefs.save(ctx, p);
        Toast.makeText(ctx, "Settings saved — reload the model to apply",
                Toast.LENGTH_LONG).show();
    }

    private void syncControlsToPerf() {
        int cores = Math.max(1, sbThreads.getMax());
        int t = Math.min(perf.threads, cores);
        sbThreads.setProgress(perf.threads);
        tvThreads.setText(String.valueOf(perf.threads));
        sbGpu.setProgress(perf.gpuLayers);
        tvGpu.setText(String.valueOf(perf.gpuLayers));
        int prog = Math.max(0, Math.min(7, (perf.nCtx - 1024) / 1024));
        sbCtx.setProgress(prog);
        tvCtx.setText(String.valueOf(1024 + prog * 1024));
        swGpu.setChecked(perf.useGpu);
        swBattery.setChecked(perf.battery);
        updateGpuCaption();
    }

    // ---------------------------------------------------------- widgets

    private LinearLayout card(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        return lp;
    }

    private LinearLayout.LayoutParams lpW() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Slider row: label left + mono value right; the SeekBar is added after. */
    private LinearLayout sliderRow(LinearLayout parent, String label) {
        Context ctx = getActivity();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(14);
        parent.addView(row, rlp);

        TextView name = new TextView(ctx);
        name.setText(label);
        name.setTextColor(COL_TEXT);
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView monoValue(LinearLayout row) {
        TextView v = new TextView(getActivity());
        v.setTextColor(COL_PRIMARY);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(14);
        v.setGravity(Gravity.END);
        v.setPadding(dp(8), 0, 0, 0);
        row.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    /** SeekBar whose listener updates the value label + in-memory perf state. */
    private SeekBar sliderBar(LinearLayout parent, int min, int max, int value, final String kind) {
        Context ctx = getActivity();
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(max - min);
        bar.setProgress(Math.max(min, Math.min(max, value)) - min);
        bar.getProgressDrawable().setColorFilter(COL_PRIMARY, android.graphics.PorterDuff.Mode.SRC_IN);
        bar.getThumb().setColorFilter(COL_PRIMARY, android.graphics.PorterDuff.Mode.SRC_IN);
        final int fmin = min;
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = fmin + progress;
                if ("cpu".equals(kind)) onThreadsChanged(v);
                else if ("gpu".equals(kind)) onGpuChanged(v);
                else onCtxChanged(1024 + v * 1024);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private SeekBar.OnSeekBarChangeListener sliderListener(int min, final String kind) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = min + progress;
                if ("cpu".equals(kind)) onThreadsChanged(v);
                else if ("gpu".equals(kind)) onGpuChanged(v);
                else onCtxChanged(1024 + v * 1024);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
    }

    /** Context slider: 1024..8192 step 1024 (7 steps); the label shows the real value. */
    private SeekBar sliderBarCtx(LinearLayout parent, int initialCtx) {
        Context ctx = getActivity();
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(7);
        bar.setProgress(Math.max(0, Math.min(7, (initialCtx - 1024) / 1024)));
        bar.getProgressDrawable().setColorFilter(COL_PRIMARY, android.graphics.PorterDuff.Mode.SRC_IN);
        bar.getThumb().setColorFilter(COL_PRIMARY, android.graphics.PorterDuff.Mode.SRC_IN);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                onCtxChanged(1024 + progress * 1024);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void onThreadsChanged(int v) {
        if (tvThreads != null) {
            tvThreads.setText(swBattery != null && swBattery.isChecked()
                    ? Math.max(1, v / 2) + " (battery saver)" : String.valueOf(v));
        }
        perf.threads = v;
    }

    private void onGpuChanged(int v) {
        if (tvGpu != null) tvGpu.setText(String.valueOf(v));
        perf.gpuLayers = v;
        updateGpuCaption();
    }

    private void onCtxChanged(int v) {
        if (tvCtx != null) tvCtx.setText(String.valueOf(v));
        perf.nCtx = v;
    }

    private LinearLayout switchRow(Context ctx, String label, boolean checked) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(COL_TEXT);
        t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch sw = new android.widget.Switch(ctx);
        sw.setChecked(checked);
        sw.getThumbDrawable().setColorFilter(
                checked ? COL_PRIMARY : 0xFF5B6678, android.graphics.PorterDuff.Mode.SRC_IN);
        sw.getTrackDrawable().setColorFilter(0xFF232B3A, android.graphics.PorterDuff.Mode.SRC_IN);
        row.addView(sw);
        row.setTag(sw);
        return row;
    }

    private TextView caption(Context ctx) {
        TextView t = new TextView(ctx);
        t.setTextColor(COL_SUB);
        t.setTextSize(12);
        t.setPadding(dp(4), 0, 0, 0);
        return t;
    }

    private TextView primaryButton(Context ctx, String label) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_button_primary);
        return b;
    }

    private void updateGpuCaption() {
        boolean mali = PerfPrefs.isMaliGpu();
        if (swGpu.isChecked() && mali) {
            tvGpuCaption.setText("Warning: Vulkan on Mali GPUs is unstable — may fall back to CPU");
            tvGpuCaption.setTextColor(0xFFF2A93B);
        } else if (!swGpu.isChecked()) {
            tvGpuCaption.setText("Inference runs fully on the CPU (default)");
        } else {
            tvGpuCaption.setText(perf.gpuLayers > 0
                    ? perf.gpuLayers + " model layers delegated to the GPU (Vulkan, experimental)"
                    : "GPU on but 0 layers — set GPU layers above");
        }
    }

    // ---------------------------------------------------------- bar chart

    /** Bar chart of decode tok/s for the last 5 runs — pure Canvas. */
    static final class BarChartView extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] values = new float[0];

        BarChartView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            float sd = c.getResources().getDisplayMetrics().scaledDensity;
            barPaint.setColor(COL_PRIMARY);
            dimPaint.setColor(COL_BAR_DIM);
            labelPaint.setColor(COL_SUB);
            labelPaint.setTextSize(10 * sd);
            textPaint.setColor(COL_TEXT);
            textPaint.setTextSize(10 * sd);
            textPaint.setTypeface(Typeface.MONOSPACE);
        }

        private int dpx(int v) {
            return (int) (v * getResources().getDisplayMetrics().density);
        }

        void setValues(List<PerfPrefs.BenchRecord> recs) {
            values = new float[recs.size()];
            for (int i = 0; i < recs.size(); i++) values[i] = recs.get(i).decodeTps;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int n = values.length;
            if (n == 0) {
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("No data yet", getWidth() / 2f, getHeight() / 2f, textPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);
                return;
            }
            float max = 1f;
            for (float v : values) max = Math.max(max, v);
            float pad = dpx(4);
            float slot = (getWidth() - pad * 2) / 5f;
            float barW = slot * 0.6f;
            int h = getHeight() - dpx(18);
            for (int i = 0; i < 5; i++) {
                int idx = n - 5 + i;
                if (idx < 0) continue;
                float v = values[idx];
                float bh = Math.max(dpx(2), (v / max) * (h - pad));
                float x = pad + slot * i + (slot - barW) / 2;
                canvas.drawRect(x, h - bh, x + barW, h, i == n - 1 ? barPaint : dimPaint);
                labelPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("#" + (idx + 1), x + barW / 2, h + dpx(12), labelPaint);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(fmt1(v), x + barW / 2, h - bh - dpx(3), textPaint);
            }
        }
    }

    private static String fmt1(float v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int sp(int v) {
        return (int) (v * getResources().getDisplayMetrics().scaledDensity);
    }
}
