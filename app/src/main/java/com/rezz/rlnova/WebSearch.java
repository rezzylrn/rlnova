package com.rezz.rlnova;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSearch (v1.2.4): pencarian web keyless untuk toggle riset di chat.
 *
 * v1.2.4 — MULTI-ENDPOINT FALLBACK (urut, pertama yang berhasil menang):
 *   (a) DDG Lite   https://lite.duckduckgo.com/lite/?q=... (HTML)
 *   (b) Bing HTML  https://www.bing.com/search?q=...       (HTML, li b_algo)
 *   (c) Wikipedia  https://id.wikipedia.org/w/api.php?...  (JSON, list=search)
 *
 * Latar: v1.2.3 hanya DDG Lite dan DDG kadang menandai client sebagai bot
 * (HTTP 202, 0 hasil) — user melihat "No internet connection" padahal HP
 * online. Kini tiap endpoint dicatat sebab gagalnya (exception class+message
 * / HTTP code / 0 hasil) di Outcome.failReasons; bila SEMUA gagal,
 * GenerationService TIDAK lagi menghentikan chat — lanjut pengetahuan dasar
 * model dengan warning.
 *
 * Parser diverifikasi terhadap file live di tests/ws/ (ParseTest):
 *   live_*.html (DDG), live_bing_*.html (Bing), live_wiki_*.json (Wikipedia).
 *
 * Kontrak untuk pemanggil (GenerationService):
 *   - searchOutcome(q) → Outcome (ok() / detail gagal per endpoint)
 *   - isEmpty(s)       → true bila evidence tidak layak dipakai
 *   - researchSystemPrompt() / userPrompt(q, evidence) → prompt riset berbasis
 *     bukti; model DILARANG jawab di luar bukti, sitasi wajib [1]..[n].
 *
 * Semua network di sini HARUS dipanggil dari worker thread (sudah —
 * dipanggil dari EXECUTOR GenerationService, bukan UI thread).
 */
public final class WebSearch {

    private static final String DDG_URL = "https://lite.duckduckgo.com/lite/";
    private static final String BING_URL = "https://www.bing.com/search";
    private static final String WIKI_URL =
            "https://id.wikipedia.org/w/api.php";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_RESULTS = 6;
    private static final int MAX_SNIPPET = 320;
    /** Audit Argon v1.2.4: evidence ke model dibatasi ~3500 karakter pertama. */
    private static final int MAX_EVIDENCE_CHARS = 3500;

    public static final String NO_RESULTS = "NO_RESULTS";

    private static final String UA_CHROME =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

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

    // ------------------------------------------------------------ query core

    /**
     * Sebab kegagalan query — v1.2.4: hasil percobaan TIAP endpoint
     * tercatat di failReasons (bukan cuma boolean networkError), supaya
     * pesan ke user memuat detail asli (exception class+message / HTTP code).
     */
    public static final class Outcome {
        public final boolean networkError;          // minimal satu exception jaringan
        public final int httpCode;                  // HTTP code endpoint terakhir
        public final List<Hit> hits;                // null bila error; bisa kosong
        /** v1.2.4: alasan gagal per endpoint, urut percobaan. Bisa kosong. */
        public final List<String> failReasons;
        public Outcome(boolean net, int code, List<Hit> h, List<String> fails) {
            networkError = net; httpCode = code; hits = h; failReasons = fails;
        }
        public boolean ok() { return !networkError && httpCode == 200
                && hits != null && !hits.isEmpty(); }
        /** Ringkasan satu-baris semua sebab gagal — untuk pesan user/log. */
        public String failSummary() {
            if (failReasons == null || failReasons.isEmpty())
                return "no failure recorded";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < failReasons.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append('[').append(i + 1).append("] ")
                  .append(failReasons.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * Cari web, kembalikan blok bukti terformat ([1] judul — URL — snippet…),
     * dipotong maks MAX_EVIDENCE_CHARS karakter pertama. null/NO_RESULTS bila
     * gagal jaringan / tidak ada hasil / parse kosong.
     */
    public static String search(String query) {
        List<Hit> hits = query(query);
        return formatHits(hits);
    }

    /** Format daftar hit → blok bukti (dipotong maks ~3500 char); NO_RESULTS bila kosong. */
    public static String formatHits(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) return NO_RESULTS;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            if (sb.length() > 0 && sb.length() + 40 > MAX_EVIDENCE_CHARS) break;
            sb.append('[').append(i + 1).append("] ").append(h.title)
              .append(" — ").append(h.url).append('\n')
              .append(h.snippet).append("\n\n");
            if (sb.length() > MAX_EVIDENCE_CHARS) {
                sb.setLength(MAX_EVIDENCE_CHARS);
                sb.append("…"); // dipotong — sisanya tidak dikirim ke model
                break;
            }
        }
        return sb.toString().trim();
    }

    /**
     * Versi detail search(): coba TIAP endpoint berurutan, pertama yang
     * menghasilkan >= 1 hit menang. Tiap kegagalan dicatat ke
     * Outcome.failReasons (exception class+message atau HTTP code atau
     * "0 hasil"). Bila semua gagal: ok()==false, failSummary() menjelaskan.
     */
    public static Outcome searchOutcome(String query) {
        if (query == null || query.trim().isEmpty())
            return new Outcome(false, 200, new ArrayList<Hit>(),
                    new ArrayList<String>());
        List<String> fails = new ArrayList<>();
        boolean anyNetErr = false;
        int lastCode = 0;
        List<Hit> lastHits = null;

        // (a) DDG Lite
        try {
            HttpURLConnection c = get(DDG_URL + "?q="
                    + URLEncoder.encode(query, "UTF-8") + "&kl=wt-wt");
            int code = c.getResponseCode();
            lastCode = code;
            if (code == 200) {
                List<Hit> h = parse(readAll(c.getInputStream()));
                close(c);
                lastHits = h;
                if (!h.isEmpty())
                    return new Outcome(false, 200, h, fails);
                fails.add("ddg: 200 tapi 0 hasil");
            } else {
                close(c);
                fails.add("ddg: HTTP " + code);
            }
        } catch (Throwable t) {
            anyNetErr = true;
            fails.add("ddg: " + t.getClass().getSimpleName() + ": "
                    + msg(t));
        }

        // (b) Bing HTML
        try {
            HttpURLConnection c = get(BING_URL + "?q="
                    + URLEncoder.encode(query, "UTF-8"));
            int code = c.getResponseCode();
            lastCode = code;
            if (code == 200) {
                List<Hit> h = parseBing(readAll(c.getInputStream()));
                close(c);
                lastHits = h;
                if (!h.isEmpty())
                    return new Outcome(false, 200, h, fails);
                fails.add("bing: 200 tapi 0 hasil");
            } else {
                close(c);
                fails.add("bing: HTTP " + code);
            }
        } catch (Throwable t) {
            anyNetErr = true;
            fails.add("bing: " + t.getClass().getSimpleName() + ": "
                    + msg(t));
        }

        // (c) Wikipedia API (id)
        try {
            HttpURLConnection c = get(WIKI_URL + "?action=query&list=search"
                    + "&srsearch=" + URLEncoder.encode(query, "UTF-8")
                    + "&format=json&srlimit=" + MAX_RESULTS);
            int code = c.getResponseCode();
            lastCode = code;
            if (code == 200) {
                List<Hit> h = parseWikipedia(readAll(c.getInputStream()));
                close(c);
                lastHits = h;
                if (!h.isEmpty())
                    return new Outcome(false, 200, h, fails);
                fails.add("wikipedia: 200 tapi 0 hasil");
            } else {
                close(c);
                fails.add("wikipedia: HTTP " + code);
            }
        } catch (Throwable t) {
            anyNetErr = true;
            fails.add("wikipedia: " + t.getClass().getSimpleName() + ": "
                    + msg(t));
        }

        return new Outcome(anyNetErr, lastCode, lastHits, fails);
    }

    /** Message exception yang aman (null → nama class saja). */
    private static String msg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.toString() : m;
    }

    /** HttpURLConnection GET siap-pakai (UA Chrome, timeout 15 dtk). */
    private static HttpURLConnection get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA_CHROME);
        c.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        return c;
    }

    /** System prompt mode riset web (jawab HANYA dari bukti + sitasi). */
    public static String researchSystemPrompt() {
        return "You are a web research assistant. The user will give you search "
             + "results from the web (numbered [1]..[n]) followed by their question. "
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

    // ------------------------------------------------------------ query helpers

    /** Jalankan query → daftar hasil (null = error jaringan/HTTP). */
    public static List<Hit> query(String q) {
        Outcome o = searchOutcome(q);
        return o.ok() ? o.hits : (o.hits != null ? o.hits : null);
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

    // Pola markup DDG Lite (diverifikasi di tests/ws/live_*.html):
    //   <a rel="nofollow" href="//duckduckgo.com/l/?uddg=<enc>&rut=..."
    //      class='result-link'>JUDUL</a>
    //   ... lalu baris berikutnya: <td class='result-snippet'>SNIPPET</td>
    private static final Pattern LINK = Pattern.compile(
            "<a[^>]*href=\"([^\"]*)\"[^>]*class=['\"]result-link['\"][^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern SNIPPET = Pattern.compile(
            "<td[^>]*class=['\"]result-snippet['\"][^>]*>(.*?)</td>",
            Pattern.DOTALL);

    /**
            "<a[^>]*href=\"([^\"]*)\"[^>]*class=['\\"]result-link['\\"][^>]*>(.*?)</a>",
     * ke-i (link dan snippet selalu 1:1 berurutan di markup lite).
     */
    public static List<Hit> parse(String html) {
        List<Hit> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        List<String> titles = new ArrayList<>();
        List<String> hrefs = new ArrayList<>();
        Matcher m = LINK.matcher(html);
        while (m.find() && titles.size() < MAX_RESULTS) {
            String title = htmlToText(m.group(2));
            if (title == null || title.isEmpty()) continue;
            titles.add(title);
            hrefs.add(m.group(1));
        }
        List<String> snips = new ArrayList<>();
        m = SNIPPET.matcher(html);
        while (m.find() && snips.size() < titles.size()) {
            snips.add(htmlToText(m.group(1)));
        }
        for (int i = 0; i < titles.size(); i++) {
            String url = unwrap(hrefs.get(i));
            String snippet = i < snips.size() ? snips.get(i) : "";
            if (url == null) continue;
            if (snippet.length() > MAX_SNIPPET)
                snippet = snippet.substring(0, MAX_SNIPPET) + "…";
            out.add(new Hit(titles.get(i), url, snippet));
        }
        return out;
    }

    // ------------------------------------------------------------ Bing parse

    // Markup Bing (diverifikasi di tests/ws/live_bing_*.html): hasil di
    // <li class="b_algo">…</li>, tiap blok berisi:
    //   <a class="tilk" ... href="https://...">          → URL asli
    //   <h2>…judul (boleh berisi <strong>)…</h2></a>     → judul
    //   <div class="b_caption"><p ...>SNIPPET</p></div>  → snippet
    private static final Pattern BING_BLOCK = Pattern.compile(
            "<li[^>]*class=\"b_algo\"[^>]*>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern BING_LINK = Pattern.compile(
            "<a[^>]*class=\"tilk\"[^>]*href=\"([^\"]+)\"");
    private static final Pattern BING_TITLE = Pattern.compile(
            "<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern BING_SNIPPET = Pattern.compile(
            "<div[^>]*class=\"b_caption\"[^>]*>.*?<p[^>]*>(.*?)</p>",
            Pattern.DOTALL);

    public static List<Hit> parseBing(String html) {
        List<Hit> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        Matcher mb = BING_BLOCK.matcher(html);
        while (mb.find() && out.size() < MAX_RESULTS) {
            String block = mb.group(1);
            Matcher mu = BING_LINK.matcher(block);
            if (!mu.find()) continue;
            String url = unwrap(mu.group(1));
            if (url == null || !url.startsWith("http")) continue;
            String title = "";
            Matcher mt = BING_TITLE.matcher(block);
            if (mt.find()) title = htmlToText(mt.group(1));
            if (title.isEmpty()) continue;
            String snippet = "";
            Matcher ms = BING_SNIPPET.matcher(block);
            if (ms.find()) snippet = htmlToText(ms.group(1));
            if (snippet.length() > MAX_SNIPPET)
                snippet = snippet.substring(0, MAX_SNIPPET) + "…";
            out.add(new Hit(title, url, snippet));
        }
        return out;
    }

    // -------------------------------------------------------- Wikipedia parse

    // JSON API (diverifikasi di tests/ws/live_wiki_*.json):
    // query.search[] = { title, snippet (HTML dengan <span class="searchmatch">) }
    private static final Pattern WIKI_TITLE = Pattern.compile(
            "\"title\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern WIKI_SNIPPET = Pattern.compile(
            "\"snippet\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Parse JSON Wikipedia API (list=search) → daftar Hit.
     * URL dibangun dari judul: https://id.wikipedia.org/wiki/<judul>
     * (spasi → underscore, title URL-encoded).
     */
    public static List<Hit> parseWikipedia(String json) {
        List<Hit> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        Matcher mt = WIKI_TITLE.matcher(json);
        Matcher ms = WIKI_SNIPPET.matcher(json);
        while (mt.find() && out.size() < MAX_RESULTS) {
            String title = mt.group(1);
            // lewati key di luar array search (mis. "title" di namespace meta)
            if (!ms.find()) break;
            String snippet = unescapeJson(ms.group(1));
            String cleanTitle = htmlToText(unescapeJson(title));
            if (cleanTitle.isEmpty()) continue;
            snippet = htmlToText(snippet);
            if (snippet.length() > MAX_SNIPPET)
                snippet = snippet.substring(0, MAX_SNIPPET) + "…";
            String url;
            try {
                url = "https://id.wikipedia.org/wiki/"
                        + URLEncoder.encode(
                              cleanTitle.replace(' ', '_'), "UTF-8");
            } catch (Exception e) { url = "https://id.wikipedia.org/"; }
            out.add(new Hit(cleanTitle, url, snippet));
        }
        return out;
    }

    /** Unescape string JSON: \\n, ", \\\\, \\uXXXX, \\/. */
    private static String unescapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': break;
                    case '"': sb.append('"'); break;
                    case '/': sb.append('/'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(
                                        s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) { }
                        }
                        break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
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
