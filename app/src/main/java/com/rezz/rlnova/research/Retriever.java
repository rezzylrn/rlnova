package com.rezz.rlnova.research;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Retrieval lokal: BM25 (k1=1.2, b=0.75) over semua chunk paket.
 * Tokenisasi: lowercase, split non-alphanumeric, Buang stopword ID/EN pendek,
 * plus stemming super-ringan (prefix trimming sederhana) — cukup untuk corpus
 * kecil dan DETERMINISTIK (hasil sama di JVM host maupun di device).
 *
 * FTS5 SQLite bawaan Android TIDAK diaktifkan AOSP (terverifikasi: riset
 * 2026-10-31, "no such module: fts5" di Android 14/API 34) — jadi ranking
 * diimplement pure-Java; SQLite tetap dipakai untuk persistensi (cache
 * ter-index per paket, dibangun ulang bila versi paket berubah).
 */
public final class Retriever {

    public static final int TOP_K = 4;
    private static final double K1 = 1.2, B = 0.75;

    public static final class Hit {
        public final Chunker.Chunk chunk;
        public final double score;
        public Hit(Chunker.Chunk c, double s) { chunk = c; score = s; }
    }

    private static final Map<String, Integer> STOPWORDS = new HashMap<>();
    static {
        String[] sw = {
            "yang", "dan", "di", "ke", "dari", "untuk", "pada", "dengan", "ini",
            "itu", "atau", "juga", "akan", "tidak", "dalam", "adalah", "oleh",
            "sebagai", "bisa", "dapat", "para", "satu", "dua", "seperti", "saat",
            "the", "a", "an", "of", "in", "on", "to", "and", "or", "is", "are",
            "for", "with", "that", "this", "it", "as", "by", "from", "at", "be",
            "was", "were", "has", "have", "had", "not", "but", "can", "may",
            // kata tanya / frasa fungsional (bukan konten) — tanpa ini kata
            // tanya generik dapat IDF tinggi di korpus kecil dan bikin
            // out-of-scope query keliru "match"
            "apa", "apakah", "bagaimana", "gimana", "mengapa", "kenapa",
            "jelaskan", "jelasin", "tolong", "cara", "membuat", "buat",
            "what", "how", "why", "who", "does", "did", "explain", "describe",
        };
        for (String s : sw) STOPWORDS.put(s, 1);
    }

    private final List<Chunker.Chunk> corpus;
    private final List<Map<String, Integer>> termFreqs;
    private final double[] docLen;
    private final double avgLen;

    public Retriever(List<Chunker.Chunk> corpus) {
        this.corpus = corpus;
        termFreqs = new ArrayList<>(corpus.size());
        docLen = new double[corpus.size()];
        double total = 0;
        for (int i = 0; i < corpus.size(); i++) {
            Map<String, Integer> tf = tokenize(corpus.get(i).text);
            termFreqs.add(tf);
            double len = 0;
            for (Integer c : tf.values()) len += c;
            docLen[i] = len;
            total += len;
        }
        avgLen = corpus.isEmpty() ? 1.0 : Math.max(1.0, total / corpus.size());
    }

    /** Query -> top-k chunk terurut skor menurun. Skor <= 0 dibuang. */
    public List<Hit> search(String query) {
        Map<String, Integer> q = tokenize(query);
        if (q.isEmpty()) return new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            double s = bm25(q, i);
            if (s > 0) hits.add(new Hit(corpus.get(i), s));
        }
        java.util.Collections.sort(hits, (a, b2) -> Double.compare(b2.score, a.score));
        return hits.size() > TOP_K ? hits.subList(0, TOP_K) : hits;
    }

    /** BM25 hanya terhadap dokumen tertentu (dipakai Compare/2-doc mode). */
    public List<Hit> searchIn(String query, String docA, String docB) {
        Map<String, Integer> q = tokenize(query);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            if (!corpus.get(i).docId.equals(docA) && !corpus.get(i).docId.equals(docB)) continue;
            double s = bm25(q, i);
            if (s > 0) hits.add(new Hit(corpus.get(i), s));
        }
        java.util.Collections.sort(hits, (a, b2) -> Double.compare(b2.score, a.score));
        return hits.size() > TOP_K ? hits.subList(0, TOP_K) : hits;
    }

    private double bm25(Map<String, Integer> q, int docIdx) {
        double score = 0;
        for (Map.Entry<String, Integer> e : q.entrySet()) {
            String term = e.getKey();
            int tf = termFreqs.get(docIdx).getOrDefault(term, 0);
            if (tf == 0) continue;
            int df = 0;
            for (Map<String, Integer> d : termFreqs) {
                if (d.containsKey(term)) df++;
            }
            double idf = Math.log(1 + (corpus.size() - df + 0.5) / (df + 0.5));
            double denom = tf + K1 * (1 - B + B * docLen[docIdx] / avgLen);
            score += idf * (tf * (K1 + 1)) / denom;
        }
        return score;
    }

    /** Tokenisasi deterministik: lowercase, split non-alnum, stopword, stem ringan. */
    public static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> out = new HashMap<>();
        if (text == null) return out;
        String low = text.toLowerCase(Locale.ROOT);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= low.length(); i++) {
            char c = i < low.length() ? low.charAt(i) : ' ';
            boolean alnum = Character.isLetterOrDigit(c);
            if (alnum) {
                cur.append(c);
            } else if (cur.length() > 0) {
                addTok(out, cur.toString());
                cur.setLength(0);
            }
        }
        return out;
    }

    private static void addTok(Map<String, Integer> out, String raw) {
        if (raw.length() < 3) return; // buang token 1-2 char
        if (STOPWORDS.containsKey(raw)) return;
        String t = stem(raw);
        if (t.length() < 3) return;
        out.merge(t, 1, Integer::sum);
    }

    /**
     * Stemming super-ringan buat matching kasar EN/ID: buang suffix umum
     * (plural, -nya, -an, -kan, -peN-, inflection verb EN). TIDAK linguistic —
     * hanya biar "models"/"model", "menjalankan"/"jalankan" ketemu.
     */
    static String stem(String w) {
        if (w.length() > 4) {
            if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
            if (w.endsWith("ses") || w.endsWith("xes")) return w.substring(0, w.length() - 2);
            if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is"))
                return w.substring(0, w.length() - 1);
        }
        if (w.length() > 5) {
            if (w.startsWith("men") && w.length() > 5) return w.substring(3);
            if (w.startsWith("meng") && w.length() > 6) return w.substring(4);
            if (w.startsWith("dipe") && w.length() > 6) return w.substring(4);
            if (w.startsWith("dip") && w.length() > 5) return w.substring(3);
            if (w.startsWith("ke") && w.length() > 5 && !w.startsWith("ker")) return w.substring(2);
            if (w.startsWith("pe") && w.length() > 5 && !w.startsWith("per")) return w.substring(2);
        }
        if (w.length() > 4) {
            if (w.endsWith("nya")) return w.substring(0, w.length() - 3);
            if (w.endsWith("kan")) return w.substring(0, w.length() - 3);
            if (w.endsWith("an") && !w.endsWith("ian")) return w.substring(0, w.length() - 2);
            if (w.endsWith("ing")) return w.substring(0, w.length() - 3);
            if (w.endsWith("ed")) return w.substring(0, w.length() - 2);
        }
        return w;
    }
}
