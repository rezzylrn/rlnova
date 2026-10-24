package com.rezz.rlnova;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Map;

/**
 * ModelLibraryFragment (mockup 02): header "Model Library" + filter chips
 * (All/Light/Medium/Heavy), list of model cards (name, tier chip, size,
 * download status). RecyclerView is flattened into a LinearLayout inside a
 * ScrollView because this APK has no external dependencies.
 *
 * BUG3 (v1.1.1): card views di-cache per modelId. Poll 500ms TIDAK membangun
 * ulang list — hanya UPDATE IN PLACE (progress bar, info text, tombol) untuk
 * card yang state-nya berubah. rebuildList() penuh HANYA saat set model
 * berubah (ganti filter, download selesai, delete, resume pertama).
 * Animasi enter (fade+rise) hanya jalan saat card view pertama dibuat.
 */
public class ModelLibraryFragment extends android.app.Fragment {

    public interface Host {
        DeviceInfo deviceInfo();

        ModelCatalog catalog();

        /** True jika file model (id.gguf) sudah ada di penyimpanan lokal. */
        boolean isModelDownloaded(String modelId);

        void startDownload(ModelCatalog.Model m);

        void openDownloads();

        void gotoChat();

        void gotoOptimization();
    }

    private static final int FILTER_ALL = 0, FILTER_LOW = 1, FILTER_MID = 2, FILTER_HIGH = 3;

    private final android.os.Handler handler = new android.os.Handler();
    private TextView chipAll, chipLow, chipMid, chipHigh;
    private LinearLayout list;
    private ModelCatalog catalog;
    private DeviceInfo device;
    private int filter = FILTER_ALL;
    private long lastVersion = -1;
    private boolean resumed = false;

    /** Kartu per modelId — untuk update in-place tanpa rebuild. */
    private final Map<String, View> cardViews = new java.util.HashMap<>();
    /** Progress bar per modelId (null kalau card tidak sedang menampilkan progress). */
    private final Map<String, ProgressBar> cardProgressBars = new java.util.HashMap<>();
    /** Info text per modelId (downloaded/total · speed). */
    private final Map<String, TextView> cardInfoTexts = new java.util.HashMap<>();
    /** State aksi terakhir yang dirender per card (untuk deteksi perubahan bentuk). */
    private final Map<String, Integer> cardShapes = new java.util.HashMap<>();

    private static final int SHAPE_IDLE = 0;      // Download / Locked
    private static final int SHAPE_PROGRESS = 1;  // bar + info + pause/cancel
    private static final int SHAPE_DONE = 2;      // On this device + Delete

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (DownloadState.uiVersion() != lastVersion) onDownloadStateVersionBump();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context ctx = getActivity();
        int dp8 = dp(8);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(0xFF0C0F14);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int dp16 = dp(16);
        scroll.setPadding(0, statusBarInset(), 0, 0);
        root.setPadding(dp(16), dp8, dp(16), dp(88)); // ruang BottomNav

        // Header "Model Library" + model-count subtitle
        TextView title = new TextView(ctx);
        title.setText("Model Library");
        title.setTextColor(0xFFEDF1F7);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        catalog = ((Host) getActivity()).catalog();
        device = ((Host) getActivity()).deviceInfo();

        TextView subtitle = new TextView(ctx);
        int n = catalog.models.size();
        subtitle.setText(n + " models available for offline download");
        subtitle.setTextColor(0xFF9AA6B8);
        subtitle.setTextSize(12);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(2);
        root.addView(subtitle, sp);

        // Filter chips (HorizontalScrollView — scrollable if they overflow on
        // small screens; P1/DESIGN-RESEARCH)
        HorizontalScrollView chipsScroll = new HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setFillViewport(true);
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, 0, 0, 0);
        chipAll = chip("All");
        chipLow = chip("Light");
        chipMid = chip("Medium");
        chipHigh = chip("Heavy");
        chipAll.setOnClickListener(v -> setFilter(FILTER_ALL));
        chipLow.setOnClickListener(v -> setFilter(FILTER_LOW));
        chipMid.setOnClickListener(v -> setFilter(FILTER_MID));
        chipHigh.setOnClickListener(v -> setFilter(FILTER_HIGH));
        chips.addView(chipAll);
        chips.addView(chipLow);
        chips.addView(chipMid);
        chips.addView(chipHigh);
        chipsScroll.addView(chips);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(12); cp.bottomMargin = dp(12);
        root.addView(chipsScroll, cp);

        // List card model
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        scroll.addView(root);
        return scroll;
    }

    @Override
    public void onAttach(android.app.Activity activity) {
        super.onAttach(activity);
        // Catalog/device references come from the Host each time
        // (fragment is recreated on tab switch; the activity persists).
    }

    private void setFilter(int f) {
        filter = f;
        refreshChipStyles();
        rebuildList(); // set model berubah → full rebuild sah
    }

    private void refreshChipStyles() {
        styleChip(chipAll, filter == FILTER_ALL);
        styleChip(chipLow, filter == FILTER_LOW);
        styleChip(chipMid, filter == FILTER_MID);
        styleChip(chipHigh, filter == FILTER_HIGH);
    }

    private void styleChip(TextView chip, boolean active) {
        chip.setBackgroundResource(R.drawable.bg_filter_chip);
        chip.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        chip.setTextColor(active ? 0xFFEDF1F7 : 0xFF9AA6B8);
        chip.setAlpha(active ? 1f : 0.55f);
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        lastVersion = -1;      // paksa sinkron pertama
        rebuildList();
        handler.post(poll);
    }

    @Override
    public void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    /**
     * BUG3: uiVersion naik (download progress ~2x/dtk) TIDAK membangun ulang
     * list. Cukup update in-place card yang terdampak.
     */
    private void onDownloadStateVersionBump() {
        lastVersion = DownloadState.uiVersion();
        for (ModelCatalog.Model m : new ArrayList<>(catalog.models)) {
            View card = cardViews.get(m.id);
            if (card == null) continue; // card tidak tampil (filter) — abaikan
            DownloadState st = DownloadState.get(m.id);
            int shape = shapeFor(m, st);
            Integer prev = cardShapes.get(m.id);
            if (prev == null || prev != shape) {
                // Bentuk aksi berubah (mis. DOWNLOADING→DONE, IDLE→DOWNLOADING):
                // rebuild SATU card ini saja, tanpa animasi.
                replaceCardInPlace(m, false);
            } else if (shape == SHAPE_PROGRESS) {
                // bentuk sama, update angka/bar saja — in-place, tanpa animasi
                ProgressBar bar = cardProgressBars.get(m.id);
                TextView info = cardInfoTexts.get(m.id);
                if (bar != null) bar.setProgress(DownloadState.progressPercent(st));
                if (info != null) {
                    info.setText(DownloadState.formatBytes(st.downloaded) + " of "
                            + DownloadState.formatBytes(st.total)
                            + (st.speed != null && st.status == DownloadState.DOWNLOADING
                               ? " · " + st.speed : ""));
                }
            }
        }
    }

    private int shapeFor(ModelCatalog.Model m, DownloadState st) {
        boolean downloaded = ((Host) getActivity()).isModelDownloaded(m.id);
        if (downloaded || st.status == DownloadState.DONE) return SHAPE_DONE;
        if (st.status == DownloadState.DOWNLOADING || st.status == DownloadState.PAUSED)
            return SHAPE_PROGRESS;
        return SHAPE_IDLE;
    }

    /** Ganti view satu card dengan yang baru (tanpa animasi enter). */
    private void replaceCardInPlace(ModelCatalog.Model m, boolean animate) {
        int idx = list.indexOfChild(cardViews.get(m.id));
        View fresh = buildModelCard(m, false);
        if (idx >= 0) {
            list.removeViewAt(idx);
            list.addView(fresh, idx);
        } else {
            list.addView(fresh);
        }
        registerCard(m, fresh, animate);
    }

    private void rebuildList() {
        list.removeAllViews();
        cardViews.clear();
        cardShapes.clear();
        cardProgressBars.clear();
        cardInfoTexts.clear();
        for (ModelCatalog.Model m : new ArrayList<>(catalog.models)) {
            boolean tierMatch =
                    (filter == FILTER_ALL)
                            || (filter == FILTER_LOW && "LOW".equals(m.tier))
                            || (filter == FILTER_MID && "MID".equals(m.tier))
                            || (filter == FILTER_HIGH && "HIGH".equals(m.tier));
            if (!tierMatch) continue;
            View card = buildModelCard(m);
            cardViews.put(m.id, card);
            cardShapes.put(m.id, shapeFor(m, DownloadState.get(m.id)));
            // P3 enter: fade+rise HANYA saat card view pertama kali dibuat
            card.setAlpha(0f);
            card.setTranslationY(dp(12));
            card.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
                    .start();
            list.addView(card);
        }
    }

    private View buildModelCard(final ModelCatalog.Model m) {
        return buildModelCard(m, true);
    }

    private View buildModelCard(final ModelCatalog.Model m, boolean animate) {
        Context ctx = getActivity();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_ripple);
        int p = dp(16);
        card.setPadding(p, p, p, p);

        // Row 1: name + tier chip
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(m.name);
        name.setTextColor(0xFFEDF1F7);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(name);

        TextView tierChip = new TextView(ctx);
        tierChip.setText(ModelCatalog.tierLabel(m.tier));
        int tierColor = tierColor(m.tier);
        tierChip.setTextColor(tierColor);
        tierChip.setBackgroundResource(R.drawable.bg_chip_pill);
        tierChip.setTextSize(12);
        tierChip.setTypeface(Typeface.DEFAULT_BOLD);
        tierChip.setPadding(dp(10), dp(3), dp(10), dp(3));
        head.addView(tierChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(head);

        // Row 2: description
        TextView desc = new TextView(ctx);
        desc.setText(m.description);
        desc.setTextColor(0xFF9AA6B8);
        desc.setTextSize(14);
        desc.setMaxLines(2);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(6);
        card.addView(desc, dlp);

        // Row 3: meta (size · quant · context)
        TextView meta = new TextView(ctx);
        meta.setText(DeviceInfo.formatBytes(m.sizeBytes) + " · " + m.quant
                + " · " + m.context + " ctx");
        meta.setTextColor(0xFF7C8AA5);
        meta.setTextSize(12);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(4);
        card.addView(meta, mp);

        // Row 4: actions depending on download state / tier
        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        card.addView(actionRow, alp);
        fillActionRow(actionRow, m);
        registerCard(m, card, animate);
        return card;
    }

    /** Simpan ref card + bar + info untuk update in-place. */
    private void registerCard(ModelCatalog.Model m, View card, boolean animate) {
        cardViews.put(m.id, card);
        cardShapes.put(m.id, shapeFor(m, DownloadState.get(m.id)));
        // progress bar / info text diambil via traversal kecil pada action row
        cardProgressBars.remove(m.id);
        cardInfoTexts.remove(m.id);
        LinearLayout row = findActionRow(card);
        if (row != null) {
            ProgressBar bar = findProgressBar(row);
            TextView info = findInfoText(row);
            if (bar != null) cardProgressBars.put(m.id, bar);
            if (info != null) cardInfoTexts.put(m.id, info);
        }
    }

    private LinearLayout findActionRow(View card) {
        if (card instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) card;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                View c = vg.getChildAt(i);
                if (c instanceof LinearLayout) return (LinearLayout) c;
            }
        }
        return null;
    }

    private ProgressBar findProgressBar(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof ProgressBar) return (ProgressBar) c;
            if (c instanceof ViewGroup) {
                ProgressBar b = findProgressBar((ViewGroup) c);
                if (b != null) return b;
            }
        }
        return null;
    }

    private TextView findInfoText(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof TextView) {
                String s = ((TextView) c).getText().toString();
                if (s.contains(" of ")) return (TextView) c;
            } else if (c instanceof ViewGroup) {
                TextView t = findInfoText((ViewGroup) c);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** Bangun ulang area aksi card sesuai state unduh saat ini. */
    private void fillActionRow(final LinearLayout row, final ModelCatalog.Model m) {
        row.removeAllViews();
        boolean fits = device != null && device.tier() != null
                && tierRank(m.tier) <= tierRank(device.tier().name())
                && catalog.fits(m, device);
        boolean downloaded = ((Host) getActivity()).isModelDownloaded(m.id);
        DownloadState st = DownloadState.get(m.id);

        if (downloaded || st.status == DownloadState.DONE) {
            TextView done = new TextView(getActivity());
            done.setText("On this device");
            done.setTextColor(0xFF34C77B);
            done.setTextSize(14);
            done.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(done, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView del = new TextView(getActivity());
            del.setText("Delete");
            del.setTextColor(0xFFE5484D);
            del.setTextSize(14);
            del.setTypeface(Typeface.DEFAULT_BOLD);
            del.setPadding(dp(16), 0, 0, 0);
            del.setMinHeight(dp(48));
            del.setGravity(Gravity.CENTER);
            del.setOnClickListener(v -> ((LibraryActivity) getActivity()).deleteModel(m.id));
            row.addView(del, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        if (st.status == DownloadState.DOWNLOADING || st.status == DownloadState.PAUSED) {
            // Progress + pause/resume + cancel buttons
            LinearLayout progressCol = new LinearLayout(getActivity());
            progressCol.setOrientation(LinearLayout.VERTICAL);
            ProgressBar bar = new ProgressBar(getActivity(), null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setProgress(DownloadState.progressPercent(st));
            bar.setProgressDrawable(getResources().getDrawable(R.drawable.progress_download));
            bar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
            progressCol.addView(bar);
            TextView info = new TextView(getActivity());
            info.setText(DownloadState.formatBytes(st.downloaded) + " of "
                    + DownloadState.formatBytes(st.total)
                    + (st.speed != null && st.status == DownloadState.DOWNLOADING
                       ? " · " + st.speed : ""));
            info.setTextColor(0xFF7C8AA5);
            info.setTextSize(12);
            info.setTypeface(Typeface.MONOSPACE);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ip.topMargin = dp(4);
            progressCol.addView(info, ip);
            row.addView(progressCol, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView toggle = new TextView(getActivity());
            toggle.setText(st.status == DownloadState.PAUSED ? "Resume" : "Pause");
            toggle.setTextColor(0xFF5B8DEF);
            toggle.setTypeface(Typeface.DEFAULT_BOLD);
            toggle.setPadding(dp(16), 0, dp(16), 0);
            toggle.setMinWidth(dp(48));
            toggle.setMinHeight(dp(48));
            toggle.setGravity(Gravity.CENTER);
            toggle.setOnClickListener(v -> {
                Intent i = new Intent(getActivity(), DownloadService.class);
                i.setAction(st.status == DownloadState.PAUSED
                        ? DownloadService.ACTION_RESUME : DownloadService.ACTION_PAUSE);
                i.putExtra(DownloadService.EXTRA_ID, m.id);
                Context c = getActivity();
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            });
            row.addView(toggle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView cancel = new TextView(getActivity());
            cancel.setText("Cancel");
            cancel.setTextColor(0xFFE5484D);
            cancel.setPadding(dp(16), 0, 0, 0);
            cancel.setMinHeight(dp(48));
            cancel.setGravity(Gravity.CENTER);
            cancel.setOnClickListener(v -> {
                Intent i = new Intent(getActivity(), DownloadService.class);
                i.setAction(DownloadService.ACTION_CANCEL);
                i.putExtra(DownloadService.EXTRA_ID, m.id);
                Context c = getActivity();
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            });
            row.addView(cancel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        if (!fits) {
            // Locked: model above device tier/RAM — show reason, not just a chip
            String lockNote = device != null
                    ? "Requires " + m.minRamGb + " GB RAM (this device has "
                      + DeviceInfo.formatBytes(device.totalRamBytes) + ")"
                    : "";
            TextView lock = new TextView(getActivity());
            lock.setText("Locked\n" + lockNote);
            lock.setTextColor(0xFF5B6678);
            lock.setTextSize(12);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(lock, llp);
            return;
        }

        // Download
        TextView dl = new TextView(getActivity());
        dl.setText("Download");
        dl.setTextColor(Color.WHITE);
        dl.setTypeface(Typeface.DEFAULT_BOLD);
        dl.setTextSize(14);
        dl.setBackgroundResource(R.drawable.bg_button_primary);
        dl.setPadding(dp(20), dp(10), dp(20), dp(10));
        dl.setOnClickListener(v -> ((Host) getActivity()).startDownload(m));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(dl, dlp);

        if (st.status == DownloadState.FAILED) {
            TextView err = new TextView(getActivity());
            err.setText("Failed: " + st.error);
            err.setTextColor(0xFFE5484D);
            err.setTextSize(12);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            elp.leftMargin = dp(12);
            row.addView(err, elp);
        }
    }

    private int tierRank(String t) {
        if ("LOW".equals(t)) return 0;
        if ("MID".equals(t)) return 1;
        return 2;
    }

    private int tierColor(String tier) {
        switch (tier) {
            case "LOW": return 0xFF34C77B;
            case "MID": return 0xFF5B8DEF;
            default: return 0xFFA78BFA;
        }
    }

    private TextView chip(String label) {
        TextView c = new TextView(getActivity());
        c.setText(label);
        c.setTextSize(14);
        c.setBackgroundResource(R.drawable.bg_filter_chip);
        c.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private int statusBarInset() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
        return dp(8) + h;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
