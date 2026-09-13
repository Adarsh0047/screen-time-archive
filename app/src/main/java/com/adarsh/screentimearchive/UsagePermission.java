package com.adarsh.screentimearchive;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;

final class UsagePermission {
    private UsagePermission() {}

    static boolean isGranted(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
