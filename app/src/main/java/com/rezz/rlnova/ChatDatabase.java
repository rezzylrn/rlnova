package com.rezz.rlnova;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Penyimpanan percakapan — tabel conversations + messages.
 * Satu database instance per proses (getWritableDatabase cached).
 */
public final class ChatDatabase extends SQLiteOpenHelper {

    public static final class Conversation {
        public long id;
        public String title;
        public String modelId;
        public String systemPrompt;
        public long createdAt;
        public long updatedAt;
    }

    public static final class Message {
        public long id;
        public long conversationId;
        public int role; // 0=system, 1=user, 2=assistant
        public String content;
        public long createdAt;
        public Integer tokens;     // nullable
        public Double tokPerSec;   // null kalau bukan dari AI
        public String modelId;     // nullable — model asal pesan assistant
    }

    public static final int ROLE_SYSTEM = 0, ROLE_USER = 1, ROLE_ASSISTANT = 2;

    private static volatile ChatDatabase instance;

    public static ChatDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (ChatDatabase.class) {
                if (instance == null) instance = new ChatDatabase(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private final SQLiteDatabase db;

    private ChatDatabase(Context ctx) {
        super(ctx, "rlnova.db", null, 2);
        db = getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE conversations ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "model_id TEXT NOT NULL, "
                + "title TEXT NOT NULL DEFAULT 'New conversation', "
                + "system_prompt TEXT NOT NULL DEFAULT '', "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, "
                + "role INTEGER NOT NULL, "
                + "content TEXT NOT NULL, "
                + "tokens INTEGER, "
                + "tok_per_sec REAL, "
                + "model_id TEXT, "
                + "created_at INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX idx_messages_conv ON messages(conversation_id, created_at)");
        d.execSQL("CREATE INDEX idx_conv_updated ON conversations(updated_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldV, int newV) {
        if (oldV < 2) {
            // v2: atribusi model per pesan. ALTER TABLE — data lama utuh,
            // baris lama dapat model_id NULL (ditampilkan tanpa nama model).
            d.execSQL("ALTER TABLE messages ADD COLUMN model_id TEXT");
        }
    }

    // -------------------------------------------------------------- CRUD

    public long createConversation(String modelId, String systemPrompt) {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("model_id", modelId);
        cv.put("system_prompt", systemPrompt);
        cv.put("created_at", now);
        cv.put("updated_at", now);
        return db.insert("conversations", null, cv);
    }

    public Conversation getConversation(long id) {
        Conversation r = null;
        android.database.Cursor c = db.rawQuery(
                "SELECT id, model_id, title, system_prompt, created_at, updated_at "
                        + "FROM conversations WHERE id = ?", new String[]{String.valueOf(id)});
        if (c.moveToFirst()) r = readConversation(c);
        c.close();
        return r;
    }

    public List<Conversation> listConversations() {
        ArrayList<Conversation> out = new ArrayList<>();
        android.database.Cursor c = db.rawQuery(
                "SELECT id, model_id, title, system_prompt, created_at, updated_at "
                        + "FROM conversations ORDER BY updated_at DESC", null);
        while (c.moveToNext()) out.add(readConversation(c));
        c.close();
        return out;
    }

    private static Conversation readConversation(android.database.Cursor c) {
        Conversation r = new Conversation();
        r.id = c.getLong(0);
        r.modelId = c.getString(1);
        r.title = c.getString(2);
        r.systemPrompt = c.getString(3);
        r.createdAt = c.getLong(4);
        r.updatedAt = c.getLong(5);
        return r;
    }

    /** Update judul (dipanggil sekali dari pesan user pertama). */
    public void renameConversation(long id, String title) {
        ContentValues cv = new ContentValues();
        cv.put("title", title.length() > 60 ? title.substring(0, 60) : title);
        cv.put("updated_at", System.currentTimeMillis());
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void setSystemPrompt(long id, String prompt) {
        ContentValues cv = new ContentValues();
        cv.put("system_prompt", prompt);
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(id)});
    }

    /** Update model percakapan (Fitur A: switch model dari layar chat). */
    public void setConversationModel(long id, String modelId) {
        ContentValues cv = new ContentValues();
        cv.put("model_id", modelId);
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteConversation(long id) {
        db.beginTransaction();
        try {
            db.delete("messages", "conversation_id=?", new String[]{String.valueOf(id)});
            db.delete("conversations", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void deleteMessage(long messageId) {
        db.delete("messages", "id=?", new String[]{String.valueOf(messageId)});
    }

    public void deleteAllConversations() {
        db.beginTransaction();
        try {
            db.delete("messages", null, null);
            db.delete("conversations", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public long insertMessage(long convId, int role, String content,
                              Integer tokens, Double tokPerSec) {
        return insertMessage(convId, role, content, tokens, tokPerSec, null);
    }

    /** insertMessage + model asal (untuk pesan assistant, atribusi di kartu AI). */
    public long insertMessage(long convId, int role, String content,
                              Integer tokens, Double tokPerSec, String modelId) {
        ContentValues cv = new ContentValues();
        cv.put("conversation_id", convId);
        cv.put("role", role);
        cv.put("content", content);
        if (tokens != null) cv.put("tokens", (int) tokens);
        if (tokPerSec != null) cv.put("tok_per_sec", tokPerSec);
        if (modelId != null) cv.put("model_id", modelId);
        cv.put("created_at", System.currentTimeMillis());
        long rowId = db.insert("messages", null, cv);
        touchConversation(convId);
        return rowId;
    }

    public void updateMessage(long messageId, String content) {
        ContentValues cv = new ContentValues();
        cv.put("content", content);
        db.update("messages", cv, "id=?", new String[]{String.valueOf(messageId)});
    }

    public void setMessageStats(long messageId, int tokens, double tokPerSec) {
        ContentValues cv = new ContentValues();
        cv.put("tokens", tokens);
        cv.put("tok_per_sec", tokPerSec);
        db.update("messages", cv, "id=?", new String[]{String.valueOf(messageId)});
    }

    public List<Message> getMessages(long convId) {
        ArrayList<Message> out = new ArrayList<>();
        android.database.Cursor c = db.rawQuery(
                "SELECT id, role, content, tokens, tok_per_sec, created_at, model_id "
                        + "FROM messages WHERE conversation_id=? ORDER BY created_at ASC, id ASC",
                new String[]{String.valueOf(convId)});
        while (c.moveToNext()) {
            Message m = new Message();
            m.id = c.getLong(0);
            m.role = c.getInt(1);
            m.content = c.getString(2);
            if (!c.isNull(3)) m.tokens = c.getInt(3);
            if (!c.isNull(4)) m.tokPerSec = c.getDouble(4);
            m.createdAt = c.getLong(5);
            if (!c.isNull(6)) m.modelId = c.getString(6);
            out.add(m);
        }
        c.close();
        return out;
    }

    private void touchConversation(long convId) {
        ContentValues cv = new ContentValues();
        cv.put("updated_at", System.currentTimeMillis());
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(convId)});
    }

    /** Sama dengan touch — dipanggil dari insert. Public juga dipakai UI. */
    public void touchConversationPublic(long convId) { touchConversation(convId); }
}
