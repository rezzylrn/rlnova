import com.rezz.rlnova.research.Chunker;
import com.rezz.rlnova.research.PackageManifest;
import com.rezz.rlnova.research.ResearchIndex;
import com.rezz.rlnova.research.ResearchPipeline;
import com.rezz.rlnova.research.ResearchPrompt;
import com.rezz.rlnova.research.Retriever;

import java.nio.file.*;
import java.util.*;

/**
 * P2 headless: bangun index dari research-package, siapkan prompt per
 * pertanyaan uji, TULIS file prompt siap kirim ke llama.cpp (ChatML), dan
 * catat evidence per pertanyaan ke JSON.
 * Jalankan di JVM host — logika retrieval identik dengan on-device.
 */
public class P2Prompts {

    public static void main(String[] args) throws Exception {
        String pkgDir = args[0];       // research-package
        String outDir = args[1];       // output dir prompts

        // --- bangun corpus dari docs (logika = ResearchIndex.load) ---
        List<Chunker.Chunk> corpus = new ArrayList<>();
        java.io.File docs = new java.io.File(pkgDir, "docs");
        java.io.File[] md = docs.listFiles((d, n) -> n.endsWith(".md"));
        Arrays.sort(md);
        for (java.io.File f : md) {
            String txt = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            String docId = f.getName().substring(0, f.getName().length() - 3);
            corpus.addAll(Chunker.chunkDocument(docId, txt));
        }
        Retriever r = new Retriever(corpus);

        // docs metadata (untuk ResearchPrompt)
        List<PackageManifest.Doc> docMeta = new ArrayList<>();
        for (java.io.File f : md) {
            PackageManifest.Doc d = new PackageManifest.Doc();
            d.id = f.getName().substring(0, f.getName().length() - 3);
            d.title = d.id;
            docMeta.add(d);
        }

        // --- 7 pertanyaan uji (P2 brief) ---
        String[][] tests = {
            // {id, task, question}
            {"T1-factual",      "EXPLAIN",    "Apa itu format GGUF?"},
            {"T2-explain",      "EXPLAIN",    "Jelaskan cara kerja inferensi on-device."},
            {"T3-compare",      "COMPARE",    "Bandingkan inferensi on-device dengan inferensi cloud."},
            {"T4-synthesize",   "SYNTHESIZE", "Bagaimana hubungan privasi, inferensi on-device, dan model bahasa lokal?"},
            {"T5-outofscope",   "EXPLAIN",    "Bagaimana cara membuat roti sourdough?"},
            {"T6-falsepremise", "EXPLAIN",    "Mengapa GGUF adalah format kompresi video?"},
            {"T7-en-factual",   "EXPLAIN",    "What is RLHF and how does it relate to large language models?"},
        };

        Files.createDirectories(Paths.get(outDir));
        StringBuilder report = new StringBuilder();
        org.json.JSONObject reportJson = new org.json.JSONObject();

        for (String[] t : tests) {
            String id = t[0], task = t[1], q = t[2];
            ResearchPipeline.Result res = ResearchPipeline.prepare(
                    testIndex(corpus, r), task, q);

            org.json.JSONObject j = new org.json.JSONObject();
            j.put("id", id);
            j.put("task", task);
            j.put("question", q);
            j.put("has_evidence", res.hasEvidence);

            if (!res.hasEvidence) {
                report.append("== ").append(id).append(" (").append(task)
                      .append(") ==\n").append("DECLINED: ").append(res.declineReason)
                      .append("\n\n");
                j.put("decline", res.declineReason);
                reportJson.put(id, j);
                continue;
            }

            // prompt ChatML lengkap (Qwen3 template)
            String chatml = "<|im_start|>system\n" + res.prompt.systemPrompt()
                    + "<|im_end|>\n<|im_start|>user\n" + res.prompt.userPrompt()
                    + "<|im_end|>\n<|im_start|>assistant\n";
            Files.write(Paths.get(outDir, id + ".txt"), chatml.getBytes("UTF-8"));

            org.json.JSONArray ev = new org.json.JSONArray();
            StringBuilder evStr = new StringBuilder();
            for (ResearchPrompt.Evidence e : res.given) {
                ev.put(e.chunkId);
                evStr.append("[").append(e.marker).append("] ").append(e.chunkId).append("\n");
            }
            j.put("evidence", ev);
            reportJson.put(id, j);

            report.append("== ").append(id).append(" (").append(task).append(") ==\n")
                  .append("Q: ").append(q).append("\n")
                  .append("evidence: ").append(evStr)
                  .append("prompt file: ").append(id).append(".txt (")
                  .append(chatml.length()).append(" chars)\n\n");
        }
        Files.write(Paths.get(outDir, "report.json"), reportJson.toString(2).getBytes("UTF-8"));
        System.out.println(report);
        System.out.println("prompts written to " + outDir);
    }

    /** Adapter test: instantiate ResearchIndex langsung dari corpus. */
    static ResearchIndex testIndex(List<Chunker.Chunk> corpus, Retriever r)
            throws Exception {
        PackageManifest mf = PackageManifest.fromJson(
                "{\"id\":\"test\",\"name\":\"AI Lokal, Privasi, dan Inferensi On-Device (test)\",\"version\":\"0\",\"docs\":[]}");
        return new ResearchIndex(mf, corpus, r);
    }
}
