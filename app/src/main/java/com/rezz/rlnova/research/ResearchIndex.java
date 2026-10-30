package com.rezz.rlnova.research;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * ResearchIndex: loader index riset in-memory.
 *  - Sumber paket: assets/knowledge/ (manifest.json + docs/*.md) — read-only.
 *  - Chunking + BM25 di-build in-memory saat Research dibuka. Corpus paket
 *    kecil (puluhan KB), build-nya deterministik dan murah (< 10 ms di host,
 *    belum diukur di device — pending QA rezz), jadi TIDAK ada cache SQLite
 *    yang bisa basi: sumber tunggal = assets, tidak ada jalur duplikat.
 *  - FTS5 SQLite bawaan Android TIDAK di-enable AOSP (terverifikasi riset
 *    2026-10-31: "no such module: fts5" sampai Android 14/API 34) — jadi
 *    ranking pakai BM25 pure-Java yang deterministik dan bisa dites di JVM
 *    host. Tidak ada asumsi fitur FTS di device.
 *  - Seluruh jalur ini TIDAK melakukan operasi jaringan (assets saja).
 */
public final class ResearchIndex {

    public final PackageManifest manifest;
    private final List<Chunker.Chunk> corpus = new ArrayList<>();
    private final Retriever retriever;

    /** Konstruktor penuh (dipakai host-test; runtime lewat load()). */
    public ResearchIndex(PackageManifest mf, List<Chunker.Chunk> corpus, Retriever r) {
        this.manifest = mf;
        this.corpus.addAll(corpus);
        this.retriever = r;
    }

    private ResearchIndex(PackageManifest mf, List<Chunker.Chunk> corpus) {
        this.manifest = mf;
        this.corpus.addAll(corpus);
        this.retriever = new Retriever(this.corpus);
    }

    public static ResearchIndex load(Context ctx) {
        PackageManifest mf;
        try {
            String s = PackageManifest.readAsset(ctx, "knowledge/manifest.json");
            mf = PackageManifest.fromJson(s);
        } catch (Exception e) {
            throw new IllegalStateException("Paket knowledge tidak ada / rusak", e);
        }
        List<Chunker.Chunk> corpus = new ArrayList<>();
        try {
            String[] files = ctx.getAssets().list(PackageManifest.docsDir());
            if (files != null) {
                java.util.Arrays.sort(files); // deterministik
                for (String fname : files) {
                    if (!fname.endsWith(".md")) continue;
                    String docId = fname.substring(0, fname.length() - 3);
                    String md = PackageManifest.readAsset(ctx,
                            PackageManifest.docsDir() + "/" + fname);
                    corpus.addAll(Chunker.chunkDocument(docId, md));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Gagal membaca paket: " + e.getMessage(), e);
        }
        if (corpus.isEmpty()) {
            throw new IllegalStateException("Paket knowledge kosong");
        }
        return new ResearchIndex(mf, corpus);
    }

    public Retriever retriever() { return retriever; }

    public List<PackageManifest.Doc> docs() { return manifest.docs; }

    public PackageManifest.Doc doc(String docId) {
        for (PackageManifest.Doc d : manifest.docs) {
            if (d.id.equals(docId)) return d;
        }
        return null;
    }

    public Chunker.Chunk chunk(String chunkId) {
        for (Chunker.Chunk c : corpus) {
            if (c.chunkId.equals(chunkId)) return c;
        }
        return null;
    }

    public int chunkCount() { return corpus.size(); }
}
