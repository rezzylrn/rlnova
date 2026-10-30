# Rlnova Knowledge Package — "AI Lokal, Privasi, dan Inferensi On-Device" v1.0.0

## 1. Identitas paket

- `package_id`: `local-ai-privacy-v1`
- `id`: `local-ai-privacy-v1`
- `name`: AI Lokal, Privasi, dan Inferensi On-Device
- `version`: `1.0.0`
- `version_date`: `2026-10-31`
- `language`: id (utama), en
- `topic`: AI lokal, privasi data, dan inferensi on-device
- `license`: KOMBINASI — lihat per dokumen di `docs/`. Dokumen original: CC0 1.0. Dokumen excerpt: CC BY-SA 4.0 / CC BY-SA 3.0 sesuai asal.
- `content_hash_mode`: sha256

## 2. Dokumen (6)

### doc-01 — inferensi-on-device (original, CC0)
- Judul: Inferensi On-Device
- URL/sumber: dokumen original untuk paket Rlnova, tanpa URL eksternal
- Attribution: Dokumen original paket Rlnova local-ai-privacy-v1
- Lisensi: CC0 1.0 (public domain dedication)
- Bahasa: Indonesia
- path: `docs/doc-01-inferensi-on-device.md`
- Deskripsi: Definisi, cara kerja, kelebihan/kekurangan inferensi on-device; kontras dengan inferensi cloud.

### doc-02 — llama-cpp-dan-format-gguf (original, CC0)
- Judul: llama.cpp dan Format GGUF
- URL/sumber: dokumen original untuk paket Rlnova, tanpa URL eksternal
- Attribution: Dokumen original paket Rlnova local-ai-privacy-v1
- Lisensi: GGUF format spec oleh Georgi Gerganov dan kontributor llama.cpp (lisensi tersendiri); teks dokumen ini CC0 1.0
- Bahasa: Indonesia
- path: `docs/doc-02-llama-cpp-dan-format-gguf.md`
- Deskripsi: Mesin inferensi llama.cpp, format kuantisasi GGUF, lapisan kuantisasi Q4/Q8.

### doc-03 — privasi-model-lokal (original, CC0)
- Judul: Privasi pada AI Lokal
- prompt/deskripsi: Privasi, aliran data, airplane mode, model bahasa lokal vs cloud
- URL/sumber: dokumen original untuk paket Rlnova, tanpa URL eksternal
- Attribution: Dokumen original paket Rlnova local-ai-privacy-v1
- Lisensi: CC0 1.0
- Bahasa: Indonesia
- path: `docs/doc-03-privasi-model-lokal.md`
- Deskripsi: Privasi pada aplikasi AI lokal: airplane mode, aliran data, batasan privasi model lokal.

### doc-04 — wikipedia-large-language-model (excerpt, CC BY-SA 4.0)
- Judul: Model Bahasa Besar (excerpt Wikipedia EN)
- URL/sumber: https://en.wikipedia.org/wiki/Large_language_model
- Attribution: Wikipedia contributors, "Large language model", Wikipedia, The Free Encyclopedia. Excerpt diambil 2026-10-31.
- Lisensi: CC BY-SA 4.0
- Bahasa: Inggris
- path: `docs/doc-04-wikipedia-llm.md`
- Deskripsi: Excerpt bagian 2020s era; definisi LLM modern, skala parameter, RLHF.

### doc-05 — wikipedia-id-kecerdasan-buatan (excerpt, CC BY-SA 3.0)
- Judul: Kecerdasan Buatan (excerpt Wikipedia ID)
- URL/sumber: https://id.wikipedia.org/wiki/Kecerdasan_buatan
- Attribution: Para penyumbang Wikipedia, "Kecerdasan buatan", Wikipedia bahasa Indonesia. Excerpt diambil 2026-10-31.
- Lisensi: CC BY-SA 3.0
- Bahasa: Indonesia
- path: `docs/doc-05-wikipedia-kecerdasan-buatan.md`
- Deskripsi: Excerpt definisi AI, sejarah ringkas, penerapan modern, perbedaan AI kuat/lemah.

### doc-06 — wikipedia-id-pemrosesan-bahasa-alami (excerpt, CC BY-SA 3.0)
- Judul: Pemrosesan Bahasa Alami (excerpt Wikipedia ID)
- URL/sumber: https://id.wikipedia.org/wiki/Pemrosesan_bahasa_alami
- Attribution: Para penyumbang Wikipedia, "Pemrosesan bahasa alami", Wikipedia bahasa Indonesia. Excerpt diambil 2026-10-31.
- Lisensi: CC BY-SA 3.0
- Bahasa: Indonesia
- path: `docs/doc-06-wikipedia-pba.md`
- Deskripsi: Excerpt definisi PBA, tugas klasik, era jaringan saraf.

## 3. Kebijakan konten

- Dokumen original (doc-01..doc-03): ditulis khusus untuk paket ini, CC0 1.0, tidak ada teks pihak ketiga.
- Dokumen excerpt (doc-04..doc-06): kutipan pendek beratribusi penuh dari Wikipedia CC BY-SA. Derivatif paket ini WAJIB melanjutkan atribusi dan lisensi yang sama (share-alike).
- Tidak ada teks berhak cipta lain tanpa lisensi.
- Setiap chunk punya ID stabil: `<docId>#c<NN>` (mis. `doc-04#c02`), tidak berubah selama dokumen tidak direvisi; revisi naikkan `version` paket.

## 4. Manifest teknis

- `chunk_size_tokens`: ~90 (dipotong per paragraf, max ~600 karakter)
- `index`: SQLite FTS5, dibangun on-device saat paket pertama dipakai (reproducible dari file `docs/` + manifest ini)
- `retrieval`: top-k=4, FTS5 MATCH BM25 ranking
