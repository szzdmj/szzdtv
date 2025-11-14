package com.liskovsoft.sharedutils.mylogger;

/**
 * Minimal logger shim to satisfy imports used in the project.
 * Uses android.util.Log by fully qualified name to avoid name clash with this class.
 * Added small compatibility surface (constants and Throwable overloads) required by callers.
 */
public final class Log {
    private static final String DEFAULT_TAG = "szzdtv";

    // compatibility constant(s) used by SmartPreferences
    public static final String LOG_TYPE_SYSTEM = "system";
    // add more constants if callers expect them:
    public static final String LOG_TYPE_VERBOSE = "verbose";

    private Log() {}

    public static void d(String tag, String msg) {
        android.util.Log.d(tag != null ? tag : DEFAULT_TAG, msg);
    }

    public static void i(String tag, String msg) {
        android.util.Log.i(tag != null ? tag : DEFAULT_TAG, msg);
    }

    public static void w(String tag, String msg) {
        android.util.Log.w(tag != null ? tag : DEFAULT_TAG, msg);
    }

    public static void e(String tag, String msg) {
        android.util.Log.e(tag != null ? tag : DEFAULT_TAG, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        android.util.Log.e(tag != null ? tag : DEFAULT_TAG, msg, t);
    }

    // Compatibility overloads: callers sometimes do Log.e(TAG, exception)
    public static void e(String tag, Throwable t) {
        android.util.Log.e(tag != null ? tag : DEFAULT_TAG, "", t);
    }

    public static void i(String tag, Throwable t) {
        android.util.Log.i(tag != null ? tag : DEFAULT_TAG, "", t);
    }
}
