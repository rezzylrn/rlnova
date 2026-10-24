package com.rezz.rlnova;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Wrapper Java untuk JNI bridge librlnova-bridge.so (llama.cpp).
 *
 * Threading model:
 *  - loadLibrary()          : wajib dipanggil sekali, DI BACKGROUND THREAD
 *                             (System.loadLibrary membuka file besar).
 *  - init()                 : background thread (blocking, model load butuh detik).
 *  - generate()             : SATU worker thread per Engine instance. Callback
 *                             onNativeToken/onNativeDone dipanggil DI THREAD ITU.
 *  - stop()                 : thread mana pun (UI). Hanya set flag atomik di
 *                             native — loop decode berhenti antar iterasi.
 *  - free()                 : background thread (onDestroy / ganti model).
 *
 * Kontrak callback (dideklarasikan abstrak di sini, diimplement Host):
 *  - void onNativeToken(String piece)
 *  - void onNativeDone(int nGenerated, int stopReason, double msTotal)
 *    stopReason: 0=eos/konteks, 1=user stop, 2=max_tokens
 */
public final class LlamaEngine {

    private static final String TAG = "LlamaEngine";
    private static volatile boolean loaded = false;

    /** Load the .so chain in order (inter-library deps). MUST run on a background thread.
     *  v1.1.5: ggml-vulkan dimuat juga (backend GPU). DT_NEEDED-nya libggml-base +
     *  libvulkan (system) — dimuat SETELAH ggml, SESUDAH ggml-cpu, SEBELUM llama. */
    public static synchronized void loadLibrary() throws UnsatisfiedLinkError {
        if (loaded) return;
        System.loadLibrary("ggml-base");
        System.loadLibrary("ggml-cpu");
        System.loadLibrary("ggml");
        try {
            System.loadLibrary("ggml-vulkan");
        } catch (UnsatisfiedLinkError e) {
            // Device tanpa driver Vulkan / lib rusak: backend Vulkan tidak
            // terdaftar — engine tetap jalan penuh di CPU (fallback diam).
            android.util.Log.w(TAG, "libggml-vulkan tidak bisa dimuat — CPU only", e);
        }
        System.loadLibrary("llama");
        System.loadLibrary("rlnova-bridge");
        loaded = true;
    }

    public static boolean isLoaded() { return loaded; }

    // ---------------------------------------------------------------- state

    private long handle = 0;
    private volatile Callback activeCallback;
    private Thread worker;
    private volatile boolean generating = false;
    private final Object lock = new Object();

    // ---------------------------------------------------------------- init

    /**
     * Muat model dengan config performa tersimpan (PerfPrefs): threads,
     * gpuLayers, dan contextTokens. Blocking — panggil dari background thread.
     * @param perf prefs performa (PerfPrefs.load()); null = default CPU 2 thread.
     * @return true kalau model siap.
     */
    public boolean init(String modelPath, int contextTokens, int threads) {
        return init(modelPath, contextTokens, threads, null);
    }

    /**
     * Muat model dengan PerfPrefs (threads/GPU layers/ctx dari SharedPreferences).
     * v1.1.5: kalau gpuLayers > 0 tapi init gagal (driver Vulkan tidak ada /
     * tidak cocok / OOM GPU), FALLBACK DIAM-DIAM ke CPU (gpuLayers=0) — sekali
     * saja. Fallback TIDAK mengubah prefs tersimpan.
     * @return true kalau model siap.
     */
    public boolean init(String modelPath, int contextTokens, int threads, PerfPrefs.Perf perf) {
        if (handle != 0) return true;
        int gpuLayers = 0, nCtx = contextTokens;
        if (perf != null) {
            threads = perf.effectiveThreads(Runtime.getRuntime().availableProcessors());
            gpuLayers = perf.effectiveGpuLayers();
        }
        if (gpuLayers > 0) {
            handle = nativeInit(modelPath, contextTokens, threads, gpuLayers);
            if (handle == 0) {
                // fallback senyap: init GPU gagal → CPU penuh, engine tetap Online
                android.util.Log.w(TAG, "init dgn GPU layers=" + gpuLayers
                        + " gagal — fallback ke CPU");
                handle = nativeInit(modelPath, contextTokens, threads, 0);
            }
        } else {
            handle = nativeInit(modelPath, contextTokens, threads, 0);
        }
        return handle != 0;
    }

    public boolean isReady() { return handle != 0; }

    /**
     * Benchmark kecepatan (tok/dtk prefill + decode) di konteks native terpisah.
     * HARUS dipanggil dari background thread & saat isGenerating() == false.
     * Return string "p=x d=y" atau "ERR=pesan".
     */
    public String bench() {
        if (handle == 0) return "ERR=engine not ready";
        return nativeBench(handle);
    }

    /**
     * Jalankan generate di background thread. Return thread-nya (untuk join
     * di pemanggil kalau perlu). Hanya satu generate aktif per Engine —
     * panggilan baru selagi jalan langsung ditolak (return false).
     *
     * @param prompt   prompt final SUDAH dirangkai chat template (string penuh)
     * @param callback dipanggil di worker thread
     */
    public synchronized boolean generateAsync(final String prompt,
                                              final GenParams params,
                                              final int maxContextTokens,
                                              final Callback callback) {
        if (handle == 0 || generating) return false;
        generating = true;
        final long h = handle;
        activeCallback = callback;
        worker = new Thread(() -> {
            int n = -1;
            try {
                n = nativeGenerate(h, prompt,
                        paramsTemperature(params), paramsTopP(params), paramsTopK(params),
                        paramsRepeat(params), paramsMaxTokens(params), maxContextTokens);
            } catch (Throwable t) {
                Log.e(TAG, "generate crash", t);
            } finally {
                generating = false;
                activeCallback = null;
            }
            if (n < 0) {
                // pastikan Java side tau kalau error (nativeDone mungkin tidak terpanggil)
                try {
                    callback.onNativeDone(-1, -1, 0);
                } catch (Throwable ignored) { }
            }
        }, "rlnova-gen");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
        return true;
    }

    public boolean isGenerating() { return generating; }

    /** Stop generation (dari UI thread — aman kapan pun). */
    public void stop() {
        if (handle != 0) nativeStop(handle);
    }

    /** Bebaskan native resources. Blocking — panggil dari background thread. */
    public synchronized void free() {
        long h = handle;
        handle = 0;
        if (h != 0) nativeFree(h);
    }

    // ------------------------------------------------------------------
    // JNI callback dispatch — dipanggil DARI NATIVE (worker thread).
    // Nama method & signature harus persis: onNativeToken(String)V dan
    // onNativeDone(IID)V karena GetMethodID di bridge pakai nama ini.
    // ------------------------------------------------------------------

    @SuppressWarnings("unused")
    private void onNativeToken(String piece) {
        Callback cb = activeCallback;
        if (cb != null) cb.onNativeToken(piece);
    }

    @SuppressWarnings("unused")
    private void onNativeDone(int nGenerated, int stopReason, double msTotal) {
        Callback cb = activeCallback;
        if (cb != null) cb.onNativeDone(nGenerated, stopReason, msTotal);
    }

    /** Cek cepat: lib termuat (dipakai UI untuk gate). */
    public static boolean libraryLoaded() { return loaded; }

    // ---------------------------------------------------------------- params

    public static final class GenParams {
        public float temperature    = 0.7f;
        public float topP           = 0.9f;
        public int   topK           = 40;
        public float repeatPenalty  = 1.1f;
        public int   maxTokens      = 512;
    }

    // aksesornya static-final-safe supaya bridge bisa pakai field langsung
    private static float paramsTemperature(GenParams p) { return p.temperature; }
    private static float paramsTopP(GenParams p)        { return p.topP; }
    private static int   paramsTopK(GenParams p)        { return p.topK; }
    private static float paramsRepeat(GenParams p)      { return p.repeatPenalty; }
    private static int   paramsMaxTokens(GenParams p)   { return p.maxTokens; }

    /** Callback — dipanggil dari worker thread native. */
    public interface Callback {
        void onNativeToken(String piece);
        void onNativeDone(int nGenerated, int stopReason, double msTotal);
    }

    // ---------------------------------------------------------------- native

    // long nativeInit(String modelPath, int nCtx, int nThreads, int nGpuLayers)
    private native long nativeInit(String modelPath, int nCtx, int nThreads, int nGpuLayers);

    // void nativeStop(long handle)
    private native void nativeStop(long handle);

    // void nativeFree(long handle)
    private native void nativeFree(long handle);

    // int nativeGenerate(long handle, String prompt, double temp, double topP,
    //     int topK, double repeatPenalty, int maxTokens, int maxContextTokens)
    //   -> number of tokens generated; -1 on error
    //   callbacks on the calling thread: onNativeToken(String), onNativeDone(IID)V
    private native int nativeGenerate(long handle, String prompt,
            double temperature, double topP, int topK, double repeatPenalty,
            int maxTokens, int maxContextTokens);

    // String nativeBench(long handle) — prefill/decode benchmark, separate context
    // returns "p=<prefill tps> d=<decode tps>" or "ERR=<message>"
    private native String nativeBench(long handle);
}
