package com.rezz.rlnova;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * GenerationService (v1.1.3, item 1):
 *
 * PEMILIK generation — bukan lagi ChatRoomFragment. Service foreground ini:
 *   - memegang shared LlamaEngine (sharedEngine / sharedEngineModelId /
 *     ENGINE_LOCK dipindah ke sini dari fragment),
 *   - menjalankan generation sampai SELESAI tanpa peduli UI,
 *   - saat selesai, SERVICE SENDIRI insert pesan assistant ke ChatDatabase
 *     (RAW termasuk tag think, model_id, tokens, tok/s) — bukan fragment,
 *   - menampilkan notifikasi ongoing "Generating with <model>" + action Stop
 *     + content intent yang membuka chat.
 *
 * Re-attach UI: ChatRoomFragment subscribe lewat GenerationState (singleton
 * listener). Teks streaming di-push ke state statis; fragment hidup merender.
 *
 * Aturan lama tetap: SATU generation dalam satu waktu.
 */
public class GenerationService extends Service {

    public static final String CHANNEL_ID = "rlnova_generation";
    public static final int NOTIF_ID = 1002;

    public static final String ACTION_START = "com.rezz.rlnova.action.GEN_START";
    public static final String ACTION_STOP = "com.rezz.rlnova.action.GEN_STOP";
    public static final String ACTION_TITLE = "com.rezz.rlnova.action.GEN_TITLE";
    // v1.2.2: riset WEB via toggle chat — prompt dibangun dari hasil pencarian
    // web (keyless), jawab tetap oleh model lokal on-device.
    public static final String ACTION_WEB_RESEARCH = "com.rezz.rlnova.action.GEN_WEB_RESEARCH";
    public static final String EXTRA_WEB = "webResearch"; // boolean extra di ACTION_START

    public static final String EXTRA_CONV = "conversationId";
    public static final String EXTRA_USER_TEXT = "userText";
    public static final String EXTRA_RETRY = "retry";
    public static final String EXTRA_MODEL_ID = "modelId";
    public static final String EXTRA_SYSTEM = "systemPrompt";

    // ------------------------------------------------------- shared engine
    // Dipindah dari ChatRoomFragment (v1.1.3) — pemilik sekarang service layer.

    private static LlamaEngine sharedEngine;
    private static String sharedEngineModelId;
    private static long sharedEngineConfigEpoch = -1;
    private static int sharedEngineInitCtx = 2048;
    public static final Object ENGINE_LOCK = new Object();

    /** Epoch config: naik tiap kali pengaturan performa disimpan. */
    public static long perfEpoch() { return PerfPrefs.saveEpochValue(); }

    /** Engine aktif (dipakai benchmark dari panel Optimasi). Null = belum dimuat. */
    public static LlamaEngine sharedEngine() { return sharedEngine; }

    public static String sharedEngineModelId() { return sharedEngineModelId; }

    public static boolean isBusy() { return GenerationState.get().generating; }

    /** Cek file model + ukuran cocok katalog (status Online/Offline toolbar). */
    public static boolean modelFileValid(android.content.Context ctx,
                                         ModelCatalog.Model m) {
        if (m == null || m.id == null) return false;
        java.io.File f = new java.io.File(ctx.getFilesDir(),
                "models/" + m.id + ".gguf");
        if (!f.exists() || f.length() <= 0) return false;
        if (m.sizeBytes > 0 && f.length() != m.sizeBytes) return false;
        return true;
    }

    private static final java.util.concurrent.ExecutorService EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "rlnova-generation");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    // ---------------------------------------------------------- control API

    public static void start(android.content.Context ctx, long convId,
                             String userText, boolean retry,
                             ModelCatalog.Model m, String systemPrompt) {
        start(ctx, convId, userText, retry, m, systemPrompt, false);
    }

    /** v1.2.2: overload dengan flag riset web (web=true → cari internet dulu). */
    public static void start(android.content.Context ctx, long convId,
                             String userText, boolean retry,
                             ModelCatalog.Model m, String systemPrompt,
                             boolean web) {
        Intent i = new Intent(ctx, GenerationService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_CONV, convId);
        i.putExtra(EXTRA_USER_TEXT, userText);
        i.putExtra(EXTRA_RETRY, retry);
        i.putExtra(EXTRA_MODEL_ID, m.id);
        i.putExtra(EXTRA_SYSTEM, systemPrompt);
        i.putExtra(EXTRA_WEB, web);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /** Stop dari tombol Stop di chat ATAU action Stop di notifikasi. */
    public static void requestStop() {
        GenerationState.get().userStopRequested = true;
        synchronized (ENGINE_LOCK) {
            LlamaEngine e = sharedEngine;
            if (e != null) e.stop();
        }
    }

    // --------------------------------------------------------------- service

    private long convId = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Generation", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("AI generation in progress");
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_TITLE.equals(intent.getAction())) {
            // Auto-title: jalan HANYA saat engine idle (drainTitleQueue cek lagi).
            EXECUTOR.execute(() -> {
                drainTitleQueue();
                stopSelf();
            });
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final long cid = intent.getLongExtra(EXTRA_CONV, -1);
        final String userText = intent.getStringExtra(EXTRA_USER_TEXT);
        final String mid = intent.getStringExtra(EXTRA_MODEL_ID);
        final String system = intent.getStringExtra(EXTRA_SYSTEM);
        final boolean isRetry = intent.getBooleanExtra(EXTRA_RETRY, false);
        final boolean web = intent.getBooleanExtra(EXTRA_WEB, false); // v1.2.2
        ModelCatalog.Model m = ModelCatalog.byId(allModels(), mid);
        if (m == null || userText == null || cid < 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        mainActivityRef = EMPTY_REF; // di-reset; di-set LibraryActivity.onCreate
        GenerationState gs = GenerationState.get();
        if (gs.generating) {
            // Satu generation dalam satu waktu — tolak (UI menampilkan toast).
            notifyRejected();
            stopSelf();
            return START_NOT_STICKY;
        }
        runGeneration(cid, userText, isRetry, m, system, web);
        return START_NOT_STICKY;
    }

    // =================================================================
    // Antrean auto-judul (item 4, v1.1.3) — HANYA saat engine idle.
    // Tidak memakai startForegroundService (tidak menampilkan notifikasi
    // generation); pekerjaan ringan dijalankan di EXECUTOR yang sama
    // (single-thread) sehingga TIDAK AKAN berebut engine dengan generation
    // chat — semua antrean di sini dieksekusi serial.
    // ===============================================================

    /** Reqs auto-title sekali per conv; diabaikan kalau sudah jalan. */
    public static void maybeEnqueueAutoTitle(android.content.Context ctx, long convId) {
        GenerationState st = GenerationState.get();
        if (st.autoTitleBusy || st.pendingAutoTitleConv == convId) return;
        synchronized (TITLE_LOCK) {
            if (pendingTitleConv != -1) return; // sudah ada yang antre
            pendingTitleConv = convId;
        }
        // Kirim no-op start supaya service hidup dan mengosongkan antrean.
        Intent i = new Intent(ctx, GenerationService.class);
        i.setAction(ACTION_TITLE);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    private static final Object TITLE_LOCK = new Object();
    private static volatile long pendingTitleConv = -1;

    private void drainTitleQueue() {
        long cid;
        synchronized (TITLE_LOCK) {
            if (GenerationState.get().generating) return; // engine sibuk — nanti lagi
            if (pendingTitleConv == -1) return;
            cid = pendingTitleConv;
            pendingTitleConv = -1;
        }
        final long titleConv = cid;
        final GenerationState st = GenerationState.get();
        final ChatDatabase db = ChatDatabase.get(getApplicationContext());
        final ChatDatabase.Conversation conv = db.getConversation(titleConv);
        if (conv == null) return;                       // percakapan sudah dihapus
        if (conv.titleAuto) return;                     // rename manual / sudah pernah
        if (db.countUserMessages(titleConv) < 3) return; // trigger: >= 3 pesan user

        // Model aktif = model percakapan (sama dengan yang dipakai generation chat).
        ModelCatalog.Model m = ModelCatalog.byId(allModels(), conv.modelId);
        if (m == null || !modelFileValid(this, m)) return;

        List<ChatDatabase.Message> ms = db.getMessages(titleConv);
        String firstUser = null, firstAssistant = null;
        for (ChatDatabase.Message msg : ms) {
            if (firstUser == null && msg.role == ChatDatabase.ROLE_USER) firstUser = msg.content;
            if (firstAssistant == null && msg.role == ChatDatabase.ROLE_ASSISTANT) firstAssistant = msg.content;
            if (firstUser != null && firstAssistant != null) break;
        }
        if (firstUser == null || firstAssistant == null) return;

        final String prompt = ChatTemplates.buildAutoTitlePrompt(
                m.chatTemplate, conv.systemPrompt, firstUser, firstAssistant);
        final LlamaEngine eng;
        synchronized (ENGINE_LOCK) {
            eng = sharedEngine;
        }
        if (eng == null || !eng.isReady()) return; // engine belum dimuat → diam saja

        st.autoTitleBusy = true;
        LlamaEngine.GenParams p = new LlamaEngine.GenParams();
        p.temperature = 0.2f;  // deterministik
        p.topP = 0.8f;
        p.topK = 20;
        p.repeatPenalty = 1.0f;
        p.maxTokens = 24;      // judul pendek
        boolean started;
        try {
            started = eng.generateAsync(prompt, p, sharedEngineInitCtx,
                    new LlamaEngine.Callback() {
                        @Override public void onNativeToken(String piece) {
                            synchronized (st.raw) { st.raw.append(piece); }
                        }
                        @Override public void onNativeDone(int nGen, int stopReason,
                                                           double msTotal) {
                            finishTitleGeneration(titleConv, st);
                        }
                    });
        } catch (Throwable t) {
            started = false;
        }
        if (!started) {
            // Gagal/kosong: diam, tanpa retry berulang.
            st.autoTitleBusy = false;
        }
    }

    /** Bersihkan hasil title (kutip/spasi/prefix) + update DB + refresh list. */
    private void finishTitleGeneration(final long convId, GenerationState st) {
        String out;
        synchronized (st.raw) { out = st.raw.toString(); }
        String title = cleanTitle(out);
        st.raw.setLength(0);
        st.autoTitleBusy = false;
        if (title == null || title.isEmpty()) return; // gagal/kosong: diam
        ChatDatabase db = ChatDatabase.get(getApplicationContext());
        ChatDatabase.Conversation conv = db.getConversation(convId);
        if (conv == null) return;                      // sudah dihapus
        if (conv.titleAuto) return;                    // rename manual menang — jangan timpa
        db.setAutoTitle(convId, title);
        uiHandler.post(() -> {
            // refresh list bila fragment chat-list sedang tampil
            android.app.Fragment f = null;
            try {
                android.app.Activity act = mainActivityRef == null ? null : mainActivityRef.get();
                if (act instanceof LibraryActivity) {
                    f = ((LibraryActivity) act).getFragmentManager()
                            .findFragmentById(R.id.content);
                }
            } catch (Throwable ignored) { }
            if (f instanceof ChatListFragment) ((ChatListFragment) f).reload();
        });
    }

    /** Rapikan output model → judul pendek. Null kalau tidak layak dipakai. */
    private static String cleanTitle(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        // buang think block kalau model sempat memuntahkannya
        int idx = t.indexOf("</think>");
        if (idx >= 0) t = t.substring(idx + "</think>".length()).trim();
        t = t.replace("\"", "").replace("\u201c", "").replace("\u201d", "")
             .replace("'", "").replace("\u2018", "").replace("\u2019", "").trim();
        // buang prefiks "Title:" kalau sempat ikut
        if (t.toLowerCase(Locale.US).startsWith("title:")) t = t.substring(6).trim();
        // potong ke baris pertama (model kadang nambah penjelasan)
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(0, nl).trim();
        // buang tanda baca di ujung
        while (!t.isEmpty() && !Character.isLetterOrDigit(t.charAt(t.length() - 1))) {
            t = t.substring(0, t.length() - 1).trim();
        }
        if (t.isEmpty()) return null;
        if (t.length() > 60) t = t.substring(0, 60);
        return t;
    }

    private final android.os.Handler uiHandler = new android.os.Handler(Looper.getMainLooper());

    /** Referensi lemah activity — untuk refresh list setelah auto-title. */
    private static final java.lang.ref.WeakReference<android.app.Activity> EMPTY_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<android.app.Activity> mainActivityRef = EMPTY_REF;

    /** Di-set LibraryActivity.onCreate — supaya service bisa refresh list. */
    public static void setMainActivityRef(android.app.Activity act) {
        mainActivityRef = new java.lang.ref.WeakReference<>(act);
    }

    /** Katalog dibaca ulang dari assets — service bisa hidup tanpa Activity. */
    private java.util.List<ModelCatalog.Model> allModels() {
        try {
            return ModelCatalog.load(this).models;
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    // ------------------------------------------------------------ generation

    private void runGeneration(final long cid, final String userText,
                               final boolean isRetry, final ModelCatalog.Model m,
                               final String system, final boolean web) {
        convId = cid;
        final GenerationState st = GenerationState.get();
        st.reset(cid, m.id);
        st.generating = true;
        st.modelName = m.name;
        st.genStartMs = System.currentTimeMillis();
        st.nTokens = 0;
        st.stopReason = -1;
        st.error = null;
        st.userStopRequested = false;
        // v1.2.2: notification text beda untuk riset web (fase pencarian).
        startForeground(NOTIF_ID, buildNotification(m.name,
                web ? "Searching the web…" : "Waiting for model…"));
        notifyToken();

        EXECUTOR.execute(() -> {
            // 0. v1.2.2: toggle research ON → cari web DULU (worker thread,
            //    sebelum nyentuh engine). Gagal jaringan/hasil kosong → jujur,
            //    TIDAK ada fallback karangan.
            String evidence = null;
            if (web) {
                evidence = WebSearch.search(userText);
                if (WebSearch.isEmpty(evidence)) {
                    st.generating = false;
                    st.stopReason = -1;
                    st.error = "No internet connection or no results — cannot search the web.";
                    notifyDone();
                    stopForeground(true);
                    stopSelf();
                    return;
                }
            }
            // 1. Library native
            try {
                LlamaEngine.loadLibrary();
            } catch (Throwable t) {
                fail("Failed to load native library: " + t.getMessage());
                return;
            }
            // 2. Pastikan engine memuat model target (ownership di sini)
            synchronized (ENGINE_LOCK) {
                if (sharedEngine != null && (!m.id.equals(sharedEngineModelId)
                        || sharedEngineConfigEpoch != perfEpoch())) {
                    sharedEngine.free();
                    sharedEngine = null;
                    sharedEngineModelId = null;
                }
                if (sharedEngine == null) {
                    DeviceInfo dev = PerfPrefs.currentDevice(getApplicationContext());
                    PerfPrefs.Perf perf = PerfPrefs.load(this, dev);
                    int ctx = contextPerTier(dev, m.context);
                    LlamaEngine e = new LlamaEngine();
                    java.io.File f = new java.io.File(getFilesDir(),
                            "models/" + m.id + ".gguf");
                    boolean ok = f.exists()
                            && e.init(f.getAbsolutePath(), ctx,
                                  perf.effectiveThreads(dev != null ? dev.cpuCores : 4), perf);
                    if (!ok) {
                        st.lastInitFailed = true;
                        fail("Failed to load model (not enough RAM or corrupt file)");
                        return;
                    }
                    sharedEngine = e;
                    sharedEngineModelId = m.id;
                    sharedEngineConfigEpoch = perfEpoch();
                    sharedEngineInitCtx = ctx;
                }
                st.lastInitFailed = false;
            }
            // 3. Riwayat + prompt
            android.content.Context appCtx = getApplicationContext();
            ChatDatabase db = ChatDatabase.get(appCtx);
            List<ChatDatabase.Message> history = db.getMessages(cid);
            if (!isRetry && !history.isEmpty()
                    && history.get(history.size() - 1).role == ChatDatabase.ROLE_USER) {
                history = history.subList(0, history.size() - 1);
            }
            if (isRetry) {
                // Retry: buang assistant row lama yang think-only.
                while (!history.isEmpty()
                        && history.get(history.size() - 1).role == ChatDatabase.ROLE_ASSISTANT) {
                    db.deleteMessage(history.get(history.size() - 1).id);
                    history = history.subList(0, history.size() - 1);
                }
            }
            final String prompt = web
                    ? ChatTemplates.build(m.chatTemplate,
                          WebSearch.researchSystemPrompt(),
                          history, WebSearch.userPrompt(userText, evidence))
                    : ChatTemplates.build(m.chatTemplate, system,
                          history, userText);

            LlamaEngine.GenParams p = new LlamaEngine.GenParams();
            final LlamaEngine eng;
            synchronized (ENGINE_LOCK) {
                eng = sharedEngine;
            }
            boolean started = eng != null && eng.isReady()
                    && eng.generateAsync(prompt, p, sharedEngineInitCtx,
                        new LlamaEngine.Callback() {
                @Override public void onNativeToken(String piece) {
                    GenerationState g = GenerationState.get();
                    synchronized (g.raw) {
                        g.raw.append(piece);
                    }
                    g.nTokens++;
                    notifyToken();
                }

                @Override public void onNativeDone(int nGen, int stopReason,
                                                   double msTotal) {
                    finishFromWorker(cid, m, nGen, stopReason, msTotal);
                }
            });
            if (!started) {
                fail("Engine busy or not ready");
            }
        });
    }

    private int contextPerTier(DeviceInfo dev, int modelContext) {
        DeviceInfo.Tier tier = dev != null ? dev.tier() : DeviceInfo.Tier.LOW;
        int ctx = tier == DeviceInfo.Tier.HIGH ? 8192
                : tier == DeviceInfo.Tier.MID ? 4096 : 2048;
        if (modelContext > 0) ctx = Math.min(ctx, modelContext);
        return ctx;
    }

    // v1.2.2: runResearch / startResearch / researchFail / finishResearch
    // (paket pengetahuan lokal v1.2.0) dihapus — riset sekarang via toggle web
    // di chat: WebSearch.search() di awal runGeneration, jawab tetap model lokal.

    private void fail(final String msg) {
        GenerationState st = GenerationState.get();
        st.generating = false;
        st.stopReason = -1;
        st.error = msg;
        notifyDone();
        stopForeground(true);
        stopSelf();
    }

    /**
     * SELESAI: dipanggil dari worker thread generate. Insert DB DI SINI —
     * di service, bukan fragment — supaya output tidak pernah kepangkas walau
     * UI ditutup / fragment dihancurkan / aplikasi di-background.
     */
    private void finishFromWorker(long cid, ModelCatalog.Model m,
                                  int nGen, int stopReason, double msTotal) {
        GenerationState st = GenerationState.get();
        st.generating = false;
        st.stopReason = nGen;
        if (nGen >= 0) {
            String rawOut;
            synchronized (st.raw) { rawOut = st.raw.toString(); }
            // v1.1.11 FIX: model yang langsung mengeluarkan EOS (0 token, raw
            // kosong) jangan disimpan sebagai pesan assistant — dulu jadi kartu
            // kosong + "0 tokens". Tandai error supaya UI menampilkan catatan
            // "No answer generated." + tombol Retry, dan JANGAN insert DB.
            if (rawOut.trim().isEmpty() && nGen == 0) {
                st.raw.setLength(0);
                st.tokensFinal = 0;
                st.tokPerSec = 0;
                st.modelIdFinal = m.id;
                st.stopReason = -2;       // penanda "kosong" untuk fragment
                st.emptyGeneration = true;
                notifyDone();
                stopForeground(true);
                stopSelf();
                return;
            }
            double secs = msTotal / 1000.0;
            double tps = secs > 0 ? nGen / secs : 0.0;
            // SERVICE yang insert pesan assistant (RAW + stats + model_id).
            ChatDatabase.get(getApplicationContext()).insertMessage(cid,
                    ChatDatabase.ROLE_ASSISTANT, st.raw.toString(),
                    nGen, tps, m.id);
            st.tokensFinal = nGen;
            st.tokPerSec = tps;
            st.modelIdFinal = m.id;
            st.pendingAutoTitleConv = cid; // item 4: antrekan auto-judul
            // Antrekan auto-judul SEKALI — hanya saat trigger tercapai & belum pernah.
            tryEnqueueAutoTitleIfEligible(getApplicationContext(), cid);
        } else {
            st.error = "Generation failed";
        }
        notifyDone();
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------- listeners

    /**
     * Cek trigger auto-title (>= 3 pesan user DAN title_auto=0) lalu antrekan.
     * Aman dipanggil dari worker thread; DB cek ringan.
     */
    private void tryEnqueueAutoTitleIfEligible(android.content.Context ctx, long cid) {
        try {
            ChatDatabase db = ChatDatabase.get(ctx);
            ChatDatabase.Conversation c = db.getConversation(cid);
            if (c == null || c.titleAuto) return;      // rename manual / sudah pernah
            if (db.countUserMessages(cid) < 3) return;  // trigger: >= 3 pesan user
            maybeEnqueueAutoTitle(ctx, cid);
        } catch (Throwable ignored) { }
    }

    public interface Listener {
        void onToken();
        void onDone(boolean success);
        void onRejected(String msg);
    }

    private static final java.util.List<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    public static void addListener(Listener l) { LISTENERS.add(l); }
    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    private void notifyToken() {
        GenerationState.get().notifyUpdate();
    }

    private void notifyRejected() {
        for (Listener l : LISTENERS) {
            try { l.onRejected("A generation is already running"); }
            catch (Throwable ignored) { }
        }
        GenerationState.get().notifyUpdate();
    }

    private void notifyDone() {
        for (Listener l : LISTENERS) {
            try { l.onDone(GenerationState.get().stopReason >= 0); }
            catch (Throwable ignored) { }
        }
        GenerationState.get().notifyUpdate();
    }

    // ---------------------------------------------------------- notifikasi

    private Notification buildNotification(String modelName, String text) {
        Intent open = new Intent(this, LibraryActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("openConvId", convId);
        PendingIntent pi = PendingIntent.getActivity(this, 3001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, GenerationService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 3002, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Generating with " + modelName)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
