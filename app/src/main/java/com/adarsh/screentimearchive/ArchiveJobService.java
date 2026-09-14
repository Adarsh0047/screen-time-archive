package com.adarsh.screentimearchive;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArchiveJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public boolean onStartJob(JobParameters params) {
        executor.execute(() -> {
            boolean retry = false;
            try {
                if (UsagePermission.isGranted(this)) {
                    new UsageRepository(this).archiveRecentDays();
                } else {
                    ArchiveHealth.recordFailure(this,
                            new IllegalStateException("Usage Access is disabled"));
                }
            } catch (Exception error) {
                ArchiveHealth.recordFailure(this, error);
                retry = true;
            }
            DaytraceWidget.requestUpdate(this);
            jobFinished(params, retry);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
