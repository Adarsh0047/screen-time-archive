package com.adarsh.screentimearchive;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DaytraceWidget extends AppWidgetProvider {
    private static final String ACTION_REFRESH =
            "com.adarsh.screentimearchive.WIDGET_REFRESH";
    private static final String ACTION_DATA_CHANGED =
            "com.adarsh.screentimearchive.WIDGET_DATA_CHANGED";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refreshAsync(context, manager, ids, true, goAsync());
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (!ACTION_REFRESH.equals(action) && !ACTION_DATA_CHANGED.equals(action)) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, DaytraceWidget.class));
        refreshAsync(context, manager, ids, ACTION_REFRESH.equals(action), goAsync());
    }

    static void requestUpdate(Context context) {
        Intent intent = new Intent(context, DaytraceWidget.class)
                .setAction(ACTION_DATA_CHANGED);
        context.sendBroadcast(intent);
    }

    private static void refreshAsync(Context context, AppWidgetManager manager,
                                     int[] ids, boolean collect,
                                     PendingResult pendingResult) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (collect && UsagePermission.isGranted(app)) {
                    new UsageRepository(app).archiveCurrentDays();
                }
                for (int id : ids) manager.updateAppWidget(id, build(app));
            } catch (Exception error) {
                ArchiveHealth.recordFailure(app, error);
                for (int id : ids) manager.updateAppWidget(id, build(app));
            } finally {
                pendingResult.finish();
            }
        }, "daytrace-widget").start();
    }

    private static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.daytrace_widget);
        long dayStart = UsageRepository.atStartOfDay(LocalDate.now());
        HistoryDb.DayEntry today;
        Map<String, Long> apps;
        try (HistoryDb db = new HistoryDb(context)) {
            today = db.getDay(dayStart);
            apps = db.getApps(dayStart);
        }

        long duration = today == null ? 0L : today.durationMs;
        int targetMinutes = context.getSharedPreferences(
                "screen_time_settings", Context.MODE_PRIVATE).getInt("target", 360);
        int progress = (int) Math.min(100L,
                duration / 60_000L * 100L / Math.max(1, targetMinutes));
        views.setTextViewText(R.id.widget_time,
                today == null ? "No data yet" : UsageRepository.formatDuration(duration));
        views.setProgressBar(R.id.widget_progress, 100, progress, false);
        views.setTextViewText(R.id.widget_target,
                progress + "% of " + UsageRepository.formatDuration(targetMinutes * 60_000L));

        long success = ArchiveHealth.lastSuccess(context);
        String update = success == 0L ? "Never synced"
                : "Updated " + DateUtils.getRelativeTimeSpanString(success,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        views.setTextViewText(R.id.widget_status,
                (ArchiveHealth.isStale(context) ? "● Needs sync · " : "● Healthy · ") + update);

        List<Map.Entry<String, Long>> top = new ArrayList<>(apps.entrySet());
        bindApp(context, views, top, 0, R.id.widget_app_1);
        bindApp(context, views, top, 1, R.id.widget_app_2);
        bindApp(context, views, top, 2, R.id.widget_app_3);

        Intent open = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 70, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent refresh = new Intent(context, DaytraceWidget.class).setAction(ACTION_REFRESH);
        views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(
                context, 71, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }

    private static void bindApp(Context context, RemoteViews views,
                                List<Map.Entry<String, Long>> apps,
                                int index, int viewId) {
        if (index >= apps.size()) {
            views.setViewVisibility(viewId, index == 0 ? View.VISIBLE : View.GONE);
            if (index == 0) views.setTextViewText(viewId, "App details will appear after sync");
            return;
        }
        Map.Entry<String, Long> app = apps.get(index);
        String name = app.getKey();
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(name, 0);
            name = context.getPackageManager().getApplicationLabel(info).toString();
        } catch (Exception ignored) {}
        views.setViewVisibility(viewId, View.VISIBLE);
        views.setTextViewText(viewId,
                (index + 1) + "  " + name + "  ·  " + UsageRepository.formatDuration(app.getValue()));
    }
}
