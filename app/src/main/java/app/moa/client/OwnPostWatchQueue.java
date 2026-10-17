package app.moa.client;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.UUID;

/** Private durable outbox. It contains only verified publication URLs, never source credentials. */
public final class OwnPostWatchQueue {
    private static final Object LOCK = new Object();
    private static final long WATCH_WINDOW_MILLIS = 3 * 86_400_000L;
    private static volatile boolean storageError;
    public enum EnqueueResult { ADDED, ALREADY_QUEUED, NOT_ENABLED, INVALID, STORAGE_ERROR }

    static final class Entry {
        final String id, url;
        final long createdAt, retryAt;
        final int failures;
        Entry(String id, String url, long createdAt, long retryAt, int failures) {
            this.id = id; this.url = url; this.createdAt = createdAt; this.retryAt = retryAt; this.failures = failures;
        }
    }

    private OwnPostWatchQueue() { }

    /** Only call from the trusted, successful-new-publication callback after notification consent. */
    public static EnqueueResult enqueue(Context context, String value) {
        Context app = context.getApplicationContext();
        String url = CommunityStore.canonicalArticleUrl(value);
        if (url == null) return EnqueueResult.INVALID;
        EnqueueResult result;
        synchronized (LOCK) {
            if (!optedIn(app)) return EnqueueResult.NOT_ENABLED;
            if (clearRequired(app)) clear(app);
            if (clearRequired(app)) return EnqueueResult.STORAGE_ERROR;
            try (Database helper = new Database(app)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                try (Cursor found = db.rawQuery("SELECT id FROM pending_posts WHERE url = ?", new String[]{url})) {
                    if (found.moveToFirst()) return EnqueueResult.ALREADY_QUEUED;
                }
                ContentValues row = new ContentValues();
                row.put("id", UUID.randomUUID().toString()); row.put("url", url);
                row.put("created_at", System.currentTimeMillis()); row.put("retry_at", 0); row.put("failures", 0);
                db.insertOrThrow("pending_posts", null, row);
                storageError = false; result = EnqueueResult.ADDED;
            } catch (SQLiteException unavailable) { storageError = true; return EnqueueResult.STORAGE_ERROR; }
        }
        PushRegistrationJobService.schedule(app, 0);
        return result;
    }

    public static long pendingCount(Context context) {
        synchronized (LOCK) {
            if (!enabled(context)) return 0;
            try (Database helper = new Database(context)) {
                SQLiteDatabase db = helper.getWritableDatabase(); discardExpired(db);
                try (Cursor cursor = db.rawQuery("SELECT count(*) FROM pending_posts", null)) {
                    storageError = false; return cursor.moveToFirst() ? cursor.getLong(0) : 0;
                }
            } catch (SQLiteException unavailable) { storageError = true; return 0; }
        }
    }

    public static boolean hasStorageError() { return storageError; }

    public static void clear(Context context) {
        synchronized (LOCK) {
            // If the database cannot be cleared (for example storage exhaustion),
            // old entries must remain blocked after an off/on or process restart.
            if (!gate(context).edit().putBoolean("clear_required", true).commit()) { storageError = true; return; }
            try (Database helper = new Database(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransaction();
                try { db.delete("pending_posts", null, null); db.delete("queue_state", null, null); db.setTransactionSuccessful(); }
                finally { db.endTransaction(); }
                storageError = !gate(context).edit().remove("clear_required").commit();
            } catch (SQLiteException unavailable) { storageError = true; }
        }
    }

    static boolean hasPending(Context context) { return pendingCount(context) > 0; }

    static long delayMillis(Context context) {
        synchronized (LOCK) {
            if (!enabled(context)) return 0;
            try (Database helper = new Database(context)) {
                SQLiteDatabase db = helper.getWritableDatabase(); discardExpired(db);
                try (Cursor cursor = db.rawQuery("SELECT MIN(retry_at) FROM pending_posts", null)) {
                    if (!cursor.moveToFirst() || cursor.isNull(0)) return 0;
                    long ready = Math.max(cursor.getLong(0), pauseUntil(db));
                    return Math.max(0, Math.min(86_400_000, ready - System.currentTimeMillis()));
                }
            } catch (SQLiteException unavailable) { storageError = true; return 60_000; }
        }
    }

    static Entry nextDue(Context context) {
        synchronized (LOCK) {
            if (!enabled(context)) return null;
            try (Database helper = new Database(context)) {
                SQLiteDatabase db = helper.getWritableDatabase(); discardExpired(db); long now = System.currentTimeMillis();
                if (pauseUntil(db) > now) return null;
                try (Cursor cursor = db.rawQuery("SELECT id,url,created_at,retry_at,failures FROM pending_posts "
                        + "WHERE retry_at <= ? ORDER BY retry_at,created_at,id LIMIT 1", new String[]{Long.toString(now)})) {
                    return cursor.moveToFirst() ? new Entry(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                            cursor.getLong(3), cursor.getInt(4)) : null;
                }
            } catch (SQLiteException unavailable) { storageError = true; return null; }
        }
    }

    static boolean current(Context context, Entry expected) {
        synchronized (LOCK) {
            if (!enabled(context)) return false;
            try (Database helper = new Database(context);
                 Cursor cursor = helper.getReadableDatabase().rawQuery("SELECT id FROM pending_posts WHERE id = ? AND url = ?",
                         new String[]{expected.id, expected.url})) { return cursor.moveToFirst(); }
            catch (SQLiteException unavailable) { storageError = true; return false; }
        }
    }

    static boolean complete(Context context, Entry expected) {
        synchronized (LOCK) {
            if (!enabled(context)) return false;
            try (Database helper = new Database(context)) {
                return helper.getWritableDatabase().delete("pending_posts", "id = ? AND url = ?",
                        new String[]{expected.id, expected.url}) == 1;
            } catch (SQLiteException unavailable) { storageError = true; return false; }
        }
    }

    static long retry(Context context, Entry expected, long requestedDelay) {
        synchronized (LOCK) {
            if (!enabled(context)) return 0;
            long backoff = 30_000L << Math.min(expected.failures, 9);
            long delay = Math.min(86_400_000, Math.max(requestedDelay, backoff));
            try (Database helper = new Database(context)) {
                ContentValues row = new ContentValues(); row.put("retry_at", System.currentTimeMillis() + delay);
                row.put("failures", Math.min(expected.failures + 1, 16));
                return helper.getWritableDatabase().update("pending_posts", row, "id = ? AND url = ?",
                        new String[]{expected.id, expected.url}) == 1 ? delay : 0;
            } catch (SQLiteException unavailable) { storageError = true; return 60_000; }
        }
    }

    static void pause(Context context, long delayMillis) {
        synchronized (LOCK) {
            if (!enabled(context)) return;
            try (Database helper = new Database(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                ContentValues row = new ContentValues(); row.put("name", "pause_until");
                row.put("value", Math.max(pauseUntil(db), System.currentTimeMillis() + Math.min(86_400_000, Math.max(0, delayMillis))));
                db.insertWithOnConflict("queue_state", null, row, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (SQLiteException unavailable) { storageError = true; }
        }
    }

    private static long pauseUntil(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT value FROM queue_state WHERE name = 'pause_until'", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private static void discardExpired(SQLiteDatabase db) {
        db.delete("pending_posts", "created_at <= ?", new String[]{Long.toString(System.currentTimeMillis() - WATCH_WINDOW_MILLIS)});
    }

    private static boolean enabled(Context context) {
        return optedIn(context) && !clearRequired(context);
    }

    private static boolean optedIn(Context context) {
        return PushClient.isEnabled(context) && CommunityOptions.autoWatchOwnPosts(context);
    }

    private static SharedPreferences gate(Context context) {
        return context.getApplicationContext().getSharedPreferences("moa_own_post_outbox_gate", Context.MODE_PRIVATE);
    }

    private static boolean clearRequired(Context context) {
        try {
            boolean blocked = gate(context).getBoolean("clear_required", false);
            if (blocked) storageError = true;
            return blocked;
        } catch (ClassCastException malformed) { storageError = true; return true; }
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context.getApplicationContext(), "moa_own_post_outbox.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE pending_posts(id TEXT PRIMARY KEY,url TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,"
                    + "retry_at INTEGER NOT NULL,failures INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX own_post_ready ON pending_posts(retry_at,created_at,id)");
            db.execSQL("CREATE INDEX own_post_expiry ON pending_posts(created_at)");
            db.execSQL("CREATE TABLE queue_state(name TEXT PRIMARY KEY,value INTEGER NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    }
}
