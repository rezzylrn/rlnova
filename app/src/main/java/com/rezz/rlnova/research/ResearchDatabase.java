package com.rezz.rlnova.research;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Penyimpanan sesi riset — tabel TERPISAH dari chat (rlnova.db, DB version 4):
 *   - research_questions: id, task, question, answer, evidence_ids (JSON),
 *     declined, created_at, tokens, tok_per_sec, model_id.
 * Follow-up riset tetap lokal: tiap baris berdiri sendiri (retrieval ulang
 * per pertanyaan); riwayat chat biasa tidak dilibatkan.
 */
public final class ResearchDatabase extends SQLiteOpenHelper {

    public static final class Question {
        public long id;
        public String task, question, answer;
        public String evidenceIds;   // JSON array of chunkId
        public boolean declined;
        public Long createdAt;
        public Integer tokens;
        public Double tokPerSec;
        public String modelId;
    }

    private static volatile ResearchDatabase instance;

    public static ResearchDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (ResearchDatabase.class) {
                if (instance == null) {
                    instance = new ResearchDatabase(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final SQLiteDatabase db;

    private ResearchDatabase(Context ctx) {
        // SATU db file bersama ChatDatabase — upgrade diaudit di ChatDatabase.
        super(ctx, "rlnova.db", null, 4);
        db = getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        // Kosong: tabel utama dibuat ChatDatabase. ResearchDatabase tidak
        // pernah jadi opener pertama di runtime (ChatDatabase.get selalu
        // dipanggil lebih dulu di LibraryActivity), tapi tetap idempotent.
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldV, int newV) {
        // ChatDatabase yang menangani upgrade (v4 = tabel riset di sana).
    }

    /** Dipanggil dari ChatDatabase.onUpgrade (v4) — idempotent. */
    public static void ensureTablePublic(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS research_questions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "task TEXT NOT NULL, "
                + "question TEXT NOT NULL, "
                + "answer TEXT NOT NULL DEFAULT '', "
                + "evidence_ids TEXT NOT NULL DEFAULT '[]', "
                + "declined INTEGER NOT NULL DEFAULT 0, "
                + "tokens INTEGER, "
                + "tok_per_sec REAL, "
                + "model_id TEXT, "
                + "created_at INTEGER NOT NULL)");
    }

    private static void ensureTable(SQLiteDatabase d) {
        ensureTablePublic(d);
    }

    public long insertQuestion(String task, String question, String answer,
                               List<String> evidenceIds, boolean declined,
                               Integer tokens, Double tokPerSec, String modelId) {
        ContentValues cv = new ContentValues();
        cv.put("task", task);
        cv.put("question", question);
        cv.put("answer", answer);
        JSONArray arr = new JSONArray();
        for (String s : evidenceIds) arr.put(s);
        cv.put("evidence_ids", arr.toString());
        cv.put("declined", declined ? 1 : 0);
        if (tokens != null) cv.put("tokens", (int) tokens);
        if (tokPerSec != null) cv.put("tok_per_sec", tokPerSec);
        if (modelId != null) cv.put("model_id", modelId);
        cv.put("created_at", System.currentTimeMillis());
        return db.insert("research_questions", null, cv);
    }

    /** Update jawaban final + stats (dipanggil dari service saat generate selesai). */
    public void setAnswer(long id, String answer, Integer tokens,
                          Double tokPerSec, String modelId) {
        ContentValues cv = new ContentValues();
        cv.put("answer", answer);
        if (tokens != null) cv.put("tokens", tokens);
        if (tokPerSec != null) cv.put("tok_per_sec", tokPerSec);
        if (modelId != null) cv.put("model_id", modelId);
        db.update("research_questions", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Question> listQuestions(int limit) {
        ArrayList<Question> out = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT id, task, question, answer, evidence_ids, declined, tokens, "
                        + "tok_per_sec, model_id, created_at FROM research_questions "
                        + "ORDER BY id DESC LIMIT " + Math.max(1, limit), null);
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    public Question getQuestion(long id) {
        Cursor c = db.rawQuery(
                "SELECT id, task, question, answer, evidence_ids, declined, tokens, "
                        + "tok_per_sec, model_id, created_at FROM research_questions "
                        + "WHERE id=?", new String[]{String.valueOf(id)});
        Question q = c.moveToFirst() ? read(c) : null;
        c.close();
        return q;
    }

    public void deleteQuestion(long id) {
        db.delete("research_questions", "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteAll() {
        db.delete("research_questions", null, null);
    }

    private static Question read(Cursor c) {
        Question q = new Question();
        q.id = c.getLong(0);
        q.task = c.getString(1);
        q.question = c.getString(2);
        q.answer = c.getString(3);
        q.evidenceIds = c.getString(4);
        q.declined = c.getInt(5) != 0;
        if (!c.isNull(6)) q.tokens = c.getInt(6);
        if (!c.isNull(7)) q.tokPerSec = c.getDouble(7);
        if (!c.isNull(8)) q.modelId = c.getString(8);
        q.createdAt = c.getLong(9);
        return q;
    }
}
