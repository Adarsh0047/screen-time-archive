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
    private static final int DB_VERSION = 1;

    HistoryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily_usage (" +
                "day_start INTEGER PRIMARY KEY," +
                "duration_ms INTEGER NOT NULL," +
                "collected_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 has no migrations.
    }

    void upsert(long dayStart, long durationMs) {
        ContentValues values = new ContentValues();
        values.put("day_start", dayStart);
        values.put("duration_ms", durationMs);
        values.put("collected_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "daily_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    List<DayEntry> getDaysSince(long since) {
        List<DayEntry> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "daily_usage",
                new String[]{"day_start", "duration_ms"},
                "day_start >= ?",
                new String[]{Long.toString(since)},
                null, null,
                "day_start ASC")) {
            while (cursor.moveToNext()) {
                rows.add(new DayEntry(cursor.getLong(0), cursor.getLong(1)));
            }
        }
        return rows;
    }

    static final class DayEntry {
        final long dayStart;
        final long durationMs;

        DayEntry(long dayStart, long durationMs) {
            this.dayStart = dayStart;
            this.durationMs = durationMs;
        }
    }
}
