package local.modem.sms;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SmsStore extends SQLiteOpenHelper {
    private static SmsStore instance;
    private final Context context;
    private boolean providerAvailable = true;
    public static synchronized SmsStore get(Context context) {
        if (instance == null) instance = new SmsStore(context.getApplicationContext());
        return instance;
    }
    private SmsStore(Context context) { super(context, "inbox.db", null, 2); this.context = context; }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT, message_key TEXT NOT NULL UNIQUE, sender TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, source TEXT NOT NULL)");
        createV2(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) { createV2(db); return; }
        throw new IllegalStateException("Unsupported database migration");
    }
    private static void createV2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deleted_messages (message_key TEXT PRIMARY KEY NOT NULL)");
        db.execSQL("CREATE INDEX messages_sender_id ON messages(sender, _id DESC)");
    }
    private static String fingerprint(String sender, String body, long sentAt) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((sender + "\u0000" + sentAt + "\u0000" + body).getBytes("UTF-8"));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    public synchronized void add(String sender, String body, long sentAt, long receivedAt, String source) throws Exception {
        sender = sender == null ? "" : sender;
        body = body == null ? "" : body;
        String key = fingerprint(sender, body, sentAt);
        SQLiteDatabase db = getWritableDatabase();
        Cursor deleted = db.query("deleted_messages", new String[]{"message_key"}, "message_key=?", new String[]{key}, null, null, null);
        try { if (deleted.moveToFirst()) return; } finally { deleted.close(); }
        ContentValues values = new ContentValues();
        values.put("message_key", key);
        values.put("sender", sender); values.put("body", body);
        values.put("sent_at", sentAt); values.put("received_at", receivedAt); values.put("source", source);
        db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }
    public synchronized void importSystem() {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(Uri.parse("content://sms/inbox"), new String[]{"address", "body", "date", "date_sent"}, null, null, "date ASC");
            if (cursor != null) while (cursor.moveToNext()) {
                long received = cursor.getLong(2), sent = cursor.getLong(3);
                add(cursor.getString(0), cursor.getString(1), sent > 0 ? sent : received, received, "system");
            }
            providerAvailable = true;
        } catch (Exception ex) { providerAvailable = false; Log.e("SmsInbox", "System inbox import failed", ex); }
        finally { if (cursor != null) cursor.close(); }
    }
    public synchronized JSONObject list(int offset, int limit) throws Exception {
        return list(offset, limit, null);
    }
    public synchronized JSONObject list(int offset, int limit, String sender) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        String where = sender == null ? null : "sender=?";
        String[] args = sender == null ? null : new String[]{sender};
        int total = count(db, "SELECT COUNT(*) FROM messages" + (sender == null ? "" : " WHERE sender=?"), args);
        int globalTotal = sender == null ? total : count(db, "SELECT COUNT(*) FROM messages", null);
        Cursor cursor = db.query("messages", new String[]{"_id", "sender", "body", "sent_at", "received_at"}, where, args, null, null, "_id DESC", offset + "," + limit);
        JSONArray messages = new JSONArray();
        try {
            while (cursor.moveToNext()) {
                JSONObject message = new JSONObject();
                message.put("id", cursor.getLong(0)); message.put("sender", cursor.getString(1));
                message.put("body", cursor.getString(2)); message.put("sentAt", cursor.getLong(3));
                message.put("receivedAt", cursor.getLong(4)); messages.put(message);
            }
        } finally { cursor.close(); }
        JSONObject result = new JSONObject();
        result.put("messages", messages); result.put("total", total); result.put("hasMore", offset + messages.length() < total);
        result.put("globalTotal", globalTotal);
        result.put("providerAvailable", providerAvailable); result.put("updatedAt", System.currentTimeMillis());
        return result;
    }
    public synchronized JSONObject senders(int offset, int limit) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        int total = count(db, "SELECT COUNT(DISTINCT sender) FROM messages", null);
        Cursor cursor = db.rawQuery("SELECT m.sender,g.message_count,m.body,m.received_at FROM (SELECT sender,COUNT(*) AS message_count,MAX(_id) AS last_id FROM messages GROUP BY sender) g JOIN messages m ON m._id=g.last_id ORDER BY g.last_id DESC LIMIT ? OFFSET ?", new String[]{String.valueOf(limit),String.valueOf(offset)});
        JSONArray groups = new JSONArray();
        try {
            while (cursor.moveToNext()) {
                JSONObject group = new JSONObject(); group.put("sender", cursor.getString(0));
                group.put("count", cursor.getInt(1)); group.put("preview", cursor.getString(2));
                group.put("receivedAt", cursor.getLong(3)); groups.put(group);
            }
        } finally { cursor.close(); }
        JSONObject result = new JSONObject(); result.put("senders", groups); result.put("total", total);
        result.put("globalTotal", count(db,"SELECT COUNT(*) FROM messages",null)); result.put("hasMore", offset+groups.length()<total);
        result.put("providerAvailable",providerAvailable); result.put("updatedAt",System.currentTimeMillis()); return result;
    }
    private static int count(SQLiteDatabase db, String sql, String[] args) {
        Cursor cursor = db.rawQuery(sql, args);
        try { cursor.moveToFirst(); return cursor.getInt(0); } finally { cursor.close(); }
    }
    public synchronized int delete(java.util.List<Long> ids) {
        if (ids.isEmpty() || ids.size()>500) throw new IllegalArgumentException("Select 1 to 500 messages");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction(); int removed=0;
        try {
            for (Long id : ids) {
                if (id == null || id.longValue() <= 0) throw new IllegalArgumentException("Invalid message ID");
                Cursor cursor=db.query("messages",new String[]{"message_key"},"_id=?",new String[]{String.valueOf(id)},null,null,null);
                String key=null;
                try { if(cursor.moveToFirst())key=cursor.getString(0); } finally { cursor.close(); }
                if(key==null)continue;
                db.execSQL("INSERT OR IGNORE INTO deleted_messages(message_key) VALUES (?)",new Object[]{key});
                removed+=db.delete("messages","_id=?",new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return removed;
    }
}
