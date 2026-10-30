package com.rezz.rlnova.research;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Konversi jawaban Research jadi ChatML-ish messages yang dipakai bersama
 * ChatTemplates.build saat riset diproses lewat GenerationService.
 *  - History chat LAMA tidak dilibatkan — Research selalu fresh (no context
 *    leak dari chat biasa).
 *  - Follow-up riset TETAP LOCAL: pertanyaan lanjutan dijawab dengan retrieval
 *    ulang dari paket lokal (chunk terpisah per pertanyaan).
 */
public final class ResearchSession {
    // Helper pemanggil: GenerationService dengan mode RESEARCH menyusun prompt
    // dari ResearchPrompt, bukan dari riwayat.
    public static final String MODE_EXPLAIN = "EXPLAIN";
    public static final String MODE_COMPARE = "COMPARE";
    public static final String MODE_SYNTHESIZE = "SYNTHESIZE";

    /** Pilih task otomatis dari pertanyaan (ID/EN) — bisa dioverride dari UI. */
    public static String detectTask(String q) {
        String t = q == null ? "" : q.toLowerCase(java.util.Locale.ROOT);
        String[] cmp = {"bandingkan", "perbedaan", "bedanya", "compare", "difference", "versus", " vs "};
        String[] syn = {"sintesis", "rangkum", "gabungkan", "kesimpulan", "synthes", "summarize", "combine"};
        for (String k : cmp) if (t.contains(k)) return MODE_COMPARE;
        for (String k : syn) if (t.contains(k)) return MODE_SYNTHESIZE;
        return MODE_EXPLAIN;
    }
}
