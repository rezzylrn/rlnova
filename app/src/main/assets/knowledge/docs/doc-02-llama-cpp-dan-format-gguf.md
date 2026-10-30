# llama.cpp dan Format GGUF

## llama.cpp

llama.cpp adalah mesin inferensi sumber terbuka yang ditulis dalam C/C++
oleh Georgi Gerganov dan kontributor komunitas. Proyek ini dirancang untuk
menjalankan model bahasa secara efisien di perangkat keras biasa — laptop,
desktop, sampai ponsel Android — tanpa memerlukan GPU kelas server.
llama.cpp menjadi dasar banyak aplikasi AI lokal karena portabilitasnya:
satu basis kode yang sama bisa dikompilasi untuk berbagai platform,
termasuk Android melalui Android NDK.

## Format GGUF

GGUF (GPT-Generated Unified Format) adalah format file biner untuk
menyimpan model yang sudah siap inferensi. Satu file GGUF berisi bobot
model, tokenizer, dan metadata (arsitektur, jumlah parameter, template
chat) dalam satu berkas tunggal sehingga mudah didistribusikan dan
dimuat. GGUF menggantikan format lama GGML.

## Kuantisasi

Model neural network secara alami menyimpan bobot dalam bilangan
floating-point 16-bit atau 32-bit. Kuantisasi menurunkan presisi bobot
menjadi bilangan yang lebih pendek — misalnya 8-bit (Q8), 6-bit (Q6), atau
4-bit (Q4) — sehingga ukuran file dan kebutuhan RAM mengecil drastis dengan
penurunan kualitas yang relatif kecil.

Kuantisasi yang umum dijumpai pengguna:
- Q8_0: hampir tanpa penurunan kualitas, ukuran sekitar setengah dari
  presisi penuh.
- Q6_K: kompromi ukuran-mutu yang baik.
- Q4_K_M: paling populer untuk perangkat mobile; ukuran sekitar seperempat
  hingga sepertiga presisi penuh. Penurunan mutu umumnya kecil untuk model
  kecil, tetapi kuantisasi di bawah Q4 (Q3, Q2) bisa merusak kemampuan
  model secara nyata.

## Contoh ukuran

Model 1 miliar parameter: sekitar 2 GB dalam 16-bit, sekitar 0,8 GB dalam
Q4_K_M. Inilah alasan model kuantisasi Q4 menjadi pilihan standar untuk
aplikasi AI offline di ponsel: file yang bisa diunduh sekali, RAM yang
masih menyisakan ruang untuk aplikasi lain.

## Peran di aplikasi mobile

Aplikasi Android yang memakai llama.cpp biasanya mengkompilasi library
native (libllama.so) untuk arsitektur arm64-v8a, lalu menjalankan inferensi
di CPU. Template chat model (ChatML untuk keluarga Qwen, Llama 3, Gemma)
dirangkai di lapisan aplikasi sebelum teks diteruskan ke mesin inferensi.
