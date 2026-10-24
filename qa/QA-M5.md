# QA Statik Rlnova v1.0.0 — Milestone 5

Tanggal: 2026-10-24 · Metode: review kode statis `app/src/main/` + verifikasi artefak `Rlnova.apk` (aapt2 badging, apksigner, unzip). Tidak ada pengujian runtime.

## Ringkasan temuan

| # | Severity | Lokasi | Temuan |
|---|----------|--------|--------|
| F1 | CRITICAL | DownloadsFragment.java:240 vs DownloadService.java:63 | Resume via `startForegroundService()` tanpa `ensureForeground()` di jalur ACTION_RESUME/ACTION_PAUSE → crash `ForegroundServiceDidNotStartInTimeException` (~10 dtk) |
| F2 | HIGH | DownloadsFragment.java:253, ModelLibraryFragment.java:340 | Tombol Batal pakai `startService()` biasa; jika proses service mati (app di-swipe), batal sebelum restart memicu `IllegalStateException` di Android 8+ |
| F3 | MEDIUM | DownloadService.java:23–24, 83, 104 | Resume/pause rusak jika proses service dibunuh OS: `lastUrlMap` + state in-memory hilang, file `.part` yatim, status kembali IDLE |
| F4 | MEDIUM | LibraryActivity.java:36–64 | Rotasi menghancurkan fragmen tanpa `savedInstanceState`; state tab user direset ke Pustaka setiap rotasi (bukan crash, tapi regress UX) |
| F5 | MEDIUM | OnboardingActivity.java:169, 171 | `onboarded=true` disimpan SEBELUM unduhan sukses; user batal unduh → aplikasi dianggap onboarded, tidak pernah lihat onboarding lagi |
| F6 | LOW | ChatRoomFragment.java:268–274 | Pesan user tersimpan ke DB sebelum yakin engine siap; jika model gagal init, bubble user tetap tersimpan (tanpa balasan AI) |
| F7 | LOW | LibraryActivity.java:201 | `startNewChat()` selalu memilih model pertama yang terunduh — user tidak bisa memilih model untuk percakapan baru |
| F8 | LOW | LlamaEngine.java:107–125 + ChatRoomFragment.java:308–372 | `free()` engine bisa berjalan di thread engine sementara `nativeGenerate()` sedang aktif di thread yang sama (satu executor) → teardown/rebuild model rentan race |
| F9 | INFO | LlamaEngine.java:31–38 | `.so` di-load manual berurutan — praktik benar untuk mencegah UnsatisfiedLinkError antar-dependency |
| F10 | LOW | DeviceInfo.java:41 | `availableProcessors()` bisa > core fisik (big.LITTLE) — default thread tidak optimal; sudah di-clamp, bukan bug |

## Jawaban per item checklist

### 1. Onboarding flow — PASS
- `MainActivity.java:53-59` gate `onboarded` (SharedPreferences `rlnova`) → OnboardingActivity atau LibraryActivity. Benar.
- `OnboardingActivity.java` : logo "Rlnova", headline, card spesifikasi (RAM/chipset/penyimpanan/CPU), chip tier berwarna, step indicator "1 Deteksi - 2 Unduh - 3 Chat" (line 200-230), card rekomendasi model, tombol unduh, tombol "Lihat semua model".
- Tombol unduh (`line 152-175`) → startForegroundService ACTION_START dengan ID/URL/name/size model rekomendasi.
- Catatan (F5): flag onboarded di-set terlalu dini (line 171), tidak menunggu unduhan selesai.
- Bahasa Indonesia konsisten, tanpa emoji, palet warna Arunika Dark (#0C0F14, #2E7CF6) sesuai DESIGN.md.

### 2. Model terkunci — PASS
- ModelLibraryFragment.java:261-265: cek `fits` = tier perangkat ≥ tier model DAN RAM `totalRamBytes/1GiB >= m.minRamGb`.
- line 347-360: jika tidak fit → tampilkan "Terkunci" + alasan "Butuh RAM X GB (perangkat ini Y)" — TANPA tombol aksi. Benar, tidak ada bypass.
- Katalog models.json: 9 model (LOW minRam 3-4, MID 6, HIGH 8-12) — logika kunci mencakup semua model HIGH/MID di HP low-end.

### 3. Download pause/resume/cancel — PASS dengan catatan
- DownloadService.java: foreground service (line 239-246 `ensureForeground`), notifikasi progress (line 248-261), channel dibuat di `onCreate`.
- PAUSE: interrupt worker → HTTP connection ditutup → file `.part` dipertahankan (line 157-165).
- RESUME: worker baru, header `Range: bytes=<len>-` (line 133, 222), fallback restart dari 0 kalau server tidak dukung 206 (line 136-143).
- CANCEL: interrupt worker + hapus entry DownloadState + `lastUrlMap.remove` (line 89-97).
- Verifikasi akhir: `out.length() != st.total` → gagal (line 188-191), lalu rename `.part` → `.gguf`.
- UI (DownloadsFragment.java:229-255, ModelLibraryFragment.java:315-343): tombol Jeda/Lanjut/Batal/Hapus terhubung dengan action yang benar.
- Catatan (F1/F2): jalur RESUME dan CANCEL tidak memanggil `ensureForeground()` ulang; jika service dipanggil via `startForegroundService` tetapi tidak masuk jalur `ACTION_START` yang memanggil `ensureForeground` → ANR/crash di Android 8+ (dokumentasi: startForegroundService WAJIB panggil startForeground dalam 5-10 dtk). Perlu konfirmasi runtime.

### 4. Chat tanpa model — PASS
- LibraryActivity.java:198-207 `startNewChat()`: cek `isModelDownloaded()`; jika tidak ada → Toast "Unduh model dulu di tab Pustaka" + redirect ke Pustaka. Tidak bisa membuat chat tanpa model.
- ChatRoomFragment.java:295-297: guard `if (m == null) toast("Model tidak ditemukan")` di awal `startGeneration`.
- ChatRoomFragment.java:331-339: guard file `.gguf` ada → gagal dengan pesan "Model gagal dimuat (RAM kurang atau file korup)".
- OptimizationFragment: benchmark butuh engine termuat (`sharedEngine()` != null), guard di line 218-222.

### 5. Benchmark tanpa model — PASS
- OptimizationFragment.java:211-228: 3 guard berurutan:
  1. `LlamaEngine.libraryLoaded()` == false → Toast "Buka chat sekali dulu untuk memuat library model"
  2. `sharedEngine == null || !isReady()` → Toast "Model belum dimuat — mulai satu percakapan dulu"
  3. `isGenerating()` → Toast "Tunggu generasi selesai dulu"
- LlamaEngine.java:86-89: guard `handle == 0` → return "ERR=engine belum siap". Benar.
- Hasil benchmark di-parse regex `p=<tps> d=<tps>` (OptimizationFragment.java:246-254), disimpan ke CSV `perf_history.csv` via PerfPrefs.

### 6. Settings aksi — PASS
- SettingsFragment.java: aksi per baris diverifikasi:
  - Ekspor percakapan (line 165-203): tulis ke `getExternalFilesDir(DIRECTORY_DOWNLOADS)`, filename `rlnova-chat-<timestamp>.txt`, per percakapan. Fallback ke internal dir kalau eksternal null.
  - Hapus semua percakapan (line 205-216): AlertDialog konfirmasi → `ChatDatabase.deleteAllConversations()` (hapus messages + conversations, transaksional).
  - Hapus model terunduh (line 218-254): list model `.gguf` + total ukuran → konfirmasi → hapus file + `DownloadState.clearAll()`.
  - Hapus cache benchmark (line 256-261): `PerfPrefs.clearHistory` → hapus `perf_history.csv`.
  - Kebijakan privasi (line 263-271): AlertDialog teks offline.
- Catatan: `exportAllChats` tidak menutup `PrintWriter` di blok `finally` (line 176, 194) — jika exception di tengah, file bisa tertulis setengah. Minor.

### 7. Rotasi layar — PASS dengan catatan
- Semua fragmen dibangun programatik (tanpa XML layout) → tidak ada fragmen yang dibuat ulang otomatis dari `savedInstanceState`; activity di-recreate dan state UI direset.
- LibraryActivity.java:36-64: `onCreate` selalu mulai dari `showLibrary()`, tidak cek `savedInstanceState`/`FragmentManager.findFragmentById`. Bottom nav tetap berfungsi, hanya state tab hilang.
- ChatRoomFragment.java:96-103: `onCreateView` re-init dari `getConversation(convId)` — riwayat pesan di-load ulang dari DB (`loadHistory()` line 249). Teks yang sedang di-stream akan hilang; `generating` flag direset → jika user keluar chat saat generation berjalan lalu masuk lagi, streaming tidak lanjut di UI (native engine tetap jalan di background, jadi tidak crash, tapi UX regress).
- DownloadService: aman terhadap rotasi (foreground service, bukan lifecycle activity).
- Catatan (F4): ini issue minor, tidak menyebabkan crash.

### 8. javac + badging + sign — PASS
- javac: build.sh line 38, `javac -source 8 -target 8 -encoding UTF-8`, JDK 17, 17 file .java di `app/src/main/java/com/rezz/rlnova/`. Build selesai (jar ada di `Rlnova.apk`).
- badging (aapt2 dump badging):
  ```
  package: name='com.rezz.rlnova' versionCode='5' versionName='1.0.0'
  sdkVersion:'26' targetSdkVersion:'34'
  application-label:'Rlnova'
  launchable-activity: name='com.rezz.rlnova.MainActivity'
  native-code: 'arm64-v8a'
  uses-permission: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS
  ```
- manifest XMLtree: `DownloadService` deklarasi `foregroundServiceType=0x1 (dataSync)` — benar, cocok dengan permission FOREGROUND_SERVICE_DATA_SYNC.
- sign (apksigner verify):
  - v2 scheme: TRUE ✓ (wajib untuk targetSdk 30+)
  - v1 (JAR): false (normal untuk minSdk 26, tidak wajib)
  - Signer: CN=Android Debug, RSA 2048 — debug keystore, bukan release. Untuk rilis publik perlu keystore release yang proper.
- APK size: 13.936.191 byte (~13,9 MB) sesuai target.
- Native libs: 5 `.so` arm64-v8a (ggml-base, ggml-cpu, ggml, llama, rlnova-bridge), total ~13,9MB.

## Hal yang HANYA bisa dites di HP asli

1. **Model load nyata + RAM native**: `nativeInit` → alokasi mmap GGUF; kegagalan OOM saat load model 4-8GB hanya terdeteksi di HP RAM terbatas.
2. **Kecepatan token/dtk (tok/s) per perangkat**: benchmark `nativeBench` sangat bergantung CPU big.LITTLE + thermal throttling HP nyata; angka tidak bisa disimulasikan.
3. **Mode hemat baterai + thermal**: apakah threads/2 benar-benar mengurangi drain & panas dalam pemakaian chat panjang.
4. **GPU Vulkan (llama.cpp Vulkan backend)**: hanya bisa dites kalau .so dengan backend Vulkan dikompilasi dan ada di HP dengan driver Vulkan stabil (bukan Mali). Perlu cek apakah backend Vulkan sudah di-embed atau CPU-only.
5. **Download foreground service di background**: notifikasi muncul, service tidak dibunuh system saat app di background / HP dikunci. Perlu uji Doze/App Standby.
6. **ACTION_PAUSE/RESUME saat proses service mati**: reproduksi F1/F2 (crash `ForegroundServiceDidNotStartInTimeException` / `IllegalStateException`) hanya bisa diverifikasi dengan menjalankan service di HP, membunuh proses, lalu mencoba resume.
7. **Server HF mendukung HTTP Range resume**: tes nyata — mulai download, jeda, lanjut → response 206 Partial Content, tidak restart dari 0. Perlu koneksi internet + server HF yang live.
8. **Keyboard focus + IME**: compose chat, soft keyboard muncul/menghilang, window resize vs pan (adjustResize vs adjustPan di theme).
9. **Rotasi landscape**: verifikasi visual (bottom nav, bubble chat, scroll position) — kode tidak menyimpan state fragment di Bundle, jadi perlu dilihat langsung apakah UX-nya cukup baik.
10. **Notifikasi download progress di system tray**: persentase update tiap ~500ms, imporansi channel LOW, tampilan di lockscreen.
11. **Ukuran APK ter-install vs file model**: model .gguf di filesDir, perlu cek bahwa HP dengan storage rendah tidak crash saat storage penuh (saat ini tidak ada pengecekan free storage sebelum mulai download).
12. **Chat template nyata per model**: verifikasi manual bahwa Qwen/Llama/Gemma menghasilkan keluaran koheren (bukan prompt-template yang salah yang menyebabkan model output sampah). Hanya bisa diuji dengan model GGUF nyata di HP.
13. **Aplikasi di-kill saat download berjalan**: apakah worker thread dan file .part survive proses restart (START_NOT_STICKY berarti service tidak restart otomatis — perlu uji apakah UI menangani status tanpa worker).
14. **Memory pressure saat engine free/reload**: perpindahan model (epoch berubah) di HP RAM sempit — kala `sharedEngine.free()` belum selesai saat init baru dimulai.

## Files reviewed
- `app/src/main/java/com/rezz/rlnova/` — 18 file Java (semua dibaca penuh)
- `app/src/main/AndroidManifest.xml`
- `app/src/main/assets/models.json` (9 model)
- `app/src/main/res/values/{colors,styles,ids}.xml`, `build.sh`
- `Rlnova.apk` — aapt2 badging, xmltree manifest, apksigner verify

## Rekomendasi prioritas perbaikan sebelum rilis
1. F1 (CRITICAL): pastikan jalur RESUME di `DownloadService.onStartCommand` selalu memanggil `ensureForeground(...)` di semua cabang yang bisa di-start via `startForegroundService`.
2. F2 (HIGH): gunakan `ContextCompat.startForegroundService` atau `startForegroundService()` di semua tombol aksi download (Batal, Jeda, Lanjut), atau tangani di sisi worker.
3. F5 (LOW-MED): pindahkan `putBoolean("onboarded", true)` ke callback sukses unduh, bukan saat klik.
