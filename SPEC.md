# Offline AI App — SPEC v1.0

Aplikasi Android (APK) chatbot AI yang berjalan 100% offline di HP.
Perintah rezz (2026-10-24): ada spesifikasi tier — low-end sampai high-end phone,
masing-masing bisa menjalankan model tertentu sesuai spesifikasi HP.
Design WAJIB digenerate bareng Gemini. Code dikerjakan vertikal: rlnmy + Hermes + Gemini,
dengan fokus utama di OPTIMASI.

## Keputusan arsitektur (hasil riset rlnmy, 2026-10-24)

- **Runtime: llama.cpp via JNI** (bukan MediaPipe/LiteRT-LM).
  Alasan: (1) LiteRT-LM masih 0.x dan MediaPipe LLM Inference sudah maintenance-only;
  (2) ekosistem GGUF raksasa tanpa license-gate; (3) kontrol optimasi penuh
  (threads, quant, GPU layers, mmap, context) — sesuai fokus "main optimize".
- **Model format: GGUF** (Q4_K_M / Q8_0), diunduh dari Hugging Face langsung di HP.
- **Build 100% offline di VM**: CMake (NDK r23 toolchain) untuk `libllama.so`
  (arm64-v8a only) + pipeline manual aapt2/d8 warisan Auto Translate.
  NOL dependency Gradle/Maven — murni AOSP APIs (Java, SQLiteOpenHelper, HttpURLConnection).
- minSdk 26, targetSdk 34. Package: `com.rezz.rlnova`. Nama aplikasi: **Rlnova**.

## Tier spesifikasi

App mendeteksi saat pertama dibuka: total RAM (ActivityManager),
CPU cores, SoC, dukungan Vulkan, storage bebas → klasifikasi tier:

| Tier | RAM | Contoh model (GGUF) | Ukuran | Context default |
|------|-----|---------------------|--------|-----------------|
| LOW | ≤4 GB | Qwen3-0.6B Q4_K_M, SmolLM2-360M Q8_0, Llama-3.2-1B Q4_K_M | 0.4–1.3 GB | 2048 |
| MID | 6–8 GB | Gemma-2-2B-it Q4_K_M, Llama-3.2-3B Q4_K_M, Qwen2.5-3B Q4_K_M | 1.6–2.5 GB | 4096 |
| HIGH | ≥12 GB | Llama-3.1-8B Q4_K_M, Qwen2.5-7B Q4_K_M, Gemma-3-4B Q4_K_M | 2.6–5 GB | 8192 |

Katalog model = JSON di assets (bisa diupdate tanpa rilis ulang).
Tiap entri: nama, repo HF, file, ukuran, min RAM, quant, chat template, deskripsi ID.

## Fitur

1. **Onboarding + deteksi device** — tampilkan tier HP user + rekomendasi model.
2. **Model Library** — daftar model per tier (badge LOW/MID/HIGH, ukuran, status
   download), tombol download/hapus. Model di atas tier HP = terkunci dengan penjelasan.
3. **Download Manager** — progress bar, pause/resume, verifikasi ukuran file,
   simpan di app-private storage. Foreground service + notifikasi.
4. **Chat** — multi-session, streaming token per token, riwayat tersimpan (SQLite),
   system prompt editable, tombol stop. Chat template otomatis per model
   (ChatML, Llama-3, Gemma, dsb).
5. **Panel Optimasi** (differentiator):
   - Default per tier: threads = cores−2, context adaptif, mmap on.
   - GPU layers (Vulkan): HIGH + deteksi GPU; Mali = default 0
     (pelajaran dari llama_flutter_android: Vulkan Mali tidak stabil).
   - Benchmark: ukur tokens/detik per model di HP user → rekomendasi otomatis
     "model tercepat/terpintar untuk HP-mu".
   - Parameter editable: temperature, top_p, top_k, repeat penalty, max tokens.
6. **Settings** — bahasa Indonesia default, tema gelap, export chat (.txt/.md),
   hapus data.

## Optimasi (fokus utama)

- Prefill/decode benchmark internal → auto-tune threads & GPU layers per device.
- KV-cache & context dihitung dari RAM bebas aktual (jangan sampai LMK kill).
- Model load di background thread (jangan pernah di main thread — anti ANR).
- Unload model otomatis saat RAM kritis (onTrimMemory).

## Lane kerja

- **Design UI: Gemini** (wajib) — via gemini.google.com, akun rezzstck (AI Pro).
  Output: design system + mockup tiap screen, native Android Views (dp).
- **Code: vertikal rlnmy (direktur + review) + Hermes (eksekusi) + Gemini (generate/critique).**
- Review rlnmy per milestone. QA: install di HP rezz (SM-A165F),
  smoke test inference per tier yang didukung HP tersebut.

## Milestone

- M1: scaffolding project + `libllama.so` arm64 ke-build + app kosong kebuka di HP.
- M2: deteksi tier + Model Library + download manager jalan.
- M3: JNI bridge + chat streaming jalan dengan 1 model LOW (validasi di HP rezz).
- M4: panel optimasi + benchmark + auto-tune.
- M5: polish UI sesuai design Gemini + QA penuh + rilis APK via Drive.
