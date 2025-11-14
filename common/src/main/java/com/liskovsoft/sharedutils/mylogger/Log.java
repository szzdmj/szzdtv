package com.liskovsoft.sharedutils.mylogger;

import android.util.Log as AndroidLog;

/**
 * Minimal logger shim to satisfy imports.
 * Replace with project's real logger implementation if available.
 */
public final class Log {
    private static final String TAG = "szzd-log";

    private Log() {}

    public static void d(String tag, String msg) {
        AndroidLog.d(tag != null ? tag : TAG, msg);
    }

    public static void i(String tag, String msg) {
        AndroidLog.i(tag != null ? tag : TAG, msg);
    }

    public static void e(String tag, String msg) {
        AndroidLog.e(tag != null ? tag : TAG, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        AndroidLog.e(tag != null ? tag : TAG, msg, t);
    }
}
