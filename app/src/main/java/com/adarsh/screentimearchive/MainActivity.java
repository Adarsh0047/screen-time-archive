package com.adarsh.screentimearchive;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "screen_time_settings";
    private static final String KEY_PURCHASE_DATE = "purchase_date";
    private static final int EXPORT_REQUEST = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView permissionTitle;
    private TextView permissionBody;
    private Button permissionButton;
    private Button dateButton;
    private Button scanButton;
    private Button exportButton;
    private ProgressBar progress;
    private TextView resultHeadline;
    private TextView resultDetails;
    private LinearLayout historyRows;

    private long purchaseDateMillis;
    private UsageRepository.ScanResult lastResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionTitle = findViewById(R.id.permissionTitle);
        permissionBody = findViewById(R.id.permissionBody);
        permissionButton = findViewById(R.id.permissionButton);
        dateButton = findViewById(R.id.dateButton);
        scanButton = findViewById(R.id.scanButton);
        exportButton = findViewById(R.id.exportButton);
        progress = findViewById(R.id.progress);
        resultHeadline = findViewById(R.id.resultHeadline);
        resultDetails = findViewById(R.id.resultDetails);
        historyRows = findViewById(R.id.historyRows);

        purchaseDateMillis = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_PURCHASE_DATE, 0L);
        updateDateButton();
        updatePermissionUi();

        permissionButton.setOnClickListener(v -> openUsageAccessSettings());
        dateButton.setOnClickListener(v -> choosePurchaseDate());
        scanButton.setOnClickListener(v -> scan());
        exportButton.setOnClickListener(v -> beginExport());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionUi();
    }

    private void openUsageAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception firstFailure) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private void updatePermissionUi() {
        boolean granted = UsagePermission.isGranted(this);
        if (granted) {
            permissionTitle.setText("Usage access granted");
            permissionBody.setText("Your usage stays on this device. You can revoke access in Settings at any time.");
            permissionButton.setVisibility(View.GONE);
        } else {
            permissionTitle.setText(R.string.usage_access_required);
            permissionBody.setText(R.string.usage_access_explanation);
            permissionButton.setVisibility(View.VISIBLE);
        }
        scanButton.setEnabled(granted);
    }

    private void choosePurchaseDate() {
        Calendar calendar = Calendar.getInstance();
        if (purchaseDateMillis > 0L) calendar.setTimeInMillis(purchaseDateMillis);

        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (picker, year, month, day) -> {
                    LocalDate selected = LocalDate.of(year, month + 1, day);
                    purchaseDateMillis = UsageRepository.atStartOfDay(selected);
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putLong(KEY_PURCHASE_DATE, purchaseDateMillis).apply();
                    updateDateButton();
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void updateDateButton() {
        dateButton.setText(purchaseDateMillis == 0L
                ? getString(R.string.choose_date)
                : UsageRepository.formatDate(purchaseDateMillis));
    }

    private void scan() {
        if (!UsagePermission.isGranted(this)) {
            openUsageAccessSettings();
            return;
        }
        if (purchaseDateMillis == 0L) {
            Toast.makeText(this, "Choose when you bought or first used the phone.", Toast.LENGTH_LONG).show();
            return;
        }

        setLoading(true);
        long requestedStart = purchaseDateMillis;
        executor.execute(() -> {
            try {
                UsageRepository.ScanResult result = new UsageRepository(this).scan(requestedStart);
                runOnUiThread(() -> showResult(result));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    resultHeadline.setText("The scan could not finish");
                    resultDetails.setText(error.getMessage() == null
                            ? "Android did not return usage data. Check Usage access and try again."
                            : error.getMessage());
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        scanButton.setEnabled(!loading && UsagePermission.isGranted(this));
        dateButton.setEnabled(!loading);
    }

    private void showResult(UsageRepository.ScanResult result) {
        lastResult = result;
        setLoading(false);
        resultHeadline.setText(UsageRepository.formatDuration(result.lifetimeEstimateMs));

        if (result.lifetimeEstimateMs == 0L) {
            resultDetails.setText("Android returned no historical foreground-use summary for the selected period. This can happen when access was just granted, usage was cleared, or the phone maker keeps less history.");
        } else {
            String oldest = result.oldestAvailable == null
                    ? "unknown"
                    : UsageRepository.formatDate(result.oldestAvailable);
            resultDetails.setText(
                    "Estimated app foreground time since " + UsageRepository.formatDate(result.purchaseDate) +
                    ". The oldest surviving aggregate overlaps: " + oldest + ".\n\n" +
                    "This is not a recovered Digital Wellbeing database. Old daily detail may already be gone, removed apps may be missing, and split-screen use can overlap."
            );
        }

        historyRows.removeAllViews();
        for (UsageRepository.PeriodUsage period : result.years) {
            addHistoryRow(period.label, UsageRepository.formatDuration(period.durationMs), period.source);
        }

        if (!result.months.isEmpty()) {
            TextView monthTitle = new TextView(this);
            monthTitle.setText("RECENT MONTHS");
            monthTitle.setTextColor(getColor(R.color.accent));
            monthTitle.setTextSize(12f);
            monthTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            monthTitle.setPadding(0, dp(18), 0, dp(4));
            historyRows.addView(monthTitle);
            for (int i = result.months.size() - 1; i >= 0; i--) {
                UsageRepository.PeriodUsage month = result.months.get(i);
                addHistoryRow(month.label, UsageRepository.formatDuration(month.durationMs), month.source);
            }
        }

        if (!result.archivedDays.isEmpty()) {
            TextView archiveTitle = new TextView(this);
            archiveTitle.setText("PRIVATE DAILY ARCHIVE");
            archiveTitle.setTextColor(getColor(R.color.accent));
            archiveTitle.setTextSize(12f);
            archiveTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            archiveTitle.setPadding(0, dp(18), 0, dp(4));
            historyRows.addView(archiveTitle);

            int first = Math.max(0, result.archivedDays.size() - 10);
            for (int i = result.archivedDays.size() - 1; i >= first; i--) {
                HistoryDb.DayEntry day = result.archivedDays.get(i);
                addHistoryRow(UsageRepository.formatDate(day.dayStart),
                        UsageRepository.formatDuration(day.durationMs), "saved on this phone");
            }
        }

        exportButton.setVisibility(View.VISIBLE);
        ArchiveScheduler.schedule(this);
    }

    private void addHistoryRow(String title, String value, String caption) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView primary = new TextView(this);
        primary.setText(title + "  •  " + value);
        primary.setTextColor(getColor(R.color.text_primary));
        primary.setTextSize(17f);
        primary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(primary);

        TextView secondary = new TextView(this);
        secondary.setText(caption);
        secondary.setTextColor(getColor(R.color.text_secondary));
        secondary.setTextSize(13f);
        row.addView(secondary);
        historyRows.addView(row);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void beginExport() {
        if (lastResult == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "screen-time-history.csv");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK || data == null
                || data.getData() == null || lastResult == null) return;

        try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null) throw new IllegalStateException("Could not open the chosen file.");
            output.write(lastResult.toCsv().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Export failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
