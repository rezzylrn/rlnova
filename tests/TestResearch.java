import com.rezz.rlnova.research.Chunker;
import com.rezz.rlnova.research.Retriever;
import com.rezz.rlnova.research.ResearchPrompt;
import com.rezz.rlnova.research.CitationRenderer;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/**
 * Host-side tooling (dijalankan di JVM, bukan device):
 *  1. mode "genchunks": chunk-kan docs dari research-package/ jadi
 *     assets/knowledge/ (manifest.json + docs/*.md disalin, chunks_*.json
 *     digenerate dari Chunker yang SAMA dengan di device — deterministik).
 *  2. mode "test": unit test retrieval + citation + prompt (test retrieval
 *     deterministik, marker hanya dari evidence, marker invalid diabaikan).
 */
public class TestResearch {

    static int pass = 0, fail = 0;
    static void ok(boolean cond, String label) {
        if (cond) { pass++; System.out.println("PASS " + label); }
        else { fail++; System.out.println("FAIL " + label); }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "test";
        if (mode.equals("genchunks")) { genChunks(args[1], args[2]); return; }

        // ============ unit tests (host JVM) ============
        String pkgDir = args.length > 1 ? args[1] : "../research-package";

        // --- Chunker ---
        String doc1 = new String(Files.readAllBytes(
                Paths.get(pkgDir, "docs/doc-01-inferensi-on-device.md")), "UTF-8");
        List<Chunker.Chunk> c1 = Chunker.chunkDocument("doc-01", doc1);
        ok(c1.size() >= 3, "chunker doc-01 menghasilkan >= 3 chunk (dapat " + c1.size() + ")");
        ok(c1.get(0).chunkId.equals("doc-01#c01"), "chunkId pertama = doc-01#c01");
        // determinisme: jalankan dua kali, hasil identik
        List<Chunker.Chunk> c1b = Chunker.chunkDocument("doc-01", doc1);
        boolean same = c1.size() == c1b.size();
        for (int i = 0; same && i < c1.size(); i++) {
            same = c1.get(i).chunkId.equals(c1b.get(i).chunkId)
                    && c1.get(i).text.equals(c1b.get(i).text);
        }
        ok(same, "chunker deterministik (2x jalankan identik)");
        // panjang chunk masuk batas
        boolean lenOk = true;
        for (Chunker.Chunk c : c1) if (c.text.length() > 720) lenOk = false;
        ok(lenOk, "semua chunk doc-01 <= 720 char");

        // --- load semua docs jadi corpus ---
        List<Chunker.Chunk> corpus = new ArrayList<>();
        File docs = new File(pkgDir, "docs");
        File[] md = docs.listFiles((d, n) -> n.endsWith(".md"));
        Arrays.sort(md);
        for (File f : md) {
            String txt = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            String docId = f.getName().substring(0, f.getName().length() - 3);
            corpus.addAll(Chunker.chunkDocument(docId, txt));
        }
        System.out.println("corpus: " + corpus.size() + " chunks dari " + md.length + " docs");
        ok(corpus.size() >= 30, "corpus >= 30 chunks (dapat " + corpus.size() + ")");

        Retriever r = new Retriever(corpus);

        // --- retrieval: pertanyaan ID tentang topik paket ---
        List<Retriever.Hit> h1 = r.search("Apa itu inferensi on-device?");
        ok(!h1.isEmpty(), "retrieval ID 'inferensi on-device' tidak kosong");
        ok(h1.get(0).chunk.docId.startsWith("doc-01"),
                "top hit 'inferensi on-device' = doc-01* (dapat " + h1.get(0).chunk.docId + ")");

        List<Retriever.Hit> h2 = r.search("Apa itu GGUF dan llama.cpp?");
        ok(!h2.isEmpty() && h2.get(0).chunk.docId.startsWith("doc-02"),
                "top hit 'GGUF llama.cpp' = doc-02* (dapat " + (h2.isEmpty() ? "-" : h2.get(0).chunk.docId) + ")");

        List<Retriever.Hit> h3 = r.search("Apakah model lokal aman untuk privasi data?");
        ok(!h3.isEmpty(), "retrieval privasi tidak kosong");

        // retrieval cross-language: pertanyaan EN menemukan doc ID
        List<Retriever.Hit> h4 = r.search("What is artificial intelligence?");
        ok(!h4.isEmpty(), "retrieval EN 'artificial intelligence' tidak kosong");

        // out of scope
        List<Retriever.Hit> h5 = r.search("resep masak rendang padang");
        System.out.println("out-of-scope top score: " + (h5.isEmpty() ? "-" : h5.get(0).score));
        // corpus kecil BM25 hampir selalu > 0; yang penting skor jauh lebih rendah
        double topScoreInScope = h1.get(0).score;
        double topScoreOut = h5.isEmpty() ? 0 : h5.get(0).score;
        ok(topScoreOut < topScoreInScope / 3,
                "skor out-of-scope << in-scope (" + topScoreOut + " vs " + topScoreInScope + ")");

        // --- ResearchPrompt: hanya evidence yang diberikan ---
        ResearchPrompt p = ResearchPrompt.build("EXPLAIN", "Apa itu inferensi on-device?", h1, docsOf(pkgDir));
        ok(p.given.size() >= 1 && p.given.size() <= 4, "evidence 1..4 (dapat " + p.given.size() + ")");
        String sys = p.systemPrompt();
        boolean noForeign = !sys.contains("rendang");
        ok(noForeign, "prompt tak mengandung konten luar");
        ok(p.isValidChunkId(p.given.get(0).chunkId), "isValidChunkId menerima ID yang diberikan");
        ok(!p.isValidChunkId("doc-99#c01"), "isValidChunkId menolak ID asing");
        ok(sys.contains("[1]"), "prompt berisi marker [1]");

        // strict mode: minHits yang tidak tercapai
        ResearchPrompt strict = ResearchPrompt.buildStrict("EXPLAIN",
                "resep rendang", h5, docsOf(pkgDir), 5);
        ok(!strict.sufficient, "strict mode: bukti kurang -> sufficient=false");

        // --- CitationRenderer ---
        List<ResearchPrompt.Evidence> ev = p.given;
        String answer = "Inferensi on-device adalah menjalankan model di perangkat [1]. "
                + "Ini berbeda dari cloud [9]. Model bisa disebut juga doc-99#c01.";
        List<CitationRenderer.Span> spans = CitationRenderer.render(answer, ev);
        boolean hasValid = false, noInvalid = true;
        for (CitationRenderer.Span s : spans) {
            if (s.marker > 0) hasValid = true;
        }
        String rendered = "";
        for (CitationRenderer.Span s : spans) rendered += s.text;
        ok(hasValid, "renderer menghasilkan sitasi valid");
        ok(!rendered.contains("[9]"), "marker [9] (tidak diberikan ke model) dibuang");
        ok(CitationRenderer.rawIdIfValid(answer, ev) == null,
                "rawIdIfValid menolak doc-99#c01 (tidak ada di evidence)");
        // raw ID valid: pakai chunkId PERTAMA yang benar-benar diberikan ke model
        String firstId = ev.get(0).chunkId;
        String answer2 = "Menurut " + firstId + ", model berjalan lokal.";
        ok(CitationRenderer.rawIdIfValid(answer2, ev) != null
                        && firstId.equals(CitationRenderer.rawIdIfValid(answer2, ev)),
                "raw ID valid terdeteksi (" + firstId + ")");

        System.out.println("\n=== " + pass + " pass, " + fail + " fail ===");
        System.exit(fail > 0 ? 1 : 0);
    }

    static List<org.json.JSONObject> docJsons;

    // docs dummy untuk prompt (title lookup)
    static List<com.rezz.rlnova.research.PackageManifest.Doc> docsOf(String pkgDir) throws Exception {
        List<com.rezz.rlnova.research.PackageManifest.Doc> out = new ArrayList<>();
        File docs = new File(pkgDir, "docs");
        File[] md = docs.listFiles((d, n) -> n.endsWith(".md"));
        if (md != null) {
            Arrays.sort(md);
            for (File f : md) {
                com.rezz.rlnova.research.PackageManifest.Doc d =
                        new com.rezz.rlnova.research.PackageManifest.Doc();
                d.id = f.getName().substring(0, f.getName().length() - 3);
                d.title = d.id;
                out.add(d);
            }
        }
        return out;
    }

    // ================= genChunks: generate assets/knowledge =================
    static void genChunks(String pkgDir, String outDir) throws Exception {
        File docs = new File(pkgDir, "docs");
        File[] md = docs.listFiles((d, n) -> n.endsWith(".md"));
        Arrays.sort(md);
        File outDocs = new File(outDir, "docs");
        outDocs.mkdirs();

        // parse metadata dari manifest.md: bagian "### doc-NN — ..." berisi
        // baris "- Judul:", "- URL/sumber:", "- Attribution:", "- Lisensi:",
        // "- Bahasa:", "- path:"
        Map<String, Map<String, String>> meta = new LinkedHashMap<>();
        String cur = null;
        for (String line : Files.readAllLines(Paths.get(pkgDir, "manifest.md"))) {
            if (line.startsWith("### doc-")) {
                cur = line.substring(4).split(" ")[0].trim(); // "doc-01"
                meta.put(cur, new LinkedHashMap<>());
            } else if (cur != null && line.startsWith("- ")) {
                int c = line.indexOf(':');
                if (c > 2) meta.get(cur).put(line.substring(2, c).trim(),
                        line.substring(c + 1).trim());
            } else if (line.startsWith("## ")) {
                cur = null;
            }
        }

        JSONArray docsArr = new JSONArray();
        for (File f : md) {
            String docId = f.getName().substring(0, f.getName().length() - 3);
            // manifest.md pakai ID pendek (doc-01); cocokkan by prefix
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> en : meta.entrySet()) {
                if (docId.startsWith(en.getKey())) { m = en.getValue(); break; }
            }
            String txt = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            Files.copy(f.toPath(), new File(outDocs, f.getName()).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            List<Chunker.Chunk> chunks = Chunker.chunkDocument(docId, txt);

            JSONArray carr = new JSONArray();
            for (Chunker.Chunk c : chunks) carr.put(c.chunkId);
            JSONObject o = new JSONObject();
            o.put("id", docId);
            o.put("title", m.getOrDefault("Judul", docId));
            o.put("url", m.getOrDefault("URL/sumber", ""));
            o.put("attribution", m.getOrDefault("Attribution", ""));
            o.put("license", m.getOrDefault("Lisensi", "CC0 1.0"));
            o.put("lang", m.getOrDefault("Bahasa", "id").toLowerCase());
            o.put("path", m.getOrDefault("path", "docs/" + f.getName()));
            o.put("chunk_count", chunks.size());
            o.put("chunk_ids", carr);
            docsArr.put(o);
            System.out.println(docId + ": " + chunks.size() + " chunks");
        }

        JSONObject manifest = new JSONObject();
        manifest.put("id", "local-ai-privacy-v1");
        manifest.put("name", "AI Lokal, Privasi, dan Inferensi On-Device");
        manifest.put("version", "1.0.0");
        manifest.put("version_date", "2026-10-31");
        manifest.put("topic", "AI lokal, privasi data, dan inferensi on-device");
        manifest.put("docs", docsArr);
        Files.write(Paths.get(outDir, "manifest.json"),
                manifest.toString(2).getBytes("UTF-8"));
        System.out.println("manifest.json ditulis ke " + outDir);
    }
}
