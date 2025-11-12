package com.liskovsoft.sharedutils.mylogger;

/**
 * Minimal logging wrapper providing LOG_TYPE_SYSTEM constant and simple d/e methods.
 * This implementation avoids Java import alias (which is invalid in Java) and uses android.util.Log directly.
 * Replace with the real sharedutils.mylogger.Log implementation when available.
 */
public final class Log {
    public static final String LOG_TYPE_SYSTEM = "system";

    private Log() {}

    public static void d(String tag, String msg) {
        android.util.Log.d(tag, msg == null ? "" : msg);
    }

    public static void e(String tag, String msg) {
        android.util.Log.e(tag, msg == null ? "" : msg);
    }

    public static void e(String tag, Throwable t) {
        if (t == null) {
            android.util.Log.e(tag, "");
        } else {
            android.util.Log.e(tag, android.util.Log.getStackTraceString(t));
        }
    }
}
