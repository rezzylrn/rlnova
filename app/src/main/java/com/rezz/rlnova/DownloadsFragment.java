package com.rezz.rlnova;

import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * DownloadsFragment (mockup 04): "Downloading" section (4dp progress bar
 * + mono info, pause/resume, cancel), "Done" section (delete), footer
 * storage card. Polled from DownloadState every 500ms.
 */
public class DownloadsFragment extends Fragment {

    public interface Host {
        ModelCatalog catalog();

        void openLibrary();

        void deleteModel(String modelId);
    }

    private static final int[] SAMPLE_IDS = new int[0];

    private final android.os.Handler handler = new android.os.Handler();
    private LinearLayout sectionActive, sectionDone;
    private ModelCatalog catalog;
    private long lastVersion = -1;
    private boolean resumed = false;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (DownloadState.uiVersion() != lastVersion) rebuild();
            handler.postDelayed(this, 500);
        }
    };

    private TextView storageInfo;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        catalog = ((Host) getActivity()).catalog();

        ScrollView scroll = new ScrollView(getActivity());
        scroll.setBackgroundColor(0xFF0C0F14);

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), statusBarInset(), dp(16), dp(16));

        TextView title = new TextView(getActivity());
        title.setText("Downloads");
        title.setTextColor(0xFFEDF1F7);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView lblActive = sectionLabel("Downloading");
        root.addView(lblActive);
        sectionActive = new LinearLayout(getActivity());
        sectionActive.setOrientation(LinearLayout.VERTICAL);
        root.addView(sectionActive);

        TextView lblDone = sectionLabel("Completed");
        lblActive.setPadding(0, dp(16), 0, 0);
        root.addView(lblDone);
        sectionDone = new LinearLayout(getActivity());
        sectionDone.setOrientation(LinearLayout.VERTICAL);
        root.addView(sectionDone);

        // Footer: info penyimpanan
        LinearLayout foot = new LinearLayout(getActivity());
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setBackgroundResource(R.drawable.bg_card);
        foot.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView storageTitle = new TextView(getActivity());
        storageTitle.setText("Storage");
        storageTitle.setTextColor(0xFFEDF1F7);
        storageTitle.setTextSize(14);
        storageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        foot.addView(storageTitle);
        TextView si = new TextView(getActivity());
        si.setText(storageSummary());
        si.setTextColor(0xFF9AA6B8);
        si.setTextSize(12);
        LinearLayout.LayoutParams sip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sip.topMargin = dp(4);
        foot.addView(si, sip);
        storageInfo = si;
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(24);
        root.addView(foot, fp);
        sectionDone.setPadding(0, dp(8), 0, 0);
        sectionActive.setPadding(0, dp(8), 0, 0);

        scroll.addView(root);
        scroll.setFillViewport(true);
        return scroll;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        rebuild();
        handler.post(poll);
    }

    @Override
    public void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(getActivity());
        t.setText(text);
        t.setTextColor(0xFF9AA6B8);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(true);
        return t;
    }

    private void rebuild() {
        sectionActive.removeAllViews();
        sectionDone.removeAllViews();
        boolean anyActive = false, anyDone = false;
        for (ModelCatalog.Model m : new ArrayList<>(catalog.models)) {
            DownloadState st = DownloadState.get(m.id);
            if (st.status == DownloadState.DOWNLOADING || st.status == DownloadState.PAUSED) {
                sectionActive.addView(activeRow(m, st));
                anyActive = true;
            } else if (st.status == DownloadState.DONE) {
                sectionDone.addView(doneRow(m));
                anyDone = true;
            }
        }
        if (!anyActive) {
            TextView empty = new TextView(getActivity());
            empty.setText("No active downloads.");
            empty.setTextColor(0xFF5B6678);
            empty.setTextSize(14);
            empty.setPadding(0, dp(4), 0, 0);
            sectionActive.addView(empty);
        }
        if (!anyDone) {
            TextView empty = new TextView(getActivity());
            empty.setText("No completed downloads yet.");
            empty.setTextColor(0xFF5B6678);
            empty.setTextSize(14);
            empty.setPadding(0, dp(8), 0, 0);
            sectionDone.addView(empty);
        }
        if (storageInfo != null) storageInfo.setText(storageSummary());
    }

    private View activeRow(final ModelCatalog.Model m, final DownloadState st) {
        Context c = getActivity();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        int p = dp(16);
        row.setPadding(p, p, p, p);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setText(m.name);
        name.setTextColor(0xFFEDF1F7);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(name);
        TextView status = new TextView(c);
        status.setText(st.status == DownloadState.PAUSED ? "Paused" : "Downloading");
        status.setTextColor(st.status == DownloadState.PAUSED ? 0xFFE5A13D : 0xFF5B8DEF);
        status.setTextSize(12);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(head);

        ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgress(DownloadState.progressPercent(st));
        bar.setProgressDrawable(getResources().getDrawable(R.drawable.progress_download));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        bp.topMargin = dp(10);
        row.addView(bar, bp);

        TextView info = new TextView(c);
        String eta = DownloadState.etaText(st);
        info.setText(DownloadState.formatBytes(st.downloaded) + " of "
                + DownloadState.formatBytes(st.total)
                + " · " + DownloadState.progressPercent(st) + "%"
                + (st.status == DownloadState.DOWNLOADING && st.speed != null
                   ? " · " + st.speed : "")
                + (eta != null ? " · " + eta : ""));
        info.setTextColor(0xFF7C8AA5);
        info.setTextSize(12);
        info.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(6);
        row.addView(info, ip);

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView toggle = new TextView(c);
        toggle.setText(st.status == DownloadState.PAUSED ? "Resume" : "Pause");
        toggle.setPadding(dp(16), 0, dp(16), 0);
        toggle.setMinWidth(dp(48));
        toggle.setMinHeight(dp(48));
        toggle.setGravity(Gravity.CENTER);
        toggle.setTextColor(0xFF5B8DEF);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setOnClickListener(v -> {
            Intent i = new Intent(getActivity(), DownloadService.class);
            i.setAction(st.status == DownloadState.PAUSED
                    ? DownloadService.ACTION_RESUME : DownloadService.ACTION_PAUSE);
            i.putExtra(DownloadService.EXTRA_ID, m.id);
            Context cc = getActivity();
            if (Build.VERSION.SDK_INT >= 26) cc.startForegroundService(i);
            else cc.startService(i);
        });
        actions.addView(toggle);

        TextView cancel = new TextView(c);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFFE5484D);
        cancel.setPadding(dp(16), 0, 0, 0);
        cancel.setMinHeight(dp(48));
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(v -> {
            Intent i = new Intent(getActivity(), DownloadService.class);
            i.setAction(DownloadService.ACTION_CANCEL);
            i.putExtra(DownloadService.EXTRA_ID, m.id);
            Context cc = getActivity();
            if (Build.VERSION.SDK_INT >= 26) cc.startForegroundService(i);
            else cc.startService(i);
        });
        actions.addView(cancel);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(8);
        row.addView(actions, ap);
        return row;
    }

    private View doneRow(final ModelCatalog.Model m) {
        Context c = getActivity();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(16);
        row.setPadding(p, p, p, p);

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(c);
        name.setText(m.name);
        name.setTextColor(0xFFEDF1F7);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(name);
        TextView meta = new TextView(c);
        meta.setText(DeviceInfo.formatBytes(m.sizeBytes) + " · ready offline");
        meta.setTextColor(0xFF7C8AA5);
        meta.setTextSize(12);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(2);
        textCol.addView(meta, mp);
        row.addView(textCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = new TextView(c);
        del.setText("Delete");
        del.setTextColor(0xFFE5484D);
        del.setTypeface(Typeface.DEFAULT_BOLD);
        del.setTextSize(14);
        del.setPadding(dp(16), 0, 0, 0);
        del.setMinHeight(dp(48));
        del.setGravity(Gravity.CENTER);
        del.setOnClickListener(v -> ((Host) getActivity()).deleteModel(m.id));
        row.addView(del, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private String storageSummary() {
        File dir = new File(getActivity().getFilesDir(), "models");
        long used = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) used += f.length();
        StatFs stat = new StatFs(getActivity().getFilesDir().getPath());
        long free = stat.getAvailableBytes();
        return "Models use " + DeviceInfo.formatBytes(used)
                + " · " + DeviceInfo.formatBytes(free) + " free";
    }

    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
