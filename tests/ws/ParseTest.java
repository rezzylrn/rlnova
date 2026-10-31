import com.rezz.rlnova.WebSearch;
import java.nio.file.*;
import java.util.List;

/**
 * ParseTest v1.2.3: jalankan parser DDG Lite BARU di atas file HTML live
 * (tests/ws/live_*.html, hasil fetch asli lite.duckduckgo.com/lite/).
 * Usage: java ParseTest <file.html> [...]
 * Exit code 1 bila ada file dengan 0 hasil.
 */
public class ParseTest {
    public static void main(String[] a) throws Exception {
        int failures = 0;
        for (String f : a) {
            String html = new String(Files.readAllBytes(Paths.get(f)), "UTF-8");
            List<WebSearch.Hit> hits = WebSearch.parse(html);
            System.out.println("== " + f + " -> hits=" + hits.size());
            for (int i = 0; i < hits.size(); i++) {
                WebSearch.Hit h = hits.get(i);
                System.out.println("  [" + (i + 1) + "] " + h.title + " | " + h.url);
                System.out.println("      " + h.snippet.substring(0,
                        Math.min(100, h.snippet.length())));
            }
            if (hits.isEmpty()) failures++;
        }
        System.out.println("unwrap=" + WebSearch.unwrap(
                "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&amp;rut=abc"));
        System.out.println(failures == 0 ? "ALL_OK" : "FAILURES=" + failures);
        if (failures > 0) System.exit(1);
    }
}
