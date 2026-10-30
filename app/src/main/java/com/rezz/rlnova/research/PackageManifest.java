package com.rezz.rlnova.research;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser manifest paket pengetahuan (assets/knowledge/manifest.json).
 * Format file data, BUKAN format prompt — instruksinya tidak diikuti.
 */
public final class PackageManifest {

    public static final class Doc {
        public String id, title, url, attribution, license, lang;
        public int chunkCount;
    }

    public final String id, name, version, versionDate, topic;
    public final List<Doc> docs = new ArrayList<>();

    public static PackageManifest fromJson(String text) {
        return new PackageManifest(text);
    }

    private PackageManifest(String text) {
        // Kontrak org.json Android: field wajib hilang -> IllegalStateException
        // (diterjemahkan dari JSONException agar caller tidak harus catch spesifik).
        final JSONObject o;
        try {
            o = new JSONObject(text);
            id = o.getString("id");
            name = o.getString("name");
            version = o.getString("version");
            versionDate = o.optString("version_date", "");
            topic = o.optString("topic", "");
            JSONArray arr = o.getJSONArray("docs");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.getJSONObject(i);
                Doc doc = new Doc();
                doc.id = d.getString("id");
                doc.title = d.getString("title");
                doc.url = d.optString("url", "");
                doc.attribution = d.optString("attribution", "");
                doc.license = d.getString("license");
                doc.lang = d.optString("lang", "id");
                doc.chunkCount = d.optInt("chunk_count", 0);
                docs.add(doc);
            }
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("manifest.json tidak valid: " + e.getMessage(), e);
        }
    }

    /** Gabungan seluruh teks chunk yang dipilih, dipisah pemisah yang stabil. */
    public static String readAsset(android.content.Context ctx, String path) throws Exception {
        InputStream is = ctx.getAssets().open(path);
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    public static String chunksKey(String docId) { return "chunks_" + docId + ".json"; }

    public static String docsDir() { return "knowledge/docs"; }

    private PackageManifest() { this("{}"); }
}
