# Riset Design Rlnova — Referensi Aplikasi Open Source

Tanggal: 2026-10-24. Tujuan: menaikkan kualitas UI/UX Rlnova (AI chat offline,
dark theme, native Android Views + Java, TANPA library eksternal) dengan
meniru pola yang terbukti bagus di aplikasi open source. Semua URL GitHub di
dokumen ini sudah diverifikasi ada (via GitHub API).

> Catatan: `tachiyomiorg/tachiyomi` sudah tidak ada (404) — tidak dipakai.
> `ChatterUI` dan `maid` itu TypeScript (bukan native Views) — tidak dipakai
> sebagai referensi native.

## 0. Token motion — fondasi untuk semua animasi

Tetapkan SATU set token di `res/values/` (atau konstanta Java) dan pakai
konsisten di seluruh app. Jangan campur durasi/easing sembarangan.

**Durasi (Material Design 3):**
- Micro (press, ripple, toggle kecil): 100–150ms
- Small (chip select, FAB morph, expand): 200–250ms
- Medium (pindah tab, bottom sheet, dialog): 300–400ms
- Large (transisi antar Activity penuh): 400–500ms
- Hero (splash/onboarding pertama kali saja): 500–700ms

**Easing (pakai `PathInterpolator`, tersedia sejak API 21):**
- Standar (UI umum): `cubic-bezier(0.2, 0, 0, 1)` → `FastOutSlowInInterpolator`
  bawaan Android sudah cukup dekat.
- Emphasized enter (elemen MASUK layar — onboarding, dialog):
  `new PathInterpolator(0.05f, 0.7f, 0.1f, 1f)`
- Emphasized exit (elemen KELUAR): `new PathInterpolator(0.3f, 0f, 0.8f, 0.15f)`

Aturan: elemen masuk = decelerate/emphasized (mulai cepat, berhenti halus);
elemen keluar = accelerate (mulai halus, pergi cepat). Jangan pernah pakai
`LinearInterpolator` untuk gerakan yang terlihat user — terasa mekanis/murah.
Referensi token resmi: https://github.com/material-components/material-components-android

---

## P0 — Perbaiki kebocoran UI (sebelum animasi apa pun)

**APA:** Dari screenshot QA: layar awal masih bertuliskan "Offline AI",
bukan "Rlnova". Audit SEMUA string yang tampil ke user: `android:label` di
manifest, judul toolbar tiap Activity/Fragment, wordmark di onboarding,
nama di dialog/notifikasi, dan **launcher icon** (adaptive icon:
`mipmap-anydpi-v26`, foreground vector + background #0C0F14).

**KENAPA:** Nama yang tidak konsisten = sinyal "app belum jadi" yang paling
cepat ditangkap user, mengalahkan semua animasi bagus.

**REFERENSI:** https://github.com/beemdevelopment/Aegis — contoh branding
konsisten di launcher icon, judul, dan about screen dengan satu identitas.

---

## P1 — Touch feedback yang proper (quick win, dampak besar)

**APA:**
1. Setiap baris/card yang bisa diklik WAJIB punya ripple:
   `android:foreground="?attr/selectableItemBackground"` (bounded) atau
   `?attr/selectableItemBackgroundBorderless` (untuk icon button).
2. Card model di Pustaka: tambah `StateListAnimator` — saat ditekan, card
   terangkat sedikit (translationZ 0→2dp, 120ms). Bisa murni XML
   (`res/animator/card_lift.xml`).
3. Tombol "Muat"/"Unduh": pastikan ada state disabled yang JELAS
   (alpha 0.4 + teks alasan "Butuh RAM 12 GB") — dari screenshot, state
   "Terkunci" sudah ada, tinggal pastikan kontrasnya cukup di dark theme.
4. Min touch target 48dp untuk semua tombol ikon (pause, hapus, kirim).

**KENAPA:** 90% rasa "premium" vs "kaku" berasal dari 150ms pertama setelah
sentuhan. Tanpa ripple, app terasa mati walaupun logikanya benar.

**REFERENSI:**
- https://github.com/AntennaPod/AntennaPod — ripple + swipe action yang
  konsisten di semua daftar episode.
- https://github.com/TeamNewPipe/NewPipe — touch feedback di daftar video
  yang padat, tetap responsif.

---

## P2 — Splash & onboarding animation (yang berkelas untuk app AI)

**APA — koreografi yang disarankan:**
1. **Splash (total ≤ 1 detik, hanya sekali per cold start):**
   - Logo Rlnova (vector geometris, BUKAN emoji) muncul dengan
     `scale 0.85 → 1.0` + `alpha 0 → 1`, durasi 450ms, emphasized enter.
   - Opsional berkelas: 3 titik/cincin kecil di bawah logo yang "bernapas"
     (alpha 0.3→1.0 bergantian, 600ms loop) sebagai metafora "AI sedang
     memuat" — lalu berhenti saat masuk onboarding.
   - Jangan: progress bar horizontal di splash, teks "Loading...", animasi
     lebih dari 1 detik.
2. **Onboarding (stagger, bukan sekaligus):** elemen masuk berurutan dengan
   jeda 60–80ms: logo → headline → kartu spesifikasi → kartu rekomendasi →
   tombol. Tiap elemen: `translationY 24dp → 0` + fade, 300ms, emphasized
   enter. Implementasi native: `ViewPropertyAnimator` dengan `setStartDelay`,
   atau `android.transition` + `TransitionSet.setOrdering(ORDERING_SEQUENTIAL)`.
3. Chip "TIER: MID" saat muncul: scale 0.9→1.0 + fade 200ms — memberi momen
   "reveal" untuk hasil deteksi device.

**KENAPA:** Stagger membuat layar yang padat (spek + rekomendasi + tombol)
terasa ringan dibaca; otak memproses satu blok dalam satu waktu. Satu
animasi hero di splash memberi identitas "AI yang hidup" tanpa norak.

**REFERENSI:**
- https://github.com/nickbutcher/plaid — kitab suci animasi Android:
  contoh stagger, shared element, dan FAB morphing yang benar.
- https://github.com/google/iosched — onboarding + transisi antar screen
  yang halus dan konsisten (lihat package `ui` / motion-nya).
- SplashScreen API bawaan Android 12+ (tanpa library): pola
  `windowBackground` → animasi icon; kalau mau manual, tiru durasinya
  (±500ms icon, keluar 300ms).

---

## P3 — Transisi antar screen (micro-interactions)

**APA:**
1. **Pindah tab bottom nav** (Pustaka ↔ Unduhan ↔ Chat): JANGAN pakai slide
   kiri-kanan (itu untuk wizard). Pakai **fade-through**: konten lama fade
   out 90ms, konten baru fade + scale 0.98→1.0 selama 210ms.
   Implementasi native: `android.transition.Fade` + `ChangeBounds` dalam
   `TransitionSet`, atau manual dengan dua `ViewPropertyAnimator`.
2. **Tombol "Muat" → ruang chat:** shared element transition — kartu model
   "melebar" menjadi header chat (`ChangeBounds` + `ChangeTransform`,
   350ms emphasized). Ini momen "AI-nya bangun" — paling berkesan.
3. **Download selesai → tombol berubah jadi "Muat":** crossfade 200ms,
   bukan ganti mendadak.
4. **Kembali (back):** reverse dari animasi masuk, durasi sedikit lebih
   cepat (×0.8).

**KENAPA:** Transisi yang konsisten memberi tahu user "di mana dia" dalam
hierarki app tanpa perlu berpikir. Fade-through untuk tab sejajar,
shared-element untuk hubungan sebab-akibat (model → chat).

**REFERENSI:**
- https://github.com/google/iosched — transisi antar destinasi navigasi.
- https://github.com/nickbutcher/plaid — shared element story → detail.

---

## P4 — Empty states & loading states

**APA:**
1. **Empty state** (belum ada percakapan / belum ada model diunduh /
   histori benchmark kosong): JANGAN layar kosong atau teks saja. Pola:
   ilustrasi vector sederhana 96–120dp (garis tipis, 1 warna secondary —
   bisa bikin sendiri, mis. bubble chat kosong / kotak download) +
   headline 16sp/600 + 1 kalimat penjelasan 14sp secondary + SATU tombol
   aksi ("Unduh model pertama", "Mulai percakapan").
2. **Loading daftar:** skeleton rows (bukan spinner full-screen) — 3–4 baris
   abu-abu dengan alpha berdenyut (`ObjectAnimator` alpha 0.35→0.7,
   800ms, repeat reverse). Spinner hanya untuk aksi singkat (<2 detik).
3. **AI "berpikir" (khas app AI):** indikator tiga titik memantul
   (seperti Telegram "typing…") di dalam kartu AI + teks mono kecil
   "memuat model…" / "berpikir…". JANGAN spinner raksasa di tengah chat.
4. **Download model (ratusan MB):** progress determinan + teks mono
   "1,2 GB dari 1,9 GB · 62% · 8,4 MB/dtk · sisa ±3 mnt" (ETA dihitung dari
   kecepatan rata-rata 5 detik terakhir) + tombol pause/resume yang morph
   (ikon berubah dengan crossfade 150ms).

**KENAPA:** Empty/loading state adalah tempat user paling sering menunggu —
di sinilah app dinilai "niat" atau "asal jadi". ETA + pause/resume mengubah
download 2GB dari siksaan jadi terkendali.

**REFERENSI:**
- https://github.com/AntennaPod/AntennaPod — empty states podcast yang
  informatif + CTA jelas.
- https://github.com/TeamNewPipe/NewPipe — UI download dengan progress,
  kecepatan, dan kontrol yang lengkap.
- https://github.com/mlc-ai/mlc-llm (`android/MLCChat`) — aplikasi Android
  native on-device LLM: alur download model → chat, pola yang sama dengan
  Rlnova.

---

## P5 — Visual hierarchy & spacing di daftar & chat

**APA:**
1. **Kartu model:** maksimal 2 gaya teks per kartu (nama 16sp/600 +
   deskripsi 14sp secondary). Info teknis ("2,3 GB · QQ4_K_M · konteks
   8192") pakai mono 12sp secondary — bedakan dari teks bacaan.
   Jarak antar kartu 8dp, padding dalam 16dp, margin layar 16dp (grid 8dp).
2. **Badge tier:** pertahankan pill chip (sudah bagus di screenshot), tapi
   pastikan "Terkunci" tidak sekadar teks abu-abu — tambah ikon gembok
   kecil 16dp agar terbaca sebagai status, bukan deskripsi.
3. **Chat:**
   - Bubble user (kanan, primary) vs kartu AI (kiri, surface) — sudah benar.
     Tambah: **grouping** — pesan berurutan dari pihak yang sama rapat
     (jarak 4dp), ganti pihak jarak 12dp; timestamp kecil 11sp di dalam/
     bawah bubble, bukan baris terpisah.
   - **Day divider** ("Hari ini") saat tanggal berganti.
   - **FAB "scroll ke bawah"** muncul saat user scroll ke atas + ada pesan
     baru (dengan badge jumlah). Ini pola wajib app chat.
   - Lebar bubble maksimal ~75% layar (jangan full-width).
4. **Angka benchmark:** selalu mono + tabular (sudah di DESIGN.md) —
   tambahkan label kecil "prefill" vs "decode" agar angka tok/detik
   bermakna.

**KENAPA:** Hierarchy yang benar = user menemukan info dalam <2 detik tanpa
membaca semuanya. Chat yang tidak di-grouping terlihat seperti log mesin.

**REFERENSI:**
- https://github.com/signalapp/Signal-Android — standar emas UI chat
  (grouping, divider, scroll FAB, swipe).
- https://github.com/Telegram-FOSS-Team/Telegram-FOSS — bubble, ticks,
  typing indicator, gesture chat yang dipoles bertahun-tahun.
- https://github.com/mollyim/mollyim-android — fork Signal, varian chat UI
  modern yang lebih baru.

---

## P6 — Micro-interactions khusus download manager

(Sudah disinggung di P4; ini detail implementasinya.)
1. Progress bar 4dp dengan **rounded caps** + warna primary; track #232B3A.
2. Saat pause: bar membeku + ikon berubah jadi resume (crossfade 150ms),
   teks kecepatan jadi "dijeda".
3. Saat selesai: bar penuh → check hijau scale-in 200ms → tombol "Muat"
   fade-in. Rayakan sedikit — user baru menunggu lama.
4. Footer penyimpanan: thin bar + teks "12,4 GB dari 42 GB tersedia";
   warning kuning jika sisa < 2× ukuran model terbesar di tier.

---

## Anti-pattern — yang HARUS dihindari

1. **Bounce/spring di setiap interaksi.** Overshoot hanya untuk momen
   perayaan (download selesai, pertama kali chat jalan). Kalau semua
   memantul, tidak ada yang istimewa. (Plaid justru menang karena
   restraint.)
2. **Animasi >500ms untuk aksi berulang.** Tab, tombol, chip: maksimal
   250ms. User mengetuk ratusan kali sehari.
3. **Splash >1 detik / splash dengan tombol "Skip".** Kalau butuh tombol
   skip, animasinya kepanjangan.
4. **LayoutTransition di RecyclerView.** Menyebabkan jank; pakai
   `DefaultItemAnimator` (atau custom `ItemAnimator` sederhana) untuk
   insert/remove.
5. **Animasi untuk menutupi loading lambat.** Percepat dulu (mmap, lazy
   init), baru poles. Animasi ≠ optimasi.
6. **Emoji sebagai ikon/indikator.** Sudah dilarang di DESIGN.md —
   berlaku juga untuk empty state & indikator AI. Pakai vector.
7. **Terlalu banyak jenis easing.** Maksimal 3 curve (standar, enter,
   exit) di seluruh app.
8. **Teks animasi yang bergerak saat dibaca** (marquee) — mengganggu,
   terutama di chat streaming.

---

## Daftar repo referensi (semua terverifikasi ada)

| Repo | Bahasa | Dipakai untuk |
|---|---|---|
| https://github.com/nickbutcher/plaid | Kotlin | Animasi & material motion (stagger, shared element, FAB morph) |
| https://github.com/google/iosched | Kotlin | Onboarding, transisi navigasi, dark theme |
| https://github.com/material-components/material-components-android | Java | Token motion resmi (durasi & easing M3) |
| https://github.com/AntennaPod/AntennaPod | Java | Daftar + swipe action, empty states, ripple konsisten |
| https://github.com/TeamNewPipe/NewPipe | Java | Dark theme, bottom sheet, UI download lengkap |
| https://github.com/Docile-Alligator/Infinity-For-Reddit | Java | Dark theme pekat, gesture, daftar padat |
| https://github.com/signalapp/Signal-Android | Kotlin | Standar emas UI chat |
| https://github.com/Telegram-FOSS-Team/Telegram-FOSS | Java | Bubble chat, typing indicator, gesture chat |
| https://github.com/mollyim/mollyim-android | Kotlin | Varian modern UI chat Signal |
| https://github.com/Kunzisoft/KeePassDX | Kotlin | Dark clean, FAB, struktur settings |
| https://github.com/beemdevelopment/Aegis | Java | UI dark sederhana & konsisten, branding |
| https://github.com/tuskyapp/Tusky | Kotlin | Timeline list, perilaku FAB |
| https://github.com/thunderbird/thunderbird-android | Kotlin | Daftar email, unified UX |
| https://github.com/mlc-ai/mlc-llm | Python (+`android/MLCChat` native) | App Android on-device LLM: download model → chat |

## Prioritas eksekusi yang disarankan

1. **P0** — audit & betulkan semua string/icon "Offline AI" → "Rlnova" (1–2 jam)
2. **P1** — ripple + stateListAnimator + touch target 48dp (0.5 hari)
3. **P2** — splash (AVD logo, 450ms) + stagger onboarding (0.5–1 hari)
4. **P4** — empty states + skeleton loading + indikator "berpikir" AI (1 hari)
5. **P3** — fade-through tab + shared element model→chat (1 hari)
6. **P5** — grouping chat + day divider + scroll FAB (1–1.5 hari)
7. **P6** — polish download manager (0.5 hari)

Estimasi total: ±5–6 hari kerja untuk semua, bisa dicicil per milestone.
