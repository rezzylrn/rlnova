package com.rezz.rlnova.research;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunker: pecah dokumen markdown paket pengetahuan jadi chunk dengan ID stabil.
 * ID = "<docId>#c<NN>" (NN dua digit, mulai 01), urutan kemunculan di file.
 * Aturan: paragraf (dipisah baris kosong) digabung sampai ~600 karakter;
 * header markdown jadi prefix konteks paragraf di bawahnya. Headings tidak
 * jadi chunk sendiri.
 *
 * Deterministik — input sama menghasilkan chunk sama (ID stabil antar build).
 */
public final class Chunker {

    public static final class Chunk {
        public final String docId, chunkId, text;
        public Chunk(String docId, int idx, String text) {
            this.docId = docId;
            this.chunkId = docId + "#c" + String.format(java.util.Locale.US, "%02d", idx + 1);
            this.text = text;
        }
    }

    private static final int TARGET = 600;   // max karakter per chunk (manifest: ~600)
    private static final int MIN_LEN = 40;   // paragraf lebih pendek dari ini digabung ke tetangga

    public static List<Chunk> chunkDocument(String docId, String markdown) {
        List<Chunk> out = new ArrayList<>();
        String heading = "";
        StringBuilder para = new StringBuilder();
        StringBuilder buf = new StringBuilder();

        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("#")) {
                // heading: tutup paragraf berjalan, simpan sebagai konteks berikutnya
                appendPara(out, docId, buf, para, heading);
                heading = t.replaceFirst("^#+\\s*", "").trim();
                continue;
            }
            if (t.isEmpty()) {
                appendPara(out, docId, buf, para, heading);
                continue;
            }
            if (para.length() > 0) para.append(' ');
            para.append(t);
        }
        appendPara(out, docId, buf, para, heading);

        // sisa buffer yang belum full di-flush
        if (buf.length() > 0) {
            String b = buf.toString().trim();
            if (!b.isEmpty()) out.add(new Chunk(docId, out.size(), b));
        }
        return out;
    }

    private static void appendPara(List<Chunk> out, String docId,
                                   StringBuilder buf, StringBuilder para, String heading) {
        if (para.length() == 0) return;
        String p = para.toString();
        para.setLength(0);
        if (heading.isEmpty()) {
            addText(out, docId, buf, p);
        } else {
            String withHead = "[" + heading + "] " + p;
            if (withHead.length() + 1 <= TARGET) {
                addText(out, docId, buf, withHead);
            } else {
                addText(out, docId, buf, p); // heading tidak muat — konten tetap utuh
            }
        }
    }

    private static void addText(List<Chunk> out, String docId, StringBuilder buf, String text) {
        if (text.isEmpty()) return;
        if (buf.length() == 0) {
            buf.append(text);
        } else if (buf.length() + 1 + text.length() <= TARGET) {
            buf.append('\n').append(text);
        } else {
            String b = buf.toString();
            if (!b.isEmpty()) out.add(new Chunk(docId, out.size(), b));
            buf.setLength(0);
            buf.append(text);
        }
        // single paragraf over TARGET: pecah per kalimat kasar
        if (buf.length() > TARGET) {
            String b = buf.toString();
            int cut = b.lastIndexOf('.', TARGET);
            if (cut < MIN_LEN) cut = TARGET;
            out.add(new Chunk(docId, out.size(), b.substring(0, cut + 1).trim()));
            buf.setLength(0);
            buf.append(b.substring(cut + 1).trim());
        }
    }

}
