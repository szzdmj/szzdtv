package com.liskovsoft.sharedutils.mylogger;

import android.util.Log as AndroidLog;

/**
 * Minimal logging wrapper providing LOG_TYPE_SYSTEM constant and simple d/e methods.
 * If your project expects more features from mylogger.Log, replace this with real implementation.
 */
public final class Log {
    public static final String LOG_TYPE_SYSTEM = "system";

    private Log() {}

    public static void d(String tag, String msg) {
        AndroidLog.d(tag, msg == null ? "" : msg);
    }

    public static void e(String tag, String msg) {
        AndroidLog.e(tag, msg == null ? "" : msg);
    }

    public static void e(String tag, Throwable t) {
        if (t == null) {
            AndroidLog.e(tag, "");
        } else {
            AndroidLog.e(tag, android.util.Log.getStackTraceString(t));
        }
    }
}
