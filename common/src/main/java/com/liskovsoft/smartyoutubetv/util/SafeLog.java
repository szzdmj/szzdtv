package com.liskovsoft.smartyoutubetv.util;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Safe logging helper that:
 * - always logs to android.util.Log
 * - at runtime, if com.szzdmj.nanohttpd.CrashLogger exists, will invoke CrashLogger.i/w via reflection
 *
 * Placing this in common module avoids compile-time dependency on com.szzdmj.nanohttpd.
 */
public final class SafeLog {
    private static final String TAG = "SafeLog";
    private static final String CRASH_LOGGER_CLASS = "com.szzdmj.nanohttpd.CrashLogger";

    private static Method sInfoMethod;
    private static Method sWarnMethod;
    private static boolean sInitialized = false;

    private SafeLog() {}

    private static synchronized void init() {
        if (sInitialized) return;
        sInitialized = true;
        try {
            Class<?> clazz = Class.forName(CRASH_LOGGER_CLASS);
            try {
                sInfoMethod = clazz.getMethod("i", String.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                sWarnMethod = clazz.getMethod("w", String.class, Throwable.class);
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {
            // CrashLogger not present; that's fine
        }
    }

    public static void i(String msg) {
        // Always log to android.util.Log for visibility
        try { Log.i(TAG, msg); } catch (Throwable ignored) {}
        init();
        if (sInfoMethod != null) {
            try { sInfoMethod.invoke(null, msg); } catch (Throwable ignored) {}
        }
    }

    public static void w(String msg, Throwable t) {
        try { Log.w(TAG, msg, t); } catch (Throwable ignored) {}
        init();
        if (sWarnMethod != null) {
            try { sWarnMethod.invoke(null, msg, t); } catch (Throwable ignored) {}
        }
    }
}
