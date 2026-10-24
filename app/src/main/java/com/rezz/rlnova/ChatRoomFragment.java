package com.rezz.rlnova;

import android.animation.ObjectAnimator;
import android.app.Fragment;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * ChatRoomFragment (mockup 03, chat room page):
 *   toolbar   : back + model name + green dot "Offline - this device"
 *   scroll    : user bubble right (primary), AI card left + streaming cursor
 *   footer    : mono "N tokens · X.X tok/s" + "Stop" button (while generating)
 *   composer  : rounded 12dp text field + 48dp send button
 *
 * Threading: loadLibrary/init/generate run on a background thread (single-
 * thread executor); native callbacks -> UI Handler for rendering; stop() is
 * called straight from the UI thread (atomic flag in native, latency <= 1
 * decode iteration).
 */
public class ChatRoomFragment extends Fragment {

    public interface Host {
        long createConversation(String modelId, String systemPrompt);
        ChatDatabase.Conversation getConversation(long id);
        ModelCatalog.Model getModel(String modelId);
        DeviceInfo deviceInfo();
        void onChatClosed();
        void onConversationListChanged();
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
    private EditText input;
    private TextView sendBtn;
    private TextView streamingBubble;   // kartu AI yang sedang di-stream
    private final StringBuilder streamingText = new StringBuilder();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean generating = false;
    private long aiMessageRowId = -1;
    private int nGeneratedLive = 0;
    private long genStartMs = 0;

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

        TextView title = new TextView(ctx);
        title.setText(model != null ? model.name : "Rlnova");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFEDF1F7);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolbar.addView(title, new LinearLayout.LayoutParams(
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
                addBubbleUser(m.content);
            } else if (m.role == ChatDatabase.ROLE_ASSISTANT) {
                addCardAi(m.content);
                if (m.tokens != null && m.tokPerSec != null) {
                    TextView st = addStatsRow();
                    st.setText(m.tokens + " tokens · " + fmt1(m.tokPerSec) + " tok/s");
                }
            }
        }
        scrollToBottom();
    }

    // ================================================================ kirim

    private void onSendClicked() {
        if (generating) return;
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
        ((Host) getActivity()).onChatClosed();
    }

    // ============================================================ generation

    private void startGeneration(final String userText) {
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
        streamingBubble.setText(streamingText + "▍");
        scrollToBottom();
        double secs = (System.currentTimeMillis() - genStartMs) / 1000.0;
        double tps = secs > 0.05 ? nGeneratedLive / secs : 0.0;
        footerStats.setText(nGeneratedLive + " tokens · " + fmt1(tps) + " tok/s");
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
        String content = streamingText.toString();
        double secs = msTotal / 1000.0;
        double tps = secs > 0 ? nGen / secs : 0.0;
        ChatDatabase.get(getActivity()).insertMessage(convId, ChatDatabase.ROLE_ASSISTANT,
                content, nGen, tps);
        removeStreamingCard();
        addCardAi(content);
        TextView stats = addStatsRow();
        stats.setText(nGen + " tokens · " + fmt1(tps) + " tok/s");
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
        streamingText.setLength(0);
    }

    // ------------------------------------------------------------------- UI

    private void addBubbleUser(String text) {
        TextView b = new TextView(getActivity());
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundResource(R.drawable.bg_bubble_user);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
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
}
