# POIDH #31 — Claim & Demo Pack (siap pakai rezz)

Disusun 31 Okt 2026 ~08:30 WIB. Repo sudah selesai dari sisi agen; sisa langkah di bawah ini HANYA bisa rezz yang lakukan (device QA, rekam demo, posting, claim). Agen berhenti sebelum posting/claim.

## Status terverifikasi

- **Rilis v1.2.0 (vc24) live di GitHub** — halaman Releases menampilkan "Offline Research vertical slice (POIDH #31)" di posisi teratas (dicek 31 Okt 08:30 WIB): paket pengetahuan lokal (topik AI lokal/privasi/on-device), retrieval BM25 pure-Java, mode Research (Explain/Compare/Synthesize) + sitasi yang bisa dibuka, jawaban terkendali bukti.
- **Repo selesai:** commit d83796f (v1.2.0) + cc58307 (`P2-RESULTS.md` + README) sudah di-push ke master.
- **Deadline: HARI INI, 31 Okt 2026** — halaman bounty tidak mencantumkan jam/zona waktu. Jangan asumsikan sampai tengah malam; kerjakan sepagi mungkin.
- **Bounty:** https://poidh.xyz/mainnet/bounty/31 — pot 1,3 ETH pernah disebut tapi TIDAK terverifikasi di halaman bounty; jangan klaim angka itu.
- **Syarat bukti (dari halaman bounty):** demo publik di X atau Farcaster yang menunjukkan app jalan offline + beberapa contoh query/respons (termasuk yang model 1B bakal gagal) + link repo GitHub + penjelasan singkat pendekatan. Lalu submit screenshot ke poidh beserta link postingan dan repo. Repo harus berisi versi yang disubmit saat claim dibuat.

## Hasil tes P2 — angka jujur (headless, BUKAN HP)

Dari `tests/P2-RESULTS.md` (31 Okt, Qwen3-0.6B, host JVM/x86):

- **6/7 lolos bersih**, retrieval menemukan bukti di semua tes yang relevan.
- **T3 Compare TERPOTONG** di cap 400 token — bagian "Perbedaan" belum sempat muncul. Jangan demo-kan Compare sebagai contoh utama, atau naikkan cap dulu.
- **T5 out-of-scope:** retriever menolak dengan benar, tapi model masih mengarang paragraf hedging. Kalau demo T5, tunjukkan pesan penolakan retriever, jangan andalkan jawaban model.
- **T6 false-premise lolos** (model menolak premis "GGUF = kompresi video") — ini contoh demo yang bagus, "yang model 1B bakal gagal".
- **Perf headless:** prompt eval 57–105 tok/s, generation 5–24 tok/s (x86 VM). **Angka HP belum ada sama sekali** — device QA rezz yang tentukan.
- **Retrieval = BM25 pure-Java, BUKAN SQLite FTS** (FTS5 tidak aktif di AOSP). Jangan tulis FTS di mana pun.
- **Total aset ~447 MB** (per README). Batas bounty: 12 GB RAM, 50 GB total aset — aman.
- **TTS tidak disentuh v1.2.0** — kalau tombol speaker masih force-close (bug v1.1.17 yang menunggu log rezz), jangan pencet speaker saat demo.

## Checklist rezz (berurutan)

1. **Install v1.2.0 di HP** dari GitHub release (file Drive sama seperti biasa). Pasang model + paket pengetahuan dulu, selagi masih online.
2. **Airplane mode ON**, lalu jalankan demo (skrip di bawah). Rekam layar terus-menerus tanpa putus — ini bukti "fully offline".
3. **Posting demo ke X/Farcaster** — hanya dari akun proyek/non-pribadi yang sudah diotorisasi. Jangan pakai akun sosial pribadi rezz.
4. **Claim di poidh:** submit screenshot + link postingan + link repo `https://github.com/rezzylrn/rlnova`.

## Skrip demo (2–3 menit, airplane mode)

1. Tunjukkan airplane mode aktif + nama model (Qwen3-0.6B) di layar.
2. **T1 faktual:** "Apa itu GGUF?" — jawaban + buka 1 sitasi sampai ke sumbernya.
3. **T6 false-premise:** "Kenapa GGUF adalah format kompresi video?" — model harus menolak premisnya.
4. **T2 explain (opsional):** "Jelaskan cara kerja inferensi on-device" — awas, jawaban ini terpotong di tengah kalimat pada tes headless; kalau di HP juga terpotong, potong videonya sebelum bagian itu atau pilih T1+T6 saja.
5. Tutup dengan tampilan paket pengetahuan + ukuran total aset.

## Draf postingan (boleh copas, edit seperlunya)

> Rlnova v1.2.0 — offline AI research on Android, no internet needed once installed.
>
> Local knowledge pack + on-device retrieval (BM25) + Research mode (Explain / Compare / Synthesize) with openable citations. If the pack has no evidence, it says so instead of making things up.
>
> Demo recorded in airplane mode. Repo: https://github.com/rezzylrn/rlnova
> Bounty: https://poidh.xyz/mainnet/bounty/31

## Yang TIDAK boleh diklaim

- **Jangan klaim bar ">50% sebagus internet + frontier model"** — tidak ada evaluasi yang mendukung itu; pemenang ditentukan konfirmasi Vitalik atau pilihan kreator bounty.
- Jangan klaim SQLite FTS, jangan klaim angka tok/s HP, jangan klaim T3 Compare lolos bersih.
- Jangan sebut pot hadiah 1,3 ETH sebagai fakta (belum terverifikasi).

## Kalau tidak sempat hari ini

Bounty berakhir 31 Okt: kalau tidak ada submission yang dikonfirmasi Vitalik, kreator akan wind down (dana kembali) atau memilih pemenang yang dianggap paling memenuhi syarat. Submit hari ini tetap berarti — tapi tanpa demo perangkat nyata, klaimnya lemah; jangan submit klaim kosong.
