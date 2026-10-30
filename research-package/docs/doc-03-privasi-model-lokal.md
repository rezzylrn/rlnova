# Privasi pada AI Lokal

## Prinsip dasar

Privasi pada aplikasi AI lokal berdiri di atas satu prinsip: teks yang
diketik pengguna tidak boleh meninggalkan perangkat saat diproses. Ini
berbeda dengan layanan AI cloud, di mana setiap pertanyaan dikirim ke
server penyedia, disimpan sesuai kebijakan retensi mereka, dan berpotensi
dipakai untuk pelatihan model berikutnya.

## Airplane mode sebagai ujian

Uji privasi paling jujur untuk aplikasi AI lokal adalah airplane mode.
Buka pengaturan cepat perangkat, aktifkan mode pesawat, lalu gunakan
aplikasi seperti biasa. Jika seluruh fitur tetap berfungsi — bertanya,
mendapat jawaban, membuka sumber — maka aplikasi itu benar-benar tidak
bergantung pada server. Aplikasi yang sebenarnya masih mengirim data akan
gagal atau melambat drastis dalam mode ini.

## Apa yang tetap butuh internet

Aplikasi AI lokal umumnya tetap meminta izin INTERNET untuk satu hal:
mengunduh file model (GGUF) dan aset pendukung saat instalasi atau saat
pengguna memilih model baru di katalog. Unduhan ini terjadi jelas dan
sekali per model — bukan saat pertanyaan diproses. Pemisahan ini penting:
provisioning aset boleh online, penggunaan riset harus offline.

## Data yang tersimpan lokal

Percakapan, riwayat chat, dan index pencarian biasanya disimpan dalam
database SQLite lokal di penyimpanan privat aplikasi. Data ini tidak bisa
dibaca aplikasi lain dan tidak otomatis disinkronkan ke mana pun. Menghapus
data aplikasi menghapus semuanya.

## Batasan privasi yang tetap ada

- Meta aplikasi: sistem operasi tetap tahu aplikasi apa yang dipakai dan
  kapan. Privasi on-device melindungi isi, bukan keberadaan penggunaan.
- Model: model yang diunduh bisa saja dari penerbit pihak ketiga; pilih
  aplikasi yang mencantumkan sumber dan lisensi modelnya.
- Input lain: mikrofon (untuk fitur suara) dan keyboard tetap merupakan
  jalur input perangkat; izinnya terpisah dari izin internet.

## Ringkasan

AI lokal memindahkan tempat komputasi — dari server ke perangkat — dan
karenanya memindahkan kontrol data ke pengguna. Bukan solusi magis, tetapi
untuk aliran pertanyaan-jawaban, ia menghilangkan kategori kebocoran
terbesar: pengiriman teks pengguna ke pihak ketiga.
