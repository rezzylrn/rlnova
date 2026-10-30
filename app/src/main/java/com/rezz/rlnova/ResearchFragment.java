package com.rezz.rlnova;

import android.app.Fragment;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rezz.rlnova.research.CitationRenderer;
import com.rezz.rlnova.research.ResearchDatabase;
import com.rezz.rlnova.research.ResearchIndex;
import com.rezz.rlnova.research.ResearchPrompt;
import com.rezz.rlnova.research.ResearchSession;

import java.util.ArrayList;
import java.util.List;

/**
 * ResearchFragment (v1.2.0 — vertical slice "Offline Research"):
 *  - Entry/tab riset terpisah; tab Chat lama TIDAK disentuh.
 *  - Mode task: Jelaskan / Bandingkan / Sintesis (chip pilihan; auto-detect
 *    bila tidak dipilih).
 *  - Jawaban dirender dengan sitasi [N] yang HANYA berasal dari evidence yang
 *    diberikan ke model (ResearchDatabase.evidence_ids); tap sitasi membuka
 *    dialog judul sumber + cuplikan chunk.
 *  - Bukti kurang -> pesan jujur (row declined), tanpa fallback web.
 *  - Seluruh jalur tanpa jaringan: retrieval dari assets, generation lokal.
 */
public class ResearchFragment extends Fragment {

    private LinearLayout list;
    private EditText input;
    private TextView sendBtn;
    private TextView chipExplain, chipCompare, chipSynth;
    private String selectedTask = "";       // "" = auto
    private ResearchIndex index;            // dimuat lazily di background
    private final android.os.Handler ui = new android.os.Handler();

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        android.content.Context ctx = getActivity();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0C0F14);

        // ---- header
        TextView title = new TextView(ctx);
        title.setText("Research");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFEDF1F7);
        title.setPadding(dp(16), statusBarInset() + dp(16), dp(16), dp(4));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(ctx);
        subtitle.setText("Jawaban dari paket pengetahuan lokal — tanpa internet");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xFF9AA6B8);
        subtitle.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- chips task
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(16), 0, dp(16), dp(8));
        chipExplain = chip(ctx, "Jelaskan");
        chipCompare = chip(ctx, "Bandingkan");
        chipSynth = chip(ctx, "Sintesis");
        chips.addView(chipExplain);
        chips.addView(chipCompare, lParams(dp(8)));
        chips.addView(chipSynth, lParams(dp(8)));
        root.addView(chips);

        chipExplain.setOnClickListener(v -> selectTask(""));
        chipCompare.setOnClickListener(v -> selectTask(ResearchSession.MODE_COMPARE));
        chipSynth.setOnClickListener(v -> selectTask(ResearchSession.MODE_SYNTHESIZE));
        selectTask("");

        // ---- list Q&A
        FrameWrap body = new FrameWrap(ctx);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(8));
        ScrollView sc = new ScrollView(ctx);
        sc.setFillViewport(true);
        sc.addView(list);
        body.addView(sc);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- composer
        LinearLayout composer = new LinearLayout(ctx);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setBackgroundColor(0xFF131822);
        composer.setPadding(dp(8), dp(8), dp(8), dp(8));
        input = new EditText(ctx);
        input.setHint("Tanya dari paket lokal…");
        input.setTextColor(0xFFEDF1F7);
        input.setHintTextColor(0xFF5A6472);
        input.setTextSize(14);
        input.setBackgroundResource(R.drawable.bg_composer);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setMaxLines(4);
        composer.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = new TextView(ctx);
        sendBtn.setText("Ask");
        sendBtn.setTextColor(0xFFFFFFFF);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setBackgroundResource(R.drawable.bg_button_primary);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(64),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(8);
        sendBtn.setPadding(0, dp(10), 0, dp(10));
        composer.addView(sendBtn, sp);
        sendBtn.setOnClickListener(v -> onAsk());
        root.addView(composer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        reload();
        loadIndexInBackground();
        return root;
    }

    // --------------------------------------------------------------- task chips

    private void selectTask(String task) {
        selectedTask = task;
        int on = 0xFF5B8DEF, off = 0xFF232B38;
        chipExplain.getBackground().setColorFilter(
                task.isEmpty() ? on : off, android.graphics.PorterDuff.Mode.SRC_ATOP);
        chipCompare.getBackground().setColorFilter(
                ResearchSession.MODE_COMPARE.equals(task) ? on : off,
                android.graphics.PorterDuff.Mode.SRC_ATOP);
        chipSynth.getBackground().setColorFilter(
                ResearchSession.MODE_SYNTHESIZE.equals(task) ? on : off,
                android.graphics.PorterDuff.Mode.SRC_ATOP);
    }

    private TextView chip(android.content.Context ctx, String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(0xFFEDF1F7);
        t.setBackgroundResource(R.drawable.bg_filter_chip);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        return t;
    }

    private static LinearLayout.LayoutParams lParams(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = leftMargin;
        return lp;
    }

    // --------------------------------------------------------------- ask

    private void onAsk() {
        if (!isAdded()) return;
        android.app.Activity act = getActivity();
        if (GenerationService.isBusy()) {
            android.widget.Toast.makeText(act,
                    "A generation is already running", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String q = input.getText() == null ? "" : input.getText().toString().trim();
        if (q.isEmpty()) return;
        ModelCatalog.Model m = pickModel(act);
        if (m == null) {
            android.widget.Toast.makeText(act,
                    "Download a model first in the Library tab", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        input.setText("");
        String task = selectedTask.isEmpty()
                ? ResearchSession.detectTask(q) : selectedTask;

        // insert row dulu (answer kosong), lalu generation mengisinya
        long rid = ResearchDatabase.get(act).insertQuestion(task, q, "",
                new ArrayList<>(), false, null, null, null);
        reload();
        GenerationService.startResearch(act, rid, q, task, m);
        enterGeneratingUi();
    }

    private ModelCatalog.Model pickModel(android.app.Activity act) {
        ModelCatalog catalog;
        try {
            catalog = ModelCatalog.load(act);
        } catch (Exception e) {
            return null;
        }
        for (ModelCatalog.Model m : catalog.models) {
            if (((Host) act).isModelDownloaded(m.id)) return m;
        }
        return null;
    }

    public interface Host {
        boolean isModelDownloaded(String modelId);
    }

    private void enterGeneratingUi() {
        sendBtn.setText("…");
        sendBtn.setAlpha(0.5f);
        sendBtn.setEnabled(false);
    }

    private void exitGeneratingUi() {
        sendBtn.setText("Ask");
        sendBtn.setAlpha(1f);
        sendBtn.setEnabled(true);
    }

    // --------------------------------------------------------------- render

    /** Re-render list dari DB (background thread aman). */
    public void reload() {
        if (!isAdded()) return;
        final android.app.Activity act = getActivity();
        new Thread(() -> {
            final List<ResearchDatabase.Question> qs =
                    ResearchDatabase.get(act).listQuestions(30);
            ui.post(() -> renderList(act, qs));
        }, "rlnova-research-reload").start();
    }

    private void renderList(android.app.Activity act,
                            List<ResearchDatabase.Question> qs) {
        if (!isAdded() || list == null) return;
        list.removeAllViews();
        ResearchIndex ix = index;
        boolean generating = GenerationService.isBusy()
                && GenerationService.activeResearchId != -1;
        if (generating) {
            enterGeneratingUi();
            TextView live = new TextView(act);
            live.setText("Menjawab…");
            live.setTextSize(12);
            live.setTextColor(0xFF9AA6B8);
            live.setPadding(dp(4), dp(8), dp(4), dp(8));
            list.addView(live);
        } else {
            exitGeneratingUi();
        }
        if (qs.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("Belum ada pertanyaan. Paket aktif: "
                    + (ix == null ? "…" : ix.manifest.name)
                    + " (" + (ix == null ? "…" : ix.chunkCount() + " potongan)"));
            empty.setTextSize(13);
            empty.setTextColor(0xFF5A6472);
            empty.setPadding(dp(4), dp(24), dp(4), dp(24));
            list.addView(empty);
            return;
        }
        // tampilkan terlama -> terbaru
        for (int i = qs.size() - 1; i >= 0; i--) {
            list.addView(qaCard(act, ix, qs.get(i)));
        }
        ((ScrollView) list.getParent()).post(() ->
                ((ScrollView) list.getParent()).fullScroll(View.FOCUS_DOWN));
    }

    private View qaCard(android.app.Activity act, ResearchIndex ix,
                        ResearchDatabase.Question q) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = dp(12);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);

        // header: task chip + question
        TextView head = new TextView(act);
        head.setText(taskLabel(q.task) + "  ·  " + q.question);
        head.setTextSize(14);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(0xFFEDF1F7);
        card.addView(head);

        if (q.declined) {
            TextView a = new TextView(act);
            a.setText(q.answer);
            a.setTextSize(13);
            a.setTextColor(0xFFC8A15A);
            a.setPadding(0, dp(6), 0, 0);
            card.addView(a);
            return card;
        }

        // answer dengan sitasi — HANYA dari evidence_ids yang disimpan
        TextView a = new TextView(act);
        a.setTextSize(13);
        a.setTextColor(0xFFC9D2DE);
        a.setPadding(0, dp(6), 0, 0);
        if (q.answer == null || q.answer.isEmpty()) {
            a.setText("…");
            a.setAlpha(0.6f);
            card.addView(a);
            return card;
        }
        List<ResearchPrompt.Evidence> given = evidenceOf(ix, q);
        List<CitationRenderer.Span> spans = CitationRenderer.render(q.answer, given);
        StringBuilder shown = new StringBuilder();
        // Sederhana dulu: gabung teks, sitasi jadi superscript kecil [N] (inline)
        // — sitasi valid ditandai; ID tidak valid dibuang oleh renderer.
        for (CitationRenderer.Span s : spans) {
            if (s.marker > 0) {
                shown.append("[").append(s.marker).append("]");
            } else {
                shown.append(s.text);
            }
        }
        a.setText(shown.toString());
        card.addView(a);

        // baris sumber (tap -> dialog)
        if (!given.isEmpty()) {
            TextView src = new TextView(act);
            StringBuilder sb = new StringBuilder("Sumber: ");
            for (int i2 = 0; i2 < given.size(); i2++) {
                if (i2 > 0) sb.append(", ");
                sb.append("[").append(given.get(i2).marker).append("] ")
                  .append(titleOf(ix, given.get(i2).chunkId));
            }
            src.setText(sb.toString());
            src.setTextSize(11);
            src.setTextColor(0xFF5B8DEF);
            src.setPadding(0, dp(8), 0, 0);
            src.setOnClickListener(v -> showSourcesDialog(act, ix, given));
            card.addView(src);
        }

        // footer stats
        if (q.tokens != null && q.tokens > 0) {
            TextView st = new TextView(act);
            st.setText(q.tokens + " tokens · " + String.format(java.util.Locale.US,
                    "%.1f", q.tokPerSec == null ? 0 : q.tokPerSec) + " tok/s");
            st.setTextSize(10);
            st.setTextColor(0xFF5A6472);
            st.setPadding(0, dp(4), 0, 0);
            card.addView(st);
        }
        return card;
    }

    private void showSourcesDialog(android.app.Activity act, ResearchIndex ix,
                                   List<ResearchPrompt.Evidence> given) {
        StringBuilder msg = new StringBuilder();
        for (ResearchPrompt.Evidence e : given) {
            msg.append("[").append(e.marker).append("] ")
               .append(titleOf(ix, e.chunkId)).append("\n")
               .append(e.text).append("\n\n");
        }
        new android.app.AlertDialog.Builder(act)
                .setTitle("Sumber jawaban")
                .setMessage(msg.toString())
                .setPositiveButton("[Tutup]", (d, w) -> d.dismiss())
                .show();
    }

    private String titleOf(ResearchIndex ix, String chunkId) {
        if (ix == null) return chunkId;
        int h = chunkId.lastIndexOf("#c");
        String docId = h > 0 ? chunkId.substring(0, h) : chunkId;
        com.rezz.rlnova.research.PackageManifest.Doc d = ix.doc(docId);
        return d != null ? d.title : docId;
    }

    private List<ResearchPrompt.Evidence> evidenceOf(ResearchIndex ix,
                                                     ResearchDatabase.Question q) {
        List<ResearchPrompt.Evidence> out = new ArrayList<>();
        if (ix == null || q.evidenceIds == null) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(q.evidenceIds);
            for (int i = 0; i < arr.length(); i++) {
                String cid = arr.optString(i, "");
                com.rezz.rlnova.research.Chunker.Chunk c = ix.chunk(cid);
                if (c == null) continue;    // ID tidak dikenal — diabaikan
                com.rezz.rlnova.research.PackageManifest.Doc d = ix.doc(c.docId);
                out.add(new ResearchPrompt.Evidence(i + 1, cid,
                        d != null ? d.title : c.docId, c.text));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String taskLabel(String task) {
        if (ResearchSession.MODE_COMPARE.equals(task)) return "Bandingkan";
        if (ResearchSession.MODE_SYNTHESIZE.equals(task)) return "Sintesis";
        return "Jelaskan";
    }

    // --------------------------------------------------------------- index load

    private void loadIndexInBackground() {
        if (!isAdded()) return;
        final android.app.Activity act = getActivity();
        new Thread(() -> {
            try {
                ResearchIndex ix = ResearchIndex.load(act);
                index = ix;
            } catch (Exception e) {
                index = null;
            }
            ui.post(() -> reload());
        }, "rlnova-research-index").start();
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
        // streaming update saat generating
        GenerationState.get().addListener(updateListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        GenerationState.get().removeListener(updateListener);
    }

    private final GenerationState.UpdateListener updateListener =
            new GenerationState.UpdateListener() {
                @Override public void onGenerationUpdate() {
                    if (!isAdded()) return;
                    GenerationState g = GenerationState.get();
                    if (!g.generating && GenerationService.activeResearchId == -1) {
                        // generation selesai — reload list
                        ui.post(() -> reload());
                    }
                }
            };

    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
        return dp(8) + h;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** FrameLayout sederhana (helper agar tidak import ambiguity). */
    private static class FrameWrap extends android.widget.FrameLayout {
        FrameWrap(android.content.Context c) { super(c); }
    }
}
