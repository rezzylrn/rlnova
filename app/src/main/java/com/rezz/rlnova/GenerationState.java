package com.rezz.rlnova;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton state generation aktif (satu proses) — jembatan antara
 * GenerationService (pemilik generation) dan ChatRoomFragment (UI).
 *
 * Fragment subscribe lewat {@link #addListener}; state berisi RAW streaming
 * text, status, dan statistik terkumpul — fragment re-attach membaca snapshot
 * ini lalu subscribe update.
 */
public final class GenerationState {

    private static final GenerationState INSTANCE = new GenerationState();
    public static GenerationState get() { return INSTANCE; }
    private GenerationState() { }

    public long convId = -1;
    public String modelId;              // model yang sedang dipakai generate
    public String modelName;
    public volatile boolean generating = false;

    /** Akumulasi RAW streaming (termasuk tag think). Kunci: raw itu sendiri. */
    public final StringBuilder raw = new StringBuilder();

    public volatile int nTokens = 0;
    public volatile long genStartMs = 0;

    // Hasil final (dipakai UI untuk render kartu + stats setelah selesai)
    public int tokensFinal = -1;
    public double tokPerSec = 0;
    public String modelIdFinal;
    public int stopReason = -1;
    public String error;               // non-null = gagal
    public volatile boolean userStopRequested = false;
    public volatile boolean lastInitFailed = false; // init engine gagal terakhir (status Offline)
    /** v1.1.11: generation selesai tapi 0 token / raw kosong — tanpa kartu, tampil Retry. */
    public volatile boolean emptyGeneration = false;

    /** ConvId yang menunggu auto-judul (item 4), atau -1. */
    public volatile long pendingAutoTitleConv = -1;
    public volatile boolean autoTitleBusy = false;

    public void reset(long convId, String modelId) {
        this.convId = convId;
        this.modelId = modelId;
        this.modelName = null;
        this.tokensFinal = -1;
        this.tokPerSec = 0;
        this.modelIdFinal = null;
        this.stopReason = -1;
        this.error = null;
        this.emptyGeneration = false;
        synchronized (raw) { raw.setLength(0); }
    }

    public String rawText() {
        synchronized (raw) { return raw.toString(); }
    }

    public boolean isGeneratingFor(long conversationId) {
        return generating && convId == conversationId;
    }

    /** Tokens/dtk live untuk footer. */
    public double liveTokPerSec() {
        double secs = (System.currentTimeMillis() - genStartMs) / 1000.0;
        return secs > 0.05 ? nTokens / secs : 0.0;
    }

    // ------------------------------------------------------------- listeners

    public interface UpdateListener { void onGenerationUpdate(); }

    private final CopyOnWriteArrayList<UpdateListener> listeners =
            new CopyOnWriteArrayList<>();

    public void addListener(UpdateListener l) { listeners.add(l); }
    public void removeListener(UpdateListener l) { listeners.remove(l); }

    public void notifyUpdate() {
        for (UpdateListener l : listeners) {
            try { l.onGenerationUpdate(); } catch (Throwable ignored) { }
        }
    }
}
