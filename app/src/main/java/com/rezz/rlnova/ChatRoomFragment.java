package com.rezz.rlnova;

import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import com.k2fsa.sherpa.onnx.GeneratedAudio;

/**
 * ChatRoomFragment (mockup 03, chat room page).
 *
 * v1.1.3: fragment TIDAK lagi memiliki/menjalankan engine — generation
 * dimiliki GenerationService (foreground service). Fragment hanya:
 *   - memanggil GenerationService.start(...) dari tombol Send/Retry,
 *   - subscribe GenerationState (listener) untuk streaming UI live,
 *   - saat generation selesai, render ulang pesan final dari DB
 *     (insert pesan assistant dilakukan SERVICE, bukan fragment),
 *   - tombol Stop memanggil GenerationService.requestStop().
 *
 * Tetap dipertahankan: think collapsible (ThinkSplit), stats
 * "NamaModel · N tokens · X tok/s", long-press copy (jawaban), selector
 * model, Retry tanpa duplikasi pesan user, satu generation dalam satu waktu.
 */
public class ChatRoomFragment extends Fragment {

    public interface Host {
        long createConversation(String modelId, String systemPrompt);
        ChatDatabase.Conversation getConversation(long id);
        ModelCatalog.Model getModel(String modelId);
        DeviceInfo deviceInfo();
        void onChatClosed();
        void onConversationListChanged();
        // Fitur A:
        java.util.List<ModelCatalog.Model> allModels();
        boolean isModelDownloaded(String modelId);
        void startDownloadFromChat(ModelCatalog.Model m);
    }

    /** Epoch config: naik tiap kali pengaturan performa disimpan (PerfPrefs.save). */
    public static long perfEpoch() {
        return PerfPrefs.saveEpochValue();
    }

    /** Engine aktif = milik GenerationService (dipakai benchmark panel Optimasi). */
    public static LlamaEngine sharedEngine() {
        return GenerationService.sharedEngine();
    }

    private long convId = -1;
    private ChatDatabase.Conversation conv;
    private ModelCatalog.Model model;

    private LinearLayout messagesView;
    private ScrollView scroller;
    private LinearLayout footer;
    private TextView footerStats;
    private TextView statusDotLabel;
    private View statusDot; // assigned in onCreateView

    /** Ukuran ekspektasi katalog (bila diketahui); 0 = tidak diketahui. */
    private long modelSizeBytesExpect() {
        if (model == null) return 0;
        if (model.sizeBytes > 0) return model.sizeBytes;
        DownloadState st = DownloadState.get(model.id);
        return st != null && st.total > 0 ? st.total : 0;
    }

    /**
     * Model aktif siap dipakai: file .gguf ada di ModelCatalog.localFileById
     * dan ukurannya cocok dengan ekspektasi katalog (bila diketahui).
     * File tidak ada, atau hanya .part tanpa .gguf, atau ukuran tidak cocok
     * → false (dot Offline).
     */
    private boolean modelReadyOnDisk() {
        if (!isAdded() || model == null) return false;
        Context c = getActivity();
        long expect = modelSizeBytesExpect();
        java.io.File f = ModelCatalog.localFileById(c, model.id);
        if (f.exists()) {
            return expect <= 0 || f.length() == expect;
        }
        return false; // hanya .part tanpa .gguf juga jatuh ke sini
    }

    /** Hitung ulang dot Online/Offline toolbar dari kondisi nyata model aktif. */
    private void refreshStatusDot() {
        if (!isAdded() || statusDot == null || statusDotLabel == null) return;
        if (conv == null) { // v1.1.3: percakapan sudah dihapus — tampilkan Offline
            statusDotLabel.setText("Offline");
            statusDotLabel.setTextColor(0xFF8A94A8);
            android.graphics.drawable.GradientDrawable bgn =
                    (android.graphics.drawable.GradientDrawable) statusDot.getBackground();
            if (bgn != null) bgn.setColor(0xFF8A94A8);
            return;
        }
        boolean ok = (modelReadyOnDisk() || generating)
                && !GenerationState.get().lastInitFailed;
        statusDotLabel.setText(ok ? "Online" : "Offline");
        statusDotLabel.setTextColor(ok ? 0xFF34C77B : 0xFF8A94A8);
        android.graphics.drawable.GradientDrawable bg =
                (android.graphics.drawable.GradientDrawable) statusDot.getBackground();
        if (bg != null) bg.setColor(ok ? 0xFF34C77B : 0xFF8A94A8);
    }

    private TextView toolbarTitle;      // Fitur A: tappable model name
    private EditText input;
    private TextView sendBtn;
    private TextView micBtn;            // v1.1.7: tombol mic (STT on-device)
    private TextView streamingBubble;   // kartu AI yang sedang di-stream
    private TextView thinkingHeader;    // header collapsible "Thinking"
    private TextView thinkingBody;      // isi think (collapse/expand)
    private View thinkingSection;       // container header+body
    private boolean thinkingExpanded = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean generating = false;     // mirror GenerationState utk UI guard
    private volatile boolean switchingModel = false; // Fitur A: satu switch pada satu waktu

    /** Poll DownloadState.uiVersion: recomputed dot saat download selesai/gagal. */
    private long lastDlVersion = -1;
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            long v = DownloadState.uiVersion();
            if (v != lastDlVersion) {
                lastDlVersion = v;
                refreshStatusDot();
            }
            ui.postDelayed(this, 1000);
        }
    };

    /** Listener GenerationState — token/done datang dari worker thread service. */
    private final GenerationState.UpdateListener stateListener =
            () -> ui.post(() -> { if (isAdded()) onGenerationUpdate(); });

    // ---- Fitur A: bottom sheet pilihan model
    private Dialog modelSheet;
    private LinearLayout sheetList;
    private final java.util.ArrayList<SheetRow> sheetRows = new java.util.ArrayList<>();
    private final Runnable sheetPoll = new Runnable() {
        @Override public void run() {
            if (modelSheet == null || !modelSheet.isShowing()) return;
            for (SheetRow r : sheetRows) r.refresh();
            ui.postDelayed(this, 500);
        }
    };

    // Bentuk baris sheet (paralel dengan ModelLibraryFragment)
    private static final int SHAPE_IDLE = 0, SHAPE_PROGRESS = 1, SHAPE_READY = 2;

    public static ChatRoomFragment newInstance(long conversationId) {
        ChatRoomFragment f = new ChatRoomFragment();
        Bundle b = new Bundle();
        b.putLong("convId", conversationId);
        f.setArguments(b);
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final android.app.Activity act = getActivity();
        final android.content.Context ctx = act;
        convId = getArguments() != null ? getArguments().getLong("convId", -1) : -1;
        Host host = (Host) act;
        conv = host.getConversation(convId);
        model = conv != null ? host.getModel(conv.modelId) : null;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0C0F14);

        // ---------------- toolbar ----------------
        LinearLayout toolbar = new LinearLayout(ctx);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(0xFF131822);
        toolbar.setPadding(dp(4), statusBarInset(), dp(16), dp(10));

        TextView back = new TextView(ctx);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(0xFFEDF1F7);
        back.setPadding(dp(12), dp(4), dp(12), dp(4));
        back.setOnClickListener(v -> close());
        toolbar.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Fitur A: judul model jadi tappable + chevron affordance
        LinearLayout titleWrap = new LinearLayout(ctx);
        titleWrap.setOrientation(LinearLayout.HORIZONTAL);
        titleWrap.setGravity(Gravity.CENTER_VERTICAL);
        titleWrap.setBackgroundResource(R.drawable.bg_row_ripple);
        titleWrap.setPadding(dp(4), dp(4), dp(4), dp(4));
        titleWrap.setOnClickListener(v -> { if (!generating && !switchingModel) showModelSheet(); });

        toolbarTitle = new TextView(ctx);
        toolbarTitle.setText(model != null ? model.name : "Rlnova");
        toolbarTitle.setTextSize(16);
        toolbarTitle.setTypeface(Typeface.DEFAULT_BOLD);
        toolbarTitle.setTextColor(0xFFEDF1F7);
        toolbarTitle.setSingleLine(true);
        toolbarTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // v1.1.4 FIX pill melar: batas lebar nama model — nama panjang jadi
        // ellipsize, indikator Online/Offline tidak terdorong keluar layar.
        toolbarTitle.setMaxWidth(dp(220));
        titleWrap.addView(toolbarTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView chev = new TextView(ctx);
        chev.setText(" ▾");
        chev.setTextSize(12);
        chev.setTextColor(0xFF9AA6B8);
        titleWrap.addView(chev, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // v1.1.4 FIX pill melar: WRAP_CONTENT tanpa weight — dulu (0, WRAP, 1f)
        // bikin pill meregang mengisi seluruh sisa lebar toolbar. Spacer
        // weight-1 di antara pill dan status menjaga indikator tetap di kanan.
        toolbar.addView(titleWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View titleSpacer = new View(ctx);
        toolbar.addView(titleSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // status: dot + label dihitung dinamis (refreshStatusDot)
        LinearLayout status = new LinearLayout(ctx);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(ctx);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(0xFF8A94A8); // di-set ulang oleh refreshStatusDot()
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(6);
        dot.setLayoutParams(dotLp);
        dot.setBackground(dotBg);
        statusDot = dot;
        status.addView(dot);
        statusDotLabel = new TextView(ctx);
        statusDotLabel.setText("Offline");
        statusDotLabel.setTextSize(12);
        statusDotLabel.setTextColor(0xFF8A94A8);
        status.addView(statusDotLabel);
        toolbar.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View div = new View(ctx);
        div.setBackgroundColor(0xFF232B3A);
        root.addView(div, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // ---------------- messages scroll ----------------
        messagesView = new LinearLayout(ctx);
        messagesView.setOrientation(LinearLayout.VERTICAL);
        messagesView.setPadding(dp(16), dp(12), dp(16), dp(16));
        scroller = new ScrollView(ctx);
        scroller.setFillViewport(false);
        scroller.addView(messagesView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------------- footer ----------------
        footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), dp(6), dp(16), dp(6));
        footer.setBackgroundColor(0xFF131822);
        footerStats = new TextView(ctx);
        footerStats.setTextSize(12);
        footerStats.setTypeface(Typeface.MONOSPACE);
        footerStats.setTextColor(0xFF9AA6B8);
        footerStats.setText("0 tokens · 0.0 tok/s");
        footer.addView(footerStats, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView stopBtn = new TextView(ctx);
        stopBtn.setText("Stop");
        stopBtn.setTextSize(14);
        stopBtn.setTypeface(Typeface.DEFAULT_BOLD);
        stopBtn.setTextColor(0xFFFFFFFF);
        stopBtn.setBackgroundResource(R.drawable.bg_button_primary);
        stopBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        stopBtn.setOnClickListener(v -> stopGeneration());
        footer.addView(stopBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        footer.setVisibility(View.GONE);
        root.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------------- composer ----------------
        LinearLayout composer = new LinearLayout(ctx);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(12), dp(8), dp(12), dp(12));
        composer.setBackgroundColor(0xFF0C0F14);

        input = new EditText(ctx);
        input.setHint("Type a message…");
        input.setTextColor(0xFFEDF1F7);
        input.setHintTextColor(0xFF5B6678);
        input.setTextSize(14);
        input.setMaxLines(4);
        input.setHorizontallyScrolling(false);
        input.setBackgroundResource(R.drawable.bg_composer);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                onSendClicked();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ilp.rightMargin = dp(8);
        composer.addView(input, ilp);

        // ---- v1.1.7: tombol mic (STT on-device) di kiri tombol Send ----
        micBtn = new TextView(ctx);
        micBtn.setText("Mic");
        micBtn.setTextSize(13);
        micBtn.setTypeface(Typeface.DEFAULT_BOLD);
        micBtn.setTextColor(0xFFEDF1F7);
        micBtn.setGravity(Gravity.CENTER);
        micBtn.setBackgroundResource(R.drawable.bg_composer);
        micBtn.setOnClickListener(v -> onMicClicked());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(48), dp(48));
        mlp.rightMargin = dp(8);
        composer.addView(micBtn, mlp);

        sendBtn = new TextView(ctx);
        sendBtn.setText("Send");
        sendBtn.setTextSize(14);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sendBtn.setTextColor(0xFFFFFFFF);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setBackgroundResource(R.drawable.bg_button_primary);
        sendBtn.setOnClickListener(v -> onSendClicked());
        composer.addView(sendBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));

        root.addView(composer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // v1.1.3: conv null = percakapan sudah dihapus dari list menu ⋮.
        // Nonaktifkan kirim, jangan crash, jangan insert ke conversation hilang.
        if (conv == null) {
            sendBtn.setEnabled(false);
            sendBtn.setAlpha(0.4f);
            input.setEnabled(false);
            input.setHint("Conversation deleted");
            TextView note = new TextView(ctx);
            note.setText("This conversation no longer exists.");
            note.setTextSize(13);
            note.setTextColor(0xFFE5484D);
            note.setPadding(dp(16), dp(12), dp(16), dp(8));
            messagesView.addView(note);
            toolbarTitle.setText("Rlnova");
            return root;
        }

        loadHistory();
        refreshStatusDot();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Subscribe dulu, baru cek apakah percakapan ini sedang digenerate
        // oleh service (re-attach: kembali dari back / dibuka dari notifikasi).
        GenerationState.get().addListener(stateListener);
        refreshStatusDot(); // masuk chat: hitung ulang status model aktif
        lastDlVersion = -1; // paksa recompute di tick poll pertama
        ui.removeCallbacks(statusPoll);
        ui.post(statusPoll);
        if (GenerationState.get().isGeneratingFor(convId)) {
            generating = true;
            footer.setVisibility(View.VISIBLE);
            sendBtn.setEnabled(false);
            sendBtn.setAlpha(0.4f);
            // header Thinking + kartu streaming dari state.raw yang terkumpul
            if (streamingBubble == null) insertStreamingCard();
            renderStreamingState();
            footerStats.setText(GenerationState.get().nTokens + " tokens · "
                    + fmt1(GenerationState.get().liveTokPerSec()) + " tok/s");
        } else if (!generating) {
            footer.setVisibility(View.GONE);
            sendBtn.setEnabled(true);
            sendBtn.setAlpha(1f);
        }
    }

    @Override
    public void onPause() {
        ui.removeCallbacks(statusPoll);
        GenerationState.get().removeListener(stateListener);
        stopSpeaking(); // v1.1.8: fragment pause = berhenti bicara
        super.onPause();
    }

    // ================================================================ riwayat

    private void loadHistory() {
        if (!isAdded() || conv == null) return; // v1.1.3: conv dihapus → tidak render (note di onCreateView tidak tersentuh)
        // v1.1.4 FIX bug dobel: bersihkan container sebelum render ulang dari DB.
        // Dulu loadHistory hanya append — bubble user optimistis dari onSendClicked
        // tetap ada, lalu finishGeneration() memuat ulang pesan yang sama dari DB
        // (user 2x, AI 1x). Lepas referensi kartu streaming dulu supaya tidak
        // menunjuk view yang dibuang; normalnya removeStreamingCard() sudah
        // dipanggil sebelum finishGeneration() → di sini hanya jaga-jaga.
        if (streamingBubble != null) {
            stopThinkingDots();
            streamingBubble = null;
        }
        if (thinkingSection != null) {
            thinkingSection = null;
            thinkingHeader = null;
            thinkingBody = null;
        }
        thinkingExpanded = false;
        messagesView.removeAllViews();
        List<ChatDatabase.Message> ms = ChatDatabase.get(getActivity()).getMessages(convId);
        for (ChatDatabase.Message m : ms) {
            if (m.role == ChatDatabase.ROLE_USER) {
                addBubbleUser(m.content); // Fitur C: long-press copy tersangkut di sini
            } else if (m.role == ChatDatabase.ROLE_ASSISTANT) {
                addCardAiParsed(m.content); // parse think block juga di history
                if (m.tokens != null && m.tokPerSec != null) {
                    TextView st = addStatsRow();
                    // Fitur B: atribusi model asal pesan (null → format lama)
                    String name = m.modelId == null ? null
                            : ModelCatalog.displayNameFor(((Host) getActivity()).allModels(), m.modelId);
                    st.setText(name != null
                            ? name + " · " + m.tokens + " tokens · " + fmt1(m.tokPerSec) + " tok/s"
                            : m.tokens + " tokens · " + fmt1(m.tokPerSec) + " tok/s");
                }
            }
        }
        scrollToBottom();
    }

    // ================================================================ kirim

    private void onSendClicked() {
        if (conv == null) return; // v1.1.3: percakapan sudah dihapus — jangan insert
        if (GenerationService.isBusy() || switchingModel) return;
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        addBubbleUser(text);
        ChatDatabase.get(getActivity()).insertMessage(convId, ChatDatabase.ROLE_USER, text, null, null);
        if (conv != null && ("New conversation".equals(conv.title) || conv.title == null)) {
            ChatDatabase.get(getActivity()).renameConversation(convId, text);
            ((Host) getActivity()).onConversationListChanged();
        }
        GenerationService.start(getActivity(), convId, text, false, model,
                conv != null ? conv.systemPrompt : null);
        enterGeneratingUi();
    }

    private void deleteLastUserMessage() {
        if (conv == null) return; // v1.1.3: conv dihapus — tidak ada yang perlu dibuang
        List<ChatDatabase.Message> ms = ChatDatabase.get(getActivity()).getMessages(convId);
        if (!ms.isEmpty() && ms.get(ms.size() - 1).role == ChatDatabase.ROLE_USER) {
            ChatDatabase.get(getActivity()).deleteMessage(ms.get(ms.size() - 1).id);
        }
    }

    private void stopGeneration() {
        GenerationService.requestStop();
    }

    private void close() {
        dismissSheet();
        ((Host) getActivity()).onChatClosed();
    }

    /** UI masuk mode generating (footer live + Send terkunci). */
    private void enterGeneratingUi() {
        GenerationState st = GenerationState.get();
        stopSpeaking(); // v1.1.8: generation baru = berhenti bicara
        generating = true;
        thinkingExpanded = false;
        footer.setVisibility(View.VISIBLE);
        footerStats.setText("0 tokens · 0.0 tok/s");
        sendBtn.setEnabled(false);
        sendBtn.setAlpha(0.4f);
        // kartu streaming muncul segera (caret blink sebelum token pertama)
        if (streamingBubble == null) insertStreamingCard();
        renderStreamingState();
    }

    // ===================================================== Fitur A: switch

    /**
     * Ganti model percakapan yang sedang dibuka (Fitur A). Jalur UI thread.
     * v1.1.3: init engine didelegasikan ke GenerationService — cukup tolak
     * switch saat generation berjalan; pembaruan engine terjadi otomatis di
     * service pada generation berikutnya (model beda/epoch naik → reload).
     */
    private void switchModelTo(ModelCatalog.Model target) {
        if (conv == null) return; // v1.1.3: percakapan sudah dihapus
        if (switchingModel) return;
        if (GenerationService.isBusy()) {
            toast("Wait for the current generation to finish");
            return;
        }
        switchingModel = true;
        ChatDatabase.get(getActivity()).setConversationModel(convId, target.id);
        conv = ((Host) getActivity()).getConversation(convId);
        model = target;
        toolbarTitle.setText(target.name);
        refreshStatusDot(); // status mengikuti model yang baru aktif
        switchingModel = false;
        toast("Switched to " + target.name);
    }

    // ============================================== Fitur A: bottom sheet

    private void showModelSheet() {
        if (!isAdded() || modelSheet != null) return;
        Context c = getActivity();
        modelSheet = new Dialog(c);
        modelSheet.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF131822);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Tinggi sheet maksimum ~70% layar — bukan fixed 420dp.
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.7);

        TextView head = new TextView(c);
        head.setText("Switch model");
        head.setTextSize(16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(0xFFEDF1F7);
        head.setPadding(0, 0, 0, dp(8));
        head.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(head);

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        sheetList = new LinearLayout(c);
        sheetList.setOrientation(LinearLayout.VERTICAL);
        sheetList.setPadding(0, 0, 0, 0);
        scroll.addView(sheetList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        modelSheet.setContentView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = modelSheet.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, maxH);
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF131822));
        }

        sheetRows.clear();
        for (ModelCatalog.Model m : ((Host) getActivity()).allModels()) {
            SheetRow r = new SheetRow(m);
            sheetRows.add(r);
            r.attach(sheetList);
        }
        modelSheet.setOnDismissListener(d -> {
            modelSheet = null;
            sheetRows.clear();
            ui.removeCallbacks(sheetPoll);
        });
        modelSheet.show();
        ui.post(sheetPoll);
    }

    private void dismissSheet() {
        if (modelSheet != null) {
            try { modelSheet.dismiss(); } catch (Exception ignored) { }
            modelSheet = null;
            ui.removeCallbacks(sheetPoll);
        }
    }

    private int tierColor(String tier) {
        switch (tier) {
            case "LOW": return 0xFF34C77B;
            case "MID": return 0xFF5B8DEF;
            default: return 0xFFA78BFA;
        }
    }

    // ============================================================ generation

    /**
     * Jantung wiring v1.1.3: dipanggil di UI thread setiap GenerationState
     * berubah (per-token, selesai, gagal). Fragment hanya MIRROR state —
     * generation, engine, dan insert pesan final semua di GenerationService.
     */
    private void onGenerationUpdate() {
        GenerationState st = GenerationState.get();
        // Generation milik percakapan lain → abaikan di room ini.
        if (st.generating && st.convId != convId) return;
        if (st.generating) {
            if (!generating) enterGeneratingUi();
            if (st.nTokens > 0) stopThinkingDots(); // token pertama: stop caret blink
            renderStreamingState();
            footerStats.setText(st.nTokens + " tokens · "
                    + fmt1(st.liveTokPerSec()) + " tok/s");
            scrollToBottom();
        } else if (generating) {
            // Transisi generating → selesai. Pesan final SUDAH diinsert oleh
            // service — fragment render ulang dari DB lewat loadHistory().
            generating = false;
            removeStreamingCard();
            if (st.error != null) {
                if (st.lastInitFailed) deleteLastUserMessage();
                refreshStatusDot(); // init gagal → dot Offline segera
                onGenerationFailed(st.error);
            } else if (st.stopReason < 0) {
                onGenerationFailed("Generation failed");
            } else {
                refreshStatusDot(); // generation sukses → engine hidup, dot Online
                finishGeneration();
            }
        }
    }

    private void onGenerationFailed(String msg) {
        if (!isAdded()) return; // fragment detached mid-generation
        if (GenerationState.get().convId != convId) return; // v1.1.3: room beda conv
        generating = false;
        switchingModel = false;
        footer.setVisibility(View.GONE);
        sendBtn.setEnabled(true);
        sendBtn.setAlpha(1f);
        // Orphan user bubble dibuang HANYA kalau init engine gagal (tidak ada
        // pesan assistant sama sekali) — error generatif lain dipertahankan.
        if (GenerationState.get().lastInitFailed) deleteLastUserMessage();
        refreshStatusDot(); // segera saat init gagal: dot Offline
        TextView err = new TextView(getActivity());
        err.setText(msg);
        err.setTextSize(13);
        err.setTextColor(0xFFE5484D);
        err.setBackgroundResource(R.drawable.bg_card_ai);
        err.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.topMargin = dp(8);
        messagesView.addView(err, lp);
        scrollToBottom();
    }

    /**
     * Render ulang seluruh akumulasi RAW (dari GenerationState.raw — milik
     * service) ke (think collapsible + answer).
     * Re-split seluruh string tiap update — tag yang terbelah antar token
     * ("<thi" + "nk>") tetap terdeteksi.
     */
    private void renderStreamingState() {
        if (!isAdded() || streamingBubble == null) return;
        ThinkSplit.Result r = ThinkSplit.parse(GenerationState.get().rawText());
        ensureThinkSection(r.hasThink);
        if (r.hasThink) {
            boolean live = generating;
            if (r.inThink) {
                thinkingHeader.setText(live ? "Thinking…" : "Thinking");
            } else {
                thinkingHeader.setText("Thinking");
            }
            thinkingBody.setText(r.thinking);
            thinkingSection.setVisibility(View.VISIBLE);
            thinkingBody.setVisibility(thinkingExpanded ? View.VISIBLE : View.GONE);
            // Isi kartu jawaban: jawaban parsial, atau caret saat masih think.
            if (r.inThink) {
                streamingBubble.setText(generating ? "▍" : "");
            } else {
                streamingBubble.setText(r.answer + "▍");
            }
        } else {
            if (thinkingSection != null) thinkingSection.setVisibility(View.GONE);
            streamingBubble.setText(r.answer + "▍");
        }
    }

    /** Pastikan section think streaming ada (dibuat sekali per generasi). */
    private void ensureThinkSection(boolean needed) {
        if (!needed) {
            // biarkan section lama (kalau ada) — visibility diatur pemanggil
            return;
        }
        if (thinkingSection == null || thinkingHeader == null || thinkingBody == null) {
            View v = buildThinkSection("", true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.START;
            lp.rightMargin = dp(48);
            lp.topMargin = dp(8);
            // sisipkan tepat sebelum kartu streaming
            int idx = messagesView.indexOfChild(streamingBubble);
            if (idx < 0) idx = messagesView.getChildCount();
            messagesView.addView(v, idx, lp);
            thinkingSection = v;
            // ambil referensi header/body dari section yang baru dibuat
            thinkingHeader = (TextView) ((ViewGroup) v).getChildAt(0);
            thinkingBody = (TextView) ((ViewGroup) v).getChildAt(1);
            // state expand: ikut flag global (default collapsed)
            thinkingBody.setVisibility(thinkingExpanded ? View.VISIBLE : View.GONE);
        }
    }

    /** Kartu AI final (dipakai finishGeneration & loadHistory): parse think → header collapsible + answer. */
    private void addCardAiParsed(String rawContent) {
        if (!isAdded() || messagesView == null) return;
        ThinkSplit.Result r = ThinkSplit.parse(rawText(rawContent));
        if (!r.hasThink) {
            addCardAi(r.answer);
            return;
        }
        final View section = buildThinkSection(r.thinking, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.START;
        slp.rightMargin = dp(48);
        slp.topMargin = dp(8);
        messagesView.addView(section, slp);
        if (!r.answer.trim().isEmpty()) {
            addCardAi(r.answer);
        } else {
            // BUG2 fallback: generation berakhir dengan think-only → tampilkan catatan + Retry.
            TextView note = new TextView(getActivity());
            note.setText("No answer generated.");
            note.setTextSize(13);
            note.setTextColor(0xFF9AA6B8);
            note.setPadding(dp(14), dp(8), dp(14), dp(10));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.gravity = Gravity.START;
            nlp.rightMargin = dp(48);
            nlp.topMargin = dp(4);
            messagesView.addView(note, nlp);

            TextView retry = new TextView(getActivity());
            retry.setText("Retry");
            retry.setTextSize(14);
            retry.setTypeface(Typeface.DEFAULT_BOLD);
            retry.setTextColor(0xFF5B8DEF);
            retry.setPadding(dp(14), dp(6), dp(14), dp(10));
            retry.setOnClickListener(v -> {
                if (GenerationService.isBusy()) return;
                // hapus tampilan section lama (note + retry ikut hilang bareng parentnya)
                messagesView.removeView(section);
                // Retry via service — tanpa duplikasi pesan user (text terakhir dipakai service dari DB).
                GenerationService.start(getActivity(), convId, lastUserTextFor(""), true,
                        model, conv != null ? conv.systemPrompt : null);
                enterGeneratingUi();
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.gravity = Gravity.START;
            rlp.topMargin = dp(2);
            rlp.bottomMargin = dp(8);
            messagesView.addView(retry, rlp);
        }
    }

    /** Ambil pesan user terakhir dari DB (untuk Retry — bubble user tidak diduplikasi). */
    private String lastUserTextFor(String ignore) {
        if (conv == null) return "";
        List<ChatDatabase.Message> ms = ChatDatabase.get(getActivity()).getMessages(convId);
        for (int i = ms.size() - 1; i >= 0; i--) {
            if (ms.get(i).role == ChatDatabase.ROLE_USER) return ms.get(i).content;
        }
        return "";
    }

    private String rawText(String s) { return s == null ? "" : s; }

    /** Bangun section think (header collapsible + body) — dipakai streaming & final. */
    private View buildThinkSection(String thinking, boolean live) {
        Context c = getActivity();
        LinearLayout section = new LinearLayout(c);
        section.setOrientation(LinearLayout.VERTICAL);

        final TextView header = new TextView(c);
        header.setText(live ? "Thinking…" : "Thinking");
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(0xFF8A94A8); // secondary
        header.setPadding(dp(4), dp(4), dp(4), dp(4));
        header.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView body = new TextView(c);
        body.setText(thinking);
        body.setTextSize(13);
        body.setTextColor(0xFF7C8AA5);
        body.setBackgroundResource(R.drawable.bg_card_ai);
        body.setPadding(dp(12), dp(8), dp(12), dp(8));
        body.setVisibility(View.GONE); // collapsed by default
        section.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        header.setOnClickListener(v -> {
            thinkingExpanded = !thinkingExpanded;
            body.setVisibility(thinkingExpanded ? View.VISIBLE : View.GONE);
        });
        return section;
    }

    /**
     * Dipanggil dari onGenerationUpdate saat service melaporkan selesai sukses.
     * insertMessage TIDAK di sini — service sudah insert; fragment hanya
     * render ulang pesan final dari DB (loadHistory).
     */
    private void finishGeneration() {
        if (!isAdded()) return; // fragment detached mid-generation
        if (GenerationState.get().convId != convId) return; // v1.1.3: room beda conv
        sendBtn.setEnabled(true);
        sendBtn.setAlpha(1f);
        footer.setVisibility(View.GONE);
        loadHistory(); // render final (think collapsible + stats) dari DB
        ((Host) getActivity()).onConversationListChanged();
    }

    private void insertStreamingCard() {
        if (!isAdded()) return;
        streamingBubble = new TextView(getActivity());
        streamingBubble.setTextColor(0xFFEDF1F7);
        streamingBubble.setTextSize(14);
        streamingBubble.setBackgroundResource(R.drawable.bg_card_ai);
        streamingBubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        // "AI thinking" dots (P4): animate caret while waiting for the first token
        streamingBubble.setText("▍");
        startThinkingDots(streamingBubble);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.rightMargin = dp(48);
        lp.topMargin = dp(8);
        messagesView.addView(streamingBubble, lp);
        scrollToBottom();
    }

    /** P4 "thinking" indicator: caret pulse while the model loads / before the first token. */
    private void startThinkingDots(final TextView bubble) {
        if (bubble == null) return;
        ObjectAnimator blink = ObjectAnimator.ofFloat(bubble, "alpha", 0.35f, 1f);
        blink.setDuration(600);
        blink.setRepeatCount(ObjectAnimator.INFINITE);
        blink.setRepeatMode(ObjectAnimator.REVERSE);
        blink.start();
        bubble.setTag(R.id.content, blink); // hold reference for cancel
    }

    private void stopThinkingDots() {
        if (streamingBubble != null) {
            Object tag = streamingBubble.getTag(R.id.content);
            if (tag instanceof ObjectAnimator) ((ObjectAnimator) tag).cancel();
            streamingBubble.setAlpha(1f);
        }
    }

    private void removeStreamingCard() {
        stopThinkingDots();
        if (streamingBubble != null) {
            messagesView.removeView(streamingBubble);
            streamingBubble = null;
        }
        // lepas juga section think streaming (final dirender ulang oleh addCardAiParsed)
        if (thinkingSection != null) {
            messagesView.removeView(thinkingSection);
            thinkingSection = null;
            thinkingHeader = null;
            thinkingBody = null;
        }
        thinkingExpanded = false;
    }

    // ------------------------------------------------------------------- UI

    /** Fitur C: salin teks pesan ke clipboard + Toast "Copied". */
    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty() || !isAdded()) return;
        ClipboardManager cm = (ClipboardManager) getActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("message", text));
        toast("Copied");
    }

    private void addBubbleUser(String text) {
        TextView b = new TextView(getActivity());
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundResource(R.drawable.bg_bubble_user);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setLongClickable(true);
        b.setOnLongClickListener(v -> {
            copyToClipboard(text);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.leftMargin = dp(64);
        lp.topMargin = dp(4);
        messagesView.addView(b, lp);
    }

    private void addCardAi(String content) {
        Context c = getActivity();
        TextView card = new TextView(getActivity());
        card.setText(content);
        card.setTextSize(14);
        card.setTextColor(0xFFEDF1F7);
        card.setBackgroundResource(R.drawable.bg_card_ai);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        // Fitur C: hanya teks jawaban (bukan think block, bukan statistik)
        card.setLongClickable(true);
        card.setOnLongClickListener(v -> {
            copyToClipboard(content);
            return true;
        });
        // v1.1.8: tombol speaker kecil di bawah kartu jawaban
        TextView speak = new TextView(c);
        speak.setText("Speak");
        speak.setTextSize(12);
        speak.setTypeface(Typeface.DEFAULT_BOLD);
        speak.setTextColor(0xFF5B8DEF);
        speak.setPadding(dp(4), dp(2), dp(4), dp(2));
        speak.setMinHeight(dp(32));
        speak.setGravity(Gravity.CENTER_VERTICAL);
        speak.setOnClickListener(v -> onSpeakClicked(speak, content));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(card);
        row.addView(speak, slp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.rightMargin = dp(48);
        lp.topMargin = dp(8);
        messagesView.addView(row, lp);
    }

    private TextView addStatsRow() {
        TextView stats = new TextView(getActivity());
        stats.setTextSize(12);
        stats.setTypeface(Typeface.MONOSPACE);
        stats.setTextColor(0xFF7C8AA5);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(8);
        messagesView.addView(stats, lp);
        return stats;
    }

    private void scrollToBottom() {
        if (scroller != null) scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private void toast(String s) {
        if (isAdded()) android.widget.Toast.makeText(getActivity(), s,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
        return dp(8) + h;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // v1.1.7: hasil runtime permission RECORD_AUDIO (perm mic pertama kali).
        if (requestCode != REQ_RECORD_AUDIO) return;
        if (getActivity() == null) return;
        if (requestCode == REQ_RECORD_AUDIO
                && grantResults != null && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onMicClicked(); // izin baru saja diberikan — lanjut mulai rekam
        } else {
            toast("Microphone permission is needed for voice input");
        }
    }

    @Override
    public void onDestroy() {
        stopRecording();       // buang recorder + thread-nya
        freeWhisper();         // bebaskan model STT bila dimuat
        freeTts();             // v1.1.8: bebaskan TTS + stop playback
        super.onDestroy();
    }

    // ====================================================== v1.1.7: mic/STT

    private static final int REQ_RECORD_AUDIO = 3001;

    private final WhisperEngine whisperEngine = new WhisperEngine();
    private android.media.AudioRecord recorder;
    private volatile Thread recordThread;
    private volatile boolean recording = false;

    private void onMicClicked() {
        if (!isAdded() || conv == null) return;
        if (recording) {           // tap kedua: berhenti -> transkrip
            stopRecording();
            return;
        }
        if (whisperEngine.isTranscribing()) return;
        Context c = getActivity();
        if (c == null) return;
        if (Build.VERSION.SDK_INT >= 23
                && c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        if (!((LibraryActivity) getActivity()).isVoiceModelReady()) {
            // Model STT belum ada → arahkan download, jangan crash, jangan
            // diam-diam pakai recognizer sistem.
            VoiceCatalog.VoiceModel v = VoiceCatalog.whisperTiny();
            DownloadState st = DownloadState.get(v.id);
            if (st.status == DownloadState.DOWNLOADING || st.status == DownloadState.PAUSED) {
                toast("Voice model is still downloading — check the Library tab");
            } else {
                toast("Download the Whisper voice model first (Library > Voice models)");
                if (getActivity() instanceof LibraryActivity) {
                    ((LibraryActivity) getActivity()).startVoiceDownload(v);
                }
            }
            return;
        }
        startRecording();
    }

    /** Rekam 16 kHz mono PCM via AudioRecord; tap mic lagi = berhenti → transkrip. */
    private void startRecording() {
        if (recording) return;
        int minBuf = android.media.AudioRecord.getMinBufferSize(
                16000, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            toast("Audio recording is not available on this device");
            return;
        }
        int bufSize = Math.max(minBuf, 16000 * 2); // ~1 detik minimum
        try {
            recorder = new android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    16000, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Exception e) {
            toast("Could not start recording");
            return;
        }
        if (recorder.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            toast("Could not start recording");
            return;
        }
        recorder.startRecording();
        recording = true;
        micBtn.setText("Stop");
        micBtn.setTextColor(0xFFE5484D);
        toast("Recording… tap Stop to transcribe");

        recordThread = new Thread(() -> {
            short[] buf = new short[16000 / 10]; // 100 ms chunk
            while (!Thread.currentThread().isInterrupted()) {
                int n;
                synchronized (recorderLock) {
                    if (recorder == null || !recording) break;
                    n = recorder.read(buf, 0, buf.length);
                }
                if (n > 0) {
                    short[] chunk = new short[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    synchronized (chunks) { chunks.add(chunk); }
                }
            }
        }, "rlnova-rec");
        recordThread.start();
    }

    private final Object recorderLock = new Object();
    private final java.util.ArrayList<short[]> chunks = new java.util.ArrayList<>();

    /** Berhenti rekam + gabung PCM -> transkrip on-device -> isi input (bukan kirim). */
    private void stopRecording() {
        if (!recording) return;
        recording = false;
        Thread t = recordThread;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException ignored) { }
            recordThread = null;
        }
        android.media.AudioRecord r = recorder;
        recorder = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) { }
            try { r.release(); } catch (Exception ignored) { }
        }
        if (!isAdded() || getActivity() == null) { chunks.clear(); return; }

        // gabungkan chunk yang terkumpul
        int total = 0;
        synchronized (chunks) { for (short[] c : chunks) total += c.length; }
        if (total < 16000 / 2) { // < 0.5 detik: dianggap tidak ada ucapan
            chunks.clear();
            resetMicButton();
            toast("Recording too short");
            return;
        }
        short[] pcm = new short[total];
        int off = 0;
        synchronized (chunks) {
            for (short[] c : chunks) {
                System.arraycopy(c, 0, pcm, off, c.length);
                off += c.length;
            }
            chunks.clear();
        }
        // potong ke maksimum ~60 detik agar RAM aman
        final short[] pcmFinal;
        if (off > 16000 * 60) {
            pcmFinal = new short[16000 * 60];
            System.arraycopy(pcm, off - pcmFinal.length, pcmFinal, 0, pcmFinal.length);
        } else {
            pcmFinal = pcm;
        }
        resetMicButton();
        micBtn.setText("…");
        toast("Transcribing…");
        transcribeInBackground(pcmFinal);
    }

    private void resetMicButton() {
        if (micBtn != null) {
            micBtn.setText("Mic");
            micBtn.setTextColor(0xFFEDF1F7);
        }
    }

    /** Load model (bila belum) lalu transkrip di worker; hasil → kolom input. */
    private void transcribeInBackground(final short[] pcm) {
        final int nSamples = pcm.length;
        final android.app.Activity act = getActivity();
        if (act == null) return;
        final String modelPath =
                VoiceCatalog.localFile(getActivity(), VoiceCatalog.WHISPER_TINY_ID).getAbsolutePath();
        new Thread(() -> {
            try {
                whisperEngine.loadLibrary();
            } catch (Throwable t) {
                ui.post(() -> { toast("Voice engine failed to load"); resetMicButton(); });
                return;
            }
            if (!whisperEngine.init(modelPath)) {
                ui.post(() -> { toast("Could not load the voice model"); resetMicButton(); });
                return;
            }
            whisperEngine.transcribeAsync(pcm, nSamples, result -> ui.post(() -> {
                resetMicButton();
                if (!isAdded()) return;
                if (result == null) {
                    toast("Transcription failed");
                } else if (result.trim().isEmpty()) {
                    toast("No speech detected");
                } else {
                    // ISI input — JANGAN langsung kirim (user bisa edit dulu).
                    String current = input.getText() == null ? "" : input.getText().toString();
                    input.setText(current.isEmpty() ? result : current + " " + result);
                    input.setSelection(input.getText().length());
                }
            }));
        }, "rlnova-stt").start();
    }

    private void freeWhisper() {
        new Thread(() -> whisperEngine.free(), "rlnova-stt-free").start();
    }

    // ====================================================== v1.1.8: TTS speak

    private final TtsEngine ttsEngine = new TtsEngine();
    private volatile Thread ttsWorker;      // worker sintesis+playback aktif
    private volatile boolean speaking = false;

    /**
     * Tap tombol speaker: mulai/stop membacakan teks jawaban. Satu suara
     * dalam satu waktu — tap saat bicara = berhenti. Sintesis + playback
     * jalan di worker thread (blocking), update UI via Handler.
     */
    private void onSpeakClicked(final TextView btn, final String text) {
        if (speaking) { stopSpeaking(); return; }
        if (ttsWorker != null && ttsWorker.isAlive()) return; // sintesis lain jalan
        android.app.Activity act = getActivity();
        if (act == null || text == null || text.trim().isEmpty()) return;

        if (!VoiceCatalog.isTtsReady(act)) {
            // Model TTS belum siap → arahkan download, jangan pakai suara sistem.
            DownloadState st = DownloadState.get(VoiceCatalog.PIPER_ID_ID);
            DownloadState se = DownloadState.get(VoiceCatalog.PIPER_ESPEAK_ID);
            if (st.status == DownloadState.DOWNLOADING || st.status == DownloadState.PAUSED
                    || st.status == DownloadState.DONE
                    || DownloadState.get(VoiceCatalog.PIPER_ESPEAK_ID).status
                       == DownloadState.DOWNLOADING) {
                toast("TTS voice is still downloading — check the Library tab");
            } else {
                toast("Download the Piper voice model first (Library > Voice models)");
                if (getActivity() instanceof LibraryActivity) {
                    ((LibraryActivity) getActivity()).startVoiceDownload(VoiceCatalog.piperId());
                    ((LibraryActivity) getActivity()).startVoiceDownload(VoiceCatalog.piperEspeak());
                }
            }
            return;
        }

        speaking = true;
        btn.setText("Stop");
        btn.setTextColor(0xFFE5484D);
        final android.app.Activity activity = act;
        ttsWorker = new Thread(() -> {
            try {
                TtsEngine.loadLibrary();
            } catch (Throwable t) {
                ui.post(() -> { toast("TTS engine failed to load"); resetSpeakButton(btn); });
                speaking = false;
                return;
            }
            if (!ttsEngine.init(activity)) {
                ui.post(() -> { toast("Could not load the TTS voice model"); resetSpeakButton(btn); });
                speaking = false;
                return;
            }
            GeneratedAudio audio = ttsEngine.generate(text);
            if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
                ui.post(() -> { toast("Speech synthesis failed"); resetSpeakButton(btn); });
                speaking = false;
                return;
            }
            boolean ok = ttsEngine.play(audio);
            if (ok) {
                // tunggu playback selesai ATAU stopPlayback() dari thread lain
                while (speaking) {
                    try { Thread.sleep(120); } catch (InterruptedException e) { break; }
                }
            }
            ttsEngine.stopPlayback();
            ui.post(() -> resetSpeakButton(btn));
            speaking = false;
        }, "rlnova-tts");
        ttsWorker.start();
    }

    private void stopSpeaking() {
        speaking = false;
        ttsEngine.stopPlayback();
        if (ttsWorker != null) ttsWorker.interrupt();
    }

    /** Kosongkan TTS native (dipanggil di onDestroy — background thread). */
    private void freeTts() {
        stopSpeaking();
        new Thread(() -> ttsEngine.free(), "rlnova-tts-free").start();
    }

    private void resetSpeakButton(TextView btn) {
        if (btn != null) {
            btn.setText("Speak");
            btn.setTextColor(0xFF5B8DEF);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /**
     * Satu baris model di bottom sheet (Fitur A). Konten dibangun ulang hanya
     * saat bentuk (READY/PROGRESS/IDLE) berubah — progress di-update in-place.
     * READY: warna penuh, tap = switch. PROGRESS/IDLE: alpha 0.4, caption
     * "Not downloaded" + tombol Download (ACTION_START seperti di Library).
     * Setelah DONE, poll membuat baris otomatis SHAPE_READY tanpa tutup sheet.
     */
    private final class SheetRow {
        final ModelCatalog.Model m;
        LinearLayout row;
        int shape = -1;
        ProgressBar bar;
        TextView info;

        SheetRow(ModelCatalog.Model m) { this.m = m; }

        int currentShape() {
            if (!isAdded()) return SHAPE_IDLE;
            boolean downloaded = ((Host) getActivity()).isModelDownloaded(m.id);
            DownloadState st = DownloadState.get(m.id);
            if (downloaded || st.status == DownloadState.DONE) return SHAPE_READY;
            if (st.status == DownloadState.DOWNLOADING || st.status == DownloadState.PAUSED)
                return SHAPE_PROGRESS;
            return SHAPE_IDLE;
        }

        void attach(LinearLayout parent) {
            row = new LinearLayout(getActivity());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_row_ripple);
            int p = dp(14);
            row.setPadding(p, dp(12), p, dp(12));
            // WRAP_CONTENT tingginya — jangan pernah meregang memenuhi sheet.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8); // gap seragam antar baris
            parent.addView(row, lp);
            render();
        }

        void refresh() {
            int s = currentShape();
            if (s != shape) {
                render();
            } else if (s == SHAPE_PROGRESS) {
                DownloadState st = DownloadState.get(m.id);
                if (bar != null) bar.setProgress(DownloadState.progressPercent(st));
                if (info != null) {
                    info.setText(DownloadState.formatBytes(st.downloaded) + " of "
                            + DownloadState.formatBytes(st.total)
                            + (st.speed != null && st.status == DownloadState.DOWNLOADING
                               ? " · " + st.speed : ""));
                }
            }
        }

        void render() {
            if (!isAdded() || row == null) return;
            shape = currentShape();
            boolean ready = shape == SHAPE_READY;
            row.removeAllViews();
            row.setAlpha(ready ? 1f : 0.4f);
            row.setOnClickListener(null);
            row.setClickable(false);
            bar = null;
            info = null;

            // Head: nama + tier chip
            LinearLayout head = new LinearLayout(getActivity());
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(getActivity());
            name.setText(m.name);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(ready ? 0xFFEDF1F7 : 0xFF5B6678);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(name);

            TextView tier = new TextView(getActivity());
            tier.setText(ModelCatalog.tierLabel(m.tier));
            tier.setTextSize(11);
            tier.setTypeface(Typeface.DEFAULT_BOLD);
            tier.setTextColor(tierColor(m.tier));
            tier.setBackgroundResource(R.drawable.bg_chip_pill);
            tier.setPadding(dp(8), dp(2), dp(8), dp(2));
            head.addView(tier, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(head);

            if (shape == SHAPE_READY) {
                TextView cap = new TextView(getActivity());
                cap.setText(DeviceInfo.formatBytes(m.sizeBytes) + " · On this device");
                cap.setTextSize(12);
                cap.setTextColor(0xFF7C8AA5);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(2);
                row.addView(cap, clp);
                row.setClickable(true);
                row.setOnClickListener(v -> {
                    if (generating || switchingModel) return;
                    if (model != null && model.id.equals(m.id)) {
                        dismissSheet();
                        return;
                    }
                    dismissSheet();
                    switchModelTo(m);
                });
            } else {
                if (shape == SHAPE_PROGRESS) {
                    DownloadState st = DownloadState.get(m.id);
                    bar = new ProgressBar(getActivity(), null,
                            android.R.attr.progressBarStyleHorizontal);
                    bar.setProgress(DownloadState.progressPercent(st));
                    bar.setProgressDrawable(getResources().getDrawable(R.drawable.progress_download));
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
                    blp.topMargin = dp(8);
                    row.addView(bar, blp);

                    info = new TextView(getActivity());
                    info.setText(DownloadState.formatBytes(st.downloaded) + " of "
                            + DownloadState.formatBytes(st.total)
                            + (st.speed != null && st.status == DownloadState.DOWNLOADING
                               ? " · " + st.speed : ""));
                    info.setTextSize(12);
                    info.setTypeface(Typeface.MONOSPACE);
                    info.setTextColor(0xFF7C8AA5);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    ilp.topMargin = dp(4);
                    row.addView(info, ilp);
                } else {
                    // IDLE: caption "Not downloaded" + tombol Download di kanan
                    // baris yang sama (row wrap → tambahkan baris flex di bawah head).
                    LinearLayout line2 = new LinearLayout(getActivity());
                    line2.setOrientation(LinearLayout.HORIZONTAL);
                    line2.setGravity(Gravity.CENTER_VERTICAL);

                    TextView cap = new TextView(getActivity());
                    cap.setText("Not downloaded");
                    cap.setTextSize(12);
                    cap.setTextColor(0xFF5B6678);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    line2.addView(cap, clp);

                    TextView dl = new TextView(getActivity());
                    dl.setText("Download");
                    dl.setTextColor(0xFFFFFFFF);
                    dl.setTextSize(13);
                    dl.setTypeface(Typeface.DEFAULT_BOLD);
                    dl.setBackgroundResource(R.drawable.bg_button_primary);
                    dl.setPadding(dp(14), dp(8), dp(14), dp(8));
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    dlp.leftMargin = dp(8);
                    line2.addView(dl, dlp);
                    dl.setOnClickListener(v -> {
                        if (generating || switchingModel) return;
                        ((Host) getActivity()).startDownloadFromChat(m);
                    });
                    LinearLayout.LayoutParams l2lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    l2lp.topMargin = dp(4);
                    row.addView(line2, l2lp);
                    row.setClickable(true);
                    row.setOnClickListener(v -> {
                        if (generating || switchingModel) return;
                        ((Host) getActivity()).startDownloadFromChat(m);
                    });
                }
            }
        }
    }
}
