package com.liskovsoft.sharedutils.mylogger;

/**
 * Minimal logger shim to satisfy imports used in the project.
 * Uses android.util.Log by fully qualified name to avoid name clash with this class.
 */
public final class Log {
    private static final String DEFAULT_TAG = "szzdtv";

    private Log() {}

    public static void d(String tag, String msg) {
        android.util.Log.d(tag != null ? tag : DEFAULT_TAG, msg);
    }

    public static void i(String tag, String msg) {
        android.util.Log.i(tag != null ? tag : DEFAULT_TAG, msg);
    }

    public static void e(String tag, String msg) {
        android.util.Log.e(tag != null ? tag : DEFAULT_TAG, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        android.util.Log.e(tag != null ? tag : DEFAULT_TAG, msg, t);
    }

    public static void w(String tag, String msg) {
        android.util.Log.w(tag != null ? tag : DEFAULT_TAG, msg);
    }
}
