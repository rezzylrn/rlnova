package com.rezz.rlnova.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline riset end-to-end (TANPA Android, TANPA jaringan — murni logika):
 *   1. Retrieval top-k (BM25 pure-Java, deterministik).
 *   2. Compare: pilih 2 dokumen teratas, retrieval dibatasi ke dua doc itu.
 *   3. Kalau tidak ada hit: Result.hasEvidence=false + alasan jujur —
 *      TIDAK ADA fallback web / pengetahuan luar.
 *   4. Prompt dibangun HANYA dari pertanyaan + chunk bukti (ResearchPrompt).
 *
 * Caller (GenerationService mode riset / host test) menjalankan generation
 * dengan Result.systemPrompt + Result.userPrompt, lalu me-render sitasi via
 * CitationRenderer dengan Result.given (ID yang benar-benar diberikan).
 */
public final class ResearchPipeline {

    public static final class Result {
        public final String task;             // EXPLAIN/COMPARE/SYNTHESIZE
        public final String question;
        public final boolean hasEvidence;
        public final String declineReason;    // non-null bila !hasEvidence
        public final ResearchPrompt prompt;   // non-null bila hasEvidence
        public final List<ResearchPrompt.Evidence> given = new ArrayList<>();

        Result(String task, String question, ResearchPrompt prompt) {
            this(task, question, prompt, null);
        }

        Result(String task, String question, ResearchPrompt prompt, String decline) {
            this.task = task; this.question = question;
            this.prompt = prompt;
            this.hasEvidence = prompt != null;
            this.declineReason = decline;
        }

        static Result declined(String task, String question, String reason) {
            return new Result(task, question, null, reason);
        }
    }

    private ResearchPipeline() { }

    /** Siapkan prompt riset dari index + pertanyaan. Tidak memanggil model. */
    public static Result prepare(ResearchIndex index, String task, String question) {
        Retriever r = index.retriever();
        List<Retriever.Hit> hits;
        if (ResearchSession.MODE_COMPARE.equals(task)) {
            hits = topTwoDocs(r.search(question), r, question);
        } else {
            hits = r.search(question);
        }
        // Threshold relevansi: skor hit teratas harus signifikan terhadap
        // query (>= 2.0 pada skala BM25 korpus kecil = setidaknya 2 term kuat
        // yang benar-benar match). Di bawah itu dianggap tidak relevan.
        if (hits.isEmpty() || hits.get(0).score < MIN_SCORE) {
            return Result.declined(task, question,
                    "Tidak ada sumber dalam paket \"" + index.manifest.name
                    + "\" yang cukup relevan dengan pertanyaan ini. Paket hanya "
                    + "mencakup topik terbatas; pertanyaan tidak bisa dijawab "
                    + "dari paket.");
        }
        // buang hit yang skornya jauh di bawah hit teratas (noise)
        List<Retriever.Hit> strong = new ArrayList<>();
        for (Retriever.Hit h : hits) {
            if (h.score >= hits.get(0).score * 0.3) strong.add(h);
        }
        ResearchPrompt p = ResearchPrompt.build(task, question, strong, index.docs());
        Result res = new Result(task, question, p);
        res.given.addAll(p.given);
        return res;
    }

    /** Skor BM25 minimum untuk menganggap bukti relevan (korpus kecil). */
    public static final double MIN_SCORE = 2.0;

    /** Ambil top hits dari 2 dokumen terkuat (untuk mode COMPARE). */
    private static List<Retriever.Hit> topTwoDocs(List<Retriever.Hit> all,
                                                  Retriever r, String question) {
        Map<String, Double> best = new LinkedHashMap<>();
        for (Retriever.Hit h : all) {
            Double cur = best.get(h.chunk.docId);
            if (cur == null || h.score > cur) best.put(h.chunk.docId, h.score);
        }
        String a = null, b = null;
        double sa = -1, sb = -1;
        for (Map.Entry<String, Double> e : best.entrySet()) {
            if (e.getValue() > sa) { b = a; sb = sa; a = e.getKey(); sa = e.getValue(); }
            else if (e.getValue() > sb) { b = e.getKey(); sb = e.getValue(); }
        }
        if (a == null) return all;
        if (b == null) return r.searchIn(question, a, a);
        return r.searchIn(question, a, b);
    }
}
