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

    void upsert(long dayStart, long durationMs) {
        ContentValues values = new ContentValues();
        values.put("day_start", dayStart);
        values.put("duration_ms", durationMs);
        values.put("collected_at", System.currentTimeMillis());
        values.put("source", "automatic");
        getWritableDatabase().insertWithOnConflict(
                "daily_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void createAppTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE app_days(day_start INTEGER NOT NULL, package TEXT NOT NULL, duration INTEGER NOT NULL, PRIMARY KEY(day_start,package))");
    }

    void saveApps(long day, java.util.Map<String,Long> totals) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("app_days","day_start = ?",new String[]{Long.toString(day)});
            for(java.util.Map.Entry<String,Long> e:totals.entrySet()) {
                ContentValues v=new ContentValues();v.put("day_start",day);v.put("package",e.getKey());v.put("duration",e.getValue());
                db.insertOrThrow("app_days",null,v);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
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
