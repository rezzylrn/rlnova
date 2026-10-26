// Rlnova JNI bridge — whisper.cpp (STT on-device), CPU-only.
// Package Java: com.rezz.rlnova.WhisperEngine
//
// Static-build strategy:
//   libwhisper.a + libggml*.a (whisper.cpp's vendored ggml 0.27) are linked
//   STATICALLY into this one .so with -fvisibility=hidden. Nothing ggml-
//   related is exported, so it can never collide with the llama.cpp chain
//   (llama.cpp vendors a different ggml — 0.16 — as shared libs in the same
//   APK; exporting ggml symbols here would break symbol interposition).
//   GGML_BACKEND_DL is OFF, so the registry never dlopens backends.
//
// Threading: one transcription at a time per context (mutex). The Java side
// calls nativeWhisperTranscribe on a dedicated worker thread; a cancel flag
// is checked by whisper_full's progress callback between segments.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>

#include "whisper.h"

#define TAG "rlnova-whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct WhisperCtx {
    whisper_context * ctx = nullptr;
    std::mutex mutex;               // one transcription at a time
    std::atomic<bool> cancel{false};
};

inline WhisperCtx * as_ctx(jlong h) { return reinterpret_cast<WhisperCtx *>(h); }

std::string jstr(JNIEnv * env, jstring js) {
    if (!js) return std::string();
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string s = c ? c : "";
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

} // namespace

extern "C" {

// long nativeWhisperInit(String modelPath)  -> handle (0 on failure)
JNIEXPORT jlong JNICALL
Java_com_rezz_rlnova_WhisperEngine_nativeWhisperInit(JNIEnv * env, jobject, jstring jPath) {
    std::string path = jstr(env, jPath);
    if (path.empty()) return 0;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), whisper_context_default_params());
    if (!ctx) {
        LOGE("whisper_init_from_file failed: %s", path.c_str());
        return 0;
    }
    LOGI("whisper model loaded: %s", path.c_str());
    return reinterpret_cast<jlong>(new WhisperCtx{ctx});
}

// void nativeWhisperFree(long handle)
JNIEXPORT void JNICALL
Java_com_rezz_rlnova_WhisperEngine_nativeWhisperFree(JNIEnv *, jobject, jlong h) {
    WhisperCtx * wc = as_ctx(h);
    if (!wc) return;
    wc->cancel = true;
    std::lock_guard<std::mutex> lock(wc->mutex);
    if (wc->ctx) whisper_free(wc->ctx);
    delete wc;
}

// String nativeWhisperTranscribe(long handle, short[] pcm, int nSamples)
//   -> transcript text, or null on error. Blocking; call from a worker thread.
JNIEXPORT jstring JNICALL
Java_com_rezz_rlnova_WhisperEngine_nativeWhisperTranscribe(
        JNIEnv * env, jobject, jlong h, jshortArray jPcm, jint nSamples) {

    WhisperCtx * wc = as_ctx(h);
    if (!wc || !wc->ctx || nSamples <= 0) return nullptr;

    jsize len = env->GetArrayLength(jPcm);
    if (nSamples > len) nSamples = len;

    std::vector<float> pcm((size_t) nSamples);
    {
        // GetShortArrayElements may pin or copy; for a few seconds of 16 kHz
        // audio (<= ~1 MB) a copy is cheap and safe.
        std::vector<jshort> raw((size_t) nSamples);
        env->GetShortArrayRegion(jPcm, 0, nSamples, raw.data());
        for (int i = 0; i < nSamples; i++) pcm[(size_t) i] = raw[(size_t) i] / 32768.0f;
    }

    wc->cancel = false;
    std::lock_guard<std::mutex> lock(wc->mutex);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = 2;
    p.translate        = false;          // transcript in the spoken language
    p.print_progress   = false;
    p.print_special    = false;
    p.print_realtime   = false;
    p.print_timestamps = false;
    p.single_segment   = false;
    p.no_context       = true;           // don't carry context across calls
    p.suppress_blank   = true;
    p.suppress_nst     = true;
    // ggml_abort_callback: return true to ABORT ("If it returns true, the
    // computation is aborted" — ggml.h), false to continue. Jadi abort hanya
    // saat flag cancel di-set; v1.1.12 fix — versi lama membalik kondisinya
    // (return !(cancel)) sehingga ggml meng-abort SEGERA setiap transkripsi.
    p.abort_callback = [](void * user_data) -> bool {
        WhisperCtx * wc2 = (WhisperCtx *) user_data;
        return (wc2 && wc2->cancel.load());
    };
    p.abort_callback_user_data = wc;

    if (whisper_full(wc->ctx, p, pcm.data(), nSamples) != 0) {
        LOGE("whisper_full failed");
        return nullptr;
    }

    std::string text;
    const int n = whisper_full_n_segments(wc->ctx);
    for (int i = 0; i < n; i++) {
        const char * seg = whisper_full_get_segment_text(wc->ctx, i);
        if (seg) text += seg;
    }
    // trim leading/trailing whitespace
    size_t b = text.find_first_not_of(" \t\n\r");
    if (b == std::string::npos) return env->NewStringUTF("");
    size_t e = text.find_last_not_of(" \t\n\r");
    return env->NewStringUTF(text.substr(b, e - b + 1).c_str());
}

// void nativeWhisperCancel(long handle) — request cancel of the running transcribe
JNIEXPORT void JNICALL
Java_com_rezz_rlnova_WhisperEngine_nativeWhisperCancel(JNIEnv *, jobject, jlong h) {
    WhisperCtx * wc = as_ctx(h);
    if (wc) wc->cancel = true;
}

} // extern "C"
