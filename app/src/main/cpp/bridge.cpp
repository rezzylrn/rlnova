// Rlnova JNI bridge — wrapper minimal di atas llama.cpp C API.
// Package Java: com.rezz.rlnova.LlamaEngine
//
// Threading model:
//  - nativeInit   : dari background thread Java; blokir sampai model termuat.
//  - nativeGenerate: dari SATU worker thread Java per handle. Loop decode
//    token-per-token; tiap token dikirim ke Java via callback
//    onNativeToken(String) DI THREAD WORKER INI (Java yang pindahkan ke UI).
//  - nativeStop   : dari thread mana pun (UI). Cuma set flag atomic; loop
//    membacanya antar iterasi decode. Stop tidak memotong llama_decode() yang
//    sedang jalan — latensi stop terburuk = 1 iterasi (~50-300ms di HP). Aman
//    dan tidak butuh kill thread.
//  - nativeFree   : dari background thread Java (onTrimMemory / ganti model).
//
// Setiap panggilan generate() = konteks baru (KV cache di-clear). Prompt
// dirangkai penuh di sisi Java (chat template per model) — bridge murni
// text-in text-out.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <chrono>

#include "llama.h"

#define TAG "rlnova-bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *     model   = nullptr;
    llama_context *   ctx     = nullptr;
    llama_sampler *   sampler = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t n_threads = 0;
    int32_t n_ctx     = 0;
    std::atomic<bool> request_stop{false};
    // mencegah dua generate() paralel di engine yang sama
    std::mutex gen_mutex;
};

inline Engine * as_engine(jlong h) { return reinterpret_cast<Engine *>(h); }

std::string to_std_string(JNIEnv * env, jstring js) {
    if (!js) return std::string();
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string s = c ? c : "";
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

struct GenParams {
    float   temperature    = 0.7f;
    float   top_p          = 0.9f;
    int32_t top_k          = 40;
    float   repeat_penalty = 1.1f;
    int32_t max_tokens     = 512;
};

// Sampler chain: penalties(64) -> top_k -> top_p -> temp -> dist.
// Urutan sama dengan rekomendasi llama.cpp (sampling.cpp common defaults).
llama_sampler * build_sampler(const GenParams & p) {
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!chain) return nullptr;
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(64, p.repeat_penalty, 0.0f, 0.0f));
    if (p.top_k > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(p.top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(p.top_p, 1));
    if (p.temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(p.temperature));
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return chain;
}

} // namespace

extern "C" {

// long nativeInit(String modelPath, int nCtx, int nThreads, int nGpuLayers)
JNIEXPORT jlong JNICALL
Java_com_rezz_rlnova_LlamaEngine_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring jModelPath,
        jint nCtx, jint nThreads, jint nGpuLayers) {
    std::string path = to_std_string(env, jModelPath);
    if (path.empty()) return 0;

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap  = true;   // SPEC: mmap on
    mparams.use_mlock = false;  // biarkan LMK reclaim RAM saat kritik
    mparams.n_gpu_layers = nGpuLayers;

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("model load gagal: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx        = (uint32_t) (nCtx > 0 ? nCtx : 2048);
    cparams.n_batch      = 512;
    cparams.n_ubatch     = 512;
    cparams.n_seq_max    = 1;
    cparams.n_threads     = nThreads > 0 ? nThreads : 2;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.embeddings   = false;
    cparams.offload_kqv  = nGpuLayers > 0;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("context init gagal (n_ctx=%u)", cparams.n_ctx);
        llama_model_free(model);
        return 0;
    }

    Engine * e = new Engine();
    e->model     = model;
    e->ctx       = ctx;
    e->vocab     = llama_model_get_vocab(model);
    e->n_threads = cparams.n_threads;
    e->n_ctx     = (int32_t) cparams.n_ctx;
    LOGI("model siap: ctx=%d threads=%d gpu=%d", e->n_ctx, e->n_threads, nGpuLayers);
    return reinterpret_cast<jlong>(e);
}

// void nativeStop(long handle)
JNIEXPORT void JNICALL
Java_com_rezz_rlnova_LlamaEngine_nativeStop(JNIEnv *, jobject, jlong handle) {
    Engine * e = as_engine(handle);
    if (e) e->request_stop.store(true);
}

// void nativeFree(long handle)
JNIEXPORT void JNICALL
Java_com_rezz_rlnova_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    Engine * e = as_engine(handle);
    if (!e) return;
    std::lock_guard<std::mutex> lock(e->gen_mutex);
    e->request_stop.store(true);
    if (e->sampler) { llama_sampler_free(e->sampler); e->sampler = nullptr; }
    if (e->ctx)     { llama_free(e->ctx);             e->ctx = nullptr; }
    if (e->model)   { llama_model_free(e->model);     e->model = nullptr; }
    delete e;
}

// int nativeGenerate(long handle, String prompt,
//     double temperature, double topP, int topK, double repeatPenalty,
//     int maxTokens, int maxContextTokens)
// return: jumlah token yang dihasilkan, -1 kalau error.
// Callback Java (di thread pemanggil): onNativeToken(String), onNativeDone(int nGen, int stopReason, double msPerTok)
JNIEXPORT jint JNICALL
Java_com_rezz_rlnova_LlamaEngine_nativeGenerate(
        JNIEnv * env, jobject thiz, jlong handle, jstring jPrompt,
        jdouble temperature, jdouble topP, jint topK, jdouble repeatPenalty,
        jint maxTokens, jint maxContextTokens) {

    Engine * e = as_engine(handle);
    if (!e || !e->ctx || !e->model || !e->vocab) return -1;

    std::lock_guard<std::mutex> lock(e->gen_mutex);

    // --- callback ids ---
    jclass cls = env->GetObjectClass(thiz);
    jmethodID midToken = env->GetMethodID(cls, "onNativeToken", "(Ljava/lang/String;)V");
    jmethodID midDone  = env->GetMethodID(cls, "onNativeDone", "(IID)V");
    env->DeleteLocalRef(cls);
    if (!midToken || !midDone) {
        LOGE("callback onNativeToken/onNativeDone tidak ditemukan");
        return -1;
    }

    // --- sampler ---
    GenParams p;
    p.temperature = (float) temperature;
    p.top_p       = (float) topP;
    p.top_k       = topK;
    p.repeat_penalty = (float) repeatPenalty;
    p.max_tokens  = maxTokens;
    if (e->sampler) { llama_sampler_free(e->sampler); e->sampler = nullptr; }
    e->sampler = build_sampler(p);
    if (!e->sampler) return -1;

    // --- tokenize prompt (add_special=false: template chat sudah menaruh BOS) ---
    std::string prompt = to_std_string(env, jPrompt);
    if (prompt.empty()) return -1;
    std::vector<llama_token> tokens(prompt.size() + 16);
    int n_tokens = llama_tokenize(e->vocab, prompt.data(), (int32_t) prompt.size(),
                                  tokens.data(), (int32_t) tokens.size(), false, true);
    if (n_tokens < 0) {
        tokens.resize((size_t) (-n_tokens));
        n_tokens = llama_tokenize(e->vocab, prompt.data(), (int32_t) prompt.size(),
                                  tokens.data(), (int32_t) tokens.size(), false, true);
    }
    if (n_tokens <= 0) {
        LOGE("tokenize gagal (%d)", n_tokens);
        return -1;
    }
    tokens.resize((size_t) n_tokens);

    // Sisakan ruang output: kalau prompt mepet konteks, buang kepala prompt
    // (system message paling terdepan yang dikorbankan dulu).
    int32_t ctx_budget = maxContextTokens > 0 ? maxContextTokens : e->n_ctx;
    if (n_tokens + 16 > ctx_budget) {
        int32_t keep = ctx_budget / 2;
        tokens.erase(tokens.begin(), tokens.end() - keep);
        n_tokens = keep;
    }

    e->request_stop.store(false);

    // KV cache fresh per request; prompt dirangkai penuh di Java.
    llama_memory_clear(llama_get_memory(e->ctx), true);
    llama_set_n_threads(e->ctx, e->n_threads, e->n_threads);

    // --- prefill: seluruh prompt dalam satu batch ---
    {
        llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
        int rc = llama_decode(e->ctx, batch);
        if (rc != 0) {
            LOGE("prefill decode gagal (%d)", rc);
            return -1;
        }
    }

    // --- loop decode ---
    const auto t0 = std::chrono::steady_clock::now();
    int32_t n_generated = 0;
    int32_t stop_reason = 0; // 0=eos/konteks, 1=user stop, 2=max_tokens
    std::vector<llama_token> cached_tokens(tokens); // buat penalties window

    llama_token cur = 0;
    bool have_cur = false;

    // Akumulasi byte UTF-8 antar token: tokenizer sering memecah 1 rune
    // (mis. huruf Indonesia beraksen, emoji) ke beberapa token. Mengirim
    // piece parsial ke NewStringUTF menghasilkan karakter rusak, jadi kita
    // tahan byte kontinuitas dan hanya flush saat rune lengkap.
    std::string pending;
    auto flush = [&](bool force) {
        if (pending.empty()) return;
        if (!force) {
            // tahan kalau byte terakhir adalah lead byte / continuation
            // dari rune UTF-8 yang belum selesai
            unsigned char last = (unsigned char) pending.back();
            if ((last & 0xC0) == 0x80) return;            // continuation tunggal
            int lead_len = 0;
            if      ((last & 0xE0) == 0xC0) lead_len = 2;
            else if ((last & 0xF0) == 0xE0) lead_len = 3;
            else if ((last & 0xF8) == 0xF0) lead_len = 4;
            if (lead_len > 0) {
                size_t start = pending.size();
                while (start > 0 && ((unsigned char) pending[start-1] & 0xC0) == 0x80) start--;
                if (pending.size() - start < (size_t) lead_len) return; // rune belum lengkap
            }
        }
        jstring jt = env->NewStringUTF(pending.c_str());
        pending.clear();
        if (jt) {
            env->CallVoidMethod(thiz, midToken, jt);
            env->DeleteLocalRef(jt);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGE("exception di callback Java — generation dihentikan");
        }
    };

    while (true) {
        if (e->request_stop.load()) { stop_reason = 1; break; }
        if (n_generated >= maxTokens) { stop_reason = 2; break; }

        // decode 1 token (pertama: logit prefill dipakai untuk token pertama)
        if (have_cur) {
            if (n_generated + n_tokens >= ctx_budget) { stop_reason = 0; break; }
            llama_batch batch = llama_batch_get_one(&cur, 1);
            int rc = llama_decode(e->ctx, batch);
            if (rc != 0) { LOGE("decode gagal (%d) di token %d", rc, n_generated); break; }
        }

        cur = llama_sampler_sample(e->sampler, e->ctx, -1);
        have_cur = true;

        if (llama_vocab_is_eog(e->vocab, cur)) { stop_reason = 0; break; }

        char piece[192];
        int n = llama_token_to_piece(e->vocab, cur, piece, sizeof(piece), 0, true);
        if (n < 0) break;

        pending.append(piece, (size_t) n);
        flush(false);

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGE("exception di callback Java — generation dihentikan");
            break;
        }

        n_generated++;
    }

    flush(true); // sisa byte terakhir (rune terpotong dihilangkan sendiri oleh UTF-8 checker Java)

    const auto t1 = std::chrono::steady_clock::now();
    double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    env->CallVoidMethod(thiz, midDone, (jint) n_generated, (jint) stop_reason, (jdouble) ms);
    return n_generated;
}

// String nativeBench(long handle)
// Benchmark prompt standar pendek di KONTEKS TERPISAH dari model yang sama:
//   1. tokenize prompt pendek (bhs Indonesia, ~15-25 token)
//   2. prefill satu batch -> t_prefill
//   3. decode 48 token greedy -> t_decode
// KV cache konteks benchmark DIHAPUS di akhir. Tidak menyentuh sampler chat
// (greedy sampler lokal, di-free di sini) dan TIDAK menyentuh gen_mutex —
// tapi JANGAN dipanggil selama generate() jalan (guard di Java: hanya
// dieksekusi kalau isGenerating() == false).
// Return: "p=<prefill_tok_per_dtk> d=<decode_tok_per_dtk>" atau
//         "ERR=<pesan>" kalau gagal.
JNIEXPORT jstring JNICALL
Java_com_rezz_rlnova_LlamaEngine_nativeBench(JNIEnv * env, jobject, jlong handle) {
    Engine * e = as_engine(handle);
    if (!e || !e->model || !e->vocab) {
        return env->NewStringUTF("ERR=engine belum siap");
    }

    const char * bench_prompt = "Ceritakan tentang AI dalam 3 kalimat.";
    std::string prompt(bench_prompt);
    std::vector<llama_token> tokens(prompt.size() + 16);
    int n_tokens = llama_tokenize(e->vocab, prompt.data(), (int32_t) prompt.size(),
                                  tokens.data(), (int32_t) tokens.size(), false, true);
    if (n_tokens < 0) {
        tokens.resize((size_t) (-n_tokens));
        n_tokens = llama_tokenize(e->vocab, prompt.data(), (int32_t) prompt.size(),
                                  tokens.data(), (int32_t) tokens.size(), false, true);
    }
    if (n_tokens <= 0) return env->NewStringUTF("ERR=tokenize gagal");
    tokens.resize((size_t) n_tokens);

    // Konteks terpisah, kecil, threads dari engine — tidak menyentuh e->ctx
    // sehingga KV cache chat aktif tetap utuh.
    llama_context_params bp = llama_context_default_params();
    bp.n_ctx    = 256;
    bp.n_batch  = 512;
    bp.n_ubatch = 512;
    bp.n_seq_max = 1;
    bp.n_threads        = e->n_threads;
    bp.n_threads_batch  = e->n_threads;
    bp.embeddings = false;
    bp.offload_kqv = false;
    llama_context * bctx = llama_init_from_model(e->model, bp);
    if (!bctx) return env->NewStringUTF("ERR=konteks bench gagal dibuat");

    std::string result;
    {
        llama_memory_clear(llama_get_memory(bctx), true);
        llama_sampler * greedy = llama_sampler_init_greedy();

        // ---- prefill: seluruh prompt satu batch, ukur waktu
        const auto t0 = std::chrono::steady_clock::now();
        llama_batch b0 = llama_batch_get_one(tokens.data(), n_tokens);
        int rc = llama_decode(bctx, b0);
        const auto t1 = std::chrono::steady_clock::now();
        float prefill_tps = 0.0f;
        if (rc == 0) {
            double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
            prefill_tps = ms > 0 ? (float) (n_tokens / (ms / 1000.0)) : 0.0f;
        }

        // ---- decode: 48 token greedy, ukur tok/dtk
        const int32_t N_BENCH = 48;
        int32_t n_gen = 0;
        const auto t1b = std::chrono::steady_clock::now();
        llama_token cur = llama_sampler_sample(greedy, bctx, -1);
        while (n_gen < N_BENCH && !llama_vocab_is_eog(e->vocab, cur)) {
            if (n_tokens + n_gen + 1 >= 256) break;
            llama_batch b = llama_batch_get_one(&cur, 1);
            rc = llama_decode(bctx, b);
            if (rc != 0) break;
            cur = llama_sampler_sample(greedy, bctx, -1);
            n_gen++;
        }
        const auto t2 = std::chrono::steady_clock::now();
        double dms = std::chrono::duration<double, std::milli>(t2 - t1b).count();
        float decode_tps = dms > 0 ? (float) (n_gen / (dms / 1000.0)) : 0.0f;
        LOGI("bench: prefill %d tok %.1f tok/s, decode %d tok %.1f tok/s",
             n_tokens, prefill_tps, n_gen, decode_tps);

        if (rc != 0 || n_gen == 0) {
            result = "ERR=decode bench gagal";
        } else {
            char buf[64];
            snprintf(buf, sizeof(buf), "p=%.2f d=%.2f", prefill_tps, decode_tps);
            result = buf;
        }
        llama_sampler_free(greedy);
    }
    llama_free(bctx);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
