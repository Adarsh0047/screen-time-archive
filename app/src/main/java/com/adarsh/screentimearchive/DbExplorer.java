package com.adarsh.screentimearchive;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Small, deliberately read-only window into Daytrace's private archive. */
final class DbExplorer implements AutoCloseable {
    private static final int QUERY_LIMIT = 200;
    private final HistoryDb history;

    DbExplorer(Context context) {
        history = new HistoryDb(context);
    }

    List<String> tables() {
        List<String> names = new ArrayList<>();
        try (Cursor cursor = history.getReadableDatabase().rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                        "AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
            while (cursor.moveToNext()) names.add(cursor.getString(0));
        }
        return names;
    }

    long rowCount(String table) {
        requireTable(table);
        try (Cursor cursor = history.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + quote(table), null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    QueryResult schema(String table) {
        requireTable(table);
        return readCursor(history.getReadableDatabase().rawQuery(
                "PRAGMA table_info(" + quote(table) + ")", null), QUERY_LIMIT);
    }

    QueryResult rows(String table, int offset, int limit) {
        requireTable(table);
        int safeLimit = Math.max(1, Math.min(limit, QUERY_LIMIT));
        int safeOffset = Math.max(0, offset);
        return readCursor(history.getReadableDatabase().rawQuery(
                "SELECT * FROM " + quote(table) + " ORDER BY rowid DESC LIMIT ? OFFSET ?",
                new String[]{Integer.toString(safeLimit), Integer.toString(safeOffset)}), safeLimit);
    }

    QueryResult query(String sql) {
        String statement = normalise(sql);
        enforceReadOnly(statement);
        SQLiteDatabase db = history.getReadableDatabase();
        db.execSQL("PRAGMA query_only = ON");
        try {
            return readCursor(db.rawQuery(statement, null), QUERY_LIMIT);
        } finally {
            db.execSQL("PRAGMA query_only = OFF");
        }
    }

    private void requireTable(String table) {
        if (!tables().contains(table)) throw new IllegalArgumentException("Unknown table");
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String normalise(String sql) {
        if (sql == null) throw new IllegalArgumentException("Enter a query first");
        String value = sql.trim();
        while (value.startsWith("--") || value.startsWith("/*")) {
            if (value.startsWith("--")) {
                int end = value.indexOf('\n');
                value = end < 0 ? "" : value.substring(end + 1).trim();
            } else {
                int end = value.indexOf("*/", 2);
                if (end < 0) throw new IllegalArgumentException("Unclosed SQL comment");
                value = value.substring(end + 2).trim();
            }
        }
        if (value.isEmpty()) throw new IllegalArgumentException("Enter a query first");
        int semicolon = value.indexOf(';');
        if (semicolon >= 0 && !value.substring(semicolon + 1).trim().isEmpty()) {
            throw new IllegalArgumentException("Only one statement can be run at a time");
        }
        return value;
    }

    private static void enforceReadOnly(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        boolean read = upper.matches("(?s)^(SELECT|WITH|EXPLAIN)(\\s|$).*");
        if (upper.startsWith("PRAGMA ")) {
            String pragma = upper.substring(7).trim();
            if (pragma.endsWith(";")) pragma=pragma.substring(0,pragma.length()-1).trim();
            boolean introspection = !pragma.contains("=") && Arrays.stream(new String[]{
                    "TABLE_INFO", "TABLE_XINFO", "INDEX_LIST", "INDEX_INFO",
                    "INDEX_XINFO", "FOREIGN_KEY_LIST"
            }).anyMatch(pragma::startsWith);
            boolean scalar = Arrays.asList("DATABASE_LIST", "COMPILE_OPTIONS",
                    "USER_VERSION", "SCHEMA_VERSION").contains(pragma);
            read = introspection || scalar;
        }
        if (!read) {
            throw new IllegalArgumentException(
                    "Read-only explorer: use SELECT, WITH, EXPLAIN, or a safe PRAGMA");
        }
    }

    private static QueryResult readCursor(Cursor cursor, int limit) {
        try (Cursor closeable = cursor) {
            List<String> columns = Arrays.asList(closeable.getColumnNames());
            List<List<String>> rows = new ArrayList<>();
            boolean truncated = false;
            while (closeable.moveToNext()) {
                if (rows.size() >= limit) { truncated = true; break; }
                List<String> row = new ArrayList<>();
                for (int i = 0; i < closeable.getColumnCount(); i++) {
                    switch (closeable.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: row.add("NULL"); break;
                        case Cursor.FIELD_TYPE_BLOB:
                            byte[] blob = closeable.getBlob(i);
                            row.add("<BLOB " + (blob == null ? 0 : blob.length) + " bytes>");
                            break;
                        default: row.add(closeable.getString(i));
                    }
                }
                rows.add(row);
            }
            return new QueryResult(columns, rows, truncated);
        }
    }

    @Override public void close() {
        history.close();
    }

    static final class QueryResult {
        final List<String> columns;
        final List<List<String>> rows;
        final boolean truncated;

        QueryResult(List<String> columns, List<List<String>> rows, boolean truncated) {
            this.columns = columns;
            this.rows = rows;
            this.truncated = truncated;
        }
    }
}
