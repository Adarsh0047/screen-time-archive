package com.adarsh.screentimearchive;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "screen_time_settings";
    private static final String KEY_PURCHASE_DATE = "purchase_date";
    private static final int EXPORT_REQUEST = 1001, IMPORT_REQUEST = 1002;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private View overviewScreen, historyScreen, insightsScreen, dataScreen;
    private LinearLayout recentRows;
    private Button overviewTab, historyTab, insightsTab, dataTab, permissionButton, dateButton, exportButton;
    private TextView permissionTitle, permissionBody, todayValue, todayCaption, weekAverage, weekChange;
    private TextView historySummary, lifetimeValue, lifetimeCaption, insightWeekday, insightHighest;
    private TextView insightTrend, insightStreak, archiveStatus, refreshStatus;
    private ProgressBar progress;
    private TrendChartView weekChart, historyChart;
    private long purchaseDateMillis;
    private boolean refreshRunning;
    private UsageRepository.ScanResult lastResult;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_main); bindViews();
        purchaseDateMillis = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_PURCHASE_DATE, 0L);
        if (purchaseDateMillis == 0L) {
            purchaseDateMillis = UsageRepository.atStartOfDay(LocalDate.of(2024, 1, 1));
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_PURCHASE_DATE, purchaseDateMillis).apply();
        }
        updateDateButton(); showScreen(overviewScreen, overviewTab);
        overviewTab.setOnClickListener(v -> showScreen(overviewScreen, overviewTab));
        historyTab.setOnClickListener(v -> showScreen(historyScreen, historyTab));
        insightsTab.setOnClickListener(v -> showScreen(insightsScreen, insightsTab));
        dataTab.setOnClickListener(v -> showScreen(dataScreen, dataTab));
        permissionButton.setOnClickListener(v -> openUsageAccessSettings());
        dateButton.setOnClickListener(v -> choosePurchaseDate());
        exportButton.setOnClickListener(v -> beginExport());
        findViewById(R.id.importButton).setOnClickListener(v -> beginImport());
        ArchiveScheduler.schedule(this); updatePermissionUi(); automaticRefresh();
    }

    private void bindViews() {
        overviewScreen = findViewById(R.id.overviewScreen); historyScreen = findViewById(R.id.historyScreen);
        insightsScreen = findViewById(R.id.insightsScreen); dataScreen = findViewById(R.id.dataScreen);
        overviewTab = findViewById(R.id.overviewTab); historyTab = findViewById(R.id.historyTab);
        insightsTab = findViewById(R.id.insightsTab); dataTab = findViewById(R.id.dataTab);
        permissionTitle = findViewById(R.id.permissionTitle); permissionBody = findViewById(R.id.permissionBody);
        permissionButton = findViewById(R.id.permissionButton); dateButton = findViewById(R.id.dateButton);
        exportButton = findViewById(R.id.exportButton); progress = findViewById(R.id.progress);
        todayValue = findViewById(R.id.todayValue); todayCaption = findViewById(R.id.todayCaption);
        weekAverage = findViewById(R.id.weekAverage); weekChange = findViewById(R.id.weekChange);
        historySummary = findViewById(R.id.historySummary); lifetimeValue = findViewById(R.id.lifetimeValue);
        lifetimeCaption = findViewById(R.id.lifetimeCaption); insightWeekday = findViewById(R.id.insightWeekday);
        insightHighest = findViewById(R.id.insightHighest); insightTrend = findViewById(R.id.insightTrend);
        insightStreak = findViewById(R.id.insightStreak); archiveStatus = findViewById(R.id.archiveStatus);
        refreshStatus = findViewById(R.id.refreshStatus); recentRows = findViewById(R.id.recentRows);
        weekChart = findViewById(R.id.weekChart); historyChart = findViewById(R.id.historyChart);
    }

    @Override protected void onResume() {
        super.onResume(); updatePermissionUi();
        if (UsagePermission.isGranted(this)) { ArchiveScheduler.schedule(this); automaticRefresh(); }
    }

    private void automaticRefresh() {
        if (refreshRunning || !UsagePermission.isGranted(this)) return;
        refreshRunning = true; progress.setVisibility(View.VISIBLE); refreshStatus.setText("Updating automatically…");
        long start = purchaseDateMillis;
        executor.execute(() -> {
            try {
                UsageRepository.ScanResult result = new UsageRepository(this).scan(start);
                runOnUiThread(() -> display(result));
            } catch (Exception error) {
                runOnUiThread(() -> { refreshRunning = false; progress.setVisibility(View.GONE);
                    refreshStatus.setText("Automatic update could not finish. Check usage access and battery settings."); });
            }
        });
    }

    private void display(UsageRepository.ScanResult result) {
        lastResult = result; refreshRunning = false; progress.setVisibility(View.GONE);
        List<HistoryDb.DayEntry> days = result.archivedDays;
        long todayMs = days.isEmpty() ? 0L : days.get(days.size() - 1).durationMs;
        todayValue.setText(UsageRepository.formatDuration(todayMs));
        todayCaption.setText("Today so far · updated automatically");
        long currentWeek = sumTail(days, 7, 0), previousWeek = sumTail(days, 7, 7);
        int count = Math.min(7, days.size());
        weekAverage.setText(count == 0 ? "—" : UsageRepository.formatDuration(currentWeek / count));
        weekChange.setText(comparison(currentWeek, previousWeek));
        weekChart.setDays(days, 7); historyChart.setDays(days, 30);
        lifetimeValue.setText(UsageRepository.formatDuration(result.lifetimeEstimateMs));
        lifetimeCaption.setText("Estimated foreground time since " + UsageRepository.formatDate(result.purchaseDate));
        historySummary.setText(days.size() + " daily records preserved on this phone");
        buildRecentRows(days); buildInsights(days, currentWeek, previousWeek);
        archiveStatus.setText("Automatic archive: " + (ArchiveScheduler.isScheduled(this) ? "active" : "waiting")
                + "\n" + days.size() + " daily records stored privately");
        refreshStatus.setText("Last automatic update: just now"); exportButton.setEnabled(true);
    }

    private void buildRecentRows(List<HistoryDb.DayEntry> days) {
        recentRows.removeAllViews(); int first = Math.max(0, days.size() - 14);
        for (int i = days.size() - 1; i >= first; i--) {
            HistoryDb.DayEntry day = days.get(i); TextView row = new TextView(this);
            row.setText(UsageRepository.formatDate(day.dayStart) + "\n" + UsageRepository.formatDuration(day.durationMs));
            row.setTextColor(getColor(R.color.text_primary)); row.setTextSize(16f); row.setPadding(0, dp(12), 0, dp(12));
            recentRows.addView(row);
        }
    }

    private void buildInsights(List<HistoryDb.DayEntry> days, long currentWeek, long previousWeek) {
        if (days.isEmpty()) return;
        HistoryDb.DayEntry highest = days.get(0); long[] totals = new long[7]; int[] counts = new int[7];
        for (HistoryDb.DayEntry day : days) {
            if (day.durationMs > highest.durationMs) highest = day;
            int index = Instant.ofEpochMilli(day.dayStart).atZone(ZoneId.systemDefault()).getDayOfWeek().getValue() - 1;
            totals[index] += day.durationMs; counts[index]++;
        }
        int maxDay = 0;
        for (int i = 1; i < 7; i++) if (average(totals, counts, i) > average(totals, counts, maxDay)) maxDay = i;
        String weekday = DayOfWeek.of(maxDay + 1).toString();
        insightWeekday.setText(weekday.substring(0, 1) + weekday.substring(1).toLowerCase());
        insightHighest.setText(UsageRepository.formatDuration(highest.durationMs) + "\n" + UsageRepository.formatDate(highest.dayStart));
        insightTrend.setText(comparison(currentWeek, previousWeek));
        int streak = 0;
        for (int i = days.size() - 1; i >= 0 && days.get(i).durationMs < 6L * 60L * 60L * 1000L; i--) streak++;
        insightStreak.setText(streak + (streak == 1 ? " day" : " days") + " under 6h");
    }

    private long average(long[] totals, int[] counts, int i) { return counts[i] == 0 ? 0 : totals[i] / counts[i]; }
    private long sumTail(List<HistoryDb.DayEntry> days, int count, int skip) {
        long sum = 0; int end = Math.max(0, days.size() - skip);
        for (int i = Math.max(0, end - count); i < end; i++) sum += days.get(i).durationMs;
        return sum;
    }
    private String comparison(long current, long previous) {
        if (previous <= 0) return "Not enough earlier data";
        long percent = Math.round(Math.abs(current - previous) * 100.0 / previous);
        return percent + "% " + (current <= previous ? "lower" : "higher") + " than previous 7 days";
    }

    private void showScreen(View selected, Button selectedTab) {
        View[] screens = {overviewScreen, historyScreen, insightsScreen, dataScreen};
        Button[] tabs = {overviewTab, historyTab, insightsTab, dataTab};
        for (View screen : screens) screen.setVisibility(screen == selected ? View.VISIBLE : View.GONE);
        for (Button tab : tabs) tab.setTextColor(getColor(tab == selectedTab ? R.color.accent : R.color.text_secondary));
    }

    private void updatePermissionUi() {
        boolean granted = UsagePermission.isGranted(this);
        permissionTitle.setText(granted ? "Usage access granted" : getString(R.string.usage_access_required));
        permissionBody.setText(granted ? "Automatic daily archiving is enabled. Your data stays on this phone."
                : getString(R.string.usage_access_explanation));
        permissionButton.setVisibility(granted ? View.GONE : View.VISIBLE);
    }
    private void openUsageAccessSettings() {
        try { Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName())); startActivity(intent);
        } catch (Exception ignored) { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
    }
    private void choosePurchaseDate() {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(purchaseDateMillis);
        DatePickerDialog dialog = new DatePickerDialog(this, (picker, year, month, day) -> {
            purchaseDateMillis = UsageRepository.atStartOfDay(LocalDate.of(year, month + 1, day));
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_PURCHASE_DATE, purchaseDateMillis).apply();
            updateDateButton(); automaticRefresh();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis()); dialog.show();
    }
    private void updateDateButton() { dateButton.setText(UsageRepository.formatDate(purchaseDateMillis)); }
    private void beginImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*");
        startActivityForResult(intent, IMPORT_REQUEST);
    }
    private void beginExport() {
        if (lastResult == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "screen-time-history.csv"); startActivityForResult(intent, EXPORT_REQUEST);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == EXPORT_REQUEST && lastResult != null) {
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new IllegalStateException();
                output.write(lastResult.toCsv().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show();
            } catch (Exception error) { Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show(); }
        } else if (requestCode == IMPORT_REQUEST) {
            Uri uri = data.getData(); executor.execute(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException(); int imported = new UsageRepository(this).importCsv(input);
                    runOnUiThread(() -> { Toast.makeText(this, imported + " new daily records imported", Toast.LENGTH_LONG).show(); automaticRefresh(); });
                } catch (Exception error) { runOnUiThread(() -> Toast.makeText(this, "Import failed: check the CSV format", Toast.LENGTH_LONG).show()); }
            });
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
