package com.rezz.rlnova;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Katalog model dari assets/models.json (SPEC: bisa diupdate tanpa rilis ulang).
 * Parser pakai org.json bawaan Android — di test JVM, stub manual.
 */
public final class ModelCatalog {

    public static final class Model {
        public String id, name, tier, repo, file, quant, chatTemplate, description;
        public long sizeBytes;
        public int minRamGb, context;

        public String url() {
            return "https://huggingface.co/" + repo + "/resolve/main/" + file;
        }
    }

    public final ArrayList<Model> models = new ArrayList<>();
    public int catalogVersion = 1;

    public static ModelCatalog load(Context ctx) throws Exception {
        InputStream is = ctx.getAssets().open("models.json");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return parse(new String(bos.toByteArray(), StandardCharsets.UTF_8));
    }

    /** Versi murni dari string JSON (untuk test di JVM tanpa Android). */
    public static ModelCatalog parse(String json) throws Exception {
        ModelCatalog c = new ModelCatalog();
        JSONObject root = new JSONObject(json);
        c.catalogVersion = root.optInt("version", 1);
        JSONArray arr = root.getJSONArray("models");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.getJSONObject(i);
            Model mo = new Model();
            mo.id = m.getString("id");
            mo.name = m.getString("name");
            mo.tier = m.getString("tier");
            mo.repo = m.getString("repo");
            mo.file = m.getString("file");
            mo.sizeBytes = m.getLong("sizeBytes");
            mo.minRamGb = m.getInt("minRamGb");
            mo.quant = m.getString("quant");
            mo.chatTemplate = m.getString("chatTemplate");
            mo.context = m.getInt("context");
            mo.description = m.getString("description");
            c.models.add(mo);
        }
        return c;
    }

    /** Direkomendasikan untuk HP: tier pas dan RAM cukup. */
    public Model recommendFor(DeviceInfo device) {
        DeviceInfo.Tier t = device.tier();
        for (Model m : models) {
            if (m.tier.equals(t.name()) && fits(m, device)) return m;
        }
        // fallback: model terkecil yang masih muat
        Model best = null;
        for (Model m : models) {
            if (fits(m, device) && (best == null || m.sizeBytes < best.sizeBytes)) best = m;
        }
        return best;
    }

    public boolean fits(Model m, DeviceInfo device) {
        double ramGb = device.totalRamBytes / (1024.0 * 1024.0 * 1024.0);
        return ramGb >= m.minRamGb;
    }

    /** Path lokal file model (app-private). */
    public static File localFile(Context ctx, Model m) {
        return new File(ctx.getFilesDir(), "models/" + m.file);
    }

    /**
     * Path file .gguf berdasarkan model id (penamaan service/download:
     * filesDir/models/<id>.gguf — dipakai GenerationService & ChatRoomFragment).
     */
    public static File localFileById(Context ctx, String modelId) {
        return new File(new File(ctx.getFilesDir(), "models"), modelId + ".gguf");
    }

    /** Nama tampilan dari modelId — fallback ke raw id kalau tidak ada di katalog. */
    public static String displayNameFor(java.util.List<Model> models, String modelId) {
        if (modelId == null) return null;
        for (Model m : models) {
            if (m.id.equals(modelId)) return m.name;
        }
        return modelId; // fallback: raw id
    }

    /** Cari model by id (null kalau tidak ada). */
    public static Model byId(java.util.List<Model> models, String modelId) {
        if (modelId == null) return null;
        for (Model m : models) {
            if (m.id.equals(modelId)) return m;
        }
        return null;
    }

    public static String tierLabel(String tier) {
        switch (tier) {
            case "LOW": return "Light";
            case "MID": return "Medium";
            case "HIGH": return "Heavy";
            default: return tier;
        }
    }
}
