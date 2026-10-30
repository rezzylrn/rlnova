package com.rezz.rlnova.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CitationRenderer: render jawaban model jadi segmen + sitasi.
 *  - Marker yang dikenal: [1], [2] … (hanya marker yang ADA di evidence list).
 *  - ID mentah (doc-04#c02) yang ditempel model juga diterima TAPI hanya bila
 *    ID itu ada di evidence — lainnya diabaikan (dianggap halusinasi).
 *  - Semua token [N] yang TIDAK valid dihapus dari teks tampilan.
 */
public final class CitationRenderer {

    public static final class Span {
        public final String text;          // potongan teks jawaban
        public final int marker;           // -1 = teks biasa, >=1 = mulai sitasi
        public final String chunkId;       // chunkID sumber (null bila teks biasa)
        public Span(String t, int m, String c) { text = t; marker = m; chunkId = c; }
    }

    private static final Pattern BRACKET = Pattern.compile("\\[(\\d{1,2})\\]");
    private static final Pattern RAWID = Pattern.compile("(doc-[A-Za-z0-9._-]+#c\\d{2})");

    public static List<Span> render(String answer, List<ResearchPrompt.Evidence> given) {
        List<Span> out = new ArrayList<>();
        Map<Integer, String> valid = new TreeMap<>();
        for (ResearchPrompt.Evidence e : given) valid.put(e.marker, e.chunkId);

        Matcher m = BRACKET.matcher(answer);
        int last = 0;
        StringBuilder plain = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String chunkId = valid.get(n);
            plain.append(answer, last, m.start());
            if (chunkId != null) {
                // sitasi valid: flush teks, buka span sitasi
                if (plain.length() > 0) {
                    out.add(new Span(plain.toString(), -1, null));
                    plain.setLength(0);
                }
                out.add(new Span("", n, chunkId));
            }
            // marker tidak valid: dibuang (tidak ditampilkan, tidak jadi sitasi)
            last = m.end();
        }
        plain.append(answer.substring(last));
        if (plain.length() > 0) out.add(new Span(plain.toString(), -1, null));
        return out;
    }

    /**
     * Fallback deteksi ID mentah (kalau model menulis doc-04#c02 bukan [N]):
     * dipakai renderer UI untuk mengubah jadi marker bila valid. Mengembalikan
     * chunkId bila valid, null selain itu.
     */
    public static String rawIdIfValid(String text, List<ResearchPrompt.Evidence> given) {
        Matcher m = RAWID.matcher(text == null ? "" : text);
        if (!m.find()) return null;
        String id = m.group(1);
        for (ResearchPrompt.Evidence e : given) {
            if (e.chunkId.equals(id)) return id;
        }
        return null;
    }
}
