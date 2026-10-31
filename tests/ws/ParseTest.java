import com.rezz.rlnova.WebSearch;
import java.nio.file.*;
import java.util.List;

public class ParseTest {
    public static void main(String[] a) throws Exception {
        String html = new String(Files.readAllBytes(Paths.get("/tmp/ddg.html")), "UTF-8");
        List<WebSearch.Hit> hits = WebSearch.parse(html);
        System.out.println("hits=" + hits.size());
        for (int i = 0; i < hits.size(); i++) {
            WebSearch.Hit h = hits.get(i);
            System.out.println("[" + (i + 1) + "] " + h.title + " | " + h.url);
            System.out.println("    " + h.snippet.substring(0, Math.min(120, h.snippet.length())));
        }
        // unwrap test untuk uddg-style redirect
        System.out.println("unwrap=" + WebSearch.unwrap("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=abc"));
    }
}
