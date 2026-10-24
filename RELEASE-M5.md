# Rlnova v1.0.0 — Rilis Pertama (M5)

Tanggal rilis: 2026-10-24
APK: ~/workspace/offline-ai-app/Rlnova.apk (13.9 MB, arm64-v8a)
Package: com.rezz.rlnova · versionCode 5 · versionName 1.0.0
Signature: apksigner v2 (CN=Android Debug, RSA-2048) — debug keystore
Link download: https://tmpfiles.org/dl/whAqUFXyBExE/rlnova.apk (1 minggu)

## Ringkasan rilis
- Polish M5: onboarding flow fixed (skipped-entry bug), engine RAM leak saat
  switch GPU diperbaiki, aksi settings berlabel jelas ("Hapus"), pesan error
  benchmark diperjelas (arah ke tab Chat), style card heading 15→16sp,
  bar chart label 12sp.
- Fix QA critical/high: jalur RESUME sekarang ensureForeground() (mencegah
  crash ForegroundServiceDidNotStartInTimeException di Android 8+), semua
  tombol Jeda/Lanjut/Batal pakai startForegroundService() (mencegah
  IllegalStateException), CANCEL menghapus .part.
- Fix F6: pesan user dihapus dari DB jika generation gagal (tidak ada bubble
  yatim saat chat dibuka ulang).

## Perubahan file
- app/src/main/AndroidManifest.xml: versionCode 4→5, versionName 0.4.0→1.0.0
- app/src/main/java/com/rezz/rlnova/OnboardingActivity.java — inline goToLibrary()
- app/src/main/java/com/rezz/rlnova/ChatRoomFragment.java — engine free/reload fix + deleteLastUserMessage
- app/src/main/java/com/rezz/rlnova/ChatDatabase.java — deleteMessage() baru
- app/src/main/java/com/rezz/rlnova/DownloadService.java — ensureForeground di RESUME, delete .part saat CANCEL, notifyUiChanged di PAUSE
- app/src/main/java/com/rezz/rlnova/DownloadsFragment.java — startForegroundService untuk aksi download
- app/src/main/java/com/rezz/rlnova/ModelLibraryFragment.java — sama + lock note RAM
- app/src/main/java/com/rezz/rlnova/OptimizationFragment.java — pesan benchmark diperjelas, h1 16sp
- app/src/main/java/com/rezz/rlnova/SettingsFragment.java — label aksi, versi 1.0.0
- app/src/main/java/com/rezz/rlnova/ChatListFragment.java — status bar inset di title
- app/src/main/java/com/rezz/rlnova/DownloadService.java — UA "Rlnova/1.0 (Android)"

## Laporan QA lengkap
Lihat qa/QA-M5.md (audit statis 8 checklist item, severity, file:line).

## Diketahui belum (untuk rilis berikutnya)
- Tab state tidak persist saat rotasi (LibraryActivity.onCreate selalu
  showLibrary) — UX regress, bukan crash.
- Tidak ada pilihan model saat memulai percakapan baru (otomatis pakai model
  pertama yang terunduh).
- Tidak ada cek free-storage sebelum mulai download model besar (5 GB).
- free() engine di executor bisa race dengan nativeGenerate (F8) — perlu
  review ulang threading engine.
- Upload Drive belum dilakukan: gws binary kosong, google_token.json tidak ada.

---

# Rlnova v1.1.0 (2026-10-24)
APK: Rlnova.apk 14.0 MB · versionCode 6 · versionName 1.1.0
Link: https://tmpfiles.org/dl/wLA6UnaWX0if/rlnova.apk (1 minggu)
Tidak diupload Drive (instruksi user).

Perubahan v1.1.0:
- Splash + onboarding + manifest: "Rlnova" branding lengkap (adaptive icon
  mipmap-anydpi-v26, foreground logo di bg #0C0F14, ic_launcher di 5 density).
- Splash subtitle: "Private AI, on this device".
- Palette primary konsisten #5B8DEF (sebelumnya campur #2E7CF6 di 7 lokasi).
- POST_NOTIFICATIONS runtime request sebelum download pertama (API 33+).
- Filter chips dibungkus HorizontalScrollView.
- Ripples di card model & rows, 48dp touch targets semua tombol download.
- P3: fade-through tab switch, stagger enter di model cards.
- P4: AI thinking dots (caret blink) saat model loading / sebelum token pertama.
- P6: ETA text di notifikasi & Downloads row ("~3 min left"), progress rounded.
- ChatRoomFragment: 2 referensi rusak (sisa run sebelumnya) diperbaiki.

## Rlnova v1.1.1 — 2026-10-24 (REVISED brief, 4 P0 bugs)
BUILD FINAL: Rlnova.apk 14.0 MB (14,039,614 B), versionCode 7 / versionName 1.1.1, com.rezz.rlnova, signed debug keystore.
- BUG1 (raw think di chat): ThinkSplit.java baru — parse akumulasi RAW penuh (tag terbelah aman), header "Thinking…" collapsible (default collapsed), isi DB tetap RAW, history ikut parse. ChatRoomFragment dirender ulang via renderStreamingState()/addCardAiParsed().
- BUG2 (turn ke-2 stuck think): bridge.cpp — deteksi EOS saat think belum ditutup → inject literal close-tag ke konteks + lanjut sampling (cap 2x), log stopReason+nGen tiap selesai (tag rlnova-bridge), fallback UI "No answer generated" + Retry (hapus assistant row lama, user bubble tidak diduplikasi).
- BUG3 (Model Library kedip): card di-cache per modelId; poll 500ms hanya update in-place (progress bar/info/shape change satu card); rebuildList hanya saat set model berubah (filter/delete/DONE→IDLE/delete model); animasi enter hanya saat card pertama dibuat. deleteModel() sekarang bumpVersion() (tidak re-create fragment).
- BUG4 (icon bar putih): logo di-crop ke rect navy + flood-fill putih border → navy #0C0F14 full-bleed; semua density (mdpi-xxxhdpi) legacy + adaptive foreground (artwork di 66dp safe zone) di-regenerate. Verifikasi piksel: 0 px non-navy di border semua density.
CATATAN: vision_analyze bermasalah di sesi ini (bug "Invalid port") — verifikasi visual ikon dilakukan via analisis piksel programatik.
