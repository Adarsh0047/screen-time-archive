package com.adarsh.screentimearchive;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

final class ArchiveScheduler {
    private static final int OLD_JOB_ID = 43017;
    private static final int JOB_ID = 43018;
    private static final long SIX_HOURS_MS = 6L * 60L * 60L * 1000L;
    private static final long FLEX_MS = 60L * 60L * 1000L;

    private ArchiveScheduler() {}

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        scheduler.cancel(OLD_JOB_ID);
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(context, ArchiveJobService.class))
                .setPeriodic(SIX_HOURS_MS, FLEX_MS)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    static boolean isScheduled(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        return scheduler != null && scheduler.getPendingJob(JOB_ID) != null;
    }
}
