package com.liskovsoft.sharedutils.helpers;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * Minimal AppInfoHelpers shim used only to satisfy compile-time references.
 * Expand with real implementations if you need to query packages at runtime.
 */
public final class AppInfoHelpers {
    private AppInfoHelpers() {}

    public static boolean isPackageInstalled(Context ctx, String packageName) {
        if (ctx == null || packageName == null) return false;
        try {
            PackageManager pm = ctx.getPackageManager();
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String getVersionName(Context ctx, String packageName) {
        if (ctx == null || packageName == null) return "";
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(packageName, 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
