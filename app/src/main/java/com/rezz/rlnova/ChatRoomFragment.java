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

/**
 * ChatRoomFragment (mockup 03, chat room page).
 *
 * Threading: loadLibrary/init/generate run on a background thread (single-
 * thread executor); native callbacks -> UI Handler for rendering; stop() is
 * called straight from the UI thread (atomic flag in native, latency <= 1
 * decode iteration).
 *
 * v1.1.2:
 *   - Fitur A: model selector di toolbar (bottom sheet; switch in-place via
 *     ENGINE_LOCK, rollback ke model lama kalau init gagal, unduh langsung
 *     dari sheet dengan progres live via DownloadState).
 *   - Fitur B: atribusi model per pesan (messages.model_id; baris statistik
 *     kartu AI menampilkan nama model ASAL pesan).
 *   - Fitur C: long-press bubble user / kartu AI menyalin teks ke clipboard.
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

    // Satu model termuat per proses — dipakai lintas percakapan.
    private static LlamaEngine sharedEngine;
    private static String sharedEngineModelId;
    private static long sharedEngineConfigEpoch = -1; // berubah saat prefs disimpan → paksa reload
    private static int sharedEngineInitCtx = 2048;    // ctx yang dipakai saat init engine aktif
    private static final Object ENGINE_LOCK = new Object();

    /** Epoch config: naik tiap kali pengaturan performa disimpan (PerfPrefs.save). */
    public static long perfEpoch() {
        return PerfPrefs.saveEpochValue();
    }

    /** Engine aktif (untuk benchmark dari panel Optimasi). Null = belum dimuat. */
    public static LlamaEngine sharedEngine() {
        return sharedEngine;
    }

    private static final java.util.concurrent.ExecutorService EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "rlnova-engine");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private long convId = -1;
    private ChatDatabase.Conversation conv;
    private ModelCatalog.Model model;

    private LinearLayout messagesView;
    private ScrollView scroller;
    private LinearLayout footer;
    private TextView footerStats;
    private TextView statusDotLabel;
    private View statusDot;
    private TextView toolbarTitle;      // Fitur A: tappable model name
    private EditText input;
    private TextView sendBtn;
    private TextView streamingBubble;   // kartu AI yang sedang di-stream
    private TextView thinkingHeader;    // header collapsible "Thinking"
    private TextView thinkingBody;      // isi think (collapse/expand)
    private View thinkingSection;       // container header+body
    private boolean thinkingExpanded = false;
    private final StringBuilder streamingText = new StringBuilder(); // RAW (dengan tag think)

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean generating = false;
    private volatile boolean switchingModel = false; // Fitur A: satu switch pada satu waktu
    private int nGeneratedLive = 0;
    private long genStartMs = 0;

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
        titleWrap.addView(toolbarTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView chev = new TextView(ctx);
        chev.setText(" ▾");
        chev.setTextSize(12);
        chev.setTextColor(0xFF9AA6B8);
        titleWrap.addView(chev, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        toolbar.addView(titleWrap, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // status: green dot + "Offline — on this device"
        LinearLayout status = new LinearLayout(ctx);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(ctx);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(0xFF34C77B);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(6);
        dot.setLayoutParams(dotLp);
        dot.setBackground(dotBg);
        status.addView(dot);
        statusDotLabel = new TextView(ctx);
        statusDotLabel.setText("Offline — on this device");
        statusDotLabel.setTextSize(12);
        statusDotLabel.setTextColor(0xFF34C77B);
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

        loadHistory();
        return root;
    }

    // ================================================================ riwayat

    private void loadHistory() {
        if (!isAdded() || conv == null) return;
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
        if (generating || switchingModel) return;
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        addBubbleUser(text);
        ChatDatabase.get(getActivity()).insertMessage(convId, ChatDatabase.ROLE_USER, text, null, null);
        if (conv != null && ("New conversation".equals(conv.title) || conv.title == null)) {
            ChatDatabase.get(getActivity()).renameConversation(convId, text);
            ((Host) getActivity()).onConversationListChanged();
        }
        startGeneration(text);
    }

    private void deleteLastUserMessage() {
        List<ChatDatabase.Message> ms = ChatDatabase.get(getActivity()).getMessages(convId);
        if (!ms.isEmpty() && ms.get(ms.size() - 1).role == ChatDatabase.ROLE_USER) {
            ChatDatabase.get(getActivity()).deleteMessage(ms.get(ms.size() - 1).id);
        }
    }

    private void stopGeneration() {
        if (sharedEngine != null) sharedEngine.stop();
    }

    private void close() {
        dismissSheet();
        ((Host) getActivity()).onChatClosed();
    }

    // ===================================================== Fitur A: switch

    /** Ganti model percakapan yang sedang dibuka (Fitur A). Jalur UI thread. */
    private void switchModelTo(ModelCatalog.Model target) {
        if (switchingModel) return;
        final ModelCatalog.Model prev = model;
        switchingModel = true;
        ChatDatabase.get(getActivity()).setConversationModel(convId, target.id);
        conv = ((Host) getActivity()).getConversation(convId);
        model = target;
        toolbarTitle.setText(target.name);

        EXECUTOR.execute(() -> {
            boolean ok = false;
            String err = null;
            try {
                LlamaEngine.loadLibrary();
                synchronized (ENGINE_LOCK) {
                    // Bebaskan engine lama jika modelnya beda (RAM benar2 kembali)
                    if (sharedEngine != null && !target.id.equals(sharedEngineModelId)) {
                        sharedEngine.free();
                        sharedEngine = null;
                        sharedEngineModelId = null;
                    }
                    if (sharedEngine == null) {
                        DeviceInfo dev = ((Host) getActivity()).deviceInfo();
                        PerfPrefs.Perf perf = PerfPrefs.load(getActivity(), dev);
                        int ctx = Math.min(perf.nCtx > 0 ? perf.nCtx : contextPerTier(dev, target.context),
                                target.context > 0 ? target.context : Integer.MAX_VALUE);
                        if (ctx < 256) ctx = 256;
                        LlamaEngine e = new LlamaEngine();
                        java.io.File f = new java.io.File(getActivity().getFilesDir(),
                                "models/" + target.id + ".gguf");
                        ok = f.exists() && e.init(f.getAbsolutePath(), ctx,
                                perf.effectiveThreads(dev != null ? dev.cpuCores : 4), perf);
                        if (ok) {
                            sharedEngine = e;
                            sharedEngineModelId = target.id;
                            sharedEngineConfigEpoch = perfEpoch();
                            sharedEngineInitCtx = ctx;
                        }
                    } else {
                        ok = true; // engine sudah memuat model target
                    }
                }
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final boolean okF = ok;
            final String errF = err;
            ui.post(() -> {
                if (!isAdded()) return;
                switchingModel = false;
                if (okF) {
                    toast("Switched to " + target.name);
                } else {
                    // Rollback: kembalikan toolbar + modelId DB ke model sebelumnya
                    // agar percakapan tidak nyangkut di model yang gagal load.
                    ChatDatabase.get(getActivity()).setConversationModel(convId, prev.id);
                    conv = ((Host) getActivity()).getConversation(convId);
                    model = prev;
                    toolbarTitle.setText(prev.name);
                    onGenerationFailed("Model failed to load"
                            + (errF != null ? " (" + errF + ")" : ""));
                }
            });
        });
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

        TextView head = new TextView(c);
        head.setText("Switch model");
        head.setTextSize(16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(0xFFEDF1F7);
        int hp = dp(16);
        head.setPadding(hp, dp(16), hp, dp(8));
        panel.addView(head);

        ScrollView scroll = new ScrollView(c);
        sheetList = new LinearLayout(c);
        sheetList.setOrientation(LinearLayout.VERTICAL);
        sheetList.setPadding(dp(8), 0, dp(8), dp(16));
        scroll.addView(sheetList);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        modelSheet.setContentView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        Window w = modelSheet.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(420));
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
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

    private void startGeneration(final String userText) {
        startGeneration(userText, false);
    }

    /** Retry (BUG2 fallback): generate ulang TANPA menambah bubble/row user baru. */
    private void startGeneration(final String userText, final boolean retry) {
        final Host host = (Host) getActivity();
        final ModelCatalog.Model m = model;
        if (m == null) {
            toast("Model not found");
            return;
        }
        generating = true;
        genStartMs = System.currentTimeMillis();
        nGeneratedLive = 0;
        streamingText.setLength(0);
        thinkingExpanded = false;
        footer.setVisibility(View.VISIBLE);
        footerStats.setText("0 tokens · 0.0 tok/s");
        sendBtn.setEnabled(false);
        sendBtn.setAlpha(0.4f);

        EXECUTOR.execute(() -> {
            // --- 1. pastikan library termuat & model siap (blocking di sini saja)
            try {
                LlamaEngine.loadLibrary();
            } catch (Throwable t) {
                ui.post(() -> onGenerationFailed("Failed to load native library: " + t.getMessage()));
                return;
            }
            synchronized (ENGINE_LOCK) {
                // Model berubah ATAU config berubah (epoch naik): bebaskan yang
                // lama dulu sebelum init ulang, supaya RAM-nya benar-benar kembali.
                if (sharedEngine != null && (!m.id.equals(sharedEngineModelId)
                        || sharedEngineConfigEpoch != perfEpoch())) {
                    sharedEngine.free();
                    sharedEngine = null;
                    sharedEngineModelId = null;
                }
                if (sharedEngine == null) {
                    DeviceInfo dev = host.deviceInfo();
                    PerfPrefs.Perf perf = PerfPrefs.load(getActivity(), dev);
                    int ctx = Math.min(perf.nCtx > 0 ? perf.nCtx : contextPerTier(dev, m.context),
                            m.context > 0 ? m.context : Integer.MAX_VALUE);
                    if (ctx < 256) ctx = 256;
                    LlamaEngine e = new LlamaEngine();
                    java.io.File f = new java.io.File(getActivity().getFilesDir(), "models/" + m.id + ".gguf");
                    boolean ok = f.exists() && e.init(f.getAbsolutePath(), ctx,
                            perf.effectiveThreads(dev != null ? dev.cpuCores : 4), perf);
                    if (!ok) {
                        ui.post(() -> onGenerationFailed(
                                "Failed to load model (not enough RAM or corrupt file)"));
                        return;
                    }
                    sharedEngine = e;
                    sharedEngineModelId = m.id;
                    sharedEngineConfigEpoch = perfEpoch();
                    sharedEngineInitCtx = ctx;
                }
            }
            // --- 2. build the prompt from history (drop the just-saved user message)
            android.content.Context appCtx = getActivity().getApplicationContext();
            List<ChatDatabase.Message> history =
                    ChatDatabase.get(appCtx).getMessages(convId);
            if (!history.isEmpty()
                    && history.get(history.size() - 1).role == ChatDatabase.ROLE_USER) {
                history = history.subList(0, history.size() - 1);
            }
            if (retry) {
                // Retry: buang juga assistant row lama yang think-only supaya
                // tidak menumpuk pesan kosong di riwayat & prompt.
                while (!history.isEmpty()
                        && history.get(history.size() - 1).role == ChatDatabase.ROLE_ASSISTANT) {
                    ChatDatabase.get(appCtx).deleteMessage(
                            history.get(history.size() - 1).id);
                    history = history.subList(0, history.size() - 1);
                }
            }
            final String prompt = ChatTemplates.build(m.chatTemplate,
                    conv != null ? conv.systemPrompt : null, history, userText);

            LlamaEngine.GenParams p = new LlamaEngine.GenParams(); // default 0.7/0.9/40/1.1/512
            final int ctx = sharedEngineConfigEpoch == perfEpoch() && sharedEngine != null
                    ? sharedEngineInitCtx : contextPerTier(host.deviceInfo(), m.context);

            boolean started = sharedEngine.generateAsync(prompt, p, ctx, new LlamaEngine.Callback() {
                @Override public void onNativeToken(String piece) {
                    ui.post(() -> { if (isAdded()) appendToken(piece); });
                }

                @Override public void onNativeDone(int nGen, int stopReason, double msTotal) {
                    ui.post(() -> { if (isAdded()) finishGeneration(nGen, stopReason, msTotal); });
                }
            });
            if (!started) {
                ui.post(() -> onGenerationFailed("Engine busy or not ready"));
            }
        });
    }

    private int contextPerTier(DeviceInfo dev, int modelContext) {
        DeviceInfo.Tier tier = dev != null ? dev.tier() : DeviceInfo.Tier.LOW;
        int ctx = tier == DeviceInfo.Tier.HIGH ? 8192
                : tier == DeviceInfo.Tier.MID ? 4096 : 2048;
        return modelContext > 0 ? Math.min(ctx, modelContext) : ctx;
    }

    private void onGenerationFailed(String msg) {
        if (!isAdded()) return; // fragment detached mid-generation
        generating = false;
        switchingModel = false;
        footer.setVisibility(View.GONE);
        sendBtn.setEnabled(true);
        sendBtn.setAlpha(1f);
        // The unanswered user message is deleted from the DB so history does
        // not keep an orphan bubble when the chat is reopened.
        deleteLastUserMessage();
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

    // Called on the UI thread per token; guards against a detached fragment
    // (back press mid-generation must not NPE).
    private void appendToken(String piece) {
        if (!isAdded() || messagesView == null) return;
        streamingText.append(piece);
        nGeneratedLive++;
        if (streamingBubble == null) {
            insertStreamingCard();
        } else {
            stopThinkingDots(); // first token arrived: stop caret blink
        }
        renderStreamingState();
        scrollToBottom();
        double secs = (System.currentTimeMillis() - genStartMs) / 1000.0;
        double tps = secs > 0.05 ? nGeneratedLive / secs : 0.0;
        footerStats.setText(nGeneratedLive + " tokens · " + fmt1(tps) + " tok/s");
    }

    /**
     * Render ulang seluruh akumulasi RAW ke (think collapsible + answer).
     * Re-split seluruh string tiap update — tag yang terbelah antar token
     * ("<thi" + "nk>") tetap terdeteksi.
     */
    private void renderStreamingState() {
        if (!isAdded() || streamingBubble == null) return;
        ThinkSplit.Result r = ThinkSplit.parse(streamingText.toString());
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
            final String raw = rawText(rawContent);
            retry.setOnClickListener(v -> {
                if (generating) return;
                // hapus tampilan section lama (note + retry ikut hilang bareng parentnya)
                messagesView.removeView(section);
                startGeneration(lastUserTextFor(raw), true);
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

    private void finishGeneration(int nGen, int stopReason, double msTotal) {
        if (!isAdded()) return; // fragment detached mid-generation
        generating = false;
        sendBtn.setEnabled(true);
        sendBtn.setAlpha(1f);
        footer.setVisibility(View.GONE);
        if (nGen < 0) {
            onGenerationFailed("Generation failed");
            return;
        }
        String content = streamingText.toString(); // RAW, dengan tag think
        double secs = msTotal / 1000.0;
        double tps = secs > 0 ? nGen / secs : 0.0;
        // Fitur B: simpan model yang aktif SAAT pesan dibuat (atribusi asal).
        ChatDatabase.get(getActivity()).insertMessage(convId, ChatDatabase.ROLE_ASSISTANT,
                content, nGen, tps, model != null ? model.id : null);
        removeStreamingCard();
        addCardAiParsed(content); // render think collapsible + answer
        TextView stats = addStatsRow();
        // Fitur B: "<Nama model> · N tokens · X tok/s"
        String mName = model != null ? model.name : null;
        stats.setText(mName != null
                ? mName + " · " + nGen + " tokens · " + fmt1(tps) + " tok/s"
                : nGen + " tokens · " + fmt1(tps) + " tok/s");
        scrollToBottom();
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
        streamingText.setLength(0);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.rightMargin = dp(48);
        lp.topMargin = dp(8);
        messagesView.addView(card, lp);
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
            row.setPadding(p, p, p, p);
            parent.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
                    TextView cap = new TextView(getActivity());
                    cap.setText("Not downloaded");
                    cap.setTextSize(12);
                    cap.setTextColor(0xFF5B6678);
                    row.addView(cap, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                }

                if (shape == SHAPE_IDLE) {
                    TextView dl = new TextView(getActivity());
                    dl.setText("Download");
                    dl.setTextColor(0xFFFFFFFF);
                    dl.setTextSize(13);
                    dl.setTypeface(Typeface.DEFAULT_BOLD);
                    dl.setBackgroundResource(R.drawable.bg_button_primary);
                    dl.setPadding(dp(14), dp(8), dp(14), dp(8));
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    dlp.topMargin = dp(8);
                    row.addView(dl, dlp);
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
