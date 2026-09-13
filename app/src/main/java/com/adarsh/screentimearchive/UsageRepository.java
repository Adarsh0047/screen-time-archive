package com.adarsh.screentimearchive;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class UsageRepository {
    private static final int DAILY_BACKFILL_DAYS = 10;

    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final HistoryDb database;
    private final Set<String> homePackages;
    private final Set<String> countedPackages;

    UsageRepository(Context context) {
        this.context = context.getApplicationContext();
        this.usageStatsManager = (UsageStatsManager)
                this.context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.database = new HistoryDb(this.context);
        this.homePackages = findPackagesForCategory(Intent.CATEGORY_HOME);
        this.countedPackages = findPackagesForCategory(Intent.CATEGORY_LAUNCHER);
        this.countedPackages.removeAll(homePackages);
        this.countedPackages.remove(this.context.getPackageName());
        this.countedPackages.remove("com.android.systemui");
    }

    ScanResult scan(long purchaseDateMillis) {
        archiveRecentDays();

        ZoneId zone = ZoneId.systemDefault();
        LocalDate purchaseDate = Instant.ofEpochMilli(purchaseDateMillis).atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(zone);
        List<PeriodUsage> years = new ArrayList<>();
        List<PeriodUsage> months = new ArrayList<>();
        long lifetimeEstimate = 0L;
        Long oldestAvailable = null;

        for (int year = purchaseDate.getYear(); year <= today.getYear(); year++) {
            LocalDate yearStartDate = LocalDate.of(year, 1, 1);
            LocalDate yearEndDate = yearStartDate.plusYears(1);
            long start = Math.max(purchaseDateMillis, atStartOfDay(yearStartDate));
            long end = Math.min(System.currentTimeMillis(), atStartOfDay(yearEndDate));
            if (end <= start) continue;

            long duration = queryFilteredAggregate(UsageStatsManager.INTERVAL_YEARLY, start, end);
            years.add(new PeriodUsage(Integer.toString(year), start, end, duration,
                    "Filtered Android yearly summary"));
            lifetimeEstimate += duration;
            if (duration > 0 && oldestAvailable == null) oldestAvailable = start;
        }

        LocalDate firstMonth = today.withDayOfMonth(1).minusMonths(5);
        if (purchaseDate.withDayOfMonth(1).isAfter(firstMonth)) {
            firstMonth = purchaseDate.withDayOfMonth(1);
        }
        for (LocalDate month = firstMonth; !month.isAfter(today); month = month.plusMonths(1)) {
            long start = Math.max(purchaseDateMillis, atStartOfDay(month));
            long end = Math.min(System.currentTimeMillis(), atStartOfDay(month.plusMonths(1)));
            if (end <= start) continue;
            long duration = queryFilteredAggregate(UsageStatsManager.INTERVAL_MONTHLY, start, end);
            String label = month.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()));
            months.add(new PeriodUsage(label, start, end, duration,
                    "Filtered Android monthly summary"));
        }

        List<HistoryDb.DayEntry> archivedDays = database.getDaysSince(atStartOfDay(purchaseDate));
        return new ScanResult(purchaseDateMillis, System.currentTimeMillis(), lifetimeEstimate,
                oldestAvailable, years, months, archivedDays);
    }

    void archiveRecentDays() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        for (int offset = DAILY_BACKFILL_DAYS - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            long start = atStartOfDay(day);
            long end = offset == 0 ? System.currentTimeMillis() : atStartOfDay(day.plusDays(1));
            long duration = queryEventDerivedScreenTime(start, end);
            database.upsert(start, duration);
        }
    }

    int importCsv(java.io.InputStream input) throws java.io.IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        int imported = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            String[] columns = line.split(",", -1);
            if (columns.length < 4 || !"day".equalsIgnoreCase(columns[0].trim())) continue;
            try {
                LocalDate date = LocalDate.parse(columns[1].trim());
                long minutes = Long.parseLong(columns[3].trim());
                if (minutes >= 0 && minutes <= 24L * 60L
                        && database.importDay(atStartOfDay(date), minutes * 60_000L)) {
                    imported++;
                }
            } catch (RuntimeException ignored) {
                // Skip malformed CSV rows while preserving all valid rows.
            }
        }
        return imported;
    }

    int archivedDayCount() {
        return database.getDayCount();
    }

    private long queryFilteredAggregate(int interval, long start, long end) {
        if (usageStatsManager == null || end <= start) return 0L;
        List<UsageStats> stats = usageStatsManager.queryUsageStats(interval, start, end);
        if (stats == null) return 0L;

        long total = 0L;
        for (UsageStats usage : stats) {
            if (countedPackages.contains(usage.getPackageName())) {
                total += Math.max(0L, usage.getTotalTimeInForeground());
            }
        }
        return total;
    }

    /**
     * Calculates the union of visible, launchable app sessions. A union is used
     * so split-screen activities and duplicate OEM lifecycle events cannot make
     * a day longer than the elapsed wall-clock period.
     */
    private long queryEventDerivedScreenTime(long start, long end) {
        if (usageStatsManager == null || end <= start) return 0L;
        long lookbackStart = Math.max(0L, start - 24L * 60L * 60L * 1000L);
        UsageEvents events = usageStatsManager.queryEvents(lookbackStart, end);
        if (events == null) return 0L;

        Set<String> activeActivities = new HashSet<>();
        UsageEvents.Event event = new UsageEvents.Event();
        long cursor = lookbackStart;
        long total = 0L;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            long timestamp = Math.max(cursor, Math.min(end, event.getTimeStamp()));
            if (!activeActivities.isEmpty()) {
                total += overlapMillis(cursor, timestamp, start, end);
            }

            String packageName = event.getPackageName();
            int type = event.getEventType();
            if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || type == UsageEvents.Event.KEYGUARD_SHOWN) {
                activeActivities.clear();
            } else if (packageName != null && homePackages.contains(packageName)
                    && type == UsageEvents.Event.ACTIVITY_RESUMED) {
                activeActivities.clear();
            } else if (packageName != null && countedPackages.contains(packageName)) {
                String className = event.getClassName() == null ? "" : event.getClassName();
                String activityKey = packageName + "/" + className;
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    activeActivities.add(activityKey);
                } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                        || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    activeActivities.remove(activityKey);
                }
            }
            cursor = timestamp;
        }

        if (!activeActivities.isEmpty()) {
            total += overlapMillis(cursor, end, start, end);
        }
        return Math.min(Math.max(0L, total), end - start);
    }

    private static long overlapMillis(long intervalStart, long intervalEnd,
                                      long windowStart, long windowEnd) {
        long clippedStart = Math.max(intervalStart, windowStart);
        long clippedEnd = Math.min(intervalEnd, windowEnd);
        return Math.max(0L, clippedEnd - clippedStart);
    }

    private Set<String> findPackagesForCategory(String category) {
        Set<String> packages = new HashSet<>();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> matches = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
        for (ResolveInfo match : matches) {
            if (match.activityInfo != null && match.activityInfo.packageName != null) {
                packages.add(match.activityInfo.packageName);
            }
        }
        return packages;
    }

    static long atStartOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static String formatDuration(long millis) {
        long totalMinutes = Math.max(0L, millis) / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours + "h " + minutes + "m";
    }

    static String formatDate(long millis) {
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(millis);
    }

    static final class PeriodUsage {
        final String label;
        final long start;
        final long end;
        final long durationMs;
        final String source;

        PeriodUsage(String label, long start, long end, long durationMs, String source) {
            this.label = label;
            this.start = start;
            this.end = end;
            this.durationMs = durationMs;
            this.source = source;
        }
    }

    static final class ScanResult {
        final long purchaseDate;
        final long scannedAt;
        final long lifetimeEstimateMs;
        final Long oldestAvailable;
        final List<PeriodUsage> years;
        final List<PeriodUsage> months;
        final List<HistoryDb.DayEntry> archivedDays;

        ScanResult(long purchaseDate, long scannedAt, long lifetimeEstimateMs,
                   Long oldestAvailable, List<PeriodUsage> years, List<PeriodUsage> months,
                   List<HistoryDb.DayEntry> archivedDays) {
            this.purchaseDate = purchaseDate;
            this.scannedAt = scannedAt;
            this.lifetimeEstimateMs = lifetimeEstimateMs;
            this.oldestAvailable = oldestAvailable;
            this.years = Collections.unmodifiableList(years);
            this.months = Collections.unmodifiableList(months);
            this.archivedDays = Collections.unmodifiableList(archivedDays);
        }

        String toCsv() {
            StringBuilder csv = new StringBuilder();
            csv.append("granularity,period_start,period_end,foreground_time_minutes,source\n");
            DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
            ZoneId zone = ZoneId.systemDefault();
            for (PeriodUsage year : years) {
                csv.append("year,")
                        .append(Instant.ofEpochMilli(year.start).atZone(zone).toLocalDate().format(iso)).append(',')
                        .append(Instant.ofEpochMilli(year.end).atZone(zone).toLocalDate().format(iso)).append(',')
                        .append(year.durationMs / 60_000L).append(',')
                        .append(year.source).append('\n');
            }
            for (PeriodUsage month : months) {
                csv.append("month,")
                        .append(Instant.ofEpochMilli(month.start).atZone(zone).toLocalDate().format(iso)).append(',')
                        .append(Instant.ofEpochMilli(month.end).atZone(zone).toLocalDate().format(iso)).append(',')
                        .append(month.durationMs / 60_000L).append(',')
                        .append(month.source).append('\n');
            }
            for (HistoryDb.DayEntry day : archivedDays) {
                LocalDate date = Instant.ofEpochMilli(day.dayStart).atZone(zone).toLocalDate();
                csv.append("day,")
                        .append(date.format(iso)).append(',')
                        .append(date.plusDays(1).format(iso)).append(',')
                        .append(day.durationMs / 60_000L).append(',')
                        .append(day.source).append('\n');
            }
            return csv.toString();
        }
    }
}
