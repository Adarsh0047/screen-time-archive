package com.adarsh.screentimearchive;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;

import java.time.LocalDate;

final class ArchiveHealth {
    private static final String PREFS = "archive_health";
    private static final String ATTEMPT = "last_attempt";
    private static final String SUCCESS = "last_success";
    private static final String ERROR = "last_error";
    private static final String SAVED = "last_saved";
    private static final String PROTECTED = "last_protected";
    private static final String UNAVAILABLE = "last_unavailable";

    private ArchiveHealth() {}

    static void recordAttempt(Context context) {
        prefs(context).edit().putLong(ATTEMPT, System.currentTimeMillis()).apply();
    }

    static void recordSuccess(Context context, UsageRepository.ArchiveReport report) {
        prefs(context).edit()
                .putLong(SUCCESS, System.currentTimeMillis())
                .remove(ERROR)
                .putInt(SAVED, report.savedDays)
                .putInt(PROTECTED, report.protectedDays)
                .putInt(UNAVAILABLE, report.unavailableDays)
                .apply();
    }

    static void recordFailure(Context context, Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "Collection failed" : error.getMessage();
        prefs(context).edit().putString(ERROR, message).apply();
    }

    static long lastSuccess(Context context) {
        return prefs(context).getLong(SUCCESS, 0L);
    }

    static boolean isStale(Context context) {
        long success = lastSuccess(context);
        return success == 0L || System.currentTimeMillis() - success > 12L * 60L * 60L * 1000L;
    }

    static int recentGapCount(Context context) {
        int gaps = 0;
        try (HistoryDb db = new HistoryDb(context)) {
            LocalDate today = LocalDate.now();
            for (int offset = 1; offset <= 9; offset++) {
                if (!db.hasDay(UsageRepository.atStartOfDay(today.minusDays(offset)))) gaps++;
            }
        }
        return gaps;
    }

    static String summary(Context context) {
        SharedPreferences values = prefs(context);
        long success = values.getLong(SUCCESS, 0L);
        String when = success == 0L ? "Never completed"
                : DateUtils.getRelativeTimeSpanString(success, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        int gaps = recentGapCount(context);
        String error = values.getString(ERROR, null);
        StringBuilder result = new StringBuilder();
        result.append(isStale(context) ? "Needs attention" : "Healthy")
                .append(" · last success ").append(when)
                .append("\n")
                .append(gaps == 0 ? "No gaps in the previous 9 days"
                        : gaps + " missing day" + (gaps == 1 ? "" : "s") + " in the previous 9 days")
                .append("\nLast run: ")
                .append(values.getInt(SAVED, 0)).append(" saved, ")
                .append(values.getInt(PROTECTED, 0)).append(" protected, ")
                .append(values.getInt(UNAVAILABLE, 0)).append(" unavailable");
        if (error != null) result.append("\nLast error: ").append(error);
        return result.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
