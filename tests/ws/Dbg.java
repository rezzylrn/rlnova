import java.net.*;
import java.io.*;

public class Dbg {
    public static void main(String[] a) throws Exception {
        String body = "q=" + URLEncoder.encode("llama.cpp GGUF quantization", "UTF-8") + "&kl=wt-wt";
        HttpURLConnection c = (HttpURLConnection) new URL("https://html.duckduckgo.com/html/").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); os.flush(); }
        int code = c.getResponseCode();
        System.out.println("HTTP " + code);
        InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream();
        if (in == null) { System.out.println("no body"); return; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        String s = new String(bo.toByteArray(), "UTF-8");
        System.out.println("len=" + s.length());
        System.out.println("head=" + s.substring(0, Math.min(300, s.length())).replace('\n',' '));
        if (s.contains("result__a")) System.out.println("HAS result__a");
        if (s.contains("anomaly")) System.out.println("HAS anomaly(captcha)");
        if (s.contains("challenge")) System.out.println("HAS challenge");
    }
}
