# Design System — "Arunika Dark" (by Gemini, 2026-10-24)

Nama final: **Rlnova** (dipilih rezz 2026-10-24; wajib mengandung "rln").
Package: `com.rezz.rlnova`. Mockup Gemini memakai "Arunika" sebagai placeholder —
wordmark diganti Rlnova saat implementasi.

Aturan keras: TANPA AI-slop — tanpa emoji di UI, tanpa gradient, tanpa glow generik.

## Warna (solid fills)
- Primary #2E7CF6 (CTA, active state) / On Primary #FFFFFF
- Primary Container #1D2C4E (chip highlight)
- Secondary #7C8AA5, Background #0C0F14, Surface #131822
- Surface Variant (cards) #1B2230, Outline/Divider #232B3A
- Text Primary #EDF1F7, Secondary #9AA6B8, Disabled #5B6678
- Success #34C77B, Warning #E5A13D, Error #E5484D
- Tier badges (pill chip, ~15% opacity + teks berwarna): LOW #34C77B, MID #2E7CF6, HIGH #A78BFA

## Tipografi
- Inter (res/font, fallback Roboto); angka benchmark/token: Roboto Mono
- Display 24sp/700, Title 20sp/600, Heading 16sp/600, Body 14sp/400, Caption 12sp/400, Mono 12sp

## Shape & elevation
- Radius: button 8dp, card 12dp, text field 12dp, chip pill 16dp, bottom sheet 16dp (top), FAB/avatar circle
- Elevation flat-first: base 0dp; AppBar 0dp + divider 1dp; card 0dp + stroke 1dp #232B3A; BottomNav 8dp; dialog 8dp; FAB 6dp; Snackbar 6dp

## Spacing
- Grid 8dp; margin layar 16dp; padding card 16dp; list rows 56–72dp; ikon 24dp Material Symbols outlined

## Screens (mockup di design/mockups/)
1. 01-onboarding.jpg — OnboardingActivity: logo 64dp + wordmark, headline 24sp/700
   "AI yang berjalan 100% offline di HP Anda", step indicator 1 Deteksi–2 Unduh–3 Chat,
   card spesifikasi (RAM/Chipset/Storage + chip TIER), card rekomendasi model,
   button "Unduh model yang disarankan" 48dp, TextButton "Lihat semua model".
2. 02-model-library.jpg — ModelLibraryFragment: Toolbar "Pustaka Model" + search,
   filter chips (Semua/Ringan/Menengah/Berat), RecyclerView cards (nama 16sp/600,
   tier chip, ukuran; status: Muat+hapus / progress+pause / Unduh), BottomNav.
3. 03-chat.jpg — ChatListFragment (rows 72dp + FAB 56dp) & ChatRoomFragment:
   toolbar + dot hijau "Offline – perangkat ini", bubble user primary kanan,
   kartu AI kiri + streaming cursor, footer mono "128 token · 14,2 tok/dtk" +
   button "Berhenti", composer rounded 12dp + FAB kirim 48dp.
4. 04-downloads.jpg — DownloadsFragment: section "Sedang mengunduh",
   LinearProgressIndicator 4dp + mono "1,2 GB dari 1,9 GB · 62% · 8,4 MB/dtk",
   pause/cancel, section "Selesai", footer card penyimpanan.
5. 05-optimization.jpg — OptimizationFragment: card benchmark ("18,4 token/detik"
   32sp mono + bar chart + "Jalankan benchmark"), sliders (Thread CPU, Lapisan GPU,
   Ukuran konteks), switch (Gunakan GPU, Mode hemat baterai), "Simpan pengaturan".
6. 06-settings.jpg — SettingsFragment: sections UMUM (Bahasa/Indonesia, Tema/Gelap),
   PERCAKAPAN (Ekspor .txt, Hapus semua — merah), DATA (Hapus model, Hapus cache),
   TENTANG (Versi 1.0.0, Kebijakan privasi). Rows 56dp, divider 1dp.

Bahasa UI: Bahasa Indonesia.
