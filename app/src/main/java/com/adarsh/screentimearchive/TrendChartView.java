package com.adarsh.screentimearchive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TrendChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<HistoryDb.DayEntry> days = new ArrayList<>();
    private final DateTimeFormatter label = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());

    public TrendChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMinimumHeight(dp(210));
    }

    void setDays(List<HistoryDb.DayEntry> values, int maxDays) {
        days.clear();
        int first = Math.max(0, values.size() - maxDays);
        days.addAll(values.subList(first, values.size()));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (days.isEmpty()) {
            paint.setColor(getResources().getColor(R.color.text_secondary, null));
            paint.setTextSize(dp(14));
            canvas.drawText("Daily history will appear automatically", dp(12), getHeight() / 2f, paint);
            return;
        }
        float left = dp(8), right = getWidth() - dp(8), top = dp(12), bottom = getHeight() - dp(34);
        long max = 1;
        for (HistoryDb.DayEntry day : days) max = Math.max(max, day.durationMs);
        float gap = dp(5);
        float width = Math.max(dp(5), (right - left - gap * (days.size() - 1)) / days.size());
        paint.setColor(getResources().getColor(R.color.chart_grid, null));
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(left, bottom, right, bottom, paint);
        paint.setColor(getResources().getColor(R.color.accent, null));
        for (int i = 0; i < days.size(); i++) {
            float x = left + i * (width + gap);
            float height = (bottom - top) * days.get(i).durationMs / (float) max;
            canvas.drawRoundRect(new RectF(x, bottom - height, x + width, bottom), dp(4), dp(4), paint);
        }
        paint.setColor(getResources().getColor(R.color.text_secondary, null));
        paint.setTextSize(dp(11));
        paint.setTextAlign(Paint.Align.LEFT);
        HistoryDb.DayEntry first = days.get(0);
        canvas.drawText(date(first), left, getHeight() - dp(10), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(date(days.get(days.size() - 1)), right, getHeight() - dp(10), paint);
    }

    private String date(HistoryDb.DayEntry entry) {
        return Instant.ofEpochMilli(entry.dayStart).atZone(ZoneId.systemDefault()).toLocalDate().format(label);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
