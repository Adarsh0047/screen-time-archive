package com.adarsh.screentimearchive;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class HistoryDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "screen_time_archive.db";
    private static final int DB_VERSION = 3;

    HistoryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createAppTable(db);
        db.execSQL("CREATE TABLE daily_usage (" +
                "day_start INTEGER PRIMARY KEY," +
                "duration_ms INTEGER NOT NULL," +
                "collected_at INTEGER NOT NULL," +
                "source TEXT NOT NULL DEFAULT 'automatic')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) createAppTable(db);
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE daily_usage ADD COLUMN source TEXT NOT NULL DEFAULT 'automatic'");
        }
    }

    /**
     * Stores a complete snapshot only when it is at least as large as the
     * saved value. Usage events can disappear from Android between scans; a
     * later incomplete query must never reduce history already preserved.
     */
    boolean saveSnapshotIfNotLower(long dayStart, long durationMs,
                                   java.util.Map<String, Long> apps) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Long existing = null;
            try (Cursor cursor = db.rawQuery(
                    "SELECT duration_ms FROM daily_usage WHERE day_start = ?",
                    new String[]{Long.toString(dayStart)})) {
                if (cursor.moveToFirst()) existing = cursor.getLong(0);
            }
            if (existing != null && durationMs < existing) return false;

            ContentValues values = new ContentValues();
            values.put("day_start", dayStart);
            values.put("duration_ms", durationMs);
            values.put("collected_at", System.currentTimeMillis());
            values.put("source", "automatic");
            db.insertWithOnConflict("daily_usage", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);

            db.delete("app_days", "day_start = ?",
                    new String[]{Long.toString(dayStart)});
            for (java.util.Map.Entry<String, Long> entry : apps.entrySet()) {
                ContentValues app = new ContentValues();
                app.put("day_start", dayStart);
                app.put("package", entry.getKey());
                app.put("duration", entry.getValue());
                db.insertOrThrow("app_days", null, app);
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private void createAppTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE app_days(day_start INTEGER NOT NULL, package TEXT NOT NULL, duration INTEGER NOT NULL, PRIMARY KEY(day_start,package))");
    }

    java.util.Map<String,Long> getApps(long day) {
        java.util.Map<String,Long> rows=new java.util.LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT package,duration FROM app_days WHERE day_start=? AND duration>0 ORDER BY duration DESC",new String[]{Long.toString(day)})) {
            while(c.moveToNext())rows.put(c.getString(0),c.getLong(1));
        }
        return rows;
    }

    boolean importDay(long dayStart, long durationMs) {
        ContentValues values = new ContentValues();
        values.put("day_start", dayStart);
        values.put("duration_ms", durationMs);
        values.put("collected_at", System.currentTimeMillis());
        values.put("source", "csv import");
        return getWritableDatabase().insertWithOnConflict(
                "daily_usage", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L;
    }

    int getDayCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM daily_usage", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    boolean hasDay(long dayStart) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM daily_usage WHERE day_start = ? LIMIT 1",
                new String[]{Long.toString(dayStart)})) {
            return cursor.moveToFirst();
        }
    }

    DayEntry getDay(long dayStart) {
        try (Cursor cursor = getReadableDatabase().query(
                "daily_usage", new String[]{"day_start", "duration_ms", "source"},
                "day_start = ?", new String[]{Long.toString(dayStart)},
                null, null, null)) {
            return cursor.moveToFirst()
                    ? new DayEntry(cursor.getLong(0), cursor.getLong(1), cursor.getString(2))
                    : null;
        }
    }

    List<DayEntry> getDaysSince(long since) {
        List<DayEntry> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "daily_usage",
                new String[]{"day_start", "duration_ms", "source"},
                "day_start >= ?",
                new String[]{Long.toString(since)},
                null, null,
                "day_start ASC")) {
            while (cursor.moveToNext()) {
                rows.add(new DayEntry(cursor.getLong(0), cursor.getLong(1), cursor.getString(2)));
            }
        }
        return rows;
    }

    static final class DayEntry {
        final long dayStart;
        final long durationMs;
        final String source;

        DayEntry(long dayStart, long durationMs, String source) {
            this.dayStart = dayStart;
            this.durationMs = durationMs;
            this.source = source;
        }
    }
}
