package com.rezz.rlnova;

/**
 * Think-block parser (BUG1 v1.1.1).
 *
 * Qwen3 (dan model think lain) memancarkan "<think>...</think>" sebelum
 * jawaban. Karena streaming per-piece, tag sering TERPOTONG di batas token
 * ("<thi" + "nk>"). Parser ini bekerja di atas akumulasi RAW penuh — bukan
 * per-piece — sehingga tag yang terbelah tetap terdeteksi.
 *
 * Arah pemakaian (ChatRoomFragment):
 *   - appendToken()          : append piece ke raw accumulator lalu render ThinkSplit.
 *   - finishGeneration()     : render ThinkSplit final.
 *   - loadHistory()          : render ThinkSplit dari konten DB (disimpan RAW).
 */
public final class ThinkSplit {

    /** Hasil split satu pesan. */
    public static final class Result {
        public final String thinking;   // isi di dalam think (boleh kosong)
        public final String answer;     // jawaban setelah think ditutup (boleh kosong)
        public final boolean inThink;   // true = think terbuka & belum ditutup
        public final boolean hasThink;  // true = ada tag think sama sekali

        Result(String thinking, String answer, boolean inThink, boolean hasThink) {
            this.thinking = thinking;
            this.answer = answer;
            this.inThink = inThink;
            this.hasThink = hasThink;
        }
    }

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private ThinkSplit() {}

    /**
     * Split teks mentah (yang mungkin masih berada di dalam think) menjadi
     * (thinking, answer, inThink). Tag terbelah di ekor streaming aman:
     * kalau tidak ada tag buka penuh, seluruh teks dianggap bagian think yang
     * sedang berjalan (inThink) — dan jika teksnya sendiri adalah prefix dari
     * tag buka (mis. "<thi"), kita tahan di buffer think sampai terungkap.
     */
    public static Result parse(String raw) {
        if (raw == null) raw = "";
        boolean hasOpen = raw.contains(OPEN);
        boolean hasClose = raw.contains(CLOSE);

        if (!hasOpen && !hasCloseOpenPrefix(raw)) {
            // Tidak ada indikasi think sama sekali → seluruh teks = jawaban.
            return new Result("", raw, false, false);
        }

        int openIdx = raw.indexOf(OPEN);
        if (openIdx < 0) {
            // Belum ada tag buka penuh, tapi teks adalah prefix dari tag buka
            // (mis. "<thi"). Tampilkan sebagai think yang sedang mengalir.
            return new Result(raw, "", true, true);
        }

        int closeIdx = raw.indexOf(CLOSE, openIdx);
        if (closeIdx < 0) {
            // Think terbuka, belum ditutup.
            String thinking = raw.substring(openIdx + OPEN.length());
            String before = raw.substring(0, openIdx);
            return new Result(thinking, before, true, true);
        }

        // Think tertutup: apa pun sebelum tag buka dibuang (whitespace/prefix),
        // jawaban = setelah tag tutup.
        String thinking = raw.substring(openIdx + OPEN.length(), closeIdx);
        String answer = raw.substring(closeIdx + CLOSE.length());
        return new Result(thinking, answer, false, true);
    }

    /** True kalau raw adalah prefix non-empty dari "<think>" (tag terbelah di ekor). */
    private static boolean hasThinkOpenPrefix(String raw) {
        if (raw.length() == 0 || raw.length() >= OPEN.length()) return false;
        return OPEN.startsWith(raw);
    }

    /**
     * Ada potongan akhir yang berpotensi menjadi awal tag buka? Cek apakah
     * sufiks teks adalah prefix dari "<think>". Dipakai untuk menghindari
     * salah klasifikasi saat token "…<thi" belum selesai.
     */
    private static boolean hasCloseOpenPrefix(String raw) {
        // prefix penuh dari tag terlihat di awal teks (model menaruh think di awal)
        if (hasThinkOpenPrefix(raw)) return true;
        // sufiks teks berpotensi jadi awal tag (mis. "…<thi")
        int max = Math.min(OPEN.length() - 1, raw.length());
        for (int len = max; len >= 1; len--) {
            if (raw.regionMatches(raw.length() - len, OPEN, 0, len)) return true;
        }
        return false;
    }
}
