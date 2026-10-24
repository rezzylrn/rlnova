package com.rezz.rlnova;

import android.util.Log;

/**
 * Wrapper Java untuk JNI bridge librlnova-whisper.so (whisper.cpp, CPU-only,
 * di-link statik — lihat cpp/whisper-jni.cpp untuk alasan linking-nya).
 *
 * Threading model (sama polanya dengan LlamaEngine):
 *  - loadLibrary()  : sekali, background thread (System.loadLibrary buka file besar).
 *  - init()         : background thread (blocking; model 75 MB termuat ke RAM).
 *  - transcribe()   : SATU worker thread; blocking sampai transkrip selesai.
 *                     Callback onNativeDone dipanggil DI THREAD ITU.
 *  - cancel()       : thread mana pun (UI); flag atomik dibaca callback abort
 *                     whisper antar segmen.
 *  - free()         : background thread.
 */
public final class WhisperEngine {

    private static final String TAG = "WhisperEngine";
    private static volatile boolean loaded = false;

    /** Load librlnova-whisper.so — self-contained (static ggml). Background thread. */
    public static synchronized void loadLibrary() throws UnsatisfiedLinkError {
        if (loaded) return;
        System.loadLibrary("rlnova-whisper");
        loaded = true;
    }

    public static boolean libraryLoaded() { return loaded; }

    private long handle = 0;
    private volatile Thread worker;
    private final Object lock = new Object();

    /**
     * Muat model whisper ggml dari path. Blocking — panggil dari background thread.
     * @return true kalau model siap.
     */
    public boolean init(String modelPath) {
        if (handle != 0) return true;
        handle = nativeWhisperInit(modelPath);
        return handle != 0;
    }

    public boolean isReady() { return handle != 0; }

    /**
     * Transkripsi PCM 16 kHz mono. Blocking — WAJIB dari background thread.
     * Satu transkripsi pada satu waktu; panggilan paralel ditolak (return false).
     *
     * @param pcm      sampel audio 16-bit PCM mono 16 kHz
     * @param callback dipanggil di thread pemanggil saat selesai/gagal
     */
    public synchronized boolean transcribeAsync(final short[] pcm, final int nSamples,
                                                final Callback callback) {
        if (handle == 0 || worker != null && worker.isAlive()) return false;
        final long h = handle;
        worker = new Thread(() -> {
            String text = null;
            try {
                text = nativeWhisperTranscribe(h, pcm, nSamples);
            } catch (Throwable t) {
                android.util.Log.e("WhisperEngine", "transcribe crash", t);
            }
            final String result = text;
            try {
                callback.onTranscribeDone(result);
            } catch (Throwable ignored) { }
        }, "rlnova-stt");
        worker.start();
        return true;
    }

    public boolean isTranscribing() {
        Thread w = worker;
        return w != null && w.isAlive();
    }

    /** Minta pembatalan transkripsi yang sedang jalan (aman dari thread mana pun). */
    public void cancel() {
        if (handle != 0) nativeWhisperCancel(handle);
    }

    /** Bebaskan model. Blocking — panggil dari background thread. */
    public synchronized void free() {
        long h = handle;
        handle = 0;
        if (h != 0) nativeWhisperFree(h);
    }

    /** Callback — dipanggil dari worker thread. result null = gagal. */
    public interface Callback {
        void onTranscribeDone(String result);
    }

    // ---------------------------------------------------------------- native

    // long nativeWhisperInit(String modelPath) -> handle, 0 on failure
    private native long nativeWhisperInit(String modelPath);

    // void nativeWhisperFree(long handle)
    private native void nativeWhisperFree(long handle);

    // String nativeWhisperTranscribe(long handle, short[] pcm, int nSamples)
    //   -> transcript ("" bila hening), null bila gagal. Blokir — worker thread.
    private native String nativeWhisperTranscribe(long handle, short[] pcm, int nSamples);

    // void nativeWhisperCancel(long handle)
    private native void nativeWhisperCancel(long handle);
}
