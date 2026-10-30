# Inferensi On-Device

## Definisi

Inferensi on-device adalah proses menjalankan model kecerdasan buatan —
misalnya model bahasa (LLM) — langsung di perangkat pengguna seperti ponsel
atau laptop, tanpa mengirim data ke server. Kebalikannya adalah inferensi
cloud: model berjalan di pusat data, dan perangkat hanya mengirim pertanyaan
lalu menerima jawaban lewat internet.

## Cara kerja

Pada inferensi on-device, file model (misalnya format GGUF) disimpan di
penyimpanan perangkat. Saat pengguna mengajukan pertanyaan, aplikasi:
(1) merangkai prompt dari pertanyaan dan instruksi, (2) menjalankan model
menggunakan CPU atau GPU perangkat, (3) menghasilkan jawaban token demi
token secara lokal. Tidak ada paket data yang keluar dari perangkat selama
proses ini berlangsung.

## Kelebihan

- Privasi: pertanyaan, dokumen, dan jawaban tidak pernah meninggalkan
  perangkat. Aplikasi yang dirancang benar tetap berfungsi dalam airplane
  mode.
- Ketersediaan: tidak bergantung pada sinyal, kecepatan internet, atau
  status server penyedia.
- Biaya: tidak ada biaya per-pertanyaan yang dibayar ke penyedia API cloud.
- Latensi: untuk model kecil, jawaban pertama bisa muncul lebih cepat
  karena tidak ada waktu tempuh jaringan.

## Kekurangan

- Kualitas: model yang muat di ponsel jauh lebih kecil daripada model
  frontier yang berjalan di pusat data, sehingga kemampuannya lebih
  terbatas, terutama untuk penalaran panjang dan pengetahuan yang sangat
  baru.
- Kecepatan: kecepatan generasi dibatasi kemampuan CPU/GPU perangkat;
  ponsel kelas menengah umumnya menghasilkan beberapa sampai puluhan token
  per detik untuk model 1-2 miliar parameter.
- Memori: model membutuhkan RAM. Model 1 miliar parameter yang dikuantisasi
  4-bit butuh sekitar 0,5-1 GB RAM tambahan.
- Pengetahuan statis: model hanya tahu apa yang dipelajarinya saat
  pelatihan. Untuk fakta yang berubah, diperlukan dokumen tambahan
  (misalnya paket pengetahuan lokal) yang disediakan terpisah.

## Kaitan dengan privasi

Karena seluruh komputasi terjadi lokal, inferensi on-device menghilangkan
kebutuhan mengirim teks pengguna ke server. Aplikasi yang benar-benar
on-device biasanya tetap meminta izin internet hanya untuk mengunduh file
model saat instalasi, bukan untuk memproses pertanyaan.
