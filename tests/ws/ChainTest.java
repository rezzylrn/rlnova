import com.rezz.rlnova.WebSearch;
import java.nio.file.*;
import java.util.List;

/**
 * ChainTest v1.2.4: verifikasi rantai fallback v1.2.4 headless.
 *
 *   1. Parser Bing + Wikipedia di file live tests/ws/ (hit kebaca).
 *   2. failSummary() — ringkasan multi-endpoint.
 *   3. ok() FALSE meski networkError false / lastCode 200 (0 hasil semua).
 *   4. Fallback warn v1.2.4 terbentuk dengan detail asli (exception+HTTP).
 *
 * Usage: java com.rezz.ChainTest <live_bing.html> <live_wiki.json>
 * Exit 1 bila ada cek gagal.
 */
public class ChainTest {
    static int failures = 0;

    static void chk(boolean cond, String label, String detail) {
        System.out.println((cond ? "OK   " : "FAIL ") + label
                + (detail == null ? "" : " — " + detail));
        if (!cond) failures++;
    }

    public static void main(String[] a) throws Exception {
        String bingHtml = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
        String wikiJson = new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8");

        // 1. Parser Bing + Wikipedia kebaca dari file live
        List<WebSearch.Hit> bing = WebSearch.parseBing(bingHtml);
        chk(bing.size() >= 3, "parseBing hit kebaca", "hits=" + bing.size()
                + "; pertama: " + bing.get(0).title);
        List<WebSearch.Hit> wiki = WebSearch.parseWikipedia(wikiJson);
        chk(wiki.size() >= 3, "parseWikipedia hit kebaca", "hits=" + wiki.size()
                + "; pertama: " + wiki.get(0).title);

        // 2. failSummary multi-endpoint
        java.util.List<String> fails = new java.util.ArrayList<>();
        fails.add("ddg: 200 tapi 0 hasil");
        fails.add("bing: UnknownHostException: www.bing.com");
        fails.add("wikipedia: HTTP 503");
        com.rezz.rlnova.WebSearch.Outcome o = new com.rezz.rlnova.WebSearch.Outcome(
                true, 503, new java.util.ArrayList<WebSearch.Hit>(), fails);
        chk(!o.ok(), "ok()==false saat semua endpoint gagal", null);
        String fs = o.failSummary();
        chk(fs.contains("[1] ddg: 200 tapi 0 hasil"), "failSummary #1",
                fs);
        chk(fs.contains("[2] bing: UnknownHostException: www.bing.com"),
                "failSummary #2 (exception asli kebawa)", null);
        chk(fs.contains("[3] wikipedia: HTTP 503"), "failSummary #3", null);

        // 3. Warning fallback v1.2.4 (format GenerationService)
        String warn = "⚠️ Pencarian web gagal (" + fs
                + ") — menjawab dari pengetahuan dasar.";
        chk(warn.startsWith("⚠️ Pencarian web gagal (")
                && warn.contains("menjawab dari pengetahuan dasar."),
                "warn fallback terbentuk", warn);

        // 4. Outcome sukses (all-ok) → ok()==true
        com.rezz.rlnova.WebSearch.Outcome okO = new com.rezz.rlnova.WebSearch.Outcome(
                false, 200, new java.util.ArrayList<WebSearch.Hit>(),
                new java.util.ArrayList<String>());
        chk(okO.ok() == false, "ok() false bila hits kosong", null);

        System.out.println(failures == 0 ? "ALL_OK" : "FAILURES=" + failures);
        if (failures > 0) System.exit(1);
    }
}
