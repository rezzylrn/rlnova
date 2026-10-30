package com.rezz.rlnova.research;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder prompt riset — ATURAN bukti-terkendali:
 *  - Prompt berisi HANYA instruksi + pertanyaan + chunk hasil retrieval.
 *  - Setiap chunk diberi nomor sitasi [1..N]; ID aslinya dicatat di daftar
 *    `given` (cita IDs yang BENAR-BENAR diberikan ke model).
 *  - Renderer sitasi di UI HANYA boleh memakai ID dari daftar ini; ID lain
 *    (halusinasi model) diabaikan.
 *  - Kalau retrieval tidak menemukan bukti, prompt jadi prompt penolakan:
 *    model diarahkan menjawab "tidak cukup sumber" — TIDAK ADA fallback web.
 */
public final class ResearchPrompt {

    public static final class Evidence {
        public final int marker;              // [1..N] yang diberikan ke model
        public final String chunkId;          // ID stabil (doc-04#c02)
        public final String docTitle;
        public final String text;
        public Evidence(int m, String chunkId, String docTitle, String text) {
            this.marker = m; this.chunkId = chunkId; this.docTitle = docTitle; this.text = text;
        }
    }

    public final String task;         // EXPLAIN | COMPARE | SYNTHESIZE
    public final String question;
    public final List<Evidence> given = new ArrayList<>();
    public final boolean sufficient;

    private ResearchPrompt(String task, String question, List<Retriever.Hit> hits,
                           List<PackageManifest.Doc> docs, int minHits) {
        this.task = task;
        this.question = question;
        List<Retriever.Hit> use = hits;
        if (use.size() > maxEvidence(task)) {
            use = use.subList(0, maxEvidence(task));
        }
        for (Retriever.Hit h : use) {
            given.add(new Evidence(given.size() + 1, h.chunk.chunkId,
                    titleOf(h.chunk.docId, docs), h.chunk.text));
        }
        this.sufficient = given.size() >= minHits;
    }

    /** Bangun prompt dari hasil retrieval top-k. minHits=1 untuk Explain/Synthesize. */
    public static ResearchPrompt build(String task, String question,
                                       List<Retriever.Hit> hits,
                                       List<PackageManifest.Doc> docs) {
        return new ResearchPrompt(task, question, hits, docs, 1);
    }

    /**
     * Mode bukti-minimum: prompt HANYA dibuat bila retrieval menemukan >= minHits
     * chunk. Kalau tidak, caller wajib menampilkan pesan "tidak cukup sumber"
     * dan TIDAK memanggil model sama sekali.
     */
    public static ResearchPrompt buildStrict(String task, String question,
                                             List<Retriever.Hit> hits,
                                             List<PackageManifest.Doc> docs,
                                             int minHits) {
        return new ResearchPrompt(task, question, hits, docs, minHits);
    }

    public static int maxEvidence(String task) {
        return "COMPARE".equals(task) ? 6 : 4;   // jaga konteks di perangkat
    }

    /** ID sitasi valid = chunkId yang benar-benar diberikan ke model. */
    public boolean isValidChunkId(String chunkId) {
        for (Evidence e : given) {
            if (e.chunkId.equals(chunkId)) return true;
        }
        return false;
    }

    public Evidence byMarker(int marker) {
        for (Evidence e : given) {
            if (e.marker == marker) return e;
        }
        return null;
    }

    /** Prompt lengkap siap dikirim ke model (ChatML/Qwen template di luar). */
    public String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("Anda asisten riset offline. Jawab HANYA dari potongan sumber ")
          .append("berikut. Setiap potongan diberi nomor [1], [2], dst. Saat ")
          .append("menyatakan fakta dari potongan, sertakan nomornya seperti [1]. ")
          .append("JANGAN memakai pengetahuan di luar potongan. JANGAN mengarang. ")
          .append("Jika potongan tidak cukup untuk menjawab, atau sumber saling ")
          .append("bertentangan, katakan secara jujur bahwa buktinya tidak cukup ")
          .append("atau bertentangan. Jawab dalam bahasa pertanyaan.\n\n");
        for (Evidence e : given) {
            sb.append("[").append(e.marker).append("] (")
              .append(e.chunkId).append(")\n")
              .append(e.text).append("\n\n");
        }
        return sb.toString();
    }

    public String userPrompt() {
        StringBuilder sb = new StringBuilder();
        switch (task) {
            case "COMPARE":
                sb.append("Bandingkan pandangan/informasi dari sumber di atas ")
                  .append("untuk pertanyaan berikut. Sebutkan persamaan, ")
                  .append("perbedaan, dan sumber mana yang mendukung tiap poin ")
                  .append("(dengan nomor sitasi).\n\nPertanyaan: ");
                break;
            case "SYNTHESIZE":
                sb.append("Gabungkan informasi dari beberapa sumber di atas ")
                  .append("menjadi satu jawaban utuh untuk pertanyaan berikut. ")
                  .append("Sertakan nomor sitasi untuk setiap klaim.\n\nPertanyaan: ");
                break;
            default:
                sb.append("Jelaskan jawaban pertanyaan berikut berdasarkan ")
                  .append("sumber di atas. Sertakan nomor sitasi.\n\nPertanyaan: ");
        }
        sb.append(question);
        return sb.toString();
    }

    private static String titleOf(String docId, List<PackageManifest.Doc> docs) {
        for (PackageManifest.Doc d : docs) {
            if (d.id.equals(docId)) return d.title;
        }
        return docId;
    }
}
