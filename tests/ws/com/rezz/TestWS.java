import com.rezz.rlnova.WebSearch;
import java.util.List;

public class TestWS {
    public static void main(String[] args) throws Exception {
        String q = args.length > 0 ? String.join(" ", args)
                : "llama.cpp GGUF quantization";
        List<WebSearch.Hit> hits = WebSearch.query(q);
        System.out.println("hits=" + (hits == null ? "null(offline)" : hits.size()));
        if (hits == null || hits.isEmpty()) {
            System.out.println("OFFLINE_OR_EMPTY=true");
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            WebSearch.Hit h = hits.get(i);
            System.out.println("[" + (i + 1) + "] " + h.title + " | " + h.url);
        }
        String ev = WebSearch.search(q);
        System.out.println("isEmpty=" + WebSearch.isEmpty(ev));
        System.out.println("evLen=" + ev.length());
        System.out.println("--- evidence head ---");
        System.out.println(ev.substring(0, Math.min(600, ev.length())));
    }
}
