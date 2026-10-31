package com.rezz.rlnova;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSearch (v1.2.2): pencarian web keyless untuk toggle riset di chat.
 *
 * Endpoint: DuckDuckGo HTML ("https://html.duckduckgo.com/html/") — POST form
 * q=<query>&kl=wt-wt, TANPA API key, TANPA layanan berbayar. Respons HTML
 * di-parse dengan regex ke daftar {judul, URL, snippet} (maks 6 hasil).
 * Redirect eksternal (//duckduckgo.com/l/?uddg=...) di-decode ke URL asli.
 *
 * Kontrak untuk pemanggil (GenerationService):
 *   - search(query)  → null/""/marker "NO_RESULTS" = gagal/kosong (cek isEmpty)
 *   - isEmpty(s)     → true bila evidence tidak layak dipakai
 *   - researchSystemPrompt() / userPrompt(q, evidence) → prompt riset berbasis
 *     bukti; model DILARANG jawab di luar bukti, sitasi wajib [1]..[6].
 *
 * Semua network di sini HARUS dipanggil dari worker thread (sudah —
 * dipanggil dari EXECUTOR GenerationService, bukan UI thread).
 */
public final class WebSearch {

    private static final String DDG_URL = "https://html.duckduckgo.com/html/";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_RESULTS = 6;
    private static final int MAX_SNIPPET = 320;

    public static final String NO_RESULTS = "NO_RESULTS";

    /** Hasil satu entri pencarian. */
    public static final class Hit {
        public final String title;
        public final String url;
        public final String snippet;
        Hit(String t, String u, String s) { title = t; url = u; snippet = s; }
    }

    private WebSearch() { }

    /** true bila evidence null/kosong/marker NO_RESULTS → tolak jujur. */
    public static boolean isEmpty(String evidence) {
        return evidence == null || evidence.trim().isEmpty()
                || evidence.trim().equals(NO_RESULTS);
    }

    // ------------------------------------------------------------ public API

    /**
     * Cari web, kembalikan blok bukti terformat ([1] judul — URL — snippet…).
     * null/NO_RESULTS bila gagal jaringan / tidak ada hasil / parse kosong.
     */
    public static String search(String query) {
        List<Hit> hits = query(query);
        if (hits == null || hits.isEmpty()) return NO_RESULTS;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            sb.append('[').append(i + 1).append("] ").append(h.title)
              .append(" — ").append(h.url).append('\n')
              .append(h.snippet).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** System prompt mode riset web (jawab HANYA dari bukti + sitasi). */
    public static String researchSystemPrompt() {
        return "You are a web research assistant. The user will give you search "
             + "results from the web (numbered [1]..[6]) followed by their question. "
             + "Answer the question using ONLY the information in these search "
             + "results. Cite sources inline with their numbers, like [1] or [2][3]. "
             + "If the search results do not contain enough information, say so "
             + "honestly instead of guessing. Do not use knowledge outside the "
             + "provided results. Answer in the same language the user asked in.";
    }

    /** User prompt: bukti terformat + pertanyaan. */
    public static String userPrompt(String question, String evidence) {
        return "WEB SEARCH RESULTS:\n\n" + evidence + "\n\n---\n\n"
             + "QUESTION: " + (question == null ? "" : question);
    }

    // ------------------------------------------------------------ query core

    /** Jalankan query → daftar hasil (null = error jaringan/HTTP). */
    public static List<Hit> query(String q) {
        if (q == null || q.trim().isEmpty()) return null;
        try {
            String body = "q=" + URLEncoder.encode(q, "UTF-8") + "&kl=wt-wt";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);

            HttpURLConnection c = (HttpURLConnection)
                    new URL(DDG_URL).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            c.setRequestProperty("Content-Length",
                    String.valueOf(payload.length));
            // UA Chrome — DDG menolak UA tanpa-browser di beberapa edge.
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; "
                    + "Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36");
            c.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
                os.flush();
            }
            int code = c.getResponseCode();
            if (code != 200) { close(c); return null; }
            String html = readAll(c.getInputStream());
            close(c);
            return parse(html);
        } catch (Throwable t) {
            return null; // jaringan gagal → caller tolak jujur
        }
    }

    private static void close(HttpURLConnection c) {
        try { c.getInputStream().close(); } catch (Throwable ignored) { }
        c.disconnect();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ HTML parse

    // Pola div.result hasil pencarian DDG HTML. <a rel="nofollow"
    // class="result__a" href="//duckduckgo.com/l/?uddg=<enc>&rut=...">
    // <b>JUDUL</b></a> ... <a class="result__snippet">SNIPPET</a>.
    private static final Pattern RESULT = Pattern.compile(
            "result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"   // 1 href, 2 judul
          + ".*?result__snippet[^>]*>(.*?)</a>",                 // 3 snippet
            Pattern.DOTALL);

    public static List<Hit> parse(String html) {
        List<Hit> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        Matcher m = RESULT.matcher(html);
        while (m.find() && out.size() < MAX_RESULTS) {
            String href = m.group(1);
            String title = htmlToText(m.group(2));
            String snippet = htmlToText(m.group(3));
            String url = unwrap(href);
            if (url == null || title == null || title.isEmpty()) continue;
            if (snippet.length() > MAX_SNIPPET)
                snippet = snippet.substring(0, MAX_SNIPPET) + "…";
            out.add(new Hit(title, url, snippet));
        }
        return out;
    }

    /**
     * URL DDG eksternal dikemas di //duckduckgo.com/l/?uddg=<encoded>.
     * Ambil uddg-nya dan decode. URL non-l (internal) diteruskan apa adanya.
     */
    public static String unwrap(String href) {
        if (href == null) return null;
        String h = href;
        if (h.startsWith("//")) h = "https:" + h;
        int i = h.indexOf("uddg=");
        if (i >= 0) {
            String u = h.substring(i + 5);
            int amp = u.indexOf('&');
            if (amp >= 0) u = u.substring(0, amp);
            try { return URLEncoder.encode(u, "UTF-8").equals(u) || u.contains("%")
                    ? java.net.URLDecoder.decode(u, "UTF-8") : u; }
            catch (Exception e) { return u; }
        }
        return h;
    }

    /** HTML → teks: unescape entity umum + strip tag + normalisasi spasi. */
    public static String htmlToText(String s) {
        if (s == null) return "";
        String t = s
                .replace("<b>", "").replace("</b>", "")
                .replace("<strong>", "").replace("</strong>", "")
                .replace("<em>", "").replace("</em>", "")
                .replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&#x2F;", "/").replace("&sol;", "/");
        t = t.replaceAll("<[^>]+>", " ");
        t = t.replace("&hellip;", "…");
        return t.replaceAll("\\s+", " ").trim();
    }
}
