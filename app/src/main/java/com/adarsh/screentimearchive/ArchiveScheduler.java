package com.adarsh.screentimearchive;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

final class ArchiveScheduler {
    private static final int JOB_ID = 43017;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private ArchiveScheduler() {}

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(context, ArchiveJobService.class))
                .setPeriodic(DAY_MS)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    static boolean isScheduled(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        return scheduler != null && scheduler.getPendingJob(JOB_ID) != null;
    }
}
